/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.moves;

import java.util.ArrayList;
import java.util.List;

import megamek.common.ToHitData;
import megamek.common.compute.Compute;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;
import megamek.common.units.FirePreview;

/**
 * Movement heat and target movement modifier of a move: the server's heat phase and the movement path display use
 * these, and a preview of a plotted path gets the same numbers from them.
 */
public final class MovePathSummary {

    /** One itemized source of movement heat: a {@code HeatBreakdown.*} key of the common bundle and its heat. */
    public record HeatSource(String reasonKey, int heat) { }

    private MovePathSummary() { }

    /**
     * The heat the end of the movement phase adds when the unit completes this path as plotted. The movement type, the
     * hexes moved, the use of mechanical jump boosters and evasion come from the path; everything else (damage,
     * movement mode) is the unit's current state. An empty path is standing still.
     * <p>
     * Call it only on the thread that owns the unit's game: the run heat reads the unit's evasion flag, which the
     * server sets when it executes an EVADE step, so the flag holds the plan's value ({@link FirePreview.End#evading()})
     * for the length of the call.
     */
    public static int heat(MovePath path) {
        Entity unit = path.getEntity();
        boolean evading = unit.isEvading();
        unit.setEvading(FirePreview.End.of(path).evading());
        try {
            return movementHeat(unit, path.getLastStepMovementType(), path.getHexesMoved(),
                  path.contains(MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER)).stream().mapToInt(HeatSource::heat).sum();
        } finally {
            unit.setEvading(evading);
        }
    }

    /**
     * The movement heat of a unit that moved this way, itemized in the order the server adds it at the end of the
     * movement phase. A source may add 0 heat.
     *
     * @param moved                  the movement type of the move
     * @param distance               the hexes moved
     * @param mechanicalJumpBoosters whether the unit jumped with mechanical jump boosters
     */
    public static List<HeatSource> movementHeat(Entity entity, EntityMovementType moved, int distance,
          boolean mechanicalJumpBoosters) {
        List<HeatSource> sources = new ArrayList<>();
        if (entity.hasDamagedRHS()) {
            sources.add(new HeatSource("HeatBreakdown.damagedRadicalHeatSink", 1));
        }

        if ((entity.getMovementMode() == EntityMovementMode.BIPED_SWIM)
              || (entity.getMovementMode() == EntityMovementMode.QUAD_SWIM)) {
            // UMU heat
            sources.add(new HeatSource("HeatBreakdown.movementUMU", 1));
            return sources;
        }

        if (moved == EntityMovementType.MOVE_NONE) {
            sources.add(new HeatSource("HeatBreakdown.movementStanding", entity.getStandingHeat()));
        } else if ((moved == EntityMovementType.MOVE_WALK)
              || (moved == EntityMovementType.MOVE_VTOL_WALK)
              || (moved == EntityMovementType.MOVE_CAREFUL_STAND)) {
            sources.add(new HeatSource("HeatBreakdown.movementWalking", entity.getWalkHeat()));
        } else if ((moved == EntityMovementType.MOVE_RUN)
              || (moved == EntityMovementType.MOVE_VTOL_RUN)
              || (moved == EntityMovementType.MOVE_SKID)) {
            sources.add(new HeatSource("HeatBreakdown.movementRunning", entity.getRunHeat()));
        } else if ((moved == EntityMovementType.MOVE_JUMP) && !mechanicalJumpBoosters) {
            sources.add(new HeatSource("HeatBreakdown.movementJumping", entity.getJumpHeatForDistance(distance)));
        } else if ((moved == EntityMovementType.MOVE_SPRINT)
              || (moved == EntityMovementType.MOVE_VTOL_SPRINT)) {
            sources.add(new HeatSource("HeatBreakdown.movementSprinting", entity.getSprintHeat()));
        }
        return sources;
    }

    /**
     * The target movement modifier the movement path display shows for this path (see {@link #tmm(MoveStep, Game)});
     * an empty path is standing still.
     */
    public static ToHitData tmm(MovePath path) {
        MoveStep lastStep = path.getLastStep();
        if (lastStep != null) {
            return tmm(lastStep, path.getGame());
        }
        Entity unit = path.getEntity();
        return tmm(EntityMovementType.MOVE_NONE, unit.getMovementMode(), unit, 0, path.getGame());
    }

    /**
     * The target movement modifier the movement path display shows under the last step of a path, from the hexes
     * moved, a jump and being airborne only. It is not the modifier attackers get: once the unit has moved,
     * {@link Compute#getTargetMovementModifier(Game, int)} also applies, among others, sprinting, skidding, a dedicated
     * pilot and the TacOps standing-still rule, and does not count a jump by a unit airborne as a VTOL or WiGE.
     */
    public static ToHitData tmm(MoveStep lastStep, Game game) {
        return tmm(lastStep.getMovementType(true), lastStep.getMovementMode(), lastStep.getEntity(),
              lastStep.getDistance(), game);
    }

    private static ToHitData tmm(EntityMovementType type, EntityMovementMode mode, Entity unit, int distance,
          Game game) {
        boolean airborneNonAerospace = (type == EntityMovementType.MOVE_VTOL_RUN)
              || (type == EntityMovementType.MOVE_VTOL_WALK)
              || ((mode == EntityMovementMode.VTOL)
              && ((type != EntityMovementType.MOVE_NONE) || unit.isAirborneVTOLorWIGE()))
              || (type == EntityMovementType.MOVE_VTOL_SPRINT);
        return Compute.getTargetMovementModifier(distance, type == EntityMovementType.MOVE_JUMP, airborneNonAerospace,
              game);
    }
}
