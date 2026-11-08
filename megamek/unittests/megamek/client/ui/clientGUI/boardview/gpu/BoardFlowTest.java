/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector2;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardFlowTest {
    @Test
    void flatLakesAndAmbiguousFlatRiversHaveNoInventedCurrent() {
        Map<Coords, Integer> water = new HashMap<>();
        for (int y = 0; y < 12; y++) { water.put(new Coords(2, y), 0); }
        Coords lake = new Coords(8, 6);
        for (int x = 6; x <= 10; x++) {
            for (int y = 4; y <= 8; y++) {
                if (lake.distance(new Coords(x, y)) <= 2) { water.put(new Coords(x, y), 0); }
            }
        }
        assertTrue(BoardFlow.calculate(scene(12, 12, water, Map.of(), Set.of())).isEmpty());
    }

    @Test
    void aLevelEdgeRiverFeedsTheWiderLakeButElevationTakesPriority() {
        Map<Coords, Integer> water = new HashMap<>();
        Coords center = new Coords(6, 8);
        for (int x = 3; x <= 9; x++) {
            for (int y = 5; y <= 11; y++) {
                Coords coords = new Coords(x, y);
                if (center.distance(coords) <= 2) { water.put(coords, 0); }
            }
        }
        for (int y = 0; y < 6; y++) { water.put(new Coords(6, y), 0); }
        for (BoardLiquid liquid : List.of(BoardLiquid.WATER, new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0))) {
            Map<Coords, BoardLiquid> types = new HashMap<>();
            water.keySet().forEach(coords -> types.put(coords, liquid));
            var towardLake = BoardFlow.calculate(scene(13, 14, water, types, Set.of()));
            for (int y = 0; y < 5; y++) {
                assertDownstream(towardLake.get(new Coords(6, y)), new Coords(6, y), new Coords(6, y + 1));
            }
            assertFalse(towardLake.containsKey(center), "An incoming river must not translate the whole lake");
            water.put(new Coords(6, 0), -2);
            var downhill = BoardFlow.calculate(scene(13, 14, water, types, Set.of()));
            for (int y = 1; y < 5; y++) {
                assertDownstream(downhill.get(new Coords(6, y)), new Coords(6, y), new Coords(6, y - 1));
            }
            water.put(new Coords(6, 0), 0);
        }
    }

    @Test
    void domeVentInletsFeedAllThreeBranchesForLavaAndWater() {
        BoardScene lava = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        assertEquals(59, lava.tiles().stream().filter(tile -> tile.liquid().molten()).count());
        for (BoardScene scene : List.of(lava, BoardWaterfallTest.withWater(lava))) {
            var currents = BoardFlow.calculate(scene);
            var field = new GpuWaterShader.Pools(scene, currents, new HashMap<>(), scene.tile(new Coords(14, 8)).liquid().molten());
            // User-marked arms: 1509 -> 1608, 1305 -> 1404 -> 1403, and 0805 -> 0706 -> 0606.
            for (Coords[] reach : List.of(
                  new Coords[] { new Coords(14, 8), new Coords(15, 7) },
                  new Coords[] { new Coords(13, 3), new Coords(13, 2) },
                  new Coords[] { new Coords(7, 4), new Coords(6, 5) },
                  new Coords[] { new Coords(6, 5), new Coords(5, 5) })) {
                assertDownstream(currents.get(reach[0]), reach[0], reach[1]);
                var at = BoardGeometry.center(reach[0], scene.tile(reach[0]).elevation());
                float[] sample = new float[4];
                field.sample(at.x, at.y, sample);
                assertTrue(new Vector2(sample[1], sample[2]).nor().dot(direction(reach[0], reach[1])) > .7f,
                      "The continuous render field must preserve the branch direction at " + reach[0]);
            }
            // The main descents still travel from their higher hex into the lower one.
            for (Coords[] reach : List.of(
                  new Coords[] { new Coords(13, 10), new Coords(13, 9) },
                  new Coords[] { new Coords(11, 6), new Coords(11, 5) },
                  new Coords[] { new Coords(14, 12), new Coords(14, 11) })) {
                assertTrue(scene.tile(reach[0]).elevation() > scene.tile(reach[1]).elevation());
                assertDownstream(currents.get(reach[0]), reach[0], reach[1]);
            }
        }
    }

    @Test
    void currentsFollowAFlatBendTowardItsLowerOutletAndReverseWhenTheOutletChanges() {
        List<Coords> river = new ArrayList<>(List.of(new Coords(3, 1)));
        for (int direction : new int[] { 3, 3, 2, 2, 3 }) { river.add(river.getLast().translated(direction)); }
        Map<Coords, Integer> water = new HashMap<>();
        river.forEach(coords -> water.put(coords, 2));
        water.put(river.getLast(), 0);
        Map<Coords, BoardFlow.Current> forward = BoardFlow.calculate(scene(12, 12, water, Map.of(), Set.of()));
        for (int index = 0; index < river.size() - 1; index++) {
            assertDownstream(forward.get(river.get(index)), river.get(index), river.get(index + 1));
        }
        assertFalse(forward.containsKey(river.getLast()), "The closed lower pool has no assumed outlet");
        water.put(river.getLast(), 2);
        water.put(river.getFirst(), 0);
        Map<Coords, BoardFlow.Current> reverse = BoardFlow.calculate(scene(12, 12, water, Map.of(), Set.of()));
        for (int index = 1; index < river.size(); index++) {
            assertDownstream(reverse.get(river.get(index)), river.get(index), river.get(index - 1));
        }
    }

    @Test
    void aHigherInletDoesNotMakeTheLakeBodyDrift() {
        Map<Coords, Integer> water = new HashMap<>();
        Coords center = new Coords(6, 6), inlet = new Coords(6, 3);
        for (int x = 3; x <= 9; x++) {
            for (int y = 3; y <= 9; y++) {
                Coords coords = new Coords(x, y);
                if (center.distance(coords) <= 2) { water.put(coords, 0); }
            }
        }
        water.put(inlet, 2);
        Map<Coords, BoardFlow.Current> currents = BoardFlow.calculate(scene(12, 12, water, Map.of(), Set.of()));
        assertEquals(Set.of(inlet), currents.keySet());
    }

    @Test
    void aRiverKeepsItsCurrentWhereItWidensThroughAShallow() {
        // As on Mountain Lake (Savannah): the reach widens to two hexes, open on three or four sides at its own level.
        // Only water open on five or more sides is a lake's still body.
        Map<Coords, Integer> water = new HashMap<>();
        for (int y = 1; y <= 9; y++) { water.put(new Coords(3, y), 1); }
        List<Coords> widening = List.of(new Coords(3, 4), new Coords(3, 5), new Coords(4, 4), new Coords(4, 5));
        widening.forEach(coords -> water.put(coords, 1));
        Coords outlet = new Coords(3, 10);
        water.put(outlet, 0);
        var currents = BoardFlow.calculate(scene(8, 12, water, Map.of(), Set.of()));
        for (Coords coords : widening) {
            assertTrue(currents.containsKey(coords), "The widened reach keeps its current at " + coords);
            assertTrue(velocity(currents.get(coords)).dot(direction(coords, outlet)) > 0, "Downstream at " + coords);
        }
    }

    @Test
    void iceAndIncompatibleLiquidsStopTheCurrentButToxicWaterSharesIt() {
        Map<Coords, Integer> water = Map.of(new Coords(3, 2), 2, new Coords(3, 3), 2, new Coords(3, 4), 0);
        assertTrue(BoardFlow.calculate(scene(8, 8, water, Map.of(), Set.of(new Coords(3, 3)))).isEmpty());
        var lava = new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
        assertTrue(BoardFlow.calculate(scene(8, 8, water, Map.of(new Coords(3, 4), lava), Set.of())).isEmpty());
        var toxic = new BoardLiquid(BoardLiquid.Kind.HAZARDOUS, "", 0);
        assertDownstream(BoardFlow.calculate(scene(8, 8, water, Map.of(new Coords(3, 4), toxic), Set.of()))
              .get(new Coords(3, 2)), new Coords(3, 2), new Coords(3, 3));
    }

    @Test
    void flowAcceleratesNearTheConnectedWaterfallAndRespondsToItsDropHeight() {
        Map<Coords, Integer> water = new HashMap<>();
        for (int y = 1; y <= 8; y++) { water.put(new Coords(3, y), 2); }
        Coords outlet = new Coords(3, 8), lip = new Coords(3, 7);
        water.put(outlet, -1);
        var currents = BoardFlow.calculate(scene(8, 10, water, Map.of(), Set.of()));
        // The river's head at (3, 1) runs slow of its own accord.
        assertEquals(speed(currents.get(new Coords(3, 2))), speed(currents.get(new Coords(3, 4))), 0.0001f,
              "A waterfall must not accelerate the whole river");
        for (int y = 4; y < 7; y++) {
            assertTrue(speed(currents.get(new Coords(3, y + 1))) > speed(currents.get(new Coords(3, y))),
                  "Each approach hex must flow faster toward the lip");
        }
        water.put(outlet, -2);
        var largerDrop = BoardFlow.calculate(scene(8, 10, water, Map.of(), Set.of()));
        assertTrue(speed(largerDrop.get(lip)) > speed(currents.get(lip)), "Larger drops pull a faster current");
        water.put(outlet, -100);
        var extremeDrop = BoardFlow.calculate(scene(8, 10, water, Map.of(), Set.of()));
        assertEquals(speed(largerDrop.get(lip)), speed(extremeDrop.get(lip)), 0.0001f,
              "Extreme map elevations must not create unbounded animation speeds");
        assertFalse(extremeDrop.containsKey(outlet), "The closed receiving pool remains in place");
    }

    @Test
    void slopingOutletsKeepTheirLevelApproachAtOrdinaryStreamSpeed() {
        for (int drop : new int[] { 1, 2 }) {
            Map<Coords, Integer> water = new HashMap<>();
            for (int y = 1; y <= 8; y++) { water.put(new Coords(3, y), drop); }
            water.put(new Coords(3, 8), 0);
            var currents = BoardFlow.calculate(scene(8, 10, water, Map.of(), Set.of()));
            for (int y = 4; y <= 7; y++) {
                assertEquals(speed(currents.get(new Coords(3, 2))), speed(currents.get(new Coords(3, y))), .0001f,
                      "A sloping outlet must not churn the flat reach before it");
                assertDownstream(currents.get(new Coords(3, y)), new Coords(3, y), new Coords(3, y + 1));
            }
        }
    }

    private static float speed(BoardFlow.Current current) {
        return new Vector2(current.u(), current.v() * BoardGeometry.HEIGHT / BoardGeometry.WIDTH).len();
    }

    @Test
    void distantOutletEditsReverseAnUnchangedReachAcrossChunkBoundaries() {
        Map<Coords, Integer> water = new HashMap<>();
        for (int y = 1; y <= 63; y++) { water.put(new Coords(3, y), 1); }
        water.put(new Coords(3, 63), 0);
        var south = BoardFlow.calculate(scene(8, 65, water, Map.of(), Set.of()));
        water.put(new Coords(3, 63), 1);
        water.put(new Coords(3, 1), 0);
        var north = BoardFlow.calculate(scene(8, 65, water, Map.of(), Set.of()));
        assertDownstream(south.get(new Coords(3, 32)), new Coords(3, 32), new Coords(3, 33));
        assertDownstream(north.get(new Coords(3, 32)), new Coords(3, 32), new Coords(3, 31));
    }

    private static void assertDownstream(BoardFlow.Current current, Coords from, Coords to) {
        assertTrue(current != null, "Missing current at " + from);
        assertTrue(velocity(current).dot(direction(from, to)) > 0.7f, "Stream must head toward its outlet at " + from);
    }

    private static Vector2 velocity(BoardFlow.Current current) {
        return new Vector2(-current.u() * BoardGeometry.WIDTH, current.v() * BoardGeometry.HEIGHT).nor();
    }

    private static Vector2 direction(Coords from, Coords to) {
        return new Vector2(BoardGeometry.centerX(to) - BoardGeometry.centerX(from),
              BoardGeometry.centerY(to) - BoardGeometry.centerY(from)).nor();
    }

    private static BoardScene scene(int width, int height, Map<Coords, Integer> water, Map<Coords, BoardLiquid> types,
          Set<Coords> frozen) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Coords coords = new Coords(x, y);
                boolean wet = water.containsKey(coords);
                tiles.add(new BoardScene.Tile(coords, water.getOrDefault(coords, 0), wet ? 1 : -1, frozen.contains(coords),
                      0, BoardScene.Surface.ROCK, null, null, List.of(), List.of(),
                      wet ? types.getOrDefault(coords, BoardLiquid.WATER) : BoardLiquid.NONE));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
