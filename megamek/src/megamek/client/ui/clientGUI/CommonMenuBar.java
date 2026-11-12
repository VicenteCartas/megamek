/*
 * Copyright (C) 2003, 2004, 2005 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2003-2026 The MegaMek Team. All Rights Reserved.
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

import static java.awt.event.KeyEvent.*;
import static megamek.client.ui.Messages.getString;
import static megamek.client.ui.clientGUI.ClientGUI.*;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;

import megamek.MMConstants;
import megamek.MegaMek;
import megamek.client.Client;
import megamek.client.ui.CopySystemDataAction;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.client.ui.ReleaseNotesAction;
import megamek.client.ui.ShowBugReportDialogAction;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuUiLibraryWindow;
import megamek.client.ui.design.swing.SwingUiLibrary;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.KeyBindParser;
import megamek.common.enums.GamePhase;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.logging.MMLogger;

/**
 * The menu bar of the lobby and the game.
 */
public class CommonMenuBar extends JMenuBar implements ActionListener, IPreferenceChangeListener {
    private final static MMLogger logger = MMLogger.create(CommonMenuBar.class);

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();

    /**
     * Supplies the client whose game the bug report packager should save. The menu bar is built before any client
     * exists, so this is resolved lazily and defaults to no client.
     */
    private Supplier<Client> clientSupplier = () -> null;

    /** The current phase of the game, if any. */
    private GamePhase phase = GamePhase.UNKNOWN;

    // The Game menu
    private final JMenuItem gameLoad = new JMenuItem(getString("CommonMenuBar.fileGameLoad"));
    private final JMenuItem gameSave = new JMenuItem(getString("CommonMenuBar.fileGameSave"));
    private final JMenuItem gameQSave = new JMenuItem(getString("CommonMenuBar.fileGameQuickSave"));
    private final JMenuItem gameQLoad = new JMenuItem(getString("CommonMenuBar.fileGameQuickLoad"));
    private final JMenuItem gameSaveServer = new JMenuItem(getString("CommonMenuBar.fileGameSaveServer"));
    private final JCheckBoxMenuItem gameRoundReport = new JCheckBoxMenuItem(getString("CommonMenuBar.viewRoundReport"));
    private final JMenuItem gameEditBots = new JMenuItem(getString("CommonMenuBar.editBots"));
    /** Asks for the Game Master role; only shown while nobody holds it and the game allows one. */
    private final JMenuItem gameRequestGameMaster = new JMenuItem(getString("CommonMenuBar.gameRequestGameMaster"));
    /** Gives up the Game Master role; only shown while the local player holds it. */
    private final JMenuItem gameGiveUpGameMaster = new JMenuItem(getString("CommonMenuBar.gameGiveUpGameMaster"));

    /**
     * Whether the player at this screen holds the Game Master role, which decides whether the reinforcement entries
     * are theirs to use during a game. Kept because the menu is rebuilt on events that do not carry the role.
     */
    private boolean localPlayerHoldsGameMasterRole;
    /*
     * Lobby-only shortcuts that set MG burst fire and LRM hot-loading on every unit the player may configure at
     * once, sharing the labels of the same actions in the unit right-click menu. Shown only in the lobby and only
     * while the matching game option is on; setLobbyEquipmentOptions keeps them in step.
     */
    private final JMenuItem gameAllMgBurstOn = new JMenuItem(getString("ChatLounge.RapidFireToggleOn"));
    private final JMenuItem gameAllMgBurstOff = new JMenuItem(getString("ChatLounge.RapidFireToggleOff"));
    private final JMenuItem gameAllHotLoadOn = new JMenuItem(getString("ChatLounge.HotLoadToggleOn"));
    private final JMenuItem gameAllHotLoadOff = new JMenuItem(getString("ChatLounge.HotLoadToggleOff"));
    /** Whether the burst MG fire game option is on, which offers the all-units MG entries in the lobby. */
    private boolean burstMgAvailable = false;
    /** Whether the hot-loading game option is on, which offers the all-units LRM entries in the lobby. */
    private boolean hotLoadAvailable = false;
    private final JCheckBoxMenuItem gamePlayerList = new JCheckBoxMenuItem(getString("CommonMenuBar.viewPlayerList"));
    private final JCheckBoxMenuItem gameRoundsInAir =
          new JCheckBoxMenuItem(getString("CommonMenuBar.viewRoundsInAir"));
    private final JMenuItem gameGameOptions = new JMenuItem(getString("CommonMenuBar.viewGameOptions"));
    private final JMenuItem gamePlayerSettings = new JMenuItem(getString("CommonMenuBar.viewPlayerSettings"));
    private final JMenuItem gameNetworkInformation = new JMenuItem(getString("CommonMenuBar.viewNetworkInformation"));

    // The Units menu
    private final JMenuItem fileUnitsReinforce = new JMenuItem(getString("CommonMenuBar.fileUnitsReinforce"));
    private final JMenuItem fileUnitsReinforceRAT = new JMenuItem(getString("CommonMenuBar.fileUnitsReinforceRAT"));
    private final JMenuItem fileUnitsSave = new JMenuItem(getString("CommonMenuBar.fileUnitsSave"));
    // The Board menu
    private final JMenuItem boardSaveAs = new JMenuItem(getString("CommonMenuBar.fileBoardSaveAs"));
    private final JMenuItem boardSaveAsImage = new JMenuItem(getString("CommonMenuBar.fileBoardSaveAsImage"));
    private final JMenuItem boardSaveAsImageUnits = new JMenuItem(getString("CommonMenuBar.fileBoardSaveAsImageUnits"));

