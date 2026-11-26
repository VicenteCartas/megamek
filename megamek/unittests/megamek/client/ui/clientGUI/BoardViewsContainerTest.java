/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

class BoardViewsContainerTest {
    @Test
    void aGameShowsTheGpuBoard() throws Exception {
        Game game = new Game();
        game.setPhase(GamePhase.STARTING_SCENARIO);
        ClientGUI gui = clientGUI(game);
        doCallRealMethod().when(gui).showDefaultBoard(ClientGUI.CG_STARTING_SCENARIO);
        SwingUtilities.invokeAndWait(() -> {
            try (var gpu = mockStatic(GpuBoardWindow.class)) {
                gui.showDefaultBoard(ClientGUI.CG_STARTING_SCENARIO);
                gpu.verify(() -> GpuBoardWindow.open(eq(gui), any()));
                verify(gui, never()).setLegacy2DViewEnabled(true);
            }
        });
    }

    /**
     * View > Renderer is the menu's one entry for the game board's graphics: Classic and Enhanced as one radio group
     * that shows the graphics the preferences choose. During a game a choice shows the board with them; in the lobby,
     * which shows no board, it is saved for the game's board. Legacy 2D is no longer a game's renderer.
     */
    @Test
    void theRendererMenuShowsTheGraphicsAndAChoiceSwitchesThem() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        BoardGraphics graphics = preferences.getBoardGraphics();
        SwingUtilities.invokeAndWait(() -> {
            CommonMenuBar menu = CommonMenuBar.getMenuBarForGame();
            try (var gpu = mockStatic(GpuBoardWindow.class)) {
                preferences.setBoardGraphics(BoardGraphics.ENHANCED);
                Game game = new Game();
                game.setPhase(GamePhase.MOVEMENT);
                menu.setPhase(GamePhase.MOVEMENT);
                ClientGUI gui = clientGUI(game);
                menu.addActionListener(gui);
                JMenu view = Arrays.stream(menu.getComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast)
                      .filter(group -> group.getText().equals(Messages.getString("CommonMenuBar.ViewMenu")))
                      .findFirst().orElseThrow();
                List<JMenu> renderers = Arrays.stream(view.getMenuComponents()).filter(JMenu.class::isInstance)
                      .map(JMenu.class::cast).filter(group -> group.getText()
                            .equals(Messages.getString("CommonMenuBar.viewRenderer"))).toList();
                assertEquals(1, renderers.size());
                JMenu renderer = renderers.getFirst();
                assertEquals(List.of(ClientGUI.VIEW_GRAPHICS_CLASSIC, ClientGUI.VIEW_GRAPHICS_ENHANCED),
                      Arrays.stream(renderer.getMenuComponents())
                            .map(item -> ((JRadioButtonMenuItem) item).getActionCommand()).toList());
                assertEquals(ClientGUI.VIEW_GRAPHICS_ENHANCED, selected(renderer));

                item(renderer, ClientGUI.VIEW_GRAPHICS_CLASSIC).doClick(0);
                assertEquals(BoardGraphics.CLASSIC, preferences.getBoardGraphics());
                assertEquals(ClientGUI.VIEW_GRAPHICS_CLASSIC, selected(renderer));
                gpu.verify(() -> GpuBoardWindow.open(eq(gui), any()));

                preferences.setBoardGraphics(BoardGraphics.ENHANCED);
                assertEquals(ClientGUI.VIEW_GRAPHICS_ENHANCED, selected(renderer), "Client Settings' choice shows too");

                game.setPhase(GamePhase.LOUNGE);
                menu.setPhase(GamePhase.LOUNGE);
                assertTrue(item(renderer, ClientGUI.VIEW_GRAPHICS_CLASSIC).isEnabled());
                item(renderer, ClientGUI.VIEW_GRAPHICS_CLASSIC).doClick(0);
                assertEquals(BoardGraphics.CLASSIC, preferences.getBoardGraphics(),
                      "The lobby saves the choice for the game's board");
                // and shows no board: the game's choice is still the only one that opened it
                gpu.verify(() -> GpuBoardWindow.open(eq(gui), any()));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                menu.die();
                preferences.setBoardGraphics(graphics);
            }
        });
    }
    /** A client of {@code game} that runs its real menu actions and nothing else. */
    private static ClientGUI clientGUI(Game game) throws ReflectiveOperationException {
        ClientGUI gui = mock(ClientGUI.class, invocation -> invocation.getMethod().getName().equals("actionPerformed")
              ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        Field field = ClientGUI.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(gui, client);
        return gui;
    }

    private static JRadioButtonMenuItem item(JMenu renderer, String command) {
        return Arrays.stream(renderer.getMenuComponents()).map(JRadioButtonMenuItem.class::cast)
              .filter(item -> item.getActionCommand().equals(command)).findFirst().orElseThrow();
    }

    /** The action command of the checked renderer. */
    private static String selected(JMenu renderer) {
        return Arrays.stream(renderer.getMenuComponents()).map(JRadioButtonMenuItem.class::cast)
              .filter(JRadioButtonMenuItem::isSelected).map(JRadioButtonMenuItem::getActionCommand)
              .reduce((first, second) -> first + "," + second).orElse("");
    }

    @Test
    void nativeSelectionNeedsNoLegacy2DComponentsAndSurvivesSwitchingViews() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        var views = AbstractClientGUI.class.getDeclaredField("boardViews");
        views.setAccessible(true);
        views.set(gui, new HashMap<>());
        var states = AbstractClientGUI.class.getDeclaredField("boardStates");
        states.setAccessible(true);
        states.set(gui, new HashMap<>());
        Client client = mock(Client.class);
        Game game = new Game();
        game.setBoard(0, new Board(1, 1));
        game.setBoard(1, new Board(1, 1));
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(game);
        BoardView first = mock(BoardView.class);
        BoardView second = mock(BoardView.class);
        when(first.getComponent()).thenReturn(new JPanel());
        when(second.getComponent()).thenReturn(new JPanel());
        SwingUtilities.invokeAndWait(() -> {
            BoardViewsContainer container = new BoardViewsContainer(gui);
            container.setLegacy2DEnabled(false);
            assertTrue(container.getCurrentBoardView().isEmpty());
            gui.boardStates.put(0, mock(BoardClientState.class));
            gui.boardStates.put(1, mock(BoardClientState.class));
            container.updateMapTabs();
            assertEquals(0, container.getCurrentBoardId().orElseThrow());
            container.showBoardView(1);
            container.updateMapTabs();
            assertEquals(1, container.getCurrentBoardId().orElseThrow());
            assertTrue(container.getCurrentBoardView().isEmpty());
            verify(first, never()).getComponent();
            verify(second, never()).getComponent();
            assertEquals(0, ((JPanel) container.getPanel()).getComponentCount());

            gui.boardViews.put(0, first);
            gui.boardViews.put(1, second);
            container.setLegacy2DEnabled(true);
            JTabbedPane tabs = (JTabbedPane) ((JPanel) container.getPanel()).getComponent(0);
            assertSame(second.getComponent(), tabs.getSelectedComponent());
            tabs.setSelectedComponent(first.getComponent());
            assertSame(first, container.getCurrentBoardView().orElseThrow());
            container.setLegacy2DEnabled(false);
            assertEquals(0, ((JPanel) container.getPanel()).getComponentCount());
            assertSame(first, container.getCurrentBoardView().orElseThrow());

            gui.boardViews.remove(0);
            gui.boardStates.remove(0);
            container.updateMapTabs();
            assertSame(second, container.getCurrentBoardView().orElseThrow());
            gui.boardViews.clear();
            gui.boardStates.clear();
            container.updateMapTabs();
            assertTrue(container.getCurrentBoardView().isEmpty());
        });
    }
}
