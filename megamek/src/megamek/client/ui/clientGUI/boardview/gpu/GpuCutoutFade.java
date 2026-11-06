/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;

/**
 * The faded texels of cutouts, blended whatever order they draw in (weighted blended order-independent transparency,
 * McGuire and Bavoil 2013; cutout-fade.glsl), for every view that draws a cutout. They add up in targets of their own,
 * tested against the scene's depth, which they share; a full-view step then lays the result over the scene. How much a
 * pixel covers is exact, the layers multiplying as in life; its colour is an average in which nearer layers count more.
 * Everything adds, so it needs no per-target blending and works from GL 3.3.
 */
final class GpuCutoutFade implements Disposable {
    private final IntBuffer query = BufferUtils.newIntBuffer(16);
    private final Vector3 corner = new Vector3();
    private final Mesh quad = GpuAtmosphere.screenQuad();
    private final GpuWaterDepth glassDepth = new GpuWaterDepth();
    private ShaderProgram program;
    private int framebuffer, attached, attachedType;
    private Texture colour, opticalDepth;

    GpuCutoutFade() {
        program = GpuShaderManager.program(() -> GpuAtmosphere.shader("cutout-fade.frag"), next -> program = next);
    }

    /** Whether this cutout's faded texels blend after its solid ones (GpuModelMaterial#fade). */
    static boolean fades(Material material) {
        var surface = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
        return surface != null && surface.fade == 1;
    }

