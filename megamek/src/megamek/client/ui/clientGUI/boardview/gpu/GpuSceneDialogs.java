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
 * The one path every Swing dialog of a {@link GpuHost} scene takes while the scene shows; the native lobby's game
 * options, player settings, bot setup, camouflage, unit customisation, C3, map setup, load and save choosers, questions
 * and alerts all take it, and so can another program's scene (MekHQ's unported Swing screens). The scene opens them all
 * on {@link #parent()}, its hidden frame behind the native window. A Swing dialog of that frame, or a modal one Swing
 * gives its shared frame (a {@code JOptionPane} without a parent, as MekHQ asks while it sets up a game), is then caught
 * as it shows:
 * <ul>
 *     <li>centred over the native window rather than the hidden frame, which would put it at the screen's corner or
 *     on another monitor;</li>
 *     <li>owned natively by the window ({@link GpuNativeDialogOwner}), or kept on top where the platform can't, so it
 *     stays in front of it;</li>
 *     <li>when modal, blocking the scene's input until the last modal one closes, as its Swing owner would be;</li>
 *     <li>giving the focus back to the native window as the last one closes, when it had it.</li>
 * </ul>
 *
 * <p>A scene {@link #install}s it as it asks for the window, passes its window to {@link #started} from
 * {@link GpuHost.Scene#started}, calls {@link #presented} on the Swing thread once it shows, {@link #released} as its
 * listener is disposed and {@link #uninstall} once it has ended. Swing thread, except where a method says otherwise.</p>
 */
public final class GpuSceneDialogs {
    /** What the dialogs need from the scene. */
    public interface Host {
        /** The scene's hidden frame, which owns its dialogs. */
        @Nullable JFrame frame();

        /** The native window's client area in Swing's coordinates, or null when it doesn't show. */
        default @Nullable Rectangle bounds() {
            return GpuHost.swingClientBounds();
        }

        /** Blocks or restores the scene's input. */
        void inputBlocked(boolean blocked);

        /** Gives the focus back to the native window. */
        default void focus() {
            GpuHost.focus();
        }

        /** Whether the scene is closing, so new dialogs are left alone. */
        boolean closing();
    }

    private final Host host;
    /** The native window's owner for dialogs while the scene shows; null before, and where the platform has none. */
    private volatile GpuNativeDialogOwner nativeOwner;
    private final Set<Dialog> modal = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> onTop = new HashMap<>();
    private final Set<Dialog> raised = new HashSet<>();
    private final AWTEventListener listener = this::event;
    /** Whether one of the dialogs had the focus, so the native window takes it back as the last one closes. */
    private boolean hadFocus;
    private volatile boolean blocked;

    public GpuSceneDialogs(Host host) {
        this.host = host;
    }

    /** The owner for every dialog the scene opens itself. */
    public @Nullable JFrame parent() {
        return host.frame();
    }

    /** Any thread: whether a modal dialog is open, so the scene's input is blocked. */
    public boolean blocked() {
        return blocked;
    }

    /** Starts catching the dialogs, as the scene asks for the window. */
    public void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
    }

    /** Stops watching, and puts the dialogs it kept on top back as they were. */
    public void uninstall() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        modal.clear();
        raised.clear();
        blocked = false;
        hadFocus = false;
        onTop.forEach(Dialog::setAlwaysOnTop);
        onTop.clear();
    }

    /** GPU thread, from {@link GpuHost.Scene#started}: the native window the dialogs are owned by from now on. */
    public void started(long windowHandle) {
        nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
    }

    /** GPU thread, as the scene's listener is disposed: returns every adopted dialog to its Swing owner. */
    public void released() {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
    }

    /** The native window's owner for dialogs, once the scene has started; null where the platform has none. */
    @Nullable GpuNativeDialogOwner nativeOwner() {
        return nativeOwner;
    }

    /** Once the native window shows: catches the dialogs that opened before it. */
    public void presented() {
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
                    // only blocks the scene's input, and a click on the window brings it to the front
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
     * A dialog of the scene's hidden frame, or a modal one without an owner of its own, which Swing gives its hidden
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

    /** Brings the newest open modal dialog to the front when the window is clicked or asked to close. */
    public void frontModal() {
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
        GpuNativeDialogOwner owner = nativeOwner;
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
        GpuNativeDialogOwner owner = nativeOwner;
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
