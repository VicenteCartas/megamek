/*
 * Copyright (C) 2000-2005 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2002-2026 The MegaMek Team. All Rights Reserved.
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

import static megamek.common.compute.Compute.d6;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.MegaMek;
import megamek.SuiteConstants;
import megamek.client.Client;
import megamek.client.IClient;
import megamek.client.SBFClient;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.ui.BugReportMessages;
import megamek.client.ui.CopySystemDataAction;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuErrorPresenter;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow;
import megamek.client.ui.clientGUI.tooltip.PilotToolTip;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Palette;
import megamek.client.ui.design.swing.TokenMotion;
import megamek.client.ui.dialogs.LicensingDialog;
import megamek.client.ui.dialogs.MMAboutDialog;
import megamek.client.ui.dialogs.UnitLoadingDialog;
import megamek.client.ui.dialogs.buttonDialogs.BotConfigDialog;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.dialogs.helpDialogs.HelpDialog;
import megamek.client.ui.dialogs.helpDialogs.MMReadMeHelpDialog;
import megamek.client.ui.dialogs.randomArmy.AbstractRandomArmyDialog;
import megamek.client.ui.dialogs.randomArmy.MMMainMenuRandomArmyDialog;
import megamek.client.ui.dialogs.releaseNotes.ReleaseNotes;
import megamek.client.ui.dialogs.unitSelectorDialogs.MainMenuUnitBrowserDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.panels.skinEditor.SkinEditorMainGUIPanel;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.util.UIUtil;
import megamek.codeUtilities.StringUtility;
import megamek.common.Configuration;
import megamek.common.KeyBindParser;
import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.equipment.SensorChoiceHandler;
import megamek.common.game.GameType;
import megamek.common.game.IGame;
import megamek.common.jacksonAdapters.BotParser;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.scenario.Scenario;
import megamek.common.util.EmailService;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.common.weapons.handlers.WeaponOrderHandler;
import megamek.logging.MMLogger;
import megamek.server.IGameManager;
import megamek.server.Server;
import megamek.server.sbf.SBFGameManager;
import megamek.server.totalWarfare.TWGameManager;

public class MegaMekGUI implements IPreferenceChangeListener {
    private static final MMLogger LOGGER = MMLogger.create(MegaMekGUI.class);

    private static final String FILENAME_ICON_16X16 = "megamek-icon-16x16.png";
    private static final String FILENAME_ICON_32X32 = "megamek-icon-32x32.png";
    private static final String FILENAME_ICON_48X48 = "megamek-icon-48x48.png";
    private static final String FILENAME_ICON_256X256 = "megamek-icon-256x256.png";

    private JFrame frame;
    private IClient client;
    private Server server;
    private IGameManager gameManager;
    private CommonSettingsDialog settingsDialog;
    private AbstractRandomArmyDialog randomArmyDialog;

    private static MegaMekController controller;

    public void start(boolean show) {
        createGUI(show);
    }

    /**
     * Construct a MegaMek and, when {@code show}, open the start screen. The main frame itself stays hidden: it owns
     * the forms the start screen opens, and the start screen's native window adopts them.
     */
    private void createGUI(boolean show) {
        createController();

        GUIPreferences.getInstance().addPreferenceChangeListener(this);
        TokenMotion.setReduced(GUIPreferences.getInstance().getReduceMotion());
        applyPalette();

        // TODO : Move Theme setup to MegaMek::initializeSuiteSetups as part of implementing it in SuiteOptions
        updateGuiScaling();

        // TODO : Move ToolTip setup to MegaMek::initializeSuiteSetups as part of implementing them in SuiteOptions
        ToolTipManager.sharedInstance().setInitialDelay(GUIPreferences.getInstance().getTooltipDelay());
        if (GUIPreferences.getInstance().getTooltipDismissDelay() >= 0) {
            ToolTipManager.sharedInstance().setDismissDelay(GUIPreferences.getInstance().getTooltipDismissDelay());
        }

        frame = new JFrame("MegaMek");
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });

        List<Image> iconList = new ArrayList<>();
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_16X16).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_32X32).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_48X48).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_256X256).toString()));
        frame.setIconImages(iconList);

        installBugReportButtonOnErrorDialogs();
        GpuStartWindow.install(frame, this::perform, this::host, this::connect, this::load, this::scenario);
        // init the cache
        MekSummaryCache.getInstance();

        if (show) {
            GpuStartWindow.open(true);
            // Show licensing/welcome dialog once the greeting has played
            GpuStartWindow.whenSettled(() -> {
                if (!GpuStartWindow.showWelcome()) {
                    LicensingDialog.showIfNeeded(frame);
                }
            });
        }
        ReleaseNotes.rememberRunningVersion();
    }

    /**
     * Puts a "Report a Bug" button on every error dialog the program raises, including the one shown for an uncaught
     * exception.
     *
     * <p>A crash report is worth most at the moment it happens, while the player still knows what they were doing and
     * before the logs are overwritten by the next run. Offering the reporting tools on the dialog itself saves them
     * having to find the Help menu afterwards - and after an uncaught exception, the rest of the interface may not be
     * usable anyway.</p>
     *
     * <p>The current client is resolved when the button is pressed, so a crash during a game can still package the
     * game up, while a crash at the main menu quietly produces a logs-only archive.</p>
     */
    private void installBugReportButtonOnErrorDialogs() {
        MMLogger.setErrorDialogButton(new MMLogger.ErrorDialogButton(
              new BugReportMessages().get("package.reportBug"),
              Messages.getString("Okay"),
              () -> BugReportDialog.showWithGameTools(frame, this::currentTotalWarfareClient)));
        GpuErrorPresenter.install();
    }

    /**
     * @return the running client when it is a Total Warfare client the bug report packager can save, otherwise
     *       {@code null}; other client types have no save the packager understands
     */
    private @Nullable Client currentTotalWarfareClient() {
        return (client instanceof Client totalWarfareClient) ? totalWarfareClient : null;
    }

    public void createController() {
        KeyboardFocusManager keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        // Remove any previously registered controller first. Each call to this method (e.g. once per MekHQ scenario)
        // otherwise adds another dispatcher to the global KeyboardFocusManager that is never removed, leaking
        // dispatchers that keep catching key events for games that have already ended. See issue #8888.
        if (controller != null) {
            keyboardFocusManager.removeKeyEventDispatcher(controller);
        }
        controller = new MegaMekController();
        controller.megaMekGUI = this;
        keyboardFocusManager.addKeyEventDispatcher(controller);
        KeyBindParser.parseKeyBindings(controller);
    }

    public static MegaMekController getKeyDispatcher() {
        return controller;
    }

    /**
     * Display the board editor.
     */
    void showEditor() {
        megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.openEditor(frame, null, false);
    }

    void showSkinEditor() {
        int response = JOptionPane.showConfirmDialog(frame,
              "The skin editor is currently " +
                    "in beta and is a work in progress.  There are likely to " +
                    "be issues. \nContinue?",
              "Continue?",
              JOptionPane.OK_CANCEL_OPTION);
        if (response == JOptionPane.CANCEL_OPTION) {
            return;
        }
        SkinEditorMainGUIPanel skinEditor = new SkinEditorMainGUIPanel();
        skinEditor.initialize();
        launch(skinEditor.getFrame());
    }

    /**
     * Display the board editor and open an "open" dialog.
     */
    void showEditorOpen() {
        megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.openEditor(frame, null, true);
    }

    /** Starts the server and the client for the game the start screen's Host page describes. */
    private void host(GpuStartWindow.HostRequest request) {
        if (server != null) {
            // A second click queued behind the first; that game is already starting
            return;
        }
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        preferences.setLastPlayerName(request.playerName());
        preferences.setLastConnectPort(request.port());
        preferences.setLastServerPass(request.password());
        preferences.setValue("megamek.megamek.metaservername", request.metaserver());
        startHost(request.password(),
              request.port(),
              request.register(),
              request.register() ? request.metaserver() : "",
              null,
              null,
              request.playerName());
    }

    public void startHost(@Nullable String serverPassword, int port, boolean isRegister, @Nullable String metaServer,
          @Nullable String mailPropertiesFileName, @Nullable File saveGame, String playerName) {
        if (!startServer(serverPassword, port, isRegister, metaServer, mailPropertiesFileName, saveGame)) {
            return;
        }

        startClient(playerName, MMConstants.LOCALHOST, server.getPort());
        if (client == null) {
            // A game nobody could join; the next one may take its port
            server.die();
            server = null;
        }
    }

    public boolean startServer(@Nullable String serverPassword, int port, boolean isRegister,
          @Nullable String metaServer, @Nullable String mailPropertiesFileName, @Nullable File saveGameFile) {
        return startServer(serverPassword,
              port,
              isRegister,
              metaServer,
              mailPropertiesFileName,
              saveGameFile,
              GameType.TW);
    }

    public boolean startServer(@Nullable String serverPassword, int port, boolean isRegister,
          @Nullable String metaServer, @Nullable String mailPropertiesFileName, @Nullable File saveGameFile,
          GameType gameType) {
        try {
            serverPassword = Server.validatePassword(serverPassword);
            port = Server.validatePort(port);
        } catch (Exception ex) {
            LOGGER.error("Failed to start Server", ex);
            GpuStartWindow.reopen();
            return false;
        }

        EmailService mailer = null;
        if (!StringUtility.isNullOrBlank(mailPropertiesFileName)) {
            File propsFile = new File(mailPropertiesFileName);
            try (var propsReader = new FileReader(propsFile)) {
                var mailProperties = new Properties();
                mailProperties.load(propsReader);
                mailer = new EmailService(mailProperties);
            } catch (Exception ex) {
                LOGGER.errorDialog(ex,
                      Messages.getFormattedString("MegaMek.StartServerError", port, ex.getMessage()),
                      Messages.getString("MegaMek.LoadGameAlert.title"));
                return false;
            }
        }

        // kick off a RNG check
        d6();

        // start server
        try {
            gameManager = getGameManager(gameType);
            server = new Server(serverPassword, port, gameManager, isRegister, metaServer, mailer, false);
        } catch (Exception ex) {
            LOGGER.errorDialog(ex,
                  Messages.getFormattedString("MegaMek.StartServerError", port, ex.getMessage()),
                  Messages.getString("MegaMek.LoadGameAlert.title"));
            return false;
        }

        if (saveGameFile != null && !server.loadGame(saveGameFile)) {
            LOGGER.errorDialog(Messages.getString("MegaMek.LoadGameAlert.title"),
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message", saveGameFile.getAbsolutePath()));
            server.die();
            server = null;
            return false;
        }

        return true;
    }

    private IGameManager getGameManager(GameType gameType) {
        if (gameType == GameType.SBF) {
            return new SBFGameManager();
        }

        return new TWGameManager();
    }

    private IClientGUI getClientGUI(GameType gameType, IClient client, MegaMekController controller) {
        if (gameType == GameType.SBF) {
            return new SBFClientGUI((SBFClient) client, controller);
        }

        return new ClientGUI((Client) client, controller);
    }

    private IClient getClient(GameType gameType, String playerName, String host, int port) {
        if (gameType == GameType.SBF) {
            return new SBFClient(playerName, host, port);
        }

        return new Client(playerName, host, port);
    }

    public void startClient(String playerName, String serverAddress, int port) {
        startClient(playerName, serverAddress, port, GameType.TW);
    }

    public void startClient(String playerName, String serverAddress, int port, GameType gameType) {
        try {
            playerName = Server.validatePlayerName(playerName);
            serverAddress = Server.validateServerAddress(serverAddress);
            port = Server.validatePort(port);
        } catch (Exception ex) {
            LOGGER.errorDialog(ex,
                  Messages.getFormattedString("MegaMek.ServerConnectionError", serverAddress, port),
                  Messages.getString("MegaMek.LoadGameAlert.title"));
            return;
        }

        // delete PilotToolTip cache
        PilotToolTip.deleteImageCache();
        client = getClient(gameType, playerName, serverAddress, port);
        IClientGUI gui = getClientGUI(gameType, client, controller);
        controller.clientGUI = gui;
        frame.setCursor(new Cursor(Cursor.WAIT_CURSOR));
        gui.initialize();
        frame.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        if (!client.connect()) {
            LOGGER.errorDialog(Messages.getString("MegaMek.LoadGameAlert.title"),
                  Messages.getFormattedString("MegaMek.ServerConnectionError", serverAddress, port));
            client.die();
            gui.die();
            client = null;
            return;
        }

        // free some memory that's only needed in lounge This normally happens in the deployment phase in Client, but
        // if we are loading a game, this phase may not be reached
        MekFileParser.dispose();
        // We must do this last, as the name and unit generators can create a new instance if they are running
        MekSummaryCache.dispose();

        launch(gui.getFrame());
    }

    /** Hosts the saved game the start screen's Load game page describes, as the player it names. */
    private void load(GpuStartWindow.LoadRequest request) {
        if (server != null) {
            // A second click queued behind the first; that game is already starting
            return;
        }
        GpuStartWindow.HostRequest host = request.host();
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        preferences.setLastPlayerName(host.playerName());
        preferences.setLastConnectPort(host.port());
        preferences.setLastServerPass(host.password());
        preferences.setValue("megamek.megamek.metaservername", host.metaserver());
        startHost(host.password(),
              host.port(),
              host.register(),
              host.register() ? host.metaserver() : "",
              null,
              request.saveGame(),
              host.playerName());
        if (server == null || client == null) {
            return;
        }
        if (request.observe()) {
            for (Player player : server.getGame().getPlayersList()) {
                if (player.getName().equals(host.playerName())) {
                    player.setObserver(true);
                }
            }
        }
        rejoinBots(request.bots(), server.getPort());
    }

    /**
     * Has a Princess with her default behaviour take back each of a loaded save's {@code bots}: the server gives a
     * player who connects under a disconnected player's name that player's place.
     */
    private void rejoinBots(List<String> bots, int port) {
        for (String name : bots) {
            LOGGER.info("Bringing back the bot {}", name);
            BotClient botClient = BotFactory.createBot(AIType.PRINCESS, name, MMConstants.LOCALHOST, port);
            botClient.getGame().addGameListener(new BotGUI(frame, botClient));
            if (!botClient.connect()) {
                LOGGER.error("Bot {} failed to connect; shutting it down", name);
                botClient.die();
            } else if (controller.clientGUI instanceof ClientGUI clientGUI) {
                // As Edit Bots does: the host's client owns the bot, so it can be set up and readies with the host
                clientGUI.getLocalBots().put(name, botClient);
            }
        }
    }

    /**
     * Developer Utility: Loads "quicksave.sav.gz" with the last used connection settings.
     */
    public void quickLoadGame() {
        File file = MegaMek.getQuickSaveFile();
        if (!file.exists() || !file.canRead()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message", file.getAbsolutePath()),
                  Messages.getString("MegaMek.LoadGameAlert.title"),
                  JOptionPane.ERROR_MESSAGE);
            GpuStartWindow.reopen();
            return;
        }

        startHost("", 0, false, "", null, file, PreferenceManager.getClientPreferences().getLastPlayerName());
    }

    /**
     * Loads a specific save game file without showing the HostDialog. Used when loading from the lobby where server
     * settings are not needed.
     *
     * @param file The save game file to load
     */
    public void loadGameFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message",
                        file != null ? file.getAbsolutePath() : "null"),
                  Messages.getString("MegaMek.LoadGameAlert.title"),
                  JOptionPane.ERROR_MESSAGE);
            GpuStartWindow.reopen();
            return;
        }

        startHost("", 0, false, "", null, file, PreferenceManager.getClientPreferences().getLastPlayerName());
    }

    /**
     * Hosts the scenario the start screen's briefing describes: the scenario's open game options and planetary
     * conditions first, then the server, the host's client and the bots the briefing asked for.
     */
    private void scenario(GpuStartWindow.ScenarioRequest request) {
        if (server != null) {
            // A second click queued behind the first; that game is already starting
            return;
        }
        Scenario scenario = request.scenario();
        IGame game = request.game();
        PreferenceManager.getClientPreferences().setLastScenario(scenario.getFileName());

        Player[] players = game.getPlayersList().toArray(new Player[0]);

        Compute.d6();
        int port = request.port();
        if (!startServer(request.password(), port, false, null, null, null, scenario.getGameType())) {
            return;
        }
        server.setGame(game);
        scenario.applyDamage(gameManager);
        startClient(request.playerName(), MMConstants.LOCALHOST, port, scenario.getGameType());
        if (client == null) {
            // The server has the briefing's game now; the briefing makes it again before it starts another
            server.die();
            server = null;
            return;
        }
        gameManager.calculatePlayerInitialCounts();

        // Currently no bot supports anything other than TW
        if (scenario.getGameType() == GameType.TW) {
            for (int x = 0; x < players.length; x++) {
                if (request.roles().get(x) != GpuStartWindow.Role.BOT) {
                    continue;
                }
                AIType aiType = AIType.PRINCESS;
                BehaviorSettings scenarioBehavior = null;
                if (scenario.hasBotInfo(players[x].getName()) &&
                      scenario.getBotInfo(players[x].getName()) instanceof BotParser.PrincessRecord(
                            AIType recordAIType, BehaviorSettings behaviorSettings
                      )) {
                    aiType = recordAIType;
                    scenarioBehavior = behaviorSettings;
                }
                LOGGER.info("Adding bot {} as {}", players[x].getName(), aiType);
                BotClient botClient = BotFactory.createBot(aiType, players[x].getName(), MMConstants.LOCALHOST, port);
                if (scenarioBehavior != null) {
                    botClient.setBehaviorSettings(scenarioBehavior);
                }
                botClient.getGame().addGameListener(new BotGUI(frame, botClient));
                if (!botClient.connect()) {
                    LOGGER.error("Bot {} failed to connect; shutting it down", players[x].getName());
                    botClient.die();
                }
            }
        }

        // A host who takes none of the scenario's players watches
        if (!request.hasSlot()) {
            for (Player player : server.getGame().getPlayersList()) {
                if (player.getName().equals(request.playerName())) {
                    player.setObserver(true);
                }
            }
        }
    }

    /** Joins the game the start screen's Connect page describes, as the player or, after the bot dialog, a bot. */
    private void connect(GpuStartWindow.ConnectRequest request) {
        if (client != null) {
            // A second click queued behind the first; that client is already joining
            return;
        }
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        preferences.setLastPlayerName(request.playerName());
        preferences.setLastConnectAddr(request.serverAddress());
        preferences.setLastConnectPort(request.port());
        if (request.bot()) {
            connectBot(request.playerName(), request.serverAddress(), request.port());
        } else {
            startClient(request.playerName(), request.serverAddress(), request.port(),
                  request.sbf() ? GameType.SBF : GameType.TW);
        }
    }

    /** Sets up a bot in the bot dialog, starting from {@code botName}, and has it join the game. */
    private void connectBot(String botName, String serverAddress, int port) {
        var bcd = new BotConfigDialog(frame, botName);
        bcd.setVisible(true);
        if (bcd.getResult() == DialogResult.CANCELLED) {
            return;
        }
        client = BotFactory.createBot(bcd.getSelectedAIType(),
              bcd.getBotName(),
              serverAddress,
              port,
              bcd.getBehaviorSettings());
        client.getGame().addGameListener(new BotGUI(frame, (BotClient) client));
        ClientGUI gui = new ClientGUI((Client) client, controller);
        controller.clientGUI = gui;
        gui.initialize();
        if (!client.connect()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.ServerConnectionError", serverAddress, port),
                  Messages.getString("MegaMek.ConnectDialog.title"),
                  JOptionPane.ERROR_MESSAGE);
            client.die();
            gui.die();
            // The start screen stays open, so the player can correct the address and try again
            client = null;
            return;
        }
        launch(gui.getFrame());
    }

    private void showSkinningHowTo() {
        try {
            // Get the correct help file.
            StringBuilder helpPath = new StringBuilder("file:///");
            helpPath.append(System.getProperty("user.dir"));
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
        } catch (MalformedURLException e) {
            LOGGER.error(e);
        }
    }

    /**
     * Called when the user selects the "View->Client Settings" menu item.
     */
    void showSettings() {
        // Do we need to create the "settings" dialog?
        if (settingsDialog == null) {
            settingsDialog = new CommonSettingsDialog(frame);
        }

        // Show the settings dialog.
        settingsDialog.setVisible(true);
    }

    /**
     * Called when the quit buttons is pressed or the main menu is closed.
     */
    static void quit() {
        MegaMek.getMMPreferences().saveToFile(SuiteConstants.MM_PREFERENCES_FILE);
        PreferenceManager.getInstance().save();

        try {
            WeaponOrderHandler.saveWeaponOrderFile();
        } catch (IOException e) {
            LOGGER.error(e, "Error saving custom weapon orders!");
        }

        try {
            SensorChoiceHandler.saveSensorChoiceFile();
        } catch (IOException exception) {
            LOGGER.error(exception, "Error saving custom sensor choices!");
        }
        System.exit(0);
    }

    /**
     * Suspends the start screen while {@code launched} is open, then calls unlaunch() once it closes.
     */
    private void launch(JFrame launched) {
        // clean up disposable things
        if (randomArmyDialog != null) {
            randomArmyDialog.dispose();
            randomArmyDialog = null;
        }
        // listen to new frame
        GpuStartWindow.suspendWhile(launched, this::unlaunch);
        if (!launched.isDisplayable()) {
            // A frame closes with windowClosed only once it has a native peer; the native lobby and board keep the
            // client's frame hidden, so it may never have been shown
            launched.addNotify();
        }
        launched.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                GpuStartWindow.launchClosed(launched);
            }
        });
    }

    /**
     * Un-hides the main menu and tries to clean up the client or server.
     */
    void unlaunch() {
        // clean up server, if we have one
        if (server != null) {
            server.die();
            server = null;
        }
        // show the start screen again, without its greeting
        GpuStartWindow.resume();
        GpuStartWindow.reopen();

        // Check for post-unlaunch action (e.g., load game from lobby)
        Runnable postAction = controller.consumePostUnlaunchAction();
        if (postAction != null) {
            SwingUtilities.invokeLater(postAction);
        }

        // on Mac, override auto-added "Quit MM" behavior; in the main menu, it can simply quit
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler(null);
        }

        // just to free some memory
        client = null;
        System.gc();
    }

    /** Carries out what the player picked on the start screen. Runs on the event dispatch thread. */
    private void perform(GpuStartWindow.Action action) {
        switch (action) {
            case QUICK_LOAD -> quickLoadGame();
            case MAP_EDITOR -> showEditor();
            case OPEN_MAP -> showEditorOpen();
            case SKIN_EDITOR -> showSkinEditor();
            case UNIT_BROWSER -> {
                UnitLoadingDialog unitLoadingDialog = new UnitLoadingDialog(frame);
                if (!MekSummaryCache.getInstance().isInitialized()) {
                    unitLoadingDialog.setVisible(true);
                }
                MainMenuUnitBrowserDialog unitSelectorDialog = new MainMenuUnitBrowserDialog(frame, unitLoadingDialog);
                new Thread(unitSelectorDialog, "Mek Selector Dialog").start();
                unitSelectorDialog.setVisible(true);
            }
            case RANDOM_ARMY -> {
                if (randomArmyDialog == null) {
                    randomArmyDialog = new MMMainMenuRandomArmyDialog(frame);
                }
                randomArmyDialog.setVisible(true);
            }
            case SETTINGS -> showSettings();
            case HELP_CONTENTS -> {
                if (!GpuStartWindow.showHelp(Messages.getString("CommonMenuBar.HelpMenu"),
                      new File(MMConstants.MEGAMEK_README_FILE_PATH))) {
                    new MMReadMeHelpDialog(frame).setVisible(true);
                }
            }
            case HELP_SKINNING -> {
                if (!GpuStartWindow.showHelp(Messages.getString("ClientGUI.skinningHelpPath.title"),
                      new File(Messages.getString("ClientGUI.skinningHelpPath")))) {
                    showSkinningHowTo();
                }
            }
            case REPORT_BUG -> {
                if (!GpuStartWindow.showBugReport(this::currentTotalWarfareClient)) {
                    BugReportDialog.showWithGameTools(frame, this::currentTotalWarfareClient);
                }
            }
            case COPY_SYSTEM_DATA -> new CopySystemDataAction().actionPerformed(null);
            case RESET_PROMPTS -> MegaMek.getMMOptions().setNagDialogIgnore(MMConstants.NAG_BOT_README, false);
            case ABOUT -> {
                if (!GpuStartWindow.showAbout()) {
                    new MMAboutDialog(frame).show();
                }
            }
            case QUIT -> quit();
        }
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent evt) {
        switch (evt.getName()) {
            case GUIPreferences.UI_THEME:
                setLookAndFeel();
                break;
            case GUIPreferences.GUI_SCALE:
                updateGuiScaling();
                GpuStartWindow.refresh();
                break;
            case GUIPreferences.REDUCE_MOTION:
                TokenMotion.setReduced(GUIPreferences.getInstance().getReduceMotion());
                break;
            case GUIPreferences.COLOR_BLIND_PALETTE:
                applyPalette();
                setLookAndFeel();
                break;
        }
    }

    /** Sets the design tokens' palette from the color vision setting; the look and feel picks it up when set. */
    private static void applyPalette() {
        DesignTokens.setPalette(GUIPreferences.getInstance().getColorBlindPalette() ? Palette.COLORBLIND
              : Palette.STANDARD);
    }

    public static void updateGuiScaling() {
        System.setProperty("flatlaf.uiScale", Double.toString(GUIPreferences.getInstance().getGUIScale()));
        setLookAndFeel();
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(GUIPreferences.getInstance().getUITheme());
            UIUtil.updateAfterUiChange();
        } catch (Exception ex) {
            LOGGER.error("setLookAndFeel() Exception", ex);
        }
    }
}
