/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Deeply notched plateau tops on the shipped Savannah Box Canyon. */
@Tag("on-demand")
class GpuBoxCanyonSmokeTest {
    @Test
    void rendersConcavePlateausWithoutShadingSpokes() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"), false, true);
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var shaders = new GpuShaderManager();
                try {
                    shaders.run(() -> {
                        try { review(scene, shaders); }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally {
                    shaders.close();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Box Canyon plateau shading", failure.get()); }
    }

    private static void review(BoardScene scene, GpuShaderManager shaders) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER))
                  .append("; 1440x1080; fixed camera; 120 warmup / 360 samples; GPU timestamps; no HUD\n");
            terrain.update(scene);
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            String source = GpuShaderSource.read("terrain-sculpt.frag");
            for (Coords at : List.of(new Coords(13, 1), new Coords(12, 1), new Coords(12, 13))) {
                for (var lod : TerrainLod.values()) {
                    long tops = new BoardSurface(scene, scene.tile(at), lod).faces.stream()
                          .filter(f -> f.finish() == BoardSurface.Finish.TOP).count();
                    report.append(at.getBoardNum()).append(' ').append(lod).append(" top triangles: ").append(tops).append('\n');
                }
                for (boolean iso : new boolean[] { false, true }) {
                    camera.setIsometric(iso);
                    camera.camera.zoom = scene.tile(at).elevation() == 8 ? .055f : .10f;
                    camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    String name = "box-canyon-" + at.getBoardNum() + (iso ? "-iso-" : "-top-");
                    for (String mode : List.of("material", "normal", "contact")) {
                        // The uniform-controlled mix retains the full shader interface during diagnostic reloads.
                        String diagnostic = switch (mode) {
                            case "normal" -> source.replace("fragColor = vec4(result, 1.0);",
                                  "fragColor = vec4(mix(result, face * .5 + .5, step(-.5, u_metre)), 1.0);");
                            case "contact" -> source.replace("fragColor = vec4(result, 1.0);",
                                  "fragColor = vec4(mix(result, vec3(clamp(v_diffuseUV.x / 4.0, 0.0, 1.0)),"
                                        + " step(-.5, u_metre)), 1.0);");
                            default -> source;
                        };
                        var changed = shaders.apply(Map.of("terrain-sculpt.frag", diagnostic));
                        assertTrue(changed.success(), changed.message());
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, name + mode + ".png"));
                        if (mode.equals("material") && Boolean.getBoolean("megamek.gpu.measureBoxCanyon")) {
                            measure(frame, terrain, camera, scene, report, name);
                        }
                    }
                }
            }
            Files.writeString(new File(output, "box-canyon-cost.txt").toPath(), report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
            frame.dispose();
        }
    }

    private static void measure(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          StringBuilder report, String name) {
        try (var timings = new GpuStageTimings()) {
            for (int round = 0; round < 2; round++) {
                for (int i = -120; i < 360; i++) {
                    timings.beginFrame(i >= 0);
                    timings.stage("terrain frame");
                    frame.render(terrain, camera, scene);
                    timings.stage(null);
                    // Pace this create()-time review; the wait lies outside the measured GPU stage.
                    Gdx.gl.glFinish();
                }
                timings.appendReport(report, name + round);
            }
        }
        var profiler = new GLProfiler(Gdx.graphics);
        GL20 raw = Gdx.gl20;
        profiler.enable();
        try {
            frame.render(terrain, camera, scene);
            report.append("draws=").append(profiler.getDrawCalls())
                  .append(" vertices=").append(profiler.getVertexCount().total).append('\n');
        } finally { GpuStageTimings.stopCounting(profiler, raw); }
    }
}
