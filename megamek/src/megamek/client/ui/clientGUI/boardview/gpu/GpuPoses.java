/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * The poses of pieces moved on the CPU, one row each, which the instanced vertex stages apply (tree-instances.glsl,
 * poseRow): a texture of its own, RGBA32F, {@link #ROW} texels wide and {@link #ROWS} high, two texels a piece. The
 * first holds the piece's shift from where it stood and its scale, the second its turn as a quaternion, both about its
 * instance's origin: a piece at rest (no shift, scale 1, no turn) stands as it was placed. A row with a negative scale
 * poses nothing: its piece still moves in closed form, and the row names its fall's slot + 1 ({@link #analytic}).
 * {@link GpuDestruction} writes the rows of the falls whose pieces land as bodies.
 * <p>
 * Below the rows, {@link #TABLE_ROWS} more hold the fragment tables of the broken parts the fall lists draw
 * ({@link #table}): two texels a fragment, its box's centre and first vertex, then its half size and turn (KitFragments),
 * placed once per table while a list draws it, so a part may break into as many fragments as its cut makes.
 * <p>
 * The texture is replaced whole in a frame that changed a row, from one buffer kept for its life, and stays bound on
 * {@link #UNIT}, a unit only the vertex stage reads and no other texture takes. Its size never changes, so nothing is
 * allocated while pieces move. Render thread only.
 */
final class GpuPoses implements Disposable {
    /** Texels a row, rows, and the pieces they hold. */
    static final int ROW = 1024, ROWS = 32, PIECES = ROW * ROWS / 2;
    /** Rows of fragment tables below the pose rows, and the fragments they hold. */
    static final int TABLE_ROWS = 32, TABLE_FRAGMENTS = ROW * TABLE_ROWS / 2;
    /**
     * The texture unit the rows stay bound on: past those ModelBatch's binder hands out (1 to 31) and unit 0, which
     * uploads use. OpenGL 3.3 has at least 48 units, 16 of them for the samplers of the vertex stage alone.
     */
    static final int UNIT = 32;
    private static final int FLOATS = 8;

    private final FloatBuffer rows = BufferUtils.newFloatBuffer(ROW * (ROWS + TABLE_ROWS) * 4);
    private int texture, uploads;
    private boolean changed = true;

    /** A table placed among the table rows: its first texel there and how many pieces' records draw it. */
    private static final class Placed {
        final int first, texels;
        int users;

        Placed(int first, int texels) {
            this.first = first;
            this.texels = texels;
        }
    }

    /** The tables placed, by the array that holds them (GpuInstancedMesh.Fragments#table), in no order. */
    private final java.util.Map<float[], Placed> tables = new java.util.IdentityHashMap<>();
    /** The pose rows given out ({@link #take}), a bit a row. */
    private final java.util.BitSet used = new java.util.BitSet(PIECES);

    /** Takes the first run of {@code count} free pose rows and returns its first row; -1 where no such run is left. */
    int take(int count) {
        for (int first = used.nextClearBit(0); count > 0 && first + count <= PIECES; ) {
            int end = used.nextSetBit(first);
            if (end < 0 || end - first >= count) {
                used.set(first, first + count);
                return first;
            }
            first = used.nextClearBit(end);
        }
        return -1;
    }

    /** Gives back {@code count} rows from {@code first} ({@link #take}). */
    void free(int first, int count) { used.clear(first, first + count); }

    /** The pose rows given out, for tests and reports. */
    int taken() { return used.cardinality(); }

    /** Every piece at rest. */
    GpuPoses() {
        for (int piece = 0; piece < PIECES; piece++) {
            rows.put(piece * FLOATS + 3, 1);
            rows.put(piece * FLOATS + 7, 1);
        }
    }

    /**
     * Poses a piece: moved by ({@code x}, {@code y}, {@code z}) from where it stood, scaled by {@code scale} and turned by
     * the unit quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}), about its instance's origin.
     */
    void set(int piece, float x, float y, float z, float scale, float qx, float qy, float qz, float qw) {
        int at = piece * FLOATS;
        rows.put(at, x).put(at + 1, y).put(at + 2, z).put(at + 3, scale);
        rows.put(at + 4, qx).put(at + 5, qy).put(at + 6, qz).put(at + 7, qw);
        changed = true;
    }

    /** A piece still moved in closed form by the fall of {@code slot} (GpuDestruction): its row poses nothing. */
    void analytic(int piece, int slot) { set(piece, slot + 1, 0, 0, -1, 0, 0, 0, 1); }

    /** A piece's row as written: its shift and scale, then its turn. */
    float[] get(int piece) {
        float[] row = new float[FLOATS];
        for (int index = 0; index < FLOATS; index++) { row[index] = rows.get(piece * FLOATS + index); }
        return row;
    }

    /**
     * Places a fragment table among the table rows for one more record that draws it, and returns its first texel there,
     * counted from the first table row; -1 where no run of free texels holds it: its part then falls whole.
     */
    int table(float[] table) {
        Placed placed = tables.get(table);
        if (placed == null) {
            int texels = table.length / 4, first = 0;
            List<Placed> runs = new java.util.ArrayList<>(tables.values());
            runs.sort(java.util.Comparator.comparingInt(run -> run.first));
            for (Placed run : runs) {
                if (run.first - first >= texels) { break; }
                first = run.first + run.texels;
            }
            if (first + texels > ROW * TABLE_ROWS) { return -1; }
            placed = new Placed(first, texels);
            tables.put(table, placed);
            rows.position(ROW * ROWS * 4 + first * 4);
            rows.put(table, 0, table.length);
            rows.position(0);
            changed = true;
        }
        placed.users++;
        return placed.first;
    }

    /** A record no longer draws the table: placed for none, its texels are free again. */
    void release(float[] table) {
        Placed placed = tables.get(table);
        if (placed != null && --placed.users <= 0) { tables.remove(table); }
    }

    /** The table texels placed, for tests and reports. */
    int tableTexels() {
        int texels = 0;
        for (Placed placed : tables.values()) { texels += placed.texels; }
        return texels;
    }

    /** How often the rows were uploaded. */
    int uploads() { return uploads; }

    /**
     * Before the frame's first pass that draws posed pieces: uploads the rows if any changed since the last call, the
     * whole texture at once, which lets the driver replace its storage instead of waiting for the draws that read it.
     */
    void bind() {
        if (texture != 0 && !changed) { return; }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + UNIT);
        if (texture == 0) {
            texture = Gdx.gl.glGenTexture();
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
        }
        rows.position(0).limit(rows.capacity());
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, ROW, ROWS + TABLE_ROWS, 0, GL20.GL_RGBA, GL20.GL_FLOAT, rows);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        changed = false;
        uploads++;
    }

    @Override
    public void dispose() {
        if (texture == 0) { return; }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + UNIT);
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Gdx.gl.glDeleteTexture(texture);
        texture = 0;
        changed = true;
    }
}
