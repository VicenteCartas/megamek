/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;

import megamek.client.bot.BotClient;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.phaseDisplay.FlightPathNotice;
import megamek.client.ui.dialogs.phaseDisplay.LandingConfirmation;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The client's MESSAGE chokepoints routed through the native modal bridge: each returns what its Swing dialog returns
 * for the same answer, and Swing stays in use while no native window draws dialogs. The test thread plays the GL
 * thread; no GL is used. Its helpers are the bridge tests' shared ones: the presented window, the native requests
 * awaited and answered, and the Swing dialog's components and buttons.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuDialogRoutingTest {

    @Test
    void confirmAndMessageReturnWhatJOptionPaneReturnsForTheSameAnswer() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                GpuDialogFixture.Asked<Integer> yes = GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false);
                assertEquals(DialogKind.MESSAGE, yes.request().kind());
                assertEquals("Confirm", yes.request().title());
                assertEquals("Flee?", yes.request().message());
                assertEquals(2, yes.request().buttons().size());
                assertEquals(0, yes.request().defaultButton());
                assertEquals(-1, yes.request().cancelButton(), "Esc closes it like the JOptionPane's close box");
                assertEquals("", yes.request().checkbox());
                assertEquals(JOptionPane.YES_OPTION, yes.result());
                assertEquals(JOptionPane.NO_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 1, false).result());
                assertEquals(JOptionPane.CLOSED_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), -1, false).result());

                GpuDialogFixture.Asked<Integer> cancel = GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Save first?", "Quit",
                      JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE), 2, false);
                assertEquals(3, cancel.request().buttons().size());
                assertEquals(JOptionPane.CANCEL_OPTION, cancel.result());

                // OK/Cancel is the one standard set whose second button is not index 1 in JOptionPane's answers.
                assertEquals(JOptionPane.OK_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Land?", "Landing",
                      JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).result());
                assertEquals(JOptionPane.CANCEL_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Land?", "Landing",
                      JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE), 1, false).result());

                GpuDialogFixture.Asked<Boolean> shown = GpuDialogFixture.ask(fixture.source, () -> {
                    gui.message("<html>The Mek can no longer hold on<br>and will fall &amp; take damage</html>",
                          "Climbing", JOptionPane.WARNING_MESSAGE);
                    return true;
                }, 0, false);
                assertEquals("The Mek can no longer hold on\nand will fall & take damage", shown.request().message(),
                      "Swing's HTML text is shown as plain text");
                assertEquals(1, shown.request().buttons().size());
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void aPromptAskedWhileTheWindowStartsWaitsForItAndIsAskedNatively() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardWindow window = GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                GpuDialogFixture.set(window, "presented", false);
                Thread presenter = new Thread(() -> {
                    try {
                        Thread.sleep(300);
                        GpuDialogFixture.set(window, "presented", true);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                presenter.start();
                // A loaded save's first phase asks before its board is presented: no Swing dialog meanwhile
                assertEquals(JOptionPane.YES_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Record?", "GIF",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).result());
                presenter.join();
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void aDeadClientsPromptIsAnsweredClosedWithoutAskingNativelyOrInSwing() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        when(gui.isDead()).thenReturn(true);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                // A Swing dialog would wait for a click here, or fail headless
                assertEquals(JOptionPane.CLOSED_OPTION, GpuTestSupport.onSwing(() -> gui.confirm("Record?", "GIF",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)));
                assertNull(fixture.source.dialog(), "a closed game asks nothing in its window");
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void optionReturnsTheChosenIndexAndLeavesTheCheckboxAsThePlayerSetIt() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                // The doomed-deployment prompt: a text, a "don't ask again" box and three options, Cancel focused.
                JCheckBox dontAsk = GpuTestSupport.onSwing(() -> new JCheckBox("Don't ask again"));
                Object[] message = { "The unit will be destroyed.", dontAsk };
                Object[] options = { "Deploy anyway", "Remove from game", "Cancel" };
                GpuDialogFixture.Asked<Integer> removed = GpuDialogFixture.ask(fixture.source, () -> gui.option(message, "Doomed",
                      JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]), 1, true);
                assertEquals(List.of("Deploy anyway", "Remove from game", "Cancel"), removed.request().buttons());
                assertEquals(2, removed.request().defaultButton());
                assertEquals("The unit will be destroyed.", removed.request().message());
                assertEquals("Don't ask again", removed.request().checkbox());
                assertFalse(removed.request().checkboxInitial());
                assertEquals(1, removed.result());
                assertTrue(GpuTestSupport.onSwing(dontAsk::isSelected));

                assertEquals(JOptionPane.CLOSED_OPTION, GpuDialogFixture.ask(fixture.source, () -> gui.option(message, "Doomed",
                      JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]), -1, false)
                      .result());
                assertFalse(GpuTestSupport.onSwing(dontAsk::isSelected), "Closing keeps the box as the player left it");
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void yesNoAndAlertDialogsKeepTheirSwingResults() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                GpuDialogFixture.swingYesNo("OS yes", "OS no");
                GpuDialogFixture.Asked<Boolean> yes = GpuDialogFixture.ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), 0,
                      false);
                assertEquals("Eject the pilot?", yes.request().message());
                assertEquals(List.of(Messages.getString("Yes"), Messages.getString("No")), yes.request().buttons(),
                      "ConfirmDialog's texts in MegaMek's language, whatever language Swing uses");
                assertEquals(0, yes.request().defaultButton(), "ConfirmDialog focuses Yes, which Enter presses");
                assertTrue(yes.result());
                assertEquals(List.of("OS yes", "OS no"), GpuDialogFixture.ask(fixture.source, () -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE), 0, false).request().buttons(),
                      "A JOptionPane site keeps the texts its Swing dialog shows");
                assertFalse(GpuDialogFixture.ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), 1, false)
                      .result());
                assertFalse(GpuDialogFixture.ask(fixture.source, () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"), -1, false)
                      .result(), "Esc answers No, as ConfirmDialog's close action does");

                GpuDialogFixture.Asked<Boolean> alert = GpuDialogFixture.ask(fixture.source, () -> {
                    gui.doAlertDialog("Error", "Could not save &quot;game.sav&quot;:\n  disk full");
                    return true;
                }, 0, false);
                assertEquals("Could not save \"game.sav\":\n  disk full", alert.request().message());
                assertTrue(alert.request().monospace(), "Alerts show their preformatted text");
                assertEquals(1, alert.request().buttons().size());
            } finally {
                GpuDialogFixture.swingYesNo(null, null);
                GpuDialogFixture.dismiss();
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
        ClientGUI gui = GpuDialogFixture.routingClient();
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

            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                bot.doAlertDialog(title, "disk full");
                DialogRequest alert = GpuDialogFixture.awaitDialog(fixture.source);
                assertEquals(List.of(DialogKind.MESSAGE, title, "disk full", true), List.of(alert.kind(),
                      alert.title(), alert.message(), alert.monospace()));
                fixture.source.answer(alert.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
                assertNull(GpuTestSupport.onSwing(fixture.source::dialog), "The alert is answered");
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void aeroNoticesStoreDontShowAgainAndLandingKeepsOkAndCancel() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        String key = "ShowFlightPathNotice";
        boolean hadKey = preferences.hasProperty(key);
        boolean original = preferences.getBoolean(key);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                preferences.setValue(key, true);
                FlightPathNotice notice = GpuTestSupport.onSwing(() -> new FlightPathNotice(gui));
                GpuDialogFixture.Asked<Boolean> shown = GpuDialogFixture.ask(fixture.source, () -> {
                    notice.show();
                    return true;
                }, 0, true);
                assertEquals(Messages.getString("FlightPathNotice.title"), shown.request().title());
                assertEquals(Messages.getString("SimpleNagNotice.dontShowAgain"), shown.request().checkbox());
                assertFalse(preferences.getBoolean(key), "The ticked box is stored");
                assertNull(GpuTestSupport.onSwing(() -> {
                    notice.show();
                    return fixture.source.dialog();
                }), "A notice the player turned off is not shown again");

                LandingConfirmation landing = GpuTestSupport.onSwing(() -> new LandingConfirmation(gui));
                GpuDialogFixture.Asked<Boolean> ok = GpuDialogFixture.ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, 0, false);
                assertEquals(2, ok.request().buttons().size());
                assertTrue(ok.result());
                assertFalse(GpuDialogFixture.ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, 1, false).result());
                assertFalse(GpuDialogFixture.ask(fixture.source, () -> {
                    landing.show();
                    return landing.isOkSelected();
                }, -1, false).result());
            } finally {
                GpuDialogFixture.dismiss();
                preferences.setValue(key, !hadKey || original);
            }
        }
    }

    @Test
    void swingDialogsRemainWhileNoNativeWindowDrawsThem() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardWindow window = GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                GpuDialogFixture.set(window, "presented", false);
                GpuDialogFixture.set(window, "closing", true);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), GpuTestSupport.onSwing(() -> swingAnswers(gui, fixture.source)),
                      "The native window is closing before it was presented");
                GpuDialogFixture.set(null, "active", null);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), GpuTestSupport.onSwing(() -> swingAnswers(gui, fixture.source)),
                      "No native window for this client");
                GpuDialogFixture.present(gui, fixture.view, fixture.source);
                assertEquals(List.of(JOptionPane.NO_OPTION, 2), GpuTestSupport.onSwing(() -> {
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
                GpuDialogFixture.dismiss();
            }
        }
    }

    @Test
    void toastsTheClientShowsReachTheNativeWindowAfterTheToastSetting() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean enabled = preferences.getToastEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                preferences.setToastEnabled(true);
                GpuTestSupport.onSwing(() -> {
                    gui.addToast(ToastLevel.WARNING, "Atlas <b>cannot</b> reach that hex");
                    return null;
                });
                List<GpuToasts.Toast> toasts = GpuTestSupport.onSwing(() -> fixture.source.toasts().capture().toasts());
                assertEquals(1, toasts.size(), "The native stack does not need the Swing toast overlay");
                assertEquals(ToastLevel.WARNING, toasts.getFirst().level());
                assertEquals("Atlas cannot reach that hex", toasts.getFirst().text());

                preferences.setToastEnabled(false);
                GpuTestSupport.onSwing(() -> {
                    gui.addToast(ToastLevel.ERROR, "Switched off");
                    return null;
                });
                assertEquals(1, GpuTestSupport.onSwing(() -> fixture.source.toasts().capture().toasts()).size(),
                      "The player's toast setting applies to the native stack too");
            } finally {
                preferences.setToastEnabled(enabled);
                GpuDialogFixture.dismiss();
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
}
