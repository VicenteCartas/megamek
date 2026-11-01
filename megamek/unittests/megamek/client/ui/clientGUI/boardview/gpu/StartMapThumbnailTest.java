/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;

import megamek.client.ui.util.PlayerColour;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioV1;
import org.junit.jupiter.api.Test;

/**
 * The briefing's map: its shape, each side's zone in its colour, which scenarios roll their map at random, how the
 * frame gives way in a short window, and that the sides take the player colours in order.
 */
class StartMapThumbnailTest {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;

    private static Board board() {
        Hex[] hexes = new Hex[WIDTH * HEIGHT];
        for (int i = 0; i < hexes.length; i++) {
            hexes[i] = new Hex(0);
        }
        return new Board(WIDTH, HEIGHT, hexes);
    }

    private static Player side(int id, int start, PlayerColour colour) {
        Player player = new Player(id, "Side " + id);
        player.setStartingPos(start);
        player.setColour(colour);
        return player;
    }

    @Test
    void theImageKeepsTheBoardsShapeAlongItsLongerSide() {
        BufferedImage image = StartMapThumbnail.bake(board(), List.of(), 200);
        assertEquals(200, image.getWidth());
        // 16 columns are 12.25 hexes wide, 8.5 rows are 8.5 * sqrt(3) / 2 high
        assertEquals(Math.round(200 * 8.5 * Math.sqrt(3) / 2 / 12.25), image.getHeight(), 1);
    }

    @Test
    void aSidesZoneIsTintedInItsColour() {
        Player west = side(1, Board.START_W, PlayerColour.RED);
        BufferedImage plain = StartMapThumbnail.bake(board(), List.of(), 400);
        BufferedImage zoned = StartMapThumbnail.bake(board(), List.of(west), 400);
        // The centre of hex (0, 3), in the west zone, turns redder; the board's middle doesn't change
        int inZoneX = (int) (.5 / 12.25 * 400);
        int inZoneY = (int) (3.5 * Math.sqrt(3) / 2 / 12.25 * 400);
        int middleX = zoned.getWidth() / 2;
        int middleY = zoned.getHeight() / 2;
        assertTrue(red(zoned.getRGB(inZoneX, inZoneY)) > red(plain.getRGB(inZoneX, inZoneY)));
        assertEquals(plain.getRGB(middleX, middleY), zoned.getRGB(middleX, middleY));
    }

    @Test
    void aZoneOverTheWholeBoardIsNotDrawn() {
        Board board = board();
        assertNull(StartMapThumbnail.zone(board, side(1, Board.START_ANY, PlayerColour.RED)));
        boolean[][] west = StartMapThumbnail.zone(board, side(2, Board.START_W, PlayerColour.RED));
        assertTrue(west[0][0]);
        assertFalse(west[WIDTH - 1][0]);
    }

    @Test
    void aMapIsRandomWhenASheetIsRandomOrLeftOut() {
        assertTrue(StartMapThumbnail.randomMap(v1("RANDOM,RANDOM", "2", "1")));
        assertTrue(StartMapThumbnail.randomMap(v1("rotate:RANDOM", null, null)));
        assertTrue(StartMapThumbnail.randomMap(v1("canyon", "1", "2")));
        assertTrue(StartMapThumbnail.randomMap(v1(null, null, null)));
        assertFalse(StartMapThumbnail.randomMap(v1("canyon,canyon2", "1", "2")));
        assertFalse(StartMapThumbnail.randomMap(mock(Scenario.class)));
    }

    @Test
    void theCaptionSaysTheMapsSize() {
        String caption = StartMapThumbnail.caption(gameOf(board()), v1("a", null, null));
        assertTrue(caption.contains("16") && caption.contains("8"), caption);
        assertFalse(caption.contains("{"), caption);
        assertTrue(StartMapThumbnail.caption(gameOf(board()), v1("RANDOM", null, null)).contains("\u00b7"));
    }

    @Test
    void theFrameIsSizedByTheColumnAlone() {
        assertEquals(StartMapFrame.MIN_HEIGHT, StartMapFrame.frameHeight(100));
        assertEquals(400 * StartMapFrame.HEIGHT_SHARE, StartMapFrame.frameHeight(400), .01);
        assertEquals(StartMapFrame.MAX_HEIGHT, StartMapFrame.frameHeight(1000));
    }

    @Test
    void inAShortWindowTheFrameShrinksFirstThenDropsOut() {
        assertEquals(200, StartMapFrame.fitted(200, 110, -50));
        assertEquals(150, StartMapFrame.fitted(200, 110, 50));
        assertEquals(110, StartMapFrame.fitted(200, 110, 90));
        assertEquals(0, StartMapFrame.fitted(200, 110, 91));
    }

    @Test
    void everySideTakesThePlayerColoursInOrder() {
        Player first = new Player(1, "First");
        Player second = new Player(2, "Second");
        Camouflage chosen = new Camouflage("Clans", "Wolf.png");
        second.setCamouflage(chosen);
        StartBriefingPage.colourSides(List.of(first, second));
        PlayerColour[] colours = PlayerColour.values();
        assertEquals(colours[0], first.getColour());
        assertEquals(colours[1], second.getColour());
        assertEquals(Camouflage.of(colours[0]), first.getCamouflage());
        // A camouflage the scenario chose stays
        assertEquals(chosen, second.getCamouflage());
    }

    private static Game gameOf(Board board) {
        Game game = new Game();
        game.setBoardDirect(board);
        return game;
    }

    private static ScenarioV1 v1(String maps, String boardWidth, String boardHeight) {
        ScenarioV1 scenario = mock(ScenarioV1.class);
        when(scenario.getString("Maps")).thenReturn(maps);
        when(scenario.getString("BoardWidth")).thenReturn(boardWidth);
        when(scenario.getString("BoardHeight")).thenReturn(boardHeight);
        return scenario;
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }
}
