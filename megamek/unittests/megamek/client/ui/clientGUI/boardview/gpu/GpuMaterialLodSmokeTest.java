/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Mapped material response: normal maps, dry reflection, plateau outcrops and fungal pigment; authored roughness. */
@Tag("on-demand")
class GpuMaterialLodSmokeTest {
    @Test
    void cacheDebugFollowsDrawPathAndNeverBakesTheTint() {
        UiTestStage.onGl(GpuBoardWindow.configuration(false), () -> {
            var terrain = new GpuTerrain();
            var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
            try {
                var camera = new BoardCamera();
                BoardScene scene = scene("dirt");
                GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                terrain.setGrass(false);
                terrain.setSmallObjects(false);
                camera.setIsometric(false);
                for (float footprint : new float[] { .15f, .55f }) {
                    camera.camera.zoom = footprint * BoardGeometry.width() / 30;
                    camera.center(BoardGeometry.center(new Coords(3, 3), 0));
                    terrain.setMaterialCache(false);
                    GpuCalibrationScenes.settle(terrain, frame, camera, scene);
                    byte[] plain = debugPixels(frame, terrain, camera, scene);
                    terrain.setMaterialCacheDebug(true);
                    byte[] direct = debugPixels(frame, terrain, camera, scene);
                    assertDominant(direct, 0, "Uncached ground is red even when caching is disabled");
                    terrain.setMaterialCacheDebug(false);
                    assertArrayEquals(plain, debugPixels(frame, terrain, camera, scene), "Debug off restores the frame");

                    // Fill with diagnostics enabled too: its colour must never enter the stored material.
                    terrain.setMaterialCacheDebug(true);
                    terrain.setMaterialCache(true);
                    for (int i = 0; i < 600 && !terrain.materialCacheComplete(); i++) {
                        terrain.refine(camera.camera);
                        frame.render(terrain, camera, scene);
                    }
                    assertTrue(terrain.materialCacheComplete(), terrain.materialCacheReport());
                    assertEquals(footprint < .2f ? 0 : 2, terrain.materialCacheStatus().level());
                    assertDominant(debugPixels(frame, terrain, camera, scene), footprint < .2f ? 1 : 2,
                          "Cache level 0 is green; level 2 is blue");
                    terrain.setMaterialCacheDebug(false);
                    byte[] cached = debugPixels(frame, terrain, camera, scene);
                    // DIRT stays near-neutral; a baked red, green or blue tint would exceed this by a wide margin.
                    double[] rgb = meanRgb(cached);
                    assertTrue(Math.max(rgb[0], Math.max(rgb[1], rgb[2]))
                          - Math.min(rgb[0], Math.min(rgb[1], rgb[2])) < 65, "Cache fills contain no diagnostic tint");
                    terrain.setMaterialCacheDebug(true);
                    debugPixels(frame, terrain, camera, scene);
                    terrain.setMaterialCacheDebug(false);
                    assertArrayEquals(cached, debugPixels(frame, terrain, camera, scene));
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                frame.dispose();
                terrain.dispose();
            }
        });
    }

    private static byte[] debugPixels(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        frame.render(terrain, camera, scene);
        return ScreenUtils.getFrameBufferPixels(Gdx.graphics.getBackBufferWidth() / 2 - 32,
              Gdx.graphics.getBackBufferHeight() / 2 - 32, 64, 64, false);
    }

    private static double[] meanRgb(byte[] pixels) {
        double[] mean = new double[3];
        for (int i = 0; i < pixels.length; i += 4) {
            for (int c = 0; c < 3; c++) { mean[c] += (pixels[i + c] & 255) / (pixels.length / 4.0); }
        }
        return mean;
    }

    private static void assertDominant(byte[] pixels, int channel, String message) {
        double[] mean = meanRgb(pixels);
        assertTrue(mean[channel] > mean[(channel + 1) % 3] + 40
              && mean[channel] > mean[(channel + 2) % 3] + 40, message + ": " + java.util.Arrays.toString(mean));
    }

    @Test
    void materialsUseTheirDetailMapsAndKeepTheirColourAndHeat() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkMaterials(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Material LOD", failure.get()); }
    }

    /** The ground's rule: a far pixel shows the close view (GpuMaterialCache.CLOSE_PIXEL) averaged into it. */
    @Test
    void farGroundAveragesTheCloseView() { averages(false); }

    /** The same rule where the material cache draws the far ground from its pages. */
    @Test
    void farCachedGroundAveragesTheCloseView() { averages(true); }

    private static void averages(boolean cached) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkAveraging(cached)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Far ground averages the close view", failure.get()); }
    }

