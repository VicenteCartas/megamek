/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/** A closed extrusion of the artwork's alpha contour, shared by unit tokens and raised map symbols. */
final class GpuCutout {
    // Halfway between integer alpha values avoids coincident crossings at pixel centres (including alpha 128).
    private static final float ALPHA_THRESHOLD = 127.5f;
    private static final float PANEL_TOLERANCE = .55f; // Source pixels, independent of camera and unit scale.
    private static final float SMOOTH_COSINE = .5f; // Keep turns of 60 degrees or more as real creases.

    private GpuCutout() { }

    private record Crossing(long key, Vector3 point) { }
    private record Link(Vector3 point, long next) { }
    record Edge(Vector3 start, Vector3 end) {
        double x(double y) {
            return start.x + (end.x - start.x) * (y - start.y) / (end.y - start.y);
        }
    }

    /**
     * Positions are in artwork pixels, centred on the image, from origin.z to origin.z + height. Caps and
     * walls use the same simplified loops, including holes and disconnected islands. The top cap goes to {@code caps},
     * the bottom one to {@code bottom}: the caps' own builder to show the artwork underneath too, the walls' to paint
     * it. Returns vertices written.
     */
    static int extrude(BoardScene.Pixels pixels, TextureRegion region, Vector3 origin, float scale, float height,
          MeshPartBuilder caps, MeshPartBuilder sides, MeshPartBuilder bottom) {
        var outlines = contours(pixels);
        var panels = outlines.stream().map(GpuCutout::simplify).toList();
        // A subpixel feature can be narrower than the tolerance. Keep its topology rather than crossing walls.
        if (intersects(panels) || !sameNesting(outlines, panels)) { panels = outlines; }
        int vertices = caps(panels, pixels, region, origin, scale, height, caps, bottom);
        Color color = averageColor(pixels);
        for (var loop : panels) {
            var normals = new ArrayList<Vector3>();
            for (int i = 0; i < loop.size(); i++) {
                Vector3 a = loop.get(i), b = loop.get((i + 1) % loop.size());
                normals.add(new Vector3(b.y - a.y, b.x - a.x, 0).nor());
            }
            for (int i = 0; i < loop.size(); i++) {
                int next = (i + 1) % loop.size();
                Vector3 a = loop.get(i), b = loop.get(next), normal = normals.get(i);
                Vector3 na = smooth(normal, normals.get((i + loop.size() - 1) % loop.size()));
                Vector3 nb = smooth(normal, normals.get(next));
                // Outward winding agrees with the vertex normals, including the inward-facing walls of holes.
                sides.rect(vertex(pixels, region, origin, scale, height, b.x, b.y, 0).setNor(nb).setCol(color),
                      vertex(pixels, region, origin, scale, height, a.x, a.y, 0).setNor(na).setCol(color),
                      vertex(pixels, region, origin, scale, height, a.x, a.y, 1).setNor(na).setCol(color),
                      vertex(pixels, region, origin, scale, height, b.x, b.y, 1).setNor(nb).setCol(color));
                vertices += 4;
            }
        }
        return vertices;
    }

    private static Vector3 smooth(Vector3 face, Vector3 neighbor) {
        return face.dot(neighbor) > SMOOTH_COSINE ? new Vector3(face).add(neighbor).nor() : face;
    }

    /** Marching squares with shared grid-edge identities, so tracing never depends on float equality. */
    private static List<List<Vector3>> contours(BoardScene.Pixels pixels) {
        Map<Long, Link> links = new LinkedHashMap<>();
        for (int y = -1; y < pixels.height(); y++) {
            for (int x = -1; x < pixels.width(); x++) {
                Vector3[] corners = { sample(pixels, x, y), sample(pixels, x + 1, y),
                      sample(pixels, x + 1, y + 1), sample(pixels, x, y + 1) };
                var crossings = new Crossing[4];
                int count = 0;
                float center = 0;
                for (int edge = 0; edge < 4; edge++) {
                    Vector3 a = corners[edge], b = corners[(edge + 1) % 4];
                    center += a.z * .25f;
                    if ((a.z >= ALPHA_THRESHOLD) == (b.z >= ALPHA_THRESHOLD)) { continue; }
                    // Always interpolate in increasing grid coordinates, on both sides of a shared edge.
                    if (edge >= 2) { Vector3 swap = a; a = b; b = swap; }
                    int column = x + (edge == 1 ? 1 : 0), row = y + (edge == 2 ? 1 : 0);
                    long key = 2L * ((long) (row + 1) * (pixels.width() + 2) + column + 1) + edge % 2;
                    Vector3 point = new Vector3(a).lerp(b, (ALPHA_THRESHOLD - a.z) / (b.z - a.z));
                    point.z = 0;
                    crossings[edge] = new Crossing(key, point);
                    count++;
                }
                if (count == 2) {
                    Crossing start = null, end = null;
                    for (int edge = 0; edge < 4; edge++) {
                        if (crossings[edge] == null) { continue; }
                        if (corners[edge].z >= ALPHA_THRESHOLD) { start = crossings[edge]; }
                        else { end = crossings[edge]; }
                    }
                    connect(links, start, end);
                } else if (count == 4) {
                    // The centre decides which diagonal corners connect; do not join separate islands arbitrarily.
                    for (int corner = 0; corner < 4; corner++) {
                        boolean filled = corners[corner].z >= ALPHA_THRESHOLD;
                        if (filled == (center >= ALPHA_THRESHOLD)) { continue; }
                        Crossing previous = crossings[(corner + 3) % 4], next = crossings[corner];
                        connect(links, filled ? next : previous, filled ? previous : next);
                    }
                }
            }
        }
        List<List<Vector3>> result = new ArrayList<>();
        while (!links.isEmpty()) {
            long first = links.keySet().iterator().next(), key = first;
            List<Vector3> loop = new ArrayList<>();
            do {
                Link link = links.remove(key);
                loop.add(link.point());
                key = link.next();
            } while (key != first);
            result.add(loop);
        }
        return result;
    }

