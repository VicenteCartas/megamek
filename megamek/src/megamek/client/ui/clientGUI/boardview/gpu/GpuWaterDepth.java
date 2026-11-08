/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * The nearest water of a frame, displaced surface or board-edge section, in a depth target of its own. The water's
 * colour pass tests against it, so a crest hides the waves behind it at grazing angles and a section shows only where
 * it is the first water a ray meets; fog stops at it. The scene's own depth keeps the beds and units beneath: unit
 * outlines, tactical overlays and weather never see the moving waves. Sized like the viewport it is drawn for, so a
 * pixel's depth sits at that pixel's texel. {@link GpuCutoutFade} keeps the nearest glass's depth in one too.
 */
final class GpuWaterDepth implements Disposable {
    private final IntBuffer query = BufferUtils.newIntBuffer(16);
    private int framebuffer;
    private Texture depth;
    private boolean current;

    /** The target the colour pass tests against, once created. */
    Texture texture() { return depth; }

    /** This frame's nearest water, or null when the frame drew no water. */
    Texture nearest() { return current ? depth : null; }

    /** A new frame: no water surface yet. */
    void invalidate() { current = false; }

    /**
     * Binds the target cleared to the far plane, for the water's depth-only pass, and returns the framebuffer that was
     * bound, to restore afterwards. The viewport is left as it was.
     */
    int begin() {
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query);
        int previous = query.get(0);
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
        int width = query.get(0) + query.get(2), height = query.get(1) + query.get(3);
        if (depth == null || depth.getWidth() != width || depth.getHeight() != height) { create(width, height); }
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        current = true;
        return previous;
    }

    private void create(int width, int height) {
        dispose();
        depth = new Texture(new GLOnlyTextureData(width, height, 0, GL30.GL_DEPTH_COMPONENT24, GL20.GL_DEPTH_COMPONENT,
              GL20.GL_UNSIGNED_INT));
        depth.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        depth.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        framebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
              depth.getTextureObjectHandle(), 0);
        // Depth only: no colour is ever written or read.
        IntBuffer none = BufferUtils.newIntBuffer(1);
        none.put(GL20.GL_NONE).flip();
        Gdx.gl30.glDrawBuffers(1, none);
        Gdx.gl30.glReadBuffer(GL20.GL_NONE);
        if (Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER) != GL20.GL_FRAMEBUFFER_COMPLETE) {
            dispose();
            throw new IllegalStateException("GPU water depth target is incomplete");
        }
    }

    @Override
    public void dispose() {
        if (framebuffer != 0) { Gdx.gl.glDeleteFramebuffer(framebuffer); }
        framebuffer = 0;
        if (depth != null) { depth.dispose(); }
        depth = null;
        current = false;
    }
}
