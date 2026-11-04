/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiHealthBar;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the lobby's damage editor through the stage: a click on the doll picks the location, the stepper and hit
 * boxes change the form and the doll, Restore Unit brings the unit back whole, Cancel leaves the unit and OK writes
 * the edits to it.
 */
@Tag("on-demand")
class LobbyDamageSmokeTest {

    @Test
    void theDollPicksAndTheControlsEditTheUnit() throws Exception {
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf"))
              .getEntity();
        atlas.setGame(new Game());
        if (atlas.getCrew() == null) {
            atlas.setCrew(new Crew(CrewType.SINGLE));
        }
        int leftArm = atlas.getLocationFromAbbr("LA");
        int armor = atlas.getArmor(leftArm);
        GpuPaperdolls paperdolls = new GpuPaperdolls(Path.of("../../mm-data/data/images/paperdolls"));
        UiTestStage.run(ui -> {
            UnitDamageForm form = UnitDamageForm.of(atlas);
            int[] applied = { 0 };
            boolean[] closed = { false };
            UiDialog dialog = new UiDialog(ui.kit, "Damage", () -> { }).width("dialog.lg");
            LobbyDamageView view = new LobbyDamageView(ui.kit, dialog, form, paperdolls,
                  (work, then) -> {
                      work.run();
                      then.run();
                  }, () -> applied[0]++, () -> { }, () -> closed[0] = true);
            dialog.show(ui.stage, false);
            ui.draw();
            assertEquals("CT", view.chosen().abbr(), "the centre torso shows first");

            GpuPaperdoll doll = find(ui, "damage-doll");
            Vector2 arm = spot(doll, "LA");
            assertNotNull(arm, "the doll has a left arm to click");
            clickAt(ui, doll, arm);
            assertEquals("LA", view.chosen().abbr(), "a click on the doll picks its location");
            Label heading = find(ui, "damage-heading");
            assertEquals(view.chosen().name(), heading.getText().toString(), "the heading follows");

            List<UnitDamageForm.Value> values = view.chosen().values();
            int armorRow = values.indexOf(view.chosen().armor());
            Actor down = find(ui, "damage-value-" + armorRow + "-down");
            reveal(ui, down);
            click(ui, down);
            click(ui, down);
            assertEquals(armor - 2, form.place("LA").armor().value(), "the stepper takes armour off");

            UiHealthBar armorBar = find(ui, "damage-value-" + armorRow);
            Actor bar = armorBar.bar();
            int quarter = armor / 4;
            clickAt(ui, bar, new Vector2(bar.getWidth() * quarter / armor, bar.getHeight() / 2));
            assertEquals(quarter, form.place("LA").armor().value(), "a click on the bar sets it there");
            dragAcross(ui, bar, bar.getWidth() / 4, bar.getWidth() - 1);
            assertEquals(armor, form.place("LA").armor().value(), "a drag to the end scrubs it full");
            Vector2 middle = bar.localToStageCoordinates(new Vector2(bar.getWidth() / 2, bar.getHeight() / 2));
            Vector2 screen = ui.stage.stageToScreenCoordinates(middle);
            ui.stage.mouseMoved(Math.round(screen.x), Math.round(screen.y));
            ui.stage.scrolled(0, 1);
            ui.stage.scrolled(0, 1);
            assertEquals(armor - 2, form.place("LA").armor().value(), "the wheel steps it down");
            assertEquals("\u22122", armorBar.delta().getText().toString(), "the change from the unit shows");
            ui.stage.setKeyboardFocus(armorBar.field());
            armorBar.field().selectAll();
            ui.stage.keyTyped('7');
            ui.draw();
            assertEquals(7, form.place("LA").armor().value(), "a typed number sets it");

            click(ui, find(ui, "damage-next"));
            assertEquals(form.places().get(form.places().indexOf(form.place("LA")) + 1), view.chosen(),
                  "the chevron steps to the next location");
            click(ui, find(ui, "damage-whole"));
            assertNull(view.chosen(), "Whole unit shows the general values");
            assertEquals(Messages.getString("Lobby.damage.wholeUnit"), heading.getText().toString());
            click(ui, find(ui, "damage-previous"));
            assertEquals(form.places().get(form.places().size() - 1), view.chosen(), "back from the whole unit");

            UiButton reroll = find(ui, "damage-reroll");
            assertTrue(reroll.isDisabled(), "nothing to roll again yet");
            click(ui, find(ui, "damage-roll-heavy"));
            assertFalse(reroll.isDisabled(), "a heavy roll can be rolled again");
            assertTrue(form.places().stream().anyMatch(place -> place.armor() != null
                  && place.armor().value() < place.armor().max()), "a heavy roll takes armour off");
            click(ui, find(ui, "damage-roll-none"));
            assertFalse(form.changed(), "None goes back to the unit as read");
            clickAt(ui, doll, arm);
            int boxesRow = -1;
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).kind() == UnitDamageForm.Kind.BOXES && values.get(index).max() > 0) {
                    boxesRow = index;
                    break;
                }
            }
            assertTrue(boxesRow >= 0, "the arm has an item with hit boxes");
            UnitDamageForm.Value hits = values.get(boxesRow);
            Actor box = find(ui, "damage-value-" + boxesRow + "-0");
            reveal(ui, box);
            click(ui, box);
            assertEquals(1, hits.value(), "a click on the box hits the item");
            click(ui, box);
            assertEquals(0, hits.value(), "a click on the last hit clears it");
            click(ui, box);
            assertEquals(1, hits.value());

