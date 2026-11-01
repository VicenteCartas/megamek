/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.containers.MunitionTree;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MULParser;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lobby's file routes ask the system file dialog: a fake dialog records each request and answers with a file in a
 * temporary folder, and the game options, a loadout and a unit list are saved, then loaded back.
 */
@Tag("on-demand")
class LobbyFileChoosersSmokeTest {

    @TempDir
    Path folder;

    private final List<SystemFileDialog.Request> requests = new ArrayList<>();
    private final LinkedList<File> answers = new LinkedList<>();
    private final List<String> reported = new ArrayList<>();

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @AfterEach
    void showDialogsAgain() {
        SystemFileDialog.fake(null);
    }

    /** The lobby's file routes, asking the system file dialog as the lobby window does, which the fake answers. */
    private LobbyFiles files() {
        SystemFileDialog.fake((request, answer) -> {
            requests.add(request);
            answer.accept(answers.poll());
        });
        return new LobbyFiles((ask, chosen) -> SystemFileDialog.show(SystemFileDialog.Request.of(ask), () -> null,
              () -> null, busy -> { }, choice -> chosen.accept(choice == null ? null : choice.file())),
              new LobbyFiles.Report() {
                  @Override
                  public void error(LobbyError error, Object... args) {
                      reported.add(error.name());
                  }

                  @Override
                  public void message(String title, String text) {
                      reported.add(text);
                  }
              }, new LobbyFiles.Folders());
    }

    @Test
    void theGameOptionsSaveAndLoadThroughTheSystemDialog() {
        UiTestStage.run(ui -> {
            LobbyFiles files = files();
            GameOptions options = new GameOptions();
            options.getOption(OptionsConstants.BASE_FRIENDLY_FIRE).setValue(true);
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.gameOptions(new GameOptionsForm(options, Set.of(), true), optionsHost(files));
            ui.draw();
            answers.add(folder.resolve("options").toFile());
            click(ui, find(ui, "options-save"));
            assertEquals(1, requests.size(), "Save asks the system dialog");
            assertTrue(requests.get(0).save());
            assertEquals(new File("mmconf"), requests.get(0).directory());
            assertEquals(List.of("xml"), List.of(requests.get(0).filter().getExtensions()));
            File saved = folder.resolve("options.xml").toFile();
            assertTrue(saved.isFile(), "the options are saved with the dialog's extension");
            dialogs.dispose();

            GameOptionsForm fresh = new GameOptionsForm(new GameOptions(), Set.of(), true);
            assertEquals(Boolean.FALSE, fresh.value(OptionsConstants.BASE_FRIENDLY_FIRE));
            dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.gameOptions(fresh, optionsHost(files));
            ui.draw();
            answers.add(saved);
            click(ui, find(ui, "options-load"));
            assertEquals(2, requests.size(), "Load asks the system dialog");
            assertFalse(requests.get(1).save());
            assertEquals(Boolean.TRUE, fresh.value(OptionsConstants.BASE_FRIENDLY_FIRE), "the saved options load");
            assertTrue(reported.isEmpty(), reported.toString());
            dialogs.dispose();
        });
    }

    @Test
    void aLoadoutSavesAndLoadsThroughTheSystemDialog() {
        UiTestStage.run(ui -> {
            LobbyFiles files = files();
            PlayerSettingsForm form = playerSettings();
            MunitionTree tree = new MunitionTree();
            tree.insertImperative("Atlas", "AS7-D", "any", "AC/20", "Precision", "Standard");
            form.loaded(tree);
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.playerSettings(form, form.positions(), null, settingsHost(files));
            ui.draw();

            answers.add(folder.resolve("assault").toFile());
            Actor save = find(ui, "settings-save-loadout");
            reveal(ui, save);
            click(ui, save);
            assertEquals(1, requests.size(), "Save Loadout asks the system dialog");
            assertEquals(List.of("adf"), List.of(requests.get(0).filter().getExtensions()));
            File saved = folder.resolve("assault.adf").toFile();
            assertTrue(saved.isFile());

            form.loaded(new MunitionTree());
            answers.add(saved);
            Actor load = find(ui, "settings-load-loadout");
            reveal(ui, load);
            click(ui, load);
            assertEquals(2, requests.size(), "Load Loadout asks the system dialog");
            assertEquals(tree.getPriorityList("Atlas", "AS7-D", "any", "AC/20"),
                  form.loadoutToSave().getPriorityList("Atlas", "AS7-D", "any", "AC/20"), "the saved loadout loads");
            dialogs.dispose();
        });
    }

