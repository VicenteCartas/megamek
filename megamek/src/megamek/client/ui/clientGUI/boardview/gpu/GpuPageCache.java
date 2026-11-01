/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

/**
 * World-locked square pages of drawn content in a mip pyramid, each page a layer of a set of texture arrays with its
 * own mip levels: content that costs much to draw and changes rarely is drawn into a page once and read from it until
 * it changes. A page at pyramid level v covers 2^v times the ground of a level-0 page in the owner's plane, and builds in
 * as many steps as its owner needs (one a budget unit). The owner names the pages it needs each frame and builds a page
 * when {@link #fill} asks; this class keeps the layers (the pages needed this frame pinned, the others out least
 * recently needed first), readiness, the shaders' page tables (each a std140 uniform block over a window of one
 * level, where a page not ready reads none: its owner draws that content directly) and the fill budget.
 * <p>
 * A page is ready once its last step has been issued for the current revision, in the layer it holds now; a layer handed
 * to another page never answers for the one before. A page marked {@link #invalidate stale} stays ready, serving its
 * last content whole, while it rebuilds in a spare layer that replaces it on the last step.
 */
final class GpuPageCache implements Disposable {
    /**
     * Builds step {@code step} of a page in its layer: {@link #DONE} after the last, {@link #MORE} before it, or
     * {@link #AGAIN} when the step could not draw everything (a program still links): the page then starts over.
     */
    interface Filler {
        int fill(int level, int x, int y, int layer, int step);
    }

    static final int MORE = 0, DONE = 1, AGAIN = 2;

    /** Whether a ready page (x, y) of a table's window may serve: its owner may know it lacks content it must hold. */
    interface Serves {
        boolean serves(int x, int y);
    }

    /** Entries of the page table: a layer, or -1; four packed into each of 1020 ivec4 after a 32-byte header (16 KB). */
    static final int TABLE_ENTRIES = 4080;
    static final int TABLE_HEADER_BYTES = 32;
    static final int TABLE_BYTES = TABLE_HEADER_BYTES + TABLE_ENTRIES * 4;
    /** Page tables: one for each window the owner's shaders read (GpuMaterialCache: the tops' and the walls'). */
    static final int TABLES = 2;
    /** The anisotropy a read may take: its footprint stays within the gutter at the levels it reads. */
    static final float MAX_ANISOTROPY = 4;
    /** The first fill's cost guess in nanoseconds of GPU time; measured fills replace it. */
    private static final long FIRST_ESTIMATE = 1_000_000;

    private final int texels, gutter, levels;
    private final int[] formats;
    private final GLTexture[] arrays;
    private final Map<Long, Page> pages = new HashMap<>();
    private final List<Page> needed = new ArrayList<>();
    private Page[] layers = new Page[0];
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final ByteBuffer table = BufferUtils.newByteBuffer(TABLE_BYTES);
    private final IntBuffer binding = BufferUtils.newIntBuffer(16);
    private final int[] buffers = new int[TABLES];
    private long frame, revision;
    // The fill budget: credit in nanoseconds of GPU time; per pyramid level, the cost one step is expected to take and
    // the last steps' costs, of which the estimate is the second largest (one stall does not throttle the fills that
    // follow); the timer queries (GL_TIME_ELAPSED, one a step) of earlier fills not yet read, with their levels.
    private long credit;
    private final long[] estimates;
    private final long[][] costs;
    private final int[] costCounts;
    private final ArrayDeque<int[]> queries = new ArrayDeque<>();
    private long fills, steps;

    private static final class Page {
        final int level, x, y;
        int layer = -1, spare = -1, step;
        long revision = -1, needed;
        boolean pinned, stale, urgent;

