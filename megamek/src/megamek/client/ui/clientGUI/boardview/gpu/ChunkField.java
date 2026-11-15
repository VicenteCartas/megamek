/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.board.Coords;

/**
 * One terrain chunk's height field at one detail ({@link TerrainField}): a block of samples per hex the field takes, in
 * plain arrays. Built on the chunk's preparation worker from the scene revision's {@link FieldBoard}; its blocks feed
 * the chunk's {@link ChunkMesh} and the hexes' {@link BoardSurface} adapters (support, picking, the old passes).
 */
final class ChunkField {
    /**
     * A wall's rows, as shares of its height: today's cliff rows and the midpoints between them, as fine as today's
     * walls, which the rock relief needs to show its joints; coarser detail takes every second or fourth.
     */
    private static final float[] ROWS = { 0f, .055f, .11f, .21f, .31f, .43f, .55f, .665f, .78f, .8575f, .935f, .9675f, 1f };

    final FieldBoard board;
    final TerrainLod lod;
    final float floor;
    private final Map<Coords, Block> blocks = new ConcurrentHashMap<>();
    /** The banks' level-set grids, one per chunk of {@link TerrainLod#CHUNK_SIZE} hexes. */
    private final Map<Coords, FieldBoard.Shares> shares = new ConcurrentHashMap<>();

    ChunkField(FieldBoard board, TerrainLod lod, float floor) {
        this.board = board;
        this.lod = lod;
        this.floor = floor;
    }

    static int segments(TerrainLod lod) {
        return switch (lod) { case FULL -> 8; case MEDIUM -> 4; case COARSE, DISTANT -> 2; };
    }

    /** The level-set grid over the chunk holding h, with a hex's margin. */
    FieldBoard.Shares shares(Coords h) {
        int size = TerrainLod.CHUNK_SIZE;
        Coords chunk = new Coords(h.getX() / size * size, h.getY() / size * size);
        return shares.computeIfAbsent(chunk, at -> {
            Coords far = new Coords(at.getX() + size - 1, at.getY() + size - 1);
            float w = board.width, ht = board.height;
            return board.new Shares(board.centerX(at) - w, board.centerY(far) - 1.5f * ht, board.centerX(far) + w,
                  board.centerY(at) + 1.5f * ht);
        });
    }

    /** The hex's block, evaluated once on the first ask; null where the field does not take the hex. */
    Block block(Coords h) {
        return board.taken(h) ? blocks.computeIfAbsent(h, at -> new Builder(this, at).run()) : null;
    }

    /**
     * One hex's samples (structure of arrays), lattice samples first, then its walls' samples, and its triangles by
     * group as index triples into them. Lattice samples carry the field's values; wall samples their own.
     * {@code faceRim} and {@code faceFoot}: a step or wall sample's height above its foot and depth below its rim, in
     * metres; {@code rim}, {@code foot}: a top sample's distances to the nearest drop and rise, in metres.
     * {@code parents}: per sample six floats, its coarse parent (field-morph.glsl), the position the next coarser detail
     * has at its place minus its own, and that detail's normal there; NaN where the sample is its own parent.
     * {@code sides}: the neighbouring chunk a sample borders (ChunkMesh), 0 none.
     */
    record Block(Coords coords, int family, int level, int lattice, float[] x, float[] y, float[] z, float[] nx,
          float[] ny, float[] nz, float[] role, float[] rock, float[] rim, float[] foot, float[] steps, float[] faceRim,
          float[] faceFoot, float[] projection, int[] levelTops, int[] ground, int[] faces, int[] walls,
          float[] bounds, float[] parents, int[] sides) {
        int size() { return x.length; }

        /** Edge e's row of lattice samples, x and y, from corner e to corner e + 1 (where its wall and rocks stand). */
        float[][] row(int e) {
            int n = (int) Math.round((Math.sqrt(12.0 * (lattice - 1) + 9) - 3) / 6);
            float[][] row = new float[n + 1][];
            for (int j = 0; j <= n; j++) {
                int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                row[j] = new float[] { x[v], y[v] };
            }
            return row;
        }

        /** A top sample's distance to the nearest drop, in metres, as today's ground carries it. */
        float rimMetres(int v) { return Math.clamp(rim[v] / BoardRelief.metres(1), 0, 99); }

        /** A top sample's distance to the nearest rise, in metres. */
        float footMetres(int v) { return Math.clamp(foot[v] / BoardRelief.metres(1), 0, 99); }

        /** A top sample's occlusion: darker toward the foot of a rise (today's groundOcclusion, approximately). */
        float occlusion(int v) { return 1 - .3f * (float) Math.exp(-footMetres(v) / 1.6f); }

        /** A top sample's tint: the height of the nearest step, in levels up to seven. */
        float coverTint(int v) { return .3f + .1f * Math.min(steps[v], 7); }

        /** A face sample's occlusion: a bank darkens toward its middle, as today's banks do; rock keeps its light. */
        float faceOcclusion(int v) {
            if (rock[v] >= .5f) { return 1; }
            float span = faceRim[v] + faceFoot[v];
            return span <= 0 ? 1 : 1 - .15f * (float) Math.sin(Math.PI * faceFoot[v] / span);
        }

        /** A face sample's rock bed hardness (BoardRelief#bed). */
        float bed(int v) { return BoardRelief.bed(x[v], y[v], z[v], family); }

        /** The edge (by edge index) nearest the triangle at index {@code i} of {@code list}: the edge it stands on. */
        int edge(int[] list, int i) {
            float px = (x[list[i]] + x[list[i + 1]] + x[list[i + 2]]) / 3, py = (y[list[i]] + y[list[i + 1]] + y[list[i + 2]]) / 3;
            float cx = megamek.common.board.HexTile.centerX(coords, BoardGeometry.width());
            float cy = megamek.common.board.HexTile.centerY(coords, BoardGeometry.height());
            float angle = (float) Math.atan2(py - cy, px - cx);
            int best = 0;
            float bestDot = Float.NEGATIVE_INFINITY;
            for (int e = 0; e < 6; e++) {
                float mx = (megamek.common.board.HexTile.cornerX(coords, e, BoardGeometry.width())
                      + megamek.common.board.HexTile.cornerX(coords, (e + 1) % 6, BoardGeometry.width())) / 2 - cx;
                float my = (megamek.common.board.HexTile.cornerY(coords, e, BoardGeometry.height())
                      + megamek.common.board.HexTile.cornerY(coords, (e + 1) % 6, BoardGeometry.height())) / 2 - cy;
                float dot = (float) Math.cos(angle - Math.atan2(my, mx));
                if (dot > bestDot) { bestDot = dot; best = e; }
            }
            return best;
        }
    }

