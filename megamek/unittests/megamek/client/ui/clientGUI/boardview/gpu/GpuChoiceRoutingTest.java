/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.GifRecordingMode;
import megamek.client.ui.clientGUI.IClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.unitDisplay.UnitEquipmentActions;
import megamek.client.ui.dialogs.ChoiceDialog;
import megamek.client.ui.dialogs.SliderDialog;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.client.ui.dialogs.phaseDisplay.EMPMineSettingDialog;
import megamek.client.ui.dialogs.phaseDisplay.EcmSuiteChoiceDialog;
import megamek.client.ui.dialogs.phaseDisplay.EntityChoiceDialog;
import megamek.client.ui.dialogs.phaseDisplay.ManeuverChoiceDialog;
import megamek.client.ui.dialogs.phaseDisplay.TeleMissileSettingDialog;
import megamek.client.ui.dialogs.phaseDisplay.VibrabombSettingDialog;
import megamek.common.ManeuverType;
import megamek.common.board.Board;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.Mounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The client's CHOICE, MULTI and INPUT chokepoints routed through the native modal bridge: each returns what its Swing
 * dialog returns for the same answer, and Swing stays in use while no native window draws dialogs. The test thread
 * plays the GL thread; no GL is used. Tests that build Swing dialogs need a display.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuChoiceRoutingTest {

    @Test
    void inputReturnsTheChosenValueOrTheTypedTextLikeJOptionPane() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                Object[] targets = { "Atlas AS7-D", "Hunchback HBK-4G", "Locust LCT-1V" };
                Callable<Object> tagTarget = () -> gui.input("Choose a TAG target", "TAG target",
                      JOptionPane.QUESTION_MESSAGE, targets, targets[0]);
                Asked<Object> tagged = ask(fixture.source, tagTarget, GpuDialogFixture.pick(0, 2));
                DialogRequest request = tagged.request();
                assertEquals(DialogKind.CHOICE, request.kind());
                assertEquals("TAG target", request.title());
                assertEquals("Choose a TAG target", request.message());
                assertEquals(List.of("Atlas AS7-D", "Hunchback HBK-4G", "Locust LCT-1V"), GpuDialogFixture.labels(request));
                assertEquals(List.of(0), request.initiallySelected(), "The initial value is selected");
                assertEquals(List.of(UIManager.getString("OptionPane.okButtonText"),
                      UIManager.getString("OptionPane.cancelButtonText")), request.buttons());
                assertEquals(0, request.defaultButton());
                assertEquals(1, request.cancelButton());
                assertEquals("Locust LCT-1V", tagged.result());
                assertNull(ask(fixture.source, tagTarget, GpuDialogFixture.pick(1, 2)).result(), "Cancel returns null");

                // Without an initial value, JOptionPane's combo box (fewer than 20 values) starts on the first value
                Object[] apds = { "None", "LRM 20 from Archer ARC-2R (distance 2)" };
                Asked<Object> none = ask(fixture.source, () -> gui.input("Assign the APDS", "APDS",
                      JOptionPane.QUESTION_MESSAGE, apds, null), GpuDialogFixture.pick(0, 0));
                assertEquals(List.of(0), none.request().initiallySelected());
                assertEquals("None", none.result());
                // ... and its list for 20 or more values starts without a selection, so OK alone returns null
                Object[] many = IntStream.range(0, 20).mapToObj(index -> "Target " + index).toArray();
                Asked<Object> unselected = ask(fixture.source, () -> gui.input("Pick one", "Targets",
                      JOptionPane.QUESTION_MESSAGE, many, null), GpuDialogFixture.pick(0));
                assertEquals(List.of(), unselected.request().initiallySelected());
                assertNull(unselected.result());

                Callable<Object> fileName = () -> gui.input("File name", "Save on server",
                      JOptionPane.QUESTION_MESSAGE, null, "savegame.sav.gz");
                Asked<Object> typed = ask(fixture.source, fileName, typed(0, "round3"));
                assertEquals(DialogKind.INPUT, typed.request().kind());
                assertEquals("savegame.sav.gz", typed.request().initialText());
                assertNull(typed.request().min(), "Any text");
                assertNull(typed.request().max());
                assertEquals("round3", typed.result());
                assertNull(ask(fixture.source, fileName, typed(1, "round3")).result(),
                      "Cancel discards the typed text");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void choiceDialogsReturnTheirSwingResultsForNativeAnswers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                // Units dropping from a bay with two usable doors: any units, but at most two
                String[] units = { "Sparrowhawk", "Corsair", "Stuka", "Lucifer" };
                Callable<int[]> drop = () -> {
                    ChoiceDialog dialog = new ChoiceDialog(frame, "Drop units", "Which units drop?", units, false,
                          2);
                    dialog.setVisible(true);
                    return dialog.getChoices();
                };
                Asked<int[]> dropped = ask(fixture.source, drop, GpuDialogFixture.pick(0, 3, 0));
                DialogRequest request = dropped.request();
                assertEquals(DialogKind.MULTI, request.kind());
                assertEquals("Drop units", request.title());
                assertEquals("Which units drop?", request.message());
                assertEquals(List.of(units), GpuDialogFixture.labels(request));
                assertEquals(List.of(), request.initiallySelected());
                assertEquals(2, request.max());
                assertEquals(List.of(Messages.getString("Okay"), Messages.getString("Cancel")), request.buttons());
                assertEquals(1, request.cancelButton());
                assertArrayEquals(new int[] { 0, 3 }, dropped.result());
                assertNull(ask(fixture.source, drop, GpuDialogFixture.pick(0, 0, 2, 3)).result(),
                      "More units than the dialog allows are refused, not trimmed to the first ones");
                assertNull(ask(fixture.source, drop, GpuDialogFixture.pick(1, 0)).result(), "Cancel chooses nothing");

                // One of a slot's two mounts: a single choice that starts on the first
                Asked<int[]> mount = ask(fixture.source, () -> {
                    ChoiceDialog dialog = new ChoiceDialog(frame, "Select", "Which one?",
                          new String[] { "Medium Laser", "Small Laser" }, true);
                    dialog.setVisible(true);
                    return dialog.getAnswer() ? dialog.getChoices() : null;
                }, GpuDialogFixture.pick(0, 1));
                assertEquals(DialogKind.CHOICE, mount.request().kind());
                assertEquals(List.of(0), mount.request().initiallySelected());
                assertArrayEquals(new int[] { 1 }, mount.result());

                assertNull(ask(fixture.source, () -> gui.doChoiceDialog("Unload", "Unload stranded units?",
                      "Harasser", "Savannah Master"), GpuDialogFixture.pick(0)).result(), "OK without a tick chooses nothing");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void anEcmSuiteConflictIsAskedNativelyWithoutBuildingItsSwingDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "The client's frame is a Swing window");
        ClientGUI gui = routingClient();
        Client client = mock(Client.class);
        when(gui.getClient()).thenReturn(client);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            when(gui.getFrame()).thenReturn(frame);
            when(client.getGame()).thenReturn(fixture.game);
            Entity atlas = fixture.entity;
            List<MiscMounted> suites = onSwing(() -> {
                MiscMounted first = (MiscMounted) atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"),
                      Mek.LOC_LEFT_ARM);
                MiscMounted second = (MiscMounted) atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"),
                      Mek.LOC_RIGHT_ARM);
                // ECM modes are shared type state that each game sets from its options
                atlas.setGameOptions();
                second.setMode(Mounted.MODE_OFF);
                return List.of(first, second);
            });
            int firstNum = atlas.getEquipmentNum(suites.get(0));
            int secondNum = atlas.getEquipmentNum(suites.get(1));
            long dialogs = ecmDialogs();
            present(gui, fixture.view, fixture.source);
            try {
                // Switching the second suite back on would put two suites into use: the player keeps one of them
                Asked<Boolean> kept = ask(fixture.source,
                      () -> UnitEquipmentActions.changeMode(gui, atlas, suites.get(1), 0), GpuDialogFixture.pick(0, 1));
                DialogRequest request = kept.request();
                assertEquals(DialogKind.CHOICE, request.kind());
                assertEquals(Messages.getString("EcmSuiteChoiceDialog.title"), request.title());
                assertEquals("Atlas AS7-D may only use one ECM suite at a time (TM p.213).\nWhich one do you want to"
                      + " leave on? The others will be switched off.", request.message());
                assertEquals(List.of("ECM Suite (Guardian) #1 (Left Arm)\nECM",
                      "ECM Suite (Guardian) #2 (Right Arm)\nECM"), GpuDialogFixture.labels(request),
                      "The suite in use and the one switched, each with the mode it would have");
                assertEquals(List.of(), request.initiallySelected());
                assertEquals(List.of(Messages.getString("Ok.text"), Messages.getString("Cancel.text")),
                      request.buttons());
                assertEquals(1, request.cancelButton());
                assertTrue(kept.result(), "The switch goes ahead");
                verify(client).sendModeChange(atlas.getId(), firstNum, 1);
                verify(client).sendModeChange(atlas.getId(), secondNum, 0);

                // Back on the first suite: keeping the second, or cancelling, abandons the switch
                assertFalse(ask(fixture.source, () -> UnitEquipmentActions.changeMode(gui, atlas, suites.get(0), 0),
                      GpuDialogFixture.pick(0, 1)).result());
                assertFalse(ask(fixture.source, () -> UnitEquipmentActions.changeMode(gui, atlas, suites.get(0), 0),
                      GpuDialogFixture.pick(1)).result());
                verify(client, never()).sendModeChange(atlas.getId(), firstNum, 0);
                assertEquals(dialogs, ecmDialogs(), "No Swing dialog was built for the question");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void maneuverChoiceOffersOnlyThePerformableManeuvers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        Board board = mock(Board.class);
        MovePath path = mock(MovePath.class);
        when(path.getStepVector()).thenReturn(new Vector<>());
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                // Velocity 3 at altitude 5 over open sky: too slow for a loop, and not a VSTOL for a VIFF
                Callable<Integer> maneuver = () -> {
                    ManeuverChoiceDialog dialog = new ManeuverChoiceDialog(frame, "Maneuver");
                    dialog.checkPerformability(3, 5, 0, false, 0, board, path);
                    dialog.setVisible(true);
                    return dialog.getChoice();
                };
                Asked<Integer> immelman = ask(fixture.source, maneuver, GpuDialogFixture.pick(0, ManeuverType.MAN_IMMELMAN));
                DialogRequest request = immelman.request();
                assertEquals(DialogKind.CHOICE, request.kind());
                assertEquals("Maneuver", request.title());
                assertEquals(List.of("None", "Loop", "Immelman", "Split S", "Hammerhead", "Half Roll", "Barrel Roll",
                      "Side Slip (Left)", "Side Slip (Right)", "VIFF"), GpuDialogFixture.labels(request));
                assertEquals(List.of(true, false, true, true, true, true, true, true, true, false),
                      request.rows().stream().map(DialogRow::enabled).toList());
                String loop = request.rows().get(ManeuverType.MAN_LOOP).detail();
                assertTrue(loop.startsWith("Loop") && !loop.contains("<"), "The tooltip is the plain detail: " + loop);
                assertEquals(List.of(ManeuverType.MAN_NONE), request.initiallySelected());
                assertEquals(ManeuverType.MAN_IMMELMAN, immelman.result());

                assertEquals(ManeuverType.MAN_NONE, ask(fixture.source, maneuver, GpuDialogFixture.pick(0, ManeuverType.MAN_LOOP))
                      .result(), "A maneuver the unit cannot perform is never chosen");
                assertEquals(-1, ask(fixture.source, maneuver, GpuDialogFixture.pick(1, ManeuverType.MAN_IMMELMAN)).result(),
                      "Cancel chooses nothing");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void unitChoiceRowsShowPlainSummariesAndReturnTheChosenUnit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        Entity archer = new MekFileParser(new File("testresources/megamek/common/units/Archer ARC-2R.mtf"))
              .getEntity();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            onSwing(() -> {
                archer.setId(2);
                archer.setOwner(fixture.player);
                fixture.game.addEntity(archer, false);
                return null;
            });
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Callable<Entity> load = () -> EntityChoiceDialog.showSingleChoiceDialog(frame,
                      "DeploymentDisplay.loadUnitDialog.title", "<html>Which unit do you want to load?</html>",
                      List.of(fixture.entity, archer));
                Asked<Entity> loaded = ask(fixture.source, load, GpuDialogFixture.pick(0, 1));
                DialogRequest request = loaded.request();
                assertEquals(DialogKind.CHOICE, request.kind());
                assertEquals("Load Unit", request.title());
                assertEquals("Which unit do you want to load?", request.message());
                assertEquals(List.of(), request.initiallySelected());
                assertEquals(List.of("Ok", "Cancel"), request.buttons());
                for (DialogRow row : request.rows()) {
                    assertFalse(row.label().contains("<") || row.detail().contains("<"), "Plain text: " + row);
                    assertFalse(row.detail().isBlank(), "The details the dialog can show");
                }
                assertTrue(request.rows().get(0).label().contains("Atlas"), request.rows().get(0).label());
                assertTrue(request.rows().get(1).label().contains("Archer"), request.rows().get(1).label());
                assertSame(archer, loaded.result());
                assertNull(ask(fixture.source, load, GpuDialogFixture.pick(1, 1)).result(), "Cancel chooses no unit");
                assertNull(ask(fixture.source, load, GpuDialogFixture.pick(0)).result(), "OK without a row chooses no unit");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void numberSettingsAcceptOnlyTheirRange() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Callable<Integer> vibrabomb = () -> {
                    VibrabombSettingDialog dialog = new VibrabombSettingDialog(frame);
                    dialog.setVisible(true);
                    return dialog.getSetting();
                };
                Asked<Integer> set = ask(fixture.source, vibrabomb, typed(0, "35"));
                DialogRequest request = set.request();
                assertEquals(DialogKind.INPUT, request.kind());
                assertEquals("Vibrabomb setting", request.title());
                assertEquals("Select a setting (10-200 tons):", request.message());
                assertEquals("10", request.initialText());
                assertEquals(10, request.min());
                assertEquals(200, request.max());
                assertEquals(List.of(Messages.getString("Okay")), request.buttons());
                assertEquals(-1, request.cancelButton(), "Esc closes it like the dialog's close box");
                assertEquals(35, set.result());
                // Every caller uses the setting, and 0 is none: Esc keeps the one shown
                assertEquals(10, ask(fixture.source, vibrabomb, typed(-1, null)).result(), "Esc: the setting shown");
                assertEquals(10, ask(fixture.source, vibrabomb, typed(0, "5")).result(),
                      "A setting the dialog rejects is not taken");
                // The minefield deployment reads an EMP setting of 0 as a cancel, so Esc keeps that meaning there
                assertEquals(0, ask(fixture.source, () -> {
                    EMPMineSettingDialog dialog = new EMPMineSettingDialog(frame);
                    dialog.setVisible(true);
                    return dialog.getSetting();
                }, typed(-1, null)).result(), "Esc: no EMP setting");

                Callable<Integer> launch = () -> {
                    TeleMissileSettingDialog dialog = new TeleMissileSettingDialog(frame, fixture.game);
                    dialog.setVisible(true);
                    return dialog.getSetting();
                };
                Asked<Integer> velocity = ask(fixture.source, launch, typed(0, "12"));
                assertEquals("Enter Launch Velocity:", velocity.request().message());
                assertEquals(1, velocity.request().min());
                assertEquals(50, velocity.request().max(), "The game's bearings-only velocity");
                assertEquals(12, velocity.result());
                // The attack divides by the velocity, so Esc keeps the one shown, within the game's maximum
                assertEquals(50, ask(fixture.source, launch, typed(-1, null)).result(), "Esc: the velocity shown");
                onSwing(() -> {
                    String maximum = OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_BEARINGS_ONLY_VELOCITY;
                    fixture.game.getOptions().getOption(maximum).setValue(30);
                    return null;
                });
                assertEquals(30, ask(fixture.source, launch, typed(-1, null)).result(), "Esc: at most the maximum");

                Callable<List<Integer>> sinks = () -> {
                    SliderDialog dialog = new SliderDialog(frame, "Heat sinks", "Active heat sinks?", 4, 0, 10);
                    boolean confirmed = dialog.showDialog();
                    return List.of(confirmed ? 1 : 0, dialog.getValue());
                };
                Asked<List<Integer>> slid = ask(fixture.source, sinks, typed(0, "7"));
                assertEquals("4", slid.request().initialText());
                assertEquals(0, slid.request().min());
                assertEquals(10, slid.request().max());
                assertEquals(List.of(Messages.getString("Okay"), Messages.getString("Cancel")),
                      slid.request().buttons());
                assertEquals(List.of(1, 7), slid.result());
                assertEquals(List.of(0, 4), ask(fixture.source, sinks, typed(1, "7")).result(),
                      "Cancel keeps the value");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void gifConsentForAResumedGameIsAskedNativelyAndRemembered() throws Exception {
        ClientGUI gui = routingClient();
        GUIPreferences preferences = GUIPreferences.getInstance();
        GifRecordingMode mode = preferences.getGifGameSummaryRecording();
        Method consent = MinimapPanel.class.getDeclaredMethod("askWhetherToRecordGif", Component.class,
              IClientGUI.class);
        consent.setAccessible(true);
        Method recallOrAsk = MinimapPanel.class.getDeclaredMethod("recallOrAskRecordingDecision");
        recallOrAsk.setAccessible(true);
        Field decisions = MinimapPanel.class.getDeclaredField("GIF_RECORDING_DECISIONS");
        decisions.setAccessible(true);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            try {
                preferences.setGifGameSummaryRecording(GifRecordingMode.ASK);
                Asked<Object> declined = ask(fixture.source, () -> consent.invoke(null, null, gui),
                      new DialogAnswer(1, List.of(), null, false, List.of()));
                DialogRequest request = declined.request();
                assertEquals(DialogKind.MESSAGE, request.kind());
                assertEquals("Record Game Summary GIF?", request.title());
                assertEquals(Messages.getString("MinimapPanel.RecordGifDialog.message") + "\n"
                      + Messages.getString("MinimapPanel.RecordGifDialog.warning"), request.message());
                assertEquals("Remember my choice (change later in Client Settings)", request.checkbox());
                assertEquals(false, declined.result());
                assertEquals(GifRecordingMode.ASK, preferences.getGifGameSummaryRecording(), "Not remembered");

                // A game resumed without a lobby ready asks when the first summary frame is due, through its client
                MinimapPanel panel = onSwing(() -> new MinimapPanel(null, fixture.game, fixture.view, gui, 0));
                assertEquals(true, ask(fixture.source, () -> recallOrAsk.invoke(panel),
                      new DialogAnswer(0, List.of(), null, true, List.of())).result());
                assertEquals(GifRecordingMode.ALWAYS, preferences.getGifGameSummaryRecording(), "Remembered");
            } finally {
                ((Map<?, ?>) decisions.get(null)).remove(fixture.game.getUUIDString());
                preferences.setGifGameSummaryRecording(mode);
                dismiss();
            }
        }
    }

    @Test
    void swingDialogsRemainWhileNoNativeWindowDrawsThem() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = GpuDialogFixture.clientFrame(gui);
            JFrame lobbyFrame = onSwing(JFrame::new);
            GpuBoardWindow window = present(gui, fixture.view, fixture.source);
            try {
                GpuDialogFixture.set(window, "presented", false);
                GpuDialogFixture.set(window, "closing", true);
                assertEquals("Hunchback", onSwing(() -> {
                    try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                        swing.when(() -> JOptionPane.showInputDialog(any(), any(), any(), anyInt(), any(), any(),
                              any())).thenReturn("Hunchback");
                        return gui.input("Target?", "TAG", JOptionPane.QUESTION_MESSAGE,
                              new Object[] { "Atlas", "Hunchback" }, "Atlas");
                    }
                }), "The facade returns the Swing dialog's value");
                assertTrue(onSwing(() -> GpuDialogFixture.closedAfterShowing(ChoiceDialog.class, () -> {
                    ChoiceDialog dialog = new ChoiceDialog(frame, "Drop", "Which?", new String[] { "A", "B" });
                    dialog.setVisible(true);
                    return dialog.getChoices() == null;
                })), "The choice dialog shows as before, and closing it chooses nothing");
                // The tooltip cannot format the details of a unit outside the game, which Swing shows only on request
                Entity outside = new MekFileParser(new File("testresources/megamek/common/units/Archer ARC-2R.mtf"))
                      .getEntity();
                assertTrue(onSwing(() -> GpuDialogFixture.closedAfterShowing(EntityChoiceDialog.class,
                      () -> EntityChoiceDialog.showSingleChoiceDialog(frame, "DeploymentDisplay.loadUnitDialog.title",
                            "Which?", List.of(fixture.entity, outside)) == null)),
                      "The unit choice shows as before, without formatting the native rows");
                assertTrue(onSwing(() -> GpuDialogFixture.closedAfterShowing(VibrabombSettingDialog.class, () -> {
                    VibrabombSettingDialog dialog = new VibrabombSettingDialog(frame);
                    dialog.setVisible(true);
                    return dialog.getSetting() == 0;
                })));

                GpuDialogFixture.set(window, "presented", true);
                assertTrue(onSwing(() -> GpuDialogFixture.closedAfterShowing(ChoiceDialog.class, () -> {
                    ChoiceDialog dialog = new ChoiceDialog(lobbyFrame, "Drop", "Which?", new String[] { "A" });
                    dialog.setVisible(true);
                    return dialog.getChoices() == null;
                })), "A dialog over a frame of no client stays Swing");
                assertNull(fixture.source.dialog(), "No native dialog was asked");
            } finally {
                dismiss();
                onSwing(() -> {
                    frame.dispose();
                    lobbyFrame.dispose();
                    return null;
                });
            }
        }
    }

    /** The ECM suite choice dialogs among the application's windows. */
    private static long ecmDialogs() {
        return Arrays.stream(Window.getWindows()).filter(EcmSuiteChoiceDialog.class::isInstance).count();
    }

    /** An INPUT answer: the button (-1 is Esc) and the typed text. */
    private static DialogAnswer typed(int button, String text) {
        return new DialogAnswer(button, List.of(), text, false, List.of());
    }
}
