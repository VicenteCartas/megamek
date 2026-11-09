/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;

/**
 * The visible mesh inside the terrain-to-grass-top band, projected once for all roots on those surfaces: its clipped
 * polygons, and within {@code radius} of their edges (the wall slivers' too), kept in a grid of cells seen from above. A
 * tall or authored building's thousands of overlapping polygons are never merged into one shape: a point only tests the
 * few in its cell.
 */
final class BoardGroundFootprint {
    /** The grid's cells along its longer side: a cell holds a handful of polygons. */
    private static final int CELLS = 48;
    private final double radius, minX, minY, maxX, maxY, cell;
    private final int columns, rows;
    /** Filled polygons first, then wall slivers ({@link #solid}), each as x, y pairs counter-clockwise. */
    private final List<double[]> polygons;
    private final int solid;
    private final int[][] grid;

    private BoardGroundFootprint(List<double[]> filled, List<double[]> walls, double radius) {
        this.radius = radius;
        polygons = new ArrayList<>(filled);
        polygons.addAll(walls);
        solid = filled.size();
        double x0 = Double.MAX_VALUE, y0 = x0, x1 = -Double.MAX_VALUE, y1 = x1;
        for (double[] xy : polygons) {
            for (int j = 0; j < xy.length; j += 2) {
                x0 = Math.min(x0, xy[j]); x1 = Math.max(x1, xy[j]); y0 = Math.min(y0, xy[j + 1]); y1 = Math.max(y1, xy[j + 1]);
            }
        }
        minX = x0 - radius; minY = y0 - radius; maxX = x1 + radius; maxY = y1 + radius;
        cell = Math.max(Math.max(maxX - minX, maxY - minY) / CELLS, 1e-3);
        columns = polygons.isEmpty() ? 0 : (int) Math.ceil((maxX - minX) / cell) + 1;
        rows = polygons.isEmpty() ? 0 : (int) Math.ceil((maxY - minY) / cell) + 1;
        List<List<Integer>> cells = new ArrayList<>();
        for (int i = 0; i < columns * rows; i++) { cells.add(new ArrayList<>()); }
        for (int k = 0; k < polygons.size(); k++) {
            double[] xy = polygons.get(k);
            double bx0 = Double.MAX_VALUE, by0 = bx0, bx1 = -Double.MAX_VALUE, by1 = bx1;
            for (int j = 0; j < xy.length; j += 2) {
                bx0 = Math.min(bx0, xy[j]); bx1 = Math.max(bx1, xy[j]); by0 = Math.min(by0, xy[j + 1]); by1 = Math.max(by1, xy[j + 1]);
            }
            int c0 = (int) ((bx0 - radius - minX) / cell), c1 = (int) ((bx1 + radius - minX) / cell);
            int r0 = (int) ((by0 - radius - minY) / cell), r1 = (int) ((by1 + radius - minY) / cell);
            for (int row = Math.max(0, r0); row <= Math.min(rows - 1, r1); row++) {
                for (int column = Math.max(0, c0); column <= Math.min(columns - 1, c1); column++) {
                    cells.get(row * columns + column).add(k);
                }
            }
        }
        grid = new int[cells.size()][];
        for (int i = 0; i < cells.size(); i++) { grid[i] = cells.get(i).stream().mapToInt(Integer::intValue).toArray(); }
    }

    boolean isEmpty() { return columns == 0; }

