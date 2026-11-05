/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadCrossingTest {
    private static final Coords AT = BoardScenes.CENTER;

    @Test
    void independentStraightCrossingsStopBothIncomingLanesBeforeTheActualTrackBand() {
        for (int exits : new int[] { 9, 18, 36 }) {
            for (int rails : new int[] { 9, 18, 36 }) {
                if (exits == rails) { continue; }
                var road = road(exits, RailRoute.Corridor.NONE);
                var crossing = road.crossing(exits, rails, RailRoute.pieces(rails));
                assertEquals(2, crossing.approaches().size());
                assertTrue(crossing.clearance().contains(0, 0));
                Area bars = road.stopLines(crossing.approaches());
                assertFalse(bars.isEmpty());
                bars.intersect(crossing.clearance());
                assertTrue(bars.isEmpty(), "Waiting traffic stops before the rail band");
                var channels = road.approachLines(exits, crossing.approaches());
                for (var stop : crossing.approaches()) {
                    assertTrue(channels.contains(stop.x() + stop.nx() * 3, stop.y() + stop.ny() * 3));
                    assertFalse(channels.contains(stop.x() - stop.nx(), stop.y() - stop.ny()));
                    float across = (stop.inner() + stop.outer()) / 2;
                    assertTrue(road.stopLines(crossing.approaches()).contains(stop.x() - stop.ny() * across,
                          stop.y() + stop.nx() * across), "The stop bar uses the same incoming frame as the roadside control");
                }
            }
        }
    }

    @Test
    void parallelCorridorsAndSharedJunctionsDoNotBecomeIndependentRailwayCrossings() {
        for (int exits : new int[] { 9, 18, 36, 5, 10, 17, 34, 19, 27, 63 }) {
            for (var corridor : List.of(new RailRoute.Corridor(exits, 0, 0), new RailRoute.Corridor(exits, exits, exits))) {
                var crossing = road(exits, corridor).crossing(exits, exits, RailRoute.pieces(exits));
                assertTrue(crossing.approaches().isEmpty());
                assertTrue(crossing.clearance().isEmpty(), "A shared bend or turnout uses its ordinary corridor/junction rules");
            }
        }
        for (int rails : new int[] { 11, 13, 25, 41 }) {
            var crossing = road(9, new RailRoute.Corridor(9, 0, 0)).crossing(9, rails, RailRoute.pieces(rails));
            assertTrue(crossing.approaches().isEmpty(), "A diverging turnout does not cross its shared through road");
            assertTrue(crossing.clearance().isEmpty());
        }
    }

    @Test
    void aSideStreetSharesOneOuterStopBoundaryWithItsRailwayCrossing() {
        for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var road = road(11, corridor);
            var crossing = road.crossing(11, 9, RailRoute.pieces(9));
            assertEquals(List.of(1), crossing.approaches().stream().map(BoardRoad.StopApproach::direction).toList(),
                  "Only the transverse side street crosses the parallel track");
            var merged = road.stopApproaches(11, crossing);
            assertEquals(1, merged.size(), "The road and railway share one bar on the incoming side street");
            var bars = road.stopLines(merged);
            assertFalse(bars.isEmpty());
            bars.intersect(crossing.clearance());
            assertTrue(bars.isEmpty(), "The merged bar cannot move behind the railway");
        }
    }

    @Test
    void capturedCrossingsRequireConventionalRailOnTheRoadsOwnLayer() {
        record Case(String terrain, boolean deck, int count) { }
        for (var example : List.of(new Case("road:1:9;rail:1:18", false, 2),
              new Case("road:1:9;rail:2:18", false, 0), new Case("road:1:18", false, 0),
              new Case("road:1:9;bridge:1:18;bridge_elev:2;bridge_cf:40;bridge_rail:1:18", false, 0),
              new Case("bridge:1:27;bridge_elev:2;bridge_cf:40;bridge_road:1:9;bridge_rail:1:18", true, 2),
              new Case("rail:1:18;bridge:1:9;bridge_elev:2;bridge_cf:40;bridge_road:1:9", true, 0))) {
            var hex = new Hex(0, example.terrain(), "");
            var artwork = new BoardArtwork.HexImage(AT, null, null, List.of(), Map.of(), null);
            var tile = BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(),
                  c -> c.equals(AT) ? hex : new Hex(0));
            int exits = example.deck() ? tile.deckRoadExits() : tile.roadExits();
            var crossing = road(exits, example.deck() ? tile.deckCorridor() : tile.corridor()).crossing(tile, example.deck());
            assertEquals(example.count(), crossing.approaches().size(), example.terrain());
        }
    }

    private static BoardRoad road(int exits, RailRoute.Corridor corridor) {
        return BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).corridor(corridor);
    }

    @Test
    void liveCrossingsReplaceOnlyOldPanelsOnTheSameRoadAndRailSurface() {
        var hex = new Hex(0, "road:1:18;rail:1:9;bridge:1:9;bridge_elev:2;bridge_road:1:18", "");
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        var deck = megamek.common.board.BoardDecoration.Placement.on("bridge", 0);
        String panel = "decal/rail/level-crossing";
        var covered = new megamek.common.board.BoardDecoration("covered", "decal", panel, null, 0, 0, 0, false, 1, ground, 0);
        var above = new megamek.common.board.BoardDecoration("above", "decal", panel, null, 0, 0, 0, false, 1, deck, 0);
        var beside = new megamek.common.board.BoardDecoration("beside", "decal", panel, null, .4, .4, 0, false, 1, ground, 0);
        hex.setDecorations(java.util.List.of(covered, above, beside));
        var at = new Coords(2, 2);
        var visible = BoardFeatures.capture(hex, at, Map.of()).stream().map(BoardScene.Feature::decoration).toList();
        assertFalse(visible.contains(covered), "The road crossing uses the embedded track");
        assertTrue(visible.contains(above), "Ground rails do not replace artwork on the bridge above them");
        assertTrue(visible.contains(beside), "An authored panel away from the crossing remains available");
        megamek.common.board.RailRoute.lay(hex, true, 9, 0);
        visible = BoardFeatures.capture(hex, at, Map.of()).stream().map(BoardScene.Feature::decoration).toList();
        assertFalse(visible.contains(above), "A deck road and deck rail replace their own old crossing panel");
        hex.removeTerrain(Terrains.ROAD);
        visible = BoardFeatures.capture(hex, at, Map.of()).stream().map(BoardScene.Feature::decoration).toList();
        assertTrue(visible.contains(covered), "Track without a road does not claim an authored panel");
    }
}
