/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.enums.SkillLevel;
import org.junit.jupiter.api.Test;

class AssetFormTest {

    private static BattlefieldSupportAsset asset(boolean veteran) {
        BattlefieldSupportAsset asset = new BattlefieldSupportAsset();
        asset.setODestroyCheck(6);
        asset.setDestroyCheck(6);
        if (veteran) {
            asset.setVeteranCost(4);
        }
        asset.getCrew().setName("Crew", 0);
        return asset;
    }

    @Test
    void anAssetWithoutAVeteranProfileStaysRegular() {
        AssetForm form = new AssetForm(asset(false), true);
        assertTrue(form.gradeLocked());
        assertEquals(List.of(SkillLevel.REGULAR), form.grades());
        form.grade(SkillLevel.VETERAN);
        assertEquals(SkillLevel.REGULAR, form.grade());
    }

    @Test
    void applyCrewPutsNameAndGradeOnTheAsset() {
        BattlefieldSupportAsset asset = asset(true);
        AssetForm form = new AssetForm(asset, true);
        assertFalse(form.gradeLocked());
        assertEquals("Crew", form.name());
        form.name("Hammer");
        form.grade(SkillLevel.VETERAN);
        form.applyCrew();
        assertEquals("Hammer", asset.getCrew().getName(0));
        assertEquals(SkillLevel.VETERAN, asset.getCrewSkillLevel());
    }

    @Test
    void aLockedFormLeavesTheCrewAlone() {
        BattlefieldSupportAsset asset = asset(true);
        AssetForm form = new AssetForm(asset, false);
        form.name("Hammer");
        form.applyCrew();
        assertEquals("Crew", asset.getCrew().getName(0));
    }

    @Test
    void destroyCheckStaysBetweenZeroAndTheOriginal() {
        BattlefieldSupportAsset asset = asset(false);
        AssetForm form = new AssetForm(asset, true);
        assertEquals(6, form.originalDestroyCheck());
        form.destroyCheck(9);
        assertEquals(6, form.destroyCheck());
        form.destroyCheck(-1);
        assertEquals(0, form.destroyCheck());
        form.destroyCheck(3);
        form.applyDamage();
        assertEquals(3, asset.getDestroyCheck());
    }
}
