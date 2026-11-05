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
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every parked-car variant converted (its cars seated in their car-park decal's bays), in the Tactical View, top down and
 * oblique, with each installed car where its placed object says.
 */
@Tag("on-demand")
class GpuSceneryCarSmokeTest {
    @Test
    void sharedParkedCarsRetainTheirAuthoredPositionsBesideEveryRoadDirection() throws Exception {
        var scenes = new ArrayList<BoardScene>();
        for (int variant : new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 98, 99 }) {
            scenes.add(GpuSceneryPlacementSmokeTest.capture(new GpuSceneryPlacementSmokeTest.Sample(
                  "cars-" + variant, 0, "pavement:1", "", "fluff:5:" + variant, false), true));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "fluff-cars");
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
                    var coords = new Coords(2, 2);
                    for (int index = 0; index < scenes.size(); index++) {
                        var scene = scenes.get(index);
                        var cars = scene.tile(coords).features().stream()
                              .filter(f -> f.asset().startsWith("scenery/vehicles/car")).toList();
                        assertEquals(BoardSceneryTest.COUNTS.get(index).intValue(), cars.size());
                        terrain.setColumns(false);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        var installed = new ArrayList<ModelInstance>();
                        for (Object chunk : (List<?>) field(terrain, "chunks")) {
                            for (Object prop : (List<?>) field(chunk, "props")) {
                                var instance = (ModelInstance) field(prop, "instance");
                                if (coords.equals(field(prop, "coords")) && instance.nodes.first().id.equals("car")) {
                                    installed.add(instance);
                                }
                            }
                        }
                        assertEquals(cars.size(), installed.size());
                        for (int car = 0; car < cars.size(); car++) {
                            var actual = installed.get(car).transform.getTranslation(new Vector3());
                            var authored = cars.get(car);
                            assertEquals(BoardGeometry.centerX(coords) + authored.x() * BoardGeometry.hexScale(), actual.x, .001f);
                            assertEquals(BoardGeometry.centerY(coords) + authored.y() * BoardGeometry.hexScale(), actual.y, .001f);
                        }
                        for (int view = 0; view < 3; view++) {
                            boolean artwork = view == 0;
                            // The tileset art: the Classic graphics, a build of their own.
                            terrain.setColumns(artwork);
                            terrain.update(scene);
                            camera.setIsometric(view == 2);
                            camera.center(BoardGeometry.center(coords, 0));
                            camera.camera.zoom = .10f;
                            camera.update();
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "cars_" + BoardSceneryTest.VARIANTS.get(index)
                                  + (artwork ? "-art.png" : view == 2 ? "-oblique.png" : "-models.png")));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Parked-car alignment", failure.get()); }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
