/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.gdx.UiButton;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real input through both map workspaces: menu placement, modal input, document save and window close dispatch. */
@Tag("on-demand")
class GpuMapMenuSmokeTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void menuWorksInPreviewAndEditor(boolean editing) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires an OpenGL window");
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger switches = new AtomicInteger();
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(9, 9));
            if (editor != null) {
                editor.save(temporary.resolve("menu.board2"));
                editor.pointer(new Coords(4, 4), 0, 0, false);
                editor.command(new BoardEditorSession.Command(BoardEditorSession.Action.ELEVATION, "1"), null);
                assertTrue(editor.snapshot().dirty());
            }
            return new GpuMapSource(game, null, editor, () -> {
                assertTrue(SwingUtilities.isEventDispatchThread(), "Close must use the window's EDT handler");
                closes.incrementAndGet();
            }, () -> {
                assertTrue(SwingUtilities.isEventDispatchThread(), "View switching belongs to the window's EDT handler");
                switches.incrementAndGet();
            });
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 800);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view); settle(view, source);
                        var stage = GpuBoardTestUi.stage();
                        UiButton menu = stage.getRoot().findActor("utility-menu");
                        assertNotNull(menu);
                        Vector2 corner = menu.localToStageCoordinates(new Vector2());
                        assertEquals(GpuBoardHud.GAP, stage.getWidth() - corner.x - menu.getWidth(), .5f);
                        assertEquals(GpuBoardHud.GAP, stage.getHeight() - corner.y - menu.getHeight(), .5f);
                        assertEquals(GpuUtilityBar.HEIGHT, menu.getWidth(), .5f);
                        if (editing) {
                            GpuBoardTestUi.click("editor-settings-button"); settle(view, source);
                            assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-settings")));
                            press(Input.Keys.ESCAPE); settle(view, source);
                        }
                        GpuBoardTestUi.click("utility-menu"); settle(view, source);
                        Actor panel = stage.getRoot().findActor("menu-panel");
                        assertTrue(GpuBoardTestUi.shown(panel));
                        assertTrue(menu.isChecked());
                        // The editor's Legacy 2D Preview; a preview switches to Legacy 2D in View > Renderer.
                        assertEquals(editing, GpuBoardTestUi.shown(stage.getRoot().findActor("/boardPreview2D")));
                        if (editing) {
                            UiButton preview2D = stage.getRoot().findActor("/boardPreview2D");
                            assertEquals("Legacy 2D Preview", preview2D.getText().toString());
                        }
                        assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("/viewClientSettings")));
                        assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("/helpAbout")));
                        assertEquals(editing, GpuBoardTestUi.shown(stage.getRoot().findActor("/fileBoardSave")));
                        // The launcher's Recent Boards, a submenu of the editor's Menu.
                        assertEquals(editing, GpuBoardTestUi.shown(stage.getRoot().findActor("/fileBoardRecent")));
                        // Run Board Tagger (not clicked here: it rewrites the board files of the boards directories).
                        assertEquals(editing, GpuBoardTestUi.shown(stage.getRoot().findActor("/boardRunBoardTagger")));
                        if (editing) {
                            assertFalse(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-settings")));
                        }
                        Vector2 center = panel.localToStageCoordinates(new Vector2(panel.getWidth() / 2, panel.getHeight() / 2));
                        assertTrue(stage.hit(center.x, center.y, true).isDescendantOf(panel), "Menu must be above editor panels");
                        float zoom = view.boardCamera.camera.zoom;
                        var tool = source.editorState() == null ? null : source.editorState().tool();
                        press(Input.Keys.C); press(Input.Keys.Z); settle(view, source);
                        assertEquals(zoom, view.boardCamera.camera.zoom, "Menu blocks camera shortcuts");
                        if (editing) { assertEquals(tool, source.editorState().tool(), "Menu blocks editor tool shortcuts"); }
                        GpuBoardTestUi.capture(new File(output, editing ? "editor-menu.png" : "preview-menu.png"));

                        press(Input.Keys.ESCAPE); settle(view, source);
                        assertFalse(GpuBoardTestUi.shown(panel));
                        assertFalse(menu.isChecked());
                        checkViewMenu(view, source, editing, output);
                        // Menu → About opens the native About card, not the Swing box; Close returns to the map.
                        GpuBoardTestUi.click("utility-menu"); settle(view, source);
                        GpuBoardTestUi.click("/helpAbout"); settle(view, source);
                        assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("about-card")), "About is native");
                        assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("about-wiki")));
                        assertFalse(GpuBoardTestUi.shown(panel), "About replaces the Menu");
                        GpuBoardTestUi.capture(new File(output, editing ? "editor-about.png" : "preview-about.png"));
                        GpuBoardTestUi.click("about-close"); settle(view, source);
                        assertFalse(GpuBoardTestUi.shown(stage.getRoot().findActor("about-card")));
                        assertTrue(java.util.Arrays.stream(java.awt.Window.getWindows()).noneMatch(java.awt.Window::isShowing),
                              "no Swing About box opened");
                        if (editing) { checkEditorEntries(view, source, output); }
                        GpuBoardTestUi.click("utility-menu"); settle(view, source);
                        if (editing) {
                            GpuBoardTestUi.click("/boardPreview2D"); settle(view, source);
                        } else {
                            // checkViewMenu left View closed.
                            GpuBoardTestUi.click("/view"); settle(view, source);
                            GpuBoardTestUi.click("/view/viewRenderer"); settle(view, source);
                            GpuBoardTestUi.click("/view/viewRenderer/viewLegacy2D"); settle(view, source);
                        }
                        assertEquals(1, switches.get());
                        assertEquals(0, closes.get(), "Switching views must not run the close/discard action");
                        assertFalse(GpuBoardTestUi.shown(panel));
                        GpuBoardTestUi.click("utility-menu"); settle(view, source);
                        Actor modal = stage.getRoot().findActor("map-menu-modal");
                        assertEquals(modal, stage.hit(1, 1, true), "Backdrop intercepts board presses");
                        Vector2 outside = stage.stageToScreenCoordinates(new Vector2(1, 1));
                        var input = Gdx.input.getInputProcessor();
                        input.touchDown((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT);
                        input.touchUp((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT);
                        settle(view, source);
                        assertFalse(GpuBoardTestUi.shown(panel), "Backdrop dismisses the menu");
                        if (editing) {
                            GpuBoardTestUi.click("utility-menu"); settle(view, source);
                            GpuBoardTestUi.click("/fileBoardSave"); settle(view, source);
                            assertFalse(GpuBoardTestUi.shown(panel));
                            assertFalse(source.editorState().dirty(), "Menu saves through the existing document command");
                        }
                        GpuBoardTestUi.click("utility-menu"); settle(view, source);
                        press(Input.Keys.END); press(Input.Keys.ENTER); settle(view, source);
                        assertEquals(1, closes.get(), "Keyboard selection dispatches the existing close handler once");
                        assertFalse(GpuBoardTestUi.shown(panel));
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(source::close);
        }
        if (failure.get() != null) { throw new AssertionError(editing ? "Editor menu" : "Preview menu", failure.get()); }
        BoardScene.Command close = source.takeFrame().globalCommands().stream()
              .filter(command -> command.id().equals("close")).findFirst().orElseThrow();
        close.action().run();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, closes.get(), "A stale menu cannot dispatch after the source closes");
    }

    private static void checkViewMenu(GpuBattleView view, GpuMapSource source, boolean editing, File output) throws Exception {
        GpuBoardTestUi.click("utility-menu"); settle(view, source);
        GpuBoardTestUi.click("/view"); settle(view, source);
        var stage = GpuBoardTestUi.stage();
        for (String id : List.of("viewRenderer", "viewToggleHexCoords", "viewToggleHexTypes")) {
            assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("/view/" + id)), id);
        }
        // Not in this View menu: the camera's commands, and Swing's GUI scale, which is Swing's alone.
        for (String id : List.of("viewToggleIsometric", "viewWireframe", "viewZoomIn", "viewZoomOut",
              "viewZoomReset", "viewZoomOverviewToggle", "viewFitBoard", "viewLOSSetting", "viewIncGUIScale",
              "viewDecGUIScale")) {
            assertFalse(GpuBoardTestUi.shown(stage.getRoot().findActor("/view/" + id)), id);
        }
        GpuBoardTestUi.capture(new File(output, editing ? "editor-view-menu.png" : "preview-view-menu.png"));

        // Restore the compact root menu before the remaining modal/document/close checks.
        GpuBoardTestUi.click("/view"); settle(view, source);
        press(Input.Keys.ESCAPE); settle(view, source);
    }

    /**
     * The editor's parity entries: File commands (New, Save As Image, Open Board Source File), Help with the keys and
     * gestures, and the View menu's Trace Overlay toggle and settings. New opens Settings at its Size section.
     */
    private static void checkEditorEntries(GpuBattleView view, GpuMapSource source, File output) throws Exception {
        var root = GpuBoardTestUi.stage().getRoot();
        GpuBoardTestUi.click("utility-menu"); settle(view, source);
        for (String id : List.of("/fileBoardNew", "/fileBoardSaveAsImage", "/boardSourceFile", "/boardEditorHelp")) {
            assertTrue(GpuBoardTestUi.shown(root.findActor(id)), id);
        }
        GpuBoardTestUi.click("/view"); settle(view, source);
        assertTrue(GpuBoardTestUi.shown(root.findActor("/view/boardTraceOverlay")));
        assertTrue(GpuBoardTestUi.shown(root.findActor("/view/boardTraceOverlaySettings")));
        GpuBoardTestUi.click("/view"); settle(view, source);
        GpuBoardTestUi.click("/boardEditorHelp"); settle(view, source);
        Actor help = root.findActor("editor-help");
        assertTrue(GpuBoardTestUi.shown(help));
        var texts = GpuBoardTestUi.texts(help);
        for (String key : List.of("BoardEditor.help.paint", "BoardEditor.help.undo", "BoardEditor.help.ctrlWheel",
              "KeyBinds.cmdNames.cameraFitBoard")) {
            assertTrue(texts.contains(megamek.client.ui.Messages.getString(key)), key + " in " + texts);
        }
        assertTrue(texts.contains("C"), "The Paint tool's key");
        GpuBoardTestUi.capture(new File(output, "editor-help.png"));
        press(Input.Keys.ESCAPE); settle(view, source);
        assertFalse(GpuBoardTestUi.shown(help));

        GpuBoardTestUi.click("utility-menu"); settle(view, source);
        GpuBoardTestUi.click("/fileBoardNew"); settle(view, source);
        assertTrue(GpuBoardTestUi.shown(root.findActor("editor-settings")), "New opens the board settings");
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane scroll = root.findActor("editor-settings-scroll");
        Actor size = root.findActor("editor-settings-size");
        Vector2 top = size.localToActorCoordinates(scroll, new Vector2(0, size.getHeight()));
        GpuBoardTestUi.capture(new File(output, "editor-menu-new.png"));
        // Scrolled down to the Size heading: at the top of the view, or as near as the end of the list allows.
        assertTrue(scroll.getScrollY() > 0 && top.y <= scroll.getHeight() + 1 && top.y - size.getHeight() >= 0
              && (top.y > scroll.getHeight() - 60 || scroll.getScrollY() >= scroll.getMaxY() - 1),
              "at the Size section: its heading at " + top.y + " of " + scroll.getHeight());
        press(Input.Keys.ESCAPE); settle(view, source);
        assertFalse(GpuBoardTestUi.shown(root.findActor("editor-settings")));
    }

    private static void press(int key) {
        GpuBoardTestUi.withModifiers(0, () -> {
            Gdx.input.getInputProcessor().keyDown(key);
            Gdx.input.getInputProcessor().keyUp(key);
        });
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
