/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayData;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.panels.phaseDisplay.AbstractPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.AimedShotHandler;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.client.ui.panels.phaseDisplay.WeaponDeclarationDisplay;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.RangeType;
import megamek.common.TargetRollModifier;
import megamek.common.ToHitData;
import megamek.common.actions.AbstractAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeArc;
import megamek.common.compute.TurretFacing;
import megamek.common.enums.FacingArc;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.rules.RulesTarget;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import megamek.logging.MMLogger;

/**
 * EDT service for weapon declarations (rebuild plan A.8): an immutable snapshot of the local actor's orders, read from
 * the FiringDisplay's queue and to-hit numbers, and guarded commands that change that queue through the display's own
 * methods. The queue is the one source of the orders and the display's target the one source of the focus: a target
 * is whatever they name (TargetKey), a unit, a hex, a building or a minefield; letters are this service's
 * presentation of the queue, and every reorder keeps the display's action objects. While another own unit is the
 * actor, a unit's orders wait as its draft (H33), and "Resolve phase" declares the drafts on the following own turns
 * of the phase (H6).
 */
final class GpuFireOrders implements AutoCloseable {
    private static final MMLogger LOGGER = MMLogger.create(GpuFireOrders.class);
    /** How far the front-arc outline reaches without a weapon displayed (the prototype's 12 hexes, overlay.js:58). */
    static final int FRONT_ARC_RADIUS = 12;
    /** At most this many TN badges, the best first (the prototype's top 10, overlay.js:133). */
    static final int BADGES = 10;
    /** No turn index noted. */
    private static final int NO_TURN = Integer.MIN_VALUE;

    /**
     * One weapon of the actor, in the Unit Display's list order. {@code kind} is "Energy", "Missile", "Ballistic" or
     * "". {@code damage} is the value before " dmg": "{per hit}x{rack}" for cluster weapons, else the Unit Display's.
     * {@code mode} is "" without modes. {@code ammo} lists the bins it may switch to (its one fixed bin, or none), each
     * by its carrier and number; {@code loadedAmmo} is the loaded bin's index among them (-1 none) and {@code shots}
     * that bin's shots (-1 none). The TN column shows {@code target}: the target of the weapon's queued attack, else
     * the focus, else {@code TargetKey.NONE}; {@code value} and {@code odds} are its roll (TargetRoll values, odds
     * 0-100), and {@code reason} the rules' or the display's reason, non-empty only for an impossible shot.
     * {@code usable}: it can be assigned (valid for the phase) or is queued.
     */
    record WeaponRow(int eqNum, String name, String location, String kind, String damage, int heat, String mode,
          List<GpuUnitRecord.AmmoChoice> ammo, int loadedAmmo, int shots, TargetKey target, int value, double odds,
          String reason, boolean usable, boolean locationDestroyed) {
        WeaponRow {
            ammo = List.copyOf(ammo);
        }
    }

    /**
     * A target with queued attacks, in letter order: a unit, a hex, a building or a minefield. Letters follow the first
     * assignment and never change while the target keeps an attack. {@code primary} and {@code secondaryModifier} are
     * the rules' (Compute.getSecondaryTargetMod: null = primary, else its value); {@code frontArc} = in the actor's
     * forward arc; {@code hex} is the hex of a target that is no unit while it lies on the shown board, else null (the
     * scene places the units).
     */
    record Target(TargetKey key, char letter, String name, boolean primary, int secondaryModifier, boolean frontArc,
          Coords hex) { }

    /**
     * The firing display's target (H15): a unit the local player may identify, a hex, a building or a minefield, with
     * its name and, for a target that is no unit, its hex while it lies on the shown board (else null).
     */
    record Focus(TargetKey key, String name, Coords hex) {
        static final Focus NONE = new Focus(TargetKey.NONE, "", null);
    }

    /**
     * One queued weapon attack, in fire (queue) order. {@code eqNum} is -1 for a handheld weapon's attack;
     * {@code ammo} is the bin's Unit Display entry ("" none) with its {@code shots} (-1 none); {@code detail} is the
     * roll's description or reason.
     */
    record Attack(int eqNum, TargetKey target, String weapon, String location, String kind, String ammo, int shots,
          int value, double odds, String detail) { }

    /**
     * Heat if fired (H20): the unit's heat now, the heat it builds up this turn with the queued weapons, its heat
     * capacity as the Unit Display writes it, and the heat after dissipation with its effects ("" none). The ticks
     * and scale are the unit record's (GpuUnitRecord.heatTicks).
     */
    record Heat(int current, int after, String sinks, int end, String effectsAtEnd, List<Integer> ticks, int scale) {
        Heat {
            ticks = List.copyOf(ticks);
        }
    }

    record Modifier(String description, int value) { }

    /**
     * The mount arc of the selected weapon for the board's wedge: from {@code origin}, measured from the hex side
     * {@code facing} (twist, turret and mount included; leg weapons ignore the twist), clockwise from {@code start}
     * to {@code end} degrees (FacingArc's angles; {@code end} below {@code start} wraps through 0).
     */
    record WeaponArc(Coords origin, int facing, int start, int end) { }

    /**
     * The selected weapon against the target its row shows ({@code TargetKey.NONE}: none, then no modifiers).
     * {@code ranges} are min, short, medium, long[, extreme while the weapon display shows it]; {@code arc} names the
     * mount arc ("" for an arc without a name); {@code distance} is the rules' effective distance. {@code wedge} is
     * null while the actor is not on the shown board.
     */
    record Solution(int eqNum, TargetKey target, List<Integer> ranges, String arc, int distance,
          List<Modifier> modifiers, int value, double odds, String reason, WeaponArc wedge) {
        Solution {
            ranges = List.copyOf(ranges);
            modifiers = List.copyOf(modifiers);
        }
    }

    /**
     * The front arc, only while no weapon is displayed and the actor is on the shown board: the hexes up to
     * FRONT_ARC_RADIUS in the forward arc.
     */
    record FrontArc(Coords origin, int facing, Set<Coords> hexes) {
        FrontArc {
            hexes = Set.copyOf(hexes);
        }
    }

    /** A roll on one unit; {@code reason} is non-empty only when there is no shot. */
    record Badge(int targetId, int value, double odds, String reason) { }

    /**
     * The aimed shot choice on the focus (R4, H36): the locations the aimed shot handler offers in place of its dialog,
     * each enabled or not as the dialog's, the location it aims at ({@code Entity.LOC_NONE}: none), and the actor's
     * weapons that may aim.
     */
    record Aim(List<String> locations, List<Boolean> enabled, int location, Set<Integer> weapons) {
        Aim {
            locations = List.copyOf(locations);
            enabled = List.copyOf(enabled);
            weapons = Set.copyOf(weapons);
        }
    }

    /**
     * The local actor's orders while it declares attacks in the local firing turn, otherwise {@link #idle}.
     * {@code selectedWeapon} is the weapon the Unit Display selects (-1 none); {@code twist} is the secondary facing
     * from the facing, -2..3. {@code badges} are the selected weapon's rolls on the other identified enemies in its
     * long range, best first; {@code hoverBest} the actor's best roll on the hovered enemy. {@code heat},
     * {@code solution}, {@code frontArc} and {@code hoverBest} may be null. {@code drafted} counts the weapon attacks
     * of each other own unit's draft (H33; units with none left out) on every turn of the firing phase;
     * {@code pendingUnits} is, while active, the own units left to declare in this phase, the actor included; while
     * "Resolve phase" is on, {@code autoDeclareRemaining} is the own units it has left to declare, on every turn of
     * the phase, else 0. {@code aim} is the aimed shot choice on the focus, null while none is offered.
     */
    record Snapshot(boolean active, boolean editable, int actorId, Focus focus, int selectedWeapon,
          List<WeaponRow> weapons, List<Target> targets, List<Attack> attacks, int twist, boolean canTwistLeft,
          boolean canTwistRight, String torsoLabel, Heat heat, Solution solution, FrontArc frontArc,
          List<Badge> badges, Badge hoverBest, Map<Integer, Integer> drafted, int pendingUnits,
          int autoDeclareRemaining, Aim aim) {
        static final Snapshot EMPTY = idle(Map.of(), 0);

        /**
         * No local declaration (another player's turn, or the local turn's declaration sent), with the drafts' counts
         * and the resolve mode's count; equal to EMPTY without either.
         */
        static Snapshot idle(Map<Integer, Integer> drafted, int autoDeclareRemaining) {
            return new Snapshot(false, false, Entity.NONE, Focus.NONE, -1, List.of(), List.of(), List.of(), 0, false,
                  false, "", null, null, null, List.of(), null, drafted, 0, autoDeclareRemaining, null);
        }

        Snapshot {
            weapons = List.copyOf(weapons);
            targets = List.copyOf(targets);
            attacks = List.copyOf(attacks);
            badges = List.copyOf(badges);
            drafted = Map.copyOf(drafted);
        }

        /**
         * The targets with a target card, which replaces a unit's nameplate and TN badge (H24, P3): the targets in
         * letter order, then the focus without attacks.
         */
        Set<TargetKey> carded() {
            return GpuFireOrders.carded(targets.stream().map(Target::key).toList(), focus.key());
        }

        /** The hexes of the carded targets that are no units, while they lie on the shown board. */
        Map<TargetKey, Coords> hexes() {
            Map<TargetKey, Coords> hexes = new LinkedHashMap<>();
            for (Target target : targets) {
                if (target.hex() != null) {
                    hexes.put(target.key(), target.hex());
                }
            }
            if (focus.hex() != null) {
                hexes.putIfAbsent(focus.key(), focus.hex());
            }
            return hexes;
        }
    }

    /** One weapon's roll for the snapshot: the target it is against and the to-hit. */
    private record Shot(TargetKey target, @Nullable ToHitData toHit) { }

