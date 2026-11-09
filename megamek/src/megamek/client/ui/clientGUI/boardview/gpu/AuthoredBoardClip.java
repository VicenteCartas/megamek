/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/** Clips a whole authored placement to the hex board, sealing only the board's exposed edges. Model units throughout. */
final class AuthoredBoardClip {
    static final String CAP = "authored-map-cut";
    private static final double EPSILON = .0001;
    private record Point(long u, long z) {
        static Point at(double u, double z) { return new Point(Math.round(u * 1000), Math.round(z * 1000)); }
    }
    private record Edge(double x, double y, double tx, double ty, double length) {
        double side(float px, float py) { return ty * (px - x) - tx * (py - y); }
        Point project(float[] p) { return Point.at(tx * (p[0] - x) + ty * (p[1] - y), p[2]); }
        float[] vertex(double u, double z) {
            return new float[] { (float) (x + tx * u), (float) (y + ty * u), (float) z,
                  (float) ty, (float) -tx, 0, 1, 1, 1, 1, 0, 0 };
        }
    }

    private AuthoredBoardClip() { }

    static ModelData clip(GeneratedMesh whole, ModelData source, int width, int height, List<BuildingVolumes.Solid> solids) {
        var outline = BuildingVolumes.boardOutline(width, height);
        Area board = BuildingVolumes.polygon(outline);
        Rectangle2D bounds = bounds(whole);
        if (bounds == null || board.contains(bounds)) { return whole.data(source.id, source); }
        Rectangle2D boardBounds = board.getBounds2D();
        GeneratedMesh result = new GeneratedMesh();
        whole.parts.forEach((material, part) -> {
            for (int offset = 0; offset < part.size; offset += 3 * RigidGlb.STRIDE) {
                var triangle = triangle(part.items, offset);
                Rectangle2D box = bounds(triangle);
                if (board.contains(box)) { result.polygon(material, triangle); continue; }
                if (box.getMaxX() < boardBounds.getMinX() || box.getMinX() > boardBounds.getMaxX()
                      || box.getMaxY() < boardBounds.getMinY() || box.getMinY() > boardBounds.getMaxY()) { continue; }
                // The union is concave. Partition only boundary-crossing triangles over the candidate hex prisms.
                int minX = Math.max(0, (int) Math.floor(box.getMinX() / (HexTile.MODEL_WIDTH * .75)) - 1);
                int maxX = Math.min(width - 1, (int) Math.ceil(box.getMaxX() / (HexTile.MODEL_WIDTH * .75)) + 1);
                int minY = Math.max(0, (int) Math.floor(-box.getMaxY() / HexTile.HEIGHT) - 2);
                int maxY = Math.min(height - 1, (int) Math.ceil(-box.getMinY() / HexTile.HEIGHT) + 1);
                float[] a = triangle.get(0), b = triangle.get(1), c = triangle.get(2);
                boolean vertical = Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) < EPSILON;
                Set<List<Float>> verticalFragments = new HashSet<>();
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        Coords at = new Coords(x, y);
                        List<float[]> polygon = triangle;
                        for (int corner = 0; corner < 6 && !polygon.isEmpty(); corner++) {
                            int next = (corner + 1) % 6;
                            double px = HexTile.cornerX(at, corner, HexTile.MODEL_WIDTH), py = HexTile.cornerY(at, corner, HexTile.HEIGHT);
                            double dx = HexTile.cornerX(at, next, HexTile.MODEL_WIDTH) - px, dy = HexTile.cornerY(at, next, HexTile.HEIGHT) - py;
                            polygon = GeneratedMesh.clip(polygon, (vx, vy) -> dx * (vy - py) - dy * (vx - px));
                        }
                        if (polygon.isEmpty()) { continue; }
                        if (vertical) {
                            var span = bounds(polygon);
                            float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
                            for (var vertex : polygon) { bottom = Math.min(bottom, vertex[2]); top = Math.max(top, vertex[2]); }
                            // Only identical fragments can be duplicates. Tolerant picking would lose walls just beside an edge.
                            if (!verticalFragments.add(List.of((float) span.getMinX(), (float) span.getMinY(),
                                  (float) span.getMaxX(), (float) span.getMaxY(), bottom, top))) { continue; }
                        }
                        result.polygon(material, polygon);
                    }
                }
            }
        });
        List<GeneratedMesh> helpers = new ArrayList<>();
        for (var solid : solids) {
            var helper = new GeneratedMesh();
            for (var t : solid.triangles()) { helper.triangle("helper", vertex(t.a()), vertex(t.b()), vertex(t.c())); }
            helpers.add(helper);
        }
        for (int i = 0; i < outline.size(); i++) {
            var a = outline.get(i); var b = outline.get((i + 1) % outline.size());
            if (!bounds.intersectsLine(a.x(), a.y(), b.x(), b.y())) { continue; }
            double length = Math.hypot(b.x() - a.x(), b.y() - a.y());
            Edge edge = new Edge(a.x(), a.y(), (b.x() - a.x()) / length, (b.y() - a.y()) / length, length);
            Area coplanar = new Area(), section = section(whole, edge, coplanar);
            Area span = new Area(new Rectangle2D.Double(0, -1e8, edge.length(), 2e8));
            section.intersect(span);
            Path2D combined = new Path2D.Double(section);
            // Helpers seal occupiable rooms too; the open courtyard remains outside their structural union.
            // Trim to this edge before the union, and sweep the canonical Area contours together just once.
            for (GeneratedMesh helper : helpers) {
                Area room = section(helper, edge, new Area()); room.intersect(span); combined.append(room, false);
            }
            section = new Area(combined);
            section.subtract(coplanar);
            for (var triangle : BoardTacticalGeometry.flat(section, 0)) {
                result.triangle(CAP, edge.vertex(triangle.a().x, triangle.a().y), edge.vertex(triangle.b().x, triangle.b().y), edge.vertex(triangle.c().x, triangle.c().y));
            }
        }
        var data = result.data(source.id, source);
        if (result.parts.containsKey(CAP)) {
            var material = new ModelMaterial(); material.id = CAP; material.diffuse = new Color(.08f, .08f, .08f, 1);
            data.materials.add(material);
        }
        return data;
    }

    private static float[] vertex(BuildingVolumes.Point p) {
        return new float[] { (float) p.x(), (float) p.y(), (float) p.z(), 0, 0, 0, 1, 1, 1, 1, 0, 0 };
    }

    private static List<float[]> triangle(float[] vertices, int offset) {
        List<float[]> result = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) { result.add(Arrays.copyOfRange(vertices, offset + i * RigidGlb.STRIDE, offset + (i + 1) * RigidGlb.STRIDE)); }
        return result;
    }

    private static Rectangle2D bounds(List<float[]> vertices) {
        Rectangle2D result = new Rectangle2D.Double(vertices.getFirst()[0], vertices.getFirst()[1], 0, 0);
        for (var p : vertices) { result.add(p[0], p[1]); }
        return result;
    }

    private static Rectangle2D bounds(GeneratedMesh mesh) {
        Rectangle2D result = null;
        for (var part : mesh.parts.values()) {
            for (int i = 0; i < part.size; i += RigidGlb.STRIDE) {
                if (result == null) { result = new Rectangle2D.Double(part.items[i], part.items[i + 1], 0, 0); }
                else { result.add(part.items[i], part.items[i + 1]); }
            }
        }
        return result;
    }

    /** Closed directed cross-section loops, before trimming to the finite edge. Open decorative sheets are not filled. */
    private static Area section(GeneratedMesh mesh, Edge edge, Area coplanar) {
        Map<Point, List<Point>> next = new LinkedHashMap<>();
        mesh.parts.values().forEach(part -> {
            for (int offset = 0; offset < part.size; offset += 3 * RigidGlb.STRIDE) {
                int stride = RigidGlb.STRIDE;
                double sa = edge.side(part.items[offset], part.items[offset + 1]);
                double sb = edge.side(part.items[offset + stride], part.items[offset + stride + 1]);
                double sc = edge.side(part.items[offset + 2 * stride], part.items[offset + 2 * stride + 1]);
                if (sa > EPSILON && sb > EPSILON && sc > EPSILON || sa < -EPSILON && sb < -EPSILON && sc < -EPSILON) { continue; }
                var t = triangle(part.items, offset);
                double[] side = {sa, sb, sc};
                if (Math.abs(sa) <= EPSILON && Math.abs(sb) <= EPSILON && Math.abs(sc) <= EPSILON) {
                    Path2D path = new Path2D.Double();
                    for (int k = 0; k < 3; k++) {
                        Point p = edge.project(t.get(k));
                        if (k == 0) { path.moveTo(p.u() / 1000.0, p.z() / 1000.0); }
                        else { path.lineTo(p.u() / 1000.0, p.z() / 1000.0); }
                    }
                    path.closePath(); coplanar.add(new Area(path)); continue;
                }
                if (sa >= -EPSILON && sb >= -EPSILON && sc >= -EPSILON) { continue; }
                List<float[]> cuts = new ArrayList<>();
                for (int k = 0; k < 3; k++) {
                    int j = (k + 1) % 3;
                    if (Math.abs(side[k]) <= EPSILON) { cuts.add(t.get(k)); }
                    if (side[k] < -EPSILON && side[j] > EPSILON || side[k] > EPSILON && side[j] < -EPSILON) {
                        double f = side[k] / (side[k] - side[j]);
                        cuts.add(new float[] { (float) (t.get(k)[0] + f * (t.get(j)[0] - t.get(k)[0])),
                              (float) (t.get(k)[1] + f * (t.get(j)[1] - t.get(k)[1])),
                              (float) (t.get(k)[2] + f * (t.get(j)[2] - t.get(k)[2])) });
                    }
                }
                if (cuts.size() != 2) { continue; }
                Point p = edge.project(cuts.get(0)), q = edge.project(cuts.get(1));
                if (p.equals(q)) { continue; }
                float[] a = t.get(0), b = t.get(1), c = t.get(2);
                double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
                double ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
                double nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
                // n(cut) cross n(face), projected into the cut plane's (u,z) axes.
                if ((q.u() - p.u()) * -nz + (q.z() - p.z()) * (edge.tx() * nx + edge.ty() * ny) < 0) {
                    Point swap = p; p = q; q = swap;
                }
                next.computeIfAbsent(p, ignored -> new ArrayList<>()).add(q);
            }
        });
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        // A touching open sheet may lead to a dead end, which the loops leave out without losing the solid's.
        for (List<Point> loop : GpuCutout.loops(next)) {
            path.moveTo(loop.getFirst().u() / 1000.0, loop.getFirst().z() / 1000.0);
            for (int i = 1; i < loop.size(); i++) { path.lineTo(loop.get(i).u() / 1000.0, loop.get(i).z() / 1000.0); }
            path.closePath();
        }
        return new Area(path);
    }
}
