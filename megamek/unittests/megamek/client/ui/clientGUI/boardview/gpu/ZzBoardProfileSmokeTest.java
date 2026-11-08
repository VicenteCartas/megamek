/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL15;

/** Scratch: GPU time per render stage and per shader program on a saved board, in the real battle view. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class ZzBoardProfileSmokeTest {
    private static final int WARMUP = Integer.getInteger("megamek.gpu.profileWarmup", 60);
    private static final int SAMPLES = Integer.getInteger("megamek.gpu.profileSamples", 30);
    private static final boolean PROGRAMS = !"false".equals(System.getProperty("megamek.gpu.profilePrograms"));
    /** Count samples passed (pixels drawn, in millions a frame) per stage and program instead of GPU time. */
    private static final boolean COVERAGE = Boolean.getBoolean("megamek.gpu.profileCoverage");
    /** name:iso|top:zoom factor of the fitted zoom (1 = fit)[:pan|:refit]; pan moves the view 1.5 m a frame, refit
     * redraws the whole shadow map every frame. */
    private static final String MODES = System.getProperty("megamek.gpu.profileModes",
          "fit:iso:1,medium:iso:.4,close:iso:.18");

    private record Mode(String name, boolean isometric, float factor, String motion) { }

    /** Timestamps at every program switch and stage boundary; one frame at a time, read after its glFinish. */
    static final class Tracer implements InvocationHandler {
        final GL30 raw;
        final int[] queries = new int[16384];
        final String[] stages = new String[queries.length];
        final int[] programs = new int[queries.length];
        int used, program, overflow;
        String stage = "frame start";
        boolean active, open;
        final Map<String, double[]> sums = new LinkedHashMap<>();
        int frames;

        Tracer(GL30 raw) {
            this.raw = raw;
            for (int i = 0; i < queries.length; i++) { queries[i] = GL15.glGenQueries(); }
        }

        void mark() {
            if (!active) { return; }
            if (used == queries.length) { overflow++; return; }
            if (COVERAGE) {
                if (open) { GL15.glEndQuery(GL15.GL_SAMPLES_PASSED); }
                GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, queries[used]);
                open = true;
            } else {
                ARBTimerQuery.glQueryCounter(queries[used], ARBTimerQuery.GL_TIMESTAMP);
            }
            stages[used] = stage;
            programs[used] = program;
            used++;
        }

        void begin() { used = 0; active = true; stage = "frame start"; mark(); }

        void stage(String name) {
            stage = name == null ? "end" : name;
            mark();
        }

        /** After glFinish: every query is available. */
        void end() {
            if (COVERAGE) {
                if (open) { GL15.glEndQuery(GL15.GL_SAMPLES_PASSED); }
                open = active = false;
                for (int i = 0; i < used; i++) {
                    long samples = ARBTimerQuery.glGetQueryObjectui64(queries[i], GL15.GL_QUERY_RESULT);
                    String key = stages[i] + "	" + (PROGRAMS ? programs[i] : 0);
                    sums.computeIfAbsent(key, ignored -> new double[2])[0] += samples / 1e6;
                }
                frames++;
                return;
            }
            mark();
            active = false;
            long previous = ARBTimerQuery.glGetQueryObjectui64(queries[0], GL15.GL_QUERY_RESULT);
            for (int i = 0; i < used - 1; i++) {
                long next = ARBTimerQuery.glGetQueryObjectui64(queries[i + 1], GL15.GL_QUERY_RESULT);
                String key = stages[i] + "\t" + (PROGRAMS ? programs[i] : 0);
                sums.computeIfAbsent(key, ignored -> new double[2])[0] += (next - previous) / 1e6;
                previous = next;
            }
            frames++;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (PROGRAMS && args != null && args.length == 1 && method.getName().equals("glUseProgram")) {
                int next = (int) args[0];
                if (next != program) {
                    program = next;
                    mark();
                }
            }
            try { return method.invoke(raw, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    @Test
    void profilesSavedBoard() throws Exception {
        String path = System.getProperty("megamek.gpu.profileBoard");
        String[] size = System.getProperty("megamek.gpu.profileSize", "3840x2160").split("x");
        int width = Integer.parseInt(size[0]), height = Integer.parseInt(size[1]);
        File output = new File(System.getProperty("megamek.gpu.profileOutput", "build/zz-profile"));
        Files.createDirectories(output.toPath());
        List<Mode> modes = new ArrayList<>();
        for (String mode : MODES.split(",")) {
            String[] parts = mode.split(":");
            modes.add(new Mode(parts[0], parts[1].equals("iso"), Float.parseFloat(parts[2]), parts.length > 3 ? parts[3] : ""));
        }
        GUIPreferences preferences = GUIPreferences.getInstance();
        // The switches below only count in High.
        preferences.setBoardQuality("HIGH");
        preferences.setBoardAmbientOcclusion(Boolean.getBoolean("megamek.gpu.profileAo"));
        preferences.setBoardTessellation(Boolean.getBoolean("megamek.gpu.profileTessellation"));
        Board board = BoardFile.loadSheet(new File(path), false, true);
        StringBuilder report = new StringBuilder("board=" + path + " window=" + width + "x" + height
              + " ao=" + preferences.getBoardAmbientOcclusion() + " tessellation=" + preferences.getBoardTessellation()
              + " programs=" + PROGRAMS + "\n");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                try { ((Timer) GpuTestSupport.field(fixture.source, "timer")).stop(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(width, height);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                Tracer tracer;
                int frame;
                float fitZoom;
                final double[] wall = new double[SAMPLES];

                @Override
                boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) { return true; }

                @Override
                void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                public void create() {
                    GL30 raw = Gdx.gl30;
                    tracer = new Tracer(raw);
                    GL30 traced = (GL30) Proxy.newProxyInstance(GL30.class.getClassLoader(), new Class<?>[] { GL30.class },
                          tracer);
                    Gdx.graphics.setGL30(traced);
                    Gdx.graphics.setGL20(traced);
                    Gdx.gl = Gdx.gl20 = Gdx.gl30 = traced;
                    super.create();
                    boardCamera.animateOnSelectionChange = false;
                    boardCamera.animateCombatPlayback = false;
                    boardCamera.animateOnMove = false;
                    report.append("renderer=").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
                }

                @Override
                void renderStage(String stage) { tracer.stage(stage); }

                @Override
                public void render() {
                    try {
                        if (GpuBoardTestUi.loading(this)) { super.render(); return; }
                        if (frame < 2) {
                            super.render();
                            GpuBoardTestUi.<Slider>tuning(this, "Time of day").setValue(12);
                            if (frame == 1) {
                                Method fit = GpuBattleView.class.getDeclaredMethod("fitBoard");
                                fit.setAccessible(true);
                                fit.invoke(this);
                                fitZoom = boardCamera.camera.zoom;
                            }
                            frame++;
                            return;
                        }
                        int sample = frame - 2;
                        int perMode = WARMUP + SAMPLES;
                        int index = sample / perMode, phase = sample % perMode;
                        Mode mode = modes.get(index);
                        if (phase == 0) {
                            boardCamera.setIsometric(mode.isometric());
                            Method fit = GpuBattleView.class.getDeclaredMethod("fitBoard");
                            fit.setAccessible(true);
                            fit.invoke(this);
                            if (mode.factor() != 1) {
                                boardCamera.camera.zoom = boardCamera.camera.zoom * mode.factor();
                                int x = board.getWidth() / 2, y = board.getHeight() / 2;
                                boardCamera.center(BoardGeometry.center(new Coords(x, y), board.getHex(x, y).getLevel()));
                            }
                        }
                        if (mode.motion().equals("refit")) {
                            ((GpuTerrain) GpuTestSupport.field(this, "terrain")).refreshShadows();
                        }
                        if (mode.motion().equals("pan")) {
                            // Across the board at 1.5 m a frame, from a third of its width: the shadow map refits as it goes.
                            int x = board.getWidth() / 3, y = board.getHeight() / 2;
                            var start = BoardGeometry.center(new Coords(x, y), board.getHex(x, y).getLevel());
                            boardCamera.center(start.add(phase * 1.5f * BoardGeometry.width() / 30, 0, 0));
                        }
                        boolean measured = phase >= WARMUP;
                        if (measured) { tracer.begin(); }
                        long start = System.nanoTime();
                        super.render();
                        Gdx.gl.glFinish();
                        if (measured) {
                            wall[phase - WARMUP] = (System.nanoTime() - start) / 1e6;
                            tracer.end();
                        }
                        if (phase == perMode - 1) {
                            float spacing = BoardGeometry.centerX(new Coords(1, 0)) - BoardGeometry.centerX(new Coords(0, 0));
                            double[] sorted = wall.clone();
                            Arrays.sort(sorted);
                            report.append(String.format(Locale.ROOT,
                                  "%n== %s iso=%s zoom=%.4f fitZoom=%.4f pxPerColumn=%.1f wallMedianMs=%.2f wallP95Ms=%.2f overflow=%d%n",
                                  mode.name(), mode.isometric(), boardCamera.camera.zoom, fitZoom,
                                  spacing / boardCamera.camera.zoom, sorted[SAMPLES / 2], sorted[(int) (SAMPLES * .95)],
                                  tracer.overflow));
                            appendSums(report, tracer, programNames(output));
                            tracer.sums.clear();
                            tracer.frames = 0;
                            GpuBoardTestUi.capture(new File(output, mode.name() + ".png"));
                            Files.writeString(output.toPath().resolve("profile.txt"), report);
                            if (index == modes.size() - 1) { Gdx.app.exit(); }
                        }
                        frame++;
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        Files.writeString(output.toPath().resolve("profile.txt"), report);
        System.out.print(report);
        if (failure.get() != null) { throw new AssertionError("Board profile failed", failure.get()); }
    }

    /** Stage totals, then the programs within each stage, as means per frame. */
    private static void appendSums(StringBuilder report, Tracer tracer, Map<Integer, String> names) {
        Map<String, Double> stages = new LinkedHashMap<>();
        double total = 0;
        for (var entry : tracer.sums.entrySet()) {
            String stage = entry.getKey().split("\t")[0];
            double mean = entry.getValue()[0] / tracer.frames;
            stages.merge(stage, mean, Double::sum);
            total += mean;
        }
        report.append(String.format(Locale.ROOT, COVERAGE ? "megapixelsDrawn=%.2f%n" : "gpuTotalMs=%.2f%n", total));
        stages.forEach((stage, ms) -> report.append(String.format(Locale.ROOT, "  stage %-24s %7.2f%n", stage, ms)));
        if (!PROGRAMS) { return; }
        Map<String, Double> programs = new HashMap<>();
        for (var entry : tracer.sums.entrySet()) {
            String[] key = entry.getKey().split("\t");
            int handle = Integer.parseInt(key[1]);
            String name = key[0] + " | " + names.getOrDefault(handle, "program " + handle);
            programs.merge(name, entry.getValue()[0] / tracer.frames, Double::sum);
        }
        programs.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
              .filter(entry -> entry.getValue() >= (COVERAGE ? .02 : .2))
              .forEach(entry -> report.append(String.format(Locale.ROOT, "  %7.2f  %s%n", entry.getValue(), entry.getKey())));
    }

    /** Program handle to its GpuGlsl source name, matched through the exported sources' ids. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> programNames(File output) throws Exception {
        Map<String, String> exported = new HashMap<>();
        String directory = System.getProperty(GpuGlsl.EXPORT_PROPERTY);
        File[] files = directory == null ? null : new File(directory).listFiles((dir, name) -> name.endsWith(".frag"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 5);
                exported.put(name.substring(name.length() - 36), name.substring(0, name.length() - 37));
            }
        }
        Method sourceId = GpuGlsl.class.getDeclaredMethod("sourceId", String.class, String.class);
        sourceId.setAccessible(true);
        var field = ShaderProgram.class.getDeclaredField("shaders");
        field.setAccessible(true);
        Map<Integer, String> names = new HashMap<>();
        File sources = new File(output, "programs");
        sources.mkdirs();
        for (var entry : (ObjectMap<?, Array<ShaderProgram>>) field.get(null)) {
            for (ShaderProgram program : entry.value) {
                String id = (String) sourceId.invoke(null, program.getVertexShaderSource(), program.getFragmentShaderSource());
                String name = exported.get(id);
                names.put(program.getHandle(), (name == null ? "unnamed" : name) + " #" + program.getHandle());
                if (name == null) {
                    Files.writeString(new File(sources, program.getHandle() + ".frag").toPath(), program.getFragmentShaderSource());
                }
            }
        }
        return names;
    }
}