        Page(int level, int x, int y) {
            this.level = level;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Pages of {@code texels} a side, {@code gutter} of them along each edge drawn beyond the page so filtering reads
     * real neighbours, {@code levels} mip levels of each page, pyramid levels 0 to {@code pyramid} - 1, one array of each
     * internal format.
     */
    GpuPageCache(int texels, int gutter, int levels, int pyramid, int... formats) {
        this.texels = texels;
        this.gutter = gutter;
        this.levels = levels;
        estimates = new long[pyramid];
        costs = new long[pyramid][8];
        costCounts = new int[pyramid];
        this.formats = formats.clone();
        arrays = new GLTexture[formats.length];
    }

    int capacity() { return layers.length; }

    /** The array of one format, for a sampler. */
    GLTexture array(int index) { return arrays[index]; }

    /** Bytes one page takes with its mip levels: every format takes four bytes a texel. */
    long pageBytes() {
        long bytes = 0;
        for (int level = 0; level < levels; level++) { bytes += (long) (texels >> level) * (texels >> level); }
        return bytes * 4 * formats.length;
    }

    /** Pages built since the cache was made, and their steps (diagnostics). */
    long fills() { return fills; }

    long steps() { return steps; }

    /** The GPU nanoseconds a step of {@code level} is expected to take (diagnostics). */
    long estimate(int level) { return estimates[level] == 0 ? FIRST_ESTIMATE : estimates[level]; }

    /** Whether storage exists. */
    boolean allocated() { return layers.length > 0; }

    /**
     * Storage for {@code capacity} pages, written once now so the driver commits it while the board loads; the bound
     * framebuffer, viewport and clear colour stay as they were. Every page is lost; the same capacity keeps the storage.
     */
    void allocate(int capacity) {
        if (capacity == layers.length) { return; }
        release();
        if (capacity <= 0) { return; }
        for (int i = 0; i < formats.length; i++) { arrays[i] = new PageArray(formats[i], texels, levels, capacity); }
        int framebuffer = Gdx.gl.glGenFramebuffer();
        int previous = boundFramebuffer();
        IntBuffer viewport = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        FloatBuffer clear = BufferUtils.newFloatBuffer(16);
        Gdx.gl.glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, clear);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        try {
            IntBuffer targets = BufferUtils.newIntBuffer(formats.length);
            for (int i = 0; i < formats.length; i++) { targets.put(GL30.GL_COLOR_ATTACHMENT0 + i); }
            targets.flip();
            Gdx.gl30.glDrawBuffers(formats.length, targets);
            layers = new Page[capacity];
            Gdx.gl.glClearColor(0, 0, 0, 0);
            for (int layer = 0; layer < capacity; layer++) {
                for (int level = 0; level < levels; level++) {
                    attach(layer, level);
                    if (layer == 0 && level == 0) {
                        int status = Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
                        if (status != GL20.GL_FRAMEBUFFER_COMPLETE) {
                            throw new IllegalStateException("Page cache framebuffer incomplete: " + status);
                        }
                    }
                    Gdx.gl.glViewport(0, 0, texels >> level, texels >> level);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                }
                free.add(layer);
            }
        } catch (RuntimeException failure) {
            release();
            throw failure;
        } finally {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previous);
            Gdx.gl.glDeleteFramebuffer(framebuffer);
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            Gdx.gl.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
        }
        for (int i = 0; i < TABLES; i++) {
            buffers[i] = Gdx.gl.glGenBuffer();
            Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffers[i]);
            Gdx.gl.glBufferData(GL31.GL_UNIFORM_BUFFER, TABLE_BYTES, null, GL20.GL_DYNAMIC_DRAW);
        }
        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    /** Attaches mip {@code level} of {@code layer} of every array to the bound framebuffer. */
    void attach(int layer, int level) {
        for (int i = 0; i < formats.length; i++) {
            Gdx.gl30.glFramebufferTextureLayer(GL20.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                  arrays[i].getTextureObjectHandle(), level, layer);
        }
    }

    /** Every page is lost: none is ready until it builds again. */
    void invalidateAll() {
        revision++;
        for (Page page : pages.values()) {
            page.revision = -1;
            page.step = 0;
            page.stale = false;
            dropSpare(page);
        }
    }

    private void dropSpare(Page page) {
        if (page.spare < 0) { return; }
        layers[page.spare] = null;
        free.add(page.spare);
        page.spare = -1;
    }

    /**
     * The pages of pyramid level {@code level} within [x0, x1] x [y0, y1] (page coordinates, inclusive) are stale: a
     * ready one keeps serving its content while it rebuilds, one in the middle of a build starts again.
     */
    void invalidate(int level, int x0, int y0, int x1, int y1) {
        for (Page page : pages.values()) {
            if (page.level == level && page.x >= x0 && page.x <= x1 && page.y >= y0 && page.y <= y1) {
                page.stale = ready(page);
                page.step = 0;
            }
        }
    }

    /** Starts a frame's needs; pages holding no layer are forgotten until needed again. */
    void begin() {
        frame++;
        needed.clear();
        pages.values().removeIf(page -> page.layer < 0);
    }

    /**
     * Page (x, y) of pyramid level {@code level} is needed this frame, in priority order; a pinned page keeps its
     * layer this frame, an unpinned one (a prefetch) only takes a layer nothing pinned needs.
     */
    void need(int level, int x, int y, boolean pinned) {
        Page page = pages.computeIfAbsent(pack(level, x, y), ignored -> new Page(level, x, y));
        if (page.needed == frame) {
            page.pinned |= pinned;
            return;
        }
        page.needed = frame;
        page.pinned = pinned;
        page.urgent = false;
        needed.add(page);
    }

