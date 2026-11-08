/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connected corner kits' outlines (models/buildings/README.md): the regular board hex, the corner pieces' sectors,
 * and the two footprints, Angled and Square, as floor polygons and walls per piece. Model units, x east, y north. The
 * art kits' Python generator (tools/buildings/kit_geometry.py) lays the same outlines.
 */
final class KitOutline {
    /** The regular board hex: corners R from the centre, edge midpoints H; a level's height. */
    static final double R = 41.5625, A = R / 2, H = 36, LEVEL = 18;
    static final String[] DIRECTIONS = { "n", "ne", "se", "s", "sw", "nw" };
    static final double[][] MIDPOINTS = { { 0, H }, { .75 * R, H / 2 }, { .75 * R, -H / 2 }, { 0, -H },
          { -.75 * R, -H / 2 }, { -.75 * R, H / 2 } };
    static final double[][] HEX = { { R, 0 }, { A, H }, { -A, H }, { -R, 0 }, { -A, -H }, { A, -H } };
    /**
     * The Angled footprint (the classic exits art): a linked hexside opens from corner to corner, an unlinked one
     * closes with two walls meeting on its axis NOSE from the centre, where the neighbouring arms' walls cross.
     */
    static final double NOSE = 2 * H / 3;
    /** How far a facade or band goes on past a cut or corner end, for the piece's cut or the wall's mitre to trim. */
    static final double EXTEND = 10;
    /** A roof's parapet stands this far inside its wall line, so a storey going on above stands in front of it. */
    static final double ROOF_INSET = .12;
    /** The corner pieces' cut lines: through the centre and the edge midpoints (the hex is regular only to a 32nd). */
    private static final double[][] AXES = { unit(MIDPOINTS[0]), unit(MIDPOINTS[1]), unit(MIDPOINTS[2]) };

    private KitOutline() { }

    /**
     * How a wall's end meets the next: on a corner piece's cut line (extended, the cut mitres it), on a cut line within a
     * whole-hex piece (goes straight on), at a turn to another wall of the piece ({@code keeps} hold its mitres), at a
     * hexside the building continues across, or between a rounded corner's arcs.
     */
    enum End { CUT, JOINT, CORNER, OPEN, ROUND }

    /**
     * A wall from p to q with the building on its left. {@code front}: on a whole-hex piece's front (its outline's first
     * edge), where an entrance belongs.
     */
    record Wall(double[] p, double[] q, End start, End end, List<GeneratedMesh.Keep> keeps, boolean front) {
        double length() { return Math.hypot(q[0] - p[0], q[1] - p[1]); }
    }

    /** A kit piece: its tags ({@code -link-n-end-ne}...), its corner direction (null for a whole hex), floors and walls. */
    record Piece(String tags, Integer direction, List<List<double[]>> polygons, List<Wall> walls) { }

    /**
     * How a hex's wall ending at its corner c (between its sides c and c + 1; A across side c, B across c + 1) meets the
     * wall of another hex that goes on from there, in an inside corner: its own wall square to side c (S0, a corner or
     * band piece linked across c alone) or to side c + 1 (S1), or the wall closing its link across side c (C0) or c + 1
     * (C1) above a shorter neighbour ({@link #closed}); then the other wall. Its end is carried on past the corner
     * and cut on the mitre ({@link #junctionWall}, {@link #partnerNormal}); ConnectedKit finds them from the hexes
     * around the corner. A hex's own walls meet within its pieces.
     */
    enum Junction {
        /** B's wall square to side AB (a 60-degree notch); A's closing wall along AB. */
        S0_NOTCH, S0_CAP,
        /** A's wall square to side AB; B's closing wall along AB. */
        S1_NOTCH, S1_CAP,
        /** B's closing wall along AB; B's wall square to side c + 1. */
        C0_CAP, C0_SQUARE,
        /** A's closing wall along AB; A's wall square to side c. */
        C1_CAP, C1_SQUARE
    }

    /** The wall whose end at corner {@code c} {@code junction} carries on, as its piece lays it. */
    static Wall junctionWall(Junction junction, int c) {
        double[] corner = hexCorner(c);
        return switch (junction.name().substring(0, 2)) {
            case "S0" -> new Wall(scaled(MIDPOINTS[(c + 1) % 6], NOSE / H), corner, End.CUT, End.OPEN, List.of(), false);
            case "S1" -> new Wall(corner, scaled(MIDPOINTS[c % 6], NOSE / H), End.OPEN, End.CUT, List.of(), false);
            case "C0" -> new Wall(corner, hexCorner(c + 5), End.OPEN, End.OPEN, List.of(), false);
            default -> new Wall(hexCorner(c + 1), corner, End.OPEN, End.OPEN, List.of(), false);
        };
    }

