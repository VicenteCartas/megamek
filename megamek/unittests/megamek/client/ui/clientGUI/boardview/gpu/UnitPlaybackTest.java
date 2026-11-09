/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import megamek.common.ResolvedAttack;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.ProneCause;
import org.junit.jupiter.api.Test;

class UnitPlaybackTest {
    @Test
    void scheduledStrikeReleasesItsFinalTerrainAtFlashBeforeFollowingCasualties() {
        var victim = UnitPlaybackFixture.unit(2, 0);
        var before = UnitPlaybackFixture.scene(victim);
        var after = new BoardScene(0, 1, 1, List.of(new BoardScene.Tile(new Coords(0, 0), -3, -1,
              false, 0, null, null, List.of(), List.of())), List.of(), List.of(), -1, "FIRING", List.of());
        var strike = new megamek.common.NuclearDetonation(UUID.randomUUID(), 0, new Coords(0, 0), 0, 10000, 109, 184, 3);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(before), new BoardScene.Detonation(strike),
              new BoardScene.SceneUpdate(after), UnitPlaybackFixture.attack(victim, victim, ResolvedAttack.Kind.DEATH, true)), after, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        assertNull(playback.attack(), "A scheduled strike has no invented attacker");
        assertEquals(0, playback.present(after).tiles().getFirst().elevation());
        assertTrue(playback.takeDetonations().isEmpty());
        playback.advance((UnitPlayback.DETONATION_CONTACT_SECONDS - .001) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(0, playback.present(after).tiles().getFirst().elevation());
        playback.advance(.001 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(List.of(strike), playback.takeDetonations().stream().map(BoardScene.Detonation::result).toList());
        assertEquals(-3, playback.present(after).tiles().getFirst().elevation());
        assertTrue(playback.present(after).units().contains(victim), "The queued casualty retains its observed model");
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        assertTrue(playback.takeDetonations().isEmpty(), "The flash edge is delivered once");
        playback.finish();
        assertTrue(playback.takeDetonations().isEmpty(), "Finishing after contact must not emit a second blast");
        assertEquals(after.units(), playback.present(after).units());
    }

    @Test
    void instantScheduledStrikesKeepOrderAndClearDiscardsUnplayedEffects() {
        var first = new megamek.common.NuclearDetonation(UUID.randomUUID(), 0, new Coords(0, 0), 0, 1000, 23, 86, 1);
        var second = new megamek.common.NuclearDetonation(UUID.randomUUID(), 0, new Coords(0, 0), -1, 1234, 31, 93, 2);
        var unchanged = UnitPlaybackFixture.scene();
        var playback = new UnitPlayback();
        var events = List.<BoardScene.Animation>of(new BoardScene.SceneUpdate(unchanged), new BoardScene.Detonation(first),
              new BoardScene.SceneUpdate(unchanged), new BoardScene.Detonation(second), new BoardScene.SceneUpdate(unchanged));
        playback.accept(events, unchanged, ignored -> false);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertEquals(List.of(first, second), playback.takeDetonations().stream().map(BoardScene.Detonation::result).toList());
        assertFalse(playback.busy());
        playback.accept(events, unchanged, ignored -> false);
        playback.clear();
        playback.finish();
        assertTrue(playback.takeDetonations().isEmpty());
    }

    /**
     * A chain of fuel tanks as the server nests it: the first sets off a tank two hexes north, which sets off one beyond
     * it, and a tank five hexes south. Each goes off as its own cause's front reaches it (GpuArtilleryBlast
     * #shockArrival), the southern one timed from the first tank although it plays after the northern pair, and all of
     * them long before the first one's falls are down; an explosion of its own after them goes off as the chain ends.
     */
    @Test
    void eachTankOfAChainGoesOffAsItsCausesFrontReachesIt() {
        var first = megamek.common.NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(4, 6), 0, 80, 8);
        var north = megamek.common.NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(4, 4), 0, 50, 5);
        var beyond = megamek.common.NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(4, 2), 0, 50, 5);
        var south = megamek.common.NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(4, 11), 0, 50, 5);
        var alone = megamek.common.NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(4, 7), 0, 50, 5);
        var unchanged = UnitPlaybackFixture.scene();
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(unchanged), detonation(first, null),
              new BoardScene.SceneUpdate(unchanged), detonation(north, first), new BoardScene.SceneUpdate(unchanged),
              detonation(beyond, north), detonation(south, first), detonation(alone, null)), unchanged, ignored -> false);
        double step = 1 / 480.0, clock = 0;
        java.util.Map<megamek.common.NuclearDetonation, Double> wentOff = new java.util.HashMap<>();
        while (wentOff.size() < 5 && clock < 20) {
            playback.advance(step / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            clock += step;
            for (var detonation : playback.takeDetonations()) { wentOff.put(detonation.result(), clock); }
        }
        assertEquals(UnitPlayback.arrival(first, north), wentOff.get(north) - wentOff.get(first), 2 * step);
        assertEquals(UnitPlayback.arrival(north, beyond), wentOff.get(beyond) - wentOff.get(north), 2 * step);
        assertEquals(UnitPlayback.arrival(first, south), wentOff.get(south) - wentOff.get(first), 2 * step,
              "Timed from its own cause, not from the tank before it");
        assertTrue(wentOff.get(south) - wentOff.get(first) < UnitPlayback.duration(first) / 2);
        assertEquals(UnitPlayback.duration(south), wentOff.get(alone) - wentOff.get(south), 2 * step,
              "One of its own waits for the chain's last");
    }

    private static BoardScene.Detonation detonation(megamek.common.NuclearDetonation result,
          megamek.common.NuclearDetonation cause) {
        return new BoardScene.Detonation(result, List.of(), List.of(), cause);
    }

    @Test
    void movementPresentationRetainsTheCapturedHeat() {
        var start = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(1, 4);
        var hot = new BoardScene.Unit(target.id(), target.part(), target.name(), target.location(), target.image(), false,
              target.annotations(), target.height(), target.airborne(), target.model(), target.outlineRgb(),
              target.footprint(), target.attachment(), 23);
        var move = new BoardScene.Movement(1, 0, List.of(start.location(), hot.location()), EntityMovementType.MOVE_WALK, 0, 4, hot);
        var playback = new UnitPlayback();
        playback.accept(List.of(move), UnitPlaybackFixture.scene(hot), ignored -> false);
        assertEquals(23, playback.present(UnitPlaybackFixture.scene(hot)).units().getFirst().heat());
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        assertEquals(23, playback.present(UnitPlaybackFixture.scene(hot)).units().getFirst().heat());
        playback.finish();
        assertEquals(23, playback.present(UnitPlaybackFixture.scene(hot)).units().getFirst().heat());
    }

    @Test
    void cameraHoldStopsEveryShotAndSoundAtEachVolleyBoundaryEvenWithALargeFastFrame() {
        var attacker = UnitPlaybackFixture.unit(1, 0);
        var firstTarget = UnitPlaybackFixture.unit(2, 8);
        var secondTarget = UnitPlaybackFixture.unit(3, 12);
        var first = UnitPlaybackFixture.attack(attacker, firstTarget, ResolvedAttack.Kind.SHOT, true);
        var second = UnitPlaybackFixture.attack(attacker, secondTarget, ResolvedAttack.Kind.SHOT, true);
        var reply = UnitPlaybackFixture.attack(firstTarget, attacker, ResolvedAttack.Kind.SHOT, true);
        List<UnitAttack> sounds = new ArrayList<>();
        var playback = new UnitPlayback(ignored -> { }, (shot, contact) -> sounds.add(shot));
        playback.accept(List.of(first, second, reply), UnitPlaybackFixture.scene(attacker, firstTarget, secondTarget), ignored -> false);
        playback.advance(10, UnitMotion.Speed.QUADRUPLE, state -> {
            assertEquals(2, state.attacks().size(), "The camera receives every target before any shot advances");
            return false;
        });
        assertEquals(2, playback.attacks().size());
        playback.attacks().forEach(shot -> assertTrue(shot.seconds <= 0, "Later target passes may have a launch delay"));
        assertTrue(sounds.isEmpty());
        assertFalse(playback.paused(), "Camera framing must not toggle the user's pause setting");
        playback.advance(10, UnitMotion.Speed.QUADRUPLE, state -> state.attack().event == first);
        assertSame(reply, playback.attack().event, "The next attacker also gets a camera hold within the same frame");
        assertEquals(0, playback.attack().seconds);
        assertTrue(sounds.stream().noneMatch(shot -> shot.event == reply));
        playback.advance(0, UnitMotion.Speed.INSTANT, ignored -> false);
        assertFalse(playback.busy(), "Skipping playback must also skip its camera hold");
    }

    @Test
    void movementUsesConstantAccelerationAndJumpUsesCapturedGravity() {
        var start = UnitPlaybackFixture.unit(1, 0).location();
        var end = UnitPlaybackFixture.unit(1, 40);
        var walk = new BoardScene.Movement(1, 0, List.of(start, end.location()), EntityMovementType.MOVE_WALK, 0, 4, end);
        var playback = new UnitPlayback();
        playback.accept(List.of(walk), UnitPlaybackFixture.scene(end), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        double duration = playback.motions.get(1).remainingSeconds();
        var expected = new UnitMotion(start);
        expected.append(walk.path(), walk.type(), walk.jumpMP(), false, walk.movementMP(), 0);
        assertEquals(expected.remainingSeconds(), duration, "Playback must use UnitMotion's constant acceleration");
        for (float gravity : new float[] { 0, .5f, 1, 2 }) {
            var landing = UnitPlaybackFixture.unit(2, 1);
            var jump = new BoardScene.Movement(2, 0, List.of(start, landing.location()), EntityMovementType.MOVE_JUMP, 6, 6, landing, gravity);
            var flight = new UnitPlayback();
            flight.accept(List.of(jump), UnitPlaybackFixture.scene(landing), ignored -> false);
            flight.advance(0, UnitMotion.Speed.NORMAL);
            var motion = flight.motions.get(2);
            double step = motion.remainingSeconds() / 1000 / UnitMotion.Speed.NORMAL.rate;
            float highest = 0;
            for (int i = 0; i < 1000; i++) {
                flight.advance(step, UnitMotion.Speed.NORMAL);
                highest = Math.max(highest, motion.position().z);
            }
            assertEquals(4 / Math.max(.25, gravity), highest / BoardGeometry.LEVEL, .001);
            flight.finish();
            assertFalse(flight.busy());
            assertNull(motion.sample().jets());
        }
    }

    @Test
    void stateOnlyTransitionsQueueButRepeatedSnapshotsAndRevealDoNotReplayThem() {
        var ground = aircraft(0, BoardScene.AeroState.LANDED);
        var air = aircraft(4, BoardScene.AeroState.AIRBORNE);
        var completions = new ArrayList<BoardScene.Movement>();
        var playback = new UnitPlayback(completions::add);
        playback.accept(List.of(), UnitPlaybackFixture.scene(ground), ignored -> false);
        assertFalse(playback.busy());

        playback.accept(List.of(), UnitPlaybackFixture.scene(air), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        var motion = playback.motions.get(ground.id());
        assertNotNull(motion.sample().gear(), "An observed takeoff must play its landing-gear transition");
        double takeoffSeconds = motion.remainingSeconds();
        playback.accept(List.of(), UnitPlaybackFixture.scene(air), ignored -> false);
        playback.accept(List.of(), UnitPlaybackFixture.scene(ground), ignored -> false);
        playback.accept(List.of(), UnitPlaybackFixture.scene(ground), ignored -> false);
        assertEquals(takeoffSeconds, motion.remainingSeconds(), "New snapshots must not restart the active takeoff");

        playback.advance(takeoffSeconds / UnitMotion.Speed.NORMAL.rate + UnitPlayback.COMPLETION_HOLD_SECONDS,
              UnitMotion.Speed.NORMAL);
        assertEquals(1, completions.size());
        assertEquals(List.of(ground.location(), air.location()), completions.getFirst().path());
        assertEquals(BoardGeometry.center(air.location().coords(), 0), motion.position(),
              "The queued landing must begin at the takeoff's destination");
        playback.finish();
        assertEquals(2, completions.size(), "Repeated snapshots must not enqueue duplicate transitions");
        assertEquals(List.of(air.location(), ground.location()), completions.getLast().path());
        assertEquals(BoardGeometry.center(ground.location().coords(), 0), motion.position());
        playback.accept(List.of(), UnitPlaybackFixture.scene(ground), ignored -> false);
        assertFalse(playback.busy());

        var revealed = new UnitPlayback();
        revealed.accept(List.of(), UnitPlaybackFixture.scene(air), ignored -> false);
        revealed.advance(0, UnitMotion.Speed.NORMAL);
        assertFalse(revealed.busy(), "First seeing an airborne unit must not invent a takeoff");
        assertTrue(revealed.motions.isEmpty());
    }

    @Test
    void observedTakeoffPrecedesAFlightPathWithoutReplayingEitherMove() {
        var ground = aircraft(0, BoardScene.AeroState.LANDED);
        var air = aircraft(1, BoardScene.AeroState.AIRBORNE);
        var end = aircraft(2, BoardScene.AeroState.AIRBORNE);
        var completions = new ArrayList<BoardScene.Movement>();
        var playback = new UnitPlayback(completions::add);
        playback.accept(List.of(), UnitPlaybackFixture.scene(ground), ignored -> false);
        playback.accept(List.of(), UnitPlaybackFixture.scene(air), ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        var motion = playback.motions.get(ground.id());
        double takeoffSeconds = motion.remainingSeconds();
        var flight = new BoardScene.Movement(air.id(), 0,
              List.of(air.location().withFootprint(List.of()), end.location()),
              EntityMovementType.MOVE_SAFE_THRUST, 0, 0, end);
        playback.accept(List.of(flight), UnitPlaybackFixture.scene(end), ignored -> false);
        playback.accept(List.of(), UnitPlaybackFixture.scene(end), ignored -> false);
        playback.advance(takeoffSeconds / UnitMotion.Speed.NORMAL.rate + UnitPlayback.COMPLETION_HOLD_SECONDS,
              UnitMotion.Speed.NORMAL);
        assertEquals(1, completions.size());
        assertEquals(BoardGeometry.center(air.location().coords(), 0), motion.position());
        assertNull(motion.sample().gear(), "Continuing flight must not repeat the takeoff transition");
        playback.advance(motion.remainingSeconds() / (2 * UnitMotion.Speed.NORMAL.rate), UnitMotion.Speed.NORMAL);
        assertTrue(motion.position().epsilonEquals(BoardGeometry.center(air.location().coords(), 0)
              .lerp(BoardGeometry.center(end.location().coords(), 0), .5f), .001f));
        playback.finish();
        assertEquals(2, completions.size());
        assertSame(flight, completions.getLast());
        assertFalse(playback.busy());
    }

    private static BoardScene.Unit aircraft(int row, BoardScene.AeroState state) {
        Coords coords = new Coords(0, row);
        var location = new BoardScene.Waypoint(coords, 0, 0).withAeroState(state).withFootprint(List.of(coords));
        return new BoardScene.Unit(1, -1, "Aircraft", location, null, false, null, 2,
              state != BoardScene.AeroState.LANDED);
    }

    @Test
    void visualGravityChangesNewJumpArcsAndTimingWithoutChangingCapturedMoves() {
        var start = UnitPlaybackFixture.unit(1, 0).location();
        var landing = UnitPlaybackFixture.unit(1, 1);
        var jump = new BoardScene.Movement(1, 0, List.of(start, landing.location()), EntityMovementType.MOVE_JUMP, 6, 6, landing, 1);
        double lowDuration = 0;
        float lowApex = 0;
        for (float gravity : new float[] { 0.5f, 2 }) {
            var flight = new UnitPlayback();
            flight.gravityOverride = gravity;
            flight.accept(List.of(jump), UnitPlaybackFixture.scene(landing), ignored -> false);
            flight.advance(0, UnitMotion.Speed.NORMAL);
            var motion = flight.motions.get(1);
            double duration = motion.remainingSeconds();
            float apex = 0;
            // Changing the preview while airborne must not snap or rebuild an already started jump.
            flight.gravityOverride = 10;
            for (int i = 0; i < 1000; i++) {
                flight.advance(duration / 1000 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
                apex = Math.max(apex, motion.position().z);
            }
            assertEquals(4 / gravity, apex / BoardGeometry.LEVEL, .001);
            if (gravity < 1) {
                lowDuration = duration;
                lowApex = apex;
            } else {
                assertTrue(lowApex > apex, "Lower preview gravity produces a taller jump");
                assertTrue(Math.abs(lowDuration - duration) > .01, "Preview gravity must also change jump timing");
            }
            assertEquals(1, jump.gravity(), "The game movement snapshot must remain untouched");
            assertEquals(landing.location(), jump.path().getLast());
            flight.finish();
            flight.gravityOverride = Float.NaN;
            flight.accept(List.of(jump), UnitPlaybackFixture.scene(landing), ignored -> false);
            flight.advance(0, UnitMotion.Speed.NORMAL);
            var captured = new UnitMotion(start);
            captured.append(jump.path(), jump.type(), jump.jumpMP(), false, jump.movementMP(), 0);
            assertEquals(captured.remainingSeconds(), motion.remainingSeconds(), .00001,
                  "Removing the preview restores captured game gravity on the next jump");
        }
    }

    @Test
    void pushUsesObservedDisplacementForBothParticipantsAndNeverInventsAMove() {
        var attacker = UnitPlaybackFixture.unit(1, 0);
        var victim = UnitPlaybackFixture.unit(2, 1);
        var advanced = UnitPlaybackFixture.unit(1, 1);
        var pushed = UnitPlaybackFixture.unit(2, 2);
        for (boolean moved : List.of(true, false)) {
            var after = moved ? UnitPlaybackFixture.scene(advanced, pushed) : UnitPlaybackFixture.scene(attacker, victim);
            for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.QUADRUPLE)) {
                var playback = new UnitPlayback();
                playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(attacker, victim)),
                      UnitPlaybackFixture.attack(attacker, victim, ResolvedAttack.Kind.PUSH, true), new BoardScene.SceneUpdate(after)), after, ignored -> false);
                playback.advance((.65 + UnitAttack.RECOVERY_SECONDS * .5) / speed.rate, speed);
                var visible = playback.present(after);
                for (var unit : visible.units()) {
                    var end = BoardGeometry.center(unit.location().coords(), 0);
                    var position = end.cpy();
                    playback.placeDisplacement(unit, position);
                    var original = BoardGeometry.center((unit.id() == 1 ? attacker : victim).location().coords(), 0);
                    assertEquals(original.lerp(end, .5f).y, position.y, .0001);
                }
                playback.advance(UnitAttack.RECOVERY_SECONDS / speed.rate + 1, speed);
                assertEquals(after.units(), playback.present(after).units());
            }
        }
    }

    @Test
    void aTargetChargedOffTheBoardShowsUntilItsFallEndsLiveAndInItsReplay() {
        // Facing north from (0, 1), the charge shoves its target at (0, 0) off the one-hex board; MegaMek removes it.
        var charger = UnitPlaybackFixture.unit(1, 1);
        var target = UnitPlaybackFixture.unit(2, 0);
        var after = UnitPlaybackFixture.scene(charger);
        var charge = UnitPlaybackFixture.attack(charger, target, ResolvedAttack.Kind.CHARGE, true);
        var clock = new UnitAttack(charge);
        float fall = clock.contactSeconds + UnitAttack.SHOVE_SECONDS + UnitAttack.PUSHED_OFF_FALL_SECONDS;
        var speed = UnitMotion.Speed.NORMAL;
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(charger, target)), charge, new BoardScene.SceneUpdate(after)),
              after, ignored -> false);
        playback.advance((clock.contactSeconds + .05) / speed.rate, speed);
        assertTrue(playback.present(after).units().contains(target), "The target goes over the edge after the contact");
        playback.advance((fall - clock.contactSeconds - .1) / speed.rate, speed);
        assertTrue(playback.present(after).units().contains(target), "It shows until its fall ends");
        playback.advance(.1 / speed.rate, speed);
        assertFalse(playback.present(after).units().contains(target));
        playback.advance(100, speed);
        assertFalse(playback.busy());
        playback.review(charge);
        playback.advance((clock.contactSeconds + .05) / speed.rate, speed);
        assertTrue(playback.present(after).units().contains(target), "Its replay pushes it off again");
        playback.advance(fall / speed.rate, speed);
        assertFalse(playback.reviewing());
        assertEquals(after.units(), playback.present(after).units());

        // Skipped to the results, the push off never played: its replay tips the target off all the same.
        var skipped = new UnitPlayback();
        skipped.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(charger, target)), charge, new BoardScene.SceneUpdate(after)),
              after, ignored -> false);
        skipped.advance(0, UnitMotion.Speed.INSTANT);
        assertFalse(skipped.busy());
        skipped.review(charge);
        skipped.advance((clock.contactSeconds + .05) / speed.rate, speed);
        assertTrue(skipped.present(after).units().contains(target));
        assertTrue(skipped.attack().pushedOff(), "The replay after skipping goes over the edge, as the live one would have");
    }

    @Test
    void aDeathFromAboveShovesOffTheBoardAsMegamekDisplacesIt() {
        // From (0, 2) facing north, the shove's own hex (0, 0) is on the two-hex board. MegaMek removed the target, so a
        // death from above took the next hex in its order, off the board; a charge shoves only along its facing.
        var attacker = UnitPlaybackFixture.unit(1, 2);
        var target = UnitPlaybackFixture.unit(2, 1);
        var tiles = List.of(new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, null, null, List.of(), List.of()),
              new BoardScene.Tile(new Coords(0, 1), 0, -1, false, 0, null, null, List.of(), List.of()));
        var before = new BoardScene(0, 1, 2, tiles, List.of(attacker, target), List.of(), -1, "PHYSICAL", List.of());
        var after = before.withUnits(List.of(attacker));
        var speed = UnitMotion.Speed.NORMAL;
        for (var kind : List.of(ResolvedAttack.Kind.DFA, ResolvedAttack.Kind.CHARGE)) {
            var event = UnitPlaybackFixture.attack(attacker, target, kind, true);
            var playback = new UnitPlayback();
            playback.accept(List.of(new BoardScene.SceneUpdate(before), event, new BoardScene.SceneUpdate(after)), after,
                  ignored -> false);
            playback.advance((new UnitAttack(event).contactSeconds + .05) / speed.rate, speed);
            assertEquals(kind == ResolvedAttack.Kind.DFA, playback.attack().pushedOff(), kind.name());
        }
    }

    @Test
    void destructionFinishesBeforeRemovalAtEverySpeedAndCanBePausedOrSkipped() {
        var victim = UnitPlaybackFixture.unit(2, 5);
        var removed = UnitPlaybackFixture.scene();
        for (var speed : UnitMotion.Speed.values()) {
            var playback = new UnitPlayback();
            playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(victim)),
                  UnitPlaybackFixture.attack(victim, victim, ResolvedAttack.Kind.DEATH, true), new BoardScene.SceneUpdate(removed)),
                  removed, ignored -> false);
            playback.advance(0, speed);
            if (speed != UnitMotion.Speed.INSTANT) {
                playback.advance((UnitAttack.DEATH_SECONDS - .001) / speed.rate, speed);
                assertTrue(playback.present(removed).units().contains(victim));
                float pose = playback.attack().deathProgress();
                playback.togglePaused();
                playback.advance(20, speed);
                assertEquals(pose, playback.attack().deathProgress());
                playback.togglePaused();
                playback.advance(.001 / speed.rate, speed);
                assertTrue(playback.present(removed).units().isEmpty());
                double hold = UnitPlayback.DEATH_HOLD_SECONDS;
                assertEquals(hold, playback.holdSeconds(), 1e-5);
                playback.advance(hold + .001, speed);
            }
            assertFalse(playback.busy());
            assertTrue(playback.present(removed).units().isEmpty());
        }
    }

    @Test
    void aUnitThatDiesInItsFallDiesAsItLands() {
        var victim = UnitPlaybackFixture.unit(2, 5);
        var down = victim.at(victim.location().withProneCause(ProneCause.FORCED));
        var fall = new BoardScene.Movement(victim.id(), 0, List.of(victim.location().withProneCause(ProneCause.NONE),
              down.location()), EntityMovementType.MOVE_NONE, 0, 0, down);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(victim)), fall,
              UnitPlaybackFixture.attack(down, down, ResolvedAttack.Kind.DEATH, true), new BoardScene.SceneUpdate(UnitPlaybackFixture.scene())), UnitPlaybackFixture.scene(), ignored -> false);
        var speed = UnitMotion.Speed.NORMAL;
        playback.advance(0, speed);
        assertNull(playback.attack(), "The fall plays first");
        playback.advance(UnitMotion.POSTURE_SECONDS / speed.rate + .001, speed);
        assertTrue(playback.attack() != null && playback.attack().death(), "The death plays as the fall lands");
    }

    @Test
    void concealmentClearsCapturedPassengersAttacksAndHistoryWithoutReplayingOnReveal() {
        var original = UnitPlaybackFixture.unit(1, 4);
        var victim = UnitPlaybackFixture.unit(2, 5);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(original, victim)), move(1),
              UnitPlaybackFixture.attack(original, victim, ResolvedAttack.Kind.SHOT, true)), UnitPlaybackFixture.scene(original, victim), ignored -> true);
        playback.advance(.2, UnitMotion.Speed.NORMAL);
        assertNotNull(playback.motions.get(1).sample().boarding());
        playback.togglePaused();
        playback.accept(List.of(new BoardScene.Concealed(1, 0), new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(victim))),
              UnitPlaybackFixture.scene(victim), ignored -> true);
        assertTrue(playback.paused());
        assertTrue(playback.motions.isEmpty());
        assertTrue(playback.attacks().isEmpty());
        assertEquals(List.of(victim), playback.present(UnitPlaybackFixture.scene(victim)).units());
        playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(original, victim))), UnitPlaybackFixture.scene(original, victim), ignored -> true);
        playback.togglePaused();
        playback.advance(10, UnitMotion.Speed.NORMAL);
        assertFalse(playback.busy());
        assertTrue(playback.motions.isEmpty());
    }

    @Test
    void pauseFreezesTravelAttacksAndTheRealTimeCompletionHoldWhileInstantStillSkips() {
        var playback = new UnitPlayback();
        playback.accept(List.of(move(1), UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.SHOT, true)),
              UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5)), ignored -> false);
        playback.advance(.5, UnitMotion.Speed.NORMAL);
        var motion = playback.motions.get(1);
        var position = motion.position().cpy();
        double remaining = motion.remainingSeconds();
        playback.togglePaused();
        playback.advance(100, UnitMotion.Speed.QUADRUPLE);
        assertEquals(position, motion.position());
        assertEquals(remaining, motion.remainingSeconds());
        playback.togglePaused();
        playback.advance(remaining / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        playback.togglePaused();
        playback.advance(100, UnitMotion.Speed.HALF);
        assertEquals(1, playback.holdSeconds(), 1e-6);
        playback.togglePaused();
        playback.advance(1.1, UnitMotion.Speed.NORMAL);
        float attackSeconds = playback.attack().seconds;
        playback.togglePaused();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertEquals(attackSeconds, playback.attack().seconds);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertFalse(playback.busy());
        assertFalse(playback.paused());
    }

    @Test
    void skippingQueuedMovesAppliesCapturedArrivalStatesInOrderAndClearingDoesNotReplayThem() {
        var completions = new ArrayList<BoardScene.Movement>();
        var playback = new UnitPlayback(completions::add);
        var first = move(1);
        var second = new BoardScene.Movement(1, 0, List.of(UnitPlaybackFixture.unit(1, 4).location(), UnitPlaybackFixture.unit(1, 0).location()),
              EntityMovementType.MOVE_WALK, 0, 4, UnitPlaybackFixture.unit(1, 0));
        playback.accept(List.of(first, second), UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 0)), ignored -> true);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertEquals(List.of(first, second), completions);
        assertEquals(BoardGeometry.center(second.path().getLast().coords(), 0), playback.motions.get(1).position());
        assertEquals(UnitMotion.Stage.UNLOAD, playback.motions.get(1).sample().boarding().stage());
        assertEquals(1, playback.motions.get(1).sample().boarding().progress());
        playback.accept(List.of(first), UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4)), ignored -> true);
        playback.clear();
        assertEquals(2, completions.size(), "A board switch discards its old playback instead of re-creating old models");
        assertTrue(playback.motions.isEmpty());
    }

    @Test
    void stepOnceWhilePausedPresentsExactlyOneMoreAction() {
        var first = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.SHOT, true);
        var second = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(3, 6), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.SHOT, false);
        var third = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(3, 6), ResolvedAttack.Kind.KICK, true);
        var playback = new UnitPlayback();
        playback.accept(List.of(first, second, third), UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), UnitPlaybackFixture.unit(3, 6)), ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        playback.stepOnce();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertFalse(playback.busy(), "Without a pause the queue plays on");

        playback.accept(List.of(first, second, third), UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), UnitPlaybackFixture.unit(3, 6)), ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        playback.togglePaused();
        assertEquals(Set.of(first.result().id(), second.result().id(), third.result().id()), playback.waiting(),
              "The shot under way has not landed yet");
        playback.stepOnce();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertSame(first, playback.attack().event, "The shot under way landing is the one more action");
        assertEquals(Set.of(second.result().id(), third.result().id()), playback.waiting());
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertSame(first, playback.attack().event, "The queue waits again");
        playback.stepOnce();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertSame(second, playback.attack().event, "A landed shot finishes, then the next one plays");
        assertTrue(playback.paused());
        assertEquals(Set.of(third.result().id()), playback.waiting());
        playback.stepOnce();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertSame(third, playback.attack().event);
        assertTrue(playback.waiting().isEmpty());
        playback.togglePaused();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertFalse(playback.busy());
    }

    @Test
    void reviewPlaysARetainedAttackAloneOverTheLiveSceneAndAppliesNothing() {
        var attacker = UnitPlaybackFixture.unit(1, 4);
        var target = UnitPlaybackFixture.unit(2, 5);
        var hit = new BoardScene.Unit(2, -1, "Unit 2 hit", target.location(), null, false, null, 2, false);
        var retained = UnitPlaybackFixture.attack(attacker, target, ResolvedAttack.Kind.SHOT, true);
        var queued = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(3, 6), hit, ResolvedAttack.Kind.SHOT, false);
        var live = UnitPlaybackFixture.scene(attacker, hit, UnitPlaybackFixture.unit(3, 6));
        var playback = new UnitPlayback();
        playback.accept(List.of(queued), live, ignored -> false);
        playback.togglePaused();
        playback.review(retained);
        assertSame(retained, playback.attack().event);
        assertEquals(List.of(retained), playback.attacks().stream().map(shot -> shot.event).toList());
        var shown = playback.present(live);
        assertSame(live.tiles(), shown.tiles(), "A review keeps the live tiles");
        assertEquals(target, shown.units().stream().filter(unit -> unit.id() == 2).findFirst().orElseThrow(),
              "Before its impact the target shows as captured");
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertFalse(playback.reviewing());
        assertEquals(Set.of(queued.result().id()), playback.waiting(), "The paused live queue did not move");
        assertEquals(live.units(), playback.present(live).units(), "Nothing of the review stays");

        playback.togglePaused();
        playback.review(retained);
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertEquals(Set.of(queued.result().id()), playback.waiting(), "Even a large frame never interleaves the two");
        playback.advance(.01, UnitMotion.Speed.NORMAL);
        assertSame(queued, playback.attack().event, "The live queue goes on after the review");
        playback.review(retained);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertFalse(playback.reviewing(), "Skipping ends a review");
        assertFalse(playback.busy());
    }

    private static BoardScene.Movement move(int id) {
        return new BoardScene.Movement(id, 0, List.of(UnitPlaybackFixture.unit(id, 0).location(), UnitPlaybackFixture.unit(id, 4).location()),
              EntityMovementType.MOVE_WALK, 0, 4, UnitPlaybackFixture.unit(id, 4));
    }

    private static BoardScene withRanges(BoardScene scene, String label) {
        Coords coords = new Coords(0, 0);
        var line = new BoardScene.FiringLine(UnitPlaybackFixture.unit(1, 0).location(), UnitPlaybackFixture.unit(2, 4).location(), 0x44FF88, false, 1,
              TargetKey.unit(2));
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(), List.of(line),
              List.of(new BoardScene.RangeBorder(coords, 63, 0x44FF88, label)), scene.markers(), scene.tactical(),
              List.of(new BoardScene.RangeLabel(coords, 0x44FF88, label)), scene.fieldOfView());
    }

    @Test
    void rangesWaitForMovementAndThenUseTheLatestSnapshotAtEverySpeed() {
        for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
            var playback = new UnitPlayback();
            var initial = withRanges(UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4)), "S");
            playback.accept(List.of(move(1)), initial, ignored -> false);
            assertTrue(playback.present(initial).rangeBorders().isEmpty(), "Queued movement must already suppress ranges");
            assertTrue(playback.present(initial).rangeLabels().isEmpty());
            playback.advance(0, speed);
            var motion = playback.motions.get(1);
            playback.advance(motion.remainingSeconds() / (2 * speed.rate), speed);
            assertTrue(motion.isMoving());
            var latest = withRanges(initial, "M");
            playback.accept(List.of(), latest, ignored -> false);
            var moving = playback.present(latest);
            assertTrue(moving.rangeBorders().isEmpty());
            assertTrue(moving.rangeLabels().isEmpty());
            assertTrue(moving.firingLines().isEmpty(), "Attack endpoints must not jump to the destination mid-move");
            assertEquals("S", initial.rangeBorders().getFirst().label(), "Presentation must not mutate published scenes");
            playback.advance(motion.remainingSeconds() / speed.rate, speed);
            assertFalse(motion.isMoving());
            assertTrue(playback.busy(), "The final completion hold is still active");
            assertEquals(latest.rangeBorders(), playback.present(latest).rangeBorders());
            assertEquals(latest.rangeLabels(), playback.present(latest).rangeLabels());
        }
    }

    @Test
    void rangesStayHiddenAcrossQueuedMovesAndInterveningHoldsAndAttacks() {
        var playback = new UnitPlayback();
        var scene = withRanges(UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 4)), "S");
        playback.accept(List.of(move(1), UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 4), ResolvedAttack.Kind.SHOT, true), move(2)),
              scene, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        playback.advance(playback.motions.get(1).remainingSeconds() / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertFalse(playback.motions.get(1).isMoving());
        assertNull(playback.motions.get(2));
        assertTrue(playback.present(scene).rangeBorders().isEmpty(), "Ranges must not flash during the gap between moves");
        playback.advance(UnitPlayback.COMPLETION_HOLD_SECONDS, UnitMotion.Speed.NORMAL);
        assertNotNull(playback.attack());
        assertTrue(playback.present(scene).rangeLabels().isEmpty(), "The second move is still waiting behind combat");
        playback.advance(playback.attack().duration / UnitMotion.Speed.NORMAL.rate + UnitPlayback.COMPLETION_HOLD_SECONDS,
              UnitMotion.Speed.NORMAL);
        assertTrue(playback.motions.get(2).isMoving());
        assertTrue(playback.present(scene).rangeBorders().isEmpty());
        playback.advance(playback.motions.get(2).remainingSeconds() / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(scene.rangeBorders(), playback.present(scene).rangeBorders());
        assertEquals(scene.rangeLabels(), playback.present(scene).rangeLabels());
    }

    @Test
    void combatAloneKeepsRangesAndSkippingOrClearingMovementRestoresThem() {
        var scene = withRanges(UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5)), "S");
        var playback = new UnitPlayback();
        playback.accept(List.of(UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.SHOT, true)), scene, ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        assertTrue(playback.busy());
        assertEquals(scene.rangeBorders(), playback.present(scene).rangeBorders());
        assertEquals(scene.rangeLabels(), playback.present(scene).rangeLabels());
        for (Consumer<UnitPlayback> finish : List.<Consumer<UnitPlayback>>of(
              value -> value.advance(0, UnitMotion.Speed.INSTANT), UnitPlayback::finish, UnitPlayback::clear)) {
            playback.clear();
            playback.accept(List.of(move(1)), scene, ignored -> false);
            playback.advance(.1, UnitMotion.Speed.NORMAL);
            assertTrue(playback.present(scene).rangeBorders().isEmpty());
            finish.accept(playback);
            assertSame(scene, playback.present(scene));
        }
    }

    @Test
    void movementHoldsOneRealSecondBeforeTheNextUnitsMoveAtEverySpeed() {
        for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
            var playback = new UnitPlayback();
            var scene = UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 4));
            playback.accept(List.of(move(1), move(2)), scene, id -> false);
            playback.advance(0, speed);
            assertEquals(0, playback.present(scene).units().get(1).location().coords().getY(), "Waiting unit stays at departure");
            playback.advance(playback.motions.get(1).remainingSeconds() / speed.rate, speed);
            assertFalse(playback.motions.get(1).isMoving());
            assertEquals(1, playback.holdSeconds(), 1e-6);
            playback.advance(.999, speed);
            assertNull(playback.motions.get(2));
            playback.advance(.002, speed);
            assertTrue(playback.motions.get(2).isMoving());
        }
    }

    @Test
    void attacksShareTheQueueAndHoldAfterRecovery() {
        var playback = new UnitPlayback();
        var scene = UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5));
        var attack = UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.SHOT, true);
        playback.accept(List.of(move(1), attack, move(2)), scene, id -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        playback.advance(playback.motions.get(1).remainingSeconds() / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertNull(playback.attack());
        playback.advance(1, UnitMotion.Speed.NORMAL);
        assertNotNull(playback.attack());
        float duration = playback.attack().duration;
        playback.advance(duration * 2, UnitMotion.Speed.NORMAL);
        assertEquals(duration, playback.attack().seconds);
        assertEquals(1, playback.holdSeconds(), 1e-6);
        assertNull(playback.motions.get(2));
        playback.advance(1, UnitMotion.Speed.NORMAL);
        assertNull(playback.attack());
        assertTrue(playback.motions.get(2).isMoving());
    }

    @Test
    void instantAndBoardChangesCancelEffectsAndHolds() {
        var playback = new UnitPlayback();
        var scene = UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5));
        for (var kind : ResolvedAttack.Kind.values()) {
            playback.accept(List.of(UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), kind, true), move(1)), scene, id -> false);
            playback.advance(.5, UnitMotion.Speed.NORMAL);
            assertNotNull(playback.attack());
            playback.advance(0, UnitMotion.Speed.INSTANT);
            assertFalse(playback.busy());
            assertNull(playback.attack());
            assertSame(scene, playback.present(scene));
        }
        playback.accept(List.of(move(1)), scene, id -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        playback.clear();
        assertFalse(playback.busy());
        assertTrue(playback.motions.isEmpty());
    }

    @Test
    void frameRateAndLargeFrameDeltasDoNotChangeQueueTiming() {
        var low = new UnitPlayback();
        var high = new UnitPlayback();
        var once = new UnitPlayback();
        var scene = UnitPlaybackFixture.scene(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5));
        List<BoardScene.Animation> events = List.of(move(1), UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(1, 4), UnitPlaybackFixture.unit(2, 5), ResolvedAttack.Kind.KICK, false), move(2));
        for (var playback : List.of(low, high, once)) {
            playback.accept(events, scene, id -> false);
            playback.advance(0, UnitMotion.Speed.NORMAL);
        }
        double seconds = once.motions.get(1).remainingSeconds() / UnitMotion.Speed.NORMAL.rate
              + UnitPlayback.COMPLETION_HOLD_SECONDS + .4;
        for (int rate : new int[] { 30, 144 }) {
            var playback = rate == 30 ? low : high;
            int frames = (int) (seconds * rate);
            for (int i = 0; i < frames; i++) { playback.advance(1.0 / rate, UnitMotion.Speed.NORMAL); }
            playback.advance(seconds - frames / (double) rate, UnitMotion.Speed.NORMAL);
        }
        once.advance(seconds, UnitMotion.Speed.NORMAL);
        assertNotNull(once.attack());
        assertEquals(once.attack().seconds, low.attack().seconds, 1e-5);
        assertEquals(once.attack().seconds, high.attack().seconds, 1e-5);
        assertEquals(0, high.attack().impact(), "A miss must not generate a hit reaction");
    }

    @Test
    void queuedAttacksKeepOnlyTheAlreadyKnownVictimUntilPlaybackFinishes() {
        var attacker = UnitPlaybackFixture.unit(1, 4);
        var victim = UnitPlaybackFixture.unit(2, 5);
        var playback = new UnitPlayback();
        var removed = UnitPlaybackFixture.scene(attacker);
        playback.accept(List.of(UnitPlaybackFixture.attack(attacker, victim, ResolvedAttack.Kind.SHOT, true)), removed, id -> false);
        playback.advance(.5, UnitMotion.Speed.NORMAL);
        assertTrue(playback.present(removed).units().contains(victim));
        playback.advance(10, UnitMotion.Speed.NORMAL);
        assertSame(removed, playback.present(removed));
    }

    @Test
    void groupCompletionHoldWaitsForTheLastSuitToLand() {
        var armor = new BattleArmor();
        armor.setSquadSize(6);
        for (int i = 1; i <= 6; i++) { armor.initializeInternal(1, i); }
        var appearance = new BoardScene.UnitModel("", "", "", 6, 0, BoardScene.LocationDamage.NONE, UnitModelState.capture(armor));
        var unit = new BoardScene.Unit(10, -1, "Battle Armor", UnitPlaybackFixture.unit(10, 4).location(), null, false, null, 1, false, appearance, 0);
        var playback = new UnitPlayback();
        var scene = withRanges(UnitPlaybackFixture.scene(unit), "S");
        playback.accept(List.of(new BoardScene.Movement(10, 0, List.of(UnitPlaybackFixture.unit(10, 0).location(), unit.location()),
              EntityMovementType.MOVE_JUMP, 3, 3, unit)), scene, id -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        var motion = playback.motions.get(10);
        assertNotNull(motion.sample().group());
        playback.advance((motion.remainingSeconds() - UnitMotion.GROUP_START_JITTER_SECONDS
              - UnitMotion.FORMATION_SETTLE_SECONDS) * 2, UnitMotion.Speed.NORMAL);
        assertTrue(motion.isMoving());
        assertEquals(0, playback.holdSeconds());
        playback.advance(UnitMotion.GROUP_START_JITTER_SECONDS * 2, UnitMotion.Speed.NORMAL);
        assertTrue(motion.isMoving());
        assertEquals(0, playback.holdSeconds());
        assertTrue(playback.present(scene).rangeBorders().isEmpty(), "The final formation settling must also finish");
        assertTrue(playback.present(scene).rangeLabels().isEmpty());
        playback.advance(UnitMotion.FORMATION_SETTLE_SECONDS * 2, UnitMotion.Speed.NORMAL);
        assertFalse(motion.isMoving());
        assertEquals(1, playback.holdSeconds(), 1e-6);
        assertEquals(scene.rangeBorders(), playback.present(scene).rangeBorders());
        assertEquals(scene.rangeLabels(), playback.present(scene).rangeLabels());
    }

    @Test
    void queuedTransportDecisionUsesItsCapturedUnitInsteadOfTheLatestFormation() {
        var original = UnitPlaybackFixture.unit(1, 4);
        var changed = UnitPlaybackFixture.unit(1, 5);
        var target = UnitPlaybackFixture.unit(2, 6);
        var movement = new BoardScene.Movement(1, 0, List.of(UnitPlaybackFixture.unit(1, 0).location(), original.location()),
              EntityMovementType.MOVE_WALK, 0, 4, original);
        var playback = new UnitPlayback();
        AtomicReference<BoardScene.Unit> used = new AtomicReference<>();
        java.util.function.Predicate<BoardScene.Unit> transports = captured -> {
            used.set(captured);
            return captured == original;
        };
        playback.accept(List.of(UnitPlaybackFixture.attack(original, target, ResolvedAttack.Kind.SHOT, true), movement), UnitPlaybackFixture.scene(original, target), transports);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        float duration = playback.attack().duration;
        playback.accept(List.of(), UnitPlaybackFixture.scene(changed, target), transports);
        playback.advance(duration / UnitMotion.Speed.NORMAL.rate + 1, UnitMotion.Speed.NORMAL);
        assertSame(original, used.get());
        assertNotNull(playback.motions.get(1).sample().boarding());
        assertEquals(UnitMotion.Stage.BOARD, playback.motions.get(1).sample().boarding().stage());
    }
}
