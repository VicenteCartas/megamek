/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void theLightComesFromTheBiggestOpeningNotBetweenOpeningsOrFromASpeck() {
        int width = 200;
        int height = 100;
        float[] light = new float[width * height];
        fill(light, width, 20, 30, 40, 70);
        fill(light, width, 120, 160, 20, 80);
        fill(light, width, 80, 82, 10, 12);
        StartCommandEffects.Opening opening = StartCommandEffects.opening(light, width, height);
        assertNotNull(opening);
        assertEquals(0.7, opening.u(), 0.03, "the centre of the big opening, not between the two");
        assertEquals(0.5, opening.v(), 0.03);
        assertTrue(opening.inside()[50 * width + 140], "the big opening is lit");
        assertFalse(opening.inside()[50 * width + 30], "the other opening is not");
        assertFalse(opening.inside()[11 * width + 81], "nor the speck");
    }

    @Test
    void tooLittleLightCastsNoShafts() {
        float[] light = new float[100 * 50];
        fill(light, 100, 10, 12, 10, 12);
        assertNull(StartCommandEffects.opening(light, 100, 50));
    }

    private static void fill(float[] light, int width, int x0, int x1, int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                light[y * width + x] = 1;
            }
        }
    }
}
