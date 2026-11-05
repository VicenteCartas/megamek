/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.utils.Array;
import megamek.client.ui.clientGUI.boardview.gpu.GpuCalibrationScenes.View;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;

/**
 * Step 5's depth pre-pass (GpuTerrain.depthPrepass), timed with everything it costs. Each round of a view draws the
 * settings in a palindrome (off, all, all with LEQUAL, the trees, the trees and the cliff chunks' relief, the default
 * (those and the building shells), all but foliage, all but impostors, then back), so a steady drift of clock or heat cancels; a setting's value in a round is the mean of its two
 * slots, its result the median over the rounds. The opaque stage splits into "opaque preparation" (the frame's detail, uploads and single
 * gather), "depth pre-pass" (the twins) and "colour pass" (the opaque colour draws), beside the frame's other stages for
 * its total. Each board's report also gives its load time with the twins and its colour and twin programs: counts and
 * the driver's binary sizes. Views are the baseline's (GpuDepthPrepassBaselineSmokeTest): the calibration board in rain,
 * the 40 by 40 forest, Dome Vent 2 and the 48 by 48 city at noon; Valencia City's only when named
 * (-Dmegamek.gpu.prepassBoards=city,valencia). Measured, not asserted: build/gpu-board-review/depth-prepass-benchmark.txt,
 * which depth_prepass_abba.py reads. A run can be narrowed: -Dmegamek.gpu.prepassSettings=off,trees (setting names),
 * -Dmegamek.gpu.prepassViews=forest middle,forest close (view names) and -Dmegamek.gpu.prepassRounds=8.
 */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuDepthPrepassBenchmarkSmokeTest {
    static final Set<GpuTerrain.Prepass> ALL = EnumSet.allOf(GpuTerrain.Prepass.class);
    static final Set<GpuTerrain.Prepass> NONE = EnumSet.noneOf(GpuTerrain.Prepass.class);
    private static final File CITY = new File("data/boards/unofficial/VictorMorson/48x48.board");
    private static final File VALENCIA = new File(
          "data/boards/unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board");
    private static final int ROUNDS = Integer.getInteger("megamek.gpu.prepassRounds", 4), SLOT_FRAMES = 30,
          WARM_FRAMES = 8;
    private static final List<Setting> SETTINGS = chosen("megamek.gpu.prepassSettings", List.of(
          new Setting("off", NONE, GL20.GL_EQUAL), new Setting("all", ALL, GL20.GL_EQUAL),
          new Setting("all-lequal", ALL, GL20.GL_LEQUAL),
          new Setting("trees", EnumSet.of(GpuTerrain.Prepass.FOLIAGE, GpuTerrain.Prepass.IMPOSTORS), GL20.GL_EQUAL),
          new Setting("trees-cliffs", EnumSet.of(GpuTerrain.Prepass.CLIFFS, GpuTerrain.Prepass.FOLIAGE,
                GpuTerrain.Prepass.IMPOSTORS), GL20.GL_EQUAL),
          new Setting("default", EnumSet.of(GpuTerrain.Prepass.CLIFFS, GpuTerrain.Prepass.FOLIAGE,
                GpuTerrain.Prepass.IMPOSTORS, GpuTerrain.Prepass.OBJECTS), GL20.GL_EQUAL),
          without(GpuTerrain.Prepass.FOLIAGE), without(GpuTerrain.Prepass.IMPOSTORS)), Setting::name);

    /** A measured setting: the categories the pre-pass draws (none: off) and the colour pass's test for their draws. */
    private record Setting(String name, Set<GpuTerrain.Prepass> categories, int depthFunction) { }

    /** The items a comma-separated property names, all of them without it. */
    private static <T> List<T> chosen(String property, List<T> items, Function<T, String> name) {
        String names = System.getProperty(property, "");
        if (names.isBlank()) { return items; }
        List<String> wanted = List.of(names.split("\\s*,\\s*"));
        return items.stream().filter(item -> wanted.contains(name.apply(item))).toList();
    }

    private static Setting without(GpuTerrain.Prepass category) {
        return new Setting("no-" + category.name().toLowerCase(Locale.ROOT), EnumSet.complementOf(EnumSet.of(category)),
              GL20.GL_EQUAL);
    }

    @Test
    void depthPrepassTimings() throws Exception {
        BoardScene calibration = GpuCalibrationScenes.scene(GpuCalibrationScenes.CALIBRATION);
        BoardScene dome = GpuCalibrationScenes.scene(GpuCalibrationScenes.DOME_VENT);
        BoardScene forest = GpuForestBenchmarkSmokeTest.forest();
        List<String> boards = List.of(System.getProperty("megamek.gpu.prepassBoards", "calibration,forest,dome vent,city")
              .split("\s*,\s*"));
        BoardScene city = boards.contains("city") ? GpuCalibrationScenes.scene(CITY) : null;
        BoardScene valencia = boards.contains("valencia") ? GpuCalibrationScenes.scene(VALENCIA) : null;
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        config.useVsync(false);
        config.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                Set<GpuTerrain.Prepass> previous = GpuTerrain.depthPrepass;
                try {
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; window ")
                          .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                          .append(Gdx.graphics.getBackBufferHeight()).append("; ").append(ROUNDS)
                          .append(" rounds, each setting twice a round for ").append(SLOT_FRAMES)
                          .append(" frames after ").append(WARM_FRAMES).append(" unmeasured; glFinish every frame\n");
                    if (boards.contains("calibration")) {
                        measure("calibration", calibration, GpuCalibrationScenes.RAIN, report,
                              chosen("megamek.gpu.prepassViews", GpuCalibrationScenes.calibrationViews(),
                                    View::name));
                    }
                    if (boards.contains("forest")) {
                        measure("forest", forest, GpuCalibrationScenes.NOON, report,
                              chosen("megamek.gpu.prepassViews", GpuCalibrationScenes.forestViews(forest),
                                    View::name));
                    }
                    if (boards.contains("dome vent")) {
                        measure("dome vent", dome, GpuCalibrationScenes.NOON, report,
                              chosen("megamek.gpu.prepassViews", GpuCalibrationScenes.domeViews(dome),
                                    View::name));
                    }
                    if (city != null) {
                        // The 48 by 48 city, where buildings shade most of the frame (step 5's census).
                        measure("city", city, GpuCalibrationScenes.NOON, report, chosen(
                              "megamek.gpu.prepassViews", List.of(View.fitted("city fitted"),
                                    View.at("city middle", .5f, new Coords(24, 24))), View::name));
                    }
                    if (valencia != null) {
                        // Valencia City's low, spread-out buildings; the middle view at its densest block.
                        measure("valencia", valencia, GpuCalibrationScenes.NOON, report, chosen(
                              "megamek.gpu.prepassViews", List.of(View.fitted("valencia fitted"),
                                    View.at("valencia middle", .5f, new Coords(96, 32))), View::name));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuTerrain.depthPrepass = previous;
                    GpuTerrain.prepassDepthFunction = GL20.GL_EQUAL;
                    BoardCamera.renderScale = 1;
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        Files.writeString(new File(GpuCalibrationScenes.OUTPUT, "depth-prepass-benchmark.txt").toPath(),
              report);
        if (failure.get() != null) { throw new AssertionError("Depth pre-pass benchmark", failure.get()); }
    }

    /** Builds the board once with every category's twins, then times each view's rounds. */
    private static void measure(String board, BoardScene scene, BoardAtmosphere.Settings settings, StringBuilder report,
          List<View> views) throws Exception {
        if (views.isEmpty()) { return; }
        // Twins build behind the loading stage for the categories set then: all of them.
        GpuTerrain.depthPrepass = ALL;
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings);
        try (var timings = new GpuStageTimings()) {
            var camera = new BoardCamera();
            long start = System.nanoTime();
            GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
            List<Setting> order = new ArrayList<>(SETTINGS);
            List<Setting> back = new ArrayList<>(SETTINGS);
            Collections.reverse(back);
            order.addAll(back);
            for (View view : views) {
                GpuTerrain.depthPrepass = ALL;
                GpuTerrain.prepassDepthFunction = GL20.GL_EQUAL;
                GpuCalibrationScenes.show(view, scene, camera);
                GpuCalibrationScenes.settle(terrain, frame, camera, scene);
                frame.render(terrain, camera, scene, timings, view.scale(), () -> terrain.render(camera.camera));
                report.append(String.format(Locale.ROOT, "%s: loaded and settled %.1f s after the build began; %d twin"
                      + " draws a frame%n", view.name(), (System.nanoTime() - start) / 1e9, terrain.prepassDraws()));
                for (int round = 0; round < ROUNDS; round++) {
                    for (Setting setting : order) {
                        GpuTerrain.depthPrepass = setting.categories();
                        GpuTerrain.prepassDepthFunction = setting.depthFunction();
                        for (int frames = 0; frames < WARM_FRAMES + SLOT_FRAMES; frames++) {
                            timings.beginFrame(frames >= WARM_FRAMES);
                            frame.render(terrain, camera, scene, timings, view.scale(), () -> {
                                timings.stage("opaque preparation");
                                terrain.render(camera.camera, () -> timings.stage("depth pre-pass"),
                                      () -> timings.stage("colour pass"));
                            });
                            Gdx.gl.glFinish();
                        }
                        timings.appendReport(report, view.name() + " | " + setting.name() + " | round " + round);
                    }
                }
            }
            report.append(board).append(" programs: ").append(programs(terrain)).append('\n');
        } finally {
            frame.dispose();
            terrain.dispose();
            BoardCamera.renderScale = 1;
        }
    }

    /**
     * The terrain's programs, for the report: colour programs (family programs among them) and twins, with the
     * driver's binary sizes (GL_PROGRAM_BINARY_LENGTH, what the program cache keeps; a proxy for their memory).
     */
    static String programs(GpuTerrain terrain) throws ReflectiveOperationException {
        List<Shader> colour = shaders(((ModelBatch) GpuTestSupport.field(terrain, "batch")).getShaderProvider());
        Object twinProvider = GpuTestSupport.field(terrain, "prepassShaders");
        List<Shader> twins = twinProvider == null ? List.of() : shaders((ShaderProvider) twinProvider);
        return String.format(Locale.ROOT, "%d colour programs, %d of them family programs (%.0f KiB of binaries); %d"
                    + " twins, %d paired with a generic program now (%.0f KiB)", colour.size(),
              terrain.familyPrograms().programs(), binaryKib(colour), twins.size(), terrain.prepassTwins().size(),
              binaryKib(twins));
    }

    /** A ladder's programs: its BaseShaderProvider's, inside a shader manager's handle when there is one. */
    @SuppressWarnings("unchecked")
    private static List<Shader> shaders(ShaderProvider provider) throws ReflectiveOperationException {
        ShaderProvider ladder = provider instanceof GpuShaderProvider handles ? handles.active() : provider;
        Field field = BaseShaderProvider.class.getDeclaredField("shaders");
        field.setAccessible(true);
        List<Shader> result = new ArrayList<>();
        for (Shader shader : (Array<Shader>) field.get(ladder)) { result.add(shader); }
        return result;
    }

    /** Kibibytes of the programs' driver binaries; -1 without OpenGL 4.1. */
    private static double binaryKib(List<Shader> shaders) {
        if (!GL.getCapabilities().OpenGL41) { return -1; }
        long bytes = 0;
        for (Shader shader : shaders) {
            bytes += GL20C.glGetProgrami(((BaseShader) shader).program.getHandle(), GL41C.GL_PROGRAM_BINARY_LENGTH);
        }
        return bytes / 1024.0;
    }
}
