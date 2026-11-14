/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;

/**
 * One falling piece's motion in closed form on the CPU: tree-instances.glsl's fallBegin, from the same inputs, so that
 * {@link GpuDestruction} knows when a piece touches down and how it moves at that moment, and can hand it to a body
 * there. {@link #start} takes the piece's throw, {@link #at} poses it at a clock, {@link #flight} gives its state in the
 * air. One object serves every piece in turn; nothing is allocated per piece. Render thread only.
 */
final class FallForm {
    /** A piece's inputs: its instance's eight floats (GpuTreeInstances#append), then its box's eight ({@link #box}). */
    static final int INPUTS = 16;

    private float gravityUniform, level, reach, columns, rows, hexWidth, hexHeight;
    /** The instance's origin, the piece's middle standing, its launch speed and spin, and its box's half size. */
    final Vector3 origin = new Vector3(), centre = new Vector3(), velocity = new Vector3(), spin = new Vector3(),
          extent = new Vector3();
    /** Where its middle is, how fast it moves and how it has turned from standing, as {@link #at} or {@link #flight} left them. */
    final Vector3 position = new Vector3(), moving = new Vector3();
    final Quaternion turn = new Quaternion();
    /** Its size: 1, less as it shrinks away past the board's edge. */
    float scale;
    /** When it starts to move, and how long after that it touches down; never, where it flies past the board's edge. */
    float begins, landing;
    /** The fall's gravity, in world units per second squared. */
    float gravity;
    /** The box's first side as the piece stood, level. */
    float alongX, alongY;
    private float ground, share, pile, height, siteX, siteY, siteZ, sinks, floored;
    private boolean beyond;
    private final Vector3 scratch = new Vector3(), over = new Vector3();
    private final Quaternion lean = new Quaternion();

    /** The falls' gravity, in world units per second squared, and the board's size in hexes (GpuLavaLighting). */
    void board(float gravity, int columns, int rows) {
        gravityUniform = gravity;
        level = BoardGeometry.level();
        reach = BoardGeometry.height() * .5f;
        this.columns = columns;
        this.rows = rows;
        hexWidth = BoardGeometry.width();
        hexHeight = BoardGeometry.height();
    }

    /**
     * A piece's box as eight floats at {@code at}: a broken part's fragment {@code index} from its table
     * (GpuInstancedMesh.Fragments), else the whole part's ({@code table} null): its middle, its first vertex, its half
     * size and its turn about the vertical.
     */
    static void box(float[] out, int at, float[] table, int index, Vector3 middle, Vector3 half) {
        if (table != null) {
            System.arraycopy(table, 8 * index, out, at, 8);
            return;
        }
        out[at] = middle.x;
        out[at + 1] = middle.y;
        out[at + 2] = middle.z;
        out[at + 3] = 0;
        out[at + 4] = half.x;
        out[at + 5] = half.y;
        out[at + 6] = half.z;
        out[at + 7] = 0;
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        return h ^ (h >>> 16);
    }

    private static float noise(int seed, int salt) {
        return (mix(seed + salt * 0x9e3779b9) & 0xffffff) / 16777216f;
    }

    private boolean offBoard(float x, float y) {
        if (columns < .5f) { return false; }
        float q = (x - .5f * hexWidth) / (.75f * hexWidth), r = (-y - .5f * hexHeight) / hexHeight - .5f * q;
        float cz = -q - r, hx = (float) Math.floor(q + .5f), hy = (float) Math.floor(r + .5f), hz = (float) Math.floor(cz + .5f);
        float ox = Math.abs(hx - q), oy = Math.abs(hy - r), oz = Math.abs(hz - cz);
        if (ox > oy && ox > oz) { hx = -hy - hz; } else if (oy > oz) { hy = -hx - hz; }
        float row = hy + (float) Math.floor(.5f * hx);
        return hx < 0 || hx >= columns || row < 0 || row >= rows;
    }

    /** Where the piece stood, from its inputs alone: {@link #origin} and {@link #centre}, which a row is written about. */
    void place(float[] inputs, int at) {
        float iw = inputs[at + 3], cos = inputs[at + 4], sin = inputs[at + 5];
        origin.set(inputs[at], inputs[at + 1], inputs[at + 2]);
        float px = inputs[at + 8] * Math.abs(iw), py = inputs[at + 9] * iw, pz = inputs[at + 10] * inputs[at + 6];
        centre.set(cos * px - sin * py + origin.x, sin * px + cos * py + origin.y, pz + origin.z);
    }

