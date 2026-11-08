/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardLunarTest {
    private static final Coords ORIGIN = new Coords(0, 0);
    private static final BoardScene.Pixels PIXELS = new BoardScene.Pixels(
          new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
    private static final Predicate<BoardRelief.Kind> STONES = kind -> kind == BoardRelief.Kind.ROCK;
    private static final Predicate<BoardRelief.Kind> SHRUBS = kind -> kind != BoardRelief.Kind.ROCK;

    @ParameterizedTest
    @CsvSource({ "2, 1, 1", "0, 2, -2", "2, 2, 0", "-1, 2, -3", "2, 0, 2", "2, -1, 2" })
    void depthBecomesDryGroundForLabelsSupportAndPicking(int elevation, int depth, int expected) {
        var original = tile(elevation, depth, BoardLiquid.WATER, BoardScene.Surface.GRASS, List.of(), true);
        var lunar = original.lunar(true);
        var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
        var surface = new BoardSurface(scene, lunar);
        var center = BoardGeometry.center(ORIGIN, expected);
        assertEquals(expected, lunar.elevation());
        assertEquals(expected * BoardGeometry.level(), BoardGeometry.groundZ(lunar), .001f);
        assertEquals(center.z, surface.height(center.x, center.y), .001f);
        assertEquals(ORIGIN, BoardGeometry.pick(scene, new Ray(center.cpy().add(0, 0, 500), new Vector3(0, 0, -1))));
        assertTrue(surface.waterFaces.isEmpty());
        assertEquals(expected == 0 ? List.of() : List.of(BoardHexText.level(expected)),
              lunar.text().stream().map(BoardHexText::text).toList());
        assertTrue(lunar.text().stream().allMatch(label -> label.baseline() == 66),
              "Without DEPTH and FOLIAGE the bed's height line settles on the bottom edge");
        assertEquals(lunar, lunar.lunar(true), "Repeated presentation must not lower the bed again");
        assertEquals(elevation, original.elevation());
        assertEquals(depth, original.waterDepth());
    }

    @ParameterizedTest
    @CsvSource({ "0, 2, 1, 3", "2, 1, 1, 2", "-1, 3, 2, 5", "0, 0, 1, 1", "0, -1, 1, 1" })
    void bridgesAndTheirLabelsStayAtTheOriginalAbsoluteLevel(int elevation, int depth, int height, int expectedHeight) {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, height, BoardScene.FeatureKind.PROP, 9);
        var label = new BoardHexText(Messages.getString("BoardView1.HEIGHT") + " " + height, 46,
              new Font(Font.SANS_SERIF, Font.PLAIN, 10), -1, false, height);
        var original = new BoardScene.Tile(ORIGIN, elevation, depth, false, 0, BoardScene.Surface.ROCK, null,
              List.of(bridge), List.of(label));
        var lunar = original.lunar(true);
        var raised = BoardBridge.feature(lunar);
        assertEquals(expectedHeight, raised.elevation());
        assertEquals(bridge.bridgeExits(), raised.bridgeExits());
        assertEquals(elevation + height, lunar.elevation() + raised.elevation());
        assertEquals(Messages.getString("BoardView1.HEIGHT") + " " + expectedHeight, lunar.text().getFirst().text());
        assertEquals(elevation + height, lunar.elevation() + lunar.text().getFirst().elevation());
        assertEquals(lunar, lunar.lunar(true), "Repeated presentation must not raise the bridge again");
        assertEquals(height, BoardBridge.feature(original).elevation(), "The source bridge stays unchanged");

        var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
        var shape = BoardNaturalBridge.build(scene, lunar, BoardBridge.deck(scene, lunar), TerrainLod.COARSE, new HashMap<>());
        var center = BoardGeometry.center(ORIGIN, elevation + height);
        assertEquals(elevation + height, shape.level());
        assertEquals(100, shape.hit(new Ray(center.add(0, 0, 10), new Vector3(0, 0, -1))), .001f,
              "Rendered and picked deck geometry stays at the original level");
    }

    @Test
    void spansOverDifferentDepthsKeepTheirConnectedDeckLevel() {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        var deep = new BoardScene.Tile(ORIGIN, 0, 2, false, 0, BoardScene.Surface.ROCK, null, List.of(bridge),
              List.of()).lunar(true);
        var shallow = new BoardScene.Tile(ORIGIN.translated(3), 0, 1, false, 0, BoardScene.Surface.ROCK, null,
              List.of(bridge), List.of()).lunar(true);
        assertEquals(3, BoardBridge.feature(deep).elevation());
        assertEquals(2, BoardBridge.feature(shallow).elevation());
        assertTrue(BoardBridge.connected(deep, shallow, 3));
        assertTrue(BoardBridge.connected(shallow, deep, 0));
    }

    @Test
    void theTypeBlockOnTheDryBedRaisesTheBridgeAndLosesTheVegetationTheIceAndTheLiquids() {
        var font = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        // SWAMP, HEAVY, DEEP SNOW, ICE and TORRENT go with the liquid; ROUGH, FORTIFIED and FIRE stay.
        var hex = new Hex(0, "water:2;swamp:1;bridge:1;bridge_elev:1;bridge_cf:40;woods:2;foliage_elev:2;ice:1;"
              + "snow:2;rapids:2;rough:1;fortified:1;fire:1", "");
        Map<Boolean, List<String>> lines = new HashMap<>();
        for (boolean types : new boolean[] { false, true }) {
            var labels = BoardHexText.capture(ORIGIN, hex, Board.createEmptyBoard(1, 1), 1, font, font, false, types,
                  false);
            var lunar = new BoardScene.Tile(ORIGIN, 0, 2, true, 0, BoardScene.Surface.ROCK, null,
                  List.of(bridge), labels).lunar(true);
            lines.put(types, lunar.text().stream().map(BoardHexText::text).toList());
            assertEquals(3, lunar.text().get(1).elevation(), "HEIGHT 3 or BRIDGE▲3 on the deck, as high as before");
            assertEquals(lunar, lunar.lunar(true), "Repeated presentation must not raise the bridge again");
            assertEquals(66, lunar.text().stream().filter(label -> label.elevation() == 0)
                  .mapToInt(BoardHexText::baseline).max().orElse(0), "The ground lines still end on the bottom edge");
            // In vacuum the frozen water keeps its depth, ICE and TORRENT, and the deck its height; the swamp, the
            // woods and the snow go with the old surface.
            var airless = new BoardScene.Tile(ORIGIN, 0, 2, true, 0, BoardScene.Surface.ROCK, null,
                  List.of(bridge), labels).lunar(false);
            assertEquals(labels.stream().map(BoardHexText::text)
                        .filter(line -> !List.of("SWAMP", "HEAVY", "DEEP SNOW").contains(line)).toList(),
                  airless.text().stream().map(BoardHexText::text).toList());
            if (types) {
                assertTrue(airless.text().stream().map(BoardHexText::text).toList()
                      .containsAll(List.of("ICE", "TORRENT")));
            }
        }
        // The deck keeps its absolute level with Hex Types off and on alike.
        assertEquals(List.of("SUBLEVEL 2", Messages.getString("BoardView1.HEIGHT") + " 3"), lines.get(false));
        assertEquals(List.of("SUBLEVEL 2", BoardHexText.bridge(3), "ROUGH", "FORTIFIED", "FIRE"), lines.get(true));
    }

    @Test
    void allLiquidAndSurfaceFamiliesBecomeDryWithoutVegetationOrIceKeepingConcrete() {
        var building = feature("building", BoardScene.FeatureKind.BUILDING);
        var boulder = feature("boulder-0", BoardScene.FeatureKind.BOULDER);
        var features = List.of(building, boulder, feature("tree-oak", BoardScene.FeatureKind.TREE),
              feature("vegetation/foliage-temperate", BoardScene.FeatureKind.TREE),
              feature("scatter-plant", BoardScene.FeatureKind.SCATTER),
              feature("scatter-rock", BoardScene.FeatureKind.SCATTER),
              feature("rough/felled-trunk", BoardScene.FeatureKind.ROUGH));
        for (var kind : BoardLiquid.Kind.values()) {
            for (var family : BoardScene.Surface.values()) {
                var lunar = tile(0, -1, new BoardLiquid(kind, "", 2), family, features, true).lunar(true);
                var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
                assertEquals(family == BoardScene.Surface.CONCRETE ? family : BoardScene.Surface.LUNAR, lunar.surface());
                assertEquals(BoardSurfaceBlend.solid(lunar.surface()), lunar.groundCover(),
                      "The rendered material must match the airless surface, including preserved concrete");
                assertTrue(lunar.detailedGround(), "The airless material must replace captured ground artwork");
                assertEquals(BoardLiquid.NONE, lunar.liquid());
                assertFalse(lunar.water());
                assertFalse(lunar.frozen());
                assertFalse(lunar.blackIce());
                assertEquals(BoardScene.Biome.NONE, lunar.biome());
                assertEquals(BoardScene.Biome.NONE, BoardBiome.kind(lunar));
                assertFalse(GpuGroundCover.grows(scene, lunar));
                assertFalse(BoardScatter.allowed(lunar), "Neither captured scatter nor field cover dresses bare rock");
                assertEquals(List.of(building, new BoardScene.Feature(BoardRocks.OUTCROP, 0, 0, 0, 3, 1.5f, 0,
                      BoardScene.FeatureKind.BOULDER)), lunar.features(), "A loose boulder becomes bedrock");
                assertNull(lunar.tileset());
                assertNull(lunar.decals());
                assertNull(lunar.decalsWithoutLimbs());
                assertEquals(BoardRoad.Kind.PAVED, lunar.road());
                assertTrue(lunar.impassable());
            }
        }
    }

    @Test
    void vacuumFreezesTheWaterKeepsTheMagmaAndWhatGravityHoldsButNothingGrows() {
        var building = feature("building", BoardScene.FeatureKind.BUILDING);
        var boulder = feature("rough-boulder", BoardScene.FeatureKind.BOULDER);
        var stone = feature("scatter-rock", BoardScene.FeatureKind.SCATTER);
        var features = List.of(building, boulder, feature("tree-oak", BoardScene.FeatureKind.TREE),
              feature("vegetation/foliage-temperate", BoardScene.FeatureKind.TREE),
              feature("scatter-plant", BoardScene.FeatureKind.SCATTER), stone,
              feature(BoardFungus.SCATTER.getFirst(), BoardScene.FeatureKind.SCATTER),
              feature("rough/felled-trunk", BoardScene.FeatureKind.ROUGH));
        for (var kind : BoardLiquid.Kind.values()) {
            for (var family : BoardScene.Surface.values()) {
                var liquid = new BoardLiquid(kind, "", 2);
                int depth = liquid.present() && !liquid.molten() ? 2 : -1;
                var original = tile(1, depth, liquid, family, features, false);
                var airless = original.lunar(false);
                var scene = new BoardScene(0, 1, 1, List.of(airless), List.of(), List.of(), -1, "", List.of());
                assertEquals(family == BoardScene.Surface.CONCRETE ? family : BoardScene.Surface.LUNAR, airless.surface());
                assertEquals(BoardSurfaceBlend.solid(airless.surface()), airless.groundCover(),
                      "The rendered material must match the airless surface, including preserved concrete");
                assertTrue(airless.detailedGround(), "The airless material must replace captured ground artwork");
                assertEquals(liquid, airless.liquid(), "Water freezes and magma stays");
                assertEquals(depth >= 0, airless.frozen(), "Water and hazardous liquids freeze as ICE does");
                assertEquals(original.elevation(), airless.elevation());
                assertEquals(original.waterDepth(), airless.waterDepth());
                assertFalse(airless.blackIce());
                assertEquals(BoardScene.Biome.NONE, BoardBiome.kind(airless));
                assertFalse(GpuGroundCover.grows(scene, airless));
                assertFalse(airless.bare(), "Gravity holds loose stones");
                assertTrue(airless.barren(), "Nothing grows, not even the relief's field shrubs");
                assertEquals(!liquid.present() && !liquid.volcanic(), BoardScatter.allowed(airless),
                      "Loose stones dress the dry ground");
                assertEquals(List.of(building, boulder, stone), airless.features(),
                      "Vegetation goes; boulders and loose stones stay");
                assertEquals(original.text().stream().map(BoardHexText::text)
                            .filter(line -> !line.equals(Messages.getString("BoardView1.LowFoliage"))).toList(),
                      airless.text().stream().map(BoardHexText::text).toList(), "Only the vegetation's label goes");
                assertNull(airless.tileset());
                assertNull(airless.decals());
                assertEquals(airless, airless.lunar(false), "Repeated presentation changes nothing");
            }
        }
    }

    @Test
    void roughBouldersBecomeFewerLargerOutcropsOnce() {
        var boulders = new ArrayList<BoardScene.Feature>();
        for (int i = 0; i < 9; i++) {
            boulders.add(new BoardScene.Feature("rough-boulder", i, -i, 40 * i, .8f, .3f, 0, BoardScene.FeatureKind.BOULDER));
        }
        var lunar = tile(0, -1, BoardLiquid.NONE, BoardScene.Surface.GRASS, boulders, true).lunar(true);
        assertEquals(3, lunar.features().size(), "Every third boulder of the spiral, from its centre, becomes bedrock");
        for (int i = 0; i < lunar.features().size(); i++) {
            var outcrop = lunar.features().get(i);
            var boulder = boulders.get(3 * i);
            assertEquals(BoardRocks.OUTCROP, outcrop.asset());
            assertEquals(BoardScene.FeatureKind.BOULDER, outcrop.kind(), "Outcrops remain rough cover for support");
            assertEquals(boulder.x(), outcrop.x());
            assertEquals(boulder.y(), outcrop.y());
            assertTrue(outcrop.scale() > boulder.scale() && outcrop.height() >= boulder.height());
        }
        assertEquals(lunar, lunar.lunar(true), "Repeated presentation must not thin the outcrops again");
        assertEquals(boulders, tile(0, -1, BoardLiquid.NONE, BoardScene.Surface.GRASS, boulders, true).lunar(false)
              .features(), "In vacuum gravity still holds every loose boulder");
    }

    @Test
    void openGroundKeepsNoLooseStonesOrShrubs() {
        // The relief dresses featureless open ground with field stones and shrubs. Stripping a meadow's captured
        // scatter must not hand the bare plain to that dressing, nor may a rocky plain's cached surface keep it.
        for (boolean meadow : new boolean[] { true, false }) {
            var plain = meadow ? plain(BoardScene.Surface.GRASS, true, 5) : plain(BoardScene.Surface.ROCK, false, 5);
            var surfaces = new BoardSurface.Cache();
            assertTrue(standing(plain, surfaces, kind -> true) > 0,
                  "At one g featureless hexes carry field stones and shrubs");
            assertEquals(0, standing(lunar(plain, true), surfaces, kind -> true),
                  meadow ? "Stripped scatter leaves no field cover behind" : "Bare rock never reuses a one-g surface");
        }
    }

    @Test
    void vacuumOpenGroundKeepsFieldStonesButGrowsNoShrubs() {
        for (boolean meadow : new boolean[] { true, false }) {
            var plain = meadow ? plain(BoardScene.Surface.GRASS, true, 5) : plain(BoardScene.Surface.ROCK, false, 5);
            var airless = lunar(plain, false);
            var surfaces = new BoardSurface.Cache();
            assertTrue(standing(airless, surfaces, STONES) > 0, "Gravity holds the airless plain's loose stones");
            assertEquals(0, standing(airless, surfaces, SHRUBS), "Nothing grows in vacuum");
        }
    }

    @Test
    void lunarGroundWithAirKeepsItsShrubs() {
        var moon = plain(BoardScene.Surface.LUNAR, false, 8);
        var surfaces = new BoardSurface.Cache();
        assertTrue(standing(moon, surfaces, SHRUBS) > 0, "A lunar-theme map with air keeps its field shrubs");
        var airless = lunar(moon, false);
        assertEquals(standing(moon, surfaces, STONES), standing(airless, surfaces, STONES),
              "Vacuum keeps the very same field stones");
        assertEquals(0, standing(airless, surfaces, SHRUBS), "Vacuum takes only the shrubs");
    }

    /** A flat square plain of one ground family, featureless or with its captured scatter. */
    private static BoardScene plain(BoardScene.Surface family, boolean scatter, int size) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                var coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, family, null, null,
                      scatter ? BoardFeatures.capture(new Hex(0), coords, Map.of()) : List.of(), List.of(),
                      BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene lunar(BoardScene scene, boolean weightless) {
        return scene.withTiles(scene.tiles().stream().map(tile -> tile.lunar(weightless)).toList());
    }

    /**
     * Faces standing on a flat plain without rough, of the given relief kinds: its field stones (ROCK) and shrubs
     * (PLANT, LEAF), which also carry units.
     */
    private static long standing(BoardScene scene, BoardSurface.Cache surfaces, Predicate<BoardRelief.Kind> kinds) {
        return scene.tiles().stream().mapToLong(tile -> {
            var surface = surfaces.get(scene, tile);
            return surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.OUTCROP
                  && kinds.test(surface.relief.shade(face.a()).kind())).count();
        }).sum();
    }

    private static BoardScene.Feature feature(String asset, BoardScene.FeatureKind kind) {
        return new BoardScene.Feature(asset, 0, 0, 0, 1, 1, 0, kind);
    }

    private static BoardScene.Tile tile(int elevation, int depth, BoardLiquid liquid, BoardScene.Surface family,
          List<BoardScene.Feature> features, boolean frozen) {
        var font = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        var labels = new ArrayList<BoardHexText>();
        // As BoardHexText.capture stacks them: the height line on top, then DEPTH, the last line on the bottom edge.
        if (elevation != 0) {
            labels.add(new BoardHexText(BoardHexText.level(elevation), 46, font, -1, false, 0));
        }
        if (depth > 0) {
            labels.add(new BoardHexText(Messages.getString("BoardView1.DEPTH") + " " + depth, 56, font, -1, false, 0));
        }
        labels.add(new BoardHexText(Messages.getString("BoardView1.LowFoliage"), 66, font, -1, false, 0));
        return new BoardScene.Tile(ORIGIN, elevation, depth, frozen, 0, family, PIXELS, PIXELS, features, labels, liquid, PIXELS, false, BoardRoad.Kind.PAVED, BoardFireSmoke.NONE,
              BoardScene.Biome.MARSH, true, true);
    }

    @Test
    void lunarThemeKeepsItsOwnGeologyWithoutReplacingExplicitSurfaceTerrain() {
        Hex hex = new Hex(0);
        hex.setTheme("lunar");
        assertEquals(BoardScene.Surface.LUNAR, BoardFeatures.surface(hex));
        hex.setTheme("rock");
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
        hex.setTheme("lunar");
        hex.addTerrain(new Terrain(Terrains.MAGMA, 1));
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
        hex.removeTerrain(Terrains.MAGMA);
        hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
        assertEquals(BoardScene.Surface.CONCRETE, BoardFeatures.surface(hex));
    }
}
