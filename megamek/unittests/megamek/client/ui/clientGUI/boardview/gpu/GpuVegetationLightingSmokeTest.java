/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the production instanced foliage shader, including cutouts, snow, bark, cactus and distant cards. */
@Tag("on-demand")
class GpuVegetationLightingSmokeTest {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 720;

    @Test
    void dryAndWetBarkSnowAndGrassUseTheSharedReflectionModel() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkReflections(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration());
        if (failure.get() != null) { throw new AssertionError("Vegetation material response", failure.get()); }
    }

    private static void checkReflections(GpuShaderManager manager) {
        var cover = new GpuGroundCover();
        try (var fixture = new Fixture()) {
            var grass = scene(BoardScene.Surface.GRASS, BoardLiquid.Kind.NONE, false);
            fixture.terrain.update(grass);
            fixture.environment.get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type).lights.first()
                  .set(.9f, .9f, .9f, -.3f, .4f, -1);
            // Trees and grass take their sun reflection from shadeSurface (surface-shading.glsl).
            String lighting = GpuShaderSource.read("surface-shading.glsl");
            String disabled = lighting.replace(
                  "sheen = sun * surfaceReflectance(normal, light, view, roughness, f0) * multiscatter * hollows;",
                  "sheen = vec3(0.0);");
            assertTrue(!lighting.equals(disabled));
            var missing = new ArrayList<String>();
            var root = BoardGeometry.center(new Coords(0, 0), 0);
            for (String species : List.of("vegetation/tree-dead", "vegetation/pine-snow", "grass")) {
                var stand = new GpuTreeInstances.Stand();
                if (!species.equals("grass")) { stand.add(species, new Matrix4().setToTranslation(root), 0); }
                for (boolean top : new boolean[] { true, false }) {
                    var camera = camera(top, root.cpy().add(0, 0, species.equals("grass") ? 0 : 15), .14f);
                    for (float wetness : new float[] { 0, .7f }) {
                        fixture.terrain.setWetness(wetness);
                        String name = species + (top ? "-top" : "-iso") + (wetness == 0 ? "-dry" : "-wet");
                        int[][] frames = new int[2][];
                        for (int variant = 0; variant < 2; variant++) {
                            var result = manager.apply(variant == 0 ? Map.of() : Map.of("surface-shading.glsl", disabled));
                            assertTrue(result.success(), result.message());
                            if (species.equals("grass")) {
                                GpuTreeLodSmokeTest.newShadingPass(fixture.terrain);
                                var blades = cover.visible(grass, camera.camera, grass.tiles(), fixture.terrain::planted);
                                assertTrue(!blades.isEmpty(), "Exercise real planted grass");
                                ScreenUtils.clear(0, 0, 0, 0, true);
                                fixture.batch.begin(camera.camera);
                                fixture.batch.render(blades, fixture.environment);
                                fixture.batch.end();
                                frames[variant] = pixels();
                            } else { frames[variant] = fixture.pixels(camera, stand, 0); }
                            save("reflection-" + name + (variant == 0 ? "" : "-no-sheen"));
                        }
                        assertTrue(coverage(frames[0]) > 100, name + ": visible receiving geometry");
                        assertEquals(coverage(frames[0]), coverage(frames[1]), name + ": preserve the silhouette");
                        if (red(frames[0]) <= red(frames[1])) { missing.add(name); }
                    }
                }
            }
            assertTrue(missing.isEmpty(), "Dry and wet vegetation must reflect light through GGX: " + missing);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        finally { cover.dispose(); }
    }

    @Test
    void localLightReachesPlantsAndClearsWithSourceVisibilityAndRemoval() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try (var fixture = new Fixture()) {
                    var plain = scene(BoardLiquid.Kind.NONE, false);
                    var hidden = scene(BoardLiquid.Kind.NONE, true);
                    var lava = scene(BoardLiquid.Kind.MAGMA, false);
                    var root = new Vector3(28, -12, 0);
                    var emitter = new GpuLavaLighting.Source(0, root.cpy().add(12, -12, 28), 1,
                          new Vector3(1.8f, .24f, .025f), 40);
                    for (String species : List.of("vegetation/tree", "vegetation/tree-dead", "vegetation/pine-snow", "vegetation/cactus")) {
                        var stand = new GpuTreeInstances.Stand();
                        stand.add(species, new Matrix4().setToTranslation(root), 0);
                        for (boolean top : new boolean[] { true, false }) {
                            var camera = camera(top, root.cpy().add(0, 0, 15), .14f);
                            for (int level = 0; level < TreeLod.LEVELS; level++) {
                                String name = species + "-lod" + level + (top ? "-top" : "-iso");
                                fixture.lights.setFungus(List.of());
                                fixture.lights.update(plain);
                                float[] unlit = fixture.radiance(camera, stand, level);
                                if (level == 0 || level == 3) { fixture.saveDisplay(name + "-unlit"); }
                                fixture.lights.update(lava);
                                float[] molten = fixture.radiance(camera, stand, level);
                                // The linear red the lava adds, for its calibration: in all, per covered pixel and at most.
                                double added = red(molten) - red(unlit);
                                System.out.printf(java.util.Locale.ROOT, "Lava red %s: %+.6g total, %+.6g per covered pixel,"
                                      + " %+.6g peak%n", name, added, added / Math.max(1, coverage(unlit)), peakRed(unlit, molten));
                                if (!top && level == 0) {
                                    assertTrue(red(molten) > red(unlit), name + ": nearby lava reaches the plant");
                                }
                                fixture.lights.update(scene(BoardLiquid.Kind.MAGMA, true));
                                assertArrayEquals(unlit, fixture.radiance(camera, stand, level), name + ": hidden lava");
                                fixture.lights.setFungus(List.of(emitter));
                                fixture.lights.update(plain);
                                float[] lit = fixture.radiance(camera, stand, level);
                                if (level == 0 || level == 3) { fixture.saveDisplay(name + "-lit"); }
                                assertTrue(red(lit) > red(unlit), name + ": nearby fungal light reaches the plant");
                                assertEquals(coverage(unlit), coverage(lit), name + ": alpha coverage is unchanged");
                                fixture.lights.update(hidden);
                                assertArrayEquals(unlit, fixture.radiance(camera, stand, level), name + ": hidden fungi");
                                fixture.lights.setFungus(List.of());
                                fixture.lights.update(plain);
                                assertArrayEquals(unlit, fixture.radiance(camera, stand, level), name + ": removed fungi");
                                fixture.lights.setFungus(List.of(emitter));
                                fixture.lights.update(plain);
                                assertArrayEquals(lit, fixture.radiance(camera, stand, level), name + ": restored source");
                                fixture.lighting.lavaLighting = null;
                                assertArrayEquals(unlit, fixture.radiance(camera, stand, level), name + ": no light field");
                                fixture.lighting.lavaLighting = fixture.lights;
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration());
        if (failure.get() != null) { throw new AssertionError("Vegetation lighting", failure.get()); }
    }

    /** Isolated foliage cost, not whole-game FPS: overlapping trees, zero emitters and 32 overlapping ones. */
    @Test
    @Tag("gpu-benchmark")
    void measuresDenseFoliageWithAndWithoutLocalLights() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            private Fixture fixture;
            private GpuStageTimings timings;
            private final GpuTreeInstances.Stand stand = new GpuTreeInstances.Stand();
            private final List<GpuLavaLighting.Source> emitters = new ArrayList<>();
            private final StringBuilder report = new StringBuilder();
            private BoardScene board;
            private BoardCamera camera;
            private int scenario;
            private int frame;

            @Override
            public void create() {
                try {
                    fixture = new Fixture();
                    timings = new GpuStageTimings();
                    board = scene(BoardLiquid.Kind.NONE, false);
                    for (int x = 0; x < 24; x++) for (int y = 0; y < 24; y++) {
                        stand.add("vegetation/tree", new Matrix4().setToTranslation(x * 12, y * 12, 0), 0);
                    }
                    for (int i = 0; i < 32; i++) {
                        emitters.add(new GpuLavaLighting.Source(0, new Vector3(40 + i % 8 * 28, 40 + i / 8 * 56, 20),
                              1, new Vector3(1.8f, .24f, .025f), 40));
                    }
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; ")
                          .append(Gdx.graphics.getBackBufferWidth()).append('x')
                          .append(Gdx.graphics.getBackBufferHeight()).append("; 576 instanced trees; 120 warmup / 600 samples\n");
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                try {
                    if (frame == 0) {
                        camera = camera(scenario < 2, new Vector3(138, 138, 15), .48f);
                        fixture.lights.setFungus(scenario % 2 == 0 ? List.of() : emitters);
                        fixture.lights.update(board);
                    }
                    timings.beginFrame(frame >= 120);
                    timings.stage("foliage");
                    fixture.draw(camera, stand, 0);
                    timings.stage(null);
                    if (++frame == 720) {
                        String name = (scenario < 2 ? "top" : "iso") + (scenario % 2 == 0 ? "-no-lights" : "-32-lights");
                        save("forest-" + name);
                        timings.appendReport(report, name);
                        frame = 0;
                        if (++scenario == 4) {
                            Files.writeString(new File(output(), "timings.txt").toPath(), report);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void dispose() {
                if (timings != null) { timings.close(); }
                if (fixture != null) { fixture.close(); }
            }
        }, configuration());
        if (failure.get() != null) { throw new AssertionError("Vegetation timing", failure.get()); }
    }

    private static final class Fixture implements AutoCloseable {
        private final GpuTerrain terrain = new GpuTerrain();
        private final GpuAssets assets = new GpuAssets();
        private final GpuLavaLighting lights = new GpuLavaLighting(assets);
        /** Local lights reach the draws through the environment's lighting; its sky is black. */
        private final GpuEnvironmentLighting lighting = new GpuEnvironmentLighting();
        private final Environment environment = new Environment();
        private final GpuSceneTestOutput output = new GpuSceneTestOutput();
        private final ModelBatch batch;
        private final GpuTreeInstances instances;

        Fixture() throws ReflectiveOperationException {
            // Borrow the actual board batch and material preparation, including its instanced/impostor variants.
            var field = GpuTerrain.class.getDeclaredField("batch");
            field.setAccessible(true);
            batch = (ModelBatch) field.get(terrain);
            var prepare = GpuTerrain.class.getDeclaredMethod("foliage", Model.class);
            prepare.setAccessible(true);
            instances = new GpuTreeInstances((name, level) -> {
                try { return (Model) prepare.invoke(null, assets.lodModel(name, level)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
            environment.set(ColorAttribute.createAmbientLight(.02f, .02f, .02f, 1));
            environment.add(new DirectionalLight().set(.04f, .04f, .04f, -.3f, .4f, -1));
            lighting.lavaLighting = lights;
            environment.set(lighting);
        }

        void draw(BoardCamera camera, GpuTreeInstances.Stand stand, int level) {
            draw(camera, stand, level, () -> ScreenUtils.clear(0, 0, 0, 0, true));
        }

        private void draw(BoardCamera camera, GpuTreeInstances.Stand stand, int level, Runnable clearTarget) {
            GpuTreeLodSmokeTest.newShadingPass(terrain); // Advance the production shader's per-pass global cache.
            clearTarget.run();
            instances.begin(GpuTreeInstances.Pass.COLOUR);
            instances.add(stand, level, true);
            batch.begin(camera.camera);
            batch.render(instances, environment);
            batch.end();
        }

        int[] pixels(BoardCamera camera, GpuTreeInstances.Stand stand, int level) {
            draw(camera, stand, level);
            return GpuVegetationLightingSmokeTest.pixels();
        }

        /**
         * Canonical linear radiance from the production HDR scene target. Local light too weak to change an 8-bit
         * window pixel, such as molten lava's spill, still adds to it.
         */
        float[] radiance(BoardCamera camera, GpuTreeInstances.Stand stand, int level) {
            draw(camera, stand, level, () -> output.begin(0, 0, 0, 0));
            return output.linearPixels();
        }

        /** Saves the last {@link #radiance} frame through the production display conversion. */
        void saveDisplay(String name) {
            output.display();
            save(name);
        }

        @Override
        public void close() {
            instances.dispose(); lighting.dispose(); terrain.dispose(); assets.dispose(); output.close();
        }
    }

    private static Lwjgl3ApplicationConfiguration configuration() {
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        configuration.useVsync(false);
        configuration.setForegroundFPS(0);
        return configuration;
    }

    private static BoardCamera camera(boolean top, Vector3 center, float zoom) {
        var camera = new BoardCamera();
        camera.resize(WIDTH, HEIGHT);
        camera.setIsometric(!top);
        camera.camera.zoom = zoom;
        camera.center(center);
        return camera;
    }

    private static BoardScene scene(BoardLiquid.Kind kind, boolean hidden) {
        return scene(BoardScene.Surface.ROCK, kind, hidden);
    }

    private static BoardScene scene(BoardScene.Surface surface, BoardLiquid.Kind kind, boolean hidden) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, surface, null, null, List.of(), List.of(),
              new BoardLiquid(kind, "", 0), null, true);
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
        return new BoardScene(scene.boardId(), 1, 1, scene.tiles(), scene.units(), scene.plannedPath(), scene.selectedId(),
              scene.phase(), scene.commands(), scene.light(), scene.firingLines(), scene.rangeBorders(), scene.markers(),
              scene.tactical(), scene.rangeLabels(), new BoardFieldOfView(1, 1,
                    List.of(hidden ? new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)
                          : BoardFieldOfView.Hex.VISIBLE), 0, 0, hidden, false, false));
    }

    private static long red(int[] pixels) {
        long total = 0;
        for (int pixel : pixels) { total += pixel >>> 24; }
        return total;
    }

    private static double red(float[] radiance) {
        double total = 0;
        for (int i = 0; i < radiance.length; i += 4) { total += radiance[i]; }
        return total;
    }

    private static int coverage(float[] radiance) {
        int count = 0;
        for (int i = 3; i < radiance.length; i += 4) { if (radiance[i] != 0) { count++; } }
        return count;
    }

    /** The largest red increase of any pixel. */
    private static double peakRed(float[] before, float[] after) {
        double peak = 0;
        for (int i = 0; i < before.length; i += 4) { peak = Math.max(peak, after[i] - before[i]); }
        return peak;
    }

    private static int[] pixels() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, WIDTH, HEIGHT);
        try {
            int[] pixels = new int[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                pixels[y * WIDTH + x] = image.getPixel(x, y);
            }
            return pixels;
        } finally { image.dispose(); }
    }

    private static int coverage(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) { if ((pixel & 255) != 0) { count++; } }
        return count;
    }

    private static File output() {
        var directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "vegetation-light");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return directory;
    }

    private static void save(String name) { GpuReviewFrame.save(new File(output(), name + ".png")); }
}
