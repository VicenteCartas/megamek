/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Rule C of batch 6: placed decorative objects under a quarter hex hide while a hex is narrower than 12 px on screen
 * (TreeLod's ±10 % hysteresis), in the colour, shadow and transparent passes and in picking; the editor's pins keep the
 * selection drawn and pickable. Placed objects draw instanced in every view (b13), so no prop page holds them. The cars
 * are the fixture's only small objects, so every count below is a whole number of cars (GLProfiler counts one instanced
 * draw's indices once). Everything is paved, so nothing else on the board changes with the zoom.
 */
@Tag("on-demand")
class GpuSmallObjectSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "small-objects");
    private static final Coords CAR = new Coords(2, 2), MIRRORED = new Coords(3, 2), GARDEN = new Coords(5, 3),
          EDITED = new Coords(6, 6), ROOF = new Coords(10, 3), LONE = new Coords(3, 10);

    /**
     * Two cars (one mirrored, drawn in a batch of its own), a formal garden, a car on a building's roof, and a car alone
     * in its chunk (8 hexes a side), which so packs no prop.
     */
    private static Board board(boolean edited) {
        Board board = Board.createEmptyBoard(12, 12);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var coords = new Coords(x, y);
                board.setHex(coords, new Hex(edited && coords.equals(EDITED) ? 1 : 0,
                      coords.equals(ROOF) ? "pavement:1;building:2;bldg_elev:2;bldg_cf:50" : "pavement:1", ""));
            }
        }
        var ground = BoardDecoration.Placement.ground();
        place(board, CAR, "car", "scenery/vehicles/car", false, ground, BoardDecoration.Colours.NONE);
        place(board, MIRRORED, "mirrored", "scenery/vehicles/car", true, ground, BoardDecoration.Colours.of("#1ee0f0"));
        place(board, ROOF, "roof", "scenery/vehicles/car", false, BoardDecoration.Placement.on("building", 0),
              BoardDecoration.Colours.of("#b8b014"));
        place(board, LONE, "lone", "scenery/vehicles/car", false, ground, BoardDecoration.Colours.of("#245c99"));
        place(board, GARDEN, "garden", "scenery/parks/formal-garden-hex", false, ground, BoardDecoration.Colours.NONE);
        return board;
    }

    private static void place(Board board, Coords at, String id, String asset, boolean mirror,
          BoardDecoration.Placement placement, BoardDecoration.Colours colours) {
        board.getHex(at).setDecorations(List.of(new BoardDecoration(id, "prop", asset, null, 0, 0, 30, mirror, 1,
              placement, 0, false).withColours(colours)));
    }

    private static BoardScene scene(Board board) throws Exception {
        var captured = BoardScenes.scene(board);
        return new BoardScene(0, captured.width(), captured.height(), captured.tiles(), List.of(), List.of(), -1, "",
              List.of(), new BoardScene.Light(-24, -30));
    }

    @Test
    void smallObjectsHideWhenZoomedOutInEveryPassAndView() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        BoardScene scene = scene(board(false)), edited = scene(board(true));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var assets = new GpuAssets();
                var terrain = new GpuTerrain();
                try {
                    profiler.enable();
                    int car = indices(assets.model("scenery/vehicles/car"));
                    terrain.update(scene);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.center(BoardGeometry.center(new Coords(6, 4), 0));
                    // The upright cars, whatever their colour, draw in one instanced draw (option A), the mirrored car in
                    // a batch of its own.
                    checkZoom(terrain, camera, profiler, 2 * car, "view");
                    checkHysteresis(terrain, camera, profiler, 2 * car);
                    checkShadowRedraws(terrain, camera, profiler, 2 * car);
                    checkOccupiedSmallChunk(terrain, camera, profiler);
                    checkOccupiedBuilding(terrain, camera, profiler, car);
                    checkPins(terrain, scene, camera, profiler, car);
                    zoom(camera, 9);
                    camera.setPerspective(true);
                    assertEquals(0, hidden(terrain, camera, profiler), "Perspective views hide nothing, like grass");
                    camera.setPerspective(false);
                    checkReusedTile(terrain, edited, camera, profiler, 2 * car, "view");
                    checkScar(terrain, edited, camera, profiler);
                    // The Classic graphics build the placed objects too, which follow the same rule there.
                    terrain.setColumns(true);
                    terrain.update(edited);
                    zoom(camera, 16);
                    assertEquals(0, hidden(terrain, camera, profiler));
                    zoom(camera, 9);
                    assertEquals(2 * car, hidden(terrain, camera, profiler), "The Classic graphics' objects follow the rule");
                    terrain.setColumns(false);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    profiler.disable();
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Small objects", failure.get()); }
    }

    /** Both cars hide at 9 px and show at 16 px, in the colour and shadow passes; the garden never hides. */
    private static void checkZoom(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler, int cars, String view) {
        zoom(camera, 16);
        assertEquals(0, hidden(terrain, camera, profiler), view + ": a 16 px hex shows every object");
        assertEquals(0, hiddenShadows(terrain, camera, profiler), view + ": and casts every shadow");
        capture(terrain, camera, view + "-16px.png");
        zoom(camera, 9);
        assertEquals(cars, hidden(terrain, camera, profiler), view + ": a 9 px hex hides the cars, and only them");
        assertEquals(cars, hiddenShadows(terrain, camera, profiler), view + ": the shadow redraw follows the rule");
        capture(terrain, camera, view + "-9px.png");
    }

    /** Hidden below 10.8 px, shown above 13.2 px; in the band each direction keeps its state. */
    private static void checkHysteresis(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler, int cars) {
        zoom(camera, 16);
        int shown = opaque(terrain, camera, profiler);
        zoom(camera, 9);
        assertEquals(shown - cars, opaque(terrain, camera, profiler));
        zoom(camera, 12);
        assertEquals(shown - cars, opaque(terrain, camera, profiler), "Coming from 9 px, a 12 px hex still hides them");
        zoom(camera, 14);
        assertEquals(shown, opaque(terrain, camera, profiler), "A 14 px hex shows them");
        zoom(camera, 12);
        assertEquals(shown, opaque(terrain, camera, profiler), "Coming from 14 px, a 12 px hex still shows them");
    }

    /** A crossing alone keeps the shadow map; the next redraw applies the rule. */
    private static void checkShadowRedraws(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler, int cars) {
        zoom(camera, 16);
        terrain.render(camera.camera);
        terrain.refreshShadows();
        int all = count(profiler, () -> terrain.renderShadows(null, List.of()));
        assertTrue(all > 0);
        zoom(camera, 9);
        terrain.render(camera.camera);
        assertEquals(0, count(profiler, () -> terrain.renderShadows(null, List.of())), "A crossing does not redraw the shadows");
        terrain.refreshShadows();
        assertEquals(all - cars, count(profiler, () -> terrain.renderShadows(null, List.of())));
    }

    /** A unit's arrival or departure on the lone hex fades its car without rebuilding a page; the car packs nothing. */
    private static void checkOccupiedSmallChunk(GpuTerrain game, BoardCamera camera, GLProfiler profiler) throws Exception {
        Object chunk = ((List<?>) GpuTestSupport.field(game, "chunks")).get(1);
        assertEquals(0, ((Array<?>) GpuTestSupport.field(chunk, "shadowPropRenderables")).size, "The lone hex's chunk packs no prop");
        assertTrue(((Array<?>) GpuTestSupport.field(chunk, "decorationRenderables")).size > 0, "The lone car draws instanced");
        var pages = (GpuPropBatch) GpuTestSupport.field(game, "propBatch");
        var box = new ModelBuilder().createBox(4, 4, 12, new Material(), VertexAttributes.Usage.Position);
        try {
            var unit = new ModelInstance(box, BoardGeometry.center(LONE, 0).add(0, 0, 6));
            zoom(camera, 9);
            opaque(game, camera, profiler);
            long builds = pages.rebuilds();
            for (boolean occupied : new boolean[] { true, false, true, false }) {
                game.animate(0, occupied ? List.of(unit) : List.of(), .5f);
                opaque(game, camera, profiler);
                assertEquals(occupied, !((java.util.Set<?>) GpuTestSupport.field(chunk, "faded")).isEmpty(), "The unit fades the prop under it");
            }
            assertEquals(builds, pages.rebuilds(), "Occupancy changes repack no page");
        } finally { box.dispose(); }
    }

    /** The faded car on the roof of a building with a unit inside follows the rule in the transparent pass. */
    private static void checkOccupiedBuilding(GpuTerrain game, BoardCamera camera, GLProfiler profiler, int car) {
        var occupant = GpuTestSupport.occupant(ROOF);
        zoom(camera, 9);
        game.animate(0, List.of(occupant), .5f);
        occupant.model.dispose();
        assertEquals(car, hiddenTransparent(game, camera, profiler), "The faded roof car hides at 9 px");
        zoom(camera, 14);
        assertEquals(0, hiddenTransparent(game, camera, profiler), "and fades as before at 14 px");
        game.animate(0, List.of(), .5f);
    }

    /** Selected or hovered objects draw and pick at every zoom; pins never enter the shadow map. */
    private static void checkPins(GpuTerrain editor, BoardScene scene, BoardCamera camera, GLProfiler profiler, int car) {
        zoom(camera, 9);
        editor.keepShown(Set.of());
        int base = opaque(editor, camera, profiler);
        editor.refreshShadows();
        int shadows = count(profiler, () -> editor.renderShadows(null, List.of()));
        var object = editor.editorObjects(CAR).getFirst();
        assertEquals(false, object.shown(), "Box select skips the hidden car");
        Vector3 centre = object.bounds().getCenter(new Vector3());
        var ray = new Ray(new Vector3(centre.x, centre.y, object.bounds().max.z + 100), new Vector3(0, 0, -1));
        assertNull(editor.decorationHit(scene, ray), "A hidden object cannot be picked");
        assertEquals(CAR, editor.selectionHit(scene, ray).coords(), "Picking passes through it to the ground");
        editor.keepShown(Set.of("car"));
        assertEquals(base + car, opaque(editor, camera, profiler), "The selected car draws");
        capture(editor, camera, "editor-9px-pinned.png");
        editor.refreshShadows();
        assertEquals(shadows, count(profiler, () -> editor.renderShadows(null, List.of())), "A pin casts no shadow");
        assertEquals("car", editor.decorationHit(scene, ray).id(), "The selected car can be picked");
        assertEquals(true, editor.editorObjects(CAR).getFirst().shown());
        editor.keepShown(Set.of());
        assertNull(editor.decorationHit(scene, ray));
    }

    /** An edit elsewhere in the chunk reuses the cars' tiles, which keep their size class. */
    private static void checkReusedTile(GpuTerrain terrain, BoardScene edited, BoardCamera camera, GLProfiler profiler,
          int cars, String view) throws Exception {
        Object before = carBounds(terrain);
        terrain.update(edited);
        assertSame(before, carBounds(terrain), view + ": the car's tile is reused");
        zoom(camera, 9);
        assertEquals(cars, hidden(terrain, camera, profiler), view + ": a reused small car stays hidden");
        zoom(camera, 16);
        assertEquals(0, hidden(terrain, camera, profiler), view + ": and shows again when zoomed in");
    }

    /** A combat scar on a car hides with the car at 9 px (Object LoD then removes more with scars than without). */
    private static void checkScar(GpuTerrain game, BoardScene scene, BoardCamera camera, GLProfiler profiler) throws Exception {
        zoom(camera, 16);
        opaque(game, camera, profiler);
        Vector3 aim = ((BoundingBox) carBounds(game)).getCenter(new Vector3());
        Vector3 origin = aim.cpy().add(BoardRelief.metres(10), 0, BoardRelief.metres(40));
        var mark = GroundDamagePlaybackTest.impact("ISAC20", null);
        mark.attack().landscape = ray -> game.hit(scene, ray);
        mark.attack().landscapeSegment = (ray, length) -> game.hit(scene, ray, length);
        var contact = mark.attack().landscapeContact(t -> origin.cpy().lerp(aim, (float) t), 1);
        assertNotNull(contact, "The shot strikes the car");
        game.impact(scene, new GpuGroundDamage.Impact(mark.attack(), "car", origin, contact.point(), mark.effect(), mark.shot()));
        opaque(game, camera, profiler);
        assertTrue(game.surfaceScars().tileCount() > 0, "The shot scars the car");
        assertEquals(0, hidden(game, camera, profiler), "A 16 px hex shows the car and its scar");
        zoom(camera, 9);
        game.setCombatScars(false);
        int cars = hidden(game, camera, profiler);
        game.setCombatScars(true);
        assertTrue(hidden(game, camera, profiler) > cars, "The car's scar hides with the car");
    }

    private static Object carBounds(GpuTerrain terrain) throws Exception {
        for (Object prop : (List<?>) GpuTestSupport.field(((List<?>) GpuTestSupport.field(terrain, "chunks")).getFirst(), "props")) {
            var instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
            if (CAR.equals(GpuTestSupport.field(prop, "coords")) && instance.nodes.first().id.equals("car")) { return GpuTestSupport.field(prop, "bounds"); }
        }
        throw new AssertionError("No car prop");
    }

    /** Vertices the rule removes from the opaque pass at this zoom (the test hook's off minus on). */
    private static int hidden(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler) {
        terrain.setSmallObjects(false);
        int all = opaque(terrain, camera, profiler);
        terrain.setSmallObjects(true);
        return all - opaque(terrain, camera, profiler);
    }

    private static int hiddenShadows(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler) {
        terrain.setSmallObjects(false);
        terrain.render(camera.camera);
        int all = count(profiler, () -> terrain.renderShadows(null, List.of()));
        terrain.setSmallObjects(true);
        terrain.render(camera.camera);
        return all - count(profiler, () -> terrain.renderShadows(null, List.of()));
    }

    private static int hiddenTransparent(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler) {
        terrain.setSmallObjects(false);
        int all = count(profiler, () -> terrain.renderTransparent(camera.camera));
        terrain.setSmallObjects(true);
        return all - count(profiler, () -> terrain.renderTransparent(camera.camera));
    }

    private static void capture(GpuTerrain terrain, BoardCamera camera, String name) {
        ScreenUtils.clear(.2f, .2f, .2f, 1, true);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        GpuReviewFrame.save(new File(OUTPUT, name));
    }

    private static int opaque(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler) {
        return count(profiler, () -> terrain.render(camera.camera));
    }

    private static int count(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        ScreenUtils.clear(0, 0, 0, 1, true);
        draw.run();
        return (int) profiler.getVertexCount().total;
    }

    /** A hex this many framebuffer pixels wide in the orthographic view. */
    private static void zoom(BoardCamera camera, float pixels) {
        camera.camera.zoom = BoardGeometry.width() * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight() / pixels;
        camera.update();
    }

    private static int indices(Model model) {
        int total = 0;
        for (Node node : model.nodes) { total += indices(node); }
        return total;
    }

    private static int indices(Node node) {
        int total = 0;
        for (NodePart part : node.parts) { total += part.meshPart.size; }
        for (Node child : node.getChildren()) { total += indices(child); }
        return total;
    }
}
