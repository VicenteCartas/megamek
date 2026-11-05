/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual material pixels, shared-clock motion, normal maps, darkness, live edits and both camera projections. */
@Tag("on-demand")
class GpuMagmaSmokeTest {
    @TempDir Path directory;

    private static final Coords CRUST = new Coords(2, 2), LAVA = new Coords(6, 2);

    /** The actual molten/fall provider reads authored linear RGB and scale, without multiplying coverage twice. */
    @Test
    void authoredEmissionReachesBothMoltenProvidersOnceWithoutChangingDepth() throws Exception {
        File data = Configuration.dataDir();
        var roots = new ArrayList<Path>();
        for (int index = 0; index < 4; index++) {
            Path root = directory.resolve("source-" + index);
            Path textures = root.resolve("models/board/textures");
            // The final repeat changes coverage to zero while retaining exactly the same emitted light.
            int alpha = index == 3 ? 0 : 255;
            BoardMaterialFiles.relief(textures, "terrain/lava/lava-flow", filled(0xff000000), filled(0xff8080ff),
                  filled(0x0080ccff));
            BoardMaterialFiles.heat(textures, "terrain/lava/lava-flow", filled((alpha << 24) | 0x004080c0),
                  index == 3 ? 2 : index * 2);
            roots.add(root);
        }
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(128, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                Mesh mesh = paletteQuad(0, 100);
                var owners = new ArrayList<GpuAssets>();
                try (var output = new GpuSceneTestOutput(128, 128)) {
                    for (Path root : roots) {
                        Configuration.setDataDir(root.toFile());
                        owners.add(new GpuAssets());
                    }
                    Configuration.setDataDir(data);
                    var camera = new OrthographicCamera(100, 100);
                    camera.position.set(0, 0, 100);
                    camera.near = .01f;
                    camera.far = 200;
                    camera.update();
                    var batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
                    var part = new Renderable();
                    part.meshPart.set("authored-emission", mesh, 0, mesh.getNumVertices(), GL20.GL_TRIANGLES);
                    part.environment = terrain.environment();
                    float firstDepth = -1;
                    float[] firstTwo = null;
                    for (int mode : new int[] { GpuMagmaShader.LAVA, GpuMagmaShader.FALL }) {
                        for (int index = 0; index < owners.size(); index++) {
                            part.material = GpuMagmaShader.material(owners.get(index), mode, null);
                            // Camera/time/weather stay fixed; mode, maps and scale are actual per-draw setters.
                            output.begin(0, 0, 0, 0);
                            batch.begin(camera); batch.render(part); batch.end();
                            var depth = BufferUtils.newFloatBuffer(1);
                            Gdx.gl.glReadPixels(64, 64, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
                            assertTrue(depth.get(0) > 0 && depth.get(0) < 1, "The actual surface writes hardware depth");
                            if (firstDepth < 0) { firstDepth = depth.get(0); }
                            else { assertEquals(firstDepth, depth.get(0), 0, "Source edits cannot move the surface"); }
                            float[] pixels = output.linearPixels();
                            int center = (64 * 128 + 64) * 4;
                            float scale = index == 3 ? 2 : index * 2;
                            for (int channel = 0; channel < 3; channel++) {
                                float expected = (channel + 1) * 64 * scale / 255f;
                                // One conservative RGBA16F ULP at the largest sample (3.012), not a display tolerance.
                                assertEquals(expected, pixels[center + channel], scale == 0 ? 0 : .002f,
                                      "Raw emission reaches mode " + mode + " channel " + channel + " once");
                            }
                            assertEquals(1, pixels[center + 3], 0, "Heat coverage is not surface transparency");
                            if (mode == GpuMagmaShader.LAVA && index == 1) { firstTwo = pixels; }
                            if (index == 1 || index == 3) {
                                assertArrayEquals(firstTwo, pixels, "Provider/coverage changes retain the same constant source");
                            }
                            output.begin(0, 0, 0, 0);
                            batch.begin(camera); batch.render(part); batch.end();
                            assertArrayEquals(pixels, output.linearPixels(), "Drawing cannot advance the material clock");
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    Configuration.setDataDir(data);
                    owners.forEach(GpuAssets::dispose);
                    mesh.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Authored molten emission", failure.get()); }
    }

    /** Compare complete palette channels with the saved eager evaluator, holding all other shaders current. */
    @Test
    void paletteSkippingKeepsZeroWeightsShoreActivationAndSandSubstrate() {
        String baseline = System.getProperty("megamek.gpu.shaderAuditBaseline");
        assumeTrue(baseline != null, "Optional saved terrain-materials oracle");
        File oracle = new File(baseline,
              "megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/terrain-materials.glsl");
        assertTrue(oracle.isFile(), "Missing saved material evaluator: " + oracle);
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(128, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkPaletteSkipping(manager, oracle)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain palette coverage", failure.get()); }
    }

    private static void checkPaletteSkipping(GpuShaderManager manager, File oracle) {
        var terrain = new GpuTerrain();
        var assets = new GpuAssets();
        var meshes = new ArrayList<Mesh>();
        // R=121 represents shore=-0.0204 hex widths, inside the actual 0.08-hex bank margin.
        var shore = new GpuWaterShader.Field.Prepared(1, 1, 1, 0, 0,
              new byte[] { 121, 0, (byte) 128, (byte) 128 }, null).upload(false);
        try (var output = new GpuSceneTestOutput(128, 128)) {
            terrain.setAtmosphere(null);
            // This isolated provider has no board pass; initialize its owned inactive biome sentinel.
            var biomesField = GpuTerrain.class.getDeclaredField("biomes");
            biomesField.setAccessible(true);
            ((GpuBiomeSurface) biomesField.get(terrain)).update(null);
            float span = BoardRelief.metres(30);
            var camera = new OrthographicCamera(span, span);
            camera.position.set(0, 0, span * 2);
            camera.near = .01f;
            camera.far = span * 4;
            camera.update();
            float exposure = 0;
            for (int y = 3; y < 128; y += 5) for (int x = 64; x < 128; x++) {
                exposure = Math.max(exposure,
                      BoardSurfaceBlend.sandExposure(((x + .5f) / 128 - .5f) * 30, ((y + .5f) / 128 - .5f) * 30));
            }
            assertEquals(1, exposure, 0, "The sampled 30m patch must include completely exposed substrate");
            var blend = GpuTerrain.class.getDeclaredMethod("blendMaterial", GpuSurfaceBlend.Palette.class);
            blend.setAccessible(true);
            var batchField = GpuTerrain.class.getDeclaredField("batch");
            batchField.setAccessible(true);
            var batch = (ModelBatch) batchField.get(terrain); // Borrowed; terrain owns provider disposal.
            var parts = new ArrayList<Renderable>();
            int rock = BoardScene.Surface.ROCK.ordinal(), sand = BoardScene.Surface.SAND.ordinal();
            int grass = BoardScene.Surface.GRASS.ordinal(), fungus = BoardScene.Surface.FUNGUS.ordinal();
            int snow = BoardScene.Surface.SNOW.ordinal();
            var palettes = List.of(new GpuSurfaceBlend.Palette(rock, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK,
                        BoardSurfaceBlend.CRUST),
                  new GpuSurfaceBlend.Palette(rock, BoardSurfaceBlend.BANK, rock, rock),
                  new GpuSurfaceBlend.Palette(sand, BoardSurfaceBlend.CRUST, sand, sand),
                  new GpuSurfaceBlend.Palette(rock, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK, rock),
                  new GpuSurfaceBlend.Palette(grass, grass, rock, fungus),
                  new GpuSurfaceBlend.Palette(sand, rock, sand, sand),
                  new GpuSurfaceBlend.Palette(rock, fungus, grass, snow),
                  new GpuSurfaceBlend.Palette(grass, grass, rock, fungus),
                  new GpuSurfaceBlend.Palette(grass, rock, grass, grass),
                  new GpuSurfaceBlend.Palette(grass, grass, rock, fungus),
                  new GpuSurfaceBlend.Palette(rock, sand, rock, rock),
                  new GpuSurfaceBlend.Palette(grass, grass, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST),
                  new GpuSurfaceBlend.Palette(grass, grass, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST),
                  new GpuSurfaceBlend.Palette(BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK,
                        BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST));
            for (int index = 0; index < palettes.size(); index++) {
                var material = (Material) blend.invoke(terrain, palettes.get(index));
                if (index == 1) {
                    material.set(GpuMagmaShader.material(assets, GpuMagmaShader.BANK, shore)
                          .get(GpuMagmaShader.class, GpuMagmaShader.TYPE));
                } else if (index == 2 || index == 5) {
                    material.get(FloatAttribute.class, Attribute.getAttributeType("boardSculpt")).value =
                          index == 2 ? BoardSurfaceBlend.CRUST : rock;
                } else if (index == 4 || index == 11) {
                    // Same family as the base, but a real per-slot scale/map override must prevent complete reuse.
                    var cover = material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    cover.tiles[1][0] *= .63f;
                    cover.layers[1][0] = cover.layers[2][0];
                    assertTrue(cover.layers[1][0] != cover.layers[0][0]);
                }
                Mesh mesh = paletteQuad(index == 11 ? 4 : index == 12 ? 7 : index == 13 ? 3 : index, span);
                meshes.add(mesh);
                var part = new Renderable();
                part.meshPart.set("palette", mesh, 0, mesh.getNumVertices(), GL20.GL_TRIANGLES);
                part.material = material;
                part.environment = terrain.environment();
                parts.add(part);
            }
            // The same tuple with and without B's override must select different family programs; C also aliases.
            var familyParts = new HashMap<Integer, Renderable>();
            for (int index : new int[] { 4, 7, 8 }) {
                var part = new Renderable().set(parts.get(index));
                part.material = new Material(part.material);
                part.material.set(new GpuFamilyPrograms.Constants());
                part.shader = null;
                familyParts.put(index, part);
            }
            // Compile each actual variant first: apply must report a broken probe instead of a cold fallback.
            for (var part : parts) { batch.getShaderProvider().getShader(part); }
            for (int index : new int[] { 4, 7, 8 }) { batch.getShaderProvider().getShader(familyParts.get(index)); }
            String source = GpuShaderSource.readDisk("terrain-sculpt.frag");
            // Keep the original body as an unused helper so the composer retains its shared damage/lighting
            // declarations. The diagnostic main alone executes, with the normal provider and storage boundary.
            String probe = GpuShaderSource.replaceOnce(source, "void main() {", """
                  void main() {
                      vec3 world = v_cloudPosition / u_metre, face = normalize(v_normal);
                      pixelMetres = max(length(dFdx(world)), length(dFdy(world)));
                      vec3 color = vec3(.2), normal = face, bounce = vec3(0.0);
                      float cavity = 1.0, relief = 1.0, height = .5, grass = 0.0, sand = 0.0;
                      float response = 0.0, rainCover = 0.0, volcanic;
                      Finish finish = Finish(.9, 0.0, 0.0, vec3(0.0));
                      vec4 weights;
                      // The substrate case deliberately includes zero transfer and the actual exposed-sand field.
                      float sediment = any(lessThan(abs(u_coverFamilies - 2.0), vec4(.5)))
                            && u_sculptFamily != 2.0 && world.x < 0.0 ? 1.0 : 0.0;
                      blendCovers(world, face, v_diffuseUV.x, v_diffuseUV.y, .6, .5, sediment, .4, .6, .5,
                            color, normal, cavity, relief, height, grass, sand, bounce, response, rainCover,
                            finish, volcanic, weights);
                      int channel = int(gl_FragCoord.y) % 5;
                      if (channel == 0) fragColor = vec4(color, height);
                      else if (channel == 1) fragColor = vec4(normal * .5 + .5, cavity);
                      else if (channel == 2) fragColor = vec4(finish.emission, finish.roughness);
                      else if (channel == 3) fragColor = weights;
                      else fragColor = vec4(volcanic, grass, sand, rainCover);
                  }
                  void paletteOriginalSurface() {
                  """, "palette diagnostic");
            int[] ordinaryFamilies = { rock, rock, sand, rock, -1, -1, -1, -1, -1, -1, -1, -1, grass, -2 };
            List<float[]> expected = new ArrayList<>();
            String previous = Gdx.files.absolute(oracle.getAbsolutePath()).readString("UTF-8");
            for (boolean saved : new boolean[] { true, false }) {
                var sources = new HashMap<String, String>();
                sources.put("terrain-sculpt.frag", probe);
                if (saved) { sources.put("terrain-materials.glsl", previous); }
                var change = manager.apply(sources);
                assertTrue(change.success(), "saved=" + saved + ": " + change.message());
                assertTrue(change.updated() > 0, "The palette programs must adopt the diagnostic sources");
                for (int index = 0; index < parts.size(); index++) {
                    String label = "palette " + index + " saved=" + saved;
                    var shader = batch.getShaderProvider().getShader(parts.get(index));
                    var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
                    String fragment = program.getFragmentShaderSource();
                    assertTrue(fragment.contains("int channel = int(gl_FragCoord.y) % 5;")
                                && fragment.contains("else if (channel == 3) fragColor = weights;"),
                          label + ": the linked program must execute the material diagnostic, never fallback shading");
                    boolean bank = index == 4 || index == 6 || index == 7 || index == 11 || index == 12;
                    camera.position.set(0, bank ? -.8f : 0, bank ? .6f : 1).scl(span * 2);
                    camera.up.set(0, bank ? .6f : 1, bank ? .8f : 0);
                    camera.lookAt(0, 0, 0);
                    camera.update();
                    output.begin(0, 0, 0, 0);
                    batch.begin(camera);
                    batch.render(parts.get(index));
                    batch.end();
                    // Mixed palettes with one ordinary material share its program regardless of slot order.
                    // An actual map override needs generic dispatch; all-volcanic palettes need no ordinary maps.
                    int family = ordinaryFamilies[index];
                    if (family != -1) {
                        assertTrue(fragment.contains("#define VOLCANIC_ORDINARY_FAMILY " + family + "\n"),
                              "The shader must match the palette's ordinary material identity: case " + index);
                    } else {
                        assertFalse(fragment.contains("#define VOLCANIC_ORDINARY_FAMILY "),
                              "Ordinary or distinct-identity palettes must keep generic dispatch");
                    }
                    for (int other = 0; other < parts.size(); other++) {
                        if (ordinaryFamilies[other] != family) {
                            assertFalse(shader.canRender(parts.get(other)),
                                  "A cached program must reject another ordinary family: " + index + " -> " + other);
                        } else if (family != -1) {
                            assertTrue(shader.canRender(parts.get(other)),
                                  "Equivalent ordinary identities must share one program: " + index + " -> " + other);
                            assertSame(shader, batch.getShaderProvider().getShader(parts.get(other)),
                                  "Palette slot order must not create another specialized shader");
                        }
                    }
                    FloatBuffer pixels = readFiniteScene(output, label);
                    float[] actual = new float[pixels.capacity()];
                    for (int i = 0; i < actual.length; i++) {
                        actual[i] = pixels.get(i) / (i % 4 == 3 ? 1 : GpuSceneRadiance.SCALE);
                    }
                    if (saved) { expected.add(actual); }
                    else {
                        comparePalettePixels(expected.get(index), actual, "Palette case " + index);
                    }
                    float minimum = 1, maximum = 0;
                    // Ordinary cases read the first non-base slot's actual final weight; volcanic cases its marker.
                    for (int y = index < 4 || index == 13 ? 4 : 3; y < 128; y += 5) for (int x = 0; x < 128; x++) {
                        float contribution = actual[(y * 128 + x) * 4 + (index < 4 || index == 13 ? 0 : 1)];
                        minimum = Math.min(minimum, contribution);
                        maximum = Math.max(maximum, contribution);
                    }
                    System.out.println(label + " tested contribution range=" + minimum + ".." + maximum);
                    assertTrue(maximum > .1, "The probe must activate the tested material: " + label);
                    if (index == 13) {
                        assertEquals(1, minimum, 0x1p-10f, "The all-volcanic palette must contain no ordinary contribution");
                        assertEquals(1, maximum, 0x1p-10f, "Every covered sample must remain fully volcanic");
                    } else if (index == 0 || index == 2 || index >= 4 && index < 8 || index == 11 || index == 12) {
                        assertEquals(0, minimum, 0, "The same image must include truly zero tested weights: " + label);
                    } else if (index == 1) {
                        assertTrue(minimum > .5, "The shoreline must activate an initially zero bank slot");
                    } else if (index == 3) {
                        assertTrue(minimum > .2, "Crust and cooler bank must both contribute to the mixed palette");
                    } else if (index < 10) {
                        assertTrue(minimum > 0, "Zero-base case " + index + " must retain the active non-base slot: " + minimum);
                    } else {
                        // Full sand exposure legitimately removes the sand slot; it is not an inactive material bug.
                        assertEquals(0, minimum, 0, "The exposed right patch must include a complete sand-to-rock transfer");
                    }
                    if (index >= 8 && index < 11) {
                        float leftBase = 0, rightBase = 0;
                        for (int y = 3; y < 128; y += 5) for (int x = 0; x < 128; x++) {
                            int pixel = (y * 128 + x) * 4;
                            float baseWeight = actual[pixel];
                            if (x < 64) leftBase = Math.max(leftBase, baseWeight);
                            else rightBase = Math.max(rightBase, baseWeight);
                            if (index == 10) {
                                if (x < 64) assertEquals(1, actual[pixel + 1], 0,
                                      "Sediment must keep the left patch entirely sandy");
                                assertEquals(baseWeight, actual[pixel + 2], 0,
                                      "Repeated substrate slots must receive equal sand transfer");
                                assertEquals(baseWeight, actual[pixel + 3], 0x1p-19f,
                                      "The last repeated substrate must retain the same complete material");
                            }
                        }
                        assertEquals(0, leftBase, 0, "The left patch must exercise a truly absent base");
                        if (index == 8) assertTrue(rightBase > .2, "The same mesh must also need its ordinary base");
                        else if (index == 9) assertEquals(0, rightBase, 0,
                              "Only the repeated slot uses the base evaluation in this case");
                        else assertTrue(rightBase > .03, "Exposed sand must reveal its initially zero base substrate");
                    }
                    if (index == 7 || index == 12) {
                        double difference = 0;
                        float[] overridden = expected.get(index == 12 ? 11 : 4);
                        for (int y = 0; y < 128; y += 5) for (int x = 65; x < 128; x++) {
                            for (int channel = 0; channel < 3; channel++) {
                                int i = (y * 128 + x) * 4 + channel;
                                difference += Math.abs(actual[i] - overridden[i]);
                            }
                        }
                        assertTrue(difference > 1, "The grass bank must visibly exercise its map/scale override: " + difference);
                    }
                    if (!saved && familyParts.containsKey(index)) {
                        checkFamilyPalette(batch, camera, output, parts, familyParts, index, actual);
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            terrain.dispose();
            meshes.forEach(Mesh::dispose);
            shore.dispose();
            assets.dispose();
        }
    }

    /** Marked actual providers retain the generic material, including per-slot map and scale overrides. */
    private static void checkFamilyPalette(ModelBatch batch, OrthographicCamera camera, GpuSceneTestOutput output,
          List<Renderable> genericParts, Map<Integer, Renderable> familyParts, int index, float[] expected) {
        var genericPart = genericParts.get(index);
        var familyPart = familyParts.get(index);
        var provider = batch.getShaderProvider();
        var generic = provider.getShader(genericPart);
        var family = provider.getShader(familyPart);
        String fragment = ((BaseShader) GpuShaderProvider.unwrap(family)).program.getFragmentShaderSource();
        String label = "Family palette " + index;
        assertTrue(fragment.contains("#define SCULPT_FAMILY ") && fragment.contains("#define COVER_FAMILIES ")
                    && fragment.contains("int channel = int(gl_FragCoord.y) % 5;")
                    && fragment.contains("else if (channel == 3) fragColor = weights;"),
              label + ": linked actual family diagnostic, never a generic or cold fallback");
        assertEquals(index == 7, fragment.contains("#define COVER_B_IS_BASE\n"),
              "Only the unchanged B maps and scale may remove its evaluator");
        assertEquals(index == 8, fragment.contains("#define COVER_C_IS_BASE\n"),
              "The unchanged C maps and scale remove its evaluator");
        assertFalse(generic.canRender(familyPart), "The generic program must reject the family marker");
        assertFalse(family.canRender(genericPart), "The family program must reject the unmarked material");
        assertTrue(family.canRender(familyPart), "The actual family program accepts its own material");
        if (index == 4 || index == 7) {
            int other = index == 4 ? 7 : 4;
            assertArrayEquals(familyPart.material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE).families,
                  familyParts.get(other).material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE).families);
            assertFalse(family.canRender(familyParts.get(other)),
                  "The same family tuple with different B map/scale identity needs another program");
        }
        float[] depth = grazingDepth(output);
        long covered = 0;
        for (float value : depth) { if (value > 0 && value < 1) { covered++; } }
        assertTrue(covered > 1000, label + ": generic control must draw the actual patch");
        output.begin(0, 0, 0, 0);
        batch.begin(camera); batch.render(familyPart); batch.end();
        FloatBuffer pixels = readFiniteScene(output, label);
        float[] actual = new float[pixels.capacity()];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = pixels.get(i) / (i % 4 == 3 ? 1 : GpuSceneRadiance.SCALE);
        }
        comparePalettePixels(expected, actual, label);
        assertArrayEquals(depth, grazingDepth(output), "Family specialization preserves every hardware depth sample");
    }

    private static void comparePalettePixels(float[] expected, float[] actual, String label) {
        assertEquals(expected.length, actual.length);
        double maximum = 0;
        for (int i = 0; i < actual.length; i++) {
            // Both computations pass through half storage once. Permit the existing one-adjacent-half bound.
            float minimum = i % 4 == 3 ? 0x1p-24f : 0x1p-19f;
            float step = Math.max(minimum, Math.scalb(1f, Math.getExponent(expected[i]) - 10));
            maximum = Math.max(maximum, Math.abs(actual[i] - expected[i]) / step);
        }
        System.out.println(label + " maximum half steps=" + maximum);
        assertTrue(maximum <= 1.01, label + ": complete material channels must match, half steps=" + maximum);
    }

    /** A zero-weight plateau meets positive coverage inside a derivative quad, not on its even-pixel edge. */
    /** A 2-pixel square of one ARGB colour. */
    private static BufferedImage filled(int argb) {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) { image.setRGB(x, y, argb); }
        return image;
    }

    private static Mesh paletteQuad(int scenario, float span) {
        float[] vertices = new float[12 * 18];
        int offset = 0;
        // Boundary at screen x=64.5: sample 64 is zero, sample 65 positive, inside the same 2-wide quad.
        float[] columns = { -.5f, .5f / 128, .5f };
        for (int segment = 0; segment < 2; segment++) {
            int[] corners = { 0, 1, 2, 2, 3, 0 };
            for (int corner : corners) {
                float x = columns[segment + (corner == 1 || corner == 2 ? 1 : 0)];
                float y = corner >= 2 ? .5f : -.5f;
                boolean sloping = scenario == 4 || scenario == 6 || scenario == 7;
                float weight = (scenario == 0 || sloping) && x == .5f ? .65f : scenario == 3 ? .35f : 0;
                // Zero-base cases cover an absent ordinary family, an active slot borrowing that base, and
                // a base revealed only by the existing sand-exposure field. No geometry or lighting changes.
                if (scenario == 8) weight = x == .5f ? .35f : 1f;
                else if (scenario == 9 || scenario == 10) weight = 1f;
                float bank = scenario == 3 ? .35f : 0;
                float levelMetres = BoardGeometry.level() / BoardRelief.metres(1);
                float foot = sloping ? (y + .5f) * 2 * levelMetres : 2;
                float rim = sloping ? 2 * levelMetres - foot : 2;
                float[] vertex = { x * span, y * span * (sloping ? .6f : 1), y * span * (sloping ? .8f : 0),
                      0, sloping ? -.8f : 0, sloping ? .6f : 1, Color.WHITE_FLOAT_BITS, foot, rim,
                      1 - weight - bank, weight, bank, 0, 0, 0, 0, 0, 0 }; // no bank foot (BoardSurfaceBlend.NO_FOOT)
                System.arraycopy(vertex, 0, vertices, offset, vertex.length);
                offset += vertex.length;
            }
        }
        return new Mesh(true, 12, 0, GpuSurfaceBlend.VERTICES).setVertices(vertices);
    }

    /** Optional saved sources permit an alternating reference/current comparison in one context and frozen scene. */
    @Test
    @Tag("gpu-benchmark")
    void volcanicShaderCostOnFrozenScene() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> benchmark(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Volcanic shader timings", failure.get()); }
    }

    private static void benchmark(GpuShaderManager manager) {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "magma-timing");
        assertTrue(output.isDirectory() || output.mkdirs());
        Map<String, String> reference = new HashMap<>();
        String baseline = System.getProperty("megamek.gpu.shaderAuditBaseline");
        if (baseline != null) {
            List<String> required = List.of("terrain-magma.glsl", "magma-solid.glsl", "magma-flow.glsl",
                  "magma-lighting.glsl", "terrain-magma-solid.frag", "terrain-magma-flow.frag", "terrain-materials.glsl");
            List<String> sources = new ArrayList<>(required);
            sources.addAll(List.of("terrain-sculpt.frag", "lava-lighting.glsl", "liquid-flow.glsl"));
            for (String name : sources) {
                File source = new File(baseline, "megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/" + name);
                if (!source.exists() && !required.contains(name)) { continue; }
                assertTrue(source.isFile(), "Missing baseline shader " + source);
                reference.put(name, Gdx.files.absolute(source.getAbsolutePath()).readString("UTF-8"));
            }
        }
        boolean poolBenchmark = Boolean.getBoolean("megamek.gpu.magmaPoolBenchmark");
        boolean currentFirst = Boolean.getBoolean("megamek.gpu.magmaBenchmarkCurrentFirst");
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
        assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expectedRenderer.toLowerCase(Locale.ROOT)), renderer);
        if (poolBenchmark) {
            assertEquals(1280, Gdx.graphics.getBackBufferWidth());
            assertEquals(900, Gdx.graphics.getBackBufferHeight());
        }
        Gdx.graphics.setVSync(false);
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings(13));
        var camera = new BoardCamera();
        var scene = poolBenchmark ? isolatedPoolScene() : scene(false);
        StringBuilder report = new StringBuilder("GPU: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
              + "\n1280x900; frozen magma clock 3 s, same scene/camera/materials for every source variant.\n"
              + "20 warmup + 60 measured frames per block; glFinish outside stages.\n"
              + "Order: " + (currentFirst ? "current/reference/reference/current" : "reference/current/current/reference") + "\n"
              + (poolBenchmark ? "Enclosed equal-level pool; close top/angled views; measured geometry is unchanged.\n" : "")
              + "Terrain stage includes opaque scene work; FFT animation/update is deliberately excluded.\n"
              + "Source-replacement wall time excludes prior GPU work and the first draw; cold draw work is not steady timing.\n"
              + "Baseline sources: " + reference.keySet().stream().sorted().toList() + "\n");
        boolean diagnostics = Boolean.getBoolean("megamek.gpu.magmaDiagnostics");
        if (diagnostics) {
            report.append("Diagnostics replace only fragment output, retaining geometry/depth: flow=pool/falls, ")
                  .append("solid=crust/cooled banks, sculpt=ordinary and boundary ground.\n")
                  .append("The spill diagnostic returns zero lava irradiance to measure the shared emitter-loop cost.\n")
                  .append("Compare opaque stage with surrounding current controls; deltas are marginal costs, not additive totals.\n");
        }
        var reportFile = Gdx.files.absolute(new File(output, "timing.txt").getAbsolutePath());
        try {
            stableOrder(terrain);
            if (poolBenchmark) {
                camera.resize(1280, 900);
                camera.fit(scene);
                frame.prepare(terrain, camera, scene);
            }
            terrain.update(scene);
            terrain.animate(3, List.of());
            camera.resize(1280, 900);
            List<String> views = poolBenchmark ? List.of("pool-top-close", "pool-angled-close")
                  : List.of("top", "orbit", "crust-close", "lava-close");
            for (String view : views) {
                camera.setIsometric(!(view.equals("top") || view.equals("pool-top-close")));
                camera.fit(scene);
                if (view.endsWith("close")) {
                    Coords focus = view.startsWith("crust") ? CRUST : LAVA;
                    camera.camera.zoom = .12f;
                    camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                }
                camera.update();
                frame.prepare(terrain, camera, scene);
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (terrain.refine(camera.camera) || terrain.busy()) {
                    assertTrue(System.nanoTime() < deadline,
                          () -> "Magma geometry/material detail must settle before timing: " + view
                                + "; progress=" + terrain.buildProgress() + "; " + terrain.buildDetails());
                    frame.render(terrain, camera, scene);
                    Gdx.gl.glFinish();
                }
                if (reference.containsKey("terrain-materials.glsl")) {
                    checkDuplicateMaterials(manager, frame, terrain, camera, scene,
                          reference.get("terrain-materials.glsl"), view, report);
                }
                if (poolBenchmark) {
                    replaceSources(manager, Map.of(), view + "-selector-current", report);
                    try (var surface = new GpuSceneTestOutput(1280, 900)) {
                        int pixels = reviewTransportSelector(manager, frame, surface, terrain, camera, scene,
                              Map.of(), output, view, true);
                        assertTrue(pixels > Gdx.graphics.getBackBufferWidth() * Gdx.graphics.getBackBufferHeight() / 2,
                              "Pool benchmark must cover more than half the viewport with actual pool fragments: " + pixels);
                        report.append(view).append(" pool-pixels=").append(pixels).append(System.lineSeparator());
                    }
                }
                byte[] original = null;
                Map<Boolean, byte[]> repeated = new HashMap<>();
                int variants = reference.isEmpty() ? 2 : 4;
                for (int variant = 0; variant < variants; variant++) {
                    boolean saved = !reference.isEmpty() && ((variant == 0 || variant == 3) != currentFirst);
                    String label = view + "-" + variant + (saved ? "-reference" : "-current");
                    replaceSources(manager, saved ? reference : Map.of(), label, report);
                    byte[] pixels = measure(frame, terrain, camera, scene, label, report);
                    if (original == null) { original = pixels; }
                    long total = 0, changed = 0;
                    int maximum = 0;
                    for (int index = 0; index < pixels.length; index++) {
                        int delta = Math.abs(Byte.toUnsignedInt(pixels[index]) - Byte.toUnsignedInt(original[index]));
                        total += delta;
                        maximum = Math.max(maximum, delta);
                        if (delta > 2) { changed++; }
                    }
                    report.append(String.format(Locale.ROOT, "%s image meanAbs=%.6f channelsAbove2=%.5f%% max=%d%n",
                          label, total / (double) pixels.length, 100.0 * changed / pixels.length, maximum));
                    GpuReviewFrame.save(new File(output, label + ".png"));
                    byte[] previous = repeated.putIfAbsent(saved, pixels);
                    if (previous != null) {
                        boolean equal = Arrays.equals(previous, pixels);
                        report.append(label).append(" frozen-repeat=").append(equal ? "byte-exact" : "FAILED").append('\n');
                        assertArrayEquals(previous, pixels, label + " must repeat exactly at the frozen clock");
                    }
                    reportFile.writeString(report.toString(), false);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                }
                if (diagnostics) { diagnoseFamilies(manager, frame, terrain, camera, scene, view, output, report); }
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            frame.dispose();
            terrain.dispose();
            reportFile.writeString(report.toString(), false);
        }
    }

    /** Flush earlier work before measuring source replacement; first-use draw/driver work belongs to warmup. */
    private static void replaceSources(GpuShaderManager manager, Map<String, String> sources, String label,
          StringBuilder report) {
        System.out.println(label + " source-replace starting (draining prior GPU work)");
        System.out.flush();
        Gdx.gl.glFinish();
        long start = System.nanoTime();
        var change = manager.apply(sources);
        String completed = String.format(Locale.ROOT, "%s source-replace completed wallMs=%.3f programs=%d%n",
              label, (System.nanoTime() - start) / 1e6, change.updated());
        report.append(completed);
        System.out.print(completed);
        System.out.flush();
        assertTrue(change.success(), change.message());
    }

    private static byte[] measure(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          String label, StringBuilder report) {
        var measured = new StringBuilder();
        try (var timings = new GpuStageTimings()) {
            for (int iteration = 0; iteration < 80; iteration++) {
                timings.beginFrame(iteration >= 20);
                frame.render(terrain, camera, scene, timings);
                // Finish after the last timestamp so queued frames cannot saturate the four-frame query ring.
                Gdx.gl.glFinish();
            }
            timings.appendReport(measured, label);
        }
        report.append(measured);
        String[] rows = measured.toString().split("\\R");
        assertTrue(rows[0].contains("GPU timestamps: supported") && rows[0].endsWith("dropped samples: 0"),
              "Every timed block needs GPU timestamps without dropped samples: " + rows[0]);
        assertTrue(rows.length > 2, "The benchmark must measure actual render stages: " + label);
        for (int row = 2; row < rows.length; row++) {
            String[] columns = rows[row].split(",");
            assertEquals(60, Integer.parseInt(columns[1]), label + " incomplete samples for " + columns[0]);
        }
        return ScreenUtils.getFrameBufferPixels(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
    }

    /** Output-only ablations retain the real depth/geometry so surrounding material overdraw stays comparable. */
    private static void diagnoseFamilies(GpuShaderManager manager, GpuReviewFrame frame, GpuTerrain terrain,
          BoardCamera camera, BoardScene scene, String view, File output, StringBuilder report) {
        byte[] control = null;
        for (String family : List.of("control-before", "flow", "solid", "sculpt", "spill", "control-after")) {
            String file = switch (family) {
                case "flow" -> "terrain-magma-flow.frag";
                case "solid" -> "terrain-magma-solid.frag";
                case "sculpt" -> "terrain-sculpt.frag";
                case "spill" -> "lava-lighting.glsl";
                default -> null;
            };
            Map<String, String> sources = Map.of();
            if (file != null) {
                String source = GpuShaderSource.readDisk(file);
                if (family.equals("spill")) {
                    // Every reader walks the list through lavaLighting: a stub that lights nothing takes its name, and
                    // the real function, renamed, stays unused.
                    String signature = "vec3 lavaLighting(vec3 position, vec3 normal, vec3 view, vec3 f0, int facets, vec3 facet,"
                          + " float roughness,\n      out vec3 specular, vec3 facet2, float roughness2, out vec3 specular2) {";
                    sources = Map.of(file, GpuShaderSource.replaceOnce(source.replace("\r\n", "\n"), signature, signature
                          + "\n    specular = vec3(0.0); specular2 = vec3(0.0); return vec3(0.0);\n}\n"
                          + signature.replace("lavaLighting", "unusedLavaLighting"), file));
                } else {
                    assertTrue(!source.contains("gl_FragDepth") && !source.contains("discard;"),
                          "An output-only ablation must retain the source's opaque depth coverage: " + file);
                    int main = source.indexOf("void main()");
                    assertTrue(main >= 0 && source.indexOf("void main()", main + 1) < 0,
                          "Expected one trailing material main function: " + file);
                    // These material fragments end with main; keep the header and helper insertion markers before it.
                    // Sculpt owns its cloud-lighting anchor in main; the other families keep it in shared helpers.
                    String flat = family.equals("sculpt")
                          ? "vec3 albedo = vec3(.1), ambient = vec3(1.0), direct = vec3(0.0), sheen = vec3(0.0);\n"
                                + "albedo *= ambient + direct;\nfragColor = vec4(albedo, 1.0);"
                          : "fragColor = vec4(.1, .1, .1, 1.0);";
                    sources = Map.of(file, source.substring(0, main)
                          + "void main() { " + flat + " }\n");
                }
            }
            String label = view + "-diagnostic-" + family;
            replaceSources(manager, sources, label, report);
            byte[] pixels = measure(frame, terrain, camera, scene, label, report);
            GpuReviewFrame.save(new File(output, label + ".png"));
            if (control == null) { control = pixels; }
            else if (file == null) {
                boolean equal = Arrays.equals(control, pixels);
                report.append(label).append(" frozen-repeat=").append(equal ? "byte-exact" : "FAILED").append('\n');
                assertArrayEquals(control, pixels, "Restoring current sources must reproduce the diagnostic control: " + view);
            }
            Gdx.files.absolute(new File(output, "timing.txt").getAbsolutePath()).writeString(report.toString(), false);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        }
    }

    /** Hold the relief/lighting implementation fixed while checking the exact duplicate-palette optimization. */
    private static void checkDuplicateMaterials(GpuShaderManager manager, GpuReviewFrame frame, GpuTerrain terrain,
          BoardCamera camera, BoardScene scene, String previous, String view, StringBuilder report) {
        replaceSources(manager, Map.of("terrain-materials.glsl", previous), view + "-duplicate-reference", report);
        for (int iteration = 0; iteration < 3; iteration++) { frame.render(terrain, camera, scene); }
        byte[] expected = ScreenUtils.getFrameBufferPixels(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
        replaceSources(manager, Map.of(), view + "-duplicate-current", report);
        for (int iteration = 0; iteration < 3; iteration++) { frame.render(terrain, camera, scene); }
        byte[] actual = ScreenUtils.getFrameBufferPixels(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
        int maximum = 0;
        long total = 0;
        for (int index = 0; index < actual.length; index++) {
            int delta = Math.abs(Byte.toUnsignedInt(actual[index]) - Byte.toUnsignedInt(expected[index]));
            maximum = Math.max(maximum, delta);
            total += delta;
        }
        report.append(String.format(Locale.ROOT, "%s duplicate-palette-only meanAbs=%.6f max=%d%n",
              view, total / (double) actual.length, maximum));
        assertTrue(maximum <= 1,
              "Reusing identical palette slots must match within one display quantization step: "
                    + view + "; max=" + maximum);
    }

    @Test
    void volcanicMaterialsKeepTheirHeatAtNightAndMoveOnlyLiquid() throws Exception {
        boolean terraceReview = Boolean.getBoolean("megamek.gpu.magmaDomeTerraceReview");
        BoardScene dome = terraceReview || Boolean.getBoolean("megamek.gpu.magmaDomeReview") ? domeScene() : null;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var manager = new GpuShaderManager();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                manager.run(() -> {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(13));
                try (var surface = new GpuSceneTestOutput()) {
                    stableOrder(terrain);
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    if (terraceReview) {
                        assertNull(System.getProperty("megamek.gpu.shaderAuditBaseline"), "No unrelated source override");
                        reviewDomeTerraces(manager, frame, surface, terrain, dome, output);
                        return; // Explicit diagnostic only; the default material/core test is unchanged.
                    }
                    BoardScene scene = scene(false);
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    var fftField = GpuTerrain.class.getDeclaredField("lavaOcean");
                    fftField.setAccessible(true);
                    GpuOcean lavaWaves = (GpuOcean) fftField.get(terrain);
                    assertNotNull(lavaWaves.texture(), "Lava must run the actual inverse FFT, not silently fall back");
                    var camera = new BoardCamera();
                    camera.resize(1280, 900);
                    camera.fit(scene);
                    camera.camera.zoom *= .7f;
                    camera.update();
                    terrain.setAtmosphere(frame.lighting());
                    int[] dayCrust = sample(surface, terrain, camera, scene, CRUST);
                    int[] dayLava = sample(surface, terrain, camera, scene, LAVA);
                    terrain.animate(3, List.of());
                    assertArrayEquals(dayCrust, sample(surface, terrain, camera, scene, CRUST), "Solid crust must not drift");
                    int[] moved = sample(surface, terrain, camera, scene, LAVA);
                    assertTrue(difference(dayLava, moved) > 1.5,
                          "The directed molten surface must visibly advect: mean channel change " + difference(dayLava, moved));
                    assertArrayEquals(moved, sample(surface, terrain, camera, scene, LAVA), "Drawing does not advance time");
                    terrain.animate(0, List.of());
                    assertArrayEquals(moved, sample(surface, terrain, camera, scene, LAVA), "A second view cannot advance the FFT");
                    terrain.setNormalMaps(false);
                    int[] flat = sample(surface, terrain, camera, scene, CRUST);
                    terrain.setNormalMaps(true);
                    assertTrue(difference(dayCrust, flat) > .4, "Normal/height maps must affect the rendered relief");
                    terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(0, 0, -1), Color.BLACK, Color.BLACK,
                          Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE, 1, 0, false));
                    int[] darkCrust = sample(surface, terrain, camera, scene, CRUST);
                    int[] darkLava = sample(surface, terrain, camera, scene, LAVA);
                    int[] crustCoverage = readCrust(camera, scene);
                    GpuReviewFrame.save(new File(output, "magma-unlit.png"));
                    checkCrustEmission(manager, surface, terrain, camera, scene, crustCoverage);
                    checkMoltenMaterial(manager, surface, terrain, camera);
                    double coldCrust = Arrays.stream(crustCoverage)
                          .filter(p -> (p >>> 24) < 40 && (p >>> 16 & 255) < 40 && (p >>> 8 & 255) < 40)
                          .count() / (double) crustCoverage.length;
                    assertTrue(coldCrust > .5,
                          "Most solid plate faces must stay cold, not glow like lava: cold fraction " + coldCrust);
                    assertTrue(hot(darkLava) > hot(crustCoverage) + .05, "Lava exposes more heat than intact crust");
                    assertTrue(difference(dayCrust, darkCrust) > 1, "Cold basalt still responds to day/night lighting");
                    surface.draw(terrain, camera.camera, true);
                    assertArrayEquals(darkLava, read(camera, scene, LAVA), "Lava writes the opaque depth pass");
                    checkGravity(surface, terrain, camera, scene, lavaWaves);
                    int[] restoredCrust = sample(surface, terrain, camera, scene, CRUST);
                    GpuReviewFrame.save(new File(output, "magma-unlit-after-gravity.png"));
                    assertArrayEquals(darkCrust, restoredCrust, "Restoring gravity must preserve solid crust emission");
                    assertArrayEquals(crustCoverage, readCrust(camera, scene), "All sampled crust retains its emitted heat");
                    checkContinuity(surface, terrain, camera, scene, output);
                    for (boolean perspective : new boolean[] { false, true }) {
                        camera.setPerspective(perspective);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        for (int hour : new int[] { 13, 0 }) {
                            frame.configure(settings(hour));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "magma-" + (hour == 0 ? "night" : "day")
                                  + (perspective ? "-perspective" : "-isometric") + ".png"));
                        }
                    }
                    camera.setPerspective(false);
                    camera.setIsometric(false);
                    camera.fit(scene);
                    frame.configure(settings(13));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "magma-top.png"));
                    for (Coords focus : List.of(CRUST, LAVA)) {
                        camera.setIsometric(true);
                        camera.camera.zoom = .12f;
                        camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                        for (int hour : new int[] { 13, 0 }) {
                            frame.configure(settings(hour));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "magma-detail-" + (focus.equals(CRUST) ? "crust" : "lava")
                                  + (hour == 0 ? "-night" : "-day") + ".png"));
                        }
                    }
                    if (Boolean.getBoolean("megamek.gpu.magmaAnimation")) {
                        reviewMovingPlates(manager, frame, surface, terrain, camera, scene, output, false, false);
                    }
                    if (Boolean.getBoolean("megamek.gpu.magmaPoolReview")) {
                        reviewIsolatedPool(manager, frame, surface, flowTime(terrain), output);
                    }
                    if (dome != null) { reviewDomeMotion(manager, frame, surface, dome, output); }
                    if (Boolean.getBoolean("megamek.gpu.magmaComponents")) {
                        reviewGrazingComponents(manager, frame, surface, terrain, camera, scene, output);
                    }
                    camera.setIsometric(false);
                    camera.fit(scene);
                    frame.configure(settings(13));
                    frame.render(terrain, camera, scene);
                    int[] beforeCooling = sample(surface, terrain, camera, scene, LAVA);
                    BoardScene edited = scene(true);
                    terrain.update(edited);
                    assertTrue(difference(sample(surface, terrain, camera, edited, LAVA), beforeCooling) > 3,
                          "Live cooling replaces the molten material at the same camera and clock");
                    terrain.animate(2, List.of());
                    int[] cooled = sample(surface, terrain, camera, edited, LAVA);
                    terrain.animate(2, List.of());
                    assertArrayEquals(cooled, sample(surface, terrain, camera, edited, LAVA), "Live cooling removes lava motion");
                    assertNull(lavaWaves.texture(), "Cooling the last pool releases the FFT targets");
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    assertNotNull(lavaWaves.texture(), "Reheating recreates the simulation at the shared clock");
                    assertTrue(difference(cooled, sample(surface, terrain, camera, scene, LAVA)) > 3);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    manager.close();
                    Gdx.app.exit();
                }
                });
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Volcanic terrain", failure.get()); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    /** Optional visual evidence: shared-clock flow cycles, two material LODs and the actual sun's mirror direction. */
    private static void reviewMovingPlates(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera, BoardScene scene, File output,
          boolean pool, boolean dome) throws Exception {
        boolean context = !pool && !dome && Boolean.getBoolean("megamek.gpu.magmaAnimationContext");
        boolean contacts = !dome && Boolean.getBoolean("megamek.gpu.magmaAnimationContacts");
        File directory = new File(output, dome ? "magma-motion-dome"
              : pool ? "magma-motion-pool" : context ? "magma-motion-context" : "magma-motion");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        int frameCount = Integer.parseInt(System.getProperty("megamek.gpu.magmaAnimationFrames", dome ? "301" : "121"));
        assertTrue(frameCount >= 2, "Motion evidence needs at least two frames");
        if (dome) { assertTrue(frameCount >= 241 && frameCount <= 361, "Dome review covers 8–12 seconds"); }
        String baseline = System.getProperty("megamek.gpu.shaderAuditBaseline");
        Map<String, String> sources = new HashMap<>();
        if (baseline != null) {
            File source = new File(baseline,
                  "megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/magma-flow.glsl");
            assertTrue(source.isFile(), "The motion comparison needs the saved flowing material: " + source);
            sources.put("magma-flow.glsl", Gdx.files.absolute(source.getAbsolutePath()).readString());
            File fragment = new File(source.getParentFile(), "terrain-magma-flow.frag");
            if (fragment.isFile()) {
                sources.put(fragment.getName(), Gdx.files.absolute(fragment.getAbsolutePath()).readString());
            }
        }
        // A stationary-material diagnostic can optimize u_magmaTime out. Its report reads the same owning clock,
        // while the ordinary capture and all production assertions retain their actual uniform readback.
        var animationClock = baseline == null && !context && !dome ? null
              : GpuTerrain.class.getDeclaredField("magmaClock");
        if (animationClock != null) { animationClock.setAccessible(true); }
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
        assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expectedRenderer.toLowerCase(Locale.ROOT)), renderer);
        StringBuilder report = new StringBuilder("GPU: " + renderer + "; " + width + "x" + height
              + "\nFlow source: " + (baseline == null ? "current" : baseline)
              + "\nClock source: " + (animationClock == null ? "u_magmaTime readback" : "GpuTerrain.magmaClock")
              + "\n" + frameCount + (contacts ? " simulation steps" : " frames")
              + " at 30 fps; each pair shares one material-animation clock."
              + (contacts ? " Contact frames only; all intervening animation/FFT updates still run."
                    : " Every simulation step is captured.")
              + (dome ? "\nShipped Dome Vent 1, fitted top and angled views of the five requested molten regions."
                    : pool ? "\nEnclosed, equal-level pool: no inlet, outlet, descent or BoardFlow current."
                    : "\nThe original close scene includes a directed spill approach; it is not a still-pool control.")
              + (dome ? "\nOne terrain/FFT owner; both views and raw material diagnostics share each simulation step."
                    : context ? "\nFull fall and directed bend: separately owned scenes/FFT fields, fitted cameras."
                    : "\nClose zoom .12; far zoom 4 omits the surface-height map.")
              + " Clear 13:00; no sun glare."
              + "\nFrame deltas compare successive captured images, not necessarily successive simulation steps;"
              + " display RGB mean absolute differences are not a perceptual pop threshold."
              + "\nframe,flowSeconds,view,displayMeanDelta\n");
        if (dome) {
            String fragment = sources.getOrDefault("terrain-magma-flow.frag",
                  GpuShaderSource.readDisk("terrain-magma-flow.frag"));
            String line = "fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);";
            assertTrue(fragment.contains(line), "Read the actual completed molten material before lighting");
            sources.put("terrain-magma-flow.frag", fragment.replace("void main() {",
                  "uniform float u_magmaMotionProbe;\nvoid main() {").replace(line, """
                  if (u_magmaMotionProbe > .5) {
                      fragColor = vec4(material.heat.x, material.surface.r,
                            dot(material.albedo, vec3(.2126, .7152, .0722)), .25);
                  } else {
                      fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);
                  }
                  """));
            report.append("Dome Vent 1; region hexes 1310, 1414, 1315, 1210, 1008; start clock=.5s at 1g.\n")
                  .append("Diagnostic RGB=pre-light heat, mapped height, pigment luminance; raw linear data, no display transfer.\n")
                  .append("Cold coverage is heat<.25, not opacity or emissive brightness. No motion-quality threshold.\n")
                  .append("Terrain settings: ").append(TerrainSettings.capture()).append('\n');
        }
        report.append("Motion overrides: ").append(sources.keySet().stream().sorted().toList()).append('\n');
        Vector3 focus = BoardGeometry.center(dome ? DOME_HEXES.get(0) : LAVA,
              scene.tile(dome ? DOME_HEXES.get(0) : LAVA).elevation());
        byte[][] previous = new byte[2][];
        FloatBuffer[] previousMaterial = new FloatBuffer[2];
        var materialReport = new StringBuilder("frame,flowSeconds,view,hex,cx,cy,radius,centerHit,moltenPixels,coldFraction,"
              + "meanHeat,meanHeight,meanPigmentY,heatDelta,heightDelta,pigmentDelta\n");
        BoardScene[] scenes = { scene, scene };
        GpuTerrain[] terrains = { terrain, terrain };
        BoardCamera[] cameras = { camera, camera };
        String[] labels = dome ? new String[] { "top", "angled" }
              : context ? new String[] { "full-fall", "directed-bend" } : new String[] { "close", "far" };
        GpuTerrain bendTerrain = null;
        try {
            if (!sources.isEmpty()) {
                var change = manager.apply(sources);
                assertTrue(change.success(), change.message());
            }
            frame.configure(settings(13));
            camera.setPerspective(false);
            camera.setIsometric(true);
            camera.camera.zoom = .12f;
            camera.center(focus);
            if (context) {
                cameras[0] = fullFallCamera(scene, report);
                scenes[1] = moltenBendScene();
                cameras[1] = directedBendCamera(scenes[1], report);
                bendTerrain = new GpuTerrain();
                terrains[1] = bendTerrain;
                stableOrder(bendTerrain);
                frame.prepare(bendTerrain, cameras[1], scenes[1]);
                bendTerrain.update(scenes[1]);
                // Use the public animation path, never write a clock. The prior gravity assertions mean the two
                // scenes' FFT clocks need not match; each field is independent, while their material time does.
                bendTerrain.animate(animationClock.getFloat(terrain), List.of());
                var fieldClock = GpuTerrain.class.getDeclaredField("clock");
                fieldClock.setAccessible(true);
                report.append("Initial independent FFT clocks: full-fall=").append(fieldClock.getFloat(terrain))
                      .append(", directed-bend=").append(fieldClock.getFloat(bendTerrain)).append('\n');
            }
            if (dome) {
                cameras[0] = domeCamera(scene, false, report);
                cameras[1] = domeCamera(scene, true, report);
            }
            for (int view = 0; view < (context || dome ? 2 : 1); view++) {
                frame.prepare(terrains[view], cameras[view], scenes[view]);
                GpuReviewFrame.settle(terrains[view], null, scenes[view], cameras[view]);
                for (int warmup = 0; warmup < 8; warmup++) {
                    frame.render(terrains[view], cameras[view], scenes[view]);
                }
                if (pool || Boolean.getBoolean("megamek.gpu.magmaSelectorReview")) {
                    reviewTransportSelector(manager, frame, surface, terrains[view], cameras[view], scenes[view],
                          sources, directory, labels[view], pool);
                }
            }
            for (int index = 0; index < frameCount; index++) {
                if (index > 0) {
                    terrain.animate(1f / 30, List.of());
                    if (bendTerrain != null) { bendTerrain.animate(1f / 30, List.of()); }
                }
                if (context) {
                    assertEquals(animationClock.getFloat(terrain), animationClock.getFloat(bendTerrain), 0,
                          "Both context views must sample the same owning material clock");
                }
                // Two-second contacts straddle nominal half-cycles, not every spatially staggered local reset.
                if (contacts && index != 0 && index != frameCount - 1 && index % 15 != 0
                      && (index < 59 || (index % 60 != 59 && index % 60 > 1))) { continue; }
                for (int view = 0; view < 2; view++) {
                    String label = labels[view];
                    if (!context && !dome) {
                        camera.camera.zoom = view == 0 ? .12f : 4;
                        camera.update();
                    }
                    frame.render(terrains[view], cameras[view], scenes[view]);
                    byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, width, height, false);
                    GpuReviewFrame.save(new File(directory, String.format(Locale.ROOT, "%s-%03d.png", label, index)));
                    double delta = 0;
                    if (previous[view] != null) {
                        for (int pixel = 0; pixel < pixels.length; pixel += 4) {
                            for (int channel = 0; channel < 3; channel++) {
                                delta += Math.abs(Byte.toUnsignedInt(pixels[pixel + channel])
                                      - Byte.toUnsignedInt(previous[view][pixel + channel]));
                            }
                        }
                    }
                    float time = animationClock == null ? flowTime(terrains[view])
                          : animationClock.getFloat(terrains[view]);
                    report.append(String.format(Locale.ROOT, "%d,%.7f,%s,%.8f%n", index, time, label,
                          delta / (width * (double) height * 3)));
                    if (contacts || index % 30 == 0) {
                        frame.render(terrains[view], cameras[view], scenes[view]);
                        assertArrayEquals(pixels, ScreenUtils.getFrameBufferPixels(0, 0, width, height, false),
                              "A second view must not advance the molten plates: " + label + " frame " + index);
                    }
                    previous[view] = pixels;
                    if (dome) {
                        if (index == 0) {
                            surface.draw(terrain, cameras[view].camera, false);
                            FloatBuffer control = readFiniteScene(surface, "Unprobed Dome control");
                            for (int channel = 3; channel < control.capacity(); channel += 4) {
                                assertTrue(control.get(channel) != .25f,
                                      "The material diagnostic tag must be absent from production output");
                            }
                        }
                        setMotionProbe(terrain, 1);
                        try {
                            surface.draw(terrain, cameras[view].camera, false);
                            FloatBuffer values = readFiniteScene(surface, "Dome pre-light material");
                            assertEquals(0, values.position(), "Compare the complete absolute-index readback");
                            saveDomeMaterial(values, previousMaterial[view], terrain, cameras[view], scene, directory,
                                  label, index, time, width, height, materialReport);
                            if (contacts || index % 30 == 0) {
                                surface.draw(terrain, cameras[view].camera, false);
                                assertEquals(values, readFiniteScene(surface, "Dome frozen material repeat"),
                                      "Drawing the pre-light diagnostic must not advance any material channel");
                            }
                            previousMaterial[view] = values;
                        } finally { setMotionProbe(terrain, 0); }
                        assertEquals(time, animationClock.getFloat(terrain), 0,
                              "Diagnostic and production share the exact same owning clock");
                    }
                }
            }
            for (int hour : dome ? new int[0] : new int[] { 7, 17 }) {
                frame.configure(settings(hour));
                Vector3 source = frame.lighting().direction();
                camera.setIsometric(false);
                camera.orbit((float) Math.toDegrees(Math.atan2(source.x, -source.y)),
                      (float) Math.toDegrees(Math.acos(-source.z)));
                camera.camera.zoom = .12f;
                camera.center(focus);
                Vector3 mirror = new Vector3(source.x, source.y, -source.z);
                assertTrue(camera.camera.direction.cpy().scl(-1).dot(mirror) > .9999f,
                      "The low-angle view must actually align with the sun reflection");
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(directory, "grazing-" + hour + ".png"));
                report.append(String.format(Locale.ROOT, "grazing hour=%d azimuth=%.5f tilt=%.5f source=%s%n",
                      hour, camera.azimuth(), camera.tilt(), source));
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Motion and grazing review");
        } finally {
            if (bendTerrain != null) { bendTerrain.dispose(); }
            if (dome) {
                Gdx.files.absolute(new File(directory, "material.csv").getAbsolutePath())
                      .writeString(materialReport.toString(), false);
            }
            Gdx.files.absolute(new File(directory, "motion.txt").getAbsolutePath())
                  .writeString(report.toString(), false);
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
            frame.configure(settings(13));
        }
    }


    private static final List<Coords> DOME_HEXES = List.of(new Coords(12, 9), new Coords(13, 13),
          new Coords(12, 14), new Coords(11, 9), new Coords(9, 7));

    /** The same shipped-board capture path as the landscape fixture; preserve every terrain field. */
    static BoardScene domeScene() throws Exception {
        var board = BoardFile.loadSheet(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"), false, true);
        var captured = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                captured.set(fixture.source.takeFrame().scene());
            });
        }
        BoardScene scene = captured.get();
        assertEquals(16, scene.width()); assertEquals(17, scene.height());
        for (Coords hex : DOME_HEXES) {
            assertNotNull(scene.tile(hex), hex.getBoardNum());
            assertTrue(scene.tile(hex).liquid().molten(), hex.getBoardNum() + " must remain actual magma:2");
        }
        return scene;
    }

    private static void reviewDomeMotion(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, BoardScene scene, File output) throws Exception {
        var terrain = new GpuTerrain();
        try {
            stableOrder(terrain);
            var camera = domeCamera(scene, false, new StringBuilder());
            frame.configure(settings(13));
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(.5f, List.of());
            reviewMovingPlates(manager, frame, surface, terrain, camera, scene, output, false, true);
        } finally { terrain.dispose(); }
    }


    /** One frozen production frame, depth occupancy and output-only sculpt roles; no motion-quality assertion. */
    private static void reviewDomeTerraces(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, GpuTerrain terrain, BoardScene scene, File output) throws Exception {
        File directory = new File(output, "dome-terraces");
        assertTrue(directory.mkdir(), "Keep previous terrace evidence: " + directory);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        assertEquals(1280, width, "Fixed source-pixel diagnostic width");
        assertEquals(900, height, "Fixed source-pixel diagnostic height");
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        String expected = System.getProperty("megamek.gpu.expectedRenderer", "");
        assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)), renderer);
        var report = new StringBuilder("Diagnostic only: no core-material or motion test result is implied.\nGPU: ")
              .append(renderer).append("\nClear 13:00, fixed owning clock .5s, no source baseline.\n");
        BoardCamera top = domeCamera(scene, false, report), angled = domeCamera(scene, true, report);
        frame.configure(settings(13));
        frame.prepare(terrain, top, scene);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        for (BoardCamera camera : List.of(top, angled)) {
            frame.prepare(terrain, camera, scene);
            GpuReviewFrame.settle(terrain, null, scene, camera);
            for (int warmup = 0; warmup < 8; warmup++) { frame.render(terrain, camera, scene); }
        }
        frame.render(terrain, top, scene);
        frame.render(terrain, angled, scene);
        float clock = flowTime(terrain);
        assertEquals(.5f, clock, 0, "The first Dome frame uses the original owning clock");
        report.append("Actual bound material clock=").append(clock).append('\n');
        byte[] production = ScreenUtils.getFrameBufferPixels(0, 0, width, height, false);
        GpuReviewFrame.save(new File(directory, "production.png"));
        FloatBuffer originalDepth = terraceDepth(directory, "production-coverage", width, height);
        String original = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String outputLine = "fragColor = vec4(result, 1.0);";
        assertEquals(original.indexOf(outputLine), original.lastIndexOf(outputLine), "Unique sculpt output");
        assertTrue(original.contains(outputLine), "Keep the production sculpt stage intact before output");
        String roles = original.replace(outputLine, """
              // Diagnostic roles only; retain all production geometry, depth and draw order.
              fragColor = vec4(ground ? vec3(1.0, 0.0, 0.0) : cliff ? vec3(0.0, 1.0, 0.0)
                    : plant ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 1.0, 0.0), .25);
              """);
        try {
            var change = manager.apply(Map.of("terrain-sculpt.frag", roles));
            assertTrue(change.success(), change.message());
            frame.render(terrain, angled, scene);
            GpuReviewFrame.save(new File(directory, "sculpt-roles.png"));
            FloatBuffer roleDepth = terraceDepth(directory, "role-coverage", width, height);
            surface.draw(terrain, angled.camera, false);
            FloatBuffer raw = readFiniteScene(surface, "Terrace role output");
            // PNG top-left coordinates, chosen before this probe from the original 1280x900 capture.
            int[][] points = { { 485, 451 }, { 476, 452 }, { 483, 451 }, { 494, 449 }, { 444, 359 } };
            report.append("Role RGB is canonical linear data, not the graded PNG. Alpha .25 tags sculpt only.\n")
                  .append("Red=ground; green=cliff; blue=plant; yellow=other sculpt; untagged=other draw/background.\n")
                  .append("Depth is the actual scene depth exported by the atmosphere composite; 1 is uncovered.\n")
                  .append("x,y,productionR,productionG,productionB,productionDepth,roleDepth,tagged,roleR,roleG,roleB\n");
            for (int[] point : points) for (int offset = -1; offset <= 1; offset++) {
                int x = point[0], y = point[1] + offset, pixel = (height - 1 - y) * width + x, index = pixel * 4;
                report.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.9f,%.9f,%s,%.7f,%.7f,%.7f%n", x, y,
                      Byte.toUnsignedInt(production[index]), Byte.toUnsignedInt(production[index + 1]),
                      Byte.toUnsignedInt(production[index + 2]), originalDepth.get(pixel), roleDepth.get(pixel),
                      raw.get(index + 3) == .25f, raw.get(index) / GpuSceneRadiance.SCALE,
                      raw.get(index + 1) / GpuSceneRadiance.SCALE, raw.get(index + 2) / GpuSceneRadiance.SCALE));
            }
            int coverageChanges = 0;
            float maximumDepthChange = 0;
            for (int pixel = 0; pixel < originalDepth.capacity(); pixel++) {
                if ((originalDepth.get(pixel) < 1) != (roleDepth.get(pixel) < 1)) { coverageChanges++; }
                maximumDepthChange = Math.max(maximumDepthChange, Math.abs(originalDepth.get(pixel) - roleDepth.get(pixel)));
            }
            report.append("Full-frame production/role coverage differences=").append(coverageChanges)
                  .append(", maximum depth difference=").append(maximumDepthChange).append('\n');
        } finally {
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
            frame.render(terrain, angled, scene);
            GpuReviewFrame.save(new File(directory, "production-restored.png"));
            assertArrayEquals(production, ScreenUtils.getFrameBufferPixels(0, 0, width, height, false),
                  "The frozen production frame must survive the output-only diagnostic byte for byte");
            assertEquals(clock, flowTime(terrain), 0, "Source probes cannot advance the owning clock");
            report.append("Restored production: byte-exact.\n");
            Gdx.files.absolute(new File(directory, "terraces.txt").getAbsolutePath()).writeString(report.toString(), false);
        }
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Terrace coverage diagnostic");
    }

    /** Read the completed composite's scene-depth copy; visualize coverage without misleading depth normalization. */
    private static FloatBuffer terraceDepth(File directory, String label, int width, int height) {
        var depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        var coverage = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            coverage.setBlending(Pixmap.Blending.None);
            for (int pixel = 0; pixel < depth.capacity(); pixel++) {
                float value = depth.get(pixel);
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "Finite hardware scene depth");
                coverage.drawPixel(pixel % width, height - 1 - pixel / width, value < 1 ? 0xffffffff : 0x000000ff);
            }
            PixmapIO.writePNG(Gdx.files.absolute(new File(directory, label + ".png").getAbsolutePath()), coverage);
        } finally { coverage.dispose(); }
        return depth;
    }

    private static BoardCamera domeCamera(BoardScene scene, boolean angled, StringBuilder report) {
        List<Vector3> points = new ArrayList<>();
        for (Coords hex : DOME_HEXES) {
            BoardScene.Tile tile = scene.tile(hex);
            for (int corner = 0; corner < 6; corner++) {
                points.add(BoardGeometry.corner(hex, tile.elevation(), corner));
            }
            report.append("Dome hex ").append(hex.getBoardNum()).append(" level=").append(tile.elevation())
                  .append(" liquid=").append(tile.liquid()).append('\n');
        }
        return motionCamera(scene, points, angled ? 45 : 0, angled ? 45 : 0,
              angled ? "Dome angled" : "Dome top", report);
    }

    /** Test-only switch; both branches stay in the same native program throughout the sequence. */
    private static void setMotionProbe(GpuTerrain terrain, float value) throws ReflectiveOperationException {
        int changed = 0;
        for (Object shader : terrainShaders(terrain)) {
            var program = ((BaseShader) shader).program;
            int uniform = program.getUniformLocation("u_magmaMotionProbe");
            if (uniform < 0) { continue; }
            program.bind();
            program.setUniformf(uniform, value);
            changed++;
        }
        assertTrue(changed > 0, "At least one actual molten shader must receive the diagnostic switch");
    }

    /** Data images and per-hex changes bypass lighting/exposure, so a radiance pulse cannot mimic crust motion. */
    private static void saveDomeMaterial(FloatBuffer values, FloatBuffer previous, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, File directory, String view, int frame, float clock, int width, int height,
          StringBuilder report) {
        var image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            image.setBlending(Pixmap.Blending.None);
            image.setColor(0, 0, 0, 1); image.fill();
            for (int index = 0; index < values.capacity(); index += 4) {
                if (values.get(index + 3) != .25f) { continue; }
                image.drawPixel((index / 4) % width, height - 1 - (index / 4) / width,
                      Color.rgba8888(values.get(index) / GpuSceneRadiance.SCALE,
                            values.get(index + 1) / GpuSceneRadiance.SCALE,
                            values.get(index + 2) / GpuSceneRadiance.SCALE, 1));
            }
            PixmapIO.writePNG(Gdx.files.absolute(new File(directory,
                  String.format(Locale.ROOT, "%s-material-%03d.png", view, frame)).getAbsolutePath()), image);
        } finally { image.dispose(); }
        for (Coords hex : DOME_HEXES) {
            BoardScene.Tile tile = scene.tile(hex);
            Vector3 point = BoardGeometry.center(hex, 0).add(0, 0, BoardGeometry.surfaceZ(tile));
            Vector3 screen = camera.camera.project(point.cpy(), 0, 0, width, height);
            Vector3 edge = camera.camera.project(point.cpy().add(0, BoardGeometry.height() * .5f, 0), 0, 0, width, height);
            float pixelRadius = screen.dst(edge);
            int radius = Math.max(3, (int) (pixelRadius * .25f));
            int cx = Math.round(screen.x), cy = Math.round(screen.y);
            assertTrue(cx - radius >= 0 && cx + radius < width && cy - radius >= 0 && cy + radius < height,
                  "Dome region must remain inside the framebuffer: " + hex.getBoardNum());
            // Do not mistake an occluding molten tile for movement in the requested world-space region.
            var hit = terrain.hit(scene, new Ray(point.cpy().mulAdd(camera.camera.direction, -camera.camera.far),
                  camera.camera.direction));
            String centerHit = hit == null ? "none" : hit.coords().getBoardNum();
            boolean visible = hit != null && hit.coords().equals(hex);
            int count = 0, cold = 0, comparable = 0;
            double[] sum = new double[3], delta = new double[3];
            for (int y = cy - radius; y <= cy + radius; y++) for (int x = cx - radius; x <= cx + radius; x++) {
                int index = (y * width + x) * 4;
                if (!visible || values.get(index + 3) != .25f) { continue; }
                count++;
                if (values.get(index) / GpuSceneRadiance.SCALE < .25f) { cold++; }
                boolean common = previous != null && previous.get(index + 3) == .25f;
                if (common) { comparable++; }
                for (int channel = 0; channel < 3; channel++) {
                    sum[channel] += values.get(index + channel) / GpuSceneRadiance.SCALE;
                    if (common) {
                        delta[channel] += Math.abs(values.get(index + channel) - previous.get(index + channel))
                              / GpuSceneRadiance.SCALE;
                    }
                }
            }
            // A hidden region is reported honestly; the top view must expose every requested molten region.
            if (view.equals("top")) { assertTrue(count > 0, "No visible molten pixels at " + hex.getBoardNum()); }
            double pixels = count == 0 ? Double.NaN : count;
            double pairs = comparable == 0 ? Double.NaN : comparable;
            report.append(String.format(Locale.ROOT, "%d,%.7f,%s,%s,%d,%d,%d,%s,%d,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f%n",
                  frame, clock, view, hex.getBoardNum(), cx, cy, radius, centerHit, count, cold / pixels,
                  sum[0] / pixels, sum[1] / pixels, sum[2] / pixels,
                  delta[0] / pairs, delta[1] / pairs, delta[2] / pairs));
        }
    }

    private static BoardScene isolatedPoolScene() {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 9; x++) for (int y = 0; y < 6; y++) {
            BoardLiquid liquid = x >= 5 && x <= 7 && y >= 1 && y <= 4
                  ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE;
            tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.ROCK, null, null,
                  List.of(), List.of(), liquid, null, true));
        }
        var scene = new BoardScene(0, 9, 6, tiles, List.of(), List.of(), -1, "", List.of());
        assertTrue(BoardFlow.calculate(scene).isEmpty(), "An enclosed equal-level pool has no directed reach");
        for (BoardScene.Tile tile : scene.tiles()) {
            if (tile.liquid().molten()) {
                assertTrue(new BoardSurface(scene, tile).waterfalls.isEmpty(), "A still pool has no falls");
            }
        }
        return scene;
    }

    /** A separate still-pool control: the original close scene deliberately includes a downhill spill. */
    private static void reviewIsolatedPool(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, float clock, File output) throws Exception {
        var terrain = new GpuTerrain();
        try {
            var scene = isolatedPoolScene();
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.fit(scene);
            camera.setIsometric(true);
            camera.camera.zoom = .12f;
            camera.center(BoardGeometry.center(LAVA, 0));
            stableOrder(terrain);
            frame.configure(settings(13));
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(clock, List.of());
            reviewMovingPlates(manager, frame, surface, terrain, camera, scene, output, true, false);
        } finally { terrain.dispose(); }
    }

    /** Read the actual pre-deformation selector inputs, preserving the selected source and frozen scene. */
    private static int reviewTransportSelector(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          Map<String, String> sources, File directory, String label, boolean pool) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        frame.render(terrain, camera, scene);
        byte[] original = ScreenUtils.getFrameBufferPixels(0, 0, width, height, false);
        GpuReviewFrame.save(new File(directory, "selector-" + label + "-production.png"));
        surface.draw(terrain, camera.camera, false);
        FloatBuffer production = readFiniteScene(surface, "Selector production control");
        for (int index = 3; index < production.capacity(); index += 4) {
            assertTrue(Math.abs(production.get(index) - .25f) > .001f,
                  "The diagnostic alpha tag must not occur in the unchanged opaque scene");
        }
        String fragment = sources.getOrDefault("terrain-magma-flow.frag",
              GpuShaderSource.readDisk("terrain-magma-flow.frag"));
        String output = "fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);";
        assertTrue(fragment.contains(output), "Measure the actual molten shader's selected branch and inputs");
        // Magma's opaque material has no blending. Alpha is an exact tag, untouched by scene RGB storage units.
        String probe = fragment.replace(output, """
              fragColor = vec4(magmaSheetWeight, length(current),
                    length(vec3(0.0, 0.0, -1.0) + face * face.z), .25);
              """);
        var overrides = new HashMap<>(sources);
        overrides.put("terrain-magma-flow.frag", probe);
        var change = manager.apply(overrides);
        assertTrue(change.success(), change.message());
        int count = 0;
        try {
            surface.draw(terrain, camera.camera, false);
            FloatBuffer values = readFiniteScene(surface, "Molten transport selector");
            int anchored = 0, transported = 0;
            float[] minimum = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
            float[] maximum = new float[3];
            double[] sum = new double[3];
            var image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            try {
                image.setBlending(Pixmap.Blending.None);
                image.setColor(0, 0, 0, 1);
                image.fill();
                for (int index = 0; index < values.capacity(); index += 4) {
                    if (values.get(index + 3) != .25f) { continue; }
                    count++;
                    float selector = values.get(index) / GpuSceneRadiance.SCALE;
                    float current = values.get(index + 1) / GpuSceneRadiance.SCALE;
                    float slope = values.get(index + 2) / GpuSceneRadiance.SCALE;
                    if (selector == 0) { anchored++; }
                    if (selector == 1) { transported++; }
                    for (int channel = 0; channel < 3; channel++) {
                        float value = values.get(index + channel) / GpuSceneRadiance.SCALE;
                        minimum[channel] = Math.min(minimum[channel], value);
                        maximum[channel] = Math.max(maximum[channel], value);
                        sum[channel] += value;
                    }
                    image.drawPixel((index / 4) % width, height - 1 - (index / 4) / width,
                          Color.rgba8888(selector, Math.min(1, current / .05f), Math.min(1, slope), 1));
                }
                PixmapIO.writePNG(Gdx.files.absolute(new File(directory, "selector-" + label + ".png").getAbsolutePath()),
                      image);
            } finally { image.dispose(); }
            assertTrue(count > 1000, "The selector probe must include visible molten surface: " + count);
            var report = new StringBuilder("Actual molten pixels only, tagged by opaque alpha=.25.\n"
                  + "Raw RGB divided by scene storage SCALE once; values are data, not radiance.\n"
                  + "Preview RGB: selector, min(current/.05,1), geometric downhill slope; non-molten black.\n"
                  + "Current is the final interpolated field including slope acceleration, in hex widths/second.\n"
                  + "Slope is pre-deformation geometric downhill magnitude; FFT and material normals are excluded.\n"
                  + "No clock advance between production, probe and restored production.\n");
            report.append(String.format(Locale.ROOT, "pixels=%d anchored=%d mixed=%d transported=%d%n",
                  count, anchored, count - anchored - transported, transported));
            String[] names = { "selector", "fieldCurrent", "geometricSlope" };
            for (int channel = 0; channel < 3; channel++) {
                report.append(String.format(Locale.ROOT, "%s,min=%.9g,mean=%.9g,max=%.9g%n", names[channel],
                      minimum[channel], sum[channel] / count, maximum[channel]));
            }
            Gdx.files.absolute(new File(directory, "selector-" + label + ".txt").getAbsolutePath())
                  .writeString(report.toString(), false);
            System.out.println(label + " transport selector: " + report);
            if (pool) {
                assertEquals(count, anchored, "The isolated flat pool must actually use the anchored branch");
                assertTrue(maximum[1] < .00002f, "No interpolated directed current in the isolated pool");
                assertTrue(maximum[2] < .00002f, "No geometric descent in the isolated pool");
            }
        } finally {
            var restored = manager.apply(sources);
            assertTrue(restored.success(), restored.message());
            frame.render(terrain, camera, scene);
        }
        assertArrayEquals(original, ScreenUtils.getFrameBufferPixels(0, 0, width, height, false),
              "Removing the transport diagnostic restores the exact frozen production image");
        return count;
    }

    /** Fit the actual three-level sheet plus the upper and receiving pools, viewed from downstream. */
    private static BoardCamera fullFallCamera(BoardScene scene, StringBuilder report) {
        Coords upper = new Coords(6, 3), lower = new Coords(6, 4);
        BoardSurface surface = new BoardSurface(scene, scene.tile(upper));
        BoardSurface.Side drop = surface.waterfalls.stream().filter(side ->
              upper.translated(BoardGeometry.edgeDirection(side.edge())).equals(lower)).findFirst().orElseThrow();
        Vector3[][] grid = GpuWaterfall.grid(surface, drop);
        List<Vector3> points = new ArrayList<>();
        for (Vector3[] column : grid) { points.addAll(List.of(column)); }
        points.add(BoardGeometry.center(upper, scene.tile(upper).elevation()));
        points.add(BoardGeometry.center(lower, scene.tile(lower).elevation()));
        Vector3 downstream = surface.crest(drop).normal(.5f);
        float azimuth = (float) Math.toDegrees(Math.atan2(downstream.x, -downstream.y)) + 20;
        BoardCamera result = motionCamera(scene, points, azimuth, 62, "full-fall", report);
        Vector3[] middle = grid[grid.length / 2];
        Vector3 lip = result.camera.project(middle[1].cpy());
        Vector3 foot = result.camera.project(middle[middle.length - 1].cpy());
        assertTrue(Math.abs(lip.y - foot.y) >= Gdx.graphics.getHeight() * .18f,
              "The complete falling sheet must remain large enough to inspect, not a distant overview");
        report.append("Fall: ").append(upper).append(" -> ").append(lower)
              .append("; levels=").append(scene.tile(upper).elevation() - scene.tile(lower).elevation())
              .append("; sheet rows=").append(middle.length).append("; projected lip/foot=")
              .append(lip).append(" / ").append(foot).append('\n');
        return result;
    }

    /** Retain the established winding-river geometry and all metadata; change only its liquid kind. */
    private static BoardScene moltenBendScene() {
        BoardScene river = BoardScenes.scene(BoardScene.Surface.VOLCANO);
        return river.withTiles(river.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(), tile.elevation(),
              tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(),
              tile.decals(), tile.decalsWithoutLimbs(), tile.features(), tile.text(),
              tile.liquid().present() ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : tile.liquid(),
              tile.tileset(), tile.detailedGround(), tile.road(), tile.fireSmoke(), tile.biome(), tile.impassable(),
              tile.blackIce(), tile.cliffTopExits(), tile.bare(), tile.groundCover(),
              tile.ultraSublevel(), tile.tilesetDecals(), tile.tilesetScenery(), tile.appearance(), tile.cover())).toList());
    }

    private static BoardCamera directedBendCamera(BoardScene scene, StringBuilder report) {
        List<Vector3> points = new ArrayList<>();
        List<Vector3> directions = new ArrayList<>();
        Map<Coords, BoardFlow.Current> currents = BoardFlow.calculate(scene);
        for (BoardScene.Tile tile : scene.tiles()) {
            Coords coords = tile.coords();
            if (coords.getX() < 4 || coords.getX() > 9 || coords.getY() < 3 || coords.getY() > 7) { continue; }
            for (int corner = 0; corner < 6; corner++) {
                points.add(BoardGeometry.corner(coords, tile.elevation(), corner));
            }
            BoardFlow.Current current = currents.get(coords);
            if (current == null) { continue; }
            Vector3 direction = new Vector3(-current.u(),
                  current.v() * BoardGeometry.height() / BoardGeometry.width(), 0);
            assertTrue(direction.len() > .02f, "The bend must contain an explicit transported current: " + coords);
            directions.add(direction.nor());
            report.append("Bend current ").append(coords).append('=').append(current).append('\n');
        }
        assertTrue(directions.size() >= 3, "Frame an actual connected molten reach");
        assertTrue(directions.stream().anyMatch(a -> directions.stream().anyMatch(b -> a.dot(b) < .8f)),
              "The framed currents must actually turn, not merely show a straight reach");
        return motionCamera(scene, points, 25, 45, "directed-bend", report);
    }

    /** Orthographic framing of real geometry; all supplied anchors must remain inside the image. */
    private static BoardCamera motionCamera(BoardScene scene, List<Vector3> points, float azimuth, float tilt,
          String label, StringBuilder report) {
        assertFalse(points.isEmpty());
        BoardCamera result = new BoardCamera();
        result.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        result.fit(scene);
        result.setPerspective(false);
        result.setIsometric(false);
        result.orbit(azimuth, tilt);
        Vector3 right = result.camera.direction.cpy().crs(result.camera.up).nor(), up = result.camera.up;
        Vector3 focus = new Vector3();
        for (Vector3 point : points) { focus.add(point); }
        focus.scl(1f / points.size());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Vector3 point : points) {
            Vector3 local = point.cpy().sub(focus);
            minX = Math.min(minX, local.dot(right)); maxX = Math.max(maxX, local.dot(right));
            minY = Math.min(minY, local.dot(up)); maxY = Math.max(maxY, local.dot(up));
        }
        result.camera.zoom = Math.max((maxX - minX) / Gdx.graphics.getWidth(),
              (maxY - minY) / Gdx.graphics.getHeight()) / .84f;
        result.center(focus.mulAdd(right, (minX + maxX) / 2).mulAdd(up, (minY + maxY) / 2));
        for (Vector3 point : points) {
            Vector3 screen = result.camera.project(point.cpy());
            assertTrue(screen.x >= 0 && screen.x < Gdx.graphics.getWidth()
                        && screen.y >= 0 && screen.y < Gdx.graphics.getHeight() && screen.z >= 0 && screen.z <= 1,
                  label + " framing clipped a required geometry anchor: " + screen);
        }
        report.append(label).append(" camera: azimuth=").append(result.azimuth()).append(" tilt=")
              .append(result.tilt()).append(" zoom=").append(result.camera.zoom).append(" focus=")
              .append(focus).append(" anchors=").append(points.size()).append('\n');
        return result;
    }

    /** Optional paired attribution through the actual material and compositor, with one fixed camera/clock. */
    private static void reviewGrazingComponents(GpuShaderManager manager, GpuReviewFrame frame,
          GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera, BoardScene scene, File output)
          throws ReflectiveOperationException {
        String stage = System.getProperty("megamek.gpu.magmaResponseReview");
        assertNotNull(stage, "magmaComponents needs the explicit reflection stage with before/candidate source trees");
        assertNull(System.getProperty("megamek.gpu.shaderAuditBaseline"), "No unrelated source override");
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        assertTrue(renderer.contains(System.getProperty("megamek.gpu.expectedRenderer", "")), renderer);
        assertEquals(1280, surface.scene().getWidth());
        assertEquals(900, surface.scene().getHeight());
        var variants = new java.util.LinkedHashMap<String, Map<String, String>>();
        for (String label : List.of("before", "candidate")) {
            var sources = new HashMap<String, String>();
            for (String name : List.of("magma-flow.glsl", "magma-lighting.glsl", "terrain-sculpt.frag")) {
                File file = new File(stage, label + "/megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/" + name);
                assertTrue(file.isFile(), "Missing explicit response source: " + file);
                sources.put(name, Gdx.files.absolute(file.getAbsolutePath()).readString("UTF-8"));
            }
            variants.put(label, sources);
        }
        File directory = new File(output, "magma-components");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String fragment = GpuShaderSource.readDisk("terrain-magma-flow.frag");
        String outputLine = "fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);";
        String returnLine = "return vec4(albedo + sheen + magmaRadiance(material, bank, strength), 1.0);";
        String environmentLine = "albedo += surfaceEnvironment(material.normal, 0.0, material.surface.g, GROUND_ALBEDO) * material.surface.b;";
        String composite = GpuShaderSource.readDisk("atmosphere-composite.frag");
        assertTrue(fragment.contains(outputLine));
        assertTrue(composite.contains("color = displayScene(linear);"));
        assertTrue(composite.contains("vec4 sand = sandLayer(surfaceDepth);"));
        var field = GpuReviewFrame.class.getDeclaredField("atmosphere");
        field.setAccessible(true);
        var atmosphere = (GpuAtmosphere) field.get(frame); // Borrowed; frame retains ownership.
        StringBuilder report = new StringBuilder("GPU: " + renderer
              + "\nPaired reflection-only sources, fixed geometry/clock/07:00 mirror camera; no lens glare."
              + "\nRGB is canonical before display; heat-selection alpha is the actual heatWeight."
              + "\nPre-shoulder includes actual grade, exposure and halo. Component rows are material outputs."
              + "\nThis hook precedes displayScene white balance/colorAdjust; the review uses their neutral defaults."
              + "\nvariant,hour,component,region,pixels,meanR,meanG,meanB,peakR,peakG,peakB,meanA,peakA\n");
        var clockField = GpuTerrain.class.getDeclaredField("magmaClock");
        clockField.setAccessible(true);
        float clock = clockField.getFloat(terrain);
        assertEquals(clock, flowTime(terrain), "The preceding production draw bound the owning clock");
        try {
            frame.configure(settings(7));
            Vector3 source = frame.lighting().direction();
            camera.setPerspective(false);
            camera.setIsometric(false);
            camera.orbit((float) Math.toDegrees(Math.atan2(source.x, -source.y)),
                  (float) Math.toDegrees(Math.acos(-source.z)));
            camera.camera.zoom = .12f;
            camera.center(BoardGeometry.center(LAVA, scene.tile(LAVA).elevation()));
            for (int hour : new int[] {7, 13, 0}) {
                int[] originalRegions = null;
                float[] originalDepth = null, originalEmission = null;
                for (var variant : variants.entrySet()) {
                    String label = variant.getKey();
                    Map<String, String> base = variant.getValue();
                    var changed = manager.apply(base);
                    assertTrue(changed.success(), changed.message());
                    frame.configure(settings(hour));
                    frame.render(terrain, camera, scene);
                    assertEquals(clock, clockField.getFloat(terrain), "Production drawing preserves the owning clock");
                    assertEquals(clock, flowTime(terrain), "The actual production shader binds the owning clock");
                    GpuReviewFrame.save(new File(directory, label + "-hour-" + hour + ".png"));
                    assertTrue(terrain.environment().has(GpuEnvironmentLighting.TYPE));
                    report.append(String.format(Locale.ROOT, "# %s hour=%d exposure=%.9f clock=%.9f camera=%s light=%s%n",
                          label, hour, atmosphere.exposure(), clock, camera.camera.position, frame.lighting().direction()));
                    String mask = fragment.replace(outputLine, """
                          material = magmaChill(material, shore);
                          fragColor = vec4(face.z, magmaRadiance(material, bank, 1.0).r, 4.0, .375);
                          """);
                    var draft = new HashMap<>(base);
                    draft.put("terrain-magma-flow.frag", mask);
                    changed = manager.apply(draft);
                    assertTrue(changed.success(), changed.message());
                    surface.draw(terrain, camera.camera, false);
                    FloatBuffer material = readFiniteScene(surface, "Molten region mask");
                    int[] regions = new int[material.capacity() / 4];
                    for (int pixel = 0; pixel < regions.length; pixel++) {
                        int at = pixel * 4;
                        if (material.get(at + 3) != .375f) { continue; }
                        assertEquals(4, material.get(at + 2) / GpuSceneRadiance.SCALE, 0);
                        boolean cold = material.get(at + 1) / GpuSceneRadiance.SCALE < .03f;
                        regions[pixel] = material.get(at) / GpuSceneRadiance.SCALE > .95f ? (cold ? 1 : 2) : (cold ? 3 : 4);
                    }
                    float[] depth = grazingDepth(surface);
                    if (originalRegions == null) { originalRegions = regions; originalDepth = depth; }
                    else {
                        assertArrayEquals(originalRegions, regions, "Response edits preserve material/heat coverage");
                        assertArrayEquals(originalDepth, depth, "Response edits preserve every depth sample");
                    }
                    String lighting = base.get("magma-lighting.glsl");
                    assertTrue(lighting.contains(returnLine));
                    assertTrue(lighting.contains("albedo *= ambient + direct;"));
                    assertEquals(label.equals("before"), lighting.contains(environmentLine),
                          "The explicit pair removes the actual environment contribution");
                    if (label.equals("candidate")) assertTrue(lighting.contains("sheen = vec3(0.0);"));
                    String instrumented = lighting.replace("vec3 albedo = materialReflectance(material.albedo);",
                          "vec3 diagnosticEnvironment = vec3(0.0);\nvec3 albedo = materialReflectance(material.albedo);")
                          .replace("albedo *= ambient + direct;",
                                "albedo *= ambient + direct;\nvec3 diagnosticDiffuse = albedo;")
                          .replace(environmentLine, "diagnosticEnvironment = surfaceEnvironment(material.normal, 0.0,"
                                + " material.surface.g, GROUND_ALBEDO) * material.surface.b;\nalbedo += diagnosticEnvironment;");
                    String[][] components = {{"total", "albedo + sheen + magmaRadiance(material, bank, strength)"},
                          {"diffuse", "diagnosticDiffuse"}, {"direct-specular", "sheen"},
                          {"environment-specular", "diagnosticEnvironment"},
                          {"emission", "magmaRadiance(material, bank, strength)"}};
                    for (String[] component : components) {
                        draft = new HashMap<>(base);
                        draft.put("magma-lighting.glsl", instrumented.replace(returnLine,
                              "return vec4(" + component[1] + ", 1.0);"));
                        changed = manager.apply(draft);
                        assertTrue(changed.success(), changed.message());
                        surface.draw(terrain, camera.camera, false);
                        float[] pixels = new float[regions.length * 4];
                        readFiniteScene(surface, component[0]).get(pixels);
                        surface.draw(terrain, camera.camera, false);
                        float[] repeat = new float[pixels.length];
                        readFiniteScene(surface, "Repeated " + component[0]).get(repeat);
                        assertArrayEquals(pixels, repeat, "Frozen component repeat: " + component[0]);
                        assertArrayEquals(originalDepth, grazingDepth(surface), "Component edits retain actual geometry");
                        if (component[0].equals("emission")) {
                            if (originalEmission == null) { originalEmission = pixels; }
                            else for (int pixel = 0; pixel < regions.length; pixel++) {
                                if (regions[pixel] == 0) { continue; }
                                for (int channel = 0; channel < 4; channel++) assertEquals(originalEmission[pixel * 4 + channel],
                                      pixels[pixel * 4 + channel], "Reflection-only edits preserve raw emitted light");
                            }
                        }
                        if (label.equals("candidate") && component[0].endsWith("specular")) {
                            for (int pixel = 0; pixel < regions.length; pixel++) if (regions[pixel] != 0) {
                                for (int channel = 0; channel < 3; channel++) assertEquals(0, pixels[pixel * 4 + channel], 0,
                                      "Actual candidate specular contribution must be zero");
                            }
                        }
                        if (label.equals("before") && hour == 7 && component[0].endsWith("specular")) {
                            boolean positive = false;
                            for (int pixel = 0; pixel < regions.length; pixel++) if (regions[pixel] != 0) {
                                positive |= pixels[pixel * 4] > 0;
                            }
                            assertTrue(positive, "The original mirror view exercises " + component[0]);
                        }
                        grazingStatistics(report, label, hour, component[0], regions, pixels);
                        // A zero-output component can optimize the time uniform away; the terrain owns time.
                        assertEquals(clock, clockField.getFloat(terrain), "Diagnostics never advance the owning flow clock");
                    }
                    // The atmosphere still owns the untouched production frame rendered above. Reuse its actual
                    // composite and output target overload, observing boundaries without copying grade/heat math.
                    for (String probe : List.of("heat-selection", "pre-shoulder")) {
                        String anchor = probe.equals("heat-selection") ? "vec4 sand = sandLayer(surfaceDepth);"
                              : "color = displayScene(linear);";
                        String value = probe.equals("heat-selection")
                              ? "vec4(storeScene(heat), heatWeight(max(color, vec3(0.0))))"
                              : "vec4(storeScene(linear), 1.0)";
                        draft = new HashMap<>(base);
                        draft.put("atmosphere-composite.frag", composite.replace(anchor,
                              "fragColor = " + value + "; DEPTH = depth; return;\n" + anchor));
                        changed = manager.apply(draft);
                        assertTrue(changed.success(), changed.message());
                        atmosphere.end(camera.camera, terrain, scene, 0, null, false, surface.scene());
                        float[] pixels = new float[regions.length * 4];
                        readFiniteScene(surface, probe).get(pixels);
                        grazingStatistics(report, label, hour, probe, regions, pixels);
                    }
                    assertEquals(clock, clockField.getFloat(terrain), "Compositor diagnostics preserve the owning flow clock");
                    // Restoring material sources rebuilds programs. Their uniforms are zero until a real terrain
                    // draw binds them; a composite-only pass cannot be used as a shader-clock observation.
                    changed = manager.apply(base);
                    assertTrue(changed.success(), changed.message());
                    surface.draw(terrain, camera.camera, false);
                    assertEquals(clock, clockField.getFloat(terrain), "Restoring production preserves the owning clock");
                    assertEquals(clock, flowTime(terrain), "The restored production shader receives the same clock");
                    assertArrayEquals(originalDepth, grazingDepth(surface), "Restored production retains every depth sample");
                }
            }
        } finally {
            Gdx.files.absolute(new File(directory, "components.csv").getAbsolutePath()).writeString(report.toString(), false);
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
            surface.pixels().end();
            frame.configure(settings(13));
        }
    }

    private static float[] grazingDepth(GpuSceneTestOutput surface) {
        var target = surface.scene();
        target.begin();
        try {
            FloatBuffer buffer = BufferUtils.newFloatBuffer(target.getWidth() * target.getHeight());
            Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(), GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer);
            float[] result = new float[buffer.capacity()];
            buffer.get(result);
            return result;
        } finally { target.end(); }
    }

    private static void grazingStatistics(StringBuilder report, String variant, int hour, String component,
          int[] regions, float[] pixels) {
        String[] labels = {"excluded", "flat-cold", "flat-hot", "inclined-cold", "inclined-hot"};
        for (int region = 1; region < labels.length; region++) {
            int count = 0;
            double[] sum = new double[4], peak = new double[4];
            for (int pixel = 0; pixel < regions.length; pixel++) {
                if (regions[pixel] != region) { continue; }
                count++;
                for (int channel = 0; channel < 4; channel++) {
                    double value = pixels[pixel * 4 + channel] / (channel == 3 ? 1 : GpuSceneRadiance.SCALE);
                    sum[channel] += value;
                    peak[channel] = Math.max(peak[channel], value);
                }
            }
            if (region <= 2) assertTrue(count > 0, "The view includes " + labels[region]);
            if (component.equals("diffuse") && region == 1) assertTrue(peak[0] > 0, "Cold skin retains reflected light");
            report.append(String.format(Locale.ROOT, "%s,%d,%s,%s,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                  variant, hour, component, labels[region], count,
                  count == 0 ? 0 : sum[0] / count, count == 0 ? 0 : sum[1] / count, count == 0 ? 0 : sum[2] / count,
                  peak[0], peak[1], peak[2], count == 0 ? 0 : sum[3] / count, peak[3]));
        }
    }

    /** Shader object identities must not reorder coplanar terrain during controlled source comparisons. */
    private static void stableOrder(GpuTerrain terrain) throws ReflectiveOperationException {
        var batchField = GpuTerrain.class.getDeclaredField("batch");
        batchField.setAccessible(true);
        var sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        sorter.set(batchField.get(terrain), new DefaultRenderableSorter());
    }

    /** Isolate emitted fissure light from reflected lava fill and the scene's display transfer. */
    private static void checkCrustEmission(GpuShaderManager manager, GpuSceneTestOutput surface,
          GpuTerrain terrain, BoardCamera camera, BoardScene scene, int[] original) {
        String lighting = GpuShaderSource.readDisk("magma-lighting.glsl");
        String emission = "+ magmaRadiance(material, bank, strength)";
        assertTrue(lighting.contains(emission), "The emission probe must replace the actual volcanic source");
        var change = manager.apply(Map.of("magma-lighting.glsl", lighting.replace(emission, "")));
        assertTrue(change.success(), change.message());
        try {
            surface.draw(terrain, camera.camera, false);
            int[] extinguished = readCrust(camera, scene);
            int emitting = 0, bright = 0;
            for (int index = 0; index < original.length; index++) {
                int red = (original[index] >>> 24) - (extinguished[index] >>> 24);
                int green = (original[index] >>> 16 & 255) - (extinguished[index] >>> 16 & 255);
                if (red > 8 && red > green * 1.3) { emitting++; }
                if (red > 90) { bright++; }
            }
            double fraction = emitting / (double) original.length;
            assertTrue(fraction > .015, "Fissure emission must visibly cover the sampled crust: " + fraction);
            assertTrue(bright > 0, "Some fissures must retain strong red emission even with all sky/sun light off");
        } finally {
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
            surface.draw(terrain, camera.camera, false);
        }
        assertArrayEquals(original, readCrust(camera, scene), "Removing the probe must restore the same crust pixels");
    }

    /** The original molten material remains finite, bounded and visibly emissive in canonical radiance. */
    private static void checkMoltenMaterial(GpuShaderManager manager, GpuSceneTestOutput surface,
          GpuTerrain terrain, BoardCamera camera) throws Exception {
        var assets = (GpuAssets) GpuTestSupport.field(terrain, "assets");
        float sourceScale = assets.lavaEmission().scale();
        String fragment = GpuShaderSource.readDisk("terrain-magma-flow.frag");
        String output = "fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);";
        assertTrue(fragment.contains(output), "The material probe must read the completed molten material");
        String probe = fragment.replace(output, """
              material = magmaChill(material, shore);
              fragColor = vec4(magmaRadiance(material, bank, 1.0).r, material.surface.g, 4.0, 1.0);
              """);
        // Validate the unchanged scene as well: a diagnostic shader can remove reflected-light calculations.
        surface.draw(terrain, camera.camera, false);
        readFiniteScene(surface, "Production scene");
        var change = manager.apply(Map.of("terrain-magma-flow.frag", probe));
        assertTrue(change.success(), change.message());
        try {
            surface.draw(terrain, camera.camera, false);
            var pixels = readFiniteScene(surface, "Molten material probe");
            int molten = 0, cold = 0;
            float minimumColdRoughness = 1, minimumRoughness = 1, maximumRoughness = 0, peak = 0;
            for (int index = 0; index < pixels.capacity(); index += 4) {
                // Finiteness was checked before the marker test; a NaN must never qualify as a molten tag.
                if (Math.abs(pixels.get(index + 2) / GpuSceneRadiance.SCALE - 4) > .001f) { continue; }
                float emission = pixels.get(index) / GpuSceneRadiance.SCALE;
                float roughness = pixels.get(index + 1) / GpuSceneRadiance.SCALE;
                molten++;
                peak = Math.max(peak, emission);
                minimumRoughness = Math.min(minimumRoughness, roughness);
                maximumRoughness = Math.max(maximumRoughness, roughness);
                if (emission < .03f) {
                    cold++;
                    minimumColdRoughness = Math.min(minimumColdRoughness, roughness);
                }
            }
            double fraction = cold / (double) molten;
            System.out.printf(Locale.ROOT,
                  "Molten material: pixels=%d cold=%.4f minimumColdRoughness=%.4f minimumRoughness=%.4f peakRed=%.4f%n",
                  molten, fraction, minimumColdRoughness, minimumRoughness, peak);
            assertTrue(molten > 1000, "The probe must cover the actual melt and falls");
            // Original HEAD exposes glossier melt; the rejected later skin's coverage/roughness floors do not apply.
            assertTrue(minimumRoughness >= 0 && maximumRoughness <= 1,
                  "Perceptual roughness must remain in [0,1]: " + minimumRoughness + ".." + maximumRoughness);
            // PNG RGB is bounded by one; filtering, phase mixing, shore and bank attenuation cannot amplify it.
            // The separate constant-map provider test proves scale and channel values, including a zero source.
            assertTrue((sourceScale == 0 ? peak == 0 : peak > 0) && peak <= sourceScale * 1.001f,
                  "Exposed rifts must retain the authored source bound: " + peak + " / " + sourceScale);
        } finally {
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
            surface.draw(terrain, camera.camera, false);
        }
    }

    /** Reject nonfinite output before display conversion or material-marker classification can conceal it. */
    private static FloatBuffer readFiniteScene(GpuSceneTestOutput surface, String label) {
        var target = surface.scene();
        target.begin();
        var pixels = BufferUtils.newFloatBuffer(target.getWidth() * target.getHeight() * 4);
        Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
        target.end();
        int invalid = 0;
        var first = new StringBuilder();
        for (int index = 0; index < pixels.capacity(); index += 4) {
            if (Float.isFinite(pixels.get(index)) && Float.isFinite(pixels.get(index + 1))
                  && Float.isFinite(pixels.get(index + 2)) && Float.isFinite(pixels.get(index + 3))) { continue; }
            invalid++;
            if (invalid <= 8) {
                first.append(String.format(Locale.ROOT, " (%d,%d:%s,%s,%s,%s)",
                      (index / 4) % target.getWidth(), (index / 4) / target.getWidth(),
                      pixels.get(index), pixels.get(index + 1), pixels.get(index + 2), pixels.get(index + 3)));
            }
        }
        assertEquals(0, invalid, label + " nonfinite pixels; first:" + first);
        return pixels;
    }

    private static void checkGravity(GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera, BoardScene scene, GpuOcean waves)
          throws ReflectiveOperationException {
        float start = flowTime(terrain);
        terrain.setGravity(.25f);
        terrain.animate(2, List.of());
        sample(surface, terrain, camera, scene, LAVA);
        assertEquals(start + 1, flowTime(terrain), 1e-5,
              "At quarter gravity the rendered lava must advect at half its 1 g speed");
        terrain.setGravity(4);
        terrain.animate(0, List.of());
        sample(surface, terrain, camera, scene, LAVA);
        assertEquals(start + 1, flowTime(terrain), 1e-5, "Changing gravity must not restart the advection phase");
        terrain.animate(.5f, List.of());
        sample(surface, terrain, camera, scene, LAVA);
        assertEquals(start + 2, flowTime(terrain), 1e-5,
              "At fourfold gravity the rendered lava must advect at twice its 1 g speed");
        terrain.setGravity(0);
        terrain.animate(0, List.of());
        int[] frozen = sample(surface, terrain, camera, scene, LAVA);
        terrain.animate(1, List.of());
        assertArrayEquals(frozen, sample(surface, terrain, camera, scene, LAVA), "Zero gravity stops both lava motion paths");
        assertNull(waves.texture(), "Zero gravity releases the lava simulation");
        assertEquals(start + 2, flowTime(terrain), 1e-5, "A paused molten surface retains its phase");
        terrain.update(scene);
        terrain.animate(0, List.of());
        assertNull(waves.texture(), "The zero-gravity terrain presentation removes lava along with water");
        assertTrue(scene.tile(LAVA).liquid().molten(), "The scenario's source tiles are unchanged");
        terrain.setGravity(1);
        terrain.update(scene);
        terrain.animate(0, List.of());
        assertNotNull(waves.texture(), "Restoring gravity returns the molten surface");
        sample(surface, terrain, camera, scene, LAVA);
        assertEquals(start + 2, flowTime(terrain), 1e-5, "Restoring the board does not advance advection");
    }

    private record LavaSample(int[] display, float[] radiance) { }

    private static void checkContinuity(GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, File output) throws ReflectiveOperationException {
        // Spatial phase staggering means this is not a global cycle boundary. Test the actual continuous response
        // as elapsed time shrinks, rather than imposing the old raw-window byte cutoff after sRGB encoding.
        terrain.animate(4.999f, List.of());
        LavaSample start = continuitySample(surface, terrain, camera, scene, output, "start");
        assertArrayEquals(start.display(), sample(surface, terrain, camera, scene, LAVA),
              "A frozen magma clock must reproduce the identical displayed pixels");
        float clock = flowTime(terrain);
        terrain.animate(.0002f, List.of());
        LavaSample tiny = continuitySample(surface, terrain, camera, scene, output, "0.0002s");
        terrain.animate(.0018f, List.of());
        LavaSample small = continuitySample(surface, terrain, camera, scene, output, "0.002s");
        assertEquals(clock + .002f, flowTime(terrain), 1e-5, "The short-interval sample uses the actual shared clock");
        terrain.animate(.018f, List.of());
        LavaSample frame = continuitySample(surface, terrain, camera, scene, output, "0.02s");
        double tinyDelta = radianceDifference(start.radiance(), tiny.radiance());
        double smallDelta = radianceDifference(start.radiance(), small.radiance());
        double frameDelta = radianceDifference(start.radiance(), frame.radiance());
        String details = String.format(Locale.ROOT,
              "Magma continuity: relative linear deltas .0002/.002/.02s = %.8f/%.8f/%.8f; display mean deltas = %.6f/%.6f/%.6f",
              tinyDelta, smallDelta, frameDelta, difference(start.display(), tiny.display()),
              difference(start.display(), small.display()), difference(start.display(), frame.display()));
        System.out.println(details);
        Gdx.files.absolute(new File(output, "magma-continuity.txt").getAbsolutePath()).writeString(details, false);
        // Each interval is ten times shorter. Allow curvature in the moving material, but require a substantial
        // decrease. Two half-float rounding errors contribute at most about one relative normal-range ULP.
        double rounding = 1.0 / 1024;
        assertTrue(tinyDelta <= smallDelta * .25 + rounding, "Shrinking the interval must remove a pop: " + details);
        assertTrue(smallDelta <= frameDelta * .25 + rounding, "Sub-frame motion must approach zero continuously: " + details);
    }

    private static LavaSample continuitySample(GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, File output, String label) {
        int[] display = sample(surface, terrain, camera, scene, LAVA);
        GpuReviewFrame.save(new File(output, "magma-continuity-" + label + ".png"));
        Vector3 screen = camera.camera.project(BoardGeometry.center(LAVA, 0)
              .add(0, 0, BoardGeometry.surfaceZ(scene.tile(LAVA))));
        var buffer = BufferUtils.newFloatBuffer(48 * 48 * 4);
        surface.scene().begin();
        Gdx.gl.glReadPixels(Math.round(screen.x) - 24, Math.round(screen.y) - 24, 48, 48,
              GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        surface.scene().end();
        float[] radiance = new float[buffer.capacity()];
        buffer.get(radiance);
        for (int index = 0; index < radiance.length; index += 4) {
            for (int channel = 0; channel < 3; channel++) {
                radiance[index + channel] /= GpuSceneRadiance.SCALE;
                assertTrue(Float.isFinite(radiance[index + channel]), "Lava radiance must remain finite");
            }
        }
        return new LavaSample(display, radiance);
    }

    private static double radianceDifference(float[] first, float[] second) {
        double difference = 0, magnitude = 0;
        for (int index = 0; index < first.length; index += 4) {
            for (int channel = 0; channel < 3; channel++) {
                difference += Math.abs(first[index + channel] - second[index + channel]);
                magnitude += Math.max(first[index + channel], second[index + channel]);
            }
        }
        return difference / Math.max(magnitude, 1e-9);
    }

    /** Read the actual liquid shader input, catching missing or raw-clock uniform bindings as well as timing bugs. */
    private static Iterable<?> terrainShaders(GpuTerrain terrain) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        var provider = ((ModelBatch) field.get(terrain)).getShaderProvider();
        if (provider instanceof GpuShaderProvider editable) { provider = editable.active(); }
        var shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        return (Iterable<?>) shaders.get(provider);
    }

    private static float flowTime(GpuTerrain terrain) throws ReflectiveOperationException {
        float latest = Float.NaN;
        for (Object shader : terrainShaders(terrain)) {
            var program = ((BaseShader) shader).program;
            int uniform = program.getUniformLocation("u_magmaTime");
            if (uniform >= 0) {
                float value = org.lwjgl.opengl.GL20.glGetUniformf(program.getHandle(), uniform);
                // Editing rebuilds historical variants too; unused variants retain zero/stale uniforms. Every
                // live lava draw receives the same monotonic clock, so its latest binding is the greatest value.
                latest = Float.isNaN(latest) ? value : Math.max(latest, value);
            }
        }
        if (Float.isNaN(latest)) { throw new AssertionError("No live lava advection program"); }
        return latest;
    }

    static BoardScene scene(boolean cooled) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 6; y++) {
                boolean inside = y > 0 && y < 5 && x > 0 && x < 8 && x != 4;
                BoardLiquid liquid = inside ? new BoardLiquid(x < 4 || cooled ? BoardLiquid.Kind.MAGMA_CRUST
                      : BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE;
                // A gentle grade at the back and a three-level spill in front exercise both forms of flow.
                tiles.add(new BoardScene.Tile(new Coords(x, y), y < 2 ? 3 : y < 4 ? 2 : -1, -1, false, 0,
                      BoardScene.Surface.ROCK, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 9, 6, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static int[] sample(GpuSceneTestOutput surface, GpuTerrain terrain, BoardCamera camera, BoardScene scene, Coords tile) {
        surface.draw(terrain, camera.camera, false);
        return read(camera, scene, tile);
    }

    private static int[] read(BoardCamera camera, BoardScene scene, Coords tile) {
        Vector3 screen = camera.camera.project(BoardGeometry.center(tile, 0).add(0, 0, BoardGeometry.surfaceZ(scene.tile(tile))));
        Pixmap image = ScreenUtils.getFrameBufferPixmap(Math.round(screen.x) - 24, Math.round(screen.y) - 24, 48, 48);
        try {
            int[] result = new int[48 * 48];
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) { result[y * 48 + x] = image.getPixel(x, y); }
            }
            return result;
        } finally { image.dispose(); }
    }

    private static double hot(int[] pixels) {
        return Arrays.stream(pixels).filter(p -> (p >>> 24) > 90 && (p >>> 24) > (p >>> 16 & 255) * 1.3
              && (p >>> 16 & 255) > (p >>> 8 & 255) * 1.5).count() / (double) pixels.length;
    }

    /** Sample the three crust tile interiors: one tiny crop can land almost wholly inside a cold plate. */
    private static int[] readCrust(BoardCamera camera, BoardScene scene) {
        return List.of(new Coords(1, 2), CRUST, new Coords(3, 2)).stream()
              .flatMapToInt(coords -> Arrays.stream(read(camera, scene, coords))).toArray();
    }

    private static double difference(int[] a, int[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 24, 16, 8 }) { sum += Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)); }
        }
        return sum / (a.length * 3);
    }
}
