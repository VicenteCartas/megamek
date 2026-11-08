/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/** Solid columns and floor slabs, clipped to the building's external wall outline. */
final class GpuBuildingInterior {
    private static final float SPACING = 22;
    private static final float STRUT_WIDTH = 1.6f;
    /** A floor slab's depth below its walking surface. */
    static final float SLAB = 1.2f;
    /** How far a floor's edge stays from a wall face beside its slab, beyond depth-buffer precision. */
    static final float CLEARANCE = .2f;
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

    private GpuBuildingInterior() { }

    /** Weld triangle seams and reverse faces for the section graph, without depending on material normals. */
    private record Point(long x, long y) {
        static Point at(Vector3 a, Vector3 b, float z) {
            float t = (z - a.z) / (b.z - a.z);
            return new Point(Math.round((a.x + t * (b.x - a.x)) * 1000),
                  Math.round((a.y + t * (b.y - a.y)) * 1000));
        }
    }

    /** Mid-storey sections ignore horizontal roof overhangs, foundation borders and floor ledges. */
    static Area walls(List<Vector3> triangles, float z) {
        Map<Point, Set<Point>> edges = new LinkedHashMap<>();
        for (int i = 0; i < triangles.size(); i += 3) {
            List<Point> crossings = new ArrayList<>(2);
            for (int side = 0; side < 3; side++) {
                Vector3 a = triangles.get(i + side), b = triangles.get(i + (side + 1) % 3);
                if ((a.z > z) != (b.z > z)) { crossings.add(Point.at(a, b, z)); }
            }
            if (crossings.size() != 2 || crossings.get(0).equals(crossings.get(1))) { continue; }
            Point a = crossings.get(0), b = crossings.get(1);
            edges.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
            edges.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
        }
        List<Area> contours = new ArrayList<>();
        for (Point start : edges.keySet()) {
            while (!edges.get(start).isEmpty()) {
                Path2D.Double path = new Path2D.Double();
                path.moveTo(start.x / 1000.0, start.y / 1000.0);
                Point current = start;
                do {
                    if (edges.get(current).isEmpty()) { break; }
                    Point next = edges.get(current).iterator().next();
                    edges.get(current).remove(next);
                    edges.get(next).remove(current);
                    current = next;
                    path.lineTo(current.x / 1000.0, current.y / 1000.0);
                } while (!current.equals(start));
                // An open decorative panel is not an enclosed building footprint.
                if (current.equals(start)) { path.closePath(); contours.add(new Area(path)); }
            }
        }
        // Filling nested contours includes the wall thickness: the external boundary is authoritative here.
        Area result = new Area();
        contours.forEach(result::add);
        return result;
    }

    /**
     * The wall faces beside a floor slab topped at {@code top}: where {@code shell} crosses the slab at five heights
     * through its depth, as segments {x1, y1, x2, y2} with the face's front to their right, each once and a straight run
     * of them as one.
     */
    static List<float[]> faces(List<Vector3> shell, float top) {
        Map<Point, Set<Point>> segments = new LinkedHashMap<>();
        for (int step = 0; step <= 4; step++) {
            float z = top - .01f - step * (SLAB - .02f) / 4;
            for (int i = 0; i < shell.size(); i += 3) {
                List<Point> crossings = new ArrayList<>(2);
                for (int side = 0; side < 3; side++) {
                    Vector3 a = shell.get(i + side), b = shell.get(i + (side + 1) % 3);
                    if ((a.z > z) != (b.z > z)) { crossings.add(Point.at(a, b, z)); }
                }
                if (crossings.size() != 2 || crossings.get(0).equals(crossings.get(1))) { continue; }
                Point a = crossings.get(0), b = crossings.get(1);
                Vector3 p = shell.get(i), q = shell.get(i + 1), r = shell.get(i + 2);
                float normalX = (q.y - p.y) * (r.z - p.z) - (q.z - p.z) * (r.y - p.y);
                float normalY = (q.z - p.z) * (r.x - p.x) - (q.x - p.x) * (r.z - p.z);
                // Its right-hand side, (dy, -dx), faces the way the triangle does.
                if ((b.y - a.y) * normalX - (b.x - a.x) * normalY < 0) { Point swap = a; a = b; b = swap; }
                segments.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
            }
        }
        List<float[]> result = new ArrayList<>();
        for (Point start : segments.keySet()) {
            for (Point next : List.copyOf(segments.get(start))) {
                if (!segments.get(start).remove(next)) { continue; }
                Point end = next;
                // On while the only way on is straight ahead, within the weld tolerance.
                while (segments.containsKey(end) && segments.get(end).size() == 1) {
                    Point after = segments.get(end).iterator().next();
                    double dx = end.x - start.x, dy = end.y - start.y, ax = after.x - end.x, ay = after.y - end.y;
                    if (dx * ax + dy * ay <= 0 || Math.abs(dx * ay - dy * ax) > 2 * Math.hypot(dx, dy)) { break; }
                    segments.get(end).remove(after);
                    end = after;
                }
                result.add(new float[] { start.x / 1000f, start.y / 1000f, end.x / 1000f, end.y / 1000f });
            }
        }
        return result;
    }

