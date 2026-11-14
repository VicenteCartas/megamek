/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native HDR/composite boundary tests, independent of lava artwork and terrain geometry. */
@Tag("on-demand")
class GpuHeatGlowSmokeTest {
    private static final String SOURCE = """
          #version 330 core
          in vec2 v_uv;
          layout(location = 0) out vec4 fragColor;
          uniform vec3 u_heat;
          uniform float u_occluder;
          uniform float u_strip;
          void main() {
              bool source = v_uv.x > .4 && v_uv.x < .6 && v_uv.y > .3 && v_uv.y < .7;
              if (u_strip > 0.0) source = v_uv.x > .4 && v_uv.x < .6 && abs(gl_FragCoord.y - u_strip) < .5;
              if (u_occluder > .5 && !source) discard;
              vec3 color = vec3(.12, .16, .10);
              if (source && u_occluder < .5 && u_heat.r > 0.0) color = u_heat;
              fragColor = vec4(color, 1.0);
              gl_FragDepth = u_occluder > .5 ? .25 : .5;
          }
          """;
    private static final String COPY = """
          #version 330 core
          in vec2 v_uv;
          layout(location = 0) out vec4 fragColor;
          uniform sampler2D u_source;
          // SCENE_RADIANCE
          void main() { fragColor = vec4(loadScene(texture(u_source, v_uv).rgb) / 32.0, 1.0); }
          """;

    @Test
    void heatSurvivesNightButRespectsOcclusionAndVisibilityWithoutChangingOrdinarySurfaces() {
        nativeCheck(this::checkHeat);
    }

