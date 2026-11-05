/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import megamek.common.Hex;
import megamek.common.board.Board;
import org.junit.jupiter.api.Test;

class GpuLobbyWindowTest {
    @Test
    void theMapDoesNotHoldTheEdtWhileItBakes() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean readOnEdt = new AtomicBoolean(), deliveredOnEdt = new AtomicBoolean();
        AtomicReference<BufferedImage> image = new AtomicReference<>();
        AtomicReference<SwingWorker<BufferedImage, Void>> worker = new AtomicReference<>();
        Board board = heldBoard(reading, release, readOnEdt);
        try {
            SwingUtilities.invokeAndWait(() -> worker.set(GpuLobbyWindow.bakeUnitBayMap(board, result -> {
                deliveredOnEdt.set(SwingUtilities.isEventDispatchThread());
                image.set(result);
                completed.countDown();
            })));
            assertTrue(reading.await(10, TimeUnit.SECONDS), "the worker starts reading the board");
            CountDownLatch uiResponsive = new CountDownLatch(1);
            SwingUtilities.invokeLater(uiResponsive::countDown);
            assertTrue(uiResponsive.await(5, TimeUnit.SECONDS), "the EDT remains available during the bake");
            assertFalse(readOnEdt.get(), "the board is read by the worker");
            assertEquals(1, completed.getCount(), "the image has not finished yet");
            release.countDown();
            assertTrue(completed.await(30, TimeUnit.SECONDS), "the completed picture is delivered");
            assertTrue(deliveredOnEdt.get(), "the host receives completion on the EDT before posting to the GPU");
            assertNotNull(image.get());
        } finally {
            release.countDown();
            if (worker.get() != null) { worker.get().cancel(true); }
        }
    }

    @Test
    void aCancelledBakeDoesNotPublishItsPicture() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean();
        Board board = heldBoard(reading, release, new AtomicBoolean());
        SwingWorker<BufferedImage, Void> worker = GpuLobbyWindow.bakeUnitBayMap(board,
              image -> delivered.set(true));
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            // SwingWorker invokes done on this thread when cancellation is requested from the EDT.
            SwingUtilities.invokeAndWait(() -> assertTrue(worker.cancel(true)));
            assertTrue(worker.isCancelled());
            assertFalse(delivered.get(), "closing the bay must not publish a cancelled result");
        } finally {
            release.countDown();
            worker.cancel(true);
        }
    }

    private static Board heldBoard(CountDownLatch reading, CountDownLatch release, AtomicBoolean readOnEdt) {
        return new Board(1, 1, new Hex()) {
            @Override
            public int getWidth() {
                readOnEdt.set(SwingUtilities.isEventDispatchThread());
                reading.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The test did not release the board");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return super.getWidth();
            }
        };
    }

    @Test
    void trimmedKeepsTheVisiblePixelsAndAPixelOfMargin() {
        BufferedImage tile = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(20, 10, 0xFF112233);
        tile.setRGB(50, 40, 0xFF445566);

        BufferedImage cut = GpuLobbyWindow.trimmed(tile);

        assertEquals(33, cut.getWidth());
        assertEquals(33, cut.getHeight());
        assertEquals(0xFF112233, cut.getRGB(1, 1));
        assertEquals(0xFF445566, cut.getRGB(31, 31));
    }

    @Test
    void trimmedStopsAtTheImageEdges() {
        BufferedImage tile = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(0, 0, 0xFFFFFFFF);
        tile.setRGB(9, 9, 0xFFFFFFFF);

        BufferedImage cut = GpuLobbyWindow.trimmed(tile);

        assertEquals(10, cut.getWidth());
        assertEquals(10, cut.getHeight());
    }

    @Test
    void trimmedLeavesAClearImageAlone() {
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);

        assertSame(tile, GpuLobbyWindow.trimmed(tile));
    }
}
