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

/** Identical 30 m hexes and slopes expose material scale; surfaceScale.cliffTop extends the wall for tall-cliff review. */
@Tag("on-demand")
class GpuSurfaceScaleSmokeTest {
    @Test
    void capturesEverySurfaceAtTheSamePhysicalScale() throws Exception {
        var names = List.of("grass", "dirt", "sand", "rock", "concrete", "snow", "lunar", "fungus",
              "desert", "mars", "volcano", "tropical", "mud", "swamp", "ice", "crust");
        int cliffTop = Integer.getInteger("megamek.gpu.surfaceScale.cliffTop", 5);
        var scenes = new ArrayList<BoardScene>();
        for (String name : names) {
            var board = Board.createEmptyBoard(7, 8);
            String contents = switch (name) {
                case "sand" -> "sand:1";
                case "concrete" -> "pavement:1";
                case "mud" -> "mud:1";
                case "swamp" -> "swamp:1";
                case "ice" -> "ice:1";
                case "crust" -> "magma:1";
                default -> "";
            };
            String theme = contents.isEmpty() ? name : "grass";
            for (int x = 0; x < 7; x++) for (int y = 0; y < 8; y++) {
                int level = y < 2 ? cliffTop : y < 4 ? 2 : y < 6 ? 1 : 0;
                board.setHex(new Coords(x, y), new Hex(level, contents, theme));
            }
            scenes.add(BoardScenes.capture(board));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "surface-scale");
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
                    // Inspect the actual surface maps without grass blades hiding their scale. Runtime density is untouched.
                    terrain.setGrass(false);
                    for (int i = 0; i < names.size(); i++) {
                        var scene = scenes.get(i);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .44f;
                            camera.center(BoardGeometry.center(new Coords(3, 4), oblique ? (cliffTop + 2) / 2 : 2));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, names.get(i) + (oblique ? "-oblique.png" : "-top.png")));
                        }
                        camera.camera.zoom = .18f;
                        camera.center(BoardGeometry.center(new Coords(3, 3), (cliffTop + 2) / 2));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, names.get(i) + "-detail.png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), names.get(i));
                    }
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Surface scale review", failure.get()); }
    }
}
