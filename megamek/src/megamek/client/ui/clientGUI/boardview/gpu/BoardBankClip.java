/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.triangle;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardNaturalBridge.deck;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardNaturalBridge.joined;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardNaturalBridge.side;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Facet;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Part;

/**
 * Where a natural span's top lies on the level of a bank it rests on, the bank owns the top: the span's top facets
 * give way inside the outline of the bank's top ({@link #outsideBanks}). Every edge crossing that outline or the
 * bank's level is divided once, at a point all faces along it share, so the shell keeps no T-junction. A bank's
 * island inside one facet is handled for completeness, not because boards make one.
 */
final class BoardBankClip {
    private BoardBankClip() { }

    /** How far, as a share of the inradius, a point may lie off a hex edge's line and still lie on it. */
    static final float SEAM = 1e-4f;
    /** How far, as a share of {@link #same}, a point may lie off a face's edge and still lie on it: float rounding. */
    private static final float ON_EDGE = .1f;
    /** How far, in metres, a top may stand above its bank's level and still lie on it: float rounding, not a rise. */
    private static final float ON_LEVEL = .001f;

    /**
     * The bank owns its top where the rock's top lies on its level ({@code height}): keep the buried shell, but never
     * submit two coplanar walking surfaces. Where the rock rises above that level, towards a higher bank beside it, its
     * top stays whole over the bank, so it never ends in an open cut above the bank's top. Faces are first divided where
     * their top edges cross the level or the bank's outline ({@link #cuts}); every face sharing such an edge takes the
     * same points, the walls below the rim and a joined span across their edge alike, so no edge ends in a T-junction.
     */
    static List<Facet> outsideBanks(BoardScene scene, BoardScene.Tile tile, List<Facet> facets, Area banks,
          float height) {
        var outline = outline(banks);
        Map<Integer, List<Vector3>> crossings = new HashMap<>();
        var cuts = cuts(scene, tile, facets, banks, outline, height, crossings);
        if (cuts.isEmpty() && banks.isEmpty()) { return facets; }
        var exact = through(outline, crossings);
        float level = height + BoardRelief.metres(ON_LEVEL);
        var result = new ArrayList<Facet>(facets.size());
        for (var face : facets) {
            var polygon = divided(face, cuts);
            if (banks.isEmpty() || face.part() != Part.TOP && face.part() != Part.RIM) {
                emit(result, face, cuts);
                continue;
            }
            // The polygon is convex: the two points on the level beside its rise bound both the rise and the level part.
            var line = new ArrayList<Vector3>();
            for (int i = 0; i < polygon.size(); i++) {
                var p = polygon.get(i);
                if (p.z <= level && (polygon.get((i + 1) % polygon.size()).z > level
                      || polygon.get(Math.floorMod(i - 1, polygon.size())).z > level)) { line.add(p); }
            }
            var low = polygon.stream().filter(p -> p.z <= level).toList();
            var high = polygon.stream().filter(p -> p.z > level || line.contains(p)).toList();
            boolean rise = high.size() > line.size();
            // A level part with no area, its points all on the dividing line, leaves the whole face to the rise.
            if (low.size() < 3 || rise && (line.size() != 2
                  || low.stream().allMatch(p -> line.contains(p) || between(line.get(0), line.get(1), p)))) {
                emit(result, face, cuts);
                continue;
            }
            var path = footprint(low);
            var remaining = new Area(path);
            var overlap = new Area(remaining);
            if (banks.intersects(path.getBounds2D())) { overlap.intersect(banks); } else { overlap.reset(); }
            if (overlap.isEmpty()) {
                if (rise) {
                    fan(result, high, face.part());
                    fan(result, low, face.part());
                } else {
                    emit(result, face, cuts);
                }
                continue;
            }
            remaining.subtract(exact);
            var along = new ArrayList<Vector3>();
            var pieces = clippedTop(remaining, polygon, outline);
            for (var clipped : pieces) {
                for (var point : List.of(clipped.a(), clipped.b(), clipped.c())) {
                    // A point the polygon has, the nearest, keeps it exactly, which the faces beside it share. Where the
                    // bank's outline crosses the line dividing the level part from the rise, the rise takes the same point.
                    var kept = low.stream().filter(p -> Vector2.dst2(p.x, p.y, point.x, point.y) < same() * same())
                          .min(Comparator.comparingDouble(p -> Vector2.dst2(p.x, p.y, point.x, point.y)));
                    if (kept.isPresent()) {
                        point.set(kept.get());
                    } else if (rise && between(line.get(0), line.get(1), point)) {
                        point.z = height;
                        if (!along.contains(point)) { along.add(point); }
                    } else {
                        point.z = face.a().z - (face.normal().x * (point.x - face.a().x)
                              + face.normal().y * (point.y - face.a().y)) / face.normal().z;
                    }
                }
            }
            for (var clipped : pieces) {
                // Area leaves out a point lying on a straight stretch of the outline it keeps, such as a cut on an edge
                // the bank does not reach: the faces beside that edge have it, so each side of a piece lying on that
                // edge takes back the edge's points between its ends.
                // Taking the polygon's own point a third of a millimetre away can turn a sliver over: it is dropped.
                var divided = new ArrayList<Facet>();
                divide(divided, clipped.a(), clipped.b(), clipped.c(), dividing(face, cuts, clipped.a(), clipped.b()),
                      dividing(face, cuts, clipped.b(), clipped.c()), dividing(face, cuts, clipped.c(), clipped.a()),
                      face.part());
                divided.stream().filter(facet -> facet.normal().dot(face.normal()) > 0).forEach(result::add);
            }
            if (rise) {
                // The rise runs from one point on the level round its higher corners to the other; the dividing line
                // closes it from its last point to its first, through the outline's crossings.
                var closed = new ArrayList<>(high);
                for (int k = 0; k < closed.size()
                      && !(line.contains(closed.getFirst()) && line.contains(closed.getLast())); k++) {
                    Collections.rotate(closed, 1);
                }
                var last = closed.getLast();
                along.stream().sorted(Comparator.comparingDouble(last::dst2)).forEach(closed::add);
                fan(result, closed, face.part());
            }
        }
        return result;
    }

