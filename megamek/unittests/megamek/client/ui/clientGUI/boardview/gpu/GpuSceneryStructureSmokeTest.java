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
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native views expose floating hazard markings, slab coverage and open structural supports. */
@Tag("on-demand")
class GpuSceneryStructureSmokeTest {
    @Test
    void sharedStructuresPreserveGrassSandAndSnowUnderTheirOpenSpaces() throws Exception {
        var samples = new ArrayList<GpuSceneryPlacementSmokeTest.Sample>();
        for (int variant : new int[] { 7, 99 }) {
            samples.add(new GpuSceneryPlacementSmokeTest.Sample("barrier-" + variant, 0,
                  "pavement:1", "", "fluff:5:" + variant, false));
        }
        String[] ground = { "", "sand:1", "snow:2" };
        String[] names = { "grass", "sand", "snow" };
        for (int biome = 0; biome < ground.length; biome++) for (int variant = 1; variant <= 3; variant++) {
            samples.add(new GpuSceneryPlacementSmokeTest.Sample("suburb" + variant + "-" + names[biome],
                  0, ground[biome], "", "fluff:7:" + (variant + 2), false));
        }
        var scenes = new ArrayList<BoardScene>();
        for (var sample : samples) { scenes.add(GpuSceneryPlacementSmokeTest.capture(sample, true)); }
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "scenery-structures");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int index = 0; index < scenes.size(); index++) {
                        var scene = scenes.get(index);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        for (boolean oblique : List.of(false, true)) {
                            camera.setIsometric(oblique);
                            camera.center(BoardGeometry.center(new Coords(2, 2), 0));
                            camera.camera.zoom = .10f;
                            camera.update();
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, samples.get(index).name()
                                  + (oblique ? "-oblique.png" : "-top.png")));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scenery structure rendering", failure.get()); }
    }
}
