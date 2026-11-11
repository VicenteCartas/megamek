/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.ApplicationListener;
import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.MegaMek;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.lobby.BotFacingForm;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.C3NetworkBoard;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.IconCatalog;
import megamek.client.lobby.LanceRoleForm;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.LobbyUnitActions;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.lobby.LobbyUnitOrder;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.PortraitCatalog;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.lobby.UnitConfigForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyBoardPreviews;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyServices;
import megamek.client.ui.tileset.EntityImage;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Player;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.board.Board;
import megamek.common.containers.MunitionTree;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GamePollEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.event.player.GamePlayerChatEvent;
import megamek.common.event.player.GamePlayerConnectedEvent;
import megamek.common.event.player.GamePlayerDisconnectedEvent;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.AbstractIcon;
import megamek.common.icons.Camouflage;
import megamek.common.icons.Portrait;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.logging.MMLogger;
import megamek.server.ServerBoardHelper;

/**
 * The game lobby as a scene of MegaMek's one native window ({@link GpuHost}), for games MegaMek hosts or joins and
 * games MekHQ starts alike. It shows while the client's game is in the lounge; the client's Swing frame stays hidden.
 * This controller lives on the Swing thread: it listens to the client's game, sends each change to the screen
 * ({@link GpuLobbyScreen}) as a {@link LobbySnapshot}, and runs the screen's actions on the Swing thread, where the
 * game is.
 *
 * <p>Ready runs the lobby's ready step ({@link LobbyServices#ready()}), whose questions the lobby asks in its own
 * native dialogs. While a red warning blocks readying, the
 * top bar lights its warnings instead of that step's blocked dialog. This scene owns the dialogs natively, as the board
 * and the start screen own theirs, so they stay in front of the window, and a modal one blocks the screen's input.
 * Leave and the window's close box run the client's own exit ({@link ClientGUI#handleExit}), with its save question.
 * </p>
 *
 * <p>When the scene fails, for example because OpenGL can't start, an error says so and the client leaves the game,
 * back to the start screen.</p>
 */
