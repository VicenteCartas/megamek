/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadRoundaboutTest {
    @Test
    void threeCellLoopsShareOneTwoLaneRingAndKeepTheirExternalConnections() {
        for (int column : new int[] { 3, 4 }) {
            for (int direction = 0; direction < 6; direction++) {
                Coords at = new Coords(column, 4);
                var members = List.of(at, at.translated(direction), at.translated((direction + 1) % 6));
                var board = triangle(members);
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      board.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                      board.containsKey(c) ? board.get(c).getTerrain(Terrains.ROAD).getExits() : 0,
                      0, BoardScene.Surface.GRASS));
                float centerX = (float) members.stream().mapToDouble(BoardGeometry::centerX).average().orElseThrow();
                float centerY = (float) members.stream().mapToDouble(BoardGeometry::centerY).average().orElseThrow();
                Map<Coords, BoardRoad> rendered = new HashMap<>();
                for (Coords member : members) { rendered.put(member, BoardRoad.of(scene, scene.tile(member))); }
                for (Coords member : members) {
                    int exits = board.get(member).getTerrain(Terrains.ROAD).getExits();
                    var road = rendered.get(member);
                    var captured = BoardRoad.clearance(member, board.get(member), board::get);
                    Area footprint = road.footprint(0);
                    assertEquals(rendered.get(at).owner(), road.owner(),
                          "A neighboring paint collector installs the shared ring only once");
                    float cx = (centerX - BoardGeometry.centerX(member)) / BoardGeometry.hexScale();
                    float cy = (centerY - BoardGeometry.centerY(member)) / BoardGeometry.hexScale();
                    assertTrue(road.stopApproaches(exits).isEmpty(), "A shared roundabout replaces the three stop-controlled hubs");
                    assertFalse(road.footprint(BoardRoad.SHOULDER).contains(cx, cy), "The loop keeps one central island");
                    for (int angle = 0; angle < 360; angle += 10) {
                        double theta = Math.toRadians(angle);
                        for (float radius : new float[] { 18, 24.5f, 32, 39.5f, 46 }) {
                            float x = cx + radius * (float) Math.cos(theta), y = cy + radius * (float) Math.sin(theta);
                            assertTrue(footprint.contains(x, y), "Both 15-unit circulating lanes are continuous");
                            assertEquals(road.distance(x, y), captured.distance(x, y), .002f,
                                  "Capture clears the same complete roundabout as rendering");
                        }
                    }
                    for (Coords other : members) {
                        var adjacent = rendered.get(other);
                        float dx = (BoardGeometry.centerX(member) - BoardGeometry.centerX(other)) / BoardGeometry.hexScale();
                        float dy = (BoardGeometry.centerY(member) - BoardGeometry.centerY(other)) / BoardGeometry.hexScale();
                        for (float x = -45; x <= 45; x += 9) {
                            for (float y = -45; y <= 45; y += 9) {
                                assertEquals(road.distance(x, y), adjacent.distance(x + dx, y + dy), .002f,
                                      "Every member derives the same world-space circle and entry curves");
                            }
                        }
                    }
                    int outside = exits;
                    for (Coords other : members) {
                        if (!member.equals(other)) { outside &= ~(1 << member.direction(other)); }
                    }
                    float[] gate = RailRoute.border(Integer.numberOfTrailingZeros(outside));
                    float length = (float) Math.hypot(gate[0], gate[1]);
                    for (float across : new float[] { -14, 0, 14 }) {
                        float x = gate[0] - gate[1] / length * across, y = gate[1] + gate[0] / length * across;
                        assertTrue(footprint.contains(x, y), "The authored external road retains its full-width mouth");
                    }
                    assertClearLaneCentres(road.flowMarkings(), cx, cy, new float[] { 24.5f, 39.5f });
                    assertClearLaneCentres(road.markings(member, exits), cx, cy, new float[] { 24.5f, 39.5f });
                }
            }
        }
    }

    @Test
    void sharedRoundaboutMouthsBlendSmoothlyIntoBothCirculatingLanesInEveryOrientation() {
        for (int direction = 0; direction < 6; direction++) {
            Coords at = BoardScenes.CENTER;
            var members = List.of(at, at.translated(direction), at.translated((direction + 1) % 6));
            var board = triangle(members);
            var road = BoardRoad.clearance(at, board.get(at), board::get);
            double cx = (members.stream().mapToDouble(BoardGeometry::centerX).average().orElseThrow()
                  - BoardGeometry.centerX(at)) / BoardGeometry.hexScale();
            double cy = (members.stream().mapToDouble(BoardGeometry::centerY).average().orElseThrow()
                  - BoardGeometry.centerY(at)) / BoardGeometry.hexScale();
            assertSmoothOuterEdge(road.footprint(0), cx, cy);
        }
    }

    @Test
    void everyRoundaboutEntryHasAStraightGiveWayLineAndTriangleFacingApproachingTraffic() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) >= 5) {
                var symbols = assertGiveWayMarkings(BoardRoad.clearance(BoardScenes.CENTER, exits), 0, 0, 25.5,
                      Integer.bitCount(exits));
                for (int direction = 0; direction < 6; direction++) {
                    if ((exits & 1 << direction) != 0) { assertRightHandEntry(symbols, 0, 0, direction); }
                }
            }
        }
        for (int direction = 0; direction < 6; direction++) {
            Coords at = BoardScenes.CENTER;
            var members = List.of(at, at.translated(direction), at.translated((direction + 1) % 6));
            double cx = (members.stream().mapToDouble(BoardGeometry::centerX).average().orElseThrow()
                  - BoardGeometry.centerX(at)) / BoardGeometry.hexScale();
            double cy = (members.stream().mapToDouble(BoardGeometry::centerY).average().orElseThrow()
                  - BoardGeometry.centerY(at)) / BoardGeometry.hexScale();
            for (int choices = 0; choices < 8; choices++) {
                var board = triangle(members, choices);
                var road = BoardRoad.clearance(at, board.get(at), board::get);
                var symbols = assertGiveWayMarkings(road, cx, cy, 47, 3);
                for (Coords member : members) {
                    int external = board.get(member).getTerrain(Terrains.ROAD).getExits();
                    for (Coords other : members) {
                        if (!member.equals(other)) { external &= ~(1 << member.direction(other)); }
                    }
                    assertRightHandEntry(symbols,
                          (BoardGeometry.centerX(member) - BoardGeometry.centerX(at)) / BoardGeometry.hexScale(),
                          (BoardGeometry.centerY(member) - BoardGeometry.centerY(at)) / BoardGeometry.hexScale(),
                          Integer.numberOfTrailingZeros(external));
                }
            }
        }
    }

    @Test
    void singleHexCirculationHasOneCounterclockwiseLaneAndIndependentYJunctionsStaySolid() {
        for (int exits : new int[] { 31, 63 }) {
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            Area paint = road.flowMarkings();
            assertClearLaneCentres(paint, 0, 0, new float[] { 18 });
            assertFalse(paint.contains(25.5, 0), "A single circulating lane has no outer-lane divider");
            for (int direction = 0; direction < 6; direction++) {
                if ((exits & (1 << direction)) == 0) { continue; }
                float[] border = RailRoute.border(direction);
                var normal = new Vector2(border[0], border[1]).nor();
                boolean incoming = false;
                for (float along = 28; along <= 35; along += .25f) {
                    for (float across = 2; across <= 13; across += .25f) {
                        incoming |= paint.contains(normal.x * along - normal.y * across,
                              normal.y * along + normal.x * across);
                        assertFalse(paint.contains(normal.x * along + normal.y * across,
                              normal.y * along - normal.x * across), "Give-way paint leaves the outgoing lane open");
                    }
                }
                assertTrue(incoming, "Each incoming approach has give-way markings before the circulating lane");
            }
        }
        for (int exits : new int[] { 21, 42 }) {
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            assertTrue(road.footprint(0).contains(0, 0));
            assertFalse(road.stopApproaches(exits).isEmpty());
            assertTrue(road.flowMarkings().isEmpty());
        }
    }

    @Test
    void sharedRoundaboutCopiesKeepTheSamePinnedConcreteApproach() {
        for (int orientation = 0; orientation < 6; orientation++) {
            Coords at = BoardScenes.CENTER;
            var members = List.of(at, at.translated(orientation), at.translated((orientation + 1) % 6));
            var board = triangle(members);
            Coords member = members.getLast();
            int exits = board.get(member).getTerrain(Terrains.ROAD).getExits();
            for (Coords other : members) { if (!member.equals(other)) { exits &= ~(1 << member.direction(other)); } }
            int direction = Integer.numberOfTrailingZeros(exits);
            Coords receiver = member.translated(direction);
            var paving = new HashSet<Coords>();
            for (int step = -2; step <= 2; step++) {
                paving.add(receiver.translated((direction + (step < 0 ? 4 : 1)) % 6, Math.abs(step)));
            }
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  board.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  board.containsKey(c) ? board.get(c).getTerrain(Terrains.ROAD).getExits() : 0, 0,
                  paving.contains(c) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND));
            var own = BoardRoad.of(scene, scene.tile(member));
            assertTrue(BoardConcrete.of(scene).corners(receiver).stream()
                  .allMatch(shift -> shift.x() == 0 && shift.y() == 0),
                  "The connected road pins its concrete receiver, so this mouth needs no extension");
            float[] gate = RailRoute.border(direction);
            float length = (float) Math.hypot(gate[0], gate[1]);
            for (Coords other : members) {
                var copy = BoardRoad.of(scene, scene.tile(other));
                float dx = (BoardGeometry.centerX(member) - BoardGeometry.centerX(other)) / BoardGeometry.hexScale();
                float dy = (BoardGeometry.centerY(member) - BoardGeometry.centerY(other)) / BoardGeometry.hexScale();
                for (float along = 28; along <= 65; along += 2) {
                    float x = gate[0] / length * along, y = gate[1] / length * along;
                    assertEquals(own.distance(x, y), copy.distance(x + dx, y + dy), .002f,
                          "Deduplicated paint keeps even another member's fitted concrete mouth");
                }
            }
        }
    }

    @Test
    void railsGradesBridgesAndLooseSurfacesRetainTheirAuthoredJunctions() {
        Coords at = BoardScenes.CENTER;
        var members = List.of(at, at.translated(0), at.translated(1));
        for (String terrain : new String[] { "rail:1:9", "bridge:1:9;bridge_cf:40;bridge_elev:2", "water:1", "road:3:0" }) {
            var board = triangle(members);
            Hex changed = board.get(members.getLast());
            if (terrain.startsWith("road")) {
                changed.addTerrain(new Terrain(Terrains.ROAD, 3, true, changed.getTerrain(Terrains.ROAD).getExits()));
            } else {
                Hex addition = new Hex(0, terrain, "");
                for (int type : addition.getTerrainTypes()) { changed.addTerrain(addition.getTerrain(type)); }
            }
            assertTrue(BoardRoad.clearance(at, board.get(at), board::get).flowMarkings().isEmpty(), terrain);
        }
        var board = triangle(members);
        board.get(members.getLast()).setLevel(1);
        assertTrue(BoardRoad.clearance(at, board.get(at), board::get).flowMarkings().isEmpty());
    }

    @Test
    void railsThroughFiveAndSixWayRoadsKeepSolidHubsAndTheirAuthoredCourses() {
        for (int exits : new int[] { 31, 63 }) {
            Hex hex = new Hex(0, "road:1:" + exits + ";rail:1:9", "");
            var road = BoardRoad.clearance(BoardScenes.CENTER, hex, at -> null);
            assertTrue(road.footprint(0).contains(0, 0), "Straight rails pass through a solid road junction");
            assertTrue(road.flowMarkings().isEmpty(), "Railway hubs never suggest circular traffic over the tracks");
            assertFalse(road.markings(BoardScenes.CENTER, exits).contains(0, 0));
            assertEquals(9, hex.getTerrain(Terrains.RAIL).getExits(), "Visual layout does not alter the authored railway");
        }
    }

    /** Inspect the finished paint, independently of the placement algorithm and its private approach frames. */
    private static List<Point2D.Double> assertGiveWayMarkings(BoardRoad road, double cx, double cy,
          double radius, int approaches) {
        Area paint = road.flowMarkings();
        Area offRoad = new Area(paint);
        offRoad.subtract(road.footprint(0));
        assertTrue(offRoad.isEmpty(), "All entry paint stays on the existing road");
        paint.subtract(new Area(new Ellipse2D.Double(cx - radius, cy - radius, 2 * radius, 2 * radius)));
        var contours = new ArrayList<List<Point2D.Double>>();
        var contour = new ArrayList<Point2D.Double>();
        double[] coordinates = new double[6];
        for (var iterator = paint.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO -> {
                    contour = new ArrayList<>();
                    contour.add(new Point2D.Double(coordinates[0], coordinates[1]));
                }
                case PathIterator.SEG_LINETO -> contour.add(new Point2D.Double(coordinates[0], coordinates[1]));
                case PathIterator.SEG_CLOSE -> {
                    if (contour.getLast().distance(contour.getFirst()) < .001) { contour.removeLast(); }
                    // AWT retains collinear stroke endpoints along mitered triangle edges.
                    for (int i = contour.size() - 1; i >= 0; i--) {
                        var before = contour.get((i + contour.size() - 1) % contour.size());
                        var point = contour.get(i);
                        var after = contour.get((i + 1) % contour.size());
                        if (java.awt.geom.Line2D.ptSegDist(before.x, before.y, after.x, after.y, point.x, point.y)
                              < .001) { contour.remove(i); }
                    }
                    if (contour.size() < 3) { continue; } // Discard zero-area seams retained by AWT's union.
                    assertTrue(contour.size() == 3 || contour.size() == 4,
                          "Entry paint consists only of triangular outlines and straight rectangular dashes: " + contour);
                    contours.add(contour);
                }
                default -> throw new AssertionError("A give-way line must be straight, not an arc of the ring");
            }
        }
        var triangles = contours.stream().filter(points -> points.size() == 3).toList();
        // Each hollow symbol has an outer triangle and an inner hole; inspect the outer boundary.
        var symbols = triangles.stream().filter(points -> triangles.stream()
              .noneMatch(other -> other != points && polygon(other).contains(points.getFirst()))).toList();
        assertEquals(approaches, symbols.size(), "Every incoming approach retains one complete give-way symbol");
        var dashes = contours.stream().filter(points -> points.size() == 4).toList();
        int checked = 0;
        for (var symbol : symbols) {
            int base = 0;
            for (int i = 1; i < 3; i++) {
                if (symbol.get(i).distance(symbol.get((i + 1) % 3))
                      < symbol.get(base).distance(symbol.get((base + 1) % 3))) { base = i; }
            }
            var a = symbol.get(base);
            var b = symbol.get((base + 1) % 3);
            var tip = symbol.get((base + 2) % 3);
            double x = (a.x + b.x) / 2, y = (a.y + b.y) / 2;
            double length = tip.distance(x, y), nx = (tip.x - x) / length, ny = (tip.y - y) / length;
            assertTrue(nx * (x - cx) + ny * (y - cy) > 0,
                  "The give-way triangle points away from the ring, towards approaching traffic");
            var lane = dashes.stream().filter(points -> symbols.stream()
                  .allMatch(other -> center(points).distance(center(symbol))
                        <= center(points).distance(center(other)))).toList();
            assertTrue(lane.size() >= 4, "The straight dashed line spans the incoming lane");
            double plane = Double.NaN, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (var dash : lane) {
                var middle = center(dash);
                double along = (middle.x - x) * nx + (middle.y - y) * ny;
                if (Double.isNaN(plane)) { plane = along; }
                assertEquals(plane, along, .002, "All entry dashes lie on one straight transverse line");
                assertTrue(along < -1, "The line precedes the triangle in the direction of approaching traffic");
                for (var point : dash) {
                    assertTrue(point.distance(cx, cy) > radius, "The whole line stays outside the circulating lane");
                    double across = -(point.x - x) * ny + (point.y - y) * nx;
                    min = Math.min(min, across);
                    max = Math.max(max, across);
                }
                checked++;
            }
            assertTrue(max - min > 10, "Give-way paint covers the width of the incoming lane");
        }
        assertEquals(dashes.size(), checked, "Every dash belongs to exactly one approach");
        return symbols.stream().map(BoardRoadRoundaboutTest::center).toList();
    }

    private static void assertRightHandEntry(List<Point2D.Double> symbols, double x, double y, int direction) {
        float[] gate = RailRoute.border(direction);
        var closest = symbols.stream().min(java.util.Comparator.comparingDouble(
              point -> point.distance(x + gate[0], y + gate[1]))).orElseThrow();
        // Board Y is north-positive. For a driver heading inward, the right lane is left of the outward axis.
        assertTrue(-(closest.x - x) * gate[1] + (closest.y - y) * gate[0] > 0,
              "Give-way symbols belong to right-hand incoming traffic, never to its outgoing lane");
    }

    private static Point2D.Double center(List<Point2D.Double> points) {
        return new Point2D.Double(points.stream().mapToDouble(point -> point.x).average().orElseThrow(),
              points.stream().mapToDouble(point -> point.y).average().orElseThrow());
    }

    private static Path2D.Double polygon(List<Point2D.Double> points) {
        var path = new Path2D.Double();
        path.moveTo(points.getFirst().x, points.getFirst().y);
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
        path.closePath();
        return path;
    }

    private static void assertClearLaneCentres(Area paint, float cx, float cy, float[] radii) {
        for (float radius : radii) {
            for (int step = 0; step < 360; step++) {
                double angle = Math.toRadians(step);
                float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
                float x = cx + radius * nx, y = cy + radius * ny;
                assertFalse(paint.contains(x, y), "Circulating lane centres have no repeated arrows or symbols");
            }
        }
    }

    /** Sample the actual curb uniformly, so short tessellation edges cannot conceal a sharp ring/approach join. */
    private static void assertSmoothOuterEdge(Area footprint, double cx, double cy) {
        var contour = new ArrayList<Point2D.Double>();
        double[] point = new double[6];
        int checked = 0;
        for (var iterator = footprint.getPathIterator(null, .01); !iterator.isDone(); iterator.next()) {
            switch (iterator.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> {
                    contour.clear();
                    contour.add(new Point2D.Double(point[0], point[1]));
                }
                case PathIterator.SEG_LINETO -> contour.add(new Point2D.Double(point[0], point[1]));
                case PathIterator.SEG_CLOSE -> {
                    contour.add(contour.getFirst());
                    double length = 0;
                    for (int i = 1; i < contour.size(); i++) { length += contour.get(i - 1).distance(contour.get(i)); }
                    int count = (int) Math.ceil(length / .5);
                    var samples = new ArrayList<Point2D.Double>();
                    int edge = 1;
                    double before = 0;
                    for (int i = 0; i < count; i++) {
                        double target = length * i / count;
                        while (edge < contour.size() - 1
                              && before + contour.get(edge - 1).distance(contour.get(edge)) < target) {
                            before += contour.get(edge - 1).distance(contour.get(edge));
                            edge++;
                        }
                        var a = contour.get(edge - 1);
                        var b = contour.get(edge);
                        double t = (target - before) / a.distance(b);
                        samples.add(new Point2D.Double(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)));
                    }
                    for (int i = 0; i < count; i++) {
                        var a = samples.get((i + count - 2) % count);
                        var b = samples.get(i);
                        var c = samples.get((i + 2) % count);
                        // Check the ring and its flares, not the distant approach caps. Ignore the island and AWT's
                        // zero-area seams where separate tangent strokes meet.
                        double radial = b.distance(cx, cy);
                        if (radial < 32 || radial > 32 + 3 * BoardRoad.Kind.PAVED.halfWidth
                              || !boundary(footprint, a, b) || !boundary(footprint, b, c)) { continue; }
                        double ax = b.x - a.x, ay = b.y - a.y, bx = c.x - b.x, by = c.y - b.y;
                        double turn = Math.abs(Math.atan2(ax * by - ay * bx, ax * bx + ay * by));
                        assertTrue(turn < Math.toRadians(12), "The curb must turn smoothly at " + b);
                        checked++;
                    }
                }
                default -> throw new AssertionError("Expected a flattened road outline");
            }
        }
        assertTrue(checked > 500, "Exercise the complete outside curb, including all six entry/exit blends");
    }

    private static boolean boundary(Area area, Point2D.Double a, Point2D.Double b) {
        double distance = a.distance(b), nx = (b.y - a.y) / distance * .08, ny = (a.x - b.x) / distance * .08;
        double x = (a.x + b.x) / 2, y = (a.y + b.y) / 2;
        return area.contains(x + nx, y + ny) != area.contains(x - nx, y - ny);
    }

    private static Map<Coords, Hex> triangle(List<Coords> members) {
        return triangle(members, 0);
    }

    private static Map<Coords, Hex> triangle(List<Coords> members, int choices) {
        Map<Coords, Hex> board = new HashMap<>();
        for (Coords member : members) {
            int internal = 0;
            for (Coords other : members) {
                if (!member.equals(other)) { internal |= 1 << member.direction(other); }
            }
            int selected = (choices & (1 << members.indexOf(member))) == 0
                  ? Integer.numberOfTrailingZeros(internal) : 31 - Integer.numberOfLeadingZeros(internal);
            int outside = (selected + 3) % 6;
            int exits = internal | (1 << outside);
            board.put(member, new Hex(0, "road:1:" + exits, ""));
            board.put(member.translated(outside), new Hex(0, "road:1:" + (1 << ((outside + 3) % 6)), ""));
        }
        return board;
    }
}
