/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.common.board.Coords;

/**
 * Captured labels, each on its outline unless that setting is off: a roof label (HEIGHT, or with Hex Types the structure line) shows through its
 * own hex; top view also reveals the enabled ground labels. The Tactical View adds its own: water depths and
 * structures' construction factors ({@link BoardHexText#tactical}).
 */
final class GpuHexText implements Disposable {
    /** Bounded 16 MiB instance buffers; all glyphs share the same four vertices and six indices. */
    private static final int PAGE_GLYPHS = 262_144;
    private static final int GLYPH_FLOATS = 16;
    /** Eight borrowed font pages, plus depth, unit depth and contrast, fit the GL 3.3 minimum of sixteen units. */
    private static final int TEXTURES = 8;
    private record Plane(int elevation, boolean overlay) { }
    /** A chunk's glyph instances, one array per page of the outline font and of the text font, in drawing order. */
    private record Chunk(List<FloatArray> outlines, List<FloatArray> pages, BoundingBox bounds) {
        static void add(List<FloatArray> pages, BitmapFontCache glyphs, float contrast, Coords tile, float headroom, Plane plane) {
            for (int page = 0; page < glyphs.getPageCount(); page++) {
                if (pages.size() == page) { pages.add(new FloatArray()); }
                float[] vertices = glyphs.getVertices(page);
                FloatArray instances = pages.get(page);
                for (int at = 0; at < glyphs.getVertexCount(page); at += 20) {
                    instances.add(vertices[at], vertices[at + 1], vertices[at + 10], vertices[at + 11]);
                    instances.add(vertices[at + 2]);
                    instances.add(vertices[at + 3], vertices[at + 4], vertices[at + 13], vertices[at + 14]);
                    instances.add(contrast);
                    instances.add(tile.getX(), tile.getY(), headroom);
                    instances.add(plane.elevation() * BoardGeometry.level(), plane.overlay() ? 1 : 0, 0);
                }
            }
        }
    }
    private record Range(BoundingBox bounds, int offset, int count) { }
    /** Owns the quad/instance buffer, borrowing the fonts' live textures, including their incremental glyph updates. */
    private static final class Page {
        private final List<Texture> textures;
        private final Mesh mesh;
        private final float[] instances;
        private final List<Range> ranges;
        private final BitSet visible = new BitSet();
        private final FloatArray compacted = new FloatArray();
        private boolean uploaded;

