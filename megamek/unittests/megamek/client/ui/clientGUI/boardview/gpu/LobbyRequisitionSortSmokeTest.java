/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.lobby.RequisitionSort;
import megamek.client.ui.gdx.TableSort;
import megamek.client.ui.gdx.TableSort.Direction;
import megamek.client.ui.gdx.UiSortHeader;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sorts the Requisition's results through the stage, as a pointer would: a column's header and the sort control above
 * the results set one order, each showing what the other set, and the search answers in that order.
 */
@Tag("on-demand")
class LobbyRequisitionSortSmokeTest {

    @Test
    void theHeadersAndTheSortControlSetOneOrder() {
        UiTestStage.run(ui -> {
            SortActions actions = new SortActions();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, false);
            Table body = requisition.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 1400, 900);
            requisition.opened(LobbyForces.EMPTY);
            run(ui, .1f);
            assertEquals(RequisitionSort.BY_NAME, requisition.sort());
            assertEquals(List.of("Archer", "Atlas", "Locust"), names(requisition));
            UiSortHeader name = requisition.sortHeader(RequisitionSort.NAME);
            assertNotNull(name);
            assertEquals(Direction.ASCENDING, name.direction(), "the name shows the order the results start in");

            UiSortHeader armor = requisition.sortHeader(RequisitionSort.ARMOR);
            assertNotNull(armor, "a wide view shows the armor column");
            float armorX = armor.localToStageCoordinates(new Vector2()).x;
            click(ui, armor);
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.ARMOR, Direction.DESCENDING), requisition.sort());
            assertEquals(requisition.sort(), actions.last.sort(), "the search is asked for the order");
            assertEquals(List.of("Atlas", "Archer", "Locust"), names(requisition));
            assertEquals(requisition.sort(), requisition.sortControl().sort(), "the control shows the header's order");
            assertNull(name.direction());
            assertEquals(Direction.DESCENDING, armor.direction());
            assertEquals(armorX, armor.localToStageCoordinates(new Vector2()).x, .5f, "the columns stand still");

            click(ui, armor);
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.ARMOR, Direction.ASCENDING), requisition.sort());
            assertEquals(List.of("Locust", "Archer", "Atlas"), names(requisition));

            click(ui, requisition.sortControl().select());
            click(ui, requisition.sortControl().select().popup().label(
                  requisition.sortControl().keys().indexOf(RequisitionSort.TONS)));
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.TONS, Direction.DESCENDING), requisition.sort());
            assertEquals(List.of("Atlas", "Archer", "Locust"), names(requisition));
            assertNull(armor.direction(), "no column shows the tonnage, so no header has the arrow");

            click(ui, requisition.sortControl().directionButton());
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.TONS, Direction.ASCENDING), requisition.sort());
            assertEquals(List.of("Locust", "Archer", "Atlas"), names(requisition));
        });
    }

    @Test
    void aSortByTheValueFollowsTheValueShown() {
        UiTestStage.run(ui -> {
            SortActions actions = new SortActions();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, false);
            Table body = requisition.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 1400, 900);
            requisition.opened(LobbyForces.EMPTY);
            run(ui, .1f);

            UiSortHeader value = requisition.sortHeader(RequisitionSort.BV);
            assertNotNull(value);
            click(ui, value);
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.BV, Direction.DESCENDING), requisition.sort());
            assertEquals(Direction.DESCENDING, value.direction());

            click(ui, requisition.valueToggle().option(1));
            run(ui, .1f);
            assertEquals(new TableSort<>(RequisitionSort.PV, Direction.DESCENDING), requisition.sort());
            assertTrue(requisition.sortControl().keys().contains(RequisitionSort.PV));
            assertEquals(requisition.sort(), requisition.sortControl().sort());
            assertEquals(value, requisition.sortHeader(RequisitionSort.PV));
            assertEquals(Direction.DESCENDING, value.direction());
            assertEquals(List.of("Atlas", "Archer", "Locust"), names(requisition));
        });
    }

    @Test
    void aNarrowViewHidesColumnsButTheControlStillReachesTheirKeys() {
        UiTestStage.run(ui -> {
            SortActions actions = new SortActions();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, false);
            Table body = requisition.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 420, 900);
            requisition.opened(LobbyForces.EMPTY);
            run(ui, .1f);
            assertNull(requisition.sortHeader(RequisitionSort.LEVEL), "the rules column is the first to go");
            assertTrue(requisition.sortControl().keys().contains(RequisitionSort.LEVEL));
            assertNotNull(requisition.sortHeader(RequisitionSort.NAME));
        });
    }

    private static List<String> names(LobbyRequisitionPanel requisition) {
        return requisition.resultsList().items().stream().map(row -> row.unit().getChassis()).toList();
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

    private static MekSummary unit(String chassis, double tons, int bv, int pv, int armor) {
        MekSummary unit = new MekSummary();
        unit.setChassis(chassis);
        unit.setModel("");
        unit.setName(chassis);
        unit.setUnitType(UnitType.getTypeName(UnitType.MEK));
        unit.setYear(3025);
        unit.setTons(tons);
        unit.setBV(bv);
        unit.setPointValue(pv);
        unit.setWalkMp(4);
        unit.setRunMp(6);
        unit.setTotalArmor(armor);
        unit.setTechBase("Inner Sphere");
        unit.setLevel("A");
        return unit;
    }

    /** Answers with three units in the query's order, and keeps the last query. */
    private static final class SortActions implements LobbyRequisitionPanel.Actions {
        private final List<MekSummary> units = List.of(unit("Archer", 70, 1817, 38, 208),
              unit("Atlas", 100, 1897, 52, 304), unit("Locust", 20, 432, 18, 64));
        private RequisitionSearch.Query last;

        @Override
        public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
            last = query;
            List<RequisitionRow> rows = new ArrayList<>();
            for (MekSummary unit : units) {
                rows.add(RequisitionRow.of(unit));
            }
            List<RequisitionRow> sorted = RequisitionSort.sorted(rows, query.sort());
            done.accept(new RequisitionSearch.Result(query, sorted, sorted.size(), List.of(), 0, 0));
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