    /** Inside a filled polygon, or within the radius of any polygon's edge: as round-capped strokes of the outlines. */
    boolean contains(double x, double y) {
        if (columns == 0 || x < minX || x > maxX || y < minY || y > maxY) { return false; }
        int column = Math.min(columns - 1, (int) ((x - minX) / cell)), row = Math.min(rows - 1, (int) ((y - minY) / cell));
        double r2 = radius * radius;
        for (int k : grid[row * columns + column]) {
            double[] xy = polygons.get(k);
            int n = xy.length / 2;
            boolean inside = k < solid;
            for (int j = 0; j < n; j++) {
                double ax = xy[2 * j], ay = xy[2 * j + 1], bx = xy[(2 * j + 2) % (2 * n)], by = xy[(2 * j + 3) % (2 * n)];
                if (inside && (bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0) { inside = false; }
                double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
                double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
                double ex = ax + t * dx - x, ey = ay + t * dy - y;
                if (radius > 0 && ex * ex + ey * ey <= r2) { return true; }
            }
            if (inside) { return true; }
        }
        return false;
    }

    private record Plane(double x, double y, double z, double offset) {
        double distance(Vector3 p) { return x * p.x + y * p.y + z * p.z + offset; }
    }

    static BoardGroundFootprint of(List<Vector3> triangles, List<BoardSurface.Face> ground, float height, float radius) {
        // One pass against the bounds of every face's prism: the per-face loop then sees only the triangles near the ground.
        double gx0 = Double.MAX_VALUE, gy0 = gx0, gz0 = gx0, gx1 = -Double.MAX_VALUE, gy1 = gx1, gz1 = gx1;
        for (var face : ground) {
            Vector3 a = face.a(), b = face.b(), c = face.c();
            double cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(cross) < 1e-5) { continue; }
            double px = ((b.z - a.z) * (c.y - a.y) - (c.z - a.z) * (b.y - a.y)) / cross;
            double py = ((b.x - a.x) * (c.z - a.z) - (c.x - a.x) * (b.z - a.z)) / cross;
            double margin = radius * Math.hypot(px, py);
            gx0 = Math.min(gx0, Math.min(a.x, Math.min(b.x, c.x)) - radius);
            gx1 = Math.max(gx1, Math.max(a.x, Math.max(b.x, c.x)) + radius);
            gy0 = Math.min(gy0, Math.min(a.y, Math.min(b.y, c.y)) - radius);
            gy1 = Math.max(gy1, Math.max(a.y, Math.max(b.y, c.y)) + radius);
            gz0 = Math.min(gz0, Math.min(a.z, Math.min(b.z, c.z)) - margin - .02);
            gz1 = Math.max(gz1, Math.max(a.z, Math.max(b.z, c.z)) + margin + height);
        }
        List<Vector3> near = new ArrayList<>();
        for (int i = 0; i + 2 < triangles.size(); i += 3) {
            var p = triangles.get(i); var q = triangles.get(i + 1); var r = triangles.get(i + 2);
            if (Math.max(p.x, Math.max(q.x, r.x)) < gx0 || Math.min(p.x, Math.min(q.x, r.x)) > gx1
                  || Math.max(p.y, Math.max(q.y, r.y)) < gy0 || Math.min(p.y, Math.min(q.y, r.y)) > gy1
                  || Math.max(p.z, Math.max(q.z, r.z)) < gz0 || Math.min(p.z, Math.min(q.z, r.z)) > gz1) { continue; }
            near.add(p); near.add(q); near.add(r);
        }
        List<double[]> filled = new ArrayList<>(), walls = new ArrayList<>();
        for (var face : ground) {
            Vector3 a = face.a(), b = face.b(), c = face.c();
            double cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(cross) < 1e-5) { continue; }
            double px = ((b.z - a.z) * (c.y - a.y) - (c.z - a.z) * (b.y - a.y)) / cross;
            double py = ((b.x - a.x) * (c.z - a.z) - (c.x - a.x) * (b.z - a.z)) / cross;
            double offset = a.z - px * a.x - py * a.y;
            List<Plane> planes = new ArrayList<>();
            var corners = List.of(a, b, c);
            for (int i = 0; i < 3; i++) {
                var p = corners.get(i); var q = corners.get((i + 1) % 3);
                double nx = (p.y - q.y) * Math.signum(cross), ny = (q.x - p.x) * Math.signum(cross);
                planes.add(new Plane(nx, ny, 0, -nx * p.x - ny * p.y + radius * Math.hypot(nx, ny)));
            }
            // A small tolerance includes coplanar foundations; a raised part only counts within blade reach.
            planes.add(new Plane(-px, -py, 1, -offset + .02));
            planes.add(new Plane(px, py, -1, offset + height));
            double minX = Math.min(a.x, Math.min(b.x, c.x)) - radius, maxX = Math.max(a.x, Math.max(b.x, c.x)) + radius;
            double minY = Math.min(a.y, Math.min(b.y, c.y)) - radius, maxY = Math.max(a.y, Math.max(b.y, c.y)) + radius;
            double margin = radius * Math.hypot(px, py);
            double minZ = Math.min(a.z, Math.min(b.z, c.z)) - margin - .02;
            double maxZ = Math.max(a.z, Math.max(b.z, c.z)) + margin + height;
            for (int i = 0; i < near.size(); i += 3) {
                var p = near.get(i); var q = near.get(i + 1); var r = near.get(i + 2);
                if (Math.max(p.x, Math.max(q.x, r.x)) < minX || Math.min(p.x, Math.min(q.x, r.x)) > maxX
                      || Math.max(p.y, Math.max(q.y, r.y)) < minY || Math.min(p.y, Math.min(q.y, r.y)) > maxY
                      || Math.max(p.z, Math.max(q.z, r.z)) < minZ || Math.min(p.z, Math.min(q.z, r.z)) > maxZ) { continue; }
                List<Vector3> polygon = List.of(p, q, r);
                for (var plane : planes) { polygon = clip(polygon, plane); }
                if (polygon.size() < 3) { continue; }
                double area = 0;
                for (int j = 0; j < polygon.size(); j++) {
                    var u = polygon.get(j); var v = polygon.get((j + 1) % polygon.size());
                    area += (double) u.x * v.y - (double) v.x * u.y;
                }
                // Vertical walls keep their projected line clearance; the rest fill, counter-clockwise.
                if (area < 0) { polygon = polygon.reversed(); }
                double[] xy = new double[polygon.size() * 2];
                for (int j = 0; j < polygon.size(); j++) { xy[2 * j] = polygon.get(j).x; xy[2 * j + 1] = polygon.get(j).y; }
                (Math.abs(area) < 1e-5 ? walls : filled).add(xy);
            }
        }
        // Without a radius only the filled polygons count, as before: a wall sliver has no area.
        return new BoardGroundFootprint(filled, radius > 0 ? walls : List.of(), radius);
    }

    private static List<Vector3> clip(List<Vector3> polygon, Plane plane) {
        if (polygon.isEmpty()) { return polygon; }
        List<Vector3> result = new ArrayList<>(polygon.size() + 1);
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            double da = plane.distance(a), db = plane.distance(b);
            if (da >= 0) { result.add(a); }
            if (da < 0 != db < 0 && da != 0 && db != 0) { result.add(new Vector3(a).lerp(b, (float) (da / (da - db)))); }
        }
        return result;
    }
}
