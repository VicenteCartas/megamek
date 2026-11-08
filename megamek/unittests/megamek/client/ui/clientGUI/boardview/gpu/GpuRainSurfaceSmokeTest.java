/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL integration: wet ground and transparent water share rain animation without extra draws. */
@Tag("on-demand")
class GpuRainSurfaceSmokeTest {
    @Test
    void rainAnimatesNearbySurfacesAndDisappearsAtOverviewScale() throws Exception {
        Hex[] hexes = new Hex[12 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 12; x++) {
                Hex hex = new Hex(0);
                hex.setTheme("grass");
                if (x < 4) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                else if (x < 8) { hex.addTerrain(new Terrain(Terrains.WATER, 1)); }
                hexes[y * 12 + x] = hex;
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(12, 8, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GLProfiler profiler = new GLProfiler(Gdx.graphics);
                    try (var outputFrame = new GpuSceneTestOutput()) {
                        Gdx.graphics.setVSync(false);
                        terrain.update(scene);
                        var settings = new BoardAtmosphere.Settings(13, 0.8f, 0, 2.5f, 0, 0,
                              new BoardAtmosphere.Effects(1, 0, 0, 0, 0, 0, 0));
                        terrain.setAtmosphere(BoardAtmosphere.lighting(settings));
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        profiler.enable();
                        StringBuilder timings = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER))
                              .append("\nSynchronized terrain plus display-conversion median, 24 alternating samples; ")
                              .append("includes CPU submission.\n");
                        for (boolean isometric : new boolean[] { false, true }) {
                            camera.setIsometric(isometric);
                            camera.fit(scene);
                            // A close view keeps the rings resolvable in both camera presets.
                            camera.camera.zoom *= 0.55f;
                            camera.camera.update();
                            terrain.renderShadows(List.of());
                            timings.append(isometric ? "isometric: " : "top: ").append(measure(outputFrame, terrain, camera)).append('\n');
                            profiler.reset();
                            Pixmap dry = frame(outputFrame, terrain, camera, 0);
                            int draws = profiler.getDrawCalls();
                            profiler.reset();
                            Pixmap wet = frame(outputFrame, terrain, camera, 1);
                            assertEquals(draws, profiler.getDrawCalls(), "Rain must not add draw calls");
                            Pixmap same = frame(outputFrame, terrain, camera, 1);
                            assertEquals(wet.getPixels(), same.getPixels(), "Rendering alone must not advance the rain clock");
                            terrain.animate(0.37f, List.of());
                            Pixmap laterDry = frame(outputFrame, terrain, camera, 0);
                            Pixmap laterWet = frame(outputFrame, terrain, camera, 1);
                            try {
                                save(wet, "rain-surfaces-" + (isometric ? "isometric" : "top"));
                                assertTrue(animatedRain(dry, wet, laterDry, laterWet, camera, new Coords(5, 4)) > 5,
                                      "Water must show animated rain beyond its existing animated texture");
                                int puddles = 0;
                                for (int x = 0; x < 4; x++) {
                                    for (int y = 1; y < 7; y++) {
                                        puddles += animatedRain(dry, wet, laterDry, laterWet, camera, new Coords(x, y));
                                    }
                                }
                                assertTrue(puddles > 10, "Visible paved puddles must show animated rain: " + puddles);
                            } finally {
                                dry.dispose(); wet.dispose(); same.dispose(); laterDry.dispose(); laterWet.dispose();
                            }
                        }
                        camera.setIsometric(false);
                        camera.fit(scene);
                        camera.camera.zoom = BoardGeometry.WIDTH * Gdx.graphics.getBackBufferWidth()
                              / (camera.camera.viewportWidth * 10);
                        camera.camera.update();
                        terrain.renderShadows(List.of());
                        timings.append("overview: ").append(measure(outputFrame, terrain, camera)).append('\n');
                        Pixmap distantDry = frame(outputFrame, terrain, camera, 0);
                        Pixmap distantWet = frame(outputFrame, terrain, camera, 1);
                        try {
                            Vector3 water = camera.camera.project(BoardGeometry.center(new Coords(5, 4), 0));
                            assertEquals(distantDry.getPixel((int) water.x, (int) water.y),
                                  distantWet.getPixel((int) water.x, (int) water.y),
                                  "Distant water skips both rain reflection and ripples");
                        } finally {
                            distantDry.dispose(); distantWet.dispose();
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        checkWetPigment();
                        checkRainAmount();
                        Files.writeString(new File(System.getProperty("megamek.gpu.screenshots"), "rain-surface-timing.txt")
                              .toPath(), timings);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        profiler.disable();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    /** Read the production wet-pigment function in linear RGBA16F, without a display curve or lighting. */
    private static void checkWetPigment() {
        try (var probe = new WetPigmentProbe()) {
            for (float[] pigment : new float[][] { { 0, 0, 0 }, { 1, 1, 1 }, { .04f, .18f, .5f }, { .8f, .5f, .2f } }) {
                float[] dry = probe.read(pigment, 0, 0, 0);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(pigment[channel], dry[channel], .0005f, "Dry pigment stays unchanged in linear light");
                }
                float[] previous = dry;
                for (float absorption : new float[] { .1f, .25f, .5f, .75f, 1 }) {
                    float[] wet = probe.read(pigment, absorption, 0, 0);
                    for (int channel = 0; channel < 3; channel++) {
                        assertTrue(Float.isFinite(wet[channel]) && wet[channel] >= 0 && wet[channel] <= previous[channel],
                              "Increasing saturation must not brighten pigment or produce invalid radiance");
                        if (pigment[channel] == 0 || pigment[channel] == 1) {
                            assertEquals(pigment[channel], wet[channel], 0, "Black and perfectly scattering white endpoints");
                        }
                    }
                    previous = wet;
                }
            }
            float[] pigment = { .8f, .5f, .2f };
            float[] saturated = probe.read(pigment, .5f, 1, 0);
            assertTrue(saturated[2] / pigment[2] < saturated[0] / pigment[0],
                  "The more absorbing pigment channel must lose a larger fraction; wetting is not a gray light gain");
            float[] dry = probe.read(pigment, 0, 0, 0);
            float[] film = probe.read(pigment, 0, 1, 1);
            assertEquals(0, dry[3], 0, "No rain means no added ground absorption");
            assertTrue(saturated[3] > film[3] && film[3] > 0,
                  "Absorbent ground darkens more than material which retains surface film");
            float previous = 0;
            for (float wet : new float[] { 0, .25f, .5f, .75f, 1 }) {
                float amount = probe.read(pigment, 0, wet, .5f)[3];
                assertTrue(amount >= previous && amount <= 1, "Ground absorption follows wetness continuously");
                previous = amount;
            }
        }
    }

    private static final class WetPigmentProbe implements AutoCloseable {
        private final FrameBuffer target = GpuHeatGlow.buffer(4, 4);
        private final Mesh quad = GpuAtmosphere.screenQuad();
        private final ShaderProgram shader = GpuGlsl.compile("Wet material pigment reference",
              GpuShaderSource.read("atmosphere.vert"), """
              #version 330 core
              layout(location = 0) out vec4 fragColor;
              vec3 v_cloudPosition = vec3(0.0);
              uniform vec3 u_probePigment;
              uniform float u_probeAbsorption;
              uniform float u_probeWet;
              uniform float u_probeResponse;
              """ + GpuShaderSource.read("rain-surface.glsl") + """
              void main() {
                  fragColor = vec4(wetAlbedo(u_probePigment, u_probeAbsorption),
                        groundAbsorption(u_probeWet, u_probeResponse));
              }
              """);

        float[] read(float[] pigment, float absorption, float wet, float response) {
            target.begin();
            try {
                GpuAtmosphere.screenState();
                shader.bind();
                shader.setUniformf("u_probePigment", pigment[0], pigment[1], pigment[2]);
                shader.setUniformf("u_probeAbsorption", absorption);
                shader.setUniformf("u_probeWet", wet);
                shader.setUniformf("u_probeResponse", response);
                quad.render(shader, GL20.GL_TRIANGLES);
                var pixels = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(2, 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                float[] result = new float[4]; pixels.get(result); return result;
            } finally { target.end(); }
        }

        @Override public void close() { shader.dispose(); quad.dispose(); target.dispose(); }
    }

    private static void checkRainAmount() {
        var manager = new GpuShaderManager();
        try { manager.run(() -> { checkRainAmount(manager); checkBiomeRainAbsorption(manager); }); }
        finally { manager.close(); }
    }

    /** Render actual rain response, then read its material coverage independently of sky-tinted diffuse light. */
    private static void checkRainAmount(GpuShaderManager manager) {
        BufferedImage gray = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { gray.setRGB(x, y, 0xff888888); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(gray);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 8; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.CONCRETE, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 12, 8, tiles, List.of(), List.of(), -1, "", List.of());
        GpuTerrain terrain = new GpuTerrain();
        try (var outputFrame = new GpuSceneTestOutput()) {
            terrain.update(scene);
            // Ripple normals need angularly varying illumination: a uniform sky with no sun produces an
            // almost constant reflection from a flat pool in the top view. Use the actual daylight environment.
            var lighting = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
            terrain.setAtmosphere(lighting);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            for (boolean isometric : new boolean[] { false, true }) {
                camera.setIsometric(isometric);
                camera.fit(scene);
                camera.camera.zoom *= 0.75f;
                camera.camera.update();
                terrain.renderShadows(camera.camera, List.of());
                Pixmap light = frame(outputFrame, terrain, camera, 0.2f);
                Pixmap moderate = frame(outputFrame, terrain, camera, 0.6f), heavy = frame(outputFrame, terrain, camera, 1);
                int[] a = patch(light, camera), c = patch(heavy, camera);
                try {
                    save(light, "puddle-amount-light-" + isometric);
                    save(moderate, "puddle-amount-moderate-" + isometric);
                    save(heavy, "puddle-amount-heavy-" + isometric);
                    // The former fixed ripple centers repeated their complete pattern every 1.25 seconds.
                    terrain.animate(1.25f, List.of());
                    Pixmap laterLight = frame(outputFrame, terrain, camera, 0.2f), laterHeavy = frame(outputFrame, terrain, camera, 1);
                    try {
                        int lightImpacts = changed(a, patch(laterLight, camera));
                        int heavyImpacts = changed(c, patch(laterHeavy, camera));
                        save(laterLight, "puddle-amount-light-later-" + isometric);
                        save(laterHeavy, "puddle-amount-heavy-later-" + isometric);
                        System.out.println("Rain impacts (isometric=" + isometric + ") light=" + lightImpacts
                              + ", heavy=" + heavyImpacts + "; direct=" + lighting.direct()
                              + ", sky=" + lighting.sky() + ", horizon=" + lighting.horizon());
                        assertTrue(heavyImpacts > lightImpacts && heavyImpacts > 50,
                              "Rain must increase impacts and avoid repeating the fixed pattern: " + lightImpacts + ", " + heavyImpacts);
                    } finally {
                        laterLight.dispose(); laterHeavy.dispose();
                    }
                } finally {
                    light.dispose(); moderate.dispose(); heavy.dispose();
                }
            }
            // The blue sky also illuminates dry pigment under PBR, so blue-minus-red cannot identify water.
            // Change only the output expression: the actual wetness, footprint and material coverage stay live.
            String ground = GpuShaderSource.readDisk("terrain-normal.frag");
            String output = "fragColor = vec4(groundLighting(albedo, normal, geometric, wet, response, puddle, finish, occlusion), 1.0);";
            assertTrue(ground.contains(output), "The coverage probe must read the production puddle value");
            var changed = manager.apply(Map.of("terrain-normal.frag",
                  ground.replace(output, "fragColor = vec4(vec3(step(.01, puddle)), 1.0);")));
            assertTrue(changed.success() && changed.updated() > 0, changed.message());
            for (boolean isometric : new boolean[] { false, true }) {
                camera.setIsometric(isometric);
                camera.fit(scene);
                camera.camera.zoom *= .75f;
                camera.camera.update();
                int[] areas = new int[4];
                float[] rain = { 0, .2f, .6f, 1 };
                for (int i = 0; i < rain.length; i++) {
                    Pixmap coverage = frame(outputFrame, terrain, camera, rain[i]);
                    try { areas[i] = puddlePixels(patch(coverage, camera)); }
                    finally { coverage.dispose(); }
                }
                assertEquals(0, areas[0], "Dry ground must contain no rain puddles");
                assertTrue(areas[2] > areas[1] && areas[3] > areas[2] * 1.3 && areas[3] > 1000,
                      "Rain must expand visible puddle coverage (isometric=" + isometric + "): " + Arrays.toString(areas));
                System.out.println("Rain puddle coverage (isometric=" + isometric + "): " + Arrays.toString(areas));
            }
        } finally {
            terrain.dispose();
        }
    }

    /** Actual enhanced biome materials: rain cannot absorb their already saturated pigment a second time. */
    private static void checkBiomeRainAbsorption(GpuShaderManager manager) {
        assertTrue(manager.apply(Map.of()).success());
        String source = GpuShaderSource.readDisk("terrain-sculpt.frag").replace("\r\n", "\n");
        String probe = GpuShaderSource.replaceOnce(source, "#ifdef lightingFlag\n    vec3 view = -viewDirection();",
              "vec3 probePigment = albedo;\n#ifdef lightingFlag\n    vec3 view = -viewDirection();",
              "wet biome pigment output");
        probe = GpuShaderSource.replaceOnce(probe, "fragColor = vec4(result, 1.0);",
              "fragColor = vec4(probePigment, 2.0 + biomeDamp);", "wet biome validity and dampness output");
        assertTrue(manager.apply(Map.of("terrain-sculpt.frag", probe)).success());
        try {
            for (int kind = 0; kind < 4; kind++) {
                BoardScene.Biome biome = kind == 1 ? BoardScene.Biome.MUD
                      : kind == 2 ? BoardScene.Biome.QUICKSAND : BoardScene.Biome.MARSH;
                boolean sand = kind == 3;
                var scene = BoardScenes.scene(coords -> {
                    var tile = BoardScenes.tile(coords, coords.getX() < 5 ? biome : BoardScene.Biome.NONE, 0);
                    if (!sand) { return tile; }
                    return new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.SAND, null, null, List.of(),
                          List.of(), BoardLiquid.NONE, null, true,
                          BoardRoad.Kind.NONE, BoardFireSmoke.NONE, tile.biome());
                });
                var terrain = new GpuTerrain();
                try (var output = new GpuSceneTestOutput()) {
                    terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(false);
                    camera.fit(scene);
                    terrain.update(scene, camera.camera);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    float[][] frames = new float[3][];
                    for (int step = 0; step < frames.length; step++) {
                        terrain.setWetness(step * .5f);
                        output.draw(terrain, camera.camera, false);
                        frames[step] = output.linearPixels();
                    }
                    int soaked = 0, dryChanged = 0, partial = 0;
                    float maxSoakedChange = 0;
                    for (int pixel = 0; pixel < frames[0].length; pixel += 4) {
                        // Only this actual sculpt program tags alpha above 2; exclude props/vegetation/sky.
                        float damp = frames[0][pixel + 3] - 2;
                        if (damp < 0 || damp > 1) { continue; }
                        assertEquals(frames[0][pixel + 3], frames[2][pixel + 3], 0,
                              "Rain must not change the permanent biome coverage at a frozen clock");
                        float difference = 0;
                        for (int channel = 0; channel < 3; channel++) {
                            float dry = frames[0][pixel + channel], half = frames[1][pixel + channel];
                            float wet = frames[2][pixel + channel];
                            assertTrue(Float.isFinite(wet) && wet >= 0 && wet <= half + .0005f && half <= dry + .0005f,
                                  "Actual biome pigment must stay bounded and darken monotonically with rain");
                            difference = Math.max(difference, Math.abs(dry - wet));
                        }
                        if (damp >= .30f) { soaked++; maxSoakedChange = Math.max(maxSoakedChange, difference); }
                        if (damp == 0 && difference > .002f) { dryChanged++; }
                        if (damp > .01f && damp < .25f) { partial++; }
                    }
                    String label = biome + (sand ? "-SAND" : "");
                    System.out.printf(java.util.Locale.ROOT,
                          "WET_BIOME %s soaked=%d dryChanged=%d partial=%d maxSoakedChange=%.7f%n",
                          label, soaked, dryChanged, partial, maxSoakedChange);
                    assertTrue(soaked > 100 && dryChanged > 100 && partial > 20,
                          "Real material controls must include soaked, ordinary dry and transitioning pigment: " + label);
                    assertTrue(maxSoakedChange <= .0005f,
                          "Already saturated peat/mud/mineral pigment must not darken again in rain: " + label);
                } finally { terrain.dispose(); }
            }
        } catch (Exception error) { throw new AssertionError("Actual biome rain absorption", error); }
        finally { assertTrue(manager.apply(Map.of()).success()); }
    }

    private static int[] patch(Pixmap pixels, BoardCamera camera) {
        int[] result = new int[128 * 128];
        Vector3 center = BoardGeometry.center(new Coords(6, 4), 0);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                Vector3 screen = camera.camera.project(center.cpy().add((x - 64) * 2, (y - 64) * 2, 0));
                result[y * 128 + x] = pixels.getPixel(Math.round(screen.x), Math.round(screen.y));
            }
        }
        return result;
    }

