/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/**
 * Broken pieces as rigid bodies: a meeple's thrown by its explosion, away from the hits and out from its middle; or
 * boxes, a structure's fragments, thrown as the caller says ({@link #box}) as they touch down (GpuDestruction). Under a tabletop gravity they bounce off the ground and scenery under them until they
 * come to rest. They touch the board, not each other: through a height field sampled from it as they reach it
 * ({@link Ground}), or through a table of its hexes' floors ({@link Floors}). One integrator steps them all, on either
 * ground, a body resting on its own support points or on its box's corners; the bodies lie in flat arrays, and a step
 * allocates nothing. A box on the ground topples onto its flattest side and lies down there ({@link #topple},
 * {@link #lie}). Presentation only.
 */
final class MeepleDebris {
    /** World units per second squared under standard gravity: faster than life, as a miniature's fall reads on the table. */
    private static final float GRAVITY = 450;
    private static final float STEP = 1 / 120f;
    /** Steps one frame may take: after a hitch the pieces slow down instead of passing through the ground. */
    private static final int MAX_STEPS = 12;
    private static final float RESTITUTION = .2f, FRICTION = .8f;
    /**
     * How fast a piece touching the ground loses its speed and its spin, per second: rubble skids rather than rolls on,
     * but a slab on its edge still topples.
     */
    private static final float GROUND_DRAG = 12, GROUND_SPIN_DRAG = 7;
    /**
     * A piece touching the ground this slowly, in world units and radians per second, for this long settles; besides
     * the step of gravity the ground takes back from a resting piece, and the rocking of a piece on its corners.
     */
    private static final float SLEEP_SPEED = 4, SLEEP_SPIN = 2, SLEEP_SECONDS = .35f;
    /**
     * The explosion's push on a piece of average size, in world units per second: an internal blast low in the body,
     * outward from there, and the hits' momentum away from them, by each point of damage they dealt, at most
     * {@link #MAX_KICK}. Where the pieces land is the physics'.
     */
    private static final float BLAST = 85, KICK_PER_DAMAGE = 2.5f, MAX_KICK = 180;
    /** A piece meeting the ground at least this fast, in world units per second, raises dust ({@link #landings}). */
    private static final float HARD_LANDING = 70;
    /** The height field's spacing, and how far above the meeple's ground its pieces look for what they land on. */
    static final float CELL = 2;
    /** The ground's height where the board gives none, past its edge: nothing to land on. */
    private static final float NOWHERE = -1e6f;
    /**
     * A rise across one cell steeper than this, 45 degrees, is a step, not a slope: interpolated, the crest of a steeper
     * slope would cut its corner, and pieces resting there would sink into it.
     */
    private static final float CLIFF = CELL;
    /** The spacing of the samples that place a step's line. */
    private static final float FINE = CELL / 4;
    /** In place of a body's count of support points: it rests on the corners of its box ({@link #box}). */
    private static final int BOX = -1;
    /** The share of its lean a box on the ground loses in a step: a landed piece's rate in GpuDestruction's closed form. */
    private static final float LIE_DOWN = 1 - (float) Math.exp(-GpuDestruction.GROUND_SPIN_DRAG * STEP);
    /** The floats of a hard landing kept until they are asked for ({@link #landings}): where, how fast, its size. */
    private static final int LANDING = 5;

    /**
     * The top of what pieces land on at a world position: ground, roof, deck or rock, foliage giving way; NaN off the
     * board. Pieces start at the height {@code from}, and look past a structure above it: a bridge deck, which they can
     * pass under, and over the meeple's own ground ({@code own}) the building roof that covered it.
     */
    interface Ground {
        float height(float x, float y, float from, boolean own);
    }

    /**
     * The ground as a table, for pieces too many to ask a {@link Ground} for the surface under each: every hex's floor by
     * its board index (column * {@code rows} + row, as HexTile#at), on a board of {@code columns} by {@code rows} hexes
     * {@code width} by {@code height}. A point takes its hex's floor, one array read, read anew each time; past the
     * board's edge there is none. It remembers the hex its last point lay over, where the next one mostly lies too: for
     * one thread at a time.
     */
    static final class Floors {
        private final float[] heights;
        private final int columns, rows;
        private final float width, height;
        /** Half a hex's height, a quarter of its width, and the length of the two: its slanted edges' gradient. */
        private final float tall, wide, slant;
        /** The hex the last point lay over: its middle, and its board index, negative past the board's edge. */
        private float middleX = Float.NaN, middleY;
        private int index;

        Floors(float[] heights, int columns, int rows, float width, float height) {
            this.heights = heights;
            this.columns = columns;
            this.rows = rows;
            this.width = width;
            this.height = height;
            tall = height / 2;
            wide = width / 4;
            slant = (float) Math.hypot(tall, wide);
        }

        /** The floor of the hex that holds (x, y), the hex HexTile#at finds. */
        float at(float x, float y) {
            if (!holds(x, y, 0)) { find(x, y); }
            return index < 0 ? NOWHERE : heights[index];
        }

        /** The one floor under everything within {@code reach} of (x, y); NaN when that lies over more than one hex. */
        float under(float x, float y, float reach) {
            if (!holds(x, y, 0)) { find(x, y); }
            return !holds(x, y, reach) ? Float.NaN : index < 0 ? NOWHERE : heights[index];
        }