    // The View menu
    private final JCheckBoxMenuItem viewMinimap = new JCheckBoxMenuItem(getString("CommonMenuBar.viewMinimap"));
    private final JCheckBoxMenuItem viewMekDisplay = new JCheckBoxMenuItem(getString("CommonMenuBar.viewMekDisplay"));
    private final JCheckBoxMenuItem viewForceDisplay = new JCheckBoxMenuItem(getString("CommonMenuBar.viewForceDisplay"));
    private final JMenuItem viewNovaNetworks = new JMenuItem(getString("CommonMenuBar.viewNovaNetworks"));
    private final JMenu helpUiLibrary = new JMenu(getString("CommonMenuBar.helpUiLibrary"));
    private final JCheckBoxMenuItem viewKeybindsOverlay = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewKeyboardShortcuts"));
    private final JCheckBoxMenuItem viewPlanetaryConditionsOverlay = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewPlanetaryConditions"));
    private final JMenuItem viewZoomIn = new JMenuItem(getString("CommonMenuBar.viewZoomIn"));
    /**
     * The game board's graphics: Classic or Enhanced ({@link #rendererMenu}). It is enabled in every state: outside a
     * game's board (the lobby) a choice is saved for the next one.
     */
    private final JMenu viewRenderer = rendererMenu(this, false);
    private final JMenuItem viewZoomOut = new JMenuItem(getString("CommonMenuBar.viewZoomOut"));
    private final JMenuItem viewZoomReset = new JMenuItem(getString("CommonMenuBar.viewZoomReset"));
    private final JMenuItem viewZoomOverviewToggle = new JMenuItem(getString("CommonMenuBar.viewZoomOverviewToggle"));
    private final JMenuItem viewLabels = new JMenuItem(getString("CommonMenuBar.viewLabels"));
    // Bot Commands is a submenu offering three mutually exclusive display modes (Off / Float / Dock).
    private final JMenu viewBotCommands = new JMenu(getString("CommonMenuBar.viewBotCommands"));
    private final JRadioButtonMenuItem viewBotCommandsOff =
          new JRadioButtonMenuItem(getString("CommonMenuBar.viewBotCommandsOff"));
    private final JRadioButtonMenuItem viewBotCommandsFloat =
          new JRadioButtonMenuItem(getString("CommonMenuBar.viewBotCommandsFloat"));
    private final JRadioButtonMenuItem viewBotCommandsDock =
          new JRadioButtonMenuItem(getString("CommonMenuBar.viewBotCommandsDock"));
    private final JCheckBoxMenuItem toggleIsometric = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleIsometric"));
    private final JCheckBoxMenuItem toggleHexCoords = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleHexCoords"));
    private final JCheckBoxMenuItem toggleHexTypes = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleHexTypes"));
    private final JCheckBoxMenuItem toggleSensorRange = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleSensorRange"));
    private final JCheckBoxMenuItem toggleFieldOfFire = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleFieldOfFire"));
    private final JMenuItem toggleFleeZone = new JMenuItem(getString("CommonMenuBar.viewToggleFleeZone"));
    private final JCheckBoxMenuItem toggleFovHighlight = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleFovHighlight"));
    private final JCheckBoxMenuItem toggleFovDarken = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleFovDarken"));
    private final JCheckBoxMenuItem toggleFovSpotting = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleFovSpotting"));
    private final JCheckBoxMenuItem toggleShowObjects = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleShowObjects"));
    private final JCheckBoxMenuItem toggleFiringSolutions = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleFiringSolutions"));
    private final JCheckBoxMenuItem toggleUnitLinks = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleUnitLinks"));
    private final JCheckBoxMenuItem toggleCFWarning = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.viewToggleCFWarning"));
    private final JCheckBoxMenuItem viewMovementEnvelope = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.movementEnvelope"));
    private final JCheckBoxMenuItem viewTurnDetailsOverlay = new JCheckBoxMenuItem(getString(
          "CommonMenuBar.turnDetailsOverlay"));
    private final JMenuItem viewMovModEnvelope = new JMenuItem(getString("CommonMenuBar.movementModEnvelope"));
    private final JMenuItem viewLOSSetting = new JMenuItem(getString("CommonMenuBar.viewLOSSetting"));
    private final JCheckBoxMenuItem viewUnitOverview = new JCheckBoxMenuItem(getString("CommonMenuBar.viewUnitOverview"));
    private final JMenuItem viewClientSettings = new JMenuItem(getString("CommonMenuBar.viewClientSettings"));
    private final JMenuItem viewIncGUIScale = new JMenuItem(getString("CommonMenuBar.viewIncGUIScale"));
    private final JMenuItem viewDecGUIScale = new JMenuItem(getString("CommonMenuBar.viewDecGUIScale"));

    // The Firing Action menu
    private final JMenuItem fireSaveWeaponOrder = new JMenuItem(getString("CommonMenuBar.fireSaveWeaponOrder"));

    /**
     * Contains all ActionListeners that have registered themselves with this menu bar.
     */
    private final List<ActionListener> actionListeners = new ArrayList<>();

    /** Maps the Action Command to the respective MenuItem. */
    private final Map<String, JMenuItem> itemMap = new HashMap<>();

