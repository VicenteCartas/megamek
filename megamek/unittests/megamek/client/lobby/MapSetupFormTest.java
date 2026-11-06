/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import megamek.client.lobby.MapSetupForm.Entry;
import megamek.client.lobby.MapSetupForm.Kind;
import megamek.client.lobby.MapSetupForm.Picture;
import megamek.client.lobby.MapSetupForm.Slot;
import megamek.client.lobby.MapSetupForm.Theme;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MapSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The native map setup's form against a fake lobby: sizes, slots, the board search, the odd board nag, files. */
class MapSetupFormTest {
    private static final BoardDimensions SIXTEEN = new BoardDimensions(16, 17);
    private static final BoardDimensions FIFTEEN = new BoardDimensions(15, 17);

    private final List<String> calls = new ArrayList<>();
    private MapSettings server;
    private MapSetupForm form;

    @TempDir
    File folder;

    /** Does to the settings what the lobby's commands do, without a client. */
    private final MapSetupForm.Commands commands = new MapSetupForm.Commands() {
        @Override
        public void medium(MapSettings settings, int medium) {
            calls.add("medium " + medium);
            settings.setMedium(medium);
        }

        @Override
        public void resizeMap(MapSettings settings, int width, int height) {
            calls.add("map " + width + "x" + height);
            settings.setMapSize(width, height);
        }

        @Override
        public void resizeBoards(MapSettings settings, int width, int height) {
            calls.add("boards " + width + "x" + height);
            settings.setBoardSize(width, height);
        }

        @Override
        public void changeBoard(MapSettings settings, int slot, String board) {
            String value = board.contains("\n") && !board.startsWith(MapSettings.BOARD_SURPRISE)
                  ? MapSettings.BOARD_SURPRISE + board : board;
            calls.add("board " + slot + " " + value);
            settings.getBoardsSelectedVector().set(slot, value);
        }

        @Override
        public void selectSingleBoard(MapSettings settings, String board, BoardDimensions size) {
            calls.add("single " + board);
        }

        @Override
        public void theme(MapSettings settings, int slot, String theme) {
            calls.add("theme " + slot + " " + theme);
            settings.setBoardTheme(slot, theme);
        }

        @Override
        public void levelOffset(MapSettings settings, int slot, int offset) {
            calls.add("offset " + slot + " " + offset);
            settings.setBoardLevelOffset(slot, offset);
        }

        @Override
        public void applySetup(MapSettings settings, MapSetup setup) {
            calls.add("setup " + setup.getMapWidth() + "x" + setup.getMapHeight() + " " + setup.getBoards());
            setup.applyTo(settings);
        }

        @Override
        public void generate() {
            calls.add("generate");
        }
    };

    @BeforeEach
    void setUp() {
        server = MapSettings.getInstance();
        server.setBoardSize(16, 17);
        server.setMapSize(2, 1);
        server.setBoardsSelectedVector(List.of("16x17/Desert 1.board2", MapSettings.BOARD_GENERATED));
        server.setBoardsAvailableVector(List.of("16x17/Desert 1.board2", "16x17/City Hills.board2",
              "16x17/Lake Shore.board2"));
        form = form();
    }

    private MapSetupForm form() {
        Map<String, String> tags = Map.of("16x17/Lake Shore.board2", "water||lake",
              "16x17/City Hills.board2", "urban||hills");
        return new MapSetupForm(() -> server, () -> Set.of(SIXTEEN, FIFTEEN, new BoardDimensions(16, 34)),
              commands, board -> tags.getOrDefault(board, ""), () -> List.of("snow", "", "Mars"));
    }

    @Test
    void picturesTheSlotsAndSizes() {
        Picture picture = form.picture();
        assertEquals(2, picture.mapWidth());
        assertEquals(List.of(FIFTEEN, SIXTEEN, new BoardDimensions(16, 34)), picture.sizes());
        assertEquals(1, picture.sizeIndex());
        assertFalse(picture.customSize());
        assertTrue(picture.canShrinkWidth());
        assertFalse(picture.canShrinkHeight());
        assertFalse(picture.advancedSearch());
        Slot fixed = picture.slots().get(0);
        assertEquals(Kind.FIXED, fixed.kind());
        assertEquals("Desert 1", fixed.label());
        assertTrue(fixed.canRotate());
        assertEquals(Kind.GENERATED, picture.slots().get(1).kind());
        assertTrue(picture.generable());
    }

