/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationListener;
import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.lobby.LobbyUnitOrder;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog;
import megamek.client.ui.dialogs.helpDialogs.PrincessHelpDialog;
import megamek.client.ui.panels.phaseDisplay.lobby.MapSetupDialog;
import megamek.client.ui.panels.phaseDisplay.lobby.SwingLobbyServices;
import megamek.client.ui.panels.phaseDisplay.lobby.SwingLobbyUnitActions;
import megamek.client.ui.panels.phaseDisplay.lobby.VictoryConditionsDialog;
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
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.planetaryConditions.PlanetaryConditions;
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
 * <p>Ready runs the lobby's ready step ({@link SwingLobbyServices#ready()}), whose questions ask through
 * {@code SwingLobbyPrompts}: Swing dialogs owned by the hidden client frame. While a red warning blocks readying, the
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

    /** EDT: the lobby holding the window or about to. */
    private static GpuLobbyWindow active;
    /** EDT: the client whose lobby a scene it opened, such as the 3D preview or the board editor, replaced. */
    private static ClientGUI displaced;
    /** The client whose lobby an open from another thread is about to show. */
    private static volatile ClientGUI pending;

    private final ClientGUI gui;
    private final LobbyDialogs swingDialogs = new LobbyDialogs(new LobbyDialogs.Host() {
        @Override
        public JFrame frame() {
            return gui.getFrame();
        }

        @Override
        public GpuNativeDialogOwner nativeOwner() {
            return nativeOwner;
        }

        @Override
        public Rectangle bounds() {
            return GpuHost.swingClientBounds();
        }

        @Override
        public void inputBlocked(boolean blocked) {
            setInputBlocked(blocked);
        }

        @Override
        public void focus() {
            GpuHost.focus();
        }

        @Override
        public boolean closing() {
            return closing;
        }
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
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
            nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
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
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    private volatile boolean closing;
    private volatile GpuNativeDialogOwner nativeOwner;
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
    /** EDT: the sprite keys whose images went to the screen, and the keys of the units it last got. */
    private final Set<String> spriteKeys = new HashSet<>();
    /** The catalogue units whose sprites the Requisition asked for, the latest first, and the thread that draws them. */
    private final LobbySpriteWorker<MekSummary> sprites = new LobbySpriteWorker<>("Requisition sprites",
          this::drawRequisitionSprite);
    private Map<Integer, String> sentSprites = Map.of();
    /** Set when a new screen needs every sprite again. */
    private volatile boolean spritesStale;
    /** EDT: what the map picture last drawn, or being drawn, shows; the newest drawing's number, and its delay. */
    private MapKey mapKey;
    private int mapGeneration;
    private Timer mapTimer;
    private volatile BufferedImage lastMap;

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
        active = window;
        gui.getClient().getGame().addGameListener(window.gameListener);
        window.swingDialogs.install();
        gui.lobbyCommands().camoPicker(window.camoPicker);
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

    private void close() {
        closing = true;
        GpuHost.end(scene);
    }

    /** The client whose lobby this is. */
    ClientGUI gui() {
        return gui;
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
            SwingLobbyServices services = SwingLobbyServices.of(gui);
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
            SwingLobbyServices.of(gui).ready();
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

    /** Any thread: leaves the game the way the client's exit does, after its save question. */
    void leave() {
        SwingUtilities.invokeLater(() -> {
            if (closing || exitRequested) {
                return;
            }
            exitRequested = true;
            try {
                gui.handleExit();
            } finally {
                exitRequested = false;
            }
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

    /** Any thread: runs one of the forces column's commands, most of which open a Swing dialog. */
    void forcesCommand(LobbyForcesPanel.Command command) {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            SwingLobbyServices services = SwingLobbyServices.of(gui);
            switch (command) {
                case ADD_UNIT, RANDOM_ARMY, SKILLS, NAMES, C3 -> services.forcesCommand(
                      SwingLobbyServices.ForcesCommand.valueOf(command.name()), null);
                case LOAD_LIST, SAVE_LIST, PRINT_LIST -> services.forcesCommand(
                      SwingLobbyServices.ForcesCommand.valueOf(command.name()), gui.getClient().getLocalPlayer());
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
     * Any thread: draws the sprites of catalogue {@code units} in the local player's camouflage and hands each to the
     * screen. The files are read on a thread of their own, the latest asked first, as the newest rows are those in view.
     */
    void requisitionSprites(List<MekSummary> units) {
        if (!closing) {
            sprites.request(units);
        }
    }

    /** The Requisition sprites' thread: draws one unit's sprite on the Swing thread and hands it to the screen. */
    private void drawRequisitionSprite(MekSummary unit) {
        if (closing) {
            return;
        }
        Entity entity = LobbyRequisitionController.FILES.read(unit);
        if (entity == null) {
            return;
        }
        String key = LobbyRequisitionPanel.spriteKey(unit);
        try {
            // The tileset and its images are Swing's
            SwingUtilities.invokeAndWait(() -> {
                Player local = gui.getClient().getLocalPlayer();
                BufferedImage image = (local == null) ? null : sprite(entity, local.getCamouflage());
                if (image != null) {
                    GpuHost.post(() -> {
                        GpuLobbyScreen shown = screen;
                        if (shown != null) {
                            shown.requisitionSprite(key, image);
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
            action.accept(new LobbyBattlefieldController(gui::getClient, lounge(SwingLobbyServices.of(gui))));
            queueRefresh();
        });
    }

    /** The battlefield's Swing dialogs and the map setup, with the board editor as the start screen opens it. */
    private LobbyBattlefieldController.Lounge lounge(SwingLobbyServices lounge) {
        return new LobbyBattlefieldController.Lounge() {
            @Override
            public void command(LobbyBattlefieldController.Command command) {
                switch (command) {
                    case CONDITIONS -> openConditions();
                    case GAME_OPTIONS -> openGameOptions();
                    default -> lounge.battlefieldCommand(SwingLobbyServices.BattlefieldCommand.valueOf(command.name()));
                }
            }

            @Override
            public void mapSetup() {
                MapSetupDialog.open(gui);
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
                lounge.runAutoResolve();
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
        int ticket = ++mapGeneration;
        Runnable bake = () -> new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                Board drawn = board != null ? board
                      : copy == null ? null : ServerBoardHelper.getPossibleGameBoard(copy, true);
                if (drawn == null || drawn.getWidth() <= 0 || drawn.getHeight() <= 0) {
                    return null;
                }
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
                SwingLobbyServices.of(gui).unitOrder().select(index);
                queueRefresh();
            }
        });
    }

    /** Any thread: opens the configuration of the editable unit {@code id}. */
    void configureUnit(int id) {
        forces(controller -> controller.configure(id));
    }

    /** Any thread: moves dragged units or formations onto {@code target}. */
    void dropForces(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
        forces(controller -> controller.drop(unitIds, formationIds, target));
    }

    /**
     * Any thread: builds the unit menu for these units and formations and sends its items to the screen; the menu stays
     * here until one is chosen.
     */
    void unitMenu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView) {
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
                unitMenu = LobbyUnitMenu.of(gui.getClient(), gui.lobbyCommands(), entities, formations, formationsView);
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
                new SwingLobbyUnitActions(gui).run(command);
                refreshSoon();
            }
        });
    }

    /** Reads the lobby now and twice more, as the server's answer to a unit change takes a moment to come back. */
    private void refreshSoon() {
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
            LobbyUnitOrder order = SwingLobbyServices.of(gui).unitOrder();
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

    private static @Nullable BufferedImage sprite(Entity entity, Camouflage camouflage) {
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
    private static final int CAMO_PREVIEW = 96;

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
    private static CamoCatalog camoCatalog(boolean refresh) {
        if (refresh) {
            MMStaticDirectoryManager.refreshCamouflageDirectory();
        }
        List<String> colours = new ArrayList<>();
        for (PlayerColour colour : PlayerColour.values()) {
            colours.add(colour.name());
        }
        return new CamoCatalog(CamoCatalog.of(MMStaticDirectoryManager.getCamouflage()), colours);
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
            public void saveLoadout(MunitionTree tree) {
                new SwingLobbyUnitActions(gui).saveLoadout(tree);
            }

            @Override
            public @Nullable MunitionTree loadLoadout() {
                return new SwingLobbyUnitActions(gui).loadLoadout();
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
                SwingUtilities.invokeLater(() -> {
                    if (!closing) {
                        new PrincessHelpDialog(swingDialogs.parent()).setVisible(true);
                    }
                });
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
    private void edt(String what, Runnable work, Runnable then) {
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
              Set.of(VictoryConditionsDialog.VICTORY_OPTIONS_GROUP_NAME), true);
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
            public void save(Vector<IBasicOption> all) {
                File file = GameOptionsDialog.selectGameOptionsFile(swingDialogs.parent(), true);
                if (file != null) {
                    GameOptions.saveOptions(all, file.getAbsolutePath());
                }
            }

            @Override
            public boolean load(GameOptions into) {
                File file = GameOptionsDialog.selectGameOptionsFile(swingDialogs.parent(), false);
                if (file == null) {
                    return false;
                }
                into.loadOptions(file, false);
                return true;
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
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
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
    }

    private void finished(Throwable failure) {
        swingDialogs.uninstall();
        if (active == this || active == null || active.gui != gui) {
            gui.lobbyCommands().camoPicker(null);
        }
        gui.getClient().getGame().removeGameListener(gameListener);
        boolean replaced = closing;
        closing = true;
        sprites.stop();
        if (mapTimer != null) {
            mapTimer.stop();
        }
        if (active == this) {
            active = null;
        }
        if (failure == null && !replaced && gui.getClient().getGame().getPhase().isLounge()) {
            // Another scene took the window, such as the 3D preview; the lobby comes back when it closes
            displaced = gui;
        } else {
            MapSetupDialog.close(gui);
        }
        if (failure != null && !replaced) {
            LOGGER.error(failure, "The native lobby failed; the client leaves the game");
            // MegaMek's own window that never opened can't show the start screen either; MekHQ's game returns to MekHQ
            boolean exit = GpuHost.keepsOpen() && !GpuHost.everOpened();
            SwingLobbyServices.sceneFailed(gui, failure, exit);
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
