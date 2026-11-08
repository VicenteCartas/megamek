/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.ProneCause;
import org.junit.jupiter.api.Test;

class UnitDefensePlaybackTest {
    @Test
    void missilesStopAtTheNearerInterceptionPointAndKeepHitsSeparateFromMisses() {
        for (int distance : new int[] { 1, 2, 4, 10 }) {
            var source = UnitPlaybackFixture.unit(1, 0);
            var target = UnitPlaybackFixture.unit(2, distance);
            var shot = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, true, 8)
                  .withInterception(UUID.randomUUID(), 6);
            var attack = new UnitAttack(combat(source, target, shot));
            var origin = BoardGeometry.center(source.location().coords(), 0).add(0, 0, 20);
            var launch = GpuMissileEffects.capture(attack, new Vector3[] { origin }, null, 20, 8, true, 123);
            int hits = 0, intercepted = 0, misses = 0;
            for (int index = 0; index < 20; index++) {
                if (launch.hit(index)) { hits++; }
                else if (launch.intercepted(index)) {
                    intercepted++;
                    float end = launch.endProgress(index);
                    assertEquals(Math.min(distance / 2f, 2), distance * (1 - end), .001);
                    assertEquals(end, GpuMissileEffects.progress(launch, index, launch.endSeconds(index)), .0001);
                    var point = GpuMissileEffects.position(launch, index, end, new Vector3());
                    assertTrue(point.z > 0, "Interception must retain the missile's airborne arc");
                    assertTrue(launch.endSeconds(index) < attack.contactSeconds);
                } else { misses++; }
            }
            assertEquals(8, hits);
            assertEquals(6, intercepted);
            assertEquals(6, misses);
        }
    }

    @Test
    void neighboringDefensePairsWithItsActualSalvoEvenWhenAnotherRackFiresAtTheSameTarget() {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 8);
        var neighbor = UnitPlaybackFixture.unit(3, 7);
        var id = UUID.randomUUID();
        var incoming = combat(source, target, new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 8).withInterception(id, 6));
        var unrelated = combat(source, target, new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 8)
              .withInterception(UUID.randomUUID(), 4));
        var counter = combat(neighbor, source, new ResolvedAttack.Shot("", Set.of(), false, true, 1, 0, false).withInterception(id, 0));
        var playback = new UnitPlayback();
        playback.accept(List.of(counter, unrelated, incoming), UnitPlaybackFixture.scene(source, target, neighbor), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        var defense = playback.attacks().getFirst();
        assertSame(incoming, defense.incoming.event);
        assertEquals(defense.incoming.delay, defense.delay);
    }

    @Test
    void pushDisplacementStartsAtContactAndPausesWithTheAttack() {
        var source = unit(1, 0, 0, ProneCause.NONE);
        var target = unit(2, 1, 0, ProneCause.NONE);
        var displaced = unit(2, 2, 0, ProneCause.NONE);
        var push = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.PUSH, true);
        var move = new BoardScene.Movement(2, 0, List.of(target.location(), displaced.location()), EntityMovementType.MOVE_NONE, 0, 0, displaced);
        var before = UnitPlaybackFixture.scene(source, target);
        var after = UnitPlaybackFixture.scene(source, displaced);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(before), push, move, new BoardScene.SceneUpdate(after)), after, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        float contact = playback.attack().contactSeconds;
        playback.advance((contact - .01) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertFalse(playback.motions.containsKey(2));
        playback.advance(.1 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        var motion = playback.motions.get(2);
        assertNotNull(motion);
        assertTrue(motion.isMoving());
        var position = motion.position().cpy();
        assertTrue(position.dst(BoardGeometry.center(target.location().coords(), 0)) > 0);
        assertTrue(position.dst(motion.destination()) > 0);
        assertEquals(EntityMovementType.MOVE_NONE, motion.sample().type());
        playback.togglePaused();
        playback.advance(10, UnitMotion.Speed.NORMAL);
        assertEquals(position, motion.position());
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertEquals(BoardGeometry.center(displaced.location().coords(), 0), motion.position());
        assertFalse(playback.busy());
    }

    @Test
    void waterFallHasAnIntermediatePoseAndSplashThatSurvivesArrivalButNotInstantPlayback() {
        var start = unit(1, 0, 0, ProneCause.NONE);
        var end = unit(1, 1, -1, ProneCause.FORCED);
        var water = scene(start, end);
        var motion = new UnitMotion(start.location());
        motion.append(List.of(start.location(), end.location()), EntityMovementType.MOVE_NONE, 0);
        boolean falling = false, splash = false;
        for (int frame = 0; frame < 120; frame++) {
            motion.advance(.01, 1);
            var posture = motion.sample().posture();
            falling |= posture != null && posture.fallen() > 0 && posture.fallen() < 1;
            var impact = motion.waterImpact(water);
            if (impact != null) {
                splash = true;
                assertEquals(BoardGeometry.waterZ(water.tile(end.location().coords())) + .25, impact.position().z, .001);
                assertTrue(BoardGeometry.contains(end.location().coords(), impact.position().x, impact.position().y));
            }
        }
        assertTrue(falling);
        assertTrue(splash);
        motion.finish();
        assertNull(motion.waterImpact(water));
    }

    static BoardScene.Combat combat(BoardScene.Unit source, BoardScene.Unit target, ResolvedAttack.Shot shot) {
        var raw = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, true).result();
        var result = new ResolvedAttack(raw.id(), raw.kind(), raw.attacker(), raw.target(), raw.targetType(), 0,
              shot.defensive() ? "ISLaserAMS" : "ISLRM20", 0, true, List.of(new ResolvedAttack.Mount(source.id(), 0, shot)), shot);
        return new BoardScene.Combat(result, source, target, target.location());
    }

    static BoardScene.Unit unit(int id, int row, int level, ProneCause cause) {
        return new BoardScene.Unit(id, -1, "Fall review", new BoardScene.Waypoint(new Coords(0, row), level, 0).withProneCause(cause),
              null, false, null, 2, false);
    }

    static BoardScene scene(BoardScene.Unit... units) {
        var dry = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, null, null, List.of(), List.of());
        var wet = new BoardScene.Tile(new Coords(0, 1), 0, 1, false, 0, null, null, List.of(), List.of());
        return new BoardScene(0, 1, 2, List.of(dry, wet), List.of(units), List.of(), -1, "PHYSICAL", List.of());
    }
}
