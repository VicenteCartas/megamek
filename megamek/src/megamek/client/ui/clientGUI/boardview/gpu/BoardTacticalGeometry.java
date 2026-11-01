/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/** Tessellation and terrain clipping only; all tactical decisions are supplied by the client state. */
final class BoardTacticalGeometry {
    private static final float WALL_CLEARANCE = 0.6f;
    /** Shared clearance in world units for flat hex annotations, independent of animated unit bands. */
    static final float HEX_PLANE_CLEARANCE = .65f;

    record Triangle(Vector3 a, Vector3 b, Vector3 c, int argb) { }
    private record PlaneFill(Coords coords, int argb, BoardTactical.Playback playback) { }

    /** A shared hex edge descending from one annotation plane to the neighboring plane. */
    record PlaneStep(Vector3 a, Vector3 b, float bottom, Coords neighbor) {
        void connect(float start, float end, int argb, Consumer<Triangle> destination) {
            Vector3 topA = new Vector3(a).lerp(b, start), topB = new Vector3(a).lerp(b, end);
            Vector3 bottomA = new Vector3(topA.x, topA.y, bottom), bottomB = new Vector3(topB.x, topB.y, bottom);
            destination.accept(new Triangle(topA, bottomA, bottomB, argb));
            destination.accept(new Triangle(topA, bottomB, topB, argb));
        }
    }
    /** Only the finished triangles are retained; none of the terrain builder's scene or shoreline caches. */
    record Surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes,
          List<BoardSurface.Face> faces, List<BoardSurface.Face> water, List<BoardSurface.Face> walls,
          List<BoardSurface.Side> waterfalls, List<GpuFrozenWaterfall.Geometry> frozenFalls, float highestTop,
          BoardVegetation.Ground vegetation) {
        Surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes, List<BoardSurface.Face> faces,
              List<BoardSurface.Face> water, List<BoardSurface.Face> walls, List<BoardSurface.Side> waterfalls) {
            this(top, slopes, faces, water, walls, waterfalls, List.of(), highest(top), null);
        }

        Surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes, List<BoardSurface.Face> faces,
              List<BoardSurface.Face> water, List<BoardSurface.Face> walls, List<BoardSurface.Side> waterfalls,
              List<GpuFrozenWaterfall.Geometry> frozenFalls, float highestTop) {
            this(top, slopes, faces, water, walls, waterfalls, frozenFalls, highestTop, null);
        }

        /** Derived once with this immutable finished-surface snapshot; raised border endpoints reuse it. */
        private static float highest(List<BoardSurface.Face> faces) {
            float z = Float.NEGATIVE_INFINITY;
            for (BoardSurface.Face face : faces) {
                z = Math.max(z, Math.max(face.a().z, Math.max(face.b().z, face.c().z)));
            }
            return z;
        }

        static Surface of(BoardSurface surface, BoardScene scene, float floor) {
            var sources = BoardVegetation.prepare(scene, List.of(surface.tile), surface.lod(), floor,
                  Map.of(surface.tile.coords(), surface));
            return of(surface, scene, floor, sources);
        }

        /**
         * The finished shape alone, for readers that take no ground cover support (vegetation), such as picking and
         * draping. Its walls borrow only the neighbours' edges, which their plain tops share with their sculpted ones,
         * so they match {@link #of}'s; preparing that support built up to six sculpted neighbours and their walls.
         */
        static Surface shape(BoardSurface surface, BoardScene scene, float floor) {
            return of(surface, scene, floor, Map.of(surface.tile.coords(), surface), false);
        }

        static Surface of(BoardSurface surface, BoardScene scene, float floor, Map<Coords, BoardSurface> sources) {
            return of(surface, scene, floor, sources, true);
        }

        private static Surface of(BoardSurface surface, BoardScene scene, float floor,
              Map<Coords, BoardSurface> sources, boolean cover) {
            List<BoardSurface.Face> top = new ArrayList<>();
            for (BoardSurface.Face face : surface.faces) {
                if (surface.tile.frozen() && surface.tile.water() ? face.finish() == BoardSurface.Finish.ICE
                      : face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE) {
                    top.add(face);
                }
            }
            if (!surface.tile.frozen()) { top.addAll(surface.waterFaces); }
            List<BoardSurface.Face> walls = new Faces(surface.walls(scene, floor, sources));
            return new Surface(List.copyOf(top), BoardGeometry.tuning().stepsBetweenTops() ? lying(walls) : List.of(),
                  new Faces(surface.faces), new Faces(surface.waterFaces), walls, List.copyOf(surface.waterfalls),
                  surface.frozenFalls(), highest(top),
                  cover ? BoardVegetation.ground(scene, surface, floor, sources) : null);
        }
    }
    /**
     * Faces with a grid of their outlines seen from above, built the first time a ray straight down or up asks
     * ({@link #under}): a pick at a point of a sculpted hex tests its few faces there instead of its thousands.
     */
    static final class Faces extends AbstractList<BoardSurface.Face> implements RandomAccess {
        private static final int CELLS = 8;
        private final List<BoardSurface.Face> faces;
        private volatile Grid grid;

        /** The faces' extent seen from above, and in each of its cells the faces whose outline reaches it, in order. */
        private record Grid(float x, float y, float right, float top, float width, float height,
              List<List<BoardSurface.Face>> cells) {
            int column(float at) { return Math.min(CELLS - 1, Math.max(0, (int) ((at - x) / width * CELLS))); }
            int row(float at) { return Math.min(CELLS - 1, Math.max(0, (int) ((at - y) / height * CELLS))); }
        }

        Faces(Collection<BoardSurface.Face> faces) { this.faces = List.copyOf(faces); }

        @Override
        public BoardSurface.Face get(int index) { return faces.get(index); }

        @Override
        public int size() { return faces.size(); }

        /** The faces whose outline seen from above may hold (x, y). */
        List<BoardSurface.Face> under(float x, float y) {
            Grid built = grid;
            if (built == null) { grid = built = grid(); }
            if (x < built.x || x > built.right || y < built.y || y > built.top) { return List.of(); }
            return built.cells.get(built.row(y) * CELLS + built.column(x));
        }

        private Grid grid() {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (BoardSurface.Face face : faces) {
                for (Vector3 corner : List.of(face.a(), face.b(), face.c())) {
                    minX = Math.min(minX, corner.x);
                    minY = Math.min(minY, corner.y);
                    maxX = Math.max(maxX, corner.x);
                    maxY = Math.max(maxY, corner.y);
                }
            }
            var extent = new Grid(minX, minY, maxX, maxY, Math.max(maxX - minX, 1e-4f), Math.max(maxY - minY, 1e-4f),
                  List.of());
            List<List<BoardSurface.Face>> cells = new ArrayList<>();
            for (int cell = 0; cell < CELLS * CELLS; cell++) { cells.add(new ArrayList<>()); }
            for (BoardSurface.Face face : faces) {
                Vector3 a = face.a(), b = face.b(), c = face.c();
                int left = extent.column(Math.min(a.x, Math.min(b.x, c.x)));
                int right = extent.column(Math.max(a.x, Math.max(b.x, c.x)));
                int bottom = extent.row(Math.min(a.y, Math.min(b.y, c.y)));
                int top = extent.row(Math.max(a.y, Math.max(b.y, c.y)));
                for (int row = bottom; row <= top; row++) {
                    for (int column = left; column <= right; column++) { cells.get(row * CELLS + column).add(face); }
                }
            }
            return new Grid(minX, minY, maxX, maxY, extent.width, extent.height, cells.stream().map(List::copyOf).toList());
        }
    }

    private record Edge(float x1, float y1, float x2, float y2) {
        float x(float y) {
            return x1 + (x2 - x1) * (y - y1) / (y2 - y1);
        }
    }

    private BoardTacticalGeometry() { }

    /** A hex's centre in tactical units: board units at hex scale 1, y down. */
    static BoardTactical.Point centre(Coords coords) {
        return new BoardTactical.Point(HexTile.centerX(coords, BoardGeometry.TILE_WIDTH),
              -HexTile.centerY(coords, BoardGeometry.TILE_HEIGHT));
    }

    /**
     * Where edge {@code a}'s line, moved {@code insetA} towards the hex centre, meets edge {@code b}'s line moved
     * {@code insetB}, in tactical units from the centre. Edge k runs from corner k to corner k + 1.
     */
    static BoardTactical.Point insetCorner(int a, double insetA, int b, double insetB) {
        double[] p = insetLine(a, insetA), q = insetLine(b, insetB);
        double t = ((q[0] - p[0]) * q[3] - (q[1] - p[1]) * q[2]) / (p[2] * q[3] - p[3] * q[2]);
        return new BoardTactical.Point((float) (p[0] + p[2] * t), (float) -(p[1] + p[3] * t));
    }

    /** Edge k's line moved {@code inset} towards the centre: a point on it and its direction, y up. */
    private static double[] insetLine(int edge, double inset) {
        Vector3 a = BoardGeometry.modelCorner(edge), b = BoardGeometry.modelCorner(edge + 1);
        double dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy);
        // The corners run counter-clockwise: the inward normal lies to the left of each edge.
        return new double[] { a.x - dy / length * inset, a.y + dx / length * inset, dx, dy };
    }

    /** The hex outline moved {@code inset} towards its centre, in tactical units from the centre. */
    static Path2D.Float insetHexagon(double inset) {
        Path2D.Float outline = new Path2D.Float();
        for (int k = 0; k < 6; k++) {
            var corner = insetCorner(k - 1, inset, k, inset);
            if (k == 0) { outline.moveTo(corner.x(), corner.y()); } else { outline.lineTo(corner.x(), corner.y()); }
        }
        outline.closePath();
        return outline;
    }

    /** The band between the hex outline moved {@code padding} and {@code padding + width} towards its centre. */
    static Area ring(double padding, double width) {
        Area ring = new Area(insetHexagon(padding));
        ring.subtract(new Area(insetHexagon(padding + width)));
        return ring;
    }

    /**
     * The band along the side facing {@code direction} (0 north, clockwise), from the outline moved {@code inset}
     * towards the centre to {@code width} further, in tactical units from the centre. Each end meets the band along
     * the neighbouring side in a mitre where that side is banded too ({@code left}: direction - 1, {@code right}:
     * direction + 1), else runs out to the outline.
     */
    static Path2D.Float sideBand(int direction, boolean left, boolean right, double inset, double width) {
        Line2D.Float outer = sideLine(direction, left, right, inset);
        Line2D.Float inner = sideLine(direction, left, right, inset + width);
        Path2D.Float band = new Path2D.Float();
        band.moveTo(outer.x1, outer.y1);
        band.lineTo(outer.x2, outer.y2);
        band.lineTo(inner.x2, inner.y2);
        band.lineTo(inner.x1, inner.y1);
        band.closePath();
        return band;
    }

    /** The side facing {@code direction} moved {@code inset} towards the centre, ends cut as {@link #sideBand}'s. */
    static Line2D.Float sideLine(int direction, boolean left, boolean right, double inset) {
        int side = Math.floorMod(1 - direction, 6);
        var a = insetCorner(side, inset, side + 1, left ? inset : 0);
        var b = insetCorner(side, inset, side - 1, right ? inset : 0);
        return new Line2D.Float(a.x(), a.y(), b.x(), b.y());
    }

    /** A zone boundary's run in a hex and the band on the zone's side of it, in tactical units from the centre. */
    record Run(Path2D.Double line, Path2D.Double band) { }

    /**
     * The line that cuts off {@code sides} (2 to 5) adjacent outside sides of a range zone in one straight run, as the
     * 2D board's hex-cross borders do, for the layout turned {@code turn} sixths clockwise; and the band {@code width}
     * wide on the zone's side of it, whose ends lie on the outline where the neighbours' side bands meet them. The
     * line's points are corners, chord midpoints or a third of the way down a side, so neighbouring hexes share its
     * ends.
     */
    static Run crossing(int sides, int turn, double width) {
        // Corners of the unturned layout: 0 east, then counter-clockwise (1 north-east, 2 north-west, 3 west,
        // 4 south-west, 5 south-east); each clockwise turn steps one corner back. The five-side run is drawn turned 5.
        IntFunction<Point2D> c = k -> latticeCorner(k - turn - (sides == 5 ? 5 : 0));
        List<Point2D> line = switch (sides) {
            case 2 -> List.of(c.apply(2), c.apply(0));
            case 3 -> List.of(c.apply(2), between(c.apply(1), c.apply(5), 1 / 3.0), c.apply(5));
            case 4 -> List.of(c.apply(2), between(c.apply(2), c.apply(0), .5), between(c.apply(4), c.apply(0), .5),
                  c.apply(4));
            case 5 -> List.of(c.apply(2), c.apply(4), c.apply(5), c.apply(1));
            default -> throw new IllegalArgumentException("A hex crossing cuts off 2 to 5 sides, not " + sides);
        };
        // A side band of this width extends width / sin 60 along the outline where it meets another side.
        double offset = width * 2 / Math.sqrt(3);
        List<Point2D> inner = new ArrayList<>();
        for (int i = 0; i < line.size(); i++) {
            if (i == 0 || i == line.size() - 1) {
                Point2D end = line.get(i), next = line.get(i == 0 ? 1 : i - 1);
                inner.add(endCut(end, next, offset));
            } else {
                inner.add(intersection(shifted(line.get(i - 1), line.get(i), offset),
                      shifted(line.get(i), line.get(i + 1), offset)));
            }
        }
        Path2D.Double path = new Path2D.Double(), band = new Path2D.Double();
        path.moveTo(line.getFirst().getX(), line.getFirst().getY());
        for (Point2D point : line.subList(1, line.size())) { path.lineTo(point.getX(), point.getY()); }
        band.append(path, false);
        for (int i = inner.size() - 1; i >= 0; i--) { band.lineTo(inner.get(i).getX(), inner.get(i).getY()); }
        band.closePath();
        return new Run(path, band);
    }

    /** Lattice corner {@code k} (0 east, counter-clockwise) in tactical units from the centre. */
    private static Point2D latticeCorner(int k) {
        Vector3 corner = BoardGeometry.modelCorner(k);
        return new Point2D.Double(corner.x, -corner.y);
    }

    private static Point2D between(Point2D a, Point2D b, double t) {
        return new Point2D.Double(a.getX() + (b.getX() - a.getX()) * t, a.getY() + (b.getY() - a.getY()) * t);
    }

    /** The segment {@code a}-{@code b} moved {@code offset} towards the hex centre: a point and its direction. */
    private static double[] shifted(Point2D a, Point2D b, double offset) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), length = Math.hypot(dx, dy);
        double nx = -dy / length, ny = dx / length;
        // Of the two normals, the one towards the centre (the origin).
        if (nx * -a.getX() + ny * -a.getY() < 0) { nx = -nx; ny = -ny; }
        return new double[] { a.getX() + nx * offset, a.getY() + ny * offset, dx, dy };
    }

    private static Point2D intersection(double[] p, double[] q) {
        double t = ((q[0] - p[0]) * q[3] - (q[1] - p[1]) * q[2]) / (p[2] * q[3] - p[3] * q[2]);
        return new Point2D.Double(p[0] + p[2] * t, p[1] + p[3] * t);
    }

    /**
     * Where the band of the run from corner {@code end} towards {@code next} ends: on the side from that corner that
     * leads inwards, so the end meets the neighbour's band.
     */
    private static Point2D endCut(Point2D end, Point2D next, double offset) {
        double[] band = shifted(end, next, offset);
        Point2D side = null;
        double inward = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            Point2D corner = latticeCorner(k);
            if (corner.distance(end) > 1e-3) { continue; }
            for (int neighbour : new int[] { k - 1, k + 1 }) {
                Point2D other = latticeCorner(neighbour);
                double along = (other.getX() - end.getX()) * (band[0] - end.getX())
                      + (other.getY() - end.getY()) * (band[1] - end.getY());
                if (along > inward) { inward = along; side = other; }
            }
        }
        return intersection(band,
              new double[] { end.getX(), end.getY(), side.getX() - end.getX(), side.getY() - end.getY() });
    }

    /** {@code shape}, in tactical units from {@code origin}, as one fill. */
    static BoardTactical.Fill fill(Shape shape, BoardTactical.Point origin, int argb, BoardTactical.Playback playback,
          BoardTactical.HexBorder border, BoardTactical.Point plane) {
        List<BoardTactical.Contour> contours = new ArrayList<>();
        List<BoardTactical.Point> points = new ArrayList<>();
        PathIterator path = shape.getPathIterator(null, .25);
        float[] xy = new float[6];
        while (!path.isDone()) {
            int segment = path.currentSegment(xy);
            if (segment == PathIterator.SEG_MOVETO && !points.isEmpty()) {
                contours.add(new BoardTactical.Contour(points));
                points = new ArrayList<>();
            }
            if (segment != PathIterator.SEG_CLOSE) { points.add(new BoardTactical.Point(origin.x() + xy[0], origin.y() + xy[1])); }
            path.next();
        }
        if (!points.isEmpty()) { contours.add(new BoardTactical.Contour(points)); }
        return new BoardTactical.Fill(contours, path.getWindingRule(), argb, playback, border, plane);
    }

    /** Horizontal trapezoids preserve holes, dashed strokes, concave polygons and glyph counters. */
    static List<Triangle> flat(BoardTactical.Fill fill) {
        return flat(fill.shape(), fill.argb());
    }

    /** Also used by physical surface markings, which borrow exactly the same tessellator and draping clipper. */
    static List<Triangle> flat(Shape shape, int argb) {
        List<Edge> edges = new ArrayList<>();
        TreeSet<Float> levels = new TreeSet<>();
        PathIterator path = new Area(shape).getPathIterator(null, 0.25);
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
                if (y != nextY) {
                    edges.add(new Edge(x, y, nextX, nextY));
                    levels.add(y);
                    levels.add(nextY);
                }
                x = nextX;
                y = nextY;
            }
            path.next();
        }
        List<Triangle> result = new ArrayList<>();
        List<Float> rows = new ArrayList<>(levels);
        for (int row = 1; row < rows.size(); row++) {
            float bottom = rows.get(row - 1), top = rows.get(row), middle = (bottom + top) / 2;
            List<Edge> crossings = edges.stream()
                  .filter(edge -> middle > Math.min(edge.y1(), edge.y2()) && middle < Math.max(edge.y1(), edge.y2()))
                  .sorted(Comparator.comparingDouble(edge -> edge.x(middle))).toList();
            for (int i = 1; i < crossings.size(); i += 2) {
                Edge left = crossings.get(i - 1), right = crossings.get(i);
                Vector3 a = new Vector3(left.x(bottom), bottom, 0), b = new Vector3(right.x(bottom), bottom, 0);
                Vector3 c = new Vector3(right.x(top), top, 0), d = new Vector3(left.x(top), top, 0);
                add(result::add, a, b, c, argb);
                add(result::add, a, c, d, argb);
            }
        }
        return result;
    }

    static void drape(BoardScene scene, Consumer<Triangle> destination) {
        drape(scene, scene.tactical().fills(), destination, surfaces(scene));
    }

    static void drape(BoardScene scene, Consumer<Triangle> destination, Function<Coords, Surface> surfaces) {
        drape(scene, scene.tactical().fills(), destination, surfaces);
    }

    static Function<Coords, Surface> surfaces(BoardScene scene) {
        Map<Coords, Surface> cache = new HashMap<>();
        float floor = BoardGeometry.floor(scene);
        return coords -> cache.computeIfAbsent(coords,
              key -> Surface.of(new BoardSurface(scene, scene.tile(key)), scene, floor));
    }

    private static void drape(BoardScene scene, List<BoardTactical.Fill> fills, Consumer<Triangle> destination,
          Function<Coords, Surface> surfaces) {
        Clipper clipper = new Clipper();
        int layer = 0;
        for (BoardTactical.Fill fill : fills) {
            drape(scene, fill, layer++, destination, surfaces, clipper);
        }
    }

    /** A retained command uses its absolute painter position, including the original capped lift. */
    static void drape(BoardScene scene, BoardTactical.Fill fill, int layer, Consumer<Triangle> destination,
          Function<Coords, Surface> surfaces, Clipper clipper) {
        if (floating(scene, fill, destination)) { return; }
        float lift = layerLift(layer);
        for (Triangle triangle : flat(fill)) {
            int firstX = Math.max(0, (int) Math.floor(minX(triangle) / (BoardGeometry.TILE_WIDTH * 0.75f)) - 1);
            int lastX = Math.min(scene.width() - 1, (int) Math.floor(maxX(triangle) / (BoardGeometry.TILE_WIDTH * 0.75f)));
            int firstY = Math.max(0, (int) Math.floor(minY(triangle) / BoardGeometry.TILE_HEIGHT) - 1);
            int lastY = Math.min(scene.height() - 1, (int) Math.floor(maxY(triangle) / BoardGeometry.TILE_HEIGHT));
            Triangle world = new Triangle(world(triangle.a()), world(triangle.b()), world(triangle.c()), fill.argb());
            clipper.prepare(world);
            for (int x = firstX; x <= lastX; x++) {
                for (int y = firstY; y <= lastY; y++) {
                    Coords coords = new Coords(x, y);
                    Surface surface = surfaces.apply(coords);
                    clipSurface(surface, lift, destination, clipper);
                    for (BoardSurface.Face face : surface.slopes()) {
                        clipper.clip(face, lift, destination);
                    }
                }
            }
        }
    }

    static Coords borderCoords(BoardScene scene, BoardTactical.HexBorder border) {
        return anchorCoords(scene, border.anchor());
    }

    static Coords anchorCoords(BoardScene scene, BoardTactical.Point anchor) {
        if (anchor == null) { return null; }
        BoardScene.Tile tile = BoardGeometry.tile(scene, anchor.x() * BoardGeometry.hexScale(),
              -anchor.y() * BoardGeometry.hexScale());
        return tile == null ? null : tile.coords();
    }

    /** A single horizontal plane follows the owner's elevation, independently of ramps and sculpted relief. */
    static boolean floating(BoardScene scene, BoardTactical.Fill fill, Consumer<Triangle> destination) {
        Coords coords = anchorCoords(scene, fill.planeAnchor());
        if (coords == null) { return false; }
        float z = floatingZ(scene, coords);
        for (Triangle triangle : flat(fill)) {
            Vector3 a = world(triangle.a()), b = world(triangle.b()), c = world(triangle.c());
            a.z = z; b.z = z; c.z = z;
            destination.accept(new Triangle(a, b, c, triangle.argb()));
        }
        return true;
    }

    /**
     * Shared plane for flat hex fills, labels and hover/editor cursors in either camera view. A climbing road must
     * not lift the whole hex's annotations. Raised point symbols use {@link GpuMarkers#locationSupport} instead.
     */
    static float floatingZ(BoardScene scene, Coords coords) {
        return BoardGeometry.surfaceZ(scene.tile(coords)) + HEX_PLANE_CLEARANCE;
    }

    /** Only the higher owner emits a connector; board edges and equal-height neighbors have none. */
    static PlaneStep planeStep(BoardScene scene, Coords coords, int edge) {
        Coords neighbor = coords.translated(BoardGeometry.edgeDirection(edge));
        if (scene.tile(neighbor) == null) { return null; }
        float top = floatingZ(scene, coords), bottom = floatingZ(scene, neighbor);
        if (top <= bottom) { return null; }
        Vector3 a = BoardGeometry.corner(coords, 0, edge), b = BoardGeometry.corner(coords, 0, edge + 1);
        a.z = top; b.z = top;
        return new PlaneStep(a, b, bottom, neighbor);
    }

    /** Join matching captured bands only where they both reach their common hex edge. */
    static void connectPlanes(BoardScene scene, List<BoardTactical.Fill> fills, Consumer<Triangle> destination) {
        Map<PlaneFill, Area> areas = new LinkedHashMap<>();
        for (BoardTactical.Fill fill : fills) {
            // Deployment owns its perimeter curtains and omits these per-hex fills from the plane pass.
            if (BoardDeploymentGeometry.isZone(fill)) { continue; }
            Coords coords = anchorCoords(scene, fill.planeAnchor());
            if (coords != null) {
                areas.computeIfAbsent(new PlaneFill(coords, fill.argb(), fill.playback()), ignored -> new Area())
                      .add(new Area(fill.shape()));
            }
        }
        for (var entry : areas.entrySet()) {
            PlaneFill owner = entry.getKey();
            for (int edge = 0; edge < 6; edge++) {
                PlaneStep step = planeStep(scene, owner.coords(), edge);
                if (step == null) { continue; }
                Area neighbor = areas.get(new PlaneFill(step.neighbor(), owner.argb(), owner.playback()));
                if (neighbor == null) { continue; }
                List<float[]> first = planeIntervals(entry.getValue(), owner.coords(), step);
                List<float[]> second = planeIntervals(neighbor, step.neighbor(), step);
                for (float[] a : first) {
                    for (float[] b : second) {
                        float start = Math.max(a[0], b[0]), end = Math.min(a[1], b[1]);
                        if (end - start > .00001f) { step.connect(start, end, owner.argb(), destination); }
                    }
                }
            }
        }
    }

    /** Intersect an edge with the captured fill, just inside its owner to include polygons ending on the edge. */
    private static List<float[]> planeIntervals(Area area, Coords owner, PlaneStep step) {
        double scale = BoardGeometry.hexScale();
        double ax = step.a().x / scale, ay = -step.a().y / scale;
        double bx = step.b().x / scale, by = -step.b().y / scale;
        double nx = BoardGeometry.centerX(owner) / scale - (ax + bx) / 2;
        double ny = -BoardGeometry.centerY(owner) / scale - (ay + by) / 2;
        double inset = .001 / Math.hypot(nx, ny);
        ax += nx * inset; ay += ny * inset;
        bx += nx * inset; by += ny * inset;
        double dx = bx - ax, dy = by - ay;
        TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, 1.0));
        PathIterator path = area.getPathIterator(null, .25);
        double[] point = new double[6];
        double x = 0, y = 0, startX = 0, startY = 0;
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = point[0]; startY = y = point[1];
            } else {
                double nextX = type == PathIterator.SEG_CLOSE ? startX : point[0];
                double nextY = type == PathIterator.SEG_CLOSE ? startY : point[1];
                double sx = nextX - x, sy = nextY - y, denominator = dx * sy - dy * sx;
                if (Math.abs(denominator) > .0000001) {
                    double t = ((x - ax) * sy - (y - ay) * sx) / denominator;
                    double u = ((x - ax) * dy - (y - ay) * dx) / denominator;
                    if (t > 0 && t < 1 && u >= 0 && u <= 1) { cuts.add(t); }
                }
                x = nextX; y = nextY;
            }
            path.next();
        }
        List<float[]> result = new ArrayList<>();
        double previous = 0;
        for (double next : cuts) {
            double middle = (previous + next) / 2;
            if (next > previous && area.contains(ax + dx * middle, ay + dy * middle)) {
                result.add(new float[] { (float) previous, (float) next });
            }
            previous = next;
        }
        return result;
    }

    static float layerLift(int layer) {
        return (0.35f + Math.min(layer, 10000) * 0.0001f) * BoardGeometry.hexScale();
    }

    /** Cache both presentations once; the camera only selects which one to draw. */
    static void walls(BoardScene scene, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline) {
        walls(scene, flat, destination, outline, surfaces(scene));
    }

    static void walls(BoardScene scene, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline, Function<Coords, Surface> surfaces) {
        Clipper clipper = new Clipper();
        if (flat) {
            drape(scene, scene.tactical().flatWalls(), destination, surfaces);
        }
        for (BoardTactical.Wall wall : BoardRangeBorder.join(scene.tactical().walls())) {
            wall(scene, wall, flat, destination, outline, surfaces, clipper);
        }
    }

    static void wall(BoardScene scene, BoardTactical.Wall wall, boolean flat, Consumer<Triangle> destination,
          BiConsumer<BoardTactical.Wall, Triangle> outline, Function<Coords, Surface> surfaces, Clipper clipper) {
        if (scene.tile(wall.coords()) == null) { return; }
        if (!flat) {
            Vector3 a = wallPoint(scene, wall, wall.a(), false, surfaces), b = wallPoint(scene, wall, wall.b(), false, surfaces);
            Vector3 c = wallPoint(scene, wall, wall.b(), true, surfaces), d = wallPoint(scene, wall, wall.a(), true, surfaces);
            destination.accept(new Triangle(a, b, c, wall.argb()));
            destination.accept(new Triangle(a, c, d, wall.argb()));
            wallOutline(wall, d, c, triangle -> outline.accept(wall, triangle));
        } else {
            Surface surface = surfaces.apply(wall.coords());
            Vector3 a = new Vector3(wall.a().x() * BoardGeometry.hexScale(), -wall.a().y() * BoardGeometry.hexScale(), 0);
            Vector3 b = new Vector3(wall.b().x() * BoardGeometry.hexScale(), -wall.b().y() * BoardGeometry.hexScale(), 0);
            wallOutline(wall, a, b, triangle -> {
                clipper.prepare(triangle);
                clipSurface(surface, WALL_CLEARANCE * BoardGeometry.hexScale(),
                      clipped -> outline.accept(wall, clipped), clipper);
            });
        }
    }

    private static void clipSurface(Surface surface, float lift, Consumer<Triangle> destination,
          Clipper clipper) {
        for (BoardSurface.Face face : surface.top()) {
            clipper.clip(face, lift, destination);
        }
    }

    /** A continuous ribbon: the original dash pattern is supplied by a scrolling texture. */
    private static void wallOutline(BoardTactical.Wall wall, Vector3 a, Vector3 b, Consumer<Triangle> destination) {
        if (wall.outline() == null) {
            return;
        }
        var stroke = wall.outline().stroke();
        Vector3 side = new Vector3(a.y - b.y, b.x - a.x, 0).nor()
              .scl(stroke.getLineWidth() * BoardGeometry.hexScale() / 2);
        Vector3 first = new Vector3(a).sub(side), second = new Vector3(b).sub(side);
        Vector3 third = new Vector3(b).add(side), fourth = new Vector3(a).add(side);
        destination.accept(new Triangle(first, second, third, wall.outline().argb()));
        destination.accept(new Triangle(first, third, fourth, wall.outline().argb()));
    }

    private static Vector3 wallPoint(BoardScene scene, BoardTactical.Wall wall, BoardTactical.Point point, boolean top,
          Function<Coords, Surface> surfaces) {
        Vector3 world = new Vector3(point.x() * BoardGeometry.hexScale(), -point.y() * BoardGeometry.hexScale(), 0);
        // Only interior owners of adjoining panels contribute to the joint, never terrain outside the range.
        // Finished tops include sculpted crowns and banks; deep water and lakebeds cannot pull a border down.
        float z = Math.max(scene.tile(wall.coords()).elevation() * BoardGeometry.level(),
              surfaces.apply(wall.coords()).highestTop());
        for (Coords coords : point.equals(wall.a()) ? wall.aNeighbors() : wall.bNeighbors()) {
            BoardScene.Tile neighbor = scene.tile(coords);
            if (neighbor != null) {
                float neighborZ = Math.max(neighbor.elevation() * BoardGeometry.level(), surfaces.apply(coords).highestTop());
                z = top ? Math.max(z, neighborZ) : Math.min(z, neighborZ);
            }
        }
        world.z = z + (top ? wall.height() * BoardGeometry.level() : 0) + WALL_CLEARANCE * BoardGeometry.hexScale();
        return world;
    }

    private static Vector3 world(Vector3 point) {
        return new Vector3(point.x * BoardGeometry.hexScale(), -point.y * BoardGeometry.hexScale(), 0);
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /** Convex XY clipping retains interpolated Z and reuses wholly covered vertices. The boundary is anticlockwise. */
    static List<Vector3> clip(List<Vector3> polygon, List<Vector3> boundary) {
        for (int edge = 0; edge < boundary.size() && !polygon.isEmpty(); edge++) {
            polygon = clip(polygon, boundary.get(edge), boundary.get((edge + 1) % boundary.size()));
        }
        return polygon;
    }

    /** Keep the left half-plane of one directed XY edge. */
    static List<Vector3> clip(List<Vector3> polygon, Vector3 a, Vector3 b) {
        if (polygon.isEmpty()) { return polygon; }
        if (polygon.stream().allMatch(p -> cross(a, b, p) >= 0)) { return polygon; }
        List<Vector3> next = new ArrayList<>(polygon.size() + 1);
        Vector3 previous = polygon.getLast();
        float before = cross(a, b, previous);
        for (Vector3 point : polygon) {
            float after = cross(a, b, point);
            if ((before >= 0) != (after >= 0)) { next.add(new Vector3(previous).lerp(point, before / (before - after))); }
            if (after >= 0) { next.add(point); }
            previous = point; before = after;
        }
        return next;
    }

    /** The faces of a wall that lie back far enough to be walked and marked on: slopes and talus, not cliff faces. */
    static List<BoardSurface.Face> lying(List<BoardSurface.Face> walls) {
        List<BoardSurface.Face> result = new ArrayList<>();
        for (BoardSurface.Face face : walls) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            if (normal.z > .35f * normal.len()) { result.add(face); }
        }
        return result;
    }

    /** One geometry rebuild owns its scratch vertices; emitted triangles always own separate vertices. */
    static final class Clipper {
        // Two convex triangles intersect in at most six corners; spare entries also retain boundary duplicates.
        private final Vector3[] first = vertices(), second = vertices();
        private Triangle triangle;
        private float minimumX, maximumX, minimumY, maximumY;

        /** One world triangle is clipped against many faces; its bounds stay fixed throughout those queries. */
        void prepare(Triangle triangle) {
            this.triangle = triangle;
            minimumX = minX(triangle);
            maximumX = maxX(triangle);
            minimumY = minY(triangle);
            maximumY = maxY(triangle);
        }

        private static Vector3[] vertices() {
            Vector3[] result = new Vector3[9];
            for (int i = 0; i < result.length; i++) { result[i] = new Vector3(); }
            return result;
        }

        void clip(BoardSurface.Face face, float lift, Consumer<Triangle> destination) {
            clip(face, lift, false, destination);
        }

        /** Add a piecewise planar height field while retaining the source's thickness, including vertical faces. */
        void displace(BoardSurface.Face face, Consumer<Triangle> destination) {
            clip(face, 0, true, destination);
        }

        private void clip(BoardSurface.Face face, float lift, boolean displace, Consumer<Triangle> destination) {
            if (maximumX < Math.min(face.a().x, Math.min(face.b().x, face.c().x))
                  || minimumX > Math.max(face.a().x, Math.max(face.b().x, face.c().x))
                  || maximumY < Math.min(face.a().y, Math.min(face.b().y, face.c().y))
                  || minimumY > Math.max(face.a().y, Math.max(face.b().y, face.c().y))) {
                return;
            }
            float area = cross(face.a(), face.b(), face.c());
            if (Math.abs(area) < 0.00001f) { return; }
            Vector3[] polygon = first, clipped = second;
            polygon[0].set(triangle.a());
            polygon[1].set(triangle.b());
            polygon[2].set(triangle.c());
            int count = 3;
            float sign = Math.signum(area);
            for (int edge = 0; edge < 3 && count > 0; edge++) {
                Vector3 a = edge == 0 ? face.a() : edge == 1 ? face.b() : face.c();
                Vector3 b = edge == 0 ? face.b() : edge == 1 ? face.c() : face.a();
                int clippedCount = 0;
                Vector3 previous = polygon[count - 1];
                float before = sign * cross(a, b, previous);
                for (int i = 0; i < count; i++) {
                    Vector3 point = polygon[i];
                    float after = sign * cross(a, b, point);
                    if ((before >= 0) != (after >= 0)) {
                        clipped[clippedCount++].set(previous).lerp(point, before / (before - after));
                    }
                    if (after >= 0) { clipped[clippedCount++].set(point); }
                    previous = point;
                    before = after;
                }
                Vector3[] swap = polygon;
                polygon = clipped;
                clipped = swap;
                count = clippedCount;
            }
            if (count < 3) { return; }
            for (int i = 0; i < count; i++) {
                Vector3 point = polygon[i];
                float b = cross(face.a(), point, face.c()) / area;
                float c = cross(face.a(), face.b(), point) / area;
                point.z = (displace ? point.z : 0)
                      + face.a().z + b * (face.b().z - face.a().z) + c * (face.c().z - face.a().z) + lift;
            }
            Vector3 start = new Vector3(polygon[0]), previous = new Vector3(polygon[1]);
            for (int i = 2; i < count; i++) {
                Vector3 point = new Vector3(polygon[i]);
                if (displace) {
                    var a = new Vector3(previous).sub(start);
                    var b = new Vector3(point).sub(start);
                    float lengths = a.len2() * b.len2();
                    // Clipping on a profile boundary can leave nearly collinear float slivers. They have no
                    // visible area, but their unstable plane can produce a spurious distant picking hit.
                    if (a.crs(b).len2() > 1e-10f * lengths) {
                        destination.accept(new Triangle(start, previous, point, triangle.argb()));
                    }
                } else { add(destination, start, previous, point, triangle.argb()); }
                previous = point;
            }
        }
    }

    private static void add(Consumer<Triangle> destination, Vector3 a, Vector3 b, Vector3 c, int argb) {
        if (Math.abs(cross(a, b, c)) > 0.00001f) {
            destination.accept(new Triangle(a, b, c, argb));
        }
    }

    private static float minX(Triangle triangle) { return Math.min(triangle.a().x, Math.min(triangle.b().x, triangle.c().x)); }
    private static float maxX(Triangle triangle) { return Math.max(triangle.a().x, Math.max(triangle.b().x, triangle.c().x)); }
    private static float minY(Triangle triangle) { return Math.min(triangle.a().y, Math.min(triangle.b().y, triangle.c().y)); }
    private static float maxY(Triangle triangle) { return Math.max(triangle.a().y, Math.max(triangle.b().y, triangle.c().y)); }
}
