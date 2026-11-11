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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
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
 *
 * <p>Other programs of the suite show their own start screen the same way: they {@link #install(JFrame,
 * StartScreenContent) install} what it shows and {@link #uninstall} it once their own windows take over.</p>
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
    /** What the screen shows: MegaMek's own, or another program's that installed its own. */
    private static StartScreenContent installed;
    private static Consumer<Action> actions;
    private static Consumer<HostRequest> hosts;
    private static Consumer<ConnectRequest> connects;
    private static Consumer<LoadRequest> loads;
    private static Consumer<ScenarioRequest> scenarios;
    /** The window a start action launched, until it closes; the start screen takes no input meanwhile. */
    private static volatile Window launched;
    /** EDT: what runs once the launched window closes. */
    private static Runnable launchEnded;
    private static final AtomicReference<String[]> NOTICE = new AtomicReference<>();

    private final boolean greet;
    private final StartScreenContent content;
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
    private final LobbyFiles files = new LobbyFiles(this::chooseRouteFile, new LobbyFiles.Report() {
        @Override
        public void error(LobbyError error, Object... args) {
            String title = error.title();
            message(title == null ? Messages.getString("Lobby.prompt.message") : title, error.message(args));
        }

        @Override
        public void message(String title, String text) {
            GpuStartWindow.this.message(title, text);
        }
    }, new LobbyFiles.Folders());
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            screen = new GpuStartScreen(GpuStartWindow.this, greet, GUIPreferences.getInstance());
            screen.setInputBlocked(inputBlocked());
            return screen;
        }

        @Override
        public String title() {
            return content.title();
        }

        @Override
        public void started(long windowHandle) {
            nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
            SwingUtilities.invokeLater(GpuStartWindow.this::shown);
        }

        @Override
        public void closeRequested() {
            SwingUtilities.invokeLater(GpuStartWindow.this::closeAsked);
        }

        @Override
        public void focusGained() {
            SwingUtilities.invokeLater(() -> {
                dropStaleModals();
                if (modalOpen) {
                    frontModal();
                }
            });
        }

        @Override
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    private GpuStartWindow(boolean greet, StartScreenContent content) {
        this.greet = greet;
        this.content = content;
    }

    /**
     * EDT: sets the hidden frame that owns the forms the start screen opens and the handlers of its actions and its
     * Host, Connect, Load game and briefing pages. Call once before {@link #open}; MegaMek's main frame does it even
     * when it starts without the screen (a command-line game), so the screen can open when that game ends.
     */
    public static void install(JFrame owner, Consumer<Action> handler, Consumer<HostRequest> hostHandler,
          Consumer<ConnectRequest> connectHandler, Consumer<LoadRequest> loadHandler,
          Consumer<ScenarioRequest> scenarioHandler) {
        install(owner, new MegaMekStartContent(handler));
        actions = handler;
        hosts = hostHandler;
        connects = connectHandler;
        loads = loadHandler;
        scenarios = scenarioHandler;
    }

    /**
     * EDT: sets up another program's start screen: the hidden frame that owns the forms its menu opens, and what it
     * shows. Call before {@link #open}. The window then stays open from the greeting until {@link #uninstall}, and the
     * start screen comes back, without the greeting, whenever no other scene holds it.
     */
    public static void install(JFrame owner, StartScreenContent content) {
        anchor = Objects.requireNonNull(owner);
        installed = Objects.requireNonNull(content);
        // The window takes its icons as it opens, and its taskbar button keeps them
        GpuBoardWindow.useIcons(content.icons());
        if (!owner.isDisplayable()) {
            // The native window's thread is a daemon and AWT shuts down without a displayable window, so the hidden
            // frame's peer is what keeps the program running while only the start screen shows
            owner.addNotify();
        }
        actions = null;
        hosts = null;
        connects = null;
        loads = null;
        scenarios = null;
        // The window lives from the greeting to Quit; scenes come and go in it, and the start screen shows whenever
        // none does
        GpuHost.keepOpen(true);
        GpuHost.whenIdle(GpuStartWindow::reopen);
    }

    /**
     * EDT: ends the start screen for good, as when another program's own windows take over from it, and runs
     * {@code then} on the EDT once it is gone. The native window closes when no other scene holds it, and later scenes
     * open and close it by themselves. {@link #install} sets the screen up again.
     */
    public static void uninstall(Runnable then) {
        anchor = null;
        installed = null;
        actions = null;
        hosts = null;
        connects = null;
        loads = null;
        scenarios = null;
        NOTICE.set(null);
        GpuHost.keepOpen(false);
        GpuHost.whenIdle(null);
        if (!closeThen(then)) {
            then.run();
        }
    }

    /** Any thread: shows the open screen's main menu again, as after what {@link StartScreenContent#menu} lists changed. */
    public static void menuChanged() {
        SwingUtilities.invokeLater(() -> {
            GpuStartWindow window = open;
            if (window != null) {
                window.post(() -> window.screen.menuChanged());
            }
        });
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
        GpuStartWindow window = new GpuStartWindow(greet, installed);
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
     * can't start under it; {@link #launchClosed} ends that and runs {@code ended}. The screen may come and go
     * meanwhile and stays suspended.
     */
    public static void suspendWhile(Window window, Runnable ended) {
        launched = window;
        launchEnded = ended;
        inputChanged();
    }

    /**
     * EDT: {@code window}, which a start action launched, has closed: runs what was to run then, once, and the start
     * screen takes input again even when that fails.
     */
    public static void launchClosed(Window window) {
        if (window == null || window != launched) {
            return;
        }
        Runnable ended = launchEnded;
        launchEnded = null;
        try {
            if (ended != null) {
                ended.run();
            }
        } finally {
            if (launched == window) {
                resume();
            }
        }
    }

    /** EDT: the launched window has closed; the start screen takes input again. */
    public static void resume() {
        launched = null;
        launchEnded = null;
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

    /** EDT: the window's close button: brings what holds the screen to the front, or quits when nothing does. */
    private void closeAsked() {
        dropStaleModals();
        if (modalOpen) {
            frontModal();
        } else if (launched != null) {
            frontLaunched();
        } else {
            quit();
        }
    }

    /**
     * EDT: brings the launched window, or the newest form it shows, to the front. A game's window stays hidden while
     * its scenes show in this one; when it shows nothing, its close is asked for as its own close button would, so the
     * player can always leave it from here.
     */
    private static void frontLaunched() {
        Window window = launched;
        if (window == null) {
            return;
        }
        if (!window.isDisplayable()) {
            LOGGER.warn("The start screen's launched window closed without telling it; the start screen resumes");
            launchClosed(window);
        } else if (window.isShowing()) {
            window.toFront();
        } else {
            Window shown = newestShowing(window);
            if (shown != null) {
                shown.toFront();
            } else {
                LOGGER.warn("The start screen is held by a hidden game that shows nothing; asking to leave it");
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
            }
        }
    }

    /** The innermost showing window that {@code owner} owns, newest first; null when none shows. */
    private static Window newestShowing(Window owner) {
        Window newest = null;
        for (Window owned : owner.getOwnedWindows()) {
            Window inner = newestShowing(owned);
            if (inner != null) {
                newest = inner;
            } else if (owned.isShowing()) {
                newest = owned;
            }
        }
        return newest;
    }

    /** EDT: true while the start window is open or opening. */
    public static boolean isOpen() {
        return open != null;
    }

    /**
     * EDT: shows Report a Bug natively on the start screen; its button packages {@code client}'s game, when one runs,
     * and the logs. False, showing nothing, when the start screen is not open.
     */
    public static boolean showBugReport(Supplier<Client> client) {
        GpuStartWindow window = open;
        if (window == null) {
            return false;
        }
        PackageBugReportAction packaging = GpuBugReport.packaging(client, window.files,
              card -> window.post(() -> card.accept(window.screen.dialogs)));
        window.post(() -> GpuBugReport.open(window.screen.dialogs, packaging));
        return true;
    }

    /** EDT: shows About natively on the start screen; false, showing nothing, when it is not open. */
    public static boolean showAbout() {
        GpuStartWindow window = open;
        if (window == null) {
            return false;
        }
        window.post(() -> window.screen.dialogs.about());
        return true;
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
            String title = content.title();
            if (!GpuHost.everOpened()) {
                JOptionPane.showMessageDialog(null,
                      Messages.getString("StartScreen.glFailure", String.valueOf(failure), title), title,
                      JOptionPane.ERROR_MESSAGE);
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

    /** Any thread: runs {@code action}, such as a menu entry's, on the Swing thread, unless the screen is suspended. */
    void run(Runnable action) {
        request(Runnable::run, action);
    }

    /** Any thread: Settings, on the Swing thread. */
    void settings() {
        run(content::settings);
    }

    /** Any thread: Quit, on the Swing thread. */
    void quit() {
        run(content::quit);
    }

    /** What the screen shows. */
    StartScreenContent content() {
        return content;
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

    /** The start screen's file routes, such as the briefing's game options files; Swing thread. */
    LobbyFiles files() {
        return files;
    }

    /**
     * EDT: asks for the file of a file route in the system's file dialog, as {@link #chooseFile} does; a file to save
     * over is confirmed on the screen first. {@code chosen} gets the file on the EDT, or null when none.
     */
    private void chooseRouteFile(LobbyFiles.Ask ask, Consumer<File> chosen) {
        SystemFileDialog.show(SystemFileDialog.Request.of(ask), () -> nativeOwner, () -> anchor,
              busy -> setStartEnabled(!busy), choice -> {
                  if (choice == null || !live) {
                      chosen.accept(null);
                  } else if (ask.save() && choice.askReplace()) {
                      File file = choice.file();
                      post(() -> screen.dialogs.confirm(Messages.getString("ChatLounge.map.confirmReplace"),
                            Messages.getString("Lobby.files.replace", file.getName()),
                            () -> SwingUtilities.invokeLater(() -> chosen.accept(file)),
                            () -> SwingUtilities.invokeLater(() -> chosen.accept(null))));
                  } else {
                      chosen.accept(choice.file());
                  }
              });
    }

    /** Any thread: shows an alert over the screen and any dialog on it. */
    void message(String title, String text) {
        post(() -> screen.dialogs.message(title, text));
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

    /**
     * EDT: shows an alert, such as why a game ended, on the start screen as soon as one shows: at once over the open
     * screen, or once it comes back. Returns false, keeping nothing, when MegaMek has no start screen to show it.
     */
    public static boolean notice(String title, String text) {
        if (anchor == null) {
            return false;
        }
        NOTICE.set(new String[] { title, text });
        GpuStartWindow window = open;
        if (window != null) {
            window.post(() -> window.screen.showNotice());
        }
        return true;
    }

    /** Any thread: the alert waiting for the start screen, as its title and text, and forgets it; null when none. */
    static String[] takeNotice() {
        return NOTICE.getAndSet(null);
    }

    /**
     * EDT: shows an error over the open start screen, as {@link LobbyNativeDialogs#error} does; {@code report} runs on
     * the EDT. Returns false, showing nothing, when the screen does not show, so the caller shows it otherwise.
     */
    static boolean showError(String title, String text, String dismissLabel, @Nullable String reportLabel,
          @Nullable Runnable report) {
        GpuStartWindow window = open;
        if (window == null || window.closeRequested || !window.live || window.screen == null) {
            return false;
        }
        Runnable onEdt = report == null ? null : () -> SwingUtilities.invokeLater(report);
        window.post(() -> window.screen.dialogs.error(title, text, dismissLabel, reportLabel, onEdt));
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
            if (dialog.isModal() && belongsHere(dialog) && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setStartEnabled(false);
            }
        } else if (id == ComponentEvent.COMPONENT_SHOWN && belongsHere(dialog)) {
            centre(dialog);
            raise(dialog);
        }
    }

    /**
     * A form of the start screen, or one the launched game shows while its own window stays hidden and no scene of its
     * shows, such as its save question or why its battle view failed: either would otherwise hide behind the start
     * window, which would take no input meanwhile.
     */
    private boolean belongsHere(Dialog dialog) {
        if (belongsToStart(dialog)) {
            return true;
        }
        Window game = launched;
        if (closeRequested || game == null || game.isShowing()) {
            return false;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == game) {
                return true;
            }
        }
        return false;
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

    /** EDT, once the scene has started: the forms that floated meanwhile, or showed before it, are owned. */
    private void shown() {
        if (open != this || closeRequested) {
            return;
        }
        adoptFloating();
        adoptShowing();
    }

    /**
     * EDT: raises the forms that showed before the screen did; their shown event came before its listener. A game's
     * battle view that fails asks what to do while the start screen comes back, for one.
     */
    private void adoptShowing() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && !(dialog instanceof FileDialog) && dialog.isShowing()
                  && !dialogOnTop.containsKey(dialog) && belongsHere(dialog)) {
                centre(dialog);
                raise(dialog);
            }
        }
    }

    /**
     * EDT: forgets the modal forms counted as open that no longer show, should their hiding have gone unseen, so the
     * screen never stays blocked by a form nobody can see.
     */
    private void dropStaleModals() {
        for (Dialog dialog : new ArrayList<>(modalDialogs)) {
            if (!dialog.isShowing()) {
                LOGGER.warn("The start screen still counted the form \"{}\" as open; it no longer shows",
                      dialog.getTitle());
                endModal(dialog);
            }
        }
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
