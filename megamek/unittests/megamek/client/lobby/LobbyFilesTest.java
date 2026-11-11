/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.common.Player;
import megamek.common.containers.MunitionTree;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MULParser;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.BasicOption;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lobby's file routes: what each asks the file dialog, and that each saves and loads its files. */
class LobbyFilesTest {

    @TempDir
    Path folder;

    private final List<LobbyFiles.Ask> asked = new ArrayList<>();
    private final LinkedList<File> answers = new LinkedList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<LobbyError> errors = new ArrayList<>();
    private final LobbyFiles.Folders folders = new LobbyFiles.Folders();
    private final LobbyFiles files = new LobbyFiles((ask, chosen) -> {
        asked.add(ask);
        chosen.accept(answers.poll());
    }, new LobbyFiles.Report() {
        @Override
        public void error(LobbyError error, Object... args) {
            errors.add(error);
        }

        @Override
        public void message(String title, String text) {
            messages.add(title + ": " + text);
        }
    }, folders);

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    /** Answers the next dialog with {@code name} in the test folder, or a cancel when null. */
    private File answer(String name) {
        File file = (name == null) ? null : folder.resolve(name).toFile();
        answers.add(file);
        return file;
    }

    @Test
    void eachRouteAsksWithTheSwingChoosersFolderTitleAndFiles() {
        LobbyFiles.Ask options = files.ask(LobbyFiles.Route.SAVE_GAME_OPTIONS, null);
        assertEquals(new File("mmconf"), options.directory());
        assertEquals(List.of("xml"), options.extensions());
        assertTrue(options.save());
        assertFalse(files.ask(LobbyFiles.Route.LOAD_GAME_OPTIONS, null).save());

        LobbyFiles.Ask loadout = files.ask(LobbyFiles.Route.LOAD_LOADOUT, null);
        assertEquals(Paths.get(MMConstants.USER_LOADOUTS_DIR).toAbsolutePath().toFile(), loadout.directory());
        assertEquals(Messages.getString("ClientGui.LoadoutLoadDialog.title"), loadout.title());
        assertEquals(Messages.getString("Lobby.files.loadoutFiles"), loadout.filterName());
        assertEquals(List.of("adf"), loadout.extensions());

        LobbyFiles.Ask list = files.ask(LobbyFiles.Route.SAVE_UNIT_LIST, "Kai");
        assertEquals(new File("."), list.directory());
        assertEquals(Messages.getString("ClientGUI.saveUnitListFileDialog.title"), list.title());
        assertEquals("Kai.mul", list.name());
        assertEquals(List.of("mul", "mmu"), files.ask(LobbyFiles.Route.LOAD_UNIT_LIST, null).extensions());
        assertEquals(Messages.getString("Lobby.files.unitListFiles"),
              files.ask(LobbyFiles.Route.LOAD_UNIT_LIST, null).filterName());

        LobbyFiles.Ask stats = files.ask(LobbyFiles.Route.EXPORT_STATS, "Lance");
        assertEquals(Messages.getString("Lobby.files.mmuFiles"), stats.filterName());
        assertEquals(List.of("mmu"), stats.extensions());
        assertEquals("Lance.mmu", stats.name());

        LobbyFiles.Ask sprite = files.ask(LobbyFiles.Route.EXPORT_SPRITE, "Atlas AS7-D");
        assertNull(sprite.directory());
        assertEquals("Atlas AS7-D.png", sprite.name());
        assertEquals(List.of("png"), sprite.extensions());
    }

    @Test
    void aGameSaveAsksForASaveFileInTheSaveGameFolderAndPassesItOn() {
        LobbyFiles.Ask ask = files.ask(LobbyFiles.Route.SAVE_GAME, null);
        assertTrue(ask.save());
        assertEquals(new File(MMConstants.SAVEGAME_DIR).getAbsoluteFile(), ask.directory());
        assertEquals(Messages.getString("Lobby.files.saveGame"), ask.title());
        assertEquals(List.of("sav.gz", "sav"), ask.extensions());

        File chosen = answer("my game.sav.gz");
        List<File> saved = new ArrayList<>();
        files.saveGame(saved::add);
        assertEquals(List.of(chosen), saved);

        answer(null);
        files.saveGame(saved::add);
        assertEquals(1, saved.size(), "a cancel saves nothing");

        LobbyFiles.Ask load = files.ask(LobbyFiles.Route.LOAD_GAME, null);
        assertFalse(load.save());
        assertEquals(ask.directory(), load.directory());
        assertEquals(List.of("sav.gz", "sav"), load.extensions());
        File loaded = answer("quicksave.sav.gz");
        List<File> loads = new ArrayList<>();
        files.loadGame(loads::add);
        answer(null);
        files.loadGame(loads::add);
        assertEquals(List.of(loaded), loads, "the chosen save is passed on; a cancel loads nothing");
    }

