/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.badlogic.gdx.math.Vector3;

/**
 * The ground carriageways reserve. {@link #mesh} lays each reserved piece of a hex at its road's grade, beside the hex's
 * own level top where one is given; {@link #clip} removes any other terrain standing over the reserved ground and closes
 * what is left with vertical cut faces down (or up) to the road. Both share one plane sweep, so the two sides of every
 * boundary share its vertices and the cut faces meet them exactly.
 */
final class BoardRoadCut {
    /** A reserved piece: the ground of one grade inside the hex. */
    private record Piece(Area area, BoardRoadGrade grade) { }

    record Point(float x, float y) { }

    private record Segment(float ax, float ay, float bx, float by) {
        float x(float y) {
            return ay == by ? ax : ax + (bx - ax) * (y - ay) / (by - ay);
        }
        float low() { return Math.min(ay, by); }
        float high() { return Math.max(ay, by); }
    }

    /** Which ground a point of the plane lies on: a source index, or {@link #OUTSIDE} where nothing is meshed. */
    private interface Classifier { int source(float x, float y); }

    private interface Triangles { void triangle(Point a, Point b, Point c, int source); }

    /** The sweep's segments, split at every crossing, and the rows they leave. */
    private record Sweep(List<Segment> segments, List<Float> rows, List<Part> parts) { }

    /** A part of a sweep row between two segments, all of one source, as classified at its middle. */
    private record Part(float bottom, float top, Segment left, Segment right, int source) {
        float x() { return (left.x(y()) + right.x(y())) / 2; }
        float y() { return (bottom + top) / 2; }
    }

    private static final int OUTSIDE = -2, TOP = -1;

    /** A cut face's two edges, its top and its foot, from a to b, facing (fx, fy) towards the lower side. */
    interface WallBuilder {
        void wall(Vector3 topA, Vector3 lowA, Vector3 topB, Vector3 lowB, float facingX, float facingY);
    }

    private final Area top;
    private final float level;
    private final List<Piece> pieces = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    private final Area reserved = new Area();
    /** The outline of all the ground the grades reserve: the road's edge, where natural ground meets it. */
    private final List<Segment> edge = new ArrayList<>();
    private final List<BoardRoadGrade> grades;
    /** Snapping and probing tolerance: well above the float spacing of the board's coordinates here. */
    private final float epsilon;
    /** The middle of the hex's own ground, which faces standing on its outline are probed towards. */
    private final float cx, cy;
    /** The cell's edges that are the board's, by edge bit: the board's cut below them reaches a road's surface exactly. */
    private final int boardEdges;
    /** The road faces last sampled, on a grid of {@link #ROAD_CELL} cells by their bounds; see {@link #roadHeight}. */
    private List<BoardSurface.Face> indexed;
    private final Map<Long, List<BoardSurface.Face>> roadCells = new HashMap<>();
    /** Size of a road lookup cell, in tile pixels. */
    private static final float ROAD_CELL = 4;

    /**
     * {@code top} is the hex's level top (empty where the terrain is meshed elsewhere) and {@code cell} the ground the
     * hex owns; the grades in priority order, the hex's own first. Each grade's region inside the cell is reserved once.
     */
    BoardRoadCut(Area top, Area cell, float level, List<BoardRoadGrade> grades, int boardEdges) {
        this.top = top;
        this.boardEdges = boardEdges;
        this.level = level;
        this.grades = grades;
        Rectangle2D bounds = cell.getBounds2D();
        float magnitude = (float) Math.max(Math.max(Math.abs(bounds.getMinX()), Math.abs(bounds.getMaxX())),
              Math.max(Math.abs(bounds.getMinY()), Math.abs(bounds.getMaxY())));
        epsilon = Math.max(1e-4f * BoardGeometry.hexScale(), 16 * Math.ulp(magnitude));
        cx = (float) bounds.getCenterX();
        cy = (float) bounds.getCenterY();
        Area claimed = new Area();
        for (BoardRoadGrade grade : grades) {
            Area region = grade.region();
            reserved.add(region);
            region.intersect(cell);
            region.subtract(claimed);
            if (region.isEmpty()) { continue; }
            claimed.add(region);
            pieces.add(new Piece(region, grade));
        }
        // A hex owning no reserved piece still meets the road's edge, where its slopes reach into the neighbours'.
        outline(reserved, edge, epsilon);
        if (pieces.isEmpty()) { return; }
        outline(top, segments, epsilon);
        for (Piece piece : pieces) {
            // The road's edge has a vertex where it crosses the world's grid, as the ground cut along it does
            // (BoardRoadBank splits the ground there), so the two meet vertex for vertex.
            List<Segment> sides = new ArrayList<>();
            outline(piece.area(), sides, epsilon);
            for (Segment side : sides) { stations(side, segments); }
            for (float[] line : piece.grade().creases()) { crease(piece.area(), line); }
        }
    }

    /**
     * A segment split where it crosses the world's grid (BoardRoadBank#GRID), into {@code into}: on the grid plane
     * exactly, where the bank puts its own vertex.
     */
    private void stations(Segment s, List<Segment> into) {
        float px = s.ax(), py = s.ay();
        for (float[] cut : crossings(s.ax(), s.ay(), s.bx(), s.by())) {
            float t = cut[0];
            float qx = cut[1] == 0 ? cut[2] : s.ax() + (s.bx() - s.ax()) * t;
            float qy = cut[1] == 1 ? cut[2] : s.ay() + (s.by() - s.ay()) * t;
            if (Math.hypot(qx - px, qy - py) > epsilon && Math.hypot(s.bx() - qx, s.by() - qy) > epsilon) {
                into.add(new Segment(px, py, qx, qy));
                px = qx;
                py = qy;
            }
        }
        into.add(new Segment(px, py, s.bx(), s.by()));
    }

    /**
     * The road's height at a point of its edge, the outline of all reserved ground: that of the first grade, in the order
     * every hex sorts them, whose own outline passes there, as the first claims the ground where grades overlap; so every
     * hex finds the same. NaN off the edge.
     */
    private float edgeHeight(float x, float y) {
        for (BoardRoadGrade grade : grades) {
            if (onOutline(grade, x, y)) { return grade.height(x, y); }
        }
        return Float.NaN;
    }

    private boolean onOutline(BoardRoadGrade grade, float x, float y) {
        float[] o = grade.outline();
        for (int i = 0; i < o.length; i += 4) {
            if (BoardRelief.distance(x, y, o[i], o[i + 1], o[i + 2], o[i + 3]) < 4 * epsilon) { return true; }
        }
        return false;
    }

    /** Whether a straight run from p to q lies along the road's edge. */
    private boolean alongEdge(Vector3 p, Vector3 q) {
        return onEdge(p.x, p.y) && onEdge(q.x, q.y) && onEdge((p.x + q.x) / 2, (p.y + q.y) / 2);
    }

    /** Whether a point lies on the road's edge. */
    private boolean onEdge(float x, float y) {
        float tolerance = 2 * epsilon;
        for (Segment s : edge) {
            if (BoardRelief.distance(x, y, s.ax(), s.ay(), s.bx(), s.by()) < tolerance) { return true; }
        }
        return false;
    }

    /**
     * Where a straight run from a to b crosses the world's grid planes, sorted along it: each its parameter inside the
     * run, the plane's axis (0 for x) and the plane's coordinate.
     */
    private static List<float[]> crossings(float ax, float ay, float bx, float by) {
        float grid = BoardRoadBank.GRID * BoardGeometry.hexScale();
        List<float[]> cuts = new ArrayList<>();
        for (int axis = 0; axis < 2; axis++) {
            float a = axis == 0 ? ax : ay, b = axis == 0 ? bx : by;
            if (a == b) { continue; }
            for (long k = (long) Math.ceil(Math.min(a, b) / grid); k * grid <= Math.max(a, b); k++) {
                float t = (k * grid - a) / (b - a);
                if (t > 0 && t < 1) { cuts.add(new float[] { t, axis, k * grid }); }
            }
        }
        cuts.sort((p, q) -> Float.compare(p[0], q[0]));
        return cuts;
    }

    /** Whether any carriageway reserves ground in this hex. */
    boolean reserves() { return !pieces.isEmpty(); }

    /** Whether a reserved piece leaves the level of the top anywhere. */
    boolean graded() {
        return pieces.stream().anyMatch(piece -> piece.grade().graded()
              || piece.grade().tile().elevation() * BoardGeometry.level() != level);
    }

    /** The whole ground the grades reserve, beyond this hex too. */
    Area region() { return new Area(reserved); }

