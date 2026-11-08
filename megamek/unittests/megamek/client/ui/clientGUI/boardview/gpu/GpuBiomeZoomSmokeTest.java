/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import jdk.jfr.Recording;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Exercise actual terrain handoffs while zooming: fixed-mesh plant cross-fades alone cannot detect rebuild stalls. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuBiomeZoomSmokeTest {
    @Test
    void grassFieldsAndMarshZoomWithTerrainDetailEnabled() throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "biome-zoom");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.useVsync(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try (var recording = new Recording()) {
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
                    recording.enable("jdk.ObjectAllocationSample");
                    recording.enable("jdk.GarbageCollection");
                    recording.start();
                    BoardScenes.tune(.94f, true);
                    var report = new StringBuilder("24x24; native 1280x960; terrain LOD enabled; paced outside measurements\n");
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
                    for (var kind : List.of(BoardScene.Biome.NONE, BoardScene.Biome.FIELD, BoardScene.Biome.MARSH)) {
                        var terrain = new GpuTerrain();
                        try {
                            var camera = new BoardCamera();
                            camera.resize(1280, 960);
                            camera.setIsometric(true);
                            var tiles = new ArrayList<BoardScene.Tile>();
                            for (int x = 0; x < 24; x++) {
                                for (int y = 0; y < 24; y++) {
                                    tiles.add(BoardScenes.tile(new Coords(x, y), kind, x < 12 ? 0 : 1));
                                }
                            }
                            var scene = new BoardScene(0, 24, 24, tiles, List.of(), List.of(), -1, "", List.of());
                            camera.camera.zoom = BoardGeometry.width() / 24;
                            camera.center(BoardGeometry.center(new Coords(12, 12), 0));
                            terrain.update(scene, camera.camera);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            var plants = (GpuBiomeVegetation) GpuTestSupport.field(terrain, "biomeVegetation");
                            var grass = (GpuGroundCover) GpuTestSupport.field(terrain, "groundCover");
                            try (var timings = new GpuStageTimings()) {
                                for (int pass = 0; pass < 3; pass++) {
                                    long uploads = plants.uploads(), grassUploads = grass.uploads();
                                    for (int step = 0; step < 180; step++) {
                                        float t = (step < 90 ? step : 179 - step) / 89f;
                                        float pixels = (float) (24 * Math.pow(560.0 / 24, t));
                                        camera.camera.zoom = BoardGeometry.width() / pixels;
                                        camera.center(BoardGeometry.center(new Coords(12, 12), 0));
                                        timings.beginFrame();
                                        timings.stage("terrain-handoff");
                                        terrain.refine(camera.camera);
                                        timings.stage("frame");
                                        frame.render(terrain, camera, scene);
                                        timings.stage(null);
                                        // Swap/query waits are excluded from render-thread submission and GPU timings.
                                        GLFW.glfwSwapBuffers(GLFW.glfwGetCurrentContext());
                                        Gdx.gl.glFinish();
                                        if (step == 45 && pass == 2) { GpuReviewFrame.save(new File(output, kind + ".png")); }
                                    }
                                    report.append(kind).append(" pass ").append(pass).append(": plant uploads=")
                                          .append(plants.uploads() - uploads).append(", grass uploads=")
                                          .append(grass.uploads() - grassUploads).append('\n');
                                    timings.appendReport(report, kind + " zoom " + pass);
                                    Files.writeString(new File(output, "timings.txt").toPath(), report.toString());
                                }
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            assertTrue(terrain.ready(scene));
                        } finally { terrain.dispose(); }
                    }
                    recording.stop();
                    recording.dump(new File(output, "zoom.jfr").toPath());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Biome zoom profiling", failure.get()); }
    }
}
