/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;

/** Extra data only on material boundaries. Palettes are shared by a chunk, never unique to individual hexes. */
final class GpuSurfaceBlend extends Attribute {
    static final long TYPE = register("boardSurfaceBlend");
    static final VertexAttributes VERTICES = new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(),
          VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverWeights"),
          new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_coverInterpolation", 1),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverFoot", 2));
    final GpuAssets.MaterialArrays texture;
    final float[] families, responses;
    final float[][] tiles, layers;

    GpuSurfaceBlend(GpuAssets.MaterialArrays texture, float[] families, float[] responses, float[][] tiles, float[][] layers) {
        super(TYPE);
        this.texture = texture;
        this.families = families.clone();
        this.responses = responses.clone();
        this.tiles = Arrays.stream(tiles).map(float[]::clone).toArray(float[][]::new);
        this.layers = Arrays.stream(layers).map(float[]::clone).toArray(float[][]::new);
    }

    @Override
    public GpuSurfaceBlend copy() {
        return new GpuSurfaceBlend(texture, families, responses, tiles, layers);
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var blend = (GpuSurfaceBlend) other;
        int result = Integer.compare(System.identityHashCode(texture), System.identityHashCode(blend.texture));
        if (result == 0) { result = Arrays.compare(families, blend.families); }
        if (result == 0) { result = Arrays.compare(responses, blend.responses); }
        for (int i = 0; i < tiles.length && result == 0; i++) {
            result = Arrays.compare(tiles[i], blend.tiles[i]);
            if (result == 0) { result = Arrays.compare(layers[i], blend.layers[i]); }
        }
        return result;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(super.hashCode(), texture, Arrays.hashCode(families), Arrays.hashCode(responses),
              Arrays.deepHashCode(tiles), Arrays.deepHashCode(layers));
    }

    /**
     * The sole ordinary family in a volcanic palette, -2 for all-volcanic, or -1 for generic dispatch.
     * A family can share one evaluator only when every effective slot has identical maps and scales.
     * Derive from the owned uniforms each time; ordinary palettes and missing magma assets stay generic.
     */
    static int ordinaryFamily(Material material) {
        var blend = material.get(GpuSurfaceBlend.class, TYPE);
        if (blend == null || !material.has(GpuMagmaShader.TYPE)) { return -1; }
        int ordinary = -1;
        for (int slot = 0; slot < 4; slot++) {
            float family = blend.families[slot];
            if (family != (int) family || family < 0 || family >= BoardSurfaceBlend.FAMILIES
                  || family == BoardScene.Surface.CONCRETE.ordinal()) { return -1; }
            if (family >= BoardSurfaceBlend.CRUST) { continue; }
            // The fourth slot historically borrows the base by family alone, even with a map override.
            int source = slot == 3 && family == blend.families[0] ? 0 : slot;
            if (ordinary < 0) { ordinary = source; }
            else if (family != blend.families[ordinary]
                  || !sameVector(blend.tiles[source], blend.tiles[ordinary])
                  || !sameVector(blend.layers[source], blend.layers[ordinary])) { return -1; }
        }
        return ordinary < 0 ? -2 : (int) blend.families[ordinary];
    }

    /** Bits 0/1 prove that slots B/C reuse the base's complete material; concrete's base is constructed separately. */
    int baseAliases() {
        if (Math.abs(families[0] - BoardScene.Surface.CONCRETE.ordinal()) < .5f) { return 0; }
        int aliases = 0;
        for (int slot = 1; slot <= 2; slot++) {
            if (families[slot] == families[0] && sameVector(tiles[slot], tiles[0]) && sameVector(layers[slot], layers[0])) {
                aliases |= 1 << (slot - 1);
            }
        }
        return aliases;
    }

    /** GLSL vector equality, including signed zero, for the four map/scale uniforms. */
    private static boolean sameVector(float[] a, float[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    static String vertex(String source) {
        return source.replace("void main() {",
              GpuShaderSource.read("terrain-blend.vert") + "\nvoid main() {\nterrainBlendWeights();\n");
    }

    record Palette(int base, int first, int second, int third) {
        Palette(int base, int first, int second) { this(base, first, second, base); }

        Palette(BoardScene.Surface base, BoardScene.Surface first, BoardScene.Surface second) {
            this(base.ordinal(), first.ordinal(), second.ordinal());
        }

        boolean volcanic() {
            return base >= BoardSurfaceBlend.CRUST || first >= BoardSurfaceBlend.CRUST
                  || second >= BoardSurfaceBlend.CRUST || third >= BoardSurfaceBlend.CRUST;
        }
    }

    /** A vertex and its cover; a bank's vertex also carries the ground below's uncut share of it (BoardSurfaceBlend.Footing). */
    record Point(MeshPartBuilder.VertexInfo vertex, BoardSurfaceBlend.Cover cover, BoardSurfaceBlend.Cover foot) {
        Point(MeshPartBuilder.VertexInfo vertex, BoardSurfaceBlend.Footing footing) { this(vertex, footing.cover(), footing.foot()); }
    }

    record Triangle(Point a, Point b, Point c) {
        /** {@code surface}: what keeps a vertex fixed in a curved layout (GpuTessellation.contacts); null in a plain one. */
        void write(MeshPartBuilder mesh, Palette palette, BoardSurface surface) { write(mesh, palette, surface, null); }

        /** As write; a charted wall's with {@code chart}, its corners' chart positions (GpuMaterialCache.charted). */
        void write(MeshPartBuilder mesh, Palette palette, BoardSurface surface, float[] chart) {
            mesh.triangle(write(mesh, a, palette, surface, chart, 0), write(mesh, b, palette, surface, chart, 1),
                  write(mesh, c, palette, surface, chart, 2));
        }

        private static short write(MeshPartBuilder mesh, Point point, Palette palette, BoardSurface surface,
              float[] chart, int corner) {
            var v = point.vertex();
            var cover = point.cover();
            var foot = point.foot();
            float[] values = { v.position.x, v.position.y, v.position.z, v.normal.x, v.normal.y, v.normal.z,
                  v.color.toFloatBits(), v.uv.x, v.uv.y, cover.weight(palette.base()), slot(cover, palette.first(), palette),
                  slot(cover, palette.second(), palette), slot(cover, palette.third(), palette), cover.interpolation(),
                  foot.weight(palette.base()), slot(foot, palette.first(), palette), slot(foot, palette.second(), palette),
                  slot(foot, palette.third(), palette) };
            if (chart != null) {
                values = Arrays.copyOf(values, values.length + 2);
                values[values.length - 2] = chart[2 * corner];
                values[values.length - 1] = chart[2 * corner + 1];
            }
            return GpuTessellation.vertex(mesh, surface != null && surface.fixedTessellation(v.position), values);
        }

        /** Unused slots repeat the base family and carry nothing. */
        private static float slot(BoardSurfaceBlend.Cover cover, int family, Palette palette) {
            return family == palette.base() ? 0 : cover.weight(family);
        }
    }

    /** Subdivision changes only interpolation, never the support surface or its silhouette. */
    static Map<Palette, List<Triangle>> prepare(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices) {
        return prepare(scene, tile, faces, vertices, spacing(TerrainLod.FULL));
    }

    /** Broad cover changes span several metres; texture height and noise provide the fine contact detail. */
    static float spacing(TerrainLod detail) {
        return BoardRelief.metres(detail == TerrainLod.DISTANT ? 8 : detail == TerrainLod.COARSE ? 4 : 3);
    }

    static Map<Palette, List<Triangle>> prepare(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices, float spacing) {
        var sampler = new BoardSurfaceBlend.Sampler(scene, tile);
        return prepare(BoardSurfaceBlend.family(tile), faces, vertices,
              v -> v.color.b > .375f && v.color.b < .625f
                    ? sampler.sampleFoot(v.position.x, v.position.y, v.position.z)
                    : new BoardSurfaceBlend.Footing(sampler.sample(v.position.x, v.position.y, v.position.z),
                          BoardSurfaceBlend.NO_FOOT), spacing);
    }

    private static Map<Palette, List<Triangle>> prepare(int family, List<BoardSurface.Face> faces,
          Function<Vector3, MeshPartBuilder.VertexInfo> vertices,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Footing> cover, float spacing) {
        Map<Palette, List<Triangle>> groups = new LinkedHashMap<>();
        // Coincident cliff/top vertices deliberately carry different packed roles and UV meanings.
        Map<Vector3, Point> points = new IdentityHashMap<>();
        Function<Vector3, Point> point = p -> points.computeIfAbsent(p, key -> {
            var v = vertices.apply(key);
            return new Point(v, cover.apply(v));
        });
        for (var face : faces) {
            append(groups, family, cover, point.apply(face.a()), point.apply(face.b()), point.apply(face.c()), spacing, 0);
        }
        return groups;
    }

    /** Water coverage may split a bank first; retain those vertices and their dry/submerged shading data. */
    static void appendPolygon(Map<Palette, List<Triangle>> groups, BoardScene.Surface family,
          List<MeshPartBuilder.VertexInfo> polygon, Function<Vector3, BoardSurfaceBlend.Cover> cover, float spacing) {
        appendPolygon(groups, family.ordinal(), polygon, cover, spacing);
    }

    static void appendPolygon(Map<Palette, List<Triangle>> groups, int family,
          List<MeshPartBuilder.VertexInfo> polygon, Function<Vector3, BoardSurfaceBlend.Cover> cover, float spacing) {
        List<Point> points = new ArrayList<>(polygon.size());
        Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Footing> vertexCover
              = v -> new BoardSurfaceBlend.Footing(cover.apply(v.position), BoardSurfaceBlend.NO_FOOT);
        for (var vertex : polygon) { points.add(new Point(vertex, vertexCover.apply(vertex))); }
        for (int i = 1; i + 1 < points.size(); i++) {
            Point a = points.getFirst(), b = points.get(i), c = points.get(i + 1);
            if (degenerate(a.vertex().position, b.vertex().position, c.vertex().position)) { continue; }
            append(groups, family, vertexCover, a, b, c, spacing, 0);
        }
    }

    /** Whether three positions span no area, by the squared length of their cross product. */
    static boolean degenerate(Vector3 a, Vector3 b, Vector3 c) {
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        float acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        float x = aby * acz - abz * acy, y = abz * acx - abx * acz, z = abx * acy - aby * acx;
        return x * x + y * y + z * z <= 1e-8f;
    }

    private static void append(Map<Palette, List<Triangle>> groups, int family,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Footing> cover,
          Point a, Point b, Point c, float spacing, int depth) {
        int mask = a.cover().mask() | b.cover().mask() | c.cover().mask();
        float ab = a.vertex().position.dst2(b.vertex().position), bc = b.vertex().position.dst2(c.vertex().position);
        float ca = c.vertex().position.dst2(a.vertex().position);
        boolean crowded = Integer.bitCount(mask) > 4;
        boolean mixedPoint = Integer.bitCount(a.cover().mask()) > 4 || Integer.bitCount(b.cover().mask()) > 4
              || Integer.bitCount(c.cover().mask()) > 4;
        // A long top fan must not stretch a four-metre boundary into a half-hex fade. Linear cover needs no
        // extra geometry; probe its edges and interior before subdividing a mixed triangle.
        float longest = Math.max(ab, Math.max(bc, ca));
        if (depth < 12 && (crowded || Integer.bitCount(mask) > 1 && longest > spacing * spacing)
              && !(crowded && mixedPoint && longest <= spacing * spacing)) {
            boolean split;
            if (bc > ab && bc >= ca) { split = appendSplit(groups, family, cover, b, c, a, spacing, depth, mask); }
            else if (ca > ab) { split = appendSplit(groups, family, cover, c, a, b, spacing, depth, mask); }
            else { split = appendSplit(groups, family, cover, a, b, c, spacing, depth, mask); }
            if (split) { return; }
        }
        // Authored mixtures can put more than four families at one point; subdivision cannot separate those.
        // Keep the strongest four on this already small carrier instead of growing an unbounded mesh.
        while (Integer.bitCount(mask) > 4) {
            int weakest = -1;
            float least = Float.POSITIVE_INFINITY;
            for (int candidate = 0; candidate < BoardSurfaceBlend.FAMILIES; candidate++) {
                if ((mask & (1 << candidate)) == 0) { continue; }
                float weight = a.cover().weight(candidate) + b.cover().weight(candidate) + c.cover().weight(candidate);
                if (weight < least) { weakest = candidate; least = weight; }
            }
            mask &= ~(1 << weakest);
        }
        // A slope can extend beyond its source footprint. Its absent family must not consume a palette slot.
        int base = (mask & (1 << family)) != 0 ? family : Integer.numberOfTrailingZeros(mask);
        var first = base;
        var second = base;
        var third = base;
        for (int candidate = 0; candidate < BoardSurfaceBlend.FAMILIES; candidate++) {
            if (candidate != base && (mask & (1 << candidate)) != 0) {
                if (first == base) { first = candidate; }
                else if (second == base) { second = candidate; }
                else { third = candidate; }
            }
        }
        groups.computeIfAbsent(new Palette(base, first, second, third), key -> new ArrayList<>()).add(new Triangle(a, b, c));
    }

    private static boolean appendSplit(Map<Palette, List<Triangle>> groups, int family,
          Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Footing> cover,
          Point a, Point b, Point c, float spacing, int depth, int mask) {
        var mid = between(cover, a, b, .5f);
        if (Integer.bitCount(mask) <= 4 && accurate(mid, a, b, c, .5f, .5f, 0)
              && accurate(between(cover, b, c, .5f), a, b, c, 0, .5f, .5f)
              && accurate(between(cover, c, a, .5f), a, b, c, .5f, 0, .5f)
              && accurate(between(cover, mid, c, 1 / 3f), a, b, c, 1 / 3f, 1 / 3f, 1 / 3f)) {
            return false;
        }
        append(groups, family, cover, a, mid, c, spacing, depth + 1);
        append(groups, family, cover, mid, b, c, spacing, depth + 1);
        return true;
    }

    private static Point between(Function<MeshPartBuilder.VertexInfo, BoardSurfaceBlend.Footing> cover,
          Point a, Point b, float amount) {
        var v = new MeshPartBuilder.VertexInfo().set(a.vertex()).lerp(b.vertex(), amount);
        v.normal.nor();
        return new Point(v, cover.apply(v));
    }

    private static boolean accurate(Point sample, Point a, Point b, Point c, float wa, float wb, float wc) {
        float interpolation = a.cover().interpolation() * wa + b.cover().interpolation() * wb + c.cover().interpolation() * wc;
        if (Math.abs(sample.cover().interpolation() - interpolation) > .04f) { return false; }
        for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
            float expected = a.cover().weight(family) * wa + b.cover().weight(family) * wb + c.cover().weight(family) * wc;
            if (Math.abs(sample.cover().weight(family) - expected) > .04f) { return false; }
            float foot = a.foot().weight(family) * wa + b.foot().weight(family) * wb + c.foot().weight(family) * wc;
            if (Math.abs(sample.foot().weight(family) - foot) > .04f) { return false; }
        }
        return true;
    }
}
