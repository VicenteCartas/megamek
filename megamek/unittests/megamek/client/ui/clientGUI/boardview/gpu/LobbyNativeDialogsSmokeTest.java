/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
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
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.PriorityTargetChoices.Candidate;
import megamek.client.bot.princess.PriorityTargetChoices.ForceRole;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.AlphaStrikeStatsForm;
import megamek.client.lobby.AutoResolveRun;
import megamek.client.lobby.BotFacingForm;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotTargetPicker;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.HelpDocument;
import megamek.client.lobby.IconCatalog;
import megamek.client.lobby.LanceRoleForm;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.PortraitCatalog;
import megamek.client.lobby.SbfStatsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.audio.SoundType;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.settings.ClientSettingsForm;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Hex;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.containers.MunitionTree;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.icons.Portrait;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;
import megamek.common.orders.UnitOrders;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceStore;
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
    void escapeClosesADialogWhoseTextFieldHasTheFocus() {
        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> said.add("cancel")).width("dialog.sm");
            UiTextField name = ui.kit.input("Vicente", false);
            dialog.body().add(name).width(200);
            dialog.primary("OK", () -> said.add("ok"));
            dialog.cancelButton("Cancel");
            dialog.show(ui.stage, false);
            ui.draw();
            assertEquals(name, ui.stage.getKeyboardFocus(), "the text field takes the first focus");
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertEquals(List.of("cancel"), said, "Escape cancels though the text field takes every key");

            ui.stage.setKeyboardFocus(null);
            ui.stage.act(1 / 60f);
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertEquals(List.of("cancel", "cancel"), said, "Escape cancels once a slider dropped the focus");
        });
    }

    @Test
    void theFirstFocusIsTheFirstButtonNotTheTitleBarClose() {
        UiTestStage.run(ui -> {
            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> { }).width("dialog.sm");
            Button first = ui.kit.button("button-quiet", null, "First", null);
            dialog.body().add(first).row();
            dialog.body().add(ui.kit.button("button-quiet", null, "Second", null));
            dialog.cancelButton("Close");
            dialog.show(ui.stage, false);
            ui.draw();
            assertEquals(first, ui.stage.getKeyboardFocus(), "a dialog without OK starts on its first button");
            dialog.close();

            UiDialog withOk = new UiDialog(ui.kit, "Test", () -> { }).width("dialog.sm");
            withOk.body().add(ui.kit.button("button-quiet", null, "Other", null));
            Button ok = withOk.primary("OK", () -> { });
            withOk.cancelButton("Cancel");
            withOk.show(ui.stage, false);
            ui.draw();
            assertEquals(ok, ui.stage.getKeyboardFocus(), "OK takes the first focus when there is one");
            withOk.close();
        });
    }

    @Test
    void theFooterButtonsStayCentredOnTheCardWithAToolBesideThem() {
        UiTestStage.run(ui -> {
            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> { }).width("dialog.lg");
            dialog.body().add(new Actor()).size(400, 40);
            dialog.tool("Refresh directory", () -> { });
            Button ok = dialog.primary("OK", () -> { });
            Button cancel = dialog.cancelButton("Cancel");
            dialog.show(ui.stage, false);
            ui.stage.act(1f);
            ui.draw();
            float left = ok.localToStageCoordinates(new Vector2(0, 0)).x;
            float right = cancel.localToStageCoordinates(new Vector2(cancel.getWidth(), 0)).x;
            Vector2 card = dialog.card().localToStageCoordinates(new Vector2(0, 0));
            float centre = card.x + dialog.card().getWidth() / 2;
            assertEquals(centre, (left + right) / 2, 1.5f, "the buttons are centred on the card, the tool aside");
            dialog.close();

            UiDialog wide = new UiDialog(ui.kit, "Test", () -> { }).width("dialog.lg");
            wide.body().add(new Actor()).size(400, 40);
            wide.leading().add(new Actor()).size(320, 20);
            wide.primary("OK", () -> { });
            Button quit = wide.cancelButton("Cancel");
            wide.show(ui.stage, false);
            ui.stage.act(1f);
            ui.draw();
            Actor foot = quit.getParent().getParent();
            float footRight = foot.localToStageCoordinates(new Vector2(foot.getWidth(), 0)).x;
            float quitRight = quit.localToStageCoordinates(new Vector2(quit.getWidth(), 0)).x;
            assertEquals(footRight, quitRight, 1.5f, "a wide leading side sends the buttons right, not against it");
            wide.close();
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
    void thePortraitChooserHandsBackTheClickedPortrait() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            PortraitCatalog catalog = new PortraitCatalog(new IconCatalog.Folder("", "", List.of(),
                  List.of(new IconCatalog.Folder("Pilot", "Pilot/", List.of("Ace.png", "Rookie.png"), List.of()))));
            BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            LobbyPortraitView.Source source = new LobbyPortraitView.Source() {
                @Override
                public PortraitCatalog catalog(boolean refresh) {
                    return catalog;
                }

                @Override
                public BufferedImage thumbnail(Portrait portrait, int size) {
                    return picture;
                }
            };
            List<Portrait> chosen = new ArrayList<>();
            LobbyPortraitView view = dialogs.portrait(source, catalog, new Portrait("Pilot/", "Ace.png"),
                  chosen::add);
            await(ui, () -> view.previewShown() && view.item(1) != null);
            assertTrue(dialogs.isOpen());
            assertEquals(2, view.items().size(), "the initial portrait's category shows");

            click(ui, view.item(1));
            assertEquals(new Portrait("Pilot/", "Rookie.png"), view.selected());
            click(ui, ui.stage.getRoot().findActor("portrait-ok"));
            assertFalse(dialogs.isOpen(), "OK closes the chooser");
            assertEquals(List.of(new Portrait("Pilot/", "Rookie.png")), chosen);

            LobbyPortraitView again = dialogs.portrait(source, catalog, null, chosen::add);
            ui.draw();
            click(ui, ui.stage.getRoot().findActor("portrait-cancel"));
            assertFalse(dialogs.isOpen(), "Cancel closes the chooser");
            assertEquals(1, chosen.size(), "Cancel chooses nothing");
            assertNull(again.selected());
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

    @Test
    void theClientSettingsTakeEffectAtOnceAndCancelPutsThemBack() {
        UiTestStage.run(ui -> {
            PreferenceStore store = new PreferenceStore();
            store.setDefault(GUIPreferences.MASTER_VOLUME, 80);
            store.setDefault(GUIPreferences.SOUND_MUTE_CHAT, true);
            store.setDefault(GUIPreferences.SOUND_MUTE_MY_TURN, false);
            store.setDefault(GUIPreferences.SOUND_MUTE_OTHERS_TURN, true);
            store.setDefault(GUIPreferences.SOUND_BING_FILENAME_CHAT, "chat.wav");
            int[] saved = { 0 };
            List<SoundType> played = new ArrayList<>();
            ClientSettingsView.Host host = new ClientSettingsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void play(SoundType sound) {
                    played.add(sound);
                }

                @Override
                public void save() {
                    saved[0]++;
                }
            };
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            ClientSettingsView view = dialogs.clientSettings(new ClientSettingsForm(store, new ClientPreferences(new PreferenceStore())),
                  CommonSettingsPane.SETTINGS_AUDIO, host);
            ui.draw();
            assertEquals(ClientSettingsForm.AUDIO, view.pageId());
            assertNotNull(ui.stage.getRoot().findActor("client-group-" + ClientSettingsForm.AUDIO));
            assertFalse(find(ui, "client-reset-" + ClientSettingsForm.AUDIO).isVisible(),
                  "a page at its defaults offers no reset");
            float searchTop = top(find(ui, "client-search"));
            assertEquals(searchTop, top(find(ui, "client-page").getParent()), 1, "the page's frame lines up with the search");
            assertEquals(searchTop, top(find(ui, "client-sound-check").getParent()), 1,
                  "the preview's frame lines up with the search");
            Actor chat = find(ui, "client-setting-" + GUIPreferences.SOUND_MUTE_CHAT);
            reveal(ui, chat);
            click(ui, chat);
            assertFalse(store.getBoolean(GUIPreferences.SOUND_MUTE_CHAT), "the switch unmutes the chat at once");
            assertTrue(find(ui, "client-setting-" + GUIPreferences.SOUND_MUTE_CHAT + "-changed").isVisible());
            assertTrue(find(ui, "client-reset-" + ClientSettingsForm.AUDIO).isVisible());

            click(ui, find(ui, "client-play-chat"));
            assertEquals(List.of(SoundType.BING_CHAT), played);

            click(ui, find(ui, "client-cancel"));
            assertFalse(dialogs.isOpen(), "Cancel closes the settings");
            assertTrue(store.getBoolean(GUIPreferences.SOUND_MUTE_CHAT), "Cancel puts the chat's mute back");
            assertEquals(0, saved[0]);

            view = dialogs.clientSettings(new ClientSettingsForm(store, new ClientPreferences(new PreferenceStore())), null, host);
            ui.draw();
            chat = find(ui, "client-setting-" + GUIPreferences.SOUND_MUTE_CHAT);
            reveal(ui, chat);
            click(ui, chat);
            click(ui, find(ui, "client-ok"));
            assertFalse(dialogs.isOpen(), "OK closes the settings");
            assertFalse(store.getBoolean(GUIPreferences.SOUND_MUTE_CHAT), "OK keeps what was set");
            assertEquals(1, saved[0], "OK stores the preferences");
            dialogs.dispose();
        });
    }

    @Test
    void theDisplayPageScalesAtOnceAndItsSampleFollowsTheMotion() {
        UiTestStage.run(ui -> {
            PreferenceStore store = new PreferenceStore();
            store.setDefault(GUIPreferences.GUI_SCALE, 1f);
            store.setDefault(GUIPreferences.BOARD_UI_SCALE, 1f);
            store.setDefault(GUIPreferences.COLOR_BLIND_PALETTE, false);
            store.setDefault(GUIPreferences.REDUCE_MOTION, false);
            ClientSettingsView.Host host = (work, then) -> {
                work.run();
                then.run();
            };
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            ClientSettingsForm form = new ClientSettingsForm(store, new ClientPreferences(new PreferenceStore()));
            ClientSettingsView view = dialogs.clientSettings(form, form.pageOf(CommonSettingsPane.SETTINGS_MAIN),
                  host);
            ui.draw();
            assertEquals(ClientSettingsForm.DISPLAY, view.pageId(), "the Swing dialog's main route opens Display");
            float searchTop = top(find(ui, "client-search"));
            assertEquals(searchTop, top(find(ui, "client-display-sample").getParent()), 1,
                  "the sample's frame lines up with the search");
            assertFalse(find(ui, "client-restart").isVisible(), "the language has not changed");

            Slider scale = (Slider) find(ui, "client-setting-" + GUIPreferences.GUI_SCALE);
            scale.setValue(130);
            assertEquals(1.3f, store.getFloat(GUIPreferences.GUI_SCALE), 1e-6, "a scale set by keys applies at once");


            Actor palette = find(ui, "client-setting-" + GUIPreferences.COLOR_BLIND_PALETTE);
            reveal(ui, palette);
            click(ui, palette);
            assertTrue(store.getBoolean(GUIPreferences.COLOR_BLIND_PALETTE));
            Actor motion = find(ui, "client-setting-" + GUIPreferences.REDUCE_MOTION);
            reveal(ui, motion);
            click(ui, motion);
            assertTrue(store.getBoolean(GUIPreferences.REDUCE_MOTION));
            assertEquals(Messages.getString("ClientSettings.sample.still"),
                  ((Label) find(ui, "client-sample-motion")).getText().toString());


            click(ui, find(ui, "client-cancel"));
            assertEquals(1f, store.getFloat(GUIPreferences.GUI_SCALE), 1e-6, "Cancel puts the scale back");
            assertFalse(store.getBoolean(GUIPreferences.COLOR_BLIND_PALETTE));
            dialogs.dispose();
        });
    }

    @Test
    void aKeyIsBoundByPressingItAndEscapeKeepsTheOldOne() {
        int key = KeyCommandBind.FIRE.key;
        int modifiers = KeyCommandBind.FIRE.modifiers;
        try {
            UiTestStage.run(ui -> {
                KeyCommandBind.FIRE.key = KeyCommandBind.FIRE.keyDefault;
                KeyCommandBind.FIRE.modifiers = KeyCommandBind.FIRE.modifiersDefault;
                int[] stored = { 0 };
                ClientSettingsView.Host host = (work, then) -> {
                    work.run();
                    then.run();
                };
                LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
                ClientSettingsForm form = new ClientSettingsForm(new PreferenceStore(),
                      new ClientPreferences(new PreferenceStore()), () -> stored[0]++);
                ClientSettingsView view = dialogs.clientSettings(form, ClientSettingsForm.KEYS + "combat", host);
                ui.draw();
                assertEquals(ClientSettingsForm.KEYS + "combat", view.pageId());
                assertEquals(top(find(ui, "client-search")), top(find(ui, "client-keyboard").getParent()), 1,
                      "the keyboard's frame lines up with the search");
                String fire = "client-setting-" + ClientSettingsForm.BIND + KeyCommandBind.FIRE.cmd;
                Actor cap = find(ui, fire);
                reveal(ui, cap);
                assertFalse(find(ui, fire + "-restore").isVisible(), "a binding at its default has no way back");

                click(ui, cap);
                ui.stage.keyDown(Input.Keys.ESCAPE);
                ui.draw();
                assertTrue(dialogs.isOpen(), "Escape while a key is awaited keeps the settings open");
                assertEquals(KeyCommandBind.FIRE.keyDefault, KeyCommandBind.FIRE.key, "and the binding as it was");

                click(ui, cap);
                ui.stage.keyDown(Input.Keys.F7);
                ui.stage.keyUp(Input.Keys.F7);
                ui.draw();
                assertEquals(KeyEvent.VK_F7, KeyCommandBind.FIRE.key, "the pressed key binds at once");
                assertEquals(0, KeyCommandBind.FIRE.modifiers);
                assertEquals(1, stored[0], "the bindings are stored as they change");
                assertTrue(find(ui, fire + "-restore").isVisible());

                click(ui, find(ui, fire + "-unbind"));
                assertEquals(0, KeyCommandBind.FIRE.key, "unbound");
                assertFalse(find(ui, fire + "-unbind").isVisible());
                click(ui, find(ui, fire + "-restore"));
                assertEquals(KeyCommandBind.FIRE.keyDefault, KeyCommandBind.FIRE.key, "back to the default key");

                click(ui, cap);
                ui.stage.keyDown(Input.Keys.F8);
                ui.draw();
                click(ui, find(ui, "client-cancel"));
                assertFalse(dialogs.isOpen());
                assertEquals(KeyCommandBind.FIRE.keyDefault, KeyCommandBind.FIRE.key, "Cancel puts the binding back");
                dialogs.dispose();
            });
        } finally {
            KeyCommandBind.FIRE.key = key;
            KeyCommandBind.FIRE.modifiers = modifiers;
        }
    }

    @Test
    void theYesNoQuestionAnswersWithItsBoxAndEscapeAnswersNothing() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            List<String> answers = new ArrayList<>();
            dialogs.yesNo("Record?", List.of("First line.", "Second line."), "Remember",
                  (yes, checked) -> answers.add(yes + "/" + checked));
            ui.draw();
            click(ui, find(ui, "yes-no-check"));
            click(ui, find(ui, "yes-no-no"));
            assertEquals(List.of("false/true"), answers, "No answers, with the box as ticked");
            assertFalse(dialogs.isOpen());

            dialogs.yesNo("Record?", List.of("Line."), "Remember", (yes, checked) -> answers.add(yes + "/" + checked));
            ui.draw();
            click(ui, find(ui, "yes-no-yes"));
            assertEquals("true/false", answers.getLast(), "Yes answers, the box untouched");

            dialogs.yesNo("Record?", List.of("Line."), "Remember", (yes, checked) -> answers.add(yes + "/" + checked));
            ui.draw();
            ui.stage.keyDown(Input.Keys.ESCAPE);
            ui.draw();
            assertFalse(dialogs.isOpen(), "Escape closes it");
            assertEquals(2, answers.size(), "Escape answers nothing");
            dialogs.dispose();
        });
    }

    @Test
    void theBotReadmeNagAnswersWithItsBox() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            List<String> answers = new ArrayList<>();
            dialogs.botReadme("Remember", (yes, checked) -> answers.add(yes + "/" + checked));
            ui.draw();
            assertNotNull(ui.stage.getRoot().findActor("bot-readme-headline"));
            click(ui, find(ui, "yes-no-check"));
            click(ui, find(ui, "yes-no-yes"));
            assertEquals(List.of("true/true"), answers, "Read the guide answers, with the box as ticked");
            assertFalse(dialogs.isOpen());
            dialogs.dispose();
        });
    }

    @Test
    void theDocumentTurnsItsChaptersFromTheRailAndTheFooter() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            HelpDocument report = HelpDocument.battleReport("""
                  <h1>Battle</h1><p>Two lances met.</p>
                  <a name="round-1"><h2>Initiative</h2></a>
                  <h3>Movement</h3>&ensp;<span style='color:#8686bf'>Lance A</span> moved<br>
                  &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;Atlas walked 3 hexes<br>
                  <a name="round-2"><h2>Initiative</h2></a><h3>Firing</h3>&ensp;Lance B fired<br>
                  <a name="end-of-combat"><h2>End of Combat</h2></a>
                  <a name="victory"><h2><i>Team 2 is victorious!</i></h2></a>
                  """, "Summary", round -> "Round " + round);
            LobbyDocumentView view = dialogs.document("Report", report, false);
            ui.draw();
            assertEquals(report.chapters().size(), view.chapterCount());
            assertEquals(0, view.shown());
            assertEquals("SUMMARY", ((Label) find(ui, "document-title")).getText().toString().toUpperCase());

            click(ui, find(ui, "document-chapter-1"));
            ui.draw();
            assertEquals(1, view.shown());
            assertEquals("ROUND 1", ((Label) find(ui, "document-title")).getText().toString().toUpperCase());
            assertNotNull(ui.stage.getRoot().findActor("document-event"), "the round's lines read as events");

            click(ui, find(ui, "document-next"));
            ui.draw();
            assertEquals(2, view.shown());
            click(ui, find(ui, "document-previous"));
            ui.draw();
            assertEquals(1, view.shown());

            click(ui, find(ui, "document-chapter-" + (view.chapterCount() - 1)));
            ui.draw();
            assertNotNull(ui.stage.getRoot().findActor("document-callout"), "the victory reads as a banner");
            click(ui, find(ui, "document-close"));
            assertFalse(dialogs.isOpen());
            assertNull(dialogs.documentView());
            dialogs.dispose();
        });
    }

    @Test
    void theSaveQuestionBeforeLeavingSavesLeavesOrStays() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            List<String> answers = new ArrayList<>();
            UiDialog dialog = dialogs.saveFirst(save -> answers.add("save " + save), () -> answers.add("stayed"));
            ui.draw();
            Table card = dialog.card();
            Vector2 cardRight = card.localToStageCoordinates(new Vector2(card.getWidth(), 0));
            for (String name : List.of("save-first-save", "save-first-leave", "save-first-cancel")) {
                Actor button = find(ui, name);
                Vector2 right = button.localToStageCoordinates(new Vector2(button.getWidth(), 0));
                assertTrue(right.x <= cardRight.x + 0.5f, name + " stays inside the card");
            }
            click(ui, find(ui, "save-first-save"));
            assertEquals(List.of("save true"), answers, "Save… saves");
            assertFalse(dialogs.isOpen());

            dialogs.saveFirst(save -> answers.add("save " + save), () -> answers.add("stayed"));
            ui.draw();
            click(ui, find(ui, "save-first-leave"));
            assertEquals("save false", answers.getLast(), "Don't save leaves");

            dialogs.saveFirst(save -> answers.add("save " + save), () -> answers.add("stayed"));
            ui.draw();
            click(ui, find(ui, "save-first-cancel"));
            ui.draw();
            assertEquals("stayed", answers.getLast(), "Cancel stays");

            dialogs.saveFirst(save -> answers.add("save " + save), () -> answers.add("stayed"));
            ui.draw();
            ui.stage.keyDown(Input.Keys.ESCAPE);
            ui.draw();
            assertFalse(dialogs.isOpen());
            assertEquals(List.of("save true", "save false", "stayed", "stayed"), answers, "Escape stays too");
            dialogs.dispose();
        });
    }

    @Test
    void aLockedGameOptionShowsButTakesNoClick() {
        UiTestStage.run(ui -> {
            GameOptions options = new GameOptions();
            boolean fire = options.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE);
            GameOptionsForm form = new GameOptionsForm(options, Set.of(), true);
            form.lock(Set.of(OptionsConstants.BASE_FRIENDLY_FIRE));
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.gameOptions(form, new LobbyGameOptionsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void send(String password, Vector<IBasicOption> changed, Vector<IBasicOption> all) {
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
            for (char typed : "friendly fire".toCharArray()) {
                ui.stage.keyTyped(typed);
            }
            ui.draw();
            GameOptionsForm.Page page = form.visiblePages().stream()
                  .filter(shown -> form.visibleSections(shown).stream()
                        .flatMap(section -> form.visibleRows(shown, section).stream())
                        .anyMatch(row -> row.name().equals(OptionsConstants.BASE_FRIENDLY_FIRE)))
                  .findFirst().orElseThrow();
            click(ui, find(ui, "options-page-" + page.id()));
            Actor friendlyFire = find(ui, "option-" + OptionsConstants.BASE_FRIENDLY_FIRE);
            reveal(ui, friendlyFire);
            click(ui, friendlyFire);
            assertEquals(fire, form.bool(OptionsConstants.BASE_FRIENDLY_FIRE), "a locked option keeps its value");
            dialogs.dispose();
        });
    }

    @Test
    void theAutoResolveBriefingMovesFromTheOddsToTheOutcome() {
        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyAutoResolveView view = dialogs.autoResolve(true, List.of("Chatter one", "Chatter two"),
                  new LobbyAutoResolveView.Host() {
                      @Override
                      public void proceed() {
                          said.add("proceed");
                      }

                      @Override
                      public void report() {
                          said.add("report");
                      }
                  }, () -> said.add("closed"));
            ui.draw();
            assertEquals(LobbyAutoResolveView.Phase.ODDS, view.phase());
            view.odds(3, 10);
            ui.draw();
            assertTrue(((Label) find(ui, "autoresolve-count")).getText().toString().startsWith("3"),
                  "the count says how many simulated battles ended");
            for (int frame = 0; frame < 3 * 60 * LobbyAutoResolveView.LINE_SECONDS; frame++) {
                ui.stage.act(1 / 60f);
            }

            view.choice(new AutoResolveRun.Score(10, 6, 3, 1, 0));
            ui.draw();
            assertEquals(LobbyAutoResolveView.Phase.CHOICE, view.phase());
            assertEquals("60%", ((Label) find(ui, "autoresolve-chance")).getText().toString());
            assertNotNull(ui.stage.getRoot().findActor("autoresolve-victories"));
            assertNull(ui.stage.getRoot().findActor("autoresolve-count"), "the odds' progress is gone");
            Button proceed = find(ui, "autoresolve-proceed");
            assertEquals(proceed, ui.stage.getKeyboardFocus(), "Resolve takes the focus");
            click(ui, proceed);
            assertEquals(List.of("proceed"), said);

            view.resolving();
            ui.draw();
            assertEquals(LobbyAutoResolveView.Phase.RESOLVING, view.phase());
            assertNull(ui.stage.getRoot().findActor("autoresolve-proceed"), "the footer has moved on");

            view.result(AutoResolveRun.Outcome.VICTORY, "Team 1 won the scenario.", true);
            ui.draw();
            assertEquals(AutoResolveRun.headline(AutoResolveRun.Outcome.VICTORY),
                  ((Label) find(ui, "autoresolve-headline")).getText().toString());
            click(ui, find(ui, "autoresolve-report"));
            assertEquals(List.of("proceed", "report"), said);
            click(ui, find(ui, "autoresolve-close"));
            assertFalse(view.isOpen());
            assertNull(dialogs.autoResolveView());
            assertEquals(List.of("proceed", "report", "closed"), said);
            dialogs.dispose();
        });
    }

    @Test
    void theFacingCardSetsBothCompassesAndOkSends() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            BotFacingForm form = new BotFacingForm("Atlas AS7-D", UnitOrders.FACING_AUTO, 3);
            List<String> sent = new ArrayList<>();
            dialogs.facing(form, (work, then) -> {
                work.run();
                then.run();
            }, () -> sent.add(form.whileMoving() + "/" + form.whenStopped()));
            ui.draw();
            assertTrue(find(ui, "facing-stopped-3") instanceof Button stopped && stopped.isChecked(),
                  "the unit's facing shows chosen");
            click(ui, find(ui, "facing-moving-2"));
            assertTrue(((Button) find(ui, "facing-moving-2")).isChecked());
            assertFalse(((Button) find(ui, "facing-moving-auto")).isChecked(), "one side at a time");
            click(ui, find(ui, "facing-stopped-auto"));
            assertTrue(sent.isEmpty(), "nothing is sent before OK");
            click(ui, find(ui, "facing-ok"));
            assertEquals(List.of("2/" + UnitOrders.FACING_AUTO), sent);
            assertFalse(dialogs.isOpen());

            dialogs.facing(form, (work, then) -> work.run(), () -> sent.add("again"));
            ui.draw();
            click(ui, find(ui, "facing-cancel"));
            assertEquals(1, sent.size(), "Cancel sends nothing");
            dialogs.dispose();
        });
    }

    @Test
    void theLanceRoleCardWaitsForAPlaceRoundTheConvoy() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LanceRoles.ConvoyChoice trucks = new LanceRoles.ConvoyChoice(7, "Trucks", 4, true,
                  OffBoardDirection.SOUTH);
            LanceRole shown = LanceRole.defaultEscort(7);
            LanceRoleForm form = new LanceRoleForm("Alpha", List.of(trucks), OffBoardDirection.NORTH, shown);
            for (LanceRole.Position position : LanceRole.Position.values()) {
                if (form.holds(position)) {
                    form.toggle(position);
                }
            }
            List<LanceRole> sent = new ArrayList<>();
            dialogs.lanceRole(form, (work, then) -> {
                work.run();
                then.run();
            }, () -> sent.add(form.role()));
            ui.draw();
            Button ok = find(ui, "role-ok");
            assertTrue(ok.isDisabled(), "an escort with no place round the convoy cannot be sent");
            click(ui, find(ui, "role-escort-lead"));
            ok = find(ui, "role-ok");
            assertFalse(ok.isDisabled());
            click(ui, ok);
            assertEquals(1, sent.size());
            assertTrue(sent.getFirst().isEscort());
            assertEquals(7, sent.getFirst().getConvoyForceId());
            assertTrue(sent.getFirst().getPositions().contains(LanceRole.Position.LEAD));

            LanceRoleForm convoy = new LanceRoleForm("Bravo", List.of(), OffBoardDirection.NORTH, null);
            dialogs.lanceRole(convoy, (work, then) -> work.run(), () -> sent.add(convoy.role()));
            ui.draw();
            assertNull(ui.stage.getRoot().findActor("role-tab-escort"), "no lance to guard, no Escort tab");
            click(ui, find(ui, "role-tab-convoy"));
            assertNotNull(find(ui, "role-convoy-edge"));
            click(ui, find(ui, "role-ok"));
            assertTrue(sent.getLast().isConvoy());
            dialogs.dispose();
        });
    }

    @Test
    void theStatsCardsShowNothingToCopyForNoUnits() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyStatsView.Host host = mock(LobbyStatsView.Host.class);
            when(host.fonts()).thenReturn(List.of("", "Serif"));
            when(host.sheetFonts()).thenReturn(new String[] { "Serif", "" });
            dialogs.alphaStrikeStats(new AlphaStrikeStatsForm(List.of()), host);
            ui.draw();
            Button copy = find(ui, "stats-copy-cards");
            assertTrue(copy.isDisabled(), "no units, nothing to copy");
            UiSelect sort = find(ui, "stats-sort");
            assertEquals(0, sort.getSelectedIndex());
            click(ui, find(ui, "stats-close"));
            assertFalse(dialogs.isOpen());

            dialogs.sbfStats(new SbfStatsForm(List.of(), new Game()), host);
            ui.draw();
            UiSelect header = find(ui, "stats-header-font");
            assertEquals(1, header.getSelectedIndex(), "the sheet's last header font is chosen");
            assertTrue(((Button) find(ui, "stats-print")).isDisabled());
            click(ui, find(ui, "stats-elements"));
            assertTrue(((Button) find(ui, "stats-elements")).isChecked(), "Show elements turns on");
            click(ui, find(ui, "stats-close"));
            verify(host, never()).print(org.mockito.ArgumentMatchers.any(SbfStatsForm.class),
                  org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
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

    /** The actor's top edge on the stage. */
    private static float top(Actor actor) {
        return actor.localToStageCoordinates(new Vector2(0, actor.getHeight())).y;
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
