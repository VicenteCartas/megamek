/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The aimed shot dialog (H36, R4) in the component harness: a weapon row's toggle, filled while its attack is aimed,
 * opens the dialog for the weapon and the unit its row shows; the dialog shows that unit's paperdoll with the
 * weapon's roll aimed at each location it may aim at; a pick aims there through the fire orders and closes it, a
 * location the rules leave out takes no click, "No aimed shot" removes the aim, and Dismiss and a click beside the
 * dialog close it unchanged. A shot from behind aims at the torsos on the rear armor doll beside the front one.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuAimDialogSmokeTest {
    private static final int AC20 = 1;
    private static final int KING_CRAB = 7;
    private static final TargetKey TARGET = TargetKey.unit(KING_CRAB);
    private static final GpuFireOrders.AimTarget AIM = new GpuFireOrders.AimTarget(GpuHudFixtures.ATLAS, AC20,
          TARGET);

    @Test
    void theToggleOpensTheDialogWhosePickAimsTheWeapon() {
        GpuHudTestStage.run(hud -> {
            GpuFireOrders fire = mock(GpuFireOrders.class);
            GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
            GpuAimDialog dialog = dialog(hud, fire, state);
            Group layer = (Group) dialog.actor();

            UiButton toggle = GpuAimDialog.toggle(hud.kit.ui, state, GpuHudFixtures.ATLAS, row(""), true, 30);
            assertFalse(toggle.isChecked(), "Not aimed: the outlined face");
            assertTrue(GpuAimDialog.toggle(hud.kit.ui, state, GpuHudFixtures.ATLAS, row("Left Arm"), true, 30)
                  .isChecked(),
                  "Aimed: the filled face");
            hud.window.addActor(toggle);
            toggle.setBounds(40, 40, 30, 30);
            click(hud, toggle);
            assertEquals(AIM, state.aiming, "The toggle opens the dialog for its weapon and row's unit");

            // A targeting computer's aim: every location at +3 but the head.
            GpuFireOrders.AimChoice choice = choice(Entity.LOC_NONE, false);
            update(hud, dialog, choice);
            verify(fire).showAim(AIM);
            assertTrue(layer.isVisible());
            List<String> shown = texts(layer);
            assertTrue(shown.containsAll(List.of("AIMED SHOT TO KING CRAB", "with AC/20"))
                  && !shown.contains("KING CRAB") && !shown.contains("AC/20"),
                  "The title names the unit, once, and its sub-line the weapon");
            assertEquals("8+", ((Label) layer.findActor("aim-none-roll")).getText().toString(),
                  "No aimed shot shows the roll without an aim");
            GpuPaperdoll doll = layer.findActor("aim-doll");
            assertEquals(List.of(7, 1), List.of(Collections.frequency(texts(doll), "11+"),
                  Collections.frequency(texts(doll), "\u2014")), "8+ aimed: 11+ everywhere, the head without a roll");
            move(hud, point(doll, "CT"));
            hud.draw();
            hud.capture("aim-dialog").dispose();

            touch(hud, point(doll, "HD"));
            verify(fire, never()).aim(any(), anyInt());
            assertEquals(AIM, state.aiming, "The head, which the computer may not aim at, takes no click");
            touch(hud, point(doll, "LA"));
            verify(fire).aim(AIM, Mek.LOC_LEFT_ARM);
            assertNull(state.aiming, "A pick closes the dialog");
            update(hud, dialog, choice);
            assertFalse(layer.isVisible());
            verify(fire).showAim(null);

            state.aiming = AIM;
            update(hud, dialog, choice(Mek.LOC_LEFT_ARM, false));
            click(hud, layer.findActor("aim-none"));
            verify(fire).aim(AIM, Entity.LOC_NONE);
            assertNull(state.aiming);

            state.aiming = AIM;
            update(hud, dialog, choice(Mek.LOC_LEFT_ARM, false));
            click(hud, layer.findActor("aim-dismiss"));
            assertNull(state.aiming, "Dismiss closes it");
            state.aiming = AIM;
            update(hud, dialog, choice(Mek.LOC_LEFT_ARM, false));
            touch(hud, new Vector2(10, 10));
            assertNull(state.aiming, "So does a click beside it");
            verify(fire, never()).aim(AIM, Mek.LOC_HEAD);
        });
    }

    @Test
    void aShotFromBehindAimsAtTheTorsosOnTheRearArmorDoll() {
        GpuHudTestStage.run(hud -> {
            GpuFireOrders fire = mock(GpuFireOrders.class);
            GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
            GpuAimDialog dialog = dialog(hud, fire, state);
            Group layer = (Group) dialog.actor();
            state.aiming = AIM;
            update(hud, dialog, choice(Mek.LOC_RIGHT_TORSO, true));
            GpuPaperdoll front = layer.findActor("aim-doll");
            GpuPaperdoll rear = layer.findActor("aim-rear-doll");
            assertTrue(rear.isVisible() && rear.hasParent(), "The rear armor doll stands beside the front one");
            assertEquals(List.of(4, 3), List.of(Collections.frequency(texts(front), "11+"),
                  Collections.frequency(texts(rear), "11+")), "The torsos' rolls move to the rear doll");
            move(hud, point(rear, "CT"));
            hud.draw();
            hud.capture("aim-dialog-rear").dispose();

            touch(hud, point(front, "CT"));
            verify(fire, never()).aim(any(), anyInt());
            assertEquals(AIM, state.aiming, "The front torso, whose armor the shot misses, takes no click");
            touch(hud, point(front, "LA"));
            verify(fire).aim(AIM, Mek.LOC_LEFT_ARM);
            state.aiming = AIM;
            update(hud, dialog, choice(Mek.LOC_RIGHT_TORSO, true));
            touch(hud, point(layer.findActor("aim-rear-doll"), "CT"));
            verify(fire).aim(AIM, Mek.LOC_CENTER_TORSO);
        });
    }

    @Test
    void theRowsOfTheWeaponsThatMayAimCarryTheToggle() {
        GpuHudTestStage.run(hud -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.fire()).thenReturn(mock(GpuFireOrders.class));
            GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
            GpuContextMenu menu = new GpuContextMenu(hud.kit, source, state, id -> { });
            GpuWeaponsPanel weapons = new GpuWeaponsPanel(hud.kit, source, state, menu);
            GpuTargetCards cards = new GpuTargetCards(hud.kit, source, state, menu);
            // The AC/20's attack aims at the left arm; the laser may be assigned aimed; the rifle may aim but has no
            // shot; the missiles may not aim.
            List<GpuFireOrders.WeaponRow> rows = List.of(row(AC20, "Left Arm", true, ""), row(2, "", true, ""),
                  row(3, "", true, "Target not in arc."), row(4, "", false, ""));
            GpuFireOrders.Snapshot fire = new GpuFireOrders.Snapshot(true, true, GpuHudFixtures.ATLAS,
                  new GpuFireOrders.Focus(TARGET, "King Crab", null), -1, rows,
                  List.of(new GpuFireOrders.Target(TARGET, 'A', "King Crab", true, 0, true, null)),
                  List.of(new GpuFireOrders.Attack(AC20, TARGET, "AC/20", "RT", "Ballistic", "", -1, 11, 8.3, "")),
                  0, false, false, "", null, null, null, List.of(), null, Map.of(), 1, 0, null);
            state.update(GpuHudFixtures.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            GpuHud.Inputs inputs = new GpuHud.Inputs(GpuHudFixtures.frame(GpuHudFixtures.status(),
                  GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, fire, GpuPhysicalOptions.Snapshot.EMPTY,
                        GpuUnitRecord.Snapshot.EMPTY)), GpuHud.HudView.EMPTY, null,
                  GpuContextMenuSmokeTest.preferences(), GpuHud.Metrics.of(hud.width(), hud.height()), List.of());
            weapons.update(inputs);
            cards.update(inputs);
            // In the right column, as the HUD places the panel.
            Container<Actor> slot = new Container<>(weapons.actor()).top().fillX();
            hud.window.addActor(slot);
            slot.setBounds(hud.width() - 330, 200, 310, 520);
            slot.validate();
            Group panel = (Group) weapons.actor();
            List<UiButton> toggles = List.of(panel.findActor("aim-" + AC20), panel.findActor("aim-2"),
                  panel.findActor("aim-3"));
            assertEquals(List.of(true, false, false), toggles.stream().map(UiButton::isChecked).toList(),
                  "Filled where the attack is aimed");
            assertEquals(List.of(false, false, true), toggles.stream().map(UiButton::isDisabled).toList(),
                  "Open where the weapon has its attack or \"+\" would assign it");
            assertNull(panel.findActor("aim-4"), "No toggle for a weapon that may not aim");
            UiButton card = ((Group) cards.actor()).findActor("aim-" + AC20);
            assertTrue(card.isChecked() && !card.isDisabled(), "The card's row of the aimed attack, filled");
            click(hud, toggles.get(1));
            assertEquals(new GpuFireOrders.AimTarget(GpuHudFixtures.ATLAS, 2, TARGET), state.aiming);
        });
    }

    /** The dialog in the harness, posting to {@code fire}. */
    private static GpuAimDialog dialog(GpuHudTestStage hud, GpuFireOrders fire, GpuHudState state) {
        GpuBoardSource source = mock(GpuBoardSource.class);
        when(source.fire()).thenReturn(fire);
        GpuAimDialog dialog = new GpuAimDialog(hud.kit, source, state, new GpuPaperdolls());
        Group layer = (Group) dialog.actor();
        layer.setBounds(0, 0, hud.width(), hud.height());
        hud.window.addActor(layer);
        return dialog;
    }

    /** One frame of the orders: the AC/20's row on the King Crab, which may aim, with the dialog's choice. */
    private static void update(GpuHudTestStage hud, GpuAimDialog dialog, GpuFireOrders.AimChoice choice) {
        GpuFireOrders.Snapshot fire = new GpuFireOrders.Snapshot(true, true, GpuHudFixtures.ATLAS,
              new GpuFireOrders.Focus(TARGET, "King Crab", null), -1, List.of(row("")), List.of(), List.of(), 0, false,
              false, "", null, null, null, List.of(), null, Map.of(), 1, 0, choice);
        dialog.update(new GpuHud.Inputs(GpuHudFixtures.frame(GpuHudFixtures.status(), GpuHudFixtures.panels(
              GpuMovePlan.Snapshot.EMPTY, fire, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY)),
              GpuHud.HudView.EMPTY, null, GpuContextMenuSmokeTest.preferences(),
              GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
        ((Table) dialog.actor()).validate();
    }

    private static GpuFireOrders.WeaponRow row(String aimedAt) {
        return row(AC20, aimedAt, true, "");
    }

    /** A weapon's row on the King Crab, which may aim there or not, with the reason it has no shot ("" for one). */
    private static GpuFireOrders.WeaponRow row(int eqNum, String aimedAt, boolean aimable, String reason) {
        return new GpuFireOrders.WeaponRow(eqNum, "Weapon " + eqNum, "RT", "", "20", 7, "", List.of(), -1, -1, TARGET,
              reason.isEmpty() ? 8 : TargetRoll.IMPOSSIBLE, reason.isEmpty() ? 41.7 : 0, reason, true, false,
              aimable, aimedAt);
    }

    /**
     * The King Crab's armor (an Atlas's record will do) with a targeting computer's choice, aimed at the location;
     * {@code rear}: the shot strikes the rear.
     */
    private static GpuFireOrders.AimChoice choice(int location, boolean rear) throws Exception {
        EquipmentType.initializeTypes();
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        List<Boolean> enabled = new ArrayList<>(Collections.nCopies(8, true));
        enabled.set(Mek.LOC_HEAD, false);
        List<Integer> rolls = new ArrayList<>(Collections.nCopies(8, 11));
        rolls.set(Mek.LOC_HEAD, TargetRoll.IMPOSSIBLE);
        return new GpuFireOrders.AimChoice(AIM, "AC/20", "King Crab", GpuUnitRecord.armor(unit), enabled, rolls,
              location, 8, rear, false);
    }

    /** A stage point inside the doll's region of the location. */
    private static Vector2 point(GpuPaperdoll doll, String code) {
        assertNotNull(doll);
        for (float y = 2; y < doll.getHeight(); y += 2) {
            for (float x = 2; x < doll.getWidth(); x += 2) {
                if (code.equals(doll.pick(x, y)) && code.equals(doll.pick(x + 4, y))
                      && code.equals(doll.pick(x, y + 4))) {
                    return doll.localToStageCoordinates(new Vector2(x + 2, y + 2));
                }
            }
        }
        throw new AssertionError("No region " + code);
    }

    /** The texts of the labels in the group and in the groups inside it, such as a doll's numbers and notes. */
    private static List<String> texts(Group group) {
        List<String> texts = new ArrayList<>();
        for (Actor child : group.getChildren()) {
            if (child instanceof Label label) {
                texts.add(label.getText().toString());
            } else if (child instanceof Group inner) {
                texts.addAll(texts(inner));
            }
        }
        return texts;
    }

    private static void click(GpuHudTestStage hud, Actor actor) {
        assertNotNull(actor);
        touch(hud, actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }

    private static void touch(GpuHudTestStage hud, Vector2 stagePoint) {
        Vector2 point = hud.stage.stageToScreenCoordinates(new Vector2(stagePoint));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    private static void move(GpuHudTestStage hud, Vector2 stagePoint) {
        Vector2 point = hud.stage.stageToScreenCoordinates(new Vector2(stagePoint));
        hud.stage.mouseMoved((int) point.x, (int) point.y);
    }
}
