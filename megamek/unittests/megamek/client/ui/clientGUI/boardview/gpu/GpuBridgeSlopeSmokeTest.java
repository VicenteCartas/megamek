/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Rendered slope, picking and edit integration using the same installed bridge geometry. */
@Tag("on-demand")
class GpuBridgeSlopeSmokeTest {
    @Test
    void addingAndRemovingRoadPaintKeepsTheBareBridgeRampAndPicking() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "bridge-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var at = new Coords(4, 4);
                var bankAt = at.translated(3);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(25 - camera.azimuth(), 65 - camera.tilt());
                    camera.camera.zoom = .11f;
                    camera.center(BoardGeometry.center(at, 1).lerp(BoardGeometry.center(bankAt, 0), .5f));
                    frame.prepare(terrain, camera, BridgeScenes.lowerBank(at, 3, false));
                    for (int step = 0; step < 3; step++) {
                        boolean paved = step == 1;
                        var scene = BridgeScenes.lowerBank(at, 3, paved);
                        terrain.update(scene);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, step + (paved ? "-road-ramp.png" : "-bare-ramp.png")));
                        for (float t : new float[] { .35f, .45f, .55f, .65f }) {
                            float height = (1.5f - 2 * t) * BoardGeometry.level()
                                  + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
                            var p = BoardGeometry.center(at, 1).lerp(BoardGeometry.center(bankAt, 0), t);
                            var ray = new Ray(new Vector3(p.x, p.y, height + 20), new Vector3(0, 0, -1));
                            var hit = terrain.hit(scene, ray);
                            assertNotNull(hit);
                            assertEquals(400, hit.distance(), .05f,
                                  "Installed picking follows the same continuous ramp before and after road edits");
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Bare bridge ramp rendering and road edits", failure.get()); }
    }

    /** Close views of the steel fascia where the sloping deck meets each concrete approach. */
    @Test
    void trussApproachesRenderAtBothBankHeightsAndLevelScales() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "bridge-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var previous = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var at = new Coords(4, 4);
                try {
                    for (int levelHeight : new int[] { 18, 30 }) {
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, previous.gridShade()));
                        for (int roadLevel : new int[] { 0, 2 }) {
                            var scene = BridgeScenes.typed(BoardScenes.scene(c -> {
                                if (c.equals(at)) { return BridgeScenes.bridge(c, -1, 2, 9); }
                                boolean approach = c.equals(at.translated(0)) || c.equals(at.translated(3));
                                return BoardScenes.tile(c, approach ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                                      approach ? 9 : 0, approach ? roadLevel : -1, BoardScene.Surface.ROCK);
                            }), HexAppearance.TRUSS);
                            var terrain = new GpuTerrain();
                            try {
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                frame.prepare(terrain, camera, scene);
                                terrain.update(scene);
                                terrain.animate(0, List.of());
                                for (boolean side : new boolean[] { false, true }) {
                                    camera.setIsometric(true);
                                    if (side) { camera.orbit(45, 78 - camera.tilt()); }
                                    camera.camera.zoom = .12f;
                                    camera.center(BoardGeometry.center(at, 1));
                                    GpuReviewFrame.settle(terrain, null, scene, camera);
                                    frame.render(terrain, camera, scene);
                                    GpuReviewFrame.save(new File(output, "truss-approach-road" + roadLevel + "-lh"
                                          + levelHeight + (side ? "-side" : "-iso") + ".png"));
                                }
                            } finally { terrain.dispose(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    BoardGeometry.tune(previous);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Truss approach fascia rendering", failure.get()); }
    }

    @Test
    void steppedSpansRenderAndPickTheirSlopesAfterAnElevationEdit() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridge-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var at = new Coords(4, TerrainLod.CHUNK_SIZE - 1);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.camera.zoom = .19f;
                    camera.orbit(70, 15);
                    camera.center(BoardGeometry.center(at, 1));
                    for (int exits : new int[] { 9, 63 }) {
                        var scene = BridgeScenes.routeScene(at, exits, true);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "manufactured-" + exits + ".png"));
                        float lift = GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
                        var p = BoardGeometry.center(at, 2).lerp(BoardGeometry.center(at.translated(3), 2), .4f);
                        float height = 1.7f * BoardGeometry.level() + lift;
                        assertPick(terrain, scene, at, p, height);
                        var edited = BoardScenes.replace(scene,
                              BridgeScenes.bridge(at.translated(3), -2, 6, 9));
                        terrain.update(edited);
                        GpuReviewFrame.settle(terrain, null, edited, camera);
                        assertPick(terrain, edited, at, p, 2 * BoardGeometry.level() + lift);
                    }
                    var natural = BridgeScenes.typed(BridgeScenes.routeScene(at, 9, false),
                          megamek.common.board.HexAppearance.NATURAL_BRIDGE);
                    frame.prepare(terrain, camera, natural);
                    terrain.update(natural);
                    GpuReviewFrame.settle(terrain, null, natural, camera);
                    frame.render(terrain, camera, natural);
                    GpuReviewFrame.save(new File(output, "natural.png"));
                    assertPick(terrain, natural, at, BoardGeometry.center(at, 2), 2 * BoardGeometry.level());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Stepped bridge rendering and picking", failure.get()); }
    }

    private static void assertPick(GpuTerrain terrain, BoardScene scene, Coords at, Vector3 p, float height) {
        var ray = new Ray(new Vector3(p.x, p.y, height + 20), new Vector3(0, 0, -1));
        var hit = terrain.hit(scene, ray);
        assertNotNull(hit);
        assertEquals(at, hit.coords());
        assertEquals(400, hit.distance(), .05f, "Picking must hit the drawn slope, including after edits");
        assertNotNull(terrain.roofBounds(at));
    }
}
