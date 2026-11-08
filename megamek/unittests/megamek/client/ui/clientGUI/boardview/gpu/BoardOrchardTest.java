/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardOrchardTest {
    static final List<String> FORMS = List.of("round", "spreading", "upright", "vase", "leaning", "young");

    @ParameterizedTest
    @ValueSource(strings = { "", "desert", "tropical", "snow", "lunar" })
    void orchardMarkerKeepsFruitTreesAtEveryCoverHeight(String theme) {
        Coords coords = new Coords(3, 2);
        Hex hex = new Hex(0, "woods:1;fluff:12", theme, coords);
        for (int height : new int[] { 1, 2, 3 }) {
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, height));
            var trees = BoardFeatures.capture(hex, coords, Map.of());
            assertEquals(4, trees.size());
            assertTrue(trees.stream().allMatch(tree -> FORMS.contains(
                  tree.asset().replace("vegetation/orchard-", "").replace("-snow", ""))));
            for (var tree : trees) {
                assertTrue(tree.asset().startsWith("vegetation/orchard-"));
                assertEquals(theme.equals("snow"), tree.asset().endsWith("-snow"));
                assertEquals(BoardScene.FeatureKind.TREE, tree.kind());
            }
            BoardFeaturesTest.assertWoodsHeights(trees, height, 1);
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            assertEquals(trees, BoardFeatures.capture(hex, coords, Map.of()));
        }
    }

    @Test
    void orchardsKeepPlantedRowsAcrossStaggeredHexColumnsWithOrWithoutTheNaturalDistribution() {
        for (boolean natural : new boolean[] { false, true }) {
            for (Coords coords : List.of(new Coords(0, 0), new Coords(1, 0), new Coords(2, 1))) {
                Hex hex = new Hex(0, "woods:1;fluff:12;foliage_elev:2", "", coords);
                for (var tree : BoardTreeDistributionTest.capture(hex, coords, natural)) {
                    float x = BoardGeometry.centerX(coords) / BoardGeometry.hexScale() + tree.x();
                    float y = BoardGeometry.centerY(coords) / BoardGeometry.hexScale() + tree.y();
                    float column = x / (BoardGeometry.TILE_WIDTH / 4);
                    assertEquals(Math.round(column), column, .01f, "Columns share one planting grid");
                    assertEquals(18, Math.floorMod(Math.round(y), 36), "Rows share one planting grid");
                }
            }
        }
    }

    @Test
    void markerRequiresWoodsAndCoverReductionStillRemovesTrees() {
        Coords coords = new Coords(0, 0);
        Hex hex = new Hex(0, "woods:3;fluff:12;foliage_elev:2", "", coords);
        int previous = Integer.MAX_VALUE;
        for (int density = 3; density >= 1; density--) {
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            var trees = BoardFeatures.capture(hex, coords, Map.of());
            assertEquals(new int[] { 0, 4, 6, 9 }[density], trees.size());
            assertTrue(trees.size() < previous);
            assertTrue(trees.stream().allMatch(tree -> tree.asset().startsWith("vegetation/orchard-")));
            previous = trees.size();
        }
        hex.removeTerrain(Terrains.WOODS);
        assertFalse(BoardFeatures.orchard(hex));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .noneMatch(feature -> feature.kind() == BoardScene.FeatureKind.TREE));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Unrelated fluff retains its artwork");
        hex.addTerrain(new Terrain(Terrains.JUNGLE, 1));
        assertFalse(BoardFeatures.orchard(hex));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .noneMatch(tree -> tree.asset().startsWith("vegetation/orchard-")));
        hex.removeTerrain(Terrains.JUNGLE);
        hex.addTerrain(new Terrain(Terrains.WOODS, 1));
        hex.addTerrain(new Terrain(Terrains.FLUFF, 11));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .noneMatch(tree -> tree.asset().startsWith("vegetation/orchard-")));
    }

    @Test
    void roadClearanceAndSnowTerrainKeepOrchardSelection() {
        Coords coords = new Coords(2, 2);
        Hex hex = new Hex(0, "woods:1;fluff:12;snow:1;foliage_elev:2;road:1:9", "", coords);
        var road = BoardRoad.clearance(coords, 9);
        var trees = BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(tree -> tree.kind() == BoardScene.FeatureKind.TREE).toList();
        assertEquals(4, trees.size());
        for (var tree : trees) {
            assertTrue(tree.asset().startsWith("vegetation/orchard-") && tree.asset().endsWith("-snow"));
            assertTrue(road.distance(tree.x(), tree.y()) >= BoardRoad.SHOULDER + 2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void shippedOrchardsHaveDistinctGeometryAndTexturedLods(boolean snow) {
        File root = new File(Configuration.dataDir(), "models/board");
        var shapes = new HashSet<Integer>();
        for (String form : FORMS) {
            String name = "vegetation/orchard-" + form + (snow ? "-snow" : "");
            var levels = RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath());
            assertEquals(4, levels.size(), name);
            assertNotSame(levels.get(0), levels.get(1));
            assertNotSame(levels.get(1), levels.get(2));
            assertNotSame(levels.get(2), levels.get(3));
            shapes.add(java.util.Arrays.hashCode(levels.getFirst().meshes.first().vertices));
            int previous = Integer.MAX_VALUE;
            for (int lod = 0; lod < 3; lod++) {
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
                assertTrue(triangles <= List.of(34000, 10000, 3200).get(lod));
                assertTrue(data.meshes.first().parts.length >= 3,
                      "The crown, wood and attached apples retain separate materials: " + name);
                assertEquals(0, low, .001f, name);
                assertEquals(30, high, 1f, "LOD preserves the tree height: " + name);
                boolean hasSnow = false;
                for (var material : data.materials) {
                    hasSnow |= material.id.equals("snow") || material.id.equals("canopy-snow-cutout");
                    boolean cutout = material.id.endsWith("-cutout");
                    if (cutout) {
                        assertEquals(.3f, ((RigidGlb.Data) data).alphaTests.get(material.id), .0001f);
                    }
                    // Crowns and bark carry albedo, normal and surface maps; the impostor card its colour and shape.
                    assertEquals(material.id.equals("impostor") ? 2 : 3, material.textures.size,
                          name + " LOD" + lod + ": " + material.id);
                    for (var texture : material.textures) {
                        var image = ((RigidGlb.Data) data).images.get(texture.fileName);
                        assertTrue(image != null && image.file() != null && new File(image.file()).isFile(),
                              "The sampler cache key must resolve to a shipped texture: " + texture.fileName);
                    }
                }
                assertEquals(snow, hasSnow);
                previous = triangles;
            }
        }
        assertEquals(6, shapes.size(), "Variants must have different meshes, not only different names");
    }
}