        /** Whether the remembered hex holds everything within {@code reach} of (x, y): HexTile#contains, without its hair. */
        private boolean holds(float x, float y, float reach) {
            float dx = Math.abs(x - middleX), dy = Math.abs(y - middleY);
            return dy + reach <= tall && tall * dx + wide * dy + reach * slant <= 2 * tall * wide;
        }

        /** Remembers the hex that holds (x, y). */
        private void find(float x, float y) {
            // Cube coordinates: the column, the row less half the column, and what makes their sum nothing; each rounded,
            // then the one that moved furthest worked out from the other two.
            float across = (x - .5f * width) / (.75f * width);
            float down = (-y - tall) / height - .5f * across;
            int column = (int) Math.floor(across + .5f), level = (int) Math.floor(down + .5f);
            int third = (int) Math.floor(.5f - across - down);
            float offColumn = Math.abs(column - across), offLevel = Math.abs(level - down);
            float offThird = Math.abs(third + across + down);
            if (offColumn > offLevel && offColumn > offThird) {
                column = -level - third;
            } else if (offLevel > offThird) {
                level = -column - third;
            }
            int row = level + (column >> 1);
            // HexTile#centerX and #centerY.
            middleX = column * width * .75f + width / 2;
            middleY = -(row * height + (column & 1) * height / 2 + height / 2);
            index = column < 0 || column >= columns || row < 0 || row >= rows ? -1 : column * rows + row;
        }
    }

    /** A piece meeting the ground hard: where, how fast, and its size. */
    record Landing(Vector3 point, float speed, float size) { }

    /**
     * Heights sampled from a {@link Ground}, by the column and row of a square grid ({@link MeepleDebris#key}): an
     * open-addressed table of primitives, which a lookup neither boxes nor allocates. A sample is never NaN, which marks
     * a free slot.
     */
    private static final class Samples {
        private long[] keys = new long[512];
        private float[] heights = new float[512];
        private int size;

        Samples() { Arrays.fill(heights, Float.NaN); }

        /** The sample's height, NaN when it was not taken yet. */
        float get(long key) {
            int mask = keys.length - 1;
            for (int slot = slot(key, mask);; slot = slot + 1 & mask) {
                float height = heights[slot];
                if (keys[slot] == key || Float.isNaN(height)) { return height; }
            }
        }

        /** Keeps a sample it does not hold yet. */
        void put(long key, float height) {
            if (2 * ++size > keys.length) {
                long[] oldKeys = keys;
                float[] oldHeights = heights;
                keys = new long[2 * oldKeys.length];
                heights = new float[2 * oldKeys.length];
                Arrays.fill(heights, Float.NaN);
                for (int slot = 0; slot < oldKeys.length; slot++) {
                    if (!Float.isNaN(oldHeights[slot])) { place(oldKeys[slot], oldHeights[slot]); }
                }
            }
            place(key, height);
        }

        private void place(long key, float height) {
            int mask = keys.length - 1;
            int slot = slot(key, mask);
            while (!Float.isNaN(heights[slot])) { slot = slot + 1 & mask; }
            keys[slot] = key;
            heights[slot] = height;
        }

        private static int slot(long key, int mask) {
            return (int) (key * 0x9E3779B97F4A7C15L >>> 40) & mask;
        }
    }

    /**
     * The model whose nodes the pieces are ({@link #pose()}), one a body in their order, and where their frame lies in
     * it and back; null for boxes, which the caller draws itself ({@link #pose(int, float[], int)}).
     */
    private final ModelInstance instance;
    private final Matrix4 frame, inverse;
    private final Node[] nodes;

    /**
     * The bodies, each by its number: three floats a body for where its middle was before it broke ({@code rest}) and
     * is now, its velocity and its spin (radians per second about its axis), four for its turn from how it stood.
     */
    private int count;
    private float[] rest = { }, position = { }, velocity = { }, spin = { }, turn = { };
    /**
     * Its turn in one step of its spin (x, y, z, w), which holds while that spin does ({@code steady}): in the air,
     * where nothing changes it. On the ground it is worked out anew each step.
     */
    private float[] twist = { };
    private boolean[] steady = { };
    /** Its reach from its middle, and its inertia for a unit of mass. */
    private float[] radius = { }, inertia = { };
    /** Its bounds' volume, which stands for its mass when a model's pieces are thrown; none for boxes. */
    private float[] volume = { };
    /**
     * The clock's seconds at which it starts to move, the moment the structure under it gives way; how long it has lain
     * still; and the seconds it has fallen below its structure's floor past the board's edge, shrinking away.
     */
    private float[] release = { }, still = { }, gone = { };
    private boolean[] asleep = { }, touching = { };
    /**
     * Boxes only: how far above its hex's floor a box's ground lies, the mound of a pile it lands on ({@link #handed});
     * and whether it has come to rest and lies down onto its flattest side ({@link #lie}).
     */
    private float[] lift = { };
    private boolean[] lying = { };
    /**
     * What the bodies rest on, about their middles as they stood: a body's support points, three floats each, {@code
     * size} of them from {@code first} on; for a box ({@link #BOX}) its three half axes, whose sums either way are its
     * corners.
     */
    private float[] points = { };
    private int[] first = { }, size = { };
    private int pointFloats;

