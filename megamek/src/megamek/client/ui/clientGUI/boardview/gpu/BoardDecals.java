/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;

/** Artwork on the first solid support, clipped at drops; shared by both terrain presentations. */
final class BoardDecals {
    static final float LIFT = .08f;
    private static final float EPSILON = .0001f;
    /** Each decal image's alpha ({@link Opacity}), read once per id on any thread, headless too. */
    private static final Map<String, BoardKit<Alpha>> ALPHA = new ConcurrentHashMap<>();

    private record Receiver(BoardSurface.Face face, Vector3 normal, Area footprint, Rectangle2D bounds, boolean ground) {
        float height(Vector3 point) {
            Vector3 a = face.a();
            return a.z - (normal.x * (point.x - a.x) + normal.y * (point.y - a.y)) / normal.z;
        }
    }

    /** Weld coincident corners across material/normal seams, without joining vertically separated levels. */
    private record Corner(int x, int y, int z, boolean ground) {
        Corner(Vector3 point, boolean ground) {
            this(Math.round(point.x * 1000), Math.round(point.y * 1000), Math.round(point.z * 1000), ground);
        }
    }

    private BoardDecals() { }

    /** A decal has one owner and one transform, even when several chunks receive its paint. */
    record Stamp(Coords owner, BoardDecoration object) { }

    /** Built once per scene update on the terrain worker, then borrowed by every chunk and camera. */
    static Map<Coords, List<Stamp>> index(BoardScene scene) {
        Map<Coords, List<Stamp>> result = new HashMap<>();
        for (var tile : scene.tiles()) {
            // A composition may repeat one decoration on several features; index the logical object only once.
            Set<String> seen = new HashSet<>();
            for (var feature : tile.features()) {
                BoardDecoration object = feature.decoration();
                if (object == null || !object.kind().equals("decal") || !seen.add(object.id())) { continue; }
                Stamp stamp = new Stamp(tile.coords(), object);
                for (Coords target : footprint(tile.coords(), object, scene.width(), scene.height())) {
                    result.computeIfAbsent(target, key -> new ArrayList<>()).add(stamp);
                }
            }
        }
        var order = java.util.Comparator.comparingInt((Stamp stamp) -> stamp.object().drawOrder())
              .thenComparing(stamp -> stamp.object().id());
        result.replaceAll((at, values) -> values.stream().sorted(order).toList());
        return Map.copyOf(result);
    }

    /** Untouched owners keep their footprint lists; a terrain edit need not reproject every decal on the map. */
    static Map<Coords, List<Stamp>> update(BoardScene before, BoardScene after, Map<Coords, List<Stamp>> previous) {
        Set<Coords> owners = new HashSet<>(), affected = new HashSet<>();
        for (int i = 0; i < after.tiles().size(); i++) {
            var old = before.tiles().get(i); var next = after.tiles().get(i);
            if (old == next || paint(old).equals(paint(next))) { continue; }
            owners.add(next.coords());
            for (var object : paint(old)) { affected.addAll(footprint(old.coords(), object, after.width(), after.height())); }
            for (var object : paint(next)) { affected.addAll(footprint(next.coords(), object, after.width(), after.height())); }
        }
        if (owners.isEmpty()) { return previous; }
        Map<Coords, List<Stamp>> result = new HashMap<>(previous);
        for (Coords at : affected) {
            result.put(at, new ArrayList<>(previous.getOrDefault(at, List.of()).stream().filter(s -> !owners.contains(s.owner())).toList()));
        }
        for (Coords owner : owners) {
            for (var object : paint(after.tile(owner))) {
                Stamp stamp = new Stamp(owner, object);
                for (Coords at : footprint(owner, object, after.width(), after.height())) { result.get(at).add(stamp); }
            }
        }
        var order = java.util.Comparator.comparingInt((Stamp stamp) -> stamp.object().drawOrder()).thenComparing(stamp -> stamp.object().id());
        for (Coords at : affected) {
            if (result.get(at).isEmpty()) { result.remove(at); }
            else { result.put(at, result.get(at).stream().sorted(order).toList()); }
        }
        return Map.copyOf(result);
    }

    private static List<BoardDecoration> paint(BoardScene.Tile tile) {
        return tile.features().stream().map(BoardScene.Feature::decoration).filter(java.util.Objects::nonNull)
              .filter(d -> d.kind().equals("decal")).distinct().toList();
    }

