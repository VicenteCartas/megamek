/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

/** The road's width, paint and rail inset all follow its shared corridor footprint. */
class BoardRoadRailTest {
    private static final Coords AT = new Coords(2, 2);

    private static BoardRoad road(int exits, RailRoute.Corridor corridor) {
        return BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).corridor(corridor);
    }

    @Test
    void middleHasTwoFullLanesAndBallastWithContinuousBorderWidths() {
        var road = road(9, new RailRoute.Corridor(9, 0, 0));
        float y = RailRoute.border(0)[1];
        float lane = (BoardRoad.MIDDLE_HALF + BoardRoad.MEDIAN_HALF) / 2;
        for (float at : new float[] { -y, -20, 0, 20, y }) {
            assertEquals(0, road.distance(BoardRoad.MIDDLE_HALF, at), .02f);
            assertEquals(0, road.distance(-BoardRoad.MIDDLE_HALF, at), .02f);
            assertFalse(road.street(0, at), "The central track retains ballast");
            assertTrue(road.street(lane, at), "Each side remains a driveable lane");
        }
        assertTrue(road.medianLines().contains(BoardRoad.MEDIAN_HALF + .4f, 20));
        assertFalse(road.markings(AT, 9).contains(0, 20), "No dashes under the central track");
        for (float tyre : new float[] { lane - 3, lane + 3 }) {
            assertEquals(BoardRoad.WHEEL_OFFSET / (BoardRoad.Kind.PAVED.halfWidth / 2), road.wearLane(tyre, 20), .001f,
                  "Each lane's wheel paths keep their axle spacing beside the ballast");
        }
        assertEquals(0, road.wearLane(lane, 20), .001f, "The lane centre stays between the tyres");
        assertEquals(0, BoardRoad.clearance(AT, 9, new RailRoute.Corridor(9, 0, 0)).distance(BoardRoad.CORRIDOR_HALF, y), .02f);
    }

    @Test
    void sideRoadCrossesTheTrackWithoutPaintOrBallastBlockingIt() {
        var corridor = new RailRoute.Corridor(9, 0, 0);
        var road = road(11, corridor);
        assertTrue(road.street(0, 0), "The road's side arm crosses the median at its hub");
        assertFalse(road.medianLines().contains(BoardRoad.MEDIAN_HALF + .4f, 0));
        float[] side = RailRoute.border(1);
        float length = (float) Math.hypot(side[0], side[1]);
        assertEquals(0, road.distance(side[0] + side[1] / length * 15, side[1] - side[0] / length * 15), .05f,
              "A side road stays ordinary width");
        assertFalse(road.street(0, 25), "Away from the crossing the ballast resumes");
    }

    @Test
    void besideKeepsRoadDashesAndMovesBothCoursesToOppositeSides() {
        var corridor = new RailRoute.Corridor(9, 9, 9);
        var road = road(9, corridor);
        float y = RailRoute.border(0)[1];
        for (float at : new float[] { -y, 0, y }) {
            assertEquals(0, road.distance(RailRoute.BESIDE_ROAD - 15, at), .02f);
            assertEquals(0, road.distance(RailRoute.BESIDE_ROAD + 15, at), .02f);
        }
        assertFalse(road.markings(AT, 9).isEmpty());
        assertTrue(road.medianLines().isEmpty());
        var line = RailRoute.lines(9, RailRoute.pieces(9), corridor).getFirst();
        float rail = line.at(.5f)[0];
        assertEquals(15.75f, rail, .001f);
        assertEquals(1.5f, rail - BoardRoad.MEDIAN_HALF - (RailRoute.BESIDE_ROAD + BoardRoad.Kind.PAVED.halfWidth), .001f,
              "The wider lane retains the gap between pavement and ballast");
        float car = RailRoute.BESIDE_ROAD + BoardRoad.Kind.PAVED.halfWidth / 2;
        assertTrue(rail - 8.9f * RailRoute.CONVENTIONAL_SCALE / 2 - (car + 6f / 2) >= 1.68f,
              "A scale-1 car in the near lane and a train leave at least 0.6m between them");
        assertFalse(road.street(line.at(.5f)[0], 0), "The beside track is on ballast");
    }

    @Test
    void aWiderCorridorMovesTheTrackWithoutScalingItsBallastOrGauge() {
        for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var line = RailRoute.lines(9, RailRoute.pieces(9), corridor).getFirst();
            var path = List.of(0f, .5f, 1f).stream().map(t -> {
                float[] p = line.at(t);
                return new Vector3(p[0], p[1], 0);
            }).toList();
            var layout = new BoardRail.Layout(RailRoute.Kind.RAIL, List.of(), List.of(path), List.of(List.of(false, false, false)));
            var mesh = BoardRail.model(layout).meshes.first();
            float center = line.at(.5f)[0], ballast = 0, sleeper = 0, head = 0;
            for (int i = 0; i < mesh.vertices.length; i += 7) {
                float x = Math.abs(mesh.vertices[i] - center), z = mesh.vertices[i + 2];
                ballast = Math.max(ballast, x);
                if (Math.abs(z - .9f * 1.3f) < .001f) { sleeper = Math.max(sleeper, x); }
                if (Math.abs(z - RailRoute.RAIL_TOP) < .001f) { head = Math.max(head, x); }
            }
            assertEquals(5.6f * 1.3f, ballast, .001f);
            assertEquals(3.8f * 1.3f, sleeper, .001f);
            assertEquals(2.35f * 1.3f, head, .001f, "Conventional gauge and head width grow 30% in either corridor");
        }
    }

    @Test
    void medianFollowsTheAuthoredJunctionRatherThanInventingACrossing() {
        int tracks = 1 << RailRoute.piece(0, 4) | 1 << RailRoute.piece(1, 3);
        assertTrue(RailRoute.valid(27, tracks));
        assertTrue(road(27, new RailRoute.Corridor(27, 0, 0)).medianBand().contains(0, 0));
        assertFalse(road(27, new RailRoute.Corridor(27, 0, 0, tracks)).medianBand().contains(0, 0));
    }

    @Test
    void ordinaryRoadIsUnchangedAndASingleSharedArmDoesNotWidenTheOtherBorder() {
        var original = road(9, RailRoute.Corridor.NONE);
        assertEquals(0, original.distance(15, 20), .001f);
        assertTrue(original.street(0, 0));
        assertTrue(original.medianLines().isEmpty());
        var end = road(9, new RailRoute.Corridor(1, 0, 0));
        assertEquals(0, end.distance(BoardRoad.MIDDLE_HALF, RailRoute.border(0)[1]), .02f);
        assertEquals(0, end.distance(15, RailRoute.border(3)[1]), .02f);
    }

    @Test
    void capturedFixtureKeepsFullCenteredLanesAndAlignedRailTermini() {
        var fixture = RoadJunctionFixture.create();
        for (var panel : List.of(RoadJunctionFixture.Panel.CENTERED, RoadJunctionFixture.Panel.BESIDE)) {
            for (int mask : new int[] { 9, 10, 11, 17, 18, 19 }) {
                var scene = crop(fixture, RoadJunctionFixture.center(panel, mask));
                var center = scene.tile(AT);
                assertEquals(mask, center.corridor().sides(), panel + " mask " + mask);
                var road = BoardRoad.of(scene, center);
                for (int d = 0; d < 6; d++) {
                    if ((mask & 1 << d) == 0) { continue; }
                    float[] edge = RailRoute.border(d);
                    float length = (float) Math.hypot(edge[0], edge[1]);
                    if (panel == RoadJunctionFixture.Panel.CENTERED) {
                        for (float side : new float[] { -1, 1 }) {
                            float width = side * (RailRoute.BALLAST_HALF_WIDTH + HexTile.ROAD_HALF_WIDTH);
                            assertTrue(road.distance(edge[0] + edge[1] / length * width,
                                  edge[1] - edge[0] / length * width) <= .05f,
                                  "The captured centered junction adds the rail reserve to two full lanes: " + mask + "/" + d);
                        }
                    }
                    var terminal = scene.neighbor(center, d);
                    var terminalRoad = BoardRoad.of(scene, terminal);
                    var feature = BoardRail.route(terminal);
                    var line = RailRoute.lines(terminal.railExits(), 0, terminal.corridor()).getFirst();
                    var path = BoardRail.paths(scene, terminal, feature).getFirst();
                    float[] end = line.at(0);
                    assertEquals(0, terminalRoad.endDistance(end[0], end[1]), .001f,
                          "Road and rendered rail share the terminal plane: " + panel + "/" + mask + "/" + d);
                    assertEquals(end[0], path.getFirst().x, .001f);
                    assertEquals(end[1], path.getFirst().y, .001f);
                    if (panel == RoadJunctionFixture.Panel.CENTERED) {
                        // In the terminal's frame the outgoing direction is opposite this fixture arm.
                        float x = edge[0] * .4f, y = edge[1] * .4f;
                        assertFalse(terminalRoad.street(x, y), "The central ballast continues beyond the hex centre");
                        for (float side : new float[] { -1, 1 }) {
                            for (float across : new float[] { RailRoute.BALLAST_HALF_WIDTH + .6f,
                                  RailRoute.BALLAST_HALF_WIDTH + HexTile.ROAD_HALF_WIDTH - .2f }) {
                                assertTrue(terminalRoad.street(x + edge[1] / length * across * side,
                                      y - edge[0] / length * across * side), "Both terminal lanes retain their width");
                            }
                        }
                    }
                }
            }
        }
    }

    private static BoardScene crop(Board fixture, Coords center) {
        var pixels = new BoardScene.PixelPool();
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                var at = new Coords(x, y);
                var original = new Coords(center.getX() + x - 2, center.getY() + y - 2);
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                var hex = fixture.getHex(original);
                tiles.add(BoardScene.captureTile(hex == null ? new Hex(0) : hex, artwork, null, pixels,
                      neighbor -> fixture.getHex(new Coords(center.getX() + neighbor.getX() - 2,
                            center.getY() + neighbor.getY() - 2))));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void roadRailTerminalsStayAlignedEvenBesideJunctionsWithoutACorridor() {
        var fixture = RoadJunctionFixture.create();
        for (var panel : List.of(RoadJunctionFixture.Panel.CENTERED, RoadJunctionFixture.Panel.BESIDE)) {
            for (int mask : new int[] { 3, 6, 12, 24, 33, 48, 31, 63 }) {
                var scene = crop(fixture, RoadJunctionFixture.center(panel, mask));
                var center = scene.tile(AT);
                assertEquals(0, center.corridor().sides(), "These junctions cannot carry a shared corridor");
                for (int d = 0; d < 6; d++) {
                    if ((mask & 1 << d) == 0) { continue; }
                    var terminal = scene.neighbor(center, d);
                    var road = BoardRoad.of(scene, terminal);
                    var feature = BoardRail.route(terminal);
                    var path = BoardRail.paths(scene, terminal, feature).getFirst();
                    var end = path.getFirst();
                    assertEquals(0, road.endDistance(end.x, end.y), .001f,
                          "The adjoining junction must not shorten its terminal railway: " + panel + "/" + mask + "/" + d);
                    var snapped = RailRoute.snap(fixture.getHex(RoadJunctionFixture.center(panel, mask).translated(d)),
                          feature.decoration(), end.x, end.y, 0, terminal.corridor());
                    assertEquals(end.x, snapped.x(), .001f);
                    assertEquals(end.y, snapped.y(), .001f);
                    var stop = RailRoute.stops(terminal.railExits(), terminal.corridor()).getFirst();
                    assertEquals(RailRoute.STOP_DEPTH, Math.hypot(stop.x() - end.x, stop.y() - end.y), .001f);
                }
            }
        }
    }

    @Test
    void shippedSlopedBesideRailsMeetAtTheSameCarrierHeight() throws Exception {
        var view = GpuRoadRailSmokeTest.load("unofficial/VictorMorson/48x48 The Trainjob.board", new Coords(37, 29), true);
        var scene = view.scene();
        // The 3529 -> 3629 grade immediately before the bridge, within the even-column 12x12 crop.
        var a = scene.tile(2, 4);
        var b = scene.tile(3, 4);
        var first = BoardRail.layout(scene, a, BoardRail.route(a), new BoardSurface(scene, a), at -> null);
        var second = BoardRail.layout(scene, b, BoardRail.route(b), new BoardSurface(scene, b), at -> null);
        float distance = Float.POSITIVE_INFINITY;
        Vector3 from = null, to = null;
        for (var pathA : first.paths()) {
            for (var pathB : second.paths()) {
                for (var pointA : java.util.List.of(pathA.getFirst(), pathA.getLast())) {
                    for (var pointB : java.util.List.of(pathB.getFirst(), pathB.getLast())) {
                        var wa = new Vector3(pointA).scl(BoardGeometry.hexScale()).add(BoardGeometry.center(a.coords(), a.elevation()));
                        var wb = new Vector3(pointB).scl(BoardGeometry.hexScale()).add(BoardGeometry.center(b.coords(), b.elevation()));
                        float separation = (float) Math.hypot(wa.x - wb.x, wa.y - wb.y);
                        if (separation < distance) { distance = separation; from = wa; to = wb; }
                    }
                }
            }
        }
        assertEquals(0, distance, .001f, "The offset rail meets its neighbour in plan");
        assertEquals(from.z, to.z, .001f, "The offset rail meets its neighbour vertically: " + from + " / " + to);
    }

    private static final float REACH = 24;

    private static BoardRoad extend(BoardRoad road, int direction) {
        var lengths = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        lengths.set(direction, REACH);
        return road.extended(lengths);
    }

    @Test
    void middleBallastAndBothMedianLinesContinueThroughEveryRampDirection() {
        float medianLine = BoardRoad.MEDIAN_HALF + .4f;
        for (int d = 0; d < 6; d++) {
            int exits = 1 << d | 1 << (d + 3) % 6;
            var road = extend(road(exits, new RailRoute.Corridor(exits, 0, 0)), d);
            var lines = road.medianLines();
            float[] edge = RailRoute.border(d);
            float length = (float) Math.hypot(edge[0], edge[1]);
            float nx = edge[0] / length, ny = edge[1] / length;
            for (float along : new float[] { 4, 12, 22 }) {
                float x = edge[0] + nx * along, y = edge[1] + ny * along;
                assertTrue(road.medianBand().contains(x, y), "Ballast continues beyond border " + d);
                assertFalse(road.street(x, y), "The ramp's center remains reserved for rail");
                for (int side : new int[] { -1, 1 }) {
                    assertTrue(lines.contains(x + side * ny * medianLine, y - side * nx * medianLine),
                          "Both median lines continue on ramp " + d + " at " + along);
                }
            }
            assertFalse(lines.contains(edge[0] + nx * (REACH + 3) + ny * medianLine,
                  edge[1] + ny * (REACH + 3) - nx * medianLine), "Paint ends with the carrier");
        }
    }

    @Test
    void besideRoadKeepsItsOffsetAndFullWidthAcrossEveryRampDirection() {
        for (int d = 0; d < 6; d++) {
            int exits = 1 << d | 1 << (d + 3) % 6;
            for (int right : new int[] { 0, exits }) {
                var corridor = new RailRoute.Corridor(exits, exits, right);
                var road = extend(road(exits, corridor), d);
                float[] edge = RailRoute.border(d);
                float length = (float) Math.hypot(edge[0], edge[1]);
                float nx = edge[0] / length, ny = edge[1] / length;
                float[] shift = RailRoute.borderOffset(d, RailRoute.BESIDE_ROAD * corridor.lateral(d));
                for (float along : new float[] { 4, 12, 22 }) {
                    float x = edge[0] + nx * along + shift[0], y = edge[1] + ny * along + shift[1];
                    assertEquals(-BoardRoad.Kind.PAVED.halfWidth, road.distance(x, y), .02f,
                          "The road follows its offset all the way down ramp " + d);
                    for (int side : new int[] { -1, 1 }) {
                        assertTrue(road.footprint(0).contains(x + side * ny * 7, y - side * nx * 7),
                              "Both lanes remain paved at " + along);
                    }
                }
                assertFalse(road.markings(AT, exits).isEmpty(), "The offset road retains its center dashes");
            }
        }
    }

    @Test
    void structuralCorridorIsOneCenteredWidthForEitherRailPlacement() {
        var base = BoardRoad.layout(AT, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED);
        for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var slab = base.bridgeCorridor(corridor);
            for (float y : new float[] { -30, 0, 30 }) {
                assertEquals(0, slab.distance(BoardRoad.CORRIDOR_HALF, y), .01f);
                assertEquals(0, slab.distance(-BoardRoad.CORRIDOR_HALF, y), .01f);
                assertTrue(slab.footprint(0).contains(1.8f, y), "No structural slot between beside road and rail");
            }
        }
    }
}
