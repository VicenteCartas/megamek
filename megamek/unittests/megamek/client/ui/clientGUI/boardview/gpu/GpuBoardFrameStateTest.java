/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

class GpuBoardFrameStateTest {
    @Test
    void aBoardReplacementCannotGiveAnAlreadyTakenFrameTheNewBoardsPresentationOwner() throws Exception {
        var game = new Game();
        var viewRef = new AtomicReference<BoardClientState>();
        var create = new FutureTask<>(() -> {
            game.setBoard(Board.createEmptyBoard(8, 8));
            game.setPhase(GamePhase.MOVEMENT);
            game.addPlayer(0, new Player(0, "test"));
            var view = new BoardClientState(game, null, null, 0, null);
            view.setLocalPlayer(0);
            viewRef.set(view);
            return new GpuBoardSource(view, JPanel::new);
        });
        SwingUtilities.invokeAndWait(create);
        var source = create.get();
        var view = viewRef.get();
        try {
            var taken = source.takeFrame();
            var oldOwner = taken.boardState();
            assertNotNull(oldOwner);
            assertSame(view.getGpuBoardState(), oldOwner);
            // The EDT replaces a same-sized board after GL takes its frame but before GL processes that frame.
            SwingUtilities.invokeAndWait(() -> {
                game.setBoard(new Board(8, 8, Stream.generate(() -> new Hex(2)).limit(64).toArray(Hex[]::new)));
                source.refresh();
            });
            var next = source.takeFrame();
            assertSame(oldOwner, taken.boardState());
            assertNotSame(oldOwner, next.boardState());
            assertSame(view.getGpuBoardState(), next.boardState());
            assertTrue(next.boardGeneration() > taken.boardGeneration());
            assertSame(oldOwner, taken.withTimeline(List.of()).boardState());
            assertSame(oldOwner, taken.withScene(taken.scene().withUnits(List.of())).boardState());

            var settings = TerrainSettings.capture();
            taken.boardState().update(taken.scene(), settings);
            next.boardState().update(next.scene(), settings);
            var at = new Coords(4, 4);
            assertEquals(0, oldOwner.planting().scene.tile(at).elevation());
            assertEquals(2, next.boardState().planting().scene.tile(at).elevation(),
                  "The new board's initial planting must never be seeded from the previous board's delayed frame");
        } finally { SwingUtilities.invokeAndWait(() -> { source.close(); view.close(); }); }
    }
}
