/*
 * Copyright (C) 2000-2008 - Ben Mazur (bmazur@sev.org).
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
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalTheme;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.TimerSingleton;
import megamek.client.bot.princess.MinefieldDeploymentPlanner;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListener;
import megamek.client.ui.IDisplayable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.overlay.ChatterBoxOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.TurnDetailsOverlay;
import megamek.client.ui.clientGUI.boardview.sprite.*;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricWreckSprite;
import megamek.client.ui.clientGUI.boardview.toolTip.BoardViewTooltipProvider;
import megamek.client.ui.dialogs.phaseDisplay.EntityChoiceDialog;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.EntityWreckHelper;
import megamek.client.ui.util.ImageCache;
import megamek.client.ui.util.ImageKeyedCache;
import megamek.client.ui.util.KeyBindReceiver;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.util.UIUtil;
import megamek.client.ui.widget.MegaMekBorder;
import megamek.client.ui.widget.SkinSpecification;
import megamek.client.ui.widget.SkinSpecification.UIComponents;
import megamek.client.ui.widget.SkinXMLHandler;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.KeyBindParser;
import megamek.common.Player;
import megamek.common.actions.AttackAction;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.equipment.Minefield;
import megamek.common.equipment.Mounted;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListener;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.options.OptionsConstants;
import megamek.common.pathfinder.BoardClusterTracker;
import megamek.common.pathfinder.BoardClusterTracker.BoardCluster;
import megamek.common.planetaryConditions.IlluminationLevel;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.*;
import megamek.common.util.ImageUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;

/**
 * Displays the board; lets the user scroll around and select points on it.
 */
