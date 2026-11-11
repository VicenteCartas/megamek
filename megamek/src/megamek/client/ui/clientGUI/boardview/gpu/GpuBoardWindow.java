/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.lobby.GameMasterUnitTools;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.MMAboutDialog;
import megamek.client.ui.dialogs.advancedSearchMap.AdvancedSearchMapDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.settings.ClientSettingsForm;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Entity;
import megamek.common.voting.Poll;
import megamek.logging.MMLogger;
import megamek.server.commands.ClientServerCommand;

/**
 * The battle, the map editor and the map preview as scenes of MegaMek's one native window ({@link GpuHost}), which
 * also keeps a single libGDX context.
 */
public final class GpuBoardWindow {
    /** Kinds of native modal request; FORM carries the long-tail forms. */
    public enum DialogKind { MESSAGE, CHOICE, MULTI, INPUT, FORM }

    /** Input kind of one FORM field. */
    public enum FieldKind { INTEGER, TEXT, CHOICE, CHECKBOX }

    /** One CHOICE or MULTI row; the icon is optional. */
    public record DialogRow(String label, String detail, BoardScene.Pixels icon, boolean enabled) { }

    /**
     * One FORM field: min and max bound an INTEGER, choices list a CHOICE. Changing a {@code live} CHOICE or CHECKBOX
     * answers the form at once with {@link DialogAnswer#CHANGED} and its values, so that the asker can show the form
     * again as the change makes it: other rows, labels or message.
     */
    public record DialogField(String label, FieldKind kind, List<String> choices, int min, int max, String initial,
          boolean live) {
        public DialogField {
            choices = List.copyOf(choices);
        }

        /** A field whose changes wait for the form's buttons. */
        public DialogField(String label, FieldKind kind, List<String> choices, int min, int max, String initial) {
            this(label, kind, choices, min, max, initial, false);
        }
    }

    /**
     * Immutable modal request for the native window. The board source assigns {@code id} when it shows the request,
     * so callers pass 0. An empty {@code checkbox} means none. INPUT reads an integer when min or max is set, and shows
     * that range beside its field. For MULTI, {@code max}, when set, is the most rows the player can tick. Enter
     * presses {@code defaultButton}; a {@code defaultButton} of -1 means Enter presses no button, as in a Swing dialog
     * without a default button. A {@code cancelButton} of -1 means Esc closes the dialog without pressing a button, as
     * a JOptionPane's close box does: its answer's button is then -1, and it still carries the checkbox as the player
     * left it. A MESSAGE may show an {@code image} beside its text: an image file's bytes (PNG, JPEG or BMP), base64
     * encoded; null for none.
     */
    public record DialogRequest(long id, DialogKind kind, String title, String message, boolean monospace,
          List<String> buttons, int defaultButton, int cancelButton, List<DialogRow> rows,
          List<Integer> initiallySelected, String checkbox, boolean checkboxInitial, String initialText,
          Integer min, Integer max, List<DialogField> fields, String image) {
        public DialogRequest {
            buttons = List.copyOf(buttons);
            rows = List.copyOf(rows);
            initiallySelected = List.copyOf(initiallySelected);
            fields = List.copyOf(fields);
        }

        /** A request without an image. */
        public DialogRequest(long id, DialogKind kind, String title, String message, boolean monospace,
              List<String> buttons, int defaultButton, int cancelButton, List<DialogRow> rows,
              List<Integer> initiallySelected, String checkbox, boolean checkboxInitial, String initialText,
              Integer min, Integer max, List<DialogField> fields) {
            this(id, kind, title, message, monospace, buttons, defaultButton, cancelButton, rows, initiallySelected,
                  checkbox, checkboxInitial, initialText, min, max, fields, null);
        }

        DialogRequest withId(long shownId) {
            return new DialogRequest(shownId, kind, title, message, monospace, buttons, defaultButton, cancelButton,
                  rows, initiallySelected, checkbox, checkboxInitial, initialText, min, max, fields, image);
        }
    }

    /** The answer to one request; a null text means the input was cancelled. */
    public record DialogAnswer(int button, List<Integer> selected, String text, boolean checked, List<String> values) {
        /** The button of a FORM answered because a live field changed; its values are the form's as left. */
        public static final int CHANGED = -2;

        public DialogAnswer {
            selected = List.copyOf(selected);
            values = List.copyOf(values);
        }

        /** The same result as closing the Swing dialog (CLOSED_OPTION, false or null), which every caller handles. */
        public static DialogAnswer cancelled(DialogRequest request) {
            return new DialogAnswer(request.cancelButton(), List.of(), null, request.checkboxInitial(), List.of());
        }
    }

