/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuBoardFailureDetailsTest {

    @Test
    void detailsNameTheRootCauseAndMegaMeksInnermostFrame() {
        RuntimeException root = new NullPointerException("Cannot read field \"region\"");
        root.setStackTrace(new StackTraceElement[] {
              new StackTraceElement("java.util.HashMap", "get", "HashMap.java", 10),
              new StackTraceElement("megamek.client.ui.clientGUI.boardview.gpu.GpuUnitIcons", "draw", "x.java", 1234),
              new StackTraceElement("megamek.client.ui.clientGUI.boardview.gpu.GpuBattleView", "render", "y.java", 5)
        });

        String details = GpuBoardWindow.failureDetails(new IllegalStateException("wrapped", root));

        assertEquals("NullPointerException in GpuUnitIcons.draw (line 1234)", details);
    }

    @Test
    void onlyGraphicsStackFailuresSuggestAnotherCard() {
        RuntimeException gl = new RuntimeException("shader");
        gl.setStackTrace(new StackTraceElement[] {
              new StackTraceElement("org.lwjgl.opengl.GL20", "glLinkProgram", "GL20.java", 1) });
        RuntimeException ours = new NullPointerException();
        ours.setStackTrace(new StackTraceElement[] {
              new StackTraceElement("megamek.client.ui.clientGUI.boardview.gpu.GpuUnitIcons", "draw", "x.java", 1) });

        assertTrue(GpuBoardWindow.graphicsFailure(new IllegalStateException(gl)));
        assertTrue(GpuBoardWindow.graphicsFailure(new UnsatisfiedLinkError("lwjgl")));
        assertFalse(GpuBoardWindow.graphicsFailure(ours));
    }
}