    /** The outward normal of the wall that {@code junction}'s end meets at corner {@code c}. */
    static double[] partnerNormal(Junction junction, int c) {
        double[] corner = hexCorner(c);
        double[] e0 = unit(new double[] { MIDPOINTS[c % 6][0] - corner[0], MIDPOINTS[c % 6][1] - corner[1] });
        double[] e1 = unit(new double[] { MIDPOINTS[(c + 1) % 6][0] - corner[0], MIDPOINTS[(c + 1) % 6][1] - corner[1] });
        // Along the hexside between A and B, away from this hex.
        double[] ab = unit(new double[] { -e0[0] - e1[0], -e0[1] - e1[1] });
        return switch (junction) {
            // A wall square to a side faces away from that side's middle; a cap faces the hex across it.
            case S0_NOTCH, S1_NOTCH -> new double[] { -ab[0], -ab[1] };
            case S0_CAP, C1_CAP -> across(ab, e1);
            case S1_CAP, C0_CAP -> across(ab, e0);
            case C1_SQUARE -> new double[] { -e0[0], -e0[1] };
            case C0_SQUARE -> new double[] { -e1[0], -e1[1] };
        };
    }

    /** The unit normal of a hexside along {@code side} on {@code toward}'s side. */
    private static double[] across(double[] side, double[] toward) {
        double[] normal = { -side[1], side[0] };
        return normal[0] * toward[0] + normal[1] * toward[1] >= 0 ? normal : new double[] { -normal[0], -normal[1] };
    }

    private static double[] scaled(double[] point, double by) { return new double[] { point[0] * by, point[1] * by }; }

    static double cross(double[] a, double[] b) { return a[0] * b[1] - a[1] * b[0]; }

