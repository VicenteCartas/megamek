/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The window's render resolution and SMAA through the real composite: FSR 1's scale-up of the scene, the hardware
 * depth that overlays test against, SMAA confined to jagged edges, and a resize; then a real board at each setting,
 * and, as a benchmark, the GPU time of the composite, SMAA and FSR.
 */
@Tag("on-demand")
class GpuRenderResolutionSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    /** Linear radiances of the synthetic scenes; their display values differ by far more than SMAA's threshold. */
    private static final float DARK = .02f, BRIGHT = .5f;
    /** Display codes a value must clear on both sides to lie strictly between two others. */
    private static final int MARGIN = 3;
    private static final int UNWRITTEN = 0xFF00FF;

    @Test
    void compositeScalesTheSceneUpAndKeepsTheOverlayDepth() {
        nativeCheck(() -> {
            try (var probe = new Probe()) {
                Frame dark = probe.frame(1, false, uniform(DARK));
                Frame bright = probe.frame(1, false, uniform(BRIGHT));
                Frame half = probe.frame(.5f, false, step());
                Frame oneToOne = probe.frame(1, false, step());
                int row = dark.height / 2, halfBlends = 0, oneToOneBlends = 0;
                for (int x = 2; x < dark.width - 2; x++) {
                    if (between(half, dark, bright, x, row)) { halfBlends++; }
                    if (between(oneToOne, dark, bright, x, row)) { oneToOneBlends++; }
                }
                assertTrue(halfBlends > 0, "50% upscales across the step");
                assertEquals(0, oneToOneBlends, "100% reads the scene 1:1, as before: the step stays hard");
                for (float scale : new float[] { .5f, 1 }) {
                    for (boolean smaa : new boolean[] { false, true }) {
                        assertOverlayDepth(probe.frame(scale, smaa, step()), scale + (smaa ? " with SMAA" : ""));
                    }
                }
                // A resize, as a display mode switch makes: the scene-sized and SMAA targets follow the back buffer.
                Gdx.graphics.setWindowedMode(1100, 700);
                probe.fitWindow();
                Frame resized = probe.frame(.5f, true, step());
                assertEquals(Gdx.graphics.getBackBufferWidth(), resized.width);
                assertOverlayDepth(resized, "resized");
            }
        });
    }

    @Test
    void smaaBlendsOnlyTheJaggedEdge() {
        nativeCheck(() -> {
            try (var probe = new Probe()) {
                Frame dark = probe.frame(1, false, uniform(DARK));
                Frame bright = probe.frame(1, false, uniform(BRIGHT));
                Frame off = probe.frame(1, false, staircase());
                Frame on = probe.frame(1, true, staircase());
                int changed = 0, blended = 0;
                for (int y = 0; y < off.height; y++) {
                    for (int x = 0; x < off.width; x++) {
                        if (between(off, dark, bright, x, y)) { fail("Without SMAA the edge is hard at " + x + ", " + y); }
                        if (off.rgb(x, y) == on.rgb(x, y)) { continue; }
                        changed++;
                        if (Math.abs(y - staircaseEdge(x, off.width, off.height)) > 2) {
                            fail("SMAA changes nothing away from the edge: " + x + ", " + y);
                        }
                        int value = on.green(x, y);
                        if (value < dark.green(x, y) - 1 || value > bright.green(x, y) + 1) {
                            fail("SMAA blends between the two sides at " + x + ", " + y + ": " + value);
                        }
                        if (between(on, dark, bright, x, y)) { blended++; }
                    }
                }
                assertTrue(blended > off.width / 4, "SMAA smooths the long jagged edge: " + blended + " of " + changed);
            }
        });
    }

    /**
     * The real board, framed close, at 50 and 100% with and without SMAA; the tuning shows each scene size and the
     * window's display mode, and Defaults leaves anti-aliasing alone.
     */
    @Test
    void realBoardRendersAtEachResolutionAndAntiAliasing() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 800);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.center(BoardGeometry.center(new Coords(5, 5), 0));
                        view.boardCamera.zoom(.3f);
                        Slider resolution = GpuBoardTestUi.tuning(view, "tuning-render-resolution");
                        SelectBox<String> antiAliasing = GpuBoardTestUi.tuning(view, "tuning-anti-aliasing");
                        Label size = GpuBoardTestUi.tuning(view, "tuning-render-size");
                        SelectBox<String> displayMode = GpuBoardTestUi.tuning(view, "tuning-display-mode");
                        assertEquals("Windowed", displayMode.getSelected(), "The display mode shows the window's");
                        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                        for (int percent : new int[] { 50, 100 }) {
                            for (boolean smaa : new boolean[] { false, true }) {
                                resolution.setValue(percent);
                                antiAliasing.setSelectedIndex(smaa ? 1 : 0);
                                GpuBoardTestUi.present(view);
                                view.render();
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                                assertEquals(String.format(Locale.ROOT, "Scene %d x %d px (window %d x %d)",
                                      Math.round(width * percent / 100f), Math.round(height * percent / 100f), width, height),
                                      size.getText().toString(), "The resulting pixel size");
                                GpuBoardTestUi.capture(new File(OUTPUT,
                                      "render-resolution-" + percent + (smaa ? "-smaa" : "-off") + ".png"));
                            }
                        }
                        resolution.setValue(50);
                        GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(view, "tuning-defaults"));
                        assertEquals(50, resolution.getValue(), "Defaults leaves the render resolution alone");
                        assertEquals(.5f, BoardCamera.renderScale);
                        assertEquals(1, antiAliasing.getSelectedIndex(), "and anti-aliasing");
                        assertEquals("Windowed", displayMode.getSelected(), "and the display mode");
                        // The choices are saved: leave 100% and Off behind for the tests that follow in this process.
                        resolution.setValue(100);
                        antiAliasing.setSelectedIndex(0);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        view.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Render resolution on the real board", failure.get()); }
    }

    /**
     * GPU timestamps of the composite stage, which includes SMAA's and FSR's passes, on the real board's fitted overview:
     * each render resolution with and without SMAA, alternated in one process (forward then backward rounds). Knobs:
     * megamek.gpu.renderResolutionWidth and megamek.gpu.renderResolutionHeight (1920 x 1080) and
     * megamek.gpu.renderResolutionPercents (50,100).
     */
    @Test
    @Tag("gpu-benchmark")
    void compositeAndSmaaTimings() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder report = new StringBuilder();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(Integer.getInteger("megamek.gpu.renderResolutionWidth", 1920),
                  Integer.getInteger("megamek.gpu.renderResolutionHeight", 1080));
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var timings = new AtomicReference<GpuStageTimings>();
                    var variant = new AtomicReference<String>();
                    var view = new GpuBattleView(fixture.source) {
                        @Override
                        void renderStage(String stage) {
                            if (timings.get() != null) { timings.get().stage(stage == null ? null : variant.get() + ": " + stage); }
                        }
                    };
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.fit(fixture.source.takeFrame().scene());
                        Slider resolution = GpuBoardTestUi.tuning(view, "tuning-render-resolution");
                        SelectBox<String> antiAliasing = GpuBoardTestUi.tuning(view, "tuning-anti-aliasing");
                        List<String> order = Arrays.stream(System.getProperty("megamek.gpu.renderResolutionPercents",
                              "50,100").split(",")).flatMap(percent -> Stream.of(percent + " off", percent + " SMAA"))
                              .toList();
                        timings.set(new GpuStageTimings());
                        for (int round = 0; round < 4; round++) {
                            for (int index = 0; index < order.size(); index++) {
                                String next = order.get(round % 2 == 0 ? index : order.size() - 1 - index);
                                resolution.setValue(Integer.parseInt(next.split(" ")[0]));
                                antiAliasing.setSelectedIndex(next.endsWith("SMAA") ? 1 : 0);
                                variant.set(next);
                                GpuBoardTestUi.present(view);
                                for (int frame = 0; frame < 80; frame++) {
                                    timings.get().beginFrame(frame >= 20);
                                    view.render();
                                    Gdx.gl.glFinish();
                                }
                            }
                        }
                        report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; window ")
                              .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                              .append(Gdx.graphics.getBackBufferHeight()).append('\n');
                        timings.get().appendReport(report, "Fitted isometric 16x17 board, 4 rounds of 60 frames each");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(view, "tuning-defaults"));
                        antiAliasing.setSelectedIndex(0);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (timings.get() != null) { timings.get().close(); }
                        view.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Composite and SMAA timings", failure.get()); }
        System.out.println(report);
        Files.writeString(new File(OUTPUT, "render-resolution-timings.txt").toPath(), report);
    }

    /** Whether the frame's pixel lies strictly between the dark and the bright reference frames' pixels. */
    private static boolean between(Frame frame, Frame dark, Frame bright, int x, int y) {
        int value = frame.green(x, y);
        return value > dark.green(x, y) + MARGIN && value < bright.green(x, y) - MARGIN;
    }

    /** The left half of {@link #step} lies at depth .25 and the right half at .75: the window holds the same. */
    private static void assertOverlayDepth(Frame frame, String label) {
        int row = frame.height / 2;
        assertEquals(.25f, frame.depth(frame.width / 4, row), 1e-5f, label + ": left depth");
        assertEquals(.75f, frame.depth(frame.width * 3 / 4, row), 1e-5f, label + ": right depth");
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                if (frame.rgb(x, y) == UNWRITTEN) { fail(label + ": every window pixel is written, not " + x + ", " + y); }
            }
        }
    }

    /** The scene's content: radiance and depth of the bound scene-sized target, {@code width} by {@code height}. */
    private interface Content {
        void draw(int width, int height);
    }

    private static Content uniform(float radiance) {
        return (width, height) -> {
            fill(0, 0, width, height, radiance);
            depth(0, 0, width, height, .5f);
        };
    }

    /** A dark left and a bright right half, at depths .25 and .75. */
    private static Content step() {
        return (width, height) -> {
            fill(0, 0, width / 2, height, DARK);
            fill(width / 2, 0, width - width / 2, height, BRIGHT);
            depth(0, 0, width / 2, height, .25f);
            depth(width / 2, 0, width - width / 2, height, .75f);
        };
    }

    /** Bright below a shallow edge rising one row every eight columns: long, aliased steps. */
    private static Content staircase() {
        return (width, height) -> {
            fill(0, 0, width, height, DARK);
            for (int x = 0; x < width; x++) { fill(x, 0, 1, staircaseEdge(x, width, height), BRIGHT); }
            depth(0, 0, width, height, .5f);
        };
    }

    /** The first dark row of {@link #staircase}'s column {@code x}. */
    private static int staircaseEdge(int x, int width, int height) {
        return height / 2 + Math.floorDiv(x - width / 2, 8);
    }

    private static void fill(int x, int y, int width, int height, float radiance) {
        float stored = radiance * GpuSceneRadiance.SCALE;
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(x, y, width, height);
        Gdx.gl.glClearColor(stored, stored, stored, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private static void depth(int x, int y, int width, int height, float depth) {
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(x, y, width, height);
        Gdx.gl.glClearDepthf(depth);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glClearDepthf(1);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    /** The window's 8-bit color and its depth after a frame, rows from the bottom. */
    private record Frame(int width, int height, int[] colors, float[] depths) {
        static Frame read() {
            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
            var color = BufferUtils.newByteBuffer(width * height * 4);
            var depth = BufferUtils.newFloatBuffer(width * height);
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, color);
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
            int[] colors = new int[width * height];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = (color.get(i * 4) & 255) << 16 | (color.get(i * 4 + 1) & 255) << 8 | color.get(i * 4 + 2) & 255;
            }
            float[] depths = new float[width * height];
            depth.get(depths);
            return new Frame(width, height, colors, depths);
        }

        int rgb(int x, int y) { return colors[y * width + x]; }

        /** The scenes are gray: one channel stands for all three. */
        int green(int x, int y) { return rgb(x, y) >> 8 & 255; }

        float depth(int x, int y) { return depths[y * width + x]; }
    }

    /** The window path of a standalone atmosphere: a camera covering the window and no terrain, fog or weather. */
    private static final class Probe implements AutoCloseable {
        final GpuAtmosphere atmosphere = new GpuAtmosphere();
        final BoardScene scene = new BoardScene(0, 1, 1, List.of(BoardScenes.newTile(new Coords(0, 0), 0, -1,
              false, 0, BoardScene.Surface.GRASS, List.of(), List.of())),
              List.of(), List.of(), -1, "", List.of());
        final OrthographicCamera camera = new OrthographicCamera();

        Probe() {
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1));
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0));
            fitWindow();
        }

        /** As the board's camera, this one's viewport is the window: the composite's display size. */
        void fitWindow() {
            camera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.position.z = 2;
            camera.near = .1f;
            camera.far = 10;
            camera.update();
        }

        Frame frame(float scale, boolean smaa, Content content) {
            atmosphere.setAntiAliasing(smaa);
            FrameBuffer.unbind();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            ScreenUtils.clear(1, 0, 1, 1, true);
            atmosphere.begin((int) camera.viewportWidth, (int) camera.viewportHeight, scale, 0);
            var viewport = BufferUtils.newIntBuffer(4);
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            assertEquals(GpuAtmosphere.scaled(Gdx.graphics.getBackBufferWidth(), scale), viewport.get(2),
                  "The scene renders at the render resolution");
            content.draw(viewport.get(2), viewport.get(3));
            atmosphere.end(camera, null, scene, 0, null, false);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            return Frame.read();
        }

        @Override
        public void close() { atmosphere.dispose(); }
    }

    private static void nativeCheck(Runnable check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        config.useVsync(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Render resolution and SMAA composite", failure.get()); }
    }
}
