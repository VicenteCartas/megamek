/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;

import com.badlogic.gdx.math.Vector2;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRoadJunctionTest {
    @Test
    void junctionApproachesHaveSolidChannelsThenDashesBeyondTheFinalRoadWidth() {
        var at = BoardScenes.CENTER;
        var road = BoardRoad.clearance(at, 27).extended(java.util.Collections.nCopies(6, 50f));
        var paint = road.markings(at, 27);
        for (var stop : road.stopApproaches(27)) {
            for (float distance = 2; distance <= 30; distance += .25f) {
                assertTrue(paint.contains(stop.x() + stop.nx() * distance, stop.y() + stop.ny() * distance),
                      "The last full road-width before the conflict is continuously divided");
            }
            assertFalse(paint.contains(stop.x() - stop.nx(), stop.y() - stop.ny()), "The crossing remains open for turns");
            boolean gap = false;
            for (float distance = 34; distance <= 50; distance += .25f) {
                gap |= !paint.contains(stop.x() + stop.nx() * distance, stop.y() + stop.ny() * distance);
            }
            assertTrue(gap, "The more distant straight approach retains its dashes");
        }
    }

    @Test
    void everyThreeAndFourWayJunctionHasASolidHubAndStopsOnlyItsIncomingLanes() {
        for (int column : new int[] { 3, 4 }) {
            Coords at = new Coords(column, 4);
            for (int exits = 0; exits < 64; exits++) {
                int count = Integer.bitCount(exits);
                var road = BoardRoad.clearance(at, exits);
                var approaches = road.stopApproaches(exits);
                Area stops = road.stopLines(exits);
                if (count < 3 || count > 4) {
                    assertTrue(approaches.isEmpty(), "Through roads, dead ends and roundabouts retain their existing layout");
                    assertTrue(stops.isEmpty());
                    continue;
                }
                boolean through = (exits & (exits >> 3)) != 0;
                assertEquals(count == 3 && through ? 1 : count, approaches.size());
                Area footprint = road.footprint(0), dashes = road.markings(at, exits);
                assertFalse(dashes.intersects(-6, -6, 12, 12), "Every junction leaves its central turning area unmarked");
                for (int degrees = 0; degrees < 360; degrees += 15) {
                    double angle = Math.toRadians(degrees);
                    assertTrue(footprint.contains(7 * Math.cos(angle), 7 * Math.sin(angle)), "A crossing has no central island");
                }
                for (var approach : approaches) {
                    assertTrue((exits & (1 << approach.direction())) != 0);
                    assertEquals(1, Math.hypot(approach.nx(), approach.ny()), .0001);
                    assertFalse(dashes.contains(approach.x() - approach.nx(), approach.y() - approach.ny()),
                          "A controlled approach ends its centre dashes before its stop plane");
                    for (float across = approach.inner() + .2f; across < approach.outer(); across += .5f) {
                        float x = approach.x() - approach.ny() * across;
                        float y = approach.y() + approach.nx() * across;
                        assertTrue(stops.contains(x, y), "One continuous transverse bar covers the incoming lane");
                        assertTrue(footprint.contains(x, y), "Stop paint stays on its carriageway");
                        assertFalse(stops.contains(x + approach.nx(), y + approach.ny()), "The bar is a narrow line");
                        assertFalse(stops.contains(approach.x() + approach.ny() * across,
                              approach.y() - approach.nx() * across), "The outgoing lane is not stopped");
                    }
                }
                stops.subtract(footprint);
                assertTrue(stops.isEmpty());
            }
        }
    }

    @Test
    void everyTStopsOnlyItsSideRoadJustBeforeTheThroughCarriageway() {
        for (int axis = 0; axis < 3; axis++) {
            for (int branch = 0; branch < 6; branch++) {
                if (branch == axis || branch == axis + 3) { continue; }
                int exits = (1 << axis) | (1 << (axis + 3)) | (1 << branch);
                var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
                var stops = road.stopApproaches(exits);
                assertEquals(1, stops.size());
                assertEquals(branch, stops.getFirst().direction());
                var stop = stops.getFirst();
                float[] main = RailRoute.border(axis);
                var normal = new Vector2(-main[1], main[0]).nor();
                for (float across = stop.inner(); across < stop.outer(); across += .5f) {
                    float x = stop.x() - stop.ny() * across, y = stop.y() + stop.nx() * across;
                    assertTrue(Math.abs(x * normal.x + y * normal.y) > BoardRoad.Kind.PAVED.halfWidth,
                          "Waiting traffic stays outside the through carriageway");
                }
                assertTrue(Math.hypot(stop.x(), stop.y()) < 30,
                      "A stop bar belongs near the conflict edge rather than at the tile border");
            }
        }
    }

    @Test
    void signalControlledTsStopAllApproachesWithoutChangingTheirClearTurningArea() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) != 3) { continue; }
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            var priorityPaint = road.markings(BoardScenes.CENTER, exits);
            for (var control : java.util.List.of(HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY)) {
                road.control(control);
                var stops = road.stopApproaches(exits);
                assertEquals(3, stops.size(), "Signals control each incoming approach");
                var paint = road.markings(BoardScenes.CENTER, exits);
                paint.exclusiveOr(priorityPaint);
                assertTrue(paint.isEmpty(), "Stop priority never changes the divider's clear crossing area");
                for (var stop : stops) {
                    assertFalse(priorityPaint.contains(stop.x() - stop.nx(), stop.y() - stop.ny()),
                          "Every incoming divider ends outside the conflict, including the priority T arms");
                }
            }
        }
    }

    @Test
    void fiveAndSixArmRailHubsStopIncomingRoadLanesWithoutPaintingTheMedian() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) < 5) { continue; }
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits)
                  .corridor(new RailRoute.Corridor(exits, 0, 0)).control(HexAppearance.TrafficControl.GANTRY);
            assertEquals(Integer.bitCount(exits), road.stopApproaches(exits).size(),
                  "A rail corridor replaces circulation with a controlled solid hub");
            var stops = road.stopLines(exits);
            assertFalse(stops.isEmpty());
            stops.intersect(road.medianBand());
            assertTrue(stops.isEmpty(), "Stop bars keep the central railway clear");
            assertFalse(road.markings(BoardScenes.CENTER, exits).intersects(-6, -6, 12, 12));
        }
    }

    @Test
    void crossedRoadsRoundTheirTightCornersWithoutAddingUnconnectedEntrances() {
        for (int axis = 0; axis < 3; axis++) {
            int first = axis, second = (axis + 1) % 6;
            int exits = (1 << first) | (1 << second) | (1 << ((first + 3) % 6)) | (1 << ((second + 3) % 6));
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            float[] a = RailRoute.border(first), b = RailRoute.border(second);
            var corner = new Vector2(a[0], a[1]).nor().add(new Vector2(b[0], b[1]).nor()).nor().scl(17);
            assertTrue(road.footprint(0).contains(corner.x, corner.y), "The tight X corner has a rounded turning apron");
            assertTrue(road.distance(corner.x, corner.y) < 0, "Scenery clearance follows that same apron");
            for (int direction = 0; direction < 6; direction++) {
                float[] border = RailRoute.border(direction);
                assertEquals((exits & (1 << direction)) != 0, road.footprint(0).contains(border[0], border[1]));
            }
        }
    }

    @Test
    void unmarkedMaterialJoinsDoNotAcquireStopPaintAndOnlyPavedRoadsRenderIt() {
        Coords at = BoardScenes.CENTER;
        var road = BoardRoad.layout(at, 27, BoardRoad.Kind.PAVED,
              direction -> direction == 1 ? BoardRoad.Kind.ALLEY : direction == 4 ? BoardRoad.Kind.DIRT : BoardRoad.Kind.PAVED);
        assertEquals(java.util.List.of(0, 3), road.stopApproaches(27).stream().map(BoardRoad.StopApproach::direction).toList());
        var sample = road.stopApproaches(27).getFirst();
        float across = (sample.inner() + sample.outer()) / 2;
        float x = sample.x() - sample.ny() * across, y = sample.y() + sample.nx() * across;
        for (var kind : BoardRoad.Kind.values()) {
            if (kind == BoardRoad.Kind.NONE) { continue; }
            var tile = BoardScenes.tile(at, kind, 27, 0, BoardScene.Surface.GRASS);
            var patches = GpuRoads.patches(tile, BoardRoad.layout(at, 27, kind, direction -> kind));
            assertEquals(kind == BoardRoad.Kind.PAVED,
                  patches.stream().anyMatch(patch -> patch.texture().equals("concrete-ground") && patch.shape().contains(x, y)),
                  "Only painted roads get stop-line material");
        }
    }

    @Test
    void stopBarsFollowWidenedOrOffsetRailCorridorsAndLeaveTheirBallastClear() {
        for (var corridor : java.util.List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var road = BoardRoad.layout(BoardScenes.CENTER, 27, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
                  .corridor(corridor);
            Area paint = road.stopLines(27);
            assertEquals(4, road.stopApproaches(27).size());
            for (var approach : road.stopApproaches(27)) {
                float across = (approach.inner() + approach.outer()) / 2;
                assertTrue(paint.contains(approach.x() - approach.ny() * across,
                      approach.y() + approach.nx() * across), "The stop bar follows the fitted road lane");
            }
            paint.intersect(road.medianBand());
            assertTrue(paint.isEmpty(), "Road markings leave the railway's ballast clear");
        }
    }
}
