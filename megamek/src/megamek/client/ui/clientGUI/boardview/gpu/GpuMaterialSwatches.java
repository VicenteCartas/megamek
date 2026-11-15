/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;

/**
 * The material palette's swatches (GpuMaterialPalette): a board material on a flat tile, a cube or a sphere, with its
 * whole finish as a model draws it (GpuAssets.finish), lit by the default daylight, as the shader preview's stage lights
 * its sphere (GpuShaderPreview). Render thread; owns its assets, so it reads the materials as they are on disk.
 */
final class GpuMaterialSwatches implements Disposable {
    static final int SIZE = 160;

    enum Shape {
        FLAT("Flat"), CUBE("Cube"), SPHERE("Sphere");

        private final String title;
        Shape(String title) { this.title = title; }
        @Override
        public String toString() { return title; }
    }

    private final GpuAssets assets = new GpuAssets();
    private final BoardAtmosphere.Lighting lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();
    private final Environment light = new Environment();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FrameBuffer scene = GpuHeatGlow.buffer(SIZE, SIZE, true);
    private final FrameBuffer displayed = GpuAtmosphere.buffer(SIZE, SIZE, true);
    private final GpuSceneDisplay display = new GpuSceneDisplay();
    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Material palette", GpuUnitShader::provider));
    private final Map<Shape, Model> shapes = new EnumMap<>(Shape.class);

    GpuMaterialSwatches() {
        environmentLighting.set(lighting);
        light.set(environmentLighting);
        light.set(ColorAttribute.createAmbientLight(lighting.ambient()));
        light.add(new DirectionalLight().set(lighting.direct(), lighting.direction()));
        var builder = new ModelBuilder();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates;
        shapes.put(Shape.FLAT, builder.createRect(-20, -20, 0, 20, -20, 0, 20, 20, 0, -20, 20, 0, 0, 0, 1,
              new Material(), attributes));
        shapes.put(Shape.CUBE, builder.createBox(24, 24, 24, new Material(), attributes));
        shapes.put(Shape.SPHERE, builder.createSphere(32, 32, 32, 32, 24, new Material(), attributes));
    }

    /**
     * {@code name}'s swatch on {@code shape}. Between frames: it leaves the default framebuffer bound with the back
     * buffer's viewport, as the frame starts.
     */
    BufferedImage render(String name, Shape shape) {
        var instance = new ModelInstance(shapes.get(shape));
        Material material = instance.materials.first();
        material.clear();
        material.set(assets.finish(name, new Material(TextureAttribute.createDiffuse(assets.material(name)))));
        // A cutout (its resource's transparency) at the models' usual cutoff; libGDX's shaders apply an alpha test only
        // with a blending attribute, here disabled, as the board's cutouts have it (GpuTerrain.opaqueMaterials).
        if (assets.definition(name).integer("transparency", 0) != 0) {
            material.set(FloatAttribute.createAlphaTest(.5f), new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1));
        }
        // The flat tile from above; the cube and the sphere from the side, a little above.
        float pitch = shape == Shape.FLAT ? 89 : 28, yaw = -55;
        camera.viewportWidth = camera.viewportHeight = shape == Shape.FLAT ? 41 : 44;
        camera.near = 1;
        camera.far = 1000;
        camera.up.set(Vector3.Z);
        camera.position.set(MathUtils.cosDeg(yaw) * MathUtils.cosDeg(pitch), MathUtils.sinDeg(yaw) * MathUtils.cosDeg(pitch),
              MathUtils.sinDeg(pitch)).scl(400);
        camera.lookAt(Vector3.Zero);
        camera.update();
        float exposure = lighting.adaptation(), backdrop = GpuSceneRadiance.SCALE / exposure;
        scene.bind();
        Gdx.gl.glViewport(0, 0, SIZE, SIZE);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(GpuColor.linear(.055f) * backdrop, GpuColor.linear(.065f) * backdrop,
              GpuColor.linear(.085f) * backdrop, 1, true);
        // A catalogue of materials, whatever board is open: the base set's programs (High's).
        GpuEnvironmentLighting.applySwitches(light, BoardSwitches.BASE);
        try {
            batch.begin(camera);
            try { batch.render(instance, light); } finally { batch.end(); }
            display.render(scene, displayed, exposure);
            return GpuShaderPreview.image(SIZE, SIZE);
        } finally {
            FrameBuffer.unbind();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        }
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.values().forEach(Model::dispose);
        display.dispose();
        displayed.dispose();
        scene.dispose();
        environmentLighting.dispose();
        assets.dispose();
    }
}
