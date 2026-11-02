/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The round's playback history on the render thread (rebuild plan A.10; J4, J7, J8, J10, K8): the reviewable steps of
 * the round's log in report order, the shots the playback presented, the review cursor and the replay, the speed, and
 * the live events held back while a review runs. It feeds the playback; a review re-presents a retained shot and never
 * applies an event, so damage, heat and ammunition stay as the client reports them.
 */
final class GpuPlaybackHistory {
    /** Replay shows each step at least this long at 1x (replay: 900 / speed ms). */
    static final double STEP_SECONDS = .9;

    /**
     * One reviewable step (J4, J10): a weapon or physical attack with its event, an attack MegaMek reports as not fired
     * (its log entry; MegaMek sends no event for it, so review shows its card only), or a piloting roll. One part is
     * set.
     */
    record Step(GpuReportLog.CombatEvent attack, GpuReportLog.Entry notFired, GpuReportLog.PsrItem roll) {
        /** The phase whose report section holds the step. */
        GamePhase phase() {
            return attack != null ? attack.phase() : notFired != null ? notFired.gamePhase() : roll.phase();
        }

        /** The unit that acts: the attacker, or the unit making the roll. */
        int actorId() {
            return attack != null ? attack.attackerId() : notFired != null ? notFired.attackerId() : roll.entityId();
        }

        /** The unit the step is about: an attack's target unit, else its attacker; the unit making the roll. */
        int unitId() {
            int target = attack != null ? attack.targetId() : notFired != null ? notFired.targetId() : Entity.NONE;
            return target != Entity.NONE ? target : actorId();
        }

        /** The step's key: the attack's id, the roll's id or the entry of the attack not fired. */
        private Object key() {
            return attack != null ? attack.id() : roll != null ? (Object) roll.id() : notFired;
        }
    }

    private final UnitPlayback playback;
    /** The round's shots by their id, oldest first (A.10: capped, cleared on round and board change). */
    private final Map<UUID, BoardScene.Combat> shots = new LinkedHashMap<>();
    /** Live events held back while a review runs, in arrival order (A.10). */
    private final List<BoardScene.Animation> held = new ArrayList<>();
    /** Steps presented since the board's pop-ups last took them. */
    private final List<Step> shown = new ArrayList<>();
    private GpuReportLog.Snapshot log = GpuReportLog.Snapshot.EMPTY;
    private List<Step> steps = List.of();
    private Map<Object, Step> keyed = Map.of();
    private Map<Step, Integer> numbers = Map.of();
    private List<Step> played = List.of();
    private Set<Step> playedSet = Set.of();
    private List<GpuReportLog.CombatEvent> playedAttacks = List.of();
    private Step cursor;
    /** The step whose re-presented shot has not landed yet; it pops up when it does. */
    private Step landing;
    private boolean replaying;
    /** Real seconds, at the replay's speed, that the replayed step has shown. */
    private double dwell;
    /** The steps seen first after a reset were presented before; they move nothing and pop nothing. */
    private boolean primed;
    private UnitMotion.Speed speed = UnitMotion.Speed.NORMAL;
    private boolean skipping;
    /** The newest scene whose events reached the playback: the board shows it while live events wait (A.10). */
    private BoardScene live;

    /** The history of {@code playback}, which it feeds and reviews; the board view advances it through this class. */
    GpuPlaybackHistory(UnitPlayback playback) {
        this.playback = playback;
    }

