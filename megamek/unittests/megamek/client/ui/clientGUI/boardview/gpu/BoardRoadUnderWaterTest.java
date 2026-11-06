/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
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
    void waterAboveARoadLeavingItsDeckRecedesBehindTheRamp() {
        var board = Board.createEmptyBoard(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 3; y < 7; y++) { board.setHex(new Coords(x, y), new Hex(-1, "", "")); }
        }
        board.setHex(AT, new Hex(-1, ROAD, ""));
        board.setHex(AT.translated(NORTH), deckAtTheSurface());
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        var water = new BoardSurface(scene, scene.tile(AT.translated(NORTH)));
        var road = new BoardSurface(scene, scene.tile(AT));
        var from = BoardGeometry.center(AT, 0);
        var to = BoardGeometry.center(AT.translated(NORTH), 0);
        var direction = to.cpy().sub(from).nor();
        var edge = from.cpy().lerp(to, .5f);
        for (float in : new float[] { 4, 12 }) {
            float x = edge.x + direction.x * in, y = edge.y + direction.y * in;
            assertTrue(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, x, y, Float.NaN)),
                  "No water under the ramp, " + in + " in from the edge");
        }
        assertEquals(-BoardGeometry.level(), BoardSurface.sampleHeight(road.groundFaces(), edge.x + direction.x * 4,
              edge.y + direction.y * 4, Float.NaN), .1f * BoardGeometry.level(), "The low ground runs on past the edge");
        assertFalse(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, to.x, to.y, Float.NaN)), "Water at the hex's centre");
        BoardRoadSlopeTest.assertClosed(scene, AT, TerrainLod.FULL);
    }

    /** A river along row 3, a level above the land round it, its deck at (3, 3) with these road exits, every road below the water. */
    private static BoardScene raisedRiver(int... exits) {
        var board = Board.createEmptyBoard(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                board.setHex(new Coords(x, y), y == AT.getY() ? new Hex(0, "water:1", "") : new Hex(-1, "", ""));
            }
        }
        int mask = 0;
        for (int exit : exits) {
            mask |= 1 << exit;
            board.setHex(AT.translated(exit), new Hex(-1, "road:1:" + (1 << (exit + 3) % 6), ""));
        }
        board.setHex(AT, new Hex(0, "water:1;bridge:1:" + mask + ";bridge_cf:40;bridge_elev:0", ""));
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
    }

    /** The corner three mutually adjacent hexes share. */
    private static Vector3 corner(Coords a, Coords b, Coords c) {
        return BoardGeometry.center(a, 0).add(BoardGeometry.center(b, 0)).add(BoardGeometry.center(c, 0)).scl(1 / 3f);
    }

    @Test
    void aBankBetweenTwoExitsOfATurningDeckGivesWayAtTheirCorner() {
        var scene = raisedRiver(SOUTH, 2);
        var water = new BoardSurface(scene, scene.tile(AT));
        var point = corner(AT, AT.translated(SOUTH), AT.translated(2)).lerp(BoardGeometry.center(AT, 0), .25f);
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(water.groundFaces(), point.x, point.y, Float.NaN)),
              "The water's bank has left the corner between the ramps");
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, point.x, point.y, Float.NaN)), "No water there");
        BoardRoadSlopeTest.assertClosed(scene, AT, TerrainLod.FULL);
    }

    @Test
    void aBankBesideWaterOnBothSidesOfTheRoadRecedesThroughItsMouths() {
        var scene = raisedRiver(NORTH);
        var water = new BoardSurface(scene, scene.tile(AT));
        var next = new BoardSurface(scene, scene.tile(AT.translated(1)));
        var from = corner(AT, AT.translated(NORTH), AT.translated(1));
        var to = corner(AT, AT.translated(1), AT.translated(2));
        var dry = from.cpy().lerp(to, .25f);
        for (var surface : List.of(water, next)) {
            assertTrue(Float.isNaN(BoardSurface.sampleHeight(surface.waterFaces, dry.x, dry.y, Float.NaN)),
                  "The mouth ends clear of the receded corner");
        }
        var open = from.cpy().lerp(to, .7f).lerp(BoardGeometry.center(AT, 0), .05f);
        assertFalse(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, open.x, open.y, Float.NaN)), "The mouth stays open");
        BoardRoadSlopeTest.assertClosed(scene, AT.translated(NORTH), TerrainLod.FULL);
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
