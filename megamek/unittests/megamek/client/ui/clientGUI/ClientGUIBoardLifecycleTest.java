/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Window;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.CloseClientListener;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLobbyWindow;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.sprite.EntitySprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.dialogs.unitDisplay.WeaponPanel;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyBoardPreviews;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyServices;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListener;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.server.ServerBoardHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClientGUIBoardLifecycleTest {
    @ParameterizedTest
    @EnumSource(value = GamePhase.class, names = { "UNKNOWN", "LOUNGE", "STARTING_SCENARIO", "EXCHANGE" })
    void nativeMapArrivalReplacementAndBoardSelectionNeedNoLegacy2DRenderer(GamePhase phase) throws Exception {
        onClient(() -> {
            try (var views = mockConstruction(BoardView.class);
                  var panels = mockConstruction(BoardViewPanel.class);
                  var rulers = mockConstruction(RulerDialog.class);
                  var units = mockConstruction(EntitySprite.class);
                  var isometric = mockConstruction(IsometricSprite.class);
                  var display = mockConstruction(UnitDisplayPanel.class);
                  var dialog = mockConstruction(UnitDisplayDialog.class);
                  var weapons = mockConstruction(WeaponPanel.class);
                  var sound = mockConstruction(SoundManager.class);
                  var session = Session.create(phase)) {
                Game game = session.game();
                ClientGUI gui = session.gui();
                // Startup preferences and phase rules may request the inspector before the native window exists.
                gui.setUnitDisplayVisible(true);
                gui.setUnitDisplayLocation(true);
                assertNull(gui.getUnitDisplay());
                assertNull(gui.getUnitDisplayDialog());
                game.receiveBoard(0, Board.createEmptyBoard(8, 8));
                BoardClientState first = gui.getBoardState();
                assertNotNull(first);
                assertSame(gui.getTilesetManager(), first.getTilesetManager());
                assertTrue(gui.boardViews().isEmpty());
                int listeners = game.getGameListeners().size();
                for (int i = 0; i < 3; i++) {
                    Board oldBoard = game.getBoard();
                    BoardClientState oldState = gui.getBoardState();
                    game.receiveBoard(0, Board.createEmptyBoard(8, 8));
                    assertTrue(oldState.isClosed());
                    assertNotSame(oldState, gui.getBoardState());
                    assertEquals(listeners, game.getGameListeners().size(), "Replacing a map must detach old listeners");
                    assertEquals(1, session.closeListeners().size(), "The old minimap must release its client callback");
                    assertEquals(List.of(), boardListeners(oldBoard), "Replaced maps must release every subscription");
                    long revision = oldState.getRevision();
                    oldBoard.setHex(new Coords(1, 1), oldBoard.getHex(1, 1).duplicate());
                    assertEquals(revision, oldState.getRevision());
                }
                game.receiveBoard(2, Board.createEmptyBoard(6, 6));
                gui.showBoardView(2);
                assertSame(first.getTilesetManager(), gui.getBoardState(2).getTilesetManager());
                assertSame(gui.getBoardState(2).getOverlay(BoardToastOverlay.class),
                      gui.getToastOverlay());
                assertSame(gui.getBoardState(2), gui.getBoardState());
                BoardClientState removed = gui.getBoardState(2);
                Board removedBoard = removed.getBoard();
                game.receiveBoard(2, null);
                assertTrue(removed.isClosed());
                assertNull(gui.getBoardState(2));
                assertSame(gui.getBoardState(0), gui.getBoardState());
                assertEquals(List.of(), boardListeners(removedBoard));
                assertTrue(views.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertTrue(rulers.constructed().isEmpty(), "Native startup must not construct a Swing ruler");
                assertTrue(units.constructed().isEmpty());
                assertTrue(isometric.constructed().isEmpty());
                assertTrue(display.constructed().isEmpty(), "Native startup must not construct the Swing inspector");
                assertTrue(dialog.constructed().isEmpty());
                assertTrue(weapons.constructed().isEmpty());
                gui.die();
                assertTrue(gui.boardStates().isEmpty());
                assertTrue(session.closeListeners().isEmpty());
                assertTrue(game.getGameListeners().isEmpty(), "Client shutdown must release all board and HUD listeners");
            }
            return null;
        });
    }

    /**
     * The lobby's 3D preview shows the lobby's board: a roll of the map settings without a server-built battlefield,
     * else that battlefield. A new lobby board shows in the open preview without bringing it to the front, and the
     * lobby's end closes it. The native window is stubbed: its state is the board it was last given.
     */
    @Test
    void lobby3DPreviewFollowsTheLobbyBoardAndClosesWithTheLobby() throws Exception {
        Deque<AutoCloseable> resources = new ArrayDeque<>();
        AtomicReference<Board> shown3D = new AtomicReference<>();
        AtomicInteger raised3D = new AtomicInteger();
        AtomicReference<ClientGUI> client = new AtomicReference<>();
        Board server = Board.createEmptyBoard(6, 6);
        try {
            onClient(() -> {
                resources.push(mockConstruction(UnitDisplayPanel.class));
                resources.push(mockConstruction(SoundManager.class));
                // Without a server-built battlefield every preview request rolls a new board from the map settings.
                var rolls = mockStatic(ServerBoardHelper.class);
                resources.push(rolls);
                rolls.when(() -> ServerBoardHelper.getPossibleGameBoard(any(), anyBoolean()))
                      .thenAnswer(call -> Board.createEmptyBoard(8, 8));
                Session session = Session.create();
                resources.push(session);
                ClientGUI gui = session.gui();
                Window frame = gui.getFrame();
                var gpu = mockStatic(GpuBoardWindow.class);
                resources.push(gpu);
                gpu.when(() -> GpuBoardWindow.openLobbyPreview(any(Window.class), any(Board.class), any()))
                      .thenAnswer(call -> {
                          assertSame(frame, call.getArgument(0), "The 3D preview belongs to the lobby");
                          shown3D.set(call.getArgument(1));
                          raised3D.incrementAndGet();
                          return null;
                      });
                gpu.when(() -> GpuBoardWindow.updatePreview(any(Window.class), any(Board.class))).thenAnswer(call -> {
                    if (shown3D.get() != null && call.getArgument(0) == frame) {
                        shown3D.set(call.getArgument(1));
                    }
                    return null;
                });
                gpu.when(() -> GpuBoardWindow.showsPreview(any(), any())).thenAnswer(call -> shown3D.get() != null
                      && call.getArgument(0) == frame && call.getArgument(1) == shown3D.get());
                gpu.when(() -> GpuBoardWindow.closePreview(frame)).thenAnswer(call -> {
                    shown3D.set(null);
                    return null;
                });
                MapSettings settings = MapSettings.getInstance();
                settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
                when(gui.getClient().getMapSettings()).thenReturn(settings);
                client.set(gui);
                LobbyServices.of(gui);

                LobbyBoardPreviews.preview3D(gui);
                Board first = shown3D.get();
                assertNotNull(first, "Without a server board the 3D preview shows a roll of the map settings");
                assertEquals(1, raised3D.get());

                // A server-built battlefield arrives (on the EDT later) while the 3D preview is open.
                session.game().receiveBoard(0, server);
                return null;
            });
            onClient(() -> {
                assertSame(server, shown3D.get(), "A new lobby board shows in the open 3D preview");
                assertEquals(1, raised3D.get(), "A new lobby board does not bring the 3D preview to the front");
                LobbyBoardPreviews.preview3D(client.get());
                assertSame(server, shown3D.get(), "The 3D button shows the server-built battlefield");
                LobbyBoardPreviews.closePreviews(client.get());
                assertNull(shown3D.get(), "The lobby's end closes its 3D preview");
                LobbyBoardPreviews.refreshPreview(client.get());
                assertNull(shown3D.get(), "A closed 3D preview stays closed");
                return null;
            });
        } finally {
            onClient(() -> {
                if (client.get() != null) {
                    LobbyServices.forget(client.get());
                }
                while (!resources.isEmpty()) {
                    resources.pop().close();
                }
                return null;
            });
        }
    }

    @Test
    void switchingRenderersKeepsTheSameStateAndSelection() throws Exception {
        onClient(() -> {
            try (var display = mockConstruction(UnitDisplayPanel.class);
                  var sound = mockConstruction(SoundManager.class);
                  var session = Session.create()) {
                session.game().receiveBoard(0, Board.createEmptyBoard(8, 8));
                ClientGUI gui = session.gui();
                BoardClientState state = gui.getBoardState();
                Coords selected = new Coords(3, 4);
                state.select(selected);
                for (int i = 0; i < 2; i++) {
                    gui.setLegacy2DViewEnabled(true);
                    assertSame(state, gui.getBoardView().getClientState());
                    assertEquals(selected, state.getSelected());
                    gui.setLegacy2DViewEnabled(false);
                    assertSame(state, gui.getBoardState());
                    assertFalse(state.isClosed());
                    assertTrue(gui.boardViews().isEmpty());
                    assertEquals(selected, state.getSelected());
                }
            }
            return null;
        });
    }

    /**
     * A client that joins or reconnects to a game past the lounge goes straight to the board, back to the lobby scene
     * when the game returns to the lounge, and to the board again as the next game starts.
     */
    @Test
    void joiningARunningGameShowsTheBoardAndTheLoungeShowsTheLobby() throws Exception {
        onClient(() -> {
            try (var session = Session.create(GamePhase.MOVEMENT);
                  var lobby = mockStatic(GpuLobbyWindow.class);
                  var board = mockStatic(GpuBoardWindow.class)) {
                ClientGUI gui = session.gui();
                gui.showDefaultBoard(ClientGUI.CG_DEFAULT);
                board.verify(() -> GpuBoardWindow.open(same(gui), any()));
                lobby.verify(() -> GpuLobbyWindow.open(any()), never());
                gui.showDefaultBoard(ClientGUI.CG_BOARD_VIEW);
                board.verify(() -> GpuBoardWindow.open(same(gui), any()), times(1));

                gui.showDefaultBoard(ClientGUI.CG_CHAT_LOUNGE);
                lobby.verify(() -> GpuLobbyWindow.open(gui));
                board.verify(() -> GpuBoardWindow.closeFor(gui));

                // Between the lounge and the first board phase the lobby stays up
                lobby.when(() -> GpuLobbyWindow.isActiveFor(gui)).thenReturn(true);
                gui.showDefaultBoard(ClientGUI.CG_DEFAULT);
                board.verify(() -> GpuBoardWindow.open(same(gui), any()), times(1));
                gui.showDefaultBoard(ClientGUI.CG_BOARD_VIEW);
                board.verify(() -> GpuBoardWindow.open(same(gui), any()), times(2));
                lobby.verify(() -> GpuLobbyWindow.closeFor(gui), times(2));
            }
            return null;
        });
    }

    /**
     * Before the server sends its first phase the window keeps what it shows; a scenario, which never enters the
     * lounge, then goes straight to the board without the lobby showing first.
     */
    @Test
    void aScenarioGoesStraightToTheBoardWithoutTheLobby() throws Exception {
        onClient(() -> {
            try (var lobby = mockStatic(GpuLobbyWindow.class);
                  var board = mockStatic(GpuBoardWindow.class)) {
                try (var waiting = Session.create(GamePhase.UNKNOWN)) {
                    waiting.gui().showDefaultBoard(ClientGUI.CG_DEFAULT);
                    board.verify(() -> GpuBoardWindow.open(any(ClientGUI.class), any()), never());
                }
                try (var scenario = Session.create(GamePhase.STARTING_SCENARIO)) {
                    ClientGUI gui = scenario.gui();
                    gui.showDefaultBoard(ClientGUI.CG_STARTING_SCENARIO);
                    board.verify(() -> GpuBoardWindow.open(same(gui), any()));
                }
                lobby.verify(() -> GpuLobbyWindow.open(any()), never());
            }
            return null;
        });
    }

    private record Session(Game game, ClientGUI gui, Set<CloseClientListener> closeListeners) implements AutoCloseable {
        static Session create() throws Exception {
            return create(GamePhase.LOUNGE);
        }

        static Session create(GamePhase phase) throws Exception {
            Game game = new Game();
            game.setPhase(phase);
            Player player = new Player(0, "Native lifecycle");
            game.addPlayer(0, player);
            Client client = mock(Client.class);
            Set<CloseClientListener> closeListeners = new HashSet<>();
            doAnswer(call -> { closeListeners.add(call.getArgument(0)); return null; })
                  .when(client).addCloseClientListener(any());
            doAnswer(call -> { closeListeners.remove(call.getArgument(0)); return null; })
                  .when(client).removeCloseClientListener(any());
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(player);
            ClientGUI gui = new ClientGUI(client, null);
            ChatterBox history = mock(ChatterBox.class);
            history.history = new LinkedList<>();
            var chat = ClientGUI.class.getDeclaredField("cb");
            chat.setAccessible(true);
            chat.set(gui, history);
            var listener = ClientGUI.class.getDeclaredField("gameListener");
            listener.setAccessible(true);
            game.addGameListener((GameListener) listener.get(gui));
            return new Session(game, gui, closeListeners);
        }

        @Override
        public void close() {
            if (gui.getFrame().isDisplayable() || !gui.boardStates().isEmpty()) {
                gui.die();
            }
        }
    }

    private static List<?> boardListeners(Board board) {
        try {
            var field = Board.class.getDeclaredField("boardListeners");
            field.setAccessible(true);
            return (List<?>) field.get(board);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