    /**
     * One frame (GL, before {@link #advance}): reads the steps of the log, retains the frame's shots, truncates the
     * shots of units it conceals at once, and passes its events to the playback, or holds them back while a review
     * runs. A concealment always reaches the playback at once, which then catches up as it always does (A.10).
     */
    void accept(List<BoardScene.Animation> timeline, BoardScene scene, GpuReportLog.Snapshot reports,
          Predicate<BoardScene.Unit> transports) {
        read(reports);
        int concealed = -1;
        for (int index = 0; index < timeline.size(); index++) {
            if (timeline.get(index) instanceof BoardScene.Concealed hidden && hidden.boardId() == scene.boardId()) {
                shots.values().removeIf(shot -> shot.attacker().id() == hidden.entityId()
                      || shot.target() != null && shot.target().id() == hidden.entityId());
                concealed = index;
            } else if (timeline.get(index) instanceof BoardScene.Combat combat
                  && combat.result().kind() != ResolvedAttack.Kind.DEATH) {
                shots.putIfAbsent(combat.result().id(), combat);
                if (shots.size() > UnitPlayback.MAX_PENDING_EVENTS) {
                    shots.remove(shots.keySet().iterator().next());
                }
            }
        }
        if (holding() && concealed < 0) {
            held.addAll(timeline);
        } else if (holding()) {
            // The events before the concealment are superseded by the playback's catch-up; those after it wait.
            playback.accept(release(timeline.subList(0, concealed + 1)), scene, transports);
            held.addAll(timeline.subList(concealed + 1, timeline.size()));
        } else {
            playback.accept(release(timeline), scene, transports);
        }
        if (held.size() > UnitPlayback.MAX_PENDING_EVENTS) {
            // A large live backlog ends the review, as the playback snaps to one.
            replaying = false;
            playback.review(null);
            playback.accept(release(List.of()), scene, transports);
        }
        if (held.isEmpty() || concealed >= 0) {
            // After a concealment the playback catches up to the newest scene, which hides that unit's identity.
            live = scene;
        }
    }

    /**
     * The live scene of this frame, which the playback presents (GL, after {@link #accept}): the frame's
     * {@code scene}, but while live events wait for a review to end, the units, tiles and marks of the newest scene
     * before them under this frame's controls, so nothing of those events shows before they play (A.10).
     */
    BoardScene live(BoardScene scene) {
        return held.isEmpty() ? scene : scene.duringPlayback(live, false);
    }

    /** The held events followed by {@code events}; nothing stays held. */
    private List<BoardScene.Animation> release(List<BoardScene.Animation> events) {
        List<BoardScene.Animation> released = new ArrayList<>(held);
        released.addAll(events);
        held.clear();
        return released;
    }

    /**
     * Advances the playback, or the shot a review re-presents, at the history's speed, and the replay by
     * {@code seconds} of real time, then updates the presented steps (GL, after {@link #accept}).
     */
    void advance(double seconds, Predicate<UnitPlayback> cameraReady) {
        UnitAttack reviewed = playback.reviewing() ? playback.attack() : null;
        playback.advance(seconds, speed(), cameraReady);
        if (landing != null && (reviewed == null || reviewed.seconds >= reviewed.contactSeconds)) {
            // A re-presented shot pops up as it lands, like a live one; a review cut short before that does not.
            if (reviewed != null) {
                show(List.of(landing));
            }
            landing = null;
        }
        skipping &= playback.busy();
        if (replaying) {
            // At Instant a replay steps every frame.
            dwell += speed == UnitMotion.Speed.INSTANT ? STEP_SECONDS
                  : seconds * speed.rate / UnitMotion.Speed.NORMAL.rate;
            if (dwell >= STEP_SECONDS && !playback.reviewing()) {
                // The history's lists take no null: a card collapsed during the replay ends it here.
                int next = cursor == null ? 0 : played.indexOf(cursor) + 1;
                if (next > 0 && next < played.size()) {
                    present(played.get(next));
                } else {
                    replaying = false;
                }
            }
        }
        updatePlayed();
    }

    /** A new board: nothing retained is valid. The board view clears its playback with it. */
    void clear() {
        shots.clear();
        held.clear();
        shown.clear();
        cursor = null;
        landing = null;
        replaying = false;
        primed = false;
        live = null;
    }

    /**
     * PLAYBACK_TOGGLE and the dock's play/pause (J7, r2 6.3): stops a replay; pauses or resumes running playback; once
     * the playback is done, replays from the cursor, or from the start of the current playback.
     */
    void togglePaused() {
        if (replaying) {
            replaying = false;
        } else if (running()) {
            playback.togglePaused();
        } else {
            replay(cursor);
        }
    }

    /**
     * PLAYBACK_PREV (-1) and PLAYBACK_NEXT (+1): stops a replay and moves the cursor one presented step, pausing
     * running playback; forward at the live edge of running playback, the playback plays exactly one more action
     * instead (J7).
     */
    void step(int direction) {
        replaying = false;
        // The history's lists take no null: without a cursor nothing is at a step.
        int at = cursor == null ? -1 : played.indexOf(cursor);
        if (direction > 0 && running() && (at < 0 || at == played.size() - 1)) {
            pause();
            playback.stepOnce();
        } else if (!played.isEmpty()) {
            if (running()) {
                pause();
            }
            int next = at < 0 ? (direction > 0 ? 0 : played.size() - 1)
                  : Math.clamp(at + (long) direction, 0, played.size() - 1);
            present(played.get(next));
        }
    }

