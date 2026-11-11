/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Vector;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilder;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.common.Player;
import megamek.common.containers.MunitionTree;
import megamek.common.game.Game;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.units.Entity;
import megamek.common.units.EntityListFile;
import megamek.logging.MMLogger;
import megamek.utilities.xml.MMXMLUtility;

/**
 * The files the lobby saves and loads through a file dialog: game options, ammunition loadouts, unit lists, unit
 * sprites and the game itself. Each route asks its {@link Chooser} for a file with the folder, title and file types the Swing choosers
 * used, then reads or writes it, telling the player through the {@link Report} what went wrong, with the Swing
 * client's messages. The unit list routes remember the folder last used, as the Swing client's choosers do.
 * <p>
 * Swing thread: every route is called there, and the chooser answers there.
 */
public final class LobbyFiles {
    private static final MMLogger LOGGER = MMLogger.create(LobbyFiles.class);
    /** The folder of the game options files, as the Swing game options dialog opens it. */
    static final File OPTIONS_FOLDER = new File("mmconf");
    private static final String MUL = "mul";
    private static final String STATS = "mmu";
    private static final String SAVE = MMConstants.SAVE_FILE_EXT.substring(1);
    private static final String SAVE_GZ = MMConstants.SAVE_FILE_GZ_EXT.substring(1);

    /** The file dialogs the lobby opens. */
    public enum Route {
        SAVE_GAME_OPTIONS(true),
        LOAD_GAME_OPTIONS(false),
        SAVE_LOADOUT(true),
        LOAD_LOADOUT(false),
        SAVE_UNIT_LIST(true),
        LOAD_UNIT_LIST(false),
        EXPORT_SPRITE(true),
        EXPORT_FORCE(true),
        EXPORT_STATS(true),
        SAVE_GAME(true),
        LOAD_GAME(false),
        SAVE_BUG_REPORT(true);

        private final boolean save;

        Route(boolean save) {
            this.save = save;
        }

        /** Whether the dialog saves a file rather than opens one. */
        public boolean save() {
            return save;
        }
    }

    /**
     * What a file dialog asks.
     *
     * @param directory  the folder it opens on; null leaves it to the system
     * @param title      its title
     * @param filterName the name of the file types it shows
     * @param extensions the extensions of those files, without the dot; a saved file gets the first unless it has one
     * @param name       the file name it suggests when saving, or null
     */
    public record Ask(Route route, @Nullable File directory, String title, String filterName, List<String> extensions,
          @Nullable String name) {
        public Ask {
            Objects.requireNonNull(route);
            extensions = List.copyOf(extensions);
        }

        /** Whether the dialog saves a file rather than opens one. */
        public boolean save() {
            return route.save();
        }
    }

    /** Shows the file dialog of an {@link Ask}. */
    @FunctionalInterface
    public interface Chooser {
        /**
         * Shows the dialog; {@code chosen} then gets the file on the Swing thread, or null when the player cancels.
         * A file to save to has one of the ask's extensions.
         */
        void choose(Ask ask, Consumer<File> chosen);
    }

    /** How the routes tell the player what went wrong. */
    public interface Report {
        void error(LobbyError error, Object... args);

        void message(String title, String text);
    }

    /** Reads a unit list into the game, as the client's Load List does. */
    @FunctionalInterface
    public interface ListLoader {
        void load(File file) throws Exception;
    }

    /** The folders the unit list dialogs last used, kept while MegaMek runs. */
    public static final class Folders {
        private File loadList = new File(".");
        private File saveList = new File(".");
    }

    private final Chooser chooser;
    private final Report report;
    private final Folders folders;

    public LobbyFiles(Chooser chooser, Report report, Folders folders) {
        this.chooser = Objects.requireNonNull(chooser);
        this.report = Objects.requireNonNull(report);
        this.folders = Objects.requireNonNull(folders);
    }

