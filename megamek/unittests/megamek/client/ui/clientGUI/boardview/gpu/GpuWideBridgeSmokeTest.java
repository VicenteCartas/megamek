/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Close rendered views of the shared road/rail deck, its bank ramp and an authored rail terminus. */
@Tag("on-demand")
class GpuWideBridgeSmokeTest {
    private static final Coords RAMP = new Coords(4, 7);
    private static final Coords BANK = RAMP.translated(3);
    private static final Coords STOP = new Coords(4, 5);

    private record Variant(String name, boolean truss, boolean rail, boolean beside, boolean terminal,
          int direction, boolean reverse, boolean bare, boolean swap) {
        Variant(String name, boolean truss, boolean rail, boolean beside, boolean terminal) {
            this(name, truss, rail, beside, terminal, 3, false, false, false);
        }

        Coords ramp() { return direction == 3 ? RAMP : new Coords(5, 5); }

        Coords bank() { return ramp().translated(direction); }

        int exits() { return (1 << direction) | (1 << ((direction + 3) % 6)); }
    }

    @Test
    void wideDeckRampsAndRailTerminusRenderAndPickTheirWholeWidth() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "wide-bridges");
        Files.createDirectories(output.toPath());
        String selected = System.getProperty("megamek.gpu.wideBridgeView", "");
        var variants = List.of(
              new Variant("01-built-middle-ramp", false, true, false, false),
              new Variant("02-truss-middle-ramp", true, true, false, false),
              new Variant("03-truss-beside-ramp", true, true, true, false),
              new Variant("04-truss-beside-rail-end", true, true, true, true),
              new Variant("05-narrow-road-ramp", false, false, false, false),
              new Variant("06-recheck-built-reverse-low", false, true, false, false, 3, true, false, false),
              new Variant("07-recheck-truss-reverse-low", true, true, false, false, 3, true, false, false),
              new Variant("08-recheck-diagonal-beside-swapped", true, true, true, false, 2, false, false, true),
              new Variant("09-recheck-bare-roadless", false, false, false, false, 3, false, true, false))
              .stream().filter(variant -> variant.name().contains(selected)).toList();
        assertTrue(!variants.isEmpty(), "No wide-bridge view matches " + selected);
        var scenes = new java.util.ArrayList<BoardScene>();
        for (var variant : variants) { scenes.add(scene(variant)); }
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (int i = 0; i < variants.size(); i++) {
                        var variant = variants.get(i);
                        var scene = scenes.get(i);
                        var terrain = new GpuTerrain();
                        try {
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            camera.setIsometric(true);
                            float azimuth = 25 + 60 * (variant.direction() - 3) + (variant.reverse() ? 180 : 0);
                            camera.orbit(azimuth - camera.azimuth(), (variant.reverse() ? 77 : 57) - camera.tilt());
                            camera.camera.zoom = variant.terminal() ? .17f : variant.reverse() ? .14f : .12f;
                            camera.center(variant.terminal() ? BoardGeometry.center(STOP, 1)
                                  : BoardGeometry.center(variant.ramp(), 1).lerp(BoardGeometry.center(variant.bank(), 0), .5f));
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, variant.name() + ".png"));
                            // Diagonal grade geometry is covered at all six orientations in BoardBridgeWideTest.
                            if (variant.direction() == 3) { checkRamp(terrain, scene, variant.rail()); }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), variant.name());
                        } finally { terrain.dispose(); }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Wide bridge rendering and picking", failure.get()); }
    }

    private static void checkRamp(GpuTerrain terrain, BoardScene scene, boolean wide) {
        float scale = BoardGeometry.hexScale();
        // Probe the deck between the outside ballast foot and kerb, rather than hitting the raised track itself.
        float offset = wide ? BoardRoad.CORRIDOR_HALF - .1f : 4;
        for (float along : new float[] { .35f, .45f, .55f, .65f }) {
            var point = BoardGeometry.center(RAMP, 1).lerp(BoardGeometry.center(BANK, 0), along);
            float height = (1.5f - 2 * along) * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * scale;
            for (int side : new int[] { -1, 1 }) {
                var ray = new Ray(new Vector3(point.x + side * offset * scale, point.y, height + 20),
                      new Vector3(0, 0, -1));
                var hit = terrain.hit(scene, ray);
                assertNotNull(hit, "The installed ramp supports both outer lanes");
                assertEquals(400, hit.distance(), .05f,
                      "Both sides follow the same slope across the deck/bank seam, station " + along);
            }
        }
    }

    private static BoardScene scene(Variant variant) throws Exception {
        var board = Board.createEmptyBoard(11, 11);
        board.setNativeFormat(true);
        var route = new HashMap<Coords, Integer>();
        var at = variant.ramp();
        route.put(at, 0);
        for (int step = 1; step <= 3; step++) {
            at = at.translated(variant.direction());
            route.put(at, step);
        }
        at = variant.ramp();
        for (int step = -1; step >= -5; step--) {
            at = at.translated((variant.direction() + 3) % 6);
            route.put(at, step);
        }
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = new Hex(0, "", "grass");
                Integer step = route.get(new Coords(x, y));
                boolean bridge = step != null && step >= -4 && step <= 0;
                if (bridge) {
                    hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, variant.exits()));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
                    hex.setAppearance(Map.of("bridge", variant.truss() ? HexAppearance.TRUSS : HexAppearance.BUILT_BRIDGE));
                }
                if (step != null && !variant.bare()) {
                    hex.addTerrain(new Terrain(bridge ? Terrains.BRIDGE_ROAD : Terrains.ROAD, 1, true, variant.exits()));
                    if (variant.rail() && (!variant.terminal() || step >= -2)) {
                        int exits = variant.terminal() && step == -2 ? 1 << variant.direction() : variant.exits();
                        hex.addTerrain(new Terrain(bridge ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, exits));
                        if (variant.beside()) { RailRoute.beside(hex, bridge, variant.swap() ? 64 : variant.exits()); }
                    }
                }
                board.setHex(x, y, hex);
            }
        }
        var scene = BoardScenes.scene(board);
        if (variant.rail()) {
            var corridor = scene.tile(variant.ramp()).deckCorridor();
            assertEquals(variant.exits(), corridor.sides(), "The fixture contains a shared deck corridor");
            assertEquals(variant.beside() ? variant.exits() : 0, corridor.beside());
            if (variant.beside()) { assertEquals(variant.swap() ? 0 : variant.exits(), corridor.right()); }
            assertTrue((scene.tile(variant.bank()).corridor().sides() & 1 << ((variant.direction() + 3) % 6)) != 0,
                  "The ground corridor meets the ramp");
        } else if (variant.bare()) {
            assertEquals(BoardRoad.Kind.NONE, scene.tile(variant.ramp()).deckRoad());
            assertEquals(BoardRoad.Kind.NONE, scene.tile(variant.bank()).road());
        }
        return scene;
    }
}
