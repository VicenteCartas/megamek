/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.logging.MMLogger;

/**
 * Shows MegaMek's error dialogs, the uncaught exception's among them, in the native window's scene: over the battle,
 * the lobby or the start screen. Swing's message box, owned by no window, opens behind the native window there; over
 * the battle it is not even raised. Unseen, it blocks the start screen as if an invisible dialog were open. Where no
 * scene can draw it, while the native window is open, the message box shows always on top.
 */
public final class GpuErrorPresenter implements MMLogger.ErrorPresenter {

    /** EDT: set while an error shows natively; an error raised meanwhile shows in Swing instead of nesting. */
    private static boolean presenting;

    private GpuErrorPresenter() { }

    /** Shows MegaMek's error dialogs natively from now on. */
    public static void install() {
        MMLogger.setErrorPresenter(new GpuErrorPresenter());
    }

    @Override
    public boolean present(String title, String message, MMLogger.ErrorDialogButton report) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return false;
        }
        String dismiss = report != null ? report.dismissLabel() : Messages.getString("Okay");
        if (!presenting) {
            presenting = true;
            try {
                if (showNatively(title, message, dismiss, report)) {
                    return true;
                }
            } finally {
                presenting = false;
            }
        }
        if (!GpuHost.isOpen()) {
            return false;
        }
        showOnTop(title, message, dismiss, report);
        return true;
    }

    /** EDT: shows the error in the scene that shows, if one can; false otherwise. */
    private static boolean showNatively(String title, String message, String dismiss,
          MMLogger.ErrorDialogButton report) {
        List<String> buttons = report == null ? List.of(dismiss) : List.of(dismiss, report.label());
        Integer pressed = GpuBoardWindow.showError(title, message, buttons);
        if (pressed != null) {
            if (report != null && pressed == 1) {
                report.action().run();
            }
            return true;
        }
        String reportLabel = report == null ? null : report.label();
        Runnable reportAction = report == null ? null : report.action();
        return GpuLobbyWindow.showError(title, message, dismiss, reportLabel, reportAction)
              || GpuStartWindow.showError(title, message, dismiss, reportLabel, reportAction);
    }

    /** EDT: Swing's error box, always on top so the native window cannot hide it. */
    private static void showOnTop(String title, String message, String dismiss, MMLogger.ErrorDialogButton report) {
        Object[] options = report == null ? new Object[] { dismiss } : new Object[] { report.label(), dismiss };
        JOptionPane pane = new JOptionPane(message, JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
              options, dismiss);
        JDialog dialog = pane.createDialog(null, title);
        dialog.setAlwaysOnTop(true);
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
        }
        if (report != null && report.label().equals(pane.getValue())) {
            report.action().run();
        }
    }
}
