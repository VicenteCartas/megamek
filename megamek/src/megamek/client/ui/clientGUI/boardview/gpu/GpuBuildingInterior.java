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
        float bottom = z - 1.2f;
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