    /**
     * The piece's throw, from its hex's slot ({@code slot}, GpuDestruction#TEXELS texels) and its inputs
     * ({@link #INPUTS} floats of {@code inputs} from {@code at}). False while its hex still stands.
     */
    boolean start(float[] slot, float[] inputs, int at) {
        place(inputs, at);
        int box = at + 8;
        float ix = inputs[at], iy = inputs[at + 1], iz = inputs[at + 2], iw = inputs[at + 3];
        float cos = inputs[at + 4], sin = inputs[at + 5], tall = inputs[at + 6];
        float sx = Math.abs(iw), sy = iw, sz = tall;
        siteX = slot[4];
        siteY = slot[5];
        siteZ = slot[6];
        sinks = slot[8];
        if (slot[0] > 1e8f) { return false; }
        int seed = mix(Float.floatToRawIntBits(ix) ^ mix(Float.floatToRawIntBits(iy) ^ mix(Float.floatToRawIntBits(iz)
              ^ mix(Float.floatToRawIntBits(cos) ^ mix(Float.floatToRawIntBits(inputs[box])
              ^ mix(Float.floatToRawIntBits(inputs[box + 1]) ^ Float.floatToRawIntBits(inputs[box + 2])))))));
        height = Math.max(slot[7] - siteZ, level);
        share = MathUtils.clamp((centre.z - siteZ) / height, 0, 1);
        float foot = (1 - share) * (1 - share);
        float outX = centre.x - siteX, outY = centre.y - siteY, offset = (float) Math.hypot(outX, outY);
        float bearing = 6.2831853f * noise(seed, 0);
        float awayX = offset > 1e-3f ? outX / offset : (float) Math.cos(bearing);
        float awayY = offset > 1e-3f ? outY / offset : (float) Math.sin(bearing);
        float strength = (float) Math.hypot(slot[2], slot[3]);
        float pushX = strength > 1e-4f ? slot[2] / strength : 0, pushY = strength > 1e-4f ? slot[3] / strength : 0;
        int flags = (int) (slot[1] + .5f);
        boolean loaded = (flags & GpuDestruction.LOADED) != 0, burst = (flags & GpuDestruction.BURST) != 0;
        pile = burst ? 0 : 1;
        begins = slot[0];
        if ((flags & GpuDestruction.BLAST) != 0) {
            float fromX = centre.x - slot[9], fromY = centre.y - slot[10], far = (float) Math.hypot(fromX, fromY);
            begins += slot[11] * far;
            if (far * far > 1e-4f) {
                pushX = fromX / far;
                pushY = fromY / far;
            }
        }
        begins += burst ? 0 : GpuDestruction.RELEASE_SPAN * share * (.8f + .4f * noise(seed, 7))
              * (1 - .5f * Math.min(strength, 1)) * (loaded ? .3f : 1);
        float out = (GpuDestruction.CRUSH * foot + GpuDestruction.SPILL * share) * (.6f + .8f * noise(seed, 1));
        velocity.set(awayX * out, awayY * out, GpuDestruction.CRUSH * .3f * foot * noise(seed, 9));
        float facing = MathUtils.clamp(.5f - (outX * pushX + outY * pushY) / reach, 0, 1);
        float push = GpuDestruction.PUSH * strength * (.5f + .3f * facing + .4f * share);
        velocity.add(pushX * push, pushY * push, GpuDestruction.PUSH * .35f * Math.max(0, strength - 1));
        if (loaded) { velocity.z = Math.min(velocity.z, -GpuDestruction.LOAD_DROP); }
        scratch.set(noise(seed, 2) - .5f, noise(seed, 3) - .5f, noise(seed, 4) - .5f);
        if (scratch.len2() > 1e-6f) { scratch.nor(); } else { scratch.set(0, 0, 1); }
        float tumble = GpuDestruction.TUMBLE * share * (.3f + .7f * noise(seed, 5));
        float topple = GpuDestruction.TOPPLE_OUT * (.5f + foot) * (.5f + noise(seed, 8));
        float pushed = GpuDestruction.TOPPLE * strength * (.5f + share);
        spin.set(scratch).scl(tumble).add(-awayY * topple, awayX * topple, 0).add(-pushY * pushed, pushX * pushed, 0);
        if (burst) {
            float thrownX = awayX, thrownY = awayY;
            if (strength > 1e-4f) {
                float length = (float) Math.hypot(pushX * 1.5f + awayX, pushY * 1.5f + awayY);
                thrownX = (pushX * 1.5f + awayX) / length;
                thrownY = (pushY * 1.5f + awayY) / length;
            }
            velocity.set(thrownX, thrownY, .6f + .9f * noise(seed, 9)).nor()
                  .scl(GpuDestruction.BURST_SPEED * (.5f + .5f * noise(seed, 1)));
            spin.set(scratch).scl(GpuDestruction.TUMBLE * (.3f + .7f * noise(seed, 5)));
        }
        ground = siteZ + mound(offset * offset);
        extent.set(Math.abs(inputs[box + 4] * sx), Math.abs(inputs[box + 5] * sy), Math.abs(inputs[box + 6] * sz));
        gravity = Math.max(gravityUniform, 2 * height / (GpuDestruction.FALL_SECONDS * GpuDestruction.FALL_SECONDS));
        landing = reaches(ground);
        floored = landing;
        float skid = landing + 1 / GpuDestruction.GROUND_DRAG;
        beyond = offBoard(centre.x + velocity.x * skid, centre.y + velocity.y * skid);
        if (beyond) { landing = 1e9f; }
        float turned = inputs[box + 7], ax = (float) Math.cos(turned), ay = (iw < 0 ? -1 : 1) * (float) Math.sin(turned);
        alongX = cos * ax - sin * ay;
        alongY = sin * ax + cos * ay;
        return true;
    }