    /**
     * {@code floor} with each stretch of its edge that an outward face ({@link #faces}) comes within {@link #CLEARANCE}
     * of cut back to that clearance, so the edge never lies on a wall's outside and flickers through it, at an open end
     * either. A face turned towards the floor hides the slab's edge, which faces away. The outline keeps its shape: a
     * cut adds a few corners. A wall further inside meets the floor at a line, which does not flicker.
     */
    static Area keepClear(Area floor, List<float[]> faces) {
        Path2D.Float cuts = new Path2D.Float();
        boolean any = false;
        float[] point = new float[6];
        float firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        for (var path = floor.getPathIterator(null); !path.isDone(); path.next()) {
            int kind = path.currentSegment(point);
            if (kind == PathIterator.SEG_MOVETO) { firstX = lastX = point[0]; firstY = lastY = point[1]; continue; }
            float ax = lastX, ay = lastY, bx = kind == PathIterator.SEG_CLOSE ? firstX : point[0];
            float by = kind == PathIterator.SEG_CLOSE ? firstY : point[1];
            lastX = bx; lastY = by;
            float length = (float) Math.hypot(bx - ax, by - ay);
            if (length < .001f) { continue; }
            // Along the edge (u) and into the floor (n).
            float ux = (bx - ax) / length, uy = (by - ay) / length, nx = -uy, ny = ux;
            if (!floor.contains((ax + bx) / 2 + nx * .01f, (ay + by) / 2 + ny * .01f)) { nx = -nx; ny = -ny; }
            float minX = Math.min(ax, bx) - CLEARANCE, maxX = Math.max(ax, bx) + CLEARANCE;
            float minY = Math.min(ay, by) - CLEARANCE, maxY = Math.max(ay, by) + CLEARANCE;
            for (float[] face : faces) {
                if (Math.max(face[0], face[2]) < minX || Math.min(face[0], face[2]) > maxX
                      || Math.max(face[1], face[3]) < minY || Math.min(face[1], face[3]) > maxY
                      || (face[3] - face[1]) * nx - (face[2] - face[0]) * ny >= 0) { continue; }
                // The face in the edge's frame, kept to its stretch and to the band within the clearance of it.
                float s0 = (face[0] - ax) * ux + (face[1] - ay) * uy, t0 = (face[0] - ax) * nx + (face[1] - ay) * ny;
                float s1 = (face[2] - ax) * ux + (face[3] - ay) * uy, t1 = (face[2] - ax) * nx + (face[3] - ay) * ny;
                float[] clipped = clip(s0, t0, s1, t1, -CLEARANCE, length + CLEARANCE, -CLEARANCE, CLEARANCE);
                if (clipped == null) { continue; }
                float from = Math.max(0, Math.min(clipped[0], clipped[2]) - CLEARANCE);
                float to = Math.min(length, Math.max(clipped[0], clipped[2]) + CLEARANCE);
                float depth = CLEARANCE + Math.max(clipped[1], clipped[3]);
                cuts.moveTo(ax + ux * from - nx * .01f, ay + uy * from - ny * .01f);
                cuts.lineTo(ax + ux * to - nx * .01f, ay + uy * to - ny * .01f);
                cuts.lineTo(ax + ux * to + nx * depth, ay + uy * to + ny * depth);
                cuts.lineTo(ax + ux * from + nx * depth, ay + uy * from + ny * depth);
                cuts.closePath();
                any = true;
            }
        }
        if (!any) { return floor; }
        Area result = new Area(floor);
        result.subtract(new Area(cuts));
        return result;
    }

