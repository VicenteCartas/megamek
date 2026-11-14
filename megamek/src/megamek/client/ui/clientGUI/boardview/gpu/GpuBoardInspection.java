/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Disposable;
import jakarta.annotation.Nullable;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.logging.MMLogger;

/**
 * A read-only look at a board in the game's own renderer, for screens outside the game (a campaign's war table, say)
 * to embed: the lobby's board inspection, with its camera, line of sight and hex card.
 *
 * <p>Built and used on the GL thread. {@link #inspect} makes the board on a worker, readies it on the EDT as the lobby
 * does, and shows it back on the GL thread.</p>
 */
public final class GpuBoardInspection implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardInspection.class);
    private final LobbyBoardPreview preview;
    private final Application app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Board inspection");
        thread.setDaemon(true);
        return thread;
    });
    private volatile int ticket;
    private volatile boolean disposed;

    /**
     * An inspection in {@code kit}'s look, {@code theme} being the theme of its skin, whose back button reads
     * {@code back} and runs {@code close}. Adds the board's hex-label fonts to the skin when it has none.
     */
    public GpuBoardInspection(UiTheme theme, UiKit kit, String back, Runnable close) {
        if (!theme.skin.has("default-font", BitmapFont.class)) {
            GpuBoardSkin.worldFonts(theme);
        }
        preview = new LobbyBoardPreview(kit, back, close);
        app = Gdx.app;
    }

    /** The inspection's panel, for the screen to lay out; it must be on a stage before a board shows. */
    public Actor actor() {
        return preview;
    }

    /**
     * Shows {@code title} loading, then the board {@code board} makes on a worker under {@code conditions} (the
     * defaults when null). A later call, or {@link #close}, drops a board still on its way.
     */
    public void inspect(String title, Supplier<Board> board, @Nullable PlanetaryConditions conditions) {
        int asked = ++ticket;
        preview.loading(title);
        worker.execute(() -> {
            Board made = null;
            try {
                made = board.get();
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The board inspection could not make {}", title);
            }
            Board loaded = made;
            SwingUtilities.invokeLater(() -> {
                BoardSource source = null;
                if (loaded != null && asked == ticket && !disposed) {
                    try {
                        Game game = new Game();
                        game.setPhase(GamePhase.LOUNGE);
                        game.setBoard(loaded);
                        if (conditions != null) {
                            game.setPlanetaryConditions(new PlanetaryConditions(conditions));
                        }
                        source = new GpuMapSource(game, null, null);
                    } catch (RuntimeException failure) {
                        LOGGER.warn(failure, "The board inspection could not prepare {}", title);
                    }
                }
                BoardSource ready = source;
                app.postRunnable(() -> {
                    if (asked == ticket && !disposed) {
                        preview.show(ready);
                    } else if (ready != null) {
                        ready.close();
                    }
                });
            });
        });
    }

    /** Drops the board shown or on its way, keeping the panel for the next. GL thread. */
    public void close() {
        ticket++;
        preview.dispose();
    }

    @Override
    public void dispose() {
        disposed = true;
        close();
        worker.shutdownNow();
    }
}
