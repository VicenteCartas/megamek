/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The junction rules where single-lane, one-way roads meet two-lane ones, checked against the rules of right-hand traffic
 * in continental Europe (the 1968 Vienna Conventions): layouts are written side by side, clockwise from north, each arm
 * two-way ({@code w}), an entrance ({@code in}) or an exit ({@code out}).
 */
class LaneJunctionTest {
    private static final List<String> SIDES = List.of("N", "NE", "SE", "S", "SW", "NW");
    private static final int N = 1, NE = 2, SE = 4, S = 8, SW = 16, NW = 32;

    /** A two-lane junction of "N=w SE=in NW=out", with its default U-turn closures and the {@code closed} turns. */
    private static LaneJunction junction(String layout, long closed) { return junction(layout, closed, false); }

    private static LaneJunction junction(String layout) { return junction(layout, 0, false); }

    /** A single-lane hub of "S=in SW=in N=out". */
    private static LaneJunction hub(String layout) { return junction(layout, 0, true); }

    private static LaneJunction junction(String layout, long closed, boolean narrow) {
        int two = 0, in = 0, out = 0;
        for (String arm : layout.split(" ")) {
            String[] parts = arm.split("=");
            int side = 1 << SIDES.indexOf(parts[0]);
            switch (parts[1]) {
                case "w" -> two |= side;
                case "in" -> in |= side;
                default -> out |= side;
            }
        }
        int arms = two | in | out;
        long turns = narrow ? 0 : closed | LaneJunction.noseTurns(arms, two, in, out);
        return LaneJunction.of(arms, narrow ? arms : in | out, in, turns, narrow);
    }

    /** Closes the turn from {@code from} into {@code to}, both ways round, as SingleLane#closedTurns stores it. */
    private static long closing(int from, int to) { return 1L << 6 * from + to | 1L << 6 * to + from; }

    @Test
    void aOneWayRoadThroughKeepsPriorityOverATwoWayStub() {
        // A one-way street running south-east to north-west past a two-way stub north: the stub joins it, so it waits,
        // and its only way on, the sharp right into the one-way street, is signed, since straight on lies a no-entry sign.
        var tee = junction("N=w SE=in NW=out");
        assertEquals(SE | NW, tee.priority());
        assertEquals(N, tee.waiting());
        assertFalse(tee.yields(0), "A two-way arm stops, as at any junction");
        assertEquals(LaneJunction.TurnSign.DIRECTIONS, tee.turnSign(0));
        assertEquals(1 << 4, tee.directions(0), "A hard right only");
        assertEquals(SE | NW, junction("N=w SE=in S=out NW=out").priority(), "An exit off the stub does not change that");
    }

    @Test
    void aTwoWayRoadContinuingOneWayNeedsNoControls() {
        // North two-way, on south as a one-way street towards it, exits either side: the road's own course settles every
        // conflict (turning traffic gives way to oncoming traffic).
        var road = junction("N=w SE=out S=in NW=out");
        assertEquals(LaneJunction.State.NONE, road.state());
        assertEquals(0, road.waiting());
    }

    @Test
    void twoWayRoadsKeepPriorityOverAnEntranceOnTheirLine() {
        // A bent two-lane road with a third two-lane road and an entrance: the bend has priority, never the entrance.
        var bend = junction("N=w S=in SW=w NW=w");
        assertEquals(N | SW, bend.priority());
        assertEquals(S | NW, bend.waiting());
        assertTrue(bend.yields(3), "The entrance gives way");
        assertFalse(bend.yields(5), "The two-way arm stops");
        var ramps = junction("N=w SE=w S=in NW=in");
        assertEquals(N | SE, ramps.priority());
        assertEquals(S | NW, ramps.waiting());
        assertTrue(ramps.crossesRoad(ramps.road()), "Turns cross its centre line, which is broken round the bend");
        assertTrue(junction("N=w SW=w NW=in").crossesRoad(N | SW));
    }

