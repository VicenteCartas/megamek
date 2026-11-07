/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardFile;
import megamek.common.loaders.MapSettings;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;
import megamek.server.ServerBoardHelper;

/**
 * The lobby's 3D board preview, which follows the lobby's board while it is open, and the lobby's take on a board the
 * board editor saved.
 */
public final class LobbyBoardPreviews {

    private static final MMLogger LOGGER = MMLogger.create(LobbyBoardPreviews.class);
    /** The board each client's 3D preview got from the lobby. */
    private static final Map<ClientGUI, Board> PREVIEWED = new HashMap<>();

    private LobbyBoardPreviews() {
    }

    /** EDT: the lobby's 3D preview: the battlefield the server built, else a new roll of the map settings. */
    public static void preview3D(ClientGUI clientGUI) {
        Board board = lobbyBoard(clientGUI);
        PREVIEWED.put(clientGUI, board);
        GpuBoardWindow.openPreview(clientGUI.getFrame(), board, clientGUI.getClient().getGame().getPlanetaryConditions());
    }

    /** EDT: a new server board or changed map settings; the open 3D preview shows the new board and conditions. */
    public static void refreshPreview(ClientGUI clientGUI) {
        GpuBoardWindow.updatePreviewConditions(clientGUI.getFrame(), clientGUI.getClient().getGame().getPlanetaryConditions());
        Board shown = PREVIEWED.get(clientGUI);
        if (shown == null) {
            return;
        }
        if (!GpuBoardWindow.showsPreview(clientGUI.getFrame(), shown)) {
            PREVIEWED.remove(clientGUI);
            return;
        }
        Board board = lobbyBoard(clientGUI);
        PREVIEWED.put(clientGUI, board);
        if (board != shown) {
            GpuBoardWindow.updatePreview(clientGUI.getFrame(), board);
        }
    }

    /** EDT: the lobby ends; its 3D preview closes. */
    public static void closePreviews(ClientGUI clientGUI) {
        PREVIEWED.remove(clientGUI);
        GpuBoardWindow.closePreview(clientGUI.getFrame());
    }

    /**
     * EDT: the board editor opened from the lobby saved a board; while still in the lobby, the map settings take the
     * saved board.
     */
    public static void boardEditorClosed(ClientGUI clientGUI, Path savedFile, boolean savedAssembledPreview) {
        if ((savedFile == null) || !clientGUI.getClient().getGame().getPhase().isLounge()) {
            return;
        }
        try {
            MapSettings settings = settingsForSavedBoard(
                  MapSettings.getInstance(clientGUI.getClient().getMapSettings()), savedFile, savedAssembledPreview);
            clientGUI.getClient().sendMapSettings(settings);
        } catch (IOException ex) {
            LOGGER.error(ex, "Cannot read the saved board {}", savedFile);
        }
    }

    /** The server-built battlefield, or else a roll of the map settings. */
    private static Board lobbyBoard(ClientGUI clientGUI) {
        Board board = clientGUI.getClient().getGame().getBoard();
        if ((board == null) || (board.getWidth() <= 0) || (board.getHeight() <= 0)) {
            board = ServerBoardHelper.getPossibleGameBoard(clientGUI.getClient().getMapSettings(), false);
        }
        return board;
    }

    /** Only a saved source sheet keeps its placement; an assembled preview already includes its adjustments. */
    static MapSettings settingsForSavedBoard(MapSettings settings, Path savedFile, boolean savedAssembledPreview)
          throws IOException {
        Path saved = savedFile.toRealPath();
        for (File directory : List.of(Configuration.boardsDir(),
              new File(Configuration.userDataDir(), Configuration.boardsDir().toString()))) {
            if (!directory.isDirectory()) {
                continue;
            }
            Path root = directory.toPath().toRealPath();
            if (!saved.startsWith(root)) {
                continue;
            }
            String name = "/" + BoardFile.selectionName(root.relativize(saved).toString()).replace('\\', '/');
            Path resolved = new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(name)).getFile().toPath();
            if (!resolved.toRealPath().equals(saved)) {
                continue;
            }
            BoardDimensions size = BoardFile.metadata(saved).size();
            boolean selected = settings.getBoardsSelectedVector().stream().filter(Objects::nonNull)
                  .map(value -> value.startsWith(Board.BOARD_REQUEST_ROTATION)
                        ? value.substring(Board.BOARD_REQUEST_ROTATION.length()) : value)
                  .anyMatch(value -> value.equals(name) || ("/" + value).equals(name));
            if (savedAssembledPreview || !selected || !settings.getBoardSize().equals(size)) {
                settings.setMapSize(1, 1);
                settings.setBoardSize(size.width(), size.height());
                settings.setBoardsSelectedVector(List.of(name));
            }
            return settings;
        }
        return settings;
    }
}
