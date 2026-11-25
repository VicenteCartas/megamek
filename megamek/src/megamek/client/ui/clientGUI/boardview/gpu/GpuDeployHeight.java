/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;

/**
 * The height a placed unit deploys at (an aerospace unit's altitude, a VTOL's elevation), or a walk-on unit enters at
 * while it sets up its entry, shared by the dock's stepper and the one over the unit, so all show the height asked for
 * last until MegaMek's placed unit takes it. Deployment and walk-on entry offer the same heights
 * ({@code DeploymentHelper.placementHeights}) and name them alike.
 */
final class GpuDeployHeight {
    /** How long a height asked for shows before MegaMek's placed unit takes it. */
    private static final long ASK_MILLIS = 600;

    private final GpuBoardSource source;
    private int asked;
    private long askedAt;
    /** The wheel's notches not yet turned into a height step. */
    private float wheelLeft;

    GpuDeployHeight(GpuBoardSource source) {
        this.source = source;
    }

    /** The placed unit's height choice when it is {@code unitId}'s and it has more than one height, else null. */
    GpuBoardSource.HeightChoice choice(int unitId) {
        GpuBoardSource.HeightChoice choice = source == null ? null : source.heightChoice();
        return choice != null && choice.unitId() == unitId && choice.heights().size() > 1 ? choice : null;
    }

    /** The height to show: the one asked for last until MegaMek's unit takes it, else the unit's. */
    int shown(GpuBoardSource.HeightChoice choice) {
        boolean waiting = askedAt != 0 && System.currentTimeMillis() - askedAt < ASK_MILLIS && asked != choice.height()
              && choice.heights().contains(asked);
        if (!waiting) {
            askedAt = 0;
        }
        return waiting ? asked : choice.height();
    }

    /** "Alt 3", "Landed", "Elev 2" or "Depth 1", as the stepper names the shown height. */
    String label(GpuBoardSource.HeightChoice choice) {
        return label(choice.kind(), shown(choice));
    }

    /** What a unit's height measures, which names it. */
    enum Kind {
        /** A ground unit's elevation. */
        ELEVATION,
        /** A VTOL's or WiGE's elevation, 0 when landed. */
        LIFT,
        /** An aerospace unit's altitude, 0 when landed. */
        ALTITUDE,
        /** A submarine's or UMU unit's elevation, named as its depth under the surface. */
        DEPTH;

        /** The kind of {@code entity}'s height: an altitude, a diving unit's depth, a flier's lift, else an elevation. */
        static Kind of(Entity entity) {
            EntityMovementMode mode = entity.getMovementMode();
            return entity.isAero() ? ALTITUDE : mode.isSubmarine() || mode.isUMUInfantry() ? DEPTH
                  : mode.isVTOLOrWiGE() ? LIFT : ELEVATION;
        }

        /** The kind of a planned unit's height, as the movement plan sees it. */
        static Kind of(GpuMovePlan.Traits traits) {
            return traits.altitude() ? ALTITUDE : traits.dives() ? DEPTH : traits.elevates() ? LIFT : ELEVATION;
        }
    }

    /** Whether a unit of {@code kind} at {@code height} is landed: an aerospace unit's or a flier's height 0. */
    static boolean landed(Kind kind, int height) {
        return height == 0 && (kind == Kind.ALTITUDE || kind == Kind.LIFT);
    }

    /**
     * How a stepper names a height, in deployment and in movement alike: "Landed" ({@link #landed}), an altitude
     * ("Alt 3"), a depth ("Depth 1" for elevation -1) or an elevation ("Elev 2"). {@code height} is the unit's raw
     * elevation or altitude.
     */
    static String label(Kind kind, int height) {
        if (landed(kind, height)) {
            return text("GpuBoard.hud.deploy.landed");
        }
        return switch (kind) {
            case ALTITUDE -> text("GpuBoard.hud.deploy.altitude", height);
            case DEPTH -> text("GpuBoard.hud.move.depth", Math.max(0, -height));
            case ELEVATION, LIFT -> text("GpuBoard.hud.deploy.elevation", height);
        };
    }
    /**
     * Steps {@code unitId}'s height {@code step} up (1) or down (-1); {@code wrap} goes from the highest back to the
     * lowest, as a click on the height itself does.
     */
    void step(int unitId, int step, boolean wrap) {
        GpuBoardSource.HeightChoice choice = choice(unitId);
        if (choice == null) {
            return;
        }
        int from = shown(choice);
        List<Integer> options = choice.heights();
        int at = options.indexOf(from);
        int next = at < 0 ? choice.height()
              : wrap && at + step >= options.size() ? options.getFirst()
              : options.get(MathUtils.clamp(at + step, 0, options.size() - 1));
        if (next != from) {
            asked = next;
            askedAt = System.currentTimeMillis();
            source.setPlacedHeight(choice.unitId(), next);
        }
    }

    /** Ctrl+wheel: a notch away (up) raises {@code unitId}; false while it has no height choice. */
    boolean wheel(int unitId, float amountY) {
        if (choice(unitId) == null) {
            wheelLeft = 0;
            return false;
        }
        wheelLeft -= amountY;
        int notches = (int) wheelLeft;
        wheelLeft -= notches;
        for (int i = 0; i < Math.abs(notches); i++) {
            step(unitId, Integer.signum(notches), false);
        }
        return true;
    }
}
