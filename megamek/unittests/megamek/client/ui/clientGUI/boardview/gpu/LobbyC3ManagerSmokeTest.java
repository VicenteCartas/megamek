/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.C3NetworkBoard;
import megamek.client.lobby.C3NetworkBoard.Network;
import megamek.client.lobby.C3NetworkBoard.Picture;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.util.C3Util;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The C3 network manager's view clicked through the stage: templates, Plan Force, Build, picks and slots. */
@Tag("on-demand")
class LobbyC3ManagerSmokeTest {
    private Game game;
    private Player player;
    private int nextId;

    @Test
    void planForceThenBuildMakesALanceNetworkAndDisconnectBreaksIt() {
        UiTestStage.run(ui -> {
            setUp();
            Entity master = unit("C3M", "ISC3MasterComputer");
            List<Entity> slaves = List.of(unit("C3S", "ISC3SlaveUnit"), unit("C3S", "ISC3SlaveUnit"),
                  unit("C3S", "ISC3SlaveUnit"));
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;
            assertEquals(4, fixture.board.picture().rosterUnits().size(), "four units wait in the tray");
            assertNotNull(body.findActor("c3-unit-" + master.getId()), "the master's chip is in the tray");

            click(ui, body.findActor("c3-plan-force"));
            Picture planned = fixture.view.shown();
            assertEquals(1, planned.plans().size(), "Plan Force stages a card");
            assertNotNull(body.findActor("c3-plan-0"), "the card shows");

            click(ui, body.findActor("c3-build"));
            Picture built = fixture.view.shown();
            assertTrue(built.plans().isEmpty(), "Build turns the card into a network");
            assertEquals(1, built.networks().size());
            Network network = built.networks().get(0);
            assertEquals(4, network.members().size());
            assertNotNull(body.findActor("c3-network-" + network.topId()), "the network's card shows");
            for (Entity slave : slaves) {
                assertEquals(master.getId(), slave.getC3Master() == null ? -1 : slave.getC3Master().getId(),
                      "each slave runs to the master");
            }

            click(ui, body.findActor("c3-unit-" + master.getId()));
            assertEquals(List.of(master.getId()), fixture.view.selected(), "a click picks the master");
            click(ui, body.findActor("c3-disconnect"));
            assertTrue(fixture.view.shown().networks().isEmpty(), "Disconnect breaks the network");
            assertTrue(slaves.stream().allMatch(slave -> slave.getC3Master() == null));
            body.remove();
        });
    }

    @Test
    void aTemplateLaysOutSlotsThatAPickedUnitFillsByClick() {
        UiTestStage.run(ui -> {
            setUp();
            Entity master = unit("C3M", "ISC3MasterComputer");
            Entity slave = unit("C3S", "ISC3SlaveUnit");
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;

            click(ui, body.findActor("c3-auto-check"));
            assertTrue(!fixture.view.shown().autoAssign(), "the check box turns auto-assign off");
            click(ui, body.findActor("c3-config-1"));
            Picture empty = fixture.view.shown();
            assertEquals(1, empty.plans().size(), "the template stages an empty card");
            assertEquals(0, empty.planFilled());

            click(ui, body.findActor("c3-unit-" + master.getId()));
            Actor company = body.findActor("c3-slot-0-company--1--1");
            assertNotNull(company, "the company slot is open: " + names(body));
            click(ui, company);
            assertEquals(1, fixture.view.shown().planFilled(), "the picked master fills the company slot: " + fixture.view.shown().status() + " picked " + fixture.view.selected());
            assertNotNull(body.findActor("c3-slot-0-company--1--1-" + master.getId()));

            click(ui, body.findActor("c3-unit-" + slave.getId()));
            click(ui, body.findActor("c3-plan-head-0"));
            assertEquals(2, fixture.view.shown().planFilled(), "a click on the card fills its first open slot");

            click(ui, body.findActor("c3-discard"));
            assertTrue(fixture.view.shown().plans().isEmpty(), "Discard drops the card");
            assertEquals(2, fixture.view.shown().rosterUnits().size());
            body.remove();
        });
    }

    private final class Fixture implements LobbyC3View.Host {
        final C3NetworkBoard board;
        final LobbyC3View view;
        final Table body;

        Fixture(UiTestStage ui) {
            board = new C3NetworkBoard(() -> game, player.getId(), new GameCommands(), List.of());
            view = new LobbyC3View(ui.kit, this, id -> null);
            body = view.body();
            ui.window.addActor(body);
            body.setBounds(40, 40, 1400, 900);
            view.show(board.picture());
            body.validate();
            ui.draw();
        }

        @Override
        public void act(Consumer<C3NetworkBoard> action) {
            action.accept(board);
            view.show(board.picture());
            body.validate();
        }

        @Override
        public void confirm(String title, String text, Runnable yes) {
            yes.run();
        }
    }

    /** Applies the actions to the game as the lobby's commands would. */
    private final class GameCommands implements C3NetworkBoard.Commands {
        @Override
        public void disconnect(List<Entity> units) {
            C3Util.disconnectFromNetwork(game, units);
        }

        @Override
        public void companyMaster(List<Entity> units) {
            run(() -> C3Util.setCompanyMaster(units));
        }

        @Override
        public void lanceMaster(List<Entity> units) {
            run(() -> C3Util.setLanceMaster(units));
        }

        @Override
        public void joinPeers(List<Entity> units, int masterId, boolean disconnectFirst) {
            run(() -> C3Util.joinNh(game, units, masterId, disconnectFirst));
        }

        @Override
        public void connect(List<Entity> units, int masterId, boolean disconnectFirst) {
            run(() -> C3Util.connect(game, units, masterId, disconnectFirst));
        }

        private void run(Action action) {
            try {
                action.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private void setUp() {
        EquipmentType.initializeTypes();
        game = new Game();
        player = new Player(0, "Test Player");
        game.addPlayer(0, player);
        nextId = 1;
    }

    private Entity unit(String model, String equipment) {
        Entity entity = new BipedMek();
        entity.setGame(game);
        entity.setId(nextId++);
        entity.setChassis("Test Mek");
        entity.setModel(model);
        entity.setCrew(new Crew(CrewType.SINGLE));
        entity.setOwner(player);
        entity.setWeight(50.0);
        entity.setOriginalWalkMP(5);
        try {
            entity.addEquipment(EquipmentType.get(equipment), Mek.LOC_HEAD);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        game.addEntity(entity);
        return entity;
    }

    private static String names(Actor actor) {
        List<String> names = new ArrayList<>();
        collect(actor, names);
        return String.join(" ", names);
    }

    private static void collect(Actor actor, List<String> names) {
        if (actor.getName() != null && actor.getName().startsWith("c3-")) {
            names.add(actor.getName());
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                collect(child, names);
            }
        }
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor, "the control is in the view");
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
