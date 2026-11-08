/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual theme capture, native foliage materials, shadows, both projections and distant LODs. */
@Tag("on-demand")
class GpuVolcanoFoliageSmokeTest {
    @Test
    void rendersVolcanicTreesAndLowThicketsThroughTheSharedScene() throws Exception {
        var board = Board.createEmptyBoard(10, 8);
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 8; y++) {
                String cover = "";
                if (x >= 1 && x <= 8 && (y == 2 || y == 5)) {
                    int density = x < 4 ? 1 : x < 7 ? 2 : 3;
                    cover = (x == 8 ? "jungle" : "woods") + ":" + density
                          + ";foliage_elev:" + (y == 5 ? 1 : 2);
                }
                if (x == 5) { cover += ";road:1:9"; }
                board.setHex(new Coords(x, y), new Hex(x < 2 ? 1 : 0, cover, "volcano"));
            }
        }
        var captured = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                captured.set(fixture.source.takeFrame().scene());
            });
        }
        var scene = captured.get();
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "volcano-foliage");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1100);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    // Loading must see the same shader flags as the frame that follows.
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    var ray = new Ray(BoardGeometry.center(new Coords(3, 3), 0).add(0, 0, 1000), new Vector3(0, 0, -1));
                    BoardGeometry.Hit picked = null;
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.fit(scene);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        var hit = terrain.hit(scene, ray);
                        assertNotNull(hit);
                        if (picked == null) { picked = hit; } else { assertEquals(picked, hit); }
                        GpuReviewFrame.save(new File(output, oblique ? "volcano-oblique.png" : "volcano-top.png"));
                    }
                    for (int row : new int[] { 2, 5 }) {
                        camera.camera.zoom = .12f;
                        camera.center(BoardGeometry.center(new Coords(3, row), 0).add(0, 0, row == 2 ? 18 : 8));
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, row == 2 ? "volcano-trees-close.png" : "volcano-thickets-close.png"));
                    }
                    camera.camera.zoom = 2.4f;
                    camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    assertEquals(picked, terrain.hit(scene, ray));
                    GpuReviewFrame.save(new File(output, "volcano-far.png"));
                    camera.camera.zoom = 8;
                    camera.update();
                    frame.render(terrain, camera, scene);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Volcanic foliage review", failure.get()); }
    }
}
