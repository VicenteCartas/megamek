/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.NumberUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Interleaved submission comparison on the same installed terrain and camera, with actual GL counters.
 * Opt-in performanceCost measures pixel/fragment cost at three zooms. performanceShaderReference instead
 * compares a supplied terrain-materials.glsl with the current shader, keeping meshes and animation fixed.
 */
@Tag("on-demand")
class GpuTerrainScalingSmokeTest {
    @Test
    void measuresProjectedTerrainAndMaterialStateReuse() throws Exception {
        String path = System.getProperty("megamek.gpu.performanceBoard", "");
        assumeTrue(!path.isEmpty());
        Board board = BoardFile.loadSheet(new File(path), false, true);
        boolean cost = Boolean.getBoolean("megamek.gpu.performanceCost");
        int width = cost ? Integer.getInteger("megamek.gpu.performanceWidth", 2560) : 1280;
        int height = cost ? Integer.getInteger("megamek.gpu.performanceHeight", 1440) : 900;
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
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuSceneTestOutput target = new GpuSceneTestOutput(width, height);
                    try {
                        if (cost) { installCostProbe(terrain); }
                        BoardScene scene = fixture.source.takeFrame().scene();
                        BoardCamera camera = new BoardCamera();
                        camera.resize(width, height);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        long start = System.nanoTime();
                        terrain.update(scene, camera.camera);
                        settle(terrain, camera);
                        System.out.printf("TERRAIN size=%dx%d renderer=%s open=%.3f ms%n", scene.width(), scene.height(),
                              Gdx.gl.glGetString(GL20.GL_RENDERER), (System.nanoTime() - start) / 1e6);
                        ModelBatch batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
                        GpuTerrainBatch grouping = (GpuTerrainBatch) batch.getRenderableSorter();
                        GpuTerrainPages pages = (GpuTerrainPages) GpuTestSupport.field(terrain, "terrainPages");
                        if (cost) {
                            for (String view : new String[] { "overview", "medium", "close" }) {
                                if (!view.equals("overview")) {
                                    camera.camera.zoom = view.equals("medium") ? 2 : .5f;
                                    camera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                                    settle(terrain, camera);
                                }
                                terrain.animate(0, List.of());
                                terrain.renderShadows(camera.camera, List.of());
                                reportGeometry(terrain, camera, view);
                                if (System.getProperty("megamek.gpu.performanceShaderReference") == null) {
                                    measureCost(terrain, camera, target, view);
                                } else { compareShaders(terrain, camera, target, view); }
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            return;
                        }
                        boolean testPages = Boolean.getBoolean("megamek.gpu.performancePages");
                        boolean testProps = Boolean.getBoolean("megamek.gpu.performanceProps");
                        if (!testProps) { pages.setEnabled(false); }
                        Consumer<Boolean> toggle = testPages ? pages::setEnabled : testProps
                              ? enabled -> toggleProps(terrain, enabled) : grouping::setEnabled;
                        for (String view : new String[] { "overview", "medium", "close" }) {
                            if (!view.equals("overview")) {
                                camera.camera.zoom = view.equals("medium") ? 2 : .5f;
                                camera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                                double maximum = settle(terrain, camera);
                                System.out.printf("TERRAIN %s maximum render-thread detail work=%.3f ms%n", view, maximum);
                            }
                            terrain.animate(0, List.of());
                            terrain.renderShadows(camera.camera, List.of());
                            byte[] reference = null;
                            Long referenceIndices = null;
                            for (int round = 0; round < 2; round++) {
                                for (boolean enabled : new boolean[] { false, true }) {
                                    target.begin(.2f, .26f, .31f, 1);
                                    toggle.accept(enabled);
                                    double warmupMaximum = 0;
                                    for (int i = 0; i < 90; i++) {
                                        long warmupStart = System.nanoTime();
                                        draw(terrain, camera);
                                        warmupMaximum = Math.max(warmupMaximum, (System.nanoTime() - warmupStart) / 1e6);
                                    }
                                    Gdx.gl.glFinish();
                                    double[] times = new double[300];
                                    long began = System.nanoTime();
                                    for (int i = 0; i < times.length; i++) {
                                        start = System.nanoTime();
                                        draw(terrain, camera);
                                        times[i] = (System.nanoTime() - start) / 1e6;
                                    }
                                    Gdx.gl.glFinish();
                                    double mean = (System.nanoTime() - began) / 1e6 / times.length;
                                    Arrays.sort(times);
                                    GL20 rawGl20 = Gdx.gl20;
                                    GLProfiler profiler = new GLProfiler(Gdx.graphics);
                                    profiler.enable();
                                    // GLProfiler accumulates indices in a float: draw reordering loses low bits above
                                    // 2^24. Count the actual ranges as longs to check geometry preservation exactly.
                                    long[] indices = { 0 };
                                    Field sorterField = ModelBatch.class.getDeclaredField("sorter");
                                    sorterField.setAccessible(true);
                                    sorterField.set(batch, (RenderableSorter) (viewCamera, ranges) -> {
                                        grouping.sort(viewCamera, ranges);
                                        for (Renderable range : ranges) {
                                            for (Renderable part : GpuTerrainBatch.ranges(range)) { indices[0] += part.meshPart.size; }
                                        }
                                    });
                                    try { draw(terrain, camera); }
                                    finally { sorterField.set(batch, grouping); }
                                    System.out.printf("TERRAIN view=%s round=%d pages=%s props=%s enabled=%s mean=%.3f cpuMedian=%.3f p95=%.3f draws=%d glCalls=%d indices=%.0f%n",
                                          view, round, testPages, testProps, enabled, mean, times[150], times[285], profiler.getDrawCalls(),
                                          profiler.getCalls(), profiler.getVertexCount().total);
                                    GpuStageTimings.stopCounting(profiler, rawGl20);
                                    System.out.printf("TERRAIN exactIndices=%d cacheGeometryMiB=%.3f warmupMax=%.3f ms%n",
                                          indices[0], GpuDrawCallAudit.pageBytes(pages) / 1048576.0, warmupMaximum);
                                    if (referenceIndices == null) { referenceIndices = indices[0]; }
                                    else { assertEquals(referenceIndices.longValue(), indices[0], "Every triangle survives batching"); }
                                    target.display();
                                    byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, 1280, 900, false);
                                    if (reference == null) { reference = pixels; }
                                    else {
                                        if (!Arrays.equals(reference, pixels)) {
                                            save(reference, view + "-original.png");
                                            save(pixels, view + "-batched.png");
                                        }
                                        assertArrayEquals(reference, pixels, "Batching preserves " + view);
                                    }
                                }
                            }
                            FrameBuffer.unbind();
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { target.close(); terrain.dispose(); Gdx.app.exit(); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Terrain scaling benchmark", failure.get()); }
    }

    private static void toggleProps(GpuTerrain terrain, boolean enabled) {
        try {
            Object props = GpuTestSupport.field(terrain, "propBatch");
            var toggle = props.getClass().getDeclaredMethod("setEnabled", boolean.class);
            toggle.setAccessible(true);
            toggle.invoke(props, enabled);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Diagnostic-only uniform: identical meshes/vertex shaders, with an optional constant-colour fragment path. */
    private static void installCostProbe(GpuTerrain terrain) throws Exception {
        ModelBatch batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
        var config = (DefaultShader.Config) GpuTestSupport.field(batch.getShaderProvider(), "sculptShader");
        String original = config.fragmentShader;
        config.fragmentShader = original.replaceFirst("void\\s+main\\s*\\(\\s*\\)\\s*\\{",
              "uniform float u_costProbe;\nvoid main() {\n"
                    + "if (u_costProbe > .5) { fragColor = vec4(.5, .5, .5, 1.0); return; }\n");
        assertTrue(!original.equals(config.fragmentShader), "The probe must wrap the sculpt fragment entry point");
    }

    private static void costProbe(GpuTerrain terrain, boolean flat) throws Exception {
        ModelBatch batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
        Field shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        for (Object value : (Array<?>) shaders.get(batch.getShaderProvider())) {
            if (value instanceof DefaultShader shader && shader.program.hasUniform("u_costProbe")) {
                shader.program.bind();
                shader.program.setUniformf("u_costProbe", flat ? 1 : 0);
            }
        }
    }

    private static void measureCost(GpuTerrain terrain, BoardCamera camera, GpuSceneTestOutput target, String view) throws Exception {
        target.begin(.2f, .26f, .31f, 1);
        try (GpuStageTimings timings = new GpuStageTimings()) {
            for (int i = 0; i < 90; i++) { draw(terrain, camera); }
            for (String mode : new String[] { "full", "half-resolution", "flat-fragment", "full-repeat" }) {
                costProbe(terrain, mode.equals("flat-fragment"));
                int divisor = mode.equals("half-resolution") ? 2 : 1;
                Gdx.gl.glViewport(0, 0, target.scene().getWidth() / divisor, target.scene().getHeight() / divisor);
                for (int i = 0; i < 270; i++) {
                    timings.beginFrame(i >= 90);
                    timings.stage("terrain");
                    draw(terrain, camera);
                    timings.stage(null);
                    // This is a GPU-cost diagnostic, not app FPS. Drain each sample to avoid dropping queries
                    // when the CPU can submit many more than four frames ahead of the GPU.
                    Gdx.gl.glFinish();
                }
                Gdx.gl.glFinish();
                StringBuilder report = new StringBuilder();
                timings.appendReport(report, "COST " + view + " " + mode);
                System.out.print(report);
                GL20 raw = Gdx.gl20;
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                profiler.enable();
                try {
                    draw(terrain, camera);
                    System.out.printf("COST draws=%d indices=%.0f viewport=%dx%d%n", profiler.getDrawCalls(),
                          profiler.getVertexCount().total, target.scene().getWidth() / divisor, target.scene().getHeight() / divisor);
                } finally { GpuStageTimings.stopCounting(profiler, raw); }
            }
            var output = Gdx.files.absolute(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                  "terrain-cost-" + view + ".png").getAbsolutePath());
            output.parent().mkdirs();
            // Timings isolate opaque terrain; visual review must also include the water above its bed.
            draw(terrain, camera);
            terrain.renderTransparent(camera.camera);
            target.display();
            Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0, target.scene().getWidth(), target.scene().getHeight());
            try { PixmapIO.writePNG(output, pixels, -1, true); }
            finally { pixels.dispose(); }
        } finally { costProbe(terrain, false); FrameBuffer.unbind(); }
    }

    /** Both shader versions render the very same installed meshes, textures, animation time and camera. */
    private static void compareShaders(GpuTerrain terrain, BoardCamera camera, GpuSceneTestOutput target, String view) throws Exception {
        ModelBatch batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
        var provider = batch.getShaderProvider();
        var config = (DefaultShader.Config) GpuTestSupport.field(provider, "sculptShader");
        Field shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        @SuppressWarnings("unchecked") Array<Shader> cache = (Array<Shader>) shaders.get(provider);
        Array<Shader> optimized = new Array<>(cache), reference = new Array<>();
        String optimizedSource = config.fragmentShader;
        String material = Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/terrain-materials.glsl").readString();
        String original = Gdx.files.absolute(System.getProperty("megamek.gpu.performanceShaderReference")).readString();
        String referenceSource = optimizedSource.replace(material, original);
        assertTrue(!optimizedSource.equals(referenceSource), "Reference shader must replace the material functions");
        Field sorterField = ModelBatch.class.getDeclaredField("sorter");
        sorterField.setAccessible(true);
        RenderableSorter sorter = batch.getRenderableSorter();
        byte[] referencePixels = null;
        try (GpuStageTimings timings = new GpuStageTimings()) {
            for (int mode = 0; mode < 4; mode++) {
                target.begin(.2f, .26f, .31f, 1);
                boolean changed = mode % 2 == 1;
                Array<Shader> active = changed ? optimized : reference;
                cache.clear(); cache.addAll(active);
                config.fragmentShader = changed ? optimizedSource : referenceSource;
                for (int i = 0; i < 270; i++) {
                    timings.beginFrame(i >= 90);
                    timings.stage("terrain");
                    draw(terrain, camera);
                    timings.stage(null);
                    Gdx.gl.glFinish();
                }
                active.clear(); active.addAll(cache);
                StringBuilder report = new StringBuilder();
                timings.appendReport(report, "SHADER " + view + " round=" + mode / 2 + " optimized=" + changed);
                System.out.print(report);
                // Program identities differ across versions. Compare images in the same depth order so ties
                // between terrain, plants and props do not masquerade as changes in material shading.
                sorterField.set(batch, new DefaultRenderableSorter());
                try { draw(terrain, camera); }
                finally { sorterField.set(batch, sorter); }
                target.display();
                byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, target.scene().getWidth(), target.scene().getHeight(), false);
                if (referencePixels == null) { referencePixels = pixels; }
                long total = 0, different = 0;
                int maximum = 0;
                for (int i = 0; i < pixels.length; i++) {
                    int delta = Math.abs(Byte.toUnsignedInt(referencePixels[i]) - Byte.toUnsignedInt(pixels[i]));
                    total += delta; maximum = Math.max(maximum, delta);
                    if (delta > 2) { different++; }
                }
                System.out.printf("SHADER image meanAbs=%.6f channelsAbove2=%.5f%% max=%d%n",
                      total / (double) pixels.length, 100.0 * different / pixels.length, maximum);
                var output = Gdx.files.absolute(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                      "shader-" + view + "-" + mode + ".png").getAbsolutePath());
                output.parent().mkdirs();
                Pixmap image = new Pixmap(target.scene().getWidth(), target.scene().getHeight(), Pixmap.Format.RGBA8888);
                try { image.getPixels().put(pixels); PixmapIO.writePNG(output, image, -1, true); }
                finally { image.dispose(); }
            }
        } finally {
            FrameBuffer.unbind();
            config.fragmentShader = optimizedSource;
            cache.clear(); cache.addAll(optimized);
            for (Shader shader : reference) { shader.dispose(); }
        }
    }

    private static void reportGeometry(GpuTerrain terrain, BoardCamera camera, String view) throws Exception {
        long[] counts = new long[7];
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            if (!camera.camera.frustum.boundsInFrustum((BoundingBox) GpuTestSupport.field(chunk, "bounds"))) {
                continue;
            }
            Map<Mesh, float[]> vertices = new IdentityHashMap<>();
            for (Object value : (Array<?>) GpuTestSupport.field(chunk, "terrainRenderables")) {
                Renderable part = (Renderable) value;
                if (!part.material.has(Attribute.getAttributeType("boardSculpt"))) { continue; }
                Mesh mesh = part.meshPart.mesh;
                int stride = mesh.getVertexSize() / Float.BYTES;
                int color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                float[] data = vertices.computeIfAbsent(mesh, key -> mesh.getVertices(new float[mesh.getNumVertices() * stride]));
                short[] indices = new short[part.meshPart.size];
                mesh.getIndices(part.meshPart.offset, part.meshPart.size, indices, 0);
                for (short index : indices) {
                    int packed = NumberUtils.floatToIntColor(data[Short.toUnsignedInt(index) * stride + color]);
                    int blue = (packed >>> 16) & 255;
                    counts[blue < 32 ? 0 : blue < 96 ? 1 : blue < 160 ? 2 : blue < 224 ? 3 : blue < 255 ? 4 : 5]++;
                    if (blue < 32 && (packed >>> 24) < 64) { counts[6]++; }
                }
            }
        }
        System.out.printf("GEOMETRY %s sculptIndices ground=%d plant=%d cliff=%d pit=%d wetRock=%d rock=%d wetGround=%d%n",
              view, counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6]);
    }

    private static double settle(GpuTerrain terrain, BoardCamera camera) throws InterruptedException {
        long deadline = System.nanoTime() + 360_000_000_000L;
        double maximum = 0;
        do {
            long start = System.nanoTime();
            terrain.refine(camera.camera);
            maximum = Math.max(maximum, (System.nanoTime() - start) / 1e6);
            assertTrue(System.nanoTime() < deadline, "Large-map construction and visible detail must settle");
            if (terrain.busy()) { Thread.sleep(5); }
        } while (terrain.busy());
        return maximum;
    }

    private static void save(byte[] pixels, String name) {
        var file = Gdx.files.absolute(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "terrain-pages-" + name).getAbsolutePath());
        file.parent().mkdirs();
        Pixmap image = new Pixmap(1280, 900, Pixmap.Format.RGBA8888);
        try {
            image.getPixels().put(pixels);
            PixmapIO.writePNG(file, image, -1, true);
        } finally { image.dispose(); }
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera) {
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(GpuColor.linear(.2f) * GpuSceneRadiance.SCALE,
              GpuColor.linear(.26f) * GpuSceneRadiance.SCALE, GpuColor.linear(.31f) * GpuSceneRadiance.SCALE, 1, true);
        terrain.render(camera.camera);
    }
}
