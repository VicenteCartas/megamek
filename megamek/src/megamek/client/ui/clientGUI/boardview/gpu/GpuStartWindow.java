/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.game.IGame;
import megamek.common.scenario.Scenario;
import megamek.logging.MMLogger;

/**
 * MegaMek's start screen, the first scene of the one native (libGDX) window ({@link GpuHost}): the greeting, the art
 * and the main menu. The Swing main frame stays hidden and owns the forms the menu opens, which the window adopts
 * natively so they stay above it. A board, the map editor or the UI library takes the window's place when it opens
 * and the start screen comes back, without the greeting, when they close; so does the game lobby, from hosting or
 * joining a game until leaving it. There is no Swing fallback: if OpenGL cannot start, MegaMek says so and exits.
 */
public final class GpuStartWindow {
    private static final MMLogger LOGGER = MMLogger.create(GpuStartWindow.class);

    /** What the start screen asks MegaMek to do; each runs on the Swing thread. */
    public enum Action {
        QUICK_LOAD, MAP_EDITOR, OPEN_MAP, UNIT_BROWSER,
        RANDOM_ARMY, SKIN_EDITOR, SETTINGS, HELP_CONTENTS, HELP_SKINNING, REPORT_BUG, COPY_SYSTEM_DATA, RESET_PROMPTS,
        ABOUT, QUIT
    }

    /**
     * A new game the Host page asks MegaMek to host, its inputs already checked.
     *
     * @param password   the game's password, or blank for an open game
     * @param metaserver the server browser's URL, or blank when the game is not listed
     */
    public record HostRequest(String playerName, String password, int port, boolean register, String metaserver) { }

    /**
     * A game the Connect page asks MegaMek to join, its inputs already checked.
     *
     * @param bot whether a bot joins, set up in the bot dialog, rather than the player
     * @param sbf whether the game is Strategic BattleForce; there is no SBF bot
     */
    public record ConnectRequest(String playerName, String serverAddress, int port, boolean bot, boolean sbf) {
        public ConnectRequest {
            if (bot && sbf) {
                throw new IllegalArgumentException("A bot can't join a Strategic BattleForce game");
            }
        }
    }

    /**
     * A saved game the Load game page asks MegaMek to host, its inputs already checked.
     *
     * @param host    the server's settings and the save's player the host takes, or the observer's name
     * @param observe whether the host only watches, under a name none of the save's players has
     * @param bots    the save's players a bot played, which a bot takes back; never the host's own player
     */
    public record LoadRequest(File saveGame, HostRequest host, boolean observe, List<String> bots) {
        public LoadRequest {
            Objects.requireNonNull(saveGame);
            Objects.requireNonNull(host);
            bots = List.copyOf(bots);
            if (!observe && bots.contains(host.playerName())) {
                throw new IllegalArgumentException("The host's own player can't get a bot");
            }
        }
    }

    /** Who plays one of a scenario's players. */
    public enum Role {
        /** The host. */
        YOU,
        /** Another person, who connects to the host. */
        HUMAN,
        /** A bot the host starts. */
        BOT
    }

    /**
     * A scenario the briefing asks MegaMek to host, with the game already made from it.
     *
     * @param roles      who plays each of the game's players, in the game's player order
     * @param playerName the host's name: the player they take, or the observer's name when they take none
     * @param password   the game's password, or blank for an open game
     */
    public record ScenarioRequest(Scenario scenario, IGame game, List<Role> roles, String playerName, String password,
          int port) {
        public ScenarioRequest {
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(game);
            roles = List.copyOf(roles);
            if (roles.size() != game.getPlayersList().size()) {
                throw new IllegalArgumentException("Each of the scenario's players needs a role");
            }
            if (roles.stream().filter(role -> role == Role.YOU).count() > 1) {
                throw new IllegalArgumentException("The host takes one player at most");
            }
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("The host needs a name");
            }
        }

