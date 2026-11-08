/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TreeLodTest {
    @Test
    void retainsCloseDetailAndCanSkipLevelsWhenOverviewToggles() {
        assertEquals(0, TreeLod.level(360, 3));
        assertEquals(1, TreeLod.level(60, 0));
        assertEquals(2, TreeLod.level(30, 0));
        assertEquals(3, TreeLod.level(10, 0));
        assertEquals(3, TreeLod.level(0, 0));
        assertEquals("birch-snow-lod3", TreeLod.asset("birch-snow", 3));
        assertEquals("birch-snow-lod0", TreeLod.asset("birch-snow", 0));
    }

    @Test
    void smallZoomOscillationsDoNotRebuildMeshesAtEitherBoundary() {
        for (float pixels : new float[] { 74, 80, 86, 80 }) {
            assertEquals(0, TreeLod.level(pixels, 0));
            assertEquals(1, TreeLod.level(pixels, 1));
        }
        for (float pixels : new float[] { 44, 48, 52, 48 }) {
            assertEquals(1, TreeLod.level(pixels, 1));
            assertEquals(2, TreeLod.level(pixels, 2));
        }
        for (float pixels : new float[] { 22, 24, 26, 24 }) {
            assertEquals(2, TreeLod.level(pixels, 2));
            assertEquals(3, TreeLod.level(pixels, 3));
        }
        assertEquals(1, TreeLod.level(71, 0));
        assertEquals(0, TreeLod.level(89, 1));
        assertEquals(3, TreeLod.level(21, 2));
        assertEquals(2, TreeLod.level(27, 3));
    }

    @Test
    void buildingsAndEffectsKeepThreeLevels() {
        assertEquals(1, TreeLod.level(TreeLod.PROPS, 50, 0));
        assertEquals(2, TreeLod.level(TreeLod.PROPS, 10, 0));
        assertEquals(1, TreeLod.level(TreeLod.PROPS, 27, 2));
    }

    @Test
    void aGiantSwitchesAtFiveHexesOfItsSide() {
        // 16 pixels a hex is .19 a unit: the 1,108-unit HPG's whole side would still be 213 pixels.
        assertEquals(0, TreeLod.prop(1108, .25f, 0));
        assertEquals(1, TreeLod.prop(1108, .16f, 0));
        // The same scale leaves a smaller building as it was.
        assertEquals(0, TreeLod.prop(300, .25f, 0));
        assertEquals(1, TreeLod.prop(150, .45f, 0));
    }
}
