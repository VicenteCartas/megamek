/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuBuildingInteriorTest {
    @BeforeAll
    static void loadNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void lightBuildingFloorsAndRoofCoverItsCentre() {
        var kit = GpuBuilding.kit("buildings/standard/building-light");
        // Its floor caps hold the occupied area (GpuBuilding#volumes), as its closed panels are wall strips, not rooms.
        // Alone, every storey's cap and its roof cover its centre; connected, its roof still does.
        for (int[] reach : List.of(new int[6], new int[] { 2, 0, 0, 2, 0, 0 }, new int[] { 2, 0, 2, 0, 2, 0 })) {
            boolean alone = java.util.Arrays.stream(reach).allMatch(levels -> levels == 0);
            for (var entry : storeys(kit, reach).entrySet()) {
                if (!alone && !entry.getKey().startsWith("roof")) { continue; }
                assertTrue(GpuBuildingInterior.area(entry.getValue()).contains(0, 0),
                      entry.getKey() + " " + java.util.Arrays.toString(reach));
            }
        }
    }

    @Test
    void fortressFootprintRemainsCompact() {
        var data = GpuBuildingTest.lods(GpuBuildingTest.ASSET).get(1);
        for (var mesh : data.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                mesh.vertices[i] += 71; mesh.vertices[i + 1] -= 23; mesh.vertices[i + 2] += 43;
            }
        }
        for (var node : data.nodes) { node.translation.add(900, -400, 257); }
        java.awt.geom.Area volume = null;
        for (var entry : storeys(ConnectedKit.load(List.of(data)), new int[6]).entrySet()) {
            var area = entry.getKey().startsWith("roof") ? GpuBuildingInterior.area(entry.getValue())
                  : GpuBuildingInterior.walls(entry.getValue(), entry.getKey().endsWith("@0") ? 9 : 27);
            // A module keeps its authored XY (ConnectedKit#placements): its centre moved with both offsets.
            assertTrue(area.contains(900 + 71, -400 - 23), "The wall outline must enclose usable volume");
            if (volume == null) { volume = area; } else { volume.intersect(area); }
        }
        var footprint = GpuBuildingInterior.triangles(volume);
        assertTrue(footprint.size() < 300, "The footprint must not inherit facade tessellation");
    }

    /**
     * A two-storey hex assembled from the kit's first level of detail, connecting {@code reach} levels each way: its
     * pieces' triangles by module type and level ("base@0", "floor@1", "roof@2").
     */
    private static java.util.Map<String, List<Vector3>> storeys(ConnectedKit.Kit kit, int[] reach) {
        java.util.Map<String, List<Vector3>> result = new java.util.HashMap<>();
        for (var piece : ConnectedKit.placements(kit, 0, 2, reach, 0, 1)) {
            String key = piece.role().replaceFirst("[0-9]+$", "") + "@" + piece.level();
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(ConnectedKit.triangles(kit, 0, piece));
        }
        return result;
    }

    @Test
    void wallSectionsKeepExternalThicknessAndExcludeLedgesAndOpenPanels() {
        List<Vector3> walls = new ArrayList<>();
        rectangle(walls, -13, -25, 13, 25);
        rectangle(walls, -12, -24, 12, 24);
        // A projecting sidewalk at the base and a disconnected decorative panel must not expand the storey.
        walls.addAll(List.of(new Vector3(-18, -30, 0), new Vector3(18, -30, 0), new Vector3(18, 30, 0)));
        wall(walls, 16, -20, 16, 20);
        // Double-sided imports and repeated material seams retain the same outline.
        var original = List.copyOf(walls);
        for (int i = 0; i < original.size(); i += 3) {
            walls.add(original.get(i + 2)); walls.add(original.get(i + 1)); walls.add(original.get(i));
        }
        var footprint = GpuBuildingInterior.walls(walls, 9);
        assertTrue(footprint.contains(12.5, 0), "The wall's thickness belongs to the external footprint");
        assertTrue(footprint.contains(0, 24.5));
        assertTrue(footprint.contains(0, 0));
        assertFalse(footprint.contains(13.1, 0));
        assertFalse(footprint.contains(0, 25.1));
        assertFalse(footprint.contains(16, 0));
    }

    @Test
    void wallSectionsPreserveConcaveOutlinesAndDisconnectedWings() {
        List<Vector3> walls = new ArrayList<>();
        float[][] outline = { { 0, 0 }, { 30, 0 }, { 30, 10 }, { 10, 10 }, { 10, 30 }, { 0, 30 } };
        for (int i = 0; i < outline.length; i++) {
            float[] a = outline[i], b = outline[(i + 1) % outline.length];
            wall(walls, a[0], a[1], b[0], b[1]);
        }
        rectangle(walls, 40, 0, 50, 10);
        var footprint = GpuBuildingInterior.walls(walls, 9);
        assertTrue(footprint.contains(5, 25));
        assertTrue(footprint.contains(25, 5));
        assertTrue(footprint.contains(45, 5));
        assertFalse(footprint.contains(20, 20));
        assertFalse(footprint.contains(35, 5));
    }

    @Test
    void floorsKeepClearOfWallFacesButReachAnOpenEnd() {
        // A room whose walls stand on three sides; its north end stands open.
        List<Vector3> walls = new ArrayList<>();
        wall(walls, -12, -25, 12, -25);
        wall(walls, 12, -25, 12, 25);
        wall(walls, -12, 25, -12, -25);
        var floor = GpuBuildingInterior.keepClear(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Float(-12, -25, 24, 50)),
              GpuBuildingInterior.faces(walls, 18));
        assertTrue(floor.contains(0, 0));
        assertFalse(floor.contains(11.9, 0), "A floor edge on a wall's face flickers through it");
        assertFalse(floor.contains(0, -24.9));
        assertTrue(floor.contains(11.7, 0));
        assertTrue(floor.contains(0, 24.9), "The open end keeps its floor to the edge");
    }

    private static void rectangle(List<Vector3> triangles, float x1, float y1, float x2, float y2) {
        wall(triangles, x1, y1, x2, y1);
        wall(triangles, x2, y1, x2, y2);
        wall(triangles, x2, y2, x1, y2);
        wall(triangles, x1, y2, x1, y1);
    }

    private static void wall(List<Vector3> triangles, float x1, float y1, float x2, float y2) {
        Vector3 a = new Vector3(x1, y1, 0), b = new Vector3(x2, y2, 0);
        Vector3 c = new Vector3(x2, y2, 18), d = new Vector3(x1, y1, 18);
        triangles.addAll(List.of(a, b, c, a, c, d));
    }
}
