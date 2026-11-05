/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbyUnit;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.force.Force;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks a unit card's own actions and walks the unit menu's submenus through the stage, as a pointer would: the
 * card's icons act on their unit, and a submenu opens in the menu's place instead of beside it.
 */
@Tag("on-demand")
class LobbyUnitCardActionsSmokeTest {

    @Test
    void aWideCardsIconsActOnItsUnit() {
        UiTestStage.run(ui -> {
            Recorder actions = new Recorder();
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, actions, id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(forces(unit(1, true, true), unit(2, true, false), unit(3, false, false),
                  unit(4, true, false, false)));
            ui.draw();

            click(ui, panel.findActor("lobby-unit-view-1"));
            click(ui, panel.findActor("lobby-unit-configure-1"));
            click(ui, panel.findActor("lobby-unit-damage-1"));
            click(ui, panel.findActor("lobby-unit-camo-1"));
            click(ui, panel.findActor("lobby-unit-c3-1"));
            click(ui, panel.findActor("lobby-unit-delete-2"));
            click(ui, panel.findActor("lobby-unit-view-3"));
            assertEquals(List.of("VIEW 1", "configure 1", "DAMAGE 1", "INDI_CAMO 1", "c3 1", "delete [2]",
                  "VIEW 3"), actions.log);

            assertNull(panel.findActor("lobby-unit-alpha-1"), "Alpha Strike stats stay in the menu");
            assertNull(panel.findActor("lobby-unit-c3-2"), "a unit without C3 has no C3 icon");
            assertNull(panel.findActor("lobby-unit-configure-3"), "another player's unit has no actions of its own");
            assertNull(panel.findActor("lobby-unit-camo-3"));
            assertNull(panel.findActor("lobby-unit-delete-3"));
            assertNull(panel.findActor("lobby-unit-secondary-3"), "nor a second row");
            assertNull(panel.findActor("lobby-unit-damage-4"), "a unit whose status may not open, an asset a Game Master keeps, has no icon");
            assertNotNull(panel.findActor("lobby-unit-camo-4"));

            Table first = (Table) panel.findActor("lobby-unit-configure-1").getParent();
            assertEquals(List.of("lobby-unit-view-1", "lobby-unit-configure-1", "lobby-unit-delete-1"),
                  Arrays.stream(first.getChildren().toArray()).limit(3).map(Actor::getName).toList(),
                  "the first row holds the main actions");
            assertEquals(4, first.getChildren().size, "and ends with the ⋮");
            Table second = panel.findActor("lobby-unit-secondary-1");
            assertEquals(List.of("lobby-unit-damage-1", "lobby-unit-camo-1", "lobby-unit-c3-1"),
                  Arrays.stream(second.getChildren().toArray()).map(Actor::getName).toList(),
                  "the second row the secondary ones");
            assertEquals(first.localToStageCoordinates(new Vector2()).x,
                  second.localToStageCoordinates(new Vector2()).x, 0.5f, "both rows start at the same edge");

            actions.log.clear();
            click(ui, unitMore(panel));
            assertEquals(List.of("menu [1] [CONFIGURE, DAMAGE, DELETE, DEPLOY, INDI_CAMO, VIEW]"), actions.log,
                  "the card's ⋮ leaves out what its icons offer");

            forces.compact(true);
            ui.draw();
            assertNull(panel.findActor("lobby-unit-configure-1"), "a compact row keeps only its ⋮");
            panel.remove();
        });
    }

