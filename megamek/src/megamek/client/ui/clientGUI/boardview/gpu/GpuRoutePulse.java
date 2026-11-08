/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;

/**
 * The plotted route's pulse (user item 55): a glowing head leaves the moving unit, runs along the route lighting the
 * marks it passes and settles into the destination, where the ghost surges and a ring ripples out; after a rest it
 * leaves again. GpuBoardOverlay hands it the route while it builds its meshes, and draws it after them every frame: a
 * few dozen soft glowing quads of one small mesh, which add light and tint what lies under them, depth-tested in 3D
 * without writing depth, flat on top in the Tactical View. It decides no rule. GL thread only; it owns its mesh and
 * its glow texture.
 */
final class GpuRoutePulse implements Disposable {
    /** Whether the plotted route pulses; the Tuning panel switches it while the board runs. */
    static final boolean ENABLED = true;
    /** The head's speed: at 1 it takes {@link #SECONDS_PER_HEX} per hex, within the travel bounds. */
    static final float SPEED = 1;
    /** The brightness of the head, its trail, the ripple and the ghost's surge; 0 shows none of them. */
    static final float INTENSITY = 1;
    /** The head's travel time per hex at speed 1, and a route's shortest and longest travel, in seconds. */
    static final float SECONDS_PER_HEX = .16f;
    static final float MIN_TRAVEL = .5f;
    static final float MAX_TRAVEL = 1.8f;
    /** After the head lands: the ghost's surge, then the rest before the next pulse leaves the unit, in seconds. */
    static final float SURGE = .9f;
    static final float REST = .6f;
    /** The head's glow radius: in hex radii in 3D, in prototype pixels in the Tactical View. */
    static final float HEAD_SIZE = .42f;
    static final float FLAT_HEAD_SIZE = 15;
    /** How far behind the head the route's marks stay lit, in hexes. */
    static final float TRAIL = 2.5f;
    /** The 3D ghost's glow added at the surge's peak, and the share of its transparency the peak takes away. */
    static final float GHOST_SURGE = .52f;
    static final float GHOST_SURGE_OPACITY = .8f;
    /** How far the ripple runs out from the destination's centre, in hex radii, and how long it takes, in seconds. */
    static final float RING_RADIUS = 1.65f;
    static final float RING_SECONDS = .75f;

    // The values in use: render-thread state that GpuBoardTuning's controls edit and its Defaults restores.
    static boolean enabled = ENABLED;
    static float speed = SPEED;
    static float intensity = INTENSITY;

    /** The shares of the travel time spent speeding up out of the unit and settling into the destination. */
    private static final float ACCELERATE = .25f;
    private static final float SETTLE = .35f;
    /** The head's fade-in as it leaves the unit, its flare as it lands and the trail's afterglow, in seconds. */
    private static final float FADE_IN = .12f;
    private static final float FLARE = .35f;
    private static final float AFTERGLOW = .45f;
    /** The rise of the ghost's surge, in seconds. */
    private static final float SURGE_RISE = .08f;
    /** How far ahead of the head the marks begin to light, in hex radii. */
    private static final float LEAD = .3f;
    /** The motes lifting off the lit trail in 3D: their size, and how far they rise and drift over it, in hex radii. */
    private static final float MOTE_SIZE = .05f;
    private static final float MOTE_RISE = .55f;
    private static final float MOTE_DRIFT = .2f;
    /** The streak of light right behind the head: its length and half width in hex radii, or prototype pixels. */
    private static final float STREAK = 1.1f;
    private static final float STREAK_WIDTH = .1f;
    private static final float FLAT_STREAK = 34;
    private static final float FLAT_STREAK_WIDTH = 3.5f;
    /** The second, fainter ripple: its delay in seconds and its share of the first's strength. */
    private static final float ECHO = .14f;
    private static final float ECHO_STRENGTH = .35f;
    /** The destination's flash as the head lands, in seconds. */
    private static final float FLASH = .5f;
    /** Where the ripple starts, a fraction of the hex radius just inside the destination ring. */
    private static final float RING_START = .9f;
    /** The glow texture's side in texels and its falloff: exp(-falloff r^2), shifted to reach 0 at the edge. */
    private static final int GLOW_TEXELS = 64;
    private static final float GLOW_FALLOFF = 4.5f;
    /** One route sample: x, y, z, the ground under it, its distance along the route, red, green, blue. */
    private static final int SAMPLE = 8;
    /** One mark: its two ends (the same for a dot), its half width, its distance along the route, red, green, blue. */
    private static final int MARK = 11;
    /** One vertex: x, y, z, packed colour, u, v. */
    private static final int VERTEX = 6;
    /** The quads one frame may draw; a route's lit trail, head and ripples take well under a hundred. */
    private static final int QUADS = 256;
    /** The hex corners' directions as MegaMek's BoardGeometry numbers them: x in radii, y in half hex heights. */
    private static final float[] CORNER_X = { 1, .5f, -.5f, -1, -.5f, .5f };
    private static final float[] CORNER_Y = { 0, 1, 1, 0, -1, -1 };

