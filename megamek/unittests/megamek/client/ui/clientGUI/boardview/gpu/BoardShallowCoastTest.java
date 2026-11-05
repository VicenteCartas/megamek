/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Curved depth-zero coasts grow from the installed bank, including its part inside a land hex. */
class BoardShallowCoastTest {
    @ParameterizedTest
    @CsvSource({ "FULL,true", "FULL,false", "MEDIUM,true", "MEDIUM,false" })
    void waterfallGrassFollowsExposedBanksAcrossHexOwnership(TerrainLod lod, boolean transitions) throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            var defaults = BoardGeometry.DEFAULTS;
            BoardGeometry.tune(new BoardGeometry.Tuning(defaults.hexScale(), defaults.unitScale(), defaults.unitHeightScale(),
                  defaults.levelHeight(), defaults.gridShade(), defaults.multiHexUnitScale(), transitions, 0));
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "unofficial/Lanceman/16x17 Waterfall.board"), false, true);
            var scene = BoardScenes.scene(board);
            var owners = List.of(new Coords(3, 7), new Coords(4, 7), new Coords(3, 8), new Coords(4, 8),
                  new Coords(3, 9), new Coords(4, 9), new Coords(3, 10), new Coords(4, 10));
            var tiles = owners.stream().map(scene::tile).toList();
            float floor = BoardGeometry.floor(scene), sink = BoardGeometry.width() * .001f;
            Map<Coords, BoardSurface> sources = new HashMap<>();
            for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
            Set<List<Float>> positions = new HashSet<>();
            Map<Coords, Integer> shoreRoots = new HashMap<>();
            int borrowed = 0;
            for (var tile : tiles) {
                assertTrue(GpuGroundCover.grows(scene, tile), "The reported coast accepts grass at " + tile.coords());
                var source = sources.get(tile.coords());
                var surface = BoardTacticalGeometry.Surface.of(source, scene, floor, sources);
                var plants = BoardPlants.plant(scene, tile, surface, lod, List.of());
                assertNotNull(plants);
                assertNotNull(plants.grass());
                var ground = surface.vegetation();
                assertNotNull(ground);
                var support = new GpuBiomeVegetation.Support(ground.faces());
                var roots = plants.grass();
                var rebuilt = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile, lod), scene, floor);
                assertEquals(roots, BoardPlants.plant(scene, tile, rebuilt, lod, List.of()).grass(),
                      "A single-hex rebuild and shared chunk preparation keep the same roots");
                assertEquals(BoardGrassBankTest.shape(rebuilt), BoardTacticalGeometry.Surface.shape(
                      new BoardSurface(scene, tile, lod), scene, floor), "A coast's shape keeps its waterline walls");
                for (int i = 0; i < roots.size; i += 4) {
                    float x = roots.get(i), y = roots.get(i + 1), z = roots.get(i + 2);
                    assertTrue(BoardGeometry.contains(tile.coords(), x, y), "Roots stay in their canonical owner");
                    assertTrue(positions.add(List.of(x, y)), "Adjacent owners never duplicate a root");
                    var face = support.face(x, y);
                    assertNotNull(face);
                    assertEquals(face.height(x, y), z + sink, .002f, "The root follows the rendered ground");
                    if (face.finish() != BoardSurface.Finish.SHORE && face.finish() != BoardSurface.Finish.BED) {
                        continue;
                    }
                    var water = new BoardSurface.Face(waterVertex(ground, face.a()), waterVertex(ground, face.b()),
                          waterVertex(ground, face.c()), BoardSurface.Finish.TOP);
                    assertTrue(z > water.height(x, y),
                          "A shore blade's sunken root must remain above the local water surface");
                    shoreRoots.merge(tile.coords(), 1, Integer::sum);
                    if (!tile.liquid().present() && !source.faces.contains(face)) { borrowed++; }
                }
            }
            assertTrue(borrowed > 40, lod + " restores the curved shore inside land hexes: " + shoreRoots);
            assertTrue(shoreRoots.getOrDefault(new Coords(3, 8), 0) > 0
                  && shoreRoots.getOrDefault(new Coords(3, 9), 0) > 0,
                  "Both marked banks beside 0409 and 0410 carry shore grass: " + shoreRoots);
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void descendingShallowStreamKeepsRootsAboveItsDrawnWater(TerrainLod lod) {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
              c.getY() < 4 ? 1 : 0, c.getX() == 4 ? 0 : -1, 0));
        var tiles = scene.tiles().stream().filter(tile -> tile.coords().getX() >= 3 && tile.coords().getX() <= 5
              && tile.coords().getY() >= 2 && tile.coords().getY() <= 5).toList();
        float floor = BoardGeometry.floor(scene);
        Map<Coords, BoardSurface> sources = new HashMap<>();
        for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
        sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
        var pools = sources.values().stream().filter(source -> source.tile.liquid().present()).toList();
        int gradedRoots = 0;
        for (var tile : tiles) {
            var surface = BoardTacticalGeometry.Surface.of(sources.get(tile.coords()), scene, floor, sources);
            var ground = surface.vegetation();
            assertNotNull(ground);
            var support = new GpuBiomeVegetation.Support(ground.faces());
            var roots = GpuGroundCover.plant(scene, tile, surface, null);
            for (int i = 0; i < roots.size; i += 4) {
                float x = roots.get(i), y = roots.get(i + 1), z = roots.get(i + 2);
                var face = support.face(x, y);
                for (var pool : pools) {
                    float water = BoardSurface.sampleHeight(pool.waterFaces, x, y, Float.NEGATIVE_INFINITY);
                    if (z <= water) {
                        fail("No root may be submerged by the descending stream's actual triangles: owner="
                              + tile.coords() + ", root=" + new Vector3(x, y, z) + ", waterOwner=" + pool.tile.coords()
                              + ", water=" + water + ", face=" + face + ", faceSources="
                              + sources.values().stream().filter(source -> source.faces.contains(face))
                                    .map(source -> source.tile.coords()).toList()
                              + ", wetReferences=" + ground.waterHeights().get(face.a()) + ", "
                              + ground.waterHeights().get(face.b()) + ", " + ground.waterHeights().get(face.c()));
                    }
                }
                if (!ground.waterHeights().containsKey(face.a())) { continue; }
                var water = new BoardSurface.Face(waterVertex(ground, face.a()), waterVertex(ground, face.b()),
                      waterVertex(ground, face.c()), BoardSurface.Finish.TOP);
                assertTrue(z > water.height(x, y), "Grass also clears the wet reference on exposed graded banks");
                float low = Math.min(water.a().z, Math.min(water.b().z, water.c().z));
                float high = Math.max(water.a().z, Math.max(water.b().z, water.c().z));
                if (high - low > .1f * BoardGeometry.hexScale()) { gradedRoots++; }
            }
        }
        assertTrue(gradedRoots > 10, "Exercise populated banks whose local water reference slopes: " + gradedRoots);
    }

    @Test
    void shallowWaterUsesNeighbouringLandInsteadOfItsNominalGrassFamily() {
        var grass = coast(BoardScene.Surface.GRASS, 0, false, BoardLiquid.WATER);
        assertTrue(GpuGroundCover.grows(grass, grass.tile(BoardScenes.CENTER)));
        for (var family : List.of(BoardScene.Surface.DESERT, BoardScene.Surface.SAND, BoardScene.Surface.CONCRETE)) {
            var scene = coast(family, 0, false, BoardLiquid.WATER);
            assertFalse(GpuGroundCover.grows(scene, scene.tile(BoardScenes.CENTER)),
                  "A water tile's nominal grass family cannot plant a " + family + " coast");
        }
    }

    @Test
    void deepFrozenAndMoltenWaterDoNotBecomeGrassSupport() {
        var center = BoardScenes.CENTER;
        for (var scene : List.of(coast(BoardScene.Surface.GRASS, 1, false, BoardLiquid.WATER),
              coast(BoardScene.Surface.GRASS, 0, true, BoardLiquid.WATER),
              coast(BoardScene.Surface.GRASS, 0, false, new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0)))) {
            assertFalse(GpuGroundCover.grows(scene, scene.tile(center)));
        }
    }

    @Test
    void shallowWetlandBanksRemainOwnedByReeds() {
        var scene = BoardScenes.scene(c -> c.equals(BoardScenes.CENTER)
              ? BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, 0, 0)
              : new BoardScene.Tile(c, 0, -1, false, 0, BoardScene.Surface.GRASS, null, null,
                    List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE,
                    BoardScene.Biome.MARSH, false));
        var tile = scene.tile(BoardScenes.CENTER);
        assertEquals(BoardScene.Biome.MARSH, BoardBiome.plantKind(scene, tile));
        assertFalse(GpuGroundCover.grows(scene, tile), "New shallow grass cannot duplicate the existing wetland plants");
    }

    @Test
    void changingShallowDepthInvalidatesItsOwnAndNeighbouringGrassInputs() {
        var shallow = coast(BoardScene.Surface.GRASS, 0, false, BoardLiquid.WATER);
        var deep = coast(BoardScene.Surface.GRASS, 1, false, BoardLiquid.WATER);
        for (var at : List.of(BoardScenes.CENTER, BoardScenes.CENTER.translated(5))) {
            assertNotEquals(BoardVegetation.key(shallow, shallow.tile(at)), BoardVegetation.key(deep, deep.tile(at)),
                  "Depth changes grass eligibility and the adjacent bank fade");
        }
    }

    private static Vector3 waterVertex(BoardVegetation.Ground ground, Vector3 vertex) {
        Float height = ground.waterHeights().get(vertex);
        assertNotNull(height, "Finished shore vertices publish the same local water reference as their material");
        return new Vector3(vertex.x, vertex.y, height);
    }

    private static BoardScene coast(BoardScene.Surface land, int depth, boolean frozen, BoardLiquid liquid) {
        return BoardScenes.scene(c -> c.equals(BoardScenes.CENTER)
              ? new BoardScene.Tile(c, 0, depth, frozen, 0, BoardScene.Surface.GRASS, null, null,
                    List.of(), List.of(), liquid, null, true)
              : BoardScenes.tile(c, land, 0, -1, 0));
    }
}
