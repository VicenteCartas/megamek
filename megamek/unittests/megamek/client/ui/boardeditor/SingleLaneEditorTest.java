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
}
