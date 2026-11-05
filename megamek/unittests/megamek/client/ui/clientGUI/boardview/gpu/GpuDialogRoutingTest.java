/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import megamek.client.bot.BotClient;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.phaseDisplay.FlightPathNotice;
import megamek.client.ui.dialogs.phaseDisplay.LandingConfirmation;
import megamek.common.Configuration;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

/**
 * The client's MESSAGE chokepoints routed through the native modal bridge: each returns what its Swing dialog returns
 * for the same answer, and Swing stays in use while no native window draws dialogs. The test thread plays the GL
 * thread; no GL is used. Its helpers are the bridge tests' shared ones: the presented window, the native requests
 * awaited and answered, and the Swing dialog's components and buttons.
 */
@Timeout(120)
class GpuDialogRoutingTest {
    private static final long WAIT_SECONDS = 20;
    /** The client methods under test run their real code; every other client call answers the Mockito default. */
    private static final Set<String> ROUTED = Set.of("askNative", "askYesNo", "confirm", "option", "message",
          "doYesNoDialog", "doAlertDialog", "addToast", "askRows", "askText", "input", "doChoiceDialog", "askForm",
          "askEntry", "askSaveFirst");
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixture needs the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @Test
    void confirmAndMessageReturnWhatJOptionPaneReturnsForTheSameAnswer() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                Asked<Integer> yes = ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false);
                assertEquals(DialogKind.MESSAGE, yes.request().kind());
                assertEquals("Confirm", yes.request().title());
                assertEquals("Flee?", yes.request().message());
                assertEquals(2, yes.request().buttons().size());
                assertEquals(0, yes.request().defaultButton());
                assertEquals(-1, yes.request().cancelButton(), "Esc closes it like the JOptionPane's close box");
                assertEquals("", yes.request().checkbox());
                assertEquals(JOptionPane.YES_OPTION, yes.result());
                assertEquals(JOptionPane.NO_OPTION, ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 1, false).result());
                assertEquals(JOptionPane.CLOSED_OPTION, ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), -1, false).result());

                Asked<Integer> cancel = ask(fixture.source, () -> gui.confirm("Save first?", "Quit",
                      JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE), 2, false);
                assertEquals(3, cancel.request().buttons().size());
                assertEquals(JOptionPane.CANCEL_OPTION, cancel.result());

                // OK/Cancel is the one standard set whose second button is not index 1 in JOptionPane's answers.
                assertEquals(JOptionPane.OK_OPTION, ask(fixture.source, () -> gui.confirm("Land?", "Landing",
                      JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).result());
                assertEquals(JOptionPane.CANCEL_OPTION, ask(fixture.source, () -> gui.confirm("Land?", "Landing",
                      JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE), 1, false).result());

                Asked<Boolean> shown = ask(fixture.source, () -> {
                    gui.message("<html>The Mek can no longer hold on<br>and will fall &amp; take damage</html>",
                          "Climbing", JOptionPane.WARNING_MESSAGE);
                    return true;
                }, 0, false);
                assertEquals("The Mek can no longer hold on\nand will fall & take damage", shown.request().message(),
                      "Swing's HTML text is shown as plain text");
                assertEquals(1, shown.request().buttons().size());
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void aPromptAskedWhileTheWindowStartsWaitsForItAndIsAskedNatively() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardWindow window = present(gui, fixture.view, fixture.source);
            try {
                set(window, "presented", false);
                Thread presenter = new Thread(() -> {
                    try {
                        Thread.sleep(300);
                        set(window, "presented", true);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                presenter.start();
                // A loaded save's first phase asks before its board is presented: no Swing dialog meanwhile
                assertEquals(JOptionPane.YES_OPTION, ask(fixture.source, () -> gui.confirm("Record?", "GIF",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).result());
                presenter.join();
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void aDeadClientsPromptIsAnsweredClosedWithoutAskingNativelyOrInSwing() throws Exception {
        ClientGUI gui = routingClient();
        when(gui.isDead()).thenReturn(true);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                // A Swing dialog would wait for a click here, or fail headless
                assertEquals(JOptionPane.CLOSED_OPTION, onSwing(() -> gui.confirm("Record?", "GIF",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)));
                assertNull(fixture.source.dialog(), "a closed game asks nothing in its window");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void optionReturnsTheChosenIndexAndLeavesTheCheckboxAsThePlayerSetIt() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                // The doomed-deployment prompt: a text, a "don't ask again" box and three options, Cancel focused.
                JCheckBox dontAsk = onSwing(() -> new JCheckBox("Don't ask again"));
                Object[] message = { "The unit will be destroyed.", dontAsk };
                Object[] options = { "Deploy anyway", "Remove from game", "Cancel" };
                Asked<Integer> removed = ask(fixture.source, () -> gui.option(message, "Doomed",
                      JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]), 1, true);
                assertEquals(List.of("Deploy anyway", "Remove from game", "Cancel"), removed.request().buttons());
                assertEquals(2, removed.request().defaultButton());
                assertEquals("The unit will be destroyed.", removed.request().message());
                assertEquals("Don't ask again", removed.request().checkbox());
                assertFalse(removed.request().checkboxInitial());
                assertEquals(1, removed.result());
                assertTrue(onSwing(dontAsk::isSelected));

                assertEquals(JOptionPane.CLOSED_OPTION, ask(fixture.source, () -> gui.option(message, "Doomed",
                      JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]), -1, false)
                      .result());
                assertFalse(onSwing(dontAsk::isSelected), "Closing keeps the box as the player left it");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void yesNoAndAlertDialogsKeepTheirSwingResults() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                swingYesNo("OS yes", "OS no");
                Asked<Boolean> yes = ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), 0,
                      false);
                assertEquals("Eject the pilot?", yes.request().message());
                assertEquals(List.of(Messages.getString("Yes"), Messages.getString("No")), yes.request().buttons(),
                      "ConfirmDialog's texts in MegaMek's language, whatever language Swing uses");
                assertEquals(0, yes.request().defaultButton(), "ConfirmDialog focuses Yes, which Enter presses");
                assertTrue(yes.result());
                assertEquals(List.of("OS yes", "OS no"), ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).request().buttons(),
                      "A JOptionPane site keeps the texts its Swing dialog shows");
                assertFalse(ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), 1, false)
                      .result());
                assertFalse(ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), -1, false)
                      .result(), "Esc answers No, as ConfirmDialog's close action does");

                Asked<Boolean> alert = ask(fixture.source, () -> {
                    gui.doAlertDialog("Error", "Could not save &quot;game.sav&quot;:\n  disk full");
                    return true;
                }, 0, false);
                assertEquals("Could not save \"game.sav\":\n  disk full", alert.request().message());
                assertTrue(alert.request().monospace(), "Alerts show their preformatted text");
                assertEquals(1, alert.request().buttons().size());
            } finally {
                swingYesNo(null, null);
                dismiss();
            }
        }
    }

    /**
     * R15: a local bot's alert (its units could not be saved) has no window of its own. While the creating client's
     * battle window is presented it is that client's native alert, asked from the bot's thread; otherwise the bot
     * keeps its own Swing box.
     */
    @Test
    void aLocalBotsAlertIsTheCreatingClientsNativeAlertWhileItsWindowIsPresented() throws Exception {
        ClientGUI gui = routingClient();
        BotClient bot = mock(BotClient.class, CALLS_REAL_METHODS);
        bot.setClientGUI(gui);
        String title = Messages.getString("ClientGUI.errorSavingFile");
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                bot.doAlertDialog(title, "disk full");
                swing.verify(() -> JOptionPane.showMessageDialog(isNull(), any(JScrollPane.class), eq(title),
                      eq(JOptionPane.ERROR_MESSAGE)));
            }
            assertNull(fixture.source.dialog(), "No native window: the bot's own box");

            present(gui, fixture.view, fixture.source);
            try {
                bot.doAlertDialog(title, "disk full");
                DialogRequest alert = awaitDialog(fixture.source);
                assertEquals(List.of(DialogKind.MESSAGE, title, "disk full", true), List.of(alert.kind(),
                      alert.title(), alert.message(), alert.monospace()));
                fixture.source.answer(alert.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
                assertNull(onSwing(fixture.source::dialog), "The alert is answered");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void aeroNoticesStoreDontShowAgainAndLandingKeepsOkAndCancel() throws Exception {
        ClientGUI gui = routingClient();
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        String key = "ShowFlightPathNotice";
        boolean hadKey = preferences.hasProperty(key);
        boolean original = preferences.getBoolean(key);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                preferences.setValue(key, true);
                FlightPathNotice notice = onSwing(() -> new FlightPathNotice(gui));
                Asked<Boolean> shown = ask(fixture.source, () -> {
                    notice.show();
                    return true;
                }, 0, true);
                assertEquals(Messages.getString("FlightPathNotice.title"), shown.request().title());
                assertEquals(Messages.getString("SimpleNagNotice.dontShowAgain"), shown.request().checkbox());
                assertFalse(preferences.getBoolean(key), "The ticked box is stored");
                assertNull(onSwing(() -> {
                    notice.show();
                    return fixture.source.dialog();
                }), "A notice the player turned off is not shown again");

                LandingConfirmation landing = onSwing(() -> new LandingConfirmation(gui));
                Asked<Boolean> ok = ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, 0, false);
                assertEquals(2, ok.request().buttons().size());
                assertTrue(ok.result());
                assertFalse(ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, 1, false).result());
                assertFalse(ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, -1, false).result());
            } finally {
                dismiss();
                preferences.setValue(key, !hadKey || original);
            }
        }
    }

    @Test
    void swingDialogsRemainWhileNoNativeWindowDrawsThem() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardWindow window = present(gui, fixture.view, fixture.source);
            try {
                set(window, "presented", false);
                set(window, "closing", true);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), onSwing(() -> swingAnswers(gui, fixture.source)),
                      "The native window is closing before it was presented");
                set(null, "active", null);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), onSwing(() -> swingAnswers(gui, fixture.source)),
                      "No native window for this client");
                present(gui, fixture.view, fixture.source);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), onSwing(() -> {
                    try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                        Object[] message = { "Assign AMS", new JLabel("a list the native dialog cannot show") };
                        swing.when(() -> JOptionPane.showConfirmDialog(any(), any(), any(), anyInt(), anyInt()))
                              .thenReturn(JOptionPane.NO_OPTION);
                        swing.when(() -> JOptionPane.showOptionDialog(any(), any(), any(), anyInt(), anyInt(), any(),
                              any(), any())).thenReturn(2);
                        int confirmed = gui.confirm(message, "AMS", JOptionPane.OK_CANCEL_OPTION,
                              JOptionPane.QUESTION_MESSAGE);
                        Object[] options = { "Forward", new JLabel("Backward") };
                        int chosen = gui.option("Domino", "Domino", JOptionPane.DEFAULT_OPTION,
                              JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                        assertNull(fixture.source.dialog());
                        return List.of(confirmed, chosen);
                    }
                }), "A message part the native dialog cannot show keeps the Swing dialog");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void toastsTheClientShowsReachTheNativeWindowAfterTheToastSetting() throws Exception {
        ClientGUI gui = routingClient();
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean enabled = preferences.getToastEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                preferences.setToastEnabled(true);
                onSwing(() -> {
                    gui.addToast(ToastLevel.WARNING, "Atlas <b>cannot</b> reach that hex");
                    return null;
                });
                List<GpuToasts.Toast> toasts = onSwing(() -> fixture.source.toasts().capture().toasts());
                assertEquals(1, toasts.size(), "The native stack does not need the Swing toast overlay");
                assertEquals(ToastLevel.WARNING, toasts.getFirst().level());
                assertEquals("Atlas cannot reach that hex", toasts.getFirst().text());

                preferences.setToastEnabled(false);
                onSwing(() -> {
                    gui.addToast(ToastLevel.ERROR, "Switched off");
                    return null;
                });
                assertEquals(1, onSwing(() -> fixture.source.toasts().capture().toasts()).size(),
                      "The player's toast setting applies to the native stack too");
            } finally {
                preferences.setToastEnabled(enabled);
                dismiss();
            }
        }
    }

    /** EDT: a confirm and an option answered by a stubbed Swing JOptionPane; no native dialog may appear. */
    private static List<Integer> swingAnswers(ClientGUI gui, GpuBoardSource source) {
        try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
            swing.when(() -> JOptionPane.showConfirmDialog(null, "Flee?", "Confirm", JOptionPane.YES_NO_OPTION,
                  JOptionPane.QUESTION_MESSAGE)).thenReturn(JOptionPane.NO_OPTION);
            Object[] options = { "Forward", "Backward", "No action" };
            swing.when(() -> JOptionPane.showOptionDialog(null, "Domino", "Domino", JOptionPane.YES_NO_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE, null, options, options[0])).thenReturn(2);
            int confirmed = gui.confirm("Flee?", "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            int chosen = gui.option("Domino", "Domino", JOptionPane.YES_NO_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            assertNull(source.dialog());
            return List.of(confirmed, chosen);
        }
    }

    /** One routed question: the request the native window was asked to show and what the chokepoint returned. */
    record Asked<T>(DialogRequest request, T result) { }

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
     * {@link #awaitDialog(GpuBoardSource)} for the first request other than {@code previous} (any for null): the next
     * question of a chokepoint, or a form shown again, which is a new request. Fails at once when the chokepoint, if
     * given, ends without asking.
     */
    static DialogRequest next(GpuBoardSource source, DialogRequest previous, FutureTask<?> chokepoint)
          throws Exception {
        return awaitDialog(source, chokepoint, previous, null);
    }

    /**
     * {@link #awaitDialog(GpuBoardSource)} that fails at once when the chokepoint, if given, ends without asking: with
     * its own exception when it threw one. A non-null {@code previous} waits for another request, a non-null
     * {@code message} for the dialog that shows it.
     */
    private static DialogRequest awaitDialog(GpuBoardSource source, FutureTask<?> chokepoint, DialogRequest previous,
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

    /** A mocked client whose prompt chokepoints run their real code. Created on the test thread. */
    static ClientGUI routingClient() {
        return mock(ClientGUI.class, invocation -> ROUTED.contains(invocation.getMethod().getName())
              ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
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
        GpuBoardWindow window = onSwing(() -> constructor.newInstance(gui, view, panel));
        set(window, "source", source);
        set(window, "presented", true);
        set(null, "active", window);
        return window;
    }

    /** Unregisters the window of {@link #present}. */
    static void dismiss() throws ReflectiveOperationException {
        set(null, "active", null);
    }

    /**
     * Sets Swing's own Yes/No button texts apart from MegaMek's, as a system in another language does; null, null
     * removes the override.
     */
    static void swingYesNo(String yes, String no) throws Exception {
        onSwing(() -> {
            UIManager.put("OptionPane.yesButtonText", yes);
            UIManager.put("OptionPane.noButtonText", no);
            return null;
        });
    }

    static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
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

    /** The first button inside {@code root} with this text. */
    static AbstractButton button(Container root, String text) {
        return components(root, AbstractButton.class).stream().filter(button -> text.equals(button.getText()))
              .findFirst().orElseThrow(() -> new AssertionError("No button " + text));
    }

    /** EDT: clicks the button inside {@code root} with this text, as the player does. */
    static void press(Container root, String text) {
        button(root, text).doClick();
    }

    /** Sets a field of {@code window}, or a static field of GpuBoardWindow for null. */
    static void set(GpuBoardWindow window, String name, Object value) throws ReflectiveOperationException {
        Field field = GpuBoardWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(window, value);
    }
}
