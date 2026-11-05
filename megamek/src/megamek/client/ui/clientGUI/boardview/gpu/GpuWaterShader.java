/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/**
 * Water material: palette, falling sheet and the receiving pool's impacts. Everything that varies across a hex
 * (depth, bank distance, current, rapids) comes from continuous inputs instead, so neighbouring hexes of any depth
 * or current share one seamless surface: the chunk's {@link Field}, per-vertex agitation and the shared detail map.
 */
final class GpuWaterShader extends Attribute {
    /** True derives water color from each pixel's depth; false restores the authored Saxarba GIF colors. */
    static final boolean USE_PROCEDURAL_WATER = true;
    /** Opacity of the authored GIF color; procedural water derives its opacity from the depth of each pixel. */
    static final float SURFACE_OPACITY = 0.48f;
    static final long TYPE = register("boardWaterSurface");
    /** Separate programs; palette and colour source remain material parameters within each mode. */
    enum Mode { SURFACE, FALL, SPRAY, CUT, BEYOND, DEPTH }
    /** Palette indices shared with water-optics.glsl and the submerged bed's vertex color. */
    static final int CLEAR = 0, MARS = 1, VOLCANO = 2, HAZARDOUS = 3;
    /** Depth encoded by the field and by the bed's vertex color, in levels; deeper water is uniformly deep. */
    static final float DEPTH_RANGE = 4;
    /** Signed bank distance encoded by the field, in hex widths; banks further away no longer shape the surface. */
    private static final float SHORE_RANGE = 0.4f;
    /** Currents are encoded in hex widths per second; the fastest approach to a fall stays well inside it. */
    private static final float CURRENT_RANGE = 1;
    /** Radius of the kernel that blends neighbouring currents and rapids, in hex widths. */
    private static final float BLEND_RADIUS = 0.9f;
    /** A river's surface disturbance fades through connected still water over this many hexes. */
    private static final int RIPPLE_REACH = 4;
    private static final int DETAIL_SIZE = 256;

    /**
     * A stretch of the line where a fall lands, the water it spreads into lying to the right of {@code from} to
     * {@code to}, and the radius of the boil it churns up.
     */
    record Impact(Vector3 from, Vector3 to, float radius) { }
    /** Most landing stretches one pool churns with; the shader's arrays hold this many. */
    static final int MAX_IMPACTS = 12;

    private final int palette;
    final Mode mode;
    private final boolean procedural;
    private final Field field;
    final List<Impact> impacts;

    GpuWaterShader(BoardScene scene, BoardSurface surface, boolean procedural, boolean falling, Field field) {
        super(TYPE);
        BoardScene.Tile tile = surface.tile;
        palette = palette(tile.liquid());
        mode = falling ? Mode.FALL : Mode.SURFACE;
        this.procedural = procedural;
        this.field = field;
        impacts = falling || field == null ? List.of() : impacts(scene, tile, field::surface);
    }

