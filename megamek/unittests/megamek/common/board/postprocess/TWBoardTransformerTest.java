/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.common.board.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import megamek.server.ServerBoardHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Characterization tests for {@link TWBoardTransformer}. These pin the behavior of the board assembly pipeline
 * (loading fixed boards, rotation requests, sheet combination, surprise board resolution) so that consolidating
 * the duplicate board-build code paths in the server does not change behavior.
 */
class TWBoardTransformerTest {

    /**
     * A 2x2 board whose four hexes carry distinct levels so that position, combination and rotation can be
     * asserted unambiguously. Levels by (x, y): (0,0)=0, (1,0)=1, (0,1)=2, (1,1)=3.
     */
    private static final String FIRST_BOARD_CONTENT = """
          size 2 2
          hex 0101 0 "" ""
          hex 0201 1 "" ""
          hex 0102 2 "" ""
          hex 0202 3 "" ""
          end""";

    /** A second 2x2 board with levels 4-7, distinguishable from the first when sheets are combined. */
    private static final String SECOND_BOARD_CONTENT = """
          size 2 2
          hex 0101 4 "" ""
          hex 0201 5 "" ""
          hex 0102 6 "" ""
          hex 0202 7 "" ""
          end""";

    private static final String FIRST_BOARD_NAME = "transformerTestBoardOne";
    private static final String SECOND_BOARD_NAME = "transformerTestBoardTwo";
    private static final String THEMED_BOARD_NAME = "transformerTestThemedBoard";
    private static final String THEMED_BOARD_CONTENT = """
          size 2 2
          hex 0101 0 "" "grass"
          hex 0201 1 "" "grass"
          hex 0102 2 "rough:1" "grass"
          hex 0202 3 "" "desert"
          end""";

    @TempDir
    private static Path temporaryBoardsDirectory;

    private static File originalBoardsDirectory;

    @BeforeAll
    static void createBoardFilesAndRedirectBoardsDirectory() throws IOException {
        Files.writeString(temporaryBoardsDirectory.resolve(FIRST_BOARD_NAME + ".board"), FIRST_BOARD_CONTENT);
        Files.writeString(temporaryBoardsDirectory.resolve(SECOND_BOARD_NAME + ".board"), SECOND_BOARD_CONTENT);
        Files.writeString(temporaryBoardsDirectory.resolve(THEMED_BOARD_NAME + ".board"), THEMED_BOARD_CONTENT);
        originalBoardsDirectory = Configuration.boardsDir();
        Configuration.setBoardsDir(temporaryBoardsDirectory.toFile());
    }

    @AfterAll
    static void restoreBoardsDirectory() {
        Configuration.setBoardsDir(originalBoardsDirectory);
    }

    private static MapSettings createMapSettingsFor(List<String> selectedBoards) {
        MapSettings mapSettings = MapSettings.getInstance();
        mapSettings.setBoardSize(2, 2);
        mapSettings.setMapSize(selectedBoards.size(), 1);
        mapSettings.setBoardsSelectedVector(selectedBoards);
        return mapSettings;
    }

    @Test
    void loadsFixedBoardFromMapSettings() {
        MapSettings mapSettings = createMapSettingsFor(List.of(FIRST_BOARD_NAME));

        Board board = TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        assertEquals(2, board.getWidth());
        assertEquals(2, board.getHeight());
        assertEquals(0, board.getHex(0, 0).getLevel());
        assertEquals(1, board.getHex(1, 0).getLevel());
        assertEquals(2, board.getHex(0, 1).getLevel());
        assertEquals(3, board.getHex(1, 1).getLevel());
    }

    @Test
    void appliesRequestedRotationForEvenWidthBoards() {
        MapSettings mapSettings = createMapSettingsFor(List.of(Board.BOARD_REQUEST_ROTATION + FIRST_BOARD_NAME));

        Board board = TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        // A rotation request flips the board 180 degrees: each hex swaps with its diagonal opposite
        assertEquals(3, board.getHex(0, 0).getLevel());
        assertEquals(2, board.getHex(1, 0).getLevel());
        assertEquals(1, board.getHex(0, 1).getLevel());
        assertEquals(0, board.getHex(1, 1).getLevel());
    }

