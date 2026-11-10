/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import megamek.logging.MMLogger;

/**
 * Blurs and dims what is on screen behind an overlay, as glass over the scene: after the scene and its own interface
 * have drawn, {@link #apply(float, Color)} copies the frame, blurs a quarter-size copy with dual Kawase passes and
 * draws it back over the frame, then dims it; the overlay draws after. Strength 0 leaves the frame as it was and 1
 * blurs it fully, so a strength easing in fades the blur in. The frame is copied each call, so a live scene keeps moving
 * behind the glass. Call it on the GL thread with the default framebuffer bound; {@link #dispose()} it with its view.
 */
public class UiBackdropBlur implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(UiBackdropBlur.class);
    private static final int DOWNSAMPLE = 4;
    private static final int PASSES = 4;

    private static final String VERTEX = """
          attribute vec4 a_position;
          attribute vec4 a_color;
          attribute vec2 a_texCoord0;
          uniform mat4 u_projTrans;
          varying vec4 v_color;
          varying vec2 v_texCoords;
          void main() {
              v_color = a_color;
              v_texCoords = a_texCoord0;
              gl_Position = u_projTrans * a_position;
          }
          """;

    private static final String KAWASE = """
          #ifdef GL_ES
          precision mediump float;
          #endif
          varying vec4 v_color;
          varying vec2 v_texCoords;
          uniform sampler2D u_texture;
          uniform vec2 u_step;
          void main() {
              vec4 sum = texture2D(u_texture, v_texCoords) * 0.2;
              sum += texture2D(u_texture, v_texCoords + vec2(u_step.x, u_step.y)) * 0.2;
              sum += texture2D(u_texture, v_texCoords + vec2(-u_step.x, u_step.y)) * 0.2;
              sum += texture2D(u_texture, v_texCoords + vec2(u_step.x, -u_step.y)) * 0.2;
              sum += texture2D(u_texture, v_texCoords + vec2(-u_step.x, -u_step.y)) * 0.2;
              gl_FragColor = vec4(sum.rgb, 1.0) * v_color;
          }
          """;

    private SpriteBatch batch;
    private ShaderProgram kawase;
    private Texture frame;
    private Texture white;
    private FrameBuffer ping;
    private FrameBuffer pong;
    private int width;
    private int height;
    private boolean failed;

    /**
     * Blurs the frame on screen by {@code strength} (0 to 1) and lays {@code dim} over it at {@code strength} times its
     * alpha. Leaves the viewport set to the whole back buffer; an overlay stage should apply its viewport before drawing.
     */
    public void apply(float strength, Color dim) {
        if (strength <= 0 || failed) {
            if (failed && strength > 0) {
                dimOnly(strength, dim);
            }
            return;
        }
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        if (w <= 0 || h <= 0 || !ready(w, h)) {
            dimOnly(strength, dim);
            return;
        }
        frame.bind();
        Gdx.gl.glCopyTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);

        int sw = ping.getWidth();
        int sh = ping.getHeight();
        batch.setShader(kawase);
        batch.disableBlending();
        FrameBuffer source = null;
        FrameBuffer target = ping;
        // The copy and the framebuffers keep GL's bottom-up rows, so every draw of them flips back.
        for (int pass = 0; pass < PASSES; pass++) {
            target.begin();
            batch.getProjectionMatrix().setToOrtho2D(0, 0, sw, sh);
            batch.begin();
            float reach = pass + .5f;
            if (source == null) {
                kawase.setUniformf("u_step", reach / w, reach / h);
                batch.draw(frame, 0, 0, sw, sh, 0, 0, w, h, false, true);
            } else {
                kawase.setUniformf("u_step", reach / sw, reach / sh);
                batch.draw(source.getColorBufferTexture(), 0, 0, sw, sh, 0, 0, sw, sh, false, true);
            }
            batch.end();
            target.end();
            source = target;
            target = target == ping ? pong : ping;
        }
        batch.setShader(null);
        batch.enableBlending();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        Gdx.gl.glViewport(0, 0, w, h);
        batch.begin();
        batch.setColor(1, 1, 1, Math.min(1, strength));
        batch.draw(source.getColorBufferTexture(), 0, 0, w, h, 0, 0, sw, sh, false, true);
        batch.setColor(dim.r, dim.g, dim.b, dim.a * strength);
        batch.draw(white, 0, 0, w, h);
        batch.setColor(Color.WHITE);
        batch.end();
    }

    /** Lays the dim over the frame alone, when the blur cannot run. */
    private void dimOnly(float strength, Color dim) {
        if (batch == null) {
            batch = new SpriteBatch(4);
        }
        if (white == null) {
            white = solid();
        }
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, w, h);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        batch.setColor(dim.r, dim.g, dim.b, Math.min(1, dim.a * strength * 1.3f));
        batch.draw(white, 0, 0, w, h);
        batch.setColor(Color.WHITE);
        batch.end();
    }

    /** Makes or remakes the copies for a {@code w} by {@code h} back buffer; false if the blur cannot run. */
    private boolean ready(int w, int h) {
        if (batch == null) {
            batch = new SpriteBatch(4);
        }
        if (kawase == null) {
            kawase = new ShaderProgram(VERTEX, KAWASE);
            if (!kawase.isCompiled()) {
                LOGGER.warn("The backdrop blur shader did not compile; overlays dim the scene only: {}",
                      kawase.getLog());
                kawase.dispose();
                kawase = null;
                failed = true;
                return false;
            }
        }
        if (white == null) {
            white = solid();
        }
        if (frame != null && width == w && height == h) {
            return true;
        }
        disposeTargets();
        width = w;
        height = h;
        frame = new Texture(w, h, Pixmap.Format.RGBA8888);
        frame.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        int sw = Math.max(1, w / DOWNSAMPLE);
        int sh = Math.max(1, h / DOWNSAMPLE);
        ping = new FrameBuffer(Pixmap.Format.RGBA8888, sw, sh, false);
        pong = new FrameBuffer(Pixmap.Format.RGBA8888, sw, sh, false);
        for (FrameBuffer target : new FrameBuffer[] { ping, pong }) {
            target.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            target.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        frame.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return true;
    }

    private static Texture solid() {
        Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE);
        pixel.fill();
        Texture texture = new Texture(pixel);
        pixel.dispose();
        return texture;
    }

    private void disposeTargets() {
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
        if (ping != null) {
            ping.dispose();
            ping = null;
        }
        if (pong != null) {
            pong.dispose();
            pong = null;
        }
    }

    @Override
    public void dispose() {
        disposeTargets();
        if (kawase != null) {
            kawase.dispose();
            kawase = null;
        }
        if (white != null) {
            white.dispose();
            white = null;
        }
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
    }
}