    /** Everything as it stood: its bounds. */
    private final BoundingBox all = new BoundingBox().inf();
    /** The ground the pieces stood on: past the board's edge they shrink away below it. */
    private float floor;
    /**
     * The pieces' own ground, out to {@code reach} from their {@code middle}, and the height just above the ground they
     * stood on, from which they look for what they land on ({@link Ground}).
     */
    private final Vector3 middle = new Vector3();
    private float reach, from;
    /** The ground of the advance under way: one of the two; and what was sampled of a {@link Ground}, coarse and fine. */
    private Ground ground;
    private Floors floors;
    private Samples heights, steps;
    /** A structure's pieces, whose hard landings raise dust ({@link #landings}), {@link #LANDING} floats each. */
    private final boolean collapsing;
    private float[] landed = { };
    private int landedFloats;
    private float pending, seconds;
    /** How far the pieces have sunk below where they lie, as a share of their size. */
    private float sunk;
    /** A step's own numbers: a point turned with its body ({@link #turned}), and the deepest one's depth and arm ({@link #dip}). */
    private float turnedX, turnedY, turnedZ, deepest, armX, armY, armZ;
    private final float[] posed = new float[8];
    private final Matrix4 motion = new Matrix4();
    private final Quaternion orientation = new Quaternion();
    private final Vector3 scratch = new Vector3();

    /**
     * Throws the pieces under {@code pieces} from where they stand in {@code instance}.
     *
     * @param supports each piece's support points in the cut's frame (MeepleShatter.Cut#supports), in node order
     * @param hit horizontal, away from the hits, as long as the damage they dealt; null to burst out evenly
     */
    MeepleDebris(ModelInstance instance, Node pieces, List<List<Vector3>> supports, Vector3 hit, int seed) {
        this(instance, pieces, supports, false);
        // The blast goes off low in the body: it lifts what is above it and sweeps out what is beside it.
        var bounds = bounds();
        var blast = new Vector3(middle.x, middle.y, bounds.isValid() ? MathUtils.lerp(bounds.min.z, bounds.max.z, .25f) : 0);
        float average = averageVolume();
        var kick = hit == null ? new Vector3() : new Vector3(hit).scl(KICK_PER_DAMAGE).limit(MAX_KICK);
        var push = new Vector3();
        var tumble = new Vector3();
        for (int body = 0; body < count; body++) {
            int at = 3 * body;
            int noise = seed * 977 + body * 131;
            push.set(rest[at], rest[at + 1], rest[at + 2]).sub(blast);
            if (push.len2() < 1e-4f) { push.set(Vector3.X).rotate(Vector3.Z, UnitAttack.noise(noise) * 360); }
            push.nor().scl(BLAST).add(kick);
            // The push grows with a piece's face and its mass with its volume: a shard flies faster than a chunk. An
            // uneven break scatters both.
            float speed = MathUtils.clamp((float) Math.cbrt(average / volume[body]), .5f, 2)
                  * (.7f + .6f * UnitAttack.noise(noise + 1));
            set(velocity, at, push.scl(speed));
            set(spin, at, tumble.set(UnitAttack.noise(noise + 2) - .5f, UnitAttack.noise(noise + 3) - .5f,
                  UnitAttack.noise(noise + 4) - .5f).nor().scl((3 + 9 * UnitAttack.noise(noise + 5)) * speed));
        }
    }

    /**
     * No bodies yet, and no model: boxes, a structure's fragments, which the caller adds ({@link #box}), steps on either
     * ground and draws itself from their poses ({@link #pose(int, float[], int)}).
     */
    MeepleDebris() {
        instance = null;
        frame = null;
        inverse = null;
        nodes = null;
        collapsing = true;
    }

    private MeepleDebris(ModelInstance instance, Node pieces, List<List<Vector3>> supports, boolean structure) {
        this.instance = instance;
        instance.calculateTransforms();
        frame = new Matrix4(instance.transform).mul(pieces.globalTransform);
        inverse = new Matrix4(frame).inv();
        collapsing = structure;
        reserve(pieces.getChildCount());
        nodes = new Node[pieces.getChildCount()];
        points = new float[3 * supports.stream().mapToInt(List::size).sum()];
        var point = new Vector3();
        var center = new Vector3();
        for (Node piece : pieces.getChildren()) {
            piece.isAnimated = true;
            int body = count++;
            nodes[body] = piece;
            var box = new BoundingBox().inf();
            first[body] = pointFloats;
            size[body] = supports.get(body).size();
            for (Vector3 support : supports.get(body)) {
                box.ext(point.set(support).mul(frame));
                set(points, pointFloats, point);
                pointFloats += 3;
            }
            all.ext(box);
            box.getCenter(center);
            volume[body] = Math.max(1, box.getWidth() * box.getHeight() * box.getDepth());
            float farthest = 1;
            for (int at = first[body]; at < pointFloats; at += 3) {
                points[at] -= center.x;
                points[at + 1] -= center.y;
                points[at + 2] -= center.z;
                farthest = Math.max(farthest, Vector3.len(points[at], points[at + 1], points[at + 2]));
            }
            stand(body, center, farthest);
        }
        bound();
    }