    /** A log card's click (K8): stops a replay, pauses running playback and moves the cursor there; again collapses. */
    void reviewTo(Step step) {
        if (step.equals(cursor)) {
            cursor = null;
        } else {
            replayStep(step);
        }
    }

    /** A report's Replay: presents only this step, even when already selected, and leaves the cursor on it. */
    void replayStep(Step step) {
        if (playedSet.contains(step)) {
            replaying = false;
            if (running()) {
                pause();
            }
            present(step);
        }
    }

    /**
     * Replays the presented steps from {@code from} to the newest, pausing running playback (J8). The dock's Replay
     * passes null: from the start of the current playback, or of the round in its review (END, END_REPORT).
     */
    void replay(Step from) {
        int start = from != null ? played.indexOf(from) : first();
        if (start >= 0) {
            if (running()) {
                pause();
            }
            replaying = true;
            present(played.get(start));
        }
    }

    /** The current playback's first presented step: the round's in the end phase, else the newest step's phase's. */
    private int first() {
        if (played.isEmpty() || log.phase().isEnd() || log.phase().isEndReport()) {
            return played.isEmpty() ? -1 : 0;
        }
        GamePhase phase = played.getLast().phase();
        for (int index = 0; index < played.size(); index++) {
            if (played.get(index).phase() == phase) {
                return index;
            }
        }
        return 0;
    }

    /** "Skip to results" (J5): stops a replay and a review; the playback presents everything left at once. */
    void skip() {
        replaying = false;
        playback.review(null);
        skipping = true;
    }

    /** The speed the playback and the replay run at; Instant while skipping. */
    UnitMotion.Speed speed() {
        return skipping ? UnitMotion.Speed.INSTANT : speed;
    }

    /**
     * The phase header's speeds, 0.5x to 4x and Instant; "Skip to results" is an
     * Instant for the current playback only ({@link #skip}).
     */
    void speed(UnitMotion.Speed value) {
        speed = value;
    }

    /** All the round's reviewable steps in report order. */
    List<Step> steps() {
        return steps;
    }

    /** The steps presented so far, in report order; the dock counts them as reviewable (J4). */
    List<Step> played() {
        return played;
    }

    boolean played(Step step) {
        return playedSet.contains(step);
    }

    /** The attacks of the presented steps; the forces list's review lines count these (C.6). */
    List<GpuReportLog.CombatEvent> playedAttacks() {
        return playedAttacks;
    }

    /** A step's number in the round (1 for the first), as its log card and pop-up show it. */
    int number(Step step) {
        return numbers.getOrDefault(step, 0);
    }

    /** The steps of a log entry: its attack, its attack that was not fired, or its piloting rolls. */
    List<Step> steps(GpuReportLog.Entry entry) {
        List<Step> result = new ArrayList<>();
        for (Object key : keys(entry)) {
            Step step = keyed.get(key);
            if (step != null) {
                result.add(step);
            }
        }
        return result;
    }

    private static List<Object> keys(GpuReportLog.Entry entry) {
        List<Object> keys = new ArrayList<>(entry.psr());
        keys.add(entry);
        if (entry.attack() != null) {
            keys.add(entry.attack());
        }
        return keys;
    }

    /** The step under the review cursor, or null. */
    Step current() {
        return cursor;
    }

    boolean replaying() {
        return replaying;
    }

    boolean paused() {
        return playback.paused();
    }

    /** Live playback has actions left: the playback is busy, or live events wait for a review to end. */
    boolean running() {
        return playback.busy() || !held.isEmpty();
    }

    /** Board effects freeze while the playback is paused or a review runs (overlay.js frozenFx). */
    boolean frozen() {
        return playback.paused() || holding();
    }

    /**
     * The steps presented since the last call, live or in review, for the board's pop-ups (J10): an attack as its shot
     * lands, a step without a shot at once.
     */
    List<Step> takeShown() {
        List<Step> taken = List.copyOf(shown);
        shown.clear();
        return taken;
    }

    private boolean holding() {
        return replaying || playback.reviewing();
    }

    private void pause() {
        if (!playback.paused()) {
            playback.togglePaused();
        }
    }

