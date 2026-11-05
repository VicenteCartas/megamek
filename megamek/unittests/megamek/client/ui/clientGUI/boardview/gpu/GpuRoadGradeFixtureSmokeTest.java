/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Overviews of the editable grade matrix, plus opt-in close views of named cases on either surface/rail panel. */
@Tag("on-demand")
class GpuRoadGradeFixtureSmokeTest {
    @Test
    void savedGradeFixturesRenderInBothGraphicsModes() throws Exception {
        var selected = selected("megamek.gpu.gradeCases");
        var surfaces = selected("megamek.gpu.gradeSurfaces");
        var panels = selected("megamek.gpu.gradePanels");
        var cases = RoadGradeFixture.cases();
        assertTrue(selected.stream().allMatch(id -> cases.stream().anyMatch(sample -> id.equals(sample.id()))),
              "Every gradeCases selector must name a fixture case");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "grade-fixture");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (boolean concrete : new boolean[] { false, true }) {
                        String surface = concrete ? "concrete" : "grassland";
                        if (!surfaces.isEmpty() && !surfaces.contains(surface)) { continue; }
                        var board = BoardFile.read(Path.of("data/boards/fixtures",
                              RoadGradeFixture.filename(concrete)));
                        var scene = BoardScenes.scene(board);
                        for (boolean classic : new boolean[] { false, true }) {
                            String mode = classic ? "classic" : "enhanced";
                            var terrain = new GpuTerrain();
                            try {
                                terrain.setColumns(classic);
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                var overview = BoardGeometry.center(new Coords(RoadGradeFixture.WIDTH / 2,
                                      RoadGradeFixture.HEIGHT / 2), 3);
                                camera.setIsometric(false);
                                camera.camera.zoom = 5.6f;
                                camera.center(overview);
                                frame.prepare(terrain, camera, scene);
                                // Start at the overview's detail instead of building every chunk at full detail first.
                                terrain.update(scene, camera.camera);
                                capture(terrain, frame, camera, scene, output,
                                      surface + "-" + mode + "-overview", overview, 5.6f, -1);
                                for (var panel : RoadGradeFixture.Panel.values()) {
                                    String panelName = panel.name().toLowerCase(java.util.Locale.ROOT);
                                    if (!panels.isEmpty() && !panels.contains(panelName)) { continue; }
                                    for (int index = 0; index < cases.size(); index++) {
                                        var sample = cases.get(index);
                                        if (!selected.contains(sample.id())) { continue; }
                                        Coords at = RoadGradeFixture.center(panel, index);
                                        var focus = BoardGeometry.center(at, scene.tile(at).elevation());
                                        for (int view : new int[] { -1, 45, 225 }) {
                                            capture(terrain, frame, camera, scene, output,
                                                  surface + "-" + panelName + "-" + sample.id() + "-" + mode,
                                                  focus, .35f, view);
                                        }
                                    }
                                }
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            } finally { terrain.dispose(); }
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road grade fixture", failure.get()); }
    }

    private static List<String> selected(String property) {
        return Arrays.stream(System.getProperty(property, "").split(","))
              .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static void capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          File output, String name, Vector3 focus, float zoom, int view) throws Exception {
        camera.setIsometric(view >= 0);
        if (view >= 0) { camera.orbit(view - camera.azimuth(), 0); }
        camera.camera.zoom = zoom;
        camera.center(focus);
        GpuReviewFrame.settle(terrain, null, scene, camera);
        terrain.animate(0, List.of());
        frame.render(terrain, camera, scene);
        GpuReviewFrame.save(new File(output, name + (view < 0 ? "-top" : "-side-" + view) + ".png"));
    }
}
