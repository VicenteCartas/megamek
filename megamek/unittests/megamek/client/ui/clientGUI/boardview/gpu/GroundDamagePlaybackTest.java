/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.units.Entity;
import megamek.common.units.Tank;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GroundDamagePlaybackTest {
    @ParameterizedTest
    @CsvSource({ "ISLargeLaser, LASER_BLAST, 3.394", "ISPPC, PPC_BLAST, 3.795", "ISAC20, BALLISTIC_BLAST, 5.367" })
    void perpendicularImpactsUseRoundVariantsAtTheInclusive45DegreeBoundary(String weapon, GpuGroundDamage.Style round,
          float blastDiameter) {
        var mark = impact(weapon, null);
        var point = new Vector3();
        var flat = new BoardSurface.Face(point, new Vector3(1, 0, 0), new Vector3(0, 1, 0), BoardSurface.Finish.TOP);
        var reversed = new BoardSurface.Face(flat.a(), flat.c(), flat.b(), flat.finish());
        for (float x : new float[] { 0, .999f, 1, 1.001f, 10 }) {
            var angled = new GpuGroundDamage.Impact(mark.attack(), mark.key(), new Vector3(x, 0, 1), point,
                  mark.effect(), mark.shot());
            var expected = x <= 1 ? round : mark.style();
            assertEquals(expected, angled.style(flat));
            assertEquals(expected, angled.style(reversed), "Triangle winding must not alter the angle test");
            float expectedRadius = x <= 1 ? BoardRelief.metres(blastDiameter / 2) : mark.radius();
            assertEquals(expectedRadius, angled.radius(angled.style(flat)), BoardRelief.metres(.005f),
                  "Perpendicular diameter follows gouge width; shallow shots keep their full gouge length");
            assertEquals(mark.thermal(), angled.style(flat).thermal(), "Round variants retain the weapon's material");
        }
        assertEquals(mark.style(), mark.style(flat), "A missing direction must not invent a perpendicular hit");
    }

    @Test
    void impactAngleUsesTheReceivingSlopeRatherThanWorldVertical() {
        var mark = impact("ISLargeLaser", null);
        var point = new Vector3();
        var bank = new BoardSurface.Face(point, new Vector3(0, 1, 0), new Vector3(-1, 0, 2), BoardSurface.Finish.TOP);
        var angled = new GpuGroundDamage.Impact(mark.attack(), mark.key(), new Vector3(2, 0, 1), point,
              mark.effect(), mark.shot());
        assertEquals(GpuGroundDamage.Style.LASER_BLAST, angled.style(bank), "A shot normal to a steep bank is round");
        var flat = new BoardSurface.Face(point, new Vector3(1, 0, 0), new Vector3(0, 1, 0), BoardSurface.Finish.TOP);
        assertEquals(GpuGroundDamage.Style.LASER, angled.style(flat), "The same direction grazes level ground");
        for (String weapon : List.of("ISLRM5", "ISLongTom", "Flamer", "ISPlasmaRifle")) {
            var other = impact(weapon, null);
            var vertical = new GpuGroundDamage.Impact(other.attack(), other.key(), new Vector3(0, 0, 1), point,
                  other.effect(), other.shot());
            assertEquals(other.style(), vertical.style(flat), "Already-round styles keep their own identity");
            assertEquals(other.radius(), vertical.radius(vertical.style(flat)), "Already-round styles keep their full size");
        }
    }

    @Test
    void flamerOpacityIsFixedWhileDamageChangesItsFootprint() {
        var flame = impact("Flamer", null);
        var heavy = impact("Heavy Flamer", null);
        assertEquals(flame.strength(), heavy.strength());
        assertTrue(heavy.radius() > flame.radius());
        assertTrue(flame.strength() < impact("ISLRM5", "IS Ammo LRM-5").strength());
    }

    @Test
    void zeroDamageUsesTheMinimumScarSizeWithoutChangingCapturedDamage() {
        for (String effect : List.of("laser", "ppc", "bullet", "missile", "flame")) {
            float minimum = 0;
            for (double damage : new double[] { 1, 0, .5 }) {
                var profile = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 0, false, null, null, null,
                      false, 0, false, false, false, null, damage);
                var impact = new GpuGroundDamage.Impact(null, "minimum", new Vector3(), new Vector3(), effect, profile);
                if (damage == 1) { minimum = impact.radius(); }
                assertEquals(minimum, impact.radius());
                assertEquals(damage, profile.damagePerHit());
                assertTrue(impact.radius() > 0);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
          "ISPPC, PPC", "ISLightPPC, PPC", "CLERPPC, PPC",
          "ISLightPlasmaRifle, PLASMA", "ISPlasmaRifle, PLASMA", "ISHeavyPlasmaRifle, PLASMA",
          "CLPlasmaCannon, PLASMA", "MFUK Plasma Rifle, PLASMA"
    })
    void weaponFlagsChooseTheScarEvenWithAGenericModelEmitter(String weapon, GpuGroundDamage.Style style) {
        var actual = impact(weapon, null);
        assertEquals(style, actual.style());
        for (String emitter : List.of("energy", "bullet", "laser")) {
            var generic = new GpuGroundDamage.Impact(actual.attack(), actual.key(), actual.origin(), actual.point(),
                  emitter, actual.shot());
            assertEquals(style, generic.style());
            assertEquals(actual.radius(), generic.radius(), "An authored emitter must not change damage scaling");
            assertTrue(generic.thermal());
            assertFalse(generic.burn(), "Plasma has a melted core, distinct from a flamer's matte burn");
        }
    }

    @Test
    void plasmaMissesRetainTheirWeaponIdentityAndDamageThroughEffectPlayback() {
        var light = impact("ISLightPlasmaRifle", null);
        var rifle = impact("ISPlasmaRifle", null);
        var heavy = impact("ISHeavyPlasmaRifle", null);
        var cannon = impact("CLPlasmaCannon", "CLPlasmaCannonAmmo");
        assertTrue(heavy.radius() > rifle.radius());
        assertTrue(rifle.radius() > light.radius());
        assertTrue(light.radius() > cannon.radius());
        assertEquals(0, cannon.shot().damagePerHit());
        assertEquals(BoardRelief.metres(1.35f), cannon.radius(),
              "Zero nominal plasma damage keeps the common one-damage, 2.7-metre splash minimum");
        assertEquals(impact("ISThunderbolt10", "IS Ammo Thunderbolt-10").radius(), rifle.radius(),
              "Plasma and explosive marks with equal damage use the same diameter");
        var effects = new GpuAttackEffects();
        for (var expected : List.of(light, rifle, heavy, cannon)) {
            var attack = expected.attack();
            var marks = new ArrayList<GpuGroundDamage.Impact>();
            attack.seconds = attack.contactSeconds;
            effects.update(List.of(attack), null, Map.of());
            effects.groundImpacts(List.of(), marks::add);
            assertEquals(1, marks.size());
            assertEquals(GpuGroundDamage.Style.PLASMA, marks.getFirst().style());
            assertSame(expected.shot(), marks.getFirst().shot());
            assertFalse(marks.getFirst().cut());
            assertEquals(effects.emissions(attack).getFirst().target(), marks.getFirst().point());
        }
    }

    @Test
    void scarSizeFollowsOneProjectileRatherThanTheSalvo() {
        var smallLaser = impact("ISSmallLaser", null);
        var largeLaser = impact("ISLargeLaser", null);
        assertTrue(smallLaser.cut());
        assertTrue(largeLaser.radius() > smallLaser.radius());
        var ppc = impact("ISPPC", null);
        assertTrue(ppc.cut());
        assertTrue(ppc.radius() > largeLaser.radius());
        assertEquals(impact("ISMediumLaser", null).radius(), impact("ISLightPPC", null).radius(),
              "Lasers and PPCs with equal damage leave equal-sized gouges");
        var ac20 = impact("ISAC20", null);
        assertTrue(ac20.radius() > impact("ISAC2", null).radius() * 3,
              "Ten times the round damage must make a substantially larger gouge");
        assertTrue(ac20.radius() > largeLaser.radius() * 1.5f,
              "An AC/20 must be visibly longer than an eight-damage Large Laser");
        assertEquals(impact("ISMediumLaser", null).radius(), impact("ISAC5", null).radius(),
              "Thermal and ballistic gouges use the same damage-to-length scale");

        var lrm5 = impact("ISLRM5", "IS Ammo LRM-5");
        var lrm15 = impact("ISLRM15", "IS Ammo LRM-15");
        var srm2 = impact("ISSRM2", "IS Ammo SRM-2");
        var srm6 = impact("ISSRM6", "IS Ammo SRM-6");
        assertEquals(lrm5.radius(), lrm15.radius());
        assertEquals(srm2.radius(), srm6.radius());
        assertTrue(srm2.radius() > lrm5.radius());
        assertFalse(srm2.cut());

        var thunderbolt5 = impact("ISThunderbolt5", "IS Ammo Thunderbolt-5");
        var thunderbolt20 = impact("ISThunderbolt20", "IS Ammo Thunderbolt-20");
        assertEquals(1, thunderbolt5.shot().missiles());
        assertEquals(1, thunderbolt20.shot().missiles());
        assertTrue(thunderbolt20.radius() > thunderbolt5.radius());
        assertTrue(thunderbolt5.radius() > srm6.radius());
        var thumper = impact("ISThumper", null);
        var sniper = impact("ISSniper", null);
        var longTom = impact("ISLongTom", null);
        assertFalse(thumper.cut());
        assertFalse(longTom.cut());
        assertTrue(longTom.radius() > sniper.radius());
        assertTrue(sniper.radius() > thumper.radius());
        assertEquals(thunderbolt20.radius(), sniper.radius(), "Equal warhead damage gives equal blast radii");
        assertEquals(largeLaser.radius() / smallLaser.radius(), (float) Math.sqrt(8f / 3), .001f,
              "Changing damage scales the footprint area proportionally, without weapon-specific size adjustments");
    }

    @ParameterizedTest
    @CsvSource({ "ISLRM5, IS Ammo LRM-5, 1, 2.7", "ISLongTom, , 25, 13.5264" })
    void explosiveBlastDiametersMatchTheLrmAndLongTomTargets(String weapon, String ammo, double damage, float diameter) {
        var mark = impact(weapon, ammo);
        assertEquals(GpuGroundDamage.Style.BLAST, mark.style());
        assertEquals(damage, mark.shot().damagePerHit());
        assertEquals(BoardRelief.metres(diameter / 2), mark.radius(), BoardRelief.metres(.001f),
              "LRM diameter is 10% smaller; a Long Tom's is a Sniper's grown in step with damage (25 to 20)");
    }

    @Test
    void missedFlameLeavesABurnAtContactButUnitHitsDoNotScarTheGround() {
        var flame = impact("Flamer", null);
        assertTrue(flame.burn());
        assertFalse(flame.cut());
        var attack = flame.attack();
        var effects = new GpuAttackEffects();
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        attack.seconds = attack.contactSeconds - .01f;
        effects.update(List.of(attack), null, Map.of());
        effects.groundImpacts(List.of(), marks::add);
        assertTrue(marks.isEmpty());
        attack.seconds = attack.contactSeconds;
        effects.groundImpacts(List.of(), marks::add);
        assertEquals(1, marks.size());
        assertTrue(marks.getFirst().burn());
        assertEquals(effects.emissions(attack).getFirst().target(), marks.getFirst().point());
        marks.clear();
        var hit = shot("Flamer", true, flame.shot());
        hit.seconds = hit.contactSeconds;
        effects.update(List.of(hit), null, Map.of());
        effects.groundImpacts(List.of(), marks::add);
        assertTrue(marks.isEmpty());
    }

    static GpuGroundDamage.Impact impact(String weapon, String ammo) {
        var owner = new Tank();
        var gun = Mounted.createMounted(owner, EquipmentType.get(weapon));
        if (ammo != null) { gun.setLinked(Mounted.createMounted(owner, EquipmentType.get(ammo))); }
        var profile = ResolvedAttack.Shot.capture(gun);
        var attack = shot(new UUID(0, 42), weapon, false, profile);
        return new GpuGroundDamage.Impact(attack, "sample", new Vector3(), new Vector3(), attack.effect(1, 0), profile);
    }

    @Test
    void scarsWaitForContactAndKeepTheVisibleProjectileEndpointAfterCompletion() {
        var shot = shot("ISAC5", false, new ResolvedAttack.Shot("", Set.of(), false, false, 1, 0, false,
              null, null, null, true, 5));
        var effects = new GpuAttackEffects();
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        shot.seconds = shot.contactSeconds - .01f;
        effects.update(List.of(shot), null, Map.of());
        effects.groundImpacts(List.of(), marks::add);
        assertTrue(marks.isEmpty());
        shot.seconds = shot.contactSeconds;
        effects.groundImpacts(List.of(), marks::add);
        var point = effects.emissions(shot).getFirst().target().cpy();
        assertEquals(1, marks.size());
        assertEquals(point, marks.getFirst().point());
        marks.clear();
        shot.seconds = shot.duration;
        effects.update(List.of(), List.of(shot), null, Map.of());
        effects.groundImpacts(List.of(shot), marks::add);
        assertTrue(marks.isEmpty(), "Recovery/completion must not repeatedly collect a finished impact");
        assertEquals(point, effects.emissions(shot).getFirst().target());
    }

    @Test
    void unitHitsAndBeamsEscapingTheBoardLeaveNoGroundScars() {
        var effects = new GpuAttackEffects();
        var hit = shot("ISAC5", true, null);
        var sky = shot("ISMediumLaser", false, null);
        sky.landscape = ray -> null;
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        for (var shot : List.of(hit, sky)) {
            shot.seconds = shot.contactSeconds;
            effects.update(List.of(shot), null, Map.of());
            effects.groundImpacts(List.of(), marks::add);
        }
        assertTrue(marks.isEmpty());
    }

    @Test
    void partiallyHittingSalvosScarOnlyTheUninterceptedMisses() {
        var profile = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 12, false, 4)
              .withInterception(UUID.randomUUID(), 4);
        var shot = shot("ISLRM20", true, profile);
        shot.seconds = shot.contactSeconds;
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), marks::add);
        assertEquals(4, marks.size(), "Four hit units and four are destroyed in the air; four reach the ground");
        var launch = effects.missileLaunches(shot).getFirst();
        for (var mark : marks) {
            assertTrue(java.util.stream.IntStream.range(0, launch.missiles()).anyMatch(i ->
                  !launch.hit(i) && !launch.intercepted(i) && launch.targets()[i].equals(mark.point())));
        }
    }

    static UnitAttack shot(String weapon, boolean hit, ResolvedAttack.Shot profile) {
        return shot(UUID.randomUUID(), weapon, hit, profile);
    }

    /**
     * The server places a round that drifted by hex and whole level; the terrain there is sculpted higher. Its ground
     * mark sits on the terrain, where it can be painted, not at the flat level, where it would be dropped.
     */
    @Test
    void aDriftedArtilleryRoundMarksTheTerrainItCameDownOn() {
        Coords drifted = new Coords(3, 7);
        var landing = new UnitLocation(Entity.NONE, drifted, 0, 0, 0);
        var profile = new ResolvedAttack.Shot("", Set.of(), true, false, 1, 0, true, null, null, landing, true, 20,
              false, false, false, null, 20);
        UnitAttack round = shot("ISLongTom", false, profile);
        float groundHeight = 7;
        round.landscape = ray -> {
            if (ray.direction.z >= 0) {
                return null;
            }
            float distance = (groundHeight - ray.origin.z) / ray.direction.z;
            return distance < 0 ? null : new BoardGeometry.Hit(drifted, distance * distance);
        };
        round.seconds = round.duration;
        var effects = new GpuAttackEffects();
        effects.update(List.of(round), null, Map.of());
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), marks::add);

        assertEquals(1, marks.size());
        Vector3 point = marks.getFirst().point();
        Vector3 hexCentre = BoardGeometry.center(drifted, 0);
        assertEquals(hexCentre.x, point.x, .01f, "Over the hex it drifted to");
        assertEquals(hexCentre.y, point.y, .01f);
        assertEquals(groundHeight, point.z, .01f, "On the terrain, not the flat level");
    }

    /**
     * A round landing in woods is placed on the canopy, where its point matches no ground face. Its crater goes on the
     * ground directly beneath: the face under the point, at that face's own height, and none for a point beside them.
     */
    @Test
    void aCraterInWoodsGoesOnTheGroundBeneathTheCanopy() {
        var low = new BoardSurface.Face(new Vector3(0, 0, 2), new Vector3(10, 0, 2), new Vector3(0, 10, 2),
              BoardSurface.Finish.TOP);
        var high = new BoardSurface.Face(new Vector3(10, 0, 4), new Vector3(10, 10, 4), new Vector3(0, 10, 4),
              BoardSurface.Finish.TOP);
        List<BoardSurface.Face> ground = List.of(low, high);

        assertSame(low, GpuGroundDamage.groundBeneath(ground, 2, 2), "The face under the point, not its neighbour");
        assertEquals(2, GpuGroundDamage.groundBeneath(ground, 2, 2).height(2, 2), .001f);
        assertSame(high, GpuGroundDamage.groundBeneath(ground, 8, 8));
        assertNull(GpuGroundDamage.groundBeneath(ground, 20, 20), "No ground beneath a point beside every face");
    }

    /** An Arrow IV round flies in slower than a shell from the same range, so its missile is seen coming. */
    @Test
    void anArrowIVRoundArrivesSlowerThanAShell() {
        EquipmentType.initializeTypes();
        var profile = new ResolvedAttack.Shot("", Set.of(), true, false, 1, 0, true, null, null, null, true, 20,
              false, false, false, null, 20);
        UnitAttack arrowIV = shot("ISArrowIV", true, profile);
        UnitAttack shell = shot("ISLongTom", true, profile);

        assertTrue(UnitAttack.firesArrowIV(arrowIV.event.result()));
        assertFalse(UnitAttack.firesArrowIV(shell.event.result()));
        assertTrue(shell.flightSeconds <= .7f, "A shell: " + shell.flightSeconds);
        assertTrue(arrowIV.flightSeconds >= UnitAttack.ARROW_IV_MIN_FLIGHT_SECONDS, "An Arrow IV: " + arrowIV.flightSeconds);
        assertTrue(arrowIV.contactSeconds > shell.contactSeconds, "It lands later");
    }

    @Test
    void aSingleMissInALargeRackIsNeverLostToImpactGrouping() {
        var shot = shot(new UUID(0, 1), "ISLRM20", true, new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 19));
        shot.seconds = shot.contactSeconds;
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), marks::add);
        assertEquals(1, marks.size());
    }

    @Test
    void aFullyMissedLrm20GroupsTwentyMissilesIntoTenGroundMarks() {
        var shot = shot(new UUID(0, 2), "ISLRM20", false, new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 0));
        shot.seconds = shot.contactSeconds;
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), marks::add);
        assertEquals(20, effects.missileLaunches(shot).getFirst().missiles());
        assertEquals(10, marks.size());
    }

    private static UnitAttack shot(UUID id, String weapon, boolean hit, ResolvedAttack.Shot profile) {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var original = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, hit).result();
        var event = new ResolvedAttack(id, original.kind(), original.attacker(), original.target(), original.targetType(),
              0, weapon, 4, hit, original.mounts(), profile);
        var shot = new UnitAttack(new BoardScene.Combat(event, source, target, target.location()));
        shot.landscape = ray -> {
            if (ray.direction.z >= 0) { return null; }
            float distance = -ray.origin.z / ray.direction.z;
            return distance < 0 ? null : new BoardGeometry.Hit(new Coords(0, 4), distance * distance);
        };
        return shot;
    }

    @Test
    void aFrameCrossingTheEntireAnimationRetainsItsImpactOnce() {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var event = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, false);
        var playback = new UnitPlayback();
        playback.accept(List.of(event), UnitPlaybackFixture.scene(source, target), ignored -> false);
        playback.advance(60, UnitMotion.Speed.NORMAL);
        assertTrue(playback.attacks().isEmpty());
        var completed = playback.takeCompletedAttacks();
        assertEquals(1, completed.size());
        assertSame(event, completed.getFirst().event);
        assertEquals(completed.getFirst().duration, completed.getFirst().seconds);
        assertTrue(playback.takeCompletedAttacks().isEmpty());
    }

    @Test
    void instantPlaybackIncludesQueuedShotsAndClearsThemOnBoardChange() {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var first = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, false);
        var second = UnitPlaybackFixture.attack(target, source, ResolvedAttack.Kind.SHOT, false);
        var punch = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.PUNCH, false);
        var playback = new UnitPlayback();
        playback.accept(List.of(first, second, punch), UnitPlaybackFixture.scene(source, target), ignored -> false);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertEquals(List.of(first.result().id(), second.result().id()),
              playback.takeCompletedAttacks().stream().map(shot -> shot.event.result().id()).toList());
        playback.accept(List.of(first), UnitPlaybackFixture.scene(source, target), ignored -> false);
        playback.finish();
        playback.clear();
        assertTrue(playback.takeCompletedAttacks().isEmpty());
    }

    @Test
    void finishingDuringRecoveryKeepsTheOriginalCapturedEndpoints() {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var event = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, false);
        var playback = new UnitPlayback();
        playback.accept(List.of(event), UnitPlaybackFixture.scene(source, target), ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        var presented = playback.attack();
        playback.finish();
        assertSame(presented, playback.takeCompletedAttacks().getFirst(), "Do not reroll a partly presented shot");
    }
}