    @Test
    void noRoadHasPriorityWhereTwoWayRoadsTie() {
        // A Y of two-way roads: priority to the right among them. An exit leaves it so; an entrance gives way to them all.
        var y = junction("N=w SE=w SW=w NW=out");
        assertEquals(0, y.priority());
        assertEquals(LaneJunction.State.PRIORITY_RIGHT, y.state());
        assertEquals(0, y.waiting(), "No all-way stop: nobody would have priority");
        var entrance = junction("N=w SE=w SW=w NW=in");
        assertEquals(0, entrance.priority());
        assertEquals(NW, entrance.waiting());
        assertEquals(HexAppearance.TrafficControl.SIGNALS, entrance.defaultControl(), "Four approaches, no priority road");
        assertEquals(0, junction("N=w SE=w S=out SW=w NW=out").priority());
    }

    @Test
    void aHairpinIsNeverThePriorityRoad() {
        // Two-way roads meeting in a hairpin: an entrance between them gives way, never takes priority over them.
        var entrance = junction("N=w SW=in NW=w");
        assertEquals(0, entrance.priority());
        assertEquals(SW, entrance.waiting());
        var exit = junction("N=w SW=out NW=w");
        assertEquals(0, exit.priority());
        assertEquals(LaneJunction.State.PRIORITY_RIGHT, exit.state());
    }

    @Test
    void aBendWhoseOwnTrafficConflictsIsPriorityToTheRight() {
        // North and south-west two-way, an exit south both may take: neither follows the bend into it, so only signs on
        // the bend could say who goes first. Without them it is priority to the right, and no line runs on through.
        var bend = junction("N=w S=out SW=w");
        assertEquals(0, bend.priority());
        assertEquals(LaneJunction.State.PRIORITY_RIGHT, bend.state());
        assertEquals(0, bend.road());
    }

    @Test
    void aStraightRoadWithOnlyExitsOffItNeedsNoControls() {
        var road = junction("N=w SE=out S=w");
        assertEquals(LaneJunction.State.NONE, road.state());
        assertEquals(N | S, road.road());
        assertEquals(0, road.signalled(), "Not even under traffic lights");
        assertTrue(road.crossesRoad(N | S), "Southbound turns left into the exit across the centre line: broken");
        var restricted = junction("N=w SE=out S=w", closing(0, 2));
        assertFalse(restricted.crossesRoad(N | S));
        assertTrue(restricted.closedCrossing(N | S), "The turn it closes would cross it: solid, to keep traffic from it");
        assertEquals(LaneJunction.TurnSign.NO_LEFT, restricted.turnSign(0));
        assertFalse(junction("N=w SW=out S=w", closing(3, 4)).crossesRoad(N | S), "The right turn into it crosses nothing");
    }

    @Test
    void aRoadDividingIntoCarriagewaysNeedsNoControlsButAKeepRightSign() {
        // The entrance on the right of the exit, seen from the junction: traffic keeps right on both.
        var beside = junction("N=w SW=in NW=out");
        assertEquals(LaneJunction.State.NONE, beside.state());
        assertEquals(9, beside.nose(), "Between south-west and north-west");
        assertFalse(beside.move(4, 5), "No U-turn round the nose");
        assertEquals(5, junction("N=w SE=in S=out").nose());
        var y = junction("N=w SE=in SW=out");
        assertEquals(LaneJunction.State.NONE, y.state());
        assertEquals(6, y.nose(), "A Y: its nose south");
        assertFalse(y.move(2, 4));
        assertTrue(beside.issues().isEmpty() && y.issues().isEmpty());
    }