    /**
     * Adds a box, a structure's fragment, and returns its number: its middle where it stands, its half size along its
     * own three axes, those axes' turn from the board's, how fast it is thrown and spins (radians per second about the
     * spin's axis) and the seconds from now until it starts to move. It rests on its eight corners.
     */
    int box(Vector3 center, Vector3 half, Quaternion axes, Vector3 thrown, Vector3 spinning, float delay) {
        if (count == asleep.length) { reserve(Math.max(16, 2 * count)); }
        if (pointFloats + 9 > points.length) { points = Arrays.copyOf(points, Math.max(144, 2 * points.length)); }
        int body = count++;
        first[body] = pointFloats;
        size[body] = BOX;
        for (int axis = 0; axis < 3; axis++) {
            scratch.set(axis == 0 ? half.x : 0, axis == 1 ? half.y : 0, axis == 2 ? half.z : 0);
            set(points, pointFloats, axes.transform(scratch));
            pointFloats += 3;
        }
        int at = first[body];
        for (int index = 0; index < 8; index++) {
            float a = (index & 1) == 0 ? 1 : -1, b = (index & 2) == 0 ? 1 : -1, c = (index & 4) == 0 ? 1 : -1;
            all.ext(center.x + a * points[at] + b * points[at + 3] + c * points[at + 6],
                  center.y + a * points[at + 1] + b * points[at + 4] + c * points[at + 7],
                  center.z + a * points[at + 2] + b * points[at + 5] + c * points[at + 8]);
        }
        stand(body, center, Math.max(1, half.len()));
        set(velocity, 3 * body, thrown);
        set(spin, 3 * body, spinning);
        release[body] = seconds + delay;
        bound();
        return body;
    }

    /**
     * A box handed over in flight ({@link GpuDestruction}): its turn so far from how it stood, about which {@link #box}
     * took its axes, and how far above its hex's floor it comes to rest, on a pile's mound.
     */
    void handed(int body, Quaternion turned, float mound) {
        int to = 4 * body;
        turn[to] = turned.x;
        turn[to + 1] = turned.y;
        turn[to + 2] = turned.z;
        turn[to + 3] = turned.w;
        lift[body] = mound;
    }

    /** Room for this many bodies. */
    private void reserve(int bodies) {
        rest = Arrays.copyOf(rest, 3 * bodies);
        position = Arrays.copyOf(position, 3 * bodies);
        velocity = Arrays.copyOf(velocity, 3 * bodies);
        spin = Arrays.copyOf(spin, 3 * bodies);
        turn = Arrays.copyOf(turn, 4 * bodies);
        twist = Arrays.copyOf(twist, 4 * bodies);
        steady = Arrays.copyOf(steady, bodies);
        radius = Arrays.copyOf(radius, bodies);
        inertia = Arrays.copyOf(inertia, bodies);
        if (instance != null) { volume = Arrays.copyOf(volume, bodies); }
        release = Arrays.copyOf(release, bodies);
        still = Arrays.copyOf(still, bodies);
        gone = Arrays.copyOf(gone, bodies);
        asleep = Arrays.copyOf(asleep, bodies);
        touching = Arrays.copyOf(touching, bodies);
        if (instance == null) {
            lift = Arrays.copyOf(lift, bodies);
            lying = Arrays.copyOf(lying, bodies);
        }
        first = Arrays.copyOf(first, bodies);
        size = Arrays.copyOf(size, bodies);
    }

    /** A new body at rest where its middle stands, reaching {@code farthest} from it. */
    private void stand(int body, Vector3 center, float farthest) {
        set(rest, 3 * body, center);
        set(position, 3 * body, center);
        turn[4 * body + 3] = 1;
        radius[body] = farthest;
        inertia[body] = .4f * farthest * farthest;
    }

    /** Takes the pieces' floor, middle and reach from the bounds of everything as it stands. */
    private void bound() {
        floor = all.isValid() ? all.min.z : 0;
        if (all.isValid()) { all.getCenter(middle); }
        reach = all.isValid() ? Math.max(all.getWidth(), all.getHeight()) * .5f + CELL : 0;
        from = floor + CELL;
    }

    private static void set(float[] values, int at, Vector3 vector) {
        values[at] = vector.x;
        values[at + 1] = vector.y;
        values[at + 2] = vector.z;
    }

    private BoundingBox bounds() {
        var bounds = new BoundingBox().inf();
        for (int body = 0; body < count; body++) {
            for (int at = first[body], end = at + 3 * size[body]; at < end; at += 3) {
                bounds.ext(points[at] + rest[3 * body], points[at + 1] + rest[3 * body + 1], points[at + 2] + rest[3 * body + 2]);
            }
        }
        return bounds;
    }

    private float averageVolume() {
        return (float) IntStream.range(0, count).mapToDouble(body -> volume[body]).average().orElse(1);
    }

    /** Runs the pieces on by {@code delta} seconds, in fixed steps, under {@code gravity} standard gravities. */
    void advance(float delta, Ground ground, float gravity) {
        if (heights == null) {
            heights = new Samples();
            steps = new Samples();
        }
        this.ground = ground;
        floors = null;
        advance(delta, gravity);
    }

    /** {@link #advance(float, Ground, float)}, the pieces landing on the hexes' floors. */
    void advance(float delta, Floors floors, float gravity) {
        this.floors = floors;
        ground = null;
        advance(delta, gravity);
    }

