/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.awaitDialog;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/** The native modal bridge: the EDT waits in a nested loop, the test thread plays the GL thread. No GL is used. */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuModalBridgeTest {
    private static final long WAIT_SECONDS = 20;

    @Test
    void askReturnsTheAnswerPostedByTheRenderThreadWhileSwingKeepsRunning() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FutureTask<DialogAnswer> ask = askLater(() -> fixture.source.ask(request("Continue?")));
            DialogRequest shown = awaitDialog(fixture.source, "Continue?");
            AtomicBoolean pumped = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> pumped.set(true));
            assertTrue(pumped.get(), "The EDT keeps dispatching events while the dialog waits");
            assertFalse(ask.isDone());

            fixture.source.answer(shown.id(), new DialogAnswer(0, List.of(), "text", false, List.of()));
            assertEquals(new DialogAnswer(0, List.of(), "text", false, List.of()), ask.get(WAIT_SECONDS, SECONDS));
            assertNull(fixture.source.dialog());
        }
    }

    @Test
    void closeCancelsEveryPendingAskAndLaterAsksFallBackToSwing() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FutureTask<DialogAnswer> outer = askLater(() -> fixture.source.ask(request("Outer")));
            awaitDialog(fixture.source, "Outer");
            FutureTask<DialogAnswer> inner = askLater(() -> fixture.source.ask(request("Inner")));
            awaitDialog(fixture.source, "Inner");

            DialogRequest shownAfterClose = onSwing(() -> {
                fixture.source.close();
                return fixture.source.dialog();
            });
            assertNull(shownAfterClose, "No cancelled dialog is shown while its caller unwinds");
            // Cancelled equals closing the Swing dialog: the cancel button and the unchanged checkbox.
            DialogAnswer cancelled = new DialogAnswer(1, List.of(), null, true, List.of());
            assertEquals(cancelled, inner.get(WAIT_SECONDS, SECONDS));
            assertEquals(cancelled, outer.get(WAIT_SECONDS, SECONDS));
            assertNull(fixture.source.dialog());
            assertNull(askLater(() -> fixture.source.ask(request("Late"))).get(WAIT_SECONDS, SECONDS));
        }
    }

    @Test
    void nestedAsksShowTheNewestAndRepublishTheOuterDialogAfterIt() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> returned = new CopyOnWriteArrayList<>();
            FutureTask<DialogAnswer> outer = askLater(() -> ask(fixture.source, "Outer", returned));
            DialogRequest outerShown = awaitDialog(fixture.source, "Outer");
            FutureTask<DialogAnswer> inner = askLater(() -> ask(fixture.source, "Inner", returned));
            DialogRequest innerShown = awaitDialog(fixture.source, "Inner");

            fixture.source.answer(innerShown.id(), answer(1));
            assertEquals(answer(1), inner.get(WAIT_SECONDS, SECONDS));
            assertSame(outerShown, fixture.source.dialog());
            assertFalse(outer.isDone());
            fixture.source.answer(outerShown.id(), answer(0));
            assertEquals(answer(0), outer.get(WAIT_SECONDS, SECONDS));
            assertEquals(List.of("Inner", "Outer"), returned);
            assertNull(fixture.source.dialog());
        }
    }

    @Test
    void anOuterAnswerCannotReturnBeforeTheNewerDialog() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> returned = new CopyOnWriteArrayList<>();
            FutureTask<DialogAnswer> outer = askLater(() -> ask(fixture.source, "Outer", returned));
            DialogRequest outerShown = awaitDialog(fixture.source, "Outer");
            AtomicReference<DialogRequest> shownAfterInner = new AtomicReference<>(outerShown);
            AtomicBoolean inputAfterInner = new AtomicBoolean(true);
            FutureTask<DialogAnswer> inner = askLater(() -> {
                DialogAnswer result = ask(fixture.source, "Inner", returned);
                // The answered outer caller is still suspended below this frame.
                shownAfterInner.set(fixture.source.dialog());
                inputAfterInner.set(fixture.source.acceptsInput());
                return result;
            });
            DialogRequest innerShown = awaitDialog(fixture.source, "Inner");

            fixture.source.answer(outerShown.id(), answer(0));
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(outer.isDone(), "The outer caller resumes only after the newer dialog returns");
            assertSame(innerShown, fixture.source.dialog());
            fixture.source.answer(innerShown.id(), answer(1));
            assertEquals(answer(1), inner.get(WAIT_SECONDS, SECONDS));
            assertNull(shownAfterInner.get(), "The answered outer dialog is not shown again");
            assertFalse(inputAfterInner.get(), "Input waits until the outer caller has returned as well");
            assertEquals(answer(0), outer.get(WAIT_SECONDS, SECONDS));
            assertEquals(List.of("Inner", "Outer"), returned);
            assertTrue(onSwing(fixture.source::acceptsInput));
        }
    }

    @Test
    void windowAsksOnlyThroughAPresentedOpenWindowOfTheSameClient() throws Exception {
        ClientGUI gui = mock(ClientGUI.class, CALLS_REAL_METHODS);
        ClientGUI other = mock(ClientGUI.class);
        DialogRequest request = request("Continue?");
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            assertNull(onSwing(() -> GpuBoardWindow.ask(gui, request)), "No window");
            GpuBoardWindow window = present(gui, fixture.view, fixture.source);
            try {
                set(window, "presented", false);
                assertNull(onSwing(() -> GpuBoardWindow.ask(gui, request)), "Not presented yet");
                set(window, "presented", true);
                set(window, "closing", true);
                assertNull(onSwing(() -> GpuBoardWindow.ask(gui, request)), "Closing");
                set(window, "closing", false);
                assertNull(onSwing(() -> GpuBoardWindow.ask(other, request)), "Another client's window");
                assertNull(GpuBoardWindow.ask(gui, request), "Off the EDT");
                assertNull(fixture.source.dialog());
                // Whether the client takes hotkeys also follows which Swing window is active (ClientGUI
                // isGameWindowActive), which other tests may leave anywhere: the dialog's effect is measured
                // against it.
                boolean idle = onSwing(gui::shouldIgnoreHotKeys);

                FutureTask<DialogAnswer> ask = askLater(() -> GpuBoardWindow.ask(gui, request));
                DialogRequest shown = awaitDialog(fixture.source, "Continue?");
                assertTrue(GpuBoardWindow.dialogPendingFor(gui));
                assertFalse(GpuBoardWindow.dialogPendingFor(other));
                assertTrue(onSwing(gui::shouldIgnoreHotKeys), "ClientGUI ignores hotkeys while the dialog waits");
                fixture.source.answer(shown.id(), answer(0));
                assertEquals(answer(0), ask.get(WAIT_SECONDS, SECONDS));
                assertFalse(GpuBoardWindow.dialogPendingFor(gui));
                assertEquals(idle, onSwing(gui::shouldIgnoreHotKeys),
                      "The answered dialog no longer holds the hotkeys");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void commandsAndBoardInputQueuedBeforeADialogAreDropped() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<Integer> boardEvents = new CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> recordBoardEvents(fixture.view, boardEvents));
            Runnable hold = fixture.source.takeFrame().scene().commands().getFirst().action();
            Coords hex = new Coords(3, 3);
            hold.run();
            fixture.source.click(hex, false, 0);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, fixture.clicks.get(), "Without a dialog the command runs");
            assertEquals(List.of(BoardViewEvent.BOARD_HEX_CLICKED), boardEvents);
            boardEvents.clear();

            // A native click or command lands on the queue just before phase code opens a dialog on the EDT.
            FutureTask<DialogAnswer> ask = askLater(() -> {
                hold.run();
                fixture.source.click(hex, false, 0);
                fixture.source.hover(hex, 0);
                return fixture.source.ask(request("Continue?"));
            });
            DialogRequest shown = awaitDialog(fixture.source, "Continue?");
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, fixture.clicks.get(), "The queued command did not run inside the nested loop");
            assertEquals(List.of(), boardEvents);
            fixture.source.answer(shown.id(), answer(0));
            ask.get(WAIT_SECONDS, SECONDS);

            hold.run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(2, fixture.clicks.get(), "Input is accepted again once the dialog returned");
        }
    }

    @Test
    void swingModalsDropNativeInputButNonModalClientWindowsDoNot() throws Exception {
        // This client ignores hotkeys, as it does while its non-modal Help or accessibility window is open.
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.shouldIgnoreHotKeys()).thenReturn(true);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<Integer> boardEvents = new CopyOnWriteArrayList<>();
            GpuBoardSource source = onSwing(() -> {
                BoardView view = new BoardView(fixture.game, null, gui, 0);
                view.setLocalPlayer(fixture.player.getId());
                // The client shows this board, so its captured commands stay current.
                when(gui.getCurrentBoardState()).thenReturn(Optional.of(view.getClientState()));
                recordBoardEvents(view.getClientState(), boardEvents);
                return new GpuBoardSource(view.getClientState(), () -> fixture.panel);
            });
            AtomicReference<MockedStatic<UIUtil>> modal = new AtomicReference<>();
            try {
                Runnable hold = source.takeFrame().scene().commands().getFirst().action();
                Coords hex = new Coords(3, 3);
                hold.run();
                source.click(hex, false, 0);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, fixture.clicks.get(), "A non-modal window does not block native commands");
                assertEquals(List.of(BoardViewEvent.BOARD_HEX_CLICKED), boardEvents);

                // A Swing modal is shown. Static stubs apply per thread, and the guard runs on the EDT.
                SwingUtilities.invokeAndWait(() -> {
                    modal.set(mockStatic(UIUtil.class, CALLS_REAL_METHODS));
                    modal.get().when(UIUtil::isModalDialogDisplayed).thenReturn(true);
                });
                hold.run();
                source.click(hex, false, 0);
                source.hover(hex, 0);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, fixture.clicks.get(), "The command did not run inside the Swing modal's loop");
                assertEquals(List.of(BoardViewEvent.BOARD_HEX_CLICKED), boardEvents);
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (modal.get() != null) {
                        modal.get().close();
                    }
                    source.close();
                    source.currentView().close();
                });
            }
        }
    }

    @Test
    void askNeverWaitsWhileHoldingAMonitorOtherThreadsNeed() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        DialogRequest request = request("Continue?");
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    synchronized (fixture.source) {
                        assertThrows(IllegalStateException.class, () -> fixture.source.ask(request));
                    }
                });
                // The synchronized open() shows Swing prompts; one raised there keeps using Swing.
                FutureTask<DialogAnswer> held = askLater(() -> {
                    synchronized (GpuBoardWindow.class) {
                        return GpuBoardWindow.ask(gui, request);
                    }
                });
                assertNull(held.get(WAIT_SECONDS, SECONDS));
                assertNull(fixture.source.dialog());
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void renderThreadReadsThePendingDialogWhileAnotherThreadHoldsTheMonitors() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                FutureTask<DialogAnswer> ask = askLater(() -> fixture.source.ask(request("Continue?")));
                DialogRequest shown = awaitDialog(fixture.source, "Continue?");
                CountDownLatch held = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicBoolean timedOut = new AtomicBoolean();
                Thread holder = new Thread(() -> {
                    synchronized (fixture.source) {
                        synchronized (GpuBoardWindow.class) {
                            held.countDown();
                            try {
                                timedOut.set(!release.await(10, SECONDS));
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }, "monitor holder");
                holder.start();
                assertTrue(held.await(WAIT_SECONDS, SECONDS));
                DialogRequest seen = fixture.source.dialog();
                boolean pending = GpuBoardWindow.dialogPendingFor(gui);
                release.countDown();
                holder.join();
                assertFalse(timedOut.get(), "A read waited for a monitor");
                assertSame(shown, seen);
                assertTrue(pending);
                fixture.source.answer(shown.id(), answer(0));
                assertEquals(answer(0), ask.get(WAIT_SECONDS, SECONDS));
            } finally {
                dismiss();
            }
        }
    }

    private static DialogRequest request(String message) {
        return new DialogRequest(0, DialogKind.MESSAGE, "Confirm", message, false, List.of("Yes", "No"), 0, 1,
              List.of(), List.of(), "Do not ask again", true, "", null, null, List.of());
    }

    private static DialogAnswer answer(int button) {
        return new DialogAnswer(button, List.of(), null, false, List.of());
    }

    private static DialogAnswer ask(GpuBoardSource source, String message, List<String> returned) {
        DialogAnswer result = source.ask(request(message));
        returned.add(message);
        return result;
    }

    /** Starts an ask on the EDT without waiting for it, as phase code would from an event. */
    private static FutureTask<DialogAnswer> askLater(Callable<DialogAnswer> ask) {
        FutureTask<DialogAnswer> task = new FutureTask<>(ask);
        SwingUtilities.invokeLater(task);
        return task;
    }

    /** EDT: records the board events that reach the phase display. */
    private static void recordBoardEvents(BoardClientState view, List<Integer> events) {
        view.addBoardViewListener(new BoardViewListenerAdapter() {
            @Override
            public void hexMoused(BoardViewEvent event) {
                events.add(event.getType());
            }
        });
    }
}