    private final FloatArray samples = new FloatArray();
    private final FloatArray marks = new FloatArray();
    /** The destination's centre on its surface, and the route's last colour. */
    private final Vector3 destination = new Vector3();
    private final Color last = new Color();
    /** The route the clock runs for; another route starts it again from the unit. */
    private Object key;
    private boolean active;
    private boolean wasActive;
    private boolean tactical;
    /** The hex radius in world units. */
    private float radius;
    private int hexes;
    /** Seconds since the pulse last left the unit. */
    private float clock;
    private Texture glow;
    private Mesh mesh;
    private final Renderable renderable = new Renderable();
    private float[] vertices;
    private int quads;
    private int vertex;
    // Per-frame scratch: a frame allocates nothing.
    private final Vector3 point = new Vector3();
    private final Color pointColour = new Color();
    private float pointGround;
    private final Vector3 view = new Vector3();
    private final Vector3 right = new Vector3();
    private final Vector3 up = new Vector3();
    private float push;

    /** A new build of the overlay: no route until {@link #arrive} names one. */
    void begin(boolean tactical, float radius) {
        wasActive = active;
        active = false;
        this.tactical = tactical;
        this.radius = radius;
        samples.clear();
        marks.clear();
    }

    /**
     * The build has a plotted route with a ghost: {@code key} names it, {@code hexes} counts hexes or vertical levels,
     * {@code centre} the destination's centre on its surface and {@code colour} its last band's colour. The pulse keeps
     * its rhythm for the route of the last build and starts from the unit for any other.
     */
    void arrive(Object key, int hexes, Vector3 centre, Color colour) {
        if (!wasActive || !key.equals(this.key)) {
            clock = 0;
        }
        this.key = key;
        this.hexes = hexes;
        destination.set(centre);
        last.set(colour);
        active = true;
    }

    /** The route's line: a point {@code distance} hex radii along it, over {@code ground}, in its band's colour. */
    void point(Vector3 at, float ground, float distance, Color colour) {
        samples.add(at.x, at.y, at.z, ground);
        samples.add(distance, colour.r, colour.g, colour.b);
    }

    /** A mark of the route that lights up as the head passes: a dash from {@code a} to {@code b}, or a dot. */
    void mark(Vector3 a, Vector3 b, float halfWidth, float distance, Color colour) {
        marks.add(a.x, a.y, a.z, b.x);
        marks.add(b.y, b.z, halfWidth, distance);
        marks.add(colour.r, colour.g, colour.b);
    }

    /** Moves the pulse on by a frame; a longer frame counts as .1 s, as the HUD's actions do. */
    void advance(float seconds) {
        if (!active) {
            return;
        }
        clock += MathUtils.clamp(seconds, 0, .1f);
        float cycle = travel() + SURGE + REST;
        if (clock >= cycle) {
            clock %= cycle;
        }
    }

    /** The route's spatial length in hex radii; 0 without a route or for a turn without translation. */
    float length() {
        return samples.size == 0 ? 0 : samples.items[samples.size - SAMPLE + 4];
    }

