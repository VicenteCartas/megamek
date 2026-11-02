/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.attack;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.scene;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UnitSlowMotionTest {
    @BeforeAll
    static void equipment() { EquipmentType.initializeTypes(); }

    @Test
    void disablingRestoresSpeedImmediatelyAndImpactHasNoRecoveryState() {
        var shot = new UnitAttack(shot(unit(1, 0), unit(2, 2), "ISAC20"));
        var death = death(shot);
        death.seconds = -.01f;
        var attacks = List.of(shot, death);
        assertEquals(.1f, UnitSlowMotion.scale(attacks, ignored -> 0, 1, true), 1e-6);
        assertEquals(1, UnitSlowMotion.scale(attacks, ignored -> {
            throw new AssertionError("Disabled slow motion must not inspect the projectile");
        }, 1, false));
        death.seconds = 0;
        assertEquals(1, UnitSlowMotion.scale(attacks, ignored -> 0, 1, true), "Impact restores speed immediately");
        assertEquals(1, UnitSlowMotion.scale(List.of(), ignored -> 0, 1, true));
    }

    @Test
    void everyPhysicalAttackStaysAtSpeedThroughApproachImpactAndDeath() {
        for (var kind : List.of(ResolvedAttack.Kind.PUNCH, ResolvedAttack.Kind.KICK, ResolvedAttack.Kind.CHARGE,
              ResolvedAttack.Kind.DFA, ResolvedAttack.Kind.RAM, ResolvedAttack.Kind.PUSH, ResolvedAttack.Kind.CLUB,
              ResolvedAttack.Kind.BRUSH_OFF, ResolvedAttack.Kind.SHAKE_OFF, ResolvedAttack.Kind.STOP_SWARM)) {
            var attacker = unit(1, 0);
            var target = unit(2, 1);
            var playback = new UnitPlayback();
            playback.accept(List.of(attack(attacker, target, kind, true),
                  attack(target, target, ResolvedAttack.Kind.DEATH, true)), scene(attacker, target), ignored -> false);
            playback.advance(0, UnitMotion.Speed.NORMAL);
            for (int frame = 0; frame < 3000 && playback.busy(); frame++) {
                assertEquals(1, UnitSlowMotion.scale(playback.attacks(), ignored -> {
                    throw new AssertionError("Melee must never use projectile distance");
                }, 1, true), "Physical attacks never slow the clock, including a lethal impact: " + kind);
                playback.advance(.01, UnitMotion.Speed.NORMAL);
            }
            assertFalse(playback.busy(), "The full physical action and death must complete");
        }
    }

    @Test
    void laserAndPpcBeamsLastOneExtraRealSecondAtEachPlaybackSpeedThenExplodeAtFullSpeed() {
        for (String weapon : List.of("ISMediumLaser", "ISPPC")) {
            for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL,
                  UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
                var shot = new UnitAttack(shot(unit(1, 0), unit(2, 8), weapon));
                var death = death(shot);
                var attacks = List.of(shot, death);
                var effects = new GpuAttackEffects();
                try {
                    effects.update(List.of(shot), null, Map.of());
                    shot.seconds = Math.nextDown(shot.contactSeconds);
                    assertEquals(Float.POSITIVE_INFINITY, effects.distanceToImpact(shot), "A beam has no travelling projectile");
                    shot.seconds = UnitAttack.ANTICIPATION_SECONDS - .01f;
                    death.seconds = shot.seconds - shot.contactSeconds;
                    assertEquals(1, UnitSlowMotion.scale(attacks, effects::distanceToImpact, speed.rate, true),
                          "Charging and anticipation retain their normal duration");
                    double clock = UnitAttack.ANTICIPATION_SECONDS, elapsed = 0;
                    for (int frame = 0; frame < 10000 && clock < shot.contactSeconds - 1e-7; frame++) {
                        shot.seconds = (float) clock;
                        death.seconds = shot.seconds - shot.contactSeconds;
                        float scale = UnitSlowMotion.scale(attacks, effects::distanceToImpact, speed.rate, true);
                        double step = Math.min(1 / 120d, (shot.contactSeconds - clock) / (speed.rate * scale));
                        clock += step * speed.rate * scale;
                        elapsed += step;
                    }
                    assertEquals(shot.flightSeconds / speed.rate + 1, elapsed, .0001,
                          "The lit beam gains one real second, without changing its impact timeline");
                    death.seconds = 0;
                    assertEquals(1, UnitSlowMotion.scale(attacks, effects::distanceToImpact, speed.rate, true));
                } finally {
                    effects.dispose();
                }
            }
        }
    }

    @Test
    void burstApproachTracksTheFinalRenderedTracer() {
        var base = attack(unit(1, 0), unit(2, 8), ResolvedAttack.Kind.SHOT, true);
        var result = base.result();
        var profile = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 0, false, null, null, null,
              true, 2, false, true, false);
        var shot = new UnitAttack(new BoardScene.Combat(new ResolvedAttack(result.id(), result.kind(), result.attacker(),
              result.target(), result.targetType(), 0, "ISMG", 4, true, null, profile),
              base.attacker(), base.target(), base.destination()));
        var effects = new GpuAttackEffects();
        try {
            effects.update(List.of(shot), null, Map.of());
            shot.seconds = UnitAttack.ANTICIPATION_SECONDS + shot.flightSeconds;
            assertEquals(Float.POSITIVE_INFINITY, effects.distanceToImpact(shot),
                  "Earlier MG bullets have arrived, but the final tracer has not launched");
            float launch = UnitAttack.ANTICIPATION_SECONDS + shot.roundDelay(UnitAttack.roundCount(profile) - 1, profile);
            shot.seconds = launch;
            float distance = effects.distanceToImpact(shot);
            assertTrue(Float.isFinite(distance) && distance > 4 * BoardGeometry.height());
            shot.seconds = (launch + shot.contactSeconds) / 2;
            assertEquals(distance / 2, effects.distanceToImpact(shot), .001f,
                  "Slow motion measures the same captured tracer position as rendering");
            var origin = UnitAttack.center(null, base.attacker().location(), new Vector3());
            assertEquals(origin.dst(shot.endpoint(null, origin, new Vector3())), distance, .001f);
            shot.seconds = shot.contactSeconds;
            assertEquals(0, effects.distanceToImpact(shot));
        } finally { effects.dispose(); }
    }

    @Test
    void lethalProjectileSlowsAcrossFourHexesAndImpactRestoresFullSpeedAtEveryPlaybackSpeed() {
        for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
            for (int fps : new int[] { 15, 30, 60, 144 }) {
                var attacker = unit(1, 0);
                var target = unit(2, 8);
                var playback = new UnitPlayback();
                playback.accept(List.of(shot(attacker, target, "ISAC20"),
                      attack(target, target, ResolvedAttack.Kind.DEATH, true)), scene(attacker, target), ignored -> false);
                playback.advance(0, speed);
                var shot = playback.attack();
                var death = playback.attacks().getLast();
                assertSame(shot, death.cause);
                float step = 1f / fps, previous = 1;
                int ticks = 0;
                while (death.seconds < 0 && ticks++ < fps * 30) {
                    float distance = 8 * BoardGeometry.height() * (1 - shot.flight());
                    float scale = UnitSlowMotion.scale(playback.attacks(), cause -> {
                        assertSame(shot, cause);
                        return distance;
                    }, speed.rate, true);
                    if (distance >= 4 * BoardGeometry.height()) { assertEquals(1, scale); }
                    assertTrue(scale <= previous + 1e-6, "The incoming round slows continuously");
                    assertTrue(scale >= .1f);
                    previous = scale;
                    playback.advance(step * scale, speed);
                }
                assertTrue(ticks < fps * 30, "The projectile reaches the target");
                assertEquals(.1f, previous, .003f, "The last approach frame is at one tenth speed");
                assertEquals(1, UnitSlowMotion.scale(playback.attacks(), ignored -> 0, speed.rate, true),
                      "The impact frame and explosion are already at full speed");
            }
        }
    }

    @Test
    void ordinaryHitsAndDeathsWithoutRangedCausesStayAtNormalSpeed() {
        var shot = new UnitAttack(shot(unit(1, 0), unit(2, 5), "ISAC20"));
        var death = new UnitAttack(attack(unit(2, 5), unit(2, 5), ResolvedAttack.Kind.DEATH, true));
        for (float seconds = 0; seconds < shot.duration; seconds += .01f) {
            shot.seconds = seconds;
            assertEquals(1, UnitSlowMotion.scale(List.of(shot, death), ignored -> {
                throw new AssertionError("An ordinary hit or unlinked death has no killing projectile");
            }, 1, true));
        }
    }

    private static UnitAttack death(UnitAttack shot) {
        var death = new UnitAttack(attack(shot.event.target(), shot.event.target(), ResolvedAttack.Kind.DEATH, true));
        death.cause = shot;
        return death;
    }

    private static BoardScene.Combat shot(BoardScene.Unit attacker, BoardScene.Unit target, String weapon) {
        var base = attack(attacker, target, ResolvedAttack.Kind.SHOT, true);
        var result = base.result();
        return new BoardScene.Combat(new ResolvedAttack(result.id(), result.kind(), result.attacker(), result.target(),
              result.targetType(), 0, weapon, 4, true), attacker, target, base.destination());
    }
}
