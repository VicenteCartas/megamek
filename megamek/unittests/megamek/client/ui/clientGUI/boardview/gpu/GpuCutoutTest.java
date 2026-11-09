/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import org.junit.jupiter.api.Test;

/** Pigment, closed silhouette geometry and lighting normals shared by tokens and raised features. */
class GpuCutoutTest {
    @Test
    void sideColorAveragesLinearLight() {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff008080);
        image.setRGB(1, 0, 0xffff8080);
        Color color = GpuCutout.averageColor(new BoardScene.Pixels(image));
        assertEquals(.5f, color.r, .000001f, "Black and white contribute equal linear radiance");
        assertEquals(.2158605f, color.g, .000001f, "Authored gray decodes before the weighted average");
        assertEquals(.2158605f, color.b, .000001f);
        assertEquals(1, color.a);
    }

    @Test
    void sideColorIgnoresTransparentPixelsAndWeightsPartialAlpha() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0x800000ff);
        image.setRGB(2, 0, 0x0000ff00);
        Color color = GpuCutout.averageColor(new BoardScene.Pixels(image));
        assertEquals(255f / 383, color.r, 0.0001f);
        assertEquals(0, color.g);
        assertEquals(128f / 383, color.b, 0.0001f);
        assertEquals(1, color.a);
    }

    @Test
    void emptySpriteHasFiniteOpaqueSideColor() {
        assertEquals(Color.BLACK, GpuCutout.averageColor(new BoardScene.Pixels(
              new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))));
    }

    @Test
    void pixelStairsBecomePanelsWithOutwardNormalsAndMatchingCaps() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 4; y < 60; y++) {
            for (int x = 8 + y / 2; x < 60; x++) { image.setRGB(x, y, 0xff338844); }
        }
        var mesh = extrude(image, true);
        assertTrue(mesh.sides().getNumVertices() < 80, "A diagonal raster edge must not produce hundreds of walls");
        checkNormals(mesh.caps());
        checkNormals(mesh.sides());
        checkCapArea(mesh);
        checkUvs(mesh.caps(), image);
        assertTrue(hit(mesh.caps(), 0, 0, 100, 0, 0, -1));
        assertTrue(hit(mesh.sides(), 100, 0, 16, -1, 0, 0));
    }

    @Test
    void capsPreserveHolesAndDisconnectedIslands() {
        var image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 22; y++) {
            for (int x = 2; x < 22; x++) {
                if (x < 8 || x >= 16 || y < 8 || y >= 16) { image.setRGB(x, y, 0xffffffff); }
            }
        }
        for (int y = 6; y < 18; y++) {
            for (int x = 26; x < 30; x++) { image.setRGB(x, y, 0xffffffff); }
        }
        var mesh = extrude(image, true);
        assertFalse(hit(mesh.caps(), -4, 0, 100, 0, 0, -1), "Hole is open through both caps");
        assertFalse(hit(mesh.caps(), 8, 0, 100, 0, 0, -1), "Islands are not bridged");
        assertTrue(hit(mesh.caps(), -12, 0, 100, 0, 0, -1));
        assertTrue(hit(mesh.caps(), 12, 0, 100, 0, 0, -1));
        assertTrue(hit(mesh.sides(), -4, 0, 16, 1, 0, 0), "Hole walls remain pickable");
        checkNormals(mesh.caps());
        checkNormals(mesh.sides());
        checkCapArea(mesh);
    }

    @Test
    void roundedWallsShareNormalsButCapAndPanelCreasesStaySharp() {
        var circle = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float coverage = Math.max(0, Math.min(1, 25.5f - (float) Math.hypot(x - 31.5f, y - 31.5f)));
                circle.setRGB(x, y, Math.round(coverage * 255) << 24 | 0x338844);
            }
        }
        var mesh = extrude(circle, true);
        float[] sides = vertices(mesh.sides());
        int count = mesh.sides().getNumVertices();
        for (int i = 0; i < count; i += 4) {
            // A wall starts at its end point; the following wall ends at that same contour point.
            int next = (i + 5) % count;
            assertTrue(vector(sides, i, 0).epsilonEquals(vector(sides, next, 0), .00001f));
            assertTrue(vector(sides, i, 3).epsilonEquals(vector(sides, next, 3), .00001f),
                  "Smooth contours have continuous lighting across panel boundaries");
            assertEquals(0, vector(sides, i, 3).z, .00001f, "Walls never average their normals with the caps");
        }
        checkNormals(mesh.caps());
        checkNormals(mesh.sides());
        checkCapArea(mesh);

        var square = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) { square.setRGB(x, y, 0xffffffff); }
        }
        var squareMesh = extrude(square, false);
        float[] squareSides = vertices(squareMesh.sides());
        boolean crease = false;
        int squareCount = squareMesh.sides().getNumVertices();
        for (int i = 0; i < squareCount; i += 4) {
            crease |= vector(squareSides, i, 3).dot(vector(squareSides, (i + 5) % squareCount, 3)) < .5f;
        }
        assertTrue(crease, "Structural corners retain distinct normals");
        float[] caps = vertices(squareMesh.caps());
        for (int i = 0; i < squareMesh.caps().getNumVertices(); i++) { assertEquals(32, vector(caps, i, 0).z); }
    }

    @Test
    void alphaThresholdBorderPixelsAndDiagonalSaddlesRemainClosed() {
        // All marching-square cases, including exact 128 alpha, border contact, diagonal islands and empty art.
        for (int alpha : new int[] { 128, 255 }) {
            for (int mask = 0; mask < 16; mask++) {
                var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < 4; i++) {
                    if ((mask & (1 << i)) != 0) { image.setRGB(i % 2, i / 2, alpha << 24 | 0xffffff); }
                }
                var mesh = extrude(image, true);
                assertEquals(mask == 0, mesh.sides().getNumVertices() == 0);
                checkNormals(mesh.caps());
                checkNormals(mesh.sides());
                checkCapArea(mesh);
            }
        }
    }

    @Test
    void narrowAntialiasedFeaturesKeepTheirCapCoverage() {
        var random = new Random(3107);
        int[] alpha = { 0, 127, 128, 255 };
        for (int sample = 0; sample < 64; sample++) {
            var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    image.setRGB(x, y, alpha[random.nextInt(alpha.length)] << 24 | 0xffffff);
                }
            }
            var mesh = extrude(image, true);
            checkNormals(mesh.caps());
            checkNormals(mesh.sides());
            checkCapArea(mesh);
        }
    }

    private record Extrusion(MeshBuilder caps, MeshBuilder sides) { }

    /** With {@code bottom}, the artwork shows underneath as well; else the bottom cap goes elsewhere, as a meeple paints it. */
    private static Extrusion extrude(BufferedImage image, boolean bottom) {
        var caps = new MeshBuilder();
        var sides = new MeshBuilder();
        var under = new MeshBuilder();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates;
        caps.begin(attributes, GL20.GL_TRIANGLES);
        sides.begin(attributes, GL20.GL_TRIANGLES);
        under.begin(attributes, GL20.GL_TRIANGLES);
        var region = mock(TextureRegion.class);
        when(region.getU()).thenReturn(.2f);
        when(region.getU2()).thenReturn(.6f);
        when(region.getV()).thenReturn(.3f);
        when(region.getV2()).thenReturn(.9f);
        int count = GpuCutout.extrude(new BoardScene.Pixels(image), region, Vector3.Zero, 1, 32, caps, sides,
              bottom ? caps : under);
        assertEquals(caps.getNumVertices() + sides.getNumVertices() + under.getNumVertices(), count);
        return new Extrusion(caps, sides);
    }

    private static float[] vertices(MeshBuilder mesh) {
        var vertices = new float[mesh.getNumVertices() * 8];
        mesh.getVertices(vertices, 0);
        return vertices;
    }

    private static short[] indices(MeshBuilder mesh) {
        var indices = new short[mesh.getNumIndices()];
        mesh.getIndices(indices, 0);
        return indices;
    }

    private static Vector3 vector(float[] vertices, int index, int offset) {
        int start = index * 8 + offset;
        return new Vector3(vertices[start], vertices[start + 1], vertices[start + 2]);
    }

    private static void checkNormals(MeshBuilder mesh) {
        float[] vertices = vertices(mesh);
        short[] indices = indices(mesh);
        for (int i = 0; i < indices.length; i += 3) {
            var a = vector(vertices, Short.toUnsignedInt(indices[i]), 0);
            var b = vector(vertices, Short.toUnsignedInt(indices[i + 1]), 0);
            var c = vector(vertices, Short.toUnsignedInt(indices[i + 2]), 0);
            var geometric = b.sub(a).crs(c.sub(a)).nor();
            assertTrue(geometric.len2() > .99f, "No degenerate triangles");
            for (int corner = 0; corner < 3; corner++) {
                var normal = vector(vertices, Short.toUnsignedInt(indices[i + corner]), 3);
                assertEquals(1, normal.len2(), .00001f);
                assertTrue(geometric.dot(normal) > .5f, "Winding agrees with the lighting normal");
            }
        }
    }

    private static void checkCapArea(Extrusion mesh) {
        float[] sides = vertices(mesh.sides()), caps = vertices(mesh.caps());
        double outlineArea = 0, capArea = 0;
        for (int i = 0; i < mesh.sides().getNumVertices(); i += 4) {
            Vector3 a = vector(sides, i, 0), b = vector(sides, i + 1, 0);
            outlineArea += a.x * b.y - b.x * a.y;
        }
        short[] indices = indices(mesh.caps());
        for (int i = 0; i < indices.length; i += 3) {
            Vector3 a = vector(caps, Short.toUnsignedInt(indices[i]), 0);
            if (a.z == 0) { continue; }
            Vector3 b = vector(caps, Short.toUnsignedInt(indices[i + 1]), 0);
            Vector3 c = vector(caps, Short.toUnsignedInt(indices[i + 2]), 0);
            capArea += b.sub(a).crs(c.sub(a)).z;
        }
        assertEquals(outlineArea, capArea, .001, "Caps cover exactly the wall outline, subtracting holes");
    }

    private static void checkUvs(MeshBuilder mesh, BufferedImage image) {
        float[] vertices = vertices(mesh);
        for (int i = 0; i < mesh.getNumVertices(); i++) {
            Vector3 position = vector(vertices, i, 0);
            assertEquals(.2f + .4f * (position.x / image.getWidth() + .5f), vertices[i * 8 + 6], .00001f);
            assertEquals(.3f + .6f * (.5f - position.y / image.getHeight()), vertices[i * 8 + 7], .00001f);
        }
    }

    /**
     * A cut through rubble: what closed outlines wrap fills once even where they overlap, and a chain that never
     * closes, the cut through a shell open at one side, closes across its chord.
     */
    @Test
    void overlappingOutlinesFillTheirUnionAndOpenChainsCloseAcrossTheirChord() {
        var edges = new java.util.ArrayList<GpuCutout.Edge>();
        square(edges, 0, 0);
        square(edges, 1, 1);
        // Three sides of a 2 x 2 square at (5, 5), its fourth side missing.
        edges.add(new GpuCutout.Edge(new Vector3(5, 5, 0), new Vector3(7, 5, 0)));
        edges.add(new GpuCutout.Edge(new Vector3(7, 5, 0), new Vector3(7, 7, 0)));
        edges.add(new GpuCutout.Edge(new Vector3(7, 7, 0), new Vector3(5, 7, 0)));
        assertEquals(2, GpuCutout.outlines(edges, 1e-4f, false).size(), "Without chains, the two squares alone");
        var outlines = GpuCutout.outlines(edges, 1e-4f, true);
        assertEquals(3, outlines.size(), "Two squares, and the open one closed");
        var closed = new java.util.ArrayList<GpuCutout.Edge>();
        for (var outline : outlines) {
            for (int corner = 0; corner < outline.size(); corner++) {
                closed.add(new GpuCutout.Edge(outline.get(corner), outline.get((corner + 1) % outline.size())));
            }
        }
        assertEquals(7 + 4, area(GpuCutout.fillNonZero(closed)), 1e-4f, "The squares' union, 4 + 4 - 1, and the closed one");
        assertEquals(6 + 4, area(GpuCutout.fill(closed)), 1e-4f, "Even/odd leaves the overlap open");
    }

    /** A 2 x 2 square from (x, y), wound counter-clockwise. */
    private static void square(java.util.List<GpuCutout.Edge> edges, float x, float y) {
        Vector3[] corners = { new Vector3(x, y, 0), new Vector3(x + 2, y, 0), new Vector3(x + 2, y + 2, 0), new Vector3(x, y + 2, 0) };
        for (int corner = 0; corner < 4; corner++) { edges.add(new GpuCutout.Edge(corners[corner], corners[(corner + 1) % 4])); }
    }

    private static float area(java.util.List<Vector3[]> spans) {
        float area = 0;
        for (Vector3[] span : spans) {
            area += ((span[1].x - span[0].x) + (span[2].x - span[3].x)) * .5f * (span[2].y - span[1].y);
        }
        return area;
    }

    private static boolean hit(MeshBuilder mesh, float x, float y, float z, float dx, float dy, float dz) {
        var ray = new Ray(new Vector3(x, y, z), new Vector3(dx, dy, dz));
        float[] vertices = vertices(mesh);
        short[] indices = indices(mesh);
        for (int i = 0; i < indices.length; i += 3) {
            if (Intersector.intersectRayTriangle(ray, vector(vertices, Short.toUnsignedInt(indices[i]), 0),
                  vector(vertices, Short.toUnsignedInt(indices[i + 1]), 0),
                  vector(vertices, Short.toUnsignedInt(indices[i + 2]), 0), null)) { return true; }
        }
        return false;
    }
}
