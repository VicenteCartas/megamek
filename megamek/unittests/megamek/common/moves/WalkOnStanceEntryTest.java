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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.GameBoardTestCase;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A walk-on unit chooses its stance in its entry hex before it enters (Core Rulebook p. 224). Entering on that hex is
 * the deployment, not movement, so a unit set up prone or hull-down may enter; any move after it obeys its stance.
 */
class WalkOnStanceEntryTest extends GameBoardTestCase {

    private static final Coords ENTRY = new Coords(1, 2);

    static {
        initializeBoard("WALK_ON_STANCE_BOARD", """
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
              end"""
        );
    }

    private Game game;
    private BipedMek mek;

    @BeforeEach
    void beforeEach() {
        Board board = getBoard("WALK_ON_STANCE_BOARD");
        game = new Game();
        game.setBoard(board);
        mek = new BipedMek();
        mek.setGame(game);
        mek.setId(game.getNextEntityId());
        mek.setWeight(50);
        mek.setOriginalWalkMP(4);
        mek.setMovementMode(EntityMovementMode.BIPED);
        game.addEntity(mek);
        mek.setDeployed(true);
        mek.setPosition(ENTRY);
        mek.setBoardId(board.getBoardId());
        mek.setFacing(0);
        mek.setElevation(0);
    }

    @Test
    void aProneMekEntersOnItsEntryHex() {
        mek.setProne(true);
        MovePath path = new MovePath(game, mek);
        path.addStep(MoveStepType.DEPLOY);

        assertTrue(path.isMoveLegal(), "Entering prone is the unit's chosen stance in its entry hex.");
    }

    @Test
    void aHullDownMekEntersOnItsEntryHex() {
        mek.setHullDown(true);
        MovePath path = new MovePath(game, mek);
        path.addStep(MoveStepType.DEPLOY);

        assertTrue(path.isMoveLegal(), "Entering hull-down is the unit's chosen stance in its entry hex.");
    }

    @Test
    void aProneMekMustStillGetUpToWalkOnFromItsEntryHex() {
        mek.setProne(true);
        MovePath path = new MovePath(game, mek);
        path.addStep(MoveStepType.DEPLOY);
        path.addStep(MoveStepType.FORWARDS);

        assertFalse(path.isMoveLegal(), "A prone unit must stand before it walks on from its entry hex.");
    }

    @Test
    void aProneMekStandsUpOnItsEntryHexAndWalksOn() {
        mek.setProne(true);
        MovePath path = new MovePath(game, mek);
        path.addStep(MoveStepType.DEPLOY);
        path.addStep(MoveStepType.GET_UP);
        path.addStep(MoveStepType.FORWARDS);

        assertTrue(path.isMoveLegal(), "Standing up on the entry hex lets the unit walk on from it.");
    }
}
