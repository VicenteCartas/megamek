/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import megamek.common.Hex;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;

class SingleLaneTest {
    private static final Coords J = new Coords(4, 4);

    /** A paved road hex with these exits, a single lane entered through {@code inbound} (null for none stored). */
    static Hex road(int exits, boolean single, Integer inbound) {
        Hex hex = new Hex(0, "road:1:" + exits, "");
        if (single) { hex.setAppearance(Map.of("road", HexAppearance.withLanes(null, true, inbound))); }
        return hex;
    }

    /**
     * A two-lane north-south road through J, and a single-lane road leaving it south-east for three hexes, whose
     * traffic runs away from J (an exit) or towards it (an entrance); null stores no direction.
     */
    static Map<Coords, Hex> ramp(Boolean exit) {
        Map<Coords, Hex> board = new HashMap<>();
        board.put(J.translated(0), road(9, false, null));
        board.put(J, road(9 | 4, false, null));
        board.put(J.translated(3), road(9, false, null));
        Coords at = J;
        for (int i = 0; i < 3; i++) {
            at = at.translated(2);
            board.put(at, road(4 | 32, true, exit == null ? null : exit ? 32 : 4));
        }
        return board;
    }

    @Test
    void onlyPavedRoadsCarryASingleLane() {
        assertTrue(SingleLane.single(road(9, true, 1)));
        assertFalse(SingleLane.single(road(9, false, null)));
        for (String terrain : List.of("road:2:9", "road:3:9", "road:4:9")) {
            Hex hex = new Hex(0, terrain, "");
            hex.setAppearance(Map.of("road", HexAppearance.withLanes(null, true, 1)));
            assertFalse(SingleLane.single(hex), terrain + ": alleys and loose roads keep their width");
            assertFalse(SingleLane.paved(hex));
        }
        assertFalse(SingleLane.single(null));
    }

    @Test
    void storedDirectionIsReadWithinTheRoadsExits() {
        var board = ramp(true);
        Function<Coords, Hex> at = board::get;
        Coords first = J.translated(2);
        assertEquals(32, SingleLane.inbound(board.get(first), first, at), "Traffic enters the exit ramp from J");
        assertEquals(0, SingleLane.inbound(board.get(J), J, at), "A two-lane road stores no direction");
        Hex stray = road(4 | 32, true, 32 | 1);
        assertEquals(32, SingleLane.inbound(stray, first, at), "Sides without a road exit are ignored");
    }

    @Test
    void joinsReadEntranceOrExitFromTheSingleLaneNeighbour() {
        for (boolean exit : new boolean[] { true, false }) {
            var board = ramp(exit);
            assertEquals(4, SingleLane.joined(board.get(J), J, board::get), "The ramp joins J on its south-east side");
            assertEquals(!exit, SingleLane.entrance(board.get(J), J, 2, board::get));
            assertFalse(SingleLane.entrance(board.get(J), J, 0, board::get), "A two-lane neighbour is no ramp");
            assertFalse(SingleLane.entrance(board.get(J), J, 1, board::get), "No road joins on that side");
        }
    }

    @Test
    void unstoredDirectionsRunOneWayAlongTheWholeRun() {
        var board = ramp(null);
        Coords first = J.translated(2), second = first.translated(2), third = second.translated(2);
        // Nothing stored anywhere: the run runs from its end with the least coordinates, every hex of it alike.
        for (Coords at : List.of(first, second, third)) {
            assertEquals(32, SingleLane.inbound(board.get(at), at, board::get), at + " runs away from J");
        }
        assertFalse(SingleLane.entrance(board.get(J), J, 2, board::get), "So the ramp is J's exit");
        // One stored hex turns the rest of its run with it.
        board.put(second, road(4 | 32, true, 4));
        for (Coords at : List.of(first, second, third)) {
            assertEquals(4, SingleLane.inbound(board.get(at), at, board::get), at + " runs towards J");
        }
        assertTrue(SingleLane.entrance(board.get(J), J, 2, board::get));
        assertEquals(1 | 8, SingleLane.inbound(road(9, true, 1 | 8), J, board::get), "A stored direction is kept as it is");
    }

