/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Natural terrain alone under a repeatable moving camera: frame timings, picking and material stability in pans. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuTerrainMaterialReviewSmokeTest {
    private static final List<BoardScene.Surface> FAMILIES = List.of(BoardScene.Surface.SAND,
          BoardScene.Surface.DESERT, BoardScene.Surface.VOLCANO);
    private static final int WARMUP = 120;
    private static final int SAMPLES = 480;

    @Test
    void reviewsMaterialsDuringPanAndZoom() throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "material-motion");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.useVsync(false);
        config.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            private final GpuShaderManager manager = new GpuShaderManager();
            private final StringBuilder report = new StringBuilder();
            private final StringBuilder stability = new StringBuilder("view,metresPerPixel,maximumAlignedMeanByteError\n");
            private final double[] cpu = new double[SAMPLES];
            private final double[] intervals = new double[SAMPLES];
            private GpuTerrain terrain;
            private GpuReviewFrame compositor;
            private GpuStageTimings timings;
            private BoardScene scene;
            private BoardCamera camera;
            private Vector3 focus;
            private Ray ray;
            private Coords picked;
            private int scenario;
            private int frame;
            private long previous;
            private double refinementMaximum;

            @Override
            public void create() {
                manager.run(() -> {
                    try {
                        compositor = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                        timings = new GpuStageTimings();
                        report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; ")
                              .append(Gdx.graphics.getBackBufferWidth()).append('x').append(Gdx.graphics.getBackBufferHeight())
                              .append("; 120 warmup / 480 samples; top and isometric; moving terrain only\n");
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                });
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                manager.run(() -> {
                    try {
                        if (frame == 0) { prepare(); }
                        long start = System.nanoTime();
                        if (frame >= WARMUP) { intervals[frame - WARMUP] = (start - previous) / 1e6; }
                        previous = start;
                        timings.beginFrame(frame >= WARMUP);
                        timings.stage("moving terrain frame");
                        // One simulated camera cycle per sample window; performance does not change the camera path.
                        float phase = (float) (2 * Math.PI * (frame - WARMUP) / SAMPLES);
                        camera.camera.zoom = BoardRelief.metres(.40f + .22f * (float) Math.cos(phase));
                        camera.center(focus.cpy().add(BoardGeometry.width() * 1.3f * (float) Math.sin(phase),
                              BoardGeometry.height() * .6f * (float) Math.sin(2 * phase), 0));
                        long refining = System.nanoTime();
                        terrain.refine(camera.camera);
                        if (frame >= WARMUP) {
                            refinementMaximum = Math.max(refinementMaximum, (System.nanoTime() - refining) / 1e6);
                        }
                        terrain.animate(1 / 60f, List.of());
                        compositor.render(terrain, camera, scene);
                        timings.stage(null);
                        if (frame >= WARMUP) { cpu[frame - WARMUP] = (System.nanoTime() - start) / 1e6; }
                        if (++frame == WARMUP + SAMPLES) {
                            String name = name();
                            timings.appendReport(report, name);
                            Arrays.sort(cpu);
                            Arrays.sort(intervals);
                            report.append(String.format(Locale.ROOT,
                                  "CPU median/p95 %.4f/%.4f ms; interval median/p95 %.4f/%.4f ms; refinement max %.4f ms%n",
                                  percentile(cpu, .5), percentile(cpu, .95), percentile(intervals, .5),
                                  percentile(intervals, .95), refinementMaximum));
                            Files.writeString(new File(output, "timings.txt").toPath(), report);
                            var hit = terrain.hit(scene, ray);
                            assertNotNull(hit, name);
                            assertEquals(picked, hit.coords(), name + ": preserve shared picking while changing detail");
                            assertEquals(0, terrain.treeGeometryBytes(), "The review must not allocate tree geometry");
                            capture();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), name);
                            frame = 0;
                            if (++scenario == FAMILIES.size() * 2) { Gdx.app.exit(); }
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                });
            }

            private String name() {
                return FAMILIES.get(scenario / 2).name().toLowerCase(Locale.ROOT)
                      + (scenario % 2 == 0 ? "-top" : "-iso");
            }

            private void prepare() throws Exception {
                if (scenario % 2 == 0) {
                    if (terrain != null) { terrain.dispose(); }
                    terrain = new GpuTerrain();
                    var layout = GpuTerrainShowcaseSmokeTest.scene(FAMILIES.get(scenario / 2));
                    var tiles = layout.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(), tile.elevation(),
                          tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(), null, null, List.of(),
                          List.of(), tile.liquid(), null, true)).toList();
                    scene = new BoardScene(0, layout.width(), layout.height(), tiles, List.of(), List.of(), -1, "", List.of());
                    terrain.update(scene);
                    focus = BoardGeometry.center(new Coords(7, 5), 1);
                    ray = new Ray(focus.cpy().add(0, 0, 10000), new Vector3(0, 0, -1));
                    var hit = terrain.hit(scene, ray);
                    assertNotNull(hit);
                    picked = hit.coords();
                }
                camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(scenario % 2 == 1);
                camera.center(focus);
                camera.camera.zoom = BoardRelief.metres(.18f);
                camera.update();
                compositor.prepare(terrain, camera, scene);
                GpuReviewFrame.settle(terrain, null, scene, camera);
                compositor.render(terrain, camera, scene);
                refinementMaximum = 0;
            }

            private void capture() throws Exception {
                for (float metres : new float[] { .18f, .35f, .60f }) {
                    camera.camera.zoom = BoardRelief.metres(metres);
                    camera.center(focus);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    compositor.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, name() + "-" + metres + "m.png"));
                    // A whole-pixel translation must preserve the same material at the same world points. This
                    // checks mip/derivative stability without mistaking actual camera motion for image flicker.
                    byte[] reference = ScreenUtils.getFrameBufferPixels(false);
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    float scaleX = (float) width / Gdx.graphics.getWidth();
                    float scaleY = (float) height / Gdx.graphics.getHeight();
                    var start = camera.camera.project(focus.cpy());
                    double maximum = 0;
                    for (int step = 0; step < 8; step++) {
                        camera.pan(1 / scaleX, 0);
                        compositor.render(terrain, camera, scene);
                        var next = camera.camera.project(focus.cpy()).sub(start);
                        int dx = Math.round(next.x * scaleX), dy = Math.round(next.y * scaleY);
                        assertEquals(dx, next.x * scaleX, .02f, "Review an integer physical-pixel pan");
                        assertEquals(dy, next.y * scaleY, .02f, "Review an integer physical-pixel pan");
                        byte[] moved = ScreenUtils.getFrameBufferPixels(false);
                        long error = 0;
                        for (int y = height / 2 - 64; y < height / 2 + 64; y++) {
                            for (int x = width / 2 - 64; x < width / 2 + 64; x++) {
                                int a = (y * width + x) * 4, b = ((y + dy) * width + x + dx) * 4;
                                for (int channel = 0; channel < 3; channel++) {
                                    error += Math.abs((reference[a + channel] & 255) - (moved[b + channel] & 255));
                                }
                            }
                        }
                        maximum = Math.max(maximum, error / (128.0 * 128 * 3));
                    }
                    stability.append(String.format(Locale.ROOT, "%s,%.2f,%.6f%n", name(), metres, maximum));
                    Files.writeString(new File(output, "stability.csv").toPath(), stability);
                    assertTrue(maximum < .5, name() + " at " + metres + " m/pixel: aligned material error " + maximum);
                }
                // Close material contact on the same crest, in both gameplay views, without tree features.
                camera.camera.zoom = .16f;
                camera.center(BoardGeometry.center(new Coords(8, 5), 2));
                if (scenario % 2 == 1) { camera.orbit(30, 12); }
                GpuReviewFrame.settle(terrain, null, scene, camera);
                compositor.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, name() + "-rim.png"));
                camera.camera.zoom = .06f;
                camera.update();
                GpuReviewFrame.settle(terrain, null, scene, camera);
                compositor.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, name() + "-rim-close.png"));
            }

            @Override
            public void dispose() {
                if (terrain != null) { terrain.dispose(); }
                if (compositor != null) { compositor.dispose(); }
                if (timings != null) { timings.close(); }
                manager.close();
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain material motion review", failure.get()); }
    }

    private static double percentile(double[] values, double fraction) {
        return values[Math.min(values.length - 1, (int) (values.length * fraction))];
    }
}
