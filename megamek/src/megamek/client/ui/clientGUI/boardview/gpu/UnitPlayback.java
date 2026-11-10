/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import megamek.common.units.EntityMovementType;

/** One GL-owned event queue for both cameras. The game remains free to receive updates while it plays. */
final class UnitPlayback {
    static final double COMPLETION_HOLD_SECONDS = 1;
    /**
     * How long the playback holds on an action in which a unit died, in place of {@link #COMPLETION_HOLD_SECONDS}, while
     * its pieces settle: with the death's slow motion, the next action begins about two seconds after the explosion.
     */
    static final double DEATH_HOLD_SECONDS = .8;
    static final int MAX_PENDING_EVENTS = 512;
    /** Maximum spread between a unit's weapon launches, in shared animation seconds. */
    static final float VOLLEY_JITTER_SECONDS = .12f;
    /** A brief contact edge lets the camera settle before the scheduled strike and its final terrain appear together. */
    static final float DETONATION_CONTACT_SECONDS = .05f;
    final Map<Integer, UnitMotion> motions = new HashMap<>();
    /** GL-owned visual preview; NaN keeps each event's captured game gravity. Read only when a jump starts. */
    float gravityOverride = Float.NaN;
    private final ArrayDeque<BoardScene.Animation> pending = new ArrayDeque<>();
    /** The chain of explosions playing: when each went off after the first started, and when the next starts (#chained). */
    private final Map<UUID, Double> chainFlashes = new HashMap<>();
    private double chainAt;
    private final Map<Integer, BoardScene.Waypoint> observed = new HashMap<>();
    private BoardScene.Animation active;
    private UnitAttack attack;
    private final List<UnitAttack> attacks = new ArrayList<>();
    /** Completed/instant shots wait only until the next render consumes their persistent cosmetic impacts. */
    private final Map<UUID, UnitAttack> completedAttacks = new LinkedHashMap<>();
    private final Map<UUID, BoardScene.Detonation> detonations = new LinkedHashMap<>();
    /** The checkpoints shown since the last take that show trees an attack broke (#takeBrokenTrees). */
    private final List<BoardScene.SceneUpdate> brokenTrees = new ArrayList<>();
    private boolean detonationStarted;
    private final UnitVolley volley = new UnitVolley();
    private record Reaction(BoardScene.Movement movement, double start) { }
    private final List<Reaction> reactions = new ArrayList<>();
    private final List<UnitAttack> visibleAttacks = java.util.Collections.unmodifiableList(attacks);
    private double combatSeconds, combatDuration, combatContact;
    /** When the terrain the action changed shows its change: its contact, or a fuel-air round's ignition after it. */
    private double combatTerrainRelease;
    private BoardScene volleyScene;
    private UnitConversion conversion;
    private UnitAttachmentMotion attachment;
    private double attachmentStart;
    private double hold;
    private boolean completed;
    private boolean paused;
    /** stepOnce(): the paused queue plays until an action other than {@link #stepFrom} has completed. */
    private boolean stepping;
    private BoardScene.Animation stepFrom;
    /** A retained attack that a review re-presents over the waiting live queue (A.10), or null. */
    private UnitAttack review;
    /** The shoves that pushed their target off the board as they played live, which a review of them plays again. */
    private final Set<UUID> pushedOffResults = new HashSet<>();
    private List<UnitAttack> reviewed = List.of();
    private Predicate<BoardScene.Unit> transports = ignored -> false;
    private final Consumer<BoardScene.Movement> completeMovement;
    private final java.util.function.BiConsumer<UnitAttack, Boolean> soundCue;
    // GL-owned presentation history only. The game and current selection continue to update on Swing.
    private BoardScene settledScene;
    private BoardScene overlayInput;
    private BoardScene overlayState;
    private BoardScene presentationScene;
    private boolean overlayHideMovement;
    private boolean checkpoints;

    UnitPlayback() {
        this(ignored -> { });
    }

    UnitPlayback(Consumer<BoardScene.Movement> completeMovement) {
        this(completeMovement, (attack, contact) -> { });
    }

    /** Optional audio consumes launch/contact edges from this clock, never from drawing or attack declarations. */
    UnitPlayback(Consumer<BoardScene.Movement> completeMovement, java.util.function.BiConsumer<UnitAttack, Boolean> soundCue) {
        this.completeMovement = completeMovement;
        this.soundCue = soundCue;
    }

