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
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.client.lobby.MapSetupForm;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
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
    private final Supplier<Board> battlefield;
    private final BiConsumer<MapSetupForm.Picture, List<MapSetupForm.Theme>> show;
    private final BiConsumer<String, BufferedImage> picture;
    private final Set<String> requested = new HashSet<>();
    private final Set<String> generatedKeys = new HashSet<>();
    private List<String> visibleBoards = List.of();
    private boolean browserPainting;
    private Board generatedBoard;
    private int generation;
    private volatile boolean disposed;
    private final ExecutorService painter = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Lobby map setup boards");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param show    hands a picture of the setup, with the themes any slot can take, to the GPU thread
     * @param picture hands a board's picture, by {@link #pictureKey}, to the GPU thread
     */
    LobbyMapSetupController(MapSetupForm form, Supplier<Board> battlefield,
          BiConsumer<MapSetupForm.Picture, List<MapSetupForm.Theme>> show,
          BiConsumer<String, BufferedImage> picture) {
        this.form = form;
        this.battlefield = battlefield;
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

    /** The key of a slot's picture, or null when it has no visible board to draw. */
    static @Nullable String pictureKey(MapSetupForm.Slot slot, MapSetupForm.Picture setup) {
        if (slot.kind() == MapSetupForm.Kind.GENERATED && !setup.space()) {
            return "generated:" + slot.index() + '|' + setup.boardWidth() + 'x' + setup.boardHeight();
        }
        if (slot.file().isBlank() || setup.space()) {
            return null;
        }
        return pictureKey(slot.file(), slot.rotated() && slot.canRotate(), setup.boardWidth(), setup.boardHeight(),
              slot.theme(), slot.levelOffset());
    }

    /** EDT: takes the server's settings again, if it sent others, and sends the picture. */
    void refresh() {
        try {
            MapSettings before = MapSettings.getInstance(form.settings());
            form.reloadIfNew();
            if (!MapSetupForm.sameSetup(before, form.settings())) {
                clearGenerated();
            }
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The map setup could not read the map settings");
            return;
        }
        send();
    }

    /** EDT: runs one of the view's actions on the form, then sends the picture. */
    void act(Consumer<MapSetupForm> action) {
        MapSettings before = MapSettings.getInstance(form.settings());
        try {
            action.accept(form);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The map setup action failed");
        }
        if (!MapSetupForm.sameSetup(before, form.settings())) {
            clearGenerated();
            // This board belongs to the setup before the edit. Wait for its server replacement.
            generatedBoard = battlefield.get();
        }
        send();
    }

    /** Captures a private read-only scene on the painter, never a mutable game board on the GPU thread. */
    void inspect(String name, Consumer<BoardScene> ready) {
        painter.execute(() -> {
            BoardScene scene = null;
            try {
                File file = new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(name)).getFile();
                Board board = GpuBoardWindow.loadPreview(file);
                scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
                      List.of(), List.of(), -1, "", List.of());
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The map setup could not inspect the board {}", name);
            }
            if (!disposed) {
                ready.accept(scene);
            }
        });
    }

    /** EDT: replaces the browser's demand. Only one browser thumbnail is ever in flight. */
    void previews(List<String> boards) {
        visibleBoards = List.copyOf(boards);
        paintNextVisible();
    }

    private void paintNextVisible() {
        if (disposed || browserPainting) {
            return;
        }
        MapSettings settings = form.settings();
        int width = settings.getBoardWidth(), height = settings.getBoardHeight();
        for (String file : visibleBoards) {
            String key = pictureKey(file, false, width, height, null, 0);
            if (file.startsWith(MapSettings.BOARD_GENERATED) || !requested.add(key)) {
                continue;
            }
            browserPainting = true;
            painter.execute(() -> {
                BufferedImage image = null;
                try {
                    image = LobbyMapThumbnail.bake(load(file, false, width, height), List.of(), null, PICTURE_SIDE);
                } catch (RuntimeException failure) {
                    LOGGER.warn(failure, "The map setup could not draw the board {}", file);
                }
                BufferedImage finished = image;
                SwingUtilities.invokeLater(() -> {
                    browserPainting = false;
                    if (!disposed) {
                        if (finished != null) {
                            picture.accept(key, finished);
                        }
                        // Choose from the current viewport, not the rows that were visible when this job started.
                        paintNextVisible();
                    }
                });
            });
            return;
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
            if (!slot.file().isBlank() && !setup.space()) {
                paint(slot.file(), slot.rotated() && slot.canRotate(), setup.boardWidth(), setup.boardHeight(),
                      slot.theme(), slot.levelOffset(), slot.index());
            }
        }
        show.accept(setup, themes);
        generated(setup);
    }

    private void clearGenerated() {
        generation++;
        generatedKeys.forEach(key -> picture.accept(key, null));
        generatedKeys.clear();
    }

    /** The server publishes a new board after generation. Slice that exact result into the generated slots. */
    private void generated(MapSetupForm.Picture setup) {
        Board board = battlefield.get();
        if (board == generatedBoard) {
            return;
        }
        generatedBoard = board;
        clearGenerated();
        MapSettings settings = MapSettings.getInstance(form.settings());
        int ticket = generation;
        for (MapSetupForm.Slot slot : setup.slots()) {
            if (slot.kind() != MapSetupForm.Kind.GENERATED || setup.space()) {
                continue;
            }
            String key = pictureKey(slot, setup);
            generatedKeys.add(key);
            painter.execute(() -> {
                Board sheet = sheet(board, settings, slot.index());
                if (sheet != null) {
                    BufferedImage image = LobbyMapThumbnail.bake(sheet, List.of(), null, PICTURE_SIDE);
                    SwingUtilities.invokeLater(() -> {
                        if (!disposed && ticket == generation) {
                            picture.accept(key, image);
                        }
                    });
                }
            });
        }
    }

    /** Copies a sheet from the assembled board, including the padding between odd-width columns. */
    static @Nullable Board sheet(@Nullable Board board, MapSettings settings, int slot) {
        int width = settings.getBoardWidth(), height = settings.getBoardHeight();
        int across = settings.getMapWidth(), down = settings.getMapHeight();
        if (board == null || slot < 0 || slot >= across * down || board.getHeight() != height * down
              || board.getWidth() < width * across) {
            return null;
        }
        int padding = across > 1 ? (board.getWidth() - width * across) / (across - 1) : 0;
        if (board.getWidth() != width * across + padding * (across - 1)
              || width % 2 == 0 && padding != 0) {
            return null;
        }
        int left = slot % across * (width + padding), top = slot / across * height;
        Hex[] hexes = new Hex[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                hexes[y * width + x] = board.getHex(left + x, top + y).duplicate();
                hexes[y * width + x].setCoords(new Coords(x, y));
            }
        }
        Board result = new Board();
        result.copyMetadataFrom(board);
        result.newData(width, height, hexes, null);
        return result;
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
                BufferedImage image = LobbyMapThumbnail.bake(board, List.of(), null, PICTURE_SIDE);
                if (!disposed) {
                    picture.accept(key, image);
                }
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
        disposed = true;
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
