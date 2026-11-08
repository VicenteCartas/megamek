/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Geometry regressions run normally; the rendered contact check requires the on-demand OpenGL task. */
class GpuRailTessellationSmokeTest {
    /** Two rising tracks: one on ballast with a crossing, the other embedded in an asphalt road. */
    static BoardScene scene(boolean rails) {
        Board board = Board.createEmptyBoard(9, 9);
        board.setNativeFormat(true);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                Hex hex = new Hex(y >= 4 ? 1 : 0);
                if (x == 5) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9)); }
                if (x >= 1 && x <= 3 && y == 4) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 18)); }
                if (rails && (x == 2 || x == 5)) { RailRoute.lay(hex, false, 9, 0, RailRoute.Kind.RAIL); }
                board.setHex(x, y, hex);
            }
        }
        return new BoardScene(0, 9, 9, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void rigidTracksAndCrossingsKeepTheirOriginalSupportingPlanes() {
        BoardScene scene = scene(true);
        int supports = 0, curvedSupports = 0, streetSupports = 0, freeCurves = 0, shared = 0;
        var contacts = new HashMap<Vector3, Boolean>();
        for (int x = 1; x <= 6; x++) {
            for (int y = 2; y <= 5; y++) {
                var tile = scene.tile(x, y);
                var surface = new BoardSurface(scene, tile, TerrainLod.FULL);
                surface.walls(scene, BoardGeometry.floor(scene));
                for (var face : surface.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    var a = GpuTerrain.sculptVertex(face.a(), surface.relief.shade(face.a()), Float.NaN, surface);
                    var b = GpuTerrain.sculptVertex(face.b(), surface.relief.shade(face.b()), Float.NaN, surface);
                    var c = GpuTerrain.sculptVertex(face.c(), surface.relief.shade(face.c()), Float.NaN, surface);
                    boolean curved = GpuTessellation.curves(a.position, a.normal, b.position, b.normal, c.position, c.normal);
                    if (curved && !surface.fixedTessellation(a.position) && !surface.fixedTessellation(b.position)
                          && !surface.fixedTessellation(c.position)) { freeCurves++; }
                    for (var vertex : List.of(a, b, c)) {
                        boolean fixed = surface.fixedTessellation(vertex.position);
                        var previous = contacts.putIfAbsent(vertex.position, fixed);
                        if (previous != null) {
                            assertEquals(previous, fixed, "Shared rail contact at " + vertex.position + " in " + tile.coords());
                            shared++;
                        }
                    }
                }
                var rail = BoardRail.route(tile);
                if (rail == null) { continue; }
                var layout = BoardRail.layout(scene, tile, rail, surface, at -> null);
                Vector3 origin = BoardGeometry.center(tile.coords(), BoardRail.level(tile, rail));
                for (var path : layout.paths()) {
                    for (int i = 1; i < path.size(); i++) {
                        var centre = new Vector3(path.get(i - 1)).lerp(path.get(i), .5f);
                        var across = new Vector3(path.get(i)).sub(path.get(i - 1));
                        across.set(-across.y, across.x, 0).nor();
                        for (float width : new float[] { -BoardRail.GAUGE_HALF, 0, BoardRail.GAUGE_HALF }) {
                            Vector3 p = new Vector3(centre).mulAdd(across, width).scl(BoardGeometry.hexScale()).add(origin);
                            float support = BoardSurface.sampleHeight(surface.groundFaces(), p.x, p.y, Float.NaN);
                            for (var face : surface.groundFaces()) {
                                if (face.finish() != BoardSurface.Finish.TOP
                                      || !Float.isFinite(face.height(p.x, p.y))
                                      || Math.abs(face.height(p.x, p.y) - support) > .001f) { continue; }
                                var a = GpuTerrain.sculptVertex(face.a(), surface.relief.shade(face.a()), Float.NaN, surface);
                                var b = GpuTerrain.sculptVertex(face.b(), surface.relief.shade(face.b()), Float.NaN, surface);
                                var c = GpuTerrain.sculptVertex(face.c(), surface.relief.shade(face.c()), Float.NaN, surface);
                                supports++;
                                if (GpuTessellation.curves(a.position, a.normal, b.position, b.normal, c.position, c.normal)) {
                                    for (var vertex : List.of(a, b, c)) {
                                        assertTrue(surface.fixedTessellation(vertex.position),
                                              "Rail support must stay fixed at " + tile.coords() + ": " + vertex.position);
                                    }
                                    curvedSupports++;
                                }
                                if (BoardRoad.rendered(tile)) { streetSupports++; }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(supports > 100 && streetSupports > 20, "Exercise ballast and embedded road tracks");
        assertTrue(curvedSupports > 0, "The fixture must exercise support that would otherwise bulge");
        assertTrue(freeCurves > 0, "Surrounding natural ground must retain tessellation");
        assertTrue(shared > 100, "Exercise shared control vertices around track beds");
    }

    @Test
    void ordinaryRoadsKeepTheirCurvedSurface() {
        BoardScene scene = scene(false);
        var surface = new BoardSurface(scene, scene.tile(5, 4));
        int curved = 0;
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            var a = GpuTerrain.sculptVertex(face.a(), surface.relief.shade(face.a()), Float.NaN, surface);
            var b = GpuTerrain.sculptVertex(face.b(), surface.relief.shade(face.b()), Float.NaN, surface);
            var c = GpuTerrain.sculptVertex(face.c(), surface.relief.shade(face.c()), Float.NaN, surface);
            if (GpuTessellation.curves(a.position, a.normal, b.position, b.normal, c.position, c.normal)) {
                assertFalse(surface.fixedTessellation(a.position));
                assertFalse(surface.fixedTessellation(b.position));
                assertFalse(surface.fixedTessellation(c.position));
                curved++;
            }
        }
        assertTrue(curved > 0, "Only rigid rail support is pinned, not the ordinary road grade");
    }

    @Test
    @Tag("on-demand")
    void railHeadsRemainVisibleWithTessellation() throws Exception {
        BoardScene scene = scene(true);
        var available = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        File output = GpuCalibrationScenes.OUTPUT;
        Files.createDirectories(output.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
                var camera = new BoardCamera();
                try {
                    available.set(GpuTessellation.available());
                    if (!available.get()) { return; }
                    GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                    for (float tilt : new float[] { 0, 55 }) {
                        camera.setIsometric(false);
                        camera.orbit(0 - camera.azimuth(), tilt - camera.tilt());
                        camera.camera.zoom = 180 / camera.camera.viewportHeight;
                        camera.center(BoardGeometry.center(new Coords(2, 4), .5f)
                              .lerp(BoardGeometry.center(new Coords(5, 4), .5f), .5f));
                        terrain.setTessellation(false);
                        terrain.update(scene);
                        byte[] before = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                              new File(output, "rail-" + (int) tilt + "-off.png"));
                        checkRoadEnd(camera, before);
                        terrain.setTessellation(true);
                        terrain.update(scene);
                        byte[] after = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                              new File(output, "rail-" + (int) tilt + "-on.png"));
                        checkRoadEnd(camera, after);
                        checkRailHeads(scene, camera, before, after);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tessellated rail contacts", failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
    }

    /** The pavement continues over the neighbouring rim, including the notch reported in the original capture. */
    private static void checkRoadEnd(BoardCamera camera, byte[] pixels) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        for (float x : new float[] { 253, 255, 257 }) {
            var point = camera.camera.project(new Vector3(x, -330, BoardGeometry.level()), 0, 0, width, height);
            int at = ((int) point.y * width + (int) point.x) * 4;
            int r = Byte.toUnsignedInt(pixels[at]), g = Byte.toUnsignedInt(pixels[at + 1]),
                  b = Byte.toUnsignedInt(pixels[at + 2]);
            assertTrue(Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 14,
                  "Road end must show asphalt at " + x + " / tilt " + camera.tilt() + ": " + r + "," + g + "," + b);
        }
    }

    /** Samples rail heads and the lower panels between them, where a crossing is set into the road's surface. */
    private static void checkRailHeads(BoardScene scene, BoardCamera camera, byte[] before, byte[] after) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        int checked = 0, changed = 0, panels = 0;
        for (int x : new int[] { 2, 5 }) {
            for (int y = 2; y <= 5; y++) {
                var tile = scene.tile(x, y);
                var rail = BoardRail.route(tile);
                var layout = BoardRail.layout(scene, tile, rail, new BoardSurface(scene, tile), at -> null);
                var origin = BoardGeometry.center(tile.coords(), BoardRail.level(tile, rail));
                for (int line = 0; line < layout.paths().size(); line++) {
                    var path = layout.paths().get(line);
                    var street = layout.streets().get(line);
                    for (int i = 1; i < path.size(); i++) {
                        var across = new Vector3(path.get(i)).sub(path.get(i - 1));
                        across.set(-across.y, across.x, 0).nor();
                        float start = street.get(i - 1) ? BoardRail.STREET_TOP : RailRoute.RAIL_TOP;
                        float end = street.get(i) ? BoardRail.STREET_TOP : RailRoute.RAIL_TOP;
                        for (float t : new float[] { .3f, .7f }) {
                            for (float gauge : new float[] { -BoardRail.GAUGE_HALF, 0, BoardRail.GAUGE_HALF }) {
                                if (gauge == 0 && !(street.get(i - 1) && street.get(i))) { continue; }
                                float top = start + t * (end - start);
                                if (gauge == 0) { top *= .8f; }
                                var p = new Vector3(path.get(i - 1)).lerp(path.get(i), t).mulAdd(across, gauge)
                                      .add(0, 0, top).scl(BoardGeometry.hexScale()).add(origin);
                                camera.camera.project(p, 0, 0, width, height);
                                int px = (int) p.x, py = (int) p.y;
                                if (px < 1 || py < 1 || px >= width - 1 || py >= height - 1 || p.z < 0 || p.z > 1) { continue; }
                                int at = (py * width + px) * 4, difference = 0;
                                for (int channel = 0; channel < 3; channel++) {
                                    difference += Math.abs(Byte.toUnsignedInt(before[at + channel])
                                          - Byte.toUnsignedInt(after[at + channel]));
                                }
                                if (difference > 24) { changed++; }
                                if (gauge == 0) { panels++; }
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("Rail contact samples at tilt %.0f: %d / %d changed (%d crossing panels)%n",
              camera.tilt(), changed, checked, panels);
        assertTrue(checked > 100, "The close views must exercise both tracks");
        assertTrue(panels > 0, "Include the low crossing panels, not only the raised rail heads");
        assertTrue(changed <= checked / 50, "Tessellation must preserve rail visibility: " + changed + " / " + checked);
    }
}
