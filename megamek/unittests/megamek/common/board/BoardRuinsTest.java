/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** A destroyed structure: the rules see the rubble that replaced it, the board remembers what stood there. */
class BoardRuinsTest {
    private static final String BRIDGE = "bridge:4:18;bridge_cf:2:18;bridge_elev:2:18;bridge_road:1:18";

    private static Board bridged() {
        var hexes = new Hex[] { new Hex(2, "road:1:34", "grass"), new Hex(0, BRIDGE + ";swamp:1", ""),
              new Hex(2, "road:1:20", "grass") };
        var board = new Board();
        board.newData(3, 1, hexes, null);
        return board;
    }

    @Test
    void aCollapsedBridgeLeavesRubbleAndIsRememberedAsItsRuin() {
        Board board = bridged();
        Coords span = new Coords(1, 0);
        String standing = BoardFile.hexNode(board.getHex(span)).toString();
        board.collapseBuilding(span);
        Hex hex = board.getHex(span);
        assertFalse(hex.containsTerrain(Terrains.BRIDGE), "The rules no longer see the bridge");
        assertFalse(hex.containsTerrain(Terrains.BRIDGE_ROAD), "Nor the road on its deck");
        assertEquals(4, hex.terrainLevel(Terrains.RUBBLE), "Its rubble is the bridge's construction");
        assertTrue(hex.containsTerrain(Terrains.SWAMP));
        assertEquals(2, hex.getRuin(Terrains.BRIDGE_ELEV).getLevel(), "The ruin keeps the deck's height");
        assertEquals(18, hex.getRuin(Terrains.BRIDGE).getExits(), "and the bridge's ends");
        assertNotNull(hex.getRuin(Terrains.BRIDGE_ROAD));
        hex.restoreRuins();
        assertFalse(hex.containsTerrain(Terrains.RUBBLE), "Restored, its rubble goes");
        assertFalse(hex.hasRuins());
        assertEquals(standing, BoardFile.hexNode(hex).toString(), "and it stands as it stood");
    }

    @Test
    void aDestroyedStructureRoundTripsThroughTheBoardFileAndTheClipboard() throws Exception {
        Board board = bridged();
        board.setNativeFormat(true);
        Coords span = new Coords(1, 0);
        board.collapseBuilding(span);
        Hex fallen = board.getHex(span);
        // A building's class stays on its hex after a collapse, as before: only the structure's own terrains are its ruin.
        fallen.addTerrain(new Terrain(Terrains.BLDG_CLASS, 1));
        fallen.addRuin(new Terrain(Terrains.BUILDING, 2, true, 0));
        var output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"destroyed\":true"), text);
        // Read strictly, as a game loads it: a destroyed building's class does not make the hex invalid.
        Hex loaded = BoardFile.readNative(text).getHex(span);
        assertEquals(BoardFile.hexNode(fallen), BoardFile.hexNode(loaded), "The ruin and the rubble load as saved");
        assertNull(loaded.getTerrain(Terrains.BUILDING));
        assertEquals(1, loaded.terrainLevel(Terrains.BLDG_CLASS), "What the collapse left on the hex stays on it");
        Hex pasted = BoardFile.fromClipboard(BoardFile.clipboard(fallen));
        assertEquals(BoardFile.hexNode(fallen), BoardFile.hexNode(pasted), "A copied hex keeps its ruin");
    }
}
