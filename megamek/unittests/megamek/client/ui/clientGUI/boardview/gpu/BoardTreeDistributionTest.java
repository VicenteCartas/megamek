/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTreeDistributionTest {
    static final List<String> BASIC_TREES = List.of("vegetation/tree", "vegetation/tree-broad", "vegetation/tree-slender",
          "vegetation/tree-forked", "vegetation/tree-layered", "vegetation/birch", "vegetation/birch-tall",
          "vegetation/birch-spreading", "vegetation/birch-young", "vegetation/willow", "vegetation/willow-broad",
          "vegetation/pine", "vegetation/pine-tall", "vegetation/pine-broad", "vegetation/pine-slender", "vegetation/pine-layered");
    /** The grown trees, each with its winter form, and the dead tree, palms and saguaros. */
    static final List<String> IMPORTED_TREES = java.util.stream.Stream.concat(
          BASIC_TREES.stream().flatMap(name -> java.util.stream.Stream.of(name, name + "-snow")),
          java.util.stream.Stream.of("vegetation/tree-dead", "vegetation/tree-dead-snow", "vegetation/palm", "vegetation/palm-bent",
                "vegetation/cactus", "vegetation/cactus-flowers", "vegetation/cactus-old", "vegetation/cactus-young",
                "vegetation/cactus-organ")).toList();

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, boolean natural) {
        return BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null, TilesetScenery.Scenery.EMPTY, natural)
              .stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
    }

    @Test
    void standsFavorNearbyFamiliesWithoutLosingCatalogVarietyOrTreeCounts() {
        int[] related = new int[2];
        var selected = new HashSet<String>();
        int pairs = 0;
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                var coords = new Coords(x, y);
                for (int mode = 0; mode < 2; mode++) {
                    var hex = new Hex(0, "woods:2;foliage_elev:2", "", coords);
                    var trees = capture(hex, coords, mode == 1);
                    assertEquals(9, trees.size());
                    assertEquals(trees, capture(hex, coords, mode == 1), "Capture order cannot reroll a stand");
                    if (mode == 1) { trees.forEach(t -> selected.add(t.asset())); }
                    for (int i = 1; i < trees.size(); i++) {
                        if (family(trees.getFirst().asset()).equals(family(trees.get(i).asset()))) { related[mode]++; }
                        if (mode == 0) { pairs++; }
                    }
                }
            }
        }
        assertEquals(14, selected.size(), "Temperate woods use twice the original seven shapes");
        assertTrue(related[1] > related[0] + pairs * .20f,
              "Nearby trees must form stands, not merely a different random mix: " + related[0] + " / " + related[1]);
    }

    @Test
    void standSelectionIsContinuousAcrossEveryHexEdge() {
        var random = new Random(842);
        for (int x = -4; x < 4; x++) {
            for (int direction = 0; direction < 6; direction++) {
                var a = new Coords(x, 5);
                var b = a.translated(direction);
                var delta = BoardGeometry.center(b, 0).sub(BoardGeometry.center(a, 0)).scl(.5f);
                long individual = random.nextLong();
                assertEquals(BoardTreeDistribution.species(BASIC_TREES, a, delta.x, delta.y, individual),
                      BoardTreeDistribution.species(BASIC_TREES, b, -delta.x, -delta.y, individual),
                      "The same point must not change species at a hex seam");
            }
        }
    }

    @Test
    void groupingKeepsCoverHeightsFootprintsAndClearGround() {
        var coords = new Coords(7, 4);
        for (int density = 1; density <= 3; density++) {
            var hex = new Hex(0, "woods:" + density + ";foliage_elev:2", "", coords);
            var trees = capture(hex, coords, true);
            assertEquals(density == 1 ? 3 : density == 2 ? 9 : 16, trees.size());
            assertTrue(trees.stream().allMatch(t -> t.height() >= 2 && t.height() <= 2.2f
                  && Math.hypot(t.x(), t.y()) <= 29), "Keep authoritative cover and the existing trunk footprint");
        }
        var clear = new Hex(0, "", "", coords);
        assertTrue(capture(clear, coords, true).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "desert", "tropical", "snow", "lunar", "mars", "fungus" })
    void distributionAppliesToEveryBiomeIncludingOrchardsAndLowCover(String theme) {
        var coords = new Coords(7, 4);
        for (int height : new int[] { 1, 2 }) {
            for (String marker : List.of("", ";fluff:12")) {
                var hex = new Hex(0, "woods:1;foliage_elev:" + height + marker + ";road:1:9", theme, coords);
                var natural = capture(hex, coords, true);
                var original = capture(hex, coords, false);
                assertEquals(original.size(), natural.size(), "The flag changes distribution, not cover density");
                assertNotEquals(original, natural, "No vegetation is exempt from the distribution flag");
                assertEquals(natural, capture(hex, coords, true));
                var road = BoardRoad.clearance(coords, hex, c -> null);
                assertTrue(natural.stream().allMatch(f -> road.distance(f.x(), f.y()) >= BoardRoad.SHOULDER + 2));
            }
        }
    }

    @Test
    void singleFamilyPalettesAlsoFormStands() {
        List<String> forms = List.of("vegetation/orchard-round", "vegetation/orchard-spreading", "vegetation/orchard-upright", "vegetation/orchard-vase");
        var coords = new Coords(5, 3);
        var random = new Random(730);
        var counts = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < 1000; i++) {
            counts.merge(BoardTreeDistribution.species(forms, coords, 0, 0, random.nextLong()), 1, Integer::sum);
        }
        assertEquals(4, counts.size(), "Keep occasional other forms");
        assertTrue(counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow() > 700,
              "A single-family palette must still favor a local form");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void doubledCatalogHasDistinctTrunksBoundedLodsAndSharedLeafTextures(boolean snow) {
        File root = new File(Configuration.dataDir(), "models/board");
        var trunks = new HashSet<List<Float>>();
        var pigments = new HashSet<Integer>();
        for (String tree : BASIC_TREES) {
            String name = tree + (snow ? "-snow" : "");
            var levels = RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath());
            assertEquals(4, levels.size(), name);
            var materials = new java.util.HashMap<String, String>();
            BoardFoliageTest.partMaterials(levels.getFirst().nodes, materials);
            var trunk = new ArrayList<Float>();
            var mesh = levels.getFirst().meshes.first();
            for (var part : mesh.parts) {
                if (materials.get(part.id).startsWith("bark")) {
                    for (short index : part.indices) {
                        int v = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                        trunk.add(mesh.vertices[v]); trunk.add(mesh.vertices[v + 1]); trunk.add(mesh.vertices[v + 2]);
                    }
                } else {
                    int v = Short.toUnsignedInt(part.indices[0]) * RigidGlb.STRIDE;
                    pigments.add(Math.round(mesh.vertices[v + 6] / mesh.vertices[v + 7] * 1000));
                }
            }
            trunks.add(trunk);
            for (int lod = 0; lod < 4; lod++) {
                var model = levels.get(lod);
                int triangles = 0;
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (var m : model.meshes) {
                    for (var part : m.parts) { triangles += part.indices.length / 3; }
                    for (int v = 0; v < m.vertices.length; v += RigidGlb.STRIDE) {
                        low = Math.min(low, m.vertices[v + 2]); high = Math.max(high, m.vertices[v + 2]);
                    }
                }
                assertTrue(triangles > 0 && triangles <= List.of(1600, 410, 96, 12).get(lod), name);
                assertEquals(0, low, .001f, name);
                assertEquals(30, high, .001f, name);
                for (var material : model.materials) {
                    if (!material.id.endsWith("-cutout")) { continue; }
                    assertEquals(.5f, ((RigidGlb.Data) model).alphaTests.get(material.id), .0001f);
                    var texture = ((RigidGlb.Data) model).images.get(material.textures.first().fileName);
                    // Willows wear their own cutout in summer and the broadleaf snow in winter.
                    String cutout = tree.startsWith("vegetation/pine") ? "conifer"
                          : tree.startsWith("vegetation/willow") && !snow ? "willow" : "broadleaf";
                    assertTrue(texture.file().endsWith(cutout + (snow ? "-snow" : "") + "-cutout_albedo.png"),
                          "Variants borrow the shared textures");
                }
            }
        }
        assertEquals(16, trunks.size(), "Different trunks and branching, not recolored or resized crown copies");
        assertTrue(snow || pigments.size() >= 8, "Summer variants have distinct subtle leaf pigments");
    }

    private static String family(String asset) {
        return asset.split("-")[0];
    }
}
