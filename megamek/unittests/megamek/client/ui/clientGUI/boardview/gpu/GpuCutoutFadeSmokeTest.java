/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Faded cutout texels stack as in life and in any order: the behaviour GpuCutoutFade exists for, on the real GPU path. */
@Tag("gpuBoardSmoke")
class GpuCutoutFadeSmokeTest {
    private record Layer(float red, float green, float alpha, float depth) { }

    private static final String VERTEX = """
          #version 330 core
          in vec4 a_position;
          uniform float u_depth;
          void main() { gl_Position = vec4(a_position.xy, u_depth * 2.0 - 1.0, 1.0); }
          """;

    private GpuWaterDepth glass;
    private FrameBuffer scene;
    private Texture sceneDepth;
    private GpuCutoutFade fade;
    private ShaderProgram layers;
    private float side;
    private Mesh quad;

    @Test
    void fadedTexelsStackInAnyOrderBehindWhatIsSolid() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(320, 200);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    check();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Cutout fade", failure.get()); }
    }

    private void check() {
        scene = GpuHeatGlow.buffer(64, 64);
        sceneDepth = GpuAtmosphere.attachDepthTexture(scene);
        fade = new GpuCutoutFade();
        glass = new GpuWaterDepth();
        quad = GpuAtmosphere.screenQuad();
        layers = GpuGlsl.compile("cutout fade layers", "#define blendedFlag\n#define alphaTestFlag\n#define modelSurfaceFlag\n"
              + "#define diffuseTextureFlag\n", VERTEX, "#version 330 core\nlayout(location = 0) out vec4 fragColor;\n"
              + "uniform sampler2D u_diffuseTexture;\nuniform vec4 u_colour;\n" + GpuShaderSource.read("cutout-fade.glsl")
              + "\nvoid main() { if (u_colour.a <= cutoutFadeCut(0.0, vec2(0.0))) discard; fragColor = u_colour; cutoutFadeWrite(fragColor); }\n");

        // Each layer lets through what the last one left, times its own remainder: more layers, less background.
        Layer half = new Layer(1, 0, .5f, .3f);
        assertEquals(1, draw(1, List.of())[2], .002f, "No layer leaves the background");
        assertEquals(.5f, draw(1, List.of(half))[2], .005f, "One half layer");
        assertEquals(.25f, draw(1, List.of(half, half))[2], .005f, "Two half layers");
        assertEquals(.125f, draw(1, List.of(half, half, half))[2], .005f, "Three half layers");
        Layer a = new Layer(1, 0, .4f, .2f), b = new Layer(0, 1, .6f, .3f), c = new Layer(.5f, .5f, .3f, .4f);
        assertEquals(.6f * .4f * .7f, draw(1, List.of(a, b, c))[2], .005f, "Layers of different alpha");

        // The picture does not depend on the order they draw in.
        float[] first = draw(1, List.of(a, b, c));
        for (List<Layer> order : List.of(List.of(a, c, b), List.of(b, a, c), List.of(b, c, a), List.of(c, a, b), List.of(c, b, a))) {
            float[] other = draw(1, order);
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(first[channel], other[channel], .002f, "Channel " + channel + " in another order");
            }
        }

        // Nearer layers count more, whichever colour they are.
        float[] redNearer = draw(1, List.of(new Layer(1, 0, .5f, .2f), new Layer(0, 1, .5f, .4f)));
        float[] greenNearer = draw(1, List.of(new Layer(1, 0, .5f, .4f), new Layer(0, 1, .5f, .2f)));
        assertTrue(redNearer[0] > 5 * redNearer[1], "The nearer red leads: " + redNearer[0] + " over " + redNearer[1]);
        assertTrue(greenNearer[1] > 5 * greenNearer[0], "The nearer green leads: " + greenNearer[1] + " over " + greenNearer[0]);

        // A solid texel at depth .5: what lies behind it is hidden, what lies in front blends over it.
        assertEquals(1, draw(.5f, List.of(new Layer(1, 0, .5f, .7f)))[2], .002f, "A fade behind a solid texel");
        assertEquals(.5f, draw(.5f, List.of(new Layer(1, 0, .5f, .3f)))[2], .005f, "A fade before a solid texel");

        // Glass at depth .5 splits them: each pass draws only the layers on its side of it.
        Gdx.gl.glClearDepthf(.5f);
        scene.begin();
        int bound = glass.begin();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, bound);
        scene.end();
        Gdx.gl.glClearDepthf(1);
        Layer nearer = new Layer(1, 0, .5f, .3f), farther = new Layer(0, 1, .5f, .7f);
        side = -1;
        float[] behind = draw(1, List.of(nearer, farther));
        side = 1;
        float[] inFront = draw(1, List.of(nearer, farther));
        side = 0;
        assertTrue(behind[1] > .4f && behind[0] < .01f, "Behind the glass, the farther layer alone: " + behind[0] + ", " + behind[1]);
        assertTrue(inFront[0] > .4f && inFront[1] < .01f, "In front of it, the nearer layer alone: " + inFront[0] + ", " + inFront[1]);
    }

    /** A blue background whose depth is {@code solid}, then these layers; the resulting pixel. */
    private float[] draw(float solid, List<Layer> order) {
        scene.begin();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(solid);
        ScreenUtils.clear(0, 0, 1, 1, true);
        Gdx.gl.glClearDepthf(1);
        int bound = fade.begin();
        assertTrue(bound > 0, "The scene shares its depth");
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LESS);
        Gdx.gl.glDepthMask(false);
        layers.bind();
        layers.setUniformf("u_modelFade", 2);
        layers.setUniformf("u_modelFadeDepth", 0, 20);
        layers.setUniformf("u_modelFadeSide", side);
        if (side != 0) {
            glass.texture().bind(5);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            layers.setUniformi("u_modelFadeGlass", 5);
        }
        for (Layer layer : order) {
            layers.setUniformf("u_colour", layer.red, layer.green, 0, layer.alpha);
            layers.setUniformf("u_depth", layer.depth);
            quad.render(layers, GL20.GL_TRIANGLES);
        }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        fade.end(bound);
        FloatBuffer pixel = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glReadPixels(32, 32, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
        scene.end();
        return new float[] { pixel.get(0), pixel.get(1), pixel.get(2) };
    }

    private void dispose() {
        if (glass != null) { glass.dispose(); }
        if (layers != null) { layers.dispose(); }
        if (quad != null) { quad.dispose(); }
        if (fade != null) { fade.dispose(); }
        if (sceneDepth != null) { sceneDepth.dispose(); }
        if (scene != null) { scene.dispose(); }
    }
}