    @Test
    void unstoredBendsAgreeAcrossTheirBorders() {
        Map<Coords, Hex> board = new HashMap<>();
        Coords a = J, b = J.translated(2);
        board.put(a, road(1 | 4, true, null));
        board.put(b, road(32 | 4, true, null));
        int out = SingleLane.inbound(board.get(a), a, board::get), in = SingleLane.inbound(board.get(b), b, board::get);
        assertEquals((out & 4) == 0, (in & 32) != 0, "Traffic leaving one by their border enters the other");
    }

    @Test
    void adjacentForksThatStoreNothingAgreeAcrossTheirBorder() {
        Map<Coords, Hex> board = new HashMap<>();
        Coords f1 = J, f2 = J.translated(2);
        board.put(f1, road(1 | 4 | 8, true, null));
        board.put(f2, road(32 | 2 | 8, true, null));
        board.put(f1.translated(0), road(9, true, 1));
        board.put(f1.translated(3), road(9, true, 1));
        board.put(f2.translated(1), road(2 | 16, true, 2));
        board.put(f2.translated(3), road(9, true, 1));
        int one = SingleLane.inbound(board.get(f1), f1, board::get), two = SingleLane.inbound(board.get(f2), f2, board::get);
        assertTrue(((one & 4) != 0) != ((two & 32) != 0), "Exactly one of them sends traffic across: " + one + ", " + two);
    }

    @Test
    void aRunEndingOnADeckOrTheBoardsEdgeStillHasAWayIn() {
        Map<Coords, Hex> board = new HashMap<>();
        Coords first = J, second = J.translated(3);
        // North of the first hex is a bridge deck (no ground road), south of the second the two-lane road it joins.
        board.put(first, road(9, true, null));
        board.put(second, road(9, true, null));
        board.put(second.translated(3), road(9, false, null));
        var run = SingleLane.run(board::get, first, -1);
        assertEquals(0, run.steps().getFirst().back(), "The run starts by its open north side");
        assertEquals(1, run.steps().getFirst().inbound(true), "So running forwards, traffic enters there");
        assertEquals(1, SingleLane.inbound(board.get(first), first, board::get));
        assertTrue(SingleLane.entrance(board.get(second.translated(3)), second.translated(3), 0, board::get),
              "Its traffic runs on into the two-lane road: an entrance...");
        board.put(first, road(9, true, 8));
        board.put(second, road(9, true, 8));
        assertEquals(8, SingleLane.inbound(board.get(first), first, board::get), "Stored the other way, it leaves by the deck");
        assertFalse(SingleLane.entrance(board.get(second.translated(3)), second.translated(3), 0, board::get), "...or an exit");
    }

    @Test
    void anOffRampSplittingIntoTwoSingleLanesIsStillAnExit() {
        Map<Coords, Hex> board = new HashMap<>();
        board.put(J.translated(0), road(9, false, null));
        board.put(J, road(1 | 4 | 8, false, null));
        board.put(J.translated(3), road(9, false, null));
        Coords fork = J.translated(2);
        board.put(fork, road(32 | 4 | 8, true, null));
        board.put(fork.translated(2), road(32 | 4, true, 32));
        board.put(fork.translated(3), road(1 | 8, true, 1));
        assertEquals(32, SingleLane.inbound(board.get(fork), fork, board::get),
              "Both its branches run away from the fork, so its traffic comes in from J");
        assertFalse(SingleLane.entrance(board.get(J), J, 2, board::get), "An exit, not an entrance");
        board.put(fork.translated(2), road(32 | 4, true, 4));
        board.put(fork.translated(3), road(1 | 8, true, 8));
        assertEquals(4 | 8, SingleLane.inbound(board.get(fork), fork, board::get), "Two merging lanes run on into J");
        assertTrue(SingleLane.entrance(board.get(J), J, 2, board::get));
    }

