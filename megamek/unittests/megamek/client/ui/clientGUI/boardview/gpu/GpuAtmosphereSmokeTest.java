/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual GL shaders, depth reconstruction and framebuffer resizing. */
@Tag("on-demand")
class GpuAtmosphereSmokeTest {
    private final File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));

    @Test
    void fogStopsAtGeometryAndTimeChangesLightingWithoutReallocatingShadows() {
        runNative(false, false);
    }

    @Test
    void authoredBackdropMatchesUiAcrossTaintTimeExposureAndCameras() {
        runNative(true, false);
    }

    @Test
    void geometryShadowsAttenuateFogWithoutAliasingMoonlessPasses() {
        runNative(false, true);
    }

    private void runNative(boolean skyOnly, boolean fogShadowOnly) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    if (skyOnly) {
                        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                        String expected = System.getProperty("megamek.gpu.expectedRenderer");
                        System.out.println("UI_BACKDROP renderer=" + renderer);
                        if (expected != null) { assertTrue(renderer.contains(expected), renderer); }
                        checkSkyDirections();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } else {
                        checkRendering(fogShadowOnly);
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private void checkRendering(boolean fogShadowOnly) throws Exception {
        // Measure render work independently of the live window's VSync preference.
        Gdx.graphics.setVSync(false);
        assertTrue(output.isDirectory() || output.mkdirs());
        if (!fogShadowOnly) {
            checkScatteringPhase();
            checkFogCloudBounds();
            checkSkyDirections();
            checkForegroundWeather();
        }
        // Gray-155 tileset art with one gray-65 hex: the known receivers the light checks compare. Enhanced ground
        // draws its own materials; tileset art is the Classic graphics'.
        BoardScene.Pixels gray = art(155), dark = art(65);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                      List.of(), List.of(), BoardLiquid.NONE, x == 1 && y == 2 ? dark : gray));
            }
        }
        BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
        GpuTerrain terrain = new GpuTerrain();
        terrain.setColumns(!fogShadowOnly);
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        ModelBatch batch = new ModelBatch(GpuUnitShader.provider());
        var model = new ModelBuilder().createBox(36, 36, 108,
              new Material(ColorAttribute.createDiffuse(new Color(0.61f, 0.61f, 0.61f, 1))),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        ModelInstance tower = new ModelInstance(model);
        Vector3 center = BoardGeometry.center(new Coords(3, 3), 0);
        tower.transform.setToTranslation(center.x, center.y, 54);
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(false);
        camera.fit(scene);
        terrain.update(scene);
        try {
            if (fogShadowOnly) {
                checkFogGeometryShadows(atmosphere, terrain, batch, tower, camera, scene);
                return;
            }
            BoardAtmosphere.Settings clear = new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0);
            draw(atmosphere, terrain, batch, tower, camera, scene, clear);
            checkCloudVariantReuse(atmosphere, terrain, batch, tower, camera, scene, clear);
            var shadow = terrain.environment().shadowMap;
            Vector3 ground = BoardGeometry.center(new Coords(1, 3), 0);
            Vector3 darkGround = BoardGeometry.center(new Coords(1, 2), 0);
            Vector3 roof = new Vector3(center).add(0, 0, 108);
            Color clearGround = sample(camera, ground);
            Color clearRoof = sample(camera, roof);
            // At noon, the light unit, display and linear encodings of the light agree: this compares the light levels
            // of the two paths, and the full-moon comparison below also catches an encoding mismatch.
            assertOneLightModel(clearRoof, clearGround, "noon");
            float shadowShare = luminance(sample(camera, new Vector3(center).add(0, -32, 0))) / luminance(clearGround);
            assertTrue(shadowShare > 0.4f && shadowShare < 0.6f,
                  "Native daylight shadows retain sky fill while showing clear contrast: " + shadowShare);
            float clearContrast = linearLuminance(clearGround) - linearLuminance(sample(camera, darkGround));
            GpuBoardTestUi.capture(new File(output, "atmosphere-noon.png"));
            draw(atmosphere, terrain, batch, tower, camera, scene,
                  new BoardAtmosphere.Settings(12, 0, 0.8f, 1.5f, 0, 0));
            Color fogGround = sample(camera, ground);
            Color fogRoof = sample(camera, roof);
            GpuBoardTestUi.capture(new File(output, "atmosphere-depth-fog.png"));
            assertTrue(difference(clearGround, fogGround) > 0.02f, "Ground fog must subtly affect low terrain");
            assertTrue(difference(clearGround, fogGround) > difference(clearRoof, fogRoof) * 2,
                  "Camera depth and exponential height fog must leave the raised roof clearer: ground "
                        + clearGround + " -> " + fogGround + ", roof " + clearRoof + " -> " + fogRoof);
            draw(atmosphere, terrain, batch, tower, camera, scene,
                  new BoardAtmosphere.Settings(12, 0, 1, 8, 1, 0));
            float fogContrast = linearLuminance(sample(camera, ground)) - linearLuminance(sample(camera, darkGround));
            assertEquals(1 - BoardAtmosphere.MAX_FOG_OPACITY, fogContrast / clearContrast, 0.025f,
                  "Maximum fog and haze must retain the configured share of scene contrast in linear light");
            GpuBoardTestUi.capture(new File(output, "atmosphere-maximum-fog.png"));
            var fullMoon = new BoardAtmosphere.Settings(0, 0, 0, 1.5f, 0, 0);
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0, 0, 0, 0));
            draw(atmosphere, terrain, batch, tower, camera, scene, fullMoon);
            Color originalMoonGround = sample(camera, ground);
            Vector3 moonShadow = new Vector3(center).add(0, 32, 0);
            Color originalMoonShade = sample(camera, moonShadow);
            GpuBoardTestUi.capture(new File(output, "atmosphere-night-original-shadows.png"));
            atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
            draw(atmosphere, terrain, batch, tower, camera, scene, fullMoon);
            Color night = sample(camera, ground);
            assertOneLightModel(sample(camera, roof), night, "full moon");
            // Sky and moon have different chromaticities. Transferring their energy preserves luminance, not RGB.
            assertEquals(1, linearLuminance(night) / linearLuminance(originalMoonGround), .04f,
                  "Moon contrast must preserve lit-ground luminance: " + originalMoonGround + " -> " + night);
            assertTrue(luminance(sample(camera, moonShadow)) < luminance(originalMoonShade) * 0.95f,
                  "The Full Moon contrast control must visibly deepen native cast shadows without a settings change");
            float nightToDay = linearLuminance(night) / linearLuminance(clearGround);
            assertTrue(nightToDay > .23f && nightToDay < .33f,
                  "Calibrated full moon stays below daylight on the matched gray receiver: " + nightToDay);
            // Retain a readable display-space floor after the night exposure and cool tint.
            float nightBrightnessFloor = 0.29f;
            assertTrue(luminance(night) > nightBrightnessFloor,
                  "Full moon lighting must keep the ground readable at its default exposure: " + night);
            assertTrue(night.b > night.r, "Moonlit gray ground must have a blue tint");
            assertSame(shadow, terrain.environment().shadowMap, "Changing time must reuse the shadow framebuffer");
            GpuBoardTestUi.capture(new File(output, "atmosphere-night.png"));
            // Twilight is the sun's lowest lit window: 6:00-6:45 at dawn, 17:15-18:00 at dusk. Earlier and later
            // hours are night, which is darker by design, so only the twilight hours have to stay readable.
            StringBuilder floor = new StringBuilder(String.format(Locale.ROOT,
                  "Display luminance of lit gray-155 ground. Full moon: %.3f; overcast twilight:%n", luminance(night)));
            float darkestTwilight = 1;
            for (float hour : new float[] { 6, 6.25f, 6.5f, 6.75f, 17.25f, 17.5f, 17.75f, 18 }) {
                draw(atmosphere, terrain, batch, tower, camera, scene,
                      new BoardAtmosphere.Settings(hour, 1, 0, 1.5f, 0, 0));
                float twilight = luminance(sample(camera, ground));
                floor.append(String.format(Locale.ROOT, "%s: %.3f%n", hour, twilight));
                darkestTwilight = Math.min(darkestTwilight, twilight);
                GpuBoardTestUi.capture(new File(output, "atmosphere-twilight-" + hour + ".png"));
            }
            Files.writeString(new File(output, "twilight-floor.txt").toPath(), floor);
            // Overcast twilight, whose cloud shadows take most of the low sun's light, stays about as readable as a clear
            // full-moon night: no darker than four fifths of it.
            assertTrue(darkestTwilight > .8f * luminance(night),
                  "The rendered board must not go dark during twilight:\n" + floor);
            checkPressureLighting(atmosphere, terrain, batch, tower, camera, scene, ground, center);
            checkTwilightShadows(atmosphere, terrain, batch, tower, camera, scene);
            checkTwilightAndCloudPalette(atmosphere, terrain, batch, tower, camera, scene);
            checkCloudOpacity(scene);
            checkFixedLightingAndMoonModes(atmosphere, terrain, batch, tower, camera, scene);
            checkClouds(atmosphere, terrain, batch, tower, camera, scene);
            checkWetGround(atmosphere, terrain, batch, tower, camera, scene);
            checkParticles(atmosphere, terrain, batch, tower, camera, scene);
            for (boolean isometric : List.of(false, true)) {
                camera.setIsometric(isometric);
                camera.fit(scene);
                for (int size : new int[] { 900, 2043, 1280 }) {
                    camera.resize(size, 600);
                    draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    draw(atmosphere, terrain, batch, tower, camera, scene,
                          new BoardAtmosphere.Settings(13, 0.6f, 0, 2.5f, 0, 0));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Cloud and shaft buffers must resize together");
                }
            }
            camera.resize(1280, 800);
            camera.setIsometric(true);
            camera.fit(scene);
            StringBuilder timing = new StringBuilder("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
                  + "\n1280x800 synthetic 7x7 board and tower; warmed, synchronous GL completion; no vsync wait\n");
            String[] modes = { "Clear", "Maximum fog/haze", "Downpour",
                  "Maximum snow", "Maximum hail", "Maximum sand", "All precipitation at maximum",
                  "Broken clouds and shafts", "Overcast clouds", "Storm clouds and shafts" };
            int[] particles = { 0, 0, 4608, 4608, 3072, 0, 12288, 0, 0, 4608 };
            int[] particleDraws = { 0, 0, 1, 1, 1, 0, 3, 0, 0, 1 };
            for (int mode = 0; mode < modes.length; mode++) {
                var effects = new BoardAtmosphere.Effects(mode == 2 || mode == 6 || mode == 9 ? 1 : 0,
                      mode == 3 || mode == 6 ? 1 : 0, mode == 4 || mode == 6 ? 1 : 0,
                      mode == 5 || mode == 6 ? 1 : 0, 0, 0.4f, 60);
                var settings = new BoardAtmosphere.Settings(13, mode >= 8 ? 1 : mode == 7 ? 0.6f : 0,
                      mode == 1 ? 1 : 0, 8, mode == 1 ? 1 : mode >= 7 ? 0.15f : 0, 0, effects);
                List<Double> millis = new ArrayList<>();
                for (int frame = 0; frame < 45; frame++) {
                    long start = System.nanoTime();
                    draw(atmosphere, terrain, batch, tower, camera, scene, settings);
                    Gdx.gl.glFinish();
                    if (frame >= 15) {
                        millis.add((System.nanoTime() - start) / 1_000_000.0);
                    }
                }
                millis.sort(Double::compareTo);
                timing.append(String.format(Locale.ROOT, "%s: median %.3f ms, p95 %.3f ms%n",
                      modes[mode],
                      millis.get(millis.size() / 2), millis.get((int) (millis.size() * 0.95))));
                // Instrument outside the timed window: no board redraw during postprocessing, no density reductions.
                atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
                var profiler = new GLProfiler(Gdx.graphics);
                profiler.enable();
                try {
                    atmosphere.renderWeather(camera.camera, scene);
                    assertEquals(particleDraws[mode], profiler.getDrawCalls(), "Weather draw budget: " + modes[mode]);
                    assertEquals(particles[mode] * 6f, profiler.getVertexCount().total,
                          "All particle quads must remain at full density: " + modes[mode]);
                    profiler.reset();
                    atmosphere.end(camera.camera, terrain, scene, 0);
                    int postDraws = profiler.getDrawCalls();
                    assertEquals(mode == 1 || mode >= 7 ? 2 : 1, postDraws,
                          modes[mode] + " must only composite, plus scattering when enabled");
                    timing.append(String.format(Locale.ROOT, "  Postprocess draws: %d; particle draws: %d; particles: %d%n",
                          postDraws, particleDraws[mode], particles[mode]));
                } finally {
                    profiler.disable();
                }
            }
            Files.writeString(new File(output, "atmosphere-timing.txt").toPath(), timing);
            checkLargeBoard(atmosphere, terrain, batch, tower, camera);
        } finally {
            atmosphere.dispose();
            terrain.dispose();
            batch.dispose();
            model.dispose();
        }
    }

    private void draw(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings settings) {
        draw(atmosphere, terrain, batch, tower, camera, scene, settings, null);
    }

    private void draw(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings settings, GpuCloudShadow cloudOverride) {
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, settings, cloudOverride, 0.1f);
    }

    private void draw(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, List<ModelInstance> units,
          BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings settings, GpuCloudShadow cloudOverride, float delta) {
        drawScene(atmosphere, terrain, batch, units, camera, scene, settings, cloudOverride, delta, true);
    }

    /** Surface checks sample before precipitation; both paths use the actual weather-derived material uniforms. */
    private void drawScene(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, List<ModelInstance> units,
          BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings settings, GpuCloudShadow cloudOverride, float delta) {
        drawScene(atmosphere, terrain, batch, units, camera, scene, settings, cloudOverride, delta, false);
    }

    private void drawScene(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, List<ModelInstance> units,
          BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings settings, GpuCloudShadow cloudOverride, float delta,
          boolean weather) {
        atmosphere.configure(settings);
        atmosphere.updateLight(camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(units);
        atmosphere.prepareClouds(terrain, scene, delta);
        if (cloudOverride != null) {
            GpuEnvironmentLighting.required(terrain.environment()).cloudShadow = cloudOverride;
        }
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, delta);
        terrain.render(camera.camera);
        batch.begin(camera.camera);
        batch.render(units, terrain.environment());
        batch.end();
        if (weather) { atmosphere.renderWeather(camera.camera, scene); }
        atmosphere.end(camera.camera, terrain, scene, 0);
    }

    /** Exercise the real fog pass independently of the broad fixture's unrelated twilight brightness floor. */
    private void checkFogGeometryShadows(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        String expected = System.getProperty("megamek.gpu.expectedRenderer");
        System.out.println("HARDWARE_DEPTH_FOG renderer=" + renderer);
        if (expected != null) { assertTrue(renderer.contains(expected), renderer); }
        camera.setIsometric(true);
        camera.fit(scene);
        var cloudy = new BoardAtmosphere.Settings(13, .85f, .05f, 2.5f, 0, 0);
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        // Settle the actual family mask selected by the configured frame before comparing retained scene depth.
        terrain.update(scene);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        var shadow = terrain.environment().shadowMap;
        assertNotNull(shadow);
        GpuShadowSmokeTest.assertDepthTexture(shadow.getDepthMap().texture);
        var fogField = GpuAtmosphere.class.getDeclaredField("fog");
        fogField.setAccessible(true);
        FrameBuffer fog = (FrameBuffer) fogField.get(atmosphere);
        var renderFog = GpuAtmosphere.class.getDeclaredMethod("renderFog", Camera.class, GpuTerrain.class, BoardScene.class);
        renderFog.setAccessible(true);
        float[] blocked = fogPixels(fog);
        GpuBoardTestUi.capture(new File(output, "hardware-depth-fog-shadow.png"));
        float[] unblocked;
        try {
            // Only the volume's geometry visibility changes; camera depth, cloud transmission and clock stay fixed.
            terrain.environment().shadowMap = null;
            renderFog.invoke(atmosphere, camera.camera, terrain, scene);
            unblocked = fogPixels(fog);
        } finally { terrain.environment().shadowMap = shadow; }
        renderFog.invoke(atmosphere, camera.camera, terrain, scene);
        assertArrayEquals(blocked, fogPixels(fog), "Restoring geometry shadows restores the exact fog output");
        float maximumAttenuation = 0;
        int attenuated = 0;
        for (int i = 0; i < blocked.length; i++) {
            if (i % 4 == 3) {
                assertEquals(blocked[i], unblocked[i], "Visibility must not change physical fog transmission");
            } else {
                assertTrue(unblocked[i] >= blocked[i], "A geometry shadow can only attenuate scattered sunlight");
                float attenuation = unblocked[i] - blocked[i];
                maximumAttenuation = Math.max(maximumAttenuation, attenuation);
                if (attenuation > 0) { attenuated++; }
            }
        }
        assertTrue(attenuated > 0, "The actual caster must attenuate at least one fog RGB sample");
        try {
            for (float strength : new float[] { 0, .5f, 1 }) {
                atmosphere.setOptions(shadowStrength(strength));
                atmosphere.updateLight(camera);
                renderFog.invoke(atmosphere, camera.camera, terrain, scene);
                float[] scaled = fogPixels(fog);
                if (strength == 0) {
                    assertArrayEquals(unblocked, scaled, "Zero shadow strength equals removing only the geometry map");
                } else if (strength == 1) {
                    assertArrayEquals(blocked, scaled, "Restoring shadow strength restores the exact fog output");
                } else {
                    for (int i = 0; i < scaled.length; i++) {
                        if (i % 4 == 3) {
                            assertEquals(blocked[i], scaled[i], "Shadow strength cannot change volume transmission");
                        } else {
                            float midpoint = (blocked[i] + unblocked[i]) * .5f;
                            // The raw fog target is half float; allow two half-ULPs for the stored endpoints/midpoint.
                            float tolerance = Math.scalb(2f, Math.max(-14, Math.getExponent(midpoint)) - 10);
                            assertEquals(midpoint, scaled[i], tolerance, "Half-strength shadow is the visibility midpoint");
                        }
                    }
                }
            }
        } finally { atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS); }
        atmosphere.setOptions(new GpuAtmosphere.Options(0, false));
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        assertFalse(java.util.Arrays.equals(blocked, fogPixels(fog)), "The actual shaft contribution must be nonzero");
        GpuBoardTestUi.capture(new File(output, "hardware-depth-fog-rays-off.png"));
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        byte[] daylightDepth = GpuFamilyAssemblyReview.shadowDepth(terrain);
        long mask = terrain.environment().getMask();
        int flags = GpuEnvironmentLighting.flags(terrain.environment());
        int programs = ShaderProgram.getNumManagedShaderPrograms();
        int families = terrain.familyPrograms().programs();
        var conditions = new PlanetaryConditions();
        conditions.setFog(Fog.FOG_LIGHT);
        for (Light preset : new Light[] { Light.MOONLESS, Light.PITCH_BLACK }) {
            conditions.setLight(preset);
            draw(atmosphere, terrain, batch, List.of(tower), camera, scene,
                  BoardAtmosphere.fromScenario(conditions, false, .5), null, 0);
            assertFalse(atmosphere.lighting().hasDirectLight());
            assertZeroDirectionalLight(terrain);
            assertSame(shadow, terrain.environment().shadowMap, "Dark presets retain the same shadow sampler binding");
            assertEquals(mask, terrain.environment().getMask(), "A preset does not change the surface shader mask");
            assertEquals(flags, GpuEnvironmentLighting.flags(terrain.environment()), "nor the lighting's features");
            assertFalse(terrain.busy(), "A preset must not restart terrain material preparation");
            assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "A preset links no new shader program");
            assertEquals(families, terrain.familyPrograms().programs(), "A preset retains its compiled families");
            int occupied = 0;
            for (byte depth : GpuFamilyAssemblyReview.shadowDepth(terrain)) { occupied |= depth ^ -1; }
            assertEquals(0, occupied, "The retained D24 attachment must be entirely clear, not stale daytime shadows");
            GLProfiler profiler = new GLProfiler(Gdx.graphics);
            profiler.enable();
            try {
                profiler.reset();
                terrain.renderShadows(List.of(tower));
                assertEquals(0, profiler.getCalls(), "An already clear no-source map performs no GL work");
            } finally { profiler.disable(); }
        }
        conditions.setLight(Light.MOONLESS);
        var moonless = BoardAtmosphere.fromScenario(conditions, false, .5);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, moonless, null, 0);
        float[] noMoon = fogPixels(fog);
        boolean nonemptyFog = false;
        for (int i = 3; i < noMoon.length; i += 4) { nonemptyFog |= noMoon[i] < 1; }
        assertTrue(nonemptyFog, "The no-moon branch must still execute an actual fog volume");
        GpuBoardTestUi.capture(new File(output, "hardware-depth-fog-moonless.png"));
        atmosphere.setOptions(new GpuAtmosphere.Options(0, false));
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, moonless, null, 0);
        assertArrayEquals(noMoon, fogPixels(fog), "Without a directional source the rays control is an exact no-op");
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        assertSame(shadow, terrain.environment().shadowMap);
        assertArrayEquals(daylightDepth, GpuFamilyAssemblyReview.shadowDepth(terrain),
              "Returning to daylight refits and restores the actual shadow attachment");
        assertArrayEquals(blocked, fogPixels(fog), "Returning to daylight restores the actual shadowed fog");
        assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Daylight reuses the original programs");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        System.out.println("HARDWARE_DEPTH_FOG attenuatedChannels=" + attenuated
              + " maximumAttenuation=" + maximumAttenuation);
    }

    private static void assertZeroDirectionalLight(GpuTerrain terrain) {
        var lights = terrain.environment().get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
        assertNotNull(lights, "Dark presets retain the directional-light attribute");
        assertEquals(1, lights.lights.size);
        Color color = lights.lights.first().color;
        assertEquals(0, color.r);
        assertEquals(0, color.g);
        assertEquals(0, color.b, "A stable binding must never invent moonlight");
        assertNotNull(terrain.environment().shadowMap);
    }

    private static GpuAtmosphere.Options shadowStrength(float strength) {
        var source = GpuAtmosphere.Options.DEFAULTS;
        return new GpuAtmosphere.Options(source.rays(), source.fixedSun(), source.minCloudShadow(),
              source.maxCloudShadow(), source.sunGlare(), source.fogHeightVariation(), source.fogDensityVariation(),
              source.moonShadowContrast(), source.taintStrength(), source.fogCalmDrift(), source.whiteBalance(),
              source.tint(), source.saturation(), source.contrast(), strength, source.ambientShare(),
              source.skyContribution(), source.environmentImage(), source.lightingSky());
    }

    private static float[] fogPixels(FrameBuffer fog) {
        var viewport = BufferUtils.newIntBuffer(4);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        fog.begin();
        try {
            float[] result = new float[fog.getWidth() * fog.getHeight() * 4];
            var pixels = BufferUtils.newFloatBuffer(result.length);
            Gdx.gl.glReadPixels(0, 0, fog.getWidth(), fog.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            pixels.get(result);
            for (float value : result) { assertTrue(Float.isFinite(value), "Finite actual fog output"); }
            return result;
        } finally {
            fog.end();
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
    }

    private void checkFixedLightingAndMoonModes(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene) throws IOException {
        var originalShadow = terrain.environment().shadowMap;
        var cloudy = new BoardAtmosphere.Settings(13, 0.6f, 0, 2.5f, 0, 0);
        camera.setIsometric(true);
        atmosphere.setOptions(new GpuAtmosphere.Options(0.5f, true));
        Vector3 screenDirection = null;
        for (int turn = 0; turn < 3; turn++) {
            camera.orbit(60, 5);
            camera.fit(scene);
            draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
            var lighting = atmosphere.lighting();
            assertEquals(BoardAtmosphere.lighting(cloudy).adaptation(), atmosphere.exposure(), .00001f,
                  "Orbiting Fixed Sun cannot change canonical exposure");
            var light = terrain.environment().get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
            assertEquals(lighting.direction().cpy().nor(), light.lights.first().direction,
                  "The native directional light normalizes its copied source vector");
            Vector3 screen = lighting.direction().cpy().rot(camera.camera.view);
            if (screenDirection == null) { screenDirection = screen; }
            assertTrue(screenDirection.epsilonEquals(screen, 0.00001f));
            assertSame(originalShadow, terrain.environment().shadowMap, "Fixed lighting reuses the native shadow map");
            var cloud = GpuEnvironmentLighting.cloudShadow(terrain.environment());
            Vector3 point = BoardGeometry.center(new Coords(2, 2), 0);
            Vector3 alongLight = point.cpy().mulAdd(lighting.direction(), 100);
            Vector3 cloudA = point.cpy().mul(cloud.projection), cloudB = alongLight.cpy().mul(cloud.projection);
            assertEquals(cloudA.x, cloudB.x, 0.00001f);
            assertEquals(cloudA.y, cloudB.y, 0.00001f, "Cloud transmission must project along the same camera-relative light");
            Vector3 shadowA = point.cpy().prj(originalShadow.getProjViewTrans());
            Vector3 shadowB = alongLight.cpy().prj(originalShadow.getProjViewTrans());
            assertEquals(shadowA.x, shadowB.x, 0.00001f);
            assertEquals(shadowA.y, shadowB.y, 0.00001f, "Geometry shadows must follow that same light");
            GpuBoardTestUi.capture(new File(output, "atmosphere-fixed-sun-" + turn + ".png"));
        }
        // Exercise the actual compositor owner at grazing sun, where a relative light's incidence meter differs.
        var grazing = new BoardAtmosphere.Settings(6.5f, 0, 0, 2.5f, 0, 0);
        var grazingCamera = new BoardCamera();
        grazingCamera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        boolean differentViewMeter = false;
        atmosphere.configure(grazing);
        for (float tilt : new float[] { 0, 55, BoardCamera.MAX_TILT }) {
            grazingCamera.orbit(47, tilt);
            for (boolean fixed : List.of(false, true)) {
                atmosphere.setOptions(new GpuAtmosphere.Options(.5f, fixed));
                atmosphere.updateLight(grazingCamera);
                assertEquals(BoardAtmosphere.lighting(grazing).adaptation(), atmosphere.exposure(), .00001f,
                      "The real compositor meters world light independently of orbit and Fixed Sun");
                differentViewMeter |= Math.abs(atmosphere.lighting().adaptation() - atmosphere.exposure()) > .01f;
            }
        }
        assertTrue(differentViewMeter, "The fixed-light negative control must exercise a different view incidence");
        atmosphere.setOptions(new GpuAtmosphere.Options(.5f, true));
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        // The same saved toggle is ignored while flying, including views above the horizon. Compare complete
        // rendered frames with it off/on, not merely the boolean policy or a hand-written lighting formula.
        var orbitLight = atmosphere.lighting();
        float orbitExposure = atmosphere.exposure();
        var worldLight = BoardAtmosphere.lighting(cloudy);
        camera.setFirstPerson(true);
        for (float pitch : new float[] { -45, 65, 55 }) {
            camera.look(73, pitch);
            byte[] freeFrame = null;
            for (boolean fixed : List.of(false, true)) {
                atmosphere.setOptions(new GpuAtmosphere.Options(.5f, fixed));
                draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
                var light = atmosphere.lighting();
                assertEquals(worldLight.direction(), light.direction(), "Free Flight keeps a world-relative sun");
                assertEquals(worldLight.direct().r, light.direct().r, .00001f, "No camera-dependent red light gain");
                assertEquals(worldLight.direct().g, light.direct().g, .00001f, "No camera-dependent green light gain");
                assertEquals(worldLight.direct().b, light.direct().b, .00001f, "No camera-dependent blue light gain");
                assertEquals(worldLight.adaptation(), atmosphere.exposure(), .00001f, "Free Flight preserves world exposure");
                var uploaded = terrain.environment().get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
                assertEquals(worldLight.direction().cpy().nor(), uploaded.lights.first().direction,
                      "Terrain receives the normalized world sun");
                assertSame(originalShadow, terrain.environment().shadowMap, "Camera transitions reuse shadow resources");
                byte[] frame = ScreenUtils.getFrameBufferPixels(0, 0,
                      Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
                if (freeFrame == null) { freeFrame = frame; }
                else { assertArrayEquals(freeFrame, frame, "The Fixed Sun toggle has no rendered effect while flying: " + pitch); }
            }
        }
        camera.setFirstPerson(false);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, cloudy, null, 0);
        assertEquals(orbitLight.direction(), atmosphere.lighting().direction(), "Leaving Free Flight restores saved Fixed Sun");
        assertEquals(orbitLight.direct().r, atmosphere.lighting().direct().r, .00001f);
        assertEquals(orbitLight.direct().g, atmosphere.lighting().direct().g, .00001f);
        assertEquals(orbitLight.direct().b, atmosphere.lighting().direct().b, .00001f);
        assertEquals(orbitExposure, atmosphere.exposure(), .00001f);
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        draw(atmosphere, terrain, batch, tower, camera, scene, cloudy);
        assertEquals(BoardAtmosphere.lighting(cloudy).direction(), atmosphere.lighting().direction(),
              "Disabling the option restores the world-space direction");
        var conditions = new PlanetaryConditions();
        conditions.setFog(Fog.FOG_LIGHT);
        atmosphere.setOptions(new GpuAtmosphere.Options(0.5f, true));
        Vector3 nightReceiver = BoardGeometry.center(new Coords(1, 3), 0);
        Color fullMoonPixel = null;
        float moonlessBrightness = 0;
        float[] moonlessSource = null;
        float[] fullMoonFrame = null, moonlessFrame = null;
        StringBuilder nightReport = new StringBuilder(
              "preset,sourceR,sourceG,sourceB,exposure,displayR,displayG,displayB,frameY,skyY,direct,shadow\n");
        for (Light night : new Light[] { Light.FULL_MOON, Light.MOONLESS, Light.PITCH_BLACK, Light.FULL_MOON }) {
            conditions.setLight(night);
            // Hold the existing fog/noise clock fixed while comparing only the night preset.
            draw(atmosphere, terrain, batch, List.of(tower), camera, scene,
                  BoardAtmosphere.fromScenario(conditions, false, 0.5), null, 0);
            boolean moon = night == Light.FULL_MOON;
            assertEquals(moon, atmosphere.lighting().hasDirectLight());
            var lights = terrain.environment().get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
            assertNotNull(lights);
            assertEquals(1, lights.lights.size, "Presets keep a stable directional-light binding");
            if (!moon) { assertZeroDirectionalLight(terrain); }
            assertSame(originalShadow, terrain.environment().shadowMap);
            var cloud = GpuEnvironmentLighting.cloudShadow(terrain.environment());
            assertNotNull(cloud, "Cloud sampler ownership remains stable without a direct light");
            assertEquals(moon, cloud.enabled);
            if (moon) { assertSame(originalShadow, terrain.environment().shadowMap, "Reenabling moonlight reuses its resources"); }
            Color nightPixel = sample(camera, nightReceiver);
            float[] nightSource = sampleScene(atmosphere, camera, nightReceiver);
            float brightness = linearLuminance(nightPixel);
            float[] frame = nightFrameBrightness(atmosphere);
            assertTrue(brightness > 0, "The matched night receiver remains visible");
            if (night == Light.FULL_MOON) {
                if (fullMoonPixel == null) {
                    fullMoonPixel = nightPixel;
                    fullMoonFrame = frame;
                } else {
                    assertEquals(fullMoonPixel, nightPixel, "Restoring full moon restores its composed receiver");
                    assertArrayEquals(fullMoonFrame, frame, "Restoring full moon restores the completed frame");
                }
            } else if (night == Light.MOONLESS) {
                // Individual moon-shadow pixels need not order: compare this whole fixture and its sky.
                assertTrue(frame[0] < fullMoonFrame[0], "Moonless lowers the completed frame's mean luminance");
                assertTrue(frame[1] < fullMoonFrame[1], "Moonless must not brighten the visible sky");
                moonlessFrame = frame;
                moonlessBrightness = brightness;
                moonlessSource = nightSource;
            } else {
                assertTrue(brightness < moonlessBrightness,
                      "Pitch black is a darker presentation of the same moonless source");
                assertTrue(frame[0] > 0 && frame[0] < moonlessFrame[0]);
                assertTrue(frame[1] > 0 && frame[1] < moonlessFrame[1]);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(moonlessSource[channel], nightSource[channel],
                          "Changing night exposure must not change canonical scene light");
                }
            }
            nightReport.append(String.format(Locale.ROOT, "%s,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%s,%s%n",
                  night, nightSource[0], nightSource[1], nightSource[2], atmosphere.exposure(),
                  nightPixel.r, nightPixel.g, nightPixel.b, frame[0], frame[1], atmosphere.lighting().hasDirectLight(),
                  terrain.environment().shadowMap != null));
            GpuBoardTestUi.capture(new File(output, "atmosphere-" + night.name().toLowerCase(Locale.ROOT) + ".png"));
        }
        Files.writeString(new File(output, "night-presets.csv").toPath(), nightReport);
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        camera.setIsometric(false);
        camera.fit(scene);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /** Compare pressure and no-moon illumination on the same gray terrain and full tower. */
    private void checkPressureLighting(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene, Vector3 ground, Vector3 center) throws Exception {
        StringBuilder report = new StringBuilder("hour,pressure,litDisplay,shadeDisplay,decodedShadeToLit\n");
        for (int hour : new int[] { 12, 0 }) {
            float previousRatio = -1;
            for (Atmosphere pressure : Atmosphere.values()) {
                var settings = new BoardAtmosphere.Settings(hour, 0, 0, 1.5f, 0, 0,
                      BoardAtmosphere.Effects.NONE, pressure);
                draw(atmosphere, terrain, batch, tower, camera, scene, settings);
                Color lit = sample(camera, ground);
                Color shade = sample(camera, new Vector3(center).add(0, hour == 12 ? -32 : 32, 0));
                float ratio = linearLuminance(shade) / linearLuminance(lit);
                report.append(String.format(Locale.ROOT, "%d,%s,%.6f,%.6f,%.6f%n",
                      hour, pressure, luminance(lit), luminance(shade), ratio));
                Files.writeString(new File(output, "pressure-lighting.csv").toPath(), report);
                GpuBoardTestUi.capture(new File(output, "atmosphere-" + hour + "-" + pressure + ".png"));
                assertTrue(ratio > previousRatio, "Actual cast-shadow contrast softens with pressure: " + pressure);
                assertTrue(luminance(shade) > .02f, "Even airless shaded gray terrain remains visible");
                previousRatio = ratio;
            }
        }
        report = new StringBuilder("preset,pressure,canonicalR,canonicalG,canonicalB,exposure,displayY\n");
        var conditions = new PlanetaryConditions();
        for (Light preset : new Light[] { Light.MOONLESS, Light.PITCH_BLACK }) {
            conditions.setLight(preset);
            float previous = -1;
            for (Atmosphere pressure : new Atmosphere[] {
                  Atmosphere.VACUUM, Atmosphere.TRACE, Atmosphere.THIN, Atmosphere.STANDARD }) {
                conditions.setAtmosphere(pressure);
                draw(atmosphere, terrain, batch, tower, camera, scene,
                      BoardAtmosphere.fromScenario(conditions, false, .5));
                float brightness = luminance(sample(camera, ground));
                float[] source = sampleScene(atmosphere, camera, ground);
                report.append(String.format(Locale.ROOT, "%s,%s,%.9g,%.9g,%.9g,%.9g,%.6f%n",
                      preset, pressure, source[0], source[1], source[2], atmosphere.exposure(), brightness));
                Files.writeString(new File(output, "pressure-darkness.csv").toPath(), report);
                GpuBoardTestUi.capture(new File(output, "atmosphere-" + preset + "-" + pressure + ".png"));
                assertFalse(atmosphere.lighting().hasDirectLight());
                assertZeroDirectionalLight(terrain);
                assertTrue(brightness > .04f, "Actual airless gray terrain remains navigable");
                assertTrue(brightness > previous, "Vacuum < trace < thin < standard readable terrain");
                previous = brightness;
            }
        }
    }

    /** Compare the same receivers with/without a small caster so the entire long shadow fits on the fixture. */
    private void checkTwilightShadows(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene) throws IOException {
        float height = 36;
        Vector3 center = BoardGeometry.center(new Coords(3, 3), 0);
        ModelInstance caster = new ModelInstance(tower.model);
        caster.transform.setToTranslation(center.x, center.y, height / 2).scale(0.35f, 0.35f, height / 108);
        StringBuilder report = new StringBuilder("Rendered twilight shadows: identical ground probes with/without a 36-unit caster\n");
        try {
            for (boolean fixed : new boolean[] { false, true }) {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, fixed));
                for (boolean isometric : new boolean[] { false, true }) {
                    camera.setIsometric(isometric);
                    camera.fit(scene);
                    for (float hour : new float[] { 6, 6.25f, 6.5f, 6.75f, 17.25f, 17.5f, 17.75f, 18 }) {
                        var settings = new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0);
                        draw(atmosphere, terrain, batch, List.of(), camera, scene, settings, null, 0);
                        var lighting = atmosphere.lighting();
                        assertTrue(lighting.sunlight());
                        Vector3 tail = new Vector3(lighting.direction()).scl(height / -lighting.direction().z);
                        tail.z = 0;
                        if (!fixed) {
                            assertTrue(tail.len() > height * 4, "The rendered source must cast a long twilight shadow");
                        }
                        Vector3 inside = center.cpy().mulAdd(tail, 0.7f);
                        Vector3 beyond = center.cpy().add(tail).mulAdd(tail.cpy().nor(), 25);
                        Color lit = sample(camera, inside);
                        Color outside = sample(camera, beyond);
                        draw(atmosphere, terrain, batch, List.of(caster), camera, scene, settings, null, 0);
                        float ratio = luminance(sample(camera, inside)) / luminance(lit);
                        String label = hour + ", fixed=" + fixed + ", isometric=" + isometric;
                        assertTrue(ratio < 0.87f && ratio > 0.5f,
                              "Twilight must cast a visibly shaded, readable trail at " + label + ": " + ratio);
                        assertTrue(difference(outside, sample(camera, beyond)) < 0.025f,
                              "The shadow must end at the source's projected length, not shade all ground: " + label);
                        report.append(String.format(Locale.ROOT, "%s: length/height=%.2f, shadow/lit=%.3f%n",
                              label, tail.len() / height, ratio));
                        if (!fixed && (hour == 6 || hour == 18)) {
                            GpuBoardTestUi.capture(new File(output, "twilight-shadow-" + hour + "-" + (isometric ? "iso" : "top") + ".png"));
                        }
                    }
                }
            }
            Files.writeString(new File(output, "twilight-shadow-checks.txt").toPath(), report);
        } finally {
            atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
            camera.setIsometric(false);
            camera.fit(scene);
        }
    }

    private void checkTwilightAndCloudPalette(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene) throws IOException {
        Vector3 ground = BoardGeometry.center(new Coords(1, 3), 0);
        for (float hour : new float[] { 0, 6, 6.75f, 12, 17.25f, 18 }) {
            var clear = new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0);
            draw(atmosphere, terrain, batch, List.of(), camera, scene, clear, null, 0);
            int[] sky = backgroundColumn();
            Color low = new Color(sky[sky.length / 10]);
            Color high = new Color(sky[sky.length * 9 / 10]);
            if (hour > 0 && hour != 12) {
                float[] surface = sampleScene(atmosphere, camera, ground);
                // The upper sky can keep the total surface cool. Isolate the directional sun against the same
                // sky/BRDF, on a receiver without a caster, instead of prescribing the combined palette.
                var lighting = atmosphere.lighting();
                Color originalDirect = new Color(lighting.direct());
                try {
                    lighting.direct().set(0, 0, 0, 1);
                    draw(atmosphere, terrain, batch, List.of(), camera, scene, clear, null, 0);
                    float[] fill = sampleScene(atmosphere, camera, ground);
                    double red = surface[0] - fill[0], blue = surface[2] - fill[2];
                    double redError = sceneRounding(surface[0]) + sceneRounding(fill[0]);
                    double blueError = sceneRounding(surface[2]) + sceneRounding(fill[2]);
                    System.out.printf(Locale.ROOT, "TWILIGHT_SOURCE hour=%.2f red=%.9g blue=%.9g "
                                + "redBound=%.9g blueBound=%.9g%n", hour, red, blue, redError, blueError);
                    assertTrue(red > redError && red - redError > 1.5 * Math.max(0, blue + blueError),
                          "Canonical twilight sunlight must be positive beyond quantization and warm at " + hour
                                + ": red=" + red + ", blue=" + blue);
                    // Source removal changes the final meter. No inverse display transfer can isolate that source.
                } finally {
                    lighting.direct().set(originalDirect);
                    terrain.setAtmosphere(lighting);
                }
                assertTrue(low.r > low.b * 1.5f, "The rendered twilight horizon must be warm: " + low);
                assertTrue(high.b > high.r, "The upper twilight backdrop must retain the cool sky: " + high);
            }
            draw(atmosphere, terrain, batch, List.of(tower), camera, scene, clear, null, 0);
            GpuBoardTestUi.capture(new File(output, "atmosphere-clock-" + hour + ".png"));
            Color previousLow = low, previousHigh = high;
            for (float cover : new float[] { 0.25f, 0.6f, 1 }) {
                draw(atmosphere, terrain, batch, List.of(tower), camera, scene,
                      new BoardAtmosphere.Settings(hour, cover, 0, 2.5f, 0, 0), null, 0);
                int[] cloudy = backgroundColumn();
                Color cloudyLow = new Color(cloudy[cloudy.length / 10]);
                Color cloudyHigh = new Color(cloudy[cloudy.length * 9 / 10]);
                float change = difference(previousHigh, cloudyHigh) + difference(previousLow, cloudyLow);
                assertTrue(change > .01f,
                      "Each cloud-cover change must visibly affect the sky at " + hour + "/" + cover + ": " + change);
                assertTrue(difference(cloudyLow, cloudyHigh) > .01f,
                      "Cloud cover must retain the backdrop gradient at " + hour + "/" + cover);
                // Cover changes both the palette and its energy normalization. Display-space distances from
                // the clear sky need not grow monotonically or be larger overhead; the numerical probe below
                // checks the actual normalized endpoints without prescribing a different lighting model.
                previousLow = cloudyLow;
                previousHigh = cloudyHigh;
            }
            GpuBoardTestUi.capture(new File(output, "atmosphere-clock-overcast-" + hour + ".png"));
        }
    }

    /** Exercise the real atlas shader at a fixed density field, independently of surface texture colors. */
    private void checkCloudOpacity(BoardScene scene) throws ReflectiveOperationException {
        var quad = GpuAtmosphere.screenQuad();
        var clouds = new GpuClouds(quad);
        try {
            var settings = new BoardAtmosphere.Settings(13, 0.65f, 0, 2.5f, 0, 0);
            var lighting = BoardAtmosphere.lighting(settings);
            var field = GpuClouds.class.getDeclaredField("shadow");
            field.setAccessible(true);
            var buffer = (FrameBuffer) field.get(clouds);
            assertEquals(192, buffer.getWidth());
            var texture = clouds.shadow().texture;
            int[] opaque = null;
            for (float strength : new float[] { 1, 0.2f, 0 }) {
                clouds.update(settings, lighting, scene, 0, strength);
                assertSame(texture, clouds.shadow().texture, "Strength changes reuse the fixed Low atlas");
                buffer.begin();
                Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, buffer.getWidth(), buffer.getHeight());
                buffer.end();
                try {
                    int[] transmission = new int[pixels.getWidth() * pixels.getHeight()];
                    int darkest = 255;
                    for (int index = 0; index < transmission.length; index++) {
                        int value = pixels.getPixel(index % pixels.getWidth(), index / pixels.getWidth()) >>> 24;
                        transmission[index] = value;
                        darkest = Math.min(darkest, value);
                        if (opaque != null) {
                            assertEquals(255 - (255 - opaque[index]) * strength, value, 1.1f,
                                  "Strength scales opacity while retaining the same cloud shapes and clear openings");
                        }
                    }
                    if (opaque == null) {
                        assertTrue(darkest < 220, "The fixed field must contain cloud shadows");
                        opaque = transmission;
                    }
                } finally {
                    pixels.dispose();
                }
            }
        } finally {
            clouds.dispose();
            quad.dispose();
        }
    }

    /** First clear/cloudy/clear draws must reuse surface programs, before other cloud checks can warm them. */
    private void checkCloudVariantReuse(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene, BoardAtmosphere.Settings clear) {
        var cloud = GpuEnvironmentLighting.cloudShadow(terrain.environment());
        assertNotNull(cloud);
        assertFalse(cloud.enabled);
        long mask = terrain.environment().getMask();
        int flags = GpuEnvironmentLighting.flags(terrain.environment());
        int programs = ShaderProgram.getNumManagedShaderPrograms();
        Vector3 ground = BoardGeometry.center(new Coords(1, 3), 0);
        Color clearGround = sample(camera, ground);
        var covered = new BoardAtmosphere.Settings(clear.hour(), 1, clear.fog(), clear.groundLayerHeight(),
              clear.haze(), clear.exposure());
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, covered, null, 0);
        assertSame(cloud, GpuEnvironmentLighting.cloudShadow(terrain.environment()));
        assertTrue(cloud.enabled);
        assertEquals(mask, terrain.environment().getMask(), "Cloud cover cannot change the surface shader mask");
        assertEquals(flags, GpuEnvironmentLighting.flags(terrain.environment()), "nor the lighting's features");
        assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(),
              "First cloud cover must reuse the programs prepared by the clear frame");
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, clear, null, 0);
        assertSame(cloud, GpuEnvironmentLighting.cloudShadow(terrain.environment()));
        assertFalse(cloud.enabled);
        assertEquals(mask, terrain.environment().getMask());
        assertEquals(flags, GpuEnvironmentLighting.flags(terrain.environment()));
        assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Clearing clouds reuses those programs");
        assertEquals(clearGround, sample(camera, ground), "Disabled transmission restores the clear surface exactly");
    }

    private void checkClouds(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera, BoardScene scene) throws IOException {
        checkCloudSurfaceLighting(atmosphere, terrain, batch, tower, camera, scene);
        var backwards = new BoardAtmosphere.Settings(13, 0.65f, 0, 2.5f, 0, 0,
              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 225));
        List<Vector3> wrapProbes = List.of(BoardGeometry.center(new Coords(0, 3), 0),
              BoardGeometry.center(new Coords(1, 3), 0), BoardGeometry.center(new Coords(5, 5), 0));
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, backwards, null, 0);
        List<Color> beforeWrap = wrapProbes.stream().map(point -> sample(camera, point)).toList();
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, backwards, null, 0.0001f);
        for (int index = 0; index < wrapProbes.size(); index++) {
            assertTrue(difference(beforeWrap.get(index), sample(camera, wrapProbes.get(index))) < 0.025f,
                  "The first negative wind step must wrap the noise without teleporting the cloud shadows");
        }
        var wind = new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 90);
        var broken = new BoardAtmosphere.Settings(13, 0.6f, 0, 2.5f, 0.15f, 0, wind);
        camera.setIsometric(true);
        camera.fit(scene);
        draw(atmosphere, terrain, batch, tower, camera, scene, broken);
        assertNotNull(atmosphere.depthTexture(), "Cloud shafts must stop at captured opaque depth");
        GpuCloudShadow cloud = GpuEnvironmentLighting.cloudShadow(terrain.environment());
        assertNotNull(cloud, "Terrain and units must share the cloud transmission map");
        assertTrue(cloud.enabled);
        var texture = cloud.texture;
        var projection = cloud.projection.cpy();
        int[] sky = backgroundColumn();
        long still = GpuBoardTestUi.capture(new File(output, "clouds-broken.png"));
        for (int frame = 0; frame < 60; frame++) {
            draw(atmosphere, terrain, batch, tower, camera, scene, broken);
        }
        assertNotEquals(still, GpuBoardTestUi.capture(new File(output, "clouds-wind.png")),
              "Wind must move cloud lighting across the board");
        assertArrayEquals(sky, backgroundColumn(), "The sky stays a smooth color gradient while cloud shadows move");
        for (int y = 1; y < sky.length; y++) {
            assertTrue(difference(new Color(sky[y - 1]), new Color(sky[y])) < 0.025f,
                  "The backdrop must have no cloud texture or hard color transitions");
        }
        assertSame(texture, GpuEnvironmentLighting.cloudShadow(terrain.environment()).texture,
              "Animation must reuse the cloud atlas");
        camera.setIsometric(false);
        camera.fit(scene);
        draw(atmosphere, terrain, batch, tower, camera, scene, broken);
        assertArrayEquals(projection.val, cloud.projection.val, "Camera changes must not move or rescale world-space shadows");
        GpuBoardTestUi.capture(new File(output, "clouds-top.png"));
        float darkest = 1, brightest = 0;
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                if (x == 3 || x == 1 && y == 2) { continue; }
                float value = luminance(sample(camera, BoardGeometry.center(new Coords(x, y), 0)));
                darkest = Math.min(darkest, value);
                brightest = Math.max(brightest, value);
            }
        }
        assertTrue(brightest - darkest > 0.025f, "Broken clouds must create distinct lit and shaded patches");
        camera.setIsometric(true);
        camera.fit(scene);
        var controlled = new BoardAtmosphere.Settings(13, 0.85f, 0, 2.5f, 0, 0);
        atmosphere.setOptions(new GpuAtmosphere.Options(0, false));
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, controlled, null, 0);
        var depth = atmosphere.depthTexture();
        long withoutRays = GpuBoardTestUi.capture(new File(output, "clouds-rays-off.png"));
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        draw(atmosphere, terrain, batch, List.of(tower), camera, scene, controlled, null, 0);
        assertSame(depth, atmosphere.depthTexture(), "Rays reuse scene depth without an extra depth target");
        assertNotEquals(withoutRays, GpuBoardTestUi.capture(new File(output, "clouds-rays-default.png")));
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(13, 0.65f, 0, 2.5f, 0, 0, wind, Atmosphere.THIN));
        GpuBoardTestUi.capture(new File(output, "clouds-thin.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(16, 0.65f, 0.05f, 2.5f, 0.35f, 0, wind));
        GpuBoardTestUi.capture(new File(output, "clouds-afternoon-shafts.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene, overcast(broken));
        GpuBoardTestUi.capture(new File(output, "clouds-overcast.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(13, 1, 0.1f, 2.5f, 0.2f, 0,
                    new BoardAtmosphere.Effects(1, 0, 0, 0, 0.65f, 0.8f, 60)));
        GpuBoardTestUi.capture(new File(output, "clouds-storm.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(0, 0.6f, 0, 2.5f, 0, 0));
        assertSame(depth, atmosphere.depthTexture(), "Night reuses the same hardware scene depth");
        GpuBoardTestUi.capture(new File(output, "clouds-night.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
        GpuBoardTestUi.capture(new File(output, "clouds-clear.png"));
        Vector3 ground = BoardGeometry.center(new Coords(1, 3), 0);
        assertSame(cloud, GpuEnvironmentLighting.cloudShadow(terrain.environment()));
        assertFalse(cloud.enabled, "Clear weather keeps the sampler but bypasses its stale cloudy texels");
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0, BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM));
        Color clearVacuum = sample(camera, ground);
        draw(atmosphere, terrain, batch, tower, camera, scene,
              new BoardAtmosphere.Settings(13, 1, 1, 2.5f, 1, 0, wind, Atmosphere.VACUUM));
        GpuBoardTestUi.capture(new File(output, "clouds-vacuum.png"));
        assertEquals(clearVacuum, sample(camera, ground),
              "Vacuum must suppress clouds, haze and weather without changing the same airless surface light");
        assertTrue(atmosphere.lighting().sky().b < 0.01f, "An airless world has no blue atmospheric sky");
        assertSame(cloud, GpuEnvironmentLighting.cloudShadow(terrain.environment()));
        assertFalse(cloud.enabled, "Vacuum keeps cloud transmission disabled");
        assertSame(depth, atmosphere.depthTexture());
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        camera.setIsometric(false);
        camera.fit(scene);
    }

    private void checkCloudSurfaceLighting(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch,
          ModelInstance tower, BoardCamera camera, BoardScene scene) {
        Pixmap pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setColor(Color.WHITE);
        pixels.fill();
        Texture texture = new Texture(pixels);
        Matrix4 projection = new Matrix4().setToScaling(0, 0, 0);
        projection.setTranslation(0.5f, 0.5f, -1);
        GpuCloudShadow cloud = new GpuCloudShadow(texture, projection);
        List<Vector3> probes = List.of(BoardGeometry.center(new Coords(0, 3), 0),
              BoardGeometry.center(new Coords(1, 3), 0), BoardGeometry.center(new Coords(3, 3), 0).add(0, 0, 108));
        boolean isometric = camera.isIsometric();
        try {
            var clear = BoardAtmosphere.DEFAULTS;
            draw(atmosphere, terrain, batch, tower, camera, scene, clear);
            List<Color> lit = probes.stream().map(point -> sample(camera, point)).toList();
            draw(atmosphere, terrain, batch, tower, camera, scene, clear, cloud);
            for (int i = 0; i < probes.size(); i++) {
                assertEquals(lit.get(i), sample(camera, probes.get(i)),
                      "An open cloud column must leave native ground/normal-map/unit lighting unchanged");
            }
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false));
            draw(atmosphere, terrain, batch, tower, camera, scene,
                  new BoardAtmosphere.Settings(13, 1, 0, 2.5f, 0, 0), cloud);
            for (int i = 0; i < probes.size(); i++) {
                assertEquals(1, linearLuminance(sample(camera, probes.get(i))) / linearLuminance(lit.get(i)), .04f,
                      "Cloud cover may change sky chromaticity, but must not globally dim an open sun column");
            }
            pixels.setColor(Color.BLACK);
            pixels.fill();
            texture.draw(pixels, 0, 0);
            cloud.enabled = false;
            draw(atmosphere, terrain, batch, tower, camera, scene, clear, cloud);
            for (int i = 0; i < probes.size(); i++) {
                assertEquals(lit.get(i), sample(camera, probes.get(i)),
                      "Disabled transmission must ignore even an opaque cloud atlas");
            }
            cloud.enabled = true;
            draw(atmosphere, terrain, batch, tower, camera, scene, clear, cloud);
            var lighting = atmosphere.lighting();
            for (int i = 0; i < probes.size(); i++) {
                float share = linearLuminance(sample(camera, probes.get(i))) / linearLuminance(lit.get(i));
                assertTrue(share < .9f,
                      "An opaque cloud must visibly remove direct light on all surface shaders: " + share);
            }
            // An opaque cloud retains the defined .003 transmission floor. Measure the real canonical scene:
            // removing the directional source also changes final adaptation, so display differences are not additive.
            // This includes the wall's ground bounce and GGX response under the same sky/material/camera.
            camera.setIsometric(true);
            camera.fit(scene);
            Vector3 view = camera.camera.direction;
            Vector3 side = Math.abs(view.x) > Math.abs(view.y) ? new Vector3(-Math.signum(view.x), 0, 0)
                  : new Vector3(0, -Math.signum(view.y), 0);
            Vector3 wall = BoardGeometry.center(new Coords(3, 3), 0).mulAdd(side, 18.5f).add(0, 0, 54);
            List<Vector3> shadedProbes = new ArrayList<>(probes);
            shadedProbes.add(wall);
            draw(atmosphere, terrain, batch, tower, camera, scene, clear);
            List<float[]> full = shadedProbes.stream().map(point -> sampleScene(atmosphere, camera, point)).toList();
            draw(atmosphere, terrain, batch, tower, camera, scene, clear, cloud);
            List<float[]> shaded = shadedProbes.stream().map(point -> sampleScene(atmosphere, camera, point)).toList();
            Color originalDirect = new Color(lighting.direct());
            try {
                lighting.direct().set(0, 0, 0, 1);
                draw(atmosphere, terrain, batch, tower, camera, scene, clear);
                for (int i = 0; i < shadedProbes.size(); i++) {
                    float[] off = sampleScene(atmosphere, camera, shadedProbes.get(i));
                    for (int channel = 0; channel < 3; channel++) {
                        double expected = off[channel] + .003 * (full.get(i)[channel] - off[channel]);
                        double error = sceneRounding(shaded.get(i)[channel]) + .997 * sceneRounding(off[channel])
                              + .003 * sceneRounding(full.get(i)[channel]);
                        System.out.printf(Locale.ROOT, "CLOUD_SOURCE probe=%d channel=%d full=%.9g shaded=%.9g "
                                    + "off=%.9g expected=%.9g bound=%.9g%n", i, channel, full.get(i)[channel],
                              shaded.get(i)[channel], off[channel], expected, error);
                        assertEquals(expected, shaded.get(i)[channel], error,
                              "Cloud/source-off canonical mixture at probe " + i + ", channel " + channel);
                    }
                }
            } finally {
                lighting.direct().set(originalDirect);
                terrain.setAtmosphere(lighting);
            }
        } finally {
            camera.setIsometric(isometric);
            camera.fit(scene);
            atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
            atmosphere.configureClouds(terrain, scene);
            texture.dispose();
            pixels.dispose();
        }
    }

    private void checkWetGround(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera, BoardScene scene) throws IOException {
        // Rain wets Enhanced's ground materials; Classic's tileset art stays dry.
        terrain.setColumns(false);
        terrain.update(scene);
        camera.setIsometric(false);
        camera.fit(scene);
        Vector3 plain = BoardGeometry.center(new Coords(1, 3), 0);
        // Use an interior normal-mapped tile; the outer cliff rim has its own material response.
        Vector3 normal = BoardGeometry.center(new Coords(2, 1), 0);
        Vector3 roof = BoardGeometry.center(new Coords(3, 3), 0).add(0, 0, 108);
        draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
        Color dryPlain = sample(camera, plain), dryNormal = sample(camera, normal), dryRoof = sample(camera, roof);
        var rain = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0,
              new BoardAtmosphere.Effects(1, 0, 0, 0, 0, 0, 0));
        drawScene(atmosphere, terrain, batch, List.of(tower), camera, scene, rain, null, 0);
        Color wetPlain = sample(camera, plain);
        // Rain darkens the ground's albedo by a quarter to a half of the light it returns (rain-surface.glsl wetAlbedo);
        // the wet film's sheen gives a little back. Measure linear radiance: display encoding compresses the change.
        assertTrue(linearLuminance(wetPlain) < linearLuminance(dryPlain) * 0.9f,
              "Wet ground darkens its albedo: " + dryPlain + " -> " + wetPlain);
        assertTrue(linearLuminance(sample(camera, normal)) < linearLuminance(dryNormal) * 0.97f,
              "Wetness also reaches normal-mapped ground with its independently tuned material response");
        assertEquals(dryRoof, sample(camera, roof), "Ground wetness must not tint unrelated unit materials");
        GpuBoardTestUi.capture(new File(output, "terrain-wet.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
        assertEquals(dryPlain, sample(camera, plain), "Stopping rain removes the wet material response immediately");
        var lunarDry = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM);
        draw(atmosphere, terrain, batch, tower, camera, scene, lunarDry);
        Color dryLunarGround = sample(camera, plain);
        var lunar = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0, rain.effects(), Atmosphere.VACUUM);
        draw(atmosphere, terrain, batch, tower, camera, scene, lunar);
        assertEquals(dryLunarGround, sample(camera, plain), "Airless conditions suppress rain and wet ground together");
        var snowTiles = scene.tiles().stream().map(tile -> BoardScenes.newTile(tile.coords(), 0, -1, false, 0,
              BoardScene.Surface.SNOW, List.of(), List.of())).toList();
        var snow = new BoardScene(0, 7, 7, snowTiles, List.of(), List.of(), -1, "", List.of());
        terrain.update(snow);
        draw(atmosphere, terrain, batch, tower, camera, snow, BoardAtmosphere.DEFAULTS);
        Color frozenGround = sample(camera, plain);
        drawScene(atmosphere, terrain, batch, List.of(tower), camera, snow, rain, null, 0);
        assertEquals(frozenGround, sample(camera, plain), "Snow artwork must not acquire a liquid-water sheen");
        var pavementTiles = scene.tiles().stream().map(tile -> BoardScenes.newTile(tile.coords(), 0, -1, false, 0,
              BoardScene.Surface.CONCRETE, List.of(), List.of())).toList();
        var pavement = new BoardScene(0, 7, 7, pavementTiles, List.of(), List.of(), -1, "", List.of());
        terrain.update(pavement);
        Vector3 sun = atmosphere.lighting().direction().cpy().scl(-1);
        // Observe a horizontal water film at the reflected sun direction, then rotate away at the same tilt.
        camera.orbit((float) Math.toDegrees(Math.atan2(-sun.x, sun.y)), (float) Math.toDegrees(Math.acos(sun.z)));
        camera.fit(pavement);
        drawScene(atmosphere, terrain, batch, List.of(tower), camera, pavement, rain, null, 0);
        Color highlight = sample(camera, plain);
        GpuBoardTestUi.capture(new File(output, "terrain-wet-sun-reflection.png"));
        camera.orbit(90, 0);
        camera.fit(pavement);
        drawScene(atmosphere, terrain, batch, List.of(tower), camera, pavement, rain, null, 0);
        assertTrue(luminance(highlight) > luminance(sample(camera, plain)) + 0.035f,
              "Wet pavement must reflect the sun as the viewing angle changes, not merely become darker");
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        camera.setIsometric(false);
        camera.fit(scene);
        terrain.setColumns(true);
        terrain.update(scene);
    }

    /** A reproducible larger workload, not a whole-game FPS claim: static terrain and 64 simple lit casters. */
    private void checkLargeBoard(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera) throws IOException {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        List<ModelInstance> units = new ArrayList<>();
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                      List.of(), List.of()));
                if (x % 8 == 4 && y % 8 == 4) {
                    ModelInstance unit = new ModelInstance(tower.model);
                    unit.transform.setToTranslation(BoardGeometry.center(new Coords(x, y), 0).add(0, 0, 54));
                    units.add(unit);
                }
            }
        }
        var scene = new BoardScene(0, 64, 64, tiles, List.of(), List.of(), -1, "", List.of());
        terrain.update(scene);
        StringBuilder report = new StringBuilder("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
              + "\n1280x800; synthetic 64x64 board, 64 simple casters; warmed 15 frames, sampled 30; glFinish; no vsync\n");
        var broken = new BoardAtmosphere.Settings(13, 0.65f, 0, 2.5f, 0.15f, 0,
              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0.4f, 60));
        for (boolean isometric : new boolean[] { false, true }) {
            camera.setIsometric(isometric);
            camera.fit(scene);
            for (var settings : List.of(BoardAtmosphere.DEFAULTS, broken, overcast(broken))) {
                List<Double> times = new ArrayList<>();
                for (int frame = 0; frame < 45; frame++) {
                    long start = System.nanoTime();
                    draw(atmosphere, terrain, batch, units, camera, scene, settings, null, 1f / 60);
                    Gdx.gl.glFinish();
                    if (frame >= 15) { times.add((System.nanoTime() - start) / 1_000_000.0); }
                }
                times.sort(Double::compareTo);
                report.append(String.format(Locale.ROOT, "%s cover %.2f: median %.3f ms, p95 %.3f ms%n",
                      isometric ? "Orbit" : "Top", settings.clouds(), times.get(15), times.get(28)));
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                if (settings.clouds() > 0) {
                    var atlas = GpuEnvironmentLighting.cloudShadow(terrain.environment());
                    assertEquals(192, atlas.texture.getWidth(), "Cloud shadows always use the Low budget");
                    var previous = atlas.texture;
                    draw(atmosphere, terrain, batch, units, camera, scene, settings, null, 0);
                    assertSame(previous, GpuEnvironmentLighting.cloudShadow(terrain.environment()).texture,
                          "Animation and camera changes reuse the fixed atlas");
                }
                if (isometric && settings.clouds() == broken.clouds()) {
                    GpuBoardTestUi.capture(new File(output, "clouds-large-low.png"));
                }
            }
        }
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        Files.writeString(new File(output, "cloud-shadow-timing.txt").toPath(), report);
    }

    private static BoardAtmosphere.Settings overcast(BoardAtmosphere.Settings current) {
        return new BoardAtmosphere.Settings(current.hour(), 1, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0.02f,
              current.exposure(), new BoardAtmosphere.Effects(0, 0, 0, 0, 0, current.effects().wind(),
              current.effects().windDirection()), current.pressure(), current.temperature(), current.moonlight(), current.taint());
    }

    private int[] backgroundColumn() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(5, 5, 1, Gdx.graphics.getBackBufferHeight() - 10);
        try {
            int[] result = new int[pixels.getHeight()];
            for (int y = 0; y < result.length; y++) { result[y] = pixels.getPixel(0, y); }
            return result;
        } finally {
            pixels.dispose();
        }
    }

    /** Incident-sky debug follows world rays; the authored UI backdrop keeps its screen gradient and EV response. */
    private void checkSkyDirections() {
        record Palette(Color sky, Color horizon, String label, float exposure, float backdropExposure) {
            Palette(Color sky, Color horizon, String label) { this(sky, horizon, label, 1, 0); }
        }
        var palettes = new ArrayList<Palette>();
        palettes.add(new Palette(new Color(.4f, .3f, .2f, 1), new Color(.1f, .15f, .18f, 1), "known endpoints"));
        for (float hour : new float[] { 0, 6, 6.75f, 12, 17.25f, 18 }) {
            for (float cover : new float[] { 0, .25f, .6f, 1 }) {
                var lighting = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, cover, 0, 2.5f, 0, 0));
                palettes.add(new Palette(lighting.sky(), lighting.horizon(), "hour=" + hour + ", cover=" + cover));
            }
        }
        // Actual palette owners, with independent reference-UI gamma/shoulder math below. Adaptation is
        // deliberately far from one; the backdrop must follow user EV, not scene illumination normalization.
        for (float hour : new float[] { 0, 6, 12, 18 }) {
            for (var taint : AtmosphericTaint.values()) {
                var settings = new BoardAtmosphere.Settings(hour, .6f, 0, 2.5f, 0, 0,
                      BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, true, taint);
                var palette = BoardAtmosphere.skyPalette(settings, BoardAtmosphere.DEFAULT_TAINT_STRENGTH, false);
                for (float ev : new float[] { .25f, 1, 4 }) {
                    palettes.add(new Palette(palette.sky(), palette.horizon(),
                          "UI hour=" + hour + ", taint=" + taint + ", EV gain=" + ev, ev * 17, ev));
                }
            }
        }
        var vacuum = BoardAtmosphere.skyPalette(new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0,
              BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM), 1, false);
        palettes.add(new Palette(vacuum.sky(), vacuum.horizon(), "UI vacuum", 17, 1));
        var shader = GpuAtmosphere.shader("atmosphere-composite.frag");
        var quad = GpuAtmosphere.screenQuad();
        var output = GpuHeatGlow.buffer(1, 3);
        var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixels.setColor(Color.WHITE); pixels.fill();
        var depth = new Texture(pixels);
        pixels.dispose();
        try {
            output.begin();
            GpuAtmosphere.screenState();
            shader.bind();
            shader.setUniformi("u_scene", 0);
            shader.setUniformi("u_depth", 1);
            shader.setUniformi("u_waterDepth", 1);
            shader.setUniformf("u_exposure", 1);
            shader.setUniformf("u_tint", 1, 1, 1);
            shader.setUniformf("u_saturation", 1);
            var sample = BufferUtils.newFloatBuffer(4);
            double maximumError = 0;
            int maximumCodeError = 0, authoredSamples = 0;
            for (Palette palette : palettes) {
                float[] sky = { palette.sky().r, palette.sky().g, palette.sky().b };
                float[] horizon = { palette.horizon().r, palette.horizon().g, palette.horizon().b };
                var clear = BufferUtils.newFloatBuffer(4);
                clear.put(new float[] { sky[0] * GpuSceneRadiance.SCALE, sky[1] * GpuSceneRadiance.SCALE,
                      sky[2] * GpuSceneRadiance.SCALE, 0 }).flip();
                var scene = GpuColorTexture.linear(clear, 1, 1, false);
                try {
                    scene.bind(0); depth.bind(1);
                    for (int channel = 0; channel < 3; channel++) {
                        float stored = Float.float16ToFloat(Float.floatToFloat16(sky[channel] * GpuSceneRadiance.SCALE));
                        clear.put(channel, stored / GpuSceneRadiance.SCALE);
                    }
                    shader.setUniformf("u_sky", clear.get(0), clear.get(1), clear.get(2));
                    shader.setUniformf("u_horizon", horizon[0], horizon[1], horizon[2]);
                    shader.setUniformf("u_exposure", palette.exposure());
                    shader.setUniformf("u_backdropExposure", palette.backdropExposure());
                    shader.setUniformf("u_backdropSky", sky[0], sky[1], sky[2]);
                    shader.setUniformf("u_backdropHorizon", horizon[0], horizon[1], horizon[2]);
                    for (Camera camera : new Camera[] { new OrthographicCamera(2, 2), new PerspectiveCamera(60, 2, 2) }) {
                        camera.near = .1f; camera.far = 100;
                        for (float elevation : new float[] { -1, 0, .25f, 1 }) {
                            camera.direction.set((float) Math.sqrt(1 - elevation * elevation), 0, elevation);
                            camera.up.set(0, 1, 0);
                            camera.update();
                            shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
                            shader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22],
                                  camera.projection.val[Matrix4.M23], camera.projection.val[Matrix4.M32],
                                  camera.projection.val[Matrix4.M33]);
                            quad.render(shader, GL20.GL_TRIANGLES);
                            boolean parallel = camera instanceof OrthographicCamera;
                            // The authored backdrop must match in all rows for both projection types. The
                            // incident-sky center perspective ray has the explicit world elevation set above.
                            boolean authored = palette.backdropExposure() > 0;
                            boolean allRows = parallel || authored;
                            for (int row = allRows ? 0 : 1; row <= (allRows ? 2 : 1); row++) {
                                sample.clear();
                                Gdx.gl.glReadPixels(0, row, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, sample);
                                double y = (row + .5) / 3;
                                double weight = allRows ? y * y * (3 - 2 * y) : Math.sqrt(Math.max(0, elevation));
                                for (int channel = 0; channel < 3; channel++) {
                                    double radiance = horizon[channel] + (sky[channel] - horizon[channel]) * weight;
                                    if (authored) { radiance = Math.pow(radiance, 2.2) * palette.backdropExposure(); }
                                    // Independent reference: the actual UI uses gamma 2.2 and EV alone. The
                                    // incident-sky debug instead follows the current scene-linear display path.
                                    double shoulder = Math.min(radiance, .6)
                                          + .4 * (1 - Math.exp(-Math.max(0, radiance - .6) / .4));
                                    double display = authored ? Math.pow(shoulder, 1 / 2.2)
                                          : shoulder <= .0031308 ? shoulder * 12.92
                                          : 1.055 * Math.pow(shoulder, 1 / 2.4) - .055;
                                    double expected = display;
                                    String label = palette.label() + ", " + camera.getClass().getSimpleName()
                                          + ", ray=" + elevation + ", row=" + row + ", channel=" + channel;
                                    assertEquals(expected, sample.get(channel), .0015, label);
                                    if (authored) {
                                        double error = Math.abs(expected - sample.get(channel));
                                        int codeError = (int) Math.abs(Math.round(expected * 255)
                                              - Math.round(sample.get(channel) * 255));
                                        maximumError = Math.max(maximumError, error);
                                        maximumCodeError = Math.max(maximumCodeError, codeError);
                                        authoredSamples++;
                                        assertTrue(codeError <= 1, label + ", PNG8 code difference=" + codeError);
                                    }
                                }
                                assertEquals(1, sample.get(3), 0, "The ordinary sky backdrop remains opaque");
                            }
                        }
                    }
                } finally { scene.dispose(); }
            }
            System.out.printf(Locale.ROOT, "UI_BACKDROP samples=%d maxError=%.9f maxPng8CodeError=%d%n",
                  authoredSamples, maximumError, maximumCodeError);
        } finally {
            output.end(); output.dispose(); depth.dispose(); quad.dispose(); shader.dispose();
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            Gdx.gl.glDepthMask(true);
        }
    }

    /** Foreground weather composes after background fog, in linear HDR, before the single display conversion. */
    private void checkForegroundWeather() {
        var shader = GpuAtmosphere.shader("atmosphere-composite.frag");
        var quad = GpuAtmosphere.screenQuad();
        var output = GpuHeatGlow.buffer(1, 1);
        float[][] texels = { { .2f, .1f, .05f, 1 }, { .5f, 0, 0, 1 },
              { .03f, .04f, .05f, .25f }, { 2, .125f, .25f, .5f }, { 0, 0, 0, 0 }, { 1, 0, 0, 1 } };
        Texture[] textures = new Texture[texels.length];
        try {
            for (int index = 0; index < textures.length; index++) {
                var pixel = BufferUtils.newFloatBuffer(4);
                float[] stored = texels[index].clone();
                if (index != 1 && index != 5) {
                    for (int channel = 0; channel < 3; channel++) { stored[channel] *= GpuSceneRadiance.SCALE; }
                }
                pixel.put(stored).flip();
                textures[index] = GpuColorTexture.linear(pixel, 1, 1, false);
            }
            output.begin();
            GpuAtmosphere.screenState();
            shader.bind();
            for (int index = 0; index < textures.length; index++) { textures[index].bind(index); }
            shader.setUniformi("u_fog", 2);
            shader.setUniformi("u_weather", 3);
            shader.setUniformf("u_fogEnabled", 1);
            shader.setUniformf("u_weatherEnabled", 1);
            shader.setUniformf("u_fogSize", 1, 1);
            shader.setUniformf("u_scatteringBounds", 0, 0, 1, 1);
            shader.setUniformf("u_projectionDepth", -1, 0, 0, 1);
            shader.setUniformf("u_edgeScale", 1);
            shader.setUniformf("u_exposure", .1f);
            shader.setUniformf("u_tint", 1, 1, 1);
            shader.setUniformf("u_saturation", 1);
            shader.setUniformf("u_sky", .1f, .1f, .1f);
            shader.setUniformf("u_horizon", .1f, .1f, .1f);
            shader.setUniformMatrix("u_inverseView", new Matrix4());
            var sample = BufferUtils.newFloatBuffer(4);
            for (boolean transparent : new boolean[] { false, true }) {
                shader.setUniformi("u_scene", transparent ? 4 : 0);
                shader.setUniformi("u_depth", transparent ? 5 : 1);
                shader.setUniformi("u_waterDepth", transparent ? 5 : 1);
                shader.setUniformf("u_transparentBackground", transparent ? 1 : 0);
                quad.render(shader, GL20.GL_TRIANGLES);
                sample.clear();
                Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, sample);
                for (int channel = 0; channel < 3; channel++) {
                    float expected = transparent ? texels[3][channel] / .5f
                          : (texels[0][channel] * .25f + texels[2][channel]) * .5f + texels[3][channel];
                    assertEquals(GpuColor.display(expected * .1f), sample.get(channel), .0015f,
                          "Foreground weather must retain HDR radiance without the background's fog attenuation");
                }
                assertEquals(transparent ? .5f : 1, sample.get(3), 0,
                      "Transparent weather exports retain straight colour and source-over coverage");
            }
        } finally {
            output.end(); output.dispose(); quad.dispose(); shader.dispose();
            for (Texture texture : textures) { if (texture != null) { texture.dispose(); } }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            Gdx.gl.glDepthMask(true);
        }
    }

    /** Raising the shaft ceiling must not silently lengthen the fog/haze column below it. */
    private void checkFogCloudBounds() throws ReflectiveOperationException {
        var shader = GpuAtmosphere.shader("atmosphere-fog.frag");
        var quad = GpuAtmosphere.screenQuad();
        var atmosphere = new GpuAtmosphere();
        atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, .1f, 0));
        atmosphere.beginPixels(4, 4, 0);
        var fogField = GpuAtmosphere.class.getDeclaredField("fog");
        fogField.setAccessible(true);
        var buffer = (com.badlogic.gdx.graphics.glutils.FrameBuffer) fogField.get(atmosphere);
        var pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(0.5f, 0, 0, 1);
        pixel.fill();
        var depth = new Texture(pixel);
        pixel.dispose();
        try {
            buffer.begin();
            GpuAtmosphere.screenState();
            shader.bind();
            depth.bind(0);
            shader.setUniformi("u_depth", 0);
            // This zero-ray probe still links both sampler types; they must use distinct units.
            shader.setUniformi("u_geometryShadow", 2);
            var inverse = new Matrix4();
            inverse.val[Matrix4.M22] = -100;
            shader.setUniformMatrix("u_inverseView", inverse);
            shader.setUniformf("u_boundsMin", -50, -50, -1);
            shader.setUniformf("u_fog", 0, 2, 0);
            shader.setUniformf("u_haze", 0.01f);
            shader.setUniformf("u_noiseScale", 0.5f);
            shader.setUniformf("u_layerScreenBounds", 0, 0, 1, 1);
            shader.setUniformf("u_fogColor", 0.5f, 0.5f, 0.5f);
            shader.setUniformf("u_maxOpacity", BoardAtmosphere.MAX_FOG_OPACITY);
            shader.setUniformf("u_rays", 0);
            int reference = 0;
            for (float ceiling : new float[] { 12, 60 }) {
                shader.setUniformf("u_boundsMax", 50, 50, ceiling);
                quad.render(shader, GL20.GL_TRIANGLES);
                Pixmap result = Pixmap.createFromFrameBuffer(0, 0, 1, 1);
                try {
                    int rgba = result.getPixel(0, 0);
                    if (ceiling == 12) {
                        reference = rgba;
                        assertEquals(0.885f, new Color(rgba).a, 0.01f, "Uncapped haze must retain its original column density");
                    } else {
                        assertEquals(reference, rgba, "Cloud altitude must not thicken the same fog/haze");
                    }
                } finally {
                    result.dispose();
                }
            }
            // The runtime's own reduced-resolution target must retain both bright shafts and faint coloured fog.
            shader.setUniformf("u_haze", 1);
            shader.setUniformf("u_fogColor", 8, .004f, .002f);
            quad.render(shader, GL20.GL_TRIANGLES);
            var radiance = BufferUtils.newFloatBuffer(4);
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, radiance);
            assertEquals(2, radiance.get(0) / GpuSceneRadiance.SCALE, .003,
                  "Fog radiance above one must survive until scene exposure");
            assertEquals(.001, radiance.get(1) / GpuSceneRadiance.SCALE, .000001,
                  "Faint fog must not quantize to an 8-bit channel");
            assertEquals(.0005, radiance.get(2) / GpuSceneRadiance.SCALE, .000001);
            assertEquals(.75, radiance.get(3), .001, "Fog alpha remains linear transmission");

            // The linear particle-light handoff must not inherit a display-white clamp.
            var light = atmosphere.lighting();
            light.ambient().r = 8;
            light.direct().r = 0;
            assertEquals(3.2, atmosphere.sceneParticleLight().r, .00001);
            assertEquals(8, light.ambient().r, 0, "The particle handoff must not mutate atmospheric light");
        } finally {
            buffer.end();
            atmosphere.dispose();
            depth.dispose();
            quad.dispose();
            shader.dispose();
            Gdx.gl.glDepthMask(true);
        }
    }

    /** Exercise the air-scattering GLSL, including its direction convention. */
    private void checkScatteringPhase() {
        String root = "megamek/client/ui/clientGUI/boardview/gpu/";
        String phase = Gdx.files.classpath(root + "scattering-phase.glsl").readString("UTF-8");
        ShaderProgram shader = GpuGlsl.compile("scattering phase probe",
              Gdx.files.classpath(root + "atmosphere.vert").readString("UTF-8"),
              "in vec2 v_uv;\nlayout(location = 0) out vec4 fragColor;\n" + phase + "\nvoid main() {\n"
                    + "float cosine = v_uv.x * 2.0 - 1.0;\n"
                    + "float forward = scatteringPhase(cosine, 0.5);\n"
                    + "fragColor = vec4(forward / (1.0 + forward), scatteringPhase(cosine, 0.0) * 0.5, 0.0, 1.0);\n}");
        assertTrue(shader.isCompiled(), shader.getLog());
        var quad = GpuAtmosphere.screenQuad();
        var buffer = GpuAtmosphere.buffer(32, 1, false);
        try {
            buffer.begin();
            GpuAtmosphere.screenState();
            shader.bind();
            quad.render(shader, GL20.GL_TRIANGLES);
            Pixmap result = Pixmap.createFromFrameBuffer(0, 0, 32, 1);
            try {
                float away = new Color(result.getPixel(0, 0)).r;
                float side = new Color(result.getPixel(16, 0)).r;
                float toward = new Color(result.getPixel(31, 0)).r;
                assertTrue(toward > side * 1.8f && side > away,
                      "Forward scattering must strengthen when looking toward the sun");
                for (int x = 0; x < 32; x++) {
                    assertEquals(0.5f, new Color(result.getPixel(x, 0)).g, 0.005f,
                          "Isotropic scattering must be finite and independent of viewing angle");
                }
            } finally {
                result.dispose();
                buffer.end();
            }
        } finally {
            buffer.dispose();
            quad.dispose();
            shader.dispose();
            // The isolated screen-space probe must not leave depth writes disabled for the scene's shadow clear.
            Gdx.gl.glDepthMask(true);
        }
    }

    private void checkParticles(GpuAtmosphere atmosphere, GpuTerrain terrain, ModelBatch batch, ModelInstance tower,
          BoardCamera camera, BoardScene scene) throws IOException {
        camera.setIsometric(true);
        camera.fit(scene);
        draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
        long clear = GpuBoardTestUi.capture(new File(output, "weather-clear.png"));
        String[] names = { "rain", "snow", "hail" };
        for (int kind = 0; kind < names.length; kind++) {
            var effects = new BoardAtmosphere.Effects(kind == 0 ? 1 : 0, kind == 1 ? 1 : 0,
                  kind == 2 ? 1 : 0, 0, 0, 0.4f, 60);
            var weather = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0, effects);
            draw(atmosphere, terrain, batch, tower, camera, scene, weather);
            long first = GpuBoardTestUi.capture(new File(output, "weather-" + names[kind] + ".png"));
            assertNotEquals(clear, first, names[kind] + " must be visible");
            draw(atmosphere, terrain, batch, tower, camera, scene, weather);
            assertNotEquals(first, GpuBoardTestUi.capture(new File(output, "weather-" + names[kind] + "-moving.png")),
                  names[kind] + " must move over time");
            draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
            assertEquals(clear, GpuBoardTestUi.capture(new File(output, "weather-off.png")),
                  "Disabling weather must remove every particle immediately");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        }
        camera.setIsometric(false);
        camera.fit(scene);
        var calmRain = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0,
              new BoardAtmosphere.Effects(1, 0, 0, 0, 0, 0, 0));
        draw(atmosphere, terrain, batch, tower, camera, scene, calmRain);
        long overhead = GpuBoardTestUi.capture(new File(output, "weather-rain-top.png"));
        draw(atmosphere, terrain, batch, tower, camera, scene, calmRain);
        assertNotEquals(overhead, GpuBoardTestUi.capture(new File(output, "weather-rain-top-moving.png")),
              "Rain must remain animated when looking straight down without wind");
        var lightning = new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0,
              new BoardAtmosphere.Effects(0, 0, 0, 0, 1, 0, 0));
        float darkest = 1, brightest = 0;
        for (int frame = 0; frame < 80; frame++) {
            draw(atmosphere, terrain, batch, tower, camera, scene, lightning);
            float brightness = luminance(sample(camera, BoardGeometry.center(new Coords(1, 3), 0)));
            darkest = Math.min(darkest, brightness);
            brightest = Math.max(brightest, brightness);
            if (frame == 4) {
                assertTrue(brightest > darkest * 1.2f, "Enabling lightning must visibly flash within half a second");
                GpuBoardTestUi.capture(new File(output, "weather-lightning.png"));
            }
        }
        assertTrue(brightest > darkest * 1.2f, "A lightning storm must provide clearly visible illumination");
        assertTrue(brightest < darkest * 1.5f, "Lightning must retain terrain detail instead of whitening the board");
        draw(atmosphere, terrain, batch, tower, camera, scene, BoardAtmosphere.DEFAULTS);
        assertEquals(darkest, luminance(sample(camera, BoardGeometry.center(new Coords(1, 3), 0))), 0.005f,
              "Disabling lightning must immediately restore normal exposure");
    }


    /** Compare the complete fixed-camera frame, not a selected receiver that may fall inside a real moon shadow. */
    private static float[] nightFrameBrightness(GpuAtmosphere atmosphere) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        int skyX = width / 2, skyY = height * 15 / 16;
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            var field = GpuAtmosphere.class.getDeclaredField("sceneColor");
            field.setAccessible(true);
            var scene = (FrameBuffer) field.get(atmosphere);
            assertEquals(width, scene.getWidth());
            assertEquals(height, scene.getHeight());
            var viewport = BufferUtils.newIntBuffer(4);
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            scene.begin();
            try {
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(skyX, skyY, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                assertEquals(0, pixel.get(3), 0, "The sky comparison must actually hit the scene's clear background");
            } finally {
                scene.end();
                Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
            Color color = new Color();
            double total = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    total += linearLuminance(color.set(frame.getPixel(x, y)));
                }
            }
            return new float[] { (float) (total / (width * height)),
                  linearLuminance(color.set(frame.getPixel(skyX, skyY))) };
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Actual night frame", failure); }
        finally { frame.dispose(); }
    }

    /** Read the owned scene's canonical RGB after draw(), before the already-completed display can affect it. */
    private static float[] sampleScene(GpuAtmosphere atmosphere, BoardCamera camera, Vector3 point) {
        try {
            var field = GpuAtmosphere.class.getDeclaredField("sceneColor");
            field.setAccessible(true);
            var scene = (FrameBuffer) field.get(atmosphere);
            Vector3 projected = camera.camera.project(new Vector3(point), 0, 0, scene.getWidth(), scene.getHeight());
            int x = Math.round(projected.x), y = Math.round(projected.y);
            assertTrue(x >= 0 && y >= 0 && x < scene.getWidth() && y < scene.getHeight(),
                  "The matched world probe must be inside the physical scene framebuffer");
            var viewport = BufferUtils.newIntBuffer(4);
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            scene.begin();
            try {
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                assertEquals(1, pixel.get(3), .0005, "The source probe must hit an opaque receiver");
                float[] result = { pixel.get(0) / GpuSceneRadiance.SCALE,
                      pixel.get(1) / GpuSceneRadiance.SCALE, pixel.get(2) / GpuSceneRadiance.SCALE };
                for (float value : result) { assertTrue(Float.isFinite(value), "Finite canonical source"); }
                return result;
            } finally {
                scene.end();
                Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Actual canonical scene", failure); }
    }

    /** One permitted adjacent half interval plus eight float arithmetic ULPs, all in canonical scene units. */
    private static double sceneRounding(float value) {
        double half = Math.scalb(1.0, Math.max(-24,
              Math.getExponent(Math.abs((double) value) * GpuSceneRadiance.SCALE) - 10)) / GpuSceneRadiance.SCALE;
        return half + 8 * Math.ulp(value);
    }

    private Color sample(BoardCamera camera, Vector3 point) {
        Vector3 projected = camera.camera.project(new Vector3(point), 0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Pixmap pixel = Pixmap.createFromFrameBuffer(Math.round(projected.x), Math.round(projected.y), 1, 1);
        try {
            return new Color(pixel.getPixel(0, 0));
        } finally {
            pixel.dispose();
        }
    }

    private static float difference(Color a, Color b) {
        return Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b);
    }

    private static float luminance(Color color) {
        return 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
    }

    /** Units and tileset ground share one light model: the same gray under the same light renders alike. */
    private static void assertOneLightModel(Color roof, Color ground, String when) {
        assertEquals(1, linearLuminance(roof) / linearLuminance(ground), 0.04f,
              "The unit shader and the tileset ground must light alike at " + when + ": roof " + roof + ", ground "
                    + ground);
    }

    private static float linearLuminance(Color color) {
        return .2126f * GpuColor.linear(color.r) + .7152f * GpuColor.linear(color.g)
              + .0722f * GpuColor.linear(color.b);
    }

    /** A hex of uniform tileset art in this gray. */
    private static BoardScene.Pixels art(int gray) {
        var image = new java.awt.image.BufferedImage(84, 72, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(gray, gray, gray));
        graphics.fillRect(0, 0, 84, 72);
        graphics.dispose();
        return new BoardScene.Pixels(image);
    }
}
