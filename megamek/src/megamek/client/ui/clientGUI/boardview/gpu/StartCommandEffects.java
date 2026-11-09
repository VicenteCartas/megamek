/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * A command post's start screen effects ({@link StartScreenContent.Ambience#COMMAND}). The intro draws a star map that
 * charts itself behind the greeting; the view dives into it and the art boots up out of it as a hologram. Once
 * settled, soft light shafts stream from the brightest warm opening in the art through drifting haze, and the art's
 * teal screens show a faint rolling scan line and the odd contact. Both find their places in the art itself, so any
 * art works.
 *
 * <p>GPU thread only. When a shader does not compile, {@link #ready} is false and the screen shows the art plainly.</p>
 */
final class StartCommandEffects implements Disposable {
    /** The masks are this many times smaller than the window; the shaders sample them smoothly. */
    private static final int MASK_DIVISOR = 4;
    private static final int RAY_SAMPLES = 48;

    private static final String BATCH_VERTEX = """
          attribute vec4 a_position;
          attribute vec4 a_color;
          attribute vec2 a_texCoord0;
          uniform mat4 u_projTrans;
          varying vec2 v_texCoords;
          void main() {
              v_texCoords = a_texCoord0;
              gl_Position = u_projTrans * a_position;
          }
          """;

    private static final String NOISE = """
          float hash(vec2 p) {
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
          }
          float noise(vec2 p) {
              vec2 i = floor(p);
              vec2 f = fract(p);
              f = f * f * (3.0 - 2.0 * f);
              return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
          }
          """;

    /**
     * Light scattered from the mask's warm light (red) towards each pixel along the line from the light's origin,
     * stirred by haze and dimmed where the screen shades the art (blue). The opening itself gets none: it is lit
     * already, and adding to it would blow it out into a star.
     */
    private static final String RAYS_FRAGMENT = """
          uniform sampler2D u_texture;
          uniform vec2 u_origin;
          uniform float u_time;
          uniform float u_strength;
          varying vec2 v_texCoords;
          """ + NOISE + """
          void main() {
              vec2 uv = v_texCoords;
              vec2 march = (uv - u_origin) * (0.85 / float(%1$d));
              vec2 p = uv;
              float light = 0.0;
              float decay = 1.0;
              for (int i = 0; i < %1$d; i++) {
                  p -= march;
                  float haze = 0.5 + 0.5 * noise(p * 7.0 + vec2(u_time * 0.04, -u_time * 0.025));
                  light += texture2D(u_texture, p).r * haze * decay;
                  decay *= 0.965;
              }
              light /= float(%1$d);
              vec4 here = texture2D(u_texture, uv);
              float outside = 1.0 - smoothstep(0.05, 0.6, here.r);
              gl_FragColor = vec4(1.0, 0.86, 0.62, clamp(light * outside * here.b * u_strength, 0.0, 0.3));
          }
          """.formatted(RAY_SAMPLES);

    /**
     * The art's screens (green) left as painted but for a faint line rolling down each at its own time (alpha holds each
     * screen's phase), as a display refreshes, and now and then a hex contact that pings with a hexagonal ripple, as on
     * a hex map.
     */
    private static final String MONITORS_FRAGMENT = """
          uniform sampler2D u_texture;
          uniform float u_time;
          uniform float u_strength;
          uniform vec2 u_cells;
          varying vec2 v_texCoords;
          """ + NOISE + """
          float hexDistance(vec2 p) {
              p = abs(p);
              return max(dot(p, vec2(0.8660254, 0.5)), p.y);
          }
          void main() {
              vec4 mask = texture2D(u_texture, v_texCoords);
              if (mask.g < 0.02) {
                  discard;
              }
              float roll = fract(v_texCoords.y * 3.0 - u_time * 0.08 + mask.a);
              float scan = (1.0 - smoothstep(0.0, 0.012, abs(roll - 0.5))) * 0.14
                    + smoothstep(0.3, 0.5, roll) * step(roll, 0.5) * 0.03;
              vec2 cellUv = v_texCoords * u_cells;
              vec2 cell = floor(cellUv);
              float seed = hash(cell);
              float blip = 0.0;
              if (seed > 0.86) {
                  vec2 spot = vec2(hash(cell + 17.0), hash(cell + 31.0)) * 0.16 + 0.42;
                  float phase = fract(u_time / (3.0 + 3.0 * seed) + seed * 7.0);
                  float d = hexDistance(cellUv - cell - spot);
                  float contact = (1.0 - smoothstep(0.0, 0.5, phase)) * (1.0 - smoothstep(0.1, 0.145, d));
                  float reach = 0.09 + 0.24 * smoothstep(0.0, 0.7, phase);
                  float ripple = (1.0 - smoothstep(0.0, 0.035, abs(d - reach))) * (1.0 - smoothstep(0.1, 0.7, phase));
                  blip = contact * 0.9 + ripple * 0.5;
              }
              gl_FragColor = vec4(0.6, 1.0, 0.94, clamp(mask.g * (scan + blip) * u_strength, 0.0, 0.7));
          }
          """;

    /**
     * The art boots up as a hologram: a line of light opens into a flickering, torn, colour-split projection that
     * settles into the art.
     */
    private static final String HOLOGRAM_FRAGMENT = """
          uniform sampler2D u_texture;
          uniform float u_progress;
          uniform float u_time;
          uniform float u_rows;
          uniform vec3 u_tint;
          varying vec2 v_texCoords;
          """ + NOISE + """
          void main() {
              float p = u_progress;
              float settle = 1.0 - p;
              float open = smoothstep(0.0, 0.3, p);
              float gap = open - abs(v_texCoords.y - 0.5) * 2.0;
              if (gap < -0.004) {
                  discard;
              }
              float frame = floor(u_time * 24.0);
              float row = floor(v_texCoords.y * u_rows / 3.0);
              float tear = step(0.93, hash(vec2(floor(v_texCoords.y * 18.0), frame)));
              float shift = (hash(vec2(row, frame)) - 0.5) * 0.012
                    + tear * (hash(vec2(frame, row * 0.1)) - 0.5) * 0.08;
              vec2 uv = v_texCoords + vec2(shift * settle * settle, 0.0);
              float split = 0.008 * settle;
              vec3 art = vec3(texture2D(u_texture, uv + vec2(split, 0.0)).r, texture2D(u_texture, uv).g,
                    texture2D(u_texture, uv - vec2(split, 0.0)).b);
              float tinted = pow(settle, 1.5);
              vec3 holo = u_tint * (0.25 + dot(art, vec3(0.3, 0.59, 0.11)) * 1.5);
              vec3 colour = mix(art, holo, tinted * 0.85);
              float lines = 0.5 + 0.5 * sin(v_texCoords.y * u_rows * 1.4);
              colour *= 1.0 - 0.4 * tinted * lines;
              float rim = 1.0 - smoothstep(0.0, 0.006, abs(gap));
              colour += u_tint * rim * (1.0 - smoothstep(0.25, 0.4, p)) * 1.5;
              colour += u_tint * 0.12 * settle * noise(v_texCoords * vec2(4.0, 40.0) + vec2(0.0, u_time * 3.0));
              float flicker = mix(0.55 + 0.45 * step(0.2, hash(vec2(frame, 3.0))), 1.0, smoothstep(0.35, 0.8, p));
              float alpha = smoothstep(-0.004, 0.0, gap) * flicker * mix(0.75, 1.0, smoothstep(0.3, 0.9, p));
              gl_FragColor = vec4(colour, clamp(alpha, 0.0, 1.0));
          }
          """;

    private static final String STARS_VERTEX = """
          attribute vec3 a_position;
          attribute vec2 a_texCoord0;
          attribute float a_when;
          attribute float a_kind;
          attribute vec3 a_tone;
          attribute float a_size;
          uniform mat4 u_view;
          uniform mat4 u_proj;
          uniform vec2 u_far;
          varying vec2 v_uv;
          varying float v_when;
          varying float v_kind;
          varying vec3 v_tone;
          varying float v_near;
          void main() {
              v_uv = a_texCoord0;
              v_when = a_when;
              v_kind = a_kind;
              v_tone = a_tone;
              vec4 view = u_view * vec4(a_position, 1.0);
              float d = length(view.xyz);
              v_near = smoothstep(0.08, 0.5, d) * (1.0 - smoothstep(u_far.x, u_far.y, d));
              view.xy += (a_texCoord0 * 2.0 - 1.0) * a_size;
              gl_Position = u_proj * view;
          }
          """;

    /**
     * Star systems light up and jump routes draw out from them, with a bright head where each is growing; a faint haze
     * of each system's colour tints the space its owner holds.
     */
    private static final String STARS_FRAGMENT = """
          uniform float u_build;
          uniform float u_fade;
          varying vec2 v_uv;
          varying float v_when;
          varying float v_kind;
          varying vec3 v_tone;
          varying float v_near;
          void main() {
              float since = u_build - v_when;
              if (since < 0.0) {
                  discard;
              }
              float fresh = 1.0 - smoothstep(0.0, 0.06, since);
              vec2 d = v_uv * 2.0 - 1.0;
              float r2 = dot(d, d);
              float a;
              if (v_kind > 1.5) {
                  a = exp(-r2 * 4.0) * 0.07 * smoothstep(0.0, 0.15, since);
                  fresh = 0.0;
              } else if (v_kind > 0.5) {
                  a = (exp(-r2 * 9.0) + 0.35 * exp(-r2 * 2.5)) * (0.8 + 1.5 * fresh);
              } else {
                  float across = 1.0 - abs(v_uv.y * 2.0 - 1.0);
                  a = across * across * 0.35 * (1.0 + 3.0 * fresh);
              }
              gl_FragColor = vec4(v_tone + fresh * 0.4, clamp(a * v_near * u_fade, 0.0, 1.0));
          }
          """;

    /** When the star map starts drawing itself, in seconds into the intro, and how long it takes. */
    private static final double MAP_START = 0.3;
    private static final double MAP_BUILD = 2.2;
    private static final int MAP_STARS = 150;
    /**
     * Position (3), across or around (2), when it lights (1), whether a route, a star or a haze (1), its colour (3) and
     * how far a star's glow reaches either way from its centre (1).
     */
    private static final int STAR_FLOATS = 11;
    /** Light years from Terra the charted map reaches, how many to a unit of the map, and how far a jump goes. */
    private static final double CHART_REACH = 1300;
    private static final double CHART_SCALE = 220;
    private static final double JUMP = 30;
    /** How far a charted system's haze spreads, in light years. */
    private static final double HAZE = 40;
    /**
     * Star systems at {@code points} (x, y, z each), when each lights from 0 to 1, routes as pairs of indices, each
     * system's colour (r, g, b each, or null for the tint) and the one the view dives into; a {@code planar} map lies
     * flat as a chart does.
     */
    record StarMap(float[] points, float[] born, int[] routes, float[] colours, int focus,
          boolean planar) { }

    private final float[] edge;
    private final PerspectiveCamera mapCamera = new PerspectiveCamera();
    private ShaderProgram rays;
    private ShaderProgram monitors;
    private ShaderProgram hologram;
    private ShaderProgram stars;
    private Mesh starMesh;
    private StarMap shownMap;
    private StartScreenContent.StarChart chartTried;
    private Texture mask;
    private boolean hasLight;
    private float lightU;
    private float lightV;
    private int bw;
    private int bh;

    /** {@code edge} tints the hologram's glow and the star map where it has no colours of its own. */
    StartCommandEffects(java.awt.Color edge) {
        this.edge = new float[] { edge.getRed() / 255f, edge.getGreen() / 255f, edge.getBlue() / 255f };
        rays = compile(BATCH_VERTEX, RAYS_FRAGMENT);
        monitors = compile(BATCH_VERTEX, MONITORS_FRAGMENT);
        hologram = compile(BATCH_VERTEX, HOLOGRAM_FRAGMENT);
        stars = compile(STARS_VERTEX, STARS_FRAGMENT);
    }

    private static ShaderProgram compile(String vertex, String fragment) {
        ShaderProgram program = new ShaderProgram(vertex, fragment);
        if (!program.isCompiled()) {
            GpuStartScreen.LOGGER.warn("A start screen effect's shader did not compile: {}", program.getLog());
            program.dispose();
            return null;
        }
        return program;
    }

    /** Whether the effects can draw; when not, the screen shows the art plainly. */
    boolean ready() {
        return rays != null && monitors != null && hologram != null
              && stars != null;
    }

    /** After the window's size changes. */
    void resize(int width, int height) {
        bw = width;
        bh = height;
        mapCamera.fieldOfView = 50;
        mapCamera.viewportWidth = width;
        mapCamera.viewportHeight = height;
        mapCamera.near = 0.02f;
        mapCamera.far = 30;
    }

    /**
     * Finds the light and the screens in {@code art}, the art as drawn on the screen at a smaller size, and how much the
     * screen shades it from {@code shading}, white shaded the same way at the same size.
     */
    void findLights(BufferedImage art, BufferedImage shading) {
        int width = art.getWidth();
        int height = art.getHeight();
        float[] light = new float[width * height];
        float[] screen = new float[width * height];
        float[] shade = new float[width * height];
        float[] seen = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int rgb = art.getRGB(x, y);
                light[i] = warmLight(rgb);
                screen[i] = screenGlow(rgb);
                shade[i] = luminance(shading.getRGB(x, y));
                seen[i] = light[i] * shade[i];
            }
        }
        Opening opening = opening(seen, width, height);
        hasLight = opening != null;
        if (hasLight) {
            lightU = (float) opening.u();
            lightV = (float) opening.v();
        }
        int[] screenNumbers = screens(screen, width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                // Only the opening the shafts stream from lights them; others would cast shafts from the wrong place
                float lit = hasLight && opening.inside()[i] ? light[i] : 0;
                float phase = screenNumbers[i] == 0 ? 0 : screenPhase(screenNumbers[i]);
                image.setRGB(x, y, (byteOf(phase) << 24) | (byteOf(lit) << 16) | (byteOf(screen[i]) << 8)
                      | byteOf(shade[i]));
            }
        }
        if (mask != null) {
            mask.dispose();
        }
        mask = GpuStartScreen.texture(image, false);
    }

    /** The brightest opening the light comes through: which pixels it covers and its centre (fractions, from the top). */
    record Opening(boolean[] inside, double u, double v) { }

    /**
     * The opening the most {@code light} (0 to 1 a pixel, rows from the top) shows through: the bright region around the
     * peak of the light blurred, so that the opening counts rather than a speck, with its light's centre. Null when there
     * is too little light to stream shafts from.
     */
    static Opening opening(float[] light, int width, int height) {
        double total = 0;
        for (float value : light) {
            total += value;
        }
        if (total < width * height * 0.003) {
            return null;
        }
        int radius = Math.max(1, Math.round(width * 0.03f));
        float[] blurred = light.clone();
        for (int pass = 0; pass < 3; pass++) {
            blurred = boxBlur(blurred, width, height, radius, true);
            blurred = boxBlur(blurred, width, height, radius, false);
        }
        int peak = 0;
        for (int i = 1; i < blurred.length; i++) {
            if (blurred[i] > blurred[peak]) {
                peak = i;
            }
        }
        float threshold = blurred[peak] * 0.3f;
        int[] labels = new int[light.length];
        fill(blurred, width, height, peak, threshold, labels, 1, new int[light.length]);
        boolean[] inside = new boolean[light.length];
        double weight = 0;
        double sumU = 0;
        double sumV = 0;
        for (int i = 0; i < inside.length; i++) {
            inside[i] = labels[i] == 1;
            if (inside[i]) {
                weight += light[i];
                sumU += light[i] * (i % width + 0.5) / width;
                sumV += light[i] * (i / width + 0.5) / height;
            }
        }
        return weight <= 0 ? null : new Opening(inside, sumU / weight, sumV / weight);
    }

    /**
     * Each screen in {@code glow} (0 to 1 a pixel, rows from the top) numbered from 1, a little beyond its edges so that
     * a screen's dark map does not split it; 0 where there is none.
     */
    static int[] screens(float[] glow, int width, int height) {
        int radius = Math.max(1, Math.round(width * 0.006f));
        float[] blurred = boxBlur(boxBlur(glow, width, height, radius, true), width, height, radius, false);
        int[] screens = new int[glow.length];
        int[] queue = new int[glow.length];
        int count = 0;
        for (int i = 0; i < glow.length; i++) {
            if (screens[i] == 0 && blurred[i] >= 0.05f) {
                fill(blurred, width, height, i, 0.05f, screens, ++count, queue);
            }
        }
        return screens;
    }

    /** When screen {@code number}'s scan line passes, as a fraction of its period: spread well apart for each screen. */
    static float screenPhase(int number) {
        double phase = number * 0.6180339887;
        return (float) (phase - Math.floor(phase));
    }

    /**
     * Marks {@code label} in {@code labels} on the pixels joined to {@code seed}, side by side, where {@code values}
     * reach {@code threshold}; {@code queue} is scratch as long as the image.
     */
    private static void fill(float[] values, int width, int height, int seed, float threshold, int[] labels, int label,
          int[] queue) {
        int head = 0;
        int tail = 0;
        labels[seed] = label;
        queue[tail++] = seed;
        while (head < tail) {
            int i = queue[head++];
            int x = i % width;
            int y = i / width;
            int[] neighbours = { x > 0 ? i - 1 : -1, x < width - 1 ? i + 1 : -1, y > 0 ? i - width : -1,
                                 y < height - 1 ? i + width : -1 };
            for (int next : neighbours) {
                if (next >= 0 && labels[next] == 0 && values[next] >= threshold) {
                    labels[next] = label;
                    queue[tail++] = next;
                }
            }
        }
    }

    /** One pass of a box blur {@code radius} pixels each way, along rows or columns, clamped at the edges. */
    private static float[] boxBlur(float[] in, int width, int height, int radius, boolean rows) {
        float[] out = new float[in.length];
        int lines = rows ? height : width;
        int length = rows ? width : height;
        int stride = rows ? 1 : width;
        for (int line = 0; line < lines; line++) {
            int first = rows ? line * width : line;
            float sum = 0;
            for (int k = -radius; k <= radius; k++) {
                sum += in[first + Math.max(0, Math.min(length - 1, k)) * stride];
            }
            for (int k = 0; k < length; k++) {
                out[first + k * stride] = sum / (2 * radius + 1);
                sum += in[first + Math.min(length - 1, k + radius + 1) * stride];
                sum -= in[first + Math.max(0, k - radius) * stride];
            }
        }
        return out;
    }

    /** The size the masks are drawn at for a window {@code width} by {@code height} pixels. */
    static int maskSize(int pixels) {
        return Math.max(1, pixels / MASK_DIVISOR);
    }

    /** How much a pixel is warm light, such as sunlit haze through a door: bright and redder than it is blue. */
    static float warmLight(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        return smoothstep(0.5f, 0.85f, luminance(rgb)) * smoothstep(0.02f, 0.15f, r - b);
    }

    /** How much a pixel is a lit screen: teal, its green and blue well above its red, and bright enough. */
    static float screenGlow(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        return smoothstep(0.06f, 0.2f, Math.min(g, b) - r) * smoothstep(0.25f, 0.55f, Math.max(g, b));
    }

    /** Draws the art booting up as a hologram, {@code progress} of the way through the reveal. */
    void drawHologram(SpriteBatch batch, Texture backdrop, double progress, float time) {
        batch.setShader(hologram);
        batch.begin();
        hologram.setUniformf("u_progress", (float) Math.max(0, Math.min(1, progress)));
        hologram.setUniformf("u_time", time);
        hologram.setUniformf("u_rows", bh);
        hologram.setUniformf("u_tint", edge[0], edge[1], edge[2]);
        batch.setColor(1, 1, 1, 1);
        batch.draw(backdrop, 0, 0, bw, bh);
        batch.end();
        batch.setShader(null);
    }

    /**
     * Draws the star map {@code t} seconds into the intro: it draws itself out behind the greeting, then the view dives
     * into it during the reveal as it fades. The map is {@code chart} once it has come, if it has any systems near
     * Terra, and a made-up network of jump routes until then.
     */
    void drawStarMap(double t, StartScreenContent.StarChart chart) {
        float build = (float) Math.max(0, Math.min(1, (t - MAP_START) / MAP_BUILD));
        if (build <= 0) {
            return;
        }
        if (chart != null && chart != chartTried && (starMesh == null || build < 0.05f)) {
            chartTried = chart;
            StarMap charted = chartMap(chart);
            if (charted != null) {
                show(charted);
            }
        }
        if (starMesh == null) {
            show(starMap(3025L, MAP_STARS));
        }
        double dive = Math.max(0, Math.min(1, (t - StartTimeline.REVEAL_START) / StartTimeline.REVEAL_DURATION));
        double rush = dive * dive;
        if (shownMap.planar()) {
            aimAtChart(build, dive, rush);
        } else {
            aimIntoNetwork(t, dive, rush);
        }
        float fade = 1 - smoothstep(0.55f, 1f, (float) dive);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        stars.bind();
        stars.setUniformMatrix("u_view", mapCamera.view);
        stars.setUniformMatrix("u_proj", mapCamera.projection);
        stars.setUniformf("u_far", shownMap.planar() ? 6f : 5f, shownMap.planar() ? 11f : 9f);
        stars.setUniformf("u_build", build);
        stars.setUniformf("u_fade", fade);
        starMesh.render(stars, GL20.GL_TRIANGLES);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void show(StarMap map) {
        if (starMesh != null) {
            starMesh.dispose();
        }
        shownMap = map;
        starMesh = starMesh(map, edge);
    }

    /** Looks down on the chart at a slant, gliding from Terra towards the focus, then dives straight into it. */
    private void aimAtChart(float build, double dive, double rush) {
        float[] p = shownMap.points();
        int focus = shownMap.focus();
        float glide = (float) StartTimeline.ease(build);
        float targetX = p[focus * 3] * (0.3f + 0.7f * glide);
        float targetY = p[focus * 3 + 1] * (0.3f + 0.7f * glide);
        double angle = -0.3 + 0.3 * glide;
        double distance = (5.2 - 2.0 * glide) * (1 - 0.96 * rush);
        double tilt = (0.45 + 0.2 * glide) * (1 - dive);
        float across = (float) (distance * Math.sin(tilt));
        mapCamera.position.set(targetX + (float) Math.sin(angle) * across, targetY - (float) Math.cos(angle) * across,
              (float) (distance * Math.cos(tilt)));
        mapCamera.direction.set(targetX, targetY, 0).sub(mapCamera.position).nor();
        double roll = angle + 0.35 * rush;
        mapCamera.up.set((float) -Math.sin(roll), (float) Math.cos(roll), 0);
        mapCamera.update();
    }

    /** Drifts before the network, then rushes through it with a roll. */
    private void aimIntoNetwork(double t, double dive, double rush) {
        float drift = (float) (1 - dive);
        float x = (float) (0.25 * Math.sin(t * 0.35)) * drift;
        float y = (float) (0.12 * Math.sin(t * 0.27)) * drift;
        float roll = (float) (0.35 * rush);
        mapCamera.position.set(x, y, (float) (2.4 - 0.12 * t - 5.2 * rush));
        mapCamera.direction.set(-x * 0.3f, -y * 0.3f, -1).nor();
        mapCamera.up.set((float) Math.sin(roll), (float) Math.cos(roll), 0);
        mapCamera.update();
    }

    /**
     * The star map as quads: a glow facing the view for each star, a thin strip for each route and, on a chart, a haze
     * of each held system's colour, lit when they are. {@code tint} colours what the map does not.
     */
    private static Mesh starMesh(StarMap map, float[] tint) {
        float[] p = map.points();
        float[] born = map.born();
        int[] routes = map.routes();
        float[] data = new float[(born.length * 2 + routes.length / 2) * 6 * STAR_FLOATS];
        int n = 0;
        if (map.planar()) {
            float h = (float) (HAZE / CHART_SCALE);
            for (int i = 0; i < born.length; i++) {
                float[] tone = tone(map, i, tint);
                if (held(tone)) {
                    float x = p[i * 3];
                    float y = p[i * 3 + 1];
                    float[][] corners = { { x - h, y - h, 0, 0, 0, born[i] }, { x + h, y - h, 0, 1, 0, born[i] },
                                          { x + h, y + h, 0, 1, 1, born[i] }, { x - h, y + h, 0, 0, 1, born[i] } };
                    n = starQuad(data, n, corners, 2, tone, 0);
                }
            }
        }
        for (int i = 0; i < born.length; i++) {
            float s;
            if (map.planar()) {
                s = i == map.focus() ? 0.035f : 0.012f;
            } else {
                s = i == 0 ? 0.07f : 0.03f + 0.002f * (i * 37 % 10);
            }
            float x = p[i * 3];
            float y = p[i * 3 + 1];
            float z = p[i * 3 + 2];
            float[][] corners = { { x, y, z, 0, 0, born[i] }, { x, y, z, 1, 0, born[i] },
                                  { x, y, z, 1, 1, born[i] }, { x, y, z, 0, 1, born[i] } };
            n = starQuad(data, n, corners, 1, tone(map, i, tint), s);
        }
        float width = 0.0035f;
        float[] border = { 0.3f, 0.3f, 0.3f };
        for (int k = 0; k < routes.length; k += 2) {
            int a = routes[k];
            int b = routes[k + 1];
            float[] tone = tone(map, a, tint);
            if (map.planar()) {
                float[] other = tone(map, b, tint);
                tone = Arrays.equals(tone, other) ? new float[] { tone[0] * 0.6f, tone[1] * 0.6f, tone[2] * 0.6f }
                      : border;
            }
            float dx = p[b * 3] - p[a * 3];
            float dy = p[b * 3 + 1] - p[a * 3 + 1];
            float length = (float) Math.hypot(dx, dy);
            float px = length > 1e-4f ? -dy / length * width : width;
            float py = length > 1e-4f ? dx / length * width : 0;
            float[][] corners = {
                  { p[a * 3] - px, p[a * 3 + 1] - py, p[a * 3 + 2], 0, 0, born[a] },
                  { p[b * 3] - px, p[b * 3 + 1] - py, p[b * 3 + 2], 1, 0, born[b] },
                  { p[b * 3] + px, p[b * 3 + 1] + py, p[b * 3 + 2], 1, 1, born[b] },
                  { p[a * 3] + px, p[a * 3 + 1] + py, p[a * 3 + 2], 0, 1, born[a] } };
            n = starQuad(data, n, corners, 0, tone, 0);
        }
        Mesh starMesh = new Mesh(true, n / STAR_FLOATS, 0, VertexAttribute.Position(), VertexAttribute.TexCoords(0),
              new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_when"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_kind"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_tone"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_size"));
        starMesh.setVertices(data, 0, n);
        return starMesh;
    }

    private static float[] tone(StarMap map, int i, float[] tint) {
        float[] colours = map.colours();
        return colours == null ? tint : Arrays.copyOfRange(colours, i * 3, i * 3 + 3);
    }

    /** Whether a system's colour is a power's, not the grey of one that no one holds. */
    static boolean held(float[] tone) {
        float max = Math.max(tone[0], Math.max(tone[1], tone[2]));
        float min = Math.min(tone[0], Math.min(tone[1], tone[2]));
        return max - min > 0.2f || max > 0.9f;
    }

    private static int starQuad(float[] data, int n, float[][] corners, float kind, float[] tone, float size) {
        for (int corner : new int[] { 0, 1, 2, 0, 2, 3 }) {
            System.arraycopy(corners[corner], 0, data, n, 6);
            data[n + 6] = kind;
            System.arraycopy(tone, 0, data, n + 7, 3);
            data[n + 10] = size;
            n += STAR_FLOATS;
        }
        return n;
    }

    /**
     * The systems of {@code chart} within reach of Terra as a flat map, each in its owner's colour and joined by routes
     * to its nearest neighbours a jump away, lighting outwards from Terra as humanity spread; null when none are near.
     */
    static StarMap chartMap(StartScreenContent.StarChart chart) {
        List<StartScreenContent.Star> kept = new ArrayList<>();
        for (StartScreenContent.Star star : chart.stars()) {
            if (Math.hypot(star.x(), star.y()) <= CHART_REACH) {
                kept.add(star);
            }
        }
        int count = kept.size();
        if (count < 2) {
            return null;
        }
        Random random = new Random(3025L);
        float[] points = new float[count * 3];
        float[] colours = new float[count * 3];
        float[] born = new float[count];
        Map<Long, List<Integer>> grid = new HashMap<>();
        int focus = 0;
        double closest = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            StartScreenContent.Star star = kept.get(i);
            points[i * 3] = (float) (star.x() / CHART_SCALE);
            points[i * 3 + 1] = (float) (star.y() / CHART_SCALE);
            float[] rgb = star.color().getRGBColorComponents(null);
            System.arraycopy(rgb, 0, colours, i * 3, 3);
            born[i] = (float) Math.min(1, Math.hypot(star.x(), star.y()) / CHART_REACH * 0.85
                  + random.nextFloat() * 0.1);
            double fromFocus = Math.hypot(star.x() - chart.focusX(), star.y() - chart.focusY());
            if (fromFocus < closest) {
                closest = fromFocus;
                focus = i;
            }
            grid.computeIfAbsent(gridKey(star.x(), star.y(), 0, 0), key -> new ArrayList<>()).add(i);
        }
        Set<Long> pairs = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            StartScreenContent.Star star = kept.get(i);
            List<Integer> near = new ArrayList<>();
            for (int gx = -1; gx <= 1; gx++) {
                for (int gy = -1; gy <= 1; gy++) {
                    for (int j : grid.getOrDefault(gridKey(star.x(), star.y(), gx, gy), List.of())) {
                        if (j != i && jump(kept, i, j) <= JUMP) {
                            near.add(j);
                        }
                    }
                }
            }
            int from = i;
            near.sort(Comparator.comparingDouble(j -> jump(kept, from, j)));
            for (int k = 0; k < Math.min(3, near.size()); k++) {
                pairs.add(pair(i, near.get(k), count));
            }
        }
        int[] routes = new int[pairs.size() * 2];
        int k = 0;
        for (long key : pairs) {
            routes[k++] = (int) (key / count);
            routes[k++] = (int) (key % count);
        }
        return new StarMap(points, born, routes, colours, focus, true);
    }

    private static long gridKey(double x, double y, int dx, int dy) {
        long gx = (long) Math.floor(x / JUMP) + dx;
        long gy = (long) Math.floor(y / JUMP) + dy;
        return (gx << 32) ^ (gy & 0xFFFFFFFFL);
    }

    private static double jump(List<StartScreenContent.Star> stars, int a, int b) {
        return Math.hypot(stars.get(a).x() - stars.get(b).x(), stars.get(a).y() - stars.get(b).y());
    }
    /**
     * A star map of {@code count} systems from {@code seed}: the first a hub in the middle, each joined by jump routes
     * to its nearest neighbours and all joined to the hub, lighting outwards from it a jump at a time.
     */
    static StarMap starMap(long seed, int count) {
        Random random = new Random(seed);
        float[] points = new float[count * 3];
        points[2] = -0.8f;
        for (int i = 1; i < count; i++) {
            points[i * 3] = (random.nextFloat() * 2 - 1) * 2.2f;
            points[i * 3 + 1] = (random.nextFloat() * 2 - 1) * 1.3f;
            points[i * 3 + 2] = 1 - random.nextFloat() * 5.5f;
        }
        Set<Long> pairs = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            Integer[] nearest = new Integer[count];
            for (int j = 0; j < count; j++) {
                nearest[j] = j;
            }
            int from = i;
            Arrays.sort(nearest, Comparator.comparingDouble(j -> distance2(points, from, j)));
            int links = random.nextFloat() < 0.35f ? 3 : 2;
            for (int k = 1; k <= links && k < count; k++) {
                pairs.add(pair(i, nearest[k], count));
            }
        }
        int[] depth = depths(pairs, count);
        while (true) {
            int bestFrom = -1;
            int bestTo = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    if (depth[i] >= 0 && depth[j] < 0 && distance2(points, i, j) < best) {
                        best = distance2(points, i, j);
                        bestFrom = i;
                        bestTo = j;
                    }
                }
            }
            if (bestTo < 0) {
                break;
            }
            pairs.add(pair(bestFrom, bestTo, count));
            depth = depths(pairs, count);
        }
        int deepest = 0;
        for (int d : depth) {
            deepest = Math.max(deepest, d);
        }
        float[] born = new float[count];
        for (int i = 1; i < count; i++) {
            born[i] = (depth[i] + random.nextFloat() * 0.6f) / (deepest + 1);
        }
        int[] routes = new int[pairs.size() * 2];
        int k = 0;
        for (long key : pairs) {
            routes[k++] = (int) (key / count);
            routes[k++] = (int) (key % count);
        }
        return new StarMap(points, born, routes, null, 0, false);
    }

    private static long pair(int a, int b, int count) {
        return (long) Math.min(a, b) * count + Math.max(a, b);
    }

    private static double distance2(float[] points, int a, int b) {
        double dx = points[a * 3] - points[b * 3];
        double dy = points[a * 3 + 1] - points[b * 3 + 1];
        double dz = points[a * 3 + 2] - points[b * 3 + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** How many jumps each system is from the hub along {@code pairs}; -1 where it cannot be reached. */
    private static int[] depths(Set<Long> pairs, int count) {
        List<List<Integer>> neighbours = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            neighbours.add(new ArrayList<>());
        }
        for (long key : pairs) {
            int a = (int) (key / count);
            int b = (int) (key % count);
            neighbours.get(a).add(b);
            neighbours.get(b).add(a);
        }
        int[] depth = new int[count];
        Arrays.fill(depth, -1);
        depth[0] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(0));
        while (!queue.isEmpty()) {
            int i = queue.poll();
            for (int next : neighbours.get(i)) {
                if (depth[next] < 0) {
                    depth[next] = depth[i] + 1;
                    queue.add(next);
                }
            }
        }
        return depth;
    }

    /**
     * Draws the light shafts and the live screens over the art at {@code strength} (0 to 1), {@code time} seconds into
     * their motion; a still time holds them still.
     */
    void drawAmbience(SpriteBatch batch, float time, float strength, float scale) {
        if (mask == null || strength <= 0) {
            return;
        }
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        if (hasLight) {
            batch.setShader(rays);
            batch.begin();
            rays.setUniformf("u_origin", lightU, lightV);
            rays.setUniformf("u_time", time);
            rays.setUniformf("u_strength", 1.6f * strength);
            batch.draw(mask, 0, 0, bw, bh);
            batch.end();
        }
        batch.setShader(monitors);
        batch.begin();
        monitors.setUniformf("u_time", time);
        monitors.setUniformf("u_strength", strength);
        monitors.setUniformf("u_cells", bw / (16 * scale), bh / (16 * scale));
        batch.draw(mask, 0, 0, bw, bh);
        batch.end();
        batch.setShader(null);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static float luminance(int rgb) {
        return (0.3f * ((rgb >> 16) & 0xFF) + 0.59f * ((rgb >> 8) & 0xFF) + 0.11f * (rgb & 0xFF)) / 255f;
    }

    private static float smoothstep(float from, float to, float x) {
        float t = Math.max(0, Math.min(1, (x - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    @Override
    public void dispose() {
        for (Disposable disposable : new Disposable[] { rays, monitors, hologram, stars, starMesh,
                                                        mask }) {
            if (disposable != null) {
                disposable.dispose();
            }
        }
        rays = null;
        monitors = null;
        hologram = null;
        stars = null;
        starMesh = null;
        shownMap = null;
        chartTried = null;
        mask = null;
    }
}
