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
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Frustum;
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
 * <p>
 * Steep natural walls, which no page seen from above can hold, are charted ({@link Charts}): each chunk's walls lie
 * flat in its cell of a region of the same plane past the board's north edge, where their pages build, mip and stand in
 * as the tops' do, at the levels up to {@link #WALL_TOP}, read through a second page table ({@link #WALL_BINDING}).
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
    /** The walls' page table and its binding: a wall's cached-lit program reads MaterialCachePages there. */
    private static final int WALL_TABLE = 1;
    static final int WALL_BINDING = 2;
    /**
     * The coarsest level walls are read at: their slots start on, and stay apart by, a mip-2 texel of it
     * ({@link Charts#ALIGN}). Coarser views draw them directly.
     */
    static final int WALL_TOP = 3;
    /** The id suffix of a wall's material, whose mesh carries each vertex's chart position ({@link #CHART}). */
    private static final String WALL = "#cacheWall";
    /** A wall vertex's place in its chart: world units in the wall region (a generic attribute, no Attribute type). */
    static final String CHART = "a_wallChart";
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
     * The pages a board's charted walls take, in units of its tops': their slots leave room around each wall, and a
     * view needs every wall it sees whichever way it faces (Kozice Valley: 1.6 to 2.0 times the tops' pages at levels
     * 0 to 2).
     */
    private static final int WALL_PAGES = 2;
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
     * Steeper banks and cliffs are walls ({@link #wall}).
     */
    private static final float SLOPE_Z = .7f;
    /** The lowest normal Z a charted wall may have: a few degrees past vertical, as natural cliffs lean. */
    private static final float LEAN = -.1f;
    /** The fill's GPU time a frame (a target), and its CPU time (a bound); a loading frame's prewarm takes more. */
    private static final long FILL_NANOS = 2_000_000, FILL_CPU_NANOS = 3_000_000, PREWARM_NANOS = 30_000_000;
    /**
     * Set by the GPU test tasks (gpuBoardSmoke, gpuBoardLargeMapTest): a frame builds every page it needs, within
     * {@link #AT_ONCE_NANOS}. No frame a test reads then shows a coarser stand-in while pages build, however the test
     * settles; the pages built are the same.
     */
    private static final boolean FILL_AT_ONCE = Boolean.getBoolean("megamek.gpu.materialCacheFillAtOnce");
    private static final long AT_ONCE_NANOS = 60_000_000_000L;

    /** Draws the cache tops that reach a world rectangle (world units) with the fill programs, from {@code camera}. */
    interface Tops {
        void draw(Camera camera, float minX, float minY, float maxX, float maxY);
    }

    /**
     * A chunk's cache tops or walls this frame: their terrain page, bounds in the pages' plane (world units: the tops',
     * or each seen wall slot's) and generation (#chunk); walls also each slot's centre on the board, which orders their
     * pages.
     */
    private record Chunk(int page, List<BoundingBox> bounds, List<Vector3> centres, int since) { }

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
    private boolean active, usable = true, enabled = ENABLED, prewarmed = true, hasWalls;
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
    // The chunks with cache tops or walls (all, those near the pages this frame builds), the visible tops and walls,
    // the terrain pages whose walls draw cached, how many wall pages the frame needs and how many its table leaves out.
    private final List<BoundingBox> topBounds = new ArrayList<>(), nearBounds = new ArrayList<>();
    private final BoundingBox near = new BoundingBox();
    private final List<Chunk> visible = new ArrayList<>(), walls = new ArrayList<>();
    private final IntArray cachedPages = new IntArray(), cachedWalls = new IntArray();
    private int walled, wallsOutside;
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
        float touch = BoardRelief.metres(TOUCH);
        Vector3[] corners = { face.a(), face.b(), face.c() };
        for (BoardSurface.Face bank : banks) {
            if (Math.max(bank.a().z, Math.max(bank.b().z, bank.c().z)) > lowest + clearance
                  && overlap(corners, new Vector3[] { bank.a(), bank.b(), bank.c() }, touch)) {
                return false;
            }
        }
        return true;
    }

    /** How far, in metres, two triangles may overlap along a shared edge and still count as only touching. */
    private static final float TOUCH = .01f;

    /**
     * Whether two triangles overlap in XY by more than {@code touch} (BoardRelief.metres of {@link #TOUCH}) along a
     * shared edge (separating axes).
     */
    private static boolean overlap(Vector3[] a, Vector3[] b, float touch) {
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

    private static float normalZ(BoardSurface.Face face) { return normalZ(face.a(), face.b(), face.c()); }

    private static float normalZ(Vector3 a, Vector3 b, Vector3 c) {
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

    // ---- Walls: their charts ----

    /**
     * A natural wall's face the cache can chart: a cliff or bank (CLIFF shade at its corners, no panel),
     * {@link #steep}, in a hex that no biome cover reaches ({@link #top}).
     */
    static boolean wall(BoardSurface surface, BoardSurface.Face face, BoardScene scene) {
        if (!steep(face.a(), face.b(), face.c()) || !uncovered(scene, surface.tile)) { return false; }
        for (Vector3 p : new Vector3[] { face.a(), face.b(), face.c() }) {
            var shade = surface.relief.shade(p);
            if (shade == null || shade.kind() != BoardRelief.Kind.CLIFF || !Float.isNaN(shade.projection())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A theme boundary's triangle of hex {@code tile} the cache can chart, as {@link #wall}: its pieces keep their
     * corners' kinds (GpuTerrain.sculptVertex: .5 a natural cliff or bank, no panel) instead of their shades.
     */
    static boolean wall(BoardScene scene, BoardScene.Tile tile, MeshPartBuilder.VertexInfo a,
          MeshPartBuilder.VertexInfo b, MeshPartBuilder.VertexInfo c) {
        return a.color.b == .5f && b.color.b == .5f && c.color.b == .5f && steep(a.position, b.position, c.position)
              && uncovered(scene, tile);
    }

    /**
     * Whether a triangle is too steep for a top ({@link #SLOPE_Z}) but faces up, or down by no more than a few degrees
     * past vertical ({@link #LEAN}): an overhang's underside stays direct.
     */
    private static boolean steep(Vector3 a, Vector3 b, Vector3 c) {
        float z = normalZ(a, b, c);
        return z >= LEAN && z < SLOPE_Z;
    }

    /** The material a chunk's charted walls draw in: the same attributes, so the same programs, under its own id. */
    static Material wall(Material material) {
        Material copy = new Material(material);
        copy.id = material.id + WALL;
        return copy;
    }

    static boolean isWall(Material material) { return material.id.endsWith(WALL); }

    /** A wall's vertex layout: its material's own, then its chart position ({@link #CHART}). */
    static VertexAttributes chartLayout(VertexAttributes source) {
        VertexAttribute[] attributes = new VertexAttribute[source.size() + 1];
        for (int i = 0; i < source.size(); i++) { attributes[i] = source.get(i).copy(); }
        attributes[source.size()] = new VertexAttribute(VertexAttributes.Usage.Generic, 2, CHART, 6);
        return new VertexAttributes(attributes);
    }

    /** Whether a layout carries chart positions: a wall's, whose fill and cached-lit programs read them. */
    static boolean charted(VertexAttributes attributes) {
        // Chunk workers share layouts; libGDX's cached iterator is not safe across those workers.
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).alias.equals(CHART)) { return true; }
        }
        return false;
    }

    /** A layout without its chart positions: the one a wall's direct draws share with the other ground's programs. */
    static VertexAttributes uncharted(VertexAttributes attributes) {
        if (!charted(attributes)) { return attributes; }
        VertexAttribute[] plain = new VertexAttribute[attributes.size() - 1];
        for (int i = 0, j = 0; i < attributes.size(); i++) {
            if (!attributes.get(i).alias.equals(CHART)) { plain[j++] = attributes.get(i).copy(); }
        }
        return new VertexAttributes(plain);
    }

    /**
     * The sculpt vertex stage with a wall's chart (wallChartFlag): passed on to the cached-lit read, and in the fill
     * the wall drawn flat at it, keeping its depth and every other output.
     */
    static String vertex(String source) {
        source = GpuShaderSource.replaceOnce(source, "void main() {", """
              #ifdef wallChartFlag
              in vec2 a_wallChart;
              out vec2 v_wallChart;
              #endif
              void main() {""", "wall chart");
        String position = "gl_Position = u_projViewTrans * pos;";
        return GpuShaderSource.replaceOnce(source, position, position + """

              #ifdef wallChartFlag
              v_wallChart = a_wallChart;
              #ifdef materialFillFlag
              gl_Position = u_projViewTrans * vec4(a_wallChart, pos.z, 1.0);
              #endif
              #endif
              """, "wall chart");
    }

    /**
     * A chunk's wall charts. Its cell of the wall region (a part of the pages' plane past the board's north edge, twice
     * the chunk's extent a side) holds a slot for each edge of its hexes down which the ground drops: the walls a face
     * of the hex can face out over (it owns those down to its lower neighbours, BoardSurface.walls; a bank rising from
     * it faces back into it). A slot is {@link #SLOT} along the edge and as tall as the drop, a metre more each way;
     * a wall turning a corner from a deeper neighbour's drop may reach past it and stay direct. Slots are column-packed
     * in the hexes' build order from game data alone, so every detail of the chunk lays them out alike; those that do
     * not fit leave their walls direct. Slots start on, and stay apart by, a mip-2 texel of the
     * coarsest level walls are read at ({@link #ALIGN}), so no read reaches a neighbour's. A wall face takes the
     * slot of its hex's edge it faces most ({@link #place}).
     */
    static final class Charts {
        /** A slot's span along its edge, metres: the edge's 15 m and 2 m past each end. */
        private static final float SLOT = 19;
        /** A mip-2 texel of {@link #WALL_TOP}, metres. */
        private static final float ALIGN = CLOSE_PIXEL * (1 << (WALL_TOP + 2));

        /** A hex's edge (BoardGeometry.edgeDirection), the key of its slot. */
        private record Edge(Coords hex, int edge) { }

        /**
         * A slot: its corner in the region; its edge's middle and direction (counter-clockwise round the hex, so a wall
         * facing out of the hex keeps its winding in the chart); its base (z) and height (world units).
         */
        private record Slot(float x, float y, float midX, float midY, float alongX, float alongY, float base,
              float height) { }

        /** The edges' outward directions, edge k from corner k to corner k + 1 (BoardGeometry.corner). */
        private static final float[][] OUTWARD = new float[6][];

        static {
            for (int k = 0; k < 6; k++) {
                Vector3 a = BoardGeometry.modelCorner(k), b = BoardGeometry.modelCorner(k + 1);
                float x = b.x - a.x, y = b.y - a.y, length = (float) Math.hypot(x, y);
                OUTWARD[k] = new float[] { y / length, -x / length };
            }
        }

        private final Map<Edge, Slot> slots = new HashMap<>();
        // Each slot's edge, rectangle in the region, the box its faces stand in on the board, and that box's centre.
        private final List<Edge> edges = new ArrayList<>();
        private final List<BoundingBox> rectangles = new ArrayList<>(), boxes = new ArrayList<>();
        private final List<Vector3> centres = new ArrayList<>();
        // The slots holding a face: only their pages are needed.
        private final Set<Edge> used = new HashSet<>();
        // The chart triangles each slot holds so far: a face overlapping one there stays direct.
        private final Map<Slot, List<Vector3[]>> placed = new HashMap<>();
        /** The slots' extent in the wall region (world units); invalid without any. */
        final BoundingBox bounds = new BoundingBox().inf();

        /** The charts of the chunk of {@code scene} whose first hex is (x0, y0). */
        Charts(BoardScene scene, int x0, int y0) {
            float metre = BoardRelief.metres(1), align = ALIGN * metre;
            int size = GpuTerrain.CHUNK_SIZE;
            float cellWidth = aligned(2 * size * .75f * BoardGeometry.width(), align);
            float cellHeight = aligned(2 * size * BoardGeometry.height(), align);
            // The board lies south of y = 0; the region starts two pages of WALL_TOP north of it, clear of the pages
            // (and their gutters) the board's northern tops reach at the levels walls are read at.
            float left = x0 / size * cellWidth, bottom = 2 * pageMetres(WALL_TOP) * metre + y0 / size * cellHeight;
            float column = aligned((SLOT + ALIGN) * metre, align), x = left, y = bottom;
            Vector3 a = new Vector3(), b = new Vector3();
            // The hexes in their build order (GpuTerrain.ChunkBuild.collectUntil).
            for (int hx = x0; hx < Math.min(scene.width(), x0 + size); hx++) {
                for (int hy = y0; hy < Math.min(scene.height(), y0 + size); hy++) {
                    BoardScene.Tile tile = scene.tile(hx, hy);
                    for (int edge = 0; edge < 6; edge++) {
                        var across = scene.neighbor(tile, BoardGeometry.edgeDirection(edge));
                        if (across == null || BoardGeometry.groundZ(across) >= BoardGeometry.groundZ(tile)) {
                            continue;
                        }
                        float low = BoardGeometry.groundZ(across), high = Math.max(tile.groundLevel(),
                              across.groundLevel()) * BoardGeometry.level();
                        float height = high - low + 2 * metre, footprint = aligned(height + align, align);
                        if (y + footprint > bottom + cellHeight) {
                            x += column;
                            y = bottom;
                        }
                        if (x + column > left + cellWidth || footprint > cellHeight) { continue; }
                        BoardGeometry.corner(a, tile.coords(), 0, edge);
                        BoardGeometry.corner(b, tile.coords(), 0, edge + 1);
                        float length = (float) Math.hypot(b.x - a.x, b.y - a.y);
                        edges.add(new Edge(tile.coords(), edge));
                        slots.put(edges.getLast(), new Slot(x, y, (a.x + b.x) / 2, (a.y + b.y) / 2,
                              (b.x - a.x) / length, (b.y - a.y) / length, low - metre, height));
                        rectangles.add(new BoundingBox(new Vector3(x, y, 0),
                              new Vector3(x + column, y + footprint, 0)));
                        bounds.ext(rectangles.getLast());
                        // Its faces stand anywhere in the hex that faces the edge, leaning out by up to their height.
                        Vector3 centre = BoardGeometry.center(tile.coords(), 0);
                        float reachX = BoardGeometry.width() / 2 + height, reachY = BoardGeometry.height() / 2 + height;
                        boxes.add(new BoundingBox(new Vector3(centre.x - reachX, centre.y - reachY, low - metre),
                              new Vector3(centre.x + reachX, centre.y + reachY, low - metre + height)));
                        centres.add(boxes.getLast().getCenter(new Vector3()));
                        y += footprint;
                    }
                }
            }
        }

        private static float aligned(float value, float align) { return (float) Math.ceil(value / align) * align; }

        /**
         * Adds the rectangles in the region of the slots holding faces whose walls {@code frustum} meets, with their
         * centres.
         */
        void seen(Frustum frustum, List<BoundingBox> into, List<Vector3> at) {
            for (int i = 0; i < boxes.size(); i++) {
                if (used.contains(edges.get(i)) && frustum.boundsInFrustum(boxes.get(i))) {
                    into.add(rectangles.get(i));
                    at.add(centres.get(i));
                }
            }
        }

        /** Whether two chunks' charts lie alike: a hex's built walls keep their place in either. */
        boolean sameLayout(Charts other) { return other != null && slots.equals(other.slots); }

        /**
         * A wall triangle's place in its chart, its corners' region positions (x, y each; world units), or null: it
         * draws directly. Its slot is its hex's edge it faces most; there a corner's position is its place along the
         * edge (from the slot's start) and its height above the slot's base. A triangle reaching out of its slot,
         * folding back, or overlapping one the slot holds by more than a touch leaves the slot as it was.
         */
        float[] place(Coords hex, Vector3 a, Vector3 b, Vector3 c) {
            float nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y);
            float ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
            int edge = 0;
            for (int k = 1; k < 6; k++) {
                if (nx * OUTWARD[k][0] + ny * OUTWARD[k][1] > nx * OUTWARD[edge][0] + ny * OUTWARD[edge][1]) {
                    edge = k;
                }
            }
            Edge key = new Edge(hex, edge);
            Slot slot = slots.get(key);
            if (slot == null) { return null; }
            float width = SLOT * BoardRelief.metres(1);
            Vector3[] chart = new Vector3[3];
            Vector3[] corners = { a, b, c };
            for (int i = 0; i < 3; i++) {
                Vector3 p = corners[i];
                float u = (p.x - slot.midX()) * slot.alongX() + (p.y - slot.midY()) * slot.alongY() + width / 2;
                float v = p.z - slot.base();
                if (u < 0 || u > width || v < 0 || v > slot.height()) { return null; }
                chart[i] = new Vector3(u, v, 0);
            }
            if ((chart[1].x - chart[0].x) * (chart[2].y - chart[0].y)
                  - (chart[1].y - chart[0].y) * (chart[2].x - chart[0].x) <= 0) { return null; }
            List<Vector3[]> held = placed.computeIfAbsent(slot, ignored -> new ArrayList<>());
            // A slot holds many of its wall's triangles; each new one is tested against all of them.
            float touch = BoardRelief.metres(TOUCH);
            for (Vector3[] other : held) {
                // Apart in plan, two charts with area are apart along an edge of one of them too.
                if (apart(chart, other)) { continue; }
                if (overlap(chart, other, touch)) { return null; }
            }
            held.add(chart);
            used.add(key);
            return new float[] { slot.x() + chart[0].x, slot.y() + chart[0].y, slot.x() + chart[1].x,
                  slot.y() + chart[1].y, slot.x() + chart[2].x, slot.y() + chart[2].y };
        }

        /** Whether two triangles' bounds lie strictly apart in XY. */
        private static boolean apart(Vector3[] a, Vector3[] b) {
            return Math.max(a[0].x, Math.max(a[1].x, a[2].x)) < Math.min(b[0].x, Math.min(b[1].x, b[2].x))
                  || Math.max(b[0].x, Math.max(b[1].x, b[2].x)) < Math.min(a[0].x, Math.min(a[1].x, a[2].x))
                  || Math.max(a[0].y, Math.max(a[1].y, a[2].y)) < Math.min(b[0].y, Math.min(b[1].y, b[2].y))
                  || Math.max(b[0].y, Math.max(b[1].y, b[2].y)) < Math.min(a[0].y, Math.min(a[1].y, a[2].y));
        }

        /** {@code reused}: hexes whose meshes come from {@code source}'s build (sameLayout), slots and all. */
        void keep(Charts source, Set<Coords> reused) {
            if (source == null) { return; }
            for (Edge edge : source.used) {
                if (reused.contains(edge.hex())) { used.add(edge); }
            }
        }

        /** The chunk is built: the placed faces are no longer needed. */
        void built() { placed.clear(); }
    }

    /** A top or wall as this frame draws it on terrain page {@code page}: its cached twin once its pages are read. */
    Material material(int page, Material top) {
        boolean wall = isWall(top);
        if (!active || !wall && !isTop(top) || !(wall ? cachedWalls : cachedPages).contains(page)) { return top; }
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
     * Storage for the scene's pixels ({@link #capacity}) once the board has loaded ({@link #rebuilt}), its charted
     * walls' ({@code haveWalls}) too; none without cache tops or without a usable program. The first view's pages then
     * build behind the loading stage ({@link #prewarm}).
     */
    void allocate(boolean haveTops, boolean haveWalls) {
        hasWalls = haveWalls;
        prewarmed = true;
        resized = settled = 0;
        reading = -1;
        if (ENABLED && haveTops && usable && tileFramebuffer == 0) { createTile(); }
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
     * The pages the scene's pixels need ({@link #TEXELS_PER_PIXEL}) and their walls' ({@link #WALL_PAGES}), at most as
     * many as the board holds at the finest level and as the memory allows ({@link #memory}), and no more than an
     * array's layers.
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
        int share = hasWalls ? 1 + WALL_PAGES : 1;
        long wanted = (long) Math.ceil(pixels * TEXELS_PER_PIXEL * share / Math.pow(PAGE_TEXELS - 2 * GUTTER, 2));
        float page = pageMetres(0) * metre;
        long across = (long) Math.ceil(board.getWidth() / page) + 3, down = (long) Math.ceil(board.getHeight() / page) + 3;
        return (int) Math.max(1, Math.min(Math.min(wanted, across * down * share), limit));
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

    /** Whether this frame drew every visible top and wall cached from the level its pixels need (tests). */
    boolean complete() {
        int shown = shownPages(visible);
        return active && exact && shown > 0 && cachedPages.size == shown && cachedWalls.size == shownPages(walls);
    }

    /** The terrain pages with visible tops (or walls) this frame. */
    private static int shownPages(List<Chunk> chunks) {
        Set<Integer> terrainPages = new HashSet<>();
        for (Chunk chunk : chunks) { terrainPages.add(chunk.page()); }
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
                    + "root=%d:%d/%d under=%d admitted=%d shown=%d cached=%s wallPages=%d wallsOutside=%d wallsShown=%d "
                    + "wallsCached=%s fills=%d tiles=%d gpuEstimate=%.3fms endMs=%.2f endSteps=%d",
              active, level, reading, exact, pages.capacity(), pages.capacity() * pages.pageBytes() >> 20, root,
              rootBuilt, (rootX1 - rootX0 + 1) * (rootY1 - rootY0 + 1), under, admitted, shownPages(visible),
              cachedPages, walled, wallsOutside, shownPages(walls), cachedWalls, pages.fills(), pages.steps(),
              pages.estimate(Math.max(0, level)) / 1e6, endNanos / 1e6, endSteps);
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
        under = admitted = walled = wallsOutside = 0;
        cachedPages.clear();
        cachedWalls.clear();
        topBounds.clear();
        visible.clear();
        walls.clear();
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
        if (active && inView) { visible.add(new Chunk(page, List.of(bounds), null, since)); }
    }

    /**
     * A chunk's charted walls on terrain page {@code page}: their charts, whether their bounds on the board are in
     * view, and their install (#chunk). Only the pages of the slots the view sees are needed; a view coarser than
     * {@link #WALL_TOP} draws the walls directly.
     */
    void walls(int page, Charts charts, boolean inView, int since) {
        if (!filling || !charts.bounds.isValid()) { return; }
        topBounds.add(charts.bounds);
        if (!active || level > WALL_TOP || !inView) { return; }
        Chunk chunk = new Chunk(page, new ArrayList<>(), new ArrayList<>(), since);
        charts.seen(view.frustum, chunk.bounds(), chunk.centres());
        if (!chunk.bounds().isEmpty()) { walls.add(chunk); }
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
        if (FILL_AT_ONCE) { budget = cpuNanos = AT_ONCE_NANOS; }
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
        // The walls' pages, each as near as its nearest seen slot on the board; the coarser level's that a zoom in
        // still reads stay where built.
        Map<Long, Float> wallPages = wallPages(level, focus);
        Map<Long, Float> coarseWalls = reading != level && reading <= WALL_TOP ? wallPages(reading, focus) : Map.of();
        walled = wallPages.size();
        int coarseHeld = 0;
        for (long page : coarseWalls.keySet()) {
            if (pages.layer(reading, unpackX(page), unpackY(page)) < 0) { continue; }
            pages.need(reading, unpackX(page), unpackY(page), true);
            coarseHeld++;
        }
        // The tops' pages nearest the view's centre first, as many as the storage holds, then the walls' in the room
        // left: a terrain page whose tops all fit keeps them cached whatever its walls need.
        List<Long> order = new ArrayList<>(wanted);
        order.sort((a, b) -> Float.compare(distance(a, size, focus), distance(b, size, focus)));
        List<Long> wallOrder = new ArrayList<>(wallPages.keySet());
        wallOrder.sort((a, b) -> Float.compare(wallPages.get(a), wallPages.get(b)));
        order.addAll(wallOrder);
        Set<Long> held = new HashSet<>(order.subList(0, Math.min(order.size(), Math.max(0,
              pages.capacity() - rootPages - coarse.size() - coarseHeld - SPARES))));
        admitted = held.size();
        int[] window = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (long page : order) {
            if (!held.contains(page)) { break; }
            pages.need(level, unpackX(page), unpackY(page), true);
            if (wanted.contains(page)) { extend(window, unpackX(page), unpackY(page)); }
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
            if (!wanted.contains(page)) { continue; }
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
        // The finer level once every page of the tops' the storage holds is built; walls still building there read
        // a coarser one of theirs, or draw directly.
        if (reading != level) {
            boolean built = true;
            for (long page : held) {
                built &= !wanted.contains(page) || pages.layer(level, unpackX(page), unpackY(page)) >= 0;
            }
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
        if (!pages.upload(0, reading, root, table[0], table[1], table[2] - table[0] + 1, table[3] - table[1] + 1,
              table[0] * metres, table[1] * metres, metres, standIns)) {
            active = false;
            return true;
        }
        pages.bindTable(0, BINDING);
        // A terrain page draws cached once every page under its visible tops can be read at that level, or (held and
        // still building) stood in for by a coarser one; the frame is exact once each is its own and current.
        exact = reading == level;
        Set<Integer> direct = new HashSet<>();
        Set<Long> chunkPages = new HashSet<>();
        for (Chunk chunk : visible) {
            chunkPages.clear();
            under(chunk, reading, chunkPages);
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
        // The walls' table, over the same level up to WALL_TOP, standing in as the tops' does: a terrain page draws its
        // walls cached once every page under their charts can be read. Its window takes the nearest pages first, as
        // many as the table spans (a wide view at a fine level); walls past it draw directly.
        Map<Long, Float> wallsRead = reading == level ? wallPages : coarseWalls;
        List<Long> nearest = new ArrayList<>(wallsRead.keySet());
        nearest.sort((a, b) -> Float.compare(wallsRead.get(a), wallsRead.get(b)));
        int[] wallTable = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (long page : nearest) {
            int[] grown = wallTable.clone();
            extend(grown, unpackX(page), unpackY(page));
            if ((long) (grown[2] - grown[0] + 1) * (grown[3] - grown[1] + 1) > GpuPageCache.TABLE_ENTRIES) {
                wallsOutside++;
            } else {
                wallTable = grown;
            }
        }
        if (walls.isEmpty() || wallsRead.isEmpty() || !pages.upload(WALL_TABLE, reading, WALL_TOP, wallTable[0],
              wallTable[1], wallTable[2] - wallTable[0] + 1, wallTable[3] - wallTable[1] + 1, wallTable[0] * metres,
              wallTable[1] * metres, metres, standIns)) { return done; }
        pages.bindTable(WALL_TABLE, WALL_BINDING);
        direct.clear();
        for (Chunk chunk : walls) {
            chunkPages.clear();
            under(chunk, reading, chunkPages);
            for (long page : chunkPages) {
                int x = unpackX(page), y = unpackY(page), entry = pages.entry(reading, WALL_TOP, x, y, standIns);
                boolean inside = x >= wallTable[0] && x <= wallTable[2] && y >= wallTable[1] && y <= wallTable[3];
                if (!inside || entry < 0) { direct.add(chunk.page()); }
                exact &= inside && entry >= 0 && entry < GpuPageCache.FALLBACK && pages.current(reading, x, y);
            }
        }
        for (Chunk chunk : walls) {
            if (!direct.contains(chunk.page()) && !cachedWalls.contains(chunk.page())) {
                cachedWalls.add(chunk.page());
            }
        }
        return done;
    }

    /**
     * The pages of {@code at} under the seen wall slots, each with the squared distance of its nearest slot on the
     * board to {@code focus}: what orders them.
     */
    private Map<Long, Float> wallPages(int at, Vector3 focus) {
        Map<Long, Float> nearness = new HashMap<>();
        Set<Long> below = new HashSet<>();
        for (Chunk chunk : walls) {
            for (int i = 0; i < chunk.bounds().size(); i++) {
                Vector3 centre = chunk.centres().get(i);
                float distance = Vector3.dst2(centre.x, centre.y, 0, focus.x, focus.y, 0);
                below.clear();
                under(chunk.bounds().get(i), at, below, false);
                for (long page : below) { nearness.merge(page, distance, Math::min); }
            }
        }
        return nearness;
    }

    /**
     * The pages of {@code at} under visible tops and walls that follow their detail, each with the latest install among
     * them.
     */
    private Map<Long, Integer> since(int at) {
        Map<Long, Integer> since = new HashMap<>();
        Set<Long> below = new HashSet<>();
        for (List<Chunk> chunks : List.of(visible, walls)) {
            for (Chunk chunk : chunks) {
                if (chunk.since() == 0) { continue; }
                below.clear();
                under(chunk, at, below);
                for (long page : below) { since.merge(page, chunk.since(), Math::max); }
            }
        }
        return since;
    }

    /** The pages of {@code at} under the visible tops that the view's frustum meets. */
    private Set<Long> under(int at) {
        Set<Long> pagesUnder = new HashSet<>();
        for (Chunk chunk : visible) { under(chunk, at, pagesUnder); }
        return pagesUnder;
    }

    /** Adds the pages of {@code at} under a chunk's tops that the view's frustum meets, or under its seen slots. */
    private void under(Chunk chunk, int at, Set<Long> into) {
        for (BoundingBox bounds : chunk.bounds()) { under(bounds, at, into, chunk.centres() == null); }
    }

    /**
     * Adds the pages of {@code at} under {@code bounds} (world units): with {@code frustum}, only those the view's
     * frustum meets (tops; walls' charts lie in the wall region, where their slots were seen).
     */
    private void under(BoundingBox bounds, int at, Set<Long> into, boolean frustum) {
        float size = pageMetres(at) * metre;
        int x0 = (int) Math.floor(bounds.min.x / size), x1 = (int) Math.floor(bounds.max.x / size);
        int y0 = (int) Math.floor(bounds.min.y / size), y1 = (int) Math.floor(bounds.max.y / size);
        BoundingBox box = new BoundingBox();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                box.min.set(x * size, y * size, bounds.min.z);
                box.max.set((x + 1) * size, (y + 1) * size, bounds.max.z);
                box.update();
                if (!frustum || view.frustum.boundsInFrustum(box)) { into.add(pack(x, y)); }
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
        prepare();
    }

    /**
     * Links the reduction and the sharpening, as the owning terrain is made: Intel holds a link on the render thread for
     * the whole of the shader worker's current one, and the first fill comes while the terrain's programs link there (a
     * 17 s frame of a cold load on Iris Xe).
     */
    void prepare() {
        if (!ENABLED || means != null) { return; }
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
     * A cached-lit program's page arrays and table: a wall's ({@code wall}) at the walls' binding. A program the
     * context cannot serve (more samplers than units, a table of another size) leaves the cache unused for this
     * terrain, and frees its storage.
     */
    void registerCached(DefaultShader shader, boolean wall) {
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
        GL31.glUniformBlockBinding(program, block, wall ? WALL_BINDING : BINDING);
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
