/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/**
 * The Tuning panel's Tessellation switch (GpuTerrain#setTessellation) at runtime, on Grassland 2's grassy banks: switched
 * on, the relief draws rounded; switched off again, the frame is the frame drawn before, bit for bit. Captures
 * tessellation-{off,on}.png. Fire and Ice 2 checks road and board-cut contacts; Low Bridges checks deck and shore contacts.
 * Skipped where the context has no tessellation stages (before OpenGL 4.0).
 */
@Tag("on-demand")
class GpuTessellationSmokeTest {
    @Test
    void lowBridgeContacts() throws Exception {
        BoardScene scene = GpuCalibrationScenes.scene(
              new File("data/boards/AGoAC Maps/16x17 Grassland 2 (Low Bridges).board"));
        AtomicBoolean available = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
                var camera = new BoardCamera();
                try {
                    available.set(GpuTessellation.available());
                    if (!available.get()) { return; }
                    GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                    camera.setIsometric(false);
                    camera.orbit(67.18f - camera.azimuth(), 58.35f - camera.tilt());
                    camera.camera.zoom = 112.95f / camera.camera.viewportHeight;
                    camera.center(new Vector3(435.62f, -570.18f, -1f));
                    byte[] reference = null;
                    int background = 0;
                    for (boolean enabled : new boolean[] { false, true }) {
                        terrain.setTessellation(enabled);
                        terrain.update(scene);
                        byte[] pixels = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                              new File(GpuCalibrationScenes.OUTPUT,
                                    "tessellation-low-bridge" + (enabled ? "-on.png" : "-off.png")));
                        if (!enabled) {
                            reference = pixels;
                            background = backgroundPixels(frame);
                        }
                        else {
                            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                            int bank = exposedBank(reference, pixels, width, height);
                            int after = backgroundPixels(frame);
                            System.out.printf("Low bridge: exposed bank %d; background pixels %d -> %d%n", bank, background, after);
                            assertTrue(bank <= 2, "Tessellated bank must stay beneath the bridge deck: " + bank);
                            assertTrue(after <= background + 2, "Tessellated terrain must stay closed: " + after);
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Low bridge tessellation contacts", failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
    }

    /** Brown soil newly piercing the grey deck at the reported bank contact. */
    private static int exposedBank(byte[] before, byte[] after, int width, int height) {
        int count = 0;
        for (int y = height * 54 / 100; y < height * 62 / 100; y++) {
            for (int x = width * 45 / 100; x < width * 55 / 100; x++) {
                int at = ((height - 1 - y) * width + x) * 4;
                int r = Byte.toUnsignedInt(after[at]), g = Byte.toUnsignedInt(after[at + 1]), b = Byte.toUnsignedInt(after[at + 2]);
                int sr = Byte.toUnsignedInt(before[at]), sg = Byte.toUnsignedInt(before[at + 1]), sb = Byte.toUnsignedInt(before[at + 2]);
                if (r > b + 15 && g > b + 5
                      && Math.max(sr, Math.max(sg, sb)) - Math.min(sr, Math.min(sg, sb)) < 10) { count++; }
            }
        }
        return count;
    }

    /** This close view is filled by terrain; untouched scene depth reveals a gap even beneath transparent water. */
    private static int backgroundPixels(GpuReviewFrame frame) {
        var texture = frame.depthTexture();
        var depth = BufferUtils.newFloatBuffer(texture.getWidth() * texture.getHeight());
        int binding = GL11.glGetInteger(GL20.GL_TEXTURE_BINDING_2D);
        texture.bind();
        try {
            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        } finally {
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, binding);
        }
        int count = 0;
        for (int at = 0; at < depth.capacity(); at++) { if (depth.get(at) >= .99999f) { count++; } }
        return count;
    }

    @Test
    void fireAndIceContacts() throws Exception {
        BoardScene scene = GpuCalibrationScenes.scene(
              new File("data/boards/unofficial/Drewbacca/16x17 Fire And Ice 2.board"));
        AtomicBoolean available = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
                var camera = new BoardCamera();
                try {
                    available.set(GpuTessellation.available());
                    if (!available.get()) { return; }
                    GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                    byte[] roadReference = null;
                    for (boolean enabled : new boolean[] { false, true }) {
                        terrain.setTessellation(enabled);
                        terrain.update(scene);
                        for (float[] view : new float[][] {
                              { 122.03f, 64.16f, 132.80f, 976.76f, -642.50f, 108 },
                              { 244.34f, 29.48f, 112.95f, 61.07f, -496.80f, 7.01f } }) {
                            camera.setIsometric(false);
                            camera.orbit(view[0] - camera.azimuth(), view[1] - camera.tilt());
                            camera.camera.zoom = view[2] / camera.camera.viewportHeight;
                            camera.center(new Vector3(view[3], view[4], view[5]));
                            byte[] pixels = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                                  new File(GpuCalibrationScenes.OUTPUT,
                                        "tessellation-fire-ice-" + (view[3] > 500 ? "cliff" : "road")
                                              + (enabled ? "-on.png" : "-off.png")));
                            if (view[3] < 500) {
                                if (!enabled) { roadReference = pixels; }
                                else {
                                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                                    int before = cornerSky(roadReference, width, height), after = cornerSky(pixels, width, height);
                                    int grass = exposedGrass(roadReference, pixels, width, height);
                                    System.out.printf("Fire and Ice road: corner sky pixels %d -> %d; exposed grass %d%n",
                                          before, after, grass);
                                    assertTrue(after <= before + 2, "The board cut must stay closed with tessellation: " + after);
                                    assertTrue(grass <= 16, "Tessellated ground must not pierce the asphalt: " + grass);
                                }
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Fire and Ice tessellation contacts", failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
    }

    /** Sky seen through the reported corner beside the road, away from the board's outer silhouette. */
    private static int cornerSky(byte[] pixels, int width, int height) {
        int count = 0;
        for (int y = height * 61 / 100; y < height * 79 / 100; y++) {
            for (int x = width * 61 / 100; x < width * 72 / 100; x++) {
                int at = ((height - 1 - y) * width + x) * 4;
                int r = Byte.toUnsignedInt(pixels[at]), g = Byte.toUnsignedInt(pixels[at + 1]), b = Byte.toUnsignedInt(pixels[at + 2]);
                if (b > r + 30 && g > r + 15) { count++; }
            }
        }
        return count;
    }

    /** Green terrain newly showing well inside the asphalt of the upper bend in the untessellated reference. */
    private static int exposedGrass(byte[] before, byte[] after, int width, int height) {
        int count = 0;
        for (int y = height / 10; y < height * 45 / 100; y++) {
            for (int x = width / 5; x < width * 55 / 100; x++) {
                int at = ((height - 1 - y) * width + x) * 4;
                int r = Byte.toUnsignedInt(after[at]), g = Byte.toUnsignedInt(after[at + 1]), b = Byte.toUnsignedInt(after[at + 2]);
                if (g <= r + 8 || g <= b + 10) { continue; }
                boolean asphalt = true;
                for (int dy = -4; dy <= 4 && asphalt; dy++) {
                    for (int dx = -4; dx <= 4 && asphalt; dx++) {
                        int sample = at + (dy * width + dx) * 4;
                        int sr = Byte.toUnsignedInt(before[sample]), sg = Byte.toUnsignedInt(before[sample + 1]),
                              sb = Byte.toUnsignedInt(before[sample + 2]);
                        asphalt = Math.max(sr, Math.max(sg, sb)) - Math.min(sr, Math.min(sg, sb)) < 14;
                    }
                }
                if (asphalt) { count++; }
            }
        }
        return count;
    }

    @Test
    void switchesOnAndBackAtRuntime() throws Exception {
        BoardScene scene = GpuCalibrationScenes.scene(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        AtomicBoolean available = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
                var camera = new BoardCamera();
                try {
                    available.set(GpuTessellation.available());
                    if (!available.get()) { return; }
                    GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
                    // Close enough for full detail, the only chunks that tessellate.
                    camera.setIsometric(false);
                    camera.orbit(15, 55);
                    camera.camera.zoom = .09f;
                    camera.center(BoardGeometry.center(new Coords(12, 8), scene.tile(new Coords(12, 8)).elevation()));
                    byte[] off = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                          new File(GpuCalibrationScenes.OUTPUT, "tessellation-off.png"));
                    // As the battle view does each frame: the switch, then the update that rebuilds for it.
                    terrain.setTessellation(true);
                    terrain.update(scene);
                    byte[] on = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                          new File(GpuCalibrationScenes.OUTPUT, "tessellation-on.png"));
                    terrain.setTessellation(false);
                    terrain.update(scene);
                    byte[] again = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene, null);
                    int changed = 0;
                    for (int i = 0; i < off.length; i += 4) {
                        if (Math.abs(off[i] - on[i]) + Math.abs(off[i + 1] - on[i + 1]) + Math.abs(off[i + 2] - on[i + 2]) > 6) {
                            changed++;
                        }
                    }
                    assertTrue(changed > off.length / 4 / 200, "Tessellated banks must draw rounded: " + changed + " pixels changed");
                    assertArrayEquals(off, again, "Switched off again, the frame must be the frame before it was on");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tessellation switch", failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
    }
}
