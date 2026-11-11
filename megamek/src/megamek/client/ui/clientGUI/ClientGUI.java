/*
 * Copyright (C) 2000-2004 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2004-2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicHTML;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.TimerSingleton;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.commands.*;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListener;
import megamek.client.lobby.DecliningLobbyPrompts;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.MegaMekLabPrint;
import megamek.client.ratgenerator.GenerationContext;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.audio.AudioService;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.audio.SoundType;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.CollapseWarning;
import megamek.client.ui.clientGUI.boardview.IBoardView;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHost;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLobbyWindow;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ChatterBoxOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.KeyBindingsOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.OffBoardTargetOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.PlanetaryConditionsOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.clientGUI.boardview.overlay.TurnDetailsOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.UnitOverviewOverlay;
import megamek.client.ui.clientGUI.boardview.spriteHandler.*;
import megamek.client.ui.clientGUI.boardview.toolTip.TWBoardViewTooltip;
import megamek.client.ui.clientGUI.unitDisplay.IHasUnitDisplay;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.dialogs.*;
import megamek.client.ui.dialogs.BotCommands.BotCommandsDialog;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.dialogs.buttonDialogs.EditBotsDialog;
import megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog;
import megamek.client.ui.dialogs.buttonDialogs.NetworkInformationDialog;
import megamek.client.ui.dialogs.forceDisplay.ForceDisplayDialog;
import megamek.client.ui.dialogs.forceDisplay.ForceDisplayPanel;
import megamek.client.ui.dialogs.helpDialogs.AbstractHelpDialog;
import megamek.client.ui.dialogs.helpDialogs.HelpDialog;
import megamek.client.ui.dialogs.helpDialogs.MMReadMeHelpDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayPanel;
import megamek.client.ui.dialogs.minimap.MinimapDialog;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.client.ui.dialogs.phaseDisplay.NovaNetworkViewDialog;
import megamek.client.ui.dialogs.randomArmy.RandomArmyDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.panels.ReceivingGameDataPanel;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.panels.phaseDisplay.*;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyBoardPreviews;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyServices;
import megamek.client.ui.panels.phaseDisplay.lobby.PlayerSettingsDialog;
import megamek.client.ui.tileset.EntityImage;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.BASE64ToolKit;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.MULVersionValidator;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.HandheldWeapon;
import megamek.common.equipment.ICarryable;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.SensorFamily;
import megamek.common.equipment.WeaponMounted;
import megamek.common.event.*;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.event.player.GamePlayerChatEvent;
import megamek.common.event.player.GamePlayerDisconnectedEvent;
import megamek.common.game.Game;
import megamek.common.hexArea.HexArea;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MULParser;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.moves.MovePath;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityListFile;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.IBomber;
import megamek.common.units.Targetable;
import megamek.common.util.AddBotUtil;
import megamek.common.util.Distractable;
import megamek.common.util.ImageUtil;
import megamek.common.util.StringUtil;
import megamek.common.voting.Poll;
import megamek.common.voting.PollStatus;
import megamek.common.weapons.handlers.WeaponOrderHandler;
import megamek.logging.MMLogger;

public class ClientGUI extends AbstractClientGUI
      implements BoardViewListener, ActionListener, IPreferenceChangeListener, ILocalBots,
                 IDisconnectSilently, IHasUnitDisplay, IHasBoardView, IHasMenuBar, IHasCurrentPanel {
    private final static MMLogger logger = MMLogger.create(ClientGUI.class);

    // region Variable Declarations
    // region action commands
    // region file menu
    // game submenu
    public static final String FILE_GAME_SAVE = "fileGameSave";
    public static final String FILE_GAME_LOAD = "fileGameLoad";
    public static final String FILE_GAME_SAVE_SERVER = "fileGameSaveServer";
    public static final String FILE_GAME_QUICK_SAVE = "fileGameQSave";
    public static final String FILE_GAME_QUICK_LOAD = "fileGameQLoad";
    public static final String FILE_GAME_EDIT_BOTS = "editBots";
    // board submenu
    public static final String BOARD_SAVE_AS = "fileBoardSaveAs";
    public static final String BOARD_SAVE_AS_IMAGE = "fileBoardSaveAsImage";
    public static final String BOARD_SAVE_AS_IMAGE_UNITS = "fileBoardSaveAsImageUnits";

    // unit list submenu
    public static final String FILE_UNITS_REINFORCE = "fileUnitsReinforce";
    public static final String FILE_UNITS_REINFORCE_RAT = "fileUnitsReinforceRAT";
    public static final String FILE_UNITS_REINFORCE_REQUISITION = "fileUnitsReinforceRequisition";
    public static final String FILE_REFRESH_CACHE = "fileRefreshCache";
    public static final String FILE_REBUILD_CACHE = "fileRebuildCache";
    public static final String FILE_UNITS_OPEN = "fileUnitsOpen";
    public static final String FILE_UNITS_SAVE = "fileUnitsSave";
    // endregion file menu

    // region view menu
    public static final String VIEW_INC_GUI_SCALE = "viewIncGUIScale";
    public static final String VIEW_DEC_GUI_SCALE = "viewDecGUIScale";
    public static final String VIEW_FORCE_DISPLAY = "viewForceDisplay";
    public static final String VIEW_NOVA_NETWORKS = "viewNovaNetworks";
    public static final String VIEW_UNIT_DISPLAY = "viewMekDisplay";
    public static final String VIEW_KEYBINDS_OVERLAY = "viewKeyboardShortcuts";
    public static final String VIEW_PLANETARY_CONDITIONS_OVERLAY = "viewPlanetaryConditions";
    public static final String VIEW_MINI_MAP = "viewMinimap";
    public static final String VIEW_UNIT_OVERVIEW = "viewUnitOverview";
    public static final String VIEW_ZOOM_IN = "viewZoomIn";
    public static final String VIEW_LEGACY_2D = "viewLegacy2D";
    public static final String VIEW_GRAPHICS_CLASSIC = "viewGraphicsClassic";
    public static final String VIEW_GRAPHICS_ENHANCED = "viewGraphicsEnhanced";
    public static final String VIEW_ZOOM_OUT = "viewZoomOut";
    public static final String VIEW_ZOOM_RESET = "viewZoomReset";
    public static final String VIEW_ZOOM_OVERVIEW_TOGGLE = "viewZoomOverviewToggle";
    public static final String VIEW_TOGGLE_ISOMETRIC = "viewToggleIsometric";
    public static final String VIEW_TOGGLE_HEX_COORDS = "viewToggleHexCoords";
    public static final String VIEW_TOGGLE_HEX_TYPES = "viewToggleHexTypes";
    public static final String VIEW_LABELS = "viewLabels";
    public static final String VIEW_TOGGLE_FIELD_OF_FIRE = "viewToggleFieldOfFire";
    public static final String VIEW_TOGGLE_FLEE_ZONE = "viewToggleFleeZone";
    public static final String VIEW_TOGGLE_SENSOR_RANGE = "viewToggleSensorRange";
    public static final String VIEW_TOGGLE_FOV_DARKEN = "viewToggleFovDarken";
    public static final String VIEW_TOGGLE_FOV_HIGHLIGHT = "viewToggleFovHighlight";
    public static final String VIEW_TOGGLE_FOV_SPOTTING = "viewToggleFovSpotting";
    public static final String VIEW_TOGGLE_SHOW_OBJECTS = "viewToggleShowObjects";
    public static final String VIEW_TOGGLE_FIRING_SOLUTIONS = "viewToggleFiringSolutions";
    /** The lines between units on every board: C3 networks, spotting for indirect fire and TAG. */
    public static final String VIEW_TOGGLE_UNIT_LINKS = "viewToggleUnitLinks";
    public static final String VIEW_TOGGLE_CF_WARNING = "viewToggleCFWarnings";
    public static final String VIEW_MOVE_ENV = "viewMovementEnvelope";
    public static final String VIEW_TURN_DETAILS_OVERLAY = "viewTurnDetailsOverlay";
    public static final String VIEW_MOVE_MOD_ENV = "viewMovModEnvelope";
    public static final String VIEW_ROUND_REPORT = "viewRoundReport";
    public static final String VIEW_GAME_OPTIONS = "viewGameOptions";
    public static final String GAME_GIVE_UP_GAME_MASTER = "gameGiveUpGameMaster";
    public static final String GAME_REQUEST_GAME_MASTER = "gameRequestGameMaster";
    public static final String GAME_ALL_MG_BURST_ON = "gameAllMgBurstOn";
    public static final String GAME_ALL_MG_BURST_OFF = "gameAllMgBurstOff";
    public static final String GAME_ALL_HOT_LOAD_ON = "gameAllHotLoadOn";
    public static final String GAME_ALL_HOT_LOAD_OFF = "gameAllHotLoadOff";
    public static final String VIEW_NETWORK_INFORMATION = "viewNetworkInformation";
    public static final String VIEW_CLIENT_SETTINGS = "viewClientSettings";
    public static final String VIEW_LOS_SETTING = "viewLOSSetting";
    public static final String VIEW_PLAYER_SETTINGS = "viewPlayerSettings";
    public static final String VIEW_PLAYER_LIST = "viewPlayerList";
    public static final String VIEW_ROUNDS_IN_AIR = "viewRoundsInAir";
    public static final String VIEW_RESET_WINDOW_POSITIONS = "viewResetWindowPos";
    public static final String VIEW_BOT_COMMANDS = "viewBotCommands";
    public static final String VIEW_BOT_COMMANDS_OFF = "viewBotCommandsOff";
    public static final String VIEW_BOT_COMMANDS_FLOAT = "viewBotCommandsFloat";
    public static final String VIEW_BOT_COMMANDS_DOCK = "viewBotCommandsDock";
    // endregion view menu

    /** Bot commands panel location: shown in a floating dialog. */
    public static final int BOT_COMMANDS_LOCATION_FLOATING = 0;
    /** Bot commands panel location: docked into the top of the board area. */
    public static final int BOT_COMMANDS_LOCATION_DOCKED = 1;

    // region fire menu
    public static final String FIRE_SAVE_WEAPON_ORDER = "saveWeaponOrder";
    // endregion fire menu

    // region help menu
    public static final String HELP_CONTENTS = "helpContents";
    public static final String HELP_SKINNING = "helpSkinning";
    public static final String HELP_ABOUT = "helpAbout";
    public static final String HELP_RESET_NAGS = "helpResetNags";
    // endregion help menu
    // endregion action commands

    public static final String CG_BOARD_VIEW = "BoardView";
    public static final String CG_CHAT_LOUNGE = "ChatLounge";
    public static final String CG_STARTING_SCENARIO = "JLabel-StartingScenario";
    public static final String CG_EXCHANGE = "JLabel-Exchange";
    public static final String CG_SELECT_ARTY_AUTO_HIT_HEX_DISPLAY = "SelectArtyAutoHitHexDisplay";
    public static final String CG_VICTORY_SETUP_DISPLAY = "VictorySetupDisplay";
    public static final String CG_DEPLOY_MINEFIELD_DISPLAY = "DeployMinefieldDisplay";
    public static final String CG_DEPLOYMENT_DISPLAY = "DeploymentDisplay";
    public static final String CG_TARGETING_PHASE_DISPLAY = "TargetingPhaseDisplay";
    public static final String CG_PREMOVEMENT_DISPLAY = "PremovementDisplay";
    public static final String CG_MOVEMENT_DISPLAY = "MovementDisplay";
    public static final String CG_OFFBOARD_DISPLAY = "OffboardDisplay";
    public static final String CG_PRE_FIRING = "Prefiring";
    public static final String CG_FIRING_DISPLAY = "FiringDisplay";
    public static final String CG_POINTBLANK_SHOT_DISPLAY = "PointblankShotDisplay";
    public static final String CG_PHYSICAL_DISPLAY = "PhysicalDisplay";
    public static final String CG_PREEND_DECLARATIONS_DISPLAY = "PreEndDeclarationsDisplay";
    public static final String CG_INFANTRY_COMBAT_DISPLAY = "InfantryVsInfantryCombatDisplay";
    public static final String CG_REPORT_DISPLAY = "ReportDisplay";
    public static final String CG_DEFAULT = "JLabel-Default";

    public static final String CG_CHAT_COMMAND_SAVE = "/save";
    public static final String CG_CHAT_COMMAND_LOCAL_SAVE = "/localsave";

    public static final String CG_FILE_URL_START = "file:///";
    public static final String CG_FILEPATH_USER_DIR = "user.dir";
    public static final String CG_FILEPATH_BOARDS = "boards";
    public static final String CG_FILEPATH_DATA = "data";
    public static final String CG_FILENAME_SALVAGE = "salvage";
    public static final String CG_FILEPATH_MUL = "mul";
    public static final String CG_FILE_EXTENSION_BOARD = ".board";
    public static final String CG_FILE_EXTENSION_MUL = ".mul";
    public static final String CG_FILE_EXTENSION_XML = ".xml";
    public static final String CG_FILE_EXTENSION_PNG = ".png";
    public static final String CG_FILE_FORMAT_NAME_PNG = "png";

    // a frame, to show stuff in
    private final JPanel clientGuiPanel = new JPanel();

    // A menu bar to contain all actions.
    protected CommonMenuBar menuBar;
    private AbstractHelpDialog help;
    private CommonSettingsDialog commonSettingsDialog;

    public MegaMekController controller;
    private ChatterBox cb;
    private boolean wasBoardFocused = false;
    private MovementEnvelopeSpriteHandler movementEnvelopeHandler;
    private MovementModifierSpriteHandler movementModifierSpriteHandler;
    private FleeZoneSpriteHandler fleeZoneSpriteHandler;
    private SensorRangeSpriteHandler sensorRangeSpriteHandler;
    private CollapseWarningSpriteHandler collapseWarningSpriteHandler;
    private SawClearingSpriteHandler sawClearingSpriteHandler;
    private GroundObjectSpriteHandler groundObjectSpriteHandler;
    private FiringSolutionSpriteHandler firingSolutionSpriteHandler;
    private FiringArcSpriteHandler firingArcSpriteHandler;

    private JPanel panTop;
    private JSplitPane splitPaneA;
    private JPanel panA1;
    private JPanel panA2;

    private UnitDisplayPanel unitDisplayPanel;
    private final UnitDisplayState unitDisplayState;
    private UnitDisplayDialog unitDisplayDialog;

    private BotCommandsDialog botCommandsDialog;
    private BotCommandsPanel botCommandsPanel;
    private CommandBarPanel commandBarPanel;

    public ForceDisplayPanel forceDisplayPanel;
    private ForceDisplayDialog forceDisplayDialog;
    private MapMenu popup;
    private final Map<Integer, RulerDialog> rulers = new HashMap<>();
    protected JComponent curPanel;
    /** Open the default board once per game; later phase changes preserve a manual choice or rendering fallback. */
    private boolean boardViewChosen;
    private LobbyCommands lobbyCommands;

    private final ConcurrentLinkedQueue<Runnable> toastDripQueue = new ConcurrentLinkedQueue<>();
    private javax.swing.Timer toastDripTimer;
    /** Normalized report entries already shown as toasts this phase; see {@link ReportToastFormatter#formatReport}. */
    private final Set<String> toastedReportEntries = new HashSet<>();

    // some dialogs...
    private GameOptionsDialog gameOptionsDialog;
    private NetworkInformationDialog networkInformationDialog;
    private PlayerListDialog playerListDialog;
    private RoundsInAirDialog roundsInAirDialog;
    private RandomArmyDialog randomArmyDialog;
    /** The poll a running Game Master vote is taken in; open only while a vote runs. */
    /**
     * Save and Open dialogs for MegaMek Unit List (mul) files.
     */
    private JFileChooser dlgLoadList;
    private JFileChooser dlgSaveList;
    private JFileChooser dlgSaveGifList;
    private final Client client;

    private File curFileBoardImage;
    private File curFileBoard;

    /**
     * Have we prompted about bing?
     */
    private boolean firstBing = true;
    /**
     * Set by {@link #die()}. A game event still being handled then (its prompt's nested event loop closed the game)
     * asks nothing more: a Swing prompt would outlive the game and block the start screen.
     */
    private volatile boolean dead = false;

    /**
     * Map each phase to the name of the card for the main display area.
     */
    private final Map<String, String> mainNames = new HashMap<>();

    private MiniReportDisplayPanel miniReportDisplayPanel;
    private MiniReportDisplayDialog miniReportDisplayDialog;

    /**
     * Boolean indicating whether client should be disconnected without a pop-up warning
     **/
    private boolean disconnectQuietly = false;

    /**
     * The <code>JPanel</code> containing the main display area.
     */
    private final JPanel panMain = new JPanel();

    /**
     * The <code>CardLayout</code> of the main display area.
     */
    private final CardLayout cardsMain = new CardLayout();

    /**
     * Map each phase to the name of the card for the secondary area.
     */
    private final Map<String, String> secondaryNames = new HashMap<>();

    /**
     * The <code>JPanel</code> containing the secondary display area.
     */
    private final JPanel panSecondary = new JPanel();

    private ReportDisplay reportDisplay;

    private StatusBarPhaseDisplay currPhaseDisplay;

    /**
     * The <code>CardLayout</code> of the secondary display area.
     */
    private final CardLayout cardsSecondary = new CardLayout();

    /**
     * Map phase component names to phase component objects.
     */
    private final Map<String, JComponent> phaseComponents = new HashMap<>();

    /**
     * Flag that indicates whether hotkeys should be ignored or not. This is used for disabling hot keys when various
     * dialogs are displayed.
     */
    private boolean ignoreHotKeys = false;

    /**
     * Keeps track of the Entity ID for the entity currently taking a pointblank shot.
     */
    private int pointblankEID = Entity.NONE;

    /**
     * Audio system
     */
    private final AudioService audioService = new SoundManager();

    private Coords currentHex;

    private boolean showFleeZone = false;

    private final TilesetManager tilesetManager;

    // endregion Variable Declarations

    /**
     * Construct a client which will display itself in a new frame. It will not try to connect to a server yet. When the
     * frame closes, this client will clean up after itself as much as possible, but will not call System.exit().
     */
    public ClientGUI(Client client, MegaMekController c) {
        super(client);
        this.client = client;
        controller = c;
        // Dialogs that know only their parent frame find their client here (forFrame)
        frame.getRootPane().putClientProperty(ClientGUI.class, this);
        // Only a bot's client draws its own 2D board; a player's game shows the GPU board
        boardViewsContainer.setLegacy2DEnabled(client instanceof BotClient);
        initializeSpriteHandlers();
        panMain.setLayout(cardsMain);
        panSecondary.setLayout(cardsSecondary);

        clientGuiPanel.setLayout(new BorderLayout());
        ComponentListener resizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent evt) {
                boardViewsContainer.getPanel().setPreferredSize(clientGuiPanel.getSize());
            }
        };
        clientGuiPanel.addComponentListener(resizeListener);
        clientGuiPanel.add(panMain, BorderLayout.CENTER);
        clientGuiPanel.add(panSecondary, BorderLayout.SOUTH);

        audioService.loadSoundFiles();

        unitDisplayState = new UnitDisplayState(this);
        try {
            tilesetManager = new TilesetManager(client.getGame());
        } catch (IOException e) {
            // this is fatal
            throw new RuntimeException(e);
        }

        registerCommand(new HelpCommand(this));
        registerCommand(new MoveCommand(this));
        registerCommand(new RulerCommand(this));
        registerCommand(new ShowEntityCommand(this));
        registerCommand(new FireCommand(this));
        registerCommand(new DeployCommand(this));
        registerCommand(new AddBotCommand(this));
        registerCommand(new AssignNovaNetworkCommand(this));
        registerCommand(new SituationReportCommand(this));
        registerCommand(new LookCommand(this));
        registerCommand(new ChatCommand(this));
        registerCommand(new DoneCommand(this));
        ShowTileCommand tileCommand = new ShowTileCommand(this);
        registerCommand(tileCommand);
        for (String direction : ShowTileCommand.directions) {
            clientCommands.put(direction.toLowerCase(), tileCommand);
        }
        registerCommand(new HelpCommand(this));
        registerCommand(new BotHelpCommand(this));
    }

    private void initializeFocusTracking() {
        boardViewsContainer.getPanel().addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                wasBoardFocused = true;
            }

            @Override
            public void focusLost(FocusEvent e) {
                wasBoardFocused = false;
                setChatBoxActive(false);
            }
        });
    }

    @Override
    public BoardView getBoardView() {
        return (BoardView) boardViews.get(0);
    }

    @Deprecated(since = "0.51.0", forRemoval = true)
    public BoardToastOverlay getToastOverlay() {
        return getCurrentBoardState().map(state -> state.getOverlay(BoardToastOverlay.class)).orElse(null);
    }

    /**
     * Shows a toast notification on the board view. Safe to call even when the toast overlay has not been initialized
     * yet (e.g., during the lobby phase).
     *
     * @param level the severity level determining color and default duration
     * @param text  the message text to display
     */
    public void addToast(ToastLevel level, String text) {
        addToast(level, text, null);
    }

    /**
     * Shows a toast notification with the given entity's sprite icon on the board view.
     *
     * <p>This is the single entry point for every toast in the client, server-raised ones included, so the player's
     * {@link GUIPreferences#TOAST_ENABLED} setting is enforced here and nowhere else.</p>
     *
     * @param level  the severity level determining color and default duration
     * @param text   the message text to display
     * @param entity the entity whose icon to show, or {@code null} for text-only
     */
    public void addToast(ToastLevel level, String text, @Nullable Entity entity) {
        String entityLabel = (entity != null) ?
              entity.getShortName() + " [" + entity.getId() + "]" :
              "no entity";
        // Gate before normalizing: normalizeToastText is regex-heavy and only the shown path needs the
        // cleaned text. On the suppressed path log the raw text so switching toasts off does not pay the
        // normalization cost for every would-be toast.
        if (!GUIP.getToastEnabled()) {
            logger.debug("[Toast] suppressed [{}] ({}) - toasts switched off in client settings: {}",
                  level, entityLabel, text);
            return;
        }
        String normalizedText = ReportToastFormatter.normalizeToastText(text);
        // The native battle window keeps its own stack, so it must not depend on the Swing overlay existing.
        GpuBoardWindow.toast(this, level, normalizedText, entity);
        BoardToastOverlay toastOverlay = getToastOverlay();
        if (toastOverlay == null) {
            logger.debug("[Toast] not on the Swing board [{}] ({}) - overlay not initialized yet: {}",
                  level, entityLabel, normalizedText);
            return;
        }
        logger.debug("[Toast] shown [{}] ({}): {}", level, entityLabel, normalizedText);
        toastOverlay.show(level, normalizedText, entity);
    }

    /**
     * Maps the layer-neutral severity carried by a {@link GameToastEvent} to the board view's toast styling.
     *
     * @param level the severity from the server-sent toast event
     *
     * @return the matching {@link ToastLevel} used to color and time the toast
     */
    private static ToastLevel toastLevelFor(GameToastEvent.Level level) {
        return switch (level) {
            case SUCCESS -> ToastLevel.SUCCESS;
            case WARNING -> ToastLevel.WARNING;
            case ERROR -> ToastLevel.ERROR;
            case INFO -> ToastLevel.INFO;
            case GAMEMASTER -> ToastLevel.GAMEMASTER;
        };
    }

    /**
     * Shows a progress toast for each of the local player's platoons that is busy raising or dismantling a bridge
     * (TO:AUE). Called once per round at the start of the movement phase: a busy platoon is eligible only in the
     * movement phase (movement-only, so it can continue/cancel/pause/resume) and takes no other action, so this is its
     * main per-turn feedback besides the hex indicator and the END phase report.
     */
    private void showBridgeBuildProgressToasts() {
        for (Entity entity : getClient().getGame().getEntitiesVector()) {
            if (!(entity instanceof ConvInfantry convInfantry) || !convInfantry.isBusyWithBridge()
                  || (convInfantry.getBridgeTargetCoords() == null)
                  || (entity.getOwnerId() != getClient().getLocalPlayer().getId())) {
                continue;
            }
            if (convInfantry.isDismantlingBridge()) {
                // Count the standing structure back down on the build's scale (e.g. 4/6, 3/6, ...)
                addToast(ToastLevel.INFO, Messages.getString("ClientGUI.bridgeDismantleProgress.toast",
                      entity.getShortName(), convInfantry.getBridgeDismantleRemaining(),
                      convInfantry.getBridgeBuildRequiredTurns(),
                      convInfantry.getBridgeTargetCoords().getBoardNum()), entity);
            } else {
                int turnsWorked = Math.min(convInfantry.getBridgeBuildTurns(),
                      convInfantry.getBridgeBuildRequiredTurns());
                String progressKey = convInfantry.isBridgeBuildRepair() ? "ClientGUI.bridgeRepairProgress.toast"
                      : "ClientGUI.bridgeBuildProgress.toast";
                addToast(ToastLevel.INFO, Messages.getString(progressKey,
                      entity.getShortName(), turnsWorked, convInfantry.getBridgeBuildRequiredTurns(),
                      convInfantry.getBridgeTargetCoords().getBoardNum()), entity);
            }
        }
    }

    /**
     * Splits a server-side report HTML stream into per-event toasts via {@link ReportToastFormatter} and drip-feeds
     * them to the overlay on the {@link GUIPreferences#TOAST_DRIP_SECONDS} cadence so each one has time to scroll past
     * before the next appears.
     *
     * <p>Entries already toasted earlier in the same phase are skipped, because a mid-phase report packet can carry
     * events that an earlier packet already delivered. Does nothing at all when the player has switched
     * {@link GUIPreferences#TOAST_REPORT_EVENTS} off; toasts that carry information the report log does not hold are
     * unaffected by that setting.</p>
     *
     * <p>Every reason to skip is checked before the report is formatted, because formatting is what records entries
     * as already toasted. Recording an entry that was never shown would suppress it later, when the player turns toasts
     * back on part way through the same phase.</p>
     */
    private void showReportAsToasts(String defaultPrefix, String report) {
        if (!GUIP.getToastEnabled()) {
            logger.debug("[Toast] report burst suppressed - toasts are switched off in client settings");
            return;
        }
        if (!GUIP.getToastReportEvents()) {
            logger.debug("[Toast] report burst suppressed - round report events are switched off in client settings");
            return;
        }
        List<String> bodies = ReportToastFormatter.formatReport(defaultPrefix, report, toastedReportEntries);
        logger.debug("[Toast] report burst queued {} new event(s); {} entries seen this phase",
              bodies.size(), toastedReportEntries.size());
        for (String body : bodies) {
            toastDripQueue.offer(() -> addToast(ToastLevel.INFO, body));
        }
        startToastDripIfIdle();
    }

    /**
     * Releases queued report toasts one at a time on the {@link GUIPreferences#TOAST_DRIP_SECONDS} cadence so the
     * player can read each one before the next arrives. The first toast of a fresh burst fires immediately; subsequent
     * toasts wait their turn. If a new burst arrives while the timer is already running, its entries are appended to
     * the existing queue and picked up by the running timer (the cadence in effect when the timer was created continues
     * to apply for the duration of that timer; preference changes take effect on the next fresh burst).
     */
    private void startToastDripIfIdle() {
        if (toastDripTimer != null && toastDripTimer.isRunning()) {
            return;
        }
        Runnable first = toastDripQueue.poll();
        if (first != null) {
            first.run();
        }
        if (toastDripQueue.isEmpty()) {
            return;
        }
        int dripMs = Math.max(1, GUIPreferences.getInstance().getToastDripSeconds()) * 1000;
        toastDripTimer = new javax.swing.Timer(dripMs, evt -> {
            Runnable next = toastDripQueue.poll();
            if (next != null) {
                next.run();
            }
            if (toastDripQueue.isEmpty()) {
                ((javax.swing.Timer) evt.getSource()).stop();
                toastDripTimer = null;
            }
        });
        toastDripTimer.start();
    }

    /** The optional Swing inspector. Gameplay code must use {@link #getUnitDisplayState()}. */
    @Nullable
    public UnitDisplayPanel getUnitDisplay() {
        return unitDisplayPanel;
    }

    @Override
    public UnitDisplayState getUnitDisplayState() {
        return unitDisplayState;
    }

    /** Presentation only: a phase may suggest a Swing inspector tab without constructing the inspector. */
    public void showUnitDisplayPanel(String panel) {
        if (unitDisplayPanel != null) { unitDisplayPanel.showPanel(panel); }
    }

    private void initializeLegacy2DUnitDisplay() {
        if (unitDisplayPanel == null) {
            setUnitDisplayDialog(new UnitDisplayDialog(getFrame(), this));
            unitDisplayPanel = new UnitDisplayPanel(this, controller);
            unitDisplayPanel.refreshFromState();
        }
    }

    public UnitDisplayDialog getUnitDisplayDialog() {
        return unitDisplayDialog;
    }

    public void setUnitDisplayDialog(final UnitDisplayDialog unitDisplayDialog) {
        this.unitDisplayDialog = unitDisplayDialog;
        this.unitDisplayDialog.setFocusableWindowState(false);
    }

    public ForceDisplayPanel getForceDisplayPanel() {
        return forceDisplayPanel;
    }

    public void setForceDisplayPanel(final ForceDisplayPanel forceDisplayPanel) {
        this.forceDisplayPanel = forceDisplayPanel;
    }

    public ForceDisplayDialog getForceDisplayDialog() {
        return forceDisplayDialog;
    }

    public void setForceDisplayDialog(final ForceDisplayDialog forceDisplayDialog) {
        this.forceDisplayDialog = forceDisplayDialog;
        this.forceDisplayDialog.setFocusableWindowState(false);
    }

    public MinimapDialog getMiniMapDialog() {
        return miniMaps.get(0);
    }

    public BotCommandsDialog getBotCommandsDialog() {
        return botCommandsDialog;
    }

    public void setBotCommandsDialog(BotCommandsDialog botCommandsDialog) {
        this.botCommandsDialog = botCommandsDialog;
    }

    public BotCommandsPanel getBotCommandsPanel() {
        return botCommandsPanel;
    }

    public MiniReportDisplayPanel getMiniReportDisplay() {
        return miniReportDisplayPanel;
    }

    public void setMiniReportDisplay(final MiniReportDisplayPanel miniReportDisplayPanel) {
        this.miniReportDisplayPanel = miniReportDisplayPanel;
    }

    public MiniReportDisplayDialog getMiniReportDisplayDialog() {
        return miniReportDisplayDialog;
    }

    public void setMiniReportDisplayDialog(final MiniReportDisplayDialog miniReportDisplayDialog) {
        this.miniReportDisplayDialog = miniReportDisplayDialog;
        this.miniReportDisplayDialog.setFocusableWindowState(false);
    }

    public PlayerListDialog getPlayerListDialog() {
        return playerListDialog;
    }

    public void setPlayerListDialog(final PlayerListDialog playerListDialog) {
        this.playerListDialog = playerListDialog;
        this.playerListDialog.setFocusableWindowState(false);
    }

    public RoundsInAirDialog getRoundsInAirDialog() {
        return roundsInAirDialog;
    }

    public void setRoundsInAirDialog(final RoundsInAirDialog roundsInAirDialog) {
        this.roundsInAirDialog = roundsInAirDialog;
        this.roundsInAirDialog.setFocusableWindowState(false);
    }

    @Override
    public void setDisconnectQuietly(boolean quietly) {
        disconnectQuietly = quietly;
    }

    /**
     * Display a system message in the chat box.
     *
     * @param message the <code>String</code> message to be shown.
     */
    public void systemMessage(String message) {
        client.getGame().fireGameEvent(new GamePlayerChatEvent(this, null,
              "\n" + Messages.getString("ChatterBox.MegaMek") + " " + message));
    }

    /**
     * Initializes a number of things about this frame.
     */
    @Override
    protected void initializeFrame() {
        super.initializeFrame();
        menuBar = CommonMenuBar.getMenuBarForGame();
        menuBar.setClientSupplier(() -> client);
        frame.setJMenuBar(menuBar);
    }

    /**
     * Lays out the frame by setting this Client object to take up the full frame display area.
     */
    private void layoutFrame() {
        updateFrameTitle();
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(clientGuiPanel, BorderLayout.CENTER);
        frame.validate();
    }

    /**
     * Opens, follows and closes the Game Master vote's card over the battle as the server shares the vote's state: the
     * card opens when a vote is called, follows the ballots as they come in, and closes when the vote resolves. The
     * outcome itself is announced in the chat. While the libGDX lobby is up, its Game Master seat does all of this
     * instead. Queued on the event thread, which the battle window's cards are opened from.
     */
    private void updateGameMasterVoteDialog(Poll poll) {
        SwingUtilities.invokeLater(() -> {
            if (GpuLobbyWindow.isActiveFor(this)) {
                // The lobby's Game Master seat shows the vote and the new Game Master itself
                GpuBoardWindow.closeGameMasterVote(this);
                return;
            }
            if (poll.getStatus().isResolved()) {
                GpuBoardWindow.closeGameMasterVote(this);
                if (poll.getStatus() == PollStatus.PASSED) {
                    announceNewGameMaster(poll.getRequesterId());
                }
                return;
            }
            if (!GpuBoardWindow.showGameMasterVote(this, poll)) {
                logger.warn("No battle window to show the Game Master vote over");
            }
        });
    }
    /**
     * Keeps the Game menu's Game Master entries in step with who holds the role and whether the game allows one: Give
     * Up while the local player holds it, Become while the role is free, neither while another player has it.
     */
    private void updateGameMasterMenuItems() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }
        boolean gameAllowsGameMaster = client.getGame().getOptions()
              .booleanOption(OptionsConstants.GAME_MASTER_ALLOW);
        boolean anyoneHoldsRole = false;
        for (Player player : client.getGame().getPlayersList()) {
            if (player.isGameMaster()) {
                anyoneHoldsRole = true;
                break;
            }
        }
        menuBar.setGameMasterState(localPlayer.isGameMaster(), gameAllowsGameMaster && !anyoneHoldsRole);
    }

    /** Keeps the lobby's all-units equipment shortcuts in step with the game options that allow them. */
    private void updateLobbyEquipmentMenuItems() {
        menuBar.setLobbyEquipmentOptions(
              client.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_BURST),
              client.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HOT_LOAD));
    }

    /**
     * Asks first, then gives up the Game Master role through the same /gm command that takes it, so the rules stay with
     * the server. The menu entry that leads here is only shown while the local player holds the role.
     */
    private void giveUpGameMaster() {
        int choice = confirm(Messages.getString("ClientGUI.giveUpGameMaster.message"),
              Messages.getString("ClientGUI.giveUpGameMaster.title"),
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            client.sendChat("/gm");
        }
    }

    /**
     * Tells every player who won a passed Game Master vote and how the role is taken away again: the Game Master gives
     * it up (the lobby's GM Mode button or the Game menu's Give Up Game Master entry), or the host turns off Allow Game
     * Master in the game options. Shown after the vote's card closes, so the outcome is not just a line of chat that
     * scrolls away. Runs on the event thread, where the vote's update queued it.
     *
     * @param gameMasterId the id of the player the vote made Game Master
     */
    private void announceNewGameMaster(int gameMasterId) {
        Player gameMaster = client.getGame().getPlayer(gameMasterId);
        String gameMasterName = (gameMaster != null)
              ? gameMaster.getName()
              : Messages.getString("GameMasterVoteDialog.unknownPlayer");
        if (!GpuBoardWindow.announceGameMaster(this, gameMasterName)) {
            logger.warn("No battle window to announce the new Game Master over");
        }
    }

    /**
     * Updates the frame title to show the current round and phase information. The title format is: "PlayerName - Round
     * X - Phase phase - MegaMek" For phases before the game starts (lobby, selection, etc.), only shows: "PlayerName -
     * MegaMek"
     */
    private void updateFrameTitle() {
        StringBuilder title = new StringBuilder(client.getName());

        // mark the local player as the Game Master right after their name, so it is clear who holds the role
        if ((client.getLocalPlayer() != null) && client.getLocalPlayer().isGameMaster()) {
            title.append(Messages.getString("ClientGUI.titleGameMaster"));
        }

        GamePhase phase = client.getGame().getPhase();
        int round = client.getGame().getCurrentRound();

        // Only show round/phase info for in-game phases (after round 0)
        if ((round > 0) && phase.isOnMap()) {
            title.append(Messages.getString("ClientGUI.titleRoundPhase", round, phase.localizedName()));
        }

        title.append(Messages.getString("ClientGUI.clientTitleSuffix"));
        frame.setTitle(title.toString());
    }

    private void initializeSpriteHandlers() {
        movementEnvelopeHandler = new MovementEnvelopeSpriteHandler(this, client.getGame());
        movementModifierSpriteHandler = new MovementModifierSpriteHandler(this, client.getGame());
        FlareSpritesHandler flareSpritesHandler = new FlareSpritesHandler(this, client.getGame());
        sensorRangeSpriteHandler = new SensorRangeSpriteHandler(this, client.getGame());
        collapseWarningSpriteHandler = new CollapseWarningSpriteHandler(this);
        sawClearingSpriteHandler = new SawClearingSpriteHandler(this, client.getGame());
        BridgeBuildSpriteHandler bridgeBuildSpriteHandler = new BridgeBuildSpriteHandler(this, client.getGame());
        BridgeRepairedSpriteHandler bridgeRepairedSpriteHandler = new BridgeRepairedSpriteHandler(this,
              client.getGame());
        BridgeDeploySpriteHandler bridgeDeploySpriteHandler = new BridgeDeploySpriteHandler(this, client.getGame());
        groundObjectSpriteHandler = new GroundObjectSpriteHandler(this, client.getGame());
        ScanSpriteHandler scanSpriteHandler = new ScanSpriteHandler(this, client);
        firingSolutionSpriteHandler = new FiringSolutionSpriteHandler(this, client);
        firingArcSpriteHandler = new FiringArcSpriteHandler(this);
        fleeZoneSpriteHandler = new FleeZoneSpriteHandler(this);
        BotRouteSpriteHandler botRouteSpriteHandler = new BotRouteSpriteHandler(this, client.getGame());
        FortifyBuildSpriteHandler fortifyBuildSpriteHandler = new FortifyBuildSpriteHandler(this, client.getGame());
        DugInSpriteHandler dugInSpriteHandler = new DugInSpriteHandler(this, client.getGame());
        RubbleClearSpriteHandler rubbleClearSpriteHandler = new RubbleClearSpriteHandler(this, client.getGame());
        CraneOperationSpriteHandler craneOperationSpriteHandler = new CraneOperationSpriteHandler(this,
              client.getGame());

        spriteHandlers.addAll(List.of(movementEnvelopeHandler,
              movementModifierSpriteHandler,
              sensorRangeSpriteHandler,
              flareSpritesHandler,
              collapseWarningSpriteHandler,
              sawClearingSpriteHandler,
              bridgeBuildSpriteHandler,
              bridgeRepairedSpriteHandler,
              bridgeDeploySpriteHandler,
              groundObjectSpriteHandler,
              scanSpriteHandler,
              firingSolutionSpriteHandler,
              firingArcSpriteHandler,
              fleeZoneSpriteHandler,
              botRouteSpriteHandler,
              fortifyBuildSpriteHandler,
              dugInSpriteHandler,
              rubbleClearSpriteHandler,
              craneOperationSpriteHandler));
        spriteHandlers.forEach(BoardViewSpriteHandler::initialize);
    }

    @Override
    public void initialize() {
        menuBar = CommonMenuBar.getMenuBarForGame();
        menuBar.setClientSupplier(() -> client);
        frame.setJMenuBar(menuBar);
        initializeFrame();
        super.initialize();
        boardViewsContainer.setName(CG_BOARD_VIEW);
        try {
            client.getGame().addGameListener(gameListener);

            panTop = new JPanel(new BorderLayout());
            panA1 = new JPanel();
            panA1.setVisible(false);
            panA2 = new JPanel();
            panA2.setVisible(false);

            splitPaneA = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

            splitPaneA.setDividerSize(10);
            splitPaneA.setResizeWeight(0.5);

            splitPaneA.setLeftComponent(panA1);
            splitPaneA.setRightComponent(panA2);

            panTop.add(splitPaneA, BorderLayout.CENTER);
            initializeFocusTracking();

            cb = new ChatterBox(this);

        } catch (Exception ex) {
            logger.fatal(ex, "initialize");
            doAlertDialog(Messages.getString("ClientGUI.FatalError.title"),
                  Messages.getString("ClientGUI.FatalError.message") + ex);
            die();
        }

        layoutFrame();
        menuBar.addActionListener(this);
        // the Game Master role may already be settled, taken in the lobby before this window opened, and the
        // options behind the lobby's all-units equipment shortcuts are set before it opens too
        updateGameMasterMenuItems();
        updateLobbyEquipmentMenuItems();

        setForceDisplayPanel(new ForceDisplayPanel(this));
        setForceDisplayDialog(new ForceDisplayDialog(getFrame(), this));
        getForceDisplayDialog().add(getForceDisplayPanel(), BorderLayout.CENTER);
        getForceDisplayDialog().setVisible(false);

        setMiniReportDisplay(new MiniReportDisplayPanel(this));
        setMiniReportDisplayDialog(new MiniReportDisplayDialog(getFrame(), this));
        getMiniReportDisplayDialog().setVisible(false);
        setMiniReportVisible(GUIP.getMiniReportEnabled());

        setPlayerListDialog(new PlayerListDialog(frame, client, false));
        setRoundsInAirDialog(new RoundsInAirDialog(frame, client));

        RulerDialog.color1 = GUIP.getRulerColor1();
        RulerDialog.color2 = GUIP.getRulerColor2();

        setBotCommandsDialog(new BotCommandsDialog(frame, this));
        botCommandsPanel = new BotCommandsPanel(getClient(), audioService, controller, this);
        // The command bar holds the Commands button and is the strip the bot commands panel docks into, so it is put
        // in place first; it stays even when the bot commands panel is off or floating.
        commandBarPanel = new CommandBarPanel(this);
        panTop.add(commandBarPanel, BorderLayout.NORTH);
        maybeShowCommandBar();
        // Place the panel in its configured location (floating dialog or docked strip) before first use.
        setBotCommandsLocation(GUIP.getBotCommandsEnabled());
        // The Bot Commands submenu can no longer carry a working menu accelerator, so wire the show/hide hotkey here.
        if (controller != null) {
            controller.registerCommandAction(KeyCommandBind.BOT_COMMANDS.cmd, GUIP::toggleBotCommandsEnabled);
        }

        // The scene follows the server's first phase: the lobby for the lounge, the board for a scenario or a running
        // game. Until then whatever holds the window stays, so a scenario never flashes the lobby on its way in
        client.changePhase(GamePhase.UNKNOWN);
        // Unit tools are created on demand; constructing them here can wait for the cache on the EDT. The native
        // lobby's Requisition says it is reading the unit catalogue while the cache still loads.
        frame.setVisible(getClient() instanceof BotClient);
        GUIP.addPreferenceChangeListener(this);
    }

    /** The game's chat, which the native lobby shows and sends through; null before {@link #initialize}. */
    public ChatterBox getChatterBox() {
        return cb;
    }

    @Override
    public CommonMenuBar getMenuBar() {
        return menuBar;
    }

    /**
     * Called when the user selects the "Help->About" menu item.
     */
    private void showAbout() {
        if (!inLounge() && GpuBoardWindow.showAbout(this)) {
            return;
        }
        new MMAboutDialog(frame).show();
    }

    private void refreshUnitCache() {
        MekSummaryCache mekSummaryCache = MekSummaryCache.getInstance();
        UnitLoadingDialog unitLoadingDialog = new UnitLoadingDialog(frame, mekSummaryCache,
              Messages.getString("CommonMenuBar.fileUnitsRefreshUnitCache"), !mekSummaryCache.isLoading());
        MekSummaryCache.refreshUnitData(false);
        unitLoadingDialog.setVisible(true);
    }

    private void rebuildUnitCache() {
        MekSummaryCache mekSummaryCache = MekSummaryCache.getInstance();
        UnitLoadingDialog unitLoadingDialog = new UnitLoadingDialog(frame, mekSummaryCache,
              Messages.getString("CommonMenuBar.fileUnitsRebuildUnitCache"), !mekSummaryCache.isLoading());
        MekSummaryCache.rebuildUnitData(false);
        unitLoadingDialog.setVisible(true);
    }

    /**
     * Shows a confirmation dialog when attempting to load a game from the lobby.
     *
     * @return true if the user confirms, false otherwise
     */
    private boolean confirmLoadFromLobby() {
        int result = JOptionPane.showConfirmDialog(
              frame,
              Messages.getString("ClientGUI.LoadFromLobby.message"),
              Messages.getString("ClientGUI.LoadFromLobby.title"),
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE
        );
        return result == JOptionPane.YES_OPTION;
    }

    /**
     * Handles loading a game from the lobby. Shows file chooser first, then confirms disconnect, then loads directly.
     */
    private void loadGameFromLobby() {
        // Show file chooser first (before closing lobby)
        JFileChooser fileChooser = new JFileChooser(MMConstants.SAVEGAME_DIR);
        fileChooser.setDialogTitle(Messages.getString("MegaMek.SaveGameDialog.title"));
        fileChooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().endsWith(MMConstants.SAVE_FILE_EXT)
                      || f.getName().endsWith(MMConstants.SAVE_FILE_GZ_EXT)
                      || f.isDirectory();
            }

            @Override
            public String getDescription() {
                return "Savegames";
            }
        });

        int returnVal = fileChooser.showOpenDialog(frame);
        if (returnVal != JFileChooser.APPROVE_OPTION || fileChooser.getSelectedFile() == null) {
            return;
        }

        File selectedFile = fileChooser.getSelectedFile();

        // Now confirm disconnect
        if (!confirmLoadFromLobby()) {
            return;
        }

        // Close lobby and load the selected file directly (skip HostDialog)
        leaveLobbyAndLoad(selectedFile);
    }

    /**
     * Leaves the lobby without asking and loads {@code file} from the start screen, skipping the host dialog; or the
     * quick save when {@code file} is null. The caller has already confirmed that the lobby is to be left.
     */
    public void leaveLobbyAndLoad(@Nullable File file) {
        if (file == null) {
            controller.setPostUnlaunchAction(() -> controller.megaMekGUI.quickLoadGame());
        } else {
            controller.setPostUnlaunchAction(() -> controller.megaMekGUI.loadGameFile(file));
        }
        setDisconnectQuietly(true);
        die();
    }

    /**
     * Called when the user selects the "Help->Contents" menu item.
     * <p>
     * This method can be called by subclasses.
     */
    private void showHelp() {
        // Do we need to create the "help" dialog?
        if (help == null) {
            help = new MMReadMeHelpDialog(frame);
        }
        // Show the help dialog.
        help.setVisible(true);
    }

    private void showSkinningHowTo() {
        try {
            // Get the correct help file.
            StringBuilder helpPath = new StringBuilder(CG_FILE_URL_START);
            helpPath.append(java.lang.System.getProperty(CG_FILEPATH_USER_DIR));
            if (!helpPath.toString().endsWith(File.separator)) {
                helpPath.append(File.separator);
            }
            helpPath.append(Messages.getString("ClientGUI.skinningHelpPath"));
            URL helpUrl = new URL(helpPath.toString());

            // Launch the help dialog.
            HelpDialog helpDialog = new HelpDialog(Messages.getString("ClientGUI.skinningHelpPath.title"),
                  helpUrl,
                  frame);
            helpDialog.setVisible(true);
        } catch (MalformedURLException ex) {
            logger.error(ex, "showSkinningHowTo");
            doAlertDialog(ex.getMessage(), Messages.getString("ERROR"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Called when the user selects the "View->Client Settings" menu item.
     */
    private void showSettings() {
        // Do we need to create the "settings" dialog?
        if (commonSettingsDialog == null) {
            commonSettingsDialog = new CommonSettingsDialog(frame, this);
        }

        // Show the settings dialog.
        commonSettingsDialog.setVisible(true);
    }

    /** Opens Client Settings, as View &gt; Client Settings does; for the lobby, whose window hides the menu bar. */
    public void openClientSettings() {
        showSettings();
    }

    private void showSettingsSubPage(String subpage) {
        // Do we need to create the "settings" dialog?
        if (commonSettingsDialog == null) {
            commonSettingsDialog = new CommonSettingsDialog(frame, this, subpage);
        } else {
            commonSettingsDialog.selectRoute(subpage);
        }

        // Show the settings dialog.
        commonSettingsDialog.setVisible(true);
    }

    /**
     * Called when the user selects the "View->Game Options" menu item.
     */
    private void showOptions() {
        getGameOptionsDialog().setEditable(client.getGame().getPhase().isLounge());
        // Display the game options' dialog.
        getGameOptionsDialog().update(client.getGame().getOptions());
        getGameOptionsDialog().setVisible(true);
    }

    /**
     * Whether the game is in the lounge, where the menu's dialogs show as they always have; outside it they show
     * natively over the battle window.
     */
    private boolean inLounge() {
        return client.getGame().getPhase().isLounge();
    }

    private void showNetworkInformation() {
        if (!inLounge() && GpuBoardWindow.showNetworkInformation(this)) {
            return;
        }
        // Display the network information screen
        getNetworkInformationDialog().refresh();
        getNetworkInformationDialog().pack();
        getNetworkInformationDialog().setVisible(true);
    }

    public void customizePlayer() {
        PlayerSettingsDialog psd = new PlayerSettingsDialog(this, client, getBoardState());
        psd.setVisible(true);
    }

    /**
     * Called when the user selects the "View->Player List" menu item.
     */
    public void showPlayerList() {
        if (getPlayerListDialog() == null) {
            setPlayerListDialog(new PlayerListDialog(frame, client, false));
        }
        getPlayerListDialog().setVisible(true);
        conditionalRequestFocus(true);
    }

    public void miniReportDisplayAddReportPages() {
        ignoreHotKeys = true;
        if (getMiniReportDisplay() != null) {
            getMiniReportDisplay().addReportPages(client.getGame().getPhase());
        }
        ignoreHotKeys = false;
    }

    public void reportDisplayResetDone() {
        if ((reportDisplay != null) && (!getClient().getLocalPlayer().isDone())) {
            reportDisplay.setDoneEnabled(true);
        }
    }

    public void reportDisplayResetRerollInitiative() {
        if ((reportDisplay != null) && (!getClient().getLocalPlayer().isDone()) && (getClient().getGame()
              .hasTacticalGenius(getClient().getLocalPlayer()))) {
            reportDisplay.resetRerollInitiativeEnabled();
        }
    }

    /**
     * Moves the floating windows to the top left corner of the screen. Only the position changes; each window keeps
     * its size, and the minimap keeps its zoom.
     */
    public void resetWindowPositions() {
        for (MinimapDialog miniMap : miniMaps.values()) {
            miniMap.setLocation(0, 0);
        }
        if (getUnitDisplayDialog() != null) {
            getUnitDisplayDialog().setLocation(0, 0);
        }
        if (getForceDisplayDialog() != null) {
            getForceDisplayDialog().setLocation(0, 0);
        }
        if (getRoundsInAirDialog() != null) {
            getRoundsInAirDialog().setBounds(0,
                                             0,
                                             getRoundsInAirDialog().getWidth(),
                                             getRoundsInAirDialog().getHeight());
        }
        if (getGameOptionsDialog() != null) {
            getGameOptionsDialog().setBounds(0,
                                             0,
                                             getGameOptionsDialog().getWidth(),
                                             getGameOptionsDialog().getHeight());
        }
        if (getNetworkInformationDialog() != null) {
            getNetworkInformationDialog().setBounds(0,
                                                    0,
                                                    getNetworkInformationDialog().getWidth(),
                                                    getNetworkInformationDialog().getHeight());
        }
        if (getMiniReportDisplayDialog() != null) {
            getMiniReportDisplayDialog().setBounds(0,
                  0,
                  getMiniReportDisplayDialog().getWidth(),
                  getMiniReportDisplayDialog().getHeight());
        }
        if (getPlayerListDialog() != null) {
            getPlayerListDialog().setBounds(0, 0, getPlayerListDialog().getWidth(), getPlayerListDialog().getHeight());
        }
        if (gameOptionsDialog != null) {
            gameOptionsDialog.setBounds(0, 0, gameOptionsDialog.getWidth(), gameOptionsDialog.getHeight());
        }
        if (networkInformationDialog != null) {
            networkInformationDialog.setBounds(0,
                  0,
                  networkInformationDialog.getWidth(),
                  networkInformationDialog.getHeight());
        }
        if (commonSettingsDialog != null) {
            commonSettingsDialog.setBounds(0, 0, commonSettingsDialog.getWidth(), commonSettingsDialog.getHeight());
        }
        if (getBotCommandsDialog() != null) {
            getBotCommandsDialog().setLocation(0, 0);
        }
        if (getRandomArmyDialog() != null) {
            getRandomArmyDialog().setBounds(0, 0, getRandomArmyDialog().getWidth(), getRandomArmyDialog().getHeight());
        }
        if (getRandomNameDialog() != null) {
            getRandomNameDialog().setBounds(0, 0, getRandomNameDialog().getWidth(), getRandomNameDialog().getHeight());
        }
    }

    /**
     * Implement the <code>ActionListener</code> interface.
     */
    @Override
    public void actionPerformed(ActionEvent event) {
        switch (event.getActionCommand()) {
            case VIEW_RESET_WINDOW_POSITIONS:
                resetWindowPositions();
                break;
            case FILE_GAME_SAVE:
                saveGame();
                break;
            case FILE_GAME_QUICK_SAVE:
                quickSaveGame();
                break;
            case FILE_GAME_SAVE_SERVER:
                ignoreHotKeys = true;
                String filename = (String) input(Messages.getString("ClientGUI.FileSaveServerDialog.message"),
                      Messages.getString("ClientGUI.FileSaveServerDialog.title"),
                      JOptionPane.QUESTION_MESSAGE,
                      null,
                      MMConstants.DEFAULT_SAVEGAME_NAME);
                if (filename != null) {
                    client.sendChat(CG_CHAT_COMMAND_SAVE + " " + filename);
                }
                ignoreHotKeys = false;
                break;
            case FILE_GAME_LOAD:
                loadGameFromLobby();
                break;
            case FILE_GAME_QUICK_LOAD:
                if (confirmLoadFromLobby()) {
                    leaveLobbyAndLoad(null);
                }
                break;
            case HELP_ABOUT:
                showAbout();
                break;
            case HELP_SKINNING:
                if (inLounge()) {
                    showSkinningHowTo();
                } else if (!GpuBoardWindow.showHelp(this, Messages.getString("ClientGUI.skinningHelpPath.title"),
                      new File(Messages.getString("ClientGUI.skinningHelpPath")))) {
                    logger.warn("No battle window to show the skinning help over");
                }
                break;
            case HELP_CONTENTS:
                if (inLounge()) {
                    showHelp();
                } else if (!GpuBoardWindow.showHelp(this, Messages.getString("CommonMenuBar.HelpMenu"),
                      new File(MMConstants.MEGAMEK_README_FILE_PATH))) {
                    logger.warn("No battle window to show the help over");
                }
                break;
            case FILE_UNITS_SAVE:
                ignoreHotKeys = true;
                doSaveUnit();
                ignoreHotKeys = false;
                break;
            case FILE_UNITS_OPEN:
                ignoreHotKeys = true;
                loadListFile();
                ignoreHotKeys = false;
                break;
            case FILE_UNITS_REINFORCE:
                ignoreHotKeys = true;
                Optional<Player> reinforced = choosePlayerToReinforce();
                if (reinforced != null) {
                    loadListFile(reinforced.orElse(null), true);
                }
                ignoreHotKeys = false;
                break;
            case FILE_UNITS_REINFORCE_RAT:
                ignoreHotKeys = true;
                if (client.getLocalPlayer().getTeam() == Player.TEAM_UNASSIGNED) {
                    addToast(ToastLevel.ERROR,
                          Messages.getString("ClientGUI.openUnitListFileDialog.noReinforceMessage"));
                    ignoreHotKeys = false;
                    return;
                }
                if (inLounge()) {
                    getRandomArmyDialog().setVisible(true);
                } else if (!GpuBoardWindow.showReinforcements(this)) {
                    logger.warn("No battle window to show the random reinforcements over");
                }
                ignoreHotKeys = false;
                break;
            case FILE_UNITS_REINFORCE_REQUISITION:
                if (client.getLocalPlayer().getTeam() == Player.TEAM_UNASSIGNED) {
                    addToast(ToastLevel.ERROR,
                          Messages.getString("ClientGUI.openUnitListFileDialog.noReinforceMessage"));
                    return;
                }
                if (!GpuBoardWindow.showRequisition(this, null)) {
                    logger.warn("No battle window to show the Requisition over");
                }
                break;
            case FILE_REFRESH_CACHE:
                refreshUnitCache();
                break;
            case FILE_REBUILD_CACHE:
                rebuildUnitCache();
                break;
            case VIEW_CLIENT_SETTINGS:
                showSettings();
                break;
            case VIEW_GAME_OPTIONS:
                if (inLounge()) {
                    showOptions();
                } else if (!GpuBoardWindow.showGameOptions(this)) {
                    logger.warn("No battle window to show the game options over");
                }
                break;
            case GAME_GIVE_UP_GAME_MASTER:
                giveUpGameMaster();
                break;
            case GAME_REQUEST_GAME_MASTER:
                // the same /gm command the lobby button uses: it puts the role to a vote, and the vote dialog is
                // where the request is followed and can be withdrawn, so there is nothing to confirm here first
                client.sendChat("/gm");
                break;
            case GAME_ALL_MG_BURST_ON:
            case GAME_ALL_MG_BURST_OFF:
                if (client.getGame().getPhase().isLounge()) {
                    LobbyServices.of(this).setAllBurstMg(event.getActionCommand().equals(GAME_ALL_MG_BURST_ON));
                }
                break;
            case GAME_ALL_HOT_LOAD_ON:
            case GAME_ALL_HOT_LOAD_OFF:
                if (client.getGame().getPhase().isLounge()) {
                    LobbyServices.of(this).setAllHotLoad(event.getActionCommand().equals(GAME_ALL_HOT_LOAD_ON));
                }
                break;
            case VIEW_NETWORK_INFORMATION:
                showNetworkInformation();
                break;
            case VIEW_PLAYER_SETTINGS:
                if (inLounge()) {
                    customizePlayer();
                } else if (!GpuBoardWindow.showPlayerSettings(this)) {
                    logger.warn("No battle window to show the player settings over");
                }
                break;
            case VIEW_PLAYER_LIST:
                GUIP.togglePlayerListEnabled();
                break;
            case VIEW_ROUNDS_IN_AIR:
                GUIP.toggleRoundsInAirEnabled();
                break;
            case VIEW_ROUND_REPORT:
                GUIP.toggleRoundReportEnabled();
                break;
            case VIEW_UNIT_DISPLAY:
                GUIP.toggleUnitDisplay();
                break;
            case VIEW_FORCE_DISPLAY:
                GUIP.toggleForceDisplay();
                break;
            case VIEW_NOVA_NETWORKS:
                showNovaNetworkViewDialog();
                break;
            case VIEW_MINI_MAP:
                GUIP.toggleMinimapEnabled();
                break;
            case VIEW_BOT_COMMANDS_OFF:
                GUIP.setBotCommandsEnabled(false);
                break;
            case VIEW_BOT_COMMANDS_FLOAT:
                GUIP.setBotCommandsLocation(BOT_COMMANDS_LOCATION_FLOATING);
                GUIP.setBotCommandsEnabled(true);
                break;
            case VIEW_BOT_COMMANDS_DOCK:
                GUIP.setBotCommandsLocation(BOT_COMMANDS_LOCATION_DOCKED);
                GUIP.setBotCommandsEnabled(true);
                break;
            case VIEW_TOGGLE_HEX_COORDS:
                GUIP.toggleCoords();
                break;
            case BOARD_SAVE_AS:
                ignoreHotKeys = true;
                boardSaveAs(client.getGame());
                ignoreHotKeys = false;
                break;
            case BOARD_SAVE_AS_IMAGE:
                ignoreHotKeys = true;
                boardSaveAsImage(true);
                ignoreHotKeys = false;
                break;
            case BOARD_SAVE_AS_IMAGE_UNITS:
                ignoreHotKeys = true;
                boardSaveAsImage(false);
                ignoreHotKeys = false;
                break;
            case FILE_GAME_EDIT_BOTS:
                editBots();
                break;
            case VIEW_LOS_SETTING:
                showLOSSettingDialog();
                break;
            case VIEW_ZOOM_IN:
                if (GpuBoardWindow.isActiveFor(this)) { GpuBoardWindow.cameraCommand(this, KeyCommandBind.ZOOM_IN); }
                else { getCurrentBoardView().ifPresent(IBoardView::zoomIn); }
                break;
            // The Renderer menu has saved the graphics (CommonMenuBar); the lobby keeps its own UI until a board shows.
            case VIEW_GRAPHICS_CLASSIC:
            case VIEW_GRAPHICS_ENHANCED:
                if (!client.getGame().getPhase().isLounge()) {
                    openGpuBoard();
                }
                break;
            case VIEW_ZOOM_OUT:
                if (GpuBoardWindow.isActiveFor(this)) { GpuBoardWindow.cameraCommand(this, KeyCommandBind.ZOOM_OUT); }
                else { getCurrentBoardView().ifPresent(IBoardView::zoomOut); }
                break;
            case VIEW_ZOOM_RESET:
                if (GpuBoardWindow.isActiveFor(this)) { GpuBoardWindow.cameraCommand(this, KeyCommandBind.CAMERA_RESET); }
                else { getCurrentBoardView().ifPresent(IBoardView::zoomReset); }
                break;
            case VIEW_ZOOM_OVERVIEW_TOGGLE:
                if (GpuBoardWindow.isActiveFor(this)) { GpuBoardWindow.cameraCommand(this, KeyCommandBind.ZOOM_OVERVIEW_TOGGLE); }
                else { getCurrentBoardView().ifPresent(IBoardView::zoomOverviewToggle); }
                break;
            case VIEW_TOGGLE_ISOMETRIC:
                GUIP.setIsometricEnabled(!GUIP.getIsometricEnabled());
                break;
            case VIEW_TOGGLE_FOV_HIGHLIGHT:
                GUIP.setFovHighlight(!GUIP.getFovHighlight());
                onAllBoardStates(BoardClientState::refreshDisplayables);
                if (client.getGame().getPhase().isMovement()) {
                    onAllBoardStates(BoardClientState::visibilityChanged);
                }
                break;
            case VIEW_TOGGLE_FIELD_OF_FIRE:
                GUIP.setShowFieldOfFire(!GUIP.getShowFieldOfFire());
                onAllBoardStates(BoardClientState::repaint);
                break;
            case VIEW_TOGGLE_FLEE_ZONE:
                toggleFleeZone();
                break;
            case VIEW_TOGGLE_SENSOR_RANGE:
                GUIP.setShowSensorRange(!GUIP.getShowSensorRange());
                break;
            case VIEW_TOGGLE_FOV_DARKEN:
                GUIP.setFovDarken(!GUIP.getFovDarken());
                onAllBoardStates(BoardClientState::refreshDisplayables);
                if (client.getGame().getPhase().isMovement()) {
                    onAllBoardStates(BoardClientState::visibilityChanged);
                }
                break;
            case VIEW_TOGGLE_FOV_SPOTTING:
                GUIP.setFovSpottingMode(!GUIP.getFovSpottingMode());
                onAllBoardStates(BoardClientState::refreshDisplayables);
                onAllBoardStates(BoardClientState::visibilityChanged);
                break;
            case VIEW_TOGGLE_UNIT_LINKS:
                // The board states repaint on the preference change.
                GUIP.toggleShowUnitLinks();
                break;
            case VIEW_TOGGLE_SHOW_OBJECTS:
                // the ground object sprite handler listens for the preference change and re-renders
                GUIP.setShowObjectiveOverlays(!GUIP.getShowObjectiveOverlays());
                break;
            case VIEW_TOGGLE_FIRING_SOLUTIONS:
                GUIP.setShowFiringSolutions(!GUIP.getShowFiringSolutions());
                break;
            case VIEW_TOGGLE_CF_WARNING:
                CollapseWarning.handleActionPerformed();
                break;
            case VIEW_MOVE_ENV:
                GUIP.setMoveEnvelope(!GUIP.getMoveEnvelope());
                if (curPanel instanceof MovementDisplay movementDisplay) {
                    Entity entity = getUnitDisplayState().getCurrentEntity();
                    movementDisplay.computeMovementEnvelope(entity);
                }
                break;
            case VIEW_MOVE_MOD_ENV:
                if (curPanel instanceof MovementDisplay movementDisplay) {
                    movementDisplay.computeModifierEnvelope();
                }
                break;
            case FIRE_SAVE_WEAPON_ORDER:
                Entity ent = getUnitDisplayState().getCurrentEntity();
                if (ent != null) {
                    WeaponOrderHandler.setWeaponOrder(ent.getChassis(),
                          ent.getModel(),
                          ent.getWeaponSortOrder(),
                          ent.getCustomWeaponOrder());
                    client.sendEntityWeaponOrderUpdate(ent);
                }
                break;
        }
    }

    /**
     * Save all the current in use Entities each grouped by player name
     * <p>
     * and a file for salvage
     */
    public void doSaveUnit() {
        for (Player p : getClient().getGame().getPlayersList()) {
            ArrayList<Entity> l = getClient().getGame().getPlayerEntities(p, false);
            // Be sure to include all units that have retreated.
            for (Enumeration<Entity> iter2 = getClient().getGame().getRetreatedEntities(); iter2.hasMoreElements(); ) {
                Entity e = iter2.nextElement();
                if (e.getOwnerId() == p.getId()) {
                    l.add(e);
                }
            }
            saveListFile(l, p.getName());
        }

        // save all destroyed units in a separate "salvage MUL"
        ArrayList<Entity> destroyed = new ArrayList<>();
        Enumeration<Entity> graveyard = getClient().getGame().getGraveyardEntities();
        while (graveyard.hasMoreElements()) {
            Entity entity = graveyard.nextElement();
            if (entity.isSalvage()) {
                destroyed.add(entity);
            }
        }

        if (!destroyed.isEmpty()) {
            String sLogDir = CP.getLogDirectory();
            File logDir = new File(sLogDir);
            if (!logDir.exists()) {
                logDir.mkdir();
            }
            String fileName = CG_FILENAME_SALVAGE + CG_FILE_EXTENSION_MUL;
            if (CP.stampFilenames()) {
                fileName = StringUtil.addDateTimeStamp(fileName);
            }
            File unitFile = new File(sLogDir + File.separator + fileName);
            try {
                // Save the destroyed entities to the file.
                EntityListFile.saveTo(unitFile, destroyed);
            } catch (Exception ex) {
                logger.error(ex, "doSaveUnit");
                doAlertDialog(Messages.getString("ClientGUI.errorSavingFile"), ex.getMessage());
            }
        }
    }

    /**
     * Saves the current settings to the cfg file.
     */
    @Override
    void saveSettings() {
        super.saveSettings();

        // Minimap Dialog
        if (getMiniMapDialog() != null) {
            getMiniMapDialog().saveSettings();
        }

        // Unit Display Dialog
        if (getUnitDisplayDialog() != null) {
            getUnitDisplayDialog().saveSettings();
            saveSplitPaneLocations();
        }

        // Force Display Dialog
        if (getForceDisplayDialog() != null) {
            getForceDisplayDialog().saveSettings();
        }

        // Mini Report Dialog
        if (getMiniReportDisplayDialog() != null) {
            getMiniReportDisplayDialog().saveSettings();
        }

        // Player List Dialog
        if (getPlayerListDialog() != null) {
            getPlayerListDialog().saveSettings();
        }

        // Rounds in Air Dialog
        if (getRoundsInAirDialog() != null) {
            getRoundsInAirDialog().saveSettings();
        }

        // Ruler display
        RulerDialog ruler = getCurrentBoardState().map(state -> rulers.get(state.getBoardId())).orElse(null);
        if ((ruler != null) && (ruler.getSize().width != 0) && (ruler.getSize().height != 0)) {
            GUIP.setRulerPosX(ruler.getLocation().x);
            GUIP.setRulerPosY(ruler.getLocation().y);
            GUIP.setRulerSizeWidth(ruler.getSize().width);
            GUIP.setRulerSizeHeight(ruler.getSize().height);
        }

        // BotCommands Dialog
        if (getBotCommandsDialog() != null) {
            getBotCommandsDialog().saveSettings();
        }
    }

    @Override
    public void die() {
        if (client.isAwaitingSave()) {
            SwingUtilities.invokeLater(this::die);
            return;
        }

        // Capture window positions before anything is torn down.
        saveSettings();

        if (unitDisplayPanel != null) { unitDisplayPanel.disposeDisplay(); }

        dead = true;
        try {
            releaseForDeath();
        } finally {
            // Closes the frame, which the start screen waits for, even when a display failed to let go
            super.die();
        }
    }

    /** Tells every display, dialog and the client to let go, before the frame closes. */
    private void releaseForDeath() {
        // Tell all the displays to remove themselves as listeners.
        GpuBoardWindow.closeFor(this);
        GpuLobbyWindow.closeFor(this);
        LobbyServices.forget(this);
        client.getGame().removeGameListener(gameListener);
        boolean reportHandled = false;
        boardViews().forEach(IBoardView::dispose);
        boardViews.clear();
        miniMaps.values().forEach(MinimapDialog::dispose);
        miniMaps.clear();
        rulers.values().forEach(RulerDialog::dispose);
        rulers.clear();
        boardStates().forEach(BoardClientState::close);
        boardStates.clear();

        for (String s : phaseComponents.keySet()) {
            JComponent component = phaseComponents.get(s);
            if (component instanceof ReportDisplay) {
                if (reportHandled) {
                    continue;
                }
                reportHandled = true;
            }
            if (component instanceof Distractable) {
                ((Distractable) component).removeAllListeners();
            }
        }
        phaseComponents.clear();

        client.die();

        TimerSingleton.getInstance().killTimer();

        if (controller != null) {
            controller.removeAllActions();
            controller.clientGUI = null;
        }

        if (menuBar != null) {
            menuBar.die();
            menuBar = null;
        }

        if (curPanel instanceof StatusBarPhaseDisplay) {
            ((StatusBarPhaseDisplay) curPanel).stopTimer();
        }

        GUIP.removePreferenceChangeListener(this);
        tilesetManager.close();
    }

    public GameOptionsDialog getGameOptionsDialog() {
        if (gameOptionsDialog == null) {
            gameOptionsDialog = new GameOptionsDialog(this);
        }
        return gameOptionsDialog;
    }

    public NetworkInformationDialog getNetworkInformationDialog() {
        if (networkInformationDialog == null) {
            networkInformationDialog = new NetworkInformationDialog(this);
        }
        return networkInformationDialog;
    }

    public void switchPanel(GamePhase phase) {
        // Clear the old panel's listeners.
        if (curPanel instanceof BoardViewListener) {
            boardStates().forEach(b -> b.removeBoardViewListener((BoardViewListener) curPanel));
        }

        if (curPanel instanceof ActionListener) {
            menuBar.removeActionListener((ActionListener) curPanel);
        }

        if (curPanel instanceof Distractable) {
            ((Distractable) curPanel).setIgnoringEvents(true);
        }

        // Get the new panel.
        String name = String.valueOf(phase);
        curPanel = phaseComponents.get(name);
        if (curPanel == null) {
            curPanel = initializePanel(phase);
        }

        // Handle phase-specific items.
        switch (phase) {
            case LOUNGE:
                // reset old report tabs and images, if any
                tilesetManager.reset();
                break;
            case POINTBLANK_SHOT:
            case VICTORY_SETUP:
            case SET_ARTILLERY_AUTO_HIT_HEXES:
            case DEPLOY_MINEFIELDS:
            case DEPLOYMENT:
            case TARGETING:
            case PREMOVEMENT:
            case MOVEMENT:
            case OFFBOARD:
            case PRE_FIRING:
            case FIRING:
            case PHYSICAL:
            case INITIATIVE_REPORT:
            case TARGETING_REPORT:
            case MOVEMENT_REPORT:
            case OFFBOARD_REPORT:
            case FIRING_REPORT:
            case PHYSICAL_REPORT:
            case END_REPORT:
            case VICTORY:
            default:
                break;
        }

        showDefaultBoard(mainNames.get(name));
        maybeShowMinimap();
        maybeShowCommandBar();
        maybeShowBotCommands();
        maybeShowUnitDisplay();
        maybeShowForceDisplay();
        maybeShowMiniReport();
        maybeShowPlayerList();

        cardsMain.show(panMain, mainNames.get(name));
        String secondaryToShow = secondaryNames.get(name);
        // only show the secondary component if there is one to show
        if (secondaryToShow != null) {
            panSecondary.setVisible(true);
            cardsSecondary.show(panSecondary, secondaryNames.get(name));
        } else {
            // otherwise, hide the panel
            panSecondary.setVisible(false);
        }

        // Set the new panel's listeners
        if (curPanel instanceof BoardViewListener listener) {
            boardStates().forEach(b -> b.addBoardViewListener(listener));
        }

        if (curPanel instanceof ActionListener) {
            menuBar.addActionListener((ActionListener) curPanel);
        }

        if (curPanel instanceof Distractable) {
            ((Distractable) curPanel).setIgnoringEvents(false);
        }

        // Make the new panel the focus, if the Client option says so
        if (GUIP.getFocus() && !(client instanceof BotClient)) {
            curPanel.requestFocus();
        }
    }

    /**
     * The lounge shows the native lobby scene, and every later phase the native board, each replacing the other in the
     * one window; the frame stays hidden behind both and shows only when the lobby scene can't start. Before the
     * server sends its first phase the window keeps what it shows (the start screen, or nothing yet for MekHQ), so a
     * scenario or a running game goes straight to the board; between the lounge and the first board phase the lobby
     * stays.
     */
    void showDefaultBoard(String mainPanel) {
        if (getClient() instanceof BotClient) {
            return;
        }
        if (CG_CHAT_LOUNGE.equals(mainPanel)) {
            boardViewChosen = false;
            GpuLobbyWindow.open(this);
            GpuBoardWindow.closeFor(this);
        } else if (client.getGame().getPhase() != GamePhase.UNKNOWN && !boardViewChosen
              && !(CG_DEFAULT.equals(mainPanel) && GpuLobbyWindow.isActiveFor(this))) {
            openGpuBoard();
            GpuLobbyWindow.closeFor(this);
        }
    }

    private void openGpuBoard() {
        boardViewChosen = true;
        GpuBoardWindow.open(this, () -> curPanel);
    }

    /** Construct the legacy map components only when that visualization is requested. */
    public void setLegacy2DViewEnabled(boolean enabled) {
        if (enabled) {
            boardStates().forEach(this::createLegacy2DBoardView);
        } else {
            rulers.values().forEach(RulerDialog::dispose);
            rulers.clear();
            boardStates().forEach(state -> { state.setFirstLOS(null); state.drawRuler(null); });
            boardViews().forEach(IBoardView::dispose);
            boardViews.clear();
        }
        boardViewsContainer.setLegacy2DEnabled(enabled);
        boardViewsContainer.updateMapTabs();
    }

    /** Reapply auxiliary window presentation after changing board windows, without changing saved docking choices. */
    public void refreshAuxiliaryWindows() {
        setUnitDisplayVisible(GUIP.getUnitDisplayEnabled());
        setForceDisplayVisible(GUIP.getForceDisplayEnabled());
        setMiniReportLocation(GUIP.getMiniReportEnabled());
        maybeShowMinimap();
        setMapVisible(GUIP.getMinimapEnabled());
        setPlayerListVisible(GUIP.getPlayerListEnabled());
        setRoundsInAirVisible(GUIP.getRoundsInAirEnabled());
        maybeShowBotCommands();
    }

    public void updateButtonPanel(GamePhase phase) {
        if ((currPhaseDisplay != null) && client.getGame().getPhase().equals(phase)) {
            currPhaseDisplay.setupButtonPanel();
        }
    }

    private JComponent initializePanel(GamePhase phase) {
        // Create the components for this phase.
        String name = String.valueOf(phase);
        JComponent component;
        String secondary = null;
        String main;
        switch (phase) {
            case LOUNGE:
                // the lobby shows in the native window; the hidden frame keeps an empty card for the phase
                component = new JPanel();
                main = CG_CHAT_LOUNGE;
                component.setName(main);
                panMain.add(component, main);
                break;
            case STARTING_SCENARIO:
                component = new StartingScenarioPanel();
                main = CG_STARTING_SCENARIO;
                component.setName(main);
                panMain.add(component, main);
                break;
            case EXCHANGE:
                LobbyBoardPreviews.closePreviews(this);
                component = new ReceivingGameDataPanel();
                main = CG_EXCHANGE;
                component.setName(main);
                panMain.add(component, main);
                break;
            case VICTORY_SETUP:
                component = new VictorySetupDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_VICTORY_SETUP_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case SET_ARTILLERY_AUTO_HIT_HEXES:
                component = new SelectArtyAutoHitHexDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_SELECT_ARTY_AUTO_HIT_HEX_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case DEPLOY_MINEFIELDS:
                component = new DeployMinefieldDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_DEPLOY_MINEFIELD_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case DEPLOYMENT:
                component = new DeploymentDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_DEPLOYMENT_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case TARGETING:
                component = new TargetingPhaseDisplay(this, false);
                ((TargetingPhaseDisplay) component).initializeListeners();
                main = CG_BOARD_VIEW;
                secondary = CG_TARGETING_PHASE_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case PREMOVEMENT:
                component = new PrephaseDisplay(this, GamePhase.PREMOVEMENT);
                ((PrephaseDisplay) component).initializeListeners();
                main = CG_BOARD_VIEW;
                secondary = CG_PREMOVEMENT_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case MOVEMENT:
                component = new MovementDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_MOVEMENT_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case OFFBOARD:
                component = new TargetingPhaseDisplay(this, true);
                ((TargetingPhaseDisplay) component).initializeListeners();
                main = CG_BOARD_VIEW;
                secondary = CG_OFFBOARD_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case PRE_FIRING:
                component = new PrephaseDisplay(this, GamePhase.PRE_FIRING);
                ((PrephaseDisplay) component).initializeListeners();
                main = CG_BOARD_VIEW;
                secondary = CG_PRE_FIRING;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case FIRING:
                component = new FiringDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_FIRING_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case POINTBLANK_SHOT:
                component = new PointblankShotDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_POINTBLANK_SHOT_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case PHYSICAL:
                component = new PhysicalDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_PHYSICAL_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case PREEND_DECLARATIONS:
                component = new PreEndDeclarationsDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_PREEND_DECLARATIONS_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case INFANTRY_VS_INFANTRY_COMBAT:
                component = new InfantryVsInfantryCombatDisplay(this);
                main = CG_BOARD_VIEW;
                secondary = CG_INFANTRY_COMBAT_DISPLAY;
                component.setName(secondary);
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = (StatusBarPhaseDisplay) component;
                panSecondary.add(component, secondary);
                break;
            case INITIATIVE_REPORT:
            case TARGETING_REPORT:
            case MOVEMENT_REPORT:
            case OFFBOARD_REPORT:
            case FIRING_REPORT:
            case PHYSICAL_REPORT:
            case END_REPORT:
            case VICTORY:
                main = CG_BOARD_VIEW;
                secondary = CG_REPORT_DISPLAY;
                if (reportDisplay == null) {
                    reportDisplay = new ReportDisplay(this);
                    reportDisplay.setName(secondary);
                }
                if (!mainNames.containsValue(main)) {
                    panMain.add(panTop, main);
                }
                currPhaseDisplay = reportDisplay;
                component = reportDisplay;
                if (!secondaryNames.containsValue(secondary)) {
                    panSecondary.add(reportDisplay, secondary);
                }
                break;
            default:
                component = new WaitingForServerPanel();
                main = CG_DEFAULT;
                secondary = main;
                component.setName(main);
                panMain.add(main, component);
                break;
        }
        phaseComponents.put(name, component);
        mainNames.put(name, main);
        if (secondary != null) {
            secondaryNames.put(name, secondary);
        }

        return component;
    }

    protected void showBoardPopup(BoardViewEvent event) {
        if (fillPopup(event)) {
            BoardView legacy2D = getBoardView(event.getBoardId());
            if (legacy2D != null) { legacy2D.showPopup(popup, event.getCoords()); }
        }
    }

    /**
     * Switches the Minimap and the UnitDisplay an and off together. If the UnitDisplay is active, both will be hidden,
     * else both will be shown.
     */
    public void toggleMMUDDisplays() {
        GUIP.toggleUnitDisplay();
        GUIP.setMinimapEnabled(GUIP.getUnitDisplayEnabled());
    }

    /**
     * Requests focus for the board view if the board was previously focused and the specified visibility condition is
     * met.
     *
     * @param visible a boolean indicating whether the board view should be visible. If true and the board was
     *                previously focused, focus will be requested.
     */
    private void conditionalRequestFocus(boolean visible) {
        if (wasBoardFocused && visible && !GpuBoardWindow.isActiveFor(this)) {
            requestFocus();
        }
    }

    private void requestFocus() {
        frame.requestFocusInWindow();
        boardViewsContainer.getPanel().requestFocusInWindow();
    }

    /**
     * Shows the command bar during the phases that are played on the board and hides it everywhere else. Unlike the bot
     * commands panel it has no on/off setting: the Commands button it carries is the only way to reach the game
     * commands, so it is present whenever there is a game to issue them in.
     */
    private void maybeShowCommandBar() {
        if (commandBarPanel == null) {
            return;
        }
        GamePhase phase = getClient().getGame().getPhase();
        boolean phaseAllowsCommandBar = phase.isOnMap() || phase.isReport();
        commandBarPanel.setVisible(phaseAllowsCommandBar);
        panTop.revalidate();
        panTop.repaint();
        logger.debug("[GameCommands] command bar visible={} in phase {}", phaseAllowsCommandBar, phase);
    }

    private void maybeShowBotCommands() {
        GamePhase phase = getClient().getGame().getPhase();
        boolean phaseAllowsPanel;
        if (phase.isReport()) {
            phaseAllowsPanel = GUIP.getBotCommandsAutoDisplayReportPhase() != GUIPreferences.HIDE;
        } else if (phase.isOnMap()) {
            phaseAllowsPanel = GUIP.getBotCommandsAutoDisplayNonReportPhase() != GUIPreferences.HIDE;
        } else {
            // Lounge and other non-board phases never show the panel, but the player's on/off choice is kept.
            phaseAllowsPanel = false;
        }
        // BOT_COMMANDS_ENABLED is the persistent user choice (Off/Float/Dock); honor it here, never overwrite it,
        // so the chosen mode is remembered across phases and across sessions.
        setBotCommandsLocation(GUIP.getBotCommandsEnabled() && phaseAllowsPanel && gameHasBots());
    }

    /**
     * @return {@code true} if any player in the game is a bot. Without one there is nobody the bot commands panel could
     *       give orders to, so it stays hidden - leaving the command bar with only the Commands button - until a bot
     *       joins (e.g. when a player is replaced by one).
     */
    private boolean gameHasBots() {
        for (Player player : getClient().getGame().getPlayersList()) {
            if (player.isBot()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows or hides the minimap based on the current menu setting. The native battle window's minimap keeps the
     * player's choice through every phase, so its phase rules apply to the Legacy 2D board only.
     */
    private void maybeShowMinimap() {
        if (!boardViewsContainer.isLegacy2DEnabled() || GpuBoardWindow.isActiveFor(this)) {
            return;
        }
        GamePhase phase = getClient().getGame().getPhase();

        if (phase.isReport()) {
            int action = GUIP.getMinimapAutoDisplayReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setMinimapEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setMinimapEnabled(false);
            }
        } else if (phase.isOnMap()) {
            int action = GUIP.getMinimapAutoDisplayNonReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setMinimapEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setMinimapEnabled(false);
            }
        } else {
            GUIP.setMinimapEnabled(false);
        }
    }

    private void maybeShowMiniReport() {
        if (GpuBoardWindow.isActiveFor(this)) {
            setMiniReportVisible(false);
            return;
        }
        GamePhase phase = getClient().getGame().getPhase();

        if (phase.isReport()) {
            int action = GUIP.getMiniReportAutoDisplayReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setMiniReportEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setMiniReportEnabled(false);
            }
        } else if (phase.isOnMap()) {
            int action = GUIP.getMiniReportAutoDisplayNonReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setMiniReportEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setMiniReportEnabled(false);
            }
        } else {
            GUIP.setMiniReportEnabled(false);
        }
    }

    private void maybeShowPlayerList() {
        GamePhase phase = getClient().getGame().getPhase();

        if (phase.isReport()) {
            int action = GUIP.getPlayerListAutoDisplayReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setPlayerListEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setPlayerListEnabled(false);
            }
        } else if (phase.isOnMap()) {
            int action = GUIP.getPlayerListAutoDisplayNonReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setPlayerListEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setPlayerListEnabled(false);
            }
        } else {
            GUIP.setPlayerListEnabled(false);
        }
    }

    /**
     * Shows or hides the Minimap based on the given visible. This works independently of the current menu setting, so
     * it should be used only when the Minimap is to be shown or hidden without regard for the user setting, e.g. hiding
     * it in the lobby or a report phase. Does not change the menu setting.
     */
    void setMapVisible(boolean visible) {
        // Over the native battle window its HUD shows the minimap; the minimap windows stay hidden.
        if (visible && GpuBoardWindow.isActiveFor(this)) { setMapVisible(false); return; }
        miniMaps.values().forEach(miniMap -> miniMap.setVisible(visible));
        if (getMiniMapDialog() != null) {
            getMiniMapDialog().setVisible(visible);
            conditionalRequestFocus(visible);
        }
    }

    void setMiniReportVisible(boolean visible) {
        if (getMiniReportDisplayDialog() != null) {
            setMiniReportLocation(visible);
            conditionalRequestFocus(visible && !GpuBoardWindow.isActiveFor(this));
        }
    }

    void setPlayerListVisible(boolean visible) {
        // The native battle window's HUD lists the players (Menu > Players); the Swing list stays hidden there.
        if (visible && GpuBoardWindow.isActiveFor(this)) { setPlayerListVisible(false); return; }
        conditionalRequestFocus(visible);
        if (visible) {
            showPlayerList();
        } else if (getPlayerListDialog() != null) {
            getPlayerListDialog().setVisible(false);
        }
    }

    void setRoundsInAirVisible(boolean visible) {
        // The native battle window's log lists the rounds in the air; the Swing window stays hidden there.
        if (visible && GpuBoardWindow.isActiveFor(this)) { setRoundsInAirVisible(false); return; }
        if (getRoundsInAirDialog() != null) {
            if (visible) {
                // Push the current "reveal all artillery" preference to the server on open. The preference change
                // listener only fires the push when the toggle CHANGES, so a value already set to true at game start
                // would otherwise never reach the server and enemy rounds would stay hidden. The server's reply
                // refreshes the list.
                getClient().sendArtilleryRevealPreference(
                      GUIP.getBoolean(GUIPreferences.REVEAL_ALL_ARTILLERY_ROUNDS));
                getRoundsInAirDialog().refresh();
            }
            getRoundsInAirDialog().setVisible(visible);
            conditionalRequestFocus(visible);
        }
    }

    /** Shows or hides the Unit Display based on the current menu setting. */
    public void maybeShowUnitDisplay() {
        GamePhase phase = getClient().getGame().getPhase();

        if (phase.isReport()) {
            int action = GUIP.getUnitDisplayAutoDisplayReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setUnitDisplayEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setUnitDisplayEnabled(false);
            }
        } else if (phase.isOnMap()) {
            int action = GUIP.getUnitDisplayAutoDisplayNonReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setUnitDisplayEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setUnitDisplayEnabled(false);
            }
        } else {
            GUIP.setUnitDisplayEnabled(false);
        }
    }

    /** Shows or hides the Unit Display based on the current menu setting. */
    public void maybeShowForceDisplay() {
        GamePhase phase = getClient().getGame().getPhase();

        if (phase.isReport()) {
            int action = GUIP.getForceDisplayAutoDisplayReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setForceDisplayEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setForceDisplayEnabled(false);
            }
        } else if (phase.isOnMap()) {
            int action = GUIP.getForceDisplayAutoDisplayNonReportPhase();
            if (action == GUIPreferences.SHOW) {
                GUIP.setForceDisplayEnabled(true);
            } else if (action == GUIPreferences.HIDE) {
                GUIP.setForceDisplayEnabled(false);
            }
        } else {
            GUIP.setForceDisplayEnabled(false);
        }
    }

    /**
     * Shows or hides the Unit Display based on the given visible. This works independently of the current menu setting,
     * so it should be used only when the Unit Display is to be shown or hidden without regard for the user setting,
     * e.g. hiding it in the lobby or a report phase. Does not change the menu setting.
     */
    public void setUnitDisplayVisible(boolean visible) {
        // If no unit displayed, select a unit so display can be safely shown
        // This can happen when using mouse button 4
        if (visible && (getUnitDisplayState().getCurrentEntity() == null) && (getClient() != null) && (getClient().getGame()
              != null)) {
            List<Entity> es = getClient().getGame().getEntitiesVector();
            if (!es.isEmpty()) {
                getUnitDisplayState().displayEntity(es.getFirst());
            }
        }

        setUnitDisplayLocation(visible);
    }

    /**
     * Places the bot commands panel in its configured location and sets its visibility. Mirrors
     * {@link #setUnitDisplayLocation(boolean)}: the single panel instance is reparented between the floating dialog and
     * the command bar at the top of the board area, so no command functionality is lost when switching. Floating mode
     * keeps the panel's original size and two-row layout; docked mode uses a single-row strip that shares the command
     * bar with the Commands button.
     *
     * @param visible whether the bot commands panel should be shown
     */
    void setBotCommandsLocation(boolean visible) {
        // The native battle window's Players panel holds the bot commands; the Swing panel stays hidden there.
        if (visible && GpuBoardWindow.isActiveFor(this)) { setBotCommandsLocation(false); return; }
        if ((botCommandsPanel == null) || (getBotCommandsDialog() == null) || (commandBarPanel == null)) {
            return;
        }
        boolean docked = !GpuBoardWindow.isActiveFor(this)
              && GUIP.getBotCommandsLocation() == BOT_COMMANDS_LOCATION_DOCKED;
        botCommandsPanel.setDockedLayout(docked);
        if (docked) {
            getBotCommandsDialog().setVisible(false);
            commandBarPanel.dockBotCommandsPanel(botCommandsPanel);
            botCommandsPanel.setVisible(visible);
            logger.debug("[BotPanel] docked in the command bar, visible={}", visible);
        } else {
            commandBarPanel.undockBotCommandsPanel();
            getBotCommandsDialog().add(botCommandsPanel);
            botCommandsPanel.setVisible(true);
            getBotCommandsDialog().setVisible(visible);
            logger.debug("[BotPanel] floating dialog, visible={}", visible);
        }
        panTop.revalidate();
        panTop.repaint();
        conditionalRequestFocus(visible);
    }

    private void saveSplitPaneLocations() {
        if ((panA1.isVisible()) && (panA2.isVisible())) {
            GUIP.setSplitPaneALocation(splitPaneA.getDividerLocation());
        }
    }

    private void setDividerLocations() {
        splitPaneA.setDividerLocation(GUIP.getSplitPaneADividerLocation());
    }

    public void setForceDisplayVisible(boolean visible) {
        // The native battle window's force overview stands for it; its window (and its Unit Display) stays hidden.
        if (visible && GpuBoardWindow.isActiveFor(this)) { setForceDisplayVisible(false); return; }
        if (getForceDisplayDialog() != null) {
            getForceDisplayDialog().setVisible(visible);
            conditionalRequestFocus(visible);
        }
    }

    private void hideEmptyPanel(JPanel p, JSplitPane sp, Double d) {
        boolean b = false;

        for (Component comp : p.getComponents()) {
            if (comp.isVisible()) {
                b = true;
                break;
            }
        }

        if (!b) {
            p.setVisible(false);
            sp.setDividerLocation(d);
        } else {
            p.setVisible(true);
        }
    }

    private void revalidatePanels() {
        if (unitDisplayPanel != null) {
            unitDisplayPanel.setMinimumSize(new Dimension(0, (int) (panTop.getHeight() * 0.7)));
            unitDisplayPanel.setPreferredSize(new Dimension(0, (int) (panTop.getHeight() * 0.7)));
        }
        getMiniReportDisplay().setMinimumSize(new Dimension(0, (int) (panTop.getHeight() * 0.3)));
        getMiniReportDisplay().setPreferredSize(new Dimension(0, (int) (panTop.getHeight() * 0.3)));

        if (unitDisplayDialog != null) {
            unitDisplayDialog.revalidate();
            unitDisplayDialog.repaint();
        }
        panA1.revalidate();
        panA1.repaint();
        panA2.revalidate();
        panA2.repaint();
    }

    private void setDockAxis() {
        if (GUIP.getDockMultipleOnYAxis()) {
            panA1.setLayout(new BoxLayout(panA1, BoxLayout.Y_AXIS));
            panA2.setLayout(new BoxLayout(panA2, BoxLayout.Y_AXIS));
        } else {
            panA1.setLayout(new BoxLayout(panA1, BoxLayout.X_AXIS));
            panA2.setLayout(new BoxLayout(panA2, BoxLayout.X_AXIS));
        }
    }

    public void setUnitDisplayLocation(boolean visible) {
        if (GpuBoardWindow.isActiveFor(this) || !boardViewsContainer.isLegacy2DEnabled()) {
            if (getUnitDisplayDialog() != null) { getUnitDisplayDialog().setVisible(false); }
            return;
        }
        if (unitDisplayPanel == null && !visible) { return; }
        initializeLegacy2DUnitDisplay();
        saveSplitPaneLocations();
        setDockAxis();

        if (GUIP.getDockOnLeft()) {
            switch (GUIP.getUnitDisplayLocation()) {
                case 0:
                    panA2.add(boardViewsContainer.getPanel());
                    panA2.setVisible(true);
                    getUnitDisplayDialog().add(getUnitDisplay(), BorderLayout.CENTER);
                    getUnitDisplayDialog().setVisible(visible);
                    getUnitDisplay().setVisible(visible);
                    getUnitDisplay().setTitleVisible(false);
                    hideEmptyPanel(panA1, splitPaneA, 0.0);
                    conditionalRequestFocus(visible);
                    break;
                case 1:
                    panA2.add(boardViewsContainer.getPanel());
                    panA2.setVisible(true);
                    panA1.add(getUnitDisplay());
                    getUnitDisplayDialog().setVisible(false);
                    getUnitDisplay().setVisible(visible);
                    getUnitDisplay().setTitleVisible(true);
                    hideEmptyPanel(panA1, splitPaneA, 0.0);
                    conditionalRequestFocus(visible);
                    break;
            }
        } else {
            switch (GUIP.getUnitDisplayLocation()) {
                case 0:
                    panA1.add(boardViewsContainer.getPanel());
                    panA1.setVisible(true);
                    getUnitDisplayDialog().add(getUnitDisplay(), BorderLayout.CENTER);
                    getUnitDisplayDialog().setVisible(visible);
                    getUnitDisplay().setVisible(visible);
                    getUnitDisplay().setTitleVisible(false);
                    hideEmptyPanel(panA2, splitPaneA, 1.0);
                    conditionalRequestFocus(visible);
                    break;
                case 1:
                    panA1.add(boardViewsContainer.getPanel());
                    panA1.setVisible(true);
                    panA2.add(getUnitDisplay());
                    getUnitDisplayDialog().setVisible(false);
                    getUnitDisplay().setVisible(visible);
                    getUnitDisplay().setTitleVisible(true);
                    hideEmptyPanel(panA2, splitPaneA, 1.0);
                    conditionalRequestFocus(visible);
                    break;
            }
        }

        setDividerLocations();
        revalidatePanels();
    }

    public void setMiniReportLocation(boolean visible) {
        // The native reader owns reports while the 3D board is presented. Preference and docking changes can
        // reach this method even while the Swing frame is hidden; never let them reopen its report dialog.
        if (GpuBoardWindow.isActiveFor(this)) {
            getMiniReportDisplayDialog().setVisible(false);
            getMiniReportDisplay().setVisible(false);
            return;
        }
        saveSplitPaneLocations();
        setDockAxis();

        if (GUIP.getDockOnLeft()) {
            switch (GUIP.getMiniReportLocation()) {
                case 0:
                    panA2.add(boardViewsContainer.getPanel());
                    panA2.setVisible(true);
                    getMiniReportDisplayDialog().add(getMiniReportDisplay(), BorderLayout.CENTER);
                    getMiniReportDisplayDialog().setVisible(visible);
                    getMiniReportDisplay().setVisible(visible);
                    hideEmptyPanel(panA1, splitPaneA, 0.0);
                    conditionalRequestFocus(visible);
                    break;
                case 1:
                    panA2.add(boardViewsContainer.getPanel());
                    panA2.setVisible(true);
                    panA1.add(getMiniReportDisplay());
                    getMiniReportDisplayDialog().setVisible(false);
                    getMiniReportDisplay().setVisible(visible);
                    hideEmptyPanel(panA1, splitPaneA, 0.0);
                    conditionalRequestFocus(visible);
                    break;
            }
        } else {
            switch (GUIP.getMiniReportLocation()) {
                case 0:
                    panA1.add(boardViewsContainer.getPanel());
                    panA1.setVisible(true);
                    getMiniReportDisplayDialog().add(getMiniReportDisplay(), BorderLayout.CENTER);
                    getMiniReportDisplayDialog().setVisible(visible);
                    getMiniReportDisplay().setVisible(visible);
                    hideEmptyPanel(panA2, splitPaneA, 1.0);
                    conditionalRequestFocus(visible);
                    break;
                case 1:
                    panA1.add(boardViewsContainer.getPanel());
                    panA1.setVisible(true);
                    panA2.add(getMiniReportDisplay());
                    getMiniReportDisplayDialog().setVisible(false);
                    getMiniReportDisplay().setVisible(visible);
                    hideEmptyPanel(panA2, splitPaneA, 1.0);
                    conditionalRequestFocus(visible);
                    break;
            }
        }

        revalidatePanels();
        setDividerLocations();
        conditionalRequestFocus(true);
    }

    private boolean fillPopup(BoardViewEvent event) {
        popup = new MapMenu(event.getCoords(), event.getBoardState().getBoardId(), curPanel, this);
        return popup.getHasMenu();
    }

    /**
     * Pops up a dialog box giving the player a series of choices that are not mutually exclusive.
     *
     * @param title    the <code>String</code> title of the dialog box.
     * @param question the <code>String</code> question that has a "Yes" or "No" answer. The question will be split
     *                 across multiple line on the '\n' characters.
     * @param choices  the array of <code>String</code> choices that the player can select from.
     *
     * @return The array of the <code>int</code> indexes from the input array that match the selected choices. If no
     *       choices were available, if the player did not select a choice, or if the player canceled the choice, a
     *       <code>null</code> value is returned.
     */
    public @Nullable int[] doChoiceDialog(String title, String question, String... choices) {
        ChoiceDialog choice = new ChoiceDialog(frame, title, question, choices);
        choice.setVisible(true);
        return choice.getChoices();
    }

    /**
     * EDT: asks a JOptionPane question in this client's native battle window and waits for the answer, as the modal
     * JOptionPane does. The message is a String, which Swing reads as HTML when it starts with "&lt;html&gt;", or an
     * Object[] of such Strings, at most one JCheckBox, which receives the answer's checkbox state, and at most one
     * ImageIcon, which the native dialog shows beside the text. Enter presses the JOptionPane's default button: the
     * first one without options, else the option equal to {@code initialValue}, and none when no option equals it. The
     * JOptionPane's message type (its icon) is not carried: the native dialog shows errors, warnings and questions
     * alike.
     *
     * @return what the JOptionPane returns: the chosen option's index when options are given, otherwise
     *       {@link JOptionPane#YES_OPTION}, {@code NO_OPTION}, {@code CANCEL_OPTION} or {@code OK_OPTION}, and
     *       {@link JOptionPane#CLOSED_OPTION} when Esc closes it; null when the caller must show its Swing dialog (no
     *       native window draws it, or the message holds a part it cannot show)
     */
    public @Nullable Integer askNative(@Nullable Object message, @Nullable String title, int optionType,
          @Nullable Object[] options, @Nullable Object initialValue, boolean monospace) {
        StringJoiner text = new StringJoiner("\n");
        JCheckBox checkBox = null;
        ImageIcon image = null;
        for (Object part : (message instanceof Object[] parts) ? parts : new Object[] { message }) {
            if (part instanceof String line) {
                text.add(promptText(line));
            } else if ((part instanceof JCheckBox box) && (checkBox == null)) {
                checkBox = box;
            } else if ((part instanceof ImageIcon icon) && (image == null)) {
                image = icon;
            } else {
                return null;
            }
        }
        List<String> buttons;
        if (options == null) {
            buttons = optionButtons(optionType);
        } else {
            buttons = new ArrayList<>();
            for (Object option : options) {
                if ((option instanceof Component) || (option instanceof Icon)) {
                    return null;
                }
                buttons.add(String.valueOf(option));
            }
        }
        int defaultButton = (options == null) ? 0 : Arrays.asList(options).indexOf(initialValue);
        // the image goes as its file's bytes, which only a window that draws the dialog needs
        String imageFile = ((image == null) || !GpuBoardWindow.drawsDialogsFor(this)) ? null
              : ImageUtil.base64TextEncodeImage(image.getImage());
        DialogAnswer answer = GpuBoardWindow.route(this, new DialogRequest(0, DialogKind.MESSAGE,
              Objects.requireNonNullElse(title, ""), text.toString(), monospace, buttons, defaultButton,
              JOptionPane.CLOSED_OPTION, List.of(), List.of(), (checkBox == null) ? "" : checkBox.getText(),
              (checkBox != null) && checkBox.isSelected(), null, null, null, List.of(), imageFile));
        if (answer == null) {
            return null;
        }
        if (checkBox != null) {
            checkBox.setSelected(answer.checked());
        }
        int button = answer.button();
        if ((button < 0) || (button >= buttons.size())) {
            return JOptionPane.CLOSED_OPTION;
        }
        return ((options == null) && (optionType == JOptionPane.OK_CANCEL_OPTION) && (button == 1))
              ? JOptionPane.CANCEL_OPTION : button;
    }

    /**
     * {@link #askNative} shaped like {@link ConfirmDialog}: MegaMek's own Yes and No texts, and {@code dontBother},
     * when given, below the question. ConfirmDialog sets no default button; Enter presses the focused button, as
     * FlatLaf does outside macOS. Without the box, Yes is focused, so Enter answers Yes. With it, the box is focused
     * and Enter answers nothing.
     *
     * @return {@link JOptionPane#YES_OPTION}, {@code NO_OPTION}, or {@code CLOSED_OPTION} when Esc closes it; null
     *       when the caller must show its Swing dialog
     */
    public @Nullable Integer askYesNo(String question, String title, @Nullable JCheckBox dontBother) {
        Object[] yesNo = { Messages.getString("Yes"), Messages.getString("No") };
        Object message = (dontBother == null) ? question : new Object[] { question, dontBother };
        return askNative(message, title, JOptionPane.YES_NO_OPTION, yesNo, (dontBother == null) ? yesNo[0] : null,
              false);
    }

    /**
     * EDT: asks for one row (CHOICE) or for any rows (MULTI) in this client's native battle window and waits for the
     * answer, as a modal dialog does. Texts may be Swing HTML; they are shown as plain text. Button 0 confirms, the
     * others and Esc ({@code cancelButton}, or -1 for a close box) cancel. {@code initiallySelected} holds the rows
     * selected when the dialog opens (at most one for CHOICE); {@code max}, when set, is the most rows a MULTI ticks.
     * An answer with more rows than allowed is cancelled: dropping some of them would change the player's choice.
     *
     * @return null when the caller must show its Swing dialog (no native window draws it); otherwise the answer. Its
     *       selection is empty unless button 0 was pressed, and holds only enabled rows in ascending order: at most one
     *       for CHOICE, at most {@code max} for MULTI
     */
    public @Nullable DialogAnswer askRows(@Nullable String message, String title, List<DialogRow> rows, boolean multi,
          List<Integer> initiallySelected, @Nullable Integer max, List<String> buttons, int cancelButton) {
        return askRows(message, title, rows, multi, initiallySelected, max, null, buttons, cancelButton);
    }

    /**
     * {@link #askRows(String, String, List, boolean, List, Integer, List, int)} with {@code checkBox}, when given,
     * below the rows: as in {@link #askNative}, the box receives the answer's state whichever button closed the dialog.
     */
    public @Nullable DialogAnswer askRows(@Nullable String message, String title, List<DialogRow> rows, boolean multi,
          List<Integer> initiallySelected, @Nullable Integer max, @Nullable JCheckBox checkBox, List<String> buttons,
          int cancelButton) {
        List<DialogRow> shown = rows.stream()
              .map(row -> new DialogRow(promptText(row.label()), promptText(row.detail()), row.icon(), row.enabled()))
              .toList();
        DialogRequest request = new DialogRequest(0, multi ? DialogKind.MULTI : DialogKind.CHOICE, title,
              promptText(message), false, buttons, 0, cancelButton, shown, initiallySelected,
              (checkBox == null) ? "" : promptText(checkBox.getText()), (checkBox != null) && checkBox.isSelected(),
              null, null, max, List.of());
        DialogAnswer answer = GpuBoardWindow.route(this, request);
        if (answer == null) {
            return null;
        }
        if (checkBox != null) {
            checkBox.setSelected(answer.checked());
        }
        List<Integer> selected = (answer.button() != 0) ? List.of() : answer.selected().stream()
              .filter(row -> (row >= 0) && (row < rows.size()) && rows.get(row).enabled())
              .distinct().sorted().toList();
        int limit = !multi ? 1 : ((max == null) ? rows.size() : max);
        if (selected.size() > limit) {
            logger.warn("Native {} answer selects {} rows, more than {}: cancelled", request.kind(), selected.size(),
                  limit);
            return DialogAnswer.cancelled(request);
        }
        return new DialogAnswer(answer.button(), selected, null, answer.checked(), List.of());
    }

    /**
     * EDT: {@link #askRows} for one entry of a dialog's combo box: its entries are the rows, and the dialog opens on its
     * selected entry. A confirmed entry becomes the combo box's selection.
     *
     * @return null when the caller must show its Swing dialog (no native window draws it); true when button 0
     *       confirmed an entry, false otherwise
     */
    public @Nullable Boolean askEntry(@Nullable String message, String title, JComboBox<?> entries,
          List<String> buttons, int cancelButton) {
        List<DialogRow> rows = new ArrayList<>();
        for (int index = 0; index < entries.getItemCount(); index++) {
            rows.add(new DialogRow(String.valueOf(entries.getItemAt(index)), "", null, true));
        }
        int selected = entries.getSelectedIndex();
        DialogAnswer answer = askRows(message, title, rows, false, (selected < 0) ? List.of() : List.of(selected),
              null, buttons, cancelButton);
        if (answer == null) {
            return null;
        }
        if (answer.selected().isEmpty()) {
            return false;
        }
        entries.setSelectedIndex(answer.selected().getFirst());
        return true;
    }

    /**
     * EDT: the game master's choice of the player to reinforce from a unit file, asked in the native battle window, or
     * in the modal Swing player list without it.
     *
     * @return the player chosen, empty when none was (which the caller reports), or null when the native choice was
     *       cancelled
     */
    @SuppressWarnings("OptionalAssignedToNull")
    private @Nullable Optional<Player> choosePlayerToReinforce() {
        List<Player> players = PlayerListDialog.sortedPlayerList(client.getGame());
        List<DialogRow> rows = players.stream()
              .map(player -> new DialogRow(player.getName(),
                    PlayerListDialog.details(client, player, true).trim(), null, true))
              .toList();
        DialogAnswer answer = askRows(null, Messages.getString("PlayerListDialog.title"), rows, false, List.of(),
              null, List.of(Messages.getString("Okay"), Messages.getString("Cancel")), 1);
        if (answer == null) {
            PlayerListDialog dialog = new PlayerListDialog(frame, client, true);
            dialog.setVisible(true);
            return Optional.ofNullable(dialog.getSelected());
        }
        if (answer.button() != 0) {
            return null;
        }
        return answer.selected().isEmpty() ? Optional.empty() : Optional.of(players.get(answer.selected().getFirst()));
    }

    /**
     * EDT: asks for a text in this client's native battle window and waits for the answer, as a modal dialog does.
     * With {@code min} or {@code max} set, the text must be an integer in that range. Button 0 confirms, the others
     * and Esc ({@code cancelButton}, or -1 for a close box) cancel.
     *
     * @return null when the caller must show its Swing dialog (no native window draws it); otherwise the answer, whose
     *       text is null unless button 0 was pressed with a text that meets the bounds
     */
    public @Nullable DialogAnswer askText(@Nullable String message, String title, String initialText,
          @Nullable Integer min, @Nullable Integer max, List<String> buttons, int cancelButton) {
        DialogAnswer answer = GpuBoardWindow.route(this, new DialogRequest(0, DialogKind.INPUT, title,
              promptText(message), false, buttons, 0, cancelButton, List.of(), List.of(), "", false, initialText, min,
              max, List.of()));
        if (answer == null) {
            return null;
        }
        String text = (answer.button() == 0) ? answer.text() : null;
        if ((text != null) && ((min != null) || (max != null)) && !isIntegerWithin(text, min, max)) {
            text = null;
        }
        return new DialogAnswer(answer.button(), List.of(), text, answer.checked(), List.of());
    }

    /**
     * EDT: asks for the values of a form in this client's native battle window and waits for the answer, as a modal
     * dialog does. Texts may be Swing HTML; they are shown as plain text. Button 0 confirms, the others and Esc
     * ({@code cancelButton}, or -1 for a close box) cancel. An answer with a value its field cannot hold is cancelled.
     * A change of a live field answers {@link DialogAnswer#CHANGED} with the values as the player left them, unchecked,
     * for the caller's next form.
     *
     * @return null when the caller must show its Swing dialog (no native window draws it); otherwise the answer. Its
     *       values are empty unless button 0 was pressed, and then hold one value per field: an INTEGER of the field's
     *       range, one of a CHOICE's choices, "true" or "false" for a CHECKBOX, or the text of a TEXT field
     */
    public @Nullable DialogAnswer askForm(@Nullable String message, String title, List<DialogField> fields,
          List<String> buttons, int cancelButton) {
        List<DialogField> shown = fields.stream().map(field -> new DialogField(promptText(field.label()),
              field.kind(), field.choices(), field.min(), field.max(), field.initial(), field.live())).toList();
        DialogRequest request = new DialogRequest(0, DialogKind.FORM, title, promptText(message), false, buttons, 0,
              cancelButton, List.of(), List.of(), "", false, null, null, null, shown);
        DialogAnswer answer = GpuBoardWindow.route(this, request);
        if (answer == null) {
            return null;
        }
        if (answer.button() == DialogAnswer.CHANGED) {
            return new DialogAnswer(DialogAnswer.CHANGED, List.of(), null, answer.checked(), answer.values());
        }
        if (answer.button() != 0) {
            return new DialogAnswer(answer.button(), List.of(), null, answer.checked(), List.of());
        }
        List<String> values = answer.values();
        boolean fits = values.size() == fields.size();
        for (int index = 0; fits && (index < values.size()); index++) {
            fits = holds(fields.get(index), values.get(index));
        }
        if (!fits) {
            logger.warn("Native form answer {} does not fit its {} fields: cancelled", values, fields.size());
            return DialogAnswer.cancelled(request);
        }
        return new DialogAnswer(0, List.of(), null, answer.checked(), values);
    }

    /** Whether a form field can hold the value the native form answered for it. */
    private static boolean holds(DialogField field, @Nullable String value) {
        return (value != null) && switch (field.kind()) {
            case INTEGER -> isIntegerWithin(value, field.min(), field.max());
            case CHOICE -> field.choices().contains(value);
            case CHECKBOX -> "true".equals(value) || "false".equals(value);
            case TEXT -> true;
        };
    }

    private static boolean isIntegerWithin(String text, @Nullable Integer min, @Nullable Integer max) {
        try {
            int value = Integer.parseInt(text);
            return ((min == null) || (value >= min)) && ((max == null) || (value <= max));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * {@link JOptionPane#showInputDialog(Component, Object, String, int, Icon, Object[], Object)} over this client's
     * frame, asked in its native battle window instead when that draws dialogs: the values' texts as a CHOICE, or a
     * text field (INPUT) when there are no values.
     *
     * @return the chosen value or the entered text; null when cancelled, as JOptionPane returns
     */
    public @Nullable Object input(String message, String title, int messageType, @Nullable Object[] values,
          @Nullable Object initialValue) {
        List<String> okCancel = optionButtons(JOptionPane.OK_CANCEL_OPTION);
        if (values == null) {
            DialogAnswer answer = askText(message, title, (initialValue == null) ? "" : initialValue.toString(), null,
                  null, okCancel, 1);
            if (answer != null) {
                return answer.text();
            }
        } else {
            List<DialogRow> rows = Arrays.stream(values)
                  .map(value -> new DialogRow(String.valueOf(value), "", null, true)).toList();
            DialogAnswer answer = askRows(message, title, rows, false, firstSelectedRow(values, initialValue), null,
                  okCancel, 1);
            if (answer != null) {
                return answer.selected().isEmpty() ? null : values[answer.selected().getFirst()];
            }
        }
        return JOptionPane.showInputDialog(frame, message, title, messageType, null, values, initialValue);
    }

    /**
     * The row a JOptionPane input list starts with: the initial value, or else the first value in the combo box it uses
     * for fewer than 20 values; its list for 20 or more values starts without a selection.
     */
    private static List<Integer> firstSelectedRow(Object[] values, @Nullable Object initialValue) {
        int row = (initialValue == null) ? -1 : Arrays.asList(values).indexOf(initialValue);
        if ((row < 0) && (values.length > 0) && (values.length < 20)) {
            row = 0;
        }
        return (row < 0) ? List.of() : List.of(row);
    }

    /** A prompt text as the native dialog shows it: Swing's HTML (text starting with "&lt;html&gt;") as plain text. */
    private static String promptText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return BasicHTML.isHTMLString(text) ? GpuBoardWindow.plainText(text) : text;
    }

    /**
     * The client whose frame {@code window} is, or null for any other window. Dialogs that know only their parent frame
     * use it to ask in that client's native battle window.
     */
    public static @Nullable ClientGUI forFrame(@Nullable Window window) {
        return ((window instanceof JFrame owner)
              && (owner.getRootPane().getClientProperty(ClientGUI.class) instanceof ClientGUI gui)) ? gui : null;
    }

    /** JOptionPane's buttons, with its texts, for an option type without custom options. */
    private static List<String> optionButtons(int optionType) {
        String ok = UIManager.getString("OptionPane.okButtonText");
        String cancel = UIManager.getString("OptionPane.cancelButtonText");
        String yes = UIManager.getString("OptionPane.yesButtonText");
        String no = UIManager.getString("OptionPane.noButtonText");
        return switch (optionType) {
            case JOptionPane.YES_NO_OPTION -> List.of(yes, no);
            case JOptionPane.YES_NO_CANCEL_OPTION -> List.of(yes, no, cancel);
            case JOptionPane.OK_CANCEL_OPTION -> List.of(ok, cancel);
            default -> List.of(ok);
        };
    }

    /**
     * {@link JOptionPane#showConfirmDialog(Component, Object, String, int, int)} over this client's frame, asked in its
     * native battle window instead when that draws dialogs (see {@link #askNative}).
     */
    public int confirm(Object message, String title, int optionType, int messageType) {
        Integer answer = askNative(message, title, optionType, null, null, false);
        return (answer != null) ? answer
              : JOptionPane.showConfirmDialog(frame, message, title, optionType, messageType);
    }

    /**
     * {@link JOptionPane#showOptionDialog} over this client's frame, asked in its native battle window instead when
     * that draws dialogs (see {@link #askNative}).
     */
    public int option(Object message, String title, int optionType, int messageType, @Nullable Icon icon,
          Object[] options, Object initialValue) {
        Integer answer = askNative(message, title, optionType, options, initialValue, false);
        return (answer != null) ? answer
              : JOptionPane.showOptionDialog(frame, message, title, optionType, messageType, icon, options,
              initialValue);
    }

    /**
     * {@link JOptionPane#showMessageDialog(Component, Object, String, int)} over this client's frame, shown in its
     * native battle window instead when that draws dialogs (see {@link #askNative}).
     */
    public void message(Object message, String title, int messageType) {
        if (askNative(message, title, JOptionPane.DEFAULT_OPTION, null, null, false) == null) {
            JOptionPane.showMessageDialog(frame, message, title, messageType);
        }
    }

    /**
     * Pops up a dialog box showing an alert
     */
    public void doAlertDialog(String title, String message) {
        doAlertDialog(title, message, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Brings up a dialog that displays a message using a default icon determined by the <code>messageType</code>
     * parameter.
     *
     * @param message     the <code>Object</code> to display
     * @param title       the title string for the dialog
     * @param messageType the type of message to be displayed:
     *                    <code>ERROR_MESSAGE</code>,
     *                    <code>INFORMATION_MESSAGE</code>,
     *                    <code>WARNING_MESSAGE</code>,
     *                    <code>QUESTION_MESSAGE</code>,
     *                    or <code>PLAIN_MESSAGE</code>
     *
     * @throws HeadlessException if
     *                           <code>GraphicsEnvironment.isHeadless</code> returns
     *                           <code>true</code>
     * @see java.awt.GraphicsEnvironment#isHeadless
     */
    public void doAlertDialog(String title, String message, int messageType) {
        if (askNative("<html><pre>" + message + "</pre></html>", title, JOptionPane.DEFAULT_OPTION, null, null, true)
              != null) {
            return;
        }
        JTextPane textArea = new JTextPane();
        Report.setupStylesheet(textArea);
        BASE64ToolKit toolKit = new BASE64ToolKit();
        textArea.setEditorKit(toolKit);
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea,
              ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        textArea.setText("<pre>" + message + "</pre>");
        scrollPane.setPreferredSize(new Dimension((int) (alertArea().getWidth() / 1.5),
              (int) (alertArea().getHeight() / 1.5)));
        JOptionPane.showMessageDialog(frame, scrollPane, title, messageType);
    }

    /**
     * The area an alert sizes itself by: the client's panel while it shows; the native window while that shows in place
     * of the hidden frame, as in the libGDX lobby, where the panel has no size; the screen otherwise.
     */
    private Dimension alertArea() {
        if (clientGuiPanel.isShowing()) {
            return clientGuiPanel.getSize();
        }
        Rectangle window = GpuHost.swingClientBounds();
        if (window != null) {
            return window.getSize();
        }
        return Toolkit.getDefaultToolkit().getScreenSize();
    }


    /**
     * Pops up a dialog box asking a yes/no question
     *
     * @param title    the <code>String</code> title of the dialog box.
     * @param question the <code>String</code> question that has a "Yes" or "No" answer. The question will be split
     *                 across multiple line on the '\n' characters.
     *
     * @return <code>true</code> if yes
     */
    public boolean doYesNoDialog(String title, String question) {
        Integer answer = askYesNo(question, title, null);
        if (answer != null) {
            return answer == JOptionPane.YES_OPTION;
        }
        ConfirmDialog confirm = new ConfirmDialog(frame, title, question);
        confirm.setVisible(true);
        return confirm.getAnswer();
    }

    /**
     * Pops up a dialog box asking a yes/no question
     * <p>
     * The player will be given a chance to not show the dialog again.
     *
     * @param title    the <code>String</code> title of the dialog box.
     * @param question the <code>String</code> question that has a "Yes" or "No" answer. The question will be split
     *                 across multiple lines on the '\n' characters.
     *
     * @return the <code>ConfirmDialog</code> containing the player's responses. The dialog will already have been shown
     *       to the player, and is only being returned so the calling function can see the answer to the question and
     *       the state of the "Show again?" question.
     */
    public ConfirmDialog doYesNoBotherDialog(String title, String question) {
        ConfirmDialog confirm = new ConfirmDialog(frame, title, question, true);
        confirm.setVisible(true);
        confirm.dispose();
        return confirm;
    }

    /**
     * Pops up a dialog box asking a yes/no question
     * <p>
     * The player will be given a chance to not show the dialog again.
     *
     * @param title    the <code>String</code> title of the dialog box.
     * @param question the <code>String</code> question that has a "Yes" or "No" answer. The question will be split
     *                 across multiple line on the '\n' characters.c tr23ty
     *
     * @return the <code>ConfirmDialog</code> containing the player's responses. The dialog will already have been shown
     *       to the player, and is only being returned so the calling function can see the answer to the question and
     *       the state of the "Show again?" question.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    public ConfirmDialog doWarnNoBotherDialog(String title, String question) {
        ConfirmDialog confirm = new ConfirmDialog(frame, title, question, true);
        confirm.setVisible(true);
        confirm.dispose();
        return confirm;
    }

    /**
     * Allow the player to select a MegaMek Unit List file to load. The {@link Entity}s in the file will replace any
     * that the player has already selected. As such, this method should only be called in the chat lounge. The file can
     * record damage sustained, non-standard munitions selected, and ammunition expended in a prior engagement.
     */
    protected void loadListFile() {
        loadListFile(client.getLocalPlayer());
    }

    /**
     * Allow the player to select a MegaMek Unit List file to load. The {@link Entity}s in the file will replace any
     * that the player has already selected. As such, this method should only be called in the chat lounge. The file can
     * record damage sustained, non-standard munitions selected, and ammunition expended in a prior engagement.
     *
     * @param player The player to add the units to
     */
    public void loadListFile(Player player) {
        loadListFile(player, false);
    }

    /**
     * Loads a unit file onto a player as reinforcements, arriving next round.
     *
     * <p>Public so that a gamemaster tool which already knows which player it is setting up can send units to them
     * without asking again in a second dialog.</p>
     *
     * @param player The player the units are for
     */
    public void reinforceFromFile(Player player) {
        // the team check below is skipped because the caller has just set one. A team change is queued by the
        // server and applied at the end of the round, so the player is still on no team at this instant even
        // though they will have one before anything of theirs deploys; checking now would refuse the very case
        // the gamemaster tools exist to handle.
        loadListFile(player, true, false);
    }

    /**
     * Allow the player to select a MegaMek Unit List file to load. The
     * <code>Entity</code>s in the file will replace any that the player has
     * already selected. As such, this method should only be called in the chat lounge. The file can record damage
     * sustained, non-standard munitions selected, and ammunition expended in a prior engagement.
     *
     * @param player The player to add the units to
     */
    protected void loadListFile(Player player, boolean reinforce) {
        loadListFile(player, reinforce, true);
    }

    /**
     * Loads a unit file onto a player.
     *
     * @param player        The player to add the units to
     * @param reinforce     Whether the units arrive as reinforcements during a game
     * @param requireATeam  Whether to refuse a player who is on no team. A gamemaster tool that has just assigned
     *                      one passes {@code false}, because the change does not reach the board until the end of
     *                      the round and the player has no team yet at the moment this is called
     */
    protected void loadListFile(Player player, boolean reinforce, boolean requireATeam) {
        if (player != null) {
            boolean addedUnits = false;

            if (requireATeam && reinforce && (player.getTeam() == Player.TEAM_UNASSIGNED)) {
                addToast(ToastLevel.ERROR,
                      Messages.getString("ClientGUI.openUnitListFileDialog.noReinforceMessage"));
                return;
            }
            // Build the "load unit" dialog, if necessary.
            if (dlgLoadList == null) {
                dlgLoadList = new JFileChooser(".");
                dlgLoadList.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
                dlgLoadList.setDialogTitle(Messages.getString("ClientGUI.openUnitListFileDialog.title"));
                dlgLoadList.setFileFilter(new FileNameExtensionFilter("MUL files", "mul", "mmu"));
            }
            // Default to the player's name.
            dlgLoadList.setSelectedFile(new File(player.getName() + CG_FILE_EXTENSION_MUL));

            int returnVal = dlgLoadList.showOpenDialog(frame);
            if ((returnVal != JFileChooser.APPROVE_OPTION) || (dlgLoadList.getSelectedFile() == null)) {
                return;
            }

            // Did the player select a file?
            File unitFile = dlgLoadList.getSelectedFile();

            try {
                addedUnits = loadUnitFile(player, unitFile, reinforce) > 0;
            } catch (Exception ex) {
                logger.error(ex, "loadListFile");
                doAlertDialog(Messages.getString("ClientGUI.errorLoadingFile"), ex.getMessage());
            }

            // If we've added reinforcements, then we need to set the round deployment up
            // again.
            if (addedUnits && reinforce) {
                client.getGame().setupDeployment();
                client.sendResetRoundDeployment();
            }
        } else {
            doAlertDialog(Messages.getString("ClientGUI.errorLoadingFile"),
                  Messages.getString("ClientGUI.errorSelectingPlayer"));
        }
    }

    /**
     * Loads the MegaMek Unit List {@code unitFile} onto {@code player}, as Load List does once a file is chosen, for a
     * caller that chose the file itself.
     *
     * @return how many units were added, or -1 when the file's version was refused
     *
     * @throws Exception when the file cannot be read
     */
    public int loadListFile(Player player, File unitFile) throws Exception {
        return loadUnitFile(player, unitFile, false);
    }

    private int loadUnitFile(Player player, File unitFile, boolean reinforce) throws Exception {
        // Read the units from the file.
        final MULParser parser = new MULParser(unitFile,
              (GameOptions) getClient().getGame().getOptions());
        if (!MULVersionValidator.isCorrectVersion(parser,
              (text, title) -> message(text, title, JOptionPane.ERROR_MESSAGE),
              (text, title) -> confirm(text, title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    == JOptionPane.YES_OPTION)) {
            return -1;
        }
        final Vector<Entity> loadedUnits = parser.getEntities();

        // in the Lounge, set default deployment to "Before Game Start", round 0
        // but in a game in-progress, deploy at the start of next round
        final int deployRound = client.getGame().getRoundCount() + ((client.getGame().getPhase()
              == GamePhase.LOUNGE) ? 0 : 1);

        // Add the units from the file.
        for (Entity entity : loadedUnits) {
            entity.setOwner(player);
            if (reinforce) {
                entity.setDeployRound(deployRound);
                entity.setGame(client.getGame());
                // Set these to true, otherwise units reinforced in
                // the movement turn are considered selectable
                entity.setDone(true);
                entity.setUnloaded(true);
                if (entity instanceof IBomber && (client.getGame().getPhase() != GamePhase.LOUNGE)) {
                    // Only apply bombs when we're going straight into the game; doing this in the
                    // lounge
                    // breaks the bombs completely.
                    ((IBomber) entity).applyBombs();
                }
            }
        }

        if (!loadedUnits.isEmpty()) {
            client.sendAddEntity(loadedUnits);
            String msg = client.getLocalPlayer()
                  + " loaded MUL file for player: "
                  + player.getName()
                  + " ["
                  + loadedUnits.size()
                  + " units]";
            client.sendServerChat(Player.PLAYER_NONE, msg);
        }
        return loadedUnits.size();
    }

    @Override
    protected int askSaveFirst() {
        return confirm(Messages.getString("ClientGUI.gameSaveDialogMessage"),
              Messages.getString("ClientGUI.gameSaveFirst"), JOptionPane.YES_NO_CANCEL_OPTION,
              JOptionPane.WARNING_MESSAGE);
    }

    @Override
    public boolean saveGame() {
        String title = Messages.getString("ClientGUI.FileSaveDialog.title");
        Optional<File> chosen = GpuBoardWindow.chooseFile(this, new File(MMConstants.SAVEGAME_DIR).getAbsoluteFile(),
              title, new FileNameExtensionFilter(Messages.getString("Lobby.files.saveGames"),
                    MMConstants.SAVE_FILE_GZ_EXT.substring(1), MMConstants.SAVE_FILE_EXT.substring(1)), true);
        File selected;
        if (chosen != null) {
            selected = chosen.orElse(null);
        } else {
            ignoreHotKeys = true;
            JFileChooser fc = new JFileChooser(MMConstants.SAVEGAME_DIR);
            fc.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
            fc.setDialogTitle(title);
            int returnVal = fc.showSaveDialog(frame);
            ignoreHotKeys = false;
            selected = (returnVal == JFileChooser.APPROVE_OPTION) ? fc.getSelectedFile() : null;
        }
        if (selected == null) {
            // I want a file, know!
            return false;
        }
        String file = selected.getName();
        // stupid hack to allow for savegames in folders with spaces in
        // the name
        String path = selected.getAbsoluteFile().getParentFile().getPath();
        path = path.replace(" ", "|");
        client.sendChat(CG_CHAT_COMMAND_LOCAL_SAVE + " " + file + " " + path);
        client.setAwaitingSave(true);
        return true;
    }

    /** Developer Utility: Save game to quicksave.sav.gz without any prompts. */
    private void quickSaveGame() {
        client.sendChat(CG_CHAT_COMMAND_LOCAL_SAVE
              + " "
              + MMConstants.QUICKSAVE_FILE
              + " "
              + MMConstants.QUICKSAVE_PATH);
    }

    /**
     * Allow the player to save a list of entities to a MegaMek Unit List file. A "Save As" dialog will be displayed
     * that allows the user to select the file's name and directory. The player can later load this file to quickly
     * select the units for a new game. The file will record damage sustained, non-standard munitions selected, and
     * ammunition expended during the course of the current engagement.
     *
     * @param unitList - the <code>Vector</code> of <code>Entity</code>s to be saved to a file. If this value is
     *                 <code>null</code> or empty, the "Save As" dialog will not be displayed.
     * @param filename The filename to save to
     */
    public void saveListFile(ArrayList<Entity> unitList, String filename) {
        // Handle empty lists.
        if ((unitList == null) || unitList.isEmpty()) {
            return;
        }

        // Build the "save unit" dialog, if necessary.
        if (dlgSaveList == null) {
            dlgSaveList = new JFileChooser(".");
            dlgSaveList.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
            dlgSaveList.setDialogTitle(Messages.getString("ClientGUI.saveUnitListFileDialog.title"));
            FileNameExtensionFilter filter = new FileNameExtensionFilter(Messages.getString(
                  "ClientGUI.descriptionMULFiles"), CG_FILEPATH_MUL);
            dlgSaveList.setFileFilter(filter);
        }
        // Default to the player's name.
        dlgSaveList.setSelectedFile(new File(filename + CG_FILE_EXTENSION_MUL));

        int returnVal = dlgSaveList.showSaveDialog(frame);
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (dlgSaveList.getSelectedFile() == null)) {
            // I want a file, know!
            return;
        }

        // Did the player select a file?
        File unitFile = dlgSaveList.getSelectedFile();
        if (unitFile != null) {
            if (!(unitFile.getName().toLowerCase().endsWith(CG_FILE_EXTENSION_MUL) || unitFile.getName()
                  .toLowerCase()
                  .endsWith(CG_FILE_EXTENSION_XML))) {
                try {
                    unitFile = new File(unitFile.getCanonicalPath() + CG_FILE_EXTENSION_MUL);
                } catch (Exception ignored) {
                    // nothing needs to be done here
                    return;
                }
            }

            try {
                // Save the player's entities to the file.
                EntityListFile.saveTo(unitFile, unitList);
            } catch (Exception ex) {
                logger.error(ex, "saveListFile");
                doAlertDialog(Messages.getString("ClientGUI.errorSavingFile"), ex.getMessage());
            }
        }
    }

    private @Nullable ProcessBuilder printToMegaMekLab(ArrayList<Entity> unitList) {
        MegaMekLabPrint print = MegaMekLabPrint.of(CP.getMmlPath());
        MegaMekLabPrint.Problem problem = print.problem();
        if (problem != null) {
            logger.errorDialog(Messages.getString("MegaMekLabPrint.title"), problem.message(print.executable()));
            return null;
        }
        File unitFile;
        try {
            unitFile = File.createTempFile("MegaMekPrint", ".mul");
            EntityListFile.saveTo(unitFile, unitList);
            unitFile.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return print.command(unitFile);
    }

    /**
     * Request MegaMekLab to print out record sheets for the current player's selected units. The method will try to
     * find MML either automatically or based on a configured client setting.
     *
     * @param unitList The list of units to print
     * @param button   The button that started the printing, which shows the progress; if you need to trigger this method from somewhere
     *                 else, override it.
     */
    public void printList(ArrayList<Entity> unitList, JButton button) {
        // Do nothing if there are no units to print
        if ((unitList == null) || unitList.isEmpty()) {
            return;
        }

        var pb = printToMegaMekLab(unitList);
        if (pb == null) {
            return;
        }

        try {
            // It sometimes takes a while for MML to start, so we change the text of the button
            // to let the user know that something is happening
            button.setText(Messages.getString("ChatLounge.butPrintList.printing"));

            logger.info("Running command: {}", String.join(" ", pb.command()));

            var p = pb.start();

            // This thread's only purpose is to wait for the MML process to finish and change the button's text back to
            // its original value.
            new Thread(() -> {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    logger.error(e);
                } finally {
                    button.setText(Messages.getString("ChatLounge.butPrintList"));
                }
            }).start();

        } catch (Exception e) {
            // If something goes wrong, probably ProcessBuild.start if anything,
            // Make sure to set the button text back to what it started as no matter what.
            button.setText(Messages.getString("ChatLounge.butPrintList"));
            logger.errorDialog(e, "Operation failed", "Error printing unit list");

        }
    }

    protected void saveVictoryList() {
        String filename = client.getLocalPlayer().getName();

        // Build the "save unit" dialog, if necessary.
        if (dlgSaveList == null) {
            dlgSaveList = new JFileChooser(".");
            dlgSaveList.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
            dlgSaveList.setDialogTitle(Messages.getString("ClientGUI.saveUnitListFileDialog.title"));
            FileNameExtensionFilter filter = new FileNameExtensionFilter(Messages.getString(
                  "ClientGUI.descriptionMULFiles"), CG_FILEPATH_MUL);
            dlgSaveList.setFileFilter(filter);
        }
        // Default to the player's name.
        dlgSaveList.setSelectedFile(new File(filename + CG_FILE_EXTENSION_MUL));

        int returnVal = dlgSaveList.showSaveDialog(frame);
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (dlgSaveList.getSelectedFile() == null)) {
            return;
        }

        // Did the player select a file?
        File unitFile = dlgSaveList.getSelectedFile();
        if (unitFile != null) {
            if (!(unitFile.getName().toLowerCase().endsWith(CG_FILE_EXTENSION_MUL) || unitFile.getName()
                  .toLowerCase()
                  .endsWith(CG_FILE_EXTENSION_XML))) {
                try {
                    unitFile = new File(unitFile.getCanonicalPath() + CG_FILE_EXTENSION_MUL);
                } catch (Exception ignored) {
                    // nothing needs to be done here
                    return;
                }
            }

            try {
                // Save the player's entities to the file.
                EntityListFile.saveTo(unitFile, getClient());
            } catch (Exception ex) {
                logger.error(ex, "saveVictoryList");
                doAlertDialog(Messages.getString("ClientGUI.errorSavingFile"), ex.getMessage());
            }
        }
    }

    /**
     * Shows the Ruler/LOS dialog. This consolidates the old LOSDialog functionality into the RulerDialog which provides
     * distance, to-hit modifiers, and an elevation cross-section diagram.
     */
    private void showLOSSettingDialog() {
        if (GpuBoardWindow.showRuler(this)) { return; }
        RulerDialog ruler = getCurrentBoardState().map(state -> rulers.get(state.getBoardId())).orElse(null);
        if (ruler != null) {
            ruler.setVisible(true);
            ruler.toFront();
        }
    }

    /** Measures from {@code from} to {@code to} in the active board's native panel or Legacy 2D ruler dialog. */
    public void measureLineOfSight(int boardId, Coords from, Coords to) {
        if (GpuBoardWindow.updateRuler(this, boardId, model -> model.measure(from, to, Entity.NONE, null))) { return; }
        RulerDialog ruler = rulers.get(boardId);
        if (ruler != null) {
            ruler.measure(from, to);
        }
    }

    /** Sets the height of the active board's ruler point at {@code point}. */
    public void setRulerHeight(int boardId, Coords point, int height) {
        if (GpuBoardWindow.updateRuler(this, boardId, model -> model.height(point, height))) { return; }
        RulerDialog ruler = rulers.get(boardId);
        if (ruler != null) {
            ruler.setHeight(point, height);
        }
    }

    /**
     * Shows the Nova CEWS network view dialog (read-only).
     */
    private void showNovaNetworkViewDialog() {
        if (!inLounge() && GpuBoardWindow.showNovaNetworks(this)) {
            return;
        }
        NovaNetworkViewDialog dialog = new NovaNetworkViewDialog(frame, this);
        dialog.setVisible(true);
    }

    /**
     * Loads a preview image of the unit into the BufferedPanel.
     *
     * @param bp     The JLabel to set the image as icon to
     * @param entity The unit
     */
    public void loadPreviewImage(JLabel bp, Entity entity) {
        Camouflage camouflage = client.getLocalPlayer().getCamouflage();
        if (entity.hasOwner()) {
            camouflage = entity.getCamouflageOrElseOwners();
        }
        final Image base = MMStaticDirectoryManager.getMekTileset().imageFor(entity);
        final Image icon = new EntityImage(base, camouflage, frame, entity).loadPreviewImage(true);
        bp.setIcon((icon == null) ? null : new ImageIcon(icon));
    }

    /**
     * Make a "bing" sound.
     */
    public void bingChat() {
        audioService.playSound(SoundType.BING_CHAT);
    }

    /**
     * This prompts the user if they want to have the My Turn notifications enabled or not
     */
    private void promptForSound() {
        JCheckBox chkSoundNagSuppress = new JCheckBox(Messages.getString("ClientGUI.bingRemember"));
        JButton launchSettings = new JButton(Messages.getString("ClientGUI.bingLaunchSettings"));
        JButton playMyTurnSound = new JButton(Messages.getString("ClientGUI.bingPlay"));
        JLabel lblPlay = new JLabel(Messages.getString("ClientGUI.bingPlaySound"));
        JLabel lblMessage = new JLabel(Messages.getString("ClientGUI.bingMessage"));
        JLabel lblClientSettings = new JLabel(Messages.getString("ClientGUI.bingClientSettings"));

        int padding;
        // Action listeners
        launchSettings.addActionListener(e -> showSettingsSubPage(CommonSettingsPane.SETTINGS_AUDIO));
        playMyTurnSound.addActionListener(e -> audioService.playSoundNoMute(SoundType.BING_MY_TURN));

        // Set alignments
        lblPlay.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblClientSettings.setAlignmentX(Component.LEFT_ALIGNMENT);
        launchSettings.setAlignmentX(Component.RIGHT_ALIGNMENT);
        lblMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkSoundNagSuppress.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblClientSettings.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Audio button panel
        padding = UIUtil.scaleForGUI(5);
        JPanel playSoundPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, padding, 0));
        playSoundPanel.add(lblPlay);
        playSoundPanel.add(playMyTurnSound);

        // Client settings label panel
        JPanel clientSettingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        clientSettingsPanel.add(lblClientSettings);

        // Client Settings button panel
        JPanel settingsButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        settingsButtonPanel.add(launchSettings);

        // Panel alignment
        playSoundPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsButtonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        playSoundPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsButtonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        clientSettingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Audio sub-panel
        JPanel audioPanel = new JPanel();
        audioPanel.setLayout(new BoxLayout(audioPanel, BoxLayout.Y_AXIS));

        padding = UIUtil.scaleForGUI(10);
        audioPanel.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(),
                        BorderFactory.createEmptyBorder(padding, padding, padding, padding)
                )
        );

        audioPanel.add(playSoundPanel);
        padding = UIUtil.scaleForGUI(20);
        audioPanel.add(Box.createVerticalStrut(padding));
        audioPanel.add(clientSettingsPanel);
        padding = UIUtil.scaleForGUI(5);
        audioPanel.add(Box.createVerticalStrut(padding));
        audioPanel.add(settingsButtonPanel);

        // Main content panel
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(lblMessage);
        padding = UIUtil.scaleForGUI(10);
        panel.add(Box.createVerticalStrut(padding));
        panel.add(audioPanel);
        panel.add(Box.createVerticalStrut(padding));
        panel.add(chkSoundNagSuppress);

        // The native battle window asks the question with the box, without the panel's sound test and settings buttons
        Integer nativeAnswer = askYesNo(Messages.getString("ClientGUI.bingMessage"),
              Messages.getString("ClientGUI.bingTitle"), chkSoundNagSuppress);
        int response = (nativeAnswer != null) ? nativeAnswer
              : JOptionPane.showConfirmDialog(null, panel, Messages.getString("ClientGUI.bingTitle"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        boolean soundPrompt = (response == JOptionPane.YES_OPTION);
        boolean currentSetting = GUIPreferences.getInstance().getSoundMuteMyTurn();
        if (soundPrompt != currentSetting) {
            GUIPreferences.getInstance().setSoundMuteMyTurn(soundPrompt);
        }
        if (chkSoundNagSuppress.isSelected()) {
            GUIPreferences.getInstance()
                          .setSoundPromptSuppress(soundPrompt);
        }
    }

    public void bingMyTurn() {
        if (dead) {
            return;
        }
        if (!GUIP.getSoundPromptSuppress() && firstBing) {
            // Cleared first: the prompt waits in a nested event loop, where the next turn's bing must not ask again
            firstBing = false;
            promptForSound();
        }
        audioService.playSound(SoundType.BING_MY_TURN);
    }

    public void bingOthersTurn() {
        audioService.playSound(SoundType.BING_OTHERS_TURN);
    }

    /** Whether {@link #die()} has run: the game is closed and its prompts are answered as closed, unseen. */
    public boolean isDead() {
        return dead;
    }

    private void setWeaponOrderPrefs(boolean prefChange) {
        for (Entity entity : client.getGame().getEntitiesVector()) {
            if ((entity.getOwner().equals(client.getLocalPlayer()))
                  && (!entity.getWeaponSortOrder().isCustom())
                  && ((!entity.isDeployed()) || (prefChange))) {
                entity.setWeaponSortOrder(GUIP.getDefaultWeaponSortOrder());
                client.sendEntityWeaponOrderUpdate(entity);
            }
        }
    }

    /**
     * Applies the player's sensor preference to their own units, picking for each one the first sensor family on the
     * preference list that the unit actually carries a sensor for.
     *
     * <p>Example: the preference lists Active Probe, then Infrared, then Magscan. A Marauder MAD-3R carries Mek
     * Radar, Mek IR, Mek Magscan and Mek Seismic but no probe, so it deploys on Mek IR instead of the Mek Radar it
     * would otherwise have defaulted to. A Cicada CDA-3M carrying a Beagle Active Probe still deploys on the
     * probe.</p>
     *
     * <p>Units belonging to anyone else are left alone, bots included, so this never changes what Princess or CASPAR
     * do. A unit whose sensor the player picked by hand is left alone as well, in the lobby or in round zero.</p>
     *
     * <p>A unit that has already deployed is never touched. Changing the preference part-way through a game switches
     * the sensors of reinforcements still waiting to come on, and nothing that is already on the board.</p>
     */
    private void setSensorPrefs() {
        List<SensorFamily> preferenceOrder = GUIP.getSensorPreferenceOrder();
        Player localPlayer = client.getLocalPlayer();
        for (Entity entity : client.getGame().getEntitiesVector()) {
            if (!entity.getOwner().equals(localPlayer)
                  || entity.hasCustomSensorChoice()
                  || entity.isDeployed()) {
                continue;
            }
            int preferredSensorIndex = SensorFamily.preferredSensorIndex(entity, preferenceOrder);
            if (preferredSensorIndex >= 0) {
                entity.setNextSensor(entity.getSensors().elementAt(preferredSensorIndex));
                client.sendSensorChange(entity.getId(), preferredSensorIndex);
            }
        }
    }

    private final GameListener gameListener = new GameListenerAdapter() {

        @Override
        public void gamePollChange(GamePollEvent evt) {
            updateGameMasterVoteDialog(evt.getPoll());
        }

        @Override
        public void gameBoardChanged(GameBoardChangeEvent e) {
            // Keep the Rounds in the Air window current the moment in-flight artillery changes. The artillery packet
            // arrives after the phase-change event, so a phase-change-only refresh would lag a phase (and miss
            // off-board counter-battery rounds entirely until the next phase).
            if (roundsInAirDialog != null) {
                roundsInAirDialog.refresh();
            }
        }

        @Override
        public void gameBoardNew(GameBoardNewEvent e) {
            int boardId = e.getBoardId();
            IBoardView oldView = boardViews.remove(boardId);
            if (oldView != null) { oldView.dispose(); }
            RulerDialog oldRuler = rulers.remove(boardId);
            if (oldRuler != null) { oldRuler.dispose(); }
            BoardClientState oldState = boardStates.remove(boardId);
            if (oldState != null) { oldState.close(); }
            var oldMinimap = miniMaps.remove(boardId);
            if (oldMinimap != null) { oldMinimap.dispose(); }
            if (e.getNewBoard() != null) {
                try {
                    BoardClientState state = new BoardClientState(client.getGame(), controller, ClientGUI.this, boardId, tilesetManager);
                    boardStates.put(boardId, state);
                    state.setLocalPlayer(client.getLocalPlayer());
                    state.addBoardViewListener(ClientGUI.this);
                    if (curPanel instanceof BoardViewListener listener) { state.addBoardViewListener(listener); }
                    state.addOverlay(new ChatterBoxOverlay(ClientGUI.this, state, controller, cb));
                    state.addOverlay(new UnitOverviewOverlay(ClientGUI.this));
                    state.addOverlay(new UnitOverviewOverlay(ClientGUI.this, true));
                    state.addOverlay(new OffBoardTargetOverlay(ClientGUI.this));
                    state.addOverlay(new KeyBindingsOverlay(state));
                    state.addOverlay(new PlanetaryConditionsOverlay(state));
                    state.addOverlay(new TurnDetailsOverlay(state));
                    state.addOverlay(new BoardToastOverlay(state, ClientGUI.this));
                    state.redrawAllEntities();
                    state.refreshAttacks();
                    if (boardViewsContainer.isLegacy2DEnabled()) { createLegacy2DBoardView(state); }
                    MinimapDialog minimap = new MinimapDialog(frame);
                    minimap.add(new MinimapPanel(minimap, client.getGame(), state, ClientGUI.this, boardId));
                    minimap.setVisible(!client.getGame().getPhase().isLounge()
                          && boardViewsContainer.isLegacy2DEnabled() && !GpuBoardWindow.isActiveFor(ClientGUI.this)
                          && GUIP.getMinimapEnabled());
                    miniMaps.put(boardId, minimap);
                } catch (IOException ex) { throw new IllegalStateException("Could not initialize board presentation", ex); }
            }
            boardViewsContainer.updateMapTabs();
            if (CG_BOARD_VIEW.equals(mainNames.get(client.getGame().getPhase().toString()))) {
                showDefaultBoard(CG_BOARD_VIEW);
            }
        }

        @Override
        public void gamePlayerChange(GamePlayerChangeEvent evt) {
            if (playerListDialog != null) {
                playerListDialog.refreshPlayerList();

                if (currPhaseDisplay != null) {
                    currPhaseDisplay.setStatusBarWithNotDonePlayers();
                }
            }
            // The bot commands panel is hidden while the game has no bots; a player change can be a bot joining
            // (e.g. replacing a dropped player) or the last bot leaving, so re-evaluate the panel.
            maybeShowBotCommands();
            // the Game Master role may have changed hands, so keep the title's marker and the Game menu's
            // Game Master entries in step
            updateFrameTitle();
            updateGameMasterMenuItems();
        }

        @Override
        public void gamePlayerDisconnected(GamePlayerDisconnectedEvent evt) {
            if (!disconnectQuietly) {
                doAlertDialog(Messages.getString("ClientGUI.Disconnected.message"),
                      Messages.getString("ClientGUI.Disconnected.title"),
                      JOptionPane.ERROR_MESSAGE);
            }
            frame.setVisible(false);
            die();
        }

        @Override
        public void gamePlayerChat(GamePlayerChatEvent e) {
            bingChat();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent e) {
            for (BoardClientState bv : boardStates()) {
                // This is a really lame place for this, but I couldn't find a
                // better one without making massive changes (which didn't seem
                // worth it for one little feature).
                if (bv.getLocalPlayer() != client.getLocalPlayer()) {
                    // The address based comparison is somewhat important.
                    // Use of the /reset command can cause the player to get reset,
                    // and the equals function of Player isn't powerful enough.
                    bv.setLocalPlayer(client.getLocalPlayer().getId());
                }
                bv.setChatterBoxActive(false);
            }

            // Swap to this phase's panel.
            GamePhase phase = getClient().getGame().getPhase();
            switchPanel(phase);

            // Keep the Rounds in the Air list current as rounds are declared, fly, and land each phase.
            if (roundsInAirDialog != null) {
                roundsInAirDialog.refresh();
            }

            // Special-report packets replay the whole phase report each time they are sent, so the "already toasted"
            // history only has to span a single phase. Clearing it here also lets a line that genuinely recurs in a
            // later phase toast again.
            toastedReportEntries.clear();

            // Once per round, remind the player of own platoons busy raising bridges (TO:AUE); building
            // platoons are ineligible for all phases, so no phase display ever selects them
            if (phase.isMovement()) {
                showBridgeBuildProgressToasts();
            }

            // Reset spotting FOV mode at game start to prevent player confusion
            if (phase.isLounge()) {
                GUIP.setFovSpottingMode(false);
            }

            if (phase.isDeployment()) {
                setWeaponOrderPrefs(false);
                setSensorPrefs();
            }

            menuBar.setPhase(phase);

            // Update the frame title to show current round and phase
            updateFrameTitle();

            // Update Nova Networks menu based on whether Nova CEWS units exist
            updateNovaNetworksMenu();

            clientGuiPanel.validate();
            cb.moveToEnd();
            hideFleeZone();
        }

        @Override
        public void gameEntityChange(GameEntityChangeEvent e) {
            if ((unitDisplayState.getCurrentEntity() != null)
                  && (e.getEntity() != null)
                  && (unitDisplayState.getCurrentEntity().getId() == e.getEntity().getId())) {
                // underlying object may have changed, so reset
                unitDisplayState.displayEntity(e.getEntity());
            }
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent e) {
            updateNovaNetworksMenu();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent e) {
            updateNovaNetworksMenu();
        }

        @Override
        public void gameReport(GameReportEvent e) {
            // Normally the Report Display is updated when the panel is
            // switched during a phase change.
            // This update is for reports that get sent at odd times,
            // currently Tactical Genius reroll requests and when
            // a player wishes to continue moving after a fall.
            if (getClient().getGame().getPhase().isInitiativeReport()) {
                miniReportDisplayAddReportPages();
                reportDisplayResetDone();
                // Check if the player deserves an active reroll button
                // (possible, if he gets one which he didn't use, and his
                // opponent got and used one) and if so activates it.
                reportDisplayResetRerollInitiative();

                if (!(getClient() instanceof BotClient)) {
                    showReportAsToasts(Messages.getString("ClientGUI.dialogTacticalGeniusReport"),
                          e.getReport());
                }
            } else {
                // Continued movement after getting up
                if (!(getClient() instanceof BotClient)) {
                    showReportAsToasts(Messages.getString("ClientGUI.dialogMovementReport"), e.getReport());
                }
            }
        }

        @Override
        public void gameEnd(GameEndEvent e) {
            getBoardState().clearMovementData();
            clearFieldOfFire();
            clearTemporarySprites();
            getLocalBots().values().forEach(AbstractClient::die);
            getLocalBots().clear();

            // Make a list of the player's living units.
            ArrayList<Entity> living = getClient().getGame().getPlayerEntities(getClient().getLocalPlayer(), false);

            // Be sure to include all units that have retreated.
            for (Enumeration<Entity> iter = getClient().getGame().getRetreatedEntities(); iter.hasMoreElements(); ) {
                Entity ent = iter.nextElement();
                if (ent.getOwnerId() == getClient().getLocalPlayer().getId()) {
                    living.add(ent);
                }
            }

            if (PreferenceManager.getClientPreferences().askForVictoryList()) {
                // Ask if you want to persist the final unit list from a battle encounter
                if (doYesNoDialog(Messages.getString("ClientGUI.SaveUnitsDialog.title"),
                      Messages.getString("ClientGUI.SaveUnitsDialog.message"))) {
                    saveVictoryList();
                }
            }

            // save all destroyed units in a separate "salvage MUL"
            ArrayList<Entity> destroyed = new ArrayList<>();
            Enumeration<Entity> graveyard = getClient().getGame().getGraveyardEntities();
            while (graveyard.hasMoreElements()) {
                Entity entity = graveyard.nextElement();
                if (entity.isSalvage()) {
                    destroyed.add(entity);
                }
            }

            if (!destroyed.isEmpty()) {
                String sLogDir = CP.getLogDirectory();
                File logDir = new File(sLogDir);
                if (!logDir.exists()) {
                    logDir.mkdir();
                }
                String fileName = CG_FILENAME_SALVAGE + CG_FILE_EXTENSION_MUL;
                if (CP.stampFilenames()) {
                    fileName = StringUtil.addDateTimeStamp(fileName);
                }
                File unitFile = new File(sLogDir + File.separator + fileName);
                try {
                    // Save the destroyed entities to the file.
                    EntityListFile.saveTo(unitFile, destroyed);
                } catch (IOException ex) {
                    logger.error(ex, "gameEnd");
                    doAlertDialog(Messages.getString("ClientGUI.errorSavingFile"), ex.getMessage());
                }
            }

        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent evt) {
            if ((gameOptionsDialog != null) && gameOptionsDialog.isVisible() && !evt.isMapSettingsOnlyChange()) {
                gameOptionsDialog.update(getClient().getGame().getOptions());
            }


            // the host may have turned Allow Game Master on or off, which offers or takes away the Become entry,
            // or the burst fire and hot-loading options behind the lobby's all-units equipment shortcuts
            updateGameMasterMenuItems();
            updateLobbyEquipmentMenuItems();
        }

        @Override
        public void gameScriptedEvent(GameScriptedEvent event) {
            if (event instanceof GameScriptedMessageEvent) {
                showScriptedMessage((GameScriptedMessageEvent) event);
            }
        }

        @Override
        public void gameToast(GameToastEvent event) {
            Entity entity = client.getGame().getEntity(event.entityId());
            addToast(toastLevelFor(event.level()), event.message(), entity);
        }

        @Override
        public void gameClientFeedbackRequest(GameCFREvent gameCFREvent) {
            // Note: entity may be null for CFR types that don't use entityId (e.g., TAG_TARGET, TELEGUIDED_TARGET)
            // Each case handles null checking as appropriate
            Entity entity = client.getGame().getEntity(gameCFREvent.getEntityId());
            int direction = gameCFREvent.getDirection();
            Object result;
            String input;

            switch (gameCFREvent.getCFRType()) {
                case CFR_DOMINO_EFFECT:
                    // If the client connects to a game as a bot, it's possible to have the bot respond AND have the
                    // client ask the player. This is bad, ignore this if the client is a bot
                    if (client instanceof BotClient) {
                        return;
                    }
                    if (entity == null) {
                        return;
                    }

                    MovePath stepForward = new MovePath(client.getGame(), entity);
                    MovePath stepBackward = new MovePath(client.getGame(), entity);
                    boolean bNoCost = !Game.rulesManager.getRulesMovement().getDominoDisplacementCostsMP();
                    stepForward.addStep(MoveStepType.FORWARDS, bNoCost);
                    stepBackward.addStep(MoveStepType.BACKWARDS, bNoCost);
                    stepForward.compile(client.getGame(), entity, false);
                    stepBackward.compile(client.getGame(), entity, false);
                    Object[] options;
                    MovePath[] paths;
                    int optionType;
                    if (Game.rulesManager.getRulesMovement().isDominoMoveLegal(direction, entity, stepForward, true)
                          && Game.rulesManager.getRulesMovement().isDominoMoveLegal(direction, entity, stepBackward,
                          false)) {
                        options = new Object[3];
                        paths = new MovePath[3];
                        options[0] = Messages.getString("CFRDomino.Forward", stepForward.getMpUsed());
                        options[1] = Messages.getString("CFRDomino.Backward", stepForward.getMpUsed());
                        options[2] = Messages.getString("CFRDomino.NoAction");
                        paths[0] = stepForward;
                        paths[1] = stepBackward;
                        paths[2] = null;
                        optionType = JOptionPane.YES_NO_CANCEL_OPTION;
                    } else if (Game.rulesManager.getRulesMovement().isDominoMoveLegal(direction, entity, stepForward, true)) {
                        options = new Object[2];
                        paths = new MovePath[2];
                        options[0] = Messages.getString("CFRDomino.Forward", stepForward.getMpUsed());
                        options[1] = Messages.getString("CFRDomino.NoAction");
                        paths[0] = stepForward;
                        paths[1] = null;
                        optionType = JOptionPane.YES_NO_OPTION;
                    } else if (Game.rulesManager.getRulesMovement().isDominoMoveLegal(direction, entity, stepBackward
                          ,false)) {
                        options = new Object[2];
                        paths = new MovePath[2];
                        options[0] = Messages.getString("CFRDomino.Backward", stepBackward.getMpUsed());
                        options[1] = Messages.getString("CFRDomino.NoAction");
                        paths[0] = stepBackward;
                        paths[1] = null;
                        optionType = JOptionPane.YES_NO_OPTION;
                    } else {
                        // No request is sent if both moves are illegal
                        options = new Object[2];
                        paths = new MovePath[2];
                        options[0] = Messages.getString("CFRDomino.Backward", stepForward.getMpUsed());
                        options[1] = Messages.getString("CFRDomino.NoAction");
                        paths[0] = stepBackward;
                        paths[1] = null;
                        optionType = JOptionPane.YES_NO_OPTION;
                    }
                    int choice = option(Messages.getFormattedString("CFRDomino.Message", entity.getDisplayName()),
                          Messages.getString("CFRDomino.Title"),
                          optionType,
                          JOptionPane.QUESTION_MESSAGE,
                          null,
                          options,
                          options[0]);
                    // If they closed it, assume no action
                    if (choice == JOptionPane.CLOSED_OPTION) {
                        choice = options.length - 1;
                    }
                    client.sendDominoCFRResponse(paths[choice]);
                    break;
                case CFR_AMS_ASSIGN:
                    if (entity == null) {
                        return;
                    }
                    ArrayList<String> amsOptions = new ArrayList<>();
                    amsOptions.add(Messages.getString("NONE"));
                    for (WeaponAttackAction waa : gameCFREvent.getWAAs()) {
                        Entity ae = waa.getEntity(client.getGame());
                        String waaMsg;
                        if (ae != null) {
                            Mounted<?> weapon = ae.getEquipment(waa.getWeaponId());
                            waaMsg = weapon.getDesc() + " " + Messages.getString("FROM") + "  " + ae.getDisplayName();
                        } else {
                            waaMsg = Messages.getString("ClientGUI.missilesFromAnUnknownAttacker");
                        }
                        amsOptions.add(waaMsg);
                    }

                    // Updated AMS selection code for dealing with Multi_AMS and standard selection
                    JList amsList = new JList(amsOptions.toArray());
                    JScrollPane amsScrollPane = new JScrollPane(amsList);
                    int amsMaxCount;
                    if (entity.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_MULTI_USE_AMS)) {
                        amsMaxCount = amsOptions.size();
                    } else if (Game.rulesManager.getRulesEquipment().getAMSMultiShot()) {
                        amsMaxCount = 2;
                    } else {
                        amsMaxCount = 1;
                    }
                    amsList.setSelectionModel(new AmsAssignGUI(amsList, amsMaxCount));

                    String amsTitle = Messages.getString("CFRAMSAssign.Message", entity.getDisplayName());
                    DialogAnswer amsAnswer = askRows("", amsTitle,
                          amsOptions.stream().map(option -> new DialogRow(option, "", null, true)).toList(), true,
                          List.of(), amsMaxCount, optionButtons(JOptionPane.OK_CANCEL_OPTION), 1);
                    int amsResult;
                    if (amsAnswer != null) {
                        // The list stays the source of the selection, capped by its selection model as in Swing
                        amsList.setSelectedIndices(amsAnswer.selected().stream().mapToInt(Integer::intValue).toArray());
                        amsResult = (amsAnswer.button() == 0) ? JOptionPane.OK_OPTION : JOptionPane.CANCEL_OPTION;
                    } else {
                        amsResult = JOptionPane.showConfirmDialog(frame,
                              amsScrollPane,
                              amsTitle,
                              JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE
                        );
                    }

                    int[] selectedItems = amsList.getSelectedIndices();
                    if (amsResult == JOptionPane.OK_OPTION && !(selectedItems.length == 1
                          && amsList.getSelectedValue() == "NONE")) {
                        // Due to the "None" option, reduce all selected index values by 1.
                        // This makes "None" a -1 value.
                        for (int i = 0; i < selectedItems.length; i++) {
                            selectedItems[i] = selectedItems[i] - 1;
                        }
                        client.sendAMSAssignCFRResponse(selectedItems);
                    } else {
                        client.sendAMSAssignCFRResponse(null);
                    }

                    break;
                case CFR_APDS_ASSIGN:
                    if (entity == null) {
                        return;
                    }
                    ArrayList<String> apdsOptions = new ArrayList<>();
                    apdsOptions.add(Messages.getString("NONE"));
                    Iterator<Integer> distIt = gameCFREvent.getApdsDistances().iterator();
                    for (WeaponAttackAction waa : gameCFREvent.getWAAs()) {
                        Entity ae = waa.getEntity(client.getGame());
                        int dist = distIt.next();
                        String waaMsg;
                        if (ae != null) {
                            Mounted<?> weapon = ae.getEquipment(waa.getWeaponId());
                            waaMsg = weapon.getDesc()
                                  + " "
                                  + Messages.getString("FROM")
                                  + " "
                                  + ae.getDisplayName()
                                  + " ("
                                  + Messages.getString("ClientGUI.distance")
                                  + " "
                                  + dist
                                  + ")";
                        } else {
                            waaMsg = Messages.getString("ClientGUI.missilesFromAnUnknownAttacker");
                        }
                        apdsOptions.add(waaMsg);
                    }

                    result = input(Messages.getString("CFRAPDSAssign.Message", entity.getDisplayName()),
                          Messages.getString("CFRAPDSAssign.Title", entity.getDisplayName()),
                          JOptionPane.QUESTION_MESSAGE,
                          apdsOptions.toArray(),
                          null);
                    // If they closed it, assume no action
                    if ((result == null) || result.equals(Messages.getString("NONE"))) {
                        client.sendAPDSAssignCFRResponse(null);
                    } else {
                        client.sendAPDSAssignCFRResponse(apdsOptions.indexOf(result) - 1);
                    }
                    break;
                case CFR_HIDDEN_PBS:
                    Entity attacker = client.getGame().getEntity(gameCFREvent.getEntityId());
                    Entity target = client.getGame().getEntity(gameCFREvent.getTargetId());
                    // Are we not the client handling the PBS?
                    if ((attacker == null) || (target == null)) {
                        if (curPanel instanceof StatusBarPhaseDisplay) {
                            ((StatusBarPhaseDisplay) curPanel).setStatusBarText(Messages.getString(
                                  "StatusBarPhaseDisplay.pointblankShot"));
                        }
                        return;
                    }
                    // Confirm if these units can be part of a PBS at all
                    if (!Compute.canPointBlankShot(attacker, target)) {
                        // If we are the correct client but the PBS is not legal, return a cancellation
                        logger.error("Received request to handle an illegal pointblank shot ({} @ {} -> {} @ {})",
                              attacker.getDisplayName(),
                              attacker.getPosition().toFriendlyString(),
                              target.getDisplayName(),
                              target.getPosition().toFriendlyString());
                        client.sendHiddenPBSCFRResponse(null);
                        return;
                    }
                    // If this is the client to handle the PBS, take care of it
                    getBoardState().centerOn(attacker);
                    getBoardState().highlight(attacker.getPosition());
                    getBoardState().select(target.getPosition());
                    getBoardState().cursor(target.getPosition());

                    // Ask whether the player wants to take a PBS or not
                    int pbsChoice = confirm(Messages.getString("ClientGUI.PointBlankShot.Message",
                                target.getShortName(),
                                attacker.getShortName()),
                          Messages.getString("ClientGUI.PointBlankShot.Title"),
                          JOptionPane.YES_NO_OPTION,
                          JOptionPane.QUESTION_MESSAGE);
                    // Process the PBS - switch to PointblankShotDisplay
                    if (pbsChoice == JOptionPane.YES_OPTION) {
                        // Send a non-null response to indicate PBS is accepted
                        // This allows the servers to notify the clients,
                        // as they may be in for a wait
                        client.sendHiddenPBSCFRResponse(new Vector<>());
                        // Used to indicate it's this player's turn
                        setPointblankEID(gameCFREvent.getEntityId());
                        // Switch to the right display
                        switchPanel(GamePhase.POINTBLANK_SHOT);
                        PointblankShotDisplay currentDisplay = ((PointblankShotDisplay) curPanel);
                        // Set targeting info
                        currentDisplay.beginMyTurn();
                        currentDisplay.selectEntity(gameCFREvent.getEntityId());
                        currentDisplay.target(target);
                        getBoardState().select(target.getPosition());
                    } else { // PBS declined
                        client.sendHiddenPBSCFRResponse(null);
                    }
                    break;
                case CFR_TELEGUIDED_TARGET:
                    logger.debug("CFR_TELEGUIDED_TARGET: processing teleguided missile target selection");
                    List<Integer> targetIds = gameCFREvent.getTelemissileTargetIds();
                    List<Integer> toHitValues = gameCFREvent.getTmToHitValues();
                    List<String> targetDescriptions = new ArrayList<>();
                    for (int i = 0; i < targetIds.size(); i++) {
                        int id = targetIds.get(i);
                        int th = toHitValues.get(i);
                        Entity tgt = client.getGame().getEntity(id);
                        if (tgt != null) {
                            targetDescriptions.add(Messages.getFormattedString("TeleMissileTargetDialog.target",
                                  tgt.getDisplayName(),
                                  th));
                        }
                    }
                    logger.debug("CFR_TELEGUIDED_TARGET: showing dialog with {} targets", targetDescriptions.size());
                    // Set up the selection pane
                    input = (String) input(Messages.getString("TeleMissileTargetDialog.message"),
                          Messages.getString("TeleMissileTargetDialog.title"),
                          JOptionPane.QUESTION_MESSAGE,
                          targetDescriptions.toArray(),
                          targetDescriptions.getFirst());
                    if (input != null) {
                        for (int i = 0; i < targetDescriptions.size(); i++) {
                            if (input.equals(targetDescriptions.get(i))) {
                                logger.debug("CFR_TELEGUIDED_TARGET: user selected target index {}", i);
                                client.sendTelemissileTargetCFRResponse(i);
                                break;
                            }
                        }
                    } else {
                        // If input is null, as in the case of pressing the close or cancel buttons...
                        // Just pick the first target in the list, or server will be left waiting
                        // indefinitely.
                        logger.debug("CFR_TELEGUIDED_TARGET: dialog cancelled, defaulting to first target");
                        client.sendTelemissileTargetCFRResponse(0);
                    }
                    break;
                case CFR_TAG_TARGET:
                    logger.debug("CFR_TAG_TARGET: processing TAG target selection");
                    List<Integer> TAGTargets = gameCFREvent.getTAGTargets();
                    List<Integer> TAGTargetTypes = gameCFREvent.getTAGTargetTypes();
                    List<String> TAGTargetDescriptions = new ArrayList<>();
                    for (int i = 0; i < TAGTargets.size(); i++) {
                        int id = TAGTargets.get(i);
                        int nType = TAGTargetTypes.get(i);
                        Targetable tgt = client.getGame().getTarget(nType, id);
                        if (tgt != null) {
                            TAGTargetDescriptions.add(tgt.getDisplayName());
                        }
                    }
                    logger.debug("CFR_TAG_TARGET: showing dialog with {} targets", TAGTargetDescriptions.size());
                    // Set up the selection pane
                    input = (String) input(Messages.getString("TAGTargetDialog.message"),
                          Messages.getString("TAGTargetDialog.title"),
                          JOptionPane.QUESTION_MESSAGE,
                          TAGTargetDescriptions.toArray(),
                          TAGTargetDescriptions.getFirst());
                    if (input != null) {
                        for (int i = 0; i < TAGTargetDescriptions.size(); i++) {
                            if (input.equals(TAGTargetDescriptions.get(i))) {
                                logger.debug("CFR_TAG_TARGET: user selected target index {}", i);
                                client.sendTAGTargetCFRResponse(i);
                                break;
                            }
                        }
                    } else {
                        // If input IS null, as in the case of pressing the close or cancel buttons...
                        // Just pick the first target in the list, or server will be left waiting
                        // indefinitely.
                        logger.debug("CFR_TAG_TARGET: dialog cancelled, defaulting to first target");
                        client.sendTAGTargetCFRResponse(0);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    public Client getClient() {
        return client;
    }

    /** @return the lobby's commands for this client; the lobby attaches its own dialogs while it shows */
    public LobbyCommands lobbyCommands() {
        if (lobbyCommands == null) {
            lobbyCommands = new LobbyCommands(this::getClient, new DecliningLobbyPrompts());
        }
        return lobbyCommands;
    }

    /**
     * EDT: the board editor opened from the lobby saved {@code savedFile}, or nothing (null); while still in the lobby,
     * the map settings take the saved board, as the map setup's own Edit Board does.
     */
    public void boardEditorClosed(@Nullable Path savedFile, boolean savedAssembledPreview) {
        LobbyBoardPreviews.boardEditorClosed(this, savedFile, savedAssembledPreview);
    }
    @Override
    public JComponent turnTimerComponent() {
        return menuBar;
    }

    @Override
    public void setChatBoxActive(boolean active) {
        getCurrentBoardState().ifPresent(state -> state.setChatterBoxActive(active));
    }

    @Override
    public void clearChatBox() {
        getCurrentBoardState().ifPresent(state -> state.setChatterBoxActive(false));
    }

    @Override
    public boolean isChatBoxActive() {
        return getCurrentBoardState().map(BoardClientState::getChatterBoxActive).orElse(false);
    }

    @Override
    public Map<String, AbstractClient> getLocalBots() {
        return client.getBots();
    }

    /**
     * What each player's units were generated for - faction, command, year and rating - keyed by
     * player id. Recorded when an army generator adds units, and used by force organization to build
     * a structure the way that faction would.
     *
     * <p>Client-local and deliberately not transmitted: it describes a choice this client's user made
     * in a dialog, and only this client generates on their behalf. It is not game state.</p>
     */
    private final Map<Integer, GenerationContext> generationContexts = new HashMap<>();

    /**
     * Records what a player's newly generated units were rolled for, replacing any earlier record.
     * Callers record only what a generator actually asked the player, never a default, so that a
     * later roll on a generator that knows nothing cannot erase a real choice.
     *
     * @param playerId the owner of the generated units
     * @param context  what they were generated for
     */
    public void setGenerationContext(int playerId, GenerationContext context) {
        generationContexts.put(playerId, context);
    }

    /**
     * @param playerId the player to look up
     *
     * @return what that player's units were generated for, or {@code null} when nothing has been
     *       recorded for them - they have generated nothing, or only from a generator that asks for
     *       no faction, command or rating
     */
    public @Nullable GenerationContext getGenerationContext(int playerId) {
        return generationContexts.get(playerId);
    }

    /**
     * @param selectedEntityNum The selectedEntityNum to set.
     */
    public void setSelectedEntityNum(int selectedEntityNum) {
        boardStates().forEach(state -> state.selectEntity(client.getGame().getEntity(selectedEntityNum)));
    }

    public RandomArmyDialog getRandomArmyDialog() {
        if (randomArmyDialog == null) {
            new UnitLoadingDialog(frame).setVisible(true);
            randomArmyDialog = new RandomArmyDialog(frame, this);
        }
        return randomArmyDialog;
    }

    public RandomNameDialog getRandomNameDialog() {
        return new RandomNameDialog(this);
    }

    /**
     * Checks to see if there is already a path and name stored; if not, calls "save as"; otherwise, saves the board to
     * the specified file.
     */
    private void boardSave(Game game) {
        if (curFileBoard == null || game.getBoard().requiresNativeFormat()
              && !megamek.common.board.BoardFile.isNativeName(curFileBoard.getName())) {
            boardSaveAs(game);
            return;
        }

        if (game.getBoard().requiresNativeFormat() || megamek.common.board.BoardFile.isNativeName(curFileBoard.getName())) {
            // Game boards are converted where they load, so the game board itself is written.
            try { megamek.common.board.BoardFile.save(game.getBoard(), curFileBoard.toPath()); }
            catch (IOException failure) { logger.error(failure, "Failed to save native board"); }
            return;
        }

        // save!
        try (OutputStream os = new FileOutputStream(curFileBoard)) {
            game.getBoard().save(os);
        } catch (Exception ex) {
            logger.error(ex, "Failed to save board!");
        }
    }

    /**
     * Saves the board in PNG image format.
     */
    private void boardSaveImage(boolean ignoreUnits) {
        if (curFileBoardImage == null) {
            boardSaveAsImage(ignoreUnits);
            return;
        }
        JDialog waitD = new JDialog(frame, Messages.getString("BoardEditor.waitDialog.title"));
        waitD.add(new JLabel(Messages.getString("BoardEditor.waitDialog.message")));
        waitD.setSize(250, 130);
        // move to middle of screen
        waitD.setLocation((frame.getSize().width / 2) - (waitD.getSize().width / 2),
              (frame.getSize().height / 2) - (waitD.getSize().height / 2));
        waitD.setVisible(true);
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        waitD.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // save!
        try {
            ImageIO.write(getCurrentBoardState().orElseThrow().getEntireBoardImage(ignoreUnits),
                  CG_FILE_FORMAT_NAME_PNG,
                  curFileBoardImage);
        } catch (IOException e) {
            logger.error(e, "boardSaveImage");
        }
        waitD.setVisible(false);
        frame.setCursor(Cursor.getDefaultCursor());
    }

    /**
     * Opens a file dialog box to select a file to save as; saves the board to the file.
     */
    public void boardSaveAs(Game game) {
        JFileChooser fc = new JFileChooser(CG_FILEPATH_DATA + File.separator + CG_FILEPATH_BOARDS);
        fc.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
        fc.setDialogTitle(Messages.getString("BoardEditor.saveBoardAs"));
        fc.setFileFilter(new BoardFileFilter());
        int returnVal = fc.showSaveDialog(frame);
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (fc.getSelectedFile() == null)) {
            return;
        }
        if (game.getBoard().requiresNativeFormat() || megamek.common.board.BoardFile.isNativeName(fc.getSelectedFile().getName())) {
            java.nio.file.Path target = fc.getSelectedFile().toPath();
            if (!megamek.common.board.BoardFile.isNativeName(target.toString())) {
                target = target.resolveSibling(megamek.common.board.BoardFile.withoutExtension(target.getFileName().toString()) + ".board2");
            }
            if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(frame,
                  "Replace " + target.getFileName() + "?", "Save board", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            try { megamek.common.board.BoardFile.save(game.getBoard(), target); curFileBoard = target.toFile(); }
            catch (IOException failure) { logger.error(failure, "Failed to save native board"); }
            return;
        }
        curFileBoard = fc.getSelectedFile();

        // make sure the file ends in board
        if (!curFileBoard.getName().toLowerCase(Locale.ENGLISH).endsWith(CG_FILE_EXTENSION_BOARD)) {
            try {
                curFileBoard = new File(curFileBoard.getCanonicalPath() + CG_FILE_EXTENSION_BOARD);
            } catch (IOException ie) {
                // failure!
                return;
            }
        }
        boardSave(game);
    }

    /**
     * Opens a file dialog box to select a file to save as; saves the board to the file as an image. Useful for printing
     * boards.
     */
    private void boardSaveAsImage(boolean ignoreUnits) {
        JFileChooser fc = new JFileChooser(".");
        fc.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
        fc.setDialogTitle(Messages.getString("BoardEditor.saveAsImage"));
        fc.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File dir) {
                return (dir.getName().endsWith(CG_FILE_EXTENSION_PNG) || dir.isDirectory());
            }

            @Override
            public String getDescription() {
                return CG_FILE_EXTENSION_PNG;
            }
        });
        int returnVal = fc.showSaveDialog(frame);
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (fc.getSelectedFile() == null)) {
            return;
        }
        curFileBoardImage = fc.getSelectedFile();

        // make sure the file ends in png
        if (!curFileBoardImage.getName().toLowerCase(Locale.ENGLISH).endsWith(CG_FILE_EXTENSION_PNG)) {
            try {
                curFileBoardImage = new File(curFileBoardImage.getCanonicalPath() + CG_FILE_EXTENSION_PNG);
            } catch (IOException ie) {
                // failure!
                return;
            }
        }
        boardSaveImage(ignoreUnits);
    }

    @Override
    public void hexMoused(BoardViewEvent event) {
        if (event.getType() == BoardViewEvent.BOARD_HEX_POPUP) {
            showBoardPopup(event);
        }
    }

    @Override
    public void hexCursor(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void boardHexHighlighted(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void hexSelected(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void firstLOSHex(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void secondLOSHex(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void finishedMovingUnits(BoardViewEvent b) {
        // ignored
    }

    @Override
    public void unitSelected(BoardViewEvent b) {
        JComponent panel = getCurrentPanel();
        // These controllers select the acting unit (or a special target) themselves.
        // Startup, report and setup panels still need ordinary unit inspection.
        if (hasUnitSelectionController(panel)
              || panel instanceof AbstractPhaseDisplay phase && phase.isIgnoringEvents()) {
            return;
        }
        inspectUnit(b.getEntityId());
    }

    /** Whether the current panel owns acting-unit selection or special unit targets. */
    public static boolean hasUnitSelectionController(JComponent panel) {
        return panel instanceof ActionPhaseDisplay || panel instanceof DeploymentDisplay || panel instanceof PrephaseDisplay;
    }

    /** Inspect a visible unit without changing the acting unit, its orders, or the camera. */
    public void inspectUnit(int entityId) {
        if (shouldIgnoreHotKeys()) {
            return;
        }
        Game game = getClient().getGame();
        Entity entity = game.getEntity(entityId);
        Player viewer = getClient().getLocalPlayer();
        if (entity != null && EntityVisibilityUtils.detectedOrHasVisual(viewer, game, entity)
              && !EntityVisibilityUtils.onlyDetectedBySensors(viewer, entity)) {
            getUnitDisplayState().displayEntity(entity);
            setSelectedEntityNum(entity.getId());
            maybeShowUnitDisplay();
        }
    }

    @Override
    public boolean shouldIgnoreHotKeys() {
        return ignoreHotKeys
              || !isGameWindowActive()
              || ((gameOptionsDialog != null) && gameOptionsDialog.isVisible())
              || isModalDialogShown()
              || ((help != null) && help.isVisible())
              || ((commonSettingsDialog != null) && commonSettingsDialog.isVisible());
    }

    /**
     * The key dispatcher is registered application-wide, so when this game runs inside MekHQ it also sees keys typed
     * into MekHQ windows. Hotkeys must only fire while the game frame, or a window it owns such as the minimap, is the
     * active window; otherwise e.g. Backspace (undo step) and the arrow keys (weapon/target cycling) are swallowed
     * from MekHQ text fields. See MekHQ issue #10642.
     * <p>
     * Swing reports no active window at all while a window it does not own has the focus, such as the native board
     * window, whose keys reach the game through its own input. That counts as the game window; a MekHQ window is a
     * Swing window and so still does not.
     * </p>
     */
    private boolean isGameWindowActive() {
        Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (window == null) {
            return true;
        }
        while (window != null) {
            if (window == frame) {
                return true;
            }
            window = window.getOwner();
        }
        return false;
    }

    void editBots() {
        if (!inLounge() && GpuBoardWindow.showEditBots(this)) {
            return;
        }
        var rpd = new EditBotsDialog(frame, this);
        rpd.setVisible(true);
        if (rpd.getResult() == DialogResult.CANCELLED) {
            return;
        }
        applyBotEdits(rpd.getNewBots(), rpd.getNewBotTypes(), rpd.getChangedBots(), rpd.getKickBots());
    }

    /**
     * Carries out what Edit Bots chose: the ghosts to replace with a bot of the given AI and settings, the local bots
     * whose settings changed, and the bots to kick.
     */
    public void applyBotEdits(Map<String, BehaviorSettings> newBotSettings, Map<String, AIType> newBotTypes,
          Map<String, BehaviorSettings> changedBots, Set<String> kickBotNames) {
        AddBotUtil util = new AddBotUtil();
        for (String ghostName : newBotSettings.keySet()) {
            StringBuilder message = new StringBuilder();
            AIType aiType = newBotTypes.getOrDefault(ghostName, AIType.PRINCESS);
            BotClient botClient = null;
            try {
                botClient = util.replaceGhostWithBot(aiType, newBotSettings.get(ghostName), ghostName,
                      client, message);
            } catch (Exception exception) {
                logger.error(exception, "Failed to stand up a " + aiType + " bot for " + ghostName);
            }
            // An experimental bot that fails to stand up must not leave the seat empty: the player asked
            // for a bot in that slot, so Princess takes it instead. Guarded like the first attempt, so a
            // failure here degrades to an empty seat and a log line rather than a crash.
            if ((botClient == null) && (AIType.PRINCESS != aiType)) {
                message.append(" Falling back to Princess. ");
                try {
                    botClient = util.replaceGhostWithBot(AIType.PRINCESS, newBotSettings.get(ghostName),
                          ghostName, client, message);
                } catch (Exception exception) {
                    logger.error(exception, "The Princess fallback failed for " + ghostName + " too");
                }
            }
            systemMessage(message.toString());
            // Make this bot a locally owned bot. This way it can be configured, and if on the lobby
            // it will faithfully press Done when the local player does.
            if (botClient != null) {
                getLocalBots().put(ghostName, botClient);
            }
        }

        for (String botName : changedBots.keySet()) {
            StringBuilder message = new StringBuilder();
            util.changeBotSettings(changedBots.get(botName), botName, client, message);
            systemMessage(message.toString());
        }

        for (String botName : kickBotNames) {
            StringBuilder message = new StringBuilder();
            util.kickBot(botName, client, message);
            systemMessage(message.toString());
        }
    }

    @Override
    public JComponent getCurrentPanel() {
        return curPanel;
    }

    public boolean isProcessingPointblankShot() {
        return pointblankEID != Entity.NONE;
    }

    public void setPointblankEID(int eid) {
        pointblankEID = eid;
    }

    public int getPointblankEID() {
        return pointblankEID;
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent e) {
        switch (e.getName()) {
            case GUIPreferences.MINI_MAP_ENABLED -> setMapVisible(GUIP.getMinimapEnabled());
            case GUIPreferences.PLAYER_LIST_ENABLED -> setPlayerListVisible(GUIP.getPlayerListEnabled());
            case GUIPreferences.ROUNDS_IN_AIR_ENABLED -> setRoundsInAirVisible(GUIP.getRoundsInAirEnabled());
            case GUIPreferences.REVEAL_ALL_ARTILLERY_ROUNDS -> {
                getClient().sendArtilleryRevealPreference(GUIP.getBoolean(GUIPreferences.REVEAL_ALL_ARTILLERY_ROUNDS));
                if (roundsInAirDialog != null) {
                    roundsInAirDialog.refresh();
                }
            }
            case GUIPreferences.UNIT_DISPLAY_ENABLED, GUIPreferences.UNIT_DISPLAY_LOCATION ->
                  setUnitDisplayVisible(GUIP.getUnitDisplayEnabled());
            case GUIPreferences.FORCE_DISPLAY_ENABLED -> setForceDisplayVisible(GUIP.getForceDisplayEnabled());
            case GUIPreferences.MINI_REPORT_ENABLED, GUIPreferences.MINI_REPORT_LOCATION ->
                  setMiniReportVisible(GUIP.getMiniReportEnabled());
            case GUIPreferences.DOCK_ON_LEFT, GUIPreferences.DOCK_MULTIPLE_ON_Y_AXIS -> {
                setUnitDisplayVisible(GUIP.getUnitDisplayEnabled());
                setMiniReportVisible(GUIP.getMiniReportEnabled());
            }
            case GUIPreferences.DEFAULT_WEAPON_SORT_ORDER -> {
                setWeaponOrderPrefs(true);
                getUnitDisplayState().displayEntity(getUnitDisplayState().getCurrentEntity());
            }
            case GUIPreferences.SENSOR_PREFERENCE_ORDER -> {
                setSensorPrefs();
                getUnitDisplayState().displayEntity(getUnitDisplayState().getCurrentEntity());
            }
            case GUIPreferences.SOUND_BING_FILENAME_CHAT,
                 GUIPreferences.SOUND_BING_FILENAME_MY_TURN,
                 GUIPreferences.SOUND_BING_FILENAME_OTHERS_TURN -> audioService.loadSoundFiles();
            case GUIPreferences.MASTER_VOLUME -> audioService.setVolume();
            case GUIPreferences.BOT_COMMANDS_ENABLED, GUIPreferences.BOT_COMMANDS_LOCATION ->
                // Route through maybeShowBotCommands() so the phase rules (non-board phases never show the panel)
                // are enforced consistently, whether the change came from the menu, the hotkey, or a phase change.
                  maybeShowBotCommands();
        }
    }

    /**
     * Shows the movement envelope in the BoardView for the given entity. The movement envelope data is a map of move
     * end Coords to movement points used.
     *
     * @param entity    The entity for which the movement envelope is
     * @param mvEnvData The movement envelope data
     * @param gear      The move gear, MovementDisplay.GEAR_LAND or GEAR_JUMP
     */
    public void showMovementEnvelope(Entity entity, Map<Coords, Integer> mvEnvData, int gear) {
        movementEnvelopeHandler.setMovementEnvelope(mvEnvData,
              entity.getBoardId(),
              entity.getWalkMP(),
              entity.getRunMP(),
              entity.getAnyTypeMaxJumpMP(),
              gear);
    }

    /**
     * Removes visibility to the Movement Envelope.
     */
    public void clearMovementEnvelope() {
        movementEnvelopeHandler.clear();
    }

    /**
     * Removes all temporary sprites from the board, such as pending actions, movement envelope, collapse warnings etc.
     * Does not remove game-state sprites such as units or flares.
     */
    public void clearTemporarySprites() {
        clearMovementEnvelope();
        movementModifierSpriteHandler.clear();
        sensorRangeSpriteHandler.clear();
        collapseWarningSpriteHandler.clear();
        firingSolutionSpriteHandler.clear();
        firingArcSpriteHandler.clear();
    }

    /**
     * Shows the optimal available movement modifiers in the BoardView.
     *
     * @param movePaths The available longest move paths.
     */
    public void showMovementModifiers(Collection<MovePath> movePaths) {
        movementModifierSpriteHandler.renewSprites(movePaths);
    }

    /**
     * Shows the sensor/visual ranges for the given entity on its own position in the BoardView
     *
     * @param entity The entity that is looking/sensing
     */
    public void showSensorRanges(Entity entity) {
        showSensorRanges(entity, entity.getPosition());
    }

    /**
     * Shows the sensor/visual ranges for the given entity in the BoardView. The ranges are centered on the given
     * assumedPosition rather than the entity's own position.
     *
     * @param entity          The entity that is looking/sensing
     * @param assumedPosition The position to center all ranges on
     */
    public void showSensorRanges(Entity entity, Coords assumedPosition) {
        sensorRangeSpriteHandler.setSensorRange(entity, assumedPosition);
    }

    /**
     * Shows collapse warnings in the given list of Coords in the BoardView
     *
     * @param warnList The list of coordinates to show the warning on
     */
    public void showCollapseWarning(Collection<BoardLocation> warnList) {
        collapseWarningSpriteHandler.setCFWarningSprites(warnList);
    }

    /**
     * Shows saw clearing indicators on the given hexes in the BoardView.
     *
     * @param cutHexes a map of board locations to turns remaining for saw clearing
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    public void showSawClearingHexes(Map<BoardLocation, Integer> cutHexes) {
        sawClearingSpriteHandler.setSawClearingSprites(cutHexes);
    }

    /**
     * Shows ground object icons in the given list of Coords in the BoardView
     *
     * @param groundObjectList The list of coordinates to show
     */
    public void showGroundObjects(Map<Coords, List<ICarryable>> groundObjectList) {
        groundObjectSpriteHandler.setGroundObjectSprites(groundObjectList);
    }

    /**
     * Shows firing solutions from the viewpoint of the given entity on targets
     *
     * @param entity The attacking entity
     */
    public void showFiringSolutions(Entity entity) {
        firingSolutionSpriteHandler.showFiringSolutions(entity, getDisplayedWeapon(), getDisplayedAmmo());
    }

    public JPanel getMainPanel() {
        return clientGuiPanel;
    }

    /**
     * @return The unit currently shown in the Unit Display. Note: This can be a unit than the one that is selected to
     *       move or fire.
     */
    @Nullable
    public Entity getDisplayedUnit() {
        return unitDisplayState.getCurrentEntity();
    }

    /**
     * Returns the weapon that is currently selected in the Unit Display. The selection can be void for various reasons,
     * therefore this returns it as an Optional. Note: this method does some additional checks to avoid bugs where the
     * weapon of the same ID on the unit is different from the selected weapon or is not even present on the unit. Also,
     * the displayed unit is checked to be an active unit (i.e. can be found in game.getEntity()). It will log an error
     * and return null otherwise. Using the returned weapon should be fairly safe.
     *
     * @return The weapon that is currently selected in the Unit Display, if any
     */
    public Optional<WeaponMounted> getDisplayedWeapon() {
        WeaponMounted weapon = unitDisplayState.getSelectedWeapon();
        if ((getDisplayedUnit() == null) || (weapon == null) || (client.getGame().getEntity(getDisplayedUnit().getId())
              == null)) {
            return Optional.empty();
        }
        Mounted<?> weaponOnUnit = getDisplayedUnit().getEquipment(unitDisplayState.getSelectedWeaponNum());
        if (weaponOnUnit == weapon) {
            return Optional.of(weapon);
        } else if (weapon.getEntity() instanceof HandheldWeapon hhw && hhw.getAttackingEntity()
              .equals(getDisplayedUnit())) {
            return Optional.of(weapon);
        } else {
            logger.error("Unsafe selected weapon. Returning null instead. Equipment ID {} on unit {}",
                  unitDisplayState.getSelectedWeaponNum(),
                  getDisplayedUnit());
            return Optional.empty();
        }
    }

    public Optional<AmmoMounted> getDisplayedAmmo() {
        return unitDisplayState.getSelectedAmmo();
    }

    /**
     * Updates the shown firing arc. The given entity should be the one that has taken an action such as moving or torso
     * twisting or the unit whose selected weapon has changed. This method will check if the given unit is the one
     * displayed in the unit viewer and/or the currently acting unit and update or remove the firing arcs accordingly.
     *
     * @param entity The unit that has acted or is otherwise the origin of the update
     */
    public void updateFiringArc(Entity entity) {
        if ((entity == null) || (getDisplayedUnit() == null) || getDisplayedWeapon().isEmpty()) {
            // with no unit given or no unit displayed or no weapon selected, clear the
            // firing arcs
            clearFieldOfFire();
            return;

        } else if (!entity.equals(getDisplayedUnit())) {
            // the update is not for the displayed unit; therefore do not update the firing
            // arc
            return;
        }

        if (curPanel instanceof MovementDisplay md) {
            if (entity.getId() == md.getCurrentEntity()) {
                firingArcSpriteHandler.update(entity, getDisplayedWeapon().get(), md.getPlannedMovement());
                return;
            }
        }

        // not in an ActionPhase - or - the unit is not the acting unit:
        // show for viewed entity, no move or actions to be taken into account
        firingArcSpriteHandler.update(entity, getDisplayedWeapon().get());
        showFiringSolutions(entity);
    }

    /**
     * Removes the field of fire from the BoardView and clears the cached values in the sprite handler.
     */
    public void clearFieldOfFire() {
        firingArcSpriteHandler.clearValues();
    }

    /**
     * Updates the Nova Networks menu enablement based on whether the local player has any Nova CEWS units in their
     * force.
     */
    private void updateNovaNetworksMenu() {
        boolean hasNovaUnits = client.getGame().getEntitiesVector().stream()
              .filter(entity -> entity.getOwner().equals(client.getLocalPlayer()))
              .anyMatch(Entity::hasNovaCEWS);
        menuBar.setEnabled(VIEW_NOVA_NETWORKS, hasNovaUnits);
    }

    /**
     * Return the Current Hex, used by client commands for the visually impaired
     *
     * @return the current Hex
     */
    public Coords getCurrentHex() {
        return currentHex;
    }

    /**
     * Set the Current Hex, used by client commands for the visually impaired
     */
    public void setCurrentHex(Hex hex) {
        if (hex != null) {
            currentHex = hex.getCoords();
        }
    }

    public void setCurrentHex(Coords hex) {
        currentHex = hex;
    }

    private void toggleFleeZone() {
        showFleeZone = !showFleeZone;
        Entity entity = unitDisplayState.getCurrentEntity();
        if (showFleeZone && entity != null) {
            Game game = client.getGame();
            Board board = game.getBoard(entity);
            HexArea fleeZone = game.getFleeZone(entity);
            fleeZoneSpriteHandler.renewSprites(fleeZone.getCoords(board), board.getBoardId());
        } else {
            fleeZoneSpriteHandler.clear();
        }
    }

    public void hideFleeZone() {
        showFleeZone = false;
        if (fleeZoneSpriteHandler != null) {
            fleeZoneSpriteHandler.clear();
        }
    }

    /**
     * Returns true when the currently shown {@link BoardView} is showing some animation and should not be centered on
     * another hex or be hidden right now. An example is showing a unit's move animation.
     *
     * @return True when the currently shown BoardView is in the process of showing some animation
     */
    public boolean isCurrentBoardViewShowingAnimation() {
        return getCurrentBoardState().filter(BoardClientState::isShowingAnimation).isPresent();
    }

    /**
     * Shows the BoardView of the given location, if it exists, and centers on its coords.
     *
     * @param boardLocation The location to show and center on
     */
    public void centerOnHex(@Nullable BoardLocation boardLocation) {
        centerOn(boardLocation, null);
    }

    private void centerOn(@Nullable BoardLocation boardLocation, @Nullable Entity entity) {
        if (getClient().getGame().hasBoardLocation(boardLocation)) {
            showBoardView(boardLocation.boardId());
            BoardClientState board = getBoardState(boardLocation);
            if (entity == null) {
                board.centerOnHex(boardLocation.coords());
            } else {
                board.centerOn(entity);
            }
        }
    }

    /**
     * Shows the BoardView of the given targetable, if it exists, and centers on its position, if that exists.
     *
     * @param targetable The unit or target to show and center on
     */
    public void centerOnUnit(@Nullable Targetable targetable) {
        if (targetable != null) {
            centerOn(targetable.getBoardLocation(), targetable instanceof Entity entity ? entity : null);
        }
    }

    @Override
    public BoardView getBoardView(int boardId) {
        // TW games use only standard BoardViews
        return (BoardView) super.getBoardView(boardId);
    }

    @Override
    public BoardView getBoardView(Targetable entity) {
        // TW games use only standard BoardViews
        return (BoardView) super.getBoardView(entity);
    }

    @Override
    public BoardView getBoardView(BoardLocation boardLocation) {
        // TW games use only standard BoardViews
        return (BoardView) super.getBoardView(boardLocation);
    }

    public void onAllBoardViews(Consumer<BoardView> consumer) {
        boardViews().forEach(boardView -> consumer.accept((BoardView) boardView));
    }

    public void suspendBoardTooltips() {
        onAllBoardStates(BoardClientState::suspendTooltip);
        // hide any currently shown tooltip, but don't disable tooltips entirely:
        ToolTipManager.sharedInstance().setEnabled(false);
        ToolTipManager.sharedInstance().setEnabled(true);
    }

    public void activateBoardTooltips() {
        onAllBoardStates(BoardClientState::activateTooltip);
    }

    public TilesetManager getTilesetManager() {
        return tilesetManager;
    }
    public void onAllBoardStates(Consumer<BoardClientState> consumer) {
        boardStates().forEach(consumer);
    }

    private void createLegacy2DBoardView(BoardClientState state) {
        if (boardViews.containsKey(state.getBoardId())) { return; }
        try {
            BoardView view = new BoardView(state, controller, this);
            view.setTooltipProvider(new TWBoardViewTooltip(client.getGame(), this, state));
            view.getPanel().setPreferredSize(clientGuiPanel.getSize());
            view.getPanel().addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent event) { state.chatKey(event); }
            });
            boardViews.put(state.getBoardId(), view);
            rulers.computeIfAbsent(state.getBoardId(), id -> new RulerDialog(frame, state, client.getGame()));
            view.redrawAllEntities();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not open the Legacy 2D board", exception);
        }
    }

}