    /**
     * Each family's close frame (0.1 m a pixel) against the central eighth of a frame eight times coarser, in the board
     * view's own frame, whose warmed programs the material cache needs. The close frame is finer than the cache draws.
     */
    private static void checkAveraging(boolean cached) {
        var failures = new ArrayList<String>();
        for (String material : List.of("grass", "dirt", "sand", "rock", "snow", "desert", "concrete")) {
            var terrain = new GpuTerrain();
            var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
            try {
                var camera = new BoardCamera();
                BoardScene scene = scene(material);
                GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                // The ground alone: grass blades and small objects have their own levels of detail.
                terrain.setGrass(false);
                terrain.setSmallObjects(false);
                terrain.setMaterialCache(cached);
                camera.setIsometric(false);
                double close = meanLuminance(terrain, frame, camera, scene, .1f, 1, false);
                double far = meanLuminance(terrain, frame, camera, scene, .8f, 8, cached);
                System.out.printf(Locale.ROOT, "%s%s: close %.5f far %.5f (%+.2f%%)%n", material, cached ? " cached" : "",
                      close, far, 100 * (far - close) / close);
                if (Math.abs(far - close) > close * .05) {
                    failures.add(material + ": a far pixel shows the close view averaged");
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            } finally {
                frame.dispose();
                terrain.dispose();
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** The mean linear luminance of the central 1/k of the frame at {@code footprint} metres a pixel. */
    private static double meanLuminance(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          float footprint, int k, boolean cached) throws InterruptedException {
        camera.camera.zoom = footprint * BoardGeometry.width() / 30;
        camera.center(BoardGeometry.center(new Coords(3, 3), 0));
        GpuCalibrationScenes.settle(terrain, frame, camera, scene);
        for (int i = 0; cached && i < 600 && !terrain.materialCacheComplete(); i++) {
            terrain.refine(camera.camera);
            frame.render(terrain, camera, scene);
        }
        assertTrue(!cached || terrain.materialCacheComplete(), "The material cache fills: " + terrain.materialCacheReport());
        frame.render(terrain, camera, scene);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var pixels = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            var data = pixels.getPixels();
            int w = width / k, h = height / k, x0 = (width - w) / 2, y0 = (height - h) / 2;
            double sum = 0;
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    int at = (y * width + x) * 4;
                    sum += .2126 * linear(data.get(at) & 255) + .7152 * linear(data.get(at + 1) & 255)
                          + .0722 * linear(data.get(at + 2) & 255);
                }
            }
            return sum / (w * h);
        } finally { pixels.dispose(); }
    }

    private static double linear(int code) {
        double value = code / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    @Test
    void authoredRoughnessFollowsProjectedAndMixedTerrainWithoutNormalDetail(
          @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        File previous = megamek.common.Configuration.dataDir();
        File images = megamek.common.Configuration.imagesDir(), fonts = megamek.common.Configuration.fontsDir();
        prepareRoughnessArtwork(previous.toPath().resolve("models/board"), directory.resolve("models/board"));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(600, 480);
        megamek.common.Configuration.setImagesDir(images);
        megamek.common.Configuration.setFontsDir(fonts);
        megamek.common.Configuration.setDataDir(directory.toFile());
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var manager = new GpuShaderManager();
                    try { manager.run(() -> {
                        try { checkSpatialRoughness(manager); }
                        catch (Throwable error) { failure.set(error); }
                    }); }
                    catch (Throwable error) { failure.set(error); }
                    finally { try { manager.close(); } finally { Gdx.app.exit(); } }
                }
            }, config);
        } finally {
            megamek.common.Configuration.setDataDir(previous);
            megamek.common.Configuration.setImagesDir(images);
            megamek.common.Configuration.setFontsDir(fonts);
        }
        if (failure.get() != null) { throw new AssertionError("Projected authored roughness", failure.get()); }
    }

    /** Separate numerical stores carry identical bytes; the existing height path is the projection/blend oracle. */
    private static void prepareRoughnessArtwork(java.nio.file.Path source, java.nio.file.Path target) throws Exception {
        try (var paths = java.nio.file.Files.walk(source)) {
            for (var path : paths.toList()) {
                var copy = target.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) { java.nio.file.Files.createDirectories(copy); }
                else { java.nio.file.Files.copy(path, copy); }
            }
        }
        var sculpt = target.resolve("textures/terrain/surfaces");
        // Only these five ordinary roles appear in the scene; every factor is Godot's default 1, so each role's
        // roughness is its ORM map alone. Unrelated authored maps may have different resolutions.
        try (var files = java.nio.file.Files.list(sculpt)) {
            for (var file : files.filter(path -> path.toString().endsWith(".tres")).toList()) {
                String text = java.nio.file.Files.readString(file);
                java.nio.file.Files.writeString(file, text.replaceAll("(?m)^roughness = .*$", "roughness = 1.0"));
            }
        }
        var names = List.of("grassland-ground", "rock-ground", "temperate-debris", "temperate-cliff", "temperate-bank");
        for (int role = 0; role < names.size(); role++) {
            String name = names.get(role);
            assertTrue(java.nio.file.Files.exists(sculpt.resolve(name + ".tres")), "The actual grass/rock material role exists: " + name);
            var color = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            var height = new BufferedImage(512, 512, BufferedImage.TYPE_BYTE_GRAY);
            var shipped = javax.imageio.ImageIO.read(sculpt.resolve(name + "_orm.png").toFile());
            // The ORM map keeps its occlusion (R) and metallic (B); its roughness (G) becomes the varying review map.
            var orm = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            // Distinct two-axis dyadic checker patterns. Every mip is binary until one averaging step
            // makes it uniformly 1/2; later levels average that same constant (no cumulative UNORM error).
            int xShift = 4 + role % 3, yShift = 4 + (role + 1) % 3;
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    int value = (((x >> xShift) ^ (y >> yShift) ^ (role / 3)) & 1) * 255;
                    color.setRGB(x, y, 0x789064);
                    height.getRaster().setSample(x, y, 0, value);
                    orm.setRGB(x, y, (shipped.getRGB(x, y) & 0xff00ff) | value << 8);
                }
            }
            assertTrue(javax.imageio.ImageIO.write(color, "png", sculpt.resolve(name + "_albedo.png").toFile()));
            assertTrue(javax.imageio.ImageIO.write(height, "png", sculpt.resolve(name + "_heightmap.png").toFile()));
            assertTrue(javax.imageio.ImageIO.write(orm, "png", sculpt.resolve(name + "_orm.png").toFile()));
            BoardMaterialFiles.loose(target.resolve("textures"), "terrain/surfaces/" + name, 512, 12 + role * 4);
        }
    }

    private static void checkSpatialRoughness(GpuShaderManager manager) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        System.out.println("Spatial roughness renderer: " + renderer);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
        assertTrue(renderer.contains(expectedRenderer), "Actual renderer must match " + expectedRenderer);
        String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
        // After the material and its cover blend, before the optional biome stage.
        String begin = "void main() {";
        String boundary = "    // biomeMaskFlag: the board has biomes or liquids of two palettes";
        String output = "fragColor = vec4(result, 1.0);";
        for (String anchor : List.of(begin, boundary, output)) {
            assertEquals(1, sculpt.split(java.util.regex.Pattern.quote(anchor), -1).length - 1,
                  "The probe must read the actual completed ordinary material: " + anchor);
        }
        sculpt = sculpt.replace(begin, begin + "\n    vec3 reviewBase = vec3(0.0);\n")
              .replace(boundary, "    reviewBase = vec3(finish.roughness, materialHeight, grass);\n" + boundary)
              .replace(output, "fragColor = natural && (ground || cliff)"
                    + " ? vec4(reviewBase, ground ? .25 : .5) : vec4(0.0, 0.0, 0.0, 1.0);");
        var changed = manager.apply(Map.of("terrain-sculpt.frag", sculpt));
        assertTrue(changed.success(), changed.message());
        var terrain = new GpuTerrain();
        try (var surface = new GpuSceneTestOutput()) {
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.update(roughnessScene());
            var center = BoardGeometry.center(new Coords(3, 3), 1);
            var camera = new OrthographicCamera();
            // Finer than the close view (GpuMaterialCache.CLOSE_PIXEL), whose mips hold plain means as the height's do;
            // coarser ones widen the roughness by the normals' spread (GpuAssets.mipmaps).
            camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(.05f);
            camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(.05f);
            camera.near = 1; camera.far = 3000;
            var report = new ArrayList<Map<String, Object>>();
            for (boolean angled : new boolean[] { false, true }) {
                camera.position.set(center).add(angled ? 500 : 0, angled ? -600 : 0, 800);
                camera.up.set(0, angled ? 0 : 1, angled ? 1 : 0);
                camera.lookAt(center); camera.update();
                float[] reference = null;
                for (boolean normals : new boolean[] { true, false, true }) {
                    terrain.setNormalMaps(normals);
                    render(surface, terrain, camera); // Complete first use before the exact frozen repeat.
                    float[] pixels = surface.linearPixels();
                    render(surface, terrain, camera);
                    assertArrayEquals(pixels, surface.linearPixels(), "Frozen provider output must repeat exactly");
                    int ground = 0, cliff = 0, mixed = 0, apart = 0;
                    float minimum = 1, maximum = 0, maxError = 0;
                    var levels = new java.util.HashSet<Integer>();
                    // Binary/half-valued dyadic mips: at most one R8 code, one half step for each output,
                    // plus float arithmetic. Convex sampling and the actual normalized role/family weights
                    // cannot amplify this bound. This is chosen from formats, never fitted to rendered error.
                    double tolerance = 1.0 / 255 + 2.0 / 1024 + .00001;
                    for (int i = 0; i < pixels.length; i += 4) {
                        for (int channel = 0; channel < 4; channel++) {
                            assertTrue(Float.isFinite(pixels[i + channel]), "Every diagnostic channel is finite");
                        }
                        if (reference != null) {
                            assertEquals(reference[i + 3], pixels[i + 3], 0, "Normal detail preserves every coverage tag");
                        }
                        if (pixels[i + 3] != .25f && pixels[i + 3] != .5f) { continue; }
                        if (pixels[i + 3] == .25f) { ground++; } else { cliff++; }
                        if (pixels[i + 2] > .05f && pixels[i + 2] < .95f) { mixed++; }
                        assertTrue(pixels[i] >= 0 && pixels[i] <= 1, "Raw authored roughness stays bounded");
                        // Where a projection reads past the close level (steep and grazing faces), the normals'
                        // spread widens the roughness mips (GpuAssets.mipmaps): only those few may part.
                        if (Math.abs(pixels[i + 1] - pixels[i]) > tolerance) { apart++; }
                        if (reference != null) {
                            for (int channel = 0; channel < 4; channel++) {
                                assertEquals(reference[i + channel], pixels[i + channel], 0,
                                      "Normal detail cannot change base roughness, height, mixture or coverage");
                            }
                        }
                        minimum = Math.min(minimum, pixels[i]); maximum = Math.max(maximum, pixels[i]);
                        maxError = Math.max(maxError, Math.abs(pixels[i] - pixels[i + 1]));
                        levels.add(Math.round(pixels[i] * 255));
                    }
                    assertTrue(ground > 100 && mixed > 100, "Actual ground and mixed grass/rock must be visible");
                    assertTrue(apart <= .001 * (ground + cliff),
                          "Independent roughness array follows actual height projection/mix: " + apart + " of " + (ground + cliff));
                    assertTrue(!angled || cliff > 100, "The angled frame must contain actual projected cliff samples");
                    assertTrue(maximum - minimum > .25 && levels.size() > 16,
                          "The authored map must remain spatial, not a scalar/average fallback");
                    report.add(Map.of("angled", angled, "normalMaps", normals, "ground", ground, "cliff", cliff,
                          "mixed", mixed, "min", minimum, "max", maximum, "levels", levels.size(), "maxError", maxError));
                    System.out.println("Spatial roughness apart: " + apart + " of " + (ground + cliff));
                    String destination = System.getProperty("megamek.gpu.screenshots");
                    if (destination != null && reference == null) {
                        File image = new File(destination, "spatial-roughness/" + (angled ? "angled" : "top") + ".png");
                        assertTrue(image.getParentFile().isDirectory() || image.getParentFile().mkdirs());
                        surface.display(); GpuReviewFrame.save(image);
                    }
                    if (reference == null) { reference = pixels; }
                }
            }
            System.out.println("Spatial roughness: " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report));
            String destination = System.getProperty("megamek.gpu.screenshots");
            if (destination != null) {
                var path = java.nio.file.Path.of(destination, "spatial-roughness", "samples.json");
                java.nio.file.Files.createDirectories(path.getParent());
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            try { terrain.dispose(); }
            finally { assertTrue(manager.apply(Map.of()).success(), "Restore production sources"); }
        }
    }

    private static BoardScene roughnessScene() {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var mixed = new BoardSurfaceBlend.Cover(.5f, 0, 0, .5f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var family = x < 3 ? BoardScene.Surface.GRASS : BoardScene.Surface.ROCK;
                tiles.add(new BoardScene.Tile(new Coords(x, y), x < 3 ? 3 : 0, -1, false, 0,
                      family, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true,
                      BoardRoad.Kind.NONE, BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false, 0, false,
                      x == 2 || x == 3 ? mixed : BoardSurfaceBlend.solid(family)));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void checkMaterials(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        try (var surface = new GpuSceneTestOutput()) {
            var camera = new OrthographicCamera();
            var center = BoardGeometry.center(new Coords(3, 3), 0);
            camera.position.set(center).add(0, 0, 5000);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            camera.near = 1;
            camera.far = 10000;
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            var outcropFailures = new ArrayList<String>();
            for (String material : List.of("grass", "dirt", "sand", "rock", "concrete", "snow", "lunar", "fungus",
                  "desert", "mars", "volcano", "tropical", "crust", "lava")) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene(material));
                for (float footprint : new float[] { .1f, .8f, 2f }) {
                    camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(footprint);
                    camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(footprint);
                    camera.update();
                    assertTrue(manager.apply(Map.of()).success());
                    byte[] original = render(surface, terrain, camera);
                    if (Boolean.getBoolean("megamek.gpu.materialMigrationReview")) {
                        File output = new File(System.getProperty("megamek.gpu.screenshots"), "material-families");
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuReviewFrame.save(new File(output, material + "-" + footprint + ".png"));
                    }
                    if (List.of("desert", "mars", "volcano", "dirt", "rock", "lunar").contains(material)
                          && footprint < 1f) {
                        checkPlateauOutcrops(surface, manager, terrain, camera, original, material, footprint, outcropFailures);
                    }
                    var changed = manager.apply(changedMaps());
                    assertTrue(changed.success(), changed.message());
                    byte[] altered = render(surface, terrain, camera);
                    String description = material + " at " + footprint + " m/pixel";
                    double difference = difference(original, altered);
                    System.out.println(description + ": detail-map difference " + difference);
                    if (footprint < .2f) {
                        // Molten emission dominates its reflected light; even a large normal change is subtle.
                        assertTrue(difference > .001, description + " still uses its normal map");
                        if (!material.equals("crust") && !material.equals("lava")) {
                            String lighting = GpuShaderSource.readDisk("surface-shading.glsl");
                            changed = manager.apply(Map.of("surface-shading.glsl", lighting.replace(
                                  "sheen = sun * surfaceReflectance(normal, light, view, roughness, f0) * multiscatter * hollows;",
                                  "sheen = vec3(0.0);")));
                            assertTrue(changed.success(), changed.message());
                            assertTrue(difference(original, render(surface, terrain, camera)) > .001,
                                  description + " reflects light even when dry");
                        }
                    }
                }
            }
            // Both fungal skins need visible weathering, while retaining violet lowlands and cyan raised crust.
            int lowlandCrust = -1;
            for (int elevation : new int[] { -3, 0, 3 }) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene("fungus", elevation));
                camera.position.set(BoardGeometry.center(new Coords(3, 3), elevation)).add(0, 0, 5000);
                camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(.4f);
                camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(.4f);
                camera.update();
                byte[] original = render(surface, terrain, camera);
                String review = System.getProperty("megamek.gpu.screenshots");
                if (review != null) {
                    File output = new File(review, "fungal-elevations");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    GpuReviewFrame.save(new File(output, "level-" + elevation + ".png"));
                }
                double weathered = visiblePatchFraction(surface, manager, terrain, camera, original, "fungus");
                System.out.println("fungus at level " + elevation + ": weathering on " + weathered + " of visible ground");
                if (weathered <= .05) { outcropFailures.add("Fungal weathering must remain visible at level " + elevation); }
                // Elevation selects a pigment, independent of the atmosphere's blue sky or direct-light color.
                // Read the completed material under neutral unit illumination while retaining its weathering.
                var paletteProbe = manager.apply(Map.of("terrain-sculpt.frag", albedoProbe()));
                assertTrue(paletteProbe.success(), paletteProbe.message());
                byte[] pigment = render(surface, terrain, camera);
                // Blue-violet lowland skin keeps red near green; the cyan crust has green well above red.
                int violet = 0, cyan = 0;
                for (int i = 0; i < pigment.length; i += 4) {
                    int red = pigment[i] & 255, green = pigment[i + 1] & 255;
                    if (red + 5 > green) { violet++; }
                    if (green > red + 20) { cyan++; }
                }
                // Lowlands stay violet; raised ground grows the crust, broken by tissue gaps and slopes.
                if (elevation == 0) { lowlandCrust = cyan; }
                assertTrue(elevation <= 0 ? violet > cyan : cyan > 3 * lowlandCrust,
                      "Fungus must retain its elevation-specific pigment at level " + elevation
                            + ": violet=" + violet + ", cyan=" + cyan + ", level 0 cyan=" + lowlandCrust);
            }
            assertTrue(outcropFailures.isEmpty(), String.join("\n", outcropFailures));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); }
    }

    /** Mineral caps must visibly break up a clear plateau at close and normal map zoom, with quiet ground left. */
    private static void checkPlateauOutcrops(GpuSceneTestOutput surface, GpuShaderManager manager, GpuTerrain terrain,
          OrthographicCamera camera, byte[] original, String family, float footprint, List<String> failures) {
        double visible = visiblePatchFraction(surface, manager, terrain, camera, original, family);
        String description = family + " at " + footprint + " m/pixel";
        System.out.println(description + ": outcrops on " + visible + " of visible ground");
        if (visible <= .05) { failures.add(description + " must retain visible bedrock patches (" + visible + ")"); }
        if (visible >= .55) { failures.add(description + " must retain broad areas of ground cover (" + visible + ")"); }
    }

    private static double visiblePatchFraction(GpuSceneTestOutput surface, GpuShaderManager manager, GpuTerrain terrain,
          OrthographicCamera camera, byte[] original, String family) {
        String material = GpuShaderSource.readDisk("terrain-materials.glsl");
        // Volcanic programs mix their roles in naturalMaterialFor, every other family in cliffMaterial.
        String natural = "vec4 roles = vec4(cover, soil,", cliff = "vec4 roles = vec4(cover, clamp(mantle,";
        String coverOnly = GpuShaderSource.replaceOnce(GpuShaderSource.replaceOnce(material, natural,
              "cover = 1.0; soil = 0.0; deposit = 0.0; dust = 0.0; " + natural, "volcanic full cover"), cliff,
              "cover = 1.0; mantle = 0.0; deposit = 0.0; " + cliff, "ordinary full cover");
        if (family.equals("rock")) {
            // Ground fines and exposed granite may have similar pigment. Read the final production role
            // weights instead of requiring an authored color gain, a light direction or a display contrast.
            // Ordinary rock takes cliffMaterial; level tops read the rock map for bedrock too (u_flatRock).
            String emission = "    result.finish = mixFinish(surfaceA, surfaceB, share);";
            String roles = emission + "\n    result.finish.emission = vec3(roleShare.w, roleShare.x, 0.0);";
            assertTrue(material.contains(emission), "The probe reads completed cliff-material roles");
            String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
            // This diagnostic deliberately stores non-material data in emission, even with plain authored finishes.
            sculpt = sculpt.replace("#define SCORCH_IN_MATERIAL",
                  "#define SCORCH_IN_MATERIAL\n#undef plainSculptFinishFlag");
            String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
            String output = "fragColor = vec4(result, 1.0);";
            assertTrue(sculpt.contains(result) && sculpt.contains(output), "The role probe uses the final scene writer");
            // Ground rock is opaque. An exact alpha tag excludes boulders, cliffs, clear color and other props;
            // alpha is not scaled, while linearPixels restores the production RGB storage units once.
            // Keep the lighting call: the shaders include the lighting library only where it is used.
            sculpt = sculpt.replace(result, result + "\n    result = finish.emission;")
                  .replace(output, "fragColor = vec4(result, ground ? .25 : 1.0);");
            var probe = manager.apply(Map.of("terrain-sculpt.frag", sculpt,
                  "terrain-materials.glsl", material.replace(emission, roles)));
            assertTrue(probe.success(), probe.message());
            render(surface, terrain, camera);
            float[] selected = linearPixels(surface);
            probe = manager.apply(Map.of("terrain-sculpt.frag", sculpt,
                  "terrain-materials.glsl", coverOnly.replace(emission, roles)));
            assertTrue(probe.success(), probe.message());
            render(surface, terrain, camera);
            float[] cover = linearPixels(surface);
            int ground = 0, patches = 0;
            for (int i = 0; i < selected.length; i += 4) {
                for (int channel = 0; channel < 4; channel++) {
                    assertTrue(Float.isFinite(selected[i + channel]) && Float.isFinite(cover[i + channel]),
                          "Material-role diagnostics must remain finite before coverage classification");
                }
                if (selected[i + 3] != .25f) { continue; }
                ground++;
                assertEquals(.25f, cover[i + 3], 0, "The control retains the same visible geometry");
                assertEquals(0f, cover[i], .00001f, "Forcing cover must remove bedrock contribution");
                assertEquals(1f, cover[i + 1], .001f, "Forcing cover must leave complete cover contribution");
                assertTrue(selected[i] >= 0 && selected[i] <= 1 && selected[i + 1] >= 0 && selected[i + 1] <= 1,
                      "Selected material weights must remain bounded");
                if (selected[i] > .5f) { patches++; }
            }
            assertTrue(ground > 100, "The rock scene must render visible material-role samples");
            return patches / (double) ground;
        }
        var changed = manager.apply(Map.of("terrain-materials.glsl", coverOnly));
        assertTrue(changed.success(), changed.message());
        byte[] hardpan = render(surface, terrain, camera);
        int ground = 0, patches = 0;
        for (int i = 0; i < original.length; i += 4) {
            int plain = (hardpan[i] & 255) + (hardpan[i + 1] & 255) + (hardpan[i + 2] & 255);
            if (plain <= 30) { continue; } // Exclude the clear color, but retain dark fungal lowlands.
            ground++;
            int contrast = 0;
            for (int channel = 0; channel < 3; channel++) {
                int difference = (original[i + channel] & 255) - (hardpan[i + channel] & 255);
                contrast += family.equals("desert") ? difference : Math.abs(difference);
            }
            // Fungal depressions are deliberately dark. Judge their contrast relative to their own skin;
            // mineral caps still need a clear absolute difference at normal map zoom.
            if (contrast > (family.equals("fungus") ? plain * .20 : 36)) { patches++; }
        }
        assertTrue(ground > 100, "The " + family + " scene must render visible ground");
        return patches / (double) ground;
    }

    private static String albedoProbe() {
        String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
        assertTrue(sculpt.contains(result), "The palette probe must read the actual completed albedo");
        return sculpt.replace(result, result + "\nresult = surface.albedo;\n");
    }

    private static float[] linearPixels(GpuSceneTestOutput surface) {
        var target = surface.scene();
        target.begin();
        var buffer = BufferUtils.newFloatBuffer(target.getWidth() * target.getHeight() * 4);
        Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        float[] pixels = new float[buffer.capacity()];
        buffer.get(pixels);
        for (int i = 0; i < pixels.length; i++) {
            if (i % 4 != 3) { pixels[i] /= GpuSceneRadiance.SCALE; }
        }
        target.end();
        return pixels;
    }

    private static Map<String, String> changedMaps() {
        var result = new HashMap<String, String>();
        // C1 separates color and numerical arrays. Perturb only actual normal/AO reads, retaining their
        // projection and coverage branches; odd semantic IDs no longer pass through color helpers.
        for (String file : List.of("terrain-materials.glsl", "terrain-sculpt.frag")) {
            String source = GpuShaderSource.readDisk(file);
            String changed = source.replaceAll(
                  "\\b(?:materialTexel|fungusTexel|translatedTexel|cliffTexel)\\(u_terrainData,",
                  "changedNormalTexel(u_terrainData,");
            assertTrue(!source.equals(changed), file + " must alter actual numerical-array sample sites");
            result.put(file, changed);
        }
        String projection = GpuShaderSource.readDisk("terrain-projection.glsl");
        String normal = "vec4 normalTexel(float layer, vec2 uv) {";
        assertTrue(projection.contains(normal), "Projected normal-map diagnostic must be installed");
        result.put("terrain-projection.glsl", """
              vec4 changedNormalTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
                  return vec4(.95, .05, .65, .1);
              }
              vec4 changedNormalTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
                  return changedNormalTexel(maps, layer, uv, gradient);
              }
              """ + projection.replace(normal, normal + " return vec4(.95, .05, .65, .1);"));
        String magma = GpuShaderSource.readDisk("terrain-magma.glsl");
        String magmaSample = "vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy) {";
        assertTrue(magma.contains(magmaSample), "Volcanic numerical-map diagnostic must be installed");
        result.put("terrain-magma.glsl", magma.replace(magmaSample, magmaSample
              + " if (map == MAGMA_NORMAL) return vec4(.95, .05, .65, .1);"));
        return result;
    }

    private static byte[] render(GpuSceneTestOutput surface, GpuTerrain terrain, OrthographicCamera camera) {
        surface.draw(terrain, camera, false);
        return ScreenUtils.getFrameBufferPixels(false);
    }

    private static double difference(byte[] a, byte[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) { total += Math.abs((a[i] & 255) - (b[i] & 255)); }
        return total / (double) a.length;
    }

    private static BoardScene scene(String material) {
        return scene(material, 0);
    }

    private static BoardScene scene(String material, int elevation) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        BoardLiquid liquid = switch (material) {
            case "crust" -> new BoardLiquid(BoardLiquid.Kind.MAGMA_CRUST, "", 0);
            case "lava" -> new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
            default -> BoardLiquid.NONE;
        };
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), elevation, -1, false, 0,
                      liquid.kind() == BoardLiquid.Kind.NONE ? BoardScene.Surface.valueOf(material.toUpperCase(Locale.ROOT))
                            : BoardScene.Surface.SAND, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
