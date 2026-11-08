/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Authored stone, concrete and bark pigment reaches the real sculpt material without fixed shader gains. */
@Tag("on-demand")
class GpuAuthoredSculptSmokeTest {
    private static final int SIZE = 512;
    /** Largest adjacent binary16 step for the captured numeric fields in [0, 1]. */
    private static final float HALF_STEP_BELOW_ONE = 1 / 2048f;
    private static final Coords CENTER = new Coords(2, 2);
    private static final String CONTROL = "u_authoredSculptReview";

    @Test
    void snowRocksAndPlantingPitsRetainAuthoredMaterialChannels() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try { check(manager); }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored sculpt materials", failure.get()); }
    }

    private static void check(GpuShaderManager manager) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        System.out.println("Authored sculpt GL_RENDERER=" + renderer);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer");
        if (expectedRenderer != null) { assertEquals(expectedRenderer, renderer, "Requested native adapter"); }
        var originalGeometry = BoardGeometry.tuning();
        var originalRelief = BoardRelief.tuning();
        var specimens = new ArrayList<Specimen>();
        var environmentLight = new GpuEnvironmentLighting();
        environmentLight.set(new Color(.24f, .25f, .27f, 1));
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(.24f, .25f, .27f, 1), environmentLight);
        environment.add(new DirectionalLight().set(1, .96f, .90f, -.4f, .6f, -1));
        GpuEnvironmentLighting.applyAntialiasing(environment);
        try (var output = new GpuSceneTestOutput(SIZE, SIZE)) {
            BoardScenes.tune(1, true);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            specimens.add(specimen(BoardScene.Surface.SNOW, environment));
            specimens.add(specimen(BoardScene.Surface.CONCRETE, environment));
            var before = new ArrayList<Frame>();
            for (var specimen : specimens) {
                // Establish each actual provider before apply: a broken draft must fail transactionally, not fall back.
                specimen.batch().getShaderProvider().getShader(specimen.part());
                before.add(draw(output, specimen, -1));
                save(output, specimen.name() + "-production");
            }
            String source = GpuShaderSource.readDisk("terrain-sculpt.frag");
            apply(manager, Map.of("terrain-sculpt.frag", diagnostic(source)));
            File textures = GpuMaterialMaps.boardTextures();
            float mulchRoughness = GpuAssets.authoredFinish(GodotMaterial.read(
                  new File(textures, "terrain/surfaces/concrete-mulch.tres"), textures)).surface()[0];
            var checks = new ArrayList<Executable>();
            for (int index = 0; index < specimens.size(); index++) {
                var specimen = specimens.get(index);
                var reference = before.get(index);
                var shader = specimen.batch().getShaderProvider().getShader(specimen.part());
                var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
                // Programs compile without comments; the probe's uniform proves the linked program contains it.
                assertTrue(program.hasUniform(CONTROL),
                      "The linked actual provider must contain the material probe, never fallback shading");
                assertArrayEquals(reference.rgba(), draw(output, specimen, 0).rgba(),
                      "An inactive probe preserves the complete shaded image");
                checkBindings(specimen);
                float[][] fields = new float[11][];
                for (int mode = 1; mode <= 10; mode++) {
                    Frame measured = draw(output, specimen, mode);
                    assertArrayEquals(reference.depth(), measured.depth(), "Material probes preserve actual geometry depth");
                    fields[mode] = measured.rgba();
                }
                checks.add(() -> verify(specimen.name(), fields, reference.depth(), mulchRoughness));
            }
            apply(manager, Map.of());
            for (int index = 0; index < specimens.size(); index++) {
                Frame restored = draw(output, specimens.get(index), -1);
                save(output, specimens.get(index).name() + "-restored");
                assertArrayEquals(before.get(index).rgba(), restored.rgba(), "Restore exact real material shading");
                assertArrayEquals(before.get(index).depth(), restored.depth(), "Restore exact geometry depth");
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertAll("Both authored material specimens", checks);
        } finally {
            try { if (manager.hasDrafts()) { apply(manager, Map.of()); } }
            finally {
                for (var specimen : specimens) { specimen.mesh().dispose(); specimen.terrain().dispose(); }
                environmentLight.dispose();
                BoardGeometry.tune(originalGeometry); BoardRelief.tune(originalRelief);
            }
        }
    }

    /** Record endpoints and the already-computed snow weight; do not reimplement the material selection formula. */
    private static String diagnostic(String source) {
        source = GpuShaderSource.replaceOnce(source, "void main() {", """
              uniform int u_authoredSculptReview;
              void main() {
                  vec3 reviewStone = vec3(0.0), reviewSnow = vec3(0.0);
                  float reviewWeight = 0.0;
                  vec3 reviewSnowInputs = vec3(-1.0), reviewHeights = vec3(-1.0);
              """, "authored sculpt control");
        source = GpuShaderSource.replaceOnce(source, "albedo = wall.rgb;",
              "albedo = wall.rgb; reviewStone = wall.rgb;", "authored stone endpoint");
        String snowMask = "ledge *= family(5.0) ? smoothstep(.2, .9, h) : family(2.0) ? .45 : 0.0;";
        source = GpuShaderSource.replaceOnce(source, snowMask,
              snowMask + " reviewSnowInputs = vec3(face.z, h, ledge);", "actual snow mask inputs");
        source = GpuShaderSource.replaceOnce(source, "float w = heightBlend(wall.a, top.a, ledge);", """
              float w = heightBlend(wall.a, top.a, ledge);
              reviewWeight = w;
              reviewHeights = vec3(wall.a, top.a, ledge);
              reviewSnow = groundTone(top.rgb, world, broad, fine, region, 0.0, 0.0);
              """, "actual snow mixture");
        String boundary = "// Rain darkens exposed ground and rock, and gathers in puddles on level ground.";
        return GpuShaderSource.replaceOnce(source, boundary, """
              if (u_authoredSculptReview > 0) {
                  // Uniform diagnostic branch: evaluate both aligned authored maps before per-role output selection.
                  vec4 bark = planar(u_treePitMaterial.x, p, u_treePitMaterial.y, fine);
                  vec4 barkNormal = planarNormal(u_treePitMaterial.x + 1.0, p, u_treePitMaterial.y, fine);
                  vec3 concrete = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad).rgb;
                  float role = pit ? (v_color.a > .5 ? .5 : .75) : kind >= .999 && family(5.0) ? .25 : 0.0;
                  vec3 authored = pit ? (v_color.a > .5 ? concrete : bark.rgb) : reviewStone;
                  float tint = pit ? 1.0 : mix(.78, 1.0, v_color.a);
                  vec3 value = albedo;
                  if (u_authoredSculptReview == 2) value = authored;
                  if (u_authoredSculptReview == 3) value = reviewSnow;
                  if (u_authoredSculptReview == 4) value = vec3(reviewWeight, tint, 0.0);
                  if (u_authoredSculptReview == 5) value = vec3(materialHeight, finish.roughness, cavity);
                  if (u_authoredSculptReview == 6) value = vec3(bark.a, barkNormal.a, 0.0);
                  if (u_authoredSculptReview == 7) value = normal * .5 + .5;
                  if (u_authoredSculptReview == 8) value = upNormal(barkNormal.rgb, face) * .5 + .5;
                  if (u_authoredSculptReview == 9) value = reviewSnowInputs;
                  if (u_authoredSculptReview == 10) value = reviewHeights;
                  fragColor = vec4(value, role);
                  return;
              }
              """ + boundary, "pre-wetness material boundary");
    }

    private static void verify(String name, float[][] fields, float[] depth, float mulchRoughness) {
        int bare = 0, mixed = 0, snow = 0, kerb = 0, mulch = 0;
        float maximumError = 0, minimumBark = 1, maximumBark = 0;
        float minimumWeight = 1, maximumWeight = 0, maximumNormal = -1, maximumRim = -1, maximumLedge = -1;
        int fullLedge = 0, maximumPixel = -1;
        for (int pixel = 0; pixel < depth.length; pixel++) {
            int at = pixel * 4;
            float role = fields[1][at + 3];
            if (role == 0) { continue; }
            assertTrue(depth[pixel] < 1, "The actual material owns the tested depth");
            assertTrue(role == .25f || role == .5f || role == .75f, "Known material role");
            for (int mode = 2; mode <= 10; mode++) { assertEquals(role, fields[mode][at + 3], 0, "Same visible role"); }
            float weight = fields[4][at], tint = fields[4][at + 1];
            for (int channel = 0; channel < 3; channel++) {
                float expected = fields[2][at + channel];
                if (role == .25f) { expected = (expected * (1 - weight) + fields[3][at + channel] * weight) * tint; }
                maximumError = Math.max(maximumError, Math.abs(expected - fields[1][at + channel]));
                // Four independently stored half-float inputs bound the final affine comparison's rounding.
                assertEquals(expected, fields[1][at + channel], .0015f,
                      name + " completed pigment must equal the authored role/mix at pixel " + pixel);
            }
            if (role == .25f) {
                minimumWeight = Math.min(minimumWeight, weight);
                if (weight > maximumWeight) { maximumWeight = weight; maximumPixel = pixel; }
                maximumNormal = Math.max(maximumNormal, fields[9][at]);
                maximumRim = Math.max(maximumRim, fields[9][at + 1]);
                maximumLedge = Math.max(maximumLedge, fields[9][at + 2]);
                if (fields[9][at + 2] == 1) { fullLedge++; }
                float ledge = fields[9][at + 2];
                if (ledge == 0) {
                    bare++;
                    assertEquals(0, weight, 0, "No snow mask skips the blend and retains exact bare stone");
                } else {
                    // Classify from the inputs, not an approximate GPU division stored in binary16. Four input
                    // terms bound the uncertainty of upper + want - (lower + 1 - want), each by one half step.
                    float dominance = fields[10][at + 1] + ledge - (fields[10][at] + 1 - ledge);
                    float margin = 4 * HALF_STEP_BELOW_ONE;
                    if (ledge == 1 && dominance > .2f + margin) {
                        snow++;
                        assertEquals(1, weight, HALF_STEP_BELOW_ONE,
                              "Proven zero stone contribution must produce a full snow cap within one half step");
                    } else if (dominance < -.2f - margin) {
                        bare++;
                        assertEquals(0, weight, 0, "Proven zero snow contribution retains exact bare stone");
                    } else if (Math.abs(dominance) < .2f - margin) {
                        mixed++;
                        assertTrue(weight > 0 && weight < 1, "Both proven height contributions produce a true transition");
                    }
                }
            } else if (role == .5f) { kerb++; }
            else {
                mulch++;
                minimumBark = Math.min(minimumBark, fields[2][at]);
                maximumBark = Math.max(maximumBark, fields[2][at]);
                assertEquals(fields[6][at], fields[5][at], .001f, "Mulch retains authored height");
                assertEquals(mulchRoughness, fields[5][at + 1], .001f, "Mulch uses its authored scalar roughness");
                assertEquals(fields[6][at + 1], fields[5][at + 2], .001f, "Mulch retains aligned authored AO");
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(fields[8][at + channel], fields[7][at + channel], .001f,
                          "Mulch normal uses the same world scale and projection as its pigment");
                }
            }
        }
        System.out.printf("Authored sculpt %s bare=%d mixedSnow=%d fullSnow=%d kerb=%d mulch=%d maxError=%s bark=%s..%s%n",
              name, bare, mixed, snow, kerb, mulch, maximumError, minimumBark, maximumBark);
        if (name.equals("snow-rock")) {
            System.out.printf("Snow inputs weight=%s..%s normalMax=%s rimMax=%s ledgeMax=%s fullLedge=%d maxPixel=%d%n",
                  minimumWeight, maximumWeight, maximumNormal, maximumRim, maximumLedge, fullLedge, maximumPixel);
            if (maximumPixel >= 0) {
                int at = maximumPixel * 4;
                System.out.printf("Snow maximum inputs normal=%s rim=%s ledge=%s stoneHeight=%s snowHeight=%s%n",
                      fields[9][at], fields[9][at + 1], fields[9][at + 2], fields[10][at], fields[10][at + 1]);
            }
            // This actual flat-faceted boulder has bare sides and a level cap; it need not contain a sloping
            // transition. Any genuine mixed pixels above still exercise the same independent affine pigment oracle.
            assertTrue(bare > 0 && snow > 0, "Actual rock must expose bare stone and a mathematically complete snow cap");
        } else {
            assertTrue(kerb > 0 && mulch > 0, "Actual planting pit must expose both kerb and mulch");
            assertTrue(maximumBark > minimumBark, "The authored bark map must retain spatial pigment detail");
        }
    }

    private record Specimen(String name, GpuTerrain terrain, ModelBatch batch, Mesh mesh, Renderable part,
                            OrthographicCamera camera) { }

    /** Check the provider's actual uploaded identities, not just two reads through the same possibly wrong uniform. */
    private static void checkBindings(Specimen specimen) throws Exception {
        var names = GpuTerrain.class.getDeclaredField("SCULPT_LAYERS"); names.setAccessible(true);
        List<?> layers = (List<?>) names.get(null);
        var shader = specimen.batch().getShaderProvider().getShader(specimen.part());
        var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
        var values = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glGetUniformfv(program.getHandle(), program.getUniformLocation("u_treePitMaterial"), values);
        assertTrue(layers.contains("concrete-mulch"));
        assertEquals(2f * layers.indexOf("concrete-mulch"), values.get(0), 0, "Actual mulch layer identity");
        var catalogued = new GpuAssets();
        float tile = catalogued.sculptTile("concrete-mulch");
        catalogued.dispose();
        assertEquals(tile, values.get(1), 0, "Actual authored mulch repeat in metres");
        values.clear();
        Gdx.gl.glGetUniformfv(program.getHandle(), program.getUniformLocation("u_sculptLayers"), values);
        String ground = specimen.name().equals("snow-rock") ? "snow-ground" : "concrete-ground";
        assertEquals(2f * layers.indexOf(ground), values.get(0), 0, "Actual ground/kerb pigment identity");
        assertEquals(2f * layers.indexOf("temperate-cliff"), values.get(2), 0, "Actual unpainted stone identity");
        System.out.println("Authored sculpt bindings " + specimen.name() + ": " + ground
              + "/temperate-cliff; mulch=" + 2 * layers.indexOf("concrete-mulch") + " tile=" + tile);
    }

    /** Real production geometry and vertex encoding, isolated from unrelated scene models and their loading. */
    private static Specimen specimen(BoardScene.Surface family, Environment environment) throws Exception {
        boolean snow = family == BoardScene.Surface.SNOW;
        var feature = new BoardScene.Feature(snow ? "rough-boulder" : "vegetation/tree", 0, 0, 20, 1, .5f, 0,
              snow ? BoardScene.FeatureKind.BOULDER : BoardScene.FeatureKind.TREE);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
            var coords = new Coords(x, y);
            tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, family, null, null,
                  coords.equals(CENTER) ? List.of(feature) : List.of(), List.of(), BoardLiquid.NONE, null, true));
        }
        var scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        var surface = new BoardSurface(scene, scene.tile(CENTER), TerrainLod.FULL);
        var builder = new MeshBuilder();
        builder.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        var bounds = new BoundingBox().inf();
        int targetFaces = 0;
        for (var face : surface.faces) {
            var a = surface.relief.shade(face.a());
            var b = surface.relief.shade(face.b());
            var c = surface.relief.shade(face.c());
            assertTrue(a != null && b != null && c != null,
                  "Every drawn face must retain actual relief metadata; untagged ground cannot masquerade as rock");
            var target = snow ? BoardRelief.Kind.ROCK : BoardRelief.Kind.PIT;
            if (a.kind() == target && b.kind() == target && c.kind() == target) {
                bounds.ext(face.a()).ext(face.b()).ext(face.c()); targetFaces++;
            }
            builder.triangle(GpuTerrain.sculptVertex(face.a(), a, Float.NaN, surface),
                  GpuTerrain.sculptVertex(face.b(), b, Float.NaN, surface),
                  GpuTerrain.sculptVertex(face.c(), c, Float.NaN, surface));
        }
        assertTrue(targetFaces > 0, "Actual BoardSurface must construct the requested material geometry");
        Mesh mesh = builder.end();
        var terrain = new GpuTerrain();
        try {
            terrain.setWetness(0);
            // This isolated provider owns the same inactive biome texture as the existing provider fixtures.
            var biomes = GpuTerrain.class.getDeclaredField("biomes"); biomes.setAccessible(true);
            ((GpuBiomeSurface) biomes.get(terrain)).update(null);
            var material = GpuTerrain.class.getDeclaredMethod("sculptMaterial", BoardScene.Surface.class);
            material.setAccessible(true);
            var part = new Renderable();
            part.meshPart.set("authored-sculpt", mesh, 0, mesh.getNumIndices(), GL20.GL_TRIANGLES);
            part.material = (Material) material.invoke(terrain, family);
            part.environment = environment;
            var batch = GpuTerrain.class.getDeclaredField("batch"); batch.setAccessible(true);
            Vector3 center = bounds.getCenter(new Vector3()), dimensions = bounds.getDimensions(new Vector3());
            float span = dimensions.len() * 1.35f;
            var camera = new OrthographicCamera(span, span);
            camera.position.set(center).add(new Vector3(1, -1.4f, 1.7f).nor().scl(span * 2));
            camera.up.set(Vector3.Z); camera.lookAt(center); camera.near = .01f; camera.far = span * 5; camera.update();
            // Project actual target vertices, not the fitting box: an accidental world-origin extent would otherwise
            // validate itself while reducing these material closeups to a distant view of the whole hex.
            var pixels = new BoundingBox().inf();
            for (var face : surface.faces) {
                if (surface.relief.shade(face.a()).kind() != (snow ? BoardRelief.Kind.ROCK : BoardRelief.Kind.PIT)) { continue; }
                for (var point : List.of(face.a(), face.b(), face.c())) {
                    pixels.ext(camera.project(new Vector3(point), 0, 0, SIZE, SIZE));
                }
            }
            System.out.println("Authored sculpt " + family + " actual role triangles=" + targetFaces
                  + " world=" + bounds + " targetPixels=" + pixels);
            assertTrue(pixels.min.x >= 0 && pixels.min.y >= 0 && pixels.max.x <= SIZE && pixels.max.y <= SIZE,
                  "The complete specimen must remain inside the capture");
            assertTrue(pixels.getWidth() > SIZE / 3f && pixels.getHeight() > SIZE / 3f,
                  "Actual material geometry must fill a useful 512-pixel closeup");
            return new Specimen(snow ? "snow-rock" : "planting-pit", terrain, (ModelBatch) batch.get(terrain), mesh, part, camera);
        } catch (Exception | Error error) { mesh.dispose(); terrain.dispose(); throw error; }
    }

    private record Frame(float[] rgba, float[] depth) { }

    private static Frame draw(GpuSceneTestOutput output, Specimen specimen, int mode) {
        if (mode >= 0) {
            var shader = specimen.batch().getShaderProvider().getShader(specimen.part());
            var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
            program.bind(); program.setUniformi(CONTROL, mode);
        }
        output.begin(.08f, .09f, .11f, 0);
        specimen.batch().begin(specimen.camera());
        specimen.batch().render(specimen.part());
        specimen.batch().end();
        var buffer = BufferUtils.newFloatBuffer(SIZE * SIZE);
        Gdx.gl.glReadPixels(0, 0, SIZE, SIZE, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer);
        float[] depth = new float[buffer.capacity()]; buffer.get(depth);
        float[] rgba = output.linearPixels();
        for (float value : rgba) { assertTrue(Float.isFinite(value), "Finite actual material output"); }
        return new Frame(rgba, depth);
    }

    private static void save(GpuSceneTestOutput output, String name) {
        File file = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "authored-sculpt/" + name + ".png");
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        output.display();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
        try { PixmapIO.writePNG(Gdx.files.absolute(file.getAbsolutePath()), pixels, -1, true); }
        finally { pixels.dispose(); output.pixels().end(); }
    }

    private static void apply(GpuShaderManager manager, Map<String, String> sources) {
        var result = manager.apply(sources);
        assertTrue(result.success(), result.message());
        assertTrue(result.updated() > 0, "The actual sculpt providers must adopt/restore the requested source");
    }
}
