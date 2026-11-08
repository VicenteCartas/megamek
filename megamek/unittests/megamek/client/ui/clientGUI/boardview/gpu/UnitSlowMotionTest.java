/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.attack;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.scene;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.units.Tank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UnitSlowMotionTest {
    @BeforeAll
    static void equipment() { EquipmentType.initializeTypes(); }

    @Test
    void disablingRestoresSpeedImmediatelyAndImpactHasNoRecoveryState() {
        var shot = new UnitAttack(shot(unit(1, 0), unit(2, 2), "ISAC20"));
        var death = new UnitAttack(attack(shot.event.target(), shot.event.target(), ResolvedAttack.Kind.DEATH, true));
        death.cause = shot;
        death.seconds = -Float.MIN_NORMAL;
        var attacks = List.of(shot, death);
        assertEquals(.1f, UnitSlowMotion.scale(attacks, true), 1e-6);
        assertEquals(1, UnitSlowMotion.scale(attacks, false));
        death.seconds = 0;
        assertEquals(1, UnitSlowMotion.scale(attacks, true), "Impact restores speed immediately");
        assertEquals(1, UnitSlowMotion.scale(List.of(), true));
    }

    @Test
    void everyPhysicalAttackStaysAtSpeedThroughApproachImpactAndDeath() {
        for (var kind : List.of(ResolvedAttack.Kind.PUNCH, ResolvedAttack.Kind.KICK, ResolvedAttack.Kind.CHARGE,
              ResolvedAttack.Kind.DFA, ResolvedAttack.Kind.RAM, ResolvedAttack.Kind.PUSH, ResolvedAttack.Kind.CLUB,
              ResolvedAttack.Kind.BRUSH_OFF, ResolvedAttack.Kind.SHAKE_OFF, ResolvedAttack.Kind.STOP_SWARM)) {
            var attacker = unit(1, 0);
            var target = unit(2, 1);
            assertNormalThroughCompletion(playback(attack(attacker, target, kind, true), false));
        }
    }

    @Test
    void allRangedWeaponsSlowBeforeDeathAtEveryRangePlaybackSpeedAndFrameRate() {
        for (String weapon : List.of("ISAC20", "ISMG", "ISMediumLaser", "ISPPC", "ISFlamer", "ISPlasmaRifle",
              "ISSRM6", "ISArrowIV", "InfantryAutoRifle", "InfantryPortableFlamer", "Pistol (Flamer)")) {
            for (int range : new int[] { 0, 2, 8, 25 }) {
                for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL,
                      UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
                    for (int fps : new int[] { 15, 30, 60, 144 }) {
                        var playback = playback(shot(unit(1, 0), unit(2, range), weapon), false);
                        var shot = playback.attack();
                        var death = playback.attacks().getLast();
                        assertSame(shot, death.cause);
                        float previous = 1;
                        int ticks = 0;
                        while (death.seconds < 0 && ticks++ < fps * 30) {
                            float scale = UnitSlowMotion.scale(playback.attacks(), true);
                            if (death.seconds <= -UnitSlowMotion.DEATH_LEAD_SECONDS) {
                                assertEquals(1, scale, "Only the final death window slows: " + weapon);
                            }
                            if (shot.seconds <= UnitAttack.ANTICIPATION_SECONDS) {
                                assertEquals(1, scale, "Even point-blank weapons start firing at full speed: " + weapon);
                            }
                            assertTrue(scale <= previous + 1e-6, "The approach slows continuously: " + weapon);
                            assertTrue(scale >= .1f);
                            previous = scale;
                            playback.advance(scale / fps, speed);
                        }
                        assertTrue(ticks < fps * 30, "The death must start: " + weapon);
                        assertEquals(.1f, previous, .025f, "The last pre-death frame approaches one tenth speed: " + weapon);
                        assertEquals(1, UnitSlowMotion.scale(playback.attacks(), true),
                              "The impact frame is already at full speed: " + weapon);
                        playback.advance(.1, speed);
                        assertEquals(1, UnitSlowMotion.scale(playback.attacks(), true));
                    }
                }
            }
        }
    }

    @Test
    void beamsFlamesAndProjectilesShareTheSameTimeWindow() {
        for (String weapon : List.of("ISAC20", "ISMediumLaser", "ISFlamer", "ISMG")) {
            var playback = playback(shot(unit(1, 0), unit(2, 2), weapon), false);
            var death = playback.attacks().getLast();
            playback.advance((-death.seconds - UnitSlowMotion.DEATH_LEAD_SECONDS) / UnitMotion.Speed.NORMAL.rate,
                  UnitMotion.Speed.NORMAL);
            double elapsed = 0;
            while (death.seconds < 0 && elapsed < 2) {
                playback.advance(UnitSlowMotion.scale(playback.attacks(), true) / 240d, UnitMotion.Speed.NORMAL);
                elapsed += 1 / 240d;
            }
            assertEquals(1.12, elapsed, .02, "Every weapon has the same roughly one-second approach: " + weapon);
        }
    }

    @Test
    void mixedWeaponsWaitForTheScheduledDeathAfterEarlierImpactsAndTargetChanges() {
        var attacker = unit(1, 0);
        var firstTarget = unit(2, 8);
        var victim = unit(3, 2);
        var mixed = shot(attacker, victim, "ISAC20", "ISMediumLaser", "ISFlamer", "ISMG");
        var playback = new UnitPlayback();
        playback.accept(List.of(shot(attacker, firstTarget, "ISAC20"), mixed,
              attack(victim, victim, ResolvedAttack.Kind.DEATH, true)), scene(attacker, firstTarget, victim), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        var death = playback.attacks().getLast();
        var cause = death.cause;
        assertSame(mixed, cause.event);
        assertTrue(cause.delay > 0, "This target is reached later in the volley");
        float earlierImpact = UnitAttack.ANTICIPATION_SECONDS + cause.flightSeconds;
        playback.advance((cause.delay + earlierImpact) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertTrue(death.seconds < -UnitSlowMotion.DEATH_LEAD_SECONDS, "The final MG round has not arrived yet");
        assertEquals(1, UnitSlowMotion.scale(playback.attacks(), true), "Earlier weapons cannot trigger the slowdown");
        playback.advance((-death.seconds - UnitSlowMotion.DEATH_LEAD_SECONDS / 2) / UnitMotion.Speed.NORMAL.rate,
              UnitMotion.Speed.NORMAL);
        float scale = UnitSlowMotion.scale(playback.attacks(), true);
        assertTrue(scale > .1f && scale < 1, "The scheduled death approaches even with mixed effect families");
        playback.togglePaused();
        float at = death.seconds;
        playback.advance(10, UnitMotion.Speed.NORMAL);
        assertEquals(at, death.seconds);
        assertEquals(scale, UnitSlowMotion.scale(playback.attacks(), true));
        playback.togglePaused();
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertFalse(playback.busy());
        assertEquals(1, UnitSlowMotion.scale(playback.attacks(), true));
    }

    @Test
    void pilotLossStaysAtSpeedEvenWhenLinkedToARangedHit() {
        var playback = playback(shot(unit(1, 0), unit(2, 2), "ISAC20"), true);
        assertSame(playback.attack(), playback.attacks().getLast().cause);
        assertNormalThroughCompletion(playback);
    }

    @Test
    void ordinaryHitsAndStandaloneDeathsStayAtNormalSpeed() {
        var attacker = unit(1, 0);
        var target = unit(2, 5);
        for (var event : List.of(shot(attacker, target, "ISAC20"), attack(target, target, ResolvedAttack.Kind.DEATH, true))) {
            var playback = new UnitPlayback();
            playback.accept(List.of(event), scene(attacker, target), ignored -> false);
            playback.advance(0, UnitMotion.Speed.NORMAL);
            assertNormalThroughCompletion(playback);
        }
    }

    private static void assertNormalThroughCompletion(UnitPlayback playback) {
        for (int frame = 0; frame < 3000 && playback.busy(); frame++) {
            assertEquals(1, UnitSlowMotion.scale(playback.attacks(), true));
            playback.advance(.01, UnitMotion.Speed.NORMAL);
        }
        assertFalse(playback.busy(), "The full action and death must complete");
    }

    private static UnitPlayback playback(BoardScene.Combat shot, boolean crewLoss) {
        var target = shot.target();
        var death = attack(target, target, ResolvedAttack.Kind.DEATH, true);
        var playback = new UnitPlayback();
        playback.accept(List.of(shot, new BoardScene.Combat(death.result().withCrewLoss(crewLoss), target, target, target.location())),
              scene(shot.attacker(), target), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        return playback;
    }

    private static BoardScene.Combat shot(BoardScene.Unit attacker, BoardScene.Unit target, String... weapons) {
        var base = attack(attacker, target, ResolvedAttack.Kind.SHOT, true);
        var result = base.result();
        List<ResolvedAttack.Mount> mounts = new ArrayList<>();
        for (int index = 0; index < weapons.length; index++) {
            var type = EquipmentType.get(weapons[index]);
            assertNotNull(type, weapons[index]);
            var profile = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), type));
            mounts.add(new ResolvedAttack.Mount(attacker.id(), index, profile));
        }
        return new BoardScene.Combat(new ResolvedAttack(result.id(), result.kind(), result.attacker(), result.target(),
              result.targetType(), 0, weapons[0], 4, true, mounts, mounts.getFirst().shot()), attacker, target, base.destination());
    }
}
