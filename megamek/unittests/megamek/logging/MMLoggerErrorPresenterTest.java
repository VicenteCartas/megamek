/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An installed error presenter shows MegaMek's error dialogs on the Swing thread instead of Swing's message box. */
class MMLoggerErrorPresenterTest {
    private static final MMLogger LOGGER = MMLogger.create(MMLoggerErrorPresenterTest.class);
    private static final MMLogger.ErrorDialogButton REPORT = new MMLogger.ErrorDialogButton("Report", "Okay", () -> { });

    private record Shown(String title, String message, MMLogger.ErrorDialogButton report, boolean onEdt) { }

    private final List<Shown> shown = new ArrayList<>();

    @AfterEach
    void uninstall() {
        MMLogger.setErrorPresenter(null);
        MMLogger.setErrorDialogButton(null);
    }

    private void install(CountDownLatch presented) {
        MMLogger.setErrorDialogButton(REPORT);
        MMLogger.setErrorPresenter((title, message, report) -> {
            shown.add(new Shown(title, message, report, SwingUtilities.isEventDispatchThread()));
            presented.countDown();
            return true;
        });
    }

    @Test
    void anExceptionsErrorShowsOnThePresenterWithTheReportButton() throws Exception {
        install(new CountDownLatch(1));
        SwingUtilities.invokeAndWait(() -> LOGGER.errorDialog(new IllegalStateException("boom"), "It broke %s",
              "Broken", "here"));

        assertEquals(1, shown.size());
        assertEquals("Broken", shown.get(0).title());
        assertEquals("It broke here", shown.get(0).message());
        assertSame(REPORT, shown.get(0).report());
    }

    @Test
    void anErrorWithoutAnExceptionOffersNoReportButton() throws Exception {
        install(new CountDownLatch(1));
        SwingUtilities.invokeAndWait(() -> LOGGER.errorDialog("Wrong version", "Update the server"));

        assertEquals(1, shown.size());
        assertNull(shown.get(0).report());
    }

    @Test
    void anErrorOffTheSwingThreadShowsLaterOnIt() throws Exception {
        CountDownLatch presented = new CountDownLatch(1);
        install(presented);
        LOGGER.errorDialog(new IllegalStateException("boom"), "From a worker", "Broken");

        assertTrue(presented.await(10, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(shown.get(0).onEdt());
    }
}