    /** Symmetric difference of paint contents includes removed, moved, resized, reordered and replaced decals. */
    static Set<Coords> changed(Map<Coords, List<Stamp>> before, Map<Coords, List<Stamp>> after) {
        if (before == after) { return Set.of(); }
        Set<Coords> result = new HashSet<>(before.keySet()); result.addAll(after.keySet());
        result.removeIf(at -> java.util.Objects.equals(before.get(at), after.get(at)));
        return result;
    }

    static Set<Coords> footprint(Coords owner, BoardDecoration object, int width, int height) {
        Area paint = footprint(owner, object);
        Rectangle2D bounds = paint.getBounds2D();
        // Bound the search by the rotated rectangle, not by every hex or a guessed fixed neighbour radius.
        int minX = (int) Math.max(0, Math.floor((bounds.getMinX() / BoardGeometry.width() - 1) / .75));
        int maxX = (int) Math.min(width - 1, Math.ceil(bounds.getMaxX() / BoardGeometry.width() / .75));
        int minY = (int) Math.max(0, Math.floor(-bounds.getMaxY() / BoardGeometry.height() - 1.5));
        int maxY = (int) Math.min(height - 1, Math.ceil(-bounds.getMinY() / BoardGeometry.height()));
        Set<Coords> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Coords at = new Coords(x, y);
                Area intersection = hex(at); intersection.intersect(paint);
                if (!intersection.isEmpty()) { result.add(at); }
            }
        }
        return result;
    }

    private static Area hex(Coords coords) {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 6; i++) { corners.add(BoardGeometry.corner(coords, 0, i)); }
        return area(corners);
    }

    private static Area footprint(Coords owner, BoardDecoration object) {
        Area result = area(paintRectangle(owner, object));
        if (object.clipToHex()) { result.intersect(hex(owner)); }
        return result;
    }

    /** A tilted decal is a rotated stamp projected onto its receiving surface, not a floating quad. */
    static List<Vector3> paintRectangle(Coords owner, BoardDecoration object) {
        Vector3 anchor = anchor(owner, object);
        double[] size = size(object);
        List<Vector3> corners = new ArrayList<>();
        for (int[] corner : new int[][] { {-1, -1}, {1, -1}, {1, 1}, {-1, 1} }) {
            double[] point = object.rotateVector(corner[0] * size[0] / 2, corner[1] * size[1] / 2, 0);
            corners.add(new Vector3((float) point[0], (float) point[1], 0).add(anchor));
        }
        Vector3 a = corners.get(0), b = corners.get(1), c = corners.get(2);
        float cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        if (Math.abs(cross) < .0001f) { return List.of(); }
        if (cross < 0) { java.util.Collections.reverse(corners); }
        return corners;
    }

    /** Inverse of the stamp's projected XY basis; shared by rendering and footprint clipping. */
    static PaintUv paintUv(BoardDecoration object) {
        double[] size = size(object);
        double[] x = object.rotateVector(size[0], 0, 0);
        double[] y = object.rotateVector(0, size[1], 0);
        double determinant = x[0] * y[1] - x[1] * y[0];
        double mirror = object.mirror() ? -1 : 1;
        return new PaintUv(y[1] / determinant * mirror, -y[0] / determinant * mirror,
              x[1] / determinant, -x[0] / determinant);
    }

    /** The stamp's width and length in world units: its art's footprint ({@link BoardDecalArt}) times scale and stretch. */
    private static double[] size(BoardDecoration object) {
        var art = BoardDecalArt.footprint(object.asset());
        return new double[] { art.width() * BoardGeometry.hexScale() * object.scale() * object.stretch().x(),
              art.height() * BoardGeometry.hexScale() * object.scale() * object.stretch().y() };
    }

    record PaintUv(double ux, double uy, double vx, double vy) {
        float u(double x, double y) { return (float) (.5 + ux * x + uy * y); }
        float v(double x, double y) { return (float) (.5 + vx * x + vy * y); }
    }

    private static Vector3 anchor(Coords coords, BoardDecoration object) {
        return BoardGeometry.center(coords, 0).add((float) object.x() * BoardGeometry.width(),
              (float) object.y() * BoardGeometry.height(), 0);
    }

    /**
     * The legacy paint overlay the full 3D render draws on a hex (its layer of legacy emblems, rubble paths, gravel,
     * deposits and transitions): without the limbs where the scene draws those as models.
     */
    static BoardScene.Pixels legacyPaint(BoardScene.Tile tile) {
        return tile.decalsWithoutLimbs() != null ? tile.decalsWithoutLimbs() : tile.decals();
    }

    /** Drop the decal images' alpha with the other board kits on an asset reload. */
    static void reload() { ALPHA.clear(); }

    /** An image's alpha by texel, row 0 at the top as the paint texture samples it; empty for a missing image. */
    private record Alpha(int width, int height, byte[] values) {
        static final Alpha NONE = new Alpha(0, 0, new byte[0]);

        static Alpha of(String id) {
            File file = BoardDecalArt.image(id);
            try {
                BufferedImage image = file.isFile() ? ImageIO.read(file) : null;
                if (image == null) { return NONE; }
                int width = image.getWidth(), height = image.getHeight();
                int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
                byte[] values = new byte[argb.length];
                for (int i = 0; i < argb.length; i++) { values[i] = (byte) (argb[i] >>> 24); }
                return new Alpha(width, height, values);
            } catch (IOException error) {
                // Paint that cannot be read draws nothing (GpuAssets.decorationPaint), so it clears no cover either.
                return NONE;
            }
        }

        /** The nearest texel's alpha (0..1) at texture coordinates (u, v); 0 outside the image. */
        float at(float u, float v) {
            if (!(u >= 0 && u < 1 && v >= 0 && v < 1)) { return 0; }
            return (values[(int) (v * height) * width + (int) (u * width)] & 255) / 255f;
        }
    }

    /**
     * How opaque the paint on one hex's ground is at a world point: the placed decals that paint its ground (each
     * image's alpha at its drawn transform, so a round emblem leaves no square patch) and the hex's legacy paint overlay,
     * composited. Every 3D view's ground cover grows by one minus this ({@link BoardPlants}), so nothing grows through
     * paint. Each paint counts with its largest alpha within one model px of the point, the margin cover keeps from a
     * road's shoulder. Built once per hex on the terrain worker; roofs, decks, industrial tops, fuel tanks and ice grow
     * no cover, so only paint on the ground counts.
     */
    static final class Opacity {
        /** One paint's alpha (0..1) at a world point, and whether a point lies within its reach plus the margin. */
        private interface Paint {
            float alpha(float x, float y);

            default boolean near(float x, float y) { return true; }
        }

        /** A placed decal at its drawn transform ({@link #paintUv}); {@code du}, {@code dv} are the margin in u and v. */
        private record Placed(BoardDecoration object, Coords owner, float x, float y, PaintUv uv, float du, float dv,
              Alpha image) implements Paint {
            @Override public boolean near(float px, float py) {
                float u = uv.u(px - x, py - y), v = uv.v(px - x, py - y);
                return u >= -du && u <= 1 + du && v >= -dv && v <= 1 + dv;
            }

            @Override public float alpha(float px, float py) {
                if (object.clipToHex() && !BoardGeometry.contains(owner, px, py)) { return 0; }
                return image.at(uv.u(px - x, py - y), uv.v(px - x, py - y));
            }
        }

        /** The legacy overlay, mapped as the ground's top vertices map it ({@code GpuTerrain.topVertex}). */
        private record Legacy(Coords coords, BoardScene.Pixels pixels) implements Paint {
            @Override public float alpha(float x, float y) {
                float u = .5f + (x - BoardGeometry.centerX(coords)) / BoardGeometry.width() * BoardRim.GROUND_UV_SCALE;
                float v = .5f - (y - BoardGeometry.centerY(coords)) / BoardGeometry.height() * BoardRim.GROUND_UV_SCALE;
                if (!(u >= 0 && u < 1 && v >= 0 && v < 1)) { return 0; }
                return (pixels.rgba((int) (v * pixels.height()) * pixels.width() + (int) (u * pixels.width())) & 255) / 255f;
            }
        }

        private final List<Paint> paints;

        private Opacity(List<Paint> paints) { this.paints = paints; }

        /** The paint on {@code tile}'s ground from its stamps ({@link #index}), or null when nothing paints it. */
        static Opacity of(BoardScene.Tile tile, List<Stamp> stamps) {
            float margin = BoardGeometry.hexScale();
            List<Paint> paints = new ArrayList<>();
            for (Stamp stamp : stamps) {
                var object = stamp.object();
                var receiver = object.placement().receiver();
                if (receiver == null || !receiver.terrain().equals("ground")) { continue; }
                Alpha image = ALPHA.computeIfAbsent(object.asset(), id -> new BoardKit<>(() -> Alpha.of(id))).get();
                if (image.width() == 0) { continue; }
                PaintUv uv = paintUv(object);
                Vector3 anchor = anchor(stamp.owner(), object);
                paints.add(new Placed(object, stamp.owner(), anchor.x, anchor.y, uv,
                      (float) Math.hypot(uv.ux(), uv.uy()) * margin, (float) Math.hypot(uv.vx(), uv.vy()) * margin, image));
            }
            BoardScene.Pixels legacy = legacyPaint(tile);
            if (legacy != null) { paints.add(new Legacy(tile.coords(), legacy)); }
            return paints.isEmpty() ? null : new Opacity(List.copyOf(paints));
        }

        /** The paint's opacity (0..1) at world (x, y): each paint's largest alpha within the margin, composited. */
        float at(float x, float y) {
            float margin = BoardGeometry.hexScale(), clear = 1;
            for (Paint paint : paints) {
                // Most roots lie outside a decal and its margin: one transform and a bounds test.
                if (!paint.near(x, y)) { continue; }
                float largest = paint.alpha(x, y);
                for (int i = 0; i < 4 && largest < 1; i++) {
                    largest = Math.max(largest, paint.alpha(x + (i == 0 ? margin : i == 1 ? -margin : 0),
                          y + (i == 2 ? margin : i == 3 ? -margin : 0)));
                }
                clear *= 1 - largest;
            }
            return 1 - clear;
        }
    }

    /** Finished ground is a height field; roofs and decks can overlap it and each other. Liquids are never supplied. */
    static List<BoardSurface.Face> project(Coords coords, List<BoardSurface.Face> ground, List<BoardSurface.Face> raised) {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 6; i++) { corners.add(BoardGeometry.corner(coords, 0, i)); }
        return project(BoardGeometry.center(coords, 0), area(corners), ground, raised);
    }

    /** Historical callers project on the owning hex. */
    static List<BoardSurface.Face> project(Coords coords, List<BoardSurface.Face> raised,
          BoardDecoration object) {
        return project(coords, coords, raised, object);
    }

    /** Each recipient owns its triangles. UVs still use the owner's transform, so chunk seams stay continuous. */
    static List<BoardSurface.Face> project(Coords owner, Coords recipient, List<BoardSurface.Face> raised,
          BoardDecoration object) {
        if (!object.clipToHex() && object.placement().receiver().terrain().equals("ground")) {
            return projectGround(owner, recipient, raised, object);
        }
        Area clip = footprint(owner, object); clip.intersect(hex(recipient));
        Vector3 placed = anchor(owner, object);
        // A clipped piece of a larger emblem (a 7-hex set missing a piece) centres beyond its hex: it lies on its hex's support.
        Vector3 anchor = !object.clipToHex() || BoardGeometry.contains(owner, placed.x, placed.y) ? placed
              : BoardGeometry.center(owner, 0);
        if (object.clipToHex() && raised.stream().noneMatch(face -> Float.isFinite(face.height(anchor.x, anchor.y)))) {
            return List.of();
        }
        // Unbounded paint drapes on the explicitly chosen surface in every recipient, including lower ground.
        return project(object.clipToHex() ? anchor : null, clip, List.of(), raised);
    }

    /** Ground is a height field: its triangles do not occlude each other. Clip each once, without pairwise Areas. */
    private static List<BoardSurface.Face> projectGround(Coords owner, Coords recipient, List<BoardSurface.Face> faces,
          BoardDecoration object) {
        List<Vector3> rectangle = paintRectangle(owner, object), hex = new ArrayList<>();
        if (rectangle.isEmpty()) { return List.of(); }
        for (int i = 0; i < 6; i++) { hex.add(BoardGeometry.corner(recipient, 0, i)); }
        List<BoardSurface.Face> result = new ArrayList<>();
        for (var face : faces) {
            if (!upward(face)) { continue; }
            List<Vector3> polygon = BoardTacticalGeometry.clip(
                  BoardTacticalGeometry.clip(List.of(face.a(), face.b(), face.c()), rectangle), hex);
            for (int i = 1; i + 1 < polygon.size(); i++) {
                var triangle = new BoardSurface.Face(polygon.getFirst(), polygon.get(i), polygon.get(i + 1), BoardSurface.Finish.TOP);
                if (upward(triangle)) { result.add(triangle); }
            }
        }
        return result;
    }

    private static List<BoardSurface.Face> project(Vector3 anchor, Area hex, List<BoardSurface.Face> ground,
          List<BoardSurface.Face> raised) {
        List<Receiver> receivers = new ArrayList<>();
        var reach = hex.getBounds2D();
        for (var faces : List.of(raised, ground)) {
            for (var face : faces) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                if (normal.z <= EPSILON) { continue; }
                // A face whose bounds lie apart from the clip's shares no area with it: most of a roof, under a small decal.
                Vector3 a = face.a(), b = face.b(), c = face.c();
                if (Math.max(a.x, Math.max(b.x, c.x)) < reach.getMinX() || Math.min(a.x, Math.min(b.x, c.x)) > reach.getMaxX()
                      || Math.max(a.y, Math.max(b.y, c.y)) < reach.getMinY() || Math.min(a.y, Math.min(b.y, c.y)) > reach.getMaxY()) {
                    continue;
                }
                Area footprint = area(List.of(face.a(), face.b(), face.c()));
                footprint.intersect(hex);
                if (!footprint.isEmpty()) {
                    receivers.add(new Receiver(face, normal, footprint, footprint.getBounds2D(), faces == ground));
                }
            }
        }
        if (anchor != null) { receivers = supportLevel(anchor, receivers); }
        if (raised.isEmpty()) { return receivers.stream().map(Receiver::face).toList(); }
        List<BoardSurface.Face> result = new ArrayList<>();
        for (int i = 0; i < receivers.size(); i++) {
            Receiver receiver = receivers.get(i);
            Area visible = new Area(receiver.footprint());
            for (int j = 0; j < receivers.size() && !visible.isEmpty(); j++) {
                Receiver other = receivers.get(j);
                if (i == j || receiver.ground() && other.ground() || !receiver.bounds().intersects(other.bounds())) { continue; }
                // Clip at the planes' intersection too: sorting by average height fails on sloped roofs/decks.
                List<Vector3> above = above(other.face(), receiver, j < i ? -EPSILON : EPSILON);
                if (above.size() >= 3) { visible.subtract(area(above)); }
            }
            for (var triangle : BoardTacticalGeometry.flat(visible, -1)) {
                Vector3 a = triangle.a(), b = triangle.b(), c = triangle.c();
                a.z = receiver.height(a); b.z = receiver.height(b); c.z = receiver.height(c);
                result.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
            }
        }
        return result;
    }

    /**
     * Never resume artwork on a lower roof, terrace or the ground below its anchor. Keep connected slopes whole:
     * clipping every vertex below the anchor's Z would cut a pitched roof in half. Vertical risers are not receivers,
     * so their upper and lower surfaces remain separate components.
     */
    private static List<Receiver> supportLevel(Vector3 anchor, List<Receiver> receivers) {
        int[] parents = new int[receivers.size()];
        Map<Corner, Integer> corners = new HashMap<>();
        float height = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < receivers.size(); i++) {
            parents[i] = i;
            var face = receivers.get(i).face();
            height = Math.max(height, face.height(anchor.x, anchor.y));
            for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                Integer previous = corners.putIfAbsent(new Corner(point, receivers.get(i).ground()), i);
                if (previous != null) { parents[root(parents, i)] = root(parents, previous); }
            }
        }
        float[] highest = new float[parents.length];
        Arrays.fill(highest, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < receivers.size(); i++) {
            var face = receivers.get(i).face();
            int root = root(parents, i);
            highest[root] = Math.max(highest[root], Math.max(face.a().z, Math.max(face.b().z, face.c().z)));
        }
        List<Receiver> result = new ArrayList<>();
        for (int i = 0; i < receivers.size(); i++) {
            if (highest[root(parents, i)] >= height - EPSILON) { result.add(receivers.get(i)); }
        }
        return result;
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    static boolean upward(BoardSurface.Face face) {
        return (face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x) > EPSILON;
    }

    private static List<Vector3> above(BoardSurface.Face face, Receiver receiver, float threshold) {
        List<Vector3> result = new ArrayList<>(4);
        Vector3 previous = face.c();
        float before = previous.z - receiver.height(previous) - threshold;
        for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
            float after = point.z - receiver.height(point) - threshold;
            if ((before >= 0) != (after >= 0)) {
                result.add(new Vector3(previous).lerp(point, before / (before - after)));
            }
            if (after >= 0) { result.add(point); }
            previous = point;
            before = after;
        }
        return result;
    }

    private static Area area(List<Vector3> points) {
        if (points.isEmpty()) { return new Area(); }
        Path2D.Float path = new Path2D.Float();
        path.moveTo(points.getFirst().x, points.getFirst().y);
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
        path.closePath();
        return new Area(path);
    }
}