            click(ui, find(ui, "damage-repair"));
            assertEquals(armor, form.place("LA").armor().value(), "Restore Unit fills the armour");
            assertEquals(0, hits.value(), "and clears the hits");
            assertFalse(form.changed());

            down = find(ui, "damage-value-" + armorRow + "-down");
            reveal(ui, down);
            click(ui, down);
            assertEquals(armor, atlas.getArmor(leftArm), "the unit is untouched until OK");
            click(ui, find(ui, "damage-ok"));
            assertEquals(1, applied[0], "OK reports the edit");
            assertTrue(closed[0], "and closes");
            assertEquals(armor - 1, atlas.getArmor(leftArm), "OK writes the armour to the unit");
            dialog.close();
            ui.draw();
            assertNull(ui.stage.getRoot().findActor("damage-doll"));
        });
    }

    /** A point of the doll, in its own coordinates, that picks the region {@code code}. */
    private static Vector2 spot(GpuPaperdoll doll, String code) {
        for (float y = 1; y < doll.getHeight(); y += 2) {
            for (float x = 1; x < doll.getWidth(); x += 2) {
                if (code.equals(doll.pick(x, y))) {
                    return new Vector2(x, y);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Actor> T find(UiTestStage ui, String name) {
        Actor actor = ui.stage.getRoot().findActor(name);
        assertNotNull(actor, name + " shows");
        return (T) actor;
    }

    private static void reveal(UiTestStage ui, Actor actor) {
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
    }

    private static void dragAcross(UiTestStage ui, Actor actor, float from, float to) {
        float y = actor.getHeight() / 2;
        Vector2 start = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(from, y)));
        Vector2 end = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(to, y)));
        ui.stage.touchDown(Math.round(start.x), Math.round(start.y), 0, Input.Buttons.LEFT);
        ui.stage.touchDragged(Math.round((start.x + end.x) / 2), Math.round(start.y), 0);
        ui.stage.touchDragged(Math.round(end.x), Math.round(end.y), 0);
        ui.stage.touchUp(Math.round(end.x), Math.round(end.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }
    private static void click(UiTestStage ui, Actor actor) {
        clickAt(ui, actor, new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
    }

    private static void clickAt(UiTestStage ui, Actor actor, Vector2 local) {
        Vector2 point = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(local)));
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
