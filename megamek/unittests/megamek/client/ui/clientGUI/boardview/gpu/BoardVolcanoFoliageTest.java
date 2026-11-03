/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.files.FileHandle;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardVolcanoFoliageTest {
    @ParameterizedTest
    @ValueSource(strings = { "volcano", "VOLCANO", "volcanic ash", "volcano snow" })
    void themeSelectsCinderTreesAndDedicatedLowThicketsWithoutChangingCover(String theme) {
        for (String type : List.of("woods", "jungle")) {
            for (int density = 1; density <= 3; density++) {
                for (int height = 1; height <= 3; height++) {
                    for (String extra : List.of("", ";snow:1", ";pavement:1", ";fluff:12")) {
                        var hex = new Hex(0, type + ":" + density + ";foliage_elev:" + height + extra, theme);
                        var at = new Coords(density, height);
                        var cover = cover(hex, at);
                        assertTrue(!cover.isEmpty() && cover.size() <= BoardTreeCoverage.canopyLimit(density));
                        for (var feature : cover) {
                            assertTrue(height == 1 ? feature.asset().matches("vegetation/foliage-volcano(-[23])?")
                                  : BoardFeatures.VOLCANO_TREES.contains(feature.asset()), feature.asset());
                            assertTrue(feature.height() >= height && feature.height() <= height * 1.1f);
                        }
                        assertEquals(cover, cover(hex, at), "Placement remains deterministic");
                        assertEquals(density, hex.terrainLevel(type.equals("woods") ? Terrains.WOODS : Terrains.JUNGLE));
                        assertEquals(height, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
                        assertEquals(theme, hex.getTheme());
                        assertFalse(hex.containsTerrain(Terrains.FIRE), "The red growth is not burning terrain");
                    }
                }
            }
        }
        var used = new HashSet<String>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 12; y++) {
                var woods = new Hex(0, "woods:" + (1 + (x + y) % 3) + ";foliage_elev:2", theme);
                cover(woods, new Coords(x, y)).forEach(feature -> used.add(feature.asset()));
            }
        }
        assertEquals(new HashSet<>(BoardFeatures.VOLCANO_TREES), used);
    }

    @Test
    void keepsRoadsClearAndOnlyReplacesExistingVolcanicCover() {
        var at = new Coords(4, 5);
        assertTrue(cover(new Hex(0, "", "volcano"), at).isEmpty());
        for (int height : new int[] { 1, 2 }) {
            for (int density = 1; density <= 3; density++) {
                var hex = new Hex(0, "woods:" + density + ";foliage_elev:" + height + ";road:1:9", "volcano");
                var road = BoardRoad.clearance(at, hex, neighbor -> null);
                assertTrue(cover(hex, at).stream().allMatch(f -> road.distance(f.x(), f.y()) >= BoardRoad.SHOULDER + 2));
            }
            for (String theme : List.of("", "rock", "desert", "snow", "mars", "fungus")) {
                var hex = new Hex(0, "woods:2;foliage_elev:" + height, theme);
                assertTrue(cover(hex, at).stream().noneMatch(f -> f.asset().contains("volcano")));
            }
        }
    }

    @Test
    void cinderTreesAndLowThicketsKeepTheirBudgetsRootsAndHeight() {
        var root = new File("data/models/board").toPath().toAbsolutePath();
        var names = new ArrayList<>(BoardFeatures.VOLCANO_TREES);
        names.addAll(List.of("vegetation/foliage-volcano", "vegetation/foliage-volcano-2", "vegetation/foliage-volcano-3"));
        for (String name : names) {
            boolean shrub = name.startsWith("vegetation/foliage-");
            int[] budgets = shrub ? new int[] { 24000, 8000, 2200, 12 } : new int[] { 32000, 10000, 2600, 12 };
            var levels = RigidGlb.loadLods(new FileHandle(root.resolve(name + ".glb").toFile()), root);
            assertEquals(4, levels.size(), name);
            int previous = Integer.MAX_VALUE;
            for (int lod = 0; lod < levels.size(); lod++) {
                var level = levels.get(lod);
                int triangles = 0;
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
                for (var mesh : level.meshes) {
                    for (var part : mesh.parts) { triangles += part.indices.length / 3; }
                    for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                        low = Math.min(low, mesh.vertices[i + 2]);
                        high = Math.max(high, mesh.vertices[i + 2]);
                        left = Math.min(left, mesh.vertices[i]);
                        right = Math.max(right, mesh.vertices[i]);
                    }
                    for (float component : mesh.vertices) { assertTrue(Float.isFinite(component), name); }
                }
                assertTrue(triangles > 0 && triangles <= budgets[lod] && triangles < previous, name + " LOD " + lod);
                previous = triangles;
                assertEquals(0, low, .001f, name + " roots touch the ground");
                assertEquals(shrub ? 18 : 30, high, .001f, name + " keeps its height");
                if (shrub && lod == 0) {
                    assertTrue(right - left > high, "Low thickets have their own spreading proportions");
                }
                assertEquals(lod == 3 ? 1 : 2, level.meshes.first().parts.length, "Shared bark and cutout draw parts");
                for (var material : level.materials) { assertFalse(material.textures.isEmpty()); }
            }
        }
    }

    private static List<BoardScene.Feature> cover(Hex hex, Coords at) {
        return BoardFeatures.capture(hex, at, Map.of()).stream()
              .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
    }
}
