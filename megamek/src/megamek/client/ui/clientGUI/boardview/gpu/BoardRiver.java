/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;

/** Shared stream paths through connected water hexes. Only visual geometry; the board still supplies water depth. */
final class BoardRiver {
    private static final int STEPS = 6;
    /** Perlin gradients here sum two unit offsets and the result is scaled by 1.4: no sample exceeds this. */
    private static final float MAX_GRADIENT = 2.8f;
    /** World units of slack for float rounding in a channel's bound before it may be skipped. */
    private static final float CLEARANCE_MARGIN = .05f;
    /** Half the width, in hex-scale units, of the strip of land along a separated side, and how far it wanders. */
    private static final float STRIP = 8, STRIP_WANDER = 1.5f;
    /**
     * The shares of a side at a strip's free end: open water from the corner, so no land reaches it, then the round nose,
     * over about the strip's width.
     */
    private static final float STRIP_OPEN = .22f, STRIP_TIP = .2f;
    /** Width, in hex-scale units, of the rounding where two strips meet at a bend. */
    private static final float STRIP_ROUND = 8;
    private final BoardScene scene;
    private final float scale = BoardGeometry.hexScale();
    private final float width = BoardGeometry.width(), height = BoardGeometry.height();
    private final Map<Coords, Sample> samples = new HashMap<>();
    private final Map<Coords, Integer> masks = new HashMap<>();
    private int column = Integer.MIN_VALUE, evenRow, oddRow;
    private final List<Sample> window = new ArrayList<>(25);

    private record Sample(float x, float y, boolean rounded, List<Channel> channels, List<float[]> lakes, List<Strip> strips) { }

    /**
     * The strip of land along a separated side, from corner (ax, ay) to corner (bx, by). {@code tipA}, {@code tipB}: open
     * water passes round that end, where the strip stops short of the corner in a round nose.
     */
    private record Strip(float ax, float ay, float bx, float by, boolean tipA, boolean tipB) {
        float field(float x, float y, float half) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            float share = share(t);
            // No land stands on the open stretch, so it is no shore either, not even a zero-width one.
            return share == 0 ? Float.POSITIVE_INFINITY : length(x - ax - t * dx, y - ay - t * dy) - half * share;
        }

