/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native visual comparison: the identical SAND patch, rough patch and wet patch over different substrates. */
@Tag("on-demand")
class GpuAridSurfaceSmokeTest {
    @Test
    void capturesSharedSandAcrossThemesWithTheirOwnCliffs() throws Exception {
        var themes = List.of("desert", "mars", "grass", "rock", "lunar", "dirt", "volcano");
        var scenes = new ArrayList<BoardScene>();
        for (String theme : themes) {
            var board = Board.createEmptyBoard(9, 7);
            for (int x = 0; x < 9; x++) for (int y = 0; y < 7; y++) {
                boolean sand = x >= 3 && x <= 5 && y >= 2 && y <= 4 || x == 1 && y == 3;
                String cover = sand ? "sand:1" : "";
                if (x == 4 && y == 4) { cover += ";rough:1"; }
                if (x == 5 && y == 2) { cover += ";swamp:1"; }
                board.setHex(new Coords(x, y), new Hex(x <= 2 ? 3 : 0, cover, theme));
            }
            scenes.add(BoardScenes.capture(board));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "arid-surfaces");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    BoardScenes.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    for (int i = 0; i < themes.size(); i++) {
                        var scene = scenes.get(i);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .48f;
                            camera.center(BoardGeometry.center(new Coords(4, 3), 1));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, themes.get(i) + (oblique ? "-oblique.png" : "-top.png")));
                        }
                        camera.camera.zoom = .22f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, themes.get(i) + "-close.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Arid surface review", failure.get()); }
    }
}