    @Test
    void carriagewaysRunningOnTheLeftAreReported() {
        assertEquals(List.of(LaneJunction.Kind.REVERSED_SPLIT), kinds(junction("N=w SW=out NW=in")));
        assertEquals(List.of(LaneJunction.Kind.REVERSED_SPLIT), kinds(junction("N=w SE=out SW=in")));
        assertTrue(junction("N=w SE=out SW=in").yields(4), "Its crossing is still signed");
        // A two-way road carrying on as a one-way street opposite, with a side street beside it, runs on no side.
        assertTrue(junction("N=w SE=out S=in").issues().isEmpty());
        assertEquals(List.of(LaneJunction.Kind.REVERSED_PAIR), kinds(junction("N=w S=w SW=out NW=in")));
    }

    @Test
    void layoutsThatStrandTrafficAreReported() {
        assertEquals(List.of(LaneJunction.Kind.DEAD_END_ROAD), kinds(junction("N=w S=in")), "Traffic from north can only turn back");
        assertEquals(List.of(LaneJunction.Kind.SOURCELESS_ROAD), kinds(junction("N=w S=out")));
        assertEquals(List.of(LaneJunction.Kind.DEAD_END_ROAD), kinds(junction("N=w SE=in SW=in")));
        assertEquals(Set.of(LaneJunction.Kind.STRANDED), Set.copyOf(kinds(hub("N=in SE=in SW=in"))), "A sink");
        assertEquals(Set.of(LaneJunction.Kind.UNFED_EXIT), Set.copyOf(kinds(hub("N=out SE=out SW=out"))), "A source");
        assertEquals(List.of(LaneJunction.Kind.TWO_LANE_HUB), kinds(junction("SW=in NW=out")));
        assertEquals(List.of(LaneJunction.Kind.UNFED_EXIT), kinds(junction("N=w SE=out S=w", closing(0, 2) | closing(3, 2))));
    }

    @Test
    void singleLanesMergingGiveWay() {
        // One branch running straight on: the other joins it and gives way.
        var straight = hub("N=out S=in SW=in");
        assertEquals(N | S, straight.priority());
        assertEquals(SW, straight.waiting());
        assertTrue(straight.yields(4));
        // Two equal branches: the one with the other on its right gives way, the one turning left.
        var y = hub("N=out SE=in SW=in");
        assertEquals(0, y.priority());
        assertEquals(SW, y.waiting());
        assertEquals(LaneJunction.State.NONE, hub("N=out NE=out S=in").state(), "A fork: nothing to control");
        assertEquals(0, hub("N=out S=in SW=in").signalled(), "A hub is never signalled");
        assertEquals(0, hub("N=out S=in SW=in").oneWay(), "Every road there is one-way: no one-way signs");
        assertEquals(S | SW, hub("N=out S=in SW=in").noEntry(), "Each branch could be entered from the other");
        assertEquals(0, hub("N=out S=in").noEntry());
    }

    @Test
    void aClosedTurnIsSignedAsExactlyAsItsWaysOnAllow() {
        // From the south: straight on into the exit north closed, a right on north-east kept.
        var straight = junction("N=out NE=w S=w", closing(3, 0));
        assertEquals(LaneJunction.TurnSign.DIRECTIONS, straight.turnSign(3), "No sign forbids only straight on");
        assertEquals(1 << 3, straight.directions(3));
        // From the south-west: the hard left into the exit north-west closed, the left into north kept. A no-left-turn
        // sign would forbid that too.
        var hard = junction("N=w NE=w SW=w NW=out", closing(4, 5));
        assertEquals(LaneJunction.TurnSign.DIRECTIONS, hard.turnSign(4));
        assertEquals(1 << 1 | 1 << 2, hard.directions(4), "A left and straight on");
        assertEquals(LaneJunction.TurnSign.NO_RIGHT, junction("N=w SE=out S=w", closing(3, 2)).turnSign(3));
        assertEquals(LaneJunction.TurnSign.NONE, junction("N=w SE=out S=w").turnSign(3));
    }

