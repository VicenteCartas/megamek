/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.IntArray;

/**
 * Instanced mesh that can draw part of its uploaded instances: a prefix, or several ranges. Ground cover and trees
 * keep a whole chunk's, or the whole board's, instances uploaded and select per frame, so changing density or view
 * never uploads again. Instance attributes must be disabled before unbinding the VAO in a core GL context.
 */
final class GpuInstancedMesh extends Mesh {
    // Instances to draw, or -1 for all uploaded ones; ignored while ranges are set.
    int drawInstances = -1;
    // (first instance, count) pairs to draw instead, with one bind for all of them.
    private IntArray ranges;
    /**
     * Every instance is a mirror image, whose reflection reverses the winding of its triangles: draws take the
     * clockwise winding as the front, so each pass's culling keeps the faces it keeps for the model unmirrored.
     */
    boolean mirrored;
    /**
     * What moves the batch's instances (tree-instances.glsl): for a kit's falling pieces (GpuDestruction) the centre and
     * half size of their node's box in the model's frame, about which each turns, then {@link #PIECES}; for trees zeros,
     * then {@link #FELLED} or {@link #STANDING}. Null for every other batch, which nothing moves.
     */
    float[] piece;
    static final float PIECES = 1, FELLED = 2, STANDING = 3;

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

    /** Draw only these (first instance, count) ranges of the uploaded instances. */
    void drawRanges(IntArray ranges) { this.ranges = ranges; }

    /** Back to drawing every uploaded instance, or the prefix in {@link #drawInstances}. */
    void clearRanges() { ranges = null; }

    @Override
    public void render(ShaderProgram shader, int primitiveType, int offset, int count, boolean autoBind) {
        if (!mirrored) {
            draw(shader, primitiveType, offset, count, autoBind);
            return;
        }
        Gdx.gl.glFrontFace(GL20.GL_CW);
        try {
            draw(shader, primitiveType, offset, count, autoBind);
        } finally { Gdx.gl.glFrontFace(GL20.GL_CCW); }
    }

    private void draw(ShaderProgram shader, int primitiveType, int offset, int count, boolean autoBind) {
        if (ranges == null && (drawInstances < 0 || !isInstanced)) {
            super.render(shader, primitiveType, offset, count, autoBind);
            return;
        }
        if (count == 0 || ranges == null && drawInstances == 0) { return; }
        if (autoBind) { bind(shader); }
        if (ranges != null) {
            // The bind left the instance buffer bound and its attributes pointed at instance 0. Pointing them at
            // each range's first instance draws that range alone, with plain instanced draws on any OpenGL 3.3.
            VertexAttributes attributes = instances.getAttributes();
            int stride = attributes.vertexSize;
            int[] locations = new int[attributes.size()];
            for (int i = 0; i < locations.length; i++) { locations[i] = shader.getAttributeLocation(attributes.get(i).alias); }
            for (int r = 0; r < ranges.size; r += 2) {
                if (ranges.items[r + 1] <= 0) { continue; }
                point(attributes, locations, ranges.items[r] * stride);
                Gdx.gl30.glDrawElementsInstanced(primitiveType, count, GL20.GL_UNSIGNED_SHORT, offset * 2, ranges.items[r + 1]);
            }
            point(attributes, locations, 0);
        } else {
            Gdx.gl30.glDrawElementsInstanced(primitiveType, count, GL20.GL_UNSIGNED_SHORT, offset * 2,
                  Math.min(drawInstances, instances.getNumInstances()));
        }
        if (autoBind) { unbind(shader); }
    }

    private static void point(VertexAttributes attributes, int[] locations, int bytes) {
        for (int i = 0; i < locations.length; i++) {
            if (locations[i] < 0) { continue; }
            VertexAttribute attribute = attributes.get(i);
            Gdx.gl30.glVertexAttribPointer(locations[i], attribute.numComponents, attribute.type, attribute.normalized,
                  attributes.vertexSize, attribute.offset + bytes);
        }
    }

    @Override
    public void unbind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
        // libGDX 1.14.2 unbinds the VAO first, then disables instance attributes with no VAO bound.
        if (instances != null && instances.getNumInstances() > 0) { instances.unbind(shader, instanceLocations); }
        vertices.unbind(shader, locations);
        if (indices.getNumIndices() > 0) { indices.unbind(); }
    }
}
