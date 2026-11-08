/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.LongArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Edits must match a fresh build, including copied index ranges, water, cliffs, and support/picking triangles. */
@Tag("on-demand")
class GpuTerrainIncrementalSmokeTest {
    @Test
    void editsReuseInteriorGeometryAndMatchCompleteBuilds() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain incremental = new GpuTerrain();
                try {
                    BoardScene scene = dryScene();
                    incremental.update(scene);
                    Coords untouched = new Coords(1, 1);
                    var retained = incremental.tacticalSurface(untouched);
                    scene = edit(scene, new Coords(8, 8), 2, -1, BoardScene.Surface.GRASS);
                    incremental.update(scene);
                    assertSame(retained, incremental.tacticalSurface(untouched),
                          "An untouched hex inside a replaced chunk keeps its finished surface");
                    compareFresh(incremental, scene);
                    // Adding, redirecting and removing cliff exits changes only the edge shape, not either level.
                    for (int exits : new int[] { 56, 24, 0 }) {
                        var at = new Coords(8, 8);
                        var tile = scene.tile(at);
                        var tiles = new ArrayList<>(scene.tiles());
                        tiles.set(at.getX() * scene.height() + at.getY(), new BoardScene.Tile(at, tile.elevation(),
                              tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(),
                              tile.decals(), tile.decalsWithoutLimbs(), tile.features(), tile.text(),
                              tile.liquid(), tile.tileset(), tile.detailedGround(), tile.road(), tile.fireSmoke(), tile.biome(),
                              tile.impassable(), tile.blackIce(), exits));
                        scene = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
                        incremental.update(scene);
                        compareFresh(incremental, scene);
                    }
                    // Lowering the visual floor changes the perimeter, not every interior tile.
                    retained = incremental.tacticalSurface(new Coords(4, 4));
                    scene = edit(scene, new Coords(8, 8), -2, -1, BoardScene.Surface.SAND);
                    incremental.update(scene);
                    assertSame(retained, incremental.tacticalSurface(new Coords(4, 4)));
                    compareFresh(incremental, scene);

                    scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS);
                    incremental.update(scene);
                    Map<Coords, BoardTacticalGeometry.Surface> installed = new HashMap<>();
                    for (var tile : scene.tiles()) { installed.put(tile.coords(), incremental.tacticalSurface(tile.coords())); }
                    // An installed hex keeps its support while its chunk lives: a collection never makes a query rebuild it.
                    System.gc();
                    for (var tile : scene.tiles()) {
                        assertSame(installed.get(tile.coords()), incremental.tacticalSurface(tile.coords()),
                              "Queries borrow the installed support at " + tile.coords());
                    }
                    for (Coords at : List.of(new Coords(4, 4), new Coords(5, 5), new Coords(0, 3), new Coords(7, 7))) {
                        var tile = scene.tile(at);
                        scene = edit(scene, at, tile.elevation() + 1, tile.waterDepth(), BoardScene.Surface.SAND);
                        incremental.update(scene);
                        compareFresh(incremental, scene);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { incremental.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Incremental terrain parity", failure.get()); }
    }

    private static void compareFresh(GpuTerrain incremental, BoardScene scene) throws Exception {
        GpuTerrain fresh = new GpuTerrain();
        try {
            fresh.update(scene);
            // A hex its preparation gave no support holds the shape alone; ground cover compares as planted.
            for (var tile : scene.tiles()) {
                assertEquals(BoardGrassBankTest.shape(fresh.tacticalSurface(tile.coords())),
                      BoardGrassBankTest.shape(incremental.tacticalSurface(tile.coords())),
                      "Support geometry differs at " + tile.coords());
                assertEquals(fresh.planted(tile.coords()), incremental.planted(tile.coords()),
                      "Ground cover differs at " + tile.coords());
            }
            var expected = triangles(fresh);
            var actual = triangles(incremental);
            assertEquals(expected.keySet(), actual.keySet());
            for (Coords coords : expected.keySet()) {
                assertTrue(Arrays.equals(expected.get(coords), actual.get(coords)),
                      "Rendered positions/normals differ at " + coords);
            }
        } finally { fresh.dispose(); }
    }

    /** Compare the actual emitted triangles, independently of material grouping and mesh packing. */
    private static Map<Coords, long[]> triangles(GpuTerrain terrain) throws Exception {
        Map<Coords, long[]> result = new HashMap<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (var entry : ((Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).entrySet()) {
                LongArray triangles = new LongArray();
                for (Object range : (List<?>) GpuTestSupport.field(entry.getValue(), "ranges")) {
                    Mesh mesh = (Mesh) GpuTestSupport.field(range, "mesh");
                    FloatArray points = new FloatArray();
                    ShortArray indices = new ShortArray();
                    GpuPropBatch.copyVertices(mesh, (int) GpuTestSupport.field(range, "offset"), (int) GpuTestSupport.field(range, "count"), points, indices);
                    int stride = mesh.getVertexSize() / Float.BYTES;
                    var normal = mesh.getVertexAttributes().findByUsage(VertexAttributes.Usage.Normal);
                    for (int i = 0; i < indices.size; i += 3) {
                        long hash = (int) GpuTestSupport.field(range, "layer") + 1;
                        for (int j = 0; j < 3; j++) {
                            int start = Short.toUnsignedInt(indices.get(i + j)) * stride;
                            for (int n = 0; n < 3; n++) { hash = hash * 31 + Math.round(points.get(start + n) * 10000); }
                            if (normal != null) {
                                for (int n = 0; n < 3; n++) {
                                    hash = hash * 31 + Math.round(points.get(start + normal.offset / Float.BYTES + n) * 10000);
                                }
                            }
                        }
                        triangles.add(hash);
                    }
                }
                long[] sorted = triangles.toArray();
                Arrays.sort(sorted);
                result.put((Coords) entry.getKey(), sorted);
            }
        }
        return result;
    }

    private static BoardScene edit(BoardScene scene, Coords at, int level, int depth, BoardScene.Surface family) {
        var tiles = new ArrayList<>(scene.tiles());
        tiles.set(at.getX() * scene.height() + at.getY(), new BoardScene.Tile(at, level, depth, false, 0, family, null,
              null, List.of(), List.of(), depth < 0 ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene dryScene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 17; x++) {
            for (int y = 0; y < 16; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 17, 16, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
