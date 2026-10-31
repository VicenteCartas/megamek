/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.RequisitionDossier;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.force.Force;
import megamek.common.loaders.MekSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Clicks the lobby's view toggles through the stage, as a pointer would, and checks what they show. */
@Tag("on-demand")
class LobbyTogglesSmokeTest {

    @Test
    void theCompactToggleSwitchesTheRowsAndHoldsThroughSnapshots() {
        UiTestStage.run(ui -> {
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions(), id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(forces(unit(1), unit(2)));
            ui.draw();

            click(ui, forces.compactToggle());
            assertTrue(forces.compact(), "a click turns compact rows on");
            assertTrue(forces.compactToggle().isChecked(), "the toggle shows checked while on");

            forces.show(forces(unit(1), unit(2), unit(3)));
            ui.draw();
            assertTrue(forces.compact(), "a new snapshot keeps the compact rows");
            assertTrue(forces.compactToggle().isChecked());

            forces.showFormations();
            ui.draw();
            assertTrue(forces.compact(), "the formations view keeps them too");
            click(ui, forces.compactToggle());
            assertFalse(forces.compact(), "a second click turns them off");
            assertFalse(forces.compactToggle().isChecked());
            panel.remove();
        });
    }

    @Test
    void theIdsToggleHidesTheUnitIdsAsksForTheSettingAndFollowsTheSnapshots() {
        UiTestStage.run(ui -> {
            NoForcesActions actions = new NoForcesActions();
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, actions, id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(ownedForces(true));
            ui.draw();
            assertTrue(forces.idsToggle().isChecked(), "the toggle starts as the setting has it: on");
            assertTrue(panel.findActor("lobby-unit-id") != null, "the cards show their ids");

            click(ui, forces.idsToggle());
            assertEquals(List.of(false), actions.idsAsked, "a click asks to hide them");
            assertFalse(forces.idsToggle().isChecked());
            assertEquals(null, panel.findActor("lobby-unit-id"), "the cards drop their ids at once");

            forces.show(ownedForces(false));
            ui.draw();
            assertEquals(null, panel.findActor("lobby-unit-id"), "the snapshot that echoes the setting keeps them out");
            forces.compact(true);
            assertEquals(null, panel.findActor("lobby-unit-id"), "and so do the compact rows");

            click(ui, forces.idsToggle());
            assertEquals(List.of(false, true), actions.idsAsked, "a second click asks to show them");
            assertTrue(panel.findActor("lobby-unit-id") != null);
            panel.remove();
        });
    }

    @Test
    void theSettingsGearInTheTopBarOpensTheSettings() {
        UiTestStage.run(ui -> {
            int[] opened = new int[1];
            LobbyTopBar bar = new LobbyTopBar(ui.kit, new LobbyGameMasterSeat(ui.kit, new NoSeatActions(), false),
                  () -> opened[0]++, () -> { }, () -> { }, warning -> { });
            bar.attach(ui.stage.getRoot());
            ui.window.addActor(bar);
            bar.setBounds(100, 600, 1200, 80);
            bar.validate();
            ui.draw();
            click(ui, bar.findActor("lobby-settings"));
            assertEquals(1, opened[0], "a click on the gear opens Client Settings");
            bar.remove();
        });
    }

    @Test
    void aWideCardShowsItsSheetAndANarrowOneOrACompactRowDoesNot() {
        UiTestStage.run(ui -> {
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions(), id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            LobbyUnit.Sheet sheet = new LobbyUnit.Sheet(4, 6, 0, 208, 107, 10, 20,
                  List.of(new RequisitionDossier.Line("Medium Laser", 2), new RequisitionDossier.Line("PPC", 1)));
            LobbyUnit unit = new LobbyUnit(1, 1, "Unit 1", "50 t", "Medium", 50, 1000, "1,000", "", "", List.of(), 0,
                  LobbyUnit.Link.NONE, false, null, Force.NO_FORCE, true, false, sheet);
            forces.show(new LobbyForces(List.of(unit), List.of(new LobbyForces.Owner(1, "Owner", true, true, false, false,
                  1000, 50, List.of(1))), List.of(), List.of(), List.of(), -1));
            ui.draw();
            Actor block = panel.findActor("lobby-unit-sheet");
            assertTrue(block != null && block.isVisible() && block.getWidth() > 0, "a wide card shows its sheet: " + (block == null ? "none" : block.isVisible() + " " + block.getWidth() + " in " + block.getParent().getWidth()));

            panel.setWidth(360);
            panel.validate();
            ui.draw();
            block = panel.findActor("lobby-unit-sheet");
            assertFalse(block.isVisible(), "a narrow card leaves its sheet out");

            panel.setWidth(900);
            forces.compact(true);
            ui.draw();
            assertEquals(null, panel.findActor("lobby-unit-sheet"), "a compact row stays one line");
            panel.remove();
        });
    }