    @Test
    void aBugReportAsksForAZipArchiveAndPassesTheChoiceOrTheCancelOn() {
        LobbyFiles.Ask ask = files.ask(LobbyFiles.Route.SAVE_BUG_REPORT, null);
        assertTrue(ask.save());
        assertEquals(PackageBugReportAction.archiveDirectory().getAbsoluteFile(), ask.directory());
        assertEquals(PackageBugReportAction.chooserTitle(), ask.title());
        assertEquals(PackageBugReportAction.chooserFilter(), ask.filterName());
        assertEquals(List.of("zip"), ask.extensions());
        assertTrue(ask.name().startsWith("MegaMek-BugReport") && ask.name().endsWith(".zip"), ask.name());

        File chosen = answer("report.zip");
        answer(null);
        List<File> archives = new ArrayList<>();
        files.saveBugReport(archives::add);
        files.saveBugReport(archives::add);
        assertEquals(Arrays.asList(chosen, null), archives, "the choice, then the cancel as null");
    }

    @Test
    void gameOptionsSaveAndLoadBack() {
        Vector<IBasicOption> saved = new Vector<>();
        saved.add(new BasicOption(OptionsConstants.BASE_FRIENDLY_FIRE, true));
        answer("options");
        AtomicInteger done = new AtomicInteger();
        files.saveGameOptions(saved, done::incrementAndGet);
        assertEquals(1, done.get());
        assertTrue(folder.resolve("options.xml").toFile().isFile(), ".xml is added as the Swing chooser adds it");

        GameOptions loaded = new GameOptions();
        assertFalse(loaded.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE));
        answer("options.xml");
        AtomicReference<Boolean> result = new AtomicReference<>();
        files.loadGameOptions(loaded, result::set);
        assertEquals(Boolean.TRUE, result.get());
        assertTrue(loaded.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertEquals(LobbyFiles.Route.LOAD_GAME_OPTIONS, asked.get(1).route());
    }

    @Test
    void anXmlFileWithoutOptionsIsRefused() throws Exception {
        Files.writeString(answer("other.xml").toPath(), "<board/>", StandardCharsets.UTF_8);
        AtomicReference<Boolean> result = new AtomicReference<>();
        files.loadGameOptions(new GameOptions(), result::set);
        assertEquals(Boolean.FALSE, result.get());
        assertEquals(List.of(Messages.getString("ClientGUI.errorLoadingFile") + ": "
              + Messages.getString("Lobby.files.notGameOptions", "other.xml")), messages);
    }

    @Test
    void aCancelLoadsNothingAndStillFinishes() {
        answer(null);
        AtomicReference<Boolean> result = new AtomicReference<>();
        files.loadGameOptions(new GameOptions(), result::set);
        assertEquals(Boolean.FALSE, result.get());
        answer(null);
        AtomicInteger done = new AtomicInteger();
        files.saveLoadout(new MunitionTree(), done::incrementAndGet);
        assertEquals(1, done.get());
        answer(null);
        AtomicReference<MunitionTree> tree = new AtomicReference<>(new MunitionTree());
        files.loadLoadout(tree::set);
        assertNull(tree.get());
        assertTrue(messages.isEmpty());
        assertTrue(errors.isEmpty());
    }

    @Test
    void aLoadoutSavesAndLoadsBack() {
        MunitionTree tree = new MunitionTree();
        tree.insertImperative("Atlas", "AS7-D", "any", "AC/20", "Precision", "Standard");
        answer("assault");
        files.saveLoadout(tree, () -> { });
        File saved = folder.resolve("assault.adf").toFile();
        assertTrue(saved.isFile(), ".adf is added as the Swing chooser adds it");

        answers.add(saved);
        AtomicReference<MunitionTree> loaded = new AtomicReference<>();
        files.loadLoadout(loaded::set);
        assertNotNull(loaded.get());
        assertEquals(tree.getPriorityList("Atlas", "AS7-D", "any", "AC/20"),
              loaded.get().getPriorityList("Atlas", "AS7-D", "any", "AC/20"));
    }

