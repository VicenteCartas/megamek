/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;

/**
 * The ground material cache: the canonical material of level, dry ground tops, as the close view shows it
 * (terrain-sculpt.frag, materialFillFlag), averaged exactly into the world-locked pages of a mip pyramid
 * ({@link GpuPageCache}) and read back by a cached-lit program (materialCachedFlag), which adds the live layers and the
 * lighting. Zooming out only reads coarser pages and mips: nothing in a page depends on the view.
 * <p>
 * The fill renders the material in base tiles of {@link #TILE} texels at {@link #CLOSE_PIXEL}, world-aligned, and
 * reduces each to exact means; a page of level v (interior and gutters, 256·2^v base texels) is 2^v x 2^v base tiles,
 * whose means at v to v+2 make its three levels. So every page, gutters included, holds exact averages on one grid, and
 * a page's mip k is level v+k. Each level is then sharpened ({@link #SHARPEN}) where a top covers it, so the trilinear
 * read lands on the plain average of the pixel's footprint instead of softening it twice. A page one level coarser is
 * copied from its sixteen built children instead, at once. The first view's pages build behind the loading stage
 * ({@link #prewarm}).
 * <p>
 * A frame reads one level for the whole view, so neighbouring pages always filter alike: the level its pixels need,
 * or, after a zoom in, the coarser one it read until every page of the finer one is built. A terrain page's tops draw
 * cached ({@link #material}) where every cache page under them can be read at that level, else directly; where the
 * storage cannot hold the view's pages, those furthest from its centre draw directly. Its storage follows the scene's
 * pixel count, within the video memory.
 */
final class GpuMaterialCache {
    private static final MMLogger LOGGER = MMLogger.create(GpuMaterialCache.class);
    /** {@code -Dmegamek.gpu.materialCache=false} keeps every top on its own program (the split stays). */
    static final boolean ENABLED = !"false".equals(System.getProperty("megamek.gpu.materialCache"));
    /** {@code -Dmegamek.gpu.materialCacheSplit=false} builds no cache tops at all, as before the cache. */
    static final boolean SPLIT = !"false".equals(System.getProperty("megamek.gpu.materialCacheSplit"));
    private static final String TOP = "#cacheTop";
    private static final String CACHED = "#cached";
    /**
     * Page texels a side, a gutter along each edge, and the mip levels each page keeps: the view's level is chosen so
     * reads take mips 0 and 1 only (begin), whose footprints stay within the gutter; mip 2 lets a page's parent be
     * copied from it.
     */
    static final int PAGE_TEXELS = 256, GUTTER = 8, LEVELS = 3;
    /** Base tile texels a side, and its levels; a level-v page is 2^v base tiles a side. */
    private static final int TILE = 256, TILE_LEVELS = 9;
    /** The pyramid's coarsest level: its tiles' last level still holds one texel. */
    private static final int TOP_LEVEL = TILE_LEVELS - LEVELS;
    /**
     * The first coarse level: its pages render the material directly at their own texel, 4x multisampled (the source
     * maps' mips filtering the rest), in one step whatever their level. Finer levels are exact means of 2^v x 2^v base
     * tiles at the close view's texel.
     */
    private static final int COARSE = Integer.getInteger("megamek.gpu.materialCacheCoarse", 2);
    /**
     * The root: the coarsest level any view reads ({@link #TOP_LEVEL}) or, on a longer board, the coarsest needed to
     * cover it in at most ROOT_PAGES pages a side, up to MAX_ROOT; so every page has the root above it.
     */
    private static final int ROOT_PAGES = 4, MAX_ROOT = 16;
    /** The uniform block binding of the page table (MaterialCachePages); no other program uses uniform blocks. */
    static final int BINDING = 1;
    /**
     * Colour and height; the sum of the unit normals with the coverage; cavity, relief and the intrinsic GGX alpha^2.
     * All premultiplied by the coverage, 8 bits each (12 bytes a texel): the cached ground is dielectric and does not
     * scatter (GpuTerrain.cachesGround).
     */
    private static final String[] SAMPLERS = { "u_cacheColour", "u_cacheMoments", "u_cacheDetail" };
    private static final int[] FORMATS = { GL30.GL_SRGB8_ALPHA8, GL30.GL_RGBA8, GL30.GL_RGBA8 };
    /**
     * Interior texels a scene pixel may need: 2 x 2 at a level's coarse end, times the isometric view's longer pixel
     * along the ground (√3), and a quarter more for the pages cut by the view's edges and the ring around them.
     */
    private static final float TEXELS_PER_PIXEL = 4 * 1.732f * 1.25f;
    /**
     * Page memory, MiB: {@code -Dmegamek.gpu.materialCacheMiB}, else half of what the driver reports free when the
     * storage is allocated (capacity), else this.
     */
    private static final long DEFAULT_CAP_MIB = 512;
    private static final Long CAP_MIB = Long.getLong("megamek.gpu.materialCacheMiB");
    /** Layers the view's pages leave free, so a stale page always finds a spare to rebuild in. */
    private static final int SPARES = 8;
    /** Frames a changed scene size must hold before the storage follows it. */
    private static final int SETTLE_FRAMES = 30;
    /** The base tiles' texel, metres: the close view's pixel, whose material every page holds averaged. */
    static final float CLOSE_PIXEL = Float.parseFloat(System.getProperty("megamek.gpu.materialCacheClosePixel", ".1"));
    /**
     * The finest view pixel (metres, its shorter axis) the cache serves: closer views draw directly. Its own texel, so
     * level tops are never magnified; an override below {@link #CLOSE_PIXEL} would magnify level 0.
     */
    static final float MIN_PIXEL_METRES = Float.parseFloat(System.getProperty("megamek.gpu.materialCachePixel",
          Float.toString(CLOSE_PIXEL)));
    /**
     * Each level's sharpening, the separable kernel (-a, 1 + 2a, -a): the trilinear read's own blur taken back, so the
     * read matches the plain average of a pixel's footprint. Colour, height, cavity and relief; the normals' sums,
     * coverage and roughness stay exact means.
     */
    static final float SHARPEN = Float.parseFloat(System.getProperty("megamek.gpu.materialCacheSharpen", ".15"));
    /** A face counts as level from this normal Z: the same at every detail. */
    private static final float LEVEL_Z = .999f;
    /**
     * The steepest top, its normal's Z: the pages hold a slope from above, stretched by up to 1/Z along its fall line.
     * Steeper banks and cliffs draw directly.
     */
    private static final float SLOPE_Z = .7f;
    /** The fill's GPU time a frame (a target), and its CPU time (a bound); a loading frame's prewarm takes more. */
    private static final long FILL_NANOS = 2_000_000, FILL_CPU_NANOS = 3_000_000, PREWARM_NANOS = 30_000_000;

    /** Draws the cache tops that reach a world rectangle (world units) with the fill programs, from {@code camera}. */
    interface Tops {
        void draw(Camera camera, float minX, float minY, float maxX, float maxY);
    }

    /** A chunk's cache tops this frame: their terrain page, bounds (world units) and generation (#chunk). */
    private record Chunk(int page, BoundingBox bounds, int since) { }

    /** A page of any level. */
    private record PageKey(int at, int x, int y) { }

