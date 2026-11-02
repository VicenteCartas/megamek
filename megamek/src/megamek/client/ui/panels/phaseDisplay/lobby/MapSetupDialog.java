/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static java.util.stream.Collectors.toList;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.drawMinimapLabel;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.extractSurpriseMaps;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.invalidBoardTip;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility.isBoardFile;
import static megamek.client.ui.util.UIUtil.scaleForGUI;
import static megamek.client.ui.util.UIUtil.setHighQualityRendering;

import java.awt.*;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.FilteredImageSource;
import java.awt.image.ImageFilter;
import java.awt.image.ImageProducer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.file.Path;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.JDialog;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MouseInputAdapter;
import javax.swing.filechooser.FileFilter;

import jakarta.annotation.Nullable;
import megamek.MegaMek;
import megamek.SuiteConstants;
import megamek.client.Client;
import megamek.client.lobby.LobbyCommands;
import megamek.client.ui.Messages;
import megamek.client.ui.buttons.DialogButton;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.IMapSettingsObserver;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.toolTip.TWBoardViewTooltip;
import megamek.client.ui.dialogs.InformDialog;
import megamek.client.ui.dialogs.MMDialogs.MMConfirmDialog;
import megamek.client.ui.dialogs.advancedSearchMap.AdvancedSearchMapDialog;
import megamek.client.ui.dialogs.clientDialogs.ClientDialog;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.client.ui.dialogs.randomMap.RandomMapDialog;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.*;
import megamek.client.ui.preferences.JWindowPreference;
import megamek.client.ui.util.ScalingPopup;
import megamek.client.ui.util.UIUtil;
import megamek.client.ui.util.UIUtil.FixedYPanel;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardFile;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.game.Game;
import megamek.common.internationalization.I18n;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MapSetup;
import megamek.common.util.BoardUtilities;
import megamek.common.util.CollectionUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;
import megamek.server.ServerBoardHelper;

/**
 * The lobby's map setup: map and board size, the boards and their choice (surprise and random among them), the
 * medium, the map generator, the 2D and 3D previews and saving and loading a setup, in a window of its own. The lobby
 * opens it through {@code LobbyDialogs}; there is at most one per {@link ClientGUI}.
 */
public final class MapSetupDialog extends JDialog implements IMapSettingsObserver {

    private static final MMLogger LOGGER = MMLogger.create(MapSetupDialog.class);
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final Map<ClientGUI, MapSetupDialog> OPEN = new HashMap<>();
    /** The board each client's 3D preview got from the lobby while its map setup was closed. */
    private static final Map<ClientGUI, Board> PREVIEWED = new HashMap<>();

    private final transient ClientGUI clientgui;
    private final transient LobbyCommands lobbyCommands;
    private final JPanel panMap = new JPanel();
    private final transient GameListener gameListener = new GameListenerAdapter() {
        @Override
        public void gameBoardNew(GameBoardNewEvent e) {
            // The server built (or discarded) the lobby battlefield; the event arrives on the network thread
            LOGGER.debug("[LobbyBoard] board {} received from the server ({}x{})", e.getBoardId(),
                  e.getNewBoard().getWidth(), e.getNewBoard().getHeight());
            SwingUtilities.invokeLater(MapSetupDialog.this::refreshBoardPreviews);
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent e) {
            SwingUtilities.invokeLater(() -> {
                if (isDisplayable()) {
                    refreshMapSizes();
                    updateMapSettings(client().getMapSettings());
                }
            });
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent e) {
            if (!e.getNewPhase().isLounge()) {
                SwingUtilities.invokeLater(MapSetupDialog.this::dispose);
            }
        }
    };