    /** Page (x, y) of {@code level}, stale and needed this frame, no longer serves: it builds with those not built. */
    void urgent(int level, int x, int y) {
        Page page = pages.get(pack(level, x, y));
        if (page != null && page.needed == frame) { page.urgent = true; }
    }

    private boolean ready(Page page) { return page.layer >= 0 && page.revision == revision; }

    /** Page (x, y) of {@code level}'s layer while it can be read (stale or not), else -1. */
    int layer(int level, int x, int y) {
        Page page = pages.get(pack(level, x, y));
        return page != null && ready(page) ? page.layer : -1;
    }

    /** Whether page (x, y) of {@code level} is ready and current: content other pages may be built from. */
    boolean current(int level, int x, int y) {
        Page page = pages.get(pack(level, x, y));
        return page != null && ready(page) && !page.stale;
    }

    /**
     * Builds the needed pages a step at a time, within this frame's share of {@code budget} nanoseconds of GPU time a
     * frame, and before {@code cpuDeadline} (System.nanoTime): first those not ready and the urgent ones, in their
     * order, then the other stale ones, which serve meanwhile. The budget is a target: the cost of a step is an
     * estimate from earlier steps, a frame may take none, and credit carries over by up to one step so a step dearer
     * than the budget still runs every few frames. True when it stopped for no budget: every needed page that could take
     * a layer is built.
     */
    boolean fill(long budget, long cpuDeadline, Filler filler) {
        readTimings();
        long dearest = 0;
        for (Page page : needed) { dearest = Math.max(dearest, estimate(page.level)); }
        credit = Math.min(credit + budget, Math.max(budget, dearest) + budget);
        int filled = 0;
        try {
            for (boolean staleOnes : new boolean[] { false, true }) {
                for (Page page : needed) {
                    if (staleOnes != (page.stale && !page.urgent)) { continue; }
                    while (!ready(page) || page.stale) {
                        long estimate = estimate(page.level);
                        if (credit < estimate || System.nanoTime() >= cpuDeadline) { return false; }
                        // A stale page builds aside, in a spare layer, and keeps serving its old one meanwhile.
                        boolean aside = ready(page);
                        if (aside ? page.spare < 0 && (page.spare = take(page)) < 0
                              : page.layer < 0 && (page.layer = take(page)) < 0) { break; }
                        int query = GL15.glGenQueries();
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
                        int result;
                        try {
                            result = filler.fill(page.level, page.x, page.y, aside ? page.spare : page.layer,
                                  page.step++);
                        } finally {
                            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                            queries.add(new int[] { query, page.level });
                        }
                        if (result == AGAIN) {
                            // Not ready on what it could not draw: it starts over on a later frame.
                            page.step = 0;
                            credit -= estimate;
                            break;
                        }
                        // Ready only after the last step: a layer handed over keeps no readiness of its last page.
                        if (result == DONE) {
                            if (aside) {
                                layers[page.layer] = null;
                                free.add(page.layer);
                                page.layer = page.spare;
                                page.spare = -1;
                            }
                            page.revision = revision;
                            page.stale = false;
                            fills++;
                        }
                        credit -= estimate;
                        filled++;
                        steps++;
                    }
                    if (page.layer < 0 && !page.pinned) { break; }
                }
            }
            return true;
        } finally {
            if (filled == 0) { credit = Math.min(credit, Math.max(budget, dearest)); }
        }
    }

