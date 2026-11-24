/*
 * Copyright (c) 2026 - The MegaMek Team. All Rights Reserved.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.exceptions.LocationFullException;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.DeploymentSetup;
import megamek.common.units.DeploymentSetup.Stance;
import megamek.common.units.Mek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The server applies the setup a unit deploys with, and refuses one it may not choose. */
class DeploymentProcessorSetupTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    private static BipedMek mek(Game game) {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        return mek;
    }

    @Test
    void appliesTheChosenSetup() {
        Game game = new Game();
        BipedMek mek = mek(game);

        DeploymentProcessor.applySetup(mek, DeploymentSetup.of(game, mek, null).withStance(Stance.HULL_DOWN), null);

        assertTrue(mek.isHullDown());
    }

    @Test
    void unitThatDeployedBeforeKeepsItsState() {
        Game game = new Game();
        BipedMek mek = mek(game);
        mek.setNeverDeployed(false);

        DeploymentProcessor.applySetup(mek, DeploymentSetup.of(game, mek, null).withStance(Stance.PRONE), null);

        assertFalse(mek.isProne());
    }

    @Test
    void secondEcmSuiteSwitchedOnIsRefused() throws LocationFullException {
        Game game = new Game();
        BipedMek mek = mek(game);
        Mounted<?> first = mek.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_RIGHT_TORSO);
        Mounted<?> second = mek.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_TORSO);
        second.setModeImmediately(Mounted.MODE_OFF);
        int ecm = DeploymentSetup.modeIndex(second, first.curMode().getName());

        DeploymentProcessor.applySetup(mek, DeploymentSetup.of(game, mek, null)
              .withStance(Stance.PRONE).withMode(mek.getEquipmentNum(second), ecm), null);

        assertEquals(Mounted.MODE_OFF, second.curMode().getName());
        assertFalse(mek.isProne(), "A refused setup is refused whole");
    }
}
