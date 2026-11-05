/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.AFTER_BACKSPACE;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.BOMBS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.DECLARED;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.UNSET;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.arm;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.declare;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.observe;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.render;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringCharacterizationTest.used;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.dialogs.phaseDisplay.TriggerBPodDialog;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.common.ToHitData;
import megamek.common.actions.ActivateBloodStalkerAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.TriggerBPodAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.AimingMode;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.units.Infantry;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The FiringDisplay queue operations the native fire orders use (stage E3a): replacing the queue keeps every action
 * object and what queuing it did, in any order; removing one attack releases only it; toHitFor gives the to-hit and
 * the refusal reason the Fire button acts on; Cancel in the ability and B-Pod target prompts declares nothing. The
 * literals are those GpuFiringCharacterizationTest recorded on the code before the extraction.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuFiringQueueTest {

    @Test
    void replacingTheQueueWithItsOwnActionsChangesNothing() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            List<String> observed = onSwing(() -> {
                declare(firing);
                List<EntityAction> queued = firing.display.getAttacks();
                firing.display.replaceAttacks(queued);
                assertSameActions(queued, firing.display.getAttacks());
                List<String> lines = new ArrayList<>(observe(firing));
                firing.undoLastStep();
                firing.display.replaceAttacks(firing.display.getAttacks());
                lines.addAll(observe(firing));
                firing.fire(weapon(firing.attacker, "Medium Laser", Mek.LOC_LEFT_ARM), firing.left);
                firing.display.replaceAttacks(firing.display.getAttacks());
                firing.display.ready();
                return lines;
            });
            assertEquals(DECLARED, observed.subList(0, DECLARED.size()));
            assertEquals(AFTER_BACKSPACE, observed.subList(DECLARED.size(), observed.size()));
            assertEquals(List.of("twist 1 facing 1", "mount 1 weapon 10 facing 3", "searchlight 1->0:42",
                        "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1" + UNSET,
                        "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
                        "WeaponAttackAction Medium Laser LA->0:43 ammo -1 [] carrier -1" + UNSET),
                  render(firing.board.game, firing.sent(firing.attacker.getId())));
        }
    }

    @Test
    void replacingTheQueueKeepsEachAttacksSettingsAndSendsThem() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            String expected = "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1 payload {external={},"
                  + " internal={RL=2}} other 7 aim 1 TARGETING_COMPUTER strafing true false";
            List<String> observed = onSwing(() -> {
                declare(firing);
                WeaponAttackAction cannon = (WeaponAttackAction) firing.display.getAttacks().get(3);
                BombLoadout payload = new BombLoadout();
                payload.put(BombTypeEnum.RL, 2);
                cannon.getBombPayloads().put("internal", payload);
                cannon.setOtherAttackInfo(7);
                cannon.setAimedLocation(Mek.LOC_CENTER_TORSO);
                cannon.setAimingMode(AimingMode.TARGETING_COMPUTER);
                cannon.setStrafing(true);
                cannon.setStrafingFirstShot(false);
                List<EntityAction> queued = firing.display.getAttacks();
                firing.display.replaceAttacks(queued);
                assertSameActions(queued, firing.display.getAttacks());
                List<String> lines = new ArrayList<>(render(firing.board.game, firing.display.getAttacks()));
                firing.display.ready();
                return lines;
            });
            assertEquals(expected, observed.get(3));
            assertEquals(expected, render(firing.board.game, firing.sent(firing.attacker.getId())).get(3),
                  "Done sends the settings the attack kept");
        }
    }

    @Test
    void replacingTheQueueInAnotherOrderReordersTheGamesAttacks() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            List<String> observed = onSwing(() -> {
                declare(firing);
                List<EntityAction> queued = firing.display.getAttacks();
                List<EntityAction> reordered = new ArrayList<>(queued.subList(0, 3));
                reordered.add(queued.get(5));
                reordered.add(queued.get(3));
                reordered.add(queued.get(4));
                firing.display.replaceAttacks(reordered);
                assertSameActions(reordered, firing.display.getAttacks());
                return observe(firing);
            });
            // The shot at the Hachiwara (impossible: no line of sight) now comes first, in the queue and the game
            assertEquals(List.of("queue [TorsoTwistAction, DirectionalMountFacingAction, Illuminates with searchlight."
                        + " Archer ARC-2R, Medium Laser; needs Impossible, 4  (using Left Side table), AC/20 [AC/20] ;"
                        + " needs 4  (using Left Side table)]",
                  "searchlight 1->0:42",
                  "WeaponAttackAction Medium Laser LA->0:43 ammo -1 [] carrier -1" + UNSET,
                  "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1" + UNSET,
                  "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
                  "used [Medium Laser LA, Medium Laser RA, AC/20 RT]",
                  "facing 1 mount 3",
                  "heat 13 13 (20)"), observed);
        }
    }

    @Test
    void removingAnAttackReleasesOnlyThatAttack() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            List<String> observed = onSwing(() -> {
                declare(firing);
                List<EntityAction> queued = firing.display.getAttacks();
                firing.display.removeAttack(queued.get(3));
                List<EntityAction> kept = new ArrayList<>(queued);
                kept.remove(3);
                assertSameActions(kept, firing.display.getAttacks());
                return observe(firing);
            });
            // The AC/20 is usable again and its 7 heat are gone; the twist, mount, searchlight and lasers stay
            assertEquals(List.of("queue [TorsoTwistAction, DirectionalMountFacingAction, Illuminates with searchlight."
                        + " Archer ARC-2R, Medium Laser; needs 4  (using Left Side table), Impossible]",
                  "searchlight 1->0:42",
                  "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
                  "WeaponAttackAction Medium Laser LA->0:43 ammo -1 [] carrier -1" + UNSET,
                  "used [Medium Laser LA, Medium Laser RA]",
                  "facing 1 mount 3",
                  "heat 6 6 (20)"), observed);
        }
    }

    @Test
    void toHitForIsTheToHitTheFireButtonActsOnWithTheRefusalReason() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity atlas = firing.attacker;
            WeaponMounted laser = weapon(atlas, "Medium Laser", Mek.LOC_RIGHT_ARM);
            WeaponMounted centerLaser = weapon(atlas, "Medium Laser", Mek.LOC_CENTER_TORSO);
            List<String> observed = onSwing(() -> {
                List<String> lines = new ArrayList<>();
                lines.add(text(firing.display.toHitFor(laser, firing.ahead)));
                lines.add(text(firing.display.toHitFor(laser, firing.left)));
                firing.fire(laser, firing.ahead);
                lines.add(text(firing.display.toHitFor(laser, firing.ahead)));
                firing.display.torsoTwist(0);
                lines.add(text(firing.display.toHitFor(centerLaser, firing.ahead)));
                atlas.setHidden(true);
                lines.add(text(firing.display.toHitFor(laser, firing.ahead)));
                return lines;
            });
            // As GpuFiringCharacterizationTest's weapon display shows: in range, the Hachiwara out of sight, once
            // fired, out of arc; and a hidden unit
            assertEquals(List.of("4 4 (gunnery skill)", "IMPOSSIBLE LOS blocked by terrain.",
                  "IMPOSSIBLE Already fired", "IMPOSSIBLE Target not in arc.",
                  "IMPOSSIBLE " + Messages.getString("FiringDisplay.HiddenUnitMaySpot")), observed);
        }
    }

    @Test
    void internalBombsAreUncountedAndCountedAgain() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity fighter = GpuFiringFixture.unit("Cheetah F-11.blk", 7, new Coords(5, 6));
            List<String> observed = onSwing(() -> {
                List<WeaponMounted> bombs = arm(firing, fighter);
                firing.display.selectEntity(fighter.getId());
                for (WeaponMounted bomb : bombs) {
                    firing.fire(bomb, firing.ahead);
                }
                firing.display.replaceAttacks(firing.display.getAttacks());
                List<String> lines = new ArrayList<>(render(firing.board.game, firing.board.game.getActionsVector()));
                lines.add("used " + used(fighter));
                firing.display.replaceAttacks(firing.display.getAttacks().subList(0, 1));
                lines.add("used " + used(fighter));
                firing.display.ready();
                lines.add("internal bombs used " + ((IBomber) fighter).getUsedInternalBombs());
                return lines;
            });
            assertEquals(BOMBS, observed.subList(0, 2), "Replaced by themselves, the bombs are as fired");
            assertEquals(List.of("used [Rocket Launcher Pod NOS, Rocket Launcher Pod NOS]",
                  "used [Rocket Launcher Pod NOS]", "internal bombs used 1"), observed.subList(2, observed.size()),
                  "Dropping one bomb from the queue makes it usable again and uncounts it");
            assertEquals(BOMBS.subList(0, 1), render(firing.board.game, firing.sent(fighter.getId())));
        }
    }

    @Test
    void cancellingTheAbilityPromptActivatesNothing() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity atlas = firing.attacker;
            ActionEvent activate = new ActionEvent(firing.display, ActionEvent.ACTION_PERFORMED,
                  FiringCommand.FIRE_ACTIVATE_SPA.getCmd());
            onSwing(() -> {
                atlas.getCrew().getOptions().getOption(OptionsConstants.GUNNERY_BLOOD_STALKER).setValue(true);
                firing.display.target(firing.ahead);
                return null;
            });
            present(firing.gui, firing.board.view, firing.board.source);
            try {
                Asked<List<EntityAction>> cancelled = ask(firing.board.source, () -> {
                    firing.display.actionPerformed(activate);
                    return firing.display.getAttacks();
                }, new DialogAnswer(1, List.of(), null, false, List.of()));
                assertEquals(DialogKind.CHOICE, cancelled.request().kind());
                assertEquals(List.of("Blood Stalker"), cancelled.request().rows().stream().map(DialogRow::label)
                      .toList());
                assertEquals(List.of(), cancelled.result(), "Cancel activates nothing");
                assertEquals(Entity.NONE, atlas.getBloodStalkerTarget());

                dismiss();
                List<EntityAction> swingCancelled = onSwing(() -> {
                    try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                        swing.when(() -> JOptionPane.showInputDialog(any(), any(), any(), anyInt(), isNull(), any(),
                              isNull())).thenReturn(null);
                        firing.display.actionPerformed(activate);
                        return firing.display.getAttacks();
                    }
                });
                assertEquals(List.of(), swingCancelled, "The Swing dialog's Cancel activates nothing either");

                present(firing.gui, firing.board.view, firing.board.source);
                Asked<List<EntityAction>> chosen = ask(firing.board.source, () -> {
                    firing.display.actionPerformed(activate);
                    return firing.display.getAttacks();
                }, new DialogAnswer(0, List.of(0), null, false, List.of()));
                assertEquals(1, chosen.result().size());
                ActivateBloodStalkerAction stalking = (ActivateBloodStalkerAction) chosen.result().getFirst();
                assertEquals(List.of(atlas.getId(), firing.ahead.getId(), firing.ahead.getId()),
                      List.of(stalking.getEntityId(), stalking.getTargetID(), atlas.getBloodStalkerTarget()));
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void cancellingTheBPodTargetChoiceTriggersNothing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds a Swing dialog");
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity atlas = firing.attacker;
            Entity first = GpuFiringFixture.unit("Elemental BA [Laser] (Sqd5).blk", 50, atlas.getPosition());
            Entity second = GpuFiringFixture.unit("Black Wolf BA (ER Pulse) (Sqd5).blk", 51, atlas.getPosition());
            JFrame frame = onSwing(JFrame::new);
            when(firing.gui.getFrame()).thenReturn(frame);
            TriggerBPodDialog dialog = onSwing(() -> {
                atlas.addEquipment(EquipmentType.get("ISBPod"), Mek.LOC_RIGHT_ARM);
                for (Entity trooper : List.of(first, second)) {
                    trooper.setOwner(firing.enemy);
                    firing.board.game.addEntity(trooper, false);
                }
                TriggerBPodDialog created = new TriggerBPodDialog(firing.gui, atlas, Infantry.SWARM_MEK);
                checkBoxes(created.getContentPane()).forEach(pod -> pod.setSelected(true));
                return created;
            });
            present(firing.gui, firing.board.view, firing.board.source);
            try {
                Asked<List<TriggerBPodAction>> cancelled = ask(firing.board.source,
                      () -> Collections.list(dialog.getActions()), new DialogAnswer(1, List.of(), null, false,
                            List.of()));
                assertEquals(DialogKind.CHOICE, cancelled.request().kind());
                assertEquals(2, cancelled.request().rows().size());
                assertEquals(List.of(), cancelled.result(), "Cancel triggers nothing");

                Asked<List<TriggerBPodAction>> chosen = ask(firing.board.source,
                      () -> Collections.list(dialog.getActions()), new DialogAnswer(0, List.of(1), null, false,
                            List.of()));
                assertEquals(1, chosen.result().size());
                assertEquals(List.of(atlas.getId(), second.getId()),
                      List.of(chosen.result().getFirst().getEntityId(), chosen.result().getFirst().getTargetId()));
            } finally {
                dismiss();
                onSwing(() -> {
                    dialog.dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    private static void assertSameActions(List<EntityAction> expected, List<EntityAction> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index), "The same action object at " + index);
        }
    }

    /** The to-hit value (IMPOSSIBLE by name) and its description. */
    private static String text(ToHitData toHit) {
        String value = (toHit.getValue() == TargetRoll.IMPOSSIBLE) ? "IMPOSSIBLE" : String.valueOf(toHit.getValue());
        return value + " " + toHit.getDesc();
    }

    private static Stream<JCheckBox> checkBoxes(Container container) {
        return Stream.of(container.getComponents()).flatMap(component -> (component instanceof JCheckBox box)
              ? Stream.of(box) : (component instanceof Container inner) ? checkBoxes(inner) : Stream.empty());
    }
}
