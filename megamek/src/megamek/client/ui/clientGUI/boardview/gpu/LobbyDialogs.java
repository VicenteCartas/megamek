/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import jakarta.annotation.Nullable;

/**
 * The one path every Swing dialog of the native lobby takes: game options, player settings, bot setup, camouflage, unit
 * customisation, C3, map setup, the load and save choosers and every question and alert. The lobby opens them all on
 * {@link #parent()}, the client's frame, which stays hidden behind the native window; the map setup's, the lobby
 * services' and the lobby prompts' dialogs use that frame too. A Swing dialog of that frame, or a modal one Swing gives its shared frame (a
 * {@code JOptionPane} without a parent, as MekHQ asks while it sets up a game), is then caught as it shows:
 * <ul>
 *     <li>centred over the native window rather than the hidden frame, which would put it at the screen's corner or
 *     on another monitor;</li>
 *     <li>owned natively by the window ({@link GpuNativeDialogOwner}), or kept on top where the platform can't, so it
 *     stays in front of it;</li>
 *     <li>when modal, blocking the lobby's input until the last modal one closes, as its Swing owner would be;</li>
 *     <li>giving the focus back to the native window as the last one closes, when it had it.</li>
 * </ul>
 * EDT only.
 */
final class LobbyDialogs {
    /** What the dialogs need from the lobby window. */
    interface Host {
        /** The client's hidden frame, which owns the lobby's dialogs. */
        @Nullable JFrame frame();

        /** The native window's owner for dialogs, once the window exists. */
        @Nullable GpuNativeDialogOwner nativeOwner();

        /** The native window's client area in Swing's coordinates, or null when it doesn't show. */
        @Nullable Rectangle bounds();

        /** Blocks or restores the lobby's input. */
        void inputBlocked(boolean blocked);

        /** Gives the focus back to the native window. */
        void focus();

        /** Whether the lobby is closing, so new dialogs are left alone. */
        boolean closing();
    }

    private final Host host;
    private final Set<Dialog> modal = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> onTop = new HashMap<>();
    private final Set<Dialog> raised = new HashSet<>();
    private final AWTEventListener listener = this::event;
    /** Whether one of the dialogs had the focus, so the native window takes it back as the last one closes. */
    private boolean hadFocus;
    private volatile boolean blocked;

    LobbyDialogs(Host host) {
        this.host = host;
    }

    /** The owner for every dialog the lobby opens itself. */
    @Nullable JFrame parent() {
        return host.frame();
    }

    /** Whether a modal dialog is open, so the lobby's input is blocked. */
    boolean blocked() {
        return blocked;
    }

    void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Stops watching, and puts the dialogs it kept on top back as they were. */
    void uninstall() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        modal.clear();
        raised.clear();
        blocked = false;
        hadFocus = false;
        onTop.forEach(Dialog::setAlwaysOnTop);
        onTop.clear();
    }

    /** Once the native window shows: catches the dialogs that opened before it. */
    void presented() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Dialog dialog && !(dialog instanceof FileDialog)
                  && belongs(dialog)) {
                show(dialog);
                if (dialog.isFocused()) {
                    hadFocus = true;
                }
            }
        }
    }

    void event(AWTEvent event) {
        if (!(event.getSource() instanceof Dialog dialog)) {
            return;
        }
        switch (event.getID()) {
            case ComponentEvent.COMPONENT_SHOWN -> {
                if (dialog instanceof FileDialog) {
                    // The system's file dialog has no window of AWT's to own, place or float; as a modal dialog it
                    // only blocks the lobby's input, and a click on the window brings it to the front
                    if (!host.closing() && belongs(dialog) && dialog.isModal() && modal.add(dialog)
                          && modal.size() == 1) {
                        setBlocked(true);
                    }
                } else if (!host.closing() && belongs(dialog)) {
                    show(dialog);
                }
            }
            case ComponentEvent.COMPONENT_HIDDEN -> gone(dialog, false);
            case WindowEvent.WINDOW_CLOSED -> gone(dialog, true);
            case WindowEvent.WINDOW_ACTIVATED -> {
                // A dialog can take the focus before its showing comes through
                if (raised.contains(dialog) || !host.closing() && belongs(dialog)) {
                    hadFocus = true;
                }
            }
            case WindowEvent.WINDOW_DEACTIVATED -> {
                // Another application took the focus while the dialog stays open
                if (raised.contains(dialog) && dialog.isShowing()
                      && ((WindowEvent) event).getOppositeWindow() == null) {
                    hadFocus = false;
                }
            }
            default -> {
            }
        }
    }

    /**
     * A dialog of the client's hidden frame, or a modal one without an owner of its own, which Swing gives its hidden
     * shared frame: both would otherwise hide behind the native window.
     */
    boolean belongs(Dialog dialog) {
        JFrame frame = host.frame();
        Frame shared;
        try {
            shared = JOptionPane.getRootFrame();
        } catch (HeadlessException headless) {
            shared = null;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == frame && frame != null || current == shared && dialog.isModal()) {
                return true;
            }
        }
        return false;
    }

    /** EDT: brings the newest open modal dialog to the front when the window is clicked or asked to close. */
    void frontModal() {
        List<Dialog> showing = new ArrayList<>();
        for (Dialog dialog : modal) {
            if (dialog.isShowing()) {
                showing.add(dialog);
            }
        }
        if (!showing.isEmpty()) {
            showing.get(showing.size() - 1).toFront();
        }
    }

    /** Where a window of {@code size} sits centred over {@code area}. */
    static Point centred(Rectangle area, Dimension size) {
        return new Point(area.x + (area.width - size.width) / 2, area.y + (area.height - size.height) / 2);
    }

    private void show(Dialog dialog) {
        centre(dialog);
        raise(dialog);
        raised.add(dialog);
        if (dialog.isActive()) {
            hadFocus = true;
        }
        if (dialog.isModal() && modal.add(dialog) && modal.size() == 1) {
            setBlocked(true);
        }
    }

    /** Centres a dialog of the hidden frame over the window; one of another dialog stays where that one puts it. */
    private void centre(Dialog dialog) {
        Rectangle bounds = host.bounds();
        if (bounds == null || dialog.getOwner() instanceof Dialog) {
            return;
        }
        dialog.setLocation(centred(bounds, dialog.getSize()));
    }

    /** Keeps a dialog above the window until either closes: owned natively where it can be, otherwise on top. */
    private void raise(Dialog dialog) {
        GpuNativeDialogOwner owner = host.nativeOwner();
        if (owner != null && (dialog.getOwner() instanceof Dialog parent && parent.isShowing() && owner.owns(parent)
              || owner.adopt(dialog))) {
            Boolean wasOnTop = onTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
        } else {
            onTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
            dialog.setAlwaysOnTop(true);
        }
        dialog.toFront();
    }

    private void gone(Dialog dialog, boolean closed) {
        if (modal.remove(dialog) && modal.isEmpty()) {
            setBlocked(false);
        }
        GpuNativeDialogOwner owner = host.nativeOwner();
        if (owner != null) {
            if (closed) {
                owner.forget(dialog);
            } else {
                owner.hidden(dialog);
            }
        }
        if (closed) {
            Boolean wasOnTop = onTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
        }
        if (raised.remove(dialog) && hadFocus && raised.stream().noneMatch(Window::isShowing)) {
            hadFocus = false;
            if (!host.closing()) {
                host.focus();
            }
        }
    }

    private void setBlocked(boolean blocked) {
        this.blocked = blocked;
        host.inputBlocked(blocked);
    }
}