public final class GpuLobbyWindow {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyWindow.class);
    /** How often, and how long at most, a new bot is checked for having joined, to put it on its team. */
    private static final int BOT_JOIN_POLL_MS = 250;
    private static final int BOT_JOIN_WAIT_MS = 10_000;
    /** How long the map waits for changes to settle before it is drawn again. */
    private static final int MAP_DELAY_MS = 150;
    /** How long a sent ready state shows before the server's echo, at most. */
    private static final long PENDING_READY_NANOS = 3_000_000_000L;
    /**
     * The folders the unit list dialogs last used, kept while MegaMek runs, as the Swing client's choosers keep them;
     * shared with the battle's Game Master tools.
     */
    static final LobbyFiles.Folders FOLDERS = new LobbyFiles.Folders();

    /** EDT: the lobby holding the window or about to. */
    private static GpuLobbyWindow active;
    /** EDT: the client whose lobby a scene it opened, such as the 3D preview or the board editor, replaced. */
    private static ClientGUI displaced;
    /** The client whose lobby an open from another thread is about to show. */
    private static volatile ClientGUI pending;
    /** EDT: the client whose map setup opened the 3D preview, so its lobby comes back to the map setup. */
    private static ClientGUI previewedFromMapSetup;

    private final ClientGUI gui;
    private final GpuSceneDialogs swingDialogs = new GpuSceneDialogs(new GpuSceneDialogs.Host() {
        @Override
        public JFrame frame() {
            return gui.getFrame();
        }

        @Override
        public void inputBlocked(boolean blocked) {
            setInputBlocked(blocked);
        }

        @Override
        public boolean closing() {
            return closing;
        }
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final GpuLobbyTools tools = new GpuLobbyTools(this);
    private final LobbyFiles files = new LobbyFiles(this::chooseFile, new LobbyFiles.Report() {
        @Override
        public void error(LobbyError error, Object... args) {
            tools.asks().error(error, args);
        }

        @Override
        public void message(String title, String text) {
            onScreen(shown -> shown.messageDialog(title, text));
        }
    }, FOLDERS);
    private final GameListener gameListener = new GameListenerAdapter() {
        @Override
        public void gamePlayerConnected(GamePlayerConnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerDisconnected(GamePlayerDisconnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChange(GamePlayerChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChat(GamePlayerChatEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePollChange(GamePollEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            // Client packets arrive on the EDT. Observe every map echo before the general refresh coalesces them,
            // including repeated values from several quick rotations of the same board.
            if (event.isMapSettingsOnlyChange() && SwingUtilities.isEventDispatchThread() && mapSetup != null) {
                mapSetup.refresh();
            }
            queueRefresh();
            if (!event.isMapSettingsOnlyChange()) {
                gameOptionsChanged();
            }
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameBoardNew(GameBoardNewEvent event) {
            boardReplaced();
        }

        @Override
        public void gameBoardChanged(GameBoardChangeEvent event) {
            boardReplaced();
        }
    };
    /** Any thread: the client and GUI settings the lobby shows take effect at once, as the Swing lobby's did. */
    private final IPreferenceChangeListener preferenceListener = event -> {
        switch (event.getName()) {
            case ClientPreferences.SHOW_AUTO_RESOLVE_PANEL -> queueRefresh();
            case GUIPreferences.USE_CAMO_OVERLAY -> {
                spritesStale = true;
                queueRefresh();
            }
            case GUIPreferences.GUI_SCALE -> GpuHost.post(() -> {
                GpuLobbyScreen shown = GpuLobbyWindow.this.screen;
                if (shown != null) {
                    shown.rescale();
                }
            });
            default -> { }
        }
    };
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            GpuLobbyScreen created = new GpuLobbyScreen(GpuLobbyWindow.this, GUIPreferences.getInstance());
            screen = created;
            spritesStale = true;
            queueRefresh();
            return created;
        }

        @Override
        public String title() {
            return Messages.getString("Lobby.windowTitle");
        }

        @Override
        public void started(long windowHandle) {
            swingDialogs.started(windowHandle);
            SwingUtilities.invokeLater(GpuLobbyWindow.this::presented);
        }

        @Override
        public void closeRequested() {
            if (swingDialogs.blocked()) {
                SwingUtilities.invokeLater(swingDialogs::frontModal);
            } else {
                leave();
            }
        }

        @Override
        public void focusGained() {
            if (swingDialogs.blocked()) {
                SwingUtilities.invokeLater(swingDialogs::frontModal);
            }
        }

        @Override
        public void focusLost() {
            if (screen != null) { screen.focusLost(); }
        }

        @Override
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    private volatile boolean closing;
    /** The screen while its listener exists; written on the GPU thread. */
    private volatile GpuLobbyScreen screen;
    /** The newest snapshot, which a screen shows as it is created. */
    private volatile LobbySnapshot snapshot = LobbySnapshot.EMPTY;
    /** EDT: the ready state just sent, shown until the server echoes it or {@link #PENDING_READY_NANOS} pass. */
    private Boolean pendingReady;
    private long pendingSince;
    private volatile GpuLobbyProbe probe;
    private boolean exitRequested;
    /** EDT: the map settings whose custom deployment zones {@link #customZones} holds. */
    private MapSettings zonesOf;
    private Collection<Integer> customZones = List.of();
    /** EDT: the camo keys whose pictures went to the screen for the players' swatches. */
    private final Set<String> camoKeys = new HashSet<>();
    /** EDT: the unit menu whose items the screen shows, until one is chosen. */
    private LobbyUnitMenu unitMenu;
    /** EDT: the map setup while it shows in the centre; null otherwise. */
    private LobbyMapSetupController mapSetup;
    /** EDT: the C3 network manager while it shows in the centre; null otherwise. */
    private LobbyC3Controller c3;
    /** EDT: the sprite keys whose images went to the screen, and the keys of the units it last got. */
    private final Set<String> spriteKeys = new HashSet<>();
    /** The catalogue units whose sprites the Requisition asked for, the latest first, and the thread that draws them. */
    private final LobbySpriteWorker<RequisitionSprite> sprites = new LobbySpriteWorker<>("Requisition sprites",
          this::drawRequisitionSprite);
    /** The EDT captures a private camo copy for a queued drawing; it is never changed by the worker. */
    private record RequisitionSprite(MekSummary unit, Camouflage camouflage) { }
    private Map<Integer, String> sentSprites = Map.of();
    /** Set when a new screen needs every sprite again. */
    private volatile boolean spritesStale;
    /** EDT: what the map picture last drawn, or being drawn, shows; the newest drawing's number, and its delay. */
    private MapKey mapKey;
    private int mapGeneration;
    private Timer mapTimer;
    private volatile BufferedImage lastMap;

    /** EDT: set when the lobby comes back from a 3D preview the map setup opened, to show the map setup again. */
    private boolean reopenMapSetup;

    private GpuLobbyWindow(ClientGUI gui) {
        this.gui = gui;
    }

    /**
     * Any thread: shows {@code gui}'s lobby in the native window, replacing the scene there, or brings it to the front
     * when it already shows. Nothing for a bot's client, which has no screen.
     */
    public static void open(ClientGUI gui) {
        if (gui.getClient() instanceof BotClient) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            pending = gui;
            SwingUtilities.invokeLater(() -> open(gui));
            return;
        }
        if (pending == gui) {
            pending = null;
        }
        displaced = null;
        if (active != null) {
            if (active.gui == gui && !active.closing) {
                GpuHost.focus();
                return;
            }
            active.close();
        }
        GpuLobbyWindow window = new GpuLobbyWindow(gui);
        window.reopenMapSetup = previewedFromMapSetup == gui;
        previewedFromMapSetup = null;
        active = window;
        gui.getClient().getGame().addGameListener(window.gameListener);
        PreferenceManager.getClientPreferences().addPreferenceChangeListener(window.preferenceListener);
        GUIPreferences.getInstance().addPreferenceChangeListener(window.preferenceListener);
        window.swingDialogs.install();
        gui.lobbyCommands().camoPicker(window.camoPicker);
        gui.lobbyCommands().unitConfigurator(window.unitConfigurator);
        gui.lobbyCommands().asks(window.tools.asks());
        window.refresh();
        GpuHost.show(window.scene);
        window.probe = GpuLobbyProbe.applyOnce(gui);
    }

    /** EDT: ends {@code gui}'s lobby scene, if it shows; the scene that replaces it, if any, decides what shows next. */
    public static void closeFor(ClientGUI gui) {
        if (pending == gui) {
            pending = null;
        }
        if (displaced == gui) {
            displaced = null;
        }
        if (active != null && active.gui == gui) {
            active.close();
        }
    }

    /**
     * EDT: when a scene that replaced a lobby closes, for example the 3D preview or the board editor it opened, shows
     * that lobby again if {@code owner} is its client's frame or a window of it and the game is still in the lounge.
     * Returns whether it did.
     */
    static boolean reopenFor(@Nullable Window owner) {
        ClientGUI gui = displaced;
        if (gui == null) {
            return false;
        }
        for (Window current = owner; current != null; current = current.getOwner()) {
            if (current == gui.getFrame()) {
                displaced = null;
                if (!gui.getClient().getGame().getPhase().isLounge()) {
                    return false;
                }
                open(gui);
                return true;
            }
        }
        return false;
    }

    /** Whether {@code gui}'s lobby holds the native window or is about to, so its Swing frame stays hidden. */
    public static boolean isActiveFor(ClientGUI gui) {
        GpuLobbyWindow window = active;
        return pending == gui || window != null && window.gui == gui && !window.closing;
    }

    /** EDT: whether a lobby holds the window and is not closing. */
    static boolean isShowing() {
        return active != null && !active.closing;
    }

    /**
     * EDT: shows an error over the lobby that shows, as {@link LobbyNativeDialogs#error} does; {@code report} runs on
     * the EDT. Returns false, showing nothing, when no lobby's screen shows, so the caller shows it otherwise.
     */
    static boolean showError(String title, String text, String dismissLabel, @Nullable String reportLabel,
          @Nullable Runnable report) {
        GpuLobbyWindow window = active;
        if (window == null || window.closing || window.screen == null) {
            return false;
        }
        Runnable onEdt = report == null ? null : () -> SwingUtilities.invokeLater(report);
        window.onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.error(title, text, dismissLabel, reportLabel,
              onEdt)));
        return true;
    }

    /**
     * EDT: asks over the lobby that shows whether to read the bot documentation, as the Swing bot readme nag does: Yes
     * shows it as Princess help, and the box stops the question coming back. Returns false, asking nothing, when no
     * lobby shows, so the Swing nag should.
     */
    public static boolean nagBotReadme() {
        GpuLobbyWindow window = active;
        if (window == null || window.closing) {
            return false;
        }
        window.onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.botReadme(
              Messages.getString("chkIgnore.text"),
              (read, ignore) -> {
                  SwingUtilities.invokeLater(() -> MegaMek.getMMOptions()
                        .setNagDialogIgnore(MMConstants.NAG_BOT_README, ignore));
                  if (read) {
                      window.tools.princessHelp();
                  }
              })));
        return true;
    }

    /**
     * EDT: shows the HTML guide {@code file} natively over {@code gui}'s lobby, as the battle window shows it. Returns
     * false, showing nothing, when that lobby does not show, so the caller shows the Swing help.
     */
    public static boolean showHelp(ClientGUI gui, String title, File file) {
        GpuLobbyWindow window = active;
        if (window == null || window.closing || window.gui != gui) {
            return false;
        }
        window.tools.helpDocument(title, file);
        return true;
    }

    private void close() {
        closing = true;
        GpuHost.end(scene);
    }

    /** The client whose lobby this is. */
    ClientGUI gui() {
        return gui;
    }

    /** Whether the lobby is closing. */
    boolean closing() {
        return closing;
    }

    /** The lobby's screen, or null before it shows. */
    @Nullable GpuLobbyScreen screen() {
        return screen;
    }

    /** The lobby's tools and prompts. */
    GpuLobbyTools tools() {
        return tools;
    }

    /** The developer switch's shot and quit, or null; see {@link GpuLobbyProbe}. */
    GpuLobbyProbe probe() {
        return probe;
    }

    /** The newest snapshot of the lobby. */
    LobbySnapshot snapshot() {
        return snapshot;
    }

    /** Whether a modal Swing dialog over the lobby is open. */
    boolean modalOpen() {
        return swingDialogs.blocked();
    }

    /** Any thread, for the developer switch: clicks the screen's actor called {@code name} through its stage. */
    /** EDT, for the developer switch: opens the L17 tool or prompt {@code tool} names; see {@link LobbyToolsProbe}. */
    void probeTool(String tool) {
        if (closing) {
            return;
        }
        Player local = gui.getClient().getLocalPlayer();
        switch (tool) {
            case "gameoptions" -> openGameOptions();
            case "playersettings" -> openPlayerSettings(gui.getClient());
            case "savelist" -> forcesCommand(LobbyForcesPanel.Command.SAVE_LIST);
            case "loadlist" -> forcesCommand(LobbyForcesPanel.Command.LOAD_LIST);
            case "sprite" -> gui.getClient().getGame().getEntitiesVector().stream()
                  .filter(unit -> local != null && unit.getOwnerId() == local.getId()).findFirst()
                  .ifPresent(unit -> unitActions().run(LobbyUnitMenu.LMP_SPRITE + '|'
                        + LobbyUnitMenu.SPRITE_CAMO + '|' + unit.getId()));
            default -> tools.probe(tool);
        }
    }

    /** EDT, for the developer switch's {@code lobby+configure}: opens the unit bay for the player's first unit or all. */
    /** EDT, for the developer switch: opens the map setup, or with {@code random} the random map settings. */
    void probeMapSetup(boolean random) {
        if (!closing) {
            battlefieldCommand(random ? LobbyBattlefieldController.Command.RANDOM_MAP
                  : LobbyBattlefieldController.Command.MAP_SETUP);
        }
    }

    /** EDT, for the developer switch: logs the server's map settings, with what happened. */
    void probeMapSettings(String what) {
        LOGGER.info("The lobby probe's server map settings, {}: {}", what,
              LobbyMapSetupController.describe(gui.getClient().getMapSettings()));
    }

    void probeConfigure(boolean all) {        if (closing) {
            return;
        }
        Client client = gui.getClient();
        Player local = client.getLocalPlayer();
        if (local == null) {
            return;
        }
        List<Entity> units = new ArrayList<>();
        for (Entity entity : client.getGame().getEntitiesVector()) {
            if (entity.getOwnerId() == local.getId()) {
                units.add(entity);
            }
        }
        if (units.isEmpty()) {
            LOGGER.warn("The lobby probe found no unit of the player to configure");
        } else if (all) {
            gui.lobbyCommands().customizeMeks(units);
        } else {
            gui.lobbyCommands().customizeMek(units.get(0));
        }
    }

    void probeClick(String name) {
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.probeClick(name);
            }
        });
    }

    /** EDT, for the developer switch's {@code lobby+dialog=name}: opens that dialog and photographs the screen. */
    void probeDialog() {
        GpuLobbyProbe shown = probe;
        if (shown != null && !closing) {
            shown.dialogShot(this, gui);
        }
    }

    /** EDT, for the developer switch's libGDX dialogs: logs the local player as the game and the snapshot have them. */
    void logProbePlayer() {
        Player local = gui.getClient().getLocalPlayer();
        if (local == null) {
            return;
        }
        LobbySnapshot.PlayerRow row = snapshot.teams().stream().flatMap(team -> team.players().stream())
              .filter(player -> player.id() == local.getId()).findFirst().orElse(null);
        LOGGER.info("Lobby probe player: camo {}, start {} offset {}; snapshot camo '{}', start '{}'",
              LobbySnapshot.camoKey(local.getCamouflage()), local.getStartingPos(), local.getStartOffset(),
              row == null ? null : row.camo(), row == null ? null : row.start());
        LobbyBattlefield battlefield = snapshot.battlefield();
        LOGGER.info("Lobby probe battlefield: conditions '{}', rules '{}', notable rules {}",
              battlefield.conditions(), battlefield.rulesLine(), battlefield.notableRules());
        gui.getLocalBots().forEach((name, bot) -> {
            Player player = bot.getLocalPlayer();
            String behavior = bot instanceof BotClient botClient ? botClient.getBehaviorSettings().toLog() : "-";
            LOGGER.info("Lobby probe bot {}: team {}, behaviour {}", name, player == null ? null : player.getTeam(),
                  behavior.replaceAll("\\s+", " "));
        });
    }

    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            // Later, so the chat box has taken the same event first
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    /**
     * Any thread: the server sent a board, often after the chat and readiness updates that refreshed the lobby, or
     * changed the one it has in place; the map is drawn again either way.
     */
    private void boardReplaced() {
        SwingUtilities.invokeLater(() -> {
            mapKey = null;
            queueRefresh();
        });
    }

    /** EDT: reads the lobby and hands it to the screen. */
    private void refresh() {
        refreshQueued.set(false);
        if (closing) {
            return;
        }
        LobbySnapshot read;
        try {
            LobbyServices services = LobbyServices.of(gui);
            services.cleanUnits();
            ChatterBox chat = gui.getChatterBox();
            read = LobbySnapshot.of(gui::getClient, chat == null ? List.of() : chat.lines(LobbySnapshot.CHAT_LINES),
                  customZones(), unitOrder(), services.autoResolveSettings());
            bakeMap();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the game");
            return;
        }
        if (pendingReady != null) {
            if (read.localReady() == pendingReady || System.nanoTime() - pendingSince > PENDING_READY_NANOS) {
                pendingReady = null;
            } else {
                read = read.withLocalReady(pendingReady);
            }
        }
        snapshot = read;
        LobbySnapshot shownRead = read;
        sendSprites(shownRead);
        sendCamos();
        if (c3 != null) {
            c3.refresh();
        }
        if (mapSetup != null) {
            mapSetup.refresh();
        }
        tools.refreshReadout();
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.show(shownRead);
            }
        });
    }

    // ---- The screen's actions, from the GPU thread ----

    /** Any thread: toggles the local player's ready state through the lobby's ready checks. */
    void ready() {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            Player local = gui.getClient().getLocalPlayer();
            boolean readying = local != null && !local.isDone();
            // A red warning blocks readying: the bar lights them, and their tooltips say why, instead of a dialog
            if (readying && !gui.lobbyCommands().readyBlockers().isEmpty()) {
                GpuHost.post(() -> {
                    GpuLobbyScreen shown = screen;
                    if (shown != null) {
                        shown.pulseWarnings();
                    }
                });
                return;
            }
            Game game = gui.getClient().getGame();
            if (readying && MinimapPanel.needsGifRecordingConsent(game)) {
                // Asked here rather than by the Swing question the services ask; the answer readies again
                onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.yesNo(
                      Messages.getString("MinimapPanel.RecordGifDialog.title"),
                      List.of(Messages.getString("MinimapPanel.RecordGifDialog.message"),
                            Messages.getString("MinimapPanel.RecordGifDialog.warning")),
                      Messages.getString("MinimapPanel.RecordGifDialog.remember"),
                      (record, remember) -> SwingUtilities.invokeLater(() -> {
                          MinimapPanel.recordGifRecordingDecision(game, record, remember);
                          ready();
                      }))));
                return;
            }
            LobbyServices.of(gui).ready();
            if (local != null && local.isDone() != readying) {
                // The server echoes the state a moment later; until then the bar and the card show it at once
                pendingReady = readying;
                pendingSince = System.nanoTime();
                // Read again once the server has had its time, in case its echo never comes
                Timer expiry = new Timer((int) (PENDING_READY_NANOS / 1_000_000L) + 1, event -> queueRefresh());
                expiry.setRepeats(false);
                expiry.start();
            }
            queueRefresh();
        });
    }

    /**
     * Any thread: leaves the game the way the client's exit does. Unless the player stopped it, the lobby first asks
     * whether to save, in its own dialog; saving leaves once the game is written, and a cancelled save stays.
     */
    void leave() {
        SwingUtilities.invokeLater(() -> {
            if (closing || exitRequested) {
                return;
            }
            if (GUIPreferences.getInstance().getNoSaveNag() || (screen == null)) {
                exitRequested = true;
                try {
                    gui.handleExit();
                } finally {
                    exitRequested = false;
                }
                return;
            }
            exitRequested = true;
            onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.saveFirst(
                  save -> SwingUtilities.invokeLater(() -> {
                    exitRequested = false;
                    if (closing) {
                        return;
                    }
                    if (!save) {
                        gui.exitWithoutAsking();
                    } else if (!swingDialogs.blocked()) {
                        files.saveGame(file -> gui.lobbyCommands().saveGame(file,
                              written -> SwingUtilities.invokeLater(() -> {
                                  saved(written);
                                  if ((written != null) && !closing) {
                                      gui.exitWithoutAsking();
                                  }
                              })));
                    }
                }), () -> SwingUtilities.invokeLater(() -> exitRequested = false))));
        });
    }

    /** Any thread: sends a chat line, or runs a client command such as /who, as the chat box's input line does. */
    void chat(String text) {
        if (text.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ChatterBox chat = gui.getChatterBox();
            if (!closing && chat != null) {
                chat.submit(text);
                queueRefresh();
            }
        });
    }

    /**
     * EDT: the custom deployment zones of the board the map settings make, as the Swing lobby's deployment menu lists
     * them; read again only when the map settings change, as reading them builds the board.
     */
    private Collection<Integer> customZones() {
        MapSettings settings = gui.getClient().getMapSettings();
        if (settings != zonesOf) {
            zonesOf = settings;
            try {
                Board board = settings == null ? null : ServerBoardHelper.getPossibleGameBoard(settings, true);
                customZones = board == null ? List.of() : List.copyOf(board.getCustomDeploymentZones());
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "The lobby could not read the custom deployment zones");
                customZones = List.of();
            }
        }
        return customZones;
    }

    /** Any thread: runs {@code action} with the player actions on the Swing thread, then shows what changed. */
    private void players(Consumer<LobbyPlayerController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            action.accept(new LobbyPlayerController(gui::getClient, gui.lobbyCommands(), dialogs));
            queueRefresh();
        });
    }

    /** Any thread: opens the player settings of the local player or their bot {@code id}. */
    void playerSettings(int id) {
        players(controller -> controller.playerSettings(id));
    }

    /** Any thread: asks for a camouflage for the local player or their bot {@code id}. */
    void camouflage(int id) {
        players(controller -> controller.camouflage(id));
    }

    /** Any thread: moves the local player or their bot {@code id} to {@code team}. */
    void changeTeam(int id, int team) {
        players(controller -> controller.changeTeam(id, team));
    }

    /** Any thread: sets the deployment area of the local player or their bot {@code id}. */
    void changeDeployment(int id, int position) {
        players(controller -> controller.changeDeployment(id, position));
    }

    /** Any thread: asks for the Game Master role, or gives it up. */
    void toggleGameMaster() {
        players(LobbyPlayerController::toggleGameMaster);
    }

    /** Any thread: votes in the running Game Master vote. */
    void voteGameMaster(boolean allow) {
        players(controller -> controller.voteGameMaster(allow));
    }

    /** Any thread: withdraws the local player's own Game Master request. */
    void withdrawGameMaster() {
        players(LobbyPlayerController::withdrawGameMaster);
    }

    /** Any thread: opens the setup of the local bot {@code id}. */
    void botSettings(int id) {
        players(controller -> controller.botSettings(id));
    }

    /** Any thread: removes the local bot {@code id}. */
    void removeBot(int id) {
        players(controller -> controller.removeBot(id));
    }

    /** Any thread: has a new bot take the place of the disconnected player {@code id}. */
    void replaceWithBot(int id) {
        players(controller -> controller.replaceWithBot(id));
    }

    /** Any thread: adds a bot, through its setup dialog, on {@code team}. */
    void addBot(int team) {
        players(controller -> controller.addBot(team));
    }

    // ---- The forces column ----

    /** Any thread: runs {@code action} with the unit and formation actions on the Swing thread, then shows the result. */
    private void forces(Consumer<LobbyForcesController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            action.accept(new LobbyForcesController(gui::getClient, gui.lobbyCommands()));
            refreshSoon();
        });
    }

    /** Any thread: runs one of the forces column's commands; Add unit opens the Requisition on the screen instead. */
    void forcesCommand(LobbyForcesPanel.Command command) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            switch (command) {
                case ADD_UNIT -> { }
                case RANDOM_ARMY -> tools.randomArmy();
                case SKILLS -> tools.skills();
                case NAMES -> tools.names();
                case C3 -> openC3(List.of());
                case LOAD_LIST -> tools.loadList();
                case SAVE_LIST -> {
                    Player local = gui.getClient().getLocalPlayer();
                    if (local != null) {
                        files.saveUnitList(gui.getClient().getGame(), local, () -> { });
                    }
                }
                case PRINT_LIST -> tools.printList();
                case NEW_FORMATION -> gui.lobbyCommands().forceCreateEmpty();
            }
            refreshSoon();
        });
    }

    // ---- The Requisition ----

    /** Any thread: adds {@code quantity} copies of {@code unit} for {@code ownerId}, as the unit selector does. */
    void requisitionAdd(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            new LobbyRequisitionController(gui::getClient, gui.lobbyCommands(),
                  LobbyRequisitionController.FILES).add(unit, quantity, ownerId, skills);
            refreshSoon();
        });
    }

    /** Any thread: takes the unit {@code id}, which the Requisition added, back out of the game. */
    void requisitionRemove(int id) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            new LobbyRequisitionController(gui::getClient, gui.lobbyCommands(),
                  LobbyRequisitionController.FILES).remove(id);
            refreshSoon();
        });
    }

    /**
     * Any thread: draws catalogue sprites for the local player, as used by the random army dialog.
     */
    void requisitionSprites(List<MekSummary> units) {
        requisitionSprites(units, -1);
    }

    /** Any thread: captures the recipient's camouflage, then queues the newest visible units first. */
    void requisitionSprites(List<MekSummary> units, int ownerId) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            Player owner = ownerId < 0 ? gui.getClient().getLocalPlayer()
                  : gui.getClient().getGame().getPlayer(ownerId);
            if (owner != null) {
                Camouflage camouflage = owner.getCamouflage().clone();
                sprites.request(units.stream().map(unit -> new RequisitionSprite(unit, camouflage)).toList());
            }
        });
    }

    /** The Requisition sprites' thread: draws one unit's sprite on the Swing thread and hands it to the screen. */
    private void drawRequisitionSprite(RequisitionSprite request) {
        if (closing) {
            return;
        }
        MekSummary unit = request.unit();
        Entity entity = LobbyRequisitionController.FILES.read(unit);
        if (entity == null) {
            return;
        }
        String key = LobbyRequisitionPanel.spriteKey(unit);
        try {
            // The tileset and its images are Swing's
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = sprite(entity, request.camouflage());
                if (image != null) {
                    GpuHost.post(() -> {
                        GpuLobbyScreen shown = screen;
                        if (shown != null) {
                            shown.requisitionSprite(key, LobbyRequisition.camouflageKey(request.camouflage()), image);
                        }
                    });
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException failure) {
            LOGGER.warn(failure, "The Requisition could not draw the sprite of {}", unit.getName());
        }
    }

    // ---- The battlefield column ----

    /** Any thread: runs {@code action} with the battlefield actions on the Swing thread, then shows the result. */
    private void battlefield(Consumer<LobbyBattlefieldController> action) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            action.accept(new LobbyBattlefieldController(gui::getClient, lounge(LobbyServices.of(gui))));
            queueRefresh();
        });
    }

    /** The battlefield's actions, the map setup, the board editor as the start screen opens it, and auto-resolve. */
    private LobbyBattlefieldController.Lounge lounge(LobbyServices lounge) {
        return new LobbyBattlefieldController.Lounge() {
            @Override
            public void command(LobbyBattlefieldController.Command command) {
                switch (command) {
                    case CONDITIONS -> openConditions();
                    case GAME_OPTIONS -> openGameOptions();
                    case RANDOM_MAP -> openRandomMap();
                    case LOAD_MAP_SETUP -> loadMapSetup();
                    case SAVE_MAP_SETUP -> saveMapSetup();
                    case VICTORY -> tools.victory();
                    case GENERATE -> gui.getClient().sendLobbyBoardGenerationRequest();
                    case PREVIEW_3D -> LobbyBoardPreviews.preview3D(gui);
                    // The map setup, the board editor and auto-resolve come through their own methods below
                    case MAP_SETUP, BOARD_EDITOR, RUN_AUTO_RESOLVE -> { }
                }
            }

            @Override
            public void mapSetup() {
                openMapSetup();
            }

            @Override
            public void boardEditor() {
                GpuBoardWindow.openEditor(gui.getFrame(), null, false);
            }

            @Override
            public void autoResolve(boolean chances, int runs, int threads) {
                lounge.setAutoResolveSettings(chances, runs, threads);
            }

            @Override
            public LobbyBattlefield.AutoResolve autoResolve() {
                return lounge.autoResolveSettings();
            }

            @Override
            public void runAutoResolve() {
                tools.autoResolve(lounge.autoResolveSettings());
            }
        };
    }

    /** Any thread: runs one of the battlefield column's actions. */
    void battlefieldCommand(LobbyBattlefieldController.Command command) {
        battlefield(controller -> controller.run(command));
    }

    /** Any thread: sets the lobby's auto-resolve settings. */
    void autoResolve(boolean chances, int runs, int threads) {
        battlefield(controller -> controller.autoResolve(chances, runs, threads));
    }

    /** The newest map picture, which a screen shows as it is created; null while none is drawn. */
    @Nullable
    BufferedImage lastMap() {
        return lastMap;
    }

    /**
     * EDT: draws the map again, shortly, when the board or a deployment zone changed: the map the server made, or else
     * the one the map settings make of their fixed boards, as the Swing lobby previews it.
     */
    private void bakeMap() {
        Client client = gui.getClient();
        MapSettings settings = client.getMapSettings();
        Board serverBoard = client.getGame().getBoard();
        if (serverBoard != null && (serverBoard.getWidth() <= 0 || serverBoard.getHeight() <= 0)) {
            serverBoard = null;
        }
        List<Player> players = new ArrayList<>();
        List<List<Integer>> zones = new ArrayList<>();
        for (Player player : client.getGame().getPlayersList()) {
            players.add(player.copy());
            zones.add(List.of(player.getId(), player.getTeam(), player.getStartingPos(), player.getStartWidth(),
                  player.getStartOffset(), player.getStartingAnyNWx(), player.getStartingAnyNWy(),
                  player.getStartingAnySEx(), player.getStartingAnySEy(), player.isObserver() ? 1 : 0));
        }
        Player local = client.getLocalPlayer();
        MapKey key = new MapKey(settings, serverBoard, zones, local == null ? Player.PLAYER_NONE : local.getId());
        if (key.equals(mapKey)) {
            return;
        }
        mapKey = key;
        Board board = serverBoard;
        MapSettings copy = settings == null ? null : MapSettings.getInstance(settings);
        Player localCopy = local == null ? null : local.copy();
        var conditions = client.getGame().getPlanetaryConditions();
        int ticket = ++mapGeneration;
        Runnable bake = () -> new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                Board drawn = board != null ? board
                      : copy == null ? null : ServerBoardHelper.getPossibleGameBoard(copy, true);
                if (drawn == null || drawn.getWidth() <= 0 || drawn.getHeight() <= 0) {
                    return null;
                }
                // The game's likely board: the shader warm-up prepares its programs while the lobby shows.
                GpuShaderWarmup.prepare(drawn, false, conditions);
                return LobbyMapThumbnail.bake(drawn, players, localCopy, LobbyMapThumbnail.LONG_SIDE);
            }

            @Override
            protected void done() {
                if (ticket != mapGeneration || closing) {
                    return;
                }
                BufferedImage image = null;
                try {
                    image = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOGGER.warn(e.getCause(), "The lobby could not draw the map");
                }
                sendMap(image, false);
            }
        }.execute();
        if (mapTimer != null) {
            mapTimer.stop();
        }
        mapTimer = new Timer(MAP_DELAY_MS, event -> bake.run());
        mapTimer.setRepeats(false);
        mapTimer.start();
    }

    private void sendMap(@Nullable BufferedImage image, boolean drawing) {
        lastMap = image;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.map(image, drawing);
            }
        });
    }

    /** What the map picture shows: the map settings, the server's board, each player's zone, and who is local. */
    private record MapKey(@Nullable MapSettings settings, @Nullable Board board, List<List<Integer>> zones,
          int local) {
        @Override
        public boolean equals(Object other) {
            return other instanceof MapKey key && key.settings == settings && key.board == board
                  && key.zones.equals(zones) && key.local == local;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(settings), System.identityHashCode(board), zones, local);
        }
    }

    /** Any thread: orders the units by the order {@code index}. */
    void sortUnits(int index) {
        SwingUtilities.invokeLater(() -> {
            if (!closing) {
                LobbyServices.of(gui).unitOrder().select(index);
                queueRefresh();
            }
        });
    }

    /** Any thread: opens Client Settings over the lobby, whose window hides the menu bar that holds them. */
    void openSettings() {
        SwingUtilities.invokeLater(() -> {
            if (!closing && !swingDialogs.blocked()) {
                gui.openClientSettings();
            }
        });
    }

    /**
     * Any thread: runs the hidden menu bar's item whose action command is {@code command} (an item made from an action
     * answers to its text), as clicking it did.
     */
    void runMenuItem(String command) {
        SwingUtilities.invokeLater(() -> {
            if (closing || swingDialogs.blocked()) {
                return;
            }
            JMenuItem item = menuItem(gui.getMenuBar(), command);
            if (item == null) {
                LOGGER.warn("No lobby menu item {}", command);
            } else if (item.isEnabled()) {
                item.doClick(0);
            }
        });
    }

    private static @Nullable JMenuItem menuItem(MenuElement parent, String command) {
        for (MenuElement element : parent.getSubElements()) {
            if (element instanceof JMenu menu) {
                JMenuItem found = menuItem(menu.getPopupMenu(), command);
                if (found != null) {
                    return found;
                }
            } else if (element instanceof JMenuItem item && command.equals(item.getActionCommand())) {
                return item;
            } else if (element instanceof JPopupMenu popup) {
                JMenuItem found = menuItem(popup, command);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Any thread: asks for a file through the system's dialog and saves the game to it, as the menu bar's Save did. */
    void saveGame() {
        SwingUtilities.invokeLater(() -> {
            if (!closing && !swingDialogs.blocked()) {
                files.saveGame(file -> gui.lobbyCommands().saveGame(file, this::saved));
            }
        });
    }

    /** Any thread: saves the game to the quick save file without asking, as the menu bar's Quick Save did. */
    void quickSave() {
        SwingUtilities.invokeLater(() -> {
            if (!closing) {
                gui.lobbyCommands().quickSave(this::saved);
            }
        });
    }

    /**
     * Whether this lobby can leave for a saved game: only MegaMek's own, whose start screen loads it. A game MekHQ
     * started belongs to its campaign, so its lobby offers no loading.
     */
    static boolean loadsGames() {
        return GpuHost.keepsOpen();
    }

    /** Any thread: asks for a saved game through the system's dialog, then, once confirmed, leaves to load it. */
    void loadGame() {
        SwingUtilities.invokeLater(() -> {
            if (!closing && loadsGames() && !swingDialogs.blocked()) {
                files.loadGame(this::confirmLoad);
            }
        });
    }

    /** Any thread: once confirmed, leaves the lobby to load the quick save, as the menu bar's Quick Load did. */
    void quickLoad() {
        SwingUtilities.invokeLater(() -> {
            if (!closing && loadsGames()) {
                confirmLoad(null);
            }
        });
    }

    /** Swing thread: asks, as the Swing lobby did, whether to close the lobby for {@code file} (null: quick save). */
    private void confirmLoad(@Nullable File file) {
        onScreen(shown -> shown.confirmDialog(Messages.getString("ClientGUI.LoadFromLobby.title"),
              Messages.getString("ClientGUI.LoadFromLobby.message"), () -> SwingUtilities.invokeLater(() -> {
                  if (!closing) {
                      gui.leaveLobbyAndLoad(file);
                  }
              })));
    }

    /** Swing thread: tells the player in the chat where the game was saved, or that it was not. */
    private void saved(@Nullable File file) {
        chatNotice((file == null) ? Messages.getString("Lobby.save.failed")
              : Messages.getString("Lobby.save.saved", file.getAbsolutePath()));
    }

    /** Swing thread: tells the player {@code text} in the chat, as a line from the system that only they see. */
    void chatNotice(String text) {
        ChatterBox chat = gui.getChatterBox();
        if (chat != null) {
            chat.systemMessage(text);
            queueRefresh();
        }
    }

    /** Any thread: opens the configuration of the editable unit {@code id}. */
    void configureUnit(int id) {
        forces(controller -> controller.configure(id));
    }

    /** Any thread: moves dragged units or formations onto {@code target}. */
    void dropForces(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
        forces(controller -> controller.drop(unitIds, formationIds, target));
    }

    /** Any thread: deletes the selected units and formations through the unit menu's confirmation and checks. */
    void deleteForces(List<Integer> unitIds, List<Integer> formationIds) {
        forces(controller -> controller.delete(unitIds, formationIds));
    }

    /**
     * Any thread: builds the unit menu for these units and formations and sends its items to the screen; the menu stays
     * here until one is chosen.
     */
    void unitMenu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView, Set<String> omit) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            Game game = gui.getClient().getGame();
            List<Entity> entities = unitIds.stream().map(game::getEntity).filter(Objects::nonNull).toList();
            List<Force> formations = formationIds.stream().map(game.getForces()::getForce).filter(Objects::nonNull)
                  .toList();
            List<LobbyMenuEntry> entries;
            try {
                unitMenu = LobbyUnitMenu.of(gui.getClient(), gui.lobbyCommands(), entities, formations, formationsView,
                      omit);
                entries = unitMenu.entries();
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The lobby could not read the unit menu");
                unitMenu = null;
                entries = List.of();
            }
            List<LobbyMenuEntry> read = entries;
            GpuHost.post(() -> {
                GpuLobbyScreen shown = screen;
                if (shown != null) {
                    shown.unitMenu(read);
                }
            });
        });
    }

    /** Any thread: runs the item at {@code path} of the unit menu last built. */
    void chooseMenu(List<Integer> path) {
        SwingUtilities.invokeLater(() -> {
            LobbyUnitMenu menu = unitMenu;
            unitMenu = null;
            String command = (menu == null) ? null : menu.command(path);
            if (!closing && command != null) {
                runUnitCommand(command);
            }
        });
    }

    /** Any thread: runs the unit menu's command {@code name} on one unit, as a unit card's icon does. */
    void unitCommand(int unitId, String name) {
        SwingUtilities.invokeLater(() -> {
            if (!closing) {
                runUnitCommand(LobbyUnitMenu.unitCommand(name, unitId));
            }
        });
    }

    /** EDT: runs a unit menu command, natively where the lobby has its own dialog for it. */
    private void runUnitCommand(String command) {
        List<Integer> c3Units = LobbyC3Controller.managerUnits(gui.getClient().getGame(), command);
        if (c3Units != null) {
            openC3(c3Units);
            return;
        }
        if (tools.unitCommand(command)) {
            return;
        }
        unitActions().run(command);
        refreshSoon();
    }

    /** EDT: the unit menu's remaining actions, asking through the lobby's own dialogs. */
    private LobbyUnitActions unitActions() {
        return new LobbyUnitActions(gui.getClient(), gui.lobbyCommands(), files, new LobbyUnitActions.Host() {
            @Override
            public void chooseFacing(BotFacingForm form, Runnable confirmed) {
                onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.facing(form,
                      (work, then) -> edt("facing", work, then), confirmed)));
            }

            @Override
            public void chooseLanceRole(LanceRoleForm form, Runnable confirmed) {
                onScreen(shown -> shown.nativeDialog(dialogs -> dialogs.lanceRole(form,
                      (work, then) -> edt("lance role", work, then), confirmed)));
            }

            @Override
            public void chooseCamo(Entity unit, Consumer<Camouflage> chosen) {
                openCamo(unit.getCamouflageOrElseOwners(), false, List.of(unit), chosen);
            }

            @Override
            public void writeSprite(Entity unit, Camouflage camouflage, boolean damage, File file) {
                try {
                    Image base = MMStaticDirectoryManager.getMekTileset().imageFor(unit);
                    Image sprite = EntityImage.createLobbyIcon(base, camouflage, unit).loadPreviewImage(damage);
                    ImageIO.write((BufferedImage) sprite, "png", file);
                } catch (IOException | RuntimeException failure) {
                    LOGGER.error(failure, "The lobby could not save the sprite to {}", file);
                    onScreen(shown -> shown.messageDialog(Messages.getString("ClientGUI.errorSavingFile"),
                          String.valueOf(failure.getMessage())));
                }
            }
        });
    }

    /** Any thread: opens the C3 network manager on one unit, as the unit menu's C3 items do. */
    void c3Unit(int unitId) {
        SwingUtilities.invokeLater(() -> {
            if (!closing) {
                openC3(List.of(unitId));
            }
        });
    }

    /** EDT: opens the C3 network manager in the forces' place, the units it opens on picked. */
    private void openC3(List<Integer> units) {
        Player local = gui.getClient().getLocalPlayer();
        if (closing || local == null) {
            return;
        }
        Client client = gui.getClient();
        c3 = new LobbyC3Controller(new C3NetworkBoard(client::getGame, local.getId(),
              C3NetworkBoard.Commands.of(gui.lobbyCommands()), units), picture -> GpuHost.post(() -> {
                  GpuLobbyScreen shown = screen;
                  if (shown != null) {
                      shown.c3(picture);
                  }
              }));
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.openC3();
            }
        });
        c3.refresh();
    }

    /** Any thread: runs one of the C3 network manager's actions on its board. */
    void c3(Consumer<C3NetworkBoard> action) {
        SwingUtilities.invokeLater(() -> {
            if (!closing && c3 != null) {
                c3.act(action);
                refreshSoon();
            }
        });
    }

    /** Any thread: the C3 network manager left the centre. */
    void c3Closed() {
        SwingUtilities.invokeLater(() -> c3 = null);
    }

    // ---- The map setup ----

    /** Any thread: the map setup left the centre; its board pictures stop. */
    void mapSetupClosed() {
        SwingUtilities.invokeLater(() -> {
            if (mapSetup != null) {
                mapSetup.dispose();
                mapSetup = null;
            }
        });
    }

    /** Any thread: runs {@code action} on the screen on the GPU thread, if one shows. */
    private void onScreen(Consumer<GpuLobbyScreen> action) {
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                action.accept(shown);
            }
        });
    }

    /** EDT: opens the map setup in the forces' place, as the Swing lobby's Map Setup dialog. */
    private void openMapSetup() {
        if (closing) {
            return;
        }
        if (mapSetup == null) {
            Client client = gui.getClient();
            MapSetupForm form;
            try {
                form = new MapSetupForm(client::getMapSettings, client::getAvailableMapSizes,
                      MapSetupForm.Commands.of(gui.lobbyCommands(), client::sendLobbyBoardGenerationRequest),
                      MapSetupForm::fileTags, BoardArtwork::themes);
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The lobby could not read the map settings");
                return;
            }
            mapSetup = new LobbyMapSetupController(form, client::getGame,
                  (picture, themes) -> onScreen(shown -> shown.mapSetup(picture, themes)),
                  (key, image) -> onScreen(shown -> shown.mapPicture(key, image)));
        }
        onScreen(GpuLobbyScreen::openMapSetup);
        mapSetup.refresh();
    }

    /** The map setup view's side of the window. */
    LobbyMapSetupView.Host mapSetupHost() {
        return new LobbyMapSetupView.Host() {
            @Override
            public void act(Consumer<MapSetupForm> action) {
                mapSetup(controller -> controller.act(action));
            }

            @Override
            public void mapWidth(int width) {
                mapSetup(controller -> {
                    boolean[] nag = new boolean[1];
                    controller.act(form -> nag[0] = form.mapWidth(width));
                    nagOddBoards(nag[0]);
                });
            }

            @Override
            public void boardWidth(int width) {
                mapSetup(controller -> {
                    boolean[] nag = new boolean[1];
                    controller.act(form -> nag[0] = form.boardWidth(width));
                    nagOddBoards(nag[0]);
                });
            }

            @Override
            public void previews(List<String> boards) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing && mapSetup != null) {
                        mapSetup.previews(boards);
                    }
                });
            }

            @Override
            public void inspect(String board, Consumer<BoardSource> ready) {
                mapSetup(controller -> controller.inspect(board, source -> GpuHost.post(() -> ready.accept(source))));
            }

            @Override
            public void run(LobbyMapSetupView.Action action) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing) {
                        mapSetupAction(action);
                    }
                });
            }
        };
    }

    /** Any thread: runs {@code action} on the map setup's controller on the Swing thread, while it shows. */
    private void mapSetup(Consumer<LobbyMapSetupController> action) {
        SwingUtilities.invokeLater(() -> {
            if (!closing && mapSetup != null) {
                action.accept(mapSetup);
                refreshSoon();
            }
        });
    }

    /** EDT: warns of boards of odd width tiled across, unless told not to again, as the Swing map setup does. */
    private void nagOddBoards(boolean due) {
        if (due && LobbyMapSetupController.nagWanted()) {
            onScreen(shown -> shown.oddBoardNag(() -> SwingUtilities.invokeLater(
                  () -> GUIPreferences.getInstance().setNagForOddSizedBoard(false))));
        }
    }

    /** EDT: one of the map setup's toolbar or footer actions. */
    private void mapSetupAction(LobbyMapSetupView.Action action) {
        switch (action) {
            case LOAD -> loadMapSetup();
            case SAVE -> saveMapSetup();
            case BOARD_EDITOR -> battlefieldCommand(LobbyBattlefieldController.Command.BOARD_EDITOR);
            case RANDOM_MAP -> openRandomMap();
            case CONDITIONS -> openConditions();
            case PREVIEW_3D -> {
                previewedFromMapSetup = gui;
                battlefieldCommand(LobbyBattlefieldController.Command.PREVIEW_3D);
            }
            case HELP -> tools.mapSetupHelp();
        }
    }

    /** The map settings the map setup works on, or else the server's. */
    private MapSettings mapSettings() {
        return mapSetup != null ? mapSetup.form().settings() : gui.getClient().getMapSettings();
    }

    /** EDT: asks for a map setup file in the system's file dialog and applies it, sending it to the server. */
    private void loadMapSetup() {
        MapSettings settings = MapSettings.getInstance(mapSettings());
        mapSetupFile(false, file -> {
            if (!file.exists()) {
                onScreen(shown -> shown.messageDialog(Messages.getString("Error"),
                      Messages.getString("ChatLounge.fileNotFound")));
                return;
            }
            try {
                if (mapSetup != null) {
                    mapSetup.form().load(file);
                    mapSetup.act(form -> { });
                } else {
                    new MapSetupForm(() -> settings, gui.getClient()::getAvailableMapSizes,
                          MapSetupForm.Commands.of(gui.lobbyCommands(), () -> { }), board -> "", List::of)
                          .load(file);
                }
                refreshSoon();
            } catch (java.io.IOException | RuntimeException failure) {
                LOGGER.error(failure, "The map setup {} could not be loaded", file);
                onScreen(shown -> shown.messageDialog(Messages.getString("Error"),
                      Messages.getString("ChatLounge.map.problemLoadMapSetup")));
            }
        });
    }

    /** EDT: asks for a file in the system's file dialog and saves the map setup to it. */
    private void saveMapSetup() {
        MapSettings settings = MapSettings.getInstance(mapSettings());
        mapSetupFile(true, file -> {
            try {
                new MapSetupForm(() -> settings, gui.getClient()::getAvailableMapSizes,
                      MapSetupForm.Commands.of(gui.lobbyCommands(), () -> { }), board -> "", List::of).save(file);
            } catch (java.io.IOException | RuntimeException failure) {
                LOGGER.error(failure, "The map setup could not be saved to {}", file);
                onScreen(shown -> shown.messageDialog(Messages.getString("Error"),
                      Messages.getString("ChatLounge.map.problemSaving")));
            }
        });
    }

    /**
     * EDT: asks for a map setup file in the system's file dialog, as the start screen asks for a game; a file to save
     * to gets the XML extension and, unless the system's dialog asked already, a confirmation to replace it.
     *
     * @param chosen takes the file on the Swing thread
     */
    private void mapSetupFile(boolean save, Consumer<File> chosen) {
        String title = Messages.getString(save ? "ChatLounge.map.saveMapSetup" : "ChatLounge.map.loadMapSetup");
        chooseFile(new SystemFileDialog.Request(MapSetupForm.setupFolder(), title,
              new FileNameExtensionFilter(Messages.getString("ChatLounge.map.SetupXMLfiles"), "xml"), save),
              save ? "ChatLounge.map.saveMapSetupReplace" : null, chosen);
    }

    /**
     * EDT: shows the system's file dialog over the lobby, which takes no input meanwhile.
     *
     * @param replaceKey the message asking to replace an existing file, with its name as {0}; null when opening
     */
    void chooseFile(SystemFileDialog.Request request, @Nullable String replaceKey, Consumer<File> chosen) {
        chooseFileOrNone(request, replaceKey, file -> {
            if (file != null) {
                chosen.accept(file);
            }
        });
    }

    /** The lobby's file routes, which ask the system's file dialog; Swing thread. */
    LobbyFiles files() {
        return files;
    }

    /** Any thread: shows Report a Bug natively over the lobby; its button packages the game and the logs. */
    void reportBug() {
        PackageBugReportAction packaging = GpuBugReport.packaging(gui::getClient, files,
              open -> onScreen(shown -> shown.nativeDialog(open)));
        onScreen(shown -> shown.nativeDialog(dialogs -> GpuBugReport.open(dialogs, packaging)));
    }

    /** EDT: asks for the file of a lobby file route in the system's file dialog; {@code chosen} gets null if none. */
    private void chooseFile(LobbyFiles.Ask ask, Consumer<File> chosen) {
        chooseFileOrNone(SystemFileDialog.Request.of(ask), ask.save() ? "Lobby.files.replace" : null, chosen);
    }

    /** EDT: as {@link #chooseFile(SystemFileDialog.Request, String, Consumer)}, but hands null on a cancel. */
    private void chooseFileOrNone(SystemFileDialog.Request request, @Nullable String replaceKey,
          Consumer<File> chosen) {
        LOGGER.info("[LobbyFiles] The system file dialog opens: {}", request.title());
        SystemFileDialog.show(request, swingDialogs::nativeOwner, swingDialogs::parent, this::setInputBlocked, choice -> {
            LOGGER.info("[LobbyFiles] The system file dialog answered: {}", choice == null ? "cancel" : choice.file());
            if (closing || choice == null) {
                chosen.accept(null);
                return;
            }
            File file = choice.file();
            if (replaceKey != null && choice.askReplace()) {
                String text = Messages.getString(replaceKey, file.getName());
                onScreen(shown -> shown.confirmDialog(Messages.getString("ChatLounge.map.confirmReplace"), text,
                      () -> SwingUtilities.invokeLater(() -> chosen.accept(closing ? null : file)),
                      () -> SwingUtilities.invokeLater(() -> chosen.accept(null))));
            } else {
                chosen.accept(file);
            }
        });
    }

    /** EDT: opens the random map settings natively over the lobby; OK sends them to the server. */
    private void openRandomMap() {
        if (closing) {
            return;
        }
        RandomMapForm form;
        List<String> themes;
        try {
            form = new RandomMapForm(MapSettings.getInstance(mapSettings()), gui.getClient()::sendMapSettings);
            form.advanced(GUIPreferences.getInstance().getBoolean(GUIPreferences.RND_MAP_ADVANCED));
            themes = new ArrayList<>(gui.getTilesetManager().getThemes());
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the random map settings");
            return;
        }
        LobbyRandomMapView.Host host = new LobbyRandomMapView.Host() {
            @Override
            public void advanced(boolean advanced) {
                GUIPreferences.getInstance().setValue(GUIPreferences.RND_MAP_ADVANCED, advanced);
            }

            @Override
            public void load(RandomMapForm loaded, Runnable then) {
                SwingUtilities.invokeLater(() -> randomMapFile(false, file -> {
                    try {
                        loaded.load(file);
                    } catch (java.io.IOException | RuntimeException failure) {
                        LOGGER.error(failure, "The random map settings {} could not be loaded", file);
                        onScreen(shown -> shown.messageDialog(Messages.getString("Error"),
                              Messages.getString("ChatLounge.fileNotFound")));
                    }
                }, () -> GpuHost.post(then)));
            }

            @Override
            public void save(RandomMapForm saved, Consumer<Boolean> then) {
                boolean[] done = new boolean[1];
                SwingUtilities.invokeLater(() -> randomMapFile(true, file -> {
                    try {
                        done[0] = saved.applyAndSave(file);
                    } catch (java.io.IOException | RuntimeException failure) {
                        LOGGER.error(failure, "The random map settings could not be saved to {}", file);
                        onScreen(shown -> shown.messageDialog(Messages.getString("Error"),
                              Messages.getString("ChatLounge.map.problemSaving")));
                    }
                }, () -> GpuHost.post(() -> then.accept(done[0]))));
            }

            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyWindow.this.edt("random map settings", work, then);
            }
        };
        onScreen(shown -> shown.randomMapDialog(form, themes, host));
    }

    /**
     * EDT: asks for a random map settings file in the system's file dialog; {@code done} runs after {@code chosen}, or
     * when none was chosen.
     */
    private void randomMapFile(boolean save, Consumer<File> chosen, Runnable done) {
        String title = Messages.getString("RandomMapDialog.title") + " - "
              + Messages.getString(save ? "RandomMapDialog.Save" : "RandomMapDialog.Load");
        SystemFileDialog.Request request = new SystemFileDialog.Request(RandomMapForm.settingsFolder(), title,
              new FileNameExtensionFilter(Messages.getString("ChatLounge.map.SetupXMLfiles"), "xml"), save);
        LOGGER.info("[LobbyFiles] The system file dialog opens: {}", title);
        SystemFileDialog.show(request, swingDialogs::nativeOwner, swingDialogs::parent, this::setInputBlocked, choice -> {
            try {
                if (!closing && choice != null) {
                    chosen.accept(choice.file());
                }
            } finally {
                done.run();
            }
        });
    }
    /** Reads the lobby now and twice more, as the server's answer to a unit change takes a moment to come back. */
    void refreshSoon() {
        queueRefresh();
        for (int delay : new int[] { 300, 1000 }) {
            Timer timer = new Timer(delay, event -> queueRefresh());
            timer.setRepeats(false);
            timer.start();
        }
    }

    /** EDT: the order of the units, and the orders offered. */
    private LobbyForces.Order unitOrder() {
        try {
            LobbyUnitOrder order = LobbyServices.of(gui).unitOrder();
            List<Integer> ids = order.sorted().stream().map(Entity::getId).toList();
            return new LobbyForces.Order(ids, order.names(), order.index());
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not read the unit order");
            return LobbyForces.Order.NONE;
        }
    }

    /**
     * EDT: the lobby sprite of each shown unit as a key, and the images of keys not sent before; a unit a blind drop
     * hides has none.
     */
    private void sendSprites(LobbySnapshot read) {
        if (spritesStale) {
            spritesStale = false;
            spriteKeys.clear();
            sentSprites = Map.of();
        }
        Game game = gui.getClient().getGame();
        Map<Integer, String> keys = new HashMap<>();
        Map<String, BufferedImage> fresh = new HashMap<>();
        for (LobbyUnit unit : read.forces().units()) {
            Entity entity = unit.hidden() ? null : game.getEntity(unit.id());
            if (entity == null) {
                continue;
            }
            Camouflage camouflage = entity.getCamouflageOrElseOwners();
            String key = entity.getChassis() + '|' + entity.getModel() + '|' + camouflage.getCategory() + '|'
                  + camouflage.getFilename();
            keys.put(unit.id(), key);
            if (spriteKeys.add(key)) {
                BufferedImage image = sprite(entity, camouflage);
                if (image != null) {
                    fresh.put(key, image);
                }
            }
        }
        if (keys.equals(sentSprites) && fresh.isEmpty()) {
            return;
        }
        sentSprites = keys;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.sprites(keys, fresh);
            }
        });
    }

    static @Nullable BufferedImage sprite(Entity entity, Camouflage camouflage) {
        try {
            Image base = MMStaticDirectoryManager.getMekTileset().imageFor(entity);
            Image icon = base == null ? null
                  : EntityImage.createLobbyIcon(base, camouflage, entity).loadPreviewImage(false);
            if (icon == null || icon.getWidth(null) <= 0 || icon.getHeight(null) <= 0) {
                return null;
            }
            BufferedImage copy = new BufferedImage(icon.getWidth(null), icon.getHeight(null),
                  BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = copy.createGraphics();
            graphics.drawImage(icon, 0, 0, null);
            graphics.dispose();
            return trimmed(copy);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not draw the sprite of {}", entity.getShortName());
            return null;
        }
    }

    /**
     * The sprite cut to its visible pixels and a pixel of margin: the hex tile leaves wide clear edges round the unit,
     * which would shrink it in the card's box.
     */
    static BufferedImage trimmed(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) {
            return image;
        }
        left = Math.max(0, left - 1);
        top = Math.max(0, top - 1);
        right = Math.min(width - 1, right + 1);
        bottom = Math.min(height - 1, bottom + 1);
        BufferedImage cut = new BufferedImage(right - left + 1, bottom - top + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cut.createGraphics();
        graphics.drawImage(image, -left, -top, null);
        graphics.dispose();
        return cut;
    }

    /** The Swing dialogs of the player actions, owned by the client's hidden frame, which this scene adopts. */
    private final LobbyPlayerController.Dialogs dialogs = new LobbyPlayerController.Dialogs() {
        @Override
        public void playerSettings(Client client) {
            openPlayerSettings(client);
        }

        @Override
        public void camouflage(Camouflage initial, List<Entity> units, Consumer<Camouflage> chosen) {
            openCamo(initial, false, units, chosen);
        }

        @Override
        public void botSetup(@Nullable String name, BehaviorSettings behavior, @Nullable AIType type,
              Consumer<LobbyPlayerController.BotSetup> chosen) {
            openBotSetup(name, behavior, type, chosen);
        }

        @Override
        public @Nullable AbstractClient connectBot(LobbyPlayerController.BotSetup setup) {
            Client client = gui.getClient();
            BotClient bot = BotFactory.createBot(setup.type(), setup.name(), client.getHost(), client.getPort(),
                  setup.behavior());
            bot.setClientGUI(gui);
            bot.getGame().addGameListener(new BotGUI(swingDialogs.parent(), bot));
            boolean connected;
            try {
                connected = bot.connect();
            } catch (Exception failure) {
                LOGGER.error(failure, "The bot {} could not connect", setup.name());
                connected = false;
            }
            if (!connected) {
                String title = Messages.getString("ChatLounge.AlertBot.title");
                String text = Messages.getString("ChatLounge.AlertBot.message");
                GpuHost.post(() -> {
                    GpuLobbyScreen shown = screen;
                    if (shown != null) {
                        shown.messageDialog(title, text);
                    }
                });
                bot.die();
                return null;
            }
            return bot;
        }

        @Override
        public void poll(BooleanSupplier step) {
            long until = System.currentTimeMillis() + BOT_JOIN_WAIT_MS;
            Timer timer = new Timer(BOT_JOIN_POLL_MS, null);
            timer.addActionListener(event -> {
                if (closing || step.getAsBoolean() || System.currentTimeMillis() > until) {
                    timer.stop();
                    queueRefresh();
                }
            });
            timer.start();
        }

        @Override
        public BehaviorSettings favouriteBehavior() {
            BehaviorSettingsFactory factory = BehaviorSettingsFactory.getInstance();
            return factory.getBehaviorOrDefault(
                  PreferenceManager.getClientPreferences().getFavoritePrincessBehaviorSetting(),
                  factory.DEFAULT_BEHAVIOR);
        }
    };

    /** The unit menu's camo action asks through the native chooser while this lobby shows. */
    private final LobbyCommands.CamoPicker camoPicker = this::openCamo;

    // ---- The native dialogs ----

    /** The camo picture the player settings' header and the swatches show, in pixels. */
    private static final int CAMO_PICTURE = 72;
    /** The camo chooser's preview of a camo without units, in pixels. */
    static final int CAMO_PREVIEW = 96;

    /**
     * EDT: reads the camo directory and opens the native camo chooser over the lobby; {@code chosen} runs here, on
     * the Swing thread, with the confirmed camo.
     *
     * @param individual whether the camo is the units' own, which offers going back to the owner's
     * @param units      the units wearing it, of which at most two are previewed
     */
    private void openCamo(Camouflage initial, boolean individual, List<Entity> units, Consumer<Camouflage> chosen) {
        if (closing) {
            return;
        }
        CamoCatalog catalog = camoCatalog(false);
        CamoChoice choice = new CamoChoice(initial, individual);
        List<Entity> previewed = previewUnits(units);
        boolean assets = previewed.stream().anyMatch(entity -> entity instanceof BattlefieldSupportAsset);
        LobbyCamoView.Source source = new LobbyCamoView.Source() {
            @Override
            public CamoCatalog catalog(boolean refresh) {
                return camoCatalog(refresh);
            }

            @Override
            public @Nullable BufferedImage thumbnail(Camouflage camo, int size) {
                return camoPicture(camo, size);
            }

            @Override
            public List<BufferedImage> preview(Camouflage camo) {
                List<BufferedImage> images = new ArrayList<>();
                if (previewed.isEmpty()) {
                    BufferedImage picture = camoPicture(camo, CAMO_PREVIEW);
                    if (picture != null) {
                        images.add(picture);
                    }
                }
                for (Entity entity : previewed) {
                    BufferedImage image = sprite(entity, camo);
                    if (image != null) {
                        images.add(image);
                    }
                }
                return images;
            }
        };
        Consumer<Camouflage> confirmed = camo -> SwingUtilities.invokeLater(() -> {
            if (!closing) {
                chosen.accept(camo);
                queueRefresh();
            }
        });
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.camoDialog(source, catalog, choice, assets, confirmed);
            }
        });
    }

    /** The camo directory with the colour camos, read from disk again when {@code refresh}. */
    static CamoCatalog camoCatalog(boolean refresh) {
        if (refresh) {
            MMStaticDirectoryManager.refreshCamouflageDirectory();
        }
        List<String> colours = new ArrayList<>();
        for (PlayerColour colour : PlayerColour.values()) {
            colours.add(colour.name());
        }
        return new CamoCatalog(IconCatalog.of(MMStaticDirectoryManager.getCamouflage()), colours);
    }

    /** At most two units to preview: an asset and another unit when there are both, as the Swing chooser picks. */
    private static List<Entity> previewUnits(List<Entity> units) {
        Entity asset = units.stream().filter(entity -> entity instanceof BattlefieldSupportAsset).findFirst()
              .orElse(null);
        Entity other = units.stream().filter(entity -> !(entity instanceof BattlefieldSupportAsset)).findFirst()
              .orElse(null);
        if (asset != null && other != null) {
            return List.of(asset, other);
        }
        return List.copyOf(units.subList(0, Math.min(2, units.size())));
    }

    /** The camo's picture, {@code size} pixels square, or null; any thread. */
    static @Nullable BufferedImage camoPicture(Camouflage camo, int size) {
        try {
            Image image = camo.getImage(size);
            if (image == null || image.getWidth(null) <= 0 || image.getHeight(null) <= 0) {
                return null;
            }
            BufferedImage copy = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = copy.createGraphics();
            graphics.drawImage(image, 0, 0, size, size, null);
            graphics.dispose();
            return copy;
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not draw the camo {}", camo);
            return null;
        }
    }

    /** EDT: sends the pictures of the players' camos the screen has not had, for their swatches. */
    private void sendCamos() {
        Map<String, BufferedImage> fresh = new HashMap<>();
        for (Player player : gui.getClient().getGame().getPlayersList()) {
            Camouflage camo = player.getCamouflage();
            String key = LobbySnapshot.camoKey(camo);
            if (!key.isEmpty() && camoKeys.add(key)) {
                BufferedImage picture = camoPicture(camo, CAMO_PICTURE);
                if (picture != null) {
                    fresh.put(key, picture);
                }
            }
        }
        if (!fresh.isEmpty()) {
            GpuHost.post(() -> {
                GpuLobbyScreen shown = screen;
                if (shown != null) {
                    shown.camos(fresh);
                }
            });
        }
    }

    /** The unit menu's Configure and a unit card's double-click open the native unit bay while this lobby shows. */
    private final LobbyCommands.UnitConfigurator unitConfigurator = this::openUnitBay;

    /** The portraits in the unit bay, in pixels. */
    private static final int PORTRAIT_PICTURE = 128;
    /** The unit bay's board picture along its longer side, in pixels: sharp on a screen scaled twice. */
    private static final int ZONE_PICTURE = LobbyMapThumbnail.LONG_SIDE * 3 / 4;

    /**
     * EDT: reads the units into a form and shows the native unit bay in the forces' place; its OK writes them back and
     * sends them here, its Previous and Next do so and then open the unit beside them.
     */
    private void openUnitBay(Client client, List<Entity> units, boolean editable) {
        if (closing || units.isEmpty()) {
            return;
        }
        UnitConfigForm form;
        try {
            form = UnitConfigForm.of(client, gui.getClient(), units, editable);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the units to configure");
            return;
        }
        List<BufferedImage> sprites = new ArrayList<>();
        for (Entity entity : units.subList(0, Math.min(5, units.size()))) {
            BufferedImage image = sprite(entity, entity.getCamouflageOrElseOwners());
            if (image != null) {
                sprites.add(image);
            }
        }
        List<BufferedImage> portraits = new ArrayList<>();
        for (UnitConfigForm.Member member : form.members()) {
            portraits.add(portraitPicture(member.portrait()));
        }
        LobbyUnitBayView.Host host = new LobbyUnitBayView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (!closing) {
                            work.run();
                        }
                    } catch (RuntimeException failure) {
                        LOGGER.error(failure, "The unit bay failed");
                    } finally {
                        GpuHost.post(then);
                        queueRefresh();
                    }
                });
            }

            @Override
            public boolean apply(UnitConfigForm applied) {
                if (!applied.apply()) {
                    return false;
                }
                if (applied.editable()) {
                    gui.lobbyCommands().finishCustomization(applied.applied());
                }
                refreshSoon();
                return true;
            }

            @Override
            public void next(UnitConfigForm applied, boolean forward) {
                Entity next = applied.next(forward);
                if (next != null) {
                    gui.lobbyCommands().customizeMek(next);
                }
            }

            @Override
            public void portrait(UnitConfigForm shown, int slot, Consumer<BufferedImage> chosen) {
                SwingUtilities.invokeLater(() -> {
                    if (closing) {
                        return;
                    }
                    UnitConfigForm.Member member = shown.member(slot);
                    openPortrait(member.portrait(), portrait -> {
                        member.portrait(portrait);
                        BufferedImage picture = portraitPicture(portrait);
                        GpuHost.post(() -> {
                            if (!closing) { chosen.accept(picture); }
                        });
                    });
                });
            }

            @Override
            public void close() {
                GpuLobbyScreen shown = screen;
                if (shown != null) {
                    shown.closeUnitBay();
                }
            }

            @Override
            public Future<?> boardPicture(Consumer<BufferedImage> ready) {
                // The form owns this preview board; it is not the mutable game board.
                return bakeUnitBayMap(form.board(), image -> GpuHost.post(() -> {
                    if (!closing) { ready.accept(image); }
                }));
            }
        };
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.unitBay(form, sprites, portraits, host);
            }
        });
    }

    /** Starts the private preview board's image off the UI threads; completion runs on the EDT. */
    static SwingWorker<BufferedImage, Void> bakeUnitBayMap(Board board, Consumer<BufferedImage> ready) {
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                return LobbyMapThumbnail.bake(board, List.of(), null, ZONE_PICTURE);
            }

            @Override
            protected void done() {
                try {
                    ready.accept(get());
                } catch (CancellationException ignored) {
                    // The bay closed or another unit replaced it.
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    LOGGER.warn(failure.getCause(), "The unit bay could not draw the board");
                }
            }
        };
        worker.execute();
        return worker;
    }

    /** The portrait's picture, {@value #PORTRAIT_PICTURE} pixels square, or null; any thread. */
    private static @Nullable BufferedImage portraitPicture(Portrait portrait) {
        return portraitPicture(portrait, PORTRAIT_PICTURE);
    }

    /** The portrait's picture, centred in a square {@code size} pixels wide, or null; any thread. */
    static @Nullable BufferedImage portraitPicture(Portrait portrait, int size) {
        try {
            Image image = portrait.getBaseImage();
            if (image == null || image.getWidth(null) <= 0 || image.getHeight(null) <= 0) {
                return null;
            }
            return AbstractIcon.scaleAndCenter(image, size);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The lobby could not draw the portrait {}", portrait);
            return null;
        }
    }

    /**
     * EDT: reads the portrait directory and opens the native portrait chooser over the lobby and its unit bay;
     * {@code chosen} runs here, on the Swing thread, with the confirmed portrait.
     */
    private void openPortrait(@Nullable Portrait initial, Consumer<Portrait> chosen) {
        if (closing) {
            return;
        }
        PortraitCatalog catalog = portraitCatalog(false);
        LobbyPortraitView.Source source = new LobbyPortraitView.Source() {
            @Override
            public PortraitCatalog catalog(boolean refresh) {
                return portraitCatalog(refresh);
            }

            @Override
            public @Nullable BufferedImage thumbnail(Portrait portrait, int size) {
                return portraitPicture(portrait, size);
            }
        };
        Consumer<Portrait> confirmed = portrait -> SwingUtilities.invokeLater(() -> {
            if (!closing) {
                chosen.accept(portrait);
            }
        });
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.portraitDialog(source, catalog, initial, confirmed);
            }
        });
    }

    /** The portrait directory, read from disk again when {@code refresh}. */
    static PortraitCatalog portraitCatalog(boolean refresh) {
        if (refresh) {
            MMStaticDirectoryManager.refreshPortraitDirectory();
        }
        return new PortraitCatalog(IconCatalog.of(MMStaticDirectoryManager.getPortraits()));
    }

    /** EDT: reads the player settings of the player {@code client} plays and opens them natively over the lobby. */
    private void openPlayerSettings(Client client) {
        if (closing) {
            return;
        }
        PlayerSettingsForm form;
        List<PlayerSettingsForm.Position> positions;
        try {
            form = PlayerSettingsForm.of(client, gui.lobbyCommands());
            positions = form.positions();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the player settings");
            return;
        }
        BufferedImage camo = camoPicture(form.player().getCamouflage(), CAMO_PICTURE);
        int id = form.player().getId();
        LobbyPlayerSettingsView.Host host = new LobbyPlayerSettingsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (!closing) {
                            work.run();
                        }
                    } catch (RuntimeException failure) {
                        LOGGER.error(failure, "The player settings failed");
                    } finally {
                        GpuHost.post(then);
                        queueRefresh();
                    }
                });
            }

            @Override
            public void saveLoadout(MunitionTree tree, Runnable then) {
                SwingUtilities.invokeLater(() -> files.saveLoadout(tree, () -> GpuHost.post(then)));
            }

            @Override
            public void loadLoadout(Consumer<MunitionTree> then) {
                SwingUtilities.invokeLater(() -> files.loadLoadout(tree -> GpuHost.post(() -> then.accept(tree))));
            }

            @Override
            public void botSettings() {
                GpuLobbyWindow.this.botSettings(id);
            }
        };
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.settingsDialog(form, positions, camo, host);
            }
        });
    }

    /**
     * EDT: reads the bot setup and opens it natively over the lobby; {@code chosen} runs here, on the Swing thread,
     * with the confirmed setup, after the server is told the bot's settings changed, as the Swing dialog tells it.
     *
     * @param name the bot's name, or null for a new bot, which is named in the dialog
     * @param type the bot's AI, or null for a new bot, whose AI is chosen in the dialog
     */
    private void openBotSetup(@Nullable String name, BehaviorSettings behavior, @Nullable AIType type,
          Consumer<LobbyPlayerController.BotSetup> chosen) {
        if (closing) {
            return;
        }
        BotSetupForm form;
        try {
            form = BotSetupForm.of(gui.getClient(), name, behavior, type);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the bot setup");
            return;
        }
        LobbyBotSetupView.Host host = new LobbyBotSetupView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (!closing) {
                            work.run();
                        }
                    } catch (RuntimeException failure) {
                        LOGGER.error(failure, "The bot setup failed");
                    } finally {
                        GpuHost.post(then);
                    }
                });
            }

            @Override
            public void help() {
                tools.princessHelp();
            }

            @Override
            public void confirm(LobbyPlayerController.BotSetup setup) {
                SwingUtilities.invokeLater(() -> {
                    if (closing) {
                        return;
                    }
                    Client client = gui.getClient();
                    client.sendServerChat(Player.PLAYER_NONE,
                          client.getLocalPlayer() + " changed settings for bot " + setup.name());
                    chosen.accept(setup);
                    queueRefresh();
                });
            }
        };
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.botSetupDialog(form, host);
            }
        });
    }

    /** Runs {@code work} on the Swing thread unless the lobby closes, then {@code then} on the GPU thread. */
    void edt(String what, Runnable work, Runnable then) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!closing) {
                    work.run();
                }
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The lobby's {} failed", what);
            } finally {
                GpuHost.post(then);
                queueRefresh();
            }
        });
    }

    /** EDT: reads the planetary conditions and opens them natively over the lobby; OK sends them to the server. */
    private void openConditions() {
        if (closing) {
            return;
        }
        PlanetaryConditionsForm form;
        try {
            form = new PlanetaryConditionsForm(gui.getClient().getGame().getPlanetaryConditions());
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the planetary conditions");
            return;
        }
        LobbyConditionsView.Host host = new LobbyConditionsView.Host() {
            @Override
            public void send(PlanetaryConditions conditions) {
                gui.getClient().sendPlanetaryConditions(conditions);
            }

            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyWindow.this.edt("planetary conditions", work, then);
            }
        };
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.conditionsDialog(form, host);
            }
        });
    }

    /** EDT: the game's options as the lobby edits them, its victory options left to the Victory Conditions. */
    private GameOptionsForm gameOptionsForm() {
        return new GameOptionsForm(gui.getClient().getGame().getOptions(),
              Set.of(VictoryConditionsForm.GROUP), true);
    }

    /**
     * EDT: reads the game options and opens them natively over the lobby; OK sends the changed ones to the server and
     * saves them all as the last used, as the Swing dialog does.
     */
    private void openGameOptions() {
        if (closing) {
            return;
        }
        GameOptionsForm form;
        try {
            form = gameOptionsForm();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the game options");
            return;
        }
        LobbyGameOptionsView.Host host = new LobbyGameOptionsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyWindow.this.edt("game options", work, then);
            }

            @Override
            public void send(String password, Vector<IBasicOption> changed, Vector<IBasicOption> all) {
                if (!changed.isEmpty()) {
                    gui.getClient().sendGameOptions(password, changed);
                }
                GameOptions.saveOptions(all);
            }

            @Override
            public void save(Vector<IBasicOption> all, Runnable then) {
                SwingUtilities.invokeLater(() -> files.saveGameOptions(all, () -> GpuHost.post(then)));
            }

            @Override
            public void load(GameOptions into, Consumer<Boolean> then) {
                SwingUtilities.invokeLater(() -> files.loadGameOptions(into,
                      loaded -> GpuHost.post(() -> then.accept(loaded))));
            }

            @Override
            public void grabBars() {
                for (Entity entity : gui.getClient().getGame().getEntitiesVector()) {
                    if (entity instanceof Mek mek) {
                        mek.setBAGrabBars();
                    } else if (entity instanceof Tank tank) {
                        tank.setBAGrabBars();
                    }
                }
            }
        };
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.gameOptionsDialog(form, host);
            }
        });
    }

    /** Any thread: shows the server's options in the native game options, when they show, as the Swing dialog does. */
    private void gameOptionsChanged() {
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown == null || !shown.gameOptionsOpen()) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (closing) {
                    return;
                }
                GameOptionsForm fresh = gameOptionsForm();
                GpuHost.post(() -> {
                    GpuLobbyScreen now = screen;
                    if (now != null) {
                        now.gameOptionsChanged(fresh);
                    }
                });
            });
        });
    }

    /** GPU thread, as the screen's listener is disposed: returns every adopted dialog to its Swing owner. */
    void disposing() {
        screen = null;
        swingDialogs.released();
    }

    // ---- The scene's life ----

    /** EDT, once the scene has started: the frame stays hidden, and dialogs that floated meanwhile are owned. */
    private void presented() {
        if (closing || active != this) {
            return;
        }
        JFrame frame = gui.getFrame();
        if (frame != null && frame.isVisible()) {
            frame.setVisible(false);
        }
        swingDialogs.presented();
        refresh();
        if (reopenMapSetup) {
            reopenMapSetup = false;
            openMapSetup();
        }
    }

    private void finished(Throwable failure) {
        swingDialogs.uninstall();
        if (active == this || active == null || active.gui != gui) {
            gui.lobbyCommands().camoPicker(null);
        gui.lobbyCommands().unitConfigurator(null);
        gui.lobbyCommands().asks(null);
        }
        gui.getClient().getGame().removeGameListener(gameListener);
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferenceListener);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferenceListener);
        boolean replaced = closing;
        closing = true;
        sprites.stop();
        if (mapSetup != null) {
            mapSetup.dispose();
            mapSetup = null;
        }
        if (mapTimer != null) {
            mapTimer.stop();
        }
        if (active == this) {
            active = null;
        }
        if (failure == null && !replaced && gui.getClient().getGame().getPhase().isLounge()) {
            // Another scene took the window, such as the 3D preview; the lobby comes back when it closes
            displaced = gui;
        }
        if (failure != null && !replaced) {
            LOGGER.error(failure, "The native lobby failed; the client leaves the game");
            // MegaMek's own window that never opened can't show the start screen either; MekHQ's game returns to MekHQ
            boolean exit = GpuHost.keepsOpen() && !GpuHost.everOpened();
            LobbyServices.sceneFailed(gui, failure, exit);
        }
    }

    // ---- Dialogs over the lobby ----

    /**
     * Blocks or restores the screen's input. The native window itself stays enabled, as the start screen's does, so
     * Windows doesn't hand the activation to another application as a modal dialog closes.
     */
    private void setInputBlocked(boolean blocked) {
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.setInputBlocked(blocked);
            }
        });
    }
}
