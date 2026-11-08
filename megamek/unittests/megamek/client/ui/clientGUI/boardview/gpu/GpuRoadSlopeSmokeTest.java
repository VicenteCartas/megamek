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
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual maps from the uphill/downhill slope reports and the authored cliff road end. */
@Tag("on-demand")
class GpuRoadSlopeSmokeTest {
    @Test
    void capturesNativeRoadSlopesAndTheMaintainedTunnelAsset() throws Exception {
        var mines = BoardScenes.minesScene();
        var streets = BoardScenes.installedScene("unofficial/DarkISI/16x17 Strassengitter 3.board");
        var lava = BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        var mesa = BoardScenes.installedScene("unofficial/SimonLandmine/96x51/96x51 MesaCity1 N - Mesas.board");
        var fireIce = BoardScenes.installedScene("unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "road-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(weather);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (var scene : List.of(mines, streets, lava, fireIce, mesa)) {
                        String map = scene == fireIce ? "fire-and-ice-2" : scene == mesa ? "mesa-city1" : scene == mines ? "mines"
                              : scene == lava ? "lava-tubes" : "strassengitter";
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        for (var at : scene == mesa ? List.of(new Coords(42, 20), new Coords(47, 20), new Coords(44, 20))
                              : scene == fireIce ? List.of(new Coords(4, 8), new Coords(8, 8))
                              : scene == mines ? List.of(new Coords(7, 14), new Coords(2, 5))
                              : scene == lava ? List.of(new Coords(5, 5), new Coords(9, 9))
                                    : List.of(new Coords(7, 8), new Coords(6, 9))) {
                            for (boolean oblique : new boolean[] { false, true }) {
                                camera.setIsometric(oblique);
                                if ((scene == mesa || scene == fireIce) && oblique) { camera.orbit(150, 10); }
                                camera.camera.zoom = scene == mesa && at.getX() == 44 ? .4f : .22f;
                                camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
                                GpuReviewFrame.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, map + "-" + at.getBoardNum() + "-" + oblique + ".png"));
                            }
                        }
                    }
                    for (var scene : List.of(mines, lava)) {
                        terrain.update(scene);
                        var portals = scene.tiles().stream().flatMap(t -> BoardTunnel.entrances(scene, t).stream()).toList();
                        assertEquals(scene == mines ? 2 : 0, portals.size());
                        int index = 0;
                        for (var portal : portals) {
                            camera.setIsometric(true);
                            camera.camera.zoom = .10f;
                            float azimuth = (float) Math.toDegrees(Math.atan2(-portal.along().x, portal.along().y));
                            camera.orbit(azimuth - camera.azimuth() + 20, 0);
                            camera.center(new Vector3(portal.origin()).add(0, 0, 7 * BoardGeometry.hexScale()));
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, (scene == mines ? "tunnel-" : "bridge-tunnel-")
                                  + portal.road().getBoardNum() + "-" + index++ + ".png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native road slopes and tunnels", failure.get()); }
    }
}
