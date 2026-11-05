/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.closedAfterShowing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.next;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.press;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.FieldKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.phaseDisplay.AbandonUnitDialog;
import megamek.client.ui.dialogs.phaseDisplay.DetonateChargesDialog;
import megamek.client.ui.dialogs.phaseDisplay.InfantryActionDeclarationDialog;
import megamek.client.ui.dialogs.phaseDisplay.MinesweeperActivationDialog;
import megamek.client.ui.dialogs.phaseDisplay.NovaNetworkDialog;
import megamek.client.ui.dialogs.phaseDisplay.VariableRangeTargetingDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.common.InfantryActionDeclaration;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.board.CubeCoords;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.enums.VariableRangeTargetingMode;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.BuildingEntity;
import megamek.common.units.ConvInfantry;
import megamek.common.units.DemolitionCharge;
import megamek.common.units.Entity;
import megamek.common.units.IBuilding;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The pre-end declaration dialogs (swing-inventory table 3, row R2). The Swing tests record, as literals, what each
 * dialog sends or declares for scripted clicks; they ran on the dialogs before their data and results were shared with
 * the native requests. The native tests answer the same choices in the native window, where the test thread plays the
 * render thread, and expect the same results; without a native window each dialog stays Swing.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuPreEndDialogsTest {

    @Test
    void abandonmentInSwingAnnouncesTheTickedUnits() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.abandonReady();
            AbandonUnitDialog dialog = onSwing(() -> new AbandonUnitDialog(declaring.frame, declaring.gui));
            assertEquals(List.of("[ ] ", "Atlas AS7-D", "Unnamed", "[ ] ", "Archer ARC-2R", "Unnamed"),
                  onSwing(() -> texts(dialog)).subList(4, 10), "The prone, shut-down Meks with their crews");
            onSwing(() -> {
                components(dialog, JCheckBox.class).get(1).setSelected(true);
                press(dialog, "Confirm");
                return null;
            });
            verify(declaring.client).sendUnitAbandonmentAnnouncement(42);
            verify(declaring.client, never()).sendUnitAbandonmentAnnouncement(1);
            assertTrue(dialog.wasApplied());

            AbandonUnitDialog cancelled = onSwing(() -> new AbandonUnitDialog(declaring.frame, declaring.gui));
            onSwing(() -> {
                components(cancelled, JCheckBox.class).forEach(box -> box.setSelected(true));
                press(cancelled, "Cancel");
                return null;
            });
            verify(declaring.client, never()).sendUnitAbandonmentAnnouncement(1);
            assertFalse(cancelled.wasApplied());
        }
    }

    @Test
    void detonationInSwingAnnouncesTheTickedChargesWithOneToast() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            List<DemolitionCharge> charges = declaring.charges();
            DetonateChargesDialog dialog = onSwing(() -> new DetonateChargesDialog(declaring.frame, declaring.gui));
            assertEquals(List.of("[ ] ", "Building #0", "0101", "30", "[ ] ", "Building #2000", "0301", "12"),
                  onSwing(() -> texts(dialog)).subList(5, 13), "The player's charges, not the enemy's");
            onSwing(() -> {
                components(dialog, JCheckBox.class).get(1).setSelected(true);
                press(dialog, "Detonate");
                return null;
            });
            verify(declaring.client).sendExplodeBuilding(charges.get(1));
            verify(declaring.client, never()).sendExplodeBuilding(charges.get(0));
            verify(declaring.gui).addToast(ToastLevel.SUCCESS, "1 demolition charge(s) detonated!");
            assertTrue(dialog.wasApplied());

            DetonateChargesDialog none = onSwing(() -> new DetonateChargesDialog(declaring.frame, declaring.gui));
            onSwing(() -> {
                press(none, "Detonate");
                return null;
            });
            assertFalse(none.wasApplied(), "Detonate with nothing ticked announces nothing");
        }
    }

    @Test
    void variableRangeTargetingInSwingSendsOnlyTheChangedModes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.variableRange();
            VariableRangeTargetingDialog dialog = onSwing(() -> new VariableRangeTargetingDialog(declaring.frame,
                  declaring.gui));
            assertEquals(List.of("Atlas AS7-D", "Long", "[x] Long", "[ ] Short", "Archer ARC-2R", "Long->Short",
                  "[ ] Long", "[x] Short"), onSwing(() -> texts(dialog)).subList(4, 12),
                  "Each unit's mode and the mode it switches to, the effective one selected");
            onSwing(() -> {
                // Atlas Long -> Short; the Archer keeps the Short it already switches to
                components(dialog, JRadioButton.class).get(1).setSelected(true);
                press(dialog, "Apply");
                return null;
            });
            verify(declaring.client).sendVariableRangeTargetingModeChange(1, VariableRangeTargetingMode.SHORT);
            verify(declaring.client, never()).sendVariableRangeTargetingModeChange(eq(42), any());
            assertTrue(dialog.wasApplied());

            VariableRangeTargetingDialog unchanged = onSwing(() -> new VariableRangeTargetingDialog(declaring.frame,
                  declaring.gui));
            onSwing(() -> {
                press(unchanged, "Apply");
                return null;
            });
            assertFalse(unchanged.wasApplied(), "Apply without a change sends nothing");
        }
    }

    @Test
    void minesweepersInSwingSwitchOnlyWhereTheStateChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            List<MiscMounted> sweepers = declaring.minesweepers();
            MinesweeperActivationDialog dialog = onSwing(() -> new MinesweeperActivationDialog(declaring.frame,
                  declaring.gui));
            assertEquals(List.of("Atlas AS7-D", "On", "[x] On", "[ ] Off", "Archer ARC-2R", "On->Off", "[ ] On",
                  "[x] Off"), onSwing(() -> texts(dialog)).subList(4, 12),
                  "Each sweeper's state and the state it switches to, the effective one selected");
            onSwing(() -> {
                // Atlas On -> Off; the Archer keeps the Off it already switches to
                components(dialog, JRadioButton.class).get(1).setSelected(true);
                press(dialog, "Apply");
                return null;
            });
            int atlasSweeper = declaring.fixture.entity.getEquipmentNum(sweepers.getFirst());
            verify(declaring.client).sendModeChange(1, atlasSweeper, 1);
            verify(declaring.client, never()).sendModeChange(eq(42), anyInt(), anyInt());
            assertEquals("Off", sweepers.getFirst().pendingMode().getName());
            assertTrue(dialog.wasApplied());
        }
    }

    @Test
    void novaNetworksInSwingLinkAndApplyWhatWasQueued() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.novaUnits();
            NovaNetworkDialog dialog = onSwing(() -> new NovaNetworkDialog(declaring.frame, declaring.gui));
            assertEquals(List.of("== Your Units ===", "ID 1: Atlas AS7-D[Unlinked]", "ID 42: Archer ARC-2R[Unlinked]",
                  "", "== Allied Units ===", "ID 43: Hachiwara HCA-6P[Unlinked](Ally)"), onSwing(() -> rows(dialog)));
            assertEquals("No pending changes.", onSwing(() -> pending(dialog)));
            onSwing(() -> {
                try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                    press(dialog, "Link Selected Units");
                    swing.verify(() -> JOptionPane.showMessageDialog(dialog, "Please select at least one unit.",
                          "Network Error", JOptionPane.ERROR_MESSAGE));
                    JList<?> list = components(dialog, JList.class).getFirst();
                    list.setSelectedIndices(new int[] { 1, 2 });
                    press(dialog, "Link Selected Units");
                    assertEquals("Archer ARC-2R: Network of Archer ARC-2R -> Network of Atlas AS7-D\n",
                          pending(dialog), "The Atlas keeps its own network, which the Archer joins");
                    press(dialog, "Apply Changes");
                }
                return null;
            });
            verify(declaring.client).sendNovaChange(1, "C3Nova.1");
            verify(declaring.client).sendNovaChange(42, "C3Nova.1");
            assertTrue(dialog.wasApplied());
        }
    }

    @Test
    void infantryAttackInSwingDeclaresTheTickedUnits() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            AbstractBuildingEntity building = declaring.building(declaring.enemy);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            declaring.platoon(61, declaring.fixture.player, building.getPosition());
            InfantryActionDeclarationDialog dialog = onSwing(() -> new InfantryActionDeclarationDialog(
                  declaring.frame, declaring.fixture.game, declaring.fixture.player, building));
            assertEquals("Attack Garrison (Enemy)", onSwing(dialog::getTitle));
            assertEquals(List.of("<html><b>Attacking with</b></html>",
                  "[x] Rifle Platoon 60 (GPU review): 21 Marine Points",
                  "[x] Rifle Platoon 61 (GPU review): 21 Marine Points", "Committed: 42 Marine Points, counted as 42",
                  "<html><b>Against</b></html>",
                  "Garrison (Enemy): crew worth up to 2 Marine Points; how many they commit is theirs to decide",
                  "Known: 0 Marine Points, up to 2 with the whole crew"), onSwing(() -> texts(dialog)));
            onSwing(() -> {
                components(dialog, JCheckBox.class).get(1).setSelected(false);
                press(dialog, "Ok");
                return null;
            });
            assertEquals(DialogResult.CONFIRMED, dialog.getResult());
            assertEquals(InfantryActionDeclaration.attacking(0, building.getId(), List.of(60), false),
                  dialog.getDeclaration());
            assertTrue(dialog.declaresAnything());
        }
    }

    @Test
    void infantryDefenceInSwingCommitsUnitsAndCrew() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            AbstractBuildingEntity building = declaring.building(declaring.fixture.player);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            declaring.platoon(70, declaring.enemy, building.getPosition());
            InfantryActionDeclarationDialog dialog = onSwing(() -> new InfantryActionDeclarationDialog(
                  declaring.frame, declaring.fixture.game, declaring.fixture.player, building));
            assertEquals("Defend Garrison (GPU review)", onSwing(dialog::getTitle));
            assertEquals(List.of("<html><b>Defending with</b></html>",
                  "[x] Rifle Platoon 60 (GPU review): 21 Marine Points", "0 of 4 living crew are committed.",
                  "Commit more crew:", "0",
                  "Committing 0 more: the crew are worth 0 Marine Points in all, and the building fires at +0 while"
                        + " they fight.", "Committed: 21 Marine Points, counted as 21", "<html><b>Against</b></html>",
                  "Rifle Platoon 70 (Enemy): 21 Marine Points if it attacks",
                  "Attackers: 21 Marine Points, counted as 21"), onSwing(() -> texts(dialog)));
            onSwing(() -> {
                JSpinner crew = components(dialog, JSpinner.class).getFirst();
                SpinnerNumberModel model = (SpinnerNumberModel) crew.getModel();
                assertEquals(List.of(0, 4), List.of(model.getMinimum(), model.getMaximum()));
                crew.setValue(2);
                assertEquals(List.of("Committing 2 more: the crew are worth 1 Marine Points in all, and the building"
                      + " fires at +3 while they fight.", "Committed: 22 Marine Points, counted as 22"),
                      texts(dialog).subList(5, 7));
                press(dialog, "Ok");
                return null;
            });
            assertEquals(DialogResult.CONFIRMED, dialog.getResult());
            assertEquals(InfantryActionDeclaration.defending(0, building.getId(), List.of(60), 2, false),
                  dialog.getDeclaration());
        }
    }

    // ---------------------------------------------------------------- native

    @Test
    void abandonmentAnsweredNativelyAnnouncesWhatSwingAnnounces() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.abandonReady();
            declaring.present();
            AbandonUnitDialog dialog = onSwing(() -> new AbandonUnitDialog(declaring.frame, declaring.gui));
            Asked<Boolean> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return dialog.wasApplied();
            }, ticked(0, 1));
            DialogRequest request = asked.request();
            assertEquals(DialogKind.MULTI, request.kind());
            assertEquals("Abandon Units", request.title());
            assertTrue(request.message().startsWith("Select units to abandon.\nCrew will exit"), request.message());
            assertEquals(List.of("Atlas AS7-D, crew: Unnamed", "Archer ARC-2R, crew: Unnamed"), labels(request));
            assertEquals(List.of("Confirm", "Cancel"), request.buttons());
            assertEquals(1, request.cancelButton(), "Esc cancels, as the close box does");
            assertTrue(asked.result());
            verify(declaring.client).sendUnitAbandonmentAnnouncement(42);
            verify(declaring.client, never()).sendUnitAbandonmentAnnouncement(1);

            AbandonUnitDialog cancelled = onSwing(() -> new AbandonUnitDialog(declaring.frame, declaring.gui));
            assertFalse(ask(declaring.fixture.source, () -> {
                cancelled.setVisible(true);
                return cancelled.wasApplied();
            }, ticked(1)).result(), "Cancel announces nothing");
            verify(declaring.client, times(1)).sendUnitAbandonmentAnnouncement(anyInt());
        }
    }

    @Test
    void detonationAnsweredNativelyAnnouncesWhatSwingAnnounces() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            List<DemolitionCharge> charges = declaring.charges();
            declaring.present();
            DetonateChargesDialog dialog = onSwing(() -> new DetonateChargesDialog(declaring.frame, declaring.gui));
            Asked<Boolean> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return dialog.wasApplied();
            }, ticked(0, 1));
            assertEquals(List.of("Building #0, hex 0101: 30 damage", "Building #2000, hex 0301: 12 damage"),
                  labels(asked.request()), "The player's charges, not the enemy's");
            assertEquals(List.of("Detonate", "Cancel"), asked.request().buttons());
            assertTrue(asked.result());
            verify(declaring.client).sendExplodeBuilding(charges.get(1));
            verify(declaring.client, never()).sendExplodeBuilding(charges.get(0));
            verify(declaring.gui).addToast(ToastLevel.SUCCESS, "1 demolition charge(s) detonated!");

            DetonateChargesDialog cancelled = onSwing(() -> new DetonateChargesDialog(declaring.frame,
                  declaring.gui));
            assertFalse(ask(declaring.fixture.source, () -> {
                cancelled.setVisible(true);
                return cancelled.wasApplied();
            }, ticked(1)).result(), "Cancel announces nothing");
            verify(declaring.client, times(1)).sendExplodeBuilding(any());
        }
    }

    @Test
    void variableRangeTargetingAnsweredNativelySendsWhatSwingSends() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.variableRange();
            declaring.present();
            VariableRangeTargetingDialog dialog = onSwing(() -> new VariableRangeTargetingDialog(declaring.frame,
                  declaring.gui));
            Asked<Boolean> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return dialog.wasApplied();
            }, values(0, "Short", "Short"));
            DialogRequest request = asked.request();
            assertEquals(DialogKind.FORM, request.kind());
            assertEquals(List.of("Atlas AS7-D (now Long)", "Archer ARC-2R (now Long->Short)"), fieldLabels(request));
            assertEquals(List.of(List.of("Long", "Short"), List.of("Long", "Short")),
                  request.fields().stream().map(DialogField::choices).toList());
            assertEquals(List.of("Long", "Short"), initials(request), "Each starts on the mode it will have");
            assertEquals(List.of("Apply", "Cancel"), request.buttons());
            assertTrue(asked.result());
            verify(declaring.client).sendVariableRangeTargetingModeChange(1, VariableRangeTargetingMode.SHORT);
            verify(declaring.client, never()).sendVariableRangeTargetingModeChange(eq(42), any());

            VariableRangeTargetingDialog cancelled = onSwing(() -> new VariableRangeTargetingDialog(declaring.frame,
                  declaring.gui));
            assertFalse(ask(declaring.fixture.source, () -> {
                cancelled.setVisible(true);
                return cancelled.wasApplied();
            }, values(1)).result(), "Cancel sends nothing");
            verify(declaring.client, times(1)).sendVariableRangeTargetingModeChange(anyInt(), any());
        }
    }

    @Test
    void minesweepersAnsweredNativelySwitchWhatSwingSwitches() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            List<MiscMounted> sweepers = declaring.minesweepers();
            declaring.present();
            MinesweeperActivationDialog dialog = onSwing(() -> new MinesweeperActivationDialog(declaring.frame,
                  declaring.gui));
            Asked<Boolean> asked = ask(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return dialog.wasApplied();
            }, values(0, "Off", "Off"));
            DialogRequest request = asked.request();
            assertEquals(DialogKind.FORM, request.kind());
            assertEquals(List.of("Atlas AS7-D (now On)", "Archer ARC-2R (now On->Off)"), fieldLabels(request));
            assertEquals(List.of("On", "Off"), request.fields().getFirst().choices());
            assertEquals(List.of("On", "Off"), initials(request), "Each starts on the state it will have");
            assertTrue(asked.result());
            int atlasSweeper = declaring.fixture.entity.getEquipmentNum(sweepers.getFirst());
            verify(declaring.client).sendModeChange(1, atlasSweeper, 1);
            verify(declaring.client, never()).sendModeChange(eq(42), anyInt(), anyInt());
            assertEquals("Off", sweepers.getFirst().pendingMode().getName());
        }
    }

    @Test
    void novaNetworksManagedNativelyLinkAndApplyAsInSwing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            declaring.novaUnits();
            declaring.present();
            NovaNetworkDialog dialog = onSwing(() -> new NovaNetworkDialog(declaring.frame, declaring.gui));
            Scripted<Boolean> linked = script(declaring.fixture.source, () -> {
                dialog.setVisible(true);
                return dialog.wasApplied();
            }, ticked(0), ticked(0), ticked(0), ticked(0, 1, 2), ticked(2));
            DialogRequest overview = linked.requests().get(0);
            assertEquals(DialogKind.MESSAGE, overview.kind());
            assertEquals("Nova CEWS Network Management", overview.title());
            assertEquals(String.join("\n", "Select units to link or unlink Nova CEWS networks. Changes take effect next"
                        + " turn. (Max 3 units per network)", "", "Available Nova CEWS Units:", "== Your Units ===",
                  "ID 1: Atlas AS7-D[Unlinked]", "ID 42: Archer ARC-2R[Unlinked]", "", "== Allied Units ===",
                  "ID 43: Hachiwara HCA-6P[Unlinked](Ally)", "", "Pending Changes (Take Effect Next Turn):",
                  "No pending changes."), overview.message());
            assertEquals(List.of("Link...", "Unlink...", "Apply Changes", "Revert All", "Close"), overview.buttons());
            assertEquals(List.of(-1, -1), List.of(overview.defaultButton(), overview.cancelButton()),
                  "Enter presses nothing; Esc is the close box");
            DialogRequest picker = linked.requests().get(1);
            assertEquals(DialogKind.MULTI, picker.kind());
            assertEquals(List.of(false, true, true, false, false, true), picker.rows().stream()
                  .map(DialogRow::enabled).toList(), "Headers and the spacer cannot be ticked");
            assertEquals(List.of("Link Selected Units", "Cancel"), picker.buttons());
            DialogRequest refusal = linked.requests().get(2);
            assertEquals(List.of("Network Error", "Please select at least one unit."),
                  List.of(refusal.title(), refusal.message()));
            assertTrue(linked.requests().get(4).message().endsWith("Pending Changes (Take Effect Next Turn):\n"
                  + "Archer ARC-2R: Network of Archer ARC-2R -> Network of Atlas AS7-D"));
            assertTrue(linked.result());
            verify(declaring.client).sendNovaChange(1, "C3Nova.1");
            verify(declaring.client).sendNovaChange(42, "C3Nova.1");

            // Close asks before it drops pending changes, No keeps them, and Esc, like the close box, does not ask
            NovaNetworkDialog closing = onSwing(() -> new NovaNetworkDialog(declaring.frame, declaring.gui));
            Scripted<Boolean> closed = script(declaring.fixture.source, () -> {
                closing.setVisible(true);
                return closing.wasApplied();
            }, ticked(0), ticked(0, 1, 2), ticked(4), ticked(1), ticked(-1));
            assertEquals("You have pending changes. Discard them?", closed.requests().get(3).message());
            assertTrue(closed.requests().get(4).message().endsWith("Network of Atlas AS7-D"), "No keeps the change");
            assertFalse(closed.result());

            // Revert All drops the pending changes, so Close then asks nothing
            NovaNetworkDialog reverting = onSwing(() -> new NovaNetworkDialog(declaring.frame, declaring.gui));
            Scripted<Boolean> reverted = script(declaring.fixture.source, () -> {
                reverting.setVisible(true);
                return reverting.wasApplied();
            }, ticked(0), ticked(0, 1, 2), ticked(3), ticked(4));
            assertTrue(reverted.requests().get(3).message().endsWith("No pending changes."));
            assertFalse(reverted.result());
            verify(declaring.client, times(2)).sendNovaChange(anyInt(), any());
        }
    }

    @Test
    void infantryAttackAnsweredNativelyFollowsTheChoicesAndDeclaresAsInSwing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            AbstractBuildingEntity building = declaring.building(declaring.enemy);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            declaring.platoon(61, declaring.fixture.player, building.getPosition());
            declaring.present();
            InfantryActionDeclarationDialog dialog = onSwing(() -> new InfantryActionDeclarationDialog(
                  declaring.frame, declaring.fixture.game, declaring.fixture.player, building));
            Scripted<DialogResult> declared = script(declaring.fixture.source, dialog::showDialog,
                  values(DialogAnswer.CHANGED, "true", "false"), values(0, "true", "false"));
            DialogRequest first = declared.requests().getFirst();
            assertEquals(DialogKind.FORM, first.kind());
            assertEquals("Attack Garrison (Enemy)", first.title());
            assertEquals(String.join("\n", "Against",
                  "Garrison (Enemy): crew worth up to 2 Marine Points; how many they commit is theirs to decide",
                  "Known: 0 Marine Points, up to 2 with the whole crew", "", "Attacking with",
                  "Committed: 42 Marine Points, counted as 42"), first.message());
            assertEquals(List.of("Rifle Platoon 60 (GPU review): 21 Marine Points",
                  "Rifle Platoon 61 (GPU review): 21 Marine Points"), fieldLabels(first));
            assertEquals(List.of("true", "true"), initials(first));
            assertTrue(first.fields().stream().allMatch(DialogField::live), "Each change answers the form at once");
            assertEquals(List.of("Ok", "Cancel"), first.buttons());
            DialogRequest second = declared.requests().get(1);
            assertTrue(second.message().endsWith("Committed: 21 Marine Points, counted as 21"), second.message());
            assertEquals(List.of("true", "false"), initials(second), "The form is asked again as the player left it");
            assertEquals(DialogResult.CONFIRMED, declared.result());
            assertEquals(InfantryActionDeclaration.attacking(0, building.getId(), List.of(60), false),
                  dialog.getDeclaration());

            InfantryActionDeclarationDialog cancelled = onSwing(() -> new InfantryActionDeclarationDialog(
                  declaring.frame, declaring.fixture.game, declaring.fixture.player, building));
            assertEquals(DialogResult.CANCELLED, ask(declaring.fixture.source, cancelled::showDialog, values(1))
                  .result());
        }
    }

    @Test
    void infantryDefenceAnsweredNativelyCommitsUnitsAndCrewAsInSwing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            AbstractBuildingEntity building = declaring.building(declaring.fixture.player);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            declaring.platoon(70, declaring.enemy, building.getPosition());
            declaring.present();
            InfantryActionDeclarationDialog dialog = onSwing(() -> new InfantryActionDeclarationDialog(
                  declaring.frame, declaring.fixture.game, declaring.fixture.player, building));
            Scripted<DialogResult> declared = script(declaring.fixture.source, dialog::showDialog,
                  values(DialogAnswer.CHANGED, "true", "2"), values(0, "true", "2"));
            DialogRequest first = declared.requests().getFirst();
            assertEquals("Defend Garrison (GPU review)", first.title());
            assertEquals(String.join("\n", "Against", "Rifle Platoon 70 (Enemy): 21 Marine Points if it attacks",
                  "Attackers: 21 Marine Points, counted as 21", "", "Defending with",
                  "0 of 4 living crew are committed.", "Committing 0 more: the crew are worth 0 Marine Points in all,"
                        + " and the building fires at +0 while they fight.",
                  "Committed: 21 Marine Points, counted as 21"), first.message());
            assertEquals(List.of(FieldKind.CHECKBOX, FieldKind.CHOICE), first.fields().stream()
                  .map(DialogField::kind).toList());
            assertEquals(List.of("Rifle Platoon 60 (GPU review): 21 Marine Points", "Commit more crew:"),
                  fieldLabels(first));
            assertEquals(List.of("0", "1", "2", "3", "4"), first.fields().get(1).choices(), "The spinner's range");
            assertEquals(List.of("true", "0"), initials(first));
            String second = declared.requests().get(1).message();
            assertTrue(second.contains("Committing 2 more: the crew are worth 1 Marine Points in all, and the building"
                  + " fires at +3 while they fight.\nCommitted: 22 Marine Points, counted as 22"), second);
            assertEquals(DialogResult.CONFIRMED, declared.result());
            assertEquals(InfantryActionDeclaration.defending(0, building.getId(), List.of(60), 2, false),
                  dialog.getDeclaration());
        }
    }

    @Test
    void swingDialogsRemainWhileNoNativeWindowDrawsThem() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        try (Declaring declaring = Declaring.create()) {
            AbstractBuildingEntity building = declaring.building(declaring.enemy);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            assertTrue(onSwing(() -> closedAfterShowing(AbandonUnitDialog.class, () -> {
                AbandonUnitDialog dialog = new AbandonUnitDialog(declaring.frame, declaring.gui);
                dialog.setVisible(true);
                return !dialog.wasApplied();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(DetonateChargesDialog.class, () -> {
                DetonateChargesDialog dialog = new DetonateChargesDialog(declaring.frame, declaring.gui);
                dialog.setVisible(true);
                return !dialog.wasApplied();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(VariableRangeTargetingDialog.class, () -> {
                VariableRangeTargetingDialog dialog = new VariableRangeTargetingDialog(declaring.frame, declaring.gui);
                dialog.setVisible(true);
                return !dialog.wasApplied();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(MinesweeperActivationDialog.class, () -> {
                MinesweeperActivationDialog dialog = new MinesweeperActivationDialog(declaring.frame, declaring.gui);
                dialog.setVisible(true);
                return !dialog.wasApplied();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(NovaNetworkDialog.class, () -> {
                NovaNetworkDialog dialog = new NovaNetworkDialog(declaring.frame, declaring.gui);
                dialog.setVisible(true);
                return !dialog.wasApplied();
            })));
            assertTrue(onSwing(() -> closedAfterShowing(InfantryActionDeclarationDialog.class,
                  () -> new InfantryActionDeclarationDialog(declaring.frame, declaring.fixture.game,
                        declaring.fixture.player, building).showDialog() == DialogResult.CANCELLED)));
            assertNull(declaring.fixture.source.dialog(), "No native dialog was asked");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A CHOICE or MULTI answer: the button (-1 is Esc) and the ticked rows. */
    static DialogAnswer ticked(int button, Integer... rows) {
        return new DialogAnswer(button, List.of(rows), null, false, List.of());
    }

    /** A FORM answer: the button and one value per field. */
    static DialogAnswer values(int button, String... values) {
        return new DialogAnswer(button, List.of(), null, false, List.of(values));
    }

    static List<String> labels(DialogRequest request) {
        return request.rows().stream().map(DialogRow::label).toList();
    }

    static List<String> fieldLabels(DialogRequest request) {
        return request.fields().stream().map(DialogField::label).toList();
    }

    static List<String> initials(DialogRequest request) {
        return request.fields().stream().map(DialogField::initial).toList();
    }

    /** The native requests a chokepoint asked, in turn, and what it returned. */
    record Scripted<T>(List<DialogRequest> requests, T result) { }

    /**
     * Runs a chokepoint on the EDT and answers the native requests it asks in turn with the given answers, as the
     * render thread would; it fails when the chokepoint asks fewer requests.
     */
    static <T> Scripted<T> script(GpuBoardSource source, Callable<T> chokepoint, DialogAnswer... answers)
          throws Exception {
        FutureTask<T> task = new FutureTask<>(chokepoint);
        SwingUtilities.invokeLater(task);
        List<DialogRequest> requests = new ArrayList<>();
        DialogRequest answered = null;
        for (DialogAnswer answer : answers) {
            DialogRequest request = next(source, answered, task);
            requests.add(request);
            source.answer(request.id(), answer);
            answered = request;
        }
        return new Scripted<>(requests, task.get(20, SECONDS));
    }

    /** EDT: the texts of the dialog's labels, text areas and toggles, in layout order. */
    static List<String> texts(JDialog dialog) {
        List<String> texts = new ArrayList<>();
        for (Component component : components(dialog.getContentPane(), Component.class)) {
            if (component instanceof JLabel label) {
                texts.add(label.getText());
            } else if (component instanceof JTextComponent text) {
                texts.add(text.getText());
            } else if ((component instanceof AbstractButton toggle) && !(component instanceof JButton)) {
                texts.add("[" + (toggle.isSelected() ? "x" : " ") + (toggle.isEnabled() ? "" : " disabled") + "] "
                      + toggle.getText());
            }
        }
        return texts;
    }

    /** EDT: the text of the dialog's pending changes area. */
    static String pending(JDialog dialog) {
        return components(dialog.getContentPane(), JTextArea.class).getFirst().getText();
    }

    /** EDT: the rows of the dialog's list. */
    static List<String> rows(JDialog dialog) {
        JList<?> list = components(dialog.getContentPane(), JList.class).getFirst();
        return IntStream.range(0, list.getModel().getSize()).mapToObj(row -> String.valueOf(
              list.getModel().getElementAt(row))).toList();
    }

    /**
     * The board fixture's game with its local player (id 0, "GPU review", team 1), an enemy (id 2, team 2) and an ally
     * (id 3, team 1); a client mock over it and the client's frame, which owns the dialogs and finds the client. Until
     * {@link #present()} no native window draws the client's dialogs.
     */
    static final class Declaring implements AutoCloseable {
        final GpuBoardFixture fixture;
        final ClientGUI gui = GpuDialogFixture.routingClient();
        final Client client = mock(Client.class);
        final Player enemy = new Player(2, "Enemy");
        final Player ally = new Player(3, "Ally");
        final JFrame frame;

        private Declaring(GpuBoardFixture fixture) throws Exception {
            this.fixture = fixture;
            frame = GpuDialogFixture.clientFrame(gui);
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(gui.getClient()).thenReturn(client);
            when(gui.getFrame()).thenReturn(frame);
            when(gui.getBoardState()).thenReturn(fixture.view);
            onSwing(() -> {
                enemy.setTeam(2);
                ally.setTeam(1);
                fixture.game.addPlayer(enemy.getId(), enemy);
                fixture.game.addPlayer(ally.getId(), ally);
                return null;
            });
        }

        /** Registers a presented native window of the client over the fixture's board source. */
        void present() throws Exception {
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
        }

        static Declaring create() throws Exception {
            GpuBoardFixture fixture = GpuBoardFixture.create();
            try {
                return new Declaring(fixture);
            } catch (Exception | Error failure) {
                fixture.close();
                throw failure;
            }
        }

        /** EDT-safe: a unit of the test resources with the given id and owner, deployed at the hex, in the game. */
        Entity unit(String file, int id, Player owner, Coords hex) throws Exception {
            Entity unit = GpuFiringFixture.unit(file, id, hex);
            return onSwing(() -> {
                unit.setOwner(owner);
                fixture.game.addEntity(unit, false);
                return unit;
            });
        }

        /** The Atlas (1) and an Archer (42) prone and shut down, a standing Hachiwara (43); vehicles may eject. */
        void abandonReady() throws Exception {
            Entity archer = unit("Archer ARC-2R.mtf", 42, fixture.player, new Coords(7, 3));
            unit("Hachiwara HCA-6P.mtf", 43, fixture.player, new Coords(3, 3));
            onSwing(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLES_CAN_EJECT)
                      .setValue(true);
                for (Entity mek : List.of(fixture.entity, archer)) {
                    mek.setProne(true);
                    mek.setShutDown(true);
                }
                return null;
            });
        }

        /**
         * A second game, the client's from now on, on a board of two buildings: the local player's charges of 30 at
         * (0, 0) and 12 at (2, 0), and an enemy charge in the first building.
         */
        List<DemolitionCharge> charges() throws Exception {
            Game game = new Game();
            onSwing(() -> {
                game.addPlayer(fixture.player.getId(), fixture.player);
                game.addPlayer(enemy.getId(), enemy);
                game.setBoard(BoardLoader.initializeBoard("""
                      size 3 1
                      hex 0101 0 "bldg_elev:2;building:2:8;bldg_cf:100" ""
                      hex 0201 0 "" ""
                      hex 0301 0 "bldg_elev:1;building:1:8;bldg_cf:40" ""
                      end"""));
                List<IBuilding> buildings = new ArrayList<>(game.getBoard().getBuildingsVector());
                buildings.get(0).addDemolitionCharge(fixture.player.getId(), 30, new Coords(0, 0));
                buildings.get(0).addDemolitionCharge(enemy.getId(), 99, new Coords(0, 0));
                buildings.get(1).addDemolitionCharge(fixture.player.getId(), 12, new Coords(2, 0));
                return null;
            });
            when(client.getGame()).thenReturn(game);
            List<DemolitionCharge> charges = new ArrayList<>();
            for (IBuilding building : game.getBoard().getBuildingsVector()) {
                building.getDemolitionCharges().stream()
                      .filter(charge -> charge.playerId == fixture.player.getId()).forEach(charges::add);
            }
            return charges;
        }

        /** Quirks on; Variable Range Targeting on the Atlas (1, Long) and an Archer (42, Long switching to Short). */
        void variableRange() throws Exception {
            Entity archer = unit("Archer ARC-2R.mtf", 42, fixture.player, new Coords(7, 3));
            onSwing(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS).setValue(true);
                for (Entity mek : List.of(fixture.entity, archer)) {
                    mek.getQuirks().getOption(OptionsConstants.QUIRK_POS_VAR_RNG_TARG).setValue(true);
                }
                archer.setPendingVariableRangeTargetingMode(VariableRangeTargetingMode.SHORT);
                return null;
            });
        }

        /** The Atlas (1, On) and an Archer (42, On, switching Off) mount a minesweeper; returns both sweepers. */
        List<MiscMounted> minesweepers() throws Exception {
            Entity archer = unit("Archer ARC-2R.mtf", 42, fixture.player, new Coords(7, 3));
            return onSwing(() -> {
                List<MiscMounted> sweepers = new ArrayList<>();
                for (Entity mek : List.of(fixture.entity, archer)) {
                    mek.addEquipment(EquipmentType.get("ISMineSweeper"), Entity.LOC_NONE);
                    sweepers.add(mek.getMisc().stream().filter(misc -> misc.getType()
                          .hasFlag(MiscType.F_MINESWEEPER)).findFirst().orElseThrow());
                }
                sweepers.get(1).setMode("Off");
                return sweepers;
            });
        }

        /** Nova CEWS on the Atlas (1), an Archer (42) and the ally's Hachiwara (43); nobody is linked yet. */
        void novaUnits() throws Exception {
            Entity archer = unit("Archer ARC-2R.mtf", 42, fixture.player, new Coords(7, 3));
            Entity allied = unit("Hachiwara HCA-6P.mtf", 43, ally, new Coords(3, 3));
            onSwing(() -> {
                for (Entity mek : List.of(fixture.entity, archer, allied)) {
                    mek.addEquipment(EquipmentType.get("NovaCEWS"), Entity.LOC_NONE);
                }
                return null;
            });
        }

        /** A medium building entity of the owner at (8, 8), with a crew of four. */
        AbstractBuildingEntity building(Player owner) throws Exception {
            return onSwing(() -> {
                BuildingEntity building = new BuildingEntity(BuildingType.MEDIUM, 1);
                building.setId(50);
                building.setChassis("Garrison");
                building.setOwner(owner);
                building.setGame(fixture.game);
                building.getInternalBuilding().setBuildingHeight(3);
                building.getInternalBuilding().addHex(new CubeCoords(0, 0, 0), 50, 10, BasementType.UNKNOWN, false);
                building.refreshLocations();
                building.refreshAdditionalLocations();
                building.setCrewCount(4);
                building.getCrew().setSize(4);
                building.getCrew().setCurrentSize(4);
                building.setPosition(new Coords(8, 8));
                fixture.game.addEntity(building, false);
                return building;
            });
        }

        /** A rifle platoon of 28 of the owner with the given id at the hex. */
        void platoon(int id, Player owner, Coords hex) throws Exception {
            onSwing(() -> {
                ConvInfantry infantry = new ConvInfantry();
                infantry.setId(id);
                infantry.setChassis("Rifle Platoon " + id);
                infantry.setOwner(owner);
                infantry.setGame(fixture.game);
                infantry.setSquadSize(28);
                infantry.setSquadCount(1);
                infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                infantry.setPosition(hex);
                infantry.setDeployed(true);
                fixture.game.addEntity(infantry, false);
                return null;
            });
        }

        @Override
        public void close() throws Exception {
            try {
                GpuDialogFixture.dismiss();
                onSwing(() -> {
                    frame.dispose();
                    return null;
                });
            } finally {
                fixture.close();
            }
        }
    }
}