    private void advance(float delta, float gravity) {
        pending = Math.min(pending + Math.max(0, delta), STEP * MAX_STEPS);
        while (pending >= STEP) {
            pending -= STEP;
            seconds += STEP;
            step(gravity);
        }
    }

    private void step(float gravity) {
        float fall = GRAVITY * gravity * STEP;
        for (int body = 0; body < count; body++) {
            if (asleep[body] || release[body] > seconds) { continue; }
            int at = 3 * body;
            if (size[body] == BOX && lying[body]) {
                lie(body);
                continue;
            }
            velocity[at + 2] -= fall;
            position[at] += velocity[at] * STEP;
            position[at + 1] += velocity[at + 1] * STEP;
            position[at + 2] += velocity[at + 2] * STEP;
            rotate(body);
            // Past the board's edge nothing holds a piece: below its floor it shrinks to nothing, and is down.
            if (position[at + 2] < floor && height(position[at], position[at + 1]) <= NOWHERE) {
                gone[body] += STEP;
                asleep[body] = gone[body] >= GpuDestruction.EDGE_SECONDS;
                continue;
            }
            boolean contact = collide(body);
            if (contact) {
                // A box loses its speed and its spin on the ground as its fall's closed form does (GpuDestruction).
                boolean box = size[body] == BOX;
                float drag = 1 - (box ? GpuDestruction.GROUND_DRAG : GROUND_DRAG) * STEP;
                float spinDrag = 1 - (box ? GpuDestruction.GROUND_SPIN_DRAG : GROUND_SPIN_DRAG) * STEP;
                velocity[at] *= drag;
                velocity[at + 1] *= drag;
                velocity[at + 2] *= drag;
                spin[at] *= spinDrag;
                spin[at + 1] *= spinDrag;
                spin[at + 2] *= spinDrag;
                steady[body] = false;
                if (box) { topple(body); }
            } else if (still[body] == 0) {
                // In the air, and not counting: a piece settles only as it touches the ground.
                continue;
            }
            // A slow piece rocking on its corners leaves the ground for a step now and then: that keeps its count.
            boolean slow = Vector3.len(velocity[at], velocity[at + 1], velocity[at + 2]) < SLEEP_SPEED + fall
                  && Vector3.len(spin[at], spin[at + 1], spin[at + 2]) < SLEEP_SPIN;
            still[body] = !slow ? 0 : contact ? still[body] + STEP : still[body];
            if (size[body] == BOX) {
                lying[body] = still[body] >= SLEEP_SECONDS;
            } else {
                asleep[body] = still[body] >= SLEEP_SECONDS;
            }
        }
    }

    /**
     * A box on the ground topples onto its flattest side as a landed piece of {@link GpuDestruction} does
     * (tree-instances.glsl), at that fall's rate: a step turns it by a share of the angle left, about the level axis
     * across its thinnest side, and by all of it once it is nearly there. Whether it lies flat now. No slab stays on
     * an edge.
     */
    private boolean topple(int body) {
        int to = 4 * body, point = first[body];
        // Its thinnest half axis, turned with it.
        int thin = point;
        for (int axis = point + 3; axis < point + 9; axis += 3) {
            if (Vector3.len2(points[axis], points[axis + 1], points[axis + 2])
                  < Vector3.len2(points[thin], points[thin + 1], points[thin + 2])) { thin = axis; }
        }
        turned(turn[to], turn[to + 1], turn[to + 2], turn[to + 3], points[thin], points[thin + 1], points[thin + 2]);
        float level = (float) Math.hypot(turnedX, turnedY);
        float left = MathUtils.atan2(level, Math.abs(turnedZ));
        boolean flat = left < .01f;
        if (level > 1e-6f) {
            // About the thin axis crossed with the vertical it leans to.
            float angle = flat ? left : left * LIE_DOWN;
            float side = turnedZ < 0 ? -1 : 1, sin = (float) Math.sin(angle / 2) / level;
            float rx = turnedY * side * sin, ry = -turnedX * side * sin, rw = (float) Math.cos(angle / 2);
            float qx = turn[to], qy = turn[to + 1], qz = turn[to + 2], qw = turn[to + 3];
            turn[to] = rw * qx + rx * qw + ry * qz;
            turn[to + 1] = rw * qy + ry * qw - rx * qz;
            turn[to + 2] = rw * qz + rx * qy - ry * qx;
            turn[to + 3] = rw * qw - rx * qx - ry * qy;
            unit(turn, to);
        }
        return flat;
    }

    /**
     * A box come to rest lies down: it topples the rest of the way ({@link #topple}) and comes down onto its lowest
     * corner on its ground, as fast as a landed piece of {@link GpuDestruction} settles; flat and down, it sleeps.
     */
    private void lie(int body) {
        int at = 3 * body, to = 4 * body, point = first[body];
        boolean flat = topple(body);
        float floor = height(position[at], position[at + 1]);
        if (floor <= NOWHERE) {
            // Nothing under it: it falls on past the board's edge.
            lying[body] = false;
            return;
        }
        // Its reach below its middle as it lies now: the three half axes, each taken the way that points down.
        float below = 0;
        for (int axis = point; axis < point + 9; axis += 3) {
            turned(turn[to], turn[to + 1], turn[to + 2], turn[to + 3], points[axis], points[axis + 1], points[axis + 2]);
            below += Math.abs(turnedZ);
        }
        float rest = floor + lift[body] + below, above = position[at + 2] - rest;
        boolean down = Math.abs(above) < .05f;
        position[at + 2] = down ? rest : rest + above * (float) Math.exp(-GpuDestruction.GROUND_DRAG * STEP);
        asleep[body] = flat && down;
    }