        Page(List<Texture> textures, float[] instances, List<Range> ranges) {
            this.textures = List.copyOf(textures);
            this.instances = instances;
            this.ranges = List.copyOf(ranges);
            mesh = new GpuInstancedMesh(4, 6,
                  new VertexAttributes(new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position")));
            try {
                mesh.setVertices(new float[] { 0, 0, 0, 1, 1, 1, 1, 0 });
                mesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
                mesh.enableInstancedRendering(false, instances.length / GLYPH_FLOATS,
                      new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_bounds"),
                      new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_uv"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_contrast"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_surface"));
            } catch (RuntimeException | Error failure) {
                mesh.dispose();
                throw failure;
            }
        }

        void render(Camera camera, ShaderProgram shader) {
            boolean changed = !uploaded;
            for (int index = 0; index < ranges.size(); index++) {
                boolean shown = camera.frustum.boundsInFrustum(ranges.get(index).bounds());
                if (visible.get(index) != shown) { visible.set(index, shown); changed = true; }
            }
            if (changed) {
                // Keep source order, compacting only when a chunk enters/leaves the view. Stationary frames upload nothing.
                compacted.clear();
                for (int index = visible.nextSetBit(0); index >= 0; index = visible.nextSetBit(index + 1)) {
                    Range range = ranges.get(index);
                    compacted.addAll(instances, range.offset(), range.count());
                }
                if (compacted.size > 0) { mesh.setInstanceData(compacted.items, 0, compacted.size); }
                uploaded = true;
            }
            if (compacted.size == 0) { return; }
            for (int index = 0; index < textures.size(); index++) { textures.get(index).bind(index); }
            for (int index = 0; index < TEXTURES; index++) {
                shader.setUniformi(index == 0 ? "u_texture" : "u_fonts[" + (index - 1) + "]",
                      index < textures.size() ? index : 0);
            }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            mesh.render(shader, GL20.GL_TRIANGLES);
        }
    }
    private final List<Page> pages = new ArrayList<>();
    private ShaderProgram shader;
    private final Matrix4 transform = new Matrix4();
    private final IntBuffer viewport = BufferUtils.newIntBuffer(4);
    private List<BoardScene.Tile> tiles;
    private BitmapFont font;
    private BitmapFont outline;
    private int tuning = -1;
    private boolean tactical;
    private boolean types;
    private boolean outlined;
    /** In the Tactical View, the hexes units stand on, whose own labels give way to them. */
    private Set<Coords> covered = Set.of();
    /** The roof each roof label was laid on at the last rebuild, by hex; null where the hex had none. */
    private final Map<Coords, BoundingBox> roofs = new HashMap<>();

    GpuHexText() {
        shader = GpuShaderManager.program(GpuHexText::program, next -> shader = next);
    }

    private static ShaderProgram program() {
        String vertex = GpuShaderSource.read("hex-text.vert");
        String fragment = GpuShaderSource.read("hex-text.frag");
        return GpuGlsl.compile("GPU hex text", vertex, fragment);
    }

    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds) {
        update(scene, font, outline, roofBounds, false, false, true);
    }

    /**
     * The fonts and roof bounds are borrowed; {@code outline} is {@code font} with a border of
     * {@link GpuBoardSkin#OUTLINE} texels ({@link GpuBoardSkin#OUTLINE_FONT}), drawn under every label when
     * {@code outlined} (Client Settings > Hex label outline). Labels are the existing board view's captured text and,
     * in the Tactical View ({@code tactical}), its labels of the captured water depths and structures; with View > Hex
     * Types on ({@code types}) the captured type block tells the depths instead.
     */
    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds,
          boolean tactical, boolean types, boolean outlined) {
        Set<Coords> units = new HashSet<>();
        if (tactical) { scene.units().forEach(unit -> units.addAll(unit.footprint())); }
        boolean same = this.font == font && this.outline == outline && tuning == BoardGeometry.revision()
              && this.tactical == tactical && this.types == types && this.outlined == outlined
              && covered.equals(units);
        if (same && tiles != scene.tiles() && sameLabels(scene.tiles(), roofBounds)) { tiles = scene.tiles(); }
        if (same && tiles == scene.tiles()) { return; }
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        float outlineX = outline.getData().scaleX, outlineY = outline.getData().scaleY;
        Color color = new Color(font.getColor()), ringColor = new Color();
        // One label at a time: a font's cache grows its arrays to the exact size at every text it adds, so a chunk's
        // labels in one cache would copy them over and over (quadratic in the chunk's glyphs).
        BitmapFontCache glyphs = new BitmapFontCache(font, false), rings = new BitmapFontCache(outline, false);
        Map<Plane, Map<Coords, Chunk>> next = new TreeMap<>(Comparator.comparing(Plane::overlay)
              .thenComparingInt(Plane::elevation));
        Map<Coords, BoundingBox> nextRoofs = new HashMap<>();
        try {
            for (BoardScene.Tile tile : scene.tiles()) {
                for (BoardHexText label : labels(tile, tactical, types, units.contains(tile.coords()))) {
                    Plane plane = new Plane(tile.elevation() + label.elevation(), label.elevation() > 0);
                    float headroom = headroom(tile, label);
                    Coords cell = new Coords(tile.coords().getX() / GpuTerrain.CHUNK_SIZE,
                          tile.coords().getY() / GpuTerrain.CHUNK_SIZE);
                    Chunk chunk = next.computeIfAbsent(plane, key -> new HashMap<>())
                          .computeIfAbsent(cell, key -> new Chunk(new ArrayList<>(), new ArrayList<>(),
                                new BoundingBox().inf()));
                    float centerX = BoardGeometry.centerX(tile.coords()), centerY = BoardGeometry.centerY(tile.coords());
                    float z = plane.elevation() * BoardGeometry.level();
                    chunk.bounds().ext(centerX - BoardGeometry.width(), centerY - BoardGeometry.width(), z - 1)
                          .ext(centerX + BoardGeometry.width(), centerY + BoardGeometry.width(), z + 1);
                    font.getData().setScale(label.font().getSize2D() / GpuBoardSkin.FONT_RESOLUTION);
                    int argb = label.argb();
                    // Space always uses white ink and its dark outline; other neutral ink adapts without rebuilding,
                    // and deliberately colored labels retain their color.
                    float contrast = tile.backdrop() == BoardBackdrop.SPACE ? 2
                          : (argb >>> 16 & 255) == (argb >>> 8 & 255) && (argb >>> 8 & 255) == (argb & 255) ? 1 : 0;
                    font.setColor(((argb >>> 16) & 255) / 255f, ((argb >>> 8) & 255) / 255f,
                          (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, label.text());
                    float x = centerX;
                    float baseline = centerY + BoardGeometry.height() / 2 - label.baseline() * BoardGeometry.hexScale();
                    BoundingBox roof = label.elevation() > 0 ? roofBounds.apply(tile.coords()) : null;
                    if (label.elevation() > 0) {
                        nextRoofs.put(tile.coords(), roof == null ? null : new BoundingBox(roof));
                    }
                    if (roof != null) {
                        float fit = Math.min(1, Math.max(8, roof.getWidth() - 4 * BoardGeometry.hexScale()) / layout.width);
                        font.getData().setScale(font.getData().scaleX * fit);
                        layout.setText(font, label.text());
                        x = roof.getCenterX();
                        baseline = roof.getCenterY() - layout.height / 2;
                    }
                    float left = x - layout.width / 2, top = label.fromTop() ? baseline : baseline + layout.height;
                    if (outlined) {
                        // The outline: the bordered glyphs in the outline's colour. A line starts at its first glyph's
                        // border, so they go OUTLINE texels left to put each glyph's ink on the text's.
                        outline.getData().setScale(font.getData().scaleX, font.getData().scaleY);
                        Color.argb8888ToColor(ringColor, BoardHexText.outline(argb));
                        rings.clear();
                        rings.addText(new GlyphLayout(outline, label.text(), ringColor, 0, Align.left, false),
                              left - GpuBoardSkin.OUTLINE * outline.getData().scaleX, top);
                        Chunk.add(chunk.outlines(), rings, -contrast, tile.coords(), headroom, plane);
                    }
                    glyphs.clear();
                    glyphs.addText(layout, left, top);
                    Chunk.add(chunk.pages(), glyphs, contrast, tile.coords(), headroom, plane);
                }
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
            outline.getData().setScale(outlineX, outlineY);
            font.setColor(color);
        }
        List<Glyphs> ordered = new ArrayList<>();
        for (var chunks : next.values()) {
            // Keep the existing order: all of a plane's outlines, its text, then the next elevation/overlay plane.
            for (Chunk chunk : chunks.values()) { ordered.add(new Glyphs(chunk.outlines(), chunk.bounds(), outline)); }
            for (Chunk chunk : chunks.values()) { ordered.add(new Glyphs(chunk.pages(), chunk.bounds(), font)); }
        }
        List<Page> uploaded = upload(ordered);
        clear();
        pages.addAll(uploaded);
        roofs.clear();
        roofs.putAll(nextRoofs);
        tiles = scene.tiles();
        this.font = font;
        this.outline = outline;
        tuning = BoardGeometry.revision();
        this.tactical = tactical;
        this.types = types;
        this.outlined = outlined;
        covered = units;
    }

    /**
     * Whether {@code next}, the scene's new tiles, give the labels the meshes hold, so that a rebuild would upload the
     * same glyphs: the tiles are equal and every roof label's roof is where it was.
     */
    private boolean sameLabels(List<BoardScene.Tile> next, Function<Coords, BoundingBox> roofBounds) {
        if (tiles == null || tiles.size() != next.size()) { return false; }
        for (int index = 0; index < next.size(); index++) {
            if (!tiles.get(index).equals(next.get(index))) { return false; }
        }
        for (Map.Entry<Coords, BoundingBox> roof : roofs.entrySet()) {
            BoundingBox now = roofBounds.apply(roof.getKey()), then = roof.getValue();
            if (now == null ? then != null : then == null || !now.min.equals(then.min) || !now.max.equals(then.max)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A tile's labels: its captured text, {@link BoardHexText#settled settled} so that the ground lines still end on
     * the bottom edge while a roof label lies on its roof, and in the Tactical View its water depth (not with Hex
     * Types, whose type block tells it) and its structure's CF chip, whose height the captured text decides.
     */
    static List<BoardHexText> labels(BoardScene.Tile tile, boolean tactical, boolean types, boolean covered) {
        if (!tactical) { return BoardHexText.settled(tile.text(), tile.text()); }
        int depth = !types && tile.liquid().present() && !tile.frozen() ? Math.max(0, tile.waterDepth()) : 0;
        int structure = tile.cover().structure();
        int cf = structure > 0 && structure != BoardScene.Cover.INDUSTRIAL ? tile.cover().cf() : 0;
        return BoardHexText.tactical(tile.text(), depth, cf, tile.cover().height(), covered);
    }

    private record Glyphs(List<FloatArray> regions, BoundingBox bounds, BitmapFont font) { }

    /** Ordered glyphs share a bounded instance buffer and up to eight font textures, without repacking their atlas. */
    private static List<Page> upload(List<Glyphs> glyphs) {
        List<Page> pages = new ArrayList<>();
        FloatArray vertices = new FloatArray();
        List<Range> ranges = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        try {
            for (Glyphs chunk : glyphs) {
                // Incremental fonts can gain atlas pages after an earlier chunk was completed.
                List<FloatArray> regions = chunk.regions();
                for (int region = 0; region < regions.size(); region++) {
                    FloatArray quads = regions.get(region);
                    int remaining = quads.size;
                    Texture next = chunk.font().getRegions().get(region).getTexture();
                    int offset = 0;
                    while (remaining > 0) {
                        int texture = textures.indexOf(next);
                        if (vertices.size == PAGE_GLYPHS * GLYPH_FLOATS || texture < 0 && textures.size() == TEXTURES) {
                            pages.add(new Page(textures, vertices.toArray(), ranges));
                            vertices.clear();
                            ranges.clear();
                            textures.clear();
                            texture = -1;
                        }
                        if (texture < 0) { texture = textures.size(); textures.add(next); }
                        int count = Math.min(remaining, PAGE_GLYPHS * GLYPH_FLOATS - vertices.size);
                        ranges.add(new Range(chunk.bounds(), vertices.size, count));
                        vertices.addAll(quads.items, offset, count);
                        for (int at = vertices.size - count + 15; at < vertices.size; at += GLYPH_FLOATS) {
                            vertices.items[at] = texture;
                        }
                        offset += count;
                        remaining -= count;
                    }
                }
            }
            if (vertices.size > 0) { pages.add(new Page(textures, vertices.toArray(), ranges)); }
            return List.copyOf(pages);
        } catch (RuntimeException | Error failure) {
            pages.forEach(page -> page.mesh.dispose());
            throw failure;
        }
    }

    private void clear() {
        pages.forEach(page -> page.mesh.dispose());
        pages.clear();
    }

    private static float headroom(BoardScene.Tile tile, BoardHexText label) {
        if (label.elevation() > 0 || tile.liquid().present()) { return 0; }
        // Keep the existing relief envelope; each glyph carries it without splitting the plane into font batches.
        return (float) Math.ceil(BoardRelief.decoration(tile) / BoardGeometry.hexScale()) * BoardGeometry.hexScale();
    }

    void render(SpriteBatch batch, BoardCamera camera, Texture depth, int bottom) {
        render(batch, camera, depth, null, bottom);
    }

    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom) {
        render(batch, boardCamera, depth, unitDepth, bottom, null);
    }

    /** Prepared per-hex ink is borrowed for this draw; glyph geometry and depth/occlusion policy are unchanged. */
    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere) {
        render(batch, boardCamera, depth, unitDepth, bottom, atmosphere, false);
    }

    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere, boolean wireframe) {
        if (pages.isEmpty()) { return; }
        Camera camera = boardCamera.camera;
        ShaderProgram previous = batch.getShader();
        batch.setShader(shader);
        batch.setProjectionMatrix(camera.combined);
        batch.setTransformMatrix(transform.setToTranslation(0, 0, .6f * BoardGeometry.hexScale()));
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        batch.begin();
        try {
            // SpriteBatch normally applies blending when it flushes; the static meshes bypass that upload path.
            if (batch.isBlendingEnabled()) {
                Gdx.gl.glEnable(GL20.GL_BLEND);
                if (batch.getBlendSrcFunc() != -1) {
                    Gdx.gl.glBlendFuncSeparate(batch.getBlendSrcFunc(), batch.getBlendDstFunc(),
                          batch.getBlendSrcFuncAlpha(), batch.getBlendDstFuncAlpha());
                }
            } else {
                Gdx.gl.glDisable(GL20.GL_BLEND);
            }
            shader.setUniformf("u_contrastEnabled", !tactical && wireframe ? -1 : 0);
            if (!tactical && !wireframe && atmosphere != null) { atmosphere.bindContrast(shader, TEXTURES + 2); }
            depth.bind(TEXTURES);
            if (unitDepth != null) { unitDepth.bind(TEXTURES + 1); }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            shader.setUniformi("u_depth", TEXTURES);
            shader.setUniformi("u_units", unitDepth == null ? TEXTURES : TEXTURES + 1);
            shader.setUniformf("u_unitOptions", unitDepth == null ? 0 : 1,
                  .05f * BoardGeometry.hexScale());
            shader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22], camera.projection.val[Matrix4.M23],
                  camera.projection.val[Matrix4.M32], camera.projection.val[Matrix4.M33]);
            shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
            // The destination's viewport: the scene's depth captures can have another size (the render resolution).
            viewport.clear();
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            shader.setUniformf("u_viewport", viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            shader.setUniformf("u_groundBoard", 0, 0, BoardGeometry.width(), BoardGeometry.height());
            shader.setUniformi("u_topView", boardCamera.isTopDown() ? 1 : 0);
            for (Page page : pages) { page.render(camera, shader); }
        } finally {
            batch.end();
            batch.setTransformMatrix(transform.idt());
            batch.setShader(previous);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
    }

    @Override
    public void dispose() {
        clear();
        roofs.clear();
        tiles = null;
        font = null;
        outline = null;
        GpuShaderManager.dispose(shader);
    }
}