    @Test
    void combinesMultipleBoardSheetsLeftToRight() {
        MapSettings mapSettings = createMapSettingsFor(List.of(FIRST_BOARD_NAME, SECOND_BOARD_NAME));

        Board board = TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        assertEquals(4, board.getWidth());
        assertEquals(2, board.getHeight());
        // Left sheet is the first selected board, right sheet the second
        assertEquals(0, board.getHex(0, 0).getLevel());
        assertEquals(1, board.getHex(1, 0).getLevel());
        assertEquals(4, board.getHex(2, 0).getLevel());
        assertEquals(5, board.getHex(3, 0).getLevel());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void gameAndPreviewCloseOuterExitsAndKeepSheetSeams(boolean vertical) throws IOException {
        Board first = Board.createEmptyBoard(2, 2), second = Board.createEmptyBoard(2, 2);
        Coords firstAt = vertical ? new Coords(0, 1) : new Coords(1, 0);
        Coords secondAt = vertical ? new Coords(0, 2) : new Coords(2, 0);
        int firstExits = vertical ? 40 : 3; // S + NW, or N + NE.
        first.setHex(firstAt, new Hex(0, "building:1:" + firstExits + ";bldg_cf:15;bldg_elev:2", ""));
        second.setHex(0, 0, new Hex(0, "building:1:17;bldg_cf:15;bldg_elev:2", "")); // N + SW.
        first.setNativeFormat(true);
        second.setNativeFormat(true);
        Path firstFile = temporaryBoardsDirectory.resolve("edge-first.board2");
        Path secondFile = temporaryBoardsDirectory.resolve("edge-second.board2");
        BoardFile.save(first, firstFile);
        BoardFile.save(second, secondFile);
        String firstText = Files.readString(firstFile), secondText = Files.readString(secondFile);
        MapSettings settings = createMapSettingsFor(List.of("edge-first.board2", "edge-second.board2"));
        settings.setMapSize(vertical ? 1 : 2, vertical ? 2 : 1);
        settings.setBoardsSelectedVector(List.of("edge-first.board2", "edge-second.board2"));

        Board game = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());
        Board preview = ServerBoardHelper.getPossibleGameBoard(MapSettings.getInstance(settings), false);
        for (Board board : List.of(game, preview)) {
            assertEquals(vertical ? 8 : 2, board.getHex(firstAt).getTerrain(Terrains.BUILDING).getExits());
            assertEquals(vertical ? 1 : 16, board.getHex(secondAt).getTerrain(Terrains.BUILDING).getExits());
            assertSame(board.getBuildingAt(firstAt), board.getBuildingAt(secondAt), "The seam stays one building");
        }
        assertEquals(firstText, Files.readString(firstFile));
        assertEquals(secondText, Files.readString(secondFile));
    }

