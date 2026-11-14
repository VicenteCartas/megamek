/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.gdx.UiKit;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Checks radiance at the actual scene/composite and standard-model lighting boundaries. */
@Tag("on-demand")
class GpuLightingConsistencySmokeTest {
    @TempDir
    Path directory;

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final String SOURCE = """
          #version 330 core
          layout(location = 0) out vec4 fragColor;
          uniform float u_radiance;
          void main() {
              fragColor = vec4(vec3(u_radiance), 1.0);
              gl_FragDepth = .5;
          }
          """;

    @Test
    void isolatedPreviewsKeepUniformColorCoverageAndDepthAcrossTheImage() {
        nativeCheck(() -> {
            var quad = GpuAtmosphere.screenQuad();
            var source = GpuGlsl.compile("Preview color boundary", GpuShaderSource.read("atmosphere.vert"),
                  GpuSceneRadiance.fragment(SOURCE.replace("vec3(u_radiance), 1.0", "vec3(u_radiance), .25")));
            GpuSceneDisplay adaptedDisplay = null;
            try (var frame = new GpuSceneTestOutput(WIDTH, HEIGHT)) {
                adaptedDisplay = new GpuSceneDisplay();
                // A late night gain must cover every source; the next default call must reset the uniform to one.
                for (float exposure : new float[] { 1, BoardAtmosphere.lighting(new BoardAtmosphere.Settings(
                      0, 0, 0, 2, 0, 0)).adaptation(), 1 }) {
                    for (float radiance : new float[] { .02f, .2f, 4f }) {
                        frame.begin(0, 0, 0, 0);
                        GpuAtmosphere.screenState();
                        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                        Gdx.gl.glDepthMask(true);
                        source.bind();
                        source.setUniformf("u_radiance", radiance);
                        quad.render(source, GL20.GL_TRIANGLES);
                        if (exposure == 1) { adaptedDisplay.render(frame.scene(), frame.pixels()); }
                        else { adaptedDisplay.render(frame.scene(), frame.pixels(), exposure); }
                        double adapted = radiance * exposure;
                        double shoulder = Math.min(adapted, .6)
                              + .4 * (1 - Math.exp(-Math.max(adapted - .6, 0) / .4));
                        int expected = (int) Math.round(255 * display(shoulder));
                        var pixel = BufferUtils.newByteBuffer(4);
                        var depth = BufferUtils.newFloatBuffer(1);
                        for (int x : new int[] { 0, WIDTH / 2, WIDTH - 1 }) {
                            for (int y : new int[] { 0, HEIGHT / 2, HEIGHT - 1 }) {
                                pixel.clear();
                                Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixel);
                                for (int channel = 0; channel < 3; channel++) {
                                    assertEquals(expected, Byte.toUnsignedInt(pixel.get(channel)), 1,
                                          "Preview color must not depend on pixel position: " + x + "," + y);
                                }
                                assertEquals(64, Byte.toUnsignedInt(pixel.get(3)), 1, "Preserve scene coverage");
                                depth.clear();
                                Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
                                assertEquals(.5, depth.get(0), .000001, "Preserve the scene depth for preview overlays");
                            }
                        }
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                if (adaptedDisplay != null) { adaptedDisplay.dispose(); }
                source.dispose(); quad.dispose();
            }
        });
    }

    @Test
    void completeSceneAdaptsCanonicalMaterialsEmissionSpillAndSelectedHeatOnce() {
        nativeCheck(() -> {
            assertEquals(WIDTH, Gdx.graphics.getBackBufferWidth());
            assertEquals(HEIGHT, Gdx.graphics.getBackBufferHeight());
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var environmentLight = new GpuEnvironmentLighting();
            var assets = new GpuAssets();
            var spill = new GpuLavaLighting(assets);
            var environment = new Environment();
            var direction = new DirectionalLight();
            environment.add(direction);
            environmentLight.lavaLighting = spill;
            environment.set(environmentLight);
            var model = new ModelBuilder().createBox(80, 80, 4,
                  new Material(new GpuLinearColors(), ColorAttribute.createDiffuse(.18f, .24f, .30f, 1),
                        ColorAttribute.createEmissive(0, 0, 0, 1), new BlendingAttribute(1),
                        new DepthTestAttribute(GL20.GL_LEQUAL, true)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var instance = new ModelInstance(model);
            var material = instance.materials.first();
            var emission = material.get(ColorAttribute.class, ColorAttribute.Emissive).color;
            var opacity = material.get(BlendingAttribute.class, BlendingAttribute.Type);
            float[] emitted = { .02f, .01f, .005f };
            var emitter = new GpuLavaLighting.Source(0, new Vector3(0, 0, 12), .4f,
                  new Vector3(.8f, .12f, .015f), 40);
            var camera = camera(false);
            var dry = scene(false);
            var molten = scene(true);
            var quad = GpuAtmosphere.screenQuad();
            var source = GpuGlsl.compile("Canonical heat selection", GpuShaderSource.read("atmosphere.vert"),
                  GpuSceneRadiance.fragment(SOURCE.replace("uniform float u_radiance", "uniform vec4 u_radiance")
                        .replace("vec4(vec3(u_radiance), 1.0)", "u_radiance")));
            var conditions = List.of(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0),
                  new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0),
                  new BoardAtmosphere.Settings(12, 1, 0, 2.5f, 0, 0),
                  new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0, BoardAtmosphere.Effects.NONE,
                        Atmosphere.VACUUM),
                  new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0, BoardAtmosphere.Effects.NONE,
                        Atmosphere.VACUUM, 25, false));
            float[] firstSpill = null;
            double[] firstSpillError = null;
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                System.out.println("Canonical mixture renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                for (int scenario = 0; scenario <= conditions.size(); scenario++) {
                    var settings = conditions.get(Math.min(scenario, conditions.size() - 1));
                    boolean zeroSource = scenario == conditions.size();
                    atmosphere.configure(settings);
                    atmosphere.updateLight(camera);
                    var lighting = atmosphere.lighting();
                    // Vacuum retains its authored near-black sky. A separate controlled black source tests zero.
                    if (zeroSource) {
                        for (var color : List.of(lighting.direct(), lighting.ambient(), lighting.sky(),
                              lighting.horizon(), lighting.fog())) { color.set(0, 0, 0, 1); }
                        assertEquals(Math.pow(112, .6), atmosphere.exposure(), .0001,
                              "A zero source uses the bounded moonless meter, without creating fill");
                    }
                    environment.set(ColorAttribute.createAmbientLight(lighting.ambient()));
                    direction.set(lighting.direct(), lighting.direction());
                    // A black visible palette is not a black lighting environment: zero analytic and image fill.
                    environmentLight.set(zeroSource ? lighting.tuned(1, 0, 0, null) : lighting);
                    atmosphere.configureClouds(terrain, dry);
                    float[][] samples = new float[5][];
                    for (int pass = 0; pass < samples.length; pass++) {
                        // Actual model shader: D, D+E, D+S, D+E+S, then half of that mixture over D.
                        boolean emissive = pass == 1 || pass >= 3, local = pass >= 2;
                        atmosphere.begin(WIDTH, HEIGHT, 0);
                        opacity.opacity = 1;
                        if (pass == 4) {
                            emission.set(0, 0, 0, 1);
                            spill.setFungus(List.of());
                            spill.update(dry);
                            batch.begin(camera); batch.render(instance, environment); batch.end();
                            opacity.opacity = .5f;
                        }
                        emission.set(emissive ? emitted[0] : 0, emissive ? emitted[1] : 0,
                              emissive ? emitted[2] : 0, 1);
                        spill.setFungus(local ? List.of(emitter) : List.of());
                        spill.update(dry);
                        batch.begin(camera); batch.render(instance, environment); batch.end();
                        samples[pass] = centerCanonical();
                        float depth = centerDepth();
                        assertEquals(1, samples[pass][3], .0005, "Alpha-over an opaque model remains opaque");
                        assertTrue(depth > 0 && depth < 1, "The actual model must write visible depth");
                        atmosphere.end(camera, terrain, dry, 0);
                        assertComposite(samples[pass], lighting, atmosphere.exposure(), atmosphere.nightVision(), 0,
                              new float[3],
                              "Canonical material mixture pass=" + pass + "; " + settings);
                        assertEquals(depth, centerDepth(), 1f / 16777215,
                              "The compositor retains the model's hardware depth");
                        System.out.printf(Locale.ROOT, "MIXTURE hour=%.1f clouds=%.1f pressure=%s moon=%s zero=%s "
                                    + "pass=%d gain=%.9g raw=%.9g,%.9g,%.9g alpha=%.9g depth=%.9g%n",
                              settings.hour(), settings.clouds(), settings.pressure(), settings.moonlight(),
                              zeroSource, pass, atmosphere.exposure(), samples[pass][0], samples[pass][1], samples[pass][2],
                              samples[pass][3], depth);
                    }
                    float[] local = new float[3];
                    double[] localError = new double[3];
                    for (int c = 0; c < 3; c++) {
                        assertEquals(emitted[c], samples[1][c] - samples[0][c],
                              halfSpacing(samples[0][c]) + halfSpacing(samples[1][c]),
                              "Material emission stays canonical, independent of adaptation; channel=" + c);
                        local[c] = samples[2][c] - samples[0][c];
                        localError[c] = halfSpacing(samples[0][c]) + halfSpacing(samples[2][c]);
                        assertTrue(local[c] > 0, "The actual shared spill-light path contributes in every channel");
                        assertEquals(local[c], samples[3][c] - samples[1][c], localError[c]
                                    + halfSpacing(samples[1][c]) + halfSpacing(samples[3][c]),
                              "Local spill and material emission add before the one output gain");
                        assertEquals((samples[0][c] + samples[3][c]) * .5, samples[4][c],
                              .5 * (halfSpacing(samples[0][c]) + halfSpacing(samples[3][c]))
                                    + halfSpacing(samples[4][c]), "Half-transparent models blend canonical sources");
                        if (firstSpill != null) {
                            assertEquals(firstSpill[c], local[c], firstSpillError[c] + localError[c],
                                  "The same local emitter is not pre-adapted with the sun/sky");
                        }
                        if (zeroSource) {
                            assertEquals(0, samples[0][c], "Zero external light must not invent reflected radiance");
                        } else {
                            assertTrue(samples[0][c] > 0, "Real sun/sky illumination must reach the material");
                        }
                    }
                    if (firstSpill == null) { firstSpill = local; firstSpillError = localError; }

                    // Uniform canonical heat makes extraction/blur independently observable, without changing gain.
                    atmosphere.configureClouds(terrain, molten);
                    for (float red : new float[] { 2, 2.5f, 3 }) {
                        atmosphere.begin(WIDTH, HEIGHT, 0);
                        GpuAtmosphere.screenState();
                        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                        Gdx.gl.glDepthMask(true);
                        source.bind();
                        source.setUniformf("u_radiance", red, .25f, .015625f, 1);
                        quad.render(source, GL20.GL_TRIANGLES);
                        float[] raw = centerCanonical();
                        assertEquals(red, raw[0], "Exactly representable canonical heat boundary");
                        atmosphere.end(camera, terrain, molten, 0);
                        // These exact red values select 0, 1/2, 1; blue/red and red/green select full warmth.
                        float selected = red - 2;
                        float[] glow = heatCenter(atmosphere);
                        for (int c = 0; c < 3; c++) {
                            assertEquals(raw[c] * selected, glow[c], 4 * halfSpacing(raw[c] * selected),
                                  "Heat is selected in canonical units before gain, including zero-source vacuum");
                        }
                        assertComposite(raw, lighting, atmosphere.exposure(), atmosphere.nightVision(), selected, glow,
                              "Canonical heat red=" + red + "; " + settings);
                        assertEquals(.5, centerDepth(), .000001, "Heat compositing retains source depth");
                        System.out.printf(Locale.ROOT, "HEAT hour=%.1f pressure=%s moon=%s zero=%s gain=%.9g "
                                    + "red=%.1f selected=%.1f glow=%.9g,%.9g,%.9g%n",
                              settings.hour(), settings.pressure(), settings.moonlight(), zeroSource,
                              atmosphere.exposure(), red, selected, glow[0], glow[1], glow[2]);
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                source.dispose(); quad.dispose(); model.dispose(); batch.dispose();
                environmentLight.dispose(); assets.dispose(); terrain.dispose(); atmosphere.dispose();
            }
        });
    }

