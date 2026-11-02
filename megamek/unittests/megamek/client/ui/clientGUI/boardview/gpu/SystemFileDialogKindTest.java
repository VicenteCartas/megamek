/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.SystemFileDialog.Kind.AWT;
import static megamek.client.ui.clientGUI.boardview.gpu.SystemFileDialog.Kind.NATIVE;
import static megamek.client.ui.clientGUI.boardview.gpu.SystemFileDialog.Kind.SWING;
import static megamek.client.ui.clientGUI.boardview.gpu.SystemFileDialog.X11_TOOLKIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.utils.Os;
import org.junit.jupiter.api.Test;

/** Which file dialog each system gets, and that the switch and a failed dialog fall back to Swing's. */
class SystemFileDialogKindTest {
    private static final String WINDOWS_TOOLKIT = "sun.awt.windows.WToolkit";
    private static final String MAC_TOOLKIT = "sun.lwawt.macosx.LWCToolkit";

    @Test
    void eachSystemGetsItsOwnDialog() {
        assertEquals(NATIVE, SystemFileDialog.kind(Os.Windows, null, WINDOWS_TOOLKIT, null, true, true));
        assertEquals(AWT, SystemFileDialog.kind(Os.MacOsX, null, MAC_TOOLKIT, null, false, true));
        assertEquals(AWT, SystemFileDialog.kind(Os.Linux, null, X11_TOOLKIT, null, false, true));
    }

    @Test
    void theSwitchTurnsEverySystemsDialogOff() {
        assertEquals(SWING, SystemFileDialog.kind(Os.Windows, "false", WINDOWS_TOOLKIT, null, true, true));
        assertEquals(SWING, SystemFileDialog.kind(Os.MacOsX, " FALSE ", MAC_TOOLKIT, null, false, true));
        assertEquals(SWING, SystemFileDialog.kind(Os.Linux, "false", X11_TOOLKIT, null, false, true));
        assertEquals(AWT, SystemFileDialog.kind(Os.MacOsX, "true", MAC_TOOLKIT, null, false, true));
        assertEquals(NATIVE, SystemFileDialog.kind(Os.Windows, "", WINDOWS_TOOLKIT, null, true, true));
    }

    @Test
    void aDialogThatFailedOrCantShowGtksFallsBackToSwings() {
        assertEquals(SWING, SystemFileDialog.kind(Os.Windows, null, WINDOWS_TOOLKIT, null, false, true));
        assertEquals(SWING, SystemFileDialog.kind(Os.MacOsX, null, MAC_TOOLKIT, null, false, false));
        assertEquals(SWING, SystemFileDialog.kind(Os.Linux, null, X11_TOOLKIT, null, false, false));
        assertEquals(SWING, SystemFileDialog.kind(Os.Linux, null, X11_TOOLKIT, "true", false, true));
        assertEquals(SWING, SystemFileDialog.kind(Os.Linux, null, "sun.awt.wl.WLToolkit", null, false, true));
        assertEquals(SWING, SystemFileDialog.kind(Os.Android, null, null, null, false, true));
    }

    @Test
    void onlyMacOsSavePanelIsTrustedToConfirmAReplace() {
        assertTrue(SystemFileDialog.awtAsksReplace(Os.MacOsX));
        assertFalse(SystemFileDialog.awtAsksReplace(Os.Linux));
    }
}