    @Test
    void controlsDefaultByTheJunctionsTraffic() {
        assertEquals(HexAppearance.TrafficControl.STOPS, junction("N=w SE=in S=w").defaultControl());
        assertEquals(HexAppearance.TrafficControl.GANTRY, junction("N=w NE=w S=w SW=w SE=in").defaultControl());
        assertEquals(HexAppearance.TrafficControl.STOPS, hub("N=out S=in SW=in").defaultControl());
        assertEquals(HexAppearance.TrafficControl.SIGNALS, junction("N=w NE=in SE=in S=w SW=in NW=out").defaultControl(),
              "Five approaches");
        assertEquals(HexAppearance.TrafficControl.STOPS, junction("N=w NE=out SE=out S=w SW=w").defaultControl(),
              "Exits bring no traffic: three approaches");
    }

    /** Every layout of up to six arms, two-lane and hub, keeps the rules' invariants. */
    @Test
    void everyLayoutKeepsTheRules() {
        for (int code = 0; code < 4096; code++) {
            int two = 0, in = 0, out = 0;
            for (int d = 0, rest = code; d < 6; d++, rest /= 4) {
                switch (rest % 4) {
                    case 1 -> two |= 1 << d;
                    case 2 -> in |= 1 << d;
                    case 3 -> out |= 1 << d;
                    default -> { }
                }
            }
            int arms = two | in | out;
            if (Integer.bitCount(arms) < 2 || (in | out) == 0) { continue; }
            for (boolean narrow : new boolean[] { false, true }) {
                if (narrow && two != 0) { continue; }
                long closed = narrow ? 0 : LaneJunction.noseTurns(arms, two, in, out);
                var junction = LaneJunction.of(arms, in | out, in, closed, narrow);
                String name = (narrow ? "hub " : "") + code;
                int priority = junction.priority(), waiting = junction.waiting();
                if (priority != 0) {
                    assertEquals(2, Integer.bitCount(priority), name);
                    int a = Integer.numberOfTrailingZeros(priority), b = Integer.numberOfTrailingZeros(priority & ~(1 << a));
                    assertNotEquals(1, Math.min(Math.floorMod(a - b, 6), Math.floorMod(b - a, 6)), name + ": never a hairpin");
                    if ((priority & ~two) != 0) { assertTrue(Integer.bitCount(two) <= 1, name + ": no one-way road over two-way roads"); }
                }
                assertEquals(0, waiting & ~junction.arriving(), name + ": only arriving traffic waits");
                assertEquals(0, waiting & priority, name + ": the priority road waits for nothing");
                if (waiting != 0) {
                    assertNotEquals(0, junction.conflicting() & ~waiting, name + ": somebody keeps priority");
                }
                if (!junction.controlled()) { assertEquals(0, waiting | junction.signalled(), name); }
                if (narrow || two == 0) { assertEquals(0, junction.signalled(), name + ": a hub is never signalled"); }
                if ((waiting & in) != 0) { assertTrue(junction.yields(Integer.numberOfTrailingZeros(waiting & in)), name); }
                if (junction.nose() >= 0) { assertFalse(junction.controlled(), name + ": a split needs no controls"); }
                int stranded = (two | in) & ~junction.arriving();
                assertEquals(stranded, junction.issues().stream()
                      .filter(issue -> issue.kind() == LaneJunction.Kind.STRANDED || issue.kind() == LaneJunction.Kind.DEAD_END_ROAD)
                      .mapToInt(issue -> 1 << issue.side()).reduce(0, (x, y) -> x | y), name);
                for (int side = 0; side < 6; side++) {
                    var sign = junction.turnSign(side);
                    if (sign == LaneJunction.TurnSign.DIRECTIONS) { assertNotEquals(0, junction.directions(side), name); }
                    if (sign == LaneJunction.TurnSign.NO_LEFT) { assertEquals(0, junction.directions(side) & 3, name); }
                    if (sign == LaneJunction.TurnSign.NO_RIGHT) { assertEquals(0, junction.directions(side) & 24, name); }
                }
            }
        }
    }

    private static List<LaneJunction.Kind> kinds(LaneJunction junction) {
        return junction.issues().stream().map(LaneJunction.Issue::kind).collect(Collectors.toList());
    }
}
