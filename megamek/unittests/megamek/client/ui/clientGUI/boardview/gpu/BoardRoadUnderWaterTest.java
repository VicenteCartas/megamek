/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
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
        var scene = scene(board);
        var deck = AT.translated(NORTH);
        var water = new BoardSurface(scene, scene.tile(deck));
        int edge = -1;
        for (int e = 0; e < 6; e++) {
            if (deck.translated(BoardGeometry.edgeDirection(e)).equals(AT)) { edge = e; }
        }
        var from = BoardGeometry.center(AT, 0);
        var direction = BoardGeometry.center(deck, 0).sub(from).nor();
        var hexEdge = from.cpy().add(direction.cpy().scl(BoardGeometry.height() / 2));
        var rim = water.relief.outlinePoint(edge, .5f, 0);
        float behind = new Vector3(rim).sub(hexEdge).dot(direction);
        assertTrue(behind >= (1 - BoardRoadGrade.BRIDGE_START) * BoardGeometry.height() / 2, "The rim lies behind the ramp's high end: " + behind);
        var top = rim.cpy().add(direction.cpy().scl(3 * BoardGeometry.hexScale()));
        assertEquals(0, BoardSurface.sampleHeight(water.groundFaces(), top.x, top.y, Float.NaN), .15f * BoardGeometry.level(), "The riverside's top lies there");
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, top.x, top.y, Float.NaN)), "It carries no water");
        BoardRoadSlopeTest.assertClosed(scene, AT, TerrainLod.FULL);
    }

    private static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
    }

    /** A river along row 3, a level above the land round it, its deck at (3, 3) with these road exits, every road below the water. */
    private static BoardScene raisedRiver(int... exits) {
        return raisedRiver("", exits);
    }

    /** {@link #raisedRiver(int...)} with this terrain on the land round the river. */
    private static BoardScene raisedRiver(String land, int... exits) {
        var board = Board.createEmptyBoard(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                board.setHex(new Coords(x, y), y == AT.getY() ? new Hex(0, "water:1", "") : new Hex(-1, land, ""));
            }
        }
        int mask = 0;
        for (int exit : exits) {
            mask |= 1 << exit;
            board.setHex(AT.translated(exit), new Hex(-1, "road:1:" + (1 << (exit + 3) % 6), ""));
        }
        board.setHex(AT, new Hex(0, "water:1;bridge:1:" + mask + ";bridge_cf:40;bridge_elev:0", ""));
        return scene(board);
    }

    /** A road along column 3 at level 0: {@code west} columns 0 to 2 and {@code east} columns 4 on, with this terrain at these levels. */
    private static BoardScene roadBeside(int westLevel, String west, int eastLevel, String east) {
        var board = Board.createEmptyBoard(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                board.setHex(new Coords(x, y), x == 3 ? new Hex(0, ROAD, "") : x < 3 ? new Hex(westLevel, west, "") : new Hex(eastLevel, east, ""));
            }
        }
        return scene(board);
    }

    /**
     * {@link BoardRoadSlopeTest#assertClosed}, but for an edge with another face's edge running within a hundredth of a metre:
     * two meshes meeting at slightly different points, a join rather than a gap.
     */
    private static void assertNoGaps(BoardScene scene, Coords at) {
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        for (var tile : scene.tiles()) {
            if (at.distance(tile.coords()) <= 2) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, TerrainLod.FULL)); }
        }
        List<BoardSurface.Face> faces = new ArrayList<>();
        for (var surface : surfaces.values()) {
            faces.addAll(surface.groundFaces());
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
        }
        float join = .05f * BoardGeometry.hexScale();
        float reach = BoardGeometry.width();
        BoardRoadSlopeTest.assertClosed(scene, at, TerrainLod.FULL, point -> faces.stream()
              .filter(face -> Math.abs(face.a().x - point.x) < reach && Math.abs(face.a().y - point.y) < reach)
              .filter(face -> toEdge(point, face.a(), face.b()) < join || toEdge(point, face.b(), face.c()) < join
                    || toEdge(point, face.c(), face.a()) < join).count() < 2);
    }

    private static float toEdge(Vector3 point, Vector3 a, Vector3 b) {
        var along = new Vector3(b).sub(a);
        float t = along.len2() == 0 ? 0 : Math.clamp(new Vector3(point).sub(a).dot(along) / along.len2(), 0, 1);
        return new Vector3(a).mulAdd(along, t).dst(point);
    }

    /** Whether every edge of this hex's outline runs forward along its edge, at its rim, mid-slope and foot: none folded over. */
    private static void assertOutlineInOrder(BoardSurface surface) {
        float level = surface.tile.elevation() * BoardGeometry.level(), scale = BoardGeometry.hexScale();
        for (float z : new float[] { level, level - BoardGeometry.level() / 2, level - BoardGeometry.level() }) {
            for (int e = 0; e < 6; e++) {
                var from = surface.relief.outlinePoint(e, 0, z);
                var along = surface.relief.outlinePoint(e, 1, z).sub(from).nor();
                assertTrue(along.dot(BoardGeometry.corner(surface.tile.coords(), 0, e + 1).sub(BoardGeometry.corner(surface.tile.coords(), 0, e))) > 0,
                      "Edge " + e + " has crossed over at " + z);
                float last = 0;
                for (float t = .05f; t <= 1; t += .05f) {
                    float here = surface.relief.outlinePoint(e, t, z).sub(from).dot(along);
                    assertTrue(here >= last - scale, "Edge " + e + " runs back at " + t + " at height " + z);
                    last = here;
                }
            }
        }
    }

    @Test
    void aBankOverADeckRampRecedesInOrderAndKeepsItsRiverside() {
        // A sharp turn, a wide turn with land between its exits, and a straight deck with water beside its low road's hex.
        for (var exits : List.of(new int[] { SOUTH, 2 }, new int[] { 2, 4 }, new int[] { NORTH, SOUTH })) {
            var scene = raisedRiver(exits);
            var water = new BoardSurface(scene, scene.tile(AT));
            assertOutlineInOrder(water);
            var centre = BoardGeometry.center(AT, 0);
            // On every bank of its hex and of the river beside it, the riverside's top stands at the water's level just inside
            // its rim, and carries no water.
            for (var at : List.of(AT, AT.translated(1), AT.translated(5))) {
                var surface = new BoardSurface(scene, scene.tile(at));
                var middle = BoardGeometry.center(at, 0);
                for (int e = 0; e < 6; e++) {
                    var next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
                    if (next == null || next.liquid().present()) { continue; }
                    // Under the deck the riverside runs on behind the ramp, between what the deck's own corners keep.
                    for (float t : at.equals(AT) && next.wayExits() != 0 ? new float[] { .5f } : new float[] { .15f, .5f, .85f }) {
                        var rim = surface.relief.outlinePoint(e, t, 0);
                        var top = rim.cpy().add(middle.cpy().sub(rim).nor().scl(3 * BoardGeometry.hexScale()));
                        assertEquals(0, BoardSurface.sampleHeight(surface.groundFaces(), top.x, top.y, Float.NaN), .25f * BoardGeometry.level(),
                              "The riverside's top on edge " + e + " of " + at + " at " + t);
                        assertTrue(Float.isNaN(BoardSurface.sampleHeight(surface.waterFaces, top.x, top.y, Float.NaN)),
                              "No water on the bank, edge " + e + " of " + at + " at " + t);
                    }
                }
            }
            assertFalse(Float.isNaN(BoardSurface.sampleHeight(water.waterFaces, centre.x, centre.y, Float.NaN)), "Water under the deck");
            assertNoGaps(scene, AT);
        }
    }

    /** No ground of the deck's hex stands above the ramp, across this share of the road, from the hex's edge to the ramp's high end. */
    private static void assertRampClearsTheBank(BoardScene scene, int exit, float road) {
        var water = new BoardSurface(scene, scene.tile(AT));
        var centre = BoardGeometry.center(AT, 0);
        var next = BoardGeometry.center(AT.translated(exit), 0);
        var inward = centre.cpy().sub(next).nor();
        var across = new Vector3(-inward.y, inward.x, 0);
        var edge = next.cpy().lerp(centre, .5f);
        List<BoardSurface.Face> ground = new ArrayList<>(water.groundFaces());
        ground.addAll(water.walls(scene, BoardGeometry.floor(scene)));
        float level = BoardGeometry.level(), ramp = (1 - BoardRoadGrade.BRIDGE_START) * BoardGeometry.height() / 2;
        float low = BoardBridge.edgeElevation(scene.tile(AT), scene.tile(AT.translated(exit)), exit);
        for (float lateral : new float[] { -1, -.5f, 0, .5f, 1 }) {
            for (float in = .1f; in <= 1; in += .1f) {
                var point = edge.cpy().mulAdd(inward, in * ramp).mulAdd(across, lateral * road * HexTile.ROAD_HALF_WIDTH * BoardGeometry.hexScale());
                float height = BoardSurface.sampleHeight(ground, point.x, point.y, Float.NEGATIVE_INFINITY);
                float surface = BoardBridge.deckZ(low * (1 - in));
                assertTrue(height <= surface + .02f * level, "Ground at " + height + " stands over the ramp at " + surface + ", "
                      + in + " of the way up and " + lateral + " across, exit " + exit);
            }
        }
    }

    @Test
    void theBankClearsADecksRampWhateverHoldsItsCorners() {
        for (var exits : List.of(new int[] { SOUTH, 2 }, new int[] { 2, 4 }, new int[] { NORTH, SOUTH })) {
            for (int exit : exits) { assertRampClearsTheBank(raisedRiver(exits), exit, 1); }
        }
        // Paving beside the road's hex keeps its outline, and with it the bank's corners there. The bay turns in from them
        // in the road's outer metre or so, over which the bank may stand a little above the ramp.
        var paved = raisedRiver("pavement:1", SOUTH);
        assertRampClearsTheBank(paved, SOUTH, .85f);
        assertOutlineInOrder(new BoardSurface(paved, paved.tile(AT)));
    }

    @Test
    void aBankAboveARoadRecedesFromItAsADrySlopeDoes() {
        var wet = roadBeside(1, "water:1", 0, "");
        var dry = roadBeside(1, "", 0, "");
        var at = new Coords(2, 3);
        int edge = -1;
        var surface = new BoardSurface(wet, wet.tile(at));
        var land = new BoardSurface(dry, dry.tile(at));
        var road = new Coords(3, 3);
        for (int e = 0; e < 6; e++) {
            if (at.translated(BoardGeometry.edgeDirection(e)).equals(road)) { edge = e; }
        }
        assertTrue(edge >= 0);
        var from = BoardGeometry.center(road, 0);
        float z = .25f * BoardGeometry.level();
        // Near the corner, where the step comes closest to the road: both recede from it, neither is left where it stood.
        for (float t : new float[] { .1f, .9f }) {
            var bank = surface.relief.outlinePoint(edge, t, z);
            var slope = land.relief.outlinePoint(edge, t, z);
            float clear = (BoardRoad.SHOULDER - 1) * BoardGeometry.hexScale();
            var way = BoardRoad.way(wet, wet.tile(road));
            float scale = BoardGeometry.hexScale();
            assertTrue(way.distance((bank.x - from.x) / scale, (bank.y - from.y) / scale) * scale >= clear,
                  "The water's bank stands clear of the road at " + t);
            assertTrue(way.distance((slope.x - from.x) / scale, (slope.y - from.y) / scale) * scale >= clear, "As the dry slope does");
        }
        assertOutlineInOrder(surface);
        BoardRoadSlopeTest.assertClosed(wet, road, TerrainLod.FULL);
    }

    @Test
    void aRoadAboveWaterHasAFootingOutToItsVerge() {
        var scene = roadBeside(0, "", -1, "water:1");
        var road = new Coords(3, 3);
        var surface = new BoardSurface(scene, scene.tile(road));
        var way = BoardRoad.way(scene, scene.tile(road));
        var centre = BoardGeometry.center(road, 0);
        float scale = BoardGeometry.hexScale(), verge = (BoardRoad.SHOULDER + BoardRoadBank.VERGE - .5f) * scale;
        for (int e = 0; e < 6; e++) {
            if (!scene.tile(road.translated(BoardGeometry.edgeDirection(e))).liquid().present()) { continue; }
            for (float t = .1f; t < 1; t += .1f) {
                var rim = surface.relief.outlinePoint(e, t, 0);
                assertTrue(way.distance((rim.x - centre.x) / scale, (rim.y - centre.y) / scale) * scale >= verge,
                      "The road's rim on edge " + e + " at " + t + " stands out past its verge");
            }
        }
        // Ground stands at the road's level right beside it, down its length: nothing hangs over a gap.
        var support = new ArrayList<>(surface.groundFaces());
        support.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
        float beside = (HexTile.ROAD_HALF_WIDTH + BoardRoad.SHOULDER + .5f) * scale;
        for (float along = -.4f; along <= .4f; along += .1f) {
            float y = centre.y + along * BoardGeometry.height();
            assertEquals(0, BoardSurface.sampleHeight(support, centre.x + beside, y, Float.NEGATIVE_INFINITY), .1f * BoardGeometry.level(),
                  "Ground beside the road at " + along);
        }
        BoardRoadSlopeTest.assertClosed(scene, road, TerrainLod.FULL);
    }

    /** The tint a road's coat takes at this point of the hex: tenths of a level of water over it. */
    private static int tint(BoardSurface water, Vector3 at, float z) {
        return (Float.floatToRawIntBits(GpuRoads.underWater(Color.WHITE_FLOAT_BITS, at.x, at.y, z, water)) >>> 24) / 2 % 31;
    }

    @Test
    void aFloodedRoadsTintFollowsTheWaterAndStopsOnABank() {
        var scene = raisedRiver(SOUTH);
        var water = new BoardSurface(scene, scene.tile(AT));
        var centre = BoardGeometry.center(AT, 0);
        var road = BoardGeometry.center(AT.translated(SOUTH), 0);
        float level = BoardGeometry.level(), surface = water.waterHeight(centre.x, centre.y);
        assertEquals(10, tint(water, centre, surface - level), "A road a level under the deck's water");
        assertEquals(5, tint(water, centre, surface - level / 2), "A road half a level under it");
        assertEquals(0, tint(water, centre.cpy().lerp(road, .5f), -level / 2), "The ramp past the receded bank, below the water's level but dry");
        assertEquals(0, tint(water, road, -level), "The road's own hex");
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
