/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;

class UnitAttackTest {
    @Test
    void infantryCasualtiesDistinguishRifleVolleysCannonRoundsAndHeat() {
        var rifle = hit("InfantryAutoRifle", 28);
        assertEquals(.52, rifle.infantryHitDamage(), .0001);
        assertEquals(0, MeepleCasualties.infantryCuts(rifle), "A platoon's combined rifle damage is not one heavy projectile");
        assertFalse(rifle.burnsInfantry());
        assertEquals(2, MeepleCasualties.infantryCuts(hit("ISAC2", 1)));
        assertEquals(3, MeepleCasualties.infantryCuts(hit("ISAC5", 1)));
        assertEquals(4, MeepleCasualties.infantryCuts(hit("ISAC20", 2)), "Infantry damage reduction does not turn a shell into a rifle");
        for (String weapon : List.of("ISMediumLaser", "InfantryLaserRifle", "InfantryPortableFlamer", "ISPPC", "ISPlasmaRifle")) {
            var heat = hit(weapon, 12);
            assertTrue(heat.burnsInfantry(), weapon);
            assertEquals(0, MeepleCasualties.infantryCuts(heat), "Heat carbonizes instead of shattering: " + weapon);
        }
    }

    private static UnitAttack hit(String name, int dealt) {
        var base = UnitPlaybackTest.attack(UnitPlaybackTest.unit(1, 1), UnitPlaybackTest.unit(2, 2), ResolvedAttack.Kind.SHOT, true);
        var raw = base.result();
        var weapon = Mounted.createMounted(new Tank(), EquipmentType.get(name));
        var result = new ResolvedAttack(raw.id(), raw.kind(), raw.attacker(), raw.target(), raw.targetType(),
              0, name, raw.limb(), true, null, ResolvedAttack.Shot.capture(weapon))
              .withImpacts(List.of(new ResolvedAttack.Impact("*", false, dealt)));
        return new UnitAttack(new BoardScene.Combat(result, base.attacker(), base.target(), base.destination()));
    }

    @Test
    void machineGunRapidFireTriplesCosmeticRoundsInTwiceTheFiringTime() {
        for (String name : List.of("ISMG", "ISLightMG", "ISHeavyMG", "CLMG", "CLBAMG")) {
            var gun = Mounted.createMounted(new Tank(), EquipmentType.get(name));
            var normal = ResolvedAttack.Shot.capture(gun);
            gun.setRapidFire(true);
            var rapid = ResolvedAttack.Shot.capture(gun);
            var normalAttack = attack(normal);
            var rapidAttack = attack(rapid);
            assertEquals(1, normal.shots());
            assertEquals(normal.shots(), rapid.shots(), "Cosmetic rounds cannot replace the rules shot count");
            assertEquals(6, UnitAttack.roundCount(normal));
            assertEquals(18, UnitAttack.roundCount(rapid));
            assertEquals(2 * normalAttack.roundDelay(5, normal), rapidAttack.roundDelay(17, rapid), .0001f);
            assertTrue(rapidAttack.duration > normalAttack.duration);
            for (var profile : List.of(normal, rapid)) {
                var attack = attack(profile);
                float previousContact = 0;
                for (int round = 0; round < UnitAttack.roundCount(profile); round++) {
                    float delay = attack.roundDelay(round, profile);
                    float contact = attack.roundContactSeconds(profile, delay);
                    assertTrue(contact > previousContact, "Rounds must arrive successively, not all at once");
                    assertEquals(attack.flightSeconds, contact - UnitAttack.ANTICIPATION_SECONDS - delay, .0001f);
                    attack.seconds = UnitAttack.ANTICIPATION_SECONDS + delay + UnitAttack.RECOIL_KICK_SECONDS;
                    assertTrue(attack.recoil() > 0, "Every visible round needs recoil");
                    assertTrue(attack.aimWeight() > .99f, "Aiming cannot recover while still firing");
                    previousContact = contact;
                }
                assertEquals(previousContact, attack.contactSeconds, .0001f);
                attack.seconds = attack.duration;
                assertEquals(0, attack.recoil());
            }
        }
    }

    @Test
    void mixedMountsKeepTheirOwnTimingAndOtherCannonsKeepTheirRulesShotCount() {
        var gun = Mounted.createMounted(new Tank(), EquipmentType.get("ISMG"));
        gun.setRapidFire(true);
        var mg = ResolvedAttack.Shot.capture(gun);
        var cannon = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), EquipmentType.get("ISAC20")));
        var rotary = Mounted.createMounted(new Tank(), EquipmentType.get("ISRotaryAC5"));
        rotary.setMode("6-shot");
        rotary.newRound(1);
        var rac = ResolvedAttack.Shot.capture(rotary);
        assertEquals(1, UnitAttack.roundCount(cannon));
        assertEquals(6, UnitAttack.roundCount(rac));
        var mixed = attack(cannon, mg, rac);
        var single = attack(cannon);
        assertEquals(single.contactSeconds, mixed.roundContactSeconds(cannon, 0));
        assertEquals(single.contactSeconds, mixed.roundContactSeconds(rac, mixed.roundDelay(5, rac)));
        assertEquals(attack(mg).contactSeconds, mixed.contactSeconds);
        mixed.seconds = single.contactSeconds + UnitAttack.RECOVERY_SECONDS;
        assertTrue(mixed.aimWeight() > .99f, "The group must finish the longer MG burst before recovering");
        var launch = GpuMissileEffects.capture(mixed, new com.badlogic.gdx.math.Vector3[] { new com.badlogic.gdx.math.Vector3() },
              null, 1, 1, false, 42, cannon);
        assertEquals(1, GpuMissileEffects.progress(launch, 0, single.contactSeconds), .0001f,
              "A mixed bay's missiles cannot be slowed down by its MG burst");
    }

    private static UnitAttack attack(ResolvedAttack.Shot... profiles) {
        var combat = UnitPlaybackTest.attack(UnitPlaybackTest.unit(1, 1), UnitPlaybackTest.unit(2, 2), ResolvedAttack.Kind.SHOT, true);
        var raw = combat.result();
        var mounts = java.util.stream.IntStream.range(0, profiles.length)
              .mapToObj(index -> new ResolvedAttack.Mount(1, index, profiles[index])).toList();
        var result = new ResolvedAttack(raw.id(), raw.kind(), raw.attacker(), raw.target(), raw.targetType(),
              0, "", raw.limb(), raw.hit(), mounts, profiles[0]);
        return new UnitAttack(new BoardScene.Combat(result, combat.attacker(), combat.target(), combat.destination()));
    }
}
