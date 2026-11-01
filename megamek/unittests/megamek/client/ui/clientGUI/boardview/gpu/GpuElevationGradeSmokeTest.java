/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Read back actual ground and boulder materials while lowering the same flat board. */
@Tag("on-demand")
class GpuElevationGradeSmokeTest {
    private static final Coords CENTER = new Coords(2, 2);

    @Test
    void negativeElevationsKeepBouldersInStepWithGround() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 640);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var original = BoardGeometry.tuning();
                var terrain = new GpuTerrain();
                try {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    terrain.setNormalMaps(false);
                    terrain.setGrass(false);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(false);
                    camera.camera.zoom = .13f;
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "elevation-grade");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    double[] baseline = null;
                    double[] shallow = null;
                    double previousGround = Double.POSITIVE_INFINITY;
                    for (int level : new int[] { 2, 0, -1, -2, -10, -30 }) {
                        BoardScene scene = scene(level);
                        terrain.update(scene);
                        camera.center(BoardGeometry.center(CENTER, level));
                        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                        ScreenUtils.clear(0, 0, 0, 1, true);
                        terrain.render(camera.camera);
                        GpuReviewFrame.save(new File(output, "level-" + level + ".png"));
                        double[] samples = samples(scene, camera);
                        assertTrue(samples[0] <= previousGround + 1, "Lower ground must not get lighter");
                        if (level == 0) { baseline = samples; }
                        if (level == -2) { shallow = samples; }
                        if (level < 0) {
                            assertTrue(samples[0] < baseline[0], "Below-zero ground still darkens");
                            assertTrue(samples[1] > baseline[1] * .6, "Boulders retain visible texture at level " + level);
                        }
                        if (level < -2) {
                            assertEquals(samples[0] / shallow[0], samples[1] / shallow[1], .05,
                                  "Ground and boulders must darken together at level " + level);
                        }
                        previousGround = samples[0];
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain elevation grading", failure.get()); }
    }

    private static double[] samples(BoardScene scene, BoardCamera camera) {
        var surface = new BoardSurface(scene, scene.tile(CENTER));
        var ground = BoardGeometry.center(CENTER, scene.tile(CENTER).elevation()).add(0, 24 * BoardGeometry.hexScale(), 0);
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(surface.rough, ground.x, ground.y, Float.NaN)),
              "The ground sample must not land on a boulder");
        var image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            double total = 0;
            int count = 0;
            for (var face : surface.rough) {
                var shade = surface.relief.shade(face.a());
                // Near-horizontal crowns use the same world-XY texture projection at every elevation.
                if (shade == null || shade.normal().z < .9f) { continue; }
                var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                float top = BoardSurface.sampleHeight(surface.faces, point.x, point.y, Float.NaN);
                if (Math.abs(top - point.z) > .01f) { continue; }
                total += luminance(image, camera, point);
                count++;
            }
            assertTrue(count > 0, "Sample visible boulder crowns");
            return new double[] { luminance(image, camera, ground), total / count };
        } finally {
            image.dispose();
        }
    }

    private static double luminance(Pixmap image, BoardCamera camera, Vector3 point) {
        var pixel = camera.camera.project(point.cpy());
        int x = (int) (pixel.x * image.getWidth() / Gdx.graphics.getWidth());
        int y = (int) (pixel.y * image.getHeight() / Gdx.graphics.getHeight());
        double total = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int rgba = image.getPixel(x + dx, y + dy);
                total += .299 * (rgba >>> 24) + .587 * (rgba >>> 16 & 255) + .114 * (rgba >>> 8 & 255);
            }
        }
        return total / 9;
    }

    private static BoardScene scene(int level) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                var coords = new Coords(x, y);
                var hex = new Hex(level);
                if (coords.equals(CENTER)) { hex.addTerrain(new Terrain(Terrains.ROUGH, 1)); }
                // Dirt has no sublevel floor (GpuTerrain.FLOORS): only the grade changes with the level.
                var base = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.DIRT, level, -1, 0);
                tiles.add(new BoardScene.Tile(coords, level, -1, false, 0, base.surface(), null, null,
                      BoardFeatures.capture(hex, coords, Map.of()), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
