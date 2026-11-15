/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;

/**
 * The ground as one height field per hex, behind {@code -Dmegamek.gpu.terrainField} (off by default; design in
 * work/terrain-field/design.md). The scene revision's inputs are a {@link FieldBoard} (held by GpuTerrain's update plan);
 * each chunk evaluates a {@link ChunkField} on its preparation worker and writes a {@link ChunkMesh} per hex, which the
 * chunk build adds to its solid layer in place of today's sculpted ground ({@code GpuTerrain#collectTile}); a hex's
 * {@link BoardSurface} is an adapter over its block. This class is the GPU side: the vertex layout, the materials, the
 * morph's program text (field-morph.glsl, only in field programs) and its per-draw weights.
 * The field takes dry natural hexes (no water, roads, paving, ramps or bridges, which keep today's geometry).
 */
final class TerrainField {
    static final boolean ON = Boolean.getBoolean("megamek.gpu.terrainField");
    /** A field material's id marker: its meshes are in {@link #LAYOUT}. */
    private static final String FIELD = "~field";
    /**
     * The field's vertex layout: today's sculpted attributes, then the step role and rockiness (four normalized bytes)
     * and the morph's coarse parent (offset x, y, z; packed normal and neighbour). Written by {@link ChunkMesh}.
     */
    static final VertexAttributes LAYOUT = new VertexAttributes(VertexAttribute.Position(), VertexAttribute.ColorPacked(),
          VertexAttribute.Normal(), VertexAttribute.TexCoords(0),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, GL20.GL_UNSIGNED_BYTE, true, "a_fieldRole"),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_fieldParent"));
    /**
     * The binding of the morph's block (FieldMorph, std140, three vec4 a draw). Uniform-block bindings so far: 1 and 2
     * the material cache's page tables (GpuMaterialCache), 3 the compact ground's table (GroundFamilies), 7 this. Part 2
     * folds it into its per-draw block BoardDraw (binding 5), as three of its vec4.
     */
    static final int MORPH_BINDING = 7;
    /** The prefix of a field program: its flag and the field attributes' fixed locations (one numbering, design 5). */
    static final String PREFIX = "#define fieldMorphFlag\n#define FIELD_ROLE_LOCATION 13\n#define FIELD_PARENT_LOCATION 14\n";
    private static final Pattern POSITION = Pattern.compile("\\ba_position\\b"), NORMAL = Pattern.compile("\\ba_normal\\b");

    private TerrainField() { }

    /** {@code material} for chunk {@code chunk}'s field meshes: the same, marked so its draws take the field's layout. */
    static Material material(Material material, int chunk) {
        Material copy = new Material(material);
        copy.id = material.id + FIELD + chunk;
        return copy;
    }

    static boolean isField(Material material) { return material.id.contains(FIELD); }

    /** The chunk a field material draws (its weights' record), or -1. */
    static int chunk(Material material) {
        int at = material.id.indexOf(FIELD);
        if (at < 0) { return -1; }
        int value = 0, i = at + FIELD.length();
        for (; i < material.id.length() && Character.isDigit(material.id.charAt(i)); i++) { value = value * 10 + material.id.charAt(i) - '0'; }
        return value;
    }

    /**
     * A field program's vertex stage: field-morph.glsl before main(), and main() starting from the morphed position
     * and normal (a_position and a_normal read through them). Other programs are not passed here: their text is
     * unchanged.
     */
    static String vertex(String source) {
        int main = source.indexOf("void main()");
        int body = source.indexOf('{', main) + 1;
        String start = "\n\tvec3 fieldPosition = a_position;\n\tfloat fieldWeight = fieldMorphWeight(fieldPosition, FIELD_EYE);\n"
              + "\tfieldPosition += a_fieldParent.xyz * (1.0 - fieldWeight);\n#if defined(normalFlag) && !defined(fieldDepthOnly)\n"
              + "\tvec3 fieldNormal = normalize(mix(fieldParentNormal(uint(a_fieldParent.w)), a_normal, fieldWeight));\n#endif\n";
        String rest = NORMAL.matcher(POSITION.matcher(source.substring(body)).replaceAll("fieldPosition")).replaceAll("fieldNormal");
        return source.substring(0, main) + GpuShaderSource.read("field-morph.glsl") + "\n" + source.substring(main, body)
              + start + rest;
    }

