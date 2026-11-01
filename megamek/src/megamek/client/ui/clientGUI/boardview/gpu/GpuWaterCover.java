/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * Sculpted faces split at the drawn water triangles, so absorption cannot escape onto an exposed bank or cliff. An
 * instance holds the drawn water triangles that can cover a hex's faces, in the order they are applied, binned on a
 * grid so a face tests the few triangles near it rather than every triangle of up to seven water hexes. The pieces
 * keep the terrain's vertex encoding (GpuTerrain.sculptVertex).
 */
final class GpuWaterCover {
    private final List<WaterTop> tops;
    private final float minX, minY, cell;
    private final int columns, rows;
    private final int[][] cells;
    private final int[] seen;
    private int[] candidates = new int[64];
    private int stamp;

    private GpuWaterCover(List<WaterTop> tops) {
        this.tops = tops;
        float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        for (WaterTop water : tops) {
            left = Math.min(left, water.minX());
            bottom = Math.min(bottom, water.minY());
            right = Math.max(right, water.maxX());
            top = Math.max(top, water.maxY());
        }
        // Water triangles span a fraction of a hex; cells of a sixth of a hex width hold a few of them each.
        cell = BoardGeometry.width() / 6;
        minX = left;
        minY = bottom;
        columns = tops.isEmpty() ? 0 : (int) ((right - left) / cell) + 1;
        rows = tops.isEmpty() ? 0 : (int) ((top - bottom) / cell) + 1;
        cells = new int[columns * rows][];
        seen = new int[tops.size()];
        int[] counts = new int[cells.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < tops.size(); i++) {
                WaterTop water = tops.get(i);
                for (int r = row(water.minY()); r <= row(water.maxY()); r++) {
                    for (int c = column(water.minX()); c <= column(water.maxX()); c++) {
                        if (pass == 0) { counts[r * columns + c]++; } else { cells[r * columns + c][counts[r * columns + c]++] = i; }
                    }
                }
            }
            if (pass == 0) {
                for (int i = 0; i < cells.length; i++) { cells[i] = new int[counts[i]]; }
                Arrays.fill(counts, 0);
            }
        }
    }

    private int column(float x) { return Math.clamp((int) ((x - minX) / cell), 0, columns - 1); }

    private int row(float y) { return Math.clamp((int) ((y - minY) / cell), 0, rows - 1); }

    /** The triangles whose bounds meet a face's, below a ceiling elevation, in application order. */
    private int collect(float faceMinX, float faceMaxX, float faceMinY, float faceMaxY, float faceMinZ, int ceiling) {
        if (tops.isEmpty() || faceMaxX < minX || faceMaxY < minY
              || faceMinX > minX + columns * cell || faceMinY > minY + rows * cell) { return 0; }
        stamp++;
        int count = 0;
        int lastRow = row(faceMaxY), lastColumn = column(faceMaxX);
        for (int r = row(faceMinY); r <= lastRow; r++) {
            for (int c = column(faceMinX); c <= lastColumn; c++) {
                for (int index : cells[r * columns + c]) {
                    if (seen[index] == stamp) { continue; }
                    seen[index] = stamp;
                    WaterTop water = tops.get(index);
                    if (water.water().tile.elevation() >= ceiling
                          || !water.reaches(faceMinX, faceMaxX, faceMinY, faceMaxY, faceMinZ)) { continue; }
                    if (count == candidates.length) { candidates = Arrays.copyOf(candidates, count * 2); }
                    candidates[count++] = index;
                }
            }
        }
        Arrays.sort(candidates, 0, count);
        return count;
    }

    private WaterTop candidate(int i) { return tops.get(candidates[i]); }

    /** The open water whose drawn triangles clip a sculpted face: its own hex's, or the one past its land edge. */
    static BoardSurface clippingWater(BoardSurface surface, BoardSurface.Face face, BoardScene scene,
          Map<Coords, BoardSurface> surfaces) {
        if (GpuTerrain.openWater(surface.tile) != null) { return surface; }
        if (face.landEdge() < 0) { return null; }
        BoardScene.Tile adjacent = GpuTerrain.openWater(scene.tile(surface.tile.coords()
              .translated(BoardGeometry.edgeDirection(face.landEdge()))));
        return adjacent == null ? null
              : surfaces.computeIfAbsent(adjacent.coords(), key -> new BoardSurface(scene, adjacent));
    }

    /**
     * Whether water leaves a sculpted face whole and dry: none can clip it (clippingWater), or coveredPolygons hands it
     * back as it is. One wholly under water comes back whole but tinted with its water optics, which its dry chart would
     * lose. {@code coverage}: the clipping waters' covers, kept across faces.
     */
    static boolean whole(BoardSurface surface, BoardSurface.Face face, BoardScene scene,
          Map<Coords, BoardSurface> surfaces, Map<BoardSurface, GpuWaterCover> coverage) {
        BoardSurface water = clippingWater(surface, face, scene, surfaces);
        if (water == null) { return true; }
        GpuWaterCover waters = coverage.computeIfAbsent(water, own -> coveringWaters(scene, own, surfaces));
        List<List<MeshPartBuilder.VertexInfo>> pieces = new ArrayList<>();
        coveredPolygons(surface, face, waters, pieces::add);
        if (pieces.size() != 1 || pieces.getFirst().size() != 3) { return false; }
        Vector3[] corners = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            var vertex = pieces.getFirst().get(i);
            var dry = GpuTerrain.sculptVertex(corners[i], surface.relief.shade(corners[i]), Float.NaN, surface);
            if (!vertex.position.equals(corners[i]) || !vertex.color.equals(dry.color)) { return false; }
        }
        return true;
    }

    /** One drawn water triangle and its clipping planes, derived once per hex for every face it may cover. */
    private record WaterTop(BoardSurface water, BoardSurface.Face top, float minX, float maxX, float minY, float maxY,
          float maxZ, float nx, float ny, float nz, float[] edges) {
        /** World units of slack for float rounding before a piece beyond a clipping plane is left unclipped. */
        private static final float MARGIN = .01f;

        /** Whether a face's bounds can meet this triangle's; a face wholly above its surface stays dry. */
        boolean reaches(float faceMinX, float faceMaxX, float faceMinY, float faceMaxY, float faceMinZ) {
            return faceMaxX >= minX && faceMinX <= maxX && faceMaxY >= minY && faceMinY <= maxY && faceMinZ < maxZ;
        }

        /**
         * Whether every vertex of a piece lies beyond one of the four clipping planes by more than rounding. The
         * clips only ever cut a piece, so each crossing they create lies between two of its vertices and is beyond
         * that plane too: the clip at that plane would keep nothing, and the piece would be handed back whole.
         */
        boolean misses(List<MeshPartBuilder.VertexInfo> polygon) {
            for (int plane = 0; plane < 4; plane++) {
                Vector3 origin = plane == 1 ? top.b() : plane == 2 ? top.c() : top.a();
                float planeX = plane < 3 ? edges[plane * 2] : nx, planeY = plane < 3 ? edges[plane * 2 + 1] : ny;
                float planeZ = plane < 3 ? 0 : nz;
                boolean beyond = true;
                for (int i = 0; i < polygon.size() && beyond; i++) {
                    beyond = planeDistance(polygon.get(i).position, origin, planeX, planeY, planeZ) > MARGIN;
                }
                if (beyond) { return true; }
            }
            return false;
        }
    }

    static GpuWaterCover coveringWaters(BoardScene scene, BoardSurface own, Map<Coords, BoardSurface> surfaces) {
        List<WaterTop> joined = new ArrayList<>();
        waterTops(own, joined);
        // Rocks and cliff corners can project across a mouth into the next water hex.
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile next = GpuTerrain.openWater(scene.tile(own.tile.coords().translated(direction)));
            if (next != null) {
                waterTops(surfaces.computeIfAbsent(next.coords(), key -> new BoardSurface(scene, next)), joined);
            }
        }
        return new GpuWaterCover(joined);
    }

    private static GpuWaterCover waterTops(List<BoardSurface> waters) {
        List<WaterTop> result = new ArrayList<>();
        for (BoardSurface water : waters) { waterTops(water, result); }
        return new GpuWaterCover(result);
    }

    /** The planes as Vector3's cross product and normalisation compute them, without a vector per polygon. */
    private static void waterTops(BoardSurface water, List<WaterTop> result) {
        for (BoardSurface.Face top : water.waterFaces) {
            Vector3 a = top.a(), b = top.b(), c = top.c();
            float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z, acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
            float nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
            float length = nx * nx + ny * ny + nz * nz;
            if (length != 0 && length != 1) {
                float scale = 1f / (float) Math.sqrt(length);
                nx *= scale;
                ny *= scale;
                nz *= scale;
            }
            if (nz <= .00001f) { continue; }
            float[] edges = new float[6];
            for (int edge = 0; edge < 3; edge++) {
                Vector3 from = edge == 0 ? a : edge == 1 ? b : c, to = edge == 0 ? b : edge == 1 ? c : a;
                float ex = to.y - from.y, ey = from.x - to.x, span = ex * ex + ey * ey;
                if (span != 0 && span != 1) {
                    float scale = 1f / (float) Math.sqrt(span);
                    ex *= scale;
                    ey *= scale;
                }
                edges[edge * 2] = ex;
                edges[edge * 2 + 1] = ey;
            }
            result.add(new WaterTop(water, top, Math.min(a.x, Math.min(b.x, c.x)), Math.max(a.x, Math.max(b.x, c.x)),
                  Math.min(a.y, Math.min(b.y, c.y)), Math.max(a.y, Math.max(b.y, c.y)), Math.max(a.z, Math.max(b.z, c.z)),
                  nx, ny, nz, edges));
        }
    }

    /** Split at the drawn water triangles, so absorption cannot escape onto an exposed bank or cliff. */
    static void coveredFace(MeshPartBuilder mesh, BoardSurface surface, BoardSurface.Face face,
          List<BoardSurface> waters) {
        coveredFace(() -> mesh, surface, face, waterTops(waters));
    }

    static void coveredFace(Supplier<MeshPartBuilder> triangles, BoardSurface surface, BoardSurface.Face face,
          GpuWaterCover waters) {
        coveredPolygons(surface, face, waters, polygon -> surfacePolygon(triangles, polygon, surface));
    }

    static void coveredPolygons(BoardSurface surface, BoardSurface.Face face, GpuWaterCover waters,
          Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        BoardRelief.Shade a = surface.relief.shade(face.a()), b = surface.relief.shade(face.b()), c = surface.relief.shade(face.c());
        boolean bank = (face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE)
              && a != null && b != null && c != null && a.kind() == BoardRelief.Kind.GROUND
              && b.kind() == a.kind() && c.kind() == a.kind();
        if (surface.tile.liquid().present() && !surface.tile.liquid().molten()
              && (bank || submergedCliff(a) && submergedCliff(b) && submergedCliff(c))) {
            // An emerged bank needs its pool's height too: soil and damp gravel begin above the water, while
            // absorption still uses actual depth. Clipping it to the wet footprint erased that dry-side transition.
            // The pool's vertical bed boundaries also retain this reference: their projection can lie behind an
            // undercut, where an XY coverage test cannot decide submersion. Exterior cliffs still use clipping.
            // Frozen banks need the same material reference, including when neighbouring ground materials blend.
            float shore = GpuWaterShader.palette(surface.tile.liquid());
            polygonConsumer.accept(List.of(GpuTerrain.sculptVertex(face.a(), a, shore, surface),
                  GpuTerrain.sculptVertex(face.b(), b, shore, surface),
                  GpuTerrain.sculptVertex(face.c(), c, shore, surface)));
            return;
        }
        // Exterior walls stand outside the basin. A recess beneath the upper pool's footprint is still solid
        // rock, not an infinitely deep water column. Only lower pools can submerge this side of the cliff;
        // the basin's own submerged walls are handled above.
        int ceiling = cliff(a) && cliff(b) && cliff(c) ? surface.tile.elevation() : Integer.MAX_VALUE;
        coveredPolygons(face, waters, ceiling,
              p -> GpuTerrain.sculptVertex(p, surface.relief.shade(p), Float.NaN, surface), polygonConsumer);
    }

    private static boolean submergedCliff(BoardRelief.Shade shade) {
        return shade != null && shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
    }

    private static boolean cliff(BoardRelief.Shade shade) { return shade != null && shade.kind().cliff(); }

    static void coveredPolygons(BoardSurface.Face face, GpuWaterCover waters, int ceiling,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, Consumer<List<MeshPartBuilder.VertexInfo>> polygonConsumer) {
        List<List<MeshPartBuilder.VertexInfo>> dry = new ArrayList<>();
        dry.add(List.of(vertices.apply(face.a()), vertices.apply(face.b()), vertices.apply(face.c())));
        float minX = Math.min(face.a().x, Math.min(face.b().x, face.c().x));
        float maxX = Math.max(face.a().x, Math.max(face.b().x, face.c().x));
        float minY = Math.min(face.a().y, Math.min(face.b().y, face.c().y));
        float maxY = Math.max(face.a().y, Math.max(face.b().y, face.c().y));
        float minZ = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
        int count = waters.collect(minX, maxX, minY, maxY, minZ, ceiling);
        for (int i = 0; i < count && !dry.isEmpty(); i++) {
            WaterTop water = waters.candidate(i);
            BoardSurface.Face top = water.top();
            Vector3 origin = top.a();
            float[] edges = water.edges();
            // The pieces stay as they are until this triangle actually splits one of them.
            List<List<MeshPartBuilder.VertexInfo>> remaining = null;
            for (int index = 0; index < dry.size(); index++) {
                List<MeshPartBuilder.VertexInfo> polygon = dry.get(index);
                boolean reaches = !water.misses(polygon);
                List<MeshPartBuilder.VertexInfo> wet = reaches ? polygon : List.of();
                List<List<MeshPartBuilder.VertexInfo>> outside = reaches ? new ArrayList<>() : List.of();
                for (int edge = 0; edge < 3 && wet.size() >= 3; edge++) {
                    Vector3 corner = edge == 0 ? top.a() : edge == 1 ? top.b() : top.c();
                    wet = splitCovered(wet, corner, edges[edge * 2], edges[edge * 2 + 1], 0, outside);
                }
                if (wet.size() >= 3) { wet = splitCovered(wet, origin, water.nx(), water.ny(), water.nz(), outside); }
                if (wet.size() >= 3) {
                    if (remaining == null) { remaining = new ArrayList<>(dry.subList(0, index)); }
                    remaining.addAll(outside);
                    List<MeshPartBuilder.VertexInfo> tinted = new ArrayList<>();
                    for (var vertex : wet) {
                        Vector3 p = vertex.position;
                        float height = origin.z - (water.nx() * (p.x - origin.x) + water.ny() * (p.y - origin.y)) / water.nz();
                        float kind = vertex.color.b;
                        boolean ground = kind < .125f;
                        Color data = GpuTerrain.waterColor(vertex.color.r, height,
                              GpuTerrain.shoreTint(GpuWaterShader.palette(water.water().tile.liquid()),
                                    ground ? (vertex.color.a - .3f) / .1f : 0));
                        var shaded = new MeshPartBuilder.VertexInfo().set(vertex).setCol(data);
                        if (ground) {
                            // Water-tagged ground uses foot/rim order, like the shore material's wall projection.
                            shaded.uv.set(vertex.uv.y, GpuTerrain.shoreRim(vertex.uv.x, water.water().tile));
                        } else {
                            shaded.color.b = (224 + 240 * data.b) / 255f;
                            // Wet rock uses alpha for the water palette. Preserve its pigment variation in the
                            // unused second UV; a submerged cliff has no per-rock tint.
                            shaded.uv.y = kind >= .875f ? vertex.color.a : 1;
                        }
                        tinted.add(shaded);
                    }
                    polygonConsumer.accept(tinted);
                } else if (remaining != null) { remaining.add(polygon); }
            }
            if (remaining != null) { dry = remaining; }
        }
        dry.forEach(polygonConsumer);
    }

    /** Keep the half-space below a plane and retain the outside polygon for subsequent water triangles. */
    private static List<MeshPartBuilder.VertexInfo> splitCovered(List<MeshPartBuilder.VertexInfo> polygon,
          Vector3 origin, float nx, float ny, float nz, List<List<MeshPartBuilder.VertexInfo>> outside) {
        // Most clipping planes do not cross this polygon. Keep its vertices instead of allocating two
        // replacement lists for every nearby water triangle; only an actual crossing needs interpolation.
        int count = 0;
        for (var point : polygon) { if (planeDistance(point.position, origin, nx, ny, nz) <= 0) { count++; } }
        if (count == polygon.size()) { return polygon; }
        if (count == 0) {
            if (polygon.size() >= 3) { outside.add(polygon); }
            return List.of();
        }
        // A plane cut adds at most one vertex to each side.
        List<MeshPartBuilder.VertexInfo> inside = new ArrayList<>(polygon.size() + 1);
        List<MeshPartBuilder.VertexInfo> dry = new ArrayList<>(polygon.size() + 1);
        var previous = polygon.getLast();
        float before = planeDistance(previous.position, origin, nx, ny, nz);
        for (var point : polygon) {
            float after = planeDistance(point.position, origin, nx, ny, nz);
            if ((before <= 0) != (after <= 0)) {
                var crossing = new MeshPartBuilder.VertexInfo().set(previous).lerp(point, before / (before - after));
                inside.add(crossing);
                dry.add(crossing);
            }
            (after <= 0 ? inside : dry).add(point);
            previous = point;
            before = after;
        }
        if (dry.size() >= 3) { outside.add(dry); }
        return inside;
    }

    private static float planeDistance(Vector3 point, Vector3 origin, float nx, float ny, float nz) {
        return (point.x - origin.x) * nx + (point.y - origin.y) * ny + (point.z - origin.z) * nz;
    }

    private static void surfacePolygon(Supplier<MeshPartBuilder> triangles, List<MeshPartBuilder.VertexInfo> polygon,
          BoardSurface surface) {
        fan(polygon, t -> {
            MeshPartBuilder mesh = triangles.get();
            mesh.triangle(GpuTessellation.vertex(mesh, t.get(0), surface), GpuTessellation.vertex(mesh, t.get(1), surface),
                  GpuTessellation.vertex(mesh, t.get(2), surface));
        });
    }

    /** A clipped polygon's triangles, as a fan from its first vertex, without the degenerate ones. */
    static void fan(List<MeshPartBuilder.VertexInfo> polygon, Consumer<List<MeshPartBuilder.VertexInfo>> triangle) {
        for (int i = 1; i + 1 < polygon.size(); i++) {
            var a = polygon.getFirst();
            var b = polygon.get(i);
            var c = polygon.get(i + 1);
            if (!GpuSurfaceBlend.degenerate(a.position, b.position, c.position)) { triangle.accept(List.of(a, b, c)); }
        }
    }
}