    static int vertex(int k, int i, int j) {
        if (i == 0) { return 0; }
        if (j == i) { k = (k + 1) % 6; j = 0; }
        return 1 + 3 * i * (i - 1) + k * i + j;
    }

    /** One hex's evaluation into growable arrays. */
    private static final class Builder {
        final ChunkField chunk;
        final FieldBoard b;
        final Coords h;
        final int n, own, lattice;
        final List<FieldBoard.Segment> segments;
        final FieldBoard.Shares shares;
        int size;
        float[] x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim, faceFoot, projection;
        /** The wall samples' parents as they are made ({@link #wallParents}): sample, dx, dy, dz, nx, ny, nz. */
        float[] wallParent = new float[64];
        int wallParentCount;
        /** Each edge's wall samples, from {@code wallStart[e]} to before {@code wallEnd[e]}. */
        final int[] wallStart = new int[6], wallEnd = new int[6];
        final boolean[] flat;
        int[] levelTops = new int[48], ground = new int[48], faces = new int[48], walls = new int[48];
        int levelTopCount, groundCount, faceCount, wallCount;

        Builder(ChunkField chunk, Coords h) {
            this.chunk = chunk;
            b = chunk.board;
            this.h = h;
            n = segments(chunk.lod);
            own = b.level(h);
            segments = b.segments(h);
            shares = chunk.shares(h);
            lattice = 1 + 3 * n * (n + 1);
            int capacity = lattice * 2;
            x = new float[capacity]; y = new float[capacity]; z = new float[capacity]; nx = new float[capacity];
            ny = new float[capacity]; nz = new float[capacity]; role = new float[capacity]; rock = new float[capacity];
            rim = new float[capacity]; foot = new float[capacity]; steps = new float[capacity]; faceRim = new float[capacity];
            faceFoot = new float[capacity]; projection = new float[capacity];
            flat = new boolean[lattice];
            size = lattice;
        }

        Block run() {
            float cx = b.centerX(h), cy = b.centerY(h);
            float[] cornerX = new float[6], cornerY = new float[6];
            float[][] boundary = new float[6 * FieldBoard.FULL][];
            for (int k = 0; k < 6; k++) {
                cornerX[k] = b.cornerX(h, k);
                cornerY[k] = b.cornerY(h, k);
                float[][] e = b.edgeMove(h, k);
                for (int j = 0; j < FieldBoard.FULL; j++) { boundary[k * FieldBoard.FULL + j] = e[j]; }
            }
            float ownZ = own * b.levelZ, flatReach = BoardRelief.metres(8);
            float[] out = new float[6];
            for (int k = 0; k < 6; k++) {
                for (int i = 0; i <= n; i++) {
                    for (int j = 0; j < Math.max(1, i) && (i > 0 || k == 0); j++) {
                        int v = vertex(k, i, j);
                        float px = cx + (cornerX[k] - cx) * i / n + (cornerX[(k + 1) % 6] - cornerX[k]) * j / n;
                        float py = cy + (cornerY[k] - cy) * i / n + (cornerY[(k + 1) % 6] - cornerY[k]) * j / n;
                        // The boundary's move at this point's spoke, falling off linearly to the centre.
                        float dx = 0, dy = 0;
                        if (i > 0) {
                            float u = j * (float) FieldBoard.FULL / i, f = u - (int) u, s = i / (float) n;
                            float[] p0 = boundary[k * FieldBoard.FULL + (int) u];
                            float[] p1 = boundary[(k * FieldBoard.FULL + (int) u + 1) % (6 * FieldBoard.FULL)];
                            dx = (p0[0] + (p1[0] - p0[0]) * f) * s;
                            dy = (p0[1] + (p1[1] - p0[1]) * f) * s;
                        }
                        x[v] = px + dx;
                        y[v] = py + dy;
                        b.height(h, segments, shares, x[v], y[v], out);
                        z[v] = out[0]; role[v] = out[1]; rock[v] = out[2]; rim[v] = out[3]; foot[v] = out[4]; steps[v] = out[5];
                        // Flat and unmoved: merged triangles' straight edges then pass through every merged sample.
                        flat[v] = Math.abs(out[0] - ownZ) < 1e-3f && Math.min(out[3], out[4]) > flatReach
                              && Math.abs(dx) + Math.abs(dy) < 1e-4f;
                    }
                }
            }
            normals();
            for (int v = 0; v < lattice; v++) { shade(v); }
            for (int k = 0; k < 6; k++) { emit(k, 0, 0, n, 0, n, n); }
            for (int e = 0; e < 6; e++) {
                wallStart[e] = size;
                walls(e);
                wallEnd[e] = size;
            }
            float[] bounds = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                  Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            for (int v = 0; v < size; v++) {
                bounds[0] = Math.min(bounds[0], x[v]); bounds[1] = Math.min(bounds[1], y[v]); bounds[2] = Math.min(bounds[2], z[v]);
                bounds[3] = Math.max(bounds[3], x[v]); bounds[4] = Math.max(bounds[4], y[v]); bounds[5] = Math.max(bounds[5], z[v]);
            }
            return new Block(h, b.family(h), own, lattice, trim(x), trim(y), trim(z), trim(nx), trim(ny), trim(nz),
                  trim(role), trim(rock), trim(rim), trim(foot), trim(steps), trim(faceRim), trim(faceFoot),
                  trim(projection), java.util.Arrays.copyOf(levelTops, levelTopCount),
                  java.util.Arrays.copyOf(ground, groundCount), java.util.Arrays.copyOf(faces, faceCount),
                  java.util.Arrays.copyOf(walls, wallCount), bounds, parents(), sides());
        }