    /**
     * Tells this menu bar how to find the client whose game should be saved for a bug report.
     *
     * <p>Set this once the client exists. Without it, the bug report packager still works but produces an archive of
     * logs and system information only.</p>
     *
     * @param clientSupplier supplies the current client; may return {@code null} when no game is running
     */
    public void setClientSupplier(Supplier<Client> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    public static CommonMenuBar getMenuBarForGame() {
        var menuBar = new CommonMenuBar();
        menuBar.updateEnabledStates();
        return menuBar;
    }

    /** Creates the common MegaMek menu bar. */
    private CommonMenuBar() {
        // Create the Game menu
        JMenu menu = new JMenu(Messages.getString("CommonMenuBar.FileMenu"));
        menu.setMnemonic(VK_F);
        add(menu);
        initMenuItem(gameLoad, menu, FILE_GAME_LOAD, VK_L);
        initMenuItem(gameSave, menu, FILE_GAME_SAVE, VK_S);
        initMenuItem(gameQSave, menu, FILE_GAME_QUICK_SAVE);
        initMenuItem(gameQLoad, menu, FILE_GAME_QUICK_LOAD);
        initMenuItem(gameSaveServer, menu, FILE_GAME_SAVE_SERVER);

        // Create the Unit List sub-menu.
        menu = new JMenu(Messages.getString("CommonMenuBar.GameMenu"));
        add(menu);
        menu.setMnemonic(VK_G);

        initMenuItem(gameEditBots, menu, FILE_GAME_EDIT_BOTS, VK_R);
        initMenuItem(gameRequestGameMaster, menu, GAME_REQUEST_GAME_MASTER);
        initMenuItem(gameGiveUpGameMaster, menu, GAME_GIVE_UP_GAME_MASTER);
        // at most one of the two is shown, by who holds the role; setGameMasterState keeps them in step
        gameRequestGameMaster.setVisible(false);
        gameGiveUpGameMaster.setVisible(false);
        menu.addSeparator();

        initMenuItem(gameGameOptions, menu, VIEW_GAME_OPTIONS, VK_O);
        initMenuItem(gamePlayerSettings, menu, VIEW_PLAYER_SETTINGS);
        initMenuItem(gameNetworkInformation, menu, VIEW_NETWORK_INFORMATION);
        initMenuItem(fileUnitsReinforce, menu, FILE_UNITS_REINFORCE);
        initMenuItem(fileUnitsReinforceRAT, menu, FILE_UNITS_REINFORCE_RAT);
        initMenuItem(fileUnitsSave, menu, FILE_UNITS_SAVE);
        menu.addSeparator();

        initMenuItem(gameAllMgBurstOn, menu, GAME_ALL_MG_BURST_ON);
        initMenuItem(gameAllMgBurstOff, menu, GAME_ALL_MG_BURST_OFF);
        initMenuItem(gameAllHotLoadOn, menu, GAME_ALL_HOT_LOAD_ON);
        initMenuItem(gameAllHotLoadOff, menu, GAME_ALL_HOT_LOAD_OFF);
        menu.addSeparator();

        JMenuItem fileRefreshCache = new JMenuItem(getString("CommonMenuBar.fileUnitsRefreshUnitCache"));
        initMenuItem(fileRefreshCache, menu, FILE_REFRESH_CACHE);
        JMenuItem fileRebuildCache = new JMenuItem(getString("CommonMenuBar.fileUnitsRebuildUnitCache"));
        initMenuItem(fileRebuildCache, menu, FILE_REBUILD_CACHE);
        menu.addSeparator();

        initMenuItem(fireSaveWeaponOrder, menu, FIRE_SAVE_WEAPON_ORDER);

        // Create the Board sub-menu.
        menu = new JMenu(Messages.getString("CommonMenuBar.BoardMenu"));
        menu.setMnemonic(VK_B);
        add(menu);
        initMenuItem(boardSaveAs, menu, BOARD_SAVE_AS);
        menu.addSeparator();

        initMenuItem(boardSaveAsImage, menu, BOARD_SAVE_AS_IMAGE);
        boardSaveAsImage.setToolTipText(Messages.getString("CommonMenuBar.fileBoardSaveAsImage.tooltip"));
        initMenuItem(boardSaveAsImageUnits, menu, BOARD_SAVE_AS_IMAGE_UNITS);
        boardSaveAsImageUnits.setToolTipText(Messages.getString("CommonMenuBar.fileBoardSaveAsImageUnits.tooltip"));

        // Create the view menu.
        menu = new JMenu(Messages.getString("CommonMenuBar.ViewMenu"));
        menu.setMnemonic(VK_V);
        add(menu);
        menu.add(viewRenderer);
        updateRendererSelection();
        menu.addSeparator();
        initMenuItem(viewClientSettings, menu, VIEW_CLIENT_SETTINGS, VK_S);
        initMenuItem(viewIncGUIScale, menu, VIEW_INC_GUI_SCALE);
        initMenuItem(viewDecGUIScale, menu, VIEW_DEC_GUI_SCALE);
        menu.addSeparator();

        GUIP.setUnitDisplayEnabled(false);
        initMenuItem(viewMekDisplay, menu, VIEW_UNIT_DISPLAY, VK_D, GUIP.getUnitDisplayEnabled());
        GUIP.setMinimapEnabled(false);
        initMenuItem(viewMinimap, menu, VIEW_MINI_MAP, VK_M, GUIP.getMinimapEnabled());
        GUIP.setMiniReportEnabled(false);
        initMenuItem(gameRoundReport, menu, VIEW_ROUND_REPORT, GUIP.getMiniReportEnabled());
        GUIP.setPlayerListEnabled(false);
        initMenuItem(gamePlayerList, menu, VIEW_PLAYER_LIST, GUIP.getPlayerListEnabled());
        GUIP.setRoundsInAirEnabled(false);
        initMenuItem(gameRoundsInAir, menu, VIEW_ROUNDS_IN_AIR, GUIP.getRoundsInAirEnabled());
        GUIP.setForceDisplayEnabled(false);
        initMenuItem(viewForceDisplay, menu, VIEW_FORCE_DISPLAY, GUIP.getForceDisplayEnabled());
        initMenuItem(viewNovaNetworks, menu, VIEW_NOVA_NETWORKS);
        // Do not force the bot commands panel off at startup - its Off/Float/Dock choice is a persistent setting.
        initBotCommandsMenu(menu);
        menu.addSeparator();

        initMenuItem(viewKeybindsOverlay, menu, VIEW_KEYBINDS_OVERLAY, GUIP.getShowKeybindsOverlay());
        initMenuItem(viewPlanetaryConditionsOverlay,
              menu,
              VIEW_PLANETARY_CONDITIONS_OVERLAY,
              GUIP.getShowPlanetaryConditionsOverlay());
        initMenuItem(viewTurnDetailsOverlay, menu, VIEW_TURN_DETAILS_OVERLAY, GUIP.getTurnDetailsOverlay());
        initMenuItem(viewUnitOverview, menu, VIEW_UNIT_OVERVIEW, GUIP.getShowUnitOverview());
        menu.addSeparator();

        JMenuItem viewResetWindowPositions = new JMenuItem(getString("CommonMenuBar.viewResetWindowPos"));
        initMenuItem(viewResetWindowPositions, menu, VIEW_RESET_WINDOW_POSITIONS);
        menu.addSeparator();

        initMenuItem(viewZoomIn, menu, VIEW_ZOOM_IN);
        initMenuItem(viewZoomOut, menu, VIEW_ZOOM_OUT);
        initMenuItem(viewZoomReset, menu, VIEW_ZOOM_RESET);
        initMenuItem(viewZoomOverviewToggle, menu, VIEW_ZOOM_OVERVIEW_TOGGLE);
        initMenuItem(toggleIsometric, menu, VIEW_TOGGLE_ISOMETRIC, VK_T, GUIP.getIsometricEnabled());
        initMenuItem(toggleHexCoords, menu, VIEW_TOGGLE_HEX_COORDS, VK_G, GUIP.getCoordsEnabled());
        initMenuItem(toggleHexTypes, menu, VIEW_TOGGLE_HEX_TYPES, GUIP.getHexTypesEnabled());
        toggleHexTypes.setToolTipText(Messages.getString("CommonMenuBar.viewToggleHexTypesToolTip"));
        initMenuItem(viewLabels, menu, VIEW_LABELS);
        menu.addSeparator();

        initMenuItem(toggleFovDarken, menu, VIEW_TOGGLE_FOV_DARKEN, GUIP.getFovDarken());
        toggleFovDarken.setToolTipText(Messages.getString("CommonMenuBar.viewToggleFovDarkenTooltip"));
        initMenuItem(viewLOSSetting, menu, VIEW_LOS_SETTING);
        initMenuItem(toggleFovHighlight, menu, VIEW_TOGGLE_FOV_HIGHLIGHT, GUIP.getFovHighlight());
        initMenuItem(toggleFovSpotting, menu, VIEW_TOGGLE_FOV_SPOTTING, GUIP.getFovSpottingMode());
        toggleFovSpotting.setToolTipText(Messages.getString("CommonMenuBar.viewToggleFovSpottingTooltip"));
        initMenuItem(toggleShowObjects, menu, VIEW_TOGGLE_SHOW_OBJECTS, GUIP.getShowObjectiveOverlays());
        toggleShowObjects.setToolTipText(Messages.getString("CommonMenuBar.viewToggleShowObjectsTooltip"));
        initMenuItem(viewMovementEnvelope, menu, VIEW_MOVE_ENV, GUIP.getMoveEnvelope());
        initMenuItem(viewMovModEnvelope, menu, VIEW_MOVE_MOD_ENV);
        menu.addSeparator();

        initMenuItem(toggleSensorRange, menu, VIEW_TOGGLE_SENSOR_RANGE, GUIP.getShowSensorRange());
        toggleSensorRange.setToolTipText(Messages.getString("CommonMenuBar.viewToggleSensorRangeToolTip"));
        initMenuItem(toggleFieldOfFire, menu, VIEW_TOGGLE_FIELD_OF_FIRE, GUIP.getShowFieldOfFire());
        toggleFieldOfFire.setToolTipText(Messages.getString("CommonMenuBar.viewToggleFieldOfFireToolTip"));
        initMenuItem(toggleFleeZone, menu, VIEW_TOGGLE_FLEE_ZONE);
        toggleFleeZone.setToolTipText(Messages.getString("CommonMenuBar.viewToggleFleeZoneToolTip"));
        initMenuItem(toggleFiringSolutions, menu, VIEW_TOGGLE_FIRING_SOLUTIONS, GUIP.getShowFiringSolutions());
        toggleFiringSolutions.setToolTipText(Messages.getString("CommonMenuBar.viewToggleFiringSolutionsToolTip"));
        initMenuItem(toggleUnitLinks, menu, VIEW_TOGGLE_UNIT_LINKS, GUIP.getShowUnitLinks());
        toggleUnitLinks.setToolTipText(Messages.getString("CommonMenuBar.viewToggleUnitLinksToolTip"));

        initMenuItem(toggleCFWarning, menu, VIEW_TOGGLE_CF_WARNING, GUIP.getShowCFWarnings());
        toggleCFWarning.setToolTipText(Messages.getString("CommonMenuBar.viewToggleCFWarningToolTip"));

        // Create the Help menu
        menu = new JMenu(Messages.getString("CommonMenuBar.HelpMenu"));
        menu.setMnemonic(VK_H);
        add(menu);
        JMenuItem helpResetNags = new JMenuItem(getString("CommonMenuBar.helpResetNags"));
        initMenuItem(helpResetNags, menu, HELP_RESET_NAGS);
        // The Help menu
        JMenuItem helpContents = new JMenuItem(getString("CommonMenuBar.helpContents"));
        initMenuItem(helpContents, menu, HELP_CONTENTS);
        JMenuItem helpSkinning = new JMenuItem(getString("CommonMenuBar.helpSkinning"));
        initMenuItem(helpSkinning, menu, HELP_SKINNING);

        menu.addSeparator();

        // The client is resolved when the button is pressed, not now: this menu bar is built before the client exists.
        // A null client is a supported state and yields a logs-only archive.
        // This must stay a lambda rather than the method reference clientSupplier::get, which would capture the
        // placeholder supplier installed above and ignore whatever setClientSupplier later provides.
        // Over a native battle window the item shows the native Report a Bug instead.
        ShowBugReportDialogAction reportBug = new ShowBugReportDialogAction(this, new CopySystemDataAction(),
              new PackageBugReportAction(this, () -> clientSupplier.get()));
        menu.add(new AbstractAction(String.valueOf(reportBug.getValue(Action.NAME))) {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!GpuBoardWindow.showBugReport(clientSupplier.get())) {
                    reportBug.actionPerformed(event);
                }
            }
        });
        menu.add(new CopySystemDataAction());

