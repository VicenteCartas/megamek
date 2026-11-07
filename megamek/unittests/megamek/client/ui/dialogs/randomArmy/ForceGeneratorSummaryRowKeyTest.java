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
package megamek.client.ui.dialogs.randomArmy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.TreeSet;

import megamek.client.ratgenerator.ForceComposition.RowKey;
import megamek.client.ratgenerator.InfantryClass;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

/**
 * The force summary splits conventional infantry into one row per infantry class.
 */
class ForceGeneratorSummaryRowKeyTest {

    @Test
    void infantryRowsAreLabelledByClass() {
        RowKey jump = new RowKey(UnitType.INFANTRY, InfantryClass.JUMP);
        RowKey unclassified = new RowKey(UnitType.INFANTRY, null);

        assertEquals(UnitType.getTypeDisplayableName(UnitType.INFANTRY) + " (" + InfantryClass.JUMP.getDisplayName()
              + ")", jump.label());
        assertEquals(UnitType.getTypeDisplayableName(UnitType.INFANTRY), unclassified.label());
    }

    @Test
    void rowsSortByUnitTypeThenClass() {
        TreeSet<RowKey> rows = new TreeSet<>(List.of(
              new RowKey(UnitType.INFANTRY, InfantryClass.JUMP),
              new RowKey(UnitType.MEK, null),
              new RowKey(UnitType.INFANTRY, InfantryClass.LIGHT),
              new RowKey(UnitType.INFANTRY, null)));

        List<RowKey> expected = List.of(
              new RowKey(UnitType.MEK, null),
              new RowKey(UnitType.INFANTRY, null),
              new RowKey(UnitType.INFANTRY, InfantryClass.LIGHT),
              new RowKey(UnitType.INFANTRY, InfantryClass.JUMP));
        assertEquals(expected, List.copyOf(rows));
    }
}
