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
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.client.ui.dialogs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import megamek.common.loaders.MekSummaryCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnitLoadingDialogTest {
    /**
     * The longest wait for the event thread or the dialog: generous, since in a whole test run the event thread and the
     * dialog's timer share the machine with every other test, and a wait that runs out fails the test.
     */
    private static final long WAIT_SECONDS = 60;

    @Test
    void closesIfNormalCacheLoadCompletesDuringListenerRegistration() {
        assertTrue(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(false, true));
    }

    @Test
    void waitsForExplicitRefreshOrRebuildToBegin() {
        assertFalse(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(true, true));
        assertFalse(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(false, false));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void keepsUpdatingWhileUnitActionsWaitForCacheCompletion(boolean upcomingLoad) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        AtomicBoolean initialized = new AtomicBoolean(upcomingLoad);
        AtomicInteger zipCount = new AtomicInteger();
        AtomicReference<MekSummaryCache.Listener> listener = new AtomicReference<>();
        when(cache.isInitialized()).thenAnswer(invocation -> initialized.get());
        when(cache.getZipCount()).thenAnswer(invocation -> zipCount.get());
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(cache).addListener(any());

        CountDownLatch progressUpdated = new CountDownLatch(1);
        UnitLoadingDialog dialog = onEdt(() -> {
            UnitLoadingDialog loading = new UnitLoadingDialog(null, cache, "Loading units...", upcomingLoad);
            for (Component component : loading.getContentPane().getComponents()) {
                if (component instanceof JLabel label) {
                    label.addPropertyChangeListener("text", event -> {
                        if ("11026".equals(event.getNewValue())) {
                            progressUpdated.countDown();
                        }
                    });
                }
            }
            return loading;
        });
        FutureTask<Void> showDialog = new FutureTask<>(() -> {
            dialog.setVisible(true);
            return null;
        });

        try {
            initialized.set(false);
            SwingUtilities.invokeLater(showDialog);
            onEdt(() -> {
                assertTrue(dialog.isShowing());
                assertFalse(showDialog.isDone(), "Cache-dependent tools must wait for the load to finish");
                // Closing the progress window must not release unit tools into blocking cache lookups.
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                assertTrue(dialog.isShowing());
                return null;
            });
            zipCount.set(11026);
            assertTrue(progressUpdated.await(WAIT_SECONDS, TimeUnit.SECONDS), "Swing must keep processing progress updates");
            assertFalse(showDialog.isDone());

            initialized.set(true);
            listener.get().doneLoading(); // The real cache sends this from its worker thread.
            showDialog.get(WAIT_SECONDS, TimeUnit.SECONDS);
            onEdt(() -> {
                assertFalse(dialog.isDisplayable(), "Completion must release the dialog's native resources");
                return null;
            });
            verify(cache).removeListener(listener.get());
        } finally {
            onEdt(() -> {
                dialog.dispose();
                return null;
            });
        }
    }

    @Test
    void backgroundLoadingKeepsLobbyInteractiveAndClosesWhenReady() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        AtomicReference<MekSummaryCache.Listener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(cache).addListener(any());
        CountDownLatch mapClicked = new CountDownLatch(1);
        JFrame lobby = onEdt(() -> {
            JFrame frame = new JFrame("Lobby loading test");
            JButton map = new JButton("Select Map");
            map.addActionListener(event -> mapClicked.countDown());
            frame.add(map);
            frame.setBounds(100, 100, 300, 200);
            frame.setVisible(true);
            return frame;
        });
        try {
            UnitLoadingDialog dialog = onEdt(() -> {
                UnitLoadingDialog loading = new UnitLoadingDialog(lobby, cache);
                loading.setLocation(lobby.getX() + lobby.getWidth() + 20, lobby.getY());
                loading.showForBackgroundLoad();
                assertTrue(loading.isShowing());
                assertFalse(loading.isModal());
                return loading;
            });
            onEdt(() -> {
                Component button = lobby.getContentPane().getComponent(0);
                // Send input through Swing's event queue so any modal event filter still applies, without relying
                // on this test window having desktop focus (other tests and applications may also be open).
                var events = Toolkit.getDefaultToolkit().getSystemEventQueue();
                long now = System.currentTimeMillis();
                int x = button.getWidth() / 2;
                int y = button.getHeight() / 2;
                events.postEvent(new MouseEvent(button, MouseEvent.MOUSE_PRESSED, now,
                      InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
                events.postEvent(new MouseEvent(button, MouseEvent.MOUSE_RELEASED, now,
                      0, x, y, 1, false, MouseEvent.BUTTON1));
                return null;
            });
            assertTrue(mapClicked.await(WAIT_SECONDS, TimeUnit.SECONDS), "The lobby must receive clicks while units load");
            assertFalse(cache.isInitialized());

            listener.get().doneLoading();
            onEdt(() -> {
                assertFalse(dialog.isDisplayable());
                assertTrue(lobby.isShowing());
                return null;
            });
            verify(cache).removeListener(listener.get());
        } finally {
            onEdt(() -> {
                lobby.dispose();
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void dismissingBackgroundProgressOrLeavingLobbyStopsMonitoring(boolean closeLobby) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        AtomicReference<MekSummaryCache.Listener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(cache).addListener(any());
        JFrame lobby = onEdt(JFrame::new);
        UnitLoadingDialog dialog = onEdt(() -> {
            UnitLoadingDialog loading = new UnitLoadingDialog(lobby, cache);
            loading.showForBackgroundLoad();
            return loading;
        });
        try {
            onEdt(() -> {
                if (closeLobby) {
                    lobby.dispose();
                } else {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                }
                assertFalse(dialog.isDisplayable());
                return null;
            });
            verify(cache).removeListener(listener.get());
            assertFalse(cache.isInitialized(), "Closing progress must not depend on the cache finishing");
            // A completion already captured by the worker may arrive after the window has been closed.
            listener.get().doneLoading();
            onEdt(() -> {
                dialog.setVisible(true);
                assertFalse(dialog.isDisplayable());
                return null;
            });
        } finally {
            onEdt(() -> {
                dialog.dispose();
                lobby.dispose();
                return null;
            });
        }
    }

    @Test
    void doesNotOpenWhenTheLoadFinishesDuringListenerRegistration() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        when(cache.isInitialized()).thenReturn(false, true);
        assertDoesNotOpen(cache);
        verify(cache).removeListener(any());
    }

    @Test
    void doesNotOpenOrRegisterAListenerWhenCacheIsAlreadyLoaded() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        when(cache.isInitialized()).thenReturn(true);
        assertDoesNotOpen(cache);
        verify(cache, never()).addListener(any());
    }

    private static void assertDoesNotOpen(MekSummaryCache cache) throws Exception {
        UnitLoadingDialog dialog = onEdt(() -> new UnitLoadingDialog(null, cache));
        try {
            onEdt(() -> {
                dialog.setVisible(true);
                assertFalse(dialog.isVisible());
                assertFalse(dialog.isDisplayable());
                return null;
            });
        } finally {
            onEdt(() -> {
                dialog.dispose();
                return null;
            });
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