    /**
     * Every box still moving over the board comes to rest now and lies down ({@link #lie}): none rolls on after its
     * fall's pile has settled.
     */
    void settle() {
        for (int body = 0; body < count; body++) {
            if (size[body] != BOX || asleep[body] || lying[body] || release[body] > seconds
                  || height(position[3 * body], position[3 * body + 1]) <= NOWHERE) { continue; }
            lying[body] = true;
        }
    }

    /**
     * Turns the body on by a step of its spin, about the spin's axis: the arithmetic of libGDX's Quaternion (from an
     * axis and an angle, multiplied from the left, made of unit length), operation for operation, so that the pieces
     * move exactly as they did when each step made its vectors and quaternions anew.
     */
    private void rotate(int body) {
        int at = 3 * body, to = 4 * body;
        if (!steady[body]) {
            float x = spin[at], y = spin[at + 1], z = spin[at + 2];
            float length2 = x * x + y * y + z * z;
            float root = (float) Math.sqrt(length2);
            float angle = root * STEP;
            if (!(angle > 0)) { return; }
            if (length2 != 1) {
                float scale = 1 / root;
                x *= scale;
                y *= scale;
                z *= scale;
            }
            float length = Vector3.len(x, y, z);
            if (length == 0) {
                twist[to] = twist[to + 1] = twist[to + 2] = 0;
                twist[to + 3] = 1;
            } else {
                length = 1 / length;
                // There the angle goes to degrees and back, and wraps at a full turn, which a step's never is.
                float radians = angle * MathUtils.radiansToDegrees * MathUtils.degreesToRadians;
                float wrapped = radians < MathUtils.PI2 ? radians : radians % MathUtils.PI2;
                float sin = (float) Math.sin(wrapped / 2), cos = (float) Math.cos(wrapped / 2);
                twist[to] = length * x * sin;
                twist[to + 1] = length * y * sin;
                twist[to + 2] = length * z * sin;
                twist[to + 3] = cos;
                unit(twist, to);
            }
            steady[body] = true;
        }
        float rx = twist[to], ry = twist[to + 1], rz = twist[to + 2], rw = twist[to + 3];
        float qx = turn[to], qy = turn[to + 1], qz = turn[to + 2], qw = turn[to + 3];
        turn[to] = rw * qx + rx * qw + ry * qz - rz * qy;
        turn[to + 1] = rw * qy + ry * qw + rz * qx - rx * qz;
        turn[to + 2] = rw * qz + rz * qw + rx * qy - ry * qx;
        turn[to + 3] = rw * qw - rx * qx - ry * qy - rz * qz;
        unit(turn, to);
    }

    /** Makes the quaternion at {@code at} (x, y, z, w) of unit length, as Quaternion#nor does. */
    private static void unit(float[] values, int at) {
        float x = values[at], y = values[at + 1], z = values[at + 2], w = values[at + 3];
        float length = x * x + y * y + z * z + w * w;
        if (length != 0 && !MathUtils.isEqual(length, 1)) {
            length = (float) Math.sqrt(length);
            values[at + 3] = w / length;
            values[at] = x / length;
            values[at + 1] = y / length;
            values[at + 2] = z / length;
        }
    }

    /**
     * Turns the point (x, y, z) by the quaternion q, into {@link #turnedX}, Y and Z: Quaternion#transform, operation for
     * operation (the point times the conjugate, then the quaternion times that), without its objects.
     */
    private void turned(float qx, float qy, float qz, float qw, float x, float y, float z) {
        float cx = -qx, cy = -qy, cz = -qz;
        float px = 0 * cx + x * qw + y * cz - z * cy;
        float py = 0 * cy + y * qw + z * cx - x * cz;
        float pz = 0 * cz + z * qw + x * cy - y * cx;
        float pw = 0 * qw - x * cx - y * cy - z * cz;
        turnedX = qw * px + qx * pw + qy * pz - qz * py;
        turnedY = qw * py + qy * pw + qz * px - qx * pz;
        turnedZ = qw * pz + qz * pw + qx * py - qy * px;
    }

