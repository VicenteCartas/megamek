/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.ConvexHull;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardModelBounds;

/** Conservative cross-sections of solid plants, retaining vertical space and gaps between separate cups or branches. */
final class BoardPlantFootprint {
    private static final int STRIPS = 24, LAYERS = 12;
    private static final Map<File, List<Section>> SHAPES = new ConcurrentHashMap<>();
    private final List<Section> sections;
    private final Polygon cactus;
    private final float[] edges;
    private final float minX, minY, maxX, maxY;
    private final float x, y, z;

    record Key(String asset, float height, BoardDecoration.Stretch stretch, float rotation) { }
    private record Shape(float[] points, float minX, float minY, float maxX, float maxY, Map<Shape, float[]> tests) { }
    private record Section(float bottom, float top, List<Shape> shapes) { }

    private BoardPlantFootprint(BoardScene.Feature plant) {
        x = 0; y = 0; z = 0;
        sections = new ArrayList<>();
        edges = new float[4];
        java.util.Arrays.fill(edges, Float.NEGATIVE_INFINITY);
        cactus = BoardFeatures.solid(plant.asset()) ? null : BoardTreeCoverage.outline(plant);
        if (cactus != null) { minX = minY = maxX = maxY = 0; return; }
        File root = new File(Configuration.dataDir(), "models/board");
        File file = BoardModelBounds.file(root, plant.asset()).getAbsoluteFile();
        float[] bounds = BoardModelBounds.bounds(plant.asset());
        float scale = BoardTreeCoverage.scale(plant, bounds[5] - bounds[2]);
        float sx = scale * (float) plant.stretch().x(), sy = scale * (float) plant.stretch().y();
        float sz = scale * (float) plant.stretch().z();
        float inset = plant.asset().startsWith("mars/") ? (bounds[2] + (bounds[5] - bounds[2]) * .06f) * sz : 0;
        double angle = Math.toRadians(plant.rotation()), cos = Math.cos(angle), sin = Math.sin(angle);
        float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
        for (var source : SHAPES.computeIfAbsent(file, BoardPlantFootprint::read)) {
            float bottom = Math.max(0, source.bottom * sz - inset), top = source.top * sz - inset;
            if (top <= 0) { continue; }
            List<Shape> shapes = new ArrayList<>();
            for (var shape : source.shapes) {
                float[] points = shape.points.clone();
                for (int i = 0; i < points.length; i += 2) {
                    float px = points[i] * sx, py = points[i + 1] * sy;
                    points[i] = (float) (px * cos - py * sin); points[i + 1] = (float) (px * sin + py * cos);
                    for (int edge = 0; edge < 4; edge++) {
                        float nx = (edge < 2 ? -1 : 1) * BoardGeometry.TILE_HEIGHT / 2;
                        float ny = (edge % 2 == 0 ? -1 : 1) * BoardGeometry.TILE_WIDTH / 4;
                        edges[edge] = Math.max(edges[edge], nx * points[i] + ny * points[i + 1]);
                    }
                }
                var placed = shape(points);
                lowX = Math.min(lowX, placed.minX); lowY = Math.min(lowY, placed.minY);
                highX = Math.max(highX, placed.maxX); highY = Math.max(highY, placed.maxY);
                shapes.add(placed);
            }
            sections.add(new Section(bottom, top, shapes));
        }
        minX = lowX; minY = lowY; maxX = highX; maxY = highY;
    }

    private BoardPlantFootprint(BoardPlantFootprint source, float x, float y, float z) {
        sections = source.sections; cactus = source.cactus; edges = source.edges;
        minX = source.minX; minY = source.minY; maxX = source.maxX; maxY = source.maxY;
        this.x = x; this.y = y; this.z = z;
    }

    static void reload() { SHAPES.clear(); }

    static BoardPlantFootprint of(BoardScene.Feature plant) {
        var result = new BoardPlantFootprint(plant);
        return result.cactus == null ? result.at(plant.x(), plant.y(), 0) : result;
    }

    /** Geometry belongs to one packing search; each candidate position only creates a translated wrapper. */
    static BoardPlantFootprint of(BoardScene.Feature plant, Map<Key, BoardPlantFootprint> prepared) {
        var key = new Key(plant.asset(), plant.height(), plant.stretch(), plant.rotation());
        return prepared.computeIfAbsent(key, ignored -> new BoardPlantFootprint(plant)).at(plant.x(), plant.y(), 0);
    }

    /** Recheck the same body after terrain support has settled its actual drawn anchor. */
    BoardPlantFootprint at(float x, float y, float z) { return new BoardPlantFootprint(this, x, y, z); }