    /** How high the wreck of the storeys below lies under a piece resting {@code distance2} (squared) from its hex's middle. */
    private float mound(float distance2) {
        return Math.min(.12f * height, GpuDestruction.PILE_LEVELS * level) * share * pile
              * Math.max(0, 1 - distance2 / (reach * reach));
    }

    /** The mound under the spot the piece touches down on: what a body handed over there rests on above its hex's floor. */
    float lift() {
        float x = centre.x + velocity.x * floored - siteX, y = centre.y + velocity.y * floored - siteY;
        return mound(x * x + y * y);
    }

    /** Where the piece touches down, on the board's plane. */
    float landsX() { return centre.x + velocity.x * floored; }

    float landsY() { return centre.y + velocity.y * floored; }

    /**
     * How long after it starts to move the piece's underside, as it stood, comes down to the height {@code floor}: at
     * once where it stands below it, never without gravity while it does not move down.
     */
    float reaches(float floor) {
        float drop = Math.max(0, centre.z - extent.z - floor);
        return gravity > 1e-4f ? (velocity.z + (float) Math.sqrt(velocity.z * velocity.z + 2 * gravity * drop)) / gravity
              : velocity.z < -1e-4f ? drop / -velocity.z : 1e9f;
    }

    /** The height the closed form lands the piece on: its hex's floor as the slot has it, and the mound there. */
    float ground() { return ground; }

    /**
     * The piece in the air {@code seconds} after it started to move (0 and before: as it stands): {@link #position},
     * {@link #moving} and {@link #turn}; its spin is {@link #spin} throughout.
     */
    void flight(float seconds) {
        float t = Math.max(0, seconds);
        position.set(centre).mulAdd(velocity, t);
        position.z -= .5f * gravity * t * t;
        moving.set(velocity.x, velocity.y, velocity.z - gravity * t);
        float rate = spin.len();
        if (rate > 1e-4f) { turn.setFromAxisRad(scratch.set(spin).scl(1 / rate), rate * t); } else { turn.idt(); }
        scale = 1;
    }