public final class BoardView extends AbstractBoardView
      implements BoardListener, MouseListener, IPreferenceChangeListener, KeyBindReceiver, BoardGlyphContext {
    private static final MMLogger LOGGER = MMLogger.create(BoardView.class);

    public static final int BOARD_HEX_CLICK = 1;
    public static final int BOARD_HEX_DOUBLE_CLICK = 2;
    public static final int BOARD_HEX_DRAG = 3;
    private static final int BOARD_HEX_POPUP = 4;
    private static final long CHATTER_BOX_WHEEL_SCROLL_INTERVAL_NANOS = 100_000_000L;

    static final int HEX_WC = HEX_W - (HEX_W / 4);

    /**
     * This value is the vertical pixel offset for each hex elevation that is used to draw isometric mode
     */
    public static final int ISOMETRIC_OFFSET = 12;

    /**
     * This value is the vertical offset used currently for hex drawing. Its value is set to 0 for non-iso mode and
     * ISOMETRIC_OFFSET for iso mode. The drawing code itself uses the same logic to draw both modes.
     */
    private int verticalOffset = 0;

    private static final float[] ZOOM_FACTORS = { 0.30f, 0.41f, 0.50f, 0.60f, 0.68f, 0.79f, 0.90f, 1.00f, 1.09f, 1.17f,
                                                  1.3f, 1.6f, 2.0f, 3.0f };

    private static final int[] ZOOM_SCALE_TYPES = { ImageUtil.IMAGE_SCALE_AVG_FILTER, ImageUtil.IMAGE_SCALE_AVG_FILTER,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC,
                                                    ImageUtil.IMAGE_SCALE_BICUBIC, ImageUtil.IMAGE_SCALE_BICUBIC };

    public static final int[] allDirections = { 0, 1, 2, 3, 4, 5 };

    public int DROP_SHADOW_DISTANCE = 20;

    // the index of zoom factor 1.00f
    static final int BASE_ZOOM_INDEX = 7;

    // Initial zoom index
    public int zoomIndex = BASE_ZOOM_INDEX;

    // Set Zoom Out Overview Toggle inactive.
    public boolean zoomOverview = false;

    private static final Font FONT_7 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 7);
    private static final Font FONT_9 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 9);
    private static final Font FONT_10 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 10);
    private static final Font FONT_12 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONT_14 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 14);
    private static final Font FONT_16 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 16);
    private static final Font FONT_18 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 18);
    private static final Font FONT_24 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 24);

    Dimension hex_size;

    private final Font font_note = FONT_10;
    private Font font_hexNumber = FONT_10;
    private Font font_elev = FONT_9;
    private Font font_minefield = FONT_12;

    private final JPanel boardPanel = new BoardViewPanel(this);

    public final Game game;
    ClientGUI clientgui;

    private Dimension boardSize;

    // scroll stuff:
    private JScrollPane scrollPane = null;
    private JScrollBar verticalBar;
    private JScrollBar horizontalBar;
    private int scrollXDifference = 0;
    private int scrollYDifference = 0;
    private double chatterBoxWheelRotation;
    private long lastChatterBoxWheelScrollNanos = Long.MIN_VALUE;
    private double boardZoomWheelRotation;
    private int preZoomOverviewIndex = 0;
    private int preZoomOverviewViewX = 0;
    private int preZoomOverviewViewY = 0;
    private int bestZoomFactor = 0;
    // are we drag-scrolling?
    private boolean dragging = false;
    private boolean wantsPopup = false;

    /** True when the right mouse button was pressed to start a drag */
    private boolean shouldScroll = false;

    // entity sprites
    private Queue<EntitySprite> entitySprites = new PriorityQueue<>();
    private Queue<IsometricSprite> isometricSprites = new PriorityQueue<>();
    /**
     * A Map that maps an Entity ID and a secondary position to a Sprite. Note that the key is a List where the first
     * entry will be the Entity ID and the second entry will be which secondary position the sprite belongs to; if the
     * Entity has no secondary positions, the first element will be the ID and the second element will be -1.
     */
    private Map<ArrayList<Integer>, EntitySprite> entitySpriteIds = new HashMap<>();
    /**
     * A Map that maps an Entity ID and a secondary position to a Sprite. Note that the key is a List where the first
     * entry will be the Entity ID and the second entry will be which secondary position the sprite belongs to; if the
     * Entity has no secondary positions, the first element will be the ID and the second element will be -1.
     */
    private Map<ArrayList<Integer>, IsometricSprite> isometricSpriteIds = new HashMap<>();


    TilesetManager tileManager;
    private final boolean ownsClientState;
    private final GameListener boardGameListener;
    private long appliedFocus;
    private final List<Runnable> keyRegistrations = new ArrayList<>();

    // polygons for a few things
    private static final Polygon HEX_POLY = HexDrawUtilities.rasterHex();

    Shape[] movementPolys;
    Shape[] facingPolys;
    Shape[] finalFacingPolys;
    Shape upArrow;
    Shape downArrow;

    // Image to hold the complete board shadow map
    BufferedImage shadowMap;

    // Initial scale factor for sprites and map
    float scale = 1.00f;
    private ImageKeyedCache<Image> scaledImageCache = new ImageKeyedCache<>();
    private final ImageKeyedCache<BufferedImage> shadowImageCache = new ImageKeyedCache<>();

    private final Set<Integer> animatedImages = new HashSet<>();

    // Move units step by step
    private final ArrayList<MovingUnit> movingUnits = new ArrayList<>();

    private long moveWait = 0;

    // moving entity sprites
    private ArrayList<MovingEntitySprite> movingEntitySprites = new ArrayList<>();
    private HashMap<Integer, MovingEntitySprite> movingEntitySpriteIds = new HashMap<>();
    private final ArrayList<GhostEntitySprite> ghostEntitySprites = new ArrayList<>();

    // wreck sprites
    private ArrayList<WreckSprite> wreckSprites = new ArrayList<>();
    private ArrayList<IsometricWreckSprite> isometricWreckSprites = new ArrayList<>();


    private Board observedBoard;
    private boolean disposed;
    private final BoardClientState clientState;

    /** stores the theme last selected to override all hex themes */

    // reference to our timer task for redraw
    private final TimerTask redrawTimerTask;

    BufferedImage bvBgImage = null;
    boolean bvBgShouldTile = false;
    BufferedImage scrollPaneBgBuffer = null;
    Image scrollPaneBgImg = null;

    private static final int FRAMES = 24;
    private long totalTime;
    private long averageTime;
    private int frameCount;
    private final Font fpsFont = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 20);

    /**
     * Keeps track of whether we have an active ChatterBox2
     */

    /**
     * Keeps track of whether an outside source tells the BoardView that it should ignore keyboard commands.
     */

    private final FovHighlightingAndDarkening fovHighlightingAndDarkening;

    private static final String FILENAME_RADAR_BLIP_IMAGE = "radarBlip.png";
    private final Image radarBlipImage;

    /**
     * Cache that stores hex images for different coords
     */
    ImageCache<Coords, HexImageCacheEntry> hexImageCache;

    /**
     * GPU hex layers, kept per hex so captures do not re-match the tileset every frame: the ground artwork
     * the hex paints, the same ground without its water, and the features drawn over both.
     */


    private long paintCompsStartTime;


    // Soft Centering ---

    /** True when the board is in the process of centering to a spot. */
    private boolean isSoftCentering = false;
    /**
     * The final position of a soft centering relative to board size (x, y = 0...1).
     */
    private final Point2D softCenterTarget = new Point2D.Double();
    private Point2D oldCenter = new Point2D.Double();
    private long waitTimer;
    /** Speed of soft centering of the board, less is faster */
    private static final int SOFT_CENTER_SPEED = 8;

    /**
     * Holds the final Coords for a planned movement. Set by MovementDisplay, used to display the distance in the board
     * tooltip.
     */

    // Used to track the previous x/y for tooltip display
    int prevTipX = -1, prevTipY = -1;

    /**
     * Flag to indicate if we should display information about illegal terrain in hexes.
     */
    boolean displayInvalidHexInfo = false;

    /**
     * Stores the correct tooltip dismiss delay so it can be restored when exiting the BoardView
     */
    private final int dismissDelay = ToolTipManager.sharedInstance().getDismissDelay();

    /** The coords where the mouse was last. */
    Coords lastCoords;

    private final GUIPreferences GUIP = GUIPreferences.getInstance();

    private final TerrainShadowHelper shadowHelper = new TerrainShadowHelper(this);

    /**
     * Keeps track of whether all deployment zones should be shown in the Arty Auto Hit Designation phase
     */

    BoardViewTooltipProvider boardViewToolTip = (point, movementTarget) -> null;

    // Part of the sprites need specialized treatment; as there can be many sprites, filtering them on the spot is a
    // noticeable performance hit (in iso mode), therefore the sprites are copied to specialized lists when created


    /**
     * Construct a new board view for the specified game
     */
    public BoardView(final Game game, final MegaMekController controller, @Nullable ClientGUI clientgui, int boardId)
          throws IOException {
        this(game, controller, clientgui, boardId, null);
    }

    /** Editor tools can share their tileset with an explicitly opened compatibility viewport. */
    public BoardView(Game game, MegaMekController controller, @Nullable ClientGUI clientgui, int boardId,
          @Nullable TilesetManager sharedTileset) throws IOException {
        this(game, controller, clientgui, boardId, sharedTileset, null);
    }

    public BoardView(BoardClientState state, MegaMekController controller, @Nullable ClientGUI clientgui) throws IOException {
        this(state.getGame(), controller, clientgui, state.getBoardId(), state.getTilesetManager(), state);
    }

    private BoardView(Game game, MegaMekController controller, @Nullable ClientGUI clientgui, int boardId,
          @Nullable TilesetManager sharedTileset, @Nullable BoardClientState sharedState) throws IOException {
        super(boardId);
        this.game = game;
        this.clientgui = clientgui;
        ownsClientState = sharedState == null;
        clientState = ownsClientState ? new BoardClientState(game, controller, clientgui, boardId, sharedTileset) : sharedState;
        hexImageCache = new ImageCache<>();
        tileManager = clientState.getTilesetManager();
        clientState.setEntityRenderer(entity -> {
            if (entity == null) { redrawAllEntitySprites(); } else { redrawEntitySprites(entity); }
        });
        clientState.setVisibleArea(this::getVisibleArea);
        clientState.setInputEnabled(this::shouldReceiveKeyCommands);
        clientState.setProjection(this);
        clientState.setMovingUnitPainter(graphics -> {
            drawSprites(graphics, movingEntitySprites);
            drawSprites(graphics, ghostEntitySprites);
        });
        ToolTipManager.sharedInstance().registerComponent(boardPanel);
        setVerticalOffset();

        // For Entities that have converted to another mode, check for a different sprite for units that have been
        // blown up, damaged or ejected, force a reload Clear some information regardless of what phase it is
        boardGameListener = new GameListenerAdapter() {
            @Override
            public void gameEntityChange(GameEntityChangeEvent event) {
                Entity entity = event.getEntity();
                Vector<UnitLocation> path = event.getMovePath();
                if (path != null && !path.isEmpty() && GUIP.getShowMoveStep()
                      && !game.getOptions().booleanOption(OptionsConstants.INIT_SIMULTANEOUS_MOVEMENT)
                      && EntityVisibilityUtils.detectedOrHasVisual(getLocalPlayer(), game, entity)) {
                    addMovingUnit(entity, new Vector<>(path));
                }
            }
            @Override
            public void gameBoardChanged(GameBoardChangeEvent event) {
                clearHexImageCache();
            }

            @Override
            public void gameBoardNew(GameBoardNewEvent event) {
                if (!disposed && (event.getBoardId() == boardId)) {
                    if (observedBoard != null) {
                        observedBoard.removeBoardListener(BoardView.this);
                    }
                    observedBoard = event.getNewBoard();
                    if (observedBoard != null) {
                        observedBoard.addBoardListener(BoardView.this);
                        updateBoard();
                    }
                    clearHexImageCache();
                    clearShadowMap();
                    boardPanel.repaint();
                }
            }
        };

        game.addGameListener(boardGameListener);
        observedBoard = game.getBoard(boardId);
        observedBoard.addBoardListener(this);

        redrawTimerTask = scheduleRedrawTimer(); // call only once
        if (ownsClientState) { clearSprites(); }
        boardPanel.addMouseListener(this);
        boardPanel.addMouseWheelListener(mouseWheelEvent -> {
            Point mousePoint = mouseWheelEvent.getPoint();
            Point dispPoint = new Point(mousePoint.x + boardPanel.getBounds().x,
                  mousePoint.y + boardPanel.getBounds().y);

            // If the mouse is over an IDisplayable, have it react instead of the board. Currently only implemented
            // for the ChatterBox
            for (IDisplayable displayable : clientState.overlays) {
                if (displayable instanceof ChatterBoxOverlay chatterBox2) {
                    double width = scrollPane.getViewport().getSize().getWidth();
                    double height = scrollPane.getViewport().getSize().getHeight();
                    Dimension drawDimension = new Dimension();
                    drawDimension.setSize(width, height);

                    // mouseWheelEvent need to adjust the point, because it should be against the displayable dimension
                    if (displayable.isMouseOver(dispPoint, drawDimension)) {
                        boardZoomWheelRotation = 0;
                        chatterBoxWheelRotation += mouseWheelEvent.getPreciseWheelRotation();
                        long now = java.lang.System.nanoTime();
                        if ((Math.abs(chatterBoxWheelRotation) >= 1.0)
                              && ((lastChatterBoxWheelScrollNanos == Long.MIN_VALUE)
                                    || (now - lastChatterBoxWheelScrollNanos
                                          >= CHATTER_BOX_WHEEL_SCROLL_INTERVAL_NANOS))) {
                            if (chatterBoxWheelRotation > 0) {
                                chatterBox2.scrollDown();
                            } else {
                                chatterBox2.scrollUp();
                            }

                            chatterBoxWheelRotation = 0;
                            lastChatterBoxWheelScrollNanos = now;
                            refreshDisplayables();
                        }

                        return;
                    }
                }
            }

            boolean ZoomNoCtrl = GUIP.getMouseWheelZoom();
            boolean wheelFlip = GUIP.getMouseWheelZoomFlip();
            boolean doZoom = ZoomNoCtrl ^ mouseWheelEvent.isControlDown(); // = XOR
            boolean horizontalScroll = !doZoom && mouseWheelEvent.isShiftDown();

            if (doZoom) {
                boardZoomWheelRotation += mouseWheelEvent.getPreciseWheelRotation();
                int zoomSteps = (int) Math.abs(boardZoomWheelRotation);
                if (zoomSteps == 0) {
                    return;
                }

                boolean zoomIn = (boardZoomWheelRotation > 0) ^ wheelFlip; // = XOR
                boardZoomWheelRotation -= Math.copySign(zoomSteps, boardZoomWheelRotation);

                Coords zoomCenter = getCoordsAt(mouseWheelEvent.getPoint());
                Point hexL = getCentreHexLocation(zoomCenter);
                Point inHexDelta = new Point(mouseWheelEvent.getPoint());
                inHexDelta.translate(-HEX_W, -HEX_H);
                inHexDelta.translate(-hexL.x, -hexL.y);
                double inHexDeltaX = ((double) inHexDelta.x) / ((double) HEX_W) / scale;
                double inHexDeltaY = ((double) inHexDelta.y) / ((double) HEX_H) / scale;
                int oldZoomIndex = zoomIndex;

                for (int step = 0; step < zoomSteps; step++) {
                    if (zoomIn) {
                        zoomIn();
                    } else {
                        zoomOut();
                    }
                }

                if (zoomIndex != oldZoomIndex) {
                    adjustVisiblePosition(zoomCenter, dispPoint, inHexDeltaX, inHexDeltaY);
                }
                pingMinimap();
            } else {
                boardZoomWheelRotation = 0;
                if (mouseWheelEvent.getWheelRotation() == 0) {
                    return;
                }

                // SCROLL
                if (horizontalScroll) {
                    horizontalBar.setValue((int) (horizontalBar.getValue() + (HEX_H
                          * scale
                          * (mouseWheelEvent.getWheelRotation()))));
                } else {
                    verticalBar.setValue((int) (verticalBar.getValue() + (HEX_H
                          * scale
                          * (mouseWheelEvent.getWheelRotation()))));
                }
                stopSoftCentering();
                pingMinimap();
            }
        });

        MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent mouseEvent) {
                Point point = mouseEvent.getPoint();
                for (IDisplayable displayable : clientState.overlays) {

                    if (displayable.isBeingDragged()) {
                        return;
                    }

                    double width = Math.min(boardSize.getWidth(), scrollPane.getViewport().getSize().getWidth());
                    double height = Math.min(boardSize.getHeight(), scrollPane.getViewport().getSize().getHeight());
                    Dimension drawDimension = new Dimension();
                    drawDimension.setSize(width, height);
                    displayable.isMouseOver(point, drawDimension);
                }

                // Reset popup flag if the user moves their mouse away
                wantsPopup = false;

                final Coords mcoords = getCoordsAt(point);
                if (!mcoords.equals(lastCoords) && game.getBoard(boardId).contains(mcoords)) {
                    if (clientState.isTooltipSuspended()) {
                        boardPanel.setToolTipText(null);
                    } else {
                        lastCoords = mcoords;
                        boardPanel.setToolTipText(boardViewToolTip.getTooltip(mouseEvent, clientState.movementTarget));
                    }
                } else if (!game.getBoard(boardId).contains(mcoords)) {
                    boardPanel.setToolTipText(null);
                } else {
                    if (prevTipX > 0 && prevTipY > 0) {
                        int deltaX = point.x - prevTipX;
                        int deltaY = point.y - prevTipY;
                        double deltaMagnitude = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                        if (deltaMagnitude > GUIP.getTooltipDistSuppression()) {
                            prevTipX = -1;
                            prevTipY = -1;
                            // Set the dismissal delay to 0 so that the tooltip goes away and does not reappear until
                            // the mouse has moved more than the suppression distance
                            ToolTipManager.sharedInstance().setDismissDelay(0);
                            // and then, when the tooltip has gone away, reset the dismiss delay
                            SwingUtilities.invokeLater(() -> {
                                if (GUIP.getTooltipDismissDelay() >= 0) {
                                    ToolTipManager.sharedInstance().setDismissDelay(GUIP.getTooltipDismissDelay());
                                } else {
                                    ToolTipManager.sharedInstance().setDismissDelay(dismissDelay);
                                }
                            });
                        }
                    }
                    prevTipX = point.x;
                    prevTipY = point.y;
                }
            }

            @Override
            public void mouseDragged(MouseEvent mouseEvent) {
                Point point = mouseEvent.getPoint();
                for (IDisplayable displayable : clientState.overlays) {
                    Point adjustPoint = new Point((int) Math.min(boardSize.getWidth(), -boardPanel.getBounds().getX()),
                          (int) Math.min(boardSize.getHeight(), -boardPanel.getBounds().getY()));
                    Point dispPoint = new Point();
                    dispPoint.x = point.x - adjustPoint.x;
                    dispPoint.y = point.y - adjustPoint.y;
                    double width = Math.min(boardSize.getWidth(), scrollPane.getViewport().getSize().getWidth());
                    double height = Math.min(boardSize.getHeight(), scrollPane.getViewport().getSize().getHeight());
                    Dimension drawDimension = new Dimension();
                    drawDimension.setSize(width, height);
                    if (displayable.isDragged(dispPoint, drawDimension)) {
                        boardPanel.repaint();
                        return;
                    }
                }
                // only scroll when we should
                if (!shouldScroll) {
                    mouseAction(getCoordsAt(point),
                          BOARD_HEX_DRAG,
                          mouseEvent.getModifiersEx(),
                          mouseEvent.getButton());
                    return;
                }
                // if we have not yet been dragging, set the var so popups don't appear when we stop scrolling
                if (!dragging) {
                    dragging = true;
                    boardPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                Point p = scrollPane.getViewport().getViewPosition();
                int newX = p.x - (mouseEvent.getX() - scrollXDifference);
                int newY = p.y - (mouseEvent.getY() - scrollYDifference);
                int maxX = boardPanel.getWidth() - scrollPane.getViewport().getWidth();
                int maxY = boardPanel.getHeight() - scrollPane.getViewport().getHeight();

                if (newX < 0) {
                    newX = 0;
                }

                if (newX > maxX) {
                    newX = maxX;
                }

                if (newY < 0) {
                    newY = 0;
                }

                if (newY > maxY) {
                    newY = maxY;
                }

                // don't scroll horizontally if the board fits into the window
                if (scrollPane.getViewport().getWidth() >= boardPanel.getWidth()) {
                    newX = scrollPane.getViewport().getViewPosition().x;
                }

                scrollPane.getViewport().setViewPosition(new Point(newX, newY));
                pingMinimap();
            }
        };
        boardPanel.addMouseMotionListener(mouseMotionListener);

        if (controller != null) {
            registerKeyboardCommands(controller);
        }

        updateBoardSize();

        hex_size = new Dimension((int) (HEX_W * scale), (int) (HEX_H * scale));

        initPolys();


        PreferenceManager.getClientPreferences().addPreferenceChangeListener(this);
        GUIP.addPreferenceChangeListener(this);
        KeyBindParser.addPreferenceChangeListener(this);


        fovHighlightingAndDarkening = clientState.getFieldOfView();
        clientState.setChanged(() -> {
            hexImageCache.clear();
            BoardFocus request = clientState.getCenterRequest();
            if (request.sequence() != appliedFocus) {
                appliedFocus = request.sequence();
                if (request.coords() != null) { applyCenterRequest(request.coords()); }
            }
            for (EntitySprite sprite : entitySprites) {
                sprite.setAffectedByECM(clientState.isAffectedByECM(sprite.getEntity()));
                sprite.setSelected(clientState.isEntitySelected(sprite.getEntity()));
            }
            boardPanel.repaint();
        });

        radarBlipImage = clientState.getRadarBlipImage();
    }

    private void registerKeyboardCommands(final MegaMekController controller) {

        keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.SCROLL_NORTH,
              this::canScrollLegacy2D,
              this::scrollNorth,
              this::pingMinimap));
        keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.SCROLL_SOUTH,
              this::canScrollLegacy2D,
              this::scrollSouth,
              this::pingMinimap));
        keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.SCROLL_EAST,
              this::canScrollLegacy2D,
              this::scrollEast,
              this::pingMinimap));
        keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.SCROLL_WEST,
              this::canScrollLegacy2D,
              this::scrollWest,
              this::pingMinimap));
    }

    private boolean canScrollLegacy2D() {
        return scrollPane != null && shouldReceiveKeyCommands()
              && (clientgui == null || !GpuBoardWindow.isActiveFor(clientgui));
    }

    private void scrollNorth() {
        verticalBar.setValue((int) (verticalBar.getValue() - (HEX_H * scale)));
        stopSoftCentering();
    }

    private void scrollSouth() {
        verticalBar.setValue((int) (verticalBar.getValue() + (HEX_H * scale)));
        stopSoftCentering();
    }

    private void scrollEast() {
        horizontalBar.setValue((int) (horizontalBar.getValue() + (HEX_W * scale)));
        stopSoftCentering();
    }

    private void scrollWest() {
        horizontalBar.setValue((int) (horizontalBar.getValue() - (HEX_W * scale)));
        stopSoftCentering();
    }


    @Override
    public boolean shouldReceiveKeyCommands() {
        return !getChatterBoxActive() && boardPanel.isVisible() && !game.getPhase().isLounge() && !clientState.shouldIgnoreKeys;
    }

    private final RedrawWorker redrawWorker = new RedrawWorker();
    private final AtomicBoolean redrawPending = new AtomicBoolean();

    /**
     * this should only be called once!! this will cause a timer to schedule constant screen updates every 20
     * milliseconds!
     */
    private TimerTask scheduleRedrawTimer() {
        final TimerTask redraw = new TimerTask() {
            @Override
            public void run() {
                scheduleRedraw();
            }
        };
        TimerSingleton.getInstance().schedule(redraw, 20, 20);
        return redraw;
    }

    private void scheduleRedraw() {
        if (!redrawPending.compareAndSet(false, true)) {
            return;
        }
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    redrawWorker.run();
                } finally {
                    redrawPending.set(false);
                }
            });
        } catch (Exception ignored) {
            redrawPending.set(false);
        }
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent e) {
        switch (e.getName()) {
            case ClientPreferences.MAP_TILESET:
                clearHexImageCache();
                updateBoard();
                break;

            case GUIPreferences.UNIT_LABEL_STYLE:
            case GUIPreferences.UNIT_LABEL_BORDER:
            case GUIPreferences.TEAM_COLORING:
            case GUIPreferences.SHOW_DAMAGE_DECAL:
            case GUIPreferences.SHOW_DAMAGE_LEVEL:
                for (Sprite s : wreckSprites) {
                    s.prepare();
                }

                for (Sprite s : isometricWreckSprites) {
                    s.prepare();
                }

                break;
            case GUIPreferences.USE_ISOMETRIC:
                toggleIsometric();
                break;

            case GUIPreferences.SHOW_MAP_SHEETS:
            case GUIPreferences.BOARD_MAP_SHEET_COLOR:
                hexImageCache.clear();
                boardPanel.repaint();
                break;

            case GUIPreferences.AO_HEX_SHADOWS:
            case GUIPreferences.FLOATING_ISO:
            case GUIPreferences.LEVEL_HIGHLIGHT:
            case GUIPreferences.SHOW_COORDS:
            case GUIPreferences.SHOW_HEX_TYPES:
            case GUIPreferences.HEX_LABEL_OUTLINE:
            case GUIPreferences.FOV_DARKEN:
            case GUIPreferences.FOV_DARKEN_ALPHA:
            case GUIPreferences.FOV_GRAYSCALE:
            case GUIPreferences.FOV_SPOTTING_MODE:
            case GUIPreferences.LOS_MEK_IN_FIRST:
            case GUIPreferences.LOS_MEK_IN_SECOND:
            case GUIPreferences.FOV_HIGHLIGHT:
            case GUIPreferences.FOV_HIGHLIGHT_ALPHA:
            case GUIPreferences.FOV_STRIPES:
            case GUIPreferences.FOV_HIGHLIGHT_RINGS_COLORS_HSB:
            case GUIPreferences.FOV_HIGHLIGHT_RINGS_RADII:
            case GUIPreferences.SHADOW_MAP:
                clearHexImageCache();
                tileManager.reloadUnitIcons();
                boardPanel.repaint();
                break;
            case GUIPreferences.INCLINES:
                clearHexImageCache();
                boardPanel.repaint();
                break;
        }
    }

    /**
     * Returns whether a unit that finished a move can have that move animated. A unit that mounted a DropShip, was
     * recovered by a carrier or left the board during its move has no position left, so there is no hex to draw its
     * ghost sprite in.
     *
     * @param entity the unit that finished a move
     *
     * @return {@code true} when the unit still has a position to animate from
     */
    static boolean canAnimateMove(@Nullable Entity entity) {
        return (entity != null) && (entity.getPosition() != null);
    }

    void addMovingUnit(Entity entity, Vector<UnitLocation> movePath) {
        if (!canAnimateMove(entity)) {
            LOGGER.debug("Move animation skipped: {} has no position (loaded or off board)",
                  (entity == null) ? "null entity" : entity.getShortName());
            return;
        }
        if (!movePath.isEmpty() && isOnThisBord(entity)) {
            MovingUnit m = new MovingUnit(entity, movePath);
            movingUnits.add(m);
            clientState.setMovingUnits(true);

            GhostEntitySprite ghostSprite = new GhostEntitySprite(this, entity);
            ghostEntitySprites.add(ghostSprite);

            // Center on the starting hex of the moving unit.
            UnitLocation loc = movePath.getFirst();

            if (GUIP.getAutoCenter()) {
                centerOnHex(loc.coords());
            }
        }
    }

    @Override
    public void draw(Graphics graphics) {
        if (!(graphics instanceof Graphics2D graphics2D)) {
            return;
        }
        if (GUIP.getShowFPS()) {
            paintCompsStartTime = java.lang.System.nanoTime();
        }

        UIUtil.setHighQualityRendering(graphics2D);
        Rectangle viewRect = scrollPane.getVisibleRect();

        if (!isTileImagesLoaded()) {
            graphics2D.setColor(Color.DARK_GRAY);
            graphics2D.fillRect(-boardPanel.getX(), -boardPanel.getY(), viewRect.width, viewRect.height);
            graphics2D.setColor(Color.LIGHT_GRAY);
            graphics2D.drawString(Messages.getString("BoardView1.loadingImages"), 20, 50);

            if (!tileManager.isStarted()) {
                LOGGER.info("Loading images for board");
                tileManager.loadNeededImages(game);
            }

            // wait 1 second, then repaint
            boardPanel.repaint(1000);
            return;
        }

        if (bvBgShouldTile && (bvBgImage != null)) {
            Rectangle clipping = graphics2D.getClipBounds();
            int x;
            int y = 0;
            int w = bvBgImage.getWidth();
            int h = bvBgImage.getHeight();

            while (y < (int) Math.floor(clipping.getHeight())) {
                int yRem = 0;

                if (y == 0) {
                    yRem = tileOffset(clipping.y, h);
                }

                x = 0;

                while (x < (int) Math.floor(clipping.getWidth())) {
                    int xRem = 0;
                    if (x == 0) {
                        xRem = tileOffset(clipping.x, w);
                    }
                    if ((xRem > 0) || (yRem > 0)) {
                        try {
                            graphics2D.drawImage(bvBgImage.getSubimage(xRem, yRem, w - xRem, h - yRem),
                                  clipping.x + x,
                                  clipping.y + y,
                                  boardPanel);
                        } catch (Exception e) {
                            // if we somehow messed up the math, log the error and simply act as if we have no
                            // background image.
                            Rectangle rasterBounds = bvBgImage.getRaster().getBounds();

                            String errorData = String.format(
                                  "Error drawing background image. Raster Bounds: %.2f, %.2f, width:%.2f, height:%.2f, Attempted Draw Coordinates: %d, %d, width:%d, height:%d",
                                  rasterBounds.getMinX(),
                                  rasterBounds.getMinY(),
                                  rasterBounds.getWidth(),
                                  rasterBounds.getHeight(),
                                  xRem,
                                  yRem,
                                  w - xRem,
                                  h - yRem);
                            LOGGER.error(errorData);
                        }
                    } else {
                        graphics2D.drawImage(bvBgImage, clipping.x + x, clipping.y + y, boardPanel);
                    }
                    x += w - xRem;
                }
                y += h - yRem;
            }
        } else if (bvBgImage != null) {
            graphics2D.drawImage(bvBgImage,
                  -boardPanel.getX(),
                  -boardPanel.getY(),
                  (int) viewRect.getWidth(),
                  (int) viewRect.getHeight(),
                  boardPanel);
        } else {
            MetalTheme theme = new DefaultMetalTheme();
            graphics2D.setColor(theme.getControl());
            graphics2D.fillRect(-boardPanel.getX(),
                  -boardPanel.getY(),
                  (int) viewRect.getWidth(),
                  (int) viewRect.getHeight());
        }

        // Used to pad the board edge
        graphics2D.translate(HEX_W, HEX_H);

        // Initialize the shadow map when it's not yet present
        if (shadowMap == null) {
            shadowMap = shadowHelper.updateShadowMap();
        }

        drawHexes(graphics2D, graphics2D.getClipBounds());

        drawTacticalLayers(graphics2D, true);

        // Undo the previous translation
        graphics2D.translate(-HEX_W, -HEX_H);

        // draw all the "displayable"
        if (clientState.displayablesRect == null) {
            clientState.displayablesRect = new Rectangle();
        }

        clientState.displayablesRect.x = -boardPanel.getX();
        clientState.displayablesRect.y = -boardPanel.getY();
        clientState.displayablesRect.width = scrollPane.getViewport().getViewRect().width;
        clientState.displayablesRect.height = scrollPane.getViewport().getViewRect().height;

        for (IDisplayable displayable : clientState.overlays) {
            displayable.draw(graphics2D, clientState.displayablesRect);
        }

        if (GUIP.getShowFPS()) {
            if (frameCount == FRAMES) {
                averageTime = totalTime / FRAMES;
                totalTime = 0;
                frameCount = 0;
            } else {
                totalTime += java.lang.System.nanoTime() - paintCompsStartTime;
                frameCount++;
            }

            String s = String.format("%1$5.3f", averageTime / 1000000d);
            graphics2D.setFont(fpsFont);
            graphics2D.setColor(Color.YELLOW);
            graphics2D.drawString(s, -boardPanel.getX() + 5, -boardPanel.getY() + 20);
        }

        // debugging method that renders the bounding box of a unit's movement envelope.
        // renderClusters((Graphics2D) graphics2D);
        // renderDonut(graphics2D, new Coords(10, 10), 2);
        // renderApproxHexDirection((Graphics2D) graphics2D);
        // renderMinefieldScores((Graphics2D) graphics2D);
    }

    /** Shared tactical presentation for the Legacy 2D board and GPU surface layers. */
    private void drawTacticalLayers(Graphics2D graphics2D, boolean includeUnits) {
        clientState.drawTacticalLayers(graphics2D, includeUnits);
    }

    /**
     * Debugging method that renders a hex in the approximate direction from the selected entity to the selected hex, of
     * both exist.
     *
     * @param g Graphics object on which to draw.
     */
    @SuppressWarnings("unused")
    private void renderApproxHexDirection(Graphics2D g) {
        if (getSelectedEntity() == null || getSelected() == null) {
            return;
        }

        int direction = getSelectedEntity().getPosition().approximateDirection(getSelected(), 0, 0);

        Coords donutCoords = getSelectedEntity().getPosition().translated(direction);

        Point p = getCentreHexLocation(donutCoords.getX(), donutCoords.getY(), true);
        p.translate(HEX_W / 2, HEX_H / 2);
        drawHexBorder(g, p, Color.BLUE, 0, 6);
    }

    /**
     * Debugging method that renders a hex donut around the given coordinates, with the given radius.
     *
     * @param graphics2D Graphics object on which to draw.
     */
    @SuppressWarnings("unused")
    private void renderDonut(Graphics2D graphics2D, Coords coords, int radius) {
        ArrayList<Coords> donut = coords.allAtDistance(radius);

        for (Coords donutCoords : donut) {
            Point centreHexLocation = getCentreHexLocation(donutCoords.getX(), donutCoords.getY(), true);
            centreHexLocation.translate(HEX_W / 2, HEX_H / 2);
            drawHexBorder(graphics2D, centreHexLocation, Color.PINK, 0, 6);
        }
    }

    /**
     * Debugging method that renders an obnoxious pink lines around hexes in "Board Clusters"
     *
     * @param graphics2D Graphics object on which to draw.
     */
    @SuppressWarnings("unused")
    private void renderClusters(Graphics2D graphics2D) {
        BoardClusterTracker boardClusterTracker = new BoardClusterTracker();
        Map<Coords, BoardCluster> clusterMap = boardClusterTracker.generateClusters(getSelectedEntity(), false, true);

        for (BoardCluster cluster : clusterMap.values().stream().distinct().toList()) {
            for (Coords coords : cluster.contents.keySet()) {
                Point centreHexLocation = getCentreHexLocation(coords.getX(), coords.getY(), true);
                centreHexLocation.translate(HEX_W / 2, HEX_H / 2);
                drawHexBorder(graphics2D, centreHexLocation, new Color(0, 0, (20 * cluster.id) % 255), 0, 6);
            }
        }
    }

    /**
     * Debugging method used to render minefield effectiveness ratings
     */
    @SuppressWarnings("unused")
    private void renderMinefieldScores(Graphics2D graphics2D) {
    	/*Map<Coords, Integer> minefieldScores = mdp.getMinefieldScores(Minefield.TYPE_CONVENTIONAL, UnitType.TANK,
    			EntityMovementMode.WHEELED, getBoard());*/

    	MinefieldDeploymentPlanner mdp = new MinefieldDeploymentPlanner(getLocalPlayer(), game);
    	Map<Coords, Double> minefieldScores = mdp.buildCoalescedMinefieldScores(Minefield.TYPE_CONVENTIONAL, getBoard());

    	for (Coords coords : minefieldScores.keySet()) {
    		Point centreHexLocation = getCentreHexLocation(coords.getX(), coords.getY(), true);
            centreHexLocation.translate(HEX_W / 2, HEX_H);
            graphics2D.setColor(Color.pink);
            drawCenteredString(String.format("%3.1f", minefieldScores.get(coords)),
            		centreHexLocation.x, centreHexLocation.y, FONT_14, graphics2D);
    	}
    }

    public void clearShadowMap() {
        shadowMap = null;
    }

    public @Nullable Point getTerrainLightDirection() {
        return shadowHelper.lightDirection();
    }

    /**
     * Updates the boardSize variable with the proper values for this board.
     */
    void updateBoardSize() {
        int width = (game.getBoard(boardId).getWidth() * (int) (HEX_WC * scale)) + (int) ((HEX_W / 4.0f) * scale);
        int height = (game.getBoard(boardId).getHeight() * (int) (HEX_H * scale)) + (int) ((HEX_H / 2.0f) * scale);
        boardSize = new Dimension(width, height);
    }

    /**
     * Looks through a vector of buffered images and draws them if they're onscreen.
     */
    private synchronized void drawSprites(Graphics2D graphics2D, Collection<? extends Sprite> spriteArrayList) {
        clientState.drawSprites(graphics2D, spriteArrayList);
    }

    private synchronized void drawHexSpritesForHex(Coords coords, Graphics2D graphics2D,
          Collection<? extends HexSprite> spriteArrayList, boolean includeUnits) {
        Rectangle view = graphics2D.getClipBounds();

        for (HexSprite sprite : spriteArrayList) {
            if (!includeUnits && sprite instanceof IsometricSprite) {
                continue;
            }
            Coords spritePosition = sprite.getPosition();
            if (spritePosition == null) {
                continue;
            }
            // This can potentially be an expensive operation
            Rectangle spriteBounds = sprite.getBounds();
            if (spritePosition.equals(coords) && view.intersects(spriteBounds) && !sprite.isHidden()) {
                if (!sprite.isReady()) {
                    sprite.prepare();
                }

                sprite.drawOnto(graphics2D, spriteBounds.x, spriteBounds.y, boardPanel, false);
            }
        }
    }

    /**
     * Draws the wreck sprites for the given hex, optionally filtered by whether the wrecked entity was on a bridge.
     * Splitting the pass by bridge state lets callers draw under-bridge wrecks beneath the bridge orthograph and
     * on-bridge wrecks above it.
     *
     * @param coords          The Coordinates of the hex that the sprites should be drawn for.
     * @param graphics2D      The Graphics object for this board.
     * @param spriteArrayList The complete list of all IsometricWreckSprite on the board.
     * @param onBridge        When true, only draw wrecks of entities on a bridge; when false, only draw the rest.
     */
    private synchronized void drawIsometricWreckSpritesForHex(Coords coords, Graphics2D graphics2D,
          ArrayList<IsometricWreckSprite> spriteArrayList, boolean onBridge) {
        Rectangle view = graphics2D.getClipBounds();
        for (IsometricWreckSprite sprite : spriteArrayList) {
            Coords spritePosition = sprite.getPosition();
            if (spritePosition.equals(coords) && view.intersects(sprite.getBounds()) && !sprite.isHidden()
                  && EntityWreckHelper.entityOnBridge(sprite.getEntity()) == onBridge) {
                if (!sprite.isReady()) {
                    sprite.prepare();
                }
                sprite.drawOnto(graphics2D, sprite.getBounds().x, sprite.getBounds().y, boardPanel, false);
            }
        }
    }

    /**
     * Draws a translucent sprite without any of the companion graphics, if it is in the current view. This is used only
     * when performing isometric rending. This function is used to show units (with 50% transparency) that are hidden
     * behind a hill.
     * <p>
     * TODO: Optimize this function so that it is only applied to sprites that are actually hidden. This
     *  implementation performs the second rendering for all sprites.
     */
    private void drawIsometricSprites(Graphics2D graphics2D, Collection<IsometricSprite> spriteArrayList) {
        Rectangle view = graphics2D.getClipBounds();
        for (IsometricSprite sprite : spriteArrayList) {
            // This can potentially be an expensive operation
            Rectangle spriteBounds = sprite.getBounds();
            if (view.intersects(spriteBounds) && !sprite.isHidden()) {
                if (!sprite.isReady()) {
                    sprite.prepare();
                }
                sprite.drawOnto(graphics2D, spriteBounds.x, spriteBounds.y, boardPanel, true);
            }
        }
    }

    /**
     * Draws a sprite, if it is in the current view
     */


    /**
     * Checks if a deployment indicator (yellow or cyan hex border) should be drawn for the given hex and draws it.
     *
     * @param graphics2D The graphics to draw to
     * @param coords     The hex coords of the hex to check
     */
    private void drawDeployment(Graphics2D graphics2D, Coords coords) {
        clientState.drawDeployment(graphics2D, coords);
    }

    /** Draws the deploying entity's legal deployment borders for every hex overlapping the clip. */


    /**
     * Draw indicators for the deployment zones of all players
     */


    /**
     * Returns how far into a background tile the first tile of a clip area starts. The clip origin can be negative
     * when the board is scrolled against an edge, so this uses {@link Math#floorMod(int, int)}: a plain {@code %} would
     * return a negative offset and an invalid subimage request.
     *
     * @param clipStart the x or y origin of the clip area, which may be negative
     * @param tileSize  the tile width or height
     *
     * @return the offset into the tile, from {@code 0} to {@code tileSize - 1}
     */
    static int tileOffset(int clipStart, int tileSize) {
        return Math.floorMod(clipStart, tileSize);
    }

    /**
     * Draw a layer of a solid color (alpha possible) on the hex at {@link Point} no padding by default
     */
    void drawHexLayer(Graphics2D graphics2D, Color color) {
        drawHexLayer(graphics2D, color, false, false);
    }

    /**
     * Draw a layer of a solid color (alpha possible) on the hex at {@link Point} with some padding around the border
     */
    void drawHexLayer(Graphics2D graphics2D, Color color, boolean outOfFOV, boolean reverseStripes) {
        graphics2D.setColor(color);

        // create stripe effect for FOV darkening but not for colored weapon ranges
        int fogStripes = GUIP.getFovStripes();

        if (outOfFOV && fogStripes > 0) {
            // totally transparent here hurts the eyes
            GradientPaint gradientPaint = getGradientPaint(color, (float) fogStripes, reverseStripes);
            graphics2D.setPaint(gradientPaint);
        }

        Composite svComposite = graphics2D.getComposite();
        graphics2D.setComposite(AlphaComposite.SrcAtop);
        graphics2D.fillRect(0, 0, hex_size.width, hex_size.height);
        graphics2D.setComposite(svComposite);
    }

    private static GradientPaint getGradientPaint(Color startingColor, float fogStripes, boolean reversed) {
        return BoardClientState.getGradientPaint(startingColor, fogStripes, reversed);
    }

    public void drawHexBorder(Graphics2D graphics2D, Color color, double padding, double lineWidth) {
        clientState.drawHexBorder(graphics2D, color, padding, lineWidth);
    }

    public void drawHexBorder(Graphics2D graphics2D, Point point, Color col, double pad, double lineWidth) {
        clientState.drawHexBorder(graphics2D, point, col, pad, lineWidth);
    }

    /**
     * Draw an outline around the hex at {@link Point} no padding and a width of 1
     */
    private void drawHexBorder(Graphics2D graphics2D, Point point, Color color) {
        clientState.drawHexBorder(graphics2D, point, color);
    }

    /**
     * returns the weapon selected in the mek display, or null if none selected, or it is not artillery or null if the
     * selected entity is not owned
     */
    public Mounted<?> getSelectedArtilleryWeapon() {
        return clientState.getSelectedArtilleryWeapon();
    }


    /**
     * Draws hex borders for highlighted entity hexes (Nova CEWS network dialog).
     *
     * @param graphics The graphics object to draw on
     */


    /** Hazard-stripe yellow for the bold demolition charge selection outline. */
    private static final Color DEMO_CHARGE_HAZARD_COLOR = new Color(255, 213, 0);

    /**
     * Draws the outline around demolition charge hexes selected in the Detonate Charges dialog. With the hazard-outline
     * client setting on (default), each hex gets a bold yellow/black hazard-stripe border (a thick black base with a
     * yellow dashed line over it). With the setting off, it falls back to the same plain border the generic entity
     * highlight uses.
     *
     * @param graphics The graphics object to draw on
     */


    /**
     * Draw the orbital bombardment attacks on the board view
     *
     * @param boardGraphics The graphics object to draw on
     */


    /**
     * Display artillery modifier in retargeted hexes
     */


    /** The same owner-visible attacks and selected-weapon modifiers feed both views. */


    /**
     * Writes "MINEFIELD" in minefield hexes...
     */


    /**
     * Draws an indicator on every hex holding a demolition charge set by the local player, so the player can keep track
     * of armed charges until they are touched off (TO:AUE p.152). Charges are only visible to their owner.
     *
     * @param graphics2D the graphics context to draw on
     */


    private static final Color DEMO_CHARGE_OUTLINE_COLOR = new Color(0, 0, 0, 200);

    /**
     * Draws the armed-charge marker: a crosshair centered in the hex to clearly mark the rigged hex, with the damage
     * label on a dark backing pill below it so it stays readable over any terrain and is clearly distinct from unit
     * status tags.
     *
     * @param graphics2D  the graphics context to draw on
     * @param hexLocation the pixel location of the hex
     * @param label       the label text
     */


    /**
     * Draws a crosshair: a circle with four tick lines extending outward at the cardinal points and a center dot.
     *
     * @param graphics2D the graphics context to draw on
     * @param centerX    the x pixel coordinate of the crosshair center
     * @param centerY    the y pixel coordinate of the crosshair center
     * @param radius     the circle radius in pixels
     * @param tickLength the length of the tick lines in pixels
     */


    private void drawCenteredString(String string, int x, int y, Font font, Graphics2D graphics2D) {
        clientState.drawCenteredString(string, x, y, font, graphics2D);
    }

    /**
     * Draws a single combined turn label for all the bot artillery heat-map markers stacked on one hex, so the text is
     * visible in screenshots and not just in the hover text. Several tubes can target the same hex with different flight
     * times, so the distinct values are merged into one label rather than drawn over each other: firing markers count
     * down the turns until impact ({@code T-2}, joined as {@code T-1/2} when they differ, {@code SPLASH} when the only
     * one is this turn), and otherwise a predicted-position hex shows the prediction's turn ({@code T<turn>}). No-op when
     * no
     * heat-map markers are given.
     *
     * @param heatMapMarkers The heat-map markers drawn on this hex
     * @param graphics2D     The hex graphics context
     * @param scale          The current board scale
     */


    /**
     * @param values The values to render
     *
     * @return The values joined with a slash, e.g. {@code 1/2}
     */


    /**
     * The cold-to-hot diverging color ramp for predicted-position heat-map markers: navy blue (coldest, a single
     * enemy converging on the hex) through light blue, light gray, and light orange to crimson red (hottest, many
     * enemies converging).
     */
    private static final Color[] HEAT_MAP_COLOR_RAMP = {
          new Color(0, 0, 128),      // navy blue - coldest
          new Color(102, 178, 255),  // light blue
          new Color(220, 220, 220),  // light gray (neutral middle)
          new Color(255, 178, 102),  // light orange
          new Color(220, 20, 60)     // crimson red - hottest
    };

    /** The enemy count at which a predicted-position heat-map hex is drawn fully hot (crimson). */
    private static final int HEAT_MAP_MAX_HEAT_UNITS = 5;

    /** Opacity of the predicted-position heat-map color fill, so the underlying terrain still shows through. */
    private static final float HEAT_MAP_FILL_ALPHA = 0.55f;

    /**
     * Extracts the colon-separated heat-map control token (the {@code <turn>:<heat>:<kind>} part up to the first
     * space) from a heat-map marker's info text. See {@link SpecialHexDisplay#HEAT_MAP_PREFIX}.
     *
     * @param info The marker's info text
     *
     * @return The control token (without the prefix), or an empty string if there is none
     */


    /**
     * @param specialHexDisplay A special hex display being drawn
     *
     * @return {@code true} if this is any bot artillery heat-map marker (predicted-position or firing)
     */


    /**
     * @param specialHexDisplay A special hex display being drawn
     *
     * @return {@code true} if this is a predicted-position heat-map marker (painted as a cold-to-hot color fill),
     *       {@code false} for a firing marker or any non-heat-map display
     */


    /**
     * @param info A heat-map marker's info text
     *
     * @return The number of enemies predicted to converge on the hex (the marker's heat), or 1 if it cannot be parsed
     */


    /**
     * Maps a predicted hex's heat (number of enemies converging on it) to a color on the cold-to-hot diverging ramp:
     * one enemy is navy blue (coldest) and {@link #HEAT_MAP_MAX_HEAT_UNITS} or more is crimson red (hottest), with the
     * intermediate counts interpolated through the ramp.
     *
     * @param heatUnits The number of enemies converging on the hex
     *
     * @return The color to paint the hex
     */


    /**
     * Paints a predicted-position heat-map marker as a cold-to-hot translucent fill over the whole hex (navy = one
     * enemy converging, crimson = many), so the predicted enemy concentration reads at a glance and cools as units
     * are destroyed. No-op for any other special hex display.
     *
     * @param specialHexDisplay The special hex display being drawn
     * @param graphics2D        The hex graphics context
     * @param scale             The current board scale
     */


    /** Color of the artillery drift line drawn from a targeted hex to where the round actually landed. */
    private static final Color ARTILLERY_DRIFT_LINE_COLOR = new Color(255, 191, 0);

    /**
     * Draws a thin dashed line from each visible artillery miss marker's targeted hex to the hex the round actually
     * drifted to, with an arrowhead at the landing hex, so the drift reads at a glance (in addition to the combat
     * report). Only drawn for drift markers currently shown to the local player.
     *
     * @param graphics2D The board graphics context, in board pixel space at the current scale
     */


    /**
     * Draws a single drift line, with an arrowhead at the landing hex, between the centers of two hexes.
     *
     * @param graphics2D  The board graphics context
     * @param targetedHex The hex that was targeted (line origin)
     * @param landingHex  The hex the round drifted to (arrowhead end)
     */


    @Override
    public BufferedImage getEntireBoardImage(boolean ignoreUnits, boolean useBaseZoom) {
        // Set zoom to base, so we get a consistent board image
        int oldZoom = zoomIndex;
        if (useBaseZoom) {
            zoomIndex = BASE_ZOOM_INDEX;
            zoom();
        }

        BufferedImage entireBoard = new BufferedImage(boardSize.width, boardSize.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D boardGraph = (Graphics2D) entireBoard.getGraphics();
        boardGraph.setClip(0, 0, boardSize.width, boardSize.height);
        UIUtil.setHighQualityRendering(boardGraph);

        if (shadowMap == null) {
            shadowMap = shadowHelper.updateShadowMap();
        }

        // Draw hexes
        drawHexes(boardGraph, new Rectangle(boardSize), ignoreUnits);

        // If we aren't ignoring units, draw everything else
        if (!ignoreUnits) {
            drawTacticalLayers(boardGraph, true);
        }
        boardGraph.dispose();

        // Restore the zoom setting
        zoomIndex = oldZoom;
        zoom();

        return entireBoard;
    }

    private void drawHexes(Graphics2D graphics2D, Rectangle view) {
        drawHexes(graphics2D, view, false);
    }

    /**
     * Redraws all hexes in the specified rectangle
     */
    private void drawHexes(Graphics2D graphics2D, Rectangle view, boolean saveBoardImage) {
        drawHexes(graphics2D, view, saveBoardImage, true);
    }

    private void drawHexes(Graphics2D graphics2D, Rectangle view, boolean saveBoardImage,
          boolean includeUnits) {
        // only update visible hexes
        double scaledX = (int) (HEX_WC * scale);
        double scaledY = (int) (HEX_H * scale);

        int drawX = (int) (view.x / scaledX) - 1;
        int drawY = (int) (view.y / scaledY) - 1;

        int drawWidth = (int) (view.width / scaledX) + 3;
        int drawHeight = (int) (view.height / scaledY) + 3;

        Board board = game.getBoard(boardId);
        for (int y = 0; y < drawHeight; y++) {
            // Half of each row is one-half hex farther back (above) the other; draw those first
            for (int s = 0; s <= 1; s++) {
                for (int x = s; x < drawWidth + s + 1; x = x + 2) {
                    // For s == 0 the x coordinate MUST be an even number to get correct occlusion; drawX may be
                    // any int though
                    Coords coords = new Coords(x + drawX / 2 * 2, y + drawY);
                    Hex hex = board.getHex(coords);
                    if (hex != null) {
                        drawHex(coords, graphics2D, saveBoardImage);
                        drawOrthograph(coords, graphics2D);
                        // Under-bridge / no-bridge wrecks: drawn before the iso entity sprite (so a unit on the
                        // wreck paints on top) and before the second drawOrthograph (so a bridge deck paints over).
                        if (!saveBoardImage && GUIP.getShowWrecks()) {
                            drawIsometricWreckSpritesForHex(coords, graphics2D, isometricWreckSprites, false);
                        }
                        drawHexSpritesForHex(coords, graphics2D, clientState.behindTerrainHexSprites, includeUnits);
                        drawDeployment(graphics2D, coords);
                        drawOrthograph(coords, graphics2D);
                        // On-bridge wrecks: drawn after the bridge orthograph so they sit on the deck.
                        if (!saveBoardImage && GUIP.getShowWrecks()) {
                            drawIsometricWreckSpritesForHex(coords, graphics2D, isometricWreckSprites, true);
                        }
                        drawHexText(coords, hex, board, graphics2D);
                    }
                }
            }
        }

        if (!saveBoardImage && includeUnits) {
            // If we are using Isometric rendering, redraw the entity sprites at 50% transparent so sprites
            // hidden behind hills can still be seen by the user.
            drawIsometricSprites(graphics2D, isometricSprites);
        }
    }

    /** Draws base terrain independently of superposed and orthographic feature artwork. */
    private void drawBaseTerrain(Hex hex, Graphics2D graphics2D) {
        if (hex == null) {
            return;
        }
        Image baseImage = tileManager.baseFor(hex);
        drawBaseTerrain(hex, graphics2D, baseImage);
    }

    private void drawBaseTerrain(Hex hex, Graphics2D graphics, Image image) {
        BoardArtwork.drawBaseTerrain(hex, graphics, image, getScaledImage(image, true),
              getScaledImage(tileManager.getHexMask(), true), scale);
    }

    /**
     * Draws a hex onto the board buffer. This assumes that drawRect is current, and does not check if the hex is
     * visible.
     */
    /**
     * Checks if options for darkening and highlighting are turned on: If there is no LOS from currently selected
     * hex/entity, then darkens hex c. If there is a LOS from the hex c to the selected hex/entity, then hex c is
     * colored according to distance.
     *
     * @param boardGraph The board on which we paint.
     * @param c          Hex that is being processed.
     */
    private boolean drawFieldOfView(Graphics2D boardGraph, Coords c) {
        BoardFieldOfView.Hex result = fovHighlightingAndDarkening.evaluate(c);
        if (result.tint() != 0) {
            Color tint = new Color(result.tint(), true);
            if (result.visibility() == BoardFieldOfView.Visibility.ORIGIN) {
                drawHexBorder(boardGraph, new Point(0, 0), tint, 0, 7);
            } else {
                drawHexLayer(boardGraph, tint,
                      result.visibility() == BoardFieldOfView.Visibility.BLOCKED, GUIP.getFovSpottingMode());
            }
        }
        return result.hasLineOfSight();
    }

    private void drawHex(Coords coords, Graphics boardGraph, boolean saveBoardImage) {
        if (!game.getBoard(boardId).contains(coords)) {
            return;
        }

        final Hex hex = game.getBoard(boardId).getHex(coords);
        if (hex == null) {
            return;
        }

        final Point hexLocation = getHexLocation(coords);
        PlanetaryConditions conditions = game.getPlanetaryConditions();

        // Check the cache to see if we already have the image
        HexImageCacheEntry cacheEntry = hexImageCache.get(coords);
        if ((cacheEntry != null) && !cacheEntry.needsUpdating) {
            boardGraph.drawImage(cacheEntry.hexImage, hexLocation.x, hexLocation.y, boardPanel);
            return;
        }

        int level = hex.getLevel();

        Image scaledImage;
        boolean dontCache = false;
        int imgWidth = (int) (HEX_W * scale);
        int imgHeight = (int) (HEX_H * scale);
        // Tactical capture does not draw the base tile or raised Legacy 2D sides.
        Image baseImage = tileManager.baseFor(hex);
        scaledImage = getScaledImage(baseImage, true);
        dontCache = animatedImages.contains(baseImage.hashCode());
        imgWidth = Math.min(imgWidth, scaledImage.getWidth(null));
        imgHeight = Math.min(imgHeight, scaledImage.getHeight(null));
        int largestLevelDiff = 0;
        for (int dir : allDirections) {
            Hex adjHex = game.getBoard(boardId).getHexInDir(coords, dir);
            if (adjHex != null) {
                largestLevelDiff = Math.max(largestLevelDiff, Math.abs(level - adjHex.getLevel()));
            }
        }
        imgHeight += (int) (verticalOffset * scale * largestLevelDiff);
        // If the base image isn't ready, we should signal a repaint and stop
        if ((imgWidth < 0) || (imgHeight < 0)) {
            boardPanel.repaint();
            return;
        }

        BufferedImage hexImage = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics2D = (Graphics2D) (hexImage.getGraphics());
        UIUtil.setHighQualityRendering(graphics2D);

        drawBaseTerrain(hex, graphics2D);

        // To place roads under the shadow map, some supers have to be drawn before the shadow map, otherwise the
        // supers are drawn after. Unfortunately the supers images themselves can't be checked for roads.
        List<Image> supers = tileManager.supersFor(hex);
        boolean supersUnderShadow = false;
        // A .board2 holds designs instead of the art tokens: test the art the tileset draws for them, as
        // HexTileset.assignMatch selects it, so a transition goes under the shadow map as its token did.
        Hex art = hex.getAppearance().isEmpty() ? hex : BoardEditorBlueprint.get().artwork(hex);
        if (hex.containsTerrain(Terrains.ROAD)
              || hex.containsTerrain(Terrains.WATER)
              || hex.containsTerrain(Terrains.PAVEMENT)
              || art.containsTerrain(Terrains.GROUND_FLUFF)
              || hex.containsTerrain(Terrains.ROUGH)
              || hex.containsTerrain(Terrains.RUBBLE)
              || hex.containsTerrain(Terrains.SNOW)) {
            supersUnderShadow = true;
            if (supers != null) {
                for (Image image : supers) {
                    if (animatedImages.contains(image.hashCode())) {
                        dontCache = true;
                    }
                    scaledImage = getScaledImage(image, true);
                    graphics2D.drawImage(scaledImage, 0, 0, boardPanel);
                }
            }
        }

        // Add the terrain & building shadows
        if (GUIP.getShadowMap() && (shadowMap != null)) {
            Point p1SRC = getHexLocationLargeTile(coords.getX(), coords.getY(), 1);
            Point p2SRC = new Point(p1SRC.x + HEX_W, p1SRC.y + HEX_H);
            Point p2DST = new Point(hex_size.width, hex_size.height);

            Composite svComp = graphics2D.getComposite();
            if (conditions.getLight().isDay()) {
                graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 0.55f));
            } else {
                graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 0.45f));
            }

            // paint the right slice from the big pic
            graphics2D.drawImage(shadowMap, 0, 0, p2DST.x, p2DST.y, p1SRC.x, p1SRC.y, p2SRC.x, p2SRC.y, null);
            graphics2D.setComposite(svComp);
        }

        if (!supersUnderShadow) {
            if (supers != null) {
                for (Image image : supers) {
                    if (image != null) {
                        if (animatedImages.contains(image.hashCode())) {
                            dontCache = true;
                        }
                        scaledImage = getScaledImage(image, true);
                        graphics2D.drawImage(scaledImage, 0, 0, boardPanel);
                    }
                }
            }
        }

        // Check for buildings and woods buried under their own shadows.
        if ((supers != null) && supersUnderShadow && (hex.containsTerrain(Terrains.BUILDING) || hex.containsTerrain(
              Terrains.WOODS))) {
            Image lastSuper = supers.getLast();
            scaledImage = getScaledImage(lastSuper, true);
            graphics2D.drawImage(scaledImage, 0, 0, boardPanel);
        }

        // AO Hex Shadow in this hex when a higher one is adjacent
        if (GUIP.getAOHexShadows()) {
            for (int dir : allDirections) {
                Shape ShadowShape = getElevationShadowArea(coords, dir);
                GradientPaint gpl = getElevationShadowGP(coords, dir);
                if ((ShadowShape != null) && (gpl != null)) {
                    graphics2D.setPaint(gpl);
                    graphics2D.fill(getElevationShadowArea(coords, dir));
                }
            }
        }

        // Orthographic = bridges
        List<Image> orthogonalImages = tileManager.orthographicFor(hex);
        if (orthogonalImages != null) {
            for (Image image : orthogonalImages) {
                if (animatedImages.contains(image.hashCode())) {
                    dontCache = true;
                }
            }
        }
        clientState.drawHexEffects(graphics2D, coords);

        // Darken the hex for nighttime, if applicable
        if (GUIP.getDarkenMapAtNight()
              && IlluminationLevel.determineIlluminationLevel(game, boardId, coords).isNone()
              && conditions.getLight().isDuskOrFullMoonOrMoonlessOrPitchBack()) {
            for (int x = 0;
                  x < hexImage.getWidth();
                  ++x) {
                for (int y = 0;
                      y < hexImage.getHeight();
                      ++y) {
                    hexImage.setRGB(x, y, getNightDarkenedColor(hexImage.getRGB(x, y)));
                }
            }
        }

        clientState.drawSpecialHexes(graphics2D, coords);

        // Hex text (coordinates, level/depth/height) is drawn separately in drawHexText()
        // so that it renders on top of bridge orthographs

        // Used to make the following draw calls shorter
        int s21 = (int) (21 * scale);
        int s71 = (int) (71 * scale);
        int s35 = (int) (35 * scale);
        int s36 = (int) (36 * scale);
        int s62 = (int) (62 * scale);
        int s83 = (int) (83 * scale);

        Point p1 = new Point(s62, 0);
        Point p2 = new Point(s21, 0);
        Point p3 = new Point(s83, s35);
        Point p4 = new Point(s83, s36);
        Point p5 = new Point(s62, s71);
        Point p6 = new Point(s21, s71);
        Point p7 = new Point(0, s36);
        Point p8 = new Point(0, s35);

        graphics2D.setColor(Color.black);
        graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        // draw elevation borders
        if (drawElevationLine(coords, 0)) {
            drawIsometricElevation(coords, Color.GRAY, p1, p2, 0, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(s21, 0, s62, 0);
            }
        }

        if (drawElevationLine(coords, 1)) {
            drawIsometricElevation(coords, Color.DARK_GRAY, p3, p1, 1, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(s62, 0, s83, s35);
            }
        }

        if (drawElevationLine(coords, 2)) {
            drawIsometricElevation(coords, Color.LIGHT_GRAY, p4, p5, 2, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(s83, s36, s62, s71);
            }
        }

        if (drawElevationLine(coords, 3)) {
            drawIsometricElevation(coords, Color.GRAY, p6, p5, 3, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(s62, s71, s21, s71);
            }
        }

        if (drawElevationLine(coords, 4)) {
            drawIsometricElevation(coords, Color.DARK_GRAY, p7, p6, 4, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(s21, s71, 0, s36);
            }
        }

        if (drawElevationLine(coords, 5)) {
            drawIsometricElevation(coords, Color.LIGHT_GRAY, p8, p2, 5, graphics2D);
            if (GUIP.getLevelHighlight()) {
                graphics2D.drawLine(0, s35, s21, 0);
            }

        }
        // When the board image is saved, it shouldn't be spoiled by drawing LOS effects
        boolean hasLoS = saveBoardImage || drawFieldOfView(graphics2D, coords);

        drawMapSheetBorders(graphics2D, coords);
        if (!hasLoS && GUIP.getFovGrayscale()) {
            // rework the pixels to grayscale
            for (int x = 0;
                  x < hexImage.getWidth();
                  x++) {
                for (int y = 0;
                      y < hexImage.getHeight();
                      y++) {
                    int rgb = hexImage.getRGB(x, y);
                    int rd = (rgb >> 16) & 0xFF;
                    int gr = (rgb >> 8) & 0xFF;
                    int bl = (rgb & 0xFF);
                    int al = (rgb >> 24);

                    int grayLevel = (rd + gr + bl) / 3;
                    int gray = (al << 24) + (grayLevel << 16) + (grayLevel << 8) + grayLevel;
                    hexImage.setRGB(x, y, gray);
                }
            }
        }

        clientState.drawHexPlugins(graphics2D, coords);
        graphics2D.dispose();

        cacheEntry = new HexImageCacheEntry(hexImage);
        if (!dontCache) {
            hexImageCache.put(coords, cacheEntry);
        }
        boardGraph.drawImage(cacheEntry.hexImage, hexLocation.x, hexLocation.y, boardPanel);
    }

    /**
     * Draws an orthographic hex onto the board buffer. This assumes that drawRect is current, and does not check if the
     * hex is visible.
     */
    private void drawOrthograph(Coords coords, Graphics boardGraph) {
        if (!game.getBoard(boardId).contains(coords)) {
            return;
        }

        final Hex oHex = game.getBoard(boardId).getHex(coords);
        final Point oHexLoc = getHexLocation(coords);
        // Adjust the draw height for bridges according to their elevation
        int elevOffset = oHex.terrainLevel(Terrains.BRIDGE_ELEV);

        int orthogonalX = oHexLoc.x;
        int orthogonalY = oHexLoc.y - (int) (verticalOffset * scale * elevOffset);
        if (tileManager.orthographicFor(oHex) != null) {
            for (Image image : tileManager.orthographicFor(oHex)) {
                BufferedImage scaledImage = ImageUtil.createAcceleratedImage(getScaledImage(image, true));

                // Darken the hex for nighttime, if applicable
                PlanetaryConditions conditions = game.getPlanetaryConditions();
                if (GUIP.getDarkenMapAtNight() && IlluminationLevel.determineIlluminationLevel(game, boardId, coords)
                      .isNone() && conditions.getLight().isDuskOrFullMoonOrMoonlessOrPitchBack()) {
                    for (int x = 0;
                          x < Objects.requireNonNull(scaledImage).getWidth(null);
                          ++x) {
                        for (int y = 0;
                              y < scaledImage.getHeight();
                              ++y) {
                            scaledImage.setRGB(x, y, getNightDarkenedColor(scaledImage.getRGB(x, y)));
                        }
                    }
                }

                // draw orthogonal
                boardGraph.drawImage(scaledImage, orthogonalX, orthogonalY, boardPanel);
            }
        }
    }

    /**
     * Draws hex text clientState.overlays (coordinates, level, depth, height, foliage, invalid hex info) directly to the board
     * graphics. This is called after drawOrthograph so that text renders on top of bridge images.
     */
    private void drawHexText(Coords coords, Hex hex, Board board, Graphics2D boardGraph) {
        final Point hexLocation = getHexLocation(coords);
        int hexX = hexLocation.x;
        int hexY = hexLocation.y;
        BoardHexText.draw(boardGraph, hexLocation, hex_size.width,
              BoardHexText.capture(coords, hex, board, scale, font_hexNumber, font_elev));
        if (displayInvalidHexInfo && !hex.isValid(null)) {
            BoardHexText.drawInvalid(boardGraph, new Point(hexX, hexY), scale);
        }
    }

    /**
     * Draws the Isometric elevation for the hex at the given coordinates (coords) on the side indicated by the
     * direction (direction). This method only draws a triangle for the elevation, the companion triangle representing
     * the adjacent hex is also needed. The two triangles when drawn together make a complete rectangle representing the
     * complete elevated hex side.
     * <p>
     * By drawing the elevated hex as two separate triangles we avoid clipping problems with other hexes because the
     * lower elevation is rendered before the higher elevation. Thus, any hexes that have a higher elevation than the
     * lower hex will overwrite the lower hex.
     * <p>
     * The Triangle for each hex side is formed by points point1, point2, and p3. Where point1 and point2 are the
     * original hex edges, and p3 has the same X value as point1, but the y value has been increased (or decreased)
     * based on the difference in elevation between the given hex and the adjacent hex.
     *
     * @param coords     Coordinates of the source hex.
     * @param color      Color to use for the elevation polygons.
     * @param point1     The First point on the edge of the hex.
     * @param point2     The second point on the edge of the hex.
     * @param direction  The side of the hex to have the elevation drawn on.
     * @param graphics2D {@link Graphics2D} 2D Graphics Context
     */
    private void drawIsometricElevation(Coords coords, Color color, Point point1, Point point2, int direction,
          Graphics graphics2D) {
        final Hex dest = game.getBoard(boardId).getHexInDir(coords, direction);
        final Hex src = game.getBoard(boardId).getHex(coords);

        if (GUIP.getFloatingIso() || verticalOffset == 0) {
            return;
        }

        // Pad polygon size slightly to avoid rounding errors from scale float.
        int fudge = -1;
        if ((direction == 2) || (direction == 4) || (direction == 3)) {
            fudge = 1;
        }

        final int elev = src.getLevel();
        // If the Destination is null, draw the complete elevation side.
        if ((dest == null) && (elev > 0) && ((direction == 2) || (direction == 3) || (direction == 4))) {

            // Determine the depth of the edge that needs to be drawn.
            int height = elev;
            Hex southHex = game.getBoard(boardId).getHexInDir(coords, 3);
            if ((direction != 3) && (southHex != null) && (elev > southHex.getLevel())) {
                height = elev - southHex.getLevel();
            }
            int scaledHeight = (int) (verticalOffset * scale * height);

            Polygon polygon = new Polygon(new int[] { point1.x, point2.x, point2.x, point1.x },
                  new int[] { point1.y + fudge, point2.y + fudge, point2.y + scaledHeight, point1.y + scaledHeight },
                  4);
            graphics2D.setColor(color);
            graphics2D.drawPolygon(polygon);
            graphics2D.fillPolygon(polygon);

            graphics2D.setColor(Color.BLACK);
            if ((direction == 2) || (direction == 4)) {
                graphics2D.drawLine(point1.x, point1.y, point1.x, point1.y + scaledHeight);
            }
            return;
        } else if (dest == null) {
            return;
        }

        int delta = elev - dest.getLevel();
        // Don't draw the elevation if there is no exposed edge for the player to see.
        if ((delta == 0) || (((direction == 0) || (direction == 1) || (direction == 5)) && (delta > 0)) || (((direction
              == 2)
              || (
              direction == 3) || (direction == 4)) && (delta < 0))) {
            return;
        }

        if ((direction == 2) || (direction == 3) || (direction == 4)) {
            int scaledDelta = (int) (verticalOffset * scale * delta);
            Point p3 = new Point(point1.x, point1.y + scaledDelta + fudge);

            Polygon polygon = new Polygon(new int[] { point1.x, point2.x, point2.x, point1.x },
                  new int[] { point1.y + fudge, point2.y + fudge, point2.y + fudge + scaledDelta,
                              point1.y + fudge + scaledDelta },
                  4);

            if ((point1.y + fudge) < 0) {
                LOGGER.info("Negative (P1) Y value (Fudge)!: {}", (point1.y + fudge));
            }

            if ((point2.y + fudge) < 0) {
                LOGGER.info("Negative (P2) Y value (Fudge)!: {}", (point2.y + fudge));
            }

            if ((point2.y + fudge + scaledDelta) < 0) {
                LOGGER.info("Negative (P2) Y value!: {}", (point2.y + fudge + scaledDelta));
            }

            if ((point1.y + fudge + scaledDelta) < 0) {
                LOGGER.info("Negative (P1) Y value!: {}", (point1.y + fudge + scaledDelta));
            }
            graphics2D.setColor(color);
            graphics2D.drawPolygon(polygon);
            graphics2D.fillPolygon(polygon);

            graphics2D.setColor(Color.BLACK);
            if (direction == 2 || direction == 4) {
                graphics2D.drawLine(point1.x, point1.y, p3.x, p3.y);
            }
        }
    }

    /**
     * Returns true if an elevation line should be drawn between the starting hex and the hex in the direction
     * specified. Results should be transitive, that is, if a line is drawn in one direction, it should be drawn in the
     * opposite direction as well.
     */
    private boolean drawElevationLine(Coords src, int direction) {
        return HexDrawUtilities.hasElevationBorder(game.getBoard(boardId), src, direction);
    }

    /**
     * Given an int-packed RGB value, apply a modifier for the light level and return the result.
     *
     * @param rgb int-packed ARGB value.
     *
     * @return An int-packed ARGB value, which is an adjusted value of the input, based on the light level
     */
    public int getNightDarkenedColor(int rgb) {
        int rd = (rgb >> 16) & 0xFF;
        int gr = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        int al = (rgb >> 24);

        switch (game.getPlanetaryConditions().getLight()) {
            case FULL_MOON:
            case MOONLESS:
                rd = rd / 4; // 1/4 red
                gr = gr / 4; // 1/4 green
                bl = bl / 2; // half blue
                break;
            case PITCH_BLACK:
                int gy = (rd + gr + bl) / 16;
                if (Math.random() < 0.3) {
                    gy = gy * 4 / 5;
                }
                if (Math.random() < 0.3) {
                    gy = gy * 5 / 4;
                }
                rd = gy + rd / 5;
                gr = gy + gr / 5;
                bl = gy + bl / 5;
                break;
            case DUSK_DAWN:
                bl = bl * 3 / 4;
                break;
            default:
                break;
        }

        return (al << 24) + (rd << 16) + (gr << 8) + bl;
    }

    /**
     * Generates a Shape drawing area for the hex shadow effect in a lower hex when a higher hex is found in direction.
     */
    private @Nullable Shape getElevationShadowArea(Coords src, int direction) {
        final Hex srcHex = game.getBoard(boardId).getHex(src);
        final Hex destHex = game.getBoard(boardId).getHexInDir(src, direction);

        // When at the board edge, create a shadow in hexes of level < 0
        if (destHex == null) {
            if (srcHex.getLevel() >= 0) {
                return null;
            }
        } else {
            // no shadow area when the current hex is not lower than the next hex in direction
            if (srcHex.getLevel() >= destHex.getLevel()) {
                return null;
            } else if (GUIP.getHexInclines()
                  && (destHex.getLevel() - srcHex.getLevel() < 2)
                  && !destHex.hasCliffTopTowards(srcHex)) {
                return null;
            }
        }

        return AffineTransform.getScaleInstance(scale, scale)
              .createTransformedShape(HexDrawUtilities.getHexBorderArea(direction, HexDrawUtilities.CUT_BORDER, 36));
    }

    /**
     * Generates a fill gradient which is rotated and aligned properly for the drawing area for a hex shadow effect in a
     * lower hex.
     */
    private GradientPaint getElevationShadowGP(Coords src, int direction) {
        final Hex srcHex = game.getBoard(boardId).getHex(src);
        final Hex destHex = game.getBoard(boardId).getHexInDir(src, direction);

        if (destHex == null) {
            return null;
        }

        int levelDifference = destHex.getLevel() - srcHex.getLevel();
        // the shadow strength depends on the level difference, but only to a maximum difference of 3 levels
        levelDifference = Math.min(levelDifference * 5, 15);

        Color c1 = new Color(30, 30, 50, 255); // dark end of shadow
        Color c2 = new Color(50, 50, 70, 0); // light end of shadow

        Point2D p1 = new Point2D.Double(41.5, -25 + levelDifference);
        Point2D p2 = new Point2D.Double(41.5, 8.0 + levelDifference);

        AffineTransform t = new AffineTransform();
        t.scale(scale, scale);
        t.rotate(Math.toRadians(direction * 60), 41.5, 35.5);
        t.transform(p1, p1);
        t.transform(p2, p2);

        return new GradientPaint(p1, c1, p2, c2);
    }

    /**
     * @return The absolute position of the upper-left hand corner of the hex graphic
     */
    private Point getHexLocation(int x, int y, boolean ignoreElevation) {
        float elevationAdjust = 0.0f;

        Hex hex = game.getBoard(boardId).getHex(x, y);
        if ((hex != null) && !ignoreElevation) {
            elevationAdjust = hex.getLevel() * verticalOffset * scale * -1.0f;
        }
        int yPosition = (y * (int) (HEX_H * scale)) + ((x & 1) == 1 ? (int) ((HEX_H / 2.0f) * scale) : 0);
        return new Point(x * (int) (HEX_WC * scale), yPosition + (int) elevationAdjust);
    }

    /**
     * For large tile texture: Returns the absolute position of the upper-left hand corner of the hex graphic When using
     * large tiles multiplying the rounding errors from the (int) cast must be avoided however this cannot be used for
     * small tiles as it will make gaps appear between hexes This will not factor in Isometric as this would be
     * incorrect for large tiles
     */
    static Point getHexLocationLargeTile(int x, int y, float tileScale) {
        return BoardArtwork.largeTileLocation(x, y, tileScale);
    }

    private Point getHexLocationLargeTile(int x, int y) {
        return getHexLocationLargeTile(x, y, scale);
    }

    public Point getHexLocation(Coords coords) {
        return coords == null ? null : getHexLocation(coords.getX(), coords.getY(), false);
    }

    /**
     * Returns the absolute position of the centre of the hex graphic
     */
    private Point getCentreHexLocation(int x, int y, boolean ignoreElevation) {
        Point p = getHexLocation(x, y, ignoreElevation);
        p.x += (int) Math.floor((HEX_W / 2.0f) * scale);
        p.y += (int) Math.floor((HEX_H / 2.0f) * scale);
        return p;
    }

    public Point getCentreHexLocation(Coords coords) {
        return getCentreHexLocation(coords.getX(), coords.getY(), false);
    }

    public Point getCentreHexLocation(Coords coords, boolean ignoreElevation) {
        return getCentreHexLocation(coords.getX(), coords.getY(), ignoreElevation);
    }

    /**
     * Draws a crosshair-with-circle (bullseye) marker at the given hex for the ruler tool. The marker scales with the
     * current board zoom level so it remains visible at all zoom levels.
     */


    public void drawRuler(Coords startCoords, Coords endCoords, Color startColor, Color endColor) {
        clientState.drawRuler(startCoords, endCoords, startColor, endColor);
    }

    public Coords getRulerStart() {
        return clientState.getRulerStart();
    }

    public Coords getRulerEnd() {
        return clientState.getRulerEnd();
    }

    @Override
    public Coords getCoordsAt(Point point) {
        // We must account for the board translation to add padding
        point.x -= HEX_W;
        point.y -= HEX_H;

        // base values
        int x = point.x / (int) (HEX_WC * scale);
        int y = point.y / (int) (HEX_H * scale);
        // correction for the displaced odd columns
        if ((float) point.y / (scale * HEX_H) - y < 0.5) {
            y -= x % 2;
        }

        // check the surrounding hexes if they contain point checking at most 3 hexes would be sufficient but which
        // ones? This is failsafe.
        Coords coords = new Coords(x, y);
        if (!HexDrawUtilities.getHexFull(getHexLocation(coords), scale).contains(point)) {
            boolean hasMatch = false;
            for (int direction = 0;
                  direction < 6 && !hasMatch;
                  direction++) {
                Coords translated = coords.translated(direction);
                if (HexDrawUtilities.getHexFull(getHexLocation(translated), scale).contains(point)) {
                    coords = translated;
                    hasMatch = true;
                }
            }
        }

        // When using isometric rendering, a lower hex can obscure the
        // normal hex. Iterate over all hexes from highest to lowest,
        // looking for a hex that contains the selected mouse click point.
        final int minElev = Math.min(0, game.getBoard(boardId).getMinElevation());
        final int maxElev = Math.max(0, game.getBoard(boardId).getMaxElevation());
        final int delta = (int) Math.ceil(((double) maxElev - minElev) / 3.0f);
        final int minHexSpan = Math.max(y - delta, 0);
        final int maxHexSpan = Math.min(y + delta, game.getBoard(boardId).getHeight());
        for (int elev = maxElev;
              elev >= minElev;
              elev--) {
            for (int i = minHexSpan;
                  i <= maxHexSpan;
                  i++) {
                for (int dx = -1;
                      dx < 2;
                      dx++) {
                    Coords c1 = new Coords(x + dx, i);
                    Hex hexAlt = game.getBoard(boardId).getHex(c1);
                    if (HexDrawUtilities.getHexFull(getHexLocation(c1), scale).contains(point)
                          && (hexAlt != null)
                          && (hexAlt.getLevel() == elev)) {
                        // Return immediately with the highest hex found.
                        return c1;
                    }
                }
            }
        }
        // nothing found
        return new Coords(-1, -1);
    }

    public void setTooltipProvider(megamek.client.ui.clientGUI.boardview.toolTip.TWBoardViewTooltip provider) {
        boardViewToolTip = (point, target) -> provider.getTooltip(getCoordsAt(point), target);
    }

    public void setTooltipProvider(BoardViewTooltipProvider provider) {
        boardViewToolTip = provider;
    }

    public void redrawMovingEntity(Entity entity, Coords position, int facing, int elevation) {
        Integer entityId = entity.getId();
        ArrayList<Integer> spriteKey = getIdAndLoc(entityId, -1);
        EntitySprite sprite = entitySpriteIds.get(spriteKey);
        IsometricSprite isoSprite = isometricSpriteIds.get(spriteKey);
        // We can ignore secondary locations for now, as we don't have moving multi-location entities (will need to
        // change for mobile structures)

        PriorityQueue<EntitySprite> newSprites;
        PriorityQueue<IsometricSprite> isoSprites;
        HashMap<ArrayList<Integer>, EntitySprite> newSpriteIds;
        HashMap<ArrayList<Integer>, IsometricSprite> newIsoSpriteIds;

        // Remove sprite for Entity, so it's not displayed while moving
        if (sprite != null) {
            removeSprite(sprite);
            newSprites = new PriorityQueue<>(entitySprites);
            newSpriteIds = new HashMap<>(entitySpriteIds);

            newSprites.remove(sprite);
            newSpriteIds.remove(spriteKey);

            entitySprites = newSprites;
            entitySpriteIds = newSpriteIds;
        }
        // Remove iso sprite for Entity, so it's not displayed while moving
        if (isoSprite != null) {
            removeSprite(isoSprite);
            isoSprites = new PriorityQueue<>(isometricSprites);
            newIsoSpriteIds = new HashMap<>(isometricSpriteIds);

            isoSprites.remove(isoSprite);
            newIsoSpriteIds.remove(spriteKey);

            isometricSprites = isoSprites;
            isometricSpriteIds = newIsoSpriteIds;
        }

        MovingEntitySprite mSprite = movingEntitySpriteIds.get(entityId);
        ArrayList<MovingEntitySprite> newMovingSprites = new ArrayList<>(movingEntitySprites);
        HashMap<Integer, MovingEntitySprite> newMovingSpriteIds = new HashMap<>(movingEntitySpriteIds);
        // Remove any old movement sprite
        if (mSprite != null) {
            newMovingSprites.remove(mSprite);
        }
        // Create new movement sprite
        if (isOnThisBord(entity)) {
            mSprite = new MovingEntitySprite(this, entity, position, facing, elevation);
            newMovingSprites.add(mSprite);
            newMovingSpriteIds.put(entityId, mSprite);
        }

        movingEntitySprites = newMovingSprites;
        movingEntitySpriteIds = newMovingSpriteIds;
    }

    public boolean isMovingUnits() {
        return !movingUnits.isEmpty();
    }

    /**
     * @param entityId     The Entity ID
     * @param secondaryLoc the secondary loc index, or -1 for Entities without secondary positions
     *
     * @return a Key value for the entitySpriteIds and isometricSprite maps. The List contains as the first element the
     *       Entity ID and as the second element its location ID: either -1 if the Entity has no secondary locations, or
     *       the index of its secondary location.
     */
    private ArrayList<Integer> getIdAndLoc(Integer entityId, int secondaryLoc) {
        ArrayList<Integer> idLoc = new ArrayList<>(2);
        idLoc.add(entityId);
        idLoc.add(secondaryLoc);
        return idLoc;
    }

    /**
     * Clears the sprite for an entity and prepares it to be re-drawn. Replaces the old sprite with the new! Takes a
     * reference to the Entity object before changes, in case it contained important state information, like DropShips
     * taking off (airborne DropShips lose their secondary hexes). Try to prevent annoying
     * ConcurrentModificationExceptions
     */
    public void redrawEntitySprites(Entity entity) {
        Integer entityId = entity.getId();

        // Remove sprites from backing sprite collections before modifying the entitySprites and isometricSprites.
        // Otherwise, orphaned clientState.overTerrainSprites or clientState.behindTerrainHexSprites can result.
        removeSprites(entitySprites);
        removeSprites(isometricSprites);

        // If the entity we are updating doesn't have a position, ensure we
        // remove all of its old sprites
        if (entity.getPosition() == null || !isOnThisBord(entity)) {
            Iterator<EntitySprite> spriteIter;

            // Remove Entity Sprites
            spriteIter = entitySprites.iterator();
            while (spriteIter.hasNext()) {
                EntitySprite sprite = spriteIter.next();
                if (sprite.getEntity().equals(entity)) {
                    spriteIter.remove();
                }
            }

            // Update ID -> Sprite map
            spriteIter = entitySpriteIds.values().iterator();
            while (spriteIter.hasNext()) {
                EntitySprite sprite = spriteIter.next();
                if (sprite.getEntity().equals(entity)) {
                    spriteIter.remove();
                }
            }

            Iterator<IsometricSprite> isoSpriteIter;

            // Remove IsometricSprites
            isoSpriteIter = isometricSprites.iterator();
            while (isoSpriteIter.hasNext()) {
                IsometricSprite sprite = isoSpriteIter.next();
                if (sprite.getEntity().equals(entity)) {
                    isoSpriteIter.remove();
                }
            }

            // Update ID -> Iso Sprite Map
            isoSpriteIter = isometricSpriteIds.values().iterator();
            while (isoSpriteIter.hasNext()) {
                IsometricSprite sprite = isoSpriteIter.next();
                if (sprite.getEntity().equals(entity)) {
                    isoSpriteIter.remove();
                }
            }
        }

        // Create a copy of the sprite list
        Queue<EntitySprite> newSprites = new PriorityQueue<>(entitySprites);
        HashMap<ArrayList<Integer>, EntitySprite> newSpriteIds = new HashMap<>(entitySpriteIds);
        Queue<IsometricSprite> isoSprites = new PriorityQueue<>(isometricSprites);
        HashMap<ArrayList<Integer>, IsometricSprite> newIsoSpriteIds = new HashMap<>(isometricSpriteIds);

        // Remove the sprites we are going to update
        EntitySprite sprite = entitySpriteIds.get(getIdAndLoc(entityId, -1));
        IsometricSprite isoSprite = isometricSpriteIds.get(getIdAndLoc(entityId, -1));
        if (sprite != null) {
            newSprites.remove(sprite);
        }

        if (isoSprite != null) {
            isoSprites.remove(isoSprite);
        }

        for (int secondaryPos : entity.getSecondaryPositions().keySet()) {
            sprite = entitySpriteIds.get(getIdAndLoc(entityId, secondaryPos));
            if (sprite != null) {
                newSprites.remove(sprite);
            }
            isoSprite = isometricSpriteIds.get(getIdAndLoc(entityId, secondaryPos));
            if (isoSprite != null) {
                isoSprites.remove(isoSprite);
            }
        }

        // Create the new sprites
        Coords position = entity.getPosition();
        boolean canSee = EntityVisibilityUtils.detectedOrHasVisual(getLocalPlayer(), game, entity);

        if ((position != null) && canSee && isOnThisBord(entity)) {
            // Add new EntitySprite
            // If no secondary positions, add a sprite for the central position
            if (entity.getSecondaryPositions().isEmpty()) {
                sprite = new EntitySprite(this, entity, -1, radarBlipImage);
                newSprites.add(sprite);
                newSpriteIds.put(getIdAndLoc(entityId, -1), sprite);
            } else {
                // Add all secondary position sprites, which includes a sprite for the central hex
                for (int secondaryPos : entity.getSecondaryPositions().keySet()) {
                    sprite = new EntitySprite(this, entity, secondaryPos, radarBlipImage);
                    newSprites.add(sprite);
                    newSpriteIds.put(getIdAndLoc(entityId, secondaryPos), sprite);
                }
            }

            // Add new IsometricSprite
            // If no secondary positions, add a sprite for the central position
            if (entity.getSecondaryPositions().isEmpty()) {
                isoSprite = new IsometricSprite(this, entity, -1, radarBlipImage);
                isoSprites.add(isoSprite);
                newIsoSpriteIds.put(getIdAndLoc(entityId, -1), isoSprite);
            } else {
                // Add all secondary position sprites, which includes a sprite for the central hex
                for (int secondaryPos : entity.getSecondaryPositions().keySet()) {
                    isoSprite = new IsometricSprite(this, entity, secondaryPos, radarBlipImage);
                    isoSprites.add(isoSprite);
                    newIsoSpriteIds.put(getIdAndLoc(entityId, secondaryPos), isoSprite);
                }
            }
        }

        // Update Sprite state with new collections
        entitySprites = newSprites;
        entitySpriteIds = newSpriteIds;
        isometricSprites = isoSprites;
        isometricSpriteIds = newIsoSpriteIds;
        addSprites(entitySprites);
        addSprites(isometricSprites);

    }

    /**
     * Clears all old entity sprites out of memory and sets up new ones.
     */
    public void redrawAllEntitySprites() {
        int numEntities = game.getNoOfEntities();
        // Prevent IllegalArgumentException
        numEntities = Math.max(1, numEntities);
        Queue<EntitySprite> newSprites = new PriorityQueue<>(numEntities);
        Queue<IsometricSprite> newIsometricSprites = new PriorityQueue<>(numEntities);
        Map<ArrayList<Integer>, EntitySprite> newSpriteIds = new HashMap<>(numEntities);
        Map<ArrayList<Integer>, IsometricSprite> newIsoSpriteIds = new HashMap<>(numEntities);

        ArrayList<WreckSprite> newWrecks = new ArrayList<>();
        ArrayList<IsometricWreckSprite> newIsometricWrecks = new ArrayList<>();

        for (Entity entity : clientState.getWrecks()) {
                WreckSprite wreckSprite;
                IsometricWreckSprite isometricWreckSprite;
                if (entity.getSecondaryPositions().isEmpty()) {
                    wreckSprite = new WreckSprite(this, entity, -1);
                    newWrecks.add(wreckSprite);
                    isometricWreckSprite = new IsometricWreckSprite(this, entity, -1);
                    newIsometricWrecks.add(isometricWreckSprite);
                } else {
                    for (int secondaryPos : entity.getSecondaryPositions().keySet()) {
                        wreckSprite = new WreckSprite(this, entity, secondaryPos);
                        newWrecks.add(wreckSprite);
                        isometricWreckSprite = new IsometricWreckSprite(this, entity, secondaryPos);
                        newIsometricWrecks.add(isometricWreckSprite);
                    }
                }
            }

        for (Entity entity : game.getEntitiesVector()) {
            if (entity.getPosition() == null || !isOnThisBord(entity)) {
                continue;
            }
            if ((getLocalPlayer() != null)
                  && game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)
                  && entity.getOwner().isEnemyOf(getLocalPlayer())
                  && !entity.hasSeenEntity(getLocalPlayer())
                  && !entity.hasDetectedEntity(getLocalPlayer())) {
                continue;
            }
            if ((getLocalPlayer() != null)
                  && game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)
                  && entity.getOwner().isEnemyOf(getLocalPlayer())
                  && entity.isHidden()) {
                continue;
            }
            if (entity.getSecondaryPositions().isEmpty()) {
                EntitySprite sprite = new EntitySprite(this, entity, -1, radarBlipImage);
                newSprites.add(sprite);
                newSpriteIds.put(getIdAndLoc(entity.getId(), -1), sprite);
                IsometricSprite isometricSprite = new IsometricSprite(this, entity, -1, radarBlipImage);
                newIsometricSprites.add(isometricSprite);
                newIsoSpriteIds.put(getIdAndLoc(entity.getId(), -1), isometricSprite);
            } else {
                for (int secondaryPos : entity.getSecondaryPositions().keySet()) {
                    EntitySprite sprite = new EntitySprite(this, entity, secondaryPos, radarBlipImage);
                    newSprites.add(sprite);
                    newSpriteIds.put(getIdAndLoc(entity.getId(), secondaryPos), sprite);

                    IsometricSprite isometricSprite = new IsometricSprite(this, entity, secondaryPos, radarBlipImage);
                    newIsometricSprites.add(isometricSprite);
                    newIsoSpriteIds.put(getIdAndLoc(entity.getId(), secondaryPos), isometricSprite);
                }
            }

        }

        removeSprites(entitySprites);
        removeSprites(isometricSprites);

        entitySprites = newSprites;
        entitySpriteIds = newSpriteIds;

        isometricSprites = newIsometricSprites;
        isometricSpriteIds = newIsoSpriteIds;

        addSprites(entitySprites);
        addSprites(isometricSprites);

        wreckSprites = newWrecks;
        isometricWreckSprites = newIsometricWrecks;

        scheduleRedraw();
    }

    /**
     * Moves the cursor to the new position, or hides it, if newPosition is null
     */
    private void moveCursor(CursorSprite cursor, Coords newPosition) {
        final Rectangle oldBounds = new Rectangle(cursor.getBounds());
        if (newPosition != null) {
            cursor.setHexLocation(newPosition);
        } else {
            cursor.setOffScreen();
        }
        // repaint affected area
        boardPanel.repaint(oldBounds);
        boardPanel.repaint(cursor.getBounds());
    }

    /**
     * Centers the board on the position of the selected unit, if any. Uses smooth centering if activated in the client
     * settings.
     */
    public void centerOnSelected() {
        if (isOnThisBord(getSelectedEntity())) {
            clientgui.showBoardView(boardId);
            centerOn(getSelectedEntity());
        }
    }

    /**
     * Centers the board on the position of the given unit. Uses smooth centering if activated in the client settings.
     * The given entity may be null, in which case nothing happens.
     *
     * @param entity The unit to center on.
     */
    public void centerOn(@Nullable Entity entity) {
        if (entity != null) {
            centerOnHex(entity.getPosition(), entity.getId());
        }
    }

    @Override
    public void centerOnHex(@Nullable Coords coords) {
        centerOnHex(coords, Entity.NONE);
    }

    private void centerOnHex(@Nullable Coords coords, int entityId) {
        if (coords == null) {
            return;
        }
        clientState.centerOn(coords, entityId);
    }

    private void applyCenterRequest(Coords coords) {
        // A native camera request must not construct or move the legacy viewport.
        if (scrollPane == null || (clientgui != null && GpuBoardWindow.isActiveFor(clientgui))) {
            stopSoftCentering();
            return;
        }

        if (GUIP.getSoftCenter()) {
            // Soft Centering:
            // set the target point
            Point p = getCentreHexLocation(coords);
            softCenterTarget.setLocation(p.x / boardSize.getWidth(), p.y / boardSize.getHeight());

            // adjust the target point because the board can't center on points too close to an edge
            double width = scrollPane.getViewport().getWidth();
            double height = scrollPane.getViewport().getHeight();
            double boardSizeWidth = boardSize.getWidth();
            double boardSizeHeight = boardSize.getHeight();

            double minX = (width / 2 - HEX_W) / boardSizeWidth;
            double minY = (height / 2 - HEX_H) / boardSizeHeight;
            double maxX = (boardSizeWidth + HEX_W - width / 2) / boardSizeWidth;
            double maxY = (boardSizeHeight + HEX_H - height / 2) / boardSizeHeight;

            // here the order is important because the top/left edges always stop the board, the bottom/right only
            // when the board is big enough
            softCenterTarget.setLocation(Math.min(softCenterTarget.getX(), maxX),
                  Math.min(softCenterTarget.getY(), maxY));

            softCenterTarget.setLocation(Math.max(softCenterTarget.getX(), minX),
                  Math.max(softCenterTarget.getY(), minY));

            // get the current board center point
            double[] visibleArea = getVisibleArea();
            oldCenter.setLocation((visibleArea[0] + visibleArea[2]) / 2, (visibleArea[1] + visibleArea[3]) / 2);

            waitTimer = 0;
            isSoftCentering = true;

        } else {
            // no soft centering:
            // center on coords directly
            Point centreHexLocation = getCentreHexLocation(coords);
            centerOnPointRel(centreHexLocation.x / boardSize.getWidth(), centreHexLocation.y / boardSize.getHeight());
        }
    }

    /**
     * Moves the board one step towards the final position in during soft centering.
     */
    private synchronized void centerOnHexSoftStep(long deltaTime) {
        if (isSoftCentering) {
            // don't move the board if 20ms haven't passed since the last move
            waitTimer += deltaTime;
            if (waitTimer < 20) {
                return;
            }
            waitTimer = 0;

            // move the board by a fraction of the distance to the target
            Point2D newCenter = new Point2D.Double(oldCenter.getX()
                  + (softCenterTarget.getX() - oldCenter.getX()) / SOFT_CENTER_SPEED,
                  oldCenter.getY() + (softCenterTarget.getY() - oldCenter.getY()) / SOFT_CENTER_SPEED);
            centerOnPointRel(newCenter.getX(), newCenter.getY());

            oldCenter = newCenter;

            // stop the motion when close enough to the final position
            if (softCenterTarget.distance(newCenter) < 0.0005) {
                stopSoftCentering();
                pingMinimap();
            }
        }
    }

    public void stopSoftCentering() {
        isSoftCentering = false;
    }

    private void adjustVisiblePosition(@Nullable Coords coords, @Nullable Point dispPoint, double inHexDeltaX,
          double inHexDeltaY) {
        if (scrollPane == null || (coords == null) || (dispPoint == null)) {
            return;
        }

        Point hexPoint = getCentreHexLocation(coords);
        // correct for upper left board padding
        hexPoint.translate(HEX_W, HEX_H);
        JScrollBar horizontalScroll = scrollPane.getHorizontalScrollBar();
        horizontalScroll.setValue(hexPoint.x - dispPoint.x + (int) (inHexDeltaX * scale * HEX_W));
        JScrollBar verticalScroll = scrollPane.getVerticalScrollBar();
        verticalScroll.setValue(hexPoint.y - dispPoint.y + (int) (inHexDeltaY * scale * HEX_H));
        pingMinimap();
        boardPanel.repaint();
    }

    /**
     * Centers the board to a point
     *
     * @param xRelative the x position relative to board width.
     * @param yRelative the y position relative to board height. Both xRelative and yRelative should be between 0 and 1.
     *                  The method will clip both values to this range.
     */
    public void centerOnPointRel(double xRelative, double yRelative) {
        updateBoardSize();
        // restrict both values to between 0 and 1
        xRelative = Math.max(0, xRelative);
        xRelative = Math.min(1, xRelative);
        yRelative = Math.max(0, yRelative);
        yRelative = Math.min(1, yRelative);
        Point point = new Point((int) (boardSize.getWidth() * xRelative) + HEX_W,
              (int) (boardSize.getHeight() * yRelative) + HEX_H);
        if (scrollPane == null || (clientgui != null && GpuBoardWindow.isActiveFor(clientgui))) {
            Coords coords = getCoordsAt(point);
            if (!getBoard().contains(coords)) {
                coords = new Coords(Math.clamp((int) (xRelative * getBoard().getWidth()), 0, getBoard().getWidth() - 1),
                      Math.clamp((int) (yRelative * getBoard().getHeight()), 0, getBoard().getHeight() - 1));
            }
            centerOnHex(coords);
            return;
        }
        JScrollBar verticalScroll = scrollPane.getVerticalScrollBar();
        verticalScroll.setValue(point.y - (verticalScroll.getVisibleAmount() / 2));
        JScrollBar horizontalScroll = scrollPane.getHorizontalScrollBar();
        horizontalScroll.setValue(point.x - (horizontalScroll.getVisibleAmount() / 2));
        boardPanel.repaint();
    }

    /**
     * Returns the currently visible area of the board.
     *
     * @return an array of 4 double values indicating the relative size, where the first two values indicate the x and y
     *       position of the upper left corner of the visible area and the second two values the x and y position of the
     *       lower right corner. So when the whole board is visible, the values should be 0, 0, 1, 1. When the lower
     *       right corner of the board is visible and 90% of width and height: 0.1, 0.1, 1, 1 Due to board padding the
     *       values can be outside [0;1]
     */
    public double[] getVisibleArea() {
        if (scrollPane == null) {
            return new double[] { 0, 0, 1, 1 };
        }
        double[] values = new double[4];
        double x = scrollPane.getViewport().getViewPosition().getX();
        double y = scrollPane.getViewport().getViewPosition().getY();
        double width = scrollPane.getViewport().getWidth();
        double height = scrollPane.getViewport().getHeight();
        double boardSizeWidth = boardSize.getWidth();
        double boardSizeHeight = boardSize.getHeight();

        values[0] = (x - HEX_W) / boardSizeWidth;
        values[1] = (y - HEX_H) / boardSizeHeight;
        values[2] = (x - HEX_W + width) / boardSizeWidth;
        values[3] = (y - HEX_H + height) / boardSizeHeight;

        return values;
    }

    /**
     * Clears the old movement data and draws the new.
     */
    public void drawMovementData(Entity entity, MovePath movePath) {
        clientState.drawMovementData(entity, movePath);
    }

    /**
     * Add Aerospace ground map flight path indicators on the last step based on how much aerodyne movement is left.
     * This will add sprites along the forward path for the remaining velocity and indicate what point along it's
     * forward path the unit can turn.
     *
     * @param movePath - Current MovePath that represents the current units movement state
     */


    /**
     * Clears current movement data from the screen
     */
    public void clearMovementData() {
        clientState.clearMovementData();
    }

    public void addStrafingCoords(Coords coords) {
        clientState.addStrafingCoords(coords);
    }

    public void setStrafingCoords(Collection<Coords> coords) {
        clientState.setStrafingCoords(coords);
    }

    public void clearStrafingCoords() {
        clientState.clearStrafingCoords();
    }

    public ClientGUI getClientgui() {
        return clientgui;
    }

    public float getScale() {
        return scale;
    }

    public Dimension getHexSize() {
        return hex_size;
    }

    @Override
    public Game getGame() { return game; }

    @Override
    public java.awt.FontMetrics getFontMetrics(Font font) { return boardPanel.getFontMetrics(font); }

    @Override
    public int getDropShadowDistance() { return DROP_SHADOW_DISTANCE; }


    public Shape[] getFacingPolys() {
        return clientState.getFacingPolys();
    }

    public Shape[] getMovementPolys() {
        return clientState.getMovementPolys();
    }

    public Shape getUpArrow() {
        return clientState.getUpArrow();
    }

    public Shape getDownArrow() {
        return clientState.getDownArrow();
    }

    /**
     * Specifies that this should mark the deployment hexes for a player. If the player is set to null, no hexes will be
     * marked.
     */
    public void markDeploymentHexesFor(Entity ce) {
        clientState.markDeploymentHexesFor(ce);
    }

    /**
     * Returns the entity that is currently being deployed
     */
    public Entity getDeployingEntity() {
        return clientState.getDeployingEntity();
    }


    /**
     * @param coords the given coords
     *
     * @return any entities flying over the given coords
     */
    public ArrayList<Entity> getEntitiesFlyingOver(Coords coords) {
        return clientState.getEntitiesFlyingOver(coords);
    }


    /**
     * Adds an attack to the sprite list.
     */
    public void addAttack(AttackAction attackAction) {
        clientState.addAttack(attackAction);
    }


    /**
     * Removes all attack sprites from a certain entity
     */
    public synchronized void removeAttacksFor(@Nullable Entity entity) {
        clientState.removeAttacksFor(entity);
    }

    /**
     * Clears out all attacks and re-adds the ones in the current game.
     */
    public void refreshAttacks() {
        clientState.refreshAttacks();
    }


    public void clearC3Networks() {
        clientState.clearC3Networks();
    }


    /**
     * Initializes the various overlay polygons with their vertices.
     */
    public void initPolys() {
        clientState.initPolys();
        facingPolys = clientState.getFacingPolys();
        movementPolys = clientState.getMovementPolys();
        upArrow = clientState.getUpArrow();
        downArrow = clientState.getDownArrow();
    }


    synchronized boolean doMoveUnits(long idleTime) {
        boolean movingSomething = false;

        if (!movingUnits.isEmpty()) {
            moveWait += idleTime;

            if (moveWait > GUIP.getMoveStepDelay()) {
                ArrayList<MovingUnit> spent = new ArrayList<>();

                for (MovingUnit move : movingUnits) {
                    movingSomething = true;
                    Entity entity = game.getEntity(move.entity.getId());
                    if (!move.path.isEmpty()) {
                        UnitLocation loc = move.path.getFirst();

                        if (entity != null) {
                            redrawMovingEntity(move.entity, loc.coords(), loc.facing(), loc.elevation());
                        }
                        move.path.removeFirst();
                    } else {
                        if (entity != null) {
                            redrawEntity(entity);
                        }
                        spent.add(move);
                    }

                }

                for (MovingUnit move : spent) {
                    movingUnits.remove(move);
                }
                moveWait = 0;

                if (movingUnits.isEmpty()) {
                    movingEntitySpriteIds.clear();
                    movingEntitySprites.clear();
                    ghostEntitySprites.clear();
                    clientState.setMovingUnits(false);
                }
            }
        }
        return movingSomething;
    }

    //
    // MouseListener
    //
    @Override
    public void mousePressed(MouseEvent mouseEvent) {
        boardPanel.requestFocusInWindow();
        stopSoftCentering();
        Point point = mouseEvent.getPoint();

        // Button 4: Hide/Show the minimap and unitDisplay
        if (mouseEvent.getButton() == 4) {
            if (clientgui != null) {
                clientgui.toggleMMUDDisplays();
            }
        }

        // we clicked the right mouse button, remember the position if we start to scroll if we drag, we should scroll
        if (SwingUtilities.isRightMouseButton(mouseEvent)) {
            scrollXDifference = mouseEvent.getX();
            scrollYDifference = mouseEvent.getY();
            shouldScroll = true;
        }

        if (mouseEvent.isPopupTrigger() && !dragging) {
            wantsPopup = true;
            return;
        }

        for (IDisplayable displayable : clientState.overlays) {
            double width = scrollPane.getViewport().getSize().getWidth();
            double height = scrollPane.getViewport().getSize().getHeight();
            Dimension dispDimension = new Dimension();
            dispDimension.setSize(width, height);
            // we need to adjust the point, because it should be against the displayable dimension
            Point dispPoint = new Point();
            dispPoint.setLocation(point.x + boardPanel.getBounds().x, point.y + boardPanel.getBounds().y);
            if (displayable.isHit(dispPoint, dispDimension)) {
                return;
            }
        }
        mouseAction(getCoordsAt(point), BOARD_HEX_DRAG, mouseEvent.getModifiersEx(), mouseEvent.getButton());
    }

    @Override
    public void mouseReleased(MouseEvent mouseEvent) {
        // don't show the popup if we are drag-scrolling
        if ((mouseEvent.isPopupTrigger() || wantsPopup) && !dragging) {
            mouseAction(getCoordsAt(mouseEvent.getPoint()),
                  BOARD_HEX_POPUP,
                  mouseEvent.getModifiersEx(),
                  mouseEvent.getButton());
            // stop scrolling
            shouldScroll = false;
            wantsPopup = false;
            return;
        }

        // if we released the right mouse button, there's no more scrolling
        if (SwingUtilities.isRightMouseButton(mouseEvent)) {
            scrollXDifference = 0;
            scrollYDifference = 0;
            dragging = false;
            shouldScroll = false;
            wantsPopup = false;
            boardPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }

        for (IDisplayable displayable : clientState.overlays) {
            if (displayable.isReleased()) {
                return;
            }
        }

        if (mouseEvent.getClickCount() == 1) {
            mouseAction(getCoordsAt(mouseEvent.getPoint()),
                  BOARD_HEX_CLICK,
                  mouseEvent.getModifiersEx(),
                  mouseEvent.getButton());
        } else {
            mouseAction(getCoordsAt(mouseEvent.getPoint()),
                  BOARD_HEX_DOUBLE_CLICK,
                  mouseEvent.getModifiersEx(),
                  mouseEvent.getButton());
        }
    }

    @Override
    public void mouseEntered(MouseEvent mouseEvent) {
    }

    @Override
    public void mouseExited(MouseEvent mouseEvent) {
        // Reset the tooltip dismissal delay to the preference value so that elements outside the BoardView can use
        // tooltips
        if (GUIP.getTooltipDismissDelay() >= 0) {
            ToolTipManager.sharedInstance().setDismissDelay(GUIP.getTooltipDismissDelay());
        } else {
            ToolTipManager.sharedInstance().setDismissDelay(dismissDelay);
        }
    }

    @Override
    public void mouseClicked(MouseEvent me) {
    }

    private record MovingUnit(Entity entity, Vector<UnitLocation> path) {
    }

    /**
     * @return True if all hex tile images have been loaded and the board can be successfully drawn
     */
    public boolean isTileImagesLoaded() {
        return tileManager.isLoaded();
    }

    @Override
    public void setUseLosTool(boolean use) {
        clientState.setUseLosTool(use);
    }

    public TilesetManager getTilesetManager() {
        return tileManager;
    }

    /** The same anonymous contact artwork used by every board renderer. */
    public Image getRadarBlipImage() {
        return radarBlipImage;
    }

    public BoardClientState getClientState() { return clientState; }
    @Override public BoardGlyphContext glyphContext() { return clientState; }


    /** Uses the existing tooltip provider with the GPU's picked hex, regardless of Legacy 2D camera occlusion. */
    public String getHexTooltip(Coords coords) {
        if (coords == null || !getBoard().contains(coords)) {
            return "";
        }
        int originalOffset = verticalOffset;
        try {
            verticalOffset = 0;
            Point point = getCentreHexLocation(coords);
            point.translate(HEX_W, HEX_H);
            return boardViewToolTip.getTooltip(point, clientState.movementTarget);
        } finally {
            verticalOffset = originalOffset;
        }
    }


    /**
     * @param selected The selected to set.
     */
    public void setSelected(Coords selected) {
        clientState.setSelected(selected);
    }

    /**
     * @return Returns the selected.
     */
    public Coords getSelected() {
        return clientState.getSelected();
    }

    /**
     * @param firstLOS The firstLOS to set.
     */
    public void setFirstLOS(Coords firstLOS) {
        clientState.setFirstLOS(firstLOS);
    }

    /**
     * @return Returns the firstLOS.
     */
    public Coords getFirstLOS() {
        return clientState.getFirstLOS();
    }

    /**
     * Determines if this Board contains the Coords, and if so, "selects" that Coords.
     *
     * @param coords the Coords.
     */
    @Override
    public void select(Coords coords) {
        clientState.select(coords);
    }

    /** Select a hex for inspection without invoking a phase's target, deployment or movement tool. */
    public void selectForInspection(Coords coords) {
        clientState.selectForInspection(coords);
    }

    /**
     * "Selects" the specified Coords.
     *
     * @param x the x coordinate.
     * @param y the y coordinate.
     */
    public void select(int x, int y) {
        clientState.select(x, y);
    }

    /**
     * Determines if this Board contains the Coords, and if so, highlights that Coords.
     *
     * @param coords the Coords.
     */
    @Override
    public void highlight(Coords coords) {
        clientState.highlight(coords);
    }

    /**
     * @param color The new colour of the highlight cursor.
     */
    public void setHighlightColor(Color color) {
        clientState.setHighlightColor(color);
    }

    /**
     * Highlights the specified Coords.
     *
     * @param x the x coordinate.
     * @param y the y coordinate.
     */
    public void highlight(int x, int y) {
        clientState.highlight(x, y);
    }

    public synchronized void highlightSelectedEntity(Entity entity) {
        clientState.highlightSelectedEntity(entity);
        for (EntitySprite sprite : entitySprites) { sprite.setSelected(clientState.isEntitySelected(sprite.getEntity())); }
    }

    /**
     * Highlights multiple entities on the board view. All entities in the provided list will be highlighted. All other
     * entities will be unhighlighted.
     *
     * @param entities List of entities to highlight (can be empty to clear all highlights)
     */
    public synchronized void highlightSelectedEntities(List<Entity> entities) {
        clientState.highlightSelectedEntities(entities);
        for (EntitySprite sprite : entitySprites) { sprite.setSelected(clientState.isEntitySelected(sprite.getEntity())); }
    }

    /**
     * Sets the hexes to highlight with white borders (for Nova CEWS network dialog). Draws white hexagon borders around
     * the specified hex coordinates.
     *
     * @param hexes List of hex coordinates to highlight (can be empty to clear all highlights)
     */
    public void setHighlightedEntityHexes(List<Coords> hexes) {
        clientState.setHighlightedEntityHexes(hexes);
    }

    /**
     * Sets the demolition charge hexes to highlight (selected in the Detonate Charges dialog). These are drawn with a
     * bold yellow/black hazard outline when the matching client setting is on, otherwise with the plain highlight.
     *
     * @param hexes List of hex coordinates to highlight (can be empty to clear all highlights)
     */
    public void setDemolitionChargeHighlightHexes(List<Coords> hexes) {
        clientState.setDemolitionChargeHighlightHexes(hexes);
    }

    /**
     * Determines if this Board contains the Coords, and if so, "cursors" that Coords.
     *
     * @param coords the Coords.
     */
    @Override
    public void cursor(Coords coords) {
        clientState.cursor(coords);
    }

    /**
     * "Cursors" the specified Coords.
     *
     * @param x the x coordinate.
     * @param y the y coordinate.
     */
    public void cursor(int x, int y) {
        clientState.cursor(x, y);
    }

    public void checkLOS(Coords c) {
        clientState.checkLOS(c);
    }

    /**
     * Determines if this Board contains the (x, y) Coords, and if so, notifies listeners about the specified mouse
     * action.
     */
    public void mouseAction(int x, int y, int mouseActionType, int modifiers, int mouseButton) {
        clientState.mouseAction(x, y, mouseActionType, modifiers, mouseButton);
    }

    /**
     * Notifies listeners about the specified mouse action.
     *
     * @param coords      - coords the Coords.
     * @param eventType   - Board view event type
     * @param modifiers   - mouse event modifiers mask such as SHIFT_DOWN_MASK etc.
     * @param mouseButton - mouse button associated with this event 0 = no button 1 = Button 1 2 = Button 2
     */
    public void mouseAction(Coords coords, int eventType, int modifiers, int mouseButton) {
        clientState.mouseAction(coords, eventType, modifiers, mouseButton);
    }

    @Override
    public void boardNewBoard(BoardEvent boardEvent) {
        updateBoard();
        game.getBoard(boardId).initializeAllAutomaticTerrain();
        clearHexImageCache();
        clearShadowMap();
        boardPanel.repaint();
    }

    @Override
    public void boardChangedHex(BoardEvent boardEvent) {
        // Shared state has invalidated LOS. Only the Legacy 2D raster embeds FoV into its hex images.
        if (shouldFovDarken() || shouldFovHighlight()) { hexImageCache.clear(); }
        Coords coords = boardEvent.getCoords();
        Hex hex = game.getBoard(boardId).getHex(coords);
        // An elevator changes its terrain overlay level, which the isometric view can draw beyond the immediate
        // neighbors. A per-hex cache clear leaves the isometric view stale (it only recovers on a full reload), so
        // for elevator hexes clear the whole hex image cache - the same thing a board reload does.
        boolean hasIndustrialElevator = (hex != null) && hex.containsTerrain(Terrains.INDUSTRIAL_ELEVATOR);
        boolean hasSolarisElevator = (hex != null) && hex.containsTerrain(Terrains.SOLARIS_ELEVATOR);
        if (hasIndustrialElevator || hasSolarisElevator) {
            hexImageCache.clear();
        } else {
            hexImageCache.remove(coords);
            // Also repaint the surrounding hexes because of shadows, border etc.
            for (int direction : allDirections) {
                hexImageCache.remove(coords.translated(direction));
            }
        }
        clearShadowMap();
        boardPanel.repaint();
    }

    @Override
    public synchronized void boardChangedAllHexes(BoardEvent boardEvent) {
        clearHexImageCache();
        clearShadowMap();
        boardPanel.repaint();
    }

    synchronized void boardChanged() {
        redrawAllEntities();
    }

    @Override
    public void clearSprites() {
        clientState.clearSprites();
    }

    public synchronized void updateBoard() {
        updateBoardSize();
        redrawAllEntities();
    }

    /**
     * the old redraw worker converted to a runnable which is called now and then from the event thread
     */
    private class RedrawWorker implements Runnable {

        private long lastTime = java.lang.System.currentTimeMillis();

        @Override
        public void run() {
            long currentTime = java.lang.System.currentTimeMillis();

            // The GPU window also presents these clientState.overlays while the Legacy 2D panel is hidden.
            boolean redraw = false;
            for (IDisplayable displayable : clientState.overlays) {
                if (!displayable.isSliding()) {
                    displayable.setIdleTime(currentTime - lastTime, true);
                } else {
                    redraw |= displayable.slide();
                }
            }
            if (boardPanel.isShowing()) {
                redraw = redraw || doMoveUnits(currentTime - lastTime);

                if (redraw) {
                    boardPanel.repaint();
                }
                centerOnHexSoftStep(currentTime - lastTime);
            }

            lastTime = currentTime;
        }
    }

    /**
     * @param entity the BoardView's currently selected entity
     */
    public synchronized void selectEntity(Entity entity) {
        checkFoVHexImageCacheClear();
        clientState.updateEcmList();
        highlightSelectedEntity(entity);
    }


    /**
     * Have the player select an Entity from the entities at the given coords.
     *
     * @param position - the <code>Coords</code> containing targets.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    private Entity chooseEntity(Coords position) {
        // Assume that we have *no* choice.
        Entity choice = null;

        // Get the available choices.
        List<Entity> entities = game.getEntitiesVector(position);

        // Do we have a single choice?
        if (entities.size() == 1) {
            // Return that choice.
            choice = entities.getFirst();
        } else if (entities.size() > 1) {
            // If we have multiple choices, display a selection dialog.
            choice = EntityChoiceDialog.showSingleChoiceDialog(clientgui.getFrame(),
                  "BoardView1.ChooseEntityDialog.title",
                  Messages.getString("BoardView1.ChooseEntityDialog.message", position.getBoardNum()),
                  entities);
        }

        // Return the chosen unit.
        return choice;
    }

    @Override
    public Component getComponent() {
        return getComponent(false);
    }

    @Override
    public void setDisplayInvalidFields(boolean displayInvalidFields) {
        displayInvalidHexInfo = displayInvalidFields;
        clientState.setDisplayInvalidFields(displayInvalidFields);
    }

    @Override
    public void setLocalPlayer(int playerId) {
        setLocalPlayer(game.getPlayer(playerId));
    }

    public Component getComponent(boolean scrollBars) {
        // If we're already configured, return the ScrollPane
        if (scrollPane != null) {
            return scrollPane;
        }

        SkinSpecification bvSkinSpec = SkinXMLHandler.getSkin(UIComponents.BoardView.getComp());

        // Setup background icons
        try {
            File file;

            if (!bvSkinSpec.backgrounds.isEmpty()) {
                file = new MegaMekFile(Configuration.widgetsDir(), bvSkinSpec.backgrounds.getFirst()).getFile();
                if (!file.exists()) {
                    LOGGER.error("BoardView1 Error: Background 0 icon doesn't exist: {}", file.getAbsolutePath());
                } else {
                    bvBgImage = (BufferedImage) ImageUtil.loadImageFromFile(file.getAbsolutePath());
                    bvBgShouldTile = bvSkinSpec.tileBackground;
                }
            }

            if (bvSkinSpec.backgrounds.size() > 1) {
                file = new MegaMekFile(Configuration.widgetsDir(), bvSkinSpec.backgrounds.get(1)).getFile();
                if (!file.exists()) {
                    LOGGER.error("BoardView1 Error: Background 1 icon doesn't exist: {}", file.getAbsolutePath());
                } else {
                    scrollPaneBgImg = ImageUtil.loadImageFromFile(file.getAbsolutePath());
                }
            }
        } catch (Exception ex) {
            LOGGER.error(ex, "Error loading BoardView background images!");
        }

        // Place the board viewer in a set of scrollbars.
        scrollPane = new JScrollPane(boardPanel) {
            @Override
            protected void paintComponent(Graphics graphics) {
                if (scrollPaneBgImg == null) {
                    super.paintComponent(graphics);
                    return;
                }

                int w = getWidth();
                int h = getHeight();
                int iW = scrollPaneBgImg.getWidth(null);
                int iH = scrollPaneBgImg.getHeight(null);

                if ((scrollPaneBgBuffer == null)
                      || (scrollPaneBgBuffer.getWidth() != w)
                      || (scrollPaneBgBuffer.getHeight() != h)) {
                    scrollPaneBgBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics bgGraph = scrollPaneBgBuffer.getGraphics();
                    // If the unit icon not loaded, prevent infinite loop

                    if ((iW < 1) || (iH < 1)) {
                        return;
                    }

                    for (int x = 0;
                          x < w;
                          x += iW) {
                        for (int y = 0;
                              y < h;
                              y += iH) {
                            bgGraph.drawImage(scrollPaneBgImg, x, y, null);
                        }
                    }

                    bgGraph.dispose();
                }
                graphics.drawImage(scrollPaneBgBuffer, 0, 0, null);
            }
        };
        scrollPane.setBorder(new MegaMekBorder(bvSkinSpec));
        scrollPane.setLayout(new ScrollPaneLayout());
        // we need to use the simple scroll mode because otherwise the IDisplayables that are drawn in fixed
        // positions in the viewport leave artifacts when scrolling
        scrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);

        // Prevent the default arrow key scrolling
        scrollPane.getActionMap().put("unitScrollRight", DoNothing);
        scrollPane.getActionMap().put("unitScrollDown", DoNothing);
        scrollPane.getActionMap().put("unitScrollLeft", DoNothing);
        scrollPane.getActionMap().put("unitScrollUp", DoNothing);

        verticalBar = scrollPane.getVerticalScrollBar();
        horizontalBar = scrollPane.getHorizontalScrollBar();

        if (!scrollBars && !bvSkinSpec.showScrollBars) {
            verticalBar.setPreferredSize(new Dimension(0, verticalBar.getHeight()));
            horizontalBar.setPreferredSize(new Dimension(horizontalBar.getWidth(), 0));
        }

        return scrollPane;
    }

    /** Drop the inactive Swing viewport and its artwork; shared game interaction remains owned by this board adapter. */
    public void releaseLegacy2DView() {
        stopSoftCentering();
        if (scrollPane != null) {
            scrollPane.setViewportView(null);
            scrollPane = null;
        }
        verticalBar = null;
        horizontalBar = null;
        scrollPaneBgBuffer = null;
        scrollPaneBgImg = null;
        bvBgImage = null;
        shadowMap = null;
        hexImageCache.clear();
    }

    AbstractAction DoNothing = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {

        }
    };

    private void pingMinimap() {
        // send the minimap a hex moused event to make it update the visible area rectangle
        BoardViewEvent bve = new BoardViewEvent(clientState, BoardViewEvent.BOARD_HEX_DRAGGED);
        for (BoardViewListener l : clientState.listeners) {
            l.hexMoused(bve);
        }
    }

    public void showPopup(JPopupMenu popUp, Coords coords) {
        Point p = getHexLocation(coords);
        p.x += ((int) (HEX_WC * scale) - scrollPane.getX()) + HEX_W;
        p.y += ((int) ((HEX_H * scale) / 2) - scrollPane.getY()) + HEX_H;

        if (popUp.getParent() == null) {
            boardPanel.add(popUp);
        }

        popUp.show(boardPanel, p.x, p.y);
    }

    @Override
    public void zoomIn() {
        if (zoomIndex == (ZOOM_FACTORS.length - 1)) {
            return;
        }

        zoomIndex++;
        zoom();
    }

    @Override
    public void zoomOut() {
        if (zoomIndex == 0) {
            return;
        }

        zoomIndex--;
        zoom();
    }

    /**
     * Reset the zoom level to the BASE_ZOOM_INDEX
     */
    @Override
    public void zoomReset() {
        zoomIndex = BASE_ZOOM_INDEX;
        zoom();
    }

    @Override
    public void zoomOverviewToggle() {
        if (!zoomOverview) {
            preZoomOverviewIndex = zoomIndex;
            preZoomOverviewViewX = scrollPane.getHorizontalScrollBar().getValue();
            preZoomOverviewViewY = scrollPane.getVerticalScrollBar().getValue();

            for (int i = ZOOM_FACTORS.length - 1;
                  i > 0;
                  i--) {
                if (getComponent().getWidth() / getComponent().getHeight() < 1) {
                    if (((boardSize.width / ZOOM_FACTORS[zoomIndex]) + HEX_W * 2) * ZOOM_FACTORS[i]
                          < getComponent().getWidth()) {
                        bestZoomFactor = i;
                        break;
                    }
                } else {
                    if (((boardSize.height / ZOOM_FACTORS[zoomIndex]) + HEX_H * 2) * ZOOM_FACTORS[i]
                          < getComponent().getHeight()) {
                        bestZoomFactor = i;
                        break;
                    }
                }
                bestZoomFactor = 0;
            }
            zoomIndex = bestZoomFactor;
            zoomOverview = true;
            zoom();
        } else {
            zoomIndex = preZoomOverviewIndex;
            zoomOverview = false;
            zoom();
            scrollPane.getHorizontalScrollBar().setValue(preZoomOverviewViewX);
            scrollPane.getVerticalScrollBar().setValue(preZoomOverviewViewY);
        }
    }

    private void checkZoomIndex() {
        if (zoomIndex > (ZOOM_FACTORS.length - 1)) {
            zoomIndex = ZOOM_FACTORS.length - 1;
        }

        if (zoomIndex < 0) {
            zoomIndex = 0;
        }
    }

    /**
     * Changes hex dimensions and refreshes the map with the new scale
     */
    private void zoom() {
        Point dispPoint;
        double inHexDeltaX = 0;
        double inHexDeltaY = 0;
        Coords zoomCenter;

        try { // try to get mouse position and zoom centered on the cursor
            Point mouseP = new Point(boardPanel.getMousePosition());
            dispPoint = new Point(mouseP.x + boardPanel.getBounds().x, mouseP.y + boardPanel.getBounds().y);
            zoomCenter = new Coords(getCoordsAt(boardPanel.getMousePosition()));
            Point hexL = getCentreHexLocation(zoomCenter);
            Point inHexDelta = new Point(boardPanel.getMousePosition());
            inHexDelta.translate(-HEX_W, -HEX_H);
            inHexDelta.translate(-hexL.x, -hexL.y);
            inHexDeltaX = ((double) inHexDelta.x) / ((double) HEX_W) / scale;
            inHexDeltaY = ((double) inHexDelta.y) / ((double) HEX_H) / scale;

        } catch (Exception e) { // zoom on view center, if mouse position is outside the map
            Point viewCenter = new Point(boardPanel.getVisibleRect().getLocation().x
                  + boardPanel.getVisibleRect().width / 2,
                  boardPanel.getVisibleRect().getLocation().y + boardPanel.getVisibleRect().height / 2);
            dispPoint = new Point(viewCenter.x + boardPanel.getBounds().x, viewCenter.y + boardPanel.getBounds().y);
            zoomCenter = new Coords(getCoordsAt(viewCenter));
        }

        checkZoomIndex();
        stopSoftCentering();
        scale = ZOOM_FACTORS[zoomIndex];
        GUIP.setMapZoomIndex(zoomIndex);

        hex_size = new Dimension((int) (HEX_W * scale), (int) (HEX_H * scale));

        scaledImageCache = new ImageKeyedCache<>();

        clientState.cursorSprite.prepare();
        clientState.highlightSprite.prepare();
        clientState.selectedSprite.prepare();
        clientState.firstLOSSprite.prepare();
        clientState.secondLOSSprite.prepare();

        clientState.allSprites.forEach(Sprite::prepare);

        updateFontSizes();
        updateBoard();

        for (StepSprite sprite : clientState.pathSprites) {
            sprite.refreshZoomLevel();
        }

        for (FlightPathIndicatorSprite sprite : clientState.fpiSprites) {
            sprite.prepare();
        }

        boardPanel.setSize(boardSize);
        clearHexImageCache();

        adjustVisiblePosition(zoomCenter, dispPoint, inHexDeltaX, inHexDeltaY);

        if (zoomIndex != bestZoomFactor) {
            zoomOverview = false;
        }
    }

    private void updateFontSizes() {
        if (zoomIndex < 7) {
            font_elev = FONT_7;
            font_hexNumber = FONT_7;
            font_minefield = FONT_7;
        } else if ((zoomIndex < 8)) {
            font_elev = FONT_10;
            font_hexNumber = FONT_10;
            font_minefield = FONT_10;
        } else if ((zoomIndex < 10)) {
            font_elev = FONT_12;
            font_hexNumber = FONT_12;
            font_minefield = FONT_12;
        } else if ((zoomIndex < 11)) {
            font_elev = FONT_14;
            font_hexNumber = FONT_14;
            font_minefield = FONT_14;
        } else if (zoomIndex < 12) {
            font_elev = FONT_16;
            font_hexNumber = FONT_16;
            font_minefield = FONT_16;
        } else if (zoomIndex < 13) {
            font_elev = FONT_18;
            font_hexNumber = FONT_18;
            font_minefield = FONT_18;
        } else {
            font_elev = FONT_24;
            font_hexNumber = FONT_24;
            font_minefield = FONT_24;
        }
    }

    /**
     * Return a scaled version of the input. If the useCache flag is set, the scaled image will be stored in an image
     * cache for later retrieval.
     *
     * @param base     The image to get a scaled copy of. The current zoom level is used to determine the scale.
     * @param useCache This flag determines whether the scaled image should be stored in a cache for later retrieval.
     */
    @Nullable
    public Image getScaledImage(Image base, boolean useCache) {
        if (base == null) {
            return null;
        }

        // Captures can use a different raster scale from the Legacy 2D view's zoom setting.
        if (scale == 1) {
            return base;
        }

        Image scaled;
        if (useCache) {
            // Check the cache
            scaled = scaledImageCache.get(base);
        } else {
            scaled = null;
        }
        // Compute the scaled image
        if (scaled == null) {
            MediaTracker tracker = new MediaTracker(boardPanel);
            if ((base.getWidth(null) == -1) || (base.getHeight(null) == -1)) {
                tracker.addImage(base, 0);
                try {
                    tracker.waitForID(0);
                } catch (InterruptedException e) {
                    LOGGER.error(e, "");
                }
                if (tracker.isErrorAny()) {
                    return null;
                }
                tracker.removeImage(base);
            }
            int width = (int) (base.getWidth(null) * scale);
            int height = (int) (base.getHeight(null) * scale);

            if ((width < 1) || (height < 1)) {
                return null;
            }

            scaled = scale(base, width, height);
            tracker.addImage(scaled, 1);
            // Wait for image to load
            try {
                tracker.waitForID(1);
            } catch (InterruptedException e) {
                LOGGER.error(e, "");
            }

            tracker.removeImage(scaled);
            // Cache the image if the flag is set
            if (useCache) {
                scaledImageCache.put(base, scaled);
            }
        }
        return scaled;
    }

    /**
     * The actual scaling code.
     */
    private Image scale(Image image, int width, int height) {
        return ImageUtil.getScaledImage(image, width, height, ZOOM_SCALE_TYPES[zoomIndex]);
    }

    public void toggleIsometric() {
        setVerticalOffset();
        clientState.allSprites.forEach(Sprite::prepare);
        clearHexImageCache();
        updateBoard();
        repaint();
    }

    public void updateEntityLabels() {
        clientState.updateEntityLabels();
    }

    public BufferedImage createShadowMask(Image image) {
        BufferedImage mask = shadowImageCache.get(image);
        if (mask != null) {
            return mask;
        }
        mask = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        float opacity = 0.4f;
        Graphics2D graphics2D = mask.createGraphics();
        graphics2D.drawImage(image, 0, 0, null);
        graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_IN, opacity));
        graphics2D.setColor(Color.BLACK);
        graphics2D.fillRect(0, 0, image.getWidth(null), image.getHeight(null));
        graphics2D.dispose();
        shadowImageCache.put(image, mask);
        return mask;
    }

    /**
     * @return Returns true if the BoardView has an active chatter box else false.
     */
    public boolean getChatterBoxActive() {
        return clientState.getChatterBoxActive();
    }

    /**
     * @param chatterBoxActive whether the BoardView has an active chatter box or not.
     */
    public void setChatterBoxActive(boolean chatterBoxActive) {
        clientState.setChatterBoxActive(chatterBoxActive);
    }

    public void setShouldIgnoreKeys(boolean shouldIgnoreKeys) {
        clientState.setShouldIgnoreKeys(shouldIgnoreKeys);
    }

    /** EDT-only asset refresh of the 2D board. */
    public void reloadAssets() throws IOException {
        tileManager.reloadAssets();
        scaledImageCache.clear();
        clearHexImageCache();
    }

    public void clearHexImageCache() {
        if (fovHighlightingAndDarkening != null) {
            fovHighlightingAndDarkening.invalidate();
        }
        clientState.repaint();
        hexImageCache.clear();

        clientState.clearArtwork();
    }

    /**
     * Clear a specific list of Coords from the hex image cache.
     *
     * @param setCoords Set of {@link Coords} to remove
     */
    public void clearHexImageCache(Set<Coords> setCoords) {
        if (fovHighlightingAndDarkening != null) {
            fovHighlightingAndDarkening.invalidate();
        }
        clientState.repaint();
        for (Coords coords : setCoords) {
            hexImageCache.remove(coords);

            clientState.invalidateArtwork(coords);
        }
    }

    /**
     * Check to see if the HexImageCache should be cleared because of field-of-view changes.
     */
    public void checkFoVHexImageCacheClear() {
        boolean darken = shouldFovDarken();
        boolean highlight = shouldFovHighlight();
        if (darken || highlight) {
            if (fovHighlightingAndDarkening != null) {
                fovHighlightingAndDarkening.invalidate();
            }
            // GPU raster tiles no longer contain FoV. Only Legacy 2D pixels and the presentation snapshot change.
            hexImageCache.clear();
            clientState.repaint();
        }
    }

    public static Polygon getHexPoly() {
        return HEX_POLY;
    }


    public Rectangle getDisplayablesRect() {
        return clientState.displayablesRect;
    }

    public boolean shouldFovHighlight() {
        return clientState.shouldFovHighlight();
    }

    public boolean shouldFovDarken() {
        return clientState.shouldFovDarken();
    }

    public void setShowLobbyPlayerDeployment(boolean showLobbyPlayerDeployment) {
        clientState.setShowLobbyPlayerDeployment(showLobbyPlayerDeployment);
    }

    @Override
    public JPanel getPanel() {
        return boardPanel;
    }

    @Override
    public Dimension getBoardSize() {
        return boardSize;
    }

    @Override
    public Set<Integer> getAnimatedImages() {
        return animatedImages;
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle rectangle, int arg1, int arg2) {
        return (int) (scale / 2.0) * ((arg1 == SwingConstants.VERTICAL) ? HEX_H : HEX_W);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle rectangle, int arg1, int arg2) {
        Dimension size = scrollPane.getViewport().getSize();
        return (arg1 == SwingConstants.VERTICAL) ? size.height : size.width;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // The native window belongs to the client, so replacing a map must not close it.
        if (getClientgui() == null) {
            GpuBoardWindow.closeFor(clientState);
        }
        keyRegistrations.forEach(Runnable::run);
        keyRegistrations.clear();
        ToolTipManager.sharedInstance().unregisterComponent(boardPanel);
        redrawTimerTask.cancel();
        game.removeGameListener(boardGameListener);
        if (observedBoard != null) {
            observedBoard.removeBoardListener(this);
        }
        removeSprites(entitySprites);
        removeSprites(isometricSprites);
        clientState.setChanged(() -> { });
        clientState.setProjection(null);
        clientState.setMovingUnitPainter(null);
        clientState.setEntityRenderer(entity -> { });
        clientState.setInputEnabled(() -> true);
        clientState.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
        if (ownsClientState) { clientState.close(); }
        KeyBindParser.removePreferenceChangeListener(this);
        GUIP.removePreferenceChangeListener(this);
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(this);
    }

    /** @return The TurnDetailsOverlay if this BoardView has one. */
    @Nullable
    public TurnDetailsOverlay getTurnDetailsOverlay() {
        return clientState.getTurnDetailsOverlay();
    }

    /**
     * @return The unit currently shown in the Unit Display. Note: This can be a unit than the one that is selected to
     *       move or fire.
     */
    @Nullable
    public Entity getSelectedEntity() {
        return clientState.getSelectedEntity();
    }


    public ArrayList<IsometricWreckSprite> getIsoWreckSprites() {
        return isometricWreckSprites;
    }

    public ArrayList<AttackSprite> getAttackSprites() {
        return clientState.getAttackSprites();
    }

    @Override
    public void repaint() {
        boardPanel.repaint();
    }

    @Override
    public void addSprite(Sprite sprite) {
        addSprites(List.of(sprite));
    }

    @Override
    public void addSprites(Collection<? extends Sprite> sprites) {
        clientState.addSprites(sprites);
    }

    @Override
    public void removeSprites(Collection<? extends Sprite> sprites) {
        clientState.removeSprites(sprites);
    }

    /**
     * @return This BoardView's displayed board.
     */
    public Board getBoard() {
        return game.getBoard(boardId);
    }

    /**
     * @return True when the given Targetable is not null and is on this board as given by its boardId. Does *not*
     *       require the targetable to have a non-null position or a position contained within the board, nor is the
     *       targetable's deployment status checked.
     */
    public boolean isOnThisBord(@Nullable Targetable targetable) {
        return clientState.isOnThisBord(targetable);
    }

    /**
     * @return True when the given boardLocation is on this board according to its boardId only. Does *not* test the
     *       position of the boardLocation.
     *
     * @see BoardLocation#isOn(int)
     */
    @SuppressWarnings("unused")
    public boolean isOnThisBord(BoardLocation boardLocation) {
        return clientState.isOnThisBord(boardLocation);
    }

    /** Capture only affected hexes, in stable order, using the same painters as the Legacy 2D board. */


    /** Shared authoritative colors; only the static texture remains in native raster capture. */


    private void drawMapSheetBorders(Graphics2D graphics, Coords coords) {
        clientState.drawMapSheetBorders(graphics, coords);
    }

    /** Draws an embedded-board indicator using the same rectangle in both renderers. */


    @Override
    public boolean isShowingAnimation() {
        return isMovingUnits();
    }


    public void addHexDrawPlugin(HexDrawPlugin plugin) {
        clientState.addHexDrawPlugin(plugin);
    }

    /**
     * Sets the vertical offset to the correct value depending on the current isometric on/off status; the vertical
     * offset is the distance by which hexes are moved up/down in screen space to represent their elevation.
     */
    private void setVerticalOffset() {
        verticalOffset = GUIP.getIsometricEnabled() ? ISOMETRIC_OFFSET : 0;
    }

    /**
     * @return The currently used hex elevation vertical offset; when isometric mode is off, this is 0.
     */
    public int getVerticalOffset() {
        return verticalOffset;
    }

    @Override public Player getLocalPlayer() { return clientState.getLocalPlayer(); }
    @Override public void setLocalPlayer(Player player) { clientState.setLocalPlayer(player); }
    @Override public Set<Sprite> getAllSprites() { return clientState.getAllSprites(); }
    @Override public void addBoardViewListener(BoardViewListener listener) { clientState.addBoardViewListener(listener); }
    @Override public void removeBoardViewListener(BoardViewListener listener) { clientState.removeBoardViewListener(listener); }
    @Override public void processBoardViewEvent(BoardViewEvent event) { clientState.processBoardViewEvent(event); }
    @Override public void addOverlay(IDisplayable overlay) { clientState.addOverlay(overlay); }
    @Override public void removeOverlay(IDisplayable overlay) { clientState.removeOverlay(overlay); }
    public void redrawEntity(Entity entity) { clientState.redrawEntity(entity); }
    public void redrawAllEntities() { clientState.redrawAllEntities(); }

}