    /**
     * Where falls land near this pool: into it and into its neighbours at its level, so the boil of a fall that lands
     * by a corner spreads on across every hex around it without a seam. Each landing follows its crest in two
     * stretches; the nearest are kept. {@code surfaces} gives the surface of the open water at a hex, or null.
     */
    static List<Impact> impacts(BoardScene scene, BoardScene.Tile tile, Function<Coords, BoardSurface> surfaces) {
        List<Coords> pools = new ArrayList<>(List.of(tile.coords()));
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile beside = scene.tile(tile.coords().translated(direction));
            if (beside != null && beside.elevation() == tile.elevation()
                  && tile.joins(beside, direction)) {
                BoardSurface frozen = beside.frozen() ? surfaces.apply(beside.coords()) : null;
                if (!beside.frozen() || frozen != null && !frozen.waterFaces.isEmpty()) { pools.add(beside.coords()); }
            }
        }
        Vector3 middle = BoardGeometry.center(tile.coords(), 0);
        List<Impact> hits = new ArrayList<>();
        for (Coords pool : pools) {
            for (int direction = 0; direction < 6; direction++) {
                BoardSurface upper = surfaces.apply(pool.translated(direction));
                if (upper == null || upper.tile.frozen()) { continue; }
                for (BoardSurface.Side fall : upper.waterfalls) {
                    Coords below = upper.tile.coords().translated(BoardGeometry.edgeDirection(fall.edge()));
                    if (!below.equals(pool)) { continue; }
                    float levels = (BoardGeometry.waterZ(upper.tile) - fall.lowA()) / BoardGeometry.level();
                    float radius = (0.12f + 0.05f * (float) Math.sqrt(Math.min(4, levels))) * BoardGeometry.width();
                    // A narrow stream cannot churn a lake-sized disk. Leave quieter water beside its impact.
                    radius = Math.min(radius, Math.max(2 * BoardGeometry.hexScale(), .3f * fall.a().dst(fall.b())));
                    float start = upper.fallJoins(fall, true) ? 0 : .08f;
                    float end = upper.fallJoins(fall, false) ? 1 : .92f;
                    Vector3 from = GpuWaterfall.landing(upper, fall, start);
                    for (int k = 1; k <= 2; k++) {
                        Vector3 to = GpuWaterfall.landing(upper, fall, start + (end - start) * k / 2f);
                        // The boil reaches out a little over twice its radius, and behind the curtain to the wall.
                        if (distance(middle, from, to) < BoardGeometry.width() * .6f + 2.2f * radius) {
                            hits.add(new Impact(from, to, radius));
                        }
                        from = to;
                    }
                }
            }
        }
        hits.sort((a, b) -> {
            int order = Float.compare(distance(middle, a.from(), a.to()), distance(middle, b.from(), b.to()));
            if (order == 0) { order = Float.compare(a.from().x, b.from().x); }
            return order != 0 ? order : Float.compare(a.from().y, b.from().y);
        });
        return List.copyOf(hits.subList(0, Math.min(MAX_IMPACTS, hits.size())));
    }

    /** Level distance from p to the segment from a to b. */
    private static float distance(Vector3 p, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y, length2 = dx * dx + dy * dy;
        float t = length2 == 0 ? 0 : Math.clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / length2, 0, 1);
        return (float) Math.hypot(a.x + dx * t - p.x, a.y + dy * t - p.y);
    }

    private GpuWaterShader(GpuWaterShader original, Mode mode) {
        this(original, mode, original.field);
    }

    private GpuWaterShader(GpuWaterShader original, Mode mode, Field field) {
        super(TYPE);
        // The impacts are immutable and the chunk owns the field; copied materials share both.
        palette = original.palette;
        this.mode = mode;
        procedural = original.procedural;
        this.field = field;
        impacts = mode == Mode.SURFACE ? original.impacts : List.of();
    }

    /** Unchanged vertices in a replacement chunk must sample that chunk's newly owned field. */
    GpuWaterShader withField(Field field) { return new GpuWaterShader(this, mode, field); }

    /** Authored animations and local waterfall impacts retain their individual materials. */
    Field batchField() { return procedural && mode == Mode.SURFACE && impacts.isEmpty() ? field : null; }

    GpuWaterShader depth() { return new GpuWaterShader(this, Mode.DEPTH); }

    /** The same water's spray, thrown up where its falls land. */
    GpuWaterShader spray() { return new GpuWaterShader(this, Mode.SPRAY); }

    /** Optical section at the board edge: its top (colour G zero) follows the surface waves; authored frames are dropped. */
    GpuWaterShader cut() { return new GpuWaterShader(this, Mode.CUT); }

    /** What a view through the water meets beyond the board, drawn on a section's far side with the same displacement. */
    GpuWaterShader beyond() { return new GpuWaterShader(this, Mode.BEYOND); }

    static int palette(BoardLiquid liquid) {
        if (liquid.kind() == BoardLiquid.Kind.HAZARDOUS) { return HAZARDOUS; }
        return switch (liquid.theme()) {
            case "mars" -> MARS;
            case "volcano" -> VOLCANO;
            default -> CLEAR;
        };
    }

    static void register(DefaultShader shader) {
        shader.register("u_waterMaterial", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null) {
                    target.set(id, (float) water.palette, water.procedural ? 1f : 0f);
                }
            }
        });
        shader.register("u_waterField", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null && water.field != null) { target.set(id, water.field.texture); }
            }
        });
        shader.register("u_waterFieldMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null && water.field != null) {
                    Field field = water.field;
                    target.set(id, field.scaleX, field.scaleY, field.offsetX, field.offsetY);
                }
            }
        });
        shader.register("u_splashCount", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null) { target.set(id, water.impacts.size()); }
            }
        });
        for (int index = 0; index < MAX_IMPACTS; index++) {
            final int impactIndex = index;
            shader.register("u_splashLines[" + index + "]", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var water = attributes.get(GpuWaterShader.class, TYPE);
                    if (water != null && impactIndex < water.impacts.size()) {
                        Impact impact = water.impacts.get(impactIndex);
                        float scale = 1 / BoardGeometry.width();
                        target.set(id, impact.from().x * scale, impact.from().y * scale,
                              impact.to().x * scale, impact.to().y * scale);
                    }
                }
            });
            shader.register("u_splashRadii[" + index + "]", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var water = attributes.get(GpuWaterShader.class, TYPE);
                    if (water != null && impactIndex < water.impacts.size()) {
                        target.set(id, water.impacts.get(impactIndex).radius() / BoardGeometry.width());
                    }
                }
            });
        }
    }

    @Override
    public GpuWaterShader copy() { return new GpuWaterShader(this, mode); }

    @Override
    public int hashCode() {
        int result = 31 * super.hashCode() + palette;
        result = 31 * result + mode.ordinal();
        result = 31 * result + (procedural ? 1 : 0);
        result = 31 * result + System.identityHashCode(field);
        return 31 * result + impacts.hashCode();
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        GpuWaterShader water = (GpuWaterShader) other;
        int comparison = Integer.compare(palette, water.palette);
        if (comparison == 0) { comparison = mode.compareTo(water.mode); }
        if (comparison == 0) { comparison = Boolean.compare(procedural, water.procedural); }
        if (comparison == 0) {
            comparison = Integer.compare(System.identityHashCode(field), System.identityHashCode(water.field));
        }
        if (comparison == 0) { comparison = Integer.compare(impacts.size(), water.impacts.size()); }
        for (int i = 0; comparison == 0 && i < impacts.size(); i++) {
            Impact a = impacts.get(i), b = water.impacts.get(i);
            comparison = Float.compare(a.from().x, b.from().x);
            if (comparison == 0) { comparison = Float.compare(a.from().y, b.from().y); }
            if (comparison == 0) { comparison = Float.compare(a.to().x, b.to().x); }
            if (comparison == 0) { comparison = Float.compare(a.to().y, b.to().y); }
            if (comparison == 0) { comparison = Float.compare(a.radius(), b.radius()); }
        }
        return comparison;
    }

    /**
     * Shared tiling detail maps, built once: RG ripple slope, B foam noise, A caustic network. Every channel repeats
     * seamlessly, and mipmaps average the slopes, so distant water calms into a clean reflection instead of aliasing.
     */
    static Texture detailTexture() {
        byte[] rgba = GpuAssets.joined(prepareDetail());
        Pixmap pixels = new Pixmap(DETAIL_SIZE, DETAIL_SIZE, Pixmap.Format.RGBA8888);
        try {
            pixels.getPixels().put(0, rgba);
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    /** {@link #detailTexture}'s RGBA bytes, the same for every terrain: computed once per process, on the common pool. */
    private static CompletableFuture<byte[]> detailPixels;

    /**
     * Starts computing the detail maps on the common pool, once: a view calls it as it is made, before its window and
     * GL context; its terrains take them.
     */
    static synchronized CompletableFuture<byte[]> prepareDetail() {
        if (detailPixels == null) { detailPixels = GpuAssets.pooled(GpuWaterShader::detailBytes); }
        return detailPixels;
    }

    private static byte[] detailBytes() {
        int size = DETAIL_SIZE;
        float[] slopeX = new float[size * size], slopeY = new float[size * size];
        Random random = new Random(0x7761746572L);
        // Random-phase waves on integer wave vectors: a small wind-driven ripple spectrum that tiles in both
        // directions. Spread about one axis, the shader's downwind, crests run in short trains instead of bumps, yet
        // cross each other widely enough never to line up into ruled stripes.
        waves(random, 128, 3, 26, 2.2f, 1.3f, slopeX, slopeY);
        float[] foam = foam(random, size);
        float squared = 0;
        for (int i = 0; i < slopeX.length; i++) { squared += slopeX[i] * slopeX[i] + slopeY[i] * slopeY[i]; }
        // Three standard deviations fill the encoding; rarer, steeper facets saturate harmlessly.
        float range = 3 * (float) Math.sqrt(squared / (2 * slopeX.length));
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (float value : foam) { low = Math.min(low, value); high = Math.max(high, value); }
        float[] caustics = caustics(random, size);
        byte[] rgba = new byte[size * size * 4];
        for (int i = 0; i < slopeX.length; i++) {
            rgba[i * 4] = unit(0.5f + 0.5f * slopeX[i] / range);
            rgba[i * 4 + 1] = unit(0.5f + 0.5f * slopeY[i] / range);
            // Contrast leaves foam free to break into patches instead of a uniform haze.
            float froth = (foam[i] - low) / (high - low);
            rgba[i * 4 + 2] = unit(froth * froth * (3 - 2 * froth));
            rgba[i * 4 + 3] = unit(caustics[i]);
        }
        return rgba;
    }

    /** Sums wave slopes as separable angle additions: two table lookups per wave and texel, no trigonometry. */
    private static void waves(Random random, int count, float shortest, float longest, float falloff, float spread,
          float[] slopeX, float[] slopeY) {
        int size = DETAIL_SIZE;
        float[] cosX = new float[size], sinX = new float[size], cosY = new float[size], sinY = new float[size];
        for (int wave = 0; wave < count; wave++) {
            // Log-uniform wave numbers, in cycles per tile, with amplitude falling off toward short ripples.
            double number = shortest * Math.pow(longest / shortest, random.nextDouble());
            // A wave and its opposite share one axis; spread is the half-angle around it, most waves near the axis.
            double angle = (random.nextDouble() + random.nextDouble() - 1) * spread;
            int kx = (int) Math.round(number * Math.cos(angle)), ky = (int) Math.round(number * Math.sin(angle));
            if (kx == 0 && ky == 0) { continue; }
            float amplitude = (float) Math.pow(Math.hypot(kx, ky), -falloff);
            double phase = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < size; i++) {
                double x = Math.PI * 2 * kx * i / size, y = Math.PI * 2 * ky * i / size + phase;
                cosX[i] = (float) Math.cos(x);
                sinX[i] = (float) Math.sin(x);
                cosY[i] = (float) Math.cos(y);
                sinY[i] = (float) Math.sin(y);
            }
            float gradientX = -amplitude * (float) (Math.PI * 2 * kx);
            float gradientY = -amplitude * (float) (Math.PI * 2 * ky);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float sine = sinX[x] * cosY[y] + cosX[x] * sinY[y];
                    slopeX[y * size + x] += gradientX * sine;
                    slopeY[y * size + x] += gradientY * sine;
                }
            }
        }
    }

    /**
     * Tiling gradient-noise fBm: soft, organic blobs with no preferred direction, so thresholded foam breaks into
     * rounded patches and strands instead of the diamonds that a few interfering plane waves would form.
     */
    private static float[] foam(Random random, int size) {
        float[] result = new float[size * size];
        float amplitude = 1;
        for (int cells = 8; cells <= 128; cells *= 2, amplitude *= 0.65f) {
            float[] gradientX = new float[cells * cells], gradientY = new float[cells * cells];
            for (int i = 0; i < gradientX.length; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                gradientX[i] = (float) Math.cos(angle);
                gradientY[i] = (float) Math.sin(angle);
            }
            float scale = cells / (float) size;
            for (int y = 0; y < size; y++) {
                int y0 = (int) (y * scale), y1 = (y0 + 1) % cells;
                float ty = y * scale - y0, sy = ty * ty * ty * (ty * (ty * 6 - 15) + 10);
                for (int x = 0; x < size; x++) {
                    int x0 = (int) (x * scale), x1 = (x0 + 1) % cells;
                    float tx = x * scale - x0, sx = tx * tx * tx * (tx * (tx * 6 - 15) + 10);
                    float a = gradientX[y0 * cells + x0] * tx + gradientY[y0 * cells + x0] * ty;
                    float b = gradientX[y0 * cells + x1] * (tx - 1) + gradientY[y0 * cells + x1] * ty;
                    float c = gradientX[y1 * cells + x0] * tx + gradientY[y1 * cells + x0] * (ty - 1);
                    float d = gradientX[y1 * cells + x1] * (tx - 1) + gradientY[y1 * cells + x1] * (ty - 1);
                    float top = a + (b - a) * sx, bottom = c + (d - c) * sx;
                    result[y * size + x] += amplitude * (top + (bottom - top) * sy);
                }
            }
        }
        return result;
    }

    /**
     * A tiling network of focused light: bright where two warped Voronoi cells meet. Two drifting copies are
     * combined by their minimum in the shader, so the web re-forms continuously like light under moving ripples.
     */
    private static float[] caustics(Random random, int size) {
        int cells = 8;
        float cell = size / (float) cells;
        float[] pointX = new float[cells * cells], pointY = new float[cells * cells];
        for (int i = 0; i < pointX.length; i++) {
            pointX[i] = (i % cells + 0.15f + 0.7f * random.nextFloat()) * cell;
            pointY[i] = (i / cells + 0.15f + 0.7f * random.nextFloat()) * cell;
        }
        float[] result = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // A gentle periodic warp bends the straight cell borders into rounded filaments.
                float wx = x + 0.22f * cell * (float) Math.sin(Math.PI * 2 * (2 * y / (double) size + 0.13));
                float wy = y + 0.22f * cell * (float) Math.sin(Math.PI * 2 * (3 * x / (double) size + 0.61));
                int column = (int) Math.floor(wx / cell), row = (int) Math.floor(wy / cell);
                float first = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = Math.floorMod(column + dx, cells), r = Math.floorMod(row + dy, cells);
                        float px = pointX[r * cells + c] + (column + dx - c) * cell;
                        float py = pointY[r * cells + c] + (row + dy - r) * cell;
                        float distance = (float) Math.hypot(wx - px, wy - py);
                        if (distance < first) {
                            second = first;
                            first = distance;
                        } else if (distance < second) {
                            second = distance;
                        }
                    }
                }
                float border = (second - first) / cell;
                float line = 1 - Math.clamp(border / 0.2f, 0, 1);
                result[y * size + x] = line * line * (3 - 2 * line);
            }
        }
        return result;
    }

    private static byte unit(float value) {
        return (byte) Math.round(Math.clamp(value, 0, 1) * 255);
    }

    /**
     * Derived once per terrain update, before fields or meshes are built. Continue a river's small surface motion
     * into connected still water, then dissipate it; do not give the whole lake a current. The existing field kernel
     * blends these samples between hex centres. Routed currents and agitation stay intact, and no motion passes
     * through land, ice, lava or a different surface elevation. This map belongs only to the renderer.
     */
    static Map<Coords, BoardFlow.Current> surfaceCurrents(BoardScene scene, Map<Coords, BoardFlow.Current> routed) {
        var result = new HashMap<>(routed);
        Map<Coords, BoardFlow.Current> frontier = new HashMap<>();
        for (var entry : routed.entrySet()) {
            var tile = scene.tile(entry.getKey());
            if (tile == null || !tile.liquid().present() || tile.liquid().molten() || tile.frozen()) { continue; }
            var current = entry.getValue();
            float speed = (float) Math.hypot(current.u(), current.v() * BoardGeometry.height() / BoardGeometry.width());
            if (speed == 0) { continue; }
            // A falling approach may be fast, but it must not paint several lake tiles with rapids agitation.
            float scale = Math.min(1, .1f / speed);
            frontier.put(entry.getKey(), new BoardFlow.Current(current.u() * scale, current.v() * scale));
        }
        for (int distance = 1; distance < RIPPLE_REACH && !frontier.isEmpty(); distance++) {
            var incoming = new HashMap<Coords, Vector3>();
            for (var entry : frontier.entrySet()) {
                var from = scene.tile(entry.getKey());
                for (int direction = 0; direction < 6; direction++) {
                    Coords at = entry.getKey().translated(direction);
                    if (result.containsKey(at)) { continue; }
                    var to = scene.tile(at);
                    if (to == null || to.frozen() || to.elevation() != from.elevation()
                          || !from.joins(to, direction)) { continue; }
                    var current = entry.getValue();
                    incoming.computeIfAbsent(at, unused -> new Vector3()).add(current.u(), current.v(), 1);
                }
            }
            frontier = new HashMap<>();
            float t = distance / (float) RIPPLE_REACH;
            float fade = (1 - t) * (1 - t) * (1 + 2 * t);
            for (var entry : incoming.entrySet()) {
                Vector3 sum = entry.getValue();
                var carrier = new BoardFlow.Current(sum.x / sum.z, sum.y / sum.z);
                frontier.put(entry.getKey(), carrier);
                result.put(entry.getKey(), new BoardFlow.Current(carrier.u() * fade, carrier.v() * fade));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * One chunk's surface inputs on a world-aligned lattice shared by every chunk, so bilinear samples agree exactly
     * along chunk borders: R signed distance to the nearest bank (positive over water), G optical depth, BA current.
     * Built with the chunk's terrain and owned by it; nothing here changes per frame.
     */
    static final class Field implements Disposable {
        final Texture texture;
        final float scaleX, scaleY, offsetX, offsetY;
        final Prepared image;
        private Pools pools;

        private Field(Texture texture, float scaleX, float scaleY, float offsetX, float offsetY, Prepared source, boolean retainPixels) {
            this.texture = texture;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            pools = source.pools();
            // Retain only compact pixels for page atlases, never the sampled terrain graph.
            image = retainPixels ? new Prepared(source.width(), source.height(), source.spacing(), source.firstX(), source.firstY(),
                  source.pixels(), null) : null;
        }

        /** Null when the chunk has no open water; surfaces of the chunk's own tiles are reused, not rebuilt. */
        record Prepared(int width, int height, float spacing, int firstX, int firstY, byte[] pixels, Pools pools) {
            boolean samePixels(Prepared other) {
                return width == other.width && height == other.height && spacing == other.spacing
                      && firstX == other.firstX && firstY == other.firstY && Arrays.equals(pixels, other.pixels);
            }

            /** Only the small texture upload needs a GL context; field sampling is pure CPU work. */
            Field upload() {
                return upload(true);
            }

            Field upload(boolean retainPixels) {
                Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                try {
                    image.getPixels().put(pixels).flip();
                    Texture texture = new Texture(image);
                    texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                    texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
                    return new Field(texture, 1 / (spacing * width), 1 / (spacing * height),
                          -firstX / (float) width, -firstY / (float) height, this, retainPixels);
                } finally {
                    image.dispose();
                }
            }
        }

        static Prepared prepare(BoardScene scene, Map<Coords, BoardFlow.Current> currents,
              Map<Coords, BoardSurface> surfaces) {
            return prepare(scene, currents, surfaces, false);
        }

        /** Stitch the existing world-aligned fields. Padding overlaps use the sample furthest inside its source. */
        static Prepared atlas(List<Prepared> sources) {
            if (sources.isEmpty()) { throw new IllegalArgumentException("Empty water atlas"); }
            int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, right = Integer.MIN_VALUE, top = Integer.MIN_VALUE;
            float spacing = sources.getFirst().spacing();
            for (Prepared source : sources) {
                if (source.spacing() != spacing) { throw new IllegalArgumentException("Mismatched water field spacing"); }
                x = Math.min(x, source.firstX()); y = Math.min(y, source.firstY());
                right = Math.max(right, source.firstX() + source.width());
                top = Math.max(top, source.firstY() + source.height());
            }
            int width = right - x, height = top - y;
            byte[] pixels = new byte[width * height * 4];
            short[] margins = new short[width * height];
            Arrays.fill(margins, (short) -1);
            for (Prepared source : sources) {
                for (int row = 0; row < source.height(); row++) {
                    for (int col = 0; col < source.width(); col++) {
                        int target = (source.firstY() - y + row) * width + source.firstX() - x + col;
                        int margin = Math.min(Math.min(row, source.height() - 1 - row),
                              Math.min(col, source.width() - 1 - col));
                        if (margin <= margins[target]) { continue; }
                        System.arraycopy(source.pixels(), (row * source.width() + col) * 4, pixels, target * 4, 4);
                        margins[target] = (short) margin;
                    }
                }
            }
            return new Prepared(width, height, spacing, x, y, pixels, null);
        }

        static Prepared prepare(BoardScene scene, Map<Coords, BoardFlow.Current> currents,
              Map<Coords, BoardSurface> surfaces, boolean molten) {
            Pools pools = new Pools(scene, currents, surfaces, molten);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (BoardSurface surface : surfaces.values()) {
                Pool pool = pools.get(surface.tile.coords());
                if (pool == null) { continue; }
                minX = Math.min(minX, pool.minX);
                minY = Math.min(minY, pool.minY);
                maxX = Math.max(maxX, pool.maxX);
                maxY = Math.max(maxY, pool.maxY);
            }
            if (minX > maxX) { return null; }
            float spacing = BoardGeometry.height() / 16;
            int firstX = (int) Math.floor(minX / spacing) - 1, firstY = (int) Math.floor(minY / spacing) - 1;
            int width = (int) Math.ceil(maxX / spacing) - firstX + 2;
            int height = (int) Math.ceil(maxY / spacing) - firstY + 2;
            // Texels carry a one-texel ring beyond the texture, so the depth blur agrees across chunk borders too.
            int span = width + 2;
            Pool[] owners = new Pool[span * (height + 2)];
            byte[] pixels = new byte[width * height * 4];
            ByteBuffer buffer = ByteBuffer.wrap(pixels);
            float[] sample = new float[4];
            for (int row = -1; row <= height; row++) {
                for (int column = -1; column <= width; column++) {
                    Pool owner = pools.sample((firstX + column + 0.5f) * spacing, (firstY + row + 0.5f) * spacing,
                          sample);
                    owners[(row + 1) * span + column + 1] = owner;
                    if (row < 0 || row == height || column < 0 || column == width) { continue; }
                    int index = (row * width + column) * 4;
                    buffer.put(index, unit(0.5f + 0.5f * sample[0] / SHORE_RANGE));
                    buffer.put(index + 2, unit(0.5f + 0.5f * sample[1] / CURRENT_RANGE));
                    buffer.put(index + 3, unit(0.5f + 0.5f * sample[2] / CURRENT_RANGE));
                }
            }
            float[] depth = depths(pools, owners, span, firstX - 1, firstY - 1, spacing);
            // A 1-2-1 tent softens underwater steps into the few metres a real column blurs them over.
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int center = (row + 1) * span + column + 1;
                    // A sand bar or rooted rock can stand above the surface inside the original outline.
                    // Its contour is a bank too, and its dry side must carry a negative shore distance.
                    if (depth[center] < 0) {
                        int red = Byte.toUnsignedInt(buffer.get((row * width + column) * 4));
                        buffer.put((row * width + column) * 4, (byte) Math.min(red, 255 - red));
                    }
                    float blurred = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int line = center + dy * span;
                        float across = Math.max(0, depth[line - 1]) + 2 * Math.max(0, depth[line])
                              + Math.max(0, depth[line + 1]);
                        blurred += dy == 0 ? 2 * across : across;
                    }
                    buffer.put((row * width + column) * 4 + 1, unit(blurred / 16 / DEPTH_RANGE));
                }
            }
            return new Prepared(width, height, spacing, firstX, firstY, pixels, pools);
        }

        /**
         * Water depth in levels at every texel, over the bed of the pool that owns it. Each pool's bed triangles are
         * visited once and only over the texels they span, instead of searching every triangle for every texel.
         */
        private static float[] depths(Pools pools, Pool[] owners, int span, int firstX, int firstY, float spacing) {
            int rows = owners.length / span;
            float[] bed = new float[owners.length];
            Arrays.fill(bed, Float.NEGATIVE_INFINITY);
            for (Pool pool : pools.all()) {
                for (BoardSurface.Face face : pool.surface.faces) {
                    if (face.finish() == BoardSurface.Finish.ICE) { continue; }
                    Vector3 a = face.a(), b = face.b(), c = face.c();
                    float lowX = Math.min(a.x, Math.min(b.x, c.x)), highX = Math.max(a.x, Math.max(b.x, c.x));
                    float lowY = Math.min(a.y, Math.min(b.y, c.y)), highY = Math.max(a.y, Math.max(b.y, c.y));
                    int left = Math.max(0, (int) Math.ceil(lowX / spacing - 0.5f) - firstX);
                    int right = Math.min(span - 1, (int) Math.floor(highX / spacing - 0.5f) - firstX);
                    int top = Math.max(0, (int) Math.ceil(lowY / spacing - 0.5f) - firstY);
                    int bottom = Math.min(rows - 1, (int) Math.floor(highY / spacing - 0.5f) - firstY);
                    for (int row = top; row <= bottom; row++) {
                        for (int column = left; column <= right; column++) {
                            int index = row * span + column;
                            if (owners[index] != pool) { continue; }
                            bed[index] = Math.max(bed[index],
                                  face.height((firstX + column + 0.5f) * spacing, (firstY + row + 0.5f) * spacing));
                        }
                    }
                }
            }
            float[] depth = new float[owners.length];
            for (int i = 0; i < depth.length; i++) {
                Pool owner = owners[i];
                if (owner == null) { continue; }
                BoardScene.Tile tile = owner.surface.tile;
                float floor = Float.isFinite(bed[i]) ? bed[i] : BoardGeometry.groundZ(tile);
                float x = (firstX + i % span + .5f) * spacing, y = (firstY + i / span + .5f) * spacing;
                depth[i] = (owner.surface.waterHeight(x, y) - floor) / BoardGeometry.level();
            }
            return depth;
        }

        /** Shared rapids and approach currents, plus whitewater confined to the actual descending surface. */
        float agitation(Vector3 point) {
            return pools.agitation(point);
        }

        /** The surface of the open water at {@code coords}, or null where there is none. */
        BoardSurface surface(Coords coords) {
            Pool pool = pools.get(coords);
            return pool == null ? null : pool.surface;
        }

        /** Mesh construction has consumed these samples; rendering needs only the texture and its transform. */
        void finish() { pools = null; }

        @Override
        public void dispose() { texture.dispose(); }
    }

    /** One liquid family around a chunk; separate fields prevent water and lava currents crossing their banks. */
    static final class Pools {
        private final BoardScene scene;
        private final Map<Coords, BoardFlow.Current> currents;
        private final Map<Coords, BoardSurface> surfaces;
        private final Map<Coords, Pool> pools = new HashMap<>();
        private final boolean molten;
        /** Open water of the hex nearest the last sample and of its neighbours; lattice rows revisit each hex often. */
        private final Pool[] candidates = new Pool[7];
        private int count, nearColumn, nearRow = Integer.MIN_VALUE;
        private final float[] scratch = new float[4];
        private final Vector3 point = new Vector3();
        private final Vector2 acceleration = new Vector2();

        Pools(BoardScene scene, Map<Coords, BoardFlow.Current> currents, Map<Coords, BoardSurface> surfaces) {
            this(scene, currents, surfaces, false);
        }

        Pools(BoardScene scene, Map<Coords, BoardFlow.Current> currents, Map<Coords, BoardSurface> surfaces, boolean molten) {
            this.scene = scene;
            this.currents = currents;
            this.surfaces = surfaces;
            this.molten = molten;
        }

        Pool get(Coords coords) {
            if (pools.containsKey(coords)) { return pools.get(coords); }
            BoardScene.Tile tile = scene.tile(coords);
            Pool pool = null;
            if (tile != null && tile.liquid().present() && tile.liquid().molten() == molten) {
                BoardSurface surface = surfaces.get(coords);
                if (surface == null) { surface = new BoardSurface(scene, tile); }
                // Only actual liquid openings participate in a frozen tile; the remaining slab stays a bank.
                if (!tile.frozen() || !surface.waterFaces.isEmpty()) {
                    pool = new Pool(surface, currents.getOrDefault(coords, BoardFlow.Current.STILL));
                }
            }
            pools.put(coords, pool);
            return pool;
        }

        /** Every pool looked up so far: all that any sample can land in. */
        List<Pool> all() {
            return pools.values().stream().filter(Objects::nonNull).toList();
        }

        /**
         * Signed bank distance in hex widths, current in hex widths per second and agitation; returns the pool
         * whose water covers the point, or null over land.
         */
        Pool sample(float x, float y, float[] result) {
            gather(x, y);
            float range = SHORE_RANGE * BoardGeometry.width();
            Pool inside = null;
            float nearest = range * range;
            for (int i = 0; i < count; i++) {
                Pool pool = candidates[i];
                if (inside == null && pool.contains(x, y)) { inside = pool; }
                nearest = pool.shoreDistance2(x, y, nearest);
            }
            // Beyond the board's edge the water runs on as if into the missing hex (BoardSurface.cut): a point there
            // belongs to the nearest open water around it, at that water's full depth, not to land.
            if (inside == null && count > 0 && scene.tile(new Coords(nearColumn, nearRow)) == null) {
                float closest = Float.POSITIVE_INFINITY;
                for (int i = 0; i < count; i++) {
                    Pool pool = candidates[i];
                    float dx = x - pool.centerX, dy = y - pool.centerY;
                    if (dx * dx + dy * dy < closest) {
                        closest = dx * dx + dy * dy;
                        inside = pool;
                    }
                }
            }
            float distance = (float) Math.sqrt(nearest) / BoardGeometry.width();
            result[0] = inside == null ? -distance : distance;
            blend(x, y, result);
            if (inside != null) {
                point.set(x, y, inside.surface.waterHeight(x, y));
                acceleration.setZero();
                for (int i = 0; i < count; i++) {
                    candidates[i].surface.slopeEffects(point, acceleration);
                }
                // Several descents can meet at a junction. Keep their combined visual current inside the field's
                // encoding range, while the ordinary flat-reach current and agitation remain independently blended.
                if (molten) { acceleration.scl(.2f); }
                acceleration.limit(.5f);
                acceleration.add(result[1], result[2]).limit(CURRENT_RANGE);
                result[1] = acceleration.x;
                result[2] = acceleration.y;
            }
            return inside;
        }

        float agitation(Vector3 point) {
            gather(point.x, point.y);
            blend(point.x, point.y, scratch);
            float agitation = scratch[3];
            for (int i = 0; i < count; i++) {
                agitation = Math.max(agitation, candidates[i].surface.slopeAgitation(point));
            }
            return agitation;
        }

        /** Finds the hex whose centre is nearest, then its open water and its neighbours'. */
        private void gather(float x, float y) {
            float width = BoardGeometry.width(), height = BoardGeometry.height();
            int column = Math.round((x - width / 2) / (width * .75f)), bestColumn = column, bestRow = 0;
            float nearest = Float.POSITIVE_INFINITY;
            for (int c = column - 1; c <= column + 1; c++) {
                float offset = (c & 1) * height / 2;
                int row = Math.round((-y - height / 2 - offset) / height);
                float dx = x - (c * width * .75f + width / 2), dy = y + row * height + offset + height / 2;
                if (dx * dx + dy * dy < nearest) {
                    nearest = dx * dx + dy * dy;
                    bestColumn = c;
                    bestRow = row;
                }
            }
            if (bestColumn == nearColumn && bestRow == nearRow) { return; }
            nearColumn = bestColumn;
            nearRow = bestRow;
            Coords hex = new Coords(bestColumn, bestRow);
            count = 0;
            Pool own = get(hex);
            if (own != null) { candidates[count++] = own; }
            for (int direction = 0; direction < 6; direction++) {
                Pool pool = get(hex.translated(direction));
                if (pool != null) { candidates[count++] = pool; }
            }
        }

        /** Current and agitation of the gathered pools, blended by a smooth kernel around each hex centre. */
        private void blend(float x, float y, float[] result) {
            float radius2 = BLEND_RADIUS * BoardGeometry.width() * BLEND_RADIUS * BoardGeometry.width();
            float weight = 0, currentX = 0, currentY = 0, agitation = 0;
            for (int i = 0; i < count; i++) {
                Pool pool = candidates[i];
                float dx = x - pool.centerX, dy = y - pool.centerY, distance2 = (dx * dx + dy * dy) / radius2;
                if (distance2 < 1) {
                    float w = (1 - distance2) * (1 - distance2);
                    weight += w;
                    currentX += w * pool.currentX;
                    currentY += w * pool.currentY;
                    agitation += w * pool.agitation;
                }
            }
            result[1] = weight > 0 ? currentX / weight : 0;
            result[2] = weight > 0 ? currentY / weight : 0;
            result[3] = weight > 0 ? agitation / weight : 0;
        }
    }

    /** One hex of open water: its full outline, the bank segments of that outline, current and agitation. */
    private static final class Pool {
        final BoardSurface surface;
        final float[] outline;
        final float[] banks;
        final float minX, minY, maxX, maxY, centerX, centerY;
        final float currentX, currentY, agitation;

        Pool(BoardSurface surface, BoardFlow.Current current) {
            this.surface = surface;
            List<Vector3> points = new ArrayList<>();
            List<Float> segments = new ArrayList<>();
            for (int edge = 0; edge < 6; edge++) {
                List<Vector3> boundary = surface.waterBoundary(edge);
                for (int i = 0; i < boundary.size() - 1; i++) {
                    Vector3 a = boundary.get(i), b = boundary.get(i + 1);
                    points.add(a);
                    // Open mouths continue into the next pool and falling lips into their sheet; neither is a bank.
                    if (surface.bankAlong(edge, a, b)) { segments.addAll(List.of(a.x, a.y, b.x, b.y)); }
                }
            }
            outline = new float[points.size() * 2];
            float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY;
            float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < points.size(); i++) {
                Vector3 a = points.get(i);
                outline[i * 2] = a.x;
                outline[i * 2 + 1] = a.y;
                lowX = Math.min(lowX, a.x);
                lowY = Math.min(lowY, a.y);
                highX = Math.max(highX, a.x);
                highY = Math.max(highY, a.y);
            }
            for (BoardSurface.Face face : surface.faces) {
                if (face.finish() == BoardSurface.Finish.ICE || face.finish() == BoardSurface.Finish.DRESSING) { continue; }
                waterline(segments, surface, face);
            }
            if (surface.tile.frozen()) {
                // Existing cut-edge skirts intersect the opening's water level: reuse those real ice banks.
                for (BoardSurface.Face face : surface.iceBroken) { waterline(segments, surface, face); }
            }
            for (BoardSurface.Face face : surface.cliffWater) {
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    lowX = Math.min(lowX, p.x);
                    lowY = Math.min(lowY, p.y);
                    highX = Math.max(highX, p.x);
                    highY = Math.max(highY, p.y);
                }
            }
            banks = new float[segments.size()];
            for (int i = 0; i < banks.length; i++) { banks[i] = segments.get(i); }
            minX = lowX;
            minY = lowY;
            maxX = highX;
            maxY = highY;
            centerX = BoardGeometry.centerX(surface.tile.coords());
            centerY = BoardGeometry.centerY(surface.tile.coords());
            // Texture displacement per second, as BoardFlow reports it, becomes world velocity in hex widths.
            currentX = -current.u();
            currentY = current.v() * BoardGeometry.height() / BoardGeometry.width();
            float rapids = switch (surface.tile.liquid().rapids()) {
                case 1 -> .5f;
                case 2 -> .9f;
                default -> 0;
            };
            // A pool pouring over a lip churns as it accelerates, and so does any fast current.
            float approach = Math.clamp(((float) Math.hypot(currentX, currentY) - .12f) / .5f, 0, .6f);
            agitation = Math.max(rapids, Math.max(approach, surface.waterfalls.isEmpty() ? 0 : .2f));
        }

        boolean contains(float x, float y) {
            if (x < minX || x > maxX || y < minY || y > maxY) { return false; }
            if (surface.tile.frozen()) {
                return surface.waterFaces.stream().anyMatch(face -> Float.isFinite(face.height(x, y)));
            }
            boolean inside = false;
            for (int i = 0, j = outline.length - 2; i < outline.length; j = i, i += 2) {
                float xi = outline[i], yi = outline[i + 1], xj = outline[j], yj = outline[j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) { inside = !inside; }
            }
            if (inside) { return true; }
            // The visible surface can reach into a cliff recess beyond the original shore contour.
            return surface.cliffWater.stream().anyMatch(face -> Float.isFinite(face.height(x, y)));
        }

        /** Squared distance to the nearest bank segment, or the given bound when every bank is further away. */
        float shoreDistance2(float x, float y, float bound) {
            float reach = (float) Math.sqrt(bound);
            if (x < minX - reach || x > maxX + reach || y < minY - reach || y > maxY + reach) { return bound; }
            float result = bound;
            for (int i = 0; i < banks.length; i += 4) {
                float ax = banks[i], ay = banks[i + 1], dx = banks[i + 2] - ax, dy = banks[i + 3] - ay;
                float length2 = dx * dx + dy * dy;
                float t = length2 == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length2, 0, 1);
                float ex = ax + dx * t - x, ey = ay + dy * t - y;
                result = Math.min(result, ex * ex + ey * ey);
            }
            return result;
        }
    }

    /** The actual intersection of a ground triangle and the drawn water, including exposed bars and boulders. */
    static void waterline(List<Float> segments, BoardSurface surface, BoardSurface.Face face) {
        Vector3[] points = { face.a(), face.b(), face.c() };
        float[] depths = new float[3];
        for (int i = 0; i < 3; i++) { depths[i] = surface.waterHeight(points[i].x, points[i].y) - points[i].z; }
        // Fully submerged triangles do not create foam lines along the bed's mesh.
        if (Math.min(depths[0], Math.min(depths[1], depths[2])) >= 0
              || Math.max(depths[0], Math.max(depths[1], depths[2])) <= 0) { return; }
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            if ((depths[i] > 0) == (depths[j] > 0)) { continue; }
            float t = depths[i] / (depths[i] - depths[j]);
            segments.add(BoardRelief.lerp(points[i].x, points[j].x, t));
            segments.add(BoardRelief.lerp(points[i].y, points[j].y, t));
        }
    }
}
