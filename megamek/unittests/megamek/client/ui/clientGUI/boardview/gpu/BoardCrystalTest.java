/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardModelBounds;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCrystalTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static Hex hex(String theme, String type, int height, int density, Coords at) {
        var hex = new Hex(0, type + ":" + density + ";foliage_elev:" + height, theme, at);
        hex.setAppearance(Map.of("vegetation", new HexAppearance("woods/crystal", null, null, null)));
        return hex;
    }

    static List<BoardScene.Feature> crystals(Hex hex, Coords at) {
        return BoardFeatures.capture(hex, at, Map.of(), Set.of(), c -> null, TilesetScenery.Scenery.EMPTY, true)
              .stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
    }

    @Test
    void crystalAppearanceOverridesBiomesAndRetainsExactCoverHeightAndRigidBodies() {
        for (String theme : List.of("", "tropical", "volcanic", "fungus", "mars")) {
            for (String type : List.of("woods", "jungle")) {
                for (int height = 1; height <= 3; height++) {
                    for (int seed = 0; seed < 3; seed++) {
                        Coords at = new Coords(3 + seed * 8, 3 + seed * 5);
                        int previous = 0;
                        for (int density = 1; density <= 3; density++) {
                            var hex = hex(theme, type, height, density, at);
                            var plants = crystals(hex, at);
                            assertEquals(plants, crystals(hex, at), "Capture must be deterministic");
                            assertTrue(plants.size() >= previous && plants.size() <= density * 3);
                            assertFalse(plants.isEmpty());
                            previous = plants.size();
                            var bodies = new ArrayList<BoardPlantFootprint>();
                            for (var plant : plants) {
                                assertTrue(plant.asset().startsWith("crystal/cover-"));
                                assertEquals(height, plant.height());
                                assertEquals(height == 1, plant.asset().contains("cover-low-"));
                                var body = BoardPlantFootprint.of(plant, new java.util.HashMap<>());
                                assertTrue(body.inside());
                                assertTrue(bodies.stream().noneMatch(body::overlaps));
                                bodies.add(body);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void fiveGiantsAreCosmeticPropsAtTheirNamedHeightAndKeepTranslucencyAtEveryLod() {
        assertNotNull(BoardEditorBlueprint.get().variant("woods/crystal"));
        for (int level = 1; level <= 5; level++) {
            String name = "crystal/giant-level" + level;
            assertEquals("prop", BoardEditorBlueprint.get().asset(name).kind());
            float[] bounds = BoardModelBounds.bounds(name);
            assertNotNull(bounds);
            assertEquals(level * BoardGeometry.MODEL_LEVEL_HEIGHT, bounds[5] - bounds[2], .001f);
            var file = new FileHandle(new File(Configuration.dataDir(), "models/board/" + name + ".glb"));
            for (var lod : RigidGlb.loadLods(file, new File(Configuration.dataDir(), "models/board").toPath().toAbsolutePath())) {
                var data = (RigidGlb.Data) lod;
                assertFalse(data.blends.containsKey("crystal"), "Ruby transports light, not alpha-sorted scenery");
                var surface = data.surfaces.get("crystal");
                assertNotNull(surface);
                for (float glow : surface.glow()) { assertEquals(0, glow, "No self illumination"); }
                assertFalse(data.blends.containsKey("crystal-matrix"));
                assertTrue(data.alphaTests.isEmpty(), "Crystal facets are translucent geometry, never cutout cards");
            }
        }
    }
}
