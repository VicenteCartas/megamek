/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.Disposable;
import jakarta.annotation.Nullable;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.logging.MMLogger;

/**
 * A board drawn by the game's terrain and atmosphere under a screen's own camera, for screens outside the game (a
 * campaign's war table, say) to draw their own marks over: no hex grid, labels or units, only the ground, its water,
 * plants, roads and buildings, lit and weathered as in battle.
 *
 * <p>Board space is the game's: x runs east and y north across the board, z up, a hex {@link
 * megamek.common.board.HexTile#MODEL_WIDTH} wide and a level {@link megamek.common.board.HexTile#LEVEL_HEIGHT} high,
 * hex centres at {@link megamek.common.board.HexTile#centerX} and {@link megamek.common.board.HexTile#centerY}.</p>
 *
 * <p>Built and used on the GL thread. {@link #show} makes the board on a worker, readies it on the EDT as the lobby
 * does, and takes it back on the GL thread; {@link #render} then draws it once its terrain is ready.</p>
 */
public final class GpuEmbeddedBoard implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuEmbeddedBoard.class);
    private final Application app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Embedded board");
        thread.setDaemon(true);
        return thread;
    });
    private volatile int ticket;
    private volatile boolean disposed;
    private @Nullable BoardSource source;
    private @Nullable BoardScene scene;
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    private GpuAmbientOcclusion ambientOcclusion;
    private GpuSkyVisibility skyVisibility;
    private @Nullable FrameBuffer target;
    private boolean drawn;
    private boolean failed;

    /** A board drawn on the GL thread of the application running now. */
    public GpuEmbeddedBoard() {
        app = Gdx.app;
    }

    /**
     * Shows the board {@code board} makes on a worker under {@code conditions} (the defaults when null), the board
     * shown until now staying until it's ready. A later call, or {@link #close}, drops a board still on its way.
     */
    public void show(Supplier<Board> board, @Nullable PlanetaryConditions conditions) {
        int asked = ++ticket;
        worker.execute(() -> {
            Board made = null;
            try {
                made = board.get();
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The embedded board could not be made");
            }
            Board loaded = made;
            SwingUtilities.invokeLater(() -> {
                BoardSource prepared = null;
                if (loaded != null && asked == ticket && !disposed) {
                    try {
                        Game game = new Game();
                        game.setPhase(GamePhase.LOUNGE);
                        game.setBoard(loaded);
                        if (conditions != null) {
                            game.setPlanetaryConditions(new PlanetaryConditions(conditions));
                        }
                        prepared = new GpuMapSource(game, null, null);
                    } catch (RuntimeException failure) {
                        LOGGER.warn(failure, "The embedded board could not be prepared");
                    }
                }
                BoardSource ready = prepared;
                app.postRunnable(() -> {
                    if (asked == ticket && !disposed && ready != null) {
                        install(ready);
                    } else if (ready != null) {
                        ready.close();
                    }
                });
            });
        });
    }

    private void install(BoardSource next) {
        if (source != null) {
            source.close();
        }
        source = next;
        scene = next.takeFrame().scene();
        failed = false;
        if (terrain != null) {
            // A new board's terrain builds afresh; the old one's would linger over it while it loads.
            disposeRenderer();
        }
    }

    /** Whether a board is shown and its terrain has been drawn at least once. */
    public boolean ready() {
        return drawn && source != null && !failed;
    }

    /**
     * Draws the board as {@code camera} sees it, in board space, {@code width} by {@code height} pixels, and gives
     * the picture, upside up as a frame buffer's texture is; null while there's no board or its terrain is still
     * loading. The camera's projection must be up to date; the scene's depth, the same projection's, is then {@link
     * #depth}. GL thread; leaves the default frame buffer bound.
     */
    public @Nullable Texture render(Camera camera, int width, int height, float delta) {
        if (source == null || source.isClosed() || failed || width < 1 || height < 1) {
            return null;
        }
        BoardSource.Frame frame = source.takeFrame();
        scene = frame.scene();
        GpuShaderWarmup.boardDrawn();
        boolean[] done = new boolean[1];
        try {
            GpuGlsl.offscreen(() -> done[0] = draw(frame, camera, width, height, delta));
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The embedded board could not render");
            failed = true;
            disposeRenderer();
            return null;
        } finally {
            FrameBuffer.unbind();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
        drawn |= done[0];
        return done[0] && target != null ? target.getColorBufferTexture() : null;
    }

    /** The depth of what {@link #render} last drew, a depth texture under its camera's projection; null before. */
    public @Nullable Texture depth() {
        return atmosphere == null || !drawn ? null : atmosphere.depthTexture();
    }

    private boolean draw(BoardSource.Frame frame, Camera camera, int width, int height, float delta) {
        var conditions = frame.scenarioAtmosphere();
        var display = source.uiPreferences().display();
        BoardCamera.renderScale = display.resolution();
        if (terrain == null) {
            terrain = new GpuTerrain();
            atmosphere = new GpuAtmosphere(conditions, GpuAtmosphere.Options.DEFAULTS);
            atmosphere.prepare();
            terrain.prepareEffects();
            ambientOcclusion = new GpuAmbientOcclusion();
            skyVisibility = new GpuSkyVisibility();
        }
        atmosphere.configure(conditions);
        display.apply(terrain, atmosphere, ambientOcclusion, width, height, false);
        skyVisibility.update(terrain.environment(), scene, 1);
        terrain.setColumns(false);
        terrain.setGravity(conditions.gravity());
        terrain.setVacuum(conditions.pressure().isVacuum());
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            if (target != null) {
                target.dispose();
            }
            target = GpuAtmosphere.buffer(width, height, true);
            target.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        atmosphere.updateLight(camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        terrain.update(scene, camera);
        terrain.refine(camera);
        if (!terrain.ready(scene)) {
            return false;
        }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
            atmosphere.prepareClouds(terrain, scene, delta);
            terrain.animate(delta, List.of());
            terrain.renderShadows(camera, List.of());
            atmosphere.beginDisplayPixels(width, height, display.resolution(), delta);
            terrain.render(camera, () -> { },
                  () -> ambientOcclusion.render(camera, terrain, atmosphere.depthTexture()));
            ambientOcclusion.end();
            terrain.renderTransparent(camera);
            atmosphere.renderWeather(camera, scene);
            atmosphere.endDisplay(camera, terrain, scene, target);
        }
        return true;
    }

    /** Drops the board shown or on its way. GL thread. */
    public void close() {
        ticket++;
        if (source != null) {
            source.close();
            source = null;
        }
        scene = null;
        disposeRenderer();
    }

    private void disposeRenderer() {
        drawn = false;
        if (terrain != null) {
            // As the battle view does: nothing drawn without a view inherits this one's choice.
            BoardQuality.current = BoardQuality.HIGH;
            terrain.dispose();
            terrain = null;
        }
        if (atmosphere != null) {
            atmosphere.dispose();
            atmosphere = null;
        }
        if (ambientOcclusion != null) {
            ambientOcclusion.dispose();
            ambientOcclusion = null;
        }
        if (skyVisibility != null) {
            skyVisibility.dispose();
            skyVisibility = null;
        }
        if (target != null) {
            target.dispose();
            target = null;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        close();
        worker.shutdownNow();
    }
}