    private MapSetupDialog(ClientGUI clientGUI) {
        super(clientGUI.getFrame(), Messages.getString("ChatLounge.name.SelectMap"), false);
        this.clientgui = clientGUI;
        this.lobbyCommands = clientGUI.lobbyCommands();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setupMapPanel();
        for (JButton button : List.of(butBoardPreview2D, butBoardPreview3D, butGenerateBattlefield, butConditions,
              butRandomMap, butAddX, butAddY, butMapGrowW, butMapShrinkW, butMapGrowH, butMapShrinkH, butLoadMapSetup,
              butSaveMapSetup, butCancelSearch, butHelp, butAdvancedSearchMap)) {
            button.addActionListener(lobbyListener);
        }
        for (JTextField field : List.of(fldMapWidth, fldMapHeight, fldSpaceBoardWidth, fldSpaceBoardHeight)) {
            field.addFocusListener(lobbyFocusListener);
        }
        getContentPane().add(panMap, BorderLayout.CENTER);
        loader = new ImageLoader();
        loader.execute();
        lisBoardsAvailable.addMouseListener(mapListMouseListener);
        lisBoardsAvailable.addMouseMotionListener(mapListMouseListener);
        game().addGameListener(gameListener);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280, screen.width * 9 / 10), Math.min(860, screen.height * 9 / 10));
        setLocationRelativeTo(null);
    }

    /** EDT: opens the map setup of this client's lobby, or brings the open one to the front. */
    public static void open(ClientGUI clientGUI) {
        MapSetupDialog dialog = OPEN.get(clientGUI);
        if (dialog != null) {
            dialog.toFront();
            return;
        }
        dialog = new MapSetupDialog(clientGUI);
        OPEN.put(clientGUI, dialog);
        dialog.refreshMapUI();
        dialog.setVisible(true);
    }

    /** EDT: closes this client's map setup, if it is open. */
    public static void close(ClientGUI clientGUI) {
        MapSetupDialog dialog = OPEN.get(clientGUI);
        if (dialog != null) {
            dialog.dispose();
        }
    }

    /** EDT: the lobby ends; the map setup and its 2D and 3D previews close. */
    public static void closePreviews(ClientGUI clientGUI) {
        close(clientGUI);
        PREVIEWED.remove(clientGUI);
        GpuBoardWindow.closePreview(clientGUI.getFrame());
    }

    /** @return true when this client's map setup is open */
    public static boolean isOpen(ClientGUI clientGUI) {
        return OPEN.containsKey(clientGUI);
    }

    /**
     * EDT: the board editor opened from the lobby saved a board; while still in the lobby, the map settings take the
     * saved board, as the map setup's own Edit Board does.
     */
    public static void boardEditorClosed(ClientGUI clientGUI, Path savedFile, boolean savedAssembledPreview) {
        MapSetupDialog dialog = OPEN.get(clientGUI);
        if (dialog != null) {
            dialog.editorSaved(savedFile, savedAssembledPreview);
            return;
        }
        if ((savedFile == null) || !clientGUI.getClient().getGame().getPhase().isLounge()) {
            return;
        }
        try {
            MapSettings settings = settingsForSavedBoard(
                  MapSettings.getInstance(clientGUI.getClient().getMapSettings()), savedFile, savedAssembledPreview);
            clientGUI.getClient().sendMapSettings(settings);
        } catch (IOException ex) {
            LOGGER.error(ex, "Cannot read the saved board {}", savedFile);
        }
    }

    @Override
    public void dispose() {
        if (OPEN.get(clientgui) == this) {
            OPEN.remove(clientgui);
        }
        game().removeGameListener(gameListener);
        if (loader != null) {
            loader.cancel(true);
        }
        close2DPreview();
        super.dispose();
    }

    /** The 2D preview's own close (Esc, close button) leaves the 3D preview open. */
    private void close2DPreview() {
        if (previewRuler != null) {
            previewRuler.dispose();
            previewRuler = null;
        }
        if (previewBV != null) {
            previewBV.dispose();
            previewBV = null;
        }
        if (boardPreviewW != null) {
            boardPreviewW.dispose();
            boardPreviewW = null;
        }
    }

    ClientGUI getClientGUI() {
        return clientgui;
    }

    Game game() {
        return clientgui.getClient().getGame();
    }

    Client client() {
        return clientgui.getClient();
    }

    Player localPlayer() {
        return client().getLocalPlayer();
    }

    private static final int MAP_POPUP_OFFSET = -2;
    private final JToggleButton butGroundMap = new JToggleButton(Messages.getString("ChatLounge.butGroundMap"));
    private final JToggleButton butLowAtmosphereMap = new JToggleButton(Messages.getString(
          "ChatLounge.name.lowAltitudeMap"));
    private final JToggleButton butHighAtmosphereMap = new JToggleButton(Messages.getString(
          "ChatLounge.name.HighAltitudeMap"));
    private final JToggleButton butSpaceMap = new JToggleButton(Messages.getString("ChatLounge.name.spaceMap"));
    private final ButtonGroup grpMap = new ButtonGroup();
    private final JButton butAddY = new JButton(Messages.getString("ChatLounge.butAdd"));
    private final JButton butAddX = new JButton(Messages.getString("ChatLounge.butAdd"));
    private final JButton butSaveMapSetup = new JButton(Messages.getString("ChatLounge.map.saveMapSetup") + " *");
    private final JButton butLoadMapSetup = new JButton(Messages.getString("ChatLounge.map.loadMapSetup"));
    private transient ImageLoader loader;
    private final transient Map<String, Image> baseImages = new HashMap<>();
    private boolean boardEditorRefreshPending;
    private final transient MapListMouseAdapter mapListMouseListener = new MapListMouseAdapter();
    private final Map<String, String> boardTags = new HashMap<>();
    private static final String CL_KEY_FILE_EXTENSION_XML = ".xml";
    private static final String CL_KEY_FILEPATH_MAP_ASSEMBLY_HELP = "docs/Customization/MegaMek/Map and Board Stuff/MapAssemblyHelp.html";
    private static final String CL_KEY_FILEPATH_MAP_SETUP = "/mapsetup";
    private static final String CL_KEY_NAME_HELP_PANE = "helpPane";
    /* Map Settings Panel */
    private final JLabel lblMapWidth = new JLabel(Messages.getString("ChatLounge.labMapWidth"));
    private final JButton butMapGrowW = new JButton(Messages.getString("ChatLounge.butGrow"));
    private final JButton butMapShrinkW = new JButton(Messages.getString("ChatLounge.butShrink"));
    private final JTextField fldMapWidth = new JTextField(3);
    private final JLabel lblMapHeight = new JLabel(Messages.getString("ChatLounge.labMapHeight"));
    private final JButton butMapGrowH = new JButton(Messages.getString("ChatLounge.butGrow"));
    private final JButton butMapShrinkH = new JButton(Messages.getString("ChatLounge.butShrink"));
    private final JTextField fldMapHeight = new JTextField(3);
    private final FixedYPanel panMapHeight = new FixedYPanel();
    private final FixedYPanel panMapWidth = new FixedYPanel();

    private final JLabel lblSpaceBoardWidth = new JLabel(Messages.getString("ChatLounge.labBoardWidth"));
    private final JTextField fldSpaceBoardWidth = new JTextField(3);
    private final JLabel lblSpaceBoardHeight = new JLabel(Messages.getString("ChatLounge.labBoardHeight"));
    private final JTextField fldSpaceBoardHeight = new JTextField(3);
    private final FixedYPanel panSpaceBoardHeight = new FixedYPanel();
    private final FixedYPanel panSpaceBoardWidth = new FixedYPanel();

    private final JLabel lblBoardSize = new JLabel(Messages.getString("ChatLounge.labBoardSize"));
    private final JButton butHelp = new JButton(" " + Messages.getString("ChatLounge.butHelp") + " ");
    private final JButton butAdvancedSearchMap = new JButton(Messages.getString("AdvancedSearchMapDialog.title"));

    private final JButton butConditions = new JButton(Messages.getString("ChatLounge.butConditions"));
    private final JButton butRandomMap = new JButton(Messages.getString("BoardSelectionDialog.GeneratedMapSettings"));
    ArrayList<MapPreviewButton> mapButtons = new ArrayList<>(20);
    MapSettings mapSettings;
    private JPanel panGroundMap;

    private JComboBox<Comparable<?>> comMapSizes;
    private final JButton butBoardPreview2D = new JButton(Messages.getString("BoardSelectionDialog.ViewGameBoard2D"));
    private final JButton butBoardPreview3D = new JButton(Messages.getString("BoardSelectionDialog.ViewGameBoard3D"));
    private final JButton butGenerateBattlefield = new JButton(Messages.getString("ChatLounge.GenerateBattlefield"));
    private final JPanel panMapButtons = new JPanel();
    private final JLabel lblBoardsAvailable = new JLabel();
    private JList<String> lisBoardsAvailable;
    boolean resetAvailBoardSelection = false;
    boolean resetSelectedBoards = true;
    private ClientDialog boardPreviewW;
    private final Game boardPreviewGame = new Game();
    private transient BoardView previewBV;
    private transient RulerDialog previewRuler;
    Dimension currentMapButtonSize = new Dimension(0, 0);
    private final JCheckBox showPlayerDeployment = new JCheckBox(Messages.getString("ChatLounge.showPlayerDeployment"));

    private final ArrayList<String> invalidBoards = new ArrayList<>();
    private final ArrayList<String> serverBoards = new ArrayList<>();

    private JSplitPane splGroundMap;
    private final JLabel lblSearch = new JLabel(Messages.getString("ChatLounge.labSearch"));
    private final JTextField fldSearch = new JTextField(10);
    private final JButton butCancelSearch = new JButton(Messages.getString("ChatLounge.butCancelSearch"));

    FocusListener lobbyFocusListener = new FocusAdapter() {

        @Override
        public void focusLost(FocusEvent e) {
            if (e.getSource() == fldMapWidth) {
                setManualMapWidth();
            } else if (e.getSource() == fldMapHeight) {
                setManualMapHeight();
            } else if (e.getSource() == fldSpaceBoardWidth) {
                setManualBoardWidth();
            } else if (e.getSource() == fldSpaceBoardHeight) {
                setManualBoardHeight();
            }
        }
    };

    /** Shows the camo chooser and sets the selected camo. */

    private final transient ActionListener lobbyListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent ev) {
            if (ev.getSource() == butConditions) {
                PlanetaryConditionsDialog pcd = new PlanetaryConditionsDialog(clientgui);
                boolean userOkay = pcd.showDialog();
                if (userOkay) {
                    clientgui.getClient().sendPlanetaryConditions(pcd.getConditions());
                }

            } else if (ev.getSource() == butRandomMap) {
                RandomMapDialog rmd = new RandomMapDialog(clientgui.getFrame(),
                      MapSetupDialog.this,
                      clientgui.getClient(),
                      mapSettings);
                rmd.activateDialog(clientgui.getTilesetManager().getThemes());

            } else if (ev.getSource().equals(butBoardPreview2D)) {
                previewGameBoard();

            } else if (ev.getSource().equals(butBoardPreview3D)) {
                previewGameBoard3D();

            } else if (ev.getSource().equals(butGenerateBattlefield)) {
                LOGGER.debug("[LobbyBoard] requesting battlefield generation from the server");
                client().sendLobbyBoardGenerationRequest();

            } else if (ev.getSource().equals(comMapSizes)) {
                if (Messages.getString("ChatLounge.CustomMapSize").equals(comMapSizes.getSelectedItem())) {
                    refreshMapUI();
                } else if (comMapSizes.getSelectedItem() != null) {
                    BoardDimensions size = (BoardDimensions) comMapSizes.getSelectedItem();
                    resetAvailBoardSelection = true;
                    resetSelectedBoards = true;
                    lobbyCommands.resizeBoards(mapSettings, size.width(), size.height());
                }

            } else if (ev.getSource() == butGroundMap) {
                lobbyCommands.changeMedium(mapSettings, MapSettings.MEDIUM_GROUND);
                refreshMapUI();

            } else if (ev.getSource() == butSpaceMap) {
                lobbyCommands.changeMedium(mapSettings, MapSettings.MEDIUM_SPACE);
                refreshMapUI();

            } else if (ev.getSource() == butLowAtmosphereMap) {
                lobbyCommands.changeMedium(mapSettings, MapSettings.MEDIUM_ATMOSPHERE);
                refreshMapUI();

            } else if ((ev.getSource() == butAddX) || (ev.getSource() == butMapGrowW)) {
                lobbyCommands.resizeMap(mapSettings, mapSettings.getMapWidth() + 1, mapSettings.getMapHeight());
                nagAboutNonTileableBoards();

            } else if ((ev.getSource() == butAddY) || (ev.getSource() == butMapGrowH)) {
                lobbyCommands.resizeMap(mapSettings, mapSettings.getMapWidth(), mapSettings.getMapHeight() + 1);

            } else if (ev.getSource() == butSaveMapSetup) {
                saveMapSetup();

            } else if (ev.getSource() == butLoadMapSetup) {
                loadMapSetup();

            } else if (ev.getSource() == fldMapWidth) {
                setManualMapWidth();

            } else if (ev.getSource() == fldMapHeight) {
                setManualMapHeight();

            } else if (ev.getSource() == fldSpaceBoardWidth) {
                setManualBoardWidth();

            } else if (ev.getSource() == fldSpaceBoardHeight) {
                setManualBoardHeight();

            } else if (ev.getSource() == butMapShrinkW) {
                lobbyCommands.shrinkMapWidth(mapSettings);

            } else if (ev.getSource() == butMapShrinkH) {
                lobbyCommands.shrinkMapHeight(mapSettings);

            } else if (ev.getSource() == butCancelSearch) {
                fldSearch.setText("");

            } else if (ev.getSource() == butHelp) {
                File helpfile = new File(CL_KEY_FILEPATH_MAP_ASSEMBLY_HELP);
                final JDialog dialog = new ClientDialog(clientgui.getFrame(),
                      Messages.getString("ChatLounge.map.title.mapAssemblyHelp"),
                      true);
                final int height = 600;
                final int width = 600;

                final JEditorPane pane = new JEditorPane();
                pane.setName(CL_KEY_NAME_HELP_PANE);
                pane.setEditable(false);
                pane.setFont(UIUtil.getDefaultFont());
                try {
                    pane.setPage(helpfile.toURI().toURL());
                    JScrollPane tScroll = new JScrollPane(pane,
                          ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                          ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                    tScroll.getVerticalScrollBar().setUnitIncrement(16);
                    dialog.add(tScroll, BorderLayout.CENTER);
                } catch (Exception e) {
                    dialog.setTitle(Messages.getString("AbstractHelpDialog.noHelp.title"));
                    pane.setText(Messages.getString("AbstractHelpDialog.errorReading") + e.getMessage());
                    LOGGER.error(e, "");
                }

                JButton button = new DialogButton(Messages.getString("Okay"));
                button.addActionListener(e -> dialog.setVisible(false));
                JPanel okayPanel = new JPanel(new FlowLayout());
                okayPanel.add(button);
                dialog.add(okayPanel, BorderLayout.PAGE_END);

                Dimension sz = new Dimension(scaleForGUI(width), scaleForGUI(height));
                dialog.setPreferredSize(sz);
                dialog.pack();
                dialog.setVisible(true);

            } else if (ev.getSource() == butAdvancedSearchMap) {
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                AdvancedSearchMapDialog advancedSearchMapDialog = new AdvancedSearchMapDialog(clientgui.getFrame());
                setCursor(Cursor.getDefaultCursor());

                if (advancedSearchMapDialog.showDialog().isConfirmed()) {
                    String path = advancedSearchMapDialog.getPath();
                    // Only its size, from the header: a board with rules errors is marked invalid on its map button.
                    BoardDimensions size = path == null ? null
                          : Board.getSize(new MegaMekFile(Configuration.boardsDir(), path).getFile());
                    if (size != null) {
                        String boardName = megamek.common.board.BoardFile.selectionName(path);
                        boardName = boardName.replace("\\", "/");
                        lobbyCommands.selectSingleBoard(mapSettings, boardName, size);
                        refreshMapUI();
                        refreshBoardPreviews();
                    }
                }

            }
        }
    };

    private void setupMapPanel() {
        mapSettings = MapSettings.getInstance(clientgui.getClient().getMapSettings());
        setupMapAssembly();
        refreshMapUI();

        panMap.setLayout(new BoxLayout(panMap, BoxLayout.PAGE_AXIS));

        // Ground, Atmosphere, Space Map Buttons
        FixedYPanel panMapType = new FixedYPanel();
        panMapType.setAlignmentX(Component.CENTER_ALIGNMENT);
        panMapType.add(butGroundMap);
        panMapType.add(butLowAtmosphereMap);
        panMapType.add(butSpaceMap);
        grpMap.add(butGroundMap);
        grpMap.add(butLowAtmosphereMap);
        grpMap.add(butHighAtmosphereMap);
        grpMap.add(butSpaceMap);

        // Planetary Conditions and Random Map Settings buttons
        FixedYPanel panSettings = new FixedYPanel();
        panSettings.setAlignmentX(Component.CENTER_ALIGNMENT);
        panSettings.add(butConditions);
        panSettings.add(butRandomMap);

        FixedYPanel panTopRows = new FixedYPanel();
        panTopRows.setLayout(new BoxLayout(panTopRows, BoxLayout.PAGE_AXIS));
        panTopRows.add(panMapType);
        panTopRows.add(panSettings);

        JPanel panHelp = new JPanel(new GridLayout(1, 1));
        panHelp.add(butHelp);
        panHelp.add(butAdvancedSearchMap);

        FixedYPanel panTopRowsHelp = new FixedYPanel(new FlowLayout(FlowLayout.CENTER, 30, 5));
        panTopRowsHelp.add(panTopRows);
        panTopRowsHelp.add(panHelp);
        panMap.add(panTopRowsHelp);

        // Main part: Map Assembly
        panMap.add(panGroundMap);

    }

    /**
     * Sets up the ground map selection panel
     */
    private void setupMapAssembly() {
        panGroundMap = new JPanel(new GridLayout(1, 1));
        panGroundMap.setBorder(new EmptyBorder(20, 10, 10, 10));

        panMapButtons.setLayout(new BoxLayout(panMapButtons, BoxLayout.PAGE_AXIS));
        // Resize the preview buttons when the panel is resized
        panMapButtons.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateMapButtons();
            }
        });

        panMapWidth.add(lblMapWidth);
        panMapWidth.add(butMapShrinkW);
        panMapWidth.add(fldMapWidth);
        panMapWidth.add(butMapGrowW);

        panMapHeight.add(lblMapHeight);
        panMapHeight.add(butMapShrinkH);
        panMapHeight.add(fldMapHeight);
        panMapHeight.add(butMapGrowH);

        panSpaceBoardWidth.add(lblSpaceBoardWidth);
        panSpaceBoardWidth.add(fldSpaceBoardWidth);
        panSpaceBoardWidth.setVisible(false);

        panSpaceBoardHeight.add(lblSpaceBoardHeight);
        panSpaceBoardHeight.add(fldSpaceBoardHeight);
        panSpaceBoardHeight.setVisible(false);

        FixedYPanel bottomPanel = new FixedYPanel();
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        bottomPanel.add(butGenerateBattlefield);
        bottomPanel.add(butBoardPreview2D);
        bottomPanel.add(butBoardPreview3D);
        bottomPanel.add(butSaveMapSetup);
        bottomPanel.add(butLoadMapSetup);

        butBoardPreview2D.setToolTipText(Messages.getString("BoardSelectionDialog.ViewGameBoardTooltip"));
        butBoardPreview3D.setToolTipText(Messages.getString("BoardSelectionDialog.ViewGameBoardTooltip"));
        butGenerateBattlefield.setToolTipText(Messages.getString("ChatLounge.GenerateBattlefieldTooltip"));

        // The left side panel including the game map preview
        JPanel panMapPreview = new JPanel();
        panMapPreview.setLayout(new BoxLayout(panMapPreview, BoxLayout.PAGE_AXIS));

        panMapPreview.add(panMapWidth);
        panMapPreview.add(panMapHeight);
        panMapPreview.add(panSpaceBoardWidth);
        panMapPreview.add(panSpaceBoardHeight);
        panMapPreview.add(panMapButtons);
        panMapPreview.add(bottomPanel);

        // The right side panel including the list of available boards
        comMapSizes = new JComboBox<>();
        refreshMapSizes();

        lisBoardsAvailable = new JList<>(new DefaultListModel<>());
        lisBoardsAvailable.setCellRenderer(new BoardNameRenderer());
        lisBoardsAvailable.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        lisBoardsAvailable.setVisibleRowCount(-1);
        lisBoardsAvailable.setDragEnabled(true);
        lisBoardsAvailable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scrBoardsAvailable = new JScrollPane(lisBoardsAvailable);
        refreshBoardsAvailable();

        JPanel panAvail = new JPanel();
        panAvail.setLayout(new BoxLayout(panAvail, BoxLayout.PAGE_AXIS));
        panAvail.setBorder(new EmptyBorder(0, 20, 0, 0));
        panAvail.add(setupAvailTopPanel());
        panAvail.add(scrBoardsAvailable);

        // The split pane holding the left and right side panels
        splGroundMap = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panMapPreview, panAvail);
        splGroundMap.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                splGroundMap.setDividerLocation(getDividerLocation());
            }

            @Override
            public void componentShown(ComponentEvent e) {
                splGroundMap.setDividerLocation(getDividerLocation());
            }
        });
        panGroundMap.add(splGroundMap);

        showPlayerDeployment.setSelected(true);
        showPlayerDeployment.addActionListener(e -> previewGameBoard());
        refreshMapButtons();
    }

    /**
     * Sets up and returns the panel above the available boards list containing the search bar and the map size
     * chooser.
     */
    private JPanel setupAvailTopPanel() {
        FixedYPanel result = new FixedYPanel(new FlowLayout(FlowLayout.CENTER, 20, 2));
        result.setBorder(new EmptyBorder(5, 5, 5, 5));

        fldSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearch(fldSearch.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearch(fldSearch.getText());
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearch(fldSearch.getText());
            }
        });

        result.add(lblBoardSize);
        result.add(comMapSizes);
        result.add(new JLabel("    "));
        result.add(lblSearch);
        result.add(fldSearch);
        result.add(butCancelSearch);

        return result;
    }

    /**
     * Reacts to changes in the available boards search field, showing matching boards for the search string when it has
     * at least 3 characters and reverting to all boards when the search string is empty.
     */
    private void updateSearch(String searchString) {
        if (searchString.isEmpty()) {
            refreshBoardsAvailable();
        } else if (searchString.length() > 2) {
            refreshBoardsAvailable(getSearchedItems(searchString));
        }
    }

    /**
     * Returns the available boards that match the given search string (path or file name contains the search string.)
     * The search string is split at ";" and search results for the tokens are ANDed.
     */
    protected List<String> getSearchedItems(String searchString) {
        String lowerCaseSearchString = searchString.toLowerCase();
        String[] searchStrings = lowerCaseSearchString.split(";");
        java.util.List<String> result = mapSettings.getBoardsAvailableVector();
        for (String token : searchStrings) {
            java.util.List<String> byFilename = mapSettings.getBoardsAvailableVector()
                  .stream()
                  .filter(b -> b.toLowerCase().contains(token) && isBoardFile(b))
                  .collect(Collectors.toList());
            java.util.List<String> byTags = boardTags.entrySet()
                  .stream()
                  .filter(e -> e.getValue().contains(token))
                  .map(Map.Entry::getKey)
                  .collect(Collectors.toList());
            java.util.List<String> tokenResult = CollectionUtil.union(byFilename, byTags);
            result = result.stream().filter(tokenResult::contains).collect(toList());
        }
        return result;
    }

    /**
     * Returns a suitable divider location for the split pane that contains the available boards list and the map
     * preview. The divider location gives between 30% and 50% of space to the map preview depending on the width of the
     * game map.
     */
    private double getDividerLocation() {
        double base = 0.3;
        int width = mapSettings.getBoardWidth() * mapSettings.getMapWidth();
        int height = mapSettings.getBoardHeight() * mapSettings.getMapHeight();
        int wAspect = Math.max(1, width / height + 1);
        return Math.min(base + wAspect * 0.05, 0.5);
    }

    /** Updates the ground map type chooser (ground/atmosphere map). */
    private void refreshMapChoice() {
        // refresh UI possibly from a server update
        JToggleButton button = butGroundMap;
        if (mapSettings.getMedium() == MapSettings.MEDIUM_ATMOSPHERE) {
            button = butLowAtmosphereMap;
        } else if (mapSettings.getMedium() == MapSettings.MEDIUM_SPACE) {
            button = butSpaceMap;
        }

        if (!button.isSelected()) {
            button.removeActionListener(lobbyListener);
            button.setSelected(true);
            button.addActionListener(lobbyListener);
        }
    }

    /**
     * Refreshes the map size selection combo box with all available map sizes, sorted by width and then by height.
     *
     * <p>This method obtains the set of available map sizes from the client, sorts them first by width (ascending)
     * and then by height (ascending), and populates the combo box with these sorted sizes. An additional "Custom Map
     * Size" entry is added at the end of the list. The previously selected index is preserved if possible.</p>
     *
     * <p>The combo box's action listener is temporarily removed during the update to prevent unwanted event
     * firing.</p>
     */
    private void refreshMapSizes() {
        int oldSelection = comMapSizes.getSelectedIndex();
        Set<BoardDimensions> mapSizes = clientgui.getClient().getAvailableMapSizes();

        // Sort the map sizes by width (w()) and then by height (h())
        List<BoardDimensions> sortedSizes = new ArrayList<>(mapSizes);
        sortedSizes.sort(Comparator.comparingInt(BoardDimensions::w)
              .thenComparingInt(BoardDimensions::h));

        comMapSizes.removeActionListener(lobbyListener);
        comMapSizes.removeAllItems();

        for (BoardDimensions size : sortedSizes) {
            comMapSizes.addItem(size);
        }

        comMapSizes.addItem(Messages.getString("ChatLounge.CustomMapSize"));
        comMapSizes.setSelectedIndex(oldSelection != -1 ? oldSelection : 0);
        comMapSizes.addActionListener(lobbyListener);
    }

    /**
     * Refreshes the map assembly UI from the current map settings. Does NOT trigger further changes or result in
     * packets to the server.
     */
    private void refreshMapUI() {
        boolean inSpace = mapSettings.getMedium() == MapSettings.MEDIUM_SPACE;
        boolean onGround = mapSettings.getMedium() == MapSettings.MEDIUM_GROUND;
        boolean customSize = Messages.getString("ChatLounge.CustomMapSize").equals(comMapSizes.getSelectedItem());
        lisBoardsAvailable.setEnabled(!inSpace);
        mapIcons.clear();
        butConditions.setEnabled(!inSpace);
        fldSearch.setEnabled(!inSpace);
        butRandomMap.setEnabled(!inSpace);
        panMapHeight.setVisible(!inSpace);
        panMapWidth.setVisible(!inSpace);
        panSpaceBoardWidth.setVisible(inSpace || customSize);
        panSpaceBoardHeight.setVisible(inSpace || customSize);
        comMapSizes.setEnabled(!inSpace);
        lblSearch.setEnabled(!inSpace);
        lblBoardSize.setEnabled(!inSpace);
        butSaveMapSetup.setEnabled(!inSpace);
        butLoadMapSetup.setEnabled(!inSpace);
        butMapShrinkW.setEnabled(mapSettings.getMapWidth() > 1);
        butMapShrinkH.setEnabled(mapSettings.getMapHeight() > 1);
        refreshGenerateBattlefieldButton();
        butAdvancedSearchMap.setEnabled(!inSpace &&
              (mapSettings.getMapWidth() == 1) &&
              (mapSettings.getMapHeight() == 1));

        butGroundMap.removeActionListener(lobbyListener);
        butLowAtmosphereMap.removeActionListener(lobbyListener);
        butHighAtmosphereMap.removeActionListener(lobbyListener);
        butSpaceMap.removeActionListener(lobbyListener);
        if (onGround) {
            butGroundMap.setSelected(true);
        } else if (inSpace) {
            butSpaceMap.setSelected(true);
        } else {
            butLowAtmosphereMap.setSelected(true);
        }
        butGroundMap.addActionListener(lobbyListener);
        butLowAtmosphereMap.addActionListener(lobbyListener);
        butHighAtmosphereMap.addActionListener(lobbyListener);
        butSpaceMap.addActionListener(lobbyListener);

        fldMapWidth.removeActionListener(lobbyListener);
        fldMapHeight.removeActionListener(lobbyListener);
        fldSpaceBoardWidth.removeActionListener(lobbyListener);
        fldSpaceBoardHeight.removeActionListener(lobbyListener);
        fldMapWidth.setText(Integer.toString(mapSettings.getMapWidth()));
        fldMapHeight.setText(Integer.toString(mapSettings.getMapHeight()));
        fldSpaceBoardWidth.setText(Integer.toString(mapSettings.getBoardWidth()));
        fldSpaceBoardHeight.setText(Integer.toString(mapSettings.getBoardHeight()));
        fldMapWidth.addActionListener(lobbyListener);
        fldMapHeight.addActionListener(lobbyListener);
        fldSpaceBoardWidth.addActionListener(lobbyListener);
        fldSpaceBoardHeight.addActionListener(lobbyListener);
    }

    /**
     * Refreshes the list of available boards with all available boards plus GENERATED. Useful for first setup, when the
     * server transmits new map settings and when the text search field is empty.
     */
    private void refreshBoardsAvailable() {
        if (!lisBoardsAvailable.isEnabled()) {
            return;
        }
        lisBoardsAvailable.setFixedCellHeight(-1);
        lisBoardsAvailable.setFixedCellWidth(-1);
        java.util.List<String> availBoards = new ArrayList<>();
        availBoards.add(MapSettings.BOARD_GENERATED);
        availBoards.addAll(mapSettings.getBoardsAvailableVector());
        refreshBoardTags(); // is this necessary?
        refreshBoardsAvailable(availBoards);
    }

    private void refreshBoardTags() {
        boardTags.clear();
        for (String boardName : mapSettings.getBoardsAvailableVector()) {
            File boardFile = new MegaMekFile(Configuration.boardsDir(),
                  megamek.common.board.BoardFile.fileName(boardName)).getFile();
            Set<String> tags = Board.getTags(boardFile);
            boardTags.put(boardName, String.join("||", tags).toLowerCase());
        }
    }

    /**
     * Refreshes the list of available maps with the given list of boards.
     */
    private void refreshBoardsAvailable(java.util.List<String> boardList) {
        // Replace the data model (adding the elements one by one to the existing model
        // in Java 8 style is sluggish because of event firing)
        DefaultListModel<String> newModel = new DefaultListModel<>();
        for (String s : boardList) {
            newModel.addElement(s);
        }
        lisBoardsAvailable.setModel(newModel);
        lisBoardsAvailable.clearSelection();
    }

    public boolean isMultipleBoards() {
        return mapSettings.getMapHeight() * mapSettings.getMapWidth() > 1;
    }

    MapSettings oldMapSettings = MapSettings.getInstance();

    /**
     * Fills the Map Buttons scroll pane with the appropriate amount of buttons in the appropriate layout
     */
    private void refreshMapButtons() {
        panMapButtons.removeAll();
        panMapButtons.setVisible(false);
        panMapButtons.add(Box.createVerticalGlue());
        Dimension buttonSize = null;

        // If buttons are unused, remove their image so that they update when they're
        // used once more
        if (mapSettings.getMapHeight() * mapSettings.getMapWidth() < mapButtons.size()) {
            for (MapPreviewButton button : mapButtons.subList(mapSettings.getMapHeight() * mapSettings.getMapWidth(),
                  mapButtons.size())) {
                button.reset();
            }
        }

        // Add new map preview buttons if the map has grown
        while (mapSettings.getMapHeight() * mapSettings.getMapWidth() > mapButtons.size()) {
            mapButtons.add(new MapPreviewButton(this));
        }

        // Re-add the buttons to the panel and update them as necessary
        for (int i = 0; i < mapSettings.getMapHeight(); i++) {
            JPanel row = new FixedYPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            panMapButtons.add(row);
            for (int j = 0; j < mapSettings.getMapWidth(); j++) {
                int index = i * mapSettings.getMapWidth() + j;
                MapPreviewButton button = mapButtons.get(index);
                button.setIndex(index);
                row.add(button);

                // Update the board base image if it's generated and the settings have changed
                // or the board name has changed
                String boardName = mapSettings.getBoardsSelectedVector().get(index);
                if (boardName == null) {
                    continue;
                }
                if (!button.getBoard().equals(boardName) ||
                      !Objects.equals(mapSettings.getBoardTheme(index), oldMapSettings.getBoardTheme(index)) ||
                      mapSettings.getBoardLevelOffset(index) != oldMapSettings.getBoardLevelOffset(index) ||
                      oldMapSettings.getMedium() != mapSettings.getMedium() ||
                      (!mapSettings.equalMapGenParameters(oldMapSettings) &&
                            mapSettings.getMapWidth() == oldMapSettings.getMapWidth() &&
                            mapSettings.getMapHeight() == oldMapSettings.getMapHeight())) {
                    Board buttonBoard;
                    Image image;
                    // Generated and space boards use a generated example
                    if (boardName.startsWith(MapSettings.BOARD_GENERATED) ||
                          (mapSettings.getMedium() == MapSettings.MEDIUM_SPACE)) {
                        buttonBoard = BoardUtilities.generateRandom(mapSettings);
                        mapSettings.applyBoardOverrides(buttonBoard, index);
                        image = MinimapPanel.getMinimapImageMaxZoom(buttonBoard);
                    } else {
                        String boardForImage = boardName;
                        // For a surprise board, just use the first board as example
                        if (boardName.startsWith(MapSettings.BOARD_SURPRISE)) {
                            boardForImage = extractSurpriseMaps(boardName).getFirst();
                        }

                        boolean rotateBoard = false;
                        // for a rotation board, set a flag (when appropriate) and fix the name
                        if (boardForImage.startsWith(Board.BOARD_REQUEST_ROTATION)) {
                            // only rotate boards with an even width
                            if ((mapSettings.getBoardWidth() % 2) == 0) {
                                rotateBoard = true;
                            }

                            boardForImage = boardForImage.replace(Board.BOARD_REQUEST_ROTATION, "");
                        }

                        File boardFile = new MegaMekFile(Configuration.boardsDir(),
                              megamek.common.board.BoardFile.fileName(boardForImage)).getFile();
                        if (boardFile.exists()) {
                            buttonBoard = new Board(16, 17);
                            // Continues on rules errors, as the map list's thumbnails do (the strict reader refuses).
                            try (InputStream is = new FileInputStream(new MegaMekFile(Configuration.boardsDir(),
                                  megamek.common.board.BoardFile.fileName(boardForImage)).getFile())) {
                                buttonBoard.load(is, null, true);
                                BoardUtilities.flip(buttonBoard, rotateBoard, rotateBoard);
                                mapSettings.applyBoardOverrides(buttonBoard, index);
                            } catch (IOException ex) {
                                buttonBoard = Board.createEmptyBoard(mapSettings.getBoardWidth(),
                                      mapSettings.getBoardHeight());
                            }
                            image = MinimapPanel.getMinimapImageMaxZoom(buttonBoard);
                        } else {
                            buttonBoard = Board.createEmptyBoard(mapSettings.getBoardWidth(),
                                  mapSettings.getBoardHeight());
                            BufferedImage emptyBoardMap = MinimapPanel.getMinimapImageMaxZoom(buttonBoard);
                            markServerSideBoard(emptyBoardMap);
                            image = emptyBoardMap;
                        }
                    }
                    button.setImage(image, boardName);
                    buttonSize = optMapButtonSize(image);
                }
                button.scheduleRescale();
            }
        }
        oldMapSettings = MapSettings.getInstance(mapSettings);

        if (buttonSize != null) {
            for (MapPreviewButton button : mapButtons) {
                button.setPreviewSize(buttonSize);
            }
        }
        splGroundMap.setDividerLocation(getDividerLocation());

        panMapButtons.add(Box.createVerticalGlue());
        panMapButtons.setVisible(true);

        lblBoardsAvailable.setText(mapSettings.getBoardWidth() +
              "x" +
              mapSettings.getBoardHeight() +
              " " +
              Messages.getString("BoardSelectionDialog.mapsAvailable"));
        comMapSizes.removeActionListener(lobbyListener);
        int items = comMapSizes.getItemCount();

        boolean mapSizeSelected = false;
        for (int i = 0; i < (items - 1); i++) {
            BoardDimensions size = (BoardDimensions) comMapSizes.getItemAt(i);

            if ((size.width() == mapSettings.getBoardWidth()) && (size.height() == mapSettings.getBoardHeight())) {
                comMapSizes.setSelectedIndex(i);
                mapSizeSelected = true;
            }
        }
        // If we didn't select a size, select the last item: 'Custom Size'
        if (!mapSizeSelected) {
            comMapSizes.setSelectedIndex(items - 1);
        }
        comMapSizes.addActionListener(lobbyListener);

    }

    private void markServerSideBoard(BufferedImage image) {
        Graphics g = image.getGraphics();
        setHighQualityRendering(g);
        int w = image.getWidth();
        int h = image.getHeight();
        String text = Messages.getString("ChatLounge.board.serverSide");
        int fontSize = Math.min(w / 10, UIUtil.scaleForGUI(16));
        g.setFont(new Font(SuiteConstants.FONT_DIALOG, Font.ITALIC, fontSize));
        FontMetrics fm = g.getFontMetrics(g.getFont());
        int cx = (w - fm.stringWidth(text)) / 2;
        int cy = h / 10 + fm.getAscent();
        g.setColor(GUIP.getWarningColor());
        g.drawString(text, cx, cy);
        g.dispose();
    }

    /**
     * Enables the Generate Battlefield button only while it can actually be used, mirroring the server's checks:
     * building the board with a Surprise board selected would reveal the surprise, and an incomplete board
     * selection (unset slots right after a map size change) cannot be built. In both cases the server refuses
     * the request, so the button is disabled with an explanatory tooltip instead of allowing a silent no-op.
     */
    private void refreshGenerateBattlefieldButton() {
        // Board slots may be null right after a map size change, before the server fills them in
        boolean hasSurpriseBoard = mapSettings.getBoardsSelectedVector().stream()
              .anyMatch(boardName -> (boardName != null) && boardName.startsWith(MapSettings.BOARD_SURPRISE));
        boolean hasUnsetBoard = mapSettings.getBoardsSelectedVector().stream().anyMatch(Objects::isNull);
        butGenerateBattlefield.setEnabled(!hasSurpriseBoard && !hasUnsetBoard);

        String tooltipKey;
        if (hasSurpriseBoard) {
            tooltipKey = "ChatLounge.GenerateBattlefieldTooltipSurprise";
        } else if (hasUnsetBoard) {
            tooltipKey = "ChatLounge.GenerateBattlefieldTooltipIncomplete";
        } else {
            tooltipKey = "ChatLounge.GenerateBattlefieldTooltip";
        }
        butGenerateBattlefield.setToolTipText(Messages.getString(tooltipKey));
    }

    /**
     * @return the battlefield built by the server in the lobby, or {@code null} if none was built (or it was
     *       discarded after a settings change), in which case the preview falls back to a local roll
     */
    private @Nullable Board serverGeneratedBoard() {
        Board board = client().getGame().getBoard();
        boolean hasServerBoard = (board != null) && (board.getWidth() > 0) && (board.getHeight() > 0);
        return hasServerBoard ? board : null;
    }

    /** The Legacy 2D preview owns a renderer only after the user opens it. */
    private boolean initializeBoardPreview() {
        boardPreviewW = new ClientDialog(clientgui.getFrame(),
              Messages.getString("BoardSelectionDialog.ViewGameBoard"),
              false);
        boardPreviewW.setLocationRelativeTo(clientgui.getFrame());
        boardPreviewW.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        boardPreviewW.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close2DPreview(); }
        });

        try {
            boardPreviewGame.setPhase(GamePhase.LOUNGE);
            previewBV = new BoardView(boardPreviewGame, null, null, 0);
            previewBV.setDisplayInvalidFields(false);
            previewBV.setUseLosTool(false);
            previewBV.setTooltipProvider(new TWBoardViewTooltip(boardPreviewGame, clientgui, previewBV.getClientState()));

            JButton previewSaveAs = new JButton(Messages.getString("BoardSelectionDialog.ViewGameBoardSaveAs"));
            previewSaveAs.addActionListener(e -> clientgui.boardSaveAs(boardPreviewGame));
            JButton preview3D = new JButton(Messages.getString("GpuBoard.preview"));
            preview3D.addActionListener(e -> previewGameBoard3D());

            JPanel previewSettingsPanel = new JPanel(new FlowLayout());
            previewSettingsPanel.add(showPlayerDeployment);
            previewSettingsPanel.add(previewSaveAs);
            previewSettingsPanel.add(preview3D);

            Box previewPanel = Box.createVerticalBox();
            previewPanel.add(previewSettingsPanel);
            previewPanel.add(previewBV.getComponent(true));
            boardPreviewW.add(previewPanel);
            // The 3D preview's Menu: Classic or Enhanced opens the 3D preview of this board beside this one.
            boardPreviewW.setJMenuBar(GpuBoardWindow.previewMenu2D(boardPreviewW, previewBV, boardPreviewGame, null,
                  this::previewGameBoard3D));
            boardPreviewW.setSize(clientgui.getFrame().getWidth() / 2, clientgui.getFrame().getHeight() / 2);
            // remember the preview window's size and position between sessions, like the standard dialogs do
            // (the preferences are written when MegaMek exits normally)
            boardPreviewW.setName("BoardPreviewDialog");
            try {
                MegaMek.getMMPreferences().forClass(MapSetupDialog.class)
                      .manage(new JWindowPreference(boardPreviewW));
            } catch (Exception exception) {
                LOGGER.warn(exception, "Could not set up size/position memory for the board preview window");
            }

            String closeAction = "closeAction";
            final KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            boardPreviewW.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, closeAction);
            boardPreviewW.getRootPane().getInputMap(JComponent.WHEN_FOCUSED).put(escape, closeAction);
            boardPreviewW.getRootPane().getActionMap().put(closeAction, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { close2DPreview(); }
            });

            RulerDialog.color1 = GUIP.getRulerColor1();
            RulerDialog.color2 = GUIP.getRulerColor2();
            previewRuler = new RulerDialog(clientgui.getFrame(), previewBV.getClientState(), boardPreviewGame);

            // Most boards will be far too large on the standard zoom
            previewBV.zoomOut();
            previewBV.zoomOut();
            previewBV.zoomOut();
            previewBV.zoomOut();
            boardPreviewW.center();
            return true;
        } catch (IOException e) {
            close2DPreview();
            JOptionPane.showMessageDialog(this,
                  Messages.getString("BoardEditor.CouldNotInitialize") + e,
                  Messages.getString("BoardEditor.FatalError"),
                  JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private Board getBoardForPreview() {
        Board serverBoard = serverGeneratedBoard();
        if (serverBoard == null) {
            LOGGER.debug("[LobbyBoard] no server-built battlefield - preview uses a local roll of the map settings");
        }
        return (serverBoard != null)
              ? serverBoard
              : ServerBoardHelper.getPossibleGameBoard(mapSettings, false);
    }

    /**
     * Whether the 3D preview shows the lobby's preview board. The 2D and 3D previews show one board,
     * {@code boardPreviewGame}'s, so they can be compared side by side; the 3D preview belongs to the lobby window, not
     * to the 2D preview, so each closes on its own.
     */
    private boolean shows3DPreview() {
        return GpuBoardWindow.showsPreview(clientgui.getFrame(), boardPreviewGame.getBoard());
    }

    /**
     * The 2D preview. Opened beside the 3D preview, it shows the 3D preview's board; otherwise, and when it is open
     * already, a new roll of the map settings, which the open 3D preview shows too.
     */
    public void previewGameBoard() {
        if ((previewBV != null) || !shows3DPreview()) {
            rollPreviewBoard();
        }
        if ((previewBV == null) && !initializeBoardPreview()) {
            return;
        }
        previewBV.setLocalPlayer(client().getLocalPlayer());
        final var gOpts = game().getOptions();
        boardPreviewGame.setOptions(gOpts);
        previewBV.setShowLobbyPlayerDeployment(showPlayerDeployment.isSelected());

        for (Player player : boardPreviewGame.getPlayersList()) {
            boardPreviewGame.removePlayer(player.getId());
        }
        for (Player player : game().getPlayersList()) {
            boardPreviewGame.setPlayer(player.getId(), player.copy());
        }
        boardPreviewW.setVisible(true);
    }

    /**
     * EDT: the lobby's 3D preview: the open map setup's preview board, else the battlefield the server built, else a
     * new roll of the map settings.
     */
    public static void preview3D(ClientGUI clientGUI) {
        MapSetupDialog dialog = OPEN.get(clientGUI);
        if (dialog != null) {
            dialog.previewGameBoard3D();
            return;
        }
        Board board = lobbyBoard(clientGUI);
        PREVIEWED.put(clientGUI, board);
        GpuBoardWindow.openPreview(clientGUI.getFrame(), board, clientGUI.getClient().getGame().getPlanetaryConditions());
    }

    /**
     * EDT: a new server board or changed map settings while the map setup is closed; the 3D preview the lobby opened
     * shows the new board, as the map setup does when it is open.
     */
    public static void refreshClosedPreview(ClientGUI clientGUI) {
        // Conditions are independent of the map setup, including while its dialog is open.
        GpuBoardWindow.updatePreviewConditions(clientGUI.getFrame(), clientGUI.getClient().getGame().getPlanetaryConditions());
        Board shown = PREVIEWED.get(clientGUI);
        if ((shown == null) || OPEN.containsKey(clientGUI)) {
            return;
        }
        if (!GpuBoardWindow.showsPreview(clientGUI.getFrame(), shown)) {
            PREVIEWED.remove(clientGUI);
            return;
        }
        Board board = lobbyBoard(clientGUI);
        PREVIEWED.put(clientGUI, board);
        if (board != shown) {
            GpuBoardWindow.updatePreview(clientGUI.getFrame(), board);
        }
    }

    /** The server-built battlefield, or else a roll of the map settings. */
    private static Board lobbyBoard(ClientGUI clientGUI) {
        Board board = clientGUI.getClient().getGame().getBoard();
        if ((board == null) || (board.getWidth() <= 0) || (board.getHeight() <= 0)) {
            board = ServerBoardHelper.getPossibleGameBoard(clientGUI.getClient().getMapSettings(), false);
        }
        return board;
    }

    /**
     * EDT: the random map settings dialog; the open map setup shows the settings it makes.
     */
    public static void randomMap(ClientGUI clientGUI) {
        MapSetupDialog dialog = OPEN.get(clientGUI);
        IMapSettingsObserver observer = (dialog != null) ? dialog : settings -> { };
        MapSettings settings = (dialog != null) ? dialog.mapSettings
              : MapSettings.getInstance(clientGUI.getClient().getMapSettings());
        new RandomMapDialog(clientGUI.getFrame(), observer, clientGUI.getClient(), settings)
              .activateDialog(clientGUI.getTilesetManager().getThemes());
    }

    /** The 3D preview (the lobby's button and the 2D preview's): the open 2D preview's board, else a new roll. */
    private void previewGameBoard3D() {
        if (previewBV == null) {
            boardPreviewGame.setBoard(getBoardForPreview());
        }
        GpuBoardWindow.openPreview(clientgui.getFrame(), boardPreviewGame.getBoard(), game().getPlanetaryConditions());
    }

    /** Changed map settings or a new server board: a new roll in each open preview, opening none. */
    private void refreshBoardPreviews() {
        if ((boardPreviewW != null) && boardPreviewW.isVisible()) {
            previewGameBoard();
        } else if (shows3DPreview()) {
            rollPreviewBoard();
        }
    }

    /**
     * Reloads the lobby after a native editor saved a board and closed. The server rescans its boards and invalidates
     * its generated battlefield through the normal map-settings command; thumbnails must also forget their files.
     */
    void editorSaved(Path savedFile, boolean savedAssembledPreview) {
        if (savedFile == null || !game().getPhase().isLounge()) {
            return;
        }
        MapSettings settings = MapSettings.getInstance(mapSettings);
        try {
            settings = settingsForSavedBoard(settings, savedFile, savedAssembledPreview);
        } catch (IOException failure) {
            LOGGER.warn(failure, "Could not select the board saved by the 3D editor: {}", savedFile);
        }
        for (MapPreviewButton button : mapButtons) {
            button.reset();
        }
        mapIcons.clear();
        synchronized (baseImages) {
            if (loader != null) {
                loader.cancel(true);
            }
            loader = new ImageLoader();
            baseImages.clear();
            invalidBoards.clear();
            serverBoards.clear();
        }
        loader.execute();
        boardEditorRefreshPending = true;
        client().sendMapSettings(settings);
    }

    /** Only a saved source sheet keeps its placement; an assembled preview already includes its adjustments. */
    static MapSettings settingsForSavedBoard(MapSettings settings, Path savedFile, boolean savedAssembledPreview)
          throws IOException {
        Path saved = savedFile.toRealPath();
        for (File directory : List.of(Configuration.boardsDir(),
              new File(Configuration.userDataDir(), Configuration.boardsDir().toString()))) {
            if (!directory.isDirectory()) {
                continue;
            }
            Path root = directory.toPath().toRealPath();
            if (!saved.startsWith(root)) {
                continue;
            }
            String name = "/" + BoardFile.selectionName(root.relativize(saved).toString()).replace('\\', '/');
            Path resolved = new MegaMekFile(Configuration.boardsDir(), BoardFile.fileName(name)).getFile().toPath();
            if (!resolved.toRealPath().equals(saved)) {
                continue;
            }
            BoardDimensions size = BoardFile.metadata(saved).size();
            boolean selected = settings.getBoardsSelectedVector().stream().filter(Objects::nonNull)
                  .map(value -> value.startsWith(Board.BOARD_REQUEST_ROTATION)
                        ? value.substring(Board.BOARD_REQUEST_ROTATION.length()) : value)
                  .anyMatch(value -> value.equals(name) || ("/" + value).equals(name));
            if (savedAssembledPreview || !selected || !settings.getBoardSize().equals(size)) {
                settings.setMapSize(1, 1);
                settings.setBoardSize(size.width(), size.height());
                settings.setBoardsSelectedVector(List.of(name));
            }
            return settings;
        }
        return settings;
    }

    /** A new roll of the map settings; the open 3D preview shows it without coming to the front or taking the keys. */
    private void rollPreviewBoard() {
        boolean shows3D = shows3DPreview();
        boardPreviewGame.setBoard(getBoardForPreview());
        if (shows3D) {
            GpuBoardWindow.updatePreview(clientgui.getFrame(), boardPreviewGame.getBoard());
        }
    }
    /** Changes only this sheet's session settings; source board files are never written. */
    void changeMapTheme(int index, String theme) {
        lobbyCommands.changeBoardTheme(mapSettings, index, theme);
        refreshMapOverrides();
    }

    void changeMapLevelOffset(int index, int offset) {
        lobbyCommands.changeBoardLevelOffset(mapSettings, index, offset);
        refreshMapOverrides();
    }

    private void refreshMapOverrides() {
        refreshMapButtons();
        refreshBoardPreviews();
    }

    void changeMapDnD(String board, MapPreviewButton button) {
        lobbyCommands.changeBoard(mapSettings, mapButtons.indexOf(button), board);
        refreshBoardPreviews();
    }

    /**
     * Assigns the double-clicked entry of the available-boards list to the first board slot, as if it had been
     * dragged onto the map preview button. The map preview then shows the board through the usual refresh.
     *
     * @param listIndex the index of the double-clicked entry in the available-boards list
     */
    private void selectBoardFromList(int listIndex) {
        String boardName = lisBoardsAvailable.getModel().getElementAt(listIndex);
        LOGGER.debug("[LobbyBoard] double-click assigns {} to the first board slot", boardName);
        changeMapDnD(boardName, mapButtons.getFirst());
    }

    private void nagAboutNonTileableBoards() {
        boolean isBoardWidthOdd = mapSettings.getBoardWidth() % 2 != 0;
        boolean isMapSizeBiggerThanOne = mapSettings.getMapWidth() > 1;
        if (isBoardWidthOdd && isMapSizeBiggerThanOne && GUIP.getNagForOddSizedBoard()) {
            InformDialog nag = clientgui.doInformBotherDialog(I18n.getTextAt("megamek.client.messages",
                        "ChatLounge.board.warning.title"),
                  I18n.getTextAt("megamek.client.messages", "ChatLounge.board.warning.message"),
                  true);
            // do they want to be bothered again?
            if (!nag.getShowAgain()) {
                GUIP.setNagForOddSizedBoard(false);
            }
        }
    }

    /**
     * Opens a file chooser and saves the current map setup to the file, if any was chosen.
     *
     * @see MapSetup
     */
    private void saveMapSetup() {
        saveMapSetup(clientgui, mapSettings);
    }

    /** EDT: asks for a file and saves {@code mapSettings}' map setup to it. */
    public static void saveMapSetup(ClientGUI clientgui, MapSettings mapSettings) {
        JFileChooser fc = new JFileChooser(Configuration.dataDir() + CL_KEY_FILEPATH_MAP_SETUP);
        fc.setDialogTitle(Messages.getString("ChatLounge.map.saveMapSetup"));
        fc.setMultiSelectionEnabled(false);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(XMLFileFilter);

        int returnVal = fc.showSaveDialog(clientgui.getFrame());
        File selectedFile = fc.getSelectedFile();
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (selectedFile == null)) {
            return;
        }
        if (!selectedFile.getName().toLowerCase().endsWith(CL_KEY_FILE_EXTENSION_XML)) {
            selectedFile = new File(selectedFile.getPath() + CL_KEY_FILE_EXTENSION_XML);
        }
        if (selectedFile.exists()) {
            String msg = Messages.getString("ChatLounge.map.saveMapSetupReplace", selectedFile.getName());
            if (!MMConfirmDialog.confirm(clientgui.getFrame(),
                  Messages.getString("ChatLounge.map.confirmReplace"),
                  msg)) {
                return;
            }
        }
        try (OutputStream os = new FileOutputStream(selectedFile)) {
            MapSetup.save(os, mapSettings);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(clientgui.getFrame(),
                  Messages.getString("ChatLounge.map.problemSaving"),
                  Messages.getString("Error"),
                  JOptionPane.ERROR_MESSAGE);
            LOGGER.error(ex, "");
        }
    }

    /**
     * Opens a file chooser and loads a new map setup from the file, if any was chosen.
     *
     * @see MapSetup
     */
    private void loadMapSetup() {
        loadMapSetup(clientgui, mapSettings);
    }

    /** EDT: asks for a map setup file and applies it to {@code mapSettings}, sending them to the server. */
    public static void loadMapSetup(ClientGUI clientgui, MapSettings mapSettings) {
        LobbyCommands lobbyCommands = clientgui.lobbyCommands();
        JFileChooser fc = new JFileChooser(Configuration.dataDir() + CL_KEY_FILEPATH_MAP_SETUP);
        fc.setDialogTitle(Messages.getString("ChatLounge.map.loadMapSetup"));
        fc.setMultiSelectionEnabled(false);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileFilter(XMLFileFilter);

        int returnVal = fc.showOpenDialog(clientgui.getFrame());
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (fc.getSelectedFile() == null)) {
            return;
        }
        if (!fc.getSelectedFile().exists()) {
            JOptionPane.showMessageDialog(clientgui.getFrame(), Messages.getString("ChatLounge.fileNotFound"));
            return;
        }
        try (InputStream os = new FileInputStream(fc.getSelectedFile())) {
            MapSetup setup = MapSetup.load(os);
            lobbyCommands.applyMapSetup(mapSettings, setup.getMapWidth(), setup.getMapHeight(),
                  setup.getBoardWidth(), setup.getBoardHeight(), setup.getBoards());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(clientgui.getFrame(),
                  Messages.getString("ChatLounge.map.problemLoadMapSetup"),
                  Messages.getString("Error"),
                  JOptionPane.ERROR_MESSAGE);
            LOGGER.error(ex, "");
        }
    }

    // Put a filter on the files that the user can select the proper file.
    private static final FileFilter XMLFileFilter = new FileFilter() {
        @Override
        public boolean accept(File f) {
            return (f.getPath().toLowerCase().endsWith(CL_KEY_FILE_EXTENSION_XML) || f.isDirectory());
        }

        @Override
        public String getDescription() {
            return Messages.getString("ChatLounge.map.SetupXMLfiles");
        }
    };

    private void setManualMapWidth() {
        try {
            int newMapWidth = Integer.parseInt(fldMapWidth.getText());
            if (lobbyCommands.changeMapWidth(mapSettings, newMapWidth)) {
                nagAboutNonTileableBoards();
            }
        } catch (NumberFormatException e) {
            // no number, no new map width
        }
    }

    private void setManualMapHeight() {
        try {
            int newMapHeight = Integer.parseInt(fldMapHeight.getText());
            lobbyCommands.changeMapHeight(mapSettings, newMapHeight);
        } catch (NumberFormatException e) {
            // no number, no new map height
        }
    }

    private void setManualBoardWidth() {
        try {
            int newBoardWidth = Integer.parseInt(fldSpaceBoardWidth.getText());
            if (lobbyCommands.changeBoardWidth(mapSettings, newBoardWidth)) {
                nagAboutNonTileableBoards();
            }
        } catch (NumberFormatException e) {
            // no number, no new board width
        }
    }

    private void setManualBoardHeight() {
        try {
            int newBoardHeight = Integer.parseInt(fldSpaceBoardHeight.getText());
            lobbyCommands.changeBoardHeight(mapSettings, newBoardHeight);
        } catch (NumberFormatException e) {
            // no number, no new board height
        }
    }

    /**
     * Updates to show the map settings that have, presumably, just been sent by the server.
     */
    @Override
    public void updateMapSettings(MapSettings newSettings) {
        mapSettings = MapSettings.getInstance(newSettings);
        refreshMapButtons();
        refreshMapChoice();
        refreshMapUI();
        refreshBoardsAvailable();
        updateSearch(fldSearch.getText());
        if (boardEditorRefreshPending) {
            boardEditorRefreshPending = false;
            refreshBoardPreviews();
        }
    }

    public class MapListMouseAdapter extends MouseInputAdapter implements ActionListener {
        ScalingPopup popup;

        @Override
        public void actionPerformed(ActionEvent action) {
            String[] command = action.getActionCommand().split(":");

            switch (command[0]) {
                case MapListPopup.MLP_BOARD:
                    boolean rotate = (command.length > 3) && Boolean.parseBoolean(command[3]);
                    String rotateRequest = rotate ? Board.BOARD_REQUEST_ROTATION : "";

                    changeMapDnD(rotateRequest + command[2], mapButtons.get(Integer.parseInt(command[1])));
                    break;

                case MapListPopup.MLP_SURPRISE:
                    changeMapDnD(command[2], mapButtons.get(Integer.parseInt(command[1])));
                    break;
                default:
                    break;
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            maybeShowPopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeShowPopup(e);
        }

        /**
         * Shows the board selection popup on a right-click (checked on both press and release, as the popup
         * trigger is platform-dependent). Left clicks are left alone so that they select boards and can form
         * a double-click, which opens the board preview instead.
         */
        private void maybeShowPopup(MouseEvent e) {
            if (!e.isPopupTrigger() || !lisBoardsAvailable.isEnabled()) {
                return;
            }
            int index = lisBoardsAvailable.locationToIndex(e.getPoint());
            if ((index != -1) && lisBoardsAvailable.getCellBounds(index, index).contains(e.getPoint())) {
                if (!lisBoardsAvailable.isSelectedIndex(index)) {
                    lisBoardsAvailable.setSelectedIndex(index);
                }
                showPopup(e);
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            boolean isDoubleClick = SwingUtilities.isLeftMouseButton(e) && (e.getClickCount() == 2);
            if (!lisBoardsAvailable.isEnabled() || !isDoubleClick) {
                return;
            }
            int index = lisBoardsAvailable.locationToIndex(e.getPoint());
            if ((index != -1) && lisBoardsAvailable.getCellBounds(index, index).contains(e.getPoint())) {
                closePopup();
                selectBoardFromList(index);
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            if (popup == null) {
                return;
            }
            Point p = e.getLocationOnScreen();
            if (!popup.contains(p)) {
                closePopup();
            }
        }

        private void closePopup() {
            if (popup == null) {
                return;
            }
            popup.setVisible(false);
            popup = null;
        }

        /** Shows the map selection menu on the map table */
        private void showPopup(MouseEvent e) {
            if (lisBoardsAvailable.isSelectionEmpty()) {
                return;
            }
            java.util.List<String> boards = lisBoardsAvailable.getSelectedValuesList();
            int activeButtons = mapSettings.getMapWidth() * mapSettings.getMapHeight();
            boolean enableRotation = (mapSettings.getBoardWidth() % 2) == 0;
            popup = MapListPopup.mapListPopup(boards, activeButtons, this, MapSetupDialog.this, enableRotation);
            popup.show(e.getComponent(), e.getX() + MAP_POPUP_OFFSET, e.getY() + MAP_POPUP_OFFSET);
        }
    }

    private void redrawMapTable(Image image) {
        if (image != null) {
            if (lisBoardsAvailable.getFixedCellHeight() != image.getHeight(null) ||
                  lisBoardsAvailable.getFixedCellWidth() != image.getWidth(null)) {
                lisBoardsAvailable.setFixedCellHeight(image.getHeight(null));
                lisBoardsAvailable.setFixedCellWidth(image.getWidth(null));
            }
            lisBoardsAvailable.repaint();
        }
    }

    class ImageLoader extends SwingWorker<Void, Image> {

        private final BlockingQueue<String> boards = new LinkedBlockingQueue<>();

        private synchronized void add(String name) {
            if (!boards.contains(name)) {
                try {
                    boards.put(name);
                } catch (InterruptedException e) {
                    LOGGER.warn(e,
                          "[Thread=({}){}] Failed to load image {} for board, common on startup",
                          Thread.currentThread().getId(),
                          Thread.currentThread().getName(),
                          name);
                } catch (Exception e) {
                    LOGGER.error(e,
                          "[Thread=({}){}] Failed to load image {} for board",
                          Thread.currentThread().getId(),
                          Thread.currentThread().getName(),
                          name);
                }
            }
        }

        private Image prepareImage(String boardName) {
            File boardFile = new MegaMekFile(Configuration.boardsDir(),
                  megamek.common.board.BoardFile.fileName(boardName)).getFile();
            Board board;
            java.util.List<String> errors = new ArrayList<>();
            if (boardFile.exists()) {
                board = new Board();
                try (InputStream is = new FileInputStream(boardFile)) {
                    board.load(is, errors, true);
                } catch (IOException ex) {
                    board = Board.createEmptyBoard(mapSettings.getBoardWidth(), mapSettings.getBoardHeight());
                }
            } else {
                board = Board.createEmptyBoard(mapSettings.getBoardWidth(), mapSettings.getBoardHeight());
            }

            // Determine a minimap zoom from the board size and gui scale. This is very magic numbers but currently
            // the minimap has only fixed zoom states.
            int zoom = getZoom(board);
            BufferedImage bufImage = MinimapPanel.getMinimapImage(board, zoom);

            // Add the board name label and the server-side board label if necessary
            String text = LobbyUtility.cleanBoardName(boardName, mapSettings);
            Graphics g = bufImage.getGraphics();
            drawMinimapLabel(text, bufImage.getWidth(), bufImage.getHeight(), g, !errors.isEmpty());
            if (!boardFile.exists() && !boardName.startsWith(MapSettings.BOARD_GENERATED)) {
                markServerSideBoard(bufImage);
            }
            g.dispose();

            synchronized (baseImages) {
                // A save can replace this loader while the old file is still being read or rendered.
                if (this != loader || isCancelled()) {
                    return null;
                }
                if (!errors.isEmpty()) {
                    invalidBoards.add(boardName);
                }
                if (!boardFile.exists() && !boardName.startsWith(MapSettings.BOARD_GENERATED)) {
                    serverBoards.add(boardName);
                }
                baseImages.put(boardName, bufImage);
            }
            return bufImage;
        }

        private int getZoom(Board board) {
            int largerEdge = Math.max(board.getWidth(), board.getHeight());
            int zoom = 3;

            if (largerEdge < 17) {
                zoom = 4;
            }

            if (largerEdge > 20) {
                zoom = 2;
            }

            if (largerEdge > 30) {
                zoom = 1;
            }

            if (largerEdge > 40) {
                zoom = 0;
            }

            if (board.getWidth() < 25) {
                zoom = Math.max(zoom, 3);
            }

            float scale = GUIP.getGUIScale();
            zoom = (int) (scale * zoom);

            if (zoom > 6) {
                zoom = 6;
            }

            if (zoom < 0) {
                zoom = 0;
            }

            return zoom;
        }

        @Override
        protected Void doInBackground() throws Exception {
            Image image;
            while (!isCancelled()) {
                // Create thumbnails for the MapSettings boards
                String boardName = boards.poll(1, TimeUnit.SECONDS);
                if (boardName != null) {
                    synchronized (baseImages) {
                        if (baseImages.containsKey(boardName)) {
                            continue;
                        }
                    }
                    image = prepareImage(boardName);
                    if (image != null) {
                        publish(image);
                    }
                }
            }
            return null;
        }

        @Override
        protected void process(List<Image> images) {
            if (this == loader && !isCancelled() && !images.isEmpty()) {
                MapSetupDialog.this.redrawMapTable(images.getLast());
            }
        }
    }

    Map<String, ImageIcon> mapIcons = new HashMap<>();

    /** A renderer for the list of available boards. */
    public class BoardNameRenderer extends DefaultListCellRenderer {
        @Serial
        private static final long serialVersionUID = -3218595828938299222L;

        private float oldGUIScale = GUIP.getGUIScale();

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
              boolean cellHasFocus) {

            String board = (String) value;
            // For generated boards, add the size to have different images for different
            // sizes
            if (board.startsWith(MapSettings.BOARD_GENERATED)) {
                board += mapSettings.getBoardSize();
            }

            // If the gui scaling has changed, clear out all images, triggering a reload
            float currentGUIScale = GUIP.getGUIScale();
            if (currentGUIScale != oldGUIScale) {
                oldGUIScale = currentGUIScale;
                mapIcons.clear();
                synchronized (baseImages) {
                    baseImages.clear();
                }
            }

            // If an icon is present for the current board, use it
            ImageIcon icon = mapIcons.get(board);
            if (icon != null) {
                setIcon(icon);
            } else {
                // The icon is not present, see if there's a base image
                Image image;

                synchronized (baseImages) {
                    image = baseImages.get(board);
                }

                if (image == null) {
                    // There's no base image: trigger loading it and, for now, return the base list's panel The
                    // [GENERATED] entry will always land here as well
                    loader.add(board);
                    setToolTipText(null);
                    return super.getListCellRendererComponent(list,
                          new File(board).getName(),
                          index,
                          isSelected,
                          cellHasFocus);
                } else {
                    // There is a base image: make it into an icon, store it and use it
                    if (!lisBoardsAvailable.isEnabled()) {
                        ImageFilter filter = new GrayFilter(true, 50);
                        ImageProducer producer = new FilteredImageSource(image.getSource(), filter);
                        image = Toolkit.getDefaultToolkit().createImage(producer);
                    }

                    icon = new ImageIcon(image);

                    mapIcons.put(board, icon);
                    setIcon(icon);
                }
            }

            // Found or created an icon; finish the panel
            setText("");
            if (lisBoardsAvailable.isEnabled()) {
                setToolTipText(createBoardTooltip(board));
            } else {
                setToolTipText(null);
            }

            if (isSelected) {
                setForeground(list.getSelectionForeground());
                setBackground(list.getSelectionBackground());
            } else {
                setForeground(list.getForeground());
                setBackground(list.getBackground());
            }

            return this;
        }
    }

    void updateMapButtons() {
        boolean haveImages = false;
        for (MapPreviewButton button : mapButtons) {
            button.scheduleRescale();
            haveImages |= button.hasBoard();
        }
        if (!haveImages) {
            Dimension size = maxMapButtonSize();
            for (MapPreviewButton button : mapButtons) {
                button.setPreviewSize(size);
            }
        }
    }

    void updateMapButtons(Dimension size) {
        if (!currentMapButtonSize.equals(size)) {
            currentMapButtonSize = size;
            for (MapPreviewButton button : mapButtons) {
                button.setPreviewSize(size);
            }
        }
    }

    Dimension maxMapButtonSize() {
        // minus 1 to ensure that the images actually fit in the frame
        int pw = (int) Math.floor(panMapButtons.getWidth() / (double) mapSettings.getMapWidth()) - 1;
        int ph = (int) Math.floor(panMapButtons.getHeight() / (double) mapSettings.getMapHeight()) - 1;
        return new Dimension(pw, ph);
    }

    Dimension optMapButtonSize(Image image) {
        Dimension optSize = maxMapButtonSize();
        double factorX = optSize.width / (double) image.getWidth(null);
        double factorY = optSize.height / (double) image.getHeight(null);
        double factor = Math.min(factorX, factorY);
        int w = (int) (factor * image.getWidth(null));
        int h = (int) (factor * image.getHeight(null));
        return new Dimension(w, h);
    }

    /**
     * Returns true when the string boardName contains an invalid board. boardName may denote a generated board (which
     * is never invalid) or a surprise board with several actual board names attached which will return true when at
     * least one of the boards is invalid.
     */
    boolean hasInvalidBoard(String boardName) {
        return hasSpecialBoard(boardName, invalidBoards);
    }

    /**
     * Returns true when the string boardName contains a board that isn't present on the client (only on the server).
     * boardName may denote a generated board (which is never serverside) or a surprise board with several actual board
     * names attached which will return true when at least one of the boards is serverside.
     */
    boolean hasServerSideBoard(String boardName) {
        return hasSpecialBoard(boardName, serverBoards);
    }

    /**
     * Returns true when boardName (if a single board) or any of the boards contained in boardName (if a surprise board
     * list) is contained in the provided list. Returns false for generated boards.
     */
    private boolean hasSpecialBoard(String boardName, Collection<String> list) {
        if (boardName.startsWith(MapSettings.BOARD_GENERATED)) {
            return false;
        } else if (boardName.startsWith(MapSettings.BOARD_SURPRISE)) {
            return !Collections.disjoint(extractSurpriseMaps(boardName), list);
        } else {
            return list.contains(boardName);
        }
    }

    /**
     * Returns a tooltip for the provided boardName that may be a single board or a generated or surprise board. Adds
     * info for serverside or invalid boards.
     */
    String createBoardTooltip(String boardName) {
        String result;
        if (boardName.startsWith(MapSettings.BOARD_GENERATED)) {
            result = Messages.getString("ChatLounge.board.generatedMessage");
        } else if (boardName.startsWith(MapSettings.BOARD_SURPRISE)) {
            result = Messages.getString("ChatLounge.board.randomlySelectedMessage");
            result += boardName.substring(MapSettings.BOARD_SURPRISE.length()).replace("\n", "<BR>");
        } else {
            result = boardName;
        }

        if (hasInvalidBoard(boardName)) {
            result += invalidBoardTip();
        }

        if (hasServerSideBoard(boardName)) {
            result += Messages.getString("ChatLounge.map.serverSideTip");
        }

        return result;
    }

}
