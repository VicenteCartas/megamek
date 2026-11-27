/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The players panel's capture of the bot commands panel: no Swing popup commands on the rows (the bot sheet gives the
 * bots' orders), and the panel's Pause/Continue button as a plain command while the players panel is open.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuBotCommandsTest {

    /** The game around the fixture's local player: an allied bot, an enemy player and an enemy bot. */
    private record Bots(GpuPlayers players, BotCommandsPanel panel, Client client, Player princess) { }

    @Test
    void pauseIsCapturedOnlyWhileThePlayersPanelIsOpen() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Bots game = GpuTestSupport.onSwing(() -> bots(fixture, new CopyOnWriteArrayList<>()));
            GpuPlayers.Snapshot open = GpuTestSupport.onSwing(game.players()::capture);
            assertEquals(List.of("GPU review", "Princess", "Opponent", "Enemy bot", "Bot Two"),
                  open.players().stream().map(GpuPlayers.PlayerRow::name).toList());
            assertNotNull(open.pause());
            assertSame(open, GpuTestSupport.onSwing(game.players()::capture),
                  "an unchanged Pause keeps the snapshot's instance");
            game.players().setPanelOpen(false);
            assertNull(GpuTestSupport.onSwing(game.players()::capture).pause(),
                  "no command is captured while the players panel is closed");
        }
    }

    @Test
    void pauseIsAPlainCommandWithThePanelsLabelAvailabilityAndAction() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Bots game = GpuTestSupport.onSwing(() -> bots(fixture, new CopyOnWriteArrayList<>()));
            BoardScene.Command pause = GpuTestSupport.onSwing(game.players()::capture).pause();
            assertEquals(title("PauseGame"), pause.label());
            assertFalse(pause.enabled(), "a human player still has units");
            assertEquals(Messages.getString("BotCommandPanel.PauseGame.unavailable.tooltip"), pause.detail());
            pause.action().run();
            SwingUtilities.invokeAndWait(() -> { });
            verify(game.client(), never()).sendPause();

            SwingUtilities.invokeAndWait(() -> {
                // Only bots play now: the panel enables the button at the next phase.
                fixture.player.setBot(true);
                fixture.game.getPlayer(4).setBot(true);
                fixture.game.setPhase(GamePhase.FIRING);
            });
            pause = GpuTestSupport.onSwing(game.players()::capture).pause();
            assertTrue(pause.enabled());
            pause.action().run();
            SwingUtilities.invokeAndWait(() -> { });
            verify(game.client()).sendPause();
            assertEquals(title("ContinueGame"), GpuTestSupport.onSwing(game.players()::capture).pause().label());
        }
    }

    /**
     * EDT: the fixture's game with Princess, an allied bot with an Atlas on the board, the enemy player Opponent with
     * an Atlas, an enemy bot and a second allied bot without units, so the panel's buttons list two bots; a client that
     * records the chat it sends and the ClientGUI's toasts in {@code sent}, and the players service of a source whose
     * view has that ClientGUI's bot commands panel, open.
     */
    private static Bots bots(GpuBoardFixture fixture, List<String> sent) throws Exception {
        Player princess = player(fixture, 3, "Princess", 1, true);
        player(fixture, 4, "Opponent", 2, false);
        player(fixture, 5, "Enemy bot", 2, true);
        player(fixture, 6, "Bot Two", 1, true);
        // Only a CASPAR bot takes unit orders (Move Order, Quick Orders)
        fixture.game.recordBotType(princess.getName(), AIType.CASPAR);
        unit(fixture, 2, princess, new Coords(6, 6));
        unit(fixture, 3, fixture.game.getPlayer(4), new Coords(9, 9));
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        doCallRealMethod().when(client).getInGameObjects();
        doAnswer(call -> sent.add("chat: " + call.getArgument(0))).when(client).sendChat(anyString());
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        doAnswer(call -> sent.add("toast: " + call.getArgument(1))).when(gui).addToast(any(ToastLevel.class),
              anyString());
        BotCommandsPanel panel = new BotCommandsPanel(client, null, null, gui);
        when(gui.getBotCommandsPanel()).thenReturn(panel);
        BoardClientState view = spy(fixture.view);
        doReturn(gui).when(view).getClientgui();
        GpuBoardSource source = mock(GpuBoardSource.class);
        when(source.currentView()).thenReturn(view);
        when(source.acceptsInput()).thenReturn(true);
        GpuPlayers players = new GpuPlayers(source);
        players.setPanelOpen(true);
        return new Bots(players, panel, client, princess);
    }

    private static Player player(GpuBoardFixture fixture, int id, String name, int team, boolean bot) {
        Player player = new Player(id, name);
        player.setTeam(team);
        player.setBot(bot);
        fixture.game.addPlayer(id, player);
        return player;
    }

    private static void unit(GpuBoardFixture fixture, int id, Player owner, Coords position) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf"))
              .getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setPosition(position);
        entity.setDeployed(true);
        fixture.game.addEntity(entity, false);
    }

    /** A bot commands panel button's label. */
    private static String title(String button) {
        return Messages.getString("BotCommandPanel." + button + ".title");
    }
}
