/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.badlogic.gdx.scenes.scene2d.Actor;
import org.junit.jupiter.api.Test;

/** The board chip's column, shared by deployment's and movement's chips: the height on the unit, the buttons on it. */
class GpuBoardChipTest {
    private static Actor actor(float width, float height) {
        Actor actor = new Actor();
        actor.setSize(width, height);
        return actor;
    }

    @Test
    void theHeightRowStandsOnTheUnitAndTheButtonsOnIt() {
        Actor window = actor(800, 600);
        Actor heights = actor(140, 28);
        Actor buttons = actor(140, 28);
        GpuBoardChip.stack(window, 400, 200, heights, buttons, null);
        assertEquals(200, heights.getY());
        assertEquals(330, heights.getX());
        assertEquals(200 + 28 + GpuBoardChip.GAP, buttons.getY());
        assertEquals(330, buttons.getX());
        assertSame(heights, GpuBoardChip.bottom(heights, buttons));
    }

    @Test
    void withoutAHeightTheButtonsStandOnTheUnit() {
        Actor heights = actor(140, 28);
        heights.setVisible(false);
        Actor buttons = actor(140, 28);
        GpuBoardChip.stack(actor(800, 600), 400, 200, heights, buttons, null);
        assertEquals(200, buttons.getY());
        assertSame(buttons, GpuBoardChip.bottom(heights, buttons));
    }

    @Test
    void underTheWindowsTopTheColumnMovesDownAsOne() {
        Actor heights = actor(140, 28);
        Actor buttons = actor(140, 28);
        GpuBoardChip.stack(actor(800, 600), 790, 590, heights, buttons, null);
        assertEquals(600 - GpuBoardChip.MARGIN - 28, buttons.getY());
        assertEquals(buttons.getY() - GpuBoardChip.GAP - 28, heights.getY());
        assertEquals(800 - GpuBoardChip.MARGIN - 140, heights.getX());
    }
}