        /** Whether the host takes one of the scenario's players rather than watching. */
        public boolean hasSlot() {
            return roles.contains(Role.YOU);
        }
    }

    private static GpuStartWindow open;
    private static JFrame anchor;
    private static Consumer<Action> actions;
    private static Consumer<HostRequest> hosts;
    private static Consumer<ConnectRequest> connects;
    private static Consumer<LoadRequest> loads;
    private static Consumer<ScenarioRequest> scenarios;
    /** The window a start action launched, until it closes; the start screen takes no input meanwhile. */
    private static volatile Window launched;

    private final boolean greet;
    private final Set<Dialog> modalDialogs = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> dialogOnTop = new HashMap<>();
    private final AWTEventListener dialogListener = this::dialogEvent;
    /** Whether a modal form is open; the GPU thread reads it to send clicks on the window to that form. */
    private volatile boolean modalOpen;
    /** Whether the screen's listener exists: from its creation until it is disposed. */
    private volatile boolean live;
    private volatile GpuNativeDialogOwner nativeOwner;
    private volatile boolean closeRequested;
    private Runnable afterClose;
    /** EDT: what to run once the intro finishes, and whether it has. */
    private Runnable onSettled;
    private boolean settled;
    private volatile GpuStartScreen screen;
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            screen = new GpuStartScreen(GpuStartWindow.this, greet, GUIPreferences.getInstance());
            screen.setInputBlocked(inputBlocked());
            return screen;
        }

        @Override
        public String title() {
            return Messages.getString("StartScreen.title");
        }

        @Override
        public void started(long windowHandle) {
            nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
            SwingUtilities.invokeLater(GpuStartWindow.this::shown);
        }

        @Override
        public void closeRequested() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuStartWindow.this::frontModal);
            } else if (launched != null) {
                SwingUtilities.invokeLater(GpuStartWindow::frontLaunched);
            } else {
                dispatch(Action.QUIT);
            }
        }

        @Override
        public void focusGained() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuStartWindow.this::frontModal);
            }
        }

        @Override
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    private GpuStartWindow(boolean greet) {
        this.greet = greet;
    }

    /**
     * EDT: sets the hidden frame that owns the forms the start screen opens and the handlers of its actions and its
     * Host, Connect, Load game and briefing pages. Call once before {@link #open}; MegaMek's main frame does it even
     * when it starts without the screen (a command-line game), so the screen can open when that game ends.
     */
    public static void install(JFrame owner, Consumer<Action> handler, Consumer<HostRequest> hostHandler,
          Consumer<ConnectRequest> connectHandler, Consumer<LoadRequest> loadHandler,
          Consumer<ScenarioRequest> scenarioHandler) {
        anchor = owner;
        actions = handler;
        hosts = hostHandler;
        connects = connectHandler;
        loads = loadHandler;
        scenarios = scenarioHandler;
        // MegaMek's own window lives from the greeting to Quit; scenes come and go in it, and the start screen shows
        // whenever none does
        GpuHost.keepOpen(true);
        GpuHost.whenIdle(GpuStartWindow::reopen);
    }

    /** EDT: opens the start window, with the greeting when {@code greet}, or brings the open one to the front. */
    public static void open(boolean greet) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> open(greet));
            return;
        }
        if (anchor == null) {
            throw new IllegalStateException("Install the start window's owner before opening it");
        }
        if (GpuBoardWindow.isShowingAny() || GpuUiLibraryWindow.isShowing() || GpuLobbyWindow.isShowing()) {
            // The scene holding the window stays; the start screen comes back when it ends (GpuHost.whenIdle)
            return;
        }
        if (open != null) {
            if (open.closeRequested || !GpuHost.isShowing(open.scene)) {
                // Another scene has taken the window; the screen comes back once this one has finished
                closeThen(() -> open(greet));
            } else {
                GpuHost.focus();
            }
            return;
        }
        GpuStartWindow window = new GpuStartWindow(greet);
        open = window;
        Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
        GpuHost.show(window.scene);
    }

    /** EDT: reopens the start screen, without the greeting, after a game, an editor or a tool closes. */
    public static void reopen() {
        if (anchor != null) {
            open(false);
        }
    }

    /**
     * EDT: suspends the start screen while {@code window}, which a start action launched, is open, so another game
     * can't start under it; {@link #resume} ends that. The screen may come and go meanwhile and stays suspended.
     */
    public static void suspendWhile(Window window) {
        launched = window;
        inputChanged();
    }

    /** EDT: the launched window has closed; the start screen takes input again. */
    public static void resume() {
        launched = null;
        inputChanged();
        startEnded();
    }

    /** EDT: whether a launched window has the start screen suspended. */
    public static boolean isSuspended() {
        return launched != null;
    }

    private static void inputChanged() {
        GpuStartWindow window = open;
        if (window != null) {
            window.post(() -> window.screen.setInputBlocked(window.inputBlocked()));
        }
    }

    private boolean inputBlocked() {
        return modalOpen || launched != null;
    }

    private static void frontLaunched() {
        Window window = launched;
        if (window != null && window.isShowing()) {
            window.toFront();
        }
    }

    /** EDT: true while the start window is open or opening. */
    public static boolean isOpen() {
        return open != null;
    }

    /**
     * EDT: ends the start screen and runs {@code then} on the EDT once it is gone. Returns false, and runs nothing,
     * when it is not open. Runs every {@code then} asked for while it closes, in order.
     */
    public static boolean closeThen(Runnable then) {
        if (open == null) {
            return false;
        }
        Runnable earlier = open.afterClose;
        open.afterClose = earlier == null ? then : () -> {
            earlier.run();
            then.run();
        };
        open.requestClose();
        return true;
    }

    /** EDT: reopens the start screen in place of showing {@code window} when it is the start screen's hidden owner. */
    static boolean reopenFor(Window window) {
        if (window == null || window != anchor) {
            return false;
        }
        reopen();
        return true;
    }

    /** EDT: lays the open screen out again, as after the GUI scale changes. */
    public static void refresh() {
        GpuStartWindow window = open;
        if (window != null) {
            window.post(() -> window.screen.rescale());
        }
    }

    /** Any thread: runs {@code work} on the GPU thread while the screen's listener exists, else nothing. */
    private void post(Runnable work) {
        GpuHost.post(() -> {
            if (live) {
                work.run();
            }
        });
    }

    /**
     * GLFW screen coordinates are device pixels on Windows, while Swing's are scaled by each display's scale around
     * that display's origin; macOS and X11 share theirs. Uses the display under the rectangle's centre.
     */
    static Rectangle toSwing(Rectangle glfw) {
        if (SharedLibraryLoader.os != Os.Windows) {
            return new Rectangle(glfw);
        }
        try {
            List<Rectangle> screens = new ArrayList<>();
            List<Double> scales = new ArrayList<>();
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration configuration = device.getDefaultConfiguration();
                screens.add(configuration.getBounds());
                scales.add(configuration.getDefaultTransform().getScaleX());
            }
            return toSwing(glfw, screens, scales);
        } catch (HeadlessException headless) {
            return new Rectangle(glfw);
        }
    }

    /**
     * {@code glfw} in Swing coordinates, given each display's Swing bounds (device pixel origin, scaled size) and scale;
     * the first display, the primary, when none holds its centre.
     */
    static Rectangle toSwing(Rectangle glfw, List<Rectangle> screens, List<Double> scales) {
        if (screens.isEmpty()) {
            return new Rectangle(glfw);
        }
        double centreX = glfw.getCenterX();
        double centreY = glfw.getCenterY();
        int at = 0;
        for (int i = 0; i < screens.size(); i++) {
            Rectangle screen = screens.get(i);
            double scale = scales.get(i);
            if (centreX >= screen.x && centreX < screen.x + screen.width * scale
                  && centreY >= screen.y && centreY < screen.y + screen.height * scale) {
                at = i;
                break;
            }
        }
        Rectangle screen = screens.get(at);
        double scale = scales.get(at);
        return new Rectangle((int) Math.round(screen.x + (glfw.x - screen.x) / scale),
              (int) Math.round(screen.y + (glfw.y - screen.y) / scale),
              (int) Math.round(glfw.width / scale), (int) Math.round(glfw.height / scale));
    }

    private void finished(Throwable failure) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        modalDialogs.clear();
        modalOpen = false;
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
        if (open == this) {
            open = null;
        }
        if (failure != null && !closeRequested) {
            LOGGER.error(failure, "The start screen's native window failed");
            String title = Messages.getString("StartScreen.title");
            if (!GpuHost.everOpened()) {
                JOptionPane.showMessageDialog(null, Messages.getString("StartScreen.glFailure", String.valueOf(failure)),
                      title, JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
            // It ran before, so a fresh window may well work
            String[] choices = { Messages.getString("StartScreen.reopen"), Messages.getString("StartScreen.quit") };
            int choice = JOptionPane.showOptionDialog(null,
                  Messages.getString("StartScreen.failure", String.valueOf(failure)), title,
                  JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, choices, choices[0]);
            if (choice == 0) {
                open(false);
                return;
            }
            System.exit(1);
        }
        Runnable then = afterClose;
        afterClose = null;
        if (then != null) {
            then.run();
        }
    }

    private void requestClose() {
        closeRequested = true;
        GpuHost.end(scene);
    }

    /** GPU thread: the screen's listener is being created. */
    void started() {
        live = true;
        if (closeRequested) {
            GpuHost.end(scene);
        }
    }

    /** Any thread: runs a menu action on the Swing thread. */
    void dispatch(Action action) {
        request(actions, action);
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the game the Host page describes. */
    void host(HostRequest request) {
        start(hosts, request);
    }

    /** Any thread: asks MegaMek, on the Swing thread, to join the game the Connect page describes. */
    void connect(ConnectRequest request) {
        start(connects, request);
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the saved game the Load game page describes. */
    void load(LoadRequest request) {
        start(loads, request);
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the scenario the briefing describes. */
    void scenario(ScenarioRequest request) {
        start(scenarios, request);
    }

    /** Any thread: hands {@code request} to {@code handler} on the Swing thread, unless the screen is suspended. */
    static <T> void request(Consumer<T> handler, T request) {
        if (handler != null) {
            SwingUtilities.invokeLater(() -> {
                if (launched == null) {
                    handler.accept(request);
                }
            });
        }
    }

    /**
     * Any thread: hands a start page's {@code request} to {@code handler} as {@link #request} does. Unless that
     * launched a game's window, however it failed, the page's button that says the game is starting works again.
     */
    private static <T> void start(Consumer<T> handler, T request) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (launched == null && handler != null) {
                    handler.accept(request);
                }
            } finally {
                if (launched == null) {
                    startEnded();
                }
            }
        });
    }

    /** EDT: no game started, or the one that did has closed; the open screen's start button works again. */
    private static void startEnded() {
        GpuStartWindow window = open;
        if (window != null) {
            window.post(() -> window.screen.startEnded());
        }
    }

    /**
     * Any thread: shows the system's file dialog, or a file chooser that is a form of the hidden frame and so above
     * this window where it can't, at {@code directory}, and hands the file the player opens to {@code chosen} on the
     * GPU thread; nothing when they cancel. The start window takes no input while either is open.
     */
    void chooseFile(File directory, String title, FileNameExtensionFilter filter, Consumer<File> chosen) {
        SystemFileDialog.show(new SystemFileDialog.Request(directory, title, filter, false), () -> nativeOwner,
              () -> anchor, busy -> setStartEnabled(!busy), choice -> {
                  if (choice != null) {
                      post(() -> chosen.accept(choice.file()));
                  }
              });
    }

    /**
     * Any thread: runs {@code work} on the Swing thread with the hidden frame that owns the screen's forms, so a form it
     * opens shows above this window, then hands its result to {@code done} on the GPU thread.
     */
    <T> void onSwing(Function<JFrame, T> work, Consumer<T> done) {
        SwingUtilities.invokeLater(() -> {
            T result = work.apply(anchor);
            post(() -> done.accept(result));
        });
    }

    /**
     * Any thread: runs {@code then} on the Swing thread once the open window's intro has finished, so a form doesn't
     * cover the greeting; at once when no window is open.
     */
    public static void whenSettled(Runnable then) {
        if (!SwingUtilities.isEventDispatchThread()) {
            // Queued after a pending open, so it sees the window that open makes
            SwingUtilities.invokeLater(() -> whenSettled(then));
            return;
        }
        GpuStartWindow window = open;
        if (window == null || window.settled) {
            then.run();
        } else {
            window.onSettled = then;
        }
    }

    /**
     * EDT: shows the welcome on the open start screen while the Commander still wants it at each start. True when it
     * is handled: shown, or not wanted; false when no start screen can show it, so the Swing form should.
     */
    public static boolean showWelcome() {
        if (!GUIPreferences.getInstance().getNagForReadme()) {
            return true;
        }
        GpuStartWindow window = open;
        if (window == null || !window.live || window.screen == null) {
            return false;
        }
        window.post(() -> window.screen.welcomeAtStart());
        return true;
    }
    /** GPU thread: the intro has finished, or was skipped. */
    void settled() {
        SwingUtilities.invokeLater(() -> {
            settled = true;
            Runnable then = onSettled;
            onSettled = null;
            if (then != null) {
                then.run();
            }
        });
    }

    /** GPU thread, as the screen's listener is disposed: returns every adopted form to its Swing owner. */
    void disposing() {
        live = false;
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
    }

    private void dialogEvent(AWTEvent event) {
        if (!(event.getSource() instanceof Dialog dialog)) {
            return;
        }
        int id = event.getID();
        if (id == ComponentEvent.COMPONENT_HIDDEN || id == WindowEvent.WINDOW_CLOSED) {
            endModal(dialog);
            GpuNativeDialogOwner owner = nativeOwner;
            if (owner != null) {
                if (id == WindowEvent.WINDOW_CLOSED) {
                    owner.forget(dialog);
                } else {
                    owner.hidden(dialog);
                }
            }
            if (id == WindowEvent.WINDOW_CLOSED) {
                Boolean wasOnTop = dialogOnTop.remove(dialog);
                if (wasOnTop != null) {
                    dialog.setAlwaysOnTop(wasOnTop);
                }
            }
        } else if (id == ComponentEvent.COMPONENT_SHOWN && dialog instanceof FileDialog) {
            // The system's file dialog has no window of AWT's to own, place or float; as a modal form it only blocks
            // the start window's input
            if (dialog.isModal() && belongsToStart(dialog) && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setStartEnabled(false);
            }
        } else if (id == ComponentEvent.COMPONENT_SHOWN && belongsToStart(dialog)) {
            centre(dialog);
            raise(dialog);
        }
    }

    /**
     * A form of the hidden main frame, a dialog without an owner of its own (which Swing gives its hidden shared frame),
     * or a dialog of either: all would otherwise hide behind the start window.
     */
    private static boolean belongsToStart(Dialog dialog) {
        Frame shared;
        try {
            shared = JOptionPane.getRootFrame();
        } catch (HeadlessException headless) {
            shared = null;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == anchor || current == shared) {
                return true;
            }
        }
        return dialog.getOwner() == null;
    }

    /** Centres a form of the hidden frame over the start window; the hidden frame would centre it on the screen. */
    private void centre(Dialog dialog) {
        Rectangle bounds = GpuHost.clientBounds();
        if (bounds == null || dialog.getOwner() instanceof Dialog) {
            return;
        }
        Rectangle swing = toSwing(bounds);
        dialog.setLocation(swing.x + (swing.width - dialog.getWidth()) / 2,
              swing.y + (swing.height - dialog.getHeight()) / 2);
    }

    /**
     * Keeps a form above the start window until either closes: owned natively where the platform allows, otherwise
     * floating on top. A modal form blocks the start window's input as it would its Swing owner's.
     */
    private void raise(Dialog dialog) {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null && (dialog.getOwner() instanceof Dialog parent && parent.isShowing() && owner.owns(parent)
              || owner.adopt(dialog))) {
            Boolean wasOnTop = dialogOnTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
            if (dialog.isModal() && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setStartEnabled(false);
            }
            dialog.toFront();
            return;
        }
        dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
    }

    /** EDT, once the scene has started: the forms that floated meanwhile are owned. */
    private void shown() {
        if (open != this || closeRequested) {
            return;
        }
        adoptFloating();
    }

    /** EDT: owns the forms that floated while the scene started. */
    private void adoptFloating() {
        for (Dialog dialog : new HashSet<>(dialogOnTop.keySet())) {
            if (dialog.isShowing()) {
                centre(dialog);
                raise(dialog);
            }
        }
    }

    private void endModal(Dialog dialog) {
        if (modalDialogs.remove(dialog) && modalDialogs.isEmpty()) {
            setStartEnabled(true);
        }
    }

    /** EDT: brings the newest open modal form to the front when the start window is clicked or asked to close. */
    private void frontModal() {
        Dialog newest = null;
        for (Dialog dialog : modalDialogs) {
            if (dialog.isShowing()) {
                newest = dialog;
            }
        }
        if (newest != null) {
            newest.toFront();
        }
    }

    /**
     * Blocks or restores the start window's input. The native window itself stays enabled: Windows picks the window to
     * activate as a modal form hides, before Swing reports it, and a still disabled owner would hand the activation to
     * another application, flashing the start window away until it took the focus back.
     */
    private void setStartEnabled(boolean enabled) {
        modalOpen = !enabled;
        post(() -> screen.setInputBlocked(inputBlocked()));
    }
}
