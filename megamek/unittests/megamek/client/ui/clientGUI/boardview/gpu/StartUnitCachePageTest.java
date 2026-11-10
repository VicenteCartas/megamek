/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.StartUnitCachePage.MOST;
import static megamek.client.ui.clientGUI.boardview.gpu.StartUnitCachePage.fraction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartUnitCachePageTest {
    @Test
    void aLoadStartsEmpty() {
        assertEquals(0, fraction(0, 10_000, 0), 1e-6);
        assertEquals(0, fraction(0, 0, 0), 1e-6);
    }

    @Test
    void aRebuildFillsByTheUnitsItHasReadOfThoseKnown() {
        assertEquals(MOST / 2, fraction(5_000, 10_000, 0), 1e-6);
    }

    @Test
    void aLoadOfUnknownSizeOrARefreshStillMovesWithTime() {
        float early = fraction(0, 0, 1);
        float later = fraction(0, 0, 10);
        assertTrue(early > 0 && later > early, "The bar grows while the load runs");
        // A refresh reads only changed files, so its few units don't hold the bar back
        assertTrue(fraction(3, 10_000, 10) > 0.5f);
    }

    @Test
    void aRunningLoadNeverLooksDone() {
        assertTrue(fraction(0, 0, 1_000) <= MOST);
        assertEquals(MOST, fraction(12_000, 10_000, 0), 1e-6);
        assertEquals(0, fraction(0, 0, -5), 1e-6);
    }
}