    /**
     * What {@code route}'s dialog asks.
     *
     * @param name the player's name for a unit list to save, the unit's for a sprite; otherwise ignored
     */
    public Ask ask(Route route, @Nullable String name) {
        return switch (route) {
            case SAVE_GAME_OPTIONS, LOAD_GAME_OPTIONS -> new Ask(route, OPTIONS_FOLDER,
                  Messages.getString(route.save() ? "Lobby.files.saveGameOptions" : "Lobby.files.loadGameOptions"),
                  "GameOptions", List.of("xml"), null);
            case SAVE_LOADOUT, LOAD_LOADOUT -> new Ask(route,
                  Paths.get(MMConstants.USER_LOADOUTS_DIR).toAbsolutePath().toFile(),
                  Messages.getString(route.save() ? "ClientGui.LoadoutSaveDialog.title"
                        : "ClientGui.LoadoutLoadDialog.title"),
                  Messages.getString("Lobby.files.loadoutFiles"), List.of("adf"), null);
            case SAVE_UNIT_LIST -> new Ask(route, folders.saveList,
                  Messages.getString("ClientGUI.saveUnitListFileDialog.title"),
                  Messages.getString("ClientGUI.descriptionMULFiles"), List.of(MUL),
                  (name == null) ? null : name + '.' + MUL);
            case LOAD_UNIT_LIST -> new Ask(route, folders.loadList,
                  Messages.getString("ClientGUI.openUnitListFileDialog.title"),
                  Messages.getString("Lobby.files.unitListFiles"), List.of(MUL, "mmu"), null);
            case EXPORT_SPRITE -> new Ask(route, null, Messages.getString("ExportUnitSpriteDialog.title"),
                  Messages.getString("Lobby.files.pngFiles"), List.of("png"), (name == null) ? null : name + ".png");
            case EXPORT_FORCE -> new Ask(route, folders.saveList,
                  Messages.getString("ForceGeneratorDialog.exportMUL.title"),
                  Messages.getString("ClientGUI.descriptionMULFiles"), List.of(MUL),
                  (name == null) ? null : name + '.' + MUL);
            case EXPORT_STATS -> new Ask(route, folders.saveList, Messages.getString("Save.text"),
                  Messages.getString("Lobby.files.mmuFiles"), List.of(STATS),
                  (name == null) ? null : name + '.' + STATS);
            case SAVE_GAME, LOAD_GAME -> new Ask(route, new File(MMConstants.SAVEGAME_DIR).getAbsoluteFile(),
                  Messages.getString(route.save() ? "Lobby.files.saveGame" : "Lobby.files.loadGame"),
                  Messages.getString("Lobby.files.saveGames"), List.of(SAVE_GZ, SAVE), null);
            case SAVE_BUG_REPORT -> new Ask(route, PackageBugReportAction.archiveDirectory().getAbsoluteFile(),
                  PackageBugReportAction.chooserTitle(), PackageBugReportAction.chooserFilter(), List.of("zip"),
                  PackageBugReportAction.suggestedArchiveName());
        };
    }

    /**
     * Asks where the bug report archive goes, as {@link PackageBugReportAction}'s chooser does, and hands the file to
     * {@code chosen}, or null when cancelled.
     */
    public void saveBugReport(Consumer<File> chosen) {
        chooser.choose(ask(Route.SAVE_BUG_REPORT, null), chosen);
    }

    // ---- The game

    /**
     * Asks for a file to save the game to, in the save game folder, and hands it to {@code save}; nothing when
     * cancelled. The server writes the game, so the file chosen is only its name and folder.
     */
    public void saveGame(Consumer<File> save) {
        chooser.choose(ask(Route.SAVE_GAME, null), file -> {
            if (file != null) {
                save.accept(file);
            }
        });
    }

    /** Asks for a saved game to load, in the save game folder, and hands it to {@code load}; nothing when cancelled. */
    public void loadGame(Consumer<File> load) {
        chooser.choose(ask(Route.LOAD_GAME, null), file -> {
            if (file != null) {
                load.accept(file);
            }
        });
    }

    // ---- Game options

    /** Asks for a file and saves {@code options} to it; {@code done} runs once saved or cancelled. */
    public void saveGameOptions(Vector<IBasicOption> options, Runnable done) {
        choose(ask(Route.SAVE_GAME_OPTIONS, null), file -> {
            if (file != null) {
                File saved = withXml(file);
                GameOptions.saveOptions(options, saved.getAbsolutePath());
                LOGGER.info("[LobbyFiles] Saved the game options to {}", saved);
            }
        }, done);
    }

