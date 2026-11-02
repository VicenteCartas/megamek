/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.ChatLine;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.Relation;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.loaders.MekSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the chat dock's fold button and the Requisition's kept filters through the stage, as a pointer would, and
 * checks what they show and what the client keeps.
 */
@Tag("on-demand")
class LobbyFoldAndFiltersSmokeTest {

    @Test
    void theFoldButtonFoldsTheChatAndTheBadgeCountsLinesFromOthers() {
        UiTestStage.run(ui -> {
            PlayerActions actions = new PlayerActions();
            LobbyPlayersPanel players = new LobbyPlayersPanel(ui.kit, actions, true);
            players.attach(ui.stage.getRoot());
            Table panel = place(ui, players.panel());
            List<ChatLine> lines = new ArrayList<>(List.of(notice("Welcome")));
            players.show(snapshot(lines), null);
            ui.draw();
            Actor log = panel.findActor("lobby-chat-log");
            Actor input = panel.findActor("lobby-chat-input");
            assertTrue(log.isVisible() && log.getHeight() > 0, "the chat starts open");
            float foldX = stageX(players.chatFoldButton());
            float foldY = stageY(players.chatFoldButton());
            float inputY = stageY(input);

            click(ui, players.chatFoldButton());
            assertFalse(players.chatOpen(), "a click folds the chat");
            assertEquals(List.of(false), actions.kept, "the client keeps the fold");
            assertEquals(0, log.getHeight(), .5f, "the log gives its height up");
            assertFalse(log.isVisible());
            assertEquals(foldX, stageX(players.chatFoldButton()), .5f, "the fold button stays put");
            assertEquals(foldY, stageY(players.chatFoldButton()), .5f);
            assertEquals(inputY, stageY(input), .5f, "the input row stays put");
            assertFalse(players.chatBadge().isVisible(), "no badge without unread lines");

            lines.add(new ChatLine(ChatLine.Kind.PLAYER, "Me", "hello", Relation.OWN));
            players.show(snapshot(lines), snapshot(lines.subList(0, 1)));
            ui.draw();
            assertEquals(0, players.chatUnread(), "the local player's own line doesn't count");
            assertEquals("Me: hello", players.chatLatest(), "the folded header shows the latest line");

            List<ChatLine> before = List.copyOf(lines);
            lines.add(new ChatLine(ChatLine.Kind.PLAYER, "Bot", "contact", Relation.ENEMY));
            lines.add(new ChatLine(ChatLine.Kind.PLAYER, "Pal", "o7", Relation.ALLY));
            players.show(snapshot(lines), snapshot(before));
            ui.draw();
            assertEquals(2, players.chatUnread());
            assertTrue(players.chatBadge().isVisible(), "the badge counts lines from others");
            assertEquals(2, players.chatBadge().count());
            assertTrue(players.chatBadge().getWidth() > 0);

            click(ui, players.chatFoldButton());
            assertTrue(players.chatOpen(), "a second click opens the chat");
            assertEquals(List.of(false, true), actions.kept);
            assertEquals(0, players.chatUnread(), "opening reads the lines");
            assertFalse(players.chatBadge().isVisible());
            assertTrue(log.isVisible() && log.getHeight() > 0);
            assertEquals(foldY, stageY(players.chatFoldButton()), .5f, "the fold button is where it was");
            panel.remove();
        });
    }

    @Test
    void aFoldedChatStaysFoldedFromTheStart() {
        UiTestStage.run(ui -> {
            LobbyPlayersPanel players = new LobbyPlayersPanel(ui.kit, new PlayerActions(), false);
            Table panel = place(ui, players.panel());
            players.show(snapshot(List.of(notice("Welcome"))), null);
            ui.draw();
            assertFalse(players.chatOpen());
            assertEquals(0, panel.findActor("lobby-chat-log").getHeight(), .5f);
            assertEquals("Welcome", players.chatLatest());
            panel.remove();
        });
    }

