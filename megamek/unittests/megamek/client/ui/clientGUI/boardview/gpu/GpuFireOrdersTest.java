/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.awaitDialog;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import javax.swing.JPanel;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.phaseDisplay.AimedShotDialog;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.actions.compute.ComputeToHit;
import megamek.common.board.Coords;
import megamek.common.enums.AimingMode;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.EquipmentTypeLookup;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The native fire orders (stage E3b) over a real FiringDisplay on the local player's firing turn (GpuFiringFixture):
 * the snapshot shows the display's queue and the rules' rolls, and every command changes the queue through the
 * display, keeping its action objects. The Atlas AS7-D at (5, 5) faces north; the Archer ARC-2R ahead (7, 3) and a
 * Crab CRB-20 (5, 2) are in its front arc, a Quickdraw QKD-8X (8, 5) is beside it, and the Hachiwara HCA-6P (3, 3) is
 * out of sight.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuFireOrdersTest {

    @Test
    void theRowsShowTheRulesRollsOfTheScenario() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int missiles = GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO);
            GpuFireOrders.Snapshot focused = GpuFiringFixture.command(firing,
                  fire -> fire.focusTarget(TargetKey.unit(firing.ahead.getId())));
            GpuFireOrders.Snapshot queued = GpuFiringFixture.command(firing,
                  fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFireOrders.Snapshot hidden = GpuFiringFixture.command(firing,
                  fire -> fire.focusTarget(TargetKey.unit(firing.left.getId())));
            assertEquals(List.of(
                  // The Medium Laser's literal WeaponAttackAction.toHit at the Archer (GpuFiringCharacterizationTest)
                  "Medium Laser RA Energy 5 dmg 3 heat mode '' ammo [] loaded -1 shots -1 -> 42 4 91.6 '' usable",
                  "AC/20 RT Ballistic 20 dmg 7 heat mode '' ammo [1/16:[RT] AC/20  (5), 1/17:[RT] AC/20  (5)] loaded 0"
                        + " shots 5 -> 42 4 91.6 '' usable",
                  "Medium Laser LA Energy 5 dmg 3 heat mode '' ammo [] loaded -1 shots -1 -> 43 IMPOSSIBLE 0.0 'LOS"
                        + " blocked by terrain.' usable",
                  "LRM 20 LT Missile 1\u00D720 dmg 6 heat mode '' ammo [1/12:[LT] LRM 20  (6), 1/13:[LT] LRM 20  (6)]"
                        + " loaded 0 shots 6 -> 43 IMPOSSIBLE 0.0 'LOS blocked by terrain.' usable"),
                  List.of(row(focused, right), row(queued, cannon), row(hidden, left), row(hidden, missiles)));
            assertEquals(List.of("15 AC/20 RT Ballistic -> 42 ammo '[RT] AC/20  (5)' 5 4 91.6 4 (gunnery skill)"),
                  queued.attacks().stream().map(GpuFireOrdersTest::attack).toList());
            assertEquals("0 -> 7 sinks 20 end 0 '' ticks [5, 8, 10, 13, 14, 15, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27,"
                  + " 28, 29, 30] scale 31", heat(queued.heat()));
            // The selected Medium Laser (LA) at the focused Archer: no minimum range, the left arm arc from north
            assertEquals("6 -> 42 ranges [0, 3, 6, 9] 'Left arm arc' distance 3 [gunnery skill 4] 4 91.6 '' wedge"
                  + " (5, 5) facing 0 from 240 to 60", solution(queued.solution()));
            assertEquals(List.of(focused.selectedWeapon(), firing.ahead.getId(), firing.left.getId()),
                  List.of(queued.selectedWeapon(), queued.focus().key().id(), hidden.focus().key().id()),
                  "The weapon the Unit Display selected stays selected; the focus is the display's target");
        }
    }

    @Test
    void badgesAndTheHoverRollShowTheOtherEnemies() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            GpuFiringFixture.enemy(firing, "Quickdraw QKD-8X.mtf", 45, GpuFiringFixture.EAST);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.focusTarget(TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.selectWeapon(right));
            GpuFireOrders.Snapshot badged = afterBadgeJob(firing, crab.getPosition());
            assertEquals(List.of("[44 4 91.6 '', 45 4 91.6 '', 43 IMPOSSIBLE 0.0 'LOS blocked by terrain.']",
                  "Badge[targetId=44, value=4, odds=91.6, reason=]"), List.of(badges(badged),
                  String.valueOf(badged.hoverBest())), "The Medium Laser (RA) on each enemy but the focused Archer,"
                  + " best first; the best weapon on the hovered Crab");
            // Both in one EDT event: the source's 100 ms timer captures without the hovered hex in between
            List<GpuFireOrders.Snapshot> again = onSwing(() -> List.of(
                  firing.board.source.fire().capture(firing.display, crab.getPosition()),
                  firing.board.source.fire().capture(firing.display, crab.getPosition())));
            assertEquals(badged, again.getFirst());
            assertSame(again.getFirst(), again.getLast(), "Nothing changed: the same snapshot, and no new badge job");
            GpuFireOrders.Snapshot refocused = GpuFiringFixture.command(firing, fire -> fire.focusTarget(TargetKey.unit(crab.getId())));
            assertEquals("[45 4 91.6 '', 43 IMPOSSIBLE 0.0 'LOS blocked by terrain.']", badges(refocused),
                  "Before its job ran, the focused Crab already has no badge");
            GpuFireOrders.Snapshot rebadged = afterBadgeJob(firing, crab.getPosition());
            assertEquals("[42 4 91.6 '', 45 4 91.6 '', 43 IMPOSSIBLE 0.0 'LOS blocked by terrain.']",
                  badges(rebadged));
            assertNull(rebadged.hoverBest(), "A unit with a card has no hover roll");
            GpuFireOrders.Snapshot none = GpuFiringFixture.command(firing, fire -> fire.selectWeapon(-1));
            GpuFireOrders.FrontArc arc = none.frontArc();
            assertEquals(List.of(-1, 0, 0, 64, true, false), List.of(none.selectedWeapon(), none.badges().size(),
                  arc.facing(), arc.hexes().size(), arc.hexes().contains(GpuFiringFixture.NORTH), arc.hexes().contains(GpuFiringFixture.EAST)),
                  "Without a weapon: no badges, and the front arc on the board holds the Crab ahead, not the Quickdraw"
                        + " beside");
        }
    }

    @Test
    void lettersStayWhenThePrimaryChanges() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFireOrders.Snapshot before = GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            GpuFireOrders.Snapshot after = GpuFiringFixture.command(firing, fire -> fire.setPrimary(TargetKey.unit(crab.getId())));
            assertEquals(List.of("A 42 primary front", "B 44 secondary +1 front"), GpuFiringFixture.targets(before));
            assertEquals(List.of("A 42 secondary +1 front", "B 44 primary front"), GpuFiringFixture.targets(after),
                  "The letters stay; the rules now make the Crab primary");
            assertEquals(List.of("Medium Laser LA@44", "AC/20 RT@42"), GpuFiringFixture.queue(firing), "Its attacks fire first");
            verify(firing.gui).addToast(ToastLevel.INFO, Messages.getString("GpuBoard.hud.fire.primary",
                  "Crab CRB-20", "+1", "+1"));
        }
    }

    @Test
    void aFrontArcTargetStaysPrimary() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity quickdraw = GpuFiringFixture.enemy(firing, "Quickdraw QKD-8X.mtf", 45, GpuFiringFixture.EAST);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(right, TargetKey.unit(quickdraw.getId())));
            List<EntityAction> queued = onSwing(firing.display::getAttacks);
            GpuFireOrders.Snapshot refused = GpuFiringFixture.command(firing,
                  fire -> fire.setPrimary(TargetKey.unit(quickdraw.getId())));
            assertEquals(List.of("A 42 primary front", "B 45 secondary +1"), GpuFiringFixture.targets(refused));
            GpuFiringFixture.assertSameActions(queued, onSwing(firing.display::getAttacks));
            verify(firing.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.fire.cannotBePrimary",
                  "Quickdraw QKD-8X", "Archer ARC-2R"));
            GpuFiringFixture.command(firing, fire -> fire.setPrimary(TargetKey.unit(firing.left.getId())));
            verify(firing.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.fire.assignFirst"));
        }
    }

    @Test
    void retargetKeepsTheAttacksPlaceInTheFireOrder() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(right, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@44"), GpuFiringFixture.queue(firing));
            GpuFireOrders.Snapshot moved = GpuFiringFixture.command(firing, fire -> fire.retarget(cannon, TargetKey.unit(crab.getId())));
            assertEquals(List.of("Medium Laser RA@42", "AC/20 RT@44", "Medium Laser LA@44"), GpuFiringFixture.queue(firing),
                  "The cannon joins the Crab's attacks ahead of the laser it was declared before");
            assertEquals(List.of("A 42 primary front", "B 44 secondary +1 front"), GpuFiringFixture.targets(moved));
            assertEquals("used: AC/20 RT, Medium Laser LA, Medium Laser RA", GpuFiringFixture.used(firing),
                  "Only the queued weapons count as fired");
        }
    }

    @Test
    void moveSwapsOnlyWithinATarget() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(right, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            List<EntityAction> queued = onSwing(firing.display::getAttacks);
            GpuFireOrders.Snapshot earlier = GpuFiringFixture.command(firing, fire -> fire.move(right, -1));
            assertEquals(List.of("Medium Laser RA@42", "AC/20 RT@42", "Medium Laser LA@44"), GpuFiringFixture.queue(firing));
            assertEquals(List.of(right, cannon, left), earlier.attacks().stream()
                  .map(GpuFireOrders.Attack::eqNum).toList(), "The snapshot lists the attacks in fire order");
            GpuFiringFixture.command(firing, fire -> fire.move(left, -1));
            GpuFiringFixture.command(firing, fire -> fire.move(left, 1));
            assertEquals(List.of("Medium Laser RA@42", "AC/20 RT@42", "Medium Laser LA@44"), GpuFiringFixture.queue(firing),
                  "An attack never moves past another target's attack, nor past the end");
            List<EntityAction> reordered = onSwing(firing.display::getAttacks);
            assertSame(queued.get(1), reordered.get(0), "The display keeps its own action objects");
            assertSame(queued.get(0), reordered.get(1));
        }
    }

    /**
     * The user's report of 2026-10-03: a terrain target kept "Hold fire". The display's target is the one focus, a
     * wooded hex as much as a unit: MegaMek's board click chooses it, the armed weapon fires at it, the rows roll at
     * it and a "+" assigns another weapon there. Its attacks count, which the dock's Fire weapons follows, and take a
     * letter, the primary's here, with a unit's beside it.
     */
    @Test
    void aWoodedHexIsTargetedAndAssignedAsAUnitIs() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            // The board's light woods north-east of the Atlas, beside the Archer.
            Coords woods = new Coords(7, 2);
            int laser = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            // Nothing armed: the click only targets the woods, to clear them.
            GpuFireOrders.Snapshot focused = GpuFiringFixture.command(firing, fire -> fire.clickHex(woods, 0, -1));
            Targetable chosen = onSwing(firing.display::getTarget);
            TargetKey hex = TargetKey.of(chosen);
            assertEquals(List.of(Targetable.TYPE_HEX_CLEAR, woods), List.of(chosen.getTargetType(),
                  chosen.getPosition()));
            assertEquals(new GpuFireOrders.Focus(hex, chosen.getDisplayName(), woods), focused.focus());
            assertEquals(List.of(), focused.attacks());
            assertEquals(hex, weaponRow(focused, laser).target(), "The rows roll at the hex");

            GpuFiringFixture.command(firing, fire -> fire.clickHex(woods, 0, laser));
            GpuFireOrders.Snapshot assigned = GpuFiringFixture.command(firing, fire -> fire.assign(cannon, hex));
            assertEquals(List.of("Medium Laser RA@" + hex.id(), "AC/20 RT@" + hex.id()), GpuFiringFixture.queue(firing));
            assertEquals(List.of(hex, hex), assigned.attacks().stream().map(GpuFireOrders.Attack::target).toList());
            assertEquals(List.of("A " + hex.id() + " primary front"), GpuFiringFixture.targets(assigned));
            assertEquals(woods, assigned.targets().getFirst().hex(), "The board marks the hex");

            GpuFireOrders.Snapshot both = GpuFiringFixture.command(firing,
                  fire -> fire.assign(left, TargetKey.unit(firing.ahead.getId())));
            assertEquals(List.of("A " + hex.id() + " primary front", "B 42 secondary +1 front"), GpuFiringFixture.targets(both));
        }
    }

    /** A row dropped two places away (H29): the attack takes that place and the others keep their order. */
    @Test
    void moveTakesAnAttackSeveralPlacesInOneCommand() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            for (int weapon : List.of(cannon, right, left)) {
                GpuFiringFixture.command(firing, fire -> fire.assign(weapon, TargetKey.unit(firing.ahead.getId())));
            }
            assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@42"), GpuFiringFixture.queue(firing));
            GpuFiringFixture.command(firing, fire -> fire.move(left, -2));
            assertEquals(List.of("Medium Laser LA@42", "AC/20 RT@42", "Medium Laser RA@42"), GpuFiringFixture.queue(firing),
                  "The left arm's laser fires first, the others in their order (no swap with the cannon)");
            GpuFiringFixture.command(firing, fire -> fire.move(left, 3));
            assertEquals(List.of("Medium Laser LA@42", "AC/20 RT@42", "Medium Laser RA@42"), GpuFiringFixture.queue(firing),
                  "Nothing moves past the last attack");
            GpuFiringFixture.command(firing, fire -> fire.move(left, 2));
            assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@42"), GpuFiringFixture.queue(firing));
        }
    }

    /**
     * A queued attack on a unit that has left the game (destroyed and removed) has no roll: MegaMek logs an error for a
     * to-hit without a target (seen in a live game, P1), so the orders leave the attack out, as a draft's.
     */
    @Test
    void aQueuedAttackOnAUnitThatLeftTheGameHasNoRoll() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            List<Integer> untargeted = new ArrayList<>();
            GpuFireOrders.Snapshot after = onSwing(() -> {
                firing.board.game.removeEntity(firing.ahead.getId(), IEntityRemovalConditions.REMOVE_SALVAGEABLE);
                // The rules' to-hit, recorded when it is asked without a target (static mocks are per thread).
                try (MockedStatic<ComputeToHit> rolls = mockStatic(ComputeToHit.class, invocation -> {
                    if (invocation.getMethod().getName().equals("toHitCalc") && (invocation.getArgument(2) == null)) {
                        untargeted.add(invocation.getArgument(1));
                    }
                    return invocation.callRealMethod();
                })) {
                    return firing.board.source.fire().capture(firing.display, null);
                }
            });
            assertEquals(List.of(), untargeted, "No roll is asked without a target");
            assertEquals(List.of(TargetKey.unit(crab.getId())), after.attacks().stream()
                  .map(GpuFireOrders.Attack::target).toList(),
                  "Only the attack on the Crab, which is still in the game, is listed");
        }
    }

    @Test
    void setAmmoDeclaresTheAttackAgainWithTheNewAmmunitionInItsPlace() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            WeaponMounted atm = onSwing(() -> {
                Mek atlas = (Mek) firing.attacker;
                WeaponMounted launcher = (WeaponMounted) atlas.addEquipment(EquipmentType.get("CLATM6"),
                      Mek.LOC_LEFT_ARM);
                atlas.addEquipment(EquipmentType.get("CLATM6 Ammo"), Mek.LOC_LEFT_ARM);
                atlas.addEquipment(EquipmentType.get("CLATM6 ER Ammo"), Mek.LOC_LEFT_ARM);
                atlas.loadAllWeapons();
                firing.display.selectEntity(atlas.getId());
                firing.board.source.refresh();
                return launcher;
            });
            int launcher = firing.attacker.getEquipmentNum(atm);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(launcher, TargetKey.unit(crab.getId())));
            GpuFireOrders.Snapshot standard = GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(crab.getId())));
            GpuFireOrders.WeaponRow before = weaponRow(standard, launcher);
            int extended = before.ammo().get(1).eqNum();
            GpuFireOrders.Snapshot changed = GpuFiringFixture.command(firing, fire -> fire.setAmmo(launcher, before.ammo().get(1)));
            assertEquals(List.of("ATM 6 LA Missile 2\u00D76 dmg 4 heat mode '' ammo [1/34:[LA] Standard ATM/6  (10),"
                        + " 1/35:[LA] Extended-Range ATM/6  (10)] loaded 0 shots 10 -> 44 7 58.3 '' usable",
                  "ATM 6 LA Missile 1\u00D76 dmg 4 heat mode '' ammo [1/34:[LA] Standard ATM/6  (10),"
                        + " 1/35:[LA] Extended-Range ATM/6  (10)] loaded 1 shots 10 -> 44 7 58.3 '' usable"),
                  List.of(row(standard, launcher), row(changed, launcher)));
            assertEquals(List.of("AC/20 RT@42", "ATM 6 LA@44", "Medium Laser LA@44"), GpuFiringFixture.queue(firing),
                  "The launcher's attack keeps its place");
            AmmoMounted loaded = onSwing(atm::getLinkedAmmo);
            WeaponAttackAction attack = (WeaponAttackAction) onSwing(firing.display::getAttacks).get(1);
            assertEquals(List.of(extended, extended), List.of(firing.attacker.getEquipmentNum(loaded),
                  attack.getAmmoId()), "The weapon loads the bin and its attack fires it");
            verify(firing.gui).addToast(ToastLevel.INFO, Messages.getString("GpuBoard.hud.fire.ammoChanged",
                  "ATM 6", "[LA] Extended-Range ATM/6  (10)"));
        }
    }

    /**
     * U4 / H30: a tractor's weapon may load the bins of the trailer it tows, which the trailer numbers as the tractor
     * numbers its own (two Bulldogs): a pick loads the bin of its carrier, and the server hears that carrier.
     */
    @Test
    void aBinIsChosenByItsCarrierAndItsNumberThere() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity tractor = GpuFiringFixture.unit("Bulldog Medium Tank.blk", 50, new Coords(8, 8));
            Entity trailer = GpuFiringFixture.unit("Bulldog Medium Tank.blk", 51, new Coords(8, 9));
            GpuFireOrders.Snapshot towing = onSwing(() -> {
                for (Entity unit : List.of(tractor, trailer)) {
                    Tank tank = (Tank) unit;
                    tank.setTrailer(unit == trailer);
                    tank.addEquipment(EquipmentType.get(EquipmentTypeLookup.HITCH), Tank.LOC_BODY);
                    tank.setTrailerHitches();
                    unit.setOwner(firing.board.player);
                    firing.board.game.addEntity(unit, false);
                }
                tractor.towUnit(trailer.getId());
                firing.display.selectEntity(tractor.getId());
                return firing.board.source.fire().capture(firing.display, null);
            });
            WeaponMounted launcher = tractor.getWeaponList().stream()
                  .filter(weapon -> weapon.getName().equals("SRM 4")).findFirst().orElseThrow();
            int srm = tractor.getEquipmentNum(launcher);
            List<GpuUnitRecord.AmmoChoice> bins = weaponRow(towing, srm).ammo();
            GpuUnitRecord.AmmoChoice own = bins.stream().filter(bin -> bin.carrierId() == tractor.getId())
                  .findFirst().orElseThrow();
            GpuUnitRecord.AmmoChoice towed = bins.stream().filter(bin -> bin.carrierId() == trailer.getId())
                  .findFirst().orElseThrow();
            assertEquals(own.eqNum(), towed.eqNum(), "The trailer numbers its bin as the tractor numbers its own");
            assertEquals(bins.indexOf(own), weaponRow(towing, srm).loadedAmmo());

            GpuFireOrders.Snapshot loaded = GpuFiringFixture.command(firing, fire -> fire.setAmmo(srm, towed));
            assertSame(trailer, onSwing(() -> launcher.getLinkedAmmo().getEntity()), "The trailer's bin feeds it");
            verify(firing.client).sendAmmoChange(tractor.getId(), srm, towed.eqNum(), trailer.getId(), 0);
            assertEquals(bins.indexOf(towed), weaponRow(loaded, srm).loadedAmmo());
            GpuFiringFixture.command(firing, fire -> fire.setAmmo(srm, own));
            assertSame(tractor, onSwing(() -> launcher.getLinkedAmmo().getEntity()), "and its own bin again");
        }
    }

    /**
     * R4, H36: while the native window draws the client's dialogs, the aimed shot handler offers its dialog's choices
     * through the orders and opens no Swing dialog. An immobile Mek offers every location, aimed at the head as the
     * dialog preselects it; an immobile tank seen from behind leaves out its body and front (the handler's mask). The
     * native choice aims the declared shot, a location the mask leaves out is refused, and "Don't aim" clears it.
     */
    @Test
    void theAimedShotChoicesAreTheHandlersOfferInPlaceOfItsDialog() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity tank = GpuFiringFixture.enemy(firing, "Bulldog Medium Tank.blk", 46, GpuFiringFixture.NORTH);
            onSwing(() -> {
                firing.ahead.setShutDown(true);
                tank.setShutDown(true);
                firing.board.source.refresh();
                return null;
            });
            present(firing.gui, firing.board.view, firing.board.source);
            try {
                int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
                int missiles = GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO);
                GpuFireOrders.Aim archer = GpuFiringFixture.command(firing,
                      fire -> fire.focusTarget(TargetKey.unit(firing.ahead.getId()))).aim();
                assertEquals(List.of("Head", "Center Torso", "Right Torso", "Left Torso", "Right Arm", "Left Arm",
                      "Right Leg", "Left Leg"), archer.locations());
                assertEquals(Collections.nCopies(8, true), archer.enabled(), "An immobile Mek offers every location");
                assertEquals(Mek.LOC_HEAD, archer.location());
                assertEquals(List.of(true, false), List.of(archer.weapons().contains(cannon),
                      archer.weapons().contains(missiles)), "Missiles cannot aim at an immobile unit");
                assertEquals(List.of(), onSwing(() -> Arrays.stream(Window.getWindows())
                      .filter(AimedShotDialog.class::isInstance).toList()), "No Swing aimed shot dialog");

                assertEquals(Mek.LOC_LEFT_ARM, GpuFiringFixture.command(firing, fire -> fire.aim(cannon, Mek.LOC_LEFT_ARM)).aim()
                      .location());
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
                WeaponAttackAction attack = onSwing(() -> (WeaponAttackAction) firing.display.getAttacks().getFirst());
                assertEquals(List.of(Mek.LOC_LEFT_ARM, AimingMode.IMMOBILE), List.of(attack.getAimedLocation(),
                      attack.getAimingMode()), "The shot is declared aimed at the chosen location");
                assertEquals(Entity.LOC_NONE, GpuFiringFixture.command(firing, fire -> fire.aim(cannon, Entity.LOC_NONE)).aim()
                      .location(), "Don't aim");

                GpuFireOrders.Aim bulldog = GpuFiringFixture.command(firing,
                      fire -> fire.focusTarget(TargetKey.unit(tank.getId()))).aim();
                assertEquals(List.of("Body", "Front", "Right", "Left", "Rear", "Turret"), bulldog.locations());
                assertEquals(List.of(false, false, true, true, true, true), bulldog.enabled());
                assertEquals(Tank.LOC_REAR, bulldog.location());
                assertEquals(Tank.LOC_REAR, GpuFiringFixture.command(firing, fire -> fire.aim(cannon, Tank.LOC_FRONT)).aim()
                      .location(), "The front, which the attacker cannot see, is refused");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void autoEndFiringIsHonouredOnceAfterAPlus() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity atlas = firing.attacker;
            onSwing(() -> {
                // Swing's Fire declares the rear lasers too (they cannot hit the Archer ahead)
                for (WeaponMounted weapon : atlas.getWeaponList()) {
                    if (weapon.isRearMounted()) {
                        firing.fire(weapon, firing.ahead);
                    }
                }
                firing.board.source.refresh();
                return null;
            });
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int[] others = { GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO), GpuFiringFixture.eqNum(firing, "SRM 6", Mek.LOC_LEFT_TORSO),
                             GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM) };
            for (int weapon : others) {
                GpuFiringFixture.command(firing, fire -> fire.assign(weapon, TargetKey.unit(firing.ahead.getId())));
            }
            onSwing(() -> {
                GUIPreferences.getInstance().setAutoEndFiring(true);
                return null;
            });
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(right, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.move(right, -1));
            GpuFiringFixture.command(firing, fire -> fire.setPrimary(TargetKey.unit(firing.ahead.getId())));
            verify(firing.client, never()).sendAttackData(anyInt(), any());
            GpuFireOrders.Snapshot ended = GpuFiringFixture.command(firing,
                  fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            verify(firing.client, times(1)).sendAttackData(anyInt(), any());
            assertNull(onSwing(firing.display::currentEntity), "The last weapon ended the turn");
            assertSame(GpuFireOrders.Snapshot.EMPTY, ended);
            assertEquals(List.of("Medium Laser CT@42", "Medium Laser CT@42", "LRM 20 LT@42", "SRM 6 LT@42",
                  "Medium Laser RA@42", "Medium Laser LA@42", "AC/20 RT@42"), GpuFiringFixture.sent(firing, atlas.getId()),
                  "Sent once, in the order the native commands left; the last shot where Fire queued it");
        }
    }

    @Test
    void aSensorContactIsNotTargetable() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            onSwing(() -> {
                Game game = firing.board.game;
                game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                firing.left.setWhoCanSee(new Vector<>());
                firing.left.addBeenDetectedBy(firing.board.player);
                firing.board.source.refresh();
                return null;
            });
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            GpuFireOrders.Snapshot focused = GpuFiringFixture.command(firing,
                  fire -> fire.focusTarget(TargetKey.unit(firing.left.getId())));
            GpuFireOrders.Snapshot assigned = GpuFiringFixture.command(firing,
                  fire -> fire.assign(right, TargetKey.unit(firing.left.getId())));
            assertEquals(List.of(GpuFireOrders.Focus.NONE, GpuFireOrders.Focus.NONE), List.of(focused.focus(),
                  assigned.focus()));
            assertEquals(List.of(), GpuFiringFixture.queue(firing));
            verify(firing.gui, times(2)).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.fire.contactNotTargetable"));
        }
    }

    @Test
    void aRefusalToastCarriesToHitForsReason() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuFireOrders.Snapshot refused = GpuFiringFixture.command(firing,
                  fire -> fire.assign(left, TargetKey.unit(firing.left.getId())));
            assertEquals(List.of(), GpuFiringFixture.queue(firing));
            assertEquals(List.of(), refused.attacks());
            // toHitFor's reason, as GpuFiringQueueTest recorded it for this shot
            verify(firing.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.fire.refused",
                  "Medium Laser", "Hachiwara HCA-6P", "LOS blocked by terrain."));
        }
    }

    @Test
    void fireDialogsGoThroughTheNativeModalBridge() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity quickdraw = GpuFiringFixture.enemy(firing, "Quickdraw QKD-8X.mtf", 45, GpuFiringFixture.EAST);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuFiringFixture.command(firing, fire -> fire.assign(right, TargetKey.unit(quickdraw.getId())));
            onSwing(() -> {
                // On the EDT: Mockito stubs the next call on the mock from any thread, and the source's jobs call it
                doCallRealMethod().when(firing.gui).doYesNoDialog(anyString(), anyString());
                return null;
            });
            present(firing.gui, firing.board.view, firing.board.source);
            try {
                // A shot at a front arc target after one beside the unit: Fire asks whether the to-hits may change
                firing.board.source.fire().assign(cannon, TargetKey.unit(firing.ahead.getId()));
                DialogRequest asked = awaitDialog(firing.board.source);
                assertEquals(Messages.getString("FiringDisplay.SecondaryTargetToHitChange.message"),
                      asked.message());
                firing.board.source.fire().remove(right);
                firing.board.source.answer(asked.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
                GpuFireOrders.Snapshot answered = onSwing(() -> firing.board.source.fire()
                      .capture(firing.display, null));
                assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@45"), GpuFiringFixture.queue(firing),
                      "The removal posted during the dialog was dropped; the new primary's attack fires first");
                assertEquals(List.of("A 45 secondary +1", "B 42 primary front"), GpuFiringFixture.targets(answered));
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void closingTheSourceRemovesTheListenerTheOrdersAddedWhileFiring() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            List<Integer> counts = onSwing(() -> {
                Game game = firing.board.game;
                int before = game.getGameListeners().size();
                GpuBoardSource idle = new GpuBoardSource(firing.board.view, JPanel::new);
                int idleSource = game.getGameListeners().size() - before;
                idle.close();
                // A source's first capture runs in its constructor; here the orders capture the firing actor
                GpuBoardSource source = new GpuBoardSource(firing.board.view, () -> firing.display);
                int firingSource = game.getGameListeners().size() - before;
                source.fire().capture(firing.display, null);
                int recaptured = game.getGameListeners().size() - before;
                source.close();
                return List.of(firingSource - idleSource, recaptured - idleSource,
                      game.getGameListeners().size() - before);
            });
            assertEquals(List.of(1, 1, 0), counts, "Capturing a firing actor listens to the game once, later"
                  + " captures add nothing, and closing the source removes it with the source's own listeners");
        }
    }

    @Test
    void aHandheldWeaponIsReleasedOnItsOwnUnit() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity handheld = GpuFiringFixture.unit("TestHandheldWeapon.blk", 70, null);
            WeaponMounted gun = onSwing(() -> {
                handheld.setOwner(firing.board.player);
                handheld.setDeployed(true);
                firing.board.game.addEntity(handheld, false);
                handheld.setTransportId(firing.attacker.getId());
                // Carried since an earlier turn: picking it up now would bar firing this turn
                firing.attacker.getCarriedObjects().put(Mek.LOC_RIGHT_ARM, handheld);
                firing.display.selectEntity(firing.attacker.getId());
                return handheld.getWeaponList().getFirst();
            });
            WeaponMounted rear = firing.attacker.getWeaponList().stream().filter(WeaponMounted::isRearMounted)
                  .findFirst().orElseThrow();
            List<String> observed = new ArrayList<>();
            onSwing(() -> {
                // Carrying blocks the arm and front torso weapons; Fire queues a shot without checking its roll
                firing.fire(gun, firing.ahead);
                firing.fire(rear, firing.ahead);
                firing.board.source.refresh();
                return null;
            });
            observed.add(handheld(firing, gun));
            // The native reorder requeues both attacks through the display's release step
            GpuFiringFixture.command(firing, fire -> fire.move(firing.attacker.getEquipmentNum(rear), -1));
            observed.add(handheld(firing, gun));
            onSwing(() -> {
                firing.undoLastStep();
                return null;
            });
            observed.add(handheld(firing, gun));
            assertEquals(List.of("[Ultra AC/2 GUN@42, Medium Laser CT@42] handheld fired true game actions 2",
                  "[Medium Laser CT@42, Ultra AC/2 GUN@42] handheld fired true game actions 2",
                  "[Medium Laser CT@42] handheld fired false game actions 1"), observed,
                  "The handheld weapon's own weapon is released, and its attack leaves the game once");
        }
    }

    /** The orders once the badge job a capture posted ran (it republishes in its own event), hovering the hex. */
    private static GpuFireOrders.Snapshot afterBadgeJob(GpuFiringFixture firing, Coords hover) throws Exception {
        onSwing(() -> null);
        return onSwing(() -> firing.board.source.fire().capture(firing.display, hover));
    }

    /** The queue, whether the handheld weapon counts as fired, and how many attacks the game holds. */
    private static String handheld(GpuFiringFixture firing, WeaponMounted gun) throws Exception {
        List<String> queue = GpuFiringFixture.queue(firing);
        return onSwing(() -> queue + " handheld fired " + gun.isUsedThisRound() + " game actions "
              + firing.board.game.getActionsVector().size());
    }

    private static GpuFireOrders.WeaponRow weaponRow(GpuFireOrders.Snapshot fire, int eqNum) {
        return fire.weapons().stream().filter(row -> row.eqNum() == eqNum).findFirst().orElseThrow();
    }

    private static String row(GpuFireOrders.Snapshot fire, int eqNum) {
        GpuFireOrders.WeaponRow row = weaponRow(fire, eqNum);
        return row.name() + " " + row.location() + " " + row.kind() + " " + row.damage() + " dmg " + row.heat()
              + " heat mode '" + row.mode() + "' ammo " + row.ammo().stream().map(ammo -> ammo.carrierId() + "/"
              + ammo.eqNum() + ":" + ammo.label()).toList() + " loaded " + row.loadedAmmo() + " shots " + row.shots()
              + " -> " + row.target().id() + " " + value(row.value()) + " " + row.odds() + " '" + row.reason()
              + "'" + (row.usable() ? " usable" : "") + (row.locationDestroyed() ? " dead" : "");
    }

    private static String attack(GpuFireOrders.Attack attack) {
        return attack.eqNum() + " " + attack.weapon() + " " + attack.location() + " " + attack.kind() + " -> "
              + attack.target().id() + " ammo '" + attack.ammo() + "' " + attack.shots() + " " + value(attack.value())
              + " " + attack.odds() + " " + attack.detail();
    }

    private static String heat(GpuFireOrders.Heat heat) {
        return (heat == null) ? "none" : heat.current() + " -> " + heat.after() + " sinks " + heat.sinks() + " end "
              + heat.end() + " '" + heat.effectsAtEnd() + "' ticks " + heat.ticks() + " scale " + heat.scale();
    }

    private static String solution(GpuFireOrders.Solution solution) {
        return (solution == null) ? "none" : solution.eqNum() + " -> " + solution.target().id() + " ranges "
              + solution.ranges() + " '" + solution.arc() + "' distance " + solution.distance() + " " + solution
              .modifiers().stream().map(modifier -> modifier.description() + " " + modifier.value()).toList() + " "
              + value(solution.value()) + " " + solution.odds() + " '" + solution.reason() + "' wedge ("
              + solution.wedge().origin().getX() + ", " + solution.wedge().origin().getY() + ") facing "
              + solution.wedge().facing() + " from " + solution.wedge().start() + " to " + solution.wedge().end();
    }

    private static String badges(GpuFireOrders.Snapshot fire) {
        return fire.badges().stream().map(badge -> badge.targetId() + " " + value(badge.value()) + " " + badge.odds()
              + " '" + badge.reason() + "'").toList().toString();
    }

    private static String value(int value) {
        return (value == TargetRoll.IMPOSSIBLE) ? "IMPOSSIBLE" : Integer.toString(value);
    }
}
