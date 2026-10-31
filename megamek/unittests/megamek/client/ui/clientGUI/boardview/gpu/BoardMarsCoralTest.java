/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardMarsCoralTest {
    @ParameterizedTest
    @ValueSource(strings = { "mars", "MARS", "mars snow" })
    void allMartianCoverUsesCoralsIncludingLowFoliageJungleAndSnow(String theme) {
        Set<String> used = new HashSet<>();
        for (String type : List.of("woods", "jungle")) {
            for (int density = 1; density <= 3; density++) {
                for (int height : new int[] { 1, 2, 3 }) {
                    for (String extra : List.of("", ";snow:1", ";pavement:1", ";fluff:12")) {
                        var hex = new Hex(0, type + ":" + density + ";foliage_elev:" + height + extra, theme);
                        var at = new Coords(density, height);
                        var before = hex.duplicate();
                        var cover = cover(hex, at);
                        assertEquals(2 * density, cover.size());
                        assertEquals(height, cover.getFirst().height());
                        // Woods keep their height: one shorter colony in light woods, two in heavier woods.
                        assertEquals(density == 1 ? 1 : 2, cover.stream().filter(f -> f.height() < height).count());
                        for (var feature : cover) {
                            assertTrue(BoardFeatures.MARS_CORALS.contains(feature.asset()), feature.asset());
                            assertTrue(feature.height() > 0 && feature.height() <= height);
                            used.add(feature.asset());
                        }
                        assertEquals(cover, cover(hex, at), "Snapshot capture remains deterministic");
                        assertEquals(before.getTheme(), hex.getTheme());
                        assertEquals(before.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.WOODS));
                        assertEquals(before.terrainLevel(Terrains.JUNGLE), hex.terrainLevel(Terrains.JUNGLE));
                        assertEquals(height, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
                    }
                }
            }
        }
        // Coherent stands intentionally repeat nearby forms. Check catalog reach across a wider woodland,
        // rather than requiring all twelve forms in the nine positions above.
        var woodland = new Hex(0, "woods:2;foliage_elev:2", theme);
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                cover(woodland, new Coords(x, y)).forEach(tree -> used.add(tree.asset()));
            }
        }
        assertEquals(Set.copyOf(BoardFeatures.MARS_CORALS), used);
    }

    @Test
    void coralsRespectRoadClearanceAndDoNotCreateCoverOnBareGround() {
        var at = new Coords(4, 5);
        var bare = new Hex(0, "snow:1;rough:1", "mars");
        assertTrue(cover(bare, at).isEmpty());
        for (int density = 1; density <= 3; density++) {
            var hex = new Hex(0, "woods:" + density + ";foliage_elev:2;road:1:9", "mars");
            var road = BoardRoad.clearance(at, hex, neighbor -> null);
            var cover = cover(hex, at);
            assertEquals(2 * density, cover.size());
            assertTrue(cover.stream().allMatch(f -> road.distance(f.x(), f.y()) >= BoardRoad.SHOULDER + 2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "grass", "desert", "lunar", "snow", "fungus" })
    void otherThemesKeepTheirOwnVegetation(String theme) {
        var hex = new Hex(0, "woods:2;foliage_elev:2", theme);
        var cover = cover(hex, new Coords(4, 4));
        assertFalse(cover.isEmpty());
        assertTrue(cover.stream().noneMatch(f -> BoardFeatures.MARS_CORALS.contains(f.asset())));
        if (theme.equals("fungus")) {
            assertEquals(4, cover.size());
            assertTrue(cover.stream().allMatch(f -> BoardFungus.COVER.contains(f.asset())));
        } else {
            assertEquals(9, cover.size());
        }
    }

    @Test
    void everyCoralLoadsWithFourSimplerTexturedMeshesAtTheSameGroundAndHeight() {
        var root = new File("data/models/board").toPath().toAbsolutePath();
        for (String name : BoardFeatures.MARS_CORALS) {
            var levels = RigidGlb.loadLods(new FileHandle(root.resolve(name + ".glb").toFile()), root);
            assertEquals(4, levels.size());
            int previous = Integer.MAX_VALUE;
            for (var level : levels) {
                int triangles = 0;
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (var mesh : level.meshes) {
                    for (var part : mesh.parts) { triangles += part.indices.length / 3; }
                    for (int i = 2; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                        low = Math.min(low, mesh.vertices[i]);
                        high = Math.max(high, mesh.vertices[i]);
                    }
                    for (float component : mesh.vertices) { assertTrue(Float.isFinite(component), name); }
                }
                assertTrue(triangles > 0 && triangles < previous, name + " must simplify at every LOD");
                previous = triangles;
                assertEquals(0, low, .001f, name + " roots stay grounded");
                assertEquals(30, high, .001f, name + " keeps its height");
                // Mineral coral uses solid foliage lighting; the farthest level is the baked impostor.
                for (var material : level.materials) {
                    assertTrue(List.of("coral", "impostor").contains(material.id), name + ": " + material.id);
                    assertEquals(1, material.opacity);
                    assertFalse(material.textures.isEmpty());
                }
                boolean impostor = false;
                for (var node : level.nodes) {
                    for (var part : node.parts) { impostor |= part.materialId.equals("impostor"); }
                }
                assertEquals(level == levels.getLast(), impostor, name);
            }
        }
    }

    private static List<BoardScene.Feature> cover(Hex hex, Coords at) {
        return BoardFeatures.capture(hex, at, Map.of()).stream()
              .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
    }
}
