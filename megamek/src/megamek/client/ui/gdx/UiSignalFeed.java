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
 * then torn by a glitch band shifted sideways under a bright line, darkened toward its edges like a tube's face, with a
 * faint interference bar drifting down it. It tunes in from snow: the picture rolls, shears and splits into its colors
 * and settles as the snow clears. Rougher signals keep more of the bar and the color split. While its speaker talks
 * ({@link #voice(float)}) the picture brightens with their voice. Corner brackets frame it. How rough the feed is comes
 * from its {@link Signal}; without motion it shows settled, tinted and scanned, with no static, glitches or flicker.
 * The widget owns a shader: {@link #dispose()} it with its view.
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
    private static final float TUNE_SECONDS = .65f;
    /** Seconds the interference bar takes to drift down the picture. */
    private static final float BAR_SECONDS = 14;
    /** The snow's frames a second while tuning in. */
    private static final float SNOW_RATE = 30;

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
          uniform vec4 u_live;
          uniform float u_snow;
          float hash(vec2 p) {
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
          }
          void main() {
              vec2 span = u_region.zw - u_region.xy;
              vec2 local = (v_texCoords - u_region.xy) / span;
              float py = local.y * u_size.y;
              float settle = 1.0 - u_boot;
              float tuning = step(0.001, settle);
              float ly = tuning > 0.0 ? fract(local.y + settle * settle * 0.8) : local.y;
              float band = step(u_glitch.x, py) * step(py, u_glitch.y);
              float shear = settle * settle * (hash(vec2(floor(py / 3.0), u_snow)) - 0.5) * 0.12;
              vec2 lp = vec2(local.x + band * u_glitch.z / u_size.x + shear, ly);
              float inside = step(0.0, lp.x) * step(lp.x, 1.0);
              float split = (u_live.z * 1.5 + settle * 6.0) / u_size.x;
              vec4 art = texture2D(u_texture, u_region.xy + lp * span);
              art.r = texture2D(u_texture, u_region.xy + vec2(clamp(lp.x + split, 0.0, 1.0), lp.y) * span).r;
              art.b = texture2D(u_texture, u_region.xy + vec2(clamp(lp.x - split, 0.0, 1.0), lp.y) * span).b;
              art = art * inside + vec4(0.0, 0.0, 0.0, 1.0 - inside) * band;
              float luma = dot(art.rgb, vec3(0.299, 0.587, 0.114));
              vec3 colour = mix(art.rgb, u_tint.rgb, u_tint.a);
              colour = mix(colour, u_tint.rgb * (0.25 + luma * 1.5), settle * 0.7);
              colour *= 1.0 + u_live.y * 0.18;
              float drift = local.y - u_live.x;
              drift -= floor(drift + 0.5);
              colour += u_tint.rgb * exp(-drift * drift / 0.004) * (0.05 + 0.1 * u_live.z) * u_live.w;
              float line = step(u_scan.x - 1.0, mod(py, u_scan.x));
              colour *= 1.0 - line * u_scan.y;
              vec2 cell = floor(vec2(local.x * u_size.x / 6.0, py / 2.0));
              float fleck = step(1.0 - u_noise.x, hash(cell + vec2(u_noise.z, u_noise.z * 0.37)));
              colour = mix(colour, mix(u_tint.rgb, vec3(1.0), 0.5), fleck * u_noise.y);
              float edge = 1.0 - step(1.0, abs(py - u_glitch.x));
              colour = mix(colour, u_line, edge * u_glitch.w);
              float snow = 1.0 - smoothstep(0.1, 0.8, u_boot);
              float grain = hash(floor(vec2(local.x * u_size.x, py) / 2.0) + vec2(u_snow * 1.7, u_snow * 0.61));
              colour = mix(colour, grain * mix(vec3(1.0), u_tint.rgb + 0.3, 0.4), snow);
              colour *= 1.0 - (1.0 - smoothstep(0.0, 0.06, ly)) * tuning * 0.85;
              vec2 face = (local - 0.5) * vec2(1.0, 0.85);
              colour *= 1.0 - smoothstep(0.35, 0.75, length(face)) * (0.35 + 0.15 * u_live.z);
              float alpha = max(art.a, snow) * u_flicker;
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
    private float bar;
    private float snowTime;
    private int snowFrame;
    private float voice;

    /**
     * A feed of {@code picture}, preferring {@code width} by {@code height} stage units, tinted {@code tint} with glitch
     * lines in {@code line}; {@code white} is a solid white region for the brackets. It tunes in once it is on stage,
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
        bar = random.nextFloat();
    }

    /** Starts tuning in over, as when the channel opens again. */
    public void reboot() {
        boot = motion ? 0 : 1;
    }

    /** Ends tuning in at once, as when the player skips the channel opening. */
    public void settle() {
        boot = 1;
        flicker = 1;
    }

    /** Whether the feed has tuned in. */
    public boolean booted() {
        return boot >= 1;
    }

    /** How loud the speaker is now, 0 (quiet) to 1; the picture brightens with it. Ignored without motion. */
    public void voice(float level) {
        voice = motion ? Math.max(0, Math.min(1, level)) : 0;
    }

    /**
     * How rough the signal is overall, 0 (clean) to 1 (barely holding): the static and the glitches together. It sets
     * how strongly the interference bar shows and how far the colors split.
     */
    public float roughness() {
        return Math.max(0, Math.min(1, signal.noiseDensity() * 8 + signal.glitchChance() * 1.5f));
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!motion) {
            return;
        }
        boot = Math.min(1, boot + delta / TUNE_SECONDS);
        bar = (bar + delta / BAR_SECONDS) % 1;
        snowTime += delta;
        if (snowTime >= 1 / SNOW_RATE) {
            snowTime %= 1 / SNOW_RATE;
            snowFrame = (snowFrame + 1) % 977;
        }
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
            program.setUniformf("u_live", bar, voice, roughness(), live ? 1 : 0);
            program.setUniformf("u_snow", snowFrame);
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

    /** Whether the feed's shader failed to compile, so the picture shows plain. */
    boolean plain() {
        return failed;
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
