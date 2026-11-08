/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * Shared local light: installed lava, burning hexes and luminous fungi, and each frame's fireballs and weapon fire.
 * Every visible emitter is binned on the board plane: each grid cell lists the emitters whose reach overlaps it, so a
 * lit pixel evaluates only those (lava-lighting.glsl). An emitter is a point, or a segment such as a beam.
 */
final class GpuLavaLighting implements Disposable {
    /** Texels per row of the list texture; lava-lighting.glsl addresses it with the same width. */
    private static final int ROW = 1024;
    /** Integers stored as floats stay exact below 2^24, which is also 16384 rows, the common maximum texture height. */
    private static final int MAX_TEXELS = 1 << 24;
    /**
     * An emitter; {@code tile} indexes the scene's tiles for the field-of-view mask, or is negative for an effect. A
     * segment (a beam or a flamer stream) runs from {@code position} to {@code end} as a tube of {@code radius}, in
     * world units, and spreads the light of a point of the same power and size along its length (lava-lighting.glsl);
     * a point has no end.
     */
    record Source(int tile, Vector3 position, float power, Vector3 color, float size, Vector3 end, float radius) {
        Source(int tile, Vector3 position, float power, Vector3 color, float size) {
            this(tile, position, power, color, size, null, 0);
        }
    }
    /** A burning hex's flames: warmer and dimmer than molten lava, reaching about two hexes. */
    private static final Vector3 FIRE = new Vector3(2f, .8f, .22f);
    private final GpuAssets assets; // Borrowed from the same owner as the rendered material.
    private List<BoardScene.Tile> tiles;
    private List<Source> sources = List.of();
    private List<Source> fungus = List.of();
    private List<Source> buildings = List.of();
    private List<Source> nightBuildings = List.of();
    private float nightShare = Float.NaN;
    /** This frame's effect lights, replaced whole by the render thread; shown wherever the effect is drawn. */
    private List<Source> effects = List.of();
    private float width;
    private float level;
    // The binned snapshot: replaced whole on the render thread when the visible emitters change, never mutated.
    private List<Source> binned = List.of();
    /**
     * Whole rows of texels: one per grid cell (first entry texel, end texel), then two per point and four per segment
     * (as in the shader).
     */
    private float[] list = new float[ROW * 4];
    private int columns;
    private int rows;
    private float originX;
    private float originY;
    private float cellsPerUnit;
    private long revision;
    // Owned GL copy of the list, uploaded at the first draw after a change.
    private Texture texture;
    private long uploaded = -1;
    /**
     * The board's hex table (GpuBiomeSurface) in whole rows, one RGBA texel per hex row after row: the texture's last rows,
     * so ground and water read it and the lights through one texture unit, and the list's growth leaves it where it is.
     */
    private float[] hexes = new float[ROW * 4];
    private int hexWidth = 1, hexRow;
    private boolean hexesUploaded;

    GpuLavaLighting(GpuAssets assets) { this.assets = assets; }

    /** A new hex table: per hex, row after row, its RGBA bytes in one int (R highest). */
    void setHexes(int[] colors, int width) {
        float[] table = new float[(colors.length + ROW - 1) / ROW * ROW * 4];
        for (int i = 0; i < colors.length; i++) {
            for (int channel = 0; channel < 4; channel++) {
                table[i * 4 + channel] = (colors[i] >>> (24 - 8 * channel) & 255) / 255f;
            }
        }
        hexes = table;
        hexWidth = width;
        hexesUploaded = false;
    }

    /** The board's width in hexes, for the table's addresses (terrain-hexes.glsl). */
    int hexWidth() { return hexWidth; }

    /** The table's first row (terrain-hexes.glsl); the texture holds it from now on. */
    int hexRow() {
        texture();
        return hexRow;
    }

    /** Chunk installation replaces the whole derived prop-light snapshot together with its rendered meshes. */
    void setFungus(List<Source> sources) { fungus = List.copyOf(sources); }

    /** Installed fixtures, independent of terrain detail mode and LOD. Stable sources keep the current GL upload. */
    void setBuildings(List<Source> sources) {
        if (buildings.equals(sources)) { return; }
        buildings = List.copyOf(sources);
        nightShare = Float.NaN;
    }