    /** How far the head is along the route in hex radii while it travels; -1 while it rests or nothing pulses. */
    float head() {
        float travel = travel();
        return !shows() || clock >= travel ? -1 : length() * ease(clock / travel);
    }

    /** Where the head is while it travels, into {@code out}; false while it rests or nothing pulses. */
    boolean headAt(Vector3 out) {
        float distance = head();
        if (distance < 0) {
            return false;
        }
        locate(distance);
        out.set(point);
        return true;
    }

    /** Copies the final segment's colour for the destination ghost's surge. */
    Color arrivalColor(Color out) {
        return out.set(last);
    }

    /**
     * The ghost's surge: 0 until the head lands, then a quick rise to the intensity in use and a slow ease back to 0
     * within {@link #SURGE} seconds.
     */
    float surge() {
        float since = clock - travel();
        if (!shows() || since < 0 || since >= SURGE) {
            return 0;
        }
        float rise = since < SURGE_RISE ? MathUtils.sin(since / SURGE_RISE * MathUtils.HALF_PI)
              : .5f + .5f * MathUtils.cos((since - SURGE_RISE) / (SURGE - SURGE_RISE) * MathUtils.PI);
        return rise * intensity;
    }

    private boolean shows() {
        return active && enabled && intensity > 0;
    }

    /** The head's travel time at the speed in use; none for a route that stays in its hex. */
    private float travel() {
        return length() <= 0 ? 0
              : MathUtils.clamp(hexes * SECONDS_PER_HEX, MIN_TRAVEL, MAX_TRAVEL) / Math.max(speed, .05f);
    }

    /**
     * The share of the route travelled after the share {@code u} of the travel time: the speed rises smoothly out of
     * the unit, holds, and falls smoothly to rest at the destination (half-cosine ramps).
     */
    static float ease(float u) {
        if (u <= 0) {
            return 0;
        }
        if (u >= 1) {
            return 1;
        }
        float cruise = 1 / (1 - (ACCELERATE + SETTLE) / 2);
        if (u < ACCELERATE) {
            return cruise * (u / 2 - ACCELERATE / MathUtils.PI2 * MathUtils.sin(MathUtils.PI * u / ACCELERATE));
        }
        if (u <= 1 - SETTLE) {
            return cruise * (u - ACCELERATE / 2);
        }
        float settling = u - (1 - SETTLE);
        return cruise * (1 - SETTLE - ACCELERATE / 2 + settling / 2
              + SETTLE / MathUtils.PI2 * MathUtils.sin(MathUtils.PI * settling / SETTLE));
    }

    /** Its quad mesh and material ahead of a first route, for GpuBoardOverlay.prepare to link their program. */
    Renderable prepare() {
        if (mesh == null) { create(); }
        return renderable;
    }

    /**
     * This frame's quads, or null when nothing shows; GpuBoardOverlay draws them after its own meshes. While the
     * head travels: the lit marks behind it, its streak and the head; after it lands: the flare, the fading trail,
     * the destination's flash and two ripples.
     */
    Renderable renderable(Camera camera) {
        quads = 0;
        vertex = 0;
        if (!shows()) {
            return null;
        }
        if (mesh == null) {
            create();
        }
        if (!tactical) {
            face(camera);
        }
        float strength = Math.min(intensity, 2);
        float travel = travel();
        float length = length();
        if (clock < travel) {
            float distance = length * ease(clock / travel);
            float shown = strength * Math.min(1, clock / FADE_IN);
            marks(distance, shown);
            streak(distance, shown);
            locate(distance);
            head(shown, 1);
        } else {
            float since = clock - travel;
            if (length > 0) {
                float fade = 1 - since / AFTERGLOW;
                if (fade > 0) {
                    marks(length, strength * fade * fade);
                }
                if (since < FLARE) {
                    float left = 1 - since / FLARE;
                    locate(length);
                    head(strength * left * left, 1 + .8f * (1 - left * left));
                }
            }
            flash(since, strength);
            ripple(since, strength);
            ripple(since - ECHO, strength * ECHO_STRENGTH);
        }
        if (quads == 0) {
            return null;
        }
        mesh.setVertices(vertices, 0, vertex);
        renderable.meshPart.size = quads * 6;
        return renderable;
    }