        /**
         * Every sample's parent: an odd lattice sample halves a coarse edge and takes its midpoint (along its ring,
         * outward, or across) and the mean of its ends' coarse normals; an even inner one stays and takes the coarse
         * normal, its triangles' as normals() makes them at the coarser detail (the outline's and flat samples' are the
         * same at every detail); a wall sample takes the coarser wall's point at its place (#wallParents).
         */
        private float[] parents() {
            float[] result = new float[size * 6];
            java.util.Arrays.fill(result, Float.NaN);
            float[] cx = new float[lattice], cy = new float[lattice], cz = new float[lattice];
            for (int k = 0; n >= 4 && k < 6; k++) {
                for (int i = 1; i <= n / 2; i++) {
                    for (int j = 0; j < i; j++) {
                        accumulate(vertex(k, 2 * i - 2, 2 * j), vertex(k, 2 * i, 2 * j), vertex(k, 2 * i, 2 * j + 2), cx, cy, cz);
                        if (j < i - 1) {
                            accumulate(vertex(k, 2 * i - 2, 2 * j), vertex(k, 2 * i, 2 * j + 2), vertex(k, 2 * i - 2, 2 * j + 2),
                                  cx, cy, cz);
                        }
                    }
                }
            }
            for (int v = 0; v < lattice; v++) {
                float len = (float) Math.sqrt(cx[v] * cx[v] + cy[v] * cy[v] + cz[v] * cz[v]);
                boolean coarse = n >= 4 && len > 0 && !flat[v] && v < lattice - 6 * n;
                cx[v] = coarse ? cx[v] / len : nx[v];
                cy[v] = coarse ? cy[v] / len : ny[v];
                cz[v] = coarse ? cz[v] / len : nz[v];
                if (coarse) {
                    int o = v * 6;
                    result[o] = result[o + 1] = result[o + 2] = 0;
                    result[o + 3] = cx[v];
                    result[o + 4] = cy[v];
                    result[o + 5] = cz[v];
                }
            }
            for (int k = 0; n >= 4 && k < 6; k++) {
                for (int i = 1; i <= n; i++) {
                    for (int j = 0; j < i; j++) {
                        if (i % 2 == 0 && j % 2 == 0) { continue; }
                        int a, c;
                        if (i % 2 == 1 && j % 2 == 1) { a = vertex(k, i - 1, j - 1); c = vertex(k, i + 1, j + 1); }
                        else if (i % 2 == 1) { a = vertex(k, i - 1, j); c = vertex(k, i + 1, j); }
                        else { a = vertex(k, i, j - 1); c = vertex(k, i, j + 1); }
                        int v = vertex(k, i, j), o = v * 6;
                        float px = cx[a] + cx[c], py = cy[a] + cy[c], pz = cz[a] + cz[c];
                        float len = (float) Math.sqrt(px * px + py * py + pz * pz);
                        result[o] = (x[a] + x[c]) / 2 - x[v];
                        result[o + 1] = (y[a] + y[c]) / 2 - y[v];
                        result[o + 2] = (z[a] + z[c]) / 2 - z[v];
                        result[o + 3] = px / len;
                        result[o + 4] = py / len;
                        result[o + 5] = pz / len;
                    }
                }
            }
            for (int i = 0; i < wallParentCount; i += 7) {
                System.arraycopy(wallParent, i + 1, result, (int) wallParent[i] * 6, 6);
            }
            return result;
        }

        /**
         * Which neighbouring chunk a sample on the hex's outline borders, its edge's walls' samples included (1-8, row
         * by row from the chunk to the lower left, the chunk itself skipped; 0 none): its morph is capped by that
         * chunk's, so no crack opens between chunks at different detail.
         */
        private int[] sides() {
            int[] result = new int[size];
            int chunkSize = TerrainLod.CHUNK_SIZE;
            for (int e = 0; e < 6; e++) {
                Coords g = h.translated(BoardGeometry.edgeDirection(e));
                int dx = Math.floorDiv(g.getX(), chunkSize) - Math.floorDiv(h.getX(), chunkSize);
                int dy = Math.floorDiv(g.getY(), chunkSize) - Math.floorDiv(h.getY(), chunkSize);
                if (dx == 0 && dy == 0) { continue; }
                int index = (dy + 1) * 3 + dx + 1;
                int side = index < 4 ? index + 1 : index;
                for (int j = 0; j <= n; j++) {
                    int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                    if (result[v] == 0) { result[v] = side; }
                }
                java.util.Arrays.fill(result, wallStart[e], wallEnd[e], side);
            }
            return result;
        }

