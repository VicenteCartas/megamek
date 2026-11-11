/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.RecentBoardList;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.dialogs.MMAboutDialog;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.UIUtil;
import megamek.codeUtilities.StringUtility;
import megamek.common.Hex;
import megamek.common.KeyBindParser;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.game.Game;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;
import megamek.utilities.BoardsTagger;

/** Native preview/editor input. The Game/Board and editor operations are the only authoritative state. */
final class GpuMapSource implements BoardSource {
    private static final MMLogger LOGGER = MMLogger.create(GpuMapSource.class);
    /** A tab separates a row of the hex card from its detail ({@link #hexCard}). */
    static final char COLUMN = '\t';

    private final Game game;
    private final Window owner;
    /** EDT-owned measurement; map previews and battles share the same model and native panel. */
    private RulerModel ruler;
    private final BoardEditorSession editor;
    private final GpuEditorFiles editorFiles;
    private final Runnable requestClose;
    private final Runnable requestLegacy2D;
    private final Runnable requestEditor;
    /** Only Swing owns the active brush stroke; render input carries the board generation it picked. */
    private Board editorStrokeBoard;
    /** Swing queues wheel notches (hex, levels) in arrival order until a capture; releasing Ctrl commits the undo entry. */
    private final List<Map.Entry<Coords, Integer>> pendingElevation = new ArrayList<>();
    private boolean editorElevationStroke;
    /** Swing-owned brush settings are published only as a preview, never read directly by the render thread. */
    private record EditorBrush(Coords center, long generation, List<Coords> hexes, String hint) { }
    private volatile EditorBrush editorBrush = new EditorBrush(null, -1, List.of(), "");
    /** Where the render thread last saw the pointer for a Paint or Erase event ({@link #editorHover}); null off the board. */
    private record EditorHover(Coords coords, double x, double y, String receiver) { }
    private volatile EditorHover editorHover;
    /** EDT-owned: the session's last preview and what it was made into for the render thread. */
    private BoardEditorSession.Preview shownPreview = BoardEditorSession.Preview.NONE;
    private GpuEditorPreview.Data previewData = GpuEditorPreview.Data.NONE;

    private volatile List<String> editorThemes = BoardArtwork.themes();
    private final BoardScene.PixelPool images = new BoardScene.PixelPool();
    /** EDT-owned: the board's tiles, the one capture the game shares; the editor's markers are ours. */
    private final BoardTiles terrain = new BoardTiles(images);
    private final GpuAtmosphereControls atmosphere;
    private final Timer timer;
    private final BoardListenerAdapter boardListener = new BoardListenerAdapter() {
        @Override public void boardNewBoard(BoardEvent event) {
            onSwing(() -> { if (!closed && event.getSource() == board) { dirtyAll(); } });
        }
        @Override public void boardChangedAllHexes(BoardEvent event) { boardNewBoard(event); }
        @Override public void boardChangedHex(BoardEvent event) {
            onSwing(() -> {
                if (closed || event.getSource() != board) { return; }
                if (event.getCoords() == null) { dirtyAll(); } else { terrain.changed(event.getCoords()); }
            });
        }
    };
    private final GameListenerAdapter gameListener = new GameListenerAdapter() {
        @Override public void gameBoardNew(GameBoardNewEvent event) { onSwing(() -> refresh()); }
    };
    private final IPreferenceChangeListener preferences = event -> onSwing(() -> {
        if (isClosed()) { return; }
        // The trace overlay is a view setting of the editor alone: no board art changes with it.
        if (GpuTraceOverlay.Settings.PREFERENCES.contains(event.getName())) {
            if (isEditor()) { trace = GpuTraceOverlay.Settings.capture(); }
            return;
        }
        uiPreferences = UiPreferences.capture();
        // Tiles carry the art of both graphics, and the UI scale changes no art: the next frame publishes the choice,
        // and no hex is captured again.
        if (GUIPreferences.BOARD_GRAPHICS.equals(event.getName())
              || GUIPreferences.BOARD_UI_SCALE.equals(event.getName())) { return; }
        // Label/color preferences affect presentation only; interned terrain pixels and feature geometry are reused.
        dirtyAll();
    });
    /** New key bindings change only the menus' shortcuts. */
    private final IPreferenceChangeListener keyBinds = event -> onSwing(() -> {
        if (!isClosed()) { refresh(); }
    });
    private Board board;
    private List<BoardScene.Tile> tiles = List.of();
    /** EDT-owned derived editor annotations; never supplied to map previews or gameplay. */
    private final Map<Coords, BoardTactical> terrainMarkers = new HashMap<>();
    private final Map<Coords, List<BoardEditorTerrain.Badge>> badges = new HashMap<>();
    private Map<Coords, List<BoardEditorTerrain.Badge>> badgeSnapshot = Map.of();
    private BoardTactical editorTerrain = BoardTactical.EMPTY;
    private long boardGeneration;
    private record Publication(Frame frame, BoardEditorSession.Snapshot editor, GpuEditorPreview.Data preview,
          Map<Coords, List<BoardEditorTerrain.Badge>> badges, boolean authoredGesture) { }
    private volatile Publication publication;
    /** Render-thread latch: selection and geometry must describe the same EDT capture for the entire frame. */
    private Publication rendering;
    /**
     * Guarded by this, with each {@link #publication}: what Destroyed brought down (editorCommand), as the game's collapse
     * and its checkpoint, which the next frame taken carries once; and whether the tiles changed otherwise since a frame
     * was last taken: that frame then carries none, and the view shows the change at once (GpuBattleView#renderFrame).
     */
    private final List<BoardScene.Animation> pendingEvents = new ArrayList<>();
    private boolean editedSinceTake;
    private volatile UiPreferences uiPreferences = UiPreferences.capture();
    /** The editor's trace overlay settings, copied from the preferences on the EDT; none for a map preview. */
    private volatile GpuTraceOverlay.Settings trace = GpuTraceOverlay.Settings.NONE;
    private volatile PhaseStatus phaseStatus = new PhaseStatus("", false);
    private volatile Coords hoverCoords;
    private Coords contextCoords;
    private volatile boolean closed;
    /** EDT-owned job state; only the immutable request crosses to the render thread. */
    private boolean printing;
    private boolean choosingPrint;
    private Supplier<GpuNativeDialogOwner> fileDialogOwner = () -> null;
    private Consumer<Boolean> fileDialogBusy = busy -> { };
    private Notice notice = (title, text, error) -> false;
    private Settings settings = route -> false;
    private final AtomicReference<GpuBoardPrint.Request> printRequest = new AtomicReference<>();
    /** EDT-owned: the board tagger runs ({@link #runBoardTagger}). */
    private boolean tagging;
    GpuMapSource(Game game, Window owner, BoardEditorSession editor) {
        this(game, owner, editor, null);
    }

