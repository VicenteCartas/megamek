/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * Ground-truth ambient occlusion of the opaque scene (ambient-occlusion-horizon.frag), which the lit surfaces apply to
 * their ambient term (ambient-occlusion.glsl). It runs between the depth pre-pass and the opaque colour draws, in the
 * colour stage of {@link GpuTerrain#render(Camera, Runnable, Runnable)}: half-resolution horizons from the pre-pass
 * depth with contact shadows toward the sun (CONTACT_METRES), then two depth-aware blur passes. While the terrain's
 * lighting carries it (GpuEnvironmentLighting.ambientOcclusion) the pre-pass also draws the relief, so the occlusion
 * sees the ground, the trees and the building shells the pre-pass draws (GpuTerrain.twin); other props, and units,
 * neither cast nor receive it.
 * Its presence compiles ambientOcclusionFlag into the lit programs (GpuCloudShadow.prefix), so a switch is a lighting
 * change behind the terrain's loading stage, as specular anti-aliasing is. The occlusion is current from {@link #render}
 * to {@link #end}; other draws read visibility 1. Its targets follow the scene depth's size, so the window and the
 * render resolution. Owns its targets, programs and screen quad; one instance per view.
 */
final class GpuAmbientOcclusion implements Disposable {
    /**
     * The world radius in metres. The board's sky visibility (GpuSkyVisibility) already hides the sky behind relief a
     * cell or more away, the feet of cliffs and canyon floors, and both darken the same sky light: this keeps to the
     * occluders nearer than its cells (a trunk's foot, a rock, a bank's crease); its cells grow past this on boards over
     * about 1 km across.
     */
    static final float RADIUS_METRES = GpuSkyVisibility.CELL_METRES;
    /** The largest screen radius in scene pixels: a close view searches a smaller world radius instead of more pixels. */
    static final float MAX_RADIUS_PIXELS = 160;
    /** Contact shadows march this far toward the sun, and take an occluder this deep behind its surface (metres). */
    static final float CONTACT_METRES = 2;
    static final float CONTACT_THICKNESS_METRES = 1;
    private final Vector3 toSun = new Vector3();
    private final IntBuffer query = BufferUtils.newIntBuffer(16);
    private Mesh quad;
    private ShaderProgram horizon;
    private ShaderProgram blur;
    /** Visibility 1 at camera distance 0, which no fragment has, and no contact shadow: what the programs read without one. */
    private Texture fallback;
    /** The half-resolution horizons, where the second blur pass leaves the final occlusion, and the first's output. */
    private FrameBuffer horizons;
    private FrameBuffer blurred;
    private boolean current;
    /** What the colour programs read with the occlusion: the projection's depth terms and a scene pixel's world size. */
    private final float[] projectionDepth = new float[4];
    private float pixelBase;
    private float pixelSlope;
    /**
     * Subtracted from the camera distance the targets hold: the orbit pivot's depth in an orthographic view, whose camera
     * stands thousands of units back, so the half floats keep the visible depths finely enough; 0 in perspective, where
     * the depth tolerance grows with the distance.
     */
    private float rangeOrigin;
    private int revision;

    /**
     * Gives the environment's lighting this occlusion while {@code enabled}, else takes it away with its targets;
     * called before the frame's terrain is submitted, as material readiness follows the environment.
     */
    void configure(Environment environment, boolean enabled) {
        var lighting = GpuEnvironmentLighting.required(environment);
        if (!enabled) {
            lighting.ambientOcclusion = null;
            disposeTargets();
            return;
        }
        if (horizon == null) { createPrograms(); }
        lighting.ambientOcclusion = this;
    }

    /**
     * This frame's occlusion from the scene depth {@code depth}, which the depth pre-pass has just drawn. It runs inside
     * the terrain's opaque batch, through the render context that tracks the bound textures and the state it changes,
     * and restores the scene target and viewport. Nothing unless the terrain's lighting carries it, or without a
     * pre-pass (the Classic columns).
     */
    void render(Camera camera, GpuTerrain terrain, Texture depth) {
        current = false;
        if (GpuEnvironmentLighting.ambientOcclusion(terrain.environment()) != this || !terrain.prepassed()) { return; }
        RenderContext context = terrain.renderContext();
        // Before the targets: building one binds the window's framebuffer, and the colour pass after this one would then
        // draw there (Intel also compiles each colour program again for it).
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query);
        int previous = query.get(0);
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
        int viewportX = query.get(0), viewportY = query.get(1), viewportWidth = query.get(2), viewportHeight = query.get(3);
        int width = (depth.getWidth() + 1) / 2, height = (depth.getHeight() + 1) / 2;
        if (horizons == null || horizons.getWidth() != width || horizons.getHeight() != height) {
            disposeTargets();
            horizons = createTarget(width, height);
            blurred = createTarget(width, height);
        }
        context.setDepthTest(0);
        context.setDepthMask(false);
        context.setBlending(false, GL20.GL_ONE, GL20.GL_ZERO);
        context.setCullFace(0);
        float[] projection = camera.projection.val;
        projectionDepth[0] = projection[Matrix4.M22];
        projectionDepth[1] = projection[Matrix4.M23];
        projectionDepth[2] = projection[Matrix4.M32];
        projectionDepth[3] = projection[Matrix4.M33];
        // A scene pixel's world size at camera distance d: clip w (d in perspective, 1 orthographic) over the pixel scale.
        float pixelScale = projection[Matrix4.M11] * depth.getHeight() / 2;
        pixelBase = projection[Matrix4.M33] / pixelScale;
        pixelSlope = -projection[Matrix4.M32] / pixelScale;
        rangeOrigin = camera instanceof BoardProjectionCamera board && !board.perspective ? board.pivotDepth : 0;
        horizons.bind();
        Gdx.gl.glViewport(0, 0, width, height);
        horizon.bind();
        horizon.setUniformi("u_depth", context.textureBinder.bind(depth));
        horizon.setUniformf("u_projectionDepth", projectionDepth[0], projectionDepth[1], projectionDepth[2],
              projectionDepth[3]);
        horizon.setUniformf("u_projectionScale", projection[Matrix4.M00], projection[Matrix4.M11]);
        horizon.setUniformf("u_radius", BoardRelief.metres(RADIUS_METRES), MAX_RADIUS_PIXELS);
        // The direction to the sun or moon in view space; no march without a direct light.
        var lights = terrain.environment().get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
        var light = lights == null || lights.lights.isEmpty() ? null : lights.lights.first();
        boolean lit = light != null && (light.color.r > 0 || light.color.g > 0 || light.color.b > 0);
        if (lit) { toSun.set(light.direction).scl(-1).rot(camera.view).nor(); }
        horizon.setUniformf("u_contact", toSun.x, toSun.y, toSun.z, lit ? BoardRelief.metres(CONTACT_METRES) : 0);
        horizon.setUniformf("u_contactThickness", BoardRelief.metres(CONTACT_THICKNESS_METRES));
        horizon.setUniformf("u_rangeOrigin", rangeOrigin);
        quad.render(horizon, GL20.GL_TRIANGLES);
        blur(blurred, horizons, 1, 0, context);
        blur(horizons, blurred, 0, 1, context);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previous);
        Gdx.gl.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
        // Leave the texture units as a batch starts them: the colour draws rebind what they sample, and a texture created
        // or uploaded later binds on unit 0, outside the binder, never over a unit a draw samples. Without this the
        // forest's colour draws read a wrong texture and hung the GPU (a driver reset on NVIDIA).
        context.textureBinder.begin();
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        revision++;
        current = true;
    }

    private void blur(FrameBuffer target, FrameBuffer source, int x, int y, RenderContext context) {
        target.bind();
        blur.bind();
        blur.setUniformi("u_occlusion", context.textureBinder.bind(source.getColorBufferTexture()));
        blur.setUniformi("u_direction", x, y);
        blur.setUniformf("u_blur", pixelBase, pixelSlope);
        quad.render(blur, GL20.GL_TRIANGLES);
    }

    /** The draws that read this frame's occlusion are done: the opaque scene and the units. Later draws read 1. */
    void end() { current = false; }

    /**
     * Native checks: the target holding the last occlusion (visibility, camera distance less {@link #rangeOrigin}, contact
     * visibility), or null.
     */
    FrameBuffer target() { return horizons; }

    float rangeOrigin() { return rangeOrigin; }

    private Texture texture() { return current ? horizons.getColorBufferTexture() : fallback; }

    /**
     * The define that compiles the occlusion into a lit program (ambient-occlusion.glsl) while its environment's
     * lighting carries it. Sculpted ground with volcanic crust beside the board's sky visibility (GpuSkyVisibility) or
     * ice draws without it and keeps the board-wide sky occlusion, which a crust patch in deep shade would visibly lack.
     */
    static String prefix(Renderable renderable) {
        return GpuEnvironmentLighting.ambientOcclusion(renderable.environment) != null
              && !(renderable.material.has(GpuMagmaShader.TYPE)
                    && (GpuSkyVisibility.STRENGTH > 0 || GpuIceShader.mode(renderable.material) != 0))
              ? "#define ambientOcclusionFlag\n" : "";
    }

    /** The colour programs' inputs (ambient-occlusion.glsl); a program without ambientOcclusionFlag has none of them. */
    static void register(DefaultShader shader) {
        shader.register("u_ambientOcclusion", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = GpuEnvironmentLighting.ambientOcclusion(attributes);
                // Touched every draw: material textures can evict it from the binder's slots.
                if (occlusion != null) { target.set(id, occlusion.texture()); }
            }
        });
        shader.register("u_projectionDepth", new BaseShader.LocalSetter() {
            private GpuAmbientOcclusion uploaded;
            private int uploadedRevision = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = GpuEnvironmentLighting.ambientOcclusion(attributes);
                if (occlusion == null || occlusion == uploaded && occlusion.revision == uploadedRevision) { return; }
                uploaded = occlusion;
                uploadedRevision = occlusion.revision;
                float[] depth = occlusion.projectionDepth;
                target.set(id, depth[0], depth[1], depth[2], depth[3]);
            }
        });
        shader.register("u_ambientOcclusionPixel", new BaseShader.LocalSetter() {
            private GpuAmbientOcclusion uploaded;
            private int uploadedRevision = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = GpuEnvironmentLighting.ambientOcclusion(attributes);
                if (occlusion == null || occlusion == uploaded && occlusion.revision == uploadedRevision) { return; }
                uploaded = occlusion;
                uploadedRevision = occlusion.revision;
                target.set(id, occlusion.pixelBase, occlusion.pixelSlope, occlusion.rangeOrigin);
            }
        });
    }

    /** Built with the setting's first switch on, which shows the terrain's loading stage. */
    private void createPrograms() {
        try {
            quad = GpuAtmosphere.screenQuad();
            fallback = new Texture(new GLOnlyTextureData(1, 1, 0, GL30.GL_RGBA16F, GL20.GL_RGBA, GL20.GL_FLOAT));
            fallback.bind();
            var pixel = BufferUtils.newFloatBuffer(4);
            pixel.put(1).put(0).put(1).put(0).flip();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            horizon = GpuShaderManager.program(() -> GpuAtmosphere.shader("ambient-occlusion-horizon.frag"),
                  next -> horizon = next);
            blur = GpuShaderManager.program(() -> GpuAtmosphere.shader("ambient-occlusion-blur.frag"), next -> blur = next);
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    private static FrameBuffer createTarget(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addFloatAttachment(GL30.GL_RGBA16F, GL20.GL_RGBA, GL20.GL_FLOAT, true);
        FrameBuffer result = builder.build();
        result.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        result.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    private void disposeTargets() {
        current = false;
        if (horizons != null) { horizons.dispose(); horizons = null; }
        if (blurred != null) { blurred.dispose(); blurred = null; }
    }

    @Override
    public void dispose() {
        disposeTargets();
        if (fallback != null) { fallback.dispose(); fallback = null; }
        if (quad != null) { quad.dispose(); quad = null; }
        GpuShaderManager.dispose(horizon);
        GpuShaderManager.dispose(blur);
        horizon = null;
        blur = null;
    }
}