    @Test
    void halfFloatStoragePreservesBrightRangeThroughAlphaBlendingHeatAndLateExposure() {
        nativeCheck(() -> {
            try {
                checkSceneRange(1);
                checkSceneRange(1500000f / 180380);
            }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    private static void nativeCheck(Runnable check) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(256, 128, -1, -1);
        config.setWindowedMode(256, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Heat glow", failure.get()); }
    }

    private static void checkSceneRange(float multiplier) throws ReflectiveOperationException {
        Mesh quad = GpuAtmosphere.screenQuad();
        ShaderProgram source = GpuGlsl.compile("HDR storage range", GpuShaderSource.read("atmosphere.vert"),
              GpuSceneRadiance.fragment("""
                    #version 330 core
                    layout(location = 0) out vec4 fragColor;
                    uniform vec4 u_radiance;
                    void main() { fragColor = u_radiance; }
                    """));
        var atmosphere = new GpuAtmosphere();
        var terrain = new GpuTerrain();
        var camera = new OrthographicCamera(256, 128);
        camera.position.set(0, 0, 100);
        camera.near = 1;
        camera.far = 201;
        camera.update();
        var molten = scene(true);
        // Retain the original point-ice regression stimulus, then stress the storage boundary near the finite
        // directional conservative bound (~1.46 million). These do not claim current filtered ice produces them.
        float[] highlight = { 180380 * multiplier, 137941 * multiplier, 73770 * multiplier };
        float[] warm = { 180380 * multiplier, 20000 * multiplier, 4000 * multiplier };
        float[] mixed = new float[3];
        for (int channel = 0; channel < 3; channel++) { mixed[channel] = highlight[channel] * .25f + warm[channel] * .5f; }
        try {
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0));
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
            atmosphere.updateLight(camera);
            atmosphere.configureClouds(terrain, molten);
            atmosphere.begin(256, 128, 0);
            GpuAtmosphere.screenState();
            source.bind();
            source.setUniformf("u_radiance", highlight[0], highlight[1], highlight[2], 1);
            quad.render(source, GL20.GL_TRIANGLES);
            assertRadiance(highlight, readPixel(128, 64), "The actual main scene retains the bright range stimulus");

            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            source.setUniformf("u_radiance", highlight[0], highlight[1], highlight[2], .5f);
            quad.render(source, GL20.GL_TRIANGLES);
            source.setUniformf("u_radiance", warm[0], warm[1], warm[2], .5f);
            quad.render(source, GL20.GL_TRIANGLES);
            float[] stored = readPixel(128, 64);
            assertRadiance(mixed, stored, "Transparent highlights blend in the shared half-float storage units");
            assertEquals(.75, stored[3], .0005, "The radiance scale must never scale coverage alpha");
            assertTrue(stored[0] / GpuSceneRadiance.SCALE > 65504,
                  "The blended highlight must still exceed the unscaled half-float range");
            atmosphere.end(camera, terrain, molten, 0);

            var heatField = GpuAtmosphere.class.getDeclaredField("heatGlow");
            heatField.setAccessible(true);
            var heat = (GpuHeatGlow) heatField.get(atmosphere);
            for (String name : new String[] { "work", "glow" }) {
                var field = GpuHeatGlow.class.getDeclaredField(name);
                field.setAccessible(true);
                var target = (FrameBuffer) field.get(heat);
                target.begin();
                try {
                    assertRadiance(mixed, readPixel(target.getWidth() / 2, target.getHeight() / 2),
                          "Downsampling and both heat blur passes preserve uniform HDR radiance: " + name);
                } finally { target.end(); }
            }

            // Use the actual configured composite with diagnostic exposure below the user slider's range.
            // No source is rerendered: exposure must recover stored highlight detail after blending and blur.
            var compositeField = GpuAtmosphere.class.getDeclaredField("compositeShader");
            compositeField.setAccessible(true);
            var composite = (ShaderProgram) compositeField.get(atmosphere);
            for (float exposure : new float[] { 1f / (262144 * multiplier), 1f / (524288 * multiplier) }) {
                GpuAtmosphere.screenState();
                composite.bind();
                composite.setUniformf("u_exposure", exposure);
                quad.render(composite, GL20.GL_TRIANGLES);
                Pixmap output = Pixmap.createFromFrameBuffer(128, 64, 1, 1);
                try {
                    int rgba = output.getPixel(0, 0);
                    for (int channel = 0; channel < 3; channel++) {
                        double radiance = mixed[channel] * 1.12 * exposure;
                        double shoulder = Math.min(radiance, .6) + .4 * (1 - Math.exp(-Math.max(radiance - .6, 0) / .4));
                        double encoded = shoulder <= .0031308 ? 12.92 * shoulder : 1.055 * Math.pow(shoulder, 1 / 2.4) - .055;
                        assertEquals(Math.round(encoded * 255), (rgba >>> (24 - 8 * channel)) & 255, 1,
                              "Late exposure recovers the source plus the 12% heat halo; channel=" + channel);
                    }
                } finally { output.dispose(); }
            }
            var sceneField = GpuAtmosphere.class.getDeclaredField("sceneColor");
            sceneField.setAccessible(true);
            var sceneTarget = (FrameBuffer) sceneField.get(atmosphere);
            sceneTarget.begin();
            try {
                float[] after = readPixel(128, 64);
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(stored[channel], after[channel], "Display conversion must leave the HDR source unchanged");
                }
            } finally { sceneTarget.end(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
            atmosphere.dispose();
            source.dispose();
            quad.dispose();
        }
    }

    private static float[] readPixel(int x, int y) {
        var pixel = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
        return new float[] { pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3) };
    }

    private static void assertRadiance(float[] expected, float[] stored, String message) {
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(Float.isFinite(stored[channel]), message + ": finite channel " + channel);
            assertEquals(expected[channel], stored[channel] / GpuSceneRadiance.SCALE, expected[channel] * .002,
                  message + ": channel " + channel);
        }
    }

