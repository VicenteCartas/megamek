/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import megamek.MMConstants;
import megamek.client.Client;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyUnitCleanup;
import megamek.client.lobby.LobbyUnitOrder;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.common.Player;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.game.Game;
import megamek.common.units.Entity;

/**
 * What the lobby does beyond its commands, for one client: readying with its checks, the lobby save and the GIF
 * question; the auto-resolve settings; the unit order. It outlives a lobby window, so a lobby that opens again finds
 * its settings as it left them.
 */
public final class SwingLobbyServices {

    public static final int DEFAULT_RUNS = 10;
    public static final int MAX_RUNS = 1000;

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final Map<ClientGUI, SwingLobbyServices> SERVICES = new HashMap<>();

    private final ClientGUI clientGUI;
    private final LobbyUnitOrder unitOrder;
    private final int maxThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    private boolean chances;
    private int runs = DEFAULT_RUNS;
    private int threads = Math.min(Runtime.getRuntime().availableProcessors(), maxThreads);
    private boolean lobbySavePerformed;

    private final GameListener gameListener = new GameListenerAdapter() {
        @Override
        public void gamePlayerChange(GamePlayerChangeEvent e) {
            game().setupTeams();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent e) {
            // Only reset the save flag when entering the lounge from a different phase
            if (e.getNewPhase().isLounge() && !e.getOldPhase().isLounge()) {
                lobbySavePerformed = false;
            }
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent e) {
            SwingUtilities.invokeLater(() -> {
                unitOrder.settingsChanged();
                MapSetupDialog.refreshClosedPreview(clientGUI);
            });
        }

        @Override
        public void gameBoardNew(GameBoardNewEvent e) {
            SwingUtilities.invokeLater(() -> MapSetupDialog.refreshClosedPreview(clientGUI));
        }
    };

    private SwingLobbyServices(ClientGUI clientGUI) {
        this.clientGUI = clientGUI;
        this.unitOrder = new LobbyUnitOrder(clientGUI.getClient());
        game().addGameListener(gameListener);
    }

    /** EDT: the lobby services of this client, made on first use. */
    public static SwingLobbyServices of(ClientGUI clientGUI) {
        return SERVICES.computeIfAbsent(clientGUI, SwingLobbyServices::new);
    }

    /** EDT: the client ends; its lobby services stop listening and its map setup closes. */
    public static void forget(ClientGUI clientGUI) {
        SwingLobbyServices services = SERVICES.remove(clientGUI);
        if (services != null) {
            services.game().removeGameListener(services.gameListener);
        }
        MapSetupDialog.closePreviews(clientGUI);
    }

    /**
     * EDT: the lobby's scene failed; an error says so and the client leaves the game. When the window itself never
     * opened in MegaMek's own run, no screen can show, and MegaMek ends.
     */
    public static void sceneFailed(ClientGUI clientGUI, Throwable failure, boolean exit) {
        String text = Messages.getString("Lobby.failure", String.valueOf(failure));
        String title = Messages.getString("Lobby.windowTitle");
        // The start screen that comes back says why, natively; without one, only Swing can
        if (exit || !GpuStartWindow.notice(title, text)) {
            JOptionPane.showMessageDialog(null, text, title, JOptionPane.ERROR_MESSAGE);
        }
        if (exit) {
            System.exit(1);
        }
        clientGUI.die();
    }

    private Client client() {
        return clientGUI.getClient();
    }

    private Game game() {
        return client().getGame();
    }

    private LobbyCommands commands() {
        return clientGUI.lobbyCommands();
    }

    /** The unit order of the lobby's unit list. */
    public LobbyUnitOrder unitOrder() {
        return unitOrder;
    }

    /** EDT: takes from the units what the game's options don't allow, before the lobby shows them. */
    public void cleanUnits() {
        LobbyUnitCleanup.apply(game().getEntitiesVector(), game().getOptions());
    }

    /**
     * EDT: toggles the local player's ready state. Readying is checked first, and may ask; before the player is done,
     * the GIF recording question is asked and, when the preference says so, the lobby is saved once.
     */
    public void ready() {
        Player local = client().getLocalPlayer();
        if (local == null) {
            return;
        }
        boolean done = !local.isDone();
        // Only readying is checked; standing down is always allowed
        if (done && !commands().checkReady()) {
            return;
        }
        MapSetupDialog.closePreviews(clientGUI);
        if (done) {
            // Ask up front (once) whether to record the combat-summary GIF, so recording never interrupts play
            MinimapPanel.promptForGifRecordingConsent(game(), clientGUI.getFrame());
        }
        if (done && GUIP.getSaveLobbyOnStart() && !lobbySavePerformed) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            client().sendChat(ClientGUI.CG_CHAT_COMMAND_LOCAL_SAVE + " Lobby_Save_" + timestamp + " ./"
                  + MMConstants.SAVEGAME_DIR);
            lobbySavePerformed = true;
        }
        commands().sendDone(done);
    }

    /**
     * EDT: the auto-resolve settings: whether the chances are shown before the battle is resolved, the number of runs
     * they are worked out from, and how many run at once, with their upper bound.
     */
    public LobbyBattlefield.AutoResolve autoResolveSettings() {
        return new LobbyBattlefield.AutoResolve(false, chances, runs, threads, maxThreads);
    }

    /** EDT: sets the auto-resolve settings; values out of bounds are brought within. */
    public void setAutoResolveSettings(boolean chances, int runs, int threads) {
        this.chances = chances;
        this.runs = Math.clamp(runs, 1, MAX_RUNS);
        this.threads = Math.clamp(threads, 1, maxThreads);
    }

    /** The units the local player may configure: their own and their local bots'. */
    private List<Entity> configurableEntities() {
        List<Entity> configurable = new ArrayList<>();
        for (Entity entity : game().getEntitiesVector()) {
            if (commands().isEditable(entity)) {
                configurable.add(entity);
            }
        }
        return configurable;
    }

    /** EDT: sets burst MG fire on or off on every unit the local player may configure; the menu bar's shortcut. */
    public void setAllBurstMg(boolean burstOn) {
        commands().toggleBurstMg(configurableEntities(), burstOn);
    }

    /** EDT: sets LRM hot-loading on or off on every unit the local player may configure; the menu bar's shortcut. */
    public void setAllHotLoad(boolean hotLoadOn) {
        commands().toggleHotLoad(configurableEntities(), hotLoadOn);
    }
}
