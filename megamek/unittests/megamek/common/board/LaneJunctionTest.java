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
        // Only an exit leaves: the hairpin's own course settles its traffic, and its centre line runs on through.
        var exit = junction("N=w SW=out NW=w");
        assertEquals(0, exit.priority());
        assertEquals(LaneJunction.State.NONE, exit.state());
        assertEquals(N | NW, exit.road());
        assertTrue(exit.crossesRoad(N | NW), "Turning from the north into the exit crosses it: broken");
        // A one-way road running straight on through the junction past the hairpin keeps priority, as a road through does.
        var through = junction("N=w NE=w SE=in NW=out");
        assertEquals(SE | NW, through.priority());
        assertEquals(N | NE, through.waiting() & (N | NE));
    }

    @Test
    void aBendThatOnlyExitsLeaveNeedsNoControls() {
        // North and south-west two-way, an exit south: turning traffic gives way to oncoming traffic on the bend, so
        // nothing needs controls, and the bend runs its centre line on through, broken where turns cross it.
        var bend = junction("N=w S=out SW=w");
        assertEquals(LaneJunction.State.NONE, bend.state());
        assertEquals(N | SW, bend.road());
        assertEquals(0, bend.signalled(), "Not even under traffic lights");
        // An entrance joining the same bend makes it a priority road the entrance gives way to.
        var joined = junction("N=w S=out SW=w NW=in");
        assertEquals(LaneJunction.State.CONTROLLED, joined.state());
        assertEquals(NW, joined.waiting());
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
        // A two-way road carrying on as a one-way street opposite, with a side street beside it, may run on no side: only a
        // warning, in case they are its two carriageways.
        assertEquals(List.of(LaneJunction.Kind.REVERSED_PAIR), kinds(junction("N=w SE=out S=in")));
        assertEquals(List.of(LaneJunction.Kind.REVERSED_PAIR), kinds(junction("N=w S=out SW=in")));
        assertTrue(junction("N=w NE=w SE=out S=in").issues().isEmpty(), "Beside more roads, a side street and a one-way road");
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

    /**
     * Every layout of up to six arms, two-lane and hub, keeps the rules: with its default closures and, at a two-lane
     * junction, every choice of which two-way approaches may take each exit (SingleLane#feeds).
     */
    @Test
    void everyLayoutKeepsTheRules() {
        int checked = 0, restricted = 0;
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
            if (two == 0) { checkRules(LaneJunction.of(arms, arms, in, 0, true), 0, "hub " + code); }
            for (long feeds : feedChoices(two, out)) {
                long closed = feeds | LaneJunction.noseTurns(arms, two, in, out);
                checkRules(LaneJunction.of(arms, in | out, in, closed, false), closed, code + " closed " + closed);
                checked++;
                if (feeds != 0) { restricted++; }
            }
        }
        assertTrue(checked > 4000 && restricted > 1000, checked + " layouts, " + restricted + " with restricted exits");
    }

    /** Every choice of the two-way approaches that may take each exit, as closed turns: none closed first. */
    private static List<Long> feedChoices(int two, int out) {
        List<Long> result = new java.util.ArrayList<>(List.of(0L));
        if (Integer.bitCount(two) > 3 || Integer.bitCount(out) > 2) { return result; }
        for (int exit = 0; exit < 6; exit++) {
            if ((out & 1 << exit) == 0 || two == 0) { continue; }
            List<Long> next = new java.util.ArrayList<>();
            for (long before : result) {
                // A non-empty subset of the approaches takes it; the others' turns into it are closed.
                for (int taken = two; taken != 0; taken = taken - 1 & two) {
                    long closed = before;
                    for (int from = 0; from < 6; from++) {
                        if ((two & ~taken & 1 << from) != 0) { closed |= closing(from, exit); }
                    }
                    next.add(closed);
                }
            }
            result = next;
        }
        return result;
    }

    private static void checkRules(LaneJunction junction, long closed, String name) {
        int two = junction.twoWay(), in = junction.entrances();
        int priority = junction.priority(), waiting = junction.waiting();
        if (priority != 0) {
            assertEquals(2, Integer.bitCount(priority), name);
            int a = Integer.numberOfTrailingZeros(priority), b = Integer.numberOfTrailingZeros(priority & ~(1 << a));
            int gap = Math.min(Math.floorMod(a - b, 6), Math.floorMod(b - a, 6));
            assertNotEquals(1, gap, name + ": never a hairpin");
            assertTrue(junction.move(a, b) || junction.move(b, a), name + ": a road traffic follows");
            if ((priority & ~two) != 0 && Integer.bitCount(two) >= 2) {
                assertEquals(3, gap, name + ": only a one-way road straight through outranks two-way roads");
                assertTrue((priority & in) != 0 && (priority & junction.exits()) != 0, name);
            }
        }
        // Waiting means giving way to traffic that keeps priority.
        assertEquals(0, waiting & priority, name + ": the priority road waits for nothing");
        for (int side = 0; side < 6; side++) {
            if ((waiting & 1 << side) == 0) { continue; }
            assertNotEquals(0, junction.directions(side), name + ": nothing waits that cannot move on");
            assertNotEquals(0, junction.conflicting() & 1 << side, name + ": nothing waits that conflicts with nothing");
        }
        if (waiting != 0) { assertNotEquals(0, junction.conflicting() & ~waiting, name + ": somebody keeps priority"); }
        if (junction.nose() >= 0) { assertFalse(junction.controlled(), name + ": a split needs no controls"); }
        if (in == 0 && Integer.bitCount(two) == 2) { assertFalse(junction.controlled(), name + ": only exits leave a two-way road"); }
        // A stranded arm is exactly one traffic arrives along and cannot leave.
        int stranded = junction.issues().stream()
              .filter(issue -> issue.kind() == LaneJunction.Kind.STRANDED || issue.kind() == LaneJunction.Kind.DEAD_END_ROAD)
              .mapToInt(issue -> 1 << issue.side()).reduce(0, (x, y) -> x | y);
        for (int side = 0; side < 6; side++) {
            boolean arrives = ((two | in) & 1 << side) != 0;
            assertEquals(arrives && junction.directions(side) == 0, (stranded & 1 << side) != 0, name + " side " + side);
            var sign = junction.turnSign(side);
            int kept = junction.directions(side), lost = 0;
            for (int to = 0; to < 6; to++) {
                if (to != side && (closed & 1L << 6 * side + to) != 0 && (two & 1 << side) != 0) {
                    lost |= 1 << Math.floorMod(to - side, 6) - 1;
                }
            }
            // A sign forbids only closed turns and shows exactly the open ones; a restricted two-way approach is signed.
            switch (sign) {
                case NO_LEFT -> assertTrue((kept & 3) == 0 && (lost & 3) != 0 && (lost & ~3) == 0, name);
                case NO_RIGHT -> assertTrue((kept & 24) == 0 && (lost & 24) != 0 && (lost & ~24) == 0, name);
                case DIRECTIONS -> assertNotEquals(0, kept, name);
                case NONE -> assertTrue(lost == 0 || kept == 0, name + ": a restricted approach is signed, side " + side);
            }
            for (int to = 0; to < 6; to++) {
                boolean open = junction.move(side, to);
                assertEquals(open, to != side && (kept & 1 << Math.floorMod(to - side, 6) - 1) != 0, name);
            }
        }
    }

    private static List<LaneJunction.Kind> kinds(LaneJunction junction) {
        return junction.issues().stream().map(LaneJunction.Issue::kind).collect(Collectors.toList());
    }
}