    @Test
    void aSubmenuOpensInTheMenusPlaceAndBackReturns() {
        UiTestStage.run(ui -> {
            Recorder actions = new Recorder();
            LobbyForcesPanel forces = new LobbyForcesPanel(ui.kit, actions, id -> null);
            forces.attach(ui.stage.getRoot());
            Table panel = place(ui, forces.panel());
            forces.show(forces(unit(1, true, false)));
            ui.draw();

            Actor more = unitMore(panel);
            assertNotNull(more);
            click(ui, more);
            assertEquals(List.of("menu [1] [CONFIGURE, DAMAGE, DELETE, DEPLOY, INDI_CAMO, VIEW]"), actions.log);
            forces.showMenu(List.of(LobbyMenuEntry.item("View...", true), LobbyMenuEntry.SEPARATOR,
                  LobbyMenuEntry.submenu("Deploy", true, List.of(LobbyMenuEntry.item("Round 1", true),
                        LobbyMenuEntry.item("Round 2", true))),
                  LobbyMenuEntry.item("Delete", true)));
            ui.draw();
            Group menu = ui.stage.getRoot().findActor("lobby-unit-menu");
            assertTrue(menu.isVisible());
            assertNull(menu.findActor("lobby-unit-menu-back"), "the top of the menu has no Back row");

            click(ui, row(menu, "Deploy"));
            assertTrue(menu.isVisible(), "a submenu keeps the menu open");
            assertEquals(List.of(2), forces.menuPath());
            assertNotNull(row(menu, "Round 2"), "the submenu's items replace the menu's");
            assertNull(row(menu, "View..."));
            assertNotNull(menu.findActor("lobby-unit-menu-back"));

            click(ui, menu.findActor("lobby-unit-menu-back"));
            assertEquals(List.of(), forces.menuPath());
            assertNotNull(row(menu, "View..."), "Back shows the menu's top again");

            click(ui, row(menu, "Deploy"));
            assertTrue(forces.back(), "Escape goes up a level first");
            assertTrue(menu.isVisible());
            assertEquals(List.of(), forces.menuPath());

            click(ui, row(menu, "Deploy"));
            ui.stage.keyDown(Input.Keys.LEFT);
            assertEquals(List.of(), forces.menuPath(), "Left goes up a level");

            click(ui, row(menu, "Deploy"));
            click(ui, row(menu, "Round 2"));
            assertEquals("choose [2, 1]", actions.log.getLast());
            assertFalse(menu.isVisible(), "choosing an item closes the menu");
            panel.remove();
        });
    }

    private static Actor unitMore(Table panel) {
        Actor configure = panel.findActor("lobby-unit-configure-1");
        Group tools = configure.getParent();
        return tools.getChildren().peek();
    }

    /** The menu row that shows {@code text}, or null. */
    private static UiButton row(Group menu, String text) {
        List<UiButton> found = new ArrayList<>();
        collect(menu, text, found);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static void collect(Group group, String text, List<UiButton> found) {
        for (Actor child : group.getChildren()) {
            if (child instanceof UiButton button && hasLabel(button, text)) {
                found.add(button);
            } else if (child instanceof Group inner) {
                collect(inner, text, found);
            }
        }
    }

    private static boolean hasLabel(Group group, String text) {
        for (Actor child : group.getChildren()) {
            if ((child instanceof Label label && text.equals(label.getText().toString()))
                  || (child instanceof Group inner && hasLabel(inner, text))) {
                return true;
            }
        }
        return false;
    }

    private static Table place(UiTestStage ui, Table table) {
        ui.window.addActor(table);
        table.setBounds(100, 100, 900, 800);
        table.validate();
        ui.draw();
        return table;
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

    private static LobbyUnit unit(int id, boolean editable, boolean c3) {
        return unit(id, editable, c3, editable);
    }

    private static LobbyUnit unit(int id, boolean editable, boolean c3, boolean damage) {
        return new LobbyUnit(id, 1, "Unit " + id, "", "", 50, 1000, "1,000", "", "", List.of(), 0,
              LobbyUnit.Link.NONE, false, null, Force.NO_FORCE, editable, c3, null, damage);
    }

    /** The units under their owner, so the units view draws their cards. */
    private static LobbyForces forces(LobbyUnit... units) {
        List<Integer> ids = Arrays.stream(units).map(LobbyUnit::id).toList();
        LobbyForces.Owner owner = new LobbyForces.Owner(1, "Player", true, true, false, false, 3000, 150, ids);
        return new LobbyForces(List.of(units), List.of(owner), List.of(), List.of(), List.of(), -1);
    }

    private static final class Recorder implements LobbyForcesPanel.Actions {
        final List<String> log = new ArrayList<>();

        @Override
        public void command(LobbyForcesPanel.Command command) {
            log.add("command " + command);
        }

        @Override
        public void sort(int index) {
        }

        @Override
        public void configure(int unitId) {
            log.add("configure " + unitId);
        }

        @Override
        public void c3(int unitId) {
            log.add("c3 " + unitId);
        }

        @Override
        public void drop(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
        }

        @Override
        public void delete(List<Integer> unitIds, List<Integer> formationIds) {
            log.add("delete " + unitIds);
        }

        @Override
        public void unitCommand(int unitId, String name) {
            log.add(name + " " + unitId);
        }

        @Override
        public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView,
              Set<String> omit) {
            log.add("menu " + unitIds + " " + new TreeSet<>(omit));
        }

        @Override
        public void choose(List<Integer> path) {
            log.add("choose " + path);
        }
    }
}
