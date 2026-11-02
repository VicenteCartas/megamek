/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.PriorityTargetChoices.Candidate;
import megamek.client.bot.princess.PriorityTargetChoices.ForceRole;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotTargetPicker;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.containers.MunitionTree;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the lobby's libGDX dialogs through the stage, as a pointer and keyboard would: the kit's dialog blocks the
 * lobby behind it and answers Enter, Escape and Tab; the camo chooser hands back the clicked camo; the player settings
 * refuse a bad number and send a chosen start position; the planetary conditions follow their rules and send what was
 * chosen; the game options search, mark a change, ask before hiding the unofficial rules and send the changes.
 */
@Tag("on-demand")
class LobbyNativeDialogsSmokeTest {

    @Test
    void theDialogBlocksWhatIsBehindItAndAnswersTheKeys() {
        UiTestStage.run(ui -> {
            int[] behind = { 0 };
            Actor lobby = new Actor();
            lobby.setBounds(0, 0, ui.stage.getWidth(), ui.stage.getHeight());
            lobby.setTouchable(Touchable.enabled);
            lobby.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    behind[0]++;
                }
            });
            ui.window.addActor(lobby);
            List<String> said = new ArrayList<>();
            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> said.add("cancel")).width("dialog.sm");
            dialog.body().add(new Actor()).size(200, 40);
            dialog.primary("OK", () -> said.add("ok"));
            Button cancel = dialog.cancelButton("Cancel");
            dialog.show(ui.stage, false);
            ui.draw();
            assertTrue(dialog.isOpen());

            clickAt(ui, 4, 4);
            assertEquals(0, behind[0], "the lobby behind takes no click");
            assertTrue(dialog.isOpen(), "a click on the scrim does not dismiss a dialog that wants an answer");

            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals(List.of("ok"), said, "Enter presses OK");

            for (int i = 0; i < 6; i++) {
                ui.stage.keyDown(Input.Keys.TAB);
                Actor focus = ui.stage.getKeyboardFocus();
                assertNotNull(focus);
                assertTrue(focus.isDescendantOf(dialog.card()), "Tab stays in the dialog");
            }
            ui.stage.setKeyboardFocus(cancel);
            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals("cancel", said.getLast(), "Enter presses the focused button");
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertEquals(List.of("ok", "cancel", "cancel"), said, "Escape cancels");

            dialog.close();
            ui.draw();
            assertFalse(dialog.isOpen());
            click(ui, lobby);
            assertEquals(1, behind[0], "the lobby takes clicks again once it closes");
        });
    }

    @Test
    void theCamoChooserHandsBackTheClickedCamo() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            CamoCatalog catalog = new CamoCatalog(null, List.of("BLUE", "CYAN", "RED"));
            BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            LobbyCamoView.Source source = new LobbyCamoView.Source() {
                @Override
                public CamoCatalog catalog(boolean refresh) {
                    return catalog;
                }

                @Override
                public BufferedImage thumbnail(Camouflage camo, int size) {
                    return picture;
                }

                @Override
                public List<BufferedImage> preview(Camouflage camo) {
                    return List.of(picture);
                }
            };
            List<Camouflage> chosen = new ArrayList<>();
            CamoChoice choice = new CamoChoice(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "CYAN"), false);
            LobbyCamoView view = dialogs.camo(source, catalog, choice, false, chosen::add);
            await(ui, () -> view.previewShown() && view.item(0) != null);
            assertTrue(dialogs.isOpen());
            assertEquals(3, view.items().size(), "the colour camos show, as the initial camo's category");

            click(ui, view.item(0));
            assertEquals(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "BLUE"), view.choice().selected());
            click(ui, ui.stage.getRoot().findActor("camo-ok"));
            assertFalse(dialogs.isOpen(), "OK closes the chooser");
            assertEquals(1, chosen.size());
            assertEquals("BLUE", chosen.getFirst().getFilename());
            dialogs.dispose();
        });
    }

    @Test
    void thePlayerSettingsRefuseABadNumberAndSendTheStartPosition() {
        UiTestStage.run(ui -> {
            Game game = new Game();
            Player local = new Player(1, "Local");
            game.addPlayer(1, local);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(local);
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardSize(16, 17);
            when(client.getMapSettings()).thenReturn(settings);
            when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
            PlayerSettingsForm form = new PlayerSettingsForm(client,
                  new LobbyCommands(() -> client, mock(LobbyPrompts.class)), board(),
                  List.of(new PlayerSettingsForm.Faction("IS", "Inner Sphere")));
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.playerSettings(form, form.positions(), null, new LobbyPlayerSettingsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void saveLoadout(MunitionTree tree, Runnable then) {
                    then.run();
                }

                @Override
                public void loadLoadout(Consumer<MunitionTree> then) {
                    then.accept(null);
                }

                @Override
                public void botSettings() {
                }
            });
            ui.draw();
            Actor start = ui.stage.getRoot().findActor("settings-start-5");
            reveal(ui, start);
            click(ui, start);
            assertEquals(5, form.startPos());

            Actor offset = ui.stage.getRoot().findActor("settings-offset");
            reveal(ui, offset);
            click(ui, offset);
            for (int i = 0; i < 4; i++) {
                ui.stage.keyTyped('\b');
            }
            ui.draw();
            Button ok = ui.stage.getRoot().findActor("settings-ok");
            assertTrue(ok.isDisabled(), "an empty number disables OK");
            click(ui, ok);
            verify(client, never()).sendPlayerInfo(local);

            ui.stage.setKeyboardFocus(offset);
            ui.stage.keyTyped('2');
            ui.draw();
            assertFalse(ok.isDisabled());
            click(ui, ok);
            verify(client).sendPlayerInfo(local);
            assertEquals(5, local.getStartingPos());
            assertEquals(2, local.getStartOffset());
            assertFalse(dialogs.isOpen(), "OK closes the settings");
            dialogs.dispose();
        });
    }

    @Test
    void theBotSetupHandsBackWhatWasClicked() {
        UiTestStage.run(ui -> {
            Map<String, BehaviorSettings> stored = new TreeMap<>();
            BehaviorSettings berserk = new BehaviorSettings();
            berserk.setDescription("BERSERK");
            berserk.setBraveryIndex(9);
            berserk.setHyperAggressionIndex(9);
            stored.put("BERSERK", berserk);
            BotSetupForm.Presets presets = new BotSetupForm.Presets() {
                @Override
                public List<String> names() {
                    return List.copyOf(stored.keySet());
                }

                @Override
                public BehaviorSettings get(String name) {
                    return stored.get(name);
                }

                @Override
                public void save(BehaviorSettings preset) {
                    stored.put(preset.getDescription(), preset);
                }

                @Override
                public void remove(String name) {
                    stored.remove(name);
                }
            };
            BotTargetPicker picker = new BotTargetPicker(true, List.of(
                  new Candidate(1, "Atlas", 5, "Enemy", 2, 10, "Lance", ForceRole.NONE, true, false),
                  new Candidate(2, "Locust", 5, "Enemy", 2, 10, "Lance", ForceRole.NONE, true, false)), Map.of(),
                  Map.of());
            BotSetupForm form = new BotSetupForm(null, "Princess", Set.of("Local"), new BehaviorSettings(), null,
                  false, false, presets, Map.of(), null, picker);
            List<LobbyPlayerController.BotSetup> confirmed = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.botSetup(form, new LobbyBotSetupView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void help() {
                }

                @Override
                public void confirm(LobbyPlayerController.BotSetup setup) {
                    confirmed.add(setup);
                }
            });
            ui.draw();

            click(ui, find(ui, "bot-preset-stored-BERSERK"));
            Slider bravery = find(ui, "bot-slider-bravery");
            assertEquals(9, Math.round(bravery.getValue()), "the preset shows in the sliders");
            Button save = find(ui, "bot-save");
            assertTrue(save.isDisabled(), "an unchanged preset has nothing to save");
            Slider aggression = find(ui, "bot-slider-aggression");
            reveal(ui, aggression);
            click(ui, aggression);
            assertEquals(5, form.value(BotSetupForm.Slider.AGGRESSION), "a click on the slider's middle sets it");
            assertFalse(save.isDisabled(), "a changed preset may be saved");
            Actor pirate = find(ui, "bot-flag-pirate");
            reveal(ui, pirate);
            click(ui, pirate);

            Actor name = find(ui, "bot-name");
            reveal(ui, name);
            click(ui, name);
            ui.stage.keyTyped('X');
            ui.draw();

            click(ui, find(ui, "bot-tab-targets"));
            click(ui, find(ui, "bot-tick-force-5:10"));
            ui.draw();
            assertEquals("2", ((Label) find(ui, "bot-target-count")).getText().toString().replaceAll("\\D", ""));
            Actor hex = find(ui, "bot-hex-coords");
            reveal(ui, hex);
            click(ui, hex);
            for (char typed : "3, 4".toCharArray()) {
                ui.stage.keyTyped(typed);
            }
            Actor add = find(ui, "bot-hex-add");
            click(ui, add);
            assertEquals(1, form.hexTargets().size());

            UiDialog setup = dialogs.dialog();
            dialogs.message("Note", "Over the setup");
            ui.draw();
            click(ui, find(ui, "message-ok"));
            assertTrue(setup.isOpen(), "a message over the setup closes alone");
            assertTrue(dialogs.dialog() == setup);

            click(ui, find(ui, "bot-ok"));
            assertFalse(dialogs.isOpen(), "OK closes the setup");
            assertEquals(1, confirmed.size());
            LobbyPlayerController.BotSetup chosen = confirmed.getFirst();
            assertEquals("PrincessX", chosen.name());
            assertEquals(AIType.PRINCESS, chosen.type());
            assertEquals(9, chosen.behavior().getBraveryIndex());
            assertEquals(5, chosen.behavior().getHyperAggressionIndex());
            assertTrue(chosen.behavior().iAmAPirate());
            assertEquals(Set.of(1, 2), chosen.behavior().getPriorityUnitTargets());
            assertEquals(Set.of(new Coords(2, 3).toString()), chosen.behavior().getStrategicBuildingTargets());
            assertEquals("BERSERK", chosen.behavior().getDescription());
            dialogs.dispose();
        });
    }

    @Test
    void theConditionsFollowTheirRulesAndSendWhatWasChosen() {
        UiTestStage.run(ui -> {
            PlanetaryConditionsForm form = new PlanetaryConditionsForm(new PlanetaryConditions());
            List<PlanetaryConditions> sent = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.conditions(form, new LobbyConditionsView.Host() {
                @Override
                public void send(PlanetaryConditions conditions) {
                    sent.add(conditions);
                }

                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }
            });
            ui.draw();

            UiSelect weather = find(ui, "conditions-weather");
            reveal(ui, weather);
            click(ui, weather);
            ui.stage.keyDown(Input.Keys.DOWN);
            ui.stage.keyDown(Input.Keys.ENTER);
            ui.draw();
            assertEquals(Weather.values()[1], form.weather(), "the weather list's next entry is chosen");

            UiSelect atmosphere = find(ui, "conditions-atmosphere");
            reveal(ui, atmosphere);
            click(ui, atmosphere);
            ui.stage.keyDown(Input.Keys.HOME);
            ui.stage.keyDown(Input.Keys.ENTER);
            ui.draw();
            assertEquals(Atmosphere.VACUUM, form.atmosphere());
            assertTrue(weather.isDisabled(), "a vacuum has no weather");
            assertEquals(Weather.CLEAR, form.weather(), "a vacuum clears the weather");

            Actor temperature = find(ui, "conditions-temperature");
            reveal(ui, temperature);
            click(ui, temperature);
            for (int i = 0; i < 4; i++) {
                ui.stage.keyTyped('\b');
            }
            for (char typed : "500".toCharArray()) {
                ui.stage.keyTyped(typed);
            }
            ui.draw();
            Button ok = find(ui, "conditions-ok");
            assertFalse(form.valid());
            assertTrue(ok.isDisabled(), "a temperature out of range disables OK");
            click(ui, ok);
            assertTrue(sent.isEmpty());

            ui.stage.setKeyboardFocus(temperature);
            ui.stage.keyTyped('\b');
            ui.stage.keyTyped('\b');
            ui.draw();
            assertFalse(ok.isDisabled());
            click(ui, ok);
            assertEquals(1, sent.size());
            assertEquals(5, sent.getFirst().getTemperature());
            assertEquals(Atmosphere.VACUUM, sent.getFirst().getAtmosphere());
            assertFalse(dialogs.isOpen(), "OK closes the conditions");
            dialogs.dispose();
        });
    }

    @Test
    void theGameOptionsSearchMarkAskAndSendTheChanges() {
        UiTestStage.run(ui -> {
            GameOptionsForm form = new GameOptionsForm(new GameOptions(), Set.of(), true);
            List<Vector<IBasicOption>> sent = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyGameOptionsView view = dialogs.gameOptions(form, new LobbyGameOptionsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void send(String password, Vector<IBasicOption> changed, Vector<IBasicOption> all) {
                    sent.add(changed);
                }

                @Override
                public void save(Vector<IBasicOption> all, Runnable then) {
                    then.run();
                }

                @Override
                public void load(GameOptions into, Consumer<Boolean> then) {
                    then.accept(false);
                }

                @Override
                public void grabBars() {
                }
            });
            ui.draw();

            click(ui, find(ui, "options-search"));
            for (char typed : "double blind".toCharArray()) {
                ui.stage.keyTyped(typed);
            }
            ui.draw();
            List<GameOptionsForm.Page> shown = form.visiblePages();
            assertTrue(shown.size() < form.pages().size(), "the search narrows the groups");
            GameOptionsForm.Page sensors = shown.stream()
                  .filter(page -> form.visibleSections(page).stream()
                        .flatMap(section -> form.visibleRows(page, section).stream())
                        .anyMatch(row -> row.name().equals(OptionsConstants.ADVANCED_DOUBLE_BLIND)))
                  .findFirst().orElseThrow();
            click(ui, find(ui, "options-page-" + sensors.id()));
            assertEquals(sensors.id(), view.pageId());

            Actor changed = find(ui, "option-" + OptionsConstants.ADVANCED_DOUBLE_BLIND + "-changed");
            assertFalse(changed.isVisible(), "an untouched option carries no mark");
            Actor doubleBlind = find(ui, "option-" + OptionsConstants.ADVANCED_DOUBLE_BLIND);
            reveal(ui, doubleBlind);
            click(ui, doubleBlind);
            assertTrue(form.bool(OptionsConstants.ADVANCED_DOUBLE_BLIND));
            assertTrue(changed.isVisible(), "a changed option is marked");

            click(ui, find(ui, "options-unofficial"));
            assertNotNull(ui.stage.getRoot().findActor("confirm-ok"), "hiding the unofficial rules asks first");
            click(ui, find(ui, "confirm-cancel"));
            assertTrue(form.unofficialShown(), "a cancelled question keeps them shown");

            click(ui, find(ui, "options-ok"));
            assertEquals(1, sent.size());
            assertEquals(List.of(OptionsConstants.ADVANCED_DOUBLE_BLIND),
                  sent.getFirst().stream().map(IBasicOption::getName).toList());
            assertFalse(dialogs.isOpen(), "OK closes the options");
            dialogs.dispose();
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Actor> T find(UiTestStage ui, String name) {
        Actor actor = ui.stage.getRoot().findActor(name);
        assertNotNull(actor, name + " shows");
        return (T) actor;
    }

    /** Draws frames until {@code done}, as the camo pictures arrive from their thread, three seconds at most. */
    private static void await(UiTestStage ui, BooleanSupplier done) throws InterruptedException {
        for (int frame = 0; frame < 300 && !done.getAsBoolean(); frame++) {
            ui.draw();
            Thread.sleep(10);
        }
        ui.draw();
        assertTrue(done.getAsBoolean(), "the dialog's pictures arrived");
    }

    /** Scrolls the dialog's body to {@code actor}, as the probe does. */
    private static void reveal(UiTestStage ui, Actor actor) {
        UiDialog dialog = ui.stage.getRoot().findActor("dialog") == null ? null : null;
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        clickAt(ui, Math.round(point.x), Math.round(point.y));
    }

    private static void clickAt(UiTestStage ui, int x, int y) {
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static Board board() {
        Hex[] hexes = new Hex[16 * 17];
        for (int i = 0; i < hexes.length; i++) {
            hexes[i] = new Hex();
        }
        return new Board(16, 17, hexes);
    }
}
