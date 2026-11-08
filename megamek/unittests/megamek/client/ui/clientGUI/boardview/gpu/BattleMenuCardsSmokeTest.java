/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.MMConstants;
import megamek.client.Client;
import megamek.client.lobby.BalancedArmy;
import megamek.client.lobby.BattleValueArmy;
import megamek.client.lobby.FactionChoice;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opens the cards the battle's menus show over the battle window instead of their Swing dialogs, as the battle's own
 * cards on its HUD: the game options to read only, the help, and the random army's force generator for reinforcements.
 */
@Tag("on-demand")
class BattleMenuCardsSmokeTest {

    @Test
    void theBattleMenusShowTheirCardsOverTheBattle() {
        UiTestStage.run(ui -> {
            Game game = new Game();
            game.setPhase(GamePhase.MOVEMENT);
            ClientGUI gui = mock(ClientGUI.class);
            Client client = mock(Client.class);
            when(gui.getClient()).thenReturn(client);
            when(client.getGame()).thenReturn(game);
            LobbyNativeDialogs cards = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            GpuBattleTools tools = new GpuBattleTools(gui, Runnable::run, () -> false, () -> cards);

            // The game options read only: no OK, no defaults or loading, the controls disabled; Save stays
            tools.gameOptions();
            ui.draw();
            assertTrue(cards.isOpen(), "the game options show over the battle");
            assertTrue(((UiButton) find(ui, "options-ok")).isDisabled(), "the battle's game options are read only");
            assertFalse(find(ui, "options-defaults").isVisible(), "no defaults in the battle");
            assertFalse(find(ui, "options-load").isVisible(), "no loading in the battle");
            assertFalse(((UiButton) find(ui, "options-save")).isDisabled(), "the options can still be saved");
            click(ui, find(ui, "options-cancel"));
            assertFalse(cards.isOpen());

            // The help, a chapter at a time, and the skinning how-to
            tools.help(Messages.getString("CommonMenuBar.HelpMenu"), new File(MMConstants.MEGAMEK_README_FILE_PATH));
            ui.draw();
            find(ui, "document-chapter-0");
            click(ui, find(ui, "document-close"));
            tools.help(Messages.getString("ClientGUI.skinningHelpPath.title"),
                  new File(Messages.getString("ClientGUI.skinningHelpPath")));
            ui.draw();
            find(ui, "document-page");
            assertNull(ui.stage.getRoot().findActor("document-rail"), "the how-to is a single chapter");
            click(ui, find(ui, "document-close"));
            assertFalse(cards.isOpen());

            // The random army, as the battle opens it for reinforcements, and its force generator
            RandomArmyForm form = new RandomArmyForm(List.of("Table"), "Table", List.of("Me"),
                  (count, table) -> List.of());
            RatBrowser browser = new RatBrowser(new RatBrowser.Folder("RATs", List.of(), List.of("Table")));
            LobbyRandomArmyView view = cards.randomArmy(form, browser, generators(), tools::requisitionSprite,
                  new LobbyRandomArmyView.Host() {
                      @Override
                      public void edt(Runnable work, Runnable then) {
                          work.run();
                          then.run();
                      }

                      @Override
                      public void add(RandomArmyForm adding) {
                      }

                      @Override
                      public void sprites(List<MekSummary> units) {
                          tools.requisitionSprites(units);
                      }

                      @Override
                      public void factions(Consumer<LobbyRandomArmyView.Factions> ready) {
                      }

                      @Override
                      public void remember(FactionChoice factions, int unitType) {
                      }

                      @Override
                      public int lastTab() {
                          return 0;
                      }

                      @Override
                      public void lastTab(int tab) {
                      }
                  });
            ui.draw();
            click(ui, find(ui, "army-mode-force"));
            assertEquals(LobbyRandomArmyView.Mode.FORCE, view.mode(), "the force generator shows");
            assertNull(tools.requisitionSprite("Mek 0"), "the battle draws no requisition sprites");
            assertNotNull(find(ui, "army-add"));
            cards.dispose();
        });
    }

    /** Six Meks of 2000 BV, designed in 3040. */
    private static LobbyRandomArmyView.Generators generators() {
        MekSummary[] roster = new MekSummary[6];
        for (int i = 0; i < roster.length; i++) {
            MekSummary unit = new MekSummary();
            unit.setName("Mek " + i);
            unit.setChassis("Mek");
            unit.setModel(String.valueOf(i));
            unit.setAsUnitType(ASUnitType.BM);
            unit.setUnitType(UnitType.getTypeName(UnitType.MEK));
            unit.setBV(2000);
            unit.setTons(50);
            unit.setYear(3040);
            roster[i] = unit;
        }
        return new LobbyRandomArmyView.Generators(new BattleValueArmy(() -> roster, 3050),
              new BalancedArmy(() -> roster));
    }

    private static Actor find(UiTestStage ui, String name) {
        Supplier<Actor> lookup = () -> ui.stage.getRoot().findActor(name);
        for (int i = 0; i < 20 && lookup.get() == null; i++) {
            ui.draw();
        }
        Actor actor = lookup.get();
        assertNotNull(actor, name);
        return actor;
    }

    private static void click(UiTestStage ui, Actor actor) {
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            pane.layout();
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
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
