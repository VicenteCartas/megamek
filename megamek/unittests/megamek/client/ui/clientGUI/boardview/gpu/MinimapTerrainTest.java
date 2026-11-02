/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardModelIds;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class MinimapTerrainTest {
    @Test
    void bothThumbnailsMatchTheGameForLegacyAndNativeTerrain() {
        Board board = variedBoard();
        checkColours(board);
        BoardSceneryLayouts.convert(board);
        checkColours(board);
    }

    private static void checkColours(Board board) {
        List<BoardScene.Tile> scene = BoardTiles.captureAll(board);
        BufferedImage lobby = LobbyMapThumbnail.bake(board, List.of(), null, 720);
        BufferedImage briefing = StartMapThumbnail.bake(board, List.of(), 720);
        assertArrayEquals(lobby.getRGB(0, 0, lobby.getWidth(), lobby.getHeight(), null, 0, lobby.getWidth()),
              briefing.getRGB(0, 0, briefing.getWidth(), briefing.getHeight(), null, 0, briefing.getWidth()),
              "The briefing and lobby use exactly the same terrain layer");
        double unit = 720 / Math.max(board.getWidth() * .75 + .25, (board.getHeight() + .5) * Math.sqrt(3) / 2);
        for (BoardScene.Tile tile : scene) {
            Coords at = tile.coords();
            MinimapTerrain direct = MinimapTerrain.capture(board.getHex(at));
            assertEquals(tile.minimap(), direct, "The lightweight capture matches the scene at " + at);
            int rgba = GpuMinimap.colour(tile);
            int argb = 0xFF000000 | rgba >>> 8;
            int px = (int) ((.5 + .75 * at.getX()) * unit);
            int py = (int) ((at.getY() + .5 + (at.getX() % 2) * .5) * Math.sqrt(3) / 2 * unit);
            assertEquals(argb, lobby.getRGB(px, py), "The thumbnail paints the in-game colour at " + at);
        }
    }

    @Test
    void thumbnailsDoNotResolveAssetsOrScenery() {
        Board board = variedBoard();
        board.getHex(board.getWidth() - 2, 0).setAppearance(Map.of("building",
              new HexAppearance(null, "buildings/thumbnail-must-ignore-this", null, null)));
        try (var artwork = mockConstruction(BoardArtwork.class);
              var tileset = mockStatic(TilesetScenery.class);
              var scenery = mockStatic(BoardSceneryLayouts.class);
              var models = mockStatic(BoardModelIds.class);
              var blueprint = mockStatic(BoardEditorBlueprint.class)) {
            LobbyMapThumbnail.bake(board, List.of(), null, 360);
            StartMapThumbnail.bake(board, List.of(), 360);
            assertTrue(artwork.constructed().isEmpty(), "Flat previews must not enter the artwork/scene capture path");
            tileset.verifyNoInteractions();
            scenery.verifyNoInteractions();
            models.verifyNoInteractions();
            blueprint.verifyNoInteractions();
        }
    }

    @Test
    void missingBuildingArtworkDoesNotChangeTheGameMinimap() {
        Coords at = new Coords(0, 0);
        Hex hex = new Hex(0, "building:1:0;bldg_cf:15;bldg_elev:1", "", at);
        hex.setAppearance(Map.of("building", new HexAppearance(null, "buildings/missing", null, null)));
        var art = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
        var tile = BoardScene.captureTile(hex, art, null, new BoardScene.PixelPool(), coords -> null);
        assertFalse(tile.building(), "The scene has no resolved building model");
        assertEquals(0xB0ADA6FF, GpuMinimap.colour(tile), "Map terrain still identifies the building");
        assertEquals(MinimapTerrain.capture(hex), tile.minimap());
        assertEquals(0xB0ADA6FF, GpuMinimap.colour(tile.lunar(false)));
        assertEquals(0xB0ADA6FF, GpuMinimap.colour(tile.lunar(true)));
    }

    @Test
    void thePaletteKeepsItsTerrainPrecedenceAndElevationLimits() {
        assertEquals(0x7E8D41FF, colour(0, ""));
        assertEquals(0x4E7A34FF, colour(0, "woods:1"));
        assertEquals(0x2C4E1EFF, colour(0, "woods:3"));
        assertEquals(0x3E7A3AFF, colour(0, "jungle:2;woods:1"));
        assertEquals(0x4A8CA6FF, colour(0, "water:0;woods:3"));
        assertEquals(0x33688FFF, colour(0, "water:2;woods:3"));
        assertEquals(0xC8DCEBFF, colour(0, "water:2;ice:1;magma:2"));
        assertEquals(0xE0581EFF, colour(0, "magma:2;water:2"));
        assertEquals(0x5A2A1CFF, colour(0, "magma:1"));
        assertEquals(0xA8A050FF, colour(0, "planted_fields:1;rough:1"));
        assertEquals(0x8F8160FF, colour(0, "rough:1"));
        assertEquals(0xB0ADA6FF, colour(0, "building:5:9;bldg_cf:120;bldg_elev:1;woods:3"));
        assertNotEquals(colour(0, "water:2"), colour(0, "water:2;hazardous_liquid:1"));
        assertNotEquals(colour(0, ""), colour(0, "road:1:9"));
        assertNotEquals(colour(0, "water:1"), colour(0, "water:1;bridge:1:9;bridge_cf:15;bridge_elev:1"));
        assertEquals(colour(6, ""), colour(100, ""));
        assertEquals(colour(-6, ""), colour(-100, ""));
    }

    @Test
    void anEditReplacesTheSnapshotAndCosmeticOverlaysRetainIt() {
        Board board = new Board(1, 1, new Hex(0));
        Coords at = new Coords(0, 0);
        try (BoardTiles tiles = new BoardTiles(new BoardScene.PixelPool())) {
            BoardScene.Tile dry = tiles.tiles(board, (coords, art) -> { }).getFirst();
            var cosmetic = dry.withFeatures(List.of(new BoardScene.Feature("rough-boulder", 0, 0, 0, 1, 1, 0,
                  BoardScene.FeatureKind.BOULDER)));
            assertSame(dry.minimap(), cosmetic.minimap(), "Presentation overlays do not become terrain");
            assertEquals(0x7E8D41FF, GpuMinimap.colour(cosmetic));
            board.getHex(at).addTerrain(new Terrain(Terrains.WATER, 2));
            tiles.changed(at);
            BoardScene.Tile wet = tiles.tiles(board, (coords, art) -> { }).getFirst();
            assertEquals(0x33688FFF, GpuMinimap.colour(wet));
            assertEquals(MinimapTerrain.capture(board.getHex(at)), wet.minimap());
            assertEquals(0x7E8D41FF, GpuMinimap.colour(dry), "The published snapshot is immutable");
        }
    }

    @Test
    void airlessPresentationStillUpdatesTheMinimap() {
        var water = BoardTiles.captureAll(new Board(1, 1, new Hex(0, "water:2", ""))).getFirst();
        assertEquals(0xC8DCEBFF, GpuMinimap.colour(water.lunar(false)), "Water freezes in vacuum");
        var weightless = water.lunar(true);
        assertEquals(colour(-2, "", "lunar"), GpuMinimap.colour(weightless), "Without gravity the bed is exposed");
        assertEquals(weightless.minimap(), weightless.lunar(true).minimap());
    }

    private static int colour(int elevation, String terrain) { return colour(elevation, terrain, ""); }

    private static int colour(int elevation, String terrain, String theme) {
        return MinimapTerrain.capture(new Hex(elevation, terrain, theme)).colour();
    }

    private static Board variedBoard() {
        List<String> terrains = List.of("", "woods:1", "woods:2", "woods:3", "jungle:2", "water:0",
              "water:3;woods:2", "water:1;ice:1", "water:2;hazardous_liquid:1", "magma:1", "magma:2",
              "pavement:1", "road:1:9", "road:2:9", "road:3:9", "rough:1;road:1:9", "rough:2", "planted_fields:1",
              "swamp:1", "swamp:2", "mud:1", "tundra:1", "snow:1", "pavement:1;fluff:6:0",
              "building:5:9;bldg_cf:120;bldg_elev:1;rough:1", "water:1;bridge:1:9;bridge_cf:15;bridge_elev:1");
        List<String> themes = List.of("", "snow", "mars", "fungus", "volcano");
        Hex[] hexes = new Hex[terrains.size() * themes.size()];
        for (int y = 0; y < themes.size(); y++) {
            for (int x = 0; x < terrains.size(); x++) {
                hexes[y * terrains.size() + x] = new Hex(y - 2, terrains.get(x), themes.get(y), new Coords(x, y));
            }
        }
        return new Board(terrains.size(), themes.size(), hexes);
    }
}
