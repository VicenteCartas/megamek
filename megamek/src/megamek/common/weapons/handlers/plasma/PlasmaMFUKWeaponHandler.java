/*
 * Copyright (c) 2005 - Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2007-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.common.weapons.handlers.plasma;

import java.io.Serial;
import java.util.Vector;

import megamek.common.Report;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.enums.HitDamageType;
import megamek.common.equipment.ArmorType;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.IBuilding;
import megamek.common.units.Mek;
import megamek.common.weapons.handlers.EnergyWeaponHandler;
import megamek.server.totalWarfare.TWGameManager;

public class PlasmaMFUKWeaponHandler extends EnergyWeaponHandler {
    @Serial
    private static final long serialVersionUID = -6816799343788643259L;

    public PlasmaMFUKWeaponHandler(ToHitData toHit, WeaponAttackAction waa, Game g, TWGameManager m)
          throws EntityLoadingException {
        super(toHit, waa, g, m);
        generalDamageType = HitDamageType.DAMAGE_HEAT;
    }

    @Override
    protected void handleEntityDamage(Entity entityTarget, Vector<Report> vPhaseReport,
          IBuilding bldg, int hits, int nCluster, int bldgAbsorbs) {
        if (entityTarget instanceof Mek) {
            if (!bSalvo) {
                // hits
                Report r = new Report(3390);
                r.subject = subjectId;
                vPhaseReport.addElement(r);
            }
            super.handleEntityDamage(entityTarget, vPhaseReport, bldg, hits, nCluster, bldgAbsorbs);

            if (missed) {
                return;
            }

            Report r = new Report(3400);
            r.subject = subjectId;
            r.indent(2);
            if ((entityTarget.getArmor(hit) > 0)
                  && ((entityTarget.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HEAT_DISSIPATING)
                  || (entityTarget.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REFLECTIVE))) {
                entityTarget.heatFromExternal += 2;
                r.add(2);
                r.choose(true);
                r.messageId = 3406;
                r.add(5);
                r.add(ArmorType.forEntity(entityTarget, hit.getLocation()).getName());
            } else {
                entityTarget.heatFromExternal += 5;
                r.add(5);
                r.choose(true);
            }
            vPhaseReport.addElement(r);
        } else {
            super.handleEntityDamage(entityTarget, vPhaseReport, bldg, hits, nCluster, bldgAbsorbs);
        }
    }

    @Override
    protected void handleIgnitionDamage(Vector<Report> vPhaseReport, IBuilding bldg, int hits) {
        if (!bSalvo) {
            // hits!
            Report r = new Report(2270);
            r.subject = subjectId;
            r.newlines = 0;
            vPhaseReport.addElement(r);
        }
        TargetRoll tn = new TargetRoll(weaponType.getFireTN(), weaponType.getName());
        if (tn.getValue() != TargetRoll.IMPOSSIBLE) {
            Report.addNewline(vPhaseReport);
            gameManager.tryIgniteHex(target.getPosition(), target.getBoardId(), subjectId, true, false,
                  tn, true, -1, vPhaseReport);
        }
    }

    @Override
    protected boolean igniteWhileClearing(Vector<Report> vPhaseReport) {
        return tryIgniteTarget(vPhaseReport, true, false, weaponFireRoll());
    }
}