    private static void connect(Map<Long, Link> links, Crossing start, Crossing end) {
        links.put(start.key(), new Link(start.point(), end.key()));
    }

    private static Vector3 sample(BoardScene.Pixels pixels, int x, int y) {
        int alpha = x < 0 || y < 0 || x >= pixels.width() || y >= pixels.height()
              ? 0 : pixels.rgba(y * pixels.width() + x) & 0xff;
        return new Vector3(x + .5f, y + .5f, alpha);
    }

    /** Douglas-Peucker on two arcs of a closed loop. Subpixel stair steps become a single panel. */
    private static List<Vector3> simplify(List<Vector3> loop) {
        int opposite = 1;
        for (int i = 2; i < loop.size(); i++) {
            if (loop.getFirst().dst2(loop.get(i)) > loop.getFirst().dst2(loop.get(opposite))) { opposite = i; }
        }
        List<Vector3> result = new ArrayList<>();
        simplify(loop, 0, opposite, result);
        simplify(loop, opposite, loop.size(), result);
        // Do not collapse small islands or holes, or reverse their winding.
        return result.size() < 3 || area(result) * area(loop) <= 0 ? loop : result;
    }

    private static void simplify(List<Vector3> loop, int first, int last, List<Vector3> result) {
        Vector3 a = loop.get(first), b = loop.get(last % loop.size());
        double furthest = PANEL_TOLERANCE * PANEL_TOLERANCE;
        int split = -1;
        for (int i = first + 1; i < last; i++) {
            Vector3 point = loop.get(i);
            double distance = Line2D.ptSegDistSq(a.x, a.y, b.x, b.y, point.x, point.y);
            if (distance > furthest) { furthest = distance; split = i; }
        }
        if (split < 0) { result.add(a); }
        else { simplify(loop, first, split, result); simplify(loop, split, last, result); }
    }

    private static float area(List<Vector3> loop) {
        float area = 0;
        for (int i = 0; i < loop.size(); i++) {
            Vector3 a = loop.get(i), b = loop.get((i + 1) % loop.size());
            area += a.x * b.y - b.x * a.y;
        }
        return area;
    }

