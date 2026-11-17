/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import jakarta.annotation.Nullable;
import megamek.common.ECMInfo;
import megamek.common.Hex;
import megamek.common.Messages;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeECM;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.moves.ClimbingHelper;
import megamek.common.moves.MovePath;
import megamek.common.moves.MoveStep;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.capitalWeapons.CapitalMissileWeapon;

/**
 * Movement fire preview: real to-hit numbers, from the unchanged declaration code, for a unit at the end of its plotted
 * path (OUTGOING) and for enemies against it there (INCOMING). The unit ends the move with the location exposure the
 * server sets there (WaterExposure), so fire from and into water is previewed too. An empty plan evaluates the unit
 * where it stands with its current movement state; like the server's stand-still commit, it only resets a twist left
 * from the previous round. {@link #computeInPlace} evaluates a unit that is not plotting a move where it stands.
 * Movement phase only (Reason.NOT_MOVEMENT_PHASE otherwise). Presentation only: never changes game state, fires events,
 * sends packets or adds actions; the unit is placed transiently for one synchronous call and put back (see
 * HypotheticalState). Call on the thread that owns the game (the Swing EDT for a client); never on a thread whose game
 * other threads read, such as a bot's calculation thread. The numbers are "if the plan succeeds" and first-declaration
 * numbers: no PSR outcome, no secondary-target modifier (no attack is declared in the movement phase), and the enemies
 * as they stand now.
 */
public final class FirePreview {
    /** Steps whose effect the end state fully captures; any other step (including new ones) is not previewed. */
    private static final Set<MoveStepType> SUPPORTED_STEPS = EnumSet.of(MoveStepType.FORWARDS,
          MoveStepType.BACKWARDS, MoveStepType.TURN_LEFT, MoveStepType.TURN_RIGHT, MoveStepType.LATERAL_LEFT,
          MoveStepType.LATERAL_RIGHT, MoveStepType.LATERAL_LEFT_BACKWARDS, MoveStepType.LATERAL_RIGHT_BACKWARDS,
          MoveStepType.START_JUMP, MoveStepType.UP, MoveStepType.DOWN, MoveStepType.GET_UP, MoveStepType.CAREFUL_STAND,
          MoveStepType.GO_PRONE, MoveStepType.HULL_DOWN, MoveStepType.CLIMB_MODE_ON, MoveStepType.CLIMB_MODE_OFF,
          MoveStepType.EVADE);

    /** Why something was not previewed. */
    public enum Reason {
        UNSUPPORTED_UNIT, UNSUPPORTED_STATE, UNSUPPORTED_STEP, ILLEGAL_PATH, STALE_PLAN, NOT_MOVEMENT_PHASE,
        OTHER_BOARD, NOT_A_TARGET, FIRES_AUTOMATICALLY, OTHER_PHASE_WEAPON, DUMPING_AMMO, WALK_ON;

        /** The localized explanation. */
        public String description() {
            return Messages.getString("FirePreview.Reason." + name());
        }
    }

    /**
     * The state the server commits if the plan succeeds; a value, so it can also serve as a cache key. For an empty
     * plan it is the unit's current state (the server commits an empty move unchanged).
     */
    public record End(@Nullable Coords position, int boardId, int facing, int elevation, boolean prone,
          boolean hullDown, boolean evading, int braceLocation, EntityMovementType moved, int hexesMoved, int mpUsed) {

        /** Reads the compiled path (an empty one: the unit's current state); never recompiles it or writes its unit. */
        public static End of(MovePath plan) {
            Entity unit = plan.getEntity();
            if (plan.length() == 0) {
                return current(unit);
            }
            return new End(plan.getFinalCoords(), plan.getFinalBoardId(), plan.getFinalFacing(),
                  plan.getFinalElevation(), plan.getFinalProne(), plan.getFinalHullDown(),
                  plan.contains(MoveStepType.EVADE) || unit.isEvading(),
                  // The server ends bracing as soon as the move spends MP; standing still keeps it.
                  (plan.getMpUsed() > 0) ? Entity.LOC_NONE : unit.braceLocation(),
                  plan.getLastStepMovementType(), plan.getHexesMoved(), plan.getMpUsed());
        }

        /** The unit's live state: where it stands and how it moved this phase so far. */
        public static End current(Entity unit) {
            return new End(unit.getPosition(), unit.getBoardId(), unit.getFacing(), unit.getElevation(),
                  unit.isProne(), unit.isHullDown(), unit.isEvading(), unit.braceLocation(), unit.moved,
                  unit.delta_distance, unit.mpUsed);
        }
    }

