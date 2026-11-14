/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.button;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.dialogs.BotCommands.BotMoveOrderDialog;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A bot order that picks target hexes (HexTargetPicker), over the GPU battle window: no Swing control dialog, the
 * pick in the frame for the HUD, the board's clicks picking the hexes, and the HUD's Done and Esc ending it, with the
 * same order the Legacy 2D board's picker sends.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuHexPickTest {
    private static final Coords FIRST = new Coords(6, 6);
    private static final Coords SECOND = new Coords(7, 6);

    /** The fixture's game with an allied bot, a client recording its chat and toasts, and a source over its board. */
    private record Session(ClientGUI gui, BoardClientState view, GpuBoardSource source, Player princess,
          List<String> sent) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            onSwing(() -> {
                source.close();
                view.close();
                gui.getFrame().dispose();
                return null;
            });
        }
    }

    @Test
    void theBoardsClicksPickTheHexesAndDoneSendsWhatTheLegacy2DPickerSends() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "The Legacy 2D picker shows its Swing control dialog");
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session game = onSwing(() -> session(fixture))) {
            // The Legacy 2D board: the control dialog, the board's clicks and the dialog's Done.
            onSwing(() -> {
                strategicTarget(game).doClick(0);
                return null;
            });
            JDialog controls = onSwing(GpuHexPickTest::controlDialog);
            assertNotNull(controls, "the Legacy 2D board shows the control dialog");
            onSwing(() -> {
                game.view().mouseAction(FIRST, BoardClientState.BOARD_HEX_CLICK, 0, 1);
                game.view().mouseAction(SECOND, BoardClientState.BOARD_HEX_CLICK, 0, 1);
                button(controls, Messages.getString("BotCommandPanel.HexPicker.done")).doClick(0);
                return null;
            });
            List<String> legacy2D = List.copyOf(game.sent());
            assertEquals(2, legacy2D.stream().filter(line -> line.startsWith("chat: Princess: ")).count(),
                  "one target order per hex: " + legacy2D);
            assertTrue(legacy2D.stream().anyMatch(line -> line.endsWith(FIRST.getBoardNum()))
                  && legacy2D.stream().anyMatch(line -> line.endsWith(SECOND.getBoardNum())), legacy2D.toString());

            present(game.gui(), game.view(), game.source());
            try {
                game.sent().clear();
                nativeStrategicTarget(game);
                assertNull(onSwing(GpuHexPickTest::controlDialog), "no control dialog over the battle window");
                String instructions = Messages.getString("BotCommandPanel.HexPicker.instructions",
                      Messages.getString("BotCommandPanel.StrategicTarget.title"));
                assertEquals(new GpuPlayers.Pick(instructions, ""), pick(game), "the frame carries the pick");

                // The HUD's left clicks during a pick (GpuHud.boardClick).
                game.source().click(FIRST, false, 0);
                assertEquals(new GpuPlayers.Pick(instructions,
                      Messages.getString("BotCommandPanel.HexPicker.status", 1, FIRST.getBoardNum())), pick(game));
                assertTrue(game.source().takeFrame().scene().rangeBorders().stream()
                      .anyMatch(border -> border.coords().equals(FIRST) && border.edges() == 63),
                      "the board highlights the picked hex");
                game.source().click(SECOND, false, 0);
                // The HUD's Done key.
                game.source().players().endPick(true);
                assertNull(pick(game), "Done ends the pick");
                assertEquals(legacy2D, List.copyOf(game.sent()), "the Legacy 2D picker's order and toasts");
                assertTrue(game.view().getWeaponRanges().isEmpty(), "the highlights go with the pick");
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void escCancelsThePickAndANewPickOrAClosingWindowEndsTheOneThatRuns() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session game = onSwing(() -> session(fixture))) {
            present(game.gui(), game.view(), game.source());
            try {
                nativeStrategicTarget(game);
                game.source().click(FIRST, false, 0);
                assertNotNull(pick(game));
                // The HUD's Esc.
                game.source().players().endPick(false);
                assertNull(pick(game), "Esc ends the pick");
                assertTrue(game.sent().stream().noneMatch(line -> line.startsWith("chat: ")), "and sends no order");
                assertTrue(game.view().getWeaponRanges().isEmpty());

                nativeStrategicTarget(game);
                var first = onSwing(() -> game.view().getHexPick());
                nativeStrategicTarget(game);
                assertNotSame(first, onSwing(() -> game.view().getHexPick()),
                      "one pick at a time: a new order's pick ends the running one");
                game.source().click(FIRST, false, 0);
                assertEquals(1, onSwing(() -> game.view().getWeaponRanges().size()),
                      "and the ended pick takes no clicks");
                assertNotNull(pick(game));
                onSwing(() -> {
                    game.source().close();
                    return null;
                });
                assertNull(onSwing(() -> game.view().getHexPick()), "the closing window ends the pick its HUD showed");
                assertTrue(game.sent().stream().noneMatch(line -> line.startsWith("chat: ")));
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void theMoveOrderEditorPicksWaypointsOnTheNativeBoardAndTheHudEndsItsPick() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "The Move Order editor is a Swing dialog");
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session game = onSwing(() -> session(fixture))) {
            present(game.gui(), game.view(), game.source());
            try {
                BotMoveOrderDialog editor = onSwing(() -> {
                    moveOrder(game).doClick(0);
                    return Arrays.stream(Window.getWindows()).filter(BotMoveOrderDialog.class::isInstance)
                          .map(BotMoveOrderDialog.class::cast).filter(Window::isShowing).findFirst().orElseThrow();
                });
                try {
                    // An editor without a route opens picking.
                    String instructions = Messages.getString("BotCommandPanel.MoveOrder.pickHint");
                    assertEquals(new GpuPlayers.Pick(instructions, ""), pick(game), "the HUD shows the editor's pick");
                    // The HUD's left clicks during a pick (GpuHud.boardClick).
                    game.source().click(FIRST, false, 0);
                    assertEquals(new GpuPlayers.Pick(instructions,
                          Messages.getString("BotCommandPanel.HexPicker.status", 1, FIRST.getBoardNum())), pick(game),
                          "a board click adds a waypoint");
                    game.source().players().endPick(true);
                    assertNull(pick(game), "the HUD's Done stops picking");
                    assertEquals(1, onSwing(() -> game.view().getWeaponRanges().size()),
                          "the draft route stays on the board while the editor is open");
                    onSwing(() -> {
                        button(editor, Messages.getString("BotCommandPanel.MoveOrder.pick")).doClick(0);
                        return null;
                    });
                    assertNotNull(pick(game), "the editor's pick button picks again");
                    onSwing(() -> {
                        fixture.game.setPhase(GamePhase.FIRING);
                        return null;
                    });
                    assertNull(pick(game), "a new phase, with its own display under the board, ends the pick");
                } finally {
                    onSwing(() -> {
                        editor.dispose();
                        return null;
                    });
                }
                assertTrue(onSwing(() -> game.view().getWeaponRanges().isEmpty()), "and goes with the editor");
            } finally {
                dismiss();
            }
        }
    }

    /**
     * EDT: Princess, an allied bot with an Atlas, beside the fixture's player; a client that records the chat it sends
     * and the toasts, with its bot commands panel; a board view of the client over the fixture's game and a source
     * over that view.
     */
    private static Session session(GpuBoardFixture fixture) throws Exception {
        Player princess = new Player(3, "Princess");
        princess.setTeam(1);
        princess.setBot(true);
        fixture.game.addPlayer(3, princess);
        // Only a CASPAR bot takes unit orders such as a Move Order
        fixture.game.recordBotType(princess.getName(), AIType.CASPAR);
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        atlas.setId(2);
        atlas.setOwner(princess);
        atlas.setPosition(new Coords(9, 9));
        atlas.setDeployed(true);
        fixture.game.addEntity(atlas, false);
        List<String> sent = new CopyOnWriteArrayList<>();
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        doCallRealMethod().when(client).getInGameObjects();
        doAnswer(call -> sent.add("chat: " + call.getArgument(0))).when(client).sendChat(anyString());
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        when(gui.getFrame()).thenReturn(new JFrame());
        doAnswer(call -> sent.add("toast: " + call.getArgument(1))).when(gui).addToast(any(ToastLevel.class),
              anyString());
        BotCommandsPanel panel = new BotCommandsPanel(client, null, null, gui);
        when(gui.getBotCommandsPanel()).thenReturn(panel);
        BoardClientState view = new BoardClientState(fixture.game, null, gui, 0, null);
        view.setLocalPlayer(fixture.player.getId());
        when(gui.getCurrentBoardState()).thenReturn(Optional.of(view));
        when(gui.getBoardState(anyInt())).thenReturn(view);
        GpuBoardSource source = new GpuBoardSource(view, JPanel::new);
        return new Session(gui, view, source, princess, sent);
    }

    /** EDT: Princess's Strategic Target item in the panel's Targets popup, as the Swing panel shows it. */
    private static JMenuItem strategicTarget(Session game) {
        String title = Messages.getString("BotCommandPanel.Targets.title");
        BotCommandsPanel.PopupCommand command = game.gui().getBotCommandsPanel().popupCommands(game.princess())
              .stream().filter(popup -> popup.button().getText().equals(title)).findFirst().orElseThrow();
        Component[] items = command.popup().get().getComponents();
        // A popup for one bot lists that bot's items directly or under the bot's own menu.
        if (items.length == 1 && items[0] instanceof JMenu menu) {
            items = menu.getMenuComponents();
        }
        return Arrays.stream(items).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
              .filter(item -> item.getText().equals(Messages.getString("BotCommandPanel.StrategicTarget.title")))
              .findFirst().orElseThrow();
    }

    /** EDT: the Move Order editor's item for Princess's lance in the panel's Move Order popup. */
    private static JMenuItem moveOrder(Session game) {
        String title = Messages.getString("BotCommandPanel.MoveOrder.title");
        BotCommandsPanel.PopupCommand command = game.gui().getBotCommandsPanel().popupCommands(game.princess())
              .stream().filter(popup -> popup.button().getText().equals(title)).findFirst().orElseThrow();
        Component[] items = command.popup().get().getComponents();
        if (items.length == 1 && items[0] instanceof JMenu menu) {
            items = menu.getMenuComponents();
        }
        return Arrays.stream(items).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).findFirst()
              .orElseThrow();
    }

    /** The players panel's Strategic Target command of Princess, run as the HUD runs it. */
    private static void nativeStrategicTarget(Session game) throws Exception {
        GpuPlayers players = game.source().players();
        players.setPanelOpen(true);
        BoardScene.Command order = onSwing(() -> players.capture().players().stream()
              .filter(row -> row.id() == game.princess().getId()).findFirst().orElseThrow().botCommands().stream()
              .filter(group -> group.label().equals(Messages.getString("BotCommandPanel.Targets.title")))
              .findFirst().orElseThrow());
        BoardScene.Command item = leaf(order, Messages.getString("BotCommandPanel.StrategicTarget.title"));
        players.setPanelOpen(false);
        item.action().run();
        // The command runs on the Swing thread after the call returns.
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static BoardScene.Command leaf(BoardScene.Command command, String label) {
        if (command.label().equals(label) && command.children().isEmpty()) {
            return command;
        }
        return command.children().stream().map(child -> leaf(child, label)).filter(found -> found != null)
              .findFirst().orElse(null);
    }

    /** The pick of the frame the source publishes once the Swing thread ran what the HUD posted. */
    private static GpuPlayers.Pick pick(Session game) throws Exception {
        onSwing(() -> {
            game.source().refresh();
            return null;
        });
        return game.source().takeFrame().panels().players().pick();
    }

    /** EDT: the Legacy 2D picker's control dialog while it shows, else null. */
    private static JDialog controlDialog() {
        return Arrays.stream(Window.getWindows()).filter(window -> window instanceof JDialog dialog
                    && dialog.isShowing()
                    && Messages.getString("BotCommandPanel.HexPicker.title").equals(dialog.getTitle()))
              .map(JDialog.class::cast).findFirst().orElse(null);
    }
}