    /**
     * Asks for a game options file and loads it into {@code into}; {@code done} gets whether one was loaded. The Swing
     * chooser showed only the XML files holding options; the system's dialog shows every XML file, so another one is
     * refused here with a message.
     */
    public void loadGameOptions(GameOptions into, Consumer<Boolean> done) {
        chooser.choose(ask(Route.LOAD_GAME_OPTIONS, null), chosen -> {
            boolean loaded = false;
            try {
                if (chosen != null) {
                    File file = withXml(chosen);
                    if (holdsGameOptions(file)) {
                        into.loadOptions(file, false);
                        loaded = true;
                        LOGGER.info("[LobbyFiles] Loaded the game options from {}", file);
                    } else {
                        report.message(Messages.getString("ClientGUI.errorLoadingFile"),
                              Messages.getString("Lobby.files.notGameOptions", file.getName()));
                    }
                }
            } finally {
                done.accept(loaded);
            }
        });
    }

    /** Whether {@code file} is an XML file with options, which the Swing game options chooser offered. */
    static boolean holdsGameOptions(File file) {
        if (!file.isFile()) {
            return false;
        }
        try {
            DocumentBuilder builder = MMXMLUtility.newSafeDocumentBuilder();
            return builder.parse(file).getElementsByTagName("options").getLength() > 0;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** {@code file}, with ".xml" added unless its name ends with it, as the Swing game options chooser does. */
    private static File withXml(File file) {
        return file.getName().endsWith(".xml") ? file : new File(file + ".xml");
    }

    // ---- Loadouts

    /** Asks for a file and saves {@code tree} to it; {@code done} runs once saved or cancelled. */
    public void saveLoadout(MunitionTree tree, Runnable done) {
        choose(ask(Route.SAVE_LOADOUT, null), file -> {
            if (file != null) {
                String path = file.getAbsolutePath();
                String saved = path.toLowerCase(Locale.ROOT).endsWith(".adf") ? path : path + ".adf";
                tree.writeToADFFilename(saved);
                LOGGER.info("[LobbyFiles] Saved the loadout to {}", saved);
            }
        }, done);
    }

    /**
     * Asks for a loadout file and reads it; {@code done} gets the loadout, or null when none was chosen or it could not
     * be read, which is reported.
     */
    public void loadLoadout(Consumer<MunitionTree> done) {
        chooser.choose(ask(Route.LOAD_LOADOUT, null), file -> {
            MunitionTree tree = null;
            try {
                if (file != null) {
                    tree = new MunitionTree(file.getAbsolutePath());
                    LOGGER.info("[LobbyFiles] Loaded the loadout from {}", file);
                }
            } catch (IllegalArgumentException unreadable) {
                report.error(LobbyError.ADF_READ, unreadable.getMessage());
            } finally {
                done.accept(tree);
            }
        });
    }

    // ---- Unit lists

    /**
     * Asks for a file, suggesting the player's name, and saves {@code player}'s units to it in force tree order, so
     * the list reloads with its forces as they were arranged; {@code done} runs once saved or cancelled. Nothing is
     * asked when the player has no units.
     */
    public void saveUnitList(Game game, Player player, Runnable done) {
        if (game.getPlayerEntities(player, false).isEmpty()) {
            done.run();
            return;
        }
        choose(ask(Route.SAVE_UNIT_LIST, player.getName()), file -> {
            if (file == null) {
                return;
            }
            folders.saveList = folder(file, folders.saveList);
            File saved = withMul(file);
            try {
                int count = writeUnitList(saved, game, player);
                LOGGER.info("[LobbyFiles] Saved {} unit(s) to {}", count, saved);
            } catch (Exception failure) {
                LOGGER.error(failure, "saveListFile");
                report.message(Messages.getString("ClientGUI.errorSavingFile"), String.valueOf(failure.getMessage()));
            }
        }, done);
    }

    /**
     * Writes {@code player}'s units to {@code file} as a unit list in force tree order, each stamped with its force
     * while it is written, so the list reloads with its forces as they were arranged.
     *
     * @return how many units it wrote
     */
    public static int writeUnitList(File file, Game game, Player player) throws IOException {
        ArrayList<Entity> units = new ArrayList<>(game.getForces()
              .sortedInTreeOrder(game.getPlayerEntities(player, false)));
        for (Entity unit : units) {
            unit.setForceString(game.getForces().forceStringFor(unit));
        }
        try {
            EntityListFile.saveTo(file, units);
            return units.size();
        } finally {
            // A stamp left behind would outrank the live force tree the next time the unit is written
            for (Entity unit : units) {
                unit.setForceString("");
            }
        }
    }

    /** {@code file}, with ".mul" added unless it is a MUL or XML file, as the Swing client saves a list. */
    private static File withMul(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return (name.endsWith('.' + MUL) || name.endsWith(".xml")) ? file : new File(file.getPath() + '.' + MUL);
    }

    /** Asks for a unit list and has {@code loader} read it; {@code done} runs once read or cancelled. */
    public void loadUnitList(ListLoader loader, Runnable done) {
        choose(ask(Route.LOAD_UNIT_LIST, null), file -> {
            if (file == null) {
                return;
            }
            folders.loadList = folder(file, folders.loadList);
            try {
                loader.load(file);
                LOGGER.info("[LobbyFiles] Loaded the unit list {}", file);
            } catch (Exception failure) {
                LOGGER.error(failure, "The lobby could not load the unit list {}", file);
                report.message(Messages.getString("ClientGUI.errorLoadingFile"), String.valueOf(failure.getMessage()));
            }
        }, done);
    }

    // ---- Sprites

    /** Asks for a PNG file, suggesting the unit's name, and hands it to {@code write}; nothing when cancelled. */
    public void exportSprite(String unitName, Consumer<File> write) {
        chooser.choose(ask(Route.EXPORT_SPRITE, unitName), file -> {
            if (file != null) {
                write.accept(file.getPath().endsWith(".png") ? file : new File(file.getPath() + ".png"));
            }
        });
    }

    // ---- Alpha Strike and SBF stats

    /** Writes a stats file; it may fail. */
    @FunctionalInterface
    public interface StatsWriter {
        void write(File file) throws IOException;
    }

    /**
     * Asks for an MMU file, suggesting {@code name}, in the unit lists' folder, and writes the stats to it; nothing
     * when cancelled, and a message when it cannot be written.
     */
    public void exportStats(String name, StatsWriter write) {
        chooser.choose(ask(Route.EXPORT_STATS, name), file -> {
            if (file == null) {
                return;
            }
            folders.saveList = folder(file, folders.saveList);
            File stats = file.getName().endsWith('.' + STATS) ? file : new File(file.getPath() + '.' + STATS);
            try {
                write.write(stats);
                LOGGER.info("[LobbyFiles] Saved the stats to {}", stats);
            } catch (IOException | RuntimeException failure) {
                LOGGER.error(failure, "The lobby could not save the stats to {}", stats);
                report.message(Messages.getString("ClientGUI.errorSavingFile"), String.valueOf(failure.getMessage()));
            }
        });
    }

    // ---- The Force Generator

    /**
     * Asks for a file, suggesting the force's name, in the unit lists' folder, and hands it to {@code write} as a MUL
     * file; nothing when cancelled.
     */
    public void exportForce(String forceName, Consumer<File> write) {
        chooser.choose(ask(Route.EXPORT_FORCE, forceName), file -> {
            if (file != null) {
                folders.saveList = folder(file, folders.saveList);
                write.accept(withMul(file));
            }
        });
    }

    /** Asks with {@code ask}, hands the choice to {@code use}, then runs {@code done}, even when {@code use} fails. */
    private void choose(Ask ask, Consumer<File> use, Runnable done) {
        chooser.choose(ask, file -> {
            try {
                use.accept(file);
            } finally {
                done.run();
            }
        });
    }

    private static File folder(File file, File otherwise) {
        File parent = file.getAbsoluteFile().getParentFile();
        return (parent == null) ? otherwise : parent;
    }
}
