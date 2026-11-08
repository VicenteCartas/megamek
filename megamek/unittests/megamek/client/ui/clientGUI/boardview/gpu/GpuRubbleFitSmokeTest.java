/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Close views of a rubble pile's contact with level ground, one exposed edge and an isolated plateau. */
@Tag("on-demand")
class GpuRubbleFitSmokeTest {
    @Test
    void rendersRubbleOnLevelGroundAndCliffs() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "rubble-fit");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.8f, true);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    for (int exits : new int[] { 0, 1, 63 }) {
                        int level = exits == 0 ? 0 : 3;
                        var scene = BoardRubbleFitTest.scene(level, exits, "hardened");
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        for (int view = 0; view < 3; view++) {
                            camera.setIsometric(view != 0);
                            if (view == 2) { camera.orbit(0, 18); }
                            camera.camera.zoom = .12f;
                            camera.center(BoardGeometry.center(BoardRubbleFitTest.CENTER, level));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "rubble-" + exits + "-view-" + view + ".png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(original);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Rubble plateau contact", failure.get()); }
    }
}