    /**
     * Lights the marks about the head: from a little ahead of it, brightest at it, fading over the trail behind. In 3D
     * about every other mark the head passed lets a mote of light rise and drift off as the head moves on.
     */
    private void marks(float distance, float strength) {
        float trail = TRAIL * BoardGeometry.HEIGHT / radius;
        float[] mark = marks.items;
        for (int i = 0; i < marks.size; i += MARK) {
            float behind = distance - mark[i + 7];
            if (behind < -LEAD || behind > trail) {
                continue;
            }
            float lit = behind < 0 ? 1 + behind / LEAD : 1 - behind / trail;
            float alpha = lit * lit * strength;
            float half = mark[i + 6] * radius;
            pointColour.set(mark[i + 8], mark[i + 9], mark[i + 10], 1);
            float x = (mark[i] + mark[i + 3]) / 2;
            float y = (mark[i + 1] + mark[i + 4]) / 2;
            float z = (mark[i + 2] + mark[i + 5]) / 2;
            float reach = Vector3.dst(mark[i], mark[i + 1], mark[i + 2], mark[i + 3], mark[i + 4], mark[i + 5]) / 2;
            // A soft halo about the mark, and a brighter core in the same movement colour.
            flat(x, y, z, reach + 3.5f * half, glow(pointColour, .85f * alpha, .4f * alpha));
            float core = glow(pointColour, alpha, .9f * alpha);
            if (reach > 0) {
                band(mark[i], mark[i + 1], mark[i + 2], mark[i + 3], mark[i + 4], mark[i + 5], 1.2f * half,
                      1.2f * half, core, core, false);
            } else {
                flat(x, y, z, 1.6f * half, core);
            }
            float seed = random(i);
            if (!tactical && behind > 0 && seed < .5f) {
                // Its own rise and drift, from the mark's place in the route: quick at first, then floating.
                float age = behind / trail;
                float risen = 1 - (1 - age) * (1 - age) * (1 - age);
                float angle = random(i + 1) * MathUtils.PI2;
                float drift = MOTE_DRIFT * radius * risen;
                float shine = (1 - age) * strength;
                facing(x + MathUtils.cos(angle) * drift, y + MathUtils.sin(angle) * drift,
                      z + MOTE_RISE * radius * risen * (.6f + seed), MOTE_SIZE * radius,
                      glow(pointColour, shine, .5f * shine));
            }
        }
    }

    /** A fixed pseudo-random number from 0 to 1 for {@code index}. */
    private static float random(int index) {
        float value = MathUtils.sin(index * 12.9898f) * 43758.547f;
        return value - (float) Math.floor(value);
    }

    /** The streak right behind the head, a comet's tail: brightest and widest at it, narrowing to nothing behind. */
    private void streak(float distance, float strength) {
        float length = tactical ? FLAT_STREAK * GpuBoardOverlay.PIXEL : STREAK;
        float half = (tactical ? FLAT_STREAK_WIDTH * GpuBoardOverlay.PIXEL : STREAK_WIDTH) * radius;
        float from = distance - length;
        float[] sample = samples.items;
        int count = samples.size / SAMPLE;
        for (int j = 0; j + 1 < count; j++) {
            int a = j * SAMPLE;
            int b = a + SAMPLE;
            float start = sample[a + 4];
            float span = sample[b + 4] - start;
            if (span <= 0 || start + span <= from || start >= distance) {
                continue;
            }
            float t0 = Math.max(0, (from - start) / span);
            float t1 = Math.min(1, (distance - start) / span);
            float left0 = 1 - (distance - start - t0 * span) / length;
            float left1 = 1 - (distance - start - t1 * span) / length;
            pointColour.set(sample[a + 5], sample[a + 6], sample[a + 7], 1);
            band(sample[a] + (sample[b] - sample[a]) * t0, sample[a + 1] + (sample[b + 1] - sample[a + 1]) * t0,
                  sample[a + 2] + (sample[b + 2] - sample[a + 2]) * t0,
                  sample[a] + (sample[b] - sample[a]) * t1, sample[a + 1] + (sample[b + 1] - sample[a + 1]) * t1,
                  sample[a + 2] + (sample[b + 2] - sample[a + 2]) * t1, half * (.3f + .7f * left0),
                  half * (.3f + .7f * left1),
                  glow(pointColour, .85f * strength * left0 * left0, .4f * strength * left0 * left0),
                  glow(pointColour, .85f * strength * left1 * left1, .4f * strength * left1 * left1), true);
        }
    }

