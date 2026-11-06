/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Water is a layer over the same ground: a road under it behaves as a dry road at the water's floor. */
class BoardRoadUnderWaterTest {
    private static final Coords AT = new Coords(3, 3);
    private static final int NORTH = 0, SOUTH = 3;
    private static final String ROAD = "road:1:9";

    private static BoardScene scene(Hex road, Hex north) {
        var board = Board.createEmptyBoard(7, 7);
        board.setHex(AT, road);
        board.setHex(AT.translated(NORTH), north);
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
    }

    private static Hex deckAtTheSurface() {
        return new Hex(0, "water:1;bridge:1:9;bridge_cf:40;bridge_elev:0;bridge_road:1:9", "");
    }

    @Test
    void aRoadALevelUnderADeckGetsTheRampOfADryRoadALevelBelowOne() {
        for (var scene : List.of(scene(new Hex(0, "water:1;" + ROAD, ""), deckAtTheSurface()),
              scene(new Hex(-1, ROAD, ""), deckAtTheSurface()))) {
            var deck = scene.tile(AT.translated(NORTH));
            var road = scene.tile(AT);
            assertTrue(BoardBridge.road(deck, road, SOUTH));
            assertTrue(BoardBridge.bank(deck, road, SOUTH));
            assertEquals(-.5f, BoardBridge.edgeElevation(deck, road, SOUTH), "The deck starts down its half of the ramp");
        }
    }

    @Test
    void twoLevelsUnderADeckIsNoApproachWetOrDry() {
        for (var scene : List.of(scene(new Hex(0, "water:2;" + ROAD, ""), deckAtTheSurface()),
              scene(new Hex(-2, ROAD, ""), deckAtTheSurface()))) {
            var deck = scene.tile(AT.translated(NORTH));
            assertFalse(BoardBridge.road(deck, scene.tile(AT), SOUTH));
            assertEquals(0, BoardBridge.edgeElevation(deck, scene.tile(AT), SOUTH));
        }
    }

    @Test
    void aDeckEasesDownOntoAFordsBedHalfwayAtTheEdgeBothSidesShare() {
        var scene = scene(new Hex(0, "water:0;" + ROAD, ""), deckAtTheSurface());
        var deck = scene.tile(AT.translated(NORTH));
        var ford = scene.tile(AT);
        assertTrue(BoardBridge.road(deck, ford, SOUTH));
        assertEquals(BoardRoadGrade.ground(ford) / 2, BoardBridge.edgeElevation(deck, ford, SOUTH));
        assertEquals(BoardRoadGrade.ground(ford), BoardRoadGrade.edgeLevel(ford, deck, NORTH),
              "The deck's solid ramp carries it, as over any road below a deck");
        assertEquals(0, BoardRoadGrade.ramps(scene, ford), "A ford's dip is no climb that would change its road's course");
    }

    @Test
    void dryGroundKeepsItsLevelBesideWaterAndTheWetHexCarriesTheRamp() {
        var scene = scene(new Hex(0, "water:1;" + ROAD, ""), new Hex(0, ROAD, ""));
        var wet = scene.tile(AT);
        var dry = scene.tile(AT.translated(NORTH));
        assertEquals(-1, wet.floor());
        assertEquals(0, BoardRoadGrade.edgeLevel(dry, wet, SOUTH), "A ramp cut into dry ground would flood");
        assertEquals(0, BoardRoadGrade.edgeLevel(wet, dry, NORTH));
    }

    @Test
    void aRoadLiesOnItsOwnFlatSurfaceWhereBanksTiltTheBedBesideIt() {
        var board = Board.createEmptyBoard(7, 7);
        for (Coords at : List.of(AT, AT.translated(NORTH), AT.translated(SOUTH))) {
            board.setHex(at, new Hex(0, "water:1;" + ROAD, ""));
        }
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        var tile = scene.tile(AT);
        var surface = new BoardSurface(scene, tile);
        var centre = BoardGeometry.center(AT, 0);
        for (float along = -.4f; along <= .4f; along += .05f) {
            float y = centre.y + along * BoardGeometry.height();
            assertEquals(BoardGeometry.groundZ(tile), BoardSurface.sampleHeight(surface.groundFaces(), centre.x, y, Float.NaN),
                  .01f * BoardGeometry.level(), "Along the road at " + along);
        }
    }
}
