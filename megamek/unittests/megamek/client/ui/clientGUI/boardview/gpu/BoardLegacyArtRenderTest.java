/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Batch 10, what the views draw for legacy art the conversion took: a hex design draws exactly what its token drew,
 * in 3D (the one capture, Tactical tile and ground artwork included) and in 2D; an elevator's level plate is a decal of
 * its terrain, drawn once.
 */
class BoardLegacyArtRenderTest {
    private static final Coords AT = new Coords(2, 2);

    private static Board converted(String terrain) {
        Board board = Board.createEmptyBoard(5, 5);
        board.setHex(AT, new Hex(0, terrain, ""));
        BoardSceneryLayouts.convert(board);
        return board;
    }

    /** The tile of {@code hex} at {@link #AT} on {@code board}, drawn as given: the token's own art, today's picture. */
    private static BoardScene.Tile drawnAsGiven(Board board, Hex hex) {
        try (var artwork = new BoardArtwork()) {
            return BoardScene.captureTile(hex, artwork.capture(board, AT, hex), null, new BoardScene.PixelPool(), board::getHex);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "ground_fluff:1:3", "grass:1;ground_fluff:4:5", "road:1:09;road_fluff:1",
          "road:1:18;road_fluff:2", "woods:1;fluff:12", "rough:1;fluff:1", "rough:2;fluff:2",
          "building:1;bldg_elev:2;bldg_cf:15;fluff:11:7", "building:2;bldg_elev:1;bldg_cf:30;bldg_fluff:29",
          "pavement:1;ground_fluff:2:2", "water:2:63;fluff:100:6" })
    void aDesignDrawsExactlyWhatItsTokenDrew(String terrain) throws Exception {
        Board game = converted(terrain);
        // Today's picture: the hex with its token and without the design that now stands for it.
        Hex token = game.getHex(AT).duplicate();
        token.setAppearance(Map.of());
        var today = drawnAsGiven(game, token);
        // The game draws the hex without its taken token (BoardTiles), the .board2 holds it so.
        var drawn = BoardTiles.captureAll(game).get(AT.getX() * game.getHeight() + AT.getY());
        var text = new ByteArrayOutputStream();
        BoardFile.write(game, text);
        Board saved = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        var reloaded = BoardTiles.captureAll(saved).get(AT.getX() * saved.getHeight() + AT.getY());
        for (RecordComponent component : BoardScene.Tile.class.getRecordComponents()) {
            if (component.getName().equals("appearance")) { continue; }
            Object expected = component.getAccessor().invoke(today);
            assertEquals(expected, component.getAccessor().invoke(drawn), component.getName());
            assertEquals(expected, component.getAccessor().invoke(reloaded), component.getName() + " of the .board2");
        }
    }

    @Test
    void theTwoDBoardDrawsADesignAsItsToken() {
        Game game = new Game();
        var tileset = new HexTileset(game);
        try {
            tileset.loadFromFile("saxarba.tileset");
            // The quay art has selector designs too; building art has none (BoardSceneryLayouts.takenTokens). A container
            // yard look draws the first token of its rotation: each rotation group's first token draws itself.
            List<String> terrains = new java.util.ArrayList<>(List.of("ground_fluff:1:3", "woods:1;fluff:12",
                  "rough:1;fluff:2", "road:1:09;road_fluff:1", "water:2:63;fluff:100:6", "water:3;fluff:100:1", "pavement:1;fluff:100:2"));
            for (String yard : List.of("80:01", "80:08", "80:16", "80:23", "81:01", "81:04", "81:07", "81:10", "81:13", "81:16",
                  "81:19", "81:22", "81:25", "81:28", "81:31", "81:34")) {
                terrains.add("pavement:1;woods:1;foliage_elev:2;fluff:" + yard);
            }
            for (String terrain : terrains) {
                Board board = converted(terrain);
                Hex token = board.getHex(AT).duplicate();
                token.setAppearance(Map.of());
                Hex design = BoardSceneryLayouts.withoutTakenTokens(board.getHex(AT));
                assertFalse(design.getAppearance().isEmpty(), terrain);
                Object[] expected = tileset.assignMatch(token), actual = tileset.assignMatch(design);
                assertEquals(expected[0], actual[0], terrain + ": base");
                assertEquals(expected[1], actual[1], terrain + ": supers");
                assertEquals(expected[2], actual[2], terrain + ": orthographic");
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            tileset.close();
        }
    }

    @Test
    void anElevatorsPlateIsADecalOfItsTerrainFollowingItsLevelAndDrawnOnce() {
        for (int level : new int[] { 3, 1 }) {
            Board board = Board.createEmptyBoard(5, 5);
            board.setHex(AT, new Hex(0, "pavement:1;solaris_elevator:" + level, ""));
            var tile = BoardTiles.captureAll(board).get(AT.getX() * board.getHeight() + AT.getY());
            var plates = tile.features().stream().filter(feature -> feature.decoration() != null).toList();
            assertEquals(1, plates.size(), tile.features().toString());
            var plate = plates.getFirst().decoration();
            assertEquals("decal/elevator/level-" + level, plate.asset());
            assertEquals("decal", plate.kind());
            assertTrue(BoardFeatures.terrainOwned(plate), "Never picked as an object");
            assertTrue(board.getHex(AT).getDecorations().isEmpty(), "The hex stores no object");
            assertTrue(tile.features().stream().noneMatch(feature -> feature.asset().startsWith("decal/")
                  && feature.decoration() == null), "No mesh named after the decal");
            assertNull(tile.tilesetScenery(), "The Tactical View draws the plate once, as the decal");
        }
    }
}