    /** The piece's pose at {@code clock}: {@link #position}, {@link #turn} and {@link #scale}; false while it has not given way. */
    boolean at(float clock) {
        float t = clock - begins;
        if (t <= 0) { return false; }
        float flight = Math.min(t, landing), slide = Math.max(0, t - landing);
        position.set(centre).mulAdd(velocity, flight);
        position.z -= .5f * gravity * flight * flight;
        float skid = (1 - (float) Math.exp(-GpuDestruction.GROUND_DRAG * slide)) / GpuDestruction.GROUND_DRAG;
        position.x += velocity.x * skid;
        position.y += velocity.y * skid;
        float rate = spin.len();
        float settle = 1 - (float) Math.exp(-GpuDestruction.GROUND_SPIN_DRAG * slide);
        float angle = rate * (flight + settle / GpuDestruction.GROUND_SPIN_DRAG);
        if (rate > 1e-4f) { turn.setFromAxisRad(scratch.set(spin).scl(1 / rate), angle); } else { turn.idt(); }
        // Landed, it topples onto its flattest side as it skids to rest.
        if (extent.z <= Math.min(extent.x, extent.y)) { scratch.set(0, 0, 1); }
        else if (extent.x < extent.y) { scratch.set(alongX, alongY, 0); }
        else { scratch.set(-alongY, alongX, 0); }
        turn.transform(scratch);
        float thinZ = scratch.z;
        over.set(scratch).crs(0, 0, thinZ < 0 ? -1 : 1);
        float leaning = over.len();
        if (slide > 0 && leaning > 1e-4f) {
            lean.setFromAxisRad(over.scl(1 / leaning), MathUtils.atan2(leaning, Math.abs(thinZ)) * settle);
            turn.mulLeft(lean);
        }
        // The board's up in the piece's standing frame: the turned box's reach below its middle.
        scratch.set(0, 0, 1);
        lean.set(turn).conjugate().transform(scratch);
        float below = Math.abs(scratch.x * alongX + scratch.y * alongY) * extent.x
              + Math.abs(-scratch.x * alongY + scratch.y * alongX) * extent.y + Math.abs(scratch.z) * extent.z;
        float liesX = position.x - siteX, liesY = position.y - siteY;
        float rest = siteZ + below + mound(liesX * liesX + liesY * liesY);
        scale = 1;
        if (beyond) {
            float gone = MathUtils.clamp((t - floored) / GpuDestruction.EDGE_SECONDS, 0, 1);
            scale = 1 - gone * gone * (3 - 2 * gone);
        } else {
            float settled = slide > 0 ? MathUtils.lerp(position.z, rest, 1 - (float) Math.exp(-GpuDestruction.GROUND_DRAG * slide))
                  : position.z;
            position.z = Math.max(settled, rest);
        }
        float sinking = MathUtils.clamp((clock - sinks) / GpuDestruction.SINK_SECONDS, 0, 1);
        sinking = sinking * sinking * (3 - 2 * sinking);
        position.z -= 2 * extent.len() * sinking;
        // It shrinks as it sinks: at the board's edge nothing pokes out through the board's side.
        scale *= Math.max(1e-3f, 1 - sinking);
        return true;
    }

    /**
     * Writes the pose ({@link #position}, {@link #turn}, {@link #scale}) as the piece's row, about its instance's origin:
     * p' = origin + shift + scale * turn(p - origin) (tree-instances.glsl, poseRow).
     */
    void row(GpuPoses poses, int row) {
        scratch.set(centre).sub(origin);
        turn.transform(scratch).scl(scale);
        poses.set(row, position.x - origin.x - scratch.x, position.y - origin.y - scratch.y,
              position.z - origin.z - scratch.z, scale, turn.x, turn.y, turn.z, turn.w);
    }

    /**
     * Hands the piece to a box body of {@code bodies} as it flies {@code seconds} after it started to move, or as it
     * stands that long before: where it is, turned and moving as the closed form has it, on its box's own axes, to rest
     * on the pile's mound where it lands. Returns the body's number.
     */
    int body(MeepleDebris bodies, float seconds) {
        flight(seconds);
        lean.setFromAxisRad(Vector3.Z, MathUtils.atan2(alongY, alongX));
        int body = bodies.box(position, extent, lean, moving, spin, Math.max(0, -seconds));
        bodies.handed(body, turn, lift());
        return body;
    }

    /** A body's pose (MeepleDebris#pose) as the piece's row. */
    void row(GpuPoses poses, int row, float[] pose) {
        pose(pose);
        row(poses, row);
    }

    /** Takes a body's pose (MeepleDebris#pose) as the piece's: {@link #position}, {@link #scale} and {@link #turn}. */
    void pose(float[] pose) {
        position.set(pose[0], pose[1], pose[2]);
        scale = pose[3];
        turn.set(pose[4], pose[5], pose[6], pose[7]);
    }

    /**
     * The pose as the transform of a node whose parent stands at the instance's {@link #origin}, its mesh about that
     * origin as the piece stood: p' = position + scale turn (p - centre), for a piece drawn as a node of a model of its
     * own rather than instanced (GpuDestruction#takeModel). Standing, the identity.
     */
    Matrix4 node(Matrix4 out) {
        return out.setToTranslation(position.x - origin.x, position.y - origin.y, position.z - origin.z).rotate(turn)
              .scale(scale, scale, scale).translate(origin.x - centre.x, origin.y - centre.y, origin.z - centre.z);
    }
}
