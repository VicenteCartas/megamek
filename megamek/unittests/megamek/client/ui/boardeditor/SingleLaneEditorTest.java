/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.SingleLane;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Single-lane, one-way roads in the editor: the brush, the inspector, ramps' entrance or exit and their runs. */
class SingleLaneEditorTest {
    @TempDir Path directory;
    private static final Coords J = new Coords(4, 4);
    private static final Coords S1 = J.translated(2), S2 = S1.translated(2), S3 = S2.translated(2);

    /** A two-lane road north to south through J, and a single-lane ramp drawn from J out to the south-east. */
    private static BoardEditorSession ramp() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J, J.translated(3));
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, S1, S2, S3);
        return session;
    }

    private static Integer inbound(BoardEditorSession session, Coords at) {
        return HexAppearance.inbound(session.board().getHex(at).getAppearance(), "road");
    }

    private static boolean single(BoardEditorSession session, Coords at) {
        return SingleLane.single(session.board().getHex(at));
    }

    @Test void aRampDrawnFromATwoLaneRoadIsItsExitAndLeavesItTwoLanes() {
        var session = ramp();
        assertFalse(single(session, J), "The stroke's start on the two-lane road keeps its two lanes");
        assertTrue(session.board().getHex(J).containsTerrainExit(Terrains.ROAD, 2), "J now leads onto the ramp");
        for (Coords at : List.of(S1, S2, S3)) { assertTrue(single(session, at), at + " is a single lane"); }
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "Traffic runs the way the ramp was drawn: in from J");
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S2));
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S3));
        inspect(session, J);
        var snapshot = session.snapshot();
        assertEquals(1 << 2, snapshot.rampSides(), "The inspector offers the ramp joining J on its south-east side");
        assertEquals(0, snapshot.entranceSides(), "As an exit (off-ramp)");
        assertFalse(snapshot.laneRun(), "J itself is no single-lane run");
    }

    @Test void choosingEntranceTurnsTheWholeRunInOneUndoStep() {
        var session = ramp();
        inspect(session, J);
        send(session, Action.RAMP, "road:2", "entrance");
        assertEquals("", session.snapshot().message());
        assertEquals(Integer.valueOf(1 << 2), inbound(session, S1), "Its traffic now comes in from the far end");
        assertEquals(Integer.valueOf(1 << 2), inbound(session, S2));
        assertEquals(Integer.valueOf(0), inbound(session, S3), "A dead end where the ramp begins lets no traffic in");
        assertEquals(1 << 2, session.snapshot().entranceSides());
        assertTrue(SingleLane.entrance(session.board().getHex(J), J, 2, session.board()::getHex));
        send(session, Action.UNDO, "", "");
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "One undo restores every hex of the run");
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S3));
        send(session, Action.REDO, "", "");
        assertEquals(Integer.valueOf(1 << 2), inbound(session, S2));
        send(session, Action.RAMP, "road:1", "exit");
        assertEquals(Messages.getString("BoardEditor.rampNone"), session.snapshot().message(), "No ramp joins there");
    }

    @Test void theTwoLaneHexChoosesWhichDirectionsMayTakeItsExit() {
        var session = ramp();
        inspect(session, J);
        assertEquals(Integer.valueOf(1 | 8), session.snapshot().exitFrom().get(2), "Both directions by default");
        send(session, Action.RAMP_FROM, "road:2", "0");
        assertEquals("", session.snapshot().message());
        assertEquals(Long.valueOf(1L << 6 * 2), HexAppearance.exitFrom(session.board().getHex(J).getAppearance(), "road"),
              "Stored on J itself, for its exit across the south-east side");
        assertNull(session.board().getHex(S1).getAppearance().get("road").exitFrom(), "Nothing on the ramp");
        assertEquals(Integer.valueOf(1), session.snapshot().exitFrom().get(2));
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "Its direction stays");
        send(session, Action.RAMP_FROM, "road:2", "all");
        assertNull(HexAppearance.exitFrom(session.board().getHex(J).getAppearance(), "road"), "All directions store none");
        send(session, Action.UNDO, "", "");
        assertEquals(Long.valueOf(1L << 6 * 2), HexAppearance.exitFrom(session.board().getHex(J).getAppearance(), "road"));
        send(session, Action.RAMP_FROM, "road:2", "2");
        assertEquals(Messages.getString("BoardEditor.rampFromApproach"), session.snapshot().message(), "Not one of J's approaches");
        send(session, Action.RAMP, "road:2", "entrance");
        send(session, Action.RAMP_FROM, "road:2", "0");
        assertEquals(Messages.getString("BoardEditor.rampFromNone"), session.snapshot().message(), "An entrance has none");
        send(session, Action.RAMP, "road:2", "exit");
        assertEquals(Integer.valueOf(1), session.snapshot().exitFrom().get(2), "Turned back into an exit, it keeps its choice");
        inspect(session, S2);
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        send(session, Action.BRUSH_VALUE, "lanes", "two");
        stroke(session, J.translated(0), J, J.translated(3));
        inspect(session, J);
        assertEquals(Integer.valueOf(1), session.snapshot().exitFrom().get(2),
              "Reversed and back, and the road through J painted again: still J's own choice");
    }

    @Test void aJunctionThatOnlyAnExitLeavesKeepsItsChosenControlsForLater() {
        var session = ramp();
        inspect(session, J);
        send(session, Action.TRAFFIC_CONTROL, "road", "gantry");
        var snapshot = session.snapshot();
        assertFalse(snapshot.junction().controlled(),
              "Only the exit leaves J: the inspector shows no controls");
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(session.board().getHex(J).getAppearance(), "road"),
              "But the choice is stored");
        send(session, Action.RAMP, "road:2", "entrance");
        snapshot = session.snapshot();
        assertTrue(snapshot.junction().controlled(),
              "An entrance joins: the choice applies again");
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(session.board().getHex(J).getAppearance(), "road"));
    }

    @Test void aCarParkBesideTheJunctionIsNoRoadOfIt() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "pavement", "1");
        stroke(session, J.translated(1));
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J, J.translated(3));
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, S1, S2, S3);
        inspect(session, J);
        // Roads connect along their strokes only: its side to the pavement is opened in Edit.
        send(session, Action.EDGE, "road", "1");
        send(session, Action.TRAFFIC_CONTROL, "road", "signals");
        var snapshot = session.snapshot();
        int exits = snapshot.property("road").exits() & 63;
        assertEquals(1 << 1, exits & 1 << 1, "The road runs into the pavement north-east");
        assertEquals(1 << 1, snapshot.apronSides(), "An apron");
        assertFalse(snapshot.junction().controlled(),
              "Only the exit leaves J: the inspector shows no controls, as the board draws none");
    }

    @Test void reversingFromAnyHexOfTheRunTurnsItAndItsJoin() {
        var session = ramp();
        inspect(session, S2);
        assertTrue(session.snapshot().laneRun());
        assertEquals(0, session.snapshot().rampSides(), "A single lane is no join of its own");
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        assertEquals(Integer.valueOf(1 << 2), inbound(session, S1));
        assertTrue(SingleLane.entrance(session.board().getHex(J), J, 2, session.board()::getHex), "J sees an entrance now");
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "And back");
        inspect(session, J);
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        assertEquals(Messages.getString("BoardEditor.laneRunNone"), session.snapshot().message());
    }

    @Test void extendingARunKeepsItsWayWhicheverWayTheExtensionIsDrawn() {
        var session = ramp();
        Coords s4 = S3.translated(2), s5 = s4.translated(2);
        // Drawn back towards the run: the run's own hexes outvote the stroke's.
        stroke(session, s5, s4, S3);
        assertEquals(Integer.valueOf(1 << 5), inbound(session, s4), "Traffic still runs away from J");
        assertEquals(Integer.valueOf(1 << 5), inbound(session, s5));
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S3));
    }

    @Test void aShortRampKeepsItsWayWhenALongerStrokeIsDrawnOntoIt() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J, J.translated(3));
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, S1);
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "A one-hex exit");
        Coords far = S1.translated(1), farther = far.translated(1), farthest = farther.translated(1);
        stroke(session, farthest, farther, far, S1);
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1), "The ramp it already was keeps its way");
        assertEquals(0, entrances(session, J), "J keeps its exit");
        assertEquals(Integer.valueOf(1 << 4), inbound(session, far), "The extension runs on the same way, away from J");
    }

    @Test void clickingASingleLaneWithTheBrushKeepsItsWay() {
        var session = ramp();
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, S2);
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S2));
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S1));
    }

    @Test void neighboursOfARampDoNotJoinItOrKeepStoredExits() {
        var session = new BoardEditorSession();
        Coords south = J.translated(3), further = south.translated(3);
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J, south, further);
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, S1, S2);
        Hex beside = session.board().getHex(south);
        assertEquals(1 | 8, beside.getTerrain(Terrains.ROAD).getExits(), "The two-lane road keeps the exits its stroke drew");
        assertFalse(beside.containsTerrainExit(Terrains.ROAD, south.direction(S1)), "It does not join the ramp it borders");
        assertEquals(1 << 5 | 1 << 2, session.board().getHex(S1).getTerrain(Terrains.ROAD).getExits(),
              "The ramp joins J and the next ramp hex alone");
        inspect(session, further);
        send(session, Action.REMOVE_COMPONENT, "", "road");
        assertFalse(session.board().getHex(south).containsTerrainExit(Terrains.ROAD, 3), "Nothing dangles where a road was removed");
    }

    @Test void anUnstoredRunKeepsTheWayItShowsWhenANeighbourIsEdited() throws Exception {
        var session = ramp();
        // A hand-written board: the run's lanes but no stored direction.
        for (Coords at : List.of(S1, S2, S3)) {
            Hex hex = session.board().getHex(at).duplicate();
            hex.setAppearance(java.util.Map.of("road", HexAppearance.withLanes(null, true, null)));
            session.board().setHex(at, hex);
        }
        int shown = SingleLane.inbound(session.board().getHex(S2), S2, session.board()::getHex);
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, S3.translated(1), S3.translated(1).translated(0));
        assertEquals(Integer.valueOf(shown), inbound(session, S2), "An edit beside it stores the way it already showed");
    }

    private static int entrances(BoardEditorSession session, Coords at) {
        int result = 0;
        for (int side = 0; side < 6; side++) {
            if (SingleLane.entrance(session.board().getHex(at), at, side, session.board()::getHex)) { result |= 1 << side; }
        }
        return result;
    }

    @Test void theInspectorNarrowsOrWidensAHexAndANewSingleLaneFollowsItsNeighbours() {
        var session = ramp();
        inspect(session, S2);
        send(session, Action.LANES, "road", "two");
        assertFalse(single(session, S2));
        assertNull(inbound(session, S2), "Two lanes drop the direction");
        send(session, Action.LANES, "road", "single");
        assertTrue(single(session, S2));
        assertEquals(Integer.valueOf(1 << 5), inbound(session, S2), "It rejoins its run's way");
        send(session, Action.UNDO, "", "");
        assertFalse(single(session, S2), "One undo step");
        inspect(session, J.translated(0));
        send(session, Action.TERRAIN, "road", "3");
        send(session, Action.LANES, "road", "single");
        assertEquals(Messages.getString("BoardEditor.lanesPaved"), session.snapshot().message(), "Only a paved road narrows");
    }

    @Test void changingARoadToDirtDropsItsLanes() {
        var session = ramp();
        inspect(session, S2);
        send(session, Action.TERRAIN, "road", "3");
        assertNull(session.board().getHex(S2).getAppearance().get("road") == null ? null
              : session.board().getHex(S2).getAppearance().get("road").lanes(), "A dirt road has no lanes stored");
        send(session, Action.TERRAIN, "road", "1");
        assertFalse(single(session, S2), "Back to paved, it is a two-lane road again");
    }

    @Test void lanesAndDirectionSurviveSaveAndLoad() throws Exception {
        var session = ramp();
        Path file = directory.resolve("ramp.board2");
        session.save(file);
        var board = BoardFile.read(file);
        for (Coords at : List.of(S1, S2, S3)) {
            Hex hex = board.getHex(at);
            assertTrue(SingleLane.single(hex), at.toString());
            assertEquals(inbound(session, at), HexAppearance.inbound(hex.getAppearance(), "road"));
        }
        assertFalse(SingleLane.single(board.getHex(J)));
        String saved = java.nio.file.Files.readString(file).replaceAll("\\s", "");
        assertTrue(saved.contains("\"lanes\":1") && saved.contains("\"inbound\":[\"nw\"]"), "Sides are stored by name");
    }

    private static void inspect(BoardEditorSession session, Coords at) {
        send(session, Action.TOOL, "", "SELECT"); stroke(session, at); session.release();
    }

    private static void stroke(BoardEditorSession session, Coords... cells) {
        for (int i = 0; i < cells.length; i++) { session.pointer(cells[i], 0, 0, i > 0, null, false, "ground"); }
        session.finishStroke();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
    /** The issues the editor reports at {@code at}. */
    private static List<String> issuesAt(BoardEditorSession session, Coords at) {
        return session.snapshot().issues().stream().filter(issue -> issue.coords().equals(at)).map(megamek.common.board.HexIssue::text)
              .toList();
    }

    private static boolean reported(BoardEditorSession session, Coords at, String kind, Object... sides) {
        String text = Messages.getString("BoardEditor.issueLane." + kind, sides);
        return issuesAt(session, at).stream().anyMatch(issue -> issue.startsWith(text));
    }

    @Test void aLaneEndingInADeadEndIsReportedUntilItRunsTheOtherWay() {
        var session = ramp();
        assertTrue(reported(session, S3, "DEAD_END"), "The exit ends three hexes on: its traffic cannot leave");
        inspect(session, J);
        send(session, Action.RAMP, "road:2", "entrance");
        assertFalse(reported(session, S3, "DEAD_END"), "Re-checked at the run's far end, though the edit was at J");
        assertTrue(reported(session, S3, "FROM_NOWHERE"));
        assertTrue(issuesAt(session, J).isEmpty(), "A T with its entrance: nothing wrong at J");
        send(session, Action.UNDO, "", "");
        assertTrue(reported(session, S3, "DEAD_END"), "Undone, and reported again");
    }

    @Test void carriagewaysRunningOnTheLeftAreReportedAndNeverRefused() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J);
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, J.translated(2), J.translated(2).translated(2));
        stroke(session, J, J.translated(4), J.translated(4).translated(4));
        assertTrue(reported(session, J, "SOURCELESS_ROAD", "N"), "Only exits leave the two-lane road: nothing feeds it");
        inspect(session, J);
        send(session, Action.RAMP, "road:4", "entrance");
        assertEquals("", session.snapshot().message(), "Not refused");
        assertTrue(reported(session, J, "REVERSED_SPLIT", "SE", "SW"), "Southbound traffic would run on the east carriageway");
        send(session, Action.RAMP, "road:2", "entrance");
        send(session, Action.RAMP, "road:4", "exit");
        assertTrue(issuesAt(session, J).isEmpty(), "Divided the right way round");
        var junction = session.snapshot().junction();
        assertEquals(megamek.common.board.LaneJunction.State.NONE, junction.state(), "Nothing to control: no row to choose");
        assertEquals(6, junction.nose(), "A keep-right sign on its nose");
    }
    @Test void reversingARunBeyondAForkReChecksTheJunctionOnItsFarSide() {
        // J: a two-lane road north, an entrance from the south-west, and a single-lane fork F south-east of it whose
        // branch north-east runs in and whose branch south runs out, so F's traffic enters J: J's south-east is an entrance.
        var session = new BoardEditorSession();
        Coords fork = J.translated(2);
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J.translated(0), J);
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J.translated(4).translated(4), J.translated(4), J);
        stroke(session, J, fork, fork.translated(3), fork.translated(3).translated(3));
        stroke(session, fork.translated(1).translated(1), fork.translated(1), fork);
        assertTrue(reported(session, J, "DEAD_END_ROAD", "N"), issuesAt(session, J).toString());
        // Reversing the north-east branch turns F's side towards J round: J's south-east becomes an exit, a Y split
        // running on the left. J lies beyond the fork, two hexes from any hex the edit changed.
        inspect(session, fork.translated(1));
        send(session, Action.LANE_DIRECTION, "road", "reverse");
        assertFalse(reported(session, J, "DEAD_END_ROAD", "N"), issuesAt(session, J).toString());
        assertTrue(reported(session, J, "REVERSED_SPLIT", "SE", "SW"), issuesAt(session, J).toString());
        // Undone, and the branch next to the fork widened to two lanes instead: F's side towards J turns round again.
        send(session, Action.UNDO, "", "");
        assertTrue(reported(session, J, "DEAD_END_ROAD", "N"), issuesAt(session, J).toString());
        send(session, Action.LANES, "road", "two");
        assertTrue(reported(session, J, "REVERSED_SPLIT", "SE", "SW"), "Widened: " + issuesAt(session, J));
    }

    @Test void aSingleLaneHubAtACarParkIsNoStrandedJunction() {
        // A single-lane hex at a car park's edge, both its other lanes leading away: the car park feeds them.
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "pavement", "1");
        stroke(session, J.translated(0));
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J);
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, J.translated(2), J.translated(2).translated(2));
        stroke(session, J, J.translated(4), J.translated(4).translated(4));
        inspect(session, J);
        send(session, Action.EDGE, "road", "0");
        send(session, Action.LANES, "road", "single");
        assertEquals(1 | 4 | 16, session.board().getHex(J).getTerrain(Terrains.ROAD).getExits(), "A hub: the car park and two lanes");
        assertTrue(single(session, J));
        assertTrue(issuesAt(session, J).isEmpty(), issuesAt(session, J).toString());
    }

    @Test void aCarParkMouthWithOneWayLanesIsNoStrandedJunction() {
        // A two-lane hex at a car park's edge, its traffic leaving by one single lane and arriving by another.
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "pavement", "1");
        stroke(session, J.translated(0));
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, J);
        send(session, Action.BRUSH_VALUE, "lanes", "single");
        stroke(session, J, J.translated(2), J.translated(2).translated(2));
        stroke(session, J, J.translated(4), J.translated(4).translated(4));
        inspect(session, J);
        send(session, Action.EDGE, "road", "0");
        assertTrue(session.board().getHex(J).containsTerrainExit(Terrains.ROAD, 0), "Its side to the car park opened in Edit");
        send(session, Action.RAMP, "road:2", "entrance");
        assertTrue(issuesAt(session, J).isEmpty(), "The car park feeds the exit and takes the entrance's traffic: "
              + issuesAt(session, J));
    }
}
