/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The arc diagram's geometry and fill, without a GL context (LobbyDossierTabsSmokeTest draws one). */
class UiArcDiagramTest {

    @Test
    void theFirstArcFacesUpAndTheRestGoClockwise() {
        assertEquals(90, UiArcDiagram.centreAngle(0, 6));
        assertEquals(30, UiArcDiagram.centreAngle(1, 6));
        assertEquals(-90, UiArcDiagram.centreAngle(3, 6));
        assertEquals(0, UiArcDiagram.centreAngle(1, 4));
    }

    @Test
    void eachArcSpansItsShareLessTheGap() {
        float[] angles = UiArcDiagram.sliceAngles(0, 6, 4, 4);
        assertEquals(5, angles.length);
        assertEquals(90 + 28, angles[0], 1e-4f);
        assertEquals(90 - 28, angles[4], 1e-4f);
        for (int i = 1; i < angles.length; i++) {
            assertTrue(angles[i] < angles[i - 1], "the slices run clockwise");
        }
        float[] next = UiArcDiagram.sliceAngles(1, 6, 4, 1);
        assertEquals(4, angles[4] - next[0], 1e-4f, "neighbours leave the gap between them");
        assertArrayEquals(new float[] { 180, 0 }, UiArcDiagram.sliceAngles(0, 2, 0, 1), 1e-4f);
    }

    @Test
    void theGapIsAnEvenBandSoItTakesLessAngleFurtherOut() {
        float near = UiArcDiagram.gapDegrees(8, 50);
        float far = UiArcDiagram.gapDegrees(8, 100);
        assertTrue(far < near, "the same width takes a smaller angle at the rim");
        // A side 4 off the divider at radius r sits at r * sin(half the angle) from it, at every radius
        assertEquals(4, 50 * Math.sin(Math.toRadians(near / 2)), 1e-4);
        assertEquals(4, 100 * Math.sin(Math.toRadians(far / 2)), 1e-4);
        assertEquals(0, UiArcDiagram.gapDegrees(8, 0));
    }

    @Test
    void theFillRisesFromTheFloorToTheFullAtThePeak() {
        assertEquals(0, UiArcDiagram.intensity(0, 10, 0.2f));
        assertEquals(0, UiArcDiagram.intensity(5, 0, 0.2f));
        assertEquals(0.2f, UiArcDiagram.intensity(1e-9, 10, 0.2f), 1e-4f);
        assertEquals(0.6f, UiArcDiagram.intensity(5, 10, 0.2f), 1e-4f);
        assertEquals(1, UiArcDiagram.intensity(10, 10, 0.2f));
        assertEquals(1, UiArcDiagram.intensity(30, 10, 0.2f), "never over the full");
    }
}
