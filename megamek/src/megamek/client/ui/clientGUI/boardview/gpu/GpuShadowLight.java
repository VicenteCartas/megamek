/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;
import org.lwjgl.opengl.GL32;

/** One hardware depth map for terrain, models and fog, through libGDX's existing shadow texture binding. */
final class GpuShadowLight extends DirectionalShadowLight {
    /** The map's coarser levels (levels): a penumbra wider than the finest tent reads one (contactShadow). */
    static final int LEVELS = 3;
    private final ShaderProgram levelProgram;
    private final Mesh levelQuad = GpuAtmosphere.screenQuad();
    private final int levelFramebuffer = Gdx.gl.glGenFramebuffer();

    /** near and far as SlabCamera reads them: the slab's top and bottom heights, or distances along a lower light. */
    GpuShadowLight(int width, int height, float viewportWidth, float viewportHeight, float near, float far) {
        super(width, height, viewportWidth, viewportHeight, near, far);
        cam = new SlabCamera();
        ((SlabCamera) cam).texels = width;
        cam.viewportWidth = viewportWidth;
        cam.viewportHeight = viewportHeight;
        cam.near = near;
        cam.far = far;
        fbo.dispose();
        fbo = levelled(depthBuffer(width, height));
        // The binder applies this descriptor too; setting only Texture's filter would be overwritten by libGDX. Every
        // lookup names its level, so the finest compares exactly as without levels.
        textureDesc.minFilter = Texture.TextureFilter.MipMapLinearNearest;
        textureDesc.magFilter = Texture.TextureFilter.Linear;
        levelProgram = GpuGlsl.compile("GPU shadow levels", "", GpuShaderSource.read("atmosphere.vert"),
              GpuShaderSource.read("shadow-levels.frag"));
    }

    /** A square map of {@code texels} a side in place of the current one, whose depth is lost. */
    void resize(int texels) {
        fbo.dispose();
        fbo = levelled(depthBuffer(texels, texels));
        ((SlabCamera) cam).texels = texels;
    }

