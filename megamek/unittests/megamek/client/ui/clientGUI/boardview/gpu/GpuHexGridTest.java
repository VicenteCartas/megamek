/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.ToHitData;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The Tactical View's ground layer marks the hexes the captured board describes: cliffs by the one
 * cliff rule (BoardRim.high, Cliff Top exits included) on both sides of their hexside, 1- and 2-level steps, woods,
 * water and structures with their outline, smoke and fire, and the enemy's arcs from the captured side table.
 */
class GpuHexGridTest {
    private static final int SIZE = 7;
    private static final Coords PLATEAU = new Coords(1, 1);
    private static final Coords STEP = new Coords(4, 1);
    private static final Coords DOUBLE_STEP = new Coords(5, 4);
    private static final Coords CLIFF_TOP = new Coords(2, 4);
    private static final Coords WOODS = new Coords(0, 4);
    private static final Coords LAKE = new Coords(6, 6);
    private static final Coords BRIDGED = new Coords(6, 3);
    private static final Coords HALL = new Coords(3, 3);
    private static final Coords ANNEX = new Coords(3, 4);
    private static final Coords SMOKE = new Coords(0, 0);
    private static final Coords FIRE = new Coords(6, 0);

    private static BoardScene scene() {
        Hex[] hexes = new Hex[SIZE * SIZE];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        hex(hexes, PLATEAU).setLevel(3);
        hex(hexes, STEP).setLevel(1);
        hex(hexes, DOUBLE_STEP).setLevel(2);
        // A 1-level drop to the south the map marks as a cliff side.
        hex(hexes, CLIFF_TOP).setLevel(1);
        for (int direction = 0; direction < 6; direction++) {
            if (direction != 3) { hex(hexes, CLIFF_TOP.translated(direction)).setLevel(1); }
        }
        hex(hexes, CLIFF_TOP).addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, 1 << 3));
        hex(hexes, WOODS).addTerrain(new Terrain(Terrains.WOODS, 2));
        hex(hexes, LAKE).addTerrain(new Terrain(Terrains.WATER, 2));
        hex(hexes, BRIDGED).addTerrain(new Terrain(Terrains.WATER, 1));
        hex(hexes, BRIDGED).addTerrain(new Terrain(Terrains.BRIDGE, 2, true, (1 << 0) | (1 << 3)));
        hex(hexes, BRIDGED).addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        hex(hexes, BRIDGED).addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        // A two-hex medium building, continued across its shared hexside.
        building(hexes, HALL, 1 << 3);
        building(hexes, ANNEX, 1);
        hex(hexes, SMOKE).addTerrain(new Terrain(Terrains.SMOKE, 2));
        hex(hexes, FIRE).addTerrain(new Terrain(Terrains.FIRE, 1));
        Board board = new Board(SIZE, SIZE, hexes);
        return new BoardScene(0, SIZE, SIZE, BoardTiles.captureAll(board), List.of(), List.of(), Entity.NONE, "",
              List.of());
    }

    private static Hex hex(Hex[] hexes, Coords coords) {
        return hexes[coords.getY() * SIZE + coords.getX()];
    }

    private static void building(Hex[] hexes, Coords coords, int exits) {
        hex(hexes, coords).addTerrain(new Terrain(Terrains.BUILDING, 2, true, exits));
        hex(hexes, coords).addTerrain(new Terrain(Terrains.BLDG_ELEV, 2));
        hex(hexes, coords).addTerrain(new Terrain(Terrains.BLDG_CF, 40));
    }

    private static int edges(BoardScene scene, Coords coords) {
        return GpuHexGrid.edges(scene, scene.tile(coords));
    }

    @Test
    void cliffsAndStepsFollowTheOneCliffRule() {
        BoardScene scene = scene();
        assertEquals(63, edges(scene, PLATEAU) & 63, "A 3-level drop is a cliff on every side");
        for (int direction = 0; direction < 6; direction++) {
            Coords below = PLATEAU.translated(direction);
            assertTrue((edges(scene, below) & 1 << ((direction + 3) % 6 + 6)) != 0,
                  "The hex below at " + below + " carries the cliff's hachures toward the plateau");
        }
        assertEquals(1 << 3, edges(scene, CLIFF_TOP) & 63, "A Cliff Top exit makes its 1-level drop a cliff");
        assertEquals(0, edges(scene, CLIFF_TOP) >> 12 & 63, "and no step");
        int step = edges(scene, STEP);
        assertEquals(0, step & 63, "A 1-level drop is no cliff");
        assertEquals(63, step >> 12 & 63, "but a thin step line on every lower side");
        assertEquals(63, edges(scene, DOUBLE_STEP) >> 18 & 63, "A 2-level drop is a double step");
        assertEquals(0, edges(scene, new Coords(6, 5)) & (63 | 63 << 12 | 63 << 18), "Level ground has no edge");
    }

    @Test
    void areasCarryTheirFillAndOutline() {
        BoardScene scene = scene();
        int woods = GpuHexGrid.area(scene, scene.tile(WOODS));
        assertEquals(GpuHexGrid.WOODS + 1, woods >> 6, "Heavy woods");
        // Column 0: its north-west and south-west sides are the board's edge, which bounds no woods.
        assertEquals((1 << 0) | (1 << 1) | (1 << 2) | (1 << 3), woods & 63);
        assertEquals(GpuHexGrid.WATER + 2, GpuHexGrid.area(scene, scene.tile(LAKE)) >> 6, "Depth 2");
        int hall = GpuHexGrid.area(scene, scene.tile(HALL));
        assertEquals(GpuHexGrid.BUILDING + 1, hall >> 6, "A medium building");
        assertEquals(63 & ~(1 << 3), hall & 63, "outlined except where it continues into its annex");
        assertEquals(63 & ~1, GpuHexGrid.area(scene, scene.tile(ANNEX)) & 63);
        assertEquals(new BoardScene.Cover(0, false, 2, 1 << 3, 40, 0, 2), scene.tile(HALL).cover(),
              "The captured cover: type, exits, the hex's CF without a game building, height");
        assertEquals(0, GpuHexGrid.area(scene, scene.tile(new Coords(6, 5))), "Plain ground has none");
    }

    @Test
    void smokeFireAndWaterSurfacesAreMarked() {
        BoardScene scene = scene();
        assertEquals(2, GpuHexGrid.marks(scene.tile(SMOKE)) & 3, "Heavy smoke");
        assertTrue((GpuHexGrid.marks(scene.tile(FIRE)) & 4) != 0, "Fire");
        assertTrue((GpuHexGrid.marks(scene.tile(LAKE)) & 8) != 0, "A lake's marks lie on its surface");
        assertEquals(0, GpuHexGrid.marks(scene.tile(WOODS)) & 8, "Land marks on the plane above the board");
        // A bridge deck over water stands above the water's plane, a hair under the surface, so it covers the fill.
        BoardScene.Tile bridged = scene.tile(BRIDGED);
        assertTrue((GpuHexGrid.marks(bridged) & 8) != 0);
        BoardScene.Feature deck = BoardBridge.feature(bridged);
        assertNotNull(deck, "The captured bridge");
        assertTrue(deck.elevation() * BoardGeometry.level() > BoardGeometry.waterZ(bridged) - BoardGeometry.hexScale(),
              "The deck lies above the water fill's plane");
    }

    @Test
    void theArcsComeFromTheCapturedSideTable() {
        BoardScene scene = scene();
        Coords origin = new Coords(3, 2);
        Coords rear = origin.translated(3);
        Coords side = origin.translated(2);
        Coords front = origin.translated(0);
        Coords sideFront = side.translated(1);
        var arc = new GpuBattleStatus.Arc(9, origin, Map.of(rear, ToHitData.SIDE_REAR, side, ToHitData.SIDE_RIGHT,
              front, ToHitData.SIDE_FRONT, sideFront, ToHitData.SIDE_FRONT, rear.translated(3), ToHitData.SIDE_REAR));
        assertEquals(GpuHexGrid.REAR, GpuHexGrid.code(arc, rear));
        assertEquals(GpuHexGrid.SIDE, GpuHexGrid.code(arc, side));
        assertEquals(GpuHexGrid.FRONT, GpuHexGrid.code(arc, front));
        assertEquals(0, GpuHexGrid.code(arc, new Coords(6, 6)), "Beyond the reach");
        float[] rearMarks = GpuHexGrid.overlay(scene, arc, rear);
        assertEquals(GpuHexGrid.REAR, rearMarks[0]);
        assertEquals(1, rearMarks[1], "One hex from the enemy");
        assertEquals(1 << 1, (int) rearMarks[2], "A solid ray where the rear meets the side arc");
        float[] sideMarks = GpuHexGrid.overlay(scene, arc, side);
        assertEquals(1 << 1, (int) sideMarks[3], "A dashed border where the side meets the front arc");
    }
}