    @Test
    void editorThumbnailAndHexSectionCallersApplyTheirDisplayGain() throws Exception {
        var fixture = new RigidGlbTest();
        fixture.directory = directory;
        var data = RigidGlb.load(fixture.sided(true));
        var editor = new BoardEditorSession();
        var board = Board.createEmptyBoard(1, 1);
        var coords = new Coords(0, 0);
        board.setHex(coords, new Hex(2, "", ""));
        editor.game().setBoard(board);
        editor.command(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "SELECT"), null);
        editor.pointer(coords, 0, 0, false);
        var snapshot = editor.snapshot();
        nativeCheck(() -> {
            try {
                checkModelThumbnailGain(data);
                checkHexSectionGain(snapshot);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    private static void checkModelThumbnailGain(com.badlogic.gdx.graphics.g3d.model.data.ModelData data)
          throws ReflectiveOperationException {
        var render = GpuEditorModelPreviews.class.getDeclaredMethod("render",
              com.badlogic.gdx.graphics.g3d.Model.class, boolean.class, Image.class);
        var model = new com.badlogic.gdx.graphics.g3d.Model(data);
        render.setAccessible(true);
        var previews = new GpuEditorModelPreviews();
        var probeScene = GpuHeatGlow.buffer(128, 96, true);
        var probeDisplay = GpuAtmosphere.buffer(128, 96, true);
        try {
            float expected = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS).adaptation();
            float night = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0)).adaptation();
            float[] first = null;
            for (int pass = 0; pass < 2; pass++) {
                // Each thumbnail has its own target, just as a palette page does; previews owns both images.
                var target = new Image();
                render.invoke(previews, model, false, target);
                assertEquals("Ready", target.getUserObject());
                var display = (GpuSceneDisplay) previewField(previews, "display");
                var image = (FrameBuffer) ((java.util.Map<?, ?>) previewField(previews, "images")).get(target);
                assertPreviewGain(display, expected);
                float[] pixels = previewPixels(image, false);
                previewForeground(previewPixels(image, true), image.getWidth(), image.getHeight());
                assertPreviewBackdrop(pixels, image.getWidth(), image.getHeight(), .045f, .06f, .065f);
                if (first == null) { first = pixels; }
                else { assertArrayEquals(first, pixels, "A reused display must restore the default thumbnail gain"); }
                image.end();
                if (pass == 0) {
                    probeScene.begin();
                    Gdx.gl.glDepthMask(true);
                    Gdx.gl.glClearColor(.02f * GpuSceneRadiance.SCALE, 0, 0, 1);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                    probeScene.end();
                    display.render(probeScene, probeDisplay, night);
                    assertPreviewGain(display, night);
                    probeDisplay.end();
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            previews.dispose();
            model.dispose();
            probeScene.dispose();
            probeDisplay.dispose();
        }
    }

    private static void checkHexSectionGain(BoardEditorSession.Snapshot snapshot) throws ReflectiveOperationException {
        var terrain = new GpuTerrain();
        var theme = new GpuBoardSkin();
        var stage = new Stage(new ScreenViewport());
        var section = new GpuHexSection(null, () -> terrain, new UiKit(theme.skin), () -> 0, object -> object.id());
        stage.addActor(section);
        stage.getViewport().update(WIDTH, HEIGHT, true);
        section.setSize(WIDTH, HEIGHT);
        var tile = BoardScenes.newTile(new Coords(0, 0), 2, -1, false, 0, BoardScene.Surface.GRASS,
              List.of(), List.of(), BoardLiquid.NONE);
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
        var manual = new BoardScene(0, 1, 1, scene.tiles(), List.of(), List.of(), -1, "", List.of(),
              new BoardScene.Light(0, 0));
        var settings = List.of(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0),
              new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0),
              new BoardAtmosphere.Settings(12, 1, 0, 2.5f, 0, 0));
        var defaults = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        var manualMeter = defaults.ambient().cpy();
        // This fixture's manual light is vertical: ground receives its entire published direct color.
        manualMeter.r += defaults.direct().r;
        manualMeter.g += defaults.direct().g;
        manualMeter.b += defaults.direct().b;
        try {
            terrain.setColumns(true);
            terrain.setGrass(false);
            Object geometry = null;
            float[] cameraMatrix = null;
            for (int pass = 0; pass < 6; pass++) {
                float expected;
                if (pass < 3 || pass == 5) {
                    var lighting = BoardAtmosphere.lighting(settings.get(pass == 5 ? 0 : pass));
                    terrain.setAtmosphere(lighting);
                    expected = lighting.adaptation();
                } else {
                    terrain.setAtmosphere(null);
                    expected = BoardAtmosphere.adaptation(pass == 3 ? manualMeter : new Color(.7f, .7f, .7f, 1));
                }
                terrain.update(pass == 3 ? manual : scene);
                terrain.renderShadows(camera(false), List.of());
                if (geometry == null) { geometry = previewField(terrain, "meshGeneration"); }
                assertEquals(geometry, previewField(terrain, "meshGeneration"), "Lighting changes do not rebuild geometry");
                drawChangedSection(terrain, stage, section, snapshot);
                assertEquals(pass + 1, section.renders, "One actual section draw per lighting change");
                var sectionCamera = (OrthographicCamera) previewField(section, "camera");
                if (cameraMatrix == null) { cameraMatrix = sectionCamera.combined.val.clone(); }
                else { assertArrayEquals(cameraMatrix, sectionCamera.combined.val, "Section camera remains unchanged"); }
                assertPreviewGain((GpuSceneDisplay) previewField(section, "display"), expected);
                var source = (FrameBuffer) previewField(section, "sceneBuffer");
                var target = (FrameBuffer) previewField(section, "buffer");
                float[] raw = previewPixels(source, false), rawDepth = previewPixels(source, true);
                float[] shown = previewPixels(target, false), shownDepth = previewPixels(target, true);
                int pixel = previewForeground(rawDepth, WIDTH, HEIGHT);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(exposedDisplay(raw[4 * pixel + channel] / GpuSceneRadiance.SCALE, expected),
                          shown[4 * pixel + channel], 2f / 255, "The actual section adapts its canonical scene once");
                }
                assertEquals(rawDepth[pixel], shownDepth[pixel], .0000001f, "Section display copies scene depth");
                assertEquals(raw[4 * pixel + 3], shown[4 * pixel + 3], .000001f, "Adaptation preserves coverage");
                assertPreviewBackdrop(shown, WIDTH, HEIGHT, .035f, .055f, .062f);
                int corner = (HEIGHT - 1) * WIDTH;
                assertEquals(linear(.035), raw[4 * corner] / GpuSceneRadiance.SCALE * expected, .00004,
                      "The scene clear compensates the fixed UI backdrop before the final gain");
                target.end();
            }
            // Sections meter their actual published light, including a manually positioned grazing source.
            var dawn = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(6.5f, 0, 0, 2.5f, 0, 0));
            terrain.setAtmosphere(dawn);
            drawChangedSection(terrain, stage, section, snapshot);
            assertPreviewGain((GpuSceneDisplay) previewField(section, "display"), dawn.adaptation());
            var grazingManual = new BoardScene(0, 1, 1, scene.tiles(), List.of(), List.of(), -1, "", List.of(),
                  new BoardScene.Light(1000, 0));
            terrain.setAtmosphere(null);
            terrain.update(grazingManual);
            var grazingSource = terrain.environment().get(DirectionalLightsAttribute.class,
                  DirectionalLightsAttribute.Type).lights.first();
            assertTrue(-grazingSource.direction.z < .5f, "This manual light must exercise the sloped receiver floor");
            Color grazingMeter = defaults.ambient().cpy();
            grazingMeter.r += .5f * defaults.direct().r;
            grazingMeter.g += .5f * defaults.direct().g;
            grazingMeter.b += .5f * defaults.direct().b;
            drawChangedSection(terrain, stage, section, snapshot);
            assertPreviewGain((GpuSceneDisplay) previewField(section, "display"), BoardAtmosphere.adaptation(grazingMeter));
            terrain.setAtmosphere(BoardAtmosphere.lighting(settings.getFirst()));
            terrain.update(scene);
            drawChangedSection(terrain, stage, section, snapshot);
            // Republishing equal colors as different objects does not invalidate the cached image.
            terrain.setAtmosphere(BoardAtmosphere.lighting(settings.getFirst()));
            assertSectionIdle(stage, section);
            var ambient = terrain.environment().get(ColorAttribute.class, ColorAttribute.AmbientLight).color;
            ambient.set(.25f, .25f, .25f, 1);
            drawChangedSection(terrain, stage, section, snapshot);
            Color before = ambient.cpy();
            float gain = terrain.lightAdaptation();
            // A constant-luminance tint below one display-code step defeats Color.equals and exposure-only caches.
            ambient.r += .0001f;
            ambient.g -= .0001f * .2126f / .7152f;
            assertEquals(before, ambient, "This fixture deliberately retains libGDX's quantized color equality");
            assertEquals(gain, terrain.lightAdaptation(), .000001f, "Only chroma changes, not the meter");
            drawChangedSection(terrain, stage, section, snapshot);
            var sun = terrain.environment().get(DirectionalLightsAttribute.class,
                  DirectionalLightsAttribute.Type).lights.first();
            sun.direction.rotate(Vector3.Z, 30);
            assertEquals(gain, terrain.lightAdaptation(), .000001f, "Yaw preserves downward incidence");
            drawChangedSection(terrain, stage, section, snapshot);
            terrain.environment().get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE)
                  .set(new Color(.4f, .5f, .6f, 1));
            assertEquals(gain, terrain.lightAdaptation(), .000001f, "Sky radiance can change without the ambient meter");
            drawChangedSection(terrain, stage, section, snapshot);
            assertEquals(geometry, previewField(terrain, "meshGeneration"));
            assertArrayEquals(cameraMatrix, ((OrthographicCamera) previewField(section, "camera")).combined.val);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            section.dispose();
            stage.dispose();
            theme.dispose();
            terrain.dispose();
        }
    }

    private static void drawChangedSection(GpuTerrain terrain, Stage stage, GpuHexSection section,
          BoardEditorSession.Snapshot snapshot) throws ReflectiveOperationException {
        assertFalse(terrain.busy(), "The section must notice lighting without a geometry/build trigger");
        int before = section.renders;
        section.update(snapshot, 1, List.of(), false);
        stage.getViewport().apply();
        stage.draw();
        assertSame(snapshot, previewField(section, "snapshot"), "The editor snapshot retains its identity");
        assertEquals(before + 1, section.renders, "Changed light redraws the section once");
        assertSectionIdle(stage, section);
    }

    private static void assertSectionIdle(Stage stage, GpuHexSection section) {
        int before = section.renders;
        for (int repeat = 0; repeat < 2; repeat++) {
            stage.getViewport().apply();
            stage.draw();
            assertEquals(before, section.renders, "Unchanged light and editor state reuse the cached image");
        }
    }

    private static Object previewField(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void assertPreviewGain(GpuSceneDisplay display, float expected) throws ReflectiveOperationException {
        var shader = (ShaderProgram) previewField(display, "shader");
        var actual = BufferUtils.newFloatBuffer(1);
        Gdx.gl.glGetUniformfv(shader.getHandle(), shader.getUniformLocation("u_exposure"), actual);
        assertEquals(expected, actual.get(0), .00001f, "The actual caller uploads its published light's gain");
    }

    private static float[] previewPixels(FrameBuffer target, boolean depth) {
        target.bind();
        int size = target.getWidth() * target.getHeight() * (depth ? 1 : 4);
        var values = BufferUtils.newFloatBuffer(size);
        Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(),
              depth ? GL20.GL_DEPTH_COMPONENT : GL20.GL_RGBA, GL20.GL_FLOAT, values);
        var result = new float[size];
        values.get(result);
        return result;
    }

    private static int previewForeground(float[] depth, int width, int height) {
        int selected = -1, covered = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int pixel = y * width + x;
                if (depth[pixel] < 1 && depth[pixel - 1] < 1 && depth[pixel + 1] < 1
                      && depth[pixel - width] < 1 && depth[pixel + width] < 1) {
                    covered++;
                    if (selected < 0 || depth[pixel] < depth[selected]) { selected = pixel; }
                }
            }
        }
        assertTrue(covered > 16, "The actual preview must contain rendered foreground, not just its clear color");
        return selected;
    }

    private static void assertPreviewBackdrop(float[] pixels, int width, int height, float r, float g, float b) {
        int corner = 4 * (height - 1) * width;
        assertEquals(r, pixels[corner], 2f / 255, "The UI backdrop stays fixed under scene adaptation");
        assertEquals(g, pixels[corner + 1], 2f / 255);
        assertEquals(b, pixels[corner + 2], 2f / 255);
        assertEquals(1, pixels[corner + 3], .000001f);
    }


    @Test
    void packedTetherColorDecodesBeforeHalfFloatSceneStorage() {
        nativeCheck(() -> {
            var shader = GpuGlsl.compile("Tether color boundary", GpuShaderSource.read("scene-lines.vert"),
                  GpuShaderSource.read("scene-lines.frag"));
            var shapes = new com.badlogic.gdx.graphics.glutils.ShapeRenderer(32, shader);
            var target = GpuHeatGlow.buffer(1, 1);
            try {
                target.begin();
                GpuAtmosphere.screenState();
                shapes.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, 1, 1));
                shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
                shapes.setColor(new Color(0xA9B8B8FF));
                shapes.rect(0, 0, 1, 1);
                shapes.end();
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(linear((channel == 0 ? 169.0 : 184.0) / 255),
                          pixel.get(channel) / GpuSceneRadiance.SCALE, .0003,
                          "Packed ShapeRenderer colors decode before storage scaling, without losing byte precision");
                }
                assertEquals(1, pixel.get(3), .0005, "Tether coverage is independent of radiance storage units");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { target.end(); target.dispose(); shapes.dispose(); shader.dispose(); }
        });
    }

    @Test
    void thermalPaletteInterpolatesDecodedEndpointsInLinearLight() {
        nativeCheck(() -> {
            String thermal = GpuShaderSource.read("unit-thermal.frag");
            String palette = thermal.substring(thermal.indexOf("vec3 thermalPalette("), thermal.indexOf("void main()"));
            var shader = GpuGlsl.compile("Thermal palette boundary", GpuShaderSource.read("atmosphere.vert"),
                  "layout(location = 0) out vec4 fragColor;\nuniform float u_heat;\n"
                        + GpuShaderSource.read("color-space.glsl") + palette
                        + "void main() { fragColor = vec4(thermalPalette(u_heat), 1.0); }");
            var target = GpuHeatGlow.buffer(1, 1);
            var quad = GpuAtmosphere.screenQuad();
            double[][] stops = { { .025, .055, .85 }, { .32, .015, 1 }, { .85, .015, .58 },
                  { 1, .035, .06 }, { 1, .40, .015 }, { 1, .90, .12 }, { 1, 1, .94 } };
            try {
                assertTrue(shader.isCompiled(), shader.getLog());
                target.begin();
                GpuAtmosphere.screenState();
                shader.bind();
                for (int sample = 0; sample <= 12; sample++) {
                    shader.setUniformf("u_heat", sample / 12f);
                    quad.render(shader, GL20.GL_TRIANGLES);
                    var pixel = BufferUtils.newFloatBuffer(4);
                    Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                    int low = sample / 2, high = Math.min(6, low + 1);
                    for (int channel = 0; channel < 3; channel++) {
                        double expected = sample % 2 == 0 ? linear(stops[low][channel])
                              : .5 * (linear(stops[low][channel]) + linear(stops[high][channel]));
                        assertEquals(expected, pixel.get(channel), .0005,
                              "Thermal stop/midpoint " + sample + ", channel " + channel);
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { target.end(); target.dispose(); quad.dispose(); shader.dispose(); }
        });
    }

    @Test
    void darkSceneSubnormalsSurviveStorageAndMaximumDisplayExposure() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var quad = GpuAtmosphere.screenQuad();
            var source = GpuGlsl.compile("Dark scene storage", GpuShaderSource.read("atmosphere.vert"),
                  GpuSceneRadiance.fragment(SOURCE));
            var camera = camera(false);
            var scene = scene(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                for (var settings : List.of(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 2),
                      new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 2, BoardAtmosphere.Effects.NONE,
                            Atmosphere.VACUUM, 25, false))) {
                    atmosphere.configure(settings);
                    atmosphere.updateLight(camera);
                    atmosphere.configureClouds(terrain, scene);
                    // Binary16's subnormal step is 2^-24 in storage units. Include exact steps and both sides of the
                    // normal boundary, so the diagnostic distinguishes truncation from flushing every subnormal.
                    double step = Math.scalb(1.0, -24) / GpuSceneRadiance.SCALE;
                    float[] inputs = { 0, (float) (step * .25), (float) (step * .5), (float) (step * .51),
                          (float) (step * .75), (float) step, (float) (step * 1.5), (float) (step * 1.99),
                          (float) (step * 2), (float) (step * 2.5), (float) (step * 4), .000001f, .000003f,
                          .00001f, .00004f, .0001f, .0005f, .001f, (float) (step * 511), (float) (step * 512),
                          (float) (step * 1023), (float) (step * 1024), (float) (step * 1025), .002f, .003f, .004f };
                    var checks = new ArrayList<Executable>();
                    var diagnostic = new StringBuilder("Dark scene quantization: ")
                          .append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; SCALE=").append(GpuSceneRadiance.SCALE)
                          .append("; conditions=").append(settings).append("; gain=").append(atmosphere.exposure())
                          .append("\ninput,stored,readCanonical,inputSteps,readSteps,errorSteps,displayExpected,displayActual\n");
                    for (float radiance : inputs) {
                        atmosphere.begin(WIDTH, HEIGHT, 0);
                        GpuAtmosphere.screenState();
                        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                        Gdx.gl.glDepthMask(true);
                        source.bind();
                        source.setUniformf("u_radiance", radiance);
                        quad.render(source, GL20.GL_TRIANGLES);
                        float measured = centerRadiance();
                        atmosphere.end(camera, terrain, scene, 0);
                        int displayed = centerRed();
                        long expectedDisplay = expectedComposite(new float[] { radiance, radiance, radiance },
                              atmosphere.lighting(), atmosphere.exposure(), atmosphere.nightVision(), 0,
                              new float[3])[0];
                        double spacing = Math.scalb(1.0, Math.max(-24,
                              Math.getExponent((double) radiance * GpuSceneRadiance.SCALE) - 10)) / GpuSceneRadiance.SCALE;
                        diagnostic.append(String.format(Locale.ROOT, "%.10g,%.10g,%.10g,%.5f,%.5f,%+.5f,%d,%d%n",
                              radiance, measured * GpuSceneRadiance.SCALE, measured,
                              radiance / step, measured / step, (measured - radiance) / step, expectedDisplay, displayed));
                        // OpenGL 4.6 section 17.3.8 permits either adjacent representable color value.
                        // Intel's native probe selects the lower half, so require that exact bracket, not nearest rounding.
                        double lower = Math.floor(radiance / spacing) * spacing;
                        double upper = Math.ceil(radiance / spacing) * spacing;
                        checks.add(() -> assertAll("Radiance " + radiance,
                              () -> assertTrue(Float.isFinite(measured)),
                              () -> assertTrue(measured == lower || measured == upper,
                                    () -> "Scene RGB must select an adjacent half: " + measured + " outside ["
                                          + lower + ", " + upper + "]"),
                              () -> {
                                  if (radiance > 0 && lower == upper) {
                                      assertEquals(radiance, measured, 0,
                                            "Exact positive half-float values must survive, including subnormals");
                                  }
                              },
                              () -> assertEquals(expectedDisplay, displayed, 1,
                                    "The maximum +2 EV grade exposes dark scene light through the same display transfer")));
                    }
                    // Complete every draw and report every sample before checking either storage or display behavior.
                    System.out.println(diagnostic);
                    assertAll(diagnostic.toString(), checks);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                source.dispose(); quad.dispose(); terrain.dispose(); atmosphere.dispose();
            }
        });
    }

    @Test
    void exposureRecoversOrdinaryHighlightsAndLiquidChangesKeepTheSceneTarget() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            Mesh quad = GpuAtmosphere.screenQuad();
            ShaderProgram source = GpuGlsl.compile("Lighting radiance reference",
                  GpuShaderSource.read("atmosphere.vert"), GpuSceneRadiance.fragment(SOURCE));
            var camera = camera(false);
            var dry = scene(false);
            var molten = scene(true);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, -2));
                int dim = radianceFrame(atmosphere, terrain, dry, camera, quad, source, 1);
                assertEquals(Math.round(255 * exposedDisplay(1, atmosphere.exposure())), dim, 2,
                      "The composite applies exposure in linear light and encodes exactly once");
                var depth = atmosphere.depthTexture();
                int bright = radianceFrame(atmosphere, terrain, dry, camera, quad, source, 4);
                assertTrue(bright > dim + 50,
                      "Negative exposure must recover distinct highlights above one: " + dim + "/" + bright);
                int withLava = radianceFrame(atmosphere, terrain, molten, camera, quad, source, 4);
                assertEquals(bright, withLava, 1, "White surface highlights keep the same response beside lava");
                assertSame(depth, atmosphere.depthTexture(), "A liquid edit must not replace the scene color/depth target");
                radianceFrame(atmosphere, terrain, dry, camera, quad, source, 4);
                assertSame(depth, atmosphere.depthTexture(), "Removing lava retains that same target");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                source.dispose();
                quad.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void standardModelsAddEmissionToReflectedLinearLightInTopAndIsometricViews() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var model = new ModelBuilder().createBox(80, 80, 4,
                  new Material(ColorAttribute.createDiffuse(new Color(.4f, .4f, .4f, 1)),
                        ColorAttribute.createEmissive(new Color(.25f, .25f, .25f, 1))),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var instance = new ModelInstance(model);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(1, 1, 1, 1));
            var scene = scene(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0));
                for (boolean isometric : new boolean[] { false, true }) {
                    // The attribute-less environment retains its analytic dielectric fallback. Emission adds
                    // after reflection, and this sum stays below the highlight shoulder in both views.
                    int expected = (int) Math.round(255 * exposedDisplay(dielectricRadiance(.4, isometric)
                          + linear(.25), atmosphere.exposure()));
                    var camera = camera(isometric);
                    atmosphere.updateLight(camera);
                    atmosphere.configureClouds(terrain, scene);
                    atmosphere.begin(WIDTH, HEIGHT, 0);
                    batch.begin(camera);
                    batch.render(instance, environment);
                    batch.end();
                    atmosphere.end(camera, terrain, scene, 0);
                    assertEquals(expected, centerRed(), 3,
                          "Model emission and reflected light must add before display encoding; iso=" + isometric);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                model.dispose();
                batch.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void transparentModelsBlendLinearRadianceBeforeTheFinalDisplayConversion() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var background = new ModelBuilder().createBox(80, 80, 4,
                  new Material(ColorAttribute.createDiffuse(.2f, .2f, .2f, 1)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var foreground = new ModelBuilder().createBox(80, 80, 4,
                  new Material(ColorAttribute.createDiffuse(.8f, .8f, .8f, 1), new BlendingAttribute(.5f)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var back = new ModelInstance(background);
            var front = new ModelInstance(foreground);
            front.transform.setToTranslation(0, 0, 10);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(1, 1, 1, 1));
            var scene = scene(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2.5f, 0, 0));
                for (boolean isometric : new boolean[] { false, true }) {
                    double radiance = (dielectricRadiance(.2, isometric) + dielectricRadiance(.8, isometric)) * .5;
                    int displayed = (int) Math.round(255 * exposedDisplay(radiance, atmosphere.exposure()));
                    var camera = camera(isometric);
                    atmosphere.updateLight(camera);
                    atmosphere.configureClouds(terrain, scene);
                    atmosphere.begin(WIDTH, HEIGHT, 0);
                    batch.begin(camera);
                    batch.render(back, environment);
                    batch.end();
                    batch.begin(camera);
                    batch.render(front, environment);
                    batch.end();
                    assertEquals(radiance, centerRadiance(), .003,
                          "Scene blending must average decoded material radiance; iso=" + isometric);
                    atmosphere.end(camera, terrain, scene, 0);
                    assertEquals(displayed, centerRed(), 2,
                          "A half-transparent bright material over dark ground must encode after blending; iso="
                                + isometric);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                foreground.dispose();
                background.dispose();
                batch.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void clearSkyInterpolatesDecodedPaletteStopsBeforeTheSingleDisplayConversion() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var camera = camera(false);
            var scene = scene(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0));
                atmosphere.updateLight(camera);
                atmosphere.configureClouds(terrain, scene);
                atmosphere.begin(WIDTH, HEIGHT, 0);
                // Independent authored noon red stops. Both stay below the highlight shoulder, so this isolates
                // the scene/gradient encoding contract from the tone curve and any rendered geometry.
                double scale = noonRedSkyScale(atmosphere.lighting());
                assertEquals(linear(.154) * scale, centerRadiance(), .00005,
                      "The scene clear must contain sky radiance decoded once from the authored color");
                atmosphere.end(camera, terrain, scene, 0);
                Pixmap column = Pixmap.createFromFrameBuffer(WIDTH / 2, 0, 1, HEIGHT);
                try {
                    for (int y : new int[] { 0, HEIGHT / 2, HEIGHT - 1 }) {
                        double v = (y + .5) / HEIGHT;
                        double blend = v * v * (3 - 2 * v);
                        // The uncovered backdrop is the UI's: its authored stops, blended as the UI draws them. Below
                        // the shoulder's knee the display shows that blend unchanged.
                        int expected = (int) Math.round(255 * (.54 + (.154 - .54) * blend));
                        assertEquals(expected, column.getPixel(0, y) >>> 24, 1,
                              "The parallel board backdrop must show the UI's authored gradient; row=" + y);
                    }
                } finally { column.dispose(); }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void depthlessModelsPreserveCoverageForTheUncoveredSkyGradient() {
        nativeCheck(() -> {
            var atmosphere = new GpuAtmosphere();
            var terrain = new GpuTerrain();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var model = new ModelBuilder().createBox(80, 80, 4,
                  new Material(ColorAttribute.createDiffuse(.8f, .8f, .8f, 1), new BlendingAttribute(.5f),
                        new DepthTestAttribute(GL20.GL_LEQUAL, false)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            var instance = new ModelInstance(model);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(1, 1, 1, 1));
            var scene = scene(false);
            var camera = camera(false);
            try {
                atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
                atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0));
                atmosphere.updateLight(camera);
                atmosphere.configureClouds(terrain, scene);
                atmosphere.begin(WIDTH, HEIGHT, 0);
                batch.begin(camera);
                batch.render(instance, environment);
                batch.end();
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                assertEquals(.5f, pixel.get(3), .001f,
                      "A half-transparent depthless model must cover half the sky, not one quarter");
                assertEquals((dielectricRadiance(.8, false) + linear(.154) * noonRedSkyScale(atmosphere.lighting())) * .5,
                      pixel.get(0) / GpuSceneRadiance.SCALE, .001,
                      "Coverage correction must preserve the ordinary linear RGB blend");
                var depth = BufferUtils.newFloatBuffer(1);
                Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
                assertEquals(1f, depth.get(0), "The depthless material must leave the sky depth untouched");
                atmosphere.end(camera, terrain, scene, 0);
                // Parallel board cameras retain a screen-space backdrop; uncovered coverage shows the UI's authored
                // gradient at this pixel, whose radiance the view's exposure brings to its authored display value.
                double v = (HEIGHT / 2.0 + .5) / HEIGHT;
                double blend = v * v * (3 - 2 * v);
                double sky = linear(.54 + (.154 - .54) * blend) / atmosphere.exposure();
                double radiance = .5 * dielectricRadiance(.8, false) + .5 * sky;
                assertEquals(Math.round(255 * exposedDisplay(radiance, atmosphere.exposure())), centerRed(), 1,
                      "The final pixel must contain half the material and half the UI's board backdrop");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                model.dispose();
                batch.dispose();
                terrain.dispose();
                atmosphere.dispose();
            }
        });
    }

    @Test
    void importedAndProceduralColorsMultiplyAndInterpolateInLinearLight() {
        nativeCheck(() -> {
            var batch = new ModelBatch(GpuUnitShader.provider());
            var target = GpuHeatGlow.buffer(WIDTH, HEIGHT, true);
            var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.drawPixel(0, 0, 0x808080FF);
            var texture = GpuColorTexture.create(pixels, false);
            pixels.dispose();
            try {
                for (boolean imported : new boolean[] { true, false }) {
                    var material = new Material(ColorAttribute.createDiffuse(.18f, .18f, .18f, 1),
                          TextureAttribute.createDiffuse(texture));
                    if (imported) { material.set(new GpuLinearColors()); }
                    var builder = new ModelBuilder();
                    builder.begin();
                    var part = builder.part("gradient", GL20.GL_TRIANGLES,
                          VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorUnpacked
                                | VertexAttributes.Usage.TextureCoordinates, material);
                    part.rect(gradientVertex(-40, -40, .02f), gradientVertex(40, -40, .5f),
                          gradientVertex(40, 40, .5f), gradientVertex(-40, 40, .02f));
                    var model = builder.end();
                    try {
                        target.begin();
                        Gdx.gl.glClearColor(0, 0, 0, 0);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        batch.begin(camera(false));
                        batch.render(new ModelInstance(model));
                        batch.end();
                        double fraction = (40 + .5) / 80;
                        double left = imported ? .02 : linear(.02), right = imported ? .5 : linear(.5);
                        double factor = imported ? .18 : linear(.18);
                        assertEquals(linear(128.0 / 255) * factor * (left * (1 - fraction) + right * fraction),
                              centerRadiance(), .00003,
                              "Imported linear values must stay linear; procedural sRGB must decode before interpolation/multiplication; imported="
                                    + imported);
                    } finally {
                        target.end();
                        model.dispose();
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                texture.dispose();
                target.dispose();
                batch.dispose();
            }
        });
    }

    private static MeshPartBuilder.VertexInfo gradientVertex(float x, float y, float value) {
        return new MeshPartBuilder.VertexInfo().setPos(x, y, 0).setCol(value, value, value, 1).setUV(0, 0);
    }

    @Test
    void importedGhostsEncodeOnceBeforeTheirExistingDisplaySpaceBlend() throws Exception {
        var fixture = new RigidGlbTest();
        fixture.directory = directory;
        var imported = RigidGlb.loadLods(fixture.embedded("png")).getFirst();
        nativeCheck(() -> {
            var textures = new HashMap<String, Texture>();
            var source = ModelTextures.create(imported, textures, (key, color) -> {
                throw new AssertionError("This imported fixture embeds its image");
            });
            var batch = new ModelBatch(GpuUnitShader.displayProvider());
            var target = GpuHeatGlow.buffer(WIDTH, HEIGHT, true);
            try {
                // Keep the actual imported linear COLOR_0 and material on simple, unlit carrier geometry,
                // isolating the ghost's input/output boundary from the separate BRDF regression tests.
                float pigment = imported.meshes.first().vertices[6];
                assertEquals(128f / 255, pigment, .000001f);
                for (int mode = 0; mode < 3; mode++) {
                    var material = source.materials.first().copy();
                    if (mode == 2) { GpuBoardOverlay.ghostly(material); }
                    else { GpuEditorPreview.ghostly(material, mode == 1); }
                    assertTrue(material.has(GpuLinearColors.TYPE), "Ghost material replacement must retain the mesh's linear color domain");
                    var builder = new ModelBuilder();
                    builder.begin();
                    var part = builder.part("imported-ghost", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                          | VertexAttributes.Usage.ColorUnpacked | VertexAttributes.Usage.TextureCoordinates, material);
                    part.rect(gradientVertex(-40, -40, pigment).setUV(.25f, .25f),
                          gradientVertex(40, -40, pigment).setUV(.25f, .25f),
                          gradientVertex(40, 40, pigment).setUV(.25f, .25f),
                          gradientVertex(-40, 40, pigment).setUV(.25f, .25f));
                    var model = builder.end();
                    try {
                        target.begin();
                        Gdx.gl.glClearColor(.2f, .2f, .2f, 0);
                        Gdx.gl.glDepthMask(true);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        batch.begin(camera(false));
                        batch.render(new ModelInstance(model));
                        batch.end();
                        // Paint is the movement destination's blue ghost; Erase is the same ghost in red.
                        double radiance = mode == 1 ? pigment + linear(.5) : linear(.25) * pigment + linear(.15);
                        double opacity = .75;
                        assertEquals(display(radiance) * opacity + .2 * (1 - opacity), centerValue(), .001,
                              "Imported paint, erase and movement ghosts encode once before blending; mode=" + mode);
                        var rgba = BufferUtils.newFloatBuffer(4);
                        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, rgba);
                        assertEquals(opacity * opacity, rgba.get(3), .001, "Display overlays retain their existing alpha blend");
                        var depth = BufferUtils.newFloatBuffer(1);
                        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
                        assertEquals(1f, depth.get(0), "Ghosts must keep the scene's depth");
                    } finally {
                        target.end();
                        model.dispose();
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                target.dispose();
                batch.dispose();
                source.dispose();
                textures.values().forEach(Texture::dispose);
            }
        });
    }

    @Test
    void unitMarkerAndDamageLayersBlendLinearTextureSamplesWithMaterialRadiance() {
        nativeCheck(() -> {
            var batch = new ModelBatch(GpuUnitShader.provider());
            var target = GpuHeatGlow.buffer(WIDTH, HEIGHT, true);
            var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0xFFFFFF80);
            var texture = GpuColorTexture.create(pixels, false);
            pixels.dispose();
            try {
                for (int pass = 0; pass < 3; pass++) {
                    boolean marker = pass == 0, glass = pass == 2;
                    float factor = glass ? 1 : .02f;
                    var material = new Material(ColorAttribute.createDiffuse(factor, factor, factor, 1), new GpuLinearColors());
                    if (marker) {
                        material.set(new GpuUnitCamouflage.Paint(0, texture, new Matrix4(), new Matrix3()));
                    } else {
                        material.set(new UnitDamageDisplay.Overlay(texture));
                    }
                    var builder = new ModelBuilder();
                    builder.begin();
                    var part = builder.part("paint", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                          | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorUnpacked, material);
                    part.setColor(glass ? GpuColor.linear(.21f, .67f, .73f) : Color.WHITE);
                    part.rect(-40, -40, 0, 40, -40, 0, 40, 40, 0, -40, 40, 0, 0, 0, 1);
                    var model = builder.end();
                    try {
                        target.begin();
                        Gdx.gl.glClearColor(0, 0, 0, 0);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        batch.begin(camera(false));
                        batch.render(new ModelInstance(model));
                        batch.end();
                        double alpha = 128.0 / 255;
                        assertEquals(glass ? linear(.21) : .02 * (1 - alpha) + alpha, centerRadiance(), .001,
                              "Material overlays blend linear light while imported cockpit glass stays unpainted; pass=" + pass);
                    } finally {
                        target.end();
                        model.dispose();
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                texture.dispose();
                target.dispose();
                batch.dispose();
            }
        });
    }

    /** Adjacent binary16 spacing in canonical radiance units; no nearest-rounding assumption. */
    private static double halfSpacing(double value) {
        return Math.scalb(1.0, Math.max(-24, Math.getExponent(Math.abs(value) * GpuSceneRadiance.SCALE) - 10))
              / GpuSceneRadiance.SCALE;
    }

    private static float[] centerCanonical() {
        var pixel = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
        float[] result = { pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3) };
        for (int c = 0; c < 3; c++) {
            result[c] /= GpuSceneRadiance.SCALE;
            assertTrue(Float.isFinite(result[c]), "Canonical scene channels must remain finite");
        }
        return result;
    }

    private static float centerDepth() {
        var depth = BufferUtils.newFloatBuffer(1);
        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        return depth.get(0);
    }

    /** Borrow the real heat target after end(); restore the visible framebuffer/viewport after its readback. */
    private static float[] heatCenter(GpuAtmosphere atmosphere) {
        try {
            var field = GpuAtmosphere.class.getDeclaredField("heatGlow");
            field.setAccessible(true);
            var heat = (GpuHeatGlow) field.get(atmosphere);
            field = GpuHeatGlow.class.getDeclaredField("glow");
            field.setAccessible(true);
            var target = (FrameBuffer) field.get(heat);
            target.begin();
            try {
                var pixel = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(target.getWidth() / 2, target.getHeight() / 2, 1, 1,
                      GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                return new float[] { pixel.get(0) / GpuSceneRadiance.SCALE,
                      pixel.get(1) / GpuSceneRadiance.SCALE, pixel.get(2) / GpuSceneRadiance.SCALE };
            } finally { target.end(); }
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Actual heat target", failure); }
    }

    /**
     * Independent RGB grade of an opaque pixel: selected heat bypasses tint and the night's rod vision ({@code night},
     * its share), but shares exactly one output gain.
     */
    private static long[] expectedComposite(float[] raw, BoardAtmosphere.Lighting light, double gain, double night,
          double selected, float[] glow) {
        double[] tint = { light.tint().r, light.tint().g, light.tint().b };
        double luminance = raw[0] * tint[0] * .2126 + raw[1] * tint[1] * .7152 + raw[2] * tint[2] * .0722;
        double[] ordinary = new double[3];
        for (int c = 0; c < 3; c++) {
            ordinary[c] = (luminance + (raw[c] * tint[c] - luminance) * light.saturation()) * (1 - selected) * gain;
        }
        ordinary = nightVision(ordinary, night);
        long[] result = new long[3];
        for (int c = 0; c < 3; c++) {
            double radiance = ordinary[c] / gain + raw[c] * selected + .12 * glow[c];
            result[c] = Math.round(255 * exposedDisplay(radiance, gain));
        }
        return result;
    }

    /**
     * Rod vision (atmosphere-grade.glsl nightVision) of exposed light: dim light turns blue-grey at its luminance,
     * light with a bright channel keeps its colour.
     */
    private static double[] nightVision(double[] exposed, double share) {
        double luminance = exposed[0] * .2126 + exposed[1] * .7152 + exposed[2] * .0722;
        double t = Math.clamp((Math.max(exposed[0], Math.max(exposed[1], exposed[2])) - .05) / .2, 0, 1);
        share *= 1 - t * t * (3 - 2 * t);
        if (share <= 0) { return exposed; }
        double rods = exposed[0] * .04 + exposed[1] * .46 + exposed[2] * .5;
        double[] cool = { .72, .9, 1.2 }, shifted = new double[3];
        for (int c = 0; c < 3; c++) { shifted[c] = exposed[c] + (rods * cool[c] - exposed[c]) * share; }
        double scale = luminance / Math.max(shifted[0] * .2126 + shifted[1] * .7152 + shifted[2] * .0722, 1e-6);
        for (int c = 0; c < 3; c++) { shifted[c] *= scale; }
        return shifted;
    }

    private static void assertComposite(float[] raw, BoardAtmosphere.Lighting light, double gain, double night,
          double selected, float[] glow, String message) {
        long[] expected = expectedComposite(raw, light, gain, night, selected, glow);
        var pixel = BufferUtils.newByteBuffer(4);
        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixel);
        for (int c = 0; c < 3; c++) {
            assertEquals(expected[c], Byte.toUnsignedInt(pixel.get(c)), 1, message + "; RGB channel=" + c);
        }
        assertEquals(255, Byte.toUnsignedInt(pixel.get(3)), "Ordinary board output remains opaque");
    }

    private static double exposedDisplay(double radiance, double gain) {
        double value = radiance * gain;
        return display(Math.min(value, .6) + .4 * (1 - Math.exp(-Math.max(value - .6, 0) / .4)));
    }

    // Independent reference transfer functions: shader or CPU helper regressions must not update the expectations.
    private static double linear(double value) {
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    private static double display(double value) {
        return value <= .0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1 / 2.4) - .055;
    }

    /** Horizontal dielectric under unit white sky and .2 ground, without the optional environment LUT. */
    private static double dielectricRadiance(double color, boolean isometric) {
        double cosine = isometric ? 1 / Math.sqrt(2) : 1;
        double fresnel = .04 + .96 * Math.pow(1 - cosine, 5);
        double reflectedEnvironment = .2 + .8 * (cosine * .5 + .5);
        return linear(color) * (1 - fresnel) + reflectedEnvironment * fresnel;
    }

    /**
     * The scene clear's authored noon red stops share one scale (the visible palette's normalization and white point),
     * which the published horizon measures; the visible sky is presentation, so the ambient light does not.
     */
    private static double noonRedSkyScale(BoardAtmosphere.Lighting lighting) {
        return lighting.horizon().r / linear(.54);
    }

    private static int radianceFrame(GpuAtmosphere atmosphere, GpuTerrain terrain, BoardScene scene,
          OrthographicCamera camera, Mesh quad, ShaderProgram source, float radiance) {
        atmosphere.updateLight(camera);
        atmosphere.configureClouds(terrain, scene);
        atmosphere.begin(WIDTH, HEIGHT, 0);
        GpuAtmosphere.screenState();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        source.bind();
        source.setUniformf("u_radiance", radiance);
        quad.render(source, GL20.GL_TRIANGLES);
        assertEquals(radiance, centerRadiance(), .002f,
              "The floating-point scene target must retain linear values above one until compositing");
        atmosphere.end(camera, terrain, scene, 0);
        return centerRed();
    }

    private static float centerRadiance() {
        return centerValue() / GpuSceneRadiance.SCALE;
    }

    private static float centerValue() {
        var pixel = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
        return pixel.get(0);
    }

    private static int centerRed() {
        Pixmap image = Pixmap.createFromFrameBuffer(WIDTH / 2, HEIGHT / 2, 1, 1);
        try { return image.getPixel(0, 0) >>> 24; }
        finally { image.dispose(); }
    }

    private static OrthographicCamera camera(boolean isometric) {
        var camera = new OrthographicCamera(WIDTH, HEIGHT);
        camera.near = 1;
        camera.far = 500;
        camera.position.set(isometric ? 120 : 0, isometric ? -160 : 0, 200);
        camera.up.set(0, isometric ? 0 : 1, isometric ? 1 : 0);
        camera.lookAt(0, 0, 0);
        camera.update();
        return camera;
    }

    private static BoardScene scene(boolean molten) {
        var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS,
              List.of(), List.of(),
              molten ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE);
        return new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
    }

    private static void nativeCheck(Runnable check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(WIDTH, HEIGHT, -1, -1);
        config.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check.run(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Lighting consistency", failure.get()); }
    }
}