    /** The part of the segment (s0, t0)-(s1, t1) inside the box [sMin, sMax] x (tMin, tMax), or null (Liang-Barsky). */
    private static float[] clip(float s0, float t0, float s1, float t1, float sMin, float sMax, float tMin, float tMax) {
        float ds = s1 - s0, dt = t1 - t0, enter = 0, leave = 1;
        float[] p = { -ds, ds, -dt, dt }, q = { s0 - sMin, sMax - s0, t0 - tMin, tMax - t0 };
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] <= 0) { return null; }
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0) { enter = Math.max(enter, r); } else { leave = Math.min(leave, r); }
            }
        }
        if (enter > leave) { return null; }
        return new float[] { s0 + enter * ds, t0 + enter * dt, s0 + leave * ds, t0 + leave * dt };
    }

    static Area area(List<Vector3> triangles) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        for (int i = 0; i < triangles.size(); i += 3) {
            Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
            float normal = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(normal) < .0001f) { continue; }
            if (normal < 0) { Vector3 swap = b; b = c; c = swap; }
            // Match the wall-section weld tolerance; import roundoff must not create sliver holes at cap seams.
            path.moveTo(Math.round(a.x * 1000) / 1000f, Math.round(a.y * 1000) / 1000f);
            path.lineTo(Math.round(b.x * 1000) / 1000f, Math.round(b.y * 1000) / 1000f);
            path.lineTo(Math.round(c.x * 1000) / 1000f, Math.round(c.y * 1000) / 1000f);
            path.closePath();
        }
        return new Area(path);
    }

    static List<Vector3> triangles(Area footprint) {
        List<Vector3> result = new ArrayList<>();
        for (var triangle : BoardTacticalGeometry.flat(footprint, 0)) {
            result.add(triangle.a());
            result.add(triangle.b());
            result.add(triangle.c());
        }
        return List.copyOf(result);
    }

    static Model build(Model shell, int levels) {
        float height = shell.calculateBoundingBox(new BoundingBox()).max.z;
        var triangles = GpuTerrain.triangles(shell);
        var roof = plane(triangles, height, true);
        // Curved dome shells have no horizontal top cap. Their projected shell still bounds an explicit interior override.
        if (roof.isEmpty()) { roof = triangles(area(triangles)); }
        return build(roof, height, levels);
    }

    /** Horizontal cap faces delimit occupied volume; roof furniture and bounding boxes cannot fill courtyards. */
    static List<Vector3> plane(List<Vector3> triangles, float z, boolean upward) {
        List<Vector3> result = new ArrayList<>();
        for (int index = 0; index < triangles.size(); index += 3) {
            Vector3 a = triangles.get(index), b = triangles.get(index + 1), c = triangles.get(index + 2);
            float normal = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(a.z - z) < .001f && Math.abs(b.z - z) < .001f && Math.abs(c.z - z) < .001f
                  && (upward ? normal > .0001f : normal < -.0001f)) {
                // Always return upward-facing triangles for the shared interior generator.
                result.add(a);
                result.add(upward ? b : c);
                result.add(upward ? c : b);
            }
        }
        return List.copyOf(result);
    }

    /** Extrude the clipped external footprint through the wall stack for the generated cutaway interior. */
    static Model build(List<Vector3> roof, float height, int levels) {
        if (roof.isEmpty() || height <= 0 || levels < 1) {
            throw new IllegalArgumentException("Building interior needs a footprint and positive height");
        }
        return buildStoreys(java.util.Collections.nCopies(levels, roof), height / levels, levels);
    }

    /** Floors and supports stay within their own storey's shell, including setbacks above shorter neighbours. */
    static Model buildStoreys(List<List<Vector3>> footprints, float levelHeight, int floorCount) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        builder.node().id = "struts";
        Material strutMaterial = new Material("struts", ColorAttribute.createDiffuse(0.29f, 0.31f, 0.33f, 1));
        for (int level = 0; level < footprints.size(); level++) {
            var columns = columns(footprints.get(level), levelHeight);
            if (columns.isEmpty()) { continue; }
            MeshPartBuilder struts = builder.part("struts-" + level, GL20.GL_TRIANGLES, ATTRIBUTES, strutMaterial);
            for (Vector3 column : columns) {
                BoxShapeBuilder.build(struts, column.x, column.y, (level + .499f) * levelHeight,
                      STRUT_WIDTH, STRUT_WIDTH, levelHeight);
            }
        }
        builder.node().id = "floors";
        Material floorMaterial = new Material("floors", ColorAttribute.createDiffuse(0.58f, 0.56f, 0.52f, 1));
        for (int level = 0; level < floorCount; level++) {
            if (footprints.get(level).isEmpty()) { continue; }
            // Separate parts let ModelBuilder split tall structures before a mesh exceeds its vertex limit.
            MeshPartBuilder floors = builder.part("floor-" + level, GL20.GL_TRIANGLES, ATTRIBUTES, floorMaterial);
            // Ground floor clears the terrain; upper floors sit at the game's level boundaries.
            float z = (level == 0 ? 0.002f : level) * levelHeight;
            floor(floors, footprints.get(level), z);
        }
        return builder.end();
    }

    private static List<Vector3> columns(List<Vector3> roof, float height) {
        BoundingBox bounds = new BoundingBox().inf();
        roof.forEach(bounds::ext);
        List<Vector3> columns = new ArrayList<>();
        int across = Math.max(1, (int) (bounds.getWidth() / SPACING));
        int along = Math.max(1, (int) (bounds.getHeight() / SPACING));
        for (int x = 0; x < across; x++) {
            for (int y = 0; y < along; y++) {
                addColumn(columns, roof, bounds.min.x + bounds.getWidth() * (x + 0.5f) / across,
                      bounds.min.y + bounds.getHeight() * (y + 0.5f) / along, height);
            }
        }
        // Small or disconnected wings can miss the grid. Give uncovered roof sections a nearby support.
        for (int index = 0; index < roof.size(); index += 3) {
            Vector3 center = new Vector3(roof.get(index)).add(roof.get(index + 1)).add(roof.get(index + 2)).scl(1f / 3);
            if (columns.stream().noneMatch(column -> (column.x - center.x) * (column.x - center.x)
                  + (column.y - center.y) * (column.y - center.y) < SPACING * SPACING)) {
                addColumn(columns, roof, center.x, center.y, height);
            }
        }
        return columns;
    }

    /** A floor's walking surface stays at its gameplay level; the slab extends below it and closes at its outline. */
    static void floor(MeshPartBuilder floors, List<Vector3> triangles, float z) {
        float bottom = z - SLAB;
        for (int index = 0; index < triangles.size(); index += 3) {
            Vector3 a = triangles.get(index), b = triangles.get(index + 1), c = triangles.get(index + 2);
            floors.triangle(vertex(a, z, 1), vertex(b, z, 1), vertex(c, z, 1));
            floors.triangle(vertex(c, bottom, -1), vertex(b, bottom, -1), vertex(a, bottom, -1));
        }
        Vector3 first = new Vector3(), previous = new Vector3();
        float[] point = new float[6];
        for (var path = area(triangles).getPathIterator(null); !path.isDone(); path.next()) {
            int kind = path.currentSegment(point);
            if (kind == PathIterator.SEG_MOVETO) { first.set(point[0], point[1], z); previous.set(first); continue; }
            Vector3 next = kind == PathIterator.SEG_CLOSE ? first.cpy() : new Vector3(point[0], point[1], z);
            Vector3 normal = new Vector3(previous.y - next.y, next.x - previous.x, 0).nor();
            floors.rect(new Vector3(next.x, next.y, bottom), new Vector3(previous.x, previous.y, bottom),
                  previous, next, normal);
            previous.set(next);
        }
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, float z, float normalZ) {
        return new MeshPartBuilder.VertexInfo().setPos(point.x, point.y, z).setNor(0, 0, normalZ);
    }

    private static void addColumn(List<Vector3> columns, List<Vector3> roof, float x, float y, float height) {
        float half = STRUT_WIDTH / 2;
        // Checking the footprint keeps columns out of courtyards, gaps and concave outside corners.
        if (inside(roof, x, y) && inside(roof, x - half, y - half) && inside(roof, x + half, y - half)
              && inside(roof, x - half, y + half) && inside(roof, x + half, y + half)) {
            columns.add(new Vector3(x, y, height));
        }
    }

    private static boolean inside(List<Vector3> roof, float x, float y) {
        for (int index = 0; index < roof.size(); index += 3) {
            Vector3 a = roof.get(index), b = roof.get(index + 1), c = roof.get(index + 2);
            if (Intersector.isPointInTriangle(x, y, a.x, a.y, b.x, b.y, c.x, c.y)) {
                return true;
            }
        }
        return false;
    }
}
