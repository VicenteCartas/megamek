/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbyRequisition.Recipient;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.lobby.LobbySnapshot.TeamGroup;
import megamek.client.lobby.RequisitionCatalog;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the roster's cards while the Requisition is open, as a pointer would, and checks that the recipient follows
 * them, that the picker beside Add follows too, and that the roster marks whoever the picker names.
 */
@Tag("on-demand")
class LobbyRecipientSmokeTest {
    private static final int ME = 1;
    private static final int MY_BOT = 2;
    private static final int ENEMY = 3;

    @Test
    void theRosterAndThePickerBesideAddChooseTheRecipientTogether() {
        UiTestStage.run(ui -> {
            LobbyRequisitionPanel[] requisition = new LobbyRequisitionPanel[1];
            LobbyPlayersPanel players = new LobbyPlayersPanel(ui.kit, new PlayerActions(id -> requisition[0].recipient(id)),
                  true);
            players.attach(ui.stage.getRoot());
            Table roster = place(ui, players.panel(), 20, 360);
            requisition[0] = new LobbyRequisitionPanel(ui.kit, new RequisitionActions(), key -> null, false);
            Table body = place(ui, requisition[0].body(), 400, 900);
            LobbyRequisition rules = new LobbyRequisition(RequisitionCatalog.Rules.OPEN,
                  List.of(new Recipient(ME, "Me", true, false, "blue"),
                        new Recipient(MY_BOT, "Princess", false, true, "red")), false,
                  List.of());
            players.show(snapshot(rules), null);
            requisition[0].show(rules, LobbyForces.EMPTY);
            requisition[0].opened(LobbyForces.EMPTY);
            Runnable frame = () -> {
                players.recipient(requisition[0].recipient());
                ui.draw();
            };
            frame.run();

            UiSelect owner = body.findActor("lobby-requisition-owner");
            assertNotNull(owner, "two recipients give a picker");
            assertSame(body.findActor("lobby-requisition-add").getParent(), owner.getParent().getParent(),
                  "the picker stands in Add's group, not across the strip");
            assertEquals(ME, requisition[0].recipient(), "the units go to you first");
            assertEquals(ME, players.recipient(), "the roster marks you");

            clickName(ui, roster.findActor("lobby-player-" + MY_BOT));
            frame.run();
            assertEquals(MY_BOT, requisition[0].recipient(), "a click on your bot's card sends the units to it");
            assertEquals(1, owner.getSelectedIndex(), "the picker follows the roster");
            assertEquals(MY_BOT, players.recipient(), "the roster marks the bot");

            clickName(ui, roster.findActor("lobby-player-" + ENEMY));
            frame.run();
            assertEquals(MY_BOT, requisition[0].recipient(), "an enemy's card cannot take your units");

            owner.select(0);
            owner.fire(new ChangeListener.ChangeEvent());
            frame.run();
            assertEquals(ME, requisition[0].recipient());
            assertEquals(ME, players.recipient(), "the roster follows the picker");
            assertSame(ui.kit.skin.getDrawable("token-card-selected"),
                  ((Table) roster.findActor("lobby-player-" + ME)).getBackground(), "the marked card is lit");
            assertSame(ui.kit.skin.getDrawable("token-card"),
                  ((Table) roster.findActor("lobby-player-" + MY_BOT)).getBackground(), "the other cards are not");

            players.recipient(-1);
            assertSame(ui.kit.skin.getDrawable("token-card"),
                  ((Table) roster.findActor("lobby-player-" + ME)).getBackground(), "closing unmarks the roster");
            body.remove();
            roster.remove();
        });
    }