    boolean overlaps(BoardPlantFootprint other) {
        if (cactus != null && other.cactus != null) { return Intersector.overlapConvexPolygons(cactus, other.cactus); }
        if (cactus != null) { return other.overlaps(this); }
        if (other.cactus != null) {
            for (var section : sections) {
                for (var shape : section.shapes) {
                    var polygon = new Polygon(shape.points); polygon.setPosition(x, y);
                    if (Intersector.overlapConvexPolygons(polygon, other.cactus)) { return true; }
                }
            }
            return false;
        }
        float dx = other.x - x, dy = other.y - y, dz = other.z - z;
        if (maxX <= other.minX + dx || minX >= other.maxX + dx || maxY <= other.minY + dy || minY >= other.maxY + dy) { return false; }
        for (var a : sections) {
            for (var b : other.sections) {
                if (a.top <= b.bottom + dz || a.bottom >= b.top + dz) { continue; }
                for (var aa : a.shapes) {
                    for (var bb : b.shapes) {
                        if (aa.maxX <= bb.minX + dx || aa.minX >= bb.maxX + dx || aa.maxY <= bb.minY + dy || aa.minY >= bb.maxY + dy) { continue; }
                        var ranges = aa.tests.computeIfAbsent(bb, shape -> ranges(aa.points, shape.points));
                        if (overlaps(ranges, dx, dy)) { return true; }
                    }
                }
            }
        }
        return false;
    }

    /** A rigid colony stays within its own hex to prevent intersections across shared borders; crowns may overhang its cliff top. */
    boolean inside() {
        if (cactus != null) { return BoardTreeCoverage.inside(cactus); }
        float w = BoardGeometry.TILE_WIDTH, h = BoardGeometry.TILE_HEIGHT;
        if (minY + y < -h / 2 + .02 || maxY + y > h / 2 - .02) { return false; }
        for (int edge = 0; edge < 4; edge++) {
            float nx = (edge < 2 ? -1 : 1) * h / 2, ny = (edge % 2 == 0 ? -1 : 1) * w / 4;
            if (edges[edge] + nx * x + ny * y > w * h / 4 - .02 * Math.hypot(h / 2, w / 4)) { return false; }
        }
        return true;
    }