    /**
     * Writes page table {@code index} for the window of {@code columns} x {@code rows} pages of pyramid level
     * {@code level} from (x0, y0), its first corner at ({@code originX}, {@code originY}) in the owner's units and
     * pages {@code pageSize} of them: each page's {@link #entry}. False if the window holds more pages than the table.
     */
    boolean upload(int index, int level, int x0, int y0, int columns, int rows, float originX, float originY,
          float pageSize, Serves serves) {
        if (buffers[index] == 0 || columns <= 0 || rows <= 0 || columns * rows > TABLE_ENTRIES) { return false; }
        table.clear();
        table.putFloat(originX).putFloat(originY).putFloat(pageSize).putFloat(texels);
        table.putFloat(columns).putFloat(rows).putFloat(gutter).putFloat(0);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                table.putInt(entry(level, x0 + x, y0 + y, serves));
            }
        }
        int used = table.position();
        table.flip();
        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffers[index]);
        Gdx.gl.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, used, table);
        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        return true;
    }

    /** Page (x, y) of {@code level}'s table entry: its layer while it can be read and serves, else -1. */
    int entry(int level, int x, int y, Serves serves) {
        int layer = layer(level, x, y);
        return layer >= 0 && serves.serves(x, y) ? layer : -1;
    }

    /** Binds page table {@code index} to uniform block binding {@code binding}. */
    void bindTable(int index, int binding) {
        if (buffers[index] != 0) { Gdx.gl30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, buffers[index]); }
    }

    /**
     * A layer for {@code page}, its main one (built from the first step) or its spare: a free one, else one of the page
     * least recently needed and not needed now; -1 if none. A victim losing its spare starts that rebuild again; one
     * losing its main layer is no longer ready, and its spare goes too.
     */
    private int take(Page page) {
        Integer free = this.free.poll();
        int layer = free == null ? -1 : free;
        if (layer < 0) {
            Page victim = null;
            for (int i = 0; i < layers.length; i++) {
                Page owner = layers[i];
                if (owner == null || owner == page || owner.needed == frame && (owner.pinned || !page.pinned)) {
                    continue;
                }
                if (victim == null || owner.needed < victim.needed) {
                    victim = owner;
                    layer = i;
                }
            }
            if (victim == null) { return -1; }
            if (layer == victim.spare) {
                victim.spare = -1;
                victim.step = 0;
            } else {
                victim.layer = -1;
                victim.revision = -1;
                victim.step = 0;
                victim.stale = false;
                dropSpare(victim);
                if (victim.needed != frame) { pages.remove(pack(victim.level, victim.x, victim.y)); }
            }
        }
        if (page.layer < 0) {
            page.revision = -1;
            page.step = 0;
            page.stale = false;
        }
        layers[layer] = page;
        return layer;
    }

    /** Folds finished timer queries into their levels' cost estimates. */
    private void readTimings() {
        while (!queries.isEmpty()) {
            int[] query = queries.peek();
            if (GL15.glGetQueryObjecti(query[0], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) { return; }
            long elapsed = GL33.glGetQueryObjecti64(query[0], GL15.GL_QUERY_RESULT);
            queries.remove();
            GL15.glDeleteQueries(query[0]);
            int level = query[1];
            long[] recent = costs[level];
            recent[costCounts[level]++ % recent.length] = elapsed;
            long[] sorted = Arrays.copyOf(recent, Math.min(costCounts[level], recent.length));
            Arrays.sort(sorted);
            estimates[level] = sorted[Math.max(0, sorted.length - 2)];
        }
    }

    private static long pack(int level, int x, int y) {
        return (long) level << 58 | (x & 0x1FFFFFFFL) << 29 | y & 0x1FFFFFFFL;
    }

    private int boundFramebuffer() {
        binding.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, binding);
        return binding.get(0);
    }

    private void release() {
        for (int i = 0; i < arrays.length; i++) {
            if (arrays[i] != null) { arrays[i].dispose(); arrays[i] = null; }
        }
        for (int i = 0; i < TABLES; i++) {
            if (buffers[i] != 0) { Gdx.gl.glDeleteBuffer(buffers[i]); buffers[i] = 0; }
        }
        for (int[] query : queries) { GL15.glDeleteQueries(query[0]); }
        queries.clear();
        pages.clear();
        free.clear();
        layers = new Page[0];
        revision++;
    }

    @Override
    public void dispose() { release(); }

    /** One array of the cache: every page a layer with its own mip levels, filtered like the terrain's own arrays. */
    private static final class PageArray extends TextureArray {
        PageArray(int internalFormat, int texels, int levels, int capacity) {
            super(new Storage(internalFormat, texels, levels, capacity));
            bind();
            Gdx.gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAX_LEVEL, levels - 1);
            Gdx.gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_LINEAR_MIPMAP_LINEAR);
            Gdx.gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_LINEAR);
            Gdx.gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
            Gdx.gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
            // Bounded so a read's footprint stays within the gutter (MAX_ANISOTROPY).
            float anisotropy = Math.min(MAX_ANISOTROPY, GLTexture.getMaxAnisotropicFilterLevel());
            if (anisotropy > 1) {
                Gdx.gl.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
            }
            Gdx.gl.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    /** Empty storage of every level, sized here: libGDX's allocator shares internal and input enums. */
    private static final class Storage implements TextureArrayData {
        private final int internalFormat, texels, levels, depth;

        Storage(int internalFormat, int texels, int levels, int depth) {
            this.internalFormat = internalFormat;
            this.texels = texels;
            this.levels = levels;
            this.depth = depth;
        }

        @Override public boolean isPrepared() { return true; }
        @Override public void prepare() { }
        @Override public int getWidth() { return texels; }
        @Override public int getHeight() { return texels; }
        @Override public int getDepth() { return depth; }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL20.GL_RGBA; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }

        @Override
        public void consumeTextureArrayData() {
            for (int level = 0; level < levels; level++) {
                Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, internalFormat, texels >> level, texels >> level,
                      depth, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, (java.nio.Buffer) null);
            }
        }
    }
}
