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
package megamek.common.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.GameBoardTestCase;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Minimum movement (TW p. 49): a unit may always enter one hex, but only when that is the only MP it spends in the
 * turn. A walk-on unit's entry into its first hex is that movement (TW p. 223), so a 1 MP platoon walking on cannot
 * take a second hex for free.
 */
class InfantryWalkOnMinimumMovementTest extends GameBoardTestCase {

    private static final Coords ENTRY = new Coords(1, 2);

    static {
        initializeBoard("WALK_ON_BOARD", """
              size 3 3
              hex 0101 0 "" ""
              hex 0201 0 "" ""
              hex 0301 0 "" ""
              hex 0102 0 "" ""
              hex 0202 0 "rough:1" ""
              hex 0302 0 "" ""
              hex 0103 0 "" ""
              hex 0203 0 "" ""
              hex 0303 0 "" ""
              end"""
        );
    }

    private Game game;
    private ConvInfantry platoon;

    @BeforeEach
    void beforeEach() {
        Board board = getBoard("WALK_ON_BOARD");
        game = new Game();
        game.setBoard(board);
        platoon = new ConvInfantry();
        platoon.setSquadSize(7);
        platoon.setSquadCount(4);
        platoon.autoSetInternal();
        platoon.setMovementMode(EntityMovementMode.INF_LEG);
        platoon.setGame(game);
        platoon.setId(game.getNextEntityId());
        game.addEntity(platoon);
        platoon.setDeployed(true);
        platoon.setPosition(ENTRY);
        platoon.setBoardId(board.getBoardId());
        platoon.setFacing(0);
        platoon.setElevation(0);
    }

    @Test
    void aPlatoonOnTheBoardEntersARoughHexWithItsOneMP() {
        assertEquals(1, platoon.getWalkMP());
        MovePath path = new MovePath(game, platoon);
        path.addStep(MoveStepType.FORWARDS);

        assertTrue(path.isMoveLegal(), "Minimum movement lets a 1 MP platoon enter the 2 MP rough hex.");
    }

    @Test
    void aWalkingOnPlatoonCannotTakeASecondHexForFree() {
        MovePath path = new MovePath(game, platoon);
        path.addStep(MoveStepType.DEPLOY);
        assertTrue(path.isMoveLegal(), "The entry hex is the platoon's one hex of movement.");

        path.addStep(MoveStepType.FORWARDS);

        assertFalse(path.isMoveLegal(), "Entering the board spent the MP; the next hex is not minimum movement.");
    }
}