    private final GpuPageCache pages = new GpuPageCache(PAGE_TEXELS, GUTTER, LEVELS, MAX_ROOT + 1, FORMATS);
    // A top's cached-lit twin, and the generic copy its fill draws: both serve every family alike (GpuFamilyPrograms),
    // so no family programs link for the cache.
    private final Map<Material, Material> twins = new IdentityHashMap<>(), generics = new IdentityHashMap<>();
    private final OrthographicCamera fillCamera = new OrthographicCamera();
    // The base tile: its targets with every level, its depth, the framebuffers that draw and copy it and that draw into
    // a page, and the programs that reduce and sharpen.
    private final int[] tiles = new int[FORMATS.length];
    private int tileFramebuffer, tileDepth, readFramebuffer, drawFramebuffer, pageFramebuffer, mipVertices;
    // A coarse page's multisampled render: its framebuffer, its targets' and its depth's storage.
    private int coarseFramebuffer, coarseDepth;
    private final int[] coarseTargets = new int[FORMATS.length];
    private ShaderProgram means, sharpen;
    private boolean active, usable = true, enabled = ENABLED, prewarmed = true;
    // The board's extent (world units), its world metre, and the pages the context allows (read once).
    private final BoundingBox board = new BoundingBox();
    private float metre = 1;
    private long limit = -1;
    // A scene size the storage does not fit yet, and the frames it has held.
    private int resized, settled;
    // The root's level and pages (inclusive), always needed first: any page not built yet reads it meanwhile.
    private int root = TOP_LEVEL, rootX0, rootY0, rootX1 = -1, rootY1 = -1;
    // The texel the fill evaluates the material at (u_cacheClosePixel): a base tile's, or a coarse page's.
    private float fillTexel = CLOSE_PIXEL;
    // This frame: the level its pixels need and the level it reads (-1 before a frame reads any), the pages under the
    // view and how many of them the storage holds, the terrain pages drawn cached, and whether all read their own level.
    private int level, reading = -1, under, admitted;
    private boolean exact;
    // The chunks with cache tops (all, those near the pages this frame builds, and the visible ones).
    private final List<BoundingBox> topBounds = new ArrayList<>(), nearBounds = new ArrayList<>();
    private final BoundingBox near = new BoundingBox();
    private final List<Chunk> visible = new ArrayList<>();
    private final IntArray cachedPages = new IntArray();
    // Pages copied from their children: their mip 2 only approximates the next level, so none is copied from them.
    private final Set<PageKey> copied = new HashSet<>();
    // Detail changes so far (detailChanged), and the one each page's build finished at: a change over a page in the
    // middle of its build restarts it (GpuPageCache.invalidate), so its content is drawn from the geometry of then.
    private int generation;
    private final Map<PageKey, Integer> stamps = new HashMap<>();
    private Camera view;
    private Tops tops;
    // Whether this frame may build pages (the root at least): the fill's programs are ready.
    private boolean filling;
    // The last view frame's CPU time in end() and the steps it built (diagnostics).
    private long endNanos, endSteps;

    // ---- Tops: which faces and materials ----

