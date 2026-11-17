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
package megamek.common.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardType;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.rules.RulesManager;
import megamek.common.rules.totalwarfare.TWRulesManager;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Tank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Under walk-on deployment the server counts a deployment turn only for a unit that cannot walk on, so the turn must
 * be offered to such a unit first; a unit that walks on is offered only once none is left.
 */
class WalkOnDeploymentOrderTest {
    private RulesManager originalRulesManager;
    private Game game;
    private Player owner;

    @BeforeEach
    void setUp() {
        EquipmentType.initializeTypes();
        originalRulesManager = Game.rulesManager;
        Game.rulesManager = new TWRulesManager();
        Game.rulesManager.getRulesGame().setWalkOnDeployment(true);
        Hex[] hexes = new Hex[16 * 17];
        for (int index = 0; index < hexes.length; index++) {
            hexes[index] = new Hex();
        }
        Board board = new Board(16, 17, hexes);
        board.setBoardType(BoardType.GROUND);
        owner = new Player(0, "Owner");
        owner.setStartingPos(Board.START_N);
        game = new Game();
        game.setBoard(board);
        game.addPlayer(0, owner);
        game.setPhase(GamePhase.DEPLOYMENT);
        game.setCurrentRound(1);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = originalRulesManager;
    }

    @Test
    void theTurnGoesToAUnitThatCannotWalkOnFirst() {
        Tank walksOn = tank(1, Board.START_N);
        Tank placed = tank(2, Board.START_CENTER);
        GameTurn turn = new GameTurn(owner.getId());

        assertEquals(placed.getId(), game.getFirstDeployableEntityNum(turn));
        assertEquals(placed.getId(), game.getNextDeployableEntityNum(turn, 0));

        placed.setDeployed(true);
        assertEquals(walksOn.getId(), game.getFirstDeployableEntityNum(turn), "only the walk-on unit is left");
    }

    @Test
    void roundZeroInitiativeIsSkippedWhenEveryUnitWalksOn() {
        game.setCurrentRound(0);
        game.setPhase(GamePhase.INITIATIVE_REPORT);
        tank(1, Board.START_N);

        assertFalse(game.isCurrentPhasePlayable(), "the deployment phase it orders has no turns");

        tank(2, Board.START_CENTER);
        assertTrue(game.isCurrentPhasePlayable(), "a unit still deploys in the deployment phase");
    }

    private Tank tank(int id, int startingPos) {
        Tank tank = new Tank();
        tank.setId(id);
        tank.setOwner(owner);
        tank.setWeight(50);
        tank.setMovementMode(EntityMovementMode.TRACKED);
        tank.setStartingPos(startingPos);
        tank.setDeployRound(0);
        game.addEntity(tank);
        return tank;
    }
}
