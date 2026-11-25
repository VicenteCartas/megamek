/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.client.ui.Messages;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Infantry;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The dock's controls for diving infantry: their UMU MP stored as jump MP never offers Jump, and the depth stepper only
 * stands in water. A glider ProtoMek on the ground walks its 1 ground MP and has no run until it rises. A LAM moves in
 * its starting mode the round it converts.
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
    @Test
    void groundedGliderWalksOneMpWithoutRunUntilItRises() {
        Board board = new Board(2, 1);
        board.setHex(WATER.getX(), WATER.getY(), new Hex());
        board.setHex(LAND.getX(), LAND.getY(), new Hex());
        Game game = new Game(board);

        ProtoMek glider = new ProtoMek();
        glider.setIsGlider(true);
        glider.setMovementMode(EntityMovementMode.WIGE);
        glider.setOriginalWalkMP(5);
        glider.setGame(game);
        glider.setBoardId(board.getBoardId());
        game.addEntity(glider);
        glider.setPosition(LAND);

        glider.setElevation(0);
        GpuMovePlan.Traits grounded = GpuMovePlan.Traits.of(glider, new MovePath(game, glider), false, null);
        assertEquals(1, grounded.walk(), "On the ground a glider has 1 MP");
        assertFalse(grounded.runs(), "On the ground a glider has no faster gait");

        glider.setElevation(1);
        GpuMovePlan.Traits airborne = GpuMovePlan.Traits.of(glider, new MovePath(game, glider), false, null);
        assertEquals(-1, airborne.walk(), "Airborne it walks its own MP");
        assertTrue(airborne.runs(), "Airborne it runs");
    }

    @Test
    void lamConvertingToAirMekMovesInMekModeAndExplainsFighter() {
        Board board = new Board(2, 1);
        board.setHex(WATER.getX(), WATER.getY(), new Hex());
        board.setHex(LAND.getX(), LAND.getY(), new Hex());
        Game game = new Game(board);

        LandAirMek lam = new LandAirMek(Mek.GYRO_STANDARD, Mek.COCKPIT_STANDARD, LandAirMek.LAM_STANDARD);
        lam.setOriginalWalkMP(6);
        lam.setOriginalJumpMP(6);
        lam.setGame(game);
        lam.setBoardId(board.getBoardId());
        game.addEntity(lam);
        lam.setPosition(LAND);

        MovePath path = new MovePath(game, lam);
        path.addStep(MoveStepType.CONVERT_MODE);
        assertEquals(EntityMovementMode.WIGE, path.getFinalConversionMode(), "The plan ends in AirMek");
        GpuMovePlan.Traits traits = GpuMovePlan.Traits.of(lam, path, false, null, true);
        assertTrue(traits.converting());
        assertFalse(traits.elevates(), "A LAM moves in its starting mode the round it converts, so it doesn't rise");

        GpuMovePlan.Form fighter = traits.forms().getLast();
        assertFalse(fighter.enabled(), "A standard LAM on a ground map goes from Mek to Fighter through AirMek");
        assertEquals(Messages.getString("GpuBoard.hud.dock.form.throughAirMek"), fighter.why());
        assertNull(traits.forms().get(1).why(), "AirMek is a step away");
    }
}
