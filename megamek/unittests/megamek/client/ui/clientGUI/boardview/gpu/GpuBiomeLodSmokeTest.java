/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Zoom through both sides of every vegetation handoff using the real shaders and instance batches. */
@Tag("on-demand")
class GpuBiomeLodSmokeTest {
    @ParameterizedTest
    @EnumSource(value = BoardScene.Biome.class, names = { "FIELD", "MARSH" })
    void zoomingDoesNotSwitchWholeFieldsAtLodBoundaries(BoardScene.Biome kind) throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "plantation-lod-" + kind);
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.94f, true);
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    camera.setIsometric(true);
                    var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                          c.getX() >= 3 && c.getX() <= 5 && c.getY() >= 3 && c.getY() <= 5
                                ? kind : BoardScene.Biome.MUD, 0));
                    var focus = BoardGeometry.center(new Coords(4, 4), 0);
                    camera.camera.zoom = .2f;
                    camera.center(focus);
                    terrain.update(scene, camera.camera);
                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                    var plants = (GpuBiomeVegetation) field(terrain, "biomeVegetation");
                    frame.render(terrain, camera, scene);
                    unchangedTerrainKeepsItsPlants(terrain, plants, camera, scene);
                    var metrics = new StringBuilder("Perspective, boundary, direction, changed pixels, field area fraction\n");
                    boolean continuous = true;
                    for (boolean perspective : new boolean[] { false, true }) {
                        camera.setPerspective(perspective);
                        // The old hysteresis thresholds, plus the ends of the new cross-fade intervals.
                        for (float boundary : new float[] { 12, 18, 36, 64, 86.4f, 105.6f, 128, 240, 288, 352, 480 }) {
                            for (int direction : new int[] { -1, 1 }) {
                                camera.camera.zoom = BoardGeometry.width() / (direction < 0 ? 600 : 24);
                                camera.center(focus);
                                frame.render(terrain, camera, scene);
                                camera.camera.zoom = BoardGeometry.width() / (boundary - direction * .001f);
                                camera.center(focus);
                                frame.render(terrain, camera, scene);
                                long uploads = plants.uploads();
                                String name = (perspective ? "perspective" : "ortho") + "-" + boundary + "-" + direction;
                                var before = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
                                try {
                                    GpuReviewFrame.save(new File(output, name + "-before.png"));
                                    camera.camera.zoom = BoardGeometry.width() / (boundary + direction * .001f);
                                    camera.center(focus);
                                    frame.render(terrain, camera, scene);
                                    if (boundary == 288) {
                                        assertEquals(uploads, plants.uploads(), "Zoom within a fade must reuse instance buffers");
                                    }
                                    int changed = changed(before);
                                    GpuReviewFrame.save(new File(output, name + "-after.png"));
                                    double area = Math.min(1280.0 * 960, 9 * boundary * boundary);
                                    metrics.append(perspective).append(", ").append(boundary).append(", ")
                                          .append(direction).append(", ").append(changed).append(", ")
                                          .append(changed / area).append('\n');
                                    // Allow a few raster edges, but never a whole field's density/silhouette change.
                                    continuous &= changed <= 8 + .002 * area;
                                } finally { before.dispose(); }
                            }
                        }
                        // Native captures for visual review throughout each transition, including distant removal.
                        for (int step = 0; step <= 60; step++) {
                            float pixels = (float) (600 * Math.pow(10.0 / 600, step / 60.0));
                            camera.camera.zoom = BoardGeometry.width() / pixels;
                            camera.center(focus);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, (perspective ? "perspective" : "ortho")
                                  + "-zoom-" + String.format("%02d", step) + ".png"));
                        }
                    }
                    Files.writeString(new File(output, "continuity.csv").toPath(), metrics.toString());
                    assertTrue(continuous, "An imperceptible zoom step must not replace the field: " + metrics);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    TerrainLod.tune(originalLod);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Plantation zoom continuity", failure.get()); }
    }

    private static void unchangedTerrainKeepsItsPlants(GpuTerrain terrain, GpuBiomeVegetation plants,
          BoardCamera camera, BoardScene scene) throws Exception {
        // A chunk prepared again replants equal rows on equal ground: the buffers must not upload or change.
        var replanted = new HashMap<Coords, BoardPlants>();
        for (var tile : scene.tiles()) {
            var planted = terrain.planted(tile.coords());
            if (planted != null) {
                replanted.put(tile.coords(), BoardPlants.plant(scene, tile, terrain.tacticalSurface(tile.coords()), TerrainLod.FULL,
                      java.util.List.of()));
            }
        }
        plants.visible(scene, camera.camera, scene.tiles(), terrain::planted);
        var batches = GpuBiomeSmokeTest.batches(plants);
        float[][] roots = new float[batches.size()][];
        for (int i = 0; i < batches.size(); i++) { roots[i] = ((FloatArray) field(batches.get(i), "data")).toArray(); }
        long uploads = plants.uploads();
        plants.visible(scene, camera.camera, scene.tiles(), replanted::get);
        assertEquals(uploads, plants.uploads(), "Equal replanted rows must not upload again");
        assertEquals(batches, GpuBiomeSmokeTest.batches(plants), "A chunk handoff must keep the chunk batches");
        for (int i = 0; i < batches.size(); i++) {
            assertArrayEquals(roots[i], ((FloatArray) field(batches.get(i), "data")).toArray(),
                  "A chunk handoff must not clear or partially repopulate the visible field");
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }

    private static int changed(Pixmap before) {
        var after = Pixmap.createFromFrameBuffer(0, 0, before.getWidth(), before.getHeight());
        try {
            int changed = 0;
            for (int y = 0; y < before.getHeight(); y++) {
                for (int x = 0; x < before.getWidth(); x++) {
                    int a = before.getPixel(x, y), b = after.getPixel(x, y);
                    int difference = Math.abs((a >>> 24) - (b >>> 24))
                          + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255))
                          + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
                    if (difference > 36) { changed++; }
                }
            }
            return changed;
        } finally { after.dispose(); }
    }
}