        /**
         * A wall's samples' parents: the next coarser detail builds the same wall on every second lattice sample, with a
         * {@code ratio}th of the columns and rows every {@code coarseStride}; each sample's parent is that wall's surface at its place
         * (the triangles strip() cuts there), so at weight 0 this wall lies on the coarser one, rims and feet on the
         * coarser rows the ground morphs to.
         */
        private void wallParents(Wall w, int[][] columns, int[] rims, int[] feet2, int ratio, int stride,
              int coarseStride) {
            int fine = columns.length - 1, coarse = fine / ratio;
            int rows = (ROWS.length - 1) / coarseStride + 1;
            // Per coarser column: its rows' points and normals (x, y, z, nx, ny, nz), then its rim and foot.
            float[][] grid = new float[coarse + 1][];
            boolean[] tall = new boolean[coarse + 1];
            float[] out = new float[4];
            for (int c = 0; c <= coarse; c++) {
                w.column(c, coarse, 2);
                tall[c] = w.top - w.bottom > .01f * b.metre;
                float[] column = grid[c] = new float[(rows + 2) * 6];
                for (int k = 0; k < rows; k++) {
                    if (tall[c]) { w.point(k * coarseStride, coarseStride, out); }
                    else { out[0] = w.px; out[1] = w.py; out[2] = w.top; }
                    set(column, k, out[0], out[1], out[2], w.onx, w.ony, 0);
                }
                // Lips are level rock; a column with no height is one sample, lit as the wall (walls()).
                set(column, rows, w.px, w.py, w.top, tall[c] ? 0 : w.onx, tall[c] ? 0 : w.ony, tall[c] ? 1 : 0);
                set(column, rows + 1, w.px, w.py, tall[c] ? w.bottom : w.top, tall[c] ? 0 : w.onx, tall[c] ? 0 : w.ony,
                      tall[c] ? 1 : 0);
            }
            // Its normals as wallNormals lights the coarser wall.
            for (int c = 0; c <= coarse; c++) {
                if (!tall[c]) { continue; }
                float[] before = grid[Math.max(0, c - 1)], after = grid[Math.min(coarse, c + 1)], column = grid[c];
                for (int k = 0; k < rows; k++) {
                    int down = Math.max(0, k - 1) * 6, up = Math.min(rows - 1, k + 1) * 6, at = k * 6;
                    float tx = after[at] - before[at], ty = after[at + 1] - before[at + 1], tz = after[at + 2] - before[at + 2];
                    float ux = column[up] - column[down], uy = column[up + 1] - column[down + 1];
                    float uz = column[up + 2] - column[down + 2];
                    float cx = ty * uz - tz * uy, cy = tz * ux - tx * uz, cz = tx * uy - ty * ux;
                    if (cx * w.onx + cy * w.ony < 0) { cx = -cx; cy = -cy; cz = -cz; }
                    float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (len < 1e-6f) { continue; }
                    column[at + 3] = cx / len;
                    column[at + 4] = cy / len;
                    column[at + 5] = cz / len;
                }
            }
            float[] p = new float[6];
            for (int c = 0; c <= fine; c++) {
                int c0 = Math.min(c / ratio, coarse), c1 = Math.min(c0 + 1, coarse);
                float fc = c0 == c1 ? 0 : (c - c0 * ratio) / (float) ratio;
                int[] column = columns[c];
                if (column.length == 1) {
                    // A column with no height stands at the coarser wall's top.
                    surface(grid[c0], grid[c1], rows - 1, rows - 1, fc, 0, p);
                    parent(column[0], p, false);
                    continue;
                }
                for (int i = 0; i < column.length; i++) {
                    int r = i * stride, k0 = Math.min(r / coarseStride, rows - 1), k1 = Math.min(k0 + 1, rows - 1);
                    float s0 = share(k0 * coarseStride, coarseStride), s1 = share(k1 * coarseStride, coarseStride);
                    float fr = k0 == k1 ? 0 : (share(r, stride) - s0) / (s1 - s0);
                    surface(grid[c0], grid[c1], k0, k1, fc, fr, p);
                    parent(column[i], p, true);
                }
                surface(grid[c0], grid[c1], rows, rows, fc, 0, p);
                lipNormal(grid[c0], coincide(grid[c0], rows - 1, rows) ? rows - 1 : rows, grid[c1], rows, fc, p);
                parent(rims[c], p, true);
                surface(grid[c0], grid[c1], rows + 1, rows + 1, fc, 0, p);
                lipNormal(grid[c0], rows + 1, grid[c1], coincide(grid[c1], 0, rows + 1) ? 0 : rows + 1, fc, p);
                parent(feet2[c], p, true);
            }
        }

        /** Whether rows k and q of a coarser column stand at one point (a lip with no width there). */
        private boolean coincide(float[] column, int k, int q) {
            float dx = column[k * 6] - column[q * 6], dy = column[k * 6 + 1] - column[q * 6 + 1], dz = column[k * 6 + 2] - column[q * 6 + 2];
            return dx * dx + dy * dy + dz * dz < 1e-6f * b.metre * b.metre;
        }