    private void checkHeat() {
        assertEquals(256, Gdx.graphics.getBackBufferWidth());
        assertEquals(128, Gdx.graphics.getBackBufferHeight());
        Mesh quad = GpuAtmosphere.screenQuad();
        ShaderProgram source = GpuGlsl.compile("Heat test source", GpuShaderSource.read("atmosphere.vert"),
              GpuSceneRadiance.fragment(SOURCE));
        ShaderProgram copy = GpuGlsl.compile("Heat test copy", GpuShaderSource.read("atmosphere.vert"),
              COPY.replace("// SCENE_RADIANCE", GpuSceneRadiance.functions()));
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuTerrain terrain = new GpuTerrain();
        GpuHeatGlow glow = new GpuHeatGlow(quad);
        GpuFieldOfView field = new GpuFieldOfView();
        FrameBuffer input = GpuHeatGlow.buffer(256, 128);
        Texture depth = GpuAtmosphere.attachDepthTexture(input);
        OrthographicCamera camera = new OrthographicCamera(256, 128);
        var center = BoardGeometry.center(new Coords(0, 0), 0);
        camera.position.set(center.x, center.y, 100);
        camera.zoom = .1f;
        camera.near = 1;
        camera.far = 201;
        camera.update();
        BoardScene dry = scene(false), molten = scene(true);
        try {
            for (float hour : new float[] { 13, 0 }) {
                Pixmap ldr = frame(atmosphere, terrain, dry, camera, quad, source, hour, 0, false);
                Pixmap hdr = frame(atmosphere, terrain, molten, camera, quad, source, hour, 0, false);
                try {
                    assertNear(ldr, hdr, 2, "Dark non-emissive surfaces retain the existing grade at hour " + hour);
                } finally { ldr.dispose(); hdr.dispose(); }
                ldr = frame(atmosphere, terrain, dry, camera, quad, source, hour, 2, false);
                hdr = frame(atmosphere, terrain, molten, camera, quad, source, hour, 2, false);
                try {
                    assertNear(ldr, hdr, 2, "Bright white reflections do not become heat or glow");
                } finally { ldr.dispose(); hdr.dispose(); }
            }

            Pixmap dark = frame(atmosphere, terrain, molten, camera, quad, source, 0, 0, false);
            Pixmap night = frame(atmosphere, terrain, molten, camera, quad, source, 0, 1, false);
            Pixmap day = frame(atmosphere, terrain, molten, camera, quad, source, 13, 1, false);
            Pixmap hidden = frame(atmosphere, terrain, molten, camera, quad, source, 0, 1, true);
            try {
                assertTrue(red(night, .5f, .5f) > 245, "Molten HDR cores must reach luminous display values");
                assertEquals(red(day, .5f, .5f), red(night, .5f, .5f), 2,
                      "Self emission must not take the night surface tint/desaturation");
                assertTrue(red(night, .62f, .5f) > red(dark, .62f, .5f) + 8,
                      "Heat spreads a restrained halo beyond the visible source silhouette");
                assertEquals(red(dark, .1f, .5f), red(night, .1f, .5f), 2,
                      "Distant dark ground stays unchanged");
                assertNear(dark, hidden, 2, "A nearer opaque surface removes the entire hidden heat halo");
            } finally { dark.dispose(); night.dispose(); day.dispose(); hidden.dispose(); }

            input.begin();
            draw(quad, source, 1, false);
            input.end();
            var mask = new BoardFieldOfView(1, 1,
                  List.of(new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)), 255, 0, true, false, false);
            field.update(mask);
            field.configure(GpuFieldOfView.Style.DIMMED, 0, GpuFieldOfView.Style.DIMMED, 0);
            double undimmed = glowEnergy(glow, input, depth, camera, field, quad, copy);
            field.configure(GpuFieldOfView.Style.DIMMED, .5f, GpuFieldOfView.Style.DIMMED, .5f);
            double half = glowEnergy(glow, input, depth, camera, field, quad, copy);
            field.configure(GpuFieldOfView.Style.DIMMED, 1, GpuFieldOfView.Style.DIMMED, 1);
            double obscured = glowEnergy(glow, input, depth, camera, field, quad, copy);
            assertTrue(undimmed > 1, "A zero-opacity FoV effect leaves visible heat available for the halo");
            assertEquals(.5, half / undimmed, .035, "Source radiance follows the shared FoV opacity");
            assertEquals(0, obscured, "Fully hidden heat cannot leak through the blur into a visible neighbour");
            // Put one hidden hot source row beside a visible row within the same bilinear 2x2 footprint.
            // A mask applied after RGB interpolation would mistakenly classify their mixture as visible.
            field.update(new BoardFieldOfView(1, 2, List.of(BoardFieldOfView.Hex.VISIBLE,
                  new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)), 255, 0, true, false, false));
            camera.position.y = -BoardGeometry.height() - .1f;
            camera.update();
            input.begin();
            draw(quad, source, 0, false);
            source.setUniformf("u_heat", 8, 1.2f, .015f);
            source.setUniformf("u_strip", 64.5f);
            quad.render(source, GL20.GL_TRIANGLES);
            input.end();
            assertEquals(0, glowEnergy(glow, input, depth, camera, field, quad, copy),
                  "A hidden texel touching visible terrain is masked before the downsample mixes their colors");
            field.configure(GpuFieldOfView.Style.DIMMED, 0, GpuFieldOfView.Style.DIMMED, 0);
            assertTrue(glowEnergy(glow, input, depth, camera, field, quad, copy) > 0,
                  "That same narrow source remains visible when the shared FoV opacity is zero");
            assertEquals(Texture.TextureFilter.Nearest, input.getColorBufferTexture().getMinFilter(),
                  "The scene sampler is restored before compositing");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            depth.dispose();
            input.dispose();
            field.dispose();
            glow.dispose();
            terrain.dispose();
            atmosphere.dispose();
            source.dispose();
            copy.dispose();
            quad.dispose();
        }
    }

    private static BoardScene scene(boolean molten) {
        var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS,
              List.of(), List.of(),
              molten ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE);
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
    }

    private static Pixmap frame(GpuAtmosphere atmosphere, GpuTerrain terrain, BoardScene scene,
          OrthographicCamera camera, Mesh quad, ShaderProgram source, float hour, int hot, boolean occluded) {
        atmosphere.configure(new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0));
        atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
        atmosphere.updateLight(camera);
        atmosphere.prepareClouds(terrain, scene, 0);
        atmosphere.begin(256, 128, 0);
        draw(quad, source, hot, occluded);
        var profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        try {
            atmosphere.end(camera, terrain, scene, 0);
            assertEquals(scene.tiles().getFirst().liquid().molten() ? 4 : 1, profiler.getDrawCalls(),
                  "Only molten maps add the three reduced-resolution heat passes");
        } finally { profiler.disable(); }
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static void draw(Mesh quad, ShaderProgram source, int hot, boolean occluded) {
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(1);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        source.bind();
        source.setUniformf("u_heat", hot == 0 ? 0 : 8, hot == 2 ? 8 : 1.2f, hot == 2 ? 8 : .015f);
        source.setUniformf("u_occluder", 0);
        source.setUniformf("u_strip", 0);
        quad.render(source, GL20.GL_TRIANGLES);
        if (occluded) {
            source.setUniformf("u_occluder", 1);
            quad.render(source, GL20.GL_TRIANGLES);
        }
    }

    private static double glowEnergy(GpuHeatGlow glow, FrameBuffer input, Texture depth, OrthographicCamera camera,
          GpuFieldOfView field, Mesh quad, ShaderProgram copy) {
        glow.render(input.getColorBufferTexture(), depth, null, camera, field);
        GpuAtmosphere.screenState();
        copy.bind();
        glow.texture().bind(0);
        copy.setUniformi("u_source", 0);
        quad.render(copy, GL20.GL_TRIANGLES);
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            long total = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) { total += image.getPixel(x, y) >>> 24; }
            }
            return total / (double) (image.getWidth() * image.getHeight());
        } finally { image.dispose(); }
    }

    private static int red(Pixmap image, float x, float y) {
        return image.getPixel((int) (x * image.getWidth()), (int) (y * image.getHeight())) >>> 24;
    }

    private static void assertNear(Pixmap expected, Pixmap actual, int tolerance, String message) {
        int maximum = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getPixel(x, y), b = actual.getPixel(x, y);
                for (int shift = 8; shift <= 24; shift += 8) {
                    maximum = Math.max(maximum, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
                }
            }
        }
        assertTrue(maximum <= tolerance, message + ": maximum channel difference " + maximum);
    }
}
