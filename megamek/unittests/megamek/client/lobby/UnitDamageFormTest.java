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
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import megamek.common.compute.damage.PreExistingDamageLevel;
import megamek.common.equipment.AmmoMounted;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;

class UnitDamageFormTest {
    private static final String UNITS = "testresources/megamek/common/units/";

    private static Entity load(String file) throws Exception {
        Entity entity = new MekFileParser(new File(UNITS + file)).getEntity();
        Game game = new Game();
        entity.setGame(game);
        entity.setId(game.getNextEntityId());
        if (entity.getCrew() == null) {
            entity.setCrew(new Crew(CrewType.SINGLE));
        }
        game.addEntity(entity);
        return entity;
    }

    @Test
    void coversPaperdollUnitsAndLeavesTheRestToSwing() throws Exception {
        assertTrue(UnitDamageForm.supported(load("Atlas AS7-D.mtf")));
        assertTrue(UnitDamageForm.supported(load("Bulldog Medium Tank.blk")));
        assertTrue(UnitDamageForm.supported(load("SOAR VTOL.blk")));
        assertTrue(UnitDamageForm.supported(load("Cheetah F-11.blk")));
        assertFalse(UnitDamageForm.supported(load("Foot Platoon (AFFS) (Laser 3067+).blk")));
        assertFalse(UnitDamageForm.supported(load("Union (3055).blk")));
        assertFalse(UnitDamageForm.supported(load("Simple Gun Emplacement (Gauss).blk")));
        assertFalse(UnitDamageForm.supported(null));
    }

