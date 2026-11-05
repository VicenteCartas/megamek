/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardEditorEraseTest {
    private static final Coords FIRST = new Coords(4, 4), SECOND = new Coords(6, 4);

    @Test void groundEraserRemovesThePointedBuildingAndKeepsTheGroundAndOtherTerrain() {
        var session = new BoardEditorSession();
        Hex building = new Hex(3, "building:4;bldg_elev:5;bldg_cf:120;woods:1;foliage_elev:2;road:1", "grass");
        building.setAppearance(Map.of("ground", new HexAppearance("ground/grass", null, null, null)));
        session.board().setHex(FIRST, building);
        Hex before = session.board().getHex(FIRST).duplicate();
        send(session, Action.CHOOSE_BRUSH, "ground", "clear");
        send(session, Action.TOOL, "", "ERASE");
        var preview = session.preview(FIRST, 0, 0, "building");
        Hex erased = preview.after().get(FIRST);
        assertNotNull(erased, "A roof or floor previews the building under the ground eraser");
        assertFalse(erased.containsTerrain(Terrains.BUILDING));
        assertEquals(3, erased.getLevel());
        assertEquals("grass", erased.getTheme());
        assertEquals(before.getAppearance().get("ground"), erased.getAppearance().get("ground"));
        assertTrue(erased.containsTerrain(Terrains.WOODS));
        assertTrue(erased.containsTerrain(Terrains.ROAD));
        assertEquals(BoardFile.hexNode(before), BoardFile.hexNode(session.board().getHex(FIRST)), "Preview is not an edit");
        session.pointer(FIRST, 0, 0, false, null, false, "building"); session.finishStroke();
        assertEquals(BoardFile.hexNode(erased), BoardFile.hexNode(session.board().getHex(FIRST)));
        send(session, Action.UNDO, "", "");
        assertEquals(BoardFile.hexNode(before), BoardFile.hexNode(session.board().getHex(FIRST)));
        send(session, Action.REDO, "", "");
        assertEquals(BoardFile.hexNode(erased), BoardFile.hexNode(session.board().getHex(FIRST)));
    }

    @Test void anExplicitTerrainEraserKeepsTheBuildingItPointsAt() {
        var session = new BoardEditorSession();
        session.board().setHex(FIRST, new Hex(3, "building:4;bldg_elev:5;bldg_cf:120;woods:1;foliage_elev:2", ""));
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(FIRST, 0, 0, false, null, false, "building"); session.finishStroke();
        Hex erased = session.board().getHex(FIRST);
        assertFalse(erased.containsTerrain(Terrains.WOODS));
        assertTrue(erased.containsTerrain(Terrains.BUILDING));
        assertEquals(3, erased.getLevel());
    }

    @Test void hexStampEraseKeepsEveryGroundLevelThroughPreviewStrokeUndoAndRedo() {
        for (boolean applyLevel : List.of(false, true)) {
            var session = new BoardEditorSession();
            session.pointer(new Coords(1, 1), 0, 0, false); session.finishStroke();
            send(session, Action.SAMPLE, "", "");
            send(session, Action.BRUSH_VALUE, "applyElevation", Boolean.toString(applyLevel));
            send(session, Action.TOOL, "", "ERASE");
            Map<Coords, Hex> before = Map.of(FIRST, new Hex(3, "building:4;bldg_elev:5;bldg_cf:120", ""),
                  SECOND, new Hex(-2, "water:2;woods:1;foliage_elev:2", ""));
            before.forEach((at, hex) -> session.board().setHex(at, hex.duplicate()));
            for (var entry : before.entrySet()) {
                Hex erased = session.preview(entry.getKey(), 0, 0, "building").after().get(entry.getKey());
                assertNotNull(erased);
                assertEquals(entry.getValue().getLevel(), erased.getLevel(), "Preview preserves the destination's height");
                assertFalse(erased.containsAnyTerrainOf(Terrains.BUILDING, Terrains.WATER, Terrains.WOODS));
            }
            session.pointer(FIRST, 0, 0, false, null, false, "building");
            session.pointer(SECOND, 0, 0, true); session.finishStroke();
            before.forEach((at, hex) -> assertEquals(BoardFile.hexNode(new Hex(hex.getLevel())),
                  BoardFile.hexNode(session.board().getHex(at))));
            send(session, Action.UNDO, "", "");
            before.forEach((at, hex) -> assertEquals(BoardFile.hexNode(hex), BoardFile.hexNode(session.board().getHex(at))));
            send(session, Action.REDO, "", "");
            before.forEach((at, hex) -> assertEquals(BoardFile.hexNode(new Hex(hex.getLevel())),
                  BoardFile.hexNode(session.board().getHex(at))));
        }
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
