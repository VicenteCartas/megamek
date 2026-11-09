/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;

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

    @Test
    void eachScreenIsNumberedOnceEvenWithADarkMapOnIt() {
        int width = 200;
        int height = 100;
        float[] glow = new float[width * height];
        fill(glow, width, 20, 60, 20, 50);
        fill(glow, width, 120, 170, 40, 80);
        // A dark map line across the first screen
        for (int y = 20; y < 50; y++) {
            glow[y * width + 40] = 0;
        }
        int[] screens = StartCommandEffects.screens(glow, width, height);
        int first = screens[30 * width + 25];
        assertTrue(first > 0);
        assertEquals(first, screens[30 * width + 55], "the map line does not split the screen");
        int second = screens[60 * width + 140];
        assertTrue(second > 0 && second != first, "the other screen is its own");
        assertEquals(0, screens[90 * width + 100], "the wall is no screen");
        assertTrue(Math.abs(StartCommandEffects.screenPhase(first) - StartCommandEffects.screenPhase(second)) > 0.1f,
              "the screens scan at different times");
    }

    @Test
    void aSplitFlapFallsFromUpToDownAndBouncesWhereItLands() {
        assertEquals(0, StartCommandEffects.flapAngle(-1), 1e-9, "waiting flaps stand up");
        assertEquals(Math.PI, StartCommandEffects.flapAngle(0.75), 1e-9, "the flap lands");
        assertTrue(StartCommandEffects.flapAngle(0.85) < Math.PI, "and bounces");
        assertEquals(Math.PI, StartCommandEffects.flapAngle(1), 1e-9, "landed flaps hang down");
        assertTrue(StartCommandEffects.flapAngle(0.2) < StartCommandEffects.flapAngle(0.5));
    }

    @Test
    void theFlapsFallTopRowsFirstAndAllInTime() {
        int cols = 40;
        int rows = 22;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double start = StartCommandEffects.flapStart(col, row, cols, rows);
                assertTrue(start >= 0 && start + StartCommandEffects.BACKS_IN + StartCommandEffects.FLAP_SPAN <= 1,
                      "each flap lands within the reveal");
            }
        }
        assertTrue(StartCommandEffects.flapStart(0, 0, cols, rows) < StartCommandEffects.flapStart(0, rows - 1, cols,
              rows), "the top row falls before the bottom row");
    }

    @Test
    void theStarMapIsJoinedAndLightsOutwardsFromItsHub() {
        StartCommandEffects.StarMap map = StartCommandEffects.starMap(3025L, 150);
        float[] born = map.born();
        int[] routes = map.routes();
        assertEquals(450, map.points().length);
        assertEquals(0, born[0], "the hub lights first");
        for (int i = 1; i < born.length; i++) {
            assertTrue(born[i] > 0 && born[i] < 1, "every system lights during the build");
            boolean reached = false;
            for (int k = 0; k < routes.length; k += 2) {
                int other = routes[k] == i ? routes[k + 1] : routes[k + 1] == i ? routes[k] : -1;
                reached |= other >= 0 && born[other] < born[i];
            }
            assertTrue(reached, "system " + i + " is reached by a route from one lit before it");
        }
    }

    @Test
    void aChartKeepsSystemsNearTerraJoinsThoseAJumpApartAndDivesIntoItsFocus() {
        Color fs = new Color(255, 221, 0);
        StartScreenContent.StarChart chart = new StartScreenContent.StarChart(List.of(
              new StartScreenContent.Star(0, 0, Color.WHITE),
              new StartScreenContent.Star(20, 0, fs),
              new StartScreenContent.Star(800, 0, fs),
              new StartScreenContent.Star(3000, 0, fs)), 798, 1);

        StartCommandEffects.StarMap map = StartCommandEffects.chartMap(chart);

        assertNotNull(map);
        assertTrue(map.planar());
        assertEquals(9, map.points().length, "the far system is left out");
        assertEquals(2, map.focus());
        assertArrayEquals(new int[] { 0, 1 }, map.routes(), "only the near pair is a jump apart");
        assertEquals(1f, map.colours()[3], 0.001f);
        assertEquals(221 / 255f, map.colours()[4], 0.001f);
        assertTrue(map.born()[0] < map.born()[2], "Terra lights before the far reaches");
    }

    @Test
    void aChartWithNothingNearTerraIsNoMap() {
        assertNull(StartCommandEffects.chartMap(new StartScreenContent.StarChart(
              List.of(new StartScreenContent.Star(5000, 0, Color.WHITE)), 0, 0)));
    }

    @Test
    void powersHazeButTheUnheldGreyDoesNot() {
        assertTrue(StartCommandEffects.held(new float[] { 1f, 0.87f, 0f }));
        assertTrue(StartCommandEffects.held(new float[] { 1f, 0.98f, 0.94f }));
        assertFalse(StartCommandEffects.held(new float[] { 0.43f, 0.43f, 0.43f }));
    }

    private static void fill(float[] light, int width, int x0, int x1, int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                light[y * width + x] = 1;
            }
        }
    }
}
