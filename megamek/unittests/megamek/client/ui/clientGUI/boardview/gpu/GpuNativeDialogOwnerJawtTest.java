/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;

/**
 * JAWT finds the native windows of Swing dialogs, and on macOS and Linux (X11) the board window owns a dialog by
 * them. A visible frame stands in for the native board window, which needs no GL context for this.
 */
class GpuNativeDialogOwnerJawtTest {
    private JFrame board;
    private JFrame hiddenClient;
    private JDialog dialog;
    private GpuNativeDialogOwner owner;

    @BeforeEach
    void open() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        GpuTestSupport.onSwing(() -> {
            board = new JFrame("board");
            board.setBounds(100, 100, 400, 300);
            board.setVisible(true);
            hiddenClient = new JFrame("client");
            hiddenClient.pack();
            dialog = new JDialog(hiddenClient, "options", false);
            dialog.setBounds(150, 150, 200, 120);
            dialog.setVisible(true);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        GpuTestSupport.onSwing(() -> {
            if (owner != null) {
                owner.releaseAll();
            }
            for (Window window : new Window[] { dialog, hiddenClient, board }) {
                if (window != null) {
                    window.dispose();
                }
            }
            return null;
        });
    }

    @Test
    void jawtReadsAShowingDialogsNativeWindow() throws Exception {
        long platformInfo = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.Jawt.withPlatformInfo(dialog, info -> info));
        assertNotEquals(0, platformInfo);
    }

    @Test
    void onWindowsJawtAndFlatLafAgreeOnTheDialogsWindow() throws Exception {
        assumeTrue(GpuNativeDialogOwner.Win32.supported(), "Windows with FlatLaf's native library");
        long jawt = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.Jawt.withPlatformInfo(dialog,
              info -> JAWTWin32DrawingSurfaceInfo.create(info).hwnd()));
        assertEquals(GpuTestSupport.onSwing(() -> FlatNativeWindowsLibrary.getHWND(dialog)), jawt);
    }

    @Test
    void onMacOsTheDialogBecomesAChildWindowOfTheBoardUntilReleased() throws Exception {
        assumeTrue(SharedLibraryLoader.os == Os.MacOsX);
        owner = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.forSwingWindow(board));
        assertNotNull(owner, "the board's NSWindow");

        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));
        long window = owner.adoptedHandle(dialog);
        assertNotEquals(0, window);
        assertTrue(eventually(() -> parentWindow(window) == owner.ownerHandle()), "AppKit made it a child window");

        GpuTestSupport.onSwing(() -> {
            owner.hidden(dialog);
            return null;
        });
        assertFalse(owner.owns(dialog));
        assertTrue(eventually(() -> parentWindow(window) != owner.ownerHandle()), "a hidden dialog is let go");

        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));
        assertTrue(eventually(() -> parentWindow(window) == owner.ownerHandle()), "and taken back when shown");

        owner.releaseAll();
        assertNotEquals(owner.ownerHandle(), parentWindow(window), "released before the board closes");
    }

    @Test
    void onLinuxTheDialogsTopLevelWindowIsMarkedTransientForTheBoard() throws Exception {
        assumeTrue(SharedLibraryLoader.os == Os.Linux);
        owner = GpuTestSupport.onSwing(() -> GpuNativeDialogOwner.forSwingWindow(board));
        assumeTrue(owner != null, "an X11 display (not native Wayland)");

        assertTrue(GpuTestSupport.onSwing(() -> owner.adopt(dialog)));
        assertNotEquals(0, owner.adoptedHandle(dialog));
        assertNotEquals(owner.ownerHandle(), owner.adoptedHandle(dialog));
    }

    private static long parentWindow(long window) {
        return GpuNativeDialogOwner.ObjC.send(window, "parentWindow");
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
