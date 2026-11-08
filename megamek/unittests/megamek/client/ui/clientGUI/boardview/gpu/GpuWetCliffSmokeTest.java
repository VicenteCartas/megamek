/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Cliff/bank junctions at varying bed depths, also reviewable on a supplied board. */
@Tag("on-demand")
class GpuWetCliffSmokeTest {
    @Test
    void rendersCliffEndsAboveAndBelowDeepWater() throws Exception {
        String board = System.getProperty("megamek.gpu.cliff.board", "");
        BoardScene scene;
        if (board.isBlank()) {
            scene = BoardScenes.mixedDepthScene();
        } else {
            // As a game loads a sheet: converted.
            var loaded = BoardFile.loadSheet(new File(board), false, true);
            try (var fixture = GpuBoardFixture.create(loaded)) {
                SwingUtilities.invokeAndWait(fixture.source::refresh);
                scene = fixture.source.takeFrame().scene();
            }
        }
        Coords focus = board.isBlank() ? new Coords(4, 3) : new Coords(7, 9);
        var failure = new AtomicReference<Throwable>();
        var original = BoardRelief.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                          board.isBlank() ? "mixed-depth-cliffs" : "custom-board-cliffs");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardScenes.tune(true);
                    terrain.update(scene);
                    terrain.animate(.4f, List.of());
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int angle : new int[] { 0, 90, 180, 270 }) {
                        camera.setIsometric(true);
                        camera.orbit(angle, 0);
                        camera.camera.zoom = .17f;
                        camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                        for (boolean water : new boolean[] { true, false }) {
                            frame.render(terrain, camera, scene, water);
                            GpuReviewFrame.save(new File(output, "cliff-" + angle + (water ? "-water" : "-bed") + ".png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardRelief.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Deep-water cliff transitions", failure.get()); }
    }
}
