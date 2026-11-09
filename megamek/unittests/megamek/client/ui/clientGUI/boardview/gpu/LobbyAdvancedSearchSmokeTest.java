/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.gdx.UiPickList;
import megamek.client.ui.gdx.UiRangeInput;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the Requisition's advanced search through the stage, as a pointer would: the filter strip's Advanced opens it
 * in the results' place, typed and picked criteria reach the search and show as tags, and a tag, Clear and Escape undo
 * them.
 */
@Tag("on-demand")
class LobbyAdvancedSearchSmokeTest {

    @Test
    void criteriaSetInTheViewFilterTheResultsAndShowAsTags() {
        EquipmentType.initializeTypes();
        UiTestStage.run(ui -> {
            SearchActions actions = new SearchActions();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, true);
            Table body = requisition.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 1400, 900);
            requisition.opened(LobbyForces.EMPTY);
            run(ui, .1f);
            assertEquals(3, requisition.resultsList().items().size());

            click(ui, requisition.advancedToggle());
            run(ui, .1f);
            assertTrue(requisition.advancedOpen(), "the strip's Advanced opens the advanced search");
            assertTrue(requisition.advancedToggle().isChecked(), "and shows checked while it is open");
            assertFalse(requisition.drawerOpen());
            LobbyAdvancedSearchView view = requisition.advancedView();
            assertNotNull(view);
            assertTrue(text(view.actor()).contains("3 matching units"), text(view.actor()));

            UiRangeInput walk = assertInstanceOf(UiRangeInput.class, view.control(Field.WALK));
            click(ui, walk.minField());
            ui.stage.keyTyped('5');
            run(ui, .1f);
            assertEquals(new RequisitionAdvancedSearch.Range(5, null), actions.criteria().values().get(Field.WALK));
            assertEquals(2, requisition.resultsList().items().size(), "the walk of at least 5 leaves two units");
            assertTrue(text(view.actor()).contains("2 matching units"), text(view.actor()));
            assertTrue(walk.minField().hasKeyboardFocus(), "typing keeps the field's focus");

            click(ui, view.tabs().option(2));
            Actor bfType = view.control(Field.BF_TYPE);
            assertNotNull(bfType, "the Battlefield Support tab shows its controls");
            click(ui, view.tabs().option(1));
            run(ui, .05f);
            Group asTypes = assertInstanceOf(Group.class, view.control(Field.AS_TYPE));
            Actor first = asTypes.getChildren().get(0);
            Actor second = asTypes.getChildren().get(1);
            assertEquals(first.getY(), second.getY(), 0.5f, "the unit type chips flow side by side, not one per row");
            click(ui, view.tabs().option(0));
            run(ui, .05f);
            if (view.columns() == 3) {
                Actor movement = view.control(Field.WALK);
                Actor gyro = view.control(Field.GYRO);
                Actor quirks = view.control(Field.QUIRKS);
                Actor omni = view.control(Field.OMNI);
                assertEquals(movement.localToStageCoordinates(new Vector2()).x,
                      gyro.localToStageCoordinates(new Vector2()).x, 1f, "construction sits under movement");
                assertEquals(quirks.localToStageCoordinates(new Vector2()).x,
                      omni.localToStageCoordinates(new Vector2()).x, 1f, "the unit flags sit under the quirks");
            }
            UiPickList pickList = find(view.control(Field.QUIRKS), UiPickList.class);
            assertNotNull(pickList, "the quirks are a pick list");
            view.reveal(pickList.select());
            run(ui, .05f);
            click(ui, pickList.select());
            click(ui, pickList.select().popup().label(0));
            run(ui, .1f);
            assertTrue(actions.criteria().values().containsKey(Field.QUIRKS), "a picked quirk reaches the search");

            click(ui, view.actor().findActor("lobby-requisition-advanced-done"));
            run(ui, .1f);
            assertFalse(requisition.advancedOpen(), "Done shows the results again");
            assertFalse(requisition.advancedToggle().isChecked());
            assertTrue(requisition.advancedToggle().getText().toString().contains("2"),
                  "the Advanced count has both criteria: " + requisition.advancedToggle().getText());
            assertFalse(requisition.filtersToggle().getText().toString().contains("2"),
                  "the Filters count leaves them to Advanced: " + requisition.filtersToggle().getText());
            assertFalse(requisition.resetButton().isDisabled(), "Reset filters is on while criteria are at work");

            Group tags = body.findActor("lobby-requisition-tags");
            assertNotNull(tags, "the criteria show as tags");
            Button walkTag = tag(tags, "Walk");
            assertNotNull(walkTag, text(tags));
            assertNotNull(tag(tags, "Quirks"), text(tags));
            click(ui, walkTag);
            run(ui, .1f);
            assertNull(actions.criteria().values().get(Field.WALK), "the tag removes its criterion");
            assertTrue(actions.criteria().values().containsKey(Field.QUIRKS), "and only that one");
            assertEquals(3, requisition.resultsList().items().size());

            click(ui, requisition.advancedToggle());
            run(ui, .1f);
            view = requisition.advancedView();
            Button clear = button(view.actor(), "Clear");
            assertNotNull(clear);
            click(ui, clear);
            run(ui, .1f);
            assertTrue(actions.criteria().isEmpty(), "Clear empties the tab's criteria");
            click(ui, requisition.advancedToggle());
            run(ui, .1f);
            assertFalse(requisition.advancedOpen(), "a second click on Advanced shuts the view");
            click(ui, requisition.advancedToggle());
            run(ui, .1f);
            assertTrue(requisition.back(), "Escape's step shuts the view");
            assertFalse(requisition.advancedOpen());
            body.remove();
        });
    }

    private static <T extends Actor> T find(Actor root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Group group) {
            for (Actor child : group.getChildren()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Button tag(Group tags, String start) {
        for (Actor child : tags.getChildren()) {
            if (child instanceof Button button && text(button).toLowerCase().startsWith(start.toLowerCase())) {
                return button;
            }
        }
        return null;
    }

    private static Button button(Actor root, String text) {
        if (root instanceof Button button && text(button).trim().equalsIgnoreCase(text)) {
            return button;
        }
        if (root instanceof Group group) {
            for (Actor child : group.getChildren()) {
                Button found = button(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String text(Actor actor) {
        StringBuilder text = new StringBuilder();
        collect(actor, text);
        return text.toString();
    }

    private static void collect(Actor actor, StringBuilder text) {
        if (actor instanceof Label label) {
            text.append(label.getText()).append('\n');
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                collect(child, text);
            }
        }
    }

    private static void run(UiTestStage ui, float seconds) {
        for (float t = 0; t < seconds; t += 1 / 60f) {
            ui.stage.act(1 / 60f);
            ui.draw();
        }
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor);
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static MekSummary unit(String model, int walk) {
        MekSummary unit = new MekSummary();
        unit.setChassis("Atlas");
        unit.setModel(model);
        unit.setName("Atlas " + model);
        unit.setUnitType(UnitType.getTypeName(UnitType.MEK));
        unit.setYear(2755);
        unit.setTons(100);
        unit.setBV(1897);
        unit.setPointValue(52);
        unit.setWalkMp(walk);
        unit.setRunMp(walk + walk / 2);
        unit.setTotalArmor(304);
        unit.setTotalInternal(152);
        unit.setTechBase("Inner Sphere");
        unit.setLevel("A");
        return unit;
    }

    /** Searches three Atlases with the walk criterion applied, and keeps the last criteria asked for. */
    private static final class SearchActions implements LobbyRequisitionPanel.Actions {
        private final List<MekSummary> units = List.of(unit("AS7-D", 3), unit("AS7-X", 5), unit("AS7-Y", 6));
        private RequisitionAdvancedSearch criteria = RequisitionAdvancedSearch.NONE;

        RequisitionAdvancedSearch criteria() {
            return criteria;
        }

        @Override
        public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
            criteria = query.filters().advanced();
            List<RequisitionRow> rows = new ArrayList<>();
            RequisitionAdvancedSearch.Value walk = criteria.values().get(Field.WALK);
            for (MekSummary unit : units) {
                if (!(walk instanceof RequisitionAdvancedSearch.Range range) || (range.min() == null)
                      || (unit.getWalkMp() >= range.min())) {
                    rows.add(RequisitionRow.of(unit));
                }
            }
            done.accept(new RequisitionSearch.Result(query, rows, rows.size(), List.of(), 0, 0));
        }

        @Override
        public void load(MekSummary unit, Consumer<RequisitionRecord> done) {
        }

        @Override
        public void factions(MekSummary unit, Consumer<RequisitionFactions> done) {
            done.accept(RequisitionFactions.EMPTY);
        }

        @Override
        public boolean readoutTro() {
            return false;
        }

        @Override
        public void readoutTro(boolean value) {
        }

        @Override
        public void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
        }

        @Override
        public void remove(int id) {
        }

        @Override
        public void sprites(List<MekSummary> units) {
        }

        @Override
        public void source(LobbyForcesPanel.Command command) {
        }
    }
}
