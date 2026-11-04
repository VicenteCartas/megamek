/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Area;
import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AuthoredBoardClipTest {
    @BeforeAll static void natives() { GdxNativesLoader.load(); }

    @Test void cutsAllSidesAndConcaveCornersIntoClosedSolidsWithOutwardNeutralCaps() {
        int width = 3, height = 3;
        var board = BuildingVolumes.polygon(BuildingVolumes.boardOutline(width, height));
        for (double[] box : List.of(new double[] {-100, -350, 350, 100}, new double[] {-30, -120, 100, -40},
              new double[] {120, -170, 260, -60}, new double[] {50, -30, 150, 70}, new double[] {60, -300, 180, -160})) {
            var outline = rectangle(box[0], box[1], box[2], box[3]);
            GeneratedMesh mesh = new GeneratedMesh(); mesh.slab("body", outline, 10, 50, true);
            ModelData data = AuthoredBoardClip.clip(mesh, material(), width, height, List.of());
            var result = mesh(data);
            Area retained = BuildingVolumes.polygon(outline.stream().map(p -> new BuildingVolumes.Point(p[0], p[1], 0)).toList());
            retained.intersect(board);
            assertEquals(BuildingVolumes.area(retained) * 40, volume(result), 3, Arrays.toString(box));
            assertTrue(result.parts.containsKey(AuthoredBoardClip.CAP));
            assertInside(result, width, height);
            for (var mat : data.materials) {
                if (mat.id.equals(AuthoredBoardClip.CAP)) { assertEquals(new Color(.08f, .08f, .08f, 1), mat.diffuse); }
            }
            var caps = result.parts.get(AuthoredBoardClip.CAP);
            for (int i = 0; i < caps.size; i += 3 * RigidGlb.STRIDE) {
                Vector3 a = point(caps.items, i), b = point(caps.items, i + RigidGlb.STRIDE), c = point(caps.items, i + 2 * RigidGlb.STRIDE);
                var normal = new Vector3(caps.items[i + 3], caps.items[i + 4], caps.items[i + 5]);
                assertTrue(new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).dot(normal) >= 0);
                var centre = new Vector3(a).add(b).add(c).scl(1f / 3);
                assertFalse(board.contains(centre.x + normal.x * .02, centre.y + normal.y * .02), "Only the map perimeter gets caps");
                assertTrue(board.contains(centre.x - normal.x * .02, centre.y - normal.y * .02));
            }
        }
    }

    @Test void sealedCrossSectionRetainsAnEnclosedHoleAndOpenSheetsDoNotBecomeWalls() {
        GeneratedMesh mesh = new GeneratedMesh();
        mesh.slab("body", rectangle(-100, -200, 200, 100), 0, 60, true);
        GeneratedMesh hole = new GeneratedMesh();
        hole.slab("body", rectangle(-100, -200, 200, 100), 20, 40, true);
        var inner = hole.parts.get("body");
        for (int i = 0; i < inner.size; i += 3 * RigidGlb.STRIDE) {
            mesh.triangle("body", vertex(inner.items, i), vertex(inner.items, i + 2 * RigidGlb.STRIDE), vertex(inner.items, i + RigidGlb.STRIDE));
        }
        var clipped = mesh(AuthoredBoardClip.clip(mesh, material(), 1, 1, List.of()));
        assertEquals(BuildingVolumes.area(BuildingVolumes.hex(new Coords(0, 0))) * 40, volume(clipped), 1);
        var caps = clipped.parts.get(AuthoredBoardClip.CAP);
        for (int i = 0; i < caps.size; i += 3 * RigidGlb.STRIDE) {
            double z = (caps.items[i + 2] + caps.items[i + RigidGlb.STRIDE + 2] + caps.items[i + 2 * RigidGlb.STRIDE + 2]) / 3;
            assertTrue(z <= 20 || z >= 40, "The cut must not fill the hole");
        }
        GeneratedMesh sheet = new GeneratedMesh();
        sheet.triangle("body", new float[] {-100, -30, 30, 0, 0, 1, 1, 1, 1, 1, 0, 0},
              new float[] {200, -30, 30, 0, 0, 1, 1, 1, 1, 1, 1, 0}, new float[] {40, -150, 30, 0, 0, 1, 1, 1, 1, 1, 0, 1});
        assertFalse(mesh(AuthoredBoardClip.clip(sheet, material(), 1, 1, List.of())).parts.containsKey(AuthoredBoardClip.CAP));
    }

    @Test void untouchedFacesKeepTheirAttributesAndExistingPerimeterFacesAreNotCappedTwice() {
        GeneratedMesh mesh = new GeneratedMesh(); mesh.slab("body", rectangle(30, -45, 50, -25), 0, 20, true);
        assertArrayEquals(mesh.parts.get("body").toArray(), mesh(AuthoredBoardClip.clip(mesh, material(), 1, 1, List.of())).parts.get("body").toArray(), 1e-6f);
        GeneratedMesh exact = new GeneratedMesh();
        exact.slab("body", BuildingVolumes.boardOutline(1, 1).stream().map(p -> new double[] {p.x(), p.y()}).toList(), 0, 20, true);
        var retained = mesh(AuthoredBoardClip.clip(exact, material(), 1, 1, List.of()));
        assertFalse(retained.parts.containsKey(AuthoredBoardClip.CAP));
        assertEquals(volume(exact), volume(retained), .1);
        GeneratedMesh outside = new GeneratedMesh(); outside.slab("body", rectangle(-100, 50, -50, 100), 0, 20, true);
        assertTrue(AuthoredBoardClip.clip(outside, material(), 1, 1, List.of()).meshes.isEmpty());
    }

    @Test void anOpenCanopyTouchingTheShellDoesNotStealItsClosedCapLoop() {
        GeneratedMesh box = new GeneratedMesh(); box.slab("body", rectangle(30, -10, 50, 10), 0, 20, true);
        GeneratedMesh canopy = new GeneratedMesh(); canopy.slab("body", rectangle(50, -10, 80, 10), 19, 20, true);
        var top = canopy.parts.get("body");
        // slab writes its two top triangles first; a single open sheet shares the box's upper-right edge.
        for (int i = 0; i < 6 * RigidGlb.STRIDE; i += 3 * RigidGlb.STRIDE) {
            box.triangle("body", vertex(top.items, i), vertex(top.items, i + RigidGlb.STRIDE), vertex(top.items, i + 2 * RigidGlb.STRIDE));
        }
        var caps = mesh(AuthoredBoardClip.clip(box, material(), 1, 1, List.of())).parts.get(AuthoredBoardClip.CAP);
        assertNotNull(caps);
        double area = 0;
        for (int i = 0; i < caps.size; i += 3 * RigidGlb.STRIDE) {
            var a = point(caps.items, i);
            area += point(caps.items, i + RigidGlb.STRIDE).sub(a).crs(point(caps.items, i + 2 * RigidGlb.STRIDE).sub(a)).len() * .5;
        }
        assertEquals(400, area, .1);
    }

    @Test void verticalWallsOnAndJustBesideInternalEdgesAreRetainedExactlyOnce() {
        for (float y : new float[] {-72, -72.0005f, -71.9995f}) {
            GeneratedMesh wall = new GeneratedMesh();
            wall.quad("body", new float[] {30, y, 0, 0, 1, 0, 1, 1, 1, 1, 0, 0},
                  new float[] {50, y, 0, 0, 1, 0, 1, 1, 1, 1, 1, 0},
                  new float[] {50, y, 20, 0, 1, 0, 1, 1, 1, 1, 1, 1}, new float[] {30, y, 20, 0, 1, 0, 1, 1, 1, 1, 0, 1});
            var result = mesh(AuthoredBoardClip.clip(wall, material(), 1, 2, List.of()));
            assertTrue(result.parts.containsKey("body"));
            assertEquals(wall.parts.get("body").size, result.parts.get("body").size, "Wall at y=" + y);
        }
    }

    @Test void structuralHelpersSealOpenRoomsWithoutFillingTheCourtyardBetweenThem() {
        GeneratedMesh roofs = new GeneratedMesh();
        var solids = new java.util.ArrayList<BuildingVolumes.Solid>();
        for (var rectangle : List.of(rectangle(25, -100, 35, 20), rectangle(50, -100, 60, 20), rectangle(25, -100, 60, -40))) {
            GeneratedMesh shell = new GeneratedMesh(); shell.slab("body", rectangle, 0, 20, true);
            var vertices = shell.parts.get("body");
            var triangles = new java.util.ArrayList<BuildingVolumes.Triangle>();
            for (int i = 0; i < vertices.size; i += 3 * RigidGlb.STRIDE) {
                var a = point(vertices.items, i); var b = point(vertices.items, i + RigidGlb.STRIDE); var c = point(vertices.items, i + 2 * RigidGlb.STRIDE);
                triangles.add(new BuildingVolumes.Triangle(new BuildingVolumes.Point(a.x, a.y, a.z),
                      new BuildingVolumes.Point(b.x, b.y, b.z), new BuildingVolumes.Point(c.x, c.y, c.z)));
                if (i < 6 * RigidGlb.STRIDE) { roofs.triangle("body", vertex(vertices.items, i), vertex(vertices.items, i + RigidGlb.STRIDE), vertex(vertices.items, i + 2 * RigidGlb.STRIDE)); }
            }
            solids.add(new BuildingVolumes.Solid("room", triangles));
        }
        var caps = mesh(AuthoredBoardClip.clip(roofs, material(), 1, 2, solids)).parts.get(AuthoredBoardClip.CAP);
        assertNotNull(caps);
        double area = 0;
        for (int i = 0; i < caps.size; i += 3 * RigidGlb.STRIDE) {
            var a = point(caps.items, i); var b = point(caps.items, i + RigidGlb.STRIDE); var c = point(caps.items, i + 2 * RigidGlb.STRIDE);
            double x = (a.x + b.x + c.x) / 3;
            assertTrue(x <= 35 || x >= 50, "The courtyard has no structural volume to seal");
            area += b.sub(a).crs(c.sub(a)).len() * .5;
        }
        assertEquals(400, area, .1);
    }

    static void assertInside(GeneratedMesh mesh, int width, int height) {
        for (var part : mesh.parts.values()) {
            for (int i = 0; i < part.size; i += RigidGlb.STRIDE) {
                assertTrue(HexTile.at(part.items[i], part.items[i + 1], HexTile.MODEL_WIDTH, HexTile.HEIGHT, width, height) >= 0,
                      "Outside vertex: " + point(part.items, i));
            }
        }
    }

    static GeneratedMesh mesh(ModelData data) {
        GeneratedMesh mesh = new GeneratedMesh();
        for (var node : data.nodes) { mesh.placed(data, node, new Matrix4()); }
        return mesh;
    }

    private static ModelData material() {
        var data = new ModelData(); data.id = "test";
        var material = new ModelMaterial(); material.id = "body"; material.diffuse = Color.WHITE;
        data.materials.add(material); return data;
    }

    private static List<double[]> rectangle(double x0, double y0, double x1, double y1) {
        return List.of(new double[] {x0, y0}, new double[] {x1, y0}, new double[] {x1, y1}, new double[] {x0, y1});
    }

    private static float[] vertex(float[] vertices, int i) { return Arrays.copyOfRange(vertices, i, i + RigidGlb.STRIDE); }
    private static Vector3 point(float[] vertices, int i) { return new Vector3(vertices[i], vertices[i + 1], vertices[i + 2]); }

    private static double volume(GeneratedMesh mesh) {
        double volume = 0;
        for (var part : mesh.parts.values()) {
            for (int i = 0; i < part.size; i += 3 * RigidGlb.STRIDE) {
                volume += point(part.items, i).dot(point(part.items, i + RigidGlb.STRIDE).crs(point(part.items, i + 2 * RigidGlb.STRIDE))) / 6.0;
            }
        }
        return volume;
    }
}
