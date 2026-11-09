/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.List;

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
 * A command post's start screen effects ({@link StartScreenContent.Ambience#COMMAND}). The art arrives as a departures
 * board: its tiles flip over in perspective, cascading from the top left, and land with a little overshoot. Once
 * settled, soft light shafts stream from the brightest warm opening in the art through drifting haze, and the art's teal
 * screens show a faint rolling scan line and the odd blip. Both find their places in the art itself, so any art works.
 *
 * <p>GPU thread only. When a shader does not compile, {@link #ready} is false and the screen shows the art plainly.</p>
 */
final class StartCommandEffects implements Disposable {
    /** The fraction of the reveal one tile takes to flip. */
    static final double FLIP_SPAN = 0.3;
    /** The fraction of the reveal the tiles' backs take to fade in before the first flips. */
    static final double BACKS_IN = 0.12;
    /** The masks are this many times smaller than the window; the shaders sample them smoothly. */
    private static final int MASK_DIVISOR = 4;
    /** Position (3), art or tile coordinates (2), colour (4) and which face shows (1). */
    private static final int FLOATS = 10;
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
              return max(dot(p, vec2(0.5, 0.8660254)), p.y);
          }
          void main() {
              vec4 mask = texture2D(u_texture, v_texCoords);
              if (mask.g < 0.02) {
                  discard;
              }
              float roll = fract(v_texCoords.y * 8.0 - u_time * 0.1 + mask.a * 8.0);
              float scan = (1.0 - smoothstep(0.0, 0.012, abs(roll - 0.5))) * 0.14
                    + smoothstep(0.3, 0.5, roll) * step(roll, 0.5) * 0.03;
              vec2 cellUv = v_texCoords * u_cells;
              vec2 cell = floor(cellUv);
              float seed = hash(cell);
              float blip = 0.0;
              if (seed > 0.84) {
                  vec2 spot = vec2(hash(cell + 17.0), hash(cell + 31.0)) * 0.3 + 0.35;
                  float phase = fract(u_time / (3.0 + 3.0 * seed) + seed * 7.0);
                  float d = hexDistance(cellUv - cell - spot);
                  float contact = (1.0 - smoothstep(0.0, 0.5, phase)) * (1.0 - smoothstep(0.07, 0.1, d));
                  float reach = 0.06 + 0.24 * smoothstep(0.0, 0.7, phase);
                  float ripple = (1.0 - smoothstep(0.0, 0.025, abs(d - reach))) * (1.0 - smoothstep(0.1, 0.7, phase));
                  blip = contact * 0.9 + ripple * 0.5;
              }
              gl_FragColor = vec4(0.6, 1.0, 0.94, clamp(mask.g * (scan + blip) * u_strength, 0.0, 0.7));
          }
          """;

    private static final String FLIP_VERTEX = """
          attribute vec3 a_position;
          attribute vec2 a_texCoord0;
          attribute vec4 a_color;
          attribute float a_face;
          uniform mat4 u_projTrans;
          varying vec2 v_uv;
          varying vec4 v_color;
          varying float v_face;
          void main() {
              v_uv = a_texCoord0;
              v_color = a_color;
              v_face = a_face;
              gl_Position = u_projTrans * vec4(a_position, 1.0);
          }
          """;

    /** A tile's face shows its piece of the art; its back is a dark flap with a lit edge and a centre seam. */
    private static final String FLIP_FRAGMENT = """
          uniform sampler2D u_texture;
          uniform vec3 u_edge;
          varying vec2 v_uv;
          varying vec4 v_color;
          varying float v_face;
          void main() {
              if (v_face > 0.5) {
                  gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb * v_color.rgb, v_color.a);
              } else {
                  vec2 d = min(v_uv, 1.0 - v_uv);
                  float edge = 1.0 - smoothstep(0.0, 0.025, min(d.x, d.y));
                  float seam = 1.0 - smoothstep(0.0, 0.01, abs(v_uv.y - 0.5));
                  gl_FragColor = vec4(v_color.rgb + u_edge * (edge * 0.35 + seam * 0.12), v_color.a);
              }
          }
          """;

    private final float[] edge;
    private final float[] back;
    private final PerspectiveCamera camera = new PerspectiveCamera();
    private ShaderProgram rays;
    private ShaderProgram monitors;
    private ShaderProgram flip;
    private Mesh mesh;
    private float[] vertices = new float[0];
    private Texture mask;
    private boolean hasLight;
    private float lightU;
    private float lightV;
    private int bw;
    private int bh;

    /** {@code edge} lights the flaps' edges; {@code base} is the screen's dark field, the flaps' backs a shade above. */
    StartCommandEffects(java.awt.Color edge, java.awt.Color base) {
        this.edge = new float[] { edge.getRed() / 255f, edge.getGreen() / 255f, edge.getBlue() / 255f };
        back = new float[] { Math.min(1, base.getRed() / 255f * 1.6f + 0.04f),
                             Math.min(1, base.getGreen() / 255f * 1.6f + 0.04f),
                             Math.min(1, base.getBlue() / 255f * 1.6f + 0.04f) };
        rays = compile(BATCH_VERTEX, RAYS_FRAGMENT);
        monitors = compile(BATCH_VERTEX, MONITORS_FRAGMENT);
        flip = compile(FLIP_VERTEX, FLIP_FRAGMENT);
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
        return rays != null && monitors != null && flip != null;
    }

    /** After the window's size changes: a camera that shows the plane z = 0 pixel for pixel. */
    void resize(int width, int height) {
        bw = width;
        bh = height;
        camera.fieldOfView = 30;
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        float distance = (float) (height / 2.0 / Math.tan(Math.toRadians(camera.fieldOfView / 2)));
        camera.position.set(width / 2f, height / 2f, distance);
        camera.lookAt(width / 2f, height / 2f, 0);
        camera.up.set(0, 1, 0);
        camera.near = distance / 10;
        camera.far = distance * 2;
        camera.update();
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

    /**
     * A tile's angle about its horizontal axis {@code progress} of the way through its flip: from showing its back (pi)
     * to flat (0), overshooting a little before it settles.
     */
    static double flipAngle(double progress) {
        double p = Math.max(0, Math.min(1, progress)) - 1;
        double overshoot = 1.4;
        double eased = 1 + (overshoot + 1) * p * p * p + overshoot * p * p;
        return Math.PI * (1 - eased);
    }

    /**
     * Draws the departures board {@code progress} of the way through the reveal: {@code backdrop} is the finished screen,
     * {@code tiles} the tiles' centres in pixels from the top left with when each starts, {@code half} half a tile's
     * side in pixels.
     */
    void drawBoard(Texture backdrop, List<GpuStartScreen.Tile> tiles, float scale, double half, double progress) {
        int needed = tiles.size() * 6 * FLOATS;
        if (mesh == null || vertices.length < needed) {
            if (mesh != null) {
                mesh.dispose();
            }
            vertices = new float[needed];
            mesh = new Mesh(false, tiles.size() * 6, 0, VertexAttribute.Position(), VertexAttribute.TexCoords(0),
                  VertexAttribute.ColorUnpacked(), new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_face"));
        }
        float backsAlpha = (float) Math.max(0, Math.min(1, progress / BACKS_IN));
        float r = (float) (half * scale);
        int n = 0;
        for (GpuStartScreen.Tile tile : tiles) {
            double flipProgress = (progress - BACKS_IN - tile.at()) / FLIP_SPAN;
            double angle = flipAngle(flipProgress);
            n = tile(n, (float) (tile.x() * scale), (float) (bh - tile.y() * scale), r, angle, backsAlpha);
        }
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        backdrop.bind(0);
        flip.bind();
        flip.setUniformMatrix("u_projTrans", camera.combined);
        flip.setUniformi("u_texture", 0);
        flip.setUniformf("u_edge", edge[0], edge[1], edge[2]);
        mesh.setVertices(vertices, 0, n);
        mesh.render(flip, GL20.GL_TRIANGLES, 0, n / FLOATS);
    }

    /** Adds one tile turned {@code angle} about its horizontal axis through ({@code x}, {@code y}); returns the end. */
    private int tile(int n, float x, float y, float r, double angle, float backsAlpha) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        boolean face = cos >= 0;
        float light = 0.55f + 0.45f * Math.abs(cos);
        float[][] corners = { { -r, -r }, { r, -r }, { r, r }, { -r, -r }, { r, r }, { -r, r } };
        for (float[] corner : corners) {
            float lx = corner[0];
            float ly = corner[1];
            vertices[n++] = x + lx;
            vertices[n++] = y + ly * cos;
            vertices[n++] = ly * sin;
            if (face) {
                vertices[n++] = (x + lx) / bw;
                vertices[n++] = 1 - (y + ly) / bh;
                vertices[n++] = light;
                vertices[n++] = light;
                vertices[n++] = light;
                vertices[n++] = 1;
                vertices[n++] = 1;
            } else {
                vertices[n++] = (lx / r + 1) / 2;
                vertices[n++] = (ly / r + 1) / 2;
                vertices[n++] = back[0] * light;
                vertices[n++] = back[1] * light;
                vertices[n++] = back[2] * light;
                vertices[n++] = backsAlpha;
                vertices[n++] = 0;
            }
        }
        return n;
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
        monitors.setUniformf("u_cells", bw / (24 * scale), bh / (24 * scale));
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
        for (Disposable disposable : new Disposable[] { rays, monitors, flip, mesh, mask }) {
            if (disposable != null) {
                disposable.dispose();
            }
        }
        rays = null;
        monitors = null;
        flip = null;
        mesh = null;
        mask = null;
    }
}
