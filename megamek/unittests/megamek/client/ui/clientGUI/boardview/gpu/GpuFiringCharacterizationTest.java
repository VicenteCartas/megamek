/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.panels.phaseDisplay.PointblankShotDisplay;
import megamek.common.actions.DirectionalMountFacingAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.FlipArmsAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.actions.TorsoTwistAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Characterizes the Swing FiringDisplay's attack queue: what twisting, flipping a Directional Torso Mount, the automatic
 * searchlight and three shots at two targets queue (every setting of each action, the weapons marked as fired and the
 * heat the weapon display shows), what Backspace removes, what Done sends to the server, how internal bombs are counted
 * and what to-hit the weapon display shows. The values were recorded on the code before stage E3a extracted requeue,
 * replaceAttacks, removeAttack and toHitFor, and must not change.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuFiringCharacterizationTest {
    /** The settings of a weapon attack declared without bombs, special ammunition, aimed shot or strafing run. */
    static final String UNSET = " payload {external={}, internal={}} other -1 aim -1 NONE strafing false true";
    /** {@link #observe} after {@link #declare}: the left arm's laser cannot reach the Hachiwara (Impossible). */
    static final List<String> DECLARED = List.of("queue [TorsoTwistAction, DirectionalMountFacingAction, Illuminates"
                + " with searchlight. Archer ARC-2R, AC/20 [AC/20] ; needs 4  (using Left Side table), Medium Laser;"
                + " needs 4  (using Left Side table), Impossible]",
          "searchlight 1->0:42",
          "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1" + UNSET,
          "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
          "WeaponAttackAction Medium Laser LA->0:43 ammo -1 [] carrier -1" + UNSET,
          "used [Medium Laser LA, Medium Laser RA, AC/20 RT]",
          "facing 1 mount 3",
          "heat 13 13 (20)");
    /** {@link #observe} after Backspace undid the last shot. */
    static final List<String> AFTER_BACKSPACE = List.of("queue [TorsoTwistAction, DirectionalMountFacingAction,"
                + " Illuminates with searchlight. Archer ARC-2R, AC/20 [AC/20] ; needs 4  (using Left Side table),"
                + " Medium Laser; needs 4  (using Left Side table)]",
          "searchlight 1->0:42",
          "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1" + UNSET,
          "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
          "used [Medium Laser RA, AC/20 RT]",
          "facing 1 mount 3",
          "heat 10 10 (20)");
    /** The Cheetah's two internal rocket bombs fired at the Archer, as queued and as sent. */
    static final List<String> BOMBS = List.of(
          "WeaponAttackAction Rocket Launcher Pod NOS->0:42 ammo 4 [M_STANDARD] carrier 7" + UNSET,
          "WeaponAttackAction Rocket Launcher Pod NOS->0:42 ammo 6 [M_STANDARD] carrier 7" + UNSET);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void aForcedGrenadeTargetRestoresThePreviousTargetWithoutAnInspector(boolean pointblank) throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            onSwing(() -> {
                FiringDisplay active = firing.display;
                if (pointblank) {
                    firing.display.setIgnoringEvents(true);
                    firing.board.game.setPhase(GamePhase.MOVEMENT);
                    when(firing.gui.isProcessingPointblankShot()).thenReturn(true);
                    active = new PointblankShotDisplay(firing.gui);
                    active.selectEntity(firing.attacker.getId());
                }
                try {
                    WeaponMounted laser = weapon(firing.attacker, "Medium Laser", Mek.LOC_RIGHT_ARM);
                    WeaponMounted grenade = (WeaponMounted) firing.attacker.addEquipment(
                          EquipmentType.get("ISVehicularGrenadeLauncher"), Mek.LOC_LEFT_ARM);
                    grenade.setFacing(0);
                    firing.unitDisplay.selectWeapon(laser);
                    active.target(firing.ahead);
                    firing.unitDisplay.selectWeapon(grenade);
                    assertNotSame(firing.ahead, active.getTarget());
                    firing.unitDisplay.selectWeapon(laser);
                    assertSame(firing.ahead, active.getTarget());
                    // Inspecting an undeployed unit must not force the active attacker's target.
                    Entity inspected = GpuFiringFixture.unit("Atlas AS7-D.mtf", 99, null);
                    WeaponMounted otherGrenade = (WeaponMounted) inspected.addEquipment(
                          EquipmentType.get("ISVehicularGrenadeLauncher"), Mek.LOC_LEFT_ARM);
                    otherGrenade.setFacing(0);
                    firing.unitDisplay.displayEntity(inspected);
                    firing.unitDisplay.selectWeapon(otherGrenade);
                    assertSame(firing.ahead, active.getTarget());
                    assertNull(firing.gui.getUnitDisplay());
                    assertNull(firing.gui.getUnitDisplayDialog());
                } finally {
                    if (pointblank) { active.removeAllListeners(); }
                }
                return null;
            });
        }
    }

    @Test
    void inspectingAnotherUnitClearsThePreviousFiringSolutionWithoutAnInspector() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            onSwing(() -> {
                firing.unitDisplay.selectWeapon(weapon(firing.attacker, "Medium Laser", Mek.LOC_RIGHT_ARM));
                firing.display.target(firing.ahead);
                assertNotEquals("---", firing.unitDisplay.getToHit());
                firing.unitDisplay.displayEntity(firing.left);
                assertNull(firing.unitDisplay.getSelectedWeapon());
                assertEquals("---", firing.unitDisplay.getRange());
                assertEquals("---", firing.unitDisplay.getToHit());
                return null;
            });
        }
    }

    @Test
    void declaredActionsKeepTheirSettingsAndAreSentInFrontArcOrder() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            List<String> observed = onSwing(() -> {
                declare(firing);
                List<String> lines = new ArrayList<>(observe(firing));
                firing.undoLastStep();
                lines.addAll(observe(firing));
                firing.fire(weapon(firing.attacker, "Medium Laser", Mek.LOC_LEFT_ARM), firing.left);
                firing.display.ready();
                return lines;
            });
            assertEquals(DECLARED, observed.subList(0, DECLARED.size()));
            assertEquals(AFTER_BACKSPACE, observed.subList(DECLARED.size(), observed.size()),
                  "Backspace removes the last weapon attack and makes its weapon usable again");
            // Done sends the twist, the mount facing and the searchlight first, then the shots in the front arc,
            // then the others
            assertEquals(List.of("twist 1 facing 1", "mount 1 weapon 10 facing 3", "searchlight 1->0:42",
                        "WeaponAttackAction AC/20 RT->0:42 ammo 16 [M_STANDARD] carrier 1" + UNSET,
                        "WeaponAttackAction Medium Laser RA->0:42 ammo -1 [] carrier -1" + UNSET,
                        "WeaponAttackAction Medium Laser LA->0:43 ammo -1 [] carrier -1" + UNSET),
                  render(firing.board.game, firing.sent(firing.attacker.getId())));
            verify(firing.gui, never()).doYesNoDialog(anyString(), anyString());
        }
    }

    @Test
    void internalBombsAreCountedOncePerShotWhenSent() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity fighter = GpuFiringFixture.unit("Cheetah F-11.blk", 7, new Coords(5, 6));
            List<String> observed = onSwing(() -> {
                List<WeaponMounted> bombs = arm(firing, fighter);
                firing.display.selectEntity(fighter.getId());
                for (WeaponMounted bomb : bombs) {
                    firing.fire(bomb, firing.ahead);
                }
                List<String> lines = new ArrayList<>(render(firing.board.game, firing.board.game.getActionsVector()));
                lines.add("used " + used(fighter));
                firing.display.ready();
                lines.add("internal bombs used " + ((IBomber) fighter).getUsedInternalBombs());
                return lines;
            });
            assertEquals(BOMBS, observed.subList(0, 2));
            assertEquals(List.of("used [Rocket Launcher Pod NOS, Rocket Launcher Pod NOS]", "internal bombs used 2"),
                  observed.subList(2, observed.size()));
            assertEquals(BOMBS, render(firing.board.game, firing.sent(fighter.getId())));
        }
    }

    @Test
    void theWeaponDisplayShowsTheToHitTheFireButtonActsOn() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            Entity atlas = firing.attacker;
            WeaponMounted laser = weapon(atlas, "Medium Laser", Mek.LOC_RIGHT_ARM);
            WeaponMounted centerLaser = weapon(atlas, "Medium Laser", Mek.LOC_CENTER_TORSO);
            List<String> observed = onSwing(() -> {
                List<String> lines = new ArrayList<>();
                firing.unitDisplay.selectWeapon(laser);
                firing.display.target(firing.ahead);
                lines.add(solution(firing));
                firing.display.fire();
                firing.unitDisplay.selectWeapon(laser);
                lines.add(solution(firing));
                firing.display.torsoTwist(0);
                firing.unitDisplay.selectWeapon(centerLaser);
                firing.display.target(firing.ahead);
                lines.add(solution(firing));
                return lines;
            });
            // In range and arc; then the same laser once fired; then a torso laser after twisting away from the Archer
            assertEquals(List.of("true Range: 3 To Hit: 4 (91.6%) = 4 (gunnery skill)", "false Range: 3 Already fired",
                  "false Range: 3 To Hit: (0%) Target not in arc."), observed);
        }
    }

    /**
     * EDT: the Atlas twists right, flips its left torso's Directional Torso Mount (on the LRM 20, quirks on) to the
     * rear, lights the Archer with its searchlight (declared automatically with the first shot) and fires the AC/20 and
     * the right arm's laser at the Archer and the left arm's laser at the Hachiwara.
     */
    static void declare(GpuFiringFixture firing) {
        Entity atlas = firing.attacker;
        firing.board.game.getOptions().getOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS).setValue(true);
        WeaponMounted lrm = weapon(atlas, "LRM 20", Mek.LOC_LEFT_TORSO);
        lrm.getQuirks().getOption(OptionsConstants.QUIRK_WEAPON_POS_DIRECT_TORSO_MOUNT).setValue(true);
        atlas.setExternalSearchlight(true);
        atlas.setSearchlightState(true);
        GUIPreferences.getInstance().setAutoDeclareSearchlight(true);
        firing.display.torsoTwist(1);
        firing.unitDisplay.selectWeapon(lrm);
        firing.display.flipDirectionalMount();
        firing.fire(weapon(atlas, "AC/20", Mek.LOC_RIGHT_TORSO), firing.ahead);
        firing.fire(weapon(atlas, "Medium Laser", Mek.LOC_RIGHT_ARM), firing.ahead);
        firing.fire(weapon(atlas, "Medium Laser", Mek.LOC_LEFT_ARM), firing.left);
    }

    /** EDT: adds the bomber as the local player's unit with two internal rocket bombs; returns the bombs. */
    static List<WeaponMounted> arm(GpuFiringFixture firing, Entity bomber) {
        bomber.setOwner(firing.board.player);
        firing.board.game.addEntity(bomber, false);
        BombLoadout bombs = new BombLoadout();
        bombs.put(BombTypeEnum.RL, 2);
        ((IBomber) bomber).setIntBombChoices(bombs);
        ((IBomber) bomber).applyBombs();
        return bomber.getWeaponList().stream().filter(WeaponMounted::isInternalBomb).toList();
    }

    /** EDT: the queue's descriptions, the game's temporary actions, the weapons marked as fired and the heat. */
    static List<String> observe(GpuFiringFixture firing) {
        Game game = firing.board.game;
        Entity atlas = firing.attacker;
        List<String> lines = new ArrayList<>();
        lines.add("queue " + firing.display.getAttackDescriptions());
        lines.addAll(render(game, game.getActionsVector()));
        lines.add("used " + used(atlas));
        lines.add("facing " + atlas.getSecondaryFacing() + " mount "
              + weapon(atlas, "LRM 20", Mek.LOC_LEFT_TORSO).getDirectionalMountFacing());
        WeaponDisplayData.HeatBuildup heat = WeaponDisplayData.heatBuildup(game, atlas);
        lines.add("heat " + heat.value() + " " + heat.text());
        return lines;
    }

    /** Every setting of each action that Done sends (ready() copies these into the weapon attacks it sends). */
    static List<String> render(Game game, List<EntityAction> actions) {
        return actions.stream().map(action -> render(game, action)).toList();
    }

    private static String render(Game game, EntityAction action) {
        return switch (action) {
            case WeaponAttackAction attack -> {
                Entity unit = game.getEntity(attack.getEntityId());
                WeaponMounted weapon = (WeaponMounted) unit.getEquipment(attack.getWeaponId());
                yield "%s %s %s->%d:%d ammo %d %s carrier %d payload %s other %d aim %d %s strafing %b %b".formatted(
                      attack.getClass().getSimpleName(), weapon.getName(), unit.getLocationAbbr(weapon.getLocation()),
                      attack.getTargetType(), attack.getTargetId(), attack.getAmmoId(), attack.getAmmoMunitionType(),
                      attack.getAmmoCarrier(), payloads(attack), attack.getOtherAttackInfo(),
                      attack.getAimedLocation(), attack.getAimingMode(), attack.isStrafing(),
                      attack.isStrafingFirstShot());
            }
            case TorsoTwistAction twist -> "twist " + twist.getEntityId() + " facing " + twist.getFacing();
            case DirectionalMountFacingAction mount -> "mount " + mount.getEntityId() + " weapon "
                  + mount.getWeaponNumber() + " facing " + mount.getFacing();
            case FlipArmsAction flip -> "flip arms " + flip.getEntityId() + " " + flip.getIsFlipped();
            case SearchlightAttackAction light -> "searchlight " + light.getEntityId() + "->" + light.getTargetType()
                  + ":" + light.getTargetId();
            default -> action.getClass().getSimpleName() + " " + action.getEntityId();
        };
    }

    /** The bomb payloads in a stable order. */
    private static Map<String, Map<BombTypeEnum, Integer>> payloads(WeaponAttackAction attack) {
        Map<String, Map<BombTypeEnum, Integer>> sorted = new TreeMap<>();
        attack.getBombPayloads().forEach((bay, loadout) -> sorted.put(bay, new TreeMap<>(loadout)));
        return sorted;
    }

    /** The unit's weapons marked as fired this round, by name and location. */
    static List<String> used(Entity unit) {
        return unit.getWeaponList().stream().filter(WeaponMounted::isUsedThisRound)
              .map(weapon -> weapon.getName() + " " + unit.getLocationAbbr(weapon.getLocation())).toList();
    }

    /** EDT: whether Fire is enabled, and the weapon display's range and to-hit as plain text. */
    static String solution(GpuFiringFixture firing) {
        return firing.display.isFireAllowed() + " " + GpuBoardWindow.plainText(
              firing.unitDisplay.getFiringSolution()).replaceAll("\\s+", " ").strip();
    }
}
