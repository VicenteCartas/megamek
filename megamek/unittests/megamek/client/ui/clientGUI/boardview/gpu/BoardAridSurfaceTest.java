/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardAridSurfaceTest {
    @ParameterizedTest
    @CsvSource({ "desert,sand:1;rough:1,DESERT", "desert,'',DESERT", "mars,sand:1,MARS", "grass,sand:1;swamp:1,GRASS",
          "mars,sand:1;building:1;bldg_cf:50;bldg_elev:1,MARS", "volcano,sand:1;rough:1,VOLCANO",
          "volcano,'',VOLCANO", "lunar,sand:1,LUNAR" })
    void authoredSandCombinesWithTheThemeAndOtherTerrainThroughNativeCapture(
          String theme, String terrains, BoardScene.Surface geology) throws Exception {
        var board = Board.createEmptyBoard(1, 1);
        var at = new Coords(0, 0);
        var hex = new Hex(2, terrains, theme, at);
        board.setHex(at, hex);
        var scene = BoardScenes.capture(board);
        var tile = scene.tile(at);
        boolean sand = hex.containsTerrain(Terrains.SAND);
        assertTrue(tile.detailedGround(), "Native ground must replace old tile artwork for " + terrains);
        assertEquals(geology, tile.surface());
        assertTrue(sand ? tile.groundCover().sand() > .9f : tile.groundCover().sand() == 0);
        assertTrue(tile.groundCover().weight(geology) > 0, "The sand palette must retain its local substrate");
        var top = BoardGeometry.center(at, 2);
        assertEquals(tile.groundCover().sand(), BoardSurfaceBlend.sample(scene, tile, top.x, top.y, top.z).sand());
        if (sand) { assertTrue(BoardSurfaceBlend.boundary(scene, tile), "The renderer must install the sand cover"); }
        // Board-edge cliffs take the fallback path too: loose deposits must not replace their supporting rock.
        var outside = top.cpy().add(BoardGeometry.width(), 0, -BoardRelief.metres(3));
        assertEquals(1, BoardSurfaceBlend.sampleCliff(scene, tile, outside.x, outside.y, outside.z).weight(geology));
        if (hex.containsTerrain(Terrains.ROUGH)) {
            assertTrue(tile.features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
        }
        assertEquals(hex.containsTerrain(Terrains.SWAMP) ? BoardScene.Biome.MARSH : BoardScene.Biome.NONE, tile.biome());
        assertEquals(theme, hex.getTheme());
        assertEquals(sand, hex.containsTerrain(Terrains.SAND), "Capture never rewrites gameplay terrain");
    }

    @ParameterizedTest
    @ValueSource(strings = { "grass", "desert", "mars", "rock", "lunar", "dirt", "volcano" })
    void applyingSharedSandChangesTheMaterialWithoutAddingFlatTerrainTriangles(String theme) throws Exception {
        var board = Board.createEmptyBoard(3, 3);
        var center = new Coords(1, 1);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            board.setHex(new Coords(x, y), new Hex(0, "", theme));
        }
        var before = BoardScenes.capture(board);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            board.setHex(new Coords(x, y), new Hex(0, "sand:1", theme));
        }
        var after = BoardScenes.capture(board);
        var tile = after.tile(center);
        assertEquals(before.tile(center).surface(), tile.surface(), "The theme's geology is unchanged");
        assertFalse(before.tile(center).sameGeometry(tile), "Editing SAND must invalidate cached material data");
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(after, tile, lod);
            var tops = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            var ground = GpuSurfaceBlend.prepare(after, tile, tops, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(1, 0, 0, .3f).setUV(99, 99), GpuSurfaceBlend.spacing(lod));
            assertEquals(6, ground.values().stream().mapToInt(List::size).sum(), theme + " at " + lod);
            assertEquals(1, ground.size(), "One shared material group for homogeneous SAND");
            assertTrue(ground.values().stream().flatMap(List::stream)
                  .flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                  .allMatch(p -> p.cover().sand() > .9f && p.cover().weight(tile.surface()) > 0));
        }
    }

    @Test
    void grassGrowsOnlyInExposedTurfPatchesAcrossSandyHexes() throws Exception {
        var board = Board.createEmptyBoard(5, 5);
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
            board.setHex(new Coords(x, y), new Hex(0, "sand:1", "grass"));
        }
        var scene = BoardScenes.capture(board);
        int roots = 0;
        float metre = BoardRelief.metres(1);
        for (var at : List.of(new Coords(2, 2), new Coords(2, 3), new Coords(3, 2))) {
            var tile = scene.tile(at);
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
            var planted = GpuGroundCover.plant(scene, tile, surface, null);
            assertEquals(planted, GpuGroundCover.plant(scene, tile, surface, null), "Rebuilding must not move the tufts");
            for (int i = 0; i < planted.size; i += 4) {
                float x = planted.items[i], y = planted.items[i + 1];
                assertTrue(BoardSurfaceBlend.sandExposure(x / metre, y / metre) > .5f,
                      "Every root needs an exposed turf window, not merely a SAND hex with grass underneath");
                roots++;
            }
        }
        assertTrue(roots > 100 && roots < 3000, "Sparse living patches without turning sand into a meadow: " + roots);
    }
}