    /**
     * Where top edges cross a level or a bank's outline. This span's own banks' level ({@code height}) and the outline
     * of their tops ({@code banks}) divide the top edges of faces reaching over them, and the level every edge the span
     * shares with a joined span; a joined span's banks' level divides that shared edge too. Each point is computed from
     * the edge's first end ({@link #edge}), so every face and span sharing the edge takes the same one; a point on a
     * level lies on it exactly. An edge whose lower end lies on the level needs no point: that end divides it.
     * {@code crossings} gains, for each segment of the outline, the points standing for its crossings with edges: the
     * cut, or the edge's end within {@link #same} of the crossing.
     */
    private static Map<List<Vector3>, List<Vector3>> cuts(BoardScene scene, BoardScene.Tile tile, List<Facet> facets,
          Area banks, List<float[]> outline, float height, Map<Integer, List<Vector3>> crossings) {
        var levels = new ArrayList<float[]>();
        if (!banks.isEmpty()) { levels.add(new float[] { height, -1 }); }
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (joined(tile, next, d) && levelBank(scene, next)) {
                levels.add(new float[] { deck(next) * BoardGeometry.level(), d });
            }
        }
        Map<List<Vector3>, List<Vector3>> cuts = new HashMap<>();
        // An outline corner that touches an edge, as near as splits() takes a clipped point to lie on one, divides the
        // edge nearest it there, and only that one, which every face along it takes alike.
        Map<Integer, List<Vector3>> touched = new HashMap<>();
        float[] closest = new float[outline.size()];
        Arrays.fill(closest, same());
        for (var face : facets) {
            if (face.part() != Part.TOP && face.part() != Part.RIM) { continue; }
            boolean over = banks.intersects(footprint(face.a(), face.b(), face.c()).getBounds2D());
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                var key = edge(points[i], points[(i + 1) % 3]);
                var a = key.get(0);
                var b = key.get(1);
                int joint = joint(scene, tile, a, b);
                for (var level : levels) {
                    int d = (int) level[1];
                    float lid = level[0] + BoardRelief.metres(ON_LEVEL);
                    if ((a.z > lid) == (b.z > lid) || Math.min(a.z, b.z) >= level[0] - BoardRelief.metres(ON_LEVEL)
                          || (d < 0 ? !over && joint < 0 : joint != d)) { continue; }
                    var cut = new Vector3(a).lerp(b, (level[0] - a.z) / (b.z - a.z));
                    cut.z = level[0];
                    add(cuts, key, cut);
                }
                if (!over) { continue; }
                float rx = b.x - a.x, ry = b.y - a.y;
                for (int k = 0; k < outline.size(); k++) {
                    var segment = outline.get(k);
                    float off = offset(a, b, new Vector3(segment[0], segment[1], 0));
                    if (off < closest[k]) {
                        closest[k] = off;
                        touched.put(k, key);
                    }
                    float sx = segment[2] - segment[0], sy = segment[3] - segment[1], denominator = rx * sy - ry * sx;
                    if (denominator == 0) { continue; }
                    float t = ((segment[0] - a.x) * sy - (segment[1] - a.y) * sx) / denominator;
                    float u = ((segment[0] - a.x) * ry - (segment[1] - a.y) * rx) / denominator;
                    if (t < 0 || t > 1 || u < 0 || u > 1) { continue; }
                    float length = (float) Math.sqrt(rx * rx + ry * ry);
                    var at = t * length <= same() ? a
                          : (1 - t) * length <= same() ? b : add(cuts, key, new Vector3(a).lerp(b, t));
                    var on = crossings.computeIfAbsent(k, segmentIndex -> new ArrayList<>());
                    if (!on.contains(at)) { on.add(at); }
                }
            }
        }
        touched.forEach((k, key) -> {
            var a = key.get(0);
            var b = key.get(1);
            float rx = b.x - a.x, ry = b.y - a.y;
            float t = ((outline.get(k)[0] - a.x) * rx + (outline.get(k)[1] - a.y) * ry) / (rx * rx + ry * ry);
            add(cuts, key, new Vector3(a).lerp(b, t));
        });
        return cuts;
    }

    /** Adds a point to an edge's cuts unless it has one there already. The point the edge keeps there. */
    private static Vector3 add(Map<List<Vector3>, List<Vector3>> cuts, List<Vector3> edge, Vector3 point) {
        var on = cuts.computeIfAbsent(edge, key -> new ArrayList<>());
        var kept = on.stream().filter(p -> p.dst2(point) < same() * same()).findFirst();
        if (kept.isPresent()) { return kept.get(); }
        on.add(point);
        return point;
    }

    /**
     * The banks' area with its outline running exactly through the points standing for its crossings with the faces'
     * edges ({@link #cuts}), so that clipping a face crosses each edge at the point both faces along it share: where the
     * outline crosses an edge at a shallow angle, the area's own crossing could lie further along the edge than
     * {@link #same}, though both lie on both lines to a rounding step.
     */
    private static Area through(List<float[]> outline, Map<Integer, List<Vector3>> crossings) {
        var path = new Path2D.Float();
        for (int k = 0; k < outline.size(); k++) {
            var segment = outline.get(k);
            if (k == 0 || outline.get(k - 1)[2] != segment[0] || outline.get(k - 1)[3] != segment[1]) {
                path.moveTo(segment[0], segment[1]);
            }
            crossings.getOrDefault(k, List.of()).stream()
                  .sorted(Comparator.comparingDouble(p -> Vector2.dst2(segment[0], segment[1], p.x, p.y)))
                  .forEach(p -> path.lineTo(p.x, p.y));
            path.lineTo(segment[2], segment[3]);
        }
        return new Area(path);
    }

    /** The straight segments of an area's outline, each as its two ends' x and y. */
    private static List<float[]> outline(Area area) {
        var segments = new ArrayList<float[]>();
        float[] point = new float[6];
        float startX = 0, startY = 0, x = 0, y = 0;
        for (var path = area.getPathIterator(null); !path.isDone(); path.next()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = point[0];
                startY = y = point[1];
            } else if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE) {
                boolean close = type == PathIterator.SEG_CLOSE;
                float toX = close ? startX : point[0], toY = close ? startY : point[1];
                segments.add(new float[] { x, y, toX, toY });
                x = toX;
                y = toY;
            }
        }
        return segments;
    }

    /** Some exit of the span rests on a bank at its own deck's level, whose top the span's top gives way to. */
    private static boolean levelBank(BoardScene scene, BoardScene.Tile tile) {
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (BoardBridge.abutment(tile, next, d) && BoardRoadGrade.ground(next) == deck(tile)) { return true; }
        }
        return false;
    }

    /** The direction of the joined span whose shared edge, or its line beyond, holds both points; else -1. */
    private static int joint(BoardScene scene, BoardScene.Tile tile, Vector3 a, Vector3 b) {
        for (int d = 0; d < 6; d++) {
            if (joined(tile, scene.tile(tile.coords().translated(d)), d) && seam(tile, d, a) && seam(tile, d, b)) {
                return d;
            }
        }
        return -1;
    }

    /** The point lies on the hex edge the span shares in this direction, or on its line beyond. */
    private static boolean seam(BoardScene.Tile tile, int direction, Vector3 point) {
        return Math.abs(BoardGeometry.share(tile.coords(), direction, point.x, point.y) - 1) < SEAM;
    }

    /** The point lies on one of the polygon's edges in the plane, away from its points. */
    private static boolean splits(List<Vector3> polygon, Vector3 point) {
        if (polygon.stream().anyMatch(p -> Vector2.dst2(p.x, p.y, point.x, point.y) < same() * same())) { return false; }
        for (int i = 0; i < polygon.size(); i++) {
            if (between(polygon.get(i), polygon.get((i + 1) % polygon.size()), point)) { return true; }
        }
        return false;
    }

    /** The point lies on the segment from a to b in the plane, away from its ends ({@link #offset}). */
    private static boolean between(Vector3 a, Vector3 b, Vector3 point) { return offset(a, b, point) < same(); }

    /**
     * How far the point lies off the segment from a to b in the plane, where it lies along the segment beyond
     * {@link #same} of either end; else infinity: a point that near an end is that end.
     */
    private static float offset(Vector3 a, Vector3 b, Vector3 point) {
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.sqrt(dx * dx + dy * dy);
        float along = ((point.x - a.x) * dx + (point.y - a.y) * dy) / length;
        if (!(along > same() && along < length - same())) { return Float.POSITIVE_INFINITY; }
        return Math.abs((point.x - a.x) * dy - (point.y - a.y) * dx) / length;
    }

    /**
     * The points dividing the face's edge that a piece's side from {@code from} to {@code to} lies on, running in the
     * face's own direction, between the side's ends, in order. The side lies on an edge where both its ends lie on that
     * edge's own stretch, to the float rounding a cut or a point taken from the face has ({@link #ON_EDGE}); if a thin
     * face has two such edges, on the nearer. A point at a side's end is that end.
     */
    private static List<Vector3> dividing(Facet face, Map<List<Vector3>, List<Vector3>> cuts, Vector3 from, Vector3 to) {
        Vector3[] points = { face.a(), face.b(), face.c() };
        int edge = -1;
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            var a = points[i];
            var b = points[(i + 1) % 3];
            float start = station(a, b, from), end = station(a, b, to), length = Vector2.dst(a.x, a.y, b.x, b.y);
            float off = Math.max(distance(a, b, from), distance(a, b, to));
            if (off < ON_EDGE * same() && end - start > 2 * same() && start > -same() && end < length + same()
                  && off < nearest) {
                edge = i;
                nearest = off;
            }
        }
        if (edge < 0) { return List.of(); }
        var a = points[edge];
        var b = points[(edge + 1) % 3];
        float start = station(a, b, from), end = station(a, b, to);
        return cuts.getOrDefault(edge(a, b), List.of()).stream()
              .filter(p -> station(a, b, p) > start + same() && station(a, b, p) < end - same())
              .sorted(Comparator.comparingDouble(p -> station(a, b, p))).toList();
    }

    /** How far along the line from a to b, in the plane, a point within {@link #same} of it lies; else NaN. */
    private static float station(Vector3 a, Vector3 b, Vector3 point) {
        if (distance(a, b, point) > same()) { return Float.NaN; }
        float dx = b.x - a.x, dy = b.y - a.y;
        return ((point.x - a.x) * dx + (point.y - a.y) * dy) / (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** How far a point lies off the line through a and b, in the plane. */
    private static float distance(Vector3 a, Vector3 b, Vector3 point) {
        float dx = b.x - a.x, dy = b.y - a.y;
        return Math.abs((point.x - a.x) * dy - (point.y - a.y) * dx) / (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** How close, in world units, two points are the same point: a third of a millimetre. */
    private static float same() { return .001f * BoardGeometry.hexScale(); }

    /** An edge's key, the same for both its directions. */
    private static List<Vector3> edge(Vector3 a, Vector3 b) {
        boolean first = a.x != b.x ? a.x < b.x : a.y != b.y ? a.y < b.y : a.z < b.z;
        return first ? List.of(a, b) : List.of(b, a);
    }

    /** The facet's corners in its winding, with the cut points on each of its edges in order along it. */
    private static List<Vector3> divided(Facet face, Map<List<Vector3>, List<Vector3>> cuts) {
        Vector3[] points = { face.a(), face.b(), face.c() };
        var polygon = new ArrayList<Vector3>(3);
        for (int i = 0; i < 3; i++) {
            var a = points[i];
            polygon.add(a);
            var on = cuts.get(edge(a, points[(i + 1) % 3]));
            if (on != null) { on.stream().sorted(Comparator.comparingDouble(a::dst2)).forEach(polygon::add); }
        }
        return polygon;
    }

    /** The facet itself where nothing divides it, else its pieces with every cut on its edges ({@link #divide}). */
    private static void emit(List<Facet> result, Facet face, Map<List<Vector3>, List<Vector3>> cuts) {
        var ab = on(cuts, face.a(), face.b());
        var bc = on(cuts, face.b(), face.c());
        var ca = on(cuts, face.c(), face.a());
        if (ab.isEmpty() && bc.isEmpty() && ca.isEmpty()) {
            result.add(face);
        } else {
            divide(result, face.a(), face.b(), face.c(), ab, bc, ca, face.part());
        }
    }

    /** The cuts on the edge from a to b, in order from a. */
    private static List<Vector3> on(Map<List<Vector3>, List<Vector3>> cuts, Vector3 a, Vector3 b) {
        return cuts.getOrDefault(edge(a, b), List.of()).stream().sorted(Comparator.comparingDouble(a::dst2)).toList();
    }

    /**
     * A triangle's pieces keeping each point on its sides ({@code ab} from a to b, and so on) as a corner: fanned from
     * the corner opposite one side over that side's points, the pieces at its ends dividing the other sides alike. No
     * point lies inside, so a long thin triangle's pieces stay as stable as the triangle; a fan from its centre would
     * fall apart into needles the facet filter drops.
     */
    private static void divide(List<Facet> result, Vector3 a, Vector3 b, Vector3 c, List<Vector3> ab, List<Vector3> bc,
          List<Vector3> ca, Part part) {
        if (ab.isEmpty() && bc.isEmpty() && ca.isEmpty()) {
            triangle(result, a, b, c, part);
        } else if (ab.isEmpty()) {
            divide(result, b, c, a, bc, ca, ab, part);
        } else {
            var chain = new ArrayList<Vector3>(ab.size() + 2);
            chain.add(a);
            chain.addAll(ab);
            chain.add(b);
            for (int i = 0; i + 1 < chain.size(); i++) {
                divide(result, chain.get(i), chain.get(i + 1), c, List.of(), i + 2 == chain.size() ? bc : List.of(),
                      i == 0 ? ca : List.of(), part);
            }
        }
    }

    /** A convex polygon's triangles, fanned from its centre so that no points on one of its edges form a sliver. */
    private static void fan(List<Facet> result, List<Vector3> polygon, Part part) {
        if (polygon.size() == 3) {
            triangle(result, polygon.get(0), polygon.get(1), polygon.get(2), part);
            return;
        }
        var middle = new Vector3();
        polygon.forEach(middle::add);
        middle.scl(1f / polygon.size());
        for (int i = 0; i < polygon.size(); i++) {
            triangle(result, middle, polygon.get(i), polygon.get((i + 1) % polygon.size()), part);
        }
    }

    /**
     * Ordinary bank cuts have one outline; keep its ears instead of splitting every triangle into horizontal strips. The
     * area's arithmetic may split one of the face's edges ({@code polygon}) where nothing crosses it, on the straight
     * stretch between its neighbours; such a point is left out, since the face beside that edge lacks it, unless it is a
     * corner of the bank's {@code outline}. A corner where the outline turns stays, however near an edge a thin face
     * brings it. So are repeated points left out, and a piece left with no area.
     */
    private static List<BoardSurface.Face> clippedTop(Area area, List<Vector3> polygon, List<float[]> outline) {
        var contours = new ArrayList<List<Vector3>>();
        List<Vector3> contour = null;
        float[] point = new float[6];
        for (var path = area.getPathIterator(null); !path.isDone(); path.next()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) { contour = new ArrayList<>(); contours.add(contour); }
            var p = new Vector3(point[0], point[1], 0);
            if ((type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO)
                  && (contour.isEmpty() || !contour.getLast().equals(p))) { contour.add(p); }
        }
        for (var points : contours) {
            if (points.size() > 1 && points.getLast().equals(points.getFirst())) { points.removeLast(); }
            for (int i = 0; i < points.size() && points.size() > 3; i++) {
                var p = points.get(i);
                boolean corner = outline.stream()
                      .anyMatch(segment -> Vector2.dst2(segment[0], segment[1], p.x, p.y) < same() * same());
                if (!corner && splits(polygon, p) && between(points.get(Math.floorMod(i - 1, points.size())),
                      points.get((i + 1) % points.size()), p)) { points.remove(i--); }
            }
        }
        // Area may run an outline through one point twice where a bank's corner touches it. Two loops wound alike are
        // pieces of their own; a loop wound against the rest is a hole touching the outline there, which the ear
        // clipping takes as it is, like a bridge of no length.
        for (int c = 0; c < contours.size(); c++) {
            var points = contours.get(c);
            for (int i = 0, j; i < points.size(); i++) {
                for (j = i + 1; j < points.size() && !points.get(j).equals(points.get(i)); j++) { }
                var rest = new ArrayList<>(points.subList(0, i));
                rest.addAll(points.subList(j, points.size()));
                if (j < points.size() && winding(points.subList(i, j)) * winding(rest) > 0) {
                    contours.add(new ArrayList<>(points.subList(i, j)));
                    points.subList(i, j).clear();
                }
            }
        }
        // A piece left without area (Area leaves collinear slivers) is none: neither rock nor a hole in it.
        contours.removeIf(points -> points.size() < 3 || Math.abs(winding(points)) < same() * same());
        // A scalloped bank can leave several separate pieces of one triangle: triangulate each outline directly. A
        // bank's island inside a piece joins the piece's outline along a bridge, as earcut joins holes, so the piece
        // stays one outline and gains no point on the face's edges. Area winds a hole against its outlines; the
        // largest outline shows which way they run. The rightmost hole joins first, and each later one may bridge to a
        // hole already joined.
        var largest = contours.stream().max(Comparator.comparingDouble(points -> Math.abs(winding(points))));
        var holes = new ArrayList<List<Vector3>>();
        for (var points : contours) {
            if (Math.signum(winding(points)) != Math.signum(winding(largest.orElseThrow()))) { holes.add(points); }
        }
        contours.removeIf(points -> holes.stream().anyMatch(hole -> hole == points));
        holes.sort(Comparator.comparingDouble(hole -> -hole.stream().mapToDouble(p -> p.x).max().orElse(0)));
        var unbridged = new ArrayList<List<Vector3>>();
        for (var hole : holes) {
            var outer = contours.stream().filter(points -> hole.stream().anyMatch(p -> encloses(points, p))).findFirst();
            if (outer.isEmpty() || !bridge(outer.get(), hole, contours, holes)) { unbridged.add(hole); }
        }
        // There is always a bridge to some corner the hole sees; should rounding hide it, the pieces overlapping the
        // hole go, leaving a gap rather than rock over the bank.
        assert unbridged.isEmpty() : "A bank's island found no bridge to its piece's outline";
        var result = new ArrayList<BoardSurface.Face>();
        for (var points : contours) { clip(points, result); }
        result.removeIf(face -> unbridged.stream().anyMatch(hole -> {
            var overlap = new Area(footprint(face.a(), face.b(), face.c()));
            overlap.intersect(new Area(footprint(hole)));
            return !overlap.isEmpty();
        }));
        return result;
    }

    static Path2D footprint(Vector3 a, Vector3 b, Vector3 c) { return footprint(List.of(a, b, c)); }

    /**
     * Ear-clips an outline in coordinates local to its first point, where a hair stays many float steps wherever the
     * board lies. The ear clipping misreads a corner the outline visits again, at a bridge's ends or where a hole
     * touches it: there each later visit runs a tenth of same() inside. The faces keep the outline's own points.
     */
    private static void clip(List<Vector3> outline, List<BoardSurface.Face> result) {
        var origin = outline.getFirst();
        var local = new Vector3[outline.size()];
        Map<Vector3, Vector3> back = new IdentityHashMap<>();
        for (int i = 0; i < local.length; i++) {
            local[i] = new Vector3(outline.get(i).x - origin.x, outline.get(i).y - origin.y, 0);
            back.put(local[i], outline.get(i));
        }
        float inside = Math.signum(winding(outline)) * same() / 10;
        for (int i = 0; i < local.length; i++) {
            if (!outline.subList(0, i).contains(outline.get(i))) { continue; }
            var before = local[(i + local.length - 1) % local.length];
            var after = local[(i + 1) % local.length];
            local[i].mulAdd(new Vector3(before.y - after.y, after.x - before.x, 0).nor(), inside);
        }
        int start = result.size();
        BoardSurface.polygon(local, BoardSurface.Finish.TOP, result);
        for (int i = start; i < result.size(); i++) {
            var face = result.get(i);
            result.set(i, new BoardSurface.Face(back.get(face.a()), back.get(face.b()), back.get(face.c()),
                  BoardSurface.Finish.TOP));
        }
    }

    /** Twice the signed area a closed outline winds around, in the plane. */
    private static float winding(List<Vector3> outline) {
        float area = 0;
        var first = outline.getFirst();
        for (int i = 1; i + 1 < outline.size(); i++) {
            area += side(new Vector3(outline.get(i)).sub(first), first, outline.get(i + 1));
        }
        return area;
    }

    /** The closed outline holds the point. */
    private static boolean encloses(List<Vector3> outline, Vector3 point) {
        return footprint(outline).contains(point.x, point.y);
    }

    /**
     * Joins a hole into the outline around it: from the hole's corner furthest along x to the nearest corner of the
     * outline (earlier holes joined into it included) whose bridge to it crosses no edge and runs inside the piece, round
     * the hole and back along the same bridge. A hole with a corner within {@link #same} of the outline touches it
     * there instead: the outline's point there, or the point of its edge facing the corner, takes the corner's place,
     * and the outline runs round the hole from it and back to it. A corner the outline visits more than once, at an
     * earlier bridge's ends, takes the hole at the visit whose angle opens towards it ({@link #opens}). Whether it found
     * a bridge.
     */
    private static boolean bridge(List<Vector3> outer, List<Vector3> hole, List<List<Vector3>> contours,
          List<List<Vector3>> holes) {
        int from = 0, to = -1;
        float sign = Math.signum(winding(outer));
        for (int h = 0; h < hole.size() && to < 0; h++) {
            for (int i = 0; i < outer.size() && to < 0; i++) {
                var a = outer.get(i);
                var b = outer.get((i + 1) % outer.size());
                if (a.dst(hole.get(h)) < same() && opens(outer, i, hole.get((h + 1) % hole.size()), sign)) {
                    to = i;
                } else if (between(a, b, hole.get(h))) {
                    outer.add(i + 1, new Vector3(a).lerp(b, station(a, b, hole.get(h)) / a.dst(b)));
                    to = i + 1;
                }
                if (to >= 0) { from = h; }
            }
        }
        boolean touching = to >= 0;
        for (int i = 1; i < hole.size() && !touching; i++) { if (hole.get(i).x > hole.get(from).x) { from = i; } }
        var start = hole.get(from);
        for (int i = 0; i < outer.size() && !touching; i++) {
            var end = outer.get(i);
            if (to >= 0 && end.dst2(start) >= outer.get(to).dst2(start)) { continue; }
            var middle = new Vector3(start).add(end).scl(.5f);
            boolean clear = opens(outer, i, start, sign) && encloses(outer, middle)
                  && holes.stream().noneMatch(other -> encloses(other, middle));
            for (var points : Stream.concat(contours.stream(), holes.stream()).toList()) {
                for (int k = 0; k < points.size() && clear; k++) {
                    clear = !crosses(start, end, points.get(k), points.get((k + 1) % points.size()));
                }
            }
            if (clear) { to = i; }
        }
        if (to < 0) { return false; }
        var joined = new ArrayList<>(outer.subList(0, to + 1));
        int skip = touching ? 1 : 0;
        for (int k = skip; k <= hole.size() - skip; k++) { joined.add(new Vector3(hole.get((from + k) % hole.size()))); }
        joined.add(new Vector3(outer.get(to)));
        joined.addAll(outer.subList(to + 1, outer.size()));
        outer.clear();
        outer.addAll(joined);
        return true;
    }

    /**
     * The angle the outline's edges open at its corner i, on the side the outline winds ({@code sign}), holds the
     * direction to the point.
     */
    private static boolean opens(List<Vector3> outline, int i, Vector3 point, float sign) {
        var before = outline.get(Math.floorMod(i - 1, outline.size()));
        var at = outline.get(i);
        var after = outline.get((i + 1) % outline.size());
        var entering = new Vector3(at).sub(before);
        var leaving = new Vector3(after).sub(at);
        // Inward of the edge entering the corner, and of the edge leaving it; a reflex corner opens to either.
        boolean first = sign * side(entering, before, point) > 0, second = sign * side(leaving, at, point) > 0;
        return sign * side(entering, before, after) > 0 ? first && second : first || second;
    }

    /** The segments p-q and r-s cross at a point inside both, not where they merely share an end. */
    private static boolean crosses(Vector3 p, Vector3 q, Vector3 r, Vector3 s) {
        if (p.epsilonEquals(r, 0) || p.epsilonEquals(s, 0) || q.epsilonEquals(r, 0) || q.epsilonEquals(s, 0)) { return false; }
        var rs = new Vector3(s).sub(r);
        var pq = new Vector3(q).sub(p);
        return (side(rs, r, p) > 0) != (side(rs, r, q) > 0) && (side(pq, p, r) > 0) != (side(pq, p, s) > 0);
    }

    private static Path2D footprint(List<Vector3> polygon) {
        var path = new Path2D.Float();
        path.moveTo(polygon.getFirst().x, polygon.getFirst().y);
        for (var p : polygon.subList(1, polygon.size())) { path.lineTo(p.x, p.y); }
        path.closePath();
        return path;
    }
}
