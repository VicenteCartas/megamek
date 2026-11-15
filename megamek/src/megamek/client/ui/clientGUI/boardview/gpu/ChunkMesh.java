/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;

/**
 * A height-field hex's ground mesh ({@link TerrainField}), written on the chunk's preparation worker from its
 * {@link ChunkField.Block}: interleaved vertices in {@link TerrainField#LAYOUT}'s order (position 3, packed data 1,
 * normal 3, uv 2, field role 1, field parent 4) and 16-bit triangle indices, in two groups: the merged level tops,
 * which the ground material cache can draw, and everything else (sloping cover, step faces, walls), drawn directly.
 * The vertex data are today's sculpted contract ({@link GpuTerrain#shadeColor}): a sample in both a top and a step
 * face is written once for each.
 */
final class ChunkMesh {
    /** Floats a vertex. */
    static final int FLOATS = 14;

    private ChunkMesh() { }

    /** {@code bounds}: min x, y, z, max x, y, z of every vertex. */
    record Hex(float[] direct, short[] directIndices, float[] levelTops, short[] levelTopIndices, float[] bounds) { }

    static Hex of(ChunkField.Block block) {
        Writer direct = new Writer(block), tops = new Writer(block);
        direct.triangles(block.ground(), false);
        direct.triangles(block.faces(), true);
        direct.triangles(block.walls(), true);
        tops.triangles(block.levelTops(), false);
        return new Hex(direct.vertices(), direct.indices(), tops.vertices(), tops.indices(), block.bounds());
    }

    /** One group's vertices, each sample written once a kind (top cover or step face). */
    private static final class Writer {
        final ChunkField.Block block;
        final float[] parents;
        final int[] sides;
        final int[] cover, face;
        float[] vertices = new float[FLOATS * 64];
        short[] indices = new short[192];
        int count, indexCount;

        Writer(ChunkField.Block block) {
            this.block = block;
            parents = block.parents();
            sides = block.sides();
            cover = new int[block.size()];
            face = new int[block.size()];
            Arrays.fill(cover, -1);
            Arrays.fill(face, -1);
        }

        void triangles(int[] list, boolean faces) {
            for (int i = 0; i < list.length; i++) {
                if (indexCount == indices.length) { indices = Arrays.copyOf(indices, indices.length * 2); }
                indices[indexCount++] = (short) vertex(list[i], faces);
            }
        }

        private int vertex(int v, boolean faces) {
            int[] written = faces ? face : cover;
            if (written[v] >= 0) { return written[v]; }
            if ((count + 1) * FLOATS > vertices.length) { vertices = Arrays.copyOf(vertices, vertices.length * 2); }
            int o = count * FLOATS;
            vertices[o] = block.x()[v];
            vertices[o + 1] = block.y()[v];
            vertices[o + 2] = block.z()[v];
            float u, w;
            if (faces) {
                vertices[o + 3] = GpuTerrain.packedShade(BoardRelief.Kind.CLIFF, block.faceOcclusion(v), block.rock()[v], block.bed(v),
                      block.projection()[v]);
                u = block.faceRim()[v];
                w = block.faceFoot()[v];
            } else {
                vertices[o + 3] = GpuTerrain.packedShade(BoardRelief.Kind.GROUND, block.occlusion(v), block.level(),
                      block.coverTint(v), Float.NaN);
                u = block.rimMetres(v);
                w = block.footMetres(v);
            }
            vertices[o + 4] = block.nx()[v];
            vertices[o + 5] = block.ny()[v];
            vertices[o + 6] = block.nz()[v];
            vertices[o + 7] = u;
            vertices[o + 8] = w;
            // a_fieldRole: step role and rockiness as normalized bytes (the high bytes stay zero: never a NaN pattern).
            int role = Math.round(Math.clamp(faces ? 1 : block.role()[v], 0, 1) * 255);
            int rock = Math.round(Math.clamp(block.rock()[v], 0, 1) * 255);
            vertices[o + 9] = Float.intBitsToFloat(role | rock << 8);
            // a_fieldParent: the coarse parent's offset and normal, octahedral (a sample that is its own parent: no
            // offset, its own normal), and the neighbouring chunk the sample borders.
            int p = v * 6;
            boolean own = Float.isNaN(parents[p]);
            vertices[o + 10] = own ? 0 : parents[p];
            vertices[o + 11] = own ? 0 : parents[p + 1];
            vertices[o + 12] = own ? 0 : parents[p + 2];
            vertices[o + 13] = (own ? octahedral(block.nx()[v], block.ny()[v], block.nz()[v])
                  : octahedral(parents[p + 3], parents[p + 4], parents[p + 5])) + (sides[v] << 20);
            written[v] = count;
            return count++;
        }

        /** A unit normal as two 10-bit octahedral coordinates in one exact integer (field-morph.glsl decodes it). */
        private static int octahedral(float x, float y, float z) {
            float sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
            float u = x / sum, v = y / sum;
            if (z < 0) {
                float ou = (1 - Math.abs(v)) * Math.signum(u == 0 ? 1 : u), ov = (1 - Math.abs(u)) * Math.signum(v == 0 ? 1 : v);
                u = ou;
                v = ov;
            }
            int a = Math.round((u * .5f + .5f) * 1023), b = Math.round((v * .5f + .5f) * 1023);
            return a | b << 10;
        }

        float[] vertices() { return Arrays.copyOf(vertices, count * FLOATS); }

        short[] indices() { return Arrays.copyOf(indices, indexCount); }
    }
}