    @Test
    void nestedAssemblyKeepsConnectionsUntilTheFinalBoundaryIsKnown() {
        Board sheet = Board.createEmptyBoard(2, 2);
        sheet.setHex(1, 0, new Hex(0, "building:1:3;bldg_cf:15;bldg_elev:2", ""));
        Board intermediate = BoardUtilities.combine(2, 2, 2, 1,
              new Board[] { Board.createEmptyBoard(2, 2), sheet }, MapSettings.MEDIUM_GROUND);
        assertEquals(3, intermediate.getHex(3, 0).getTerrain(Terrains.BUILDING).getExits());
        Board next = Board.createEmptyBoard(4, 2);
        next.setHex(0, 0, new Hex(0, "building:1:17;bldg_cf:15;bldg_elev:2", ""));
        next.setHex(3, 1, new Hex(0, "building:1:1;bldg_cf:15;bldg_elev:2", ""));
        Board finished = BoardUtilities.combine(4, 2, 2, 1, new Board[] { intermediate, next }, MapSettings.MEDIUM_GROUND);
        finished.closeOffBoardBuildingExits();

        assertEquals(2, finished.getHex(3, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(16, finished.getHex(4, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(1, finished.getHex(7, 1).getTerrain(Terrains.BUILDING).getExits(), "An authored in-map opening stays");
        assertEquals(3, intermediate.getHex(3, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(3, sheet.getHex(1, 0).getTerrain(Terrains.BUILDING).getExits());
    }

    @Test
    void generatedBoardMatchesRequestedDimensions() {
        MapSettings mapSettings = MapSettings.getInstance();
        mapSettings.setBoardSize(16, 17);
        mapSettings.setMapSize(1, 1);
        mapSettings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
        mapSettings.setBoardTheme(0, "volcano");

        Board board = TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        assertEquals(16, board.getWidth());
        assertEquals(17, board.getHeight());
        assertEquals("volcano", board.getHex(0, 0).getTheme());
    }

    @Test
    void perPanelOverridesAgreeInPreviewAndMatchAssemblyAndKeepRotation() throws IOException {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(2, 2);
        settings.setMapSize(2, 2);
        settings.setBoardsSelectedVector(List.of(THEMED_BOARD_NAME, THEMED_BOARD_NAME, THEMED_BOARD_NAME,
              Board.BOARD_REQUEST_ROTATION + THEMED_BOARD_NAME));
        settings.setBoardTheme(0, "volcano");
        settings.setBoardTheme(3, "snow");
        settings.setBoardLevelOffset(0, 5);
        settings.setBoardLevelOffset(2, -4);
        settings.setBoardLevelOffset(3, 2);

        Board game = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());
        Board preview = ServerBoardHelper.getPossibleGameBoard(MapSettings.getInstance(settings), false);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(game.getHex(x, y).getTheme(), preview.getHex(x, y).getTheme());
                assertEquals(game.getHex(x, y).getLevel(), preview.getHex(x, y).getLevel());
                if ((x < 2) && (y < 2)) {
                    assertEquals("volcano", game.getHex(x, y).getTheme());
                    assertEquals(5 + x + 2 * y, game.getHex(x, y).getLevel());
                } else if ((x >= 2) && (y >= 2)) {
                    assertEquals("snow", game.getHex(x, y).getTheme());
                    assertEquals(2 + (3 - x) + 2 * (3 - y), game.getHex(x, y).getLevel());
                } else if (x < 2) {
                    assertEquals(-4 + x + 2 * (y - 2), game.getHex(x, y).getLevel());
                } else {
                    assertEquals(x - 2 + 2 * y, game.getHex(x, y).getLevel());
                }
            }
        }
        assertEquals("grass", game.getHex(2, 0).getTheme());
        assertEquals("desert", game.getHex(3, 1).getTheme(), "Default keeps mixed source themes");
        assertEquals("grass", game.getHex(0, 2).getTheme());
        assertEquals(5, game.getHex(2, 2).getLevel(), "The shifted sheet remains rotated");
        assertEquals(THEMED_BOARD_CONTENT,
              Files.readString(temporaryBoardsDirectory.resolve(THEMED_BOARD_NAME + ".board")));
    }

    @Test
    void defaultReloadRestoresOriginalThemesAndLevelsWithoutChangingSourceOrEarlierBoard() throws IOException {
        MapSettings settings = createMapSettingsFor(List.of(THEMED_BOARD_NAME));
        settings.setBoardTheme(0, "volcano");
        settings.setBoardLevelOffset(0, -10);
        Board overridden = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());
        settings.setBoardTheme(0, null);
        settings.setBoardLevelOffset(0, 0);
        Board restored = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());
        assertEquals("volcano", overridden.getHex(1, 1).getTheme());
        assertEquals("grass", restored.getHex(0, 0).getTheme());
        assertEquals("desert", restored.getHex(1, 1).getTheme());
        assertEquals(-10, overridden.getHex(0, 0).getLevel());
        assertEquals(-7, overridden.getHex(1, 1).getLevel());
        assertEquals(0, restored.getHex(0, 0).getLevel());
        assertEquals(3, restored.getHex(1, 1).getLevel());
        assertEquals(THEMED_BOARD_CONTENT,
              Files.readString(temporaryBoardsDirectory.resolve(THEMED_BOARD_NAME + ".board")));
    }

    @Test
    void positiveOffsetJoinsFlatSheetToLevelFiveEdge() throws IOException {
        String raised = """
              size 2 2
              hex 0101 5 "" ""
              hex 0201 5 "" ""
              hex 0102 5 "" ""
              hex 0202 5 "" ""
              end""";
        String flat = """
              size 2 2
              hex 0101 0 "" ""
              hex 0201 1 "" ""
              hex 0102 0 "" ""
              hex 0202 0 "" ""
              end""";
        Path raisedFile = temporaryBoardsDirectory.resolve("raised.board");
        Path flatFile = temporaryBoardsDirectory.resolve("flat.board");
        Files.writeString(raisedFile, raised);
        Files.writeString(flatFile, flat);
        MapSettings settings = createMapSettingsFor(List.of("raised", "flat"));
        settings.setBoardLevelOffset(1, 5);

        Board board = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());

        assertEquals(5, board.getHex(1, 0).getLevel());
        assertEquals(5, board.getHex(2, 0).getLevel());
        assertEquals(5, board.getHex(1, 1).getLevel());
        assertEquals(5, board.getHex(2, 1).getLevel());
        assertEquals(6, board.getHex(3, 0).getLevel(), "The original level-one hill shifts by the same amount");
        assertEquals(raised, Files.readString(raisedFile));
        assertEquals(flat, Files.readString(flatFile));
    }

