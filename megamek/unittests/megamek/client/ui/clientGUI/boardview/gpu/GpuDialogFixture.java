/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;

/** A client whose prompts route to the native window, and the helpers that answer them. */
final class GpuDialogFixture {
    private GpuDialogFixture() { }

    /**
     * Runs a chokepoint on the EDT, answers its native dialog as the render thread would (button -1 is Esc), and
     * returns what the chokepoint returned.
     */
    static <T> Asked<T> ask(GpuBoardSource source, Callable<T> chokepoint, int button, boolean checked)
          throws Exception {
        return ask(source, chokepoint, new DialogAnswer(button, List.of(), null, checked, List.of()));
    }

    /** {@link #ask(GpuBoardSource, Callable, int, boolean)} with any answer, such as chosen rows or a typed text. */
    static <T> Asked<T> ask(GpuBoardSource source, Callable<T> chokepoint, DialogAnswer answer) throws Exception {
        FutureTask<T> task = new FutureTask<>(chokepoint);
        SwingUtilities.invokeLater(task);
        DialogRequest shown = awaitDialog(source, task, null, null);
        source.answer(shown.id(), answer);
        return new Asked<>(shown, task.get(WAIT_SECONDS, SECONDS));
    }

    /** Polls the published dialog as the GL thread does every frame. */
    static DialogRequest awaitDialog(GpuBoardSource source) throws Exception {
        return awaitDialog(source, null, null, null);
    }

    /** {@link #awaitDialog(GpuBoardSource)} for the dialog of {@code message}, as nested asks show their own. */
    static DialogRequest awaitDialog(GpuBoardSource source, String message) throws Exception {
        return awaitDialog(source, null, null, message);
    }