    /**
     * The head at the located point: in 3D a pool of its light on the ground below it and glows facing the camera,
     * from a wide, faint bloom to a bright core in the segment's colour; flat glows in the Tactical View.
     * {@code grow} widens it as it lands.
     */
    private void head(float strength, float grow) {
        if (strength <= 0) {
            return;
        }
        if (tactical) {
            float size = FLAT_HEAD_SIZE * GpuBoardOverlay.PIXEL * radius * grow;
            flat(point.x, point.y, point.z, 2.2f * size, glow(pointColour, .4f * strength, .12f * strength));
            flat(point.x, point.y, point.z, size, glow(pointColour, .8f * strength, .4f * strength));
            flat(point.x, point.y, point.z, .42f * size, glow(pointColour, strength, .75f * strength));
            flat(point.x, point.y, point.z, .2f * size, glow(pointColour, strength, .9f * strength));
            return;
        }
        float size = HEAD_SIZE * radius * grow;
        float pool = strength * MathUtils.clamp(1 - (point.z - pointGround) / radius / 2.4f, 0, 1);
        flat(point.x, point.y, pointGround + .02f * radius, 2.4f * size, glow(pointColour, .45f * pool, .18f * pool));
        facing(point.x, point.y, point.z, 2 * size, glow(pointColour, .35f * strength, .14f * strength));
        facing(point.x, point.y, point.z, size, glow(pointColour, .85f * strength, .45f * strength));
        facing(point.x, point.y, point.z, .36f * size, glow(pointColour, strength, .7f * strength));
        facing(point.x, point.y, point.z, .15f * size, glow(pointColour, strength, .9f * strength));
    }

    /**
     * The camera's axes for the quads facing it. The orthographic camera's quads all lie at one depth: each comes
     * toward the camera by as much as the ground in front of it can rise into it, so the terrain never cuts it.
     */
    private void face(Camera camera) {
        view.set(camera.direction).nor();
        right.set(view).crs(camera.up).nor();
        up.set(right).crs(view).nor();
        float sine = Math.max(-view.z, .25f);
        push = Math.min(2, (float) Math.sqrt(1 - sine * sine) / sine) + .1f;
    }

    /** The destination's flash as the head lands. */
    private void flash(float since, float strength) {
        if (since < 0 || since >= FLASH) {
            return;
        }
        float rise = .06f;
        float lit = since < rise ? since / rise : 1 - (since - rise) / (FLASH - rise);
        lit *= lit * strength;
        flat(destination.x, destination.y, destination.z + lift(), 1.2f * radius, glow(last, .7f * lit,
              .3f * lit));
    }

    /**
     * A shock ring out of the destination ring, {@code since} seconds after the head landed: a bright leading edge
     * with a soft fringe before it and a fading wake behind; it slows as it widens and fades.
     */
    private void ripple(float since, float strength) {
        if (since < 0 || since >= RING_SECONDS || strength <= 0) {
            return;
        }
        float progress = since / RING_SECONDS;
        float left = 1 - progress;
        float at = RING_START + (RING_RADIUS - RING_START) * (1 - left * left * left);
        float fade = strength * (1 - progress * progress * (3 - 2 * progress));
        float edge = tactical ? 3 * GpuBoardOverlay.PIXEL : .05f;
        float wake = (tactical ? 4 : 6) * edge * (.5f + progress);
        hexBand(at - wake, at, glow(last, .65f * fade, .4f * fade), 0, .5f);
        hexBand(at, at + 2 * edge, glow(last, .7f * fade, .35f * fade), .5f, 1);
        hexBand(at - edge, at, glow(last, fade, .85f * fade), .5f, .5f);
    }

