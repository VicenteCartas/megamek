/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.client.Client;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardFile;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.server.ServerBoardHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class LobbyBoardPreviewsTest {
    @TempDir
    Path temporary;
    private File previousBoards;
    private ClientGUI gui;
    private Client client;
    private Game game;
    private MapSettings mapSettings;

    @BeforeEach
    void setUp() throws Exception {
        previousBoards = Configuration.boardsDir();
        Configuration.setBoardsDir(Files.createDirectory(temporary.resolve("boards")).toFile());
        client = mock(Client.class);
        gui = mock(ClientGUI.class);
        game = new Game();
        game.setPhase(GamePhase.LOUNGE);
        mapSettings = MapSettings.getInstance();
        mapSettings.setBoardSize(2, 2);
        mapSettings.setMapSize(2, 1);
        mapSettings.setBoardsSelectedVector(List.of("/existing.board2",
              Board.BOARD_REQUEST_ROTATION + "/existing.board2"));
        mapSettings.setBoardTheme(0, "snow");
        mapSettings.setBoardLevelOffset(1, 3);
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(game);
        when(client.getMapSettings()).thenReturn(mapSettings);
    }

    @AfterEach
    void tearDown() {
        Configuration.setBoardsDir(previousBoards);
    }

    @Test
    void savedSelectedSheetKeepsAssemblyAndRequestsServerReload() throws Exception {
        Path saved = save("existing.board2", 2, 2);
        LobbyBoardPreviews.boardEditorClosed(gui, saved, false);

        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        assertNotSame(mapSettings, request.getValue());
        assertEquals(mapSettings.getBoardsSelectedVector(), request.getValue().getBoardsSelectedVector());
        assertEquals(2, request.getValue().getMapWidth());
        assertEquals("snow", request.getValue().getBoardTheme(0));
        assertEquals(3, request.getValue().getBoardLevelOffset(1));
    }

    @Test
    void savingAssembledPreviewOverItsSourceDoesNotApplyAdjustmentsTwice() throws Exception {
        Path saved = save("existing.board2", 2, 2);
        Board original = BoardFile.read(saved);
        original.getHex(0, 0).setLevel(1);
        BoardFile.save(original, saved);
        mapSettings.setMapSize(1, 1);
        mapSettings.setBoardsSelectedVector(List.of(Board.BOARD_REQUEST_ROTATION + "/existing.board2"));
        mapSettings.setBoardTheme(0, "snow");
        mapSettings.setBoardLevelOffset(0, 3);
        Board preview = ServerBoardHelper.getPossibleGameBoard(mapSettings, false);
        assertEquals(4, preview.getHex(1, 1).getLevel());
        assertEquals("snow", preview.getHex(1, 1).getTheme());
        var session = new BoardEditorSession();
        session.open(preview, null);
        session.board().getHex(0, 0).setLevel(5);
        session.board().getHex(0, 0).setTheme("desert");
        session.save(saved);

        LobbyBoardPreviews.boardEditorClosed(gui, session.lastSavedPath(), session.lastSaveFromAssembledPreview());
        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        Board reloaded = ServerBoardHelper.getPossibleGameBoard(request.getValue(), false);
        assertEquals(5, reloaded.getHex(0, 0).getLevel(), "The edited height must not be shifted or rotated again");
        assertEquals("desert", reloaded.getHex(0, 0).getTheme(), "The saved theme must not be overridden again");
        assertEquals(4, reloaded.getHex(1, 1).getLevel(), "The saved rotation must not be applied again");
        assertEquals("snow", reloaded.getHex(1, 1).getTheme());
        assertEquals(3, preview.getHex(0, 0).getLevel(), "Editing still leaves the original preview untouched");
    }

    @Test
    void saveAsSelectsSavedDocumentSizeAndServerScanIncludesTheNewMap() throws Exception {
        save("existing.board2", 2, 2);
        Path saved = save("new map.board2", 3, 4);
        LobbyBoardPreviews.boardEditorClosed(gui, saved, false);

        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        MapSettings settings = request.getValue();
        assertEquals(List.of("/new map.board2"), settings.getBoardsSelectedVector());
        assertEquals(1, settings.getMapWidth());
        assertEquals(1, settings.getMapHeight());
        assertEquals(new BoardDimensions(3, 4), settings.getBoardSize());
        assertNull(settings.getBoardTheme(0));
        assertEquals(0, settings.getBoardLevelOffset(0));
        assertTrue(ServerBoardHelper.scanForBoards(settings).contains("/new map.board2"));
        assertEquals(2, mapSettings.getMapWidth(), "The client waits for the server's authoritative response");
    }

    @Test
    void savedFileOutsideBoardRootsDoesNotReplaceLobbySelection() throws Exception {
        Path saved = temporary.resolve("outside.board2");
        BoardFile.save(Board.createEmptyBoard(3, 4), saved);
        MapSettings settings = MapSettings.getInstance(mapSettings);
        assertSame(settings, LobbyBoardPreviews.settingsForSavedBoard(settings, saved, true));
        assertEquals(mapSettings.getBoardsSelectedVector(), settings.getBoardsSelectedVector());
        assertEquals(new BoardDimensions(2, 2), settings.getBoardSize());
    }

    @Test
    void noSaveOrNoLongerInLobbyDoesNotReload() throws Exception {
        LobbyBoardPreviews.boardEditorClosed(gui, null, false);
        game.setPhase(GamePhase.MOVEMENT);
        Path saved = save("existing.board2", 2, 2);
        LobbyBoardPreviews.boardEditorClosed(gui, saved, false);
        verify(client, never()).sendMapSettings(any());
    }

    private Path save(String filename, int width, int height) throws Exception {
        Path file = Configuration.boardsDir().toPath().resolve(filename);
        BoardFile.save(Board.createEmptyBoard(width, height), file);
        return file;
    }
}
