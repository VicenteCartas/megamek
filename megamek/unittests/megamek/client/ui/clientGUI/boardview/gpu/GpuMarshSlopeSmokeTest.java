/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Marsh slopes retain their material across levels; downhill seepage differs from a basin's uphill waterline. */
@Tag("on-demand")
class GpuMarshSlopeSmokeTest {
    /** Paired shader-source review uses the identical installed scene, animation clock and draw ordering. */
    @Test
    void frozenMarshMaterialsCompareSavedShaderSources() {
        String baseline = System.getProperty("megamek.gpu.shaderAuditBaseline");
        assumeTrue(baseline != null, "The paired review requires saved original shader sources");
        boolean timing = Boolean.getBoolean("megamek.gpu.marshTiming");
        boolean materialChanges = Boolean.getBoolean("megamek.gpu.marshMaterialChanges");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        if (timing) { config.useVsync(false); config.setForegroundFPS(0); }
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> compareFrozenSources(manager, baseline, timing, materialChanges)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Frozen marsh shader comparison", failure.get()); }
    }

    private static void compareFrozenSources(GpuShaderManager manager, String baseline, boolean timing, boolean materialChanges) {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "marsh-paired");
        assertTrue(output.isDirectory() || output.mkdirs());
        Map<String, String> reference = new HashMap<>();
        for (String name : List.of("terrain-biome.glsl", "terrain-biome-mask.glsl", "terrain-sculpt.frag", "terrain-hexes.glsl")) {
            var file = new File(baseline, "megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/" + name);
            assertTrue(file.isFile(), "Missing baseline shader " + file);
            reference.put(name, Gdx.files.absolute(file.getAbsolutePath()).readString("UTF-8"));
        }
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        var camera = new BoardCamera();
        camera.resize(960, 720);
        var geometry = BoardGeometry.tuning();
        var lod = TerrainLod.tuning();
        var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)
              + "\nFrozen scene, clock, weather and draw order; reference/current/current/reference.\n");
        if (timing) {
            report.append("960x720; shared current lighting/storage pipeline; biome sources and sculpt material vary.\n")
                  .append("24 warmup + 40 measured frames per ABBA block; GPU stage timestamps; glFinish outside stages.\n")
                  .append("CPU stage times measure submission, not synchronized whole-frame duration.\n");
        }
        try {
            BoardScenes.tune(.94f, true);
            // Shader recompilation must not reorder coplanar terrain by changing program identities.
            var sorter = ModelBatch.class.getDeclaredField("sorter");
            sorter.setAccessible(true);
            sorter.set(GpuTestSupport.field(terrain, "batch"), new DefaultRenderableSorter());
            for (boolean sloped : new boolean[] { false, true }) {
                var scene = sloped ? scene("connected")
                      : BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
                for (boolean rain : new boolean[] { false, true }) {
                    frame.configure(new BoardAtmosphere.Settings(16, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
                          new BoardAtmosphere.Effects(rain ? 1 : 0, 0, 0, 0, 0, .8f, 35)));
                    for (int pixels : new int[] { 48, 160, 560 }) {
                        camera.setIsometric(true);
                        camera.camera.zoom = BoardGeometry.width() / pixels;
                        camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene, camera.camera);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        terrain.animate(1.25f, List.of());
                        byte[] expected = null;
                        byte[] current = null;
                        String label = (sloped ? "bank" : "plateau") + (rain ? "-rain-" : "-wind-") + pixels;
                        for (int variant = 0; variant < 4; variant++) {
                            boolean original = variant == 0 || variant == 3;
                            var changed = manager.apply(original ? reference : Map.of());
                            assertTrue(changed.success(), changed.message());
                            String capture = label + "-" + variant + (original ? "-reference" : "-current");
                            if (timing) {
                                try (var timings = new GpuStageTimings()) {
                                    for (int iteration = 0; iteration < 64; iteration++) {
                                        timings.beginFrame(iteration >= 24);
                                        frame.render(terrain, camera, scene, timings);
                                        Gdx.gl.glFinish();
                                    }
                                    timings.appendReport(report, capture);
                                }
                            } else {
                                for (int warmup = 0; warmup < 3; warmup++) { frame.render(terrain, camera, scene); }
                            }
                            byte[] actual = ScreenUtils.getFrameBufferPixels(0, 0, 960, 720, false);
                            if (expected == null) { expected = actual; }
                            if (variant == 1) { current = actual; }
                            if (variant == 2 || variant == 3) {
                                org.junit.jupiter.api.Assertions.assertArrayEquals(original ? expected : current, actual,
                                      capture + " must repeat exactly at a frozen clock");
                            }
                            long total = 0;
                            int maximum = 0;
                            for (int i = 0; i < actual.length; i++) {
                                int delta = Math.abs(Byte.toUnsignedInt(actual[i]) - Byte.toUnsignedInt(expected[i]));
                                total += delta;
                                maximum = Math.max(maximum, delta);
                            }
                            double mean = total / (double) actual.length;
                            report.append(String.format(Locale.ROOT, "%s meanAbs=%.6f max=%d%n", capture, mean, maximum));
                            GpuReviewFrame.save(new File(output, capture + ".png"));
                            Gdx.files.absolute(new File(output, "comparison.txt").getAbsolutePath()).writeString(report.toString(), false);
                            if (!materialChanges) {
                                assertTrue(mean < .025 && maximum <= 3,
                                      capture + " changed the image: mean " + mean + ", max " + maximum);
                            }
                        }
                        if (!sloped && pixels == 160) { checkMarshResponse(manager, terrain, camera, rain, report); }
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (Exception error) { throw new AssertionError(error); }
        finally {
            terrain.dispose(); frame.dispose();
            BoardGeometry.tune(geometry); TerrainLod.tune(lod);
        }
    }

    /** Read the actual completed material response, independently of sun angle and display exposure. */
    private static void checkMarshResponse(GpuShaderManager manager, GpuTerrain terrain, BoardCamera camera,
          boolean rain, StringBuilder report) {
        String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
        assertTrue(sculpt.contains(result), "The diagnostic must read the completed material");
        String probe = sculpt.replace(result,
              "vec3 result = vec3(dielectricRoughness(film, finish.roughness), biomePool, biomeDamp > .27 ? 4.0 : 0.0);");
        assertTrue(manager.apply(Map.of("terrain-sculpt.frag", probe)).success());
        try (var surface = new GpuSceneTestOutput()) {
            surface.draw(terrain, camera.camera, false);
            var target = surface.scene();
            target.begin();
            var pixels = BufferUtils.newFloatBuffer(target.getWidth() * target.getHeight() * 4);
            Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            target.end();
            int peat = 0, pools = 0;
            float minimumPeat = 1, maximumPool = 0;
            for (int i = 0; i < pixels.capacity(); i += 4) {
                if (pixels.get(i + 2) / GpuSceneRadiance.SCALE < 3.9f) { continue; }
                float roughness = pixels.get(i) / GpuSceneRadiance.SCALE;
                float pool = pixels.get(i + 1) / GpuSceneRadiance.SCALE;
                if (pool < .02f) { peat++; minimumPeat = Math.min(minimumPeat, roughness); }
                if (pool > .99f) { pools++; maximumPool = Math.max(maximumPool, roughness); }
            }
            report.append(String.format(Locale.ROOT, "rain=%s peat=%d minimumRoughness=%.4f pools=%d maximumRoughness=%.4f%n",
                  rain, peat, minimumPeat, pools, maximumPool));
            assertTrue(peat > 100 && pools > 100, "The probe must see both porous peat and open water: " + peat + "/" + pools);
            assertTrue(minimumPeat > .65f, "Damp/rained peat must remain rough: " + minimumPeat);
            assertTrue(maximumPool < .09f, "Exposed pools must retain a smooth water surface: " + maximumPool);
        } finally { assertTrue(manager.apply(Map.of()).success()); }
    }

    @Test
    void connectedAndDrainingMarshBanksAcrossTerrainLods() throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "marsh-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.94f, true);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    for (String contact : new String[] { "connected", "downhill", "uphill" }) {
                        var scene = scene(contact);
                        for (var lod : TerrainLod.values()) {
                            TerrainLod.tune(switch (lod) {
                                case FULL -> new TerrainLod.Tuning(1, 1);
                                case MEDIUM -> new TerrainLod.Tuning(100_000, 1);
                                case COARSE -> new TerrainLod.Tuning(100_000, 1000);
                                case DISTANT -> new TerrainLod.Tuning(100_000, 100_000);
                            });
                            camera.setIsometric(true);
                            camera.camera.zoom = .15f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            terrain.update(scene, camera.camera);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, contact + "-" + lod + "-iso.png"));
                            camera.setIsometric(false);
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, contact + "-" + lod + "-top.png"));
                        }
                    }
                    coverageChecks();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose(); frame.dispose();
                    BoardGeometry.tune(original);
                    TerrainLod.tune(originalLod);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Marsh slope materials", failure.get()); }
    }

    private static BoardScene scene(String contact) {
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              contact.equals("connected") || (c.getY() < 4) == contact.equals("downhill")
                    ? BoardScene.Biome.MARSH : BoardScene.Biome.NONE, c.getY() < 4 ? 1 : 0));
    }

    /** Sample the real GLSL mask, independently of lighting and vegetation hiding the bank. */
    private static void coverageChecks() {
        String code = GpuShaderSource.read("terrain-hexes.glsl") + GpuShaderSource.read("terrain-biome-mask.glsl");
        var shader = GpuGlsl.compile("marsh coverage probe", """
              in vec3 a_position;
              out vec3 world;
              void main() {
                  world = a_position;
                  gl_Position = vec4((float(gl_VertexID) + .5) / 32.0 * 2.0 - 1.0, 0.0, 0.0, 1.0);
                  gl_PointSize = 1.0;
              }
              """, "layout(location = 0) out vec4 fragColor;\nuniform float u_levelHeight, u_metre;\nin vec3 world;\n" + code + """
              void main() {
                  vec4 cover, fringe;
                  float tundra;
                  biomeCoverage(world, 14.0, cover, fringe, tundra);
                  fragColor = vec4(cover.y, fringe.y, 0.0, 1.0);
              }
              """);
        var mesh = new Mesh(true, 32, 0, VertexAttribute.Position());
        var target = new FrameBuffer(Pixmap.Format.RGBA8888, 32, 1, false);
        var mask = new GpuBiomeSurface();
        try {
            assertTrue(shader.isCompiled(), shader.getLog());
            float metre = BoardRelief.metres(1), level = BoardGeometry.level() / metre;
            Vector3 edge = BoardGeometry.center(new Coords(4, 3), 0)
                  .lerp(BoardGeometry.center(new Coords(4, 4), 0), .5f).scl(1 / metre);
            for (String contact : new String[] { "connected", "downhill", "uphill" }) {
                var scene = scene(contact);
                mask.update(scene);
                float[] points = new float[32 * 3];
                for (int i = 0; i < 32; i++) {
                    points[3 * i] = edge.x + (i - 15.5f) * .4f;
                    points[3 * i + 1] = edge.y;
                    points[3 * i + 2] = level * .5f;
                }
                mesh.setVertices(points);
                target.begin();
                Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                Gdx.gl.glDisable(GL20.GL_BLEND);
                shader.bind();
                mask.texture().bind(0);
                shader.setUniformi("u_biomeHexes", 0);
                shader.setUniformf("u_biomeBoard", (float) scene.width(), (float) scene.height());
                shader.setUniformf("u_levelHeight", BoardGeometry.level());
                shader.setUniformf("u_metre", metre);
                mesh.render(shader, GL20.GL_POINTS);
                var pixels = Pixmap.createFromFrameBuffer(0, 0, 32, 1);
                target.end();
                try {
                    int minimum = 255, maximum = 0;
                    for (int i = 0; i < 32; i++) {
                        int sample = pixels.getPixel(i, 0);
                        assertEquals(0, sample >>> 24, "Connecting peat must not carry level pool support down the bank");
                        int peat = sample >>> 16 & 255;
                        minimum = Math.min(minimum, peat); maximum = Math.max(maximum, peat);
                    }
                    if (contact.equals("connected")) {
                        assertTrue(minimum > 225, "Adjacent marsh levels must retain peat across the intervening bank: " + minimum);
                    } else if (contact.equals("downhill")) {
                        assertTrue(maximum - minimum > 24, "A draining bank needs uneven seepage, not a constant height contour");
                    } else {
                        assertTrue(maximum < 20, "A lower marsh cannot saturate the higher bank like downhill drainage");
                    }
                } finally { pixels.dispose(); }
            }
        } finally { mask.dispose(); target.dispose(); mesh.dispose(); shader.dispose(); }
    }
}
