/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.LandAirMek;
import megamek.common.units.QuadVee;
import megamek.common.units.StartingModes;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A walk-on LAM or QuadVee picks its starting mode freely before its first step, as it picks its entry height: the
 * client converts its copy of the unit, the entry step moves in that mode, and the server starts the unit in it.
 */
class WalkOnStartingModeTest {

    private static final String UNITS = "testresources/megamek/common/units/";
    private static final Coords ENTRY = new Coords(3, 0);

    private RulesManager previousRules;
    private TWGameManager manager;
    private Game game;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        previousRules = Game.rulesManager;
        manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).send(anyInt(), any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        game = manager.getGame();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        game.addPlayer(0, new Player(0, "Player"));
        game.setPhase(GamePhase.MOVEMENT);
        game.setBoard(ClearBoard.of(8, 8));
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
    }

    @Test
    void aMekLamEntersAsALandedFighter() throws Exception {
        LandAirMek lam = (LandAirMek) serverUnit("Shadow Hawk LAM SHD-X2.mtf");

        walkOn(lam, "Shadow Hawk LAM SHD-X2.mtf", LandAirMek.CONV_MODE_FIGHTER);

        assertEquals(ENTRY, lam.getPosition());
        assertEquals(LandAirMek.CONV_MODE_FIGHTER, lam.getConversionMode());
        assertEquals(EntityMovementMode.WHEELED, lam.getMovementMode());
        assertFalse(lam.isAirborne());
    }

    @Test
    void aMekQuadVeeEntersInVehicleMode() throws Exception {
        QuadVee quadVee = (QuadVee) serverUnit("Boreas C.mtf");

        walkOn(quadVee, "Boreas C.mtf", QuadVee.CONV_MODE_VEHICLE);

        assertEquals(ENTRY, quadVee.getPosition());
        assertEquals(QuadVee.CONV_MODE_VEHICLE, quadVee.getConversionMode());
        assertEquals(EntityMovementMode.TRACKED, quadVee.getMovementMode());
    }

    @Test
    void aUnitThatKeepsItsModeEntersUnchanged() throws Exception {
        LandAirMek lam = (LandAirMek) serverUnit("Shadow Hawk LAM SHD-X2.mtf");

        walkOn(lam, "Shadow Hawk LAM SHD-X2.mtf", LandAirMek.CONV_MODE_MEK);

        assertEquals(ENTRY, lam.getPosition());
        assertEquals(LandAirMek.CONV_MODE_MEK, lam.getConversionMode());
        assertEquals(EntityMovementMode.BIPED, lam.getMovementMode());
    }

    @Test
    void theModeOfAnEntryStepNamesTheStartingMode() throws Exception {
        LandAirMek lam = (LandAirMek) serverUnit("Shadow Hawk LAM SHD-X2.mtf");
        QuadVee quadVee = (QuadVee) serverUnit("Boreas C.mtf");

        assertEquals(LandAirMek.CONV_MODE_FIGHTER, StartingModes.forMovementMode(lam, EntityMovementMode.WHEELED));
        assertEquals(LandAirMek.CONV_MODE_MEK, StartingModes.forMovementMode(lam, EntityMovementMode.BIPED));
        // The Shadow Hawk is bimodal: it has no AirMek mode
        assertNull(StartingModes.forMovementMode(lam, EntityMovementMode.WIGE));
        assertEquals(QuadVee.CONV_MODE_VEHICLE, StartingModes.forMovementMode(quadVee, EntityMovementMode.TRACKED));
        assertEquals(QuadVee.CONV_MODE_MEK, StartingModes.forMovementMode(quadVee, EntityMovementMode.QUAD));
    }

    @Test
    void aDeployedUnitCannotChooseAgainAndARestoreUndoesAChoice() throws Exception {
        LandAirMek lam = (LandAirMek) serverUnit("Shadow Hawk LAM SHD-X2.mtf");

        Runnable restore = StartingModes.choose(lam, LandAirMek.CONV_MODE_FIGHTER);
        assertEquals(LandAirMek.CONV_MODE_FIGHTER, lam.getConversionMode());
        restore.run();
        assertEquals(LandAirMek.CONV_MODE_MEK, lam.getConversionMode());
        assertEquals(EntityMovementMode.BIPED, lam.getMovementMode());

        lam.setDeployed(true);
        assertNull(StartingModes.choose(lam, LandAirMek.CONV_MODE_FIGHTER));
        assertEquals(LandAirMek.CONV_MODE_MEK, lam.getConversionMode());
    }

    private Entity serverUnit(String file) throws Exception {
        Entity unit = parse(file);
        unit.setId(game.getNextEntityId());
        unit.setOwner(game.getPlayer(0));
        unit.setStartingPos(Board.START_ANY);
        game.addEntity(unit);
        return unit;
    }

    /** Plots the walk-on as the client does, on its own copy of the unit put into {@code mode}, and executes it. */
    private void walkOn(Entity serverUnit, String file, int mode) throws Exception {
        Entity clientUnit = parse(file);
        clientUnit.setId(serverUnit.getId());
        clientUnit.setOwner(game.getPlayer(0));
        clientUnit.setGame(game);
        StartingModes.apply(clientUnit, mode);
        clientUnit.setPosition(ENTRY);
        clientUnit.setFacing(3);
        clientUnit.setDeployed(true);
        MovePath path = new MovePath(game, clientUnit);
        path.addStep(MoveStepType.DEPLOY);

        path.setEntity(serverUnit);
        new MovePathHandler(manager, serverUnit, path, null).processMovement();
    }

    private static Entity parse(String file) throws Exception {
        return new MekFileParser(new File(UNITS + file)).getEntity();
    }
}
