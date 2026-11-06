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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
import megamek.utilities.BoardClassifier;
import megamek.utilities.BoardsTagger;

/**
 * The lobby's map setup without a toolkit, the native counterpart of the Swing MapSetupDialog: the map's medium, its
 * size in boards, the board size, the board in each slot (fixed, rotated, generated, random or surprise), each
 * slot's theme and level offset, the searchable and filterable list of available boards (on a one-board map, the
 * whole board library at any size, as the Swing advanced map search), Generate and map setup files. Changes
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
                    commands.applyMapSetup(settings, setup);
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
     * @param file      the fixed board without its rotation prefix, empty for generated, surprise or unset slots
     * @param stack     the unique boards available to this slot, whether visible or a surprise
     * @param canRotate whether the rotation can be toggled (a fixed board of even width)
     * @param adjustable whether theme and level offset apply (a set slot off space maps)
     */
    public record Slot(int index, String board, Kind kind, String label, String file, boolean rotated,
          List<String> stack, boolean canRotate, boolean adjustable, @Nullable String theme, int levelOffset) {}

    /** An entry of the board list: the name the map settings use, its short label, its folder and its size. */
    public record Entry(String name, String label, String folder, int width, int height) {
        public boolean generated() {
            return MapSettings.BOARD_GENERATED.equals(name);
        }
    }

    /** A board of the library at any size: its name as the map settings use it, its size and its tags. */
    public record LibraryBoard(String name, int width, int height, List<String> tags) {
        public LibraryBoard {
            tags = List.copyOf(tags);
        }
    }

    /** Whether the board library, every board at any size, is read: a slow scan, wanted only by the filters. */
    public enum Library { UNREAD, READING, READ }

    /**
     * The board browser's filters, the Swing advanced map search's.
     *
     * @param anySize browse the whole library at any size rather than the boards of the current size; only a one-board
     *                map takes it, and placing a board then resizes the map to it
     * @param tags    the tags a board must have: any one of them, or all with {@code allTags}; none passes every board
     * @param folders the top folders under the boards folder a board must be in, "" for those directly in it; none
     *                passes every board
     */
    public record Filter(boolean anySize, @Nullable Integer minWidth, @Nullable Integer maxWidth,
          @Nullable Integer minHeight, @Nullable Integer maxHeight, Set<String> tags, boolean allTags,
          Set<String> folders) {
        public static final Filter NONE = new Filter(false, null, null, null, null, Set.of(), false, Set.of());

        public Filter {
            tags = Set.copyOf(tags);
            folders = Set.copyOf(folders);
        }

        public Filter withAnySize(boolean any) {
            return new Filter(any, minWidth, maxWidth, minHeight, maxHeight, tags, allTags, folders);
        }

        public Filter withWidth(@Nullable Integer min, @Nullable Integer max) {
            return new Filter(anySize, min, max, minHeight, maxHeight, tags, allTags, folders);
        }

        public Filter withHeight(@Nullable Integer min, @Nullable Integer max) {
            return new Filter(anySize, minWidth, maxWidth, min, max, tags, allTags, folders);
        }

        public Filter withTag(String tag, boolean on) {
            return new Filter(anySize, minWidth, maxWidth, minHeight, maxHeight, toggled(tags, tag, on), allTags,
                  folders);
        }

        public Filter withAllTags(boolean all) {
            return new Filter(anySize, minWidth, maxWidth, minHeight, maxHeight, tags, all, folders);
        }

        public Filter withFolder(String folder, boolean on) {
            return new Filter(anySize, minWidth, maxWidth, minHeight, maxHeight, tags, allTags,
                  toggled(folders, folder, on));
        }

        /** The filters at work; the size ones only count while browsing any size, which only a one-board map can. */
        public int count(boolean anySizeAllowed) {
            boolean any = anySize && anySizeAllowed;
            int count = any ? 1 : 0;
            if (any && ((minWidth != null) || (maxWidth != null))) {
                count++;
            }
            if (any && ((minHeight != null) || (maxHeight != null))) {
                count++;
            }
            if (!tags.isEmpty()) {
                count++;
            }
            if (!folders.isEmpty()) {
                count++;
            }
            return count;
        }

        private static Set<String> toggled(Set<String> set, String value, boolean on) {
            Set<String> next = new HashSet<>(set);
            if (on) {
                next.add(value);
            } else {
                next.remove(value);
            }
            return next;
        }
    }

    /** A theme a slot can take; {@code null} restores the board's own. */
    public record Theme(@Nullable String theme, String label) {}

    /**
     * The whole setup for a view.
     *
     * @param sizeIndex      the index in {@code sizes} of the board size, or -1 for a custom size
     * @param advancedSearch whether the browser can show boards of any size: a one-board map off space
     * @param tagChoices     the tags of the boards in scope, and those chosen; empty until the library is read
     * @param folderChoices  the top folders of the boards in scope, and those chosen
     */
    public record Picture(int medium, int mapWidth, int mapHeight, int boardWidth, int boardHeight,
          List<BoardDimensions> sizes, int sizeIndex, boolean customSize, List<Slot> slots, List<Entry> boards,
          String search, boolean generable, String generateTip, boolean advancedSearch, Filter filter,
          Library library, List<String> tagChoices, List<String> folderChoices) {
        public boolean space() {
            return medium == MapSettings.MEDIUM_SPACE;
        }

        /** Whether the browser shows the library at any size. */
        public boolean anySize() {
            return advancedSearch && filter.anySize();
        }

        public int filterCount() {
            return filter.count(advancedSearch);
        }

        public boolean hasGeneratedBoards() {
            return !space() && slots.stream().anyMatch(slot -> slot.kind() == Kind.GENERATED);
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
    /** Sent setups awaiting their echoes, in send order; an older echo must not undo later edits. */
    private final List<MapSettings> pending = new ArrayList<>();
    private String search = "";
    private boolean customSize;
    private Filter filter = Filter.NONE;
    private boolean libraryWanted;
    /** The library by {@link #key}, in name order; null until read. */
    private @Nullable Map<String, LibraryBoard> library;
    /** The library's tags by {@link #key}, lowercase and joined as {@link #fileTags}. */
    private final Map<String, String> libraryTags = new HashMap<>();

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
        pending.clear();
    }

    /**
     * Takes the server's settings again only when it sent others since, so a change sent and not yet echoed stays.
     *
     * @return whether it did
     */
    public boolean reloadIfNew() {
        MapSettings received = server.get();
        if (received == seen) {
            return false;
        }
        int acknowledged = -1;
        for (int index = 0; index < pending.size(); index++) {
            if (sameSetup(received, pending.get(index))) {
                acknowledged = index;
                break;
            }
        }
        boolean unchanged = sameSetup(received, seen);
        seen = received;
        if (acknowledged >= 0) {
            pending.subList(0, acknowledged + 1).clear();
        } else if (!unchanged) {
            // An unrelated server edit is authoritative; do not hold it behind an unacknowledged local change.
            pending.clear();
        }
        if (pending.isEmpty()) {
            settings = MapSettings.getInstance(received);
        } else {
            settings.setBoardsAvailableVector(received.getBoardsAvailableVector());
        }
        return true;
    }

    private void sent() {
        pending.add(MapSettings.getInstance(settings));
    }

    /** Compares the setup, excluding the server's catalogue of available boards. */
    public static boolean sameSetup(MapSettings first, MapSettings second) {
        return first.equalBoardConfiguration(second) && first.equalBoardStacks(second);
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
        boolean advanced = anySizeAllowed();
        return new Picture(settings.getMedium(), settings.getMapWidth(), settings.getMapHeight(),
              settings.getBoardWidth(), settings.getBoardHeight(), sizes, custom ? -1 : sizeIndex, custom,
              List.copyOf(slots), space ? List.of() : boards(), search, !surprise && !unset,
              Messages.getString(tip), advanced, filter, libraryState(), space ? List.of() : tagChoices(),
              space ? List.of() : folderChoices());
    }

    private boolean anySizeAllowed() {
        return (settings.getMedium() != MapSettings.MEDIUM_SPACE) && (settings.getMapWidth() == 1)
              && (settings.getMapHeight() == 1);
    }

    private boolean anySizeActive() {
        return filter.anySize() && anySizeAllowed();
    }

    private List<BoardDimensions> sortedSizes() {
        List<BoardDimensions> sizes = new ArrayList<>(availableSizes.get());
        sizes.sort(Comparator.comparingInt(BoardDimensions::w).thenComparingInt(BoardDimensions::h));
        return List.copyOf(sizes);
    }

    private Slot slot(int index, @Nullable String board) {
        String value = (board == null) ? "" : board;
        Kind kind;
        String file = "";
        if (value.isBlank()) {
            kind = Kind.EMPTY;
        } else if (value.startsWith(MapSettings.BOARD_GENERATED)) {
            kind = Kind.GENERATED;
        } else if (value.startsWith(MapSettings.BOARD_SURPRISE)) {
            kind = Kind.SURPRISE;
        } else {
            kind = Kind.FIXED;
            file = unrotated(value);
        }
        boolean rotated = value.startsWith(Board.BOARD_REQUEST_ROTATION);
        boolean adjustable = (kind != Kind.EMPTY) && (settings.getMedium() != MapSettings.MEDIUM_SPACE);
        return new Slot(index, value, kind, label(value), file, rotated, settings.getBoardStack(index),
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
        return label(board, settings.getBoardWidth(), settings.getBoardHeight());
    }

    public static String label(String board, int width, int height) {
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
        String size = width + "x" + height;
        return BoardFile.withoutExtension(name).replace(size, "").trim();
    }

    // region Board list

    /**
     * The boards in scope that pass the filters and a search of {@link #SEARCH_MIN} letters or more, Generated first
     * when neither a search nor a filter narrows the boards of the current size. In scope are the server's boards of
     * the current size, or, browsing any size, the whole library.
     */
    public List<Entry> boards() {
        boolean any = anySizeActive();
        List<Entry> result = new ArrayList<>();
        if (!any && (search.length() < SEARCH_MIN) && filter.tags().isEmpty() && filter.folders().isEmpty()) {
            result.add(new Entry(MapSettings.BOARD_GENERATED, label(MapSettings.BOARD_GENERATED), "",
                  settings.getBoardWidth(), settings.getBoardHeight()));
        }
        List<String> tokens = (search.length() < SEARCH_MIN) ? List.of()
              : List.of(search.toLowerCase(Locale.ROOT).split(";"));
        Set<String> wanted = new HashSet<>();
        filter.tags().forEach(tag -> wanted.add(tag.toLowerCase(Locale.ROOT)));
        for (LibraryBoard board : inScope(any)) {
            if (passes(board, any, wanted, tokens)) {
                String folder = Objects.toString(new File(board.name()).getParent(), "").replace('\\', '/');
                result.add(new Entry(board.name(), label(board.name(), board.width(), board.height()), folder,
                      board.width(), board.height()));
            }
        }
        return List.copyOf(result);
    }

    /** The boards to browse: the library at any size, or the server's boards of the current size. */
    private List<LibraryBoard> inScope(boolean any) {
        if (any) {
            return (library == null) ? List.of() : List.copyOf(library.values());
        }
        List<LibraryBoard> result = new ArrayList<>();
        for (String name : settings.getBoardsAvailableVector()) {
            result.add(new LibraryBoard(name, settings.getBoardWidth(), settings.getBoardHeight(), List.of()));
        }
        return result;
    }

    private boolean passes(LibraryBoard board, boolean any, Set<String> wanted, List<String> tokens) {
        if (!filter.folders().isEmpty() && !filter.folders().contains(topFolder(board.name()))) {
            return false;
        }
        if (any && (!within(board.width(), filter.minWidth(), filter.maxWidth())
              || !within(board.height(), filter.minHeight(), filter.maxHeight()))) {
            return false;
        }
        if (!wanted.isEmpty()) {
            Set<String> has = Set.of(tagText(board.name()).split("\\|\\|"));
            boolean match = filter.allTags() ? has.containsAll(wanted) : wanted.stream().anyMatch(has::contains);
            if (!match) {
                return false;
            }
        }
        // The dialog's search: each ";" token must match a board file's name or one of its tags
        for (String token : tokens) {
            boolean byName = board.name().toLowerCase(Locale.ROOT).contains(token) && isBoardFile(board.name());
            if (!byName && !tagText(board.name()).contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean within(int value, @Nullable Integer min, @Nullable Integer max) {
        return ((min == null) || (value >= min)) && ((max == null) || (value <= max));
    }

    /** A board's tags, lowercase and joined: the library's once read, else the board file's. */
    private String tagText(String board) {
        String known = libraryTags.get(key(board));
        return (known != null) ? known : tags.computeIfAbsent(board, tagReader);
    }

    /** The tags of the boards in scope, as the library spells them, with the chosen ones; none before it is read. */
    private List<String> tagChoices() {
        if (library == null) {
            return List.of();
        }
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (LibraryBoard board : inScope(anySizeActive())) {
            LibraryBoard known = library.get(key(board.name()));
            if (known != null) {
                result.addAll(known.tags());
            }
        }
        result.addAll(filter.tags());
        return List.copyOf(result);
    }

    /** The top folders of the boards in scope, with the chosen ones. */
    private List<String> folderChoices() {
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (LibraryBoard board : inScope(anySizeActive())) {
            result.add(topFolder(board.name()));
        }
        result.addAll(filter.folders());
        return List.copyOf(result);
    }

    /** The first folder of a board name under the boards folder, or "" for a board directly in it. */
    public static String topFolder(String board) {
        String path = key(board);
        int slash = path.indexOf('/');
        return (slash < 0) ? "" : path.substring(0, slash);
    }

    /** A board name with forward slashes and no leading one, so the server's names and the library's meet. */
    private static String key(String board) {
        String path = board.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
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

    public Filter filter() {
        return filter;
    }

    /** Changes the filters; browsing any size or by tag wants the library. */
    public void filter(UnaryOperator<Filter> change) {
        filter = change.apply(filter);
        if (filter.anySize() || !filter.tags().isEmpty()) {
            libraryWanted = true;
        }
    }

    /** Asks for the library, as the filters open, so their tags can show. */
    public void wantLibrary() {
        libraryWanted = true;
    }

    /** Whether the library is wanted and not yet read; the controller reads it off the Swing thread. */
    public boolean libraryDue() {
        return libraryWanted && (library == null);
    }

    public Library libraryState() {
        if (library != null) {
            return Library.READ;
        }
        return libraryWanted ? Library.READING : Library.UNREAD;
    }

    /** Takes the library {@link #readLibrary} read; the first of two boards of one name stays. */
    public void library(Collection<LibraryBoard> boards) {
        Map<String, LibraryBoard> read = new LinkedHashMap<>();
        libraryTags.clear();
        boards.stream().sorted(Comparator.comparing(LibraryBoard::name)).forEach(board -> {
            if (read.putIfAbsent(key(board.name()), board) == null) {
                libraryTags.put(key(board.name()), String.join("||", board.tags()).toLowerCase(Locale.ROOT));
            }
        });
        library = read;
    }

    /**
     * Reads every board at any size, with its tags, from the board classifier: a slow scan the first time, so not on
     * the Swing thread.
     */
    public static List<LibraryBoard> readLibrary() {
        BoardClassifier classifier = BoardClassifier.getInstance();
        Map<String, List<String>> tagsByPath = new HashMap<>();
        for (Map.Entry<BoardsTagger.Tags, List<String>> tagged : classifier.getBoardsByTag().entrySet()) {
            for (String path : tagged.getValue()) {
                addTag(tagsByPath, path, tagged.getKey().getName());
            }
        }
        for (Map.Entry<String, List<String>> tagged : classifier.getBoardsByManualTag().entrySet()) {
            for (String path : tagged.getValue()) {
                addTag(tagsByPath, path, tagged.getKey());
            }
        }
        List<LibraryBoard> result = new ArrayList<>();
        for (Map.Entry<String, String> board : classifier.getBoardPaths().entrySet()) {
            Integer width = classifier.getBoardWidth().get(board.getKey());
            Integer height = classifier.getBoardHeight().get(board.getKey());
            if ((width != null) && (height != null)) {
                String name = BoardFile.selectionName(board.getValue()).replace('\\', '/');
                result.add(new LibraryBoard(name, width, height,
                      tagsByPath.getOrDefault(board.getKey(), List.of())));
            }
        }
        result.sort(Comparator.comparing(LibraryBoard::name));
        return result;
    }

    /** Adds a board's tag as {@link #tagLabel} spells it, once, so a manual tag and its automatic twin merge. */
    private static void addTag(Map<String, List<String>> tagsByPath, String path, String tag) {
        List<String> tags = tagsByPath.computeIfAbsent(path, k -> new ArrayList<>());
        String label = tagLabel(tag);
        if (tags.stream().noneMatch(label::equalsIgnoreCase)) {
            tags.add(label);
        }
    }

    /**
     * A board tag as a player reads it: an automatic tag, such as "LightForest (Auto)", loses its suffix and becomes
     * words, "Light forest"; a manual tag stays as written.
     */
    public static String tagLabel(String tag) {
        String suffix = " (Auto)";
        if (!tag.endsWith(suffix)) {
            return tag;
        }
        String words = tag.substring(0, tag.length() - suffix.length()).replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        return words.isEmpty() ? words : words.charAt(0) + words.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The size a board of the list is drawn at: its own while browsing any size, else the current board size. */
    public BoardDimensions sizeOf(String board) {
        LibraryBoard known = (library == null) ? null : library.get(key(board));
        return (anySizeActive() && (known != null)) ? new BoardDimensions(known.width(), known.height())
              : settings.getBoardSize();
    }

    // endregion

    // region Map and board size

    public void medium(int medium) {
        if (medium != settings.getMedium()) {
            commands.medium(settings, medium);
            sent();
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
        sent();
        return (width > old) && oddBoardNagDue();
    }

    public void mapHeight(int height) {
        if ((height != settings.getMapHeight()) && (height >= LobbyCommands.MIN_MAP_SIZE)
              && (height <= LobbyCommands.MAX_MAP_SIZE)) {
            commands.resizeMap(settings, settings.getMapWidth(), height);
            sent();
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
            sent();
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
        sent();
        return oddBoardNagDue();
    }

    public void boardHeight(int height) {
        if ((height != settings.getBoardHeight()) && (height >= LobbyCommands.MIN_BOARD_SIZE)
              && (height <= LobbyCommands.MAX_BOARD_SIZE)) {
            commands.resizeBoards(settings, settings.getBoardWidth(), height);
            sent();
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
            settings.setBoardStack(slot, List.of());
            commands.changeBoard(settings, slot, board);
            sent();
        }
    }

    /** Adds unique alternatives without replacing the visible board or changing surprise mode. */
    public void addToStack(int slot, List<String> boards) {
        if (!validSlot(slot) || settings.getMedium() == MapSettings.MEDIUM_SPACE) { return; }
        List<String> current = settings.getBoardStack(slot);
        List<String> added = new ArrayList<>(current);
        for (String board : boards) {
            if (!board.isBlank() && !added.contains(MapSettings.stackBoardName(board))) {
                added.add(MapSettings.stackBoardName(board));
            }
        }
        if (added.equals(current) || added.isEmpty()) { return; }
        String selected = settings.getBoardsSelectedVector().get(slot);
        updateStack(slot, added, selected == null || selected.isBlank() ? added.getFirst() : selected);
    }

    /** Reveals an existing stack member without replacing the alternatives or resetting an unchanged choice. */
    public void selectFromStack(int slot, String board) {
        if (!validSlot(slot) || settings.getMedium() == MapSettings.MEDIUM_SPACE) { return; }
        List<String> boards = settings.getBoardStack(slot);
        String selected = MapSettings.stackBoardName(board);
        if (!boards.contains(selected)
              || selected.equals(MapSettings.stackBoardName(settings.getBoardsSelectedVector().get(slot)))) { return; }
        updateStack(slot, boards, selected);
    }

    /** Reveals another candidate now. The alternatives remain available for subsequent rolls. */
    public void rollStack(int slot, Random random) {
        if (!validSlot(slot)) { return; }
        List<String> boards = settings.getBoardStack(slot);
        if (boards.isEmpty()) { return; }
        String selected = settings.getBoardsSelectedVector().get(slot);
        List<String> others = boards.stream().filter(board -> !board.equals(MapSettings.stackBoardName(selected))).toList();
        List<String> choices = others.isEmpty() ? boards : others;
        updateStack(slot, boards, choices.get(random.nextInt(choices.size())));
    }

    /** Hides the choice until launch; turning surprise off reveals a random member of the same stack. */
    public void toggleSurprise(int slot, Random random) {
        if (!validSlot(slot) || settings.getBoardStack(slot).isEmpty()) { return; }
        String selected = settings.getBoardsSelectedVector().get(slot);
        if (selected.startsWith(MapSettings.BOARD_SURPRISE)) { rollStack(slot, random); }
        else { updateStack(slot, settings.getBoardStack(slot), MapSettings.BOARD_SURPRISE); }
    }

    /** Removing the displayed member rolls a remaining one; the last removal restores the generated placeholder. */
    public void removeFromStack(int slot, String board, Random random) {
        if (!validSlot(slot)) { return; }
        List<String> boards = new ArrayList<>(settings.getBoardStack(slot));
        if (!boards.remove(MapSettings.stackBoardName(board))) { return; }
        String selected = settings.getBoardsSelectedVector().get(slot);
        if (boards.isEmpty()) { assign(slot, MapSettings.BOARD_GENERATED); generate(); }
        else { updateStack(slot, boards, selected.startsWith(MapSettings.BOARD_SURPRISE) ? selected
              : boards.contains(MapSettings.stackBoardName(selected)) ? selected : boards.get(random.nextInt(boards.size()))); }
    }

    private void updateStack(int slot, List<String> boards, String selected) {
        if (boards.size() == 1 && selected.startsWith(MapSettings.BOARD_SURPRISE)) {
            selected = boards.getFirst();
        }
        boolean surprise = selected.startsWith(MapSettings.BOARD_SURPRISE);
        boolean generate = MapSettings.BOARD_GENERATED.equals(selected)
              && !selected.equals(settings.getBoardsSelectedVector().get(slot));
        settings.setBoardStack(slot, surprise ? List.of() : boards);
        commands.changeBoard(settings, slot, surprise ? MapSettings.BOARD_SURPRISE + String.join("\n", boards) : selected);
        sent();
        if (generate) { generate(); }
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
            sent();
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
            sent();
        }
    }

    public void levelOffset(int slot, int offset) {
        if (validSlot(slot) && (offset >= MapSettings.MIN_BOARD_LEVEL_OFFSET)
              && (offset <= MapSettings.MAX_BOARD_LEVEL_OFFSET) && (offset != settings.getBoardLevelOffset(slot))) {
            commands.levelOffset(settings, slot, offset);
            sent();
        }
    }

    /**
     * Makes the map the single board {@code board}, at the board's own size, as the Swing advanced search did.
     *
     * @param board a board name as the map settings use it
     * @return whether the board's size could be read and it was selected
     */
    public boolean singleBoard(String board) {
        LibraryBoard known = (library == null) ? null : library.get(key(board));
        BoardDimensions size = (known != null) ? new BoardDimensions(known.width(), known.height())
              : Board.getSize(new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(board)).getFile());
        if (size == null) {
            return false;
        }
        customSize = false;
        commands.selectSingleBoard(settings, board.replace("\\", "/"), size);
        sent();
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
        sent();
    }

    // endregion
}
