/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real map ramps, including curved grades beside high cliffs and a junction meeting a low bridge. */
@Tag("on-demand")
class GpuRoadRampSourceSmokeTest {
    private record Source(String name, String board, List<Coords> focus, float zoom) { }

    private static final List<Source> SOURCES = List.of(
          new Source("airport-close", "unofficial/Derv_Maps/25x25 Airport 3000.board",
                List.of(new Coords(14, 12), new Coords(14, 11)), .11f),
          new Source("airport-junction", "unofficial/Derv_Maps/25x25 Airport 3000.board",
                List.of(new Coords(14, 11), new Coords(14, 12), new Coords(14, 13),
                      new Coords(15, 11), new Coords(15, 12)), .20f),
          new Source("plaine", "unofficial/Cartes/16x17 Plaine 1.board",
                List.of(new Coords(9, 11), new Coords(9, 10), new Coords(8, 12), new Coords(10, 12)), .19f),
          new Source("city-hills", "Map Set 3/16x17 City Hills Residential 1.board",
                List.of(new Coords(5, 7), new Coords(6, 7), new Coords(7, 6)), .17f),
          new Source("city-hills-signals", "Map Set 3/16x17 City Hills Residential 1.board",
                List.of(new Coords(9, 5), new Coords(10, 5), new Coords(10, 6), new Coords(11, 5)), .15f),
          new Source("city-hills-flat-toe", "Map Set 3/16x17 City Hills Residential 1.board",
                List.of(new Coords(13, 5), new Coords(13, 4), new Coords(14, 5), new Coords(12, 5)), .18f),
          new Source("city-hills-2-junction", "Map Set 3/16x17 City Hills Residential 2.board",
                List.of(new Coords(12, 11), new Coords(13, 11), new Coords(12, 12), new Coords(13, 12)), .18f),
          new Source("city-hills-2-bridge", "Map Set 3/16x17 City Hills Residential 2.board",
                List.of(new Coords(7, 9), new Coords(6, 9), new Coords(6, 10)), .18f),
          new Source("city-hills-2-low-bridge", "Map Set 3/16x17 City Hills Residential 2.board",
                List.of(new Coords(7, 1), new Coords(8, 1), new Coords(6, 2)), .13f),
          new Source("city-hills-structures-barrier",
                "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board",
                List.of(new Coords(8, 1), new Coords(7, 1)), .11f),
          new Source("city-hills-structures-corner",
                "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board",
                List.of(new Coords(11, 13), new Coords(12, 14), new Coords(11, 14)), .16f),
          new Source("city-hills-structures-junction",
                "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board",
                List.of(new Coords(7, 13), new Coords(8, 13), new Coords(8, 14)), .16f),
          new Source("city-hills-structures-double-incline",
                "unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board",
                List.of(new Coords(2, 5), new Coords(1, 5), new Coords(2, 6)), .16f),
          new Source("mesa-city-climb", "unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board",
                List.of(new Coords(26, 21), new Coords(27, 20), new Coords(27, 19),
                      new Coords(28, 20), new Coords(26, 20)), .23f),
          new Source("fire-and-ice", "unofficial/Drewbacca/16x17 Fire And Ice 2.board",
                List.of(new Coords(0, 7), new Coords(1, 6), new Coords(2, 7)), .18f),
          new Source("military-base-slope", "buildingsnobasement/militarybase2.board",
                List.of(new Coords(9, 12), new Coords(8, 13), new Coords(9, 13)), .18f),
          new Source("military-base-flat", "buildingsnobasement/militarybase2.board",
                List.of(new Coords(6, 16), new Coords(7, 16), new Coords(9, 15), new Coords(8, 16)), .21f),
          new Source("park-district", "Map Pack City/City Districts/16x17 Park District.board",
                List.of(new Coords(13, 7), new Coords(14, 6), new Coords(15, 6), new Coords(14, 7)), .20f),
          new Source("observatory-junction", "Map Pack City/City Districts/16x17 Observatory Hill.board",
                List.of(new Coords(7, 11), new Coords(7, 12), new Coords(7, 13), new Coords(8, 12),
                      new Coords(8, 13)), .19f),
          new Source("large-mountain-road", "Map Set 5/16x17 Large Mountain 2.board",
                List.of(new Coords(7, 2), new Coords(8, 3), new Coords(9, 3), new Coords(10, 4),
                      new Coords(11, 4)), .28f),
          new Source("mines", "Deserts/16x17 Mines 1.board",
                List.of(new Coords(3, 6), new Coords(3, 7), new Coords(4, 6), new Coords(4, 7),
                      new Coords(4, 8)), .18f));

    @Test
    void shippedRoadRampsKeepTheirContoursInBothGraphicsModes() throws Exception {
        Map<Source, BoardScene> scenes = new LinkedHashMap<>();
        var selected = Arrays.stream(System.getProperty("megamek.gpu.rampSources", "").split(","))
              .map(String::trim).filter(name -> !name.isEmpty()).toList();
        for (var source : SOURCES) {
            if (!selected.isEmpty() && !selected.contains(source.name())) { continue; }
            var scene = BoardScenes.installedScene(source.board());
            for (var at : source.focus()) {
                var tile = scene.tile(at);
                assertNotNull(tile, source.name() + " " + at.getBoardNum());
            }
            assertTrue(source.focus().stream().map(scene::tile)
                  .anyMatch(tile -> BoardRoad.rendered(tile) || BoardBridge.feature(tile) != null),
                  source.name() + " must retain its native route");
            scenes.put(source, scene);
        }
        assertTrue(!scenes.isEmpty(), "The source filter must select at least one map view");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "source-ramps");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(weather);
                try {
                    for (var entry : scenes.entrySet()) {
                        var source = entry.getKey();
                        var scene = entry.getValue();
                        var focus = new Vector3();
                        for (var at : source.focus()) {
                            focus.add(BoardGeometry.center(at, scene.tile(at).elevation()));
                        }
                        focus.scl(1f / source.focus().size());
                        for (boolean classic : new boolean[] { false, true }) {
                            var terrain = new GpuTerrain();
                            try {
                                terrain.setColumns(classic);
                                // Keep the installed carriers, roads and bridges while exposing their sides
                                // through neighbouring buildings and vegetation from the original map.
                                terrain.setFluffHidden(true);
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setIsometric(true);
                                camera.camera.zoom = source.zoom();
                                camera.center(focus);
                                frame.prepare(terrain, camera, scene);
                                terrain.update(scene);
                                terrain.animate(0, List.of());
                                for (int view : new int[] { -1, 45, 225 }) {
                                    camera.setIsometric(view >= 0);
                                    if (view >= 0) { camera.orbit(view - camera.azimuth(), 0); }
                                    camera.camera.zoom = source.zoom();
                                    camera.center(focus);
                                    GpuReviewFrame.settle(terrain, null, scene, camera);
                                    assertEquals(classic, terrain.columns());
                                    frame.render(terrain, camera, scene);
                                    String mode = classic ? "classic" : "enhanced";
                                    String angle = view < 0 ? "top" : "side-" + view;
                                    GpuReviewFrame.save(new File(output, source.name() + "-" + mode + "-" + angle + ".png"));
                                }
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            } finally { terrain.dispose(); }
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Shipped road ramp contours", failure.get()); }
    }
}