    private static int puddlePixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) { if ((pixel >>> 24) > 128) { count++; } }
        return count;
    }

    private static int changed(int[] first, int[] second) {
        int count = 0;
        for (int i = 0; i < first.length; i++) {
            if (Math.abs(((first[i] >>> 8) & 255) - ((second[i] >>> 8) & 255)) > 1) { count++; }
        }
        return count;
    }

    private static Pixmap frame(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera, float wetness) {
        draw(outputFrame, terrain, camera, wetness);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static void draw(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera, float wetness) {
        terrain.setWetness(wetness);
        outputFrame.begin(0.08f, 0.10f, 0.12f, 1);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        outputFrame.display();
    }

    private static String measure(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera) {
        double[][] samples = new double[2][24];
        for (int i = -8; i < 24; i++) {
            for (int wet = 0; wet < 2; wet++) {
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                draw(outputFrame, terrain, camera, wet);
                Gdx.gl.glFinish();
                if (i >= 0) { samples[wet][i] = (System.nanoTime() - start) / 1e6; }
            }
        }
        Arrays.sort(samples[0]);
        Arrays.sort(samples[1]);
        return "dry=" + samples[0][12] + " ms, rain=" + samples[1][12] + " ms";
    }

    /** Subtract the dry frame at each time to exclude the water texture's own motion. */
    private static int animatedRain(Pixmap dry, Pixmap wet, Pixmap laterDry, Pixmap laterWet,
          BoardCamera camera, Coords target) {
        int changed = 0;
        for (int y = -24; y <= 24; y++) {
            for (int x = -24; x <= 24; x++) {
                Vector3 screen = camera.camera.project(BoardGeometry.center(target, 0).add(x, y, 0));
                int px = (int) screen.x, py = (int) screen.y;
                if (px < 0 || py < 0 || px >= dry.getWidth() || py >= dry.getHeight()) { continue; }
                int a = dry.getPixel(px, py), b = wet.getPixel(px, py);
                int c = laterDry.getPixel(px, py), d = laterWet.getPixel(px, py);
                int difference = 0;
                for (int shift : new int[] { 8, 16, 24 }) {
                    difference += Math.abs(((b >>> shift & 255) - (a >>> shift & 255))
                          - ((d >>> shift & 255) - (c >>> shift & 255)));
                }
                if (difference > 2) { changed++; }
            }
        }
        return changed;
    }

    private static void save(Pixmap pixels, String name) {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        PixmapIO.writePNG(Gdx.files.absolute(new File(output, name + ".png").getAbsolutePath()), pixels);
    }
}
