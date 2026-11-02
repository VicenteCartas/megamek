/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import megamek.client.lobby.MapSetupForm;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LobbyMapSetupControllerTest {
    @Test
    void inspectionUsesLiveLobbyConditionsAndReleasesItsSource(@TempDir Path directory) throws Exception {
        var boards = Configuration.boardsDir();
        BoardFile.save(Board.createEmptyBoard(6, 5), directory.resolve("inspection.board2"));
        MapSettings settings = MapSettings.getInstance();
        MapSetupForm form = new MapSetupForm(() -> settings, Set::of,
              mock(MapSetupForm.Commands.class), name -> "", List::of);
        Game game = new Game();
        var conditions = new PlanetaryConditions();
        conditions.setLight(Light.FULL_MOON);
        game.setPlanetaryConditions(conditions);
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1).setValue(true);
        LinkedBlockingQueue<BoardSource> loaded = new LinkedBlockingQueue<>();
        LobbyMapSetupController controller = new LobbyMapSetupController(form, () -> game,
              (setup, themes) -> { }, (key, image) -> { });
        try {
            Configuration.setBoardsDir(directory.toFile());
            SwingUtilities.invokeAndWait(() -> controller.inspect("inspection.board2", loaded::add));
            BoardSource source = loaded.poll(15, TimeUnit.SECONDS);
            assertNotNull(source);
            assertEquals(6, source.takeFrame().scene().width());
            assertEquals(0, BoardAtmosphere.lighting(source.takeFrame().scenarioAtmosphere()).daylight(), .001f);
            source.changeRuler(source.takeFrame().boardGeneration(), RulerModel::open);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("Diagrammed LOS", source.takeFrame().panels().los().mode());
            SwingUtilities.invokeAndWait(() -> {
                var updated = new PlanetaryConditions();
                updated.setLight(Light.DAY);
                game.setPlanetaryConditions(updated);
                game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1).setValue(false);
                controller.refresh();
            });
            assertTrue(BoardAtmosphere.lighting(source.takeFrame().scenarioAtmosphere()).daylight() > .9f,
                  "unchanged map settings still update the already-open inspection");
            assertEquals("Standard LOS", source.takeFrame().panels().los().mode(), "LOS follows the lobby's current rules");
            SwingUtilities.invokeAndWait(controller::dispose);
            assertTrue(source.isClosed(), "leaving the lobby releases the inspection's preference and board listeners");
        } finally {
            SwingUtilities.invokeAndWait(controller::dispose);
            Configuration.setBoardsDir(boards);
        }
    }

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
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(16, 17));
        LinkedBlockingQueue<BufferedImage> images = new LinkedBlockingQueue<>();
        AtomicInteger cleared = new AtomicInteger();
        LobbyMapSetupController controller = new LobbyMapSetupController(form, () -> game, (setup, themes) -> { },
              (key, image) -> {
                  if (image != null) {
                      images.add(image);
                  } else { cleared.incrementAndGet(); }
              });
        try {
            SwingUtilities.invokeAndWait(controller::refresh);
            assertImage(game.getBoard(), images.poll(10, TimeUnit.SECONDS));
            int clearsBeforeStack = cleared.get();
            SwingUtilities.invokeAndWait(() -> controller.act(value -> value.addToStack(0, List.of("another-board"))));
            assertEquals(clearsBeforeStack, cleared.get(), "adding alternatives must keep the generated preview");
            Board regenerated = Board.createEmptyBoard(16, 17);
            regenerated.getHex(4, 5).addTerrain(new Terrain(Terrains.WATER, 2));
            regenerated.getHex(7, 8).setLevel(4);
            SwingUtilities.invokeAndWait(() -> { game.setBoard(regenerated); controller.refresh(); });
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
        LobbyMapSetupController controller = new LobbyMapSetupController(form, Game::new, (setup, themes) -> { },
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
