/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;
import java.util.Objects;

import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.enums.SkillLevel;

/**
 * A Battlefield Support Asset's crew and damage without a toolkit, as the Swing
 * {@code BattlefieldSupportAssetConfigDialog} and {@code BattlefieldSupportAssetDamageDialog} edit them: the crew's name
 * and grade (Veteran only for an asset with a veteran profile), and the current Destroy Check, from 0 to the original.
 */
public final class AssetForm {
    private final BattlefieldSupportAsset asset;
    private final boolean editable;
    private String name;
    private SkillLevel grade;
    private int destroyCheck;

    /** @param editable whether the crew may be changed */
    public AssetForm(BattlefieldSupportAsset asset, boolean editable) {
        this.asset = Objects.requireNonNull(asset);
        this.editable = editable;
        name = Objects.requireNonNullElse(asset.getCrew().getName(0), "");
        grade = asset.getCrewSkillLevel();
        destroyCheck = Math.clamp(asset.getDestroyCheck(), 0, Math.max(0, asset.getODestroyCheck()));
    }

    public BattlefieldSupportAsset asset() {
        return asset;
    }

    public boolean editable() {
        return editable;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = Objects.requireNonNullElse(name, "");
    }

    /** @return the grades the asset may have: Regular, and Veteran with a veteran profile */
    public List<SkillLevel> grades() {
        return asset.hasVeteranProfile() ? List.of(SkillLevel.REGULAR, SkillLevel.VETERAN)
              : List.of(SkillLevel.REGULAR);
    }

    /** @return whether the grade is fixed, as the asset has no veteran profile */
    public boolean gradeLocked() {
        return !asset.hasVeteranProfile();
    }

    public SkillLevel grade() {
        return grade;
    }

    public void grade(SkillLevel grade) {
        if (grades().contains(grade)) {
            this.grade = grade;
        }
    }

    /** Puts the crew name and grade on the asset, when it is editable. */
    public void applyCrew() {
        if (!editable) {
            return;
        }
        asset.getCrew().setName(name, 0);
        if (asset.hasVeteranProfile()) {
            asset.setVeteranCrew(grade == SkillLevel.VETERAN);
        }
    }

    /** @return the Destroy Check the asset was built with, the most it can be */
    public int originalDestroyCheck() {
        return Math.max(0, asset.getODestroyCheck());
    }

    public int destroyCheck() {
        return destroyCheck;
    }

    public void destroyCheck(int value) {
        destroyCheck = Math.clamp(value, 0, originalDestroyCheck());
    }

    /** Puts the current Destroy Check on the asset. */
    public void applyDamage() {
        asset.setDestroyCheck(destroyCheck);
    }
}
