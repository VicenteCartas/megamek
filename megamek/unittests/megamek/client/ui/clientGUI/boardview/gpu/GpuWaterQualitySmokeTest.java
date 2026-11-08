/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Repeatable water-only measurements and lit captures of basins, shelves, an island and a connecting narrows. */
@Tag("on-demand")
class GpuWaterQualitySmokeTest {
    @Test
    void capturesAndMeasuresWaterAcrossZoomAndWind() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        int width = Integer.getInteger("megamek.gpu.waterWidth", 1280);
        int height = Integer.getInteger("megamek.gpu.waterHeight", 800);
        config.setWindowedMode(width, height);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var shaders = new GpuShaderManager();
                var snapshot = new HashMap<String, String>();
                var baseline = new HashMap<String, String>();
                var current = new HashMap<String, String>();
                String path = System.getProperty("megamek.gpu.shaderSnapshot");
                try {
                    String comparison = System.getProperty("megamek.gpu.waterBaselineShaderDirectory");
                    if (comparison != null) {
                        assertTrue(path == null, "A/B compares the current water shaders, not another whole snapshot");
                        for (String name : List.of("water-pool.glsl", "water-wave.glsl")) {
                            baseline.put(name, Files.readString(Path.of(comparison, name)));
                            current.put(name, GpuShaderSource.readDisk(name));
                        }
                        assertTrue(!baseline.equals(current), "The saved water shaders must differ from current sources");
                    }
                    if (path != null) {
                        try (var files = Files.list(Path.of(path))) {
                            for (Path file : files.toList()) {
                                if (Files.isRegularFile(file)) { snapshot.put(file.getFileName().toString(), Files.readString(file)); }
                            }
                        }
                    }
                    shaders.run(() -> {
                        if (!snapshot.isEmpty()) { assertTrue(shaders.apply(snapshot).success()); }
                        review(shaders, baseline, current);
                    });
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                finally { shaders.close(); }
            }