    /**
     * What the TN badges are computed from: the actor, the selected weapon and what changes its rolls locally (mode,
     * loaded bin, called shot), the focus, the queue (its targets and secondary-target modifiers), the twist and arms,
     * and the game's revision for everything the server changes.
     */
    private record BadgeKey(int actorId, int weapon, String setting, TargetKey focus, List<EntityAction> queue,
          int facing, boolean flipped, long revision) { }

    /** A unit's saved orders (H33): its queued actions in fire order and the serials of its targets' letters. */
    private record Draft(List<EntityAction> actions, Map<TargetKey, Integer> serials) { }

    private final GpuBoardSource source;
    // EDT only. The display of the last capture, and the letters of its actor's targets: each target's serial is the
    // order of its first assignment, so letters close gaps but never follow the primary or the fire order.
    private WeaponDeclarationDisplay display;
    private int lettersActor = Entity.NONE;
    /** The weapons last hidden for the phase, and whose: logged only when they change. */
    private int lastHiddenWeapons = -1;
    private int lastHiddenActor = Entity.NONE;
    private final Map<TargetKey, Integer> serials = new HashMap<>();
    private int serial;
    /** A command runs: a capture inside its dialog's nested loop keeps the last snapshot (the queue is half-done). */
    private boolean commanding;
    private Snapshot published = Snapshot.EMPTY;
    // The TN badges, over budget for a capture at 100 v 100 (rule 9): computed in their own event for badgeKey.
    private BadgeKey badgeKey;
    private List<Badge> badges = List.of();
    private boolean badgeJob;
    // Drafts (H33), EDT only, for one firing phase of one round (draftRound, draftPhase): the orders of the own units
    // that are not the actor, whose orders are the display's queue. The actor, turn index, orders and letters noted
    // where no operation of the display is half-done (see track) tell a unit switch the display made (Next unit, a
    // click it handled) from the end of a turn. endedTurn is a turn whose declaration the display sent: the client
    // keeps that turn until the server answers, and nothing acts in it.
    private final Map<Integer, Draft> drafts = new HashMap<>();
    private int draftRound = -1;
    private GamePhase draftPhase;
    private int trackedActor = Entity.NONE;
    private int trackedTurn = NO_TURN;
    private Draft tracked = new Draft(List.of(), Map.of());
    private int endedTurn = NO_TURN;
    private boolean trackQueued;
    /** H1: this firing phase's entry toast was shown, at the local player's first declaration in it. */
    private boolean entered;
    // Resolve phase (H6), EDT only: on from resolvePhase until Stop, the end of its phase, the last own unit or a unit
    // that cannot declare. resolvedTurn is the index of the turn the mode last acted in.
    private boolean resolving;
    private int resolvedTurn = NO_TURN;
    private boolean resolveQueued;
    /** EDT: counts the game's changes; the listener only marks them (B.1 rule 4). */
    private long revision;
    private Game listened;
    private final GameListener listener = new GameListenerAdapter() {
        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            changed();
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            changed();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            changed();
        }

        @Override
        public void gameNewAction(GameNewActionEvent event) {
            changed();
        }

        @Override
        public void gameBoardChanged(GameBoardChangeEvent event) {
            changed();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            changed();
        }

