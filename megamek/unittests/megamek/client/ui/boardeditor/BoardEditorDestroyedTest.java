/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** A structure set destroyed in the editor: its rubble plays, its ruin is remembered, clearing Destroyed restores it. */
class BoardEditorDestroyedTest {
    /** A two-hex bridge over level 0 between level-2 banks: 0001 - [0101 0201] - 0301. */
    private static BoardEditorSession bridged() {
        // Each span hex runs to both of its neighbours along the row.
        int east = new Coords(1, 0).direction(new Coords(2, 0)), west = new Coords(1, 0).direction(new Coords(0, 0));
        int first = (1 << east) | (1 << west);
        int second = (1 << new Coords(2, 0).direction(new Coords(3, 0))) | (1 << new Coords(2, 0).direction(new Coords(1, 0)));
        String deck = ";bridge_cf:40:%d;bridge_elev:2:%d;bridge_road:1:%d";
        var hexes = new Hex[] { new Hex(2, "", "grass"),
              new Hex(0, "bridge:3:" + first + deck.formatted(first, first, first), ""),
              new Hex(0, "bridge:3:" + second + deck.formatted(second, second, second), ""),
              new Hex(2, "", "grass") };
        var board = new Board();
        board.newData(4, 1, hexes, null);
        var session = new BoardEditorSession();
        session.open(board, null);
        return session;
    }

    private static void command(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }

    private static void select(BoardEditorSession session, Coords at) {
        command(session, Action.SELECT_AT, at.getX() + "," + at.getY(), "");
    }

    @Test
    void aDestroyedHexPlaysAsRubbleAndStandsAgainAsItStood() {
        var session = bridged();
        Coords span = new Coords(1, 0), other = new Coords(2, 0);
        String standing = BoardFile.hexNode(session.board().getHex(span)).toString();
        String neighbour = BoardFile.hexNode(session.board().getHex(other)).toString();
        select(session, span);
        command(session, Action.DESTROYED, "", "true");
        Hex hex = session.board().getHex(span);
        assertFalse(hex.containsTerrain(Terrains.BRIDGE), "Play sees no bridge");
        assertEquals(3, hex.terrainLevel(Terrains.RUBBLE), "but rubble of its construction");
        assertTrue(hex.getRuin(Terrains.BRIDGE) != null, "Its ruin is kept");
        assertEquals(neighbour, BoardFile.hexNode(session.board().getHex(other)).toString(),
              "The other span hex stands as it stood, its exit toward the gap kept as a game's collapse keeps it");
        assertEquals(List.of("bridge"), session.snapshot().destroyed(), "Layers shows the destroyed bridge");
        command(session, Action.COMPONENT, "", "rubble");
        assertEquals("bridge", session.snapshot().component(), "Its rubble is the bridge's: choosing it chooses the bridge");
        command(session, Action.DESTROYED, "", "false");
        assertEquals(standing, BoardFile.hexNode(session.board().getHex(span)).toString(), "Restored as it stood");
        assertEquals(neighbour, BoardFile.hexNode(session.board().getHex(other)).toString(), "and its neighbour too");
        command(session, Action.UNDO, "", "");
        assertTrue(session.board().getHex(span).hasRuins(), "Undo puts the ruin back");
        select(session, span);
        command(session, Action.DELETE_SELECTION, "", "");
        assertFalse(session.board().getHex(span).hasRuins(), "A deleted hex keeps no ruin");
    }

    @Test
    void theWholeStructureFallsAndStandsAgainInOneStep() {
        var session = bridged();
        List<Coords> span = List.of(new Coords(1, 0), new Coords(2, 0));
        select(session, span.getFirst());
        command(session, Action.DESTROYED, "structure", "true");
        for (Coords at : span) { assertTrue(session.board().getHex(at).hasRuins(), at + " destroyed"); }
        command(session, Action.DESTROYED, "structure", "false");
        for (Coords at : span) {
            assertTrue(session.board().getHex(at).containsTerrain(Terrains.BRIDGE), at + " stands again");
            assertFalse(session.board().getHex(at).containsTerrain(Terrains.RUBBLE), at + " without rubble");
        }
        command(session, Action.UNDO, "", "");
        for (Coords at : span) { assertTrue(session.board().getHex(at).hasRuins(), at + ": one undo step"); }
    }
}
