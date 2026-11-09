/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.LongMap;

/**
 * Broken pieces as rigid bodies: a meeple's thrown by its explosion, away from the hits and out from its middle; a
 * structure's dropped where they stood as it gives way under its own weight ({@link #collapse}). Under a tabletop
 * gravity they bounce off the ground and scenery under them until they come to rest. They touch the board through a
 * height field sampled from it as they reach it, not each other. Presentation only.
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
    /**
     * A collapse: each storey above the foot gives way this many seconds after the one below it; its top pieces spill
     * outward at most this fast, in world units per second, and tumble at up to this many radians per second. Its foot
     * crumbles where it stands.
     */
    private static final float STOREY_RELEASE = .04f, SPILL = 16, TUMBLE = 4;
    /**
     * How fast, in radians per second, a collapsing building's pieces start to topple outward, and how fast, in world
     * units per second, its crushed foot bursts out.
     */
    private static final float TOPPLE_OUT = 2.5f, CRUSH = 30;
    /**
     * A deck breaks from where it fails across its whole length in this many seconds, each piece turning down about its
     * far end at this many radians per second: down within a second, as fast as a mek's death (user, 2026-11-09: "5.5sec
     * for a bridge to collapse seems slow").
     */
    private static final float SPAN_RELEASE = .12f, HINGE = 5;
    /**
     * The strongest hit's push at full strength, in world units per second, more on its side and higher up, and the
     * spin it topples the pieces with: they fall away from it, without flying off.
     */
    private static final float PUSH = 40, TOPPLE = 4;
    /** How fast a unit's weight drives the pieces right under it down, in world units per second. */
    private static final float LOAD_DROP = 40;
    /** A piece meeting the ground at least this fast, in world units per second, raises dust ({@link #landings}). */
    private static final float HARD_LANDING = 70;
    /** The height field's spacing, and how far above the meeple's ground its pieces look for what they land on. */
    static final float CELL = 2;
    /**
     * A rise across one cell steeper than this, 45 degrees, is a step, not a slope: interpolated, the crest of a steeper
     * slope would cut its corner, and pieces resting there would sink into it.
     */
    private static final float CLIFF = CELL;
    /** The spacing of the samples that place a step's line. */
    private static final float FINE = CELL / 4;

    /**
     * The top of what pieces land on at a world position: ground, roof, deck or rock, foliage giving way; NaN off the
     * board. Pieces start at the height {@code from}, and look past a structure above it: a bridge deck, which they can
     * pass under, and over the meeple's own ground ({@code own}) the building roof that covered it.
     */
    interface Ground {
        float height(float x, float y, float from, boolean own);
    }

    /**
     * A structure giving way ({@link #collapse}): the middle of its foot ({@code base}) and the height of one of its
     * storeys; for a deck between supports ({@code span}), which fails where its loads stand or in its middle and hinges
     * down from its ends. {@code from}: where its strongest hit came from, with that hit's {@code strength} from 0 to 1;
     * null and 0 when its own weight brings it down. {@code loads}: where units stood on it.
     */
    record Collapse(Vector3 base, float storey, boolean span, Vector3 from, float strength, List<Vector3> loads) {
        Collapse {
            loads = List.copyOf(loads);
        }
    }

    /** A piece meeting the ground hard: where, how fast, and its size. */
    record Landing(Vector3 point, float speed, float size) { }

    private static final class Body {
        final Node node;
        /** Where its middle was before it broke, and its outermost points around that middle. */
        final Vector3 rest;
        final Vector3[] points;
        /** Its bounds' volume, which stands for its mass, and its reach from its middle. */
        final float volume, radius;
        final float inertia;
        final Vector3 position, velocity = new Vector3(), spin = new Vector3();
        final Quaternion orientation = new Quaternion();
        /** Seconds before it starts to move: the moment the structure under it gives way. */
        float release;
        float still;
        boolean asleep, touching;

        Body(Node node, BoundingBox bounds, Vector3[] points) {
            this.node = node;
            rest = bounds.getCenter(new Vector3());
            volume = Math.max(1, bounds.getWidth() * bounds.getHeight() * bounds.getDepth());
            this.points = points;
            position = new Vector3(rest);
            float reach = 1;
            for (Vector3 point : points) { reach = Math.max(reach, point.len()); }
            radius = reach;
            inertia = .4f * reach * reach;
        }
    }

    private final ModelInstance instance;
    private final Matrix4 frame, inverse;
    private final List<Body> bodies = new ArrayList<>();
    /** The ground the pieces stood on, where the board gives none. */
    private final float floor;
    /**
     * The pieces' own ground, out to {@code reach} from their {@code middle}, and the height just above the ground they
     * stood on, from which they look for what they land on ({@link Ground}).
     */
    private final Vector3 middle;
    private final float reach, from;
    private final LongMap<Float> heights = new LongMap<>(), steps = new LongMap<>();
    /** A structure's pieces, whose hard landings raise dust ({@link #landings}). */
    private final boolean collapsing;
    private final List<Landing> landings = new ArrayList<>();
    private float pending, seconds;
    /** How far the pieces have sunk below where they lie, as a share of their size. */
    private float sunk;

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
        for (int index = 0; index < bodies.size(); index++) {
            Body body = bodies.get(index);
            int noise = seed * 977 + index * 131;
            var push = new Vector3(body.rest).sub(blast);
            if (push.len2() < 1e-4f) { push.set(Vector3.X).rotate(Vector3.Z, UnitAttack.noise(noise) * 360); }
            push.nor().scl(BLAST).add(kick);
            // The push grows with a piece's face and its mass with its volume: a shard flies faster than a chunk. An
            // uneven break scatters both.
            float speed = MathUtils.clamp((float) Math.cbrt(average / body.volume), .5f, 2)
                  * (.7f + .6f * UnitAttack.noise(noise + 1));
            body.velocity.set(push).scl(speed);
            body.spin.set(UnitAttack.noise(noise + 2) - .5f, UnitAttack.noise(noise + 3) - .5f, UnitAttack.noise(noise + 4) - .5f)
                  .nor().scl((3 + 9 * UnitAttack.noise(noise + 5)) * speed);
        }
    }

    /**
     * Drops a structure's pieces where they stand: a building gives way from its foot up, its upper storeys spilling
     * outward as they come down onto the rubble below; a deck from where it fails along its length, each piece hinging
     * down from its far end. Only a strong hit pushes them, away from it; a unit's weight drives down the pieces under
     * it first.
     */
    static MeepleDebris collapse(ModelInstance instance, Node pieces, List<List<Vector3>> supports, Collapse collapse,
          int seed) {
        var debris = new MeepleDebris(instance, pieces, supports, true);
        debris.collapse(collapse, seed);
        return debris;
    }

    private MeepleDebris(ModelInstance instance, Node pieces, List<List<Vector3>> supports, boolean structure) {
        this.instance = instance;
        instance.calculateTransforms();
        frame = new Matrix4(instance.transform).mul(pieces.globalTransform);
        inverse = new Matrix4(frame).inv();
        var all = new BoundingBox().inf();
        int index = 0;
        for (Node piece : pieces.getChildren()) {
            piece.isAnimated = true;
            var box = new BoundingBox().inf();
            var points = supports.get(index++).stream().map(point -> new Vector3(point).mul(frame)).toList();
            points.forEach(box::ext);
            all.ext(box);
            var center = box.getCenter(new Vector3());
            bodies.add(new Body(piece, box, points.stream().map(point -> point.sub(center)).toArray(Vector3[]::new)));
        }
        floor = all.isValid() ? all.min.z : 0;
        middle = all.isValid() ? all.getCenter(new Vector3()) : new Vector3();
        reach = all.isValid() ? Math.max(all.getWidth(), all.getHeight()) * .5f + CELL : 0;
        from = floor + CELL;
        collapsing = structure;
    }

    private BoundingBox bounds() {
        var bounds = new BoundingBox().inf();
        for (Body body : bodies) {
            for (Vector3 point : body.points) { bounds.ext(point.x + body.rest.x, point.y + body.rest.y, point.z + body.rest.z); }
        }
        return bounds;
    }

    private float averageVolume() {
        return (float) bodies.stream().mapToDouble(body -> body.volume).average().orElse(1);
    }

    private void collapse(Collapse collapse, int seed) {
        var bounds = bounds();
        float height = Math.max(bounds.isValid() ? bounds.max.z - collapse.base().z : 0, collapse.storey());
        float average = averageVolume();
        // Where a deck fails: under its loads, else in its middle.
        var failure = collapse.loads().isEmpty() ? collapse.base() : collapse.loads().getFirst();
        var away = new Vector3();
        var push = new Vector3();
        for (int index = 0; index < bodies.size(); index++) {
            Body body = bodies.get(index);
            int noise = seed * 977 + index * 131;
            float share = MathUtils.clamp((body.rest.z - collapse.base().z) / height, 0, 1);
            horizontal(away.set(body.rest).sub(collapse.base()), noise);
            float release;
            if (collapse.span()) {
                var toward = horizontal(new Vector3(body.rest).sub(failure), noise + 6);
                float distance = Vector3.dst(body.rest.x, body.rest.y, 0, failure.x, failure.y, 0);
                release = SPAN_RELEASE * MathUtils.clamp(distance / Math.max(reach, 1), 0, 1);
                // The end nearer the failure drops first: the piece turns down about its far end, its middle dropping as
                // that end stays, until gravity takes all of it.
                float hinge = HINGE * (.6f + .8f * UnitAttack.noise(noise + 1));
                float half = 0;
                for (Vector3 point : body.points) { half = Math.max(half, point.dot(toward)); }
                body.spin.set(toward).crs(Vector3.Z).scl(hinge);
                body.velocity.z = -half * hinge;
            } else {
                release = STOREY_RELEASE * Math.max(0, body.rest.z - collapse.base().z) / Math.max(collapse.storey(), 1);
                // The foot is crushed under the weight above it and bursts out, lifting and toppling outward; higher up,
                // the storeys come down onto its wreck, spilling further out and tumbling the higher they stood.
                float foot = (1 - share) * (1 - share);
                body.velocity.set(away).scl((CRUSH * foot + SPILL * share) * (.6f + .8f * UnitAttack.noise(noise + 1)));
                body.velocity.z = CRUSH * .3f * foot * UnitAttack.noise(noise + 9);
                body.spin.set(UnitAttack.noise(noise + 2) - .5f, UnitAttack.noise(noise + 3) - .5f, UnitAttack.noise(noise + 4) - .5f)
                      .nor().scl(TUMBLE * share * (.3f + .7f * UnitAttack.noise(noise + 5)))
                      .add(new Vector3(Vector3.Z).crs(away).scl(TOPPLE_OUT * (.5f + foot) * (.5f + UnitAttack.noise(noise + 8))));
            }
            release *= .8f + .4f * UnitAttack.noise(noise + 7);
            for (Vector3 load : collapse.loads()) {
                float distance = Vector3.dst(body.rest.x, body.rest.y, 0, load.x, load.y, 0);
                float under = 1 - MathUtils.clamp(distance / Math.max(reach * .6f, 1), 0, 1);
                body.velocity.z = Math.min(body.velocity.z, -LOAD_DROP * under);
                release *= 1 - .7f * under;
            }
            if (collapse.from() != null && collapse.strength() > 0) {
                // Away from where the hit came from: across the structure from far off, out from a shell landing in it,
                // and down from a unit landing on it from above.
                push.set(body.rest).sub(collapse.from());
                if (collapse.from().z <= bounds.max.z) { push.z = 0; }
                if (push.len2() < 1e-4f) { push.set(away); }
                push.nor();
                // The side facing the hit and the top take most of it: the structure leans away and falls over.
                float facing = MathUtils.clamp(.5f - new Vector3(body.rest).sub(collapse.base()).dot(push) / Math.max(reach, 1), 0, 1);
                float size = MathUtils.clamp((float) Math.cbrt(average / body.volume), .75f, 1.25f);
                body.velocity.mulAdd(push, PUSH * collapse.strength() * (.5f + .3f * facing + .4f * share) * size);
                body.spin.add(new Vector3(Vector3.Z).crs(push).scl(TOPPLE * collapse.strength() * (.5f + share)));
                release *= 1 - .5f * collapse.strength();
            }
            body.release = release;
        }
    }

    /** The vector's level part, of unit length; a vector straight up or down takes a direction from {@code noise}. */
    private static Vector3 horizontal(Vector3 vector, int noise) {
        vector.z = 0;
        if (vector.len2() < 1e-4f) { vector.set(Vector3.X).rotate(Vector3.Z, UnitAttack.noise(noise) * 360); }
        return vector.nor();
    }

    /** Runs the pieces on by {@code delta} seconds, in fixed steps, under {@code gravity} standard gravities. */
    void advance(float delta, Ground ground, float gravity) {
        pending = Math.min(pending + Math.max(0, delta), STEP * MAX_STEPS);
        while (pending >= STEP) {
            pending -= STEP;
            seconds += STEP;
            step(ground, gravity);
        }
    }

    private void step(Ground ground, float gravity) {
        for (Body body : bodies) {
            if (body.asleep || body.release > seconds) { continue; }
            body.velocity.z -= GRAVITY * gravity * STEP;
            body.position.mulAdd(body.velocity, STEP);
            float angle = body.spin.len() * STEP;
            if (angle > 0) {
                body.orientation.mulLeft(new Quaternion(new Vector3(body.spin).nor(), angle * MathUtils.radiansToDegrees)).nor();
            }
            boolean contact = collide(body, ground);
            if (contact) {
                body.velocity.scl(1 - GROUND_DRAG * STEP);
                body.spin.scl(1 - GROUND_SPIN_DRAG * STEP);
            }
            // A slow piece rocking on its corners leaves the ground for a step now and then: that keeps its count.
            boolean slow = body.velocity.len() < SLEEP_SPEED + GRAVITY * gravity * STEP && body.spin.len() < SLEEP_SPIN;
            body.still = !slow ? 0 : contact ? body.still + STEP : body.still;
            body.asleep = body.still >= SLEEP_SECONDS;
        }
    }

    /** The deepest point below the ground pushes the piece out along the ground's normal there, and bounces it. */
    private boolean collide(Body body, Ground ground) {
        float deepest = 0;
        Vector3 arm = null;
        var turned = new Vector3();
        for (Vector3 point : body.points) {
            body.orientation.transform(turned.set(point));
            float below = height(body.position.x + turned.x, body.position.y + turned.y, ground) - body.position.z - turned.z;
            if (below > deepest) {
                deepest = below;
                arm = new Vector3(turned);
            }
        }
        boolean touched = body.touching;
        body.touching = arm != null;
        if (arm == null) { return false; }
        var normal = normal(body.position.x + arm.x, body.position.y + arm.y, ground);
        // A wall's top is far above the point: leave it a cell at a time, sideways.
        body.position.mulAdd(normal, Math.min(deepest, CELL));
        var contact = new Vector3(body.spin).crs(arm).add(body.velocity);
        float into = contact.dot(normal);
        if (into >= 0) { return true; }
        if (!touched && -into >= HARD_LANDING && collapsing) {
            landings.add(new Landing(new Vector3(body.position).add(arm), -into, body.radius));
        }
        var lever = new Vector3(arm).crs(normal);
        float push = -(1 + RESTITUTION) * into / (1 + lever.len2() / body.inertia);
        body.velocity.mulAdd(normal, push);
        body.spin.mulAdd(lever, push / body.inertia);
        var slide = contact.mulAdd(normal, -into);
        float speed = slide.len();
        if (speed > 1e-4f) {
            slide.scl(1 / speed);
            var twist = new Vector3(arm).crs(slide);
            float drag = Math.min(FRICTION * push, speed / (1 + twist.len2() / body.inertia));
            body.velocity.mulAdd(slide, -drag);
            body.spin.mulAdd(twist, -drag / body.inertia);
        }
        return true;
    }

    /**
     * The board's height at a world position, bilinear between samples taken the first time a piece comes near; across a
     * step (a cliff, a wall, a deck's edge) the nearest of samples four times as fine, which keep the step sharp and its
     * line in place instead of a ramp or a band a piece would sink into.
     */
    private float height(float x, float y, Ground ground) {
        float column = x / CELL, row = y / CELL;
        // Not MathUtils.floor: it is exact only above -16384, and a tall board's y runs far below.
        int left = (int) Math.floor(column), bottom = (int) Math.floor(row);
        float across = column - left, up = row - bottom;
        float lowLeft = sample(heights, CELL, left, bottom, ground), lowRight = sample(heights, CELL, left + 1, bottom, ground);
        float highLeft = sample(heights, CELL, left, bottom + 1, ground);
        float highRight = sample(heights, CELL, left + 1, bottom + 1, ground);
        float rise = Math.max(Math.max(lowLeft, lowRight), Math.max(highLeft, highRight))
              - Math.min(Math.min(lowLeft, lowRight), Math.min(highLeft, highRight));
        if (rise > CLIFF) {
            return sample(steps, FINE, Math.round(x / FINE), Math.round(y / FINE), ground);
        }
        return MathUtils.lerp(MathUtils.lerp(lowLeft, lowRight, across), MathUtils.lerp(highLeft, highRight, across), up);
    }

    private float sample(LongMap<Float> cache, float spacing, int column, int row, Ground ground) {
        long key = key(column, row);
        Float cached = cache.get(key);
        if (cached != null) { return cached; }
        float x = column * spacing, y = row * spacing;
        float height = ground.height(x, y, from, Vector3.dst2(x, y, 0, middle.x, middle.y, 0) < reach * reach);
        height = Float.isFinite(height) ? height : floor;
        cache.put(key, height);
        return height;
    }

    private static long key(int column, int row) {
        return ((long) column << 32) ^ (row & 0xffffffffL);
    }

    private Vector3 normal(float x, float y, Ground ground) {
        float half = CELL * .5f;
        return new Vector3(height(x - half, y, ground) - height(x + half, y, ground),
              height(x, y - half, ground) - height(x, y + half, ground), CELL).nor();
    }

    /** The hard landings since the last call, oldest first. */
    List<Landing> landings() {
        var result = List.copyOf(landings);
        landings.clear();
        return result;
    }

    /** Whether every piece has come to rest. */
    boolean settled() {
        return bodies.stream().allMatch(body -> body.asleep);
    }

    /**
     * Lowers each piece below where it lies by {@code share} of its own size, as rubble settling into the ground: at 1
     * every piece, large or small, has gone together.
     */
    void sink(float share) { sunk = share; }

    /** Draws the pieces where they are. */
    void pose() {
        var motion = new Matrix4();
        for (Body body : bodies) {
            motion.setToTranslation(body.position.x, body.position.y, body.position.z - sunk * 2 * body.radius).rotate(body.orientation)
                  .translate(-body.rest.x, -body.rest.y, -body.rest.z);
            body.node.localTransform.set(inverse).mul(motion).mul(frame);
        }
        instance.calculateTransforms();
    }
}