    /** The deepest point below the ground pushes the piece out along the ground's normal there, and bounces it. */
    private boolean collide(int body) {
        int at = 3 * body, to = 4 * body;
        float x = position[at], y = position[at + 1], z = position[at + 2];
        float qx = turn[to], qy = turn[to + 1], qz = turn[to + 2], qw = turn[to + 3];
        // On the hexes' floors, a body that lies over one hex, with the heights its ground's slope is taken from, has
        // level ground under it: above that it touches nothing.
        float level = floors == null ? Float.NaN : floors.under(x, y, radius[body] + CELL * .5f);
        // A box's ground lies its lift above the board: the box is tested that much lower.
        if (size[body] == BOX) { z -= lift[body]; }
        if (z - radius[body] > level) {
            touching[body] = false;
            return false;
        }
        boolean flat = !Float.isNaN(level);
        deepest = 0;
        int point = first[body];
        if (size[body] == BOX) {
            // Its half axes turned with it: their sums either way are its corners.
            turned(qx, qy, qz, qw, points[point], points[point + 1], points[point + 2]);
            float ax = turnedX, ay = turnedY, az = turnedZ;
            turned(qx, qy, qz, qw, points[point + 3], points[point + 4], points[point + 5]);
            float bx = turnedX, by = turnedY, bz = turnedZ;
            turned(qx, qy, qz, qw, points[point + 6], points[point + 7], points[point + 8]);
            float cx = turnedX, cy = turnedY, cz = turnedZ;
            if (flat) {
                // Over level ground its lowest corner is the deepest: each half axis taken the way that points down.
                float a = az > 0 ? -1 : 1, b = bz > 0 ? -1 : 1, c = cz > 0 ? -1 : 1;
                armX = a * ax + b * bx + c * cx;
                armY = a * ay + b * by + c * cy;
                armZ = a * az + b * bz + c * cz;
                deepest = Math.max(0, level - z - armZ);
            } else {
                for (int index = 0; index < 8; index++) {
                    float a = (index & 1) == 0 ? 1 : -1, b = (index & 2) == 0 ? 1 : -1, c = (index & 4) == 0 ? 1 : -1;
                    dip(x, y, z, a * ax + b * bx + c * cx, a * ay + b * by + c * cy, a * az + b * bz + c * cz);
                }
            }
        } else {
            for (int end = point + 3 * size[body]; point < end; point += 3) {
                turned(qx, qy, qz, qw, points[point], points[point + 1], points[point + 2]);
                dip(x, y, z, turnedX, turnedY, turnedZ);
            }
        }
        boolean touched = touching[body];
        touching[body] = deepest > 0;
        if (!touching[body]) { return false; }
        // The ground's normal under the point, from its heights half a cell to each side; straight up from level ground.
        float nx = 0, ny = 0, nz = 1;
        if (!flat) {
            float half = CELL * .5f, underX = x + armX, underY = y + armY;
            nx = height(underX - half, underY) - height(underX + half, underY);
            ny = height(underX, underY - half) - height(underX, underY + half);
            nz = CELL;
            float length2 = nx * nx + ny * ny + nz * nz;
            if (length2 != 0 && length2 != 1) {
                float scale = 1 / (float) Math.sqrt(length2);
                nx *= scale;
                ny *= scale;
                nz *= scale;
            }
        }
        // A wall's top is far above the point: leave it a cell at a time, sideways.
        float out = Math.min(deepest, CELL);
        position[at] = x + nx * out;
        position[at + 1] = y + ny * out;
        position[at + 2] = size[body] == BOX ? z + nz * out + lift[body] : z + nz * out;
        // How fast the point moves: the spin about its arm, and the body's velocity.
        float vx = velocity[at], vy = velocity[at + 1], vz = velocity[at + 2];
        float sx = spin[at], sy = spin[at + 1], sz = spin[at + 2];
        float contactX = sy * armZ - sz * armY + vx, contactY = sz * armX - sx * armZ + vy, contactZ = sx * armY - sy * armX + vz;
        float into = contactX * nx + contactY * ny + contactZ * nz;
        if (into >= 0) { return true; }
        if (!touched && -into >= HARD_LANDING && collapsing) {
            if (landedFloats == landed.length) { landed = Arrays.copyOf(landed, Math.max(16 * LANDING, 2 * landed.length)); }
            landed[landedFloats] = position[at] + armX;
            landed[landedFloats + 1] = position[at + 1] + armY;
            landed[landedFloats + 2] = position[at + 2] + armZ;
            landed[landedFloats + 3] = -into;
            landed[landedFloats + 4] = radius[body];
            landedFloats += LANDING;
        }
        float leverX = armY * nz - armZ * ny, leverY = armZ * nx - armX * nz, leverZ = armX * ny - armY * nx;
        float mass = inertia[body];
        float push = -(1 + RESTITUTION) * into / (1 + (leverX * leverX + leverY * leverY + leverZ * leverZ) / mass);
        vx += nx * push;
        vy += ny * push;
        vz += nz * push;
        float turning = push / mass;
        sx += leverX * turning;
        sy += leverY * turning;
        sz += leverZ * turning;
        // What is left of the point's motion along the ground, which friction holds back through the arm.
        contactX += nx * -into;
        contactY += ny * -into;
        contactZ += nz * -into;
        float speed = Vector3.len(contactX, contactY, contactZ);
        if (speed > 1e-4f) {
            float inverse = 1 / speed;
            contactX *= inverse;
            contactY *= inverse;
            contactZ *= inverse;
            float twistX = armY * contactZ - armZ * contactY, twistY = armZ * contactX - armX * contactZ;
            float twistZ = armX * contactY - armY * contactX;
            float drag = Math.min(FRICTION * push, speed / (1 + (twistX * twistX + twistY * twistY + twistZ * twistZ) / mass));
            vx += contactX * -drag;
            vy += contactY * -drag;
            vz += contactZ * -drag;
            float twisting = -drag / mass;
            sx += twistX * twisting;
            sy += twistY * twisting;
            sz += twistZ * twisting;
        }
        velocity[at] = vx;
        velocity[at + 1] = vy;
        velocity[at + 2] = vz;
        spin[at] = sx;
        spin[at + 1] = sy;
        spin[at + 2] = sz;
        return true;
    }

