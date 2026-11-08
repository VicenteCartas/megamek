/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.PlanetaryConditions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Same-camera A/B measurements and actual rain/splash pixels, rather than assuming shader arithmetic is faster. */
@Tag("on-demand")
class GpuWaterShaderSmokeTest {
    private static final Color BACKDROP = new Color(.04f, .06f, .08f, 1);

    /** Exercise Field.prepare, its filtered RGBA8 texture and the actual surface provider together. */
    @Test
    void packedCurrentsKeepStillPoolsStillAndPreserveBothFlowDirections() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(128, 128);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> checkPackedCurrents(manager));
                } catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Packed water current", failure.get()); }
    }

    private static void checkPackedCurrents(GpuShaderManager manager) {
        var terrain = new GpuTerrain(true, true);
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        String source = GpuShaderSource.readDisk("water-pool.glsl");
        String point = "float flowing = waterFlowShare(current);";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(point), -1).length - 1);
        // Report the real decoded current and branch selector before optics, preserving the provider and field.
        String probe = source.replace(point, point + "\n    return vec4(current, flowing, 1.0);");
        // The normal board configuration enforces a 900 x 600 minimum window. Own this probe's pixel size.
        try (var output = new GpuSceneTestOutput(128, 128)) {
            assertTrue(manager.apply(Map.of("water-pool.glsl", probe)).success());
            var camera = new BoardCamera();
            camera.resize(128, 128);
            Coords at = new Coords(4, 2);
            for (int drop : new int[] { 0, 2, -2 }) {
                BoardScene scene = scene(drop != 0, drop);
                Vector3 center = BoardGeometry.center(at, scene.tile(at).elevation());
                center.z = BoardGeometry.waterZ(scene.tile(at));
                camera.fit(scene);
                camera.camera.zoom = BoardGeometry.width() * 2 / 128;
                camera.center(center);
                frame.prepare(terrain, camera, scene);
                terrain.update(scene);
                GpuReviewFrame.settle(terrain, null, scene, camera);
                terrain.setWaterEffects(true);
                // The independent physical current is sampled before byte packing, not reconstructed by a test decoder.
                var pools = new GpuWaterShader.Pools(scene, BoardFlow.calculate(scene), Map.of());
                float[] expected = new float[4];
                pools.sample(center.x, center.y, expected);
                if (drop == 0) { assertEquals(0, expected[1]); assertEquals(0, expected[2]); }
                else { assertTrue(expected[2] * drop < -.01f, "The two real descents must drive opposite currents"); }
                // Only water is drawn into transparent black: alpha proves the sampled surface actually rendered.
                // Its production nearest-water pass is retained; opaque bed pixels cannot make the zero case pass.
                for (int warmup = 0; warmup < 3; warmup++) {
                    output.begin(0, 0, 0, 0);
                    terrain.renderTransparent(camera.camera);
                }
                float[] pixels = output.linearPixels();
                assertEquals(128 * 128 * 4, pixels.length, "Readback stride belongs to the explicit probe target");
                Vector3 projected = camera.camera.project(center.cpy(), 0, 0, 128, 128);
                assertEquals(64, projected.x, .001f, "Probe is centered on the real river");
                assertEquals(64, projected.y, .001f, "Probe is centered on the real river");
                int covered = 0;
                for (int index = 3; index < pixels.length; index += 4) { if (pixels[index] > 0) { covered++; } }
                System.out.printf("Packed current drop=%d backbuffer=%dx%d target=128x128 covered=%d center=%s physical=(%.6f,%.6f)%n",
                      drop, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), covered, projected,
                      expected[1], expected[2]);
                int compared = 0;
                for (int y = 62; y <= 65; y++) {
                    for (int x = 62; x <= 65; x++) {
                        int index = (y * 128 + x) * 4;
                        assertEquals(1, pixels[index + 3], 0, "The actual water surface covers every probe pixel");
                        for (int channel = 0; channel < 2; channel++) {
                            assertEquals(expected[channel + 1], pixels[index + channel], drop == 0 ? .00002f : .012f,
                                  "Filtered field current retains the physical sign and magnitude");
                        }
                        if (drop == 0) {
                            assertEquals(0, pixels[index + 2], 0, "Still water never enters the two-phase flow branch");
                        } else {
                            assertTrue(pixels[index + 1] * drop < -.01f, "Directed flow survives byte packing and filtering");
                            assertEquals(1, pixels[index + 2], .03f,
                                  "A full current retains its river surface through field filtering and byte rounding");
                        }
                        compared++;
                    }
                }
                System.out.printf("Packed water current drop=%d samples=%d physical=(%.6f,%.6f)%n",
                      drop, compared, expected[1], expected[2]);
                if (drop != 0) {
                    // A mouth slows gradually: a half-speed current must show a mixture, not switch completely
                    // to either surface. Keep the real packed field, provider and rendering path for this probe.
                    String slower = probe.replace(point, "current *= .5;\n    " + point);
                    assertTrue(manager.apply(Map.of("water-pool.glsl", slower)).success());
                    output.begin(0, 0, 0, 0);
                    terrain.renderTransparent(camera.camera);
                    float[] mouth = output.linearPixels();
                    int centerIndex = (64 * 128 + 64) * 4;
                    assertEquals(1, mouth[centerIndex + 3], 0, "The slower-current probe must draw water");
                    assertTrue(mouth[centerIndex + 2] > .35f && mouth[centerIndex + 2] < .65f,
                          "A half-speed mouth blends the river and open-water surfaces");
                    String depthPoint = "float depth = field.g * WATER_DEPTH_RANGE;";
                    assertTrue(probe.contains(depthPoint));
                    String shallow = probe.replace(depthPoint, "field.g = .1 / WATER_DEPTH_RANGE;\n    " + depthPoint);
                    assertTrue(manager.apply(Map.of("water-pool.glsl", shallow)).success());
                    output.begin(0, 0, 0, 0);
                    terrain.renderTransparent(camera.camera);
                    float[] shelf = output.linearPixels();
                    assertEquals(1, shelf[centerIndex + 3], 0, "The shallow probe must draw water");
                    float drag = shelf[centerIndex + 1] / pixels[centerIndex + 1];
                    assertTrue(drag > .2f && drag < .5f, "A shallow column slows visible motion without reversing it");
                    assertTrue(shelf[centerIndex + 2] > 0 && shelf[centerIndex + 2] < .35f,
                          "Slower shallow motion also damps the river ripples");
                    assertTrue(manager.apply(Map.of("water-pool.glsl", probe)).success());
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (Exception error) { throw new AssertionError(error); }
        finally {
            try { assertTrue(manager.apply(Map.of()).success()); }
            finally { frame.dispose(); terrain.dispose(); }
        }
    }

    @Test
    void reflectedCloudsUseTheirOwnCoverageAndStillWaterKeepsSunlight() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(640, 480);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                    System.out.println("Water reflection renderer: " + renderer);
                    String expected = System.getProperty("megamek.gpu.expectedRenderer", "");
                    assertTrue(renderer.contains(expected), "Expected renderer " + expected + ", actual " + renderer);
                    manager.run(() -> checkReflectedClouds(manager));
                } catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Water cloud/static reflection", failure.get()); }
    }

    private static void checkReflectedClouds(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        var pixmap = new Pixmap(128, 128, Pixmap.Format.RGBA8888);
        pixmap.setColor(1, 0, 1, 1);
        pixmap.fill();
        var texture = new com.badlogic.gdx.graphics.Texture(pixmap, true);
        texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.MipMapLinearLinear,
              com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        var projection = new com.badlogic.gdx.math.Matrix4();
        float width = BoardGeometry.width();
        projection.val[com.badlogic.gdx.math.Matrix4.M00] = 1 / (40 * width);
        projection.val[com.badlogic.gdx.math.Matrix4.M11] = 1 / (40 * width);
        projection.val[com.badlogic.gdx.math.Matrix4.M03] = .5f;
        projection.val[com.badlogic.gdx.math.Matrix4.M13] = .5f;
        projection.val[com.badlogic.gdx.math.Matrix4.M22] = 1 / width;
        projection.val[com.badlogic.gdx.math.Matrix4.M23] = -6;
        var cloud = new GpuCloudShadow(texture, projection);
        cloud.enabled = false;
        cloud.reflection = true;
        try (var output = new SceneOutput()) {
            configureParity(terrain);
            terrain.setWind(BoardAtmosphere.Effects.NONE);
            GpuEnvironmentLighting.required(terrain.environment()).cloudShadow = cloud;
            String source = GpuShaderSource.read("water-lighting.glsl");
            String point = ": pointReflectance(normal, light, view, roughness, vec3(.02037));";
            assertEquals(1, source.split(java.util.regex.Pattern.quote(point), -1).length - 1);
            String noPoint = source.replace(point, ": vec3(0.0);");
            for (boolean falls : new boolean[] { false, true }) {
                BoardScene scene = parityScene(falls);
                terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
                terrain.update(scene);
                terrain.animate(.37f, List.of());
                for (boolean perspective : new boolean[] { false, true }) {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setPerspective(perspective);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    camera.update();
                    var base = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
                    Vector3 direction = new Vector3(-camera.camera.direction.x, -camera.camera.direction.y,
                          camera.camera.direction.z).nor();
                    var lighting = new BoardAtmosphere.Lighting(direction, base.direct(), base.ambient(),
                          base.fog(), base.sky(), base.horizon(), base.tint(), base.saturation(), base.daylight(),
                          base.sunlight(), 0, base.environmentSky(), base.environmentHorizon(), null, 1);
                    terrain.setAtmosphere(lighting);
                    terrain.setWaterEffects(true);
                    cloud.reflection = false;
                    for (int warmup = 0; warmup < 4; warmup++) { parityPixels(output, terrain, camera, true); }
                    byte[] clear = parityPixels(output, terrain, camera, true);
                    float[] clearScene = waterSceneReadback(output, false);
                    float[] clearDepth = waterSceneReadback(output, true);
                    cloud.reflection = true;
                    setCloudCoverage(texture, pixmap, false);
                    assertTrue(Arrays.equals(clear, parityPixels(output, terrain, camera, true)),
                          "Zero cloud opacity must retain the existing complete water output");
                    setCloudCoverage(texture, pixmap, true);
                    byte[] reflected = parityPixels(output, terrain, camera, true);
                    float[] reflectedScene = waterSceneReadback(output, false);
                    int linearChanges = 0;
                    for (int index = 0; index < reflectedScene.length; index++) {
                        assertTrue(Float.isFinite(reflectedScene[index]), "Reflection stores finite RGBA16F values");
                        if ((index & 3) == 3) {
                            assertEquals(clearScene[index], reflectedScene[index], "Clouds do not change coverage alpha");
                        } else if (Math.abs(clearScene[index] - reflectedScene[index]) > .00001f) { linearChanges++; }
                    }
                    assertTrue(linearChanges > 100, "Cloud reflection changes raw scene radiance before display");
                    assertTrue(Arrays.equals(clearDepth, waterSceneReadback(output, true)),
                          "Cloud reflection does not change opaque/picking depth");
                    assertVisibleDifference(clear, reflected, "Coverage changes reflection while R/direct shadow stay fixed");
                    assertTrue(Arrays.equals(reflected, parityPixels(output, terrain, camera, true)),
                          "Frozen reflection repeats exactly");
                    float offset = projection.val[com.badlogic.gdx.math.Matrix4.M03];
                    projection.val[com.badlogic.gdx.math.Matrix4.M03] += .125f;
                    assertVisibleDifference(reflected, parityPixels(output, terrain, camera, true),
                          "The reflected layer follows its world projection");
                    projection.val[com.badlogic.gdx.math.Matrix4.M03] = offset;
                    assertTrue(Arrays.equals(reflected, parityPixels(output, terrain, camera, true)),
                          "Restoring cloud projection restores the image");
                    String label = (falls ? "falls" : "pool") + (perspective ? "-perspective" : "-ortho");
                    savePixels(reflected, "water-cloud-reflection-" + label);
                    checkCloudSourceScaling(output, terrain, camera, scene, cloud, lighting,
                          clearScene, reflectedScene, clearDepth, label);
                    assertTrue(Arrays.equals(reflected, parityPixels(output, terrain, camera, true)),
                          "Restoring canonical illumination restores the cloud reflection");
                    terrain.setWaterEffects(false);
                    byte[] still = parityPixels(output, terrain, camera, true);
                    cloud.reflection = false;
                    assertTrue(Arrays.equals(still, parityPixels(output, terrain, camera, true)),
                          "Effects-off skips cloud detail without removing base reflections");
                    var result = manager.apply(Map.of("water-lighting.glsl", noPoint));
                    assertTrue(result.success(), result.message());
                    assertTrue(result.updated() > 0);
                    assertVisibleDifference(parityPixels(output, terrain, camera, true), still,
                          "Still water retains a real directional reflection");
                    assertTrue(manager.apply(Map.of()).success());
                    assertTrue(Arrays.equals(still, parityPixels(output, terrain, camera, true)),
                          "Restoring the actual point reflection restores still water");
                }
            }
            checkCloudCoverageOwnership(terrain);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            try { manager.apply(Map.of()); }
            finally {
                terrain.dispose();
                texture.dispose();
                pixmap.dispose();
            }
        }
    }

    /** Check the actual cloud contribution in linear scene storage, independent of the display/background. */
    private static void checkCloudSourceScaling(SceneOutput output, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, GpuCloudShadow cloud, BoardAtmosphere.Lighting reference,
          float[] clear, float[] reflected, float[] depth, String label) {
        float[] referenceWater = nearestWaterDepthReadback(terrain);
        int referenceWaterPixels = 0;
        if (referenceWater != null) {
            for (float value : referenceWater) { if (value < 1) { referenceWaterPixels++; } }
        }
        System.out.printf("Cloud source %s baseline: nearest-water pixels=%d target=%s%n",
              label, referenceWaterPixels, referenceWater != null);
        assertNotNull(referenceWater, "The full-source reference must render the nearest-water target");
        assertTrue(referenceWaterPixels > 0, "The full-source nearest-water target cannot be empty");
        assertEquals(depth.length, referenceWater.length, "Nearest-water and scene depth cover the same viewport");
        double referenceEnergy = 0;
        for (int index = 0; index < clear.length; index++) {
            if ((index & 3) != 3) { referenceEnergy += Math.abs(reflected[index] - clear[index]); }
        }
        assertTrue(referenceEnergy > 0, "The reference must contain a measurable reflected-cloud contribution");
        try {
            for (float scale : new float[] { .5f, 0 }) {
                // Scale only canonical sources; direction, backdrop, atmosphere presentation and cloud opacity stay fixed.
                terrain.setAtmosphere(new BoardAtmosphere.Lighting(reference.direction(),
                      new Color(reference.direct()).mul(scale, scale, scale, 1),
                      new Color(reference.ambient()).mul(scale, scale, scale, 1),
                      reference.fog(), reference.sky(), reference.horizon(), reference.tint(), reference.saturation(),
                      reference.daylight(), reference.sunlight(), reference.shadowStrength(),
                      new Color(reference.environmentSky()).mul(scale, scale, scale, 1),
                      new Color(reference.environmentHorizon()).mul(scale, scale, scale, 1),
                      null, reference.environmentGain()));
                // A zero direct source changes the actual material variant; settle that existing preparation path.
                terrain.update(scene);
                cloud.reflection = false;
                // Black input deliberately bypasses parityPixels' visible-terrain precondition.
                output.draw(terrain, camera, true);
                float[] withoutCloud = waterSceneReadback(output, false);
                float[] withoutWater = nearestWaterDepthReadback(terrain);
                cloud.reflection = true;
                output.draw(terrain, camera, true);
                byte[] shown = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
                      Gdx.graphics.getBackBufferHeight(), false);
                float[] withCloud = waterSceneReadback(output, false);
                float[] withWater = nearestWaterDepthReadback(terrain);
                boolean depthEqual = Arrays.equals(depth, waterSceneReadback(output, true));
                boolean waterDepthEqual = Arrays.equals(referenceWater, withoutWater)
                      && Arrays.equals(referenceWater, withWater);
                int withoutWaterPixels = 0, withWaterPixels = 0;
                if (withoutWater != null) {
                    for (float value : withoutWater) { if (value < 1) { withoutWaterPixels++; } }
                }
                if (withWater != null) {
                    for (float value : withWater) { if (value < 1) { withWaterPixels++; } }
                }
                double energy = 0;
                float maximumError = 0, maximumScene = 0;
                boolean finite = true, alphaEqual = true;
                for (int index = 0; index < withCloud.length; index++) {
                    finite &= Float.isFinite(withCloud[index]) && Float.isFinite(withoutCloud[index]);
                    if ((index & 3) == 3) {
                        alphaEqual &= withCloud[index] == withoutCloud[index];
                    } else {
                        float contribution = withCloud[index] - withoutCloud[index];
                        energy += Math.abs(contribution);
                        maximumError = Math.max(maximumError,
                              Math.abs(contribution - scale * (reflected[index] - clear[index])));
                        maximumScene = Math.max(maximumScene, Math.max(Math.abs(withCloud[index]),
                              Math.max(Math.abs(withoutCloud[index]),
                                    scale * Math.max(Math.abs(reflected[index]), Math.abs(clear[index])))));
                    }
                }
                double ratio = energy / referenceEnergy;
                System.out.printf("Cloud source %s scale=%.1f: energy ratio=%.8f max error=%.8f scene=%.8f%n",
                      label, scale, ratio, maximumError, maximumScene);
                System.out.printf("Cloud source %s scale=%.1f: nearest-water off/on=%d/%d target=%s/%s equal=%s%n",
                      label, scale, withoutWaterPixels, withWaterPixels,
                      withoutWater != null, withWater != null, waterDepthEqual);
                savePixels(shown, "water-cloud-source-" + label + "-" + scale);
                assertTrue(finite, "Source scaling must retain finite raw scene values");
                assertTrue(depthEqual, "Source scaling must retain opaque/picking depth");
                assertTrue(waterDepthEqual,
                      "Both cloud modes must retain the nonempty full-source water footprint and nearest depth");
                assertTrue(alphaEqual, "Cloud source intensity cannot change coverage alpha");
                if (scale == 0) {
                    assertEquals(0, energy, "A black canonical source cannot produce a reflected cloud contribution");
                } else {
                    // Four RGBA16F readbacks and blending may round; the raw contribution must still scale by one half.
                    assertTrue(maximumError <= .003f * maximumScene + .000002f,
                          "Cloud radiance must scale before display, without a second gain or additive source");
                    assertEquals(scale, ratio, .02, "Total reflected-cloud change must scale with source intensity");
                }
            }
        } finally {
            terrain.setAtmosphere(reference);
            terrain.update(scene);
            cloud.reflection = true;
        }
    }

    /** Read the existing water-only depth target without allocating a target or disturbing texture bindings. */
    private static float[] nearestWaterDepthReadback(GpuTerrain terrain) {
        var texture = terrain.waterDepth();
        if (texture == null) { return null; }
        var values = com.badlogic.gdx.utils.BufferUtils.newFloatBuffer(texture.getWidth() * texture.getHeight());
        int active = org.lwjgl.opengl.GL11.glGetInteger(GL20.GL_ACTIVE_TEXTURE);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        int binding = org.lwjgl.opengl.GL11.glGetInteger(GL20.GL_TEXTURE_BINDING_2D);
        try {
            texture.bind();
            org.lwjgl.opengl.GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, values);
        } finally {
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, binding);
            Gdx.gl.glActiveTexture(active);
        }
        float[] result = new float[values.capacity()];
        values.get(result);
        return result;
    }

    private static float[] waterSceneReadback(SceneOutput output, boolean depth) {
        output.scene.bind();
        int width = output.scene.getWidth(), height = output.scene.getHeight();
        var values = com.badlogic.gdx.utils.BufferUtils.newFloatBuffer(width * height * (depth ? 1 : 4));
        Gdx.gl.glReadPixels(0, 0, width, height, depth ? GL20.GL_DEPTH_COMPONENT : GL20.GL_RGBA, GL20.GL_FLOAT, values);
        float[] result = new float[values.capacity()];
        values.get(result);
        FrameBuffer.unbind();
        return result;
    }

    private static void setCloudCoverage(com.badlogic.gdx.graphics.Texture texture, Pixmap pixels, boolean patterned) {
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                int coverage = patterned && ((x / 16 + y / 16) & 1) == 0 ? 255 : 0;
                pixels.drawPixel(x, y, 0xff00ffff | coverage << 16);
            }
        }
        texture.draw(pixels, 0, 0);
        texture.bind(0);
        Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
    }

    private static void checkCloudCoverageOwnership(GpuTerrain terrain) throws ReflectiveOperationException {
        var atmosphere = new GpuAtmosphere();
        try {
            BoardScene scene = parityScene(false);
            var settings = new BoardAtmosphere.Settings(13, .65f, 0, 2.5f, 0, 0);
            atmosphere.configure(settings);
            atmosphere.prepareClouds(terrain, scene, 0);
            var cloud = GpuEnvironmentLighting.cloudShadow(terrain.environment());
            assertTrue(cloud.enabled && cloud.reflection);
            Field owner = GpuAtmosphere.class.getDeclaredField("clouds");
            owner.setAccessible(true);
            var clouds = (GpuClouds) owner.get(atmosphere);
            Field target = GpuClouds.class.getDeclaredField("shadow");
            target.setAccessible(true);
            var buffer = (FrameBuffer) target.get(clouds);
            int[] original = null;
            for (float strength : new float[] { 1, 0 }) {
                clouds.update(settings, BoardAtmosphere.lighting(settings), scene, 0, strength);
                buffer.begin();
                Pixmap read = Pixmap.createFromFrameBuffer(0, 0, buffer.getWidth(), buffer.getHeight());
                buffer.end();
                try {
                    int[] coverage = new int[read.getWidth() * read.getHeight()];
                    int nonempty = 0;
                    for (int index = 0; index < coverage.length; index++) {
                        int pixel = read.getPixel(index % read.getWidth(), index / read.getWidth());
                        coverage[index] = pixel >>> 16 & 255;
                        if (coverage[index] > 0) { nonempty++; }
                        if (strength == 0) { assertEquals(255, pixel >>> 24); }
                    }
                    assertTrue(nonempty > 100, "Actual generated opacity must contain a cloud layer");
                    if (original != null) {
                        assertTrue(Arrays.equals(original, coverage), "Shadow strength cannot repaint reflected clouds");
                    }
                    original = coverage;
                } finally { read.dispose(); }
            }
            var night = new BoardAtmosphere.Settings(0, .65f, 0, 2.5f, 0, 0,
                  BoardAtmosphere.Effects.NONE, settings.pressure(), settings.temperature(), false);
            atmosphere.configure(night);
            atmosphere.prepareClouds(terrain, scene, 0);
            assertSame(cloud, GpuEnvironmentLighting.cloudShadow(terrain.environment()));
            assertFalse(cloud.enabled, "No moon casts no cloud shadow");
            assertTrue(cloud.reflection, "The moonless cloud layer remains available to sky reflection");
            assertEquals(0, cloud.projection.val[com.badlogic.gdx.math.Matrix4.M02],
                  "Moonless preparation actually replaces the old directional atlas projection");
            assertEquals(0, cloud.projection.val[com.badlogic.gdx.math.Matrix4.M12]);
        } finally { atmosphere.dispose(); }
    }

    @Test
    void zeroScenarioGravityBuildsLunarTerrainInBothCamerasAndRestoresWater() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(640, 480);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try (var output = new SceneOutput()) {
                    var scene = lunarScene();
                    var conditions = new PlanetaryConditions();
                    for (boolean procedural : new boolean[] { false, true }) {
                        var terrain = new GpuTerrain(true, procedural);
                        try {
                            terrain.update(scene);
                            terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
                            for (boolean perspective : new boolean[] { false, true }) {
                                var camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setPerspective(perspective);
                                camera.setIsometric(true);
                                camera.fit(scene);
                                // Exercise zero on the first frame, hiding existing pages, and returning to water.
                                for (float gravity : new float[] { 0, 1, 0, 2, .5f, 0, 1 }) {
                                    conditions.setGravity(gravity);
                                    terrain.setGravity(BoardAtmosphere.fromScenario(conditions, false, .5).gravity());
                                    terrain.update(scene);
                                    BoardScene shown = terrain.presentation(scene);
                                    if (gravity == 0) {
                                        for (var tile : shown.tiles()) {
                                            assertEquals(BoardScene.Surface.LUNAR, tile.surface());
                                            assertEquals(BoardLiquid.NONE, tile.liquid());
                                            assertEquals(scene.tile(tile.coords()).elevation()
                                                  - Math.max(0, scene.tile(tile.coords()).waterDepth()), tile.elevation());
                                        }
                                        terrain.update(scene);
                                        assertSame(shown.tiles(), terrain.presentation(scene).tiles(),
                                              "An unchanged zero-gravity frame reuses its converted tiles");
                                    } else {
                                        assertSame(scene.tiles(), shown.tiles(), "Positive gravity restores source tiles");
                                    }
                                    terrain.animate(0, List.of());
                                    for (int warmup = 0; warmup < 4; warmup++) { parityPixels(output, terrain, camera, true); }
                                    byte[] opaque = parityPixels(output, terrain, camera, false);
                                    byte[] complete = parityPixels(output, terrain, camera, true);
                                    if (gravity == 0) {
                                        assertNull(terrain.waterDepth(), "Invisible water must not occlude fog");
                                        assertParity(opaque, complete, parityPixels(output, terrain, camera, false), "zero-gravity");
                                    } else {
                                        assertNotNull(terrain.waterDepth());
                                        assertVisibleDifference(opaque, complete, "Water and falls return at " + gravity + " g");
                                    }
                                    assertEquals(2, scene.tile(new Coords(4, 4)).waterDepth(), "Game terrain stays intact");
                                }
                            }
                        } finally { terrain.dispose(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Scenario gravity water rendering", failure.get()); }
    }

    @Test
    void unitInteractionsReloadAndPreserveWaterWithoutUnits() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkUnitInteractions(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Water interaction editing", failure.get()); }
    }

    private static void checkUnitInteractions(GpuShaderManager manager) {
        var terrain = new GpuTerrain(true, true);
        try (var output = new SceneOutput()) {
            configureParity(terrain);
            var scene = scene(false, 0);
            terrain.update(scene);
            terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
            terrain.setWind(BoardAtmosphere.Effects.NONE);
            terrain.animate(.37f, List.of());
            Field field = GpuTerrain.class.getDeclaredField("waders");
            field.setAccessible(true);
            var waders = (GpuWaders) field.get(terrain);
            // Fixed render snapshots isolate the shader's response from unit geometry and animation.
            for (int i = 0; i < 2; i++) {
                var coords = new Coords(5 + i, 4 + i);
                Vector3 center = BoardGeometry.center(coords, 0);
                waders.bodies[i * 4] = center.x;
                waders.bodies[i * 4 + 1] = center.y;
                waders.bodies[i * 4 + 2] = BoardGeometry.width() * .12f;
                waders.bodies[i * 4 + 3] = BoardGeometry.waterZ(scene.tile(coords));
            }
            waders.motion[5] = 8;
            String source = GpuShaderSource.read("water-interactions.glsl");
            String edited = source.replace("return wading * effects;", "return 0.0;");
            assertFalse(source.equals(edited), "The draft must change the wake's foam output");
            for (boolean perspective : new boolean[] { false, true }) {
                var camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setPerspective(perspective);
                camera.setIsometric(true);
                camera.fit(scene);
                camera.camera.zoom *= .55f;
                camera.center(BoardGeometry.center(new Coords(5, 4), 0));
                waders.count = 0;
                // The water pages coalesce their chunks after two stable frames; references come from the paged state.
                for (int warmup = 0; warmup < 4; warmup++) { parityPixels(output, terrain, camera, true); }
                byte[] empty = parityPixels(output, terrain, camera, true);
                waders.count = 1;
                waders.motion[0] = 0;
                byte[] standing = parityPixels(output, terrain, camera, true);
                assertVisibleDifference(empty, standing, "A standing unit produces a collar and ripples");
                waders.motion[0] = 8;
                byte[] moving = parityPixels(output, terrain, camera, true);
                assertVisibleDifference(standing, moving, "Unit motion adds a trailing wake");
                waders.count = 2;
                byte[] multiple = parityPixels(output, terrain, camera, true);
                assertVisibleDifference(moving, multiple, "Both unit interactions contribute");
                String view = perspective ? "perspective" : "ortho";
                savePixels(multiple, "water-unit-wakes-" + view);

                var result = manager.apply(Map.of("water-interactions.glsl", edited));
                assertTrue(result.success(), result.message());
                assertTrue(result.updated() > 0, "The shared helper reloads live water programs");
                byte[] changed = parityPixels(output, terrain, camera, true);
                assertVisibleDifference(multiple, changed, "Editing the helper changes unit foam");
                assertFalse(manager.apply(Map.of("water-interactions.glsl", "unfinished water edit")).success());
                assertParity(changed, parityPixels(output, terrain, camera, true), parityPixels(output, terrain, camera, true),
                      "interaction-rejected-" + view);
                waders.count = 0;
                assertParity(empty, parityPixels(output, terrain, camera, true), parityPixels(output, terrain, camera, true),
                      "interaction-empty-" + view);
                assertTrue(manager.apply(Map.of()).success());
                waders.count = 2;
                assertParity(multiple, parityPixels(output, terrain, camera, true), parityPixels(output, terrain, camera, true),
                      "interaction-restored-" + view);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { terrain.dispose(); }
    }

    private static void assertVisibleDifference(byte[] before, byte[] after, String message) {
        int changed = 0;
        for (int pixel = 0; pixel < before.length; pixel += 4) {
            int difference = 0;
            for (int channel = 0; channel < 3; channel++) {
                difference += Math.abs(Byte.toUnsignedInt(before[pixel + channel]) - Byte.toUnsignedInt(after[pixel + channel]));
            }
            if (difference > 6) { changed++; }
        }
        assertTrue(changed > 50, message + ": " + changed + " changed pixels");
    }

    @Test
    void uniformFastPathsPreserveFrozenBedWaterAndWaterfallPixels() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkUniformFastPaths(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Uniform water shader fast paths", failure.get()); }
    }

    private static void checkUniformFastPaths(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        try (var output = new SceneOutput()) {
            // Reload only shader expressions on one frozen scene. Independently built renderers can differ
            // at opaque wall pixels even with identical programs, invalidating a shader-only comparison.
            configureParity(terrain);
            Map<String, String> reference = unguardedWaterSources();
            terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
            terrain.setWind(BoardAtmosphere.Effects.NONE);
            // Pool beds and submerged cliffs share the terrain shader's zero-detail path.
            for (boolean falls : new boolean[] { false, true }) {
                BoardScene scene = parityScene(falls);
                terrain.update(scene);
                terrain.animate(.37f, List.of());
                for (boolean perspective : new boolean[] { false, true }) {
                    byte[] effectsOff = null;
                    for (int mode = 0; mode < 3; mode++) {
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setPerspective(perspective);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        if (mode == 0) {
                            float scale = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
                            camera.camera.zoom = BoardGeometry.WIDTH * scale / 8;
                        } else {
                            camera.camera.zoom *= .8f;
                        }
                        camera.update();
                        float maximumHexPixels = 0;
                        for (BoardScene.Tile tile : scene.tiles()) {
                            maximumHexPixels = Math.max(maximumHexPixels, BoardGeometry.WIDTH
                                  * BoardCamera.pixelsPerUnit(camera.camera,
                                        BoardGeometry.center(tile.coords(), tile.elevation())));
                        }
                        assertTrue(mode == 0 ? maximumHexPixels < 12 : maximumHexPixels > 40,
                              "Exercise zero detail and active detail through the real camera uniforms");
                        String name = (falls ? "falls" : "pool") + (perspective ? "-perspective" : "-ortho")
                              + "-" + new String[] { "detail-zero", "effects-off", "active-wet" }[mode];
                        terrain.setWaterEffects(mode != 1);
                        terrain.setWetness(1);
                        var result = manager.apply(reference);
                        assertTrue(result.success(), result.message());
                        // Settle shader and geometry caches without advancing the shared frozen clock.
                        for (int warmup = 0; warmup < 4; warmup++) { parityPixels(output, terrain, camera, true); }
                        byte[][] before = { parityPixels(output, terrain, camera, false),
                              parityPixels(output, terrain, camera, true) };
                        result = manager.apply(Map.of());
                        assertTrue(result.success(), result.message());
                        assertTrue(result.updated() > 0, "The optimized water programs must replace the reference");
                        byte[][] after = { parityPixels(output, terrain, camera, false),
                              parityPixels(output, terrain, camera, true) };
                        result = manager.apply(reference);
                        assertTrue(result.success(), result.message());
                        assertTrue(result.updated() > 0, "Restore the reference on the same frozen scene");
                        byte[][] repeated = { parityPixels(output, terrain, camera, false),
                              parityPixels(output, terrain, camera, true) };
                        for (boolean transparent : new boolean[] { false, true }) {
                            int passIndex = transparent ? 1 : 0;
                            String pass = name + (transparent ? "-water" : "-bed");
                            assertParity(before[passIndex], after[passIndex], repeated[passIndex], pass);
                            if (transparent && mode == 1) { effectsOff = after[passIndex]; }
                            if (transparent && mode == 2) {
                                assertTrue(!Arrays.equals(effectsOff, after[passIndex]),
                                      "The active water effects must affect rendered pixels: " + name);
                            }
                        }
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { terrain.dispose(); }
    }

    private static void configureParity(GpuTerrain terrain) throws ReflectiveOperationException {
        Field field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        ModelBatch batch = (ModelBatch) field.get(terrain);
        // Keep the same depth order across replacement shader programs.
        Field sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        sorter.set(batch, new DefaultRenderableSorter());
    }

    /** Recreate the prior unconditional expressions through the same shared sources as the live shader editor. */
    private static Map<String, String> unguardedWaterSources() {
        var sources = new HashMap<String, String>();
        for (String name : List.of("terrain-sculpt.frag", "water-interactions.glsl")) {
            String current = GpuShaderSource.read(name);
            String reference = current
                  .replace("shore && u_rainDetail > 0.0 && u_waterEffects > 0.0", "shore")
                  .replace("u_rainDetail > 0.0 && u_waterEffects > 0.0", "true")
                  .replace("u_splashCount > 0", "true");
            assertFalse(current.equals(reference), "The legacy expressions must replace guards in " + name);
            sources.put(name, reference);
        }
        return sources;
    }

    private static byte[] parityPixels(SceneOutput output, GpuTerrain terrain, BoardCamera camera, boolean transparent) {
        terrain.renderShadows(camera.camera, List.of());
        output.draw(terrain, camera, transparent);
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        int varied = 0;
        // Count visible terrain above the shared display background and rounding margin.
        for (int i = 0; i < pixels.length; i += 4) {
            if (Byte.toUnsignedInt(pixels[i]) > Math.round(BACKDROP.r * 255) + 1
                  || Byte.toUnsignedInt(pixels[i + 1]) > Math.round(BACKDROP.g * 255) + 1
                  || Byte.toUnsignedInt(pixels[i + 2]) > Math.round(BACKDROP.b * 255) + 1) { varied++; }
        }
        assertTrue(varied > 500, "The comparison must include visible terrain even at zero detail");
        return pixels;
    }

    private static void assertParity(byte[] expected, byte[] actual, byte[] repeated, String name) {
        int changed = 0, referenceChanged = 0, maximum = 0, referenceMaximum = 0;
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            int delta = 0, referenceDelta = 0;
            for (int channel = 0; channel < 4; channel++) {
                int value = Byte.toUnsignedInt(expected[pixel + channel]);
                delta = Math.max(delta, Math.abs(value - Byte.toUnsignedInt(actual[pixel + channel])));
                referenceDelta = Math.max(referenceDelta, Math.abs(value - Byte.toUnsignedInt(repeated[pixel + channel])));
            }
            if (delta != 0) { changed++; maximum = Math.max(maximum, delta); }
            if (referenceDelta != 0) { referenceChanged++; referenceMaximum = Math.max(referenceMaximum, referenceDelta); }
        }
        System.out.printf("Water shader parity %s: changed=%d max=%d; repeated reference changed=%d max=%d%n",
              name, changed, maximum, referenceChanged, referenceMaximum);
        // Prior native terrain comparisons measured one cliff/shore pixel varying by 1 LSB between frozen draws.
        boolean stable = referenceChanged <= 1 && referenceMaximum <= 1;
        boolean equal = changed <= Math.max(1, referenceChanged) && maximum <= Math.max(1, referenceMaximum);
        if (!stable || !equal) {
            savePixels(expected, "water-fast-path-" + name + "-reference");
            savePixels(actual, "water-fast-path-" + name + "-optimized");
            savePixels(repeated, "water-fast-path-" + name + "-repeated");
        }
        assertTrue(stable, "The frozen shader reference must remain stable: " + name);
        assertTrue(equal, "Uniform fast paths must preserve rendered pixels: " + name + ", changed=" + changed
              + ", maximum=" + maximum);
    }

    private static void savePixels(byte[] pixels, String name) {
        Pixmap image = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
        try { image.getPixels().put(pixels); save(image, name); }
        finally { image.dispose(); }
    }

    private static BoardScene parityScene(boolean falls) {
        BoardScene source = scene(falls, falls ? 3 : 0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (BoardScene.Tile tile : source.tiles()) {
            // Include dry banks beside roads as well as the pool's submerged terrain.
            int roads = falls && !tile.water() ? 1 : 0;
            tiles.add(new BoardScene.Tile(tile.coords(), tile.elevation(), tile.waterDepth(), false, roads,
                  tile.surface(), null, null, List.of(), List.of(), tile.liquid(), null, true));
        }
        return new BoardScene(0, source.width(), source.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void proceduralWaterShowsRainAndSplashesAndMeasuresBothColorPaths() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain gif = new GpuTerrain(true, false), procedural = new GpuTerrain(true, true);
                try (var output = new SceneOutput()) {
                    Gdx.graphics.setVSync(false);
                    BoardScene scene = scene(false, 0);
                    for (GpuTerrain terrain : List.of(gif, procedural)) {
                        terrain.update(scene);
                        terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0.8f, 0, 2.5f, 0, 0,
                              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0))));
                    }
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    StringBuilder report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER))
                          .append("\nSynchronized animated terrain-frame medians, 40 interleaved samples after warmup; includes CPU updates/submission and final display conversion.\n");
                    for (boolean isometric : new boolean[] { false, true }) {
                        camera.setIsometric(isometric);
                        camera.fit(scene);
                        camera.camera.zoom *= 0.65f;
                        camera.camera.update();
                        gif.renderShadows(List.of());
                        procedural.renderShadows(List.of());
                        for (int rain = 0; rain <= 1; rain++) {
                            report.append(isometric ? "isometric" : "top").append(", rain=").append(rain).append(": ")
                                  .append(measure(output, gif, procedural, camera, rain)).append('\n');
                        }
                        Pixmap authored = frame(output, gif, camera, 0), dry = frame(output, procedural, camera, 0);
                        Pixmap light = frame(output, procedural, camera, 0.2f), wet = frame(output, procedural, camera, 1);
                        try {
                            save(authored, "water-color-gif-" + isometric);
                            save(dry, "water-color-procedural-" + isometric);
                            save(wet, "water-downpour-" + isometric);
                            int[] replacement = sample(dry, camera, new Coords(5, 4), 60);
                            assertTrue(mean(replacement, 8) > mean(replacement, 24),
                                  "Clear water keeps its cool absorption palette while reflecting the sky");
                            int[] drizzle = sample(light, camera, new Coords(5, 4), 60);
                            int[] downpour = sample(wet, camera, new Coords(5, 4), 60);
                            int lightPixels = differences(replacement, drizzle, 6);
                            int wetPixels = differences(replacement, downpour, 6);
                            assertTrue(wetPixels > replacement.length / 6 && wetPixels > lightPixels * 1.5,
                                  "Rain must be clearly visible and denser at downpour: " + lightPixels + ", " + wetPixels);
                            procedural.animate(0.31f, List.of());
                            Pixmap later = frame(output, procedural, camera, 1);
                            try { save(later, "water-downpour-later-" + isometric); } finally { later.dispose(); }
                        } finally {
                            authored.dispose(); dry.dispose(); light.dispose(); wet.dispose();
                        }
                    }
                    Files.writeString(new File(output(), "water-color-timing.txt").toPath(), report);
                    checkSplash(output, procedural, camera, "procedural");
                    checkSplash(output, gif, camera, "gif");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    gif.dispose(); procedural.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkSplash(SceneOutput output, GpuTerrain terrain, BoardCamera camera, String mode) {
        BoardScene waterfall = scene(true, 3);
        terrain.update(waterfall);
        // Isolate foam from the extra shadow cast by the raised upstream bank in the live-edit comparison.
        terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(0, 0, -1), Color.BLACK, Color.WHITE,
              Color.BLACK, Color.GRAY, Color.GRAY, Color.WHITE, 1, 0, false));
        camera.setIsometric(true);
        camera.fit(waterfall);
        camera.center(BoardGeometry.center(new Coords(4, 5), 0));
        camera.camera.zoom *= 0.55f;
        camera.camera.update();
        terrain.renderShadows(List.of());
        Pixmap splash = frame(output, terrain, camera, 0);
        try {
            save(splash, "waterfall-splash-" + mode);
            Vector3 base = BoardGeometry.center(new Coords(4, 5), 0).add(0, BoardGeometry.HEIGHT * 0.36f, 0);
            base.z = BoardGeometry.waterZ(waterfall.tile(new Coords(4, 5)));
            int[] before = sample(splash, camera, base, 9);
            terrain.animate(0.2f, List.of());
            Pixmap animated = frame(output, terrain, camera, 0);
            try {
                save(animated, "waterfall-splash-later-" + mode);
                assertTrue(differences(before, sample(animated, camera, base, 9), 6) > 20,
                      "Impact foam must animate at the waterfall base without rain");
            } finally { animated.dispose(); }
            // Removing the height difference must also remove the receiving surface's impact effect.
            terrain.update(scene(true, 0));
            terrain.renderShadows(List.of());
            Pixmap flat = frame(output, terrain, camera, 0);
            try {
                int[] after = sample(flat, camera, base, 9);
                assertTrue(mean(before, 8) > mean(after, 8) + 10,
                      "An ordinary flat connection must not retain bright impact foam after a live edit: "
                            + mean(before, 8) + " -> " + mean(after, 8));
            } finally { flat.dispose(); }
        } finally { splash.dispose(); }
    }

    private static String measure(SceneOutput output, GpuTerrain gif, GpuTerrain procedural, BoardCamera camera, float rain) {
        double[][] times = new double[2][40];
        GpuTerrain[] terrain = { gif, procedural };
        for (int sample = -12; sample < 40; sample++) {
            for (int order = 0; order < 2; order++) {
                int mode = (sample + order) & 1;
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                terrain[mode].animate(1f / 60, List.of());
                draw(output, terrain[mode], camera, rain);
                Gdx.gl.glFinish();
                if (sample >= 0) { times[mode][sample] = (System.nanoTime() - start) / 1e6; }
            }
        }
        Arrays.sort(times[0]); Arrays.sort(times[1]);
        return "GIF=" + times[0][20] + " ms, procedural=" + times[1][20] + " ms";
    }

    private static Pixmap frame(SceneOutput output, GpuTerrain terrain, BoardCamera camera, float rain) {
        draw(output, terrain, camera, rain);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static void draw(SceneOutput output, GpuTerrain terrain, BoardCamera camera, float rain) {
        terrain.setWetness(rain);
        output.draw(terrain, camera, true);
    }

    /** Pixel comparisons use the same linear blending and final display boundary as the scene. */
    private static final class SceneOutput implements AutoCloseable {
        private final FrameBuffer scene;
        private FrameBuffer pixels;
        private GpuSceneDisplay display;

        SceneOutput() {
            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
            scene = GpuHeatGlow.buffer(width, height, true);
            try {
                pixels = GpuAtmosphere.buffer(width, height, true);
                display = new GpuSceneDisplay();
            } catch (RuntimeException error) {
                scene.dispose();
                if (pixels != null) { pixels.dispose(); }
                throw error;
            }
        }

        void draw(GpuTerrain terrain, BoardCamera camera, boolean transparent) {
            terrain.renderShadows(camera.camera, List.of());
            scene.begin();
            Gdx.gl.glDepthMask(true);
            ScreenUtils.clear(GpuColor.linear(BACKDROP.r) * GpuSceneRadiance.SCALE,
                  GpuColor.linear(BACKDROP.g) * GpuSceneRadiance.SCALE,
                  GpuColor.linear(BACKDROP.b) * GpuSceneRadiance.SCALE, 1, true);
            terrain.render(camera.camera);
            if (transparent) { terrain.renderTransparent(camera.camera); }
            display.render(scene, pixels);
        }

        @Override
        public void close() {
            FrameBuffer.unbind();
            display.dispose();
            scene.dispose();
            pixels.dispose();
        }
    }

    private static int[] sample(Pixmap image, BoardCamera camera, Coords coords, int radius) {
        return sample(image, camera, BoardGeometry.center(coords, 0).add(0, 0, -BoardGeometry.HEX_SCALE), radius);
    }

    private static int[] sample(Pixmap image, BoardCamera camera, Vector3 center, int radius) {
        int side = radius * 2 + 1;
        int[] result = new int[side * side];
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                Vector3 screen = camera.camera.project(new Vector3(center).add(x, y, 0));
                result[(y + radius) * side + x + radius] = image.getPixel(Math.round(screen.x), Math.round(screen.y));
            }
        }
        return result;
    }

    private static double mean(int[] image, int shift) {
        long sum = 0;
        for (int pixel : image) { sum += (pixel >>> shift) & 255; }
        return sum / (double) image.length;
    }

    private static int differences(int[] first, int[] second, int threshold) {
        int count = 0;
        for (int i = 0; i < first.length; i++) {
            int difference = 0;
            for (int shift : new int[] { 8, 16, 24 }) { difference += Math.abs(((first[i] >>> shift) & 255) - ((second[i] >>> shift) & 255)); }
            if (difference > threshold) { count++; }
        }
        return count;
    }

    private static File output() {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        return output;
    }

    private static void save(Pixmap image, String name) {
        PixmapIO.writePNG(Gdx.files.absolute(new File(output(), name + ".png").getAbsolutePath()), image);
    }

    private static BoardScene lunarScene() {
        BoardScene scene = scene(true, 3);
        var tiles = new ArrayList<>(scene.tiles());
        for (var kind : BoardLiquid.Kind.values()) {
            var at = new Coords(6 + kind.ordinal(), 4);
            var tile = scene.tile(at);
            int depth = kind == BoardLiquid.Kind.WATER || kind == BoardLiquid.Kind.HAZARDOUS ? 1 : -1;
            tiles.set(at.getX() * scene.height() + at.getY(), new BoardScene.Tile(at, 2, depth,
                  kind == BoardLiquid.Kind.WATER, 0, tile.surface(), null, null,
                  List.of(), List.of(), new BoardLiquid(kind, "", 0), null, true));
        }
        return scene.withTiles(tiles);
    }

    private static BoardScene scene(boolean river, int drop) {
        BufferedImage gray = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { gray.setRGB(x, y, 0xff888888); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(gray);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 10; y++) {
                boolean water = !river || x == 4;
                tiles.add(new BoardScene.Tile(new Coords(x, y), river && y < 5 ? drop : 0, water ? 2 : -1, false,
                      0, BoardScene.Surface.ROCK, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 12, 10, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
