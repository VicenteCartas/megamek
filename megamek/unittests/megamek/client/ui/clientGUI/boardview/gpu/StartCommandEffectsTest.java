/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A command post's flipboard turns its tiles over and lands them flat, and its light and screens are found by colour. */
class StartCommandEffectsTest {

    @Test
    void aTileStartsOnItsBackAndLandsFlatAfterOvershooting() {
        assertEquals(Math.PI, StartCommandEffects.flipAngle(-1), 1e-9, "waiting tiles show their backs");
        assertEquals(Math.PI, StartCommandEffects.flipAngle(0), 1e-9);
        assertEquals(0, StartCommandEffects.flipAngle(1), 1e-9, "landed tiles lie flat");
        assertEquals(0, StartCommandEffects.flipAngle(2), 1e-9);
        double least = Double.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            least = Math.min(least, StartCommandEffects.flipAngle(i / 100.0));
        }
        assertTrue(least < 0, "the tile swings a little past flat before it settles");
    }

    @Test
    void warmBrightPixelsAreLightButWhiteTubesAndShadowsAreNot() {
        assertTrue(StartCommandEffects.warmLight(0xFFF0C080) > 0.5f, "sunlit haze");
        assertEquals(0, StartCommandEffects.warmLight(0xFFFFFFFF), 1e-6f, "a white ceiling tube");
        assertEquals(0, StartCommandEffects.warmLight(0xFF302010), 1e-6f, "a dark corner");
    }

    @Test
    void tealPixelsAreScreens() {
        assertTrue(StartCommandEffects.screenGlow(0xFF20D0C0) > 0.5f, "a lit teal screen");
        assertEquals(0, StartCommandEffects.screenGlow(0xFFF0C080), 1e-6f, "warm light");
        assertEquals(0, StartCommandEffects.screenGlow(0xFF101818), 1e-6f, "a dark panel");
    }
}