    @Test
    void runsStopAtTwoLaneRoadsAndForksAndKeepOneOrder() {
        var board = ramp(true);
        Coords first = J.translated(2), second = first.translated(2), third = second.translated(2);
        var fromJ = SingleLane.run(board::get, first, 5);
        assertNotNull(fromJ);
        assertEquals(List.of(first, second, third), fromJ.steps().stream().map(SingleLane.Step::at).toList());
        assertEquals(5, fromJ.steps().getFirst().back(), "The run starts across J's border");
        assertEquals(2, fromJ.steps().getLast().ahead(), "It ends by its open side, where its road stops");
        for (Coords start : List.of(first, second, third)) {
            var whole = SingleLane.run(board::get, start, -1);
            assertEquals(3, whole.steps().size());
            assertEquals(fromJ.steps().stream().map(SingleLane.Step::at).toList().reversed().equals(
                  whole.steps().stream().map(SingleLane.Step::at).toList())
                  || fromJ.steps().stream().map(SingleLane.Step::at).toList().equals(
                        whole.steps().stream().map(SingleLane.Step::at).toList()), true,
                  "Every hex of a run finds the same run");
            assertEquals(whole.steps(), SingleLane.run(board::get, first, -1).steps(), "In the same order");
        }
        assertTrue(SingleLane.forward(board::get, fromJ, at -> true), "Stored as an exit, it runs away from J");
        assertNull(SingleLane.run(board::get, J, -1), "A two-lane road is no run");
        // A fork: three single-lane sides stop the run before it.
        board.put(second, road(4 | 32 | 1, true, 32));
        board.put(second.translated(0), road(8, true, 8));
        assertEquals(List.of(first), SingleLane.run(board::get, first, 5).steps().stream().map(SingleLane.Step::at).toList());
    }

    @Test
    void votesOfTheHexesARunAlreadyHadOutweighAStrokesOwn() {
        var board = ramp(true);
        Coords first = J.translated(2), second = first.translated(2), third = second.translated(2);
        var run = SingleLane.run(board::get, first, 5);
        // Run backwards, traffic enters the end by its open side.
        board.put(third, road(4 | 32, true, 4));
        assertTrue(SingleLane.forward(board::get, run, at -> !at.equals(third)), "Two old hexes outvote a new one");
        assertFalse(SingleLane.forward(board::get, run, at -> at.equals(third)), "One old hex outvotes two new ones");
        board.put(first, road(4 | 32, true, 4));
        board.put(second, road(4 | 32, true, 4));
        assertFalse(SingleLane.forward(board::get, run, at -> true));
    }

