/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;

/**
 * The height a placed unit deploys at (an aerospace unit's altitude, a VTOL's elevation), shared by the dock's stepper
 * and the shortcut over the unit, so both show the height asked for last until MegaMek's placed unit takes it.
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

    /** "Alt 3", "Landed" or "Elev 2", as the stepper names the shown height. */
    String label(GpuBoardSource.HeightChoice choice) {
        int shown = shown(choice);
        return choice.altitude() ? (shown == 0 ? text("GpuBoard.hud.deploy.landed")
              : text("GpuBoard.hud.deploy.altitude", shown)) : text("GpuBoard.hud.deploy.elevation", shown);
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