    /** The outward normal of a wall from p to q: its right. */
    static double[] normal(double[] p, double[] q) {
        double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
        return new double[] { (q[1] - p[1]) / length, -(q[0] - p[0]) / length };
    }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t };
    }

    private static double[] unit(double[] v) {
        double length = Math.hypot(v[0], v[1]);
        return new double[] { v[0] / length, v[1] / length };
    }

    /** A point turned clockwise by {@code direction} sixths about the hex centre. */
    static double[] turnPoint(double[] point, int direction) {
        double angle = -Math.toRadians(60 * direction), c = Math.cos(angle), s = Math.sin(angle);
        return new double[] { point[0] * c - point[1] * s, point[0] * s + point[1] * c };
    }

    static boolean close(double[] a, double[] b) { return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6; }

    private static boolean onAxis(double[] point) {
        if (Math.hypot(point[0], point[1]) <= 1e-6) { return false; }
        for (double[] axis : AXES) {
            if (Math.abs(cross(axis, point)) < 1e-6) { return true; }
        }
        return false;
    }

    /** The hex corner between {@code direction} and the next direction clockwise. */
    static double[] hexCorner(int direction) { return HEX[Math.floorMod(1 - direction, 6)]; }

    /** The corner piece between a direction and the next one clockwise: the half planes of its two cut lines. */
    static List<GeneratedMesh.Keep> sector(int direction) { return sector(direction, 0); }

    /**
     * How far a generated corner piece's own facade goes on past its cut lines, under the next piece's: the turned
     * pieces' shared edge parts by float rounding, which shows as a line of pixels where it falls on their centres.
     */
    static final double SEAM_OVERLAP = .02;

    /** {@link #sector(int)} with its cut lines {@code overlap} further out. */
    static List<GeneratedMesh.Keep> sector(int direction, double overlap) {
        double[] a = MIDPOINTS[direction], b = MIDPOINTS[(direction + 1) % 6];
        double past = overlap * H;
        return List.of((x, y) -> past - (a[0] * y - a[1] * x), (x, y) -> past - (x * b[1] - y * b[0]));
    }

    static boolean inSector(int direction, double[] point) {
        return sector(direction).stream().allMatch(keep -> keep.at(point[0], point[1]) >= 0);
    }

    /** How far a point is inside the hex: a point test only. As a clip it cuts wrong where the nearest side changes. */
    static double insideHex(double x, double y) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 6; i++) {
            double[] a = HEX[i], b = HEX[(i + 1) % 6];
            best = Math.min(best, (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]));
        }
        return best;
    }

    /**
     * The hex's six sides as half planes: unlike {@link #insideHex}, each linear, so a clip cuts exactly where a side
     * crosses even when a vertex lies on another side.
     */
    static List<GeneratedMesh.Keep> hexSides() {
        List<GeneratedMesh.Keep> result = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            double[] a = HEX[i], b = HEX[(i + 1) % 6];
            result.add((x, y) -> (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]));
        }
        return result;
    }

    /** The mitre at a corner between walls with outward normals n1 and n2: the half plane on {@code inside}'s side. */
    static GeneratedMesh.Keep mitre(double[] corner, double[] n1, double[] n2, double[] inside) {
        double[] b = { n1[0] + n2[0], n1[1] + n2[1] };
        double sign = cross(b, new double[] { inside[0] - corner[0], inside[1] - corner[1] }) >= 0 ? 1 : -1;
        return (x, y) -> sign * cross(b, new double[] { x - corner[0], y - corner[1] });
    }

    /** Segment p-q cut where it crosses the cut lines: its points in order. */
    private static List<double[]> split(double[] p, double[] q) {
        double[] d = { q[0] - p[0], q[1] - p[1] };
        List<Double> ts = new ArrayList<>();
        for (double[] axis : AXES) {
            double denominator = cross(axis, d);
            if (Math.abs(denominator) <= 1e-12) { continue; }
            double t = -cross(axis, p) / denominator;
            if (t > 1e-6 && t < 1 - 1e-6) { ts.add(t); }
        }
        ts.sort(null);
        List<double[]> result = new ArrayList<>();
        result.add(p);
        for (double t : ts) { result.add(lerp(p, q, t)); }
        result.add(q);
        return result;
    }

    /** The part of segment p-q within the hex, or null. */
    private static double[][] segmentInHex(double[] p, double[] q) {
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 6; i++) {
            double[] a = HEX[i], b = HEX[(i + 1) % 6];
            double fp = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
            double fq = (b[0] - a[0]) * (q[1] - a[1]) - (b[1] - a[1]) * (q[0] - a[0]);
            if (fp < -1e-9 && fq < -1e-9) { return null; }
            if (fp < -1e-9) {
                t0 = Math.max(t0, fp / (fp - fq));
            } else if (fq < -1e-9) {
                t1 = Math.min(t1, fp / (fp - fq));
            }
        }
        return t1 - t0 > 1e-6 ? new double[][] { lerp(p, q, t0), lerp(p, q, t1) } : null;
    }

    /**
     * The Angled corner piece between a direction and the next clockwise, linked across each or not: its floor polygons
     * and walls on the hex's own points (an unlinked side's walls meet two thirds of the way to its midpoint).
     */
    static Piece angled(String tags, int direction, boolean first, boolean second) {
        int following = (direction + 1) % 6;
        double[] mid0 = MIDPOINTS[direction], mid1 = MIDPOINTS[following], corner = hexCorner(direction);
        double[] nose0 = { mid0[0] * 2 / 3, mid0[1] * 2 / 3 }, nose1 = { mid1[0] * 2 / 3, mid1[1] * 2 / 3 };
        double[] centre = { 0, 0 };
        if (first && second) { return new Piece(tags, direction, List.of(List.of(centre, mid1, corner, mid0)), List.of()); }
        if (first) {
            return new Piece(tags, direction, List.of(List.of(centre, nose1, corner, mid0)),
                  List.of(new Wall(nose1, corner, End.CUT, End.OPEN, List.of(), false)));
        }
        if (second) {
            return new Piece(tags, direction, List.of(List.of(centre, mid1, corner, nose0)),
                  List.of(new Wall(corner, nose0, End.OPEN, End.CUT, List.of(), false)));
        }
        return new Piece(tags, direction, List.of(List.of(centre, nose1, nose0)),
              List.of(new Wall(nose1, nose0, End.CUT, End.CUT, List.of(), false)));
    }

    /** A Square cell: its bounds and whether a cell continues each edge (south, east, north, west). */
    private record Cell(double x0, double x1, double y0, double y1, boolean[] continues) {
        List<double[]> corners() { return List.of(new double[] { x0, y0 }, new double[] { x1, y0 }, new double[] { x1, y1 },
              new double[] { x0, y1 }); }
    }

    /** The Square footprint's cells over the hex for a connection mask. */
    private static List<Cell> cells(int mask) {
        boolean n = (mask & 1) != 0, ne = (mask & 2) != 0, se = (mask & 4) != 0, s = (mask & 8) != 0,
              sw = (mask & 16) != 0, nw = (mask & 32) != 0;
        List<Cell> result = new ArrayList<>();
        result.add(new Cell(-A, A, 0, H, new boolean[] { true, ne, n, nw }));
        result.add(new Cell(-A, A, -H, 0, new boolean[] { s, se, true, sw }));
        if (ne) { result.add(new Cell(A, 2 * A, 0, H, new boolean[] { se, true, true, true })); }
        if (se) { result.add(new Cell(A, 2 * A, -H, 0, new boolean[] { true, true, ne, true })); }
        if (sw) { result.add(new Cell(-2 * A, -A, -H, 0, new boolean[] { true, true, nw, true })); }
        if (nw) { result.add(new Cell(-2 * A, -A, 0, H, new boolean[] { sw, true, true, true })); }
        return result;
    }

    /**
     * The Square footprint's walls for a hex's connections: each cell edge no cell continues, within the hex, collinear
     * edges joined, cut on the cut lines.
     */
    private static List<Wall> squareWalls(int mask) {
        List<double[][]> runs = new ArrayList<>();
        for (Cell cell : cells(mask)) {
            var outline = cell.corners();
            for (int edge = 0; edge < 4; edge++) {
                if (cell.continues()[edge]) { continue; }
                var inside = segmentInHex(outline.get(edge), outline.get((edge + 1) % 4));
                if (inside != null) { runs.add(inside); }
            }
        }
        boolean joined = true;
        while (joined) {
            joined = false;
            search:
            for (int i = 0; i < runs.size(); i++) {
                for (int j = 0; j < runs.size(); j++) {
                    if (i == j) { continue; }
                    double[][] first = runs.get(i), second = runs.get(j);
                    double[] n1 = normal(first[0], first[1]), n2 = normal(second[0], second[1]);
                    if (close(first[1], second[0]) && Math.max(Math.abs(n1[0] - n2[0]), Math.abs(n1[1] - n2[1])) < 1e-9) {
                        runs.set(i, new double[][] { first[0], second[1] });
                        runs.remove(j);
                        joined = true;
                        break search;
                    }
                }
            }
        }
        List<double[][]> segments = new ArrayList<>();
        for (double[][] run : runs) {
            var points = split(run[0], run[1]);
            for (int k = 0; k < points.size() - 1; k++) { segments.add(new double[][] { points.get(k), points.get(k + 1) }); }
        }
        List<Wall> walls = new ArrayList<>();
        for (double[][] segment : segments) {
            double[] p = segment[0], q = segment[1];
            End[] kinds = new End[2];
            List<GeneratedMesh.Keep> keeps = new ArrayList<>();
            double[][][] ends = { { p, q }, { q, p } };
            for (int k = 0; k < 2; k++) {
                double[] point = ends[k][0], inside = ends[k][1];
                double[][] other = null;
                for (double[][] candidate : segments) {
                    if (candidate != segment && (close(candidate[0], point) || close(candidate[1], point))) {
                        other = candidate;
                        break;
                    }
                }
                if (onAxis(point)) {
                    kinds[k] = End.CUT;
                } else if (other != null) {
                    kinds[k] = End.CORNER;
                    keeps.add(mitre(point, normal(p, q), normal(other[0], other[1]), inside));
                } else {
                    kinds[k] = End.OPEN;
                }
            }
            walls.add(new Wall(p, q, kinds[0], kinds[1], keeps, false));
        }
        return walls;
    }

    /** The Square corner piece between a direction and the next clockwise: the cells within it and its walls. */
    static Piece square(String tags, int direction, boolean first, boolean second) {
        int mask = (first ? 1 << direction : 0) | (second ? 1 << (direction + 1) % 6 : 0);
        List<List<double[]>> polygons = new ArrayList<>();
        List<GeneratedMesh.Keep> keeps = new ArrayList<>();
        keeps.addAll(hexSides());
        keeps.addAll(sector(direction));
        for (Cell cell : cells(mask)) {
            var points = clipped(cell.corners(), keeps);
            if (!points.isEmpty()) { polygons.add(points); }
        }
        List<Wall> walls = squareWalls(mask).stream().filter(wall -> inSector(direction, lerp(wall.p(), wall.q(), .5))).toList();
        return new Piece(tags, direction, polygons, walls);
    }

    /**
     * The walls round a convex outline (counter-clockwise) but on its open edges (edge i runs from point i): joints on
     * the cut lines, mitred at its corners.
     */
    static List<Wall> outlineWalls(List<double[]> points, Set<Integer> openEdges) {
        int count = points.size();
        List<Wall> walls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (openEdges.contains(i)) { continue; }
            double[] p = points.get(i), q = points.get((i + 1) % count), n = normal(p, q);
            int before = Math.floorMod(i - 1, count), after = (i + 1) % count;
            var pieces = split(p, q);
            for (int k = 0; k < pieces.size() - 1; k++) {
                double[] a = pieces.get(k), b = pieces.get(k + 1);
                End start = End.JOINT, end = End.JOINT;
                List<GeneratedMesh.Keep> keeps = new ArrayList<>();
                if (k == 0) {
                    start = openEdges.contains(before) ? End.OPEN : End.CORNER;
                    if (start == End.CORNER) { keeps.add(mitre(p, normal(points.get(before), p), n, b)); }
                }
                if (k == pieces.size() - 2) {
                    end = openEdges.contains(after) ? End.OPEN : End.CORNER;
                    if (end == End.CORNER) { keeps.add(mitre(q, n, normal(q, points.get((i + 2) % count)), a)); }
                }
                walls.add(new Wall(a, b, start, end, keeps, i == 0));
            }
        }
        return walls;
    }

    /** A piece's links, a direction mask, from its tags ({@code -link-n}...). */
    static int links(Piece piece) {
        int links = 0;
        for (int direction = 0; direction < 6; direction++) {
            if ((piece.tags() + "-").contains("-link-" + DIRECTIONS[direction] + "-")) { links |= 1 << direction; }
        }
        return links;
    }

    /**
     * Where a module (one hex's storey) meets its neighbours' across {@code links}, as walls with the module on their
     * left, clipped to the hex where laid. Angled: each linked hexside. Square: a north or south link's hexside; a
     * diagonal link's cell is a module of its own between two columns, met along both column edges (one wall for each
     * side); linked both ways on a side, its two cells meet on the hex's axis.
     */
    static List<Wall> joints(boolean square, int links) {
        List<Wall> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            if ((links & 1 << direction) == 0) { continue; }
            if (!square || direction == 0 || direction == 3) {
                result.add(joint(hexCorner(direction), hexCorner((direction + 5) % 6)));
            } else {
                // The cell's two column edges, this hex's and its neighbour's, each from the hex's axis toward the
                // hexside: the neighbour's tip of the cell lies in this hex, as this hex's in the neighbour's.
                double y = direction == 1 || direction == 5 ? H : -H;
                for (double x : direction < 3 ? new double[] { A, R } : new double[] { -A, -R }) {
                    bothSides(result, new double[] { x, 0 }, new double[] { x, y });
                }
            }
        }
        for (int east = 0; square && east < 2; east++) {
            int north = east == 0 ? 5 : 1, south = east == 0 ? 4 : 2;
            if ((links & 1 << north) != 0 && (links & 1 << south) != 0) {
                bothSides(result, new double[] { east == 0 ? -A : A, 0 }, new double[] { east == 0 ? -R : R, 0 });
            }
        }
        return result;
    }

    private static Wall joint(double[] p, double[] q) { return new Wall(p, q, End.CORNER, End.CORNER, List.of(), false); }

    private static void bothSides(List<Wall> result, double[] a, double[] b) {
        result.add(joint(a, b));
        result.add(joint(b, a));
    }

    /**
     * Close exposed links without changing the footprint: a roof's above shorter neighbours (its deck and rim share the
     * new mitred outline), a storey's beside them, each link's wall along the whole hexside, mitred with the piece's
     * walls it meets.
     */
    static Piece closed(Piece piece, int closed) {
        List<Wall> walls = new ArrayList<>(piece.walls());
        for (int direction = 0; direction < 6; direction++) {
            if ((closed & 1 << direction) == 0) { continue; }
            walls.add(new Wall(hexCorner(direction), hexCorner((direction + 5) % 6),
                  End.OPEN, End.OPEN, List.of(), false));
        }
        List<Wall> joined = new ArrayList<>();
        for (Wall wall : walls) {
            End start = wall.start(), end = wall.end();
            List<GeneratedMesh.Keep> keeps = new ArrayList<>(wall.keeps());
            double[] n = normal(wall.p(), wall.q());
            for (Wall other : walls) {
                if (wall == other) { continue; }
                if (start == End.OPEN && close(wall.p(), other.q())) {
                    start = End.CORNER;
                    keeps.add(mitre(wall.p(), n, normal(other.p(), other.q()), wall.q()));
                }
                if (end == End.OPEN && close(wall.q(), other.p())) {
                    end = End.CORNER;
                    keeps.add(mitre(wall.q(), n, normal(other.p(), other.q()), wall.p()));
                }
            }
            joined.add(new Wall(wall.p(), wall.q(), start, end, keeps, wall.front()));
        }
        return new Piece(piece.tags(), piece.direction(), piece.polygons(), joined);
    }

    /**
     * A floor polygon kept {@code by} inside the piece's walls, as convex parts: without the band along each wall's
     * inside. The band runs on {@code by} past a corner end, so that at a concave corner the bands meet where the
     * outline's inward offset turns; past any other end the wall goes on (the next piece's, a neighbour's) or rounds.
     */
    static List<List<double[]>> inset(List<double[]> polygon, List<Wall> walls, double by) {
        List<List<double[]>> parts = List.of(polygon);
        for (Wall wall : walls) {
            double[] p = wall.p(), n = normal(wall.p(), wall.q()), u = direction(wall);
            double length = wall.length();
            GeneratedMesh.Keep along = (x, y) -> (x - p[0]) * u[0] + (y - p[1]) * u[1];
            GeneratedMesh.Keep inside = (x, y) -> -((x - p[0]) * n[0] + (y - p[1]) * n[1]);
            List<GeneratedMesh.Keep> span = new ArrayList<>();
            // Before the band, past it, deeper inside than it, and beside it outside the wall.
            List<List<GeneratedMesh.Keep>> outside = new ArrayList<>();
            if (wall.start() == End.CORNER) {
                span.add((x, y) -> along.at(x, y) + by);
                outside.add(List.of((x, y) -> -along.at(x, y) - by));
            }
            if (wall.end() == End.CORNER) {
                span.add((x, y) -> length + by - along.at(x, y));
                outside.add(List.of((x, y) -> along.at(x, y) - length - by));
            }
            outside.add(with(span, (x, y) -> inside.at(x, y) - by));
            outside.add(with(span, (x, y) -> -inside.at(x, y)));
            var band = with(with(span, inside), (x, y) -> by - inside.at(x, y));
            List<List<double[]>> next = new ArrayList<>();
            for (var part : parts) {
                if (area(clipped(part, band)) < 1e-6) {
                    next.add(part);
                    continue;
                }
                for (var keeps : outside) {
                    var kept = clipped(part, keeps);
                    if (area(kept) >= 1e-6) { next.add(kept); }
                }
            }
            parts = next;
        }
        return parts;
    }

    private static List<GeneratedMesh.Keep> with(List<GeneratedMesh.Keep> keeps, GeneratedMesh.Keep keep) {
        List<GeneratedMesh.Keep> result = new ArrayList<>(keeps);
        result.add(keep);
        return result;
    }

    private static double area(List<double[]> polygon) {
        double twice = 0;
        for (int i = 0; i < polygon.size(); i++) { twice += cross(polygon.get(i), polygon.get((i + 1) % polygon.size())); }
        return Math.abs(twice) / 2;
    }

    /** Sutherland-Hodgman on plain points: the part of {@code points} where every keep holds. */
    static List<double[]> clipped(List<double[]> points, List<GeneratedMesh.Keep> keeps) {
        List<double[]> result = points;
        for (var keep : keeps) {
            List<double[]> next = new ArrayList<>(result.size() + 1);
            for (int i = 0; i < result.size(); i++) {
                double[] a = result.get(i), b = result.get((i + 1) % result.size());
                double da = keep.at(a[0], a[1]), db = keep.at(b[0], b[1]);
                if (da >= 0) { next.add(a); }
                if (da < 0 != db < 0 && da != 0 && db != 0) { next.add(lerp(a, b, da / (da - db))); }
            }
            if (next.size() < 3) { return List.of(); }
            result = next;
        }
        return result;
    }

    /**
     * A piece's walls with every convex corner within it rounded to {@code radius}: both walls stop short and an arc of
     * {@code segments} walls (ROUND ends) turns between them; the floor polygon holding the corner is cut to the arc.
     */
    static Piece rounded(Piece piece, double radius, int segments) {
        if (radius <= 0) { return piece; }
        List<Wall> result = new ArrayList<>(piece.walls());
        List<List<double[]>> polygons = new ArrayList<>(piece.polygons());
        Map<Wall, Wall> current = new IdentityHashMap<>();
        for (Wall wall : piece.walls()) { current.put(wall, wall); }
        for (Wall first : piece.walls()) {
            if (first.end() != End.CORNER) { continue; }
            Wall second = piece.walls().stream().filter(w -> close(w.p(), first.q())).findFirst().orElse(null);
            if (second == null) { continue; }
            double[] u1 = direction(first), u2 = direction(second);
            double turn = cross(u1, u2);
            if (turn <= 1e-6) { continue; }
            double angle = Math.atan2(turn, u1[0] * u2[0] + u1[1] * u2[1]);
            Wall firstNow = current.get(first), secondNow = current.get(second);
            // A compact outline can put a joint closer than the requested corner radius. Do not reverse its wall.
            double reach = Math.min(radius * Math.tan(angle / 2), .99 * Math.min(firstNow.length(), secondNow.length()));
            double cornerRadius = reach / Math.tan(angle / 2);
            double[] corner = first.q();
            double[] a = { corner[0] - u1[0] * reach, corner[1] - u1[1] * reach };
            double[] b = { corner[0] + u2[0] * reach, corner[1] + u2[1] * reach };
            double[] centre = { a[0] - u1[1] * cornerRadius, a[1] + u1[0] * cornerRadius };
            double start = Math.atan2(a[1] - centre[1], a[0] - centre[0]);
            List<double[]> arc = new ArrayList<>();
            for (int k = 0; k <= segments; k++) {
                double at = start + angle * k / segments;
                arc.add(new double[] { centre[0] + cornerRadius * Math.cos(at), centre[1] + cornerRadius * Math.sin(at) });
            }
            arc.set(0, a);
            arc.set(segments, b);
            Wall shortened = new Wall(firstNow.p(), a, firstNow.start(), End.ROUND, List.of(), firstNow.front());
            result.set(result.indexOf(firstNow), shortened);
            current.put(first, shortened);
            secondNow = current.get(second);
            Wall started = new Wall(b, secondNow.q(), End.ROUND, secondNow.end(), List.of(), secondNow.front());
            result.set(result.indexOf(secondNow), started);
            current.put(second, started);
            List<GeneratedMesh.Keep> keeps = new ArrayList<>();
            for (int k = 0; k < segments; k++) {
                result.add(new Wall(arc.get(k), arc.get(k + 1), End.ROUND, End.ROUND, List.of(), false));
                double[] p = arc.get(k), q = arc.get(k + 1);
                keeps.add((x, y) -> cross(new double[] { q[0] - p[0], q[1] - p[1] }, new double[] { x - p[0], y - p[1] }));
            }
            for (int i = 0; i < polygons.size(); i++) {
                if (polygons.get(i).stream().anyMatch(point -> close(point, corner))) {
                    polygons.set(i, clipped(polygons.get(i), keeps));
                }
            }
        }
        return new Piece(piece.tags(), piece.direction(), polygons, result);
    }

    static double[] direction(Wall wall) {
        double length = wall.length();
        return new double[] { (wall.q()[0] - wall.p()[0]) / length, (wall.q()[1] - wall.p()[1]) / length };
    }

    /**
     * A wall's ends for a band along it (skirt, parapet): each end point and its outward direction there, joined with
     * the next round wall's (scaled so the bands meet), moved on past a cut or corner end, which the piece's cut or the
     * wall's mitre trims.
     */
    static double[][] offsets(Wall wall, List<Wall> walls) {
        double[] n = normal(wall.p(), wall.q()), u = direction(wall);
        double[][] result = new double[4][];
        for (int k = 0; k < 2; k++) {
            double[] point = k == 0 ? wall.p() : wall.q();
            End kind = k == 0 ? wall.start() : wall.end();
            double sign = k == 0 ? -1 : 1;
            Wall other = null;
            if (kind == End.ROUND) {
                for (var candidate : walls) {
                    double[] meets = k == 0 ? candidate.q() : candidate.p();
                    if (candidate != wall && Math.hypot(meets[0] - point[0], meets[1] - point[1]) < 1e-6) {
                        other = candidate;
                        break;
                    }
                }
            }
            if (other != null) {
                double[] m = normal(other.p(), other.q());
                double[] half = { n[0] + m[0], n[1] + m[1] };
                double scale = 2 / (half[0] * half[0] + half[1] * half[1]);
                result[2 * k] = point;
                result[2 * k + 1] = new double[] { half[0] * scale, half[1] * scale };
            } else if (kind == End.CUT || kind == End.CORNER) {
                result[2 * k] = new double[] { point[0] + u[0] * sign * EXTEND, point[1] + u[1] * sign * EXTEND };
                result[2 * k + 1] = n;
            } else {
                result[2 * k] = point;
                result[2 * k + 1] = n;
            }
        }
        return result;
    }

    /** Solid wall backing shared by bay and habitat kits: inward thickness, mitred corners and closed exposed ends. */
    static void wallBody(GeneratedMesh result, Wall wall, List<Wall> walls, double bottom, double top, double inset, double thickness) {
        double[][] ends = offsets(wall, walls);
        double[] a = ends[0], b = ends[2], n = ends[1], m = ends[3];
        double front = inset + .02, back = inset + thickness;
        List<double[]> strip = List.of(new double[] { a[0] - n[0] * front, a[1] - n[1] * front },
              new double[] { b[0] - m[0] * front, b[1] - m[1] * front },
              new double[] { b[0] - m[0] * back, b[1] - m[1] * back },
              new double[] { a[0] - n[0] * back, a[1] - n[1] * back });
        GeneratedMesh body = new GeneratedMesh();
        body.slab(GeneratedMesh.BUILDING_BODY, strip, bottom, top, true);
        result.add(body.clipped(wall.keeps()));
    }

    /**
     * Every piece a kit of one footprint authors: the corner pieces in their four link states, a compact isolated
     * building, and for Angled its one-link whole-hex pieces (a hexside wide band
     * ending {@code end} short of the far hexside, its front). An Angled kit {@code turned} is symmetric: its north and
     * north-east corner pieces and a band linked north, which the runtime turns to every corner and link. A kit whose
     * pieces hold something placed in the hex (roof fittings) is not turned: its pieces stand in all six corners, its
     * bands in all six directions, as the Square cells' always do. Link caps close exposed hexsides above shorter
     * neighbours without changing the footprint; they contain walls only, since the connected pieces supply the slabs.
     */
    static List<Piece> footprint(boolean square, double end, boolean turned) {
        return footprint(square, end, turned, 1);
    }

    static List<Piece> footprint(boolean square, double end, boolean turned, float scale) {
        int directions = !square && turned ? 1 : 6;
        List<Piece> result = new ArrayList<>();
        for (int direction = 0; direction < directions; direction++) {
            int following = (direction + 1) % 6;
            for (int state = 0; state < 4; state++) {
                boolean first = (state & 1) != 0, second = (state & 2) != 0;
                String tags = "-" + (first ? "link" : "end") + "-" + DIRECTIONS[direction]
                      + "-" + (second ? "link" : "end") + "-" + DIRECTIONS[following];
                result.add(square ? square(tags, direction, first, second) : angled(tags, direction, first, second));
            }
        }
        // No neighbours need a joining band: leave room for facade details and sidewalks inside this hex.
        List<double[]> isolated;
        if (square) {
            isolated = List.of(new double[] { -A, -A }, new double[] { A, -A }, new double[] { A, A }, new double[] { -A, A });
        } else {
            // Keep the facade and its curb inside the hex, with the entrance on the south face.
            double inset = (H - 5) / H;
            isolated = List.of(new double[] { -A * inset, -H * inset }, new double[] { A * inset, -H * inset },
                  new double[] { R * inset, 0 }, new double[] { A * inset, H * inset },
                  new double[] { -A * inset, H * inset }, new double[] { -R * inset, 0 });
        }
        StringBuilder alone = new StringBuilder();
        isolated = isolated.stream().map(p -> new double[] { p[0] * scale, p[1] * scale }).toList();
        for (String direction : DIRECTIONS) { alone.append("-end-").append(direction); }
        result.add(new Piece(alone.toString(), null, List.of(isolated), outlineWalls(isolated, Set.of())));
        if (!square) {
            double[][] band = { { -A, -H + end }, { A, -H + end } };
            for (int direction = 0; direction < directions; direction++) {
                List<double[]> outline = List.of(turnPoint(band[0], direction), turnPoint(band[1], direction),
                      hexCorner(direction), hexCorner((direction + 5) % 6));
                StringBuilder tags = new StringBuilder();
                for (int d = 0; d < 6; d++) { tags.append(d == direction ? "-link-" : "-end-").append(DIRECTIONS[d]); }
                result.add(new Piece(tags.toString(), null, List.of(outline), outlineWalls(outline, Set.of(2))));
            }
        }
        for (int direction = 0; direction < directions; direction++) {
            var wall = new Wall(hexCorner(direction), hexCorner((direction + 5) % 6), End.OPEN, End.OPEN, List.of(), false);
            result.add(new Piece("-link-" + DIRECTIONS[direction] + "-cap", null, List.of(), List.of(wall)));
        }
        return result;
    }
}