    /** Each frame's fireballs and weapon fire (GpuAttackEffects.lights); empty when nothing burns. */
    void setEffects(List<Source> sources) { effects = sources; }

    /** Derive emitters from the installed snapshot; visibility comes from the same client's captured FoV. */
    void update(BoardScene scene) {
        update(scene, true);
    }

    void update(BoardScene scene, boolean showFungus) {
        update(scene, showFungus, 1);
    }

    void update(BoardScene scene, boolean showFungus, float night) {
        if (scene == null) { bin(List.of()); return; }
        night = MathUtils.clamp(night, 0, 1);
        if (nightShare != night) {
            nightShare = night;
            nightBuildings = night == 0 ? List.of() : buildings.stream().map(source -> new Source(source.tile(),
                  source.position(), source.power() * nightShare, source.color(), source.size(), source.end(), source.radius())).toList();
        }
        if (tiles != scene.tiles() || width != BoardGeometry.width() || level != BoardGeometry.level()) {
            tiles = scene.tiles();
            width = BoardGeometry.width();
            level = BoardGeometry.level();
            var next = new ArrayList<Source>();
            for (int i = 0; i < tiles.size(); i++) {
                var tile = tiles.get(i);
                if (tile.fireSmoke().fire() > 0) {
                    var flames = BoardGeometry.center(tile.coords(), tile.elevation());
                    flames.z += BoardRelief.metres(4);
                    next.add(new Source(i, flames, .2f + .08f * tile.fireSmoke().fire(), FIRE, width * .9f));
                }
                if (!tile.liquid().volcanic()) continue;
                var point = BoardGeometry.center(tile.coords(), tile.elevation());
                boolean molten = tile.liquid().molten();
                if (molten) point.z = BoardGeometry.waterZ(tile);
                point.z += BoardRelief.metres(.7f);
                // The same authored source as the surface, averaged once by its asset owner before shore attenuation.
                // The existing disk/falloff remains an area-light approximation; solid spill is unchanged.
                GpuAssets.LavaEmission emission = molten ? assets.lavaEmission() : null;
                if (molten && emission.red() == 0 && emission.green() == 0 && emission.blue() == 0) { continue; }
                Vector3 color = molten ? new Vector3(emission.red(), emission.green(), emission.blue())
                      : new Vector3(1.8f, .24f, .025f);
                next.add(new Source(i, point, molten ? 1f : .035f, color, width));
            }
            sources = List.copyOf(next);
        }
        var visible = new ArrayList<Source>();
        var fov = scene.fieldOfView();
        boolean masked = fov.width() == scene.width() && fov.height() == scene.height() && fov.active();
        for (List<Source> group : List.of(sources, showFungus ? fungus : List.<Source>of(), nightBuildings, effects)) {
            for (Source source : group) {
                if (!masked || source.tile() < 0 || fov.hexes().get(source.tile()).hasLineOfSight()) {
                    visible.add(source);
                }
            }
        }
        bin(visible);
    }

