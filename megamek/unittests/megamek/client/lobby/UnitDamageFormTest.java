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

import megamek.common.bays.Bay;
import megamek.common.compute.damage.PreExistingDamageLevel;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
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
    void coversEveryUnitAsTheSwingEditorDoes() throws Exception {
        for (String file : List.of("Atlas AS7-D.mtf", "Bulldog Medium Tank.blk", "SOAR VTOL.blk",
              "Cheetah F-11.blk", "Foot Platoon (AFFS) (Laser 3067+).blk", "Afreet Med BA (HH) (Sqd4).blk",
              "Union (3055).blk", "Simple Gun Emplacement (Gauss).blk", "Simple Building Entity.blk")) {
            Entity entity = load(file);
            assertTrue(UnitDamageForm.supported(entity), file);
            UnitDamageForm form = UnitDamageForm.of(entity);
            assertFalse(form.changed(), file);
            form.apply();
        }
        assertFalse(UnitDamageForm.supported(null));
    }

    @Test
    void conventionalInfantryEditsItsTroopersLeft() throws Exception {
        Infantry platoon = (Infantry) load("Foot Platoon (AFFS) (Laser 3067+).blk");
        UnitDamageForm form = UnitDamageForm.of(platoon);
        assertTrue(form.places().isEmpty(), "a platoon has no locations to step through");
        UnitDamageForm.Value troopers = general(form, "Troopers Left");
        assertEquals(platoon.getSquadCount() * platoon.getSquadSize(), troopers.max());
        assertEquals(troopers.max(), troopers.value());
        assertTrue(platoon.isMechanized() || form.general().stream().anyMatch(v -> v.label().equals("Dug In")));
        troopers.value(10);
        form.apply();
        assertEquals(10, UnitDamageForm.of(platoon).general().get(0).value());
    }

    @Test
    void battleArmorEditsEachTrooper() throws Exception {
        Entity squad = load("Afreet Med BA (HH) (Sqd4).blk");
        UnitDamageForm form = UnitDamageForm.of(squad);
        assertTrue(form.places().size() >= 4, "each trooper is a location");
        UnitDamageForm.Place trooper = form.places().get(0);
        assertNotNull(trooper.armor());
        trooper.armor().value(0);
        form.apply();
        assertEquals(0, Math.max(squad.getArmor(trooper.location()), 0));
    }

    @Test
    void aDropShipCarriesItsLargeCraftSystemsAndBays() throws Exception {
        Dropship union = (Dropship) load("Union (3055).blk");
        UnitDamageForm form = UnitDamageForm.of(union);
        assertEquals(6, general(form, "Engine").max());
        general(form, "Left Thruster");
        general(form, "K-F Boom").value(1);
        general(form, "Docking Collar");
        UnitDamageForm.Place bays = form.places().get(form.places().size() - 1);
        assertEquals("Transport bays", bays.name());
        Bay first = union.getTransportBays().get(0);
        UnitDamageForm.Value capacity = bays.values().get(0);
        assertEquals((int) Math.ceil(first.getCapacity()), capacity.max());
        capacity.value(0);
        form.apply();
        assertTrue(union.isKFBoomDamaged());
        assertEquals(first.getCapacity(), first.getBayDamage(), 0.001, "an emptied bay is all damage");

        UnitDamageForm again = UnitDamageForm.of(union);
        again.repairAll();
        again.apply();
        assertFalse(union.isKFBoomDamaged());
        assertEquals(0, first.getBayDamage(), 0.001, "a repaired bay holds its whole capacity");
    }

    @Test
    void anEmplacementNeitherMovesNorHasAnEngine() throws Exception {
        UnitDamageForm form = UnitDamageForm.of(load("Simple Gun Emplacement (Gauss).blk"));
        List<String> general = form.general().stream().map(UnitDamageForm.Value::label).toList();
        assertFalse(general.contains("Motive Damage"));
        assertFalse(general.contains("Engine"));
    }

    @Test
    void aBuildingEditsItsPowerAndGunnersInsteadOfACrew() throws Exception {
        AbstractBuildingEntity building = (AbstractBuildingEntity) load("Simple Building Entity.blk");
        UnitDamageForm form = UnitDamageForm.of(building);
        List<String> general = form.general().stream().map(UnitDamageForm.Value::label).toList();
        assertFalse(general.contains("Shutdown"), "the power switch stands in for shutdown");
        assertTrue(form.general().stream().noneMatch(value -> value.kind() == UnitDamageForm.Kind.STEPPER
              && value.max() == UnitDamageForm.MAX_CREW_HITS), "a building's gunners are not wounded");
        assertTrue(form.places().stream().flatMap(place -> place.values().stream())
              .anyMatch(value -> value.label().equals("Gunners Killed"))
              || general.contains("Gunners Killed"), "each hex can lose its gunners");
        general(form, "Power Off").value(1);
        general(form, "Gunners Stunned").value(2);
        form.apply();
        assertTrue(building.isPowerSwitchedOff());
        assertEquals(2, building.getStunnedTurns());
    }

    private static UnitDamageForm.Value general(UnitDamageForm form, String label) {
        return form.general().stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow();
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
        assertFalse(labels(torso).contains("Heat"), "heat is how the unit starts, with its deployment");

        UnitDamageForm.Place head = form.place("HD");
        assertNull(head.rear());
        assertTrue(labels(head).contains("Pilot Hits"));
        assertTrue(labels(head).contains("Cockpit"));

        UnitDamageForm.Place arm = form.place("LA");
        assertTrue(labels(arm).contains("Blown Off"));
        assertTrue(labels(arm).contains("Shoulder"));
        List<String> deployment = form.deployment().stream().map(UnitDamageForm.Value::label).toList();
        assertTrue(deployment.containsAll(List.of("Heat", "Prone", "Deploys")), deployment.toString());
        assertFalse(deployment.contains("Hull Down"), "hull-down is an optional rule");
        assertFalse(deployment.contains("Hidden"), "hidden units are an optional rule");
        assertFalse(deployment.contains("Wanted for scanning"), "only a game master with objectives marks targets");
        assertFalse(form.changed());
    }

    @Test
    void hullDownAndHiddenFollowTheirRules() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        List<String> ruled = UnitDamageForm.of(atlas, new UnitDamageForm.Options(false, false, false, true, true))
              .deployment().stream().map(UnitDamageForm.Value::label).toList();
        assertTrue(ruled.containsAll(List.of("Hull Down", "Hidden")), ruled.toString());

        atlas.setHullDown(true);
        atlas.setHidden(true);
        UnitDamageForm stale = UnitDamageForm.of(atlas, new UnitDamageForm.Options(false, false, false));
        deployment(stale, "Hull Down").value(0);
        deployment(stale, "Hidden").value(0);
        stale.apply();
        assertFalse(atlas.isHullDown(), "a unit left hull-down with the rule off can still be stood up");
        assertFalse(atlas.isHidden(), "a unit left hidden with the rule off can still be revealed");
        List<String> cleared = UnitDamageForm.of(atlas, new UnitDamageForm.Options(false, false, false))
              .deployment().stream().map(UnitDamageForm.Value::label).toList();
        assertFalse(cleared.contains("Hull Down"), cleared.toString());
        assertFalse(cleared.contains("Hidden"), cleared.toString());
    }

    @Test
    void theDeploymentReachesTheUnitAndRepairAndRollsLeaveIt() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas, new UnitDamageForm.Options(true, true, true));
        UnitDamageForm.Value round = deployment(form, "Deploys");
        assertEquals(UnitDamageForm.Kind.CHOICE, round.kind());
        assertEquals("Pre-game", round.choices().get(0));
        assertEquals("At game start", round.choices().get(round.value()));
        assertEquals("Before round 40", round.choices().getLast());
        round.value(round.choices().indexOf("Before round 3"));
        deployment(form, "Heat").value(4);
        deployment(form, "Prone").value(1);
        deployment(form, "Wanted for scanning").value(1);
        form.repairAll();
        form.roll(PreExistingDamageLevel.HEAVY);
        assertEquals("Before round 3", round.choices().get(round.value()), "Restore Unit and rolls keep it");

        form.apply();

        assertEquals(3, atlas.getDeployRound());
        assertEquals(4, atlas.heat);
        assertTrue(atlas.isProne());
        assertTrue(atlas.isDesignatedScanTarget());
        UnitDamageForm again = UnitDamageForm.of(atlas);
        assertEquals("Before round 3", deployment(again, "Deploys").choices()
              .get(deployment(again, "Deploys").value()));

        UnitDamageForm preGame = UnitDamageForm.of(atlas, new UnitDamageForm.Options(true, true, false));
        deployment(preGame, "Deploys").value(0);
        preGame.apply();
        assertEquals(Entity.DEPLOY_ROUND_PRE_GAME, atlas.getDeployRound());
    }

    @Test
    void readOnlyDamageWritesOnlyTheDeployment() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        UnitDamageForm form = UnitDamageForm.of(atlas, new UnitDamageForm.Options(false, false, false));
        assertFalse(form.damageEditable());
        UnitDamageForm.Place torso = form.place("CT");
        torso.armor().value(0);
        UnitDamageForm.Value round = deployment(form, "Deploys");
        assertEquals("At game start", round.choices().get(0), "no pre-game deployment without walk-on rules");
        round.value(2);

        form.apply();

        assertEquals(atlas.getOArmor(Mek.LOC_CENTER_TORSO), atlas.getArmor(Mek.LOC_CENTER_TORSO),
              "a player who may not edit the damage cannot change it");
        assertEquals(2, atlas.getDeployRound());
    }

    private static UnitDamageForm.Value deployment(UnitDamageForm form, String label) {
        return form.deployment().stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow();
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
        assertEquals(1, ammo.getBaseShotsLeft(), "an unchanged bin keeps its shots");
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

    @Test
    void aHitBinHoldsNoAmmunitionAndClearingTheHitFillsItBack() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        AmmoMounted ammo = atlas.getAmmo().get(0);
        UnitDamageForm form = UnitDamageForm.of(atlas);
        List<UnitDamageForm.Value> bins = form.place(atlas.getLocationAbbr(ammo.getLocation())).values().stream()
              .filter(value -> value.hits() != null && value.label().equals(ammo.getName())).toList();
        assertFalse(bins.isEmpty());
        assertEquals(UnitDamageForm.Kind.STEPPER, bins.get(0).kind());
        assertEquals(ammo.getBaseShotsLeft(), bins.get(0).max());
        bins.forEach(bin -> bin.value(3));
        form.apply();
        assertEquals(3, ammo.getBaseShotsLeft());

        UnitDamageForm again = UnitDamageForm.of(atlas);
        UnitDamageForm.Value bin = again.place(atlas.getLocationAbbr(ammo.getLocation())).values().stream()
              .filter(value -> value.hits() != null && value.label().equals(ammo.getName())).findFirst().orElseThrow();
        assertEquals(3, bin.value());
        bin.hits().value(1);
        assertTrue(bin.locked());
        assertEquals(0, bin.value(), "a hit bin is empty");
        bin.hits().value(0);
        assertFalse(bin.locked());
        assertEquals(3, bin.value(), "clearing the hit gives back what the bin held");
        bin.hits().value(1);
        again.repairAll();
        assertEquals(bin.max(), bin.value(), "repair clears the hit and fills the bin");
    }

    @Test
    void aWeaponThatCanJamCarriesTheSwitch() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf");
        WeaponMounted autocannon = atlas.getWeaponList().stream().filter(WeaponMounted::canJam).findFirst()
              .orElseThrow();
        UnitDamageForm form = UnitDamageForm.of(atlas);
        UnitDamageForm.Value hits = value(form.place(atlas.getLocationAbbr(autocannon.getLocation())),
              autocannon.getName());
        UnitDamageForm.Value jammed = hits.flags().stream().filter(flag -> flag.label().equals("Jammed"))
              .findFirst().orElseThrow();
        assertEquals(UnitDamageForm.Kind.TOGGLE, jammed.kind());
        assertNotNull(jammed.tip());
        jammed.value(1);
        assertTrue(form.changed());
        form.apply();
        assertTrue(autocannon.isJammed());

        UnitDamageForm again = UnitDamageForm.of(atlas);
        again.repairAll();
        again.apply();
        assertFalse(autocannon.isJammed());
        assertTrue(atlas.getWeaponList().stream().filter(weapon -> !weapon.canJam()).allMatch(weapon ->
              value(form.place(atlas.getLocationAbbr(weapon.getLocation())), weapon.getName()).flags().stream()
                    .noneMatch(flag -> flag.label().equals("Jammed"))), "only weapons a rule can jam");
    }

    private static List<String> labels(UnitDamageForm.Place place) {
        return place.values().stream().map(UnitDamageForm.Value::label).toList();
    }

    private static UnitDamageForm.Value value(UnitDamageForm.Place place, String label) {
        return place.values().stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow();
    }
}
