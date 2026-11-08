/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.environment.ShadowMap;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sunlit cliff interiors must not acquire a shadow-map pattern as the board camera moves. The raised hex is a
 * two-level concrete step, whose cast faces stand exactly on the hex outline without relief of their own, so any
 * shadow on its sunlit faces is an artefact; natural cliffs shade themselves with their own relief.
 */
@Tag("on-demand")
class GpuShadowSmokeTest {
    /** Height of the raised hex, in levels. */
    private static final int LEVELS = 2;

    @Test
    void sunlitCliffsStayUnshadowedAcrossCameraAnglesAndZooms() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkCliffs();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Cliff shadow regression", failure.get());
        }
    }

    /** Diagnostic variants keep the shipped Mines mesh, fixed light and zero UI shadow strength unchanged. */
    @Test
    void minesCliffLightingSeparatesTriangleGatesFromMappedNormals() throws Exception {
        runCliffLighting("Deserts/16x17 Mines 1.board", "mines-cliff", new Coords(9, 3), 1, 13, 0);
    }

    @Test
    void boxCanyonCliffLightingSeparatesTriangleGatesFromMappedNormals() throws Exception {
        runCliffLighting("Map Pack Savannahs/16x17 Box Canyon (Savannah).board", "box-canyon-cliff",
              new Coords(4, 9), 4, 7, 1);
    }

    private static void runCliffLighting(String board, String name, Coords focus, float level, float hour,
          float shadows) throws Exception {
        BoardScene scene = BoardScenes.installedScene(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1058, 1234);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> {
                    try { checkCliffLighting(scene, manager, name, focus, level, hour, shadows); }
                    catch (Throwable error) { failure.set(error); }
                }); }
                catch (Throwable error) { failure.set(error); }
                finally { try { manager.close(); } finally { Gdx.app.exit(); } }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError(name + " lighting diagnosis", failure.get()); }
    }

    private static void checkCliffLighting(BoardScene scene, GpuShaderManager manager, String name,
          Coords focus, float level, float hour, float shadows) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        assertTrue(renderer.contains(System.getProperty("megamek.gpu.expectedRenderer", "")), renderer);
        String original = GpuShaderSource.readDisk("terrain-sculpt.frag");
        // The sculpt chooses the face whose side the sun must reach; shadeSurface (surface-shading.glsl) gates on it.
        String incidentFace = "smoothSupport ? face : geometric";
        String viewGuard = "normal = shadingNormal(normal, geometric, view);";
        String output = "fragColor = vec4(result, 1.0);";
        for (String anchor : List.of(incidentFace, viewGuard, output)) {
            assertEquals(1, original.split(java.util.regex.Pattern.quote(anchor), -1).length - 1);
        }
        // These are causal review probes, not proposed fixes. Real opaque back-side rejection stays in production.
        var variants = new java.util.LinkedHashMap<String, String>();
        variants.put("baseline", original);
        variants.put("normal-maps-off", original);
        // A cliff whose incident face is the light itself is always on its lit side.
        variants.put("cliff-light-gate-off", original.replace(incidentFace, "cliff ? light : " + incidentFace));
        variants.put("cliff-view-normal-guard-off", original.replace(viewGuard, "if (!cliff) " + viewGuard));
        variants.put("incidence", original.replace(output, """
              #if defined(lightingFlag) && numDirectionalLights > 0
                  fragColor = cliff ? vec4(.5 + .5 * vec3(dot(geometric, light), dot(face, light),
                        dot(normal, light)), .5) : vec4(0.0, 0.0, 0.0, 1.0);
              #else
                  fragColor = vec4(0.0, 0.0, 0.0, 1.0);
              #endif
              """));
        variants.put("cliff-triangle-gate", original.replace(incidentFace, "geometric"));
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), name);
        java.nio.file.Files.createDirectories(directory.toPath());
        var report = new ArrayList<java.util.Map<String, Object>>();
        var probes = new ArrayList<java.util.Map<String, Object>>();
        var terrain = new GpuTerrain();
        try (var frame = new GpuSceneTestOutput()) {
            var lighting = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0))
                  .tuned(shadows, BoardAtmosphere.DEFAULT_AMBIENT_SHARE, 1, null);
            assertEquals(shadows, lighting.shadowStrength());
            terrain.update(scene);
            terrain.animate(.5f, List.of());
            for (int pose = 0; pose < 2; pose++) {
                var camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(true);
                camera.camera.zoom = .22f;
                camera.orbit(pose * 60, 0);
                camera.center(BoardGeometry.center(focus, level));
                var relative = lighting.relativeTo(camera.camera);
                terrain.setAtmosphere(relative);
                terrain.renderShadows(camera.camera, List.of());
                float[] reference = null, referenceDepth = null, uncut = null;
                for (var variant : variants.entrySet()) {
                    var changed = manager.apply(java.util.Map.of("terrain-sculpt.frag", variant.getValue()));
                    assertTrue(changed.success(), changed.message());
                    terrain.setNormalMaps(!variant.getKey().equals("normal-maps-off"));
                    draw(frame, terrain, camera).dispose();
                    float[] pixels = frame.linearPixels(), depth = minesDepth(frame);
                    draw(frame, terrain, camera).dispose();
                    org.junit.jupiter.api.Assertions.assertArrayEquals(pixels, frame.linearPixels(),
                          "Fixed scene and clock repeat exactly: " + variant.getKey());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(depth, minesDepth(frame),
                          "Frozen geometry depth repeats exactly");
                    if (reference == null) { reference = pixels; referenceDepth = depth; }
                    org.junit.jupiter.api.Assertions.assertArrayEquals(referenceDepth, depth,
                          "Lighting-only variants preserve every visible triangle/depth");
                    if (variant.getKey().equals("cliff-light-gate-off")) { uncut = pixels; }
                    if (variant.getKey().equals("cliff-triangle-gate")) {
                        // The triangle gate darkens curved cliff pixels the smooth support keeps lit, wherever the
                        // current meshes put them; each of them is already sun-facing, so no eligibility bypass is needed.
                        int darkened = 0;
                        for (int i = 0; i < pixels.length; i += 4) {
                            if (reference[i] <= pixels[i] + .01f) { continue; }
                            darkened++;
                            assertEquals(uncut[i], reference[i], 1e-6f,
                                  "An already sun-facing base surface needs no eligibility bypass");
                        }
                        assertTrue(darkened > 500, "The reproduced curved cliff keeps incident light: " + darkened);
                        probes.add(java.util.Map.of("pose", pose, "darkenedByTriangleGate", darkened));
                    }
                    int altered = 0, cliffs = 0, rejectedSmoothFront = 0, rejectedMappedFront = 0;
                    float maxChange = 0;
                    for (int i = 0; i < pixels.length; i += 4) {
                        float difference = 0;
                        for (int channel = 0; channel < 4; channel++) {
                            assertTrue(Float.isFinite(pixels[i + channel]), "Every actual HDR channel is finite");
                            if (channel < 3) { difference = Math.max(difference, Math.abs(pixels[i + channel] - reference[i + channel])); }
                        }
                        if (difference > 0) { altered++; }
                        maxChange = Math.max(maxChange, difference);
                        if (variant.getKey().equals("incidence") && pixels[i + 3] == .5f) {
                            cliffs++;
                            // RGB stores (N.L+1)/2 for actual triangle, smooth mesh and final mapped normals.
                            if (pixels[i] <= .5f && pixels[i + 1] > .5f) { rejectedSmoothFront++; }
                            if (pixels[i] <= .5f && pixels[i + 2] > .5f) { rejectedMappedFront++; }
                        }
                    }
                    if (variant.getKey().equals("incidence")) {
                        assertTrue(cliffs > 1000, "The diagnostic must actually cover the requested cliff");
                    }
                    report.add(java.util.Map.of("pose", pose, "variant", variant.getKey(),
                          "camera", camera.camera.position.toString(), "light", relative.direction().toString(),
                          "changedPixels", altered, "maxLinearChange", maxChange, "cliffPixels", cliffs,
                          "geometricBackSmoothFront", rejectedSmoothFront, "geometricBackMappedFront", rejectedMappedFront));
                    frame.display();
                    GpuReviewFrame.save(new File(directory, "pose-" + pose + "-" + variant.getKey() + ".png"));
                }
            }
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                  .writeValue(new File(directory, "diagnostics.json"), report);
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                  .writeValue(new File(directory, "regression-probes.json"), probes);
            System.out.println(name + " diagnostic renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER));
            System.out.println(name + " diagnosis: " + report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            try { terrain.dispose(); }
            finally { assertTrue(manager.apply(java.util.Map.of()).success(), "Restore original shader sources"); }
        }
    }

    private static float[] minesDepth(GpuSceneTestOutput frame) {
        frame.scene().begin();
        try {
            var buffer = com.badlogic.gdx.utils.BufferUtils.newFloatBuffer(frame.scene().getWidth() * frame.scene().getHeight());
            Gdx.gl.glReadPixels(0, 0, frame.scene().getWidth(), frame.scene().getHeight(),
                  GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer);
            float[] result = new float[buffer.capacity()];
            buffer.get(result);
            return result;
        } finally { frame.scene().end(); }
    }

    private static void checkCliffs() throws Exception {
        // Mottled ground art: paved ground has no grass cover, and review captures need visible artwork.
        BufferedImage ground = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        java.util.Random mottle = new java.util.Random(7);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) {
                int grey = 140 + mottle.nextInt(40);
                ground.setRGB(x, y, 0xff000000 | grey << 16 | grey << 8 | grey);
            }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(ground);
        Coords raised = new Coords(4, 4);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(raised) ? LEVELS : 0, -1, false, 0,
                      BoardScene.Surface.CONCRETE, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
        FrameBuffer clearDepth = constantDepth(1);
        Texture unshadowed = clearDepth.getColorBufferTexture();
        GpuTerrain terrain = new GpuTerrain();
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.center(BoardGeometry.center(raised, LEVELS / 2f));
        terrain.update(scene);
        var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        var sides = new BoardSurface(scene, scene.tile(raised)).sides(scene, BoardGeometry.floor(scene));
        int compared = 0, mismatched = 0;
        boolean captured = false;
        try (var outputFrame = new GpuSceneTestOutput()) {
            terrain.setAtmosphere(lighting.relativeTo(camera.camera));
            Gdx.gl.glDepthMask(true);
            terrain.renderShadows(camera.camera, List.of());
            long beforeDisplay = GpuFamilyAssemblyReview.shadowChecksum(terrain);
            var shadowBuffer = ((DirectionalShadowLight) terrain.environment().shadowMap).getFrameBuffer();
            shadowBuffer.begin();
            try {
                // Stale nearer occluders must be erased, even when equal-depth redraws would pass LEQUAL.
                Gdx.gl.glDepthMask(true);
                Gdx.gl.glClearDepthf(0);
                Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            } finally {
                Gdx.gl.glClearDepthf(1);
                shadowBuffer.end();
            }
            draw(outputFrame, terrain, camera).dispose();
            // The final screen pass disables depth writes. A forced same-view rebuild must still clear its depth.
            terrain.refreshShadows();
            terrain.renderShadows(camera.camera, List.of());
            assertEquals(beforeDisplay, GpuFamilyAssemblyReview.shadowChecksum(terrain),
                  "Rebuilding shadows after the final display pass retains the exact hardware shadow map");
            for (float zoom : new float[] { 0.35f, 0.7f }) {
                camera.camera.zoom = zoom;
                for (float tilt : new float[] { 35, 55, 75 }) {
                    camera.tilt(tilt - camera.tilt());
                    for (int angle = 0; angle < 24; angle++) {
                        camera.orbit(15, 0);
                        var relative = lighting.relativeTo(camera.camera);
                        terrain.setAtmosphere(relative);
                        terrain.renderShadows(camera.camera, List.of());
                        List<Vector3> samples = new ArrayList<>();
                        for (BoardSurface.Side side : sides) {
                            Vector3 normal = new Vector3(side.b()).sub(side.a()).crs(Vector3.Z).nor();
                            if (normal.dot(camera.camera.direction) > -0.3f || normal.dot(relative.direction()) > -0.04f) {
                                continue;
                            }
                            for (int along = 2; along <= 8; along++) {
                                for (int height = 2; height <= 7; height++) {
                                    Vector3 point = new Vector3(side.a()).lerp(side.b(), along / 10f);
                                    point.z = BoardGeometry.LEVEL * LEVELS * height / 10f;
                                    samples.add(point);
                                }
                            }
                        }
                        Pixmap actual = draw(outputFrame, terrain, camera);
                        Pixmap reference = drawUnshadowed(outputFrame, terrain, camera, unshadowed);
                        try {
                            int errors = 0;
                            for (Vector3 point : samples) {
                                compared++;
                                if (difference(sample(actual, camera, point), sample(reference, camera, point)) > 2) {
                                    errors++;
                                }
                            }
                            mismatched += errors;
                            if (!captured && errors > 0) {
                                draw(outputFrame, terrain, camera).dispose();
                                capture("shadow-cliff-banding.png");
                                captured = true;
                            }
                        } finally {
                            actual.dispose();
                            reference.dispose();
                        }
                    }
                }
            }
            assertTrue(compared > 5000, "Probe sunlit faces through full orbits, multiple tilts and zooms");
            assertEquals(0, mismatched, "Sunlit cliff interiors must match the unshadowed reference; probes=" + compared);
            camera.setIsometric(true);
            camera.camera.zoom = 0.35f;
            camera.orbit(15, 35 - camera.tilt());
            terrain.setAtmosphere(lighting.relativeTo(camera.camera));
            terrain.renderShadows(camera.camera, List.of());
            draw(outputFrame, terrain, camera).dispose();
            capture("shadow-cliff-clean.png");
            checkCastShadow(outputFrame, terrain, camera, raised, sides, lighting, unshadowed);
            assertDepthTexture(terrain.environment().shadowMap.getDepthMap().texture);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
            clearDepth.dispose();
        }
    }

    private static void checkCastShadow(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera, Coords raised,
          List<BoardSurface.Side> sides, BoardAtmosphere.Lighting lighting, Texture unshadowed) {
        camera.setIsometric(false);
        camera.center(BoardGeometry.center(raised, 0));
        terrain.setAtmosphere(lighting);
        terrain.renderShadows(camera.camera, List.of());
        Vector3 direction = new Vector3(lighting.direction().x, lighting.direction().y, 0).nor();
        BoardSurface.Side downstream = sides.stream().max(java.util.Comparator.comparingDouble(side ->
              new Vector3(side.b()).sub(side.a()).crs(Vector3.Z).nor().dot(direction))).orElseThrow();
        Vector3 foot = new Vector3(downstream.a()).lerp(downstream.b(), 0.5f);
        foot.z = 0;
        float length = BoardGeometry.LEVEL * LEVELS * (float) Math.hypot(lighting.direction().x, lighting.direction().y)
              / -lighting.direction().z;
        Pixmap actual = draw(outputFrame, terrain, camera);
        Pixmap reference = drawUnshadowed(outputFrame, terrain, camera, unshadowed);
        try {
            File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(new File(directory,
                  "shadow-cast-actual.png").getAbsolutePath()), actual);
            com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(new File(directory,
                  "shadow-cast-reference.png").getAbsolutePath()), reference);
            for (float distance : new float[] { 2, length / 2, length + 8 }) {
                Vector3 point = foot.cpy().mulAdd(direction, distance);
                int lit = sample(reference, camera, point) >>> 24;
                int shaded = sample(actual, camera, point) >>> 24;
                var shadow = terrain.environment().shadowMap;
                Vector3 projected = point.cpy().prj(shadow.getProjViewTrans()).scl(.5f).add(.5f, .5f, .5f);
                System.out.printf(java.util.Locale.ROOT,
                      "CAST_SHADOW distance=%.6f expectedLength=%.6f world=%s shadowUv=%s lit=%d actual=%d%n",
                      distance, length, point, projected, lit, shaded);
                if (distance >= length && Math.abs(lit - shaded) > 2) {
                    var buffer = ((DirectionalShadowLight) shadow).getFrameBuffer();
                    int x = Math.round(projected.x * buffer.getWidth() - .5f);
                    int y = Math.round(projected.y * buffer.getHeight() - .5f);
                    assertTrue(x > 0 && x < buffer.getWidth() - 1 && y > 0 && y < buffer.getHeight() - 1);
                    buffer.begin();
                    try {
                        var values = com.badlogic.gdx.utils.BufferUtils.newFloatBuffer(9);
                        Gdx.gl.glReadPixels(x - 1, y - 1, 3, 3, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, values);
                        float[] depths = new float[9];
                        values.get(depths);
                        System.out.println("CAST_SHADOW nearestDepth3x3=" + java.util.Arrays.toString(depths));
                    } finally { buffer.end(); }
                }
                if (distance < length) {
                    assertTrue(lit - shaded > 20, "The cliff must still cast a shadow, including at its foot");
                } else {
                    assertEquals(lit, shaded, 2, "Ground beyond the cast shadow must stay lit");
                }
            }
        } finally {
            actual.dispose();
            reference.dispose();
        }
    }

    /** Real comparison-texture references; RGBA stand-ins are not valid inputs to sampler2DShadow. */
    static FrameBuffer constantDepth(float depth) {
        FrameBuffer buffer = GpuShadowLight.depthBuffer(2, 2);
        buffer.begin();
        try {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearDepthf(depth);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        } finally {
            Gdx.gl.glClearDepthf(1);
            buffer.end();
        }
        return buffer;
    }

    static void assertDepthTexture(GLTexture texture) {
        texture.bind(0);
        var value = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, value);
        assertEquals(GL30.GL_COMPARE_REF_TO_TEXTURE, value.get(0));
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_FUNC, value);
        assertEquals(GL20.GL_LEQUAL, value.get(0));
        // Lookups name their level: the finest compares bilinearly, the coarser ones (GpuShadowLight.levels) as well.
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, value);
        assertEquals(GL20.GL_LINEAR_MIPMAP_NEAREST, value.get(0));
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, value);
        assertEquals(0, value.get(0));
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, value);
        assertEquals(GpuShadowLight.LEVELS, value.get(0));
        Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, value);
        assertEquals(GL20.GL_LINEAR, value.get(0));
        assertEquals(GL30.GL_DEPTH_COMPONENT16, org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
              GL20.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT));
    }

    private static Pixmap drawUnshadowed(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera, Texture unshadowed) {
        var shadow = terrain.environment().shadowMap;
        // Keep the same shader variant and light uniforms, but supply a map with no occluders.
        terrain.environment().shadowMap = new ShadowMap() {
            private final TextureDescriptor<Texture> depth = new TextureDescriptor<>(unshadowed);

            @Override
            public Matrix4 getProjViewTrans() { return shadow.getProjViewTrans(); }

            @Override
            public TextureDescriptor<Texture> getDepthMap() { return depth; }
        };
        try {
            return draw(outputFrame, terrain, camera);
        } finally {
            terrain.environment().shadowMap = shadow;
        }
    }

    private static Pixmap draw(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera) {
        outputFrame.begin(0.03f, 0.045f, 0.06f, 1);
        terrain.render(camera.camera);
        outputFrame.display();
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int sample(Pixmap image, BoardCamera camera, Vector3 point) {
        Vector3 screen = camera.camera.project(point.cpy());
        int x = (int) (screen.x * image.getWidth() / Gdx.graphics.getWidth());
        int y = (int) (screen.y * image.getHeight() / Gdx.graphics.getHeight());
        assertTrue(x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight(), "Probe must be on screen");
        return image.getPixel(x, y);
    }

    private static int difference(int a, int b) {
        return Math.max(Math.abs((a >>> 24) - (b >>> 24)), Math.max(Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)),
              Math.abs((a >>> 8 & 255) - (b >>> 8 & 255))));
    }

    private static void capture(String name) throws Exception {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }
}
