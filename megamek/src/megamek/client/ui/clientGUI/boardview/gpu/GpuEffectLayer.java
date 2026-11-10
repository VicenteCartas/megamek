/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * A half-resolution layer for soft volumes (fire, smoke, dust): they are drawn premultiplied into its buffer, a quarter
 * of the scene's pixels, and joined to the scene along the opaque depth's edges. The caller owns the blend state.
 */
final class GpuEffectLayer implements Disposable {
    private FrameBuffer buffer;
    private ShaderProgram composite;
    private Mesh quad;

    /** Links its program now, not at the first effect in play. */
    void prepare() {
        if (composite != null) { return; }
        composite = GpuShaderManager.program(() -> GpuAtmosphere.shader("terrain-effects-composite.frag"), next -> composite = next);
        quad = GpuAtmosphere.screenQuad();
    }

    int width() { return buffer.getWidth(); }
    int height() { return buffer.getHeight(); }
    Texture texture() { return buffer == null ? null : buffer.getColorBufferTexture(); }

    /** Binds its cleared buffer, half the size of the target {@code depth} was captured from. */
    void begin(GpuEffectDepth depth) {
        prepare();
        int width = Math.max(1, (depth.width + 1) / 2), height = Math.max(1, (depth.height + 1) / 2);
        if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
            if (buffer != null) { buffer.dispose(); }
            // Integrate and upsample premultiplied linear radiance without clipping emissive flame.
            buffer = GpuHeatGlow.buffer(width, height);
            buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        }
        buffer.begin();
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    /** Back on the scene's target, draws the layer over it; texture units 0 and 1 are left bound to its inputs. */
    void end(GpuEffectDepth depth, Texture opaqueDepth, Camera camera) {
        depth.restoreTarget();
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        buffer.getColorBufferTexture().bind(1);
        opaqueDepth.bind(0);
        composite.bind();
        composite.setUniformi("u_depth", 0);
        composite.setUniformi("u_effect", 1);
        composite.setUniformf("u_size", buffer.getWidth(), buffer.getHeight());
        composite.setUniformf("u_viewport", depth.x, depth.y, depth.width, depth.height);
        composite.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
        quad.render(composite, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        if (quad != null) { quad.dispose(); quad = null; }
        if (composite != null) { GpuShaderManager.dispose(composite); composite = null; }
        if (buffer != null) { buffer.dispose(); buffer = null; }
    }
}