    /**
     * A support point, (tx, ty, tz) from its body's middle at (x, y, z): how far below the ground it lies; the deepest
     * so far is kept, with its arm.
     */
    private void dip(float x, float y, float z, float tx, float ty, float tz) {
        float below = height(x + tx, y + ty) - z - tz;
        if (below > deepest) {
            deepest = below;
            armX = tx;
            armY = ty;
            armZ = tz;
        }
    }

    /**
     * The board's height at a world position. Of the hexes' floors, the floor of the hex there. Of a {@link Ground},
     * bilinear between samples taken the first time a piece comes near; across a step (a cliff, a wall, a deck's edge)
     * the nearest of samples four times as fine, which keep the step sharp and its line in place instead of a ramp or a
     * band a piece would sink into.
     */
    private float height(float x, float y) {
        if (floors != null) { return floors.at(x, y); }
        float column = x / CELL, row = y / CELL;
        // Not MathUtils.floor: it is exact only above -16384, and a tall board's y runs far below.
        int left = (int) Math.floor(column), bottom = (int) Math.floor(row);
        float across = column - left, up = row - bottom;
        float lowLeft = sample(heights, CELL, left, bottom), lowRight = sample(heights, CELL, left + 1, bottom);
        float highLeft = sample(heights, CELL, left, bottom + 1);
        float highRight = sample(heights, CELL, left + 1, bottom + 1);
        float rise = Math.max(Math.max(lowLeft, lowRight), Math.max(highLeft, highRight))
              - Math.min(Math.min(lowLeft, lowRight), Math.min(highLeft, highRight));
        if (rise > CLIFF) {
            return sample(steps, FINE, Math.round(x / FINE), Math.round(y / FINE));
        }
        return MathUtils.lerp(MathUtils.lerp(lowLeft, lowRight, across), MathUtils.lerp(highLeft, highRight, across), up);
    }

    private float sample(Samples cache, float spacing, int column, int row) {
        long key = key(column, row);
        float height = cache.get(key);
        if (!Float.isNaN(height)) { return height; }
        float x = column * spacing, y = row * spacing;
        height = ground.height(x, y, from, Vector3.dst2(x, y, 0, middle.x, middle.y, 0) < reach * reach);
        height = Float.isFinite(height) ? height : NOWHERE;
        cache.put(key, height);
        return height;
    }

    private static long key(int column, int row) {
        return ((long) column << 32) ^ (row & 0xffffffffL);
    }

    /** The hard landings since the last call, oldest first. */
    List<Landing> landings() {
        if (landedFloats == 0) { return List.of(); }
        var result = new Landing[landedFloats / LANDING];
        for (int index = 0; index < result.length; index++) {
            int at = index * LANDING;
            result[index] = new Landing(new Vector3(landed[at], landed[at + 1], landed[at + 2]), landed[at + 3], landed[at + 4]);
        }
        landedFloats = 0;
        return List.of(result);
    }

    /** Whether every piece has come to rest. */
    boolean settled() { return awake() == 0; }

    /** How many pieces have not come to rest. */
    int awake() {
        int awake = 0;
        for (int body = 0; body < count; body++) {
            if (!asleep[body]) { awake++; }
        }
        return awake;
    }

    /** The seconds its pieces have run, in whole steps: what {@link #advance} was given, less under a step still owed. */
    float seconds() { return seconds; }

    /**
     * Lowers each piece below where it lies by {@code share} of its own size, as rubble settling into the ground: at 1
     * every piece, large or small, has gone together.
     */
    void sink(float share) { sunk = share; }

    /**
     * A body's pose, eight floats from {@code at}: where its middle is, its size (1, less as it shrinks away past the
     * board's edge), and its turn from how it stood, a quaternion (x, y, z, w).
     */
    void pose(int body, float[] pose, int at) {
        pose[at] = position[3 * body];
        pose[at + 1] = position[3 * body + 1];
        pose[at + 2] = position[3 * body + 2] - sunk * 2 * radius[body];
        // Never quite to nothing: a singular transform has no normal matrix. Sinking, it shrinks too: at the board's
        // edge nothing pokes out through the board's side.
        pose[at + 3] = Math.max(1e-3f, (1 - Interpolation.smooth.apply(Math.min(1, gone[body] / GpuDestruction.EDGE_SECONDS)))
              * (1 - sunk));
        System.arraycopy(turn, 4 * body, pose, at + 4, 4);
    }

    /** Draws a model's pieces where they are. */
    void pose() {
        if (instance == null) { return; }
        for (int body = 0; body < count; body++) {
            pose(body, posed, 0);
            motion.setToTranslation(posed[0], posed[1], posed[2]).rotate(orientation.set(posed[4], posed[5], posed[6], posed[7]))
                  .scale(posed[3], posed[3], posed[3]).translate(-rest[3 * body], -rest[3 * body + 1], -rest[3 * body + 2]);
            nodes[body].localTransform.set(inverse).mul(motion).mul(frame);
        }
        instance.calculateTransforms();
    }
}
