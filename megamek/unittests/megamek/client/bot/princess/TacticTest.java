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
package megamek.client.bot.princess;

import static megamek.client.bot.princess.BehaviorChange.Trait.AGGRESSION;
import static megamek.client.bot.princess.BehaviorChange.Trait.BRAVERY;
import static megamek.client.bot.princess.BehaviorChange.Trait.FALL_SHAME;
import static megamek.client.bot.princess.BehaviorChange.Trait.MUTUAL_SUPPORT;
import static megamek.client.bot.princess.BehaviorChange.Trait.SELF_PRESERVATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.EnumMap;
import java.util.Map;

import megamek.client.ui.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link Tactic}, the quick tactics both bot command UIs offer.
 */
class TacticTest {

    private static Map<BehaviorChange.Trait, Integer> valuesOf(Tactic tactic) {
        Map<BehaviorChange.Trait, Integer> values = new EnumMap<>(BehaviorChange.Trait.class);
        tactic.indices().forEach(index -> values.put(index.trait(), index.value()));
        return values;
    }

    @Test
    void theTacticsKeepTheirValues() {
        assertEquals(Map.of(BRAVERY, 10, AGGRESSION, 10, SELF_PRESERVATION, 5), valuesOf(Tactic.ALPHA_STRIKE));
        assertEquals(Map.of(SELF_PRESERVATION, 6, BRAVERY, 8, AGGRESSION, 3, FALL_SHAME, 7),
              valuesOf(Tactic.CAREFUL_AIM));
        assertEquals(Map.of(SELF_PRESERVATION, 2, FALL_SHAME, 2), valuesOf(Tactic.DOUBLE_TIME_MARCH));
        assertEquals(Map.of(MUTUAL_SUPPORT, 0), valuesOf(Tactic.DISPERSE));
    }

    @Test
    void onlyNoPrisonersDropsMercyAndItSetsNoIndex() {
        for (Tactic tactic : Tactic.values()) {
            assertEquals(tactic == Tactic.NO_PRISONERS, tactic.dropsMercy(), tactic.name());
            assertEquals(tactic.dropsMercy(), tactic.indices().isEmpty(), tactic.name());
        }
    }

    @ParameterizedTest
    @EnumSource(Tactic.class)
    void everyTacticHasItsTexts(Tactic tactic) {
        assertFalse(Messages.getString("BotCommandPanel." + tactic.key() + ".title").startsWith("!"));
        assertFalse(Messages.getString("BotCommandPanel." + tactic.key() + ".tooltip").startsWith("!"));
    }

    @Test
    void aTacticChangesOnlyItsIndices() {
        BehaviorSettings settings = new BehaviorSettings();
        settings.setMutualSupportIndex(4);
        Princess princess = mock(Princess.class);
        when(princess.getBehaviorSettings()).thenReturn(settings);

        Tactic.FINAL_GLORY.indices().forEach(index -> index.apply(princess));

        assertEquals(0, settings.getSelfPreservationIndex());
        assertEquals(10, settings.getBraveryIndex());
        assertEquals(10, settings.getHyperAggressionIndex());
        assertEquals(6, settings.getFallShameIndex());
        assertEquals(4, settings.getMutualSupportIndex(), "a tactic must leave the other indices alone");
    }
}
