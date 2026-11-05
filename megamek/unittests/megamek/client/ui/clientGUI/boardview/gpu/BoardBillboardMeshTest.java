/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecoration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Sizing changes the panel, never the members: they keep their section and repeat. */
class BoardBillboardMeshTest {
    private static final float METRE = BoardBillboard.UNITS_PER_METRE;

    // A board material's finish reads its emission map, to tell a uniform one.
    @BeforeAll static void natives() { com.badlogic.gdx.utils.GdxNativesLoader.load(); }

    /** Each material of a level is one mesh of three vertices a triangle ({@link GeneratedMesh#data}). */
    private static ModelMesh mesh(RigidGlb.Data data, String material) {
        for (var node : data.nodes) {
            if (node.parts[0].materialId.equals(material)) {
                for (var mesh : data.meshes) { if (mesh.id.equals(node.meshId)) { return mesh; } }
            }
        }
        return null;
    }

    private static float[] range(ModelMesh mesh, int axis) {
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int at = axis; at < mesh.vertices.length; at += RigidGlb.STRIDE) {
            low = Math.min(low, mesh.vertices[at]);
            high = Math.max(high, mesh.vertices[at]);
        }
        return new float[] { low, high };
    }

    private static int triangles(ModelMesh mesh) { return mesh.vertices.length / RigidGlb.STRIDE / 3; }

    @Test void aWiderPanelGainsLampsOfTheSameSize() {
        var lens = mesh(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 0), "billboard-lens");
        var wide = mesh(BoardBillboardMesh.data(BoardBillboard.DEFAULT.with("width", "29.26"), 18, 0), "billboard-lens");
        assertEquals(2 * triangles(lens), triangles(wide), "Twice the width, twice the lamps");
        // One lamp's glass, from the last two triangles' first vertex to its opposite one.
        assertEquals(lensWidth(lens), lensWidth(wide), .001f);
        assertEquals(.6f * METRE, lensWidth(lens), .01f);
    }

    private static float lensWidth(ModelMesh mesh) {
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int at = 0; at < 6 * RigidGlb.STRIDE; at += RigidGlb.STRIDE) {
            low = Math.min(low, mesh.vertices[at]);
            high = Math.max(high, mesh.vertices[at]);
        }
        return high - low;
    }

    @Test void thePanelTakesTheSizeInMetres() {
        var data = BoardBillboardMesh.data(BoardBillboard.DEFAULT.with("width", "20").with("height", "6"), 18, 0);
        var face = mesh(data, "structures/common/weathered-paint");
        assertEquals(2, triangles(face), "A blank panel is one quad");
        assertEquals(20 * METRE, range(face, 0)[1] - range(face, 0)[0], .001f);
        assertEquals(6 * METRE, range(face, 2)[1] - range(face, 2)[0], .001f);
    }

    @Test void legsReachTheirSupportAndALeglessPanelStaysAheadOfItsRearPlane() {
        var standing = mesh(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 40, 0), "structures/common/steel");
        assertTrue(range(standing, 2)[0] <= -40, "The legs run down past the support");
        assertTrue(range(standing, 1)[1] > 0, "Legs stand behind the structure");
        var hanging = mesh(BoardBillboardMesh.data(BoardBillboard.DEFAULT.with("legs", "false"), 0, 0), "structures/common/steel");
        assertEquals(0, range(hanging, 1)[1], .001f, "The rear plane meets a wall");
        assertNull(mesh(BoardBillboardMesh.data(BoardBillboard.DEFAULT.with("legs", "false"), 0, 0), "structures/common/cast-concrete"));
    }

    @Test void panelsStackWithAPaddingInOneStructure() {
        var stack = BoardBillboard.DEFAULT.with("panels", "3").blank();
        var faces = mesh(BoardBillboardMesh.data(stack, 18, 0), "structures/common/weathered-paint");
        assertEquals(6, triangles(faces), "One quad a panel");
        float panel = (float) stack.height() * METRE, padding = (float) BoardBillboard.PADDING * METRE;
        assertEquals(0, range(faces, 2)[0], .001f);
        assertEquals(3 * panel + 2 * padding, range(faces, 2)[1], .001f);
        // The second quad's lowest vertex: the panel above the lowest one starts a panel and a padding up.
        float second = Float.MAX_VALUE;
        for (int at = 6 * RigidGlb.STRIDE + 2; at < 12 * RigidGlb.STRIDE; at += RigidGlb.STRIDE) { second = Math.min(second, faces.vertices[at]); }
        assertEquals(panel + padding, second, .001f);
        int one = totalTriangles(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 0));
        assertTrue(totalTriangles(BoardBillboardMesh.data(stack, 18, 0)) < one + 2 * 100, "Only the panels repeat");
        var light = BoardBillboardMesh.lights(stack).getFirst();
        assertTrue(2 * light.size() > stack.stackHeight() * METRE, "The lamps' light reaches the top panel");
        assertEquals(stack.stackHeight() * METRE, stack.bounds()[5], .001f);
    }

    @Test void theLegsAreAsTallAsThePlacementIsHigh() {
        var standing = new BoardDecoration("board", "prop", BoardBillboard.ASSET, null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.surface("ground", "top", 2), 0);
        float level = BoardGeometry.level() / BoardGeometry.hexScale();
        assertEquals(2 * level, BoardBillboardMesh.legs(standing, 5), .001f);
        var fixed = standing.transform(0, 0, 0, false, 1, BoardDecoration.Placement.absolute(7));
        assertEquals(2 * level, BoardBillboardMesh.legs(fixed, 5), .001f);
        var scaled = standing.transform(0, 0, 0, false, 2, standing.placement());
        assertEquals(level, BoardBillboardMesh.legs(scaled, 5), .001f, "A group's scale also scales the mesh");
        assertEquals(0, BoardBillboardMesh.legs(standing.withBillboard(BoardBillboard.DEFAULT.with("legs", "false")), 5));
    }

    @Test void levelsOfDetailSimplifyAndLampsFollowTheToggle() {
        int near = totalTriangles(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 0));
        int middle = totalTriangles(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 1));
        int far = totalTriangles(BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 2));
        assertTrue(near > middle && middle > far, near + " " + middle + " " + far);
        assertTrue(near < 1500 && far < 100, near + " " + far);
        var dark = BoardBillboard.DEFAULT.with("lights", "false");
        assertNull(mesh(BoardBillboardMesh.data(dark, 18, 0), "billboard-lens"));
        assertTrue(BoardBillboardMesh.lights(dark).isEmpty());
    }

    private static int totalTriangles(RigidGlb.Data data) {
        int total = 0;
        for (var mesh : data.meshes) { total += triangles(mesh); }
        return total;
    }

    @Test void aWholeRowOfLampsIsOneLightAlongThePanel() {
        var huge = BoardBillboardMesh.lights(BoardBillboard.DEFAULT.with("width", "100"));
        assertEquals(1, huge.size(), "Billboards must stay cheap by the hundred");
        assertEquals(100 * METRE, huge.getFirst().end().x - huge.getFirst().position().x, .001f);
        var tall = BoardBillboard.DEFAULT.with("height", "20");
        assertTrue(2 * BoardBillboardMesh.lights(tall).getFirst().size() > 20 * METRE, "A light ends at twice its size");
    }

    @Test void nightEmissionTakesAnyColourOnOneLayout() {
        var warm = BoardBillboardMesh.data(BoardBillboard.DEFAULT, 18, 0);
        var material = warm.materials.select(found -> found.id.equals("billboard-lens")).iterator().next();
        assertTrue(warm.nightEmission.contains("billboard-lens"));
        assertNull(material.emissive, "A colour attribute would select another program");
        RigidGlb.nightEmission(warm, "billboard-lens", 0, .4f, 1);
        assertArrayEquals(new float[] { 0, .4f, 1 }, warm.surfaces.get("billboard-lens").glow(), "Without an emission map it glows evenly");
    }

    @Test void thePictureCoversThePanelCroppingEvenly() {
        assertArrayEquals(new float[] { .25f, 0, .75f, 1 }, BoardBillboardMesh.cover(1000, 500, 1, 1), .0001f);
        assertArrayEquals(new float[] { 0, .25f, 1, .75f }, BoardBillboardMesh.cover(500, 1000, 1, 1), .0001f);
        assertArrayEquals(new float[] { 0, 0, 1, 1 }, BoardBillboardMesh.cover(1024, 298, 14.63, 4.27), .01f);
    }
}