    /**
     * A sculpt material whose level tops are cached: natural ground or pavement, alone or blended; not volcanic or iced.
     * A blend's live layers take its covers' plain proportions (terrain-sculpt.frag), so covers whose live effects
     * follow the height-blended weights stay direct: sand (its windows onto the substrate), fungus (the level grade) and
     * lunar ground (its regolith light). Bounce light, rain, wind and ground damage follow the mixture rather than each
     * patch. Pavement keeps its per-pixel occlusion live; like natural ground, its cached tops have no parallax.
     */
    static boolean cacheable(Material material) {
        if (!SPLIT || GpuTessellation.curved(material)) { return false; }
        var sculpt = (FloatAttribute) material.get(GpuTerrain.Sculpt.TYPE);
        if (sculpt == null || sculpt.value >= BoardSurfaceBlend.CRUST || material.has(GpuMagmaShader.TYPE)
              || material.has(GpuIceShader.TYPE)) { return false; }
        var blend = (GpuSurfaceBlend) material.get(GpuSurfaceBlend.TYPE);
        if (blend != null) {
            for (float family : blend.families) {
                if (family >= BoardSurfaceBlend.CRUST || family == BoardScene.Surface.SAND.ordinal()
                      || family == BoardScene.Surface.FUNGUS.ordinal() || family == BoardScene.Surface.LUNAR.ordinal()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The material a chunk's cache tops draw in: the same attributes, so the same programs, under its own id. */
    static Material top(Material material) {
        Material copy = new Material(material);
        copy.id = material.id + TOP;
        return copy;
    }

    static boolean isTop(Material material) { return material.id.endsWith(TOP); }

    /** The twin a top's cached draw takes: the cached-lit program of the generic variant. */
    static boolean isCached(Attributes material) { return material instanceof Material m && m.id.endsWith(CACHED); }

    /**
     * A face of ground or of a natural bank (a cliff of up to two levels, whose low caprock stands over none of its own
     * faces; no panel or pit wall) that lies level or slopes gently ({@link #SLOPE_Z}), in a hex that no biome cover
     * reaches: the canonical material holds no biome layer, and its weathering follows the cover. Water over it stays
     * live (absorption, the column's tint, caustics), its sediment and wet bank canonical: both follow the face's own
     * water level and palette.
     */
    static boolean top(BoardSurface surface, BoardSurface.Face face, BoardScene scene) {
        if (!uncovered(scene, surface.tile)) { return false; }
        for (Vector3 p : new Vector3[] { face.a(), face.b(), face.c() }) {
            var shade = surface.relief.shade(p);
            if (shade == null || shade.kind() != BoardRelief.Kind.GROUND && (shade.kind() != BoardRelief.Kind.CLIFF
                  || !Float.isNaN(shade.projection()) || shade.level() > 0)) { return false; }
        }
        return normalZ(face) >= SLOPE_Z;
    }

    /**
     * An open water hex's bed face the cache can hold, as {@link #top}: level or sloping gently, and overlapped from
     * above by none of the hex's bank tops ({@code banks}): an undercut bank would stand over it in the pages.
     */
    static boolean bed(BoardSurface surface, BoardSurface.Face face, BoardScene scene, List<BoardSurface.Face> banks) {
        if (!uncovered(scene, surface.tile) || normalZ(face) < SLOPE_Z) { return false; }
        float lowest = Math.min(face.a().z, Math.min(face.b().z, face.c().z)), clearance = BoardRelief.metres(.05f);
        for (BoardSurface.Face bank : banks) {
            if (Math.max(bank.a().z, Math.max(bank.b().z, bank.c().z)) > lowest + clearance && overlap(face, bank)) {
                return false;
            }
        }
        return true;
    }

    /** Whether two faces overlap seen from above, by more than a touch along a shared edge (separating axes). */
    private static boolean overlap(BoardSurface.Face f, BoardSurface.Face g) {
        Vector3[] a = { f.a(), f.b(), f.c() }, b = { g.a(), g.b(), g.c() };
        float touch = BoardRelief.metres(.01f);
        for (Vector3[] edges : new Vector3[][] { a, b }) {
            for (int i = 0; i < 3; i++) {
                float nx = edges[(i + 1) % 3].y - edges[i].y, ny = edges[i].x - edges[(i + 1) % 3].x;
                float length = (float) Math.hypot(nx, ny);
                if (length == 0) { continue; }
                float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE, minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
                for (int k = 0; k < 3; k++) {
                    float pa = (a[k].x * nx + a[k].y * ny) / length, pb = (b[k].x * nx + b[k].y * ny) / length;
                    minA = Math.min(minA, pa);
                    maxA = Math.max(maxA, pa);
                    minB = Math.min(minB, pb);
                    maxB = Math.max(maxB, pb);
                }
                if (maxA <= minB + touch || maxB <= minA + touch) { return false; }
            }
        }
        return true;
    }

    /** Whether a top lies level: the same at every detail. */
    static boolean level(BoardSurface.Face face) { return normalZ(face) >= LEVEL_Z; }

    private static float normalZ(BoardSurface.Face face) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        Vector3 normal = new Vector3(b).sub(a).crs(c.x - a.x, c.y - a.y, c.z - a.z);
        float length = normal.len();
        return length > 0 ? normal.z / length : -1;
    }

    /**
     * Whether the biome stage leaves the hex as the material is: no biome or deck mud in it or beside it (their cover
     * reaches 14 m past a hex, less than a neighbour's width), and in it and beside it no molten or frozen liquid and
     * water of one palette at most (GpuBiomeSurface.aqueous: the stage mixes nothing in for one palette).
     */
    private static boolean uncovered(BoardScene scene, BoardScene.Tile tile) {
        int palettes = 0;
        for (int direction = -1; direction < 6; direction++) {
            var near = direction < 0 ? tile : scene.neighbor(tile, direction);
            if (near == null) { continue; }
            if (BoardBiome.kind(near) != BoardScene.Biome.NONE || near.deckMud()) { return false; }
            if (near.liquid().present()) {
                if (!GpuBiomeSurface.aqueous(near)) { return false; }
                palettes |= 1 << GpuWaterShader.palette(near.liquid());
            }
        }
        return Integer.bitCount(palettes) < 2;
    }

    /** A top as this frame draws it on terrain page {@code page}: its cached twin once its pages can be read. */
    Material material(int page, Material top) {
        if (!active || !cachedPages.contains(page) || !isTop(top)) { return top; }
        return twins.computeIfAbsent(top, GpuMaterialCache::cachedTwin);
    }

    /** A top as its fill draws it: the generic program's, whatever family program the top draws with directly. */
    Material fillMaterial(Material top) {
        return generics.computeIfAbsent(top, material -> {
            Material generic = new Material(material);
            generic.remove(GpuFamilyPrograms.Constants.TYPE);
            return generic;
        });
    }

    /** A top's twin for the cached-lit program of its generic variant; a warm-up sample's, behind the loading stage. */
    static Material cachedTwin(Material top) {
        Material twin = new Material(top);
        twin.remove(GpuFamilyPrograms.Constants.TYPE);
        twin.id = top.id + CACHED;
        return twin;
    }

    /** Forgets the copies of replaced chunks' materials. */
    void retireTwins() {
        twins.clear();
        generics.clear();
    }

    // ---- Storage and invalidation ----

    /**
     * A build installed the board, now spanning {@code bounds} (world units, {@code worldMetre} a metre): after an
     * edit, the pages any level holds of {@code changed} (world units) rebuild, serving their old content meanwhile,
     * and the generation its new tops are installed at is returned (#detailChanged); after a load ({@code changed} null)
     * every page is lost.
     */
    int rebuilt(BoundingBox bounds, BoundingBox changed, float worldMetre) {
        board.set(bounds);
        metre = worldMetre;
        root = TOP_LEVEL;
        while (root < MAX_ROOT && (span(bounds.min.x, bounds.max.x, root) > ROOT_PAGES
              || span(bounds.min.y, bounds.max.y, root) > ROOT_PAGES)) { root++; }
        float rootSize = pageMetres(root) * metre;
        rootX0 = (int) Math.floor(bounds.min.x / rootSize);
        rootY0 = (int) Math.floor(bounds.min.y / rootSize);
        rootX1 = (int) Math.floor(bounds.max.x / rootSize);
        rootY1 = (int) Math.floor(bounds.max.y / rootSize);
        if (changed == null) {
            pages.invalidateAll();
            copied.clear();
            stamps.clear();
            return generation;
        }
        return detailChanged(changed);
    }

    /**
     * A chunk whose tops follow its detail changed detail over {@code changed} (world units): its pages rebuild, serving
     * their old content meanwhile where it still fits (#chunk). The generation its new tops are installed at.
     */
    int detailChanged(BoundingBox changed) {
        invalidate(changed);
        return ++generation;
    }

    /** The pages any level holds of {@code changed} (world units) rebuild, serving their old content meanwhile. */
    void invalidate(BoundingBox changed) {
        if (!changed.isValid()) { return; }
        for (int at = 0; at <= root; at++) {
            // A page reaches its gutter beyond its interior, and its sharpening one texel more.
            float size = pageMetres(at) * metre, reach = (GUTTER + 1) * (1 << at) * CLOSE_PIXEL * metre;
            pages.invalidate(at, (int) Math.floor((changed.min.x - reach) / size),
                  (int) Math.floor((changed.min.y - reach) / size), (int) Math.floor((changed.max.x + reach) / size),
                  (int) Math.floor((changed.max.y + reach) / size));
        }
    }

    /**
     * Storage for the scene's pixels ({@link #capacity}) once the board has loaded ({@link #rebuilt}); none without
     * cache tops or without a usable program. The first view's pages then build behind the loading stage
     * ({@link #prewarm}).
     */
    void allocate(boolean haveTops) {
        prewarmed = true;
        resized = settled = 0;
        reading = -1;
        if (ENABLED && haveTops && usable && means == null) { createTile(); }
        if (!ENABLED || !haveTops || !usable) {
            pages.allocate(0);
            return;
        }
        reallocate();
        prewarmed = false;
    }

    /** New storage: the old released first, so the memory read for the cap counts it free. */
    private void reallocate() {
        pages.allocate(0);
        limit = -1;
        int capacity = capacity();
        pages.allocate(capacity);
        copied.clear();
        reading = -1;
        LOGGER.info("Ground material cache: {} pages, {} MiB", capacity, capacity * pages.pageBytes() >> 20);
    }

    /**
     * The pages the scene's pixels need ({@link #TEXELS_PER_PIXEL}), at most as many as the board holds at the finest
     * level and as the memory allows ({@link #memory}), and no more than an array's layers.
     */
    private int capacity() {
        if (limit < 0) {
            long bytes = memory();
            IntBuffer values = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS, values);
            // A table entry holds the layer below FALLBACK.
            limit = Math.min(bytes / pages.pageBytes(), Math.min(values.get(0), GpuPageCache.FALLBACK - 1));
        }
        long pixels = (long) (Gdx.graphics.getBackBufferWidth() * BoardCamera.renderScale)
              * (long) (Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale);
        long wanted = (long) Math.ceil(pixels * TEXELS_PER_PIXEL / Math.pow(PAGE_TEXELS - 2 * GUTTER, 2));
        float page = pageMetres(0) * metre;
        long across = (long) Math.ceil(board.getWidth() / page) + 3, down = (long) Math.ceil(board.getHeight() / page) + 3;
        return (int) Math.max(1, Math.min(Math.min(wanted, across * down), limit));
    }

    /**
     * The page memory, read when storage is allocated, after the board's own textures, models and units: {@link #CAP_MIB}
     * when set, else half of what the driver reports free (NVX_gpu_memory_info's current available memory, or
     * ATI_meminfo's free texture pool), so units and effects loaded later, other programs and, on a GPU sharing system
     * memory, the system keep the rest; else {@link #DEFAULT_CAP_MIB}.
     */
    private long memory() {
        if (CAP_MIB != null) { return CAP_MIB << 20; }
        IntBuffer kib = BufferUtils.newIntBuffer(16);
        if (Gdx.graphics.supportsExtension("GL_NVX_gpu_memory_info")) {
            long[] values = new long[3];
            int[] queries = { 0x9047, 0x9048, 0x9049 }; // dedicated, total available, current available (KiB)
            for (int i = 0; i < 3; i++) {
                kib.clear();
                Gdx.gl.glGetIntegerv(queries[i], kib);
                values[i] = kib.get(0) * 1024L;
            }
            LOGGER.info("Ground material cache memory (NVX): dedicated {} MiB, total {} MiB, available {} MiB",
                  values[0] >> 20, values[1] >> 20, values[2] >> 20);
            return values[2] / 2;
        }
        if (Gdx.graphics.supportsExtension("GL_ATI_meminfo")) {
            Gdx.gl.glGetIntegerv(0x87FC, kib); // TEXTURE_FREE_MEMORY_ATI: free, largest block, aux free, aux largest
            LOGGER.info("Ground material cache memory (ATI): free {} MiB, largest {} MiB, auxiliary free {} MiB",
                  kib.get(0) >> 10, kib.get(1) >> 10, kib.get(2) >> 10);
            return kib.get(0) * 1024L / 2;
        }
        LOGGER.info("Ground material cache memory: not reported, {} MiB", DEFAULT_CAP_MIB);
        return DEFAULT_CAP_MIB << 20;
    }

    /** Pages of {@code at} a world range (world units) reaches. */
    private int span(float from, float to, int at) {
        float size = pageMetres(at) * metre;
        return (int) Math.floor(to / size) - (int) Math.floor(from / size) + 1;
    }

    /** A page's interior at pyramid level {@code level}, in metres. */
    private static float pageMetres(int level) { return (PAGE_TEXELS - 2 * GUTTER) * CLOSE_PIXEL * (1 << level); }

    /** Every page is lost: a setting that changes the canonical material, or a fill program's creation. */
    void invalidateAll() {
        pages.invalidateAll();
        copied.clear();
    }

    /** Whether the cache has storage (diagnostics and tests). */
    boolean allocated() { return pages.allocated(); }

    /** Test hook: off, every top draws with its own program. */
    void setEnabled(boolean value) { enabled = ENABLED && value; }

    /** Whether this frame drew every visible top cached from the level its pixels need (tests). */
    boolean complete() {
        int shown = shownPages();
        return active && exact && shown > 0 && cachedPages.size == shown;
    }

    /** The terrain pages with visible tops this frame. */
    private int shownPages() {
        Set<Integer> terrainPages = new HashSet<>();
        for (Chunk chunk : visible) { terrainPages.add(chunk.page()); }
        return terrainPages.size();
    }

    /** Whether the first view's pages are built: the loading stage waits for them. */
    boolean prewarmed() { return prewarmed; }

    /** The frame's levels, its pages and those the storage holds, the build so far (diagnostics). */
    String report() {
        int rootBuilt = 0;
        for (int x = rootX0; x <= rootX1; x++) {
            for (int y = rootY0; y <= rootY1; y++) { rootBuilt += pages.layer(root, x, y) >= 0 ? 1 : 0; }
        }
        return String.format(Locale.ROOT, "materialCache active=%b level=%d reading=%d exact=%b capacity=%d (%d MiB) "
                    + "root=%d:%d/%d under=%d admitted=%d shown=%d cached=%s fills=%d tiles=%d gpuEstimate=%.3fms "
                    + "endMs=%.2f endSteps=%d",
              active, level, reading, exact, pages.capacity(), pages.capacity() * pages.pageBytes() >> 20, root,
              rootBuilt, (rootX1 - rootX0 + 1) * (rootY1 - rootY0 + 1), under, admitted, shownPages(), cachedPages,
              pages.fills(), pages.steps(), pages.estimate(Math.max(0, level)) / 1e6, endNanos / 1e6, endSteps);
    }

    // ---- The frame ----

    /**
     * Starts a view's frame: whether its camera (orthographic, medium or far zoom, at most {@link
     * GpuPageCache#MAX_ANISOTROPY} times longer pixels along the ground than across) can draw cached tops, and the
     * level its pixels need: one to two texels a pixel across, so a read's mip stays below 1. {@code eligible} carries
     * the view's own (its view camera, not Tactical View); {@code fill} whether pages can be built at all (sculpted
     * relief, every program of the lighting warmed): the root then builds whatever the camera. A scene size the storage
     * does not fit takes new storage once it held for {@link #SETTLE_FRAMES}; that frame draws directly.
     */
    void begin(Camera camera, boolean fill, boolean eligible, Tops tops) {
        view = camera;
        this.tops = tops;
        filling = fill && enabled && usable;
        active = false;
        exact = false;
        under = admitted = 0;
        cachedPages.clear();
        topBounds.clear();
        visible.clear();
        near.inf();
        pages.begin();
        if (!pages.allocated()) { return; }
        int capacity = capacity();
        if (capacity == pages.capacity()) {
            settled = 0;
        } else {
            settled = capacity == resized ? settled + 1 : 0;
            resized = capacity;
            if (settled >= SETTLE_FRAMES) {
                reallocate();
                settled = 0;
                return;
            }
        }
        if (!filling || !eligible || camera.projection.val[Matrix4.M33] == 0) { return; }
        float[] pixel = groundPixel(camera);
        if (pixel[0] < MIN_PIXEL_METRES || pixel[1] > pixel[0] * GpuPageCache.MAX_ANISOTROPY) { return; }
        level = Math.max(0, (int) Math.floor(Math.log(pixel[0] / CLOSE_PIXEL) / Math.log(2)));
        active = level <= TOP_LEVEL;
    }

    /**
     * The view's pixel on a level top, metres: its shorter and longer side; 0 for a view along the ground. One pixel
     * right moves its point along the camera's right; one pixel up moves the eye's ray along the screen's up, which
     * meets the level top that much further along the ground. Scene pixels, as the render resolution draws them.
     */
    private float[] groundPixel(Camera camera) {
        float scale = BoardCamera.scenePixelScale();
        float width = camera.viewportWidth * scale, height = camera.viewportHeight * scale;
        Vector3 direction = camera.direction;
        if (width <= 0 || height <= 0 || Math.abs(direction.z) < .05f) { return new float[] { 0, 0 }; }
        Vector3 right = new Vector3(direction).crs(camera.up).nor();
        Vector3 up = new Vector3(right).crs(direction).nor();
        float across = 2 / (Math.abs(camera.projection.val[Matrix4.M00]) * width) / metre;
        float upward = 2 / (Math.abs(camera.projection.val[Matrix4.M11]) * height) / metre
              * up.mulAdd(direction, -up.z / direction.z).len();
        return new float[] { Math.min(across, upward), Math.max(across, upward) };
    }

    /**
     * A chunk's cache tops ({@code bounds}, world units) on terrain page {@code page}, and whether it is in view. Tops
     * that follow its detail were installed at generation {@code since} (#detailChanged), else 0: a page built before
     * may lack their coverage, so they draw cached only from a page (their own or a coarser one standing in) whose build
     * began since.
     */
    void chunk(int page, BoundingBox bounds, boolean inView, int since) {
        if (!filling || !bounds.isValid()) { return; }
        topBounds.add(bounds);
        if (active && inView) { visible.add(new Chunk(page, bounds, since)); }
    }

    /** {@link #end(long, long)} within a view frame's share of the fill. */
    void end() {
        long start = System.nanoTime(), steps = pages.steps();
        end(FILL_NANOS, FILL_CPU_NANOS);
        endNanos = System.nanoTime() - start;
        endSteps = pages.steps() - steps;
    }

    /**
     * Builds what this frame reads within {@code budget} nanoseconds of GPU time and {@code cpuNanos} of CPU time and
     * publishes it: the pages under the visible tops at the level the view needs, nearest its centre first and as many
     * as the storage holds; a ring of prefetched pages around them, and their parents one level up wherever those copy
     * from their children, ready for a zoom out. After a zoom in, the coarser level's pages stay while the finer ones
     * build. True once nothing is left to build.
     */
    private boolean end(long budget, long cpuNanos) {
        if (!pages.allocated() || !filling) { return true; }
        long deadline = System.nanoTime() + cpuNanos;
        // The root first, whatever the view: it stands in for every admitted page still building.
        int rootPages = 0;
        boolean rootBuilt = true;
        for (int x = rootX0; x <= rootX1; x++) {
            for (int y = rootY0; y <= rootY1; y++, rootPages++) {
                pages.need(root, x, y, true);
                // A stale root page (an edit) rebuilds too, whatever the view.
                rootBuilt &= pages.current(root, x, y);
            }
        }
        Set<Long> wanted = active ? under(level) : new HashSet<>();
        under = wanted.size();
        if (wanted.isEmpty()) {
            active = false;
            reading = -1;
            return rootBuilt || build(budget, deadline);
        }
        // One level a frame for the whole view: after a zoom in, the coarser level read so far while every page of it
        // under the view is still there, until the finer one's are all built.
        Set<Long> coarse = reading > level && reading <= TOP_LEVEL ? under(reading) : new HashSet<>();
        for (long page : coarse) {
            if (pages.layer(reading, unpackX(page), unpackY(page)) < 0) {
                coarse.clear();
                break;
            }
        }
        if (coarse.isEmpty()) { reading = level; }
        for (long page : coarse) { pages.need(reading, unpackX(page), unpackY(page), true); }
        // The view's centre on the ground, roughly: the camera's position along its direction to the board's floor.
        Vector3 focus = new Vector3(view.position);
        if (view.direction.z != 0) { focus.mulAdd(view.direction, (board.min.z - view.position.z) / view.direction.z); }
        float size = pageMetres(level) * metre;
        List<Long> order = new ArrayList<>(wanted);
        order.sort((a, b) -> Float.compare(distance(a, size, focus), distance(b, size, focus)));
        Set<Long> held = new HashSet<>(order.subList(0, Math.min(order.size(), Math.max(0,
              pages.capacity() - rootPages - coarse.size() - SPARES))));
        admitted = held.size();
        int[] window = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (long page : order) {
            if (!held.contains(page)) { break; }
            pages.need(level, unpackX(page), unpackY(page), true);
            extend(window, unpackX(page), unpackY(page));
        }
        nearTops(window, level, 3);
        // Their parents over the view and a page around it, one step each (coarse, or copied from built children),
        // ready for a zoom out, before the ring's tiles.
        if (level < TOP_LEVEL) {
            for (int x = Math.floorDiv(window[0] - 1, 2); x <= Math.floorDiv(window[2] + 1, 2); x++) {
                for (int y = Math.floorDiv(window[1] - 1, 2); y <= Math.floorDiv(window[3] + 1, 2); y++) {
                    // Built ones stay needed, so other prefetches never take their layers back and forth.
                    if (pages.layer(level + 1, x, y) >= 0
                          || (level + 1 >= COARSE ? !empty(level + 1, x, y) : copiable(level + 1, x, y))) {
                        pages.need(level + 1, x, y, false);
                    }
                }
            }
        }
        // The ring: the pages around them that hold tops, built once the view's are, where layers are left.
        for (long page : held) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = unpackX(page) + dx, y = unpackY(page) + dy;
                    if (wanted.contains(pack(x, y)) || !touches(x * size, y * size, (x + 1) * size, (y + 1) * size)) {
                        continue;
                    }
                    pages.need(level, x, y, false);
                }
            }
        }
        // A stale page the frame reads under tops installed since its build serves them nothing (#chunk): it builds
        // first, as does a stale root, which stands in for every page while they build.
        int newest = 0;
        for (var page : since(reading).entrySet()) {
            int x = unpackX(page.getKey()), y = unpackY(page.getKey());
            newest = Math.max(newest, page.getValue());
            if (stamps.getOrDefault(new PageKey(reading, x, y), 0) < page.getValue()) { pages.urgent(reading, x, y); }
        }
        for (int x = rootX0; x <= rootX1; x++) {
            for (int y = rootY0; y <= rootY1; y++) {
                if (stamps.getOrDefault(new PageKey(root, x, y), 0) < newest) { pages.urgent(root, x, y); }
            }
        }
        boolean done = build(budget, deadline);
        if (copied.size() > 2 * pages.capacity()) { copied.removeIf(page -> pages.layer(page.at(), page.x(), page.y()) < 0); }
        if (stamps.size() > 2 * pages.capacity()) {
            stamps.keySet().removeIf(page -> pages.layer(page.at(), page.x(), page.y()) < 0);
        }
        // The finer level once every page of it the storage holds is built.
        if (reading != level) {
            boolean built = true;
            for (long page : held) { built &= pages.layer(level, unpackX(page), unpackY(page)) >= 0; }
            if (built) { reading = level; }
        }
        // The table over the level the frame reads; a window too large for it draws everything directly.
        Set<Long> read = reading == level ? wanted : coarse;
        int[] table = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (long page : read) { extend(table, unpackX(page), unpackY(page)); }
        float metres = pageMetres(reading);
        // A page the storage holds reads a coarser one up to the root while it builds; one it cannot hold draws
        // directly (never a coarser level for lack of room). Under tops that follow their chunk's detail, a page serves
        // only if its build began since they were installed (#chunk).
        Map<Long, Integer> since = since(reading);
        GpuPageCache.StandIns standIns = new GpuPageCache.StandIns() {
            @Override public boolean allowed(int x, int y) { return reading == level && held.contains(pack(x, y)); }

            @Override public boolean serves(int at, int x, int y, int wx, int wy) {
                return stamps.getOrDefault(new PageKey(at, x, y), 0) >= since.getOrDefault(pack(wx, wy), 0);
            }
        };
        if (!pages.upload(reading, root, table[0], table[1], table[2] - table[0] + 1, table[3] - table[1] + 1,
              table[0] * metres, table[1] * metres, metres, standIns)) {
            active = false;
            return true;
        }
        pages.bindTable(BINDING);
        // A terrain page draws cached once every page under its visible tops can be read at that level, or (held and
        // still building) stood in for by a coarser one; the frame is exact once each is its own and current.
        exact = reading == level;
        Set<Integer> direct = new HashSet<>();
        Set<Long> chunkPages = new HashSet<>();
        for (Chunk chunk : visible) {
            chunkPages.clear();
            under(chunk.bounds(), reading, chunkPages);
            for (long page : chunkPages) {
                int x = unpackX(page), y = unpackY(page);
                int entry = pages.entry(reading, root, x, y, standIns);
                if (entry < 0) { direct.add(chunk.page()); }
                exact &= entry >= 0 && entry < GpuPageCache.FALLBACK && pages.current(reading, unpackX(page), unpackY(page));
            }
        }
        for (Chunk chunk : visible) {
            if (!direct.contains(chunk.page()) && !cachedPages.contains(chunk.page())) { cachedPages.add(chunk.page()); }
        }
        return done;
    }

    /** The pages of {@code at} under visible tops that follow their detail, each with the latest install among them. */
    private Map<Long, Integer> since(int at) {
        Map<Long, Integer> since = new HashMap<>();
        Set<Long> below = new HashSet<>();
        for (Chunk chunk : visible) {
            if (chunk.since() == 0) { continue; }
            below.clear();
            under(chunk.bounds(), at, below);
            for (long page : below) { since.merge(page, chunk.since(), Math::max); }
        }
        return since;
    }

    /** The pages of {@code at} under the visible tops that the view's frustum meets. */
    private Set<Long> under(int at) {
        Set<Long> pagesUnder = new HashSet<>();
        for (Chunk chunk : visible) { under(chunk.bounds(), at, pagesUnder); }
        return pagesUnder;
    }

    /** Adds the pages of {@code at} under {@code bounds} (world units) that the view's frustum meets. */
    private void under(BoundingBox bounds, int at, Set<Long> into) {
        float size = pageMetres(at) * metre;
        int x0 = (int) Math.floor(bounds.min.x / size), x1 = (int) Math.floor(bounds.max.x / size);
        int y0 = (int) Math.floor(bounds.min.y / size), y1 = (int) Math.floor(bounds.max.y / size);
        BoundingBox box = new BoundingBox();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                box.min.set(x * size, y * size, bounds.min.z);
                box.max.set((x + 1) * size, (y + 1) * size, bounds.max.z);
                box.update();
                if (view.frustum.boundsInFrustum(box)) { into.add(pack(x, y)); }
            }
        }
    }

    private static void extend(int[] window, int x, int y) {
        window[0] = Math.min(window[0], x);
        window[1] = Math.min(window[1], y);
        window[2] = Math.max(window[2], x);
        window[3] = Math.max(window[3], y);
    }

    /**
     * The chunks whose tops reach the pages of {@code at} in {@code window}, grown by {@code margin} pages: the only
     * ones this frame's fills and ring need, on a board of any length.
     */
    private void nearTops(int[] window, int at, int margin) {
        float size = pageMetres(at) * metre;
        float minX = (window[0] - margin) * size, minY = (window[1] - margin) * size;
        float maxX = (window[2] + margin + 1) * size, maxY = (window[3] + margin + 1) * size;
        near.set(new Vector3(minX, minY, -Float.MAX_VALUE), new Vector3(maxX, maxY, Float.MAX_VALUE));
        nearBounds.clear();
        for (BoundingBox bounds : topBounds) {
            if (bounds.max.x >= minX && bounds.min.x <= maxX && bounds.max.y >= minY && bounds.min.y <= maxY) {
                nearBounds.add(bounds);
            }
        }
    }

    /**
     * The loading stage's build, a loading frame's share at a time: the root, whatever the camera, then the pages of
     * the view's first frame and their ring, which {@code chunks} names ({@link #chunk}). {@link #prewarmed} once they
     * are all built, or when there is no view (an offline fixture).
     */
    void prewarm(Camera camera, boolean fill, boolean eligible, Tops tops, Runnable chunks) {
        if (prewarmed) { return; }
        if (camera == null) {
            prewarmed = true;
            return;
        }
        begin(camera, fill, eligible, tops);
        chunks.run();
        prewarmed = end(PREWARM_NANOS, PREWARM_NANOS);
    }

    /** Builds the pages needed so far, with the fill's state set up and restored around it. */
    private boolean build(long budget, long deadline) {
        boolean srgb = Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        boolean scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        int vertexArray = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
        int framebuffer = GL11.glGetInteger(GL20.GL_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        try {
            return pages.fill(budget, deadline, this::fill);
        } finally {
            if (srgb) { Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
            else { Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
            if (scissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); } else { Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); }
            org.lwjgl.opengl.GL30.glBindVertexArray(vertexArray);
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
    }

    private static float distance(long page, float size, Vector3 focus) {
        return Vector3.dst2((unpackX(page) + .5f) * size, (unpackY(page) + .5f) * size, 0, focus.x, focus.y, 0);
    }

    /**
     * Whether any chunk's tops reach the world rectangle (world units): those near this frame's pages when it lies
     * among them, else every chunk's (a stale coarser page rebuilt while a zoom in holds it, say).
     */
    private boolean touches(float minX, float minY, float maxX, float maxY) {
        boolean inside = minX >= near.min.x && maxX <= near.max.x && minY >= near.min.y && maxY <= near.max.y;
        for (BoundingBox bounds : inside ? nearBounds : topBounds) {
            if (bounds.max.x >= minX && bounds.min.x <= maxX && bounds.max.y >= minY && bounds.min.y <= maxY) {
                return true;
            }
        }
        return false;
    }

    // ---- Building a page ----

    /**
     * Step {@code step} of page (x, y) of {@code at}, into {@code layer}: the whole page copied from its sixteen
     * children when they are built, else one of its tiles rendered, reduced and copied into the page's three levels,
     * then sharpened after the last (GpuPageCache.Filler). A tile whose tops drew a program that still links leaves
     * the page to start over.
     */
    private int fill(int at, int x, int y, int layer, int step) {
        if (step == 0 && copiable(at, x, y)) {
            copyChildren(at, x, y, layer);
            // Its children are current: drawn from the geometry of now.
            stamps.put(new PageKey(at, x, y), generation);
            copied.add(new PageKey(at, x, y));
            return GpuPageCache.DONE;
        }
        // An exact level averages 2^at x 2^at base tiles at the close view's texel; a coarse one is one multisampled
        // render at its own. Every tile is world-aligned on the page's grid, gutters included.
        int sub = at < COARSE ? at : 0, side = 1 << sub, i = step % side, j = step / side;
        float texel = CLOSE_PIXEL * (1 << (at - sub)), tile = TILE * texel * metre;
        float minX = (x * pageMetres(at) - GUTTER * (1 << at) * CLOSE_PIXEL) * metre + i * tile;
        float minY = (y * pageMetres(at) - GUTTER * (1 << at) * CLOSE_PIXEL) * metre + j * tile;
        int last = sub + LEVELS - 1;
        // A coarse page may lie far from the view (the root): touches then tests every chunk's tops.
        if (touches(minX, minY, minX + tile, minY + tile)) {
            int unlinked = GpuShaderProvider.unlinkedDraws();
            fillTexel = texel;
            try {
                if (at >= COARSE) { renderCoarse(minX, minY, tile, last); } else { renderTile(minX, minY, tile, last); }
            } finally {
                fillTexel = CLOSE_PIXEL;
            }
            if (GpuShaderProvider.unlinkedDraws() != unlinked) { return GpuPageCache.AGAIN; }
        } else {
            clearTile(last);
        }
        copyTile(layer, sub, i, j);
        if (step < side * side - 1) { return GpuPageCache.MORE; }
        sharpen(layer);
        copied.remove(new PageKey(at, x, y));
        stamps.put(new PageKey(at, x, y), generation);
        return GpuPageCache.DONE;
    }

    /**
     * Whether page (x, y) of {@code at} can be copied from its sixteen children: each built, current and not a copy,
     * or reached by no top (empty, beyond a board's edge).
     */
    private boolean copiable(int at, int x, int y) {
        if (at == 0 || at >= COARSE) { return false; }
        for (int cx = 2 * x - 1; cx <= 2 * x + 2; cx++) {
            for (int cy = 2 * y - 1; cy <= 2 * y + 2; cy++) {
                boolean built = pages.current(at - 1, cx, cy) && !copied.contains(new PageKey(at - 1, cx, cy));
                if (!built && !empty(at - 1, cx, cy)) { return false; }
            }
        }
        return true;
    }

    /** Whether no top reaches page (x, y) of {@code at}, gutters included. */
    private boolean empty(int at, int x, int y) {
        float size = pageMetres(at) * metre, gutter = GUTTER * (1 << at) * CLOSE_PIXEL * metre;
        return !touches(x * size - gutter, y * size - gutter, (x + 1) * size + gutter, (y + 1) * size + gutter);
    }

    /**
     * A page from its children: its levels 0 and 1 are their levels 1 and 2, the interiors of the 4 x 4 children around
     * it (the outer ones' edges make its gutters), exactly; its level 2 their means. Through the base tile's levels.
     */
    private void copyChildren(int at, int x, int y, int layer) {
        Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        Gdx.gl30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        // Empty children leave their part clear.
        clearTile(LEVELS - 2);
        Gdx.gl30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        Gdx.gl30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        for (int mip = 1; mip < LEVELS; mip++) {
            int gutter = GUTTER >> mip, interior = (PAGE_TEXELS - 2 * GUTTER) >> mip;
            int extent = PAGE_TEXELS >> (mip - 1), parentGutter = GUTTER >> (mip - 1);
            for (int i = 0; i < 4; i++) {
                int d0 = parentGutter + (i - 1) * interior, x0 = Math.max(d0, 0), x1 = Math.min(d0 + interior, extent);
                for (int j = 0; j < 4; j++) {
                    int e0 = parentGutter + (j - 1) * interior, y0 = Math.max(e0, 0), y1 = Math.min(e0 + interior, extent);
                    int child = pages.layer(at - 1, 2 * x - 1 + i, 2 * y - 1 + j);
                    if (child < 0) { continue; }
                    for (int target = 0; target < FORMATS.length; target++) {
                        Gdx.gl30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                              pages.array(target).getTextureObjectHandle(), mip, child);
                        Gdx.gl30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                              GL20.GL_TEXTURE_2D, tiles[target], mip - 1);
                        Gdx.gl30.glBlitFramebuffer(gutter + x0 - d0, gutter + y0 - e0, gutter + x1 - d0, gutter + y1 - e0,
                              x0, y0, x1, y1, GL20.GL_COLOR_BUFFER_BIT, GL20.GL_NEAREST);
                    }
                }
            }
        }
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, tileFramebuffer);
        Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        reduce(LEVELS - 1, LEVELS - 1);
        copyTile(layer, 0, 0, 0);
    }

    /** The canonical material over a base tile, top-down, then its means up to level {@code last}. */
    private void renderTile(float minX, float minY, float size, int last) {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, tileFramebuffer);
        attachTile(0);
        drawTops(minX, minY, size);
        reduce(1, last);
    }

    /**
     * A coarse page's whole extent rendered once, 4x multisampled, and resolved into the base tile's first level (the
     * colour in linear light), then its means up to level {@code last}.
     */
    private void renderCoarse(float minX, float minY, float size, int last) {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, coarseFramebuffer);
        drawTops(minX, minY, size);
        // With FRAMEBUFFER_SRGB on, the resolve averages the colour in linear light; GL guarantees it from 4.4, and
        // the drivers here do it on 3.3 too (else the colour's coarse levels would read slightly dark at edges).
        Gdx.gl30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, coarseFramebuffer);
        Gdx.gl30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        for (int target = 0; target < FORMATS.length; target++) {
            Gdx.gl30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + target);
            Gdx.gl30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
                  tiles[target], 0);
            Gdx.gl30.glBlitFramebuffer(0, 0, TILE, TILE, 0, 0, TILE, TILE, GL20.GL_COLOR_BUFFER_BIT, GL20.GL_NEAREST);
        }
        Gdx.gl30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, tileFramebuffer);
        reduce(1, last);
    }

    /** The canonical material over a square of the board, top-down, into the bound framebuffer's TILE x TILE. */
    private void drawTops(float minX, float minY, float size) {
        Gdx.gl.glViewport(0, 0, TILE, TILE);
        Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        fillCamera.viewportWidth = fillCamera.viewportHeight = size;
        fillCamera.zoom = 1;
        fillCamera.direction.set(0, 0, -1);
        fillCamera.up.set(0, 1, 0);
        fillCamera.position.set(minX + size / 2, minY + size / 2, board.max.z + metre * 100);
        fillCamera.near = metre * 50;
        fillCamera.far = board.getDepth() + metre * 200;
        fillCamera.update();
        tops.draw(fillCamera, minX, minY, minX + size, minY + size);
    }

    /** An empty base tile: no coverage at any of its levels up to {@code last}. */
    private void clearTile(int last) {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, tileFramebuffer);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glClearColor(0, 0, 0, 0);
        for (int mip = 0; mip <= last; mip++) {
            attachTile(mip);
            Gdx.gl.glViewport(0, 0, TILE >> mip, TILE >> mip);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * The tile's levels {@code first} to {@code last}, each the exact mean of the level above. Every value is
     * premultiplied by the coverage and the normals and roughness are kept as sums (terrain-sculpt.frag,
     * cachedMaterial), so a plain mean is the coverage-weighted average; the colour in linear light, its target being
     * sRGB. The tile's framebuffer is bound.
     */
    private void reduce(int first, int last) {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        means.bind();
        for (int i = 0; i < FORMATS.length; i++) { means.setUniformi("u_source" + i, i); }
        org.lwjgl.opengl.GL30.glBindVertexArray(mipVertices);
        for (int mip = first; mip <= last; mip++) {
            attachTile(mip);
            // Only the level read is in range: writing the next one is no feedback loop.
            levels(mip - 1, mip - 1);
            Gdx.gl.glViewport(0, 0, TILE >> mip, TILE >> mip);
            Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 3);
        }
        unbindTile();
    }

    /**
     * A built page's levels sharpened in place ({@link #SHARPEN}): each copied to the tile's level of its size, then
     * drawn back through the kernel where the texel and its eight neighbours are fully covered, as it was elsewhere.
     */
    private void sharpen(int layer) {
        Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        Gdx.gl30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        Gdx.gl30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        for (int mip = 0; mip < LEVELS; mip++) {
            int size = PAGE_TEXELS >> mip;
            for (int target = 0; target < FORMATS.length; target++) {
                Gdx.gl30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                      pages.array(target).getTextureObjectHandle(), mip, layer);
                Gdx.gl30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
                      tiles[target], mip);
                Gdx.gl30.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL20.GL_COLOR_BUFFER_BIT,
                      GL20.GL_NEAREST);
            }
        }
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, pageFramebuffer);
        Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        sharpen.bind();
        for (int i = 0; i < FORMATS.length; i++) { sharpen.setUniformi("u_source" + i, i); }
        sharpen.setUniformf("u_sharpen", SHARPEN);
        org.lwjgl.opengl.GL30.glBindVertexArray(mipVertices);
        levels(0, LEVELS - 1);
        for (int mip = 0; mip < LEVELS; mip++) {
            pages.attach(layer, mip);
            sharpen.setUniformi("u_level", mip);
            Gdx.gl.glViewport(0, 0, PAGE_TEXELS >> mip, PAGE_TEXELS >> mip);
            Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 3);
        }
        unbindTile();
    }

    /** Binds the tile's targets to the first units with only levels {@code base} to {@code max} in range. */
    private void levels(int base, int max) {
        for (int i = 0; i < FORMATS.length; i++) {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + i);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, tiles[i]);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, base);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, max);
        }
    }

    /** Every level of the tile's targets in range again, and their units free. */
    private void unbindTile() {
        levels(0, TILE_LEVELS - 1);
        for (int i = 0; i < FORMATS.length; i++) {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + i);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** The tile's levels {@code at} to at+2 into the page's levels 0 to 2, at block (i, j) of each: plain copies. */
    private void copyTile(int layer, int at, int i, int j) {
        Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        Gdx.gl30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        for (int mip = 0; mip < LEVELS; mip++) {
            int block = TILE >> (at + mip);
            for (int target = 0; target < FORMATS.length; target++) {
                Gdx.gl30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
                      tiles[target], at + mip);
                Gdx.gl30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                      pages.array(target).getTextureObjectHandle(), mip, layer);
                Gdx.gl30.glBlitFramebuffer(0, 0, block, block, i * block, j * block, (i + 1) * block, (j + 1) * block,
                      GL20.GL_COLOR_BUFFER_BIT, GL20.GL_NEAREST);
            }
        }
    }

    private void attachTile(int mip) {
        for (int i = 0; i < FORMATS.length; i++) {
            Gdx.gl30.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL20.GL_TEXTURE_2D,
                  tiles[i], mip);
        }
    }

    /** The base tile's targets with every level, its depth, its framebuffers and the reduction and sharpening. */
    private void createTile() {
        int previous = GL11.glGetInteger(GL20.GL_FRAMEBUFFER_BINDING);
        for (int i = 0; i < FORMATS.length; i++) {
            tiles[i] = Gdx.gl.glGenTexture();
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, tiles[i]);
            for (int mip = 0; mip < TILE_LEVELS; mip++) {
                Gdx.gl30.glTexImage2D(GL20.GL_TEXTURE_2D, mip, FORMATS[i], TILE >> mip, TILE >> mip, 0, GL20.GL_RGBA,
                      GL20.GL_UNSIGNED_BYTE, null);
            }
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, TILE_LEVELS - 1);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST_MIPMAP_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
        }
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        tileDepth = Gdx.gl.glGenRenderbuffer();
        Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, tileDepth);
        Gdx.gl.glRenderbufferStorage(GL20.GL_RENDERBUFFER, GL20.GL_DEPTH_COMPONENT16, TILE, TILE);
        Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, 0);
        IntBuffer targets = BufferUtils.newIntBuffer(FORMATS.length);
        for (int i = 0; i < FORMATS.length; i++) { targets.put(GL30.GL_COLOR_ATTACHMENT0 + i); }
        targets.flip();
        tileFramebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, tileFramebuffer);
        Gdx.gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, tileDepth);
        attachTile(0);
        Gdx.gl30.glDrawBuffers(FORMATS.length, targets);
        int status = Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
        // The page framebuffer draws the sharpening into a page's three arrays at once (GpuPageCache.attach).
        pageFramebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, pageFramebuffer);
        targets.rewind();
        Gdx.gl30.glDrawBuffers(FORMATS.length, targets);
        // The coarse pages' multisampled render (4 samples, which GL 3 guarantees for these formats).
        coarseFramebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, coarseFramebuffer);
        for (int i = 0; i < FORMATS.length; i++) {
            coarseTargets[i] = Gdx.gl.glGenRenderbuffer();
            Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, coarseTargets[i]);
            Gdx.gl30.glRenderbufferStorageMultisample(GL20.GL_RENDERBUFFER, 4, FORMATS[i], TILE, TILE);
            Gdx.gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL20.GL_RENDERBUFFER,
                  coarseTargets[i]);
        }
        coarseDepth = Gdx.gl.glGenRenderbuffer();
        Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, coarseDepth);
        Gdx.gl30.glRenderbufferStorageMultisample(GL20.GL_RENDERBUFFER, 4, GL20.GL_DEPTH_COMPONENT16, TILE, TILE);
        Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, 0);
        Gdx.gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, coarseDepth);
        targets.rewind();
        Gdx.gl30.glDrawBuffers(FORMATS.length, targets);
        int coarseStatus = Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
        if (coarseStatus != GL20.GL_FRAMEBUFFER_COMPLETE) { status = coarseStatus; }
        readFramebuffer = Gdx.gl.glGenFramebuffer();
        drawFramebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previous);
        if (status != GL20.GL_FRAMEBUFFER_COMPLETE) {
            usable = false;
            LOGGER.warn("Ground material cache off: base tile framebuffer incomplete ({})", status);
        }
        mipVertices = org.lwjgl.opengl.GL30.glGenVertexArrays();
        means = program("ground material cache means", """
              void main() {
                  ivec2 p = ivec2(gl_FragCoord.xy) * 2;
                  colour = mean(u_source0, p);
                  moments = mean(u_source1, p);
                  detail = mean(u_source2, p);
              }
              """);
        sharpen = program("ground material cache sharpening", """
              uniform int u_level;
              uniform float u_sharpen;
              void main() {
                  ivec2 p = ivec2(gl_FragCoord.xy), size = textureSize(u_source0, u_level);
                  colour = texelFetch(u_source0, p, u_level);
                  moments = texelFetch(u_source1, p, u_level);
                  detail = texelFetch(u_source2, p, u_level);
                  if (any(lessThan(p, ivec2(1))) || any(greaterThan(p, size - 2))) return;
                  // The separable kernel (-a, 1 + 2a, -a) squared, where the texel and its neighbours are all covered:
                  // colour, height, cavity and relief; elsewhere (a top's edge) the plain average stays.
                  vec4 c = vec4(0.0);
                  vec2 d = vec2(0.0);
                  float covered = 1.0;
                  for (int j = -1; j <= 1; j++) {
                      for (int i = -1; i <= 1; i++) {
                          float w = (i == 0 ? 1.0 + 2.0 * u_sharpen : -u_sharpen)
                                * (j == 0 ? 1.0 + 2.0 * u_sharpen : -u_sharpen);
                          ivec2 q = p + ivec2(i, j);
                          covered = min(covered, texelFetch(u_source1, q, u_level).a);
                          c += w * texelFetch(u_source0, q, u_level);
                          d += w * texelFetch(u_source2, q, u_level).xy;
                      }
                  }
                  if (covered > .998) {
                      colour = clamp(c, 0.0, 1.0);
                      detail.xy = clamp(d, 0.0, 1.0);
                  }
              }
              """);
    }

    /**
     * A full-screen triangle drawing the three targets from the tile's three ({@code u_source0..2}, a texel's four
     * below it averaged by {@code mean}) with the given main.
     */
    private static ShaderProgram program(String name, String main) {
        String vertex = """
              #version 330 core
              void main() {
                  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
              }
              """;
        String fragment = """
              #version 330 core
              uniform sampler2D u_source0, u_source1, u_source2;
              layout(location = 0) out vec4 colour;
              layout(location = 1) out vec4 moments;
              layout(location = 2) out vec4 detail;
              vec4 mean(sampler2D source, ivec2 p) {
                  return .25 * (texelFetch(source, p, 0) + texelFetch(source, p + ivec2(1, 0), 0)
                        + texelFetch(source, p + ivec2(0, 1), 0) + texelFetch(source, p + ivec2(1, 1), 0));
              }
              """ + main;
        return GpuGlsl.compile(name, vertex, fragment);
    }

    // ---- Programs ----

    /** A fill program's texel (fillTexel). Its creation (a load, or a shader edit) rebuilds every page. */
    void registerFill(DefaultShader shader) {
        invalidateAll();
        shader.register("u_cacheClosePixel", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, fillTexel);
            }
        });
    }

    /**
     * A cached-lit program's page arrays and table. A program the context cannot serve (more samplers than units, a
     * table of another size) leaves the cache unused for this terrain, and frees its storage.
     */
    void registerCached(DefaultShader shader) {
        int program = shader.program.getHandle();
        int block = GL31.glGetUniformBlockIndex(program, "MaterialCachePages");
        if (block == GL31.GL_INVALID_INDEX) { return; }
        int size = GL31.glGetActiveUniformBlocki(program, block, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
        int samplers = 0;
        for (String name : shader.program.getUniforms()) {
            int type = shader.program.getUniformType(name);
            if (type == GL20.GL_SAMPLER_2D || type == GL20.GL_SAMPLER_CUBE || type == GL30.GL_SAMPLER_2D_ARRAY
                  || type == GL30.GL_SAMPLER_3D || type == GL30.GL_SAMPLER_2D_SHADOW) { samplers++; }
        }
        IntBuffer units = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_IMAGE_UNITS, units);
        if (size != GpuPageCache.TABLE_BYTES || samplers > units.get(0)) {
            LOGGER.warn("Ground material cache off: page table of {} bytes (expected {}), {} samplers of {} units",
                  size, GpuPageCache.TABLE_BYTES, samplers, units.get(0));
            usable = false;
            pages.allocate(0);
            prewarmed = true;
            return;
        }
        GL31.glUniformBlockBinding(program, block, BINDING);
        for (int i = 0; i < SAMPLERS.length; i++) {
            int index = i;
            shader.register(SAMPLERS[i], new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    if (pages.allocated()) { target.set(id, pages.array(index)); }
                }
            });
        }
    }

    private static long pack(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    private static int unpackX(long packed) { return (int) (packed >> 32); }

    private static int unpackY(long packed) { return (int) packed; }

    void dispose() {
        pages.dispose();
        if (means != null) { means.dispose(); means = null; }
        if (sharpen != null) { sharpen.dispose(); sharpen = null; }
        if (mipVertices != 0) { org.lwjgl.opengl.GL30.glDeleteVertexArrays(mipVertices); mipVertices = 0; }
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != 0) { Gdx.gl.glDeleteTexture(tiles[i]); tiles[i] = 0; }
        }
        if (tileDepth != 0) { Gdx.gl.glDeleteRenderbuffer(tileDepth); tileDepth = 0; }
        for (int i = 0; i < coarseTargets.length; i++) {
            if (coarseTargets[i] != 0) { Gdx.gl.glDeleteRenderbuffer(coarseTargets[i]); coarseTargets[i] = 0; }
        }
        if (coarseDepth != 0) { Gdx.gl.glDeleteRenderbuffer(coarseDepth); coarseDepth = 0; }
        for (int framebuffer : new int[] { tileFramebuffer, readFramebuffer, drawFramebuffer, pageFramebuffer,
              coarseFramebuffer }) {
            if (framebuffer != 0) { Gdx.gl.glDeleteFramebuffer(framebuffer); }
        }
        tileFramebuffer = readFramebuffer = drawFramebuffer = pageFramebuffer = coarseFramebuffer = 0;
        retireTwins();
        copied.clear();
    }
}
