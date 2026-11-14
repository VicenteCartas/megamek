/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;

/**
 * Instanced mesh that can draw a prefix of its uploaded instances, or runs of instances that place themselves (ground
 * cover). Instance attributes must be disabled before unbinding the VAO in a core GL context. It also names what moves a
 * falling piece in its record of the GPU-driven path (#piece, GpuIndirectInstances).
 */
final class GpuInstancedMesh extends Mesh {
    // Instances to draw, or -1 for all uploaded ones.
    int drawInstances = -1;
    /**
     * Runs of instances of a mesh that uploads none: each instance places itself by its index in its run
     * (gl_InstanceID), as ground cover's blades do (terrain-grass.glsl). Per run, the three floats of the vertex stage's
     * uniform named here, and its instances. Null for every other mesh.
     */
    private String runUniform;
    private FloatArray runValues;
    private IntArray runInstances;
    /**
     * What moves an instance of the GPU-driven path (tree-instances.glsl), the last of its piece's floats ({@link #piece}):
     * a kit's falling piece (GpuDestruction) turns about the centre of its node's box, rubble rises, a tree falls or
     * stands.
     */
    static final float PIECES = 1, FELLED = 2, STANDING = 3, RISES = 4;
    /** A piece's userData that makes it rubble rising out of the ground under its hex's pile ({@link #RISES}). */
    static final Object RUBBLE = new Object();
    /** The floats of {@link #piece} before a broken part's fragments' table (KitFragments). */
    static final int PIECE = 7;
    /**
     * A falling part's fragments ({@link KitFragments}), carried in its renderable's userData: per fragment its box's
     * centre and its first vertex in the pools' copy of the part, then its half size and its turn about the vertical,
     * in radians.
     */
    record Fragments(float[] table) { }

    /**
     * A falling piece's floats: its node's box in the model's frame (centre, half size), {@link #PIECES} or
     * {@link #RISES}, then its fragments' table where it broke.
     */
    static float[] piece(com.badlogic.gdx.graphics.g3d.Renderable sample) {
        var part = sample.meshPart;
        float[] table = sample.userData instanceof Fragments fragments ? fragments.table() : new float[0];
        float[] result = new float[PIECE + table.length];
        result[0] = part.center.x;
        result[1] = part.center.y;
        result[2] = part.center.z;
        result[3] = part.halfExtents.x;
        result[4] = part.halfExtents.y;
        result[5] = part.halfExtents.z;
        result[6] = sample.userData == RUBBLE ? RISES : PIECES;
        System.arraycopy(table, 0, result, PIECE, table.length);
        return result;
    }

    GpuInstancedMesh(int vertices, int indices, VertexAttributes attributes) {
        super(true, vertices, indices, attributes);
    }

    GpuInstancedMesh(Mesh source) {
        this(source.getNumVertices(), source.getNumIndices(), source.getVertexAttributes());
        float[] vertexData = new float[source.getNumVertices() * source.getVertexSize() / Float.BYTES];
        source.getVertices(vertexData);
        setVertices(vertexData);
        short[] indexData = new short[source.getNumIndices()];
        source.getIndices(indexData);
        setIndices(indexData);
    }

    /** Draw these runs of self-placed instances: {@code values} holds three floats a run for {@code uniform}. */
    void drawRuns(String uniform, FloatArray values, IntArray instances) {
        runUniform = uniform;
        runValues = values;
        runInstances = instances;
    }

    @Override
    public void render(ShaderProgram shader, int primitiveType, int offset, int count, boolean autoBind) {
        if (runInstances != null) {
            if (count == 0 || runInstances.size == 0) { return; }
            if (autoBind) { bind(shader); }
            int location = shader.fetchUniformLocation(runUniform, false);
            for (int run = 0; run < runInstances.size; run++) {
                Gdx.gl.glUniform3f(location, runValues.items[run * 3], runValues.items[run * 3 + 1], runValues.items[run * 3 + 2]);
                Gdx.gl30.glDrawElementsInstanced(primitiveType, count, GL20.GL_UNSIGNED_SHORT, offset * 2,
                      runInstances.items[run]);
            }
            if (autoBind) { unbind(shader); }
            return;
        }
        if (drawInstances < 0 || !isInstanced) {
            super.render(shader, primitiveType, offset, count, autoBind);
            return;
        }
        if (count == 0 || drawInstances == 0) { return; }
        if (autoBind) { bind(shader); }
        Gdx.gl30.glDrawElementsInstanced(primitiveType, count, GL20.GL_UNSIGNED_SHORT, offset * 2,
              Math.min(drawInstances, instances.getNumInstances()));
        if (autoBind) { unbind(shader); }
    }

    @Override
    public void unbind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
        // libGDX 1.14.2 unbinds the VAO first, then disables instance attributes with no VAO bound.
        if (instances != null && instances.getNumInstances() > 0) { instances.unbind(shader, instanceLocations); }
        vertices.unbind(shader, locations);
        if (indices.getNumIndices() > 0) { indices.unbind(); }
    }
}