    /** One weapon; exactly one of toHit and notPreviewed is non-null. */
    public record Shot(int weaponId, @Nullable ToHitData toHit, @Nullable Reason notPreviewed) {
        /** The roll can succeed: an automatic success, or a value of 12 or less that is not impossible. */
        public boolean available() {
            return (toHit != null) && !toHit.cannotSucceed() && (toHit.getValue() <= 12);
        }
    }

    /** One attacker's whole weapon list against one target at one secondary facing (a salvo never mixes twists). */
    public record Salvo(int secondaryFacing, List<Shot> shots) {
        /** Every previewed shot is available (shots that were not previewed do not count). */
        public boolean allAvailable() {
            return shots.stream().allMatch(shot -> (shot.toHit() == null) || shot.available());
        }
    }

    /** One direction against one enemy: salvos in legal-twist order (forward first), or why none was computed. */
    public record Direction(@Nullable Reason notPreviewed, List<Salvo> salvos) { }

    public record Exchange(int enemyId, Direction outgoing, Direction incoming) { }

    /**
     * notPreviewed != null means the movement modifiers are null, wet is false and exchanges is empty. wet: a location
     * of the unit is under water (exposure WET) in the evaluated state.
     */
    public record Result(End end, @Nullable Reason notPreviewed, @Nullable ToHitData attackerMovement,
          @Nullable ToHitData targetMovement, boolean wet, List<Exchange> exchanges) { }

    private FirePreview() { }

    /** Why the plan cannot be previewed exactly, or null. Cheap: reads the path and the unit. */
    public static @Nullable Reason unsupportedReason(MovePath plan) {
        Game game = plan.getGame();
        Entity planned = plan.getEntity();
        Entity live = game.getEntity(planned.getId());
        if ((live == null) || (live.getGame() != game)
              || ((live != planned) && !planned.movementState().equals(live.movementState()))) {
            return Reason.STALE_PLAN;
        }
        if (!game.getPhase().isMovement()) {
            // Other phases can hold declared twists and attacks: forward would not be the twist a move leaves, and a
            // secondary-target modifier would break the exact early stop of compute.
            return Reason.NOT_MOVEMENT_PHASE;
        }
        // A walk-on move starts off the board, which the hypothetical state cannot stand the unit in.
        if (plan.contains(MoveStepType.DEPLOY)) {
            return Reason.WALK_ON;
        }
        Reason refused = HypotheticalState.refusal(live);
        if (refused != null) {
            return refused;
        }
        // A swarming unit moves with the unit it swarms (MovePathHandler), which the end state does not describe.
        if (live.isHidden() || live.isStuck() || live.isClimbing() || live.isDangling()
              || (live.getTransportId() != Entity.NONE) || (live.getTowing() != Entity.NONE)
              || (live.getTowedBy() != Entity.NONE) || (live.getSwarmAttackerId() != Entity.NONE)
              || (live.getSwarmTargetId() != Entity.NONE)) {
            return Reason.UNSUPPORTED_STATE;
        }
        boolean onlyTurns = true;
        for (MoveStep step : plan.getStepVector()) {
            // The server resolves a TacOps climbing step itself; a partial climb leaves the unit clinging below.
            if (!SUPPORTED_STEPS.contains(step.getType()) || step.isClimbing()) {
                return Reason.UNSUPPORTED_STEP;
            }
            onlyTurns &= (step.getType() == MoveStepType.TURN_LEFT) || (step.getType() == MoveStepType.TURN_RIGHT);
        }
        if (edgeDescent(game, plan, live)) {
            return Reason.UNSUPPORTED_STEP;
        }
        if (!plan.isMoveLegal() || (plan.getLastStepMovementType() == EntityMovementType.MOVE_ILLEGAL)) {
            return Reason.ILLEGAL_PATH;
        }
        // Server-side clearing the end state does not model: moving ends dug-in and hit-the-deck postures, and a
        // tank going hull-down derives whether it backed in.
        if ((live instanceof Infantry infantry) && !onlyTurns
              && (infantry.isHitTheDeck() || (infantry.getDugIn() != Infantry.DUG_IN_NONE))) {
            return Reason.UNSUPPORTED_STATE;
        }
        boolean backsIn = plan.contains(MoveStepType.BACKWARDS) || live.getMovedBackwards();
        if ((live instanceof Tank tank) && plan.contains(MoveStepType.HULL_DOWN)
              && (tank.isBackedIntoHullDown() != backsIn)) {
            return Reason.UNSUPPORTED_STATE;
        }
        if (breachesInWater(game, plan, live)) {
            return Reason.UNSUPPORTED_STATE;
        }
        return null;
    }

    /**
     * Evaluates the given enemies against the plan's unit at the end of the plan, stopping each twist loop at the first
     * twist whose previewed weapons are all available (exact in the movement phase: with no attack declared, a later
     * twist can neither make more weapons available nor lower a value). The caller passes deployed units on the board
     * and decides which of them the player may know about.
     */
    public static Result compute(MovePath plan, List<Entity> enemies) {
        return compute(plan, enemies, false);
    }