        /**
         * A lip's normal on the coarser wall at fc from column a to column c, each lit by its row na, nc: where a lip
         * has no width at a column (the foot's at a pair's second column, the rim's at its first), strip() covers the
         * lip beside it with the triangle holding the wall's point there, so the coarser wall shades the lip toward
         * that point's normal.
         */
        private static void lipNormal(float[] a, int na, float[] c, int nc, float fc, float[] p) {
            float x = a[na * 6 + 3] + (c[nc * 6 + 3] - a[na * 6 + 3]) * fc, y = a[na * 6 + 4] + (c[nc * 6 + 4] - a[na * 6 + 4]) * fc;
            float z = a[na * 6 + 5] + (c[nc * 6 + 5] - a[na * 6 + 5]) * fc;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-6f) { return; }
            p[3] = x / len;
            p[4] = y / len;
            p[5] = z / len;
        }

        /** Row {@code k}'s point and normal of a coarser column. */
        private static void set(float[] column, int k, float px, float py, float pz, float pnx, float pny, float pnz) {
            int o = k * 6;
            column[o] = px; column[o + 1] = py; column[o + 2] = pz;
            column[o + 3] = pnx; column[o + 4] = pny; column[o + 5] = pnz;
        }

        /**
         * The coarser wall at {@code fc} across from column a to column c and {@code fr} up from row k0 to k1: on
         * strip()'s triangles (a k0, c k0, a k1) and (a k1, c k0, c k1); the normal blended the same way.
         */
        private static void surface(float[] a, float[] c, int k0, int k1, float fc, float fr, float[] result) {
            int lo = k0 * 6, hi = k1 * 6;
            for (int q = 0; q < 6; q++) {
                float pa = a[lo + q], pb = c[lo + q], pd = a[hi + q], pg = c[hi + q];
                result[q] = fc + fr <= 1 ? pa + fc * (pb - pa) + fr * (pd - pa)
                      : pg + (1 - fc) * (pd - pg) + (1 - fr) * (pb - pg);
            }
            float len = (float) Math.sqrt(result[3] * result[3] + result[4] * result[4] + result[5] * result[5]);
            for (int q = 3; q < 6; q++) { result[q] = len < 1e-6f ? 0 : result[q] / len; }
            if (len < 1e-6f) { result[5] = 1; }
        }

        /** Sample v's parent: the point {@code p} (and its normal, or v's own where {@code normal} is false). */
        private void parent(int v, float[] p, boolean normal) {
            if (wallParentCount + 7 > wallParent.length) {
                wallParent = java.util.Arrays.copyOf(wallParent, wallParent.length * 2);
            }
            wallParent[wallParentCount++] = v;
            wallParent[wallParentCount++] = p[0] - x[v];
            wallParent[wallParentCount++] = p[1] - y[v];
            wallParent[wallParentCount++] = p[2] - z[v];
            wallParent[wallParentCount++] = normal ? p[3] : nx[v];
            wallParent[wallParentCount++] = normal ? p[4] : ny[v];
            wallParent[wallParentCount++] = normal ? p[5] : nz[v];
        }

        /** Row r's share of a wall's height, rows taken every {@code stride}: the last at the rim. */
        private static float share(int r, int stride) { return r + stride >= ROWS.length ? 1 : ROWS[r]; }

        /**
         * One edge's wall: the lattice row it stands on, the heights on both sides, and how today's relief pushes it.
         * {@link #column} places a column along the edge, {@link #point} a row's point on it; walls() builds its own
         * detail with them and wallParents() the next coarser one, so both agree where they should.
         */
        private final class Wall {
            final Coords g;
            final List<FieldBoard.Segment> other;
            final boolean outside, wall;
            final float landform, onx, ony;
            final int family;
            final int[] samples;
            final float[] tops, feet, probe = new float[6];
            /** The current column's place on the row, its top and foot, rimPush and fade (#column). */
            float px, py, top, bottom, rimPush, fade;

            Wall(Coords g, List<FieldBoard.Segment> other, boolean outside, boolean wall, float landform, float onx,
                  float ony, int[] samples, float[] tops, float[] feet) {
                this.g = g; this.other = other; this.outside = outside; this.wall = wall; this.landform = landform;
                this.onx = onx; this.ony = ony; this.samples = samples; this.tops = tops; this.feet = feet;
                this.family = b.family(h);
            }

            /**
             * Column c of {@code columns} along the edge, on the lattice samples every {@code step}: its tops and feet
             * run straight between the samples', as both hexes' meshes do there.
             */
            void column(int c, int columns, int step) {
                int steps = n / step, split = columns / steps;
                int j = Math.min(c / split, steps - 1);
                float f = c / (float) split - j;
                int a = j * step, d = (j + 1) * step, v0 = samples[a], v1 = samples[d];
                px = x[v0] + (x[v1] - x[v0]) * f;
                py = y[v0] + (y[v1] - y[v0]) * f;
                top = tops[a] + (tops[d] - tops[a]) * f;
                bottom = feet[a] + (feet[d] - feet[a]) * f;
                // The rim row already carries the relief at the rim height (FieldBoard#rimMove): rows below move by the
                // relief relative to it, so the top row stays on the row.
                rimPush = outside || !wall ? 0
                      : BoardRelief.wallRelief(px, py, top, bottom, top, Math.max(landform, top - bottom), family);
                float fromEnd = Math.min(c, columns - c) / (float) columns * b.edge;
                fade = Math.clamp(fromEnd / (.15f * b.edge), 0, 1);
                fade = fade * fade * (3 - 2 * fade);
            }