    @Test
    void aFileThatIsNoLoadoutIsReported() throws Exception {
        Files.writeString(answer("notes.txt").toPath(), "hello", StandardCharsets.UTF_8);
        AtomicReference<MunitionTree> loaded = new AtomicReference<>(new MunitionTree());
        files.loadLoadout(loaded::set);
        assertNull(loaded.get());
        assertEquals(List.of(LobbyError.ADF_READ), errors);
    }

    @Test
    void aUnitListSavesLoadsBackAndRemembersItsFolders() throws Exception {
        Game game = new Game();
        Player player = new Player(0, "Kai");
        game.addPlayer(0, player);
        Entity atlas = new MekFileParser(new File("testresources/data/mekfiles/Atlas AS7-D.mtf")).getEntity();
        atlas.setGame(game);
        atlas.setId(game.getNextEntityId());
        atlas.setCrew(new Crew(CrewType.SINGLE));
        atlas.setOwner(player);
        game.addEntity(atlas);

        answer("company");
        AtomicInteger done = new AtomicInteger();
        files.saveUnitList(game, player, done::incrementAndGet);
        assertEquals(1, done.get());
        assertEquals("Kai.mul", asked.get(0).name());
        File saved = folder.resolve("company.mul").toFile();
        assertTrue(saved.isFile(), ".mul is added as the Swing client adds it");
        assertEquals(1, new MULParser(saved, null).getEntities().size());
        assertEquals("", atlas.getForceString(), "the force stamps are cleared after saving");
        assertEquals(folder.toFile().getAbsoluteFile(), files.ask(LobbyFiles.Route.SAVE_UNIT_LIST, "Kai").directory());

        answers.add(saved);
        List<File> read = new ArrayList<>();
        files.loadUnitList(read::add, done::incrementAndGet);
        assertEquals(List.of(saved), read);
        assertEquals(2, done.get());
        assertEquals(folder.toFile().getAbsoluteFile(), files.ask(LobbyFiles.Route.LOAD_UNIT_LIST, null).directory());
    }

    @Test
    void aUnitListThatFailsToLoadIsReported() {
        answer("broken.mul");
        AtomicInteger done = new AtomicInteger();
        files.loadUnitList(file -> {
            throw new IllegalStateException("unreadable");
        }, done::incrementAndGet);
        assertEquals(1, done.get());
        assertEquals(List.of(Messages.getString("ClientGUI.errorLoadingFile") + ": unreadable"), messages);
    }

    @Test
    void aPlayerWithoutUnitsIsNotAskedForAListFile() {
        Game game = new Game();
        Player player = new Player(0, "Kai");
        game.addPlayer(0, player);
        AtomicInteger done = new AtomicInteger();
        files.saveUnitList(game, player, done::incrementAndGet);
        assertEquals(1, done.get());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aSpriteGetsItsPngExtension() {
        answer("atlas");
        List<File> written = new ArrayList<>();
        files.exportSprite("Atlas AS7-D", written::add);
        assertEquals(List.of(folder.resolve("atlas.png").toFile()), written);
        answer(null);
        files.exportSprite("Atlas AS7-D", written::add);
        assertEquals(1, written.size());
    }

    @Test
    void aGeneratedForceExportsAsAUnitListAndRemembersTheFolder() {
        answer("lance");
        List<File> written = new ArrayList<>();
        files.exportForce("Davion Lance", written::add);
        LobbyFiles.Ask ask = asked.get(0);
        assertTrue(ask.save());
        assertEquals(Messages.getString("ForceGeneratorDialog.exportMUL.title"), ask.title());
        assertEquals("Davion Lance.mul", ask.name());
        assertEquals(List.of("mul"), ask.extensions());
        assertEquals(List.of(folder.resolve("lance.mul").toFile()), written, ".mul is added");
        assertEquals(folder.toFile().getAbsoluteFile(), files.ask(LobbyFiles.Route.SAVE_UNIT_LIST, "Kai").directory(),
              "the unit lists' folder is where the force went");
        answer(null);
        files.exportForce("Davion Lance", written::add);
        assertEquals(1, written.size(), "a cancel writes nothing");
    }
}