    /**
     * A cutout's faded texels: they add up, write no depth, and a texel its solid draw kept fails the depth test. The
     * programs are the solid draw's: only values differ, so nothing more links.
     */
    static Material fadeMaterial(Material material) {
        Material result = new Material(material);
        result.set(material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE).fadePass(),
              new BlendingAttribute(true, GL20.GL_ONE, GL20.GL_ONE, 1), new DepthTestAttribute(GL20.GL_LESS, false),
              new FloatAttribute(FloatAttribute.AlphaTest, .02f));
        return result;
    }

    /**
     * Faded texels and glass in their true order, pixel by pixel: {@code fades} draws those behind the nearest glass, the
     * glass goes over them, and {@code fades} draws those in front of it. The nearest glass's depth has a target of its own,
     * as the water's has, so the scene's depth keeps what lies beneath; only the frames that show glass pay for this.
     */
    void renderWithGlass(ModelBatch batch, Camera camera, Environment environment, List<ModelInstance> panes, Runnable fades) {
        if (panes.isEmpty()) {
            fades.run();
            return;
        }
        int scene = glassDepth.begin();
        depthWrites(panes, true);
        try {
            draw(batch, camera, environment, panes);
        } finally {
            depthWrites(panes, false);
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene);
        }
        GpuModelMaterial.fadeGlass = glassDepth.texture();
        try {
            GpuModelMaterial.fadeSide = -1;
            fades.run();
            draw(batch, camera, environment, panes);
            GpuModelMaterial.fadeSide = 1;
            fades.run();
        } finally {
            GpuModelMaterial.fadeGlass = null;
            GpuModelMaterial.fadeSide = 0;
        }
    }

    private static void draw(ModelBatch batch, Camera camera, Environment environment, List<ModelInstance> panes) {
        batch.begin(camera);
        for (ModelInstance pane : panes) { batch.render(pane, environment); }
        batch.end();
    }

    private static void depthWrites(List<ModelInstance> panes, boolean writes) {
        for (ModelInstance pane : panes) {
            for (Material material : pane.materials) { material.get(DepthTestAttribute.class, DepthTestAttribute.Type).depthMask = writes; }
        }
    }

    /** The faded texels of objects a view draws one by one, within these bounds, over its finished scene. */
    void render(ModelBatch batch, Camera camera, Environment environment, List<ModelInstance> instances, BoundingBox bounds) {
        boolean any = false;
        batch.begin(camera);
        for (Renderable part : GpuTerrainDepth.snapshot(instances)) {
            if (!fades(part.material)) { continue; }
            part.material = fadeMaterial(part.material);
            part.shader = null;
            part.environment = environment;
            batch.render(part);
            any = true;
        }
        if (any) { flush(batch, camera, bounds); }
        else { batch.end(); }
    }

    /**
     * Ends the batch, whose gathered parts are faded texels within these bounds, and lays them over the bound scene. The
     * window's depth cannot be shared, so nothing shows when the window is bound.
     */
    void flush(ModelBatch batch, Camera camera, BoundingBox bounds) {
        // Weight falls with depth over what is in view: nearer layers lead, within what a half float can hold.
        float near = 1, far = 0;
        for (int index = 0; index < 8; index++) {
            corner.set((index & 1) == 0 ? bounds.min.x : bounds.max.x, (index & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (index & 4) == 0 ? bounds.min.z : bounds.max.z).prj(camera.combined);
            float depth = corner.z * .5f + .5f;
            near = Math.min(near, depth);
            far = Math.max(far, depth);
        }
        GpuModelMaterial.fadeDepth[0] = near;
        GpuModelMaterial.fadeDepth[1] = Math.min(.2f * (camera.far - camera.near) / BoardRelief.metres(1), 20 / Math.max(far - near, 1e-6f));
        int scene = begin();
        if (scene < 0) { Gdx.gl.glColorMask(false, false, false, false); }
        try {
            batch.end();
        } finally {
            if (scene < 0) { Gdx.gl.glColorMask(true, true, true, true); }
            else { end(scene); }
        }
    }

    /**
     * Binds the sums cleared, for the additive draws, and returns the framebuffer that was bound, for {@link #end}; -1,
     * with nothing bound, when that has no depth to share.
     */
    int begin() {
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query);
        int scene = query.get(0);
        if (scene == 0) { return -1; }
        query.clear();
        Gdx.gl.glGetFramebufferAttachmentParameteriv(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT,
              GL20.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, query);
        int type = query.get(0);
        if (type != GL20.GL_TEXTURE && type != GL20.GL_RENDERBUFFER) { return -1; }
        query.clear();
        Gdx.gl.glGetFramebufferAttachmentParameteriv(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT,
              GL20.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, query);
        int depth = query.get(0);
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
        int width = query.get(0) + query.get(2), height = query.get(1) + query.get(3);
        // The largest scene seen: a view that also draws a small one (the editor's side view) keeps its targets.
        if (colour == null || colour.getWidth() < width || colour.getHeight() < height) {
            create(colour == null ? width : Math.max(width, colour.getWidth()), colour == null ? height : Math.max(height, colour.getHeight()));
        }
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        if (attached != depth || attachedType != type) {
            if (type == GL20.GL_TEXTURE) {
                Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D, depth, 0);
            } else {
                Gdx.gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, depth);
            }
            attached = depth;
            attachedType = type;
            if (Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER) != GL20.GL_FRAMEBUFFER_COMPLETE) {
                Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene);
                throw new IllegalStateException("GPU cutout fade target is incomplete");
            }
        }
        ScreenUtils.clear(0, 0, 0, 0, false);
        return scene;
    }

    /** Lays the sums over the scene, which is bound again. */
    void end(int scene) {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene);
        GpuAtmosphere.screenState();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        program.bind();
        opticalDepth.bind(1);
        program.setUniformi("u_fadeOpticalDepth", 1);
        colour.bind(0);
        program.setUniformi("u_fadeColour", 0);
        quad.render(program, GL20.GL_TRIANGLES);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void create(int width, int height) {
        disposeTargets();
        colour = target(width, height, GL30.GL_RGBA16F, GL20.GL_RGBA);
        opticalDepth = target(width, height, GL30.GL_R16F, GL30.GL_RED);
        framebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
              colour.getTextureObjectHandle(), 0);
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL20.GL_TEXTURE_2D,
              opticalDepth.getTextureObjectHandle(), 0);
        IntBuffer buffers = BufferUtils.newIntBuffer(2);
        buffers.put(GL20.GL_COLOR_ATTACHMENT0).put(GL30.GL_COLOR_ATTACHMENT1).flip();
        Gdx.gl30.glDrawBuffers(2, buffers);
    }

    private static Texture target(int width, int height, int format, int channels) {
        Texture texture = new Texture(new GLOnlyTextureData(width, height, 0, format, channels, GL20.GL_FLOAT));
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return texture;
    }

    private void disposeTargets() {
        if (framebuffer != 0) { Gdx.gl.glDeleteFramebuffer(framebuffer); }
        framebuffer = 0;
        attached = 0;
        attachedType = 0;
        if (colour != null) { colour.dispose(); }
        colour = null;
        if (opticalDepth != null) { opticalDepth.dispose(); }
        opticalDepth = null;
    }

    @Override
    public void dispose() {
        disposeTargets();
        glassDepth.dispose();
        quad.dispose();
        program.dispose();
    }
}
