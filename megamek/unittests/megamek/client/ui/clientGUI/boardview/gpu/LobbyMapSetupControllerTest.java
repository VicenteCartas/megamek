/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.lobby.MapSetupForm;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.Coords;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;

class LobbyMapSetupControllerTest {
    @Test
    void sheetsUseTheServersTerrainAndAccountForOddWidthPadding() {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(3, 2);
        settings.setMapSize(2, 2);
        Board[] sheets = new Board[4];
        for (int slot = 0; slot < sheets.length; slot++) {
            sheets[slot] = Board.createEmptyBoard(3, 2);
            sheets[slot].getHex(1, 1).setLevel(slot + 1);
        }
        Board assembled = BoardUtilities.combine(3, 2, 2, 2, sheets, MapSettings.MEDIUM_GROUND, 3);
        assembled.setNativeFormat(true);
        for (int slot = 0; slot < sheets.length; slot++) {
            Board sheet = LobbyMapSetupController.sheet(assembled, settings, slot);
            assertNotNull(sheet);
            assertEquals(slot + 1, sheet.getHex(1, 1).getLevel());
            assertEquals(new Coords(1, 1), sheet.getHex(1, 1).getCoords());
            assertEquals(assembled.isNativeFormat(), sheet.isNativeFormat());
            assertNotSame(sheets[slot].getHex(1, 1), sheet.getHex(1, 1));
        }
        assertNull(LobbyMapSetupController.sheet(new Board(), settings, 0), "an invalidated battlefield has no preview");
    }

    @Test
    void generatedPicturesRefreshWhenTheServerRegeneratesTheSameSetup() throws Exception {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
        MapSetupForm form = new MapSetupForm(() -> settings, () -> Set.of(new BoardDimensions(16, 17)),
              mock(MapSetupForm.Commands.class), name -> "", List::of);
        AtomicReference<Board> board = new AtomicReference<>(Board.createEmptyBoard(16, 17));
        LinkedBlockingQueue<BufferedImage> images = new LinkedBlockingQueue<>();
        LobbyMapSetupController controller = new LobbyMapSetupController(form, board::get, (setup, themes) -> { },
              (key, image) -> {
                  if (image != null) {
                      images.add(image);
                  }
              });
        try {
            SwingUtilities.invokeAndWait(controller::refresh);
            assertImage(board.get(), images.poll(10, TimeUnit.SECONDS));
            Board regenerated = Board.createEmptyBoard(16, 17);
            regenerated.getHex(4, 5).addTerrain(new Terrain(Terrains.WATER, 2));
            regenerated.getHex(7, 8).setLevel(4);
            board.set(regenerated);
            SwingUtilities.invokeAndWait(controller::refresh);
            assertImage(regenerated, images.poll(10, TimeUnit.SECONDS));
        } finally {
            SwingUtilities.invokeAndWait(controller::dispose);
        }
    }

    @Test
    void scrollingPastQueuedRowsPrioritizesTheNewVisibleBoards() throws Exception {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        MapSetupForm form = new MapSetupForm(() -> settings, () -> Set.of(new BoardDimensions(16, 17)),
              mock(MapSetupForm.Commands.class), name -> "", List::of);
        LinkedBlockingQueue<String> painted = new LinkedBlockingQueue<>();
        LobbyMapSetupController controller = new LobbyMapSetupController(form, Board::new, (setup, themes) -> { },
              (key, image) -> painted.add(key));
        try {
            SwingUtilities.invokeAndWait(() -> {
                controller.previews(List.of("scroll-test-top-1", "scroll-test-top-2", "scroll-test-top-3"));
                controller.previews(List.of("scroll-test-bottom-1", "scroll-test-bottom-2"));
            });
            for (String expected : List.of("scroll-test-top-1", "scroll-test-bottom-1", "scroll-test-bottom-2")) {
                assertEquals(LobbyMapSetupController.pictureKey(expected, false, 16, 17, null, 0),
                      painted.poll(10, TimeUnit.SECONDS), "only the in-flight thumbnail may precede the new viewport");
            }
            SwingUtilities.invokeAndWait(() -> controller.previews(List.of()));
            assertNull(painted.poll(), "offscreen rows are not left in a background generation queue");
            // Returning to a previously skipped row still requests it; cancelling demand did not mark it cached.
            SwingUtilities.invokeAndWait(() -> controller.previews(List.of("scroll-test-top-2")));
            assertEquals(LobbyMapSetupController.pictureKey("scroll-test-top-2", false, 16, 17, null, 0),
                  painted.poll(10, TimeUnit.SECONDS));
        } finally {
            SwingUtilities.invokeAndWait(controller::dispose);
        }
    }

    private static void assertImage(Board board, BufferedImage actual) {
        assertNotNull(actual, "the generated slot receives a thumbnail");
        BufferedImage expected = LobbyMapThumbnail.bake(board, List.of(), null, LobbyMapSetupController.PICTURE_SIDE);
        int width = expected.getWidth(), height = expected.getHeight();
        assertEquals(width, actual.getWidth());
        assertEquals(height, actual.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
              actual.getRGB(0, 0, width, height, null, 0, width), "the preview uses the actual generated terrain");
    }
}