    /** Fixed shape pairs reuse their projection ranges; candidate positions only shift those intervals. */
    private static float[] ranges(float[] a, float[] b) {
        float[] result = new float[(a.length + b.length) * 2];
        int index = 0;
        for (float[] axes : new float[][] { a, b }) {
            for (int i = 0, previous = axes.length - 2; i < axes.length; previous = i, i += 2) {
                float nx = axes[previous + 1] - axes[i + 1], ny = axes[i] - axes[previous];
                if (nx == 0 && ny == 0) { continue; }
                float lowA = Float.POSITIVE_INFINITY, highA = Float.NEGATIVE_INFINITY;
                float lowB = Float.POSITIVE_INFINITY, highB = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < a.length; j += 2) {
                    float p = nx * a[j] + ny * a[j + 1]; lowA = Math.min(lowA, p); highA = Math.max(highA, p);
                }
                for (int j = 0; j < b.length; j += 2) {
                    float p = nx * b[j] + ny * b[j + 1]; lowB = Math.min(lowB, p); highB = Math.max(highB, p);
                }
                result[index++] = nx; result[index++] = ny;
                result[index++] = lowA - highB; result[index++] = highA - lowB;
            }
        }
        return java.util.Arrays.copyOf(result, index);
    }

    private static boolean overlaps(float[] ranges, float x, float y) {
        for (int i = 0; i < ranges.length; i += 4) {
            float shift = ranges[i] * x + ranges[i + 1] * y;
            if (shift <= ranges[i + 2] || shift >= ranges[i + 3]) { return false; }
        }
        return true;
    }

    private static Shape shape(float[] points) {
        float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 2) {
            lowX = Math.min(lowX, points[i]); highX = Math.max(highX, points[i]);
            lowY = Math.min(lowY, points[i + 1]); highY = Math.max(highY, points[i + 1]);
        }
        return new Shape(points, lowX, lowY, highX, highY, new java.util.IdentityHashMap<>());
    }

    private static List<Section> read(File file) {
        var triangles = RigidGlb.plantTriangles(file);
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        for (var triangle : triangles) {
            for (var point : triangle) {
                low = Math.min(low, point.y); high = Math.max(high, point.y);
                bottom = Math.min(bottom, point.z); top = Math.max(top, point.z);
            }
        }
        float step = (high - low) / STRIPS, rise = (top - bottom) / LAYERS;
        List<Section> result = new ArrayList<>();
        for (int layer = 0; layer < LAYERS; layer++) {
            float z0 = bottom + layer * rise, z1 = bottom + (layer + 1) * rise;
            List<List<float[]>> rows = new ArrayList<>();
            for (int row = 0; row < STRIPS; row++) { rows.add(new ArrayList<>()); }
            for (var triangle : triangles) {
                var polygon = clip(clip(List.of(triangle), z0, true), z1, false);
                if (polygon.isEmpty()) { continue; }
                float first = Float.POSITIVE_INFINITY, last = Float.NEGATIVE_INFINITY;
                for (var point : polygon) { first = Math.min(first, point.y); last = Math.max(last, point.y); }
                for (int row = Math.clamp((int) ((first - low) / step), 0, STRIPS - 1);
                      row <= Math.clamp((int) ((last - low) / step), 0, STRIPS - 1); row++) {
                    float[] range = interval(polygon, low + row * step, low + (row + 1) * step);
                    if (range[0] <= range[1]) { rows.get(row).add(range); }
                }
            }
            List<float[]> boxes = new ArrayList<>();
            for (int row = 0; row < STRIPS; row++) {
                var intervals = rows.get(row);
                intervals.sort(Comparator.comparingDouble(range -> range[0]));
                for (int i = 0; i < intervals.size();) {
                    float left = intervals.get(i)[0], right = intervals.get(i++)[1];
                    while (i < intervals.size() && intervals.get(i)[0] <= right + .001f) { right = Math.max(right, intervals.get(i++)[1]); }
                    boxes.add(new float[] { left - .001f, low + row * step - .001f,
                          right + .001f, low + (row + 1) * step + .001f });
                }
            }
            result.add(new Section(z0 - .001f, z1 + .001f, components(boxes)));
        }
        return List.copyOf(result);
    }

    /** Connected enclosures share a convex section; disconnected cups and branches remain separate. */
    private static List<Shape> components(List<float[]> boxes) {
        int[] parent = new int[boxes.size()];
        for (int i = 0; i < parent.length; i++) { parent[i] = i; }
        for (int i = 0; i < boxes.size(); i++) {
            float[] a = boxes.get(i);
            for (int j = 0; j < i; j++) {
                float[] b = boxes.get(j);
                if (a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]) {
                    parent[root(parent, i)] = root(parent, j);
                }
            }
        }
        var groups = new LinkedHashMap<Integer, List<float[]>>();
        for (int i = 0; i < boxes.size(); i++) { groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(boxes.get(i)); }
        List<Shape> result = new ArrayList<>();
        for (var group : groups.values()) {
            float[] points = new float[group.size() * 8];
            int index = 0;
            for (var box : group) {
                for (int corner = 0; corner < 4; corner++) {
                    points[index++] = box[corner < 2 ? 0 : 2];
                    points[index++] = box[corner % 2 == 0 ? 1 : 3];
                }
            }
            result.add(shape(envelope(points)));
        }
        return result;
    }

    /** Twelve supporting planes enclose every source point while bounding the cost of each collision query. */
    private static float[] envelope(float[] points) {
        int sides = 12;
        double[] nx = new double[sides], ny = new double[sides], limit = new double[sides];
        for (int i = 0; i < sides; i++) {
            nx[i] = Math.cos(i * Math.PI * 2 / sides); ny[i] = Math.sin(i * Math.PI * 2 / sides);
            limit[i] = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < points.length; j += 2) { limit[i] = Math.max(limit[i], nx[i] * points[j] + ny[i] * points[j + 1]); }
        }
        float[] polygon = new float[sides * 2];
        for (int i = 0; i < sides; i++) {
            int next = (i + 1) % sides;
            double det = nx[i] * ny[next] - ny[i] * nx[next];
            polygon[i * 2] = (float) ((limit[i] * ny[next] - ny[i] * limit[next]) / det);
            polygon[i * 2 + 1] = (float) ((nx[i] * limit[next] - limit[i] * nx[next]) / det);
        }
        return new ConvexHull().computePolygon(polygon, false).toArray();
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static List<Vector3> clip(List<Vector3> polygon, float z, boolean above) {
        List<Vector3> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            Vector3 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            boolean inside = above ? a.z >= z : a.z <= z;
            if (inside) { result.add(a); }
            if (inside != (above ? b.z >= z : b.z <= z)) { result.add(a.cpy().lerp(b, (z - a.z) / (b.z - a.z))); }
        }
        return result;
    }

    private static float[] interval(List<Vector3> polygon, float bottom, float top) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vector3 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            if (a.y >= bottom && a.y <= top) { low = Math.min(low, a.x); high = Math.max(high, a.x); }
            if (a.y == b.y) { continue; }
            for (float edge : new float[] { bottom, top }) {
                float t = (edge - a.y) / (b.y - a.y);
                if (t >= 0 && t <= 1) {
                    float x = a.x + t * (b.x - a.x); low = Math.min(low, x); high = Math.max(high, x);
                }
            }
        }
        return new float[] { low, high };
    }
}
