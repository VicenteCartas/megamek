/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** Tiny textured details, baked into a single opaque material per terrain chunk and shared prop page. */
final class GpuScatter {
    private static final int CELL = 256;
    private static final int BORDER = 32;
    private static final int WIDTH = 2 * CELL;
    // The white fifth swatch preserves the bushes' authored bark and leaf colours; the sixth shares Mars cliff stone.
    private static final String[] MATERIALS = { "terrain/surfaces/scatter-stone", "terrain/surfaces/biome-soil",
          "terrain/surfaces/arid-cliff",
          "vegetation/palm-fronds", null, "terrain/surfaces/mars-cliff", "terrain/surfaces/volcano-cliff" };
    private static final int HEIGHT = (MATERIALS.length + 1) / 2 * CELL;

    private GpuScatter() { }

    /** The atlas's materials, read by their owner: one definition per swatch, null where the board data has none. */
    static GodotMaterial[] materials(GpuAssets assets) {
        var definitions = new GodotMaterial[MATERIALS.length];
        for (int index = 0; index < MATERIALS.length; index++) {
            if (MATERIALS[index] != null && assets.hasMaterial(MATERIALS[index])) {
                definitions[index] = assets.definition(MATERIALS[index]);
            }
        }
        return definitions;
    }

    /**
     * The atlas's sRGB pixels from {@link #materials}, without GL, so they can be made off the render thread; the caller
     * owns them. Source height/coverage alpha never makes scatter transparent.
     */
    static Pixmap pixels(GodotMaterial[] materials) {
        var atlas = new GpuLinearImage(WIDTH, HEIGHT);
        atlas.fill(1, 1, 1, 1);
        int interior = CELL - 2 * BORDER;
        for (int index = 0; index < MATERIALS.length; index++) {
            if (MATERIALS[index] == null) { continue; }
            GpuLinearImage source;
            if (materials[index] != null) {
                // Only the albedo: the atlas ignores the sculpted materials' heights.
                Pixmap pixels = GpuMaterialMaps.pack(materials[index], GpuMaterialMaps.Layout.COLOR);
                try { source = new GpuLinearImage(pixels); }
                finally { pixels.dispose(); }
            } else {
                source = new GpuLinearImage(1, 1);
                float gray = index == 3 ? 1 : GpuColor.linear(Color.GRAY.r);
                source.fill(gray, gray, gray, 1);
            }
            int x = index % 2 * CELL + BORDER, y = index / 2 * CELL + BORDER;
            atlas.draw(source, 0, 0, source.width, source.height, x, y, interior, interior);
            atlas.extrude(x, y, interior, interior, BORDER);
        }
        atlas.opaque();
        return atlas.ldr();
    }

    /** The atlas uploaded from {@link #pixels}, which the caller still owns; GpuAssets owns the texture. */
    static Texture atlas(Pixmap pixels) {
        Texture texture = GpuColorTexture.create(pixels, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        // The 32-pixel extruded border still occupies a full texel at the last sampled mip level.
        texture.bind();
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 5);
        return texture;
    }

    /** Tiny stones use only the scatter kit's eight-triangle meshes. */
    static BoardShape rock(BoardScene.Tile tile, BoardScene.Feature feature) {
        return BoardScatter.rock(tile.surface(), Math.round(feature.rotation()), feature.asset().equals("scatter-slab"));
    }

    static float diameter(BoardScene.Feature feature) {
        if (feature.asset().equals("scatter-plant")) {
            BoardShape shape = BoardScatter.plant(BoardScene.Surface.GRASS);
            return (float) Math.hypot(2 * BoardScatter.BUSH_RADIUS, shape.height())
                  * BoardScatter.bushScale(shape, feature.scale());
        }
        return (float) Math.hypot(8 * feature.scale() * BoardGeometry.hexScale(),
              feature.height() * BoardGeometry.level());
    }

