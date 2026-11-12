/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListener;
import megamek.client.ui.IDisplayable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.overlay.ChatterBoxOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.OverlayImage;
import megamek.client.ui.clientGUI.boardview.overlay.TurnDetailsOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.UnitOverviewOverlay;
import megamek.client.ui.clientGUI.boardview.sprite.AttackSprite;
import megamek.client.ui.clientGUI.boardview.sprite.BridgeBuildSprite;
import megamek.client.ui.clientGUI.boardview.sprite.BridgeRepairedSprite;
import megamek.client.ui.clientGUI.boardview.sprite.C3Sprite;
import megamek.client.ui.clientGUI.boardview.sprite.CollapseWarningSprite;
import megamek.client.ui.clientGUI.boardview.sprite.CraneOperationSprite;
import megamek.client.ui.clientGUI.boardview.sprite.CraneUnloadTargetSprite;
import megamek.client.ui.clientGUI.boardview.sprite.CursorSprite;
import megamek.client.ui.clientGUI.boardview.sprite.DugInSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FlareSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FlightPathIndicatorSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FlyOverSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FortifyBuildSprite;
import megamek.client.ui.clientGUI.boardview.sprite.GroundObjectSprite;
import megamek.client.ui.clientGUI.boardview.sprite.HexFlagSprite;
import megamek.client.ui.clientGUI.boardview.sprite.HexSprite;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.clientGUI.boardview.sprite.MovementModifierEnvelopeSprite;
import megamek.client.ui.clientGUI.boardview.sprite.MovementSprite;
import megamek.client.ui.clientGUI.boardview.sprite.RouteDotSprite;
import megamek.client.ui.clientGUI.boardview.sprite.RubbleClearSprite;
import megamek.client.ui.clientGUI.boardview.sprite.SawClearingSprite;
import megamek.client.ui.clientGUI.boardview.sprite.ScanSprite;
import megamek.client.ui.clientGUI.boardview.sprite.SensorRangeSprite;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.clientGUI.boardview.sprite.StepSprite;
import megamek.client.ui.clientGUI.boardview.sprite.TextMarkerSprite;
import megamek.client.ui.clientGUI.boardview.sprite.VTOLAttackSprite;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.util.UIUtil;
import megamek.common.ArtilleryModifier;
import megamek.common.Configuration;
import megamek.common.ECMInfo;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.TagInfo;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.AttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.PhysicalAttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.AllowedDeploymentHelper;
import megamek.common.board.Board;
import megamek.common.board.BoardHelper;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.board.FacingOption;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeArc;
import megamek.common.compute.ComputeECM;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentActivation;
import megamek.common.equipment.Minefield;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponType;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.moves.MoveStep;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.CombatVehicleEscapePod;
import megamek.common.units.DemolitionCharge;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.IBuilding;
import megamek.common.units.Infantry;
import megamek.common.units.Targetable;
import megamek.common.util.ImageUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;
import megamek.server.props.OrbitalBombardment;

