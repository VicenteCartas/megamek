/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import org.junit.jupiter.api.Test;

class GpuToastsTest {

    @Test
    void aToastRepeatingOneStillShowingIsDroppedUntilItsDurationEnds() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        GpuToasts toasts = new GpuToasts(mock(GpuBoardSource.class), now::get);
        int duration = ToastLevel.WARNING.getDefaultDurationMs();
        List<String> texts = GpuTestSupport.onSwing(() -> {
            toasts.add(ToastLevel.WARNING, "Not enough MP", null);
            toasts.add(ToastLevel.WARNING, "Not enough MP", null);
            toasts.add(ToastLevel.INFO, "Not enough MP", null);
            toasts.add(ToastLevel.WARNING, "Something else", null);
            now.addAndGet(duration - 1);
            toasts.add(ToastLevel.WARNING, "Not enough MP", null);
            now.addAndGet(1);
            toasts.add(ToastLevel.WARNING, "Not enough MP", null);
            return toasts.capture().toasts().stream().map(toast -> toast.level() + " " + toast.text()).toList();
        });
        assertEquals(List.of("WARNING Not enough MP", "INFO Not enough MP", "WARNING Something else",
              "WARNING Not enough MP"), texts);
    }
}