            private void review(GpuShaderManager shaders, Map<String, String> baseline, Map<String, String> current) {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                    String expected = System.getProperty("megamek.gpu.expectedRenderer", "");
                    assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)), renderer);
                    var scene = scene(Integer.getInteger("megamek.gpu.waterSize", 32));
                    var initial = new BoardCamera();
                    initial.resize(width, height); initial.setIsometric(true); initial.fit(scene); initial.update();
                    frame.prepare(terrain, initial, scene);
                    long start = System.nanoTime();
                    terrain.update(scene, initial.camera);
                    GpuReviewFrame.settle(terrain, null, scene, initial);
                    System.out.printf("WATER build %.1f ms; GPU %s%n", (System.nanoTime() - start) / 1e6,
                          renderer);
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "quality");
                    if (!output.isDirectory() && !output.mkdirs()) { throw new IllegalStateException("Capture directory"); }
                    if (!baseline.isEmpty()) {
                        Path sources = output.toPath().resolve("shader-ab");
                        assertTrue(Files.notExists(sources), "Keep earlier shader A/B evidence intact");
                        for (var variant : Map.of("A", baseline, "B", current).entrySet()) {
                            Path directory = Files.createDirectories(sources.resolve(variant.getKey()));
                            for (var source : variant.getValue().entrySet()) {
                                Files.writeString(directory.resolve(source.getKey()), source.getValue());
                            }
                        }
                    }
                    int firstView = Integer.getInteger("megamek.gpu.waterFirstView", 0);
                    int views = Integer.getInteger("megamek.gpu.waterViews", 4);
                    assertTrue(firstView >= 0 && views > 0 && firstView + views <= 4, "Select the existing four views");
                    for (int view = firstView; view < firstView + views; view++) {
                        var camera = new BoardCamera();
                        camera.resize(width, height);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        if (view > 0) {
                            camera.camera.zoom *= view == 1 ? .22f : .1f;
                            camera.orbit(0, view == 3 ? 25 : view == 1 ? -15 : -35);
                            if (view == 3) { camera.setPerspective(true); }
                            camera.center(BoardGeometry.center(new Coords(scene.width() / 3, scene.height() / 2), 0));
                        }
                        camera.update();
                        frame.prepare(terrain, camera, scene);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        // Populate spatial pages before measuring; steady camera motion must not rebuild them.
                        for (int i = 0; i < 64; i++) { frame.render(terrain, camera, scene); }
                        for (float wind : new float[] { 0, .6f, 1 }) {
                            frame.configure(wind(wind));
                            frame.prepare(terrain, camera, scene);
                            settleWind(terrain);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "view-" + view + "-wind-" + wind + ".png"));
                        }
                        frame.configure(wind(.6f));
                        frame.prepare(terrain, camera, scene);
                        settleWind(terrain);
                        frame.render(terrain, camera, scene);
                        if (System.getProperty("megamek.gpu.shaderSnapshot") == null) {
                            var field = GpuTerrain.class.getDeclaredField("waterPages"); field.setAccessible(true);
                            var pages = (GpuWaterPages) field.get(terrain);
                            System.out.printf("WATER pages=%d atlasUploads=%d atlasMiB=%.2f maxPageBuildMs=%.3f%n",
                                  pages.rebuilds(), pages.atlasUploads(), pages.atlasBytes() / 1048576.0, pages.maxBuildNanos() / 1e6);
                            byte[] batched = ScreenUtils.getFrameBufferPixels(false);
                            pages.setEnabled(false);
                            frame.render(terrain, camera, scene);
                            byte[] original = ScreenUtils.getFrameBufferPixels(false);
                            pages.setEnabled(true);
                            frame.render(terrain, camera, scene);
                            double difference = 0; int changed = 0;
                            for (int i = 0; i < batched.length; i++) {
                                int delta = Math.abs(Byte.toUnsignedInt(batched[i]) - Byte.toUnsignedInt(original[i]));
                                difference += delta;
                                if (delta > 8) { changed++; }
                            }
                            System.out.printf("WATER atlas parity view=%d mean=%.5f changed=%d%n", view, difference / batched.length, changed);
                            assertTrue(difference / batched.length < .1, "Atlas must preserve water shading");
                            long builds = pages.rebuilds();
                            var oldFocus = camera.focus.cpy();
                            camera.center(BoardGeometry.center(new Coords(scene.width() / 3 + 1, scene.height() / 2), 0));
                            frame.render(terrain, camera, scene);
                            assertEquals(builds, pages.rebuilds(), "A camera pan must only select cached index ranges");
                            camera.center(oldFocus);
                            frame.render(terrain, camera, scene);
                        }
                        if (!baseline.isEmpty()) {
                            compareShaders(shaders, baseline, current, terrain, frame, camera, scene,
                                  output.toPath().resolve("shader-ab"), view);
                            continue;
                        }
                        var raw = Gdx.gl;
                        var profiler = new GLProfiler(Gdx.graphics);
                        profiler.enable();
                        try (var timing = new GpuStageTimings()) {
                            for (int i = 0; i < 150; i++) {
                                Gdx.gl.glFinish();
                                profiler.reset();
                                timing.beginFrame(i >= 30);
                                timing.stage("fft");
                                terrain.animate(1 / 60f, List.of());
                                int simulationDraws = profiler.getDrawCalls();
                                frame.render(terrain, camera, scene, timing);
                                if (i == 149) {
                                    System.out.printf("WATER view=%d frameDraws=%d fftDraws=%d indices=%.0f%n", view,
                                          profiler.getDrawCalls(), simulationDraws, profiler.getVertexCount().total);
                                }
                            }
                            Gdx.gl.glFinish();
                            timing.poll();
                            var report = new StringBuilder();
                            timing.appendReport(report, "water-view-" + view);
                            System.out.println(report);
                        } finally { GpuStageTimings.stopCounting(profiler, raw); }
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "view-" + view + "-later.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water quality review", failure.get()); }
    }

    /** Frozen wave state: compare fragment/vertex shaders without mixing FFT cost or separate-process thermal drift. */
    private static void compareShaders(GpuShaderManager shaders, Map<String, String> baseline, Map<String, String> current,
          GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, Path output, int view)
          throws Exception {
        var report = new StringBuilder("A=saved water-pool/water-wave; B=current; order=ABBA; warmup=30; samples=120\n")
              .append("One terrain/camera and frozen simulation; no animate() calls during these rounds.\n")
              .append("specularAA=").append(GpuEnvironmentLighting.specularAntialiasing)
              .append("; prepass=").append(GpuTerrain.depthPrepass).append('\n');
        Path receipt = output.resolve("view-" + view + "-ab.txt");
        var radiance = new HashMap<String, float[]>();
        var display = new HashMap<String, byte[]>();
        try {
            int round = 0;
            String previous = "B";
            for (String variant : List.of("A", "B", "B", "A")) {
                var applied = shaders.apply(variant.equals("A") ? baseline : current);
                report.append("APPLY round=").append(round).append(" variant=").append(variant)
                      .append(" updated=").append(applied.updated()).append(" success=").append(applied.success()).append('\n');
                Files.writeString(receipt, report);
                assertTrue(applied.success(), applied.message());
                if (!variant.equals(previous)) {
                    assertTrue(applied.updated() > 0, "Switch the actual registered water programs");
                    terrain.shadersChanged();
                }
                previous = variant;
                frame.prepare(terrain, camera, scene);
                GpuReviewFrame.settle(terrain, null, scene, camera);
                try (var timing = new GpuStageTimings()) {
                    for (int i = 0; i < 150; i++) {
                        Gdx.gl.glFinish();
                        timing.beginFrame(i >= 30);
                        frame.render(terrain, camera, scene, timing);
                    }
                    Gdx.gl.glFinish();
                    timing.poll();
                    var measured = new StringBuilder();
                    timing.appendReport(measured, "water-ab-view-" + view + "-round-" + round + "-" + variant);
                    report.append(measured);
                    Files.writeString(receipt, report);
                    System.out.print(measured);
                    assertTrue(measured.toString().contains("GPU timestamps: supported; dropped samples: 0"),
                          "The paired comparison requires actual complete GPU measurements");
                    assertTrue(measured.toString().contains("transparent,120,"), "Every round measures 120 water frames");
                }
                String name = "view-" + view + "-round-" + round + "-" + variant;
                GpuReviewFrame.save(output.resolve(name + ".png").toFile());
                byte[] pixels = ScreenUtils.getFrameBufferPixels(false);
                float[] linear = scenePixels(frame);
                float[] prior = radiance.putIfAbsent(variant, linear);
                byte[] priorDisplay = display.putIfAbsent(variant, pixels);
                if (prior != null) {
                    report.append("REPEAT ").append(variant).append(" rawEqual=").append(Arrays.equals(prior, linear))
                          .append(" displayEqual=").append(Arrays.equals(priorDisplay, pixels)).append('\n');
                    Files.writeString(receipt, report);
                    assertArrayEquals(prior, linear, "Repeated " + variant + " retains identical frozen HDR radiance");
                    assertArrayEquals(priorDisplay, pixels, "Repeated " + variant + " retains identical display pixels");
                }
                round++;
            }
            float[] a = radiance.get("A"), b = radiance.get("B");
            byte[] screenA = display.get("A"), screenB = display.get("B");
            assertEquals(a.length, b.length, "Both shaders render the same HDR target");
            assertEquals(screenA.length, screenB.length, "Both shaders render the same display target");
            double total = 0, displayTotal = 0;
            float maximum = 0;
            int changed = 0, alphaChanged = 0, displayMaximum = 0;
            for (int i = 0; i < a.length; i++) {
                if (i % 4 == 3) {
                    if (a[i] != b[i]) { alphaChanged++; }
                    continue;
                }
                float delta = Math.abs(a[i] - b[i]);
                total += delta;
                maximum = Math.max(maximum, delta);
                if (delta > 0) { changed++; }
            }
            for (int i = 0; i < screenA.length; i++) {
                if (i % 4 == 3) { continue; }
                int displayed = Math.abs(Byte.toUnsignedInt(screenA[i]) - Byte.toUnsignedInt(screenB[i]));
                displayTotal += displayed;
                displayMaximum = Math.max(displayMaximum, displayed);
            }
            int channels = a.length / 4 * 3;
            report.append(String.format(Locale.ROOT,
                  "PIXELS canonicalRgbMean=%.9g canonicalRgbMax=%.9g changedRgb=%d changedAlpha=%d "
                        + "displayRgbMean=%.9g displayRgbMax=%d hdrPixels=%d displayPixels=%d%n",
                  total / channels, maximum, changed, alphaChanged, displayTotal / (screenA.length / 4 * 3), displayMaximum,
                  a.length / 4, screenA.length / 4));
            Files.writeString(receipt, report);
            System.out.println(report.substring(report.lastIndexOf("PIXELS")));
        } finally {
            var restored = shaders.apply(current);
            assertTrue(restored.success(), restored.message());
            if (restored.updated() > 0) { terrain.shadersChanged(); }
        }
    }

    /** Read the existing full-frame RGBA16F target, without a second render or an LDR conversion. */
    private static float[] scenePixels(GpuReviewFrame frame) throws ReflectiveOperationException {
        var field = GpuAtmosphere.class.getDeclaredField("sceneColor");
        field.setAccessible(true);
        var scene = (FrameBuffer) field.get(frame.atmosphere());
        var viewport = BufferUtils.newIntBuffer(4);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        scene.begin();
        try {
            var buffer = BufferUtils.newFloatBuffer(scene.getWidth() * scene.getHeight() * 4);
            Gdx.gl.glReadPixels(0, 0, scene.getWidth(), scene.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
            float[] pixels = new float[buffer.capacity()];
            buffer.get(pixels);
            boolean finite = true;
            for (int i = 0; i < pixels.length; i++) {
                finite &= Float.isFinite(pixels[i]);
                if (i % 4 != 3) { pixels[i] /= GpuSceneRadiance.SCALE; }
            }
            assertTrue(finite, "Actual water frame has finite HDR output");
            return pixels;
        } finally {
            scene.end();
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
    }

    private static BoardAtmosphere.Settings wind(float strength) {
        var settings = BoardAtmosphere.DEFAULTS;
        return new BoardAtmosphere.Settings(settings.hour(), settings.clouds(), settings.fog(),
              settings.groundLayerHeight(), settings.haze(), settings.exposure(),
              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, strength, 35));
    }

    private static void settleWind(GpuTerrain terrain) {
        for (int i = 0; i <= Math.ceil(GpuOcean.TRANSITION_SECONDS * 30); i++) {
            terrain.animate(1 / 30f, List.of());
        }
    }

    static BoardScene scene(int size) {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff888888); }
        }
        var pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float u = x / (float) size, v = y / (float) size;
                float left = (float) Math.hypot((u - .30f) / .26f, (v - .5f) / .40f);
                float right = (float) Math.hypot((u - .75f) / .21f, (v - .5f) / .33f);
                boolean island = Math.hypot(u - .30f, v - .42f) < .046f;
                boolean wet = !island && (Math.min(left, right) < 1 || u > .45f && u < .62f && Math.abs(v - .5f) < .055f);
                int depth = !wet ? -1 : Math.min(left, right) > .78f ? 1 : u < .23f ? 2 : 3;
                tiles.add(new BoardScene.Tile(new Coords(x, y), !wet && island ? 2 : 0, depth, false, 0,
                      BoardScene.Surface.SAND, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