/** Client-thread presentation state shared by board renderers. Game and phase controllers own the rules and moves. */
public final class BoardClientState implements BoardGlyphContext, AutoCloseable {
    public final Game game;
    private final int boardId;
    private final FovHighlightingAndDarkening fieldOfView;
    private final ClientGUI clientgui;
    private Player localPlayer;
    private final TilesetManager tileManager;
    private final Map<Font, FontMetrics> fontMetrics = new HashMap<>();
    private BoardGlyphContext projection;
    private Consumer<Graphics2D> drawMovingUnits;
    void setMovingUnitPainter(Consumer<Graphics2D> painter) { drawMovingUnits = painter; }
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final int HEX_WC = HEX_W - HEX_W / 4;
    private static final int[] allDirections = { 0, 1, 2, 3, 4, 5 };
    private static final Font FONT_10 = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 10);
    private static final Font font_note = FONT_10;
    private static final Polygon HEX_POLY = HexDrawUtilities.rasterHex();
    public static final Color DEMO_CHARGE_HAZARD_COLOR = new Color(255, 213, 0);
    private static final Color DEMO_CHARGE_OUTLINE_COLOR = new Color(0, 0, 0, 200);
    public static final Color ARTILLERY_DRIFT_LINE_COLOR = new Color(255, 191, 0);
    private static final Color[] HEAT_MAP_COLOR_RAMP = {
          new Color(0, 0, 128),      // navy blue - coldest
          new Color(102, 178, 255),  // light blue
          new Color(220, 220, 220),  // light gray (neutral middle)
          new Color(255, 178, 102),  // light orange
          new Color(220, 20, 60)     // crimson red - hottest
    };
    private static final int HEAT_MAP_MAX_HEAT_UNITS = 5;
    private static final float HEAT_MAP_FILL_ALPHA = 0.55f;
    private static final MMLogger LOGGER = MMLogger.create(BoardClientState.class);
    /** The units last found tagged, so the tag markers log only when they change. */
    private Set<Integer> lastTagged = Set.of();
    public static final int BOARD_HEX_CLICK = 1, BOARD_HEX_DOUBLE_CLICK = 2, BOARD_HEX_DRAG = 3, BOARD_HEX_POPUP = 4;
    final List<BoardViewListener> listeners = new ArrayList<>();
    /** EDT: the hex pick that takes this board's clicks, or null. */
    private BoardHexPick hexPick;
    final LinkedHashSet<IDisplayable> overlays = new LinkedHashSet<>();
    final TreeSet<Sprite> allSprites = new TreeSet<>();
    final TreeSet<Sprite> overTerrainSprites = new TreeSet<>();
    final TreeSet<HexSprite> behindTerrainHexSprites = new TreeSet<>();
    /** A mark the handlers and dialogs publish, and the Legacy 2D sprites made from it. */
    private record Published(TacticalMark mark, List<Sprite> sprites) { }
    private final List<Published> published = new ArrayList<>();
    /** The marks this state makes from the game itself; each has its Legacy 2D sprite list below. */
    private List<TacticalMark.Step> pathSteps = List.of();
    private List<TacticalMark.FlightPath> flightPath = List.of();
    private final List<TacticalMark.C3Link> c3Links = new ArrayList<>();
    private final List<TacticalMark.VtolAttack> vtolAttacks = new ArrayList<>();
    private final List<TacticalMark.FlyOver> flyOvers = new ArrayList<>();
    private final List<TacticalMark.MovementVector> movementVectors = new ArrayList<>();
    /** The attacks shown on the board, one per declared weapon or physical attack. */
    private final List<AttackAction> attacks = new ArrayList<>();
    private Coords losStart, losEnd;
    final ArrayList<StepSprite> pathSprites = new ArrayList<>();
    final ArrayList<FlightPathIndicatorSprite> fpiSprites = new ArrayList<>();
    final ArrayList<AttackSprite> attackSprites = new ArrayList<>();
    final ArrayList<MovementSprite> movementSprites = new ArrayList<>();
    final ArrayList<C3Sprite> c3Sprites = new ArrayList<>();
    final ArrayList<VTOLAttackSprite> vtolAttackSprites = new ArrayList<>();
    final ArrayList<FlyOverSprite> flyOverSprites = new ArrayList<>();
    final ArrayList<Coords> strafingCoords = new ArrayList<>();
    final CursorSprite cursorSprite = new CursorSprite(this, Color.cyan);
    final CursorSprite highlightSprite = new CursorSprite(this, Color.white);
    final CursorSprite selectedSprite = new CursorSprite(this, Color.blue);
    final CursorSprite firstLOSSprite = new CursorSprite(this, Color.red);
    final CursorSprite secondLOSSprite = new CursorSprite(this, Color.red);
    Entity en_Deployer;
    boolean useLOSTool = true, showAllDeployment, showLobbyPlayerDeployment, chatterBoxActive, shouldIgnoreKeys;
    Coords lastCursor, firstLOS, movementTarget;
    private BoardTactical.Ruler ruler;
    List<Coords> highlightedEntityHexes = new ArrayList<>(), demolitionChargeHighlightHexes = new ArrayList<>();
    private final Set<Integer> selectedEntities = new HashSet<>(), ecmEntities = new HashSet<>();
    Map<Coords, Color> ecmHexes = Map.of(), eccmHexes = Map.of(), ecmCenters = Map.of(), eccmCenters = Map.of();
    /** The 3D board's E(C)CM, rebuilt with the Legacy 2D maps above by {@link #updateEcmList()}. */
    private BoardElectronicWarfare electronicWarfare = BoardElectronicWarfare.EMPTY;
    private Shape[] facingPolys, movementPolys, finalFacingPolys;
    private Shape upArrow, downArrow;
    Rectangle displayablesRect = new Rectangle();

    private Coords selected;
    private Coords deploymentPreview;
    private MovePath plannedMovement;
    private BoardFocus focus = new BoardFocus(0, null);
    private Runnable changed = () -> { };
    private long revision;
    private boolean closed;
    private final boolean ownsTileset;
    private Board observedBoard;
    private Consumer<Entity> entityRenderer = entity -> { };
    private Supplier<Boolean> inputEnabled = () -> true;
    private final List<Runnable> keyRegistrations = new ArrayList<>();
    private boolean movingUnits;

    public BoardClientState(Game game, MegaMekController controller, @Nullable ClientGUI gui, int boardId,
          @Nullable TilesetManager tileset) throws IOException {
        this.game = Objects.requireNonNull(game);
        this.boardId = boardId;
        clientgui = gui;
        ownsTileset = tileset == null;
        fieldOfView = new FovHighlightingAndDarkening(this);
        initPolys();
        try {
            tileManager = ownsTileset ? new TilesetManager(game) : tileset;
        } catch (IOException | RuntimeException failure) {
            fieldOfView.die();
            throw failure;
        }
        showAllDeployment = GUIP.getBoolean(GUIPreferences.SHOW_DEPLOY_ZONES_ARTY_AUTO);
        if (controller != null) {
            keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.TOGGLE_CHAT,
                  this::shouldReceiveKeyCommands, () -> openChat(false)));
            keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.TOGGLE_CHAT_CMD,
                  this::shouldReceiveKeyCommands, () -> openChat(true)));
            keyRegistrations.add(controller.registerCommandAction(KeyCommandBind.CENTER_ON_SELECTED,
                  this::shouldReceiveKeyCommands, this::centerOnSelected));
        }
        SpecialHexDisplay.Type.ARTILLERY_MISS.init();
        SpecialHexDisplay.Type.ARTILLERY_HIT.init();
        SpecialHexDisplay.Type.ARTILLERY_DRIFT.init();
        SpecialHexDisplay.Type.ARTILLERY_INCOMING.init();
        SpecialHexDisplay.Type.ARTILLERY_TARGET.init();
        SpecialHexDisplay.Type.ARTILLERY_ADJUSTED.init();
        SpecialHexDisplay.Type.ARTILLERY_AUTO_HIT.init();
        SpecialHexDisplay.Type.BOMB_MISS.init();
        SpecialHexDisplay.Type.BOMB_HIT.init();
        SpecialHexDisplay.Type.BOMB_DRIFT.init();
        SpecialHexDisplay.Type.PLAYER_NOTE.init();
        SpecialHexDisplay.Type.ORBITAL_BOMBARDMENT.init();
        SpecialHexDisplay.Type.ORBITAL_BOMBARDMENT_INCOMING.init();
        SpecialHexDisplay.Type.NUKE_HIT.init();
        SpecialHexDisplay.Type.NUKE_INCOMING.init();
        game.addGameListener(gameListener);
        GUIP.addPreferenceChangeListener(preferenceListener);
        PreferenceManager.getClientPreferences().addPreferenceChangeListener(preferenceListener);
        observedBoard = getBoard();
        if (observedBoard != null) { observedBoard.addBoardListener(boardListener); }
    }

    public Game getGame() { return game; }
    public int getBoardId() { return boardId; }
    public Board getBoard() { return game.getBoard(boardId); }
    public Entity getDisplayedEntity() { return clientgui == null ? null : clientgui.getDisplayedUnit(); }
    public Coords getSelected() { return selected; }
    public long getRevision() { return revision; }
    public FovHighlightingAndDarkening getFieldOfView() { return fieldOfView; }

    /** A renderer may subscribe to invalidation; it does not own the state or calculate visibility. */
    void setChanged(Runnable changed) { this.changed = Objects.requireNonNull(changed); }

    public void setSelected(Coords coords) {
        if (!Objects.equals(selected, coords)) {
            selected = coords;
            visibilityChanged();
        }
    }

    /**
     * The hex the deploying unit is previewed on (under the pointer or pinned while it is aimed), or null: in the
     * deployment phase the field of view looks from there rather than from the unit, which has no position yet.
     */
    public void setDeploymentPreview(Coords coords) {
        if (!Objects.equals(deploymentPreview, coords)) {
            deploymentPreview = coords;
            visibilityChanged();
        }
    }

    public Coords getDeploymentPreview() { return deploymentPreview; }

    /** Retains the phase controller's actual path, not a path reconstructed from rendering artifacts. */
    public void setPlannedMovement(MovePath path) {
        plannedMovement = path;
        visibilityChanged();
    }

    public MoveStep getLastMovementStep() {
        return plannedMovement == null ? null : plannedMovement.getLastStep();
    }

    public void centerOn(Coords coords, int entityId) {
        if (coords != null) {
            focus = new BoardFocus(focus.sequence() + 1, coords, entityId);
            changed.run();
        }
    }

    public boolean shouldFovHighlight() {
        return GUIPreferences.getInstance().getFovHighlight() && !game.getPhase().isReport();
    }

    public boolean shouldFovDarken() {
        return GUIPreferences.getInstance().getFovDarken() && !game.getPhase().isReport();
    }

    public void visibilityChanged() {
        fieldOfView.invalidate();
        revision++;
        changed.run();
    }

    public BoardFieldOfView captureFieldOfView(Rectangle requestedArea) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Field of view must be captured on the Swing event thread");
        }
        var preferences = GUIPreferences.getInstance();
        if (!shouldFovDarken() && !shouldFovHighlight()) {
            return BoardFieldOfView.EMPTY;
        }
        int width = getBoard().getWidth(), height = getBoard().getHeight();
        Rectangle area = new Rectangle(requestedArea);
        area.grow(1, 1);
        area = area.intersection(new Rectangle(0, 0, width, height));
        List<BoardFieldOfView.Hex> hexes = new ArrayList<>(Collections.nCopies(width * height, BoardFieldOfView.Hex.NONE));
        for (int x = area.x; x < area.x + area.width; x++) {
            for (int y = area.y; y < area.y + area.height; y++) {
                hexes.set(x * height + y, fieldOfView.evaluate(new Coords(x, y)));
            }
        }
        BoardFieldOfView result = new BoardFieldOfView(width, height, hexes, preferences.getFovDarkenAlpha(),
              preferences.getFovHighlightAlpha(), shouldFovDarken(), preferences.getFovGrayscale(), preferences.getFovSpottingMode());
        return result.active() ? result : BoardFieldOfView.EMPTY;
    }

    @Override
    public void close() {
        if (closed) { return; }
        closed = true;
        changed = () -> { };
        entityRenderer = entity -> { };
        projection = null;
        drawMovingUnits = null;
        inputEnabled = () -> false;
        visibleArea = () -> new double[] { 0, 0, 1, 1 };
        fieldOfView.die();
        game.removeGameListener(gameListener);
        if (observedBoard != null) { observedBoard.removeBoardListener(boardListener); observedBoard = null; }
        GUIP.removePreferenceChangeListener(preferenceListener);
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferenceListener);
        keyRegistrations.forEach(Runnable::run);
        keyRegistrations.clear();
        overlays.forEach(IDisplayable::dispose);
        overlays.clear();
        hexDrawPlugins.clear();
        if (hexPick != null) { hexPick.cancel(); }
        listeners.clear();
        if (ownsTileset) { tileManager.close(); }
    }

    /** Optional drawing projection of an attached renderer; absent in a native gameplay session. */
    void setProjection(BoardGlyphContext projection) { this.projection = projection; }
    public ClientGUI getClientgui() { return clientgui; }
    public Player getLocalPlayer() { return localPlayer; }
    public void setLocalPlayer(Player player) { localPlayer = player; visibilityChanged(); }
    public void setLocalPlayer(int id) { setLocalPlayer(game.getPlayer(id)); }
    public Entity getSelectedEntity() { return getDisplayedEntity(); }
    public TilesetManager getTilesetManager() { return tileManager; }
    public float getScale() { return projection == null ? 1 : projection.getScale(); }
    public Dimension getHexSize() { return new Dimension((int) (HEX_W * getScale()), (int) (HEX_H * getScale())); }
    public int getVerticalOffset() { return projection == null ? 0 : projection.getVerticalOffset(); }
    public Point getHexLocation(Coords coords) {
        if (coords == null) { return null; }
        if (projection != null) { return projection.getHexLocation(coords); }
        return new Point((int) (coords.getX() * HEX_WC * getScale()),
              (int) ((coords.getY() * HEX_H + (coords.isXOdd() ? HEX_H / 2 : 0)) * getScale()));
    }
    public Point getCentreHexLocation(Coords coords) { return getCentreHexLocation(coords, false); }
    public Point getCentreHexLocation(Coords coords, boolean ignoreElevation) {
        if (projection != null) { return projection.getCentreHexLocation(coords, ignoreElevation); }
        Point point = getHexLocation(coords);
        point.translate(getHexSize().width / 2, getHexSize().height / 2);
        return point;
    }
    public Image getScaledImage(Image image, boolean cache) {
        if (projection != null) { return projection.getScaledImage(image, cache); }
        return getScale() == 1 ? image : ImageUtil.getScaledImage(image,
              Math.max(1, (int) (image.getWidth(null) * getScale())), Math.max(1, (int) (image.getHeight(null) * getScale())));
    }
    public BufferedImage createShadowMask(Image image) {
        BufferedImage mask = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mask.createGraphics();
        try { graphics.drawImage(image, 0, 0, null); graphics.setComposite(AlphaComposite.SrcIn); graphics.setColor(Color.BLACK);
              graphics.fillRect(0, 0, mask.getWidth(), mask.getHeight()); } finally { graphics.dispose(); }
        return mask;
    }
    public int getDropShadowDistance() { return projection == null ? 20 : projection.getDropShadowDistance(); }
    public FontMetrics getFontMetrics(Font font) {
        return fontMetrics.computeIfAbsent(font, key -> {
            Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try { return graphics.getFontMetrics(key); } finally { graphics.dispose(); }
        });
    }
    private Font getMinefieldFont() { return new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, Math.max(1, (int) (12 * getScale()))); }
    public void refreshDisplayables() { changed.run(); }
    public void repaint() { revision++; changed.run(); }
    public long getPlanarRevision() { return revision; }
    public BoardFocus getCenterRequest() { return focus; }
    public void setUseLosTool(boolean use) { useLOSTool = use; }
    public void setShowLobbyPlayerDeployment(boolean show) { showLobbyPlayerDeployment = show; repaint(); }
    public void addBoardViewListener(BoardViewListener listener) { if (!listeners.contains(listener)) { listeners.add(listener); } }
    public void removeBoardViewListener(BoardViewListener listener) { listeners.remove(listener); }

    /**
     * EDT: makes {@code pick} the board's running hex pick. One pick at a time on all the client's boards, as each holds
     * the one phase display still: a running one is cancelled.
     */
    public void startHexPick(BoardHexPick pick) {
        Set<BoardClientState> boards = new LinkedHashSet<>(List.of(this));
        if (clientgui != null) { boards.addAll(clientgui.boardStates()); }
        for (BoardClientState board : boards) {
            BoardHexPick running = board.hexPick;
            board.hexPick = null;
            if ((running != null) && (running != pick)) { running.cancel(); }
        }
        hexPick = pick;
    }

    /** EDT: {@code pick} has ended; another pick that has started since stays. */
    public void endHexPick(BoardHexPick pick) {
        if (hexPick == pick) { hexPick = null; }
    }

    /** EDT: the running hex pick, or null. */
    public @Nullable BoardHexPick getHexPick() { return hexPick; }
    public void addOverlay(IDisplayable overlay) { overlays.add(overlay); }
    public void removeOverlay(IDisplayable overlay) { overlays.remove(overlay); }
    private final List<HexDrawPlugin> hexDrawPlugins = new ArrayList<>();
    public void addHexDrawPlugin(HexDrawPlugin plugin) { hexDrawPlugins.add(plugin); repaint(); }
    public void drawHexPlugins(Graphics2D graphics, Coords coords) {
        for (HexDrawPlugin plugin : hexDrawPlugins) { plugin.draw(graphics, getBoard().getHex(coords), game, coords, this); }
    }
    public void addSprite(Sprite sprite) { addSprites(List.of(sprite)); }
    public void removeSprite(Sprite sprite) { removeSprites(List.of(sprite)); }
    public Set<Sprite> getAllSprites() { return Collections.unmodifiableSet(allSprites); }
    /** The marks handlers and dialogs published, in the order they were added. */
    public List<TacticalMark> getMarks() { return published.stream().map(Published::mark).toList(); }
    /** The planned move's shown steps: their arrows, costs and announcements. */
    public List<TacticalMark.Step> getPathSteps() { return pathSteps; }
    public List<TacticalMark.FlightPath> getFlightPath() { return flightPath; }
    public List<TacticalMark.MovementVector> getMovementVectors() { return Collections.unmodifiableList(movementVectors); }
    public List<TacticalMark.C3Link> getC3Links() { return Collections.unmodifiableList(c3Links); }
    public List<TacticalMark.VtolAttack> getVtolAttacks() { return Collections.unmodifiableList(vtolAttacks); }
    public List<TacticalMark.FlyOver> getFlyOvers() { return Collections.unmodifiableList(flyOvers); }
    public List<AttackAction> getAttacks() { return Collections.unmodifiableList(attacks); }
    public List<Coords> getStrafingCoords() { return Collections.unmodifiableList(strafingCoords); }
    public List<Coords> getHighlightedEntityHexes() { return Collections.unmodifiableList(highlightedEntityHexes); }
    public List<Coords> getDemolitionChargeHighlightHexes() {
        return Collections.unmodifiableList(demolitionChargeHighlightHexes);
    }
    /** The line-of-sight tool's ends on this board. */
    public List<TacticalMark.LosEnd> getLosEnds() {
        return Stream.of(losStart, losEnd).filter(coords -> coords != null && getBoard().contains(coords))
              .map(TacticalMark.LosEnd::new).toList();
    }
    /** Whether the board shows the units' movement vectors (advanced vector movement, Movement phase). */
    public boolean showsMovementVectors() { return game.useVectorMove() && game.getPhase().isMovement(); }
    public void addSprites(Collection<? extends Sprite> sprites) {
        allSprites.addAll(sprites);
        for (Sprite sprite : sprites) {
            if (sprite instanceof HexSprite hex && hex.isBehindTerrain()) { behindTerrainHexSprites.add(hex); }
            else { overTerrainSprites.add(sprite); }
        }
        repaint();
    }
    public void removeSprites(Collection<? extends Sprite> sprites) {
        allSprites.removeAll(sprites); overTerrainSprites.removeAll(sprites); behindTerrainHexSprites.removeAll(sprites);
        repaint();
    }
    /** Shows {@code marks} until they are removed by {@link #removeMarks} (the same instances). */
    public void addMarks(Collection<? extends TacticalMark> marks) {
        List<Sprite> sprites = new ArrayList<>();
        for (TacticalMark mark : marks) {
            Published entry = new Published(mark, legacySprites(mark));
            published.add(entry);
            sprites.addAll(entry.sprites());
        }
        addSprites(sprites);
    }
    public void removeMarks(Collection<? extends TacticalMark> marks) {
        Set<TacticalMark> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        removed.addAll(marks);
        List<Sprite> sprites = new ArrayList<>();
        published.removeIf(entry -> {
            if (!removed.contains(entry.mark())) {
                return false;
            }
            sprites.addAll(entry.sprites());
            return true;
        });
        removeSprites(sprites);
    }
    /** Legacy 2D: the sprites drawn for a published mark. */
    private List<Sprite> legacySprites(TacticalMark mark) {
        return switch (mark) {
            case TacticalMark.Envelope envelope ->
                  List.of(new MovementEnvelopeSprite(this, envelope.color(), envelope.coords(), envelope.borders()));
            case TacticalMark.WeaponRange range ->
                  List.of(new FieldOfFireSprite(this, range.bracket(), range.coords(), range.borders()));
            case TacticalMark.RangeLabel label -> List.of(new TextMarkerSprite(this, label.coords(), label.bracket()));
            case TacticalMark.ControlZone zone ->
                  List.of(new FieldOfFireSprite(this, zone.color(), zone.coords(), zone.borders()));
            case TacticalMark.SensorRange range ->
                  List.of(new SensorRangeSprite(this, range.bracket(), range.coords(), range.borders()));
            case TacticalMark.FacingModifier modifier -> List.of(new MovementModifierEnvelopeSprite(this, modifier));
            case TacticalMark.RouteDot dot -> List.of(new RouteDotSprite(this, dot.coords(), dot.color(), dot.diameter()));
            case TacticalMark.HexText text -> List.of(new TextMarkerSprite(this, text.coords(), text.text(), text.color()));
            case TacticalMark.CraneTarget target -> List.of(new CraneUnloadTargetSprite(this, target.coords(), target.color()));
            case TacticalMark.Scan scan -> List.of(new ScanSprite(this, scan.scanner(), scan.target(), scan.color()));
            case TacticalMark.Flag flag -> List.of(new HexFlagSprite(this, flag.coords(), flag.color(), flag.label(),
                  flag.progress(), flag.facing()));
            case TacticalMark.Cargo cargo -> List.of(new GroundObjectSprite(this, cargo.coords()));
            case TacticalMark.Illumination light -> List.of(new FlareSprite(this, light.flare()));
            case TacticalMark.CollapseWarning warning -> List.of(new CollapseWarningSprite(this, warning.coords()));
            case TacticalMark.SawClearing saw -> List.of(new SawClearingSprite(this, saw.coords(), saw.turnsRemaining()));
            case TacticalMark.BridgeRepaired bridge -> List.of(new BridgeRepairedSprite(this, bridge.coords()));
            case TacticalMark.BridgeBuild bridge -> List.of(new BridgeBuildSprite(this, bridge.coords(),
                  bridge.turnsWorked(), bridge.turnsRequired(), bridge.exits(), bridge.hazardOutline()));
            case TacticalMark.DugIn dugIn -> List.of(new DugInSprite(this, dugIn.coords(), dugIn.complete(), dugIn.label()));
            case TacticalMark.Fortify fortify ->
                  List.of(new FortifyBuildSprite(this, fortify.coords(), fortify.stage(), fortify.stages()));
            // The cleared-path picture lies under the terrain, its counter over it.
            case TacticalMark.RubbleClear rubble -> List.of(
                  new RubbleClearSprite(this, rubble.coords(), rubble.turnsCompleted(), rubble.turnsRequired(), false),
                  new RubbleClearSprite(this, rubble.coords(), rubble.turnsCompleted(), rubble.turnsRequired(), true));
            case TacticalMark.CraneOperation crane -> List.of(new CraneOperationSprite(this, crane.coords(), crane.unit(),
                  crane.facing(), crane.turnsCompleted(), crane.turnsRequired()));
            case TacticalMark.FlyOver flyOver -> List.of(new FlyOverSprite(this, flyOver.entity()));
            // The state makes these marks itself and draws their sprites in its own lists and order (setPath, addC3,
            // addFlyOverPath, addMovementVector, the LOS cursors); none is published.
            case TacticalMark.VtolAttack attack -> List.of();
            case TacticalMark.C3Link link -> List.of();
            case TacticalMark.MovementVector vector -> List.of();
            case TacticalMark.Step step -> List.of();
            case TacticalMark.FlightPath indicator -> List.of();
            case TacticalMark.LosEnd end -> List.of();
        };
    }
    private void moveCursor(CursorSprite cursor, Coords coords) {
        if (coords == null) { cursor.setOffScreen(); } else { cursor.setHexLocation(coords); }
        repaint();
    }
    public void processBoardViewEvent(BoardViewEvent event) {
        // Copy the listener list to allow concurrent modification
        for (BoardViewListener l : new ArrayList<>(listeners)) {
            switch (event.getType()) {
                case BoardViewEvent.BOARD_HEX_CLICKED:
                case BoardViewEvent.BOARD_HEX_DOUBLE_CLICKED:
                case BoardViewEvent.BOARD_HEX_DRAGGED:
                case BoardViewEvent.BOARD_HEX_POPUP:
                    l.hexMoused(event);
                    break;
                case BoardViewEvent.BOARD_HEX_CURSOR:
                    l.hexCursor(event);
                    break;
                case BoardViewEvent.BOARD_HEX_HIGHLIGHTED:
                    l.boardHexHighlighted(event);
                    break;
                case BoardViewEvent.BOARD_HEX_SELECTED:
                    l.hexSelected(event);
                    break;
                case BoardViewEvent.BOARD_FIRST_LOS_HEX:
                    l.firstLOSHex(event);
                    break;
                case BoardViewEvent.BOARD_SECOND_LOS_HEX:
                    l.secondLOSHex(event);
                    break;
                case BoardViewEvent.FINISHED_MOVING_UNITS:
                    l.finishedMovingUnits(event);
                    break;
                case BoardViewEvent.SELECT_UNIT:
                    l.unitSelected(event);
                    break;
            }
        }
    }


    public synchronized void drawSprites(Graphics2D graphics2D, Collection<? extends Sprite> spriteArrayList) {
        for (Sprite sprite : spriteArrayList) {
            drawSprite(graphics2D, sprite);
        }
    }

    public void drawSprite(Graphics2D graphics2D, Sprite sprite) {
        Rectangle view = graphics2D.getClipBounds();

        // This can potentially be an expensive operation
        Rectangle spriteBounds = sprite.getBounds();
        if (view.intersects(spriteBounds) && !sprite.isHidden()) {
            if (!sprite.isReady()) {
                sprite.prepare();
            }
            sprite.drawOnto(graphics2D, spriteBounds.x, spriteBounds.y, null);
        }
    }

    public void drawDeployment(Graphics2D graphics2D, Coords coords) {
        for (Color color : deploymentColors(coords)) {
            drawHexBorder(graphics2D, getHexLocation(coords), color);
        }
    }

    /**
     * The selected deployer's border colours on {@code coords}, in painting order: yellow where it may deploy, cyan
     * where only at a higher elevation, the warning colour where the hex is deadly; empty without a deployer.
     */
    public List<Color> deploymentColors(Coords coords) {
        Board board = game.getBoard(boardId);
        if (en_Deployer == null || !board.isLegalDeployment(coords, en_Deployer)) {
            return List.of();
        }
        List<Color> colors = new ArrayList<>();
        boolean isAirDeployGround = en_Deployer.getMovementMode().isHover() || en_Deployer.getMovementMode().isVTOL();
        boolean isWiGE = en_Deployer.getMovementMode().isWiGE();
        boolean boardProhibited = en_Deployer.isBoardProhibited(board);

        if (en_Deployer.isAero()) {
            if (en_Deployer.getAltitude() > 0) {
                // Flying Aeros are always above it all
                if (!en_Deployer.isLocationProhibited(coords, boardId, board.getMaxElevation()) && !boardProhibited) {
                    colors.add(Color.yellow);
                }
            } else if (en_Deployer.getAltitude() == 0) {
                // Show prospective Altitude 1+ hexes
                if (!en_Deployer.isLocationProhibited(coords, boardId, 1) && !boardProhibited) {
                    colors.add(Color.cyan);
                }
            }
        } else if (isAirDeployGround || isWiGE) {
            // Draw hexes that are legal at a higher deployment elevation
            Hex hex = board.getHex(coords);
            // Default to Elevation 1 if ceiling + 1 <= 0.
            int maxHeight = (isWiGE) ? 1 : (hex != null) ? Math.max(hex.ceiling() + 1, 1) : 1;
            if (!en_Deployer.isLocationProhibited(coords, boardId, maxHeight) && !boardProhibited) {
                colors.add(Color.cyan);
            }
        } else if (en_Deployer instanceof AbstractBuildingEntity) {
            var deploymentHelper = new AllowedDeploymentHelper(en_Deployer, coords, board, board.getHex(coords), game);
            FacingOption facingOption = deploymentHelper.findAllowedFacings(0);
            if (facingOption != null && facingOption.hasValidFacings()) {
                // Draw hexes that're legal if we rotate
                if (!boardProhibited) {
                    colors.add(Color.yellow);
                }
            }
        }

        if (!en_Deployer.isLocationProhibited(BoardLocation.of(coords, boardId)) && !boardProhibited) {
            // Draw hexes that are legal at lowest deployment elevation
            colors.add(Color.yellow);
        }

        if (!en_Deployer.isLocationProhibited(BoardLocation.of(coords, boardId))
              && en_Deployer.isLocationDeadly(coords)) {
            colors.add(GUIP.getWarningColor());
        }
        return colors;
    }

    public void drawDeploymentBorders(Graphics2D graphics2D) {
        Rectangle view = graphics2D.getClipBounds();
        int firstX = (view.x / (int) (HEX_WC * getScale())) - 1;
        int firstY = (view.y / (int) (HEX_H * getScale())) - 1;
        int lastX = firstX + (view.width / (int) (HEX_WC * getScale())) + 3;
        int lastY = firstY + (view.height / (int) (HEX_H * getScale())) + 3;
        for (int x = firstX; x <= lastX; x++) {
            for (int y = firstY; y <= lastY; y++) {
                Coords coords = new Coords(x, y);
                if (getBoard().getHex(coords) != null) {
                    drawDeployment(graphics2D, coords);
                }
            }
        }
    }

    public void drawAllDeployment(Graphics2D graphics2D) {
        Rectangle view = graphics2D.getClipBounds();
        // only update visible hexes
        int drawX = (view.x / (int) (HEX_WC * getScale())) - 1;
        int drawY = (view.y / (int) (HEX_H * getScale())) - 1;

        int drawWidth = (view.width / (int) (HEX_WC * getScale())) + 3;
        int drawHeight = (view.height / (int) (HEX_H * getScale())) + 3;

        List<Player> players = deploymentPlayers();
        Board board = game.getBoard(boardId);
        // loop through the hexes
        for (int i = 0;
              i < drawHeight;
              i++) {
            for (int j = 0;
                  j < drawWidth;
                  j++) {
                Coords coords = new Coords(j + drawX, i + drawY);
                int pCount = 0;
                int bThickness = deploymentBorderWidth();
                // loop through all players
                for (Player player : players) {
                    if (board.isLegalDeployment(coords, player)) {
                        Color playerColor = player.getColour().getColour();
                        drawHexBorder(graphics2D,
                              getHexLocation(coords),
                              playerColor,
                              (bThickness + 2) * pCount,
                              bThickness);
                        pCount++;
                    }
                }
            }
        }
    }

    /** Whether the board shows every player's deployment area: while setting artillery hexes, or in the lobby. */
    public boolean showsAllDeployment() {
        return (game.getPhase().isSetArtilleryAutoHitHexes() && showAllDeployment)
              || (game.getPhase().isLounge() && showLobbyPlayerDeployment);
    }

    /** The width of one player's band in {@link #drawAllDeployment}; the bands nest 2 apart. */
    public int deploymentBorderWidth() {
        return 1 + 10 / game.getNoOfPlayers();
    }

    /** The players whose deployment areas {@link #drawAllDeployment} shows, in their band order. */
    public List<Player> deploymentPlayers() {
        List<Player> players = game.getPlayersList();
        final var gameOptions = game.getOptions();

        if (gameOptions.booleanOption(OptionsConstants.BASE_SET_PLAYER_DEPLOYMENT_TO_PLAYER_0)) {
            players = players.stream()
                  .filter(player -> player.isBot() || player.getId() == 0)
                  .collect(Collectors.toList());
        }

        if (game.getPhase().isLounge()
              && !localPlayer.isGameMaster()
              && (gameOptions.booleanOption(OptionsConstants.BASE_BLIND_DROP) || gameOptions.booleanOption(
              OptionsConstants.BASE_REAL_BLIND_DROP))) {
            players = players.stream().filter(player -> !player.isEnemyOf(localPlayer)).collect(Collectors.toList());
        }
        return players;
    }

    public static GradientPaint getGradientPaint(Color startingColor, float fogStripes, boolean reversed) {
        Color endingColor = new Color(startingColor.getRed() / 2,
              startingColor.getGreen() / 2,
              startingColor.getBlue() / 2,
              startingColor.getAlpha() / 2);

        // the numbers make the lines align across hexes
        // reversed changes stripe direction from bottom-left/top-right to top-left/bottom-right
        if (reversed) {
            return new GradientPaint(104.0f / fogStripes,
                  0.0f,
                  startingColor,
                  42.0f / fogStripes,
                  106.0f / fogStripes,
                  endingColor,
                  true);
        } else {
            return new GradientPaint(42.0f / fogStripes,
                  0.0f,
                  startingColor,
                  104.0f / fogStripes,
                  106.0f / fogStripes,
                  endingColor,
                  true);
        }
    }

    public void drawHexBorder(Graphics2D graphics2D, Color color, double padding, double lineWidth) {
        drawHexBorder(graphics2D, new Point(0, 0), color, padding, lineWidth);
    }

    public void drawHexBorder(Graphics2D graphics2D, Point point, Color col, double pad, double lineWidth) {
        graphics2D.setColor(col);
        graphics2D.fill(AffineTransform.getTranslateInstance(point.x, point.y)
              .createTransformedShape(AffineTransform.getScaleInstance(getScale(), getScale())
                    .createTransformedShape(HexDrawUtilities.getHexFullBorderArea(lineWidth, pad))));
    }

    public void drawHexBorder(Graphics2D graphics2D, Point point, Color color) {
        drawHexBorder(graphics2D, point, color, 0, 1);
    }

    private static Graphics2D atHex(Graphics2D graphics, Point location) {
        Graphics2D local = (Graphics2D) graphics.create();
        local.translate(location.x, location.y);
        return local;
    }

    public Mounted<?> getSelectedArtilleryWeapon() {
        // We don't want to display artillery auto-hit/adjusted fire hexes during the ArtyAutoHitHexes phase. These
        // could be displayed if the player uses the /reset command in some situations
        if (game.getPhase().isSetArtilleryAutoHitHexes()) {
            return null;
        }

        Mounted<?> selectedWeapon = selectedWeapon();

        if ((getSelectedEntity() == null) || (selectedWeapon == null)) {
            return null;
        }

        if (!getSelectedEntity().getOwner().equals(getLocalPlayer())) {
            return null; // Not my business to see this
        }

        if (getSelectedEntity().getEquipmentNum(selectedWeapon) == -1) {
            return null; // inconsistent state - weapon not on entity
        }

        if (!((selectedWeapon.getType() instanceof WeaponType) && selectedWeapon.getType()
              .hasFlag(WeaponType.F_ARTILLERY))) {
            return null; // not artillery
        }

        // otherwise, a weapon is selected, and it is artillery
        return selectedWeapon;
    }

    @Nullable
    public Mounted<?> selectedWeapon() {
        return (clientgui != null) ? clientgui.getDisplayedWeapon().orElse(null) : null;
    }

    public void drawEntityHexHighlights(Graphics2D graphics) {
        graphics.setColor(UIUtil.uiGreen());
        graphics.setStroke(new BasicStroke((float) (2.0 * getScale())));

        Shape border = AffineTransform.getScaleInstance(getScale(), getScale())
              .createTransformedShape(HexDrawUtilities.getHexFullBorderLine(0));
        for (Coords hex : highlightedEntityHexes) {
            Graphics2D local = atHex(graphics, getHexLocation(hex));
            try {
                local.draw(border);
            } finally {
                local.dispose();
            }
        }
    }

    public void drawDemolitionChargeHighlights(Graphics2D graphics) {
        if (demolitionChargeHighlightHexes.isEmpty()) {
            return;
        }
        boolean hazard = GUIP.getDemolitionChargeHazardOutline();
        Shape border = AffineTransform.getScaleInstance(getScale(), getScale())
              .createTransformedShape(HexDrawUtilities.getHexFullBorderLine(0));
        for (Coords hex : demolitionChargeHighlightHexes) {
            Graphics2D local = atHex(graphics, getHexLocation(hex));
            try {
                if (hazard) {
                    float boldWidth = (float) Math.max(3.0, 4.0 * getScale());
                    // Black base pass, then a yellow dashed pass on top so the gaps show black underneath - a hazard stripe.
                    local.setColor(Color.BLACK);
                    local.setStroke(new BasicStroke(boldWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    local.draw(border);
                    float dash = (float) Math.max(6.0, 10.0 * getScale());
                    local.setColor(DEMO_CHARGE_HAZARD_COLOR);
                    local.setStroke(new BasicStroke(boldWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                          10.0f, new float[] { dash, dash }, 0.0f));
                    local.draw(border);
                } else {
                    local.setColor(UIUtil.uiGreen());
                    local.setStroke(new BasicStroke((float) (2.0 * getScale())));
                    local.draw(border);
                }
            } finally {
                local.dispose();
            }
        }
    }

    public void drawOrbitalBombardmentHexes(Graphics2D boardGraphics) {
        Image orbitalBombardmentImage = tileManager.getOrbitalBombardmentImage();
        Rectangle view = boardGraphics.getClipBounds();

        // Compute the origin of the viewing area
        int drawX = (view.x / (int) (HEX_WC * getScale())) - 1;
        int drawY = (view.y / (int) (HEX_H * getScale())) - 1;

        // Compute size of viewing area
        int drawWidth = (view.width / (int) (HEX_WC * getScale())) + 3;
        int drawHeight = (view.height / (int) (HEX_H * getScale())) + 3;

        // Draw incoming artillery sprites - requires server to update client's view of game
        for (Enumeration<OrbitalBombardment> attacks = game.getOrbitalBombardmentAttacks();
              attacks.hasMoreElements(); ) {
            final OrbitalBombardment orbitalBombardment = attacks.nextElement();
            final Coords coords = new Coords(orbitalBombardment.getX(), orbitalBombardment.getY());
            // Is the Coord within the viewing area?
            boolean insideViewArea = ((coords.getX() >= drawX)
                  && (coords.getX() <= (drawX + drawWidth))
                  && (coords.getY() >= drawY)
                  && (coords.getY() <= (drawY + drawHeight)));
            if (insideViewArea) {
                Point hexLocation = getHexLocation(coords);
                boardGraphics.drawImage(getScaledImage(orbitalBombardmentImage, true),
                      hexLocation.x,
                      hexLocation.y,
                      null);
                for (Coords atDistanceCoords : coords.allAtDistanceOrLess(orbitalBombardment.getRadius())) {
                    Point location = getHexLocation(atDistanceCoords);
                    boardGraphics.drawImage(getScaledImage(orbitalBombardmentImage, true),
                          location.x,
                          location.y,
                          null);
                }
            }
        }
    }

    public void drawArtilleryHexes(Graphics2D graphics2D) {
        Rectangle area = markerHexArea(graphics2D);
        for (BoardMarker marker : artilleryMarkers()) {
            if (!area.contains(marker.coords().getX(), marker.coords().getY())) {
                continue;
            }
            int icon = switch (marker.kind()) {
                case ARTILLERY_AUTO_HIT -> TilesetManager.ARTILLERY_AUTO_HIT;
                case ARTILLERY_ADJUSTED -> TilesetManager.ARTILLERY_ADJUSTED;
                default -> TilesetManager.ARTILLERY_INCOMING;
            };
            Point location = getHexLocation(marker.coords());
            graphics2D.drawImage(getScaledImage(tileManager.getArtilleryTarget(icon), true),
                  location.x, location.y, null);
        }
    }

    public List<BoardMarker> artilleryMarkers() {
        List<BoardMarker> result = new ArrayList<>();
        for (Enumeration<ArtilleryAttackAction> attacks = game.getArtilleryAttacks(); attacks.hasMoreElements();) {
            Targetable target = attacks.nextElement().getTarget(game);
            if (isOnThisBord(target)) {
                result.add(boardMarker(BoardMarker.Kind.ARTILLERY_INCOMING, target.getPosition(), ""));
            }
        }
        Mounted<?> weapon = getSelectedArtilleryWeapon();
        if (weapon != null) {
            for (ArtilleryModifier modifier : Objects.requireNonNull(getSelectedEntity()).aTracker.getWeaponModifiers(weapon)) {
                boolean automatic = modifier.getModifier() == TargetRoll.AUTOMATIC_SUCCESS;
                result.add(boardMarker(automatic ? BoardMarker.Kind.ARTILLERY_AUTO_HIT : BoardMarker.Kind.ARTILLERY_ADJUSTED,
                      modifier.getCoords(), automatic ? "" : Integer.toString(modifier.getModifier())));
            }
        }
        return result;
    }

    public Rectangle markerHexArea(Graphics2D graphics) {
        Rectangle clip = graphics.getClipBounds();
        return new Rectangle(clip.x / (int) (HEX_WC * getScale()) - 1, clip.y / (int) (HEX_H * getScale()) - 1,
              clip.width / (int) (HEX_WC * getScale()) + 4, clip.height / (int) (HEX_H * getScale()) + 4);
    }

    public void drawMinefields(Graphics2D graphics2D) {
        Rectangle area = markerHexArea(graphics2D);
        for (BoardMarker marker : minefieldMarkers()) {
            Coords coords = marker.coords();
            if (!area.contains(coords.getX(), coords.getY())) {
                continue;
            }
            Point hexLocation = getHexLocation(coords);
            graphics2D.drawImage(getScaledImage(tileManager.getMinefieldSign(), true),
                  hexLocation.x, hexLocation.y + (int) (10 * getScale()), null);
            graphics2D.setColor(Color.black);
            boolean vibrabomb = game.getNbrMinefields(coords) == 1
                  && game.getMinefields(coords).getFirst().getType() == Minefield.TYPE_VIBRABOMB;
            int lineY = vibrabomb ? 22 : 31;
            for (String line : marker.label().split("\\n")) {
                drawCenteredString(line, hexLocation.x, hexLocation.y + (int) (lineY * getScale()), getMinefieldFont(), graphics2D);
                lineY += 9;
            }
        }
    }

    public List<BoardMarker> minefieldMarkers() {
        List<BoardMarker> result = new ArrayList<>();
        for (Enumeration<Coords> mined = game.getMinedCoords(); mined.hasMoreElements();) {
            Coords coords = mined.nextElement();
            if (!getBoard().contains(coords)) {
                continue;
            }
            String label = "";
            if (game.getNbrMinefields(coords) > 1) {
                label = Messages.getString("BoardView1.Multiple");
            } else if (game.getNbrMinefields(coords) == 1) {
                Minefield minefield = game.getMinefields(coords).getFirst();
                label = switch (minefield.getType()) {
                    case Minefield.TYPE_CONVENTIONAL -> Messages.getString("BoardView1.Conventional") + minefield.getDensity() + ")";
                    case Minefield.TYPE_INFERNO -> Messages.getString("BoardView1.Inferno") + minefield.getDensity() + ")";
                    case Minefield.TYPE_ACTIVE -> Messages.getString("BoardView1.Active") + minefield.getDensity() + ")";
                    case Minefield.TYPE_COMMAND_DETONATED -> Messages.getString("BoardView1.Command-") + "\n"
                          + Messages.getString("BoardView1.detonated") + minefield.getDensity() + ")";
                    case Minefield.TYPE_VIBRABOMB -> Messages.getString("BoardView1.Vibrabomb")
                          + (localPlayer != null && minefield.getPlayerId() == localPlayer.getId()
                                ? "\n(" + minefield.getSetting() + ")" : "");
                    case Minefield.TYPE_TRIPWIRE -> Messages.getString("BoardView1.Tripwire");
                    case Minefield.TYPE_PITFALL -> Messages.getString("BoardView1.Pitfall");
                    default -> "";
                };
            }
            BoardMarker marker = boardMarker(BoardMarker.Kind.MINEFIELD, coords, label);
            result.add(new BoardMarker(marker.kind(), coords, marker.elevation(), marker.rgb(), label,
                  minefieldDensity(coords)));
        }
        return result;
    }

    /** The combined density of the minefields in the hex, which sets how many mines the 3D board draws there. */
    private int minefieldDensity(Coords coords) {
        int density = 0;
        for (Minefield minefield : game.getMinefields(coords)) {
            density += minefield.getDensity();
        }
        return density;
    }

    public void drawDemolitionCharges(Graphics2D graphics2D) {
        Rectangle area = markerHexArea(graphics2D);
        for (BoardMarker marker : demolitionMarkers()) {
            if (area.contains(marker.coords().getX(), marker.coords().getY())) {
                drawDemolitionChargeLabel(graphics2D, getHexLocation(marker.coords()), marker.label());
            }
        }
    }

    /**
     * A marker on every unit a TAG has hit this round that the local player can see, where it stands now. The tags
     * arrive at the end of the Off-board phase and are cleared at the next round's Initiative, so the marker lasts
     * through the Firing phase, when homing and semi-guided rounds use it.
     */
    public List<BoardMarker> tagMarkers() {
        List<BoardMarker> result = new ArrayList<>();
        Set<Integer> tagged = new HashSet<>();
        for (TagInfo tag : game.getTagInfo()) {
            boolean isUnitTag = !tag.missed && (tag.targetType == Targetable.TYPE_ENTITY) && (tag.target != null);
            Entity unit = isUnitTag ? game.getEntity(tag.target.getId()) : null;
            if ((unit == null) || !tagged.add(unit.getId()) || !isShownTagged(unit)) {
                continue;
            }
            result.add(boardMarker(BoardMarker.Kind.TAGGED, unit.getPosition(), ""));
        }
        if (!tagged.equals(lastTagged)) {
            lastTagged = tagged;
            LOGGER.debug("[TAG] tagged units this round: {}, {} shown on this board", tagged, result.size());
        }
        return result;
    }

    /** Whether a tagged unit's marker belongs on this board: on it, placed, and seen by the local player. */
    private boolean isShownTagged(Entity unit) {
        boolean isPlaced = (unit.getPosition() != null) && (unit.getBoardId() == boardId)
              && getBoard().contains(unit.getPosition());
        return isPlaced && EntityVisibilityUtils.detectedOrHasVisual(localPlayer, game, unit);
    }

    public List<BoardMarker> demolitionMarkers() {
        List<BoardMarker> result = new ArrayList<>();
        if (localPlayer != null) {
            for (IBuilding building : getBoard().getBuildingsVector()) {
                for (DemolitionCharge charge : building.getDemolitionCharges()) {
                    if (charge.playerId == localPlayer.getId() && getBoard().contains(charge.pos)) {
                        result.add(boardMarker(BoardMarker.Kind.DEMOLITION_CHARGE, charge.pos,
                              GUIP.getDemolitionChargeColor().getRGB(),
                              Messages.getString("BoardView1.demoChargeSet", charge.damage)));
                    }
                }
            }
        }
        return result;
    }

    public void drawDemolitionChargeLabel(Graphics2D graphics2D, Point hexLocation, String label) {
        // The marker color is a client setting so players can adjust it for color vision deficiencies
        // and for visibility against the terrain colors of the current map
        Color demolitionChargeColor = GUIP.getDemolitionChargeColor();
        int centerX = hexLocation.x + (getHexSize().width / 2);
        int centerY = hexLocation.y + (getHexSize().height / 2);
        int radius = Math.max(3, (int) (7 * getScale()));
        int tickLength = Math.max(2, (int) (4 * getScale()));

        Stroke oldStroke = graphics2D.getStroke();
        // Outline pass (thicker, dark) below the colored pass keeps the crosshair visible on any terrain
        graphics2D.setStroke(new BasicStroke(Math.max(2.5f, 3f * getScale())));
        graphics2D.setColor(DEMO_CHARGE_OUTLINE_COLOR);
        drawCrosshair(graphics2D, centerX, centerY, radius, tickLength);
        graphics2D.setStroke(new BasicStroke(Math.max(1f, 1.5f * getScale())));
        graphics2D.setColor(demolitionChargeColor);
        drawCrosshair(graphics2D, centerX, centerY, radius, tickLength);
        graphics2D.setStroke(oldStroke);

        // Damage label on a dark backing pill below the crosshair
        FontMetrics metrics = getFontMetrics(getMinefieldFont());
        int stringWidth = metrics.stringWidth(label);
        int labelX = centerX - (stringWidth / 2);
        int labelY = centerY + radius + tickLength + metrics.getAscent() + 2;

        graphics2D.setColor(new Color(0, 0, 0, 160));
        graphics2D.fillRoundRect(labelX - 4, labelY - metrics.getAscent() - 1,
              stringWidth + 8, metrics.getAscent() + metrics.getDescent() + 2, 8, 8);

        graphics2D.setFont(getMinefieldFont());
        graphics2D.setColor(demolitionChargeColor);
        graphics2D.drawString(label, labelX, labelY);
    }

    public void drawCrosshair(Graphics2D graphics2D, int centerX, int centerY, int radius, int tickLength) {
        graphics2D.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        graphics2D.drawLine(centerX, centerY - radius - tickLength, centerX, centerY - radius + tickLength);
        graphics2D.drawLine(centerX, centerY + radius - tickLength, centerX, centerY + radius + tickLength);
        graphics2D.drawLine(centerX - radius - tickLength, centerY, centerX - radius + tickLength, centerY);
        graphics2D.drawLine(centerX + radius - tickLength, centerY, centerX + radius + tickLength, centerY);
        graphics2D.fillOval(centerX - 1, centerY - 1, 3, 3);
    }

    public void drawCenteredString(String string, int x, int y, Font font, Graphics2D graphics2D) {
        FontMetrics currentMetrics = getFontMetrics(font);
        int stringWidth = currentMetrics.stringWidth(string);
        x += ((getHexSize().width - stringWidth) / 2);
        graphics2D.setFont(font);
        graphics2D.drawString(string, x, y);
    }

    public void drawHeatMapTurnLabel(Collection<SpecialHexDisplay> heatMapMarkers, Graphics2D graphics2D,
          float scale) {
        String label = heatMapTurnLabel(heatMapMarkers);
        if (label == null) {
            return;
        }
        Color previousColor = graphics2D.getColor();
        graphics2D.setColor(Color.WHITE);
        drawCenteredString(label, 0, (int) (45 * getScale()), font_note, graphics2D);
        graphics2D.setColor(previousColor);
    }

    /** The font of the heat map's turn label. */
    public static Font heatMapLabelFont() { return font_note; }

    /** The one turn label for a hex's heat-map markers, their distinct values merged; null for none. */
    public String heatMapTurnLabel(Collection<SpecialHexDisplay> heatMapMarkers) {
        SortedSet<Integer> firingCountdowns = new TreeSet<>();
        SortedSet<Integer> predictedTurns = new TreeSet<>();
        for (SpecialHexDisplay marker : heatMapMarkers) {
            String info = marker.getInfo();
            if ((info == null) || !info.startsWith(SpecialHexDisplay.HEAT_MAP_PREFIX)) {
                continue;
            }
            // Control token is "<turn-or-countdown>:<heat>:<kind>".
            String[] fields = heatMapToken(info).split(":");
            if (fields.length == 0) {
                continue;
            }
            try {
                int value = Integer.parseInt(fields[0]);
                if ((fields.length >= 3) && SpecialHexDisplay.HEAT_MAP_KIND_FIRING.equals(fields[2])) {
                    firingCountdowns.add(value);
                } else {
                    predictedTurns.add(value);
                }
            } catch (NumberFormatException ignored) {
                // skip a marker whose turn value is not numeric
            }
        }

        String label;
        if (!firingCountdowns.isEmpty()) {
            // A firing marker's countdown wins over a predicted label; merge distinct countdowns with '/'.
            if ((firingCountdowns.size() == 1) && (firingCountdowns.first() == 0)) {
                label = Messages.getString("BoardView.artillery.splash");
            } else {
                label = Messages.getString("BoardView.artillery.firingCountdown", joinValues(firingCountdowns));
            }
        } else if (!predictedTurns.isEmpty()) {
            label = Messages.getString("BoardView.artillery.predictedTurns", joinValues(predictedTurns));
        } else {
            return null;
        }
        return label;
    }

    public String joinValues(Collection<Integer> values) {
        StringBuilder joined = new StringBuilder();
        for (Integer value : values) {
            if (joined.length() > 0) {
                joined.append('/');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    public String heatMapToken(String info) {
        int prefixLength = SpecialHexDisplay.HEAT_MAP_PREFIX.length();
        int space = info.indexOf(' ', prefixLength);
        return (space > prefixLength) ? info.substring(prefixLength, space) : info.substring(prefixLength);
    }

    public boolean isHeatMapMarker(SpecialHexDisplay specialHexDisplay) {
        String info = specialHexDisplay.getInfo();
        return (info != null) && info.startsWith(SpecialHexDisplay.HEAT_MAP_PREFIX);
    }

    public boolean isPredictedHeatMapMarker(SpecialHexDisplay specialHexDisplay) {
        String info = specialHexDisplay.getInfo();
        if ((info == null) || !info.startsWith(SpecialHexDisplay.HEAT_MAP_PREFIX)) {
            return false;
        }
        String[] fields = heatMapToken(info).split(":");
        return (fields.length >= 3) && SpecialHexDisplay.HEAT_MAP_KIND_PREDICTED.equals(fields[2]);
    }

    public int heatMapHeatUnits(String info) {
        String[] fields = heatMapToken(info).split(":");
        if (fields.length >= 2) {
            try {
                return Integer.parseInt(fields[1]);
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    public Color heatMapDivergingColor(int heatUnits) {
        float normalized = (float) (heatUnits - 1) / (HEAT_MAP_MAX_HEAT_UNITS - 1);
        normalized = Math.max(0.0f, Math.min(1.0f, normalized));
        float scaledPosition = normalized * (HEAT_MAP_COLOR_RAMP.length - 1);
        int lowerStop = (int) Math.floor(scaledPosition);
        int upperStop = Math.min(lowerStop + 1, HEAT_MAP_COLOR_RAMP.length - 1);
        float fraction = scaledPosition - lowerStop;
        Color from = HEAT_MAP_COLOR_RAMP[lowerStop];
        Color to = HEAT_MAP_COLOR_RAMP[upperStop];
        int red = Math.round(from.getRed() + (fraction * (to.getRed() - from.getRed())));
        int green = Math.round(from.getGreen() + (fraction * (to.getGreen() - from.getGreen())));
        int blue = Math.round(from.getBlue() + (fraction * (to.getBlue() - from.getBlue())));
        return new Color(red, green, blue);
    }

    public void drawHeatMapPredictedHex(SpecialHexDisplay specialHexDisplay, Graphics2D graphics2D, float scale) {
        Color previousColor = graphics2D.getColor();
        graphics2D.setColor(heatMapFill(specialHexDisplay));
        AffineTransform hexScale = new AffineTransform();
        hexScale.scale(getScale(), getScale());
        graphics2D.fill(hexScale.createTransformedShape(HEX_POLY));
        graphics2D.setColor(previousColor);
    }

    /** A predicted-position heat-map marker's translucent hex fill, cold to hot by its heat units. */
    public Color heatMapFill(SpecialHexDisplay specialHexDisplay) {
        Color heat = heatMapDivergingColor(heatMapHeatUnits(specialHexDisplay.getInfo()));
        return new Color(heat.getRed(), heat.getGreen(), heat.getBlue(), Math.round(255 * HEAT_MAP_FILL_ALPHA));
    }

    public void drawArtilleryDriftLines(Graphics2D graphics2D) {
        if (!GUIP.getShowArtilleryDriftArrows()) {
            return;
        }
        Board board = game.getBoard(boardId);
        if (board == null) {
            return;
        }
        Map<Coords, Collection<SpecialHexDisplay>> specialHexDisplays = board.getSpecialHexDisplayTable();
        if ((specialHexDisplays == null) || specialHexDisplays.isEmpty()) {
            return;
        }
        Stroke previousStroke = graphics2D.getStroke();
        Color previousColor = graphics2D.getColor();
        float dashLength = Math.max(4.0f, getHexSize().width / 14.0f);
        graphics2D.setColor(ARTILLERY_DRIFT_LINE_COLOR);
        graphics2D.setStroke(new BasicStroke(Math.max(1.0f, getHexSize().width / 60.0f), BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND, 1.0f, new float[] { dashLength, dashLength }, 0.0f));
        for (Map.Entry<Coords, Collection<SpecialHexDisplay>> entry : specialHexDisplays.entrySet()) {
            for (SpecialHexDisplay specialHexDisplay : entry.getValue()) {
                Coords landingHex = specialHexDisplay.getDriftHex();
                // Legacy 2D keeps its artillery drift lines only; bomb drift is the 3D board's.
                if ((landingHex == null) || (specialHexDisplay.getType() != SpecialHexDisplay.Type.ARTILLERY_MISS)
                      || !specialHexDisplay.drawNow(game.getPhase(), game.getRoundCount(), getLocalPlayer(), GUIP)) {
                    continue;
                }
                drawDriftLine(graphics2D, entry.getKey(), landingHex);
            }
        }
        graphics2D.setStroke(previousStroke);
        graphics2D.setColor(previousColor);
    }

    public void drawDriftLine(Graphics2D graphics2D, Coords targetedHex, Coords landingHex) {
        Point targetedPoint = getHexLocation(targetedHex);
        Point landingPoint = getHexLocation(landingHex);
        if ((targetedPoint == null) || (landingPoint == null)) {
            return;
        }
        int fromX = targetedPoint.x + (getHexSize().width / 2);
        int fromY = targetedPoint.y + (getHexSize().height / 2);
        int toX = landingPoint.x + (getHexSize().width / 2);
        int toY = landingPoint.y + (getHexSize().height / 2);
        graphics2D.drawLine(fromX, fromY, toX, toY);
        double angle = Math.atan2((double) toY - fromY, (double) toX - fromX);
        int headLength = Math.max(6, getHexSize().width / 6);
        double spread = Math.toRadians(28);
        int leftX = (int) Math.round(toX - (headLength * Math.cos(angle - spread)));
        int leftY = (int) Math.round(toY - (headLength * Math.sin(angle - spread)));
        int rightX = (int) Math.round(toX - (headLength * Math.cos(angle + spread)));
        int rightY = (int) Math.round(toY - (headLength * Math.sin(angle + spread)));
        graphics2D.drawLine(toX, toY, leftX, leftY);
        graphics2D.drawLine(toX, toY, rightX, rightY);
    }

    public void drawRulerCrosshair(Graphics2D graphics, Coords coords, Color color) {
        // Scale crosshair size to ~20% of hex width, with a minimum of 4px
        int radius = Math.max(4, (int) (HEX_W * getScale() * 0.10f));
        int crossLen = Math.max(6, (int) (HEX_W * getScale() * 0.15f));
        Point origin = getHexLocation(coords);
        Point center = getCentreHexLocation(coords);
        center.translate(-origin.x, -origin.y);
        Graphics2D local = atHex(graphics, origin);
        try {
            local.setStroke(new BasicStroke(Math.max(1.5f, getScale() * 1.5f)));

            // Outer circle
            local.setColor(color);
            local.drawOval(center.x - radius, center.y - radius, radius * 2, radius * 2);

            // Crosshair lines extending beyond the circle
            local.drawLine(center.x - crossLen, center.y, center.x + crossLen, center.y);
            local.drawLine(center.x, center.y - crossLen, center.x, center.y + crossLen);

            // Center dot
            int dotRadius = Math.max(1, (int) (getScale() * 1.5f));
            local.fillOval(center.x - dotRadius, center.y - dotRadius, dotRadius * 2, dotRadius * 2);
        } finally {
            local.dispose();
        }
    }

    public void drawRuler(Coords startCoords, Coords endCoords, Color startColor, Color endColor) {
        drawRuler(startCoords == null ? null : new BoardTactical.Ruler(startCoords, endCoords,
              getBoard().getHex(startCoords).getLevel() + 1,
              endCoords == null ? 0 : getBoard().getHex(endCoords).getLevel() + 1,
              null, null, startColor.getRGB(), endColor.getRGB()));
    }

    public void drawRuler(BoardTactical.Ruler measurement) {
        ruler = measurement;
        repaint();
    }

    public BoardTactical.Ruler getRuler() {
        return ruler;
    }

    public Coords getRulerStart() {
        return ruler == null ? null : ruler.start();
    }

    public Coords getRulerEnd() {
        return ruler == null ? null : ruler.end();
    }

    public void drawMovementData(Entity entity, MovePath movePath) {
        MoveStep previousStep = null;

        clearMovementData();
        setPlannedMovement(movePath);

        // Nothing to do if we don't have a MovePath
        if (movePath == null) {
            movementTarget = null;
            return;
        }
        // need to update the movement sprites based on the move path for this entity only way to do this is to clear
        // and refresh (seems wasteful)

        // first get the color for the vector
        Color color = Color.blue;
        if (movePath.getLastStep() != null) {
            color = switch (movePath.getLastStep().getMovementType(true)) {
                case MOVE_RUN, MOVE_VTOL_RUN, MOVE_OVER_THRUST -> GUIP.getMoveRunColor();
                case MOVE_SPRINT, MOVE_VTOL_SPRINT -> GUIP.getMoveSprintColor();
                case MOVE_JUMP -> GUIP.getMoveJumpColor();
                case MOVE_ILLEGAL -> GUIP.getMoveIllegalColor();
                default -> GUIP.getMoveDefaultColor();
            };
            movementTarget = movePath.getLastStep().getPosition();
        } else {
            movementTarget = null;
        }

        refreshMoveVectors(entity, movePath, color);

        List<TacticalMark.Step> steps = new ArrayList<>();
        for (ListIterator<MoveStep> i = movePath.getSteps();
              i.hasNext(); ) {
            final MoveStep step = i.next();
            boolean replacesPrevious = (step.getType() == MoveStepType.UP)
                  || (step.getType() == MoveStepType.DOWN)
                  || (step.getType() == MoveStepType.ACC)
                  || (step.getType() == MoveStepType.DEC)
                  || (step.getType() == MoveStepType.ACCELERATION)
                  || (step.getType() == MoveStepType.DECELERATION)
                  // for advanced movement, we always need to hide prior because costs will overlap, and we only
                  // want the current facing
                  || game.useVectorMove()
                  // A LAM converting from AirMek to Biped uses two convert steps, and we only want to
                  // show the last.
                  || ((previousStep != null) && (step.getType() == MoveStepType.CONVERT_MODE)
                  && (previousStep.getType() == MoveStepType.CONVERT_MODE))
                  || step.getType() == MoveStepType.BOOTLEGGER;
            if ((previousStep != null) && replacesPrevious) {
                // Drop the previous step's mark so that this one is drawn in its place without overlap.
                steps.removeLast();
            }
            steps.add(new TacticalMark.Step(step, movePath.isEndStep(step)));
            previousStep = step;
        }
        setPath(steps, flightPath(movePath));
        repaint();
    }

    /** The planned move's marks, and the Legacy 2D sprites made from them. */
    private void setPath(List<TacticalMark.Step> steps, List<TacticalMark.FlightPath> flight) {
        pathSteps = List.copyOf(steps);
        flightPath = List.copyOf(flight);
        pathSprites.clear();
        pathSteps.forEach(step -> pathSprites.add(new StepSprite(this, step.step(), step.last())));
        fpiSprites.clear();
        flightPath.forEach(indicator -> fpiSprites.add(new FlightPathIndicatorSprite(this, indicator)));
    }

    /** The flight path indicators of the remaining aerodyne velocity; none in vector movement or an illegal move. */
    private List<TacticalMark.FlightPath> flightPath(MovePath movePath) {
        List<TacticalMark.FlightPath> result = new ArrayList<>();
        // Don't attempt displaying Flight Path Indicators if using advanced aero movement.
        if (game.useVectorMove()) {
            return result;
        }

        // Don't calculate any kind of flight path indicators if the move is not legal.
        if (movePath.getLastStepMovementType() == EntityMovementType.MOVE_ILLEGAL) {
            return result;
        }

        // If the unit has remaining aerodyne velocity display the flight path indicators for remaining velocity.
        if ((movePath.getFinalVelocityLeft() > 0) && !movePath.nextForwardStepOffBoard()) {
            List<MoveStep> fpiSteps = new ArrayList<>();

            // Cloning the current movement path because we don't want to change its state.
            MovePath fpiPath = movePath.clone();

            // While velocity remains, add a forward step to the cloned movement path.
            while (fpiPath.getFinalVelocityLeft() > 0) {
                fpiPath.addStep(MoveStepType.FORWARDS);
                fpiSteps.add(fpiPath.getLastStep());

                // short circuit the flight path indicator if we are off the board.
                if (fpiPath.nextForwardStepOffBoard()) {
                    break;
                }
            }

            // For each hex in the entities forward trajectory, add a flight turn indicator.
            for (MoveStep moveStep : fpiSteps) {
                result.add(new TacticalMark.FlightPath(fpiSteps, fpiSteps.indexOf(moveStep), fpiPath.isEndStep(moveStep)));
            }
        }
        return result;
    }

    public void clearMovementData() {
        setPlannedMovement(null);
        setPath(List.of(), List.of());
        movementTarget = null;
        visibilityChanged();
        repaint();
        refreshMoveVectors();
    }

    public void addStrafingCoords(Coords coords) {
        strafingCoords.add(coords);
        repaint();
    }

    public void setStrafingCoords(Collection<Coords> coords) {
        strafingCoords.clear();
        strafingCoords.addAll(coords);
        repaint();
    }

    public void clearStrafingCoords() {
        strafingCoords.clear();
        repaint();
    }

    public Shape[] getFacingPolys() {
        return facingPolys;
    }

    public Shape[] getMovementPolys() {
        return movementPolys;
    }

    public Shape getUpArrow() {
        return upArrow;
    }

    public Shape getDownArrow() {
        return downArrow;
    }

    public void markDeploymentHexesFor(Entity ce) {
        en_Deployer = ce;
        repaint();
    }

    public Entity getDeployingEntity() {
        return en_Deployer;
    }

    public void addFlyOverPath(Entity entity) {
        if (entity.getPosition() == null) {
            return;
        }

        if (entity.isMakingVTOLGroundAttack()) {
            var attack = new TacticalMark.VtolAttack(entity);
            vtolAttacks.add(attack);
            vtolAttackSprites.add(new VTOLAttackSprite(this, attack));
        }
        flyOvers.add(new TacticalMark.FlyOver(entity));
        flyOverSprites.add(new FlyOverSprite(this, entity));
    }

    public ArrayList<Entity> getEntitiesFlyingOver(Coords coords) {
        ArrayList<Entity> entities = new ArrayList<>();
        for (TacticalMark.FlyOver flyOver : flyOvers) {
            // Space borne units shouldn't count here. They show up incorrectly in the firing display when sensors
            // are in use.
            if (flyOver.entity().getPassedThrough().contains(coords) && !flyOver.entity().isSpaceborne()) {
                entities.add(flyOver.entity());
            }
        }
        return entities;
    }

    public void addC3Link(Entity entity) {
        if (entity.getPosition() == null) {
            return;
        }

        if (entity.hasC3i()) {
            for (Entity entity1 : game.getEntitiesVector()) {
                if (entity1.getPosition() == null) {
                    return;
                }

                if (entity.onSameC3NetworkAs(entity1) && !entity1.equals(entity) && !ComputeECM.isAffectedByECM(entity,
                      entity.getPosition(),
                      entity1.getPosition())) {
                    addC3(entity, entity1);
                }
            }
        } else if (entity.hasNavalC3()) {
            for (Entity entity1 : game.getEntitiesVector()) {
                if (entity1.getPosition() == null) {
                    return;
                }

                if (entity.onSameC3NetworkAs(entity1) && !entity1.equals(entity)) {
                    addC3(entity, entity1);
                }
            }
        } else if (entity.hasNovaCEWS()) {
            // WOR Nova CEWS
            for (Entity entity1 : game.getEntitiesVector()) {
                if (entity1.getPosition() == null) {
                    return;
                }
                ECMInfo ecmInfo = ComputeECM.getECMEffects(entity,
                      entity.getPosition(),
                      entity1.getPosition(),
                      true,
                      null);
                if (entity.onSameC3NetworkAs(entity1)
                      && !entity1.equals(entity)
                      && (ecmInfo != null)
                      && !ecmInfo.isNovaECM()) {
                    addC3(entity, entity1);
                }
            }
        } else if (entity.getC3Master() != null) {
            Entity eMaster = entity.getC3Master();
            if (eMaster.getPosition() == null) {
                return;
            }

            // A unit whose C3 gear is switched off is not on the network, so neither end draws a link. The
            // non-hierarchic branches above get this from onSameC3NetworkAs(); the hierarchic branch does not
            // consult it, so the same check is applied here. Network wiring is left intact, so the links come
            // back when the gear is switched on again.
            if (EquipmentActivation.isC3SwitchedOff(entity) || EquipmentActivation.isC3SwitchedOff(eMaster)) {
                return;
            }

            // ECM cuts off the network
            boolean blocked;

            if (entity.hasBoostedC3() && eMaster.hasBoostedC3()) {
                blocked = ComputeECM.isAffectedByAngelECM(entity, entity.getPosition(), eMaster.getPosition())
                      || ComputeECM.isAffectedByAngelECM(eMaster, eMaster.getPosition(), eMaster.getPosition());
            } else {
                blocked = ComputeECM.isAffectedByECM(entity, entity.getPosition(), eMaster.getPosition())
                      || ComputeECM.isAffectedByECM(eMaster, eMaster.getPosition(), eMaster.getPosition());
            }

            if (!blocked) {
                addC3(entity, entity.getC3Master());
            }
        }
    }

    private void addC3(Entity entity, Entity master) {
        c3Links.add(new TacticalMark.C3Link(entity, master));
        c3Sprites.add(new C3Sprite(this, entity, master));
    }

    public void addAttack(AttackAction attackAction) {
        // Don't make sprites for unknown entities and sensor returns
        // cross-board attacks don't get attack arrows (for now, must possibly allow some A2G, O2G, A2A attacks later
        // when target/attacker hexes are not really but effectively on the same board)
        Entity weaponEntity = game.getEntity(attackAction.getEntityId());
        if (weaponEntity == null) {
            return;
        }
        Entity attacker = weaponEntity.getAttackingEntity();
        Targetable target = game.getTarget(attackAction.getTargetType(), attackAction.getTargetId());
        if ((attacker == null)
              || (target == null)
              || (target.getTargetType() == Targetable.TYPE_I_NARC_POD)
              || (target.getPosition() == null)
              || (attacker.getPosition() == null)
              || !game.onTheSameBoard(attacker, target)
              || !isOnThisBord(target)) {
            return;
        }
        if (EntityVisibilityUtils.onlyDetectedBySensors(getLocalPlayer(), attacker)) {
            return;
        }

        repaint();
        int attackerId = attackAction.getEntityId();
        for (AttackSprite sprite : attackSprites) {
            // can we just add this attack to an existing one?
            if ((sprite.getEntityId() == attackerId) && (sprite.getTargetId() == attackAction.getTargetId())) {
                // use existing attack, but add this weapon
                attacks.add(attackAction);
                sprite.addEntityAction(attackAction);
                rebuildAllSpriteDescriptions(attackerId);
                return;
            }
        }
        // no re-use possible, add a new one don't add a sprite for an artillery attack made by the other player
        if (attackAction instanceof WeaponAttackAction weaponAttackAction) {
            int ownerId = weaponAttackAction.getEntity(game).getOwner().getId();
            int teamId = weaponAttackAction.getEntity(game).getOwner().getTeam();

            if (attackAction.getTargetType() != Targetable.TYPE_HEX_ARTILLERY
                  || ownerId == getLocalPlayer().getId() || teamId == getLocalPlayer().getTeam()) {
                attacks.add(attackAction);
                attackSprites.add(new AttackSprite(this, attackAction));
            }
        } else {
            attacks.add(attackAction);
            attackSprites.add(new AttackSprite(this, attackAction));
        }
        rebuildAllSpriteDescriptions(attackerId);
    }

    public synchronized void removeAttacksFor(@Nullable Entity entity) {
        if (entity == null) {
            return;
        }

        int entityId = entity.getId();
        attacks.removeIf(attack -> attack.getEntityId() == entityId);
        attackSprites.removeIf(sprite -> sprite.getEntityId() == entityId);
        repaint();
    }

    public void refreshAttacks() {
        clearAllAttacks();
        for (Enumeration<EntityAction> i = game.getActions();
              i.hasMoreElements(); ) {
            EntityAction entityAction = i.nextElement();
            if (entityAction instanceof AttackAction attackAction) {
                addAttack(attackAction);
            }
        }

        for (Enumeration<AttackAction> i = game.getDisplacementAttacks();
             i.hasMoreElements(); ) {
            AttackAction attackAction = i.nextElement();
            if (attackAction instanceof PhysicalAttackAction physicalAttackAction) {
                addAttack(physicalAttackAction);
            }
        }
        repaint();
    }

    public void refreshMoveVectors() {
        clearAllMoveVectors();
        if (game.useVectorMove()) {
            for (Entity entity : game.getEntitiesVector()) {
                if (entity.getPosition() != null) {
                    addMovementVector(entity, entity.getVectors());
                }
            }
        }
    }

    public void refreshMoveVectors(Entity entity, MovePath movePath, Color color) {
        clearAllMoveVectors();
        if (game.useVectorMove()) {
            // same as normal but when I find the active entity I used the MovePath to get vector
            for (Entity entity1 : game.getEntitiesVector()) {
                if (entity1.getPosition() != null) {
                    boolean planned = (entity != null) && (entity1.getId() == entity.getId());
                    addMovementVector(entity1, planned ? movePath.getFinalVectors() : entity1.getVectors());
                }
            }
        }
    }

    private void addMovementVector(Entity entity, int[] vectors) {
        var vector = TacticalMark.MovementVector.of(entity, vectors, game.getBoard(), GUIP.getAttackArrowTransparency());
        movementVectors.add(vector);
        movementSprites.add(new MovementSprite(this, vector));
    }

    public void clearC3Networks() {
        c3Links.clear();
        c3Sprites.clear();
    }

    public void clearFlyOverPaths() {
        vtolAttacks.clear();
        vtolAttackSprites.clear();
        flyOvers.clear();
        flyOverSprites.clear();
    }

    public void clearAllAttacks() {
        attacks.clear();
        attackSprites.clear();
    }

    public void clearAllMoveVectors() {
        movementVectors.clear();
        movementSprites.clear();
    }

    /** The line-of-sight tool's ends, either null; the Legacy 2D cursors follow them. */
    private void setLosEnds(Coords start, Coords end) {
        losStart = start;
        losEnd = end;
        moveCursor(firstLOSSprite, start);
        moveCursor(secondLOSSprite, end);
    }

    public void firstLOSHex(Coords coords) {
        if (useLOSTool) {
            setLosEnds(coords, null);
        }
    }

    public void secondLOSHex(Coords targetCoords, Coords attackerCoords) {
        if (useLOSTool) {
            setLosEnds(losStart, targetCoords);
            // LOS calculation and display is handled by RulerDialog via the
            // BOARD_SECOND_LOS_HEX event fired by checkLOS()
        }
    }

    public void initPolys() {
        AffineTransform facingRotate = new AffineTransform();

        // facing polygons
        Polygon facingPolyTmp = new Polygon();
        facingPolyTmp.addPoint(41, 3);
        facingPolyTmp.addPoint(35, 9);
        facingPolyTmp.addPoint(41, 7);
        facingPolyTmp.addPoint(42, 7);
        facingPolyTmp.addPoint(48, 9);
        facingPolyTmp.addPoint(42, 3);

        // create the rotated shapes
        facingPolys = new Shape[8];
        for (int direction : allDirections) {
            facingPolys[direction] = facingRotate.createTransformedShape(facingPolyTmp);
            facingRotate.rotate(Math.toRadians(60), HEX_W / 2.0f, HEX_H / 2.0f);
        }

        // final facing polygons
        Polygon finalFacingPolyTmp = new Polygon();
        finalFacingPolyTmp.addPoint(41, 3);
        finalFacingPolyTmp.addPoint(21, 18);
        finalFacingPolyTmp.addPoint(41, 14);
        finalFacingPolyTmp.addPoint(42, 14);
        finalFacingPolyTmp.addPoint(61, 18);
        finalFacingPolyTmp.addPoint(42, 3);

        // create the rotated shapes
        facingRotate.setToIdentity();
        finalFacingPolys = new Shape[8];
        for (int direction : allDirections) {
            finalFacingPolys[direction] = facingRotate.createTransformedShape(finalFacingPolyTmp);
            facingRotate.rotate(Math.toRadians(60), HEX_W / 2.0f, HEX_H / 2.0f);
        }

        // movement polygons
        Polygon movementPolyTmp = getMovementPolyTmp();

        // create the rotated shapes
        facingRotate.setToIdentity();
        movementPolys = new Shape[8];
        for (int direction : allDirections) {
            movementPolys[direction] = facingRotate.createTransformedShape(movementPolyTmp);
            facingRotate.rotate(Math.toRadians(60), HEX_W / 2.0f, HEX_H / 2.0f);
        }

        // Up and Down Arrows
        facingRotate.setToIdentity();
        facingRotate.translate(0, -31);
        upArrow = facingRotate.createTransformedShape(movementPolyTmp);

        facingRotate.setToIdentity();
        facingRotate.rotate(Math.toRadians(180), HEX_W / 2.0f, HEX_H / 2.0f);
        facingRotate.translate(0, -31);
        downArrow = facingRotate.createTransformedShape(movementPolyTmp);
    }

    public static Polygon getMovementPolyTmp() {
        Polygon movementPolyTmp = new Polygon();
        movementPolyTmp.addPoint(47, 67);
        movementPolyTmp.addPoint(48, 66);
        movementPolyTmp.addPoint(42, 62);
        movementPolyTmp.addPoint(41, 62);
        movementPolyTmp.addPoint(35, 66);
        movementPolyTmp.addPoint(36, 67);

        movementPolyTmp.addPoint(47, 67);
        movementPolyTmp.addPoint(45, 68);
        movementPolyTmp.addPoint(38, 68);
        movementPolyTmp.addPoint(38, 69);
        movementPolyTmp.addPoint(45, 69);
        movementPolyTmp.addPoint(45, 68);

        movementPolyTmp.addPoint(45, 70);
        movementPolyTmp.addPoint(38, 70);
        movementPolyTmp.addPoint(38, 71);
        movementPolyTmp.addPoint(45, 71);
        movementPolyTmp.addPoint(45, 68);
        return movementPolyTmp;
    }

    public BufferedImage captureOverlayImage(Dimension size, Dimension pixels) {
        BufferedImage image = new BufferedImage(Math.max(1, pixels.width), Math.max(1, pixels.height),
              BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = overlayGraphics(image, size, pixels);
        try {
            for (IDisplayable overlay : overlays) {
                overlay.draw(graphics, new Rectangle(size));
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public boolean overlayInput(int event, Point point, Dimension size, Dimension pixels) {
        // Drawing establishes widget bounds for this viewport before hit testing.
        if (event != MouseEvent.MOUSE_MOVED) {
            captureOverlayImage(size, pixels);
        }
        for (IDisplayable overlay : overlays) {
            boolean handled = switch (event) {
                case MouseEvent.MOUSE_PRESSED -> overlay.isHit(point, size);
                case MouseEvent.MOUSE_RELEASED -> overlay.isReleased();
                case MouseEvent.MOUSE_DRAGGED -> overlay.isDragged(point, size);
                default -> overlay.isMouseOver(point, size);
            };
            if (handled) {
                return true;
            }
        }
        return false;
    }

    public int sidePanelInset() {
        return unitStripInset(false);
    }

    public int leftPanelInset() {
        return unitStripInset(true);
    }

    public int unitStripInset(boolean left) {
        return overlays.stream().filter(UnitOverviewOverlay.class::isInstance)
              .map(UnitOverviewOverlay.class::cast).filter(strip -> strip.isOnLeft() == left)
              .mapToInt(UnitOverviewOverlay::sidePanelInset).max().orElse(0);
    }

    public List<OverlayImage> captureOverlayLayers(Dimension size, Dimension pixels) {
        List<OverlayImage> layers = new ArrayList<>();
        Rectangle bounds = new Rectangle(size);
        Graphics2D metrics = overlayGraphics(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), size, pixels);
        BufferedImage pending = null;
        Graphics2D painter = null;
        try {
            for (IDisplayable overlay : overlays) {
                List<OverlayImage> captured = overlay.captureLayers(metrics, bounds);
                if (captured != null) {
                    if (pending != null) {
                        painter.dispose();
                        painter = null;
                        layers.add(new OverlayImage(pending, 0, 0, OverlayImage.Fade.OPAQUE));
                        pending = null;
                    }
                    layers.addAll(captured);
                } else {
                    // Consecutive widgets can share a raster, but must retain their order around faded panels.
                    if (pending == null) {
                        pending = new BufferedImage(Math.max(1, pixels.width), Math.max(1, pixels.height),
                              BufferedImage.TYPE_INT_ARGB);
                        painter = overlayGraphics(pending, size, pixels);
                    }
                    overlay.draw(painter, bounds);
                }
            }
            if (pending != null) {
                layers.add(new OverlayImage(pending, 0, 0, OverlayImage.Fade.OPAQUE));
            }
        } finally {
            metrics.dispose();
            if (painter != null) {
                painter.dispose();
            }
        }
        return List.copyOf(layers);
    }

    public static Graphics2D overlayGraphics(BufferedImage image, Dimension size, Dimension pixels) {
        Graphics2D graphics = image.createGraphics();
        // Layout and hit coordinates stay logical; artwork is rasterized at the display's native density.
        graphics.scale(Math.max(1, pixels.width) / (double) Math.max(1, size.width),
              Math.max(1, pixels.height) / (double) Math.max(1, size.height));
        UIUtil.setHighQualityRendering(graphics);
        return graphics;
    }

    public void setLastCursor(Coords lastCursor) {
        this.lastCursor = lastCursor;
    }

    public Coords getLastCursor() {
        return lastCursor;
    }

    public void setFirstLOS(Coords firstLOS) {
        this.firstLOS = firstLOS;
    }

    public Coords getFirstLOS() {
        return firstLOS;
    }

    public void select(Coords coords) {
        if ((coords == null) || game.getBoard(boardId).contains(coords)) {
            selectForInspection(coords);
            processBoardViewEvent(new BoardViewEvent(this, coords, BoardViewEvent.BOARD_HEX_SELECTED, 0));
        }
    }

    public void selectForInspection(Coords coords) {
        if ((coords == null) || game.getBoard(boardId).contains(coords)) {
            setSelected(coords);
            moveCursor(selectedSprite, coords);
            setLosEnds(null, null);
        }
    }

    public void select(int x, int y) {
        select(new Coords(x, y));
    }

    public void highlight(Coords coords) {
        if ((coords == null) || game.getBoard(boardId).contains(coords)) {
            moveCursor(highlightSprite, coords);
            setLosEnds(null, null);
            processBoardViewEvent(new BoardViewEvent(this, coords, BoardViewEvent.BOARD_HEX_HIGHLIGHTED, 0));
        }
    }

    public void setHighlightColor(Color color) {
        highlightSprite.setColor(color);
        highlightSprite.prepare();
        repaint();
    }

    public void highlight(int x, int y) {
        highlight(new Coords(x, y));
    }

    public void setHighlightedEntityHexes(List<Coords> hexes) {
        highlightedEntityHexes = new ArrayList<>(hexes);
        repaint();
    }

    public void setDemolitionChargeHighlightHexes(List<Coords> hexes) {
        demolitionChargeHighlightHexes = new ArrayList<>(hexes);
        repaint();
    }

    public void cursor(Coords coords) {
        if ((coords == null) || game.getBoard(boardId).contains(coords)) {
            if ((getLastCursor() == null) || (coords == null) || !coords.equals(getLastCursor())) {
                setLastCursor(coords);
                moveCursor(cursorSprite, coords);
                setLosEnds(null, null);
                processBoardViewEvent(new BoardViewEvent(this, coords, BoardViewEvent.BOARD_HEX_CURSOR, 0));
            } else {
                setLastCursor(coords);
            }
        }
    }

    public void cursor(int x, int y) {
        cursor(new Coords(x, y));
    }

    public void checkLOS(Coords c) {
        if ((c == null) || game.getBoard(boardId).contains(c)) {
            if (getFirstLOS() == null) {
                setFirstLOS(c);
                firstLOSHex(c);
                processBoardViewEvent(new BoardViewEvent(this, c, BoardViewEvent.BOARD_FIRST_LOS_HEX, 0));
            } else {
                secondLOSHex(c, getFirstLOS());
                processBoardViewEvent(new BoardViewEvent(this, c, BoardViewEvent.BOARD_SECOND_LOS_HEX, 0));
                setFirstLOS(null);
            }
        }
    }

    public void mouseAction(int x, int y, int mouseActionType, int modifiers, int mouseButton) {
        if (game.getBoard(boardId).contains(x, y)) {
            Coords coords = new Coords(x, y);
            switch (mouseActionType) {
                case BOARD_HEX_CLICK:
                    if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
                        checkLOS(coords);
                    } else {
                        processBoardViewEvent(new BoardViewEvent(this,
                              coords,
                              BoardViewEvent.BOARD_HEX_CLICKED,
                              modifiers,
                              mouseButton));
                    }
                    break;
                case BOARD_HEX_DOUBLE_CLICK:
                    processBoardViewEvent(new BoardViewEvent(this,
                          coords,
                          BoardViewEvent.BOARD_HEX_DOUBLE_CLICKED,
                          modifiers,
                          mouseButton));
                    break;
                case BOARD_HEX_DRAG:
                    processBoardViewEvent(new BoardViewEvent(this,
                          coords,
                          BoardViewEvent.BOARD_HEX_DRAGGED,
                          modifiers,
                          mouseButton));
                    break;
                case BOARD_HEX_POPUP:
                    processBoardViewEvent(new BoardViewEvent(this,
                          coords,
                          BoardViewEvent.BOARD_HEX_POPUP,
                          modifiers,
                          mouseButton));
                    break;
            }
        }
    }

    public void mouseAction(Coords coords, int eventType, int modifiers, int mouseButton) {
        mouseAction(coords.getX(), coords.getY(), eventType, modifiers, mouseButton);
    }

    public void processAffectedCoords(Coords coords, ECMEffects ecm, ECMEffects eccm, Map<Coords, Color> newECMHexes,
          Map<Coords, Color> newECCMHexes) {
        Color hexColorECM = null;

        if (ecm != null) {
            hexColorECM = ecm.getHexColor();
        }

        Color hexColorECCM = null;

        if (eccm != null) {
            hexColorECCM = eccm.getHexColor();
        }

        // Hex color is null if all effects cancel out
        if ((hexColorECM == null) && (hexColorECCM == null)) {
            return;
        }

        if ((hexColorECM != null) && (hexColorECCM == null)) {
            if (ecm.isECCM()) {
                newECCMHexes.put(coords, hexColorECM);
            } else {
                newECMHexes.put(coords, hexColorECM);
            }
        } else if (hexColorECM == null) {
            if (eccm.isECCM()) {
                newECCMHexes.put(coords, hexColorECCM);
            } else {
                newECMHexes.put(coords, hexColorECCM);
            }
        } else { // Both are non-null
            newECMHexes.put(coords, hexColorECM);
            newECCMHexes.put(coords, hexColorECCM);
        }
    }

    public boolean getChatterBoxActive() {
        return chatterBoxActive;
    }

    public void setChatterBoxActive(boolean chatterBoxActive) {
        this.chatterBoxActive = chatterBoxActive;
    }

    public void setShouldIgnoreKeys(boolean shouldIgnoreKeys) {
        this.shouldIgnoreKeys = shouldIgnoreKeys;
    }

    /** Finds a presentation overlay owned by this board, without keeping a second registry in the GUI. */
    public <T extends IDisplayable> @Nullable T getOverlay(Class<T> type) {
        return overlays.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    @Nullable
    public TurnDetailsOverlay getTurnDetailsOverlay() {
        return getOverlay(TurnDetailsOverlay.class);
    }

    public ArrayList<AttackSprite> getAttackSprites() {
        return attackSprites;
    }

    /**
     * The 3D board's lines from each hex an artillery round or a bomb missed to where it came down, under the same
     * conditions the flat board draws them: the drift arrows option is on and the miss is one this player sees now.
     */
    public List<BoardMarker> artilleryDriftMarkers() {
        Board board = game.getBoard(boardId);
        if (!GUIP.getShowArtilleryDriftArrows() || (board == null)) {
            return List.of();
        }
        List<BoardMarker> result = new ArrayList<>();
        board.getSpecialHexDisplayTable().forEach((aimed, displays) -> {
            for (SpecialHexDisplay display : displays) {
                Coords landing = display.getDriftHex();
                boolean isMiss = (display.getType() == SpecialHexDisplay.Type.ARTILLERY_MISS)
                      || (display.getType() == SpecialHexDisplay.Type.BOMB_MISS);
                boolean isShownNow = display.drawNow(game.getPhase(), game.getRoundCount(), getLocalPlayer(), GUIP);
                if (isMiss && (landing != null) && isShownNow) {
                    Hex hex = board.getHex(aimed);
                    result.add(new BoardMarker(BoardMarker.Kind.ARTILLERY_DRIFT, aimed, hex == null ? 0 : hex.ceiling(),
                          BoardMarker.Kind.ARTILLERY_DRIFT.rgb(), "", 0, landing));
                }
            }
        });
        return result;
    }

    public BoardMarker boardMarker(BoardMarker.Kind kind, Coords coords, String label) {
        return boardMarker(kind, coords, kind.rgb(), label);
    }

    public BoardMarker boardMarker(BoardMarker.Kind kind, Coords coords, int rgb, String label) {
        Hex hex = coords == null ? null : getBoard().getHex(coords);
        return new BoardMarker(kind, coords, hex == null ? 0 : hex.ceiling(), rgb, label);
    }

    /** The marker a published mark shows on the 3D board, or null. */
    private @Nullable BoardMarker boardMarker(TacticalMark mark) {
        return switch (mark) {
            case TacticalMark.Flag flag -> boardMarker(BoardMarker.Kind.OBJECTIVE, flag.coords(), flag.color().getRGB(),
                  (flag.label() == null ? "" : flag.label()) + (flag.progress() == null ? "" : " " + flag.progress()));
            // Cargo on the ground is a crate in its hex's scenery (BoardFeatures.withCargo).
            case TacticalMark.Cargo cargo -> null;
            case TacticalMark.Illumination light -> boardMarker(BoardMarker.Kind.FLARE, light.flare().position,
                  Integer.toString(light.flare().turnsToBurn));
            case TacticalMark.CollapseWarning warning ->
                  boardMarker(BoardMarker.Kind.COLLAPSE_WARNING, warning.coords(), "");
            case TacticalMark.SawClearing saw ->
                  boardMarker(BoardMarker.Kind.SAW_CLEARING, saw.coords(), Integer.toString(saw.turnsRemaining()));
            case TacticalMark.BridgeRepaired bridge -> boardMarker(BoardMarker.Kind.BRIDGE_REPAIRED, bridge.coords(), "");
            case TacticalMark.BridgeBuild bridge -> boardMarker(BoardMarker.Kind.BRIDGE_BUILD, bridge.coords(),
                  bridge.turnsWorked() + "/" + bridge.turnsRequired());
            case TacticalMark.DugIn dugIn -> boardMarker(BoardMarker.Kind.DUG_IN, dugIn.coords(), dugIn.label());
            case TacticalMark.Fortify fortify -> boardMarker(BoardMarker.Kind.FORTIFY_BUILD, fortify.coords(),
                  fortify.stage() + "/" + fortify.stages());
            case TacticalMark.RubbleClear rubble -> boardMarker(BoardMarker.Kind.RUBBLE_CLEAR, rubble.coords(),
                  rubble.displayTurn() + "/" + rubble.turnsRequired());
            default -> null;
        };
    }

    public List<BoardMarker> getBoardMarkers() {
        List<BoardMarker> result = new ArrayList<>();
        published.stream().map(entry -> boardMarker(entry.mark())).filter(Objects::nonNull).forEach(result::add);
        result.addAll(minefieldMarkers());
        result.addAll(demolitionMarkers());
        result.addAll(artilleryMarkers());
        result.addAll(tagMarkers());
        result.addAll(artilleryDriftMarkers());
        for (Enumeration<OrbitalBombardment> attacks = game.getOrbitalBombardmentAttacks(); attacks.hasMoreElements();) {
            OrbitalBombardment attack = attacks.nextElement();
            result.add(boardMarker(BoardMarker.Kind.ORBITAL_INCOMING, new Coords(attack.getX(), attack.getY()), ""));
        }
        getBoard().getSpecialHexDisplayTable().forEach((coords, displays) -> {
            for (SpecialHexDisplay display : displays) {
                BoardMarker.Kind kind = pointMarkerKind(display);
                if (kind != null && display.drawNow(game.getPhase(), game.getRoundCount(), getLocalPlayer(), GUIP)) {
                    result.add(boardMarker(kind, coords, ""));
                }
            }
        });
        return result.stream().filter(marker -> marker.coords() != null && getBoard().contains(marker.coords()))
              .distinct().sorted(Comparator.comparingInt((BoardMarker marker) -> marker.coords().getX())
                    .thenComparingInt(marker -> marker.coords().getY()).thenComparing(BoardMarker::kind)
                    .thenComparing(BoardMarker::label).thenComparingInt(BoardMarker::rgb)).toList();
    }

    public BoardMarker.Kind pointMarkerKind(SpecialHexDisplay display) {
        if (isHeatMapMarker(display)) {
            // A predicted position is a fill; a firing marker a note, its turn label the heat map's own
            return isPredictedHeatMapMarker(display) ? null : BoardMarker.Kind.PLAYER_NOTE;
        }
        return switch (display.getType()) {
            case ARTILLERY_AUTO_HIT -> BoardMarker.Kind.ARTILLERY_AUTO_HIT;
            case ARTILLERY_ADJUSTED -> BoardMarker.Kind.ARTILLERY_ADJUSTED;
            case ARTILLERY_INCOMING -> BoardMarker.Kind.ARTILLERY_INCOMING;
            case ARTILLERY_TARGET -> BoardMarker.Kind.ARTILLERY_TARGET;
            case NUKE_INCOMING -> BoardMarker.Kind.NUKE_INCOMING;
            case ORBITAL_BOMBARDMENT_INCOMING -> BoardMarker.Kind.ORBITAL_INCOMING;
            case PLAYER_NOTE -> BoardMarker.Kind.PLAYER_NOTE;
            default -> null;
        };
    }

    public List<TacticalMark.WeaponRange> getWeaponRanges() {
        return published.stream().map(Published::mark).filter(TacticalMark.WeaponRange.class::isInstance)
              .map(TacticalMark.WeaponRange.class::cast).toList();
    }

    public List<TacticalMark.RangeLabel> getRangeLabels() {
        return BoardTactical.SCROLLING_RANGE_LABELS ? List.of() : published.stream().map(Published::mark)
              .filter(TacticalMark.RangeLabel.class::isInstance).map(TacticalMark.RangeLabel.class::cast).toList();
    }

    public boolean isOnThisBord(@Nullable Targetable targetable) {
        return (targetable != null) && targetable.getBoardId() == boardId;
    }

    public boolean isOnThisBord(BoardLocation boardLocation) {
        return boardLocation.isOn(boardId);
    }

    public static Color colorAt(Map<Coords, Color> colors, Coords coords) {
        return colors == null ? null : colors.get(coords);
    }

    /**
     * The Legacy 2D look of E(C)CM: player-colour washes, static noise and source rings. The 3D board draws
     * {@link #electronicWarfare()} instead.
     */
    public void drawElectronicWarfare(Graphics2D graphics, Coords coords) {
        Graphics2D local = (Graphics2D) graphics.create();
        try {
            Color ecm = colorAt(ecmHexes, coords);
            if (ecm != null) {
                drawFieldTint(local, ecm);
                Image noise = getScaledImage(tileManager.getEcmStaticImage(ecm), false);
                local.drawImage(noise, 0, 0, noise.getWidth(null), noise.getHeight(null), null);
            }
            drawFieldTint(local, colorAt(eccmHexes, coords));
            drawFieldSource(local, colorAt(ecmCenters, coords));
            drawFieldSource(local, colorAt(eccmCenters, coords));
        } finally {
            local.dispose();
        }
    }

    public void drawFieldTint(Graphics2D graphics, Color tint) {
        if (tint != null) {
            graphics.setColor(tint);
            graphics.fill(AffineTransform.getScaleInstance(getScale(), getScale()).createTransformedShape(HEX_POLY));
        }
    }

    public void drawFieldSource(Graphics2D graphics, Color tint) {
        if (tint != null) {
            drawHexBorder(graphics, tint.darker(), 5, 10);
        }
    }

    public void drawMapSheetBorders(Graphics2D graphics, Coords coords) {
        int borders = GUIP.getShowMapSheets() ? mapSheetBorders(coords) : 0;
        if (borders == 0) {
            return;
        }
        Path2D path = new Path2D.Double();
        for (int direction = 0; direction < 6; direction++) {
            if ((borders & (1 << direction)) != 0) {
                path.append(HexDrawUtilities.getHexBorderLine(direction), false);
            }
        }
        Graphics2D local = (Graphics2D) graphics.create();
        try {
            local.scale(getScale(), getScale());
            local.setColor(GUIP.getMapsheetColor());
            local.setStroke(new BasicStroke(1 / getScale(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            local.draw(path);
        } finally {
            local.dispose();
        }
    }

    /** The sides (bits by direction) of {@code coords} on the edge of its 16 x 17 hex map sheet. */
    public static int mapSheetBorders(Coords coords) {
        int borders = 0;
        if (coords.getX() % 16 == 0) {
            borders |= (1 << 4) | (1 << 5);
        } else if (coords.getX() % 16 == 15) {
            borders |= (1 << 1) | (1 << 2);
        }
        if (coords.getY() % 17 == 0) {
            borders |= 1;
            if (coords.getX() % 2 == 0) {
                borders |= (1 << 1) | (1 << 5);
            }
        } else if (coords.getY() % 17 == 16) {
            borders |= 1 << 3;
            if (coords.getX() % 2 == 1) {
                borders |= (1 << 2) | (1 << 4);
            }
        }
        return borders;
    }

    public void drawEmbeddedBoard(Graphics2D graphics) {
        Graphics2D local = (Graphics2D) graphics.create();
        try {
            local.scale(getScale(), getScale());
            local.setColor(new Color(0, 140, 0, 120));
            local.fillRect(HEX_W / 4 + 1, 2, HEX_W / 2 - 2, HEX_H - 4);
            local.setColor(new Color(0, 140, 0));
            local.setStroke(new BasicStroke(1.5f));
            local.drawRect(HEX_W / 4 + 1, 2, HEX_W / 2 - 2, HEX_H - 4);
        } finally {
            local.dispose();
        }
    }

    public void toggleShowDeployment() {
        showAllDeployment = !showAllDeployment;
        repaint();
    }

    public void rebuildAllSpriteDescriptions(int attackerId) {
        for (AttackSprite sprite : attackSprites) {
            if (sprite.getEntityId() == attackerId) {
                sprite.rebuildDescriptions();
            }
        }

    }

    public void drawTacticalLayers(Graphics2D graphics2D, boolean includeUnits) {
        // Minefield signs all over the place!
        drawMinefields(graphics2D);

        // Demolition charges set by the local player
        drawDemolitionCharges(graphics2D);

        // Artillery targets
        drawArtilleryHexes(graphics2D);
        drawOrbitalBombardmentHexes(graphics2D);

        // The hover and selection cursors belong to the interactive board, not to a board image.
        if (includeUnits) {
            // draw highlight border
            drawSprite(graphics2D, highlightSprite);
        }

        // draw entity hex highlights (Nova CEWS network dialog)
        drawEntityHexHighlights(graphics2D);

        // draw demolition charge selection highlights (Detonate Charges dialog)
        drawDemolitionChargeHighlights(graphics2D);

        // draw cursors
        if (includeUnits) {
            drawSprite(graphics2D, cursorSprite);
            drawSprite(graphics2D, selectedSprite);
        }
        drawSprite(graphics2D, firstLOSSprite);
        drawSprite(graphics2D, secondLOSSprite);

        // draw deployment indicators.
        if (showsAllDeployment()) {
            drawAllDeployment(graphics2D);
        }

        // A board image does not run drawHexes, which is where the deploying entity's legal deployment borders are
        // painted for the interactive board, so its tactical layer carries them instead.
        if (!includeUnits && (en_Deployer != null)) {
            drawDeploymentBorders(graphics2D);
        }

        // draw C3 links, unless View > Unit Links hides the lines between units
        if (GUIP.getShowUnitLinks()) {
            drawSprites(graphics2D, c3Sprites);
        }

        // draw flyover routes
        if (game.getBoard(boardId).isGround()) {
            drawSprites(graphics2D, vtolAttackSprites);
            drawSprites(graphics2D, flyOverSprites);
        }

        // draw moving onscreen entities; a board image leaves unit artwork out
        if (includeUnits) {
            if (drawMovingUnits != null) { drawMovingUnits.accept(graphics2D); }
        }

        // draw onscreen attacks
        drawSprites(graphics2D, attackSprites);

        // draw artillery drift lines (from the targeted hex to where the round actually landed)
        drawArtilleryDriftLines(graphics2D);

        // draw movement vectors.
        if (showsMovementVectors()) {
            drawSprites(graphics2D, movementSprites);
        }

        if (game.getPhase().isFiring()) {
            for (Coords c : strafingCoords) {
                drawHexBorder(graphics2D, getHexLocation(c), Color.yellow, 0, 3);
            }
        }

        // In iso mode, some sprites are drawn in drawHexes so they can go behind terrain; draw only the others here
        drawSprites(graphics2D, includeUnits ? overTerrainSprites : overTerrainSprites.stream()
              .filter(sprite -> !sprite.isUnitVisual()).toList());

        // draw movement, if valid
        drawSprites(graphics2D, pathSprites);

        // draw flight path indicators
        drawSprites(graphics2D, fpiSprites);

        // draw the ruler line
        if (ruler != null) {
            Point start = getCentreHexLocation(ruler.start());
            if (ruler.end() != null) {
                Point end = getCentreHexLocation(ruler.end());
                graphics2D.setColor(Color.yellow);
                graphics2D.drawLine(start.x, start.y, end.x, end.y);

                drawRulerCrosshair(graphics2D, ruler.end(), new Color(ruler.endArgb(), true));
            }

            drawRulerCrosshair(graphics2D, ruler.start(), new Color(ruler.startArgb(), true));
        }

    }

    public void updateEcmList() {
        Map<Coords, Color> newECMHexes = new HashMap<>();
        Map<Coords, Color> newECMCenters = new HashMap<>();
        Map<Coords, Color> newECCMHexes = new HashMap<>();
        Map<Coords, Color> newECCMCenters = new HashMap<>();

        ecmEntities.clear();
        // Compute info about all E(C)CM on the board
        final ArrayList<ECMInfo> allEcmInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        // The 3D board's snapshot: the shown emitting units and each displayed field with its covered hexes
        List<Entity> emitters = new ArrayList<>();
        List<Map.Entry<ECMInfo, List<Coords>>> fields = new ArrayList<>();

        // First, mark the sources of E(C)CM Used for highlighting hexes and tooltips
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.getPosition() == null || !isOnThisBord(entity)) {
                continue;
            }

            Player localPlayer = getLocalPlayer();
            boolean entityIsEnemy = entity.getOwner().isEnemyOf(localPlayer);

            // If this unit isn't spotted somehow, it's ECM doesn't show up
            if ((localPlayer != null)
                  && game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)
                  && entityIsEnemy
                  && !entity.hasSeenEntity(localPlayer)
                  && !entity.hasDetectedEntity(localPlayer)) {
                continue;
            }

            // hidden enemy entities don't show their ECM bubble
            if (entityIsEnemy && entity.isHidden()) {
                continue;
            }

            final Color ecmColor = ECMEffects.getECMColor(entity.getOwner());
            // Update ECM center information
            if (entity.getECMInfo() != null) {
                newECMCenters.put(entity.getPosition(), ecmColor);
            }
            // Update ECCM center information
            if (entity.getECCMInfo() != null) {
                newECCMCenters.put(entity.getPosition(), ecmColor);
            }
            if (entity.getECMInfo() != null || entity.getECCMInfo() != null) {
                emitters.add(entity);
            }
            Coords position = entity.getPosition();
            if (ComputeECM.isAffectedByECM(entity, position, position, allEcmInfo)) { ecmEntities.add(entity.getId()); }

        }

        // Keep track of allied ECM and enemy ECCM
        Map<Coords, ECMEffects> ecmAffectedCoords = new HashMap<>();
        // Keep track of allied ECCM and enemy ECM
        Map<Coords, ECMEffects> eccmAffectedCoords = new HashMap<>();
        for (ECMInfo ecmInfo : allEcmInfo) {
            // Check if ECM source is on this board
            // Entity-based ECM: check if entity is on this board
            // Entity-less ECM (e.g., EMP mines): check if position is valid on this board
            if (ecmInfo.getEntity() != null) {
                if (!isOnThisBord(ecmInfo.getEntity())) {
                    continue;
                }
            } else {
                // Entity-less ECM field (from EMP mines, etc.) - check position is on board
                if (ecmInfo.getPos() == null || !game.getBoard(boardId).contains(ecmInfo.getPos())) {
                    continue;
                }
            }

            // Can't see ECM field of unspotted unit
            Player localPlayer = getLocalPlayer();
            if ((ecmInfo.getEntity() != null) && (localPlayer != null) && game.getOptions()
                  .booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND) && ecmInfo.getEntity()
                  .getOwner()
                  .isEnemyOf(localPlayer) && !ecmInfo.getEntity().hasSeenEntity(localPlayer) && !ecmInfo.getEntity()
                  .hasDetectedEntity(localPlayer)) {
                continue;
            }

            // hidden enemy entities don't show their ECM bubble
            if (ecmInfo.getEntity() != null
                  && ecmInfo.getEntity().getOwner().isEnemyOf(localPlayer)
                  && ecmInfo.getEntity().isHidden()) {
                continue;
            }

            final Coords ecmPos = ecmInfo.getPos();
            final int range = ecmInfo.getRange();
            List<Coords> covered = new ArrayList<>();
            fields.add(Map.entry(ecmInfo, covered));

            // Add each Coords within range to the list of ECM Coords
            for (int x = -range;
                  x <= range;
                  x++) {
                for (int y = -range;
                      y <= range;
                      y++) {
                    Coords coords = new Coords(x + ecmPos.getX(), y + ecmPos.getY());
                    int distance = ecmPos.distance(coords);
                    int direction = ecmInfo.getDirection();
                    // Direction is the facing of the owning Entity
                    boolean inArc = (direction == -1) || ComputeArc.isInArc(ecmPos,
                          direction,
                          coords,
                          Compute.ARC_NOSE);
                    if ((distance > range) || !inArc) {
                        continue;
                    }
                    covered.add(coords);

                    // Check for allied ECCM or enemy ECM
                    if ((!ecmInfo.isOpposed(localPlayer) && ecmInfo.isECCM()) || (ecmInfo.isOpposed(localPlayer)
                          && ecmInfo.isECCM())) {
                        ECMEffects ecmEffects = eccmAffectedCoords.computeIfAbsent(coords, k -> new ECMEffects());
                        ecmEffects.addECM(ecmInfo);
                    } else {
                        ECMEffects ecmEffects = ecmAffectedCoords.computeIfAbsent(coords, k -> new ECMEffects());
                        ecmEffects.addECM(ecmInfo);
                    }
                }
            }
        }

        // Finally, determine the color for each affected hex
        for (Coords coords : ecmAffectedCoords.keySet()) {
            ECMEffects ecm = ecmAffectedCoords.get(coords);
            ECMEffects eccm = eccmAffectedCoords.get(coords);
            processAffectedCoords(coords, ecm, eccm, newECMHexes, newECCMHexes);
        }

        for (Coords coords : eccmAffectedCoords.keySet()) {
            ECMEffects ecm = ecmAffectedCoords.get(coords);
            ECMEffects eccm = eccmAffectedCoords.get(coords);
            // Already processed all ECM affected coords

            if (ecm != null) {
                continue;
            }

            processAffectedCoords(coords, null, eccm, newECMHexes, newECCMHexes);
        }

        BoardElectronicWarfare nextWarfare = BoardElectronicWarfare.capture(getBoard(), getLocalPlayer(), fields,
              emitters, GUIP.getECMTransparency());
        boolean legacyChanged = !newECMHexes.equals(ecmHexes) || !newECCMHexes.equals(eccmHexes)
              || !newECMCenters.equals(ecmCenters) || !newECCMCenters.equals(eccmCenters);
        boolean warfareChanged = !nextWarfare.equals(electronicWarfare);
        synchronized (this) {
            ecmHexes = newECMHexes;
            ecmCenters = newECMCenters;
            eccmHexes = newECCMHexes;
            eccmCenters = newECCMCenters;
            if (warfareChanged) {
                electronicWarfare = nextWarfare;
            }
        }
        if (legacyChanged || warfareChanged) {
            visibilityChanged();
        }
        repaint();
    }

    public boolean isAffectedByECM(Entity entity) { return ecmEntities.contains(entity.getId()); }

    /** The E(C)CM fields the local player may see, as the 3D board draws them; Swing thread. */
    public BoardElectronicWarfare electronicWarfare() { return electronicWarfare; }

    /**
     * A high-altitude map's band on a hex: the stars faded and the atmosphere's tint over it (both null where neither
     * shows), and whether it is the space-atmosphere interface or the ground row.
     */
    public record AltitudeBand(@Nullable Color stars, @Nullable Color atmosphere, boolean spaceInterface,
          boolean ground) {
        /** The interface's tint over the half of the hex below it, and its dashed line. */
        public static final Color INTERFACE_TINT = new Color(0, 250, 250, 15);
        public static final Color INTERFACE_LINE = new Color(130, 130, 130, 100);
        /** The ground row's strip of ground, and its edge. */
        public static final Color GROUND = new Color(40, 80, 40);
        public static final Color GROUND_EDGE = new Color(40, 140, 40);
    }

    /** {@code coords}' band on a high-altitude map, or null off one or outside every band. */
    public @Nullable AltitudeBand altitudeBand(Coords coords) {
        Board board = getBoard();
        if (!board.isHighAltitude()) {
            return null;
        }
        boolean ground = BoardHelper.isGroundRowHex(board, coords);
        boolean spaceInterface = BoardHelper.isSpaceAtmosphereInterface(game, board, coords);
        // The atmosphere of a high-altitude map (unless planetary conditions say it is vacuum), and over the ground row
        // the lowest row's
        int atmosphericRow = BoardHelper.isAtmosphericRow(game, board, coords)
              ? BoardHelper.effectiveAtmosphericRowNumber(game, board, coords)
              : ground && !game.getPlanetaryConditions().getAtmosphere().isVacuum()
              ? BoardHelper.effectiveAtmosphericRowNumber(game, board, 1) - 1 : -1;
        if (atmosphericRow < 0 && !ground && !spaceInterface) {
            return null;
        }
        Color stars = null, atmosphere = null;
        if (atmosphericRow >= 0) {
            int spaceInterfacePosition = BoardHelper.spaceAtmosphereInterfacePosition(game);
            // First, fade out the stars, then add the atmosphere
            stars = new Color(0, 0, 0, 250 - atmosphericRow * (120 / (spaceInterfacePosition - 1)));
            atmosphere = new Color(0, 250, 250, 190 - atmosphericRow * (160 / (spaceInterfacePosition - 1)));
        }
        return new AltitudeBand(stars, atmosphere, spaceInterface, ground);
    }

    public void drawHexEffects(Graphics2D graphics2D, Coords coords) {
        AffineTransform scaleTransform = new AffineTransform();
        scaleTransform.scale(getScale(), getScale());

        AltitudeBand band = altitudeBand(coords);
        if ((band != null) && (band.stars() != null)) {
            graphics2D.setColor(band.stars());
            graphics2D.fill(scaleTransform.createTransformedShape(HEX_POLY));
            graphics2D.setColor(band.atmosphere());
            graphics2D.fill(scaleTransform.createTransformedShape(HEX_POLY));
        }

        // Draw in the space/atmosphere interface in a high-altitude map
        if ((band != null) && band.spaceInterface()) {
            Polygon halfHex = new Polygon();
            halfHex.addPoint(21, 0);
            halfHex.addPoint(42, 0);
            halfHex.addPoint(42, 71);
            halfHex.addPoint(21, 71);
            halfHex.addPoint(0, 36);
            halfHex.addPoint(0, 35);
            graphics2D.setColor(AltitudeBand.INTERFACE_TINT);
            graphics2D.fill(scaleTransform.createTransformedShape(halfHex));
            Polygon line = new Polygon();
            line.addPoint(42, 0);
            line.addPoint(42, 71);
            graphics2D.setColor(AltitudeBand.INTERFACE_LINE);
            BasicStroke bs1 = new BasicStroke(2,
                  BasicStroke.CAP_BUTT,
                  BasicStroke.JOIN_ROUND,
                  1.0f,
                  new float[] { 3f, 5f },
                  0f);
            graphics2D.setStroke(bs1);
            AffineTransform oldTransform = graphics2D.getTransform();
            graphics2D.transform(scaleTransform);
            graphics2D.draw(line);
            graphics2D.setTransform(oldTransform);
        }

        // Draw in ground in a high-altitude map
        if ((band != null) && band.ground()) {
            Polygon leftTriangle = new Polygon();
            leftTriangle.addPoint(21, 0);
            leftTriangle.addPoint(21, 71);
            leftTriangle.addPoint(0, 36);
            leftTriangle.addPoint(0, 35);
            graphics2D.setColor(AltitudeBand.GROUND);
            graphics2D.fill(scaleTransform.createTransformedShape(leftTriangle));
            graphics2D.setColor(AltitudeBand.GROUND_EDGE);
            graphics2D.draw(scaleTransform.createTransformedShape(HexDrawUtilities.getHexCrossLine01(4, 2)));
        }

        if (getBoard().embeddedBoardCoords().contains(coords)) {
            drawEmbeddedBoard(graphics2D);
        }
        drawElectronicWarfare(graphics2D, coords);

    }

    public void drawSpecialHexes(Graphics2D graphics2D, Coords coords) {
        // Set the text color according to Preferences or Light Gray in space
        graphics2D.setColor(GUIP.getBoardTextColor());
        if (game.getBoard(boardId).isSpace()) {
            graphics2D.setColor(GUIP.getBoardSpaceTextColor());
        }

        // draw special stuff for the hex
        final Collection<SpecialHexDisplay> shdList = game.getBoard(boardId).getSpecialHexDisplay(coords);
        try {
            if (shdList != null) {
                // Several heat-map markers can stack on one hex (multiple tubes firing it, or a prediction plus a
                // shot). Draw each marker's icon/fill, but collect them so a single combined turn label is drawn (their
                // distinct values merged), rather than each marker drawing its label over the others.
                List<SpecialHexDisplay> heatMapMarkers = new ArrayList<>();
                for (SpecialHexDisplay shd : shdList) {
                    if (shd.drawNow(game.getPhase(), game.getRoundCount(), getLocalPlayer(), GUIP)) {
                        // A predicted-position heat-map marker paints the hex with a cold-to-hot color (navy = one
                        // enemy converging, crimson = many) instead of an icon; the firing marker and every other
                        // display draw their icon.
                        if (isPredictedHeatMapMarker(shd)) {
                            drawHeatMapPredictedHex(shd, graphics2D, getScale());
                        } else {
                            Image scaledImage = getScaledImage(shd.getDefaultImage(), true);
                            graphics2D.drawImage(scaledImage, 0, 0, null);
                        }
                        if (isHeatMapMarker(shd)) {
                            heatMapMarkers.add(shd);
                        }
                    }
                }
                if (!heatMapMarkers.isEmpty()) {
                    drawHeatMapTurnLabel(heatMapMarkers, graphics2D, getScale());
                }
            }
        } catch (Exception e) {
            LOGGER.error(e, "Exception, probably can't load file.");
            drawCenteredString("Loading Error", 0, (int) (50 * getScale()), font_note, graphics2D);
            return;
        }

    }

    public void setShowAllDeployment(boolean show) { showAllDeployment = show; repaint(); }
    public Rectangle getDisplayablesRect() { return displayablesRect; }
    public void centerOnHex(Coords coords) { centerOn(coords, Entity.NONE); }
    public void centerOn(Entity entity) { if (entity != null) { centerOn(entity.getPosition(), entity.getId()); } }
    public void centerOnSelected() {
        Entity entity = getSelectedEntity();
        if (isOnThisBord(entity)) { if (clientgui != null) { clientgui.showBoardView(boardId); } centerOn(entity); }
    }
    public void highlightSelectedEntity(Entity entity) { highlightSelectedEntities(entity == null ? List.of() : List.of(entity)); }
    public void highlightSelectedEntities(List<Entity> entities) {
        selectedEntities.clear(); entities.forEach(entity -> selectedEntities.add(entity.getId())); repaint();
    }
    public boolean isEntitySelected(Entity entity) { return selectedEntities.contains(entity.getId()); }
    public void selectEntity(Entity entity) { visibilityChanged(); updateEcmList(); highlightSelectedEntity(entity); }
    public void clearArtwork() {
        fieldOfView.invalidate();
        repaint();
    }
    public void invalidateArtwork(Coords coords) {
        // A terrain blocker changes LOS beyond the artwork's local neighborhood, with or without a renderer.
        fieldOfView.invalidate();
    }
    private Image radarBlip;
    private Supplier<double[]> visibleArea = () -> new double[] { 0, 0, 1, 1 };

    /** Render-owned viewport, read by the minimap on the client thread. */
    public void setVisibleArea(Supplier<double[]> area) { visibleArea = area; }
    public double[] getVisibleArea() { return visibleArea.get(); }

    public Image getRadarBlipImage() {
        if (radarBlip == null) {
            radarBlip = ImageUtil.loadImageFromFile(new MegaMekFile(Configuration.miscImagesDir(), "radarBlip.png").toString());
        }
        return radarBlip;
    }

    public @Nullable Point getTerrainLightDirection() {
        Board board = getBoard();
        if (!GUIP.getShadowMap() || board == null || board.isSpace()
              || board.getBoardType() == megamek.common.board.BoardType.SKY || game.getPhase().isUnknown()) {
            return null;
        }
        var light = game.getPlanetaryConditions().getLight();
        return light.isMoonlessOrPitchBack() ? new Point(0, 0)
              : light.isDuskDawn() ? new Point(-38, 14) : new Point(-19, 7);
    }

    private boolean showInvalidFields;
    public boolean displayInvalidFields() { return showInvalidFields; }
    public void setDisplayInvalidFields(boolean show) { showInvalidFields = show; repaint(); }

    public String getHexTooltip(Coords coords) {
        if (tooltipSuspended || closed) { return ""; }
        return new megamek.client.ui.clientGUI.boardview.toolTip.TWBoardViewTooltip(game, clientgui, this)
              .getTooltip(coords, movementTarget);
    }

    public List<Entity> getWrecks() {
        return Collections.list(game.getWreckedEntities()).stream().filter(entity ->
              isOnThisBord(entity) && entity.getPosition() != null && getBoard().contains(entity.getPosition())
                    && (!(entity instanceof Infantry) || entity instanceof CombatVehicleEscapePod)).toList();
    }

    public void chatKey(java.awt.event.KeyEvent event) {
        for (IDisplayable overlay : overlays) {
            if (overlay instanceof ChatterBoxOverlay chat) { chat.keyPressed(event); }
        }
    }

    public void advanceOverlays(long elapsedMillis) {
        for (IDisplayable overlay : overlays) {
            if (overlay.isSliding()) { overlay.slide(); } else { overlay.setIdleTime(elapsedMillis, true); }
        }
    }

    public void reloadAssets() throws IOException { tileManager.reloadAssets(); repaint(); }
    public void redrawEntity(Entity entity) {
        // Remove C3 links
        c3Links.removeIf(link -> (link.entity().getId() == entity.getId()) || (link.master().getId() == entity.getId()));
        c3Sprites.removeIf(c3sprite -> (c3sprite.getEntityId() == entity.getId()) || (c3sprite.getMasterId()
              == entity.getId()));

        // Update C3 link, if necessary
        if (entity.hasC3() || entity.hasC3i() || entity.hasNovaCEWS() || entity.hasNavalC3()) {
            addC3Link(entity);
        }

        // The removal above also dropped the lines that this entity's hierarchic subordinates draw TO it (each
        // slave owns its own line to its master), and addC3Link(entity) only redraws the entity's own line to its
        // master. Re-add the subordinates' lines, otherwise selecting a master in the firing phase erases its
        // network on the board until the next full redraw.
        for (Entity subordinate : game.getEntitiesVector()) {
            if ((subordinate.getC3MasterId() == entity.getId())
                  && !subordinate.equals(entity)
                  && subordinate.hasC3()
                  && isOnThisBord(subordinate)) {
                addC3Link(subordinate);
            }
        }

        vtolAttacks.removeIf(attack -> attack.entity().getId() == entity.getId());
        vtolAttackSprites.removeIf(s -> s.getEntity().getId() == entity.getId());

        // Remove fly-over paths
        flyOvers.removeIf(flyOver -> flyOver.entity().getId() == entity.getId());
        flyOverSprites.removeIf(flyOverSprite -> flyOverSprite.getEntityId() == entity.getId());

        // Add Flyover path, if necessary
        if ((boardId == entity.getPassedThroughBoardId())
              && (entity.isAirborne() || entity.isMakingVTOLGroundAttack())
              && (entity.getPassedThrough().size() > 1)) {
            addFlyOverPath(entity);
        }

        updateEcmList();
        highlightSelectedEntity(getSelectedEntity());
        entityRenderer.accept(entity);
        repaint();
    }

    public void redrawAllEntities() {
        clearC3Networks();
        clearFlyOverPaths();
        for (Entity entity : game.getEntitiesVector()) {
            if (boardId == entity.getPassedThroughBoardId()
                  && (entity.isAirborne() || entity.isMakingVTOLGroundAttack())
                  && entity.getPassedThrough().size() > 1) {
                addFlyOverPath(entity);
            }
            if (entity.getPosition() != null && isOnThisBord(entity)
                  && EntityVisibilityUtils.detectedOrHasVisual(getLocalPlayer(), game, entity)
                  && (entity.hasC3() || entity.hasC3i() || entity.hasNovaCEWS() || entity.hasNavalC3())) {
                addC3Link(entity);
            }
        }
        updateEcmList();
        highlightSelectedEntity(getSelectedEntity());
        entityRenderer.accept(null);
        repaint();
    }

    public void updateEntityLabels() {
        game.getEntitiesVector().forEach(Entity::generateShortName);
        entityRenderer.accept(null);
        repaint();
    }
    public void clearMarkedHexes() { select(null); highlight(null); cursor(null); }
    public void clearSprites() {
        setPath(List.of(), List.of());
        setPlannedMovement(null);
        clearAllAttacks();
        clearC3Networks();
        clearFlyOverPaths();
        clearAllMoveVectors();

        published.clear();
        overTerrainSprites.clear();
        behindTerrainHexSprites.clear();

        allSprites.clear();
        repaint();
    }

    public boolean isClosed() { return closed; }
    void setEntityRenderer(Consumer<Entity> renderer) { entityRenderer = renderer; }
    void setInputEnabled(Supplier<Boolean> enabled) { inputEnabled = enabled; }
    public boolean isMovingUnits() { return movingUnits; }
    public boolean isShowingAnimation() { return movingUnits; }
    public void setMovingUnits(boolean moving) {
        if (movingUnits == moving) { return; }
        movingUnits = moving;
        if (!moving) { processBoardViewEvent(new BoardViewEvent(this, BoardViewEvent.FINISHED_MOVING_UNITS)); }
    }

    public boolean shouldReceiveKeyCommands() {
        return !closed && !chatterBoxActive && !shouldIgnoreKeys && !game.getPhase().isLounge()
              && inputEnabled.get() && (clientgui == null || !clientgui.shouldIgnoreHotKeys());
    }
    private void openChat(boolean command) {
        setChatterBoxActive(true);
        for (IDisplayable overlay : overlays) {
            if (overlay instanceof ChatterBoxOverlay chat) {
                chat.slideUp();
                if (command) { chat.setMessage("/"); }
            }
        }
    }

    private void onClientThread(Runnable action) {
        Runnable guarded = () -> { if (!closed) { action.run(); } };
        if (SwingUtilities.isEventDispatchThread()) { guarded.run(); } else { SwingUtilities.invokeLater(guarded); }
    }

    private final GameListener gameListener = new GameListenerAdapter() {
        @Override public void gameEntityNew(GameEntityNewEvent event) { onClientThread(BoardClientState.this::entitiesChanged); }
        @Override public void gameEntityRemove(GameEntityRemoveEvent event) { onClientThread(BoardClientState.this::entitiesChanged); }
        @Override public void gameEntityChange(GameEntityChangeEvent gameEntityChangeEvent) {
            onClientThread(() -> {
                Entity entity = gameEntityChangeEvent.getEntity();
                // For Entities that have converted to another mode, check for a different sprite
                if (game.getPhase().isMovement() && entity.isConvertingNow()) {
                    tileManager.reloadImage(entity);
                }

                // For units that have been blown up, damaged, ejected or handed to another player (a traitor
                // switch means the new owner's camouflage), force a reload. Without the old state we cannot tell
                // whether the damage changed, so reload to be safe; the reload reuses the cached image when
                // nothing shown in fact changed, so it costs nothing in the common case.
                final Entity oldEntity = gameEntityChangeEvent.getOldEntity();
                boolean shownStateChanged = true;
                if (oldEntity != null) {
                    boolean damageChanged = entity.getDamageLevel() != oldEntity.getDamageLevel();
                    boolean destructionChanged = entity.isDestroyed() != oldEntity.isDestroyed();
                    boolean ownerChanged = entity.getOwnerId() != oldEntity.getOwnerId();
                    boolean ejectionChanged = entity.getCrew().isEjected() != oldEntity.getCrew().isEjected();
                    shownStateChanged = damageChanged || destructionChanged || ownerChanged || ejectionChanged;
                }
                if (shownStateChanged) {
                    tileManager.reloadImage(entity);
                }


                entitiesChanged();
            });
        }
        @Override public void gameNewAction(GameNewActionEvent event) {
            onClientThread(() -> { if (event.getAction() instanceof AttackAction attack) { addAttack(attack); repaint(); } });
        }
        @Override public void gameBoardChanged(GameBoardChangeEvent event) {
            onClientThread(() -> { updateEcmList(); clearArtwork(); });
        }
        @Override public void gameBoardNew(GameBoardNewEvent event) {
            if (event.getBoardId() != boardId) { return; }
            onClientThread(() -> {
                if (observedBoard != null) { observedBoard.removeBoardListener(boardListener); }
                observedBoard = event.getNewBoard();
                if (observedBoard != null) { observedBoard.addBoardListener(boardListener); }
                clearArtwork();
                redrawAllEntities();
            });
        }
        @Override public void gamePhaseChange(GamePhaseChangeEvent gamePhaseChangeEvent) {
            onClientThread(() -> {
                saveGameSummary(gamePhaseChangeEvent.getOldPhase());
                refreshAttacks();

                // Clear some information regardless of what phase it is
                if (clientgui != null) {
                    clientgui.clearTemporarySprites();
                }

                switch (gamePhaseChangeEvent.getNewPhase()) {
                    case MOVEMENT -> refreshMoveVectors();
                    case FIRING -> clearAllMoveVectors();
                    case INITIATIVE -> clearAllAttacks();
                    case INITIATIVE_REPORT, MOVEMENT_REPORT, FIRING_REPORT, PHYSICAL_REPORT, END_REPORT ->
                          redrawAllEntities();
                    case END, VICTORY, LOUNGE -> {
                        clearArtwork();
                        clearSprites();
                        clearMarkedHexes();
                    }
                    default -> { }
                }
                for (Entity entity : game.getEntitiesVector()) {
                    if ((entity.getDamageLevel() != Entity.DMG_NONE) && ((entity.damageThisRound != 0)
                          || (entity.isBuildingEntityOrGunEmplacement()))) {
                        tileManager.reloadImage(entity);
                    }
                }

                repaint();
            });
        }
    };

    private void entitiesChanged() {
        redrawAllEntities();
        if (game.getPhase().isMovement()) { refreshMoveVectors(); }
    }

    private final megamek.common.event.board.BoardListenerAdapter boardListener = new megamek.common.event.board.BoardListenerAdapter() {
        @Override public void boardChangedHex(BoardEvent event) {
            onClientThread(() -> {
                if (event.getSource() != observedBoard) { return; }
                invalidateArtwork(event.getCoords());
                // The native source tracks the local edit; changing the tactical revision would repaint the whole view.
                changed.run();
            });
        }
        @Override public void boardChangedAllHexes(BoardEvent event) { onClientThread(BoardClientState.this::clearArtwork); }
        @Override public void boardNewBoard(BoardEvent event) { onClientThread(BoardClientState.this::clearArtwork); }
    };
    private final IPreferenceChangeListener preferenceListener = this::preferenceChanged;

    private void preferenceChanged(PreferenceChangeEvent event) {
        onClientThread(() -> {
            switch (event.getName()) {
                case GUIPreferences.SHOW_DEPLOY_ZONES_ARTY_AUTO -> showAllDeployment = (boolean) event.getNewValue();
                case ClientPreferences.MAP_TILESET -> clearArtwork();
                case GUIPreferences.BOARD_ECM_TRANSPARENCY -> updateEcmList();
                case GUIPreferences.USE_CAMO_OVERLAY -> tileManager.reloadUnitIcons();
                case GUIPreferences.INCLINES -> getBoard().initializeAllAutomaticTerrain();
                case GUIPreferences.UNIT_LABEL_STYLE -> {
                    if (clientgui != null) {
                        clientgui.systemMessage("Label style changed to " + GUIP.getUnitLabelStyle().description);
                    }
                    updateEntityLabels();
                }
                case GUIPreferences.UNIT_LABEL_BORDER, GUIPreferences.TEAM_COLORING,
                      GUIPreferences.SHOW_DAMAGE_DECAL, GUIPreferences.SHOW_DAMAGE_LEVEL -> updateEntityLabels();
            }
            repaint();
        });
    }

    private boolean tooltipSuspended;
    public boolean isTooltipSuspended() { return tooltipSuspended; }
    public void suspendTooltip() { tooltipSuspended = true; }
    public void activateTooltip() { tooltipSuspended = false; }

    private void saveGameSummary(GamePhase phase) {
        if (!GUIP.getGameSummaryBoardView() || !(phase.isDeployment() || phase.isMovement() || phase.isTargeting()
              || phase.isFiring() || phase.isPhysical())) {
            return;
        }
        File directory = new File(Configuration.gameSummaryImagesBVDir(), game.getUUIDString());
        if (directory.exists() || directory.mkdirs()) {
            String name = String.format("round_%03d_%03d_%s%s.png", game.getRoundCount(), phase.ordinal(), phase,
                  boardId == 0 ? "" : "_board_" + boardId);
            File image = new File(directory, name);
            try {
                ImageIO.write(getEntireBoardImage(false), "png", image);
            } catch (Exception exception) {
                LOGGER.error(exception, "Unable to write board image {}", image);
            }
        }
    }

    /** Explicit printable export; uses the same labels and tactical painters without constructing a viewport. */
    public BufferedImage getEntireBoardImage(boolean ignoreUnits) {
        BoardGlyphContext previousProjection = projection;
        projection = null;
        List<Sprite> glyphs = allSprites.stream()
              .filter(sprite -> !sprite.isUnitVisual()).toList();
        try {
            BufferedImage overlay = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            BufferedImage result = BoardArtwork.printable(getBoard(), tileManager, coords -> {
                Graphics2D g = overlay.createGraphics();
                try {
                    g.setComposite(AlphaComposite.Clear); g.fillRect(0, 0, HEX_W, HEX_H);
                    g.setComposite(AlphaComposite.SrcOver);
                    drawHexEffects(g, coords); drawSpecialHexes(g, coords); drawHexPlugins(g, coords);
                } finally { g.dispose(); }
                return overlay;
            });
            Graphics2D graphics = result.createGraphics();
            try {
                UIUtil.setHighQualityRendering(graphics);
                graphics.setClip(0, 0, result.getWidth(), result.getHeight());
                glyphs.forEach(Sprite::prepare);
                drawSprites(graphics, behindTerrainHexSprites.stream().filter(sprite -> !sprite.isUnitVisual()).toList());
                if (!ignoreUnits) {
                    if (GUIP.getShowWrecks()) {
                        for (Entity wreck : getWrecks()) {
                            if (EntityVisibilityUtils.detectedOrHasVisual(localPlayer, game, wreck)
                                  && !EntityVisibilityUtils.onlyDetectedBySensors(localPlayer, wreck)) {
                                drawPrintedUnit(graphics, wreck, true);
                            }
                        }
                    }
                    for (Entity entity : game.getEntitiesVector()) {
                        if (isOnThisBord(entity) && entity.getPosition() != null
                              && EntityVisibilityUtils.detectedOrHasVisual(localPlayer, game, entity)) {
                            drawPrintedUnit(graphics, entity, false);
                        }
                    }
                }
                drawTacticalLayers(graphics, false);
            } finally { graphics.dispose(); }
            return result;
        } finally {
            projection = previousProjection;
            if (projection != null && getScale() != 1) { glyphs.forEach(Sprite::prepare); }
        }
    }

    private void drawPrintedUnit(Graphics2D graphics, Entity entity, boolean wreck) {
        Map<Integer, Coords> positions = entity.getSecondaryPositions().isEmpty()
              ? Map.of(-1, entity.getPosition()) : entity.getSecondaryPositions();
        boolean sensor = EntityVisibilityUtils.onlyDetectedBySensors(localPlayer, entity);
        for (var entry : positions.entrySet()) {
            Point point = getHexLocation(entry.getValue());
            Image image = sensor ? getRadarBlipImage() : wreck ? tileManager.wreckMarkerFor(entity, entry.getKey())
                  : tileManager.imageFor(entity, entry.getKey());
            graphics.drawImage(image, point.x, point.y, null);
            if (!wreck) {
                UnitAnnotations annotations = new UnitAnnotations(this, entity, entry.getKey());
                annotations.prepare();
                Rectangle bounds = annotations.getBounds();
                graphics.drawImage(annotations.image(), bounds.x, bounds.y, null);
            }
        }
    }

}
