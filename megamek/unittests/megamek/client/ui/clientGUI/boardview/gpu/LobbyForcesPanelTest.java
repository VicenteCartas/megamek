/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** How the forces' cards and compact rows share out their width. */
class LobbyForcesPanelTest {

    @Test
    void aCompactRowShowsTheFactsItsWidthHoldsInOrder() {
        List<Float> widths = List.of(40f, 100f, 60f);

        assertEquals(3, LobbyForcesPanel.compactFacts(1000, 200, 10, widths));
        assertEquals(2, LobbyForcesPanel.compactFacts(370, 200, 10, widths), "200 + 50 + 110 fits, + 70 does not");
        assertEquals(0, LobbyForcesPanel.compactFacts(240, 200, 10, widths));
    }

    @Test
    void aCardShowsItsSheetOnlyBesideItsTextsLeastWidth() {
        assertTrue(LobbyForcesPanel.sheetFits(600, 260, 24, 300));
        assertFalse(LobbyForcesPanel.sheetFits(500, 260, 24, 300));
        assertFalse(LobbyForcesPanel.sheetFits(900, 260, 24, 0), "a card without a sheet shows none");
    }
}