    GpuMapSource(Game game, Window owner, BoardEditorSession editor, Runnable requestClose) {
        this(game, owner, editor, requestClose, null);
    }

    GpuMapSource(Game game, Window owner, BoardEditorSession editor, Runnable requestClose, Runnable requestLegacy2D) {
        this(game, owner, editor, requestClose, requestLegacy2D, null);
    }

    GpuMapSource(Game game, Window owner, BoardEditorSession editor, Runnable requestClose, Runnable requestLegacy2D,
          Runnable requestEditor) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (editor != null && editor.game() != game) { throw new IllegalArgumentException("The editor owns its Game"); }
        this.game = game;
        this.owner = owner;
        this.editor = editor;
        editorFiles = editor == null ? null : new GpuEditorFiles(editor);
        this.requestClose = requestClose;
        this.requestLegacy2D = requestLegacy2D;
        this.requestEditor = requestEditor;
        atmosphere = new GpuAtmosphereControls(game::getBoard, game::getPlanetaryConditions, () -> closed);
        if (editor != null) { trace = GpuTraceOverlay.Settings.capture(); }
        timer = new Timer(33, event -> refresh());
        try {
            refresh();
            game.addGameListener(gameListener);
            PreferenceManager.getClientPreferences().addPreferenceChangeListener(preferences);
            GUIPreferences.getInstance().addPreferenceChangeListener(preferences);
            KeyBindParser.addPreferenceChangeListener(keyBinds);
            timer.start();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void refresh() { refresh(null); }

    /** {@code before}: the scene a Destroyed was given (editorCommand), whose structures it brings down; else null. */
    private void refresh(BoardScene before) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed) { return; }
        flushEditorElevation();
        Board current = game.getBoard();
        if (current != board) {
            finishEditorStroke();
            if (board != null) { board.removeBoardListener(boardListener); }
            board = current;
            board.addBoardListener(boardListener);
            boardGeneration++;
            closeMeasuring();
            contextCoords = null;
            images.clear();
            terrainMarkers.clear();
            badges.clear();
            dirtyAll();
            atmosphere.reset();
        }
        List<BoardScene.Tile> next = terrain.tiles(board, (coords, pixels) -> {
            if (editor == null) { return; }
            Hex hex = board.getHex(coords);
            var flags = BoardEditorTerrain.badges(hex);
            if (flags.isEmpty()) { badges.remove(coords); }
            else { badges.put(coords, flags); }
            var markers = BoardEditorTerrain.capture(hex, coords);
            if (markers == BoardTactical.EMPTY) { terrainMarkers.remove(coords); }
            else { terrainMarkers.put(coords, markers); }
        });
        boolean changed = next != tiles;
        if (changed) {
            tiles = next;
            badgeSnapshot = Map.copyOf(badges);
            editorTerrain = editor == null ? BoardTactical.EMPTY : new BoardTactical(
                  terrainMarkers.values().stream().flatMap(value -> value.fills().stream()).toList(),
                  terrainMarkers.values().stream().flatMap(value -> value.labels().stream()).toList());
            if (editor != null) {
                // Recompute boundaries only when their terrain changes, not on every hover/inspector refresh.
                editorTerrain = BoardDeploymentGeometry.editorOutlines(mapScene(editorTerrain));
            }
            images.retain(tiles);
        }
        Coords hover = hoverCoords;
        if (editor != null) {
            List<Coords> footprint = editor.brush(hover);
            editorBrush = new EditorBrush(hover, boardGeneration, footprint, editor.sculptHint(hover, footprint.size()));
        }
        RulerModel.Snapshot measurement = ruler == null ? RulerModel.Snapshot.NONE : ruler.capture();
        phaseStatus = new PhaseStatus(editor == null ? measuringStatus(measurement) : editor.title(), false);
        Coords inspected = contextCoords == null ? hover : contextCoords;
        String tooltip = inspected == null || !board.contains(inspected) ? "" : hexCard(board.getHex(inspected));
        BoardScene scene = mapScene(editorTerrain.withRuler(measurement.ruler()));
        Frame nextFrame = new Frame(scene, List.of(), contextCoords == null ? null : new BoardScene.Context(contextCoords, List.of()),
              menuCommands(), tooltip,
              new BoardFocus(0, null), boardGeneration, "",
              atmosphere.settings(game.getPlanetaryConditions(), board.isSpace()),
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY.withLos(measurement));
        // An unchanged editor state keeps its instance, so the views' identity checks mean a real change.
        var previous = publication == null ? null : publication.editor();
        var state = editor == null ? null : editor.snapshot();
        var projected = preview(state);
        if (editor != null && state.activeBrush().way() && editorHover != null) {
            editorBrush = new EditorBrush(hover, boardGeneration, editor.brush(hover), editor.wayHint(editorHover.coords(), shownPreview));
        }
        boolean authoredGesture = editorStrokeBoard == board && state != null && state.tool() == BoardEditorSession.Tool.SELECT
              && state.selection().stream().anyMatch(item -> board.getHex(item.coords()).getDecorations().stream()
                    .anyMatch(object -> object.id().equals(item.object())
                          && megamek.common.board.BuildingVolumes.of(object.asset()).present()));
        // What Destroyed brought down falls as the game's structures fall under their own weight.
        var collapse = before == null ? null : BoardScene.TileTransition.of(before, scene, at -> null);
        synchronized (this) {
            if (collapse != null && !editedSinceTake) {
                pendingEvents.add(collapse);
                pendingEvents.add(new BoardScene.SceneUpdate(scene));
            } else if (changed) {
                pendingEvents.clear();
                editedSinceTake = true;
            }
            publication = new Publication(nextFrame, state != null && state.equals(previous) ? previous : state, projected,
                  badgeSnapshot, authoredGesture);
        }
    }

    /** Map workspaces expose their existing document and application actions without requiring a game client. */
    private List<BoardScene.Command> menuCommands() {
        List<BoardScene.Command> commands = new ArrayList<>();
        if (editor == null && requestEditor != null) {
            commands.add(menuCommand("boardEdit3D", "BoardEditor.edit3D", requestEditor));
        }
        if (editor != null && requestLegacy2D != null) {
            // The editor opens its live Legacy 2D Preview beside itself; a preview switches through View > Renderer.
            commands.add(menuCommand("boardPreview2D", "BoardEditor.preview2D", requestLegacy2D));
        }
        if (editor != null) {
            long generation = boardGeneration;
            for (var action : List.of(BoardEditorSession.Action.OPEN, BoardEditorSession.Action.SAVE,
                  BoardEditorSession.Action.SAVE_AS, BoardEditorSession.Action.SAVE_IMAGE)) {
                String key = switch (action) {
                    case OPEN -> "fileBoardOpen";
                    case SAVE -> "fileBoardSave";
                    case SAVE_AS -> "fileBoardSaveAs";
                    default -> "fileBoardSaveAsImage";
                };
                commands.add(menuCommand(key, "CommonMenuBar." + key,
                      () -> editorCommand(new BoardEditorSession.Command(action), generation)));
            }
            // The launcher's Recent Boards list; an entry opens through the session's Open (with its save prompt).
            List<BoardScene.Command> recent = new ArrayList<>();
            for (String file : RecentBoardList.getRecentBoards()) {
                recent.add(new BoardScene.Command("fileBoardRecent-" + file, new File(file).getName(), "", true, false,
                      List.of(), () -> editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.OPEN, file), generation)));
            }
            commands.add(new BoardScene.Command("fileBoardRecent", Messages.getString("CommonMenuBar.fileBoardRecent"), "",
                  !recent.isEmpty(), false, recent, () -> { }));
            // The saved file in the system's program for it; a document never saved has none.
            commands.add(new BoardScene.Command("boardSourceFile", Messages.getString("CommonMenuBar.boardSourceFile"), "",
                  editor.path() != null && java.awt.Desktop.isDesktopSupported(), false, false, List.of(),
                  () -> editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SOURCE_FILE), generation)));
            commands.add(new BoardScene.Command("boardRunBoardTagger", Messages.getString("CommonMenuBar.boardRunBoardTagger"),
                  "", !tagging, false, false, List.of(), () -> onSwing(() -> { if (!closed) { runBoardTagger(); } })));
        }
        commands.add(new BoardScene.Command("fileBoardPrint", Messages.getString("CommonMenuBar.PrintMenu"), "",
              !printing && !choosingPrint, false, false, List.of(), () -> onSwing(this::choosePrint)));
        GUIPreferences gui = GUIPreferences.getInstance();
        List<BoardScene.Command> view = new ArrayList<>(List.of(renderer(gui),
              viewCommand(ClientGUI.VIEW_TOGGLE_HEX_COORDS, editor == null ? KeyCommandBind.HEX_COORDS : null,
                    gui.getCoordsEnabled(), gui::toggleCoords),
              viewCommand(ClientGUI.VIEW_TOGGLE_HEX_TYPES, null, gui.getHexTypesEnabled(), gui::toggleHexTypes)));
        if (editor != null) {
            // The editor's reference image over its top view (GpuTraceOverlay); its file, opacity, scale and position are
            // the Client Settings' Trace Overlay section.
            view.add(viewCommand("boardTraceOverlay", null, gui.getShowTraceOverlay(), gui::toggleTraceConditionsOverlay));
            view.add(menuCommand("boardTraceOverlaySettings", "BoardEditor.traceSettings",
                  () -> showSettings(CommonSettingsPane.SETTINGS_OVERLAYS)));
        }
        commands.add(new BoardScene.Command("view", Messages.getString("CommonMenuBar.ViewMenu"), "", true,
              false, view, () -> { }));
        commands.add(menuCommand("viewClientSettings", "CommonMenuBar.viewClientSettings", () -> showSettings(null)));
        commands.add(menuCommand("helpAbout", "CommonMenuBar.helpAbout", () -> new MMAboutDialog(owner).show()));
        if (requestClose != null) { commands.add(menuCommand("close", "Close", requestClose)); }
        return List.copyOf(commands);
    }

    /**
     * View > Renderer, as CommonMenuBar's Renderer submenu, with this window's 3D graphics checked: a preview's Legacy
     * 2D closes it and shows its board in a Legacy 2D window; the editor draws only in 3D, so there it is unavailable
     * (its Legacy 2D Preview opens beside it); the lobby's preview has no Legacy 2D. Classic and Enhanced apply at once.
     */
    private BoardScene.Command renderer(GUIPreferences gui) {
        List<BoardScene.Command> choices = new ArrayList<>();
        if (editor == null && requestLegacy2D != null) {
            choices.add(viewCommand(ClientGUI.VIEW_LEGACY_2D, null, false, requestLegacy2D));
        } else if (editor != null) {
            choices.add(new BoardScene.Command(ClientGUI.VIEW_LEGACY_2D, Messages.getString("CommonMenuBar.viewLegacy2D"),
                  Messages.getString("BoardEditor.noLegacy2D"), false, false, false, List.of(), () -> { }, "", false));
        }
        BoardGraphics graphics = gui.getBoardGraphics();
        choices.add(viewCommand(ClientGUI.VIEW_GRAPHICS_CLASSIC, null, graphics == BoardGraphics.CLASSIC,
              () -> gui.setBoardGraphics(BoardGraphics.CLASSIC)));
        choices.add(viewCommand(ClientGUI.VIEW_GRAPHICS_ENHANCED, null, graphics == BoardGraphics.ENHANCED,
              () -> gui.setBoardGraphics(BoardGraphics.ENHANCED)));
        return new BoardScene.Command("viewRenderer", Messages.getString("CommonMenuBar.viewRenderer"), "", true, false,
              List.copyOf(choices), () -> { });
    }

    /**
     * The old editor's Run Board Tagger: {@link BoardsTagger#runBoardTagger} rewrites the automatic tags of every board
     * file in the boards directories, off the EDT (its menu entry is disabled meanwhile). The open document then gets
     * the same automatic tags ({@code BOARD autotags}), so a later Save does not write stale ones back, and the old
     * report says that the tagger is done.
     */
    private void runBoardTagger() {
        if (tagging) { return; }
        tagging = true;
        refresh();
        Thread worker = new Thread(() -> {
            try { BoardsTagger.runBoardTagger(); }
            finally {
                onSwing(() -> {
                    tagging = false;
                    if (closed) { return; }
                    editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.BOARD, "autotags", ""), boardGeneration);
                    String title = Messages.getString("BoardEditor.boardRunBoardTagger.title");
                    String report = Messages.getString("BoardEditor.boardRunBoardTagger.report");
                    if (!notice.show(title, report, false)) {
                        JOptionPane.showMessageDialog(owner, report, title, JOptionPane.PLAIN_MESSAGE);
                    }
                });
            }
        }, "board-tagger");
        worker.setDaemon(true);
        worker.start();
    }

    /** EDT: Client Settings on the page of {@code route}, natively over this window when it shows them, else in Swing. */
    private void showSettings(@Nullable String route) {
        if (!settings.show(route)) {
            clientSettings(owner, route);
        }
    }

    /** The Client Settings dialog, opened on the settings page {@code route} when there is one. */
    static void clientSettings(Window owner, String route) {
        Window parent = owner;
        while (parent != null && !(parent instanceof JFrame)) { parent = parent.getOwner(); }
        CommonSettingsDialog settings = new CommonSettingsDialog((JFrame) parent);
        try {
            if (route != null) { settings.selectRoute(route); }
            settings.setVisible(true);
        } finally { settings.dispose(); }
    }

    private BoardScene.Command menuCommand(String id, String label, Runnable action) {
        return new BoardScene.Command(id, Messages.getString(label), "", true, false, false, List.of(),
              () -> onSwing(() -> { if (!closed) { action.run(); } }));
    }

    /** Preference state is captured on the EDT; native menu clicks return here to invoke the existing action. */
    private BoardScene.Command viewCommand(String id, KeyCommandBind shortcut, Boolean selected, Runnable action) {
        BoardScene.Command command = menuCommand(id, "CommonMenuBar." + id, action);
        return new BoardScene.Command(id, command.label(), "", true, false, false, List.of(), command.action(),
              shortcut == null ? "" : KeyCommandBind.getDesc(shortcut), selected);
    }

    private BoardScene mapScene(BoardTactical tactical) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(),
              Entity.NONE, "", List.of(), null, List.of(), List.of(), List.of(), tactical);
    }

    /** Captures print labels from the board without changing any view preference or its published tiles. */
    BoardScene printScene() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        finishEditorStroke();
        refresh();
        Font font = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 10);
        return mapScene(BoardTactical.EMPTY).withTiles(tiles.stream().map(tile -> {
            Hex hex = BoardSceneryLayouts.withoutTakenTokens(board.getHex(tile.coords()));
            var labels = BoardHexText.capture(tile.coords(), hex, board, 1, font, font, true, true, true).stream()
                  .map(label -> new BoardHexText(label.text(), label.baseline(), label.font(), 0xFF000000,
                        label.fromTop(), label.elevation())).toList();
            return tile.withText(labels);
        }).toList());
    }

    /** EDT entry point; completion and failure return to that same owner after the GL thread has written the file. */
    void requestPrint(Path target, Runnable completed, Consumer<Exception> failed) {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed || printing) { return; }
        printing = true;
        try {
            printRequest.set(new GpuBoardPrint.Request(printScene(), target, () -> onSwing(() -> {
                printing = false;
                if (!closed) { completed.run(); refresh(); }
            }), failure -> onSwing(() -> {
                printing = false;
                if (!closed) { failed.accept(failure); refresh(); }
            })));
        } catch (RuntimeException failure) {
            printing = false;
            failed.accept(failure);
            refresh();
        }
    }

    @Override public GpuBoardPrint.Request takePrintRequest() { return printRequest.getAndSet(null); }

    /** EDT: the window's owner for the system file dialog, and its input block while the dialog is open. */
    void fileDialogs(Supplier<GpuNativeDialogOwner> nativeOwner, Consumer<Boolean> busy) {
        fileDialogOwner = nativeOwner;
        fileDialogBusy = busy;
    }

    /** Shows a message, or an error, over the scene; false when the caller must show it in Swing instead. */
    interface Notice {
        boolean show(String title, String text, boolean error);
    }

    /** EDT: where the scene's messages show, in place of Swing's message boxes over the owner. */
    void notices(Notice notice) {
        this.notice = notice;
    }

    /** Shows the Client Settings on the page of a route over the scene; false when Swing must show them instead. */
    interface Settings {
        boolean show(@Nullable String route);
    }

    /** EDT: where View &gt; Client Settings and the trace overlay's settings show, in place of the Swing dialog. */
    void settings(Settings settings) {
        this.settings = settings;
    }
    /** Print writes the 3D snapshot as a printable JPEG, to a file chosen in the system's save dialog. */
    private void choosePrint() {
        if (closed || printing || choosingPrint) { return; }
        choosingPrint = true;
        refresh();
        try {
            Path near = editor == null ? null : editor.path();
            SystemFileDialog.Request request = printRequest(near == null ? new File(".") : near.toFile().getParentFile(),
                  near == null ? board.getBoardName() : BoardFile.withoutExtension(near.getFileName().toString()), "-3d");
            SystemFileDialog.show(request, fileDialogOwner, () -> owner, fileDialogBusy, choice -> {
                choosingPrint = false;
                refresh();
                if (choice == null || closed) { return; }
                try {
                    // The print card's Cancel ends a print without an error to report.
                    requestPrint(SystemFileDialog.withExtension(choice.file(), request.filter()).toPath(), () -> { },
                          failure -> {
                              if (!(failure instanceof java.util.concurrent.CancellationException)) {
                                  printFailed(failure);
                              }
                          });
                } catch (RuntimeException failure) { printFailed(failure); }
            });
        } catch (RuntimeException failure) {
            choosingPrint = false;
            refresh();
            printFailed(failure);
        }
    }

    /** The system save dialog's request for a print's JPEG in {@code directory}. */
    static SystemFileDialog.Request printRequest(File directory, String name, String suffix) {
        return new SystemFileDialog.Request(directory, Messages.getString("CommonMenuBar.PrintMenu"),
              new FileNameExtensionFilter(Messages.getString("GpuBoard.print.filter"), "jpg", "jpeg"), true,
              (name == null || name.isBlank() ? "board" : name) + suffix + ".jpg");
    }

    /** The Legacy 2D preview's print JPEG, chosen in Swing's chooser over its Swing window; null when cancelled. */
    static Path choosePrintFile(Window owner, File directory, String name, String suffix) {
        SystemFileDialog.Request request = printRequest(directory, name, suffix);
        JFileChooser chooser = new JFileChooser(directory);
        chooser.setDialogTitle(request.title());
        chooser.setFileFilter(request.filter());
        chooser.setAccessory(new JLabel(Messages.getString("GpuBoard.print.scale")));
        chooser.setSelectedFile(new File(directory, request.name()));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) { return null; }
        return SystemFileDialog.withExtension(chooser.getSelectedFile(), request.filter()).toPath();
    }

    private void printFailed(Exception failure) {
        if (!notice.show(Messages.getString("ClientGUI.errorSavingFile"), printFailure(failure), true)) {
            printFailed(owner, failure);
        }
    }

    /** The Legacy 2D preview's print failure, in a Swing message box over its Swing window. */
    static void printFailed(Window owner, Exception failure) {
        JOptionPane.showMessageDialog(owner, printFailure(failure), Messages.getString("ClientGUI.errorSavingFile"),
              JOptionPane.ERROR_MESSAGE);
    }

    private static String printFailure(Exception failure) {
        LOGGER.error(failure, "Unable to save printable board");
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }

    @Override
    public void reloadAssets() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Map capture belongs to the EDT"); }
        if (closed) { return; }
        megamek.common.util.ImageUtil.reloadImages();
        images.clear();
        terrainMarkers.clear();
        terrain.reload();
        editorThemes = BoardArtwork.themes();
        refresh();
    }

    @Override public List<String> editorThemes() { return editorThemes; }
    private void dirtyAll() { terrainMarkers.clear(); badges.clear(); terrain.changed(null); }
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); } else { SwingUtilities.invokeLater(action); }
    }
    public synchronized Frame takeFrame() {
        rendering = publication;
        editedSinceTake = false;
        if (pendingEvents.isEmpty()) { return rendering.frame(); }
        Frame taken = rendering.frame().withTimeline(List.copyOf(pendingEvents));
        pendingEvents.clear();
        return taken;
    }

    @Override
    public BoardAtmosphere.Settings scenarioAtmosphere() {
        var latest = publication;
        return latest == null ? BoardAtmosphere.DEFAULTS : latest.frame().scenarioAtmosphere();
    }
    public UiPreferences uiPreferences() { return uiPreferences; }
    @Override public GpuTraceOverlay.Settings trace() { return trace; }
    public PhaseStatus phaseStatus() { return phaseStatus; }
    public GpuAtmosphereControls atmosphere() { return atmosphere; }
    public boolean isClosed() { return closed; }
    public void setHover(Coords coords) { hoverCoords = coords; }
    public void inspect(Coords coords) { onSwing(() -> { contextCoords = coords; refresh(); }); }

    public void key(int keyCode, boolean down, int modifiers) {
        onSwing(() -> {
            if (closed || !down) { return; }
            var binds = KeyCommandBind.getAllBindsByKey(keyCode, modifiers);
            // Ctrl+G belongs to Group in the editor; the preview keeps the gameplay Hex Coords shortcut.
            if (editor == null && binds.contains(KeyCommandBind.HEX_COORDS)) {
                GUIPreferences.getInstance().toggleCoords();
            } else if (binds.contains(KeyCommandBind.LOS_SETTING)) {
                changeRuler(boardGeneration, RulerModel::open);
            } else if (editor != null && keyCode != KeyEvent.VK_SHIFT && keyCode != KeyEvent.VK_CONTROL
                  && keyCode != KeyEvent.VK_ALT && keyCode != KeyEvent.VK_META) {
                finishEditorStroke();
                var action = BoardEditorSession.DOCUMENT_KEYS.stream()
                      .filter(binding -> binding.key() == keyCode && binding.modifiers() == modifiers)
                      .map(BoardEditorSession.DocumentKey::action).findFirst().orElse(null);
                if (action == null || !editorFiles.command(new BoardEditorSession.Command(action))) {
                    editor.key(keyCode, modifiers, owner);
                }
            }
            refresh();
        });
    }
    public void stopKeys() { endEditorStroke(); }

    public boolean isEditor() {
        return editor != null;
    }

    /**
     * The hovered or inspected hex as the map tools' card lists it, one row a line, a {@link #COLUMN} before a row's
     * detail: its number with its level and theme; each terrain a player sees with its terrain factor, by MegaMek's
     * display names as the battle's hex tooltip lists them, so automated and cosmetic terrains, which have none, stay
     * out; then why an invalid hex is invalid.
     */
    static String hexCard(Hex hex) {
        String level = Messages.getString("GpuBoard.map.level", hex.getLevel());
        List<String> rows = new ArrayList<>(List.of(Messages.getString("GpuBoard.hud.context.hex",
              hex.getCoords().getBoardNum()) + COLUMN
              + (StringUtility.isNullOrBlank(hex.getTheme()) ? level : level + " \u00B7 " + hex.getTheme())));
        for (int type : hex.getTerrainTypes()) {
            Terrain terrain = hex.getTerrain(type);
            String name = Terrains.getDisplayName(type, terrain.getLevel());
            if (name != null) {
                int factor = terrain.getTerrainFactor();
                rows.add(name + COLUMN + (factor > 0 ? Messages.getString("GpuBoard.map.terrainFactor", factor) : ""));
            }
        }
        if (rows.size() == 1) {
            rows.add(Messages.getString("GpuBoard.hud.context.clearTerrain") + COLUMN);
        }
        List<String> errors = new ArrayList<>();
        if (!hex.isValid(errors)) {
            rows.add(UIUtil.WARNING_SIGN.strip() + " " + Messages.getString("BoardView1.invalidHex") + COLUMN);
            errors.forEach(error -> rows.add(error + COLUMN));
        }
        return String.join("\n", rows);
    }

    @Override
    public void measure(Coords coords, int modifiers, float pointedZ) {
        onSwing(() -> {
            if (closed || coords == null || !board.contains(coords)) {
                return;
            }
            if (ruler == null) { ruler = new RulerModel(game, 0, null); }
            int measurement = GpuBoardSource.isMeasurement(modifiers) ? modifiers : ruler.capture().pending();
            if (measurement != 0) {
                finishEditorStroke();
                ruler.addPoint(coords, Float.isNaN(pointedZ) ? null
                      : GpuLosResult.pointedHeight(board.getHex(coords), pointedZ));
                refresh();
            }
        });
    }

    @Override
    public void changeRuler(long generation, java.util.function.Consumer<RulerModel> action) {
        onSwing(() -> {
            if (closed || generation != boardGeneration || board != game.getBoard()) { return; }
            if (ruler == null) { ruler = new RulerModel(game, 0, null); }
            action.accept(ruler);
            refresh();
        });
    }

    private String measuringStatus(RulerModel.Snapshot measurement) {
        return measurement.pending() == 0 ? "" : Messages.getString("GpuBoard.hud.hint.completeLos");
    }

    private void closeMeasuring() { ruler = null; }

    public BoardEditorSession.Snapshot editorState() { return (rendering == null ? publication : rendering).editor(); }

    @Override public boolean editorAuthoredGesture() { return (rendering == null ? publication : rendering).authoredGesture(); }

    @Override public Map<Coords, List<BoardEditorTerrain.Badge>> editorBadges() {
        return (rendering == null ? publication : rendering).badges();
    }

    @Override public GpuEditorPreview.Data editorPreview() { return (rendering == null ? publication : rendering).preview(); }

    @Override public void editorHover(Coords coords, double x, double y, String receiver) {
        editorHover = coords == null ? null : new EditorHover(coords, x, y, receiver);
    }

    /**
     * The session's preview of the Paint or Erase event at the render thread's last pointer, made into what the render
     * thread draws once per new preview: the session keeps its preview while nothing it depends on changes.
     */
    private GpuEditorPreview.Data preview(BoardEditorSession.Snapshot state) {
        if (editor == null) { return GpuEditorPreview.Data.NONE; }
        var hover = editorHover;
        var next = hover == null || !board.contains(hover.coords()) ? BoardEditorSession.Preview.NONE
              : editor.preview(hover.coords(), hover.x(), hover.y(), hover.receiver());
        if (next != shownPreview) {
            shownPreview = next;
            java.util.function.Function<Coords, Hex> pending = at -> next.after().getOrDefault(at, board.getHex(at));
            var references = megamek.common.board.BuildingVolumes.referenceIds(board, pending);
            var previewImages = new BoardScene.PixelPool();
            previewData = GpuEditorPreview.data(next, state.tool() == BoardEditorSession.Tool.ERASE,
                  state.activeBrush(), state.drawsRoutes(), (coords, hex) -> terrain.preview(board, coords, hex, pending, references, previewImages));
        }
        return previewData;
    }

    public void editorCommand(BoardEditorSession.Command command, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                finishEditorStroke();
                // Destroyed brings its structures down as a game does, from the board as it stood just before.
                BoardScene before = null;
                if (command.action() == BoardEditorSession.Action.DESTROYED) { refresh(); before = publication.frame().scene(); }
                if (!editorFiles.command(command)) { editor.command(command, owner); }
                refresh(before);
            }
        });
    }

    @Override public GpuEditorFiles.Prompt editorFilePrompt() { return editorFiles == null ? null : editorFiles.prompt(); }

    @Override public void answerEditorFile(GpuEditorFiles.Prompt prompt, Path file, int answer) {
        onSwing(() -> {
            if (!closed && editorFiles != null) { editorFiles.answer(prompt, file, answer); refresh(); }
        });
    }

    public void editorValue(BoardEditorSession.Command command, boolean finished, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                if (editorElevationStroke) { finishEditorStroke(); }
                editorStrokeBoard = board;
                editor.command(command, owner, true);
                // The existing capture timer publishes drag previews at most once per tick. Capturing every
                // mouse event backs up the EDT with intermediate values the renderer cannot display anyway.
                if (finished) { finishEditorStroke(); refresh(); }
            }
        });
    }

    /** Reject pointer input from a board that has been replaced since the render frame was captured. */
    public void editorPointer(Coords coords, double x, double y, boolean drag, long generation) {
        editorPointer(coords, x, y, drag, null, generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, long generation) {
        editorPointer(coords, x, y, drag, object, false, generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, long generation) {
        editorPointer(coords, x, y, drag, object, additive, "ground", generation);
    }

    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, String receiver, long generation) {
        editorPointer(coords, x, y, drag, object, additive, receiver, false, generation);
    }

    @Override
    public void editorPointer(Coords coords, double x, double y, boolean drag, String object, boolean additive, String receiver,
          boolean invert, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords)) {
                if (editorElevationStroke) { finishEditorStroke(); }
                editorStrokeBoard = board;
                editor.pointer(coords, x, y, drag, object, additive, receiver, invert);
            }
        });
    }

    @Override
    public void editorSelect(List<BoardEditorSession.Selection> items, BoardEditorSession.SelectMode mode, long generation) {
        onSwing(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()) {
                finishEditorStroke();
                editor.select(items, mode);
                refresh();
            }
        });
    }

    public void paintEditor(Coords coords, int modifiers, long generation) {
        editorPointer(coords, 0, 0, false, generation);
    }

    public List<Coords> editorBrush(Coords center, long generation) {
        EditorBrush preview = editorBrush;
        return generation == preview.generation() && java.util.Objects.equals(center, preview.center())
              ? preview.hexes() : List.of();
    }

    @Override public String editorHint() { return editorBrush.hint(); }

    public void adjustEditorElevation(Coords coords, int levels, long generation) {
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null && generation == boardGeneration && board == game.getBoard()
                  && coords != null && board.contains(coords) && levels != 0) {
                if (!editorElevationStroke) {
                    finishEditorStroke();
                    editorStrokeBoard = board;
                    editorElevationStroke = true;
                }
                pendingElevation.add(Map.entry(coords, levels));
            }
        });
    }

    private void flushEditorElevation() {
        if (editorStrokeBoard != null && editorStrokeBoard == game.getBoard()) {
            pendingElevation.forEach(notch -> editor.adjustElevation(notch.getKey(), notch.getValue()));
        }
        pendingElevation.clear();
    }

    public void endEditorStroke() {
        onSwing(() -> {
            finishEditorStroke();
            if (!closed) {
                // The pointer's release, not an earlier key that finished the stroke, completes a click's drill-down.
                if (editor != null) { editor.release(); }
                refresh();
            }
        });
    }

    @Override
    public void cancelEditorStroke() {
        onSwing(() -> {
            if (closed || editor == null) { return; }
            editor.cancelStroke();
            endEditorStroke();
        });
    }

    private void finishEditorStroke() {
        flushEditorElevation();
        editorElevationStroke = false;
        if (editorStrokeBoard != null) {
            if (editorStrokeBoard == game.getBoard()) {
                editor.finishStroke();
            }
            editorStrokeBoard = null;
        }
    }

    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::close); return; }
        if (closed) { return; }
        closed = true;
        if (editorFiles != null) { editorFiles.clear(); }
        printRequest.set(null);
        finishEditorStroke();
        timer.stop();
        closeMeasuring();
        game.removeGameListener(gameListener);
        if (board != null) { board.removeBoardListener(boardListener); }
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferences);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferences);
        KeyBindParser.removePreferenceChangeListener(keyBinds);
        terrain.close();
        images.clear();
        tiles = List.of();
    }
}
