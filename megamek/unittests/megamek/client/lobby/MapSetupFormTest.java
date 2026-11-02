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
            String value = board.contains("\n") ? MapSettings.BOARD_SURPRISE + board : board;
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
        form.assignSurprise(1, List.of("16x17/Desert 1.board2", "16x17/Lake Shore.board2"));
        Slot surprise = form.picture().slots().get(1);
        assertEquals(Kind.SURPRISE, surprise.kind());
        assertEquals(2, surprise.surprise().size());
        assertEquals("16x17/Desert 1.board2", surprise.file());
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

        form.assignRotated(1, "16x17/Lake Shore.board2");
        assertEquals(Board.BOARD_REQUEST_ROTATION + "16x17/Lake Shore.board2",
              form.settings().getBoardsSelectedVector().get(1));
        form.boardSize(FIFTEEN);
        assertFalse(form.canRotate());
        int before = calls.size();
        form.assignRotated(0, "16x17/Lake Shore.board2");
        assertEquals(before, calls.size());
    }

    @Test
    void randomPicksOneOfTheChosenBoards() {
        form.assignRandom(0, List.of("a.board2", "b.board2"), new Random(1));
        String picked = form.settings().getBoardsSelectedVector().get(0);
        assertTrue(picked.equals("a.board2") || picked.equals("b.board2"));
        int before = calls.size();
        form.assignRandom(0, List.of("a.board2"), new Random(1));
        assertEquals(before, calls.size(), "needs two boards");
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
    void reloadIfNewKeepsAChangeUntilTheServerSendsOthers() {
        form.mapWidth(3);
        assertFalse(form.reloadIfNew());
        assertEquals(3, form.settings().getMapWidth());
        server = MapSettings.getInstance(server);
        assertTrue(form.reloadIfNew());
        assertEquals(2, form.settings().getMapWidth());
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
