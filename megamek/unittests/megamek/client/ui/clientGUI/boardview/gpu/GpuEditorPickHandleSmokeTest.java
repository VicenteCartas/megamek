/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.Group;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiButton;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The user's three editor requests (2026-10-11), through the real input processor: a click on a decal selects it and
 * outlines its footprint, and an object standing on the selected decal still takes a click on it; a click through water selects the object beneath its surface, a click on open water its
 * hex; and the selection's rotation handle turns an object about the vertical axis, one undo step per drag, in the
 * top and an angled view.
 */
@Tag("on-demand")
class GpuEditorPickHandleSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    private static final Coords DECAL = new Coords(2, 4), WATER = new Coords(6, 4), CAR = new Coords(4, 5), LOT = new Coords(6, 6),
          PAD = new Coords(2, 6), BRIDGE = new Coords(4, 2), BURIED = new Coords(6, 2), BUILDING = new Coords(1, 1),
          WOODS = new Coords(3, 0);

    @Test
    void decalsAndObjectsUnderWaterSelectAndTheHandleTurnsTheSelection() throws Exception {
        AtomicReference<BoardEditorSession> session = new AtomicReference<>();
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            board.setHex(BRIDGE, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", ""));
            board.setHex(BUILDING, new Hex(0, "building:2;bldg_elev:2;bldg_cf:40", ""));
            board.setHex(WOODS, new Hex(0, "woods:2;foliage_elev:2", ""));
            board.getHex(DECAL).setDecorations(List.of(new BoardDecoration("emblem", "decal", "decal/emblems/star-league",
                  null, 0, 0, 0, false, .5, BoardDecoration.Placement.ground(), 0)));
            board.getHex(PAD).setDecorations(List.of(new BoardDecoration("pad", "decal", "decal/emblems/star-league", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0), new BoardDecoration("post", "prop",
                  "scenery/parks/pillar", null, 0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0)));
            board.setHex(WATER, new Hex(0, "water:3", ""));
            board.getHex(WATER).setDecorations(List.of(new BoardDecoration("pillar", "prop", "scenery/parks/pillar", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0)));
            board.getHex(CAR).setDecorations(List.of(car("car", 0, 20)));
            board.getHex(BURIED).setDecorations(List.of(new BoardDecoration("buried", "prop", "scenery/vehicles/car", null,
                  0, 0, 35, false, 1, BoardDecoration.Placement.absolute(-2), 0, false, 20, -30)));
            board.getHex(LOT).setDecorations(List.of(car("left", -.25, 0).withGroup("lot"), car("right", .25, 0).withGroup("lot")));
            editor.game().setBoard(board);
            session.set(editor);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1600, 1000);
        configuration.setInitialVisible(false);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(false);
                        view.boardCamera.fit(source.takeFrame().scene());
                        settle(view, source);
                        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
                        groundSelection(view, source, session.get());
                        struckLayer(view, source);
                        decal(view, source);
                        underWater(view, source);
                        handle(view, source, session.get());
                        buriedControls(view, source, session.get());
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor picking and rotation handle", failure.get()); }
    }

    /** A board click selects Ground even after another component; no selected layer leaves Edit empty. */
    private static void groundSelection(GpuBattleView view, GpuMapSource source, BoardEditorSession session) throws Exception {
        Group root = GpuBoardTestUi.stage().getRoot();
        Group details = root.findActor("editor-details");
        assertNull(source.editorState().selected());
        assertFalse(details.hasChildren(), "Before a hex is selected the inspector is empty");
        assertInspectorHidden();

        Coords plain = new Coords(4, 4);
        click(view, plain); settle(view, source);
        SwingUtilities.invokeAndWait(() -> session.command(new Command(Action.COMPONENT, "bridge"), null));
        settle(view, source);
        assertEquals("bridge", source.editorState().component());
        assertNull(root.findActor("editor-component-bridge"), "The plain hex has no Bridge layer");
        assertFalse(((UiButton) root.findActor("editor-component-ground")).isChecked());
        assertFalse(details.hasChildren(), "An absent component has no heading, placeholder or Add Bridge button");
        click(view, plain); settle(view, source);
        assertGroundSelected(source, plain);

        click(view, BRIDGE); settle(view, source);
        GpuBoardTestUi.click("editor-component-bridge"); settle(view, source);
        assertEquals("bridge", source.editorState().component());
        assertTrue(((UiButton) root.findActor("editor-component-bridge")).isChecked());
        assertTrue(GpuBoardTestUi.texts(details).contains("BRIDGE"), "A selected, present Bridge still has its controls");
        click(view, plain); settle(view, source);
        assertGroundSelected(source, plain);
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-ground-selected.png"));

        assertTrue(GpuBoardTestUi.stage().setKeyboardFocus(root.findActor("editor-contents")));
        SwingUtilities.invokeAndWait(() -> session.command(new Command(Action.CLEAR_SELECTION), null));
        settle(view, source);
        assertTrue(source.editorState().selection().isEmpty());
        assertFalse(details.hasChildren(), "Clearing the selection empties the inspector");
        assertInspectorHidden();
        assertNull(GpuBoardTestUi.stage().getKeyboardFocus(), "Hiding the inspector releases its keyboard focus");
        assertNull(root.findActor("editor-component-ground"), "Without a hex, Layers lists no row");
        click(view, plain); settle(view, source);
        assertGroundSelected(source, plain);
    }

    /** A click selects the layer of what it struck: the building, the bridge, a tree; the ground beside them is Ground. */
    private static void struckLayer(GpuBattleView view, GpuMapSource source) throws Exception {
        GpuTerrain terrain = terrain(view);
        BoardScene scene = (BoardScene) GpuTestSupport.field(view, "scene");
        click(view, BUILDING); settle(view, source);
        assertLayer(source, BUILDING, "building");
        assertTrue(GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("editor-details")).contains("BUILDING"),
              "Edit shows the building");
        clickAt(screen(view, BoardGeometry.center(BRIDGE, 2))); settle(view, source);
        assertLayer(source, BRIDGE, "bridge");
        // A tree of the woods hex, found on a grid over it; the hex's ground between its trees stays Ground.
        Vector2 tree = null, soil = null;
        for (float dx = -.4f; dx <= .4f; dx += .05f) {
            for (float dy = -.4f; dy <= .4f; dy += .05f) {
                Vector2 point = screen(view, new Vector3(BoardGeometry.centerX(WOODS) + dx * BoardGeometry.width(),
                      BoardGeometry.centerY(WOODS) + dy * BoardGeometry.height(), 0));
                var pick = terrain.editorPick(scene, ray(view, point));
                if (pick == null || !pick.hit().coords().equals(WOODS)) { continue; }
                if (pick.component().equals("vegetation")) { tree = point; } else { soil = point; }
            }
        }
        assertNotNull(tree, "The woods hex draws a tree to click on");
        assertNotNull(soil, "and shows ground between its trees");
        clickAt(onBoard(tree)); settle(view, source);
        assertLayer(source, WOODS, "vegetation");
        clickAt(onBoard(soil)); settle(view, source);
        assertLayer(source, WOODS, "ground");
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-struck-layer.png"));
    }

    /** {@code point}, which no panel covers: its click reaches the board. */
    private static Vector2 onBoard(Vector2 point) {
        var stage = GpuBoardTestUi.stage();
        var intercepted = stage.hit(point.x, Gdx.graphics.getHeight() - point.y, true);
        assertTrue(intercepted == null || intercepted == stage.getRoot(), () -> "The click must reach the board: " + intercepted);
        return point;
    }

    private static void assertLayer(GpuMapSource source, Coords coords, String component) {
        assertEquals(List.of(new BoardEditorSession.Selection(coords, "")), source.editorState().selection());
        assertEquals(component, source.editorState().component());
        UiButton row = GpuBoardTestUi.stage().getRoot().findActor("editor-component-" + component);
        assertTrue(GpuBoardTestUi.shown(row) && row.isChecked(), component + " is highlighted in Layers");
    }

    private static void assertInspectorHidden() {
        var stage = GpuBoardTestUi.stage();
        Group inspector = stage.getRoot().findActor("editor-inspector");
        assertFalse(GpuBoardTestUi.shown(inspector), "Without a selection the whole inspector is hidden");
        Vector2 point = inspector.localToStageCoordinates(new Vector2(inspector.getWidth() / 2, inspector.getHeight() / 2));
        var hit = stage.hit(point.x, point.y, true);
        assertTrue(hit == null || !hit.isDescendantOf(inspector), "The hidden inspector does not intercept board clicks");
    }

    private static void assertGroundSelected(GpuMapSource source, Coords coords) {
        assertEquals(List.of(new BoardEditorSession.Selection(coords, "")), source.editorState().selection());
        assertEquals("ground", source.editorState().component());
        Group root = GpuBoardTestUi.stage().getRoot();
        assertTrue(GpuBoardTestUi.shown(root.findActor("editor-inspector")), "A board hex selection shows the inspector");
        UiButton ground = root.findActor("editor-component-ground");
        assertTrue(GpuBoardTestUi.shown(ground) && ground.isChecked(), "Ground is highlighted in Layers");
        assertTrue(GpuBoardTestUi.texts(root.findActor("editor-details")).contains("GROUND"), "Ground has its inspector controls");
    }

    /** (a) The decal is an installed object with its paint's flat box; a click on it selects it like an object. */
    private static void decal(GpuBattleView view, GpuMapSource source) throws Exception {
        GpuTerrain terrain = terrain(view);
        var decal = installed(view, terrain, DECAL, "emblem");
        assertTrue(decal.bounds().getDepth() < .1f * BoardGeometry.level(), "A decal's box is its flat footprint: " + decal.bounds());
        assertTrue(decal.bounds().getWidth() > .3f * BoardGeometry.width(), "It spans the paint: " + decal.bounds());
        clickAt(screen(view, decal.anchor())); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(DECAL, "emblem")), source.editorState().selection(),
              "A click on the decal selects it");
        assertEquals("emblem", source.editorState().object(), "Edit shows the decal");
        UiButton row = GpuBoardTestUi.stage().getRoot().findActor("editor-content-emblem");
        assertTrue(GpuBoardTestUi.shown(row) && row.isChecked(), "Its Layers row is the highlighted one");
        assertNotNull(handle(view), "A selected decal has a rotation handle too");
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-decal-selected.png"));

        // A post stands on the middle of a larger decal: its paint beside the post selects the decal, and a click on the
        // post, whose ray also crosses the selected decal's box, selects the post.
        var pad = installed(view, terrain, PAD, "pad");
        var post = installed(view, terrain, PAD, "post");
        clickAt(screen(view, new Vector3(pad.bounds().max.x - .15f * pad.bounds().getWidth(), pad.anchor().y, pad.anchor().z)));
        settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(PAD, "pad")), source.editorState().selection(),
              "A click on the decal's paint beside the post selects the decal");
        Vector3 top = post.bounds().getCenter(new Vector3());
        top.z = post.bounds().max.z - .05f * BoardGeometry.level();
        Vector2 point = screen(view, top);
        assertTrue(Intersector.intersectRayBoundsFast(ray(view, point), pad.bounds()), "The ray to the post crosses the decal's box");
        clickAt(point); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(PAD, "post")), source.editorState().selection(),
              "A click on the post standing on the selected decal selects the post");
    }

    /** (b) Water is see-through: the pillar on the depth-3 bed takes a click through the surface; open water takes the hex. */
    private static void underWater(GpuBattleView view, GpuMapSource source) throws Exception {
        GpuTerrain terrain = terrain(view);
        BoardScene scene = (BoardScene) GpuTestSupport.field(view, "scene");
        var pillar = installed(view, terrain, WATER, "pillar");
        float surface = BoardGeometry.waterZ(scene.tile(WATER));
        assertTrue(pillar.bounds().max.z < surface, "The pillar stands under water: " + pillar.bounds() + " below " + surface);
        Vector3 centre = pillar.bounds().getCenter(new Vector3());
        Vector2 point = screen(view, new Vector3(centre.x, centre.y, pillar.bounds().max.z - .05f * BoardGeometry.level()));
        Ray ray = ray(view, point);
        var ground = terrain.selectionHit(scene, ray);
        assertNotNull(ground);
        assertTrue(ray.getEndPoint(new Vector3(), (float) Math.sqrt(ground.distance())).z > pillar.bounds().max.z,
              "The ray meets the water's surface before the pillar");
        System.out.printf("Pillar %s under the surface at %.2f; hex %.2f wide%n", pillar.bounds(), surface, BoardGeometry.width());

        // West of the pillar: its rotation handle, once it is selected, lies east.
        Vector2 open = screen(view, new Vector3(BoardGeometry.centerX(WATER) - .3f * BoardGeometry.width(), BoardGeometry.centerY(WATER),
              surface));
        assertNull(terrain.decorationHit(scene, ray(view, open)), "Nothing stands under that point");
        clickAt(open); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(WATER, "")), source.editorState().selection(),
              "A click on open water selects its hex");
        assertEquals("water", source.editorState().component(), "on its Water layer");
        clickAt(point); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(WATER, "pillar")), source.editorState().selection(),
              "On its selected hex, a click through the water selects the pillar beneath it");
        // Keep the next board click above the section panel, which now occupies the lower-right board area.
        Coords elsewhere = new Coords(3, 3);
        Vector2 next = screen(view, BoardGeometry.center(elsewhere, 0));
        var stage = GpuBoardTestUi.stage();
        var intercepted = stage.hit(next.x, Gdx.graphics.getHeight() - next.y, true);
        assertTrue(intercepted == null || intercepted == stage.getRoot(), () -> "The test's empty hex click must reach the board: " + intercepted);
        clickAt(next); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(elsewhere, "")), source.editorState().selection());
        clickAt(point); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(WATER, "pillar")), source.editorState().selection(),
              "From another selection too");
        view.boardCamera.setIsometric(true); settle(view, source);
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-underwater-pillar-selected.png"));
        view.boardCamera.setIsometric(false); view.boardCamera.fit(source.takeFrame().scene()); settle(view, source);
        open = screen(view, new Vector3(BoardGeometry.centerX(WATER) - .3f * BoardGeometry.width(), BoardGeometry.centerY(WATER), surface));
        clickAt(open); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(WATER, "")), source.editorState().selection(),
              "With the pillar selected, a click on open water selects the hex");
    }

    /** (c) A drag on the handle turns the car by the pointer's sweep about its anchor: one undo step per drag. */
    private static void handle(GpuBattleView view, GpuMapSource source, BoardEditorSession session) throws Exception {
        GpuTerrain terrain = terrain(view);
        var car = installed(view, terrain, CAR, "car");
        Vector3 top = car.bounds().getCenter(new Vector3());
        top.z = car.bounds().max.z - .05f * BoardGeometry.level();
        clickAt(screen(view, top)); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(CAR, "car")), source.editorState().selection());
        assertFalse(source.editorState().canUndo(), "Selecting is no edit");
        double start = rotation(source);
        view.boardCamera.center(car.anchor()); view.boardCamera.zoom(.35f); settle(view, source);
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-rotation-handle.png"));
        drag(view, source, 90);
        assertEquals(Math.IEEEremainder(start + 90, 360), rotation(source), 1, "The car turned with the pointer's quarter sweep");
        assertHandleAttached(view, terrain, CAR, "car");
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-rotation-handle-turned.png"));
        undo(view, source, session);
        assertEquals(start, rotation(source), .001, "One undo restores the rotation");
        assertFalse(source.editorState().canUndo(), "The whole drag was one undo step");

        // The handle stays attached to the object and remains grabbable in the angled view. Undo emptied the selection.
        view.boardCamera.setIsometric(true); settle(view, source);
        car = installed(view, terrain, CAR, "car");
        top = car.bounds().getCenter(new Vector3());
        top.z = car.bounds().max.z - .05f * BoardGeometry.level();
        clickAt(screen(view, top)); settle(view, source);
        assertEquals(List.of(new BoardEditorSession.Selection(CAR, "car")), source.editorState().selection());
        drag(view, source, -45);
        assertEquals(Math.IEEEremainder(start - 45, 360), rotation(source), 1, "Clockwise in the angled view");
        assertHandleAttached(view, terrain, CAR, "car");
        GpuBoardTestUi.capture(new File(OUTPUT, "editor-rotation-handle-angled.png"));
        undo(view, source, session);
        assertEquals(start, rotation(source), .001);
        assertFalse(source.editorState().canUndo());

        // A whole group turns about its members' centre: west becomes south after a quarter turn.
        var left = installed(view, terrain, LOT, "left");
        view.boardCamera.center(BoardGeometry.center(LOT, 0)); settle(view, source);
        top = left.bounds().getCenter(new Vector3());
        top.z = left.bounds().max.z - .05f * BoardGeometry.level();
        clickAt(screen(view, top)); settle(view, source);
        assertEquals("lot", source.editorState().group(), "A click on a member selects its group");
        drag(view, source, 90);
        var turned = source.editorState().objects().stream().filter(object -> object.id().equals("left")).findFirst().orElseThrow();
        assertEquals(90, turned.rotation(), 1);
        assertEquals(0, turned.x(), .02, "The west member swung round the group's centre");
        assertTrue(turned.y() < -.2, "to its south: " + turned.y());
        undo(view, source, session);
        assertEquals(-.25, source.editorState().objects().stream().filter(object -> object.id().equals("left")).findFirst()
              .orElseThrow().x(), .001, "One undo restores the group");
        assertFalse(source.editorState().canUndo());
    }

    /** Even entirely buried geometry keeps a readable selection frame and a working, base-attached handle. */
    private static void buriedControls(GpuBattleView view, GpuMapSource source, BoardEditorSession session) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            session.command(new Command(Action.SELECT_AT, BURIED.getX() + "," + BURIED.getY(), ""), null);
            session.command(new Command(Action.SELECT_OBJECT, "buried"), null);
        });
        settle(view, source);
        var object = installed(view, terrain(view), BURIED, "buried");
        assertTrue(object.bounds().max.z < 0, "The entire test object must be below opaque ground");
        view.boardCamera.center(object.bounds().getCenter(new Vector3())); view.boardCamera.zoom(.35f);
        for (boolean perspective : new boolean[] {false, true}) {
            view.boardCamera.setPerspective(perspective); settle(view, source);
            object = installed(view, terrain(view), BURIED, "buried");
            GpuBoardTestUi.capture(new File(OUTPUT, "editor-buried-controls-" + perspective + ".png"));
            Vector3[] corners = object.outline().getVertices();
            for (int i = 0; i < corners.length; i++) {
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    if ((i & bit) == 0) {
                        assertControlPixel(view, new Vector3(corners[i]).lerp(corners[i | bit], .5f),
                              "A buried selection edge stays fully visible");
                    }
                }
            }
            Object grip = handle(view);
            var radiusMethod = grip.getClass().getDeclaredMethod("radius"); radiusMethod.setAccessible(true);
            float radius = (float) radiusMethod.invoke(grip);
            Vector3 across = new Vector3(view.boardCamera.camera.direction).crs(view.boardCamera.camera.up).nor();
            assertControlPixel(view, new Vector3(component(grip, "tip")).mulAdd(across, radius),
                  "The buried rotation circle stays fully visible");
            double before = source.editorState().objects().getFirst().rotation();
            drag(view, source, 30);
            assertEquals(before + 30, source.editorState().objects().getFirst().rotation(), 1,
                  "The visible buried handle remains usable");
            assertHandleAttached(view, terrain(view), BURIED, "buried");
        }
    }

    private static void assertControlPixel(GpuBattleView view, Vector3 world, String message) {
        Vector3 pixel = view.boardCamera.camera.project(new Vector3(world), 0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Pixmap patch = Pixmap.createFromFrameBuffer(Math.round(pixel.x) - 2, Math.round(pixel.y) - 2, 5, 5);
        try {
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    int colour = patch.getPixel(x, y);
                    // Mint at rest, white when hovered: neither should be dimmed by the ground's depth.
                    if (((colour >>> 16) & 255) > 235 && ((colour >>> 8) & 255) > 180) { return; }
                }
            }
            fail(message + " at " + pixel);
        } finally { patch.dispose(); }
    }

    private static BoardDecoration car(String id, double x, double rotation) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", null, x, 0, rotation, false, 1,
              BoardDecoration.Placement.ground(), 0);
    }

    /** Presses the selection's handle (its cursor a hand), sweeps it {@code degrees} about its pivot and releases it. */
    private static void drag(GpuBattleView view, GpuMapSource source, float degrees) throws Exception {
        Object handle = handle(view);
        assertNotNull(handle, "The selection has a rotation handle");
        Vector3 pivot = component(handle, "pivot"), tip = component(handle, "tip");
        Vector2 press = screen(view, tip);
        var input = Gdx.input.getInputProcessor();
        input.mouseMoved(Math.round(press.x), Math.round(press.y));
        assertEquals(SystemCursor.Hand, GpuTestSupport.field(view, "cursor"), "A hand over the handle");
        GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(press.x), Math.round(press.y), 0, Input.Buttons.LEFT));
        float radius = Vector2.dst(pivot.x, pivot.y, tip.x, tip.y);
        double from = Math.atan2(tip.y - pivot.y, tip.x - pivot.x);
        Vector2 point = press;
        for (int step = 1; step <= 6; step++) {
            double angle = from + Math.toRadians(degrees * step / 6);
            point = screen(view, new Vector3(pivot.x + radius * (float) Math.cos(angle), pivot.y + radius * (float) Math.sin(angle), tip.z));
            input.touchDragged(Math.round(point.x), Math.round(point.y), 0);
            view.render();
        }
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        settle(view, source);
        assertTrue(source.editorState().canUndo(), "The drag is an edit");
    }

    private static void undo(GpuBattleView view, GpuMapSource source, BoardEditorSession session) throws Exception {
        SwingUtilities.invokeAndWait(() -> session.command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), null));
        settle(view, source);
    }

    private static double rotation(GpuMapSource source) {
        return source.editorState().objects().stream().filter(object -> object.id().equals("car")).findFirst().orElseThrow().rotation();
    }

    private static void assertHandleAttached(GpuBattleView view, GpuTerrain terrain, Coords coords, String id) throws Exception {
        var box = terrain.editorObjects(coords).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow().outline();
        Vector3 expected = box.getBounds().getCenter(new Vector3()); expected.x = box.getBounds().max.x;
        expected.z = box.getBounds().min.z;
        expected.mul(box.getTransform());
        assertTrue(expected.epsilonEquals(component(handle(view), "base"), .001f), "The displayed handle uses the current prop pose, including after release");
    }

    /** The view's rotation handle for the current selection, or null. */
    private static Object handle(GpuBattleView view) throws Exception {
        var method = GpuBattleView.class.getDeclaredMethod("rotationHandle"); method.setAccessible(true);
        return method.invoke(view);
    }

    private static Vector3 component(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return (Vector3) method.invoke(record);
    }

    private static GpuTerrain.EditorObject installed(GpuBattleView view, GpuTerrain terrain, Coords coords, String id) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            var found = terrain.editorObjects(coords).stream().filter(object -> object.id().equals(id)).findFirst();
            if (found.isPresent() && !terrain.busy()) { return found.get(); }
            assertTrue(System.nanoTime() < deadline, id + " must be installed");
            view.render();
        }
    }

    private static GpuTerrain terrain(GpuBattleView view) throws Exception { return (GpuTerrain) GpuTestSupport.field(view, "terrain"); }

    /** A world point in input coordinates. */
    private static Vector2 screen(GpuBattleView view, Vector3 world) {
        var camera = view.boardCamera.camera;
        Vector3 point = camera.project(new Vector3(world), 0, 0, camera.viewportWidth, camera.viewportHeight);
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }

    private static Ray ray(GpuBattleView view, Vector2 point) {
        var camera = view.boardCamera.camera;
        return camera.getPickRay(Math.round(point.x), Math.round(point.y), 0, 0, camera.viewportWidth, camera.viewportHeight);
    }

    private static void click(GpuBattleView view, Coords coords) {
        clickAt(screen(view, BoardGeometry.center(coords, 0)));
    }

    private static void clickAt(Vector2 point) {
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
