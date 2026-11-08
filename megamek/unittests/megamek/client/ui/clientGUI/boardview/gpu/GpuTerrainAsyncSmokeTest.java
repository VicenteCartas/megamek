/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in renderer benchmark: source capture is frozen; timings include drawing during edits and tuning. */
@Tag("on-demand")
class GpuTerrainAsyncSmokeTest {
    /** Recorded only for slow frames; stage times locate stalls in the matching flight recording. */
    @Name("megamek.TerrainFrame")
    @StackTrace(false)
    static class TerrainFrame extends Event {
        int phase;
        double refineMs, shadowsMs, opaqueMs, transparentMs;
    }

    @Test
    void measuresOpeningEditingTuningAndZoomingWhileRendering() throws Exception {
        String path = System.getProperty("megamek.gpu.performanceBoard", "");
        assumeTrue(!path.isEmpty());
        Board board = BoardFile.loadSheet(new File(path), false, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var original = BoardGeometry.tuning();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                try { ((Timer) GpuTestSupport.field(fixture.source, "timer")).stop(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuTerrain terrain;
                GpuSceneTestOutput outputFrame;
                final BoardCamera camera = new BoardCamera();
                BoardScene scene;
                int phase, quiet, warmup;
                long began, previousFrame;
                long peakHeap;
                final List<Double> submissions = new ArrayList<>(), intervals = new ArrayList<>();
                final TerrainFrame frame = new TerrainFrame();

                @Override
                public void create() {
                    terrain = new GpuTerrain();
                    outputFrame = new GpuSceneTestOutput();
                    scene = fixture.source.takeFrame().scene();
                    camera.resize(1280, 900);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    begin();
                }

                private void begin() {
                    began = System.nanoTime();
                    terrain.update(scene, camera.camera);
                    System.out.printf("ASYNC phase=%d request=%.3f ms%n", phase, (System.nanoTime() - began) / 1e6);
                    quiet = 0;
                    previousFrame = 0;
                    peakHeap = 0;
                    submissions.clear();
                    intervals.clear();
                }

                @Override
                public void render() {
                    try {
                        long start = System.nanoTime();
                        frame.begin();
                        frame.phase = phase;
                        frame.shadowsMs = frame.opaqueMs = frame.transparentMs = 0;
                        boolean changed = terrain.refine(camera.camera);
                        frame.refineMs = (System.nanoTime() - start) / 1e6;
                        boolean ready = terrain.ready(scene);
                        try (var ignored = TerrainSettings.use(terrain.settings())) {
                            if (ready) {
                                terrain.animate(0, List.of());
                                long stage = System.nanoTime();
                                terrain.renderShadows(camera.camera, List.of());
                                frame.shadowsMs = (System.nanoTime() - stage) / 1e6;
                                outputFrame.begin(.2f, .26f, .31f, 1);
                                stage = System.nanoTime();
                                terrain.render(camera.camera);
                                frame.opaqueMs = (System.nanoTime() - stage) / 1e6;
                                stage = System.nanoTime();
                                terrain.renderTransparent(camera.camera);
                                frame.transparentMs = (System.nanoTime() - stage) / 1e6;
                            } else { outputFrame.begin(.2f, .26f, .31f, 1); }
                        }
                        frame.end();
                        double elapsed = (System.nanoTime() - start) / 1e6;
                        if (elapsed > 16.667) {
                            frame.commit();
                            System.out.printf("ASYNC slow phase=%d warmup=%d total=%.3f refine=%.3f shadows=%.3f opaque=%.3f transparent=%.3f ms%n",
                                  phase, warmup, elapsed, frame.refineMs, frame.shadowsMs, frame.opaqueMs, frame.transparentMs);
                        }
                        if (warmup > 0) {
                            if (--warmup > 0) { return; }
                            if (phase == 1) {
                                var at = new Coords(scene.width() / 2, scene.height() / 2);
                                var tile = scene.tile(at);
                                var tiles = new ArrayList<>(scene.tiles());
                                tiles.set(tiles.indexOf(tile), new BoardScene.Tile(at, tile.elevation() + 1,
                                      tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(), tile.decals(),
                                      tile.decalsWithoutLimbs(),
                                      tile.features(), tile.text(), tile.liquid(), tile.tileset(), tile.detailedGround(), tile.road()));
                                scene = new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles,
                                      scene.units(), scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(),
                                      scene.light(), scene.firingLines(), scene.rangeBorders(), scene.markers(), scene.tactical(),
                                      scene.rangeLabels(), scene.fieldOfView());
                            } else if (phase == 2) {
                                BoardGeometry.tune(new BoardGeometry.Tuning(original.hexScale(), original.unitScale(),
                                      original.unitHeightScale(), original.levelHeight() + 1, original.gridShade(),
                                      original.multiHexUnitScale(), original.transitions(), original.padding()));
                            } else {
                                camera.zoom((phase == 3 ? 2f : .5f) / camera.camera.zoom);
                            }
                            var displayed = terrain.tacticalSurface(scene.tiles().getFirst().coords());
                            begin();
                            assertSame(displayed, terrain.tacticalSurface(scene.tiles().getFirst().coords()));
                            return;
                        }
                        submissions.add(elapsed);
                        if (previousFrame != 0) { intervals.add((start - previousFrame) / 1e6); }
                        previousFrame = start;
                        var runtime = Runtime.getRuntime();
                        peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
                        quiet = ready && !changed && !terrain.busy() ? quiet + 1 : 0;
                        assertTrue(System.nanoTime() - began < 180_000_000_000L, "A pending board revision must complete");
                        if (quiet < 3) { return; }
                        System.out.printf("ASYNC phase=%d complete=%.3f ms frames=%d peakUsedHeap=%.1f MiB%n",
                              phase, (System.nanoTime() - began) / 1e6, submissions.size(), peakHeap / 1048576.0);
                        report("submission", submissions);
                        report("frame", intervals);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        String screenshots = System.getProperty("megamek.gpu.screenshots");
                        if (screenshots != null) {
                            // Let incremental page caches settle before comparing images; exclude this from timings.
                            for (int i = 0; i < 20; i++) {
                                terrain.renderShadows(camera.camera, List.of());
                                outputFrame.begin(.2f, .26f, .31f, 1);
                                terrain.render(camera.camera);
                                terrain.renderTransparent(camera.camera);
                            }
                            outputFrame.display();
                            GpuBoardTestUi.capture(new File(screenshots, "async-phase-" + phase + ".png"));
                        }
                        if (++phase == 5) { Gdx.app.exit(); }
                        else { warmup = 90; }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }

                @Override
                public void dispose() { outputFrame.close(); terrain.dispose(); }
            }, config);
        } finally { BoardGeometry.tune(original); }
        if (failure.get() != null) { throw new AssertionError("Async terrain renderer benchmark", failure.get()); }
    }

    private static void report(String label, List<Double> values) {
        values.sort(Double::compare);
        System.out.printf("ASYNC %s median=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms%n", label,
              values.get(values.size() / 2), values.get((int) (values.size() * .95)),
              values.get((int) (values.size() * .99)), values.getLast());
    }
}
