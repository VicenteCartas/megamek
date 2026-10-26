/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartTimelineTest {
    @Test
    void theMenuStartsBeforeTheRevealEndsAndTheIntroEndsAfterIt() {
        assertTrue(StartTimeline.REVEAL_END > StartTimeline.REVEAL_START);
        assertTrue(StartTimeline.MENU_START < StartTimeline.REVEAL_END, "The menu overlaps the end of the reveal");
        assertTrue(StartTimeline.INTRO_END > StartTimeline.REVEAL_END);
    }

    @Test
    void revealProgressRunsFromZeroToOneAcrossTheReveal() {
        assertEquals(0, StartTimeline.revealProgress(0));
        assertEquals(0, StartTimeline.revealProgress(StartTimeline.REVEAL_START));
        assertEquals(.5, StartTimeline.revealProgress((StartTimeline.REVEAL_START + StartTimeline.REVEAL_END) / 2),
              1e-9);
        assertEquals(1, StartTimeline.revealProgress(StartTimeline.REVEAL_END));
        assertEquals(1, StartTimeline.revealProgress(100));
    }

    @Test
    void theGreetingTypesLineOneThenLineTwo() {
        assertEquals(0, StartTimeline.lineOneTyped(0, 20));
        assertEquals(0, StartTimeline.lineOneTyped(StartTimeline.TEXT_START, 20));
        assertEquals(20, StartTimeline.lineOneTyped(StartTimeline.TEXT_START + StartTimeline.LINE_ONE_TYPING, 20));
        double lineTwoStart = StartTimeline.TEXT_START + StartTimeline.LINE_TWO_DELAY;
        assertEquals(0, StartTimeline.lineTwoTyped(lineTwoStart, 30));
        int halfway = StartTimeline.lineTwoTyped(lineTwoStart + StartTimeline.LINE_TWO_TYPING / 2, 30);
        assertTrue(halfway == 14 || halfway == 15, "About half the line is typed halfway through");
        assertEquals(30, StartTimeline.lineTwoTyped(StartTimeline.REVEAL_START, 30),
              "The greeting is fully typed before the reveal starts");
    }

    @Test
    void menuItemsSlideInStaggeredAndSettle() {
        double midway = StartTimeline.MENU_START + StartTimeline.ITEM_SLIDE / 2;
        assertEquals(0, StartTimeline.itemProgress(StartTimeline.MENU_START, 0));
        assertTrue(StartTimeline.itemProgress(midway, 0) > StartTimeline.itemProgress(midway, 3),
              "Lower items start later");
        assertEquals(1, StartTimeline.itemProgress(StartTimeline.INTRO_END, 5), 1e-9);
    }

    @Test
    void theGreetingWelcomesBackOnlyAPlayerWhoHasPlayedBefore() {
        assertTrue(StartTimeline.returning("  Natasha "));
        assertFalse(StartTimeline.returning(""));
        assertFalse(StartTimeline.returning("   "));
        assertFalse(StartTimeline.returning(null));
    }
}
