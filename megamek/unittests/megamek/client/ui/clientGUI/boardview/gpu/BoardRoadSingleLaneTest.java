/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector2;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.board.SingleLane;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Single-lane, one-way roads on the 3D board: their width, markings, direction arrows and the signs where they join. */
class BoardRoadSingleLaneTest {
    private static final Coords J = new Coords(4, 4);
    private static final Coords S1 = J.translated(2), S2 = S1.translated(2), S3 = S2.translated(2);

    private static void road(Board board, Coords at, String terrain, boolean single, Integer inbound,
          HexAppearance.TrafficControl control) {
        Hex hex = new Hex(0, terrain, "");
        Map<String, HexAppearance> look = new LinkedHashMap<>();
        HexAppearance road = single ? HexAppearance.withLanes(null, true, inbound) : null;
        if (control != null) { road = HexAppearance.withTrafficControl(road, control); }
        if (road != null) { look.put("road", road); }
        hex.setAppearance(look);
        board.setHex(at, hex);
    }

    private static void road(Board board, Coords at, int exits, boolean single, Integer inbound,
          HexAppearance.TrafficControl control) {
        road(board, at, "road:1:" + exits, single, inbound, control);
    }

    /**
     * A two-lane road north to south through J (and on the {@code extra} sides), and a single-lane ramp leaving J to the
     * south-east for three hexes: J's exit (off-ramp), or its entrance (on-ramp).
     */
    static Board ramp(boolean exit, HexAppearance.TrafficControl control, int extra) {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(0).translated(0), 9, false, null, null);
        road(board, J.translated(0), 9, false, null, null);
        road(board, J, 9 | 4 | extra, false, null, control);
        road(board, J.translated(3), 9, false, null, null);
        road(board, J.translated(3).translated(3), 9, false, null, null);
        for (Coords at : List.of(S1, S2, S3)) { road(board, at, 4 | 32, true, exit ? 32 : 4, null); }
        for (int d = 0; d < 6; d++) {
            if ((extra & 1 << d) != 0) { road(board, J.translated(d), 1 << (d + 3) % 6 | 1 << d, false, null, null); }
        }
        return board;
    }

    /** A two-lane road north of J narrowing at J into a single lane running south: its way on (exit) or in. */
    static Board drop(boolean exit) {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(0), 9, false, null, null);
        road(board, J, 9, false, null, null);
        road(board, J.translated(3), 9, true, exit ? 1 : 8, null);
        road(board, J.translated(3).translated(3), 9, true, exit ? 1 : 8, null);
        return board;
    }

    static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board), List.of(), List.of(),
              -1, "", List.of());
    }

    private static BoardRoad of(BoardScene scene, Coords at) { return BoardRoad.of(scene, scene.tile(at)); }

    private static Vector2 axis(int direction) {
        float[] border = RailRoute.border(direction);
        return new Vector2(border[0], border[1]).nor();
    }

    @Test
    void aSingleLaneIsHalfAsWideAndBothSidesOfItsBordersAgree() {
        var scene = scene(ramp(true, null, 0));
        var lane = of(scene, S2);
        assertTrue(lane.narrow());
        assertEquals(36, lane.single(), "Both its arms are a single lane wide");
        Vector2 across = new Vector2(axis(2)).rotate90(1);
        assertEquals(0, lane.distance(across.x * BoardRoad.SINGLE_HALF, across.y * BoardRoad.SINGLE_HALF), .05f);
        assertTrue(lane.distance(across.x * (BoardRoad.SINGLE_HALF + 1), across.y * (BoardRoad.SINGLE_HALF + 1)) > .9f);
        assertTrue(lane.distance(across.x * (BoardRoad.SINGLE_HALF - 1), across.y * (BoardRoad.SINGLE_HALF - 1)) < -.9f);
        var join = of(scene, J);
        assertFalse(join.narrow());
        assertEquals(4, join.single(), "Only J's arm onto the ramp narrows");
        float[] gate = RailRoute.border(2), back = RailRoute.border(5);
        assertEquals(0, join.distance(gate[0] + across.x * BoardRoad.SINGLE_HALF, gate[1] + across.y * BoardRoad.SINGLE_HALF), .1f,
              "J meets the ramp a single lane wide");
        var first = of(scene, S1);
        assertEquals(0, first.distance(back[0] + across.x * BoardRoad.SINGLE_HALF, back[1] + across.y * BoardRoad.SINGLE_HALF), .1f,
              "And so does the ramp, on its side of the same border");
        Vector2 main = new Vector2(axis(0)).rotate90(1);
        float[] north = RailRoute.border(0);
        assertEquals(0, join.distance(north[0] + main.x * BoardRoad.Kind.PAVED.halfWidth,
              north[1] + main.y * BoardRoad.Kind.PAVED.halfWidth), .1f, "The two-lane road keeps its width");
    }

    @Test
    void aSingleLaneHasNoCentreLineAndItsArrowsPointTheWayItsTrafficRuns() {
        for (boolean exit : new boolean[] { true, false }) {
            var scene = scene(ramp(exit, null, 0));
            var lane = of(scene, S2);
            assertTrue(lane.markings(S2, 36).isEmpty(), "A single lane has no divider");
            var arrows = lane.arrows(36);
            assertFalse(arrows.isEmpty());
            Vector2 travel = exit ? axis(2) : axis(5), across = new Vector2(travel).rotate90(1);
            assertTrue(arrows.contains(travel.x * 2 + across.x * 1.2f, travel.y * 2 + across.y * 1.2f),
                  "The arrow's head lies ahead, the way traffic runs: exit " + exit);
            assertFalse(arrows.contains(-travel.x * 5 + across.x * 1.2f, -travel.y * 5 + across.y * 1.2f), "Its tail is a narrow shaft");
            assertTrue(arrows.contains(-travel.x * 5, -travel.y * 5));
            assertTrue(of(scene, J).arrows(13).isEmpty(), "A two-lane road has no arrows");
        }
    }

    @Test
    void anEntranceGivesWayToTheTwoLaneRoadAndAnExitWaitsForNothing() {
        var entrance = of(scene(ramp(false, null, 0)), J);
        var approaches = entrance.stopApproaches(13);
        assertEquals(1, approaches.size(), "The two-lane road keeps its priority: only the ramp waits");
        var giveWay = approaches.getFirst();
        assertEquals(2, giveWay.direction());
        assertTrue(giveWay.yields());
        assertEquals(-giveWay.outer(), giveWay.inner(), .0001f, "Its one lane waits across its whole width");
        var paint = entrance.stopLines(approaches);
        int painted = 0, gaps = 0;
        for (float across = giveWay.inner() + .2f; across < giveWay.outer() - .2f; across += .25f) {
            if (paint.contains(giveWay.x() - giveWay.ny() * across, giveWay.y() + giveWay.nx() * across)) { painted++; } else { gaps++; }
        }
        assertTrue(painted > 0 && gaps > 0, "A broken give-way line, not a solid stop bar");
        assertTrue(paint.contains(giveWay.x() + giveWay.nx() * 7.8f, giveWay.y() + giveWay.ny() * 7.8f),
              "Upstream, the give-way triangle points at merging traffic");
        assertTrue(of(scene(ramp(true, null, 0)), J).stopApproaches(13).isEmpty(), "Nothing waits on an exit");

        var signals = of(scene(ramp(false, HexAppearance.TrafficControl.SIGNALS, 0)), J).stopApproaches(13);
        assertEquals(Set.of(0, 2, 3), signals.stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()));
        assertTrue(signals.stream().noneMatch(BoardRoad.StopApproach::yields), "Lights stop the ramp as they stop the road");
        for (var control : List.of(HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY)) {
            assertTrue(of(scene(ramp(true, control, 0)), J).stopApproaches(13).isEmpty(),
                  control + ": with only an exit leaving it, nothing joins the road for its lights to stop it for");
        }
        var crossroads = of(scene(ramp(true, HexAppearance.TrafficControl.SIGNALS, 16)), J).stopApproaches(13 | 16);
        assertEquals(Set.of(0, 3, 4), crossroads.stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()),
              "A third two-lane road joins: the lights stop every two-lane arm, the exit still waits for nothing");
    }

    @Test
    void theTwoLaneRoadsDividerStaysOffTheRamp() {
        var join = of(scene(ramp(true, null, 0)), J);
        var markings = join.markings(J, 13);
        Vector2 ramp = axis(2);
        float reach = RailRoute.border(2)[0] / ramp.x;
        for (float along = .5f; along <= .95f; along += .05f) {
            assertFalse(markings.contains(ramp.x * reach * along, ramp.y * reach * along), "No centre line on the ramp's arm");
        }
        boolean dashed = false;
        for (float y = 20; y <= 36; y += .5f) { dashed |= markings.contains(0, y); }
        assertTrue(dashed, "The two-lane road keeps its centre line");
        var narrowing = of(scene(drop(true)), J);
        var divider = narrowing.markings(J, 9);
        boolean north = false, south = false;
        for (float y = 5; y <= 34; y += .5f) {
            north |= divider.contains(0, y);
            south |= divider.contains(0, -y);
        }
        assertTrue(north, "The two-lane half keeps its divider");
        assertFalse(south, "It ends where the road narrows to one lane");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void thePriorityRoadKeepsItsDashedCentreLineThroughTheRampsJunction(boolean exit) {
        var junction = of(scene(ramp(exit, null, 0)), J).markings(J, 13);
        var straight = BoardRoad.layout(J, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).markings(J, 9);
        int painted = 0, gaps = 0;
        for (float y = -36; y <= 36; y += .1f) {
            assertEquals(straight.contains(0, y), junction.contains(0, y), "As through any of its own hexes, at " + y);
            if (junction.contains(0, y)) { painted++; } else { gaps++; }
        }
        assertTrue(painted > 0 && gaps > 0, "Dashed, not stopped or solid before the junction");
        for (var control : List.of(HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY)) {
            var lights = of(scene(ramp(exit, control, 0)), J).markings(J, 13);
            if (exit) {
                for (float y = -36; y <= 36; y += .1f) {
                    assertEquals(straight.contains(0, y), lights.contains(0, y), control + ": nothing joins, still dashed at " + y);
                }
            } else {
                for (float y = -12; y <= 12; y += .25f) {
                    assertFalse(lights.contains(0, y), control + ": the divider stops short of the junction, as at any, at " + y);
                }
            }
        }
        var crossroads = of(scene(ramp(exit, null, 16)), J).markings(J, 13 | 16);
        assertFalse(crossroads.contains(0, 0), "With a third two-lane arm it is a junction of two-lane roads");
    }

    /** The exit ramp of {@link #ramp}, taken only by traffic from J's side {@code from} (-1 for both directions). */
    static Board exitTakenFrom(int from, HexAppearance.TrafficControl control) {
        Board board = ramp(true, control, 0);
        if (from >= 0) { takenFrom(board, 2, 1 << from); }
        return board;
    }

    /** J storing that only traffic from its {@code sides} may take its exit across {@code exit} (HexAppearance#exitFrom). */
    private static void takenFrom(Board board, int exit, int sides) {
        Hex hex = board.getHex(J).duplicate();
        var looks = new LinkedHashMap<>(hex.getAppearance());
        looks.put("road", HexAppearance.withExitFrom(looks.get("road"), (long) sides << 6 * exit));
        hex.setAppearance(looks);
        board.setHex(J, hex);
    }

    /**
     * A two-lane road from the south bending north-east at J, and a single-lane exit running straight on to the north,
     * taken only by traffic from J's {@code sides} (0 for both directions).
     */
    static Board straightExit(int sides) { return straightExit(sides, null); }

    static Board straightExit(int sides, HexAppearance.TrafficControl control) {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(3), 9, false, null, null);
        road(board, J, 8 | 2 | 1, false, null, control);
        road(board, J.translated(1), 16 | 2, false, null, null);
        road(board, J.translated(0), 9, true, 8, null);
        road(board, J.translated(0).translated(0), 9, true, 8, null);
        if (sides != 0) { takenFrom(board, 0, sides); }
        return board;
    }

    /** The asphalt one road has and another has not, and on which side of J it lies: towards the bisector of two sides. */
    private static Vector2 extra(BoardRoad more, BoardRoad less) {
        var area = new java.awt.geom.Area(more.footprint(0));
        area.subtract(less.footprint(0));
        assertFalse(area.isEmpty(), "A turn's envelope is missing");
        var bounds = area.getBounds2D();
        return new Vector2((float) bounds.getCenterX(), (float) bounds.getCenterY());
    }

    private static Vector2 between(int a, int b) { return axis(a).add(axis(b)).nor(); }

    private static void contains(BoardRoad outer, BoardRoad inner) {
        for (float x = -40; x <= 40; x += .5f) {
            for (float y = -40; y <= 40; y += .5f) {
                assertFalse(inner.distance(x, y) < -.05f && outer.distance(x, y) > .05f, "No asphalt of its own at " + x + ", " + y);
            }
        }
    }

    @Test
    void anExitTakenFromOneDirectionTurnsOnlyThatDirectionsWayIn() {
        var both = of(scene(exitTakenFrom(-1, null)), J);
        var north = of(scene(exitTakenFrom(0, null)), J);
        var south = of(scene(exitTakenFrom(3, null)), J);
        assertTrue(north.closed(3, 2) && north.closed(2, 3) && !north.closed(0, 2));
        assertTrue(south.closed(0, 2) && !south.closed(3, 2));
        contains(both, north);
        contains(both, south);
        // Taken from the north only, the tight corner from the south is gone: what is missing lies between S and SE.
        Vector2 corner = extra(both, north);
        assertTrue(corner.dot(between(2, 3)) > corner.dot(between(0, 2)), "The square corner is the south's: " + corner);
        // Taken from the south only, the broad bend from the north is gone: between N and SE.
        Vector2 bend = extra(both, south);
        assertTrue(bend.dot(between(0, 2)) > bend.dot(between(2, 3)), "The missing bend is the north's: " + bend);
    }

    @Test
    void aClosedTurnAcrossTheOncomingLaneMakesTheCentreLineSolid() {
        assertTrue(BoardRoad.leftTurn(0, 2), "Southbound into the south-east exit turns left, across the northbound lane");
        assertFalse(BoardRoad.leftTurn(3, 2), "Northbound into it turns right");
        var southOnly = of(scene(exitTakenFrom(3, null)), J).markings(J, 13);
        for (float y = -30; y <= 30; y += .5f) { assertTrue(southOnly.contains(0, y), "Solid at " + y); }
        var northOnly = of(scene(exitTakenFrom(0, null)), J).markings(J, 13);
        var straight = BoardRoad.layout(J, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).markings(J, 9);
        for (float y = -36; y <= 36; y += .1f) {
            assertEquals(straight.contains(0, y), northOnly.contains(0, y), "Southbound may cross: still dashed at " + y);
        }
        Board gravel = exitTakenFrom(3, null);
        road(gravel, J.translated(0), "road:4:9", false, null, null);
        var loose = of(scene(gravel), J).markings(J, 13);
        assertTrue(loose.contains(0, 0), "Still solid through the junction");
        assertFalse(loose.contains(0, 31), "But it stops short of the gravel road it runs on into, as the dashed line does");
    }

    /** Whether a lane arrow on {@code arm}'s approach branches off to the left or the right of its way (BoardRoad#laneArrow). */
    private static boolean branches(BoardRoad road, java.awt.geom.Area arrows, int arm, int side) {
        return branches(road, arrows, 13, arm, side);
    }

    /** As {@link #branches(BoardRoad, java.awt.geom.Area, int, int)}, side 0 asking whether it points on straight ahead. */
    private static boolean branches(BoardRoad road, java.awt.geom.Area arrows, int exits, int arm, int side) {
        var frame = road.armFrame(exits, arm);
        float lane = (frame.inner() + frame.outer()) / 2;
        float x = frame.x() + frame.nx() * 8 - frame.ny() * lane, y = frame.y() + frame.ny() * 8 + frame.nx() * lane;
        float tx = -frame.nx(), ty = -frame.ny();
        assertTrue(arrows.contains(x, y), "An arrow in the lane of the approach across side " + arm);
        // Straight on, its head is wider than its shaft just off its middle, where no branch runs.
        if (side == 0) { return arrows.contains(x + tx * 2.5f - ty * .9f, y + ty * 2.5f + tx * .9f); }
        float bx = (tx - side * ty) * .7071f, by = (ty + side * tx) * .7071f;
        return arrows.contains(x + bx * 3.5f, y + by * 3.5f);
    }

    @Test
    void reversingARunBeyondAForkReachesTheJunctionsCachedSurface() {
        // J's exit runs into a fork E, which stores no way and takes it from the runs beyond: F south and G north-east.
        Coords e = J.translated(2), f = e.translated(3), g = e.translated(1);
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(0), 9, false, null, null);
        road(board, J, 9 | 4, false, null, null);
        road(board, J.translated(3), 9, false, null, null);
        road(board, e, 32 | 8 | 2, true, null, null);
        road(board, g, 16 | 2, true, 16, null);
        road(board, f, 9, true, 1, null);
        takenFrom(board, 2, 1);
        var before = scene(board);
        assertFalse(SingleLane.entrance(BoardRoad.lanes(before), J, 2), "Both runs leave E, so traffic reaches it from J");
        assertNotEquals(0, of(before, J).closed());
        road(board, f, 9, true, 8, null);
        var after = scene(board);
        assertTrue(SingleLane.entrance(BoardRoad.lanes(after), J, 2), "F turned round feeds E, which now runs into J");
        assertEquals(0, of(after, J).closed(), "J's exit is gone, and with it the turns it closed");
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(J)), BoardSurface.geometryKey(after, after.tile(J)),
              "J's cached surface is keyed by E's derived way, though E itself stores nothing and did not change");
    }

    /**
     * J with two-lane arms across {@code wide}, and a hex of single lane on each of the other sides it has: leaving J
     * across {@code exits}, coming in across {@code entrances}.
     */
    static Board ramps(int wide, int exits, int entrances, HexAppearance.TrafficControl control) {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J, wide | exits | entrances, false, null, control);
        for (int d = 0; d < 6; d++) {
            int back = (d + 3) % 6, course = 1 << d | 1 << back;
            Coords next = J.translated(d);
            if ((wide & 1 << d) != 0) { road(board, next, course, false, null, null); }
            else if ((exits & 1 << d) != 0) { road(board, next, course, true, 1 << back, null); }
            else if ((entrances & 1 << d) != 0) { road(board, next, course, true, 1 << d, null); }
        }
        return board;
    }

    private static HexAppearance.TrafficControl control(String name) {
        return name.isEmpty() ? null : HexAppearance.TrafficControl.valueOf(name);
    }

    /** Every road paint of J's tile, as one area. */
    private static java.awt.geom.Area paint(BoardScene scene) {
        var area = new java.awt.geom.Area();
        GpuRoads.patches(scene.tile(J), of(scene, J)).stream().filter(patch -> patch.texture().equals("concrete-ground"))
              .forEach(patch -> area.add(new java.awt.geom.Area(patch.shape())));
        return area;
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "STOPS", "SIGNALS", "GANTRY" })
    void whereOnlyExitsLeaveTheRoadNoChoiceAddsJunctionControls(String name) {
        var control = control(name);
        var stops = HexAppearance.TrafficControl.STOPS;
        record Layout(String name, Board chosen, Board reference) { }
        var layouts = List.of(new Layout("an exit south-east", ramps(9, 4, 0, control), ramps(9, 4, 0, stops)),
              new Layout("an exit north-west", ramps(9, 32, 0, control), ramps(9, 32, 0, stops)),
              new Layout("exits on both sides", ramps(9, 4 | 16, 0, control), ramps(9, 4 | 16, 0, stops)),
              new Layout("one two-lane arm forking into exits", ramps(1, 4 | 16, 0, control), ramps(1, 4 | 16, 0, stops)),
              new Layout("an exit taken from the north", exitTakenFrom(0, control), exitTakenFrom(0, stops)),
              new Layout("an exit straight on", straightExit(1 << 1, control), straightExit(1 << 1, stops)));
        for (var layout : layouts) {
            var scene = scene(layout.chosen());
            var reference = scene(layout.reference());
            var road = of(scene, J);
            int exits = scene.tile(J).roadExits();
            assertFalse(road.junction(exits).controlled(), layout.name());
            assertTrue(road.stopApproaches(exits).isEmpty(), layout.name() + ": nothing waits, whatever is chosen");
            assertEquals(of(reference, J).priorityRoad(exits), road.priorityRoad(exits), layout.name());
            // Area compares its geometry with equals(Area) alone.
            assertTrue(of(reference, J).markings(J, exits).equals(road.markings(J, exits)), layout.name() + ": the same centre line");
            assertTrue(paint(reference).equals(paint(scene)), layout.name() + ": the same paint, no bars or keep-clear box");
            Board board = layout.chosen();
            var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
            assertEquals(BoardFeatures.roadControls(layout.reference().getHex(J), J, layout.reference()::getHex, true), controls,
                  layout.name() + ": the same signs");
            assertTrue(controls.stream().allMatch(feature -> feature.asset()
                        .matches("scenery/road/(one-way-sign|no-(left|right)-turn-sign|direction-sign-[a-z-]+)")),
                  layout.name() + ": only the exit's own signs, " + controls.stream().map(BoardScene.Feature::asset).toList());
            if (control != null) {
                assertEquals(control, HexAppearance.trafficControl(board.getHex(J).getAppearance(), "road", exits),
                      layout.name() + ": the choice is kept for when something joins");
            }
        }
    }

    @Test
    void whereTrafficJoinsTheRoadItsControlsStay() {
        var signals = HexAppearance.TrafficControl.SIGNALS;
        Board entrance = ramps(9, 0, 4, signals);
        var road = of(scene(entrance), J);
        assertTrue(road.junction(13).controlled());
        assertEquals(Set.of(0, 2, 3), road.stopApproaches(13).stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()));
        var lights = BoardFeatures.roadControls(entrance.getHex(J), J, entrance::getHex, true).stream()
              .filter(feature -> feature.asset().equals("scenery/road/traffic-signal")).map(feature -> feature.decoration().id()).toList();
        assertEquals(List.of("terrain:4_4:road:stop:0", "terrain:4_4:road:stop:2", "terrain:4_4:road:stop:3"), lights.stream().sorted().toList());
        var both = of(scene(ramps(9, 4, 16, signals)), J);
        assertEquals(Set.of(0, 3, 4), both.stopApproaches(29).stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()),
              "An exit and an entrance: the entrance and the road stop at the lights, the exit waits for nothing");
    }

    /** {@code board} with roadless pavement beside J across {@code sides}, which J's road runs into: its aprons. */
    private static Board paved(Board board, int sides) {
        Hex junction = board.getHex(J).duplicate();
        int exits = junction.getTerrain(Terrains.ROAD).getExits() | sides;
        junction.addTerrain(new megamek.common.units.Terrain(Terrains.ROAD, junction.terrainLevel(Terrains.ROAD), true, exits));
        board.setHex(J, junction);
        for (int d = 0; d < 6; d++) {
            if ((sides & 1 << d) != 0) { board.setHex(J.translated(d), new Hex(0, "pavement:1", "")); }
        }
        return board;
    }

    @Test
    void anApronIsNoRoadOfTheJunctionForTheEditorAsForTheBoard() {
        // A road north to south, its exit south-east, and a car park's pavement north-east it runs into.
        Board board = paved(ramps(9, 4, 0, HexAppearance.TrafficControl.SIGNALS), 2);
        Hex hex = board.getHex(J);
        int exits = hex.getTerrain(Terrains.ROAD).getExits() & 63;
        assertEquals(1 | 2 | 4 | 8, exits);
        int aprons = SingleLane.aprons(hex, J, board::getHex, exits), ramps = SingleLane.arms(hex, J, board::getHex, exits);
        assertEquals(2, aprons, "The pavement is an apron");
        assertEquals(4, ramps);
        assertFalse(SingleLane.junction(hex, J, board::getHex).controlled(), "What the editor reads: only the exit leaves");
        var road = of(scene(board), J);
        assertEquals(aprons, road.aprons(), "The board finds the same apron");
        assertFalse(road.junction(exits).controlled(), "And the same junction");
        assertTrue(BoardFeatures.roadControls(hex, J, board::getHex, true).stream()
              .allMatch(feature -> feature.asset().equals("scenery/road/one-way-sign")), "No lights");
        // Two aprons beside a road and its entrance are still no four roads: stop signs, the entrance giving way.
        Board entrance = paved(ramps(9, 0, 4, null), 2 | 32);
        Hex join = entrance.getHex(J);
        int all = join.getTerrain(Terrains.ROAD).getExits() & 63;
        assertEquals(HexAppearance.TrafficControl.STOPS, SingleLane.control(join.getAppearance(), all,
              SingleLane.arms(join, J, entrance::getHex, all), SingleLane.aprons(join, J, entrance::getHex, all)));
        var merge = of(scene(entrance), J);
        assertEquals(HexAppearance.TrafficControl.STOPS, merge.trafficControl(all));
        assertTrue(merge.stopApproaches(all).stream().allMatch(approach -> approach.direction() == 2 && approach.yields()));
    }

    @Test
    void rampsAreNoRoadsWhenTheJunctionChoosesItsDefaultControls() {
        // A road and its two ramps once counted four roads, which default to overhead lights.
        var pair = of(scene(ramps(9, 4, 16, null)), J);
        assertEquals(HexAppearance.TrafficControl.STOPS, pair.trafficControl(29));
        var giveWay = pair.stopApproaches(29);
        assertEquals(1, giveWay.size());
        assertTrue(giveWay.getFirst().yields() && giveWay.getFirst().direction() == 4, "Only the entrance gives way");
        var tee = of(scene(ramps(1 | 8 | 16, 4, 0, null)), J);
        assertEquals(HexAppearance.TrafficControl.STOPS, tee.trafficControl(29), "A T and an exit: stop signs, as at the T");
        assertEquals(List.of(4), tee.stopApproaches(29).stream().map(BoardRoad.StopApproach::direction).toList(), "Its stem stops");
        var crossroads = of(scene(ramps(1 | 2 | 8 | 16, 4, 0, null)), J);
        assertEquals(HexAppearance.TrafficControl.GANTRY, crossroads.trafficControl(31), "Four two-lane roads: overhead lights");
    }

    @ParameterizedTest
    @ValueSource(strings = { "STOPS", "SIGNALS" })
    void anEntranceBesideTheRoadsOwnArmStillGetsItsSign(String name) {
        // The south-west entrance's mouth lies in the corner of the road's south arm: its sign stands out along its lane.
        Board board = ramps(9, 4, 16, HexAppearance.TrafficControl.valueOf(name));
        var road = of(scene(board), J);
        var approach = road.stopApproaches(29).stream().filter(arm -> arm.direction() == 4).findFirst().orElseThrow();
        String asset = name.equals("STOPS") ? "give-way-sign" : "traffic-signal";
        var signs = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true).stream()
              .filter(feature -> feature.decoration().id().equals("terrain:4_4:road:stop:4")).toList();
        assertEquals(1, signs.size(), "The entrance has its " + asset);
        var sign = signs.getFirst();
        assertEquals("scenery/road/" + asset, sign.asset());
        assertEquals(1, facing(sign).dot(approach.nx(), approach.ny()), .0001f, "Facing the entrance's traffic");
        float side = -approach.ny() * (sign.x() - approach.x()) + approach.nx() * (sign.y() - approach.y());
        assertTrue(side > approach.outer(), "On its right");
        assertFalse(road.footprint(0).contains(sign.x(), sign.y()), "Off the asphalt");
    }

    @Test
    void anEntranceGivesWayWhereNoTwoLaneRoadKeepsPriority() {
        // A Y of two-lane roads has no road through to keep priority. No all-way stop, which leaves nobody priority: the
        // two-lane roads keep priority to the right among themselves, and the entrance gives way to them all.
        var y = of(scene(ramps(1 | 4 | 16, 0, 8, HexAppearance.TrafficControl.STOPS)), J);
        assertEquals(0, y.junction(29).priority());
        assertEquals(Set.of(3), y.stopApproaches(29).stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()));
        assertTrue(y.stopApproaches(29).stream().allMatch(BoardRoad.StopApproach::yields));
        assertEquals(0, y.priorityRoad(29), "No road runs its centre line on through");
        assertEquals(HexAppearance.TrafficControl.SIGNALS, of(scene(ramps(1 | 4 | 16, 0, 8, null)), J).trafficControl(29),
              "Four approaches and no road keeping priority: traffic lights by default");
        // At a T the road through keeps priority: its stem stops and the entrance gives way to it.
        var tee = of(scene(ramps(1 | 8 | 16, 0, 4, HexAppearance.TrafficControl.STOPS)), J).stopApproaches(29);
        assertEquals(Set.of(2, 4), tee.stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()));
        assertTrue(tee.stream().filter(approach -> approach.direction() == 2).allMatch(BoardRoad.StopApproach::yields));
        assertTrue(tee.stream().filter(approach -> approach.direction() == 4).noneMatch(BoardRoad.StopApproach::yields));
    }

    @Test
    void turningAnExitIntoAnEntranceBringsBackTheChosenLights() {
        var gantry = HexAppearance.TrafficControl.GANTRY;
        Board exit = ramp(true, gantry, 0), entrance = ramp(false, gantry, 0);
        assertTrue(BoardFeatures.roadControls(exit.getHex(J), J, exit::getHex, true).stream()
              .noneMatch(feature -> feature.asset().contains("traffic-signal")), "No lights while only the exit leaves");
        assertEquals(List.of("terrain:4_4:road:stop:0", "terrain:4_4:road:stop:2", "terrain:4_4:road:stop:3"),
              BoardFeatures.roadControls(entrance.getHex(J), J, entrance::getHex, true).stream()
                    .filter(feature -> feature.asset().contains("traffic-signal")).map(feature -> feature.decoration().id()).sorted().toList(),
              "An entrance joins: the gantries the junction keeps stored stand again");
        var before = scene(exit);
        var after = scene(entrance);
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(J)), BoardSurface.geometryKey(after, after.tile(J)));
    }

    @Test
    void anExitStraightOnIsSignedAndArrowedAsStraightOn() {
        assertEquals(0, BoardRoad.turn(3, 0), "From the south into the north: straight on");
        assertEquals(1, BoardRoad.turn(0, 2), "From the north into the south-east: left");
        assertEquals(-1, BoardRoad.turn(3, 1), "From the south into the north-east: right");
        Board board = straightExit(1 << 1);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var sign = sign(controls, "direction-sign-r");
        assertEquals("terrain:4_4:road:turn:3", sign.decoration().id(),
              "Northbound may not go on into the exit: it is shown its only way on, right (no sign forbids straight on)");
        assertTrue(controls.stream().noneMatch(feature -> feature.asset().contains("no-left") || feature.asset().contains("no-right")));
        var road = of(scene(board), J);
        var arrows = road.approachArrows(11);
        assertFalse(branches(road, arrows, 11, 3, 0), "Northbound: no arrowhead straight into the closed exit");
        assertTrue(branches(road, arrows, 11, 3, -1), "Only right, as its road bends north-east");
        assertFalse(branches(road, arrows, 11, 3, 1));
        assertFalse(branches(road, arrows, 11, 1, 0), "From the north-east: no way straight on");
        assertTrue(branches(road, arrows, 11, 1, 1) && branches(road, arrows, 11, 1, -1), "Left on south, or right into the exit");
        board = straightExit(1 << 3);
        controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        assertEquals("terrain:4_4:road:turn:1", sign(controls, "no-right-turn-sign").decoration().id());
        road = of(scene(board), J);
        arrows = road.approachArrows(11);
        assertTrue(branches(road, arrows, 11, 3, 0) && branches(road, arrows, 11, 3, -1), "Northbound: on into the exit, or right");
        assertFalse(branches(road, arrows, 11, 1, -1), "South-westbound may not turn right into it");
        assertTrue(branches(road, arrows, 11, 1, 1));
    }

    @Test
    void aRestrictedExitIsShownByArrowsInTheApproachesLanes() {
        assertTrue(of(scene(exitTakenFrom(-1, null)), J).approachArrows(13).isEmpty(), "Both directions: no arrows, as at any junction");
        var north = of(scene(exitTakenFrom(0, null)), J);
        var arrows = north.approachArrows(13);
        assertTrue(branches(north, arrows, 0, 1), "Southbound may take the exit: ahead or left into it");
        assertFalse(branches(north, arrows, 0, -1));
        assertFalse(branches(north, arrows, 3, 1) || branches(north, arrows, 3, -1), "Northbound may not: ahead only");
        var south = of(scene(exitTakenFrom(3, null)), J);
        arrows = south.approachArrows(13);
        assertTrue(branches(south, arrows, 3, -1), "Northbound may: ahead or right into it");
        assertFalse(branches(south, arrows, 0, 1) || branches(south, arrows, 0, -1), "Southbound may not: ahead only");
        var frame = north.armFrame(13, 3);
        float lane = (frame.inner() + frame.outer()) / 2;
        float x = frame.x() + frame.nx() * 8 - frame.ny() * lane, y = frame.y() + frame.ny() * 8 + frame.nx() * lane;
        var tile = scene(exitTakenFrom(0, null)).tile(J);
        assertTrue(GpuRoads.patches(tile, north).stream()
              .anyMatch(patch -> patch.texture().equals("concrete-ground") && patch.shape().contains(x, y)), "They are painted");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 3 })
    void anApproachThatMayNotTakeTheExitIsSignedBeforeTheJunction(int allowed) {
        Board board = exitTakenFrom(allowed, null);
        int closed = allowed == 0 ? 3 : 0;
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var sign = sign(controls, allowed == 0 ? "no-right-turn-sign" : "no-left-turn-sign");
        assertEquals("terrain:4_4:road:turn:" + closed, sign.decoration().id());
        sign(controls, "one-way-sign");
        var road = of(scene(board), J);
        var frame = road.armFrame(13, closed);
        assertEquals(1, facing(sign).dot(frame.nx(), frame.ny()), .0001f, "It faces the traffic it turns away");
        float side = -frame.ny() * (sign.x() - frame.x()) + frame.nx() * (sign.y() - frame.y());
        assertTrue(side > frame.outer(), "On that traffic's right");
        assertFalse(road.footprint(0).contains(sign.x(), sign.y()));
        assertTrue(BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true).stream()
              .noneMatch(feature -> feature.asset().contains("turn-sign") && !feature.equals(sign)), "One sign, for the closed turn");
        var both = exitTakenFrom(-1, null);
        assertTrue(BoardFeatures.roadControls(both.getHex(J), J, both::getHex, true).stream()
              .noneMatch(feature -> feature.asset().contains("turn-sign")), "Both directions: no turn is signed away");
    }

    private static BoardScene.Feature sign(List<BoardScene.Feature> features, String asset) {
        var matches = features.stream().filter(feature -> feature.asset().equals("scenery/road/" + asset)).toList();
        assertEquals(1, matches.size(), asset + " in " + features.stream().map(BoardScene.Feature::asset).toList());
        return matches.getFirst();
    }

    /** Model +Y, a sign's face, after its rotation. */
    private static Vector2 facing(BoardScene.Feature sign) {
        double angle = Math.toRadians(sign.rotation());
        return new Vector2((float) -Math.sin(angle), (float) Math.cos(angle));
    }

    @Test
    void anEntranceHasAGiveWaySignForMergingTrafficAndANoEntrySignFacingTheJunction() {
        Board board = ramp(false, null, 0);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        assertEquals(2, controls.size(), controls.stream().map(BoardScene.Feature::asset).toList().toString());
        var scene = scene(board);
        var join = of(scene, J);
        var frame = join.stopApproaches(13).getFirst();
        var giveWay = sign(controls, "give-way-sign");
        assertEquals("terrain:4_4:road:stop:2", giveWay.decoration().id());
        assertEquals(1, facing(giveWay).dot(frame.nx(), frame.ny()), .0001f, "It faces traffic waiting on the ramp");
        float side = -frame.ny() * (giveWay.x() - frame.x()) + frame.nx() * (giveWay.y() - frame.y());
        assertTrue(side > frame.outer(), "On that traffic's right, beside the ramp");
        var noEntry = sign(controls, "no-entry-sign");
        assertEquals("terrain:4_4:road:ramp:2", noEntry.decoration().id());
        assertEquals(-1, facing(noEntry).dot(frame.nx(), frame.ny()), .0001f, "It faces the junction");
        side = -frame.ny() * (noEntry.x() - frame.x()) + frame.nx() * (noEntry.y() - frame.y());
        assertTrue(side < -frame.outer(), "On the right of traffic that would turn into the ramp");
        for (var sign : controls) {
            assertFalse(join.footprint(0).contains(sign.x(), sign.y()), "Beside the carriageway");
            assertTrue(BoardFeatures.terrainOwned(sign.decoration()));
        }
        assertEquals(controls, BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true), "Stable on recapture");
        assertTrue(scene.tile(J).features().stream().anyMatch(feature -> feature.asset().equals("scenery/road/no-entry-sign")),
              "The scene keeps them");
        assertTrue(board.getHex(J).getDecorations().isEmpty(), "Derived, never saved");
    }

    @Test
    void anExitHasAOneWaySignFacingTrafficTurningIntoIt() {
        Board board = ramp(true, null, 0);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var oneWay = sign(controls, "one-way-sign");
        assertEquals(1, controls.size(), "Nothing else: the two-lane road has priority and nothing waits on the exit");
        var frame = of(scene(board), J).rampFrame(13, 2);
        assertEquals(-1, facing(oneWay).dot(frame.nx(), frame.ny()), .0001f);
        float side = -frame.ny() * (oneWay.x() - frame.x()) + frame.nx() * (oneWay.y() - frame.y());
        assertTrue(side < -frame.outer(), "On the right of traffic turning into the exit");
    }

    @Test
    void signalledJunctionsLightTheEntranceAndKeepTheRampSignsClearOfTheirPoles() {
        Board board = ramp(false, HexAppearance.TrafficControl.GANTRY, 16);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var noEntry = sign(controls, "no-entry-sign");
        assertTrue(controls.stream().anyMatch(feature -> feature.decoration().id().endsWith(":stop:2")
              && feature.asset().startsWith("scenery/road/traffic-signal")), "The entrance has its own lights");
        for (var other : controls) {
            if (other != noEntry) {
                assertTrue(Math.hypot(other.x() - noEntry.x(), other.y() - noEntry.y()) >= 4, "Clear of " + other.asset());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void aRoadNarrowingToOneLaneSignsItsWay(boolean exit) {
        Board board = drop(exit);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        sign(controls, exit ? "one-way-sign" : "no-entry-sign");
        assertEquals(1, controls.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void aCurvingNarrowingStandsItsSignBesideTheCourseTheSceneDraws(boolean exit) {
        // J bends from the south into a single lane leaving north-east; both neighbours bend on too.
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J, 8 | 2, false, null, null);
        road(board, J.translated(3), 1 | 16, false, null, null);
        road(board, J.translated(3).translated(4), 2 | 8, false, null, null);
        road(board, J.translated(1), 16 | 1, true, exit ? 16 : 1, null);
        road(board, J.translated(1).translated(0), 8 | 1, true, exit ? 8 : 1, null);
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var sign = sign(controls, exit ? "one-way-sign" : "no-entry-sign");
        var drawn = of(scene(board), J);
        float clear = drawn.distance(sign.x(), sign.y());
        assertTrue(clear > .4f && clear < 4, "Beside the drawn asphalt, not on it or far from it: " + clear);
    }

    /** How far across a road's arm at {@code share} of the way to its border its asphalt reaches on either side. */
    private static float[] reach(BoardRoad road, int direction, float share) {
        float[] gate = RailRoute.border(direction);
        Vector2 across = new Vector2(axis(direction)).rotate90(1);
        float[] result = new float[2];
        for (int sign = 0; sign < 2; sign++) {
            float offset = 0;
            while (offset < 40 && road.distance(gate[0] * share + across.x * offset * (sign * 2 - 1),
                  gate[1] * share + across.y * offset * (sign * 2 - 1)) < 0) { offset += .05f; }
            result[sign] = offset;
        }
        return result;
    }

    @Test
    void aRampsMouthIsTheSameWhicheverSideOfTheRoadItLeaves() {
        Board east = Board.createEmptyBoard(9, 9), west = Board.createEmptyBoard(9, 9);
        road(east, J, 9 | 4, false, null, null);
        road(east, J.translated(2), 4 | 32, true, 32, null);
        road(west, J, 9 | 16, false, null, null);
        road(west, J.translated(4), 16 | 2, true, 2, null);
        float[] a = reach(of(scene(east), J), 2, .75f), b = reach(of(scene(west), J), 4, .75f);
        assertEquals(a[0] + a[1], b[0] + b[1], .5f, "Mirrored ramps open alike");
        assertTrue(Math.max(a[0], a[1]) < 11, "Its corners widen gradually, not to two lanes at once: " + a[0] + ", " + a[1]);
    }

    @Test
    void aSingleLaneMeetingADirtRoadStillArrowsItsWay() {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(0), "road:3:9", false, null, null);
        road(board, J, 9, true, 1, null);
        road(board, J.translated(3), 9, true, 1, null);
        var lane = of(scene(board), J);
        assertEquals(8, lane.single());
        assertEquals(1, lane.inbound(), "Its way in from the dirt road is kept, though that arm keeps the dirt road's width");
        Vector2 south = axis(3);
        assertTrue(lane.arrows(9).contains(south.x * 2 + .1f, south.y * 2), "Its arrow points south, the way its traffic runs");
        assertEquals(1, BoardFeatures.inbound(board.getHex(J), J, board::getHex, 8));
    }

    @Test
    void aTightTurnIntoASingleLaneKeepsItsTwoLaneHalfsDivider() {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J.translated(0), 9, false, null, null);
        road(board, J, 1 | 2, false, null, null);
        road(board, J.translated(1), 16 | 2, true, 16, null);
        var divider = of(scene(board), J).markings(J, 3);
        float[] north = RailRoute.border(0);
        boolean found = false;
        for (float share = .6f; share <= 1; share += .01f) { found |= divider.contains(north[0] * share, north[1] * share); }
        assertTrue(found, "The divider reaches the two-lane border");
    }

    @Test
    void nothingWaitsAtTheRailsOnAnExit() {
        int rails = 2 | 16;
        var exit = BoardRoad.layout(J, 13, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED, 4, false).flow(0);
        assertTrue(exit.crossing(13, rails, RailRoute.pieces(rails)).approaches().stream().noneMatch(a -> a.direction() == 2));
    }

    @Test
    void sceneAndCapturedHexesAgreeOnLanesAndDirection() {
        for (Board board : List.of(ramp(true, null, 0), ramp(false, null, 16), drop(true), drop(false), exitTakenFrom(0, null),
              exitTakenFrom(3, HexAppearance.TrafficControl.SIGNALS), straightExit(1 << 1), straightExit(1 << 3))) {
            var scene = scene(board);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    Coords at = new Coords(x, y);
                    Hex hex = board.getHex(at);
                    if (!hex.containsTerrain(Terrains.ROAD)) { continue; }
                    int exits = hex.getTerrain(Terrains.ROAD).getExits() & 63;
                    int single = BoardFeatures.singleArms(hex, at, board::getHex, exits);
                    assertEquals(BoardRoad.singleArms(scene, scene.tile(at)), single, at.toString());
                    assertEquals(BoardRoad.inbound(scene, scene.tile(at), single), BoardFeatures.inbound(hex, at, board::getHex, single),
                          at.toString());
                    assertEquals(SingleLane.closedTurns(BoardRoad.lanes(scene), at), SingleLane.closedTurns(hex, at, board::getHex),
                          at.toString());
                }
            }
        }
    }

    @Test
    void aSingleLaneMeetsLooseRoadsAtTheirWidthAndRailsKeepTwoLanes() {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J, 9, true, 1, null);
        road(board, J.translated(0), "road:3:9", false, null, null);
        var scene = scene(board);
        var lane = of(scene, J);
        assertEquals(8, lane.single(), "Its arm onto the dirt road keeps the dirt road's width");
        Vector2 across = new Vector2(axis(0)).rotate90(1);
        float[] north = RailRoute.border(0);
        assertEquals(0, lane.distance(north[0] + across.x * 15, north[1] + across.y * 15), .1f);
        assertEquals(0, of(scene, J.translated(0)).single(), "And the dirt road keeps it on its side");

        road(board, J, "road:1:9;rail:1:9", true, 1, null);
        assertFalse(SingleLane.single(board.getHex(J)), "A road shared with a railway keeps two lanes");
        assertFalse(BoardRoad.singleLane(scene(board).tile(J)));
    }

    @Test
    void changingALaneChangesTheGroundShapesOfItsNeighbours() {
        Board single = ramp(true, null, 0);
        Board wide = ramp(true, null, 0);
        road(wide, S1, 4 | 32, false, null, null);
        assertNotEquals(BoardSurface.geometryKey(scene(single), scene(single).tile(J)),
              BoardSurface.geometryKey(scene(wide), scene(wide).tile(J)), "The cached ground under J is rebuilt");
    }

    @ParameterizedTest
    @ValueSource(strings = { "give-way-sign", "no-entry-sign", "one-way-sign", "no-left-turn-sign", "no-right-turn-sign",
          "keep-right-sign", "direction-sign-a", "direction-sign-r", "direction-sign-hl-hr", "direction-sign-hl-l-a-r-hr" })
    void signModelsShipANearAndAFarLevel(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        var levels = RigidGlb.loadLods(new FileHandle(root.resolve("scenery/road/" + asset + ".glb").toFile()), root)
              .stream().distinct().toList();
        assertEquals(2, levels.size(), "Near and far meshes, as the STOP sign has");
    }
    /** A single-lane hub at J, its arms single lanes running in across {@code entrances} and out across the rest of {@code exits}. */
    private static Board hub(int exits, int entrances) {
        Board board = Board.createEmptyBoard(9, 9);
        road(board, J, exits, true, entrances, null);
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            int back = (d + 3) % 6;
            road(board, J.translated(d), 1 << d | 1 << back, true, (entrances & 1 << d) != 0 ? 1 << d : 1 << back, null);
        }
        return board;
    }

    private static int rotate(int sides, int by) { return (sides << by | sides >> 6 - by) & 63; }

    /** The stop and signal ids J's controls carry, by their approach's side. */
    private static Set<Integer> signed(List<BoardScene.Feature> controls) {
        return controls.stream().map(feature -> feature.decoration().id()).filter(id -> id.contains(":road:stop:"))
              .map(id -> id.charAt(id.length() - 1) - '0').collect(Collectors.toSet());
    }

    /**
     * Every two-lane junction that one to three single lanes join, once for each shape: under its default controls and
     * under traffic lights, each stop or give-way line it paints has its sign or signal, which never stands on a lane
     * nothing waits in, and no overhead lights span a single lane.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "SIGNALS", "GANTRY" })
    void everyLineAJunctionPaintsHasItsSignOrSignal(String name) {
        var control = control(name);
        Set<Long> shapes = new java.util.HashSet<>();
        int layouts = 0;
        for (int code = 0; code < 4096; code++) {
            int wide = 0, exits = 0, entrances = 0;
            for (int d = 0, rest = code; d < 6; d++, rest /= 4) {
                switch (rest % 4) {
                    case 1 -> wide |= 1 << d;
                    case 2 -> entrances |= 1 << d;
                    case 3 -> exits |= 1 << d;
                    default -> { }
                }
            }
            int singles = Integer.bitCount(exits | entrances), all = wide | exits | entrances;
            if (singles == 0 || singles > 3 || Integer.bitCount(wide) > (control == null ? 4 : 3) || Integer.bitCount(all) < 3) {
                continue;
            }
            long shape = Long.MAX_VALUE;
            for (int by = 0; by < 6; by++) {
                shape = Math.min(shape, (long) rotate(wide, by) << 12 | rotate(exits, by) << 6 | rotate(entrances, by));
            }
            if (!shapes.add(shape)) { continue; }
            layouts++;
            Board board = ramps(wide, exits, entrances, control);
            String layout = "two-lane " + wide + ", exits " + exits + ", entrances " + entrances;
            var road = of(scene(board), J);
            var lines = road.stopApproaches(all).stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet());
            var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
            assertEquals(lines, signed(controls), layout + ": a line for each sign, a sign for each line");
            for (var feature : controls) {
                String id = feature.decoration().id();
                if (feature.asset().endsWith("traffic-signal-gantry")) {
                    assertEquals(0, (exits | entrances) & 1 << id.charAt(id.length() - 1) - '0', layout + ": a pole over a single lane");
                }
            }
        }
        assertTrue(layouts > 150, "Every shape: " + layouts);
    }

    @Test
    void aBendThatTurnsCrossKeepsABrokenCentreLine() {
        // North and south-east two-lane, entrances south and north-west: the bend keeps priority, and turns cross its line.
        int exits = 1 | 4 | 8 | 32;
        var road = of(scene(ramps(1 | 4, 0, 8 | 32, null)), J);
        assertEquals(1 | 4, road.priorityRoad(exits));
        var bend = BoardRoad.layout(J, 1 | 4, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.PAVED);
        int line = painted(road.markings(J, exits)), solid = painted(bend.markings(J, 1 | 4));
        assertTrue(line > 0 && line < .8 * solid, "Broken through the junction, not solid as round a plain bend: " + line + " of " + solid);
    }

    /** The samples of {@code area} within 30 pixels of the hex's centre, half a pixel apart: its arms' paint and its hub's. */
    private static int painted(java.awt.geom.Area area) {
        int result = 0;
        for (float x = -30; x <= 30; x += .5f) {
            for (float y = -30; y <= 30; y += .5f) {
                if (x * x + y * y <= 900 && area.contains(x, y)) { result++; }
            }
        }
        return result;
    }

    @Test
    void aRoadDividingIntoCarriagewaysHasAKeepRightSignAndNoControls() {
        // North two-lane, dividing into an entrance south-west and an exit north-west on its right.
        Board board = ramps(1, 32, 16, null);
        var road = of(scene(board), J);
        assertTrue(road.stopApproaches(1 | 16 | 32).isEmpty());
        assertTrue(road.closed(4, 5), "No U-turn round the nose");
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        var keep = sign(controls, "keep-right-sign");
        assertFalse(road.footprint(0).contains(keep.x(), keep.y()), "On the nose, off the road");
        Vector2 out = new Vector2(keep.x(), keep.y()).nor();
        assertTrue(facing(keep).dot(out) < -.9f, "Facing the junction's traffic heading out past it");
    }

    @Test
    void singleLanesMergingAtAHubGiveWay() {
        // A single-lane hub: south runs on north, south-west joins it.
        Board board = hub(1 | 8 | 16, 8 | 16);
        var road = of(scene(board), J);
        var waiting = road.stopApproaches(1 | 8 | 16);
        assertEquals(Set.of(4), waiting.stream().map(BoardRoad.StopApproach::direction).collect(Collectors.toSet()));
        assertTrue(waiting.getFirst().yields());
        var controls = BoardFeatures.roadControls(board.getHex(J), J, board::getHex, true);
        assertEquals("terrain:4_4:road:stop:4", sign(controls, "give-way-sign").decoration().id());
        assertEquals(2, controls.stream().filter(feature -> feature.asset().endsWith("no-entry-sign")).count(),
              "Either branch could be entered from the other");
        assertTrue(controls.stream().noneMatch(feature -> feature.asset().endsWith("one-way-sign")), "Every road there is one-way");
        assertEquals(HexAppearance.TrafficControl.STOPS, road.trafficControl(1 | 8 | 16, HexAppearance.TrafficControl.SIGNALS),
              "Never signalled");
        assertTrue(of(scene(hub(1 | 2 | 8, 8)), J).stopApproaches(1 | 2 | 8).isEmpty(), "A fork: nothing waits");
    }
}
