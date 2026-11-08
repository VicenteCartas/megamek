/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Full battle-view terrain workload, including cold cover, travel, units, shadows, atmosphere and UI. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuTerrainBenchmarkSmokeTest {
    private static final int SIZE = 32;
    private static final int WARMUP = 24;
    private static final int SAMPLES = 40;
    private static final int FRAMES_PER_MODE = WARMUP + SAMPLES + 1;
    private record Mode(String name, boolean isometric, float zoom, boolean moving) { }
    private static final List<Mode> MODES = List.of(
          new Mode("iso-medium-steady", true, .58f, false),
          new Mode("top-medium-steady", false, .58f, false),
          new Mode("iso-close-steady", true, .28f, false),
          new Mode("top-close-steady", false, .28f, false),
          new Mode("iso-medium-pan", true, .58f, true),
          new Mode("iso-close-pan", true, .28f, true));

    @Test
    void measuresFullViewTerrainAtRestAndWhileTravelling() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.terrainBenchmarkOutput",
              new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                    "terrain-benchmark-" + System.currentTimeMillis()).getPath()));
        Files.createDirectories(output.toPath());
        StringBuilder report = new StringBuilder("32x32 production board; grass, sand, woods, terraces and river; 12 Atlas units.\n")
              .append("1440x1080; fixed noon; VSync off; Swing capture timer stopped after the fixture snapshot.\n")
              .append("Per mode: 24 warmup + 40 measured frames + one separate GLProfiler frame.\n")
              .append("Times include render submission and glFinish, excluding window/event-loop/VSync waits.\n")
              .append("Pan warms at its starting point, then crosses 12 columns in 40 frames; cache misses remain measured.\n")
              .append("GPU stage timestamps overlap CPU work and include command-stream idle time; do not sum CPU and GPU.\n");
        StringBuilder stages = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Board board = board();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (int i = 1; i < 12; i++) {
                        var entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
                        entity.setId(i + 1);
                        entity.setOwner(fixture.player);
                        int x = 5 + (i % 4) * 6, y = 7 + (i / 4) * 7;
                        while (board.getHex(x, y).containsTerrain(Terrains.WATER)) { x++; }
                        entity.setPosition(new Coords(x, y));
                        entity.setFacing(i % 6);
                        entity.setDeployed(true);
                        fixture.game.addEntity(entity, false);
                    }
                    fixture.source.refresh();
                    ((Timer) GpuTestSupport.field(fixture.source, "timer")).stop();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1440, 1080);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final double[] warmup = new double[WARMUP];
                final double[] measured = new double[SAMPLES];
                GLProfiler profiler;
                GpuStageTimings timings;
                int frame;
                boolean measuringStages;

                @Override
                boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) { return true; }

                @Override
                void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                public void create() {
                    super.create();
                    profiler = new GLProfiler(Gdx.graphics);
                    timings = new GpuStageTimings();
                    boardCamera.animateOnSelectionChange = false;
                    boardCamera.animateCombatPlayback = false;
                    boardCamera.animateOnMove = false;
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER))
                          .append("\nGL: ").append(Gdx.gl.glGetString(GL20.GL_VERSION))
                          .append("\nCPU: ").append(System.getenv("PROCESSOR_IDENTIFIER")).append('\n');
                }

                @Override
                void renderStage(String stage) {
                    if (measuringStages) { timings.stage(stage); }
                }

                @Override
                public void render() {
                    try {
                        if (GpuBoardTestUi.loading(this)) { super.render(); return; }
                        if (frame < 2) {
                            long start = System.nanoTime();
                            super.render();
                            Gdx.gl.glFinish();
                            report.append(String.format(Locale.ROOT, "startupFrame%dMs=%.3f%n", frame,
                                  (System.nanoTime() - start) / 1e6));
                            GpuBoardTestUi.<Slider>tuning(this, "Time of day").setValue(12);
                            frame++;
                            return;
                        }
                        int sampleFrame = frame - 2;
                        int modeIndex = sampleFrame / FRAMES_PER_MODE;
                        int phase = sampleFrame % FRAMES_PER_MODE;
                        Mode mode = MODES.get(modeIndex);
                        if (phase == 0) {
                            boardCamera.setIsometric(mode.isometric());
                            boardCamera.camera.zoom = mode.zoom();
                            boardCamera.center(BoardGeometry.center(new Coords(mode.moving() ? 7 : 15, 16), 3));
                        }
                        if (mode.moving() && phase >= WARMUP) {
                            float progress = Math.min(1, (phase - WARMUP) / (float) (SAMPLES - 1));
                            var start = BoardGeometry.center(new Coords(7, 16), 3);
                            var end = BoardGeometry.center(new Coords(19, 19), 3);
                            boardCamera.center(start.lerp(end, progress));
                        }
                        boolean profile = phase == WARMUP + SAMPLES;
                        if (profile) { profiler.reset(); profiler.enable(); }
                        measuringStages = !profile;
                        if (measuringStages) { timings.beginFrame(); }
                        long start = System.nanoTime();
                        super.render();
                        Gdx.gl.glFinish();
                        double elapsed = (System.nanoTime() - start) / 1e6;
                        measuringStages = false;
                        if (phase < WARMUP) { warmup[phase] = elapsed; }
                        else if (!profile) { measured[phase - WARMUP] = elapsed; }
                        if (phase == WARMUP - 1) { timings.appendReport(stages, mode.name() + ": warmup"); }
                        if (profile) {
                            int draws = profiler.getDrawCalls(), switches = profiler.getShaderSwitches();
                            float vertices = profiler.getVertexCount().total;
                            profiler.disable();
                            timings.appendReport(stages, mode.name() + ": measured");
                            report.append(String.format(Locale.ROOT,
                                  "%s,warmMedianMs=%.3f,warmP95Ms=%.3f,medianMs=%.3f,p95Ms=%.3f,draws=%d,shaderSwitches=%d,vertices=%.0f%n",
                                  mode.name(), percentile(warmup, .5), percentile(warmup, .95),
                                  percentile(measured, .5), percentile(measured, .95), draws, switches, vertices));
                            GpuBoardTestUi.capture(new File(output, mode.name() + ".png"));
                            Files.writeString(output.toPath().resolve("frames.txt"), report);
                            Files.writeString(output.toPath().resolve("stages.txt"), stages);
                            if (modeIndex == MODES.size() - 1) { Gdx.app.exit(); }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        frame++;
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        if (profiler != null) { profiler.disable(); }
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    try {
                        if (profiler != null) { profiler.disable(); }
                        if (timings != null) { timings.close(); }
                        super.dispose();
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Full terrain benchmark failed", failure.get()); }
        System.out.println("Terrain benchmark: " + output.getAbsolutePath());
        System.out.print(report);
    }

    private static double percentile(double[] samples, double fraction) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * fraction))];
    }

    private static Board board() {
        Hex[] hexes = new Hex[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            int channel = 15 + (int) Math.round(Math.sin(y * .31) * 2);
            for (int x = 0; x < SIZE; x++) {
                int level = Math.max(0, (int) Math.floor(3.8 + 2.2 * Math.sin(x * .29)
                      + 1.3 * Math.cos(y * .27) + .8 * Math.sin((x + y) * .36)));
                boolean water = Math.abs(x - channel) <= 1;
                Hex hex = new Hex(water ? 0 : level);
                if (water) {
                    hex.addTerrain(new Terrain(Terrains.WATER, x == channel ? 2 : 1));
                } else if (x >= 20 || x > 8 && y > 24) {
                    hex.setTheme("desert");
                    hex.addTerrain(new Terrain(Terrains.SAND, 1));
                } else if (x < 13 && Math.floorMod(x * 7 + y * 11, 17) < 3) {
                    hex.addTerrain(new Terrain(Terrains.WOODS, 1));
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                hexes[y * SIZE + x] = hex;
            }
        }
        return new Board(SIZE, SIZE, hexes);
    }
}
