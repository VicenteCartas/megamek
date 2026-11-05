/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardRoadTurnTest {
    @Test
    void everyTwoExitBendHasAContinuousDividerWhileStraightRoadsKeepGaps() throws Exception {
        for (int column : new int[] { 3, 4 }) {
            var at = new Coords(column, 4);
            for (int exits = 0; exits < 64; exits++) {
                if (Integer.bitCount(exits) != 2) { continue; }
                var road = BoardRoad.layout(at, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED);
                var paint = road.markings(at, exits);
                var course = course(road);
                int painted = 0, gaps = 0;
                for (int i = 1; i < course.size(); i++) {
                    var a = course.get(i - 1); var b = course.get(i);
                    int samples = Math.max(1, (int) Math.ceil(a.dst(b) * 4));
                    for (int sample = 0; sample < samples; sample++) {
                        var point = new Vector2(a).lerp(b, (sample + .5f) / samples);
                        if (paint.contains(point.x, point.y)) { painted++; }
                        else { gaps++; }
                    }
                }
                assertTrue(painted > 0);
                if ((exits & (exits >> 3)) == 0) {
                    assertEquals(0, gaps, "The whole turning centre line is solid, exits=" + exits);
                } else {
                    assertTrue(gaps > 0, "A straight two-way road retains its dashed divider");
                }
                paint.subtract(road.footprint(0));
                assertTrue(paint.isEmpty(), "Bend paint follows the actual shared carriageway");
            }
        }
    }

    @Test
    void solidDividersStartOnBothConnectedApproachesBeforeEveryBend() {
        var at = BoardScenes.CENTER;
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) != 2 || (exits & (exits >> 3)) != 0) { continue; }
            Map<Coords, Integer> masks = new HashMap<>();
            masks.put(at, exits);
            for (int d = 0; d < 6; d++) {
                if ((exits & 1 << d) != 0) { masks.put(at.translated(d), (1 << d) | (1 << ((d + 3) % 6))); }
            }
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  masks.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  masks.getOrDefault(c, 0), 0, BoardScene.Surface.GRASS));
            for (int d = 0; d < 6; d++) {
                if ((exits & 1 << d) == 0) { continue; }
                var next = scene.tile(at.translated(d));
                var road = BoardRoad.of(scene, next);
                var paint = road.markings(next.coords(), next.roadExits());
                var gate = gate(next.coords(), (d + 3) % 6);
                float distance = gate.len();
                var toward = new Vector2(gate).nor();
                for (float along = distance - 28; along < distance + 1; along += .25f) {
                    assertTrue(paint.contains(toward.x * along, toward.y * along),
                          "The divider becomes solid before crossing into the bend, exits=" + exits + " side=" + d);
                }
                boolean gap = false;
                for (float along = -30; along < -5; along += .25f) {
                    gap |= !paint.contains(toward.x * along, toward.y * along);
                }
                assertTrue(gap, "The distant part of the straight approach stays dashed");
            }
        }
        // A nearby turn which does not connect back cannot change the divider of this straight road.
        var next = at.translated(0);
        var unjoined = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) || c.equals(next) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(at) ? 9 : c.equals(next) ? 5 : 0, 0, BoardScene.Surface.GRASS));
        var plain = BoardRoad.layout(at, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).markings(at, 9);
        var actual = BoardRoad.of(unjoined, unjoined.tile(at)).markings(at, 9);
        actual.exclusiveOr(plain);
        assertTrue(actual.isEmpty(), "Only a connected bend changes the upstream divider");
    }

    @Test
    void fireAndIceTightTurnsRetainClearanceAndSharedRoadMouths() throws Exception {
        var file = new File("data/boards/unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        var board = new Board();
        board.load(file);
        var scene = BoardScenes.scene(file);
        for (var tile : scene.tiles()) {
            if (!BoardRoad.rendered(tile)) { continue; }
            var at = tile.coords();
            var road = BoardRoad.of(scene, tile);
            var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
            int exits = tile.roadExits() & 63;
            if (BoardRoad.climbingTurn(exits, BoardRoadGrade.ramps(scene, tile))) {
                assertClimbing(road);
            } else if (Integer.bitCount(exits) == 2 && (exits & ((exits << 1) | (exits >> 5))) != 0) {
                float radius = assertBroad(road);
                if (at.equals(new Coords(5, 7))) {
                    assertTrue(radius > BoardGeometry.height() / BoardGeometry.hexScale() / 4,
                          "The reported 0608 kink must turn across the level hex instead of at its edge: " + radius);
                }
            }
            for (float x = -35; x <= 35; x += 2.5f) {
                for (float y = -32; y <= 32; y += 2.5f) {
                    assertEquals(road.distance(x, y), captured.distance(x, y), .002f);
                }
            }
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0) { continue; }
                var next = scene.tile(at.translated(d));
                if (next == null || !BoardRoad.rendered(next)) { continue; }
                var adjacent = BoardRoad.of(scene, next);
                var middle = gate(at, d);
                var lateral = new Vector2(-middle.y, middle.x).nor();
                for (float offset = -7; offset <= 7; offset += .5f) {
                    var point = new Vector2(middle).mulAdd(lateral, offset);
                    var other = new Vector2(middle).scl(-1).mulAdd(lateral, offset);
                    assertTrue(road.distance(point.x, point.y) < 0 && adjacent.distance(other.x, other.y) < 0,
                          at + " -> " + next.coords() + " must cover the same full-width mouth at offset " + offset);
                }
                var outward = BoardRoad.bends(at, c -> BoardRoad.Node.of(scene.tile(c))).apply(d);
                var inward = BoardRoad.bends(next.coords(), c -> BoardRoad.Node.of(scene.tile(c))).apply((d + 3) % 6);
                if (outward == null) {
                    assertNull(inward);
                } else {
                    assertNotNull(inward);
                    assertTrue(outward.epsilonEquals(new Vector2(inward).scl(-1), .00001f));
                }
            }
        }
    }

    @Test
    void tightTurnsLeaveRoomForTheInsideShoulder() throws Exception {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int direction = 0; direction < 6; direction++) {
                int first = direction, last = (direction + 1) % 6;
                int exits = (1 << first) | (1 << last);
                for (int full : new int[] { -1, first, last }) {
                    var road = BoardRoad.layout(at, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED,
                          d -> d == full ? gate(at, d).nor() : null);
                    assertBroad(road);
                    for (int d : new int[] { first, last }) {
                        var gate = gate(at, d);
                        var lateral = new Vector2(-gate.y, gate.x).nor();
                        for (int offset : new int[] { -9, -7, 0, 7, 9 }) {
                            var point = new Vector2(gate).mulAdd(lateral, offset);
                            assertEquals(Math.abs(offset) - BoardRoad.Kind.PAVED.halfWidth, road.distance(point.x, point.y), .01f,
                                  "A rounded turn retains the entire shared road mouth");
                        }
                        for (int offset : new int[] { -14, 14 }) {
                            var point = new Vector2(gate).mulAdd(lateral, offset);
                            assertTrue(road.distance(point.x, point.y) < 0, "Both widened lanes reach the neighboring road");
                        }
                    }
                }
            }
        }
    }

    @Test
    void mesaBridgeHairpinAndCapturedClearanceFollowTheSameCourse() throws Exception {
        var file = new File("data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");
        var board = new Board();
        board.load(file);
        var scene = BoardScenes.scene(file);
        var at = new Coords(26, 21);
        assertEquals(3, scene.tile(at).roadExits());
        var road = BoardRoad.of(scene, scene.tile(at));
        var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
        // The hairpin descends off the mesa towards its northern neighbour.
        assertTrue(BoardRoad.climbingTurn(3, BoardRoadGrade.ramps(scene, scene.tile(at))));
        assertClimbing(road);
        for (float x = -35; x <= 35; x += 1.25f) {
            for (float y = -32; y <= 32; y += 1.25f) {
                assertEquals(road.distance(x, y), captured.distance(x, y), .002f);
            }
        }
        var next = at.translated(1);
        var adjacent = BoardRoad.of(scene, scene.tile(next));
        var middle = gate(at, 1);
        var lateral = new Vector2(-middle.y, middle.x).nor();
        for (float offset = -9; offset <= 9; offset += .5f) {
            var point = new Vector2(middle).mulAdd(lateral, offset);
            var other = new Vector2(middle).scl(-1).mulAdd(lateral, offset);
            assertEquals(road.distance(point.x, point.y), adjacent.distance(other.x, other.y), .002f,
                  "The bridge approach keeps its shared width and tangent");
        }
    }

    private static Vector2 gate(Coords at, int direction) {
        Vector3 delta = BoardGeometry.center(at.translated(direction), 0).sub(BoardGeometry.center(at, 0))
              .scl(.5f / BoardGeometry.hexScale());
        return new Vector2(delta.x, delta.y);
    }

    /**
     * A climbing return turn runs in to the hex centre and turns there, keeping both ramps long; its converging ramps
     * close the inner shoulder instead of using the whole hex as a level turn does (see {@link #assertBroad}).
     */
    private static void assertClimbing(BoardRoad road) {
        assertTrue(road.distance(0, 0) < 0, "A climbing return turn runs in to the hex centre");
    }

    private static float assertBroad(BoardRoad road) throws Exception {
        var points = course(road);
        float minimum = Float.POSITIVE_INFINITY;
        for (int i = 1; i + 1 < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            var c = points.get(i + 1);
            var ab = new Vector2(b).sub(a);
            var bc = new Vector2(c).sub(b);
            float area = Math.abs(ab.crs(bc));
            if (area < 1e-4f) { continue; }
            float radius = ab.len() * bc.len() * a.dst(c) / (2 * area);
            minimum = Math.min(minimum, radius);
        }
        assertTrue(Float.isFinite(minimum));
        assertTrue(minimum > BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER,
              "The inside shoulder must have a positive turn radius: " + minimum);
        assertTrue(points.size() <= 26, "Roundness does not require denser sampling");
        return minimum;
    }

    private static List<Vector2> course(BoardRoad road) throws Exception {
        var field = BoardRoad.class.getDeclaredField("paths");
        field.setAccessible(true);
        var path = (List<?>) ((List<?>) field.get(road)).getFirst();
        var points = new ArrayList<Vector2>();
        for (var point : path) {
            var x = point.getClass().getDeclaredMethod("x");
            var y = point.getClass().getDeclaredMethod("y");
            x.setAccessible(true);
            y.setAccessible(true);
            points.add(new Vector2((float) x.invoke(point), (float) y.invoke(point)));
        }
        return points;
    }

    private static final File MESA = new File(
          "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");

    @Test
    void mesaStraightRoadsKeepTheirAxisAndCaptureClearsTheRenderedRoute() {
        Board board = new Board();
        board.load(MESA);
        var scene = BoardScenes.scene(MESA);
        for (Coords at : List.of(new Coords(33, 15), new Coords(28, 22))) {
            var tile = scene.tile(at);
            int direction = Integer.numberOfTrailingZeros(tile.roadExits());
            assertEquals((1 << direction) | (1 << (direction + 3)), tile.roadExits(),
                  "Exercise the reported opposite-exit roads at 3416 and 2923");
            var rendered = BoardRoad.of(scene, tile);
            var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
            Vector3 gate = BoardGeometry.center(at.translated(direction), 0).sub(BoardGeometry.center(at, 0))
                  .scl(.5f / BoardGeometry.hexScale());
            Vector3 across = new Vector3(-gate.y, gate.x, 0).nor();
            for (float along = -1; along <= 1; along += .0625f) {
                for (float offset : new float[] { -9, -7, -3, 0, 3, 7, 9 }) {
                    Vector3 point = new Vector3(gate).scl(along).mulAdd(across, offset);
                    float expected = Math.abs(offset) - BoardRoad.Kind.PAVED.halfWidth;
                    assertEquals(expected, rendered.distance(point.x, point.y), .002f, at + " must not bow sideways");
                    assertEquals(rendered.distance(point.x, point.y), captured.distance(point.x, point.y), .002f,
                          "Vegetation and rocks must clear the same route that is rendered");
                }
            }
        }
    }

    @Test
    void mesaLowerRoadMakesOneBroadTurnWithoutReversingItsCurvature() {
        var scene = BoardScenes.scene(MESA);
        float previousHeading = Float.NEGATIVE_INFINITY;
        for (Coords at : List.of(new Coords(27, 22), new Coords(28, 22), new Coords(29, 21))) {
            var road = BoardRoad.of(scene, scene.tile(at));
            // Traverse the actual 2823 -> 2923 -> 3022 road from west towards north. The last tile ends at its
            // north border, so stop just before its vertical tangent; the other tiles cross their full width.
            float first = -31.25f, last = at.getX() == 29 ? -.25f : 31.25f;
            Vector2 before = new Vector2(first, centerY(road, first));
            for (float x = first + 1; x <= last; x++) {
                Vector2 point = new Vector2(x, centerY(road, x));
                float heading = (float) Math.atan2(point.y - before.y, point.x - before.x);
                assertTrue(heading >= previousHeading - .025f,
                      "A broad left turn must not insert a reverse bend at " + at + ": " + previousHeading + " -> " + heading);
                previousHeading = heading;
                before = point;
            }
        }
    }

    private static float centerY(BoardRoad road, float x) {
        float low = -36, high = 36;
        for (int step = 0; step < 45; step++) {
            float a = high - .618f * (high - low), b = low + .618f * (high - low);
            if (road.distance(x, a) < road.distance(x, b)) { high = b; } else { low = a; }
        }
        return (low + high) / 2;
    }
}