    /** Lists each emitter in every cell its reach overlaps; the same emitters keep the current list and upload. */
    private void bin(List<Source> visible) {
        if (visible.size() == binned.size()) {
            int same = 0;
            while (same < visible.size() && visible.get(same) == binned.get(same)) { same++; }
            if (same == visible.size()) { return; }
        }
        binned = visible;
        revision++;
        if (visible.isEmpty()) {
            columns = 0;
            rows = 0;
            list = new float[ROW * 4];
            return;
        }
        float cell = BoardGeometry.width();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Source source : visible) {
            float reach = reach(source);
            Vector3 end = end(source);
            minX = Math.min(minX, Math.min(source.position().x, end.x) - reach);
            minY = Math.min(minY, Math.min(source.position().y, end.y) - reach);
            maxX = Math.max(maxX, Math.max(source.position().x, end.x) + reach);
            maxY = Math.max(maxY, Math.max(source.position().y, end.y) + reach);
        }
        originX = minX;
        originY = minY;
        cellsPerUnit = 1 / cell;
        columns = (int) ((maxX - minX) / cell) + 1;
        rows = (int) ((maxY - minY) / cell) + 1;
        int cells = columns * rows;
        // Texels per cell first, then each cell's next free entry texel while filling.
        int[] next = new int[cells];
        long texels = 0;
        for (Source source : visible) {
            int size = texels(source);
            texels += (long) size * overlapped(source, cell, at -> next[at] += size);
        }
        // Entries start on an even texel and take whole pairs, so each pair shares a row.
        int start = cells + (cells & 1);
        texels += start;
        // With the hex table's whole rows after it (setHexes).
        if ((texels + ROW - 1) / ROW * ROW + hexes.length / 4 > MAX_TEXELS) {
            throw new IllegalStateException("Local light list and hex table exceed " + MAX_TEXELS + " texels");
        }
        float[] packed = new float[(int) ((texels + ROW - 1) / ROW) * ROW * 4];
        for (int at = 0, first = start; at < cells; at++) {
            int size = next[at];
            packed[at * 4] = first;
            packed[at * 4 + 1] = first + size;
            next[at] = first;
            first += size;
        }
        for (Source source : visible) {
            int size = texels(source);
            overlapped(source, cell, at -> {
                int texel = next[at] * 4;
                next[at] += size;
                packed[texel] = source.position().x;
                packed[texel + 1] = source.position().y;
                packed[texel + 2] = source.position().z;
                // A segment flags itself by a negative power, so a point keeps its two texels and its maths; never -0,
                // which the shader would read as a point.
                packed[texel + 3] = source.end() == null ? source.power()
                      : -Math.max(source.power(), Float.MIN_NORMAL);
                packed[texel + 4] = source.color().x;
                packed[texel + 5] = source.color().y;
                packed[texel + 6] = source.color().z;
                packed[texel + 7] = source.size();
                if (source.end() != null) {
                    packed[texel + 8] = source.end().x;
                    packed[texel + 9] = source.end().y;
                    packed[texel + 10] = source.end().z;
                    packed[texel + 11] = source.radius() / Math.max(source.size(), .001f);
                }
            });
        }
        list = packed;
    }

    /** A point's two texels, or a segment's four: its end and tube in the third, the fourth keeping pairs aligned. */
    private static int texels(Source source) { return source.end() == null ? 2 : 4; }

    private static Vector3 end(Source source) { return source.end() == null ? source.position() : source.end(); }

    /**
     * The source's light reaches exactly zero at twice its size (lava-lighting.glsl's reach term), in 3D and so also
     * on the board plane. The 0.1% margin keeps float rounding in the shader's cell and distance from excluding a
     * pixel the light still touches.
     */
    private static float reach(Source source) { return 2 * Math.max(source.size(), .001f) * 1.001f; }

    /**
     * Visits each cell whose square the source's reach overlaps on the board plane: a disk round a point, a capsule
     * round a segment. Returns the number of cells.
     */
    private int overlapped(Source source, float cell, IntConsumer action) {
        float reach = reach(source), x = source.position().x - originX, y = source.position().y - originY;
        float endX = end(source).x - originX, endY = end(source).y - originY;
        int x0 = Math.max(0, (int) Math.floor((Math.min(x, endX) - reach) / cell));
        int x1 = Math.min(columns - 1, (int) Math.floor((Math.max(x, endX) + reach) / cell));
        int y0 = Math.max(0, (int) Math.floor((Math.min(y, endY) - reach) / cell));
        int y1 = Math.min(rows - 1, (int) Math.floor((Math.max(y, endY) + reach) / cell));
        int count = 0;
        for (int row = y0; row <= y1; row++) {
            for (int column = x0; column <= x1; column++) {
                if (squaredDistance(x, y, endX, endY, column * cell, row * cell, (column + 1) * cell,
                      (row + 1) * cell) <= reach * reach) {
                    action.accept(row * columns + column);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The squared distance from the segment between (x, y) and (endX, endY), a point where they agree, to the
     * rectangle from (left, bottom) to (right, top): zero where the segment crosses it, otherwise the least distance
     * from an end to the rectangle or from a corner to the segment.
     */
    static float squaredDistance(float x, float y, float endX, float endY, float left, float bottom, float right,
          float top) {
        float dx = Math.max(0, Math.max(left - x, x - right));
        float dy = Math.max(0, Math.max(bottom - y, y - top));
        float nearest = dx * dx + dy * dy;
        if (nearest == 0 || (x == endX && y == endY)) { return nearest; }
        // Clip the segment to the rectangle (Liang-Barsky): any part left inside means they cross.
        float alongX = endX - x, alongY = endY - y, enter = 0, exit = 1;
        float[] toward = { -alongX, alongX, -alongY, alongY };
        float[] room = { x - left, right - x, y - bottom, top - y };
        for (int side = 0; side < 4; side++) {
            if (toward[side] == 0) {
                if (room[side] < 0) { exit = -1; }
            } else if (toward[side] < 0) {
                enter = Math.max(enter, room[side] / toward[side]);
            } else {
                exit = Math.min(exit, room[side] / toward[side]);
            }
        }
        if (enter <= exit) { return 0; }
        dx = Math.max(0, Math.max(left - endX, endX - right));
        dy = Math.max(0, Math.max(bottom - endY, endY - top));
        nearest = Math.min(nearest, dx * dx + dy * dy);
        float length2 = alongX * alongX + alongY * alongY;
        for (int corner = 0; corner < 4; corner++) {
            float cornerX = ((corner & 1) == 0 ? left : right) - x, cornerY = (corner < 2 ? bottom : top) - y;
            float t = MathUtils.clamp((cornerX * alongX + cornerY * alongY) / length2, 0, 1);
            float offX = cornerX - t * alongX, offY = cornerY - t * alongY;
            nearest = Math.min(nearest, offX * offX + offY * offY);
        }
        return nearest;
    }

    /**
     * The list texture, uploaded at the first draw after a change. Uploads use texture unit 0, which ModelBatch's
     * texture binder never assigns (its units start at 1), so the draw's tracked sampler bindings stay intact.
     */
    Texture texture() {
        if (texture != null && uploaded == revision && hexesUploaded) { return texture; }
        int height = list.length / (ROW * 4), hexHeight = hexes.length / (ROW * 4);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        if (texture == null || texture.getHeight() < height + hexHeight) {
            if (texture != null) { texture.dispose(); }
            int capacity = 1;
            while (capacity < height + hexHeight) { capacity <<= 1; }
            texture = new Texture(new GLOnlyTextureData(ROW, capacity, 0, GL30.GL_RGBA32F, GL20.GL_RGBA, GL20.GL_FLOAT));
            uploaded = -1;
            hexesUploaded = false;
        }
        texture.bind();
        if (uploaded != revision) {
            upload(list, 0, height);
            uploaded = revision;
        }
        if (!hexesUploaded) {
            hexRow = texture.getHeight() - hexHeight;
            upload(hexes, hexRow, hexHeight);
            hexesUploaded = true;
        }
        return texture;
    }

    private static void upload(float[] rows, int first, int count) {
        var pixels = BufferUtils.newFloatBuffer(rows.length);
        pixels.put(rows).flip();
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, first, ROW, count, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
    }

    /** {@code tables}: the lights whose texture holds the hex table a draw without lighting still reads; or null. */
    static void register(DefaultShader shader, GpuLavaLighting tables) {
        int grid = shader.register("u_lavaGrid");
        shader.register("u_lavaCells", new BaseShader.LocalSetter() {
            private GpuLavaLighting previous;
            private long uploaded = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = GpuEnvironmentLighting.lavaLighting(attributes);
                if (field == null) { target.set(id, 0, 0); previous = null; return; }
                if (field == previous && uploaded == field.revision) return;
                previous = field;
                uploaded = field.revision;
                target.set(id, field.columns, field.rows);
                target.set(grid, field.originX, field.originY, field.cellsPerUnit);
            }
        });
        shader.register("u_lavaLights", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = GpuEnvironmentLighting.lavaLighting(attributes);
                if (field == null) { field = tables; }
                // Without the lights nothing is fetched, but a stale unit may now hold another sampler type and
                // invalidate the draw. Unit 0 is outside ModelBatch's binder.
                if (field != null) { target.set(id, field.texture()); } else { target.set(id, 0); }
            }
        });
        // After u_lavaLights' setter, as local setters run in registration order: the table's row is that of the texture
        // the draw just bound, even when this draw's upload grew it.
        shader.register("u_hexTable", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = GpuEnvironmentLighting.lavaLighting(attributes);
                if (field == null) { field = tables; }
                if (field != null) { target.set(id, field.hexRow(), field.hexWidth()); }
            }
        });
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        uploaded = -1;
        hexesUploaded = false;
    }
}
