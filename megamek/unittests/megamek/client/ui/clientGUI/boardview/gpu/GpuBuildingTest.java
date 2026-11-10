/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.Test;

class GpuBuildingTest {
    static final String ASSET = "buildings/standard/fortress-light";

    /** A fresh copy of {@code asset}'s generated kit's levels of detail, which a test may change; none kept cached. */
    static List<ModelData> lods(String asset) {
        GeneratedKit.reload();
        var lods = List.<ModelData>copyOf(GpuBuilding.kit(asset).lods());
        GeneratedKit.reload();
        return lods;
    }

    /** The kit's module roles, each with its core node ({@link ConnectedKit#parts}). */
    private static Map<String, String> roles(ModelData data) {
        Map<String, String> result = new java.util.HashMap<>();
        ConnectedKit.parts(data).forEach(piece -> result.put(piece.role(), piece.node()));
        return result;
    }

    private static ModelData modules(String... names) {
        ModelData data = new ModelData();
        for (String name : names) {
            ModelNode node = new ModelNode();
            node.id = name;
            node.parts = new ModelNodePart[] { new ModelNodePart() };
            data.nodes.add(node);
        }
        return data;
    }

    @Test
    void aFiveLevelBuildingHasOneBaseFourFloorsAndOneRoof() {
        var parts = roles(modules("kit-base0", "kit-floor0", "kit-roof0"));
        assertEquals(List.of("base0", "floor0", "floor0", "floor0", "floor0", "roof0"),
              GpuBuilding.select(parts, 5, 41));
        assertEquals(List.of("base0", "roof0"), GpuBuilding.select(parts, 1, 41));
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.select(parts, 0, 41));
    }

    @Test
    void theStoreyUnderTheRoofIsAKitsTopModuleWhereItHasOne() {
        var parts = roles(modules("kit-base0", "kit-floor0", "kit-top0", "kit-roof0"));
        assertEquals(List.of("base0", "floor0", "floor0", "top0", "roof0"), GpuBuilding.select(parts, 4, 41));
        assertEquals(List.of("base0", "top0", "roof0"), GpuBuilding.select(parts, 2, 41));
        assertEquals(List.of("base0", "roof0"), GpuBuilding.select(parts, 1, 41), "One level is the base alone");
    }

    @Test
    void variantNumbersDoNotNeedToStartAtZero() {
        var parts = roles(modules("kit-base4", "kit-floor7", "kit-roof3"));
        assertEquals(List.of("base4", "floor7", "roof3"), GpuBuilding.select(parts, 2, 41));
    }

    @Test
    void randomVariantsAreStableAcrossCameraLodAndHeightChanges() {
        var parts = roles(modules("kit-base0", "kit-base4", "kit-floor0", "kit-floor7", "kit-roof0", "kit-roof3"));
        var reordered = roles(modules("far-roof3", "far-floor7", "far-base4", "far-floor0", "far-roof0", "far-base0"));
        Set<String> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 80; seed++) {
            var one = GpuBuilding.select(parts, 1, seed);
            var five = GpuBuilding.select(parts, 5, seed);
            var ten = GpuBuilding.select(parts, 10, seed);
            assertEquals(five, GpuBuilding.select(reordered, 5, seed));
            assertEquals(List.of(five.getFirst(), five.getLast()), one);
            assertEquals(five.subList(0, 5), ten.subList(0, 5));
            assertEquals(five.getLast(), ten.getLast());
            assertTrue(Set.of("base0", "base4").contains(five.getFirst()));
            assertTrue(five.subList(1, 5).stream().allMatch(Set.of("floor0", "floor7")::contains));
            assertTrue(Set.of("roof0", "roof3").contains(five.getLast()));
            seen.addAll(five);
        }
        assertEquals(Set.of("base0", "base4", "floor0", "floor7", "roof0", "roof3"), seen);
    }

    @Test
    void rejectsIncompleteOrAmbiguousKits() {
        for (String[] names : new String[][] {
              { "kit-floor0", "kit-floor1", "kit-roof0" }, { "kit-base0", "kit-roof0" },
              { "kit-base0", "kit-floor0" }, { "kit-base0", "other-base0", "kit-floor0", "kit-roof0" },
              { "kit-base0", "kit-floor0", "other-floor0", "kit-roof0" },
              { "kit-base0", "kit-floor0", "kit-roof0", "other-roof0" },
              { "kit-base0", "kit-floor0", "kit-roof0", "kit-extra" } }) {
            assertThrows(IllegalArgumentException.class, () -> ConnectedKit.parts(modules(names)));
        }
    }

    @Test
    void simpleBuildingsUseOneLodAndAdditionalAuthoredLodsRemainAvailable() {
        ModelData near = new ModelData(), far = new ModelData(), third = new ModelData();
        near.id = "kit-lod0";
        far.id = "kit-lod1";
        third.id = "kit-lod2";
        assertEquals(List.of(near), GpuBuilding.lods(List.of(near, near, near)));
        assertEquals(List.of(near, far), GpuBuilding.lods(List.of(near, far, far)));
        assertEquals(List.of(near, far, third), GpuBuilding.lods(List.of(near, far, third)));
        assertEquals(List.of(near, near, third), GpuBuilding.lods(List.of(near, near, third)),
              "A missing middle level keeps LOD0 until the third level's distance threshold");
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.lods(List.of(far, far, far)));
    }

    @Test
    void shippedDistantLodStaysWithinItsSmallGeometryBudget() {
        var kit = GpuBuilding.kit(ASSET);
        // What a distant hex draws: alone, and in a straight run, three storeys and the roof.
        for (int[] reach : List.of(new int[6], new int[] { 3, 0, 0, 3, 0, 0 })) {
            int triangles = ConnectedKit.placements(kit, 1, 3, reach, 0, 1).stream()
                  .mapToInt(piece -> ConnectedKit.triangles(kit, 1, piece).size() / 3).sum();
            // Includes the panels' own thickness and the roof slab's underside and edges.
            assertTrue(triangles <= 1200, "A distant hex must remain lightweight: " + triangles);
        }
    }

    @Test
    void shippedKitsChangeTowardsAConnectedNeighbour() {
        for (String asset : List.of(ASSET, "buildings/standard/building-light")) {
            // Loading checks the connected kits' contract (ConnectedKit.load).
            var kit = GpuBuilding.kit(asset);
            var alone = ConnectedKit.placements(kit, 0, 2, new int[6], 0, 1).stream().map(ConnectedKit.Placement::node).toList();
            var joined = ConnectedKit.placements(kit, 0, 2, new int[] { 2, 0, 0, 0, 0, 0 }, 0, 1).stream()
                  .map(ConnectedKit.Placement::node).toList();
            assertNotEquals(alone, joined, asset + " meets its north neighbour");
        }
    }

    @Test
    void shippedLightBuildingVariantsShareTheirFloorFootprintInBothLods() {
        var lods = GpuBuilding.kit("buildings/standard/building-light").lods();
        assertEquals(2, lods.size());
        for (var lod : lods) {
            assertEquals(Set.of("base0", "base1", "floor0", "floor1", "roof0"), roles(lod).keySet());
            // Variants share the room footprint, so neighbours join whichever stands. Recesses and the panels'
            // own inward thickness may differ; the floor caps define the occupied area.
            Map<String, com.badlogic.gdx.math.collision.BoundingBox> pieces = new java.util.HashMap<>();
            for (var node : lod.nodes) {
                var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
                for (var mesh : lod.meshes) {
                    for (var nodePart : node.parts) {
                        if (!nodePart.materialId.endsWith(GeneratedMesh.BUILDING_FLOOR)) { continue; }
                        for (var part : mesh.parts) {
                            if (!part.id.equals(nodePart.meshPartId)) { continue; }
                            for (short index : part.indices) {
                                int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                                bounds.ext(mesh.vertices[at] + node.translation.x, mesh.vertices[at + 1] + node.translation.y, 0);
                            }
                        }
                    }
                }
                if (!bounds.isValid()) { continue; }
                String piece = node.id.replaceFirst("-(base|floor)[0-9]", "-$1");
                var other = pieces.putIfAbsent(piece, bounds);
                if (other != null) {
                    assertEquals(other.min.x, bounds.min.x, .01f, node.id);
                    assertEquals(other.max.x, bounds.max.x, .01f, node.id);
                    assertEquals(other.min.y, bounds.min.y, .01f, node.id);
                    assertEquals(other.max.y, bounds.max.y, .01f, node.id);
                }
            }
            assertFalse(pieces.isEmpty(), "The kit supplies its room outlines");
        }
    }

    @Test
    void aSquareFootprintDrawsAGeneratedKitsSquareFootprint() {
        String light = "buildings/standard/building-light";
        assertEquals(light, TilesetScenery.footprint(light, megamek.common.board.HexAppearance.Footprint.ANGLED));
        String square = TilesetScenery.footprint(light, megamek.common.board.HexAppearance.Footprint.SQUARE);
        assertEquals(light + "-square", square);
        assertEquals(TilesetScenery.customBuildingFile(light), TilesetScenery.customBuildingFile(square), "One spec");
        // Loading checks the Square kit's contract too.
        GpuBuilding.kit(square);
        String generated = "buildings/multihex/dome";
        assertEquals(generated, TilesetScenery.footprint(generated, megamek.common.board.HexAppearance.Footprint.SQUARE),
              "A building without a Square kit keeps its own");
    }
}
