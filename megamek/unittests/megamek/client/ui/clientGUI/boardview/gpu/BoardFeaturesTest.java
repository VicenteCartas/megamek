/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardFeaturesTest {
    /**
     * Fungal stands may retain one shorter plant in light woods and two in heavier woods (a shorter one at
     * least .7 of it); the rest stand from their woods' height to a tenth above it.
     */
    static void assertWoodsHeights(List<BoardScene.Feature> plants, float height, int density) {
        long shorter = plants.stream().filter(plant -> plant.height() < height).count();
        assertTrue(shorter <= (density == 1 ? 1 : 2), "At most one shorter plant in light woods, two in heavier woods");
        assertTrue(plants.stream().allMatch(plant -> plant.height() >= height * .7f && plant.height() <= height * 1.1f),
              "Plants stand about their woods' height");
    }

    @Test
    void aPlacedObjectIsOneFeatureWithItsOwnTransform() {
        String asset = "scenery/parks/picnic-table";
        var placement = megamek.common.board.BoardDecoration.Placement.absolute(4);
        var object = new megamek.common.board.BoardDecoration("picnic", "prop", asset, null, .1, -.2, 90, true, .5, placement, 0);
        Hex hex = new Hex(); hex.setDecorations(java.util.List.of(object));
        var features = BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .filter(f -> f.decoration() != null).toList();
        assertEquals(1, features.size());
        var feature = features.getFirst();
        assertEquals(asset, feature.asset());
        assertEquals(.1 * BoardGeometry.TILE_WIDTH, feature.x(), .0001);
        assertEquals(-.2 * BoardGeometry.TILE_HEIGHT, feature.y(), .0001);
        assertEquals(90, feature.rotation(), .0001);
        assertEquals(.5, feature.scale(), .0001);
        assertEquals(0, feature.elevation(), .0001);
        assertEquals(object, feature.decoration());
        hex.addTerrain(new Terrain(Terrains.ULTRA_SUBLEVEL, 1));
        assertEquals(features, BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .filter(f -> f.decoration() != null).toList(), "Absolute objects remain available above a chasm");
    }

    @ParameterizedTest
    @EnumSource(value = BoardScene.Surface.class, mode = EnumSource.Mode.EXCLUDE, names = "SAND")
    void modeledStructuresKeepTheirGroundWithoutErasingOtherTerrainMarkings(BoardScene.Surface surface) {
        Hex hex = new Hex(0);
        hex.setTheme(surface.name().toLowerCase(Locale.ROOT));
        if (surface == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
        assertEquals(surface, BoardFeatures.surface(hex));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        for (int terrain : new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV,
              Terrains.BLDG_CLASS, Terrains.BLDG_ARMOR, Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_FLUFF,
              Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN,
              Terrains.INDUSTRIAL }) {
            hex.addTerrain(new Terrain(terrain, 1));
        }
        var models = new HashMap<>(Map.of(Terrains.BUILDING, "building",
              Terrains.FUEL_TANK, "tank", Terrains.INDUSTRIAL, "industrial"));
        for (int type : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
            String model = models.remove(type);
            assertFalse(BoardFeatures.detailedGround(hex, models), "Every structure needs a replacement model: " + type);
            models.put(type, model);
        }
        assertTrue(BoardFeatures.detailedGround(hex, models), "Models must retain the native " + surface + " ground");
        assertEquals(surface, BoardFeatures.surface(hex));
        for (int terrain : new int[] { Terrains.ROAD_FLUFF, Terrains.RUBBLE,
              Terrains.FORTIFIED, Terrains.GROUND_FLUFF, Terrains.FLUFF }) {
            hex.addTerrain(new Terrain(terrain, 1));
            assertFalse(BoardFeatures.detailedGround(hex, models), "Keep separate terrain markings: " + terrain);
            hex.removeTerrain(terrain);
        }
    }

    @Test
    void fieldTerrainNeverAddsALegacyModelEvenWithUnrepresentedTerrain() {
        var at = new Coords(0, 0);
        var hex = new Hex(0, "planted_fields:1;fortified:1", "");
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardFeatures.capture(hex, at, Map.of()).stream()
              .noneMatch(feature -> feature.asset().equals("vegetation/field")));
        var represented = new TilesetScenery.Scenery(List.of(), Set.of(Terrains.FORTIFIED), 0);
        assertTrue(BoardFeatures.detailedGround(hex, Map.of(), Set.of(), represented));
        assertTrue(BoardFeatures.capture(hex, at, Map.of(), Set.of(), coords -> null, represented).stream()
              .noneMatch(feature -> feature.asset().equals("vegetation/field")),
              "Converted scenery must not add the legacy field prop over instanced crops");
    }

    @Test
    void cityParkEmptyTerrainMarkerKeepsEnhancedGroundBeneathItsBuilding() {
        // City Park 0916 retains an old "none" token beside its actual building terrain.
        Hex hex = new Hex(1, "none:2:4;bldg_elev:3:4;building:4:4;bldg_cf:105:4", "");
        var models = Map.of(Terrains.BUILDING, "buildings/standard/building-hard");
        assertTrue(BoardFeatures.detailedGround(hex, models),
              "An empty legacy marker must not replace enhanced grass with tileset artwork");
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()),
              "Ignoring the empty marker must still require a model for the building");
        hex.addTerrain(new Terrain(Terrains.RUBBLE, 1));
        assertFalse(BoardFeatures.detailedGround(hex, models),
              "Real unmodeled terrain must still retain its artwork");
    }

    @Test
    void waterDecorationDoesNotChangeTheBedMaterialOrInventWater() {
        Hex hex = new Hex(-2);
        hex.setTheme("volcano");
        hex.addTerrain(new Terrain(Terrains.WATER_FLUFF, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertEquals(BoardScene.Surface.VOLCANO, BoardFeatures.surface(hex));
        assertFalse(BoardLiquid.capture(hex).present());
        hex.addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardLiquid.capture(hex).present());
    }

    @Test
    void treeCountsFollowAuthoritativeCoverReductionUntilTheHexIsClear() {
        Coords coords = new Coords(2, 3);
        for (int type : new int[] { Terrains.WOODS, Terrains.JUNGLE }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
            int previous = Integer.MAX_VALUE;
            for (int density = 3; density >= 0; density--) {
                if (density == 0) {
                    hex.removeTerrain(type);
                } else {
                    hex.addTerrain(new Terrain(type, density));
                }
                var features = BoardFeatures.capture(hex, coords, Map.of());
                int count = (int) features.stream().filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).count();
                assertTrue(count < previous, "Each cover reduction must visibly reduce the number of trees");
                assertEquals(density == 0, count == 0, "Trees disappear only when the hex becomes clear");
                assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()), "Unchanged cover must keep its scenery");
                previous = count;
            }
        }
    }

    @Test
    void collectableLimbCountsControlStableGroundProps() {
        Hex hex = new Hex(0);
        Coords coords = new Coords(2, 3);
        hex.addTerrain(new Terrain(Terrains.ARMS, 2));
        hex.addTerrain(new Terrain(Terrains.LEGS, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "A dropped limb must not replace the grass ground");
        var before = BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList();
        assertEquals(3, before.size());
        assertEquals(2, before.stream().filter(feature -> feature.asset().equals("salvage/arm")).count());
        assertEquals(1, before.stream().filter(feature -> feature.asset().equals("salvage/leg")).count());
        assertFalse(before.equals(BoardFeatures.capture(hex, new Coords(6, 3), Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList()), "Hexes no longer repeat every four columns");
        assertEquals(3, before.stream().map(BoardScene.Feature::rotation).distinct().count());
        assertEquals(before, BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList());
        hex.addTerrain(new Terrain(Terrains.ARMS, 1));
        var after = BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList();
        assertEquals(2, after.size());
        assertTrue(before.containsAll(after), "Picking up one limb must not move the others");
        hex.removeTerrain(Terrains.ARMS);
        hex.removeTerrain(Terrains.LEGS);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .noneMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB));
    }

    @Test
    void snowTerrainAndThemeSelectSnowAssetsWhileJungleUsesPalms() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.JUNGLE, 2));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        Coords coords = new Coords(1, 1);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().startsWith("vegetation/palm")));
        hex.addTerrain(new Terrain(Terrains.SNOW, 1));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")));
        hex.removeTerrain(Terrains.SNOW);
        hex.setTheme("snow");
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")));
        hex.setTheme("");
        assertFalse(BoardFeatures.capture(hex, coords, Map.of()).stream().anyMatch(feature -> feature.asset().endsWith("-snow")));
    }

    @Test
    void theThemeSelectsDesertSpeciesWhileSandKeepsItsOwnBiomesTrees() {
        Coords coords = new Coords(3, 2);
        for (int density = 1; density <= 3; density++) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
            hex.setTheme("Desert");
            var themed = BoardFeatures.capture(hex, coords, Map.of());
            assertFalse(themed.isEmpty());
            assertTrue(themed.stream().allMatch(feature -> feature.asset().startsWith("vegetation/palm")
                  || feature.asset().startsWith("vegetation/cactus") || feature.asset().equals("vegetation/tree-dead")),
                  "Desert woodland grows cacti, palms and dead trees");
            hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            assertEquals(themed, BoardFeatures.capture(hex, coords, Map.of()), "Ground paving must not change the biome's trees");
            hex.removeTerrain(Terrains.PAVEMENT);
            hex.setTheme("");
            var temperate = BoardFeatures.capture(hex, coords, Map.of());
            hex.addTerrain(new Terrain(Terrains.SAND, 1));
            assertEquals(temperate, BoardFeatures.capture(hex, coords, Map.of()), "Loose sand does not create a desert biome");
            hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")),
                  "Snow retains the existing winter variants");
        }
    }

    @Test
    void woodsStandAtLeastTheirFoliageHeightAndTheirTreesFitTheGround() {
        Coords coords = new Coords(4, 4);
        for (int density = 1; density <= 3; density++) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
            var trees = BoardFeatures.capture(hex, coords, Map.of());
            assertWoodsHeights(trees, density == 3 ? 3 : 2, density);
            for (var tree : trees) {
                assertTrue(tree.scale() > 0 && tree.scale() <= 2, "Tree placement retains a bounded positive crown scale");
            }
        }
        Hex highland = new Hex(3);
        highland.addTerrain(new Terrain(Terrains.WOODS, 2));
        highland.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        long conifers = BoardFeatures.capture(highland, coords, Map.of()).stream()
              .filter(tree -> tree.asset().startsWith("vegetation/pine")).count();
        assertTrue(conifers > BoardFeatures.capture(highland, coords, Map.of()).size() / 2, "Highland woods are mostly conifers");
        Hex snowfield = new Hex(0);
        snowfield.addTerrain(new Terrain(Terrains.WOODS, 2));
        snowfield.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        snowfield.addTerrain(new Terrain(Terrains.SNOW, 1));
        assertTrue(BoardFeatures.capture(snowfield, coords, Map.of()).stream()
              .filter(tree -> tree.asset().startsWith("vegetation/pine")).count() > BoardFeatures.capture(snowfield, coords, Map.of()).size() / 2, "Snowfields grow snow-laden conifers");
        highland.setTheme("rock");
        assertTrue(BoardFeatures.capture(highland, coords, Map.of()).stream()
              .allMatch(tree -> tree.asset().startsWith("vegetation/pine") || tree.asset().equals("vegetation/tree-dead")),
              "Rocky ground grows hardy conifers and dead trees");
    }

    @Test
    void woodlandMixesSilhouettesWhileRubbleKeepsSmallScatter() {
        Hex hex = new Hex(0);
        Coords coords = new Coords(3, 2);
        hex.addTerrain(new Terrain(Terrains.WOODS, 2));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        var forms = new java.util.HashSet<String>();
        for (int x = 0; x < 12; x++) { BoardFeatures.capture(hex, new Coords(x, 2), Map.of()).forEach(f -> forms.add(f.asset())); }
        assertTrue(forms.size() >= 5);
        for (String theme : new String[] { "", "snow", "desert" }) {
            for (int terrain : new int[] { Terrains.RUBBLE }) {
                hex.removeAllTerrains();
                hex.setTheme(theme);
                hex.addTerrain(new Terrain(terrain, 4));
                assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
                      .allMatch(feature -> feature.kind() == BoardScene.FeatureKind.SCATTER));
                hex.addTerrain(new Terrain(Terrains.WOODS, 1));
                var woodland = BoardFeatures.capture(hex, coords, Map.of());
                assertTrue(!woodland.isEmpty() && woodland.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.TREE),
                      "Cosmetic scatter must preserve coexisting woodland");
            }
        }
    }

    @Test
    void coexistingFeaturesRetainTheirOwnHeightsAndBridgeExits() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BUILDING, 3, true, 9));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 4));
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 18));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, 3));
        String selectedRoof = "buildings/standard/building-hard";
        String industrialRoof = "buildings/saxarba/misc/heavy_industrial_a";
        var models = Map.of(Terrains.BUILDING, selectedRoof, Terrains.INDUSTRIAL, industrialRoof);
        var features = BoardFeatures.capture(hex, new Coords(2, 2), models);
        assertTrue(features.stream().anyMatch(feature -> feature.asset().equals(selectedRoof) && feature.height() == 4));
        var bridges = features.stream().filter(feature -> feature.asset().equals("bridge")).toList();
        assertEquals(1, bridges.size(), "One connected deck per bridge hex");
        assertEquals(2, bridges.getFirst().elevation());
        assertEquals(18, bridges.getFirst().bridgeExits());
        assertTrue(features.stream().anyMatch(feature -> feature.asset().equals(industrialRoof) && feature.height() == 3));
        assertEquals(features, BoardFeatures.capture(hex, new Coords(2, 2), models), "Placement must remain stable across snapshots");
        assertFalse(BoardFeatures.capture(hex, new Coords(2, 2), Map.of()).stream()
              .anyMatch(feature -> feature.asset().startsWith("building")), "Blank tileset artwork must stay blank");
    }

    @Test
    void surfaceMaterialsAndCropsFollowTheHex() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.SAND, 1));
        assertEquals(BoardScene.Surface.GRASS, BoardFeatures.surface(hex));
        assertTrue(BoardSurfaceBlend.capture(hex).sand() > .9f);
        assertTrue(BoardSurfaceBlend.capture(hex).grass() > 0, "Sand retains its exposed substrate");
        hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
        assertEquals(BoardScene.Surface.CONCRETE, BoardFeatures.surface(hex));
        for (int scatter : new int[] { Terrains.ROUGH, Terrains.RUBBLE }) {
            hex.removeAllTerrains();
            hex.setTheme("");
            hex.addTerrain(new Terrain(scatter, 1));
            assertEquals(BoardScene.Surface.GRASS, BoardFeatures.surface(hex), "Scatter keeps the underlying geology");
            hex.setTheme("rock");
            assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
            hex.addTerrain(new Terrain(Terrains.SAND, 1));
            assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
            assertTrue(BoardSurfaceBlend.capture(hex).sand() > .9f);
            assertTrue(BoardSurfaceBlend.capture(hex).rock() > 0);
        }
        hex.removeAllTerrains();
        hex.setTheme("");
        hex.addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertEquals(BoardScene.Biome.FIELD, BoardFeatures.biome(hex));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .noneMatch(feature -> feature.asset().equals("vegetation/field")), "Instanced crops replace the legacy field prop");
    }

    @Test
    void treesOnPavingStandInPlantingPitsThatNeverTouchOrRaiseTheGround() {
        for (int density = 1; density <= 3; density++) {
            for (BoardScene.Surface family : List.of(BoardScene.Surface.CONCRETE, BoardScene.Surface.GRASS)) {
                Hex hex = new Hex(0);
                if (family == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                hex.addTerrain(new Terrain(Terrains.WOODS, density));
                hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                List<BoardScene.Feature> trees = BoardFeatures.capture(hex, BoardScenes.RELIEF_CENTER, Map.of());
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 5; y++) {
                        Coords coords = new Coords(x, y);
                        tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, family, null, null,
                              coords.equals(BoardScenes.RELIEF_CENTER) ? trees : List.of(), List.of(), BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.RELIEF_CENTER));
                List<Vector3> pits = new ArrayList<>();
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.DRESSING) { pits.addAll(List.of(face.a(), face.b(), face.c())); }
                }
                if (family == BoardScene.Surface.GRASS) {
                    assertTrue(pits.isEmpty(), "Only paved ground has planting pits");
                    continue;
                }
                float spacing = Float.POSITIVE_INFINITY;
                List<Vector3> trunks = new ArrayList<>();
                for (BoardScene.Feature tree : trees) {
                    Vector3 trunk = new Vector3(BoardGeometry.centerX(BoardScenes.RELIEF_CENTER) + tree.x() * BoardGeometry.HEX_SCALE,
                          BoardGeometry.centerY(BoardScenes.RELIEF_CENTER) + tree.y() * BoardGeometry.HEX_SCALE, 0);
                    for (Vector3 other : trunks) {
                        spacing = Math.min(spacing, Math.max(Math.abs(trunk.x - other.x), Math.abs(trunk.y - other.y)));
                    }
                    trunks.add(trunk);
                    assertTrue(pits.stream().anyMatch(p -> Math.hypot(p.x - trunk.x, p.y - trunk.y) < .01f),
                          "Every tree stands in its own pit");
                }
                for (Vector3 p : pits) {
                    float own = Float.POSITIVE_INFINITY;
                    for (Vector3 trunk : trunks) { own = Math.min(own, Math.max(Math.abs(p.x - trunk.x), Math.abs(p.y - trunk.y))); }
                    assertTrue(own < spacing / 2, "Neighbouring pits never touch");
                    assertTrue(p.z < BoardRelief.metres(.2f), "A pit is thin dressing on the slab");
                }
                Vector3 center = BoardGeometry.center(BoardScenes.RELIEF_CENTER, 0);
                assertEquals(center.z, surface.height(center.x, center.y), .001f, "Pits never raise what stands there");
            }
        }
    }
}
