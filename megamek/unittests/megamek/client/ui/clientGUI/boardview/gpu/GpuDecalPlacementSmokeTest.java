/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Inspect the actual uploaded overlays and render both presentations over the same solid structure meshes. */
@Tag("on-demand")
class GpuDecalPlacementSmokeTest {
    private static final Coords CENTER = new Coords(2, 2);
    private static final List<GpuSceneryPlacementSmokeTest.Sample> SAMPLES = List.of(
          sample("roof-decal", 2, "pavement:1", "building:2;bldg_elev:3;bldg_cf:90", "fluff:14:0", false),
          sample("roof-fluff", 2, "pavement:1", "building:2;bldg_elev:3;bldg_cf:90", "fluff:6:0", false),
          sample("submerged-decal", -2, "water:2", "", "fluff:14:0", true),
          sample("submerged-fluff", -2, "water:2", "", "geyser:1", true),
          sample("wet-roof-decal", 1, "water:2", "building:2;bldg_elev:4;bldg_cf:90", "fluff:14:0", false),
          sample("bridge-decal", 0, "water:2", "bridge:1:9;bridge_elev:2;bridge_cf:90", "fluff:14:0", false),
          sample("bridge-fluff", 0, "water:2", "bridge:1:9;bridge_elev:2;bridge_cf:90", "fluff:6:0", false),
          sample("tank-decal", 0, "pavement:1", "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100", "fluff:14:0", false),
          sample("industrial-decal", 1, "pavement:1", "heavy_industrial:2", "fluff:14:0", false),
          sample("ice-decal", -1, "water:2;ice:1", "", "fluff:14:0", false),
          sample("raised-decal", 3, "pavement:1", "", "fluff:14:0", false));

    private static GpuSceneryPlacementSmokeTest.Sample sample(String name, int elevation, String ground,
          String structure, String fluff, boolean bed) {
        return new GpuSceneryPlacementSmokeTest.Sample(name, elevation, ground, structure, fluff, bed);
    }

    @Test
    void bothViewsPlaceArtworkOnTheRoofDeckIceOrLakebedAndKeepItAcrossViewChanges() throws Exception {
        var controls = new ArrayList<BoardScene>();
        var decorated = new ArrayList<BoardScene>();
        for (var sample : SAMPLES) {
            controls.add(GpuSceneryPlacementSmokeTest.capture(sample, false));
            decorated.add(GpuSceneryPlacementSmokeTest.capture(sample, true));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "decal-placement");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int i = 0; i < SAMPLES.size(); i++) {
                        var sample = SAMPLES.get(i);
                        BoardScene control = controls.get(i), scene = decorated.get(i);
                        Vector3 center = BoardGeometry.center(CENTER, 0);
                        var ray = new Ray(new Vector3(center.x, center.y, 1000), new Vector3(0, 0, -1));
                        frame.prepare(terrain, camera, control);
                        terrain.update(control);
                        var hit = terrain.selectionHit(control, ray);
                        assertNotNull(hit, sample.name());
                        float support = sample.lakebed() ? BoardGeometry.groundZ(control.tile(CENTER))
                              : 1000 - (float) Math.sqrt(hit.distance());
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        for (boolean tactical : new boolean[] { false, true, false }) {
                            // The tileset decals are the Classic graphics': a build of their own.
                            terrain.setColumns(tactical);
                            terrain.update(scene);
                            float expected = support;
                            // Paint and terrain-owned sprites are overlays; art the conversion turned into placed
                            // objects (the skylights) draws in 3D in both views (GpuSceneryPlacementSmokeTest).
                            var tile = scene.tile(CENTER);
                            if (tactical ? tile.tilesetDecals() != null || tile.tilesetScenery() != null : tile.decals() != null) {
                                assertEquals(expected + BoardDecals.LIFT * BoardGeometry.hexScale(),
                                      overlayHeight(terrain, tactical, ray), .003f, sample.name() + " tactical=" + tactical);
                            }
                            if (sample.name().equals("roof-decal")) {
                                assertTrue(overlayTriangles(terrain, tactical).stream()
                                      .allMatch(point -> point.z > BoardGeometry.surfaceZ(scene.tile(CENTER)) + BoardGeometry.level()),
                                      "No part of the roof text may fall back onto the ground, tactical=" + tactical);
                            }
                            camera.setIsometric(true);
                            camera.camera.zoom = .25f;
                            camera.center(new Vector3(center.x, center.y, expected));
                            terrain.animate(.5f, List.of());
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, sample.name() + (tactical ? "-tactical.png" : "-3d.png")));
                            if (sample.lakebed()) {
                                frame.render(terrain, camera, scene, false);
                                GpuReviewFrame.save(new File(output, sample.name() + (tactical ? "-tactical-bed.png" : "-3d-bed.png")));
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Decal solid support in both views", failure.get()); }
    }

    private static float overlayHeight(GpuTerrain terrain, boolean tactical, Ray ray) throws Exception {
        Vector3 hit = new Vector3();
        assertTrue(Intersector.intersectRayTriangles(ray, overlayTriangles(terrain, tactical), hit),
              "The uploaded overlay must cover its anchor");
        return hit.z;
    }

    private static List<Vector3> overlayTriangles(GpuTerrain terrain, boolean tactical) throws Exception {
        List<Vector3> triangles = new ArrayList<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object object : (List<?>) field(chunk, tactical ? "tilesetDecals" : "surfaceDecals")) {
                var instance = (ModelInstance) object;
                triangles.addAll(GpuTerrain.triangles(instance.model));
            }
        }
        return triangles;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
