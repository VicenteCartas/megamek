/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector3;
import jakarta.annotation.Nullable;

/**
 * A meeple's geometry for renderers outside the board: a picture's alpha contour extruded into a thick cutout, as the
 * board's unit tokens are. Plain arrays, so the caller owns its buffers, textures and shading; building it needs no GL
 * context, so any thread may.
 */
public final class GpuStandee {
    /** Floats per vertex: position, normal and texture coordinates. */
    public static final int STRIDE = 8;

    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
          | VertexAttributes.Usage.TextureCoordinates;
    /** The whole picture, texture coordinates 0 to 1 with v down the image. */
    private static final TextureRegion WHOLE = new TextureRegion() {
        @Override
        public float getU() { return 0; }

        @Override
        public float getV() { return 0; }

        @Override
        public float getU2() { return 1; }

        @Override
        public float getV2() { return 1; }
    };

    private GpuStandee() { }

    /** Triangles: {@link #STRIDE} floats per vertex, three indices per triangle. */
    public record Part(float[] vertices, short[] indices) { }

    /**
     * A cutout in the picture's pixels, centred on it, Y up and Z its thickness from 0 to {@code depth}.
     *
     * @param front  the picture's face at Z = depth, facing +Z
     * @param back   its other face at Z = 0, facing -Z, mapped with the same picture (so it reads mirrored)
     * @param walls  the extruded contour's sides
     * @param paint  the picture's alpha-weighted average colour, linear RGB, for the walls
     * @param width  the picture's width in pixels
     * @param height its height in pixels
     * @param depth  the cutout's thickness in pixels
     */
    public record Geometry(Part front, Part back, Part walls, float[] paint, int width, int height, float depth) { }

    /** The cutout of {@code image}, {@code depth} pixels thick; null when the picture has nothing opaque. */
    public static @Nullable Geometry build(BufferedImage image, float depth) {
        var pixels = new BoardScene.Pixels(image);
        var front = new MeshBuilder();
        var walls = new MeshBuilder();
        var back = new MeshBuilder();
        front.begin(ATTRIBUTES, GL20.GL_TRIANGLES);
        walls.begin(ATTRIBUTES, GL20.GL_TRIANGLES);
        back.begin(ATTRIBUTES, GL20.GL_TRIANGLES);
        GpuCutout.extrude(pixels, WHOLE, Vector3.Zero, 1, depth, front, walls, back);
        Color paint = GpuCutout.averageColor(pixels);
        if (front.getNumVertices() == 0) {
            return null;
        }
        return new Geometry(part(front), part(back), part(walls), new float[] { paint.r, paint.g, paint.b },
              pixels.width(), pixels.height(), depth);
    }

    private static Part part(MeshBuilder builder) {
        if (builder.getAttributes().vertexSize / Float.BYTES != STRIDE) {
            throw new IllegalStateException("Unexpected standee vertex layout");
        }
        float[] vertices = new float[builder.getNumVertices() * STRIDE];
        short[] indices = new short[builder.getNumIndices()];
        builder.getVertices(vertices, 0);
        builder.getIndices(indices, 0);
        return new Part(vertices, indices);
    }
}