    /** The grade reserving a point; null where none does. */
    private BoardRoadGrade grade(float x, float y) {
        for (BoardRoadGrade grade : grades) {
            if (grade.contains(x, y)) { return grade; }
        }
        return null;
    }

    private static void outline(Area area, List<Segment> into, float epsilon) {
        PathIterator path = area.getPathIterator(null, .02f * BoardGeometry.hexScale());
        float[] point = new float[6];
        float x = 0, y = 0, startX = 0, startY = 0;
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = point[0];
                startY = y = point[1];
            } else {
                float nextX = type == PathIterator.SEG_CLOSE ? startX : point[0];
                float nextY = type == PathIterator.SEG_CLOSE ? startY : point[1];
                if (Math.hypot(nextX - x, nextY - y) > epsilon) { into.add(new Segment(x, y, nextX, nextY)); }
                x = nextX;
                y = nextY;
            }
            path.next();
        }
    }

    /** How near an outline's vertex, in tile pixels, a crease crossing the outline takes that vertex instead. */
    private static final float CREASE_SNAP = .05f;

    /** The parts of a crease line that lie inside its own piece. */
    private void crease(Area area, float[] line) {
        float ax = line[0], ay = line[1], bx = line[2], by = line[3];
        List<Segment> boundary = new ArrayList<>();
        outline(area, boundary, epsilon);
        // Each cut: its share of the crease, and its point taken on the boundary it crosses, which keeps it on that
        // boundary exactly (a long crease's own share puts it a little off an edge, and the sweep rows it from there).
        // A crossing a hair from the boundary's vertex is that vertex: beside it, it would add a row of slivers.
        List<float[]> cuts = new ArrayList<>(List.of(new float[] { 0, ax, ay }, new float[] { 1, bx, by }));
        float near = CREASE_SNAP * BoardGeometry.hexScale();
        for (Segment s : boundary) {
            float[] hit = intersection(ax, ay, bx, by, s.ax(), s.ay(), s.bx(), s.by());
            if (hit != null) {
                float length = (float) Math.hypot(s.bx() - s.ax(), s.by() - s.ay()), u = hit[1];
                cuts.add(u * length < near ? new float[] { hit[0], s.ax(), s.ay() }
                      : (1 - u) * length < near ? new float[] { hit[0], s.bx(), s.by() }
                      : new float[] { hit[0], s.ax() + (s.bx() - s.ax()) * u, s.ay() + (s.by() - s.ay()) * u });
            }
        }
        cuts.sort((p, q) -> Float.compare(p[0], q[0]));
        for (int i = 1; i < cuts.size(); i++) {
            float[] from = cuts.get(i - 1), to = cuts.get(i);
            float middle = (from[0] + to[0]) / 2;
            if (Math.hypot(to[1] - from[1], to[2] - from[2]) < epsilon
                  || !area.contains(ax + (bx - ax) * middle, ay + (by - ay) * middle)) { continue; }
            segments.add(new Segment(from[1], from[2], to[1], to[2]));
        }
    }

    /** Parameters along both segments where they cross, or null. Solved in double: board coordinates are large. */
    private static float[] intersection(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        double ex = (double) bx - ax, ey = (double) by - ay, fx = (double) dx - cx, fy = (double) dy - cy;
        double denominator = ex * fy - ey * fx;
        if (Math.abs(denominator) < 1e-12) { return null; }
        double t = (((double) cx - ax) * fy - ((double) cy - ay) * fx) / denominator;
        double u = (((double) cx - ax) * ey - ((double) cy - ay) * ex) / denominator;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? new float[] { (float) t, (float) u } : null;
    }

    /**
     * Meshes the top and its reserved pieces into {@code faces}, and a cut face along every boundary between two of them
     * at different heights.
     */
    void mesh(List<BoardSurface.Face> faces, WallBuilder wall) {
        if (pieces.isEmpty()) { return; }
        Map<Point, Vector3>[] vertices = vertexMaps(pieces.size() + 1);
        Classifier classifier = this::source;
        Sweep sweep = sweep(segments, epsilon, classifier, (a, b, c, source) -> {
            var map = vertices[source + 1];
            add(faces, vertex(map, a, source), vertex(map, b, source), vertex(map, c, source));
        });
        float probe = 4 * epsilon, tolerance = .001f * BoardGeometry.hexScale();
        for (Segment s : sweep.segments()) {
            for (float[] piece : rowPieces(s, sweep.rows())) {
                float ax = piece[0], ay = piece[1], bx = piece[2], by = piece[3];
                float length = (float) Math.hypot(bx - ax, by - ay);
                float nx = -(by - ay) / length, ny = (bx - ax) / length, mx = (ax + bx) / 2, my = (ay + by) / 2;
                int left = source(mx + nx * probe, my + ny * probe), right = source(mx - nx * probe, my - ny * probe);
                if (left == right || left == OUTSIDE || right == OUTSIDE) { continue; }
                float la = height(left, ax, ay), lb = height(left, bx, by);
                float ra = height(right, ax, ay), rb = height(right, bx, by);
                if (Math.abs(la - ra) < tolerance && Math.abs(lb - rb) < tolerance) { continue; }
                boolean leftHigher = la + lb > ra + rb;
                wall.wall(new Vector3(ax, ay, Math.max(la, ra)), new Vector3(ax, ay, Math.min(la, ra)),
                      new Vector3(bx, by, Math.max(lb, rb)), new Vector3(bx, by, Math.min(lb, rb)),
                      leftHigher ? -nx : nx, leftHigher ? -ny : ny);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Point, Vector3>[] vertexMaps(int count) {
        Map<Point, Vector3>[] maps = new Map[count];
        for (int i = 0; i < count; i++) { maps[i] = new HashMap<>(); }
        return maps;
    }

    private Vector3 vertex(Map<Point, Vector3> vertices, Point p, int source) {
        return vertices.computeIfAbsent(p, k -> new Vector3(p.x(), p.y(), height(source, p.x(), p.y())));
    }

    private int source(float x, float y) {
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).area().contains(x, y)) { return i; }
        }
        return top.contains(x, y) ? TOP : OUTSIDE;
    }

    private float height(int source, float x, float y) {
        return source == TOP ? level : pieces.get(source).grade().height(x, y);
    }

    private static void add(List<BoardSurface.Face> faces, Vector3 a, Vector3 b, Vector3 c) {
        float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        // A zero-area face has no plane: ray picking would report it hit at the ray's origin.
        if (Math.abs(area) <= 1e-5f * Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)))) { return; }
        faces.add(area > 0 ? new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP)
              : new BoardSurface.Face(a, c, b, BoardSurface.Finish.TOP));
    }

    /** How far, in levels, poured ground may lie under a road's surface and still be taken as lying on it. */
    private static final float ON_SURFACE = .02f;
    /**
     * How far, in levels, natural ground may lie under a road's surface and still give way: the bank lays it onto the
     * road's grade, which the emitted road follows only to within this, and left there it would show through the road.
     */
    private static final float UNDER = .05f;

    /**
     * Removes every part of {@code faces} that stands on the reserved ground and keeps the rest, cut exactly along its
     * boundary. Each kept face's cut edge on that boundary gets a vertical face from the terrain to the road beside it,
     * where they do not meet. New vertices take their face's shading from {@code relief}. Loose rocks and dressing are
     * removed whole when their middle is reserved. A poured top gives way to the road wholly; a poured step only above
     * the emitted road surface, so a step under a bridge's deck keeps its shape and meets the road exactly where it
     * passes under it. {@code natural} ground is already eased into the roads around ({@code bank}): its top gives way
     * below the road's headroom down to a fill's depth under the road, so none lies hidden just under it, and its slopes
     * and cliffs above the road's surface, so their faces run on beneath its edge; a cliff over the headroom stands over
     * its verge. A water hex's {@code shore} carries no road of its own: its top gives way only above the road's
     * surface, as a slope does. Natural ground gives way to a road built in concrete (BoardRoadGrade#poured) as poured
     * ground does, and its cut faces beside that road go to {@code concrete}.
     */
    List<BoardSurface.Face> clip(List<BoardSurface.Face> faces, BoardRelief relief, WallBuilder wall,
          WallBuilder concrete, List<BoardSurface.Face> road, BoardRoadBank bank, boolean natural, boolean shore) {
        Area region = region();
        Rectangle2D bounds = region.getBounds2D();
        List<BoardSurface.Face> result = new ArrayList<>();
        // Cut points are computed in each face's own frame; weld the copies two neighbouring faces make of one point.
        Map<List<Long>, Vector3> welded = new HashMap<>();
        // Each corner's road surface, once for all the faces sharing it.
        Map<Vector3, Float> beds = new IdentityHashMap<>();
        // Poured ground has no bank: off the reserved ground its corners continue the roads reserving ground in this hex.
        // The bank continues the road nearest a corner, which can be another one at another level beside the hex: its
        // surface would tilt the cut across a long cast face.
        List<BoardRoadGrade> reserving = natural ? List.of() : pieces.stream().map(Piece::grade).toList();
        boolean pouredHere = pieces.stream().anyMatch(piece -> piece.grade().poured());
        // Every corner on the road's edge meets the road before any face is cut, so all cut from the same heights.
        if (natural) {
            Set<Vector3> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (BoardSurface.Face face : faces) {
                for (Vector3 v : List.of(face.a(), face.b(), face.c())) {
                    if (seen.add(v)) { snap(v); }
                }
            }
        }
        for (BoardSurface.Face face : faces) {
            Vector3 a = face.a(), b = face.b(), c = face.c();
            float minX = Math.min(a.x, Math.min(b.x, c.x)), maxX = Math.max(a.x, Math.max(b.x, c.x));
            float minY = Math.min(a.y, Math.min(b.y, c.y)), maxY = Math.max(a.y, Math.max(b.y, c.y));
            if (maxX < bounds.getMinX() || minX > bounds.getMaxX() || maxY < bounds.getMinY() || minY > bounds.getMaxY()) {
                result.add(face);
                continue;
            }
            // A face standing upright on the outline the reserved ground shares with the lattice is probed just inside
            // this hex, where the ground it bounds lies: on the outline itself, which side holds it is arbitrary.
            float upright = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            float longest = Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
            float nudge = Math.abs(upright) < 1e-4f * longest ? 4 * epsilon : 0;
            float mx = (a.x + b.x + c.x) / 3, my = (a.y + b.y + c.y) / 3, md = (float) Math.hypot(cx - mx, cy - my);
            float ox = md > 0 ? (cx - mx) / md * nudge : 0, oy = md > 0 ? (cy - my) / md * nudge : 0;
            // The face's heights against the road's: above its surface, and for a natural top below it too, up to its
            // headroom it is reserved.
            float[] under = new float[3], over = new float[3];
            Vector3[] corners = { a, b, c };
            boolean top = face.finish() == BoardSurface.Finish.TOP && !shore, pouredTop = !natural && top;
            // Natural slopes are cut just under the road's surface, which covers their cut edge; at the surface itself
            // the board's cut, which has no ground beyond it, and an upright face, which never lies along the road, as a
            // cast step's face standing on the edge under a road's mouth. Poured ground keeps a band beneath the road.
            boolean boardCut = !top && face.landEdge() >= 0 && (boardEdges & 1 << face.landEdge()) != 0;
            float band = boardCut || nudge > 0 ? 0 : natural ? UNDER : ON_SURFACE;
            for (int i = 0; i < 3; i++) {
                if (pouredTop) {
                    under[i] = Float.MAX_VALUE;
                    over[i] = -Float.MAX_VALUE;
                    continue;
                }
                float x = corners[i].x + ox, y = corners[i].y + oy;
                float bed = nudge > 0 ? bed(x, y, road, bank) : beds.computeIfAbsent(corners[i], v -> bed(v.x, v.y, road, bank));
                if (!reserving.isEmpty() && !region.contains(x, y)) { bed = surface(reserving, x, y, bed); }
                under[i] = corners[i].z - bed + band * BoardGeometry.level();
                over[i] = !natural ? -Float.MAX_VALUE
                      : corners[i].z - (bed + BoardRoadGrade.CLEARANCE * BoardGeometry.level());
            }
            float lift = -band * BoardGeometry.level(), headroom = BoardRoadGrade.CLEARANCE * BoardGeometry.level();
            float fill = BoardRoadGrade.FILL * BoardGeometry.level();
            Reserved reserved = (s, t) -> {
                float x = a.x + s * (b.x - a.x) + t * (c.x - a.x) + ox;
                float y = a.y + s * (b.y - a.y) + t * (c.y - a.y) + oy;
                if (!region.contains(x, y)) { return false; }
                // Against the emitted road where it lies, which the cut follows exactly; else as its corners have it.
                float z = a.z + s * (b.z - a.z) + t * (c.z - a.z), surface = roadHeight(road, x, y, Float.NaN);
                boolean exactly = !pouredTop && !Float.isNaN(surface);
                boolean above = exactly ? z >= surface + lift : under[0] + s * (under[1] - under[0]) + t * (under[2] - under[0]) >= 0;
                BoardRoadGrade grade = natural ? grade(x, y) : null;
                if (grade != null && grade.poured()) { return top || above; }
                // A natural top gives way to the road below its headroom down to a fill's depth under it: it would only
                // lie hidden under the road. Further down it is ground the road passes over, as at a bridge's landing. A
                // slope or cliff keeps what passes under the road, so its face runs on beneath the road's edge.
                boolean clear = exactly && natural ? z > surface + headroom
                      : over[0] + s * (over[1] - over[0]) + t * (over[2] - over[0]) > 0;
                boolean near = exactly ? z >= surface - fill
                      : under[0] + s * (under[1] - under[0]) + t * (under[2] - under[0]) + fill >= 0;
                return (natural && top && near || above) && !clear;
            };
            boolean ground = face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.WALL
                  || face.finish() == BoardSurface.Finish.RIM || face.finish() == BoardSurface.Finish.CAP;
            if (!ground) {
                if (!reserved.at(1 / 3f, 1 / 3f)) { result.add(face); }
                continue;
            }
            List<Segment> chords = new ArrayList<>();
            for (Segment s : edge) {
                if (Math.max(s.ax(), s.bx()) < minX - epsilon || Math.min(s.ax(), s.bx()) > maxX + epsilon
                      || s.high() < minY - epsilon || s.low() > maxY + epsilon) { continue; }
                Segment chord = chord(face, s, nudge);
                if (chord != null) { chords.add(chord); }
            }
            if (!chords.isEmpty() || region.contains(mx + ox, my + oy)) {
                // A natural top is cut at the headroom and a fill's depth under the road (see reserved).
                boolean surfaceCut = !(natural && top && !pouredHere);
                List<BoardSurface.Face> planes = nudge > 0 || pouredTop ? List.of() : near(road, minX, minY, maxX, maxY);
                if (planes.isEmpty()) {
                    float[] deep = { under[0] + fill, under[1] + fill, under[2] + fill };
                    for (float[] values : surfaceCut ? List.of(under, over) : List.of(over, deep)) {
                        Segment chord = zero(values);
                        if (chord != null) { chords.add(chord); }
                    }
                } else {
                    // Where the face meets the road's surface, headroom or a fill's depth over each of the road's faces.
                    List<Float> offsets = new ArrayList<>();
                    offsets.add(surfaceCut ? lift : -fill);
                    if (natural) { offsets.add(headroom); }
                    for (BoardSurface.Face piece : planes) {
                        for (float offset : offsets) {
                            Segment chord = zero(new float[] { a.z - plane(piece, a.x, a.y) - offset,
                                  b.z - plane(piece, b.x, b.y) - offset, c.z - plane(piece, c.x, c.y) - offset });
                            chord = chord == null ? null : within(chord, face, piece);
                            if (chord != null) { chords.add(chord); }
                        }
                    }
                }
            }
            // A face ending on the road's edge, as the world's grid can split it there, is not cut but still closes against it.
            if (chords.isEmpty() && !alongEdge(a, b) && !alongEdge(b, c) && !alongEdge(c, a)) {
                if (!reserved.at(1 / 3f, 1 / 3f)) { result.add(face); }
                continue;
            }
            clipFace(face, chords, region, reserved, relief, result, wall, natural ? concrete : wall, welded, road, natural);
        }
        return result;
    }

    /** The largest height, in levels, by which natural ground on the road's edge moves onto the road instead of being cut. */
    private static final float SNAP = .05f;

    /**
     * Natural ground on the road's edge that misses the road only by a hair meets it exactly, so no hairline cut face
     * shows as a dark seam along the road. The height depends only on where the point lies (#edgeHeight), so every hex
     * moves it alike; only vertices the relief and bank made are moved, before any face is cut: a point a cut creates
     * may be shared with a hex too far from the road to cut it, which would keep it where it was.
     */
    private void snap(Vector3 v) {
        if (!onEdge(v.x, v.y)) { return; }
        float h = edgeHeight(v.x, v.y);
        if (Math.abs(v.z - h) < SNAP * BoardGeometry.level()) { v.z = h; }
    }

    /** The height of a face's plane over a point, continued past its edges. */
    private static float plane(BoardSurface.Face face, float x, float y) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y, det = bx * cy - by * cx;
        float s = ((x - a.x) * cy - (y - a.y) * cx) / det, t = (bx * (y - a.y) - by * (x - a.x)) / det;
        return a.z + s * (b.z - a.z) + t * (c.z - a.z);
    }

    /** The part of a chord of a face, in its (s, t) coordinates, that lies over another face's plan; null if none. */
    private static Segment within(Segment chord, BoardSurface.Face face, BoardSurface.Face other) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y, det = bx * cy - by * cx;
        float[] s = new float[3], t = new float[3];
        Vector3[] corners = { other.a(), other.b(), other.c() };
        for (int i = 0; i < 3; i++) {
            s[i] = ((corners[i].x - a.x) * cy - (corners[i].y - a.y) * cx) / det;
            t[i] = (bx * (corners[i].y - a.y) - by * (corners[i].x - a.x)) / det;
        }
        float orient = Math.signum((s[1] - s[0]) * (t[2] - t[0]) - (t[1] - t[0]) * (s[2] - s[0]));
        if (orient == 0) { return null; }
        float ds = chord.bx() - chord.ax(), dt = chord.by() - chord.ay();
        float[] range = { 0, 1 };
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            float es = s[j] - s[i], et = t[j] - t[i];
            if (!limit(range, orient * (es * (chord.ay() - t[i]) - et * (chord.ax() - s[i])), orient * (es * dt - et * ds))) {
                return null;
            }
        }
        if (range[1] - range[0] < 1e-6f) { return null; }
        return new Segment(chord.ax() + ds * range[0], chord.ay() + dt * range[0], chord.ax() + ds * range[1],
              chord.ay() + dt * range[1]);
    }

    /** Whether the point at (s, t) of a face is reserved. */
    private interface Reserved { boolean at(float s, float t); }

    /** Where a quantity linear over a face, given at its corners, is zero, in the face's (s, t) coordinates; or null. */
    private static Segment zero(float[] values) {
        float a = values[0], b = values[1], c = values[2];
        List<float[]> points = new ArrayList<>(2);
        if (a * b < 0) { points.add(new float[] { a / (a - b), 0 }); }
        if (a * c < 0) { points.add(new float[] { 0, a / (a - c) }); }
        if (b * c < 0) {
            float u = b / (b - c);
            points.add(new float[] { 1 - u, u });
        }
        if (points.size() < 2) { return null; }
        float[] p = points.get(0), q = points.get(1);
        return new Segment(p[0], p[1], q[0], q[1]);
    }

    /**
     * The road's surface at a point: the emitted road where it lies, else as the roads around continue it; without
     * their {@code bank} (a water hex keeps its own ground), the grade reserving the point, else NaN.
     */
    private float bed(float x, float y, List<BoardSurface.Face> road, BoardRoadBank bank) {
        float bed = roadHeight(road, x, y, Float.NaN);
        if (!Float.isNaN(bed)) { return bed; }
        if (bank != null) { return bank.bed(x, y); }
        BoardRoadGrade grade = grade(x, y);
        return grade == null ? Float.NaN : grade.height(x, y);
    }

    /** Of these grades' surfaces at a point, the one nearest the given height: the height itself where one continues it. */
    private static float surface(List<BoardRoadGrade> grades, float x, float y, float height) {
        float result = Float.NaN;
        for (BoardRoadGrade grade : grades) {
            float h = grade.height(x, y);
            if (Float.isNaN(result) || Math.abs(h - height) < Math.abs(result - height)) { result = h; }
        }
        return result;
    }

    /** The road's emitted surface at a point, as BoardSurface#sampleHeight finds it, from the faces whose cell holds it. */
    private float roadHeight(List<BoardSurface.Face> road, float x, float y, float fallback) {
        List<BoardSurface.Face> near = near(road, x, y, x, y);
        return near.isEmpty() ? fallback : BoardSurface.sampleHeight(near, x, y, fallback);
    }

    /** The road faces whose cells hold any of the given bounds. */
    private List<BoardSurface.Face> near(List<BoardSurface.Face> road, float minX, float minY, float maxX, float maxY) {
        float cell = ROAD_CELL * BoardGeometry.hexScale();
        if (road != indexed) {
            indexed = road;
            roadCells.clear();
            // Past the bounds by the sample's border tolerance, so every face it may take is in the cell.
            float pad = .002f * BoardGeometry.hexScale();
            for (BoardSurface.Face face : road) {
                Vector3 a = face.a(), b = face.b(), c = face.c();
                int x0 = (int) Math.floor((Math.min(a.x, Math.min(b.x, c.x)) - pad) / cell);
                int x1 = (int) Math.floor((Math.max(a.x, Math.max(b.x, c.x)) + pad) / cell);
                int y0 = (int) Math.floor((Math.min(a.y, Math.min(b.y, c.y)) - pad) / cell);
                int y1 = (int) Math.floor((Math.max(a.y, Math.max(b.y, c.y)) + pad) / cell);
                for (int i = x0; i <= x1; i++) {
                    for (int j = y0; j <= y1; j++) { roadCells.computeIfAbsent(cellKey(i, j), key -> new ArrayList<>()).add(face); }
                }
            }
        }
        int x0 = (int) Math.floor(minX / cell), x1 = (int) Math.floor(maxX / cell);
        int y0 = (int) Math.floor(minY / cell), y1 = (int) Math.floor(maxY / cell);
        if (x0 == x1 && y0 == y1) { return roadCells.getOrDefault(cellKey(x0, y0), List.of()); }
        Set<BoardSurface.Face> near = new LinkedHashSet<>();
        for (int i = x0; i <= x1; i++) {
            for (int j = y0; j <= y1; j++) { near.addAll(roadCells.getOrDefault(cellKey(i, j), List.of())); }
        }
        return new ArrayList<>(near);
    }

    private static long cellKey(int i, int j) { return (long) i << 32 ^ (j & 0xffffffffL); }

    /**
     * The trace of a boundary segment's vertical plane on a face, in the face's (s, t) coordinates; null if none. On an
     * upright face it is a vertical line, where the segment crosses the face's plan: one ending there, as where the
     * outline turns on the face's line, still marks it if it reaches within {@code reach} of it.
     */
    private static Segment chord(BoardSurface.Face face, Segment s, float reach) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float dx = s.bx() - s.ax(), dy = s.by() - s.ay(), length2 = dx * dx + dy * dy;
        float slack = reach / (float) Math.sqrt(length2);
        float bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y;
        // The plane: alpha s + beta t + gamma = 0; the position along the segment: lambda0 + ls s + lt t.
        float alpha = dx * by - dy * bx, beta = dx * cy - dy * cx, gamma = dx * (a.y - s.ay()) - dy * (a.x - s.ax());
        float lambda0 = ((a.x - s.ax()) * dx + (a.y - s.ay()) * dy) / length2;
        float ls = (bx * dx + by * dy) / length2, lt = (cx * dx + cy * dy) / length2;
        float scale = (float) Math.sqrt(length2) * Math.max((float) Math.hypot(bx, by), (float) Math.hypot(cx, cy));
        if (Math.abs(alpha) < 1e-6f * scale && Math.abs(beta) < 1e-6f * scale) { return null; }
        // Parameterize along the line by the better conditioned coordinate: p(u) = p0 + u * d.
        float p0s, p0t, ds, dt;
        if (Math.abs(beta) >= Math.abs(alpha)) { p0s = 0; p0t = -gamma / beta; ds = 1; dt = -alpha / beta; }
        else { p0s = -gamma / alpha; p0t = 0; ds = -beta / alpha; dt = 1; }
        float[] range = { Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY };
        // s >= 0, t >= 0, s + t <= 1, 0 <= lambda <= 1.
        if (!limit(range, p0s, ds) || !limit(range, p0t, dt) || !limit(range, 1 - p0s - p0t, -ds - dt)
              || !limit(range, lambda0 + ls * p0s + lt * p0t + slack, ls * ds + lt * dt)
              || !limit(range, 1 - lambda0 - ls * p0s - lt * p0t + slack, -ls * ds - lt * dt)) { return null; }
        if (range[1] - range[0] < 1e-6f) { return null; }
        return new Segment(p0s + ds * range[0], p0t + dt * range[0], p0s + ds * range[1], p0t + dt * range[1]);
    }

    /** Narrows {@code range} to where value + slope * u >= 0; false if it becomes empty. */
    private static boolean limit(float[] range, float value, float slope) {
        if (Math.abs(slope) < 1e-12f) { return value >= -1e-7f; }
        float root = -value / slope;
        if (slope > 0) { range[0] = Math.max(range[0], root); } else { range[1] = Math.min(range[1], root); }
        return range[1] > range[0];
    }

    private static final int KEPT = 0, RESERVED = 1;

    private void clipFace(BoardSurface.Face face, List<Segment> chords, Area region, Reserved reserved, BoardRelief relief,
          List<BoardSurface.Face> result, WallBuilder wall, WallBuilder concrete, Map<List<Long>, Vector3> welded,
          List<BoardSurface.Face> road, boolean natural) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        List<Segment> segments = new ArrayList<>(chords);
        segments.add(new Segment(0, 0, 1, 0));
        segments.add(new Segment(1, 0, 0, 1));
        segments.add(new Segment(0, 1, 0, 0));
        // Two rounding steps of the face's coordinates, as a share of it: a chord that far off the face's edge is
        // that edge.
        float magnitude = Math.max(Math.max(Math.abs(a.x), Math.abs(a.y)),
              Math.max(Math.max(Math.abs(b.x), Math.abs(b.y)), Math.max(Math.abs(c.x), Math.abs(c.y))));
        float e = Math.max(1e-6f, 2 * Math.ulp(magnitude)
              / (float) Math.sqrt(Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)))));
        Classifier classifier = (s, t) -> s < -e || t < -e || s + t > 1 + e ? OUTSIDE : reserved.at(s, t) ? RESERVED : KEPT;
        Map<Point, Vector3> vertices = new HashMap<>();
        java.util.function.Function<Point, Vector3> vertex = p -> vertices.computeIfAbsent(p, key -> {
            float s = p.x(), t = p.y();
            if (s == 0 && t == 0) { return a; }
            if (s == 1 && t == 0) { return b; }
            if (s == 0 && t == 1) { return c; }
            Vector3 v = new Vector3(a).mulAdd(new Vector3(b).sub(a), s).mulAdd(new Vector3(c).sub(a), t);
            float grid = 4 * epsilon;
            // A cut a hair from the face's corner is that corner: beside it, it would leave a needle face.
            for (Vector3 corner : new Vector3[] { a, b, c }) {
                if (corner.dst2(v) <= grid * grid) { return corner; }
            }
            long x = Math.round(v.x / grid), y = Math.round(v.y / grid), z = Math.round(v.z / grid);
            var weld = List.of(x, y, z);
            Vector3 same = welded.get(weld);
            // Two copies of one point can fall either side of a cell's edge: the cells beside it on its nearer sides.
            long dx = v.x / grid > x ? 1 : -1, dy = v.y / grid > y ? 1 : -1, dz = v.z / grid > z ? 1 : -1;
            for (int k = 1; same == null && k < 8; k++) {
                Vector3 near = welded.get(List.of(x + (k & 1) * dx, y + (k >> 1 & 1) * dy, z + (k >> 2) * dz));
                if (near != null && near.dst2(v) <= epsilon * epsilon) { same = near; }
            }
            if (same != null) {
                // Every later copy then finds the same point in its own cell.
                welded.putIfAbsent(weld, same);
                return same;
            }
            relief.interpolateShade(a, b, c, 1 - s - t, s, t, v);
            welded.put(weld, v);
            return v;
        });
        // Twice the face's area in plan; an upright face has none (see #clip).
        float plan = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        boolean upright = Math.abs(plan) < 1e-4f * Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
        Sweep sweep = sweep(segments, e, classifier, (p, q, r, source) -> {
            if (source != KEPT) { return; }
            // The sweep emits counter-clockwise in (s, t); keep the face's own winding in space.
            Vector3 va = vertex.apply(p), vb = vertex.apply(q), vc = vertex.apply(r);
            float longest = Math.max(va.dst2(vb), Math.max(vb.dst2(vc), vc.dst2(va)));
            // A zero-area face has no plane: ray picking would report it hit at the ray's origin.
            if (new Vector3(vb).sub(va).crs(new Vector3(vc).sub(va)).len2() > 1e-10f * longest * longest) {
                result.add(new BoardSurface.Face(va, vb, vc, face.finish(), face.landEdge(), face.scatter()));
            }
        });
        // Cut faces along every trace of the reserved ground's boundary on this face: between kept and reserved parts,
        // and where the ground kept on both sides passes under the road's edge, a fill face up to it. An upright face has
        // no side in plan: a cut face from it would only lie in its own plane.
        for (Segment s : wall == null || upright ? List.<Segment>of() : sweep.segments()) {
            float ms = (s.ax() + s.bx()) / 2, mt = (s.ay() + s.by()) / 2;
            float ns = -(s.by() - s.ay()), nt = s.bx() - s.ax(), norm = (float) Math.hypot(ns, nt);
            if (norm < 1e-9f) { continue; }
            // Either side takes the part the sweep classified beside it, however thin, and the reserved ground where that
            // part's middle lies; past the face, a point as far off in plan as the outline is resolved.
            Part leftPart = beside(sweep, s, true), rightPart = beside(sweep, s, false);
            int left = leftPart == null ? OUTSIDE : leftPart.source(), right = rightPart == null ? OUTSIDE : rightPart.source();
            if (left == RESERVED && right == RESERVED || left == OUTSIDE && right == OUTSIDE) { continue; }
            float offset = 1e-4f;
            float sx = (s.bx() - s.ax()) * (b.x - a.x) + (s.by() - s.ay()) * (c.x - a.x);
            float sy = (s.bx() - s.ax()) * (b.y - a.y) + (s.by() - s.ay()) * (c.y - a.y), length = (float) Math.hypot(sx, sy);
            if (!upright && length > 1e-9f) {
                // Only the share along the normal of a plan offset across the segment moves off its line.
                float px = -sy / length * 4 * epsilon, py = sx / length * 4 * epsilon;
                float ds = ((c.y - a.y) * px - (c.x - a.x) * py) / plan, dt = ((b.x - a.x) * py - (b.y - a.y) * px) / plan;
                offset = Math.max(offset, Math.abs(ds * ns + dt * nt) / norm);
            }
            float ls = leftPart == null ? ms + ns / norm * offset : leftPart.x();
            float lt = leftPart == null ? mt + nt / norm * offset : leftPart.y();
            float rs = rightPart == null ? ms - ns / norm * offset : rightPart.x();
            float rt = rightPart == null ? mt - nt / norm * offset : rightPart.y();
            boolean leftReserved = region.contains(a.x + ls * (b.x - a.x) + lt * (c.x - a.x), a.y + ls * (b.y - a.y) + lt * (c.y - a.y));
            boolean rightReserved = region.contains(a.x + rs * (b.x - a.x) + rt * (c.x - a.x), a.y + rs * (b.y - a.y) + rt * (c.y - a.y));
            if (leftReserved == rightReserved) {
                // On the reserved ground, natural ground kept over the road's headroom meets the part that gave way
                // below it: a cut face from its edge down to the road closes it, so the overhang stands on the verge.
                if (natural && leftReserved && left != OUTSIDE && right != OUTSIDE && (left == KEPT) != (right == KEPT)) {
                    Vector3 p = vertex.apply(new Point(s.ax(), s.ay())), q = vertex.apply(new Point(s.bx(), s.by()));
                    float rp = roadHeight(road, p.x, p.y, Float.NaN), rq = roadHeight(road, q.x, q.y, Float.NaN);
                    float above = BoardRoadGrade.CLEARANCE * BoardGeometry.level() / 2;
                    if (p.z - rp > above && q.z - rq > above) {
                        float gs = left == KEPT ? rs : ls, gt = left == KEPT ? rt : lt;
                        curtain(p, q, new Vector3(a).mulAdd(new Vector3(b).sub(a), gs).mulAdd(new Vector3(c).sub(a), gt),
                              region, wall, concrete, road, false, natural);
                    }
                }
                continue;
            }
            // A kept face's own edge on the reserved ground's boundary closes against the road too: the reserved side is
            // another face's, which, removed, leaves it to this one.
            if ((left == OUTSIDE || right == OUTSIDE) && (left == OUTSIDE ? right : left) != KEPT
                  || left == OUTSIDE && !leftReserved || right == OUTSIDE && !rightReserved) {
                continue;
            }
            curtain(vertex.apply(new Point(s.ax(), s.ay())), vertex.apply(new Point(s.bx(), s.by())), null, region, wall,
                  concrete, road, left == right, natural);
        }
    }

    /**
     * The vertical face between a cut terrain edge and the road beside it, on the road's side ({@code toward}, or the
     * reserved side of the boundary), split where the two cross; with
     * {@code fill}, only where the ground lies under the road; below it, at most {@link BoardRoadGrade#FILL} deep where
     * the ground and the road beside it are both natural.
     * Beside a road built in concrete it is {@code concrete}'s; beside a bridge's ramp only where it lies under the road.
     */
    private void curtain(Vector3 p, Vector3 q, Vector3 toward, Area region, WallBuilder wall, WallBuilder concrete,
          List<BoardSurface.Face> road, boolean fill, boolean natural) {
        float dx = q.x - p.x, dy = q.y - p.y, length = (float) Math.hypot(dx, dy);
        if (length < epsilon) { return; }
        // The road's side of this stretch: towards the given point, else the reserved side of the boundary.
        float nx = -dy / length, ny = dx / length, mx = (p.x + q.x) / 2, my = (p.y + q.y) / 2, probe = 4 * epsilon;
        if (toward != null ? (toward.x - mx) * nx + (toward.y - my) * ny < 0 : !region.contains(mx + nx * probe, my + ny * probe)) {
            nx = -nx;
            ny = -ny;
        }
        // The grade beside the middle of the stretch; its surface continues to both ends, even round a corner.
        BoardRoadGrade grade = grade(mx + nx * probe, my + ny * probe);
        if (grade == null) { return; }
        // Beside a road built in concrete the cut is cast as well. Beside a bridge's ramp, which has no natural bank,
        // only the fill under the road is: a cut above it stays the bank's own, as the rest of the cut is.
        boolean cast = grade.poured() || grade.bridgeRamp();
        // A road built in concrete has concrete cut faces; ground cut by a roadless apron's ramp shows its own face.
        if (grade.poured() && BoardRoad.carriageway(grade.tile())) { wall = concrete; }
        WallBuilder fillWall = grade.bridgeRamp() ? concrete : wall;
        // Natural ground beside a natural road closes up to it only from a fill's depth: further down the road passes
        // over it, as it does at a bridge's landing, and the ground keeps its shape.
        boolean shallow = fill || natural && !cast;
        // The road's edge bends only at its stations and the emitted road's own vertices: the face meets it at each.
        List<Float> stations = new ArrayList<>(List.of(0f, 1f));
        for (float[] cut : crossings(p.x, p.y, q.x, q.y)) { stations.add(cut[0]); }
        List<Vector3> corners = new ArrayList<>();
        for (Segment s : edge) {
            corners.add(new Vector3(s.ax(), s.ay(), 0));
            // Where the road's edge leaves the stretch, beside it: the face stands only along the reserved ground.
            float[] hit = intersection(p.x + nx * probe, p.y + ny * probe, q.x + nx * probe, q.y + ny * probe, s.ax(), s.ay(),
                  s.bx(), s.by());
            if (hit != null && hit[0] * length > epsilon && (1 - hit[0]) * length > epsilon) { stations.add(hit[0]); }
        }
        for (BoardSurface.Face face : near(road, Math.min(p.x, q.x), Math.min(p.y, q.y), Math.max(p.x, q.x), Math.max(p.y, q.y))) {
            corners.addAll(List.of(face.a(), face.b(), face.c()));
        }
        for (Vector3 v : corners) {
            float t = ((v.x - p.x) * dx + (v.y - p.y) * dy) / (length * length);
            if (t * length > epsilon && (1 - t) * length > epsilon
                  && Math.abs((v.x - p.x) * dy - (v.y - p.y) * dx) < probe * length) { stations.add(t); }
        }
        stations.sort(null);
        for (int i = 1; i < stations.size(); i++) {
            if (stations.get(i) - stations.get(i - 1) < 1e-6f) { continue; }
            Vector3 a = i == 1 ? p : new Vector3(p).lerp(q, stations.get(i - 1));
            Vector3 b = i == stations.size() - 1 ? q : new Vector3(p).lerp(q, stations.get(i));
            if (!region.contains((a.x + b.x) / 2 + nx * probe, (a.y + b.y) / 2 + ny * probe)) { continue; }
            // The emitted road's own surface, so the face's top runs along the road's edge between its vertices.
            float ra = roadHeight(road, a.x, a.y, edge(grade, a.x, a.y)), rb = roadHeight(road, b.x, b.y, edge(grade, b.x, b.y));
            float da = a.z - ra, db = b.z - rb;
            if (da * db < 0) {
                float u = da / (da - db);
                Vector3 m = new Vector3(a).lerp(b, u);
                float rm = ra + (rb - ra) * u;
                curtain(a, ra, m, rm, nx, ny, wall, fillWall, fill, shallow);
                curtain(m, rm, b, rb, nx, ny, wall, fillWall, fill, shallow);
            } else { curtain(a, ra, b, rb, nx, ny, wall, fillWall, fill, shallow); }
        }
    }

    /** The road's edge height at a point (#edgeHeight), or the grade's own surface where the point is off the edge. */
    private float edge(BoardRoadGrade grade, float x, float y) {
        float h = edgeHeight(x, y);
        return Float.isNaN(h) ? grade.height(x, y) : h;
    }

    /** A cut goes to {@code wall}, ground under the road to {@code fillWall}. */
    private void curtain(Vector3 p, float rp, Vector3 q, float rq, float nx, float ny, WallBuilder wall,
          WallBuilder fillWall, boolean fill, boolean shallow) {
        float tolerance = .001f * BoardGeometry.hexScale();
        if (Math.abs(p.z - rp) < tolerance && Math.abs(q.z - rq) < tolerance) { return; }
        boolean cut = p.z + q.z > rp + rq;
        float depth = Math.max(rp - p.z, rq - q.z);
        // A hair past a fill's depth too: ground a whole level under the road is common, and float noise would toggle it.
        if (fill && cut || shallow && !cut && depth > (BoardRoadGrade.FILL + .1f) * BoardGeometry.level()) { return; }
        if (!cut) { wall = fillWall; }
        // A cut faces the road; terrain below a raised road faces away from it.
        wall.wall(new Vector3(p.x, p.y, Math.max(p.z, rp)), new Vector3(p.x, p.y, Math.min(p.z, rp)),
              new Vector3(q.x, q.y, Math.max(q.z, rq)), new Vector3(q.x, q.y, Math.min(q.z, rq)),
              cut ? nx : -nx, cut ? ny : -ny);
    }

    /**
     * The least step, in metres, a containment wall covers, and the shortest stretch it runs along: every step the
     * bank retains at the road's edge (BoardRoadBank) is covered, so no bare seam shows.
     */
    private static final float WALL_CUT = .05f, WALL_RUN = 1;
    /** Width, in metres, of a containment wall's precast panel. */
    static final float PANEL = 2.5f;

    /**
     * Precast containment walls where this hex's natural {@code ground} still stands over a road's edge along the walled
     * runs of the slopes beside it (BoardRelief#walled), and a panel on past either end, over the slope's fall to the road
     * there: straight panels just inside the edge, each from below the road to a capped top in half-metre steps, a metre
     * at least and just over the cut it retains, never more than BoardRoadBank#WALL. A road built in concrete
     * (BoardRoadGrade#poured) has its own cut faces instead.
     */
    void containmentWalls(List<BoardSurface.Face> ground, List<BoardSurface.Face> road, BoardRelief relief,
          List<BoardSurface.Face> into) {
        float m = BoardRelief.metres(1), probe = .1f * BoardGeometry.hexScale();
        Area region = region();
        PathIterator path = region.getPathIterator(null, .02f * BoardGeometry.hexScale());
        float[] point = new float[6];
        List<float[]> loop = new ArrayList<>();
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) { loop.clear(); }
            if (type != PathIterator.SEG_CLOSE) { loop.add(new float[] { point[0], point[1] }); }
            if (type == PathIterator.SEG_CLOSE && loop.size() > 2) {
                loop.add(loop.getFirst());
                // Samples round the outline, each segment from its start: position, normal into the road, the road's
                // height (NaN off a road's edge) and the cut a wall retains over it (NaN where none does).
                List<float[]> samples = new ArrayList<>();
                for (int i = 1; i < loop.size(); i++) {
                    float[] a = loop.get(i - 1), b = loop.get(i);
                    float dx = b[0] - a[0], dy = b[1] - a[1], length = (float) Math.hypot(dx, dy);
                    if (length < epsilon) { continue; }
                    float nx = -dy / length, ny = dx / length;
                    if (!region.contains((a[0] + b[0]) / 2 + nx * probe, (a[1] + b[1]) / 2 + ny * probe)) {
                        nx = -nx;
                        ny = -ny;
                    }
                    int count = Math.max(1, (int) Math.ceil(length / m));
                    for (int k = 0; k < count; k++) {
                        float x = a[0] + dx * k / count, y = a[1] + dy * k / count;
                        BoardRoadGrade grade = grade(x + nx * probe, y + ny * probe);
                        // Only a road's edge, not where it runs on into another's.
                        float bed = grade == null || grade.poured() || grade(x - nx * probe, y - ny * probe) != null
                              ? Float.NaN : roadHeight(road, x + nx * probe, y + ny * probe,
                                    grade.height(x + nx * probe, y + ny * probe));
                        float cut = Float.isNaN(bed) || relief.walled(x, y) < .5f ? Float.NaN
                              : Math.min(BoardSurface.sampleHeight(ground, x - nx * probe, y - ny * probe, Float.NaN) - bed,
                                    BoardRoadBank.WALL * BoardGeometry.level());
                        samples.add(new float[] { x, y, nx, ny, bed, cut >= WALL_CUT * m ? cut : Float.NaN });
                    }
                }
                walls(samples, into);
            }
            path.next();
        }
    }

    /**
     * Containment walls along a closed outline's samples {x, y, nx, ny, road, cut}: over each run of retained samples,
     * and a panel on past either end along the same road's edge while it keeps its heading.
     */
    private static void walls(List<float[]> samples, List<BoardSurface.Face> into) {
        int n = samples.size();
        float reach = PANEL * BoardRelief.metres(1);
        boolean[] retained = new boolean[n], wall = new boolean[n];
        for (int i = 0; i < n; i++) { retained[i] = wall[i] = !Float.isNaN(samples.get(i)[5]); }
        for (int i = 0; i < n; i++) {
            for (int step = -1; retained[i] && step <= 1; step += 2) {
                float[] end = samples.get(i), last = end;
                float length = 0;
                for (int j = Math.floorMod(i + step, n); !retained[j] && length < reach; j = Math.floorMod(j + step, n)) {
                    float[] next = samples.get(j);
                    if (Float.isNaN(next[4]) || next[2] * end[2] + next[3] * end[3] < .7f) { break; }
                    length += (float) Math.hypot(next[0] - last[0], next[1] - last[1]);
                    wall[j] = true;
                    last = next;
                }
            }
        }
        // Round the loop from a sample without a wall, so no run is split at the loop's start.
        int start = 0;
        while (start < n && wall[start]) { start++; }
        List<float[]> run = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            float[] sample = samples.get(i);
            if (wall[i]) {
                run.add(retained[i] ? sample : new float[] { sample[0], sample[1], sample[2], sample[3], sample[4], 0 });
            } else {
                panels(run, into);
                run.clear();
            }
        }
        panels(run, into);
    }

    /** Panels along a run of outline samples {x, y, nx, ny, road, cut}; see {@link #containmentWalls}. */
    private static void panels(List<float[]> run, List<BoardSurface.Face> into) {
        float m = BoardRelief.metres(1), width = PANEL * m, joint = .05f * m, thick = .2f * m, step = .5f * m;
        float cap = .25f * m, lip = .08f * m, footing = .3f * m, gap = .05f * m;
        float[] along = new float[run.size()];
        for (int i = 1; i < run.size(); i++) {
            along[i] = along[i - 1] + (float) Math.hypot(run.get(i)[0] - run.get(i - 1)[0], run.get(i)[1] - run.get(i - 1)[1]);
        }
        float length = run.isEmpty() ? 0 : along[along.length - 1];
        if (length < WALL_RUN * m) { return; }
        int count = Math.max(1, Math.round(length / width));
        for (int k = 0; k < count; k++) {
            float from = k * length / count + (k == 0 ? 0 : joint / 2);
            float to = (k + 1) * length / count - (k + 1 == count ? 0 : joint / 2);
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            float nx = 0, ny = 0;
            for (int i = 0; i < run.size(); i++) {
                if (along[i] < from - width / 4 || along[i] > to + width / 4) { continue; }
                float[] sample = run.get(i);
                low = Math.min(low, sample[4]);
                high = Math.max(high, sample[4]);
                top = Math.max(top, sample[4] + sample[5]);
                nx += sample[2];
                ny += sample[3];
            }
            float[] a = at(run, along, from), b = at(run, along, to);
            float ex = b[0] - a[0], ey = b[1] - a[1], span = (float) Math.hypot(ex, ey);
            if (!Float.isFinite(low) || span < .01f * m) { continue; }
            // A metre at least over the road, and just over the cut, in half-metre steps; the cap tops it.
            top = high + Math.max(m, (float) Math.ceil((top - high + cap) / step) * step) - cap;
            // The panel's face is square to its own run, towards the road.
            float fx = -ey / span, fy = ex / span;
            if (fx * nx + fy * ny < 0) {
                fx = -fx;
                fy = -fy;
            }
            // Its back stands clear of the edge wherever the edge bows towards the road between its ends, so the slope
            // cut at the edge never shows through it.
            float bow = 0;
            for (int i = 0; i < run.size(); i++) {
                float[] sample = run.get(i);
                if (along[i] >= from && along[i] <= to) { bow = Math.max(bow, (sample[0] - a[0]) * fx + (sample[1] - a[1]) * fy); }
            }
            bow += gap;
            Vector3 backA = new Vector3(a[0] + fx * bow, a[1] + fy * bow, 0), backB = new Vector3(b[0] + fx * bow, b[1] + fy * bow, 0);
            Vector3 frontA = new Vector3(backA).add(fx * thick, fy * thick, 0);
            Vector3 frontB = new Vector3(backB).add(fx * thick, fy * thick, 0);
            box(into, frontA, frontB, backA, backB, low - footing, top, fx, fy);
            // The cap overhangs the panel's face a little.
            box(into, new Vector3(frontA).add(fx * lip, fy * lip, 0), new Vector3(frontB).add(fx * lip, fy * lip, 0),
                  backA, backB, top, top + cap, fx, fy);
        }
    }

    /** The position at a distance along a run of outline samples with the given cumulative lengths. */
    private static float[] at(List<float[]> run, float[] along, float distance) {
        for (int i = 1; i < run.size(); i++) {
            if (distance <= along[i] || i == run.size() - 1) {
                float u = Math.clamp((distance - along[i - 1]) / Math.max(1e-6f, along[i] - along[i - 1]), 0, 1);
                float[] p = run.get(i - 1), q = run.get(i);
                return new float[] { p[0] + (q[0] - p[0]) * u, p[1] + (q[1] - p[1]) * u };
            }
        }
        return new float[] { run.getFirst()[0], run.getFirst()[1] };
    }

    /** A closed box between a front and a back side, from low to high, its front facing (fx, fy). */
    private static void box(List<BoardSurface.Face> into, Vector3 frontA, Vector3 frontB, Vector3 backA, Vector3 backB,
          float low, float high, float fx, float fy) {
        // Seen from the front, a runs to b left to right, so every face winds outwards.
        if ((frontB.y - frontA.y) * fx - (frontB.x - frontA.x) * fy < 0) {
            Vector3 swap = frontA;
            frontA = frontB;
            frontB = swap;
            swap = backA;
            backA = backB;
            backB = swap;
        }
        Vector3 la = lift(frontA, low), lb = lift(frontB, low), ha = lift(frontA, high), hb = lift(frontB, high);
        Vector3 qa = lift(backA, low), qb = lift(backB, low), ra = lift(backA, high), rb = lift(backB, high);
        quad(into, la, lb, hb, ha);
        quad(into, ha, hb, rb, ra);
        quad(into, qa, la, ha, ra);
        quad(into, lb, qb, rb, hb);
        quad(into, qb, qa, ra, rb);
        quad(into, qa, qb, lb, la);
    }

    private static Vector3 lift(Vector3 p, float z) { return new Vector3(p.x, p.y, z); }

    private static void quad(List<BoardSurface.Face> into, Vector3 a, Vector3 b, Vector3 c, Vector3 d) {
        into.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.WALL));
        into.add(new BoardSurface.Face(a, c, d, BoardSurface.Finish.WALL));
    }

    /**
     * A conforming trapezoidal decomposition of the plane cut by {@code segments}: every trapezoid between two
     * crossing segments is classified at its middle and, unless outside, emitted as triangles with every vertex on its
     * horizontal sides kept, so no T junctions arise.
     */
    private static Sweep sweep(List<Segment> segments, float epsilon, Classifier classifier, Triangles out) {
        List<Segment> split = split(segments, epsilon);
        TreeMap<Float, List<Float>> levels = new TreeMap<>();
        for (Segment s : split) {
            levels.computeIfAbsent(s.ay(), key -> new ArrayList<>()).add(s.ax());
            levels.computeIfAbsent(s.by(), key -> new ArrayList<>()).add(s.bx());
        }
        for (var xs : levels.values()) { xs.sort(null); }
        List<Float> rows = new ArrayList<>(levels.keySet());
        List<Part> parts = new ArrayList<>();
        for (int row = 1; row < rows.size(); row++) {
            float bottom = rows.get(row - 1), top = rows.get(row), middle = (bottom + top) / 2;
            if (top - bottom < epsilon * .01f) { continue; }
            List<Segment> crossing = new ArrayList<>();
            for (Segment s : split) {
                if (s.low() <= bottom && s.high() >= top && s.ay() != s.by()) { crossing.add(s); }
            }
            crossing.sort((p, q) -> Float.compare(p.x(middle), q.x(middle)));
            for (int i = 1; i < crossing.size(); i++) {
                Segment left = crossing.get(i - 1), right = crossing.get(i);
                int source = classifier.source((left.x(middle) + right.x(middle)) / 2, middle);
                parts.add(new Part(bottom, top, left, right, source));
                if (source == OUTSIDE) { continue; }
                List<Point> low = chain(left.x(bottom), right.x(bottom), bottom, levels, epsilon);
                List<Point> high = chain(left.x(top), right.x(top), top, levels, epsilon);
                // Zip the two chains from left to right, always advancing the chain whose next vertex lies further left.
                int j = 0, k = 0;
                while (j + 1 < low.size() || k + 1 < high.size()) {
                    if (k + 1 >= high.size() || j + 1 < low.size() && low.get(j + 1).x() <= high.get(k + 1).x()) {
                        out.triangle(low.get(j), low.get(j + 1), high.get(k), source);
                        j++;
                    } else {
                        out.triangle(low.get(j), high.get(k + 1), high.get(k), source);
                        k++;
                    }
                }
            }
        }
        return new Sweep(split, rows, parts);
    }

    /**
     * The part of a sweep beside the middle of one of its segments, on its left as it runs or on its right; null where
     * nothing was swept there, outside every segment.
     */
    private static Part beside(Sweep sweep, Segment s, boolean left) {
        float ms = (s.ax() + s.bx()) / 2, mt = (s.ay() + s.by()) / 2;
        if (Math.abs(s.by() - s.ay()) <= 1e-3f * Math.abs(s.bx() - s.ax())) {
            // A level segment, or one so nearly level that its own rows hold only slivers, bounds the rows above and
            // below it.
            boolean above = left == s.bx() > s.ax();
            float y = above ? s.high() : s.low();
            for (Part part : sweep.parts()) {
                if ((above ? part.bottom() : part.top()) == y && ms >= part.left().x(y) && ms <= part.right().x(y)) {
                    return part;
                }
            }
            return null;
        }
        // Running up, its left lies towards lower x; of its rows, the one nearest its middle.
        boolean lower = left == s.by() > s.ay();
        Part nearest = null;
        float distance = Float.POSITIVE_INFINITY;
        for (Part part : sweep.parts()) {
            if ((lower ? part.right() : part.left()) != s) { continue; }
            float d = Math.max(0, Math.max(part.bottom() - mt, mt - part.top()));
            if (d < distance) {
                distance = d;
                nearest = part;
            }
        }
        return nearest;
    }

    private static List<Point> chain(float from, float to, float y, TreeMap<Float, List<Float>> levels, float epsilon) {
        List<Point> result = new ArrayList<>();
        result.add(new Point(from, y));
        for (float x : levels.getOrDefault(y, List.of())) {
            if (x > from + epsilon && x < to - epsilon) { result.add(new Point(x, y)); }
        }
        if (to - from > epsilon) { result.add(new Point(to, y)); }
        return result;
    }

    /** Every segment split where another crosses it, so no two cross inside a sweep row; coincident ones merge. */
    private static List<Segment> split(List<Segment> segments, float epsilon) {
        int n = segments.size();
        List<List<Float>> cuts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { cuts.add(new ArrayList<>(List.of(0f, 1f))); }
        for (int i = 0; i < n; i++) {
            Segment s = segments.get(i);
            float sMinX = Math.min(s.ax(), s.bx()), sMaxX = Math.max(s.ax(), s.bx());
            for (int j = i + 1; j < n; j++) {
                Segment o = segments.get(j);
                if (Math.max(o.ax(), o.bx()) < sMinX || Math.min(o.ax(), o.bx()) > sMaxX
                      || o.high() < s.low() || o.low() > s.high()) { continue; }
                float[] hit = intersection(s.ax(), s.ay(), s.bx(), s.by(), o.ax(), o.ay(), o.bx(), o.by());
                if (hit != null) {
                    cuts.get(i).add(hit[0]);
                    cuts.get(j).add(hit[1]);
                }
                // Overlapping outlines (a piece along the hex's own edge, or a cut along a face's edge) split at each
                // other's ends, even where, not quite parallel, they also cross at a shared end.
                overlap(s, o, cuts.get(i), epsilon);
                overlap(o, s, cuts.get(j), epsilon);
            }
        }
        List<Segment> result = new ArrayList<>();
        Set<List<float[]>> seen = new HashSet<>();
        Map<Long, float[]> snapped = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Segment s = segments.get(i);
            List<Float> ts = cuts.get(i);
            ts.sort(null);
            float[] previous = snap(snapped, s.ax(), s.ay(), epsilon);
            for (int k = 1; k < ts.size(); k++) {
                float t = ts.get(k);
                float[] next = t >= 1 ? snap(snapped, s.bx(), s.by(), epsilon)
                      : snap(snapped, s.ax() + (s.bx() - s.ax()) * t, s.ay() + (s.by() - s.ay()) * t, epsilon);
                if (Math.hypot(next[0] - previous[0], next[1] - previous[1]) > epsilon) {
                    // Snapped points are shared arrays, so identical segments compare equal.
                    boolean forward = previous[0] < next[0] || previous[0] == next[0] && previous[1] < next[1];
                    if (seen.add(forward ? List.of(previous, next) : List.of(next, previous))) {
                        result.add(new Segment(previous[0], previous[1], next[0], next[1]));
                    }
                    previous = next;
                }
            }
        }
        return result;
    }

    /** Where another segment's ends lie on this one, within the tolerance of its line, as cut parameters. */
    private static void overlap(Segment s, Segment o, List<Float> cuts, float epsilon) {
        float dx = s.bx() - s.ax(), dy = s.by() - s.ay(), length2 = dx * dx + dy * dy;
        if (length2 == 0) { return; }
        float length = (float) Math.sqrt(length2);
        for (float[] p : new float[][] { { o.ax(), o.ay() }, { o.bx(), o.by() } }) {
            float off = Math.abs((p[0] - s.ax()) * dy - (p[1] - s.ay()) * dx) / length;
            float t = ((p[0] - s.ax()) * dx + (p[1] - s.ay()) * dy) / length2;
            if (off <= epsilon && t > 0 && t < 1) { cuts.add(t); }
        }
    }

    /** Points within the tolerance of each other merge, so crossings computed from either segment coincide. */
    private static float[] snap(Map<Long, float[]> snapped, float x, float y, float epsilon) {
        long gx = Math.round(x / (epsilon * 4)), gy = Math.round(y / (epsilon * 4));
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                float[] existing = snapped.get((gx + dx) * 73856093L ^ (gy + dy) * 19349663L);
                if (existing != null && Math.hypot(existing[0] - x, existing[1] - y) <= epsilon * 4) { return existing; }
            }
        }
        float[] point = { x, y };
        snapped.put(gx * 73856093L ^ gy * 19349663L, point);
        return point;
    }

    /** A segment split where it crosses the sweep's rows, so its pieces meet the meshed vertices exactly. */
    private static List<float[]> rowPieces(Segment s, List<Float> rows) {
        List<Float> ts = new ArrayList<>(List.of(0f, 1f));
        if (s.ay() != s.by()) {
            int index = Collections.binarySearch(rows, s.low());
            for (int i = index >= 0 ? index : -index - 1; i < rows.size(); i++) {
                float y = rows.get(i);
                if (y >= s.high()) { break; }
                if (y > s.low()) { ts.add((y - s.ay()) / (s.by() - s.ay())); }
            }
        }
        ts.sort(null);
        List<float[]> result = new ArrayList<>();
        for (int k = 1; k < ts.size(); k++) {
            float t0 = ts.get(k - 1), t1 = ts.get(k);
            result.add(new float[] { s.ax() + (s.bx() - s.ax()) * t0, s.ay() + (s.by() - s.ay()) * t0,
                  s.ax() + (s.bx() - s.ax()) * t1, s.ay() + (s.by() - s.ay()) * t1 });
        }
        return result;
    }
}
