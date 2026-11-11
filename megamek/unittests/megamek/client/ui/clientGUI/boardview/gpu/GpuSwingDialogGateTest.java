/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import megamek.MMConstants;
import megamek.client.ui.Base64Image;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.GifRecordingMode;
import megamek.client.ui.clientGUI.IClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.Declaring;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTurretFacingRoutingTest.Turrets;
import megamek.client.ui.dialogs.BotCommands.HexTargetPicker;
import megamek.client.ui.dialogs.ChoiceDialog;
import megamek.client.ui.dialogs.ConfirmDialog;
import megamek.client.ui.dialogs.MMDialogs.MMNarrativeStoryDialog;
import megamek.client.ui.dialogs.SliderDialog;
import megamek.client.ui.dialogs.TurretFacingDialog;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.client.ui.dialogs.phaseDisplay.AbandonUnitDialog;
import megamek.client.ui.dialogs.phaseDisplay.AutomaticEjectionDialog;
import megamek.client.ui.dialogs.phaseDisplay.BombPayloadDialog;
import megamek.client.ui.dialogs.phaseDisplay.BuildingFacingDialog;
import megamek.client.ui.dialogs.phaseDisplay.CalledBlowDialog;
import megamek.client.ui.dialogs.phaseDisplay.DetonateChargesDialog;
import megamek.client.ui.dialogs.phaseDisplay.EMPMineSettingDialog;
import megamek.client.ui.dialogs.phaseDisplay.EntityChoiceDialog;
import megamek.client.ui.dialogs.phaseDisplay.FlightPathNotice;
import megamek.client.ui.dialogs.phaseDisplay.InfantryActionDeclarationDialog;
import megamek.client.ui.dialogs.phaseDisplay.LandingConfirmation;
import megamek.client.ui.dialogs.phaseDisplay.ManeuverChoiceDialog;
import megamek.client.ui.dialogs.phaseDisplay.MineDensityDialog;
import megamek.client.ui.dialogs.phaseDisplay.MineLayingDialog;
import megamek.client.ui.dialogs.phaseDisplay.MinesweeperActivationDialog;
import megamek.client.ui.dialogs.phaseDisplay.NovaNetworkDialog;
import megamek.client.ui.dialogs.phaseDisplay.SeaMineDepthDialog;
import megamek.client.ui.dialogs.phaseDisplay.SuicideImplantsDialog;
import megamek.client.ui.dialogs.phaseDisplay.TeleMissileSettingDialog;
import megamek.client.ui.dialogs.phaseDisplay.TriggerAPPodDialog;
import megamek.client.ui.dialogs.phaseDisplay.TriggerBPodDialog;
import megamek.client.ui.dialogs.phaseDisplay.VariableRangeTargetingDialog;
import megamek.client.ui.dialogs.phaseDisplay.VibrabombSettingDialog;
import megamek.client.ui.panels.phaseDisplay.ActionPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.client.ui.panels.phaseDisplay.DeploymentHelper;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.TargetingPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.VictoryHexPropertiesPane;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.equipment.Cargo;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.event.GameScriptedMessageEvent;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * With the client's battle window presented and its own dialog listener running, each prompt chokepoint of swing-inventory table 2, each
 * table-3 dialog that N7 made native and a list prompt of each class E6d routed asks in the native window, and no
 * Swing window shows; without the window the Legacy 2D client shows the same openers as Swing dialogs. The Swing
 * surfaces the user kept are raised over the battle window; any other Swing dialog is logged at error level and not
 * raised. The test thread plays the GL thread; the Swing dialogs need a display.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(900)
class GpuSwingDialogGateTest {
    private static final long WAIT_SECONDS = 20;
    /** A JOptionPane's dialog: a client message box, or a chokepoint's Swing fallback. */
    private static final Predicate<Window> MESSAGE_BOX = window -> shows(window, JOptionPane.class);
    private static final String[] BLOWS = { "No called blow (full body)", "Aim high (Punch table)",
          "Aim low (Kick table)" };

