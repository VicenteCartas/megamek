/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.ShadowMap;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pixels at the shared standard-model shader boundary, including orthographic viewing and linear maps. */
@Tag("on-demand")
class GpuModelMaterialSmokeTest {
    private static final int SIZE = 256;

    @Test
    void nightEmissionSwitchesBothGltfAndLibraryGlowWithoutChangingTheSurface() {
        nativeCheck(output -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(Color.BLACK), new GpuLinearColors(),
                        ColorAttribute.createEmissive(.2f, .1f, .02f, 1), new GpuModelLight(1, 0, 1, true),
                        new GpuModelMaterial(1, 1, 0, null, new float[] { 1, 1, 1 },
                              new float[] { .1f, .1f, .1f }, false, new float[4])),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var batch = new ModelBatch(GpuUnitShader.provider());
            var environment = new Environment();
            var lighting = new GpuEnvironmentLighting();
            environment.set(lighting, ColorAttribute.createAmbientLight(0, 0, 0, 1));
            int pixel = (SIZE / 2 * SIZE + SIZE / 2) * 4;
            try {
                for (boolean iso : new boolean[] { false, true }) {
                    var camera = camera(iso);
                    for (int hour : new int[] { 12, 0, 12 }) {
                        lighting.set(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0, 2.5f, 0, 0)));
                        draw(output, batch, object, camera, environment);
                        float[] actual = output.linearPixels();
                        float[] expected = hour == 0 ? new float[] { .3f, .2f, .12f } : new float[3];
                        for (int c = 0; c < 3; c++) {
                            assertEquals(expected[c], actual[pixel + c], .002,
                                  "All emission follows day/night in the native model shader; hour=" + hour);
                        }
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                batch.dispose(); model.dispose(); lighting.dispose();
            }
        });
    }

    @Test
    void buildingPaintRespectsAtlasMasksInLitAndUnlitRendering() {
        nativeCheck(output -> {
            Texture fixed = texture(0xbfbfbf00), mixed = texture(0xbfbfbf80), wall = texture(0xbfbfbfff);
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(TextureAttribute.createDiffuse(fixed), new GpuBuildingPaint(Color.WHITE),
                        new GpuModelMaterial(.8f, 0, 0, null, new float[] { 1, 1, 1 }, new float[3], false,
                              new float[4], true)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var material = object.materials.first();
            var batch = new ModelBatch(GpuUnitShader.provider());
            var lit = new Environment();
            lit.set(ColorAttribute.createAmbientLight(.3f, .3f, .3f, 1));
            lit.add(new DirectionalLight().set(.5f, .5f, .5f, 0, 0, -1));
            try {
                for (Environment environment : new Environment[] { lit, null }) {
                    var camera = camera(false);
                    for (Texture map : new Texture[] { fixed, mixed, wall }) {
                        material.set(TextureAttribute.createDiffuse(map), new GpuBuildingPaint(Color.WHITE));
                        int neutral = draw(output, batch, object, camera, environment);
                        assertTrue(red(neutral) > 40, "Zero mask alpha must not make windows transparent");
                        material.set(new GpuBuildingPaint(new Color(.08f, .8f, .15f, 1)));
                        int tinted = draw(output, batch, object, camera, environment);
                        if (map == fixed) {
                            assertEquals(neutral, tinted, "Window/metal atlas pixels retain their original finish");
                        } else {
                            assertTrue(red(neutral) - red(tinted) > (map == wall ? 25 : 5), "Wall pixels receive the paint");
                            assertTrue(green(tinted) > red(tinted), "Paint hue survives lighting");
                        }
                        material.remove(GpuBuildingPaint.TYPE);
                        assertEquals(neutral, draw(output, batch, object, camera, environment), "Unpainted material uses white identity");
                    }
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                fixed.dispose(); mixed.dispose(); wall.dispose(); batch.dispose(); model.dispose();
            }
        });
    }

    @Test
    void boardCameraProjectionDeterminesWhetherViewRaysAreParallel() {
        nativeCheck(output -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(.2f, .2f, .2f, 1), new GpuModelMaterial(.28f, 0, null)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var batch = new ModelBatch(GpuUnitShader.provider());
            var camera = new BoardProjectionCamera();
            camera.viewportWidth = SIZE;
            camera.viewportHeight = SIZE;
            camera.near = 1;
            camera.far = 500;
            camera.position.set(0, 0, 200);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
            environment.add(new DirectionalLight().set(.2f, .2f, .2f, -.4f, 0, -1));
            try {
                for (boolean perspective : new boolean[] { false, true }) {
                    camera.perspective = perspective;
                    camera.update();
                    draw(output, batch, object, camera, environment);
                    Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
                    try {
                        int left = red(pixels.getPixel(SIZE / 2 - 45, SIZE / 2));
                        int right = red(pixels.getPixel(SIZE / 2 + 45, SIZE / 2));
                        if (perspective) {
                            assertTrue(Math.abs(left - right) > 10,
                                  "Perspective highlight follows the eye ray: " + left + "/" + right);
                        } else {
                            assertEquals(left, right, "Orthographic board rays are parallel across the face");
                        }
                    } finally { pixels.dispose(); }
                }
            } finally {
                batch.dispose();
                model.dispose();
            }
        });
    }

    @Test
    void geometryAndCloudShadowsAttenuateReflectionsWithoutDimmingEmission() {
        nativeCheck(output -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(.2f, .2f, .2f, 1),
                        ColorAttribute.createEmissive(.1f, .1f, .1f, 1), new GpuModelMaterial(.28f, 0, null)),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var batch = new ModelBatch(GpuUnitShader.provider());
            var darkDepth = GpuShadowSmokeTest.constantDepth(0);
            var clearDepth = GpuShadowSmokeTest.constantDepth(1);
            var edgeDepth = GpuShadowSmokeTest.constantDepth(1);
            Texture dark = darkDepth.getColorBufferTexture(), clear = clearDepth.getColorBufferTexture();
            Texture cloud = texture(0x404040ff);
            edgeDepth.begin();
            try {
                Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                Gdx.gl.glScissor(0, 0, 1, 2);
                Gdx.gl.glClearDepthf(0);
                Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            } finally {
                Gdx.gl.glClearDepthf(1);
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                edgeDepth.end();
            }
            try {
                for (boolean iso : new boolean[] { false, true }) {
                    var camera = camera(iso);
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    var sun = new DirectionalLight().set(.2f, .2f, .2f,
                          -camera.direction.x, -camera.direction.y, camera.direction.z);
                    environment.add(sun);
                    var depth = new TextureDescriptor<>(clear);
                    // Near-constant UVs, with real projection axes for the receiver-plane drift calculation.
                    var shadowProjection = new Matrix4().setToScaling(1e-6f, 1e-6f, 1e-6f);
                    environment.shadowMap = new ShadowMap() {
                        @Override public Matrix4 getProjViewTrans() { return shadowProjection; }
                        @Override public TextureDescriptor<Texture> getDepthMap() { return depth; }
                    };
                    int lit = red(draw(output, batch, object, camera, environment));
                    int centerPixel = (SIZE / 2 * SIZE + SIZE / 2) * 4;
                    float litRadiance = output.linearPixels()[centerPixel];
                    depth.texture = dark;
                    int shadowed = red(draw(output, batch, object, camera, environment));
                    float shadowRadiance = output.linearPixels()[centerPixel];
                    depth.texture = clear;
                    sun.color.set(0, 0, 0, 1);
                    draw(output, batch, object, camera, environment);
                    float emissionRadiance = output.linearPixels()[centerPixel];
                    sun.color.set(.2f, .2f, .2f, 1);
                    // The isometric reflection can retain indirect ground light, which direct shadows leave intact.
                    assertTrue(shadowRadiance >= emissionRadiance,
                          "Geometry shadow retains at least the emission-only control's scene radiance");
                    assertEquals(26, shadowed, 1, "Geometry shadow removes diffuse and specular light, leaving emission");
                    // Left depth texels block, right pass. At U=.375 the 1.5-texel tent gives texels -1/0/1
                    // weights .25/1.25/.75; clamping texel -1 to the left edge makes transmission one third.
                    depth.texture = edgeDepth.getColorBufferTexture();
                    shadowProjection.setTranslation(-.25f, 0, 0);
                    draw(output, batch, object, camera, environment);
                    float filteredRadiance = output.linearPixels()[centerPixel];
                    assertEquals(shadowRadiance + (litRadiance - shadowRadiance) / 3, filteredRadiance,
                          .002f * Math.max(litRadiance, .01f),
                          "The real model provider filters hardware comparisons and leaves emission intact");
                    shadowProjection.setTranslation(0, 0, 0);
                    depth.texture = clear;
                    var projection = new Matrix4().setToScaling(0, 0, 0).setTranslation(.5f, .5f, 0);
                    // Clouds reach a draw through its environment's lighting; this one's sky is black.
                    var lighting = new GpuEnvironmentLighting();
                    lighting.cloudShadow = new GpuCloudShadow(cloud, projection);
                    environment.set(lighting);
                    int cloudy = red(draw(output, batch, object, camera, environment));
                    lighting.dispose();
                    assertTrue(cloudy > shadowed + 15 && cloudy < lit - 20,
                          "Cloud transmission dims the direct highlight: " + lit + "/" + cloudy + "/" + shadowed);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                cloud.dispose();
                edgeDepth.dispose();
                clearDepth.dispose();
                darkDepth.dispose();
                batch.dispose();
                model.dispose();
            }
        });
    }

    @Test
    void authoredSurfacesAndMapsControlLightingInTopAndIsometricViews() {
        nativeCheck(output -> {
            var model = new ModelBuilder().createBox(120, 120, 4,
                  new Material(ColorAttribute.createDiffuse(new Color(.2f, .2f, .2f, 1))),
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates);
            var object = new ModelInstance(model);
            var material = object.materials.first();
            var batch = new ModelBatch(GpuUnitShader.provider());
            // A 45-degree mapped normal stays in the view hemisphere in both mirrored orientations,
            // including the 31-degree isometric view; handedness must not test back-facing BRDF rejection.
            Texture packed = texture(0x188000ff), occlusion = texture(0x40ffffff), normal = texture(0xda80daff);
            try {
                for (boolean iso : new boolean[] { false, true }) {
                    var camera = camera(iso);
                    var sun = new DirectionalLight().set(.2f, .2f, .2f,
                          -camera.direction.x, -camera.direction.y, camera.direction.z);
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    environment.add(sun);
                    material.set(new GpuModelMaterial(.28f, 0, null));
                    int smooth = red(draw(output, batch, object, camera, environment));
                    material.set(new GpuModelMaterial(.9f, 0, null));
                    int rough = red(draw(output, batch, object, camera, environment));
                    assertTrue(smooth > rough + 30, "Roughness must broaden the sun highlight: " + smooth + "/" + rough);

                    material.set(new GpuModelMaterial(.5f, 1, packed));
                    int mapped = draw(output, batch, object, camera, environment);
                    material.set(new GpuModelMaterial(.5f * 128 / 255, 0, null));
                    assertEquals(mapped, draw(output, batch, object, camera, environment),
                          "Packed G/B are linear multipliers; R must not change surface response");

                    material.set(ColorAttribute.createDiffuse(new Color(.8f, .25f, .08f, 1)),
                          new GpuModelMaterial(.45f, 0, null));
                    int paint = draw(output, batch, object, camera, environment);
                    material.set(new GpuModelMaterial(.45f, 1, null));
                    int metal = draw(output, batch, object, camera, environment);
                    assertTrue(red(metal) > green(metal) * 3, "Metal reflection must inherit the base color");
                    assertTrue((paint >>> 8 & 255) > (metal >>> 8 & 255) + 15,
                          "Dielectric highlights add neutral light even to the dark blue channel: " + paint + "/" + metal);

                    material.set(ColorAttribute.createDiffuse(new Color(.5f, .5f, .5f, 1)),
                          new GpuModelMaterial(.8f, 0, null));
                    // Ambient occlusion attenuates indirect sky/ground, without painting dark spots over direct sunlight.
                    sun.set(0, 0, 0, 0, 0, -1);
                    environment.set(ColorAttribute.createAmbientLight(.6f, .6f, .6f, 1));
                    int ambient = red(draw(output, batch, object, camera, environment));
                    material.set(TextureAttribute.createAmbient(occlusion));
                    int occluded = red(draw(output, batch, object, camera, environment));
                    assertTrue(ambient > occluded + 25, "AO must attenuate ambient light");
                    environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1));
                    sun.set(.5f, .5f, .5f, 0, 0, -1);
                    int directWithAo = draw(output, batch, object, camera, environment);
                    material.remove(TextureAttribute.Ambient);
                    assertEquals(directWithAo, draw(output, batch, object, camera, environment), "AO must leave direct light intact");
                    int flat = red(directWithAo);
                    material.set(TextureAttribute.createNormal(normal));
                    int bumped = red(draw(output, batch, object, camera, environment));
                    assertTrue(flat > bumped + 10, "The original-UV normal map must change light incidence: " + flat + "/" + bumped);
                    sun.set(.5f, .5f, .5f, -1, -1, -.4f);
                    int originalUv = red(draw(output, batch, object, camera, environment));
                    mirrorUv(model);
                    int mirroredUv = red(draw(output, batch, object, camera, environment));
                    assertTrue(Math.abs(originalUv - mirroredUv) > 25,
                          "Mirroring mesh U must reverse the mapped tangent direction: " + originalUv + "/" + mirroredUv);
                    mirrorUv(model);
                    material.remove(TextureAttribute.Normal);

                    // Translating a surface parallel to the image must not move its highlight in an orthographic view.
                    material.set(new GpuModelMaterial(.28f, 0, null));
                    sun.set(.2f, .2f, .2f, -camera.direction.x, -camera.direction.y, camera.direction.z);
                    int center = draw(output, batch, object, camera, environment);
                    object.transform.setToTranslation(25, 0, 0);
                    assertEquals(center, draw(output, batch, object, camera, environment), "Orthographic rays remain parallel");
                    object.transform.idt();
                    System.out.printf("Model surfaces iso=%s: smooth=%d rough=%d ambient=%d AO=%d normal=%d/%d%n",
                          iso, smooth, rough, ambient, occluded, flat, bumped);
                }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                normal.dispose();
                occlusion.dispose();
                packed.dispose();
                batch.dispose();
                model.dispose();
            }
        });
    }

    private static int draw(GpuSceneTestOutput output, ModelBatch batch, ModelInstance object, Camera camera,
          Environment environment) {
        output.scene().begin();
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(0, 0, 0, 1, true);
        batch.begin(camera);
        batch.render(object, environment);
        batch.end();
        output.display();
        Pixmap pixels = Pixmap.createFromFrameBuffer(SIZE / 2, SIZE / 2, 1, 1);
        try { return pixels.getPixel(0, 0); }
        finally { pixels.dispose(); }
    }

    private static int red(int pixel) { return pixel >>> 24; }
    private static int green(int pixel) { return pixel >>> 16 & 255; }

    private static void mirrorUv(com.badlogic.gdx.graphics.g3d.Model model) {
        for (var mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int uv = mesh.getVertexAttribute(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int i = uv; i < vertices.length; i += stride) { vertices[i] = 1 - vertices[i]; }
            mesh.setVertices(vertices);
        }
    }

    private static Texture texture(int color) {
        var pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setColor(color);
        pixels.fill();
        try { return new Texture(pixels); }
        finally { pixels.dispose(); }
    }

    private static OrthographicCamera camera(boolean iso) {
        var camera = new OrthographicCamera(SIZE, SIZE);
        camera.near = 1;
        camera.far = 500;
        camera.position.set(0, iso ? -120 : 0, 200);
        camera.up.set(0, iso ? 0 : 1, iso ? 1 : 0);
        camera.lookAt(0, 0, 0);
        camera.update();
        return camera;
    }

    private static void nativeCheck(Consumer<GpuSceneTestOutput> check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(SIZE, SIZE, -1, -1);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try (var output = new GpuSceneTestOutput(SIZE, SIZE)) { check.accept(output); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Model surface lighting", failure.get()); }
    }
}
