/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch: GpuMaterialLodSmokeTest's averaging measurement (close 0.1 m vs far 0.8 m a pixel, direct path) under other
 * lights, to tell a lighting-geometry brightening from a material one.
 */
@Tag("on-demand")
class ZzAveragingProbeSmokeTest {
    private record Light(String name, float hour, float sky, float exposure) { }

    @Test
    void probes() {
        var lights = new ArrayList<Light>();
        for (String spec : System.getProperty("megamek.gpu.probeLights", "noon:13:1,morning:9:1,noonNoSky:13:0").split(",")) {
            String[] parts = spec.split(":");
            lights.add(new Light(parts[0], Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                  parts.length > 3 ? Float.parseFloat(parts[3]) : 0));
        }
        List<String> materials = List.of(System.getProperty("megamek.gpu.probeMaterials", "concrete,sand,grass,rock").split(","));
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> run(lights, materials)); }
                catch (Throwable error) { error.printStackTrace(System.out); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
    }

    private static void run(List<Light> lights, List<String> materials) {
        for (Light light : lights) {
            for (String material : materials) {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(light.hour(), 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, light.exposure()));
                frame.setSkyVisibility(light.sky());
                try {
                    var camera = new BoardCamera();
                    BoardScene scene = scene(material);
                    GpuDepthPrepassBaselineSmokeTest.prepare(terrain, frame, camera, scene);
                    terrain.setGrass(false);
                    terrain.setSmallObjects(false);
                    terrain.setMaterialCache(false);
                    camera.setIsometric(false);
                    double close = mean(terrain, frame, camera, scene, .1f, 1);
                    double far = mean(terrain, frame, camera, scene, .8f, 8);
                    double mid = mean(terrain, frame, camera, scene, .2f, 2);
                    System.out.printf(Locale.ROOT, "PROBE %s %s: close %.5f mid %.5f (%+.2f%%) far %.5f (%+.2f%%)%n",
                          light.name(), material, close, mid, 100 * (mid - close) / close, far, 100 * (far - close) / close);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                }
            }
        }
    }

    private static double mean(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          float footprint, int k) throws InterruptedException {
        camera.camera.zoom = footprint * BoardGeometry.width() / 30;
        camera.center(BoardGeometry.center(new Coords(3, 3), 0));
        GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
        frame.render(terrain, camera, scene);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var pixels = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            var data = pixels.getPixels();
            int w = width / k, h = height / k, x0 = (width - w) / 2, y0 = (height - h) / 2;
            double sum = 0;
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    int at = (y * width + x) * 4;
                    sum += .2126 * linear(data.get(at) & 255) + .7152 * linear(data.get(at + 1) & 255)
                          + .0722 * linear(data.get(at + 2) & 255);
                }
            }
            return sum / (w * h);
        } finally { pixels.dispose(); }
    }

    private static double linear(int code) {
        double value = code / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static BoardScene scene(String material) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.valueOf(material.toUpperCase(Locale.ROOT)), null, null, List.of(), List.of(),
                      BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
