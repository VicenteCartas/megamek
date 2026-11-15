/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/**
 * The height field's inputs for one scene revision ({@link TerrainField}), in plain arrays: each hex's level and family,
 * which hexes the field takes, which edges are walls, and every wall edge's and corner's moved samples. Built once per
 * scene revision on the terrain build's worker (GpuTerrain's update plan holds it and replaces it with the next plan);
 * read-only afterwards, so the chunk workers share it. Also the field's evaluator: a hex's height at a point.
 */
final class FieldBoard {
    /** The moved boundary is always sampled at full detail; coarser lattices interpolate it, so their samples nest. */
    static final int FULL = 8;
    /**
     * A wall's rim stands back from the contour by this many metres and by today's rock relief at its rim height
     * (BoardRelief#wallRelief: its retreat, caprock and joints), which breaks the rim as today's is broken.
     */
    private static final float RECESS = 2.5f;
    /** A hex keeps its own level this far round its centre, in metres (today's STEP_KEEP). */
    private static final float KEEP = 6;
    /** The largest move of a rim sample, as a share of the inner radius: the linear falloff never folds below 1. */
    private static final float MAX_MOVE = .6f;
    /** Squared distances closer than this are one distance (steps meeting at a corner sample). */
    private static final float TIE = 1e-6f;
    /** The banks' level-set grid's spacing, in metres. */
    private static final float GRID = 2;
    /**
     * A step further than this many metres from a point plays no part in its height (the cliff weight fades out from
     * half of it); every step this near a sample two hexes share is in both hexes' lists (#segments).
     */
    private static final float FAR = 12;

    final int cols, rows;
    private final int[] level, family;
    /** Per hex, the edges (by edge index) where it stands a wall above its neighbour (BoardRelief's rule). */
    private final int[] walls;
    private final boolean[] taken;
    final float width, height, levelZ, metre, room, edge, taper, keep, recess, bankRadius, bankMix, cliffRadius, maxMove, farReach;
    private final BoardRelief.StepOutline outline;
    private final Map<Long, float[][]> edgeMoves;
    private final Map<List<Integer>, float[]> cornerMoves;
    /** Per hex, the steps that shape its ground (#segments), listed on the first ask by any chunk worker. */
    private final Map<Coords, List<Segment>> steps = new java.util.concurrent.ConcurrentHashMap<>();
    /** Set once built: moves asked afterwards (edges between two hexes off the board) are computed, never stored. */
    private boolean frozen;

