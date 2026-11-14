/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardScorchTest {
    static final Coords AT = new Coords(3, 3), FAR = new Coords(0, 0);

    @Test
    void scorchRemovesTrafficLightsAndEveryProjectionOfAllDecalKinds() throws Exception {
        var source = BoardScenes.scene(board());
        var tile = source.tile(AT);
        assertTrue(tile.features().stream().anyMatch(f -> f.asset().equals("scenery/road/traffic-signal-gantry")));
        var before = BoardDecals.index(source);
        assertTrue(before.values().stream().filter(stamps -> stamps.stream().anyMatch(s -> s.owner().equals(AT))).count() > 1,
              "The parking decal must spill beyond its owning hex");
        var burned = tile.withoutGroundCover(false);
        assertTrue(burned.features().isEmpty(), "Traffic controls, placed props, decals and scatter all go");
        var shown = BoardScenes.replace(source, burned);
        var after = BoardDecals.update(source, shown, before);
        assertEquals(BoardDecals.index(shown), after, "Incremental decal removal matches a fresh load");
        assertTrue(after.values().stream().flatMap(List::stream).noneMatch(s -> s.owner().equals(AT)));
        assertTrue(after.values().stream().flatMap(List::stream).anyMatch(s -> s.owner().equals(FAR)));
        assertSame(source.tile(FAR), shown.tile(FAR));
        assertFalse(tile.features().isEmpty(), "The captured game scene stays immutable");
        assertSame(burned, burned.withoutGroundCover(false));
    }

    @Test
    void scorchKeepsSurvivingGameTerrainAndStructures() {
        var kept = new ArrayList<BoardScene.Feature>();
        for (var kind : List.of(BoardScene.FeatureKind.TREE, BoardScene.FeatureKind.BUILDING,
              BoardScene.FeatureKind.INDUSTRIAL, BoardScene.FeatureKind.FUEL_TANK, BoardScene.FeatureKind.LIMB,
              BoardScene.FeatureKind.BOULDER, BoardScene.FeatureKind.ROUGH, BoardScene.FeatureKind.SCENERY,
              BoardScene.FeatureKind.METAL, BoardScene.FeatureKind.SHAFT)) {
            kept.add(new BoardScene.Feature(kind.name(), 0, 0, 0, 1, 1, 0, kind));
        }
        kept.add(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 2));
        for (var kind : RailRoute.Kind.values()) {
            var route = object(kind.name(), "prop", kind.asset, 1);
            kept.add(new BoardScene.Feature(route.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, route));
        }
        var building = object("building", "prop", "building", 1);
        kept.add(new BoardScene.Feature(building.asset(), 0, 0, 0, 1, 2, 0,
              BoardScene.FeatureKind.BUILDING, 0, false, building));
        var features = new ArrayList<>(kept);
        features.add(new BoardScene.Feature("scatter-rock", 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.SCATTER));
        features.add(new BoardScene.Feature("scenery/road/traffic-signal", 0, 0, 0, 1, 1, 0));
        var pixels = new BoardScene.Pixels(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB));
        var tile = BoardScenes.newTile(AT, 0, -1, false, 27, BoardScene.Surface.GRASS,
              features, List.of(), BoardLiquid.NONE, pixels, BoardRoad.Kind.PAVED, BoardFireSmoke.NONE,
              BoardScene.Biome.NONE, false, false, 0, false, BoardSurfaceBlend.solid(BoardScene.Surface.GRASS),
              false, pixels);
        var burned = tile.withoutGroundCover(false);
        assertEquals(kept, burned.features());
        assertSame(pixels, burned.tileset());
        assertSame(pixels, burned.tilesetScenery(), "Classic terrain sprites such as rubble still represent game terrain");
        assertEquals(tile.roadExits(), burned.roadExits());
        assertTrue(burned.barren());
    }

    static Board board() {
        Board board = Board.createEmptyBoard(7, 7);
        board.setNativeFormat(true);
        Hex junction = board.getHex(AT);
        junction.addTerrain(new Terrain(Terrains.ROAD, 1, true, 27));
        junction.setAppearance(Map.of("road", HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.GANTRY)));
        for (int direction : new int[] { 0, 1, 3, 4 }) {
            board.getHex(AT.translated(direction)).addTerrain(new Terrain(Terrains.ROAD, 1, true, 1 << (direction + 3) % 6));
        }
        for (Coords at : List.of(AT, FAR)) {
            board.getHex(at).setDecorations(List.of(
                  object("parking-" + at, "decal", "decal/car-park/straight-4", 3),
                  object("emblem-" + at, "decal", "decal/emblems/capellan-confederation", 1),
                  object("damage-" + at, "decal", "decal/damage/cleared-rubble-gravel", 1),
                  object("barrier-" + at, "prop", "scenery/vehicles/parking-barrier", 1)));
        }
        return board;
    }

    private static BoardDecoration object(String id, String kind, String asset, double scale) {
        return new BoardDecoration(id, kind, asset, null, 0, 0, 0, false, scale,
              BoardDecoration.Placement.ground(), 0, false);
    }
}
