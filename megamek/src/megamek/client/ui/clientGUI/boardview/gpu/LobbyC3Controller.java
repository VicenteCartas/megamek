/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;
import megamek.client.lobby.C3NetworkBoard;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The Swing-thread side of the lobby's C3 network manager: it holds the {@link C3NetworkBoard}, runs the view's actions
 * on it and sends each fresh {@link C3NetworkBoard.Picture} to the GPU thread, after an action and whenever the lobby
 * reads the game again.
 */
final class LobbyC3Controller {
    private static final MMLogger LOGGER = MMLogger.create(LobbyC3Controller.class);

    private final C3NetworkBoard board;
    private final Consumer<C3NetworkBoard.Picture> show;

    /** @param show hands a picture to the GPU thread */
    LobbyC3Controller(C3NetworkBoard board, Consumer<C3NetworkBoard.Picture> show) {
        this.board = board;
        this.show = show;
    }

    /**
     * The units a unit menu command opens the manager on, or null when {@code command} ({@code COMMAND|info|ids}) is
     * not the menu's Open C3 Network Manager.
     */
    static @Nullable List<Integer> managerUnits(Game game, String command) {
        String[] parts = command.split("\\|", -1);
        if (!LobbyUnitMenu.LMP_C3_MANAGER.equals(parts[0])) {
            return null;
        }
        if (parts.length < 3 || parts[2].isBlank()) {
            return List.of();
        }
        return LobbyUtility.getEntities(game, parts[2]).stream().map(Entity::getId).sorted().toList();
    }

    /** EDT: reads the board and sends the picture. */
    void refresh() {
        C3NetworkBoard.Picture picture;
        try {
            picture = board.picture();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The C3 network manager could not read the game");
            return;
        }
        show.accept(picture);
    }

    /** EDT: runs one of the view's actions on the board, then sends the picture. */
    void act(Consumer<C3NetworkBoard> action) {
        try {
            action.accept(board);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The C3 network manager action failed");
        }
        refresh();
    }
}
