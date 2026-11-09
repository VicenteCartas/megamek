/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.File;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import de.javagl.jgltf.model.v2.GltfModelCreatorV2;
import megamek.common.Hex;
import megamek.logging.MMLogger;

/** Authored, invisible GLB volumes. Shared by placement, gameplay-height baking and interior geometry; no GPU access. */
public final class BuildingVolumes {
    public static final double LEVEL = 18;
    private static final Pattern ROLE = Pattern.compile("(?:.*-)?(base|floor|level|roof)([0-9]+)$");
    private static final MMLogger LOGGER = MMLogger.create(BuildingVolumes.class);
    private static final Map<File, Definition> CACHE = new ConcurrentHashMap<>();
    private static final Definition NONE = new Definition(Map.of(), List.of());

    private BuildingVolumes() { }

    /** Survives collapse: the placement belongs to its anchor even after that hex loses its building terrain. */
    public record Reference(String id, Coords anchor) implements Serializable { }

    /**
     * The ids of the building models the board's hexes ({@code hexes}: each by its place) belong to: saved membership,
     * including collapsed hexes, never inferred from current artwork.
     */
    public static Set<String> referenceIds(Board board, Function<Coords, Hex> hexes) {
        Set<String> result = new HashSet<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Hex hex = hexes.apply(new Coords(x, y));
                if (hex != null && hex.getBuildingReference() != null) { result.add(hex.getBuildingReference().id()); }
            }
        }
        return result;
    }
    public record Point(double x, double y, double z) { }
    public record Triangle(Point a, Point b, Point c) { }
    public record Solid(String role, List<Triangle> triangles) { }
    public record Module(String role, double lift) { }

    /** Visible module ground heights and the helper meshes in the same authored coordinate frame. */
    public record Definition(Map<String, Double> grounds, List<Solid> solids) {
        public boolean present() { return !solids.isEmpty(); }
        public boolean modular() {
            return List.of("base", "floor", "roof").stream().allMatch(prefix -> grounds.keySet().stream().anyMatch(k -> k.startsWith(prefix)));
        }

        public List<Module> modules(int levels, long seed) {
            if (!modular()) { return List.of(new Module("", 0)); }
            List<String> chosen = select(grounds.keySet(), Math.max(1, levels), seed);
            List<Module> result = new ArrayList<>();
            for (int i = 0; i < chosen.size(); i++) {
                String role = chosen.get(i);
                result.add(new Module(role, i * LEVEL - grounds.get(role)));
            }
            return List.copyOf(result);
        }

        public List<Solid> assemble(int levels, long seed) {
            List<Solid> result = new ArrayList<>();
            for (Module module : modules(levels, seed)) {
                for (Solid solid : solids) {
                    if (!solid.role().equals(module.role())) { continue; }
                    result.add(transform(solid, p -> new Point(p.x(), p.y(), p.z() + module.lift())));
                }
            }
            return List.copyOf(result);
        }
    }

    /** The same deterministic module choice used by ordinary connected kits and authored multi-hex buildings. */
    public static List<String> select(Set<String> roles, int levels, long seed) {
        var random = new SplittableRandom(seed);
        Function<String, String> pick = prefix -> {
            var options = roles.stream().filter(role -> role.startsWith(prefix)).sorted().toList();
            return options.get(random.nextInt(options.size()));
        };
        String roof = pick.apply("roof");
        List<String> result = new ArrayList<>();
        result.add(pick.apply("base"));
        for (int i = 1; i < levels; i++) { result.add(pick.apply("floor")); }
        result.add(roof);
        return List.copyOf(result);
    }

    public static long seed(BoardDecoration object) { return object.id().hashCode(); }

    /** Full turns keep the building upright; any other X/Y rotation disables its interior floors. */
    public static boolean tilted(BoardDecoration object) {
        return Math.abs(Math.IEEEremainder(object.rotationX(), 360)) > 1e-7
              || Math.abs(Math.IEEEremainder(object.rotationY(), 360)) > 1e-7;
    }

    /** Cosmetic metadata never changes coverage or revives collapsed building hexes. */
    public static boolean sameGeometry(BoardDecoration a, BoardDecoration b) {
        return a.asset().equals(b.asset()) && a.x() == b.x() && a.y() == b.y() && a.rotation() == b.rotation()
              && a.rotationX() == b.rotationX() && a.rotationY() == b.rotationY() && a.mirror() == b.mirror()
              && a.scale() == b.scale() && a.stretch().equals(b.stretch()) && a.placement().equals(b.placement())
              && a.levels() == b.levels();
    }

    public static String role(String name) {
        var match = ROLE.matcher(name == null ? "" : name);
        return match.matches() ? (match.group(1).equals("level") ? "floor" : match.group(1)) + match.group(2) : "";
    }

    public static boolean helper(String name) { return name != null && name.startsWith("building_volume"); }

    public static Definition of(String asset) {
        if (asset == null || !asset.startsWith("buildings/")) { return NONE; }
        File file = TilesetScenery.customBuildingFile(asset).getAbsoluteFile();
        if (!file.getName().endsWith(".glb")) { return NONE; }
        return CACHE.computeIfAbsent(file, BuildingVolumes::read);
    }

    public static void reload() { CACHE.clear(); }

    private static Definition read(File file) {
        if (!file.isFile()) { return NONE; }
        try (var input = Files.newInputStream(file.toPath())) {
            if (!(new GltfAssetReader().readWithoutReferences(input) instanceof GltfAssetV2 binary)) { return NONE; }
            // These are authored meshes, not material textures. Headless conversion never opens image references.
            binary.getImageReferences().forEach(image -> image.getTarget().accept(ByteBuffer.allocate(0)));
            var model = GltfModelCreatorV2.create(binary);
            List<Solid> solids = new ArrayList<>();
            Map<String, Double> grounds = new LinkedHashMap<>();
            Integer scene = binary.getGltf().getScene();
            for (NodeModel root : model.getSceneModels().get(scene == null ? 0 : scene).getNodeModels()) {
                read(root, "", false, grounds, solids);
            }
            return new Definition(Map.copyOf(grounds), List.copyOf(solids));
        } catch (Exception failure) {
            LOGGER.warn("Cannot read building volumes in {}: {}", file, failure.getMessage());
            return NONE;
        }
    }

    private static void read(NodeModel node, String parentRole, boolean inHelper, Map<String, Double> grounds, List<Solid> solids) {
        String name = node.getName() == null ? "" : node.getName();
        if (name.matches(".*-lod[1-9][0-9]*")) { return; }
        String ownRole = role(name), role = ownRole.isEmpty() ? parentRole : ownRole;
        boolean volume = inHelper || helper(name);
        List<Triangle> triangles = triangles(node);
        if (volume && !triangles.isEmpty()) { solids.add(new Solid(role, List.copyOf(triangles))); }
        else if (!role.isEmpty() && !triangles.isEmpty()) {
            double bottom = triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).mapToDouble(Point::z).min().orElse(0);
            grounds.merge(role, bottom, Math::min);
        }
        for (NodeModel child : node.getChildren()) { read(child, role, volume, grounds, solids); }
    }

    private static List<Triangle> triangles(NodeModel node) {
        List<Triangle> result = new ArrayList<>();
        float[] m = node.computeGlobalTransform(null);
        for (var mesh : node.getMeshModels()) {
            for (var primitive : mesh.getMeshPrimitiveModels()) {
                var positions = (AccessorFloatData) primitive.getAttributes().get("POSITION").getAccessorData();
                var indices = primitive.getIndices() == null ? null : primitive.getIndices().getAccessorData();
                int count = indices == null ? positions.getNumElements() : indices.getNumElements();
                for (int i = 0; i + 2 < count; i += 3) {
                    List<Point> points = new ArrayList<>(3);
                    for (int k = 0; k < 3; k++) {
                        int index = switch (indices) {
                            case null -> i + k;
                            case AccessorByteData values -> values.getInt(i + k, 0);
                            case AccessorShortData values -> values.getInt(i + k, 0);
                            case AccessorIntData values -> (int) values.getLong(i + k, 0);
                            default -> throw new IllegalArgumentException("Unsupported volume indices");
                        };
                        float x = positions.get(index, 0), y = positions.get(index, 1), z = positions.get(index, 2);
                        points.add(new Point(m[0] * x + m[4] * y + m[8] * z + m[12],
                              -(m[2] * x + m[6] * y + m[10] * z + m[14]), m[1] * x + m[5] * y + m[9] * z + m[13]));
                    }
                    result.add(new Triangle(points.get(0), points.get(1), points.get(2)));
                }
            }
        }
        return result;
    }

    private static Solid transform(Solid solid, Function<Point, Point> frame) {
        return new Solid(solid.role(), solid.triangles().stream().map(t -> new Triangle(frame.apply(t.a()), frame.apply(t.b()), frame.apply(t.c()))).toList());
    }

    public static List<Solid> placed(Coords anchor, BoardDecoration object, double base) {
        double x = HexTile.centerX(anchor, HexTile.MODEL_WIDTH) + object.x() * HexTile.MODEL_WIDTH;
        double y = HexTile.centerY(anchor, HexTile.HEIGHT) + object.y() * HexTile.HEIGHT;
        return of(object.asset()).assemble(object.levels(), seed(object)).stream().map(solid -> transform(solid, p -> {
            double[] v = object.rotateVector(p.x() * object.scale() * object.stretch().x() * (object.mirror() ? -1 : 1),
                  p.y() * object.scale() * object.stretch().y(), p.z() * object.scale() * object.stretch().z());
            return new Point(x + v[0], y + v[1], base * LEVEL + v[2]);
        })).map(solid -> object.mirror() ? new Solid(solid.role(), solid.triangles().stream()
              .map(t -> new Triangle(t.a(), t.c(), t.b())).toList()) : solid).toList();
    }

    /**
     * Exposed upward faces of structural volumes, excluding decorative geometry. Slopes are projected in level-high
     * bands so a tilted volume supplies horizontal gameplay roofs at different heights. Higher bands cover lower ones.
     */
    public static Map<Double, Area> roofs(List<Solid> solids) {
        Map<Double, Path2D.Double> paths = new java.util.TreeMap<>(Comparator.reverseOrder());
        for (Solid solid : solids) {
            for (Triangle t : solid.triangles()) {
                double cross = (t.b().x() - t.a().x()) * (t.c().y() - t.a().y()) - (t.b().y() - t.a().y()) * (t.c().x() - t.a().x());
                if (cross <= .00001) { continue; }
                double min = Math.min(t.a().z(), Math.min(t.b().z(), t.c().z()));
                double max = Math.max(t.a().z(), Math.max(t.b().z(), t.c().z()));
                if (max - min <= .001) {
                    triangle(paths.computeIfAbsent(Math.rint(max * 1000) / 1000, ignored -> new Path2D.Double()), t);
                    continue;
                }
                for (double top = (Math.floor(min / LEVEL) + 1) * LEVEL; top < max + LEVEL - .00001; top += LEVEL) {
                    var band = clipHeight(clipHeight(List.of(t.a(), t.b(), t.c()), top - LEVEL, true), top, false);
                    if (band.size() < 3) { continue; }
                    Path2D.Double path = paths.computeIfAbsent(top, ignored -> new Path2D.Double());
                    for (int i = 1; i + 1 < band.size(); i++) { triangle(path, new Triangle(band.getFirst(), band.get(i), band.get(i + 1))); }
                }
            }
        }
        Map<Double, Area> caps = new java.util.TreeMap<>(Comparator.reverseOrder());
        paths.forEach((z, path) -> caps.put(z, new Area(path)));
        Area covered = new Area();
        for (Area cap : caps.values()) {
            Area original = new Area(cap);
            cap.subtract(covered);
            covered.add(original);
        }
        return caps;
    }

    /** Clip one convex face at a horizontal plane, retaining its original winding. */
    private static List<Point> clipHeight(List<Point> points, double z, boolean above) {
        List<Point> result = new ArrayList<>();
        if (points.isEmpty()) { return result; }
        Point a = points.getLast();
        for (Point b : points) {
            boolean keepA = above ? a.z() >= z : a.z() <= z, keepB = above ? b.z() >= z : b.z() <= z;
            if (keepA != keepB) {
                double t = (z - a.z()) / (b.z() - a.z());
                result.add(new Point(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()), z));
            }
            if (keepB) { result.add(b); }
            a = b;
        }
        return result;
    }

    /** A covered hex's baked gameplay height, and the area of the placement's exposed roofs over it (model units). */
    public record Cover(int height, double area) { }

    /** Covered hexes and baked gameplay heights ({@link #covers}). */
    public static Map<Coords, Integer> coverage(Coords anchor, BoardDecoration object, int width, int height, Function<Coords, Hex> board) {
        Map<Coords, Integer> result = new LinkedHashMap<>();
        covers(anchor, object, width, height, board).forEach((at, cover) -> result.put(at, cover.height()));
        return result;
    }

    /** Covered hexes, what each holds and how much of it. Largest roof area wins; equal areas choose the taller roof. */
    public static Map<Coords, Cover> covers(Coords anchor, BoardDecoration object, int width, int height, Function<Coords, Hex> board) {
        double base = object.placement().level(board.apply(anchor));
        Map<Double, Area> roofs = roofs(placed(anchor, object, base));
        Area footprint = new Area();
        roofs.values().forEach(footprint::add);
        var bounds = footprint.getBounds2D();
        Map<Coords, Cover> result = new LinkedHashMap<>();
        int minX = Math.max(0, (int) Math.floor(bounds.getMinX() / (HexTile.MODEL_WIDTH * .75)) - 1);
        int maxX = Math.min(width - 1, (int) Math.ceil(bounds.getMaxX() / (HexTile.MODEL_WIDTH * .75)) + 1);
        int minY = Math.max(0, (int) Math.floor(-bounds.getMaxY() / HexTile.HEIGHT) - 2);
        int maxY = Math.min(height - 1, (int) Math.ceil(-bounds.getMinY() / HexTile.HEIGHT) + 1);
        Map<Integer, Area> exposed = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Coords at = new Coords(x, y);
                // Tilting around the base can bury part of a volume. Buried surfaces contribute no building height.
                int ground = board.apply(at).getLevel();
                Area cell = hex(at);
                Area covered = new Area(exposed.computeIfAbsent(ground, ignored -> {
                    Area above = new Area();
                    roofs.forEach((z, area) -> { if (z > ground * LEVEL + .00001) { above.add(area); } });
                    return above;
                }));
                covered.intersect(cell);
                double cellArea = area(cell), coveredArea = area(covered);
                if (coveredArea + cellArea * 1e-6 < cellArea * .5) { continue; }
                double roof = dominant(roofs, cell, ground * LEVEL + .00001);
                result.put(at, new Cover(Math.max(1, (int) Math.ceil(roof / LEVEL - ground - 1e-5)), coveredArea));
            }
        }
        return result;
    }

    /** The board's actual hex perimeter, counter-clockwise in model units, including its staggered recesses. */
    public static List<Point> boardOutline(int width, int height) {
        Set<Coords> border = new LinkedHashSet<>();
        for (int x = 0; x < width; x++) { border.add(new Coords(x, 0)); border.add(new Coords(x, height - 1)); }
        for (int y = 0; y < height; y++) { border.add(new Coords(0, y)); border.add(new Coords(width - 1, y)); }
        Map<Point, Point> edges = new LinkedHashMap<>();
        for (Coords at : border) {
            for (int corner = 0; corner < 6; corner++) {
                Coords neighbour = at.translated(HexTile.edgeDirection(corner));
                if (neighbour.getX() >= 0 && neighbour.getX() < width && neighbour.getY() >= 0 && neighbour.getY() < height) { continue; }
                int next = (corner + 1) % 6;
                edges.put(new Point(HexTile.cornerX(at, corner, HexTile.MODEL_WIDTH), HexTile.cornerY(at, corner, HexTile.HEIGHT), 0),
                      new Point(HexTile.cornerX(at, next, HexTile.MODEL_WIDTH), HexTile.cornerY(at, next, HexTile.HEIGHT), 0));
            }
        }
        List<Point> result = new ArrayList<>();
        Point first = edges.keySet().iterator().next(), point = first;
        do { result.add(point); point = edges.get(point); } while (!point.equals(first));
        return List.copyOf(result);
    }

    /** Geometric structural area retained by the board; independent of half-hex gameplay coverage and decorative props. */
    public static double onBoardFraction(Coords anchor, BoardDecoration object, int width, int height) {
        Path2D.Double path = new Path2D.Double();
        for (var solid : placed(anchor, object, 0)) {
            for (var triangle : solid.triangles()) { triangle(path, triangle); }
        }
        Area board = polygon(boardOutline(width, height));
        if (board.contains(path.getBounds2D())) { return 1; }
        Area footprint = new Area(path);
        double total = area(footprint);
        if (total == 0) { return 0; }
        footprint.intersect(board);
        return area(footprint) / total;
    }

    /** Nonzero winding unions the projected triangles in one sweep; normalize winding so back faces cannot cancel. */
    private static void triangle(Path2D.Double path, Triangle t) {
        Point a = t.a(), b = t.b(), c = t.c();
        double cross = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        if (cross == 0) { return; }
        if (cross < 0) { Point swap = b; b = c; c = swap; }
        path.moveTo(a.x(), a.y()); path.lineTo(b.x(), b.y()); path.lineTo(c.x(), c.y()); path.closePath();
    }

    /** Largest projected top area within one hex; floating point seam tolerances still make exact ties choose taller. */
    public static double dominant(Map<Double, Area> roofs, Area cell) {
        return dominant(roofs, cell, Double.NEGATIVE_INFINITY);
    }

    private static double dominant(Map<Double, Area> roofs, Area cell, double above) {
        double largest = -1, roof = 0, tolerance = area(cell) * 1e-6;
        for (var cap : roofs.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.reverseOrder())).toList()) {
            if (cap.getKey() <= above) { continue; }
            Area intersection = new Area(cap.getValue()); intersection.intersect(cell);
            double size = area(intersection);
            if (size > largest + tolerance) { largest = size; roof = cap.getKey(); }
        }
        return roof;
    }

    public static Area polygon(List<Point> points) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(points.getFirst().x(), points.getFirst().y());
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x(), points.get(i).y()); }
        path.closePath();
        return new Area(path);
    }

    public static Area hex(Coords at) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) { points.add(new Point(HexTile.cornerX(at, i, HexTile.MODEL_WIDTH), HexTile.cornerY(at, i, HexTile.HEIGHT), 0)); }
        return polygon(points);
    }

    public static double area(Area shape) {
        double[] p = new double[6]; double sum = 0, firstX = 0, firstY = 0, x = 0, y = 0;
        for (var it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { firstX = x = p[0]; firstY = y = p[1]; }
                case PathIterator.SEG_LINETO -> { sum += x * p[1] - p[0] * y; x = p[0]; y = p[1]; }
                case PathIterator.SEG_CLOSE -> sum += x * firstY - firstX * y;
                default -> { }
            }
        }
        return Math.abs(sum) * .5;
    }

    private record Cut(long x, long y) {
        static Cut at(Point a, Point b, double z) {
            double t = (z - a.z()) / (b.z() - a.z());
            return new Cut(Math.round((a.x() + t * (b.x() - a.x())) * 1000), Math.round((a.y() + t * (b.y() - a.y())) * 1000));
        }
    }

    /** Cross-section of closed authored solids, retaining courtyards and changing floor outlines. */
    public static Area section(List<Solid> solids, double z) {
        Area result = new Area();
        for (Solid solid : solids) {
            Map<Cut, Set<Cut>> edges = new LinkedHashMap<>();
            for (Triangle t : solid.triangles()) {
                List<Point> points = List.of(t.a(), t.b(), t.c()); List<Cut> cuts = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    Point a = points.get(i), b = points.get((i + 1) % 3);
                    if ((a.z() > z) != (b.z() > z)) { cuts.add(Cut.at(a, b, z)); }
                }
                if (cuts.size() != 2 || cuts.get(0).equals(cuts.get(1))) { continue; }
                edges.computeIfAbsent(cuts.get(0), ignored -> new LinkedHashSet<>()).add(cuts.get(1));
                edges.computeIfAbsent(cuts.get(1), ignored -> new LinkedHashSet<>()).add(cuts.get(0));
            }
            Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            for (Cut start : edges.keySet()) {
                while (!edges.get(start).isEmpty()) {
                    Cut current = start; path.moveTo(start.x() / 1000.0, start.y() / 1000.0);
                    do {
                        if (edges.get(current).isEmpty()) { break; }
                        Cut next = edges.get(current).iterator().next(); edges.get(current).remove(next); edges.get(next).remove(current);
                        current = next; path.lineTo(current.x() / 1000.0, current.y() / 1000.0);
                    } while (!current.equals(start));
                    if (current.equals(start)) { path.closePath(); }
                }
            }
            result.add(new Area(path));
        }
        return result;
    }
}
