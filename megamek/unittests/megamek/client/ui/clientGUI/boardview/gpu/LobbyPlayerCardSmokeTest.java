/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.lobby.LobbySnapshot.TeamGroup;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the player cards' team and deployment pickers and action icons through the stage, as a pointer would, and
 * checks which player actions they ask for: the cards have no menu.
 */
@Tag("on-demand")
class LobbyPlayerCardSmokeTest {

    private static final int ME = 1;
    private static final int BOT = 2;
    private static final int OTHER = 3;
    private static final int GHOST = 4;

    @Test
    void theIconsOpenThePlayerAndBotSettingsAndRemoveTheBot() {
        UiTestStage.run(ui -> {
            Recorded actions = new Recorded();
            Table panel = show(ui, actions, List.of());

            click(ui, panel.findActor("lobby-player-settings-" + ME));
            click(ui, panel.findActor("lobby-player-bot-" + BOT));
            click(ui, panel.findActor("lobby-player-settings-" + BOT));
            click(ui, panel.findActor("lobby-player-remove-" + BOT));
            assertEquals(List.of("settings " + ME, "bot " + BOT, "settings " + BOT, "remove " + BOT), actions.calls);

            assertNull(panel.findActor("lobby-player-bot-" + ME), "the local player is no bot");
            assertNull(panel.findActor("lobby-player-remove-" + ME), "nor can they remove themselves");
            for (String kind : List.of("settings", "bot", "remove", "team", "start")) {
                assertNull(panel.findActor("lobby-player-" + kind + "-" + OTHER), "another player's card has no " + kind);
            }
            click(ui, panel.findActor("lobby-player-replace-" + GHOST));
            assertEquals("replace " + GHOST, actions.calls.get(actions.calls.size() - 1),
                  "a disconnected player's card offers a bot in their place");
            panel.remove();
        });
    }

    @Test
    void theTeamButtonPicksATeamFromAFlatList() {
        UiTestStage.run(ui -> {
            Recorded actions = new Recorded();
            Table panel = show(ui, actions, List.of());
            Actor team = panel.findActor("lobby-player-team-" + BOT);
            click(ui, team);
            UiButton three = ui.stage.getRoot().findActor("lobby-team-3");
            assertNotNull(three, "the team list opens");
            UiButton two = ui.stage.getRoot().findActor("lobby-team-2");
            assertTrue(marked(two), "the bot's team is checked");
            assertFalse(marked(three));
            click(ui, three);
            assertEquals(List.of("team " + BOT + " 3"), actions.calls);
            assertFalse(pickerOpen(ui), "choosing closes the list");

            click(ui, team);
            assertTrue(pickerOpen(ui));
            click(ui, team);
            assertFalse(pickerOpen(ui), "a second press on the button closes the list");
            panel.remove();
        });
    }

    @Test
    void theStartButtonPicksADeploymentAreaFromTheCompassAndTheCustomZones() {
        UiTestStage.run(ui -> {
            Recorded actions = new Recorded();
            Table panel = show(ui, actions, List.of(5));
            click(ui, panel.findActor("lobby-player-start-" + ME));
            UiButton north = ui.stage.getRoot().findActor("lobby-deploy-2");
            assertNotNull(north, "the compass opens");
            assertTrue(north.isChecked(), "the player's own area is checked, its offset and width aside");
            assertFalse(((UiButton) ui.stage.getRoot().findActor("lobby-deploy-1")).isChecked(),
                  "NW is not N");
            for (int position = 0; position <= 10; position++) {
                assertNotNull(ui.stage.getRoot().findActor("lobby-deploy-" + position), "area " + position);
            }
            int zone = Board.encodeCustomDeploymentZoneID(5);
            click(ui, ui.stage.getRoot().findActor("lobby-deploy-" + zone));
            assertEquals(List.of("deploy " + ME + " " + zone), actions.calls, "a custom zone is offered too");

            click(ui, panel.findActor("lobby-player-start-" + ME));
            click(ui, ui.stage.getRoot().findActor("lobby-deploy-4"));
            assertEquals("deploy " + ME + " 4", actions.calls.get(1));
            assertFalse(pickerOpen(ui), "choosing closes the compass");
            panel.remove();
        });
    }

    private static Table show(UiTestStage ui, Recorded actions, List<Integer> zones) {
        LobbyPlayersPanel players = new LobbyPlayersPanel(ui.kit, actions, true);
        players.attach(ui.stage.getRoot());
        Table panel = players.panel();
        ui.window.addActor(panel);
        panel.setBounds(100, 100, 900, 800);
        List<TeamGroup> teams = List.of(
              new TeamGroup(1, "Team 1", List.of(row(ME, "Me", 1, false, true, false),
                    row(OTHER, "Other", 1, false, false, false))),
              new TeamGroup(2, "Team 2", List.of(row(BOT, "Bot", 2, true, true, false),
                    row(GHOST, "Gone", 2, false, false, true))));
        players.show(new LobbySnapshot(ME, false, false, LobbyGameMaster.OFF, teams, List.of(), zones, "", "",
              LobbyForces.EMPTY, LobbyBattlefield.EMPTY, LobbyReadiness.EMPTY, LobbyRequisition.EMPTY), null);
        panel.validate();
        ui.draw();
        return panel;
    }

    private static boolean pickerOpen(UiTestStage ui) {
        return ui.stage.getRoot().findActor("lobby-player-menu").isVisible();
    }

    /** Whether a menu row shows its check mark, its first child. */
    private static boolean marked(UiButton row) {
        return row.getChildren().first().isVisible();
    }

    private static PlayerRow row(int id, String name, int team, boolean bot, boolean local, boolean ghost) {
        return new PlayerRow(id, name, team, bot, id == ME, local, false, false, false, ghost, false, 0, 0, 0,
              "N, 1, 3", true, 0x808080, "");
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor, "the control shows");
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static final class Recorded implements LobbyPlayersPanel.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void chatOpen(boolean open) {
        }

        @Override
        public void chat(String text) {
        }

        @Override
        public void ready() {
        }

        @Override
        public void playerSettings(int id) {
            calls.add("settings " + id);
        }

        @Override
        public void camouflage(int id) {
            calls.add("camo " + id);
        }

        @Override
        public void changeTeam(int id, int team) {
            calls.add("team " + id + " " + team);
        }

        @Override
        public void changeDeployment(int id, int position) {
            calls.add("deploy " + id + " " + position);
        }

        @Override
        public void botSettings(int id) {
            calls.add("bot " + id);
        }

        @Override
        public void removeBot(int id) {
            calls.add("remove " + id);
        }

        @Override
        public void replaceWithBot(int id) {
            calls.add("replace " + id);
        }

        @Override
        public void addBot(int team) {
        }
    }
}
