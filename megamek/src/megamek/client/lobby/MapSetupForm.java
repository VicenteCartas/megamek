/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MapSetup;
import megamek.common.util.fileUtils.MegaMekFile;

/**
 * The lobby's map setup without a toolkit, the native counterpart of the Swing MapSetupDialog: the map's medium, its
 * size in boards, the board size, the board in each slot (fixed, rotated, generated, random or surprise), each
 * slot's theme and level offset, the searchable list of available boards, Generate and map setup files. Changes
 * apply at once through {@link Commands} to a local copy of the server's settings, as the dialog's do; {@link #reload}
 * takes the server's echo. {@link #picture()} reads the whole setup for a view. Runs on the Swing thread.
 */
public final class MapSetupForm {
    /** Searches shorter than this show the whole list, as the dialog's do. */
    public static final int SEARCH_MIN = 3;
    /** The folder map setup files start in, under the data directory. */
    public static final String SETUP_FOLDER = "mapsetup";
    public static final String SETUP_EXTENSION = ".xml";

    /** The lobby's map actions the form applies; {@link #of} adapts {@link LobbyCommands}. */
    public interface Commands {
        void medium(MapSettings settings, int medium);

        void resizeMap(MapSettings settings, int width, int height);

        void resizeBoards(MapSettings settings, int width, int height);

        void changeBoard(MapSettings settings, int slot, String board);

        void selectSingleBoard(MapSettings settings, String board, BoardDimensions size);

        void theme(MapSettings settings, int slot, @Nullable String theme);

        void levelOffset(MapSettings settings, int slot, int offset);

        void applySetup(MapSettings settings, MapSetup setup);

        void generate();

        static Commands of(LobbyCommands commands, Runnable generate) {
            return new Commands() {
                @Override
                public void medium(MapSettings settings, int medium) {
                    commands.changeMedium(settings, medium);
                }

                @Override
                public void resizeMap(MapSettings settings, int width, int height) {
                    commands.resizeMap(settings, width, height);
                }

                @Override
                public void resizeBoards(MapSettings settings, int width, int height) {
                    commands.resizeBoards(settings, width, height);
                }

                @Override
                public void changeBoard(MapSettings settings, int slot, String board) {
                    commands.changeBoard(settings, slot, board);
                }

                @Override
                public void selectSingleBoard(MapSettings settings, String board, BoardDimensions size) {
                    commands.selectSingleBoard(settings, board, size);
                }

                @Override
                public void theme(MapSettings settings, int slot, @Nullable String theme) {
                    commands.changeBoardTheme(settings, slot, theme);
                }

                @Override
                public void levelOffset(MapSettings settings, int slot, int offset) {
                    commands.changeBoardLevelOffset(settings, slot, offset);
                }

                @Override
                public void applySetup(MapSettings settings, MapSetup setup) {
                    commands.applyMapSetup(settings, setup.getMapWidth(), setup.getMapHeight(),
                          setup.getBoardWidth(), setup.getBoardHeight(), setup.getBoards());
                }

                @Override
                public void generate() {
                    generate.run();
                }
            };
        }
    }

    /** What a board slot holds. */
    public enum Kind { EMPTY, FIXED, GENERATED, SURPRISE }

    /**
     * A board slot as the map shows it.
     *
     * @param board     the slot's entry in the map settings, empty for an unset slot
     * @param file      the board to draw: the fixed board without its rotation prefix, or the first surprise board
     * @param surprise  the boards one of which is picked at launch, empty unless {@link Kind#SURPRISE}
     * @param canRotate whether the rotation can be toggled (a fixed board of even width)
     * @param adjustable whether theme and level offset apply (a set slot off space maps)
     */
    public record Slot(int index, String board, Kind kind, String label, String file, boolean rotated,
          List<String> surprise, boolean canRotate, boolean adjustable, @Nullable String theme, int levelOffset) {}

    /** An entry of the board list: the name the map settings use, its short label and its folder. */
    public record Entry(String name, String label, String folder) {
        public boolean generated() {
            return MapSettings.BOARD_GENERATED.equals(name);
        }
    }