    /** A program's FieldMorph block at its binding, after each link or binary load; false where it has none. */
    static boolean bindBlock(int program) {
        // With the switch off nothing of the field runs, not even this query.
        if (!ON) { return false; }
        int block = GL31C.glGetUniformBlockIndex(program, "FieldMorph");
        if (block == GL31C.GL_INVALID_INDEX) { return false; }
        GL31C.glUniformBlockBinding(program, block, MORPH_BINDING);
        return true;
    }

    /**
     * The field chunks' morph weights for one frame, in one uniform buffer, a record of three vec4 per chunk at offsets
     * aligned to GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, bound per draw by range (Part 2's BoardDraw ring takes this shape).
     * Chunks by key ({@link #key}). GL thread; nothing is allocated per draw.
     */
    static final class Morphs {
        private static final int RECORD = 48;
        private int buffer, stride, capacity, count;
        private ByteBuffer data;
        private float[] weights = new float[0];
        private int[] levels = new int[0];
        private int[] slots = new int[0], keys = new int[64];

        /**
         * This frame's weight of the chunk with key {@code key} at detail {@code level} (TerrainLod#ordinal): 1 its own
         * detail, 0 its parent's.
         */
        void weight(int key, int level, float weight) {
            if (key >= weights.length) {
                int size = Math.max(key + 1, weights.length * 2);
                float[] grown = java.util.Arrays.copyOf(weights, size);
                java.util.Arrays.fill(grown, weights.length, size, Float.NaN);
                weights = grown;
                slots = java.util.Arrays.copyOf(slots, size);
                levels = java.util.Arrays.copyOf(levels, size);
            }
            if (Float.isNaN(weights[key])) {
                if (count == keys.length) { keys = java.util.Arrays.copyOf(keys, count * 2); }
                keys[count++] = key;
            }
            weights[key] = weight;
            levels[key] = level;
        }

        /** Writes every chunk's record (its weight, the eight neighbours' caps) and uploads them; once a frame. */
        void upload() {
            if (count == 0) { return; }
            if (stride == 0) {
                int align = org.lwjgl.opengl.GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
                stride = (RECORD + align - 1) / align * align;
            }
            if (buffer == 0 || count > capacity) {
                if (buffer != 0) { GL15C.glDeleteBuffers(buffer); }
                capacity = Math.max(64, Integer.highestOneBit(count) * 2);
                buffer = GL15C.glGenBuffers();
                data = ByteBuffer.allocateDirect(capacity * stride).order(ByteOrder.nativeOrder());
                GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
                GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, (long) capacity * stride, GL15C.GL_DYNAMIC_DRAW);
            }
            data.clear();
            for (int slot = 0; slot < count; slot++) {
                int key = keys[slot], gx = key / 128, gy = key % 128;
                data.position(slot * stride);
                data.putFloat(weights[key]).putFloat(0).putFloat(0).putFloat(0);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) { continue; }
                        int other = (gx + dx) * 128 + gy + dy;
                        boolean known = gx + dx >= 0 && gy + dy >= 0 && gy + dy < 128 && other < weights.length
                              && !Float.isNaN(weights[other]);
                        // A coarser neighbour's border holds this chunk's at its parent's detail (0), a finer one's
                        // holds it at its own (2, field-morph.glsl), one at this detail shares its weight.
                        data.putFloat(!known ? 1 : levels[other] > levels[key] ? 0 : levels[other] < levels[key] ? 2
                              : weights[other]);
                    }
                }
                slots[key] = slot;
            }
            data.position(0).limit(count * stride);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
            GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0, data);
            // Next frame's weights start empty.
            for (int slot = 0; slot < count; slot++) { weights[keys[slot]] = Float.NaN; }
            count = 0;
        }

        /** Binds the record of the chunk {@code material} draws, before its draw. */
        void bind(Material material) {
            int key = chunk(material);
            if (key < 0 || key >= slots.length || buffer == 0) { return; }
            GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, MORPH_BINDING, buffer, (long) slots[key] * stride, RECORD);
        }

        void dispose() {
            if (buffer != 0) { GL15C.glDeleteBuffers(buffer); buffer = 0; }
        }
    }

    /** A chunk's key: its first hex's column and row in chunks (rows up to 128 chunks). */
    static int key(int column, int row) { return column / TerrainLod.CHUNK_SIZE * 128 + row / TerrainLod.CHUNK_SIZE; }
}