    static void build(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface surface, BoardScene.Feature feature) {
        if (!BoardScatter.allowed(tile)) { return; }
        BoardShape bush = feature.asset().equals("scatter-plant") ? BoardScatter.plant(tile.surface()) : null;
        float bushScale = bush == null ? 0 : BoardScatter.bushScale(bush, feature.scale());
        // On the hex's own ground, never over a receding rim or a transition's slope.
        float[] spot = surface.relief.settle(BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale(),
              BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale(),
              bush == null ? BoardRelief.metres(.3f) : BoardScatter.BUSH_RADIUS * bushScale);
        float x = spot[0], y = spot[1];
        Matrix4 transform = new Matrix4().setToTranslation(x, y, surface.height(x, y))
              .rotate(Vector3.Z, feature.rotation())
              .scale(feature.scale() * BoardGeometry.hexScale(), feature.scale() * BoardGeometry.hexScale(),
                    feature.height() * BoardGeometry.level());
        boolean stone = BoardScatter.stone(feature.asset());
        Color color = color(tile.surface(), feature.asset());
        float shade = 1;
        if (!stone) {
            shade = .88f + .24f * feature.rotation() / 360;
            color.mul(shade, shade, shade, 1);
        }
        int material = stone
              ? switch (tile.surface()) {
                  case DIRT -> 1;
                  case SAND, DESERT -> 2;
                  case MARS -> 5;
                  case VOLCANO -> 6;
                  default -> 0;
              } : 3;
        BoardShape shape;
        switch (feature.asset()) {
            case "scatter-dry-grass" -> {
                shape = BoardScatter.shape("grass");
            }
            case "scatter-plant" -> {
                shape = bush;
                transform.setToTranslation(x, y, surface.height(x, y) - .02f * bushScale)
                      .rotate(Vector3.Z, feature.rotation()).scale(bushScale, bushScale, bushScale);
                material = 4;
            }
            case "scatter-rock", "scatter-slab" -> {
                shape = rock(tile, feature);
                float height = feature.asset().equals("scatter-slab") ? .4f : 1;
                // Keep the entire open base below ground, with the same footprint and summit as before.
                transform.translate(0, 0, -.08f).scale(6, 4.8f, (height + .08f) / shape.height());
            }
            default -> throw new IllegalArgumentException("Unknown terrain scatter: " + feature.asset());
        }
        float radius = 0, low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var polygon : shape.polygons()) {
            for (var point : polygon.points()) {
                Vector3 world = new Vector3(point).mul(transform);
                radius = Math.max(radius, (float) Math.hypot(world.x - x, world.y - y));
                low = Math.min(low, world.z);
                high = Math.max(high, world.z);
            }
        }
        Vector3 base = new Vector3(x, y, low);
        if (!surface.relief.visibleScatter(base, high - low)
              || surface.relief.obstructed(base, radius, high - low)) { return; }
        for (BoardShape.Polygon polygon : shape.polygons()) {
            Color tint = material == 4 ? new Color(polygon.color()).mul(shade, shade, shade, 1) : color;
            triangle(mesh, polygon, transform, tint, material, shape.height(), surface);
        }
    }

    private static Color color(BoardScene.Surface surface, String asset) {
        if (asset.equals("scatter-dry-grass")) {
            return GpuColor.linear(.88f, .71f, .24f);
        }
        // Stone maps supply their final pigment; white is the neutral texture multiplier.
        return new Color(Color.WHITE);
    }

    private static void triangle(MeshPartBuilder mesh, BoardShape.Polygon polygon, Matrix4 transform, Color color,
          int material, float height, BoardSurface surface) {
        Vector3[] points = polygon.points();
        Vector3 a = points[0].cpy().mul(transform), b = points[1].cpy().mul(transform), c = points[2].cpy().mul(transform);
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        if (material == 4) {
            var kind = BoardScatter.leaf(polygon.material()) ? BoardRelief.Kind.LEAF : BoardRelief.Kind.PLANT;
            var shade = new BoardRelief.Shade(normal, kind, 1, surface.tile.elevation(), color.r, color.g, color.b);
            mesh.triangle(GpuTerrain.sculptVertex(a, shade, Float.NaN, surface),
                  GpuTerrain.sculptVertex(b, shade, Float.NaN, surface),
                  GpuTerrain.sculptVertex(c, shade, Float.NaN, surface));
            return;
        }
        // Project in object space, so turning a stone also turns its grain. Each blade spans the foliage swatch.
        Vector3 face = polygon.normal();
        boolean top = material != 3 && Math.abs(face.z) >= Math.max(Math.abs(face.x), Math.abs(face.y));
        boolean alongY = !top && Math.abs(face.x) > Math.abs(face.y);
        float low = -.5f, span = 1;
        if (material == 3) {
            low = Float.POSITIVE_INFINITY;
            float high = Float.NEGATIVE_INFINITY;
            for (Vector3 point : points) {
                float u = alongY ? point.y : point.x;
                low = Math.min(low, u);
                high = Math.max(high, u);
            }
            span = Math.max(.0001f, high - low);
        }
        float interior = CELL - 2 * BORDER - 1;
        Vector3[] world = { a, b, c };
        MeshPartBuilder.VertexInfo[] vertices = new MeshPartBuilder.VertexInfo[3];
        for (int i = 0; i < points.length; i++) {
            Vector3 local = points[i];
            float u = ((alongY ? local.y : local.x) - low) / span;
            float v = top ? local.y + .5f : local.z / height;
            vertices[i] = new MeshPartBuilder.VertexInfo().setPos(world[i]).setNor(normal).setCol(color).setUV(
                  (material % 2 * CELL + BORDER + .5f + Math.clamp(u, 0, 1) * interior) / WIDTH,
                  (material / 2 * CELL + BORDER + .5f + Math.clamp(v, 0, 1) * interior) / HEIGHT);
        }
        mesh.triangle(vertices[0], vertices[1], vertices[2]);
    }
}
