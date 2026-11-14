/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * The ground under the view in one map of fixed size, drawn by the GPU from the installed terrain seen straight down.
 * Its depth is the ground's height and its colour how much grows there: the meadow in red, earth tundra's sward in
 * green, the ground its tussocks stand on in blue and a field's crops in alpha. The ground says so itself, by what it
 * is drawn as (ground-map.frag); a road, paint, water or an object's footprint, drawn over it, leaves nothing
 * (ground-map-cover.frag). Ground cover's blades place themselves on the map (terrain-grass.glsl): nothing is planted,
 * stored or kept per hex, and the map follows whatever detail the terrain shows. Its texels are world-aligned, a power
 * of two of {@link #FINEST} metres, so a blade reads the same texels wherever the view moves at one size. The whole map
 * is drawn again only when the view leaves it, when it grew too coarse for the view, or when the board's tiles
 * changed; an installed chunk redraws its own rectangle alone ({@link #changed}). GL ownership stays here.
 */
final class GpuGroundMap implements Disposable {
    /** Texels a side: four bytes of cover and two of depth each, 6 MB in all, whatever the board's size. */
    static final int SIZE = 1024;
    /** The finest texel, in metres. */
    private static final float FINEST = .125f;
    /** The map is at least this many times as wide as the view it is drawn for, and at most {@link #WIDEST} times. */
    private static final float MARGIN = 1.5f, WIDEST = 3.2f;
    /** Metres a road or paint, and water, are raised clear of their ground; water's leaves the wet toe bare. */
    private static final float OVERLAY_LIFT = .25f, WATER_LIFT = .15f;
    /** Whether a render waits for the GPU to finish, so that {@link #stats} times the whole of it (measurements only). */
    private static final boolean TIMED = Boolean.getBoolean("megamek.gpu.groundMapTiming");

    /** The installed terrain's draws for the map's rectangle, and the board's tables (GpuTerrain#gatherGroundMap). */
    interface Terrain {
        void gather(float minX, float minY, float maxX, float maxY, Draws draws);
    }

    /** What one render draws, layer by layer; the lists borrow the chunks' renderables. */
    static final class Draws {
        private final Array<Renderable> ground = new Array<>(), roads = new Array<>(), paint = new Array<>(),
              water = new Array<>(), objects = new Array<>();
        private float bottom, top;
        /** Whether every chunk is drawn, or only those that draw grass: a board without tundra or fields grows nothing else. */
        private boolean every;
        private GpuLavaLighting tables;
        private int biomeWidth, biomeHeight, boardWidth, boardHeight;

        private void clear() {
            ground.clear(); roads.clear(); paint.clear(); water.clear(); objects.clear();
            bottom = Float.POSITIVE_INFINITY; top = Float.NEGATIVE_INFINITY;
        }

        /** The lights' texture, which holds the board's hex table, the biome stencil's board size and the board's. */
        void tables(GpuLavaLighting tables, int biomeWidth, int biomeHeight, int boardWidth, int boardHeight) {
            this.tables = tables;
            this.biomeWidth = biomeWidth; this.biomeHeight = biomeHeight;
            this.boardWidth = boardWidth; this.boardHeight = boardHeight;
        }

        /**
         * A chunk reaching the map: its solid layer (the sculpted ground; whatever else stands in it is an object), its
         * roads, paint and water, and its objects. On a board of meadows alone a chunk that draws no grass adds nothing:
         * no blade can stand there.
         */
        @SafeVarargs
        final void chunk(BoundingBox bounds, Array<Renderable> terrain, Array<Renderable> tops, Array<Renderable> roads,
              Array<Renderable> paint, Array<Renderable> water, Array<Renderable>... objects) {
            if (terrain == null || !every && !grows(terrain) && !grows(tops)) { return; }
            bottom = Math.min(bottom, bounds.min.z);
            top = Math.max(top, bounds.max.z);
            for (Array<Renderable> solid : java.util.List.of(terrain, tops)) {
                for (Renderable part : solid) {
                    (part.material.has(GpuTerrain.Sculpt.TYPE) ? ground : this.objects).add(part);
                }
            }
            this.roads.addAll(roads);
            if (paint != null) { this.paint.addAll(paint); }
            if (water != null) { this.water.addAll(water); }
            for (Array<Renderable> parts : objects) { this.objects.addAll(parts); }
        }

        private static boolean grows(Array<Renderable> parts) {
            for (Renderable part : parts) {
                float[] grass = slots(part.material, GRASS);
                if (grass[0] + grass[1] + grass[2] + grass[3] > 0) { return true; }
            }
            return false;
        }
    }

    private final Draws draws = new Draws();
    /** Made with the first map: a terrain that never shows a blade holds none. */
    private Texture cover, height;
    private int framebuffer;
    private ShaderProgram ground, covers;
    private final Matrix4 projection = new Matrix4();
    /** The map's lower left corner and texel, in world units; the height of its depth 0 and the range down to depth 1. */
    private float originX, originY, texel, top, range;
    /** Whether the whole map is to be drawn again, and the rectangle (world units) of what was installed since it was. */
    private boolean stale = true;
    private float changedMinX = Float.POSITIVE_INFINITY, changedMinY = Float.POSITIVE_INFINITY;
    private float changedMaxX = Float.NEGATIVE_INFINITY, changedMaxY = Float.NEGATIVE_INFINITY;
    private long renders, partials, renderNanos, partialNanos, lastNanos, slowestNanos;
    /** The last render's nanoseconds: gather, ground, roads, paint, water, objects. */
    private final long[] stage = new long[6];
    private String lastParts = "", slowest = "";

    GpuGroundMap() {
        ground = GpuShaderManager.program(() -> program(true), next -> ground = next);
        covers = GpuShaderManager.program(() -> program(false), next -> covers = next);
    }

    private void allocate() {
        if (cover != null) { return; }
        cover = new Texture(new GLOnlyTextureData(SIZE, SIZE, 0, GL30.GL_RGBA8, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE));
        height = new Texture(new GLOnlyTextureData(SIZE, SIZE, 0, GL20.GL_DEPTH_COMPONENT16, GL20.GL_DEPTH_COMPONENT,
              GL20.GL_UNSIGNED_SHORT));
        IntBuffer bound = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, bound);
        for (Texture texture : new Texture[] { cover, height }) {
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        framebuffer = Gdx.gl.glGenFramebuffer();
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_COLOR_ATTACHMENT0, GL20.GL_TEXTURE_2D,
              cover.getTextureObjectHandle(), 0);
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
              height.getTextureObjectHandle(), 0);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, bound.get(0));
    }

    private static ShaderProgram program(boolean ground) {
        String fragment = ground ? GpuShaderSource.replaceOnce(GpuShaderSource.read("ground-map.frag"),
              "// ground-map-functions", GpuShaderSource.read("terrain-hexes.glsl")
                    + GpuShaderSource.read("terrain-biome-mask.glsl"), "ground map") : GpuShaderSource.read("ground-map-cover.frag");
        return GpuGlsl.compile(ground ? "GPU ground map" : "GPU ground map cover", ground ? "#define groundFlag\n" : "",
              GpuShaderSource.read("ground-map.vert"), fragment);
    }

    /** The cover's shares, then the ground's height as depth: what the blades' vertex stage samples. */
    GLTexture cover() {
        allocate();
        return cover;
    }

    GLTexture height() {
        allocate();
        return height;
    }

    /** The map's lower left corner in the world, one over its width, and its texel, in world units. */
    float[] frame() { return new float[] { originX, originY, 1 / (SIZE * texel), texel }; }

    /** The height of the map's depth 0, and the range down to its depth 1. */
    float[] levels() { return new float[] { top, range }; }

    /** Its renders so far and the milliseconds they took, finished on the GPU, and its texel in metres. */
    String stats() {
        return String.format(java.util.Locale.ROOT, "whole renders %d, %.2f ms each; partial %d, %.2f ms each (last %.2f ms, "
              + "%s; slowest %s), texel %.3f m, %d B", renders, renders == 0 ? 0 : renderNanos / 1e6 / renders, partials,
              partials == 0 ? 0 : partialNanos / 1e6 / partials, lastNanos / 1e6, lastParts, slowest,
              texel / BoardRelief.metres(1), SIZE * SIZE * 6);
    }

    /** How often it was drawn again, whole or in part. */
    long redraws() { return renders + partials; }

    /** The next {@link #update} draws the whole map again: another board, other tiles, or other world units. */
    void invalidate() { stale = true; }

    /** A chunk or its paint was installed within these bounds: the next {@link #update} draws that rectangle again. */
    void changed(BoundingBox bounds) {
        changedMinX = Math.min(changedMinX, bounds.min.x); changedMaxX = Math.max(changedMaxX, bounds.max.x);
        changedMinY = Math.min(changedMinY, bounds.min.y); changedMaxY = Math.max(changedMaxY, bounds.max.y);
    }

    /**
     * Makes the map hold the rectangle of ground (world units) the view's cover can stand on, drawing every chunk or,
     * on a board of meadows alone, those with grass. Returns whether it drew any of the map again.
     */
    boolean update(float minX, float minY, float maxX, float maxY, boolean every, Terrain terrain) {
        float extent = Math.max(maxX - minX, maxY - minY), side = SIZE * texel, finest = BoardRelief.metres(FINEST);
        boolean whole = stale || every != draws.every || minX < originX || maxX > originX + side || minY < originY
              || maxY > originY + side || side > extent * WIDEST && texel > finest;
        // The texels a changed rectangle reaches, one more all round: a blade reads between texels.
        int left = 0, bottom = 0, right = SIZE, upper = SIZE;
        if (whole) {
            texel = finest;
            while (SIZE * texel < extent * MARGIN) { texel *= 2; }
            side = SIZE * texel;
            // Texel centres stay where they are in the world when the map moves.
            originX = (float) Math.floor(((minX + maxX) / 2 - side / 2) / texel) * texel;
            originY = (float) Math.floor(((minY + maxY) / 2 - side / 2) / texel) * texel;
        } else {
            left = Math.max(0, (int) Math.floor((changedMinX - originX) / texel) - 1);
            bottom = Math.max(0, (int) Math.floor((changedMinY - originY) / texel) - 1);
            right = Math.min(SIZE, (int) Math.ceil((changedMaxX - originX) / texel) + 1);
            upper = Math.min(SIZE, (int) Math.ceil((changedMaxY - originY) / texel) + 1);
        }
        changedMinX = changedMinY = Float.POSITIVE_INFINITY;
        changedMaxX = changedMaxY = Float.NEGATIVE_INFINITY;
        if (right <= left || upper <= bottom) { return false; }
        stale = false;
        draws.every = every;
        allocate();
        // Timed alone: the GPU has finished what came before, then the map.
        if (TIMED) { Gdx.gl.glFinish(); }
        long start = System.nanoTime();
        // A rectangle whose ground left the map's heights takes the whole map with it.
        if (!whole && !render(terrain, left, bottom, right, upper, false)) { whole = true; }
        if (whole) { render(terrain, 0, 0, SIZE, SIZE, true); }
        if (TIMED) { Gdx.gl.glFinish(); }
        lastNanos = System.nanoTime() - start;
        if (whole) { renders++; renderNanos += lastNanos; } else { partials++; partialNanos += lastNanos; }
        if (lastNanos > slowestNanos) {
            slowestNanos = lastNanos;
            slowest = String.format(java.util.Locale.ROOT, "%.2f ms %s: gather %.2f, ground %.2f, roads %.2f, paint %.2f, "
                  + "water %.2f, objects %.2f; %s", lastNanos / 1e6, whole ? "whole" : "partial", stage[0] / 1e6,
                  stage[1] / 1e6, stage[2] / 1e6, stage[3] / 1e6, stage[4] / 1e6, stage[5] / 1e6, lastParts);
        }
        return true;
    }

    /**
     * Draws the map's texels of a rectangle again, or all of them, which also sets the heights its depth spans. False
     * when a rectangle's ground lies outside those heights: nothing is drawn then.
     */
    private boolean render(Terrain terrain, int left, int bottom, int right, int upper, boolean whole) {
        draws.clear();
        float side = SIZE * texel, metre = BoardRelief.metres(1);
        long began = System.nanoTime();
        terrain.gather(originX + left * texel, originY + bottom * texel, originX + right * texel, originY + upper * texel, draws);
        java.util.Arrays.fill(stage, 0);
        stage[0] = System.nanoTime() - began;
        boolean empty = draws.ground.isEmpty();
        lastParts = "parts: ground " + draws.ground.size + ", roads " + draws.roads.size + ", paint " + draws.paint.size
              + ", water " + draws.water.size + ", objects " + draws.objects.size;
        if (whole) {
            top = empty ? 0 : draws.top + metre;
            range = empty ? 1 : top - draws.bottom + metre;
        } else if (!empty && (draws.top > top || draws.bottom < top - range)) {
            draws.clear();
            return false;
        }
        // Straight down: x and y across the map, depth 0 at the top and 1 at the bottom.
        projection.setToOrtho(originX, originX + side, originY, originY + side, -top, range - top);
        boolean srgb = Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        boolean scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        IntBuffer integers = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL30.GL_VERTEX_ARRAY_BINDING, integers);
        int vertexArray = integers.get(0);
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, integers);
        int bound = integers.get(0);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, integers);
        int[] viewport = { integers.get(0), integers.get(1), integers.get(2), integers.get(3) };
        Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, integers);
        int[] box = { integers.get(0), integers.get(1), integers.get(2), integers.get(3) };
        FloatBuffer clear = BufferUtils.newFloatBuffer(16);
        Gdx.gl.glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, clear);
        try {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
            Gdx.gl.glViewport(0, 0, SIZE, SIZE);
            Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(left, bottom, right - left, upper - bottom);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glColorMask(true, true, true, true);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            if (empty) { return true; }
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            stageBegan = System.nanoTime();
            drawGround();
            timed(1);
            // What lies on the ground takes its alpha's share of the growth away, and leaves the height.
            Gdx.gl.glDepthMask(false);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_ZERO, GL20.GL_ONE_MINUS_SRC_ALPHA);
            drawCovers(metre);
            return true;
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
            if (srgb) { Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
            if (!scissor) { Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); }
            Gdx.gl.glScissor(box[0], box[1], box[2], box[3]);
            Gdx.gl30.glBindVertexArray(vertexArray);
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, bound);
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            draws.clear();
        }
    }

    private void drawGround() {
        ground.bind();
        ground.setUniformMatrix(uniform(ground, "u_projViewTrans"), projection);
        ground.setUniformf(uniform(ground, "u_levelHeight"), BoardGeometry.level());
        ground.setUniformf(uniform(ground, "u_metre"), BoardRelief.metres(1));
        ground.setUniformf(uniform(ground, "u_biomeBoard"), draws.biomeWidth, draws.biomeHeight);
        ground.setUniformi(uniform(ground, "u_board"), draws.boardWidth, draws.boardHeight);
        // The lights' texture uploads on unit 0 and leaves itself bound there (GpuLavaLighting#texture).
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        draws.tables.texture().bind();
        ground.setUniformi(uniform(ground, "u_lavaLights"), 0);
        ground.setUniformi(uniform(ground, "u_hexTable"), draws.tables.hexRow(), draws.tables.hexWidth());
        // A mesh without blend weights is all its own family: the constant its missing attribute reads.
        int weights = ground.getAttributeLocation("a_coverWeights");
        if (weights >= 0) { Gdx.gl.glVertexAttrib4f(weights, 1, 0, 0, 0); }
        int[] families = { uniform(ground, "u_grass"), uniform(ground, "u_earth"), uniform(ground, "u_snow") };
        int world = uniform(ground, "u_worldTrans");
        try {
            for (Renderable part : draws.ground) {
                for (int kind = 0; kind < families.length; kind++) {
                    float[] slots = slots(part.material, kind == 0 ? GRASS : kind == 1 ? EARTH : SNOW);
                    ground.setUniformf(families[kind], slots[0], slots[1], slots[2], slots[3]);
                }
                draw(ground, world, part);
            }
        } finally {
            release(ground);
            if (weights >= 0) { Gdx.gl.glVertexAttrib4f(weights, 0, 0, 0, 1); }
        }
    }

    private void drawCovers(float metre) {
        covers.bind();
        covers.setUniformMatrix(uniform(covers, "u_projViewTrans"), projection);
        covers.setUniformi(uniform(covers, "u_maskTexture"), 0);
        int mask = uniform(covers, "u_mask"), maskUv = uniform(covers, "u_maskUV"), opacity = uniform(covers, "u_opacity");
        int lift = uniform(covers, "u_lift"), world = uniform(covers, "u_worldTrans");
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        covers.setUniformf(lift, OVERLAY_LIFT * metre);
        covers.setUniformf(opacity, 1f);
        for (Renderable part : draws.roads) {
            // A road is its mask's alpha; a coat without one covers whole.
            var region = part.material.get(TextureAttribute.class, GpuRoads.Mask.TYPE);
            if (region != null) { region.textureDescription.texture.bind(); }
            covers.setUniformi(mask, region == null ? 0 : 2);
            draw(covers, world, part);
        }
        timed(2);
        covers.setUniformi(mask, 1);
        for (Renderable part : draws.paint) {
            var image = part.material.get(TextureAttribute.class, TextureAttribute.Diffuse);
            if (image == null) { continue; }
            image.textureDescription.texture.bind();
            covers.setUniformf(maskUv, image.offsetU, image.offsetV, image.scaleU, image.scaleV);
            var blend = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
            covers.setUniformf(opacity, blend == null ? 1 : blend.opacity);
            draw(covers, world, part);
        }
        timed(3);
        covers.setUniformi(mask, 0);
        covers.setUniformf(opacity, 1f);
        covers.setUniformf(lift, WATER_LIFT * metre);
        for (Renderable part : draws.water) { draw(covers, world, part); }
        timed(4);
        // An object clears the ground it stands on, or hangs within a blade's height of: a raised deck, a crown or a
        // wire leaves its ground alone. A kit's many pieces share few meshes: sorted, each mesh binds once.
        covers.setUniformf(lift, -GpuGroundCover.MAX_HEIGHT_FRACTION * BoardGeometry.width());
        Gdx.gl.glDepthFunc(GL20.GL_GEQUAL);
        draws.objects.sort((a, b) -> Integer.compare(System.identityHashCode(a.meshPart.mesh),
              System.identityHashCode(b.meshPart.mesh)));
        for (Renderable part : draws.objects) { draw(covers, world, part); }
        release(covers);
        timed(5);
    }

    private long stageBegan;

    /** With {@link #TIMED}, the nanoseconds of a stage that has just drawn, finished on the GPU. */
    private void timed(int index) {
        if (!TIMED) { return; }
        release(covers);
        Gdx.gl.glFinish();
        long now = System.nanoTime();
        stage[index] = now - stageBegan;
        stageBegan = now;
    }

    /** The mesh bound for the program drawing; consecutive parts of one mesh share its bind. */
    private Mesh bound;

    /** A static part's triangles with one of the map's programs; instanced and skinned parts have none here. */
    private void draw(ShaderProgram program, int world, Renderable part) {
        Mesh mesh = part.meshPart.mesh;
        if (part.meshPart.size == 0 || part.meshPart.primitiveType != GL20.GL_TRIANGLES || part.bones != null
              || mesh.isInstanced()) { return; }
        if (mesh != bound) {
            release(program);
            mesh.bind(program);
            bound = mesh;
        }
        program.setUniformMatrix(world, part.worldTransform);
        mesh.render(program, GL20.GL_TRIANGLES, part.meshPart.offset, part.meshPart.size, false);
    }

    private void release(ShaderProgram program) {
        if (bound != null) { bound.unbind(program); }
        bound = null;
    }

    private static int uniform(ShaderProgram program, String name) { return program.fetchUniformLocation(name, false); }

    /**
     * Surface families as bits by ordinal: grass; the grounds earth tundra's sward grows on (GpuGroundCover's old
     * earthShare, less pavement); and snow, whose remnants it leaves.
     */
    private static final int GRASS = 1 << BoardScene.Surface.GRASS.ordinal(), SNOW = 1 << BoardScene.Surface.SNOW.ordinal();
    private static final int EARTH = GRASS | SNOW | 1 << BoardScene.Surface.DIRT.ordinal() | 1 << BoardScene.Surface.SAND.ordinal()
          | 1 << BoardScene.Surface.ROCK.ordinal() | 1 << BoardScene.Surface.DESERT.ordinal()
          | 1 << BoardScene.Surface.TROPICAL.ordinal();

    /**
     * Which slots of a ground material's palette hold one of {@code families}: its own family first (a blend's base),
     * then a blended triangle's three others (GpuSurfaceBlend). All zero for ground of none of them.
     */
    private static float[] slots(Material material, int families) {
        float[] slots = new float[4];
        var blend = material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
        var sculpt = material.get(GpuTerrain.Sculpt.class, GpuTerrain.Sculpt.TYPE);
        for (int slot = 0; slot < (blend != null ? 4 : sculpt != null ? 1 : 0); slot++) {
            int family = Math.round(blend != null ? blend.families[slot] : sculpt.value);
            slots[slot] = family >= 0 && family < 31 && (families >> family & 1) != 0 ? 1 : 0;
        }
        return slots;
    }

    @Override
    public void dispose() {
        if (cover != null) {
            Gdx.gl.glDeleteFramebuffer(framebuffer);
            cover.dispose();
            height.dispose();
        }
        GpuShaderManager.dispose(ground);
        GpuShaderManager.dispose(covers);
    }
}
