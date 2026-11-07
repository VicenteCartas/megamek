/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture3D;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.CustomTexture3DData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * How much of the sky each point above the board sees past the board's own relief, for the sky light of every lit
 * surface (sky-visibility.glsl): a canyon's floor and the feet of its walls see a strip of sky, a plateau all of it.
 * A volume over the board from its tops, the hexes' surfaces and the bodies of the structures standing on them: sampled
 * and its horizons searched on a worker when they change, uploaded on the render thread. It rides on
 * the environment's lighting (GpuEnvironmentLighting), which every lit program with that lighting compiles it for, with
 * a neutral volume until the first arrives: no program changes when a volume does. Render state, never game state.
 */
final class GpuSkyVisibility implements Disposable {
    /**
     * How strongly the board's relief and buildings hide the sky, 0 to 1: 1 their full darkening, .5 half of it. 0 leaves
     * it out of every program and searches nothing.
     */
    static final float STRENGTH = 1;
    /** The cells' horizontal size in metres, coarser where the board would need more than MAX_CELLS along a side. */
    static final float CELL_METRES = 4;
    private static final int MAX_CELLS = 256;
    private static final int MAX_LAYERS = 64;
    /** Azimuths searched for the horizon; each search walks outwards in steps growing by STEP_GROWTH up to REACH. */
    private static final int DIRECTIONS = 16;
    private static final float REACH_METRES = 300;
    private static final float STEP_GROWTH = 1.25f;

    /**
     * The board's tops and the volume's frame, all in world units: {@code heights} the tops; {@code floors} the level each
     * cell's centre stands at, its hex's surface or the top of a structure covering it, without the tops' half cell of
     * reach. Both NaN off the board.
     */
    record Field(int width, int height, int layers, float x, float y, float z, float cell, float layer,
          float[] heights, float[] floors) { }

    /** The neutral volume until the first board's, owned; made when first bound, on the render thread. */
    private Texture3D texture;
    private float originX;
    private float originY;
    private float originZ;
    private float sizeX = 1;
    private float sizeY = 1;
    private float sizeZ = 1;
    private float cell;
    private float strength;
    /** The last tiles seen, and the signature of the surface heights searched or due for a search. */
    private List<BoardScene.Tile> tiles;
    private long heights;
    /** The scene whose heights changed since the search under way began; null when none waits. */
    private BoardScene waiting;
    private CompletableFuture<Volume> pending;

    /** A searched volume: its frame and its values. */
    private record Volume(Field field, byte[] values) { }

