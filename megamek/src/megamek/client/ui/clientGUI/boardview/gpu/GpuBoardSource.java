/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import jakarta.annotation.Nullable;
import megamek.client.event.BoardViewEvent;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.UnitOwnerColors;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.clientGUI.tooltip.HexTooltip;
import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.tileset.UnitModelEquipment;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
import megamek.common.TagInfo;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.AttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.BridgeConstruction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Flare;
import megamek.common.event.GameAttackResolvedEvent;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.GameTurn;
import megamek.common.moves.MovePath;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Aero;
import megamek.common.units.BulldozerRules;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.units.UnitLocation;
import megamek.logging.MMLogger;

/** Thin Swing adapter. Reuses MegaMek's tileset, visibility checks, movement path, and actual phase buttons. */
final class GpuBoardSource implements BoardSource {
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardSource.class);
    /** The designator lines last shown, so they log only when their number changes. */
    private int lastDesignations;
    private final java.util.LinkedHashSet<java.util.UUID> receivedAttacks = new java.util.LinkedHashSet<>();
    private final Map<Integer, BoardScene.Combat> removalAttempts = new HashMap<>();
    private final Set<Integer> voluntaryReleases = new java.util.HashSet<>();

    private volatile BoardClientState view;
    private final Supplier<JComponent> phasePanel;
    private GpuBoardActions actions;
    volatile UiPreferences uiPreferences;
    private final Map<Image, BoardScene.Pixels> unitImages = new IdentityHashMap<>();
    /**
     * EDT: the board's tactical markings, without what the HUD draws itself (G4): the firing solutions are the board
     * labels' to-hit badges, and the path's step arrows and costs are left out while the plan draws the route.
     * MegaMek's movement envelope stays.
     */
    private final BoardTacticalMarks tacticalMarks = new BoardTacticalMarks();
    /** The work under way as ghosts, and the marks and tiles they were built from (EDT); see {@link #workGhosts}. */
    private volatile GpuEditorPreview.Data workGhosts = GpuEditorPreview.Data.NONE;
    private List<TacticalMark> workMarks = List.of();
    private List<BoardScene.Tile> workTiles = List.of();
    private Map<Coords, Integer> workDigging = Map.of();
    /** The scene's tiles, {@link #tiles} with what marks set on the ground, and what they were made from (EDT). */
    private List<BoardScene.Tile> markedTiles = List.of(), markedFrom = List.of();
    private List<TacticalMark> groundMarks = List.of();
    /**
     * EDT: the fighting holes of each hex its platoons are dug in: the formation slots their figures stood in when they
     * dug in ({@link InfantryVisual#slots}), kept while they stay dug in, so a casualty leaves its hole empty.
     */
    private Map<Coords, Integer> holes = Map.of();
    private final UnitCamouflage camouflage = new UnitCamouflage();
    private final GpuReportLog reports = new GpuReportLog();
    private final GpuBattleStatus battleStatus = new GpuBattleStatus();
    // The HUD services. Each reads currentView() per call, so a board swap needs no rebinding.
    private final GpuMovePlan moves = new GpuMovePlan(this);
    private final GpuFireOrders fire = new GpuFireOrders(this);
    private final GpuPhysicalOptions physical = new GpuPhysicalOptions(this);
    private final GpuUnitRecord unitRecord = new GpuUnitRecord(this);
    /** Independent capture cache for the own card while another unit's record is inspected. */
    private final GpuUnitRecord ownUnitRecord = new GpuUnitRecord(this);
    private final GpuFirePreview preview;
    private final GpuChat chat;
    private final GpuToasts toasts = new GpuToasts(this);
    private final GpuLosResult los = new GpuLosResult(this);
    private final GpuPlayers players = new GpuPlayers(this);
    /** EDT: the last published panel bundle, kept while every part is equal. */
    private GpuHudData panels = GpuHudData.EMPTY;
    /** Set by the GL thread; each capture uses them only while the unit is still identified or owned. */
    private volatile int cardUnit = Entity.NONE;
    private volatile int ownCardUnit = Entity.NONE;
    private volatile int focusUnit = Entity.NONE;
    private final GpuAtmosphereControls atmosphere;
    private final BoardScene.PixelPool terrainImages = new BoardScene.PixelPool();
    /** EDT-owned: the board's terrain tiles, the one capture the preview and the editor share; the markings are ours. */
    private final BoardTiles terrain = new BoardTiles(terrainImages);
    private final List<BoardScene.Animation> pendingEvents = new ArrayList<>();
    /**
     * Deaths received since the last checkpoint. The server reports a death as it happens, before the movement or fall
     * it happened in: the next checkpoint queues them after that, where it left the unit.
     */
    private final List<BoardScene.Combat> deaths = new ArrayList<>();
    /**
     * EDT: the hexes whose structure the last attack shown brought down, with its hit, which their collapse falls away
     * from when it follows that attack's animation (recordStructureHit, BoardScene.TileTransition.Hit). The next attack
     * ends it, and so does the first capture published in a later phase than {@link #structureHitsPhase} of round
     * {@link #structureHitsRound}: the rules bring a structure down as the phase ends.
     */
    private final Map<Coords, BoardScene.TileTransition.Hit> structureHits = new HashMap<>();
    private int structureHitsRound = -1;
    private GamePhase structureHitsPhase;
    /**
     * The attack being resolved, for the trees its checkpoint shows gone (publishScene): the hexes whose woods the server
     * names it thinned or cleared (ResolvedAttack#cleared) and the hit they break away from; none outside its phase.
     */
    private Set<Coords> treeHexes = Set.of();
    private BoardScene.TileTransition.Hit treeHit;
    private int treeHitRound = -1;
    private GamePhase treeHitPhase;
    /**
     * When the capture that first showed a structure brought down in the movement phase was held back, 0 for none: the
     * server sends that collapse, and its mover's fall, before the path that walked onto it (captureMovement).
     */
    private long collapseHeldSince;
    private final Timer timer;
    private final GameListenerAdapter gameListener;
    private final BoardListenerAdapter boardListener;
    private final IPreferenceChangeListener preferenceListener = event -> {
        if (ClientPreferences.MAP_TILESET.equals(event.getName())) {
            dirtyTerrain();
        } else {
            refreshPreferences();
        }
    };
    private Board board;
    private List<BoardScene.Tile> tiles = List.of();
    private BoardFieldOfView fieldOfView = BoardFieldOfView.EMPTY;
    private volatile boolean closed;
    /** EDT-confined stack of native modal dialogs; only the newest is shown, so they are answered LIFO. */
    private final Deque<PendingDialog> pendingDialogs = new ArrayDeque<>();
    /** Its newest unanswered dialog for the GL thread, read without any monitor and never part of a {@link Frame}. */
    private volatile GpuBoardWindow.DialogRequest dialog;
    private long dialogSequence;
    private Frame frame;
    /** Packet-bounded presentation capture only; the game continues to apply its authoritative strike updates. */
    /** A detonation being captured; {@code cause}, the explosion it is nested in, that set it off. */
    private record NuclearCapture(megamek.common.NuclearDetonation strike, Frame before,
          List<BoardScene.Animation> consequences, megamek.common.NuclearDetonation cause) { }
    /**
     * The detonations being captured, innermost first: an explosion inside another blast (a fuel tank a strike set off)
     * plays after the blast that holds it.
     */
    private final java.util.ArrayDeque<NuclearCapture> captures = new java.util.ArrayDeque<>();
    /** EDT-owned menu request; the selection flag scopes this capture, not the game's acting unit. */
    private Coords contextCoords;
    private int contextUnitId = Entity.NONE;
    private boolean contextSelectionCleared;
    /** One pointer sample from the GL thread, captured together on the EDT. */
    private record HoverPoint(Coords coords, float pointedZ) { }
    private volatile HoverPoint hoverPoint = new HoverPoint(null, Float.NaN);
    private long boardGeneration;
    private volatile Rectangle visibleArea = new Rectangle(0, 0, 16, 16);
    /** The game's units the last capture did not show on this board (reserveUnits). */
    private volatile List<BoardScene.Unit> reserve = List.of();
    private Rectangle capturedArea;
    private long capturedRevision = -1;
    private GamePhase capturedPhase;

    public void setVisibleArea(Rectangle area) {
        visibleArea = new Rectangle(area);
    }

    public void setHover(Coords coords) {
        setHover(coords, Float.NaN);
    }

    @Override
    public void setHover(Coords coords, float pointedZ) {
        hoverPoint = new HoverPoint(coords, pointedZ);
    }

    BoardClientState currentView() {
        return view;
    }

    public GpuBoardSource(BoardClientState view, Supplier<JComponent> phasePanel) {
        requireSwingThread();
        this.view = view;
        this.phasePanel = phasePanel;
        atmosphere = new GpuAtmosphereControls(() -> this.view.game.getBoard(this.view.getBoardId()), () -> this.view.game.getPlanetaryConditions(),
              () -> !acceptsInput());
        GUIPreferences preferences = GUIPreferences.getInstance();
        uiPreferences = UiPreferences.capture();
        actions = new GpuBoardActions(view, phasePanel, () -> !acceptsInput() || this.view != view, this::refresh);
        boardListener = new BoardListenerAdapter() {
            @Override
            public void boardNewBoard(BoardEvent event) {
                boardChangedAllHexes(event);
            }

            @Override
            public void boardChangedHex(BoardEvent event) {
                onSwing(() -> {
                    if (closed || event.getSource() != board) { return; }
                    // Changed hexes also change what the fire preview predicts.
                    preview.invalidate();
                    terrain.changed(event.getCoords());
                });
            }

            @Override
            public void boardChangedAllHexes(BoardEvent event) {
                onSwing(() -> {
                    if (!closed && event.getSource() == board) {
                        terrain.changed(null);
                        preview.invalidate();
                    }
                });
            }
        };
        gameListener = new GameListenerAdapter() {
            @Override
            public void gameTurnChange(megamek.common.event.GameTurnChangeEvent event) {
                // The server ends a turn after its move's last update: a collapse that move brought down shows now,
                // whatever update never came to the client, as one of a mover it cannot see (holdCollapse).
                onSwing(() -> {
                    if (!closed && collapseHeldSince != 0) {
                        releaseCollapse();
                        refresh();
                    }
                });
            }

            @Override
            public void gameReport(megamek.common.event.GameReportEvent event) {
                onSwing(() -> {
                    if (!closed) {
                        captureReports();
                        reports.live(event.getReport(), view.game.getRoundCount(), view.game.getPhase());
                        refresh();
                    }
                });
            }

            @Override
            public void gameAttackResolved(megamek.common.event.GameAttackResolvedEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                onSwing(() -> {
                    if (isCurrentView(eventView)) {
                        captureCombat(event);
                    }
                });
            }

            @Override
            public void gameNuclearDetonation(megamek.common.event.GameNuclearDetonationEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                onSwing(() -> {
                    var result = event.detonation();
                    if (!isCurrentView(eventView) || result.boardId() != view.getBoardId()) { return; }
                    if (!event.completed() && !receivedAttacks.add(result.id())) { return; }
                    Frame next = capture();
                    synchronized (GpuBoardSource.this) {
                        if (event.completed()) {
                            if (captures.isEmpty() || !captures.peek().strike().id().equals(result.id())) { return; }
                            var capture = captures.peek();
                            var before = capture.before();
                            publishScene(next, true);
                            var consequences = capture.consequences();
                            captures.pop();
                            // A conventional explosion names all it brings down; the hits of what set it off stay.
                            if (result.kind() != megamek.common.NuclearDetonation.Kind.EXPLOSION) { recordBlast(result, before.scene()); }
                            var after = before.boardGeneration() == next.boardGeneration() ? next.scene() : null;
                            var falls = strikeCollapses(result, before.scene(), after);
                            int reach = result.degradation() < 1 ? -1 : result.baseDamage() / result.degradation();
                            var felled = after == null ? List.<BoardScene.Detonation.Felled>of()
                                  : felled(before.scene(), after, at -> result.coords().distance(at) <= reach);
                            Set<Integer> concealed = consequences.stream().filter(BoardScene.Concealed.class::isInstance)
                                  .map(BoardScene.Animation::entityId).collect(Collectors.toSet());
                            consequences.stream().filter(BoardScene.Concealed.class::isInstance)
                                  .forEach(GpuBoardSource.this::queueAnimation);
                            queueAnimation(new BoardScene.Detonation(result, falls, felled, capture.cause()));
                            queueAnimation(new BoardScene.SceneUpdate(next.scene()));
                            // Its checkpoint is the newest: those its consequences took (an explosion it set off) are older.
                            consequences.stream().filter(action -> !(action instanceof BoardScene.Concealed)
                                  && !(action instanceof BoardScene.SceneUpdate)
                                  && !concealed.contains(action.entityId())
                                  && !(action instanceof BoardScene.Combat combat && combat.target() != null
                                        && concealed.contains(combat.target().id()))
                                  && !(action instanceof BoardScene.AttachmentChange change
                                        && concealed.contains(change.carrier().id())))
                                  .forEach(GpuBoardSource.this::queueAnimation);
                        } else {
                            publishScene(next, true);
                            captures.push(new NuclearCapture(result, next, new ArrayList<>(),
                                  captures.isEmpty() ? null : captures.peek().strike()));
                            if (receivedAttacks.size() > 2048) { receivedAttacks.removeFirst(); }
                        }
                    }
                });
            }

            @Override
            public void gameEntityChange(GameEntityChangeEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                // Snapshot the packet's path before leaving its callback; playback owns the immutable copy.
                List<UnitLocation> path = event.getMovePath() == null ? List.of() : List.copyOf(event.getMovePath());
                int entityId = event.getEntity().getId();
                EntityMovementType type = event.getEntity().moved;
                int moveMP = movementMP(event.getEntity(), type);
                Entity old = event.getOldEntity();
                UnitLocation start = old == null || old.getPosition() == null ? null
                      : new UnitLocation(old.getId(), old.getPosition(), old.getFacing(), old.getElevation(),
                            old.getBoardId(), proneCause(old), UnitLocation.Form.capture(old), old.getFallSide(), old.isHullDown());
                int startAltitude = old == null ? 0 : old.getAltitude();
                Entity takeoff = old == null ? event.getEntity() : old;
                int jumpMP = type == EntityMovementType.MOVE_JUMP ? takeoff.getAnyTypeMaxJumpMP() : 0;
                Coords at = event.getEntity().getPosition();
                int elevation = event.getEntity().getElevation();
                onSwing(() -> {
                    if (isCurrentView(eventView)) {
                        if (path.isEmpty()) {
                            // The last update of the move that brought a held collapse down ends its wait at once.
                            if (endsHeldMove(entityId, start, at, elevation)) { releaseCollapse(); }
                            refresh();
                        } else {
                            captureMovement(entityId, start, path, type, jumpMP, startAltitude, moveMP);
                        }
                    }
                });
            }

            @Override
            public void gameEntityNew(megamek.common.event.entity.GameEntityNewEvent event) {
                onSwing(GpuBoardSource.this::refresh);
            }

            @Override
            public void gameEntityRemove(megamek.common.event.entity.GameEntityRemoveEvent event) {
                BoardClientState eventView = GpuBoardSource.this.view;
                int condition = event.getEntity().getRemovalCondition();
                int id = event.getEntity().getId(), boardId = event.getEntity().getBoardId();
                onSwing(() -> {
                    if (!isCurrentView(eventView)) { return; }
                    if (condition == megamek.common.interfaces.IEntityRemovalConditions.REMOVE_UNKNOWN) {
                        queueAnimation(new BoardScene.Concealed(id, boardId));
                    }
                    refresh();
                });
            }
        };
        // Everything that registers a listener comes last, next to the failure cleanup below.
        preview = new GpuFirePreview(view.game, this::refresh, this::acceptsInput, GpuFirePreview.SLICE_NANOS);
        chat = new GpuChat(this);
        view.game.addGameListener(gameListener);
        PreferenceManager.getClientPreferences().addPreferenceChangeListener(preferenceListener);
        preferences.addPreferenceChangeListener(preferenceListener);
        timer = new Timer(100, event -> {
            this.view.advanceOverlays(100);
            refresh();
        });
        timer.setCoalesce(true);
        try {
            refresh();
            timer.start();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static void requireSwingThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("GPU board game access must run on the Swing event thread");
        }
    }

    /** Packet handlers already run on Swing. Capturing here preserves each event inside a batched packet. */
    private static void onSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void dirtyTerrain() {
        onSwing(() -> {
            if (!closed) {
                terrain.changed(null);
                // Changed hexes also change what the fire preview predicts.
                preview.invalidate();
            }
        });
    }

    /** Replaces the published preferences only when they changed, so the render thread can compare by identity. */
    private void refreshPreferences() {
        UiPreferences next = UiPreferences.capture();
        if (!next.equals(uiPreferences)) {
            uiPreferences = next;
        }
    }

    /** Selection is authoritative immediately, even before the next source timer publishes the new board. */
    private boolean isCurrentView(BoardClientState expected) {
        return !closed && !expected.isClosed() && view == expected && board != null && board == expected.getBoard()
              && (expected.getClientgui() == null
                    || expected.getClientgui().getCurrentBoardState().orElse(null) == expected);
    }

    /**
     * How a unit lies: its own prone cause, or voluntarily prone for infantry hitting the deck while that is its cover.
     * Dug in, or in a fortified hex, it has the +2 of that cover instead, never both (TO:AR p.106): it mans its holes or
     * trench.
     */
    private megamek.common.units.ProneCause proneCause(Entity entity) {
        var hex = view.game.getHexOf(entity);
        return entity instanceof megamek.common.units.Infantry infantry && infantry.isHitTheDeck()
              && infantry.getDugIn() != megamek.common.units.Infantry.DUG_IN_COMPLETE
              && (hex == null || !hex.containsTerrain(Terrains.FORTIFIED))
              && entity.getProneCause() == megamek.common.units.ProneCause.NONE
              ? megamek.common.units.ProneCause.VOLUNTARY : entity.getProneCause();
    }

    private void captureMovement(int entityId, UnitLocation start, List<UnitLocation> path, EntityMovementType type,
            int jumpMP, int startAltitude, int movementMP) {
        if (closed || path.isEmpty()) {
            return;
        }
        Entity entity = view.game.getEntity(entityId);
        BoardClientState movingView = view;
        if (entity == null || !visible(entity) || sensorContact(entity)
              || path.stream().anyMatch(p -> p.boardId() != view.getBoardId() || p.coords() == null)) {
            // The move's last update, with no walk to show: what it brought down shows now.
            releaseCollapse();
            refresh();
            return;
        }
        reports.moved(entity, start, path, type, view.game.getRoundCount());
        // A collapse it caused was held back (holdCollapse): the game's unit already lies where it fell, but its walk
        // starts where the last published scene, from before the collapse, shows it.
        if (collapseHeldSince != 0) { start = null; }
        List<BoardScene.Waypoint> points = new ArrayList<>();
        // An airborne visual plays at the altitude the unit has now, and at the altitude it started the move at for
        // its first point and when it has just landed, so flying stays on the board through climbs and landings.
        int flightAltitude = entity.getAltitude() > 0 ? entity.getAltitude() : startAltitude;
        if (start != null && start.boardId() == view.getBoardId()) {
            points.add(unitWaypoint(entity, start.coords(), start.elevation(), start.facing(),
                  startAltitude > 0 ? startAltitude : flightAltitude, start.form()).withProneCause(start.proneCause())
                  .withFallSide(start.fallSide()).withHullDown(start.hullDown()));
        } else if (frame != null) {
            frame.scene().units().stream().filter(unit -> unit.id() == entityId && !unit.sensorContact())
                  .findFirst().ifPresent(unit -> points.add(unit.location()));
        }
        for (UnitLocation location : path) {
            var observedForm = location.form() != null ? location.form() : points.isEmpty() ? null : points.getLast().form();
            BoardScene.Waypoint point = unitWaypoint(entity, location.coords(), location.elevation(),
                  location.facing(), flightAltitude, observedForm).withProneCause(location.proneCause() != null ? location.proneCause()
                        : points.isEmpty() ? null : points.getLast().proneCause())
                  .withFallSide(location.proneCause() != null ? location.fallSide()
                        : points.isEmpty() ? null : points.getLast().fallSide())
                  .withHullDown(location.hullDown() != null ? location.hullDown()
                        : points.isEmpty() ? null : points.getLast().hullDown());
            if (points.isEmpty() || !points.getLast().equals(point)) {
                points.add(point);
            }
        }
        // An infantry path carries no posture: troops that hit the deck lie down where their movement ends.
        if (!points.isEmpty() && entity instanceof megamek.common.units.Infantry
              && proneCause(entity) != points.getLast().proneCause()) {
            points.add(points.getLast().withProneCause(proneCause(entity)));
        }
        // Older paths carry no conversion observations. Show their known final conversion at arrival.
        var finalForm = UnitLocation.Form.capture(entity);
        if (!points.isEmpty() && finalForm != null && path.stream().allMatch(location -> location.form() == null)) {
            var last = path.getLast();
            points.add(unitWaypoint(entity, last.coords(), last.elevation(), last.facing(), flightAltitude, finalForm)
                  .withProneCause(points.getLast().proneCause()).withFallSide(points.getLast().fallSide())
                  .withHullDown(points.getLast().hullDown()));
        }
        // Publish the final visible state and its movement together, so a frame cannot jump to the end first.
        Frame next = capture();
        var captured = next.scene().units().stream()
              .filter(unit -> unit.id() == entityId && !unit.sensorContact()).findFirst().orElse(null);
        if (!points.isEmpty()) {
            points.set(0, supportedEndpoint(points.getFirst(), frame, entityId));
            points.set(points.size() - 1, supportedEndpoint(points.getLast(), next, entityId));
            if (captured != null && points.getLast().coords().equals(captured.location().coords())
                  && (UnitMotion.changesPosture(points.getLast(), captured.location())
                        || points.getLast().elevation() != captured.location().elevation())) {
                // Legacy path steps can omit the fall at arrival; the final entity packet still confirms it.
                points.add(captured.location());
            }
        }
        synchronized (this) {
            // A walk that stopped a hex short of where the game has the unit now, which it reached by a fall the game
            // sent first (onto a structure it broke, holdCollapse), starts there too; once the scene shows it there, the
            // walk would replay from the rubble. A turn in place or a walk back to its hex ends in its own hex.
            boolean shown = captured != null && !path.getLast().coords().equals(captured.location().coords())
                  && !points.isEmpty() && samePlace(points.getFirst(), captured.location()) && frame != null
                  && frame.scene().units().stream()
                        .anyMatch(unit -> unit.id() == entityId && samePlace(unit.location(), captured.location()));
            if (view == movingView && !shown) {
                queueMovement(entity, captured, points, type, jumpMP, movementMP);
            }
            publishScene(next, false);
        }
    }

    private static boolean samePlace(BoardScene.Waypoint a, BoardScene.Waypoint b) {
        return a.coords().equals(b.coords()) && a.elevation() == b.elevation() && !UnitMotion.changesPosture(a, b);
    }

    /** Split only at observed conversion steps; normal travel keeps its one continuous acceleration interval. */
    private void queueMovement(Entity entity, BoardScene.Unit captured, List<BoardScene.Waypoint> points,
          EntityMovementType type, int jumpMP, int movementMP) {
        float gravity = view.game.getPlanetaryConditions().getGravity();
        if (captured == null || captured.model() == null || points.stream().noneMatch(point -> point.form() != null)) {
            queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), points, type, jumpMP, movementMP, captured, gravity));
            return;
        }
        List<BoardScene.Waypoint> leg = new ArrayList<>();
        var form = points.getFirst().form();
        for (var point : points) {
            if (form != null && point.form() != null && form.mode() != point.form().mode()) {
                var before = inForm(captured, entity, leg.getLast(), form);
                var legStart = leg.getFirst();
                if (leg.stream().anyMatch(step -> !step.samePose(legStart))) {
                    queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), leg, type, jumpMP, movementMP, before, gravity));
                }
                var after = inForm(captured, entity, point, point.form());
                queueAnimation(new BoardScene.Conversion(view.getBoardId(), before, after));
                leg = new ArrayList<>();
                form = point.form();
            }
            leg.add(point);
            if (form == null) { form = point.form(); }
        }
        if (leg.size() > 1) {
            queueAnimation(new BoardScene.Movement(entity.getId(), view.getBoardId(), leg, type, jumpMP, movementMP,
                  inForm(captured, entity, leg.getLast(), form), gravity));
        }
    }

    private BoardScene.Unit inForm(BoardScene.Unit unit, Entity entity, BoardScene.Waypoint location, UnitLocation.Form form) {
        var model = UnitModelSelection.inForm(unit.model(), entity, unit.part(), MMStaticDirectoryManager.getMekTileset(), form);
        return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), location, unit.image(), false, unit.annotations(),
              unit.height(), form == null ? unit.airborne() : form.airborne(), model, unit.outlineRgb(), unit.footprint(), unit.attachment(), unit.heat());
    }

    /**
     * Logs what the board receives for an artillery attack, so a log card that offers no Replay can be traced: the
     * card's report line links to the attack only on the same phase, attacking unit, target and weapon name.
     */
    private void logArtilleryAttack(GameAttackResolvedEvent event) {
        var result = event.result();
        boolean isArtillery = (result.shot() != null) && result.shot().artillery();
        if (!isArtillery) {
            return;
        }
        Entity attacker = event.attacker();
        var weapon = (attacker == null) ? null : attacker.getEquipment(result.equipmentIndex());
        LOGGER.debug("[ArtilleryReplay] attack {} by {} (id {}) with {} at {} in phase {} round {}, board {} of {}"
                    + ", attacker visible {}",
              result.id(), attacker == null ? "nobody" : attacker.getShortName(),
              attacker == null ? Entity.NONE : attacker.getId(), weapon == null ? "no weapon" : weapon.getType().getName(),
              event.target() == null ? "nothing" : event.target().getDisplayName(), view.game.getPhase(),
              view.game.getRoundCount(), result.attacker().boardId(), view.getBoardId(),
              (attacker != null) && visible(attacker));
    }

    /** Copy authorized, then-visible appearance once; no Entity reaches the GL thread. */
    private void captureCombat(megamek.common.event.GameAttackResolvedEvent event) {
        requireSwingThread();
        var result = event.result();
        Entity attacker = event.attacker();
        Entity target = event.target() instanceof Entity entity ? entity : null;
        logArtilleryAttack(event);
        if (closed || result.attacker().boardId() != view.getBoardId() || attacker == null
              || !visible(attacker) || sensorContact(attacker)
              || (result.targetType() == Targetable.TYPE_ENTITY && target == null)
              || (target != null && (!visible(target) || sensorContact(target)))
              || !receivedAttacks.add(result.id())) {
            return;
        }
        if (receivedAttacks.size() > 2048) {
            receivedAttacks.removeFirst();
        }
        reports.combat(result, attacker, event.target(), view.game.getRoundCount(), view.game.getPhase());
        if (result.kind() == megamek.common.ResolvedAttack.Kind.DEATH && frame != null
              && frame.scene().units().stream().anyMatch(unit -> unit.id() == attacker.getId() && unit.attachment() != null)) {
            return; // Exterior casualties remain on their carrier until its resolved release, rather than collapsing in midair.
        }
        if (result.kind() == megamek.common.ResolvedAttack.Kind.STOP_SWARM) {
            voluntaryReleases.add(attacker.getId());
            return;
        }
        var usedImages = new IdentityHashMap<Image, Boolean>();
        // A building drawn hex by hex fires from the hex that holds the weapon: its roof turret or wall casemate.
        int firingPart = firingPart(attacker, result.equipmentIndex());
        var firing = unit(attacker, firingPart,
              firingPart < 0 ? result.attacker().coords() : attacker.getSecondaryPositions().get(firingPart), false, usedImages);
        if (result.shot() != null && result.shot().launch() != null) {
            firing = inForm(firing, attacker, unitWaypoint(attacker, result.attacker().coords(),
                  result.attacker().elevation(), result.attacker().facing(), attacker.getAltitude(), result.attacker().form()),
                  result.attacker().form());
        }
        // A building drawn hex by hex is hit on the armed hex shot at; a shot at an unarmed hex has only its impact point.
        boolean targetByHex = target != null && drawnByHex(target);
        int receivingPart = targetByHex ? armedPart(armedHexes(target), result.target().coords()) : -1;
        var receiving = target == null || targetByHex && receivingPart < 0 ? null
              : unit(target, receivingPart, result.target().coords(), false, usedImages);
        if (frame != null) {
            var observed = frame.scene().units().stream().collect(Collectors.toMap(BoardScene.Unit::id,
                  java.util.function.Function.identity(), (first, second) -> first));
            if (observed.containsKey(firing.id())) { firing = firing.withAttachment(observed.get(firing.id()).attachment()); }
            if (receiving != null && observed.containsKey(receiving.id())) {
                receiving = receiving.withAttachment(observed.get(receiving.id()).attachment());
            }
        }
        var destination = receiving == null
              ? waypoint(result.target().coords(), result.target().elevation(), result.target().facing())
              : receiving.location();
        if (result.kind() == megamek.common.ResolvedAttack.Kind.SHAKE_OFF) {
            // Movement resolution sends this before the carrier's path. Keep its confirmed result with that path.
            removalAttempts.put(attacker.getId(), new BoardScene.Combat(result, firing, receiving, destination));
            return;
        }
        // A resolved attack packet precedes its damage packets. This checkpoint closes the preceding action.
        Frame before = capture();
        synchronized (this) {
            publishScene(before, true);
            // After that checkpoint: a collapse it shows still takes the preceding attack's hit.
            recordStructureHit(result, attacker);
            // No death plays after an attack resolved after it.
            queueDeaths(true);
            var combat = new BoardScene.Combat(result, firing, receiving, destination,
                  target != null && target.isConventionalInfantry(), attacker.isOffBoard(), attacker.isClan());
            if (result.kind() == megamek.common.ResolvedAttack.Kind.DEATH) { deaths.add(combat); }
            else { queueAnimation(combat); }
        }
    }

    /**
     * Remembers the hit of an attack whose damage brought structures down (the server's ResolvedAttack#collapses), for
     * each one's collapse to fall away from if it follows this attack's animation (structureHits); any attack ends the
     * last one's. A weapon's strength is its damage per hit, a physical attack's the damage the rules give it; a death
     * from above comes from above the hex, a shell from where it landed, at half its strength beside it. Only very strong
     * hits push: an AC/20 (20 a hit) at full strength, a Gauss rifle (15) at half; a PPC (10), a machine gun or a missile
     * brings a building straight down.
     */
    private void recordStructureHit(megamek.common.ResolvedAttack result, Entity attacker) {
        if (result.kind() == megamek.common.ResolvedAttack.Kind.DEATH) { return; }
        // The trees it takes down break away from it, whatever its strength (publishScene).
        var shot = result.shot();
        Coords landing = shot != null && shot.artillery() && shot.impact() != null ? shot.impact().coords() : null;
        treeHexes = result.cleared().stream().filter(hex -> hex.boardId() == view.getBoardId()).map(megamek.common.board.BoardLocation::coords)
              .collect(Collectors.toSet());
        treeHit = new BoardScene.TileTransition.Hit(1, hitFrom(result, landing));
        treeHitRound = view.game.getRoundCount();
        treeHitPhase = view.game.getPhase();
        structureHits.clear();
        // The server names them only for a hit, or a shell that landed, even scattered.
        if (result.collapses().isEmpty()) { return; }
        structureHitsRound = view.game.getRoundCount();
        structureHitsPhase = view.game.getPhase();
        float damage = shot != null ? (float) shot.damagePerHit() : physicalDamage(result, attacker);
        float strength = Math.min(1, (damage - 10) / 10);
        if (strength <= 0) { return; }
        var from = hitFrom(result, landing);
        for (var hex : result.collapses()) {
            if (hex.boardId() != view.getBoardId()) { continue; }
            structureHits.put(hex.coords(), new BoardScene.TileTransition.Hit(
                  landing == null || landing.equals(hex.coords()) ? strength : strength * .5f, from));
        }
    }

    /** Where an attack's hit comes from: a shell from where it landed, a death from above from above, else its attacker. */
    private BoardScene.Waypoint hitFrom(megamek.common.ResolvedAttack result, Coords landing) {
        return landing != null ? waypoint(landing, 0, 0)
              : result.kind() == megamek.common.ResolvedAttack.Kind.DFA
                    ? waypoint(result.target().coords(), result.target().elevation() + DFA_HEIGHT, 0)
                    : waypoint(result.attacker().coords(), result.attacker().elevation(), result.attacker().facing());
    }

    /** Levels above its target a death from above comes down from, which crushes a structure rather than tip it over. */
    private static final int DFA_HEIGHT = 12;

    /**
     * A nuclear strike as the hit on every structure of {@code before} in its reach (structureHits), which it pushes away
     * from ground zero: what its crater takes at full strength, what its blast reaches at the strength its damage there
     * gives, as an attack's damage per hit does (recordStructureHit). The rules' blast loses its degradation each hex out
     * (TWGameManager#doExplosion); its crater reaches twice its depth.
     */
    private void recordBlast(megamek.common.NuclearDetonation strike, BoardScene before) {
        structureHits.clear();
        structureHitsRound = view.game.getRoundCount();
        structureHitsPhase = view.game.getPhase();
        for (var tile : before.tiles()) {
            if (!BoardScene.TileTransition.structure(tile)) { continue; }
            var hit = BoardScene.TileTransition.blast(strike, tile.coords());
            if (hit != null) { structureHits.put(tile.coords(), hit); }
        }
    }

    /**
     * Everything a strike brings down, each pushed away from ground zero (recordBlast): what its own checkpoint
     * ({@code after}, null on another board) shows gone, and what the server names it took to CF 0, which the rules bring
     * down at the phase's end; their later transition finds them fallen already.
     */
    private List<BoardScene.TileTransition.Collapse> strikeCollapses(megamek.common.NuclearDetonation strike,
          BoardScene before, BoardScene after) {
        Map<Coords, BoardScene.TileTransition.Collapse> falls = new LinkedHashMap<>();
        var crater = after == null ? null : BoardScene.TileTransition.of(before, after,
              at -> BoardScene.TileTransition.blast(strike, at));
        if (crater != null) { crater.collapses().forEach(collapse -> falls.put(collapse.coords(), collapse)); }
        for (var at : strike.collapses()) {
            var tile = at.boardId() == strike.boardId() ? before.tile(at.coords()) : null;
            if (tile != null && BoardScene.TileTransition.structure(tile) && !falls.containsKey(at.coords())) {
                falls.put(at.coords(), BoardScene.TileTransition.collapse(before, tile,
                      BoardScene.TileTransition.blast(strike, at.coords())));
            }
        }
        return List.copyOf(falls.values());
    }

    /**
     * The woods cover the rules took down between two checkpoints of one board, in the hexes {@code near}: each hex's
     * cover features ({@link BoardFeatures#cover}) of {@code before} that stand nowhere in {@code after}, where a blast
     * or an attack took its woods down. A tree stands on where one of any species does; a skin's row, where the same row
     * does.
     */
    private static List<BoardScene.Detonation.Felled> felled(BoardScene before, BoardScene after,
          java.util.function.Predicate<Coords> near) {
        List<BoardScene.Detonation.Felled> result = new ArrayList<>();
        for (var tile : before.tiles()) {
            if (!near.test(tile.coords())) { continue; }
            var next = after.tile(tile.coords());
            List<BoardScene.Feature> felled = tile.features().stream()
                  .filter(cover -> BoardFeatures.cover(cover) && (next == null || next.features().stream()
                        .noneMatch(kept -> BoardFeatures.cover(kept) && kept.kind() == cover.kind()
                              && (cover.kind() == BoardScene.FeatureKind.TREE || kept.asset().equals(cover.asset()))
                              && Math.abs(kept.x() - cover.x()) < 1e-3f && Math.abs(kept.y() - cover.y()) < 1e-3f)))
                  .toList();
            if (!felled.isEmpty()) { result.add(new BoardScene.Detonation.Felled(tile.coords(), felled)); }
        }
        return result;
    }

    /** The damage the rules give a physical attack, 0 for one that deals none to a structure or cannot be read. */
    private static float physicalDamage(megamek.common.ResolvedAttack result, Entity attacker) {
        try {
            return switch (result.kind()) {
                case KICK -> megamek.common.actions.KickAttackAction.getDamageFor(attacker, result.limb(), false);
                case PUNCH -> megamek.common.actions.PunchAttackAction.getDamageFor(attacker, result.limb(), false, false);
                case CHARGE -> megamek.common.actions.ChargeAttackAction.getDamageFor(attacker);
                case DFA -> megamek.common.actions.DfaAttackAction.getDamageFor(attacker, false);
                case CLUB -> attacker.getEquipment(result.equipmentIndex()) instanceof megamek.common.equipment.MiscMounted club
                      ? megamek.common.actions.ClubAttackAction.getDamageFor(attacker, club, false, false) : 0;
                default -> 0;
            };
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    /**
     * Queues the deaths received since: all of them, or those whose unit the game now has destroyed. One that its unit's
     * own movement or fall led to plays right after it, where it left the unit.
     */
    private void queueDeaths(boolean all) {
        for (var iterator = deaths.iterator(); iterator.hasNext();) {
            var death = iterator.next();
            int id = death.attacker().id();
            Entity entity = view.game.getEntity(id);
            if (!all && entity != null && !entity.isDoomed() && !entity.isDestroyed()) { continue; }
            iterator.remove();
            int moved = -1;
            for (int index = 0; index < pendingEvents.size(); index++) {
                if (pendingEvents.get(index) instanceof BoardScene.Movement move && move.entityId() == id) { moved = index; }
            }
            if (moved < 0 || !captures.isEmpty()) {
                queueAnimation(death);
                continue;
            }
            var end = ((BoardScene.Movement) pendingEvents.get(moved)).path().getLast();
            pendingEvents.add(moved + 1, new BoardScene.Combat(death.result(), death.attacker().at(end),
                  death.target() == null ? null : death.target().at(end), end, death.conventionalInfantryTarget(),
                  death.attackerOffBoard(), death.attackerClan()));
            animationsQueued++;
        }
    }

    private synchronized void queueAnimation(BoardScene.Animation animation) {
        // A concealment drops what it follows: the deaths received before it go first.
        if (animation instanceof BoardScene.Concealed) { queueDeaths(true); }
        if (!captures.isEmpty()) {
            // One final checkpoint follows the flash. Deaths keep their captured victims and play afterwards.
            if (!(animation instanceof BoardScene.SceneUpdate)) { captures.peek().consequences().add(animation); }
            return;
        }
        if (animation instanceof BoardScene.SceneUpdate
              && !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.SceneUpdate) {
            pendingEvents.removeLast();
        }
        if (pendingEvents.size() >= UnitPlayback.MAX_PENDING_EVENTS) {
            pendingEvents.clear();
        }
        pendingEvents.add(animation);
        if (!(animation instanceof BoardScene.SceneUpdate) && !(animation instanceof BoardScene.Concealed)) {
            animationsQueued++;
            view.setMovingUnits(true);
        }
    }

    /** Consecutive captures share one checkpoint; animation boundaries are never coalesced. Swing owns capture. */
    private synchronized void publishScene(Frame next, boolean detectGear) {
        if (next.scene() == null) { pendingEvents.clear(); deaths.clear(); frame = next; return; }
        if (frame != null && frame.scene() == null) { frame = null; }
        // A nuclear strike's captures leave what it brings down to its completion, which plays it right after its flash.
        var lifts = frame != null && frame.boardGeneration() == next.boardGeneration()
              ? BoardScene.TileTransition.of(frame.scene(), next.scene(), captures.isEmpty() ? structureHits::get : null)
              : null;
        boolean collapses = lifts != null && !lifts.collapses().isEmpty();
        if (collapses && holdCollapse(detectGear)) { return; }
        // A collapse held back in the movement phase stays that phase's, whichever the game has moved on to since.
        boolean moving = view.game.getPhase().isMovement() || collapseHeldSince != 0;
        collapseHeldSince = 0;
        if (collapses) { lifts.collapses().forEach(collapse -> structureHits.remove(collapse.coords())); }
        // This capture took the hits of its phase's last attack or strike; one of a later phase finds none left.
        if (view.game.getRoundCount() != structureHitsRound || view.game.getPhase() != structureHitsPhase) {
            structureHits.clear();
        }
        // The units that stood on a structure coming down fall with it, after it starts to give way; one whose weight
        // brings it down steps onto it first.
        List<BoardScene.Movement> falls = new ArrayList<>();
        List<BoardScene.Movement> steps = new ArrayList<>();
        if (collapses && moving) { lifts = stepsOnto(lifts, next, steps, falls); }
        Set<Integer> stepped = steps.stream().map(BoardScene.Movement::entityId).collect(Collectors.toSet());
        if (collapses && !detectGear) {
            falls(lifts, next).stream().filter(fall -> !stepped.contains(fall.entityId())).forEach(falls::add);
        }
        if (detectGear && frame != null && frame.boardGeneration() == next.boardGeneration()) {
            // A unit drawn in parts moves as one: its first drawn part stands for it, never another part's hex.
            Map<Integer, BoardScene.Unit> previous = new HashMap<>();
            frame.scene().units().stream().filter(unit -> !unit.sensorContact())
                  .forEach(unit -> previous.putIfAbsent(unit.id(), unit));
            for (var old : previous.values()) {
                Entity entity = view.game.getEntity(old.id());
                if (entity != null && (!visible(entity) || sensorContact(entity))) {
                    queueAnimation(new BoardScene.Concealed(old.id(), next.scene().boardId()));
                }
            }
            for (var unit : next.scene().units()) {
                var old = previous.get(unit.id());
                if (old != null && old.part() != unit.part()) { continue; }
                if (UnitConversion.changes(old, unit)) {
                    queueAnimation(new BoardScene.Conversion(next.scene().boardId(), old, unit));
                }
                // Deployment chooses a position and elevation; it is never a movement or takeoff.
                if (next.status().phase().isDeployment()) { continue; }
                if (old != null && !unit.sensorContact() && UnitMotion.changesGear(old.location(), unit.location())) {
                    Entity entity = view.game.getEntity(unit.id());
                    queueAnimation(new BoardScene.Movement(unit.id(), next.scene().boardId(),
                          List.of(old.location(), unit.location()), EntityMovementType.MOVE_SAFE_THRUST, 0,
                          entity == null ? 0 : movementMP(entity, EntityMovementType.MOVE_SAFE_THRUST), unit));
                } else if (old != null && old.attachment() == null && unit.attachment() == null
                      && UnitMotion.forcedChange(old, unit) && !rides(lifts, old, unit) && !stepped.contains(unit.id())) {
                    // No movement path accompanied this authoritative update (push, domino displacement, or fall).
                    var forced = new BoardScene.Movement(unit.id(), next.scene().boardId(),
                          List.of(old.location(), unit.location()), EntityMovementType.MOVE_NONE, 0, 0, unit);
                    if (collapses && lifts.collapses(old.location().coords())) { falls.add(forced); }
                    else { queueAnimation(forced); }
                }
            }
        }
        if (frame != null && frame.boardGeneration() == next.boardGeneration()) {
            queueAttachments(frame.scene(), next.scene());
        }
        // The trees the attack being resolved took down break away from it as this checkpoint shows (GpuTerrain#breakTrees);
        // a strike's its own completion fells.
        boolean attacking = !treeHexes.isEmpty() && view.game.getRoundCount() == treeHitRound
              && view.game.getPhase() == treeHitPhase;
        List<BoardScene.Detonation.Felled> broken = attacking && captures.isEmpty() && frame != null
              && frame.boardGeneration() == next.boardGeneration() && next.scene().boardId() == frame.scene().boardId()
              ? felled(frame.scene(), next.scene(), treeHexes::contains) : List.of();
        steps.forEach(this::queueAnimation);
        if (lifts != null) { queueAnimation(lifts); }
        falls.forEach(this::queueAnimation);
        queueDeaths(false);
        if (frame == null || frame.boardGeneration() != next.boardGeneration() || !next.scene().samePlaybackState(frame.scene())
              || (!pendingEvents.isEmpty() && !(pendingEvents.getLast() instanceof BoardScene.SceneUpdate)) || !broken.isEmpty()) {
            queueAnimation(new BoardScene.SceneUpdate(next.scene(), broken, broken.isEmpty() ? null : treeHit));
        }
        frame = next;
    }

    /**
     * The longest a collapse waits for the end of the move that brought it down, should it never come: a live server
     * sends it after the unit's fall, its damage, reports and turn, often well over a few hundred milliseconds after the
     * collapse.
     */
    private static final long COLLAPSE_HOLD_NANOS = 5_000_000_000L;

    /**
     * Whether to hold back a capture that shows a structure brought down in the movement phase. Only the unit moving
     * brings one down then, and the server sends the collapse under its weight, and its fall, before the move's last
     * update, with the path that walked it there (MovePathHandler): the path's capture publishes the collapse after the
     * walk (captureMovement), and an update without one, of a unit that stepped straight onto it, publishes it at once
     * (endsHeldMove), however late either comes. The phase's end lets one go at once too.
     */
    private boolean holdCollapse(boolean detectGear) {
        if (!detectGear || !view.game.getPhase().isMovement()) { return false; }
        long now = System.nanoTime();
        if (collapseHeldSince == 0) { collapseHeldSince = now; }
        return now - collapseHeldSince < COLLAPSE_HOLD_NANOS;
    }

    /** Ends a held collapse's wait (holdCollapse): the next capture shows it. */
    private void releaseCollapse() {
        if (collapseHeldSince != 0) { collapseHeldSince = System.nanoTime() - COLLAPSE_HOLD_NANOS; }
    }

    /**
     * Whether an update without a path ends the move that brought a held collapse down (holdCollapse): it leaves its unit
     * where the update before it, its fall, put it, which the held capture alone shows; the published scene still has it
     * where it stood before the move, beside the structure, which still stands there. Its fall moved it; a unit that
     * stood on the structure is shown there already, and one the fall pushed aside lies where none stands.
     */
    private boolean endsHeldMove(int entityId, UnitLocation start, Coords at, int elevation) {
        if (collapseHeldSince == 0 || start == null || at == null || !at.equals(start.coords()) || elevation != start.elevation()
              || frame == null || frame.scene() == null) { return false; }
        var held = frame.scene().tile(at);
        if (held == null || !BoardScene.TileTransition.structure(held)) { return false; }
        var shown = frame.scene().units().stream().filter(unit -> unit.id() == entityId).toList();
        return !shown.isEmpty() && shown.stream().noneMatch(unit -> unit.location().coords().equals(at));
    }

    /**
     * The units whose weight brings down the structure they step or jump onto in the movement phase. A walker's path
     * stops before that step, and the server sends only where it fell (MovePathHandler): it walks on from where it was
     * last shown onto the structure's deck, or into the building at the height it came from ({@code steps}). A jump's
     * path lands on the roof, and its walk ends there, before the fall the capture appended to it. Each stands on the
     * structure as a load of the returned transition and falls with it ({@code falls}).
     */
    private BoardScene.TileTransition stepsOnto(BoardScene.TileTransition transition, Frame next,
          List<BoardScene.Movement> steps, List<BoardScene.Movement> falls) {
        Map<Coords, List<BoardScene.Waypoint>> loads = new HashMap<>();
        for (var unit : next.scene().units()) {
            var collapse = transition.collapses().stream()
                  .filter(candidate -> candidate.coords().equals(unit.location().coords())).findFirst().orElse(null);
            Entity entity = view.game.getEntity(unit.id());
            if (collapse == null || entity == null || unit.sensorContact() || unit.attachment() != null) { continue; }
            int walked = -1;
            for (int index = 0; index < pendingEvents.size(); index++) {
                if (pendingEvents.get(index) instanceof BoardScene.Movement move && move.entityId() == unit.id()) { walked = index; }
            }
            var walk = walked < 0 ? null : (BoardScene.Movement) pendingEvents.get(walked);
            var last = walk != null ? walk.path().getLast() : frame.scene().units().stream()
                  .filter(old -> old.id() == unit.id() && old.part() == unit.part() && !old.sensorContact())
                  .map(BoardScene.Unit::location).findFirst().orElse(null);
            var before = collapse.before();
            BoardScene.Waypoint on;
            var path = walk == null ? null : walk.path();
            if (walk != null && last.coords().equals(collapse.coords())) {
                // Its last pose up on the structure, before the fall.
                int landed = -1;
                for (int index = 0; index < path.size(); index++) {
                    var point = path.get(index);
                    if (point.coords().equals(collapse.coords()) && point.elevation() > before.elevation()) { landed = index; }
                }
                if (landed < 1) { continue; }
                on = path.get(landed);
                path = path.subList(0, landed + 1);
            } else if (last != null && last.coords().distance(collapse.coords()) == 1) {
                float floor = Math.max(unit.location().elevation(),
                      Math.min(last.elevation(), before.elevation() + before.cover().height()));
                float height = before.features().stream().filter(feature -> "bridge".equals(feature.asset()))
                      .map(feature -> before.elevation() + feature.elevation()).findFirst().orElse(floor);
                // A step turns nothing: it faces as it did, forward or backing onto it.
                on = new BoardScene.Waypoint(collapse.coords(), height, last.facing(), last.proneCause(), last.aeroState(),
                      unit.location().footprint(), last.form(), last.fallSide(), last.hullDown());
                steps.add(walk != null
                      ? new BoardScene.Movement(unit.id(), next.scene().boardId(), List.of(last, on), walk.type(),
                            walk.jumpMP(), walk.movementMP(), unit.at(on), walk.gravity())
                      : new BoardScene.Movement(unit.id(), next.scene().boardId(), List.of(last, on),
                            EntityMovementType.MOVE_WALK, 0, movementMP(entity, EntityMovementType.MOVE_WALK), unit.at(on)));
            } else { continue; }
            // Each move leaves the unit where it ends, not where the game has it now.
            if (walk != null) {
                pendingEvents.set(walked, new BoardScene.Movement(walk.entityId(), walk.boardId(), path, walk.type(),
                      walk.jumpMP(), walk.movementMP(), unit.at(path.getLast()), walk.gravity()));
            }
            if (on.elevation() > before.elevation()) { loads.computeIfAbsent(collapse.coords(), at -> new ArrayList<>()).add(on); }
            if (!on.equals(unit.location())) {
                falls.add(new BoardScene.Movement(unit.id(), next.scene().boardId(), List.of(on, unit.location()),
                      EntityMovementType.MOVE_NONE, 0, 0, unit));
            }
        }
        if (loads.isEmpty()) { return transition; }
        return new BoardScene.TileTransition(transition.boardId(), transition.changes(), transition.collapses().stream()
              .map(collapse -> !loads.containsKey(collapse.coords()) ? collapse : new BoardScene.TileTransition.Collapse(
                    collapse.coords(), collapse.before(), collapse.strength(), collapse.from(),
                    java.util.stream.Stream.concat(collapse.loads().stream(), loads.get(collapse.coords()).stream()).toList()))
              .toList());
    }

    /**
     * The falls of the units that stood on a structure {@code transition} brings down, for a capture that does not look
     * for forced moves, a path's: every unit but the mover, whose path the capture has just queued.
     */
    private List<BoardScene.Movement> falls(BoardScene.TileTransition transition, Frame next) {
        Map<Integer, BoardScene.Unit> before = new HashMap<>();
        frame.scene().units().stream().filter(unit -> !unit.sensorContact()).forEach(unit -> before.putIfAbsent(unit.id(), unit));
        List<BoardScene.Movement> result = new ArrayList<>();
        for (var unit : next.scene().units()) {
            var old = before.get(unit.id());
            if (old == null || old.part() != unit.part() || unit.sensorContact() || !transition.collapses(old.location().coords())
                  || old.attachment() != null || unit.attachment() != null || !UnitMotion.forcedChange(old, unit)
                  || pendingEvents.stream().anyMatch(event -> event instanceof BoardScene.Movement move
                        && move.entityId() == unit.id())) { continue; }
            result.add(new BoardScene.Movement(unit.id(), next.scene().boardId(), List.of(old.location(), unit.location()),
                  EntityMovementType.MOVE_NONE, 0, 0, unit));
        }
        return result;
    }

    /**
     * A unit standing on an elevator the server moved keeps its place on the platform: it rides it up or down
     * ({@link BoardScene.TileTransition}), no forced move.
     */
    private static boolean rides(BoardScene.TileTransition lifts, BoardScene.Unit before, BoardScene.Unit after) {
        var from = before.location();
        var to = after.location();
        return lifts != null && from.coords().equals(to.coords()) && lifts.moves(to.coords())
              && !UnitMotion.changesPosture(from, to);
    }

    /** Close carrier movement at its destination before releasing any of its exterior passengers. */
    private void queueAttachments(BoardScene previous, BoardScene next) {
        Map<Integer, BoardScene.Unit> oldUnits = new HashMap<>(), newUnits = new HashMap<>();
        previous.units().forEach(unit -> oldUnits.put(unit.id(), unit));
        next.units().forEach(unit -> newUnits.put(unit.id(), unit));
        List<BoardScene.AttachmentChange> changes = new ArrayList<>();
        List<BoardScene.Unit> held = new ArrayList<>(next.units());
        for (var before : oldUnits.values()) {
            var after = newUnits.get(before.id());
            var from = before.attachment();
            var to = after == null ? null : after.attachment();
            if (before.sensorContact() || after != null && after.sensorContact() || java.util.Objects.equals(from, to)) { continue; }
            int carrierId = to == null ? from.carrierId() : to.carrierId();
            var carrier = newUnits.get(carrierId);
            if (carrier == null || carrier.sensorContact()) { continue; }
            Entity passenger = view.game.getEntityFromAllSources(before.id());
            if (after == null && (passenger == null || !passenger.isDestroyed() && !passenger.isDoomed())) { continue; }
            if (passenger != null && (!EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, passenger)
                  || sensorContact(passenger))) { continue; }
            var release = to != null ? BoardScene.Release.BOARD : from.hostile() ? BoardScene.Release.THROWN : BoardScene.Release.CLIMB_DOWN;
            boolean voluntary = to == null && voluntaryReleases.remove(before.id());
            if (voluntary) { release = BoardScene.Release.CLIMB_DOWN; }
            var movement = !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.Movement move
                  && move.entityId() == carrierId ? move : null;
            var destination = after != null ? after.location() : passenger != null && passenger.getPosition() != null
                  ? waypoint(passenger.getPosition(), passenger.getElevation(), passenger.getFacing()) : carrier.location();
            if (to == null && !voluntary && movement != null) {
                var tile = next.tile(destination.coords());
                if (tile != null && tile.waterDepth() > 0 && !tile.frozen()) { release = BoardScene.Release.WATER; }
                if (movement.type() == EntityMovementType.MOVE_JUMP) { release = BoardScene.Release.JUMP; }
            }
            changes.add(new BoardScene.AttachmentChange(next.boardId(), before, after, carrier, release, destination));
            held.removeIf(unit -> unit.id() == before.id());
            held.add(before);
        }
        var attempts = removalAttempts.values().stream().filter(attempt -> newUnits.containsKey(attempt.entityId())
              && pendingEvents.stream().anyMatch(event -> event instanceof BoardScene.Movement
                    && event.entityId() == attempt.entityId())).toList();
        if (changes.isEmpty() && attempts.isEmpty()) { return; }
        BoardScene.Movement continuation = null;
        if (changes.size() == 1 && changes.getFirst().release() == BoardScene.Release.WATER
              && !pendingEvents.isEmpty() && pendingEvents.getLast() instanceof BoardScene.Movement movement
              && movement.entityId() == changes.getFirst().carrier().id() && movement.type() != EntityMovementType.MOVE_JUMP) {
            var change = changes.getFirst();
            int index = releaseStep(movement.path(), change.destination());
            if (index > 0 && index < movement.path().size() - 1) {
                // The unit can continue past the water-entry hex. Release there, never at its later endpoint.
                var atWater = change.carrier().at(movement.path().get(index));
                pendingEvents.set(pendingEvents.size() - 1, new BoardScene.Movement(movement.entityId(), movement.boardId(),
                      movement.path().subList(0, index + 1), movement.type(), movement.jumpMP(), movement.movementMP(), atWater, movement.gravity()));
                continuation = new BoardScene.Movement(movement.entityId(), movement.boardId(),
                      movement.path().subList(index, movement.path().size()), movement.type(), movement.jumpMP(), movement.movementMP(),
                      movement.unit(), movement.gravity());
                held.replaceAll(unit -> unit.id() == atWater.id() ? atWater : unit);
                changes.set(0, new BoardScene.AttachmentChange(change.boardId(), change.before(), change.after(), atWater,
                      change.release(), change.destination()));
            }
        }
        queueAnimation(new BoardScene.SceneUpdate(next.withUnits(held)));
        for (var attempt : attempts) {
            removalAttempts.remove(attempt.entityId());
            var carrier = newUnits.get(attempt.entityId());
            Entity target = attempt.target() == null ? null : view.game.getEntityFromAllSources(attempt.target().id());
            if (carrier.sensorContact() || target == null || !visible(target) || sensorContact(target)) { continue; }
            queueAnimation(new BoardScene.Combat(attempt.result(), carrier, attempt.target(), carrier.location()));
        }
        changes.forEach(this::queueAnimation);
        if (continuation != null) {
            var atWater = changes.getFirst().carrier();
            queueAnimation(new BoardScene.SceneUpdate(next.withUnits(next.units().stream()
                  .map(unit -> unit.id() == atWater.id() ? atWater : unit).toList())));
            queueAnimation(continuation);
        }
    }

    static int releaseStep(List<BoardScene.Waypoint> path, BoardScene.Waypoint destination) {
        for (int index = 1; index < path.size(); index++) {
            if (path.get(index).coords().equals(destination.coords())) { return index; }
        }
        return -1;
    }

    /** Copy the game's current capability once at the event boundary; the renderer never recalculates MP rules. */
    static int movementMP(Entity entity, EntityMovementType type) {
        if (entity instanceof Aero aero && aero.isAirborne()) {
            return Math.max(1, aero.getCurrentVelocity());
        }
        return switch (type) {
            case MOVE_JUMP -> entity.getJumpMP();
            case MOVE_SPRINT, MOVE_VTOL_SPRINT -> entity.getSprintMP();
            case MOVE_RUN, MOVE_VTOL_RUN, MOVE_SUBMARINE_RUN, MOVE_OVER_THRUST, MOVE_SKID -> entity.getRunMP();
            default -> entity.getWalkMP();
        };
    }

    /** A landed path endpoint uses the same whole-footprint support as its captured hull, not just its centre hex. */
    private static BoardScene.Waypoint supportedEndpoint(BoardScene.Waypoint point, Frame frame, int id) {
        if (frame == null || frame.scene() == null || point.aeroState() != BoardScene.AeroState.LANDED) {
            return point;
        }
        return frame.scene().units().stream().filter(unit -> unit.id() == id && !unit.sensorContact()
                    && unit.footprint().size() > 1 && unit.location().coords().equals(point.coords())
                    && unit.location().aeroState() == BoardScene.AeroState.LANDED)
              .map(unit -> new BoardScene.Waypoint(point.coords(), unit.location().elevation(), point.facing(),
                    point.proneCause(), point.aeroState(), unit.footprint(), point.form(), point.fallSide(), point.hullDown())).findFirst().orElse(point);
    }

    public void refresh() {
        requireSwingThread();
        if (!closed) {
            Frame next = capture();
            synchronized (this) {
                publishScene(next, true);
            }
        }
    }

    @Override
    public void reloadAssets() throws java.io.IOException {
        requireSwingThread();
        if (closed) { return; }
        megamek.common.util.ImageUtil.reloadImages();
        MMStaticDirectoryManager.refreshMekTileset();
        MMStaticDirectoryManager.refreshCamouflageDirectory();
        view.reloadAssets();
        unitImages.clear();
        camouflage.clear();
        terrainImages.clear();
        terrain.reload();
        refresh();
    }

    @Override
    public synchronized BoardAtmosphere.Settings scenarioAtmosphere() {
        return frame == null ? BoardAtmosphere.DEFAULTS : frame.scenarioAtmosphere();
    }

    public synchronized Frame takeFrame() {
        Frame result = (captures.isEmpty() ? frame : captures.peekLast().before()).withTimeline(List.copyOf(pendingEvents));
        pendingEvents.clear();
        animationsTaken = animationsQueued;
        return result;
    }

    private Frame capture() {
        refreshPreferences();
        boolean changedState = false;
        if (view.getClientgui() != null) {
            BoardClientState selectedView = view.getClientgui().getCurrentBoardState().orElse(view);
            if (selectedView != view) {
                changedState = true;
                view.setMovingUnits(false);
                view.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
                view = selectedView;
                unitImages.clear();
                terrain.changed(null);
                synchronized (this) {
                    pendingEvents.clear();
                    deaths.clear();
                    captures.clear();
                    structureHits.clear();
                    collapseHeldSince = 0;
                }
            }
        }
        Board current = view.isClosed() ? null : view.getBoard();
        if (changedState || current != board) {
            if (board != null) { board.removeBoardListener(boardListener); }
            board = current;
            contextCoords = null;
            contextUnitId = Entity.NONE;
            contextSelectionCleared = false;
            boardGeneration++;
            BoardClientState owner = view;
            long generation = boardGeneration;
            // Commands captured for an older board or state go stale, also when the same state gets a new board.
            actions = new GpuBoardActions(owner, phasePanel,
                  () -> !acceptsInput() || !isCurrentView(owner) || generation != boardGeneration, this::refresh);
            view.setMovingUnits(false);
            atmosphere.reset();
            synchronized (this) {
                pendingEvents.clear();
                deaths.clear();
                captures.clear();
                structureHits.clear();
                collapseHeldSince = 0;
                receivedAttacks.clear();
                removalAttempts.clear();
                voluntaryReleases.clear();
            }
            if (board != null) { board.addBoardListener(boardListener); }
            terrain.changed(null);
        }
        if (board == null) {
            tiles = List.of();
            terrainImages.clear();
            unitImages.clear();
            fieldOfView = BoardFieldOfView.EMPTY;
            return new Frame(null, List.of(), null, List.of(), "",
                  new BoardFocus(0, null), boardGeneration, "", BoardAtmosphere.DEFAULTS,
                  GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY);
        }
        view.setVisibleArea(() -> {
            Rectangle area = visibleArea;
            Board currentBoard = view.getBoard();
            return area == null ? new double[] { 0, 0, 1, 1 } : new double[] {
                  area.x / (double) currentBoard.getWidth(), area.y / (double) currentBoard.getHeight(),
                  (area.x + area.width) / (double) currentBoard.getWidth(),
                  (area.y + area.height) / (double) currentBoard.getHeight() };
        });
        // The recaptured hexes (the whole board after a full invalidation) lose their markings until the pass below.
        Rectangle[] recaptured = { null };
        List<BoardScene.Tile> captured = terrain.tiles(board, (coords, art) -> {
            Rectangle hex = new Rectangle(coords.getX(), coords.getY(), 1, 1);
            recaptured[0] = recaptured[0] == null ? hex : recaptured[0].union(hex);
        });
        Rectangle changedHexes = recaptured[0];
        boolean changedTerrain = changedHexes != null;
        if (changedTerrain) {
            List<BoardScene.Tile> nextTiles = new ArrayList<>(tiles.size() == captured.size() ? tiles : captured);
            for (int x = changedHexes.x; x < changedHexes.x + changedHexes.width; x++) {
                for (int y = changedHexes.y; y < changedHexes.y + changedHexes.height; y++) {
                    int index = x * board.getHeight() + y;
                    nextTiles.set(index, captured.get(index));
                }
            }
            tiles = List.copyOf(nextTiles);
        }
        Rectangle area = visibleArea;
        long revision = view.getPlanarRevision();
        boolean allLabels = revision != capturedRevision || view.game.getPhase() != capturedPhase;
        if (changedTerrain || allLabels || !area.equals(capturedArea)) {
            List<BoardScene.Tile> labelled = new ArrayList<>(tiles);
            // A terrain edit relabels its changed hexes and newly exposed view only. A repaint of the client state
            // relabels the whole view, including FoV changes caused by an edited blocker.
            List<Rectangle> exposed = new ArrayList<>();
            if (allLabels || capturedArea == null || !area.intersects(capturedArea)) {
                exposed.add(area);
            } else {
                exposed.addAll(List.of(SwingUtilities.computeDifference(area, capturedArea)));
                if (changedHexes != null) {
                    Rectangle local = changedHexes.intersection(area).intersection(capturedArea);
                    if (!local.isEmpty()) { exposed.add(local); }
                }
            }
            for (Rectangle region : exposed) {
                Rectangle hexes = region.intersection(new Rectangle(0, 0, board.getWidth(), board.getHeight()));
                for (int x = hexes.x; x < hexes.x + hexes.width; x++) {
                    for (int y = hexes.y; y < hexes.y + hexes.height; y++) {
                        int index = x * board.getHeight() + y;
                        labelled.set(index, tiles.get(index).withText(BoardArtwork.labels(board, new Coords(x, y))));
                    }
                }
            }
            if (!labelled.equals(tiles)) {
                tiles = List.copyOf(labelled);
            }
            terrainImages.retain(tiles);
            fieldOfView = view.captureFieldOfView(area);
            capturedArea = area;
            capturedRevision = revision;
            capturedPhase = view.game.getPhase();
        }
        List<BoardScene.Unit> units = new ArrayList<>();
        camouflage.begin();
        Map<Image, Boolean> usedImages = new IdentityHashMap<>();
        var attachments = exteriorAttachments(view.game.getEntitiesVector(), entity ->
              EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity) && !sensorContact(entity),
              this::visible);
        for (Entity entity : view.game.getEntitiesVector()) {
            var attachment = attachments.get(entity.getId());
            if (attachment == null && (!visible(entity) || entity.getTransportId() != Entity.NONE)) {
                continue;
            }
            if (attachment != null) {
                Entity carrier = view.game.getEntity(attachment.carrierId());
                var before = frame == null ? null : frame.scene().units().stream()
                      .filter(unit -> unit.id() == entity.getId() && !unit.sensorContact()).findFirst().orElse(null);
                units.add((entity.isDestroyed() || entity.isDoomed()) && before != null ? before
                      : unit(entity, -1, carrier.getPosition(), false, usedImages).withAttachment(attachment));
                continue;
            }
            boolean sensor = sensorContact(entity);
            boolean wholeModel = !sensor && GpuUnitModels.ENABLED
                  && MMStaticDirectoryManager.getMekTileset().modelFor(entity, -1) != null;
            if (wholeModel && drawnByHex(entity)) {
                // An armed building drawn hex by hex shows each hex that holds weapons: its roof turret and casemates.
                armedHexes(entity).forEach((part, coords) -> units.add(unit(entity, part, coords, false, usedImages)));
            } else if (entity.getSecondaryPositions().isEmpty() || sensor || wholeModel) {
                units.add(unit(entity, -1, entity.getPosition(), sensor, usedImages));
            } else {
                entity.getSecondaryPositions().forEach((part, coords) ->
                      units.add(unit(entity, part, coords, false, usedImages)));
            }
        }
        if (frame != null) {
            for (var passenger : frame.scene().units()) {
                if (passenger.attachment() == null || !passenger.attachment().hostile()
                      || units.stream().anyMatch(unit -> unit.id() == passenger.id())) { continue; }
                Entity carrier = view.game.getEntity(passenger.attachment().carrierId());
                Entity removed = view.game.getEntityFromAllSources(passenger.id());
                if (view.game.getEntity(passenger.id()) == null && removed != null && (removed.isDestroyed() || removed.isDoomed())
                      && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, removed) && !sensorContact(removed)
                      && carrier != null && visible(carrier) && !sensorContact(carrier) && carrier.getSwarmAttackerId() == passenger.id()) {
                    // A removal packet can precede its carrier's path. Retain only the already authorized appearance.
                    units.add(passenger);
                }
            }
        }
        if (GpuUnitModels.ENABLED && GUIPreferences.getInstance().getShowWrecks()) {
            // BoardClientState already owns which removed units leave wrecks, including infantry/CVEP exceptions.
            var live = units.stream().map(BoardScene.Unit::id).collect(Collectors.toSet());
            view.getWrecks().stream()
                  .filter(entity -> !live.contains(entity.getId()) && visible(entity) && !sensorContact(entity))
                  .forEach(entity -> {
                      // A building drawn hex by hex leaves a wreck on each armed hex.
                      if (drawnByHex(entity)) {
                          armedHexes(entity).forEach((part, coords) -> units.add(wreck(entity, part, coords, usedImages)));
                      } else {
                          units.add(wreck(entity, -1, entity.getPosition(), usedImages));
                      }
                  });
        }
        if (GpuUnitModels.ENABLED) {
            var shown = units.stream().map(BoardScene.Unit::id).collect(Collectors.toSet());
            reserve = view.game.getEntitiesVector().stream()
                  .filter(entity -> !shown.contains(entity.getId()) && !entity.isDestroyed() && !entity.isDoomed())
                  .map(entity -> unit(entity, -1, new Coords(0, 0), false, usedImages)).toList();
        }
        JComponent panel = phasePanel.get();
        MovePath path = panel instanceof MovementDisplay movement ? movement.getPlannedMovement() : null;
        int actorId = actions.actorId();
        GameTurn activeTurn = activeTurn();
        GpuBattleStatus.Snapshot status = battleStatus.capture(view.game, view.getLocalPlayer(), activeTurn,
              actorId, this::visible, this::identified,
              entity -> unitImage(entity, -1, !identified(entity), usedImages));
        unitImages.keySet().retainAll(usedImages.keySet());
        camouflage.retain();
        List<BoardScene.Command> commands = actions.phaseCommands();
        List<BoardScene.Command> nextGlobal = new ArrayList<>(actions.globalCommands());
        if (view.getClientgui() != null) {
            var gui = view.getClientgui();
            List<BoardScene.Command> boards = gui.boardStates().stream().map(boardView -> new BoardScene.Command(
                  "Map " + boardView.getBoardId(), true, () -> SwingUtilities.invokeLater(() -> {
                      if (acceptsInput()) {
                          gui.showBoardView(boardView.getBoardId());
                          refresh();
                      }
                  }))).toList();
            nextGlobal.add(new BoardScene.Command("boards", "Maps", "", true, false, boards, () -> { }));
        }
        HoverPoint pointer = hoverPoint;
        Coords tipHex = contextCoords == null ? pointer.coords() : contextCoords;
        String nextTooltip = GpuBoardActions.plainText(view.getHexTooltip(tipHex));
        String nextDetails = tipHex != null && board.contains(tipHex)
              ? hexDetails(board.getHex(tipHex), view.getClientgui(), view.getBoardId()) : "";
        // The movement plan before the tactical capture, which leaves out what the plan draws (G4).
        Coords hover = pointer.coords();
        GpuMovePlan.Snapshot move = moves.capture(panel, hover, pointer.pointedZ());
        Point light = view.getTerrainLightDirection();
        var rulerSnapshot = los.capture();
        BoardScene scene = new BoardScene(view.getBoardId(), board.getWidth(), board.getHeight(), markedTiles(board, units), units,
              List.of(), actorId, view.game.getPhase().localizedName(), commands,
              light == null || light.x == 0 && light.y == 0 ? null : new BoardScene.Light(light.x, -light.y),
              firingLines(), view.getWeaponRanges().stream().map(range -> new BoardScene.RangeBorder(
                    range.coords(), range.borders(), TacticalStyle.rangeColor(range.bracket()).getRGB(),
                    TacticalStyle.rangeText(range.bracket()))).toList(),
              view.getBoardMarkers(), tacticalMarks.capture(view, move.planner()).withRuler(rulerSnapshot.ruler()),
              view.getRangeLabels().stream().map(label -> new BoardScene.RangeLabel(label.coords(),
                    TacticalStyle.rangeColor(label.bracket()).getRGB(),
                    TacticalStyle.rangeText(label.bracket()))).toList(), fieldOfView,
              view.electronicWarfare(), designations(), starShells());
        captureWorkGhosts(board, units);
        // The report log before the chat, whose round lines read it with the status (plan O6).
        GpuReportLog.Snapshot log = captureReports();
        chat.roundLines(status, log);
        // The other HUD panels, in this order. The fire preview follows the own focus unit, else the acting unit; the
        // fire orders show its draft read-only on another player's turn.
        int focus = checked(focusUnit, this::owned);
        int card = checked(cardUnit, this::identified);
        int ownCard = checked(ownCardUnit, this::owned);
        GpuUnitRecord.Snapshot record = unitRecord.capture(card);
        GpuHudData nextPanels = new GpuHudData(actions.phaseInfo(commands), move,
              fire.capture(panel, hover, focus), physical.capture(panel),
              record,
              preview.capture(view.getLocalPlayer(), activeTurn, focus == Entity.NONE ? actorId : focus, path,
                    this::visible, this::sensorContact),
              chat.capture(), toasts.capture(), rulerSnapshot, players.capture(),
              ownCard == card ? record : ownUnitRecord.capture(ownCard));
        if (!nextPanels.equals(panels)) {
            panels = nextPanels;
        }
        BoardScene.Context nextContext = null;
        if (contextCoords != null || contextUnitId != Entity.NONE) {
            var orders = panels.fire();
            var target = orders.active() && orders.editable() ? orders.targets().stream()
                  .filter(candidate -> candidate.key().equals(TargetKey.unit(contextUnitId))).findFirst().orElse(null) : null;
            var tools = contextSelectionCleared ? MapMenu.ContextTools.NO_SELECTION
                  : new MapMenu.ContextTools(status.myTurn() && status.phase().isMovement() && move.planner(),
                  move.mode() != GpuMovePlan.Mode.JUMP, status.myTurn() && actorId != Entity.NONE ? actorId : focus,
                  target != null && !target.primary(), target != null);
            nextContext = new BoardScene.Context(contextCoords, contextUnitId,
                  actions.contextCommands(contextCoords, contextUnitId, tools), contextSelectionCleared);
        }
        status = battleStatus.arcs(arcTarget(status, hover, panels));
        Entity actor = view.game.getEntity(actorId);
        boolean knownActor = actor != null && identified(actor);
        return new Frame(scene, List.of(), nextContext, List.copyOf(nextGlobal), nextTooltip, nextDetails,
              view.getCenterRequest(), boardGeneration, knownActor ? actor.getShortName() : "",
              atmosphereFor(view.game.getPlanetaryConditions(), board.isSpace()), log, status, panels);
    }

    /**
     * EDT: the scene's tiles: {@link #tiles} with what the marks set on the ground, which no hex stores: a dug-in
     * platoon's trench, the felled timber of woods being cleared by saws, cargo set down. The same tiles while none of
     * that changes.
     */
    private List<BoardScene.Tile> markedTiles(Board board, List<BoardScene.Unit> units) {
        List<TacticalMark> marks = view.getMarks().stream().filter(mark -> switch (mark) {
            case TacticalMark.DugIn dugIn -> dugIn.complete() && board.contains(dugIn.coords());
            case TacticalMark.SawClearing saw -> board.contains(saw.coords());
            case TacticalMark.Cargo cargo -> board.contains(cargo.coords());
            default -> false;
        }).toList();
        // A hex keeps the holes it was dug with, and gains those of any platoon that digs in beside them.
        Map<Coords, Integer> dug = new HashMap<>();
        for (TacticalMark mark : marks) {
            if (mark instanceof TacticalMark.DugIn dugIn) {
                dug.merge(dugIn.coords(), holes.getOrDefault(dugIn.coords(), 0) | slots(units, dugIn.coords()),
                      (a, b) -> a | b);
            }
        }
        boolean changed = !dug.equals(holes);
        holes = Map.copyOf(dug);
        if (tiles != markedFrom || !marks.equals(groundMarks) || changed) {
            List<BoardScene.Tile> next = new ArrayList<>(tiles);
            for (TacticalMark mark : marks) {
                Coords coords = switch (mark) {
                    case TacticalMark.DugIn dugIn -> dugIn.coords();
                    case TacticalMark.SawClearing saw -> saw.coords();
                    case TacticalMark.Cargo cargo -> cargo.coords();
                    default -> throw new IllegalStateException(mark.toString());
                };
                int index = coords.getX() * board.getHeight() + coords.getY();
                next.set(index, switch (mark) {
                    case TacticalMark.SawClearing saw -> BoardFeatures.withSawClearing(next.get(index), saw.turnsRemaining());
                    case TacticalMark.Cargo cargo -> BoardFeatures.withCargo(next.get(index));
                    default -> BoardFeatures.withTrench(next.get(index), holes.get(coords));
                });
            }
            markedTiles = marks.isEmpty() ? tiles : List.copyOf(next);
            markedFrom = tiles;
            groundMarks = marks;
        }
        return markedTiles;
    }

    /** The hexes of the board where a platoon is digging in, with the formation slots of the figures there. */
    private Map<Coords, Integer> digging(Board board, List<BoardScene.Unit> units) {
        Map<Coords, Integer> digging = new HashMap<>();
        for (TacticalMark mark : view.getMarks()) {
            if (mark instanceof TacticalMark.DugIn dugIn && !dugIn.complete() && board.contains(dugIn.coords())) {
                digging.put(dugIn.coords(), slots(units, dugIn.coords()));
            }
        }
        return digging;
    }

    /** The formation slots the figures of the troops in sight at {@code coords} stand in ({@link InfantryVisual#slots}). */
    private static int slots(List<BoardScene.Unit> units, Coords coords) {
        int slots = 0;
        for (var unit : units) {
            if (!unit.sensorContact() && UnitAnimator.troops(unit) && unit.location().coords().equals(coords)) {
                slots |= InfantryVisual.slots(unit.model());
            }
        }
        return slots;
    }

    /**
     * EDT: bridges being built, hexes being fortified, rubble being cleared and trenches being dug, as the hexes will be
     * when the work is done; rebuilt when that work or the board's tiles change.
     */
    private void captureWorkGhosts(Board board, List<BoardScene.Unit> units) {
        List<TacticalMark> work = view.getMarks().stream().filter(mark -> mark instanceof TacticalMark.BridgeBuild
              || mark instanceof TacticalMark.Fortify || mark instanceof TacticalMark.RubbleClear
              || mark instanceof TacticalMark.DugIn dugIn && !dugIn.complete()).toList();
        // A trench being dug shows the holes of the figures digging it.
        Map<Coords, Integer> digging = digging(board, units);
        if (work.equals(workMarks) && tiles == workTiles && digging.equals(workDigging)) { return; }
        workMarks = work;
        workTiles = tiles;
        workDigging = digging;
        Map<Coords, Hex> before = new LinkedHashMap<>(), done = new LinkedHashMap<>();
        for (TacticalMark mark : work) {
            var finished = finished(board, mark);
            if (finished != null) {
                before.put(finished.getKey(), board.getHex(finished.getKey()));
                done.put(finished.getKey(), finished.getValue());
            }
        }
        // A trench is no terrain: a hex being dug stays as it is, unless other work there changes it.
        for (Coords coords : digging.keySet()) {
            before.putIfAbsent(coords, board.getHex(coords));
            done.putIfAbsent(coords, board.getHex(coords));
        }
        workGhosts = GpuEditorPreview.work(before, done, (coords, hex) -> {
            BoardScene.Tile tile = terrain.preview(board, coords, hex, at -> done.getOrDefault(at, board.getHex(at)));
            return tile != null && digging.containsKey(coords) ? BoardFeatures.withTrench(tile, digging.get(coords)) : tile;
        });
    }

    /** The hex {@code work} leaves when it is done, by its coords; null where it would build nothing. */
    private static Map.Entry<Coords, Hex> finished(Board board, TacticalMark work) {
        return switch (work) {
            case TacticalMark.BridgeBuild bridge -> {
                Hex hex = BridgeConstruction.bridgedHex(board, bridge.coords(), bridge.exits(), bridge.bridgeType());
                yield hex == null ? null : Map.entry(bridge.coords(), hex);
            }
            case TacticalMark.Fortify fortify when board.contains(fortify.coords()) -> {
                Hex hex = board.getHex(fortify.coords()).duplicate();
                hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
                yield Map.entry(fortify.coords(), hex);
            }
            case TacticalMark.RubbleClear rubble when board.contains(rubble.coords()) -> {
                Hex hex = board.getHex(rubble.coords()).duplicate();
                if (!BulldozerRules.hasClearableRubble(hex)) { yield null; }
                BulldozerRules.clearRubble(hex);
                yield Map.entry(rubble.coords(), hex);
            }
            default -> null;
        };
    }

    @Override
    public GpuEditorPreview.Data workGhosts() { return workGhosts; }

    /**
     * EDT: the enemy whose arcs the Tactical View shades: the listed, identified enemy under the
     * pointer, else the focused target of the fire orders or of the physical attack, as the board overlay focuses
     * them; null for none, and while the Overlays panel's Rear arcs setting is off.
     */
    private Entity arcTarget(GpuBattleStatus.Snapshot status, Coords hover, GpuHudData shown) {
        if (!uiPreferences.overlays().rearArcs()) {
            return null;
        }
        Set<Integer> enemies = status.units().stream()
              .filter(unit -> unit.side() == GpuBattleStatus.Side.ENEMY && !unit.sensorContact())
              .map(GpuBattleStatus.UnitStatus::id).collect(Collectors.toSet());
        if (hover != null) {
            for (Entity entity : view.game.getEntitiesVector(hover, view.getBoardId())) {
                if (enemies.contains(entity.getId())) { return entity; }
            }
        }
        int focus = shown.fire().editable() ? shown.fire().focus().key().unitId()
              : shown.physical().active() ? shown.physical().targetId() : Entity.NONE;
        return enemies.contains(focus) ? view.game.getEntity(focus) : null;
    }

    /** EDT: the log of the game's reports, with the artillery rounds in the air the Rounds in the Air window lists. */
    private GpuReportLog.Snapshot captureReports() {
        return reports.capture(view.game.getAllReports(), view.game.getRoundCount(), view.game.getPhase(),
              RoundsInAirDialog.rows(view.game), this::reportIcon);
    }

    /** EDT: {@code id} while that unit exists and passes {@code check} now, else {@code Entity.NONE}. */
    private int checked(int id, Predicate<Entity> check) {
        Entity entity = view.game.getEntity(id);
        return entity != null && check.test(entity) ? id : Entity.NONE;
    }

    /** The turn the client lets the local player act in now (Client.isMyTurn/getMyTurn); null without a client. */
    private GameTurn activeTurn() {
        var client = view.getClientgui() == null ? null : view.getClientgui().getClient();
        return client != null && client.isMyTurn() ? client.getMyTurn() : null;
    }

    private Entity reportEntity(int id) {
        Entity entity = view.game.getEntityFromAllSources(id);
        return entity != null && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity)
              && !sensorContact(entity) ? entity : null;
    }

    private BoardScene.Pixels reportIcon(int id) {
        Entity entity = reportEntity(id);
        if (entity == null) {
            return null;
        }
        Image image = view.getTilesetManager().textureFor(entity, -1);
        return image == null ? null : BoardScene.Pixels.copy(image);
    }

    /** Raise the Legacy 2D unit overview's SELECT_UNIT event; the phase display decides what selecting means. */
    void selectUnit(int id) {
        SwingUtilities.invokeLater(() -> {
            Entity entity = acceptsInput() ? view.game.getEntity(id) : null;
            if (entity != null && identified(entity)) {
                view.processBoardViewEvent(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, id));
                refresh();
            }
        });
    }

    /** Centre on an own or visible unit as the Legacy 2D unit overview does; an own unit's other board is shown. */
    void locateUnit(int id) {
        SwingUtilities.invokeLater(() -> {
            Entity entity = acceptsInput() ? view.game.getEntity(id) : null;
            if (entity != null && (owned(entity) || visible(entity))) {
                if (view.getClientgui() != null) {
                    view.getClientgui().centerOnUnit(entity);
                } else {
                    centerOnBoard(entity);
                }
                refresh();
            }
        });
    }

    private void centerOnBoard(Entity entity) {
        if (entity.isDeployed() && !entity.isOffBoard() && entity.getPosition() != null
              && entity.getBoardId() == view.getBoardId()) {
            view.centerOn(entity);
        }
    }

    boolean owned(Entity entity) {
        return view.getLocalPlayer() != null && entity.getOwnerId() == view.getLocalPlayer().getId();
    }

    /** Own units, and visible units that are not anonymous sensor contacts, may be named and selected. */
    boolean identified(Entity entity) {
        return owned(entity) || visible(entity) && !sensorContact(entity);
    }

    boolean visible(Entity entity) {
        return entity.getPosition() != null && entity.getBoardId() == view.getBoardId()
              && EntityVisibilityUtils.detectedOrHasVisual(view.getLocalPlayer(), view.game, entity);
    }

    /** The carrier owns exterior occupancy, so separately delivered passenger updates cannot release it early. */
    static Map<Integer, BoardScene.Attachment> exteriorAttachments(List<Entity> entities,
          java.util.function.Predicate<Entity> identified, java.util.function.Predicate<Entity> onBoard) {
        Map<Integer, BoardScene.Attachment> result = new HashMap<>();
        Map<Integer, Entity> byId = new HashMap<>();
        entities.forEach(entity -> byId.put(entity.getId(), entity));
        for (Entity carrier : entities) {
            if (!onBoard.test(carrier) || !identified.test(carrier)) { continue; }
            for (Entity passenger : carrier.getExternalUnits()) {
                if (passenger instanceof megamek.common.battleArmor.BattleArmor && identified.test(passenger)) {
                    result.put(passenger.getId(), new BoardScene.Attachment(carrier.getId(), false));
                }
            }
            Entity swarmer = byId.get(carrier.getSwarmAttackerId());
            if (swarmer instanceof megamek.common.units.Infantry && identified.test(swarmer)) {
                result.put(swarmer.getId(), new BoardScene.Attachment(carrier.getId(), true));
            }
        }
        return result;
    }

    private List<BoardScene.FiringLine> firingLines() {
        List<BoardScene.FiringLine> result = new ArrayList<>();
        for (AttackAction action : view.getAttacks()) {
            Entity weaponEntity = view.game.getEntity(action.getEntityId());
            Entity attacker = weaponEntity == null ? null : weaponEntity.getAttackingEntity();
            Targetable target = view.game.getTarget(action.getTargetType(), action.getTargetId());
            if (attacker == null || target == null || attacker.getPosition() == null || target.getPosition() == null
                  || !board.contains(attacker.getPosition()) || !board.contains(target.getPosition())) {
                continue;
            }
            // ArtilleryAttackAction also represents direct artillery declared in the firing phase.
            boolean indirect = action instanceof ArtilleryAttackAction && switch (view.game.getPhase()) {
                case TARGETING, TARGETING_REPORT, OFFBOARD, OFFBOARD_REPORT -> true;
                default -> false;
            };
            if (action instanceof WeaponAttackAction weaponAttack) {
                var weapon = weaponEntity.getEquipment(weaponAttack.getWeaponId());
                indirect |= weapon != null && weapon.curMode().isIndirect();
            }
            result.add(new BoardScene.FiringLine(firingEndpoint(attacker), firingEndpoint(target),
                  attacker.getOwner().getColour().getColour().getRGB(), indirect, attacker.getId(),
                  TargetKey.of(target)));
        }
        // Multiple weapons on one target share a trace, but direct and indirect fire remain distinct.
        return result.stream().distinct().toList();
    }

    /**
     * The TAGs that hit this round, while semi-guided and homing rounds can still use them: from the Off-board phase,
     * when TAG fires, to the end of the Firing phase and its report. Both units must be on this board and seen.
     */
    private List<BoardScene.Designation> designations() {
        GamePhase phase = view.game.getPhase();
        boolean isOffboard = phase.isOffboard() || phase.isOffboardReport();
        boolean isFiring = phase.isFiring() || phase.isFiringReport();
        if (!isOffboard && !isFiring) {
            return List.of();
        }
        List<BoardScene.Designation> result = new ArrayList<>();
        for (TagInfo tag : view.game.getTagInfo()) {
            boolean isUnitHit = !tag.missed && (tag.targetType == Targetable.TYPE_ENTITY) && (tag.target != null);
            Entity designator = isUnitHit ? view.game.getEntity(tag.attackerId) : null;
            Entity target = isUnitHit ? view.game.getEntity(tag.target.getId()) : null;
            if (isShownDesignating(designator) && isShownDesignating(target)) {
                result.add(new BoardScene.Designation(designator.getId(), firingEndpoint(designator), target.getId(),
                      firingEndpoint(target)));
            }
        }
        if (result.size() != lastDesignations) {
            lastDesignations = result.size();
            LOGGER.debug("[TAG] {} designator lines shown in {}", result.size(), phase);
        }
        return result;
    }

    /** Whether a unit in a designation is on this board, placed, and identified by the local player. */
    private boolean isShownDesignating(@Nullable Entity unit) {
        return (unit != null) && (unit.getPosition() != null) && (unit.getBoardId() == view.getBoardId())
              && board.contains(unit.getPosition()) && identified(unit);
    }

    /** The illumination rounds' flares over this board, burning or about to. */
    private List<BoardScene.StarShell> starShells() {
        List<BoardScene.StarShell> result = new ArrayList<>();
        for (Flare flare : view.game.getFlares()) {
            boolean onThisBoard = (flare.getBoardId() == view.getBoardId()) && board.contains(flare.position);
            if (onThisBoard) {
                result.add(new BoardScene.StarShell(flare.position, flare.radius, flare.turnsToBurn, flare.isIgnited()));
            }
        }
        return result;
    }

    private BoardScene.Waypoint firingEndpoint(Targetable target) {
        Coords coords = target.getPosition();
        if (target instanceof Entity entity) {
            if (sensorContact(entity)) {
                return waypoint(coords, 0.5f, 0);
            }
            var position = unitWaypoint(entity, coords, 0);
            return new BoardScene.Waypoint(coords, position.elevation() + (entity.height() + 1) * 0.5f, 0);
        }
        return waypoint(coords, target.getElevation() + Math.max(0.15f, target.getHeight() * 0.5f), 0);
    }

    boolean sensorContact(Entity entity) {
        return EntityVisibilityUtils.onlyDetectedBySensors(view.getLocalPlayer(), entity);
    }

    private BoardScene.Unit wreck(Entity entity, int part, Coords coords, Map<Image, Boolean> usedImages) {
        var captured = unit(entity, part, coords, false, usedImages);
        var model = captured.model();
        var state = model == null ? UnitModelState.capture(entity, null, part) : model.state();
        var pose = state.pose();
        var dead = new UnitModelState(state.structure(), state.appearance(),
              new UnitModelState.Pose(pose.proneCause(), pose.facing(), pose.secondaryFacing(), pose.form(), true,
                    pose.armsFlipped(), pose.hullDown(), pose.canHullDown()));
        model = model == null ? new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, dead)
              : new BoardScene.UnitModel(model.asset(), model.fallback(), model.variant(), model.figures(), model.twist(), model.damage(), dead);
        // The 3D board has no wreck artwork: the unit keeps its own artwork and mesh, fallen and fully damaged.
        return new BoardScene.Unit(captured.id(), part, captured.name(), captured.location(), captured.image(), false, null,
              captured.height(), captured.airborne(), model, captured.outlineRgb(), captured.footprint(), null, captured.heat());
    }

    /** One copy per AWT image, shared by identity by every frame and HUD list that shows it while it is in use. */
    private BoardScene.Pixels retained(Image image, Map<Image, Boolean> usedImages) {
        usedImages.put(image, true);
        return unitImages.computeIfAbsent(image, BoardScene.Pixels::copy);
    }

    /** The tileset artwork of a unit or part, or the radar blip that stands for a sensor contact. */
    private BoardScene.Pixels unitImage(Entity entity, int part, boolean sensor, Map<Image, Boolean> usedImages) {
        var tileset = view.getTilesetManager();
        return retained(sensor ? view.getRadarBlipImage()
              : part < 0 ? tileset.wholeTextureFor(entity) : tileset.textureFor(entity, part), usedImages);
    }

    /**
     * Whether an armed building is drawn hex by hex: when it covers several hexes and one of them holds weapons. Each
     * hex holding weapons is then a part of its own, at its own roof, with the size of a one-hex turret, and the
     * building is never drawn whole.
     */
    private static boolean drawnByHex(Entity entity) {
        return entity instanceof AbstractBuildingEntity building && UnitModelEquipment.armedStructure(building)
              && building.getSecondaryPositions().size() > 1 && !armedHexes(building).isEmpty();
    }

    /** The parts of a building drawn hex by hex: its hexes that hold weapons, in part order. */
    private static TreeMap<Integer, Coords> armedHexes(Entity entity) {
        var building = (AbstractBuildingEntity) entity;
        var armed = new TreeMap<Integer, Coords>();
        building.getSecondaryPositions().forEach((part, coords) -> {
            if (!building.getWeaponsAt(coords).isEmpty()) { armed.put(part, coords); }
        });
        return armed;
    }

    /** The armed part standing on {@code hex}, or -1. */
    private static int armedPart(Map<Integer, Coords> armed, Coords hex) {
        return armed.entrySet().stream().filter(entry -> entry.getValue().equals(hex)).map(Map.Entry::getKey)
              .findFirst().orElse(-1);
    }

    /**
     * The part of a building drawn hex by hex that makes an attack: the hex holding its weapon; its first armed hex
     * stands for it in its death. -1 for a unit drawn whole.
     */
    private static int firingPart(Entity entity, int equipment) {
        if (!drawnByHex(entity)) {
            return -1;
        }
        var armed = armedHexes(entity);
        var mounted = equipment < 0 ? null : entity.getEquipment(equipment);
        int part = mounted == null ? -1
              : armedPart(armed, ((AbstractBuildingEntity) entity).getLocationCoords(mounted.getLocation()));
        return part < 0 ? armed.firstKey() : part;
    }

    private BoardScene.Unit unit(Entity entity, int part, Coords coords, boolean sensor,
          Map<Image, Boolean> usedImages) {
        BoardScene.Pixels pixels = unitImage(entity, part, sensor, usedImages);
        int facing = sensor ? 0 : view.getTilesetManager().facingFor(entity);
        boolean airborne = !sensor && airborne(entity);
        List<Coords> footprint = !sensor && part < 0 ? entity.getOccupiedCoords().stream()
              .sorted(java.util.Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).toList() : List.of(coords);
        if (footprint.isEmpty()) {
            footprint = List.of(coords);
        }
        // An armed building's turret stands on its roof; its wall casemates hang from there (GpuUnitModel.walls).
        int elevation = !sensor && entity instanceof AbstractBuildingEntity building
              && UnitModelEquipment.armedStructure(building)
              ? entity.getElevation() + building.getHeight(coords) : entity.getElevation();
        BoardScene.Waypoint location = airborne
              ? unitWaypoint(entity, coords, facing)
              : footprint.size() > 1 && UnitFootprint.terrainSupported(entity.getMovementMode())
                    ? new BoardScene.Waypoint(coords, UnitFootprint.support(board, coords, footprint, elevation), facing)
                    : waypoint(coords, sensor ? 0 : elevation, facing);
        if (!sensor) {
            location = location.withAeroState(aeroState(entity, entity.getElevation(), airborne))
                  .withProneCause(proneCause(entity))
                  .withFallSide(entity instanceof Mek ? entity.getFallSide() : null).withHullDown(entity.isHullDown());
            if (location.aeroState() != null) {
                location = location.withFootprint(footprint);
            }
        }
        Color outline = sensor ? new Color(GpuMarkers.SENSOR_RGB)
              : GUIPreferences.getInstance().getTeamColoring()
                    && UnitOwnerColors.isViewerOnASide(view.getLocalPlayer(), view.getGame())
                    ? UIUtil.teamColor(entity.getOwner(), view.getLocalPlayer())
                    : entity.getOwner().getDisplayColour().getColour(false);
        // No unit annotations: the HUD's nameplates are the units' labels (X3).
        return new BoardScene.Unit(entity.getId(), part, sensor ? Messages.getString("BoardView1.sensorReturn")
              : entity.getShortName(),
              location, pixels, sensor,
              null,
              sensor ? 1 : entity.height() + 1, airborne,
              GpuUnitModels.ENABLED
                    ? camouflage.resolve(UnitModelSelection.capture(entity, part, sensor, MMStaticDirectoryManager.getMekTileset(),
                          sensor ? 0 : UnitModelSelection.twist(entity.getFacing(), facing))) : null,
              outline.getRGB(), footprint, null, sensor || !entity.tracksHeat() ? -1 : Math.max(0, entity.heat));
    }

    private BoardScene.Waypoint waypoint(Coords coords, float relativeElevation, int facing) {
        Hex hex = board == null ? null : board.getHex(coords);
        return new BoardScene.Waypoint(coords, relativeElevation + (hex == null ? 0 : hex.getLevel()), facing);
    }

    /**
     * True while the unit is flying. Aerospace units carry an altitude; a parked aerodyne keeps its AERODYNE movement
     * mode, so {@code isAirborne()} alone would call it airborne, but the altitude decides. VTOLs and WiGEs hover on
     * their hex-relative elevation and are landed at elevation 0.
     */
    private static boolean airborne(Entity entity) {
        return entity.getAltitude() > 0 || entity.isAirborneVTOLorWIGE();
    }

    /**
     * Capture the current unit through the same elevation conversion as historical and planned movement.
     */
    private BoardScene.Waypoint unitWaypoint(Entity entity, Coords coords, int facing) {
        return unitWaypoint(entity, coords, entity.getElevation(), facing, entity.getAltitude(), UnitLocation.Form.capture(entity));
    }

    /**
     * Single conversion for current units, playback, planned movement and firing. An aerospace reports the airborne
     * sentinel (999) while flying instead of a real level; those steps play at the flight altitude above their hex.
     * A step carrying a real elevation (an aerospace taxiing, or set down on its landing hex) keeps it. Every other unit
     * keeps its own per-step elevation, so VTOL and WiGE climbs and descents still animate.
     * Published waypoint elevations are absolute render levels. Renderers must not add terrain height again;
     * this visual conversion does not change the rules engine's altitude or LOS calculations.
     */
    private BoardScene.Waypoint unitWaypoint(Entity entity, Coords coords, float elevation, int facing,
          int flightAltitude, UnitLocation.Form form) {
        boolean aero = form == null ? entity.isAero() : form.aero();
        if (form != null && form.altitude() > 0) { flightAltitude = form.altitude(); }
        BoardScene.Waypoint point = aero && elevation >= Aero.AERO_EFFECTIVE_ELEVATION && flightAltitude > 0
              ? waypoint(coords, flightAltitude, facing)
              : waypoint(coords, elevation, facing);
        // A real path elevation describes the displayed step; the final Entity may already have landed/taken off.
        return point.withAeroState(aeroState(aero, elevation, false)).withForm(form);
    }

    private BoardScene.AeroState aeroState(Entity entity, float relativeElevation, boolean flying) {
        return aeroState(entity.isAero(), relativeElevation, flying);
    }

    private BoardScene.AeroState aeroState(boolean aero, float relativeElevation, boolean flying) {
        if (!aero) {
            return null;
        }
        if (board.isSpace() || flying || relativeElevation >= Aero.AERO_EFFECTIVE_ELEVATION) {
            return BoardScene.AeroState.AIRBORNE;
        }
        return relativeElevation == 0 ? BoardScene.AeroState.LANDED : BoardScene.AeroState.ELEVATED;
    }

    /** One waiting {@link #ask}: its nested Swing loop keeps the caller's frame on the stack until it is answered. */
    private final class PendingDialog {
        private final GpuBoardWindow.DialogRequest request;
        private final SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        private GpuBoardWindow.DialogAnswer answer;

        private PendingDialog(GpuBoardWindow.DialogRequest request) {
            this.request = request;
        }

        /**
         * EDT: the first answer wins and the native window stops showing it at once. The loop ends when the event that
         * completes it returns, and its caller resumes only after every newer dialog's caller has returned.
         */
        private void complete(GpuBoardWindow.DialogAnswer result) {
            if (answer == null) {
                answer = result;
                loop.exit();
                publishDialog();
            }
        }
    }

    /**
     * EDT: publishes the request to the native window and pumps Swing events in a nested loop until the GL thread
     * answers it or this source closes, as a modal JDialog waits. The EDT never waits on the GL thread. Returns null
     * when this source is already closed, so the caller can use its Swing dialog.
     */
    GpuBoardWindow.DialogAnswer ask(GpuBoardWindow.DialogRequest request) {
        requireSwingThread();
        if (Thread.holdsLock(this)) {
            // The GL thread takes this monitor in takeFrame(); it could never show the dialog to be answered.
            throw new IllegalStateException("A native dialog must not wait while holding the board source monitor");
        }
        if (closed) {
            return null;
        }
        PendingDialog pending = new PendingDialog(request.withId(++dialogSequence));
        pendingDialogs.push(pending);
        publishDialog();
        try {
            pending.loop.enter();
        } finally {
            pendingDialogs.remove(pending);
            publishDialog();
        }
        return pending.answer == null ? GpuBoardWindow.DialogAnswer.cancelled(request) : pending.answer;
    }

    /** EDT: shows the newest dialog that still waits for an answer; an answered outer one is never shown again. */
    private void publishDialog() {
        dialog = pendingDialogs.stream().filter(pending -> pending.answer == null).map(pending -> pending.request)
              .findFirst().orElse(null);
    }

    /** GL thread: never blocks. The answer completes its dialog on the EDT, inside that dialog's nested loop. */
    void answer(long dialogId, GpuBoardWindow.DialogAnswer answer) {
        Objects.requireNonNull(answer);
        SwingUtilities.invokeLater(() -> pendingDialogs.stream().filter(pending -> pending.request.id() == dialogId)
              .findFirst().ifPresent(pending -> pending.complete(answer)));
    }

    /** GL thread: the newest pending dialog, or null. A volatile read without any monitor. */
    GpuBoardWindow.DialogRequest dialog() {
        return dialog;
    }

    /**
     * EDT guard at the start of every command from the native window: false once closed, while any native ask of
     * this source is still on the call stack, or while a Swing modal dialog is shown. Input queued before a modal
     * appeared is therefore dropped instead of running inside its nested loop. Non-modal windows (Help, the
     * accessibility window) do not block it; keys additionally respect {@code shouldIgnoreHotKeys()}.
     */
    boolean acceptsInput() {
        return !closed && !view.isClosed() && pendingDialogs.isEmpty() && !UIUtil.isModalDialogDisplayed();
    }

    /**
     * GL thread: posts a HUD service command to the EDT. There it runs only while {@link #acceptsInput()} holds, so a
     * command queued before a dialog appeared is dropped; after it runs, the frame is republished.
     */
    void command(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput()) {
                action.run();
                refresh();
            }
        });
    }

    /** GL thread: the unit on the unit card and record sheet. */
    void setCardUnit(int id) {
        cardUnit = id;
    }

    /**
     * GL thread: leaves the game as closing the window does. The client asks whether to save first and stays in the
     * game when the player cancels.
     */
    void quitGame() {
        command(() -> {
            ClientGUI gui = view.getClientgui();
            if (gui != null) {
                gui.handleExit();
            }
        });
    }

    /** GL thread: the own unit whose glance card stays beside an inspection. */
    void setOwnCardUnit(int id) {
        ownCardUnit = id;
    }

    /** GL thread: the own focus unit, which the fire preview follows and whose draft the fire orders show (H33). */
    void setFocusUnit(int id) {
        focusUnit = id;
    }

    GpuMovePlan moves() {
        return moves;
    }

    GpuFireOrders fire() {
        return fire;
    }

    GpuPhysicalOptions physical() {
        return physical;
    }

    GpuUnitRecord record() {
        return unitRecord;
    }

    GpuChat chat() {
        return chat;
    }

    GpuToasts toasts() {
        return toasts;
    }

    GpuLosResult los() {
        return los;
    }

    GpuPlayers players() {
        return players;
    }

    public void inspect(Coords coords) {
        inspect(coords, Entity.NONE);
    }

    public void inspect(Coords coords, int unitId) {
        inspect(coords, unitId, false);
    }

    public void inspect(Coords coords, int unitId, boolean selectionCleared) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput()) {
                contextCoords = coords;
                contextUnitId = unitId;
                contextSelectionCleared = selectionCleared;
                refresh();
            }
        });
    }

    public void key(int keyCode, boolean down, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && view.getClientgui() != null && !view.getClientgui().shouldIgnoreHotKeys()) {
                KeyEvent event = new KeyEvent(view.getClientgui().getFrame(),
                      down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers,
                      keyCode, KeyEvent.CHAR_UNDEFINED);
                var controller = view.getClientgui().controller;
                boolean handled = controller != null && controller.dispatchKeyEvent(event);
                // The HUD's chat field takes the typed text; a key no binding handled may be a menu accelerator.
                if (down && !handled) {
                    actions.menuShortcut(KeyStroke.getKeyStrokeForEvent(event));
                }
                refresh();
            }
        });
    }

    public void stopKeys() {
        SwingUtilities.invokeLater(() -> {
            var gui = view.getClientgui();
            if (gui != null && gui.controller != null) {
                gui.controller.stopAllRepeating();
            }
        });
    }

    /** Alt-click opens the native LOS ruler, independently of the active phase tool. */
    /**
     * A hex's lines of MegaMek's hex tooltip below its terrain ({@link HexTooltip#getHexTip}): its building, bridge or
     * fuel tank with height and CF, minefields, objects on the ground, clearing and bridge-building work; one a line.
     */
    static String hexDetails(Hex hex, ClientGUI gui, int boardId) {
        return GpuBoardActions.plainText(HexTooltip.getHexTip(hex, gui == null ? null : gui.getClient(), boardId))
              .lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
    }

    static boolean isMeasurement(int modifiers) {
        return (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
    }

    public void click(Coords coords, boolean doubleClick, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput()) {
                clickNow(coords, doubleClick, modifiers);
                refresh();
            }
        });
    }

    /** EDT: the board click {@link #click} posts, run now, as a HUD command runs it inside its own guard. */
    void clickNow(Coords coords, boolean doubleClick, int modifiers) {
        if (isCurrentView(view) && coords != null && (view.game.getPhase().isOnMap() || isMeasurement(modifiers))) {
            if (isMeasurement(modifiers)) { los.model().addPoint(coords, null); }
            else {
                view.mouseAction(coords, doubleClick ? BoardClientState.BOARD_HEX_DOUBLE_CLICK
                      : BoardClientState.BOARD_HEX_CLICK, modifiers & ~InputEvent.CTRL_DOWN_MASK, 1);
            }
        }
    }

    @Override
    public void measure(Coords coords, int modifiers, float pointedZ) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && isCurrentView(view) && coords != null && view.getBoard().contains(coords)) {
                if (!isMeasurement(modifiers) && los.capture().pending() == 0) { return; }
                los.model().addPoint(coords, Float.isNaN(pointedZ) ? null
                      : GpuLosResult.pointedHeight(view.getBoard().getHex(coords), pointedZ));
                refresh();
            }
        });
    }

    @Override
    public void changeRuler(long generation, java.util.function.Consumer<RulerModel> action) {
        command(() -> {
            if (generation == boardGeneration && isCurrentView(view)) { action.accept(los.model()); }
        });
    }

    public void hover(Coords coords, int modifiers) {
        SwingUtilities.invokeLater(() -> {
            if (acceptsInput() && coords != null && !isMeasurement(modifiers) && view.game.getPhase().isOnMap()) {
                view.mouseAction(coords, BoardClientState.BOARD_HEX_DRAG, modifiers | InputEvent.BUTTON1_DOWN_MASK, 1);
            }
        });
    }

    public UiPreferences uiPreferences() { return uiPreferences; }
    public GpuAtmosphereControls atmosphere() { return atmosphere; }

    public boolean isGameplay() { return true; }

    @Override
    public List<BoardScene.Unit> reserveUnits() { return reserve; }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        if (closed) { return; }
        closed = true;
        // A closing window always releases the EDT: every pending ask returns the cancelled answer.
        pendingDialogs.forEach(pending -> pending.complete(GpuBoardWindow.DialogAnswer.cancelled(pending.request)));
        timer.stop();
        view.setMovingUnits(false);
        view.setVisibleArea(() -> new double[] { 0, 0, 1, 1 });
        view.game.removeGameListener(gameListener);
        moves.close();
        fire.close();
        preview.close();
        chat.close();
        players.close();
        PreferenceManager.getClientPreferences().removePreferenceChangeListener(preferenceListener);
        GUIPreferences.getInstance().removePreferenceChangeListener(preferenceListener);
        if (board != null) {
            board.removeBoardListener(boardListener);
        }
        unitImages.clear();
        terrainImages.clear();
        camouflage.clear();
        tiles = List.of();
        terrain.close();
        synchronized (this) {
            pendingEvents.clear();
            deaths.clear();
            captures.clear();
        }
    }
    private long animationsQueued;
    private long animationsTaken;
    private long reportedAnimationSerial = -1;
    private long reportedGeneration = -1;
    private boolean reportedBusy;

    /** Render-thread report about the timeline consumed with this frame; never a second animation clock. */
    @Override
    public void playbackState(Frame consumed, boolean busy) {
        long serial = animationsTaken;
        if (reportedGeneration == consumed.boardGeneration() && reportedAnimationSerial == serial && reportedBusy == busy) {
            return;
        }
        reportedGeneration = consumed.boardGeneration();
        reportedAnimationSerial = serial;
        reportedBusy = busy;
        SwingUtilities.invokeLater(() -> {
            if (!isCurrentView(view) || consumed.boardGeneration() != boardGeneration) { return; }
            // A later packet may already have queued movement that this GL frame has not consumed.
            if (busy || serial == animationsQueued) { view.setMovingUnits(busy); }
        });
    }

}
