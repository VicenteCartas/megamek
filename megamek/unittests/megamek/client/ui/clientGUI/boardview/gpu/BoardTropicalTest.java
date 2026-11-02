/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTropicalTest {
    /** The real map as a map preview loads it: converted, so its transitions are drawn by their ground designs. */
    static Board racice() {
        return GpuBoardWindow.loadPreview(new File("data/boards/Battle of Tukayyid Pack/32x17 Racice River Delta (CSJ).board"));
    }

    @Test
    void raciceCapturesTropicalWoodsAndAuthoredDesertMixturesWithoutChangingTheMap() throws Exception {
        var board = racice();
        var scene = BoardAridSurfaceTest.capture(board);
        assertEquals(32, scene.width());
        assertEquals(17, scene.height());
        int tropical = 0, palms = 0, broadleaf = 0;
        for (var tile : scene.tiles()) {
            var hex = board.getHex(tile.coords());
            if (!"tropical".equals(hex.getTheme())) { continue; }
            tropical++;
            assertEquals(BoardScene.Surface.TROPICAL, tile.surface());
            assertTrue(tile.detailedGround(), "Native terrain at " + tile.coords());
            assertNull(tile.decals(), "Legacy transition artwork cannot hide the tropical material");
            assertEquals(.5f, tile.groundCover().tropical(), .00001f);
            assertEquals(.5f, tile.groundCover().desert(), .00001f);
            assertEquals(0, tile.groundCover().grass());
            long canopy = tile.features().stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE && f.height() >= 2).count();
            assertTrue(canopy > 0 && canopy <= BoardTreeCoverage.limit(1, false));
            assertEquals(canopy, tile.features().stream().filter(f -> f.asset().startsWith("vegetation/foliage-jungle")).count());
            palms += (int) tile.features().stream().filter(f -> f.asset().startsWith("vegetation/palm")).count();
            broadleaf += (int) tile.features().stream().filter(f -> f.asset().startsWith("vegetation/tree")).count();
            assertFalse(tile.features().stream().anyMatch(f -> f.asset().startsWith("vegetation/pine")));
            assertEquals(1, hex.terrainLevel(Terrains.WOODS));
            assertEquals(2, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
            assertEquals(3, hex.getTerrain(Terrains.GROUND_FLUFF).getExits());
            assertFalse(hex.containsTerrain(Terrains.JUNGLE), "The theme must not invent jungle rules");
        }
        assertEquals(24, tropical);
        assertTrue(palms > 0 && broadleaf > 0, "Natural stands retain the tropical mixture across the woodland");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void tropicalCanopyAndUnderstoryKeepDensityHeightAndRoadClearance(int density) {
        var at = new Coords(3, 4);
        for (String terrain : new String[] { "woods", "jungle" }) {
            var hex = new Hex(3, terrain + ":" + density + ";foliage_elev:2;road:1:9", "TROPICAL", at);
            var trees = BoardFeatures.capture(hex, at, Map.of());
            var road = BoardRoad.clearance(at, hex, c -> null);
            long canopy = trees.stream().filter(f -> f.height() >= 2).count();
            assertTrue(canopy > 0 && canopy <= BoardTreeCoverage.limit(density, false));
            assertTrue(trees.stream().filter(f -> f.asset().startsWith("vegetation/foliage-jungle")).count() <= canopy);
            assertEquals(trees, BoardFeatures.capture(hex, at, Map.of()), "Capture is deterministic");
            for (var tree : trees) {
                assertTrue(road.distance(tree.x(), tree.y()) >= BoardRoad.SHOULDER + 2);
                assertTrue(tree.asset().startsWith("vegetation/foliage-jungle") ? tree.height() < 1 : tree.height() <= 2.2f);
            }
            var low = new Hex(0, terrain + ":" + density + ";foliage_elev:1", "tropical", at);
            var shrubs = BoardFeatures.capture(low, at, Map.of());
            assertTrue(!shrubs.isEmpty() && shrubs.size() <= BoardTreeCoverage.limit(density, false));
            assertTrue(shrubs.stream().allMatch(f -> f.asset().startsWith("vegetation/foliage-jungle") && f.height() <= 1.1f));
        }
    }

    @Test
    void authoredMixtureReachesTheGpuPaletteAndVertices() throws Exception {
        var board = Board.createEmptyBoard(3, 3);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            board.setHex(new Coords(x, y), new Hex(0, "ground_fluff:1:3", "tropical"));
        }
        // As every load site converts a legacy board: the transition becomes its ground design.
        BoardSceneryLayouts.convert(board);
        var scene = BoardAridSurfaceTest.capture(board);
        var tile = scene.tile(new Coords(1, 1));
        var surface = new BoardSurface(scene, tile);
        var faces = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
        var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> new MeshPartBuilder.VertexInfo()
              .setPos(p).setNor(Vector3.Z).setCol(1, 0, 0, .3f).setUV(99, 99));
        assertFalse(groups.isEmpty());
        for (var entry : groups.entrySet()) {
            assertEquals(BoardScene.Surface.TROPICAL.ordinal(), entry.getKey().base());
            assertEquals(BoardScene.Surface.DESERT.ordinal(), entry.getKey().first());
            assertFalse(entry.getKey().volcanic());
            for (var triangle : entry.getValue()) {
                assertEquals(.5f, triangle.a().cover().tropical(), .00001f);
                assertEquals(.5f, triangle.a().cover().desert(), .00001f);
            }
        }
    }

    @Test
    void tropicalMaterialIsIndependentOfGrassAndKeepsExistingTerrainTreatments() {
        for (int strength = 1; strength <= 5; strength++) {
            var hex = new Hex(0, "ground_fluff:3:" + strength, "desert");
            var cover = BoardSurfaceBlend.capture(hex);
            assertEquals(strength / 6f, cover.tropical(), .00001f);
            assertEquals(1, cover.tropical() + cover.desert(), .00001f);
            assertEquals(0, cover.grass());
            assertEquals(0, cover.crust() + cover.bank());
        }
        assertEquals(BoardScene.Surface.SNOW, BoardFeatures.surface(new Hex(0, "snow:1", "tropical")));
        assertEquals(BoardScene.Surface.CONCRETE, BoardFeatures.surface(new Hex(0, "pavement:1", "tropical")));
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(new Hex(0, "magma:1", "tropical")));
        var sand = BoardSurfaceBlend.capture(new Hex(0, "sand:1", "tropical"));
        assertEquals(.94f, sand.sand(), .00001f);
        assertEquals(.06f, sand.tropical(), .00001f);
        var snowy = BoardFeatures.capture(new Hex(0, "snow:1;woods:1;foliage_elev:2", "tropical"), new Coords(0, 0), Map.of());
        assertTrue(snowy.stream().allMatch(f -> f.asset().endsWith("-snow")));
        var clear = BoardFeatures.capture(new Hex(0, "", "tropical"), new Coords(0, 0), Map.of());
        assertTrue(clear.stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
    }
}
