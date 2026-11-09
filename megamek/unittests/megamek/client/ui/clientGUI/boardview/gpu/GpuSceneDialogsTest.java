/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one path for a scene's Swing dialogs ({@link GpuSceneDialogs}), as the native lobby uses it: which dialogs it
 * takes, centring them over the window, keeping them in front, blocking the scene's input while a modal one is open and
 * handing the focus back. The window isn't needed: the dialogs get the events the toolkit would send as they show and
 * close, and without a native owner they take the floating path.
 */
class GpuSceneDialogsTest {
    private static final Rectangle WINDOW = new Rectangle(100, 200, 800, 600);

    private final List<Boolean> blocks = new ArrayList<>();
    private final List<Dialog> made = new ArrayList<>();
    private JFrame frame;
    private JFrame other;
    private int focused;
    private boolean closing;
    private GpuSceneDialogs dialogs;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing dialogs need a display");
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("client");
            other = new JFrame("other");
            dialogs = new GpuSceneDialogs(new GpuSceneDialogs.Host() {
                @Override
                public JFrame frame() {
                    return frame;
                }

                @Override
                public Rectangle bounds() {
                    return WINDOW;
                }

                @Override
                public void inputBlocked(boolean blocked) {
                    blocks.add(blocked);
                }

                @Override
                public void focus() {
                    focused++;
                }

                @Override
                public boolean closing() {
                    return closing;
                }
            });
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            made.forEach(Dialog::dispose);
            if (frame != null) {
                frame.dispose();
                other.dispose();
            }
        });
    }

    @Test
    void theSceneOpensItsDialogsOnItsFrame() throws Exception {
        onEdt(() -> assertSame(frame, dialogs.parent()));
    }

    @Test
    void withoutAStartedWindowThereIsNoNativeOwnerToRelease() throws Exception {
        onEdt(() -> {
            assertNull(dialogs.nativeOwner());
            dialogs.released();
        });
    }

    @Test
    void aModalDialogOfTheFrameIsCentredKeptInFrontAndBlocksUntilItCloses() throws Exception {
        onEdt(() -> {
            JDialog dialog = dialog(frame, true);
            shown(dialog);
            assertEquals(GpuSceneDialogs.centred(WINDOW, new Dimension(200, 100)), dialog.getLocation());
            assertEquals(new Point(400, 450), dialog.getLocation());
            assertTrue(dialog.isAlwaysOnTop());
            assertTrue(dialogs.blocked());
            assertEquals(List.of(true), blocks);

            dialogs.event(new WindowEvent(dialog, WindowEvent.WINDOW_ACTIVATED));
            dialogs.event(new ComponentEvent(dialog, ComponentEvent.COMPONENT_HIDDEN));
            assertFalse(dialogs.blocked());
            assertEquals(List.of(true, false), blocks);
            assertEquals(1, focused, "the window takes the focus back");

            dialogs.event(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSED));
            assertFalse(dialog.isAlwaysOnTop(), "closed, the dialog is as it was");
        });
    }

    @Test
    void aDialogThatTakesTheFocusBeforeItShowsStillGivesItBack() throws Exception {
        onEdt(() -> {
            JDialog dialog = dialog(frame, true);
            dialogs.event(new WindowEvent(dialog, WindowEvent.WINDOW_ACTIVATED));
            shown(dialog);
            dialogs.event(new ComponentEvent(dialog, ComponentEvent.COMPONENT_HIDDEN));
            assertEquals(1, focused);
        });
    }

    @Test
    void aNonModalDialogIsKeptInFrontWithoutBlocking() throws Exception {
        onEdt(() -> {
            JDialog dialog = dialog(frame, false);
            shown(dialog);
            assertTrue(dialog.isAlwaysOnTop());
            assertEquals(new Point(400, 450), dialog.getLocation());
            assertFalse(dialogs.blocked());
            assertTrue(blocks.isEmpty());
        });
    }

    @Test
    void aDialogOfALobbyDialogStaysWhereItsOwnerPutsIt() throws Exception {
        onEdt(() -> {
            JDialog parent = dialog(frame, true);
            shown(parent);
            JDialog child = dialog(parent, true);
            child.setLocation(7, 9);
            shown(child);
            assertTrue(dialogs.belongs(child));
            assertEquals(new Point(7, 9), child.getLocation());

            dialogs.event(new WindowEvent(child, WindowEvent.WINDOW_ACTIVATED));
            dialogs.event(new ComponentEvent(child, ComponentEvent.COMPONENT_HIDDEN));
            assertTrue(dialogs.blocked(), "the first dialog still blocks");
            dialogs.event(new ComponentEvent(parent, ComponentEvent.COMPONENT_HIDDEN));
            assertFalse(dialogs.blocked());
            assertEquals(List.of(true, false), blocks);
            assertEquals(1, focused);
        });
    }

    @Test
    void aQuestionWithoutAParentIsTakenAsMekHqAsksIt() throws Exception {
        onEdt(() -> {
            JDialog question = new JOptionPane("Load the transports?", JOptionPane.QUESTION_MESSAGE,
                  JOptionPane.YES_NO_OPTION).createDialog(null, "MekHQ");
            made.add(question);
            assertTrue(dialogs.belongs(question));
            shown(question);
            assertTrue(dialogs.blocked());
            assertTrue(question.isAlwaysOnTop());
        });
    }

    @Test
    void otherWindowsDialogsAreLeftAlone() throws Exception {
        onEdt(() -> {
            JDialog foreign = dialog(other, true);
            JDialog floating = dialog(null, false);
            assertFalse(dialogs.belongs(foreign));
            assertFalse(dialogs.belongs(floating), "a dialog without an owner that isn't modal");
            shown(foreign);
            shown(floating);
            assertFalse(foreign.isAlwaysOnTop());
            assertEquals(new Point(0, 0), foreign.getLocation());
            assertTrue(blocks.isEmpty());
        });
    }

    @Test
    void theFocusStaysWhereItIsWhenNoDialogHadIt() throws Exception {
        onEdt(() -> {
            JDialog dialog = dialog(frame, true);
            shown(dialog);
            dialogs.event(new ComponentEvent(dialog, ComponentEvent.COMPONENT_HIDDEN));
            assertEquals(0, focused);
        });
    }

    @Test
    void aClosingLobbyLeavesNewDialogsAndTheFocusAlone() throws Exception {
        onEdt(() -> {
            JDialog open = dialog(frame, true);
            shown(open);
            dialogs.event(new WindowEvent(open, WindowEvent.WINDOW_ACTIVATED));
            closing = true;
            JDialog late = dialog(frame, true);
            shown(late);
            assertFalse(late.isAlwaysOnTop());
            dialogs.event(new ComponentEvent(open, ComponentEvent.COMPONENT_HIDDEN));
            assertEquals(0, focused);
            assertFalse(dialogs.blocked());
        });
    }

    @Test
    void uninstallingPutsTheDialogsBack() throws Exception {
        onEdt(() -> {
            JDialog dialog = dialog(frame, true);
            shown(dialog);
            dialogs.uninstall();
            assertFalse(dialog.isAlwaysOnTop());
            assertFalse(dialogs.blocked());
        });
    }

    private JDialog dialog(java.awt.Window owner, boolean modal) {
        JDialog dialog = new JDialog(owner, modal ? Dialog.ModalityType.APPLICATION_MODAL
              : Dialog.ModalityType.MODELESS);
        dialog.setSize(200, 100);
        made.add(dialog);
        return dialog;
    }

    private void shown(Dialog dialog) {
        dialogs.event(new ComponentEvent(dialog, ComponentEvent.COMPONENT_SHOWN));
    }

    private static void onEdt(Runnable check) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                check.run();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }
}
