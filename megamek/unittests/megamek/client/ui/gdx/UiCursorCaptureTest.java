/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Cursor capture must not turn a delayed native recenter event into a relative drag. */
class UiCursorCaptureTest {
    private final Input originalInput = Gdx.input;
    private final Graphics originalGraphics = Gdx.graphics;
    private final UiCursorCapture cursor = new UiCursorCapture();

    @BeforeEach
    void setUp() {
        Gdx.input = mock(Input.class);
        Gdx.graphics = mock(Graphics.class);
        frame(10);
    }

    @AfterEach
    void tearDown() {
        cursor.release();
        Gdx.input = originalInput;
        Gdx.graphics = originalGraphics;
    }

    @ParameterizedTest
    @CsvSource({ "11, 100, 100", "12, 100, 100", "18, 100, 100",
                 "12, 900, 100", "12, 100, 600", "12, 900, 600" })
    void firstCapturedMotionDoesNotJumpEvenAfterFramesWithoutInput(long arrival, float x, float y) {
        cursor.capture(x, y);
        assertPoint(x, y, cursor.trusted(x, y));
        // Already queued motion from before capture still belongs to the original screen coordinates.
        assertPoint(x + 4, y + 2, cursor.trusted(x + 4, y + 2));

        // The first native poll can add the distance from the press to the window's centre to virtual coordinates.
        // No drag callbacks arrive in the intervening frames when arrival > 11.
        frame(arrival);
        float jumpedX = x + 4 + x - 500;
        float jumpedY = y + 2 + y - 350;
        assertPoint(x + 4, y + 2, cursor.trusted(jumpedX, jumpedY));
        assertPoint(x + 4, y + 2, cursor.trusted(jumpedX + 3, jumpedY - 2));

        frame(arrival + 1);
        assertPoint(x + 11, y - 3, cursor.trusted(jumpedX + 10, jumpedY - 7));
        frame(arrival + 20);
        assertPoint(x - 89, y + 37, cursor.trusted(jumpedX - 90, jumpedY + 33));
    }

    @Test
    void aNewGestureStartsWithFreshCoordinatesAndFiltersItsOwnFirstMotion() {
        cursor.capture(100, 100);
        frame(11);
        cursor.trusted(-300, -150);
        cursor.release();

        frame(20);
        cursor.capture(900, 600);
        assertPoint(900, 600, cursor.trusted(900, 600));
        frame(23);
        assertPoint(900, 600, cursor.trusted(1300, 850));
        frame(24);
        assertPoint(905, 593, cursor.trusted(1305, 843));
    }

    @Test
    void anotherOwnersCaptureIsNeitherFilteredNorReleased() {
        when(Gdx.input.isCursorCatched()).thenReturn(true);
        cursor.capture(100, 100);
        frame(12);
        assertPoint(130, 140, cursor.trusted(130, 140));
        cursor.release();
        verify(Gdx.input, never()).setCursorCatched(true);
        verify(Gdx.input, never()).setCursorCatched(false);
    }

    @Test
    void releaseUsesTheCapturedWindowEvenWhenAnotherWindowIsCurrent() {
        Input capturedInput = Gdx.input;
        cursor.capture(100, 100);
        Gdx.input = mock(Input.class);
        cursor.release();
        cursor.release();
        verify(capturedInput).setCursorCatched(false);
        verify(Gdx.input, never()).setCursorCatched(false);
    }

    private static void frame(long id) {
        when(Gdx.graphics.getFrameId()).thenReturn(id);
    }

    private static void assertPoint(float x, float y, Vector2 actual) {
        assertEquals(x, actual.x, .001f, "Horizontal drag must exclude the recenter jump");
        assertEquals(y, actual.y, .001f, "Vertical drag must exclude the recenter jump");
    }
}
