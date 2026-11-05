/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Depth cue review: Crystalline Canyon's pillars in front of its far walls without fog, each view bare, with the haze
 * behind the focus, with the sky occlusion by the relief (GpuSkyVisibility, always on on the board) and with both, as
 * the board draws them with the Display page's Depth cues. Then the focus easing: a cut from one view to another played
 * at 60 frames a second.
 */
@Tag("on-demand")
class GpuDepthCuesSmokeTest {
    /** A tilt of 0 looks straight down. */
    private record View(String name, Coords at, float zoom, float azimuth, float tilt) { }

    /** A capture's cues: the haze's strength and the sky occlusion's. */
    private record Look(String name, float haze, float sky) { }

    private static final List<Look> LOOKS = List.of(new Look("off", 0, 0), new Look("haze", GpuDepthCues.STRENGTH, 0),
          new Look("sky", 0, 1), new Look("both", GpuDepthCues.STRENGTH, 1));

    private static final List<View> VIEWS = List.of(
          new View("wall", new Coords(10, 6), .16f, 230, 70),
          new View("gap", new Coords(14, 8), .22f, 250, 68),
          new View("play", new Coords(15, 8), .7f, 45, 54.7356f),
          new View("overview", new Coords(15, 8), 1.9f, 45, 54.7356f),
          new View("top", new Coords(15, 8), .5f, 0, 0));
    /** The frames after the cut that the easing review saves. */
    private static final List<Integer> EASING_FRAMES = List.of(1, 6, 18, 60);
    private static final File BOARD = new File("data/boards/Alien Worlds/32x17 Crystalline Canyon.board2");

    @Test
    void capturesCanyonWithAndWithoutDepthCues() throws Exception {
        Board board = BoardFile.loadSheet(BOARD, false, true);
        AtomicReference<BoardScene> scene = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        File output = output();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    System.out.println("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.update(scene.get());
                    terrain.animate(.5f, List.of());
                    for (View view : VIEWS) {
                        place(camera, view, scene.get());
                        GpuReviewFrame.settle(terrain, null, scene.get(), camera);
                        // Each look starts from this view's own focus: the first, without cues, forgets the last one.
                        for (Look look : LOOKS) {
                            show(frame, look);
                            frame.render(terrain, camera, scene.get());
                            GpuReviewFrame.save(new File(output, view.name() + "-" + look.name() + ".png"));
                        }
                    }
                    View from = VIEWS.get(1), to = VIEWS.get(0);
                    place(camera, from, scene.get());
                    GpuReviewFrame.settle(terrain, null, scene.get(), camera);
                    show(frame, LOOKS.getFirst());
                    frame.render(terrain, camera, scene.get());
                    show(frame, LOOKS.getLast());
                    frame.render(terrain, camera, scene.get());
                    place(camera, to, scene.get());
                    GpuReviewFrame.settle(terrain, null, scene.get(), camera);
                    frame.setFrameSeconds(1 / 60f);
                    for (int index = 1; index <= EASING_FRAMES.get(EASING_FRAMES.size() - 1); index++) {
                        frame.render(terrain, camera, scene.get());
                        if (EASING_FRAMES.contains(index)) {
                            GpuReviewFrame.save(new File(output, String.format("easing-%02d.png", index)));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    // The canyon from above darkened 3.6% at full strength: the volume reaches the image.
                    if (GpuSkyVisibility.STRENGTH > 0) {
                        double open = brightness(new File(output, "top-off.png"));
                        assertTrue(brightness(new File(output, "top-sky.png")) < open * (1 - .02 * GpuSkyVisibility.STRENGTH),
                              "The relief's sky occlusion darkens the canyon");
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Depth cue review", failure.get()); }
    }

    /**
     * The Display page's Depth cues in the battle view: saved on, the haze's program links while the board loads and the
     * view draws the haze; switched off, the next frame draws without it. The sky occlusion stays on either way.
     * Captures of both.
     */
    @Test
    void displayOptionDrivesTheBattleView() throws Exception {
        var preferences = GUIPreferences.getInstance();
        boolean original = preferences.getBoardDepthCues();
        var failure = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardDepthCues(true));
            Board board = BoardFile.loadSheet(BOARD, false, true);
            File output = output();
            try (var fixture = GpuBoardFixture.create(board)) {
                var configuration = GpuBoardWindow.configuration(false);
                configuration.setWindowedMode(1440, 1080);
                configuration.setInitialVisible(false);
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override public void create() {
                        GpuBattleView view = null;
                        try {
                            view = new GpuBattleView(fixture.source);
                            view.create();
                            GpuBoardTestUi.present(view);
                            var atmosphere = (GpuAtmosphere) GpuTestSupport.field(view, "atmosphere");
                            assertNotNull(GpuTestSupport.field(atmosphere, "depthCues"),
                                  "Saved on, the depth cues link while the board loads");
                            place(view.boardCamera, VIEWS.get(0), (BoardScene) GpuTestSupport.field(view, "scene"));
                            GpuBoardTestUi.present(view);
                            var sky = (GpuSkyVisibility) GpuTestSupport.field(view, "skyVisibility");
                            assertEquals(GpuDepthCues.STRENGTH, GpuTestSupport.<Object>field(atmosphere, "depthCueStrength"));
                            assertEquals(GpuSkyVisibility.STRENGTH, GpuTestSupport.<Object>field(sky, "strength"));
                            GpuBoardTestUi.capture(new File(output, "battle-view-on.png"));
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(view, "tuning-depth-cues"));
                            GpuBoardTestUi.present(view);
                            assertEquals(0f, GpuTestSupport.<Object>field(atmosphere, "depthCueStrength"));
                            assertEquals(GpuSkyVisibility.STRENGTH, GpuTestSupport.<Object>field(sky, "strength"),
                                  "Sky occlusion is lighting");
                            GpuBoardTestUi.capture(new File(output, "battle-view-off.png"));
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            if (view != null) { view.dispose(); }
                            Gdx.app.exit();
                        }
                    }
                }, configuration);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardDepthCues(original));
        }
        if (failure.get() != null) { throw new AssertionError("Depth cues from the Display page", failure.get()); }
    }

    private static void show(GpuReviewFrame frame, Look look) {
        frame.atmosphere().setDepthCues(look.haze());
        frame.setSkyVisibility(look.sky());
    }

    /** A saved capture's mean of its colour channels. */
    private static double brightness(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                sum += (rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255);
            }
        }
        return sum / (3.0 * image.getWidth() * image.getHeight());
    }

    private static File output() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "depth-cues");
        Files.createDirectories(output.toPath());
        return output;
    }

    private static void place(BoardCamera camera, View view, BoardScene scene) {
        // From straight down, north up.
        camera.setIsometric(false);
        camera.orbit(view.azimuth(), view.tilt());
        camera.camera.zoom = view.zoom();
        camera.center(BoardGeometry.center(view.at(), scene.tile(view.at()).elevation()));
    }
}