    @Test
    void surpriseAndUnsetSlotsBlockGenerate() {
        form.assign(1, "16x17/Desert 1.board2");
        form.addToStack(1, List.of("16x17/Lake Shore.board2"));
        form.toggleSurprise(1, new Random(1));
        Slot surprise = form.picture().slots().get(1);
        assertEquals(Kind.SURPRISE, surprise.kind());
        assertEquals(2, surprise.stack().size());
        assertEquals("", surprise.file(), "a surprise does not expose a candidate thumbnail");
        assertFalse(form.picture().generable());
        form.generate();
        assertFalse(calls.contains("generate"));

        form.settings().getBoardsSelectedVector().set(1, null);
        assertEquals(Kind.EMPTY, form.picture().slots().get(1).kind());
        assertFalse(form.picture().generable());
    }

    @Test
    void searchMatchesNamesAndTagsAndAllTokens() {
        assertEquals(MapSettings.BOARD_GENERATED, form.boards().get(0).name());
        assertEquals(4, form.boards().size());
        form.search("la");
        assertEquals(4, form.boards().size(), "two letters do not filter");
        form.search("lake");
        assertEquals(List.of("16x17/Lake Shore.board2"), names(form.boards()));
        form.search("urban");
        assertEquals(List.of("16x17/City Hills.board2"), names(form.boards()));
        form.search("16x17;hills");
        assertEquals(List.of("16x17/City Hills.board2"), names(form.boards()));
        assertEquals("16x17", form.boards().get(0).folder());
    }

    @Test
    void foldersAndTagsFilterTheBoardsOfThisSize() {
        Picture picture = form.picture();
        assertEquals(List.of("16x17"), picture.folderChoices());
        assertEquals(List.of(), picture.tagChoices(), "no tags show before the library is read");
        assertEquals(MapSetupForm.Library.UNREAD, form.libraryState());

        form.filter(filter -> filter.withFolder("16x17", true));
        assertEquals(3, form.boards().size(), "a folder hides Generated and keeps its boards");
        form.filter(filter -> filter.withFolder("16x17", false).withFolder("other", true));
        assertEquals(List.of(), form.boards());
        form.filter(filter -> MapSetupForm.Filter.NONE);

        form.filter(filter -> filter.withTag("water", true));
        assertTrue(form.libraryDue(), "a tag wants the library");
        assertEquals(MapSetupForm.Library.READING, form.libraryState());
        assertEquals(List.of("16x17/Lake Shore.board2"), names(form.boards()), "the board file's tags serve meanwhile");

        form.library(List.of(new MapSetupForm.LibraryBoard("16x17/Lake Shore.board2", 16, 17, List.of("Water")),
              new MapSetupForm.LibraryBoard("16x17/City Hills.board2", 16, 17, List.of("Urban", "Hills")),
              new MapSetupForm.LibraryBoard("16x17/Desert 1.board2", 16, 17, List.of("Hills")),
              new MapSetupForm.LibraryBoard("32x34/Big City.board2", 32, 34, List.of("Urban"))));
        assertFalse(form.libraryDue());
        assertEquals(MapSetupForm.Library.READ, form.libraryState());
        form.filter(filter -> MapSetupForm.Filter.NONE.withTag("Urban", true).withTag("Hills", true));
        assertEquals(List.of("16x17/Desert 1.board2", "16x17/City Hills.board2"), names(form.boards()),
              "any of the tags will do, in the server's order");
        form.filter(filter -> filter.withAllTags(true));
        assertEquals(List.of("16x17/City Hills.board2"), names(form.boards()), "all the tags are needed");
        assertEquals(List.of("Hills", "Urban", "Water"), form.picture().tagChoices(),
              "only the tags of this size's boards are offered");
        assertEquals(1, form.picture().filterCount());
    }