    /**
     * Moves the cursor to a presented step and re-presents its retained shot, which pops up as it lands; a step
     * without one pops up at once and ends a review.
     */
    private void present(Step step) {
        cursor = step;
        dwell = 0;
        BoardScene.Combat shot = step.attack() == null ? null : shots.get(step.attack().id());
        playback.review(shot);
        landing = shot == null ? null : step;
        if (shot == null) {
            show(List.of(step));
        }
    }

    private void show(List<Step> presented) {
        shown.addAll(presented);
        if (shown.size() > UnitPlayback.MAX_PENDING_EVENTS) {
            shown.subList(0, shown.size() - UnitPlayback.MAX_PENDING_EVENTS).clear();
        }
    }

    /** Takes a new log's steps; a new round starts an empty history (A.10). */
    private void read(GpuReportLog.Snapshot reports) {
        if (reports == log) {
            return;
        }
        if (reports.round() != log.round()) {
            shots.clear();
            shown.clear();
            cursor = null;
            replaying = false;
            playback.review(null);
            primed = false;
        }
        log = reports;
        steps = steps(reports);
        Map<Object, Step> byKey = new HashMap<>();
        Map<Step, Integer> byStep = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            byKey.put(steps.get(index).key(), steps.get(index));
            byStep.put(steps.get(index), index + 1);
        }
        keyed = byKey;
        numbers = byStep;
        if (cursor != null && !numbers.containsKey(cursor)) {
            cursor = null;
            replaying = false;
        }
    }

    /**
     * The round's reviewable steps in report order (E8b): an attack where its linked entry is, after the attacks
     * before it that no entry links, and an attack not fired and a piloting roll where its entry is.
     */
    private static List<Step> steps(GpuReportLog.Snapshot log) {
        List<GpuReportLog.CombatEvent> combat = log.combat();
        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < combat.size(); index++) {
            order.put(combat.get(index).id(), index);
        }
        Map<Long, GpuReportLog.PsrItem> rolls = new LinkedHashMap<>();
        log.psr().forEach(roll -> rolls.put(roll.id(), roll));
        Set<GpuReportLog.Entry> notFired = new HashSet<>(log.notFired());
        int round = Math.max(1, log.round());
        List<Step> steps = new ArrayList<>();
        int next = 0;
        for (GpuReportLog.Entry entry : log.entries()) {
            Integer linked = entry.round() == round && entry.attack() != null ? order.get(entry.attack()) : null;
            for (; linked != null && next <= linked; next++) {
                steps.add(new Step(combat.get(next), null, null));
            }
            if (notFired.contains(entry)) {
                steps.add(new Step(null, entry, null));
            }
            for (long id : entry.round() == round ? entry.psr() : List.<Long>of()) {
                GpuReportLog.PsrItem roll = rolls.remove(id);
                if (roll != null) {
                    steps.add(new Step(null, null, roll));
                }
            }
        }
        for (; next < combat.size(); next++) {
            steps.add(new Step(combat.get(next), null, null));
        }
        rolls.values().forEach(roll -> steps.add(new Step(null, null, roll)));
        return List.copyOf(steps);
    }

    /**
     * The presented steps: each attack the playback no longer waits for (its shot landed), and each card-only step
     * that no waiting attack precedes. Newly presented steps move the cursor and pop up on the board, unless a review
     * runs.
     */
    private void updatePlayed() {
        Set<UUID> waiting = playback.waiting();
        for (BoardScene.Animation event : held) {
            if (event instanceof BoardScene.Combat combat) {
                waiting.add(combat.result().id());
            }
        }
        List<Step> next = new ArrayList<>();
        boolean blocked = false;
        for (Step step : steps) {
            boolean waits = step.attack() != null ? waiting.contains(step.attack().id()) : blocked;
            blocked |= step.attack() != null && waits;
            if (!waits) {
                next.add(step);
            }
        }
        if (!next.equals(played)) {
            List<Step> fresh = next.stream().filter(step -> !playedSet.contains(step)).toList();
            played = List.copyOf(next);
            playedSet = Set.copyOf(next);
            playedAttacks = played.stream().map(Step::attack).filter(Objects::nonNull).toList();
            if (primed && !fresh.isEmpty() && !holding()) {
                cursor = fresh.getLast();
                show(fresh);
            }
        }
        primed = true;
    }
}
