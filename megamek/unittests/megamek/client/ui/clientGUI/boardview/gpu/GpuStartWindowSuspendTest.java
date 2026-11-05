/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GpuStartWindowSuspendTest {
    @AfterEach
    void resume() throws Exception {
        SwingUtilities.invokeAndWait(GpuStartWindow::resume);
    }

    @Test
    void aLaunchedWindowDropsTheStartScreensRequestsUntilItCloses() throws Exception {
        List<String> handled = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> GpuStartWindow.suspendWhile(mock(Window.class), () -> { }));
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

    @Test
    void closingTheLaunchedWindowRunsItsEndOnceAndResumes() throws Exception {
        Window launched = mock(Window.class);
        List<String> ended = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> GpuStartWindow.suspendWhile(launched, () -> ended.add("ended")));

        SwingUtilities.invokeAndWait(() -> GpuStartWindow.launchClosed(mock(Window.class)));
        assertTrue(GpuStartWindow.isSuspended());

        SwingUtilities.invokeAndWait(() -> GpuStartWindow.launchClosed(launched));
        SwingUtilities.invokeAndWait(() -> GpuStartWindow.launchClosed(launched));
        assertFalse(GpuStartWindow.isSuspended());
        assertEquals(List.of("ended"), ended);
    }

    @Test
    void theStartScreenResumesEvenWhenEndingTheLaunchFails() throws Exception {
        Window launched = mock(Window.class);
        SwingUtilities.invokeAndWait(() -> GpuStartWindow.suspendWhile(launched, () -> {
            throw new IllegalStateException("the server would not stop");
        }));

        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
              () -> GpuStartWindow.launchClosed(launched)));
        assertFalse(GpuStartWindow.isSuspended());
    }
}
