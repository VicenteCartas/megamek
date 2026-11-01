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
 * A broken meeple's pieces as rigid bodies thrown by its explosion: away from the hits and out from its middle, under
 * a tabletop gravity, bouncing off the ground and scenery under them until they come to rest. They touch the board
 * through a height field sampled from it as they reach it, not each other. Presentation only.
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

    private static final class Body {
        final Node node;
        /** Where its middle was before the explosion, and its outermost points around that middle. */
        final Vector3 rest;
        final Vector3[] points;
        /** Its bounds' volume, which stands for its mass. */
        final float volume;
        final float inertia;
        final Vector3 position, velocity = new Vector3(), spin = new Vector3();
        final Quaternion orientation = new Quaternion();
        float still;
        boolean asleep;

        Body(Node node, BoundingBox box, Vector3[] points) {
            this.node = node;
            rest = box.getCenter(new Vector3());
            volume = Math.max(1, box.getWidth() * box.getHeight() * box.getDepth());
            this.points = points;
            position = new Vector3(rest);
            float radius = 1;
            for (Vector3 point : points) { radius = Math.max(radius, point.len()); }
            inertia = .4f * radius * radius;
        }
    }

    private final ModelInstance instance;
    private final Matrix4 frame, inverse;
    private final List<Body> bodies = new ArrayList<>();
    /** The ground the meeple stood on, where the board gives none. */
    private final float floor;
    /**
     * The meeple's own ground, out to {@code reach} from its {@code middle}, and the height just above the ground it stood
     * on, from which the pieces look for what they land on ({@link Ground}).
     */
    private final Vector3 middle;
    private final float reach, from;
    private final LongMap<Float> heights = new LongMap<>(), steps = new LongMap<>();
    private float pending;

    /**
     * Throws the pieces under {@code pieces} from where they stand in {@code instance}.
     *
     * @param hit horizontal, away from the hits, as long as the damage they dealt; null to burst out evenly
     */
    MeepleDebris(ModelInstance instance, Node pieces, Vector3 hit, int seed) {
        this.instance = instance;
        instance.calculateTransforms();
        frame = new Matrix4(instance.transform).mul(pieces.globalTransform);
        inverse = new Matrix4(frame).inv();
        var all = new BoundingBox().inf();
        for (Node piece : pieces.getChildren()) {
            piece.isAnimated = true;
            var box = new BoundingBox().inf();
            var points = MeepleShatter.supports(piece).stream().map(point -> point.mul(frame)).toList();
            points.forEach(box::ext);
            all.ext(box);
            var center = box.getCenter(new Vector3());
            bodies.add(new Body(piece, box, points.stream().map(point -> point.sub(center)).toArray(Vector3[]::new)));
        }
        floor = all.isValid() ? all.min.z : 0;
        middle = all.isValid() ? all.getCenter(new Vector3()) : new Vector3();
        reach = all.isValid() ? Math.max(all.getWidth(), all.getHeight()) * .5f + CELL : 0;
        from = floor + CELL;
        // The blast goes off low in the body: it lifts what is above it and sweeps out what is beside it.
        var blast = new Vector3(middle.x, middle.y, all.isValid() ? MathUtils.lerp(all.min.z, all.max.z, .25f) : 0);
        float average = (float) bodies.stream().mapToDouble(body -> body.volume).average().orElse(1);
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

    /** Runs the pieces on by {@code delta} seconds, in fixed steps, under {@code gravity} standard gravities. */
    void advance(float delta, Ground ground, float gravity) {
        pending = Math.min(pending + Math.max(0, delta), STEP * MAX_STEPS);
        while (pending >= STEP) {
            pending -= STEP;
            step(ground, gravity);
        }
    }

    private void step(Ground ground, float gravity) {
        for (Body body : bodies) {
            if (body.asleep) { continue; }
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
        if (arm == null) { return false; }
        var normal = normal(body.position.x + arm.x, body.position.y + arm.y, ground);
        // A wall's top is far above the point: leave it a cell at a time, sideways.
        body.position.mulAdd(normal, Math.min(deepest, CELL));
        var contact = new Vector3(body.spin).crs(arm).add(body.velocity);
        float into = contact.dot(normal);
        if (into >= 0) { return true; }
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
        int left = MathUtils.floor(column), bottom = MathUtils.floor(row);
        float across = column - left, up = row - bottom;
        float lowLeft = sample(heights, CELL, left, bottom, ground), lowRight = sample(heights, CELL, left + 1, bottom, ground);
        float highLeft = sample(heights, CELL, left, bottom + 1, ground);
        float highRight = sample(heights, CELL, left + 1, bottom + 1, ground);
        float rise = Math.max(Math.max(lowLeft, lowRight), Math.max(highLeft, highRight))
              - Math.min(Math.min(lowLeft, lowRight), Math.min(highLeft, highRight));
        if (rise > CLIFF) {
            return sample(steps, FINE, MathUtils.round(x / FINE), MathUtils.round(y / FINE), ground);
        }
        return MathUtils.lerp(MathUtils.lerp(lowLeft, lowRight, across), MathUtils.lerp(highLeft, highRight, across), up);
    }

    private float sample(LongMap<Float> cache, float spacing, int column, int row, Ground ground) {
        long key = ((long) column << 32) ^ (row & 0xffffffffL);
        Float cached = cache.get(key);
        if (cached != null) { return cached; }
        float x = column * spacing, y = row * spacing;
        float height = ground.height(x, y, from, Vector3.dst2(x, y, 0, middle.x, middle.y, 0) < reach * reach);
        height = Float.isFinite(height) ? height : floor;
        cache.put(key, height);
        return height;
    }

    private Vector3 normal(float x, float y, Ground ground) {
        float half = CELL * .5f;
        return new Vector3(height(x - half, y, ground) - height(x + half, y, ground),
              height(x, y - half, ground) - height(x, y + half, ground), CELL).nor();
    }

    /** Draws the pieces where they are. */
    void pose() {
        var motion = new Matrix4();
        for (Body body : bodies) {
            motion.setToTranslation(body.position).rotate(body.orientation)
                  .translate(-body.rest.x, -body.rest.y, -body.rest.z);
            body.node.localTransform.set(inverse).mul(motion).mul(frame);
        }
        instance.calculateTransforms();
    }
}
