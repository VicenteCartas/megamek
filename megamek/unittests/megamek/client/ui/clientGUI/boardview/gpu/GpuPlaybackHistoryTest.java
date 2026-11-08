/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.attack;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.scene;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackFixture.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Test;

/**
 * The playback history's rules (rebuild plan A.10; J4, J7, J8, J10, K8) on a scripted firing report of round 3: four
 * shots whose events play through a real UnitPlayback, one of them linked to no report entry, a shot the reports give
 * as impossible and a piloting roll, in report order. Review never feeds the live queue, so nothing it shows outlasts
 * it, and the live events that arrive meanwhile wait for it.
 */
class GpuPlaybackHistoryTest {
    private static final Predicate<BoardScene.Unit> NO_TRANSPORTS = unit -> false;
    private static final BoardScene.Unit ATLAS = unit(1, 0);
    private static final BoardScene.Unit WARHAMMER = unit(2, 1);
    private static final BoardScene.Unit MARAUDER = unit(3, 2);
    private static final BoardScene.Unit TIMBER_WOLF = unit(6, 7);
    private static final BoardScene.Unit KING_CRAB = unit(7, 8);
    /** The King Crab as the client shows it after the volley. */
    private static final BoardScene.Unit KING_CRAB_HIT = new BoardScene.Unit(7, -1, "Unit 7 hit", KING_CRAB.location(),
          null, false, null, 2, false);
    private static final List<String> ROUND_STEPS = List.of("1>7", "not fired 2", "2>7", "3>7", "1>6", "roll 7");

    /** Round 3's firing report and its events, in the order the server sends them. */
    private static final class Round {
        final BoardScene.Combat atlas = attack(ATLAS, KING_CRAB, ResolvedAttack.Kind.SHOT, true);
        final BoardScene.Combat warhammer = attack(WARHAMMER, KING_CRAB, ResolvedAttack.Kind.SHOT, false);
        /** A shot the client animated whose report line no entry links (a bay weapon, say). */
        final BoardScene.Combat marauder = attack(MARAUDER, KING_CRAB, ResolvedAttack.Kind.SHOT, true);
        final BoardScene.Combat second = attack(ATLAS, TIMBER_WOLF, ResolvedAttack.Kind.SHOT, false);
        final GpuReportLog.PsrItem fall = new GpuReportLog.PsrItem(3L << 32 | 40, 3, GamePhase.FIRING, 7, 5, 4,
              false, "took 20+ damage");
        final GpuReportLog.Snapshot empty = log(List.of(), List.of(), List.of());
        final GpuReportLog.Snapshot log = log(List.of(entry(atlas), entry(GpuReportLog.Kind.WEAPON, 2, 6, null,
                    List.of(), "Target not in arc"), entry(warhammer), entry(second),
              entry(GpuReportLog.Kind.PSR, -1, -1, null, List.of(fall.id()), null)),
              List.of(event(atlas), event(warhammer), event(marauder), event(second)), List.of(fall));

        List<BoardScene.Animation> timeline() {
            return List.of(atlas, warhammer, marauder, second);
        }
    }

