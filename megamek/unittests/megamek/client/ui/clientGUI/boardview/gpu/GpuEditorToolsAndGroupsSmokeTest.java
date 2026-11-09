/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Tool;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTree;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tool keys, the toolbar and menu key details, box selection (also on roofs and bridge decks), the issues triangle, and
 * groups made and edited through the real input processor.
 */
@Tag("on-demand")
class GpuEditorToolsAndGroupsSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));

    @Test
    void toolKeysSwitchToolsInsideTheEditorOnly() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(Board.createEmptyBoard(9, 9));
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1280, 800, (view, source) -> {
            var stage = GpuBoardTestUi.stage();
            Actor toolbar = stage.getRoot().findActor("editor-toolbar");
            assertEquals(GpuBoardHud.GAP, stage.getHeight() - toolbar.getTop(), 1, "The toolbar keeps one row at 1280×800");
            float previous = -1;
            for (String tool : List.of("select", "sculpt", "paint", "erase")) {
                Actor button = stage.getRoot().findActor("editor-tool-" + tool);
                assertTrue(GpuBoardTestUi.shown(button));
                assertTrue(button.getX() > previous, "Toolbar order is Select, Sculpt, Paint, Erase"); previous = button.getX();
            }
            assertEquals(List.of("Z", "X", "C", "V"), GpuBoardTestUi.texts(toolbar).stream().filter(text -> text.length() == 1).toList(),
                  "Each tool shows its key, in order");
            assertFalse(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-issues")), "No issues, no triangle");
            assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-tool-keys-toolbar.png"));

            float zoom = view.boardCamera.camera.zoom;
            for (var key : List.of(new int[] { Input.Keys.X, Tool.SCULPT.ordinal() }, new int[] { Input.Keys.C, Tool.PAINT.ordinal() },
                  new int[] { Input.Keys.V, Tool.ERASE.ordinal() }, new int[] { Input.Keys.Z, Tool.SELECT.ordinal() })) {
                press(key[0]); settle(view, source);
                assertEquals(Tool.values()[key[1]], source.editorState().tool(), Input.Keys.toString(key[0]) + " switches the tool");
            }
            assertEquals(zoom, view.boardCamera.camera.zoom, "Z selects in the editor instead of toggling the overview");

            press(Input.Keys.C); settle(view, source);
            TextField search = stage.getRoot().findActor("editor-search");
            stage.setKeyboardFocus(search);
            var input = Gdx.input.getInputProcessor();
            input.keyDown(Input.Keys.Z); input.keyTyped('z'); input.keyUp(Input.Keys.Z); settle(view, source);
            assertEquals(Tool.PAINT, source.editorState().tool(), "Typing z in a text field does not switch tools");
            assertEquals("z", search.getText());
            search.setText(""); stage.setKeyboardFocus(null); settle(view, source);

            click(view, new Coords(4, 4), Input.Buttons.RIGHT); settle(view, source);
            var menu = stage.getRoot().findActor("map-context-menu");
            assertTrue(GpuBoardTestUi.shown(menu));
            assertTrue(GpuBoardTestUi.texts(menu).containsAll(List.of("Z", "X", "C", "V", "Ctrl + G", "Ctrl + Shift + G")),
                  "The menu shows tool keys and group shortcuts: " + GpuBoardTestUi.texts(menu));
            var texts = GpuBoardTestUi.texts(menu);
            assertTrue(texts.indexOf("SELECT") < texts.indexOf("SCULPT") && texts.indexOf("SCULPT") < texts.indexOf("PAINT")
                  && texts.indexOf("PAINT") < texts.indexOf("ERASE"), "The menu lists the tools in toolbar order: " + texts);
            assertTrue(((com.badlogic.gdx.scenes.scene2d.ui.Button) stage.getRoot().findActor("editor-context-group")).isDisabled(),
                  "Nothing to group without a selection");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-tool-keys-menu.png"));
            input.keyDown(Input.Keys.ESCAPE); settle(view, source);
        });
    }

    /** Esc takes one step a press: the selection clears first; with nothing left it opens the Menu, then closes it. */
    @Test
    void escapeClearsTheSelectionThenTogglesTheMenu() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(Board.createEmptyBoard(9, 9));
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1280, 800, (view, source) -> {
            var root = GpuBoardTestUi.stage().getRoot();
            // An editor opened before in this JVM left its Assets page, which Esc would leave first.
            for (int page = 0; page < 4 && root.findActor("editor-back") != null; page++) {
                GpuBoardTestUi.click("editor-back"); settle(view, source);
            }
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT, "4,4", ""),
                  source.takeFrame().boardGeneration());
            settle(view, source);
            assertEquals(new Coords(4, 4), source.editorState().selected());
            Actor menu = root.findActor("menu-panel");
            press(Input.Keys.ESCAPE); settle(view, source);
            assertNull(source.editorState().selected(), "The first Esc clears the selection");
            assertTrue(source.editorState().selection().isEmpty());
            assertFalse(GpuBoardTestUi.shown(menu));
            for (boolean open : List.of(true, false, true)) {
                press(Input.Keys.ESCAPE); settle(view, source);
                assertEquals(open, GpuBoardTestUi.shown(menu), "With nothing left, Esc toggles the Menu");
            }
        });
    }

    @Test
    void zStillTogglesTheMapPreviewOverview() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            Game game = new Game(); game.setBoard(Board.createEmptyBoard(9, 9));
            return new GpuMapSource(game, null, null);
        });
        run(setup, 1280, 800, (view, source) -> {
            view.boardCamera.zoom(.5f); settle(view, source);
            float zoom = view.boardCamera.camera.zoom;
            press(Input.Keys.Z); settle(view, source);
            assertNotEquals(zoom, view.boardCamera.camera.zoom, "Z toggles the preview's overview");
            press(Input.Keys.Z); settle(view, source);
            assertEquals(zoom, view.boardCamera.camera.zoom, .0001f, "A second Z restores the camera");
        });
    }

    @Test
    void boxSelectsGroupsAndClicksDrillDown() throws Exception {
        Coords owner = new Coords(4, 4), elsewhere = new Coords(6, 6);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            board.getHex(owner).setDecorations(List.of(car("left", -.25), car("right", .25)));
            board.getHex(elsewhere).setDecorations(List.of(car("loose", 0)));
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1600, 1000, (view, source) -> {
            GpuTerrain terrain = terrain(view);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (terrain.editorObjects(owner).size() < 2 || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "Object models must load");
                view.render();
            }
            var stage = GpuBoardTestUi.stage();
            var input = Gdx.input.getInputProcessor();
            Vector2 left = anchor(view, owner, -.25), right = anchor(view, owner, .25);
            int x0 = Math.round(left.x) - 30, y0 = Math.round(Math.min(left.y, right.y)) - 30;
            int x1 = Math.round(right.x) + 30, y1 = Math.round(Math.max(left.y, right.y)) + 30;
            GpuBoardTestUi.withModifiers(0, () -> input.touchDown(x0, y0, 0, Input.Buttons.LEFT));
            input.touchDragged((x0 + x1) / 2, (y0 + y1) / 2, 0);
            input.touchDragged(x1, y1, 0); settle(view, source);
            assertTrue(source.editorState().selection().isEmpty(), "A plain press on unselected ground neither selects nor moves");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-group-marquee.png"));
            input.touchUp(x1, y1, 0, Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of("left", "right"), objects(source), "The box selects the two objects it covers");

            click(view, owner, Input.Buttons.RIGHT); settle(view, source);
            assertEquals(List.of("left", "right"), objects(source), "The right-click keeps the selection");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-group-menu.png"));
            GpuBoardTestUi.click("editor-context-group"); settle(view, source);
            String group = source.editorState().group();
            assertFalse(group.isEmpty(), "Group makes the selection one group");
            assertTrue(GpuBoardTestUi.texts(stage.getRoot().findActor("editor-inspector")).contains(megamek.client.ui.gdx.UiTheme.upper("Group transform")));
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-group-selected.png"));

            click(view, new Coords(2, 2), Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of(""), objects(source), "Clicking ground selects only its hex");
            Vector2 member = top(view, terrain, owner, "left");
            clickAt(member); settle(view, source);
            assertEquals(List.of("left", "right"), objects(source), "Clicking a member selects its group");
            clickAt(member); settle(view, source);
            assertEquals(List.of("left"), objects(source), "A second click drills down to the member");

            Actor header = stage.getRoot().findActor("editor-group-" + group);
            Actor row = stage.getRoot().findActor("editor-content-left");
            assertTrue(GpuBoardTestUi.shown(header) && GpuBoardTestUi.shown(row));
            assertTrue(GpuBoardTestUi.texts(header).contains("Group · 2 objects"));
            assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-content-right")), "A drilled-in member keeps its sibling listed");
            assertTrue(((UiButton) row).isChecked() && !((UiButton) header).isChecked(),
                  "The drilled-in member's row is the highlighted one");
            float headerX = header.localToStageCoordinates(new Vector2()).x, rowX = row.localToStageCoordinates(new Vector2()).x;
            assertTrue(rowX > headerX + 8, "Members are nested below their group header");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-group-contents.png"));

            // Shift-click adds a loose object; Ctrl-click removes one.
            Vector2 loose = top(view, terrain, elsewhere, "loose");
            GpuBoardTestUi.withModifiers(InputEvent.SHIFT_DOWN_MASK,
                  () -> input.touchDown(Math.round(loose.x), Math.round(loose.y), 0, Input.Buttons.LEFT));
            input.touchUp(Math.round(loose.x), Math.round(loose.y), 0, Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of("left", "loose"), objects(source), "Shift-click adds the object");
            GpuBoardTestUi.withModifiers(InputEvent.CTRL_DOWN_MASK,
                  () -> input.touchDown(Math.round(member.x), Math.round(member.y), 0, Input.Buttons.LEFT));
            input.touchUp(Math.round(member.x), Math.round(member.y), 0, Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of("loose"), objects(source), "Ctrl-click removes the object");
            click(view, new Coords(2, 2), Input.Buttons.LEFT); settle(view, source);
            clickAt(member); settle(view, source);
            assertEquals(List.of("left", "right"), objects(source));
            assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-group-" + group)));
            assertTrue(((UiButton) stage.getRoot().findActor("editor-group-" + group)).isChecked(),
                  "A whole selected group highlights its row");
            assertTrue(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-content-left"))
                  && GpuBoardTestUi.shown(stage.getRoot().findActor("editor-content-right")), "and keeps its members listed");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-group-whole-contents.png"));

            // On a selected hex a click on an object standing there selects the object, not the hex.
            clickAt(anchor(view, elsewhere, .3)); settle(view, source);
            assertEquals(List.of(new BoardEditorSession.Selection(elsewhere, "")), source.editorState().selection());
            clickAt(loose); settle(view, source);
            assertEquals(List.of("loose"), objects(source), "Clicking a car on the selected hex selects the car");

            // With a selection, a box may start on the background beyond the board.
            // One hex width left of the first column's centre lies on the background, left of the board's edge.
            int bx0 = Math.round(anchor(view, new Coords(0, owner.getY()), -1).x), by0 = y0;
            assertTrue(bx0 > 0, "The press lies inside the window, left of the board");
            Vector2 outside = stage.screenToStageCoordinates(new Vector2(bx0, by0));
            assertNull(stage.hit(outside.x, outside.y, true), "The press must be clear of editor panels");
            for (int modifiers : List.of(InputEvent.SHIFT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK)) {
                GpuBoardTestUi.withModifiers(modifiers, () -> input.touchDown(bx0, by0, 0, Input.Buttons.LEFT));
                input.touchUp(bx0, by0, 0, Input.Buttons.LEFT); settle(view, source);
                assertEquals(List.of("loose"), objects(source), "Modified background clicks keep the selection");
            }
            clickAt(new Vector2(bx0, by0)); settle(view, source);
            assertTrue(source.editorState().selection().isEmpty(), "Clicking the background clears an object selection");
            assertNull(source.editorState().selected(), "Deselecting also clears the inspected hex");
            assertFalse(GpuBoardTestUi.shown(stage.getRoot().findActor("editor-inspector")));
            click(view, new Coords(2, 2), Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of(""), objects(source));
            clickAt(new Vector2(bx0, by0)); settle(view, source);
            assertTrue(source.editorState().selection().isEmpty(), "Clicking the background clears a hex selection");
            assertNull(source.editorState().selected());
            clickAt(loose); settle(view, source);
            assertEquals(List.of("loose"), objects(source));

            GpuBoardTestUi.withModifiers(0, () -> input.touchDown(bx0, by0, 0, Input.Buttons.LEFT));
            input.touchDragged((bx0 + x1) / 2, (by0 + y1) / 2, 0);
            input.touchDragged(x1, y1, 0); settle(view, source);
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-box-from-background.png"));
            input.touchUp(x1, y1, 0, Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of("left", "right"), objects(source), "A box from beyond the board selects the group it covers");
        });
    }

    /**
     * The user's Layers report (2026-10-10): selecting a group hid its members, and a member picked on the board hid its
     * siblings. Now a selected group's row is highlighted with all thirteen members listed under it; a member picked
     * on the board is highlighted among all its siblings and scrolled into view; a group collapsed with its chevron stays
     * collapsed while the hex is worked on, until a member is selected again; another hex starts expanded.
     */
    @Test
    void layersKeepEveryMemberOfASelectedGroupListed() throws Exception {
        Coords lot = new Coords(4, 4), other = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            List<BoardDecoration> cars = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                cars.add(new BoardDecoration("car-" + (i + 1), "prop", "scenery/vehicles/car", null, -.32 + i % 5 * .16,
                      -.26 + i / 5 * .26, 0, false, .5, BoardDecoration.Placement.ground(), 0).withGroup("lot"));
            }
            board.getHex(lot).setDecorations(cars);
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        // The Layers region at its natural height, whatever divider the developer's settings keep.
        GUIPreferences preferences = GUIPreferences.getInstance();
        float split = preferences.getBoardEditorLayersSplit();
        preferences.setBoardEditorLayersSplit(-1);
        try {
            run(setup, 1280, 800, (view, source) -> {
                GpuTerrain terrain = terrain(view);
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (terrain.editorObjects(lot).size() < 13 || terrain.busy()) {
                    assertTrue(System.nanoTime() < deadline, "Object models must load");
                    view.render();
                }
                // A hex about 260 pixels wide in the middle of the board area, so that each car is easy to pick.
                float hex = BoardGeometry.width() * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight() / 260;
                view.boardCamera.zoom(hex / view.boardCamera.camera.zoom);
                view.boardCamera.center(BoardGeometry.center(lot, 0));
                var stage = GpuBoardTestUi.stage();
                UiTree layers = stage.getRoot().findActor("editor-contents");
                List<String> members = java.util.stream.IntStream.rangeClosed(1, 13).mapToObj(i -> "editor-content-car-" + i).toList();
                source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT, "4,4", ""),
                      source.takeFrame().boardGeneration());
                settle(view, source);

                // The group from its Layers row: its row highlighted, every member listed under it.
                GpuBoardTestUi.click("editor-group-lot"); settle(view, source);
                assertEquals("lot", source.editorState().group());
                assertTrue(layers.shown().containsAll(members), "A selected group keeps its members listed: " + layers.shown());
                assertTrue(pressed("editor-group-lot") && members.stream().noneMatch(GpuEditorToolsAndGroupsSmokeTest::pressed));
                opaqueCapture(new File(OUTPUT, "b9-layers-group.png"));

                // A member picked on the board (with its group selected, one click drills in): every sibling stays
                // listed, the member's row is the highlighted one, scrolled into view.
                clickAt(top(view, terrain, lot, "car-13")); settle(view, source);
                assertEquals(List.of("car-13"), objects(source));
                assertTrue(layers.shown().containsAll(members), "Every sibling stays listed: " + layers.shown());
                assertTrue(pressed("editor-content-car-13") && !pressed("editor-group-lot"));
                var scroll = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) stage.getRoot().findActor("editor-layers-scroll");
                assertTrue(scroll.getMaxY() > 0, "The thirteen members do not fit the Layers region");
                assertInView(stage, "editor-content-car-13");
                opaqueCapture(new File(OUTPUT, "b9-layers-member.png"));

                // The chevron collapses the group; the selection stays, and so does the collapse on the same hex.
                GpuBoardTestUi.click("editor-group-lot-chevron"); settle(view, source);
                assertFalse(layers.expanded("editor-group-lot"));
                assertTrue(members.stream().noneMatch(layers.shown()::contains));
                assertEquals(List.of("car-13"), objects(source), "Collapsing selects nothing");
                GpuBoardTestUi.click("editor-component-ground"); settle(view, source);
                assertEquals("ground", source.editorState().component());
                assertFalse(layers.expanded("editor-group-lot"), "A new selection on the same hex keeps the group collapsed");
                Vector2 car = top(view, terrain, lot, "car-7");
                clickAt(car); settle(view, source);
                assertEquals("lot", source.editorState().group(), "A click on a member selects its group");
                assertTrue(pressed("editor-group-lot"));
                assertFalse(layers.expanded("editor-group-lot"), "Selecting the group itself does not expand it");
                clickAt(car); settle(view, source);
                assertEquals(List.of("car-7"), objects(source), "The second click drills in");
                assertTrue(layers.expanded("editor-group-lot"), "Selecting a member expands its group");
                assertTrue(pressed("editor-content-car-7"));
                assertInView(stage, "editor-content-car-7");

                // Another hex starts expanded.
                GpuBoardTestUi.click("editor-group-lot-chevron"); settle(view, source);
                assertFalse(layers.expanded("editor-group-lot"));
                source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT,
                      other.getX() + "," + other.getY(), ""), source.takeFrame().boardGeneration());
                settle(view, source);
                source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT, "4,4", ""),
                      source.takeFrame().boardGeneration());
                settle(view, source);
                assertTrue(layers.expanded("editor-group-lot"), "Another hex resets the expansion");

                // After a click on a row, Layers take the arrow keys: Down to the first member, Enter selects it.
                GpuBoardTestUi.click("editor-group-lot"); settle(view, source);
                press(Input.Keys.DOWN); press(Input.Keys.ENTER); settle(view, source);
                assertEquals(List.of("car-1"), objects(source), "Down and Enter in Layers select the first member");
            });
        } finally { preferences.setBoardEditorLayersSplit(split); }
    }

    private static boolean pressed(String name) {
        return ((UiButton) GpuBoardTestUi.stage().getRoot().findActor(name)).isChecked();
    }

    /** The row's centre hits the row: it lies inside the Layers region's view, not scrolled away. */
    private static void assertInView(com.badlogic.gdx.scenes.scene2d.Stage stage, String name) {
        Actor row = stage.getRoot().findActor(name);
        Vector2 centre = row.localToStageCoordinates(new Vector2(row.getWidth() / 2, row.getHeight() / 2));
        Actor hit = stage.hit(centre.x, centre.y, true);
        assertTrue(hit != null && (hit == row || hit.isDescendantOf(row)), name + " is in view; the centre hits " + hit);
    }

    /** The window as an opaque PNG: the back buffer's alpha dropped. */
    private static void opaqueCapture(File file) {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            image.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) { image.drawPixel(x, y, image.getPixel(x, y) | 0xFF); }
            }
            PixmapIO.writePNG(new FileHandle(file), image, -1, true);
        } finally { image.dispose(); }
    }

    /** A box drawn by a plain drag selects objects by their installed anchors: on a roof and on a bridge deck. */
    @Test
    void boxSelectsObjectsOnARoofAndOnABridgeDeck() throws Exception {
        Coords roof = new Coords(3, 4), deck = new Coords(6, 4);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(10, 9);
            board.setHex(roof, new Hex(0, "building:2;bldg_elev:4;bldg_cf:40", ""));
            board.setHex(deck, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4", ""));
            board.getHex(roof).setDecorations(List.of(on("vent", -.2, "building", "roof"), on("tank", .2, "building", "roof")));
            board.getHex(deck).setDecorations(List.of(on("cart", -.2, "bridge", "deck"), on("crate", .2, "bridge", "deck")));
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1600, 1000, (view, source) -> {
            GpuTerrain terrain = terrain(view);
            view.boardCamera.setIsometric(true); view.boardCamera.fit(source.takeFrame().scene());
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (terrain.editorObjects(roof).size() < 2 || terrain.editorObjects(deck).size() < 2 || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "Object models must load");
                view.render();
            }
            settle(view, source);
            for (var target : List.of(new Object[] { roof, List.of("tank", "vent"), "editor-box-roof.png" },
                  new Object[] { deck, List.of("cart", "crate"), "editor-box-deck.png" })) {
                Coords owner = (Coords) target[0];
                var installed = terrain.editorObjects(owner);
                assertTrue(installed.stream().allMatch(object -> object.anchorLevel() >= 3.5f), "The objects stand on the raised support");
                float left = Float.MAX_VALUE, right = -Float.MAX_VALUE, top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
                for (var object : installed) {
                    Vector2 anchor = project(view, object.bounds().getCenter(new Vector3()).x, object.bounds().getCenter(new Vector3()).y,
                          object.anchorLevel() * BoardGeometry.level());
                    left = Math.min(left, anchor.x); right = Math.max(right, anchor.x); top = Math.min(top, anchor.y); bottom = Math.max(bottom, anchor.y);
                }
                int x0 = Math.round(left) - 24, y0 = Math.round(top) - 24, x1 = Math.round(right) + 24, y1 = Math.round(bottom) + 24;
                // The same anchors at ground level fall outside this box, so only the installed height selects them.
                for (var object : installed) {
                    Vector2 ground = project(view, object.bounds().getCenter(new Vector3()).x, object.bounds().getCenter(new Vector3()).y, 0);
                    assertFalse(ground.y >= y0 && ground.y <= y1, "The support must lift the anchors out of a ground-level box");
                }
                var input = Gdx.input.getInputProcessor();
                GpuBoardTestUi.withModifiers(0, () -> input.touchDown(x0, y0, 0, Input.Buttons.LEFT));
                input.touchDragged((x0 + x1) / 2, (y0 + y1) / 2, 0);
                input.touchDragged(x1, y1, 0); settle(view, source);
                GpuBoardTestUi.capture(new File(OUTPUT, (String) target[2]));
                input.touchUp(x1, y1, 0, Input.Buttons.LEFT); settle(view, source);
                assertEquals(target[1], objects(source), "The box selects the objects on the " + owner);
            }
        });
    }

    /**
     * At an overview zoom (a 10 px hex) the cars are hidden (Object LoD): a box over them takes the hexes, as a click would;
     * selecting a car through Layers draws it again and makes it pickable.
     */
    @Test
    void aBoxAtAnOverviewZoomTakesHexesNotHiddenObjects() throws Exception {
        Coords lot = new Coords(4, 4);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            board.getHex(lot).setDecorations(List.of(car("left", -.25), car("right", .25)));
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1600, 1000, (view, source) -> {
            GpuTerrain terrain = terrain(view);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (terrain.editorObjects(lot).size() < 2 || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "Object models must load");
                view.render();
            }
            float hex = BoardGeometry.width() * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight() / 10;
            view.boardCamera.zoom(hex / view.boardCamera.camera.zoom);
            view.boardCamera.center(BoardGeometry.center(lot, 0)); settle(view, source);
            assertTrue(terrain.editorObjects(lot).stream().noneMatch(GpuTerrain.EditorObject::shown), "A 10 px hex hides the cars");
            Vector2 left = anchor(view, lot, -.25), right = anchor(view, lot, .25);
            // Around the cars and the hex centre between them, clear of the neighbours' centres (7.5 px away and more).
            int x0 = Math.round(left.x) - 3, y0 = Math.round(Math.min(left.y, right.y)) - 3;
            int x1 = Math.round(right.x) + 3, y1 = Math.round(Math.max(left.y, right.y)) + 3;
            var input = Gdx.input.getInputProcessor();
            GpuBoardTestUi.withModifiers(0, () -> input.touchDown(x0, y0, 0, Input.Buttons.LEFT));
            input.touchDragged((x0 + x1) / 2, (y0 + y1) / 2, 0);
            input.touchDragged(x1, y1, 0); settle(view, source);
            input.touchUp(x1, y1, 0, Input.Buttons.LEFT); settle(view, source);
            assertEquals(List.of(new BoardEditorSession.Selection(lot, "")), source.editorState().selection(),
                  "The box takes the hex, not the cars the zoom hides");
            GpuBoardTestUi.click("editor-content-left"); settle(view, source);
            assertEquals(List.of("left"), objects(source));
            var selected = terrain.editorObjects(lot).stream().filter(o -> o.id().equals("left")).findFirst().orElseThrow();
            assertTrue(selected.shown(), "The selected car draws at every zoom");
            assertFalse(terrain.editorObjects(lot).stream().filter(o -> o.id().equals("right")).findFirst().orElseThrow().shown());
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-overview-selected-car.png"));
        });
    }

    /** The red triangle appears only with issues; its list frames and selects an issue's hex. */
    @Test
    void issuesTriangleListsIssuesAndFramesTheChosenHex() throws Exception {
        Coords broken = new Coords(17, 13);
        AtomicReference<BoardEditorSession> session = new AtomicReference<>();
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession(); session.set(editor);
            editor.game().setBoard(Board.createEmptyBoard(20, 16));
            return new GpuMapSource(editor.game(), null, editor);
        });
        run(setup, 1600, 1000, (view, source) -> {
            var stage = GpuBoardTestUi.stage();
            Actor triangle = stage.getRoot().findActor("editor-issues");
            assertFalse(GpuBoardTestUi.shown(triangle), "No issues, no triangle");
            view.boardCamera.zoom(.35f); view.boardCamera.center(BoardGeometry.center(new Coords(3, 3), 0)); settle(view, source);
            float middle = GpuBoardEditor.LIBRARY_WIDTH + (Gdx.graphics.getWidth() - GpuBoardEditor.LIBRARY_WIDTH - GpuBoardEditor.INSPECTOR_WIDTH) / 2f;
            assertTrue(Math.abs(project(view, BoardGeometry.centerX(broken), BoardGeometry.centerY(broken), 0).x - middle) > 200,
                  "The issue's hex starts away from the centre");
            // An incomplete bridge, through the ordinary edit commands.
            SwingUtilities.invokeAndWait(() -> {
                session.get().pointer(broken, 0, 0, false); session.get().finishStroke();
                session.get().command(new BoardEditorSession.Command(BoardEditorSession.Action.TERRAIN, "bridge", "1"), null);
            });
            settle(view, source);
            assertTrue(GpuBoardTestUi.shown(triangle), "An issue shows the triangle");
            Actor toolbar = stage.getRoot().findActor("editor-toolbar"), utilities = stage.getRoot().findActor("map-utilities");
            var corner = triangle.localToStageCoordinates(new Vector2());
            assertTrue(corner.x > toolbar.getRight() && corner.x + triangle.getWidth() < utilities.localToStageCoordinates(new Vector2()).x,
                  "The triangle sits at the top right, left of the camera utilities");
            SwingUtilities.invokeAndWait(() -> session.get().command(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "PAINT"), null));
            settle(view, source);
            GpuBoardTestUi.click("editor-issues"); settle(view, source);
            Actor list = stage.getRoot().findActor("editor-issue-list");
            assertTrue(GpuBoardTestUi.shown(list));
            assertTrue(GpuBoardTestUi.texts(list).stream().anyMatch(text -> text.startsWith(broken.getBoardNum() + " · Incomplete Bridge")),
                  "Each issue names its hex: " + GpuBoardTestUi.texts(list));
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-issues-list.png"));

            // The test drives frames without a frame clock, so the framing transition completes at once.
            view.boardCamera.animateOnSelectionChange = false;
            Actor brush = stage.getRoot().findActor("editor-brush"), side = stage.getRoot().findActor("editor-side-view");
            assertTrue(brush.isVisible(), "Paint shows its Brush panel");
            GpuBoardTestUi.click("editor-issue-" + broken.getBoardNum()); settle(view, source);
            // Choosing an issue switches to Select, which has no Brush panel; the framing uses the panels left after it.
            assertFalse(brush.isVisible(), "Select hides the Brush panel");
            float cornerTop = side.getTop();
            assertFalse(GpuBoardTestUi.shown(list), "Choosing an issue closes the list");
            assertEquals(List.of(new BoardEditorSession.Selection(broken, "")), source.editorState().selection(), "The issue's hex is selected");
            assertEquals(Tool.SELECT, source.editorState().tool());
            Vector2 framed = project(view, BoardGeometry.centerX(broken), BoardGeometry.centerY(broken), 0);
            assertEquals(middle, framed.x, 80, "The camera frames the issue's hex in the free board area");
            // Vertically too: between the toolbar and the corner panels as they are after the switch to Select.
            float panels = stage.stageToScreenCoordinates(new Vector2(0, cornerTop)).y;
            float below = stage.stageToScreenCoordinates(new Vector2(0, stage.getRoot().findActor("editor-library").getTop())).y;
            assertEquals((panels + below) / 2, framed.y, 40, "The framed hex stays above the corner panels: " + framed.y);
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-issues-framed.png"));

            SwingUtilities.invokeAndWait(() -> session.get().command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), null));
            settle(view, source);
            assertFalse(GpuBoardTestUi.shown(triangle), "Fixing the last issue hides the triangle");
        });
    }

    private static BoardDecoration on(String id, double x, String support, String surface) {
        return car(id, x).transform(x, 0, 0, false, 1, BoardDecoration.Placement.surface(support, surface, 0));
    }

    private static Vector2 project(GpuBattleView view, float x, float y, float z) {
        Vector3 point = view.boardCamera.camera.project(new Vector3(x, y, z), 0, 0,
              view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }

    private interface Steps { void run(GpuBattleView view, GpuMapSource source) throws Exception; }

    private static void run(FutureTask<GpuMapSource> setup, int width, int height, Steps steps) throws Exception {
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(width, height);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(false);
                        view.boardCamera.fit(source.takeFrame().scene());
                        settle(view, source);
                        steps.run(view, source);
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor tools and groups", failure.get()); }
    }

    private static BoardDecoration car(String id, double x) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", null, x, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
    }

    private static List<String> objects(GpuMapSource source) {
        return source.editorState().selection().stream().map(BoardEditorSession.Selection::object).sorted().toList();
    }

    private static GpuTerrain terrain(GpuBattleView view) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(view);
    }

    /** An object's ground anchor in input coordinates, as the view's box selection projects it. */
    private static Vector2 anchor(GpuBattleView view, Coords owner, double x) {
        Vector3 point = view.boardCamera.camera.project(new Vector3(BoardGeometry.centerX(owner) + (float) x * BoardGeometry.width(),
              BoardGeometry.centerY(owner), 0), 0, 0, view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }

    /** The top of an object's installed geometry, where a real click picks it. */
    private static Vector2 top(GpuBattleView view, GpuTerrain terrain, Coords owner, String id) {
        var object = terrain.editorObjects(owner).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
        var centre = object.bounds().getCenter(new Vector3());
        Vector3 point = view.boardCamera.camera.project(new Vector3(centre.x, centre.y, object.bounds().max.z - .1f));
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }

    private static void clickAt(Vector2 point) {
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static void press(int key) {
        GpuBoardTestUi.withModifiers(0, () -> {
            Gdx.input.getInputProcessor().keyDown(key);
            Gdx.input.getInputProcessor().keyUp(key);
        });
    }

    private static void click(GpuBattleView view, Coords coords, int button) {
        Vector2 point = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(coords, 0));
        assertNotNull(point);
        int x = Math.round(point.x), y = Gdx.graphics.getHeight() - Math.round(point.y);
        Vector2 stage = GpuBoardTestUi.stage().screenToStageCoordinates(new Vector2(x, y));
        assertNull(GpuBoardTestUi.stage().hit(stage.x, stage.y, true), "The click must be clear of editor panels");
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(0, () -> input.touchDown(x, y, 0, button));
        input.touchUp(x, y, 0, button);
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
