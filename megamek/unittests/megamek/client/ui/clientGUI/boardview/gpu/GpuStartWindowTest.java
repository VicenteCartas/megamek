/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GpuStartWindowTest {
    /** A 4K primary at 150% and, to its right, a 1440p display at 100%: Swing keeps each origin in device pixels. */
    private static final List<Rectangle> SCREENS = List.of(new Rectangle(0, 0, 2560, 1440),
          new Rectangle(3840, 0, 2560, 1440));
    private static final List<Double> SCALES = List.of(1.5, 1.0);

    @Test
    void aWindowOnThePrimaryUsesItsScale() {
        assertEquals(new Rectangle(100, 200, 1280, 720),
              GpuStartWindow.toSwing(new Rectangle(150, 300, 1920, 1080), SCREENS, SCALES));
    }

    @Test
    void aWindowOnTheSecondDisplayUsesItsOwnScaleAroundItsOrigin() {
        assertEquals(new Rectangle(4040, 100, 1600, 900),
              GpuStartWindow.toSwing(new Rectangle(4040, 100, 1600, 900), SCREENS, SCALES));
    }

    @Test
    void aSecondDisplayAtAHigherScaleScalesAroundItsOrigin() {
        List<Rectangle> screens = List.of(new Rectangle(0, 0, 1920, 1080), new Rectangle(1920, 0, 1280, 720));
        assertEquals(new Rectangle(2020, 100, 800, 400),
              GpuStartWindow.toSwing(new Rectangle(2120, 200, 1600, 800), screens, List.of(1.0, 2.0)));
    }

    @Test
    void aWindowOffEveryDisplayUsesThePrimary() {
        assertEquals(new Rectangle(-2000, 0, 200, 200),
              GpuStartWindow.toSwing(new Rectangle(-3000, 0, 300, 300), SCREENS, SCALES));
    }

    @AfterEach
    void resume() throws Exception {
        SwingUtilities.invokeAndWait(GpuStartWindow::resume);
    }

    @Test
    void aLaunchedWindowDropsTheStartScreensRequestsUntilItCloses() throws Exception {
        List<String> handled = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> GpuStartWindow.suspendWhile(mock(Window.class)));
        assertTrue(GpuStartWindow.isSuspended());

        GpuStartWindow.request(handled::add, "host while the skin editor is open");
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(handled.isEmpty());

        SwingUtilities.invokeAndWait(GpuStartWindow::resume);
        assertFalse(GpuStartWindow.isSuspended());
        GpuStartWindow.request(handled::add, "host");
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of("host"), handled);
    }
}
