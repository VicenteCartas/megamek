/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;

/** Presentation-only battle-clock scale, approaching a ranged death's scheduled start. */
final class UnitSlowMotion {
    /** Final .36 seconds at normal playback, stretched to about one real second by the easing curve. */
    static final float DEATH_LEAD_SECONDS = .18f;
    static final float DEATH_RATE = .1f;
    private static final float BULLET_TIME_RATE = .25f, BULLET_TIME_LEAD_SECONDS = .25f;

    /** Derived from the current presentation; no recovery clock survives the impact or a disabled checkbox. */
    static float scale(List<UnitAttack> attacks, boolean dramaticDeath) {
        float approach = 1, bulletRate = 1;
        for (var attack : attacks) {
            if (GpuBattleView.BULLET_TIME && attack.landing(BULLET_TIME_LEAD_SECONDS)) { bulletRate = BULLET_TIME_RATE; }
            if (!dramaticDeath || !attack.death() || attack.seconds >= 0 || attack.event.result().crewLoss()
                  || attack.cause == null || !attack.cause.shot()) { continue; }
            float remaining = -attack.seconds / DEATH_LEAD_SECONDS;
            approach = Math.min(approach, MathUtils.lerp(DEATH_RATE, 1, UnitAttack.smooth(remaining)));
        }
        return Math.min(bulletRate, approach);
    }
}
