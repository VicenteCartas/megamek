/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.BalancedArmy;
import megamek.client.lobby.BattleValueArmy;
import megamek.client.lobby.FactionChoice;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Player;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.enums.SkillLevel;
import megamek.common.game.Game;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the L17 lobby tools through the stage: the victory cards' switches and steppers, the random army's roll, keep
 * and add, the skills' save, the readout's tabs and units, and the prompts' suggestions, steppers and order buttons.
 */
@Tag("on-demand")
class LobbyDialogsL17SmokeTest {

    private static final LobbyPromptViews.Edt EDT = (work, then) -> {
        work.run();
        then.run();
    };

    @Test
    void theVictoryCardsSwitchAndStepAndSendTheEdits() {
        UiTestStage.run(ui -> {
            GameOptions options = new GameOptions();
            options.getOption(OptionsConstants.VICTORY_USE_KILL_COUNT).setValue(false);
            options.getOption(OptionsConstants.VICTORY_GAME_KILL_COUNT).setValue(4);
            VictoryConditionsForm form = new VictoryConditionsForm(options);
            List<VictoryConditionsForm> sent = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.victory(form, new LobbyVictoryView.Host() {
                @Override
                public void send(VictoryConditionsForm sending) {
                    sent.add(sending);
                }

                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }
            });
            ui.draw();
            String kills = "victory-" + OptionsConstants.VICTORY_GAME_KILL_COUNT.replace('_', '-');
            click(ui, find(ui, "victory-" + OptionsConstants.VICTORY_USE_KILL_COUNT.replace('_', '-')));
            assertTrue(form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT), "the switch arms the card");
            TextField count = (TextField) find(ui, kills);
            click(ui, count);
            count.setCursorPosition(count.getText().length());
            ui.stage.keyTyped('\b');
            ui.stage.keyTyped('9');
            ui.draw();
            assertEquals(9, form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT), "the typed count is taken");
            click(ui, find(ui, "victory-ok"));
            assertEquals(List.of(form), sent, "OK sends the form");
            assertFalse(dialogs.isOpen());
            dialogs.dispose();
        });
    }

    @Test
    void theRandomArmyRollsKeepsAndAdds() {
        UiTestStage.run(ui -> {
            int[] serial = {0};
            RandomArmyForm form = new RandomArmyForm(List.of("Table", "Davion Heavy", "Davion Light"), "Table",
                  List.of("Me"), (count, table) -> {
                List<MekSummary> units = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    MekSummary unit = new MekSummary();
                    unit.setName("Unit " + ++serial[0]);
                    unit.setChassis("Unit");
                    unit.setModel(String.valueOf(serial[0]));
                    unit.setBV(1000);
                    unit.setTons(50);
                    units.add(unit);
                }
                return units;
            });
            form.count(2);
            RatBrowser.Folder davion = new RatBrowser.Folder("Davion", List.of(),
                  List.of("Davion Heavy", "Davion Light"));
            RatBrowser.Folder source = new RatBrowser.Folder("(3039) - War of 39", List.of(davion), List.of());
            RatBrowser browser = new RatBrowser(new RatBrowser.Folder("RATs", List.of(source), List.of("Table")));
            List<String> said = new ArrayList<>();
            boolean[] hold = {false};
            List<Runnable> held = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.randomArmy(form, browser, generators(), key -> null, new LobbyRandomArmyView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    if (hold[0]) {
                        held.add(() -> {
                            work.run();
                            then.run();
                        });
                        return;
                    }
                    work.run();
                    then.run();
                }

                @Override
                public void add(RandomArmyForm adding) {
                    said.add("add " + adding.army().size());
                }

                @Override
                public void sprites(List<MekSummary> units) {
                }

                @Override
                public void factions(Consumer<LobbyRandomArmyView.Factions> ready) {
                }

                @Override
                public void remember(FactionChoice factions, int unitType) {
                }

                @Override
                public void readout(MekSummary unit) {
                    said.add("readout " + unit.getName());
                }
            });
            ui.draw();
            // The browser: a folder opens, a table is chosen, the back button goes up, a search finds across all
            click(ui, find(ui, "army-folder-0"));
            assertEquals(List.of("War of 39"), browser.path(), "a folder row opens the folder");
            click(ui, find(ui, "army-folder-0"));
            click(ui, find(ui, "army-rat-0"));
            assertEquals("Davion Heavy", form.table(), "a table row chooses the table");
            assertTrue(((UiButton) find(ui, "army-rat-0")).isChecked(), "the chosen table's row is marked");
            assertEquals("Davion Heavy", ((Label) find(ui, "army-chosen")).getText().toString());
            click(ui, find(ui, "army-up"));
            assertEquals(List.of("War of 39"), browser.path(), "back goes up a folder");
            click(ui, find(ui, "army-search-field"));
            for (char character : "light".toCharArray()) {
                ui.stage.keyTyped(character);
            }
            ui.draw();
            assertTrue(browser.searching(), "typing searches");
            assertEquals(1, browser.matches());
            click(ui, find(ui, "army-rat-0"));
            assertEquals("Davion Light", form.table(), "a search result chooses its table");
            click(ui, find(ui, "army-up"));
            assertFalse(browser.searching(), "back ends the search");
            assertEquals("", ((UiKit.TokenSearch) find(ui, "army-search")).field.getText());
            form.table("Table");
            click(ui, find(ui, "army-roll"));
            assertEquals(2, form.cards().size(), "Roll deals the unit count");
            click(ui, find(ui, "army-keep-0"));
            assertTrue(form.cards().get(0).kept(), "Keep holds the card");
            MekSummary kept = form.cards().get(0).unit();
            click(ui, find(ui, "army-roll"));
            assertEquals(kept, form.cards().get(0).unit(), "a kept card survives the roll");
            // While a reroll runs on the Swing thread, nothing else may change the hand under it
            hold[0] = true;
            click(ui, find(ui, "army-reroll-1"));
            assertEquals(1, held.size(), "the reroll waits on the Swing thread");
            for (String name : List.of("army-keep-0", "army-discard-0", "army-discard-1", "army-clear")) {
                assertTrue(((UiButton) find(ui, name)).isDisabled(), name + " is disabled while rolling");
                click(ui, find(ui, name));
            }
            assertEquals(2, form.cards().size(), "nothing was discarded or cleared mid-roll");
            assertTrue(form.cards().get(0).kept(), "the kept card stayed kept mid-roll");
            hold[0] = false;
            held.remove(0).run();
            assertFalse(((UiButton) find(ui, "army-discard-1")).isDisabled(), "the hand opens again after the roll");
            assertEquals(kept, form.cards().get(0).unit());
            click(ui, find(ui, "army-discard-1"));
            assertEquals(1, form.cards().size(), "Discard drops the card");
            click(ui, find(ui, "army-readout-0"));
            assertEquals(List.of("readout " + kept.getName()), said, "a card opens its unit's readout");
            said.clear();
            dialogs.readoutOver(List.of(new LobbyReadoutView.Dossier(kept.getName(), "", null,
                  List.of(List.of(), List.of(), List.of(), List.of()))));
            ui.draw();
            assertNotNull(dialogs.readoutView(), "the readout shows");
            assertNotNull(dialogs.randomArmyView(), "the random army stays under its readout");
            dialogs.cancel();
            ui.draw();
            assertNull(dialogs.readoutView(), "closing the readout leaves the random army");
            assertEquals(1, form.cards().size(), "the hand is still there");
            click(ui, find(ui, "army-add"));
            assertEquals(List.of("add 1"), said);
            dialogs.dispose();
        });
    }

    @Test
    void theRandomArmyGeneratorsDealByTheirChoices() {
        UiTestStage.run(ui -> {
            RandomArmyForm form = new RandomArmyForm(List.of("Table"), "Table", List.of("Me"),
                  (count, table) -> List.of());
            form.count(2);
            RatBrowser browser = new RatBrowser(new RatBrowser.Folder("RATs", List.of(), List.of("Table")));
            int[] asked = {0};
            List<Integer> remembered = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyRandomArmyView view = dialogs.randomArmy(form, browser, generators(), key -> null,
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
                      }

                      @Override
                      public void factions(Consumer<LobbyRandomArmyView.Factions> ready) {
                          asked[0]++;
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
                          remembered.add(tab);
                      }
                  });
            ui.draw();
            assertEquals(1, asked[0], "the faction generators are asked for once");
            assertEquals(LobbyRandomArmyView.Mode.BATTLE_VALUE, view.mode(),
                  "the army opens on the generator last dealt by, the Swing random army's tab 0");

            // Battle Value: its own counts, so no unit count; the whole army within the budget
            click(ui, find(ui, "army-mode-battleValue"));
            assertEquals(LobbyRandomArmyView.Mode.BATTLE_VALUE, view.mode());
            assertTrue(((UiButton) find(ui, "army-mode-battleValue")).isChecked(), "the mode's tile is lit");
            assertNull(ui.stage.getRoot().findActor("army-count"), "the army counts its own units");
            click(ui, find(ui, "army-bv-mek-down"));
            click(ui, find(ui, "army-roll"));
            assertEquals(3, form.cards().size(), "Roll deals the counted units");
            assertNotNull(find(ui, "army-keep-0"), "the army's cards are kept one by one");

            // The unit filter takes the random army's place until Done
            click(ui, find(ui, "army-filter"));
            assertTrue(view.filtering(), "the unit filter shows");
            click(ui, find(ui, "lobby-requisition-advanced-done"));
            assertFalse(view.filtering(), "Done goes back to the random army");

            // Balanced: two units at 2000 BV each make the 4000 target
            click(ui, find(ui, "army-clear"));
            click(ui, find(ui, "army-mode-balanced"));
            assertNotNull(find(ui, "army-count"), "a balanced lance has the unit count");
            click(ui, find(ui, "army-roll"));
            assertEquals(2, form.cards().size(), "Roll deals the balanced lance");

            // The faction generators wait for the RAT Generator
            click(ui, find(ui, "army-mode-faction"));
            assertNotNull(find(ui, "army-loading"), "the pane says the factions load");
            assertTrue(((UiButton) find(ui, "army-roll")).isDisabled(), "nothing deals while they load");
            // The force generator waits for them too, and nothing goes to the game meanwhile
            click(ui, find(ui, "army-mode-force"));
            assertEquals(LobbyRandomArmyView.Mode.FORCE, view.mode());
            assertNotNull(find(ui, "army-loading"), "the force generator's pane says the factions load");
            assertTrue(((UiButton) find(ui, "army-add")).isDisabled(), "no command, nothing to add");
            assertNull(view.forcePane(), "the command shows once the generators have arrived");
            click(ui, find(ui, "army-mode-tables"));
            assertEquals(LobbyRandomArmyView.Mode.TABLES, view.mode());
            assertNotNull(find(ui, "army-browser"), "the tables come back");
            assertEquals(List.of(0, 1, 3, 5, 2), remembered, "each mode chosen is remembered as its Swing tab");
            dialogs.dispose();
        });
    }

    /** A Battle Value army and a balanced lance dealt from six Meks of 2000 BV, designed in 3040. */
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

    @Test
    void theSkillsSaveTheChosenLevel() {
        UiTestStage.run(ui -> {
            Client client = mock(Client.class);
            when(client.getName()).thenReturn("Me");
            var generator = SkillGeneratorMethod.TOTAL_WARFARE.getGenerator();
            when(client.getSkillGenerator()).thenReturn(generator);
            RandomSkillsForm form = new RandomSkillsForm(List.of(client));
            List<String> said = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.skills(form, new LobbyCrewToolsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void save() {
                    said.add("save");
                    form.save();
                }

                @Override
                public void randomize() {
                    said.add("randomize");
                }
            });
            ui.draw();
            click(ui, find(ui, "skills-force-close"));
            assertTrue(form.forceClose(), "the check sets force close");
            form.level(SkillLevel.ELITE);
            click(ui, find(ui, "skills-save"));
            assertEquals(List.of("save"), said);
            assertEquals(SkillLevel.ELITE, generator.getLevel());
            assertFalse(dialogs.isOpen(), "Save closes the tool");
            dialogs.dispose();
        });
    }

    @Test
    void theReadoutTurnsItsPagesAndUnits() {
        UiTestStage.run(ui -> {
            List<EntityReadout.Row> rows = List.of(
                  new EntityReadout.Row(EntityReadout.Row.Kind.LABELED, List.of("Tonnage", "80")),
                  new EntityReadout.Row(EntityReadout.Row.Kind.TEXT, List.of("A line")));
            LobbyReadoutView.Dossier first = new LobbyReadoutView.Dossier(1, "First", "80 t", null,
                  List.of(rows, rows, rows, rows, rows, rows), new BufferedImage(64, 90, BufferedImage.TYPE_INT_ARGB));
            LobbyReadoutView.Dossier second = new LobbyReadoutView.Dossier(2, "Second", "80 t", null,
                  List.of(rows, rows, rows, rows, rows, rows), new BufferedImage(64, 90, BufferedImage.TYPE_INT_ARGB));
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyReadoutView view = dialogs.readout(List.of(first, second));
            ui.draw();
            assertNotNull(view.tabs());
            click(ui, find(ui, "readout-next"));
            assertEquals(1, view.shown(), "Next shows the next unit");
            view.page(LobbyReadoutView.Page.ALPHA_STRIKE);
            ui.draw();
            assertNotNull(find(ui, "readout-card"), "the Alpha Strike page shows the card");

            LobbyReadoutView.Dossier renamed = new LobbyReadoutView.Dossier(2, "Renamed", "85 t", null,
                  List.of(rows, rows, rows, rows, rows, rows), null);
            view.update(List.of(first, renamed));
            ui.draw();
            assertEquals(1, view.shown(), "an update stays on the unit that shows");
            assertEquals(LobbyReadoutView.Page.ALPHA_STRIKE.ordinal(), view.tabs().getSelectedIndex(),
                  "and on its tab");
            assertEquals("Renamed", ((Label) find(ui, "readout-name")).getText().toString());
            view.update(List.of(first));
            assertEquals(0, view.shown(), "a unit that has gone gives way to the first");
            click(ui, find(ui, "readout-close"));
            assertFalse(dialogs.isOpen());
            dialogs.dispose();
        });
    }

    @Test
    void theFactionsPageIsReadOnlyWhenItFirstShows() {
        UiTestStage.run(ui -> {
            List<EntityReadout.Row> rows = List.of(
                  new EntityReadout.Row(EntityReadout.Row.Kind.TEXT, List.of("A line")));
            List<Runnable> asked = new ArrayList<>();
            List<List<EntityReadout.Row>> factions = new ArrayList<>();
            LobbyReadoutView.LaterRows later = new LobbyReadoutView.LaterRows() {
                @Override
                public List<EntityReadout.Row> rows() {
                    return factions.isEmpty() ? null : factions.get(0);
                }

                @Override
                public void read(Runnable done) {
                    asked.add(done);
                }
            };
            LobbyReadoutView.Dossier unit = new LobbyReadoutView.Dossier(1, "Unit", "80 t", null,
                  List.of(rows, rows, rows, rows, rows), null, later);
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyReadoutView view = dialogs.readout(List.of(unit, unit));
            ui.draw();
            assertTrue(asked.isEmpty(), "nothing is read before the page shows");
            view.page(LobbyReadoutView.Page.FACTIONS);
            ui.draw();
            assertNotNull(find(ui, "readout-loading"), "the page says it is reading");
            assertEquals(1, asked.size());

            factions.add(List.of(new EntityReadout.Row(EntityReadout.Row.Kind.TEXT, List.of("Davion"))));
            view.page(LobbyReadoutView.Page.FACTIONS);
            ui.draw();
            assertNull(ui.stage.getRoot().findActor("readout-loading"), "the read rows replace the note");
            assertEquals(1, asked.size(), "rows once read are not read again");
            dialogs.dispose();
        });
    }

    @Test
    void thePromptsAnswerThroughTheirControls() throws Exception {
        Game game = new Game();
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            List<Object> said = new ArrayList<>();

            ForceNameRequest request = ForceNameRequest.of(game, local, "");
            dialogs.forceName(request, EDT, said::add);
            ui.draw();
            Actor suggestion = find(ui, "prompt-suggestion-0");
            click(ui, suggestion);
            String picked = ((TextField) find(ui, "prompt-name")).getText();
            assertFalse(picked.isBlank(), "a suggestion fills the name");
            click(ui, find(ui, "prompt-ok"));
            assertEquals(List.of(picked), said);
            assertFalse(dialogs.isOpen());

            BattlefieldSupportAsset asset = new BattlefieldSupportAsset();
            asset.setODestroyCheck(6);
            asset.setDestroyCheck(6);
            dialogs.assetDamage(new AssetForm(asset, true), EDT, () -> said.add("damage"));
            ui.draw();
            click(ui, find(ui, "prompt-destroy-check-down"));
            click(ui, find(ui, "prompt-ok"));
            assertEquals(5, asset.getDestroyCheck(), "the stepper lowers the Destroy Check");
            assertEquals("damage", said.get(said.size() - 1));

            Entity head = unit("Tractor");
            Entity a = unit("A");
            Entity b = unit("B");
            dialogs.trainOrder(new TrainOrderForm(head, List.of(a, b)), EDT, said::add);
            ui.draw();
            click(ui, find(ui, "prompt-down-0"));
            click(ui, find(ui, "prompt-ok"));
            assertEquals(List.of(b, a), said.get(said.size() - 1), "the order buttons reorder the train");

            dialogs.squadron(EDT, said::add);
            ui.draw();
            assertTrue(((com.badlogic.gdx.scenes.scene2d.ui.Button) find(ui, "prompt-ok")).isDisabled(),
                  "a blank designation cannot be OKed");
            click(ui, find(ui, "prompt-cancel"));
            assertFalse(dialogs.isOpen());

            int[] yes = {0};
            dialogs.confirm("Title", "Sure?", () -> yes[0]++);
            ui.draw();
            click(ui, find(ui, "confirm-ok"));
            assertEquals(1, yes[0]);
            dialogs.dispose();
        });
    }

    private static Entity unit(String name) {
        Entity unit = mock(Entity.class);
        when(unit.getShortName()).thenReturn(name);
        return unit;
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
