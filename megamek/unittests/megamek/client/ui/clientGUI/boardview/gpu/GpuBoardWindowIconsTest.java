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
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The native window shows the icons of the program that opens it. */
class GpuBoardWindowIconsTest {

    @AfterEach
    void restore() {
        GpuBoardWindow.useIcons("megamek");
    }

    @Test
    void theWindowShowsMegaMeksIconsByDefault() {
        String[] icons = GpuBoardWindow.windowIcons();

        assertEquals(4, icons.length);
        for (String icon : icons) {
            assertTrue(new File(icon).getName().startsWith("megamek-icon-"), icon);
        }
    }

    @Test
    void anotherProgramGivesTheWindowItsIcons() {
        GpuBoardWindow.useIcons("mekhq");

        String[] icons = GpuBoardWindow.windowIcons();

        assertEquals(4, icons.length);
        for (String icon : icons) {
            assertTrue(new File(icon).getName().startsWith("mekhq-icon-"), icon);
        }
    }

    @Test
    void missingIconsAreLeftOut() {
        GpuBoardWindow.useIcons("no-such-program");

        assertEquals(0, GpuBoardWindow.windowIcons().length);
    }
}