    @Test
    void appearanceValidatesAndRoundTripsLanes() {
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, null, null, null, null, null, null,
              null, null, null, 2, null, null), "Two lanes are the default, stored as none");
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null, null, null, null,
              null, null, null, null, null, 1, null), "A direction needs a single lane");
        assertThrows(IllegalArgumentException.class, () -> HexAppearance.withLanes(null, true, 64));
        assertThrows(IllegalArgumentException.class, () -> HexAppearance.withLanes(null, true, 1).validateOwner("bridgeRoad"),
              "Bridge decks keep two lanes");
        HexAppearance.withLanes(null, true, 1).validateOwner("road");
        var asphalt = new HexAppearance(null, null, "asphalt", null);
        var single = HexAppearance.withLanes(asphalt, true, 8);
        assertEquals("asphalt", single.material());
        assertEquals(Integer.valueOf(1), single.lanes());
        assertEquals(Integer.valueOf(8), single.inbound());
        assertEquals(asphalt, HexAppearance.withLanes(single, false, 8), "Two lanes drop the direction");
        assertNull(HexAppearance.withLanes(HexAppearance.withLanes(null, true, 1), false, null), "Nothing else stored, nothing kept");
        var controlled = HexAppearance.withTrafficControl(single, HexAppearance.TrafficControl.SIGNALS);
        assertEquals(single.inbound(), controlled.inbound(), "Other edits keep the lanes");
        assertEquals(single.lanes(), HexAppearance.withTrafficControl(controlled, null).lanes());
        assertEquals(single.lanes(), HexAppearance.withRotation(single, 0).lanes());
        assertEquals(single.inbound(), HexAppearance.keepLanes(asphalt, single).inbound());
        assertEquals(asphalt, HexAppearance.keepLanes(asphalt, null));
    }

    @Test
    void lanesAndDirectionRoundTripThroughFilesClipboardAndSerialization() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "road:1:9", "");
        var look = HexAppearance.withTrafficControl(HexAppearance.withLanes(new HexAppearance(null, null, "asphalt", null),
              true, 1 | 8), HexAppearance.TrafficControl.SIGNALS);
        hex.setAppearance(Map.of("road", look));
        board.setHex(0, 0, hex);
        var out = new java.io.ByteArrayOutputStream();
        BoardFile.write(board, out);
        String saved = out.toString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s", "");
        assertTrue(saved.contains("\"lanes\":1") && saved.contains("\"inbound\":[\"n\",\"s\"]"), saved);
        assertEquals(hex.getAppearance(), BoardFile.readNative(saved).getHex(0, 0).getAppearance());
        assertEquals(hex.getAppearance(), BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance());
        var widened = BoardFile.readNative(saved.replace("\"lanes\":1", "\"lanes\":2")).getHex(0, 0).getAppearance().get("road");
        assertNull(widened.lanes(), "A saved two is the two-lane default");
        assertNull(widened.inbound(), "Which has no direction");
        assertEquals(HexAppearance.TrafficControl.SIGNALS, widened.trafficControl());
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("\"n\",\"s\"", "\"north\"")),
              "Sides are named n, ne, se, s, sw and nw");
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("\"lanes\":1", "\"lanes\":3")));
        var bytes = new java.io.ByteArrayOutputStream();
        try (var stream = new java.io.ObjectOutputStream(bytes)) { stream.writeObject(look); }
        try (var stream = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(look, stream.readObject(), "Games send it over the network as it is");
        }
    }

    /** {@code hex} storing, for each exit leaving it, the approaches that may take it (bit {@code 6 * exit + side}). */
    private static void takenFrom(Hex hex, long approaches) {
        hex.setAppearance(Map.of("road", HexAppearance.withExitFrom(hex.getAppearance().get("road"), approaches)));
    }

    @Test
    void anExitIsTakenByTheApproachesItsJunctionStoresElseByAll() {
        var board = ramp(true);
        assertEquals(1 | 8, SingleLane.feeds(board.get(J), J, 2, board::get), "Both directions of the road through J");
        assertEquals(0, SingleLane.closedTurns(board.get(J), J, board::get));
        takenFrom(board.get(J), 1L << 6 * 2);
        assertEquals(1, SingleLane.feeds(board.get(J), J, 2, board::get), "Only traffic from the north");
        long closed = SingleLane.closedTurns(board.get(J), J, board::get);
        assertEquals(1L << 6 * 2 + 3 | 1L << 6 * 3 + 2, closed, "The turn between the south arm and the exit, both ways round");
        assertEquals(0, SingleLane.feeds(board.get(J), J, 0, board::get), "No exit leaves north");
        takenFrom(board.get(J), 1L << 6 * 4);
        assertEquals(1 | 8, SingleLane.feeds(board.get(J), J, 2, board::get), "A choice for another side leaves this exit open");
        var entrance = ramp(false);
        takenFrom(entrance.get(J), 1L << 6 * 2);
        assertEquals(0, SingleLane.feeds(entrance.get(J), J, 2, entrance::get), "An entrance is taken by nobody at J");
        assertEquals(0, SingleLane.closedTurns(entrance.get(J), J, entrance::get), "Its stored approaches wait unused");
        var asphalt = new HexAppearance(null, null, "asphalt", null);
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null, null, null, null,
              null, null, null, null, 1, null, 1L), "A single lane has no approaches of its own");
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null, null, null, null,
              null, null, null, null, null, null, 1L << 36), "Six exits of six sides each");
        assertNull(HexAppearance.withExitFrom(null, 0L), "None stored is no appearance");
        assertEquals(Long.valueOf(4), HexAppearance.withExitFrom(null, 4L).exitFrom(), "They alone are an appearance");
        assertEquals(Long.valueOf(4), HexAppearance.withTrafficControl(HexAppearance.withExitFrom(asphalt, 4L), null).exitFrom(),
              "The road's other edits keep them");
        assertNull(HexAppearance.withLanes(HexAppearance.withExitFrom(asphalt, 4L), true, null).exitFrom(), "A single lane drops them");
        assertEquals(Long.valueOf(4), HexAppearance.keepLanes(asphalt, HexAppearance.withExitFrom(asphalt, 4L)).exitFrom());
    }

    @Test
    void aJunctionsChoiceNeverReachesAnotherJunctionItsSingleLaneJoins() {
        // Two parallel two-lane roads, Ja and Jb, linked by a one-hex single lane K; and, below it, both feeding a merge M.
        Coords ja = new Coords(2, 4), k = ja.translated(2), jb = k.translated(1);
        Map<Coords, Hex> board = new HashMap<>();
        board.put(ja, road(1 | 8 | 4, false, null));
        board.put(k, road(32 | 2, true, 32));
        board.put(jb, road(1 | 8 | 16, false, null));
        takenFrom(board.get(ja), 1L << 6 * 2);
        assertEquals(1, SingleLane.feeds(board.get(ja), ja, 2, board::get), "K is Ja's exit, taken from the north");
        assertEquals(0, SingleLane.feeds(board.get(jb), jb, 4, board::get), "And Jb's entrance");
        board.put(k, road(32 | 2, true, 2));
        assertEquals(0, SingleLane.feeds(board.get(ja), ja, 2, board::get), "Turned round, K is Ja's entrance");
        assertEquals(1 | 8, SingleLane.feeds(board.get(jb), jb, 4, board::get), "And Jb's exit, open to both of Jb's directions");
        assertEquals(0, SingleLane.closedTurns(board.get(jb), jb, board::get));
        board.put(k, road(32 | 2 | 8, true, 32 | 2));
        assertEquals(1, SingleLane.feeds(board.get(ja), ja, 2, board::get), "A merge both roads leave by keeps Ja's choice");
        assertEquals(1 | 8, SingleLane.feeds(board.get(jb), jb, 4, board::get), "And Jb its own");
    }

    @Test
    void exitApproachesRoundTripAndFlip() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "road:1:15", "");
        var look = HexAppearance.withExitFrom(new HexAppearance(null, null, "asphalt", null), 1L << 6 * 2 | 8L << 6 * 1);
        hex.setAppearance(Map.of("road", look));
        board.setHex(0, 0, hex);
        var out = new java.io.ByteArrayOutputStream();
        BoardFile.write(board, out);
        String saved = out.toString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s", "");
        assertTrue(saved.contains("\"exitFrom\":{\"ne\":[\"s\"],\"se\":[\"n\"]}"), saved);
        assertEquals(hex.getAppearance(), BoardFile.readNative(saved).getHex(0, 0).getAppearance());
        assertEquals(hex.getAppearance(), BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance());
        BoardUtilities.flip(board, true, false);
        assertEquals(Long.valueOf(1L << 6 * 4 | 8L << 6 * 5), HexAppearance.exitFrom(board.getHex(0, 0).getAppearance(), "road"),
              "The exits north-east and south-east become north-west and south-west, their approaches turning with them");
    }

    @Test
    void flippingTheBoardTurnsTheDirectionWithTheExits() {
        Board board = Board.createEmptyBoard(3, 3);
        Coords at = new Coords(1, 1);
        board.setHex(at, road(1 | 2, true, 2));
        BoardUtilities.flip(board, true, false);
        Hex flipped = board.getHex(at);
        int exits = flipped.getTerrain(megamek.common.units.Terrains.ROAD).getExits();
        assertEquals(1 | 32, exits, "North and north-east become north and north-west");
        assertEquals(Integer.valueOf(32), HexAppearance.inbound(flipped.getAppearance(), "road"), "The way in turns with them");
        for (int mask = 0; mask < 64; mask++) {
            for (boolean h : new boolean[] { false, true }) {
                for (boolean v : new boolean[] { false, true }) {
                    assertEquals(mask, SingleLane.flipSides(SingleLane.flipSides(mask, h, v), h, v));
                }
            }
        }
    }
}
