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

package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LobbyBoardHandler}: building the game board in the lounge on request, refusing requests that
 * must not be honored (wrong phase, surprise boards), and discarding a built board when a change makes it stale.
 */
class LobbyBoardHandlerTest {

    private static final String BOARD_CONTENT = """
          size 2 2
          hex 0101 0 "" ""
          hex 0201 1 "" ""
          hex 0102 2 "" ""
          hex 0202 3 "" ""
          end""";

    private static final String BOARD_NAME = "lobbyHandlerTestBoard";

    @TempDir
    private static Path temporaryBoardsDirectory;

    private static File originalBoardsDirectory;

    private Game game;
    private LobbyBoardHandler lobbyBoardHandler;

    @BeforeAll
    static void createBoardFileAndRedirectBoardsDirectory() throws IOException {
        Files.writeString(temporaryBoardsDirectory.resolve(BOARD_NAME + ".board"), BOARD_CONTENT);
        originalBoardsDirectory = Configuration.boardsDir();
        Configuration.setBoardsDir(temporaryBoardsDirectory.toFile());
    }

    @AfterAll
    static void restoreBoardsDirectory() {
        Configuration.setBoardsDir(originalBoardsDirectory);
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setPhase(GamePhase.LOUNGE);
        game.setMapSettings(createMapSettingsFor(List.of(BOARD_NAME)));
        lobbyBoardHandler = new LobbyBoardHandler(spyWithoutNetwork());
    }

    private static MapSettings createMapSettingsFor(List<String> selectedBoards) {
        MapSettings mapSettings = MapSettings.getInstance();
        mapSettings.setBoardSize(2, 2);
        mapSettings.setMapSize(selectedBoards.size(), 1);
        mapSettings.setBoardsSelectedVector(selectedBoards);
        return mapSettings;
    }

