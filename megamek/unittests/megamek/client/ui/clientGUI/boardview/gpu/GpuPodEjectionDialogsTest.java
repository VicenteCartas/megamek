/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.closedAfterShowing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.press;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.labels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.texts;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.ticked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.List;
import javax.swing.JCheckBox;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.Declaring;
import megamek.client.ui.dialogs.phaseDisplay.AutomaticEjectionDialog;
import megamek.client.ui.dialogs.phaseDisplay.TriggerAPPodDialog;
import megamek.client.ui.dialogs.phaseDisplay.TriggerBPodDialog;
import megamek.common.actions.TriggerAPPodAction;
import megamek.common.actions.TriggerBPodAction;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The special-equipment dialogs (swing-inventory table 3, row R3). The Swing tests record, as literals, which pods each
 * dialog offers and triggers and which ejection settings it sends for scripted clicks; they ran on the dialogs before
 * their data and results were shared with the native requests. The native tests answer the same choices in the native
 * window, where the test thread plays the render thread, and expect the same results; without a native window each
 * dialog stays Swing.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuPodEjectionDialogsTest {

    @Test
    void antiPersonnelPodsInSwingTriggerTheTickedFirablePods() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            List<Mounted<?>> pods = onSwing(() -> List.of(
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_LEG),
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_RIGHT_LEG),
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_ARM)));
            onSwing(() -> {
                pods.get(2).setFired(true);
                return null;
            });
            TriggerAPPodDialog dialog = onSwing(() -> new TriggerAPPodDialog(declaring.frame, archer));
            assertEquals(List.of("[ ] Left Leg Anti-Personnel Pods (A-Pods)",
                  "[ ] Right Leg Anti-Personnel Pods (A-Pods)", "[  disabled] Left Arm Anti-Personnel Pods (A-Pods)"),
                  onSwing(() -> texts(dialog)).subList(1, 4),
                  "Every pod, a fired one disabled");
            List<TriggerAPPodAction> triggered = onSwing(() -> {
                components(dialog.getContentPane(), JCheckBox.class).get(1).setSelected(true);
                press(dialog, "Okay");
                return Collections.list(dialog.getActions());
            });
            assertEquals(1, triggered.size());
            assertEquals(List.of(42, archer.getEquipmentNum(pods.get(1))),
                  List.of(triggered.getFirst().getEntityId(), triggered.getFirst().getPodId()));

            TriggerAPPodDialog closed = onSwing(() -> new TriggerAPPodDialog(declaring.frame, archer));
            List<TriggerAPPodAction> closedActions = onSwing(() -> {
                components(closed.getContentPane(), JCheckBox.class).getFirst().setSelected(true);
                closed.dispatchEvent(new WindowEvent(closed, WindowEvent.WINDOW_CLOSING));
                return Collections.list(closed.getActions());
            });
            assertEquals(List.of(archer.getEquipmentNum(pods.getFirst())),
                  closedActions.stream().map(TriggerAPPodAction::getPodId).toList(),
                  "The close box keeps the ticks: the pod still fires");
        }
    }

    @Test
    void antiBattleArmorPodsInSwingOfferOnlyThePodsTheAttackAllows() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            List<Mounted<?>> pods = onSwing(() -> List.of(
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_RIGHT_ARM),
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_LEFT_LEG),
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_LEFT_TORSO, true)));
            Entity squad = declaring.unit("Elemental BA [Laser] (Sqd5).blk", 50, declaring.enemy,
                  archer.getPosition());
            TriggerBPodDialog swarm = onSwing(() -> new TriggerBPodDialog(declaring.gui, archer, Infantry.SWARM_MEK));
            assertEquals(List.of("[ ] Right Arm Anti-BattleArmor Pods (B-Pods)",
                  "[  disabled] Left Leg Anti-BattleArmor Pods (B-Pods)",
                  "[  disabled] Left Torso Anti-BattleArmor Pods (B-Pods)"), onSwing(() -> texts(swarm)).subList(1, 4),
                  "A swarm attack: front arm and side torso pods only");
            TriggerBPodDialog leg = onSwing(() -> new TriggerBPodDialog(declaring.gui, archer, Infantry.LEG_ATTACK));
            assertEquals(List.of("[  disabled] Right Arm Anti-BattleArmor Pods (B-Pods)",
                  "[ ] Left Leg Anti-BattleArmor Pods (B-Pods)",
                  "[  disabled] Left Torso Anti-BattleArmor Pods (B-Pods)"), onSwing(() -> texts(leg)).subList(1, 4),
                  "A leg attack: leg and centre torso pods only");
            List<TriggerBPodAction> triggered = onSwing(() -> {
                components(leg.getContentPane(), JCheckBox.class).get(1).setSelected(true);
                press(leg, "Okay");
                return Collections.list(leg.getActions());
            });
            assertEquals(1, triggered.size());
            assertEquals(List.of(42, archer.getEquipmentNum(pods.get(1)), squad.getId()), List.of(
                  triggered.getFirst().getEntityId(), triggered.getFirst().getPodId(),
                  triggered.getFirst().getTargetId()));
        }
    }

    @Test
    void automaticEjectionInSwingSendsTheChangedSettingsAndKeepsTheNagChoice() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForAutoEject();
        try (Declaring declaring = Declaring.create()) {
            Entity atlas = declaring.fixture.entity;
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            onSwing(() -> {
                ((Mek) atlas).setAutoEject(true);
                ((Mek) archer).setAutoEject(false);
                preferences.setNagForAutoEject(true);
                return null;
            });
            List<Entity> units = List.of(atlas, archer);
            AutomaticEjectionDialog dialog = onSwing(() -> new AutomaticEjectionDialog(declaring.frame, declaring.gui,
                  units, "vacuum"));
            assertEquals(List.of("Atlas AS7-D", "No kit", "[x] Eject automatically", "Archer ARC-2R", "No kit",
                  "[ ] Eject automatically", "[ ] Stop asking me this"), onSwing(() -> texts(dialog)).subList(4, 11));
            onSwing(() -> {
                List<JCheckBox> boxes = components(dialog.getContentPane(), JCheckBox.class);
                boxes.get(0).setSelected(false);
                boxes.get(1).setSelected(true);
                press(dialog, "Deploy");
                return null;
            });
            verify(declaring.client).sendEjectionSettingChange(1, false);
            verify(declaring.client).sendEjectionSettingChange(42, true);
            assertFalse(dialog.isDeploymentCancelled());
            assertTrue(preferences.getNagForAutoEject());
            clearInvocations(declaring.client);

            AutomaticEjectionDialog cancelled = onSwing(() -> new AutomaticEjectionDialog(declaring.frame,
                  declaring.gui, units, "vacuum"));
            onSwing(() -> {
                List<JCheckBox> boxes = components(cancelled.getContentPane(), JCheckBox.class);
                boxes.get(1).setSelected(true);
                boxes.get(2).setSelected(true);
                press(cancelled, "Cancel");
                return null;
            });
            assertTrue(cancelled.isDeploymentCancelled());
            assertFalse(preferences.getNagForAutoEject(), "Cancel keeps the player's \"stop asking\"");

            onSwing(() -> {
                preferences.setNagForAutoEject(true);
                return null;
            });
            AutomaticEjectionDialog closed = onSwing(() -> new AutomaticEjectionDialog(declaring.frame,
                  declaring.gui, units, "vacuum"));
            onSwing(() -> {
                components(closed.getContentPane(), JCheckBox.class).get(2).setSelected(true);
                closed.dispatchEvent(new WindowEvent(closed, WindowEvent.WINDOW_CLOSING));
                return null;
            });
            assertTrue(closed.isDeploymentCancelled());
            assertTrue(preferences.getNagForAutoEject(), "The close box changes nothing");
            verify(declaring.client, never()).sendEjectionSettingChange(anyInt(), anyBoolean());
        } finally {
            preferences.setNagForAutoEject(nag);
        }
    }

    @Test
    void antiPersonnelPodsAnsweredNativelyTriggerWhatSwingTriggers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            List<Mounted<?>> pods = onSwing(() -> List.of(
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_LEG),
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_RIGHT_LEG),
                  archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_ARM)));
            onSwing(() -> {
                pods.get(2).setFired(true);
                return null;
            });
            declaring.present();
            TriggerAPPodDialog dialog = onSwing(() -> new TriggerAPPodDialog(declaring.frame, archer));
            Asked<List<TriggerAPPodAction>> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return Collections.list(dialog.getActions());
            }, ticked(0, 1));
            DialogRequest request = asked.request();
            assertEquals(DialogKind.MULTI, request.kind());
            assertEquals("Trigger AP Pods", request.title());
            assertEquals("Select the Anti-Personnel Pods on \r\nArcher ARC-2R (GPU review)\nthat you want to trigger.",
                  request.message());
            assertEquals(List.of("Left Leg Anti-Personnel Pods (A-Pods)", "Right Leg Anti-Personnel Pods (A-Pods)",
                  "Left Arm Anti-Personnel Pods (A-Pods)"), labels(request));
            assertEquals(List.of(true, true, false), request.rows().stream().map(DialogRow::enabled).toList(),
                  "A fired pod cannot be ticked");
            assertEquals(List.of("Okay"), request.buttons());
            assertEquals(0, request.cancelButton(), "Esc answers as Okay: the close box keeps the ticks too");
            assertEquals(List.of(List.of(42, archer.getEquipmentNum(pods.get(1)))), asked.result().stream()
                  .map(action -> List.of(action.getEntityId(), action.getPodId())).toList());
        }
    }

    @Test
    void antiBattleArmorPodsAnsweredNativelyTriggerWhatSwingTriggers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            List<Mounted<?>> pods = onSwing(() -> List.of(
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_RIGHT_ARM),
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_LEFT_LEG),
                  archer.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_LEFT_TORSO, true)));
            Entity squad = declaring.unit("Elemental BA [Laser] (Sqd5).blk", 50, declaring.enemy,
                  archer.getPosition());
            declaring.present();
            TriggerBPodDialog dialog = onSwing(() -> new TriggerBPodDialog(declaring.gui, archer, Infantry.LEG_ATTACK));
            Asked<List<TriggerBPodAction>> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return Collections.list(dialog.getActions());
            }, ticked(0, 1));
            DialogRequest request = asked.request();
            assertEquals("Trigger ABA Pods", request.title());
            assertEquals(List.of(false, true, false), request.rows().stream().map(DialogRow::enabled).toList(),
                  "A leg attack: leg and centre torso pods only");
            assertEquals(0, request.cancelButton());
            assertEquals(List.of(List.of(42, archer.getEquipmentNum(pods.get(1)), squad.getId())), asked.result()
                  .stream().map(action -> List.of(action.getEntityId(), action.getPodId(), action.getTargetId()))
                  .toList());
        }
    }

    @Test
    void automaticEjectionAnsweredNativelySendsAndKeepsTheNagChoiceAsInSwing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForAutoEject();
        try (Declaring declaring = Declaring.create()) {
            Entity atlas = declaring.fixture.entity;
            Entity archer = declaring.unit("Archer ARC-2R.mtf", 42, declaring.fixture.player, new Coords(7, 3));
            onSwing(() -> {
                ((Mek) atlas).setAutoEject(true);
                ((Mek) archer).setAutoEject(false);
                preferences.setNagForAutoEject(true);
                return null;
            });
            List<Entity> units = List.of(atlas, archer);
            declaring.present();
            AutomaticEjectionDialog deployed = onSwing(() -> new AutomaticEjectionDialog(declaring.frame,
                  declaring.gui, units, "vacuum"));
            Asked<Boolean> asked = ask(declaring.fixture.source, () -> {
                deployed.setVisible(true);
                return deployed.isDeploymentCancelled();
            }, ticked(0, 1));
            DialogRequest request = asked.request();
            assertEquals(DialogKind.MULTI, request.kind());
            assertEquals("Ejection will kill unprotected crews", request.title());
            assertTrue(request.message().startsWith("Anyone who ejects into vacuum out there will die"),
                  request.message());
            assertEquals(List.of("Atlas AS7-D", "Archer ARC-2R"), labels(request));
            assertEquals(List.of("No kit", "No kit"), request.rows().stream().map(DialogRow::detail).toList(),
                  "Each row names what its crew is wearing, as the Swing crew column does");
            assertEquals(List.of(0), request.initiallySelected(), "Ticked as each unit is set to eject");
            assertEquals("Stop asking me this", request.checkbox());
            assertEquals(List.of("Deploy", "Cancel"), request.buttons());
            assertEquals(-1, request.cancelButton(), "Esc is the close box");
            assertFalse(asked.result());
            verify(declaring.client).sendEjectionSettingChange(1, false);
            verify(declaring.client).sendEjectionSettingChange(42, true);
            assertTrue(preferences.getNagForAutoEject());
            clearInvocations(declaring.client);

            AutomaticEjectionDialog cancelled = onSwing(() -> new AutomaticEjectionDialog(declaring.frame,
                  declaring.gui, units, "vacuum"));
            assertTrue(ask(declaring.fixture.source, () -> {
                cancelled.setVisible(true);
                return cancelled.isDeploymentCancelled();
            }, new DialogAnswer(1, List.of(), null, true, List.of())).result());
            assertFalse(preferences.getNagForAutoEject(), "Cancel keeps the player's \"stop asking\"");

            onSwing(() -> {
                preferences.setNagForAutoEject(true);
                return null;
            });
            AutomaticEjectionDialog closed = onSwing(() -> new AutomaticEjectionDialog(declaring.frame,
                  declaring.gui, units, "vacuum"));
            assertTrue(ask(declaring.fixture.source, () -> {
                closed.setVisible(true);
                return closed.isDeploymentCancelled();
            }, new DialogAnswer(-1, List.of(), null, true, List.of())).result());
            assertTrue(preferences.getNagForAutoEject(), "Esc, like the close box, changes nothing");
            verify(declaring.client, never()).sendEjectionSettingChange(anyInt(), anyBoolean());
        } finally {
            preferences.setNagForAutoEject(nag);
        }
    }

    @Test
    void swingDialogsRemainWhileNoNativeWindowDrawsThem() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            Entity atlas = declaring.fixture.entity;
            assertTrue(onSwing(() -> closedAfterShowing(TriggerAPPodDialog.class, () -> {
                TriggerAPPodDialog dialog = new TriggerAPPodDialog(declaring.frame, atlas);
                dialog.setVisible(true);
                return !dialog.getActions().hasMoreElements();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(TriggerBPodDialog.class, () -> {
                TriggerBPodDialog dialog = new TriggerBPodDialog(declaring.gui, atlas, Infantry.SWARM_MEK);
                dialog.setVisible(true);
                return !dialog.getActions().hasMoreElements();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(AutomaticEjectionDialog.class, () -> {
                AutomaticEjectionDialog dialog = new AutomaticEjectionDialog(declaring.frame, declaring.gui,
                      List.of(atlas), "vacuum");
                dialog.setVisible(true);
                return dialog.isDeploymentCancelled();
            })));
            assertNull(declaring.fixture.source.dialog(), "No native dialog was asked");
        }
    }
}
