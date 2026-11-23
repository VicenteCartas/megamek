/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Infantry;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The dock's controls for diving infantry: their UMU MP stored as jump MP never offers Jump, and the depth stepper only
 * stands in water.
 */
class GpuMovePlanTraitsTest {
    private static final Coords WATER = new Coords(0, 0);
    private static final Coords LAND = new Coords(1, 0);

    @Test
    void scubaInfantryHasNoJumpAndOnlyDivesInWater() {
        Board board = new Board(2, 1);
        board.setHex(WATER.getX(), WATER.getY(), new Hex());
        board.setHex(LAND.getX(), LAND.getY(), new Hex());
        board.getHex(WATER).addTerrain(new Terrain(Terrains.WATER, 2));
        Game game = new Game(board);

        Infantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.SUBMARINE);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        scuba.setBoardId(board.getBoardId());
        game.addEntity(scuba);

        scuba.setPosition(WATER);
        scuba.setElevation(-1);
        GpuMovePlan.Traits inWater = GpuMovePlan.Traits.of(scuba, new MovePath(game, scuba), false, null);
        assertFalse(inWater.jumps(), "SCUBA infantry's jump MP is its UMU MP");
        assertTrue(inWater.elevates(), "In water it changes depth");
        assertEquals(3, inWater.walk(), "Under water it walks with its UMU MP");

        scuba.setPosition(LAND);
        scuba.setElevation(0);
        GpuMovePlan.Traits onLand = GpuMovePlan.Traits.of(scuba, new MovePath(game, scuba), false, null);
        assertFalse(onLand.elevates(), "On land it has no depth to change");
    }
}
