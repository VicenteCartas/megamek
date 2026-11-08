/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Show lighting sky" replaces only the backdrop: the board's pixels stay, the sky shows the lighting's sky or image. */
@Tag("on-demand")
class GpuLightingSkySmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "lighting-sky");
    @TempDir Path directory;

    @Test
    void lightingSkyChangesOnlyTheBackdrop() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        var panorama = GpuLightingEnvironment.load(GpuLightingControlsTest.constantPng(directory.resolve("red.png"),
              0xC02020));
        BoardScene scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 640);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.fit(scene);
                    camera.zoom(1.6f);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    var atmosphere = frame.atmosphere();
                    // The transparent export's coverage separates the board from the sky.
                    atmosphere.setTransparentBackground(true);
                    byte[] coverage = capture(terrain, frame, camera, scene, "coverage");
                    atmosphere.setTransparentBackground(false);
                    atmosphere.setOptions(options(false, null));
                    byte[] generated = capture(terrain, frame, camera, scene, "off");
                    atmosphere.setOptions(options(true, null));
                    byte[] lighting = capture(terrain, frame, camera, scene, "on");
                    atmosphere.setOptions(options(true, panorama));
                    byte[] image = capture(terrain, frame, camera, scene, "panorama");
                    int board = 0, sky = 0, changedSky = 0, boardDifference = 0;
                    long red = 0, green = 0, blue = 0;
                    for (int i = 0; i < coverage.length; i += 4) {
                        int alpha = coverage[i + 3] & 255;
                        if (alpha == 255) {
                            board++;
                            for (int c = 0; c < 3; c++) {
                                boardDifference = Math.max(boardDifference,
                                      Math.abs((generated[i + c] & 255) - (lighting[i + c] & 255)));
                            }
                        } else if (alpha == 0) {
                            sky++;
                            int difference = 0;
                            for (int c = 0; c < 3; c++) {
                                difference = Math.max(difference, Math.abs((generated[i + c] & 255) - (lighting[i + c] & 255)));
                            }
                            if (difference > 2) { changedSky++; }
                            red += image[i] & 255;
                            green += image[i + 1] & 255;
                            blue += image[i + 2] & 255;
                        }
                    }
                    assertTrue(board > coverage.length / 40 && sky > coverage.length / 40, "The view must show board and sky");
                    assertTrue(boardDifference <= 1, "The board's pixels stay within 1 LSB: " + boardDifference);
                    assertTrue(changedSky > sky / 2, "The sky shows the lighting's sky: " + changedSky + " of " + sky);
                    assertTrue(red > green + 20L * sky && red > blue + 20L * sky,
                          "The sky shows the red panorama: " + red / sky + ", " + green / sky + ", " + blue / sky);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    if (Boolean.getBoolean("megamek.gpu.skyReview")) {
                        for (var taint : List.of(AtmosphericTaint.TOXIC_CAUSTIC, AtmosphericTaint.TOXIC_POISON,
                              AtmosphericTaint.TOXIC_FLAME)) {
                            for (int hour : new int[] { 0, 6, 18 }) {
                                frame.configure(new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0,
                                      BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, true, taint));
                                atmosphere.setOptions(options(false, null));
                                capture(terrain, frame, camera, scene, taint.name() + "-" + hour + "-backdrop");
                                atmosphere.setOptions(options(true, null));
                                capture(terrain, frame, camera, scene, taint.name() + "-" + hour + "-lighting");
                            }
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Lighting sky backdrop", failure.get()); }
    }

    /** The review frame's own options with the lighting sky and image chosen, at twice the sky contribution. */
    private static GpuAtmosphere.Options options(boolean sky, GpuLightingEnvironment image) {
        var base = new GpuAtmosphere.Options(GpuAtmosphere.Options.DEFAULTS.rays(), false, GpuClouds.MIN_SHADOW_STRENGTH,
              GpuClouds.MAX_SHADOW_STRENGTH, 0);
        return new GpuAtmosphere.Options(base.rays(), base.fixedSun(), base.minCloudShadow(), base.maxCloudShadow(),
              base.sunGlare(), base.fogHeightVariation(), base.fogDensityVariation(), base.moonShadowContrast(),
              base.taintStrength(), base.fogCalmDrift(), base.whiteBalance(), base.tint(), base.saturation(), base.contrast(),
              base.shadowStrength(), base.ambientShare(), 2, image, sky);
    }

    /** One frame, saved for review, as RGBA bytes. */
    private static byte[] capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name) {
        frame.render(terrain, camera, scene);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            ByteBuffer pixels = image.getPixels();
            byte[] result = new byte[pixels.remaining()];
            pixels.get(result);
            GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
            return result;
        } finally {
            image.dispose();
        }
    }
}
