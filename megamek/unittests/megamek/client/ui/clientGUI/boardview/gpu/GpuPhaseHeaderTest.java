/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The phase header's turn order: the blocks' width and the scroll that keeps the current turn in view. */
class GpuPhaseHeaderTest {
    /** A row of 900 units, as in a 1600-unit window between its columns. */
    private static final float ROW = 900;

    @Test
    void aFewTurnsKeepTheWidestBlocksWithoutScrolling() {
        assertEquals(GpuPhaseHeader.BLOCK_MAX, GpuPhaseHeader.blockWidth(4, ROW));
        assertEquals(0, GpuPhaseHeader.scroll(4, 3, ROW));
    }

    @Test
    void moreTurnsShareTheRowDownToTheNarrowestBlock() {
        float shared = GpuPhaseHeader.blockWidth(60, ROW);
        assertTrue(shared > GpuPhaseHeader.BLOCK_MIN && shared < GpuPhaseHeader.BLOCK_MAX, "60 turns share the row");
        assertEquals(0, GpuPhaseHeader.scroll(60, 59, ROW), "and all fit");
        assertEquals(GpuPhaseHeader.BLOCK_MIN, GpuPhaseHeader.blockWidth(146, ROW));
    }

    @Test
    void aLongOrderScrollsToCentreTheCurrentTurnWithinItsEnds() {
        // 146 turns at 8 units and 3 between them: 1603 units, 703 more than the row.
        float step = GpuPhaseHeader.BLOCK_MIN + 3;
        float total = 146 * step - 3;
        assertEquals(0, GpuPhaseHeader.scroll(146, 0, ROW), "The first turn stays at the start");
        assertEquals(total - ROW, GpuPhaseHeader.scroll(146, 145, ROW), .01f, "the last at the end");
        float shown = 73 * step + GpuPhaseHeader.BLOCK_MIN / 2 - GpuPhaseHeader.scroll(146, 73, ROW);
        assertEquals(ROW / 2, shown, .01f, "and one in between in the middle");
    }
}