    static final boolean DEFAULT_VSYNC = true;
    /** The planar compatibility layer omits wreck sprites when the shared unit renderer supplies them. */
    public static boolean modelsEnabled() { return GpuUnitModels.ENABLED; }
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardWindow.class);
    /** The longest a prompt asked while the board starts waits for it before Swing asks instead. */
    private static final long STARTUP_PROMPT_WAIT_MS = 60_000;
    /** Volatile so that {@link #ask} and {@link #dialogPendingFor} read it without the class monitor. */
    private static volatile GpuBoardWindow active;
    /**
     * The Swing windows the user kept over the battle window (rebuild plan R9, closed by Z1), each with its decision:
     * R6 and swing-inventory Q1 (the menu-level dialogs stay Swing for now); U2's conditions editor is native now.
     * Message boxes and file choosers have no class of their own; {@link #approved} names the kept ones. Everything
     * else is native.
     */
    private static final List<Class<? extends Dialog>> SWING_DIALOGS = List.of(
          // R6: Client Settings, which hold the real settings (user item 3)
          megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog.class);
    private final ClientGUI gui;
    private final BoardClientState initialView;
    private final Supplier<JComponent> panel;
    private final Timer startupTimer;
    private java.util.concurrent.CompletableFuture<?> plantsAhead;
    private volatile BoardSource source;
    private volatile String previewTitle;
    /** EDT-owned source of the current preview; null for a generated or assembled lobby map. */
    private File previewFile;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final Window swingWindow;
    /** Preview windows own their temporary game and leave the browser's modal session intact. */
    private final boolean preview;
    private final BoardEditorSession editor;
    /** The editor's open 2D Preview ({@link #showPreview2D}), or null. EDT. */
    private JDialog preview2D;
    /** Whether View > Renderer offers Legacy 2D; the lobby's preview is 3D only. */
    private boolean offersLegacy2D = true;
    private final Game mapGame;
    private final Map<Dialog, Boolean> dialogOnTop = new IdentityHashMap<>();
    /** Natively owned raised dialogs (Windows, macOS, Linux on X11) instead of floating them on top. Else {@code null}. */
    private volatile GpuNativeDialogOwner nativeOwner;
    /** EDT: natively owned modal dialogs, oldest first; while any is open the board window ignores its input. */
    private final Set<Dialog> modalDialogs = new LinkedHashSet<>();
    /** Whether a modal dialog is open; the GPU thread reads it to send clicks on the window to that dialog. */
    private volatile boolean modalOpen;
    /**
     * The Swing window hides while the GPU view runs, so a Swing dialog owned by it would open behind the native
     * window. A client dialog that opens while the GPU window cannot ask the client's prompts itself (it starts or
     * closes), or one of {@link #allowedOverBattle} while it can, is raised above it instead.
     */
    private final AWTEventListener dialogListener = event -> {
        if (closeWithPreviewOwner(event)) {
            return;
        }
        if ((event.getID() == ComponentEvent.COMPONENT_HIDDEN || event.getID() == WindowEvent.WINDOW_CLOSED)
              && event.getSource() instanceof Dialog hidden) {
            endModal(hidden);
            GpuNativeDialogOwner owner = nativeOwner;
            if (owner != null) {
                if (event.getID() == WindowEvent.WINDOW_CLOSED) {
                    owner.forget(hidden);
                } else {
                    owner.hidden(hidden);
                }
            }
        }
        if (event.getID() == WindowEvent.WINDOW_CLOSED && event.getSource() instanceof Dialog closed) {
            // A disposed dialog is released (with its content, such as the lobby's 2D preview) and keeps its stacking.
            Boolean wasOnTop = dialogOnTop.remove(closed);
            if (wasOnTop != null) {
                closed.setAlwaysOnTop(wasOnTop);
            }
            return;
        }
        if (event.getID() == ComponentEvent.COMPONENT_SHOWN && event.getSource() instanceof FileDialog fileDialog) {
            // The system's file dialog has no window of AWT's to own or float; as a modal dialog it only blocks input
            if (belongsToClient(fileDialog) && fileDialog.isModal() && modalDialogs.add(fileDialog)
                  && modalDialogs.size() == 1) {
                setBoardEnabled(false);
            }
            return;
        }
        if (event.getID() == ComponentEvent.COMPONENT_SHOWN
              && event.getSource() instanceof Dialog dialog && belongsToClient(dialog)) {
            // A modal dialog left behind the native window would block the client unseen, so it rises regardless.
            if (asksPrompts() && !allowedOverBattle(dialog) && !dialog.isModal()) {
                return;
            }
            raise(dialog);
        }
    };
    /** The scene's battle view from its creation until it is disposed; GPU thread. */
    private volatile GpuBattleView battleView;
    private final GpuHost.Scene scene = new HostScene();
    private volatile boolean closing;
    /** EDT: the lobby views the battle's menus open natively over it, made when first asked for. */
    private GpuBattleTools battleTools;
    private volatile boolean presented;
    private volatile boolean restoreLegacy2D;
    private volatile boolean exitRequested;
    private volatile Throwable startupFailure;
    private Runnable afterClose;
    /** EDT: opens the board of a game that started while this editor held the window; null for none. */
    private Runnable waitingBoard;

    private GpuBoardWindow(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel) {
        this(gui, view, panel, null, null, null);
    }

    private GpuBoardWindow(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel, Window previewOwner,
          BoardEditorSession editor, Game mapGame) {
        this.gui = gui;
        this.editor = editor;
        this.mapGame = mapGame;
        initialView = view;
        this.panel = panel;
        preview = previewOwner != null && editor == null;
        if (preview) { previewTitle = previewTitle(mapGame.getBoard()); }
        swingWindow = preview || editor != null ? previewOwner
                    : gui == null ? null : gui.getFrame();
        if (editor != null) {
            loadingMessage = Messages.getString("BoardEditor.edit3DLoading");
        } else if (preview) {
            loadingMessage = Messages.getString("GpuBoard.previewLoading");
        }
        startupTimer = new Timer(100, event -> initializeSource());
    }

    /** Load a browser entry without changing the lobby's selected boards. */
    public static void openPreview(Window owner, File boardFile) {
        Board board;
        try {
            board = loadPreview(boardFile);
        } catch (RuntimeException failure) {
            reportPreviewFailure(owner, failure);
            return;
        }
        openPreview(owner, board, boardFile, new PlanetaryConditions());
    }

    /**
     * A browser entry's board as the file preview shows it: loaded and converted as the game loads a sheet
     * ({@link BoardFile#loadSheet}), then closed at its outer building edges as a finished game map. The editor keeps
     * those exits so the sheet can join another sheet later.
     *
     * @throws IllegalArgumentException when the file cannot be read as a board
     */
    public static Board loadPreview(File boardFile) {
        Board board = BoardFile.loadSheet(boardFile, false, true);
        board.closeOffBoardBuildingExits();
        board.setMapName(boardFile.getName());
        return board;
    }

    /** The preview owns a temporary game; the shared renderer supplies all terrain and camera controls. */
    public static synchronized void openPreview(Window owner, Board board) {
        openPreview(owner, board, new PlanetaryConditions());
    }

    /** The lobby supplies its current conditions; the preview's temporary game owns a copy. */
    public static synchronized void openPreview(Window owner, Board board, PlanetaryConditions conditions) {
        openPreview(owner, board, null, conditions);
    }

    /** The lobby's map preview: as {@link #openPreview(Window, Board, PlanetaryConditions)}, but 3D only. */
    public static synchronized void openLobbyPreview(Window owner, Board board, PlanetaryConditions conditions) {
        openPreview(owner, board, null, conditions, false);
    }

    private static synchronized void openPreview(Window owner, Board board, File file, PlanetaryConditions conditions) {
        openPreview(owner, board, file, conditions, true);
    }

    private static synchronized void openPreview(Window owner, Board board, File file, PlanetaryConditions conditions,
          boolean legacy2D) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Open the GPU preview on the Swing event thread");
        }
        Objects.requireNonNull(owner);
        var snapshot = new PlanetaryConditions(conditions);
        if (active != null) {
            if (isPreviewOf(owner)) {
                updatePreviewConditions(owner, snapshot);
                active.replacePreview(board, file, true);
            } else if (active.preview || active.closing) {
                active.close(false);
                active.afterClose = () -> {
                    if (owner.isShowing()) {
                        openPreview(owner, board, file, snapshot, legacy2D);
                    }
                };
            } else {
                alreadyOpen(owner);
            }
            return;
        }
        try {
            Game game = new Game();
            game.setBoard(board);
            game.setPhase(GamePhase.LOUNGE);
            // Entering the lounge resets the temporary game's conditions, so apply the lobby copy afterwards.
            game.setPlanetaryConditions(snapshot);
            GpuBoardWindow window = new GpuBoardWindow(null, null, () -> null, owner, null, game);
            window.previewFile = file;
            window.offersLegacy2D = legacy2D;
            start(window);
        } catch (RuntimeException | LinkageError failure) {
            reportPreviewFailure(owner, failure);
        }
    }

    /** EDT: whether the owner's open map preview shows this board (not a board browsed since). */
    public static synchronized boolean showsPreview(Window owner, Board board) {
        return isPreviewOf(owner) && active.mapGame.getBoard() == board;
    }

    /**
     * EDT: shows another board in the owner's open map preview without bringing it to the front, for an update the
     * user did not ask the preview for (a changed lobby map); without an open preview it does nothing.
     */
    public static synchronized void updatePreview(Window owner, Board board) {
        if (isPreviewOf(owner)) {
            active.replacePreview(board, null, false);
        }
    }

    /** EDT: follow lobby conditions without replacing the preview board or resetting its camera. */
    public static synchronized void updatePreviewConditions(Window owner, PlanetaryConditions conditions) {
        if (isPreviewOf(owner)) {
            active.mapGame.setPlanetaryConditions(new PlanetaryConditions(conditions));
        }
    }

    private static boolean isPreviewOf(Window owner) {
        return active != null && active.preview && !active.closing && active.swingWindow == owner;
    }

    /** Closes the owner's map preview, as hiding or closing the owner does. */
    public static synchronized void closePreview(Window owner) {
        if (active != null && active.preview && active.swingWindow == owner) {
            active.close(false);
        }
    }

    public static synchronized void open(BoardClientState view, Supplier<JComponent> panel) {
        open(view.getClientgui(), view, panel);
    }

    /** Starts an independent native editor. The owner is only a launcher/file-dialog owner, never a board view. */
    public static synchronized void openEditor(Window owner, File file, boolean chooseFile) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> openEditor(owner, file, chooseFile));
            return;
        }
        if (active != null) {
            if (active.preview) {
                active.close(false);
                active.afterClose = () -> openEditor(owner, file, chooseFile);
            } else {
                alreadyOpen(owner);
            }
            return;
        }
        try {
            BoardEditorSession editor = new BoardEditorSession();
            if (file != null) { editor.open(file.toPath()); }
            else if (chooseFile && !editor.chooseOpen(owner)) { return; }
            startEditor(owner, editor);
        } catch (IOException | RuntimeException failure) {
            reportPreviewFailure(owner, failure);
        }
    }

    private static void startEditor(Window owner, BoardEditorSession editor) {
        // Copy and Paste carry hexes as text between MegaMek windows and text editors, as the old editor did.
        editor.textClipboard(Toolkit.getDefaultToolkit().getSystemClipboard());
        start(new GpuBoardWindow(null, null, () -> null, owner, editor, editor.game()));
    }

    /** EDT: reopen the source sheet or copy an assembled map before releasing its renderer. */
    private void requestEditor() {
        if (!preview || closing) { return; }
        try {
            BoardEditorSession session = new BoardEditorSession();
            session.open(mapGame.getBoard(), previewFile == null ? null : previewFile.toPath());
            close(false);
            afterClose = () -> editCopy(swingWindow, session);
        } catch (RuntimeException failure) {
            reportPreviewFailure(swingWindow, failure);
        }
    }

    /** Opens the editor on a preview's copied board, once no other native window holds the renderer. EDT. */
    private static synchronized void editCopy(Window owner, BoardEditorSession session) {
        if (active != null) {
            if (active.preview) {
                active.close(false);
                active.afterClose = () -> editCopy(owner, session);
            } else {
                alreadyOpen(owner);
            }
            return;
        }
        Window launcher = owner;
        if (launcher instanceof AdvancedSearchMapDialog browser) {
            launcher = browser.getOwner();
            // End the original modal call deliberately; the editor returns directly to the lobby.
            browser.setResult(DialogResult.CANCELLED);
            browser.dispose();
        }
        if (launcher == null || !launcher.isDisplayable()) { return; }
        try {
            startEditor(launcher, session);
        } catch (RuntimeException | LinkageError failure) {
            reportPreviewFailure(launcher, failure);
        }
    }

    /**
     * The menu's 2D command. A map preview changes its presentation: this window closes, and once the native renderer
     * has released its resources the same board shows in 2D. The editor keeps running and opens its live 2D Preview.
     */
    private void requestLegacy2D() {
        if (closing) { return; }
        if (editor != null) {
            showPreview2D();
            return;
        }
        close(false);
        afterClose = () -> showLegacy2DPreview(swingWindow, mapGame, previewFile);
    }

    /**
     * The editor's 2D Preview: one non-modal window beside the running editor, owned by the editor's owner window,
     * showing the edited board as the 2D board draws it. Its {@link BoardView} listens to the session's own Game and
     * Board as it does in a game (the session edits them on the EDT), so edits, Undo/Redo and a replaced document (Open,
     * New) show as they happen, without polling or a copy. A second request brings the open window to the front;
     * closing the editor disposes it ({@link #finish}). EDT.
     */
    private void showPreview2D() {
        if (preview2D != null && preview2D.isDisplayable()) {
            showLegacy2D(null, preview2D);
            return;
        }
        preview2D = boardWindow2D(swingWindow, Messages.getString("BoardEditor.preview2D"), "board-editor-preview-2d",
              editor.game(), null);
        if (preview2D != null) { showLegacy2D(null, preview2D); }
    }

    /**
     * A file-browser or lobby preview's Legacy 2D window, with the 3D preview's menu ({@link #previewMenu2D}): Classic or
     * Enhanced returns to the 3D preview of this exact board, including a randomly generated one, with the chosen graphics.
     */
    private static void showLegacy2DPreview(Window owner, Game game, File file) {
        JDialog dialog = boardWindow2D(owner, previewTitle(game.getBoard()), "board-preview-2d", game,
              (window, view) -> previewMenu2D(window, view, game, file, () -> {
                  openPreview(owner, game.getBoard(), file, game.getPlanetaryConditions());
                  if (active != null && active.preview && active.swingWindow == owner
                        && active.mapGame.getBoard() == game.getBoard()) { window.dispose(); }
              }));
        if (dialog == null) { return; }
        showLegacy2D(null, dialog);
    }

    /** The 2D board art's column and row pitch (63 and 72 px) at the mapsheet's 1.125 and 1.3 inches. */
    private static final int PRINT_2D_DPI_X = 56, PRINT_2D_DPI_Y = 55;

    /**
     * A Legacy 2D preview's menu bar, the 3D preview's Menu: File (3D Editor on a copy of the board, Print, Close), View
     * (Renderer, Hex Coords, Hex Types, Client Settings) and Help (About). Renderer's Classic or Enhanced chooses the 3D
     * graphics, then {@code show3D} shows the board in 3D; the window itself always draws Legacy 2D. Print saves the 2D
     * board art (the old Save As Image's, with labels) as the 3D print's JPEG, at the density that prints each hex at
     * its mapsheet size. EDT.
     */
    public static JMenuBar previewMenu2D(JDialog dialog, BoardView view, Game game, @Nullable File file, Runnable show3D) {
        GUIPreferences preferences = GUIPreferences.getInstance();
        JMenu document = new JMenu(Messages.getString("CommonMenuBar.FileMenu"));
        document.add(menuItem("BoardEditor.edit3D", () -> {
            try {
                BoardEditorSession session = new BoardEditorSession();
                session.open(game.getBoard(), file == null ? null : file.toPath());
                editCopy(dialog.getOwner(), session);
            } catch (RuntimeException failure) { reportPreviewFailure(dialog, failure); }
        }));
        document.add(menuItem("CommonMenuBar.PrintMenu", () -> {
            Path target = GpuMapSource.choosePrintFile(dialog, file == null ? new File(".") : file.getAbsoluteFile().getParentFile(),
                  file == null ? game.getBoard().getBoardName() : BoardFile.withoutExtension(file.getName()), "-2d");
            if (target == null) { return; }
            dialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            try {
                var image = view.getClientState().getEntireBoardImage(true);
                try (var jpeg = new GpuPrintJpeg(target, image.getWidth(), image.getHeight(), PRINT_2D_DPI_X, PRINT_2D_DPI_Y)) {
                    jpeg.write(image);
                }
            } catch (IOException | RuntimeException failure) {
                GpuMapSource.printFailed(dialog, failure);
            } finally { dialog.setCursor(Cursor.getDefaultCursor()); }
        }));
        document.addSeparator();
        document.add(menuItem("Close", () -> dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING))));
        JMenu renderer = CommonMenuBar.rendererMenu(event -> {
            // The window itself draws Legacy 2D; Classic or Enhanced shows the board in 3D
            if (!ClientGUI.VIEW_LEGACY_2D.equals(event.getActionCommand())) {
                preferences.setBoardGraphics(ClientGUI.VIEW_GRAPHICS_CLASSIC.equals(event.getActionCommand())
                      ? BoardGraphics.CLASSIC : BoardGraphics.ENHANCED);
                show3D.run();
            }
        }, true);
        renderer.setName("preview-renderer");
        JCheckBoxMenuItem coordinates = new JCheckBoxMenuItem(Messages.getString("CommonMenuBar.viewToggleHexCoords"));
        coordinates.addActionListener(event -> preferences.toggleCoords());
        JCheckBoxMenuItem types = new JCheckBoxMenuItem(Messages.getString("CommonMenuBar.viewToggleHexTypes"));
        types.addActionListener(event -> preferences.toggleHexTypes());
        // The window draws Legacy 2D for as long as it is open; the checks show the preferences, which other menus change.
        Runnable checks = () -> {
            CommonMenuBar.selectRenderer(renderer, ClientGUI.VIEW_LEGACY_2D);
            coordinates.setSelected(preferences.getCoordsEnabled());
            types.setSelected(preferences.getHexTypesEnabled());
        };
        checks.run();
        JMenu display = new JMenu(Messages.getString("CommonMenuBar.ViewMenu"));
        display.setName("preview-view");
        display.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent event) { checks.run(); }
            @Override public void menuDeselected(MenuEvent event) { }
            @Override public void menuCanceled(MenuEvent event) { }
        });
        display.add(renderer);
        display.add(coordinates);
        display.add(types);
        display.addSeparator();
        display.add(menuItem("CommonMenuBar.viewClientSettings", () -> GpuMapSource.clientSettings(dialog, null)));
        JMenu help = new JMenu(Messages.getString("CommonMenuBar.HelpMenu"));
        help.add(menuItem("CommonMenuBar.helpAbout", () -> new MMAboutDialog(dialog).show()));
        JMenuBar menu = new JMenuBar();
        menu.add(document);
        menu.add(display);
        menu.add(help);
        return menu;
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(Messages.getString(label));
        item.addActionListener(event -> action.run());
        return item;
    }

    /**
     * The one 2D window of a map, for the map preview's 2D Board and the editor's 2D Preview: a non-modal window showing
     * {@code game}'s board as the 2D board draws it, through a {@link BoardView} that follows the game's board events and
     * is disposed with the window, and the menu bar {@code menu} makes for them, if any. Returns null after reporting a
     * failure. EDT.
     */
    private static JDialog boardWindow2D(Window owner, String title, String name, Game game,
          @Nullable BiFunction<JDialog, BoardView, JMenuBar> menu) {
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        dialog.setName(name);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        try {
            BoardView view = new BoardView(game, null, null, 0);
            view.setUseLosTool(false);
            view.setDisplayInvalidFields(false);
            dialog.add(view.getComponent(true), BorderLayout.CENTER);
            if (menu != null) { dialog.setJMenuBar(menu.apply(dialog, view)); }
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { view.dispose(); }
            });
            dialog.setSize(1000, 750);
            dialog.setLocationRelativeTo(owner);
            return dialog;
        } catch (IOException failure) {
            dialog.dispose();
            reportPreviewFailure(owner, failure);
            return null;
        }
    }

    private static String previewTitle(Board board) {
        return Messages.getString("GpuBoard.previewTitle") + " - " + board.getBoardName();
    }

    /**
     * Browsing another map reuses the same context, artwork, shaders and UI, just like loading in the editor.
     *
     * @param raise whether the window comes to the front and takes the keyboard, as an explicit preview request does
     */
    private void replacePreview(Board board, File file, boolean raise) {
        previewTitle = previewTitle(board);
        previewFile = file;
        mapGame.setBoard(board); // The source's existing game listener captures and publishes the replacement.
        post(battle -> {
            if (!closing) { ((Lwjgl3Graphics) Gdx.graphics).getWindow().setTitle(previewTitle); }
        });
        if (raise) {
            focus(false);
        }
    }

    /** Start the chosen board window even before a scenario or server has delivered its first map. */
    public static synchronized void open(ClientGUI gui, Supplier<JComponent> panel) {
        open(gui, null, panel);
    }


    private static void open(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Open the GPU board on the Swing event thread");
        }
        if (active != null) {
            if (active.preview) {
                active.close(false);
                active.afterClose = () -> {
                    if (gui == null || gui.getFrame().isDisplayable()) {
                        open(gui, view, panel);
                    }
                };
            } else if (active.editor != null && gui != null) {
                // The game started while the map editor, opened from its lobby, holds the window: the board takes the
                // window once the editor closes, after its usual question about unsaved changes
                active.waitingBoard = () -> {
                    if (gui.getFrame().isDisplayable() && !gui.getClient().getGame().getPhase().isLounge()) {
                        open(gui, view, panel);
                    }
                };
                noticeOrSwing(active.swingWindow, Messages.getString("BoardEditor.edit3D"),
                      Messages.getString("GpuBoard.gameWaitsForEditor"));
            } else if (active.gui != gui || (gui == null && active.initialView != view)) {
                alreadyOpen(gui == null ? null : gui.getFrame());
            } else if (!active.closing) {
                active.focus(false);
            } else {
                // A new game may arrive while the previous game's native window is still being disposed.
                active.restoreLegacy2D = false;
                active.afterClose = () -> open(gui, view, panel);
            }
            return;
        }
        start(new GpuBoardWindow(gui, view, panel));
    }

    private static void start(GpuBoardWindow window) {
        // The board takes the one window from the start screen or the UI library; the start screen comes back when
        // the board ends and nothing else takes its place
        try {
            active = window;
            ClientGUI gui = window.gui;
            if (gui != null) {
                gui.setMiniReportLocation(false);
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
                  AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK
                        | AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            if (window.editor != null) { quitHandler(window::requestExit); }
            GpuHost.show(window.scene);
            // Capture map data on its owning EDT while the native thread creates its context and loading UI.
            if (window.mapGame != null) { SwingUtilities.invokeLater(window::initializeSource); }
        } catch (RuntimeException | LinkageError failure) {
            window.closing = true;
            window.finish(failure);
        }
    }

    /** The board, the editor or a map preview as a scene of the one native window. */
    private final class HostScene implements GpuHost.Scene {
        @Override
        public ApplicationListener listener() {
            return new GpuBattleView(null) {
                private boolean presentationRequested;
                private boolean sourceAttached;

                @Override
                public void create() {
                    battleView = this;
                    super.create();
                    setInputBlocked(modalOpen);
                    prepareEntrance();
                    if (closing) {
                        GpuHost.end(scene);
                    }
                }

                @Override
                public void render() {
                    BoardSource next = GpuBoardWindow.this.source;
                    if (!closing && presented && !sourceAttached && next != null) {
                        attachSource(next);
                        sourceAttached = true;
                    }
                    setLoadingMessage(loadingMessage);
                    super.render();
                    if (!presentationRequested) {
                        presentationRequested = true;
                        SwingUtilities.invokeLater(GpuBoardWindow.this::present);
                    }
                }

                @Override
                void sourceClosed() {
                    GpuHost.end(scene);
                }

                @Override
                public void dispose() {
                    // Windows destroys owned windows with their owner; the dialogs return to theirs first.
                    GpuNativeDialogOwner owner = nativeOwner;
                    if (owner != null) {
                        owner.releaseAll();
                    }
                    battleView = null;
                    super.dispose();
                }
            };
        }

        @Override
        public String title() {
            if (preview) {
                return previewTitle;
            }
            return Messages.getString(editor != null ? "BoardEditor.edit3D" : "GpuBoard.title");
        }

        @Override
        public boolean board() {
            return true;
        }

        @Override
        public boolean marksLinks() {
            // The editor shows its own beside its issues (GpuBoardEditor).
            return editor != null;
        }

        @Override
        public void started(long windowHandle) {
            // A preview's owner stays visible, so its dialogs keep floating instead of moving to this window.
            if (!preview) {
                nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
            }
        }

        @Override
        public void closeRequested() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuBoardWindow.this::frontModal);
            } else {
                requestExit();
            }
        }

        @Override
        public void focusGained() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuBoardWindow.this::frontModal);
            }
        }

        @Override
        public void focusLost() {
            GpuBattleView battle = battleView;
            if (battle != null) {
                battle.pause();
            }
        }

        @Override
        public void ended(Throwable failure) {
            closing = true;
            if (source != null) {
                source.close();
            }
            SwingUtilities.invokeLater(() -> finish(failure == null ? startupFailure : failure));
        }
    }

    /** Any thread: runs {@code work} on the GPU thread with the scene's battle view while it exists, else nothing. */
    private void post(java.util.function.Consumer<GpuBattleView> work) {
        GpuHost.post(() -> {
            GpuBattleView battle = battleView;
            if (battle != null) {
                work.accept(battle);
            }
        });
    }

    /**
     * Any thread: shows {@code text} as a native message, or as an error with {@code error}, over this window's scene.
     * Returns false, showing nothing, while the window is not presented or closes, so the caller shows it in Swing.
     */
    private boolean notice(String title, String text, boolean error) {
        if (!presented || closing || battleView == null) {
            return false;
        }
        post(battle -> {
            LobbyNativeDialogs cards = battle.cards();
            if (cards == null) {
                return;
            }
            if (error) {
                cards.error(title, text, Messages.getString("Ok.text"), null, null);
            } else {
                cards.message(title, text);
            }
        });
        focus(false);
        return true;
    }

    /**
     * EDT: shows the native Client Settings over this window's scene on the page of the Swing dialog's {@code route}.
     * Returns false, showing nothing, while the window is not presented or closes, so the caller shows them in Swing.
     */
    private boolean clientSettings(@Nullable String route) {
        if (!presented || closing || battleView == null) {
            return false;
        }
        ClientSettingsForm form = ClientSettingsForm.current();
        post(battle -> {
            LobbyNativeDialogs cards = battle.cards();
            if (cards != null) {
                cards.clientSettings(form, form.pageOf(route), ClientSettingsView.Host.swing(GpuHost::post));
            }
        });
        focus(false);
        return true;
    }

    /**
     * EDT: tells the user that the open native window keeps the one asked for from opening, in that window when it
     * shows, else in Swing over {@code owner}.
     */
    static void alreadyOpen(@Nullable Component owner) {
        noticeOrSwing(owner, Messages.getString("CommonMenuBar.viewGpuBoard"),
              Messages.getString("GpuBoard.alreadyOpen"));
    }

    private static void noticeOrSwing(@Nullable Component owner, String title, String text) {
        GpuBoardWindow window = active;
        if (window == null || !window.notice(title, text, false)) {
            JOptionPane.showMessageDialog(owner, text, title, JOptionPane.INFORMATION_MESSAGE);
        }
    }
    private void present() {
        if (closing) {
            return;
        }
        if (!preview && swingWindow != null) {
            swingWindow.setVisible(false);
            // Dialogs raised while the native window was still opening float; they can belong to it now.
            for (Dialog dialog : new ArrayList<>(dialogOnTop.keySet())) {
                if (dialog.isShowing()) {
                    raise(dialog);
                }
            }
        }
        if (preview) {
            // The owner's open dialogs stay in front, as those it opens later do (dialogListener), so a preview does not
            // hide them.
            for (Window window : Window.getWindows()) {
                if (window != swingWindow && window.isShowing() && window instanceof Dialog dialog
                      && !(dialog instanceof FileDialog) && belongsToSwingWindow(dialog)) {
                    raise(dialog);
                }
            }
        }
        if (gui != null) {
            gui.setLegacy2DViewEnabled(false);
            gui.refreshAuxiliaryWindows();
        }
        // A source captured during native startup must publish the now-measured tools inset before camera fitting.
        if (editor != null && source != null) { source.refresh(); }
        initializeSource();
        presented = true;
        focus(true);
        if (!closing && source == null) { startupTimer.start(); }
    }

    private void initializeSource() {
        if (closing || source != null) {
            startupTimer.stop();
            return;
        }
        if (!preview && editor == null) {
            String status = GpuBoardActions.phaseStatus(panel.get()).text();
            loadingMessage = status.isBlank() ? Messages.getString("ClientGUI.waitingOnTheServer") : status;
        }
        BoardClientState view = gui == null ? initialView : gui.getCurrentBoardState().orElse(null);
        if (mapGame == null && view == null) { return; }
        if (mapGame != null && (mapGame.getBoard().getWidth() < 1 || mapGame.getBoard().getHeight() < 1)) { return; }
        // The board's first capture finds its plants searched: the search leaves this thread free, and the timer looks again.
        if (mapGame != null && plantsAhead == null) { plantsAhead = BoardTiles.searchAhead(mapGame.getBoard()); }
        if (plantsAhead != null && !plantsAhead.isDone()) { return; }
        try {
            source = mapGame == null ? new GpuBoardSource(view, panel)
                  : new GpuMapSource(mapGame, swingWindow, editor, this::requestExit,
                        offersLegacy2D ? this::requestLegacy2D : null,
                        preview ? this::requestEditor : null);
            if (source instanceof GpuMapSource map) {
                map.fileDialogs(() -> nativeOwner, busy -> post(battle -> battle.setInputBlocked(busy || modalOpen)));
                map.notices(this::notice);
                map.settings(this::clientSettings);
            }
            startupTimer.stop();
            // The board shown is the likeliest next one: the shader warm-up prepares it first from now on.
            Game shown = mapGame == null ? view.getGame() : mapGame;
            GpuShaderWarmup.prepare(mapGame == null ? view.getBoard() : mapGame.getBoard(), editor != null,
                  shown.getPlanetaryConditions());
        } catch (RuntimeException | LinkageError failure) {
            startupFailure = failure;
            close(false);
        }
    }

    /**
     * macOS Quit while the editor runs: the editor's own close ({@link #requestExit}, which asks to save unsaved changes),
     * and MegaMek keeps running, as {@code AbstractClientGUI} does for a game. Null hands Quit back to the launcher, which
     * quits at once (it sets the same in its main menu).
     */
    private static void quitHandler(Runnable exit) {
        if (java.awt.Desktop.isDesktopSupported()
              && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
            java.awt.Desktop.getDesktop().setQuitHandler(exit == null ? null : (event, response) -> {
                exit.run();
                response.cancelQuit();
            });
        }
    }

    /** Native close uses the editor or client's normal quit action. Cancelled saves leave this window running. */
    private void requestExit() {
        if (exitRequested || closing) {
            return;
        }
        exitRequested = true;
        SwingUtilities.invokeLater(() -> {
            try {
                if (editor != null) {
                    if (source != null) { source.endEditorStroke(); }
                    if (editor.confirmDiscard(swingWindow)) { close(true); }
                } else if (gui == null) {
                    close(false);
                } else {
                    gui.handleExit();
                }
            } finally {
                exitRequested = false;
            }
        });
    }

    /**
     * Whether a Swing dialog may show over the battle window while that asks the client's prompts itself, which then
     * raises it: a surface the user kept in Swing ({@link #approved}), or a dialog that opens inside one, owned by it
     * or while it shows modally (its own prompts, pickers and file choosers, whatever their owner). Any other dialog is
     * a Swing surface the native HUD should replace; it is logged at error level and not raised.
     */
    static boolean allowedOverBattle(Dialog dialog) {
        boolean allowed = approved(dialog) || insideApproved(dialog);
        if (!allowed) {
            LOGGER.error("A Swing dialog outside the allowlist opened over the battle window: {} \"{}\"",
                  dialog.getClass().getName(), dialog.getTitle());
        }
        return allowed;
    }

    /**
     * A Swing surface the user kept over the battle window: one of {@link #SWING_DIALOGS}, or a file chooser (user item
     * 21), which the system file dialog falls back to. The message boxes the battle once kept (the exit's save
     * question, the bug report and its result, and the unit file version check) and the player to reinforce are
     * native now.
     */
    private static boolean approved(Dialog dialog) {
        return SWING_DIALOGS.stream().anyMatch(type -> type.isInstance(dialog)) || shows(dialog, JFileChooser.class);
    }

    /** Whether the dialog opens inside a kept surface: owned by one, or while one shows modally. */
    private static boolean insideApproved(Dialog dialog) {
        for (Window owner = dialog.getOwner(); owner != null; owner = owner.getOwner()) {
            if (owner instanceof Dialog parent && approved(parent)) {
                return true;
            }
        }
        return List.of(Window.getWindows()).stream().anyMatch(window -> window != dialog && window.isShowing()
              && window instanceof Dialog open && open.isModal() && approved(open));
    }

    /** Whether a Swing dialog shows a component of the type, as a file chooser does. */
    private static boolean shows(Dialog dialog, Class<?> type) {
        return dialog instanceof JDialog swing && List.of(swing.getContentPane().getComponents()).stream()
              .anyMatch(type::isInstance);
    }

    /**
     * Keeps a dialog above the native window until either closes. The dialog becomes a native owned window of the
     * board window where the platform allows ({@link GpuNativeDialogOwner}); elsewhere, or when that fails, it floats
     * on top ({@link #restoreDialogPresentation}).
     */
    private void raise(Dialog dialog) {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null && ownNatively(owner, dialog)) {
            Boolean wasOnTop = dialogOnTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
            if (dialog.isModal() && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setBoardEnabled(false);
            }
            dialog.toFront();
            return;
        }
        dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
    }

    /** A dialog of an owned dialog already stacks with it natively; any other is owned by the board window. */
    private static boolean ownNatively(GpuNativeDialogOwner owner, Dialog dialog) {
        return dialog.getOwner() instanceof Dialog parent && parent.isShowing() && owner.owns(parent)
              || owner.adopt(dialog);
    }

    private void endModal(Dialog dialog) {
        if (modalDialogs.remove(dialog) && modalDialogs.isEmpty()) {
            setBoardEnabled(true);
        }
    }

    /** EDT: brings the newest open modal dialog to the front when the board window is clicked or asked to close. */
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
     * Blocks or restores the board window's input on its own thread. The native window itself stays enabled: Windows
     * picks the window to activate as a modal dialog hides, before Swing reports it, and a still disabled owner would
     * hand the activation to another application, flashing the board away until it took the focus back.
     */
    private void setBoardEnabled(boolean enabled) {
        modalOpen = !enabled;
        post(battle -> battle.setInputBlocked(!enabled));
    }

    /** True when the window is the Swing window itself or a dialog chain owned by it. */
    private boolean belongsToSwingWindow(Window window) {
        for (Window current = window; current != null; current = current.getOwner()) {
            if (current == swingWindow) {
                return true;
            }
        }
        return false;
    }

    /**
     * A dialog of the Swing window, or, unless this is a preview whose owner stays visible, a modal one without an owner
     * of its own ({@code JOptionPane.showConfirmDialog(null, ...)}), which Swing gives its hidden shared frame. Such a
     * dialog blocks the whole application, so it must not hide behind the native window, whichever client opened it.
     */
    private boolean belongsToClient(Dialog dialog) {
        if (belongsToSwingWindow(dialog)) {
            return true;
        }
        if (preview || !dialog.isModal()) {
            return false;
        }
        try {
            Frame shared = JOptionPane.getRootFrame();
            for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
                if (current == shared) {
                    return true;
                }
            }
        } catch (HeadlessException headless) {
            return false;
        }
        return false;
    }

    private boolean closeWithPreviewOwner(AWTEvent event) {
        if (preview && !closing && event.getSource() == swingWindow
              && (event.getID() == ComponentEvent.COMPONENT_HIDDEN || event.getID() == WindowEvent.WINDOW_CLOSED)) {
            close(false);
            return true;
        }
        return false;
    }

    private void finish(Throwable failure) {
        startupTimer.stop();
        // The editor's 2D Preview shows this editor's document: it closes with the editor, whichever way it closes.
        if (preview2D != null) {
            preview2D.dispose();
            preview2D = null;
        }
        restoreDialogPresentation();
        synchronized (GpuBoardWindow.class) {
            if (active != this) {
                return;
            }
            active = null;
        }
        if (preview) {
            if (swingWindow.isShowing() && afterClose == null) {
                swingWindow.toFront();
                swingWindow.requestFocus();
            }
        }
        if (editor != null) {
            restoreLegacy2D = swingWindow != null && swingWindow.isDisplayable();
            quitHandler(null);
            if (editor.lastSavedPath() != null) { refreshSavedBoard(); }
        }
        // The native window is already destroyed. Never resurrect a client that is shutting down.
        if (afterClose != null) {
            afterClose.run();
        } else if (waitingBoard != null) {
            waitingBoard.run();
        } else if (GpuLobbyWindow.reopenFor(swingWindow)) {
            // The lobby that opened this preview or editor shows again
            LOGGER.debug("The lobby shows again");
        } else if (restoreLegacy2D && swingWindow != null) {
            showLegacy2D(gui, swingWindow);
        }
        if (failure != null) {
            reportFailure(failure);
        }
    }

    /** EDT: refresh from the saved file, including when later unsaved edits were discarded on close. */
    private void refreshSavedBoard() {
        megamek.utilities.BoardClassifier.invalidate();
        for (Window owner = swingWindow; owner != null; owner = owner.getOwner()) {
            if (!owner.isDisplayable()) { continue; }
            try {
                if (owner instanceof AdvancedSearchMapDialog browser) {
                    browser.refreshBoards();
                }
                ClientGUI client = ClientGUI.forFrame(owner);
                if (client != null) {
                    client.boardEditorClosed(editor.lastSavedPath(), editor.lastSaveFromAssembledPreview());
                    break;
                }
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "Could not refresh maps after closing the 3D editor");
            }
        }
    }

    private void restoreDialogPresentation() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        modalDialogs.clear();
        modalOpen = false;
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
    }

    /** Return to the existing client without disconnecting or changing the game. */
    public static synchronized void showLegacy2D(ClientGUI gui) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showLegacy2D(gui));
            return;
        }
        if (active != null && active.gui == gui) {
            active.close(true);
        } else if (gui.getFrame() != null) {
            showLegacy2D(gui, gui.getFrame());
        }
    }

    /** EDT: whether a board or an editor holds the window, which a map preview or the UI library may not replace. */
    static synchronized boolean isRunning() {
        return active != null && !active.preview && !active.closing;
    }

    /** EDT: whether a board, an editor or a map preview holds the window and is not closing. */
    static synchronized boolean isShowingAny() {
        return active != null && !active.closing;
    }
    /** Includes preparation: Legacy 2D panels must not open while the native window is starting. */
    public static synchronized boolean isActiveFor(ClientGUI gui) {
        return active != null && active.gui == gui;
    }

    /** Whether the active window keeps the dialog above itself: natively owned where supported, otherwise on top. */
    static boolean raisedOverBoard(Dialog dialog) {
        GpuBoardWindow window = active;
        GpuNativeDialogOwner owner = window == null ? null : window.nativeOwner;
        for (Dialog current = dialog; current != null;
              current = current.getOwner() instanceof Dialog parent ? parent : null) {
            if (owner != null && owner.owns(current)) {
                return true;
            }
        }
        return dialog.isAlwaysOnTop();
    }

    /** EDT: the View menu and its shortcut open the native ruler while this client's board is native. */
    public static boolean showRuler(ClientGUI gui) {
        if (!isActiveFor(gui)) { return false; }
        GpuBoardSource current = sourceFor(gui);
        if (current != null) {
            current.los().model().open();
            current.refresh();
        }
        return true;
    }

    /** EDT: client ruler commands use the native model while this client's native board is active. */
    public static boolean updateRuler(ClientGUI gui, int boardId,
          java.util.function.Consumer<RulerModel> action) {
        if (!isActiveFor(gui)) { return false; }
        GpuBoardSource current = sourceFor(gui);
        if (current != null && current.currentView().getBoardId() == boardId) {
            action.accept(current.los().model());
            current.refresh();
        }
        return true;
    }

    /**
     * EDT: raises this client's presented native window, shows the request there and waits for its answer in a nested
     * Swing event loop, as a modal JDialog does. Returns null when the caller must show its Swing dialog instead: off
     * the EDT, without a presented, open window for this client, or while the caller holds this class's monitor (the
     * synchronized open() shows Swing prompts), so a native wait never starts with it held. Not synchronized: the GL
     * thread never waits on the EDT. Package-private: the client's chokepoints use {@link #route}.
     */
    static DialogAnswer ask(ClientGUI gui, DialogRequest request) {
        GpuBoardWindow window = active;
        if (!SwingUtilities.isEventDispatchThread() || window == null || window.gui != gui || !window.asksPrompts()
              || Thread.holdsLock(GpuBoardWindow.class)) {
            return null;
        }
        // Like dialogListener's toFront() for Swing prompts: a prompt during another application's use is not hidden.
        window.focus(false);
        return window.source instanceof GpuBoardSource game ? game.ask(request) : null;
    }

    /**
     * Whether this window asks its client's prompts itself: presented, open and with its board source. Otherwise
     * {@link #ask} declines and the prompts show their Swing dialogs, which the dialog listener then raises.
     */
    private boolean asksPrompts() {
        return gui != null && presented && source != null && !closing;
    }

    /**
     * EDT: the entry point of the client's prompt chokepoints. Like {@link #ask}, but null (use Swing) while this
     * client's native window does not draw dialogs. A prompt asked while that window is still starting, as a loaded
     * save's first phase does, first waits for it ({@link #awaitStartup}). A dead client's prompt, asked by a game event
     * that its own prompt's nested event loop outlived, is answered as closed without showing anything.
     */
    public static DialogAnswer route(ClientGUI gui, DialogRequest request) {
        if (gui.isDead()) {
            return DialogAnswer.cancelled(request);
        }
        awaitStartup(gui);
        return drawsDialogsFor(gui) ? ask(gui, request) : null;
    }

    /**
     * EDT: shows an error over the battle window that asks its client's prompts and waits for its answer, as Swing's
     * message box does; Enter presses the first button. Returns the index of the button pressed, or
     * {@link JOptionPane#CLOSED_OPTION} when Esc closes it; null, showing nothing, when no battle window asks prompts.
     */
    static @Nullable Integer showError(String title, String text, List<String> buttons) {
        GpuBoardWindow window = active;
        ClientGUI client = window == null ? null : window.gui;
        if (client == null || client.isDead()) {
            return null;
        }
        DialogAnswer answer = ask(client, new DialogRequest(0, DialogKind.MESSAGE, title, text, false, buttons, 0,
              JOptionPane.CLOSED_OPTION, List.of(), List.of(), "", false, null, null, null, List.of()));
        if (answer == null) {
            return null;
        }
        int button = answer.button();
        return button < 0 || button >= buttons.size() ? JOptionPane.CLOSED_OPTION : button;
    }

    /**
     * EDT: asks for a file in the system's file dialog over this client's battle window and waits for it, as a modal
     * dialog does; the battle takes no input meanwhile. A file to save to that exists is only taken once the player
     * confirms replacing it, in the battle window.
     *
     * @return the file chosen, empty when the player cancels; null, showing nothing, when no battle window asks this
     *       client's prompts, so the caller shows Swing's chooser
     */
    @SuppressWarnings("OptionalAssignedToNull")
    public static @Nullable Optional<File> chooseFile(ClientGUI gui, @Nullable File directory, String title,
          FileNameExtensionFilter filter, boolean save) {
        return chooseFile(gui, new SystemFileDialog.Request(directory, title, filter, save));
    }

    /** EDT: as {@link #chooseFile(ClientGUI, File, String, FileNameExtensionFilter, boolean)}, for a full request. */
    @SuppressWarnings("OptionalAssignedToNull")
    static @Nullable Optional<File> chooseFile(ClientGUI gui, SystemFileDialog.Request request) {
        GpuBoardWindow window = active;
        if (!SwingUtilities.isEventDispatchThread() || window == null || window.gui != gui || !window.asksPrompts()
              || Thread.holdsLock(GpuBoardWindow.class)) {
            return null;
        }
        window.focus(false);
        AtomicReference<SystemFileDialog.Choice> chosen = new AtomicReference<>();
        AtomicBoolean answered = new AtomicBoolean();
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        SystemFileDialog.show(request, () -> window.nativeOwner,
              gui::getFrame, busy -> window.post(battle -> battle.setInputBlocked(busy || window.modalOpen)),
              choice -> {
                  chosen.set(choice);
                  answered.set(true);
                  loop.exit();
              });
        // The answer comes in a later event, inside the loop, unless the smoke tests' fake gives it at once
        if (!answered.get()) {
            loop.enter();
        }
        SystemFileDialog.Choice choice = chosen.get();
        if (choice == null) {
            return Optional.empty();
        }
        if (choice.askReplace()) {
            List<String> yesNo = List.of(Messages.getString("Yes"), Messages.getString("No"));
            DialogAnswer replace = ask(gui, new DialogRequest(0, DialogKind.MESSAGE,
                  Messages.getString("ChatLounge.map.confirmReplace"),
                  Messages.getString("Lobby.files.replace", choice.file().getName()), false, yesNo, 0,
                  JOptionPane.CLOSED_OPTION, List.of(), List.of(), "", false, null, null, null, List.of()));
            if (replace == null || replace.button() != 0) {
                return Optional.empty();
            }
        }
        return Optional.of(choice.file());
    }

    /**
     * EDT: while this client's native window is starting, waits in a nested event loop, as a modal dialog does, until
     * it asks prompts itself, closes, or {@link #STARTUP_PROMPT_WAIT_MS} pass. Asked in Swing meanwhile, the prompt
     * would be a modal dialog the board never draws; it could outlive the game and leave the start screen blocked.
     * Returns at once off the EDT, while the caller holds this class's monitor, or when no such window starts.
     */
    private static void awaitStartup(ClientGUI gui) {
        if (!SwingUtilities.isEventDispatchThread() || Thread.holdsLock(GpuBoardWindow.class) || !startingFor(gui)) {
            return;
        }
        long deadline = System.nanoTime() + STARTUP_PROMPT_WAIT_MS * 1_000_000L;
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Timer check = new Timer(50, event -> {
            if (!startingFor(gui) || System.nanoTime() > deadline) {
                loop.exit();
            }
        });
        check.start();
        try {
            loop.enter();
        } finally {
            check.stop();
        }
    }

    /** Any thread, no monitor: whether this client's native window is open but does not ask its prompts yet. */
    private static boolean startingFor(ClientGUI gui) {
        GpuBoardWindow window = active;
        return window != null && window.gui == gui && !window.closing && !window.asksPrompts();
    }

    /**
     * Any thread, no monitor: whether this client's native window is presented, so its HUD draws the client's dialogs,
     * which {@link #route} requires. A chokepoint whose request is costly to build checks it first, so its Swing
     * dialog stays as before. Auxiliary windows use {@link #isActiveFor} instead, so they stay hidden during startup.
     */
    public static boolean drawsDialogsFor(ClientGUI gui) {
        GpuBoardWindow window = active;
        return window != null && window.gui == gui && window.presented;
    }

    /**
     * EDT: shows the Game Master's damage card of {@code entity} over this client's native window, which takes no
     * board input while it shows; its edits and server acts go through {@code host} on the Swing thread. False,
     * showing nothing, when no native window of this client asks its prompts.
     *
     * @param closed runs on the Swing thread once the card has closed, however it closed
     */
    public static boolean editDamage(ClientGUI gui, Entity entity, GameMasterUnitTools.Host host, Runnable closed) {
        GpuBoardWindow window = active;
        if (!SwingUtilities.isEventDispatchThread() || window == null || window.gui != gui || !window.asksPrompts()
              || !(entity.getGame() instanceof megamek.common.game.Game game)) {
            return false;
        }
        UnitDamageForm form = UnitDamageForm.of(entity, UnitDamageForm.Options.inPlay(game));
        window.focus(false);
        GpuHost.post(() -> {
            GpuBattleView battle = window.battleView;
            LobbyNativeDialogs cards = battle == null ? null : battle.cards();
            if (cards == null) {
                SwingUtilities.invokeLater(closed);
                return;
            }
            cards.gameMasterDamage(form, (work, then) -> SwingUtilities.invokeLater(() -> {
                try {
                    work.run();
                } finally {
                    GpuHost.post(then);
                }
            }), host, () -> SwingUtilities.invokeLater(closed));
        });
        return true;
    }

    /**
     * EDT: shows the game options to read, natively over this client's battle window. False, showing nothing, when no
     * native window of this client asks its prompts.
     */
    public static boolean showGameOptions(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.gameOptions();
        }
        return tools != null;
    }

    /**
     * EDT: shows the native Client Settings over this client's battle window on the page of the Swing dialog's
     * {@code route}; false as above.
     */
    public static boolean showClientSettings(ClientGUI gui, @Nullable String route) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.clientSettings(route);
        }
        return tools != null;
    }

    /** EDT: shows the local player's settings natively over this client's battle window; false as above. */
    public static boolean showPlayerSettings(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.playerSettings();
        }
        return tools != null;
    }

    /**
     * EDT: opens the random army natively over this client's battle window, whose units join as reinforcements; false
     * as above.
     */
    public static boolean showReinforcements(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.reinforce();
        }
        return tools != null;
    }

    /**
     * EDT: shows the game's bots and dropped-out players natively over this client's battle window, to replace,
     * edit or kick; false as above.
     */
    public static boolean showEditBots(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.editBots();
        }
        return tools != null;
    }

    /**
     * EDT: shows this computer's addresses and the game's host natively over this client's battle window; false as
     * above.
     */
    public static boolean showNetworkInformation(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.networkInformation();
        }
        return tools != null;
    }

    /** EDT: shows the Nova CEWS networks natively over this client's battle window; false as above. */
    public static boolean showNovaNetworks(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.novaNetworks();
        }
        return tools != null;
    }

    /** EDT: shows About MegaMek natively over this client's battle window; false as above. */
    public static boolean showAbout(ClientGUI gui) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.about();
        }
        return tools != null;
    }

    /**
     * EDT: shows Report a Bug natively over the battle window of {@code client}, the client of a menu bar, which knows
     * no {@link ClientGUI}; false, showing nothing, when no battle window of that client asks its prompts.
     */
    public static boolean showBugReport(@Nullable Client client) {
        GpuBoardWindow window = active;
        ClientGUI gui = window == null ? null : window.gui;
        if (client == null || gui == null || gui.getClient() != client) {
            return false;
        }
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.bugReport();
        }
        return tools != null;
    }

    /** EDT: shows the HTML guide {@code file} natively over this client's battle window; false as above. */
    public static boolean showHelp(ClientGUI gui, String title, File file) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.help(title, file);
        }
        return tools != null;
    }

    /**
     * EDT: edits the local player's {@code note} on a hex natively over this client's battle window; false as above.
     *
     * @param closed runs on the Swing thread once the card has closed, however it closed; not when this returns false
     */
    public static boolean editNote(ClientGUI gui, SpecialHexDisplay note, Coords coords, int boardId,
          Runnable closed) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.mapNote(note, coords, boardId, closed);
        }
        return tools != null;
    }

    /**
     * EDT: shows the running Game Master vote natively over this client's battle window, or brings the card that
     * shows it up to date; false as above.
     */
    public static boolean showGameMasterVote(ClientGUI gui, Poll poll) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.gameMasterVote(LobbyGameMaster.of(gui.getClient().getGame(), gui.getClient().getLocalPlayer(),
                  poll));
        }
        return tools != null;
    }

    /** EDT: closes the Game Master vote's card, as the vote has ended; nothing when none shows. */
    public static void closeGameMasterVote(ClientGUI gui) {
        GpuBoardWindow window = active;
        if (SwingUtilities.isEventDispatchThread() && window != null && window.gui == gui
              && window.battleTools != null) {
            window.battleTools.closeGameMasterVote();
        }
    }

    /** EDT: tells who the vote made Game Master, natively over this client's battle window; false as above. */
    public static boolean announceGameMaster(ClientGUI gui, String name) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.gameMasterAppointed(name);
        }
        return tools != null;
    }

    /**
     * EDT: shows {@code command}'s arguments as a form natively over this client's battle window, opened on
     * {@code coords} when a hex was clicked; false as above.
     */
    public static boolean showClientCommand(ClientGUI gui, ClientServerCommand command, @Nullable Coords coords) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.clientCommand(command, coords);
        }
        return tools != null;
    }

    /** EDT: shows the Game Master's player setup natively over this client's battle window; false as above. */
    public static boolean showPlayerSetup(ClientGUI gui, @Nullable Player player) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.playerSetup(player);
        }
        return tools != null;
    }

    /**
     * EDT: shows the Requisition natively over this client's battle window, its units going to {@code player} (or the
     * first player offered) as reinforcements next round; false as above.
     */
    public static boolean showRequisition(ClientGUI gui, @Nullable Player player) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.requisition(player);
        }
        return tools != null;
    }

    /** EDT: edits the building in a hex natively over this client's battle window; false as above. */
    public static boolean editBuilding(ClientGUI gui, Coords coords) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.building(coords);
        }
        return tools != null;
    }

    /**
     * EDT: opens the Game Master's terrain brush on a hex beside the board of this client's battle window, whose board
     * clicks then paint; false as above.
     */
    public static boolean editHex(ClientGUI gui, Coords coords) {
        GpuBattleTools tools = battleTools(gui);
        if (tools != null) {
            tools.hexBrush(coords);
        }
        return tools != null;
    }

    /** EDT: the battle tools of this client's native window, raised to show them; null when none asks its prompts. */
    private static @Nullable GpuBattleTools battleTools(ClientGUI gui) {
        GpuBoardWindow window = active;
        if (!SwingUtilities.isEventDispatchThread() || window == null || window.gui != gui
              || !window.asksPrompts()) {
            return null;
        }
        window.focus(false);
        if (window.battleTools == null) {
            window.battleTools = new GpuBattleTools(gui, GpuHost::post, () -> window.closing || active != window, () -> {
                GpuBattleView battle = window.battleView;
                return battle == null ? null : battle.cards();
            });
        }
        return window.battleTools;
    }

    /** The board's one HTML-to-text rule, for prompt texts that Swing renders as HTML. */
    public static String plainText(String html) {
        return GpuBoardActions.plainText(html);
    }

    /**
     * Any thread: hands a toast of this client to its native window's toast stack. {@link ClientGUI#addToast}, the
     * client's one toast entry point, calls it after applying the player's toast settings.
     */
    public static void toast(ClientGUI gui, ToastLevel level, String text, @Nullable Entity entity) {
        GpuBoardSource current = sourceFor(gui);
        if (current != null) {
            current.toasts().add(level, text, entity);
        }
    }

    /** Any thread, no monitor: true while this client's native window shows a dialog; hotkeys and stories wait. */
    public static boolean dialogPendingFor(AbstractClientGUI gui) {
        GpuBoardSource current = sourceFor(gui);
        return current != null && current.dialog() != null;
    }

    /** Any thread, no monitor: the board source of this client's native window, or null without one. */
    private static @Nullable GpuBoardSource sourceFor(AbstractClientGUI gui) {
        GpuBoardWindow window = active;
        return window == null || window.gui != gui || !(window.source instanceof GpuBoardSource game) ? null : game;
    }

    private static void showLegacy2D(ClientGUI gui, Window window) {
        // An editor opened from the start screen returns to it rather than to its hidden owner frame
        if (gui == null && GpuStartWindow.reopenFor(window)) {
            return;
        }
        if (gui != null) {
            gui.setLegacy2DViewEnabled(!gui.getClient().getGame().getPhase().isLounge());
        }
        if (window instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        if (gui != null) {
            gui.refreshAuxiliaryWindows();
        }
    }

    static Lwjgl3ApplicationConfiguration configuration(boolean visible) {
        // Supported by current LWJGL; no retired AWT extension or macOS JVM relaunch is needed here.
        Lwjgl3ApplicationConfiguration.useGlfwAsync();
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle(Messages.getString("GpuBoard.title"));
        configuration.setWindowedMode(1280, 800);
        configuration.setWindowSizeLimits(900, 600, -1, -1);
        String[] icons = windowIcons();
        if (icons.length > 0) {
            // Windows and Linux show it on the title bar and taskbar; macOS uses the application's icon instead.
            configuration.setWindowIcon(FileType.Absolute, icons);
        }
        configuration.setInitialVisible(visible);
        // Keep rendering bounded even when VSync is disabled, we cap at 60fps. With VSync we let it do what it needs...
        configuration.setForegroundFPS(DEFAULT_VSYNC ? 0 : 60);
        configuration.useVsync(DEFAULT_VSYNC);
        configuration.setDepthBits(24);
        configuration.disableAudio(true);
        GpuGlsl.configure(configuration);
        configuration.setWindowListener(new WindowListener());
        return configuration;
    }

    /** The prefix of the icon files the native windows show; the program's own, set before its first window opens. */
    private static volatile String iconName = "megamek";

    /** Sets the prefix of the icon files the native windows opened from now on show, such as "mekhq". */
    static void useIcons(String name) {
        iconName = name;
    }

    /** The program's icons the Swing frames use, by size, that exist; a missing icon file would stop the window. */
    static String[] windowIcons() {
        String name = iconName;
        return Stream.of(16, 32, 48, 256)
              .map(size -> new File(Configuration.miscImagesDir(), name + "-icon-" + size + "x" + size + ".png"))
              .filter(File::isFile)
              .map(File::getAbsolutePath)
              .toArray(String[]::new);
    }

    /** Every board window's events: its context sets the shading language, and losing focus pauses the battle. */
    private static class WindowListener extends Lwjgl3WindowAdapter {
        @Override
        public void created(Lwjgl3Window window) {
            GpuGlsl.detect();
            GpuNativeDialogOwner.Win32.useWindowIconsForClass(window.getWindowHandle());
        }

        @Override
        public void focusLost() {
            if (Gdx.app != null && Gdx.app.getApplicationListener() instanceof GpuBattleView battle) {
                battle.pause();
            }
        }
    }

    private void focus(boolean entering) {
        if (presented) {
            post(battle -> {
                if (!closing && entering) {
                    battle.startEntrance();
                }
            });
            GpuHost.focus();
        }
    }

    private void close(boolean returnToLegacy2D) {
        afterClose = null;
        restoreLegacy2D = returnToLegacy2D;
        closing = true;
        startupTimer.stop();
        if (source != null) {
            source.close();
        }
        GpuHost.end(scene);
    }

    public static synchronized void closeFor(BoardClientState view) {
        if (active != null && (active.initialView == view || active.source instanceof GpuBoardSource gameSource && gameSource.currentView() == view)) {
            active.close(false);
        }
    }

    public static synchronized void closeFor(ClientGUI gui) {
        if (active != null && active.gui == gui) {
            active.close(false);
        }
    }

    private void reportFailure(Throwable failure) {
        if (editor != null) {
            LOGGER.error("GPU map editor failed", failure);
            JOptionPane.showMessageDialog(swingWindow, failureMessage("BoardEditor.edit3DUnavailable", failure),
                  Messages.getString("BoardEditor.edit3D"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (preview) {
            reportPreviewFailure(swingWindow, failure);
            return;
        }
        LOGGER.error("GPU battle view failed", failure);
        // The game has no other board; the player retries or leaves
        Object[] choices = { Messages.getString("CommonMenuBar.viewGpuBoard"), Messages.getString("MegaMek.Quit.label") };
        int choice = JOptionPane.showOptionDialog(swingWindow, failureMessage("GpuBoard.unavailable", failure),
              Messages.getString("CommonMenuBar.viewGpuBoard"), JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
              null, choices, choices[0]);
        if (choice == 0) {
            open(gui, initialView, panel);
        } else if (gui != null && !gui.handleExit()) {
            // The game stays without a battle view, which nothing would show again; the player retries or leaves
            reportFailure(failure);
        }
    }

    private static void reportPreviewFailure(Window owner, Throwable failure) {
        LOGGER.error("GPU map preview failed", failure);
        JOptionPane.showMessageDialog(owner, failureMessage("GpuBoard.previewUnavailable", failure),
              Messages.getString("GpuBoard.preview"), JOptionPane.ERROR_MESSAGE);
    }

    private static String failureMessage(String key, Throwable failure) {
        String message = Messages.getString(key);
        if (failure.getMessage() != null) { message += "\n\n" + failure.getMessage(); }
        if (GpuGraphicsCard.applied() == GpuGraphicsCard.DISCRETE && !GpuGraphicsCard.cards().isEmpty()) {
            message += "\n\n" + Messages.getString("GpuBoard.graphicsStartupHint", GpuGraphicsCard.DISCRETE);
        }
        return message;
    }

    public static synchronized void cameraCommand(ClientGUI gui, megamek.client.ui.util.KeyCommandBind command) {
        if (active != null && active.gui == gui) {
            active.post(battle -> battle.cameraCommand(command));
        }
    }

}
