/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The reported river mouths and depth-zero shelves, under light that exposes changes in surface normals. */
@Tag("on-demand")
class GpuWaterBasinSmokeTest {
    @Test
    void capturesDeltaMouthsAndShallowsAcrossWindAndView() throws Exception {
        capture("16x17 River Delta-Drainage Basin 1 (Savannah).board", new Coords(4, 10), .48f, "basin");
    }

    @Test
    void capturesMountainLakeMouthAndShallowRiverAcrossWindAndView() throws Exception {
        capture("16x17 Mountain Lake (Savannah).board", new Coords(6, 10), .62f, "mountain");
    }

    @Test
    void orthographicSunGlareKeepsWaterReadableWithoutChangingPerspective() throws Exception {
        var scene = GpuCalibrationScenes.scene(new File(
              "data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var shaders = new GpuShaderManager();
                try { shaders.run(() -> {
                    try { checkGlare(scene, shaders); }
                    catch (Throwable error) { failure.set(error); }
                }); }
                catch (Throwable error) { failure.set(error); }
                finally { shaders.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water sun glare", failure.get()); }
    }

    private static void checkGlare(BoardScene scene, GpuShaderManager shaders) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(12.3f, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, BoardAtmosphere.Effects.NONE));
        String source = GpuShaderSource.readDisk("water-lighting.glsl");
        String selector = "if (u_perspective < .5) {";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(selector), -1).length - 1);
        String uncapped = source.replace(selector, "if (false) {");
        assertEquals(1, source.split(java.util.regex.Pattern.quote("return reflected;"), -1).length - 1);
        String noGlare = source.replace("return reflected;", "return vec3(0.0);");
        try {
            var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "glare-1218");
            assertTrue(output.isDirectory() || output.mkdirs());
            var camera = new BoardCamera();
            camera.resize(1440, 1000);
            camera.setIsometric(true);
            camera.fit(scene);
            camera.camera.zoom *= .62f;
            camera.center(BoardGeometry.center(new Coords(6, 10), 0));
            frame.prepare(terrain, camera, scene);
            terrain.update(scene, camera.camera);
            GpuReviewFrame.settle(terrain, null, scene, camera);
            for (int i = 0; i < 300; i++) { terrain.animate(1 / 30f, List.of()); }
            for (boolean effects : new boolean[] { true, false }) {
                terrain.setWaterEffects(effects);
                for (String view : List.of("mirror", "away", "perspective")) {
                    camera.setPerspective(view.equals("perspective"));
                    var sun = frame.lighting().direction();
                    float bearing = (float) Math.toDegrees(Math.atan2(sun.x, -sun.y));
                    float tilt = (float) Math.toDegrees(Math.atan2(Math.hypot(sun.x, sun.y), -sun.z));
                    if (view.equals("away")) { tilt += 30; }
                    camera.orbit(bearing - camera.azimuth(), tilt - camera.tilt());
                    camera.update();
                    frame.prepare(terrain, camera, scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    assertTrue(shaders.apply(Map.of("water-lighting.glsl", uncapped)).success());
                    for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                    byte[] before = ScreenUtils.getFrameBufferPixels(false);
                    String label = view + (effects ? "-animated" : "-still");
                    GpuReviewFrame.save(new File(output, label + "-before.png"));
                    assertTrue(shaders.apply(Map.of("water-lighting.glsl", source)).success());
                    frame.render(terrain, camera, scene);
                    byte[] after = ScreenUtils.getFrameBufferPixels(false);
                    GpuReviewFrame.save(new File(output, label + "-after.png"));
                    System.out.printf("Water glare %s white pixels: %d -> %d%n", label, whitePixels(before), whitePixels(after));
                    if (view.equals("mirror")) {
                        assertTrue(whitePixels(before) > 1000, "The uncapped fixture must reproduce the reported whiteout");
                        assertTrue(whitePixels(after) < whitePixels(before) / 10,
                              "The mirror-angle lake must retain colour instead of becoming a white sheet");
                        assertTrue(shaders.apply(Map.of("water-lighting.glsl", noGlare)).success());
                        frame.render(terrain, camera, scene);
                        byte[] dark = ScreenUtils.getFrameBufferPixels(false);
                        int lit = 0;
                        for (int i = 0; i < after.length; i += 4) {
                            if ((after[i] & 255) > (dark[i] & 255) + 8) { lit++; }
                        }
                        assertTrue(lit > 1000, "The cap preserves a visible sun highlight rather than disabling reflection");
                    } else {
                        assertTrue(Arrays.equals(before, after),
                              "Perspective and weak off-angle glints must retain the uncapped rendering");
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (Exception error) { throw new AssertionError(error); }
        finally {
            try { assertTrue(shaders.apply(Map.of()).success()); }
            finally { frame.dispose(); terrain.dispose(); }
        }
    }

    private static int whitePixels(byte[] pixels) {
        // The display shoulder can wash out the water's colour without reaching literal RGB 255.
        int white = 0;
        for (int i = 0; i < pixels.length; i += 4) {
            if ((pixels[i] & 255) >= 225 && (pixels[i + 1] & 255) >= 225 && (pixels[i + 2] & 255) >= 225) { white++; }
        }
        return white;
    }

    private void capture(String board, Coords focus, float zoom, String label) throws Exception {
        var scene = GpuCalibrationScenes.scene(new File(
              "data/boards/Map Pack Savannahs/" + board));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    System.out.println("BASIN renderer " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), label);
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1440, 1000);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    camera.camera.zoom *= zoom;
                    camera.center(BoardGeometry.center(focus, 0));
                    camera.update();
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    int pass = 0;
                    for (float wind : new float[] { 0, .5f, 1, 0 }) {
                        var settings = new BoardAtmosphere.Settings(13, 0, 0,
                              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
                              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, wind, 35));
                        frame.configure(settings);
                        frame.prepare(terrain, camera, scene);
                        for (int i = 0; i < 300; i++) { terrain.animate(1 / 30f, List.of()); }
                        for (String view : List.of("iso", "top", "glint")) {
                            camera.setIsometric(!view.equals("top"));
                            if (view.equals("glint")) {
                                var sun = frame.lighting().direction();
                                float bearing = (float) Math.toDegrees(Math.atan2(sun.x, -sun.y));
                                float tilt = (float) Math.toDegrees(Math.atan2(Math.hypot(sun.x, sun.y), -sun.z)) + 8;
                                camera.orbit(bearing - camera.azimuth(), tilt - camera.tilt());
                            }
                            camera.update();
                            frame.prepare(terrain, camera, scene);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
                            GpuReviewFrame.save(new File(output, view + "-wind-" + wind + (pass == 3 ? "-settled" : "") + ".png"));
                        }
                        pass++;
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water basin review", failure.get()); }
    }
}