    /** A theme a slot can take; {@code null} restores the board's own. */
    public record Theme(@Nullable String theme, String label) {}

    /**
     * The whole setup for a view.
     *
     * @param sizeIndex the index in {@code sizes} of the board size, or -1 for a custom size
     */
    public record Picture(int medium, int mapWidth, int mapHeight, int boardWidth, int boardHeight,
          List<BoardDimensions> sizes, int sizeIndex, boolean customSize, List<Slot> slots, List<Entry> boards,
          String search, boolean generable, String generateTip, boolean advancedSearch) {
        public boolean space() {
            return medium == MapSettings.MEDIUM_SPACE;
        }

        public boolean canShrinkWidth() {
            return mapWidth > LobbyCommands.MIN_MAP_SIZE;
        }

        public boolean canShrinkHeight() {
            return mapHeight > LobbyCommands.MIN_MAP_SIZE;
        }
    }

    private final Supplier<MapSettings> server;
    private final Supplier<Set<BoardDimensions>> availableSizes;
    private final Commands commands;
    private final Function<String, String> tagReader;
    private final Supplier<Collection<String>> artworkThemes;
    private final Map<String, String> tags = new HashMap<>();
    private MapSettings settings;
    private MapSettings seen;
    private String search = "";
    private boolean customSize;

    /**
     * @param server         the server's map settings, copied by {@link #reload}
     * @param availableSizes the board sizes the server has boards for
     * @param tagReader      a board name's tags, lowercase and joined; see {@link #fileTags}
     * @param artworkThemes  the board artwork's themes, offered along with the blueprint's special themes
     */
    public MapSetupForm(Supplier<MapSettings> server, Supplier<Set<BoardDimensions>> availableSizes,
          Commands commands, Function<String, String> tagReader, Supplier<Collection<String>> artworkThemes) {
        this.server = server;
        this.availableSizes = availableSizes;
        this.commands = commands;
        this.tagReader = tagReader;
        this.artworkThemes = artworkThemes;
        reload();
    }