    @Test
    void instantiateBoardDoesNotMutateSourceMapSettings() {
        String surpriseSelection = MapSettings.BOARD_SURPRISE + FIRST_BOARD_NAME + "\n" + SECOND_BOARD_NAME;
        MapSettings mapSettings = createMapSettingsFor(List.of(surpriseSelection));

        TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        // The copy-first contract: resolving the surprise pick must not leak into the caller's settings
        assertEquals(surpriseSelection, mapSettings.getBoardsSelectedVector().get(0));
    }

    @Test
    void instantiateBoardResolvingSettingsRecordsSurprisePickInSettings() {
        String surpriseSelection = MapSettings.BOARD_SURPRISE + FIRST_BOARD_NAME + "\n" + SECOND_BOARD_NAME;
        MapSettings mapSettings = createMapSettingsFor(List.of(surpriseSelection));

        TWBoardTransformer.instantiateBoardResolvingSettings(mapSettings, new PlanetaryConditions(),
              new GameOptions());

        // The resolve-in-place contract: the server's settings must record which board was actually picked,
        // as the dataset logger and save games read the settings after the board is built
        String resolvedSelection = mapSettings.getBoardsSelectedVector().get(0);
        assertTrue(FIRST_BOARD_NAME.equals(resolvedSelection) || SECOND_BOARD_NAME.equals(resolvedSelection),
              "Surprise selection must be resolved to a concrete board name, but was " + resolvedSelection);
    }

    @Test
    void surprisePickResolvesToOneOfTheOfferedBoards() {
        String surpriseSelection = MapSettings.BOARD_SURPRISE + FIRST_BOARD_NAME + "\n" + SECOND_BOARD_NAME;
        MapSettings mapSettings = createMapSettingsFor(List.of(surpriseSelection));

        Board board = TWBoardTransformer.instantiateBoard(mapSettings, new PlanetaryConditions(), new GameOptions());

        // Whichever board was picked, hex (0,0) carries that board's marker level
        int cornerLevel = board.getHex(0, 0).getLevel();
        assertTrue((cornerLevel == 0) || (cornerLevel == 4),
              "Surprise pick must load one of the two offered boards, but corner level was " + cornerLevel);
    }
}