    /** The map with room for its coarser levels, undefined until levels() writes them. */
    private static FrameBuffer levelled(FrameBuffer buffer) {
        buffer.getColorBufferTexture().bind();
        for (int level = 1; level <= LEVELS; level++) {
            Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, level, GL30.GL_DEPTH_COMPONENT16, Math.max(1, buffer.getWidth() >> level),
                  Math.max(1, buffer.getHeight() >> level), 0, GL20.GL_DEPTH_COMPONENT, GL20.GL_UNSIGNED_SHORT, null);
        }
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, LEVELS);
        return buffer;
    }

    /**
     * Writes the coarser levels from the finest, after the map changed: each keeps one texel of every two by two of the
     * level above (shadow-levels.frag). Here the map is read as plain depth, its base level the one above the level
     * written, which stays within the levels a complete framebuffer may attach, and filtered without levels, so only that
     * base is read; the framebuffer, viewport and depth state it found are restored.
     */
    void levels() {
        var state = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, state);
        int framebuffer = state.get(0);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, state);
        int x = state.get(0), y = state.get(1), width = state.get(2), height = state.get(3);
        Gdx.gl.glGetIntegerv(GL20.GL_DEPTH_FUNC, state);
        int depthFunc = state.get(0);
        boolean depthTest = Gdx.gl.glIsEnabled(GL20.GL_DEPTH_TEST), cull = Gdx.gl.glIsEnabled(GL20.GL_CULL_FACE);
        boolean blend = Gdx.gl.glIsEnabled(GL20.GL_BLEND), scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        var mask = BufferUtils.newByteBuffer(16);
        Gdx.gl.glGetBooleanv(GL20.GL_DEPTH_WRITEMASK, mask);
        boolean depthMask = mask.get(0) != 0;
        Texture depth = fbo.getColorBufferTexture();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, levelFramebuffer);
        var none = BufferUtils.newIntBuffer(1);
        none.put(GL20.GL_NONE).flip();
        Gdx.gl30.glDrawBuffers(1, none);
        Gdx.gl30.glReadBuffer(GL20.GL_NONE);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
        Gdx.gl.glDepthMask(true);
        depth.bind(0);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL20.GL_NONE);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
        levelProgram.bind();
        levelProgram.setUniformi("u_depth", 0);
        for (int level = 1; level <= LEVELS; level++) {
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, level - 1);
            Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
                  depth.getTextureObjectHandle(), level);
            Gdx.gl.glViewport(0, 0, Math.max(1, depth.getWidth() >> level), Math.max(1, depth.getHeight() >> level));
            levelQuad.render(levelProgram, GL20.GL_TRIANGLES);
        }
        // Detached, so a resize's deleted map is not kept alive by this framebuffer.
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D, 0, 0);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, depth.getMinFilter().getGLEnum());
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glViewport(x, y, width, height);
        Gdx.gl.glDepthFunc(depthFunc);
        Gdx.gl.glDepthMask(depthMask);
        if (!depthTest) { Gdx.gl.glDisable(GL20.GL_DEPTH_TEST); }
        if (cull) { Gdx.gl.glEnable(GL20.GL_CULL_FACE); }
        if (blend) { Gdx.gl.glEnable(GL20.GL_BLEND); }
        if (scissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); }
    }

    @Override
    public void dispose() {
        super.dispose();
        levelProgram.dispose();
        levelQuad.dispose();
        Gdx.gl.glDeleteFramebuffer(levelFramebuffer);
    }

    /**
     * The shadow pass's map texels across (shadow-depth.frag), from the light's camera it draws with; another camera
     * (the outline depth) takes the smallest map's.
     */
    static void register(BaseShader shader) {
        shader.register("u_shadowTexels", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, (float) (target.camera instanceof SlabCamera slab ? slab.texels
                      : GpuTerrainShadows.MIN_RESOLUTION));
            }
        });
    }

    /** Caches copy only depth, with exactly the light's attachment format and normal framebuffer ownership. */
    static FrameBuffer depthBuffer(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addDepthTextureAttachment(GL30.GL_DEPTH_COMPONENT16, GL20.GL_UNSIGNED_SHORT);
        return new DepthBuffer(builder);
    }

    @Override
    public void begin() {
        // Clear the complete map, including its unoccluded border, after screen passes disabled depth writes.
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(1);
        super.begin();
        depthClamp(true);
    }

    @Override
    public void end() {
        depthClamp(false);
        super.end();
    }

    /**
     * Casters outside the slab (SlabCamera) still cast, at its top or bottom: depth clamping keeps what the slab's planes
     * would clip.
     */
    static void depthClamp(boolean enabled) {
        if (enabled) { Gdx.gl.glEnable(GL32.GL_DEPTH_CLAMP); }
        else { Gdx.gl.glDisable(GL32.GL_DEPTH_CLAMP); }
    }

    /**
     * Whether a light along {@code direction} (normalized) takes the slab (SlabCamera): every light the world-fixed sun
     * and moon cast, down to a little below the sun's lowest. A lower one, only the camera-relative light, keeps depth
     * along the light: over its long slab, the drift between a comparison's texels would need a bias that lifts shadows
     * off their casters.
     */
    static boolean slab(Vector3 direction) {
        return direction.z <= BoardAtmosphere.LOWEST_SUN_Z * .98f;
    }

    /**
     * An orthographic light camera whose depth measures height across the casters' slab instead of distance along the
     * light: its {@code near} and {@code far} are the world heights of the slab's top (depth 0) and bottom (depth 1).
     * Along every ray of light depth still grows away from the sun, so the map compares as before, while its precision
     * spans the slab's height rather than the board's whole extent along the light. Across the map it is the light's own
     * orthographic projection. A light that does not take the slab (slab) is a plain orthographic camera.
     */
    static final class SlabCamera extends OrthographicCamera {
        /** Texels across the map this camera fits (GpuTerrainShadows.resolution). */
        int texels = GpuTerrainShadows.MIN_RESOLUTION;

        @Override
        public void update(boolean updateFrustum) {
            if (!slab(direction)) {
                super.update(updateFrustum);
                return;
            }
            super.update(false);
            float scale = 2 / (near - far);
            float[] m = combined.val;
            m[Matrix4.M20] = 0;
            m[Matrix4.M21] = 0;
            m[Matrix4.M22] = -scale;
            m[Matrix4.M23] = scale * near - 1;
            if (updateFrustum) {
                invProjectionView.set(combined).inv();
                frustum.update(invProjectionView);
            }
        }
    }

    /**
     * Copies depth inside each rectangle of runs (x0, y0, x1, y1 texels, four values each) between two maps of one
     * size, as GLFrameBuffer#transfer copies the whole map.
     */
    static void copyDepth(FrameBuffer from, FrameBuffer to, IntArray runs) {
        DepthBuffer.copy(from, to, runs);
    }

    /** libGDX 1.14.2 otherwise attaches a lone texture as COLOR_ATTACHMENT0, even for a depth specification. */
    private static final class DepthBuffer extends FrameBuffer {
        DepthBuffer(GLFrameBuffer.FrameBufferBuilder builder) { super(builder); }

        static void copy(FrameBuffer from, FrameBuffer to, IntArray runs) {
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.getFramebufferHandle());
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.getFramebufferHandle());
            for (int i = 0; i < runs.size; i += 4) {
                int x0 = runs.get(i), y0 = runs.get(i + 1), x1 = runs.get(i + 2), y1 = runs.get(i + 3);
                Gdx.gl30.glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL20.GL_DEPTH_BUFFER_BIT, GL20.GL_NEAREST);
            }
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, defaultFramebufferHandle);
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, defaultFramebufferHandle);
        }

        @Override
        protected Texture createTexture(FrameBufferTextureAttachmentSpec specification) {
            Texture texture = super.createTexture(specification);
            texture.bind();
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_FUNC, GL20.GL_LEQUAL);
            return texture;
        }

        @Override
        protected void attachFrameBufferColorTexture(Texture texture) {
            Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
                  texture.getTextureObjectHandle(), 0);
            var none = BufferUtils.newIntBuffer(1);
            none.put(GL20.GL_NONE).flip();
            Gdx.gl30.glDrawBuffers(1, none);
            Gdx.gl30.glReadBuffer(GL20.GL_NONE);
        }
    }
}
