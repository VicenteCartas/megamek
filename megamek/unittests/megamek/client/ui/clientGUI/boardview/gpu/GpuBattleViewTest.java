/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuBattleViewTest {
    private static final float SAMPLES_PER_SECOND = 60;

    @Test
    void hoverWaveStaysWithinItsAmplitudeAndRepeatsItsPeriod() {
        float amplitude = GpuBattleView.HOVER_LEVELS * BoardGeometry.LEVEL;
        float reached = 0;
        for (int sample = 0; sample < 2000; sample++) {
            float offset = GpuBattleView.hoverOffset(sample / SAMPLES_PER_SECOND, 7, -1);
            assertTrue(Math.abs(offset) <= amplitude + 0.001f, "A hover offset stays within its amplitude");
            reached = Math.max(reached, Math.abs(offset));
        }
        assertTrue(reached > amplitude * 0.98f, "The wave must reach its amplitude");
        assertEquals(GpuBattleView.hoverOffset(1.25f, 7, -1),
              GpuBattleView.hoverOffset(1.25f + GpuBattleView.HOVER_PERIOD_SECONDS, 7, -1), 0.001f,
              "One period must return to the same height");
    }

    @Test
    void hoverWaveStartsAtDifferentPhasesPerUnitAndPart() {
        assertNotEquals(GpuBattleView.hoverOffset(0, 1, -1), GpuBattleView.hoverOffset(0, 2, -1), 0.01f,
              "Neighboring units must not rise and fall together");
        assertNotEquals(GpuBattleView.hoverOffset(0.7f, 4, -1), GpuBattleView.hoverOffset(0.7f, 4, 1), 0.01f,
              "Multiple parts of one unit must not rise and fall together");
    }

    @Test
    void floatingUnitsAreTiedToTheirHexCenter() {
        BoardScene.Unit floating = unit(true);
        Vector3 center = new Vector3(10, 20, 5 * BoardGeometry.LEVEL);
        assertEquals(3 * BoardGeometry.LEVEL, GpuBattleView.tetherGround(floating, 3, center, false), 0.001f,
              "The stem ends at the center of the hex under the token");
        assertTrue(Float.isNaN(GpuBattleView.tetherGround(floating, 3, center, true)),
              "A moving visual is between hexes and gets no stem");
        assertTrue(Float.isNaN(GpuBattleView.tetherGround(unit(false), 3, center, false)),
              "A grounded visual covers its hex and gets no stem");
        assertTrue(Float.isNaN(GpuBattleView.tetherGround(floating, 3,
              new Vector3(10, 20, 3 * BoardGeometry.LEVEL), false)),
              "A token at its tile surface has nothing to point at");
    }

    /**
     * A route's drag scrolls the board only near or past the edge of the area the panels leave free (here 300..1300 by
     * 100..900 with a 50 px margin): towards that edge, faster the deeper, at most the full distance; nothing inside.
     */
    @Test
    void edgeAutoPanMovesTheCameraOnlyNearTheEdge() {
        assertTrue(GpuBattleView.edgePan(800, 500, 300, 100, 1300, 900, 50, 12).isZero(), "Inside: no scroll");
        assertTrue(GpuBattleView.edgePan(351, 151, 300, 100, 1300, 900, 50, 12).isZero(), "Just inside the margin's start");
        var left = GpuBattleView.edgePan(325, 500, 300, 100, 1300, 900, 50, 12);
        assertEquals(6, left.x, 1e-4, "Half way into the left margin: half the distance, the board moving right");
        assertEquals(0, left.y, 1e-4);
        assertEquals(-12, GpuBattleView.edgePan(1400, 500, 300, 100, 1300, 900, 50, 12).x, 1e-4, "Past the right edge: all of it");
        var corner = GpuBattleView.edgePan(1300, 100, 300, 100, 1300, 900, 50, 12);
        assertEquals(-12, corner.x, 1e-4, "A corner scrolls both ways");
        assertEquals(12, corner.y, 1e-4);
        assertEquals(-12, GpuBattleView.edgePan(800, 900, 300, 100, 1300, 900, 50, 12).y, 1e-4, "The bottom edge brings in what lies below");
    }

    /**
     * Alt+Enter is the window's fullscreen switch and never an Enter: its press, the newline the window types for it
     * and its repeats, and its release reach neither the HUD nor the game's bindings. A plain Enter still does.
     */
    @Test
    void altEnterSwitchesFullscreenWithoutReachingTheHudOrTheGame() {
        var keys = new GpuBattleView(null).fullscreenKey;
        Input original = Gdx.input;
        Gdx.input = mock(Input.class);
        try {
            assertFalse(keys.keyDown(Input.Keys.ENTER), "A plain Enter goes on to the HUD and the game");
            assertFalse(keys.keyTyped('\n'));
            assertFalse(keys.keyUp(Input.Keys.ENTER));
            when(Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)).thenReturn(true);
            for (int enter : new int[] { Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER }) {
                assertTrue(keys.keyDown(enter), "Either Alt with either Enter is the switch");
                assertTrue(keys.keyTyped('\n'), "The newline typed for the press");
                assertTrue(keys.keyTyped('\n'), "and for each repeat");
                assertFalse(keys.keyTyped('a'), "Other characters pass");
                assertTrue(keys.keyUp(enter), "Its release");
                assertFalse(keys.keyTyped('\n'), "After the release Enter types again");
            }
            // The window may never deliver the release (focus lost): the next plain Enter is the game's again.
            assertTrue(keys.keyDown(Input.Keys.ENTER));
            when(Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)).thenReturn(false);
            assertFalse(keys.keyDown(Input.Keys.ENTER));
            assertFalse(keys.keyTyped('\n'));
            assertFalse(keys.keyUp(Input.Keys.ENTER));
        } finally {
            Gdx.input = original;
        }
    }

    /** The fullscreen switch takes its keys ahead of the game's bindings, so no default bind on any platform uses one. */
    @Test
    void noDefaultBindUsesAFullscreenKey() {
        int[] masks = { InputEvent.SHIFT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK, InputEvent.ALT_DOWN_MASK,
              InputEvent.META_DOWN_MASK };
        for (boolean mac : new boolean[] { false, true }) {
            for (int key = 0; key <= Input.Keys.MAX_KEYCODE; key++) {
                for (int combination = 0; combination < 1 << masks.length; combination++) {
                    int modifiers = 0;
                    for (int bit = 0; bit < masks.length; bit++) {
                        if ((combination & 1 << bit) != 0) { modifiers |= masks[bit]; }
                    }
                    if (!GpuBattleView.isFullscreenSwitch(key, modifiers, mac)) { continue; }
                    for (KeyCommandBind bind : KeyCommandBind.values()) {
                        assertFalse(KeyCommandBind.matches(bind.keyDefault, bind.modifiersDefault,
                              GpuBattleView.awtKey(key, true), modifiers), bind.cmd + " uses a fullscreen key");
                    }
                }
            }
        }
    }

    private static BoardScene.Unit unit(boolean airborne) {
        return new BoardScene.Unit(1, -1, "test", new BoardScene.Waypoint(new Coords(0, 0), 0, 0), null, false, null, 1,
              airborne);
    }

    @Test
    void pausingBeforeAMapIsAttachedDoesNotCrash() {
        var view = new GpuBattleView(null);
        assertDoesNotThrow(view::pause, "Closing the window during loading must not ask the absent map's editor");
    }
}
