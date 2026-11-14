/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verify actual weather placement on raised, negative-level and deep-water boards. */
@Tag("on-demand")
class GpuWeatherBaselineSmokeTest {
    @Test
    void waterDepthCannotMoveRainSnowOrHailBelowTheBoardSurface() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuWeatherParticles particles = new GpuWeatherParticles();
                var target = GpuHeatGlow.buffer(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), true);
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(target.getWidth(), target.getHeight());
                    camera.setIsometric(true);
                    for (int level : new int[] { 0, 4, -3 }) {
                        BoardScene dry = scene(level, -1);
                        BoardScene deepWater = scene(level, 20);
                        camera.fit(dry);
                        camera.center(BoardGeometry.center(new Coords(2, 2), level));
                        for (int kind = 0; kind < 3; kind++) {
                            var effects = effects(kind);
                            Pixmap reference = particleFrame(target, particles, camera, dry, effects);
                            Pixmap actual = particleFrame(target, particles, camera, deepWater, effects);
                            try {
                                int visible = 0;
                                for (int y = 0; y < reference.getHeight(); y++) {
                                    for (int x = 0; x < reference.getWidth(); x++) {
                                        if ((reference.getPixel(x, y) & 0xFFFFFF00) != 0) {
                                            visible++;
                                        }
                                    }
                                }
                                assertTrue(visible > 100, "Weather must render above level " + level + ", kind " + kind);
                                assertEquals(reference.getPixels(), actual.getPixels(),
                                      "Changing only water depth must not move weather: level " + level + ", kind " + kind);
                            } finally {
                                reference.dispose();
                                actual.dispose();
                            }
                        }
                    }
                    checkHdrWeather();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    target.dispose();
                    particles.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    /** The runtime weather layer preserves HDR, coverage and the scene's borrowed opaque depth. */
    private static void checkHdrWeather() throws ReflectiveOperationException {
        int size = 160;
        var atmosphere = new GpuAtmosphere();
        var layerField = GpuAtmosphere.class.getDeclaredField("weatherLayer");
        layerField.setAccessible(true);
        var sceneField = GpuAtmosphere.class.getDeclaredField("sceneColor");
        sceneField.setAccessible(true);
        var camera = new BoardCamera();
        camera.resize(size, size);
        camera.setIsometric(true);
        var board = scene(0, -1);
        camera.fit(board);
        var pixels = BufferUtils.newFloatBuffer(size * size * 4);
        try {
            for (int kind = 0; kind < 3; kind++) {
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0, effects(kind)));
                var light = atmosphere.lighting();
                light.ambient().r = 40; light.ambient().g = 20; light.ambient().b = 10;
                light.direct().r = 0; light.direct().g = 0; light.direct().b = 0;
                atmosphere.beginPixels(size, size, .1f);
                atmosphere.renderWeather(camera.camera, board);
                // Capturing weather must restore the original scene target, and leave its colour untouched.
                var scenePixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(size / 2, size / 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, scenePixel);
                assertEquals(light.sky().r, scenePixel.get(0) / GpuSceneRadiance.SCALE, .001);
                assertEquals(0, scenePixel.get(3), 0, "Weather coverage belongs to its separate layer");
                var target = (com.badlogic.gdx.graphics.glutils.FrameBuffer) layerField.get(atmosphere);
                target.begin();
                pixels.clear();
                Gdx.gl.glReadPixels(0, 0, size, size, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                int sample = 0;
                for (int at = 4; at < pixels.capacity(); at += 4) {
                    if (pixels.get(at + 3) > pixels.get(sample + 3)) { sample = at; }
                }
                float alpha = pixels.get(sample + 3);
                double authoredRed = kind == 0 ? .62 : .94;
                double linearRed = Math.pow((authoredRed + .055) / 1.055, 2.4);
                assertTrue(alpha > .1 && alpha <= 1, "Precipitation must produce bounded source-over coverage");
                assertTrue(pixels.get(sample) / GpuSceneRadiance.SCALE > 1,
                      "Bright precipitation must retain HDR light before exposure");
                assertEquals(linearRed * 16 * alpha, pixels.get(sample) / GpuSceneRadiance.SCALE, .035,
                      "Particle colour decodes once and blends linear light with the same coverage alpha");

                var sceneTarget = (com.badlogic.gdx.graphics.glutils.FrameBuffer) sceneField.get(atmosphere);
                sceneTarget.begin();
                Gdx.gl.glClearDepthf(0);
                Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                atmosphere.renderWeather(camera.camera, board);
                target.begin();
                pixels.clear();
                Gdx.gl.glReadPixels(0, 0, size, size, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                for (int at = 0; at < pixels.capacity(); at++) {
                    assertEquals(0, pixels.get(at), 0, "Opaque scene depth must reject weather in every channel");
                }
                Gdx.gl.glClearDepthf(1);
                target.end();
            }
        } finally {
            Gdx.gl.glClearDepthf(1);
            com.badlogic.gdx.graphics.glutils.FrameBuffer.unbind();
            atmosphere.dispose();
        }
    }

    @Test
    void conditionEditsAreIsolatedAndSharedStagesReloadTogether() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkReload(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkReload(GpuShaderManager manager) {
        var particles = new GpuWeatherParticles();
        try {
            BoardScene scene = scene(0, -1);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(true);
            camera.fit(scene);
            byte[][] original = frames(particles, camera, scene);
            for (var kind : GpuWeatherParticles.Kind.values()) {
                String source = GpuShaderSource.readDisk(kind.source);
                for (String edit : List.of(source.replace("particleDisc(uv)", "particleDisc(uv) * 0.5"),
                      source.replace("const float FALL_SPEED = ", "const float FALL_SPEED = 0.5 * "))) {
                    var result = manager.apply(Map.of(kind.source, edit));
                    assertTrue(result.success(), result.message());
                    assertEquals(1, result.updated(), "A condition edit replaces only its own program");
                    byte[][] changed = frames(particles, camera, scene);
                    for (int index = 0; index < original.length; index++) {
                        if (index == kind.ordinal()) {
                            assertFalse(Arrays.equals(original[index], changed[index]), kind + " edit must reach rendered pixels");
                        } else {
                            assertArrayEquals(original[index], changed[index], "Other conditions retain their appearance");
                        }
                    }
                    assertFalse(manager.apply(Map.of(kind.source, "unfinished shader edit")).success());
                    byte[][] retained = frames(particles, camera, scene);
                    for (int index = 0; index < changed.length; index++) {
                        assertArrayEquals(changed[index], retained[index], "Invalid edits retain all working programs");
                    }
                }
                assertTrue(manager.apply(Map.of()).success());
            }

            String fragment = GpuShaderSource.readDisk("weather-particles.frag");
            String vertex = GpuShaderSource.readDisk("weather-particles.vert");
            var sharedEdits = Map.of("weather-particles.frag", fragment.replace("color * u_light", "color * u_light * 0.5"),
                  "weather-particles.vert", vertex.replace("FALL_SPEED * u_level", "FALL_SPEED * u_level * 0.5"));
            for (var edit : sharedEdits.entrySet()) {
                var shared = manager.apply(Map.of(edit.getKey(), edit.getValue()));
                assertTrue(shared.success(), shared.message());
                assertEquals(3, shared.updated(), "Shared stage edits replace all precipitation programs");
                byte[][] changed = frames(particles, camera, scene);
                for (int index = 0; index < original.length; index++) {
                    assertFalse(Arrays.equals(original[index], changed[index]), "Shared edits reach every condition");
                }
                assertTrue(manager.apply(Map.of()).success());
            }
            byte[][] restored = frames(particles, camera, scene);
            for (int index = 0; index < original.length; index++) { assertArrayEquals(original[index], restored[index]); }

            ScreenUtils.clear(0, 0, 0, 1, true);
            for (int kind = 0; kind < 3; kind++) { particles.render(camera.camera, scene, effects(kind), Color.WHITE, 3); }
            byte[] sequential = pixels();
            ScreenUtils.clear(0, 0, 0, 1, true);
            particles.render(camera.camera, scene, new BoardAtmosphere.Effects(1, 1, 1, 0, 0, .6f, 60), Color.WHITE, 3);
            assertArrayEquals(sequential, pixels(), "Mixed weather preserves the same draws and blending order");

            var atmosphere = new GpuAtmosphere();
            try {
                String sand = GpuShaderSource.readDisk("weather-sand.glsl");
                var result = manager.apply(Map.of("weather-sand.glsl", sand.replace("0.12", "0.06")));
                assertTrue(result.success(), result.message());
                assertEquals(1, result.updated(), "Sand edits reload the existing composite, leaving fog and particles alone");
                assertTrue(manager.apply(Map.of()).success());
            } finally { atmosphere.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { particles.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms(), "Reloaded weather programs are released");
    }

    private static byte[][] frames(GpuWeatherParticles particles, BoardCamera camera, BoardScene scene) {
        byte[][] frames = new byte[3][];
        for (int kind = 0; kind < frames.length; kind++) {
            ScreenUtils.clear(0, 0, 0, 1, true);
            particles.render(camera.camera, scene, effects(kind), Color.WHITE, 3);
            frames[kind] = pixels();
        }
        return frames;
    }

    private static byte[] pixels() {
        return ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
    }

    private static BoardAtmosphere.Effects effects(int kind) {
        return new BoardAtmosphere.Effects(kind == 0 ? 1 : 0, kind == 1 ? 1 : 0, kind == 2 ? 1 : 0, 0, 0, .6f, 60);
    }

    private static Pixmap particleFrame(com.badlogic.gdx.graphics.glutils.FrameBuffer target,
          GpuWeatherParticles particles, BoardCamera camera, BoardScene scene,
          BoardAtmosphere.Effects effects) {
        // Compare owned scene targets, independently of hidden-window backbuffer lifetime and pixel scaling.
        target.begin();
        try {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearDepthf(1);
            ScreenUtils.clear(0, 0, 0, 1, true);
            particles.render(camera.camera, scene, effects, Color.WHITE, 3);
            // Restore canonical units before the diagnostic's 8-bit conversion; otherwise storage scaling alone
            // would erase faint flakes from the visible-pixel assertion. Alpha remains coverage.
            int width = target.getWidth(), height = target.getHeight();
            var values = BufferUtils.newFloatBuffer(width * height * 4);
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_FLOAT, values);
            var image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            image.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4;
                    image.drawPixel(x, y, Color.rgba8888(Math.clamp(values.get(at) / GpuSceneRadiance.SCALE, 0, 1),
                          Math.clamp(values.get(at + 1) / GpuSceneRadiance.SCALE, 0, 1),
                          Math.clamp(values.get(at + 2) / GpuSceneRadiance.SCALE, 0, 1), values.get(at + 3)));
                }
            }
            return image;
        } finally { target.end(); }
    }

    private static BoardScene scene(int level, int depth) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), level, depth, false, 0, BoardScene.Surface.GRASS,
                      List.of(), List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