    void accept(List<BoardScene.Animation> events, BoardScene scene, Predicate<BoardScene.Unit> transports) {
        this.transports = transports;
        int start = 0;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof BoardScene.Concealed hidden && hidden.boardId() == scene.boardId()) {
                // Catch up to the authorized scene. Historical snapshots must not restore a hidden identity.
                boolean wasPaused = paused;
                clear();
                paused = wasPaused;
                settledScene = scene;
                start = index + 1;
            }
        }
        Set<Integer> visible = scene.units().stream().filter(unit -> !unit.sensorContact()).map(BoardScene.Unit::id)
              .collect(Collectors.toSet());
        observed.keySet().retainAll(visible);
        for (var event : events.subList(start, events.size())) {
            if (event.boardId() == scene.boardId()
                  && (!(event instanceof BoardScene.Movement move)
                        || (move.unit() != null || visible.contains(move.entityId())) && move.path().size() > 1)) {
                checkpoints |= event instanceof BoardScene.SceneUpdate;
                enqueue(event);
            }
        }
        for (var unit : scene.units()) {
            if (unit.sensorContact() || unit.location().aeroState() == null) {
                continue;
            }
            var previous = observed.put(unit.id(), unit.location());
            if (!checkpoints && UnitMotion.changesGear(previous, unit.location()) && events.stream().noneMatch(event ->
                  event instanceof BoardScene.Movement movement && movement.entityId() == unit.id())) {
                enqueue(new BoardScene.Movement(unit.id(), scene.boardId(), List.of(previous, unit.location()),
                      EntityMovementType.MOVE_SAFE_THRUST, 0, 0, unit));
            }
        }
        collectVolley();
        applySceneUpdates();
    }

    private void enqueue(BoardScene.Animation event) {
        if (event instanceof BoardScene.SceneUpdate && pending.peekLast() instanceof BoardScene.SceneUpdate) {
            pending.removeLast();
        }
        if (event instanceof BoardScene.TileTransition later && later.changes().isEmpty() && mergeCollapse(later)) { return; }
        if (pending.size() >= MAX_PENDING_EVENTS) {
            // A client catching up on a large backlog snaps to the game, instead of retaining unbounded snapshots.
            finish();
        }
        pending.addLast(event);
    }

    /**
     * One building's hexes come down in one act: its remaining hexes, which the server brings down after the first one's
     * occupants have fallen (a capture can fall between them), join the collapse still waiting with only checkpoints and
     * falls after it. The checkpoints and falls between them follow the joined collapse, applying and falling as it starts.
     */
    private boolean mergeCollapse(BoardScene.TileTransition later) {
        List<BoardScene.Animation> queued = new ArrayList<>(pending);
        for (int index = queued.size() - 1; index >= 0; index--) {
            var event = queued.get(index);
            if (event instanceof BoardScene.TileTransition earlier && earlier.changes().isEmpty()) {
                List<BoardScene.TileTransition.Collapse> collapses = new ArrayList<>(earlier.collapses());
                collapses.addAll(later.collapses());
                queued.set(index, new BoardScene.TileTransition(earlier.boardId(), List.of(), collapses));
                pending.clear();
                pending.addAll(queued);
                return true;
            }
            if (!(event instanceof BoardScene.SceneUpdate || event instanceof BoardScene.Movement move && move.forced())) { return false; }
        }
        return false;
    }

    /**
     * Whether structures are coming down and the next act waiting brings more down: a collapse that began while another
     * played (#mergeCollapse joins only those still waiting) follows it at once, as one brought down in the editor
     * right after another.
     */
    private boolean collapsesOn() {
        return active instanceof BoardScene.TileTransition falling && falling.changes().isEmpty()
              && pending.peekFirst() instanceof BoardScene.TileTransition next && next.changes().isEmpty();
    }

    void advance(double seconds, UnitMotion.Speed speed) {
        advance(seconds, speed, ignored -> true);
    }

    /** The view may briefly hold an action while its camera frames the participants or complete movement route. */
    void advance(double seconds, UnitMotion.Speed speed, Predicate<UnitPlayback> cameraReady) {
        if (speed == UnitMotion.Speed.INSTANT) {
            review(null);
            paused = false;
            finish();
            return;
        }
        if (review != null) {
            // A review plays alone: the live queue waits, paused or not, so the two never interleave (A.10).
            if (cameraReady.test(this)) {
                float previous = review.seconds;
                review.seconds = (float) Math.min(review.duration, review.seconds + Math.max(0, seconds) * speed.rate);
                cue(review, previous);
                if (review.seconds >= review.duration) {
                    review(null);
                }
            }
            return;
        }
        if (paused && !stepping) {
            return;
        }
        double remaining = Math.max(0, seconds);
        motions.values().stream().filter(motion -> !motion.isMoving()).forEach(motion -> motion.advance(seconds, speed.rate));
        while (true) {
            applySceneUpdates();
            if (active == null) {
                active = pending.pollFirst();
                if (active == null) {
                    stepping = false;
                    return;
                }
                completed = false;
                if (active instanceof BoardScene.Movement movement) {
                    start(movement);
                } else if (active instanceof BoardScene.Combat combat) {
                    attack = new UnitAttack(combat);
                    attacks.add(attack);
                    combatSeconds = 0;
                    combatDuration = attack.duration;
                    combatContact = attack.contactSeconds;
                    combatTerrainRelease = terrainRelease();
                    if (attack.shot()) { volley.add(attack, 0); }
                    collectVolley();
                } else if (active instanceof BoardScene.Detonation detonation) {
                    combatSeconds = 0;
                    // One of a chain goes off as it starts, on its cause's front (#chained); any other after a moment.
                    boolean chain = detonation.cause() != null && chainFlashes.containsKey(detonation.cause().id());
                    combatContact = chain ? 0 : DETONATION_CONTACT_SECONDS;
                    combatTerrainRelease = combatContact;
                    combatDuration = chained(detonation, chain);
                    detonationStarted = false;
                } else if (active instanceof BoardScene.Conversion change) {
                    conversion = new UnitConversion(change);
                } else if (active instanceof BoardScene.AttachmentChange change) {
                    attachment = new UnitAttachmentMotion(change);
                    attachmentStart = 0;
                } else if (active instanceof BoardScene.TileTransition transition) {
                    // A scene change runs on the action clock, as a strike does; its checkpoint applies as it starts.
                    combatSeconds = 0;
                    combatContact = 0;
                    combatTerrainRelease = 0;
                    combatDuration = transition.seconds();
                }
            }
            if (!completed) {
                if (!cameraReady.test(this)) { return; }
                // Shown for a frame, its structures are falling: the next ones give way now.
                if (collapsesOn() && combatSeconds > 0) { combatDuration = combatSeconds; }
                UnitMotion motion = active instanceof BoardScene.Movement ? motions.get(active.entityId()) : null;
                double left = active instanceof BoardScene.AttachmentChange ? UnitAttachmentMotion.DURATION - attachment.seconds
                      : conversion != null ? UnitConversion.DURATION_SECONDS - conversion.seconds
                      : motion == null ? combatDuration - combatSeconds : motion.remainingSeconds();
                double step = Math.min(remaining, Math.max(0, left) / speed.rate);
                if (active instanceof BoardScene.AttachmentChange) {
                    attachment.seconds = Math.min(UnitAttachmentMotion.DURATION, attachment.seconds + (float) (step * speed.rate));
                } else if (conversion != null) {
                    conversion.seconds = Math.min(UnitConversion.DURATION_SECONDS, conversion.seconds + (float) (step * speed.rate));
                } else if (motion == null) {
                    double previousCombat = combatSeconds;
                    combatSeconds = Math.min(combatDuration, combatSeconds + step * speed.rate);
                    if (active instanceof BoardScene.Detonation detonation && !beforeImpact()) {
                        startDetonation(detonation);
                    }
                    volley.advance((float) combatSeconds);
                    attacks.forEach(shot -> {
                        float previous = shot.seconds;
                        shot.seconds = Math.min(shot.duration, (float) combatSeconds - shot.delay);
                        cue(shot, previous);
                    });
                    for (var reaction : reactions) {
                        motions.get(reaction.movement().entityId()).advance(
                              Math.max(0, combatSeconds - Math.max(previousCombat, reaction.start())), 1);
                    }
                    applySceneUpdates();
                    if (attachment != null) {
                        attachment.seconds = (float) Math.min(UnitAttachmentMotion.DURATION, combatSeconds - attachmentStart);
                    }
                } else {
                    motion.advance(step, speed.rate);
                }
                remaining = Math.max(0, remaining - step);
                if (motion == null && conversion == null && !(active instanceof BoardScene.AttachmentChange)
                      && combatSeconds + 1e-7 < combatDuration) {
                    if (remaining > 0) { continue; }
                    return;
                }
                if (step * speed.rate + 1e-7 < left) {
                    return;
                }
                completed = true;
                reactions.forEach(reaction -> completeMovement.accept(reaction.movement()));
                if (active instanceof BoardScene.Movement movement) {
                    completeMovement.accept(movement);
                } else if (active instanceof BoardScene.Conversion change) {
                    settleConversion(change);
                }
                applySceneUpdates();
                hold = attacks.stream().anyMatch(UnitAttack::death) ? DEATH_HOLD_SECONDS
                      : collapsesOn() || pending.peekFirst() instanceof BoardScene.AttachmentChange
                      || active instanceof BoardScene.AttachmentChange && pending.peekFirst() instanceof BoardScene.Movement
                      || pending.peekFirst() instanceof BoardScene.Combat next
                            && (next.result().kind() == megamek.common.ResolvedAttack.Kind.BRUSH_OFF
                                  || next.result().kind() == megamek.common.ResolvedAttack.Kind.SHAKE_OFF)
                      // A unit that died in its own movement, as a fall, dies as it lands.
                      || active instanceof BoardScene.Movement move && pending.peekFirst() instanceof BoardScene.Combat death
                            && death.result().kind() == megamek.common.ResolvedAttack.Kind.DEATH && death.entityId() == move.entityId()
                      // An explosion goes off as what set it off ends, the next of a chain as the last one's falls do.
                      || pending.peekFirst() instanceof BoardScene.Detonation next
                            && next.result().kind() == megamek.common.NuclearDetonation.Kind.EXPLOSION
                      // So does one the structure it stood on or in crushed as it came down.
                      || active instanceof BoardScene.TileTransition change && pending.peekFirst() instanceof BoardScene.Combat crushed
                            && crushed.result().kind() == megamek.common.ResolvedAttack.Kind.DEATH
                            && change.collapses(crushed.attacker().location().coords())
                      ? 0 : COMPLETION_HOLD_SECONDS;
                if (stepping && active != stepFrom) {
                    // stepOnce(): one more action has played; the queue waits again.
                    stepping = false;
                    return;
                }
            }
            double pause = Math.min(remaining, hold);
            if (attachment != null) { attachment.seconds += (float) (pause * speed.rate); }
            hold -= pause;
            remaining -= pause;
            if (hold > 1e-9) {
                return;
            }
            active = null;
            attack = null;
            attacks.forEach(this::completeAttack);
            attacks.clear();
            reactions.clear();
            volley.clear();
            volleyScene = null;
            conversion = null;
            attachment = null;
        }
    }

    /** Sounds consume the launch and contact edges of a shot's clock. */
    private void cue(UnitAttack shot, float previous) {
        if (previous < UnitAttack.ANTICIPATION_SECONDS && shot.seconds >= UnitAttack.ANTICIPATION_SECONDS) {
            soundCue.accept(shot, false);
        }
        if (previous < shot.contactSeconds && shot.seconds >= shot.contactSeconds) { soundCue.accept(shot, true); }
    }

    /** The presented attack: a reviewed one while a review runs, else the live one. */
    UnitAttack attack() { return review != null ? review : attack; }

    List<UnitAttack> attacks() { return review != null ? reviewed : visibleAttacks; }

    List<UnitAttack> takeCompletedAttacks() {
        var result = List.copyOf(completedAttacks.values());
        completedAttacks.clear();
        return result;
    }

    /** Contact edges consumed once by effects, including strikes skipped with Instant playback. */
    List<BoardScene.Detonation> takeDetonations() {
        var result = List.copyOf(detonations.values());
        detonations.clear();
        return result;
    }

    /** The checkpoints shown since the last take whose trees an attack broke, as its hit lands (GpuTerrain#breakTrees). */
    List<BoardScene.SceneUpdate> takeBrokenTrees() {
        var result = List.copyOf(brokenTrees);
        brokenTrees.clear();
        return result;
    }

    BoardScene.Detonation detonation() {
        return review == null && active instanceof BoardScene.Detonation event ? event : null;
    }

    private void startDetonation(BoardScene.Detonation event) {
        if (detonationStarted) { return; }
        detonationStarted = true;
        detonations.putIfAbsent(event.result().id(), event);
        if (detonations.size() > MAX_PENDING_EVENTS) { detonations.remove(detonations.keySet().iterator().next()); }
    }

    private void completeAttack(UnitAttack shot) {
        if (!GpuGroundDamage.COMBAT_SCARS || !shot.shot() || shot.defensive()) { return; }
        shot.seconds = shot.duration;
        completedAttacks.putIfAbsent(shot.event.result().id(), shot);
        if (completedAttacks.size() > MAX_PENDING_EVENTS) { completedAttacks.remove(completedAttacks.keySet().iterator().next()); }
    }

    /** The live movement under way; none while a review presents an attack. */
    BoardScene.Movement movement() {
        return review == null && active instanceof BoardScene.Movement move ? move : null;
    }

    /**
     * While paused, plays exactly one more action (A.10 J7) and the queue waits again: an action under way that is not
     * presented yet (an attack before its impact, a movement) is that action; one already presented finishes, then the
     * next one plays to its completion. A unit's volley is one action.
     */
    void stepOnce() {
        if (paused) {
            stepping = true;
            stepFrom = completed || (attack != null || active instanceof BoardScene.Detonation) && !beforeImpact() ? active : null;
        }
    }

    /**
     * Re-presents a retained attack (A.10 J8): its effects play once more while the live queue waits, and before its
     * impact its attacker and target show as captured. It applies nothing, so the presented scene is the live one
     * again afterwards. Null ends a review.
     */
    void review(BoardScene.Combat event) {
        review = event == null ? null : new UnitAttack(event);
        reviewed = review == null ? List.of() : List.of(review);
    }

    boolean reviewing() { return review != null; }

    /**
     * The live attacks not presented yet, by their resolution id: the queued ones and the shots under way before their
     * impact, so whatever follows the playback (log, counter, pop-ups) shows a shot as it lands.
     */
    Set<UUID> waiting() {
        Set<UUID> ids = new HashSet<>();
        for (var event : pending) {
            if (event instanceof BoardScene.Combat combat) {
                ids.add(combat.result().id());
            }
        }
        for (UnitAttack shot : attacks) {
            if (shot.seconds < shot.contactSeconds) {
                ids.add(shot.event.result().id());
            }
        }
        return ids;
    }

    /**
     * Interpolate only a displacement actually present in the post-resolution checkpoint, or for a reviewed shove in
     * the live scene.
     */
    void placeDisplacement(BoardScene.Unit unit, com.badlogic.gdx.math.Vector3 position) {
        if (reactions.stream().anyMatch(reaction -> reaction.movement().entityId() == unit.id())) { return; }
        var shove = attack();
        if (shove == null || !shove.shoves() || !shove.event.result().hit() || shove.seconds + 1e-7 < shove.contactSeconds) {
            return;
        }
        var before = unit.id() == shove.event.entityId() ? shove.event.attacker()
              : shove.event.target() != null && unit.id() == shove.event.target().id() ? shove.event.target() : null;
        if (before == null || before.location().coords().equals(unit.location().coords())) { return; }
        float progress = com.badlogic.gdx.math.MathUtils.clamp((shove.seconds - shove.contactSeconds)
              / shove.recoverySeconds, 0, 1);
        progress *= progress * (3 - 2 * progress);
        var origin = BoardGeometry.center(before.location().coords(), before.location().elevation());
        position.set(origin.lerp(position, progress));
    }

    /**
     * The shove under way, a reviewed one included, that pushed {@code unit} off the board, which poses it going over
     * the edge ({@link UnitAttack#posePushedOff}); null for any other unit.
     */
    UnitAttack pushedOff(BoardScene.Unit unit) {
        for (var shot : attacks()) {
            var target = shot.event.target();
            if (shot.pushedOff() && shot.seconds < shot.duration && target != null && target.id() == unit.id()) {
                return shot;
            }
        }
        return null;
    }

    /**
     * Marks a shove that landed as having pushed its target off the board, when {@code scene} has lost the target and a
     * hex it may have been shoved into is off the board: the first in MegaMek's order ({@link UnitAttack#shoveHexes}),
     * as the ones before it on the board would have taken the target. MegaMek removes a unit pushed off. A target that
     * dies where it stood is not off the board; a review plays again only a push off that played live, not a target the
     * game has removed since for another reason.
     */
    private boolean pushOff(UnitAttack shot, BoardScene scene) {
        if (shot.pushedOff() || shot.seconds < shot.contactSeconds
              || shot == review && !pushedOffResults.contains(shot.event.result().id())) {
            return false;
        }
        var hex = offBoard(shot, scene, shot != review);
        if (hex == null) { return false; }
        shot.pushOff(hex);
        pushedOffResults.add(shot.event.result().id());
        return true;
    }

    /**
     * The hex off the board a shove that hit pushed its target into, when {@code scene}, the checkpoint after it, has
     * lost the target; null otherwise, and while {@code live} also when the live playback holds the target's death.
     */
    private megamek.common.board.Coords offBoard(UnitAttack shot, BoardScene scene, boolean live) {
        var target = shot.event.target();
        if (scene == null || !shot.shoves() || !shot.event.result().hit() || target == null
              || scene.units().stream().anyMatch(unit -> unit.id() == target.id()) || live && dies(target.id())) {
            return null;
        }
        return shot.shoveHexes().stream().filter(hex -> scene.tile(hex) == null).findFirst().orElse(null);
    }

    /**
     * Records the skipped shoves ({@link #finish}) that pushed their target off the board in {@code scene}, the
     * checkpoint after them, as if they had played: a review of one tips its target off again.
     */
    private void recordPushedOff(List<UnitAttack> skipped, BoardScene scene) {
        skipped.stream().filter(shot -> offBoard(shot, scene, true) != null)
              .forEach(shot -> pushedOffResults.add(shot.event.result().id()));
        skipped.clear();
    }

    /** Whether the live playback holds the unit's death, under way or queued. */
    private boolean dies(int id) {
        return attacks.stream().anyMatch(attack -> attack.death() && attack.event.entityId() == id)
              || pending.stream().anyMatch(event -> event instanceof BoardScene.Combat death
                    && death.result().kind() == megamek.common.ResolvedAttack.Kind.DEATH && death.entityId() == id);
    }

    /**
     * Whether a shove that hit put {@code captured}, its target, into a hex it shoves to, or its attacker into the hex
     * the target left: a review shows that move, and no later one.
     */
    private static boolean shoved(UnitAttack shove, BoardScene.Unit captured, megamek.common.board.Coords at) {
        var target = shove.event.target();
        return target != null && (captured.id() == target.id() ? shove.shoveHexes().contains(at)
              : at.equals(target.location().coords()));
    }

    /**
     * Adjacent confirmed shots from the same pose form one volley; movement and physical actions are barriers. A death
     * that directly follows the hit that caused it joins that hit's action and plays from its landing.
     */
    private void collectVolley() {
        if (attack == null || attack.death()) { return; }
        while (attacks.size() < MAX_PENDING_EVENTS) {
            BoardScene.Combat next = null;
            boolean following = true;
            for (var event : pending) {
                if (event instanceof BoardScene.SceneUpdate) { continue; }
                if (event instanceof BoardScene.Combat casualty && casualty.result().kind() == megamek.common.ResolvedAttack.Kind.DEATH) {
                    if (following && killer(casualty) != null) {
                        next = casualty;
                        break;
                    }
                    following = false;
                    continue; // Finish the firing unit's volley before playing its other casualties.
                }
                if (attack.shot() && event instanceof BoardScene.Combat combat
                      && combat.result().kind() == megamek.common.ResolvedAttack.Kind.SHOT && sameVolley(combat)) { next = combat; }
                break;
            }
            if (next == null) { return; }
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var event = iterator.next();
                if (event == next) { iterator.remove(); break; }
                if (event instanceof BoardScene.SceneUpdate update) {
                    volleyScene = update.scene();
                    iterator.remove();
                }
            }
            var added = new UnitAttack(next);
            if (added.death()) { added.cause = killer(next); }
            attacks.add(added);
            if (added.cause == null) { volley.add(added, (float) combatSeconds); }
            for (var death : attacks) {
                if (death.cause == null) { continue; }
                // The volley may move a hit's landing while it schedules more shots.
                death.delay = death.cause.delay + death.cause.contactSeconds;
                death.seconds = Math.min(death.duration, (float) combatSeconds - death.delay);
            }
            combatDuration = attacks.stream().mapToDouble(item -> item.delay + item.duration).max().orElseThrow();
            // A death that joined shows the checkpoint at the hits' landing, as a fallen unit; its fall does not hold it.
            combatContact = attacks.stream().filter(item -> item.cause == null)
                  .mapToDouble(item -> item.delay + item.contactSeconds).max().orElseThrow();
            combatTerrainRelease = terrainRelease();
            completed = false;
            hold = 0;
        }
    }

    /** The hit that killed the casualty, when the casualty directly follows it: the last attack collected. */
    private UnitAttack killer(BoardScene.Combat casualty) {
        var last = attacks.getLast();
        return !last.death() && !last.defensive() && last.event.result().hit() && last.event.target() != null
              && last.event.target().id() == casualty.entityId() ? last : null;
    }

    private boolean beforeImpact() {
        return attack == null && !(active instanceof BoardScene.Detonation) || combatSeconds + 1e-7 < combatContact;
    }

    /** The latest moment a shot in the volley changes the terrain, never after the volley ends. */
    private double terrainRelease() {
        double release = attacks.stream().filter(item -> item.cause == null)
              .mapToDouble(item -> item.delay + item.contactSeconds + item.terrainDelaySeconds()).max().orElseThrow();
        return Math.min(release, combatDuration);
    }

    private boolean beforeTerrainRelease() {
        return attack == null && !(active instanceof BoardScene.Detonation) && !(active instanceof BoardScene.TileTransition)
              || combatSeconds + 1e-7 < combatTerrainRelease;
    }

    private boolean sameVolley(BoardScene.Combat next) {
        var primary = attacks.stream().filter(shot -> !shot.defensive()).findFirst().orElse(attack);
        boolean defensive = next.result().shot() != null && next.result().shot().defensive();
        if (defensive) {
            int incoming = primary.defensive() ? primary.event.result().target().entityId() : primary.event.entityId();
            return next.result().target().entityId() == incoming;
        }
        if (primary.defensive()) { return next.entityId() == primary.event.result().target().entityId(); }
        return next.entityId() == primary.event.entityId()
              && next.attacker().location().samePose(primary.event.attacker().location());
    }

    /** The scene change under way, through its completion hold, a review included; null when none plays. */
    BoardScene.TileTransition transition() {
        return active instanceof BoardScene.TileTransition change ? change : null;
    }

    /** Whether a structure is coming down in the scene change under way. */
    private boolean collapsing() {
        return active instanceof BoardScene.TileTransition change && !change.collapses().isEmpty();
    }

    /** How far {@link #transition} has played, 0 to 1. */
    float transitionProgress() {
        return active instanceof BoardScene.TileTransition ? (float) Math.min(1, combatSeconds / combatDuration) : 0;
    }

    UnitConversion conversion() { return conversion; }

    UnitAttachmentMotion attachment() { return attachment; }

    boolean busy() { return active != null || !pending.isEmpty(); }

    /** The final queued action lets Instant playback settle its camera once, after the whole queue is applied. */
    BoardScene.Animation lastAction() {
        var events = pending.descendingIterator();
        while (events.hasNext()) {
            var event = events.next();
            if (!(event instanceof BoardScene.SceneUpdate)) { return event; }
        }
        return active;
    }

    /** The action being presented retains camera focus through its completion hold. */
    int activeEntityId() { return active == null ? -1 : active.entityId(); }

    double holdSeconds() { return hold; }

    /**
     * How long a detonation plays, in playback seconds: a strike's whole front; a conventional explosion's front over its
     * few hexes and the fall of what it brought down.
     */
    static double duration(megamek.common.NuclearDetonation result) {
        if (result.kind() != megamek.common.NuclearDetonation.Kind.EXPLOSION) { return GpuArtilleryBlast.SHOCK_SECONDS; }
        return DETONATION_CONTACT_SECONDS + GpuArtilleryBlast.shockSeconds(GpuArtilleryBlast.nuclearRadius(result.stats()))
              / GpuArtilleryBlast.SECONDS_PER_PLAYBACK + BoardScene.TileTransition.COLLAPSE_SECONDS;
    }

    /**
     * How long {@code detonation} plays (#duration), starting now and going off after {@link #combatContact}: in a
     * {@code chain} of explosions (Detonation#cause), only until the next one goes off, as its own cause's front reaches
     * it, or at once if that came sooner (a sibling after a nephew, in the server's order). Remembers when each of the
     * chain went off.
     */
    private double chained(BoardScene.Detonation detonation, boolean chain) {
        if (!chain) {
            chainFlashes.clear();
            chainAt = 0;
        }
        double at = chainAt;
        chainFlashes.put(detonation.result().id(), at + combatContact);
        double whole = duration(detonation.result()) - (DETONATION_CONTACT_SECONDS - combatContact);
        BoardScene.Detonation next = null;
        for (var queued : pending) {
            if (queued instanceof BoardScene.SceneUpdate) { continue; }
            if (queued instanceof BoardScene.Detonation following) { next = following; }
            break;
        }
        Double causeFlash = next == null || next.cause() == null ? null : chainFlashes.get(next.cause().id());
        double played = causeFlash == null ? whole
              : Math.max(combatContact, Math.min(whole, causeFlash + arrival(next.cause(), next.result()) - at));
        chainAt = at + played;
        return played;
    }

    /**
     * Playback seconds from {@code cause}'s flash until its front reaches {@code next}'s ground zero, which goes off then
     * (GpuArtilleryBlast#shockArrival); a ground zero beyond its reach, as it ends.
     */
    static double arrival(megamek.common.NuclearDetonation cause, megamek.common.NuclearDetonation next) {
        float radius = GpuArtilleryBlast.nuclearRadius(cause.stats());
        var from = BoardGeometry.center(cause.coords(), 0);
        var to = BoardGeometry.center(next.coords(), 0);
        float distance = com.badlogic.gdx.math.Vector2.dst(from.x, from.y, to.x, to.y);
        return distance > radius ? duration(cause)
              : GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK;
    }

    boolean paused() { return paused; }

    /** Paused playback may still advance one requested action or a report review. */
    boolean clockRunning() { return !paused || stepping || review != null; }

    void togglePaused() {
        paused = !paused;
        stepping = false;
    }

    private UnitMotion start(BoardScene.Movement movement) {
        var motion = motions.computeIfAbsent(movement.entityId(), ignored -> new UnitMotion(movement.path().getFirst()));
        var unit = movement.unit();
        int members = unit != null && unit.model() != null && unit.model().state() != null
              && unit.model().state().structure().activeTroopers() > 0 ? unit.model().figures() : 0;
        motion.append(movement.path(), movement.type(), movement.jumpMP(), transports.test(unit), movement.movementMP(), members,
              UnitMotion.DEFAULT_SPEED_GAIN_PER_HEX, Float.isFinite(gravityOverride) ? gravityOverride : movement.gravity());
        return motion;
    }

    void finish() {
        attacks.forEach(this::completeAttack);
        // The shoves skipped before their checkpoint; one already past it pushed its target off as it played.
        List<UnitAttack> skipped = new ArrayList<>(attacks.stream().filter(shot -> !shot.pushedOff()).toList());
        if (active instanceof BoardScene.Detonation event) { startDetonation(event); }
        motions.values().forEach(UnitMotion::finish);
        if (!completed) { reactions.forEach(reaction -> completeMovement.accept(reaction.movement())); }
        if (active instanceof BoardScene.Movement movement && !completed) {
            completeMovement.accept(movement);
        } else if (active instanceof BoardScene.Conversion change) {
            settleConversion(change);
        }
        if (volleyScene != null) {
            settledScene = volleyScene;
            recordPushedOff(skipped, settledScene);
        }
        for (var event : pending) {
            if (event instanceof BoardScene.SceneUpdate update) {
                settledScene = update.scene();
                recordPushedOff(skipped, settledScene);
            } else if (event instanceof BoardScene.Movement movement) {
                start(movement).finish();
                completeMovement.accept(movement);
            } else if (event instanceof BoardScene.Conversion change) {
                settleConversion(change);
            } else if (event instanceof BoardScene.Combat combat) {
                var shot = new UnitAttack(combat);
                completeAttack(shot);
                skipped.add(shot);
            } else if (event instanceof BoardScene.Detonation detonation) {
                detonationStarted = false;
                startDetonation(detonation);
            }
        }
        recordPushedOff(skipped, settledScene);
        resetQueue();
    }

    private void resetQueue() {
        pending.clear();
        active = null;
        attack = null;
        attacks.clear();
        reactions.clear();
        volley.clear();
        volleyScene = null;
        conversion = null;
        attachment = null;
        hold = 0;
        completed = false;
        stepping = false;
        stepFrom = null;
    }

    void clear() {
        completedAttacks.clear();
        detonations.clear();
        brokenTrees.clear();
        resetQueue();
        review(null);
        paused = false;
        motions.clear();
        observed.clear();
        settledScene = null;
        overlayInput = null;
        overlayState = null;
        presentationScene = null;
        checkpoints = false;
    }

    /** Checkpoints after an action become visible at arrival/impact, before its recovery or completion hold. */
    private void applySceneUpdates() {
        if (attachment != null && attachment.progress() >= 1 && !attachment.settled) {
            attachment.settled = true;
            if (settledScene != null) {
                var shown = new ArrayList<>(settledScene.units());
                shown.removeIf(unit -> unit.id() == attachment.event.entityId());
                if (attachment.event.after() != null) { shown.add(attachment.event.after()); }
                settledScene = settledScene.withUnits(shown);
            }
        }
        if (active != null && !completed && beforeTerrainRelease()) {
            return;
        }
        if (volleyScene != null) {
            settledScene = volleyScene;
            volleyScene = null;
        }
        while (true) {
            if (pending.peekFirst() instanceof BoardScene.SceneUpdate update) {
                pending.removeFirst();
                settledScene = update.scene();
                if (!update.broken().isEmpty()) { brokenTrees.add(update); }
            } else if ((attack != null || collapsing()) && !completed
                  && pending.peekFirst() instanceof BoardScene.Movement movement && movement.forced()) {
                // Behind a hit or a structure coming down: what it moved, a unit knocked back or falling, moves with it.
                pending.removeFirst();
                double start = combatContact;
                boolean continuing = reactions.stream().anyMatch(reaction -> reaction.movement().entityId() == movement.entityId());
                var motion = start(movement);
                if (continuing) {
                    for (int index = 0; index < reactions.size(); index++) {
                        var reaction = reactions.get(index);
                        if (reaction.movement().entityId() == movement.entityId()) {
                            reactions.set(index, new Reaction(movement, reaction.start()));
                            break;
                        }
                    }
                    combatDuration = Math.max(combatDuration, combatSeconds + motion.remainingSeconds());
                } else {
                    reactions.add(new Reaction(movement, start));
                    combatDuration = Math.max(combatDuration, start + motion.remainingSeconds());
                    motion.advance(Math.max(0, combatSeconds - start), 1);
                }
            } else if (attack != null && !completed && attachment == null
                  && pending.peekFirst() instanceof BoardScene.AttachmentChange change) {
                pending.removeFirst();
                attachment = new UnitAttachmentMotion(change);
                attachmentStart = combatContact;
                attachment.seconds = (float) Math.min(UnitAttachmentMotion.DURATION, combatSeconds - attachmentStart);
                combatDuration = Math.max(combatDuration, attachmentStart + UnitAttachmentMotion.DURATION);
            } else { break; }
        }
        // A target pushed off the board goes over the edge and falls before the action completes.
        for (var shot : attacks) {
            if (!completed && pushOff(shot, settledScene)) {
                combatDuration = Math.max(combatDuration, shot.delay + shot.duration);
            }
        }
    }

    /**
     * Retain only captured, authorized appearance until its event plays; no later loadout or damage leaks forward. A
     * reviewed attack shows its captured attacker and target until its impact, over the live tiles (A.10); after it,
     * the live units. Until it ends, a participant the game has removed since (destroyed, pushed off the board) stays as
     * captured, and one moved since stays where it was; a shove that hit moves its participants to their live hexes
     * ({@link #placeDisplacement}).
     */
    BoardScene present(BoardScene scene) {
        BoardScene live = presentLive(scene);
        if (review == null) {
            return live;
        }
        Map<Drawn, BoardScene.Unit> shown = new LinkedHashMap<>();
        if (review.seconds + 1e-7 < review.contactSeconds) {
            holdUnits(review.event, shown, true);
        } else {
            pushOff(review, live);
            boolean displaces = review.shoves() && review.event.result().hit();
            for (var captured : java.util.Arrays.asList(review.event.attacker(), review.event.target())) {
                var now = captured == null ? null : live.units().stream()
                      .filter(unit -> Drawn.of(unit).equals(Drawn.of(captured))).findFirst().orElse(null);
                if (captured != null && now == null) {
                    hold(shown, captured);
                } else if (captured != null && !now.location().coords().equals(captured.location().coords())
                      && !(displaces && shoved(review, captured, now.location().coords()))) {
                    hold(shown, now.at(captured.location()));
                }
            }
        }
        return shown.isEmpty() ? live : live.withUnits(replaced(live.units(), shown));
    }

    private BoardScene presentLive(BoardScene scene) {
        applySceneUpdates();
        // UnitMotion completion includes landing, unloading and formation settling. Pending moves also hide
        // overlays between events; the final completion hold does not delay their return.
        boolean movementPending = active instanceof BoardScene.Movement && !completed
              || pending.stream().anyMatch(BoardScene.Movement.class::isInstance);
        // Until the action's terrain change is due (its contact, or a fuel-air round's ignition) the settled scene shows.
        boolean awaitingAction = active != null && !completed && beforeTerrainRelease()
              || pending.stream().anyMatch(event -> !(event instanceof BoardScene.SceneUpdate));
        if (awaitingAction) {
            // Review fixtures without checkpoints cannot supply history for their first combat frame.
            if (settledScene == null && !checkpoints && !movementPending) {
                settledScene = scene;
            }
            if (overlayInput != scene || overlayState != settledScene || overlayHideMovement != movementPending) {
                overlayInput = scene;
                overlayState = settledScene;
                overlayHideMovement = movementPending;
                presentationScene = scene.duringPlayback(settledScene, movementPending);
            }
            scene = presentationScene;
        } else {
            settledScene = scene;
            overlayInput = null;
            overlayState = null;
            presentationScene = null;
        }
        if (!busy()) {
            retainMotions(scene.units());
            return scene;
        }
        Map<Drawn, BoardScene.Unit> shown = new LinkedHashMap<>();
        if (active != null && !completed && beforeImpact()) {
            if (attack == null) { holdUnits(active, shown, true); }
            else { attacks.forEach(shot -> holdUnits(shot.event, shown, true)); }
        }
        pending.forEach(event -> holdUnits(event, shown, false));
        reactions.forEach(reaction -> holdUnits(reaction.movement(), shown, true));
        attacks.stream().filter(shot -> shot.pushedOff() && shot.seconds < shot.duration)
              .forEach(shot -> hold(shown, shot.event.target()));
        if (attachment != null && attachment.progress() < 1) {
            // Keep the released figures until they reach the resolved endpoint, even when damage removes them.
            var change = attachment.event;
            var endpoint = change.destination();
            var before = change.before();
            shown.put(Drawn.of(before), new BoardScene.Unit(before.id(), before.part(), before.name(), endpoint, before.image(),
                  false, before.annotations(), before.height(), false, before.model(), before.outlineRgb(),
                  List.of(endpoint.coords()), attachment.boarding() ? change.after().attachment() : null, before.heat()));
        }
        List<BoardScene.Unit> units = replaced(scene.units(), shown);
        retainMotions(units);
        return scene.withUnits(units);
    }

    /** One drawn piece of a unit: the whole unit (part -1), or one hex of a building drawn hex by hex. */
    private record Drawn(int id, int part) {
        static Drawn of(BoardScene.Unit unit) {
            return new Drawn(unit.id(), unit.part());
        }
    }

    /** The units with the held ones in their place, each drawn part by its own held part. */
    private static List<BoardScene.Unit> replaced(List<BoardScene.Unit> units, Map<Drawn, BoardScene.Unit> shown) {
        List<BoardScene.Unit> result = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (var unit : units) {
            present.add(unit.id());
            var replacement = shown.remove(Drawn.of(unit));
            result.add(replacement == null ? unit : replacement);
        }
        // An already-visible victim may be removed by the game before its final received attack has played: a whole
        // unit, or one hex of a building drawn hex by hex. A whole-unit hold never doubles a unit drawn in parts.
        shown.values().stream().filter(held -> held.part() >= 0 || !present.contains(held.id())).forEach(result::add);
        return result;
    }

    private void retainMotions(List<BoardScene.Unit> units) {
        motions.keySet().retainAll(units.stream().filter(unit -> !unit.sensorContact())
              .map(BoardScene.Unit::id).collect(Collectors.toSet()));
    }

    private void settleConversion(BoardScene.Conversion change) {
        if (settledScene != null) {
            settledScene = settledScene.withUnits(settledScene.units().stream()
                  .map(unit -> unit.id() == change.entityId() ? change.after() : unit).toList());
        }
    }

    private void holdUnits(BoardScene.Animation event, Map<Drawn, BoardScene.Unit> shown, boolean playing) {
        if (!playing && checkpoints
              && !(event instanceof BoardScene.Combat combat && combat.result().kind() == megamek.common.ResolvedAttack.Kind.DEATH)) {
            // The preceding checkpoint already contains the waiting unit's then-visible pose and appearance.
            // A queued collapse still needs its authorized victim after the volley checkpoint removes it.
            return;
        }
        if (event instanceof BoardScene.Combat combat) {
            hold(shown, combat.attacker());
            if (combat.target() != null) {
                hold(shown, combat.target());
            }
        } else if (event instanceof BoardScene.Conversion change) {
            hold(shown, playing && conversion != null ? conversion.displayed() : change.before());
        } else if (event instanceof BoardScene.Movement movement && movement.unit() != null) {
            var unit = movement.unit();
            var appearance = checkpoints && settledScene != null ? settledScene.units().stream()
                  .filter(previous -> previous.id() == unit.id() && !previous.sensorContact()).findFirst().orElse(unit) : unit;
            var location = playing ? movement.path().getLast() : movement.path().getFirst();
            var footprint = location.footprint().isEmpty()
                  ? playing ? unit.footprint() : List.of(location.coords()) : location.footprint();
            hold(shown, new BoardScene.Unit(unit.id(), unit.part(), appearance.name(), location, appearance.image(),
                  false, appearance.annotations(), unit.height(), unit.airborne(), appearance.model(), unit.outlineRgb(), footprint,
                  appearance.attachment(), appearance.heat()));
        } else if (event instanceof BoardScene.AttachmentChange change) {
            hold(shown, change.before());
            hold(shown, change.carrier());
        }
    }

    /** Holds a unit's captured appearance for its drawn part, unless an earlier event already holds that part. */
    private static void hold(Map<Drawn, BoardScene.Unit> shown, BoardScene.Unit unit) {
        shown.putIfAbsent(Drawn.of(unit), unit);
    }
}