        menu.addSeparator();

        menu.add(new ReleaseNotesAction());
        JMenuItem helpAbout = new JMenuItem(getString("CommonMenuBar.helpAbout"));
        initMenuItem(helpAbout, menu, HELP_ABOUT);

        // Developer galleries of the design system; shown only in the lobby.
        JMenuItem swingLibrary = new JMenuItem(getString("CommonMenuBar.helpUiLibrarySwing"));
        swingLibrary.addActionListener(event -> SwingUiLibrary.showLibrary(SwingUtilities.getWindowAncestor(this)));
        helpUiLibrary.add(swingLibrary);
        JMenuItem nativeLibrary = new JMenuItem(getString("CommonMenuBar.helpUiLibraryNative"));
        nativeLibrary.addActionListener(
              event -> GpuUiLibraryWindow.showLibrary(SwingUtilities.getWindowAncestor(this)));
        helpUiLibrary.add(nativeLibrary);
        menu.add(helpUiLibrary);

        setKeyBinds();
        GUIP.addPreferenceChangeListener(this);
        KeyBindParser.addPreferenceChangeListener(this);
    }

    /** Sets/updates the accelerators from the KeyCommandBinds preferences. */
    private void setKeyBinds() {
        toggleSensorRange.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.SENSOR_RANGE));
        toggleFovSpotting.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.FOV_SPOTTING));
        toggleShowObjects.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.SHOW_OBJECTS));
        toggleFieldOfFire.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.FIELD_FIRE));
        toggleIsometric.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.TOGGLE_ISO));
        viewMovementEnvelope.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.MOVE_ENVELOPE));
        viewMinimap.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.MINIMAP));
        viewLabels.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.DRAW_LABELS));
        toggleHexCoords.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.HEX_COORDS));
        viewMovModEnvelope.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.MOD_ENVELOPE));
        viewKeybindsOverlay.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.KEY_BINDS));
        viewPlanetaryConditionsOverlay.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.PLANETARY_CONDITIONS));
        viewTurnDetailsOverlay.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.TURN_DETAILS));
        viewForceDisplay.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.FORCE_DISPLAY));
        viewMekDisplay.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.UNIT_DISPLAY));
        viewUnitOverview.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.UNIT_OVERVIEW));
        viewLOSSetting.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.LOS_SETTING));
        viewIncGUIScale.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.INC_GUI_SCALE));
        viewDecGUIScale.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.DEC_GUI_SCALE));
        viewClientSettings.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.CLIENT_SETTINGS));
        gameRoundReport.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.ROUND_REPORT));
        viewZoomIn.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.ZOOM_IN));
        viewZoomOut.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.ZOOM_OUT));
        viewZoomOverviewToggle.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.ZOOM_OVERVIEW_TOGGLE));
        gameQLoad.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.QUICK_LOAD));
        gameQSave.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.QUICK_SAVE));
        gameSave.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.LOCAL_SAVE));
        gameLoad.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.LOCAL_LOAD));
        gameEditBots.setAccelerator(KeyCommandBind.keyStroke(KeyCommandBind.REPLACE_PLAYER));
        // The bot commands show/hide hotkey is handled by the controller (ClientGUI), since a submenu cannot carry a
        // working menu accelerator.
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        // Changes that are independent of the current state of MM
        // BoardView and others may listen to PreferenceChanges to detect these
        switch (event.getActionCommand()) {
            case ClientGUI.VIEW_INC_GUI_SCALE -> changeGUIScale(true);
            case ClientGUI.VIEW_DEC_GUI_SCALE -> changeGUIScale(false);
            case ClientGUI.VIEW_PLANETARY_CONDITIONS_OVERLAY -> GUIP.togglePlanetaryConditionsOverlay();
            case VIEW_KEYBINDS_OVERLAY -> GUIP.toggleKeybindsOverlay();
            case VIEW_TURN_DETAILS_OVERLAY -> GUIP.setTurnDetailsOverlay(!GUIP.getTurnDetailsOverlay());
            case ClientGUI.VIEW_LABELS -> GUIP.setUnitLabelStyle(GUIP.getUnitLabelStyle().next());
            case VIEW_TOGGLE_HEX_TYPES -> GUIP.toggleHexTypes();
            case VIEW_UNIT_OVERVIEW -> GUIP.setShowUnitOverview(!GUIP.getShowUnitOverview());
            // A Renderer choice is saved here; the game's board follows the preference.
            case VIEW_GRAPHICS_CLASSIC, VIEW_GRAPHICS_ENHANCED ->
                  GUIP.setBoardGraphics(VIEW_GRAPHICS_CLASSIC.equals(event.getActionCommand())
                        ? BoardGraphics.CLASSIC : BoardGraphics.ENHANCED);
            case ClientGUI.HELP_RESET_NAGS ->
                  MegaMek.getMMOptions().setNagDialogIgnore(MMConstants.NAG_BOT_README, false);
        }

        // Pass the action on to each of our listeners.
        // This is a source of ConcurrentModificationException errors if dialogs are
        // open during events
        // but closed after handling them; catching the CME does not lead to later
        // issues.
        try {
            actionListeners.forEach(l -> l.actionPerformed(event));
        } catch (ConcurrentModificationException e) {
            logger.warn(e, "Probable dialog open during Round Report handling");
            logger.info("Event causing this issue: " + event.getActionCommand());
        }
    }

    /** The same scale step and bounds for Swing menus and standalone native map workspaces. */
    public static void changeGUIScale(boolean increase) {
        float scale = GUIP.getGUIScale();
        if (increase ? scale < ClientGUI.MAX_GUI_SCALE : scale > ClientGUI.MIN_GUI_SCALE) {
            GUIP.setValue(GUIPreferences.GUI_SCALE, scale + (increase ? 0.1 : -0.1));
        }
    }

    /**
     * Register an object that wishes to be alerted when an item on this menu bar has been selected.
     * <p>
     * Please note, the ActionCommand property of the action event will inform the listener as to which menu item that
     * has been selected. Not all listeners will be interested in all menu items.
     *
     * @param listener - the <code>ActionListener</code> that wants to register itself.
     */
    public void addActionListener(ActionListener listener) {
        actionListeners.add(listener);
    }

    /**
     * Remove an object that was being alerted when an item on this menu bar was selected.
     *
     * @param listener - the <code>ActionListener</code> that wants to be removed.
     */
    public void removeActionListener(ActionListener listener) {
        actionListeners.remove(listener);
    }

    /**
     * Manages the enabled states of the menu items depending on the game's phase.
     */
    private synchronized void updateEnabledStates() {
        boolean isLobby = phase.isLounge();
        boolean isInGame = phase.isDuringOrAfter(GamePhase.DEPLOYMENT);
        boolean isInGameBoardView = isInGame && phase.isOnMap();
        boolean canSave = !phase.isUnknown()
              && !phase.isSelection()
              && !phase.isExchange()
              && !phase.isVictory()
              && !phase.isStartingScenario();
        boolean isNotVictory = !phase.isVictory();

        helpUiLibrary.setVisible(isLobby);

        gameQLoad.setEnabled(isLobby);
        gameLoad.setEnabled(isLobby);

        gameSave.setEnabled(isLobby || (isInGame && canSave));
        gameSaveServer.setEnabled(isLobby || (isInGame && canSave));
        gameQSave.setEnabled(isLobby || (isInGame && canSave));
        gameEditBots.setEnabled(isLobby || (isInGame && canSave));
        // the all-units equipment shortcuts belong to lobby setup, and only where their game option is on
        gameAllMgBurstOn.setVisible(isLobby && burstMgAvailable);
        gameAllMgBurstOff.setVisible(isLobby && burstMgAvailable);
        gameAllHotLoadOn.setVisible(isLobby && hotLoadAvailable);
        gameAllHotLoadOff.setVisible(isLobby && hotLoadAvailable);
        boardSaveAs.setEnabled(isInGame); // TODO: should work in the lobby
        boardSaveAsImage.setEnabled(isInGame); // TODO: should work in the lobby
        // reinforcing a player during a game is a gamemaster's job and lives on the Game Master menu; the same
        // dialog outside a game is how anybody builds an army, so the lobby keeps it
        fileUnitsReinforce.setEnabled(isInGame && isNotVictory && localPlayerHoldsGameMasterRole);
        fileUnitsReinforceRAT.setEnabled(isNotVictory
              && (isLobby || (isInGame && localPlayerHoldsGameMasterRole)));
        fileUnitsSave.setEnabled(isLobby || (isInGame && canSave));
        boardSaveAsImageUnits.setEnabled(isInGame);
        gamePlayerList.setEnabled(isInGame);
        gameRoundsInAir.setEnabled(isInGame);
        viewLabels.setEnabled(isInGameBoardView);

        gameGameOptions.setEnabled(isInGame || isLobby);
        gameNetworkInformation.setEnabled(isInGame || isLobby);
        gamePlayerSettings.setEnabled(isInGame);

        viewMinimap.setEnabled(isInGameBoardView);
        viewZoomIn.setEnabled(isInGameBoardView);
        viewZoomOut.setEnabled(isInGameBoardView);
        viewZoomReset.setEnabled(isInGameBoardView);
        viewZoomOverviewToggle.setEnabled(isInGameBoardView);
        toggleIsometric.setEnabled(isInGameBoardView);
        viewKeybindsOverlay.setEnabled(isInGameBoardView);
        viewPlanetaryConditionsOverlay.setEnabled(isInGameBoardView);
        toggleHexCoords.setEnabled(isInGameBoardView);
        toggleHexTypes.setEnabled(isInGameBoardView);

        viewLOSSetting.setEnabled(isInGameBoardView);
        viewUnitOverview.setEnabled(isInGameBoardView);
        toggleSensorRange.setEnabled(isInGameBoardView);
        toggleFieldOfFire.setEnabled(isInGameBoardView);
        toggleFleeZone.setEnabled(isInGameBoardView);
        toggleFovHighlight.setEnabled(isInGameBoardView);
        toggleFovDarken.setEnabled(isInGameBoardView);
        toggleFovSpotting.setEnabled(isInGameBoardView);
        toggleFiringSolutions.setEnabled(isInGameBoardView);
        toggleUnitLinks.setEnabled(isInGameBoardView);
        toggleCFWarning.setEnabled(isInGameBoardView);
        viewMovementEnvelope.setEnabled(isInGameBoardView);
        viewTurnDetailsOverlay.setEnabled(isInGameBoardView);
        viewMovModEnvelope.setEnabled(isInGameBoardView);
        gameRoundReport.setEnabled(isInGame);
        viewMekDisplay.setEnabled(isInGame);
        viewForceDisplay.setEnabled(isInGame);
        fireSaveWeaponOrder.setEnabled(isInGameBoardView);
        viewBotCommands.setEnabled(isInGame);
    }

    /**
     * Identify to the menu bar which phase is currently in progress
     *
     * @param current - the <code>int</code> value of the current phase (the valid values for this argument are defined
     *                as constants in the
     *                <code>Game</code> class).
     */
    public synchronized void setPhase(GamePhase current) {
        phase = current;
        updateEnabledStates();
    }

    public synchronized void setEnabled(String command, boolean enabled) {
        if (itemMap.containsKey(command)) {
            itemMap.get(command).setEnabled(enabled);
        }
    }

    /**
     * Shows the Game Master entry that applies: Give Up for the player holding the role, Become for anyone while
     * the role is free and the game allows one, and neither while another player holds it.
     */
    public synchronized void setGameMasterState(boolean localPlayerHoldsRole, boolean roleFreeToRequest) {
        localPlayerHoldsGameMasterRole = localPlayerHoldsRole;
        gameGiveUpGameMaster.setVisible(localPlayerHoldsRole);
        gameRequestGameMaster.setVisible(roleFreeToRequest);
        updateEnabledStates();
    }

    /**
     * Sets which of the lobby's all-units equipment shortcuts the game's options allow, offering the MG burst
     * entries under the burst fire option and the LRM hot-loading entries under the hot-loading option.
     */
    public synchronized void setLobbyEquipmentOptions(boolean burstMgAvailable, boolean hotLoadAvailable) {
        this.burstMgAvailable = burstMgAvailable;
        this.hotLoadAvailable = hotLoadAvailable;
        updateEnabledStates();
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent e) {
        // Adapt the menu checkboxes to a new state where necessary
        switch (e.getName()) {
            case GUIPreferences.USE_ISOMETRIC -> toggleIsometric.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.SHOW_FIELD_OF_FIRE -> toggleFieldOfFire.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.SHOW_SENSOR_RANGE -> toggleSensorRange.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.SHOW_UNIT_LINKS -> toggleUnitLinks.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.FOV_SPOTTING_MODE -> {
                // Use invokeLater to avoid interfering with accelerator processing
                final boolean newState = (Boolean) e.getNewValue();
                javax.swing.SwingUtilities.invokeLater(() -> toggleFovSpotting.setSelected(newState));
            }
            case GUIPreferences.SHOW_OBJECTIVE_OVERLAYS -> {
                final boolean newState = (Boolean) e.getNewValue();
                javax.swing.SwingUtilities.invokeLater(() -> toggleShowObjects.setSelected(newState));
            }
            case GUIPreferences.SHOW_KEYBINDS_OVERLAY -> viewKeybindsOverlay.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.SHOW_PLANETARY_CONDITIONS_OVERLAY ->
                  viewPlanetaryConditionsOverlay.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.TURN_DETAILS_OVERLAY -> viewTurnDetailsOverlay.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.SHOW_UNIT_OVERVIEW -> viewUnitOverview.setSelected((Boolean) e.getNewValue());
            case GUIPreferences.MINI_MAP_ENABLED -> viewMinimap.setSelected(GUIP.getMinimapEnabled());
            case GUIPreferences.SHOW_COORDS -> toggleHexCoords.setSelected(GUIP.getCoordsEnabled());
            case GUIPreferences.SHOW_HEX_TYPES -> toggleHexTypes.setSelected(GUIP.getHexTypesEnabled());
            case KeyBindParser.KEYBINDS_CHANGED -> setKeyBinds();
            case GUIPreferences.UNIT_DISPLAY_ENABLED -> viewMekDisplay.setSelected(GUIP.getUnitDisplayEnabled());
            case GUIPreferences.FORCE_DISPLAY_ENABLED -> viewForceDisplay.setSelected(GUIP.getForceDisplayEnabled());
            case GUIPreferences.MINI_REPORT_ENABLED -> gameRoundReport.setSelected(GUIP.getMiniReportEnabled());
            case GUIPreferences.PLAYER_LIST_ENABLED -> gamePlayerList.setSelected(GUIP.getPlayerListEnabled());
            case GUIPreferences.ROUNDS_IN_AIR_ENABLED -> gameRoundsInAir.setSelected(GUIP.getRoundsInAirEnabled());
            case GUIPreferences.BOT_COMMANDS_ENABLED, GUIPreferences.BOT_COMMANDS_LOCATION ->
                  updateBotCommandsSelection();
            case GUIPreferences.BOARD_GRAPHICS -> updateRendererSelection();
        }
    }

    /** Removes this as listener. */
    public void die() {
        GUIP.removePreferenceChangeListener(this);
        KeyBindParser.removePreferenceChangeListener(this);
    }

    private void initMenuItem(JMenuItem item, JMenu menu, String command) {
        item.addActionListener(this);
        item.setActionCommand(command);
        itemMap.put(command, item);
        menu.add(item);
    }

    private void initMenuItem(JMenuItem item, JMenu menu, String command, int mnemonic) {
        initMenuItem(item, menu, command);
        item.setMnemonic(mnemonic);
    }

    private void initMenuItem(JMenuItem item, JMenu menu, String command, boolean selected) {
        initMenuItem(item, menu, command);
        item.setSelected(selected);
    }

    private void initMenuItem(JMenuItem item, JMenu menu, String command, int mnemonic, boolean selected) {
        initMenuItem(item, menu, command);
        item.setMnemonic(mnemonic);
        item.setSelected(selected);
    }

    /**
     * Builds the Bot Commands submenu: three mutually exclusive radio items (Off / Float / Dock) grouped together, and
     * adds the submenu to the given parent View menu. The submenu replaces the former single checkbox so the player can
     * choose where the bot commands panel is shown without losing the on/off control.
     *
     * @param parentMenu the View menu the submenu is added to
     */
    private void initBotCommandsMenu(JMenu parentMenu) {
        viewBotCommands.setMnemonic(VK_G);
        initMenuItem(viewBotCommandsOff, viewBotCommands, ClientGUI.VIEW_BOT_COMMANDS_OFF);
        initMenuItem(viewBotCommandsFloat, viewBotCommands, ClientGUI.VIEW_BOT_COMMANDS_FLOAT);
        initMenuItem(viewBotCommandsDock, viewBotCommands, ClientGUI.VIEW_BOT_COMMANDS_DOCK);
        ButtonGroup botCommandsGroup = new ButtonGroup();
        botCommandsGroup.add(viewBotCommandsOff);
        botCommandsGroup.add(viewBotCommandsFloat);
        botCommandsGroup.add(viewBotCommandsDock);
        parentMenu.add(viewBotCommands);
        updateBotCommandsSelection();
    }

    /**
     * Selects the Bot Commands radio item that matches the current enabled and location preferences: Off when the panel
     * is disabled, otherwise Dock or Float depending on the configured location.
     */
    private void updateBotCommandsSelection() {
        if (!GUIP.getBotCommandsEnabled()) {
            viewBotCommandsOff.setSelected(true);
        } else if (GUIP.getBotCommandsLocation() == ClientGUI.BOT_COMMANDS_LOCATION_DOCKED) {
            viewBotCommandsDock.setSelected(true);
        } else {
            viewBotCommandsFloat.setSelected(true);
        }
    }

    /**
     * A Renderer submenu: Legacy 2D when {@code legacy2D} is set, then Classic and Enhanced, as one radio group whose
     * items carry the action commands {@link ClientGUI#VIEW_LEGACY_2D}, {@link ClientGUI#VIEW_GRAPHICS_CLASSIC} and
     * {@link ClientGUI#VIEW_GRAPHICS_ENHANCED}; {@code listener} runs a choice and {@link #selectRenderer} checks one.
     * The game's menu bar uses it without Legacy 2D, which only a map preview still offers.
     */
    public static JMenu rendererMenu(ActionListener listener, boolean legacy2D) {
        JMenu renderer = new JMenu(getString("CommonMenuBar.viewRenderer"));
        ButtonGroup group = new ButtonGroup();
        List<String> commands = legacy2D ? List.of(VIEW_LEGACY_2D, VIEW_GRAPHICS_CLASSIC, VIEW_GRAPHICS_ENHANCED)
              : List.of(VIEW_GRAPHICS_CLASSIC, VIEW_GRAPHICS_ENHANCED);
        for (String command : commands) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(getString("CommonMenuBar." + command));
            item.setActionCommand(command);
            item.addActionListener(listener);
            group.add(item);
            renderer.add(item);
        }
        return renderer;
    }

    /** Checks the item of a {@link #rendererMenu} whose action command is {@code command}. */
    public static void selectRenderer(JMenu renderer, String command) {
        for (Component item : renderer.getMenuComponents()) {
            if (item instanceof JRadioButtonMenuItem choice && command.equals(choice.getActionCommand())) {
                choice.setSelected(true);
            }
        }
    }

    /** Checks the graphics the preferences choose for the game's board. */
    private void updateRendererSelection() {
        selectRenderer(viewRenderer, GUIP.getBoardGraphics() == BoardGraphics.CLASSIC ? VIEW_GRAPHICS_CLASSIC
              : VIEW_GRAPHICS_ENHANCED);
    }
}
