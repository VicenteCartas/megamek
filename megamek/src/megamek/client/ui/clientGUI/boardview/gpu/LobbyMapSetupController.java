/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;
import megamek.client.lobby.MapSetupForm;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.loaders.MapSettings;
import megamek.common.util.BoardUtilities;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;

/**
 * The Swing-thread side of the lobby's map setup: it holds the {@link MapSetupForm}, runs the view's actions on it,
 * sends each fresh {@link MapSetupForm.Picture} to the GPU thread, after an action and whenever the lobby reads the
 * game again, and draws the boards' pictures on a thread of its own.
 */
final class LobbyMapSetupController {
    private static final MMLogger LOGGER = MMLogger.create(LobbyMapSetupController.class);
    /** The long side of a board's picture, in pixels: a tile is drawn no larger. */
    static final int PICTURE_SIDE = LobbyMapThumbnail.LONG_SIDE / 2;

    private final MapSetupForm form;
    private final BiConsumer<MapSetupForm.Picture, List<MapSetupForm.Theme>> show;
    private final BiConsumer<String, BufferedImage> picture;
    private final Set<String> requested = new HashSet<>();
    private final ExecutorService painter = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Lobby map setup boards");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param show    hands a picture of the setup, with the themes any slot can take, to the GPU thread
     * @param picture hands a board's picture, by {@link #pictureKey}, to the GPU thread
     */
    LobbyMapSetupController(MapSetupForm form, BiConsumer<MapSetupForm.Picture, List<MapSetupForm.Theme>> show,
          BiConsumer<String, BufferedImage> picture) {
        this.form = form;
        this.show = show;
        this.picture = picture;
    }

    MapSetupForm form() {
        return form;
    }

    /** The key of a board file's picture at a board size, rotated, with a slot's theme and level offset. */
    static String pictureKey(String file, boolean rotated, int width, int height, @Nullable String theme,
          int offset) {
        return file + '|' + (rotated ? "r" : "") + '|' + width + 'x' + height + '|' + (theme == null ? "" : theme)
              + '|' + offset;
    }

    /** The key of a slot's picture, or null when it has no board file to draw. */
    static @Nullable String pictureKey(MapSetupForm.Slot slot, MapSetupForm.Picture setup) {
        if (slot.file().isBlank() || setup.space()) {
            return null;
        }
        return pictureKey(slot.file(), slot.rotated() && slot.canRotate(), setup.boardWidth(), setup.boardHeight(),
              slot.theme(), slot.levelOffset());
    }

    /** EDT: takes the server's settings again, if it sent others, and sends the picture. */
    void refresh() {
        try {
            form.reloadIfNew();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The map setup could not read the map settings");
            return;
        }
        send();
    }

    /** EDT: runs one of the view's actions on the form, then sends the picture. */
    void act(Consumer<MapSetupForm> action) {
        try {
            action.accept(form);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The map setup action failed");
        }
        send();
    }

    /** EDT: draws the picture of a board of the list, unless it is drawn or being drawn. */
    void preview(String board) {
        MapSettings settings = form.settings();
        if (!board.startsWith(MapSettings.BOARD_GENERATED)) {
            paint(board, false, settings.getBoardWidth(), settings.getBoardHeight(), null, 0, -1);
        }
    }

    /** Whether the odd-sized board warning is still wanted. */
    static boolean nagWanted() {
        return GUIPreferences.getInstance().getNagForOddSizedBoard();
    }

    private void send() {
        MapSetupForm.Picture setup;
        List<MapSetupForm.Theme> themes;
        try {
            setup = form.picture();
            themes = form.themes(-1);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The map setup could not read the map settings");
            return;
        }
        for (MapSetupForm.Slot slot : setup.slots()) {
            if (pictureKey(slot, setup) != null) {
                paint(slot.file(), slot.rotated() && slot.canRotate(), setup.boardWidth(), setup.boardHeight(),
                      slot.theme(), slot.levelOffset(), slot.index());
            }
        }
        show.accept(setup, themes);
    }

    /** Draws a board file's picture on the painter's thread, the slot's theme and offset applied when it has one. */
    private void paint(String file, boolean rotated, int width, int height, @Nullable String theme, int offset,
          int slot) {
        String key = pictureKey(file, rotated, width, height, theme, offset);
        if (!requested.add(key)) {
            return;
        }
        MapSettings settings = MapSettings.getInstance(form.settings());
        painter.execute(() -> {
            try {
                Board board = load(file, rotated, width, height);
                if (slot >= 0 && slot < settings.getMapWidth() * settings.getMapHeight()) {
                    settings.applyBoardOverrides(board, slot);
                }
                picture.accept(key, LobbyMapThumbnail.bake(board, List.of(), null, PICTURE_SIDE));
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The map setup could not draw the board {}", file);
            }
        });
    }

    /** The board as the Swing dialog draws it: a blank one for a board only the server has. */
    static Board load(String file, boolean rotated, int width, int height) {
        File boardFile = new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(file)).getFile();
        if (!boardFile.exists()) {
            return Board.createEmptyBoard(width, height);
        }
        Board board = new Board(16, 17);
        // Continues on rules errors, as the Swing dialog's pictures do
        try (InputStream in = new FileInputStream(boardFile)) {
            board.load(in, null, true);
            BoardUtilities.flip(board, rotated, rotated);
            return board;
        } catch (IOException failure) {
            return Board.createEmptyBoard(width, height);
        }
    }

    /** EDT: whether the board exists only on the server, so its picture is blank. */
    static boolean serverSide(String file) {
        return !new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(file)).getFile().exists();
    }

    /** As the map setup closes: stops the painter. */
    void dispose() {
        painter.shutdownNow();
    }

    /** For the probe: the settings as a line. */
    static String describe(MapSettings settings) {
        return String.format("medium %d, map %dx%d boards of %dx%d, boards %s, themes and offsets [%s]",
              settings.getMedium(), settings.getMapWidth(), settings.getMapHeight(), settings.getBoardWidth(),
              settings.getBoardHeight(), settings.getBoardsSelectedVector(), themes(settings));
    }

    private static String themes(MapSettings settings) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < settings.getMapWidth() * settings.getMapHeight(); index++) {
            String theme = settings.getBoardTheme(index);
            int offset = settings.getBoardLevelOffset(index);
            if (theme != null || offset != 0) {
                text.append(index).append(':').append(theme).append('/').append(offset).append(' ');
            }
        }
        return text.toString().trim();
    }
}