    @Test
    void aUnitListSavesAndLoadsThroughTheSystemDialog() throws Exception {
        LobbyFiles files = files();
        Game game = new Game();
        Player player = new Player(0, "Kai");
        game.addPlayer(0, player);
        Entity atlas = new MekFileParser(new File("testresources/data/mekfiles/Atlas AS7-D.mtf")).getEntity();
        atlas.setGame(game);
        atlas.setId(game.getNextEntityId());
        atlas.setCrew(new Crew(CrewType.SINGLE));
        atlas.setOwner(player);
        game.addEntity(atlas);

        answers.add(folder.resolve("company").toFile());
        files.saveUnitList(game, player, () -> { });
        assertEquals("Kai.mul", requests.get(0).name(), "the dialog suggests the player's name");
        File saved = folder.resolve("company.mul").toFile();
        assertTrue(saved.isFile());

        answers.add(saved);
        List<Entity> loaded = new ArrayList<>();
        files.loadUnitList(file -> loaded.addAll(new MULParser(file, null).getEntities()),
              () -> { });
        assertEquals(List.of("mul", "mmu"), List.of(requests.get(1).filter().getExtensions()));
        assertEquals(1, loaded.size(), "the saved list loads");
        assertEquals(atlas.getShortName(), loaded.get(0).getShortName());
        assertEquals(folder.toFile().getAbsoluteFile(), files.ask(LobbyFiles.Route.LOAD_UNIT_LIST, null).directory(),
              "the folder is remembered");
        assertTrue(Files.size(saved.toPath()) > 0);
    }

    @Test
    void aSpriteExportAsksTheSystemDialogWithTheUnitsName() {
        LobbyFiles files = files();
        answers.add(null);
        List<File> written = new ArrayList<>();
        files.exportSprite("Atlas AS7-D", written::add);
        assertEquals("Atlas AS7-D.png", requests.get(0).name());
        assertTrue(written.isEmpty(), "a cancel writes nothing");
    }

    private static LobbyGameOptionsView.Host optionsHost(LobbyFiles files) {
        return new LobbyGameOptionsView.Host() {
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
                files.saveGameOptions(all, then);
            }

            @Override
            public void load(GameOptions into, Consumer<Boolean> then) {
                files.loadGameOptions(into, then);
            }

            @Override
            public void grabBars() {
            }
        };
    }

    private static LobbyPlayerSettingsView.Host settingsHost(LobbyFiles files) {
        return new LobbyPlayerSettingsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                work.run();
                then.run();
            }

            @Override
            public void saveLoadout(MunitionTree tree, Runnable then) {
                files.saveLoadout(tree, then);
            }

            @Override
            public void loadLoadout(Consumer<MunitionTree> then) {
                files.loadLoadout(then);
            }

            @Override
            public void botSettings() {
            }
        };
    }

    private static PlayerSettingsForm playerSettings() {
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
        Hex[] hexes = new Hex[16 * 17];
        for (int i = 0; i < hexes.length; i++) {
            hexes[i] = new Hex();
        }
        return new PlayerSettingsForm(client, new LobbyCommands(() -> client, mock(LobbyPrompts.class)),
              new Board(16, 17, hexes), List.of(new PlayerSettingsForm.Faction("IS", "Inner Sphere")));
    }

    private static Actor find(UiTestStage ui, String name) {
        Actor actor = ui.stage.getRoot().findActor(name);
        assertNotNull(actor, name + " shows");
        return actor;
    }

    private static void reveal(UiTestStage ui, Actor actor) {
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
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