    /**
     * Puts this on the lighting of the board's {@code environment} with the sky occlusion at {@code strength} (0 to 1,
     * 0 off) times {@link #STRENGTH}, and its volume on the {@code scene}'s terrain: when the hexes' surface heights change a search starts, one
     * at a time, and its volume replaces the last one when done. A new tile list with the same heights (weather settling
     * on the hexes, say) searches nothing. Render thread.
     */
    void update(Environment environment, BoardScene scene, float strength) {
        if (STRENGTH <= 0) { return; }
        this.strength = MathUtils.clamp(strength, 0, 1) * STRENGTH;
        texture();
        var lighting = environment.get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE);
        if (lighting != null) { lighting.setSkyVisibility(this); }
        if (scene.tiles() != tiles && !scene.tiles().isEmpty()) {
            tiles = scene.tiles();
            long signature = signature(scene);
            if (signature != heights) {
                heights = signature;
                waiting = scene;
            }
        }
        if (pending != null && pending.isDone()) { install(); }
        if (pending == null && waiting != null) { startSearch(waiting); }
    }

    /** Whether the volume of the heights the last update saw is in place: no search runs or waits. */
    boolean current() { return pending == null && waiting == null; }

    /** Installs the volume of the latest heights at once, waiting for its search: reviews that capture the next frame. */
    void finish() {
        if (pending != null) { install(); }
        if (waiting != null) {
            startSearch(waiting);
            install();
        }
    }

    private void startSearch(BoardScene scene) {
        waiting = null;
        // On the worker: the structures' footprints may read their kits from disk.
        pending = CompletableFuture.supplyAsync(() -> {
            Field field = field(scene);
            return new Volume(field, search(field));
        });
    }

    /**
     * The hexes' surfaces and what stands on their structures (a building, fuel tank or industrial plant: its kit, height
     * and connections follow its features; a collapse changes them), and the board geometry that places them.
     */
    private static long signature(BoardScene scene) {
        long result = Float.floatToIntBits(BoardGeometry.level()) * 31L + Float.floatToIntBits(BoardGeometry.width());
        for (BoardScene.Tile tile : scene.tiles()) {
            result = result * 31 + tile.coords().hashCode();
            result = result * 31 + Float.floatToIntBits(BoardGeometry.surfaceZ(tile));
            if (tile.cover().structure() != 0) { result = result * 31 + tile.features().hashCode(); }
        }
        return result;
    }

    private void install() {
        CompletableFuture<Volume> search = pending;
        // Cleared first: a failed search throws once and leaves the last volume in place.
        pending = null;
        Volume volume = search.join();
        Field field = volume.field();
        if (texture != null) { texture.dispose(); }
        texture = upload(field.width(), field.height(), field.layers(), volume.values());
        originX = field.x() - field.cell() / 2;
        originY = field.y() - field.cell() / 2;
        originZ = field.z() - field.layer() / 2;
        sizeX = field.width() * field.cell();
        sizeY = field.height() * field.cell();
        sizeZ = field.layers() * field.layer();
        cell = field.cell();
    }

    /**
     * The board's tops at the cells' centres, and the layers that span them: the hexes' surfaces, raised to the top of a
     * structure's body (BoardObstacles#structures) where it reaches within half a cell of the centre, so a wall thinner
     * than a cell still counts and the ground between a building and its hex's edge stays ground. The floors take a
     * body's top only where it covers the centre, so an alley that reach fills keeps its ground. Worker thread.
     */
    private static Field field(BoardScene scene) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (BoardScene.Tile tile : scene.tiles()) {
            var centre = BoardGeometry.center(tile.coords(), 0);
            minX = Math.min(minX, centre.x - BoardGeometry.width() / 2);
            maxX = Math.max(maxX, centre.x + BoardGeometry.width() / 2);
            minY = Math.min(minY, centre.y - BoardGeometry.height() / 2);
            maxY = Math.max(maxY, centre.y + BoardGeometry.height() / 2);
        }
        float cell = Math.max(BoardRelief.metres(CELL_METRES), Math.max(maxX - minX, maxY - minY) / MAX_CELLS);
        int width = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
        int height = Math.max(1, (int) Math.ceil((maxY - minY) / cell));
        float[] heights = new float[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                BoardScene.Tile tile = BoardGeometry.tile(scene, minX + (i + .5f) * cell, minY + (j + .5f) * cell);
                heights[j * width + i] = tile == null ? Float.NaN : BoardGeometry.surfaceZ(tile);
            }
        }
        float[] floors = heights.clone();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (tile.cover().structure() == 0) { continue; }
            for (var body : BoardObstacles.structures(scene, tile)) {
                var points = body.triangles();
                for (int n = 0; n < points.size(); n += 3) {
                    Vector3 a = points.get(n), b = points.get(n + 1), c = points.get(n + 2);
                    // The cells whose centres lie within half a cell of the triangle's bounds.
                    int i0 = Math.max(0, (int) Math.ceil((Math.min(a.x, Math.min(b.x, c.x)) - minX) / cell - 1));
                    int i1 = Math.min(width - 1, (int) Math.floor((Math.max(a.x, Math.max(b.x, c.x)) - minX) / cell));
                    int j0 = Math.max(0, (int) Math.ceil((Math.min(a.y, Math.min(b.y, c.y)) - minY) / cell - 1));
                    int j1 = Math.min(height - 1, (int) Math.floor((Math.max(a.y, Math.max(b.y, c.y)) - minY) / cell));
                    for (int j = j0; j <= j1; j++) {
                        for (int i = i0; i <= i1; i++) {
                            int at = j * width + i;
                            float x = minX + (i + .5f) * cell, y = minY + (j + .5f) * cell;
                            // NaN, off the board, stays off it. A floor never stands above its top.
                            if (floors[at] < body.high() && BoardObstacles.Footprint.near(a, b, c, x, y, cell / 2)) {
                                heights[at] = Math.max(heights[at], body.high());
                                if (BoardObstacles.Footprint.near(a, b, c, x, y, 0)) { floors[at] = body.high(); }
                            }
                        }
                    }
                }
            }
        }
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (float z : heights) {
            // NaN, off the board, never wins a comparison.
            if (z < low) { low = z; }
            if (z > high) { high = z; }
        }
        // From a level under the lowest surface to two above the highest, where the whole sky shows.
        float bottom = low - BoardGeometry.level(), top = high + 2 * BoardGeometry.level();
        int layers = MathUtils.clamp((int) Math.ceil((top - bottom) / (BoardGeometry.level() / 2)) + 1, 2, MAX_LAYERS);
        return new Field(width, height, layers, minX + cell / 2, minY + cell / 2, bottom, cell,
              (top - bottom) / (layers - 1), heights, floors);
    }

    /**
     * Each cell's sky visibility towards each quarter of the compass (RGBA: north-east, north-west, south-west,
     * south-east), for the shader to weigh by the quarters a surface faces: a wall must not count the sky that it hides
     * itself. Over its DIRECTIONS / 4 azimuths, a quarter keeps the mean cos² of the horizon's elevation
     * (1 / (1 + tan²)), the share of a uniform sky's light that reaches a level receiver past the relief. A cell inside
     * the ground or a structure searches from the lowest floor around it, its own or a neighbour's: inside a plateau, a
     * plain or a roof (away from its edge) it holds the surface's values, so filtering towards the surface never reads
     * rock; behind a wall's foot it holds the foot's shade rather than the open sky on top; and in an alley the tops'
     * reach fills, its floor's shade rather than the roofs' open sky. Worker thread.
     */
    static byte[] search(Field field) {
        int width = field.width(), height = field.height(), layers = field.layers();
        float[] heights = field.heights(), floors = field.floors();
        float[] lowest = new float[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                float low = floors[j * width + i];
                for (int y = Math.max(0, j - 1); y <= Math.min(height - 1, j + 1); y++) {
                    for (int x = Math.max(0, i - 1); x <= Math.min(width - 1, i + 1); x++) {
                        // NaN, off the board, never wins a comparison.
                        if (floors[y * width + x] < low) { low = floors[y * width + x]; }
                    }
                }
                lowest[j * width + i] = low;
            }
        }
        byte[] values = new byte[width * height * layers * 4];
        float reach = BoardRelief.metres(REACH_METRES) / field.cell();
        float[] cos = new float[DIRECTIONS], sin = new float[DIRECTIONS];
        for (int d = 0; d < DIRECTIONS; d++) {
            // Offset by half a step, so each quarter holds DIRECTIONS / 4 whole azimuths.
            cos[d] = MathUtils.cos((d + .5f) * MathUtils.PI2 / DIRECTIONS);
            sin[d] = MathUtils.sin((d + .5f) * MathUtils.PI2 / DIRECTIONS);
        }
        int perQuarter = DIRECTIONS / 4;
        IntStream.range(0, height * layers).parallel().forEach(row -> {
            int j = row % height, k = row / height;
            for (int i = 0; i < width; i++) {
                float ground = lowest[j * width + i];
                float z = field.z() + k * field.layer();
                if (!Float.isNaN(ground)) { z = Math.max(z, ground); }
                int at = ((k * height + j) * width + i) * 4;
                for (int quarter = 0; quarter < 4; quarter++) {
                    float sum = 0;
                    for (int d = quarter * perQuarter; d < (quarter + 1) * perQuarter; d++) {
                        float steepest = 0;
                        for (float step = 1; step <= reach; step *= STEP_GROWTH) {
                            int x = Math.round(i + cos[d] * step), y = Math.round(j + sin[d] * step);
                            if (x < 0 || y < 0 || x >= width || y >= height) { break; }
                            float rise = heights[y * width + x] - z;
                            if (rise > 0) { steepest = Math.max(steepest, rise / (step * field.cell())); }
                        }
                        sum += 1 / (1 + steepest * steepest);
                    }
                    values[at + quarter] = (byte) Math.round(sum / perQuarter * 255);
                }
            }
        });
        return values;
    }

    private static Texture3D upload(int width, int height, int layers, byte[] values) {
        var data = new CustomTexture3DData(width, height, layers, 0, GL20.GL_RGBA, GL30.GL_RGBA8, GL20.GL_UNSIGNED_BYTE);
        ByteBuffer pixels = data.getPixels();
        pixels.clear();
        pixels.put(values);
        pixels.flip();
        Texture3D result = new Texture3D(data);
        result.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        result.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    /**
     * The volume to bind: the board's, or the neutral one (everything sees the whole sky) until it arrives. Made before
     * any draw binds its textures (update, GpuEnvironmentLighting.texture): creating a texture binds it on the active
     * unit, outside the draws' texture binder.
     */
    Texture3D texture() {
        if (texture == null) { texture = upload(1, 1, 1, new byte[] { (byte) 255, (byte) 255, (byte) 255, (byte) 255 }); }
        return texture;
    }

    /**
     * The define that compiles the sky occlusion into a lit program with the environment's lighting.
     */
    static String prefix(Renderable renderable) {
        return STRENGTH > 0 && renderable.environment != null && renderable.environment.has(GpuEnvironmentLighting.TYPE)
              ? "#define skyVisibilityFlag\n" : "";
    }

    /**
     * The lit programs' inputs (sky-visibility.glsl), from the environment lighting's sky visibility; a program without
     * skyVisibilityFlag has none of them.
     */
    static void register(DefaultShader shader) {
        shader.register("u_skyVisibility", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var sky = of(attributes);
                if (sky != null) { target.set(id, sky.texture()); }
            }
        });
        shader.register("u_skyVisibilityOrigin", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var sky = of(attributes);
                if (sky != null) { target.set(id, sky.originX, sky.originY, sky.originZ); }
            }
        });
        shader.register("u_skyVisibilityScale", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var sky = of(attributes);
                if (sky != null) { target.set(id, 1 / sky.sizeX, 1 / sky.sizeY, 1 / sky.sizeZ, sky.cell); }
            }
        });
        shader.register("u_skyVisibilityStrength", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var sky = of(attributes);
                target.set(id, sky == null ? 0f : sky.strength);
            }
        });
    }

    private static GpuSkyVisibility of(Attributes attributes) {
        var lighting = attributes.get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE);
        return lighting == null ? null : lighting.skyVisibility();
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        tiles = null;
        waiting = null;
        pending = null;
    }
}
