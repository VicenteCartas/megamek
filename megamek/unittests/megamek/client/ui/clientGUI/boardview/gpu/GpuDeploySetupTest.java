/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.exceptions.LocationFullException;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.DeploymentSetup;
import megamek.common.units.Mek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The deployment setup tiles of equipment whose states hold each other, as stealth armor and its ECM suite. */
class GpuDeploySetupTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void stealthAndItsEcmStaySwitchesLockedWhileTheyHoldEachOther() throws LocationFullException {
        Game game = new Game();
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        Mounted<?> ecm = mek.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_RIGHT_TORSO);
        Mounted<?> stealth = mek.addEquipment(EquipmentType.get("IS Stealth"), Mek.LOC_LEFT_TORSO);
        stealth.setModeImmediately(Mounted.MODE_ON);

        GpuDeploySetup.Tile ecmTile = tile(game, mek, ecm);
        assertEquals(GpuDeploySetup.Kind.TOGGLE, ecmTile.kind(), "The ECM keeps its On/Off switch under Stealth");
        assertEquals(2, ecmTile.values().size());
        assertTrue(ecmTile.on());
        assertTrue(ecmTile.isLocked(), "Stealth armor needs the ECM on, so its switch can't turn it off");
        assertFalse(tile(game, mek, stealth).isLocked(), "Stealth may always be switched off");

        stealth.setModeImmediately(Mounted.MODE_OFF);
        assertFalse(tile(game, mek, ecm).isLocked(), "With Stealth off the ECM switches freely");

        ecm.setModeImmediately(Mounted.MODE_OFF);
        GpuDeploySetup.Tile stealthTile = tile(game, mek, stealth);
        assertEquals(GpuDeploySetup.Kind.TOGGLE, stealthTile.kind());
        assertTrue(stealthTile.isLocked(), "Without an ECM on, Stealth can't come on");
    }

    private static GpuDeploySetup.Tile tile(Game game, Mek mek, Mounted<?> mounted) {
        DeploymentSetup current = DeploymentSetup.of(game, mek, null);
        GpuDeploySetup.Choice choice = GpuDeploySetup.capture(game, mek, current,
              DeploymentSetup.options(game, mek, null), current);
        GpuDeploySetup.Tile tile = choice.tile(GpuDeploySetup.EQUIPMENT + mek.getEquipmentNum(mounted));
        assertNotNull(tile, mounted.getName() + " has a setup tile");
        return tile;
    }
}
