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
package megamek.common.units;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardType;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.GunEmplacement;
import megamek.common.game.Game;
import megamek.common.rules.RulesManager;
import megamek.common.rules.core.CoreRulesManager;
import megamek.common.rules.totalwarfare.TWRulesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers where a gun emplacement may deploy. Under the Core Rules it is an Asset (p.137, p.141): it needs no building
 * and may be placed anywhere 7 or more hexes outside every enemy deployment zone, or from the home edge of an enemy that
 * walks on. Under Total Warfare it deploys on a building inside its own zone.
 */
class EmplacementAssetDeploymentTest {

    private static final int BOARD_SIZE = 20;
    private static final int BOARD_ID = 0;

    private RulesManager originalRulesManager;
    private Game game;
    private Board board;
    private Player owner;
    private Player enemy;

    @BeforeEach
    void setUp() {
        EquipmentType.initializeTypes();
        originalRulesManager = Game.rulesManager;

        Hex[] hexes = new Hex[BOARD_SIZE * BOARD_SIZE];
        for (int index = 0; index < hexes.length; index++) {
            hexes[index] = new Hex();
        }
        board = new Board(BOARD_SIZE, BOARD_SIZE, hexes);
        board.setBoardType(BoardType.GROUND);

        owner = new Player(0, "Owner");
        owner.setTeam(1);
        owner.setStartingPos(Board.START_S);
        enemy = new Player(1, "Enemy");
        enemy.setTeam(2);
        enemy.setStartingPos(Board.START_N);
        game = new Game();
        game.setBoard(board);
        game.addPlayer(0, owner);
        game.addPlayer(1, enemy);
        game.setPhase(GamePhase.DEPLOYMENT);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = originalRulesManager;
    }

    private GunEmplacement emplacement() {
        GunEmplacement emplacement = new GunEmplacement();
        emplacement.setId(1);
        emplacement.setOwner(owner);
        emplacement.setStartingPos(Board.START_S);
        emplacement.setDeployRound(0);
        game.addEntity(emplacement);
        return emplacement;
    }

    @Test
    void underCoreRulesAnEmplacementKeepsSevenHexesFromAWalkOnEnemysHomeEdge() {
        Game.rulesManager = new CoreRulesManager();
        GunEmplacement emplacement = emplacement();

        assertFalse(board.isLegalDeployment(new Coords(10, 6), emplacement), "6 hexes from the enemy's edge");
        assertTrue(board.isLegalDeployment(new Coords(10, 7), emplacement), "7 hexes from the enemy's edge");
        assertTrue(board.isLegalDeployment(new Coords(10, 10), emplacement), "outside its own zone");
    }

    @Test
    void underCoreRulesAnEmplacementKeepsSevenHexesOutsideAnEnemyZone() {
        Game.rulesManager = new CoreRulesManager();
        enemy.setStartingPos(Board.START_CENTER);
        GunEmplacement emplacement = emplacement();

        assertFalse(board.isLegalDeployment(new Coords(10, 1), emplacement), "5 hexes from the enemy's zone");
        assertTrue(board.isLegalDeployment(new Coords(0, 0), emplacement), "9 hexes from the enemy's zone");
    }

    @Test
    void underCoreRulesAnEmplacementNeedsNoBuilding() {
        Game.rulesManager = new CoreRulesManager();

        assertFalse(emplacement().isLocationProhibited(new Coords(10, 10), BOARD_ID, 0));
    }

    @Test
    void underCoreRulesAnEmplacementStaysOutOfDepthOneOrDeeperWaterUnlessFrozen() {
        Game.rulesManager = new CoreRulesManager();
        GunEmplacement emplacement = emplacement();
        board.getHex(10, 10).addTerrain(new Terrain(Terrains.WATER, 0));
        board.getHex(10, 11).addTerrain(new Terrain(Terrains.WATER, 1));
        board.getHex(10, 12).addTerrain(new Terrain(Terrains.WATER, 2));
        board.getHex(10, 13).addTerrain(new Terrain(Terrains.WATER, 2));
        board.getHex(10, 13).addTerrain(new Terrain(Terrains.ICE, 1));

        assertFalse(emplacement.isLocationProhibited(new Coords(10, 10), BOARD_ID, 0), "Depth 0 has no effect");
        assertTrue(emplacement.isLocationProhibited(new Coords(10, 11), BOARD_ID, 0), "Depth 1");
        assertTrue(emplacement.isLocationProhibited(new Coords(10, 12), BOARD_ID, 0), "Depth 2");
        assertFalse(emplacement.isLocationProhibited(new Coords(10, 13), BOARD_ID, 0), "ice over Depth 2");
    }

    @Test
    void underCoreRulesAnEmplacementStaysOutOfABuildingHex() {
        Game.rulesManager = new CoreRulesManager();
        GunEmplacement emplacement = emplacement();
        board.getHex(10, 10).addTerrain(new Terrain(Terrains.BUILDING, 2));
        board.getHex(10, 10).addTerrain(new Terrain(Terrains.BLDG_ELEV, 3));

        assertTrue(emplacement.isLocationProhibited(new Coords(10, 10), BOARD_ID, 0), "inside");
        assertTrue(emplacement.isLocationProhibited(new Coords(10, 10), BOARD_ID, 3), "on the roof");
    }

    @Test
    void underTotalWarfareAnEmplacementDeploysOnABuildingInItsOwnZone() {
        Game.rulesManager = new TWRulesManager();
        Game.rulesManager.getRulesGame().setWalkOnDeployment(false);
        GunEmplacement emplacement = emplacement();

        assertTrue(board.isLegalDeployment(new Coords(10, 19), emplacement), "inside its own zone");
        assertFalse(board.isLegalDeployment(new Coords(10, 10), emplacement), "outside its own zone");
        assertTrue(emplacement.isLocationProhibited(new Coords(10, 19), BOARD_ID, 0), "no building there");
    }
}
