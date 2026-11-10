/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Tool;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuMapSourceTest {
    @Test void authoredGestureIsPublishedWithItsPoseAndEndsOnRelease() throws Exception {
        onEdt(() -> {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(9, 9), null);
            var at = new Coords(4, 4);
            editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
            editor.pointer(at, 0, 0, false); editor.finishStroke();
            var id = editor.board().getHex(at).getDecorations().getFirst().id();
            editor.command(new Command(Action.TOOL, "SELECT"), null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                long generation = source.takeFrame().boardGeneration();
                assertFalse(source.editorAuthoredGesture());
                source.editorPointer(at, 0, 0, false, id, generation);
                source.editorPointer(at, .2, .1, true, id, generation);
                source.refresh();
                assertFalse(source.editorAuthoredGesture(), "The renderer retains its latched publication until takeFrame");
                source.takeFrame();
                assertTrue(source.editorAuthoredGesture(), "Pointer drags defer terrain slicing");
                source.endEditorStroke(); source.takeFrame();
                assertFalse(source.editorAuthoredGesture());
                source.editorValue(new Command(Action.OBJECT_VALUE, "rotation", "15"), false, generation);
                source.refresh(); source.takeFrame();
                assertTrue(source.editorAuthoredGesture(), "Inspector drags use the same release boundary");
                source.editorValue(new Command(Action.OBJECT_VALUE, "rotation", "30"), true, generation);
                source.takeFrame();
                assertFalse(source.editorAuthoredGesture());
                source.editorValue(new Command(Action.OBJECT_VALUE, "rotation", "45"), false, generation);
                source.refresh(); source.takeFrame();
                assertTrue(source.editorAuthoredGesture());
                source.cancelEditorStroke(); source.takeFrame();
                assertFalse(source.editorAuthoredGesture(), "Cancelling a gesture also resumes normal terrain updates");
            }
            return null;
        });
    }

    @TempDir
    Path temporary;

    @Test
    void inspectorWallCfRefreshesItsFamilyWithoutRebuildingTheBoardsBuildingObjects() throws Exception {
        onEdt(() -> {
            var editor = new BoardEditorSession();
            Coords at = new Coords(0, 0);
            Board board = new Board();
            board.newData(1, 1, new Hex[] { new Hex(0,
                  "building:5:0;bldg_elev:2;bldg_cf:150;bldg_basement_type:1", "") }, null);
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                var before = source.takeFrame().scene().tile(at);
                assertEquals(BoardWall.Family.HARDENED, BoardWall.family(before));
                long generation = source.takeFrame().boardGeneration();
                source.editorPointer(at, 0, 0, false, generation);
                source.endEditorStroke();
                source.editorCommand(new Command(Action.TERRAIN, "bldg_cf", "11"), generation);
                var after = source.takeFrame().scene().tile(at);
                assertEquals(150, board.getBuildingAt(at).getCurrentCF(at), "Map edits leave gameplay objects untouched");
                assertEquals(BoardWall.Family.LIGHT, BoardWall.family(after));
                assertFalse(before.sameGeometry(after));
                source.editorCommand(new Command(Action.UNDO), generation);
                assertEquals(BoardWall.Family.HARDENED, BoardWall.family(source.takeFrame().scene().tile(at)));
                source.editorCommand(new Command(Action.REDO), generation);
                assertEquals(BoardWall.Family.LIGHT, BoardWall.family(source.takeFrame().scene().tile(at)));
            }
            return null;
        });
    }

    /**
     * Destroyed brings its structure down as a game does: the frame that shows the ruin carries the game's collapse and
     * its checkpoint, once. Any other change of the tiles, a restore, undo or redo, carries none and shows at once
     * (GpuBattleView#renderFrame), as does a destroy restored before its frame was taken.
     */
    @Test
    void destroyedPlaysTheGamesCollapseOnceAndAnyOtherChangeShowsAtOnce() throws Exception {
        onEdt(() -> {
            var editor = new BoardEditorSession();
            Coords at = new Coords(0, 0);
            Board board = new Board();
            board.newData(1, 1, new Hex[] { new Hex(0, "bldg_elev:3;building:2:0;bldg_cf:40", "") }, null);
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                long generation = source.takeFrame().boardGeneration();
                source.editorCommand(new Command(Action.SELECT_AT, "0,0", ""), generation);
                source.takeFrame();
                source.editorCommand(new Command(Action.DESTROYED, "", "true"), generation);
                var destroyed = source.takeFrame();
                assertEquals(2, destroyed.timeline().size(), "The collapse and its checkpoint: " + destroyed.timeline());
                var collapse = (BoardScene.TileTransition) destroyed.timeline().getFirst();
                assertEquals(List.of(at), collapse.collapses().stream().map(BoardScene.TileTransition.Collapse::coords).toList());
                assertTrue(BoardScene.TileTransition.structure(collapse.collapses().getFirst().before()));
                assertFalse(BoardScene.TileTransition.structure(destroyed.scene().tile(at)), "The frame shows the ruin");
                assertSame(destroyed.scene().tiles(), ((BoardScene.SceneUpdate) destroyed.timeline().get(1)).scene().tiles(),
                      "Its checkpoint is the frame's own scene");
                assertTrue(source.takeFrame().timeline().isEmpty(), "It plays once");
                for (var other : List.of(new Command(Action.DESTROYED, "", "false"), new Command(Action.UNDO),
                      new Command(Action.REDO))) {
                    source.editorCommand(other, generation);
                    assertTrue(source.takeFrame().timeline().isEmpty(), other.action() + " shows at once");
                }
                source.editorCommand(new Command(Action.DESTROYED, "", "true"), generation);
                source.editorCommand(new Command(Action.DESTROYED, "", "false"), generation);
                var restored = source.takeFrame();
                assertTrue(BoardScene.TileTransition.structure(restored.scene().tile(at)), "It stands again");
                assertTrue(restored.timeline().isEmpty(), "A destroy restored before it was shown plays nothing");
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void printCapturesEveryLabelWithoutChangingTheViewOrDocument(boolean editing) throws Exception {
        onEdt(() -> {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean coordinates = preferences.getCoordsEnabled();
            boolean types = preferences.getHexTypesEnabled();
            boolean outline = preferences.getHexLabelOutline();
            try {
                preferences.setValue(GUIPreferences.SHOW_COORDS, false);
                preferences.setValue(GUIPreferences.SHOW_HEX_TYPES, false);
                preferences.setValue(GUIPreferences.HEX_LABEL_OUTLINE, false);
                BoardEditorSession editor = editing ? new BoardEditorSession() : null;
                Game game = editor == null ? new Game() : editor.game();
                Board board = Board.createEmptyBoard(3, 2);
                Coords woods = new Coords(1, 1);
                board.setHex(woods, new Hex(2, "woods:1;foliage_elev:2;rough:1", "", woods));
                game.setBoard(board);
                AtomicInteger completions = new AtomicInteger();
                try (var source = new GpuMapSource(game, null, editor)) {
                    boolean dirty = editor != null && editor.dirty();
                    var displayed = source.takeFrame().scene();
                    assertTrue(command(source, "fileBoardPrint").enabled());
                    Path target = temporary.resolve("print.jpg");
                    source.requestPrint(target, () -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        completions.incrementAndGet();
                    }, failure -> { throw new AssertionError(failure); });
                    assertFalse(command(source, "fileBoardPrint").enabled());
                    var request = source.takePrintRequest();
                    assertNotNull(request);
                    assertEquals(target, request.target(), "The render thread writes the chosen file");
                    assertNull(source.takePrintRequest(), "The GL thread consumes a request only once");
                    assertEquals(6, request.scene().tiles().size());
                    for (var tile : request.scene().tiles()) {
                        assertTrue(tile.text().stream().anyMatch(label -> label.text().equals(tile.coords().getBoardNum())));
                        assertTrue(tile.text().stream().allMatch(label -> label.argb() == 0xFF000000));
                        assertSame(displayed.tile(tile.coords()).tileset(), tile.tileset(), "The print borrows captured 3D art");
                    }
                    var labels = request.scene().tile(woods).text().stream().map(label -> label.text()).toList();
                    assertTrue(labels.contains("LIGHT"));
                    assertTrue(labels.contains("ROUGH"));
                    assertSame(displayed.tiles(), source.takeFrame().scene().tiles(), "Print labels do not replace view labels");
                    assertFalse(preferences.getCoordsEnabled());
                    assertFalse(preferences.getHexTypesEnabled());
                    assertFalse(preferences.getHexLabelOutline());
                    if (editor != null) { assertEquals(dirty, editor.dirty()); }
                    source.requestPrint(target, () -> { throw new AssertionError("A second print was queued"); },
                          failure -> { throw new AssertionError(failure); });
                    assertNull(source.takePrintRequest(), "Only one print runs at a time");
                    request.completed().run();
                    assertEquals(1, completions.get());
                    assertTrue(command(source, "fileBoardPrint").enabled());
                    source.requestPrint(target, completions::incrementAndGet, failure -> { throw new AssertionError(failure); });
                    var cancelled = source.takePrintRequest();
                    source.close();
                    cancelled.completed().run();
                    assertEquals(1, completions.get(), "A closed workspace reports no completed print");
                    assertNull(source.takePrintRequest());
                }
            } finally {
                preferences.setValue(GUIPreferences.SHOW_COORDS, coordinates);
                preferences.setValue(GUIPreferences.SHOW_HEX_TYPES, types);
                preferences.setValue(GUIPreferences.HEX_LABEL_OUTLINE, outline);
            }
            return null;
        });
    }

    /**
     * 8,000 pixels wide, so that a restart interval is 512 rows: the 1,024-row band holds two and the last band one
     * shorter interval, which the restart markers join into one picture.
     */
    @Test
    void printableJpegJoinsBandsAndKeepsTheMapsheetHexSizeAndFullColour() throws Exception {
        Path output = temporary.resolve("print.jpg");
        var image = new BufferedImage(8000, 1300, BufferedImage.TYPE_3BYTE_BGR);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            // Squares across both interval joins (rows 512 and 1024, also the band join), on the 8-pixel JPEG blocks so
            // that no block mixes a square and the white.
            graphics.setColor(new java.awt.Color(0x4A719A));
            graphics.fillRect(40, 480, 64, 64);
            graphics.fillRect(7896, 992, 64, 64);
            // A one-pixel red line: subsampled chroma would spread and fade it.
            graphics.setColor(java.awt.Color.RED);
            graphics.drawLine(150, 20, 150, 1280);
        } finally { graphics.dispose(); }
        try (var jpeg = new GpuPrintJpeg(output, image.getWidth(), image.getHeight(), GpuBoardPrint.DPI, GpuBoardPrint.DPI)) {
            jpeg.write(image.getSubimage(0, 0, image.getWidth(), 1024));
            jpeg.write(image.getSubimage(0, 1024, image.getWidth(), 276));
        }
        var reader = ImageIO.getImageReadersByFormatName("jpeg").next();
        try (var input = ImageIO.createImageInputStream(output.toFile())) {
            reader.setInput(input);
            var root = (IIOMetadataNode) reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0");
            var density = (IIOMetadataNode) root.getElementsByTagName("app0JFIF").item(0);
            assertNotNull(density);
            assertEquals("1", density.getAttribute("resUnits"), "The density is in dots per inch");
            assertEquals(1.5, GpuBoardPrint.HEX_WIDTH_PIXELS / Double.parseDouble(density.getAttribute("Xdensity")),
                  .00001, "Printed hex width matches official mapsheets without fit-to-page scaling");
            assertEquals(1.3, GpuBoardPrint.PIXELS_PER_HEX / Double.parseDouble(density.getAttribute("Ydensity")),
                  .00001, "Printed hex height matches official mapsheets without fit-to-page scaling");
            var components = root.getElementsByTagName("componentSpec");
            for (int index = 0; index < components.getLength(); index++) {
                var component = (IIOMetadataNode) components.item(index);
                assertEquals("1", component.getAttribute("HsamplingFactor"), "No chroma subsampling");
                assertEquals("1", component.getAttribute("VsamplingFactor"), "No chroma subsampling");
            }
            var saved = reader.read(0);
            assertEquals(image.getWidth(), saved.getWidth());
            assertEquals(image.getHeight(), saved.getHeight());
            for (int y = 0; y < image.getHeight(); y += 7) {
                for (int x : new int[] { 0, 72, 2000, 7930, 7999 }) {
                    assertClose(image.getRGB(x, y), saved.getRGB(x, y), 6);
                }
                assertClose(0xFF0000, saved.getRGB(150, Math.max(20, Math.min(1280, y))), 40);
            }
        } finally { reader.dispose(); }
    }

    @Test
    void anUnfinishedPrintLeavesNoFile() throws Exception {
        Path output = temporary.resolve("unfinished.jpg");
        try (var jpeg = new GpuPrintJpeg(output, 400, 2000, GpuBoardPrint.DPI, GpuBoardPrint.DPI)) {
            jpeg.write(new BufferedImage(400, 1024, BufferedImage.TYPE_3BYTE_BGR));
            assertTrue(java.nio.file.Files.exists(output));
        }
        assertFalse(java.nio.file.Files.exists(output), "A cancelled or failed print deletes its partial JPEG");
    }

    private static void assertClose(int expected, int actual, int tolerance) {
        for (int shift : new int[] { 16, 8, 0 }) {
            assertTrue(Math.abs((expected >> shift & 255) - (actual >> shift & 255)) <= tolerance,
                  String.format("%06X is not within %d of %06X", actual & 0xFFFFFF, tolerance, expected));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void viewCommandsUseSharedPreferencesWithoutChangingTheDocument(boolean editing) throws Exception {
        onEdt(() -> {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean coordinates = preferences.getCoordsEnabled();
            float scale = preferences.getGUIScale();
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(4, 4));
            boolean dirty = editor != null && editor.dirty();
            try {
                preferences.setValue(GUIPreferences.SHOW_COORDS, true);
                BoardScene.Command stale;
                try (var source = new GpuMapSource(game, null, editor);
                      var other = new GpuMapSource(game, null, null)) {
                    long generation = source.takeFrame().boardGeneration();
                    Coords at = new Coords(2, 2);
                    assertTrue(source.takeFrame().scene().tile(at).text().stream()
                          .anyMatch(label -> label.text().equals(at.getBoardNum())));
                    stale = viewCommand(source, ClientGUI.VIEW_TOGGLE_HEX_COORDS);
                    assertEquals(Boolean.TRUE, stale.selected());
                    assertEquals(editing ? "" : KeyCommandBind.getDesc(KeyCommandBind.HEX_COORDS), stale.shortcut());
                    stale.action().run();
                    source.refresh(); other.refresh();
                    assertFalse(preferences.getCoordsEnabled());
                    for (var workspace : List.of(source, other)) {
                        assertEquals(Boolean.FALSE, viewCommand(workspace, ClientGUI.VIEW_TOGGLE_HEX_COORDS).selected());
                        assertTrue(workspace.takeFrame().scene().tile(at).text().stream()
                              .noneMatch(label -> label.text().equals(at.getBoardNum())), "The toggle removes rendered coordinate labels");
                    }
                    source.key(KeyCommandBind.HEX_COORDS.key, true, KeyCommandBind.HEX_COORDS.modifiers);
                    assertEquals(!editing, preferences.getCoordsEnabled(), "The editor keeps Ctrl+G for Group");
                    source.key(KeyCommandBind.LOS_SETTING.key, true, KeyCommandBind.LOS_SETTING.modifiers);
                    assertTrue(source.takeFrame().panels().los().open());
                    // Swing's GUI scale is Swing's alone: the View menu has none, and its key changes no scale.
                    for (String swing : List.of(ClientGUI.VIEW_INC_GUI_SCALE, ClientGUI.VIEW_DEC_GUI_SCALE)) {
                        assertTrue(source.takeFrame().globalCommands().stream()
                              .filter(command -> command.id().equals("view"))
                              .flatMap(command -> command.children().stream())
                              .noneMatch(command -> command.id().equals(swing)), swing);
                    }
                    source.key(KeyCommandBind.DEC_GUI_SCALE.key, true, KeyCommandBind.DEC_GUI_SCALE.modifiers);
                    assertEquals(scale, preferences.getGUIScale());
                    assertEquals(preferences.getBoardUiScale(), source.uiPreferences().scale());
                    assertEquals(generation, source.takeFrame().boardGeneration());
                    if (editor != null) {
                        assertEquals(dirty, editor.dirty());
                        assertFalse(editor.snapshot().canUndo(), "View commands do not edit the board");
                    }
                }
                boolean before = preferences.getCoordsEnabled();
                stale.action().run();
                assertEquals(before, preferences.getCoordsEnabled(), "A closed workspace cannot toggle shared preferences");
            } finally {
                preferences.setValue(GUIPreferences.SHOW_COORDS, coordinates);
            }
            return null;
        });
    }

    /**
     * The editor's Menu has Save As Image, Open Board Source File (only once the document has a file) and, in View, the
     * Trace Overlay toggle and its settings. The toggle is the shared preference; the editor's trace settings follow it
     * without editing the document or capturing the board again. A map preview has none of these.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void editorMenuHasTheFileCommandsAndTheTraceOverlay(boolean editing) throws Exception {
        onEdt(() -> {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean shown = preferences.getShowTraceOverlay();
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(4, 4));
            try (var source = new GpuMapSource(game, null, editor)) {
                var ids = source.takeFrame().globalCommands().stream().map(BoardScene.Command::id).toList();
                assertEquals(editing, ids.contains("fileBoardSaveAsImage"));
                assertEquals(editing, ids.contains("boardSourceFile"));
                var view = source.takeFrame().globalCommands().stream().filter(command -> command.id().equals("view"))
                      .flatMap(command -> command.children().stream()).map(BoardScene.Command::id).toList();
                assertEquals(editing, view.contains("boardTraceOverlay"));
                assertEquals(editing, view.contains("boardTraceOverlaySettings"));
                if (!editing) {
                    assertSame(GpuTraceOverlay.Settings.NONE, source.trace());
                    return null;
                }
                assertFalse(command(source, "boardSourceFile").enabled(), "A document never saved has no file");
                editor.save(temporary.resolve("menu.board2"));
                source.refresh();
                assertEquals(java.awt.Desktop.isDesktopSupported(), command(source, "boardSourceFile").enabled());

                var tiles = source.takeFrame().scene().tiles();
                var toggle = viewCommand(source, "boardTraceOverlay");
                assertEquals(shown, toggle.selected());
                assertEquals(shown, source.trace().enabled());
                toggle.action().run();
                assertEquals(!shown, preferences.getShowTraceOverlay());
                assertEquals(!shown, source.trace().enabled());
                source.refresh();
                assertEquals(!shown, viewCommand(source, "boardTraceOverlay").selected());
                assertSame(tiles, source.takeFrame().scene().tiles(), "A trace setting captures no board art again");
                assertFalse(editor.dirty(), "The trace overlay is no part of the board");
            } finally {
                preferences.setValue(GUIPreferences.SHOW_TRACE_OVERLAY, shown);
            }
            return null;
        });
    }

    @Test
    void previewMenuOpensTheEditorOnSwingAndRejectsClosedWorkspaceActions() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean requestedOnSwing = new AtomicBoolean();
        Runnable edit = () -> {
            requestedOnSwing.set(SwingUtilities.isEventDispatchThread());
            requests.incrementAndGet();
        };
        GpuMapSource source = onEdt(() -> {
            var editor = new BoardEditorSession();
            try (var editing = new GpuMapSource(editor.game(), null, editor, null, null, edit);
                  var unavailable = new GpuMapSource(editor.game(), null, null)) {
                for (var workspace : List.of(editing, unavailable)) {
                    assertTrue(workspace.takeFrame().globalCommands().stream()
                          .noneMatch(command -> command.id().equals("boardEdit3D")));
                }
            }
            return new GpuMapSource(editor.game(), null, null, null, null, edit);
        });
        BoardScene.Command command;
        try {
            command = onEdt(() -> command(source, "boardEdit3D"));
            assertTrue(command.enabled());
            assertEquals(Messages.getString("BoardEditor.edit3D"), command.label());
            command.action().run();
            onEdt(() -> {
                assertEquals(1, requests.get());
                assertTrue(requestedOnSwing.get(), "Native menu input must hand the editor request to Swing");
                return null;
            });
        } finally {
            onEdt(() -> { source.close(); return null; });
        }
        command.action().run();
        onEdt(() -> {
            assertEquals(1, requests.get(), "A closed preview cannot open an editor from a stale menu action");
            return null;
        });
    }

    private static BoardScene.Command command(GpuMapSource source, String id) {
        return source.takeFrame().globalCommands().stream().filter(command -> command.id().equals(id)).findFirst().orElseThrow();
    }

    private static BoardScene.Command viewCommand(GpuMapSource source, String id) {
        return source.takeFrame().globalCommands().stream().filter(command -> command.id().equals("view"))
              .flatMap(command -> command.children().stream()).filter(command -> command.id().equals(id))
              .findFirst().orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    void elevationWheelChangesOnlyHoveredHexInEveryTool(Tool tool) throws Exception {
        Coords center = new Coords(4, 4);
        for (int delta : new int[] { -3, 3 }) {
            var editor = onEdt(() -> {
                var session = new BoardEditorSession();
                session.game().setBoard(Board.createEmptyBoard(9, 9));
                session.command(new Command(Action.TOOL, "", "SCULPT"), null);
                session.command(new Command(Action.BRUSH, "", "2"), null);
                return session;
            });
            var source = onEdt(() -> new GpuMapSource(editor.game(), null, editor));
            try {
                onEdt(() -> {
                    long generation = source.takeFrame().boardGeneration();
                    source.editorCommand(new Command(Action.TOOL, "", tool.name()), generation);
                    source.adjustEditorElevation(center, delta, generation);
                    return null;
                });
                // The wheel input is queued on Swing; finish after it has been accepted.
                onEdt(() -> {
                    source.endEditorStroke();
                    for (int x = 0; x < 9; x++) {
                        for (int y = 0; y < 9; y++) {
                            Coords at = new Coords(x, y);
                            int expected = at.equals(center) ? delta : 0;
                            assertEquals(expected, editor.board().getHex(at).getLevel(), tool + " at " + at);
                        }
                    }
                    source.editorCommand(new Command(Action.UNDO, "", ""), source.takeFrame().boardGeneration());
                    for (int x = 0; x < 9; x++) {
                        for (int y = 0; y < 9; y++) { assertEquals(0, editor.board().getHex(x, y).getLevel()); }
                    }
                    assertFalse(editor.snapshot().canUndo(), "A wheel gesture is one undo step");
                    return null;
                });
            } finally {
                onEdt(() -> { source.close(); return null; });
            }
        }
    }

    @Test
    void previewUsesOnlyTheModelAndReleasesItsListeners() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            var listeners = List.copyOf(game.getGameListeners());
            try (var source = new GpuMapSource(game, null, null)) {
                assertEquals(272, source.takeFrame().scene().tiles().size());
                assertTrue(source.takeFrame().scene().units().isEmpty());
                assertTrue(game.getGameListeners().stream().noneMatch(listener ->
                      listener.getClass().getName().contains("BoardView")), "Preview must not construct a BoardView");
                long generation = source.takeFrame().boardGeneration();
                Board oldBoard = game.getBoard();
                game.setBoard(Board.createEmptyBoard(6, 8));
                assertNotEquals(generation, source.takeFrame().boardGeneration());
                assertEquals(48, source.takeFrame().scene().tiles().size());
                oldBoard.setHex(new Coords(4, 4), new Hex(7));
                source.refresh();
                assertEquals(0, source.takeFrame().scene().tile(new Coords(4, 4)).elevation());
            }
            assertEquals(listeners, game.getGameListeners(), "Closing preview must detach every owned game listener");
            return null;
        });
    }

    @Test
    void incrementalEditsRetainDistantTilesAndMatchFreshCapture() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            Coords edited = new Coords(8, 8);
            try (var source = new GpuMapSource(game, null, null)) {
                for (Hex replacement : List.of(new Hex(3),
                      new Hex(0, new Terrain[] { new Terrain(Terrains.WATER, 2) }, null),
                      new Hex(-2, new Terrain[] { new Terrain(Terrains.FIELDS, 1) }, null), new Hex(1))) {
                    BoardScene before = source.takeFrame().scene();
                    game.getBoard().setHex(edited, replacement);
                    source.refresh();
                    BoardScene after = source.takeFrame().scene();
                    int retained = 0;
                    for (int i = 0; i < before.tiles().size(); i++) {
                        if (before.tiles().get(i) == after.tiles().get(i)) { retained++; }
                    }
                    assertTrue(retained >= 247, "A local edit must retain distant tile objects: " + retained);
                    try (var fresh = new GpuMapSource(game, null, null)) {
                        assertEquals(fresh.takeFrame().scene().tiles(), after.tiles());
                    }
                    source.refresh();
                    assertSame(after.tiles(), source.takeFrame().scene().tiles(), "Idle capture retains terrain snapshots");
                }
            }
            return null;
        });
    }

    @Test
    void nativeArtworkMatchesTheGameplayCaptureBoundary() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        try (var fixture = GpuBoardFixture.create(board)) {
            onEdt(() -> {
                try (var source = new GpuMapSource(fixture.game, null, null)) {
                    var expected = fixture.source.takeFrame().scene();
                    var actual = source.takeFrame().scene();
                    assertEquals(expected.width(), actual.width());
                    for (var tile : actual.tiles()) {
                        var previous = expected.tile(tile.coords());
                        assertEquals(previous.tileset(), tile.tileset());
                        assertEquals(previous.decals(), tile.decals());
                        assertEquals(previous.features(), tile.features());
                        assertEquals(previous.text(), tile.text());
                    }
                }
                return null;
            });
        }
    }

    /**
     * The map tools' hex card: the hex with its level and theme, then the terrains
     * a player sees with their terrain factors; automated and cosmetic terrains and terrain codes stay out.
     */
    @Test
    void theHexCardListsWhatAPlayerSeesWithoutAutomatedTerrains() {
        Board board = Board.createEmptyBoard(3, 3);
        Coords coords = new Coords(1, 2);
        board.setHex(coords, new Hex(1, "woods:1;foliage_elev:2;incline_top:1:28;ground_fluff:1:1", "grass"));
        board.setHex(new Coords(0, 0), new Hex(0));
        assertEquals(List.of("Hex 0203\tLevel 1 \u00B7 grass", "Light woods\tTF 50", "Woods/Jungle elevation: 2\t"),
              List.of(GpuMapSource.hexCard(board.getHex(coords)).split("\n")));
        assertEquals(List.of("Hex 0101\tLevel 0", "Clear\t"),
              List.of(GpuMapSource.hexCard(board.getHex(new Coords(0, 0))).split("\n")));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mapToolsUseAltClickAndPublishNativeMeasurementWithoutSwingWindows(boolean editing) throws Exception {
        onEdt(() -> {
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(8, 8));
            var listeners = List.copyOf(game.getGameListeners());
            var windows = java.util.Set.of(java.awt.Window.getWindows());
            try (var source = new GpuMapSource(game, null, editor)) {
                source.measure(new Coords(2, 2), InputEvent.CTRL_DOWN_MASK, Float.NaN);
                assertFalse(source.takeFrame().panels().los().open(), "Ctrl-click is no longer a measurement");
                source.measure(new Coords(2, 2), InputEvent.ALT_DOWN_MASK, 2 * BoardGeometry.level() + .1f);
                assertEquals(InputEvent.ALT_DOWN_MASK, source.takeFrame().panels().los().pending());
                source.measure(new Coords(2, 6), 0, Float.NaN);
                var frame = source.takeFrame();
                assertEquals(0, frame.panels().los().pending());
                assertEquals(2, frame.panels().los().start().height());
                assertEquals(4, frame.panels().los().distance());
                assertEquals(frame.panels().los().ruler(), frame.scene().tactical().ruler());
                source.changeRuler(frame.boardGeneration(), model -> model.height(false, 4));
                assertEquals(4, source.takeFrame().scene().tactical().ruler().endHeight());
                source.measure(new Coords(3, 3), 0, Float.NaN);
                assertEquals(frame.panels().los().end().coords(), source.takeFrame().panels().los().end().coords());
                game.setBoard(Board.createEmptyBoard(8, 8));
                source.refresh();
                source.changeRuler(frame.boardGeneration(), model -> model.open());
                assertFalse(source.takeFrame().panels().los().open(), "An old panel cannot edit a replacement board");
                assertEquals(windows, java.util.Set.of(java.awt.Window.getWindows()), "Native LOS creates no Swing window");
            }
            assertEquals(listeners, game.getGameListeners());
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