    @Test
    void theWeightsAndTechBasesAreKeptAndClearFiltersClearsThem() {
        UiTestStage.run(ui -> {
            RequisitionActions actions = new RequisitionActions("");
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, false);
            Table body = place(ui, requisition.body());
            requisition.opened(LobbyForces.EMPTY);
            ui.draw();
            click(ui, chip(body, Messages.getString("Lobby.requisition.weight.heavy")));
            assertEquals("C:ALL;W:HEAVY;B:;L:", actions.kept, "a weight chip's click is kept");
            body.remove();

            RequisitionActions next = new RequisitionActions("C:ALL;W:HEAVY;B:CLAN;L:");
            LobbyRequisitionPanel again = new LobbyRequisitionPanel(ui.kit, next, key -> null, false);
            Table nextBody = place(ui, again.body());
            again.opened(LobbyForces.EMPTY);
            ui.draw();
            assertTrue(chip(nextBody, Messages.getString("Lobby.requisition.weight.heavy")).isChecked(),
                  "the kept weight is lit when the Requisition opens");
            Actor clear = find(nextBody, actor -> actor instanceof UiButton button && button.getText() != null
                  && button.getText().toString().equalsIgnoreCase(Messages.getString("Lobby.requisition.clear")));
            assertNotNull(clear, "the kept tech base shows as a tag with Clear filters");
            click(ui, clear);
            assertEquals("C:ALL;W:HEAVY;B:;L:", next.kept, "Clear filters clears the kept tech base, keeping the weight");
            nextBody.remove();
        });
    }

    private static ChatLine notice(String text) {
        return new ChatLine(ChatLine.Kind.NOTICE, "", text, Relation.NONE);
    }

    private static LobbySnapshot snapshot(List<ChatLine> chat) {
        return new LobbySnapshot(1, false, false, LobbyGameMaster.OFF, List.of(), chat, List.of(), "", "",
              LobbyForces.EMPTY, LobbyBattlefield.EMPTY, LobbyReadiness.EMPTY, LobbyRequisition.EMPTY);
    }

    private static float stageX(Actor actor) {
        return actor.localToStageCoordinates(new Vector2()).x;
    }

    private static float stageY(Actor actor) {
        return actor.localToStageCoordinates(new Vector2()).y;
    }

    private static UiFilterChip chip(Group group, String text) {
        return (UiFilterChip) find(group, actor -> actor instanceof UiFilterChip chip
              && chip.text().equalsIgnoreCase(text));
    }

    private static Actor find(Group group, Predicate<Actor> wanted) {
        for (Actor actor : group.getChildren()) {
            if (wanted.test(actor)) {
                return actor;
            }
            if (actor instanceof Group inner) {
                Actor found = find(inner, wanted);
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

    private static final class PlayerActions implements LobbyPlayersPanel.Actions {
        final List<Boolean> kept = new ArrayList<>();

        @Override
        public void chatOpen(boolean open) {
            kept.add(open);
        }

        @Override
        public void chat(String text) {
        }

        @Override
        public void ready() {
        }

        @Override
        public void playerSettings(int id) {
        }

        @Override
        public void camouflage(int id) {
        }

        @Override
        public void changeTeam(int id, int team) {
        }

        @Override
        public void changeDeployment(int id, int position) {
        }

        @Override
        public void toggleGameMaster() {
        }

        @Override
        public void botSettings(int id) {
        }

        @Override
        public void removeBot(int id) {
        }

        @Override
        public void replaceWithBot(int id) {
        }

        @Override
        public void addBot(int team) {
        }
    }

    private static final class RequisitionActions implements LobbyRequisitionPanel.Actions {
        String kept;

        RequisitionActions(String kept) {
            this.kept = kept;
        }

        @Override
        public String keptFilters() {
            return kept;
        }

        @Override
        public void keptFilters(String value) {
            kept = value;
        }

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
