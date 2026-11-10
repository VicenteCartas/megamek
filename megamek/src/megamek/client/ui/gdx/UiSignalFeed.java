/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.utils.Disposable;
import megamek.logging.MMLogger;

/**
 * A picture seen through a comm feed: tinted toward the signal color, crossed by scanlines and flecks of static, now and
 * then torn by a glitch band shifted sideways under a bright line, and booting up from a line of light across its
 * middle that opens into the picture. Corner brackets frame it. How rough the feed is comes from its {@link Signal};
 * without motion it shows settled, tinted and scanned, with no static, glitches or flicker. The widget owns a shader:
 * {@link #dispose()} it with its view.
 */
public class UiSignalFeed extends Widget implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(UiSignalFeed.class);

    /**
     * How rough a feed is: the tint's opacity; scanlines every {@code scanlineGap} stage units at
     * {@code scanlineOpacity}; static over {@code noiseDensity} of the picture (0 to 1) at {@code noiseOpacity}; and
     * a glitch starting on any 1/8 s step at {@code glitchChance}, lasting {@code glitchStepsMin} to
     * {@code glitchStepsMax} steps, shifting a band {@code glitchHeightMin} to {@code glitchHeightMax} units high up to
     * {@code glitchOffset} units sideways under a line at {@code glitchLineOpacity}.
     */
    public record Signal(float tintOpacity, float scanlineGap, float scanlineOpacity, float noiseDensity,
          float noiseOpacity, float glitchChance, int glitchStepsMin, int glitchStepsMax, float glitchOffset,
          float glitchHeightMin, float glitchHeightMax, float glitchLineOpacity) {
        /** A clean feed: a light tint and faint scanlines, no static or glitches. */
        public static final Signal CLEAN = new Signal(.04f, 5, .08f, 0, 0, 0, 1, 1, 0, 1, 1, 0);
    }

    private static final float STEP = .125f;
    private static final float BOOT_SECONDS = .7f;

    private static final String VERTEX = """
          attribute vec4 a_position;
          attribute vec4 a_color;
          attribute vec2 a_texCoord0;
          uniform mat4 u_projTrans;
          varying vec4 v_color;
          varying vec2 v_texCoords;
          void main() {
              v_color = a_color;
              v_color.a = v_color.a * (255.0 / 254.0);
              v_texCoords = a_texCoord0;
              gl_Position = u_projTrans * a_position;
          }
          """;

    private static final String FRAGMENT = """
          #ifdef GL_ES
          precision mediump float;
          #endif
          varying vec4 v_color;
          varying vec2 v_texCoords;
          uniform sampler2D u_texture;
          uniform vec4 u_region;
          uniform vec2 u_size;
          uniform vec4 u_tint;
          uniform vec2 u_scan;
          uniform vec3 u_noise;
          uniform vec4 u_glitch;
          uniform vec3 u_line;
          uniform float u_boot;
          uniform float u_flicker;
          float hash(vec2 p) {
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
          }
          void main() {
              vec2 span = u_region.zw - u_region.xy;
              vec2 local = (v_texCoords - u_region.xy) / span;
              float py = local.y * u_size.y;
              float band = step(u_glitch.x, py) * step(py, u_glitch.y);
              vec2 uv = v_texCoords + vec2(band * u_glitch.z / u_size.x * span.x, 0.0);
              float inside = step(min(u_region.x, u_region.z), uv.x) * step(uv.x, max(u_region.x, u_region.z));
              vec4 art = texture2D(u_texture, uv) * inside + vec4(0.0, 0.0, 0.0, 1.0 - inside) * band;
              float luma = dot(art.rgb, vec3(0.299, 0.587, 0.114));
              float settle = 1.0 - u_boot;
              vec3 colour = mix(art.rgb, u_tint.rgb, u_tint.a);
              colour = mix(colour, u_tint.rgb * (0.25 + luma * 1.5), settle * settle * 0.85);
              float line = step(u_scan.x - 1.0, mod(py, u_scan.x));
              colour *= 1.0 - line * u_scan.y;
              vec2 cell = floor(vec2(local.x * u_size.x / 6.0, py / 2.0));
              float fleck = step(1.0 - u_noise.x, hash(cell + vec2(u_noise.z, u_noise.z * 0.37)));
              colour = mix(colour, mix(u_tint.rgb, vec3(1.0), 0.5), fleck * u_noise.y);
              float edge = 1.0 - step(1.0, abs(py - u_glitch.x));
              colour = mix(colour, u_line, edge * u_glitch.w);
              float open = smoothstep(0.0, 0.55, u_boot);
              float gap = open - abs(local.y - 0.5) * 2.0;
              if (gap < -0.02) {
                  discard;
              }
              float rim = 1.0 - smoothstep(0.0, 0.03, abs(gap));
              colour += u_tint.rgb * rim * settle * 1.6;
              float alpha = art.a * smoothstep(-0.02, 0.0, gap) * u_flicker;
              gl_FragColor = vec4(colour, alpha) * v_color;
          }
          """;

    private final TextureRegion picture;
    private final TextureRegion white;
    private final Color tint;
    private final Color line;
    private final Signal signal;
    private final boolean motion;
    private final float prefWidth;
    private final float prefHeight;
    private final Random random = new Random();
    private ShaderProgram shader;
    private boolean failed;
    private float stepTime;
    private int step;
    private int glitchSteps;
    private float glitchTop;
    private float glitchHeight;
    private float glitchShift;
    private float boot;
    private float flicker = 1;

    /**
     * A feed of {@code picture}, preferring {@code width} by {@code height} stage units, tinted {@code tint} with glitch
     * lines in {@code line}; {@code white} is a solid white region for the brackets. It boots up once it is on stage,
     * unless {@code motion} is off.
     */
    public UiSignalFeed(TextureRegion picture, TextureRegion white, float width, float height, Color tint, Color line,
          Signal signal, boolean motion) {
        this.picture = picture;
        this.white = white;
        this.tint = new Color(tint);
        this.line = new Color(line);
        this.signal = signal;
        this.motion = motion;
        prefWidth = width;
        prefHeight = height;
        boot = motion ? 0 : 1;
    }

    /** Starts the boot-up over, as when the channel opens again. */
    public void reboot() {
        boot = motion ? 0 : 1;
    }

    /** Whether the feed has booted up. */
    public boolean booted() {
        return boot >= 1;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!motion) {
            return;
        }
        boot = Math.min(1, boot + delta / BOOT_SECONDS);
        stepTime += delta;
        while (stepTime >= STEP) {
            stepTime -= STEP;
            step();
        }
    }

    /** One 1/8 s step of the feed, as the Swing panel's timer ticks: static moves, glitches start and run out. */
    private void step() {
        step++;
        flicker = boot < 1 && random.nextFloat() < .35f ? .55f + .3f * random.nextFloat() : 1;
        if (glitchSteps > 0) {
            glitchSteps--;
        } else if (signal.glitchChance() > 0 && random.nextFloat() < signal.glitchChance()) {
            glitchSteps = signal.glitchStepsMin() + random.nextInt(Math.max(1, signal.glitchStepsMax()
                  - signal.glitchStepsMin() + 1));
            glitchShift = (random.nextFloat() * 2 - 1) * signal.glitchOffset();
            glitchHeight = Math.max(1, signal.glitchHeightMin() + random.nextFloat() * (signal.glitchHeightMax()
                  - signal.glitchHeightMin()));
            glitchTop = random.nextFloat() * Math.max(0, getHeight() - glitchHeight);
        }
    }

    @Override
    public float getPrefWidth() {
        return prefWidth;
    }

    @Override
    public float getPrefHeight() {
        return prefHeight;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        float alpha = getColor().a * parentAlpha;
        float x = getX();
        float y = getY();
        float width = getWidth();
        float height = getHeight();
        Color before = new Color(batch.getColor());
        ShaderProgram program = shader();
        batch.setColor(1, 1, 1, alpha);
        if (program == null) {
            batch.draw(picture, x, y, width, height);
        } else {
            batch.setShader(program);
            program.setUniformf("u_region", picture.getU(), picture.getV(), picture.getU2(), picture.getV2());
            program.setUniformf("u_size", width, height);
            program.setUniformf("u_tint", tint.r, tint.g, tint.b, signal.tintOpacity());
            program.setUniformf("u_scan", Math.max(2, signal.scanlineGap()), signal.scanlineOpacity());
            boolean live = motion;
            program.setUniformf("u_noise", live ? signal.noiseDensity() : 0, signal.noiseOpacity(), step % 977);
            boolean glitching = live && glitchSteps > 0;
            program.setUniformf("u_glitch", glitching ? glitchTop : -10, glitching ? glitchTop + glitchHeight : -10,
                  glitchShift, glitching ? signal.glitchLineOpacity() : 0);
            program.setUniformf("u_line", line.r, line.g, line.b);
            program.setUniformf("u_boot", boot);
            program.setUniformf("u_flicker", flicker);
            batch.draw(picture, x, y, width, height);
            batch.setShader(null);
        }
        brackets(batch, x, y, width, height, alpha * Math.min(1, boot * 1.5f));
        batch.setColor(before);
    }

    /** The feed's frame: a faint outline and bright corner brackets. */
    private void brackets(Batch batch, float x, float y, float width, float height, float alpha) {
        float thin = 1;
        batch.setColor(tint.r, tint.g, tint.b, .28f * alpha);
        batch.draw(white, x, y, width, thin);
        batch.draw(white, x, y + height - thin, width, thin);
        batch.draw(white, x, y, thin, height);
        batch.draw(white, x + width - thin, y, thin, height);
        float arm = Math.min(width, height) * .14f;
        float thick = 2;
        float out = 3;
        batch.setColor(tint.r, tint.g, tint.b, .95f * alpha);
        for (int corner = 0; corner < 4; corner++) {
            boolean right = (corner & 1) == 1;
            boolean top = corner >= 2;
            float cx = right ? x + width + out : x - out;
            float cy = top ? y + height + out : y - out;
            float sx = right ? -1 : 1;
            float sy = top ? -1 : 1;
            batch.draw(white, sx > 0 ? cx : cx - arm, sy > 0 ? cy : cy - thick, arm, thick);
            batch.draw(white, sx > 0 ? cx : cx - thick, sy > 0 ? cy : cy - arm, thick, arm);
        }
    }

    private ShaderProgram shader() {
        if (shader == null && !failed) {
            shader = new ShaderProgram(VERTEX, FRAGMENT);
            if (!shader.isCompiled()) {
                LOGGER.warn("The signal feed shader did not compile; the picture shows plain: {}", shader.getLog());
                shader.dispose();
                shader = null;
                failed = true;
            }
        }
        return shader;
    }

    @Override
    public void dispose() {
        if (shader != null) {
            shader.dispose();
            shader = null;
        }
    }
}
