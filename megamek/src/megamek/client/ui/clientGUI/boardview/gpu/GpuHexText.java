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
import java.util.function.Predicate;

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
    /** One label of a hex, laid out: its plane, and its outline's and its text's glyph instances by font page. */
    private record Laid(Plane plane, List<FloatArray> outlines, List<FloatArray> pages) {
        /** Adds a label's glyphs by page to a chunk's. */
        static void append(List<FloatArray> chunk, List<FloatArray> label) {
            for (int page = 0; page < label.size(); page++) {
                if (chunk.size() == page) { chunk.add(new FloatArray()); }
                chunk.get(page).addAll(label.get(page));
            }
        }
    }
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
    /** The tiles the played events lead to, as last given, and the tile each hex's labels were laid out from. */
    private List<BoardScene.Tile> coming;
    private List<BoardScene.Tile> shown = List.of();
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
    /** The count of fall changes at the last update, and the hexes whose roof labels are left out (#update). */
    private int falls;
    private final Set<Coords> down = new HashSet<>();
    /** The last rebuild's glyphs by plane and chunk: a rebuild gathers again only the chunks whose labels changed. */
    private Map<Plane, Map<Coords, Chunk>> built = Map.of();
    /** Each hex's labels as laid out, in their order: in a chunk gathered again, only changed hexes are laid out. */
    private Map<Coords, List<Laid>> laid = Map.of();

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
        update(scene, font, outline, roofBounds, tactical, types, outlined, at -> false, 0, scene.tiles());
    }

    /**
     * {@code fallen}: whether a hex's structure lies as pieces (GpuTerrain#fallTaken), {@code falls} counting the
     * changes of that. Such a hex takes its labels from {@code coming}, the tiles the played events lead to (the
     * scene's own, listed alike, once they have played): its labels change once, as it gives way, to what they will be,
     * not again at each scene the playback shows on the way (user, 2026-11-10). While it still has a roof label there,
     * that label is left out until its collapse settles.
     */
    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds,
          boolean tactical, boolean types, boolean outlined, Predicate<Coords> fallen, int falls,
          List<BoardScene.Tile> coming) {
        boolean fell = this.falls != falls;
        this.falls = falls;
        Set<Coords> units = new HashSet<>();
        if (tactical) { scene.units().forEach(unit -> units.addAll(unit.footprint())); }
        boolean same = this.font == font && this.outline == outline && tuning == BoardGeometry.revision()
              && this.tactical == tactical && this.types == types && this.outlined == outlined;
        boolean moved = tiles != scene.tiles() || this.coming != coming;
        if (same && !fell && !moved && covered.equals(units)) { return; }
        List<BoardScene.Tile> source = sources(scene.tiles(), coming, fallen);
        // The hexes whose labels this rebuild changes, and their chunks; null for all of them. A strike's collapses and
        // the scenes they leave change a few dozen of a board's thousands of labels.
        Set<Coords> hexes = same && shown.size() == source.size() ? staleHexes(source, roofBounds, fallen, units, moved)
              : null;
        tiles = scene.tiles();
        this.coming = coming;
        if (hexes != null && hexes.isEmpty()) {
            shown = source;
            covered = units;
            return;
        }
        Set<Coords> stale = hexes == null ? null : new HashSet<>();
        if (hexes != null) { hexes.forEach(hex -> stale.add(cell(hex))); }
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        float outlineX = outline.getData().scaleX, outlineY = outline.getData().scaleY;
        Color color = new Color(font.getColor()), ringColor = new Color();
        // One label at a time: a font's cache grows its arrays to the exact size at every text it adds, so a chunk's
        // labels in one cache would copy them over and over (quadratic in the chunk's glyphs).
        BitmapFontCache glyphs = new BitmapFontCache(font, false), rings = new BitmapFontCache(outline, false);
        Map<Plane, Map<Coords, Chunk>> next = new TreeMap<>(Comparator.comparing(Plane::overlay)
              .thenComparingInt(Plane::elevation));
        Map<Coords, BoundingBox> nextRoofs = new HashMap<>();
        Set<Coords> nextDown = new HashSet<>();
        Map<Coords, List<Laid>> nextLaid = stale == null ? new HashMap<>() : new HashMap<>(laid);
        if (stale != null) {
            built.forEach((plane, cells) -> cells.forEach((cell, chunk) -> {
                if (!stale.contains(cell)) { next.computeIfAbsent(plane, key -> new HashMap<>()).put(cell, chunk); }
            }));
            roofs.forEach((at, roof) -> { if (!stale.contains(cell(at))) { nextRoofs.put(at, roof); } });
            for (Coords at : down) { if (!stale.contains(cell(at))) { nextDown.add(at); } }
        }
        try {
            for (BoardScene.Tile tile : source) {
                Coords at = tile.coords();
                if (stale != null && !stale.contains(cell(at))) { continue; }
                float centerX = BoardGeometry.centerX(at), centerY = BoardGeometry.centerY(at);
                List<Laid> kept = hexes != null && !hexes.contains(at) ? laid.get(at) : null;
                List<Laid> parts = kept != null ? kept : new ArrayList<>();
                if (kept != null) {
                    // Unchanged in a chunk gathered again: its glyphs as they were laid out, and its roof.
                    if (roofs.containsKey(at)) { nextRoofs.put(at, roofs.get(at)); }
                    if (down.contains(at)) { nextDown.add(at); }
                }
                nextLaid.put(at, parts);
                for (BoardHexText label : kept != null ? List.<BoardHexText>of()
                      : labels(tile, tactical, types, units.contains(at))) {
                    BoundingBox roof = label.elevation() > 0 ? roofBounds.apply(tile.coords()) : null;
                    if (label.elevation() > 0) {
                        nextRoofs.put(tile.coords(), roof == null ? null : new BoundingBox(roof));
                        if (fallen.test(tile.coords())) {
                            nextDown.add(tile.coords());
                            continue;
                        }
                    }
                    Plane plane = new Plane(tile.elevation() + label.elevation(), label.elevation() > 0);
                    float headroom = headroom(tile, label);
                    Laid part = new Laid(plane, new ArrayList<>(), new ArrayList<>());
                    parts.add(part);
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
                        Chunk.add(part.outlines(), rings, -contrast, tile.coords(), headroom, plane);
                    }
                    glyphs.clear();
                    glyphs.addText(layout, left, top);
                    Chunk.add(part.pages(), glyphs, contrast, tile.coords(), headroom, plane);
                }
                for (Laid part : parts) {
                    Chunk chunk = next.computeIfAbsent(part.plane(), key -> new HashMap<>())
                          .computeIfAbsent(cell(at), key -> new Chunk(new ArrayList<>(), new ArrayList<>(),
                                new BoundingBox().inf()));
                    float z = part.plane().elevation() * BoardGeometry.level();
                    chunk.bounds().ext(centerX - BoardGeometry.width(), centerY - BoardGeometry.width(), z - 1)
                          .ext(centerX + BoardGeometry.width(), centerY + BoardGeometry.width(), z + 1);
                    Laid.append(chunk.outlines(), part.outlines());
                    Laid.append(chunk.pages(), part.pages());
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
        built = next;
        laid = nextLaid;
        roofs.clear();
        roofs.putAll(nextRoofs);
        down.clear();
        down.addAll(nextDown);
        shown = source;
        this.font = font;
        this.outline = outline;
        tuning = BoardGeometry.revision();
        this.tactical = tactical;
        this.types = types;
        this.outlined = outlined;
        covered = units;
    }

    /**
     * The tile each hex's labels come from: the scene's, or {@code coming}'s for a hex whose structure lies as pieces.
     */
    private static List<BoardScene.Tile> sources(List<BoardScene.Tile> tiles, List<BoardScene.Tile> coming,
          Predicate<Coords> fallen) {
        if (coming == tiles || coming.size() != tiles.size()) { return tiles; }
        List<BoardScene.Tile> result = null;
        for (int index = 0; index < tiles.size(); index++) {
            BoardScene.Tile tile = tiles.get(index), next = coming.get(index);
            if (next != tile && next.coords().equals(tile.coords()) && fallen.test(tile.coords())) {
                if (result == null) { result = new ArrayList<>(tiles); }
                result.set(index, next);
            }
        }
        return result == null ? tiles : result;
    }

    /** Whether two tiles of a hex give it the same labels in the same place: a rebuild would upload the same glyphs. */
    private boolean sameLabels(BoardScene.Tile held, BoardScene.Tile tile, boolean covered) {
        return held.elevation() == tile.elevation() && held.backdrop() == tile.backdrop()
              && held.liquid().present() == tile.liquid().present()
              && BoardRelief.decoration(held) == BoardRelief.decoration(tile)
              && labels(held, tactical, types, covered).equals(labels(tile, tactical, types, covered));
    }

    private static boolean sameRoof(BoundingBox now, BoundingBox then) {
        return now == null ? then == null : then != null && now.min.equals(then.min) && now.max.equals(then.max);
    }

    /** The terrain chunk of a hex: its labels' glyphs are kept, and culled, by it. */
    private static Coords cell(Coords hex) {
        return new Coords(hex.getX() / GpuTerrain.CHUNK_SIZE, hex.getY() / GpuTerrain.CHUNK_SIZE);
    }

    /**
     * The hexes whose labels differ from those the meshes hold: those whose tile gives other labels, whose labelled
     * roof came down or stands again, or that a unit covers or uncovers; with other tiles given ({@code moved}) also
     * those whose labelled roof is elsewhere. {@code next} lists the hexes as {@link #shown} does.
     */
    private Set<Coords> staleHexes(List<BoardScene.Tile> next, Function<Coords, BoundingBox> roofBounds,
          Predicate<Coords> fallen, Set<Coords> units, boolean moved) {
        Set<Coords> stale = new HashSet<>();
        for (int index = 0; index < next.size(); index++) {
            BoardScene.Tile tile = next.get(index), held = shown.get(index);
            boolean hidden = units.contains(tile.coords());
            if (covered.contains(tile.coords()) != hidden
                  || held != tile && !held.equals(tile) && !sameLabels(held, tile, hidden)) {
                stale.add(tile.coords());
                stale.add(held.coords());
            }
        }
        for (Map.Entry<Coords, BoundingBox> roof : roofs.entrySet()) {
            if (fallen.test(roof.getKey()) != down.contains(roof.getKey())
                  || moved && !sameRoof(roofBounds.apply(roof.getKey()), roof.getValue())) {
                stale.add(roof.getKey());
            }
        }
        return stale;
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
        coming = null;
        shown = List.of();
        built = Map.of();
        laid = Map.of();
        font = null;
        outline = null;
        GpuShaderManager.dispose(shader);
    }
}