    @Test
    void generationRequestInLoungeBuildsAndStoresTheBoard() {
        lobbyBoardHandler.handleGenerationRequest(0);

        assertTrue(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(2, game.getBoard().getWidth());
        assertEquals(2, game.getBoard().getHeight());
        assertEquals(3, game.getBoard().getHex(1, 1).getLevel());
    }

    @Test
    void generationRequestIsIgnoredOutsideTheLounge() {
        game.setPhase(GamePhase.MOVEMENT);

        lobbyBoardHandler.handleGenerationRequest(0);

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
    }

    @Test
    void generationRequestIsRefusedWhileASurpriseBoardIsSelected() {
        String surpriseSelection = MapSettings.BOARD_SURPRISE + BOARD_NAME + "\n" + BOARD_NAME;
        game.setMapSettings(createMapSettingsFor(List.of(surpriseSelection)));

        lobbyBoardHandler.handleGenerationRequest(0);

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(0, game.getBoard().getWidth());
    }

    @Test
    void generationRequestIsRefusedWhileBoardSlotsAreUnset() {
        // Map size changes fill new board slots with null until they are replaced (MapSettings.setMapSize)
        MapSettings mapSettings = createMapSettingsFor(List.of(BOARD_NAME));
        mapSettings.setMapSize(2, 1);
        game.setMapSettings(mapSettings);

        lobbyBoardHandler.handleGenerationRequest(0);

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(0, game.getBoard().getWidth());
    }

    @Test
    void surpriseCheckToleratesUnsetBoardSlots() {
        MapSettings mapSettings = createMapSettingsFor(
              Arrays.asList(null, MapSettings.BOARD_SURPRISE + BOARD_NAME));
        game.setMapSettings(mapSettings);

        // Must refuse (surprise present) without throwing on the null slot
        lobbyBoardHandler.handleGenerationRequest(0);

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
    }

    @Test
    void generationRequestSurvivesABoardThatCannotBeAssembled() {
        // A selected board whose file is missing loads as an empty board, which BoardUtilities.combine()
        // rejects as the wrong size. The failure must not escape into the packet handler.
        game.setMapSettings(createMapSettingsFor(List.of("thisBoardFileDoesNotExist")));

        assertDoesNotThrow(() -> lobbyBoardHandler.handleGenerationRequest(0));

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(0, game.getBoard().getWidth());
    }

    @Test
    void aFailedGenerationKeepsThePreviouslyBuiltBoard() {
        lobbyBoardHandler.handleGenerationRequest(0);
        assertTrue(lobbyBoardHandler.hasBoardFromLounge());

        game.setMapSettings(createMapSettingsFor(List.of("thisBoardFileDoesNotExist")));
        assertDoesNotThrow(() -> lobbyBoardHandler.handleGenerationRequest(0));

        // The board everyone is already previewing must not be torn down by a failed re-roll
        assertTrue(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(2, game.getBoard().getWidth());
    }

    @Test
    void invalidateDiscardsTheBuiltBoard() {
        lobbyBoardHandler.handleGenerationRequest(0);
        assertTrue(lobbyBoardHandler.hasBoardFromLounge());

        lobbyBoardHandler.invalidate("map settings changed");

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
        assertEquals(0, game.getBoard().getWidth());
    }

    @Test
    void stackEditsPreserveTheBuiltBattlefieldButChangingTheSlotStillInvalidatesIt() {
        game.setMapSettings(createMapSettingsFor(List.of("/" + BOARD_NAME)));
        TWGameManager manager = spyWithoutNetwork();
        manager.handlePacket(0, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        Board built = game.getBoard();
        assertEquals(2, built.getWidth());
        MapSettings changed = MapSettings.getInstance(game.getMapSettings());
        changed.setBoardStack(0, List.of("/" + BOARD_NAME, MapSettings.BOARD_GENERATED));
        manager.handlePacket(0, new Packet(PacketCommand.SENDING_MAP_SETTINGS, changed));
        assertSame(built, game.getBoard(), "adding an alternative must not discard the existing map");
        assertEquals(changed.getBoardStack(0), game.getMapSettings().getBoardStack(0));
        changed = MapSettings.getInstance(game.getMapSettings());
        changed.setBoardLevelOffset(0, 1);
        manager.handlePacket(0, new Packet(PacketCommand.SENDING_MAP_SETTINGS, changed));
        assertEquals(0, game.getBoard().getWidth(), "an actual map edit still requires rebuilding");
    }

    @Test
    void editorSaveReloadPacketRescansFilesInvalidatesBattlefieldAndLoadsSavedContents() throws IOException {
        Path existingFile = temporaryBoardsDirectory.resolve("editor-existing.board2");
        Path saveAsFile = temporaryBoardsDirectory.resolve("editor-save-as.board2");
        Board edited = Board.createEmptyBoard(2, 2);
        edited.getHex(0, 0).setLevel(1);
        BoardFile.save(edited, existingFile);
        game.setMapSettings(createMapSettingsFor(List.of("/editor-existing.board2")));
        TWGameManager manager = spyWithoutNetwork();
        manager.handlePacket(0, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        assertEquals(1, game.getBoard().getHex(0, 0).getLevel());

        edited.getHex(0, 0).setLevel(4);
        BoardFile.save(edited, existingFile);
        edited.getHex(0, 0).setLevel(7);
        BoardFile.save(edited, saveAsFile);
        assertEquals(1, game.getBoard().getHex(0, 0).getLevel(), "Saving alone leaves the cached battlefield intact");

        // The editor's close callback uses this normal client command, including when the selection is unchanged.
        manager.handlePacket(0, new Packet(PacketCommand.SENDING_MAP_SETTINGS,
              MapSettings.getInstance(game.getMapSettings())));
        assertEquals(0, game.getBoard().getWidth(), "The server must discard its pre-save battlefield");
        assertTrue(game.getMapSettings().getBoardsAvailableVector().contains("/editor-save-as.board2"));
        assertEquals(List.of("/editor-existing.board2"), game.getMapSettings().getBoardsSelectedVector());
        manager.handlePacket(0, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        assertEquals(4, game.getBoard().getHex(0, 0).getLevel(), "Rebuilding reloads the overwritten file");

        MapSettings savedSelection = MapSettings.getInstance(game.getMapSettings());
        savedSelection.setBoardsSelectedVector(List.of("/editor-save-as.board2"));
        manager.handlePacket(0, new Packet(PacketCommand.SENDING_MAP_SETTINGS, savedSelection));
        assertEquals(0, game.getBoard().getWidth());
        assertEquals(List.of("/editor-save-as.board2"), game.getMapSettings().getBoardsSelectedVector());
        manager.handlePacket(0, new Packet(PacketCommand.LOBBY_GENERATE_BOARD));
        assertEquals(7, game.getBoard().getHex(0, 0).getLevel(), "Save As loads the newly selected file");
    }

    @Test
    void invalidateWithoutABuiltBoardDoesNothing() {
        lobbyBoardHandler.invalidate("map settings changed");

        assertFalse(lobbyBoardHandler.hasBoardFromLounge());
    }

    @Test
    void invalidateOutsideTheLoungeLeavesTheGameBoardAlone() {
        lobbyBoardHandler.handleGenerationRequest(0);
        game.setPhase(GamePhase.MOVEMENT);

        lobbyBoardHandler.invalidate("planetary conditions changed");

        // The live game board must never be clobbered after the lounge
        assertEquals(2, game.getBoard().getWidth());
    }

    @Test
    void restoreFromGameKeepsALoungeBuiltBoardAfterALoadedSave() {
        lobbyBoardHandler.handleGenerationRequest(0);

        LobbyBoardHandler reloadedHandler = new LobbyBoardHandler(spyWithoutNetwork());
        reloadedHandler.restoreFromGame(game);

        assertTrue(reloadedHandler.hasBoardFromLounge());
    }

    @Test
    void restoreFromGameIgnoresTheBoardOfARunningGame() {
        lobbyBoardHandler.handleGenerationRequest(0);
        game.setPhase(GamePhase.MOVEMENT);

        LobbyBoardHandler reloadedHandler = new LobbyBoardHandler(spyWithoutNetwork());
        reloadedHandler.restoreFromGame(game);

        assertFalse(reloadedHandler.hasBoardFromLounge());
    }

    /** @return a game manager attached to the test game whose network layer is detached (no live server exists) */
    private TWGameManager spyWithoutNetwork() {
        TWGameManager gameManager = spy(new TWGameManager());
        doNothing().when(gameManager).send(any());
        doNothing().when(gameManager).send(anyInt(), any());
        doNothing().when(gameManager).sendServerChat(anyString());
        doNothing().when(gameManager).sendServerChat(anyInt(), anyString());
        gameManager.setGame(game);
        return gameManager;
    }
}