    @Test
    void theFiltersButtonSlidesTheDrawerOpenAndShut() {
        UiTestStage.run(ui -> {
            NoRequisitionActions actions = new NoRequisitionActions();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions,
                  key -> null, true);
            place(ui, requisition.body());

            click(ui, requisition.filtersToggle());
            assertTrue(requisition.drawerOpen(), "a click opens the drawer");
            assertTrue(requisition.filtersToggle().isChecked(), "the button shows checked while it is open");
            Actor drawer = requisition.body().findActor("lobby-requisition-drawer");
            assertTrue(drawer != null, "the drawer is in the view");
            float seconds = DesignTokens.shipped().duration("medium") / 1000f;
            Actor holder = drawer.getParent();
            run(ui, seconds / 3);
            assertTrue(holder.getHeight() > 0 && holder.getHeight() < drawer.getHeight(),
                  "part way, the drawer is part open: " + holder.getHeight());
            run(ui, seconds);
            assertEquals(drawer.getHeight(), holder.getHeight(), 1, "the drawer ends fully open");

            click(ui, requisition.filtersToggle());
            assertFalse(requisition.drawerOpen(), "a second click shuts it");
            assertFalse(requisition.filtersToggle().isChecked());
            run(ui, seconds * 2);
            assertEquals(0, holder.getHeight(), .5f, "the drawer ends shut");
            assertTrue(requisition.body().findActor("lobby-requisition-drawer") == null);
        });
    }

    private static UiFilterChip chip(Group group, String text) {
        for (Actor actor : group.getChildren()) {
            if (actor instanceof UiFilterChip chip && chip.text().equalsIgnoreCase(text)) {
                return chip;
            }
            if (actor instanceof Group inner) {
                UiFilterChip found = chip(inner, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Table place(UiTestStage ui, Table table) {
        ui.window.addActor(table);
        table.setBounds(100, 100, 900, 800);
        table.validate();
        ui.draw();
        return table;
    }

    private static void run(UiTestStage ui, float seconds) {
        for (float t = 0; t < seconds; t += 1 / 60f) {
            ui.stage.act(1 / 60f);
            ui.draw();
        }
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static LobbyUnit unit(int id) {
        return new LobbyUnit(id, 1, "Unit " + id, "", "", 50, 1000, "", "", "", List.of(), 0, LobbyUnit.Link.NONE,
              false, null, Force.NO_FORCE, true, false, null);
    }

    private static LobbyForces forces(LobbyUnit... units) {
        return new LobbyForces(List.of(units), List.of(), List.of(), List.of(), List.of(), -1);
    }

    /** Units 1 and 2 under their owner, so the units view draws their cards. */
    private static LobbyForces ownedForces(boolean showIds) {
        LobbyForces.Owner owner = new LobbyForces.Owner(1, "Player", true, true, false, false, 2000, 100,
              List.of(1, 2));
        return new LobbyForces(List.of(unit(1), unit(2)), List.of(owner), List.of(), List.of(), List.of(), -1,
              showIds);
    }

    private static final class NoSeatActions implements LobbyGameMasterSeat.SeatActions {
        @Override
        public void claim() {
        }

        @Override
        public void vote(boolean allow) {
        }

        @Override
        public void withdraw() {
        }
    }

    private static final class NoForcesActions implements LobbyForcesPanel.Actions {
        private final List<Boolean> idsAsked = new ArrayList<>();

        @Override
        public void command(LobbyForcesPanel.Command command) {
        }

        @Override
        public void sort(int index) {
        }

        @Override
        public void configure(int unitId) {
        }

        @Override
        public void drop(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
        }

        @Override
        public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
        }

        @Override
        public void choose(List<Integer> path) {
        }

        @Override
        public void showIds(boolean on) {
            idsAsked.add(on);
        }
    }

    private static final class NoRequisitionActions implements LobbyRequisitionPanel.Actions {
        @Override
        public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
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