    /**
     * The band between the hexagons {@code inner} and {@code outer} hex radii about the destination's centre; the
     * glow texture's profile runs from {@code across0} at the inner edge to {@code across1} at the outer (0 or 1 is
     * the soft disc's rim, .5 its full centre).
     */
    private void hexBand(float inner, float outer, float colour, float across0, float across1) {
        float z = destination.z + lift();
        float height = BoardGeometry.HEIGHT / BoardGeometry.WIDTH;
        for (int corner = 0; corner < 6 && quads < QUADS; corner++) {
            int next = (corner + 1) % 6;
            float x0 = CORNER_X[corner] * radius;
            float y0 = CORNER_Y[corner] * height * radius;
            float x1 = CORNER_X[next] * radius;
            float y1 = CORNER_Y[next] * height * radius;
            vertex(destination.x + x0 * outer, destination.y + y0 * outer, z, colour, .5f, across1);
            vertex(destination.x + x1 * outer, destination.y + y1 * outer, z, colour, .5f, across1);
            vertex(destination.x + x1 * inner, destination.y + y1 * inner, z, colour, .5f, across0);
            vertex(destination.x + x0 * inner, destination.y + y0 * inner, z, colour, .5f, across0);
            quads++;
        }
    }

    /** How far the destination's marks lie over its surface: under the route's marks in 3D. */
    private float lift() {
        return tactical ? 0 : .03f * radius;
    }

    /** The route's point {@code distance} hex radii along it: into point, pointGround and pointColour. */
    private void locate(float distance) {
        float[] sample = samples.items;
        int count = samples.size / SAMPLE;
        if (count < 2) {
            point.set(destination);
            pointGround = destination.z;
            pointColour.set(last);
            return;
        }
        int j = 0;
        while (j < count - 2 && sample[(j + 1) * SAMPLE + 4] <= distance) {
            j++;
        }
        int a = j * SAMPLE;
        int b = a + SAMPLE;
        float span = sample[b + 4] - sample[a + 4];
        float t = span <= 0 ? 1 : MathUtils.clamp((distance - sample[a + 4]) / span, 0, 1);
        point.set(sample[a] + (sample[b] - sample[a]) * t, sample[a + 1] + (sample[b + 1] - sample[a + 1]) * t,
              sample[a + 2] + (sample[b + 2] - sample[a + 2]) * t);
        pointGround = sample[a + 3] + (sample[b + 3] - sample[a + 3]) * t;
        pointColour.set(sample[a + 5], sample[a + 6], sample[a + 7], 1);
    }

    /** A soft disc lying flat at (x, y, z), {@code size} world units to its edge. */
    private void flat(float x, float y, float z, float size, float colour) {
        if (quads == QUADS || size <= 0) {
            return;
        }
        vertex(x - size, y - size, z, colour, 0, 0);
        vertex(x + size, y - size, z, colour, 1, 0);
        vertex(x + size, y + size, z, colour, 1, 1);
        vertex(x - size, y + size, z, colour, 0, 1);
        quads++;
    }

    /** A soft disc facing the camera at (x, y, z), moved toward the camera (see {@link #face}). */
    private void facing(float px, float py, float pz, float size, float colour) {
        if (quads == QUADS) {
            return;
        }
        float x = px - view.x * size * push;
        float y = py - view.y * size * push;
        float z = pz - view.z * size * push;
        float rx = right.x * size;
        float ry = right.y * size;
        float rz = right.z * size;
        float ux = up.x * size;
        float uy = up.y * size;
        float uz = up.z * size;
        vertex(x - rx - ux, y - ry - uy, z - rz - uz, colour, 0, 0);
        vertex(x + rx - ux, y + ry - uy, z + rz - uz, colour, 1, 0);
        vertex(x + rx + ux, y + ry + uy, z + rz + uz, colour, 1, 1);
        vertex(x - rx + ux, y - ry + uy, z - rz + uz, colour, 0, 1);
        quads++;
    }