        /** How much of the strip's land stands at (x, y), a point within {@code reach} of its side; else 1. */
        float land(float x, float y, float reach) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            return length(x - ax - t * dx, y - ay - t * dy) > reach ? 1 : share(t);
        }

        /** The share of a free end's depth at (x, y), a point within {@code reach} of the side: all at the corner, none at the nose. */
        float openDepth(float x, float y, float reach) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            if (length(x - ax - t * dx, y - ay - t * dy) > reach) { return 0; }
            float rise = Math.max(tipA ? 1 - Math.min(t / STRIP_OPEN, 1) : 0, tipB ? 1 - Math.min((1 - t) / STRIP_OPEN, 1) : 0);
            return rise * rise;
        }

        /** The share of the strip's width at t along it: none on the open stretch of a free end. */
        private float share(float t) { return (tipA ? nose(t) : 1) * (tipB ? nose(1 - t) : 1); }

        /** Nothing within {@link #STRIP_OPEN}, then a blunt round end opening to the strip's full width. */
        private static float nose(float t) {
            if (t <= STRIP_OPEN) { return 0; }
            float rest = 1 - Math.min((t - STRIP_OPEN) / STRIP_TIP, 1);
            return (float) Math.sqrt(1 - rest * rest * rest * rest);
        }
    }

    /** Endpoint properties belong to a whole channel, not each of its six line segments. */
    private final class Channel {
        private final List<Span> spans;
        private final float depthA, depthB;
        private final boolean broadA, broadB, detailedA, detailedB, uniform;
        /** The spans' bounds, and the largest radius any point of the channel can have at any wander. */
        private final float minX, minY, maxX, maxY, reach;

        Channel(List<Span> spans, BoardScene.Tile a, BoardScene.Tile b, boolean broadA, boolean broadB) {
            this.spans = spans;
            depthA = Math.clamp(a.waterDepth(), 0, 1);
            depthB = Math.clamp(b.waterDepth(), 0, 1);
            this.broadA = broadA;
            this.broadB = broadB;
            detailedA = a.detailedGround();
            detailedB = b.detailedGround();
            uniform = depthA == depthB && broadA == broadB && detailedA == detailedB;
            float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            for (Span span : spans) {
                left = Math.min(left, Math.min(span.ax(), span.ax() + span.dx()));
                right = Math.max(right, Math.max(span.ax(), span.ax() + span.dx()));
                bottom = Math.min(bottom, Math.min(span.ay(), span.ay() + span.dy()));
                top = Math.max(top, Math.max(span.ay(), span.ay() + span.dy()));
            }
            minX = left;
            maxX = right;
            minY = bottom;
            maxY = top;
            // The radius grows with the wander and changes monotonically along the channel, so the endpoints at the
            // largest wander bound every point of it; open water and special artwork add their own fixed caps.
            float wanderBound = .3f * BoardRelief.SHORE_WANDER * MAX_GRADIENT;
            reach = Math.max(Math.max(radius(0, wanderBound), radius(1, wanderBound)), Math.max(72 * scale, width / 2));
        }

        /** How far a point lies outside the channel's bounds; no span comes nearer than this. */
        float clearance(float x, float y) {
            float dx = Math.max(Math.max(minX - x, x - maxX), 0), dy = Math.max(Math.max(minY - y, y - maxY), 0);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        float field(float x, float y, float wander) {
            float result = Float.NEGATIVE_INFINITY;
            float commonRadius = uniform ? radius(0, wander) : 0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                float t = span.length() == 0 ? 0 : Math.clamp(((x - span.ax()) * span.dx()
                      + (y - span.ay()) * span.dy()) / span.length(), 0, 1);
                double distance = lengthSquared(x - span.ax() - t * span.dx(), y - span.ay() - t * span.dy());
                if (uniform) {
                    nearest = Math.min(nearest, distance);
                } else {
                    float radius = radius(span.start() + span.range() * t, wander);
                    result = Math.max(result, radius - (float) Math.sqrt(distance));
                }
            }
            // Equal radii need only the nearest segment; taking its square root once gives the same float union.
            return uniform ? commonRadius - (float) Math.sqrt(nearest) : result;
        }

        private float radius(float s, float wander) {
            float smooth = BoardRelief.smooth(s);
            float deep = BoardRelief.lerp(depthA, depthB, smooth);
            // Width belongs to the whole channel, not its crossings of hex edges. Carry the room needed by a
            // deep-water unit along the stream instead of making a fixed pool at every centre. Depth-zero reaches
            // have no unit-sized minimum; blend gently where a shallow reach meets deeper water.
            float minimum = BoardRelief.lerp(2, 12, deep);
            float full = Math.max(minimum, BoardRelief.SHORE_POOL * (.55f + .45f * deep) + BoardRelief.SHORE_SPREAD);
            float radius = Math.max(minimum, full + wander);
            // Open water leaves room for the shared shore field to shape the lake's bank. Widen the approaching
            // stream gradually too: a large round cap at the lake's first centre would make a sharp inlet corner.
            float open = BoardRelief.lerp(broadA ? 1 : 0, broadB ? 1 : 0, smooth);
            radius = BoardRelief.lerp(radius, Math.max(radius, width / (2 * scale)), open);
            // Special artwork (including bridges) keeps its original water footprint. The approach widens smoothly,
            // and both kinds of hex still ask the same world field at their shared opening.
            float natural = (detailedA ? 1 - s : 0) + (detailedB ? s : 0);
            radius = Math.max(radius, 72 * (1 - natural));
            return radius * scale;
        }
    }

    private record Span(float ax, float ay, float dx, float dy, float length, float start, float range) {
        Span(float ax, float ay, float bx, float by, float start, float end) {
            this(ax, ay, bx - ax, by - ay, (bx - ax) * (bx - ax) + (by - ay) * (by - ay), start, end - start);
        }
    }

    BoardRiver(BoardScene scene) {
        this.scene = scene;
    }

    /** A coordinate-based query: adjoining meshes see the same paths, tangent directions and widths. */
    float field(float x, float y) {
        return field(x, y, Float.POSITIVE_INFINITY);
    }

    /** The caller intersects this union with its shore field, so larger values cannot change the shore. */
    float field(float x, float y, float limit) {
        float result = Float.NEGATIVE_INFINITY;
        // The spline already meanders over several hexes. Reuse the width sample at a shorter scale for small,
        // irregular bank scallops, without adding shore searches, noise evaluations or increasing mesh resolution.
        float cell = .3f * BoardRelief.WANDER_CELL * scale;
        float wander = .3f * BoardRelief.SHORE_WANDER * BoardRelief.gradient(x / cell + 3.1f, y / cell - 1.7f);
        window(x, y);
        // Round whole branches near confluences and tight bends, where their inner banks otherwise meet in a cusp.
        // Blending individual spline samples or straight reaches would inflate every channel.
        float round = 0;
        for (Sample sample : window) {
            if (!sample.rounded()) { continue; }
            float distance = length(x - sample.x(), y - sample.y());
            round = Math.max(round, BoardRelief.smooth(1 - distance / (.8f * width)));
        }
        round *= 2 * BoardRelief.SHORE_BLEND * scale;
        for (Sample sample : window) {
            List<Channel> channels = sample.channels();
            for (int i = 0; i < channels.size(); i++) {
                Channel channel = channels.get(i);
                // A channel whose field cannot come within the rounding of the union so far leaves it unchanged:
                // smoothMin is an exact minimum once the two values differ by its rounding or more.
                if (channel.reach - channel.clearance(x, y) + CLEARANCE_MARGIN <= result - round) { continue; }
                float value = channel.field(x, y, wander);
                result = round > 0 ? -BoardRelief.smoothMin(-result, -value, round) : Math.max(result, value);
                if (result >= limit) { return result; }
            }
            // Three mutually adjacent water hexes contain open water, not a mesh of separate thin streams.
            for (float[] lake : sample.lakes()) {
                float ax = sample.x(), ay = sample.y(), bx = lake[0], by = lake[1], cx = lake[2], cy = lake[3];
                float ab = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
                float bc = (cx - bx) * (y - by) - (cy - by) * (x - bx);
                float ca = (ax - cx) * (y - cy) - (ay - cy) * (x - cx);
                if (ab >= 0 && bc >= 0 && ca >= 0 || ab <= 0 && bc <= 0 && ca <= 0) {
                    return Float.POSITIVE_INFINITY;
                }
            }
        }
        return result;
    }

    /**
     * How far (x, y) lies outside the strips of land along separated sides, in world units, negative on one; infinite
     * where there are none. A coordinate-based query like {@link #field}: both hexes of a side see one strip.
     */
    float strips(float x, float y) {
        window(x, y);
        float result = Float.POSITIVE_INFINITY, half = Float.NaN;
        for (Sample sample : window) {
            for (Strip strip : sample.strips()) {
                if (Float.isNaN(half)) {
                    float cell = .3f * BoardRelief.WANDER_CELL * scale;
                    half = (STRIP + STRIP_WANDER * BoardRelief.gradient(x / cell - 4.3f, y / cell + 5.9f)) * scale;
                }
                float field = strip.field(x, y, half);
                result = result == Float.POSITIVE_INFINITY ? field : BoardRelief.smoothMin(result, field, STRIP_ROUND * scale);
            }
        }
        return result;
    }

    /**
     * How much of a strip's land stands at (x, y) on a separated side: nothing on the open stretch past a free end's nose,
     * rising through the nose to all of it; everywhere else 1.
     */
    float stripLand(float x, float y) {
        window(x, y);
        float land = 1;
        for (Sample sample : window) {
            for (Strip strip : sample.strips()) { land = Math.min(land, strip.land(x, y, .5f * scale)); }
        }
        return land;
    }

    /** How much of its free end's depth the bed keeps at (x, y) on the open stretch of a separated side; elsewhere none. */
    float stripDepth(float x, float y) {
        window(x, y);
        float depth = 0;
        for (Sample sample : window) {
            for (Strip strip : sample.strips()) { depth = Math.max(depth, strip.openDepth(x, y, .5f * scale)); }
        }
        return depth;
    }

    /** Successive shore samples normally share a search window; resolve its coordinates only when it changes. */
    private void window(float x, float y) {
        int nextColumn = Math.round((x - width / 2) / (.75f * width));
        int nextEven = Math.round((-y - height / 2) / height);
        int nextOdd = Math.round((-y - height / 2 - height / 2) / height);
        if (column == nextColumn && evenRow == nextEven && oddRow == nextOdd) { return; }
        column = nextColumn;
        evenRow = nextEven;
        oddRow = nextOdd;
        window.clear();
        for (int cx = column - 2; cx <= column + 2; cx++) {
            int row = (cx & 1) == 0 ? evenRow : oddRow;
            for (int cy = row - 2; cy <= row + 2; cy++) {
                Coords coords = new Coords(cx, cy);
                int mask = masks.computeIfAbsent(coords, this::mask);
                if (mask >= 0) { window.add(samples.computeIfAbsent(coords, key -> sample(key, mask))); }
            }
        }
    }

    private Sample sample(Coords coords, int mask) {
        int starts = mask & ~((mask << 1 | mask >> 5) & 63);
        List<float[]> lakes = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            if ((mask & 1 << d) == 0 || (mask & 1 << (d + 1) % 6) == 0) { continue; }
            Coords b = coords.translated(d), c = coords.translated((d + 1) % 6);
            lakes.add(new float[] { BoardGeometry.centerX(b), BoardGeometry.centerY(b),
                  BoardGeometry.centerX(c), BoardGeometry.centerY(c) });
        }
        boolean bend = Integer.bitCount(mask) == 2 && Integer.bitCount(starts) == 2
              && (mask & (mask << 3 | mask >> 3) & 63) == 0;
        return new Sample(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords),
              bend || Integer.bitCount(mask) >= 3 && Integer.bitCount(starts) >= 2, channels(coords), lakes, strips(coords));
    }

    /** The strips along this hex's separated sides, each owned by one of its two hexes, as a channel's link is. */
    private List<Strip> strips(Coords coords) {
        List<Strip> result = new ArrayList<>(0);
        BoardScene.Tile here = tile(coords);
        for (int edge = 0; edge < 6; edge++) {
            int d = BoardGeometry.edgeDirection(edge);
            Coords other = coords.translated(d);
            BoardScene.Tile next = scene.tile(other);
            if (next == null || !water(next) || !here.separated(next, d)
                  || other.getX() < coords.getX() || other.getX() == coords.getX() && other.getY() < coords.getY()) { continue; }
            var a = BoardGeometry.corner(coords, 0, edge);
            var b = BoardGeometry.corner(coords, 0, (edge + 1) % 6);
            result.add(new Strip(a.x, a.y, b.x, b.y, free(coords, here, next, (edge + 5) % 6), free(coords, here, next, (edge + 1) % 6)));
        }
        return result;
    }

    /** Whether the hex across {@code edge} of {@code coords} holds water that joins both hexes of a separated side. */
    private boolean free(Coords coords, BoardScene.Tile here, BoardScene.Tile next, int edge) {
        BoardScene.Tile third = scene.tile(coords.translated(BoardGeometry.edgeDirection(edge)));
        return third != null && water(third) && here.joins(third) && next.joins(third);
    }

    /** Board coordinates are finite floats; double products cannot overflow or underflow like float products. */
    private static float length(float x, float y) {
        return (float) Math.sqrt(lengthSquared(x, y));
    }

    private static double lengthSquared(float x, float y) { return (double) x * x + (double) y * y; }

    private List<Channel> channels(Coords coords) {
        List<Channel> result = new ArrayList<>();
        BoardScene.Tile tile = tile(coords);
        float ax = BoardGeometry.centerX(coords), ay = BoardGeometry.centerY(coords);
        boolean broadA = broad(coords);
        result.add(new Channel(List.of(new Span(ax, ay, ax, ay, 0, 0)), tile, tile, broadA, broadA));
        for (int d = 0; d < 6; d++) {
            Coords other = coords.translated(d);
            BoardScene.Tile next = tile(other);
            if (!linked(coords, d)) { continue; }
            // Each link has one owner and one ordering, including when queried by the other hex.
            if (other.getX() < coords.getX() || other.getX() == coords.getX() && other.getY() < coords.getY()) { continue; }
            boolean broadB = broad(other);
            Coords before = continuation(coords, other), after = continuation(other, coords);
            float bx = BoardGeometry.centerX(other), by = BoardGeometry.centerY(other);
            float tx = before == null ? bx - ax : (bx - BoardGeometry.centerX(before)) / 2;
            float ty = before == null ? by - ay : (by - BoardGeometry.centerY(before)) / 2;
            float ux = after == null ? bx - ax : (BoardGeometry.centerX(after) - ax) / 2;
            float uy = after == null ? by - ay : (BoardGeometry.centerY(after) - ay) / 2;
            boolean broad = linked(coords, (d + 1) % 6) || linked(coords, (d + 5) % 6);
            // Rotate the shared direction at each centre instead of moving the centre itself. The stream then
            // meanders through its hexes, and adjoining links use the same turn at their shared centre.
            float angle = broad ? 0 : meander(ax, ay);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle), vx = tx;
            tx = vx * cos - ty * sin;
            ty = vx * sin + ty * cos;
            angle = broad ? 0 : meander(bx, by);
            cos = (float) Math.cos(angle);
            sin = (float) Math.sin(angle);
            vx = ux;
            ux = vx * cos - uy * sin;
            uy = vx * sin + uy * cos;
            List<Span> spans = new ArrayList<>(STEPS);
            float px = ax, py = ay;
            for (int i = 1; i <= STEPS; i++) {
                float t = i / (float) STEPS, t2 = t * t, t3 = t2 * t;
                float qx = (2 * t3 - 3 * t2 + 1) * ax + (t3 - 2 * t2 + t) * tx
                      + (-2 * t3 + 3 * t2) * bx + (t3 - t2) * ux;
                float qy = (2 * t3 - 3 * t2 + 1) * ay + (t3 - 2 * t2 + t) * ty
                      + (-2 * t3 + 3 * t2) * by + (t3 - t2) * uy;
                spans.add(new Span(px, py, qx, qy, (i - 1f) / STEPS, t));
                px = qx;
                py = qy;
            }
            result.add(new Channel(List.copyOf(spans), tile, next, broadA, broadB));
        }
        return List.copyOf(result);
    }

    /** Slow changes in direction; the world coordinates give both ends of a shared centre the same turn. */
    private float meander(float x, float y) {
        float cell = .5f * BoardRelief.WANDER_CELL * scale;
        return Math.clamp(.625f * BoardRelief.gradient(x / cell + 2.3f, y / cell - 2.3f), -.7f, .7f);
    }

    /** The preceding/following hex of a stream; junctions keep their separate branches. */
    private Coords continuation(Coords here, Coords from) {
        Coords result = null;
        for (int d = 0; d < 6; d++) {
            Coords next = here.translated(d);
            if (next.equals(from) || !linked(here, d)) { continue; }
            if (result != null) { return null; }
            result = next;
        }
        return result;
    }

    /** Whether the water at {@code coords} runs on in {@code direction}; beyond the board's edge nothing separates it. */
    private boolean linked(Coords coords, int direction) {
        Coords at = coords.translated(direction);
        BoardScene.Tile here = tile(coords), next = tile(at);
        return water(next) && (!here.coords().equals(coords) || !next.coords().equals(at) || !here.separated(next, direction));
    }

    private static boolean water(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().molten();
    }

    private BoardScene.Tile tile(Coords coords) {
        return scene.tile(new Coords(Math.clamp(coords.getX(), 0, scene.width() - 1),
              Math.clamp(coords.getY(), 0, scene.height() - 1)));
    }

    private int mask(Coords coords) {
        if (!water(tile(coords))) { return -1; }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if (linked(coords, d)) { result |= 1 << d; }
        }
        return result;
    }

    /** A triangle of mutually adjacent water centres contains open water, rather than separate stream branches. */
    private boolean broad(Coords coords) {
        int mask = masks.computeIfAbsent(coords, this::mask);
        return mask >= 0 && (mask & (mask << 1 | mask >> 5) & 63) != 0;
    }

}