    /**
     * The inputs for {@code scene}: all of them, or, given the previous revision's and the hexes that changed, those
     * the change reaches (the changed hexes' own values, their neighbours' walls, and the rim moves of the edges and
     * corners within the level set's reach, two rings) recomputed and the rest kept.
     */
    private FieldBoard(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed) {
        this.outline = outline;
        cols = scene.width();
        rows = scene.height();
        width = BoardGeometry.width();
        height = BoardGeometry.height();
        levelZ = BoardGeometry.level();
        level = new int[cols * rows];
        family = new int[cols * rows];
        walls = new int[cols * rows];
        taken = new boolean[cols * rows];
        metre = BoardRelief.metres(1);
        room = BoardRelief.metres(BoardRelief.TRANSITION);
        edge = width / 2;
        taper = .3f * edge;
        keep = BoardRelief.metres(KEEP);
        recess = BoardRelief.metres(RECESS);
        bankRadius = (outline == BoardRelief.StepOutline.SMOOTH ? 1.2f : .75f) * width;
        // Hex edges: banks most of the way to Rounded corners' outline, as round as today's Hex edges banks look.
        bankMix = outline == BoardRelief.StepOutline.HEX ? .8f : 1;
        // Walls take Rounded corners' level set in every mode, as today's cliffs look the same in all three.
        cliffRadius = .75f * width;
        maxMove = MAX_MOVE * edge * (float) Math.sqrt(3) / 2;
        farReach = BoardRelief.metres(FAR);
        boolean incremental = previous != null && changed != null && previous.cols == cols && previous.rows == rows
              && previous.outline == outline && previous.width == width && previous.levelZ == levelZ;
        if (!incremental) {
            edgeMoves = new HashMap<>();
            cornerMoves = new HashMap<>();
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) { hex(scene, new Coords(c, r)); }
            }
        } else {
            System.arraycopy(previous.level, 0, level, 0, level.length);
            System.arraycopy(previous.family, 0, family, 0, family.length);
            System.arraycopy(previous.walls, 0, walls, 0, walls.length);
            System.arraycopy(previous.taken, 0, taken, 0, taken.length);
            edgeMoves = new HashMap<>(previous.edgeMoves);
            cornerMoves = new HashMap<>(previous.cornerMoves);
            java.util.Set<Coords> near = rings(changed, 1), reach = rings(changed, 2);
            for (Coords at : near) { if (inside(at)) { hex(scene, at); } }
            // Only the reach's edges and corners are moved again; the rest stay as the previous revision has them.
            for (Coords at : reach) {
                for (int e = 0; e < 6; e++) {
                    edgeMoves.remove(edgeKey(at, at.translated(BoardGeometry.edgeDirection(e))));
                    cornerMoves.remove(cornerKey(at, e));
                }
            }
            for (Coords at : reach) {
                for (int e = 0; e < 6 && inside(at); e++) { edgeMove(at, e); }
            }
            return;
        }
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                for (int e = 0; e < 6; e++) { edgeMove(new Coords(c, r), e); }
            }
        }
    }

    /** One hex's own values: level, family, whether the field takes it, its walls. */
    private void hex(BoardScene scene, Coords at) {
        BoardScene.Tile t = scene.tile(at);
        int i = at.getX() * rows + at.getY();
        boolean liquid = t.liquid().present();
        level[i] = t.groundLevel() - (liquid ? Math.max(0, t.waterDepth()) : 0);
        family[i] = t.surface().ordinal();
        taken[i] = dryNatural(scene, t);
        walls[i] = 0;
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
            // A wall, as BoardRelief draws one: a drop of more than two and a half levels for its landforms.
            if (next != null && BoardRelief.levels(t, next) > 2.5f) { walls[i] |= 1 << e; }
        }
    }

    private static java.util.Set<Coords> rings(java.util.Set<Coords> hexes, int rings) {
        java.util.Set<Coords> result = new java.util.HashSet<>(hexes);
        for (int r = 0; r < rings; r++) {
            for (Coords at : List.copyOf(result)) {
                for (int d = 0; d < 6; d++) { result.add(at.translated(d)); }
            }
        }
        return result;
    }

    /** The field's inputs for {@code scene} in the given hill outline mode; on a terrain worker. */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline) {
        return of(scene, outline, null, null);
    }

    /**
     * The next revision's inputs: {@code previous}'s with the hexes {@code changed} reaches recomputed (all of them when
     * there is no previous, its board or outline differs, or {@code changed} is null: a full rebuild).
     */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed) {
        FieldBoard board = new FieldBoard(scene.groundScene(), outline, previous, changed);
        board.frozen = true;
        return board;
    }

    /** The hexes the field takes: dry natural ground without roads, paving, ramps or bridges (step 3). */
    private static boolean dryNatural(BoardScene scene, BoardScene.Tile t) {
        return !t.liquid().present() && t.surface() != BoardScene.Surface.CONCRETE && !t.ultraSublevel()
              && !t.backdrop().present() && !BoardFeatures.shaft(t) && t.wayExits() == 0 && t.road() == BoardRoad.Kind.NONE
              && BoardBridge.feature(t) == null && BoardSurface.ramps(scene, t) == 0;
    }

    record Segment(float ax, float ay, float bx, float by, int low, int high, boolean cliff) {
        float distance(float x, float y) { return (float) Math.sqrt(squared(x, y)); }

        float squared(float x, float y) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            float px = ax + t * dx - x, py = ay + t * dy - y;
            return px * px + py * py;
        }
    }

    int index(Coords c) { return Math.clamp(c.getX(), 0, cols - 1) * rows + Math.clamp(c.getY(), 0, rows - 1); }

    boolean inside(Coords c) { return c.getX() >= 0 && c.getY() >= 0 && c.getX() < cols && c.getY() < rows; }

    boolean taken(Coords c) { return inside(c) && taken[index(c)]; }

    int level(Coords c) { return level[index(c)]; }

    /** The hex's surface family, as a {@link BoardScene.Surface} ordinal. */
    int family(Coords c) { return family[index(c)]; }

    float cornerX(Coords h, int k) { return HexTile.cornerX(h, Math.floorMod(k, 6), width); }

    float cornerY(Coords h, int k) { return HexTile.cornerY(h, Math.floorMod(k, 6), height); }

    float centerX(Coords h) { return HexTile.centerX(h, width); }

    float centerY(Coords h) { return HexTile.centerY(h, height); }

    /** Whether the step between u and v is a wall by BoardRelief's rule (BoardRelief#levels). */
    boolean cliff(Coords u, Coords v) {
        return inside(u) && inside(v) && ((walls[index(u)] & 1 << edgeIndex(u, v)) != 0
              || (walls[index(v)] & 1 << edgeIndex(v, u)) != 0);
    }

    /** A wall whose row the field moves: a cliff between two hexes it takes. */
    private boolean wall(Coords u, Coords v) { return taken(u) && taken(v) && cliff(u, v); }

    /** The edge index of {@code from}'s edge toward its neighbour {@code to}. */
    static int edgeIndex(Coords from, Coords to) {
        int d = from.direction(to), e = 0;
        while (BoardGeometry.edgeDirection(e) != d) { e++; }
        return e;
    }

    private int middle(Coords u, Coords v) { return Math.floorDiv(level(u) + level(v) + 1, 2); }

    /** The share of the kernel round (x, y) on hexes at level k or above: a smoothed level set (HexTile's centres). */
    float share(int k, float x, float y, float reach) {
        float column = width * .75f, inverse = 1 / (reach * reach);
        int c0 = (int) Math.floor((x - reach) / column) - 1, c1 = (int) Math.ceil((x + reach) / column) + 1;
        int r0 = (int) Math.floor((-y - reach) / height) - 1, r1 = (int) Math.ceil((-y + reach) / height) + 1;
        float sum = 0, weight = 0;
        for (int c = c0; c <= c1; c++) {
            float dx = x - (c * column + width / 2);
            int ci = Math.clamp(c, 0, cols - 1) * rows;
            float odd = (c & 1) * height / 2 + height / 2;
            for (int r = r0; r <= r1; r++) {
                float dy = y + r * height + odd;
                float q = 1 - (dx * dx + dy * dy) * inverse;
                if (q <= 0) { continue; }
                float kq = q * q * q;
                sum += level[ci + Math.clamp(r, 0, rows - 1)] >= k ? kq : 0;
                weight += kq;
            }
        }
        return weight == 0 ? 0 : sum / weight;
    }

    /**
     * The banks' smoothed level sets over one chunk, sampled lazily on a grid of {@link #GRID} metres (value and
     * gradient interpolated bilinearly): the field reads them from here instead of summing the kernel per sample. Shared
     * by the chunk's workers; a node computed twice gets the same value. The grid's nodes lie on one board-wide grid, so
     * two chunks give a point on their border the same value.
     */
    final class Shares {
        private final float x0, y0, step;
        private final int nx, ny;
        private final Map<Integer, float[]> grids = new java.util.concurrent.ConcurrentHashMap<>();

        Shares(float minX, float minY, float maxX, float maxY) {
            step = BoardRelief.metres(GRID);
            x0 = (float) Math.floor(minX / step - 2) * step;
            y0 = (float) Math.floor(minY / step - 2) * step;
            nx = (int) Math.ceil((maxX - x0) / step) + 3;
            ny = (int) Math.ceil((maxY - y0) / step) + 3;
        }

        private float node(float[] grid, int k, int i, int j) {
            i = Math.clamp(i, 0, nx - 1);
            j = Math.clamp(j, 0, ny - 1);
            float v = grid[j * nx + i];
            if (Float.isNaN(v)) { grid[j * nx + i] = v = share(k, x0 + i * step, y0 + j * step, bankRadius); }
            return v;
        }

        /** out: level k's share at (x, y) and its gradient. */
        void sample(int k, float x, float y, float[] out) {
            float[] grid = grids.computeIfAbsent(k, key -> {
                float[] values = new float[nx * ny];
                Arrays.fill(values, Float.NaN);
                return values;
            });
            float fx = (x - x0) / step, fy = (y - y0) / step;
            int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
            float u = fx - i, w = fy - j;
            float v = 0, gx = 0, gy = 0;
            for (int b = 0; b < 2; b++) {
                for (int a = 0; a < 2; a++) {
                    float weight = (a == 0 ? 1 - u : u) * (b == 0 ? 1 - w : w);
                    int ii = i + a, jj = j + b;
                    v += weight * node(grid, k, ii, jj);
                    gx += weight * (node(grid, k, ii + 1, jj) - node(grid, k, ii - 1, jj)) / (2 * step);
                    gy += weight * (node(grid, k, ii, jj + 1) - node(grid, k, ii, jj - 1)) / (2 * step);
                }
            }
            out[0] = v;
            out[1] = gx;
            out[2] = gy;
        }
    }

    /** out: signed distance from the half line of level k's set (positive outside it) and the outward normal. */
    private void contour(int k, float x, float y, float reach, float[] out) {
        float d = BoardRelief.metres(.5f);
        float v = share(k, x, y, reach) - .5f;
        float gx = (share(k, x + d, y, reach) - share(k, x - d, y, reach)) / (2 * d);
        float gy = (share(k, x, y + d, reach) - share(k, x, y - d, reach)) / (2 * d);
        float g = (float) Math.hypot(gx, gy);
        out[0] = g < 1e-6f ? (v > 0 ? -1e6f : 1e6f) : -v / g;
        out[1] = g < 1e-6f ? 0 : -gx / g;
        out[2] = g < 1e-6f ? 0 : -gy / g;
    }

    /**
     * The move of a rim sample at (x, y) onto level k's contour, set back into the upper side by the recess and today's
     * rock relief at the rim (between {@code upper} and {@code lower}),
     * capped, and kept off the centres of the hexes {@code around} (upper ones by the keep, lower ones by the keep and
     * the talus's reach).
     */
    private float[] rimMove(int k, float x, float y, Coords[] around, Coords upper, Coords lower) {
        float[] o = new float[3];
        float px = x, py = y, limit = BoardRelief.metres(4);
        for (int it = 0; it < 12; it++) {
            contour(k, px, py, cliffRadius, o);
            if (o[1] == 0 && o[2] == 0) { break; }
            float step = Math.clamp(-o[0], -limit, limit);
            px += o[1] * step;
            py += o[2] * step;
            if (Math.abs(step) < BoardRelief.metres(.02f)) { break; }
        }
        contour(k, px, py, cliffRadius, o);
        float top = level(upper) * levelZ, bottom = level(lower) * levelZ;
        float drop = Math.max(level(upper) - level(lower), 3) * levelZ;
        float r = recess - BoardRelief.wallRelief(px, py, top, bottom, top, drop, family(upper));
        float dx = px - x - r * o[1], dy = py - y - r * o[2];
        float len = (float) Math.hypot(dx, dy);
        if (len > maxMove) { dx *= maxMove / len; dy *= maxMove / len; }
        for (Coords c : around) {
            float clear = level(c) >= k ? keep : keep + room;
            float ox = x + dx - centerX(c), oy = y + dy - centerY(c);
            float r0 = (float) Math.hypot(ox, oy);
            if (r0 < clear && r0 > 1e-3f) {
                dx += ox / r0 * (clear - r0);
                dy += oy / r0 * (clear - r0);
            }
        }
        return new float[] { dx, dy, 0 };
    }

    /** Corner k of hex h (shared with its neighbours k - 1 and k): {dx, dy, 1 when a wall's corner is held}. */
    private float[] cornerMove(Coords h, int k) {
        Coords[] t = { h, h.translated(BoardGeometry.edgeDirection((k + 5) % 6)), h.translated(BoardGeometry.edgeDirection(k)) };
        List<Integer> key = cornerKey(h, k);
        float[] cached = cornerMoves.get(key);
        if (cached != null) { return cached; }
        float[] result = { 0, 0, 0 };
        int k0 = 0;
        Coords upperHex = null, lowerHex = null;
        boolean[] side = null;
        boolean consistent = true, any = false;
        int[][] pairs = { { 0, 1 }, { 0, 2 }, { 1, 2 } };
        for (int[] p : pairs) {
            if (!cliff(t[p[0]], t[p[1]])) { continue; }
            any = true;
            if (!wall(t[p[0]], t[p[1]])) { consistent = false; continue; }
            int km = middle(t[p[0]], t[p[1]]);
            boolean[] up = { level(t[0]) >= km, level(t[1]) >= km, level(t[2]) >= km };
            if (side == null) {
                k0 = km;
                side = up;
                boolean first = level(t[p[0]]) > level(t[p[1]]);
                upperHex = first ? t[p[0]] : t[p[1]];
                lowerHex = first ? t[p[1]] : t[p[0]];
            }
            else if (!Arrays.equals(up, side)) { consistent = false; }
        }
        boolean all = taken(t[0]) && taken(t[1]) && taken(t[2]);
        if (any) {
            if (!all || !consistent || side == null) {
                result[2] = 1;
            } else {
                result = rimMove(k0, cornerX(h, k), cornerY(h, k), t, upperHex, lowerHex);
            }
        }
        if (!frozen) { cornerMoves.put(key, result); }
        return result;
    }

    /** An edge's key, the same from either hex (the smaller coordinates first). */
    private static long edgeKey(Coords h, Coords g) {
        boolean first = h.getX() < g.getX() || h.getX() == g.getX() && h.getY() < g.getY();
        Coords u = first ? h : g, v = first ? g : h;
        return (long) (u.getX() & 0xffff) << 48 | (long) (u.getY() & 0xffff) << 32 | (long) (v.getX() & 0xffff) << 16
              | v.getY() & 0xffff;
    }

    /** Corner k of hex h's key, the same from each of its three hexes. */
    private static List<Integer> cornerKey(Coords h, int k) {
        Coords[] s = { h, h.translated(BoardGeometry.edgeDirection((k + 5) % 6)), h.translated(BoardGeometry.edgeDirection(k)) };
        Arrays.sort(s, (a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX()) : Integer.compare(a.getY(), b.getY()));
        return List.of(s[0].getX(), s[0].getY(), s[1].getX(), s[1].getY(), s[2].getX(), s[2].getY());
    }

    /** The moves of h's edge e's samples at full detail, from corner e to corner e + 1; identical from either hex. */
    float[][] edgeMove(Coords h, int e) {
        Coords g = h.translated(BoardGeometry.edgeDirection(e));
        boolean canonical = h.getX() < g.getX() || h.getX() == g.getX() && h.getY() < g.getY();
        Coords u = canonical ? h : g, v = canonical ? g : h;
        long key = edgeKey(u, v);
        float[][] points = edgeMoves.get(key);
        if (points == null) {
            points = canonicalEdge(u, v);
            if (!frozen) { edgeMoves.put(key, points); }
        }
        if (canonical) { return points; }
        float[][] reversed = new float[FULL + 1][];
        for (int j = 0; j <= FULL; j++) { reversed[j] = points[FULL - j]; }
        return reversed;
    }

    private float[][] canonicalEdge(Coords u, Coords v) {
        int e = edgeIndex(u, v);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[] ca = cornerMove(u, e), cb = cornerMove(u, (e + 1) % 6);
        boolean wall = wall(u, v);
        Coords[] around = { u, v };
        float[][] points = new float[FULL + 1][];
        float fade = .5f * edge;
        for (int j = 0; j <= FULL; j++) {
            float t = j / (float) FULL;
            float mx = ca[0] + (cb[0] - ca[0]) * t, my = ca[1] + (cb[1] - ca[1]) * t;
            if (j == 0 || j == FULL || !wall) { points[j] = new float[] { mx, my }; continue; }
            float[] d = rimMove(middle(u, v), ax + (bx - ax) * t, ay + (by - ay) * t, around,
                  level(u) > level(v) ? u : v, level(u) > level(v) ? v : u);
            // The move fades out toward a held corner.
            float w = 1;
            if (ca[2] > 0) { w = Math.min(w, t * edge / fade); }
            if (cb[2] > 0) { w = Math.min(w, (1 - t) * edge / fade); }
            w = w * w * (3 - 2 * w);
            points[j] = new float[] { mx + (d[0] - mx) * w, my + (d[1] - my) * w };
        }
        return points;
    }

    /**
     * The steps round h along the moved edges: its six edges and the six spokes at its corners, and any other step at a
     * corner of a neighbour that lies within {@link #FAR} (and a metre) of h's moved outline. Every step within FAR of a
     * sample two hexes share is then in both hexes' lists, so both give it one height.
     */
    List<Segment> segments(Coords h) {
        return steps.computeIfAbsent(h, this::near);
    }

    private List<Segment> near(Coords h) {
        List<Segment> result = new ArrayList<>(12 * FULL);
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (int e = 0; e < 6; e++) {
            Coords g = h.translated(BoardGeometry.edgeDirection(e));
            add(result, h, e);
            Coords next = h.translated(BoardGeometry.edgeDirection((e + 1) % 6));
            add(result, g, edgeIndex(g, next));
            listed.add(edgeKey(h, g));
            listed.add(edgeKey(g, next));
        }
        // h's moved outline, at full detail.
        float[] ox = new float[6 * FULL + 1], oy = new float[6 * FULL + 1];
        for (int e = 0; e < 6; e++) {
            float[][] d = edgeMove(h, e);
            for (int j = 0; j < FULL; j++) {
                float t = j / (float) FULL;
                ox[e * FULL + j] = cornerX(h, e) + (cornerX(h, e + 1) - cornerX(h, e)) * t + d[j][0];
                oy[e * FULL + j] = cornerY(h, e) + (cornerY(h, e + 1) - cornerY(h, e)) * t + d[j][1];
            }
        }
        ox[6 * FULL] = ox[0];
        oy[6 * FULL] = oy[0];
        float reach = farReach + metre;
        List<Segment> candidates = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            Coords c = h.translated(BoardGeometry.edgeDirection(d));
            if (!inside(c)) { continue; }
            for (int e = 0; e < 6; e++) {
                Coords g = c.translated(BoardGeometry.edgeDirection(e)), next = c.translated(BoardGeometry.edgeDirection((e + 1) % 6));
                if (listed.add(edgeKey(c, g))) { add(candidates, c, e); }
                if ((inside(g) || inside(next)) && listed.add(edgeKey(g, next))) { add(candidates, g, edgeIndex(g, next)); }
            }
        }
        for (Segment s : candidates) {
            if (closer(s, ox, oy, reach)) { result.add(s); }
        }
        return List.copyOf(result);
    }

    /** Whether segment s comes within {@code reach} of the closed polyline (ox, oy). */
    private static boolean closer(Segment s, float[] ox, float[] oy, float reach) {
        float r2 = reach * reach;
        for (int i = 0; i + 1 < ox.length; i++) {
            Segment o = new Segment(ox[i], oy[i], ox[i + 1], oy[i + 1], 0, 0, false);
            if (s.squared(ox[i], oy[i]) <= r2 || o.squared(s.ax(), s.ay()) <= r2 || o.squared(s.bx(), s.by()) <= r2) { return true; }
            // Crossing.
            float d1 = cross(s, ox[i], oy[i]), d2 = cross(s, ox[i + 1], oy[i + 1]);
            float d3 = cross(o, s.ax(), s.ay()), d4 = cross(o, s.bx(), s.by());
            if (d1 * d2 < 0 && d3 * d4 < 0) { return true; }
        }
        return false;
    }

    private static float cross(Segment s, float x, float y) {
        return (s.bx() - s.ax()) * (y - s.ay()) - (s.by() - s.ay()) * (x - s.ax());
    }

    private void add(List<Segment> into, Coords u, int e) {
        Coords v = u.translated(BoardGeometry.edgeDirection(e));
        int lu = level(u), lv = level(v);
        if (lu == lv) { return; }
        boolean cliff = cliff(u, v);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[][] d = edgeMove(u, e);
        if (!wall(u, v)) {
            // Its samples lie on the straight line between its moved corners: one segment.
            into.add(new Segment(ax + d[0][0], ay + d[0][1], bx + d[FULL][0], by + d[FULL][1], Math.min(lu, lv), Math.max(lu, lv),
                  cliff));
            return;
        }
        for (int j = 0; j < FULL; j++) {
            float t0 = j / (float) FULL, t1 = (j + 1) / (float) FULL;
            into.add(new Segment(ax + (bx - ax) * t0 + d[j][0], ay + (by - ay) * t0 + d[j][1],
                  ax + (bx - ax) * t1 + d[j + 1][0], ay + (by - ay) * t1 + d[j + 1][1],
                  Math.min(lu, lv), Math.max(lu, lv), cliff));
        }
    }

    /** The bank's profile at signed distance s from its outline (negative inside): today's slope transition. */
    private float bank(float s, float x, float y) {
        float u = Math.clamp(s / room, -1, 1);
        float shoulder = BoardRelief.noise(x / BoardRelief.metres(35) + 11.7f, y / BoardRelief.metres(35) - 5.2f);
        float k = .25f + .35f * shoulder;
        float t = (1 - u) / 2;
        for (int i = 0; i < 4; i++) {
            float f = 1 - 2 * (t + k * (float) Math.sin(2 * Math.PI * t) / (float) (2 * Math.PI)) - u;
            float d = -2 - 2 * k * (float) Math.cos(2 * Math.PI * t);
            t = Math.clamp(t - f / d, 0, 1);
        }
        return t;
    }

    /**
     * Signed distance to the bank's smoothed outline for level k, kept off the hex centres near (x, y): {@code count}
     * floats of {@code centres}, each centre's distance and level; one at k or above stays inside, one below outside.
     * The point's centres, not only the asking hex's, so two hexes agree on the samples they share.
     */
    private float smoothDistance(Shares shares, int k, float x, float y, float[] centres, int count) {
        float[] o = new float[3];
        shares.sample(k, x, y, o);
        float v = o[0] - .5f, gx = o[1], gy = o[2];
        float g = (float) Math.hypot(gx, gy);
        float s = g < 1e-6f ? (v > 0 ? -1e6f : 1e6f) : -v / g;
        for (int i = 0; i < count; i += 2) {
            float guard = room - Math.max(0, centres[i] - keep);
            s = centres[i + 1] >= k ? Math.min(s, -guard) : Math.max(s, guard);
        }
        return s;
    }

    /** 0 up to {@link #FAR} / 2 from a step, 1 from {@link #FAR} on, smoothly between. */
    private float far(float d) {
        float t = Math.clamp((d - farReach / 2) / (farReach / 2), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * Hex h's own height at (x, y) and what its shading needs. out: z; role (0 top cover, 1 step face); rock (how much
     * of a cliff the nearest step is); distance to the nearest drop and rise; the nearest step's height in levels.
     */
    void height(Coords h, List<Segment> segments, Shares shares, float x, float y, float[] out) {
        int own = level(h), lowest = own, highest = own;
        // The centres a bank keeps off: h's and its neighbours' near the point, each with its level.
        float[] centres = new float[14];
        int count = 0;
        for (int e = -1; e < 6; e++) {
            Coords c = e < 0 ? h : h.translated(BoardGeometry.edgeDirection(e));
            if (!inside(c)) { continue; }
            float dx = x - centerX(c), dy = y - centerY(c), reach = keep + 2 * room;
            if (dx * dx + dy * dy >= reach * reach) { continue; }
            centres[count++] = (float) Math.sqrt(dx * dx + dy * dy);
            centres[count++] = level(c);
        }
        float[] d = new float[segments.size()];
        float rim = Float.POSITIVE_INFINITY, foot = Float.POSITIVE_INFINITY, near = Float.POSITIVE_INFINITY;
        int nearLevels = 0;
        for (int i = 0; i < d.length; i++) {
            Segment s = segments.get(i);
            d[i] = s.squared(x, y);
            lowest = Math.min(lowest, s.low());
            highest = Math.max(highest, s.high());
            // A cliff's rim and foot are its row; a bank's are its band's edges (below).
            if (s.cliff() && s.high() == own) { rim = Math.min(rim, d[i]); }
            if (s.cliff() && s.low() == own) { foot = Math.min(foot, d[i]); }
            // Ties (steps meeting at a corner) take the taller step, whichever hex lists them first.
            if (d[i] < near - TIE) { near = d[i]; nearLevels = s.high() - s.low(); }
            else if (d[i] <= near + TIE) { near = Math.min(near, d[i]); nearLevels = Math.max(nearLevels, s.high() - s.low()); }
        }
        rim = (float) Math.sqrt(rim);
        foot = (float) Math.sqrt(foot);
        float z = lowest * levelZ, role = 0, rock = 0, band = Float.POSITIVE_INFINITY;
        float bankRim = Float.POSITIVE_INFINITY, bankFoot = Float.POSITIVE_INFINITY;
        for (int k = lowest + 1; k <= highest; k++) {
            float nearest = Float.POSITIVE_INFINITY, bankD = Float.POSITIVE_INFINITY, cliffD = Float.POSITIVE_INFINITY;
            int cliffDrop = 3;
            for (int i = 0; i < d.length; i++) {
                Segment s = segments.get(i);
                if (s.low() >= k || s.high() < k) { continue; }
                nearest = Math.min(nearest, d[i]);
                if (s.cliff()) {
                    if (d[i] < cliffD - TIE) { cliffD = d[i]; cliffDrop = Math.max(3, s.high() - s.low()); }
                    else if (d[i] <= cliffD + TIE) { cliffD = Math.min(cliffD, d[i]); cliffDrop = Math.max(cliffDrop, s.high() - s.low()); }
                } else {
                    bankD = Math.min(bankD, d[i]);
                }
            }
            boolean in = own >= k;
            if (nearest == Float.POSITIVE_INFINITY) { z += in ? levelZ : 0; continue; }
            nearest = (float) Math.sqrt(nearest);
            if (nearest > farReach) {
                // Beyond FAR the level is whole or absent, as both hexes' lists agree there.
                z += in ? levelZ : 0;
                if (in) { bankRim = Math.min(bankRim, nearest - .95f * room); }
                else { bankFoot = Math.min(bankFoot, nearest - .95f * room); }
                continue;
            }
            bankD = (float) Math.sqrt(bankD);
            cliffD = (float) Math.sqrt(cliffD);
            float s = in ? -nearest : nearest;
            // A cliff's weight against a bank of the same level set; steps further than FAR from the point have none,
            // so two hexes' segment lists, which differ only beyond it, give their shared samples one height.
            float base = bankD == Float.POSITIVE_INFINITY ? 1 : Math.min(1, bankD / taper) * bankD / (bankD + cliffD + 1e-6f);
            float weight = cliffD == Float.POSITIVE_INFINITY ? 0 : (base + (1 - base) * far(bankD)) * (1 - far(cliffD));
            float share = 0;
            boolean step = false;
            if (weight > 0) {
                float a = Math.min(.3f, BoardRelief.metres(6) / (cliffDrop * levelZ))
                      * (.7f + .6f * BoardRelief.noise(x / BoardRelief.metres(9) - 3.3f, y / BoardRelief.metres(9) + 6.6f));
                float talus = s >= room ? 0 : a * (1 - s / room) * (1 - s / room);
                share = weight * (in ? 1 : talus);
                step = !in && s < room;
            }
            if (weight < 1) {
                float sb = s + bankMix * (smoothDistance(shares, k, x, y, centres, count) - s);
                share += (1 - weight) * bank(sb, x, y);
                // A bank's face spans its band, as today's does; the top cover's rim and foot distances run from its edges.
                step |= Math.abs(sb) < .95f * room;
                if (in) { bankRim = Math.min(bankRim, Math.max(0, -sb - .95f * room)); }
                else { bankFoot = Math.min(bankFoot, Math.max(0, sb - .95f * room)); }
            }
            if (step) {
                role = 1;
                if (Math.abs(s) < band) { band = Math.abs(s); rock = weight; }
            }
            z += share * levelZ;
        }
        out[0] = z;
        out[1] = role;
        out[2] = rock;
        out[3] = Math.min(rim, bankRim);
        out[4] = Math.min(foot, bankFoot);
        out[5] = nearLevels;
    }
}