    /**
     * A band lying across the ground from a to b, {@code halfA} and {@code halfB} world units to each side at its
     * ends: soft across (the glow's profile) or solid; its colour runs from {@code colourA} to {@code colourB}.
     */
    private void band(float ax, float ay, float az, float bx, float by, float bz, float halfA, float halfB,
          float colourA, float colourB, boolean soft) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (quads == QUADS || length < .001f && Math.abs(bz - az) < .001f) {
            return;
        }
        // A vertical floor segment has no ground direction; give it the camera's horizontal width.
        float nx = length < .001f ? right.x : -dy / length;
        float ny = length < .001f ? right.y : dx / length;
        float side = soft ? 0 : .5f;
        vertex(ax + nx * halfA, ay + ny * halfA, az, colourA, .5f, side);
        vertex(ax - nx * halfA, ay - ny * halfA, az, colourA, .5f, 1 - side);
        vertex(bx - nx * halfB, by - ny * halfB, bz, colourB, .5f, 1 - side);
        vertex(bx + nx * halfB, by + ny * halfB, bz, colourB, .5f, side);
        quads++;
    }

    private void vertex(float x, float y, float z, float colour, float u, float v) {
        vertices[vertex++] = x;
        vertices[vertex++] = y;
        vertices[vertex++] = z;
        vertices[vertex++] = colour;
        vertices[vertex++] = u;
        vertices[vertex++] = v;
    }

    /**
     * A vertex colour for the premultiplied blending: the configured movement {@code colour} gives
     * off {@code light} (added to what lies under it) and covers {@code cover} of it, which keeps the colour in sight
     * on bright ground.
     */
    private static float glow(Color colour, float light, float cover) {
        float shine = MathUtils.clamp(light, 0, 1);
        return Color.toFloatBits(colour.r * shine, colour.g * shine, colour.b * shine,
              MathUtils.clamp(cover, 0, 1));
    }

    /**
     * The glow texture (a soft white disc, premultiplied), the quad mesh and its material: premultiplied blending (see
     * {@link #glow}), visible through scene geometry without writing depth, both faces.
     */
    private void create() {
        Pixmap pixmap = new Pixmap(GLOW_TEXELS, GLOW_TEXELS, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        float edge = (float) Math.exp(-GLOW_FALLOFF);
        for (int y = 0; y < GLOW_TEXELS; y++) {
            for (int x = 0; x < GLOW_TEXELS; x++) {
                float dx = (x + .5f) * 2 / GLOW_TEXELS - 1;
                float dy = (y + .5f) * 2 / GLOW_TEXELS - 1;
                float squared = dx * dx + dy * dy;
                float alpha = squared >= 1 ? 0 : ((float) Math.exp(-GLOW_FALLOFF * squared) - edge) / (1 - edge);
                pixmap.drawPixel(x, y, Color.rgba8888(alpha, alpha, alpha, alpha));
            }
        }
        glow = new Texture(pixmap);
        glow.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        vertices = new float[QUADS * 4 * VERTEX];
        short[] indices = new short[QUADS * 6];
        for (int quad = 0; quad < QUADS; quad++) {
            int first = quad * 4;
            int at = quad * 6;
            indices[at] = (short) first;
            indices[at + 1] = (short) (first + 1);
            indices[at + 2] = (short) (first + 2);
            indices[at + 3] = (short) (first + 2);
            indices[at + 4] = (short) (first + 3);
            indices[at + 5] = (short) first;
        }
        mesh = new Mesh(false, QUADS * 4, indices.length, VertexAttribute.Position(), VertexAttribute.ColorPacked(),
              VertexAttribute.TexCoords(0));
        mesh.setIndices(indices);
        renderable.material = new Material(TextureAttribute.createDiffuse(glow),
              new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA),
              MeepleVisual.overScene(),
              IntAttribute.createCullFace(GL20.GL_NONE));
        renderable.meshPart.set("route-pulse", mesh, 0, 0, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        if (mesh != null) {
            mesh.dispose();
            mesh = null;
        }
        if (glow != null) {
            glow.dispose();
            glow = null;
        }
        vertices = null;
        active = false;
    }
}