    @Test
    void aMekShowsItsLocationsWithTheirSystemsAndCrew() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas);

        UnitDamageForm.Place torso = form.place("CT");
        assertNotNull(torso);
        assertEquals(atlas.getOArmor(Mek.LOC_CENTER_TORSO), torso.armor().max());
        assertEquals(atlas.getOArmor(Mek.LOC_CENTER_TORSO, true), torso.rear().max());
        assertEquals(atlas.getOInternal(Mek.LOC_CENTER_TORSO), torso.internal().max());
        assertTrue(labels(torso).contains("Gyro"));
        assertTrue(labels(torso).contains("Heat"));

        UnitDamageForm.Place head = form.place("HD");
        assertNull(head.rear());
        assertTrue(labels(head).contains("Pilot Hits"));
        assertTrue(labels(head).contains("Cockpit"));

        UnitDamageForm.Place arm = form.place("LA");
        assertTrue(labels(arm).contains("Blown Off"));
        assertTrue(labels(arm).contains("Shoulder"));
        assertTrue(form.general().stream().anyMatch(value -> value.label().equals("Prone")));
        assertFalse(form.changed());
    }

    @Test
    void anUnchangedFormLeavesTheUnitAsItWas() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        AmmoMounted ammo = atlas.getAmmo().get(0);
        ammo.setShotsLeft(1);

        UnitDamageForm.of(atlas).apply();

        for (int location = 0; location < atlas.locations(); location++) {
            assertEquals(atlas.getOArmor(location), atlas.getArmor(location));
            assertEquals(atlas.getOInternal(location), atlas.getInternal(location));
        }
        assertEquals(1, ammo.getBaseShotsLeft(), "ammunition is left to the Swing editor");
    }

    @Test
    void editsReachTheUnitAndRepairAllUndoesThem() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas);
        UnitDamageForm.Place torso = form.place("CT");
        torso.armor().value(torso.armor().max() - 5);
        torso.internal().value(3);
        value(torso, "Gyro").value(1);
        value(form.place("HD"), "Pilot Hits").value(2);
        assertTrue(form.changed());

        form.apply();

        assertEquals(atlas.getOArmor(Mek.LOC_CENTER_TORSO) - 5, atlas.getArmor(Mek.LOC_CENTER_TORSO));
        assertEquals(3, atlas.getInternal(Mek.LOC_CENTER_TORSO));
        assertEquals(1, ((Mek) atlas).getGyroHits());
        assertEquals(2, atlas.getCrew().getHits(0));

        UnitDamageForm again = UnitDamageForm.of(atlas);
        assertEquals(3, again.place("CT").internal().value());
        again.repairAll();
        again.apply();

        assertEquals(atlas.getOArmor(Mek.LOC_CENTER_TORSO), atlas.getArmor(Mek.LOC_CENTER_TORSO));
        assertEquals(atlas.getOInternal(Mek.LOC_CENTER_TORSO), atlas.getInternal(Mek.LOC_CENTER_TORSO));
        assertEquals(0, ((Mek) atlas).getGyroHits());
        assertEquals(0, atlas.getCrew().getHits(0));
    }

    @Test
    void aLimbBroughtBackComesBackWhole() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas);
        UnitDamageForm.Place arm = form.place("LA");
        value(arm, "Blown Off").value(1);
        form.apply();
        assertTrue(atlas.isLocationBlownOff(Mek.LOC_LEFT_ARM));

        UnitDamageForm again = UnitDamageForm.of(atlas);
        UnitDamageForm.Place gone = again.place("LA");
        gone.armor().value(0);
        value(gone, "Blown Off").value(0);
        assertEquals(gone.armor().max(), gone.armor().value());
        assertEquals(gone.internal().max(), gone.internal().value());
        again.apply();
        assertFalse(atlas.isLocationBlownOff(Mek.LOC_LEFT_ARM));
        assertEquals(atlas.getOInternal(Mek.LOC_LEFT_ARM), atlas.getInternal(Mek.LOC_LEFT_ARM));
    }

    @Test
    void aVehicleCarriesMotiveDamageAndStabilizers() throws Exception {
        Entity bulldog = load("Bulldog Medium Tank.blk");
        UnitDamageForm form = UnitDamageForm.of(bulldog);
        UnitDamageForm.Value motive = form.general().stream()
              .filter(value -> value.label().equals("Motive Damage")).findFirst().orElseThrow();
        assertEquals(4, motive.max());
        motive.value(1);
        value(form.place(bulldog.getLocationAbbr(Tank.LOC_FRONT)), "Stabilizer").value(1);

        form.apply();

        assertTrue(((Tank) bulldog).hasMinorMovementDamage());
        assertTrue(((Tank) bulldog).isStabiliserHit(Tank.LOC_FRONT));
    }

    @Test
    void aFighterEditsItsStructuralIntegrityAmongTheGeneralValues() throws Exception {
        Entity cheetah = load("Cheetah F-11.blk");
        UnitDamageForm form = UnitDamageForm.of(cheetah);
        assertNotNull(form.structuralIntegrity());
        assertTrue(form.general().contains(form.structuralIntegrity()));
        form.structuralIntegrity().value(2);

        DamageEditSpec spec = form.spec();
        assertEquals(2, spec.internal[0]);
        assertTrue(spec.ammoShots.isEmpty());
        form.apply();
        assertEquals(2, ((megamek.common.units.Aero) cheetah).getSI());
    }

    @Test
    void aRollDamagesTheFormNotTheUnitAndNoneGoesBack() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        int armor = atlas.getTotalArmor();
        UnitDamageForm form = UnitDamageForm.of(atlas);
        assertTrue(form.rollable());
        boolean hit = false;
        for (int roll = 0; roll < 20 && !hit; roll++) {
            form.roll(PreExistingDamageLevel.HEAVY);
            assertTrue(form.changed(), "a heavy roll damages the form");
            hit = form.places().stream().flatMap(place -> place.values().stream())
                  .anyMatch(value -> value.kind() == UnitDamageForm.Kind.BOXES && value.value() > 0);
        }
        assertTrue(hit, "a heavy roll's critical hits mark the hit boxes");
        assertEquals(armor, atlas.getTotalArmor(), "the unit is only read");

        form.roll(PreExistingDamageLevel.NONE);
        assertFalse(form.changed(), "None goes back to the unit as read");
        form.roll(PreExistingDamageLevel.LIGHT);
        form.apply();
        assertTrue(atlas.getTotalArmor() < armor, "an applied roll reaches the unit");
    }

    @Test
    void aRollStartsFromTheUnitAsReadWithoutRefillingALostLimb() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas);
        value(form.place("LA"), "Blown Off").value(1);
        form.apply();
        UnitDamageForm again = UnitDamageForm.of(atlas);
        again.roll(PreExistingDamageLevel.MODERATE);
        again.roll(PreExistingDamageLevel.NONE);
        assertFalse(again.changed(), "restoring a blown-off limb's switch does not fill its armour");
    }

    @Test
    void vehiclesAndFightersRollToo() throws Exception {
        for (String file : List.of("Bulldog Medium Tank.blk", "Cheetah F-11.blk")) {
            UnitDamageForm form = UnitDamageForm.of(load(file));
            assertTrue(form.rollable(), file);
            form.roll(PreExistingDamageLevel.HEAVY);
            assertTrue(form.changed(), file);
        }
    }

    private static List<String> labels(UnitDamageForm.Place place) {
        return place.values().stream().map(UnitDamageForm.Value::label).toList();
    }

    private static UnitDamageForm.Value value(UnitDamageForm.Place place, String label) {
        return place.values().stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow();
    }
}
