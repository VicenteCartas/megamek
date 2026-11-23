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
package megamek.client.ui.entityreadout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;

import megamek.client.ui.util.ViewFormatting;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.templates.TROView;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.IAero;
import megamek.common.units.LandAirMek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Readouts read a LAM in its base mode, then put the live unit back as it was. A landed fighter is wheeled; restoring
 * only its conversion mode made it aerodyne, airborne at elevation 999, so the board drew it in the sky.
 */
class LandAirMekReadoutModeTest {

    @BeforeAll
    static void beforeAll() {
        EquipmentType.initializeTypes();
    }

    @Test
    void readoutLeavesALandedFighterOnTheGround() throws Exception {
        LandAirMek lam = landedFighter();

        EntityReadout.createReadout(lam, false, false).getFullReadout(null, ViewFormatting.NONE);

        assertLanded(lam);
    }

    @Test
    void troViewLeavesALandedFighterOnTheGround() throws Exception {
        LandAirMek lam = landedFighter();

        TROView.createView(lam, ViewFormatting.NONE).processTemplate();

        assertLanded(lam);
    }

    private static LandAirMek landedFighter() throws Exception {
        LandAirMek lam = (LandAirMek) new MekFileParser(
              new File("testresources/megamek/common/units/Shadow Hawk LAM SHD-X2.mtf")).getEntity();
        lam.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
        ((IAero) lam).land();
        assertLanded(lam);
        return lam;
    }

    private static void assertLanded(LandAirMek lam) {
        assertEquals(LandAirMek.CONV_MODE_FIGHTER, lam.getConversionMode());
        assertEquals(EntityMovementMode.WHEELED, lam.getMovementMode());
        assertFalse(lam.isAirborne());
        assertEquals(0, lam.getElevation());
    }
}