    /** As {@link #compute(MovePath, List)}; everyTwist evaluates every legal twist instead of stopping early. */
    public static Result compute(MovePath plan, List<Entity> enemies, boolean everyTwist) {
        End end = End.of(plan);
        Reason refused = unsupportedReason(plan);
        if (refused != null) {
            return new Result(end, refused, null, null, false, List.of());
        }
        Entity mover = plan.getGame().getEntity(plan.getEntity().getId());
        try (HypotheticalState moved = HypotheticalState.atEndOf(mover, end)) {
            return evaluate(moved, mover, end, enemies, everyTwist);
        }
    }

    /**
     * Evaluates the given enemies against a unit that is not plotting a move, where it stands now with its live
     * movement state and location exposure: a unit that already moved, or one that waits for its turn and would stand
     * still. Only the twist is hypothetical (forward as the unit's move or stand-still leaves it, then every legal
     * twist), so any unit kind standing on a board can be evaluated. Stops early as {@link #compute(MovePath, List)};
     * the same phase and threading rules apply.
     */
    public static Result computeInPlace(Entity unit, List<Entity> enemies) {
        return computeInPlace(unit, enemies, false);
    }

    /** As {@link #computeInPlace(Entity, List)}; everyTwist evaluates every legal twist instead of stopping early. */
    public static Result computeInPlace(Entity unit, List<Entity> enemies, boolean everyTwist) {
        Game game = unit.getGame();
        // The object the rules look up by id.
        Entity live = (game == null) ? null : game.getEntity(unit.getId());
        End end = End.current((live == null) ? unit : live);
        Reason refused = null;
        // Not on a board, or hidden: a hidden unit may only spot (FiringDisplay).
        if ((live == null) || (live.getPosition() == null) || live.isOffBoard()
              || (live.getTransportId() != Entity.NONE) || live.isHidden()) {
            refused = Reason.UNSUPPORTED_STATE;
        } else if (!game.getPhase().isMovement()) {
            refused = Reason.NOT_MOVEMENT_PHASE;
        }
        if (refused != null) {
            return new Result(end, refused, null, null, false, List.of());
        }
        try (HypotheticalState here = HypotheticalState.inPlace(live)) {
            return evaluate(here, live, end, enemies, everyTwist);
        }
    }

    /** Both directions against each enemy while the scope holds the unit in the state to evaluate. */
    private static Result evaluate(HypotheticalState scope, Entity unit, End end, List<Entity> enemies,
          boolean everyTwist) {
        Game game = unit.getGame();
        List<Entity> units = game.getEntitiesVector();
        // The ECM fields of the evaluated state, gathered once for all its to-hit calls instead of several times in
        // each (each call still gathers them once more itself). Until the scope closes only secondary facings change,
        // and no field depends on one.
        List<ECMInfo> ecm = ComputeECM.computeAllEntitiesECMInfo(units);
        boolean[] c3ScratchFlags = new boolean[units.size()];
        for (int i = 0; i < units.size(); i++) {
            c3ScratchFlags[i] = units.get(i).getC3ecmAffected();
        }
        try {
            ToHitData attackerMovement = Compute.getAttackerMovementModifier(game, unit.getId());
            ToHitData targetMovement = Compute.getTargetMovementModifier(game, unit.getId());
            List<Entity> targets = game.getValidTargets(unit);
            List<Direction> outgoing = new ArrayList<>();
            for (Entity enemy : enemies) {
                outgoing.add((enemy.getBoardId() != end.boardId()) ? new Direction(Reason.OTHER_BOARD, List.of())
                      : !targets.contains(enemy) ? new Direction(Reason.NOT_A_TARGET, List.of())
                      : salvos(game, scope, unit, enemy, ecm, everyTwist));
            }
            scope.setSecondaryFacing(scope.forwardSecondaryFacing());
            List<Exchange> exchanges = new ArrayList<>();
            for (int i = 0; i < enemies.size(); i++) {
                Entity enemy = enemies.get(i);
                Direction incoming;
                if (enemy.getBoardId() != end.boardId()) {
                    incoming = new Direction(Reason.OTHER_BOARD, List.of());
                } else {
                    try (HypotheticalState twisted = HypotheticalState.inPlace(enemy)) {
                        incoming = salvos(game, twisted, enemy, unit, ecm, everyTwist);
                    }
                }
                exchanges.add(new Exchange(enemy.getId(), outgoing.get(i), incoming));
            }
            return new Result(end, null, attackerMovement, targetMovement, anyLocationWet(unit),
                  List.copyOf(exchanges));
        } finally {
            // C3 spotter selection writes a scratch flag on friendly units (ComputeC3Spotter); put it back.
            for (int i = 0; i < units.size(); i++) {
                units.get(i).setC3ecmAffected(c3ScratchFlags[i]);
            }
        }
    }

