/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.clientGUI.boardview.gpu.GpuCalibrationScenes.View;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 5's depth pre-pass (GpuTerrain.depthPrepass) draws the same frame bit for bit: the RGBA16F scene colour, its
 * depth and the GL state right after the opaque terrain, and the composited window with its depth, for each category
 * alone, all of them and all with LEQUAL, against the frame without the pre-pass drawn before them and again after them
 * (a control for an unsteady frame). The calibration board in rain (fitted, middle, close, render scale 0.75, combat
 * scars over pre-passed ground), the 40 by 40 forest at noon: canopy cards overlapping with different holes,
 * depth-quantised far away where impostors stand, mipmapped close, oblique in free flight, and the 16 by 17 City
 * (Skyscraper) at noon (fitted, middle, far out where its building shells draw without twins; in the middle view
 * buildings whole, a unit inside, whole again). Also: the colour draws keep
 * their order, the Tactical View and the Classic columns draw no pre-pass, shader edits of the twins fall back safely
 * (checkShaderEdits), and on the stages the driver compiled every twin's vertex stage is its program's, a cutout twin's
 * fragment stage is its program's with the early return, the vertex stage declares the alpha test's inputs invariant,
 * and no solid twin's program can discard or write depth. Programs link cold in a temporary cache: the report
 * (depth-prepass-parity.txt) also gives what the twins add to a load, with the colour and twin programs' counts and
 * binary sizes.
 */
