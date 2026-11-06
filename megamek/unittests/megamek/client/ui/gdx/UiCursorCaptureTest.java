/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The wrap keeps the cursor inside the window, and one drag holds the pointer at a time. */
class UiCursorCaptureTest {
    private final UiCursorCapture cursor = new UiCursorCapture();

    @AfterEach
    void tearDown() {
        cursor.release();
    }

    @ParameterizedTest
    @CsvSource({ "400, 400", "80, 80", "720, 720", "79, 719", "721, 81", "-50, 590", "850, 210" })
    void pastOneBorderTheCursorComesInAtTheOtherOfAnEightHundredPixelWindow(double at, double expected) {
        assertEquals(expected, UiCursorCapture.wrapped(at, 800));
    }

    @Test
    void anotherOwnersCaptureIsNeitherTakenOverNorReleased() {
        cursor.capture(100, 100, 1, 1);
        UiCursorCapture other = new UiCursorCapture();
        other.capture(100, 100, 1, 1);
        assertEquals(130, other.trusted(130, 140).x);
        assertEquals(140, other.trusted(130, 140).y);
        other.release();
        assertTrue(UiCursorCapture.active());
        cursor.release();
        assertFalse(UiCursorCapture.active());
    }
}
