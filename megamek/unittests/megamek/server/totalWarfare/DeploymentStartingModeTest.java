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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import megamek.common.GameBoardTestCase;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.LandAirMek;
import megamek.common.units.QuadVee;
import megamek.common.units.StartingModes;
import megamek.server.GameManagerPacketHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A LAM or QuadVee may change the mode it starts the game in at its first deployment: the deployment packet carries
 * the mode after its loaded units, and the server applies it before it checks and places the unit.
 */
class DeploymentStartingModeTest extends GameBoardTestCase {

    private static final Coords HEX = new Coords(1, 1);

    private TWGameManager gameManager;
    private Game game;
    private RulesManager previousRules;

    static {
        initializeBoard("STARTING_MODE_3x3", """
                size 3 3
                hex 0101 0 "" ""
                hex 0201 0 "" ""
                hex 0301 0 "" ""
                hex 0102 0 "" ""
                hex 0202 0 "" ""
                hex 0302 0 "" ""
                hex 0103 0 "" ""
                hex 0203 0 "" ""
                hex 0303 0 "" ""
                end""");
    }

    @BeforeAll
    static void beforeAll() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void beforeEach() {
        previousRules = Game.rulesManager;
        game = new Game();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        game.setBoard(0, getBoard("STARTING_MODE_3x3"));
        game.addPlayer(0, new Player(0, "Test Player"));
        game.setPhase(GamePhase.DEPLOYMENT);
        gameManager = mock(TWGameManager.class);
        when(gameManager.getGame()).thenReturn(game);
        when(gameManager.getPacketHelper()).thenReturn(mock(GameManagerPacketHelper.class));
        doNothing().when(gameManager).entityUpdate(anyInt());
        doNothing().when(gameManager).addReport(any(Report.class));
    }

    @AfterEach
    void afterEach() {
        Game.rulesManager = previousRules;
    }

    @Test
    void aMekLamDeploysAsALandedFighter() throws Exception {
        LandAirMek lam = (LandAirMek) unit("Shadow Hawk LAM SHD-X2.mtf");

        deploy(lam, 0, LandAirMek.CONV_MODE_FIGHTER);

        assertEquals(HEX, lam.getPosition());
        assertEquals(LandAirMek.CONV_MODE_FIGHTER, lam.getConversionMode());
        assertEquals(EntityMovementMode.WHEELED, lam.getMovementMode());
        assertFalse(lam.isAirborne());
        assertEquals(0, lam.getElevation());
    }

    @Test
    void anAirborneFighterLamDeploysAsAMekOnTheGround() throws Exception {
        LandAirMek lam = (LandAirMek) unit("Shadow Hawk LAM SHD-X2.mtf");
        lam.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
        lam.liftOff(3);

        deploy(lam, 0, LandAirMek.CONV_MODE_MEK);

        assertEquals(HEX, lam.getPosition());
        assertEquals(LandAirMek.CONV_MODE_MEK, lam.getConversionMode());
        assertEquals(EntityMovementMode.BIPED, lam.getMovementMode());
        assertFalse(lam.isAirborne());
        assertEquals(0, lam.getElevation());
    }

    @Test
    void aQuadVeeDeploysInVehicleMode() throws Exception {
        QuadVee quadVee = (QuadVee) unit("Boreas C.mtf");

        deploy(quadVee, 0, QuadVee.CONV_MODE_VEHICLE);

        assertEquals(HEX, quadVee.getPosition());
        assertEquals(QuadVee.CONV_MODE_VEHICLE, quadVee.getConversionMode());
        assertEquals(EntityMovementMode.TRACKED, quadVee.getMovementMode());
    }

    @Test
    void aRefusedDeploymentKeepsTheLobbyMode() throws Exception {
        LandAirMek lam = (LandAirMek) unit("Shadow Hawk LAM SHD-X2.mtf");

        // Off the map: refused
        deploy(lam, new Coords(7, 7), 0, LandAirMek.CONV_MODE_FIGHTER);

        assertNull(lam.getPosition());
        assertEquals(LandAirMek.CONV_MODE_MEK, lam.getConversionMode());
        assertEquals(EntityMovementMode.BIPED, lam.getMovementMode());
    }

    @Test
    void onlyConvertingUnitsHaveStartingModes() throws Exception {
        LandAirMek lam = (LandAirMek) unit("Shadow Hawk LAM SHD-X2.mtf");
        // A bimodal LAM has no AirMek mode
        assertEquals(LandAirMek.LAM_BIMODAL, lam.getLAMType());
        assertEquals(List.of(LandAirMek.CONV_MODE_MEK, LandAirMek.CONV_MODE_FIGHTER), StartingModes.of(lam));
        assertEquals(List.of(QuadVee.CONV_MODE_MEK, QuadVee.CONV_MODE_VEHICLE), StartingModes.of(unit("Boreas C.mtf")));
        assertEquals(List.of(), StartingModes.of(unit("Charger C.mtf")));
    }

    private Entity unit(String file) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        entity.setId(1);
        entity.setOwner(game.getPlayer(0));
        entity.setStartingPos(Board.START_ANY);
        game.addEntity(entity);
        game.setTurnVector(List.of(new GameTurn(0)));
        game.setTurnIndex(0, 0);
        return entity;
    }

    private void deploy(Entity entity, int height, int mode) throws Exception {
        deploy(entity, HEX, height, mode);
    }

    private void deploy(Entity entity, Coords coords, int height, int mode) throws Exception {
        new DeploymentProcessor(gameManager).receiveDeployment(new Packet(PacketCommand.ENTITY_DEPLOY,
              entity.getId(), coords, 0, 0, height, 0, false, mode), 0);
    }
}