@Tag("on-demand")
class GpuDepthPrepassSmokeTest {
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w+)\\s*(.*)");
    private static final Pattern MACRO = Pattern.compile("(\\w+)(\\([^)]*\\))?\\s*(.*)");
    private static final Pattern TOKEN = Pattern.compile("\\d+(\\.\\d*)?|\\w+|&&|\\|\\||[=!<>]=|[-+*/%()<>!]");
    private static final Pattern DEPTH_EFFECT = Pattern.compile("\\bdiscard\\b|\\bgl_FragDepth\\b");
    /** What terrain-foliage.frag's alpha test reads (foliage-coverage.glsl). */
    private static final List<String> COVERAGE = List.of("v_diffuseUV", "v_color", "v_opacity", "v_alphaTest");
    private static final File SKYSCRAPERS = new File("data/boards/Map Set 6/16x17 City (Skyscraper).board");
    private static final Set<GpuTerrain.Prepass> ALL = GpuDepthPrepassBenchmarkSmokeTest.ALL;
    private static final Set<GpuTerrain.Prepass> NONE = GpuDepthPrepassBenchmarkSmokeTest.NONE;
    private static final List<String> FAILURES = new ArrayList<>();
    @TempDir static Path directory;

    /** A setting the frame without the pre-pass is compared with. */
    private record Setting(String name, Set<GpuTerrain.Prepass> categories, int depthFunction) { }

    private static final int SCENE_STRAYS = 2;

    private static final List<Setting> SETTINGS = List.of(
          new Setting("relief", EnumSet.of(GpuTerrain.Prepass.RELIEF), GL20.GL_LEQUAL),
          new Setting("foliage", EnumSet.of(GpuTerrain.Prepass.FOLIAGE), GL20.GL_LEQUAL),
          new Setting("impostors", EnumSet.of(GpuTerrain.Prepass.IMPOSTORS), GL20.GL_LEQUAL),
          new Setting("objects", EnumSet.of(GpuTerrain.Prepass.OBJECTS), GL20.GL_LEQUAL),
          // The default (GpuTerrain.depthPrepass): the trees, the cliff chunks' relief and the building shells.
          new Setting("default", EnumSet.of(GpuTerrain.Prepass.CLIFFS, GpuTerrain.Prepass.FOLIAGE,
                GpuTerrain.Prepass.IMPOSTORS, GpuTerrain.Prepass.OBJECTS), GL20.GL_LEQUAL),
          new Setting("all", ALL, GL20.GL_LEQUAL), new Setting("all-equal", ALL, GL20.GL_EQUAL));

    /**
     * One frame: the scene target's colour and depth and the GL state right after the opaque terrain, the window's colour
     * and depth after the composite, and the twin draws.
     */
    private record Capture(float[] scene, float[] sceneDepth, List<Integer> state, byte[] window, float[] windowDepth,
          int draws) { }

    @Test
    void depthPrepassDrawsTheSameFrame() throws Exception {
        BoardScene calibration = GpuCalibrationScenes.scene(GpuCalibrationScenes.CALIBRATION);
        BoardScene forest = GpuForestBenchmarkSmokeTest.forest();
        BoardScene city = GpuCalibrationScenes.scene(SKYSCRAPERS);
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuProgramCache cache = GpuGlsl.programCache;
                Set<GpuTerrain.Prepass> previous = GpuTerrain.depthPrepass;
                try {
                    FAILURES.clear();
                    GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("cache"));
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(", ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append("; window ")
                          .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                          .append(Gdx.graphics.getBackBufferHeight()).append('\n');
                    List<View> calibrationViews = GpuCalibrationScenes.calibrationViews();
                    // The calibration board has few trees; its cliff bay's chunks draw in the default pre-pass
                    // (GpuTerrain.cliffWalls).
                    check("calibration", calibration, GpuCalibrationScenes.RAIN, calibrationViews, report,
                          EnumSet.of(GpuTerrain.Prepass.RELIEF, GpuTerrain.Prepass.CLIFFS), true);
                    List<View> forestViews = new ArrayList<>(GpuCalibrationScenes.forestViews(forest));
                    forestViews.add(forestViews.get(1).flying());
                    // Far enough that the trees stand as impostors (TreeLod): no other view reaches them.
                    Coords centre = new Coords(forest.width() / 2, forest.height() / 2);
                    forestViews.add(View.at("forest impostors", 8, centre));
                    check("forest", forest, GpuCalibrationScenes.NOON, forestViews, report, EnumSet.of(
                          GpuTerrain.Prepass.RELIEF, GpuTerrain.Prepass.FOLIAGE, GpuTerrain.Prepass.IMPOSTORS), false);
                    // Far enough that the building shells draw without their twins (TreeLod.PREPASS_OBJECTS).
                    Coords downtown = new Coords(city.width() / 2, city.height() / 2);
                    check("city", city, GpuCalibrationScenes.NOON, List.of(View.fitted("city fitted"),
                                View.at("city middle", .5f, downtown), View.at("city far", 8, downtown)), report,
                          EnumSet.of(GpuTerrain.Prepass.RELIEF, GpuTerrain.Prepass.OBJECTS), false);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    assertTrue(FAILURES.isEmpty(), "Depth pre-pass: " + FAILURES);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    report.append("Failures: ").append(failure.get() == null && FAILURES.isEmpty() ? "none"
                          : FAILURES.isEmpty() ? String.valueOf(failure.get()) : String.join("; ", FAILURES)).append('\n');
                    GpuTerrain.depthPrepass = previous;
                    GpuTerrain.prepassDepthFunction = GL20.GL_LEQUAL;
                    BoardCamera.renderScale = 1;
                    GpuGlsl.programCache = cache;
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.writeString(new File(GpuCalibrationScenes.OUTPUT, "depth-prepass-parity.txt").toPath(),
              report);
        if (failure.get() != null) { throw new AssertionError("Depth pre-pass parity", failure.get()); }
    }

    /**
     * The board loaded without the pre-pass and then with all its twins, which link while the other programs come from
     * the cache; then every view's settings against the frame without the pre-pass, and the board's special cases. Each
     * setting with one of the categories {@code present} must draw a twin in some view.
     */
    private static void check(String board, BoardScene scene, BoardAtmosphere.Settings settings, List<View> views,
          StringBuilder report, Set<GpuTerrain.Prepass> present, boolean calibration) throws Exception {
        var camera = new BoardCamera();
        var frame = new GpuReviewFrame(settings);
        try (var timings = new GpuStageTimings()) {
            GpuTerrain.depthPrepass = NONE;
            var plain = new GpuTerrain();
            try {
                long start = System.nanoTime();
                load(plain, frame, camera, scene, views);
                report.append(String.format(Locale.ROOT, "%s without the pre-pass: loaded in %.1f s%n", board,
                      (System.nanoTime() - start) / 1e9));
            } finally { plain.dispose(); }
            GpuTerrain.depthPrepass = ALL;
            var terrain = new GpuTerrain();
            try {
                int stored = GpuGlsl.programCache == null ? 0 : GpuGlsl.programCache.stored.get();
                long start = System.nanoTime();
                load(terrain, frame, camera, scene, views);
                report.append(String.format(Locale.ROOT, "%s with the pre-pass: loaded in %.1f s, %d programs linked"
                            + " cold (the twins and tree detail levels no view drew before); %s%n", board,
                      (System.nanoTime() - start) / 1e9,
                      GpuGlsl.programCache == null ? -1 : GpuGlsl.programCache.stored.get() - stored,
                      GpuDepthPrepassBenchmarkSmokeTest.programs(terrain)));
                checkSources(board, terrain, report);
                int[] drawn = new int[SETTINGS.size()];
                for (View view : views) {
                    GpuCalibrationScenes.show(view, scene, camera);
                    GpuCalibrationScenes.settle(terrain, frame, camera, scene);
                    compareSettings(board + " " + view.name(), terrain, frame, camera, scene, view, timings, report,
                          drawn);
                }
                View middle = views.get(1);
                if (present.contains(GpuTerrain.Prepass.OBJECTS)) {
                    GpuCalibrationScenes.show(middle, scene, camera);
                    checkShellsOnly(board + " " + middle.name(), terrain, frame, camera, scene, middle, timings, report);
                    checkBuildings(board + " " + middle.name(), terrain, frame, camera, scene, middle, timings, report,
                          drawn);
                }
                for (int i = 0; i < SETTINGS.size(); i++) {
                    // A category that never drew would leave its parity untested.
                    // Only a building without a kit is a shell, and this board's have kits: no shell is no failure.
                    boolean shellsOnly = SETTINGS.get(i).categories().equals(Set.of(GpuTerrain.Prepass.OBJECTS));
                    if (!shellsOnly && !Collections.disjoint(SETTINGS.get(i).categories(), present) && drawn[i] == 0) {
                        FAILURES.add(board + ": the " + SETTINGS.get(i).name() + " setting drew no twin in any view");
                    }
                }
                GpuCalibrationScenes.show(middle, scene, camera);
                checkOrder(board + " " + middle.name(), terrain, frame, camera, scene, middle, timings, report);
                if (calibration) {
                    checkScars(terrain, frame, camera, scene, middle, timings, report);
                }
            } finally { terrain.dispose(); }
            if (calibration) {
                // Built with the pre-pass on: the Classic columns still draw without it.
                GpuTerrain.depthPrepass = ALL;
                var columns = new GpuTerrain();
                try {
                    columns.setColumns(true);
                    load(columns, frame, camera, scene, views.subList(1, 2));
                    checkNoPrepass("calibration Classic columns", columns, frame, camera, scene, views.get(1), timings,
                          report);
                } finally { columns.dispose(); }
                checkShaderEdits(scene, settings, views.get(1), report);
            }
        } finally {
            frame.dispose();
            BoardCamera.renderScale = 1;
        }
    }

    /**
     * The shader editor on the twins, with the editor's own session (GpuShaderManager): an edit of the solid twins'
     * terrain-depth.frag rebuilds the twins and no colour program; until the warm-up pairs the rebuilt twins, their draws
     * draw as before; a rejected edit keeps the working twins and their pairs; restoring the file rebuilds the twins,
     * which draw again once paired. Every frame matches the frame without the pre-pass.
     */
    private static void checkShaderEdits(BoardScene scene, BoardAtmosphere.Settings settings, View view,
          StringBuilder report) {
        var session = new GpuShaderManager();
        try {
            session.run(() -> {
                try { editTwins(session, scene, settings, view, report); }
                catch (Exception error) { throw new IllegalStateException("Shader edits on the twins", error); }
            });
        } finally { session.close(); }
    }

    private static void editTwins(GpuShaderManager session, BoardScene scene, BoardAtmosphere.Settings settings,
          View view, StringBuilder report) throws Exception {
        GpuTerrain.depthPrepass = ALL;
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings);
        var camera = new BoardCamera();
        try (var timings = new GpuStageTimings()) {
            load(terrain, frame, camera, scene, List.of(view));
            var colour = (GpuShaderProvider) ((ModelBatch) GpuTestSupport.field(terrain, "batch"))
                  .getShaderProvider();
            var twins = (GpuShaderProvider) GpuTestSupport.field(terrain, "prepassShaders");
            Object colourLadder = colour.active();
            steady("shader edits", terrain, frame, camera, scene, view, timings, report);
            Capture off = capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings);
            expectTwins("shader edits, before", true, off, terrain, frame, camera, scene, view, timings, report);
            // A directive: the compiled stages drop comments (GpuGlsl.withoutComments).
            String depth = GpuShaderSource.readDisk("terrain-depth.frag"), marker = "#define depthTwinLiveDraft";

            var edit = session.apply(Map.of("terrain-depth.frag", depth + "\n" + marker + "\n"));
            report.append("  edit of terrain-depth.frag: ").append(edit.message()).append('\n');
            if (!edit.success() || edit.updated() == 0) { FAILURES.add("the depth file edit rebuilt no twin"); }
            if (colour.active() != colourLadder) { FAILURES.add("a depth file edit rebuilt the colour programs"); }
            if (twins.sources().stream().noneMatch(source -> source.fragment().contains(marker))) {
                FAILURES.add("no twin compiled the edited depth file");
            }
            // Replaced twins are unpaired: their draws draw as before until the warm-up pairs the replacements.
            expectTwins("shader edits, edited and unpaired", false, off, terrain, frame, camera, scene, view, timings,
                  report);
            GpuCalibrationScenes.settle(terrain, frame, camera, scene);
            expectTwins("shader edits, edited and paired again", true, off, terrain, frame, camera, scene, view, timings,
                  report);

            Map<Shader, Shader> pairs = Map.copyOf(terrain.prepassTwins());
            var rejected = session.apply(Map.of("terrain-depth.frag", "#version 330 core\nnot a shader"));
            report.append("  rejected edit: ").append(rejected.success() ? "accepted" : "rejected").append('\n');
            if (rejected.success()) { FAILURES.add("an invalid depth file was accepted"); }
            if (!pairs.equals(Map.copyOf(terrain.prepassTwins()))) { FAILURES.add("a rejected edit changed the pairs"); }
            expectTwins("shader edits, after a rejected edit", true, off, terrain, frame, camera, scene, view, timings,
                  report);

            var restored = session.apply(Map.of());
            if (!restored.success() || restored.updated() == 0) { FAILURES.add("restoring the depth file rebuilt no twin"); }
            if (twins.sources().stream().anyMatch(source -> source.fragment().contains(marker))) {
                FAILURES.add("a twin kept the draft after the file was restored");
            }
            expectTwins("shader edits, restored and unpaired", false, off, terrain, frame, camera, scene, view, timings,
                  report);
            GpuCalibrationScenes.settle(terrain, frame, camera, scene);
            expectTwins("shader edits, restored and paired again", true, off, terrain, frame, camera, scene, view,
                  timings, report);
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /** One frame with every category on: twin draws or none, as expected, and the frame without the pre-pass. */
    private static void expectTwins(String label, boolean paired, Capture off, GpuTerrain terrain, GpuReviewFrame frame,
          BoardCamera camera, BoardScene scene, View view, GpuStageTimings timings, StringBuilder report) {
        Capture prepassed = capture(terrain, frame, camera, scene, view, ALL, GL20.GL_LEQUAL, timings);
        compare(label + " (" + prepassed.draws() + " twin draws)", off, prepassed, report);
        if ((prepassed.draws() > 0) != paired) {
            FAILURES.add(label + ": " + prepassed.draws() + " twin draws, expected " + (paired ? "some" : "none"));
        }
    }

    /** Builds the board and settles each view, drawing it once, as a game shows a board view by view. */
    private static void load(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          List<View> views) throws InterruptedException {
        GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
        for (View view : views) {
            GpuCalibrationScenes.show(view, scene, camera);
            GpuCalibrationScenes.settle(terrain, frame, camera, scene);
        }
    }

    /** Off, each setting, off again: each setting must match the first frame exactly, the second off frame too. */
    private static void compareSettings(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report, int[] drawn) {
        steady(name, terrain, frame, camera, scene, view, timings, report);
        Capture off = capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings);
        int covered = 0, deep = 0;
        for (int i = 3; i < off.scene().length; i += 4) { if (off.scene()[i] > 0) { covered++; } }
        for (float depth : off.sceneDepth()) { if (depth < 1) { deep++; } }
        report.append(String.format(Locale.ROOT, "%s: %d scene pixels covered, %d with depth%n", name, covered, deep));
        if (covered == 0 || deep == 0) { FAILURES.add(name + ": the view shows no terrain"); }
        GpuReviewFrame.save(new File(GpuCalibrationScenes.OUTPUT,
              "depth-prepass-" + name.replace(' ', '-') + ".png"));
        for (int i = 0; i < SETTINGS.size(); i++) {
            Setting setting = SETTINGS.get(i);
            Capture prepassed = capture(terrain, frame, camera, scene, view, setting.categories(),
                  setting.depthFunction(), timings);
            drawn[i] += prepassed.draws();
            int failures = FAILURES.size();
            compare(name + ", " + setting.name() + " (" + prepassed.draws() + " twin draws)", off, prepassed, report);
            // Equal is reported for comparison: Iris Xe loses a few pixels at it, which is why nothing draws at it.
            if (setting.depthFunction() == GL20.GL_EQUAL) { while (FAILURES.size() > failures) { FAILURES.removeLast(); } }
        }
        // The control: an unsteady frame would void the comparisons above.
        compare(name + ", off again", off, capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings),
              report);
    }

    /**
     * Draws the view without the pre-pass until its window holds still for GpuMeshPage.SETTLE_FRAMES + 2 frames (at most
     * 200): mesh pages merge and the shadow map refits a while after a view settles, which moves a few pixels and would
     * void every comparison of the view.
     */
    private static void steady(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report) {
        GpuTerrain.depthPrepass = NONE;
        byte[] previous = null;
        int still = 0, frames = 0;
        for (; still < GpuMeshPage.SETTLE_FRAMES + 2 && frames < 200; frames++) {
            timings.beginFrame(false);
            frame.render(terrain, camera, scene, timings, view.scale(), () -> terrain.render(camera.camera));
            byte[] next = window();
            still = Arrays.equals(next, previous) ? still + 1 : 0;
            previous = next;
        }
        if (frames > GpuMeshPage.SETTLE_FRAMES + 3) {
            report.append(String.format(Locale.ROOT, "  %s: still after %d frames%n", name, frames));
        }
    }

    /** The window's colour, as bytes. */
    private static byte[] window() {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        ByteBuffer pixels = BufferUtils.newByteBuffer(width * height * 4);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
        byte[] window = new byte[pixels.capacity()];
        pixels.get(window);
        return window;
    }

    /** One frame with the pre-pass setting, read back right after the opaque terrain and after the composite. */
    private static Capture capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          View view, Set<GpuTerrain.Prepass> categories, int depthFunction, GpuStageTimings timings) {
        GpuTerrain.depthPrepass = categories;
        GpuTerrain.prepassDepthFunction = depthFunction;
        float[][] target = new float[2][];
        List<List<Integer>> state = new ArrayList<>();
        timings.beginFrame(false);
        frame.render(terrain, camera, scene, timings, view.scale(), () -> {
            terrain.render(camera.camera);
            state.add(exitState());
            // The scene target is still bound, at its own size.
            IntBuffer viewport = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            target[0] = floats(viewport.get(2), viewport.get(3), GL20.GL_RGBA, 4);
            target[1] = floats(viewport.get(2), viewport.get(3), GL20.GL_DEPTH_COMPONENT, 1);
        });
        byte[] window = window();
        return new Capture(target[0], target[1], state.get(0), window, floats(Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), GL20.GL_DEPTH_COMPONENT, 1), terrain.prepassDraws());
    }

    /** The bound framebuffer's colour (RGBA) or depth, as floats: a half or a 24-bit depth converts exactly. */
    private static float[] floats(int width, int height, int format, int channels) {
        FloatBuffer buffer = BufferUtils.newFloatBuffer(width * height * channels);
        Gdx.gl.glReadPixels(0, 0, width, height, format, GL20.GL_FLOAT, buffer);
        float[] values = new float[buffer.capacity()];
        buffer.get(values);
        return values;
    }

    /**
     * Bit-exact: differing scene pixels, scene depths, window bytes and window depths, with the first places, and the
     * same exit state.
     */
    private static void compare(String label, Capture expected, Capture actual, StringBuilder report) {
        int scene = differences(expected.scene(), actual.scene(), 4), sceneDepth = differences(expected.sceneDepth(),
              actual.sceneDepth(), 1), windowDepth = differences(expected.windowDepth(), actual.windowDepth(), 1);
        int window = 0;
        for (int i = 0; i < expected.window().length; i++) {
            if (expected.window()[i] != actual.window()[i]) { window++; }
        }
        boolean state = expected.state().equals(actual.state());
        report.append(String.format(Locale.ROOT, "  %s: scene pixels %d, scene depths %d, window bytes %d,"
              + " window depths %d differ; exit state %s%n", label, scene, sceneDepth, window, windowDepth,
              state ? "the same" : expected.state() + " -> " + actual.state()));
        // Up to two pixels of the scene's floats may differ on Iris Xe, none of which reaches the window.
        if (scene <= SCENE_STRAYS && sceneDepth + window + windowDepth == 0 && state) { return; }
        FAILURES.add(label);
        if (expected.scene().length != actual.scene().length) { return; }
        int listed = 0;
        for (int i = 0; i < expected.sceneDepth().length && listed < 5; i++) {
            boolean colour = false;
            for (int c = 0; c < 4; c++) {
                colour |= Float.floatToRawIntBits(expected.scene()[i * 4 + c])
                      != Float.floatToRawIntBits(actual.scene()[i * 4 + c]);
            }
            if (!colour && Float.floatToRawIntBits(expected.sceneDepth()[i])
                  == Float.floatToRawIntBits(actual.sceneDepth()[i])) { continue; }
            report.append(String.format(Locale.ROOT, "    scene pixel %d: alpha %.6f -> %.6f, depth %.9f -> %.9f%n", i,
                  expected.scene()[i * 4 + 3], actual.scene()[i * 4 + 3], expected.sceneDepth()[i],
                  actual.sceneDepth()[i]));
            listed++;
        }
    }

    /** The pixels, of {@code channels} values each, in which any value's bits differ. */
    private static int differences(float[] expected, float[] actual, int channels) {
        if (expected.length != actual.length) { return Math.max(expected.length, actual.length) / channels; }
        int count = 0;
        for (int i = 0; i < expected.length; i += channels) {
            for (int c = 0; c < channels; c++) {
                if (Float.floatToRawIntBits(expected[i + c]) != Float.floatToRawIntBits(actual[i + c])) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * What the opaque terrain leaves for the passes after it: depth function, front face, cull face mode, depth and colour
     * write masks, and whether depth testing, blending and culling are on.
     */
    private static List<Integer> exitState() {
        IntBuffer value = BufferUtils.newIntBuffer(16);
        ByteBuffer flags = BufferUtils.newByteBuffer(16);
        List<Integer> state = new ArrayList<>();
        for (int name : new int[] { GL20.GL_DEPTH_FUNC, GL20.GL_FRONT_FACE, GL20.GL_CULL_FACE_MODE }) {
            Gdx.gl.glGetIntegerv(name, value);
            state.add(value.get(0));
        }
        Gdx.gl.glGetBooleanv(GL20.GL_DEPTH_WRITEMASK, flags);
        state.add((int) flags.get(0));
        Gdx.gl.glGetBooleanv(GL20.GL_COLOR_WRITEMASK, flags);
        for (int i = 0; i < 4; i++) { state.add((int) flags.get(i)); }
        for (int capability : new int[] { GL20.GL_DEPTH_TEST, GL20.GL_BLEND, GL20.GL_CULL_FACE }) {
            state.add(Gdx.gl.glIsEnabled(capability) ? 1 : 0);
        }
        return state;
    }

    /** The colour flushes draw the same ranges, programs and materials in the same order with the pre-pass on. */
    private static void checkOrder(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report)
          throws ReflectiveOperationException {
        List<List<Object>> off = order(terrain, frame, camera, scene, view, NONE, timings);
        // Pages build one a frame: the frame without the pre-pass repeats until its order is steady.
        for (int frames = 0; frames < 64; frames++) {
            List<List<Object>> again = order(terrain, frame, camera, scene, view, NONE, timings);
            if (again.equals(off)) { break; }
            off = again;
        }
        List<List<Object>> prepassed = order(terrain, frame, camera, scene, view, ALL, timings);
        report.append(String.format(Locale.ROOT, "%s: %d colour draws, the same order with the pre-pass: %b%n", name,
              off.size(), off.equals(prepassed)));
        if (!off.equals(prepassed)) { FAILURES.add(name + ": the colour draw order changed"); }
    }

    private static List<List<Object>> order(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, Set<GpuTerrain.Prepass> categories, GpuStageTimings timings)
          throws ReflectiveOperationException {
        ModelBatch batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
        Field sorterField = ModelBatch.class.getDeclaredField("sorter");
        sorterField.setAccessible(true);
        RenderableSorter original = batch.getRenderableSorter();
        List<List<Object>> draws = new ArrayList<>();
        sorterField.set(batch, (RenderableSorter) (viewCamera, renderables) -> {
            original.sort(viewCamera, renderables);
            for (Renderable range : renderables) {
                for (Renderable part : GpuTerrainBatch.ranges(range)) {
                    draws.add(List.of(GpuShaderProvider.unwrap(part.shader), part.meshPart.mesh, part.meshPart.offset,
                          part.meshPart.size, part.material));
                }
            }
        });
        try {
            GpuTerrain.depthPrepass = categories;
            timings.beginFrame(false);
            frame.render(terrain, camera, scene, timings, view.scale(), () -> terrain.render(camera.camera));
        } finally { sorterField.set(batch, original); }
        return draws;
    }

    /** Combat scars, drawn coplanar over pre-passed ground after the opaque flush, match too. */
    private static void checkScars(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          View view, GpuStageTimings timings, StringBuilder report) {
        Coords at = GpuCalibrationScenes.FOOT;
        Vector3 center = BoardGeometry.center(at, 0);
        var surface = terrain.tacticalSurface(at);
        int shots = 0;
        for (float x : new float[] { -6, 0, 5 }) {
            Vector3 point = center.cpy().add(BoardRelief.metres(x), BoardRelief.metres(2), 0);
            point.z = surface.top().stream().map(face -> face.height(point.x, point.y)).max(Float::compare)
                  .orElse(Float.NaN);
            if (!Float.isFinite(point.z)) { continue; }
            var shot = GroundDamagePlaybackTest.shot("ISAC5", false, null);
            shot.landscape = ray -> terrain.hit(scene, ray);
            terrain.impact(scene, new GpuGroundDamage.Impact(shot, "prepass-" + shots++,
                  point.cpy().add(-40, 20, 30), point, "missile", null));
        }
        // A frame stamps the marks (GpuGroundDamage.upload).
        warm(terrain, frame, camera, scene);
        report.append(String.format(Locale.ROOT, "calibration scars: %d shots, %d ground marks%n", shots,
              terrain.groundDamage().marks()));
        if (terrain.groundDamage().marks() == 0) { FAILURES.add("calibration scars: no mark landed"); }
        Capture off = capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings);
        compare("calibration scars, all", off, capture(terrain, frame, camera, scene, view, ALL, GL20.GL_LEQUAL, timings),
              report);
        compare("calibration scars, off again", off,
              capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings), report);
    }

    /**
     * Buildings as occupancy fades them (GpuTerrain.animate), at the building of three or more storeys nearest the
     * view's centre: a unit inside, then whole again. A faded building blends and stays out of the pre-pass; every other
     * draw must still match.
     */
    private static void checkBuildings(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report, int[] drawn) throws Exception {
        BoardScene.Tile tile = scene.tiles().stream().filter(candidate -> candidate.features().stream()
                    .anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.BUILDING && feature.height() >= 3))
              .min(Comparator.comparingInt(candidate -> candidate.coords().distance(view.centre()))).orElseThrow();
        Model box = new ModelBuilder().createBox(3, 3, 3, new Material(), VertexAttributes.Usage.Position);
        // Refinement replaces chunks, and with them their fades: the view is settled before any fade.
        GpuCalibrationScenes.settle(terrain, frame, camera, scene);
        try {
            ModelInstance unit = new ModelInstance(box, BoardGeometry.center(tile.coords(), tile.elevation() + 1));
            for (String state : List.of("occupied", "whole again")) {
                terrain.animate(0, state.equals("occupied") ? List.of(unit) : List.of(), GpuTerrain.DEFAULT_BUILDING_OPACITY);
                GpuCalibrationScenes.settle(terrain, frame, camera, scene);
                int faded = 0;
                for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                    faded += ((Set<?>) GpuTestSupport.field(chunk, "faded")).size();
                }
                report.append(String.format(Locale.ROOT, "%s, %s at %s: %d faded props%n", name, state, tile.coords(),
                      faded));
                if ((faded > 0) == state.equals("whole again")) {
                    FAILURES.add(name + ", " + state + ": " + faded + " faded props");
                }
                compareSettings(name + ", " + state, terrain, frame, camera, scene, view, timings, report, drawn);
            }
        } finally { box.dispose(); }
    }

    /**
     * Only building shells take an objects twin, draw by draw (GpuModelMaterial.occluder): in a frame of the view no
     * other model draw has one, even one of a shell's program, and a shell draw's material without the flag,
     * which selects the same program, has none either.
     */
    private static void checkShellsOnly(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report) throws ReflectiveOperationException {
        Object batch = GpuTestSupport.field(terrain, "batch");
        Method gathered = batch.getClass().getDeclaredMethod("gathered");
        Method twin = GpuTerrain.class.getDeclaredMethod("twin", Shader.class, Attributes.class);
        Method prepass = GpuTerrain.class.getDeclaredMethod("prepass", Attributes.class);
        gathered.setAccessible(true);
        twin.setAccessible(true);
        prepass.setAccessible(true);
        // Shell draws with a twin; other model draws whose program has one; the shell draws checked without the flag.
        int[] counts = new int[3];
        GpuTerrain.depthPrepass = ALL;
        timings.beginFrame(false);
        frame.render(terrain, camera, scene, timings, view.scale(), () -> terrain.render(camera.camera, () -> {
            try {
                @SuppressWarnings("unchecked")
                Array<Renderable> parts = (Array<Renderable>) gathered.invoke(batch);
                for (Renderable part : parts) {
                    var surface = part.material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
                    if (surface == null) { continue; }
                    // An object surface the flag alone keeps out: not a plant's, nor blended, cut away or alpha-tested.
                    Material flagged = new Material(part.material);
                    flagged.set(surface.occluder(true));
                    if (prepass.invoke(null, flagged) != GpuTerrain.Prepass.OBJECTS) { continue; }
                    boolean twinned = twin.invoke(terrain, part.shader, part.material) != null;
                    if (twinned && !surface.occluder) {
                        FAILURES.add(name + ": a model draw other than a shell's took a twin: " + part.material.id);
                    } else if (twinned) {
                        counts[0]++;
                        Material plain = new Material(part.material);
                        plain.set(surface.occluder(false));
                        if (twin.invoke(terrain, part.shader, plain) != null) {
                            FAILURES.add(name + ": a shell's material without the flag took a twin: " + part.material.id);
                        }
                        counts[2]++;
                    } else if (!surface.occluder
                          && terrain.prepassTwins().containsKey(GpuShaderProvider.unwrap(part.shader))) {
                        counts[1]++;
                    }
                }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }, null));
        report.append(String.format(Locale.ROOT, "%s: %d shell draws with a twin; %d other model draws share a twinned"
              + " program without one; %d shell materials without the flag take none%n", name, counts[0], counts[1],
              counts[2]));
    }

    /** No twin draws, and the same frame as without the pre-pass, before and after. */
    private static void checkNoPrepass(String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, View view, GpuStageTimings timings, StringBuilder report) {
        Capture off = capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings);
        Capture all = capture(terrain, frame, camera, scene, view, ALL, GL20.GL_LEQUAL, timings);
        compare(name + ", all", off, all, report);
        compare(name + ", off again", off, capture(terrain, frame, camera, scene, view, NONE, GL20.GL_LEQUAL, timings),
              report);
        if (all.draws() != 0) { FAILURES.add(name + ": " + all.draws() + " twin draws"); }
    }

    /** A few frames after a change, so scars, shadows and pages settle before a comparison. */
    private static void warm(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        GpuTerrain.depthPrepass = NONE;
        for (int frames = 0; frames < 4; frames++) { frame.render(terrain, camera, scene); }
    }

    /**
     * The static checks on the stages the driver compiled: a twin's vertex stage is its program's and declares
     * gl_Position invariant; a cutout twin's fragment stage is its program's with the define that returns after the alpha
     * test, whose inputs the vertex stage declares invariant (the fragment stage matches it per GLSL version,
     * coverageIn); a solid twin's program can neither discard nor write depth.
     */
    private static void checkSources(String board, GpuTerrain terrain, StringBuilder report) {
        int solid = 0, cutouts = 0;
        for (Map.Entry<Shader, Shader> entry : terrain.prepassTwins().entrySet()) {
            ShaderProgram colour = ((BaseShader) entry.getKey()).program;
            ShaderProgram twin = ((BaseShader) GpuShaderProvider.unwrap(entry.getValue())).program;
            String name = board + " " + (colour instanceof GpuShaderUniforms named ? named.name : "program");
            String vertex = colour.getVertexShaderSource();
            List<String> vertexLines = activeLines(vertex);
            if (!vertex.equals(twin.getVertexShaderSource())) { FAILURES.add(name + ": the twin's vertex stage differs"); }
            if (!vertexLines.contains("invariant gl_Position;")) { FAILURES.add(name + ": gl_Position is not invariant"); }
            String twinFragment = twin.getFragmentShaderSource();
            if (twinFragment.contains("#define depthPrepassFlag")) {
                cutouts++;
                if (!twinFragment.replace("#define depthPrepassFlag\n", "").equals(colour.getFragmentShaderSource())) {
                    FAILURES.add(name + ": the cutout twin's fragment stage is not its program's");
                }
                List<String> fragmentLines = activeLines(twinFragment);
                for (String input : COVERAGE) {
                    Pattern declaration = Pattern.compile("(?:in|coverageIn)\\s+\\w+\\s+" + input + "\\s*;");
                    for (String line : fragmentLines) {
                        if (declaration.matcher(line).matches() && !vertexLines.contains("invariant " + input + ";")) {
                            FAILURES.add(name + ": " + input + " is not invariant in the vertex stage");
                        }
                    }
                }
            } else {
                solid++;
                for (String source : List.of(colour.getFragmentShaderSource(), twinFragment)) {
                    for (String line : activeLines(source)) {
                        if (DEPTH_EFFECT.matcher(line).find()) {
                            FAILURES.add(name + ": a solid twin's program can discard or write depth: " + line);
                        }
                    }
                }
            }
        }
        report.append(String.format(Locale.ROOT, "%s sources: %d solid twins, %d cutout twins checked%n", board, solid,
              cutouts));
        if (solid == 0) { FAILURES.add(board + ": no solid twin to check"); }
    }

    /** The lines of a complete stage that compile, without comments: enough of the preprocessor for these checks. */
    static List<String> activeLines(String source) {
        String code = COMMENT.matcher(source).replaceAll(match -> match.group().replaceAll("[^\\n]", " "));
        Map<String, String> macros = new HashMap<>();
        // Each open conditional: whether its surroundings compile, and whether one of its branches was taken.
        Deque<boolean[]> open = new ArrayDeque<>();
        boolean active = true;
        List<String> lines = new ArrayList<>();
        for (String raw : code.split("\r?\n")) {
            String line = raw.strip();
            Matcher directive = DIRECTIVE.matcher(line);
            if (!directive.matches()) {
                if (active && !line.isEmpty()) { lines.add(line); }
                continue;
            }
            String keyword = directive.group(1), rest = directive.group(2).strip();
            String first = rest.isEmpty() ? "" : rest.split("\\s+")[0];
            switch (keyword) {
                case "if", "ifdef", "ifndef" -> {
                    boolean taken = active && switch (keyword) {
                        case "ifdef" -> macros.containsKey(first);
                        case "ifndef" -> !macros.containsKey(first);
                        default -> new Expression(rest, macros, 0).value() != 0;
                    };
                    open.push(new boolean[] { active, taken });
                    active = taken;
                }
                case "elif" -> {
                    boolean[] branch = open.peek();
                    active = branch[0] && !branch[1] && new Expression(rest, macros, 0).value() != 0;
                    branch[1] |= active;
                }
                case "else" -> {
                    boolean[] branch = open.peek();
                    active = branch[0] && !branch[1];
                    branch[1] = true;
                }
                case "endif" -> active = open.pop()[0];
                case "define" -> {
                    Matcher macro = MACRO.matcher(rest);
                    if (active && macro.matches()) { macros.put(macro.group(1), macro.group(3).strip()); }
                }
                case "undef" -> { if (active) { macros.remove(first); } }
                case "version" -> macros.put("__VERSION__", first);
                default -> { }
            }
        }
        return lines;
    }

    /** A #if expression: numbers, macros (undefined ones are 0), defined, ! - + * / % < > <= >= == != && || and (). */
    private static final class Expression {
        private final List<String> tokens = new ArrayList<>();
        private final Map<String, String> macros;
        private final int depth;
        private int at;

        Expression(String text, Map<String, String> macros, int depth) {
            this.macros = macros;
            this.depth = depth;
            Matcher token = TOKEN.matcher(text);
            while (token.find()) { tokens.add(token.group()); }
        }

        double value() { return tokens.isEmpty() ? 0 : or(); }

        private boolean accept(String token) {
            if (at < tokens.size() && tokens.get(at).equals(token)) {
                at++;
                return true;
            }
            return false;
        }

        private double or() {
            double value = and();
            while (accept("||")) { value = and() != 0 | value != 0 ? 1 : 0; }
            return value;
        }

        private double and() {
            double value = equality();
            while (accept("&&")) { value = equality() != 0 & value != 0 ? 1 : 0; }
            return value;
        }

        private double equality() {
            double value = relation();
            while (true) {
                if (accept("==")) { value = value == relation() ? 1 : 0; }
                else if (accept("!=")) { value = value != relation() ? 1 : 0; }
                else { return value; }
            }
        }

        private double relation() {
            double value = sum();
            while (true) {
                if (accept("<=")) { value = value <= sum() ? 1 : 0; }
                else if (accept(">=")) { value = value >= sum() ? 1 : 0; }
                else if (accept("<")) { value = value < sum() ? 1 : 0; }
                else if (accept(">")) { value = value > sum() ? 1 : 0; }
                else { return value; }
            }
        }

        private double sum() {
            double value = product();
            while (true) {
                if (accept("+")) { value += product(); }
                else if (accept("-")) { value -= product(); }
                else { return value; }
            }
        }

        private double product() {
            double value = unary();
            while (true) {
                if (accept("*")) { value *= unary(); }
                else if (accept("/")) { value /= unary(); }
                else if (accept("%")) { value %= unary(); }
                else { return value; }
            }
        }

        private double unary() {
            if (accept("!")) { return unary() == 0 ? 1 : 0; }
            if (accept("-")) { return -unary(); }
            if (accept("+")) { return unary(); }
            return primary();
        }

        private double primary() {
            if (at >= tokens.size()) { return 0; }
            String token = tokens.get(at++);
            if (token.equals("(")) {
                double value = or();
                accept(")");
                return value;
            }
            if (token.equals("defined")) {
                boolean parenthesised = accept("(");
                String name = at < tokens.size() ? tokens.get(at++) : "";
                if (parenthesised) { accept(")"); }
                return macros.containsKey(name) ? 1 : 0;
            }
            if (Character.isDigit(token.charAt(0))) { return Double.parseDouble(token); }
            String value = macros.get(token);
            return value == null || value.isEmpty() || depth > 16 ? 0 : new Expression(value, macros, depth + 1).value();
        }
    }

    /** The source checks' preprocessor on the board's own patterns, without a window. */
    @Test
    void preprocessorKeepsOnlyTheCompiledLines() {
        String source = String.join("\n", "#version 460 core", "#define numDirectionalLights 1", "#define blendedFlag",
              "#if defined(blendedFlag) && defined(alphaTestFlag)", "discard;", "#elif numDirectionalLights > 0",
              "lit;", "#else", "dark;", "#endif", "#ifndef alphaTestFlag", "/* discard; */ solid; // discard;",
              "#endif", "#if (defined(lightingFlag) || defined(blendedFlag)) && !defined(vertexDataFlag)", "linear;",
              "#endif");
        assertEquals(List.of("lit;", "solid;", "linear;"), activeLines(source));
        assertEquals(List.of("a;"), activeLines(String.join("\n", "#define X 2", "#if X * 3 == 6 && -X < 0", "a;",
              "#endif", "#if !X", "b;", "#endif")));
    }
}
