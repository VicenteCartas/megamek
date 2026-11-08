/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardFoliageTest {
    /** Loading a glowing fungal material decodes its emission map with libGDX. */
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @ParameterizedTest
    @ValueSource(strings = { "vegetation/pine", "vegetation/pine-tall", "vegetation/pine-broad", "vegetation/pine-snow", "vegetation/pine-tall-snow", "vegetation/pine-broad-snow",
          "vegetation/pine-slender", "vegetation/pine-layered", "vegetation/pine-slender-snow", "vegetation/pine-layered-snow" })
    void upperPineBranchesStayAttachedToTheirBentTrunk(String name) {
        File root = new File(Configuration.dataDir(), "models/board");
        var near = RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath()).getFirst();
        Map<String, String> materials = new HashMap<>();
        partMaterials(near.nodes, materials);
        var mesh = near.meshes.first();
        float[] v = mesh.vertices;
        List<Integer> bark = new ArrayList<>();
        var roots = new HashSet<Vector3>();
        for (var part : mesh.parts) {
            if (materials.get(part.id).startsWith("bark")) {
                for (short index : part.indices) { bark.add(Short.toUnsignedInt(index) * RigidGlb.STRIDE); }
            } else if (materials.get(part.id).endsWith("-cutout")) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    for (int edge = 0; edge < 3; edge++) {
                        int a = corner(part.indices, i + edge), b = corner(part.indices, i + (edge + 1) % 3);
                        // The branch texture's stem starts midway along its bottom edge, which a folded card splits.
                        float t = (.5f - v[a + 10]) / (v[b + 10] - v[a + 10]);
                        if (v[a + 11] > .97f && v[b + 11] > .97f && Math.abs(v[a + 10] - v[b + 10]) > .3f && t >= 0 && t <= 1) {
                            roots.add(new Vector3(v[a] + t * (v[b] - v[a]), v[a + 1] + t * (v[b + 1] - v[a + 1]),
                                  v[a + 2] + t * (v[b + 2] - v[a + 2])));
                        }
                    }
                }
            }
        }
        int checked = 0;
        for (Vector3 branch : roots) {
            // The thin upper crown exposed the mismatch: a straight crown beside a leaning stem.
            if (branch.z < 20 || branch.z > 26) { continue; }
            var section = new BoundingBox().inf();
            for (int i = 0; i < bark.size(); i += 3) {
                for (int edge = 0; edge < 3; edge++) {
                    int a = bark.get(i + edge), b = bark.get(i + (edge + 1) % 3);
                    float rise = v[b + 2] - v[a + 2];
                    if (Math.abs(rise) < .0001f) { continue; }
                    float t = (branch.z - v[a + 2]) / rise;
                    if (t >= 0 && t <= 1) {
                        section.ext(v[a] + t * (v[b] - v[a]), v[a + 1] + t * (v[b + 1] - v[a + 1]), branch.z);
                    }
                }
            }
            assertTrue(section.isValid(), name + " must have a trunk at the branch root");
            assertTrue(section.getCenter(new Vector3()).dst(branch) < 1.25f,
                  name + " branch must follow the trunk at " + branch);
            checked++;
        }
        assertTrue(checked > 8, "Check several branches around the upper trunk: " + name);
    }

    @ParameterizedTest
    @CsvSource({ "'', 0, temperate", "'', 2, highland", "rock, 0, rocky", "volcano, 0, volcano",
          "dirt, 0, wetland", "lunar, 0, barren", "Desert, 0, desert",
          "sand, 0, desert", "snow, 0, snow" })
    void lowWoodsUseBiomeShrubsAtEveryDensity(String theme, int elevation, String family) {
        Hex hex = new Hex(elevation);
        hex.setTheme(theme);
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 1));
        Coords coords = new Coords(3, 2);
        for (int density = 1; density <= 3; density++) {
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            var features = BoardFeatures.capture(hex, coords, Map.of());
            assertTrue(!features.isEmpty() && features.size() <= BoardTreeCoverage.canopyLimit(density));
            assertTrue(features.stream().allMatch(feature -> shrub(feature, family)
                  && feature.kind() == BoardScene.FeatureKind.TREE));
            BoardFeaturesTest.assertWoodsHeights(features, 1, density);
            assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()), "Roots remain deterministic");
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
            assertFalse(BoardFeatures.capture(hex, coords, Map.of()).stream()
                  .anyMatch(feature -> feature.asset().startsWith("vegetation/foliage-")), "Tall woods retain their tree models");
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 1));
        }
    }

    @Test
    void terrainOverridesAndJungleChooseUnderstoryWithoutInventingCover() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 1));
        Coords coords = new Coords(1, 1);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).isEmpty());
        hex.addTerrain(new Terrain(Terrains.WOODS, 2));
        for (var entry : Map.of(Terrains.SWAMP, "wetland", Terrains.MUD, "wetland", Terrains.WATER, "wetland",
              Terrains.SAND, "temperate", Terrains.TUNDRA, "highland", Terrains.PAVEMENT, "temperate",
              Terrains.SNOW, "snow").entrySet()) {
            hex.addTerrain(new Terrain(entry.getKey(), 1));
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
                  .allMatch(feature -> shrub(feature, entry.getValue())));
            hex.removeTerrain(entry.getKey());
        }
        hex.removeTerrain(Terrains.WOODS);
        hex.addTerrain(new Terrain(Terrains.JUNGLE, 2));
        hex.setTheme("desert");
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .allMatch(feature -> shrub(feature, "jungle")));
        hex.addTerrain(new Terrain(Terrains.SNOW, 1));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .allMatch(feature -> shrub(feature, "snow")));
        hex.removeTerrain(Terrains.JUNGLE);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "temperate", "highland", "rocky", "wetland", "desert", "jungle", "barren", "snow" })
    void glbContainsThreeTexturedMeshesAndAnImpostorInTheSameRootCoordinates(String family) {
        File root = new File(Configuration.dataDir(), "models/board");
        var levels = RigidGlb.loadLods(RigidGlb.source(root, "vegetation/foliage-" + family), root.toPath());
        assertEquals(4, levels.size());
        assertNotSame(levels.get(0), levels.get(1));
        assertNotSame(levels.get(1), levels.get(2));
        assertNotSame(levels.get(2), levels.get(3));
        int previous = Integer.MAX_VALUE;
        for (int lod = 0; lod < levels.size(); lod++) {
            var data = levels.get(lod);
            int triangles = 0;
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (var mesh : data.meshes) {
                for (var part : mesh.parts) { triangles += part.indices.length / 3; }
                for (int vertex = 0; vertex < mesh.vertices.length; vertex += RigidGlb.STRIDE) {
                    low = Math.min(low, mesh.vertices[vertex + 2]);
                    high = Math.max(high, mesh.vertices[vertex + 2]);
                }
            }
            assertTrue(triangles > 0 && triangles < previous);
            boolean authored = !family.equals("jungle");
            assertTrue(triangles <= (authored ? List.of(24000, 8000, 2200, 12) : List.of(480, 240, 96, 12)).get(lod));
            assertEquals(0, low, .001f, "All detail levels remain grounded");
            assertTrue(high > 14 && high <= 19, "LOD changes must preserve the authored low silhouette");
            if (lod == 0) { assertEquals(18, high - low, .001f); }
            // Every mesh part binds its source-aligned relief and surface maps; impostors their baked colour and shape.
            for (var material : data.materials) {
                assertEquals(material.id.equals("impostor") ? 2 : 3, material.textures.size);
                for (var texture : material.textures) {
                    String file = texture.fileName.replace('\\', '/');
                    assertTrue(file.contains("/textures/vegetation/"), file);
                    var image = ((RigidGlb.Data) data).images.get(texture.fileName);
                    assertTrue(image != null && image.file() != null && Files.isRegularFile(Path.of(image.file())), file);
                }
                if (material.id.endsWith("-cutout")) {
                    assertEquals(authored ? .3f : .5f, ((RigidGlb.Data) data).alphaTests.get(material.id));
                }
            }
            previous = triangles;
        }
    }

    /**
     * Impostor cards carry their plant's own colours: those of its far mesh, which they stand for. Decode detail texels
     * before multiplying the mesh's linear vertex colour, then display-encode the result to compare with the PNG atlas.
     * The estimate weights the mesh's faces by the area the three card views show.
     */
    @Test
    void impostorAtlasesHoldTheirPlantsColours() throws IOException {
        File root = new File(Configuration.dataDir(), "models/board");
        File[] atlases = new File(root, "textures/vegetation/impostors")
              .listFiles((folder, name) -> name.endsWith(".png") && !name.endsWith("_normal.png"));
        assertTrue(atlases != null && atlases.length > 0);
        float[][] views = { { 0, -.866f, .5f }, { .866f, 0, .5f }, { 0, 0, 1 } };
        for (File atlas : atlases) {
            // A plant's file lives in vegetation/ unless its family has a folder of its own (fungus/, crystal/).
            String name = atlas.getName().replace(".png", "");
            String plant = java.util.stream.Stream.of("vegetation/", "fungus/", "mars/", "crystal/").map(folder -> folder + name)
                  .filter(path -> new File(root, path + ".glb").isFile()).findFirst().orElse("vegetation/" + name);
            // Crystal clusters draw shards at every level: their white atlas is only the crown mask BoardTreeCoverage reads.
            if (plant.startsWith("crystal/")) { continue; }
            var levels = RigidGlb.loadLods(RigidGlb.source(root, plant), root.toPath());
            var far = (RigidGlb.Data) levels.get(levels.size() - 2);
            Map<String, BufferedImage> textures = new HashMap<>();
            Map<String, double[]> cutoutMeans = new HashMap<>();
            for (var material : far.materials) {
                var image = far.images.get(material.textures.first().fileName);
                byte[] encoded = image.file() != null ? Files.readAllBytes(Path.of(image.file())) : image.encoded();
                textures.put(material.id, ImageIO.read(new ByteArrayInputStream(encoded)));
                if (material.id.endsWith("-cutout")) {
                    BufferedImage texture = textures.get(material.id);
                    double[] mean = new double[4];
                    for (int y = 0; y < texture.getHeight(); y++) {
                        for (int x = 0; x < texture.getWidth(); x++) {
                            int texel = texture.getRGB(x, y);
                            if (texel >>> 24 < 128) { continue; }
                            for (int c = 0; c < 3; c++) {
                                mean[c] += GpuColor.linear((texel >> 16 - c * 8 & 255) / 255f);
                            }
                            mean[3]++;
                        }
                    }
                    assertTrue(mean[3] > 0, "Foliage must have visible texels");
                    for (int c = 0; c < 3; c++) { mean[c] /= mean[3]; }
                    mean[3] /= texture.getWidth() * texture.getHeight();
                    cutoutMeans.put(material.id, mean);
                }
            }
            Map<String, String> partMaterials = new HashMap<>();
            partMaterials(far.nodes, partMaterials);
            var mesh = far.meshes.first();
            float[] v = mesh.vertices;
            double[] expected = new double[3];
            double weight = 0;
            for (var part : mesh.parts) {
                BufferedImage texture = textures.get(partMaterials.get(part.id));
                if (texture == null) { continue; }
                double[] mean = cutoutMeans.get(partMaterials.get(part.id));
                for (int i = 0; i < part.indices.length; i += 3) {
                    int a = corner(part.indices, i), b = corner(part.indices, i + 1), c = corner(part.indices, i + 2);
                    double shown = shown(face(v, a, b, c), views);
                    // A card's centroid may be a transparent twig gap. Integrate the repeated branch texture over
                    // opaque texels instead of treating its invisible rectangular background as leaf colour.
                    if (mean != null) { shown *= mean[3]; }
                    float u = (v[a + 10] + v[b + 10] + v[c + 10]) / 3, w = (v[a + 11] + v[b + 11] + v[c + 11]) / 3;
                    int x = Math.floorMod((int) Math.floor(u * texture.getWidth()), texture.getWidth());
                    int y = Math.floorMod((int) Math.floor(w * texture.getHeight()), texture.getHeight());
                    int texel = texture.getRGB(x, y);
                    for (int channel = 0; channel < 3; channel++) {
                        float colour = (v[a + 6 + channel] + v[b + 6 + channel] + v[c + 6 + channel]) / 3;
                        float sampled = mean == null ? GpuColor.linear((texel >> 16 - 8 * channel & 255) / 255f)
                              : (float) mean[channel];
                        expected[channel] += shown * 255 * GpuColor.display(sampled * colour);
                    }
                    weight += shown;
                }
            }
            BufferedImage cards = ImageIO.read(atlas);
            double[] baked = new double[3];
            int opaque = 0;
            for (int y = 0; y < cards.getHeight(); y++) {
                for (int x = 0; x < cards.getWidth(); x++) {
                    int texel = cards.getRGB(x, y);
                    if (texel >>> 24 < 128) { continue; }
                    for (int channel = 0; channel < 3; channel++) { baked[channel] += texel >> 16 - 8 * channel & 255; }
                    opaque++;
                }
            }
            double ratio = luma(baked) / opaque / (luma(expected) / weight);
            // The estimate takes one texel or a cutout's mean per face; the sparse twigs of foliage-barren, whose
            // impostor prepare_tree_lods.py reproduces exactly, estimate at 1.65.
            assertTrue(ratio > .75 && ratio < 1.7, plant + " impostor lightness against its far mesh: " + ratio);
        }
    }

    /** Pines' skirts and palms' fronds lost half their outline to decimation at the far levels, and snow with it. */
    @Test
    void distantTreeLevelsKeepTheirOutline() {
        File root = new File(Configuration.dataDir(), "models/board");
        float across = (float) Math.cos(Math.toRadians(35)), up = (float) Math.sin(Math.toRadians(35));
        // The board camera's views: from 35 degrees above on four sides, and from straight above.
        float[][] views = { { across, 0, up }, { 0, across, up }, { -across, 0, up }, { 0, -across, up }, { 0, 0, 1 } };
        for (String tree : BoardTreeDistributionTest.IMPORTED_TREES) {
            var levels = RigidGlb.loadLods(RigidGlb.source(root, tree), root.toPath());
            // Summing overlapping card areas is not silhouette coverage. The native tree LOD regression measures
            // alpha-tested pixels for these crowns at the same scale and from several bearings instead.
            boolean cutout = false;
            for (var material : levels.getFirst().materials) { cutout |= material.id.endsWith("-cutout"); }
            if (cutout) { continue; }
            double near = outline(levels.get(0), views);
            for (int lod = 1; lod <= 2; lod++) {
                double kept = outline(levels.get(lod), views) / near;
                assertTrue(kept >= 2 / 3.0, tree + " LOD" + lod + " keeps " + kept + " of the near outline");
            }
        }
    }

    /** The area a mesh turns toward the views, the plant's outline as the board camera sees it. */
    /** A low-woods feature is one of its family's three shrub shapes. */
    private static boolean shrub(BoardScene.Feature feature, String family) {
        return feature.asset().matches("vegetation/foliage-" + family + "(-[23])?");
    }

    private static double outline(ModelData data, float[][] views) {
        double total = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    total += shown(face(mesh.vertices, corner(part.indices, i), corner(part.indices, i + 1),
                          corner(part.indices, i + 2)), views);
                }
            }
        }
        return total;
    }

    private static int corner(short[] indices, int index) {
        return Short.toUnsignedInt(indices[index]) * RigidGlb.STRIDE;
    }

    /** Twice a triangle's area along its normal, from the offsets of its corners in a RigidGlb vertex array. */
    private static float[] face(float[] v, int a, int b, int c) {
        float[] ab = { v[b] - v[a], v[b + 1] - v[a + 1], v[b + 2] - v[a + 2] };
        float[] ac = { v[c] - v[a], v[c + 1] - v[a + 1], v[c + 2] - v[a + 2] };
        return new float[] { ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2],
              ab[0] * ac[1] - ab[1] * ac[0] };
    }

    private static double shown(float[] face, float[][] views) {
        double total = 0;
        for (float[] view : views) { total += Math.max(0, face[0] * view[0] + face[1] * view[1] + face[2] * view[2]); }
        return total;
    }

    static void partMaterials(Iterable<ModelNode> nodes, Map<String, String> result) {
        for (ModelNode node : nodes) {
            for (var part : node.parts) { result.put(part.meshPartId, part.materialId); }
            partMaterials(Arrays.asList(node.children), result);
        }
    }

    private static double luma(double[] rgb) {
        return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2];
    }
}