    /**
     * {@link #awaitDialog(GpuBoardSource)} that fails at once when the chokepoint, if given, ends without asking: with
     * its own exception when it threw one. A non-null {@code previous} waits for another request, a non-null
     * {@code message} for the dialog that shows it.
     */
    static DialogRequest awaitDialog(GpuBoardSource source, FutureTask<?> chokepoint, DialogRequest previous,
          String message) throws Exception {
        long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            DialogRequest shown = source.dialog();
            if (shown != null && (previous == null || shown.id() != previous.id())
                  && (message == null || shown.message().equals(message))) {
                return shown;
            }
            if ((chokepoint != null) && chokepoint.isDone()) {
                return fail("Ended without asking natively, returning " + chokepoint.get());
            }
            Thread.sleep(5);
        }
        return fail(message == null ? "No native dialog was shown" : "Dialog not shown: " + message);
    }

    /** The first button inside {@code root} with this text. */
    static AbstractButton button(Container root, String text) {
        return components(root, AbstractButton.class).stream().filter(button -> text.equals(button.getText()))
              .findFirst().orElseThrow(() -> new AssertionError("No button " + text));
    }

    /** Every component of {@code type} inside {@code root}, depth first in layout order. */
    static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(components(container, type));
            }
        }
        return found;
    }

    /** Unregisters the window of {@link #present}. */
    static void dismiss() throws ReflectiveOperationException {
        set(null, "active", null);
    }

    /**
     * {@link #awaitDialog(GpuBoardSource)} for the first request other than {@code previous} (any for null): the next
     * question of a chokepoint, or a form shown again, which is a new request. Fails at once when the chokepoint, if
     * given, ends without asking.
     */
    static DialogRequest next(GpuBoardSource source, DialogRequest previous, FutureTask<?> chokepoint)
          throws Exception {
        return GpuDialogFixture.awaitDialog(source, chokepoint, previous, null);
    }

    /**
     * Registers a presented native window of {@code gui} over {@code source}, without starting the native thread, which
     * draws dialogs as the native HUD does, and returns it.
     */
    static GpuBoardWindow present(ClientGUI gui, BoardClientState view, GpuBoardSource source) throws Exception {
        Constructor<GpuBoardWindow> constructor = GpuBoardWindow.class.getDeclaredConstructor(ClientGUI.class,
              BoardClientState.class, Supplier.class);
        constructor.setAccessible(true);
        Supplier<JComponent> panel = () -> null;
        GpuBoardWindow window = GpuTestSupport.onSwing(() -> constructor.newInstance(gui, view, panel));
        set(window, "source", source);
        set(window, "presented", true);
        set(null, "active", window);
        return window;
    }

    /**
     * Registers a native window of {@code gui} that is closing before it was presented. It draws no dialogs and the
     * client's prompts show in Swing at once, where those of a window still starting wait for it (GpuBoardWindow.route).
     */
    static void closingWindow(ClientGUI gui, BoardClientState view, GpuBoardSource source) throws Exception {
        GpuBoardWindow window = present(gui, view, source);
        set(window, "presented", false);
        set(window, "closing", true);
    }

    /** EDT: clicks the button inside {@code root} with this text, as the player does. */
    static void press(Container root, String text) {
        GpuDialogFixture.button(root, text).doClick();
    }

    /** A mocked client whose prompt chokepoints run their real code. Created on the test thread. */
    static ClientGUI routingClient() {
        return mock(ClientGUI.class, invocation -> ROUTED.contains(invocation.getMethod().getName())
              ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
    }

    /**
     * Sets Swing's own Yes/No button texts apart from MegaMek's, as a system in another language does; null, null
     * removes the override.
     */
    static void swingYesNo(String yes, String no) throws Exception {
        GpuTestSupport.onSwing(() -> {
            UIManager.put("OptionPane.yesButtonText", yes);
            UIManager.put("OptionPane.noButtonText", no);
            return null;
        });
    }

    /** One routed question: the request the native window was asked to show and what the chokepoint returned. */
    record Asked<T>(DialogRequest request, T result) { }

    static final long WAIT_SECONDS = 20;

    /** Sets a field of {@code window}, or a static field of GpuBoardWindow for null. */
    static void set(GpuBoardWindow window, String name, Object value) throws ReflectiveOperationException {
        Field field = GpuBoardWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(window, value);
    }

    /** The client methods under test run their real code; every other client call answers the Mockito default. */
    static final Set<String> ROUTED = Set.of("askNative", "askYesNo", "confirm", "option", "message",
          "doYesNoDialog", "doAlertDialog", "addToast", "askRows", "askText", "input", "doChoiceDialog", "askForm",
          "askEntry", "askSaveFirst");

    /** The client's frame, carrying the client as its constructor puts it there for the dialogs it owns. */
    static JFrame clientFrame(ClientGUI gui) throws Exception {
        JFrame frame = onSwing(JFrame::new);
        onSwing(() -> {
            frame.getRootPane().putClientProperty(ClientGUI.class, gui);
            return null;
        });
        Field field = AbstractClientGUI.class.getDeclaredField("frame");
        field.setAccessible(true);
        field.set(gui, frame);
        return frame;
    }

    /**
     * EDT: runs a chokepoint while a timer closes its Swing dialog of the given type as soon as it shows. True when the
     * dialog showed and the chokepoint's own check holds afterwards.
     */
    static boolean closedAfterShowing(Class<? extends Window> type, Callable<Boolean> chokepoint)
          throws Exception {
        AtomicBoolean shown = new AtomicBoolean();
        Timer closer = new Timer(50, event -> {
            for (Window window : Window.getWindows()) {
                if (type.isInstance(window) && window.isShowing()) {
                    shown.set(true);
                    window.setVisible(false);
                }
            }
        });
        closer.start();
        try {
            return chokepoint.call() && shown.get();
        } finally {
            closer.stop();
        }
    }

    static List<String> labels(DialogRequest request) {
        return request.rows().stream().map(DialogRow::label).toList();
    }

    /** A CHOICE or MULTI answer: the button (-1 is Esc) and the selected rows. */
    static DialogAnswer pick(int button, Integer... rows) {
        return new DialogAnswer(button, List.of(rows), null, false, List.of());
    }
}