            /** Row r's point (rows every {@code stride}) on the current column: x, y, z and its share into out. */
            void point(int r, int stride, float[] out) {
                float share = share(r, stride);
                float zr = bottom + (top - bottom) * share;
                // Today's rock relief, caprock and scree foot included; it fades out at the row's ends where walls
                // meet. A foot moved off the row stands on the lower ground there.
                float push = outside ? 0
                      : fade * (BoardRelief.wallRelief(px, py, zr, bottom, top, Math.max(landform, top - bottom), family)
                            - rimPush);
                float wx = px + onx * push, wy = py + ony * push;
                if (share <= 0 && other != null && push != 0) {
                    b.height(g, other, chunk.shares(g), wx, wy, probe);
                    zr = Math.min(zr, probe[0]);
                }
                out[0] = wx; out[1] = wy; out[2] = zr; out[3] = share;
            }
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

        private float[] trim(float[] values) { return java.util.Arrays.copyOf(values, size); }

        /**
         * The lattice samples' normals: area-weighted over the finest triangles round each inside the hex; on the hex's
         * edge, where the neighbour's triangles are not here, the field's own by differences, which both hexes compute
         * alike. Level ground straight up.
         */
        private void normals() {
            for (int k = 0; k < 6; k++) {
                for (int i = 1; i <= n; i++) {
                    for (int j = 0; j < i; j++) {
                        accumulate(vertex(k, i - 1, j), vertex(k, i, j), vertex(k, i, j + 1));
                        if (j < i - 1) { accumulate(vertex(k, i - 1, j), vertex(k, i, j + 1), vertex(k, i - 1, j + 1)); }
                    }
                }
            }
            float d = BoardRelief.metres(.5f);
            float[] o = new float[6];
            for (int v = 0; v < lattice; v++) {
                if (flat[v]) { nx[v] = 0; ny[v] = 0; nz[v] = 1; continue; }
                if (v >= lattice - 6 * n) {
                    // Forward differences half a metre out: two evaluations, the same in both hexes along a bank.
                    b.height(h, segments, shares, x[v] + d, y[v], o);
                    float zx = o[0];
                    b.height(h, segments, shares, x[v], y[v] + d, o);
                    float zy = o[0];
                    nx[v] = -(zx - z[v]) / d;
                    ny[v] = -(zy - z[v]) / d;
                    nz[v] = 1;
                }
                float len = (float) Math.sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v]);
                nx[v] /= len; ny[v] /= len; nz[v] /= len;
            }
        }

        private void accumulate(int a, int c, int e) { accumulate(a, c, e, nx, ny, nz); }

        /** Adds triangle (a, c, e)'s upward normal, its area's weight, to its corners' in {@code tx, ty, tz}. */
        private void accumulate(int a, int c, int e, float[] tx, float[] ty, float[] tz) {
            float ux = x[c] - x[a], uy = y[c] - y[a], uz = z[c] - z[a], vx = x[e] - x[a], vy = y[e] - y[a], vz = z[e] - z[a];
            float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
            if (cz < 0) { cx = -cx; cy = -cy; cz = -cz; }
            for (int p : new int[] { a, c, e }) { tx[p] += cx; ty[p] += cy; tz[p] += cz; }
        }

        /**
         * A lattice sample's normal (the field's own, by central differences half a metre apart; level ground straight
         * up) and, as a step face would draw it, its height above the step's foot, depth below its rim and a fixed
         * texture frame: the nearest step's normal, turned downhill.
         */
        private void shade(int v) {
            float m = b.metre;
            // Level ground never draws as a step face: no frame to find.
            if (flat[v]) { return; }
            float bottom = (float) Math.floor(z[v] / b.levelZ - 1e-3f) * b.levelZ;
            faceRim[v] = (z[v] - bottom) / m;
            faceFoot[v] = (bottom + b.levelZ - z[v]) / m;
            FieldBoard.Segment nearest = null;
            float best = Float.POSITIVE_INFINITY;
            for (FieldBoard.Segment g : segments) {
                float dg = g.distance(x[v], y[v]);
                if (dg < best) { best = dg; nearest = g; }
            }
            float sx = nearest == null ? nx[v] : nearest.by() - nearest.ay(), sy = nearest == null ? ny[v] : nearest.ax() - nearest.bx();
            if (sx * nx[v] + sy * ny[v] < 0) { sx = -sx; sy = -sy; }
            // A rock face keeps a fixed texture frame; a bank's, like today's, lets the shader project it.
            projection[v] = rock[v] >= .5f ? (float) Math.atan2(sy, sx) : Float.NaN;
        }

        private int addSample(float px, float py, float pz, float pnx, float pny, float aboveFoot, float belowRim, float frame) {
            if (size == x.length) { grow(); }
            int v = size++;
            x[v] = px; y[v] = py; z[v] = pz; nx[v] = pnx; ny[v] = pny; nz[v] = 0;
            role[v] = 1; rock[v] = 1; faceRim[v] = aboveFoot; faceFoot[v] = belowRim; projection[v] = frame;
            return v;
        }

        private void grow() {
            int capacity = x.length * 2;
            x = java.util.Arrays.copyOf(x, capacity); y = java.util.Arrays.copyOf(y, capacity);
            z = java.util.Arrays.copyOf(z, capacity); nx = java.util.Arrays.copyOf(nx, capacity);
            ny = java.util.Arrays.copyOf(ny, capacity); nz = java.util.Arrays.copyOf(nz, capacity);
            role = java.util.Arrays.copyOf(role, capacity); rock = java.util.Arrays.copyOf(rock, capacity);
            rim = java.util.Arrays.copyOf(rim, capacity); foot = java.util.Arrays.copyOf(foot, capacity);
            steps = java.util.Arrays.copyOf(steps, capacity); faceRim = java.util.Arrays.copyOf(faceRim, capacity);
            faceFoot = java.util.Arrays.copyOf(faceFoot, capacity);
            projection = java.util.Arrays.copyOf(projection, capacity);
        }

        /**
         * Where this hex stands above its neighbour along edge e's row, the wall between them with today's rock relief;
         * where the board ends, its cut down to the floor.
         */
        private void walls(int e) {
            Coords g = h.translated(BoardGeometry.edgeDirection(e));
            boolean outside = !b.inside(g);
            // Where the two hexes stand at one level their shared samples are equal: nothing to close.
            if (!outside && b.level(g) == own) { return; }
            List<FieldBoard.Segment> other = !outside && b.taken(g) ? b.segments(g) : null;
            boolean wall = !outside && b.cliff(h, g);
            // The step's height for its landforms (BoardRelief#drop): its levels, at least three for a wall, not the face
            // left above the talus; the rock's prominence and jointing follow it.
            float landform = outside ? own * b.levelZ - chunk.floor
                  : Math.max(own - b.level(g), wall ? 3 : 0) * b.levelZ;
            float otherZ = outside ? chunk.floor : b.level(g) * b.levelZ, m = b.metre;
            float c0x = b.cornerX(h, e), c0y = b.cornerY(h, e), c1x = b.cornerX(h, e + 1), c1y = b.cornerY(h, e + 1);
            float ex = c1x - c0x, ey = c1y - c0y, len = (float) Math.hypot(ex, ey);
            float onx = ey / len, ony = -ex / len;
            if ((b.centerX(h) - c0x) * onx + (b.centerY(h) - c0y) * ony > 0) { onx = -onx; ony = -ony; }
            float frame = (float) Math.atan2(ony, onx);
            int stride = outside ? ROWS.length - 1 : chunk.lod == TerrainLod.FULL ? 1 : chunk.lod == TerrainLod.MEDIUM ? 2 : 4;
            // Columns between the lattice samples too (today's walls are finer than the ground).
            int split = outside ? 1 : chunk.lod == TerrainLod.FULL ? 6 : chunk.lod == TerrainLod.MEDIUM ? 3 : 1;
            float[] out = new float[6], tops = new float[n + 1], feet = new float[n + 1];
            int[] samples = new int[n + 1];
            for (int j = 0; j <= n; j++) {
                int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                samples[j] = v;
                tops[j] = z[v];
                feet[j] = otherZ;
                if (other != null) {
                    b.height(g, other, chunk.shares(g), x[v], y[v], out);
                    feet[j] = out[0];
                }
            }
            Wall w = new Wall(g, other, outside, wall, landform, onx, ony, samples, tops, feet);
            int[][] columns = new int[n * split + 1][];
            // Where the relief moves a wall's rim or foot off the row, a level strip joins it to the row's own sample:
            // the caprock's lip on top, the scree's ledge below.
            int[] rims = new int[n * split + 1], feet2 = new int[n * split + 1];
            for (int c = 0; c <= n * split; c++) {
                w.column(c, n * split, 1);
                int j = Math.min(c / split, n - 1);
                float f = c / (float) split - j;
                float sampleRock = rock[samples[j]] + (rock[samples[j + 1]] - rock[samples[j]]) * f;
                float px = w.px, py = w.py, top = w.top, bottom = w.bottom;
                if (top - bottom > .01f * m) {
                    int[] rows = new int[ROWS.length];
                    int count = 0;
                    for (int r = 0; r < ROWS.length; r += stride) {
                        w.point(r, stride, out);
                        float zr = out[2];
                        int p = addSample(out[0], out[1], zr, onx, ony, (zr - bottom) / m, (top - zr) / m, frame);
                        rock[p] = wall || outside ? 1 : sampleRock;
                        rows[count++] = p;
                        if (out[3] >= 1) { break; }
                    }
                    columns[c] = java.util.Arrays.copyOf(rows, count);
                    rims[c] = addSample(px, py, top, onx, ony, top - bottom > 0 ? (top - bottom) / m : 0, 0, frame);
                    feet2[c] = addSample(px, py, bottom, onx, ony, 0, (top - bottom) / m, frame);
                    rock[rims[c]] = rock[feet2[c]] = wall || outside ? 1 : sampleRock;
                    // The lips are level rock: lit from above, their texture projected by the shader, not the wall's frame.
                    for (int p : new int[] { rims[c], feet2[c] }) {
                        nx[p] = 0;
                        ny[p] = 0;
                        nz[p] = 1;
                        projection[p] = Float.NaN;
                    }
                } else {
                    columns[c] = new int[] { addSample(px, py, top, onx, ony, 0, 0, frame) };
                    rock[columns[c][0]] = sampleRock;
                    rims[c] = feet2[c] = columns[c][0];
                }
                if (c > 0 && (columns[c - 1].length > 1 || columns[c].length > 1)) {
                    strip(columns[c - 1], columns[c]);
                    int[] a = columns[c - 1], d = columns[c];
                    strip(new int[] { rims[c - 1], a[a.length - 1] }, new int[] { rims[c], d[d.length - 1] });
                    strip(new int[] { a[0], feet2[c - 1] }, new int[] { d[0], feet2[c] });
                }
            }
            wallNormals(columns, onx, ony);
            // Full detail's walls morph toward medium's, medium's toward coarse's (every 4th or 6th column there).
            // The board's cut keeps its two rows and one column a lattice step at every detail.
            if (chunk.lod == TerrainLod.FULL || chunk.lod == TerrainLod.MEDIUM) {
                wallParents(w, columns, rims, feet2, outside ? 2 : chunk.lod == TerrainLod.FULL ? 4 : 6, stride,
                      outside ? stride : 2 * stride);
            }
        }

        /** A wall's normals from its displaced rows and columns, facing out of the hex: the rock relief lights. */
        private void wallNormals(int[][] columns, float onx, float ony) {
            for (int c = 0; c < columns.length; c++) {
                int[] column = columns[c];
                if (column.length < 2) { continue; }
                int[] before = columns[Math.max(0, c - 1)], after = columns[Math.min(columns.length - 1, c + 1)];
                for (int r = 0; r < column.length; r++) {
                    int a = before[Math.min(r, before.length - 1)], d = after[Math.min(r, after.length - 1)];
                    int down = column[Math.max(0, r - 1)], up = column[Math.min(column.length - 1, r + 1)];
                    float tx = x[d] - x[a], ty = y[d] - y[a], tz = z[d] - z[a];
                    float ux = x[up] - x[down], uy = y[up] - y[down], uz = z[up] - z[down];
                    float cx = ty * uz - tz * uy, cy = tz * ux - tx * uz, cz = tx * uy - ty * ux;
                    if (cx * onx + cy * ony < 0) { cx = -cx; cy = -cy; cz = -cz; }
                    float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (len < 1e-6f) { continue; }
                    int p = column[r];
                    nx[p] = cx / len;
                    ny[p] = cy / len;
                    nz[p] = cz / len;
                }
            }
        }

        /** Two columns joined: rows matched by index, the shorter one's top repeated. */
        private void strip(int[] a, int[] c) {
            int rows = Math.max(a.length, c.length);
            for (int r = 0; r + 1 < rows; r++) {
                int a0 = a[Math.min(r, a.length - 1)], a1 = a[Math.min(r + 1, a.length - 1)];
                int c0 = c[Math.min(r, c.length - 1)], c1 = c[Math.min(r + 1, c.length - 1)];
                if (a0 != a1) { walls = add(walls, wallCount, a0, c0, a1); wallCount += 3; }
                if (c0 != c1) { walls = add(walls, wallCount, a1, c0, c1); wallCount += 3; }
            }
        }

        private static int[] add(int[] list, int count, int a, int b, int c) {
            if (count + 3 > list.length) { list = java.util.Arrays.copyOf(list, list.length * 2); }
            list[count] = a;
            list[count + 1] = b;
            list[count + 2] = c;
            return list;
        }

        /** A sector triangle by its corners' (ring, along) indices: emitted whole when flat or finest, else as four. */
        private void emit(int k, int i0, int j0, int i1, int j1, int i2, int j2) {
            int extent = Math.max(Math.abs(i1 - i0), Math.max(Math.abs(i2 - i0), Math.abs(i2 - i1)));
            boolean level = allFlat(k, i0, j0, i1, j1, i2, j2);
            if (extent > 1 && !level) {
                int ia = (i0 + i1) / 2, ja = (j0 + j1) / 2, ib = (i1 + i2) / 2, jb = (j1 + j2) / 2;
                int ic = (i2 + i0) / 2, jc = (j2 + j0) / 2;
                emit(k, i0, j0, ia, ja, ic, jc);
                emit(k, ia, ja, i1, j1, ib, jb);
                emit(k, ic, jc, ib, jb, i2, j2);
                emit(k, ia, ja, ib, jb, ic, jc);
                return;
            }
            int a = vertex(k, i0, j0), c1 = vertex(k, i1, j1), c2 = vertex(k, i2, j2);
            // Counter-clockwise from above, as today's faces.
            float cross = (x[c1] - x[a]) * (y[c2] - y[a]) - (y[c1] - y[a]) * (x[c2] - x[a]);
            int second = cross >= 0 ? c1 : c2, third = cross >= 0 ? c2 : c1;
            if (level) {
                levelTops = add(levelTops, levelTopCount, a, second, third);
                levelTopCount += 3;
            } else if (role[a] + role[c1] + role[c2] >= 2) {
                faces = add(faces, faceCount, a, second, third);
                faceCount += 3;
            } else {
                ground = add(ground, groundCount, a, second, third);
                groundCount += 3;
            }
        }

        private boolean allFlat(int k, int i0, int j0, int i1, int j1, int i2, int j2) {
            int iMin = Math.min(i0, Math.min(i1, i2)), iMax = Math.max(i0, Math.max(i1, i2));
            float d = (float) ((j1 - j2) * (i0 - i2) + (i2 - i1) * (j0 - j2));
            for (int i = iMin; i <= iMax; i++) {
                for (int j = 0; j <= i; j++) {
                    float u = ((j1 - j2) * (i - i2) + (i2 - i1) * (j - j2)) / d;
                    float w = ((j2 - j0) * (i - i2) + (i0 - i2) * (j - j2)) / d;
                    if (u < -1e-4f || w < -1e-4f || 1 - u - w < -1e-4f) { continue; }
                    if (!flat[vertex(k, i, j)]) { return false; }
                }
            }
            return true;
        }
    }
}
