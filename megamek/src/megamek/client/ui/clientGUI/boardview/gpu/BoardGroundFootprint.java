/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;

/** The visible mesh inside the terrain-to-grass-top band, projected once for all roots on those surfaces. */
final class BoardGroundFootprint {
    private final Area body;
    private final Shape border, walls;
    private final Rectangle2D bounds;

    private BoardGroundFootprint(Area body, Shape border, Shape walls) {
        this.body = body; this.border = border; this.walls = walls;
        Rectangle2D extent = null;
        for (var shape : List.of(body, border, walls)) {
            var rectangle = shape.getBounds2D();
            if (!rectangle.isEmpty()) { extent = extent == null ? rectangle : extent.createUnion(rectangle); }
        }
        bounds = extent == null ? new Rectangle2D.Double() : extent;
    }

    boolean isEmpty() { return bounds.isEmpty(); }

    boolean contains(double x, double y) {
        return bounds.contains(x, y) && (body.contains(x, y) || border.contains(x, y) || walls.contains(x, y));
    }

    private record Plane(double x, double y, double z, double offset) {
        double distance(Vector3 p) { return x * p.x + y * p.y + z * p.z + offset; }
    }

    static BoardGroundFootprint of(List<Vector3> triangles, List<BoardSurface.Face> ground, float height, float radius) {
        var filled = new Path2D.Double(Path2D.WIND_NON_ZERO);
        var edges = new Path2D.Double();
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
            for (int i = 0; i < triangles.size(); i += 3) {
                var p = triangles.get(i); var q = triangles.get(i + 1); var r = triangles.get(i + 2);
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
                // Consistent winding unions overlapping faces. Vertical walls retain their projected line clearance.
                var path = Math.abs(area) < 1e-5 ? edges : filled;
                if (area < 0) { polygon = polygon.reversed(); }
                path.moveTo(polygon.getFirst().x, polygon.getFirst().y);
                for (int j = 1; j < polygon.size(); j++) { path.lineTo(polygon.get(j).x, polygon.get(j).y); }
                path.closePath();
            }
        }
        var result = new Area(filled);
        if (radius > 0) {
            var stroke = new BasicStroke(radius * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            // Keep the strokes as paths. Boolean-unioning thousands of overlapping rounded wall caps is costly;
            // containment needs only their union's logical OR, with the same exact blade clearance.
            return new BoardGroundFootprint(result, stroke.createStrokedShape(result), stroke.createStrokedShape(edges));
        }
        return new BoardGroundFootprint(result, new Path2D.Double(), new Path2D.Double());
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