    @Test
    void theClientsPromptChokepointsAskNativelyOverTheBattleWindowAndInSwingOnTheLegacy2DClient() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientPreferences clientPreferences = PreferenceManager.getClientPreferences();
        boolean hadFlightPathNotice = clientPreferences.hasProperty("ShowFlightPathNotice");
        boolean flightPathNotice = clientPreferences.getBoolean("ShowFlightPathNotice");
        GUIPreferences preferences = GUIPreferences.getInstance();
        GifRecordingMode gifRecording = preferences.getGifGameSummaryRecording();
        Method gifConsent = MinimapPanel.class.getDeclaredMethod("askWhetherToRecordGif", Component.class,
              IClientGUI.class);
        gifConsent.setAccessible(true);
        Turrets turrets = Turrets.create();
        try (Gate gate = new Gate(turrets.gui, turrets.fixture)) {
            clientPreferences.setValue("ShowFlightPathNotice", true);
            preferences.setGifGameSummaryRecording(GifRecordingMode.ASK);
            ClientGUI gui = turrets.gui;
            JFrame frame = turrets.frame;
            // D1, D3, D9: the yes/no question, the alert and the JOptionPane facade
            gate.drive("D1 doYesNoDialog", DialogKind.MESSAGE, type(ConfirmDialog.class),
                  () -> gui.doYesNoDialog("Eject?", "Eject the pilot?"));
            gate.drive("D3 doAlertDialog", DialogKind.MESSAGE, MESSAGE_BOX, () -> {
                gui.doAlertDialog("Error", "Could not save the game: disk full");
                return null;
            });
            gate.drive("D9 confirm", DialogKind.MESSAGE, MESSAGE_BOX, () -> gui.confirm("Flee?", "Confirm",
                  JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
            gate.drive("D9 option", DialogKind.MESSAGE, MESSAGE_BOX, () -> gui.option("Domino?", "Domino",
                  JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                  new Object[] { "Forward", "Backward", "No action" }, "Forward"));
            gate.drive("D9 message", DialogKind.MESSAGE, MESSAGE_BOX, () -> {
                gui.message("The Mek can no longer hold on.", "Climbing", JOptionPane.WARNING_MESSAGE);
                return null;
            });
            // D5: the aero notice and confirmation
            gate.drive("D5 SimpleNagNotice", DialogKind.MESSAGE, MESSAGE_BOX, () -> {
                new FlightPathNotice(gui).show();
                return null;
            });
            gate.drive("D5 SimpleConfirmDialog", DialogKind.MESSAGE, MESSAGE_BOX, () -> {
                new LandingConfirmation(gui).show();
                return null;
            });
            // D6, D7, D10: the choice dialogs
            gate.drive("D6 AbstractChoiceDialog", DialogKind.CHOICE, type(EntityChoiceDialog.class),
                  () -> EntityChoiceDialog.showSingleChoiceDialog(frame, "DeploymentDisplay.loadUnitDialog.title",
                        "Which unit do you want to load?", List.of(turrets.atlas, turrets.bulldog)));
            gate.drive("D7 ManeuverChoiceDialog", DialogKind.CHOICE, type(ManeuverChoiceDialog.class), () -> {
                new ManeuverChoiceDialog(frame, "Maneuver").setVisible(true);
                return null;
            });
            gate.drive("D10 ChoiceDialog, one", DialogKind.CHOICE, type(ChoiceDialog.class), () -> {
                new ChoiceDialog(frame, "Select", "Which one?", new String[] { "Medium Laser", "Small Laser" }, true)
                      .setVisible(true);
                return null;
            });
            gate.drive("D10 ChoiceDialog, several", DialogKind.MULTI, type(ChoiceDialog.class), () -> {
                new ChoiceDialog(frame, "Drop units", "Which units drop?", new String[] { "Sparrowhawk", "Corsair",
                      "Stuka" }, false, 2).setVisible(true);
                return null;
            });
            gate.drive("D10 doChoiceDialog", DialogKind.MULTI, type(ChoiceDialog.class),
                  () -> gui.doChoiceDialog("Unload", "Unload stranded units?", "Atlas", "Bulldog"));
            // D8, D11: the input facade with values and with text
            gate.drive("D8 input, a list", DialogKind.CHOICE, MESSAGE_BOX, () -> gui.input("Which hex?",
                  "Choose Hex", JOptionPane.QUESTION_MESSAGE, new Object[] { "0505", "0606" }, "0505"));
            gate.drive("D11 input, a text", DialogKind.INPUT, MESSAGE_BOX, () -> gui.input("Save the game as:",
                  "Save On Server", JOptionPane.QUESTION_MESSAGE, null, "savegame.sav.gz"));
            // D11: the number prompts
            gate.drive("D11 VibrabombSettingDialog", DialogKind.INPUT, type(VibrabombSettingDialog.class), () -> {
                new VibrabombSettingDialog(frame).setVisible(true);
                return null;
            });
            gate.drive("D11 EMPMineSettingDialog", DialogKind.INPUT, type(EMPMineSettingDialog.class), () -> {
                new EMPMineSettingDialog(frame).setVisible(true);
                return null;
            });
            gate.drive("D11 TeleMissileSettingDialog", DialogKind.INPUT, type(TeleMissileSettingDialog.class), () -> {
                new TeleMissileSettingDialog(frame, turrets.fixture.game).setVisible(true);
                return null;
            });
            gate.drive("D11 SliderDialog", DialogKind.INPUT, type(SliderDialog.class), () -> {
                new SliderDialog(frame, "Heat sinks", "Active heat sinks?", 4, 0, 10).setVisible(true);
                return null;
            });
            // D12: the turret facing picker (the aimed shot offer is the HUD's own, never a dialog)
            gate.drive("D12 TurretFacingDialog", DialogKind.CHOICE, type(TurretFacingDialog.class),
                  turrets::atlasPicker);
            // D13: the GIF recording consent of a resumed game
            gate.drive("D13 GIF consent", DialogKind.MESSAGE, MESSAGE_BOX, () -> gifConsent.invoke(null, null, gui));
        } finally {
            turrets.close();
            clientPreferences.setValue("ShowFlightPathNotice", !hadFlightPathNotice || flightPathNotice);
            preferences.setGifGameSummaryRecording(gifRecording);
        }
    }

    @Test
    void theTableThreeDialogsAskNativelyOverTheBattleWindowAndInSwingOnTheLegacy2DClient() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        Entity minelayer = GpuBombMineDialogRoutingTest.minelayer();
        Entity platoon = GpuImplantBlowFacingRoutingTest.platoon();
        Turrets turrets = Turrets.create();
        try (Gate gate = new Gate(turrets.gui, turrets.fixture)) {
            ClientGUI gui = turrets.gui;
            JFrame frame = turrets.frame;
            // R1: bombs and mines
            gate.drive("R1 BombPayloadDialog", DialogKind.FORM, type(BombPayloadDialog.class), () -> {
                new BombPayloadDialog(frame, "Bomb Payload, internal", GpuBombMineDialogRoutingTest.loadout(), false,
                      false, 2, 0).setVisible(true);
                return null;
            });
            gate.drive("R1 MineLayingDialog", DialogKind.CHOICE, type(MineLayingDialog.class), () -> {
                new MineLayingDialog(frame, minelayer).setVisible(true);
                return null;
            });
            gate.drive("R1 SeaMineDepthDialog", DialogKind.CHOICE, type(SeaMineDepthDialog.class), () -> {
                new SeaMineDepthDialog(frame, 3).setVisible(true);
                return null;
            });
            gate.drive("R1 MineDensityDialog", DialogKind.CHOICE, type(MineDensityDialog.class), () -> {
                new MineDensityDialog(frame).setVisible(true);
                return null;
            });
            // R4: implants, called blow, building facing
            gate.drive("R4 SuicideImplantsDialog", DialogKind.FORM, type(SuicideImplantsDialog.class),
                  () -> new SuicideImplantsDialog(frame, platoon).showDialog());
            gate.drive("R4 CalledBlowDialog", DialogKind.CHOICE, type(CalledBlowDialog.class),
                  () -> new CalledBlowDialog(frame, "Hatchet", BLOWS).showDialog());
            gate.drive("R4 BuildingFacingDialog", DialogKind.CHOICE, type(BuildingFacingDialog.class),
                  () -> new BuildingFacingDialog(frame, gui, turrets.atlas, Set.of(1, 2, 4)).showDialog());
            // R5: the victory point editor, whose Swing pane shows in a JOptionPane
            gate.drive("R5 VictoryHexPropertiesPane", DialogKind.FORM, MESSAGE_BOX, () -> {
                ObjectiveMarker point = new ObjectiveMarker();
                point.setName("Objective 0505");
                return VictoryHexPropertiesPane.edit(frame, point, GpuVictoryHexFormTest.players(), false);
            });
            // R9: a scenario's story
            gate.drive("R9 MMNarrativeStoryDialog", DialogKind.MESSAGE, type(MMNarrativeStoryDialog.class), () -> {
                new MMNarrativeStoryDialog(frame, story()).setVisible(true);
                return null;
            });
            // R8: a bot order's hex pick is the HUD's board pick, with no control dialog
            gate.pick("R8 HexTargetPicker", () -> new HexTargetPicker(gui, turrets.fixture.view, "Strategic Target",
                  false, 0, hexes -> { }));
        } finally {
            turrets.close();
        }

        // R2 and R3 on the pre-end declarations' fixtures: each needs its own units
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean ejectionNag = preferences.getNagForAutoEject();
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            declaring.abandonReady();
            AbstractBuildingEntity building = declaring.building(declaring.enemy);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            preferences.setNagForAutoEject(true);
            gate.drive("R2 AbandonUnitDialog", DialogKind.MULTI, type(AbandonUnitDialog.class), () -> {
                new AbandonUnitDialog(declaring.frame, declaring.gui).setVisible(true);
                return null;
            });
            gate.drive("R2 InfantryActionDeclarationDialog", DialogKind.FORM,
                  type(InfantryActionDeclarationDialog.class), () -> new InfantryActionDeclarationDialog(
                        declaring.frame, declaring.fixture.game, declaring.fixture.player, building).showDialog());
            gate.drive("R3 AutomaticEjectionDialog", DialogKind.MULTI, type(AutomaticEjectionDialog.class), () -> {
                new AutomaticEjectionDialog(declaring.frame, declaring.gui, List.of(declaring.fixture.entity),
                      "vacuum").setVisible(true);
                return null;
            });
        } finally {
            preferences.setNagForAutoEject(ejectionNag);
        }
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            declaring.variableRange();
            gate.drive("R2 VariableRangeTargetingDialog", DialogKind.FORM, type(VariableRangeTargetingDialog.class),
                  () -> {
                      new VariableRangeTargetingDialog(declaring.frame, declaring.gui).setVisible(true);
                      return null;
                  });
        }
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            declaring.minesweepers();
            gate.drive("R2 MinesweeperActivationDialog", DialogKind.FORM, type(MinesweeperActivationDialog.class),
                  () -> {
                      new MinesweeperActivationDialog(declaring.frame, declaring.gui).setVisible(true);
                      return null;
                  });
        }
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            declaring.novaUnits();
            gate.drive("R2 NovaNetworkDialog", DialogKind.MESSAGE, type(NovaNetworkDialog.class), () -> {
                new NovaNetworkDialog(declaring.frame, declaring.gui).setVisible(true);
                return null;
            });
        }
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            declaring.charges();
            gate.drive("R2 DetonateChargesDialog", DialogKind.MULTI, type(DetonateChargesDialog.class), () -> {
                new DetonateChargesDialog(declaring.frame, declaring.gui).setVisible(true);
                return null;
            });
        }
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            Entity archer = podCarrier(declaring);
            gate.drive("R3 TriggerAPPodDialog", DialogKind.MULTI, type(TriggerAPPodDialog.class), () -> {
                new TriggerAPPodDialog(declaring.frame, archer).setVisible(true);
                return null;
            });
            gate.drive("R3 TriggerBPodDialog", DialogKind.MULTI, type(TriggerBPodDialog.class), () -> {
                new TriggerBPodDialog(declaring.gui, archer, Infantry.LEG_ATTACK).setVisible(true);
                return null;
            });
            // E6d: the B-pod's choice among the infantry in the hex is a routed list prompt
            Method chooseTarget = TriggerBPodDialog.class.getDeclaredMethod("chooseTarget", Coords.class);
            chooseTarget.setAccessible(true);
            gate.drive("E6d TriggerBPodDialog, the target", DialogKind.CHOICE, MESSAGE_BOX,
                  () -> chooseTarget.invoke(new TriggerBPodDialog(declaring.gui, archer, Infantry.LEG_ATTACK),
                        archer.getPosition()));
        }
    }

    @Test
    void aListPromptOfEachRoutedClassAndTheBotherNagAskNativelyOverTheBattleWindowAndInSwingOnTheLegacy2DClient()
          throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        // MovementDisplay: the player a traitor unit goes to; the crane helpers' carrier and facing, which get only the
        // client frame; the deployment helper's elevation above the listed ones
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            JFrame frame = clientFrame(moving.gui);
            when(moving.gui.getFrame()).thenReturn(frame);
            onSwing(() -> {
                Player enemy = new Player(2, "Enemy");
                enemy.setTeam(2);
                moving.board.game.addPlayer(enemy.getId(), enemy);
                return null;
            });
            try (Gate gate = new Gate(moving.gui, moving.board)) {
                gate.drive("E6d MovementDisplay, a traitor's new owner", DialogKind.CHOICE, MESSAGE_BOX, () -> {
                    moving.display.actionPerformed(new ActionEvent(moving.display, ActionEvent.ACTION_PERFORMED,
                          MoveCommand.MOVE_TRAITOR.getCmd()));
                    return null;
                });
                Class<?> crane = Class.forName("megamek.client.ui.panels.phaseDisplay.CraneCommandDialogs");
                Method chooseCarrier = crane.getDeclaredMethod("chooseCarrier", JFrame.class, Entity.class,
                      List.class);
                chooseCarrier.setAccessible(true);
                Dropship union = new Dropship();
                union.setChassis("Union");
                Dropship leopard = new Dropship();
                leopard.setChassis("Leopard");
                gate.drive("Z1 CraneCommandDialogs, the carrier", DialogKind.CHOICE, MESSAGE_BOX,
                      () -> chooseCarrier.invoke(null, frame, moving.unit, List.of(union, leopard)));
                Method chooseFacing = crane.getDeclaredMethod("chooseFacing", JFrame.class, Entity.class);
                chooseFacing.setAccessible(true);
                gate.drive("Z1 CraneCommandDialogs, the unload facing", DialogKind.CHOICE, MESSAGE_BOX,
                      () -> chooseFacing.invoke(null, frame, moving.unit));
                Method highElevation = DeploymentHelper.class.getDeclaredMethod("showHighElevationChoiceDialog");
                highElevation.setAccessible(true);
                gate.drive("Z1 DeploymentHelper, the elevation", DialogKind.INPUT, MESSAGE_BOX,
                      () -> highElevation.invoke(new DeploymentHelper(moving.gui)));
            } finally {
                dispose(frame);
            }
        }

        // FiringDisplay, TargetingPhaseDisplay and PhysicalDisplay over one firing fixture; the bother nag too
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            JFrame frame = clientFrame(firing.gui);
            when(firing.gui.getFrame()).thenReturn(frame);
            doCallRealMethod().when(firing.gui).doYesNoBotherDialog(anyString(), anyString());
            Tank bulldog = jammedVehicle(firing);
            onSwing(() -> {
                // Two clubs, so that the punch asks which one swings
                firing.attacker.addEquipment(EquipmentType.get("ISSmallVibroblade"), Mek.LOC_LEFT_ARM);
                firing.attacker.addEquipment(EquipmentType.get("ISSmallVibroblade"), Mek.LOC_RIGHT_ARM);
                return null;
            });
            TargetingPhaseDisplay targeting = onSwing(() -> new TargetingPhaseDisplay(firing.gui, false));
            PhysicalDisplay physical = onSwing(() -> new PhysicalDisplay(firing.gui));
            try (Gate gate = new Gate(firing.gui, firing.board)) {
                Method nag = ActionPhaseDisplay.class.getDeclaredMethod("doYesNoBotherDialog", String.class,
                      String.class, Runnable.class);
                nag.setAccessible(true);
                gate.drive("D2 doYesNoBotherDialog", DialogKind.MESSAGE, type(ConfirmDialog.class),
                      () -> nag.invoke(firing.display, "Are you sure?", "The unit takes no action.",
                            (Runnable) () -> { }));
                Method firingJam = FiringDisplay.class.getDeclaredMethod("doClearWeaponJam");
                firingJam.setAccessible(true);
                gate.drive("E6d FiringDisplay, the jammed weapon", DialogKind.CHOICE, MESSAGE_BOX, () -> {
                    firing.display.selectEntity(bulldog.getId());
                    return firingJam.invoke(firing.display);
                });
                Method targetingSelect = TargetingPhaseDisplay.class.getDeclaredMethod("selectEntity", int.class);
                targetingSelect.setAccessible(true);
                Method targetingJam = TargetingPhaseDisplay.class.getDeclaredMethod("doClearWeaponJam");
                targetingJam.setAccessible(true);
                gate.drive("E6d TargetingPhaseDisplay, the jammed weapon", DialogKind.CHOICE, MESSAGE_BOX, () -> {
                    targetingSelect.invoke(targeting, bulldog.getId());
                    return targetingJam.invoke(targeting);
                });
                Method chooseClub = PhysicalDisplay.class.getDeclaredMethod("chooseClub");
                chooseClub.setAccessible(true);
                gate.drive("E6d PhysicalDisplay, the club", DialogKind.CHOICE, MESSAGE_BOX, () -> {
                    physical.selectEntity(firing.attacker.getId());
                    physical.target(firing.ahead);
                    return chooseClub.invoke(physical);
                });
            } finally {
                onSwing(() -> {
                    targeting.removeAllListeners();
                    physical.removeAllListeners();
                    return null;
                });
                dispose(frame);
            }
        }

        // DeployMinefieldDisplay: the cargo to place next
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            when(declaring.client.isMyTurn()).thenReturn(true);
            declaring.fixture.player.getGroundObjectsToPlace().addAll(List.of(
                  GpuListPromptBridgeTest.groundObject(new Cargo(), "Supply crate", 2),
                  GpuListPromptBridgeTest.groundObject(new Cargo(), "Ammo pallet", 4)));
            DeployMinefieldDisplay placing = GpuListPromptBridgeTest.placingCargo(declaring.gui);
            try {
                gate.drive("E6d DeployMinefieldDisplay, the cargo", DialogKind.CHOICE, MESSAGE_BOX,
                      () -> GpuListPromptBridgeTest.placeAt(placing, declaring.fixture, new Coords(3, 3)));
            } finally {
                onSwing(() -> {
                    placing.removeAllListeners();
                    return null;
                });
            }
        }
    }

    @Test
    void theExitsSaveFileIsAskedInTheSystemDialogOverTheBattleWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows the battle window");
        File folder = new File(System.getProperty("java.io.tmpdir"));
        FileNameExtensionFilter saves = new FileNameExtensionFilter("Saved games", "sav.gz", "sav");
        try (Declaring declaring = Declaring.create()) {
            declaring.present();
            List<Boolean> asked = new ArrayList<>();
            try {
                SystemFileDialog.fake((request, answer) -> {
                    asked.add(request.save());
                    answer.accept(new File(folder, "gpu-gate-battle"));
                });
                assertEquals(Optional.of(new File(folder, "gpu-gate-battle.sav.gz")),
                      onSwing(() -> GpuBoardWindow.chooseFile(declaring.gui, folder, "Save", saves, true)),
                      "The saved game gets the filter's extension");
                assertEquals(List.of(true), asked, "Asked once, as a save");
                SystemFileDialog.fake((request, answer) -> answer.accept(null));
                assertEquals(Optional.empty(),
                      onSwing(() -> GpuBoardWindow.chooseFile(declaring.gui, folder, "Save", saves, true)),
                      "Cancelled");
            } finally {
                SystemFileDialog.fake(null);
            }
            assertNull(onSwing(() -> GpuBoardWindow.chooseFile(mock(ClientGUI.class), folder, "Save", saves, true)),
                  "Another client's window asks nothing; it shows Swing's chooser");
        }
    }

    @Test
    void theSwingSurfacesTheUserKeptAreRaisedOverTheBattleWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean noSaveNag = preferences.getNoSaveNag();
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            JFrame frame = declaring.frame;
            gate.present();
            // Client Settings are native now: their Swing dialog is no longer kept, so it is reported. Being modal, it
            // is still raised so it cannot hide behind the battle and lock the window. LOS, Help, the network
            // information, the Nova network view and the developer conditions editor have native cards too.
            CommonSettingsDialog settings = onSwing(() -> new CommonSettingsDialog(frame));
            gate.raised(() -> {
                settings.setVisible(true);
                return null;
            }, CommonSettingsDialog.class::isInstance);
            assertEquals(1, gate.errors.size(), "Client Settings' Swing dialog is reported");
            assertTrue(gate.errors.getFirst().contains(CommonSettingsDialog.class.getName()), gate.errors.toString());
            gate.errors.clear();
            onSwing(() -> {
                settings.dispose();
                return null;
            });
            // A file chooser (user item 21)
            assertTrue(gate.raised(() -> new JFileChooser().showOpenDialog(frame),
                  window -> shows(window, JFileChooser.class)), "Item 21: a file chooser");
            // The exit's save question (D11), from the client's own exit: asked natively now, Swing's without the window
            doCallRealMethod().when(declaring.gui).handleExit();
            preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, false);
            // The drive presents its own window; the one presented above would see the Legacy 2D client's box
            gate.dismiss();
            gate.drive("D11: the exit's save question", DialogKind.MESSAGE, MESSAGE_BOX, () -> {
                declaring.gui.handleExit();
                return null;
            });
            gate.present();
            assertEquals(List.of(), gate.errors, "No kept surface is reported");
        } finally {
            preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, noSaveNag);
        }
    }

    @Test
    void aSwingDialogOutsideTheAllowlistIsLoggedAtErrorLevelAndNotRaised() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            JFrame frame = declaring.frame;
            gate.present();
            assertFalse(gate.raised(() -> {
                JDialog probe = new JDialog(frame, "Gate probe");
                probe.setSize(120, 80);
                probe.setVisible(true);
                return null;
            }, window -> window instanceof JDialog dialog && "Gate probe".equals(dialog.getTitle())),
                  "A Swing dialog outside the allowlist is not raised");
            // A raw message box, as a prompt not routed through the client's chokepoints shows
            assertFalse(gate.raised(() -> {
                JDialog box = new JOptionPane("Load the cargo by crane?").createDialog(frame, "Load by Crane");
                box.setModal(false);
                box.setVisible(true);
                return null;
            }, MESSAGE_BOX), "A message box the user did not keep is not raised");
            assertFalse(gate.raised(() -> {
                JDialog box = new JOptionPane("About").createDialog(frame,
                      Messages.getString("about.title", MMConstants.PROJECT_NAME));
                box.setModal(false);
                box.setVisible(true);
                return null;
            }, MESSAGE_BOX), "About is native over the battle, so its Swing box is not raised");
            assertFalse(gate.raised(() -> {
                JDialog box = new JOptionPane("No title").createDialog(frame, null);
                box.setModal(false);
                box.setVisible(true);
                return null;
            }, MESSAGE_BOX), "An untitled message box is not raised");
            assertEquals(List.of("A Swing dialog outside the allowlist opened over the battle window: "
                  + "javax.swing.JDialog \"Gate probe\"", "A Swing dialog outside the allowlist opened over the battle "
                  + "window: javax.swing.JDialog \"Load by Crane\"", "A Swing dialog outside the allowlist opened over "
                  + "the battle window: javax.swing.JDialog \"About MegaMek\"", "A Swing dialog outside the allowlist "
                  + "opened over the battle window: javax.swing.JDialog \"null\""), gate.errors);

            // While the window closes, the client's prompts fall back to Swing, raised. While it starts they wait for
            // it (GpuDialogRoutingTest).
            gate.errors.clear();
            GpuDialogFixture.set(gate.window, "closing", true);
            assertTrue(gate.raised(() -> {
                declaring.gui.doAlertDialog("Disconnected", "The server closed the connection.");
                return null;
            }, MESSAGE_BOX), "An alert while the window closes");
            GpuDialogFixture.set(gate.window, "closing", false);

            // Dialogs of no owner or of another window are not the battle window's (R14, R15): left as they are. A
            // local bot's own alert asks natively over its creator's window instead (GpuDialogRoutingTest).
            JFrame other = onSwing(JFrame::new);
            try {
                assertFalse(gate.raised(() -> {
                    JDialog box = new JOptionPane("Princess could not save her units.").createDialog(null,
                          "Error Saving File");
                    box.setModal(false);
                    box.setVisible(true);
                    return null;
                }, MESSAGE_BOX), "R15: a box with no owner, as MMLogger.errorDialog shows");
                assertFalse(gate.raised(() -> {
                    JDialog box = new JOptionPane(Messages.getString("GpuBoard.alreadyOpen")).createDialog(other,
                          "MegaMek");
                    box.setModal(false);
                    box.setVisible(true);
                    return null;
                }, MESSAGE_BOX), "R14: another client's message");
            } finally {
                dispose(other);
            }
            assertEquals(List.of(), gate.errors, "Only the battle window's own dialogs are reported");
        }
    }

    @Test
    void aModalSwingDialogIsRaisedEvenOutsideTheAllowlistOrWithoutAnOwner() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        try (Declaring declaring = Declaring.create(); Gate gate = new Gate(declaring.gui, declaring.fixture)) {
            JFrame frame = declaring.frame;
            gate.present();
            // Hidden behind the native window, a modal dialog would block the client unseen; it is still reported.
            assertTrue(gate.raised(() -> {
                JDialog probe = new JDialog(frame, "Modal gate probe", true);
                probe.setSize(120, 80);
                probe.setVisible(true);
                return null;
            }, window -> window instanceof JDialog dialog && "Modal gate probe".equals(dialog.getTitle())),
                  "A modal dialog outside the allowlist");
            assertEquals(List.of("A Swing dialog outside the allowlist opened over the battle window: "
                  + "javax.swing.JDialog \"Modal gate probe\""), gate.errors);
            // The first turn's sound question falls back to an ownerless confirmation box.
            assertTrue(gate.raised(() -> JOptionPane.showConfirmDialog(null, "Mute?", "Turn Notifications",
                  JOptionPane.YES_NO_OPTION), MESSAGE_BOX), "A modal box without an owner");
        }
    }

    /** An Archer (42) of the player with an AP pod and a B-pod in its left leg; two enemy squads in its hex. */
    private static Entity podCarrier(Declaring declaring) throws Exception {
        Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
        onSwing(() -> {
            archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_LEG);
            archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_LEFT_LEG);
            return null;
        });
        declaring.unit("Elemental BA [Laser] (Sqd5).blk", 50, declaring.enemy, archer.getPosition());
        declaring.unit("Elemental BA [Laser] (Sqd5).blk", 51, declaring.enemy, archer.getPosition());
        return archer;
    }

    /** A Bulldog (60) of the firing player, deployed beside the Atlas, with a jammed weapon to clear. */
    private static Tank jammedVehicle(GpuFiringFixture firing) throws Exception {
        Tank bulldog = (Tank) GpuFiringFixture.unit("Bulldog Medium Tank.blk", 60, new Coords(5, 7));
        return onSwing(() -> {
            bulldog.setOwner(firing.board.player);
            firing.board.game.addEntity(bulldog, false);
            bulldog.addJammedWeapon(bulldog.getWeaponList().getFirst());
            return bulldog;
        });
    }

    /** A scripted message with a small image, as the server sends it. */
    private static GameScriptedMessageEvent story() {
        return new GameScriptedMessageEvent(GpuSwingDialogGateTest.class, "Ambush", "<p>The trap springs.</p>",
              new Base64Image(new BufferedImage(30, 40, BufferedImage.TYPE_INT_ARGB)));
    }

    private static Predicate<Window> type(Class<? extends Window> type) {
        return type::isInstance;
    }

    /** Whether the window is a Swing dialog that shows a component of the type, as a message box or chooser does. */
    private static boolean shows(Window window, Class<?> type) {
        return window instanceof JDialog dialog && Arrays.stream(dialog.getContentPane().getComponents())
              .anyMatch(type::isInstance);
    }

    /** EDT: the application's windows that show. */
    private static Set<Window> showing() {
        return Arrays.stream(Window.getWindows()).filter(Window::isShowing).collect(Collectors.toSet());
    }

    private static List<String> names(List<Window> windows) {
        return windows.stream().map(window -> window.getClass().getName()
              + ((window instanceof Dialog dialog) ? " \"" + dialog.getTitle() + "\"" : "")).toList();
    }

    private static void dispose(Frame frame) throws Exception {
        onSwing(() -> {
            frame.dispose();
            return null;
        });
    }

    /**
     * One client under the gate: its native window registered as presented over the fixture's board source, with the
     * window's own dialog listener installed as opening the window installs it; every Swing window that shows is
     * recorded, and what the window logs at error level is collected.
     */
    private static final class Gate implements AutoCloseable {
        final List<String> errors = new CopyOnWriteArrayList<>();
        private final ClientGUI gui;
        private final GpuBoardFixture fixture;
        private final List<Window> shown = new CopyOnWriteArrayList<>();
        private final AWTEventListener watcher = event -> {
            if ((event.getID() == ComponentEvent.COMPONENT_SHOWN) && (event.getSource() instanceof Window window)) {
                shown.add(window);
            }
        };
        private final ErrorLog log = new ErrorLog(errors);
        private final Logger logger = (Logger) LogManager.getLogger(GpuBoardWindow.class);
        private GpuBoardWindow window;
        private AWTEventListener listener;

        Gate(ClientGUI gui, GpuBoardFixture fixture) throws ReflectiveOperationException {
            this.gui = gui;
            this.fixture = fixture;
            // The client's main panel, by which its Swing alert sizes itself (a mocked client has none).
            Field panel = ClientGUI.class.getDeclaredField("clientGuiPanel");
            panel.setAccessible(true);
            panel.set(gui, new JPanel());
            Toolkit.getDefaultToolkit().addAWTEventListener(watcher, AWTEvent.COMPONENT_EVENT_MASK);
            log.start();
            logger.addAppender(log);
        }

        /** Registers the client's native window as presented, with its dialog listener. */
        void present() throws Exception {
            window = GpuDialogFixture.present(gui, fixture.view, fixture.source);
            Field field = GpuBoardWindow.class.getDeclaredField("dialogListener");
            field.setAccessible(true);
            listener = (AWTEventListener) field.get(window);
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.COMPONENT_EVENT_MASK);
        }

        /** Unregisters the window and its listener: the Legacy 2D client. */
        void dismiss() throws Exception {
            if (listener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                listener = null;
            }
            GpuDialogFixture.dismiss();
        }

        /**
         * Runs the opener with the window presented, answering each native request as Esc does, then on the Legacy 2D
         * client, closing each Swing window it shows. Natively the first request is of the kind and no Swing window
         * shows; on the Legacy 2D client a window of the surface shows and nothing is asked natively.
         */
        void drive(String name, DialogKind kind, Predicate<Window> surface, Callable<?> opener) throws Exception {
            present();
            try {
                DialogRequest first = asksNatively(name, opener);
                assertEquals(kind, first.kind(), name + ": " + first.title());
            } finally {
                dismiss();
            }
            showsSwing(name, surface, opener);
        }

        private DialogRequest asksNatively(String name, Callable<?> opener) throws Exception {
            settle();
            shown.clear();
            errors.clear();
            FutureTask<?> task = new FutureTask<>(opener);
            SwingUtilities.invokeLater(task);
            DialogRequest first = null;
            long answered = -1;
            long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
            while (!task.isDone()) {
                assertTrue(System.nanoTime() < deadline, name + ": still open; asked " + fixture.source.dialog()
                      + "; Swing windows shown " + names(shown));
                DialogRequest request = fixture.source.dialog();
                if ((request != null) && (request.id() != answered)) {
                    first = (first == null) ? request : first;
                    fixture.source.answer(request.id(), DialogAnswer.cancelled(request));
                    answered = request.id();
                }
                Thread.sleep(5);
            }
            task.get();
            settle();
            assertNotNull(first, name + ": asked in the native window");
            assertEquals(List.of(), names(shown), name + ": no Swing window over the battle window");
            assertEquals(List.of(), errors, name + ": nothing outside the allowlist");
            return first;
        }

        private void showsSwing(String name, Predicate<Window> surface, Callable<?> opener) throws Exception {
            settle();
            Set<Window> before = onSwing(GpuSwingDialogGateTest::showing);
            FutureTask<?> task = new FutureTask<>(opener);
            SwingUtilities.invokeLater(task);
            List<String> closed = new ArrayList<>();
            boolean matched = false;
            long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                boolean done = task.isDone();
                List<Window> opened = onSwing(() -> showing().stream().filter(open -> !before.contains(open))
                      .toList());
                // A message box empties itself once closed, so it is recognised while it shows.
                matched |= opened.stream().anyMatch(surface);
                closed.addAll(names(opened));
                onSwing(() -> {
                    opened.forEach(open -> {
                        open.setVisible(false);
                        open.dispose();
                    });
                    return null;
                });
                if (done && opened.isEmpty()) {
                    break;
                }
                assertTrue(System.nanoTime() < deadline, name + ": the Legacy 2D client's dialog stays open");
                Thread.sleep(10);
            }
            task.get();
            assertTrue(matched, name + ": the Legacy 2D client shows its Swing dialog, " + closed);
            assertNull(fixture.source.dialog(), name + ": nothing asked natively on the Legacy 2D client");
        }

        /**
         * A board pick: with the window presented the pick runs in the HUD, so nothing is asked and no Swing window
         * shows; on the Legacy 2D client its control dialog shows.
         */
        void pick(String name, Callable<HexTargetPicker> picker) throws Exception {
            present();
            try {
                settle();
                shown.clear();
                errors.clear();
                HexTargetPicker running = onSwing(() -> {
                    HexTargetPicker started = picker.call();
                    started.start();
                    return started;
                });
                settle();
                assertNull(fixture.source.dialog(), name + ": a board pick asks nothing");
                assertEquals(List.of(), names(shown), name + ": no control dialog over the battle window");
                assertEquals(List.of(), errors, name + ": nothing outside the allowlist");
                onSwing(() -> {
                    running.cancel();
                    return null;
                });
            } finally {
                dismiss();
            }
            Set<Window> before = onSwing(GpuSwingDialogGateTest::showing);
            HexTargetPicker running = onSwing(() -> {
                HexTargetPicker started = picker.call();
                started.start();
                return started;
            });
            List<Window> opened = onSwing(() -> showing().stream().filter(open -> !before.contains(open)).toList());
            onSwing(() -> {
                running.cancel();
                return null;
            });
            assertTrue(opened.stream().anyMatch(JDialog.class::isInstance),
                  name + ": the Legacy 2D client shows the control dialog, " + names(opened));
        }

        /**
         * With the window presented: runs the opener on the EDT, waits for the window of the surface to show, and
         * returns whether the window raised it; then closes it (a modal one returns to the opener).
         */
        boolean raised(Callable<?> opener, Predicate<Window> surface) throws Exception {
            settle();
            Set<Window> before = onSwing(GpuSwingDialogGateTest::showing);
            FutureTask<?> task = new FutureTask<>(opener);
            SwingUtilities.invokeLater(task);
            Window shownWindow = awaitShowing(open -> !before.contains(open) && surface.test(open));
            boolean raised = onSwing(shownWindow::isAlwaysOnTop);
            onSwing(() -> {
                shownWindow.setVisible(false);
                shownWindow.dispose();
                return null;
            });
            task.get(WAIT_SECONDS, SECONDS);
            return raised;
        }

        /** Waits until a window that matches shows, polling on the EDT (inside any modal dialog's loop). */
        Window awaitShowing(Predicate<Window> match) throws Exception {
            long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                Window found = onSwing(() -> showing().stream().filter(match).findFirst().orElse(null));
                if (found != null) {
                    // The dialog listener runs on the show event, which is queued before this check's next task.
                    settle();
                    return found;
                }
                assertTrue(System.nanoTime() < deadline, "No such window showed");
                Thread.sleep(10);
            }
        }

        /** Lets the Swing thread run what is queued, the show events and the listener's raise among them. */
        private static void settle() throws Exception {
            onSwing(() -> null);
            onSwing(() -> null);
        }

        @Override
        public void close() throws Exception {
            try {
                dismiss();
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(watcher);
                logger.removeAppender(log);
                log.stop();
            }
        }
    }

    /** Collects the messages GpuBoardWindow logs at error level. */
    private static final class ErrorLog extends AbstractAppender {
        private final List<String> messages;

        ErrorLog(List<String> messages) {
            super("GpuSwingDialogGateTest", null, PatternLayout.createDefaultLayout(), false, null);
            this.messages = messages;
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }
    }
}