    private static Direction salvos(Game game, HypotheticalState scope, Entity attacker, Entity target,
          List<ECMInfo> ecm, boolean everyTwist) {
        List<Salvo> result = new ArrayList<>();
        for (int twist : scope.legalSecondaryFacings()) {
            scope.setSecondaryFacing(twist);
            Salvo salvo = new Salvo(twist, attacker.getWeaponList().stream()
                  .map(weapon -> shot(game, attacker, weapon, target, ecm))
                  .toList());
            result.add(salvo);
            if (!everyTwist && salvo.allAvailable()) {
                break;
            }
        }
        return new Direction(null, List.copyOf(result));
    }

    /**
     * The declaration to-hit that FiringDisplay shows, from a new declaration of this weapon at this target given the
     * ECM fields of the evaluated state, unless the weapon is one the preview cannot evaluate.
     */
    private static Shot shot(Game game, Entity attacker, WeaponMounted weapon, Targetable target, List<ECMInfo> ecm) {
        int weaponId = attacker.getEquipmentNum(weapon);
        Reason skipped = skipReason(weapon);
        if (skipped != null) {
            return new Shot(weaponId, null, skipped);
        }
        return new Shot(weaponId, new WeaponAttackAction(attacker.getId(), target.getTargetType(), target.getId(),
              weaponId).toHit(game, ecm), null);
    }

    private static @Nullable Reason skipReason(WeaponMounted weapon) {
        WeaponType type = weapon.getType();
        AmmoMounted ammo = weapon.getLinkedAmmo();
        if (weapon.firesAutomatically()) {
            return Reason.FIRES_AUTOMATICALLY;
        } else if (type.hasFlag(WeaponType.F_ARTILLERY) || type.hasFlag(WeaponType.F_CRUISE_MISSILE)
              || (type instanceof CapitalMissileWeapon) || weapon.isInBearingsOnlyMode()) {
            // The rules classify these by the current phase, which need not be the phase they are fired in.
            return Reason.OTHER_PHASE_WEAPON;
        } else if ((ammo != null) && ammo.isDumping()) {
            // The rules would link the weapon to other ammo for good.
            return Reason.DUMPING_AMMO;
        }
        return null;
    }

    /**
     * TacOps climbing: the server turns a climb-mode step off an edge three or more levels high into a climb down or
     * a dangle (MovePathHandler), which the end state does not describe.
     */
    private static boolean edgeDescent(Game game, MovePath plan, Entity unit) {
        MoveStep last = plan.getLastStep();
        return game.getOptions().booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_CLIMBING)
              && plan.getFinalClimbMode() && !plan.isJumping() && (last != null)
              && (unit.getPosition().distance(last.getPosition()) == 1)
              && ClimbingHelper.isAtEdge(unit, last.getPosition(), game);
    }

    /**
     * Whether the move takes the unit into water (a walked step, or where it ends: the places the server updates its
     * location exposure) while a location of it breaches without a roll (Entity#breachesWithoutRoll; any location
     * counts, even one the water does not reach, except one already breached, which WaterExposure never checks
     * again). The breach destroys equipment and can end the move early, which the end state does not describe.
     */
    private static boolean breachesInWater(Game game, MovePath plan, Entity unit) {
        boolean wet = inWater(game, plan.getFinalBoardId(), plan.getFinalCoords(), plan.getFinalElevation());
        if (!plan.isJumping()) {
            for (MoveStep step : plan.getStepVector()) {
                wet |= inWater(game, step.getBoardId(), step.getPosition(), step.getElevation());
            }
        }
        return wet && IntStream.range(0, unit.locations())
              .anyMatch(location -> (unit.getLocationStatus(location) != ILocationExposureStatus.BREACHED)
                    && unit.breachesWithoutRoll(location));
    }

    /** The server's test for a unit whose locations get wet (WaterExposure.apply, not jumping). */
    private static boolean inWater(Game game, int boardId, Coords position, int elevation) {
        Hex hex = game.getBoard(boardId).getHex(position);
        return (hex != null) && (hex.terrainLevel(Terrains.WATER) > 0) && (elevation < 0);
    }

    /** A location of the unit is under water: hits on it roll for a breach (TWGameManager.breachCheck). */
    private static boolean anyLocationWet(Entity unit) {
        for (int location = 0; location < unit.locations(); location++) {
            if (unit.getLocationStatus(location) == ILocationExposureStatus.WET) {
                return true;
            }
        }
        return false;
    }
}
