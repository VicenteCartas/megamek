/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTundraTest {
    static final List<String> THEMES = List.of("grass", "dirt", "desert", "rock", "snow", "tropical",
          "mars", "lunar", "volcano", "fungus");

    @ParameterizedTest
    @ValueSource(strings = { "grass", "dirt", "desert", "rock", "snow", "tropical", "mars", "lunar", "volcano", "fungus" })
    void capturedTundraRetainsThemeGeologyAndInvalidatesEditedGround(String theme) throws Exception {
        var board = Board.createEmptyBoard(1, 1);
        var at = new Coords(0, 0);
        board.setHex(at, new Hex(2, "", theme));
        var before = BoardScenes.capture(board).tile(at);
        board.setHex(at, new Hex(2, "tundra:1", theme));
        var scene = BoardScenes.capture(board);
        var after = scene.tile(at);
        assertEquals(before.surface(), after.surface());
        assertEquals(before.groundCover(), after.groundCover());
        assertEquals(BoardScene.Biome.TUNDRA, BoardBiome.kind(after));
        assertFalse(before.sameGeometry(after), "Editing tundra must invalidate captured ground/plant data");
        assertEquals(1, board.getHex(at).terrainLevel(Terrains.TUNDRA));
        assertEquals(theme, board.getHex(at).getTheme());
        for (var lod : TerrainLod.values()) {
            var top = new BoardSurface(scene, after, lod).faces.stream()
                  .filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            assertEquals(6, top.size(), "Crust detail belongs in the material, not extra flat terrain geometry");
        }
    }

    @ParameterizedTest
    @CsvSource({ "water:1,NONE", "ice:1,NONE", "pavement:1,TUNDRA", "magma:1,NONE",
          "snow:1,NONE", "sand:1,NONE", "swamp:1,MARSH", "swamp:2,QUICKSAND", "mud:1,MUD", "planted_fields:1,FIELD" })
    void existingCoverKeepsItsPrecedence(String cover, BoardScene.Biome expected) {
        assertEquals(expected, BoardFeatures.biome(new Hex(0, "tundra:1;" + cover, "fungus")));
    }

    @Test
    void connectedCrustHasNoHexSeamsAndIsolatedBankEventuallyFades() {
        var center = new Coords(4, 4);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.TUNDRA, 2));
        for (int edge = 0; edge < 6; edge++) {
            var p = BoardGeometry.corner(center, 2, edge);
            assertEquals(1, BoardBiome.coverage(scene, BoardScene.Biome.TUNDRA, p.x, p.y, p.z), .00001);
            assertTrue(BoardBiome.coverage(scene, BoardScene.Biome.TUNDRA,
                  p.x, p.y, p.z - BoardRelief.metres(.8f)) > .4f, "Mats continue below the rim");
            assertEquals(0, BoardBiome.coverage(scene, BoardScene.Biome.TUNDRA,
                  p.x, p.y, p.z - Math.max(BoardGeometry.level() * 1.5f, BoardRelief.metres(4))), .00001);
        }
    }

    @Test
    void coveredTerracesConnectAcrossOneAndTwoLevelBanks() {
        var high = new Coords(4, 3);
        var low = high.translated(3);
        for (int levels : new int[] { 1, 2 }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.TUNDRA,
                  c.getY() <= high.getY() ? levels : 0));
            float x = (BoardGeometry.centerX(high) + BoardGeometry.centerX(low)) * .5f;
            float y = (BoardGeometry.centerY(high) + BoardGeometry.centerY(low)) * .5f;
            for (float part : new float[] { .25f, .5f, .75f }) {
                assertEquals(1, BoardBiome.coverage(scene, BoardScene.Biome.TUNDRA,
                      x, y, BoardGeometry.level() * levels * part), .001, "No green band between tundra terraces");
            }
        }
    }

    @Test
    void concreteKeepsItsSurfaceAndSupportsLichenWithoutWetlandSoil() throws Exception {
        var board = Board.createEmptyBoard(1, 1);
        var at = new Coords(0, 0);
        board.setHex(at, new Hex(0, "pavement:1;tundra:1", "grass"));
        var tile = BoardScenes.capture(board).tile(at);
        assertEquals(BoardScene.Surface.CONCRETE, tile.surface());
        assertEquals(BoardScene.Biome.TUNDRA, BoardBiome.kind(tile));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(new Hex(0, "pavement:1;swamp:1", "grass")));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(new Hex(0, "pavement:1;tundra:1;snow:1", "grass")));
    }

    static Board board(String theme, boolean tundra) {
        var board = Board.createEmptyBoard(8, 6);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) {
            board.setHex(new Coords(x, y), new Hex(y < 2 ? 3 : 0, tundra && x >= 4 ? "tundra:1" : "", theme));
        }
        return board;
    }
}