    @Test
    void anySizeBrowsesTheLibraryOnlyOnAOneBoardMapAndPlacesTheBoardAtItsSize() {
        form.filter(filter -> filter.withAnySize(true).withWidth(20, null));
        Picture picture = form.picture();
        assertFalse(picture.advancedSearch());
        assertFalse(picture.anySize(), "a map of two boards stays at its size");
        assertEquals(0, picture.filterCount());
        assertEquals(MapSettings.BOARD_GENERATED, form.boards().get(0).name());

        form.mapWidth(1);
        picture = form.picture();
        assertTrue(picture.advancedSearch());
        assertTrue(picture.anySize());
        assertEquals(2, picture.filterCount());
        assertEquals(List.of(), form.boards(), "nothing lists until the library is read");
        assertTrue(form.libraryDue());

        form.library(List.of(new MapSetupForm.LibraryBoard("16x17/Desert 1.board2", 16, 17, List.of()),
              new MapSetupForm.LibraryBoard("32x34/Big City.board2", 32, 34, List.of("Urban")),
              new MapSetupForm.LibraryBoard("32x34\\Big City.board2", 32, 34, List.of()),
              new MapSetupForm.LibraryBoard("15x17/Moor.board2", 15, 17, List.of())));
        List<Entry> boards = form.boards();
        assertEquals(List.of("32x34/Big City.board2"), names(boards), "the width range keeps the wide board once");
        assertEquals(32, boards.get(0).width());
        assertEquals(List.of("15x17", "16x17", "32x34"), form.picture().folderChoices(),
              "the folders are those of the whole library");
        form.filter(filter -> filter.withWidth(null, null));
        assertEquals(3, form.boards().size(), "any size lists the whole library without Generated");
        assertEquals(new BoardDimensions(32, 34), form.sizeOf("32x34/Big City.board2"));

        assertTrue(form.singleBoard("32x34/Big City.board2"));
        assertTrue(calls.contains("single 32x34/Big City.board2"), calls.toString());
        form.filter(filter -> filter.withAnySize(false));
        assertEquals(SIXTEEN, form.sizeOf("32x34/Big City.board2"), "at this size every board has the map's");
    }

    @Test
    void topFolderIsTheFirstFolderUnderTheBoards() {
        assertEquals("16x17", MapSetupForm.topFolder("/16x17/Desert 1"));
        assertEquals("Mek", MapSetupForm.topFolder("Mek\\Arena\\Pit.board"));
        assertEquals("", MapSetupForm.topFolder("Loose.board"));
    }

