/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Real cut geometry must fit stable charts without admitting unsupported panel or biome materials. */
class GpuMaterialCacheTest {
    @Test
    void dryAndUnderwaterBoardCutsFitAtEveryEdgeAndDetail() {
        var dry = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 2, -1, 0));
        var wet = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, -2, 2, 0));
        for (var scene : List.of(dry, wet)) {
            float floor = BoardGeometry.floor(scene);
            int directions = 0;
            for (var lod : TerrainLod.values()) {
                for (var at : List.of(new Coords(0, 0), new Coords(8, 0), new Coords(0, 8), new Coords(8, 8),
                      new Coords(0, 4), new Coords(8, 4), new Coords(4, 0), new Coords(4, 8))) {
                    var tile = scene.tile(at);
                    var surface = new BoardSurface(scene, tile, lod);
                    int size = GpuTerrain.CHUNK_SIZE;
                    var charts = new GpuMaterialCache.Charts(scene, at.getX() / size * size, at.getY() / size * size, floor);
                    int faces = 0;
                    for (var face : surface.walls(scene, floor)) {
                        if (scene.neighbor(tile, BoardGeometry.edgeDirection(face.landEdge())) != null) { continue; }
                        assertTrue(Float.isFinite(surface.relief.shade(face.a()).projection()),
                              "The board cut retains its fixed texture frame");
                        assertTrue(GpuMaterialCache.wall(surface, face, scene), "Cut admission at " + at + ", " + lod);
                        assertNotNull(charts.place(at, face.a(), face.b(), face.c()), "Cut fits at " + at + ", " + lod);
                        directions |= 1 << face.landEdge();
                        faces++;
                    }
                    assertTrue(faces > 0, "The fixture must expose the board floor at " + at);
                }
            }
            assertEquals(63, directions, "The fixture exercises all six outward texture projections");
        }
    }

    @Test
    void materialBlendedCutsKeepTheirProjectionAndFitTheSameCharts() {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              c.getY() < 4 ? BoardScene.Surface.GRASS : BoardScene.Surface.DIRT, 0, -1, 0));
        var at = new Coords(0, 3);
        var tile = scene.tile(at);
        float floor = BoardGeometry.floor(scene);
        var surface = new BoardSurface(scene, tile);
        var plan = GpuTerrain.prepareSculpt(scene, tile, surface, floor, TerrainLod.FULL, Map.of());
        var charts = new GpuMaterialCache.Charts(scene, 0, 0, floor);
        int cuts = 0;
        for (var group : plan.blended().values()) {
            for (var triangle : group) {
                var a = triangle.a().vertex();
                var b = triangle.b().vertex();
                var c = triangle.c().vertex();
                if (a.color.b != .45f || b.color.b != .45f || c.color.b != .45f) { continue; }
                assertTrue(GpuMaterialCache.wall(scene, tile, a, b, c), "A subdivided border panel remains cacheable");
                assertNotNull(charts.place(at, a.position, b.position, c.position));
                cuts++;
            }
        }
        assertTrue(cuts > 0, "The fixture must send cut faces through material-boundary subdivision");
    }

    @Test
    void pouredAndMuddyCutsRemainDirect() {
        var concrete = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.CONCRETE, 0, -1, 0));
        var mud = BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c, BoardScene.Biome.MUD, 0));
        for (var scene : List.of(concrete, mud)) {
            var tile = scene.tile(new Coords(0, 4));
            var surface = new BoardSurface(scene, tile);
            var faces = surface.walls(scene, BoardGeometry.floor(scene));
            assertFalse(faces.isEmpty());
            for (var face : faces) {
                assertFalse(GpuMaterialCache.wall(surface, face, scene));
                var a = GpuTerrain.sculptVertex(face.a(), surface.relief.shade(face.a()), Float.NaN, surface);
                var b = GpuTerrain.sculptVertex(face.b(), surface.relief.shade(face.b()), Float.NaN, surface);
                var c = GpuTerrain.sculptVertex(face.c(), surface.relief.shade(face.c()), Float.NaN, surface);
                assertFalse(GpuMaterialCache.wall(scene, tile, a, b, c));
            }
        }
    }

    @Test
    void changingTheBoardFloorRequiresNewBoundaryCharts() {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        float floor = BoardGeometry.floor(scene);
        var before = new GpuMaterialCache.Charts(scene, 0, 0, floor);
        assertTrue(before.sameLayout(new GpuMaterialCache.Charts(scene, 0, 0, floor)));
        var after = new GpuMaterialCache.Charts(scene, 0, 0, floor - BoardGeometry.level());
        assertFalse(before.sameLayout(after), "Reused wall meshes must not keep chart coordinates for the old floor");
        var at = new Coords(0, 4);
        var surface = new BoardSurface(scene, scene.tile(at));
        for (var face : surface.walls(scene, floor - BoardGeometry.level())) {
            assertNotNull(after.place(at, face.a(), face.b(), face.c()), "The complete deeper cut fits its new slot");
        }
    }

    @Test
    void chartedCommCenterCutsRetainEveryFacesOwnShading() throws Exception {
        var scene = BoardCliffSeamTest.capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        var emit = GpuTerrain.class.getDeclaredMethod("chartedFaces", Supplier.class, BoardSurface.class,
              List.class, List.class, float.class);
        emit.setAccessible(true);
        class Capture extends MeshBuilder {
            float[] points() { return getVertices(); }
            short[] elements() { return getIndices(); }
        }
        int checked = 0;
        for (var lod : TerrainLod.values()) {
            for (var tile : scene.tiles()) {
                var at = tile.coords();
                if (at.getX() > 0 && at.getY() > 0 && at.getX() < scene.width() - 1 && at.getY() < scene.height() - 1) {
                    continue;
                }
                var surface = new BoardSurface(scene, tile, lod);
                int size = GpuTerrain.CHUNK_SIZE;
                var charts = new GpuMaterialCache.Charts(scene, at.getX() / size * size, at.getY() / size * size, floor);
                List<BoardSurface.Face> faces = new ArrayList<>();
                List<float[]> places = new ArrayList<>();
                for (var face : surface.walls(scene, floor)) {
                    if (!GpuMaterialCache.wall(surface, face, scene)) { continue; }
                    float[] place = charts.place(at, face.a(), face.b(), face.c());
                    if (place != null) { faces.add(face); places.add(place); }
                }
                if (faces.isEmpty()) { continue; }
                var mesh = new Capture();
                mesh.begin(GpuMaterialCache.chartLayout(GpuTerrainLayer.MeshBatch.STANDARD));
                mesh.part("cut", GL20.GL_TRIANGLES);
                emit.invoke(null, (Supplier<MeshPartBuilder>) () -> mesh, surface, faces, places, Float.NaN);
                float[] data = mesh.points();
                short[] indices = mesh.elements();
                for (int i = 0; i < faces.size(); i++) {
                    var face = faces.get(i);
                    var points = List.of(face.a(), face.b(), face.c());
                    for (int k = 0; k < 3; k++) {
                        var p = points.get(k);
                        var v = GpuTerrain.sculptVertex(p, surface.relief.shade(p), Float.NaN, surface);
                        float[] expected = { p.x, p.y, p.z, v.color.toFloatBits(), v.normal.x, v.normal.y,
                              v.normal.z, v.uv.x, v.uv.y, places.get(i)[2 * k], places.get(i)[2 * k + 1] };
                        int start = Short.toUnsignedInt(indices[3 * i + k]) * expected.length;
                        for (int a = 0; a < expected.length; a++) {
                            assertEquals(expected[a], data[start + a], "Cut vertex attribute " + a + " at " + at + ", " + lod);
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0, "Real border walls must pass through the indexed cache mesh writer");
    }
}
