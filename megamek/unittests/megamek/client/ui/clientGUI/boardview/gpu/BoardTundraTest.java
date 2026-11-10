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
        assertTrue(after.detailedGround());
        assertEquals(!List.of("mars", "lunar", "volcano", "fungus").contains(theme), GpuGroundCover.grows(scene, after),
              "Earth tundra grows its sward; blades must not obscure fungal mats or mineral crust");
        assertEquals(1, board.getHex(at).terrainLevel(Terrains.TUNDRA));
        assertEquals(theme, board.getHex(at).getTheme());
        for (var lod : TerrainLod.values()) {
            var top = new BoardSurface(scene, after, lod).faces.stream()
                  .filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            assertEquals(6, top.size(), "Crust detail belongs in the material, not extra flat terrain geometry");
        }
    }

    @ParameterizedTest
    @CsvSource({ "grass,true", "snow,true", "fungus,false", "lunar,false" })
    void tussocksGrowAsTuftsOfBladesOnlyOnEarthTundra(String theme, boolean earth) throws Exception {
        var scene = BoardScenes.capture(board(theme, true));
        int blades = 0;
        for (int x = 5; x < 8; x++) for (int y = 2; y < 6; y++) {
            var tile = scene.tile(new Coords(x, y));
            var support = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
            var plants = BoardPlants.plant(scene, tile, support, TerrainLod.FULL, List.of());
            var tussocks = plants == null ? null : plants.tussocks();
            if (tussocks == null) { continue; }
            blades += tussocks.size / 4;
            for (int i = 0; i < tussocks.size; i += 4) {
                assertTrue(Math.hypot(tussocks.items[i] - BoardGeometry.centerX(tile.coords()),
                      tussocks.items[i + 1] - BoardGeometry.centerY(tile.coords())) < BoardGeometry.width(),
                      "A tuft stands in or by its hex");
                assertTrue(i == 0 || (int) tussocks.items[i + 3] >= (int) tussocks.items[i - 1],
                      "Blades stay in rank order, so a prefix thins every tuft alike");
            }
        }
        assertEquals(earth, blades > 0, theme + " tundra's tussocks");
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

    @Test
    void grassRootsRespectTheSharedTundraBoundary() {
        var center = new Coords(4, 4);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.getX() >= 5 ? BoardScene.Biome.TUNDRA : BoardScene.Biome.NONE, 0));
        var tile = scene.tile(center);
        var support = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
        var roots = GpuGroundCover.plant(scene, tile, support, null);
        // A sward's root carries half a rank more than a meadow's (GpuGroundCover.plant).
        int meadow = 0, sward = 0;
        for (int i = 0; i < roots.size; i += 4) {
            float tundra = BoardBiome.coverage(scene, BoardScene.Biome.TUNDRA, roots.items[i], roots.items[i + 1], 0);
            if (roots.items[i + 3] % 1 < .5f) {
                meadow++;
                assertTrue(tundra < .45f, "Grass cannot fill the tundra side of the contact");
            } else {
                sward++;
                assertTrue(tundra > 0, "The sward grows only where the tundra reaches");
            }
            assertTrue(i == 0 || roots.items[i + 3] > roots.items[i - 1], "Roots stay in rank order");
        }
        assertTrue(meadow > 0, "The untreated meadow still grows grass");
        assertTrue(sward > 0, "The tundra's sward reaches into the meadow's hex");
        var tundraTile = scene.tile(new Coords(6, 4));
        var tundraRoots = GpuGroundCover.plant(scene, tundraTile,
              BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tundraTile), scene, -1), null);
        assertTrue(tundraRoots.size > 4 * GpuGroundCover.ROOTS_PER_HEX / 4, "Most of an earth tundra hex grows its sward");
        for (int i = 0; i < tundraRoots.size; i += 4) {
            assertTrue(tundraRoots.items[i + 3] % 1 >= .5f, "Inside tundra every root is the sward's");
        }
    }

    static Board board(String theme, boolean tundra) {
        var board = Board.createEmptyBoard(8, 6);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) {
            board.setHex(new Coords(x, y), new Hex(y < 2 ? 3 : 0, tundra && x >= 4 ? "tundra:1" : "", theme));
        }
        return board;
    }
}
