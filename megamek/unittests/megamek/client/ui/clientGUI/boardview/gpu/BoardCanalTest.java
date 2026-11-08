/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.Test;

/** Water standing above paving is a canal: one width all along, lined in concrete, cut into where a deck's ramp comes down. */
class BoardCanalTest {
    private static final Coords AT = new Coords(2, 3);
    private static final int NORTH = 0, SOUTH_EAST = 2, SOUTH = 3;

    /** A canal along row 3 among paving a level below; a deck at (2, 3) with these road exits, its roads paved. */
    private static BoardScene canal(int... exits) {
        var board = Board.createEmptyBoard(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                board.setHex(new Coords(x, y), y == AT.getY() ? new Hex(0, "water:1", "") : new Hex(-1, "pavement:1", ""));
            }
        }
        int mask = 0;
        for (int exit : exits) {
            mask |= 1 << exit;
            board.setHex(AT.translated(exit), new Hex(-1, "pavement:1;road:1:" + (1 << exit | 1 << (exit + 3) % 6), ""));
        }
        if (mask != 0) { board.setHex(AT, new Hex(0, "water:1;bridge:1:" + mask + ";bridge_cf:40;bridge_elev:0", "")); }
        return new BoardScene(0, 7, 7, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of());
    }

    /** Where a hex's water meets its edge toward {@code direction}: its extent along that edge from the edge's middle. */
    private static float[] mouth(BoardSurface water, Coords at, int direction) {
        int edge = Math.floorMod(1 - direction, 6);
        Vector3 a = BoardGeometry.corner(at, 0, edge), b = BoardGeometry.corner(at, 0, edge + 1);
        Vector3 middle = new Vector3(a).lerp(b, .5f), along = new Vector3(b).sub(a).nor();
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var face : water.waterFaces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (Math.abs((p.x - a.x) * along.y - (p.y - a.y) * along.x) > .01f) { continue; }
                float s = (p.x - middle.x) * along.x + (p.y - middle.y) * along.y;
                low = Math.min(low, s);
                high = Math.max(high, s);
            }
        }
        return new float[] { low, high };
    }

    @Test
    void aCanalKeepsOneWidthIntoTheNextAndCloses() {
        var scene = canal();
        var here = new BoardSurface(scene, scene.tile(AT));
        Coords next = AT.translated(SOUTH_EAST);
        var there = new BoardSurface(scene, scene.tile(next));
        assertTrue(here.relief.canalHex(), "Water a level above the paving round it is a canal");
        float[] ours = mouth(here, AT, SOUTH_EAST), theirs = mouth(there, next, (SOUTH_EAST + 3) % 6);
        float width = ours[1] - ours[0];
        assertTrue(width > .3f * BoardGeometry.width() && width < .5f * BoardGeometry.width(), "A channel, not the hex's whole edge: " + width);
        assertEquals(width, theirs[1] - theirs[0], .01f, "The next canal meets it at the same width");
        assertEquals(0, ours[0] + ours[1], .01f, "About the edge's middle");
        BoardRoadSlopeTest.assertClosed(scene, AT, TerrainLod.FULL);
        BoardRoadSlopeTest.assertClosed(scene, next, TerrainLod.FULL);
    }

    @Test
    void aDecksRampCutsIntoTheCanalsConcrete() {
        var scene = canal(NORTH, SOUTH);
        var water = new BoardSurface(scene, scene.tile(AT));
        List<BoardSurface.Face> ground = new ArrayList<>(water.groundFaces());
        ground.addAll(water.walls(scene, BoardGeometry.floor(scene)));
        var centre = BoardGeometry.center(AT, 0);
        float ramp = (1 - BoardRoadGrade.BRIDGE_START) * BoardGeometry.height() / 2, level = BoardGeometry.level();
        for (int exit : new int[] { NORTH, SOUTH }) {
            var road = BoardGeometry.center(AT.translated(exit), 0);
            var inward = new Vector3(centre).sub(road).nor();
            var across = new Vector3(-inward.y, inward.x, 0);
            var edge = new Vector3(road).lerp(centre, .5f);
            float low = BoardBridge.edgeElevation(scene.tile(AT), scene.tile(AT.translated(exit)), exit);
            for (float lateral = -1; lateral <= 1; lateral += .25f) {
                for (float in = .05f; in < 1; in += .1f) {
                    var p = new Vector3(edge).mulAdd(inward, in * ramp).mulAdd(across, lateral * HexTile.ROAD_HALF_WIDTH);
                    float height = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NEGATIVE_INFINITY);
                    assertTrue(height <= BoardBridge.deckZ(low * (1 - in)) + .02f * level, "The canal's concrete stands over the ramp "
                          + in + " of the way up and " + lateral + " across, exit " + exit);
                }
            }
        }
        // The road the ramp comes down to keeps closed ground with the canal it was cut into.
        BoardRoadSlopeTest.assertClosed(scene, AT.translated(NORTH), TerrainLod.FULL);
    }
}
