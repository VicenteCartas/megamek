/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual submitted triangle counts, shared render passes, and pixel comparisons across the shipped detail levels. */
@Tag("on-demand")
class GpuTreeLodSmokeTest {
    private static final List<String> TREES = java.util.stream.Stream.concat(
          BoardTreeDistributionTest.IMPORTED_TREES.stream(), java.util.stream.Stream.of(
                "vegetation/tree-volcano-crown", "vegetation/tree-volcano-forked", "vegetation/tree-volcano-spire")).toList();
    /** A projected diameter within each level's band, past the hysteresis of the thresholds on both sides. */
    static final float[] PIXELS = { 200, 60, 32, 12 };

    @Test
    void changesSubmittedGeometryWithoutChangingPickingOpacityOrShadows() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                if (Boolean.getBoolean("megamek.gpu.reviewImpostorMaterials")) {
                    try { reviewImpostorMaterials(); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                    return;
                }
                GpuAssets assets = new GpuAssets();
                GpuTerrain terrain = new GpuTerrain();
                ModelBatch depth = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config()));
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                Model occupant = new ModelBuilder().createBox(4, 4, 40, new Material(), VertexAttributes.Usage.Position);
                try {
                    profiler.enable();
                    if (Boolean.getBoolean("megamek.gpu.reviewAllImpostors")) {
                        reviewAllImpostors(assets);
                        return;
                    }
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    Coords coords = new Coords(0, 0);
                    Vector3 center = BoardGeometry.center(coords, 0);
                    camera.center(new Vector3(center).add(0, 0, 18));
                    ModelInstance unit = new ModelInstance(occupant);
                    unit.transform.setToTranslation(new Vector3(center).add(0, 0, 18));
                    for (String name : TREES) {
                        BoardScene scene = scene(name);
                        terrain.update(scene);
                        int[] triangles = new int[TreeLod.LEVELS];
                        for (int level = 0; level < TreeLod.LEVELS; level++) {
                            Model model = assets.lodModel(name, level);
                            for (var part : model.meshParts) {
                                triangles[level] += part.size / 3;
                            }
                            assertEquals(name.contains("snow"), model.getMaterial("snow") != null
                                  || model.getMaterial("canopy-snow-cutout") != null, name);
                            for (Material material : model.materials) {
                                if (!material.id.endsWith("-cutout")) { continue; }
                                assertEquals(authoredForest(name) ? .3f : .5f,
                                      material.get(FloatAttribute.class, FloatAttribute.AlphaTest).value);
                                assertTrue(!material.get(BlendingAttribute.class, BlendingAttribute.Type).blended,
                                      "Cutout crowns write opaque depth: " + name);
                                assertTrue(material.has(TextureAttribute.Normal) && material.has(TextureAttribute.Ambient),
                                      "Cutout crowns carry relief and packed leaf surface maps: " + name);
                            }
                        }
                        boolean bareBranches = name.startsWith("vegetation/tree-dead");
                        boolean forest = authoredForest(name);
                        assertTrue(triangles[0] <= (forest ? 32000 : bareBranches ? 12000 : name.startsWith("vegetation/cactus") ? 3600 : 1600)
                              && triangles[1] <= (forest ? 10000 : bareBranches ? 1800 : name.startsWith("vegetation/cactus") ? 950 : 410)
                              && triangles[2] <= (forest ? 2600 : bareBranches ? 180 : name.startsWith("vegetation/cactus") ? 420 : 96) && triangles[3] <= 12, name);
                        if (forest) {
                            for (Material material : assets.model(name).materials) {
                                if (material.id.startsWith("bark")) {
                                    assertTrue(material.has(TextureAttribute.Normal)
                                          && material.has(TextureAttribute.Ambient), "Authored bark surface: " + name);
                                }
                            }
                        }
                        BoundingBox bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                        assertEquals(30, bounds.getDepth(), .001f, "Plant GLBs must not contain flattened geometry: " + name);
                        float diameter = bounds.getDimensions(new Vector3()).scl(1, 1, 36 / bounds.getDepth()).len();
                        BoardGeometry.Hit nearHit = terrain.hit(scene, new Ray(new Vector3(center).add(0, 0, 1000),
                              new Vector3(0, 0, -1)));
                        int nearCount = 0;
                        int nearShadowCount = 0;
                        int nearDepthCount = 0;
                        int shadowLevel = 0;
                        float shadowZoom = 0;
                        int[] fadeCounts = new int[PIXELS.length];
                        for (int level : new int[] { 0, 1, 2, 3, 0, 3, 2, 1, 0 }) {
                            camera.camera.zoom = level == 0 ? 0.1f : zoomForSize(diameter, PIXELS[level]);
                            camera.update();
                            terrain.animate(0, List.of(), 1);
                            // A level change alone keeps the shadow map until the next refit; redraw it to count
                            // the selected level's geometry.
                            terrain.refreshShadows();
                            int shadowCount = count(profiler, () -> terrain.renderShadows(camera.camera, List.of()));
                            if (level == 0 && nearCount == 0) {
                                int submitted = count(profiler, () -> terrain.render(camera.camera));
                                int draws = profiler.getDrawCalls();
                                int alone = colour(profiler, terrain, camera);
                                System.out.printf(Locale.ROOT, "%s near level: the depth pre-pass adds %d vertices and"
                                      + " %d draws to the colour pass's %d and %d%n", name, submitted - alone,
                                      draws - profiler.getDrawCalls(), alone, profiler.getDrawCalls());
                            }
                            int colorCount = colour(profiler, terrain, camera);
                            int depthCount = count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth));
                            if (level == 0) {
                                nearCount = colorCount;
                                nearShadowCount = shadowCount;
                                nearDepthCount = depthCount;
                            }
                            assertEquals(3 * (triangles[0] - triangles[level]), nearCount - colorCount, name);
                            assertEquals(3 * (triangles[0] - triangles[level]), nearDepthCount - depthCount,
                                  "Depth uses the selected tree geometry: " + name);
                            // The map's own level follows its texel, which grows and shrinks with the view's zoom.
                            int drawn = drawnLevel(nearShadowCount - shadowCount, triangles);
                            assertTrue(drawn >= 0, "Shadow draws one level of the tree: " + name);
                            assertTrue(camera.camera.zoom > shadowZoom ? drawn >= shadowLevel : drawn <= shadowLevel,
                                  "Shadow detail falls as the map's texel grows: " + name);
                            shadowLevel = drawn;
                            shadowZoom = camera.camera.zoom;
                            assertEquals(nearHit, terrain.hit(scene, new Ray(new Vector3(center).add(0, 0, 1000),
                                  new Vector3(0, 0, -1))), "Picking remains stable across detail levels");
                            // The transparent pass holds the crowns' faded texels (GpuCutoutFade), the same before and after.
                            fadeCounts[level] = count(profiler, () -> terrain.renderTransparent(camera.camera));
                            terrain.animate(0, List.of(unit), 0);
                            assertEquals(fadeCounts[level], count(profiler, () -> terrain.renderTransparent(camera.camera)),
                                  "Trees must stay in the opaque pass when a unit enters");
                            assertEquals(colorCount, colour(profiler, terrain, camera));
                            assertEquals(depthCount,
                                  count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth)));
                        }
                        // An occupied tree stays opaque through both LoD thresholds, including camera depth
                        // for the unit outline. Its shadow is one whole level of the tree.
                        for (int level : new int[] { 1, 2, 3, 0 }) {
                            camera.camera.zoom = level == 0 ? 0.1f : zoomForSize(diameter, PIXELS[level]);
                            camera.update();
                            terrain.refreshShadows();
                            assertTrue(drawnLevel(nearShadowCount
                                  - count(profiler, () -> terrain.renderShadows(camera.camera, List.of())), triangles) >= 0);
                            assertEquals(fadeCounts[level], count(profiler, () -> terrain.renderTransparent(camera.camera)));
                            assertEquals(nearDepthCount - 3 * (triangles[0] - triangles[level]),
                                  count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth)));
                            assertEquals(0, count(profiler, () -> terrain.renderShadows(camera.camera, List.of())),
                                  "An unchanged camera and scene must reuse the shadow map");
                        }
                    }
                    profiler.disable();
                    compareLevels(assets, TREES);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    profiler.disable();
                    occupant.dispose();
                    depth.dispose();
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static boolean authoredForest(String name) {
        return name.startsWith("vegetation/tree") && !name.startsWith("vegetation/tree-dead")
              || name.startsWith("vegetation/willow") || name.startsWith("vegetation/birch");
    }

    /** Opt-in external pilot: reuse the complete review with selected immutable assets/source drafts. */
    private static void reviewImpostorMaterials() throws Exception {
        File previousData = Configuration.dataDir();
        boolean previousSpecularAA = GpuEnvironmentLighting.specularAntialiasing;
        var drafts = new java.util.LinkedHashMap<String, String>();
        String sourceRoot = System.getProperty("megamek.gpu.impostorSources");
        if (sourceRoot != null) {
            for (String name : List.of("terrain-foliage.frag", "tree-instances.glsl")) {
                drafts.put(name, Files.readString(java.nio.file.Path.of(sourceRoot, name)));
            }
        }
        var manager = new GpuShaderManager();
        try {
            String dataRoot = System.getProperty("megamek.gpu.impostorData");
            if (dataRoot != null) { Configuration.setDataDir(new File(dataRoot)); }
            GpuEnvironmentLighting.specularAntialiasing = !Boolean.getBoolean("megamek.gpu.impostorAAOff");
            var applied = manager.apply(drafts);
            assertTrue(applied.success(), applied.message());
            manager.run(() -> {
                var assets = new GpuAssets();
                try {
                    reviewAllImpostors(assets);
                } catch (Exception error) { throw new IllegalStateException(error); }
                finally { assets.dispose(); }
            });
        } finally {
            try { manager.close(); }
            finally {
                Configuration.setDataDir(previousData);
                GpuEnvironmentLighting.specularAntialiasing = previousSpecularAA;
            }
        }
    }

    /** Optional authored-material review: every effective impostor, at one matched native footprint. */
    private static void reviewAllImpostors(GpuAssets assets) throws Exception {
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer");
        if (expectedRenderer != null) {
            assertTrue(Gdx.gl.glGetString(GL20.GL_RENDERER).contains(expectedRenderer), "Requested review GPU");
        }
        String diagnosticSources = System.getProperty("megamek.gpu.impostorSources");
        boolean frameDiagnostic = diagnosticSources != null && Files.readString(
              java.nio.file.Path.of(diagnosticSources, "terrain-foliage.frag")).contains("Diagnostic: mapped frame validity");
        boolean scaledMirrored = Boolean.getBoolean("megamek.gpu.impostorScaledMirrored");
        var root = new File(Configuration.dataDir(), "models/board");
        // Tool data beside the game's sources: mm-data/tools/board-models, never shipped.
        var manifest = java.nio.file.Path.of("../../mm-data/tools/board-models/manifest.json");
        var catalog = new JsonReader().parse(Files.readString(manifest));
        List<String> names = new java.util.ArrayList<>();
        for (var entry = catalog.child; entry != null; entry = entry.next) {
            var lods = entry.get("lods");
            if (lods != null && lods.size == 4) { names.add(entry.name); }
        }
        assertEquals(84, names.size(), "Review the complete effective four-LOD catalog, including snow and shrub variants");
        names.sort(String::compareTo);
        String selected = System.getProperty("megamek.gpu.impostorNames");
        if (selected != null) {
            List<String> requested = java.util.Arrays.stream(selected.split(",")).map(String::trim).toList();
            assertTrue(!requested.isEmpty() && names.containsAll(requested), "Requested actual catalog entries");
            assertEquals(requested.size(), new java.util.HashSet<>(requested).size(), "No repeated specimen names");
            names.retainAll(requested);
        }
        var destination = new File(System.getProperty("megamek.gpu.screenshots"), "impostor-all62").toPath();
        assertTrue(Files.notExists(destination), "Preserve previous review evidence");
        Files.createDirectories(destination);
        var preview = new GpuTerrain();
        var batchField = GpuTerrain.class.getDeclaredField("batch");
        batchField.setAccessible(true);
        ModelBatch batch = (ModelBatch) batchField.get(preview);
        var prepare = GpuTerrain.class.getDeclaredMethod("foliage", Model.class);
        prepare.setAccessible(true);
        var instances = new GpuTreeInstances((name, level) -> {
            try { return (Model) prepare.invoke(null, assets.lodModel(name, level)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        List<Object> rows = new java.util.ArrayList<>();
        List<Object> repairControls = new java.util.ArrayList<>();
        List<String> invalidFrameFailures = new java.util.ArrayList<>();
        var floats = BufferUtils.newFloatBuffer(128 * 128 * 4);
        try (var output = new GpuSceneTestOutput()) {
            for (float hour : new float[] { 13, 6.5f }) {
                var lighting = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                preview.setAtmosphere(lighting);
                if (Boolean.getBoolean("megamek.gpu.reviewImpostorMaterials")) {
                    preview.setNormalMaps(!Boolean.getBoolean("megamek.gpu.impostorMicroOff"));
                    preview.setWetness(Float.parseFloat(System.getProperty("megamek.gpu.impostorWetness", "0")));
                }
                // Borrow the production light/IBL attributes, without a shadow-map target. These isolated
                // specimen captures intentionally have no ground or shadow casters; no uninitialised depth read.
                var environment = new Environment();
                for (var attribute : preview.environment()) { environment.set(attribute); }
                assertNull(environment.shadowMap);
                for (String name : names) {
                    Model reference = assets.lodModel(name, 0);
                    var placement = new Matrix4();
                    if (scaledMirrored) { placement.rotate(Vector3.Z, 23).scale(1.7f, -1.7f, .6f); }
                    BoundingBox bounds = reference.calculateBoundingBox(new BoundingBox()).mul(placement);
                    Vector3 dimensions = bounds.getDimensions(new Vector3());
                    float diameter = dimensions.len();
                    assertTrue(Float.isFinite(diameter) && diameter > 0, "Finite real bounds: " + name);
                    var stand = new GpuTreeInstances.Stand();
                    stand.add(name, placement, 0);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(Float.parseFloat(System.getProperty("megamek.gpu.impostorYawDelta", "0")),
                          Float.parseFloat(System.getProperty("megamek.gpu.impostorTiltDelta", "0")));
                    camera.center(bounds.getCenter(new Vector3()));
                    camera.camera.zoom = zoomForSize(diameter, 26.4f);
                    camera.update();
                    float physicalDiameter = diameter * Gdx.graphics.getBackBufferHeight()
                          / Gdx.graphics.getHeight() / camera.camera.zoom;
                    assertEquals(26.4f, physicalDiameter, .001f, "Same real reference bounds and footprint for both LODs");
                    var source = RigidGlb.source(root, name);
                    // The authored-normal repair also affects forked LOD1. Keep its one daylight
                    // control separate from the unchanged62-entry LOD2/3 review matrix.
                    int[] levels = name.equals("vegetation/tree-volcano-forked") && hour == 13
                          ? new int[] { 1, 2, 3 } : new int[] { 2, 3 };
                    for (int level : levels) {
                        // Warm once to populate actual program/texture bindings; the animation clock stays zero.
                        foliageFrame(output, preview, instances, stand, level, batch, environment, camera);
                        BufferedImage frame = foliageFrame(output, preview, instances, stand, level, batch, environment, camera);
                        BufferedImage repeat = foliageFrame(output, preview, instances, stand, level, batch, environment, camera);
                        assertEquals(frame.getWidth(), repeat.getWidth());
                        assertEquals(frame.getHeight(), repeat.getHeight());
                        assertTrue(java.util.Arrays.equals(frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), null, 0,
                                    frame.getWidth()), repeat.getRGB(0, 0, repeat.getWidth(), repeat.getHeight(), null, 0,
                                    repeat.getWidth())), "Frozen whole-frame repeat: " + name + " LOD" + level);
                        int x = frame.getWidth() / 2 - 64, y = frame.getHeight() / 2 - 64;
                        output.scene().begin();
                        floats.clear();
                        Gdx.gl.glReadPixels(x, y, 128, 128, GL20.GL_RGBA, GL20.GL_FLOAT, floats);
                        float maximum = 0;
                        boolean[] invalidFrames = frameDiagnostic ? new boolean[128 * 128] : null;
                        for (int i = 0; i < floats.capacity(); i++) {
                            float value = floats.get(i);
                            if (!Float.isFinite(value)) {
                                var failure = new java.util.LinkedHashMap<String, Object>();
                                int pixel = i / 4;
                                failure.put("name", name);
                                failure.put("lod", level);
                                failure.put("hour", hour);
                                failure.put("channel", "RGBA".substring(i % 4, i % 4 + 1));
                                failure.put("storedValue", Float.toString(value));
                                failure.put("cropPixelFromBottomLeft", List.of(pixel % 128, pixel / 128));
                                failure.put("framePixelFromBottomLeft", List.of(x + pixel % 128, y + pixel / 128));
                                failure.put("framePixelFromTopLeft", List.of(x + pixel % 128,
                                      frame.getHeight() - 1 - y - pixel / 128));
                                failure.put("framebufferStatus", Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER));
                                failure.put("sceneAttachmentContract", "GpuHeatGlow.buffer: RGBA16F color, DEPTH_COMPONENT24 depth");
                                failure.put("sceneRadianceScale", GpuSceneRadiance.SCALE);
                                failure.put("renderer", Gdx.gl.glGetString(GL20.GL_RENDERER));
                                failure.put("physicalGlb", source.file().getName());
                                failure.put("physicalGlbSha256", reviewHash(source.file().toPath()));
                                failure.put("completedFrames", rows.size());
                                ImageIO.write(frame, "png", destination.resolve("failure-frame.png").toFile());
                                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                                      .writeValue(destination.resolve("failure.json").toFile(), failure);
                                assertTrue(Float.isFinite(value), "Finite scene sample: " + name + " LOD" + level
                                      + " hour " + hour + " channel " + i % 4 + " sample " + i / 4 + " value " + value);
                            }
                            if (i % 4 != 3) { maximum = Math.max(maximum, value / GpuSceneRadiance.SCALE); }
                            if (frameDiagnostic && i % 4 == 0) { invalidFrames[i / 4] = value / GpuSceneRadiance.SCALE > .5f; }
                        }
                        floats.clear();
                        floats.limit(128 * 128);
                        Gdx.gl.glReadPixels(x, y, 128, 128, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, floats);
                        int covered = 0, invalidFramePixels = 0;
                        byte[] coveredDepthMask = new byte[128 * 128];
                        for (int i = 0; i < 128 * 128; i++) {
                            float depth = floats.get(i);
                            assertTrue(Float.isFinite(depth) && depth >= 0 && depth <= 1, "Valid specimen depth");
                            if (depth < 1) {
                                covered++;
                                coveredDepthMask[i] = 1;
                                if (frameDiagnostic && invalidFrames[i]) { invalidFramePixels++; }
                            }
                        }
                        assertTrue(covered > 0, "The actual cutout must cover pixels: " + name + " LOD" + level);
                        output.scene().end();
                        output.pixels().begin();
                        String filename = name.substring(name.lastIndexOf('/') + 1) + "-" + (hour == 13 ? "day" : "low-sun") + "-lod" + level + ".png";
                        var image = destination.resolve(filename);
                        ImageIO.write(frame.getSubimage(x, y, 128, 128), "png", image.toFile());
                        var row = new java.util.LinkedHashMap<String, Object>();
                        row.put("name", name);
                        row.put("lod", level);
                        row.put("hour", hour);
                        row.put("image", filename);
                        row.put("pngSha256", reviewHash(image));
                        row.put("physicalGlb", source.file().getName());
                        row.put("physicalGlbSha256", reviewHash(source.file().toPath()));
                        row.put("variant", source.snow() ? "snow" : null);
                        row.put("referenceBoundsCenter", reviewVector(bounds.getCenter(new Vector3())));
                        row.put("referenceBoundsDimensions", reviewVector(dimensions));
                        row.put("lodBoundsDimensions", reviewVector(assets.lodModel(name, level)
                              .calculateBoundingBox(new BoundingBox()).mul(placement).getDimensions(new Vector3())));
                        row.put("lodProjectedBoundsPhysicalPixels", reviewProjectedBounds(assets.lodModel(name, level)
                              .calculateBoundingBox(new BoundingBox()).mul(placement), camera));
                        row.put("referenceDiameterPhysicalPixels", physicalDiameter);
                        row.put("cameraZoom", camera.camera.zoom);
                        row.put("mirroredScaled", scaledMirrored);
                        if (frameDiagnostic) { row.put("invalidFramePixels", invalidFramePixels); }
                        row.put("cameraPosition", reviewVector(camera.camera.position));
                        row.put("cameraDirection", reviewVector(camera.camera.direction));
                        row.put("directLinear", List.of(lighting.direct().r, lighting.direct().g, lighting.direct().b));
                        row.put("ambientLinear", List.of(lighting.ambient().r, lighting.ambient().g, lighting.ambient().b));
                        row.put("lightDirection", reviewVector(lighting.direction()));
                        row.put("coveredDepthPixels", covered);
                        row.put("coveredDepthMaskSha256", java.util.HexFormat.of().formatHex(
                              java.security.MessageDigest.getInstance("SHA-256").digest(coveredDepthMask)));
                        row.put("maximumSceneRadiance", maximum);
                        row.put("displayAdaptation", Boolean.getBoolean("megamek.gpu.reviewImpostorMaterials")
                              ? preview.lightAdaptation() : 1);
                        row.put("microNormalMaps", !Boolean.getBoolean("megamek.gpu.impostorMicroOff"));
                        row.put("specularAntialiasing", GpuEnvironmentLighting.specularAntialiasing);
                        row.put("wetness", Float.parseFloat(System.getProperty("megamek.gpu.impostorWetness", "0")));
                        if (level == 3) {
                            var material = assets.lodModel(name, level).getMaterial("impostor");
                            assertTrue(material != null, "Actual card material");
                            row.put("cardNormalMap", material.has(TextureAttribute.Normal));
                            row.put("cardSurfaceMap", material.has(TextureAttribute.Ambient));
                            if (frameDiagnostic && invalidFramePixels > 0) {
                                invalidFrameFailures.add(name + " LOD3 hour " + hour + ": " + invalidFramePixels);
                            }
                            if (System.getProperty("megamek.gpu.impostorData") != null) {
                                boolean expectedMaps = Boolean.parseBoolean(System.getProperty(
                                      "megamek.gpu.impostorExpectedMaps", "true"));
                                row.put("expectedCardMaterialMaps", expectedMaps);
                                assertEquals(expectedMaps, material.has(TextureAttribute.Normal),
                                      "Actual external card normal map for " + name);
                                assertEquals(expectedMaps, material.has(TextureAttribute.Ambient),
                                      "Actual external card surface map for " + name);
                            }
                        }
                        if (Boolean.getBoolean("megamek.gpu.impostorTiming")) {
                            row.put("specimenDrawTimings", specimenTimings(output, preview, instances, stand,
                                  level, batch, environment, camera));
                        }
                        if (level == 1) { repairControls.add(row); } else { rows.add(row); }
                    }
                }
            }
            assertEquals(names.size() * 4, rows.size());
            assertEquals(names.contains("vegetation/tree-volcano-forked") ? 1 : 0, repairControls.size(),
                  "One repaired forked LOD1 daylight control when that specimen is selected");
            String selectedSource = System.getProperty("megamek.gpu.impostorSources");
            if (selectedSource != null) {
                // Draft construction can fall back on an unsupported variant: reject that silently wrong control.
                var targetsField = GpuShaderManager.class.getDeclaredField("targets");
                targetsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var targets = (List<GpuShaderManager.Target>) targetsField.get(GpuShaderManager.current());
                boolean materialDraft = Files.readString(java.nio.file.Path.of(selectedSource, "terrain-foliage.frag"))
                      .contains("#define impostorMaterialFlag");
                boolean noSelfOcclusion = Files.readString(java.nio.file.Path.of(selectedSource, "terrain-foliage.frag"))
                      .contains("Diagnostic: remove only the unaccepted affine occlusion estimate");
                boolean commonCactus = Files.readString(java.nio.file.Path.of(selectedSource, "terrain-foliage.frag"))
                      .contains("Shared material lighting also covers waxy stems");
                int checked = 0;
                for (var target : targets) {
                    if (!target.files().contains("terrain-foliage.frag")) { continue; }
                    for (var linked : target.sources()) {
                        if (!linked.fragment().contains("uniform float u_foliage;")) { continue; }
                        assertEquals(materialDraft, linked.fragment().contains("#define impostorMaterialFlag"),
                              "Actual linked foliage source must use the requested material representation");
                        assertEquals(noSelfOcclusion, linked.fragment().contains(
                              "Diagnostic: remove only the unaccepted affine occlusion estimate"),
                              "Actual linked source must use the requested visibility control");
                        assertTrue(linked.fragment().contains(
                              "surfaceVariance = normalVariance(dFdx(baseNormal), dFdy(baseNormal));"),
                              "Actual foliage program retains geometric specular AA");
                        assertEquals(frameDiagnostic, linked.fragment().contains("Diagnostic: mapped frame validity"),
                              "Actual linked program must use requested frame diagnostic");
                        assertEquals(commonCactus, linked.fragment().contains("Shared material lighting also covers waxy stems"),
                              "Cactus cleanup must remain an explicit independent control");
                        checked++;
                    }
                }
                assertTrue(checked > 0, "Actual foliage programs were checked");
            }
            var report = new java.util.LinkedHashMap<String, Object>();
            report.put("renderer", Gdx.gl.glGetString(GL20.GL_RENDERER));
            report.put("manifestSha256", reviewHash(manifest));
            report.put("physicalFramebuffer", List.of(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()));
            report.put("animationClock", 0);
            report.put("placement", scaledMirrored ? "yaw23 scale(1.7,-1.7,.6)" : "identity");
            report.put("invalidFrameFailures", invalidFrameFailures);
            report.put("selectedNames", names);
            report.put("dataRoot", Configuration.dataDir().getAbsolutePath());
            report.put("sourceRoot", System.getProperty("megamek.gpu.impostorSources"));
            var sourceHashes = new java.util.LinkedHashMap<String, String>();
            String sourceRoot = System.getProperty("megamek.gpu.impostorSources");
            if (sourceRoot != null) {
                for (String name : List.of("terrain-foliage.frag", "tree-instances.glsl")) {
                    sourceHashes.put(name, reviewHash(java.nio.file.Path.of(sourceRoot, name)));
                }
            }
            report.put("sourceSha256", sourceHashes);
            report.put("timingScope", "Optional single-specimen draw including submission, excluding clear/display/readback; not board FPS or aggregate preparation cost.");
            report.put("scope", names.size() + " effective entries, actual production foliage shader, recorded model placement, matched LOD0 bounding-sphere diameter26.4physicalpx; LOD2/3, clear13:00/06:30. Shadowless specimens; no ground, fog or self-shadow equivalence claim. Native128px crops are unscaled. Source/runtime freeze belongs to the runner.");
            report.put("frames", rows);
            report.put("repairControls", repairControls);
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                  .writeValue(destination.resolve("review.json").toFile(), report);
            assertTrue(invalidFrameFailures.isEmpty(), "No visible carrier fallback: " + invalidFrameFailures);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            instances.dispose();
            preview.dispose();
        }
    }

    private static List<Float> reviewVector(Vector3 vector) { return List.of(vector.x, vector.y, vector.z); }

    private static List<Float> reviewProjectedBounds(BoundingBox bounds, BoardCamera camera) {
        float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = new Vector3((corner & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (corner & 2) == 0 ? bounds.min.y : bounds.max.y, (corner & 4) == 0 ? bounds.min.z : bounds.max.z);
            camera.camera.project(point, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            lowX = Math.min(lowX, point.x); highX = Math.max(highX, point.x);
            lowY = Math.min(lowY, point.y); highY = Math.max(highY, point.y);
        }
        return List.of(highX - lowX, highY - lowY);
    }

    private static String reviewHash(java.nio.file.Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }


    /** The colour pass's own geometry: the depth pre-pass, on by default, submits the trees a second time. */
    private static int colour(GLProfiler profiler, GpuTerrain terrain, BoardCamera camera) {
        Set<GpuTerrain.Prepass> prepass = GpuTerrain.depthPrepass;
        GpuTerrain.depthPrepass = EnumSet.noneOf(GpuTerrain.Prepass.class);
        try { return count(profiler, () -> terrain.render(camera.camera)); }
        finally { GpuTerrain.depthPrepass = prepass; }
    }

    /**
     * The level whose geometry a pass leaves out against the near level ({@code fewer} vertices fewer), or -1 when none:
     * the shadow map draws one whole level of its own, chosen by its texel (GpuTerrain.renderDepth).
     */
    static int drawnLevel(int fewer, int[] triangles) {
        for (int level = 0; level < triangles.length; level++) {
            if (fewer == 3 * (triangles[0] - triangles[level])) { return level; }
        }
        return -1;
    }

    private static int count(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        draw.run();
        return (int) profiler.getVertexCount().total;
    }

    private static BoardScene scene(String name) {
        BufferedImage ground = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ground.getHeight(); y++) {
            for (int x = 0; x < ground.getWidth(); x++) {
                ground.setRGB(x, y, 0xff6b8255);
            }
        }
        BoardScene.Tile tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS, null,
              List.of(new BoardScene.Feature(name, 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE)),
              List.of());
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }

    /** The shipped near mesh is the coverage reference for its coarser detail levels. */
    static void compareLevels(GpuAssets assets, List<String> names) throws Exception {
        Environment environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0.55f, 0.58f, 0.62f, 1));
        environment.add(new DirectionalLight().set(0.55f, 0.53f, 0.48f, -24, -30, -18));
        ModelBatch batch = new ModelBatch();
        try {
            for (String name : names) {
                Model near = assets.lodModel(name, 0);
                for (float tilt : new float[] { 0, 54.73561f, 80 }) {
                    for (int bearing = 0; bearing < 360; bearing += 90) {
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.orbit(bearing, tilt);
                        camera.center(new Vector3(0, 0, 18));
                        camera.zoom(0.001f);
                        boolean cutout = near.getMaterial("canopy-cutout") != null
                              || near.getMaterial("canopy-snow-cutout") != null
                              || near.getMaterial("flower-cutout") != null;
                        if (cutout) {
                            BoundingBox crown = near.calculateBoundingBox(new BoundingBox());
                            camera.zoom(zoomForSize(crown.getDimensions(new Vector3()).len() * 1.2f, 150));
                            int nearCoverage = coverage(render(batch, environment, assets.lodModel(name, 0), camera));
                            assertTrue(nearCoverage > 100, "The cutout crown must actually draw: " + name);
                            for (int level = 1; level <= 2; level++) {
                                int coverage = coverage(render(batch, environment, assets.lodModel(name, level), camera));
                                double retained = (double) coverage / nearCoverage;
                                assertTrue(retained >= .67 && retained <= 1.5,
                                      name + " LOD" + level + " cutout coverage=" + retained
                                            + " tilt=" + tilt + " bearing=" + bearing);
                            }
                        }
                        if (tilt == 54.73561f && bearing == 0) {
                            camera.zoom(0.001f);
                            BufferedImage nearFrame = render(batch, environment, near, camera);
                            BufferedImage medium = render(batch, environment, assets.lodModel(name, 1), camera);
                            BufferedImage far = render(batch, environment, assets.lodModel(name, 2), camera);
                            BufferedImage comparison = new BufferedImage(nearFrame.getWidth() * 3, nearFrame.getHeight(),
                                  BufferedImage.TYPE_INT_RGB);
                            var graphics = comparison.createGraphics();
                            try {
                                graphics.drawImage(nearFrame, 0, 0, null);
                                graphics.drawImage(medium, nearFrame.getWidth(), 0, null);
                                graphics.drawImage(far, nearFrame.getWidth() * 2, 0, null);
                                graphics.setColor(java.awt.Color.WHITE);
                                graphics.drawString("Near / minimum zoom 0.1", 20, 30);
                                graphics.drawString("Medium / zoom 0.1 (comparison only)", nearFrame.getWidth() + 20, 30);
                                graphics.drawString("Far / zoom 0.1 (comparison only)", nearFrame.getWidth() * 2 + 20, 30);
                            } finally {
                                graphics.dispose();
                            }
                            File directory = new File(System.getProperty("megamek.gpu.screenshots"));
                            assertTrue(directory.isDirectory() || directory.mkdirs());
                            ImageIO.write(comparison, "png", new File(directory, "tree-detail-" + name.replace('/', '-') + ".png"));
                        }
                    }
                }
            }
            compareDistantLevels(assets, environment, names);
        } finally {
            batch.dispose();
        }
    }

    /** Native pixel scale at the largest size each coarse mesh can retain through hysteresis. */
    private static void compareDistantLevels(GpuAssets assets, Environment environment, List<String> names) throws Exception {
        // Impostor vertex colors encode surface data, not pigment. Use the actual instanced foliage shader here;
        // the default ModelBatch used for silhouette comparisons would tint these cards.
        var preview = new GpuTerrain();
        var batchField = GpuTerrain.class.getDeclaredField("batch");
        batchField.setAccessible(true);
        ModelBatch batch = (ModelBatch) batchField.get(preview);
        var prepare = GpuTerrain.class.getDeclaredMethod("foliage", Model.class);
        prepare.setAccessible(true);
        var instances = new GpuTreeInstances((name, level) -> {
            try { return (Model) prepare.invoke(null, assets.lodModel(name, level)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        String[] labels = { "Near / 150px", "Medium / 150px", "Near / 88px", "Medium / 88px", "Far / 52px", "Far / 26px", "Impostor / 26px", "Impostor / 12px" };
        int[] levels = { 0, 1, 0, 1, 2, 2, 3, 3 };
        float[] sizes = { 150, 150, 88, 88, 52.8f, 26.4f, 26.4f, 12 };
        BufferedImage sheet = new BufferedImage(192 + labels.length * 128, names.size() * 132 + 32, BufferedImage.TYPE_INT_RGB);
        var continuityErrors = new ArrayList<String>();
        var graphics = sheet.createGraphics();
        try (var output = new GpuSceneTestOutput()) {
            graphics.setColor(java.awt.Color.WHITE);
            for (int column = 0; column < labels.length; column++) {
                graphics.drawString(labels[column], 192 + column * 128, 20);
            }
            for (int row = 0; row < names.size(); row++) {
                String name = names.get(row);
                var stand = new GpuTreeInstances.Stand();
                stand.add(name, new Matrix4().setToScaling(1, 1, 36 / 30f), 0);
                BoundingBox bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                float diameter = bounds.getDimensions(new Vector3()).scl(1, 1, 36 / bounds.getDepth()).len();
                graphics.drawString(name, 12, row * 132 + 94);
                BoardCamera camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(true);
                camera.center(new Vector3(0, 0, 18));
                int farCoverage = 0;
                int nearCoverage = 0;
                for (int column = 0; column < labels.length; column++) {
                    camera.camera.zoom = zoomForSize(diameter, sizes[column]);
                    camera.update();
                    BufferedImage frame = foliageFrame(output, preview, instances, stand, levels[column], batch, environment, camera);
                    if (authoredForest(name)) {
                        if (column == 2) { nearCoverage = coverage(frame); }
                        if (column == 3) {
                            checkCoverageContinuity(continuityErrors, name, "near/medium at 88px", nearCoverage, coverage(frame));
                        }
                        if (column == 4) {
                            var medium = foliageFrame(output, preview, instances, stand, 1, batch, environment, camera);
                            checkCoverageContinuity(continuityErrors, name, "medium/far at 52px", coverage(medium), coverage(frame));
                        }
                        if (column == 5) { farCoverage = coverage(frame); }
                        if (column == 6) {
                            checkCoverageContinuity(continuityErrors, name, "far/impostor at 26px", farCoverage, coverage(frame));
                        }
                    }
                    if (name.startsWith("vegetation/orchard-")) {
                        if (column == 5) { farCoverage = coverage(frame); }
                        if (column == 6) {
                            int impostorCoverage = coverage(frame);
                            assertTrue(farCoverage >= impostorCoverage * .6 && farCoverage <= impostorCoverage * 1.6,
                                  name + " at 26px: LOD2=" + farCoverage + ", impostor=" + impostorCoverage);
                        }
                    }
                    graphics.drawImage(frame.getSubimage(frame.getWidth() / 2 - 64, frame.getHeight() / 2 - 64, 128, 128),
                          192 + column * 128, row * 132 + 32, null);
                }
            }
            // Inspect the corrected attachment and the newly converted desert plants in the production shader.
            // The default ModelBatch above only verifies silhouettes; it does not shade the cactus normal map.
            var materialPlants = new ArrayList<>(List.of("vegetation/pine-tall", "vegetation/pine-broad", "vegetation/pine-tall-snow", "vegetation/palm", "vegetation/palm-bent", "vegetation/cactus", "vegetation/cactus-flowers",
                  "vegetation/tree-forked", "vegetation/tree-layered", "vegetation/birch-tall", "vegetation/birch-spreading", "vegetation/birch-young", "vegetation/willow-broad",
                  "vegetation/pine-slender", "vegetation/pine-layered"));
            if (!names.equals(TREES)) {
                materialPlants.clear();
                materialPlants.addAll(names);
            }
            if (Boolean.getBoolean("megamek.gpu.materialMigrationReview")) {
                materialPlants.addAll(List.of("vegetation/foliage-barren", "vegetation/foliage-desert", "vegetation/foliage-highland", "vegetation/foliage-rocky",
                      "vegetation/foliage-snow", "vegetation/foliage-temperate", "vegetation/foliage-wetland", "vegetation/foliage-jungle", "vegetation/tree-volcano-crown",
                      "vegetation/orchard-leaning", "vegetation/orchard-round", "vegetation/orchard-spreading", "vegetation/orchard-upright", "vegetation/orchard-vase",
                      "vegetation/orchard-young"));
            }
            for (String name : materialPlants) {
                var stand = new GpuTreeInstances.Stand();
                stand.add(name, new Matrix4().setToScaling(1, 1, 36 / 30f), 0);
                var bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                var detail = new BufferedImage(1152, 416, BufferedImage.TYPE_INT_RGB);
                var paint = detail.createGraphics();
                int fruitPixels = 0;
                int normalChanges = 0;
                try {
                    for (int angle = 0; angle < 3; angle++) {
                        var camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        camera.orbit(angle * 120, 0);
                        camera.center(bounds.getCenter(new Vector3()).scl(1, 1, 36 / 30f));
                        camera.camera.zoom = zoomForSize(bounds.getDimensions(new Vector3()).scl(1, 1, 36 / 30f).len(), 380);
                        camera.update();
                        var frame = foliageFrame(output, preview, instances, stand, 0, batch, environment, camera);
                        if (name.startsWith("vegetation/orchard-") && !name.endsWith("-snow")) {
                            fruitPixels += redPixels(frame);
                        }
                        preview.setNormalMaps(false);
                        var plain = foliageFrame(output, preview, instances, stand, 0, batch, environment, camera);
                        preview.setNormalMaps(true);
                        assertEquals(coverage(frame), coverage(plain), 5, "Normal maps retain the plant silhouette");
                        normalChanges += differences(frame, plain);
                        paint.drawImage(frame.getSubimage(frame.getWidth() / 2 - 192, frame.getHeight() / 2 - 192, 384, 384),
                              angle * 384, 32, null);
                        paint.setColor(java.awt.Color.WHITE);
                        paint.drawString(name + " / " + angle * 120 + " degrees", angle * 384 + 12, 20);
                    }
                } finally { paint.dispose(); }
                // Needle/frond relief fades under mip filtering, unlike the broad cactus skin. It must still
                // affect real lighting across these views, without changing alpha coverage in any view.
                assertTrue(normalChanges > (name.startsWith("vegetation/cactus") ? 100 : 0),
                      "Normal detail must affect real plant lighting: " + name
                      + " changed=" + normalChanges);
                if (name.startsWith("vegetation/orchard-") && !name.endsWith("-snow")) {
                    assertTrue(fruitPixels > 40, "Ripe apples must be visible across orchard views: " + name);
                }
                ImageIO.write(detail, "png", new File(System.getProperty("megamek.gpu.screenshots"), "plant-material-" + name.replace('/', '-') + ".png"));
            }
        } finally {
            graphics.dispose();
            instances.dispose();
            preview.dispose();
        }
        ImageIO.write(sheet, "png", new File(System.getProperty("megamek.gpu.screenshots"), "tree-lod-transitions.png"));
        assertTrue(continuityErrors.isEmpty(), String.join("\n", continuityErrors));
    }

    private static String specimenTimings(GpuSceneTestOutput output, GpuTerrain preview, GpuTreeInstances instances,
          GpuTreeInstances.Stand stand, int level, ModelBatch batch, Environment environment, BoardCamera camera) {
        try (var timings = new GpuStageTimings()) {
            for (int frame = 0; frame < 80; frame++) {
                output.scene().begin();
                newShadingPass(preview);
                Gdx.gl.glDepthMask(true);
                ScreenUtils.clear(0, 0, 0, 1, true);
                timings.beginFrame(frame >= 20);
                timings.stage("specimen");
                drawFoliage(instances, stand, level, batch, environment, camera);
                timings.stage(null);
                Gdx.gl.glFinish();
                timings.poll();
                output.scene().end();
            }
            var report = new StringBuilder();
            timings.appendReport(report, "matched-footprint LOD" + level);
            String result = report.toString();
            assertTrue(result.contains("GPU timestamps: supported; dropped samples: 0"), result);
            assertTrue(result.contains("specimen,60,"), result);
            return result;
        }
    }

    private static BufferedImage foliageFrame(GpuSceneTestOutput output, GpuTerrain preview, GpuTreeInstances instances,
          GpuTreeInstances.Stand stand, int level, ModelBatch batch, Environment environment, BoardCamera camera) {
        output.scene().begin();
        // Start a real frame: shader globals are cached within one terrain pass, including the normal-map toggle.
        newShadingPass(preview);
        Gdx.gl.glDepthMask(true);
        float adaptation = Boolean.getBoolean("megamek.gpu.reviewImpostorMaterials") ? preview.lightAdaptation() : 1;
        ScreenUtils.clear(GpuColor.linear(.035f) * GpuSceneRadiance.SCALE / adaptation,
              GpuColor.linear(.055f) * GpuSceneRadiance.SCALE / adaptation,
              GpuColor.linear(.075f) * GpuSceneRadiance.SCALE / adaptation, 1, true);
        drawFoliage(instances, stand, level, batch, environment, camera);
        output.display(adaptation);
        return pixels();
    }

    private static void drawFoliage(GpuTreeInstances instances, GpuTreeInstances.Stand stand, int level,
          ModelBatch batch, Environment environment, BoardCamera camera) {
        instances.begin(GpuTreeInstances.Pass.COLOUR);
        instances.add(stand, level, true);
        batch.begin(camera.camera);
        batch.render(instances, environment);
        batch.end();
    }

    /** A new terrain shading pass, as each frame starts one: the provider's shaders refresh their cached globals. */
    static void newShadingPass(GpuTerrain terrain) {
        try {
            var pass = GpuTerrain.class.getDeclaredField("shadingPass");
            pass.setAccessible(true);
            pass.setLong(terrain, pass.getLong(terrain) + 1);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    static BufferedImage render(ModelBatch batch, Environment environment, Model model, BoardCamera camera) {
        for (Material material : model.materials) {
            if (material.id.equals("impostor")) {
                material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1),
                      new FloatAttribute(FloatAttribute.AlphaTest, .5f));
            }
        }
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        ModelInstance instance = new ModelInstance(model);
        // All levels share the LOD0 coordinate frame, whose catalog height is 30.
        instance.transform.setToScaling(1, 1, 36 / 30f);
        batch.begin(camera.camera);
        batch.render(instance, environment);
        batch.end();
        return pixels();
    }

    private static BufferedImage pixels() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            BufferedImage image = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    image.setRGB(x, pixels.getHeight() - y - 1, pixels.getPixel(x, y) >>> 8);
                }
            }
            return image;
        } finally {
            pixels.dispose();
        }
    }

    private static float zoomForSize(float diameter, float pixels) {
        return diameter * Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight() / pixels;
    }

    static int coverage(BufferedImage image) {
        int background = image.getRGB(0, 0), count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if (Math.abs((pixel >> 16 & 255) - (background >> 16 & 255)) > 2
                      || Math.abs((pixel >> 8 & 255) - (background >> 8 & 255)) > 2
                      || Math.abs((pixel & 255) - (background & 255)) > 2) { count++; }
            }
        }
        return count;
    }

    private static void checkCoverageContinuity(List<String> errors, String name, String transition, int before, int after) {
        String sample = name + " " + transition + ": " + before + " -> " + after;
        System.out.println("Forest LOD coverage: " + sample);
        if (before <= 0 || after < before * .8 || after > before * 1.2) { errors.add(sample); }
    }

    private static int redPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int red = pixel >> 16 & 255, green = pixel >> 8 & 255, blue = pixel & 255;
                if (red > 50 && red > green * 1.35 && red > blue * 1.3) { count++; }
            }
        }
        return count;
    }

    private static int differences(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                // Linear glTF colors round through float32; permit one display-channel step, not silhouette changes.
                int first = a.getRGB(x, y), second = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs((first >> shift & 255) - (second >> shift & 255)) > 1) {
                        changed++;
                        break;
                    }
                }
            }
        }
        return changed;
    }
}