    private static boolean intersects(List<List<Vector3>> loops) {
        List<Edge> edges = edges(loops);
        for (int i = 0; i < edges.size(); i++) {
            Edge a = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                Edge b = edges.get(j);
                if (a.start() == b.end() || a.end() == b.start()) { continue; }
                if (Line2D.linesIntersect(a.start().x, a.start().y, a.end().x, a.end().y,
                      b.start().x, b.start().y, b.end().x, b.end().y)) { return true; }
            }
        }
        return false;
    }

    /** A panel must not jump completely over a tiny hole or an island, even without intersecting its edges. */
    private static boolean sameNesting(List<List<Vector3>> original, List<List<Vector3>> simplified) {
        for (int i = 0; i < original.size(); i++) {
            Path2D before = path(original.get(i)), after = path(simplified.get(i));
            for (int j = 0; j < original.size(); j++) {
                if (i == j) { continue; }
                // Simplification retains each loop's first point, so it is a shared containment probe.
                Vector3 point = original.get(j).getFirst();
                if (before.contains(point.x, point.y) != after.contains(point.x, point.y)) { return false; }
            }
        }
        return true;
    }

    private static Path2D path(List<Vector3> loop) {
        var path = new Path2D.Float();
        path.moveTo(loop.getFirst().x, loop.getFirst().y);
        for (int i = 1; i < loop.size(); i++) { path.lineTo(loop.get(i).x, loop.get(i).y); }
        path.closePath();
        return path;
    }

    private static List<Edge> edges(List<List<Vector3>> loops) {
        List<Edge> edges = new ArrayList<>();
        for (var loop : loops) {
            for (int i = 0; i < loop.size(); i++) { edges.add(new Edge(loop.get(i), loop.get((i + 1) % loop.size()))); }
        }
        return edges;
    }

    /** Every cap boundary lies on the exact same panel as its wall. */
    private static int caps(List<List<Vector3>> loops, BoardScene.Pixels pixels, TextureRegion region, Vector3 origin,
          float scale, float height, MeshPartBuilder caps, MeshPartBuilder bottom) {
        int vertices = 0;
        for (Vector3[] span : fill(edges(loops))) {
            vertices += capTriangle(pixels, region, origin, scale, height, span[0], span[1], span[2], caps, bottom);
            vertices += capTriangle(pixels, region, origin, scale, height, span[0], span[2], span[3], caps, bottom);
        }
        return vertices;
    }

    /**
     * Tessellates closed outlines in the XY plane in horizontal bands between their vertices. Even/odd spans preserve
     * holes and disconnected pieces without bridging them.
     *
     * @return each span's corners: top left, top right, bottom right, bottom left
     */
    static List<Vector3[]> fill(List<Edge> edges) {
        var rows = new TreeSet<Float>();
        for (Edge edge : edges) {
            rows.add(edge.start().y);
            rows.add(edge.end().y);
        }
        List<Float> levels = new ArrayList<>(rows);
        List<Vector3[]> spans = new ArrayList<>();
        for (int row = 1; row < levels.size(); row++) {
            float top = levels.get(row - 1), bottom = levels.get(row);
            // Adjacent float levels can be one ULP apart; their midpoint must still lie strictly inside the band.
            double middle = (top + (double) bottom) * .5;
            var active = edges.stream().filter(edge -> Math.min(edge.start().y, edge.end().y) < middle
                        && Math.max(edge.start().y, edge.end().y) > middle)
                  .sorted(Comparator.comparingDouble(edge -> edge.x(middle))).toList();
            for (int span = 0; span + 1 < active.size(); span += 2) {
                Edge left = active.get(span), right = active.get(span + 1);
                spans.add(new Vector3[] { new Vector3((float) left.x(top), top, 0), new Vector3((float) right.x(top), top, 0),
                      new Vector3((float) right.x(bottom), bottom, 0), new Vector3((float) left.x(bottom), bottom, 0) });
            }
        }
        return spans;
    }

    private static int capTriangle(BoardScene.Pixels pixels, TextureRegion region, Vector3 origin, float scale,
          float height, Vector3 a, Vector3 b, Vector3 c, MeshPartBuilder caps, MeshPartBuilder bottom) {
        if (Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) < .000001f) { return 0; }
        // Image Y points down; reverse the top face so its geometric normal points up in world space.
        caps.triangle(vertex(pixels, region, origin, scale, height, c.x, c.y, 1),
              vertex(pixels, region, origin, scale, height, b.x, b.y, 1),
              vertex(pixels, region, origin, scale, height, a.x, a.y, 1));
        bottom.triangle(vertex(pixels, region, origin, scale, height, a.x, a.y, 0),
              vertex(pixels, region, origin, scale, height, b.x, b.y, 0),
              vertex(pixels, region, origin, scale, height, c.x, c.y, 0));
        return 6;
    }

    /** Alpha-weighted linear pigment, for scene geometry and material factors. */
    static Color averageColor(BoardScene.Pixels pixels) {
        double red = 0;
        double green = 0;
        double blue = 0;
        long weight = 0;
        for (int index = 0; index < pixels.width() * pixels.height(); index++) {
            int rgba = pixels.rgba(index);
            int alpha = rgba & 0xff;
            red += GpuColor.linear((rgba >>> 24) / 255f) * alpha;
            green += GpuColor.linear(((rgba >>> 16) & 0xff) / 255f) * alpha;
            blue += GpuColor.linear(((rgba >>> 8) & 0xff) / 255f) * alpha;
            weight += alpha;
        }
        return weight == 0 ? new Color(Color.BLACK)
              : new Color((float) (red / weight), (float) (green / weight), (float) (blue / weight), 1);
    }

    private static MeshPartBuilder.VertexInfo vertex(BoardScene.Pixels pixels, TextureRegion region, Vector3 origin,
          float scale, float height, float column, float row, float t) {
        return new MeshPartBuilder.VertexInfo()
              .setPos(origin.x + (column - pixels.width() / 2f) * scale,
                    origin.y + (pixels.height() / 2f - row) * scale, origin.z + t * height)
              .setNor(0, 0, t > 0 ? 1 : -1).setCol(Color.WHITE)
              .setUV(MathUtils.lerp(region.getU(), region.getU2(), column / pixels.width()),
                    MathUtils.lerp(region.getV(), region.getV2(), row / pixels.height()));
    }
}