    /** The board file's tags as the dialog's search reads them: joined with "||", lowercase. */
    public static String fileTags(String board) {
        File file = new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(board)).getFile();
        return String.join("||", Board.getTags(file)).toLowerCase(Locale.ROOT);
    }

    /** Takes the server's settings again, as an echo of a change or another player's. */
    public void reload() {
        seen = server.get();
        settings = MapSettings.getInstance(seen);
    }

    /**
     * Takes the server's settings again only when it sent others since, so a change sent and not yet echoed stays.
     *
     * @return whether it did
     */
    public boolean reloadIfNew() {
        if (server.get() == seen) {
            return false;
        }
        reload();
        return true;
    }

    /** The local copy the commands change; views must not change it. */
    public MapSettings settings() {
        return settings;
    }

    public Picture picture() {
        List<BoardDimensions> sizes = sortedSizes();
        int sizeIndex = sizes.indexOf(settings.getBoardSize());
        boolean custom = customSize || (sizeIndex < 0);
        List<String> selected = settings.getBoardsSelectedVector();
        boolean surprise = false;
        boolean unset = false;
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < settings.getMapWidth() * settings.getMapHeight(); i++) {
            String board = (i < selected.size()) ? selected.get(i) : null;
            unset |= board == null;
            surprise |= (board != null) && board.startsWith(MapSettings.BOARD_SURPRISE);
            slots.add(slot(i, board));
        }
        String tip;
        if (surprise) {
            tip = "ChatLounge.GenerateBattlefieldTooltipSurprise";
        } else if (unset) {
            tip = "ChatLounge.GenerateBattlefieldTooltipIncomplete";
        } else {
            tip = "ChatLounge.GenerateBattlefieldTooltip";
        }
        boolean space = settings.getMedium() == MapSettings.MEDIUM_SPACE;
        return new Picture(settings.getMedium(), settings.getMapWidth(), settings.getMapHeight(),
              settings.getBoardWidth(), settings.getBoardHeight(), sizes, custom ? -1 : sizeIndex, custom,
              List.copyOf(slots), space ? List.of() : boards(), search, !surprise && !unset,
              Messages.getString(tip), !space && (settings.getMapWidth() == 1) && (settings.getMapHeight() == 1));
    }

    private List<BoardDimensions> sortedSizes() {
        List<BoardDimensions> sizes = new ArrayList<>(availableSizes.get());
        sizes.sort(Comparator.comparingInt(BoardDimensions::w).thenComparingInt(BoardDimensions::h));
        return List.copyOf(sizes);
    }

    private Slot slot(int index, @Nullable String board) {
        String value = (board == null) ? "" : board;
        Kind kind;
        List<String> surprise = List.of();
        String file = "";
        if (value.isBlank()) {
            kind = Kind.EMPTY;
        } else if (value.startsWith(MapSettings.BOARD_GENERATED)) {
            kind = Kind.GENERATED;
        } else if (value.startsWith(MapSettings.BOARD_SURPRISE)) {
            kind = Kind.SURPRISE;
            surprise = surpriseBoards(value);
            file = surprise.isEmpty() ? "" : surprise.get(0);
        } else {
            kind = Kind.FIXED;
            file = unrotated(value);
        }
        boolean rotated = value.startsWith(Board.BOARD_REQUEST_ROTATION);
        boolean adjustable = (kind != Kind.EMPTY) && (settings.getMedium() != MapSettings.MEDIUM_SPACE);
        return new Slot(index, value, kind, label(value), file, rotated, surprise,
              (kind == Kind.FIXED) && evenBoardWidth(), adjustable, settings.getBoardTheme(index),
              settings.getBoardLevelOffset(index));
    }

    private static List<String> surpriseBoards(String board) {
        List<String> result = new ArrayList<>();
        for (String name : board.substring(MapSettings.BOARD_SURPRISE.length()).split("\n")) {
            if (!name.isBlank()) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    private static String unrotated(String board) {
        return board.startsWith(Board.BOARD_REQUEST_ROTATION)
              ? board.substring(Board.BOARD_REQUEST_ROTATION.length()) : board;
    }

    private boolean evenBoardWidth() {
        return (settings.getBoardWidth() % 2) == 0;
    }

    /** A board name as the map and list show it: no folder, extension or size, as the Swing lobby's. */
    public String label(String board) {
        if (board.isBlank()) {
            return "";
        }
        String name = board;
        if (board.startsWith(MapSettings.BOARD_SURPRISE)) {
            name = Messages.getString("ChatLounge.MapSurprise") + " ("
                  + surpriseBoards(board).size() + " boards)";
        } else if (board.startsWith(MapSettings.BOARD_GENERATED)) {
            name = Messages.getString("ChatLounge.MapGenerated");
        } else {
            name = new File(unrotated(board)).getName();
        }
        String size = settings.getBoardWidth() + "x" + settings.getBoardHeight();
        return BoardFile.withoutExtension(name).replace(size, "").trim();
    }

    // region Board list

    /** The available boards: Generated first, or only the matches of a search of {@link #SEARCH_MIN} letters. */
    public List<Entry> boards() {
        List<String> names = new ArrayList<>();
        if (search.length() < SEARCH_MIN) {
            names.add(MapSettings.BOARD_GENERATED);
            names.addAll(settings.getBoardsAvailableVector());
        } else {
            names.addAll(searched(search));
        }
        List<Entry> result = new ArrayList<>();
        for (String name : names) {
            String folder = (name.startsWith(MapSettings.BOARD_GENERATED)) ? ""
                  : Objects.toString(new File(name).getParent(), "").replace('\\', '/');
            result.add(new Entry(name, label(name), folder));
        }
        return List.copyOf(result);
    }

    /** The dialog's search: each ";" token must match a board file's name or one of its tags. */
    List<String> searched(String text) {
        List<String> result = new ArrayList<>(settings.getBoardsAvailableVector());
        for (String token : text.toLowerCase(Locale.ROOT).split(";")) {
            List<String> kept = new ArrayList<>();
            for (String board : result) {
                boolean byName = board.toLowerCase(Locale.ROOT).contains(token) && isBoardFile(board);
                if (byName || tags(board).contains(token)) {
                    kept.add(board);
                }
            }
            result = kept;
        }
        return result;
    }

    private String tags(String board) {
        return tags.computeIfAbsent(board, tagReader);
    }

    private static boolean isBoardFile(String board) {
        return !board.startsWith(MapSettings.BOARD_GENERATED) && !board.startsWith(MapSettings.BOARD_RANDOM)
              && !board.startsWith(MapSettings.BOARD_SURPRISE);
    }

    public void search(String text) {
        search = (text == null) ? "" : text.trim();
    }

    public String search() {
        return search;
    }

    // endregion

    // region Map and board size

    public void medium(int medium) {
        if (medium != settings.getMedium()) {
            commands.medium(settings, medium);
        }
    }

    /**
     * Sets the number of boards across, within the lobby's bounds.
     *
     * @return whether the odd board nag is due: the map grew wider with boards of odd width
     */
    public boolean mapWidth(int width) {
        int old = settings.getMapWidth();
        if ((width == old) || (width < LobbyCommands.MIN_MAP_SIZE) || (width > LobbyCommands.MAX_MAP_SIZE)) {
            return false;
        }
        commands.resizeMap(settings, width, settings.getMapHeight());
        return (width > old) && oddBoardNagDue();
    }

    public void mapHeight(int height) {
        if ((height != settings.getMapHeight()) && (height >= LobbyCommands.MIN_MAP_SIZE)
              && (height <= LobbyCommands.MAX_MAP_SIZE)) {
            commands.resizeMap(settings, settings.getMapWidth(), height);
        }
    }

    /** Whether boards of odd width are tiled across: the dialog's "non-tileable board" warning. */
    public boolean oddBoardNagDue() {
        return !evenBoardWidth() && (settings.getMapWidth() > 1);
    }

    /** Picks one of the available board sizes; leaves custom size. */
    public void boardSize(BoardDimensions size) {
        customSize = false;
        if (!size.equals(settings.getBoardSize())) {
            commands.resizeBoards(settings, size.width(), size.height());
        }
    }

    /** Shows the custom board size fields without changing the size. */
    public void customSize() {
        customSize = true;
    }

    /**
     * Sets the custom board width, within the lobby's bounds.
     *
     * @return whether the odd board nag is due
     */
    public boolean boardWidth(int width) {
        if ((width == settings.getBoardWidth()) || (width < LobbyCommands.MIN_BOARD_SIZE)
              || (width > LobbyCommands.MAX_BOARD_SIZE)) {
            return false;
        }
        commands.resizeBoards(settings, width, settings.getBoardHeight());
        return oddBoardNagDue();
    }

    public void boardHeight(int height) {
        if ((height != settings.getBoardHeight()) && (height >= LobbyCommands.MIN_BOARD_SIZE)
              && (height <= LobbyCommands.MAX_BOARD_SIZE)) {
            commands.resizeBoards(settings, settings.getBoardWidth(), height);
        }
    }

    // endregion

    // region Slots

    private boolean validSlot(int slot) {
        return (slot >= 0) && (slot < settings.getMapWidth() * settings.getMapHeight());
    }

    /** Puts a board (or Generated) into a slot. */
    public void assign(int slot, String board) {
        if (validSlot(slot) && !board.isBlank()) {
            commands.changeBoard(settings, slot, board);
        }
    }

    /** Puts a board into a slot turned by 180 degrees; only boards of even width can be. */
    public void assignRotated(int slot, String board) {
        if (evenBoardWidth() && isBoardFile(board)) {
            assign(slot, Board.BOARD_REQUEST_ROTATION + unrotated(board));
        }
    }

    /** Whether boards can be put in rotated: the board width is even. */
    public boolean canRotate() {
        return evenBoardWidth();
    }

    /** Puts one of the boards, picked now and not shown to the others, into a slot. */
    public void assignRandom(int slot, List<String> boards, Random random) {
        if (boards.size() > 1) {
            assign(slot, boards.get(random.nextInt(boards.size())));
        }
    }

    /** Makes a slot a surprise board: one of the boards is picked at launch. */
    public void assignSurprise(int slot, List<String> boards) {
        if (boards.size() > 1) {
            assign(slot, String.join("\n", boards));
        }
    }

    /** Turns a slot's fixed board by 180 degrees, or back. */
    public void toggleRotation(int slot) {
        if (!validSlot(slot) || (slot >= settings.getBoardsSelectedVector().size())) {
            return;
        }
        Slot current = slot(slot, settings.getBoardsSelectedVector().get(slot));
        if (current.canRotate()) {
            String board = current.rotated() ? unrotated(current.board())
                  : Board.BOARD_REQUEST_ROTATION + current.board();
            commands.changeBoard(settings, slot, board);
        }
    }

    /** The themes a slot can take: its own first, then grass, the artwork's and the blueprint's special themes. */
    public List<Theme> themes(int slot) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.add("grass");
        for (String theme : artworkThemes.get()) {
            if ((theme != null) && !theme.isBlank()) {
                names.add(theme);
            }
        }
        names.addAll(BoardEditorBlueprint.get().specialThemes());
        String selected = validSlot(slot) ? settings.getBoardTheme(slot) : null;
        if (selected != null) {
            names.add(selected);
        }
        List<Theme> result = new ArrayList<>();
        result.add(new Theme(null, Messages.getString("ChatLounge.MapPreviewButton.defaultTheme")));
        for (String name : names) {
            result.add(new Theme(name, BoardEditorBlueprint.themeLabel(name)));
        }
        return List.copyOf(result);
    }

    public void theme(int slot, @Nullable String theme) {
        if (validSlot(slot) && !Objects.equals(theme, settings.getBoardTheme(slot))) {
            commands.theme(settings, slot, theme);
        }
    }

    public void levelOffset(int slot, int offset) {
        if (validSlot(slot) && (offset >= MapSettings.MIN_BOARD_LEVEL_OFFSET)
              && (offset <= MapSettings.MAX_BOARD_LEVEL_OFFSET) && (offset != settings.getBoardLevelOffset(slot))) {
            commands.levelOffset(settings, slot, offset);
        }
    }

    /**
     * Makes the map the single board an advanced search found, as the dialog does.
     *
     * @param path the board file's path under the boards folder
     * @return whether the board's size could be read and it was selected
     */
    public boolean singleBoard(String path) {
        BoardDimensions size = Board.getSize(new MegaMekFile(Configuration.boardsDir(), path).getFile());
        if (size == null) {
            return false;
        }
        commands.selectSingleBoard(settings, BoardFile.selectionName(path).replace("\\", "/"), size);
        return true;
    }

    public void generate() {
        if (picture().generable()) {
            commands.generate();
        }
    }

    // endregion

    // region Map setup files

    /** The folder the map setup file dialogs start in. */
    public static File setupFolder() {
        return new File(Configuration.dataDir(), SETUP_FOLDER);
    }

    /** The file with the map setup extension, added unless it has it. */
    public static File withExtension(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(SETUP_EXTENSION) ? file
              : new File(file.getPath() + SETUP_EXTENSION);
    }

    /** Writes the map setup: map and board size and the boards. */
    public void save(File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            MapSetup.save(out, settings);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    /**
     * Reads a map setup and applies it.
     *
     * @throws FileNotFoundException when there is no such file
     */
    public void load(File file) throws IOException {
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getPath());
        }
        MapSetup setup;
        try (InputStream in = new FileInputStream(file)) {
            setup = MapSetup.load(in);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        commands.applySetup(settings, setup);
    }

    // endregion
}
