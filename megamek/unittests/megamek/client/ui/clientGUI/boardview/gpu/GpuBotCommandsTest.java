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

import java.awt.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
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
 * The bot commands on the players panel's rows (plan A.15 O3, stage E7b): for each bot the local player commands, the
 * bot commands panel's popup buttons as groups of that bot's items, which run exactly what clicking the same item of
 * the panel's popup runs; none for other players; the panel's Pause/Continue button as a plain command.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuBotCommandsTest {

    /** The order ends with a hex prompt (a board pick, or a typed prompt without a board view); not run here. */
    private static final Set<String> PROMPTS = Set.of(Messages.getString("BotCommandPanel.ScootToHex.title"),
          Messages.getString("BotCommandPanel.StrategicTarget.title"),
          Messages.getString("BotCommandPanel.PriorityTargetPicker.title"),
          Messages.getString("BotCommandPanel.ArtillerySingle.title"),
          Messages.getString("BotCommandPanel.ArtilleryVolley.title"),
          Messages.getString("BotCommandPanel.ArtilleryBarrage.title"));

    /** The game around the fixture's local player: an allied bot, an enemy player and an enemy bot. */
    private record Bots(GpuPlayers players, BotCommandsPanel panel, Client client, Player princess) { }

    @Test
    void eachBotCommandRunsTheSameActionAsItsSwingPopupItem() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> sent = new CopyOnWriteArrayList<>();
            Bots game = GpuTestSupport.onSwing(() -> bots(fixture, sent));
            GpuPlayers.Snapshot snapshot = GpuTestSupport.onSwing(game.players()::capture);
            GpuPlayers.PlayerRow princess = row(snapshot, game.princess().getId());
            List<String> buttons = GpuTestSupport.onSwing(() -> game.panel().popupCommands(game.princess()).stream()
                  .map(command -> command.button().getText()).toList());
            // Princess has no artillery, so the Artillery button's popup lists nothing for her.
            assertEquals(buttons.stream().filter(label -> !label.equals(title("Artillery"))).toList(),
                  princess.botCommands().stream().map(BoardScene.Command::label).toList(),
                  "the panel's popup buttons, in its order, as groups");
            int compared = 0;
            for (BoardScene.Command group : princess.botCommands()) {
                for (List<BoardScene.Command> path : leaves(group, new ArrayList<>())) {
                    BoardScene.Command item = path.getLast();
                    List<String> labels = path.stream().map(BoardScene.Command::label).toList();
                    JMenuItem swing = GpuTestSupport.onSwing(() -> find(popup(game, group.label()), labels.subList(1,
                          labels.size())));
                    assertNotNull(swing, "the Swing popup has " + labels);
                    assertEquals(swing.isEnabled(), item.enabled(), "availability of " + labels);
                    if (!item.enabled() || labels.stream().anyMatch(PROMPTS::contains)) {
                        continue;
                    }
                    sent.clear();
                    SwingUtilities.invokeAndWait(() -> swing.doClick(0));
                    List<String> expected = List.copyOf(sent);
                    sent.clear();
                    item.action().run();
                    // The command runs on the Swing thread after the call returns.
                    SwingUtilities.invokeAndWait(() -> { });
                    assertFalse(expected.isEmpty(), labels + " sends an order");
                    assertEquals(expected, List.copyOf(sent), labels.toString());
                    assertTrue(expected.stream().allMatch(message -> !message.startsWith("chat: ")
                          || message.startsWith("chat: Princess: ") || message.startsWith("chat: /unitOrder ")),
                          "orders go to Princess, by her chat or a unit order: " + expected);
                    compared++;
                }
            }
            assertTrue(compared > 80, "every order without a hex prompt was compared: " + compared);
        }
    }

    @Test
    void onlyBotsUnderTheLocalPlayersCommandGetCommandsAndOnlyWhileThePanelIsOpen() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Bots game = GpuTestSupport.onSwing(() -> bots(fixture, new CopyOnWriteArrayList<>()));
            GpuPlayers.Snapshot open = GpuTestSupport.onSwing(game.players()::capture);
            assertEquals(List.of("GPU review 0", "Princess 5", "Opponent 0", "Enemy bot 0", "Bot Two 3"),
                  open.players().stream().map(row -> row.name() + " " + row.botCommands().size()).toList(),
                  "an enemy bot is not under the local player's command; a bot without units on the board has no"
                        + " movement, waypoint or artillery orders");
            assertSame(open, GpuTestSupport.onSwing(game.players()::capture), "unchanged commands keep the snapshot's instance");
            game.players().setPanelOpen(false);
            GpuPlayers.Snapshot closed = GpuTestSupport.onSwing(game.players()::capture);
            assertTrue(closed.players().stream().allMatch(row -> row.botCommands().isEmpty()));
            assertNull(closed.pause(), "no commands are captured while the players panel is closed");
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

    /** EDT: the popup that the panel's button with this label opens for Princess. */
    private static JPopupMenu popup(Bots game, String label) {
        return game.panel().popupCommands(game.princess()).stream()
              .filter(command -> command.button().getText().equals(label)).findFirst().orElseThrow().popup().get();
    }

    /** The menu item at the end of the labels' path through the popup's menus, or null. */
    private static JMenuItem find(JPopupMenu popup, List<String> labels) {
        Component[] components = popup.getComponents();
        JMenuItem found = null;
        for (String label : labels) {
            found = null;
            for (Component component : components) {
                if (component instanceof JMenuItem item && label.equals(item.getText())) {
                    found = item;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            components = found instanceof JMenu menu ? menu.getMenuComponents() : new Component[0];
        }
        return found;
    }

    /** Every item below {@code command}, as its path from the group down. */
    private static List<List<BoardScene.Command>> leaves(BoardScene.Command command,
          List<BoardScene.Command> parents) {
        List<BoardScene.Command> path = new ArrayList<>(parents);
        path.add(command);
        if (command.children().isEmpty()) {
            return List.of(path);
        }
        List<List<BoardScene.Command>> leaves = new ArrayList<>();
        command.children().forEach(child -> leaves.addAll(leaves(child, path)));
        return leaves;
    }

    private static GpuPlayers.PlayerRow row(GpuPlayers.Snapshot snapshot, int id) {
        return snapshot.players().stream().filter(row -> row.id() == id).findFirst().orElseThrow();
    }

    /** A bot commands panel button's label. */
    private static String title(String button) {
        return Messages.getString("BotCommandPanel." + button + ".title");
    }
}