    @Test
    void liveStepsFollowTheReportAndSteppingReviewsThemOrPlaysOneMoreActionAtTheLiveEdge() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB);
        frame(history, List.of(), scene, round.empty, 0);
        frame(history, round.timeline(), scene, round.log, .01);

        assertEquals(ROUND_STEPS, names(history.steps()),
              "Report order; the shot no entry links comes before the next linked one");
        assertTrue(history.played().isEmpty(), "Nothing is presented before the Atlas's shot lands");
        float contact = new UnitAttack(round.atlas).contactSeconds;
        frame(history, List.of(), scene, round.log, contact / UnitMotion.Speed.NORMAL.rate);
        assertEquals(List.of("1>7", "not fired 2"), names(history.played()),
              "The Atlas's shot lands; the next cards wait for the Warhammer's shot");
        assertEquals("not fired 2", name(history.current()), "The cursor follows live play");
        assertEquals(List.of("1>7", "not fired 2"), names(history.takeShown()), "Both pop up");
        assertEquals(List.of(round.atlas.result().id()), history.playedAttacks().stream()
              .map(GpuReportLog.CombatEvent::id).toList(), "Only the presented attacks count");

        history.togglePaused();
        history.step(-1);
        assertEquals("1>7", name(history.current()));
        assertTrue(playback.paused());
        assertTrue(playback.reviewing(), "The Atlas's shot is re-presented");
        // A live event that arrives during the review waits for it (A.10).
        BoardScene.Combat late = attack(TIMBER_WOLF, ATLAS, ResolvedAttack.Kind.SHOT, true);
        frame(history, List.of(late), scene, round.log, .01);
        assertFalse(playback.waiting().contains(late.result().id()), "Held back during the review");
        assertTrue(history.running());
        history.step(1);
        assertEquals("not fired 2", name(history.current()));
        assertFalse(playback.reviewing(), "An attack that was not fired has its card only");
        frame(history, List.of(), scene, round.log, .01);
        assertTrue(playback.waiting().contains(late.result().id()), "Queued once the review ended");

        history.step(1);
        frame(history, List.of(), scene, round.log, 10);
        assertEquals(List.of("1>7", "not fired 2", "2>7"), names(history.played()),
              "Forward at the live edge: exactly one more action");
        assertEquals("2>7", name(history.current()));
        assertTrue(history.paused());
        frame(history, List.of(), scene, round.log, 10);
        assertEquals(3, history.played().size(), "The playback waits again");

        history.togglePaused();
        for (int frame = 0; frame < 20 && history.running(); frame++) {
            frame(history, List.of(), scene, round.log, 5);
        }
        assertEquals(ROUND_STEPS, names(history.played()));
        assertEquals("roll 7", name(history.current()));
        history.step(1);
        assertEquals("roll 7", name(history.current()), "Stepping stops at the newest step once the playback is done");
        history.step(-1);
        history.step(-1);
        assertEquals("3>7", name(history.current()));
    }

    @Test
    void replayRePresentsRetainedShotsOverTheLiveSceneAndAppliesNothing() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene after = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, after);
        assertSame(after, playback.present(after));
        assertEquals(ROUND_STEPS, names(history.takeShown()), "Each step popped up live");

        history.replay(null);
        assertTrue(history.replaying());
        assertEquals("1>7", name(history.current()), "From the start of this playback");
        BoardScene shown = playback.present(after);
        assertSame(after.tiles(), shown.tiles(), "Review keeps the live tiles");
        assertEquals(KING_CRAB, presented(shown, 7), "Before its impact the shot shows its target as captured");
        assertTrue(history.takeShown().isEmpty(), "Its pop-up waits for the impact");
        List<String> seen = new ArrayList<>();
        List<String> popped = new ArrayList<>();
        for (int frame = 0; frame < 1000 && history.replaying(); frame++) {
            frame(history, List.of(), after, round.log, .05);
            assertFalse(playback.busy(), "A replay never feeds the live queue");
            popped.addAll(names(history.takeShown()));
            if (seen.isEmpty() || !seen.getLast().equals(name(history.current()))) {
                seen.add(name(history.current()));
            }
        }
        assertEquals(ROUND_STEPS, seen, "Every presented step once, in order");
        assertEquals(ROUND_STEPS, popped, "Each pops up once more");
        assertSame(after, playback.present(after), "Nothing the replay showed outlasts it");
        assertEquals(ROUND_STEPS, names(history.played()), "No step was added or applied again");

        // The PSR review step: its card and pop-up only, so a live event is not held for it.
        GpuPlaybackHistory.Step roll = history.current();
        history.takeShown();
        history.reviewTo(roll);
        assertNull(history.current(), "Clicking the current card again collapses it");
        history.reviewTo(roll);
        assertEquals("roll 7", name(history.current()));
        assertFalse(playback.reviewing(), "A piloting roll has no shot to re-present");
        assertEquals(List.of(roll), history.takeShown(), "Its pop-up shows");
        frame(history, List.of(attack(TIMBER_WOLF, ATLAS, ResolvedAttack.Kind.SHOT, true)), after, round.log, .01);
        assertTrue(playback.busy(), "Nothing waits for a card-only step");
    }

    @Test
    void reportReplayPlaysOnlyTheSelectedShotAndCanRestartItWithoutCollapsingTheCard() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene after = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, after);
        history.takeShown();
        GpuPlaybackHistory.Step selected = history.played().getFirst();

        history.replayStep(selected);
        frame(history, List.of(), after, round.log,
              new UnitAttack(round.atlas).contactSeconds / (2 * UnitMotion.Speed.NORMAL.rate));
        assertTrue(playback.reviewing());
        assertTrue(playback.attack().seconds > 0);
        assertTrue(history.takeShown().isEmpty(), "The selected shot has not landed yet");
        history.replayStep(selected);
        assertSame(selected, history.current(), "Replay does not collapse an already selected card");
        assertEquals(0, playback.attack().seconds, "Replay restarts the selected shot");
        assertFalse(history.replaying(), "A report action never starts the sequence transport");

        for (int frame = 0; frame < 100 && playback.reviewing(); frame++) {
            frame(history, List.of(), after, round.log, .05);
        }
        assertFalse(playback.reviewing(), "The one retained shot completes");
        frame(history, List.of(), after, round.log, 10);
        assertSame(selected, history.current(), "Elapsed dwell time cannot advance to the next report");
        assertEquals(List.of(selected), history.takeShown(), "Only the selected event pops up");
        assertSame(after, playback.present(after), "Replay changes no live unit state");
        assertEquals(ROUND_STEPS, names(history.played()));

        // Explicit transport remains sequential even after a single-event report replay.
        history.togglePaused();
        assertTrue(history.replaying());
        frame(history, List.of(), after, round.log, 10);
        assertEquals("not fired 2", name(history.current()), "Play continues through the report");
        history.replay(null);
        assertEquals("1>7", name(history.current()), "Dock Replay still starts the full sequence");
    }

    @Test
    void reportReplayOfCardOnlyEventsStopsTheSequenceAndKeepsTheSelectedCard() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene after = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, after);
        // Put the PSR before the attacks, so an unwanted sequence replay would visibly move past it.
        List<GpuReportLog.Entry> entries = new ArrayList<>(round.log.entries());
        entries.addFirst(entries.removeLast());
        GpuReportLog.Snapshot reports = log(entries, round.log.combat(), round.log.psr());
        frame(history, List.of(), after, reports, 0);
        GpuPlaybackHistory.Step roll = history.played().getFirst();
        assertEquals("roll 7", name(roll));
        GpuPlaybackHistory.Step notFired = history.played().stream()
              .filter(step -> step.notFired() != null).findFirst().orElseThrow();
        for (GpuPlaybackHistory.Step selected : List.of(roll, notFired)) {
            history.replay(null);
            assertTrue(history.replaying());
            history.takeShown();
            history.replayStep(selected);
            assertFalse(history.replaying(), "The report action stops an existing full replay");
            assertFalse(playback.reviewing(), "A card-only event invents no animation");
            frame(history, List.of(), after, reports, 10);
            assertSame(selected, history.current(), "Later report events do not play automatically");
            assertEquals(List.of(selected), history.takeShown());
        }
    }

    @Test
    void speedsPaceTheReviewedShotAndTheReplayAndSkipPresentsEverythingAtOnce() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB);
        frame(history, List.of(), scene, round.empty, 0);
        frame(history, round.timeline(), scene, round.log, .01);
        assertEquals(UnitMotion.Speed.NORMAL, history.speed());

        history.skip();
        assertEquals(UnitMotion.Speed.INSTANT, history.speed());
        frame(history, List.of(), scene, round.log, 0);
        assertFalse(playback.busy(), "Skip to results presents every action at once");
        assertEquals(ROUND_STEPS, names(history.played()));
        assertEquals(UnitMotion.Speed.NORMAL, history.speed(), "The chosen speed stays");

        GpuPlaybackHistory.Step shot = history.played().getFirst();
        GpuPlaybackHistory.Step notFired = history.played().get(1);
        float duration = new UnitAttack(round.atlas).duration;
        for (UnitMotion.Speed speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.DOUBLE,
              UnitMotion.Speed.QUADRUPLE)) {
            history.speed(speed);
            review(history, shot);
            frame(history, List.of(), scene, round.log, duration / speed.rate - .01);
            assertTrue(playback.reviewing(), speed + ": the shot still plays");
            frame(history, List.of(), scene, round.log, .02);
            assertFalse(playback.reviewing(), speed + ": the shot played at that speed");

            // A card-only step shows for 0.9 s at 1x.
            history.replay(notFired);
            double dwell = GpuPlaybackHistory.STEP_SECONDS * UnitMotion.Speed.NORMAL.rate / speed.rate;
            frame(history, List.of(), scene, round.log, dwell - .01);
            assertEquals("not fired 2", name(history.current()), speed.toString());
            frame(history, List.of(), scene, round.log, .02);
            assertEquals("2>7", name(history.current()), speed + ": the next step after " + dwell + " s");
            history.togglePaused();
            assertFalse(history.replaying(), "Play/pause stops a replay");
        }
        // Instant is a speed of its own: a replay steps every frame.
        history.speed(UnitMotion.Speed.INSTANT);
        assertEquals(UnitMotion.Speed.INSTANT, history.speed());
        history.replay(notFired);
        frame(history, List.of(), scene, round.log, .001);
        assertEquals("2>7", name(history.current()), "a replay steps every frame at Instant");
    }

    @Test
    void concealmentTruncatesRetainedShotsAtOnceAndLiveEventsPlayAfterTheReview() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene after = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, after);
        GpuPlaybackHistory.Step atKingCrab = history.played().getFirst();
        GpuPlaybackHistory.Step atTimberWolf = history.played().get(4);

        history.replay(null);
        assertTrue(playback.reviewing(), "The Atlas's shot at the King Crab is re-presented");
        frame(history, List.of(attack(TIMBER_WOLF, ATLAS, ResolvedAttack.Kind.SHOT, true)), after, round.log, .01);
        assertFalse(playback.busy(), "A live event waits for the replay");
        BoardScene hidden = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF);
        frame(history, List.of(new BoardScene.Concealed(7, 0)), hidden, round.log, .01);
        assertFalse(playback.reviewing(), "The concealment reaches the playback at once and ends the review");
        assertTrue(playback.present(hidden).units().stream().noneMatch(unit -> unit.id() == 7),
              "No captured appearance restores the hidden unit");
        history.togglePaused();
        assertFalse(history.replaying());
        review(history, atKingCrab);
        assertFalse(playback.reviewing(), "A shot that shows the hidden unit is no longer retained");
        review(history, atTimberWolf);
        assertTrue(playback.reviewing(), "The other shots stay");

        // Events of the live playback wait for a review and play after it, in their order.
        BoardScene.Combat first = attack(TIMBER_WOLF, ATLAS, ResolvedAttack.Kind.SHOT, true);
        BoardScene.Combat next = attack(WARHAMMER, TIMBER_WOLF, ResolvedAttack.Kind.KICK, true);
        frame(history, List.of(first), hidden, round.log, .01);
        frame(history, List.of(next), hidden, round.log, .01);
        assertFalse(playback.busy());
        for (int frame = 0; frame < 100 && playback.reviewing(); frame++) {
            frame(history, List.of(), hidden, round.log, .05);
        }
        frame(history, List.of(), hidden, round.log, 0);
        assertSame(first, playback.attack().event, "Released once the shot was re-presented");
        assertTrue(playback.waiting().contains(next.result().id()));

        frame(history, List.of(new BoardScene.Concealed(ATLAS.id(), 0)), scene(WARHAMMER, MARAUDER, TIMBER_WOLF),
              round.log, 0);
        review(history, atTimberWolf);
        assertFalse(playback.reviewing(), "Nor is a shot the hidden unit fired");
    }

    /**
     * While live events wait for a review, the board's live scene is still the one before them, so their results do
     * not show before they play; once they play the newest scene is live again. A concealment meanwhile shows the
     * newest scene at once, which hides the unit's identity.
     */
    @Test
    void theLiveSceneKeepsTheStateBeforeTheEventsAReviewHoldsBack() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene before = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, before);
        assertSame(before, history.live(before), "Nothing is held");

        review(history, history.played().getFirst());
        BoardScene.Unit walked = unit(TIMBER_WOLF.id(), 9);
        BoardScene arrived = scene(ATLAS, WARHAMMER, MARAUDER, walked, KING_CRAB_HIT);
        BoardScene.Movement move = new BoardScene.Movement(TIMBER_WOLF.id(), 0,
              List.of(TIMBER_WOLF.location(), walked.location()), EntityMovementType.MOVE_WALK, 0, 4, walked);
        frame(history, List.of(move), arrived, round.log, .01);
        assertEquals(before.units(), history.live(arrived).units(), "The Timber Wolf stays where it was");
        assertSame(before.tiles(), history.live(arrived).tiles());

        for (int frame = 0; frame < 100 && playback.reviewing(); frame++) {
            frame(history, List.of(), arrived, round.log, .05);
        }
        frame(history, List.of(), arrived, round.log, 0);
        assertSame(arrived, history.live(arrived), "Released after the review");
        assertSame(move, playback.movement(), "and played");

        for (int frame = 0; frame < 100 && history.running(); frame++) {
            frame(history, List.of(), arrived, round.log, 1);
        }
        review(history, history.played().getFirst());
        frame(history, List.of(attack(WARHAMMER, ATLAS, ResolvedAttack.Kind.SHOT, true)), arrived, round.log, .01);
        BoardScene hidden = scene(ATLAS, WARHAMMER, MARAUDER, walked);
        frame(history, List.of(new BoardScene.Concealed(KING_CRAB.id(), 0),
              attack(ATLAS, WARHAMMER, ResolvedAttack.Kind.SHOT, false)), hidden, round.log, .01);
        assertTrue(history.running(), "The attack after the concealment still waits");
        assertTrue(history.live(hidden).units().stream().noneMatch(unit -> unit.id() == KING_CRAB.id()),
              "No earlier scene restores the hidden unit");
    }

    /** A card collapsed during a replay leaves no step under the cursor, which ends the replay (null-cursor guard). */
    @Test
    void collapsingTheCurrentCardDuringAReplayEndsTheReplay() {
        Round round = new Round();
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene after = scene(ATLAS, WARHAMMER, MARAUDER, TIMBER_WOLF, KING_CRAB_HIT);
        play(history, round, after);
        history.replay(null);
        history.reviewTo(history.current());
        assertNull(history.current(), "Clicked again, the current card collapses");
        assertTrue(history.replaying());
        for (int frame = 0; frame < 100 && history.replaying(); frame++) {
            frame(history, List.of(), after, round.log, .1);
        }
        assertFalse(history.replaying(), "The replay ends with no step under the cursor");
        assertNull(history.current());
    }

    @Test
    void theHistoryKeepsAtMostTheQueueLimitOfShotsAndANewRoundStartsEmpty() {
        List<BoardScene.Animation> timeline = new ArrayList<>();
        List<GpuReportLog.CombatEvent> events = new ArrayList<>();
        for (int index = 0; index <= UnitPlayback.MAX_PENDING_EVENTS; index++) {
            BoardScene.Combat shot = attack(index % 2 == 0 ? ATLAS : WARHAMMER, KING_CRAB, ResolvedAttack.Kind.SHOT,
                  true);
            timeline.add(shot);
            events.add(event(shot));
        }
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(ATLAS, WARHAMMER, KING_CRAB);
        GpuReportLog.Snapshot log = log(List.of(), events, List.of());
        frame(history, List.of(), scene, log(List.of(), List.of(), List.of()), 0);
        frame(history, timeline, scene, log, 0);
        history.skip();
        frame(history, List.of(), scene, log, 0);
        assertEquals(UnitPlayback.MAX_PENDING_EVENTS + 1, history.played().size());

        review(history, history.played().getFirst());
        assertFalse(playback.reviewing(), "The oldest shot is no longer retained");
        review(history, history.played().get(1));
        assertTrue(playback.reviewing());

        GpuReportLog.Snapshot nextRound = new GpuReportLog.Snapshot(4, GamePhase.INITIATIVE, List.of(), Map.of(),
              List.of(), List.of(), List.of(), List.of());
        frame(history, List.of(), scene, nextRound, 0);
        assertTrue(history.played().isEmpty());
        assertNull(history.current());
        assertFalse(playback.reviewing(), "A new round ends the review");
    }

    /** One frame of the board view: the history takes the frame's events and log, then advances. */
    private static void frame(GpuPlaybackHistory history, List<BoardScene.Animation> events, BoardScene scene,
          GpuReportLog.Snapshot log, double seconds) {
        history.accept(events, scene, log, NO_TRANSPORTS);
        history.advance(seconds, ignored -> true);
    }

    /** The round from its start until the live playback is done, the board then showing {@code after}. */
    private static void play(GpuPlaybackHistory history, Round round, BoardScene after) {
        frame(history, List.of(), after, round.empty, 0);
        frame(history, round.timeline(), after, round.log, 0);
        for (int frame = 0; frame < 20 && history.running(); frame++) {
            frame(history, List.of(), after, round.log, 5);
        }
        assertEquals(ROUND_STEPS, names(history.played()));
    }

    /** Reviews {@code step} from a card, collapsing it first when it is already the current one. */
    private static void review(GpuPlaybackHistory history, GpuPlaybackHistory.Step step) {
        if (step.equals(history.current())) {
            history.reviewTo(step);
        }
        history.reviewTo(step);
    }

    private static BoardScene.Unit presented(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    private static List<String> names(List<GpuPlaybackHistory.Step> steps) {
        return steps.stream().map(GpuPlaybackHistoryTest::name).toList();
    }

    private static String name(GpuPlaybackHistory.Step step) {
        if (step == null) {
            return "none";
        }
        if (step.attack() != null) {
            return step.attack().attackerId() + ">" + step.attack().targetId();
        }
        return step.notFired() != null ? "not fired " + step.notFired().attackerId() : "roll " + step.roll().entityId();
    }

    private static GpuReportLog.Snapshot log(List<GpuReportLog.Entry> entries, List<GpuReportLog.CombatEvent> combat,
          List<GpuReportLog.PsrItem> psr) {
        return new GpuReportLog.Snapshot(3, GamePhase.FIRING_REPORT, entries, Map.of(), combat, psr, List.of(),
              List.of());
    }

    /** The combat event the report log records for a shot. */
    private static GpuReportLog.CombatEvent event(BoardScene.Combat combat) {
        ResolvedAttack result = combat.result();
        return new GpuReportLog.CombatEvent(result.id(), 3, GamePhase.FIRING, result.kind(), combat.attacker().id(),
              combat.target().id(), "Medium Laser", "LA", result.hit(), result.hit() ? 5 : 0, List.of(), null, 0);
    }

    /** The report entry linked to a shot. */
    private static GpuReportLog.Entry entry(BoardScene.Combat combat) {
        return entry(GpuReportLog.Kind.WEAPON, combat.attacker().id(), combat.target().id(), combat.result().id(),
              List.of(), null);
    }

    private static GpuReportLog.Entry entry(GpuReportLog.Kind kind, int attacker, int target, UUID attack,
          List<Long> psr, String notFired) {
        return new GpuReportLog.Entry(3, "Ranged Attack Phase", GamePhase.FIRING, "", kind + " " + attacker + " "
              + target, List.of(), "", List.of(), kind, attacker, target, attack, psr, false, false, null, null,
              notFired, null, null, null, null, false, false);
    }
}
