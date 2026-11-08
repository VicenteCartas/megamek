/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Review the reported Waterfall coast from above and at grass height with the same finished terrain. */
@Tag("on-demand")
class GpuShallowCoastSmokeTest {
    @Test
    void capturesWaterfallShallowCoast() throws Exception {
        var board = BoardFile.loadSheet(new File("data/boards/unofficial/Lanceman/16x17 Waterfall.board"), false, true);
        var scene = BoardScenes.scene(board);
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "shallow-coast");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                BoardGeometry.tune(BoardGeometry.DEFAULTS);
                BoardRelief.tune(BoardRelief.DEFAULTS);
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(1280, 1000);
                    camera.camera.zoom = BoardGeometry.width() / 300;
                    camera.center(BoardGeometry.center(new Coords(4, 8), 0));
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.update();
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene, camera.camera);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        terrain.animate(0, List.of());
                        for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
                        GpuReviewFrame.save(new File(output, "waterfall-" + (oblique ? "oblique" : "top") + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardGeometry.tune(geometry);
                    BoardRelief.tune(relief);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Waterfall shallow coast review", failure.get()); }
    }
}
