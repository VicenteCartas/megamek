/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.ToDoubleFunction;

import com.badlogic.gdx.math.MathUtils;

/** Presentation-only battle-clock scale: approaching lethal projectiles or one extra real second of a lethal beam. */
final class UnitSlowMotion {
    static final float DEATH_DISTANCE_HEXES = 4;
    static final float DEATH_RATE = .1f;
    static final float BEAM_EXTRA_SECONDS = 1;
    private static final float BULLET_TIME_RATE = .25f, BULLET_TIME_LEAD_SECONDS = .25f;

    /** Derived from the current presentation; no recovery clock survives the impact or a disabled checkbox. */
    static float scale(List<UnitAttack> attacks, ToDoubleFunction<UnitAttack> distanceToImpact,
          double playbackRate, boolean dramaticDeath) {
        float approach = 1, bulletRate = 1;
        for (var attack : attacks) {
            if (GpuBattleView.BULLET_TIME && attack.landing(BULLET_TIME_LEAD_SECONDS)) { bulletRate = BULLET_TIME_RATE; }
            if (!dramaticDeath || !attack.death() || attack.seconds >= 0
                  || attack.cause == null || !attack.cause.shot()) { continue; }
            var cause = attack.cause;
            if (cause.continuousBeam()) {
                if (cause.seconds >= UnitAttack.ANTICIPATION_SECONDS) {
                    // Stretch only the lit beam: duration / rate becomes duration / rate + one real second.
                    approach = Math.min(approach,
                          (float) (cause.flightSeconds / (cause.flightSeconds + playbackRate * BEAM_EXTRA_SECONDS)));
                }
            } else {
                float distance = (float) distanceToImpact.applyAsDouble(cause);
                float outside = UnitAttack.smooth(distance / (DEATH_DISTANCE_HEXES * BoardGeometry.height()));
                approach = Math.min(approach, MathUtils.lerp(DEATH_RATE, 1, outside));
            }
        }
        return Math.min(bulletRate, approach);
    }
}
