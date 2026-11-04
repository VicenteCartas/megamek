/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.RequisitionDossier;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiDialog;
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
    void theCardsAndTheCompactRowsAlwaysShowTheUnitIds() {
        UiTestStage.run(ui -> {
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions(), id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(ownedForces());
            ui.draw();
            assertTrue(panel.findActor("lobby-unit-id") != null, "the cards show their ids");

            click(ui, forces.compactToggle());
            assertTrue(forces.compact());
            assertTrue(panel.findActor("lobby-unit-id") != null, "and so do the compact rows");
            panel.remove();
        });
    }

    @Test
    void theMenuButtonInTheTopBarOpensTheSystemMenu() {
        UiTestStage.run(ui -> {
            int[] opened = new int[1];
            LobbyTopBar bar = new LobbyTopBar(ui.kit, new LobbyGameMasterSeat(ui.kit, new NoSeatActions(), false),
                  () -> opened[0]++, () -> { }, warning -> { });
            bar.attach(ui.stage.getRoot());
            ui.window.addActor(bar);
            bar.setBounds(100, 600, 1200, 80);
            bar.validate();
            ui.draw();
            click(ui, bar.findActor("lobby-menu"));
            assertEquals(1, opened[0], "a click on the menu button opens the system menu");
            bar.remove();
        });
    }

    /** Counts what the system menu asks for: save, quick save, load, quick load, settings, leave. */
    private static final class CountingSystemActions implements LobbyNativeDialogs.SystemActions {
        final int[] runs = new int[6];
        private final boolean loads;

        CountingSystemActions(boolean loads) {
            this.loads = loads;
        }

        @Override
        public void save() {
            runs[0]++;
        }

        @Override
        public void quickSave() {
            runs[1]++;
        }

        @Override
        public void load() {
            runs[2]++;
        }

        @Override
        public void quickLoad() {
            runs[3]++;
        }

        @Override
        public boolean loads() {
            return loads;
        }

        @Override
        public void settings() {
            runs[4]++;
        }

        @Override
        public void leave() {
            runs[5]++;
        }
    }

    private static void choose(UiTestStage ui, LobbyNativeDialogs dialogs, CountingSystemActions actions,
          String item) {
        dialogs.systemMenu(actions);
        ui.draw();
        click(ui, ui.stage.getRoot().findActor(item));
        ui.draw();
        assertFalse(dialogs.isOpen(), item + " closes the menu");
    }

    @Test
    void theSystemMenuSavesLoadsOpensTheSettingsAndLeaves() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            CountingSystemActions actions = new CountingSystemActions(true);
            choose(ui, dialogs, actions, "Lobby.menu.resume");
            assertArrayEquals(new int[6], actions.runs, "Resume only closes the menu");
            choose(ui, dialogs, actions, "Lobby.gameMenu.save");
            choose(ui, dialogs, actions, "Lobby.gameMenu.quickSave");
            choose(ui, dialogs, actions, "Lobby.gameMenu.load");
            choose(ui, dialogs, actions, "Lobby.gameMenu.quickLoad");
            choose(ui, dialogs, actions, "Lobby.menu.settings");
            choose(ui, dialogs, actions, "Lobby.menu.leave");
            assertArrayEquals(new int[] { 1, 1, 1, 1, 1, 1 }, actions.runs, "each button does its one thing");

            dialogs.systemMenu(actions);
            ui.draw();
            ui.stage.keyDown(Input.Keys.ESCAPE);
            ui.draw();
            assertFalse(dialogs.isOpen(), "Escape resumes");
            assertArrayEquals(new int[] { 1, 1, 1, 1, 1, 1 }, actions.runs);
            dialogs.dispose();
        });
    }

    @Test
    void aLobbyThatCannotLoadOffersOnlySaving() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.systemMenu(new CountingSystemActions(false));
            ui.draw();
            assertTrue(ui.stage.getRoot().findActor("Lobby.gameMenu.save") != null);
            assertEquals(null, ui.stage.getRoot().findActor("Lobby.gameMenu.load"), "a MekHQ game loads nothing");
            assertEquals(null, ui.stage.getRoot().findActor("Lobby.gameMenu.quickLoad"));
            dialogs.dispose();
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
    void aWideCompactRowShowsAllItsFactsAndANarrowOneKeepsTheSkills() {
        UiTestStage.run(ui -> {
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions(), id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            LobbyUnit.Sheet sheet = new LobbyUnit.Sheet(4, 6, 3, 208, 107, 10, 20, List.of());
            LobbyUnit unit = new LobbyUnit(1, 1, "Unit 1", "", "Heavy", 65, 1000, "1,000", "Kira Vance", "3/4",
                  List.of(new LobbyUnit.Tag("Auto-eject off", LobbyUnit.Tone.FLAVOUR)), 0, LobbyUnit.Link.NONE, false,
                  null, Force.NO_FORCE, true, false, sheet);
            forces.show(new LobbyForces(List.of(unit), List.of(new LobbyForces.Owner(1, "Owner", true, true, false,
                  false, 1000, 65, List.of(1))), List.of(), List.of(), List.of(), -1));
            forces.compact(true);
            panel.setWidth(1400);
            panel.validate();
            ui.draw();
            assertEquals(List.of("65 t Heavy", "4 / 6 / 3", "Kira Vance", "3/4", "Auto-eject off"),
                  shownFacts(panel), "a wide row shows every fact");

            panel.setWidth(560);
            panel.validate();
            ui.draw();
            List<String> narrow = shownFacts(panel);
            assertTrue(narrow.contains("3/4") && !narrow.contains("Auto-eject off") && narrow.size() < 5,
                  "a narrow row keeps the skills and drops the tag first: " + narrow);
            panel.remove();
        });
    }

    private static List<String> shownFacts(Group group) {
        List<String> facts = new ArrayList<>();
        for (Actor actor : group.getChildren()) {
            if (actor instanceof Label label && LobbyForcesPanel.COMPACT_FACT.equals(label.getName())) {
                if (label.isVisible() && label.getWidth() > 0) {
                    facts.add(label.getText().toString());
                }
            } else if (actor instanceof Group inner) {
                facts.addAll(shownFacts(inner));
            }
        }
        return facts;
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

    /** A right click anywhere on a unit's card opens its menu, over its text and sheet as much as at its ends. */
    @Test
    void aRightClickAnywhereOnACardOpensItsMenu() {
        UiTestStage.run(ui -> {
            int[] menus = new int[1];
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions() {
                @Override
                public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
                    menus[0]++;
                }
            }, id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            LobbyUnit.Sheet sheet = new LobbyUnit.Sheet(4, 6, 0, 208, 107, 10, 20,
                  List.of(new RequisitionDossier.Line("Medium Laser", 2), new RequisitionDossier.Line("PPC", 1),
                        new RequisitionDossier.Line("SRM 6", 1), new RequisitionDossier.Line("AC/20", 1)));
            LobbyUnit unit = new LobbyUnit(1, 1, "Unit 1", "50 t", "Medium", 50, 1000, "1,000", "Pilot", "4/5",
                  List.of(), 0, LobbyUnit.Link.NONE, false, null, Force.NO_FORCE, true, false, sheet);
            forces.show(new LobbyForces(List.of(unit), List.of(new LobbyForces.Owner(1, "Owner", true, true, false,
                  false, 1000, 50, List.of(1))), List.of(), List.of(), List.of(), -1));
            ui.draw();
            Actor card = panel.findActor("lobby-unit-1");
            List<String> missed = new ArrayList<>();
            for (int step = 1; step < 20; step++) {
                for (float height : new float[] { 0.25f, 0.5f, 0.75f }) {
                    float x = card.getWidth() * step / 20;
                    float y = card.getHeight() * height;
                    Actor hit = card.hit(x, y, true);
                    if (hit != null && hit.isDescendantOf(card) && hit instanceof com.badlogic.gdx.scenes.scene2d.ui
                          .Button) {
                        continue;
                    }
                    int before = menus[0];
                    rightClick(ui, card, x, y);
                    if (menus[0] == before) {
                        missed.add(Math.round(x) + "," + Math.round(y) + " on " + (hit == null ? "nothing"
                              : hit.getClass().getSimpleName() + " " + hit.getName()));
                    }
                }
            }
            assertTrue(missed.isEmpty(), "a right click opens the menu everywhere on the card: missed " + missed);
            panel.remove();
        });
    }

    @Test
    void deleteUsesTheSelectionWhileTheForcesHaveKeyboardFocus() {
        UiTestStage.run(ui -> {
            List<List<Integer>> deleted = new ArrayList<>();
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, new NoForcesActions() {
                @Override
                public void delete(List<Integer> unitIds, List<Integer> formationIds) {
                    assertTrue(formationIds.isEmpty());
                    deleted.add(unitIds);
                }
            }, id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(ownedForces());
            ui.draw();

            ui.stage.setKeyboardFocus(panel);
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertTrue(deleted.isEmpty(), "Delete without a selection does nothing");

            TextField chat = ui.kit.input("abc", false);
            ui.window.addActor(chat);
            chat.setBounds(1100, 100, 200, 40);
            ui.stage.setKeyboardFocus(chat);
            click(ui, panel.findActor("lobby-unit-1"));
            assertSame(panel, ui.stage.getKeyboardFocus(), "selecting a card takes focus out of chat");
            ui.stage.keyDown(Input.Keys.BACKSPACE);
            assertTrue(deleted.isEmpty(), "Backspace is not the Delete shortcut");
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(List.of(List.of(1)), deleted);

            forces.redraw();
            ui.draw();
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(List.of(List.of(1), List.of(1)), deleted, "refreshing cards retains keyboard selection");

            ui.stage.setKeyboardFocus(chat);
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(2, deleted.size(), "editing chat never deletes a selected unit");
            click(ui, panel.findActor("lobby-unit-2"));
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(List.of(2), deleted.getLast(), "the current selection is used");

            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> { });
            dialog.cancelButton("Cancel");
            dialog.show(ui.stage, false);
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(3, deleted.size(), "a dialog keeps Delete away from the forces behind it");
            dialog.close();

            LobbyCentre centre = new LobbyCentre(ui.kit, panel, false);
            ui.window.addActor(centre);
            centre.setBounds(100, 100, 900, 800);
            ui.stage.setKeyboardFocus(panel);
            centre.show("map", "Map setup", new Table());
            ui.stage.keyDown(Input.Keys.FORWARD_DEL);
            assertEquals(3, deleted.size(), "another centre view cannot delete the hidden selection");
            centre.remove();
            chat.remove();
        });
    }

    private static void rightClick(UiTestStage ui, Actor actor, float localX, float localY) {
        Vector2 point = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(localX, localY)));
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.RIGHT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.RIGHT);
        ui.draw();
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
    private static LobbyForces ownedForces() {
        LobbyForces.Owner owner = new LobbyForces.Owner(1, "Player", true, true, false, false, 2000, 100,
              List.of(1, 2));
        return new LobbyForces(List.of(unit(1), unit(2)), List.of(owner), List.of(), List.of(), List.of(), -1);
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

    private static class NoForcesActions implements LobbyForcesPanel.Actions {
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
        public void delete(List<Integer> unitIds, List<Integer> formationIds) {
        }

        @Override
        public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
        }

        @Override
        public void choose(List<Integer> path) {
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