    @Test
    void bothPreviewsFollowTheRecipientsCamouflageDespiteCachedAndLateSprites() {
        UiTestStage.run(ui -> {
            MekSummary unit = new MekSummary();
            unit.setChassis("Atlas");
            unit.setModel("AS7-D");
            unit.setName("Atlas AS7-D");
            unit.setUnitType(UnitType.getTypeName(UnitType.MEK));
            unit.setTechBase("Inner Sphere");
            unit.setLevel("A");
            Map<String, Drawable> sprites = new HashMap<>();
            Drawable blue = ui.kit.skin.newDrawable("white", Color.BLUE);
            Drawable red = ui.kit.skin.newDrawable("white", Color.RED);
            Drawable green = ui.kit.skin.newDrawable("white", Color.GREEN);
            String blueKey = LobbyRequisition.spriteKey(unit.getName(), "blue");
            String redKey = LobbyRequisition.spriteKey(unit.getName(), "red");
            String greenKey = LobbyRequisition.spriteKey(unit.getName(), "green");
            sprites.put(blueKey, blue);
            List<List<MekSummary>> requested = new ArrayList<>();
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, new RequisitionActions() {
                @Override
                public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
                    done.accept(new RequisitionSearch.Result(query, List.of(RequisitionRow.of(unit)), 1, List.of(), 0, 0));
                }

                @Override
                public void sprites(List<MekSummary> units) {
                    requested.add(units);
                }
            }, sprites::get, false);
            Table body = place(ui, requisition.body(), 20, 1400);
            requisition.show(recipients("red"), LobbyForces.EMPTY);
            requisition.opened(LobbyForces.EMPTY);
            requisition.probePick();
            frame(ui);
            assertPreviews(requisition, blue);

            requisition.recipient(MY_BOT);
            frame(ui);
            assertEquals(List.of(List.of(unit)), requested, "the bot needs a separate preview of the same unit");
            assertFalse(hasSprite(requisition.resultsList(), blue));
            assertFalse(hasSprite(requisition.dossierBody(), blue));
            requisition.spriteArrived(blueKey);
            frame(ui);
            assertFalse(hasSprite(requisition.dossierBody(), blue), "a late image for the first player stays out");

            sprites.put(redKey, red);
            requisition.spriteArrived(redKey);
            frame(ui);
            assertPreviews(requisition, red);
            UiSelect owner = body.findActor("lobby-requisition-owner");
            owner.select(0);
            owner.fire(new ChangeListener.ChangeEvent());
            frame(ui);
            assertPreviews(requisition, blue);
            requisition.recipient(MY_BOT);
            frame(ui);
            assertPreviews(requisition, red);
            assertEquals(1, requested.size(), "returning to cached camouflages needs no new drawing");

            requisition.show(recipients("green"), LobbyForces.EMPTY);
            frame(ui);
            assertEquals(2, requested.size(), "editing the selected recipient's camo requests a fresh preview");
            assertFalse(hasSprite(requisition.dossierBody(), red));
            requisition.spriteArrived(redKey);
            frame(ui);
            assertFalse(hasSprite(requisition.dossierBody(), red), "an earlier camo cannot replace the new one");
            sprites.put(greenKey, green);
            requisition.spriteArrived(greenKey);
            frame(ui);
            assertPreviews(requisition, green);
            body.remove();
        });
    }

    private static LobbyRequisition recipients(String botCamo) {
        return new LobbyRequisition(RequisitionCatalog.Rules.OPEN,
              List.of(new Recipient(ME, "Me", true, false, "blue"),
                    new Recipient(MY_BOT, "Princess", false, true, botCamo)), false, List.of());
    }

    private static void frame(UiTestStage ui) {
        for (int i = 0; i < 3; i++) {
            ui.draw();
        }
    }

    private static void assertPreviews(LobbyRequisitionPanel requisition, Drawable sprite) {
        assertTrue(hasSprite(requisition.resultsList(), sprite), "the result row uses the recipient's sprite");
        assertTrue(hasSprite(requisition.dossierBody(), sprite), "the detail preview uses the recipient's sprite");
    }

    private static boolean hasSprite(Actor actor, Drawable sprite) {
        if (actor instanceof Image image && image.getDrawable() == sprite) {
            return true;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                if (hasSprite(child, sprite)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LobbySnapshot snapshot(LobbyRequisition requisition) {
        TeamGroup mine = new TeamGroup(1, "Team 1", List.of(row(ME, "Me", 1, false, true),
              row(MY_BOT, "Princess", 1, true, true)));
        TeamGroup theirs = new TeamGroup(2, "Team 2", List.of(row(ENEMY, "Enemy", 2, false, false)));
        return new LobbySnapshot(ME, false, false, LobbyGameMaster.OFF, List.of(mine, theirs), List.of(), List.of(), "",
              "", LobbyForces.EMPTY, LobbyBattlefield.EMPTY, LobbyReadiness.EMPTY, requisition);
    }

    private static PlayerRow row(int id, String name, int team, boolean bot, boolean local) {
        return new PlayerRow(id, name, team, bot, id == ME, local, false, false, false, false, false, 0, 0, 0, "Any",
              true, 0x3366AA, "");
    }

    private static Table place(UiTestStage ui, Table table, float x, float width) {
        ui.window.addActor(table);
        table.setBounds(x, 60, width, 820);
        table.validate();
        ui.draw();
        return table;
    }

    /** Clicks a player card on its name line, clear of the team and deployment buttons below it. */
    private static void clickName(UiTestStage ui, Actor card) {
        press(ui, card.localToStageCoordinates(new Vector2(card.getWidth() / 2, card.getHeight() - 10)));
    }

    private static void click(UiTestStage ui, Actor actor) {
        press(ui, actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }

    private static void press(UiTestStage ui, Vector2 point) {
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private record PlayerActions(Consumer<Integer> picked) implements LobbyPlayersPanel.Actions {
        @Override
        public void pick(int id) {
            picked.accept(id);
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

    private static class RequisitionActions implements LobbyRequisitionPanel.Actions {
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
