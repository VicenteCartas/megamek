/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * FSR 1 at a window's render resolution below 1: EASU scales the display-encoded composite (after SMAA when it is on) up
 * to the window's pixels, then RCAS sharpens it into the window's framebuffer, which also receives the scene's hardware
 * depth for overlay occlusion. The fsr-*.frag shaders port ffx_fsr1.h (AMD, MIT; the notice is in each). Borrows the
 * screen quad; owns its two programs, the render-sized input and the window-sized upscale.
 */
final class GpuFsr implements Disposable {
    private final Mesh quad;
    private ShaderProgram easu;
    private ShaderProgram rcas;
    private FrameBuffer input;
    private FrameBuffer upscaled;

    GpuFsr(Mesh quad) {
        this.quad = quad;
        try {
            easu = GpuShaderManager.program(() -> GpuAtmosphere.shader("fsr-easu.frag"), next -> easu = next);
            rcas = GpuShaderManager.program(() -> GpuAtmosphere.shader("fsr-rcas.frag"), next -> rcas = next);
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    /** The render-sized target that the composite, or SMAA's blend, draws into. */
    FrameBuffer input(int width, int height) {
        if (input == null || input.getWidth() != width || input.getHeight() != height) {
            if (input != null) { input.dispose(); input = null; }
            input = GpuSmaa.target(width, height, GL30.GL_RGBA8, GL20.GL_RGBA);
        }
        return input;
    }

    /** EASU into a {@code width} by {@code height} target, then RCAS into the window at {@code bottom}. */
    void end(Texture sceneDepth, Camera camera, int bottom, int width, int height) {
        end(sceneDepth, camera, bottom, width, height, null);
    }

    /** The final display may be the window or an embedded preview's full-resolution framebuffer. */
    void end(Texture sceneDepth, Camera camera, int bottom, int width, int height, FrameBuffer target) {
        if (upscaled == null || upscaled.getWidth() != width || upscaled.getHeight() != height) {
            if (upscaled != null) { upscaled.dispose(); upscaled = null; }
            upscaled = GpuSmaa.target(width, height, GL30.GL_RGBA8, GL20.GL_RGBA);
        }
        GpuAtmosphere.screenState();
        upscaled.begin();
        input.getColorBufferTexture().bind(0);
        easu.bind();
        easu.setUniformi("u_color", 0);
        quad.render(easu, GL20.GL_TRIANGLES);
        if (target != null) { target.begin(); }
        else {
            FrameBuffer.unbind();
            HdpiUtils.glViewport(0, bottom, (int) camera.viewportWidth, (int) camera.viewportHeight);
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
        Gdx.gl.glDepthMask(true);
        upscaled.getColorBufferTexture().bind(0);
        sceneDepth.bind(1);
        rcas.bind();
        rcas.setUniformi("u_color", 0);
        rcas.setUniformi("u_depth", 1);
        quad.render(rcas, GL20.GL_TRIANGLES);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** Releases both targets; the programs stay for the next time the render resolution is below 1. */
    void disposeTargets() {
        if (input != null) { input.dispose(); input = null; }
        if (upscaled != null) { upscaled.dispose(); upscaled = null; }
    }

    @Override
    public void dispose() {
        disposeTargets();
        GpuShaderManager.dispose(easu);
        GpuShaderManager.dispose(rcas);
    }
}