        @Override
        public void gameTurnChange(GameTurnChangeEvent event) {
            turnChanged();
        }
    };

    GpuFireOrders(GpuBoardSource source) {
        this.source = source;
    }

    /** The game changed: counted on the EDT; an event from another thread is reposted there (as GpuFirePreview's). */
    private void changed() {
        if (SwingUtilities.isEventDispatchThread()) {
            revision++;
        } else {
            SwingUtilities.invokeLater(this::changed);
        }
    }

    /**
     * A turn from the server, counted as a change. It makes the turn live again even when it is the turn whose
     * declaration was sent: the server sends the same turn again after a declaration it refused.
     */
    private void turnChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            revision++;
            endedTurn = NO_TURN;
        } else {
            SwingUtilities.invokeLater(this::turnChanged);
        }
    }

    /** EDT: {@link #capture(JComponent, Coords, int)} without an own focus unit. */
    Snapshot capture(JComponent panel, @Nullable Coords hover) {
        return capture(panel, hover, Entity.NONE);
    }

    /**
     * EDT: the orders of the firing display's actor during the local firing turn, with the best roll on the enemy in
     * the {@code hover} hex; otherwise the draft of the own {@code focusUnit} read-only (H33); and on every turn the
     * drafts and the resolve mode's count. Returns the previous instance while nothing changed. A capture can run
     * inside the display's own operations (an entity event refreshes the source), so the turn's state is noted, a
     * draft comes back and the resolve mode declares in events of their own.
     */
    Snapshot capture(JComponent panel, @Nullable Coords hover, int focusUnit) {
        GpuBoardSource.requireSwingThread();
        if (commanding) {
            return published;
        }
        // The Firing phase's display, or the Targeting and Off-board phases' (artillery, TAG): either declares weapons.
        display = (panel instanceof WeaponDeclarationDisplay declarations) ? declarations : null;
        Game game = source.currentView().game;
        forget(game);
        if ((display != null) && !trackQueued) {
            trackQueued = true;
            SwingUtilities.invokeLater(this::trackJob);
        }
        int remaining = resolve(game, display);
        Entity actor = acting(display);
        if (actor == null) {
            lettersActor = Entity.NONE;
            serials.clear();
            Snapshot draft = readOnly(game, focusUnit, remaining);
            Map<Integer, Integer> drafted = drafted(Entity.NONE);
            return publish((draft != null) ? draft : (drafted.isEmpty() && (remaining == 0)) ? Snapshot.EMPTY
                  : Snapshot.idle(drafted, remaining));
        }
        WeaponDeclarationDisplay fd = display;
        if (listened != game) {
            // A former game's listener goes first.
            unlisten();
            game.addGameListener(listener);
            listened = game;
        }
        List<EntityAction> queue = fd.getAttacks();
        List<TargetKey> lettered = letters(actor, queue);
        Targetable focus = focus(fd);
        TargetKey focusKey = key(focus);
        // One roll per queued attack, for its line and its weapon's row (as queued() finds it: the first). An attack on
        // a target that has left the game has no roll (MegaMek logs an error for one): it is left out, as a draft's.
        List<Attack> attacks = new ArrayList<>();
        Map<Integer, Shot> shots = new HashMap<>();
        for (EntityAction action : queue) {
            if ((action instanceof WeaponAttackAction attack) && (attack.getTarget(game) != null)) {
                ToHitData toHit = attack.toHit(game, true);
                attacks.add(attack(game, actor, attack, toHit, actor.isUseNaturalAptitudeGunnery(game, attack)));
                if (attack.getEntityId() == actor.getId()) {
                    shots.putIfAbsent(attack.getWeaponId(), new Shot(TargetKey.of(attack), toHit));
                }
            }
        }
        List<WeaponRow> rows = new ArrayList<>();
        List<WeaponMounted> declarable = declarableWeapons(game, actor);
        for (WeaponMounted weapon : declarable) {
            if (weapon.getEntity() != actor) {
                // A handheld weapon has no number on the actor; it keeps the Swing controls.
                continue;
            }
            int eqNum = actor.getEquipmentNum(weapon);
            boolean queued = shots.containsKey(eqNum);
            Shot shot = queued ? shots.get(eqNum)
                  : (focus != null) ? new Shot(focusKey, fd.toHitFor(weapon, focus))
                  : new Shot(TargetKey.NONE, null);
            shots.put(eqNum, shot);
            rows.add(row(game, actor, weapon, eqNum, shot, queued, actor.isUseNaturalAptitudeGunnery(game, weapon)));
        }
        List<Target> targets = new ArrayList<>();
        for (int index = 0; index < lettered.size(); index++) {
            TargetKey key = lettered.get(index);
            Targetable target = resolve(game, key);
            if (target != null) {
                ToHitData secondary = Compute.getSecondaryTargetMod(game, actor, target);
                targets.add(new Target(key, (char) ('A' + index), name(target), secondary == null,
                      (secondary == null) ? 0 : secondary.getValue(), inFrontArc(actor, target), hex(target)));
            }
        }
        WeaponMounted selected = selectedWeapon(fd, actor);
        if ((selected != null) && !declarable.contains(selected)) {
            // The Unit Display may still have, say, a laser selected; it cannot fire in this phase, so none is.
            selected = null;
        }
        int selectedNum = (selected == null) ? -1 : actor.getEquipmentNum(selected);
        Set<TargetKey> carded = carded(lettered, focusKey);
        // The arcs are drawn on the shown board only
        boolean shown = actor.getBoardId() == source.currentView().getBoardId();
        int rotation = (actor.getSecondaryFacing() - actor.getFacing() + 6) % 6;
        return publish(new Snapshot(true, true, actor.getId(),
              (focus == null) ? Focus.NONE : new Focus(focusKey, name(focus), hex(focus)),
              selectedNum, rows, targets, attacks, (rotation > 3) ? (rotation - 6) : rotation,
              canTwist(actor, -1), canTwist(actor, 1),
              ((actor instanceof Tank tank) && !tank.hasNoTurret()) ? Messages.getString("GpuBoard.hud.dock.turret")
                    : "",
              heat(game, actor),
              (selected == null) ? null : solution(game, actor, selected, selectedNum, shots.get(selectedNum),
                    shown, actor.isUseNaturalAptitudeGunnery(game, selected)),
              ((selected == null) && shown) ? frontArc(game, actor) : null,
              badges(badgeKey(fd, actor, queue, focusKey), carded),
              hoverBest(game, fd, actor, hover, queue, carded), drafted(actor.getId()),
              GpuBattleStatus.unitsToAct(game, source.currentView().getLocalPlayer()), remaining, aim(fd, actor)));
    }

    /**
     * EDT: the TN badges computed for {@code key} (null: no weapon selected), else a coalesced job computes them in
     * its own event and republishes (rule 9); until then the weapon's previous badges stay, but none on a unit with a
     * card.
     */
    private List<Badge> badges(@Nullable BadgeKey key, Set<TargetKey> carded) {
        if ((key != null) && !key.equals(badgeKey) && !badgeJob) {
            badgeJob = true;
            SwingUtilities.invokeLater(this::badgeJob);
        }
        boolean sameWeapon = (key != null) && (badgeKey != null) && (key.actorId() == badgeKey.actorId())
              && (key.weapon() == badgeKey.weapon());
        return sameWeapon ? badges.stream().filter(badge -> !carded.contains(TargetKey.unit(badge.targetId())))
              .toList() : List.of();
    }

    /** EDT, in its own event: the badges of the display's current state; never inside a dialog's nested loop. */
    private void badgeJob() {
        badgeJob = false;
        WeaponDeclarationDisplay fd = display;
        Entity actor = acting(fd);
        if ((actor == null) || commanding || !source.acceptsInput()) {
            return;
        }
        List<EntityAction> queue = fd.getAttacks();
        TargetKey focus = key(focus(fd));
        BadgeKey key = badgeKey(fd, actor, queue, focus);
        if ((key == null) || key.equals(badgeKey)) {
            return;
        }
        Game game = source.currentView().game;
        badges = rolls(game, fd, actor, selectedWeapon(fd, actor), queue, carded(letters(actor, queue), focus));
        badgeKey = key;
        source.refresh();
    }

    /** The key of the badges for the display's state, null without a selected weapon. */
    private @Nullable BadgeKey badgeKey(WeaponDeclarationDisplay fd, Entity actor, List<EntityAction> queue,
          TargetKey focus) {
        WeaponMounted weapon = selectedWeapon(fd, actor);
        if (weapon == null) {
            return null;
        }
        AmmoMounted ammo = weapon.getLinkedAmmo();
        String setting = weapon.curMode().getName() + ':' + ((ammo == null) ? -1
              : ammo.getEntity().getEquipmentNum(ammo)) + ':' + weapon.getCalledShot().getCall();
        return new BadgeKey(actor.getId(), actor.getEquipmentNum(weapon), setting, focus, queue,
              actor.getSecondaryFacing(), actor.getArmsFlipped(), revision);
    }

    /**
     * The targets with cards, whose units get no badge or hover roll: the targets in letter order, then the focus
     * ({@code TargetKey.NONE}: none).
     */
    private static Set<TargetKey> carded(List<TargetKey> lettered, TargetKey focus) {
        Set<TargetKey> carded = new LinkedHashSet<>(lettered);
        if (!focus.equals(TargetKey.NONE)) {
            carded.add(focus);
        }
        return carded;
    }

    // GL-safe commands (B.5). Each is posted through the source's input guard and runs only while the actor of the
    // last capture still declares its attacks in the local firing turn; every command but selectWeapon keeps the
    // weapon the Unit Display selects.

    /**
     * Selects another own unit that may fire now, as a click on it does (FiringDisplay.unitSelected); the actor's
     * orders stay as its draft, and the unit gets its own draft back (H33). Selecting the actor again changes nothing
     * (the display would clear its orders).
     */
    void selectUnit(int id) {
        source.command(() -> {
            WeaponDeclarationDisplay fd = display;
            Game game = source.currentView().game;
            Entity unit = game.getEntity(id);
            GameTurn turn = (fd == null) ? null : ((ClientGUI) fd.getClientGUI()).getClient().getMyTurn();
            if (!firingTurn(fd) || fd.isIgnoringEvents() || (unit == null) || (turn == null)
                  || !turn.isValidEntity(unit, game) || (unit == fd.currentEntity())) {
                return;
            }
            commanding = true;
            try {
                // The actor's orders as they are now; the display releases them when it switches.
                track(game, fd);
                fd.selectEntity(id);
                track(game, fd);
                Entity actor = acting(fd);
                if (actor != null) {
                    restoreDraft(fd, actor);
                }
            } finally {
                commanding = false;
            }
            track(game, fd);
        });
    }

    /** Selects the weapon in the Unit Display (its arc, bands and solution); -1 selects none (the front arc). */
    void selectWeapon(int eqNum) {
        source.command(() -> {
            WeaponDeclarationDisplay fd = display;
            Entity actor = acting(fd);
            if ((actor == null) || (actor.getId() != published.actorId()) || fd.isIgnoringEvents()) {
                return;
            }
            WeaponMounted weapon = weapon(actor, eqNum);
            if ((weapon != null) || (eqNum < 0)) {
                show(fd, actor, weapon);
            }
        });
    }

    /**
     * Saves a declaration row's position in the unit's custom weapon order, without changing its queued attacks.
     * Equipment numbers anchor the move even when this phase shows only some of the unit's weapons.
     */
    void moveWeapon(int unitId, int eqNum, int destinationEqNum) {
        command((fd, actor) -> {
            if (actor.getId() != unitId) {
                return;
            }
            WeaponMounted weapon = weapon(actor, eqNum);
            WeaponMounted destination = weapon(actor, destinationEqNum);
            List<WeaponMounted> declarable = declarableWeapons(actor.getGame(), actor);
            if (declarable.contains(weapon) && declarable.contains(destination)) {
                WeaponDisplayData.moveWeapon(actor, weapon, destination);
            }
        });
    }

    /** H15: targets an identified enemy, a hex, a building or a minefield; a sensor contact is refused (H19). */
    void focusTarget(TargetKey key) {
        command((fd, actor) -> {
            Targetable target = target(fd, actor, key);
            if (target != null) {
                setTarget(fd, target);
            }
        });
    }

    /**
     * H15, H16: a shot of the weapon at the target as the Fire button declares it (its prompts included), or the
     * refusal toast (H17); a queued weapon is retargeted. Then the queue takes the canonical order, unless Fire ended
     * the turn (the auto-end setting, honoured once, by FiringDisplay.fire).
     */
    void assign(int eqNum, TargetKey key) {
        command((fd, actor) -> declare(fd, actor, eqNum, key));
    }

    /** Assigns all available weapons to the target through the same declaration path as an individual slot. */
    void assignAll(TargetKey key) {
        command((fd, actor) -> {
            Targetable target = target(fd, actor, key);
            if (target == null) {
                return;
            }
            // Retarget existing attacks in fire order before a new last weapon can auto-end the turn.
            List<EntityAction> before = fd.getAttacks();
            List<WeaponMounted> weapons = declarableWeapons(actor.getGame(), actor).stream()
                  .filter(weapon -> weapon.getEntity() == actor)
                  .sorted(Comparator.comparingInt(weapon -> {
                      WeaponAttackAction attack = queued(before, actor, actor.getEquipmentNum(weapon));
                      return attack == null ? before.size() : before.indexOf(attack);
                  })).toList();
            for (WeaponMounted weapon : weapons) {
                if ((acting(fd) != actor) || fd.isIgnoringEvents()) {
                    break;
                }
                if ((queued(fd.getAttacks(), actor, actor.getEquipmentNum(weapon)) != null)
                      || (actor.isWeaponValidForPhase(weapon)
                            && (fd.toHitFor(weapon, target).getValue() != TargetRoll.IMPOSSIBLE))) {
                    declare(fd, actor, weapon, target);
                }
            }
        });
    }

    /**
     * A click on a hex in which the pointer picked no unit (H15, H16): the target MegaMek's board click chooses there
     * (FiringDisplay.hexSelected: a unit, a building or a wooded hex, by its dialog among several; Shift twists the
     * torso instead), then a shot of the armed weapon {@code eqNum} (-1: none) at the target it chose, as a click on an
     * enemy assigns it.
     */
    void clickHex(Coords coords, int modifiers, int eqNum) {
        command((fd, actor) -> {
            Targetable before = fd.getTarget();
            source.clickNow(coords, false, modifiers);
            Targetable chosen = fd.getTarget();
            WeaponMounted weapon = weapon(actor, eqNum);
            if ((weapon != null) && (chosen != null) && (chosen != before) && (acting(fd) == actor)) {
                declare(fd, actor, weapon, chosen);
            }
        });
    }

    /** H18: removes the weapon's attack. */
    void remove(int eqNum) {
        command((fd, actor) -> {
            List<EntityAction> before = fd.getAttacks();
            WeaponAttackAction attack = queued(before, actor, eqNum);
            if (attack != null) {
                TargetKey lead = lead(source.currentView().game, actor, before);
                fd.removeAttack(attack);
                order(fd, actor, fd.getAttacks(), lead);
            }
        });
    }

    /** H34: removes the last attack in fire order (Backspace). */
    void removeLast() {
        command((fd, actor) -> fd.removeLastFiring());
    }

    /** M3: removes every attack on the target; a focus on it moves to the first target left. */
    void removeTarget(TargetKey key) {
        command((fd, actor) -> {
            Game game = source.currentView().game;
            List<EntityAction> queue = fd.getAttacks();
            List<EntityAction> kept = queue.stream().filter(action -> !isOrder(action, key)).toList();
            if (kept.size() == queue.size()) {
                return;
            }
            order(fd, actor, kept, lead(game, actor, queue));
            List<TargetKey> lettered = letters(actor, fd.getAttacks());
            Targetable first = lettered.isEmpty() ? null : resolve(game, lettered.getFirst());
            if ((first != null) && key.equals(key(fd.getTarget()))) {
                setTarget(fd, first);
            }
        });
    }

    /** H18: the weapon's attack moves to the target and keeps its place in the fire order. */
    void retarget(int eqNum, TargetKey key) {
        command((fd, actor) -> declare(fd, actor, eqNum, key));
    }

    /**
     * H29: the weapon's attack fires {@code delta} places later (earlier when negative) among the attacks on its
     * target, in one requeue, as its card's row dropped there or Alt+Up/Down asks; the other attacks keep their order
     * and places. A move past the target's first or last attack does nothing.
     */
    void move(int eqNum, int delta) {
        command((fd, actor) -> {
            List<EntityAction> queue = new ArrayList<>(fd.getAttacks());
            WeaponAttackAction attack = queued(queue, actor, eqNum);
            if (attack == null) {
                return;
            }
            List<Integer> slots = new ArrayList<>();
            List<EntityAction> same = new ArrayList<>();
            for (int index = 0; index < queue.size(); index++) {
                if ((queue.get(index) instanceof WeaponAttackAction other)
                      && (other.getTargetType() == attack.getTargetType())
                      && (other.getTargetId() == attack.getTargetId())) {
                    slots.add(index);
                    same.add(other);
                }
            }
            int at = same.indexOf(attack);
            int to = at + delta;
            if ((delta == 0) || (to < 0) || (to >= same.size())) {
                return;
            }
            same.add(to, same.remove(at));
            for (int index = 0; index < slots.size(); index++) {
                queue.set(slots.get(index), same.get(index));
            }
            fd.replaceAttacks(queue);
        });
    }

    /**
     * H13: the target's attacks fire first. When the rules then make it primary, a toast names the secondary
     * modifiers; when they keep another target primary (front arc), the order is restored and a toast says so.
     */
    void setPrimary(TargetKey key) {
        command((fd, actor) -> {
            Game game = source.currentView().game;
            Targetable target = resolve(game, key);
            List<EntityAction> before = fd.getAttacks();
            if ((target == null) || before.stream().noneMatch(action -> isOrder(action, key))) {
                toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.assignFirst");
                return;
            }
            replace(fd, canonical(game, actor, before, key));
            if (primary(game, actor, key)) {
                RulesTarget rules = Game.rulesManager.getRulesTarget();
                toast(fd, ToastLevel.INFO, "GpuBoard.hud.fire.primary", name(target),
                      signed(rules.getSecondaryTargetModifier()), signed(rules.getSecondaryArcModifier()));
            } else {
                replace(fd, before);
                Targetable kept = letters(actor, before).stream().filter(lettered -> primary(game, actor, lettered))
                      .map(lettered -> resolve(game, lettered)).filter(Objects::nonNull).findFirst().orElse(null);
                toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.cannotBePrimary", name(target),
                      (kept == null) ? "" : name(kept));
            }
        });
    }

    /**
     * H30: loads the bin of that carrier and number (a trailer's bins are numbered on the trailer), as a pick in the
     * Unit Display's ammunition list does; a queued attack is declared again with it, at its place in the fire order
     * (the new ammunition may prompt). A toast names the ammunition.
     */
    void setAmmo(int eqNum, GpuUnitRecord.AmmoChoice choice) {
        command((fd, actor) -> {
            WeaponMounted weapon = weapon(actor, eqNum);
            AmmoMounted bin = (weapon == null) ? null : bins(actor, weapon).stream().filter(choice::is).findFirst()
                  .orElse(null);
            if ((bin == null) || (bin == GpuUnitRecord.ammoWeapon(weapon).getLinkedAmmo())) {
                return;
            }
            WeaponAttackAction attack = queued(fd.getAttacks(), actor, eqNum);
            boolean changed = (attack == null) ? load(fd, actor, weapon, bin)
                  : redeclare(fd, actor, attack, weapon, attack.getTarget(source.currentView().game), bin);
            if (changed && (acting(fd) == actor)) {
                toast(fd, ToastLevel.INFO, "GpuBoard.hud.fire.ammoChanged", weapon.getPlainDesc(),
                      WeaponDisplayData.formatAmmo(actor, bin));
            }
        });
    }

    /** H36: the next or previous mode of the weapon (FiringDisplay.changeMode), while its Mode command is enabled. */
    void cycleMode(int eqNum, boolean forward) {
        command((fd, actor) -> {
            WeaponMounted weapon = weapon(actor, eqNum);
            if (weapon != null) {
                show(fd, actor, weapon);
                if (enabled(fd, FiringCommand.FIRE_MODE) != null) {
                    fd.changeMode(forward);
                }
            }
        });
    }

    /** H36: the weapon's next called shot, through the display's Called command while it is enabled. */
    void calledShot(int eqNum) {
        command((fd, actor) -> {
            WeaponMounted weapon = weapon(actor, eqNum);
            if (weapon != null) {
                show(fd, actor, weapon);
                MegaMekButton called = enabled(fd, FiringCommand.FIRE_CALLED);
                if (called != null) {
                    called.doClick(0);
                }
            }
        });
    }

    /**
     * H36, R4: aims at a location of the focused unit ({@code Entity.LOC_NONE}: no aim) that the aimed shot handler
     * offers in place of its dialog, with a weapon that may aim, as a choice in the dialog does.
     */
    void aim(int eqNum, int location) {
        command((fd, actor) -> {
            WeaponMounted weapon = weapon(actor, eqNum);
            AimedShotHandler aims = fd.getAimedShotHandler();
            AimedShotHandler.Offer offer = (aims == null) ? null : aims.getOffer();
            if ((weapon != null) && (offer != null) && aims.allowAimedShotWith(weapon)
                  && ((location == Entity.LOC_NONE) || ((location >= 0) && (location < offer.enabled().size())
                  && offer.enabled().get(location)))) {
                show(fd, actor, weapon);
                aims.aimAt(location);
            }
        });
    }

    /** H4: twists left ({@code direction} below 0) or right as the twist keys do; twisting clears the attacks. */
    void twist(int direction) {
        command((fd, actor) -> {
            if (canTwist(actor, direction)) {
                fd.updateFlipArms(false);
                fd.torsoTwist((direction < 0) ? 0 : 1);
            }
        });
    }

    /** H6: Clear, as the display's clear does (the twist goes too). */
    void clearAll() {
        command((fd, actor) -> fd.clear());
    }

    /**
     * H6: on the local firing turn, declares the actor now with its orders, and every other own unit on the next own
     * turns of this firing phase with its draft; a unit without one holds fire. Firing turns alternate by player, so
     * this is a mode, shown by {@code autoDeclareRemaining} until it ends (see {@link #resolve}).
     */
    void resolvePhase() {
        source.command(() -> {
            WeaponDeclarationDisplay fd = display;
            Entity actor = acting(fd);
            if (resolving || (actor == null) || (actor.getId() != published.actorId()) || fd.isIgnoringEvents()) {
                return;
            }
            resolving = true;
            resolvedTurn = NO_TURN;
            declareTurn(fd);
        });
    }

    /** H6: Stop ends the resolve mode. */
    void stopResolve() {
        source.command(() -> resolving = false);
    }

    /** EDT: ends the resolve mode, forgets the drafts and removes the game listener (GpuBoardSource.close()). */
    @Override
    public void close() {
        resolving = false;
        drafts.clear();
        unlisten();
    }

    private void unlisten() {
        if (listened != null) {
            listened.removeGameListener(listener);
            listened = null;
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * Runs a command body on the EDT behind the source's input guard while the actor of the last capture still
     * declares its attacks (B.5 recheck), then selects the weapon the Unit Display selected before and notes the
     * turn's state (the orders as they are now, or the turn the command ended).
     */
    private void command(BiConsumer<WeaponDeclarationDisplay, Entity> body) {
        source.command(() -> {
            WeaponDeclarationDisplay fd = display;
            Entity actor = acting(fd);
            if ((actor == null) || (actor.getId() != published.actorId()) || fd.isIgnoringEvents()) {
                return;
            }
            WeaponMounted selected = selectedWeapon(fd, actor);
            commanding = true;
            try {
                body.accept(fd, actor);
            } finally {
                commanding = false;
                if ((acting(fd) == actor) && (selectedWeapon(fd, actor) != selected)) {
                    show(fd, actor, selected);
                }
                track(source.currentView().game, fd);
            }
        });
    }

    /**
     * {@link #declare(WeaponDeclarationDisplay, Entity, WeaponMounted, Targetable)} for the weapon's number and
     * target's key.
     */
    private void declare(WeaponDeclarationDisplay fd, Entity actor, int eqNum, TargetKey key) {
        WeaponMounted weapon = weapon(actor, eqNum);
        Targetable target = (weapon == null) ? null : target(fd, actor, key);
        if (target != null) {
            declare(fd, actor, weapon, target);
        }
    }

    /** Assigns the weapon to the target, or moves its queued attack there at the attack's place in the fire order. */
    private void declare(WeaponDeclarationDisplay fd, Entity actor, WeaponMounted weapon, Targetable target) {
        List<EntityAction> before = fd.getAttacks();
        WeaponAttackAction attack = queued(before, actor, actor.getEquipmentNum(weapon));
        if ((attack != null) && TargetKey.of(attack).equals(TargetKey.of(target))) {
            setTarget(fd, target);
        } else if (attack != null) {
            redeclare(fd, actor, attack, weapon, target, null);
        } else {
            TargetKey lead = lead(source.currentView().game, actor, before);
            // Fire may end the turn (auto-end firing, after the last weapon); the display sent the queue as it was.
            if ((shoot(fd, actor, weapon, target) != null) && (acting(fd) == actor)) {
                order(fd, actor, fd.getAttacks(), lead);
            }
        }
    }

    /**
     * Declares the queued attack again, at the target (retarget) or with the bin (setAmmo; null: the bin stays), at
     * its place in the canonical order. The attacks after that place leave the queue first: Fire, which ends the turn
     * once no weapon is left to fire (the auto-end setting), can then end it only when nothing follows the attack, the
     * queue being in that order already; otherwise they come back after the attack. A refusal, or a bin the weapon
     * does not load, puts the bin, the queue and the letters back as they were. True when the attack was declared.
     */
    private boolean redeclare(WeaponDeclarationDisplay fd, Entity actor, WeaponAttackAction attack,
          WeaponMounted weapon, Targetable target, @Nullable AmmoMounted bin) {
        Game game = source.currentView().game;
        List<EntityAction> before = fd.getAttacks();
        AmmoMounted loaded = GpuUnitRecord.ammoWeapon(weapon).getLinkedAmmo();
        Map<TargetKey, Integer> serialsBefore = Map.copyOf(serials);
        TargetKey lead = lead(game, actor, before);
        // The canonical order with a stand-in for the new attack (never queued) at the attack's place
        WeaponAttackAction standIn = new WeaponAttackAction(attack.getEntityId(), target.getTargetType(),
              target.getId(), attack.getWeaponId());
        List<EntityAction> order = new ArrayList<>(before);
        order.set(before.indexOf(attack), standIn);
        order = canonical(game, actor, order, lead);
        int place = order.indexOf(standIn);
        fd.replaceAttacks(order.subList(0, place));
        EntityAction declared = ((bin == null) || load(fd, actor, weapon, bin)) ? shoot(fd, actor, weapon, target)
              : null;
        if (acting(fd) != actor) {
            // Fire ended the turn and the display sent the queue: the attack came last in the canonical order.
            return true;
        }
        if (declared == null) {
            if (GpuUnitRecord.ammoWeapon(weapon).getLinkedAmmo() != loaded) {
                load(fd, actor, weapon, loaded);
            }
            fd.replaceAttacks(before);
            serials.clear();
            serials.putAll(serialsBefore);
            return false;
        }
        List<EntityAction> queue = new ArrayList<>(fd.getAttacks());
        queue.addAll(order.subList(place + 1, order.size()));
        order(fd, actor, queue, lead);
        return true;
    }

    /**
     * Declares a shot of the weapon at the target as the Fire button does: the Unit Display shows the weapon, the
     * display targets the target and fires when it allows it; else the refusal toast (H17). Returns the new weapon
     * attack, null when none was queued.
     */
    private @Nullable EntityAction shoot(WeaponDeclarationDisplay fd, Entity actor, WeaponMounted weapon,
          Targetable target) {
        show(fd, actor, weapon);
        setTarget(fd, target);
        if (!fd.isFireAllowed()) {
            toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.refused", weapon.getPlainDesc(), name(target),
                  fd.toHitFor(weapon, target).getDesc());
            return null;
        }
        List<EntityAction> before = fd.getAttacks();
        fd.fire();
        return fd.getAttacks().stream().filter(action -> (action instanceof WeaponAttackAction)
              && before.stream().noneMatch(old -> old == action)).findFirst().orElse(null);
    }

    /**
     * Gives the display the queue in the canonical order (A.8). The primary target of before the command ({@code lead},
     * null for none) keeps its attacks first while it has any and the rules keep it primary, as the prototype keeps
     * its primary; otherwise the targets the rules make primary lead (a target in the front arc beats one beside).
     */
    private void order(WeaponDeclarationDisplay fd, Entity actor, List<EntityAction> queue, @Nullable TargetKey lead) {
        Game game = source.currentView().game;
        boolean leads = (lead != null) && queue.stream().anyMatch(action -> isOrder(action, lead));
        replace(fd, leads ? canonical(game, actor, queue, lead) : queue);
        if (!leads || !primary(game, actor, lead)) {
            replace(fd, canonical(game, actor, fd.getAttacks(), null));
        }
    }

    /**
     * The canonical order: the weapon attacks grouped by target, {@code lead}'s group first (null: the groups the rules
     * make primary now), then the others by letter, each group in its queue order; every other action keeps its place.
     */
    private List<EntityAction> canonical(Game game, Entity actor, List<EntityAction> queue,
          @Nullable TargetKey lead) {
        List<TargetKey> lettered = letters(actor, queue);
        Map<TargetKey, Integer> rank = new HashMap<>();
        for (int index = 0; index < lettered.size(); index++) {
            TargetKey key = lettered.get(index);
            boolean leading = (lead != null) ? lead.equals(key) : primary(game, actor, key);
            rank.put(key, (leading ? 0 : lettered.size()) + index);
        }
        List<Integer> slots = new ArrayList<>();
        List<EntityAction> orders = new ArrayList<>();
        for (int index = 0; index < queue.size(); index++) {
            if (isOrder(queue.get(index), null)) {
                slots.add(index);
                orders.add(queue.get(index));
            }
        }
        orders.sort(Comparator.comparingInt(action -> rank.get(TargetKey.of((WeaponAttackAction) action))));
        List<EntityAction> canonical = new ArrayList<>(queue);
        for (int index = 0; index < slots.size(); index++) {
            canonical.set(slots.get(index), orders.get(index));
        }
        return canonical;
    }

    /** Requeues the actions in that order unless the queue has it already. */
    private static void replace(WeaponDeclarationDisplay fd, List<EntityAction> queue) {
        if (!queue.equals(fd.getAttacks())) {
            fd.replaceAttacks(queue);
        }
    }

    /** The target of the first attack in the queue whose target the rules make primary, or null. */
    private static @Nullable TargetKey lead(Game game, Entity actor, List<EntityAction> queue) {
        return queue.stream().filter(action -> isOrder(action, null))
              .map(action -> TargetKey.of((WeaponAttackAction) action))
              .filter(key -> primary(game, actor, key)).findFirst().orElse(null);
    }

    /** Whether the rules make the target the actor's primary target (Compute.getSecondaryTargetMod gives none). */
    private static boolean primary(Game game, Entity actor, TargetKey key) {
        Targetable target = resolve(game, key);
        return (target != null) && (Compute.getSecondaryTargetMod(game, actor, target) == null);
    }

    /**
     * Loads the bin by its number as a pick in the Unit Display's ammunition list does ({@code WeaponDisplayData.loadAmmo};
     * the list itself cannot pick the second of two bins with one label), then shows it in that list and lets the
     * display roll again with it, as the pick does; false when the list does not offer the bin or the weapon did not
     * load it.
     */
    private boolean load(WeaponDeclarationDisplay fd, Entity actor, WeaponMounted weapon, @Nullable AmmoMounted bin) {
        show(fd, actor, weapon);
        if ((bin == null) || !bins(actor, weapon).contains(bin)) {
            return false;
        }
        WeaponMounted member = GpuUnitRecord.ammoWeapon(weapon);
        WeaponDisplayData.loadAmmo(((ClientGUI) fd.getClientGUI()).getClient(), actor, weapon, member, bin);
        weaponState(fd).updateForEntity(actor);
        fd.updateTarget();
        return member.getLinkedAmmo() == bin;
    }

    /**
     * The key's target: an identified unit while the actor may target it (Game.getValidTargets), a sensor contact
     * getting a toast instead, or the hex, building or minefield the game resolves; else null.
     */
    private @Nullable Targetable target(WeaponDeclarationDisplay fd, Entity actor, TargetKey key) {
        Game game = source.currentView().game;
        if (key.type() != Targetable.TYPE_ENTITY) {
            return resolve(game, key);
        }
        Entity unit = game.getEntity(key.id());
        if ((unit != null) && source.sensorContact(unit)) {
            toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.contactNotTargetable");
            return null;
        }
        return ((unit != null) && game.getValidTargets(actor).contains(unit)) ? unit : null;
    }

    /** The target the game knows by the key, or null. */
    private static @Nullable Targetable resolve(Game game, TargetKey key) {
        return game.getTarget(key.type(), key.id());
    }

    private static TargetKey key(@Nullable Targetable target) {
        return (target == null) ? TargetKey.NONE : TargetKey.of(target);
    }

    /** A target's name as the orders show it: a unit's short name, else MegaMek's display name. */
    private static String name(Targetable target) {
        return (target instanceof Entity unit) ? unit.getShortName() : target.getDisplayName();
    }

    /** The hex of a target that is no unit while it lies on the shown board, else null (the scene places units). */
    private @Nullable Coords hex(Targetable target) {
        return ((target instanceof Entity) || (target.getBoardId() != source.currentView().getBoardId())) ? null
              : target.getPosition();
    }

    /** Targets the target unless the display targets it already (targeting again reopens the aimed shot dialog). */
    private static void setTarget(WeaponDeclarationDisplay fd, Targetable target) {
        if (!target.equals(fd.getTarget())) {
            fd.target(target);
        }
    }

    /** The Unit Display shows the actor with the weapon selected (null: none). */
    private static void show(WeaponDeclarationDisplay fd, Entity actor, @Nullable WeaponMounted weapon) {
        ClientGUI gui = (ClientGUI) fd.getClientGUI();
        if (gui.getUnitDisplayState().getCurrentEntity() != actor) {
            gui.getUnitDisplayState().displayEntity(actor);
        }
        UnitDisplayState weapons = weaponState(fd);
        if (weapon == null) {
            weapons.selectWeapon((WeaponMounted) null);
        } else if (weapons.getSelectedWeapon() != weapon) {
            weapons.selectWeapon(weapon);
        }
    }

    /** The phase command's button while it is enabled, else null (FiringDisplay keeps the enabled states). */
    private static @Nullable MegaMekButton enabled(WeaponDeclarationDisplay fd, FiringCommand command) {
        return fd.getActionButtons().stream().filter(button -> command.getCmd().equals(button.getActionCommand())
              && button.isEnabled()).findFirst().orElse(null);
    }

    private static void toast(WeaponDeclarationDisplay fd, ToastLevel level, String key, Object... args) {
        if (fd.getClientGUI() instanceof ClientGUI gui) {
            gui.addToast(level, (args.length == 0) ? Messages.getString(key) : Messages.getString(key, args));
        }
    }

    // ------------------------------------------------------------------ drafts (H33) and resolve (H6)

    /** Forgets the drafts, the turn's notes, the entry toast and the resolve mode when the phase or round changes. */
    private void forget(Game game) {
        if ((game.getRoundCount() != draftRound) || (game.getPhase() != draftPhase)) {
            draftRound = game.getRoundCount();
            draftPhase = game.getPhase();
            drafts.clear();
            resolving = false;
            endedTurn = NO_TURN;
            trackedActor = Entity.NONE;
            entered = false;
        }
    }

    /**
     * EDT, where no operation of the display is half-done (the end of a command, or an event of its own): compared
     * with the last note, a unit that became the actor within the turn (a switch the display made itself, as Next unit
     * does) leaves the former actor's orders as its draft, and an actor deselected within the turn while still in the
     * game ends the turn, its declaration sent. Notes the actor, the turn and the orders, and drops the drafts of units
     * that no longer declare in this phase. True when the drafts or the turn's end changed.
     */
    private boolean track(Game game, @Nullable WeaponDeclarationDisplay fd) {
        forget(game);
        Entity actor = acting(fd);
        int id = (actor == null) ? Entity.NONE : actor.getId();
        boolean changed = false;
        if ((id != trackedActor) && (trackedActor != Entity.NONE) && (game.getTurnIndex() == trackedTurn)) {
            if (id != Entity.NONE) {
                save(trackedActor, tracked);
            } else if (game.getEntity(trackedActor) != null) {
                endedTurn = trackedTurn;
            }
            changed = true;
        }
        trackedActor = id;
        trackedTurn = game.getTurnIndex();
        tracked = new Draft((actor == null) ? List.of() : fd.getAttacks(),
              (lettersActor == id) ? Map.copyOf(serials) : Map.of());
        return drafts.keySet().removeIf(unit -> !declares(game.getEntity(unit))) || changed;
    }

    /**
     * Keeps the unit's orders, with its targets' letters, as its draft. Without orders it keeps a draft the unit has
     * not got back yet: only the display's own queue holds a draft that came back, and the player may clear it there.
     */
    private void save(int unitId, Draft orders) {
        if (!orders.actions().isEmpty()) {
            drafts.put(unitId, orders);
        }
    }

    /**
     * EDT, in an event of its own after a capture: notes the turn's state ({@link #track}) and gives the actor its
     * draft back, then republishes when that changed anything; the phase's first local declaration begins with the
     * entry toast (H1). Waits while a command runs, a dialog is pending or the display ignores input; a later capture
     * asks again.
     */
    private void trackJob() {
        trackQueued = false;
        WeaponDeclarationDisplay fd = display;
        if (commanding || !source.acceptsInput() || ((fd != null) && fd.isIgnoringEvents())) {
            return;
        }
        Game game = source.currentView().game;
        boolean changed = track(game, fd);
        Entity actor = acting(fd);
        if ((actor != null) && !entered) {
            entered = true;
            toast(fd, ToastLevel.INFO, "GpuBoard.hud.fire.entry");
        }
        if ((actor != null) && restoreDraft(fd, actor)) {
            track(game, fd);
            changed = true;
        }
        if (changed) {
            source.refresh();
        }
    }

    /**
     * Gives the actor its draft back with the display's replaceAttacks while its queue is empty (a draft never joins
     * orders given since): the same action objects in their order, so the weapons, ammunition, aim, twist and arms are
     * the draft's, with its letters. A weapon attack that can no longer be declared stays out: its target left the
     * game or the actor's valid targets, or its own roll is impossible now; a toast counts those. True when a draft
     * came back.
     */
    private boolean restoreDraft(WeaponDeclarationDisplay fd, Entity actor) {
        Draft draft = drafts.remove(actor.getId());
        if ((draft == null) || !fd.getAttacks().isEmpty()) {
            return false;
        }
        Game game = source.currentView().game;
        List<EntityAction> kept;
        // Captures inside the display's operations keep the last snapshot, as during a command.
        boolean wasCommanding = commanding;
        commanding = true;
        try {
            // The display queues an action only while its target is in the game. The draft's twist comes back with
            // it, so the rolls below are made in the draft's arcs.
            fd.replaceAttacks(draft.actions().stream().filter(action -> !(action instanceof AbstractAttackAction attack)
                  || (attack.getTarget(game) != null)).toList());
            List<Entity> valid = game.getValidTargets(actor);
            List<EntityAction> queue = fd.getAttacks();
            kept = queue.stream().filter(action -> !(action instanceof WeaponAttackAction attack)
                  || declarable(game, valid, attack)).toList();
            if (kept.size() < queue.size()) {
                fd.replaceAttacks(kept);
            }
        } finally {
            commanding = wasCommanding;
        }
        lettersActor = actor.getId();
        serials.clear();
        serials.putAll(draft.serials());
        int dropped = attacks(draft.actions()) - attacks(kept);
        if (dropped > 0) {
            toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.draftDropped", actor.getShortName(), dropped);
        }
        return true;
    }

    /**
     * Whether the queued attack may still be declared: its target is in the game and, for a unit, among the actor's
     * valid targets, and its own roll (with its aim and ammunition, as the snapshot shows it) is possible.
     */
    private static boolean declarable(Game game, List<Entity> valid, WeaponAttackAction attack) {
        Targetable target = attack.getTarget(game);
        return (target != null) && (!(target instanceof Entity unit) || valid.contains(unit))
              && (attack.toHit(game, true).getValue() != TargetRoll.IMPOSSIBLE);
    }

    /** The weapon attacks of each draft but the actor's ({@code actorId}); drafts without any are left out. */
    private Map<Integer, Integer> drafted(int actorId) {
        Map<Integer, Integer> drafted = new HashMap<>();
        drafts.forEach((id, draft) -> {
            int attacks = attacks(draft.actions());
            if ((id != actorId) && (attacks > 0)) {
                drafted.put(id, attacks);
            }
        });
        return drafted;
    }

    /**
     * H33: the unit's draft read-only, while the local player declares nothing (another player's firing turn, or the
     * local turn whose declaration was sent): its weapon attacks in fire order whose target is in the game, each with
     * its own roll as the unit stands now; null when it has none. The draft's twist and secondary-target modifiers
     * apply once it is queued again, so these rolls leave them out.
     */
    private @Nullable Snapshot readOnly(Game game, int unitId, int remaining) {
        Draft draft = drafts.get(unitId);
        Entity unit = game.getEntity(unitId);
        if ((draft == null) || (unit == null)) {
            return null;
        }
        List<Attack> attacks = new ArrayList<>();
        for (EntityAction action : draft.actions()) {
            if ((action instanceof WeaponAttackAction attack) && (attack.getTarget(game) != null)) {
                attacks.add(attack(game, unit, attack, attack.toHit(game, true),
                      unit.isUseNaturalAptitudeGunnery(game, attack)));
            }
        }
        return attacks.isEmpty() ? null : new Snapshot(true, false, unitId, Focus.NONE, -1, List.of(), List.of(),
              attacks, 0, false, false, "", null, null, null, List.of(), null, drafted(unitId),
              GpuBattleStatus.unitsToAct(game, source.currentView().getLocalPlayer()), remaining, null);
    }

    private static int attacks(List<EntityAction> actions) {
        return (int) actions.stream().filter(WeaponAttackAction.class::isInstance).count();
    }

    /** Whether the unit still declares attacks in this phase: MegaMek has not marked it done (as Game counts left). */
    private static boolean declares(@Nullable Entity unit) {
        return (unit != null) && unit.isSelectableThisTurn();
    }

    /**
     * H6, EDT at every capture: ends the resolve mode once no own unit is left to declare (track ends it with its
     * phase); on a new own turn it asks for that turn's declaration in an event of its own, never inside a game event
     * this capture may run in (B.1 rule 4). Returns the own units left to declare, 0 when the mode is off.
     */
    private int resolve(Game game, @Nullable WeaponDeclarationDisplay fd) {
        int left = resolving ? GpuBattleStatus.unitsToAct(game, source.currentView().getLocalPlayer()) : 0;
        if (left == 0) {
            resolving = false;
            return 0;
        }
        if ((fd != null) && !resolveQueued && (game.getTurnIndex() != resolvedTurn) && firingTurn(fd)) {
            resolveQueued = true;
            SwingUtilities.invokeLater(() -> {
                if (declareTurn(fd)) {
                    source.refresh();
                }
            });
        }
        return left;
    }

    /**
     * H6, EDT outside any game event: in a new own turn of the resolved phase, declares the display's unit (MegaMek's
     * Next unit picks one when the turn began without) with its draft, through the buttons the dock's main button
     * presses: Done with orders, else Hold fire (GpuBoardActions.skipButton; Done when MegaMek hides Skip). Waits
     * while a dialog is pending or the display ignores input; a later capture asks again. A unit that cannot declare
     * (its button disabled, or the click left its turn open, as a declined prompt does) ends the mode with a toast.
     * True when it declared a unit or ended the mode.
     */
    private boolean declareTurn(WeaponDeclarationDisplay fd) {
        resolveQueued = false;
        Game game = source.currentView().game;
        if (!resolving || commanding || !source.acceptsInput() || fd.isIgnoringEvents() || !firingTurn(fd)
              || (game.getTurnIndex() == resolvedTurn) || (game.getRoundCount() != draftRound)
              || (game.getPhase() != draftPhase)) {
            return false;
        }
        resolvedTurn = game.getTurnIndex();
        if (fd.currentEntity() == null) {
            // MegaMek starts a turn without a unit when its "auto-select next unit" is off; Next unit picks one.
            MegaMekButton next = enabled(fd, FiringCommand.FIRE_NEXT);
            if (next != null) {
                next.doClick(0);
            }
        }
        Entity unit = fd.currentEntity();
        if (unit == null) {
            return false;
        }
        commanding = true;
        try {
            restoreDraft(fd, unit);
            MegaMekButton skip = (fd instanceof AbstractPhaseDisplay phase) ? GpuBoardActions.skipButton(phase) : null;
            MegaMekButton button = (fd.getAttacks().isEmpty() && (skip != null)) ? skip : fd.getButDone();
            if (button.isEnabled()) {
                button.doClick(0);
            }
        } finally {
            commanding = false;
        }
        if (fd.currentEntity() == null) {
            // Sent: the client keeps this turn until the server answers.
            endedTurn = resolvedTurn;
        } else if (resolving) {
            resolving = false;
            toast(fd, ToastLevel.WARNING, "GpuBoard.hud.fire.resolveStopped", unit.getShortName());
        }
        return true;
    }

    // ------------------------------------------------------------------ state

    /** EDT: the display's actor while the local player declares its attacks in the firing phase, else null. */
    private @Nullable Entity acting(@Nullable WeaponDeclarationDisplay fd) {
        return firingTurn(fd) ? fd.currentEntity() : null;
    }

    /**
     * The local turn to declare weapon attacks, until its declaration was sent (the client keeps the turn until the
     * server answers): in the Firing phase, or in the Targeting or Off-board phase for artillery and TAG.
     */
    private boolean firingTurn(@Nullable WeaponDeclarationDisplay fd) {
        Game game = source.currentView().game;
        return (fd != null) && declaresWeapons(game.getPhase()) && fd.getClientGUI().getClient().isMyTurn()
              && (game.getTurnIndex() != endedTurn);
    }

    /** The phases in which a unit declares weapon attacks through a {@link WeaponDeclarationDisplay}. */
    static boolean declaresWeapons(GamePhase phase) {
        return phase.isFiring() || phase.isTargeting() || phase.isOffboard();
    }

    private Snapshot publish(Snapshot next) {
        if (!next.equals(published)) {
            published = next;
        }
        return published;
    }

    /**
     * The actor's targets in letter order. A target keeps the serial of its first assignment while it has attacks;
     * a new target gets the next serial (the prototype's targetsOf, game.js:241).
     */
    private List<TargetKey> letters(Entity actor, List<EntityAction> queue) {
        if (actor.getId() != lettersActor) {
            lettersActor = actor.getId();
            serials.clear();
        }
        Set<TargetKey> present = new LinkedHashSet<>();
        for (EntityAction action : queue) {
            if (isOrder(action, null)) {
                present.add(TargetKey.of((WeaponAttackAction) action));
            }
        }
        serials.keySet().retainAll(present);
        for (TargetKey target : present) {
            serials.computeIfAbsent(target, key -> ++serial);
        }
        return present.stream().sorted(Comparator.comparingInt(serials::get)).toList();
    }

    /** A weapon attack ({@code key} null), or one on that target. */
    private static boolean isOrder(EntityAction action, @Nullable TargetKey key) {
        return (action instanceof WeaponAttackAction attack) && ((key == null) || TargetKey.of(attack).equals(key));
    }

    /** The actor's queued attack with its weapon {@code eqNum}, or null. */
    private static @Nullable WeaponAttackAction queued(List<EntityAction> queue, Entity actor, int eqNum) {
        for (EntityAction action : queue) {
            if ((action instanceof WeaponAttackAction attack) && (attack.getEntityId() == actor.getId())
                  && (attack.getWeaponId() == eqNum)) {
                return attack;
            }
        }
        return null;
    }

    /** The display's target, unless it is a unit the local player may not identify; else null. */
    private @Nullable Targetable focus(WeaponDeclarationDisplay fd) {
        Targetable target = fd.getTarget();
        return ((target instanceof Entity unit) && !source.identified(unit)) ? null : target;
    }

    /**
     * The actor's listed weapons that can be declared in this phase: in the Targeting phase only its artillery and
     * capital weapons, in the Off-board phase only its TAG, otherwise all of them. The rest cannot fire now, so the
     * panel does not offer them.
     */
    private List<WeaponMounted> declarableWeapons(Game game, Entity actor) {
        List<WeaponMounted> listed = WeaponDisplayData.listedWeapons(actor);
        List<WeaponMounted> declarable = new ArrayList<>();
        for (WeaponMounted weapon : listed) {
            if (WeaponDisplayData.isDeclarableIn(game.getPhase(), weapon)) {
                declarable.add(weapon);
            }
        }
        int hidden = listed.size() - declarable.size();
        if ((hidden != lastHiddenWeapons) || (actor.getId() != lastHiddenActor)) {
            lastHiddenWeapons = hidden;
            lastHiddenActor = actor.getId();
            LOGGER.debug("[FireOrders] {} in {}: {} of {} weapons can fire in this phase, {} hidden",
                  actor.getShortName(), game.getPhase(), declarable.size(), listed.size(), hidden);
        }
        return declarable;
    }

    /** The actor's listed weapon with that number, or null. */
    private static @Nullable WeaponMounted weapon(Entity actor, int eqNum) {
        return ((eqNum >= 0) && (actor.getEquipment(eqNum) instanceof WeaponMounted weapon)
              && WeaponDisplayData.listedWeapons(actor).contains(weapon)) ? weapon : null;
    }

    private static UnitDisplayState weaponState(WeaponDeclarationDisplay fd) {
        return ((ClientGUI) fd.getClientGUI()).getUnitDisplayState();
    }

    /** The actor's weapon the Unit Display selects, or null (another unit shown, or none selected). */
    private static @Nullable WeaponMounted selectedWeapon(WeaponDeclarationDisplay fd, Entity actor) {
        UnitDisplayState weapons = weaponState(fd);
        WeaponMounted weapon = (weapons.getSelectedEntityId() == actor.getId()) ? weapons.getSelectedWeapon() : null;
        return ((weapon != null) && (weapon.getEntity() == actor)) ? weapon : null;
    }

    /** The bins the Unit Display's ammunition list offers for the weapon, in its order. */
    private static List<AmmoMounted> bins(Entity actor, WeaponMounted weapon) {
        return WeaponDisplayData.ammoChoices(actor, weapon, GpuUnitRecord.ammoWeapon(weapon)).ammo();
    }

    private static boolean canTwist(Entity actor, int direction) {
        int secondary = actor.getSecondaryFacing();
        return actor.canTwistNow() && !actor.isHidden()
              && (actor.clipSecondaryFacing((secondary + ((direction < 0) ? 5 : 7)) % 6) != secondary);
    }

    private static boolean inFrontArc(Entity actor, Targetable target) {
        return ComputeArc.isInArc(actor.getPosition(), actor.getSecondaryFacing(), target, actor.getForwardArc());
    }

    // ------------------------------------------------------------------ snapshot parts

    private static WeaponRow row(Game game, Entity actor, WeaponMounted weapon, int eqNum, Shot shot, boolean queued,
          boolean aptitude) {
        UnitDisplayData.RowParts parts = UnitDisplayData.rowParts(game, weapon);
        AmmoMounted loaded = GpuUnitRecord.ammoWeapon(weapon).getLinkedAmmo();
        List<AmmoMounted> bins = bins(actor, weapon);
        if (bins.isEmpty() && (loaded != null)) {
            // A weapon that cannot switch shows its one bin.
            bins = List.of(loaded);
        }
        int location = weapon.getLocation();
        return new WeaponRow(eqNum, weapon.getPlainDesc(), parts.location(), kind(weapon.getType()),
              damage(game, actor, weapon), weapon.getCurrentHeat(), (parts.mode() == null) ? "" : parts.mode(),
              bins.stream().map(bin -> GpuUnitRecord.AmmoChoice.of(actor, bin)).toList(),
              (loaded == null) ? -1 : bins.indexOf(loaded),
              parts.loadedShots(), shot.target(), value(shot.toHit()), odds(shot.toHit(), aptitude),
              reason(shot.toHit()), queued || actor.isWeaponValidForPhase(weapon),
              (location >= 0) && actor.isLocationBad(location));
    }

    /**
     * R4: the choice the aimed shot handler offers on the display's unit target in place of its dialog, with the
     * location it aims at and the actor's listed weapons that may aim; null while it offers none.
     */
    private static @Nullable Aim aim(WeaponDeclarationDisplay fd, Entity actor) {
        AimedShotHandler aims = fd.getAimedShotHandler();
        AimedShotHandler.Offer offer = (aims == null) ? null : aims.getOffer();
        if ((offer == null) || !(fd.getTarget() instanceof Entity)) {
            return null;
        }
        Set<Integer> weapons = new LinkedHashSet<>();
        for (WeaponMounted weapon : WeaponDisplayData.listedWeapons(actor)) {
            if ((weapon.getEntity() == actor) && aims.allowAimedShotWith(weapon)) {
                weapons.add(actor.getEquipmentNum(weapon));
            }
        }
        return new Aim(offer.locations(), offer.enabled(), aims.getAimingAt(), weapons);
    }

    private static Attack attack(Game game, Entity actor, WeaponAttackAction attack, ToHitData toHit,
          boolean aptitude) {
        Entity carrier = attack.getEntity(game);
        WeaponMounted weapon = (WeaponMounted) carrier.getEquipment(attack.getWeaponId());
        Entity ammoCarrier = game.getEntity(attack.getAmmoCarrier());
        AmmoMounted bin = (attack.getAmmoId() < 0) ? null
              : (AmmoMounted) ((ammoCarrier == null) ? carrier : ammoCarrier).getEquipment(attack.getAmmoId());
        return new Attack((carrier == actor) ? attack.getWeaponId() : -1, TargetKey.of(attack), weapon.getPlainDesc(),
              UnitDisplayData.rowParts(game, weapon).location(), kind(weapon.getType()),
              (bin == null) ? "" : WeaponDisplayData.formatAmmo(actor, bin), (bin == null) ? -1 : bin.getUsableShotsLeft(),
              toHit.getValue(), odds(toHit, aptitude), toHit.getDesc());
    }

    /** "Energy", "Missile", "Ballistic" from the weapon type's flags (H26), else "". */
    private static String kind(WeaponType type) {
        return type.hasFlag(WeaponType.F_ENERGY) ? "Energy" : type.hasFlag(WeaponType.F_MISSILE) ? "Missile"
              : type.hasFlag(WeaponType.F_BALLISTIC) ? "Ballistic" : "";
    }

    /**
     * The damage per hit and rack size of a cluster weapon with ammunition ("{per hit}x{rack}"; the damage per hit is
     * the ammunition's damage per shot, as Compute.getExpectedDamage counts it), else the Unit Display's damage text.
     */
    private static String damage(Game game, Entity actor, WeaponMounted weapon) {
        String text = WeaponDisplayData.damageText(game, actor, weapon);
        AmmoMounted ammo = weapon.getLinkedAmmo();
        boolean aerospace = text.equals(Messages.getString("MekDisplay.StandardD"))
              || text.equals(Messages.getString("MekDisplay.CapitalD"));
        return ((weapon.getType().getDamage() == WeaponType.DAMAGE_BY_CLUSTER_TABLE) && !aerospace && (ammo != null))
              ? ammo.getType().getDamagePerShot() + "\u00D7" + weapon.getType().getRackSize() : text;
    }

    private static @Nullable Heat heat(Game game, Entity actor) {
        if (actor.getHeatCapacity() == Entity.DOES_NOT_TRACK_HEAT) {
            return null;
        }
        UnitToolTip.HeatDisplayHelper capacity = UnitToolTip.getHeatCapacityForDisplay(actor);
        int after = WeaponDisplayData.heatBuildup(game, actor).value();
        int end = Math.max(0, after - capacity.heatCapWater);
        List<Integer> ticks = GpuUnitRecord.heatTicks(game, actor);
        // Below the first tick the heat table has the effects of heat 0, which are none.
        String effects = (ticks.isEmpty() || (end < ticks.getFirst())) ? ""
              : HeatEffects.getHeatEffects(end,
                    game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT),
                    ((Mek) actor).hasTSM(false));
        return new Heat(actor.heat, after, capacity.heatCapacityStr, end, effects, ticks,
              GpuUnitRecord.heatScale(ticks));
    }

    private Solution solution(Game game, Entity actor, WeaponMounted weapon, int eqNum, Shot shot, boolean shown,
          boolean aptitude) {
        int[] brackets = weapon.getType().getRanges(weapon, weapon.getLinkedAmmo());
        List<Integer> ranges = new ArrayList<>();
        int last = WeaponDisplayData.showsExtremeRange(game, actor) ? RangeType.RANGE_EXTREME : RangeType.RANGE_LONG;
        for (int bracket = RangeType.RANGE_MINIMUM; bracket <= last; bracket++) {
            // A weapon without a minimum range has a negative one (WeaponType.WEAPON_NA)
            ranges.add(Math.max(0, brackets[bracket]));
        }
        Targetable target = resolve(game, shot.target());
        ToHitData toHit = shot.toHit();
        List<Modifier> modifiers = new ArrayList<>();
        if ((toHit != null) && toHit.needsRoll()) {
            for (TargetRollModifier modifier : toHit.getModifiers()) {
                modifiers.add(new Modifier(modifier.getDesc(), modifier.value()));
            }
        }
        int arc = actor.getWeaponArc(eqNum);
        FacingArc shape = FacingArc.valueOf(arc);
        return new Solution(eqNum, shot.target(), ranges, arcName(arc),
              (target == null) ? 0 : Compute.effectiveDistance(game, actor, target), modifiers, value(toHit),
              odds(toHit, aptitude), reason(toHit), shown ? new WeaponArc(actor.getPosition(),
              TurretFacing.weaponFacing(actor, eqNum), shape.getStartAngle(), shape.getEndAngle()) : null);
    }

    /** The name of a mount arc for the solution card; "" for the arcs without one. */
    private static String arcName(int arc) {
        String key = switch (arc) {
            case Compute.ARC_FORWARD -> "arcForward";
            case Compute.ARC_LEFT_ARM -> "arcLeftArm";
            case Compute.ARC_RIGHT_ARM -> "arcRightArm";
            case Compute.ARC_REAR, Compute.ARC_AFT -> "arcRear";
            case Compute.ARC_360 -> "arcAll";
            case Compute.ARC_TURRET -> "arcTurret";
            case Compute.ARC_LEFT_SIDE -> "arcLeftSide";
            case Compute.ARC_RIGHT_SIDE -> "arcRightSide";
            case Compute.ARC_NOSE -> "arcNose";
            case Compute.ARC_LEFT_WING -> "arcLeftWing";
            case Compute.ARC_RIGHT_WING -> "arcRightWing";
            default -> null;
        };
        return (key == null) ? "" : Messages.getString("GpuBoard.hud.solution." + key);
    }

    /** The hexes up to FRONT_ARC_RADIUS on the actor's board in its forward arc (the primary-target arc). */
    private static FrontArc frontArc(Game game, Entity actor) {
        Coords origin = actor.getPosition();
        Set<Coords> hexes = new LinkedHashSet<>();
        for (Coords hex : origin.allAtDistanceOrLess(FRONT_ARC_RADIUS)) {
            if (!hex.equals(origin) && game.getBoard(actor).contains(hex)
                  && ComputeArc.isInArc(origin, actor.getSecondaryFacing(), hex, actor.getForwardArc())) {
                hexes.add(hex);
            }
        }
        return new FrontArc(origin, actor.getSecondaryFacing(), hexes);
    }

    /**
     * The selected weapon's rolls on the identified enemies in its long range that are neither targets nor the focus
     * (H23), best first, at most BADGES.
     */
    private List<Badge> rolls(Game game, WeaponDeclarationDisplay fd, Entity actor, WeaponMounted weapon,
          List<EntityAction> queue, Set<TargetKey> carded) {
        int range = weapon.getType().getRanges(weapon, weapon.getLinkedAmmo())[RangeType.RANGE_LONG];
        boolean aptitude = actor.isUseNaturalAptitudeGunnery(game, weapon);
        List<Badge> rolls = new ArrayList<>();
        for (Entity enemy : game.getValidTargets(actor)) {
            if (actor.isEnemyOf(enemy) && !carded.contains(TargetKey.unit(enemy.getId())) && source.identified(enemy)
                  && (enemy.getBoardId() == actor.getBoardId())
                  && (actor.getPosition().distance(enemy.getPosition()) <= range)) {
                rolls.add(badge(enemy.getId(), rollAt(game, fd, actor, weapon, queue, enemy), aptitude));
            }
        }
        rolls.sort(Comparator.comparingInt(Badge::value));
        return rolls.subList(0, Math.min(BADGES, rolls.size()));
    }

    /**
     * The actor's best roll among its weapons on the identified enemy in the hovered hex that is neither a target nor
     * the focus (P2), with "no shot" when none can hit; null without one.
     */
    private @Nullable Badge hoverBest(Game game, WeaponDeclarationDisplay fd, Entity actor, @Nullable Coords hover,
          List<EntityAction> queue, Set<TargetKey> carded) {
        int board = source.currentView().getBoardId();
        Entity enemy = (hover == null) ? null : game.getValidTargets(actor).stream()
              .filter(unit -> hover.equals(unit.getPosition()) && (unit.getBoardId() == board)
                    && actor.isEnemyOf(unit) && !carded.contains(TargetKey.unit(unit.getId()))
                    && source.identified(unit))
              .findFirst().orElse(null);
        if (enemy == null) {
            return null;
        }
        Badge best = null;
        for (WeaponMounted weapon : WeaponDisplayData.listedWeapons(actor)) {
            if (weapon.getEntity() == actor) {
                Badge roll = badge(enemy.getId(), rollAt(game, fd, actor, weapon, queue, enemy),
                      actor.isUseNaturalAptitudeGunnery(game, weapon));
                if (roll.reason().isEmpty() && ((best == null) || (roll.value() < best.value()))) {
                    best = roll;
                }
            }
        }
        return (best != null) ? best : new Badge(enemy.getId(), TargetRoll.IMPOSSIBLE, 0,
              Messages.getString("GpuBoard.hud.fire.noShot"));
    }

    /**
     * The weapon's roll on the unit: as Fire would declare it, or for a queued weapon as its declaration there would
     * roll (WeaponAttackAction.toHit even if already fired, as its queued attack's own roll).
     */
    private static ToHitData rollAt(Game game, WeaponDeclarationDisplay fd, Entity actor, WeaponMounted weapon,
          List<EntityAction> queue, Entity unit) {
        int eqNum = actor.getEquipmentNum(weapon);
        return (queued(queue, actor, eqNum) == null) ? fd.toHitFor(weapon, unit)
              : new WeaponAttackAction(actor.getId(), Targetable.TYPE_ENTITY, unit.getId(), eqNum).toHit(game, true);
    }

    private static Badge badge(int targetId, ToHitData toHit, boolean aptitude) {
        return new Badge(targetId, toHit.getValue(), odds(toHit, aptitude), reason(toHit));
    }

    private static int value(@Nullable ToHitData toHit) {
        return (toHit == null) ? TargetRoll.IMPOSSIBLE : toHit.getValue();
    }

    /** The chance in percent to roll the value, as the weapon display gives it (natural aptitude: 3d6). */
    private static double odds(@Nullable ToHitData toHit, boolean aptitude) {
        return (toHit == null) ? 0 : Compute.oddsAbove(toHit.getValue(), aptitude);
    }

    private static String reason(@Nullable ToHitData toHit) {
        return ((toHit != null) && (toHit.getValue() == TargetRoll.IMPOSSIBLE)) ? toHit.getDesc() : "";
    }

    private static String signed(int value) {
        return (value > 0) ? ("+" + value) : Integer.toString(value);
    }
}
