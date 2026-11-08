/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.function.BooleanSupplier;
import javax.swing.JDialog;
import javax.swing.JFrame;

import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.windows.User32;

/**
 * Windows: a Swing dialog owned natively by the board window stays above it without being topmost, hides with it
 * when it is minimized, and returns to its own owner before the board window is destroyed. A visible frame stands in
 * for the native board window, which needs no GL context for this.
 */
class GpuNativeDialogOwnerTest {
    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TOPMOST = 0x8L;
    private static final long WS_VISIBLE = 0x10000000L;

    private JFrame board;
    private JFrame hiddenClient;
    private JDialog dialog;
    private GpuNativeDialogOwner owner;

    @BeforeEach
    void open() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        assumeTrue(GpuNativeDialogOwner.Win32.supported(), "Windows with FlatLaf's native library");
        GpuTestSupport.onSwing(() -> {
            board = new JFrame("board");
            board.setBounds(100, 100, 400, 300);
            board.setVisible(true);
            hiddenClient = new JFrame("client");
            hiddenClient.pack();
            dialog = new JDialog(hiddenClient, "options", false);
            dialog.setBounds(150, 150, 200, 120);
            dialog.setVisible(true);
            owner = GpuNativeDialogOwner.forWindows(FlatNativeWindowsLibrary.getHWND(board));
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        GpuTestSupport.onSwing(() -> {
            if (owner != null) {
                owner.releaseAll();
            }
            for (var window : new java.awt.Window[] { dialog, hiddenClient, board }) {
                if (window != null) {
                    window.dispose();
                }
            }
            return null;
        });
    }

    @Test
    void adoptedDialogIsOwnedByTheBoardWithoutFloatingAboveOtherApplications() throws Exception {
        long dialogHandle = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.Win32.handle(dialog));
        long previous = nativeOwner(dialogHandle);
        assertNotEquals(owner.ownerHandle(), previous);

        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));

        assertEquals(owner.ownerHandle(), nativeOwner(dialogHandle));
        assertTrue(owner.owns(dialog));
        assertEquals(0, User32.GetWindowLongPtr(dialogHandle, GWL_EXSTYLE) & WS_EX_TOPMOST);
        assertFalse(dialog.isAlwaysOnTop());

        owner.releaseAll();

        assertEquals(previous, nativeOwner(dialogHandle));
        assertFalse(owner.owns(dialog));
        assertFalse(GpuTestSupport.onSwing(() -> owner.adopt(dialog)), "a released owner adopts nothing more");
    }

    @Test
    void minimizingTheBoardHidesItsDialog() throws Exception {
        long dialogHandle = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.Win32.handle(dialog));
        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));

        GpuTestSupport.onSwing(() -> {
            board.setExtendedState(Frame.ICONIFIED);
            return null;
        });
        assertTrue(eventually(() -> !visible(dialogHandle)), "the dialog hides with its minimized owner");

        GpuTestSupport.onSwing(() -> {
            board.setExtendedState(Frame.NORMAL);
            return null;
        });
        assertTrue(eventually(() -> visible(dialogHandle)), "and returns with it");
    }

    @Test
    void forgottenDialogIsNotRestored() throws Exception {
        long dialogHandle = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.Win32.handle(dialog));
        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));

        GpuTestSupport.onSwing(() -> {
            owner.forget(dialog);
            return null;
        });
        owner.releaseAll();

        assertEquals(owner.ownerHandle(), nativeOwner(dialogHandle));
    }

    private static long nativeOwner(long handle) {
        return User32.GetWindowLongPtr(handle, GpuNativeDialogOwner.Win32.GWLP_HWNDPARENT);
    }

    private static boolean visible(long handle) {
        return (User32.GetWindowLongPtr(handle, GpuNativeDialogOwner.Win32.GWL_STYLE) & WS_VISIBLE) != 0;
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