    private static List<String> names(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    @Test
    void growingWideOddBoardsAsksForTheNag() {
        assertFalse(form.mapWidth(3), "even boards tile");
        assertEquals("map 3x1", calls.get(0));
        form.boardSize(FIFTEEN);
        assertTrue(form.oddBoardNagDue());
        assertTrue(form.mapWidth(4));
        assertFalse(form.mapWidth(2), "shrinking does not nag");
        assertFalse(form.mapWidth(LobbyCommands.MAX_MAP_SIZE + 1));
        form.mapHeight(0);
        assertEquals(1, form.settings().getMapHeight());
    }

    @Test
    void customBoardSizeKeepsTheSizeUntilChanged() {
        form.customSize();
        assertTrue(form.picture().customSize());
        assertEquals(-1, form.picture().sizeIndex());
        assertTrue(form.boardWidth(21));
        assertFalse(form.boardWidth(LobbyCommands.MIN_BOARD_SIZE - 1));
        form.boardHeight(30);
        assertEquals(new BoardDimensions(21, 30), form.settings().getBoardSize());
        form.boardSize(SIXTEEN);
        assertFalse(form.picture().customSize());
    }

    @Test
    void rotationNeedsAnEvenWidthAndAFixedBoard() {
        form.toggleRotation(0);
        Slot rotated = form.picture().slots().get(0);
        assertTrue(rotated.rotated());
        assertEquals("16x17/Desert 1.board2", rotated.file());
        form.toggleRotation(0);
        assertFalse(form.picture().slots().get(0).rotated());
        form.toggleRotation(1);
        assertEquals(MapSettings.BOARD_GENERATED, form.settings().getBoardsSelectedVector().get(1));

        form.assign(1, "16x17/Lake Shore.board2");
        form.toggleRotation(1);
        assertEquals(Board.BOARD_REQUEST_ROTATION + "16x17/Lake Shore.board2",
              form.settings().getBoardsSelectedVector().get(1));
        form.boardSize(FIFTEEN);
        assertFalse(form.picture().slots().get(0).canRotate());
        int before = calls.size();
        form.toggleRotation(0);
        assertEquals(before, calls.size());
    }

    @Test
    void stacksKeepTheirSelectionAndModeAndRejectDuplicates() {
        String desert = form.picture().slots().getFirst().file();
        form.toggleRotation(0);
        form.addToStack(0, List.of(desert, "16x17/City Hills.board2", "16x17/City Hills.board2"));
        assertEquals(List.of(desert, "16x17/City Hills.board2"), form.settings().getBoardStack(0));
        assertTrue(form.picture().slots().getFirst().rotated());
        int before = calls.size();
        form.addToStack(0, List.of(Board.BOARD_REQUEST_ROTATION + desert));
        assertEquals(before, calls.size(), "duplicates send no change");
        form.toggleSurprise(0, new Random(1));
        form.addToStack(0, List.of("16x17/Lake Shore.board2"));
        assertEquals(Kind.SURPRISE, form.picture().slots().getFirst().kind());
        assertEquals(3, form.settings().getBoardStack(0).size());
        form.rollStack(0, new Random(1));
        assertEquals(Kind.FIXED, form.picture().slots().getFirst().kind());
        List<String> stack = form.settings().getBoardStack(0);
        String chosen = form.picture().slots().getFirst().file();
        form.rollStack(0, new Random(1));
        assertFalse(chosen.equals(form.picture().slots().getFirst().file()), "reroll picks another candidate");
        assertEquals(stack, form.settings().getBoardStack(0));
        form.toggleSurprise(0, new Random(1));
        form.assign(0, desert);
        assertEquals(Kind.FIXED, form.picture().slots().getFirst().kind());
        assertEquals(List.of(desert), form.settings().getBoardStack(0), "Place replaces stack and surprise mode");
    }

    @Test
    void selectingAStackMemberKeepsItsAlternativesAndSlotSettings() {
        String desert = form.picture().slots().getFirst().file();
        String city = "16x17/City Hills.board2";
        form.addToStack(0, List.of(city, MapSettings.BOARD_GENERATED));
        List<String> stack = form.settings().getBoardStack(0);
        form.theme(0, "snow");
        form.levelOffset(0, 2);
        form.toggleRotation(0);
        form.selectFromStack(0, desert);
        assertTrue(form.picture().slots().getFirst().rotated(), "choosing the current map keeps its rotation");
        form.selectFromStack(0, city);
        assertEquals(city, form.picture().slots().getFirst().file());
        assertEquals(stack, form.settings().getBoardStack(0));
        assertEquals("snow", form.settings().getBoardTheme(0));
        assertEquals(2, form.settings().getBoardLevelOffset(0));
        assertEquals(Kind.GENERATED, form.picture().slots().get(1).kind(), "other slots are untouched");
        form.toggleSurprise(0, new Random(1));
        form.selectFromStack(0, city);
        assertEquals(Kind.FIXED, form.picture().slots().getFirst().kind());
        assertEquals(stack, form.settings().getBoardStack(0));
        form.selectFromStack(0, MapSettings.BOARD_GENERATED);
        assertEquals(Kind.GENERATED, form.picture().slots().getFirst().kind());
        assertEquals(stack, form.settings().getBoardStack(0));
        assertEquals("generate", calls.getLast(), "selecting Generated builds its preview");
        int sent = calls.size();
        form.selectFromStack(0, MapSettings.BOARD_GENERATED);
        form.selectFromStack(0, "not-in-stack");
        assertEquals(sent, calls.size(), "unchanged and removed choices must not send a map edit");
    }

    @Test
    void removingMembersKeepsSurpriseUntilOnlyOneMapRemains() {
        form.addToStack(0, List.of("16x17/City Hills.board2", "16x17/Lake Shore.board2"));
        form.removeFromStack(0, "16x17/Desert 1.board2", new Random(1));
        assertEquals("16x17/Lake Shore.board2", form.picture().slots().getFirst().file(),
              "removing the selected board rolls a random remaining member, rather than always taking the first");
        assertEquals(List.of("16x17/City Hills.board2", "16x17/Lake Shore.board2"), form.settings().getBoardStack(0));
        form.toggleSurprise(0, new Random(1));
        form.addToStack(0, List.of("16x17/Desert 1.board2"));
        form.removeFromStack(0, "16x17/Desert 1.board2", new Random(1));
        assertEquals(Kind.SURPRISE, form.picture().slots().getFirst().kind(), "two alternatives remain hidden");
        form.removeFromStack(0, "16x17/City Hills.board2", new Random(1));
        assertEquals(Kind.FIXED, form.picture().slots().getFirst().kind());
        assertEquals("16x17/Lake Shore.board2", form.picture().slots().getFirst().file());
        assertEquals(List.of("16x17/Lake Shore.board2"), form.settings().getBoardStack(0));
        MapSettings launched = MapSettings.getInstance(form.settings());
        launched.removeUnavailable();
        launched.chooseSurpriseBoards();
        assertEquals("16x17/Lake Shore.board2", launched.getBoardsSelectedVector().getFirst());
        form.removeFromStack(0, "16x17/Lake Shore.board2", new Random(1));
        assertEquals(Kind.GENERATED, form.picture().slots().getFirst().kind());
    }

    @Test
    void aRemainingGeneratedMapIsRevealedAndBuilt() {
        String city = "16x17/City Hills.board2";
        form.addToStack(1, List.of(city));
        form.theme(1, "snow");
        form.levelOffset(1, 2);
        form.toggleSurprise(1, new Random(1));
        calls.clear();
        form.removeFromStack(1, city, new Random(1));
        assertEquals(Kind.GENERATED, form.picture().slots().get(1).kind());
        assertEquals(List.of(MapSettings.BOARD_GENERATED), form.settings().getBoardStack(1));
        assertEquals("snow", form.settings().getBoardTheme(1));
        assertEquals(2, form.settings().getBoardLevelOffset(1));
        assertEquals(List.of("board 1 " + MapSettings.BOARD_GENERATED, "generate"), calls,
              "revealing Generated must also request its new terrain");
    }

    @Test
    void stacksSurviveSetupFilesAndOldServerEchoes() throws IOException {
        form.addToStack(0, List.of("16x17/City Hills.board2"));
        MapSettings first = MapSettings.getInstance(form.settings());
        form.addToStack(0, List.of("16x17/Lake Shore.board2"));
        server = first;
        form.reloadIfNew();
        assertEquals(3, form.settings().getBoardStack(0).size(), "an old echo cannot erase a later addition");
        var expected = form.settings().getBoardStack(0);
        File file = new File(folder, "stack.xml");
        form.save(file);
        form.assign(0, MapSettings.BOARD_GENERATED);
        form.load(file);
        assertEquals(expected, form.settings().getBoardStack(0));
        assertEquals("16x17/Desert 1.board2", form.picture().slots().getFirst().file());
    }

    @Test
    void themesStartWithTheDefaultAndKeepTheSlotsOwn() {
        form.theme(0, "lunar-custom");
        List<Theme> themes = form.themes(0);
        assertNull(themes.get(0).theme());
        List<String> names = new ArrayList<>();
        for (Theme theme : themes) {
            names.add(theme.theme());
        }
        assertTrue(names.contains("grass"));
        assertTrue(names.contains("snow"));
        assertTrue(names.contains("Mars"));
        assertTrue(names.contains("lunar-custom"));
        assertFalse(names.contains(""));
        form.theme(0, "lunar-custom");
        assertEquals(1, calls.stream().filter(call -> call.startsWith("theme")).count(), "unchanged is not sent");
    }

    @Test
    void levelOffsetStaysInBounds() {
        form.levelOffset(0, 3);
        assertEquals(3, form.picture().slots().get(0).levelOffset());
        form.levelOffset(0, MapSettings.MAX_BOARD_LEVEL_OFFSET + 1);
        assertEquals(3, form.settings().getBoardLevelOffset(0));
    }

    @Test
    void spaceHidesTheBoardList() {
        form.medium(MapSettings.MEDIUM_SPACE);
        Picture picture = form.picture();
        assertTrue(picture.space());
        assertTrue(picture.boards().isEmpty());
        assertFalse(picture.slots().get(0).adjustable());
    }

    @Test
    void reloadTakesTheServersEcho() {
        form.mapWidth(3);
        assertEquals(2, server.getMapWidth());
        form.reload();
        assertEquals(2, form.settings().getMapWidth());
    }

    @Test
    void reloadIfNewKeepsAChangeThroughAnUnchangedServerRefresh() {
        form.mapWidth(3);
        assertFalse(form.reloadIfNew());
        assertEquals(3, form.settings().getMapWidth());
        server = MapSettings.getInstance(server);
        assertTrue(form.reloadIfNew());
        assertEquals(3, form.settings().getMapWidth());
        server = MapSettings.getInstance(form.settings());
        assertTrue(form.reloadIfNew());
        assertEquals(3, form.settings().getMapWidth());
    }

    @Test
    void olderRotationEchoDoesNotUndoLaterClicksOrOtherSlotEdits() {
        form.toggleRotation(0);
        MapSettings first = MapSettings.getInstance(form.settings());
        form.toggleRotation(0);
        MapSettings second = MapSettings.getInstance(form.settings());
        form.theme(1, "snow");
        MapSettings latest = MapSettings.getInstance(form.settings());
        server = first;
        form.reloadIfNew();
        assertFalse(form.picture().slots().get(0).rotated());
        assertEquals("snow", form.settings().getBoardTheme(1));
        server = second;
        form.reloadIfNew();
        assertFalse(form.picture().slots().get(0).rotated());
        assertEquals("snow", form.settings().getBoardTheme(1));
        server = latest;
        form.reloadIfNew();
        assertTrue(MapSetupForm.sameSetup(latest, form.settings()));
        server = MapSettings.getInstance(latest);
        server.setBoardTheme(1, "desert");
        form.reloadIfNew();
        assertEquals("desert", form.settings().getBoardTheme(1), "other players' edits still apply");
    }

    @Test
    void coalescedEchoAcknowledgesAllMatchingChanges() {
        form.toggleRotation(0);
        form.toggleRotation(0);
        server = MapSettings.getInstance(form.settings());
        form.reloadIfNew();
        assertFalse(form.picture().slots().get(0).rotated());
        server = MapSettings.getInstance(server);
        server.getBoardsSelectedVector().set(0, Board.BOARD_REQUEST_ROTATION
              + server.getBoardsSelectedVector().get(0));
        form.reloadIfNew();
        assertTrue(form.picture().slots().get(0).rotated(), "a subsequent external rotation is authoritative");
    }

    @Test
    void threeQuickRotationsKeepTheirFinalStateThroughEveryEcho() {
        List<MapSettings> echoes = new ArrayList<>();
        for (int click = 0; click < 3; click++) {
            form.toggleRotation(0);
            echoes.add(MapSettings.getInstance(form.settings()));
        }
        for (MapSettings echo : echoes) {
            server = echo;
            form.reloadIfNew();
            assertTrue(form.picture().slots().get(0).rotated(), "intermediate echoes do not undo the final click");
        }
    }

    @Test
    void savesAndLoadsMapSetupFiles() throws IOException {
        File file = MapSetupForm.withExtension(new File(folder, "setup"));
        assertTrue(file.getName().endsWith(".xml"));
        assertEquals(file, MapSetupForm.withExtension(file));
        form.save(file);
        assertTrue(file.isFile());
        form.load(file);
        assertEquals("setup 2x1 [16x17/Desert 1.board2, " + MapSettings.BOARD_GENERATED + "]",
              calls.get(calls.size() - 1));
        assertThrows(FileNotFoundException.class, () -> form.load(new File(folder, "missing.xml")));
        File broken = new File(folder, "broken.xml");
        java.nio.file.Files.writeString(broken.toPath(), "not xml");
        assertThrows(IOException.class, () -> form.load(broken));
    }
}
