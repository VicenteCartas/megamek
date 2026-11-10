/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.ConvexHull;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.OrientedBoundingBox;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiCursorCapture;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;

/** GPU board and Scene2D controls. The source remains the sole bridge to the existing client. */
class GpuBattleView extends ApplicationAdapter {
    /** Airborne units hover: one full wave cycle lasts this long. */
    static final float HOVER_PERIOD_SECONDS = UnitAnimator.HOVER_PERIOD_SECONDS;
    /** Height of that wave in terrain levels, so a floating token drifts off its flight height. */
    static final float HOVER_LEVELS = UnitAnimator.HOVER_LEVELS;
    /** Hover and editor-brush outline inset as a fraction of the hex radius: 0 is the border, .1 keeps 90%. */
    static final float HOVER_HEX_INSET = .1f;
    /** Hidden hover and editor-brush outlines remain visible through terrain and objects at half opacity. */
    static final float HOVER_OCCLUDED_ALPHA = .5f;
    /** Hide all declared firing arrows while combat is playing, regardless of selection. */
    static final boolean HIDE_TARGET_ARROWS_DURING_ATTACKS = true;
    /** Slow motion as a shot that hits comes in, just before its impact. */
    static final boolean BULLET_TIME = false;
    /** Slow the lead-in to a ranged death; infantry dies with its last trooper. */
    static final boolean DRAMATIC_DEATH = true;
    /** unit's screen rectangle is .55 of a hex wide. */
    private static final float UNIT_RECT_WIDTH = .55f;
    /** Floating units are tied to their hex with this faint solid stem; solid, so it needs no blending state. */
    private static final Color TETHER_COLOR = Color.valueOf("A9B8B8");
    /** How near the board area's edge (unscaled window pixels) a route's drag scrolls the board ({@link #edgePan}). */
    private static final float EDGE_PAN_MARGIN = 48;
    private static final float FLIGHT_HEXES_PER_SECOND = 1.5f;
    /** The zoom factor of one ZOOM_IN or ZOOM_OUT bind; the Menu's View zoom items use it as well. */
    static final float ZOOM_STEP = 1.2f;
    /** Degrees a middle drag orbits the camera per layout unit, on the board and on the minimap (C.4). */
    static final float ORBIT_DEGREES = BoardCameraControls.ORBIT_DEGREES;
    /** The editor's rotation handle on screen, times the layout scale: its line's length and its circle's radius. */
    private static final float HANDLE_LENGTH = 28, HANDLE_RADIUS = 7;
    private static final MMLogger LOGGER = MMLogger.create(GpuBattleView.class);
    void zoomEditor(int direction) { boardCamera.zoom(direction < 0 ? 1 / 1.2f : 1.2f); }

    private BoardSource source;
    private Stage loadingStage;
    private GpuBoardSkin loadingTheme;
    private GpuLoadingPanel loadingPanel;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final GpuDisplayScale displayScale = new GpuDisplayScale();
    final BoardCamera boardCamera = new BoardCamera();
    private final UnitPlayback playback = new UnitPlayback(this::completeMovement);
    /** A historical review may finish without handing camera control to the live turn's next unit. */
    private boolean cameraReviewing;
    /** The round's playback history (A.10): it feeds and reviews the playback; the HUD steers it. */
    private final GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
    private final BoardSurface.Cache groundSurfaces = new BoardSurface.Cache();
    private final Map<Integer, UnitMotion> motions = playback.motions;
    private final GpuAttackEffects attackEffects = new GpuAttackEffects();
    private final GpuDesignators designators = new GpuDesignators();
    /** A frame slower than this is logged with what it drew, so a playtest log shows what a stutter came from. */
    private static final long SLOW_FRAME_MILLIS = 40;
    private List<BoardScene.Tile> lastFrameTiles;
    /** The editor's tiles in the frame last taken: a change of them that brings no events shows at once (renderFrame). */
    private List<BoardScene.Tile> editorTiles;
    private boolean tilesChangedThisFrame;
    private final GpuStarShells starShells = new GpuStarShells();
    private final GpuTerrainEffects terrainEffects = new GpuTerrainEffects();
    private final GpuEffectDepth effectDepth = new GpuEffectDepth();
    /** The opaque scene's ambient occlusion, between the depth pre-pass and the opaque colour (Display: Ambient occlusion). */
    private final GpuAmbientOcclusion ambientOcclusion = new GpuAmbientOcclusion();
    private final GpuSkyVisibility skyVisibility = new GpuSkyVisibility();
    private final GpuWaterImpacts waterImpacts = new GpuWaterImpacts();
    private record UnitSprite(BoardScene.Pixels pixels, boolean meeple, boolean icon) { }
    private final Map<UnitSprite, GpuUnitModel> spriteModels = new HashMap<>();
    /**
     * The hits last played against a unit: their push and latest landed attack, at {@link #hoverClock}. Its dying
     * meeples fly that way; infantry figures react to that attack's weapon and damage per hit.
     */
    record Hit(Vector3 push, float clock, UnitAttack cause) { }
    /** A hit is forgotten this long after it played: a later death without one bursts out evenly. */
    private static final float HIT_MEMORY_SECONDS = 10;
    /** The damage a nuclear blast stands for: more than any meeple's pieces can take. */
    private static final float NUCLEAR_DAMAGE = 200;
    private final Map<Integer, Hit> hits = new HashMap<>();
    private final MeepleDebris.Ground debrisGround = this::debrisHeight;
    /**
     * What a collapse's pieces land on (GpuTerrain#advanceCollapses): the board as it stands, which no longer holds their
     * structure. Within the pile's own reach they look past a deck or building standing above their foot, as an authored
     * building's standing shares do, which keep the tower's walls their cut gave them; beyond it they land on the
     * neighbours they fall onto.
     */
    private final MeepleDebris.Ground collapseGround = (x, y, from, own) -> debrisHeight(x, y, from, own, own);
    /** The units seen dead, in either camera: a meeple explodes only as its unit first shows dead. */
    private final Set<String> deadUnits = new HashSet<>();
    /** This frame's ghosts of exploded meeples ({@link #ghost}): the instances placed for them, by how much shows. */
    private final Map<ModelInstance, Float> unitGhosts = new java.util.IdentityHashMap<>();
    /** Battle seconds over which a ghost fades in and out. */
    private static final float GHOST_FADE_SECONDS = .25f;
    private final GpuUnitModels unitModels = GpuUnitModels.ENABLED ? new GpuUnitModels() : null;
    private final UnitDamageDisplay damageDisplay = new UnitDamageDisplay();
    private final MeepleCasualties casualties = new MeepleCasualties(damageDisplay);
    private final GpuJumpJets jumpJets = new GpuJumpJets();
    private final GpuPilotFlames pilotFlames = new GpuPilotFlames();
    private final GpuUnitCamouflage camouflage = new GpuUnitCamouflage();
    /** The Tactical View's unit bases and badges; created with the HUD's skin, whose icons and font they borrow. */
    private GpuUnitBases unitBases;
    private final Map<BoardScene.Unit, Vector3> unitAnchors = new HashMap<>();
    private final Map<BoardScene.Unit, UnitFootprint.Pose> unitFootprints = new HashMap<>();
    private final Map<String, ModelInstance> unitInstances = new HashMap<>();
    /** The instance the view shows for a scene unit: its 3D model or meeple. */
    private final Function<BoardScene.Unit, ModelInstance> shownUnit =
          unit -> unitInstances.get(unit.id() + ":" + unit.part());
    private final UnitBounds.Frame unitBounds = new UnitBounds.Frame();
    private final UnitPicking unitPicking = new UnitPicking();
    private final Map<String, UnitModelState.Appearance> equipmentAppearance = new HashMap<>();
    private final Map<String, BoardScene.Pixels> unitTints = new HashMap<>();
    private final Map<String, UpperBodyTurn> upperBodyTurns = new HashMap<>();
    private final Map<String, ArmFlip> armFlips = new HashMap<>();
    private final Map<String, UnitAnimator> animators = new HashMap<>();
    /** The ground of the units other than troops, as last drawn, by the hexes they occupy; troops step around it. */
    private final Map<Coords, List<Polygon>> occupiedGround = new HashMap<>();
    private final HexCrowds crowds = new HexCrowds();
    private final Map<String, BoardScene.LocationDamage> unitDamage = new HashMap<>();
    private final Map<Integer, KeyCommandBind> cameraKeys = new HashMap<>();
    private final BoardInput boardInput = new BoardInput();
    /** The loading stage's or the HUD's input, which a modal Swing dialog holds back (setInputBlocked). */
    private InputProcessor installedInput;
    private boolean inputBlocked;
    final FullscreenKey fullscreenKey = new FullscreenKey();
    /** The window's bounds before the fullscreen key made it fullscreen; the key's next press restores them. */
    private GpuWindowBounds windowed;
    /** Render-thread snapshot of modifiers delivered to the separate Swing tools window. */
    private final List<Hover> hover = new ArrayList<>();
    private GpuTerrain terrain;
    private GpuFireControl fireControl;
    /** The board overlay: envelopes, the route and its ghost, rings and glows, bands and arcs. */
    private GpuBoardOverlay overlay;
    private GpuTactical tactical;
    private final GpuFieldOfView fieldOfView;
    private GpuAtmosphere atmosphere;
    private GpuUnitVisibility unitVisibility;
    private GpuWireframe wireframe;
    private GpuMarkers markers;
    private GpuEditorBadges editorBadges;
    private GpuTextures<BoardScene.Pixels> unitTextures;
    private ModelBatch unitBatch;
    private SpriteBatch annotationBatch;
    /**
     * libGDX's sprite program, shared by the view's sprite batches (each would compile its own copy). They do not own
     * it: the view disposes it after them.
     */
    private ShaderProgram sprites;
    /** libGDX's shape program, shared in the same way by the view's lines, the board overlay and the ruler. */
    private ShaderProgram shapes;
    private ShapeRenderer lines;
    private ShapeRenderer tethers;
    private ShaderProgram tetherShader;
    /** The HUD; its skin also holds the hex labels' font. */
    private GpuBoardHud ui;
    /** The battle's own cards over the HUD, such as the Game Master's damage card; made when first shown. */
    private LobbyNativeDialogs cards;
    private GpuBoardSkin theme;
    /** The presentation settings the board reads; the HUD's developer tuning panel edits them. */
    private GpuBoardTuning tuning;
    private BoardScene scene;
    /** The live scene's tiles, which the played events lead to: a falling hex's labels come from them (GpuHexText). */
    private List<BoardScene.Tile> comingTiles = List.of();
    private GpuHexText hexText;
    private GpuHexGrid hexGrid;
    private GpuBoardPrint boardPrint;
    /** The board editor's reference image over its top view. */
    private GpuTraceOverlay traceOverlay;
    /** The board editor's Paint and Erase preview at the pointer. */
    private GpuEditorPreview editorPreview;
    private Coords hovered;
    /** The unit the latest pointer pick found, by its model, meeple or hex ({@link #pointedUnit} for a click's). */
    private int hoveredUnit = Entity.NONE;
    /** Whether the latest pointer position was over the HUD, whose cursor only a pointer move changes. */
    private boolean pointerOverHud = true;
    /** While a move animates the hovered hex is not published, so no route is previewed (rebuild plan A.17 Q5). */
    private boolean hoverHeld;
    /** Height of the walkable surface under the latest pointer in the hovered hex, owned by the render thread like hovered. */
    private float hoverZ = Float.NaN;
    /** Installed object snapshots shared by the outline passes and input until the next board render. */
    private final Map<Coords, List<GpuTerrain.EditorObject>> frameObjects = new HashMap<>();
    private SystemCursor cursor = SystemCursor.Arrow;
    private boolean fitted;
    private long frames;
    private float hoverClock;
    /** The battle clock's share of real time: 1, or less in slow motion. Presentation only. */
    private float timeScale = 1;
    private final UnitAttachments attachments = new UnitAttachments(groundSurfaces);
    private long boardGeneration;
    private long centerSequence = -1;
    private boolean entrancePending;
    private boolean entranceStarting;
    /**
     * The frames after a loading stage that still draw under it, so that the programs they need link before the board
     * shows: the board as it will show and, unless the board makes its entrance, a close view of its centre, whose
     * ground cover and nearest details have programs of their own. Effects that first appear in play link with the
     * first of them, and the game's units not on the board stand in the middle of each (warmUp, withReserve). Optional
     * views and settings (the Tactical View, the wireframe view, SMAA, a render resolution below 100%) link their own
     * programs when they first show, without a loading stage.
     */
    private enum WarmUp { BOARD, CLOSE, DONE }
    private WarmUp warmUp = WarmUp.DONE;
    /** Whether the close view was drawn under this loading stage. */
    private boolean probed;
    private int quietFrames, closeFrames;
    /** The close view's zoom, as the close benchmark view's, and the most frames its detail may take to settle. */
    private static final float CLOSE_ZOOM = .5f;
    private static final int CLOSE_FRAMES = 300;
    /** Without the parallel compiler, links what draws into framebuffer objects where nothing draws (GpuGlsl). */
    private GpuGlsl.Preparation offscreenLinks;
    private int cameraSelection = -1;
    private boolean cameraFollowingPlayback;
    private int layoutWidth;
    private int layoutHeight;
    private int layoutPixelWidth;
    private int layoutPixelHeight;
    private float layoutScale;
    private float layoutPreference;
    private long hoverCameraRevision;
    private int cameraTerrainRevision = -1;
    private boolean reloadingAssets;
    // Coalesce file-worker updates; at most one immutable snapshot is applied per rendered frame.
    private final AtomicReference<GpuAssetSync.Progress> assetSyncProgress = new AtomicReference<>();
    private boolean waitingForAssetCapture;
    private boolean assetReloadFailed;
    private boolean disposed;
    private final GpuShaderManager shaderEdits = new GpuShaderManager();

    GpuBattleView(BoardSource source) {
        this.source = source;
        fieldOfView = new GpuFieldOfView();
        // Its terrain's water detail maps compute on the common pool while the window and GL context open.
        GpuWaterShader.prepareDetail();
    }

    void setLoadingMessage(String message) {
        loadingMessage = message;
    }

    /** Attach the first map after the native loading window is already visible. Runs on the render thread. */
    void attachSource(BoardSource next) {
        source = next;
        shaderEdits.run(this::createBoard);
    }

    /** GPU thread: the board's source has closed, so this view is done; a standalone view ends its application. */
    void sourceClosed() {
        Gdx.app.exit();
    }

    void prepareEntrance() {
        entrancePending = true;
    }

    void startEntrance() {
        entrancePending = false;
        entranceStarting = true;
        if (scene != null) {
            boardCamera.enter(scene);
        }
    }

    /**
     * GL thread. Enters the Tactical View looking straight down, whose next frame shows every unit as its icon on its
     * base, with the ground layer over the terrain the graphics setting chose, or returns to the exact 3D pose it
     * replaced. The {@code TOGGLE_ISO} key (T by default) calls this.
     */
    void setTacticalView(boolean enabled) {
        boardCamera.setTactical(enabled, scene);
    }

    @Override
    public void create() {
        shaderEdits.run(this::createView);
    }

    private void installInput(InputProcessor input) {
        installedInput = input;
        Gdx.input.setInputProcessor(inputBlocked ? null : input);
    }

    /** Whether the pointer is over the HUD; the hidden cursor of a held drag is over nothing. */
    private boolean hudUnderPointer() {
        return !UiCursorCapture.pointerHidden() && ui.hit(Gdx.input.getX(), Gdx.input.getY());
    }

    /** The editor's cliff edge under the pointer, negative for none. */
    private int cliffEdgeUnderPointer() {
        return UiCursorCapture.pointerHidden() ? -1 : ui.cliffEdgeAt(Gdx.input.getX(), Gdx.input.getY());
    }

    /**
     * GPU thread: ignores the mouse and keyboard while a modal Swing dialog is open, as a disabled window would; the
     * window itself stays enabled so it takes the activation back when the dialog closes.
     */
    void setInputBlocked(boolean blocked) {
        if (blocked == inputBlocked) {
            return;
        }
        inputBlocked = blocked;
        if (blocked) {
            pause();
        }
        Gdx.input.setInputProcessor(blocked ? null : installedInput);
    }

    private void createView() {
        if (source == null) {
            createLoadingStage();
            installInput(new InputMultiplexer(fullscreenKey, loadingStage));
            return;
        }
        createBoard();
    }

    private ShaderProgram sprites() {
        if (sprites == null) { sprites = SpriteBatch.createDefaultShader(); }
        return sprites;
    }

    private ShaderProgram shapes() {
        // ShapeRenderer's own: colours, no normals or texture coordinates.
        if (shapes == null) { shapes = ImmediateModeRenderer20.createDefaultShader(false, true, 0); }
        return shapes;
    }

    /** A loading frame that took longer than this built a slice of the board (GpuTerrain#refine): the next follows at once. */
    private static final long WORKING_FRAME_NANOS = 4_000_000;
    /** The frames a second of a loading stage with nothing to do yet: the workers still prepare, or a program links. */
    private static final int WAITING_FPS = 60;
    private long frameStarted;

    /**
     * The loading stage's frames are not held for the monitor's refresh: each builds a slice of the board, and waiting
     * for the next refresh after every slice was a fifth of a city board's load ({@link #drawLoadingStage} paces them).
     * The board's frames follow the Display page's VSync again.
     */
    private void pace(boolean loading) {
        Gdx.graphics.setVSync(!loading && (tuning == null ? GpuBoardWindow.DEFAULT_VSYNC : tuning.vsync()));
        if (!loading) { Gdx.graphics.setForegroundFPS(0); }
    }

    private void createLoadingStage() {
        GpuGlsl.presenting(false);
        if (loadingStage != null) { return; }
        pace(true);
        if (theme == null && loadingTheme == null) { loadingTheme = GpuBoardSkin.forScene(); }
        var skin = theme != null ? theme.skin : loadingTheme.skin;
        loadingStage = new Stage(new ScreenViewport(), new SpriteBatch(1000, sprites()));
        loadingPanel = new GpuLoadingPanel(skin, loadingMessage);
        loadingPanel.setFillParent(true);
        loadingStage.addActor(loadingPanel);
    }

    /** The shader bar shows the share of the programs the load needs that have linked, while any is left to link. */
    static void showShaders(UiKit.Progress bar, TerrainLoadProgress.Shaders shaders) {
        GpuLoadingPanel.showShaders(bar, shaders);
    }

    /** The loading screen at the HUD's display scale, read each frame since a monitor change keeps the window size. */
    private void drawLoadingStage() {
        // A frame that only waited leaves the cores to the workers until the next one is due.
        Gdx.graphics.setForegroundFPS(System.nanoTime() - frameStarted > WORKING_FRAME_NANOS ? 0 : WAITING_FPS);
        if (Gdx.graphics.getWidth() <= 0 || Gdx.graphics.getHeight() <= 0) { return; }
        // The native loading window shows before the first map, and its UI scale preference, arrives.
        float preference = source == null ? 1 : source.uiPreferences().scale();
        var viewport = (ScreenViewport) loadingStage.getViewport();
        viewport.setUnitsPerPixel(1 / DisplayScale.read(preference, displayScale.contentScale()));
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        loadingStage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
        loadingStage.draw();
    }

    private void createBoard() {
        boardCamera.setIsometric(true);
        // One skin for the window: the loading screen's, when it showed, becomes the HUD's (M1 row 1). The loading
        // screen borrows it again while the terrain builds.
        theme = loadingTheme == null ? GpuBoardSkin.forScene() : loadingTheme;
        loadingTheme = null;
        tuning = new GpuBoardTuning(theme.skin, source, boardCamera);
        tuning.terrainCacheStatus(() -> terrain == null ? null : terrain.materialCacheStatus());
        // The switch resizes and redraws the window: after the HUD's event, not inside it.
        tuning.onDisplayMode(fullscreen -> Gdx.app.postRunnable(() -> setFullscreen(fullscreen)));
        // Before the scene renderers: the atmosphere starts with the board's conditions, as the first frame sets them.
        tuning.useScenario(source.scenarioAtmosphere(), true);
        createSceneRenderers();
        boardCamera.flightCollision = (eye, movement) -> {
            if (terrain != null) { terrain.moveCamera(eye, movement, boardCamera.collisionRadius()); }
        };
        boardCamera.terrainHit = ray -> scene == null || terrain == null ? null : terrain.selectionHit(scene, ray);
        markers = new GpuMarkers();
        if (source.isEditor()) { editorBadges = new GpuEditorBadges(); }
        if (source.isGameplay()) { markers.prepareModels(); }
        unitTextures = new GpuTextures<>(false, true);
        overlay = new GpuBoardOverlay(shapes());
        unitBases = new GpuUnitBases(theme.skin);
        // The HUD draws with the view's batch, which it does not own: the battle HUD over a game, the map tools over
        // the board editor and the map preview. The batch outlives an asset reload, which recreates the renderers.
        annotationBatch = new SpriteBatch(1000, sprites());
        ui = source instanceof GpuBoardSource game
              ? new GpuHud(game, theme.skin, annotationBatch, boardCamera, tuning, history)
              : new GpuMapHud(source, theme.skin, annotationBatch, boardCamera, tuning, history, () -> terrain);
        installInput(new InputMultiplexer(ui.stage(), boardInput) {
            // The HUD's Stage takes the presses on its widgets; every key goes through BoardInput, which asks the
            // HUD first (C.4), so no key reaches the Stage twice.
            @Override
            public boolean scrolled(float amountX, float amountY) {
                // The hidden cursor of a held drag is over nothing on the HUD, so nothing there sees it.
                if (UiCursorCapture.pointerHidden()) { return boardInput.scrolled(amountX, amountY); }
                int x = Gdx.input.getX(), y = Gdx.input.getY();
                megamek.client.ui.gdx.UiKit.focusScrollAt(ui.stage(), x, y);
                // A board wheel gesture leaves the edited field, just as a board press does.
                if (source.isEditor() && !ui.hit(x, y)) { ui.stage().setKeyboardFocus(null); }
                return super.scrolled(amountX, amountY);
            }

            @Override
            public boolean touchDragged(int x, int y, int pointer) {
                return UiCursorCapture.pointerHidden() && boardInput.boardGesture
                      ? boardInput.touchDragged(x, y, pointer) : super.touchDragged(x, y, pointer);
            }

            @Override
            public boolean touchDown(int x, int y, int pointer, int button) {
                if (UiCursorCapture.pointerHidden()) { return true; }
                // Finish wheel edits before a toolbar or menu action can consume the click.
                boardInput.finishElevationScroll();
                return super.touchDown(x, y, pointer, button);
            }

            @Override
            public boolean keyDown(int key) {
                boardInput.finishElevationScroll();
                return fullscreenKey.keyDown(key) || boardInput.keyDown(key);
            }

            @Override
            public boolean keyUp(int key) {
                return fullscreenKey.keyUp(key) || boardInput.keyUp(key);
            }

            @Override
            public boolean keyTyped(char character) {
                return fullscreenKey.keyTyped(character) || boardInput.keyTyped(character);
            }
        });
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    /** Recreate every board shader owner; their programs link again behind the next loading stage (warmUp). */
    private void createSceneRenderers() {
        probed = false;
        terrain = new GpuTerrain(unitModels, unitBounds);
        terrain.setCombatScars(source.isGameplay());
        terrain.setFragments(source.isGameplay());
        terrain.setEditable(source.isEditor());
        offscreenLinks = new GpuGlsl.Preparation();
        GpuGlsl.linkOffscreen(offscreenLinks);
        fireControl = new GpuFireControl(sprites());
        tactical = new GpuTactical(terrain::tacticalSurface, sprites(), shapes());
        // As each frame configures it (renderFrame): its sky is generated once, for these settings.
        atmosphere = new GpuAtmosphere(boardCamera.tactical() ? BoardAtmosphere.DEFAULTS : tuning.atmosphere(),
              tuning.atmosphereOptions());
        unitVisibility = new GpuUnitVisibility();
        wireframe = new GpuWireframe();
        unitBatch = new ModelBatch(GpuShaderManager.provider("Units", GpuUnitShader::provider), new GpuOpaqueSorter());
        hexText = new GpuHexText();
        hexGrid = new GpuHexGrid();
        traceOverlay = new GpuTraceOverlay(sprites());
        editorPreview = new GpuEditorPreview(sprites());
        lines = new ShapeRenderer(5000, shapes());
        tetherShader = GpuShaderManager.program(() -> GpuGlsl.compile("Scene tethers", "",
              GpuShaderSource.read("scene-lines.vert"), GpuShaderSource.read("scene-lines.frag")), shader -> {
                  tetherShader = shader;
                  ((ImmediateModeRenderer20) tethers.getRenderer()).setShader(shader);
              });
        tethers = new ShapeRenderer(2000, tetherShader);
    }

    /** Render-thread only. Null released owners so a failed shader compile can be retried safely. */
    private void disposeSceneRenderers() {
        if (boardPrint != null) { boardPrint.dispose(); boardPrint = null; }
        GpuGlsl.linkOffscreen(null);
        if (offscreenLinks != null) { offscreenLinks.dispose(); offscreenLinks = null; }
        if (editorPreview != null) { editorPreview.dispose(); editorPreview = null; }
        // Join the terrain worker before releasing the model buffers it borrows.
        if (terrain != null) { terrain.dispose(); terrain = null; }
        if (fireControl != null) { fireControl.dispose(); fireControl = null; }
        if (tactical != null) { tactical.dispose(); tactical = null; }
        if (atmosphere != null) { atmosphere.dispose(); atmosphere = null; }
        if (unitVisibility != null) { unitVisibility.dispose(); unitVisibility = null; }
        if (wireframe != null) { wireframe.dispose(); wireframe = null; }
        if (unitBatch != null) { unitBatch.dispose(); unitBatch = null; }
        if (hexText != null) { hexText.dispose(); hexText = null; }
        if (hexGrid != null) { hexGrid.dispose(); hexGrid = null; }
        if (traceOverlay != null) { traceOverlay.dispose(); traceOverlay = null; }
        if (lines != null) { lines.dispose(); lines = null; }
        if (tethers != null) { tethers.dispose(); tethers = null; }
        if (tetherShader != null) { GpuShaderManager.dispose(tetherShader); tetherShader = null; }
        attackEffects.dispose();
        designators.dispose();
        starShells.dispose();
        terrainEffects.dispose();
        jumpJets.dispose();
        pilotFlames.dispose();
        waterImpacts.dispose();
        effectDepth.dispose();
        ambientOcclusion.dispose();
        skyVisibility.dispose();
    }

    @Override
    public void resize(int width, int height) {
        if (ui == null || width <= 0 || height <= 0) {
            return;
        }
        // The 3D board's own UI scale (the Tuning panel's); Swing's GUI scale never reaches the native window.
        float preference = source.uiPreferences().scale();
        float scale = DisplayScale.read(preference, displayScale.contentScale());
        int pixelWidth = Gdx.graphics.getBackBufferWidth();
        int pixelHeight = Gdx.graphics.getBackBufferHeight();
        if (width == layoutWidth && height == layoutHeight && scale == layoutScale && preference == layoutPreference
              && pixelWidth == layoutPixelWidth && pixelHeight == layoutPixelHeight) {
            return;
        }
        layoutWidth = width;
        layoutHeight = height;
        layoutPixelWidth = pixelWidth;
        layoutPixelHeight = pixelHeight;
        layoutScale = scale;
        layoutPreference = preference;
        tuning.windowPixels(pixelWidth, pixelHeight);
        ui.resize(width, height, scale);
        // The board fills the window; the HUD's panels float over it (rebuild plan A.1 A1).
        boardCamera.resize(width, height, scene, scale);
    }

    @Override
    public void render() {
        shaderEdits.run(() -> {
            renderBoard();
            shaderEdits.renderPreview();
        });
    }

    private void renderBoard() {
        frameObjects.clear();
        if (!waitingForAssetCapture && shaderEdits.update() && terrain != null) { terrain.shadersChanged(); }
        if (tuning != null && tuning.takeAssetReloadRequest()) { reloadAssets(); }
        showAssetSyncProgress();
        if (waitingForAssetCapture || assetReloadFailed) {
            if (source.isClosed()) { sourceClosed(); return; }
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            ui.draw();
            return;
        }
        try (TerrainSettings.Scope settings = TerrainSettings.use(terrain == null ? null : terrain.settings())) {
            long start = System.nanoTime();
            renderFrame(settings);
            logSlowFrame((System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException failure) {
            if (!reloadingAssets) { throw failure; }
            assetReloadFailed(failure);
        }
    }

    /**
     * Logs a frame slower than {@link #SLOW_FRAME_MILLIS} with what it drew: the artillery blasts and their volumes, and
     * whether the terrain changed, so a playtest's stutter can be traced to its cause from the log alone.
     */
    private void logSlowFrame(long millis) {
        if (millis > SLOW_FRAME_MILLIS) {
            LOGGER.debug("[GpuFrame] {} ms: {} artillery blasts, {} blast volumes, terrain {}", millis,
                  attackEffects.artilleryBlastCount(), attackEffects.explosionCount(),
                  tilesChangedThisFrame ? "changed" : "unchanged");
        }
    }

    /** File sync runs in the background, then EDT artwork capture hands GPU disposal back to the render thread. */
    private void reloadAssets() {
        pause();
        reloadingAssets = true;
        waitingForAssetCapture = true;
        assetReloadFailed = false;
        assetSyncProgress.set(null);
        var application = Gdx.app;
        var data = Configuration.dataDir().toPath();
        Thread.ofVirtual().name("gpu-asset-sync").start(() -> {
            try {
                var result = GpuAssetSync.sync(data, assetSyncProgress::set);
                if (result.source() != null) {
                    LOGGER.info("Asset reload copied {} changed files from {} to {}", result.copied(), result.source(), data);
                }
                application.postRunnable(() -> {
                    if (disposed || source.isClosed()) { return; }
                    showAssetSyncProgress();
                    tuning.assetReloadStage("Reloading artwork");
                });
                captureReloadedAssets(application);
            } catch (java.io.IOException | RuntimeException failure) {
                assetReloadFailed(application, failure);
            }
        });
    }

    private void showAssetSyncProgress() {
        var progress = assetSyncProgress.getAndSet(null);
        if (progress != null) { tuning.assetSyncProgress(progress); }
    }

    private void captureReloadedAssets(Application application) {
        SwingUtilities.invokeLater(() -> {
            try {
                BoardTreeCoverage.reload();
                source.reloadAssets();
                application.postRunnable(() -> shaderEdits.run(() -> {
                    if (disposed || source.isClosed()) { return; }
                    waitingForAssetCapture = false;
                    tuning.assetReloadStage("Rebuilding board assets");
                    try {
                        disposeSceneRenderers();
                        clearUnitInstances();
                        spriteModels.values().forEach(GpuUnitModel::dispose);
                        spriteModels.clear();
                        if (unitModels != null) { unitModels.dispose(); }
                        camouflage.dispose();
                        damageDisplay.dispose();
                        unitTextures.dispose();
                        unitBases.dispose();
                        groundSurfaces.clear();
                        attachments.clear();
                        BoardRocks.reload();
                        BoardRough.reload();
                        BoardGroundCut.reload();
                        BoardFungus.reload();
                        BoardScatter.reload();
                        BoardObstacles.reload();
                        GpuShaderSource.forget();
                        BoardDecals.reload();
                        BoardBridgeFooting.reload();
                        createSceneRenderers();
                        cameraTerrainRevision = -1;
                    } catch (RuntimeException failure) {
                        assetReloadFailed(failure);
                    }
                }));
            } catch (java.io.IOException | RuntimeException failure) {
                assetReloadFailed(application, failure);
            }
        });
    }

    private void assetReloadFailed(Application application, Exception failure) {
        application.postRunnable(() -> {
            if (!disposed && !source.isClosed()) { assetReloadFailed(failure); }
        });
    }

    private void assetReloadFailed(Exception failure) {
        LOGGER.error("Cannot reload GPU board assets", failure);
        // Lazy programs can fail inside a scene/shadow pass. Keep the retry panel on the window framebuffer.
        FrameBuffer.unbind();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        reloadingAssets = false;
        waitingForAssetCapture = false;
        assetSyncProgress.set(null);
        assetReloadFailed = true;
        tuning.assetReloadFinished(false);
    }

    private void renderFrame(TerrainSettings.Scope settings) {
        frameStarted = System.nanoTime();
        if (source == null) {
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            loadingPanel.message(loadingMessage);
            drawLoadingStage();
            return;
        }
        if (source.isClosed()) {
            sourceClosed();
            return;
        }
        if (Gdx.graphics.getWidth() <= 0 || Gdx.graphics.getHeight() <= 0) {
            return;
        }
        // The Tuning panel's UI scale reaches the layout as the saved preference, which the source publishes.
        tuning.saveUiScale();
        // Moving to another monitor can change DPI without changing the window's dimensions.
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        BoardSource.Frame frame = source.takeFrame();
        if (frame.scene() == null) {
            // Without a board the HUD still draws: a dialog asked now holds the EDT until it is answered here.
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            updateHud(frame);
            ui.draw();
            return;
        }
        renderStage("scene update");
        if (scene != null && (boardGeneration != frame.boardGeneration() || scene.boardId() != frame.scene().boardId() || scene.width() != frame.scene().width()
              || scene.height() != frame.scene().height())) {
            terrain.boardChanged();
            atmosphere.resetContrast();
            playback.clear();
            timeScale = 1;
            attackEffects.clearArtilleryBlasts();
            history.clear();
            animators.clear();
            groundSurfaces.clear();
            attachments.clear();
            jumpJets.clear();
            waterImpacts.clear();
            unitPicking.clear();
            hovered = null;
            boardInput.reset();
            fitted = false;
            probed = false;
            boardCamera.boardChanged();
        }
        tuning.useScenario(frame.scenarioAtmosphere(), scene == null || boardGeneration != frame.boardGeneration()
              || scene.boardId() != frame.scene().boardId());
        List<BoardScene.Tile> previousTiles = scene == null ? null : scene.tiles();
        scene = frame.scene();
        if (unitModels != null) { unitModels.setDisplayMode(displayMode(), boardCamera.tactical()); }
        history.accept(frame.timeline(), scene, frame.reports(), this::hasInfantryTransports);
        // The trees an attack takes down split into their pieces ahead of its hit (GpuTerrain#breakTrees).
        for (var event : frame.timeline()) {
            if (event instanceof BoardScene.SceneUpdate update && !update.broken().isEmpty()) {
                terrain.prepareBreak(update.broken());
            }
        }
        // The editor plays Destroyed's collapse, which comes with the frame that shows the ruin (GpuMapSource#editorCommand).
        // Any other change of its tiles shows at once, as Skip to results shows it, so no collapse plays on, or lies, where
        // a structure stands again; with nothing falling the skip changes nothing.
        if (source.isEditor() && frame.scene().tiles() != editorTiles) {
            if (frame.timeline().isEmpty()) { history.skip(); }
            editorTiles = frame.scene().tiles();
        }
        scene = history.live(scene);
        comingTiles = scene.tiles();
        // Apply HUD state independently of camera framing; panels overlay the full board viewport.
        if (ui instanceof GpuHud hud) { hud.updateState(frame, playbackBusy(), dialog()); }
        playback.gravityOverride = tuning.gravityOverride();
        if (!fitted) { updateCameraFocus(scene, frame.centerRequest()); }
        var instantAction = history.speed() == UnitMotion.Speed.INSTANT ? playback.lastAction() : null;
        // Instant has no animation clock: finish existing casualties before their models or markers are drawn again.
        if (history.speed() == UnitMotion.Speed.INSTANT) {
            casualties.dispose();
            terrain.clearCollapses();
            attackEffects.clearArtilleryBlasts();
        }
        updateSlowMotion();
        history.advance(Gdx.graphics.getDeltaTime() * timeScale, state -> preparePlaybackCamera(state, scene));
        // The impact frame's explosion and debris already use full speed, without waiting for the next frame.
        updateSlowMotion();
        // Historical review is presentation only: it must never signal a live movement completion to Swing.
        source.playbackState(frame, history.running());
        scene = playback.present(scene);
        BoardSource.Frame unitFrame = frame.withScene(scene);
        if (ui instanceof GpuHud hud) {
            hud.updateState(frame, playbackBusy(), dialog());
            unitFrame = hud.state.presented(unitFrame);
            scene = unitFrame.scene();
        }
        if (warmUp != WarmUp.DONE) { scene = withReserve(scene); }
        // Dying meeples still wear their unit's camouflage (MeepleCasualties).
        camouflage.retain(Stream.concat(scene.units().stream(), casualties.units().stream()).toList());
        if (unitModels != null) {
            // The units not on the board keep the models their warm-up assembled, for the moment they appear.
            unitModels.retainAssemblies(Stream.of(scene.units(), source.reserveUnits(), casualties.units()).flatMap(List::stream)
                  .filter(unit -> !unit.sensorContact()).map(BoardScene.Unit::id).collect(Collectors.toSet()));
        }
        boardGeneration = frame.boardGeneration();
        var atmosphereSettings = tuning.atmosphere();
        // The Tactical View has clear air and its one fixed light: no clouds, fog, haze or weather.
        atmosphere.configure(boardCamera.tactical() ? BoardAtmosphere.DEFAULTS : atmosphereSettings);
        atmosphere.setOptions(tuning.atmosphereOptions());
        tuning.displaySettings().apply(terrain, atmosphere, ambientOcclusion,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), boardCamera.tactical());
        // Material readiness includes live lighting/cloud flags, before any visible terrain is submitted.
        atmosphere.updateLight(boardCamera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        // The temporary close-up only discovers programs. Baking pages for it would evict the user's view.
        terrain.setMaterialCache(tuning.terrainCache() && warmUp != WarmUp.CLOSE);
        terrain.setMaterialCacheDebug(tuning.terrainCacheDebug());
        terrain.setGravity(atmosphereSettings.gravity());
        terrain.setVacuum(atmosphereSettings.pressure().isVacuum());
        // The 3D Graphics choose what the terrain builds; the first frame already has the saved choice, and a switch
        // rebuilds behind the loading stage below. The Tactical View is the camera and the presentation.
        terrain.setColumns(source.uiPreferences().graphics() == BoardGraphics.CLASSIC);
        terrain.present(playback.transition(), playback.transitionProgress());
        // Authored transforms move the installed shell below. Slice, replant and rebuild sky occlusion after release.
        boolean authoredGesture = source.editorAuthoredGesture() && terrain.ready(scene);
        skyVisibility.update(terrain.environment(), authoredGesture ? terrain.presentation(scene) : scene, 1);
        if (!authoredGesture) { terrain.update(scene, boardCamera.camera); }
        // Keep draining preparation, but do not build another terrain level for the shader probe's temporary camera.
        boolean detailChanged = terrain.refine(warmUp == WarmUp.CLOSE ? null : boardCamera.camera, source.isEditor());
        settings.set(terrain.settings());
        tuning.terrainProgress(terrain.buildProgress());
        if (!terrain.ready(scene)) {
            ScreenUtils.clear(.045f, .065f, .075f, 1, true);
            createLoadingStage();
            loadingPanel.update(terrain);
            drawLoadingStage();
            renderStage(null);
            // Each loading stage readies what may change programs, a lighting switch included: probe again.
            warmUp = WarmUp.BOARD;
            probed = false;
            return;
        }
        boolean hidden = warmUp != WarmUp.DONE;
        if (!hidden && loadingStage != null) {
            pace(false);
            loadingStage.dispose();
            loadingStage.getBatch().dispose();
            loadingStage = null;
            if (loadingTheme != null) { loadingTheme.release(); }
            loadingTheme = null;
            // Models that could not load stand as marked boxes (GpuAssets#missing); the shown board names them.
            var missing = terrain.takeMissingAssets();
            if (!missing.isEmpty()) {
                megamek.client.ui.gdx.UiMessageDialog.show(ui.uiKit(), ui.stage(), UiKit.text("GpuBoard.missingAssets.title"),
                      UiKit.text("GpuBoard.missingAssets.message", missing.keySet().stream().sorted()
                            .collect(Collectors.joining("\n"))), List.of(UiKit.text("Close")), answer -> { });
            }
        }
        // The optional views' own programs link in the background (GpuGlsl.deferral) when they first show: no freeze, and
        // until linked their draws wait (GpuShaderProvider.UNLINKED).
        if (!hidden) { GpuGlsl.presenting(true); }
        if (source.isEditor()) { terrain.previewEditorObjects(scene); }
        if (boardPrint == null) {
            var request = source.takePrintRequest();
            if (request != null) {
                boardPrint = new GpuBoardPrint(request, terrain, theme.skin.getFont("default-font"),
                      theme.skin.getFont(GpuBoardSkin.OUTLINE_FONT), atmosphereSettings, tuning.atmosphereOptions(),
                      sprites());
            }
        }
        if (boardPrint != null && boardPrint.update()) { boardPrint = null; }
        if (ui instanceof GpuMapHud map) { map.print(boardPrint); }
        scene = terrain.presentation(scene);
        tilesChangedThisFrame = scene.tiles() != lastFrameTiles;
        lastFrameTiles = scene.tiles();
        boolean changedTiles = previousTiles != scene.tiles();
        if (changedTiles || cameraTerrainRevision != BoardGeometry.revision()) {
            boardCamera.terrainChanged(scene);
            cameraTerrainRevision = BoardGeometry.revision();
        }
        fireControl.update(scene, HIDE_TARGET_ARROWS_DURING_ATTACKS && !playback.attacks().isEmpty(), unitFrame.status(),
              unitFrame.panels().fire());
        tactical.update(scene, detailChanged, hovered, !unitFrame.panels().move().route().isEmpty(), hoveredUnit);
        fieldOfView.update(scene.fieldOfView());
        fieldOfView.configure(tuning.fovStyle(), tuning.fovDarkness(), tuning.sensorStyle(), tuning.sensorDarkness());
        attackEffects.setWind(atmosphereSettings.effects());
        // The board's pressure already reads as vacuum in space or at zero gravity.
        attackEffects.setFuelAirStrength(AreaEffectHelper.fuelAirStrength(atmosphereSettings.pressure(), false));
        // The Tactical View hides the scatter, by one draw rule and without a rebuild.
        terrain.setScatterHidden(boardCamera.tactical());
        terrain.setGrass(tuning.grass());
        terrain.setWaterEffects(tuning.waterEffects());
        // Sprite meshes bake their atlas UVs. New artwork is appended, so only the moved slots need new meshes.
        Set<BoardScene.Pixels> moved = unitTextures.updateRegions(Stream.concat(scene.units().stream().filter(unit -> !unit.sensorContact()
              && (unitModels == null || unitModels.get(unit.model(), unit.id(), unit.part()) == null)), casualties.units().stream())
              .map(BoardScene.Unit::image).distinct()
              .collect(Collectors.toMap(pixels -> pixels, pixels -> pixels)));
        Set<BoardScene.Pixels> images = Stream.concat(scene.units().stream(), casualties.units().stream())
              .map(BoardScene.Unit::image).collect(Collectors.toSet());
        disposeSpriteModels(sprite -> moved.contains(sprite.pixels()) || !images.contains(sprite.pixels()));
        updateCameraFocus(scene, frame.centerRequest(), instantAction);
        // The first visible frame's delta may still include the hidden window's loading time.
        boardCamera.advance(entranceStarting ? 0 : Gdx.graphics.getDeltaTime());
        entranceStarting = false;
        float cameraSpeed = BoardCameraControls.speed(modifiers());
        if (ui.isTextEditing() || ui.isModal()) {
            // Text editing and modal dialogs suspend held camera movement.
            cameraKeys.clear();
        } else if (boardCamera.firstPerson()) {
            advanceFirstPerson(Gdx.graphics.getDeltaTime(), cameraSpeed);
        } else {
            BoardCameraControls.move(boardCamera, cameraKeys.values(), Gdx.graphics.getDeltaTime() * cameraSpeed);
            boardInput.autoPan(Gdx.graphics.getDeltaTime());
        }
        if (changedTiles || hoverCameraRevision != boardCamera.revision()) {
            source.setVisibleArea(boardCamera.visibleArea(scene));
        }
        if (UiCursorCapture.pointerHidden()) {
            // The hidden cursor points at nothing; its return move after the release refreshes the hover.
            hoverCameraRevision = boardCamera.revision();
        } else if (hoverCameraRevision != boardCamera.revision() || hoverHeld != (playback.movement() != null)) {
            hoverCameraRevision = boardCamera.revision();
            hoverHeld = playback.movement() != null;
            boardInput.mouseMoved(Gdx.input.getX(), Gdx.input.getY());
        }
        hoverClock += animationSeconds();
        groundSurfaces.columns(terrain.columns());
        renderStage("unit poses");
        markers.beginFrame(scene.markers(), Gdx.graphics.getDeltaTime());
        prepareUnits();
        casualties.advance(animationSeconds(), debrisGround, atmosphereSettings.gravity() / BoardAtmosphere.STANDARD_GRAVITY);
        terrain.advanceCollapses(animationSeconds(), collapseGround, atmosphereSettings.gravity() / BoardAtmosphere.STANDARD_GRAVITY);
        terrain.advanceFalls(animationSeconds(), atmosphereSettings.gravity() / BoardAtmosphere.STANDARD_GRAVITY);
        applyHover();
        for (var unit : scene.units()) {
            var instance = unitInstances.get(unit.id() + ":" + unit.part());
            if (MeepleVisual.isMeeple(instance)) {
                MeepleAnimator.attacks(unit, instance, playback.attacks(), unitInstances, unitGhosts.containsKey(instance));
                var visual = ((GpuUnitInstance) instance).visual();
                unitAnchors.put(unit, visual.anchor(instance, boardCamera.camera));
            }
        }
        var completedAttacks = playback.takeCompletedAttacks();
        for (var attacks : List.of(playback.attacks(), completedAttacks)) {
            for (var attack : attacks) {
                attack.landscape = ray -> terrain.hit(scene, ray);
                attack.landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
            }
        }
        aimAttack();
        attachments.place(scene, unitModels, unitInstances, animators, playback.attachment(), boardCamera.camera, unitAnchors, hoverClock);
        for (var unit : scene.units()) {
            if (unit.attachment() == null) { continue; }
            scene.units().stream().filter(host -> host.id() == unit.attachment().carrierId()).findFirst().ifPresent(host -> {
                var pose = unitFootprints.get(host);
                if (pose != null) { unitFootprints.put(unit, new UnitFootprint.Pose(unit, pose.position(), pose.facing())); }
            });
        }
        updateEquipmentDetail();
        if (boardCamera.tactical()) { anchorOnBases(); }
        markers.update(unitInstances.values().stream().filter(instance -> !unitGhosts.containsKey(instance)).toList(),
              boardCamera.camera);
        updateJumpJets();
        updatePilotFlames();
        // A nuke's fire and smoke link as it launches, or as a scheduled one goes off, before the burning hexes arrive
        // (warmUp links them only on a board that has fire or smoke).
        var detonations = playback.takeDetonations();
        if (!detonations.isEmpty() || playback.attacks().stream()
              .anyMatch(attack -> GpuArtilleryBlast.nuclearStats(attack.event.result().shot()) != null)) {
            GpuGlsl.offscreen(terrainEffects::prepare);
        }
        attackEffects.update(playback.attacks(), completedAttacks, unitModels, unitInstances);
        for (var detonation : detonations) {
            attackEffects.detonate(detonation.result());
            // What it brings down falls with its front; Instant shows only the outcome.
            if (history.speed() != UnitMotion.Speed.INSTANT) { terrain.strike(detonation); }
        }
        // The trees an attack took down break away from it as its hit lands; Instant and a review show only the outcome.
        for (var update : playback.takeBrokenTrees()) {
            if (history.speed() != UnitMotion.Speed.INSTANT && !playback.reviewing()) {
                terrain.breakTrees(update.broken(), update.hit());
            }
        }
        // A nuclear round fired in combat: what its attack brought down falls with its front (GpuTerrain#strike); Instant
        // and a review show only the outcome.
        attackEffects.artilleryStrikes((result, landing) -> {
            if (history.speed() != UnitMotion.Speed.INSTANT && !playback.reviewing()) { terrain.strike(result, landing); }
        });
        // Blasts run on the playback clock: they pause with it and follow its speed, at 1x in real time.
        float blastSeconds = animationSeconds() * GpuArtilleryBlast.SECONDS_PER_PLAYBACK;
        attackEffects.advance(blastSeconds);
        terrain.advanceScorch(blastSeconds);
        terrain.shocks(attackEffects::shocks);
        // No craters on the Classic columns.
        if (!terrain.columns()) {
            attackEffects.groundImpacts(completedAttacks, impact -> terrain.impact(scene, impact));
            attackEffects.artilleryGroundMarks(impact -> terrain.impact(scene, impact));
            attackEffects.artilleryScorches(scorch -> terrain.scorch(scene, scorch));
        }
        unitBounds.begin();
        List<ModelInstance> units = new ArrayList<>(unitInstances.values());
        // Both views share the casualties and their clock. Ghosts cast no shadow, nor anything half faded.
        casualties.casualties().forEach(casualty -> units.add(casualty.instance));
        units.removeIf(instance -> unitGhosts.containsKey(instance)
              || instance.userData instanceof GpuUnitShader.Shown shown && shown.opacity < .5f);
        List<ModelInstance> outlined = scene.units().stream()
              .filter(unit -> !unit.sensorContact() || GpuMarkers.outlineEnabled(BoardMarker.Kind.SENSOR_CONTACT))
              .map(unit -> unitInstances.get(unit.id() + ":" + unit.part()))
              .collect(Collectors.toCollection(ArrayList::new));
        outlined.addAll(markers.outlinedInstances());
        outlined.removeIf(instance -> instance == null || unitGhosts.containsKey(instance)
              || instance.userData instanceof GpuUnitShader.Shown shown && shown.opacity < 1
              || !boardCamera.camera.frustum.boundsInFrustum(unitBounds.get(instance)));
        float seeThrough = tuning.seeThrough();
        boolean wireframeView = tuning.wireframe() && GpuWireframe.supported();
        // Trees dissolve between detail levels, except in the wireframe view, whose depth fill and lines cannot.
        terrain.treeDissolves = !wireframeView;
        // The units' poses and anchors and the camera are final for this frame. The tuning model follows the camera,
        // which can leave Free Flight by itself (its keys, the Tactical View).
        renderStage("board-space HUD");
        tuning.syncCamera();
        tuning.showFullscreen(Gdx.graphics.isFullscreen());
        updateHud(frame);
        if (boardCamera.tactical()) {
            // After the HUD update, which settles the board overlay's acting unit and focused enemy.
            unitBases.update(scene, frame.status(), unitFootprints, overlay.actor(), overlay.focus(), hoveredUnit,
                  hovered, UnitScreenScale.hexPixels(boardCamera.camera.zoom, 1), Gdx.graphics.getDeltaTime(),
                  source.uiPreferences().overlays().statusBadges(), casualties.casualties());
        }
        renderStage("cutaways and light");
        atmosphere.updateLight(boardCamera);
        terrain.setAtmosphere(atmosphere.lighting());
        // An armed building's roof turret and wall casemates are part of it: unlike a unit inside, they never open it.
        // Dying meeples neither wade nor open buildings.
        List<ModelInstance> occupants = new ArrayList<>(unitInstances.values());
        occupants.removeIf(unitGhosts::containsKey);
        for (var unit : scene.units()) {
            if (unit.model() != null && unit.model().state() != null && unit.model().state().structure().building() != null) {
                occupants.remove(unitInstances.get(unit.id() + ":" + unit.part()));
            }
        }
        // An occupied building fades. In the Tactical View, whose ground layer marks its roof, it goes altogether, so no
        // token is covered.
        terrain.animate(Gdx.graphics.getDeltaTime(), occupants, boardCamera.tactical() ? 0 : tuning.buildingOpacity());
        editorPreview.prepare(scene, terrain, ghosts());
        renderStage("geometry shadows");
        // The Tactical View's icons cast nothing.
        terrain.renderShadows(boardCamera.camera, boardCamera.tactical() ? List.of() : units);
        renderStage("cloud transmission");
        atmosphere.prepareClouds(terrain, scene, Gdx.graphics.getDeltaTime());
        // The opaque terrain: its preparation and gather, its depth pre-pass (GpuTerrain.depthPrepass), then its colour.
        renderStage("opaque preparation");
        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
        atmosphere.begin((int) boardCamera.camera.viewportWidth, (int) boardCamera.camera.viewportHeight,
              BoardCamera.renderScale, Gdx.graphics.getDeltaTime());
        if (wireframeView) {
            renderStage("opaque terrain");
            wireframe.fill(boardCamera.camera, terrain);
        } else {
            terrain.setEffectLights(attackEffects.lights());
            terrain.render(boardCamera.camera, () -> renderStage("depth pre-pass"), () -> {
                renderStage("ambient occlusion");
                ambientOcclusion.render(boardCamera.camera, terrain, atmosphere.depthTexture());
                renderStage("opaque terrain");
            });
        }
        if (boardCamera.tactical()) {
            // The Tactical View's ground marks, then the bases, which the icons cover.
            renderStage("ground layer");
            hexGrid.render(boardCamera, scene, terrain.columns(), true, frame.status().arc(),
                  source.uiPreferences().overlays().levelShading());
            unitBases.renderBases(boardCamera.camera);
        }
        renderStage("units");
        if (!boardCamera.tactical()) { renderUnits(wireframeView); }
        // Water and the transparent effects read no occlusion.
        ambientOcclusion.end();
        renderStage("transparent effects");
        renderTethers();
        if (!wireframeView) { terrain.renderTransparent(boardCamera.camera); }
        // The Tactical View's icons write no depth, so they follow the water; the effects draw over them.
        if (boardCamera.tactical()) { renderUnits(wireframeView); }
        Color smokeLight = atmosphere.sceneParticleLight();
        terrainEffects.update(scene, groundSurfaces, atmosphereSettings.effects(), animationSeconds());
        effectDepth.begin();
        // Illumination rounds light the opaque scene before smoke and fire are drawn over it.
        starShells.update(scene, attackEffects.starShellOpenings(), Gdx.graphics.getDeltaTime());
        starShells.renderLight(boardCamera.camera, effectDepth, atmosphere.lighting().daylight());
        // The Classic columns' tileset art already shows fire and smoke, the Tactical View marks them on their hexes.
        if (!terrain.columns() && !boardCamera.tactical()) {
            terrainEffects.render(boardCamera.camera, effectDepth, smokeLight, atmosphere.lighting().direction());
        }
        jumpJets.setSmokeLight(smokeLight);
        attackEffects.setSmokeLight(smokeLight);
        attackEffects.setLightDirection(atmosphere.lighting().direction());
        // Mines lie on the terrain, so not on the Classic columns, which draw no craters.
        boolean terrainMarks = !terrain.columns();
        attackEffects.setMinefields(terrainMarks ? GpuArtilleryBlast.minefields(scene.markers()) : Map.of(),
              ray -> terrain.hit(scene, ray));
        jumpJets.render(boardCamera.camera);
        pilotFlames.render(boardCamera.camera);
        attackEffects.render(boardCamera.camera, effectDepth, casualties.casualties(), terrain.dust());
        designators.render(boardCamera.camera, scene.designations(), Gdx.graphics.getDeltaTime());
        starShells.renderFlares(boardCamera.camera, atmosphere.lighting().daylight());
        if (!terrain.waterVisible()) { waterImpacts.clear(); }
        else {
            waterImpacts.render(boardCamera.camera, scene, motions, unitInstances, smokeLight, playback.attachment());
        }
        if (boardCamera.tactical()) { unitBases.renderBadges(boardCamera.camera); }
        renderStage("weather particles");
        atmosphere.renderWeather(boardCamera.camera, scene);
        renderStage("atmosphere composite");
        atmosphere.setFlash(attackEffects.nuclearFlash());
        atmosphere.end(boardCamera.camera, terrain, scene, 0, fieldOfView, !wireframeView);
        if (!boardCamera.tactical()) { atmosphere.prepareContrast(boardCamera.camera, scene, 0); }
        if (wireframeView) { wireframe.lines(boardCamera.camera, terrain); }
        renderStage("unit outlines");
        if (boardCamera.tactical()) { unitVisibility.renderDepth(boardCamera.camera, outlined); }
        unitVisibility.render(boardCamera.camera, outlined, atmosphere.depthTexture(), 0, seeThrough, layoutScale,
              unitBounds, terrain.columns() || boardCamera.tactical() ? null : terrainEffects.opacityTexture(),
              terrain);
        Gdx.gl.glDepthRangef(0, 1);
        if (source.isEditor()) { traceOverlay.render(boardCamera, source.trace(), Gdx.graphics.getDeltaTime()); }
        renderStage("tactical overlays");
        // Each artillery round in the air marks the hex it is coming down on, until it lands.
        overlay.setIncomingTargets(playback.attacks(), Gdx.graphics.getDeltaTime());
        // Movement annotations show through scenery; draw the destination ghost after the route and the units.
        overlay.renderMarks(boardCamera.camera, unitFootprints::get);
        overlay.render(boardCamera.camera);
        renderHoverRings();
        var ghosts = ghosts();
        if (ghosts != GpuEditorPreview.Data.NONE) {
            editorPreview.render(boardCamera.camera, scene, terrain, ghosts);
        }
        fireControl.render(boardCamera.camera, Gdx.graphics.getDeltaTime(), boardCamera.tactical());
        renderStage("terrain markers");
        tactical.render(boardCamera.camera, Gdx.graphics.getDeltaTime(), boardCamera.tactical(),
              source.uiPreferences().overlays());
        renderStage("hex grid");
        // Outside the Tactical View the grid over the finished scene; inside it, the ground layer drew it.
        if (!boardCamera.tactical()) {
            hexGrid.render(boardCamera, scene, terrain.columns(), false, GpuBattleStatus.Arc.NONE, false, atmosphere);
        }
        renderStage("hex labels");
        renderHexText(wireframeView);
        renderStage("overlay labels and ghosts");
        overlay.renderGhost(boardCamera.camera, shownUnit);
        overlay.renderGhosts(boardCamera.camera, unitGhosts);
        fireControl.renderLabels(boardCamera.camera, boardCamera.tactical());
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        tactical.renderLabels(boardCamera.camera);
        renderEditorObjectControls();
        renderStage("annotations and UI");
        renderAnnotations();
        renderMarquee();
        renderEntrance();
        if (hidden) {
            warmUp(detailChanged);
            renderStage(null);
            return;
        }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(null)) { ui.draw(); }
        renderStage(null);
        if (reloadingAssets) {
            reloadingAssets = false;
            tuning.assetReloadFinished(true);
        }
        frames++;
    }

    /** Covers this frame, drawn for its programs alone, with the loading stage and readies the next one (WarmUp). */
    private void warmUp(boolean detailChanged) {
        switch (warmUp) {
            case BOARD -> {
                // Effects draw into the scene's targets; hex masks over the finished frame. Combat's brief effects link
                // now; hex fire and smoke, and splashes, where the board has them. Pilot flames link in these frames,
                // with the flamers that carry them; star shells, ECM fields, the deployment tint and fire or smoke a
                // board gains later, which last while they show, when they first appear.
                GpuGlsl.offscreen(() -> {
                    attackEffects.prepare();
                    designators.prepare();
                    if (scene.tiles().stream().anyMatch(tile -> tile.fireSmoke().present())) { terrainEffects.prepare(); }
                    if (terrain.hasWater()) { waterImpacts.prepare(); }
                    jumpJets.prepare();
                    atmosphere.prepare();
                    terrain.prepareEffects();
                });
                // Overlays over the finished frame: before a first range, route or outline needs their programs.
                tactical.prepare();
                fireControl.prepare();
                overlay.prepare();
                scene.units().stream().filter(unit -> !unit.sensorContact() && unit.image() != null).findFirst()
                      .ifPresent(unit -> MeepleVisual.prepareIcon(unitBatch, unitTextures.region(unit.image()).getTexture()));
                // Probing moves the camera: not while the board makes its entrance. An editor loads what its starting view
                // draws and the rest when it is asked for: a closer view's programs link while the board shows.
                if (probed || source.isEditor() || entrancePending || boardCamera.isFraming() || boardCamera.tactical()) {
                    warmUp = WarmUp.DONE;
                } else {
                    boardCamera.look(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0),
                          CLOSE_ZOOM);
                    quietFrames = closeFrames = 0;
                    warmUp = WarmUp.CLOSE;
                }
            }
            case CLOSE -> {
                quietFrames = terrain.busy() || detailChanged ? 0 : quietFrames + 1;
                if (quietFrames >= 3 || ++closeFrames >= CLOSE_FRAMES) {
                    probed = true;
                    // Back to the pose the probes replaced, drawn once more under the stage.
                    boardCamera.endLook(scene);
                    warmUp = WarmUp.BOARD;
                }
            }
            default -> { }
        }
        ScreenUtils.clear(.045f, .065f, .075f, 1, true);
        createLoadingStage();
        drawLoadingStage();
    }

    /** The scene with the game's units not on the board (BoardSource.reserveUnits) on the hex in the middle of the view. */
    private BoardScene withReserve(BoardScene shown) {
        List<BoardScene.Unit> reserve = source.reserveUnits();
        if (reserve.isEmpty()) { return shown; }
        Coords middle = BoardGeometry.pick(shown, boardCamera.camera.getPickRay(boardCamera.camera.viewportWidth / 2,
              boardCamera.camera.viewportHeight / 2, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        BoardScene.Tile tile = shown.tile(middle == null ? new Coords(shown.width() / 2, shown.height() / 2) : middle);
        if (tile == null) { return shown; }
        var place = new BoardScene.Waypoint(tile.coords(), tile.elevation(), 0);
        List<BoardScene.Unit> units = new ArrayList<>(shown.units());
        reserve.forEach(unit -> units.add(unit.at(place)));
        return shown.withUnits(units);
    }

    /**
     * Hands the HUD this frame: the board view's facts, the pending dialog and the preferences. The board overlay
     * then shows the frame with the scene the playback presents.
     */
    private void updateHud(GpuBoardSource.Frame frame) {
        GpuHud.HudView view = hudView(frame.scene() != null, frame.panels().fire());
        // Over the board the pointer follows what a click there takes every frame, as the unit outline does: a mode
        // change or a new envelope changes it without a pointer move.
        if (!pointerOverHud) {
            cursor(boardCursor(view.hoveredUnit(), Gdx.input.getX(), Gdx.input.getY()));
        }
        // The HUD shows the tuning controls' own values, not the settings of the terrain on display.
        try (TerrainSettings.Scope ignored = TerrainSettings.use(null)) {
            ui.update(frame, view, dialog(), source.uiPreferences());
        }
        if (ui.isModal()) {
            cameraKeys.clear();
            if (!boardInput.pressedKeys.isEmpty()) {
                boardInput.pressedKeys.clear();
                source.stopKeys();
            }
            if (boardInput.boardGesture) { boardInput.interrupt(); }
        }
        if (ui instanceof GpuHud hud && frame.scene() != null) {
            overlay.columns(terrain.columns());
            overlay.update(frame.withScene(scene), view, source.uiPreferences(), hud.state);
        }
    }

    /** The pending native dialog of a game's source; a map has none. */
    private GpuBoardWindow.DialogRequest dialog() {
        return source instanceof GpuBoardSource game ? game.dialog() : null;
    }

    /**
     * The hover rings the view draws itself every frame, inset by {@link #HOVER_HEX_INSET}:
     * the hovered hex's outline, or with Ctrl in the editor the brush's hexes, at the height a unit would stand there;
     * and a building floor under the pointer column: its outline half a hex above the floor's level,
     * joined by its corners to a faint outline on the hex. Hidden sections draw at half opacity.
     */
    private void renderHoverRings() {
        float top = hoverTop();
        if (!source.isEditor()) {
            if (!hudUnderPointer()) {
                GpuHexHover.render(lines, boardCamera.camera, scene, hovered, top);
            }
            return;
        }
        if ((hovered == null || scene.tile(hovered) == null || hudUnderPointer())
              && (!source.isEditor() || source.editorState() == null || source.editorState().selection().isEmpty())
              && cliffEdgeUnderPointer() < 0) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        // Both passes compare against scene depth without letting the outline occlude itself or later overlays.
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        try {
            Gdx.gl.glDepthFunc(GL20.GL_GREATER);
            drawHoverRings(top, HOVER_OCCLUDED_ALPHA);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            drawHoverRings(top, 1);
        } finally {
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    /** The ghosts over the frame: the editor's preview, or in a game the work under way. */
    private GpuEditorPreview.Data ghosts() {
        if (!source.isEditor()) {
            // A stand-in source (a test's mock) may give none.
            return java.util.Objects.requireNonNullElse(source.workGhosts(), GpuEditorPreview.Data.NONE);
        }
        return editorPreviewShown() ? source.editorPreview() : GpuEditorPreview.Data.NONE;
    }

    /**
     * Whether the editor's preview shows: Paint or Erase with the pointer over a hex of the board (over the HUD the
     * pointer hovers no hex, and the source previews nothing).
     */
    private boolean editorPreviewShown() {
        var state = source.editorState();
        return state != null && (state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.PAINT
              || state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.ERASE)
              && hovered != null && scene.tile(hovered) != null;
    }

    private void drawHoverRings(float top, float alpha) {
        lines.begin(ShapeRenderer.ShapeType.Line);
        if (source.isEditor() && source.editorState() != null) {
            var state = source.editorState();
            lines.setColor(.45f, 1, .8f, alpha);
            int selectionIndex = 0;
            for (var item : state.selection()) {
                Coords destination = state.movePreview().size() == state.selection().size()
                      ? state.movePreview().get(selectionIndex) : item.coords();
                selectionIndex++;
                if (scene.tile(item.coords()) == null) { continue; }
                if (item.object().isEmpty()) {
                    float z = BoardTacticalGeometry.floatingZ(scene, item.coords());
                    ring(item.coords(), z);
                    var cliff = state.property("cliff_top");
                    if (item.coords().equals(state.selected()) && cliff != null) {
                        for (int edge = 0; edge < 6; edge++) {
                            if ((cliff.exits() & (1 << BoardGeometry.edgeDirection(edge))) != 0) {
                                ringEdge(item.coords(), z, edge, HOVER_HEX_INSET + .05f);
                            }
                        }
                    }
                }
                else {
                    var object = frameObjects(item.coords()).stream()
                          .filter(o -> o.id().equals(item.object())).findFirst();
                    if (object.isPresent()) {
                        // Only a held drag adds the ground projection of its drop. Object controls draw over the scene.
                        if (boardInput.dragged && boardInput.editorGrab != null) {
                            editorProjection(destination, object.get().anchorLevel() * BoardGeometry.level());
                        }
                    } else {
                        ring(destination, BoardTacticalGeometry.floatingZ(scene, destination));
                    }
                }
            }
            drawAuthoredVolumes(alpha);
            for (Coords coords : state.movePreview()) {
                boolean valid = scene.tile(coords) != null;
                lines.setColor(valid ? .45f : 1, valid ? 1 : .3f, valid ? .8f : .3f, alpha);
                if (valid && boardInput.editorHexGrab != null) { editorProjection(coords, boardInput.editorPlaneZ); }
                else { ring(coords, valid ? BoardTacticalGeometry.floatingZ(scene, coords) : BoardGeometry.level()); }
            }
        }
        lines.end();
        drawCliffEdgeHover(alpha);
        if (hovered == null || scene.tile(hovered) == null || hudUnderPointer()) { return; }
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(1, 1, 1, alpha);
        if (source.isEditor() && (modifiers() == InputEvent.CTRL_DOWN_MASK
              || source.editorState() != null && source.editorState().tool() != megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT)) {
            // The brush's reach, dimmed where the preview shows the hexes a click changes: white, or red for Erase.
            var preview = editorPreviewShown() ? source.editorPreview() : GpuEditorPreview.Data.NONE;
            lines.setColor(1, 1, 1, alpha * (preview.hexes().isEmpty() ? 1 : .35f));
            for (Coords coords : source.editorBrush(hovered, boardGeneration)) {
                if (scene.tile(coords) != null) {
                    ring(coords, BoardTacticalGeometry.floatingZ(scene, coords));
                }
            }
            lines.setColor(1, preview.erase() ? .33f : 1, preview.erase() ? .3f : 1, alpha);
            for (Coords coords : preview.hexes()) {
                if (scene.tile(coords) != null) { ring(coords, BoardTacticalGeometry.floatingZ(scene, coords)); }
            }
            for (var deck : preview.decks()) {
                lines.setColor(1, deck.removed() ? .33f : .65f, deck.removed() ? .3f : .12f, alpha);
                ring(deck.at(), (deck.level() + .06f) * BoardGeometry.level());
            }
            // A drawn route's turns too sharp to lay, which end in stubs on release: red, twice.
            lines.setColor(1, .33f, .3f, alpha);
            for (Coords coords : preview.stubs()) {
                if (scene.tile(coords) == null) { continue; }
                float z = BoardTacticalGeometry.floatingZ(scene, coords);
                ring(coords, z);
                for (int edge = 0; edge < 6; edge++) { ringEdge(coords, z, edge, HOVER_HEX_INSET + .05f); }
            }
            for (var box : preview == GpuEditorPreview.Data.NONE ? List.<com.badlogic.gdx.math.collision.BoundingBox>of()
                  : editorPreview.outlines()) {
                lines.box(box.min.x, box.min.y, box.max.z, box.getWidth(), box.getHeight(), box.getDepth());
            }
        } else {
            GpuHexHover.draw(lines, scene, hovered, top, alpha);
        }
        lines.end();
    }

    private List<BoardScene.Tile> authoredSelection() {
        var state = source.editorState();
        if (state == null) { return List.of(); }
        var ids = state.selection().stream().map(megamek.client.ui.boardeditor.BoardEditorSession.Selection::object)
              .filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) { return List.of(); }
        return scene.tiles().stream().filter(tile -> tile.cover().reference() != null
              && ids.contains(tile.cover().reference().id())).toList();
    }

    /** The GLB's hidden structural solids and the gameplay columns they bake, visible only while selected. */
    private void drawAuthoredVolumes(float alpha) {
        var selected = authoredSelection();
        java.util.Set<String> drawn = new java.util.HashSet<>();
        float unit = BoardGeometry.hexScale();
        for (var tile : selected) {
            var ref = tile.cover().reference();
            lines.setColor(.45f, 1, .8f, alpha);
            ring(tile.coords(), (tile.elevation() + .06f) * BoardGeometry.level());
            lines.setColor(1, .75f, .25f, alpha);
            ring(tile.coords(), (tile.elevation() + tile.cover().height() + .06f) * BoardGeometry.level());
            var feature = tile.features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElse(null);
            if (feature == null || !drawn.add(ref.id())) { continue; }
            lines.setColor(.45f, 1, .8f, alpha * .55f);
            for (var solid : megamek.common.board.BuildingVolumes.placed(ref.anchor(), feature.decoration(), feature.elevation())) {
                for (var triangle : solid.triangles()) {
                    var points = List.of(triangle.a(), triangle.b(), triangle.c());
                    for (int i = 0; i < 3; i++) {
                        var a = points.get(i); var b = points.get((i + 1) % 3);
                        lines.line((float) a.x() * unit, (float) a.y() * unit, (float) a.z() * unit,
                              (float) b.x() * unit, (float) b.y() * unit, (float) b.z() * unit);
                    }
                }
            }
        }
    }

    /** A hex's outline, inset by {@link #HOVER_HEX_INSET}, at height {@code z}. */
    private void ring(Coords coords, float z) {
        for (int edge = 0; edge < 6; edge++) {
            ringEdge(coords, z, edge, HOVER_HEX_INSET);
        }
    }

    private void ringEdge(Coords coords, float z, int edge, float inset) {
        Vector3 center = BoardGeometry.center(coords, 0);
        Vector3 from = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, inset);
        Vector3 to = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, inset);
        lines.line(from.x, from.y, z, to.x, to.y, z);
    }

    /** A filled ribbon keeps the hovered side visible without depending on native wide-line support. */
    private void drawCliffEdgeHover(float alpha) {
        int direction = cliffEdgeUnderPointer();
        var state = source.editorState();
        if (direction < 0 || state == null || state.selected() == null || scene.tile(state.selected()) == null) { return; }
        Coords coords = state.selected();
        int edge = Math.floorMod(1 - direction, 6);
        float z = BoardTacticalGeometry.floatingZ(scene, coords);
        Vector3 center = BoardGeometry.center(coords, 0);
        Vector3 a = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, HOVER_HEX_INSET);
        Vector3 b = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center, HOVER_HEX_INSET);
        lines.translate(0, 0, z);
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(1, .85f, .35f, alpha);
        lines.rectLine(a.x, a.y, b.x, b.y, BoardGeometry.hexScale());
        lines.end();
        lines.identity();
    }

    /**
     * The board view's facts for the HUD: the view mode, the hovered hex and unit, a hex's width on the screen and,
     * while a board is drawn, each drawn unit's head (its label anchor) and screen rectangle in stage units, y up, and
     * its animated board position. The rectangle runs from the unit's ground up to its head, .55 of a hex wide, in 3D
     * and is the square around its base's ring in the Tactical View, whose head is the ring's north point; a unit
     * whose head lies behind the camera has neither. A unit drawn in parts is placed by its centre part. A fire target
     * that is no unit is anchored on its hex's centre, on the plane of the hex annotations.
     */
    private GpuHud.HudView hudView(boolean drawn, GpuFireOrders.Snapshot fire) {
        Map<Integer, Rectangle> rects = new HashMap<>();
        Map<Integer, Vector2> heads = new HashMap<>();
        Map<Integer, Vector2> positions = new HashMap<>();
        float hexPixels = UnitScreenScale.hexPixels(boardCamera.camera.zoom, layoutScale);
        boolean based = boardCamera.tactical();
        float width = hexPixels * UNIT_RECT_WIDTH;
        // Stage units per viewport pixel: the HUD's stage spans the board camera's viewport.
        float stage = ui.stage().getWidth() / boardCamera.camera.viewportWidth;
        for (Map.Entry<BoardScene.Unit, Vector3> entry : unitAnchors.entrySet()) {
            UnitFootprint.Pose pose = unitFootprints.get(entry.getKey());
            if (!drawn || entry.getKey().part() > 0 || pose == null
                  || unitGhosts.containsKey(unitInstances.get(entry.getKey().id() + ":" + entry.getKey().part()))) {
                continue;
            }
            int id = entry.getKey().id();
            positions.put(id, new Vector2(pose.position().x, pose.position().y));
            Vector2 head = GpuNameplates.project(boardCamera.camera, entry.getValue());
            Vector2 ground = GpuNameplates.project(boardCamera.camera, pose.position());
            if (head == null || ground == null) {
                continue;
            }
            heads.put(id, head.scl(stage));
            ground.scl(stage);
            if (based) {
                // The square around the base's oval, whose centre lies off a unit's position when its hexes do.
                var oval = GpuUnitBases.oval(scene, entry.getKey());
                Vector2 center = GpuNameplates.project(boardCamera.camera, pose.position().cpy().add(oval.x(), oval.y(), 0));
                if (center == null) { continue; }
                float pixels = hexPixels / BoardGeometry.width();
                float across = 2 * oval.halfWidth() * pixels;
                float up = 2 * oval.halfHeight() * pixels;
                center.scl(stage);
                rects.put(id, new Rectangle(center.x - across / 2, center.y - up / 2, across, up));
            } else {
                rects.put(id, new Rectangle(Math.min(ground.x, head.x) - width / 2, Math.min(ground.y, head.y),
                      Math.abs(head.x - ground.x) + width, Math.abs(head.y - ground.y)));
            }
        }
        Map<TargetKey, Vector2> targets = new HashMap<>();
        fire.hexes().forEach((target, hex) -> {
            Vector2 head = drawn && scene != null && scene.tile(hex) != null ? GpuNameplates.project(boardCamera.camera,
                  new Vector3(BoardGeometry.centerX(hex), BoardGeometry.centerY(hex),
                        BoardTacticalGeometry.floatingZ(scene, hex))) : null;
            if (head != null) {
                targets.put(target, head.scl(stage));
            }
        });
        return new GpuHud.HudView(boardCamera.tactical(), playbackBusy(), rects, heads, positions, hovered,
              pointedUnit(), hexPixels, targets);
    }

    /**
     * The hovered unit as a left click there takes it ({@link GpuHud#clickedUnit}): none over a hex the click plans a
     * move to, its unit's model included, so the board shows the route preview and no unit outline there, and on a
     * stack the unit the click takes next.
     */
    private int pointedUnit() {
        return ui instanceof GpuHud hud ? hud.clickedUnit(hovered, hoveredUnit) : hoveredUnit;
    }

    /**
     * Whether the HUD is presenting live or historical actions. Only history.running() reports live work to Swing.
     */
    private boolean playbackBusy() {
        return history.running() || playback.reviewing() || history.replaying();
    }

    /** Benchmark boundary only. Normal gameplay does not allocate queries, read clocks or wait for the GPU. */
    void renderStage(String stage) { }

    /** The editor's selection box, drawn in window pixels like {@link #renderEntrance}; coral while Ctrl removes. */
    private void renderMarquee() {
        if (!boardInput.marquee || !boardInput.dragged) { return; }
        Color tone = (boardInput.gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0 ? UiTheme.CORAL : UiTheme.MINT;
        float height = Gdx.graphics.getHeight();
        float x = Math.min(boardInput.startX, boardInput.dragX), y = height - Math.max(boardInput.startY, boardInput.dragY);
        float width = Math.abs(boardInput.dragX - boardInput.startX), boxHeight = Math.abs(boardInput.dragY - boardInput.startY);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(tone.r, tone.g, tone.b, .12f);
        lines.rect(x, y, width, boxHeight);
        lines.end();
        lines.begin(ShapeRenderer.ShapeType.Line);
        lines.setColor(tone.r, tone.g, tone.b, .9f);
        lines.rect(x, y, width, boxHeight);
        lines.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderEntrance() {
        float opacity = entrancePending ? 0 : boardCamera.entranceOpacity();
        if (opacity >= 1) { return; }
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        lines.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        lines.begin(ShapeRenderer.ShapeType.Filled);
        lines.setColor(0.035f, 0.055f, 0.075f, 1 - opacity);
        lines.rect(0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
        lines.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void updateJumpJets() {
        jumpJets.beginFrame();
        if (unitModels != null) {
            for (var unit : scene.units()) {
                var motion = motions.get(unit.id());
                if (unit.sensorContact() || motion == null) {
                    continue;
                }
                var sample = motion.sample();
                if (sample.jets() == null && sample.group() == null) {
                    continue;
                }
                String key = unit.id() + ":" + unit.part();
                var instance = unitInstances.get(key);
                var model = instance instanceof GpuUnitInstance displayed ? displayed.visual() : null;
                if (instance != null && model != null) {
                    jumpJets.update(key, model, instance, unit, sample);
                }
            }
        }
        jumpJets.endFrame();
    }

    private void updatePilotFlames() {
        pilotFlames.beginFrame(Gdx.graphics.getDeltaTime());
        if (unitModels == null) {
            return;
        }
        for (var unit : scene.units()) {
            var instance = unitInstances.get(unit.id() + ":" + unit.part());
            if (instance instanceof GpuUnitInstance displayed && displayed.visual() != null) {
                pilotFlames.update(displayed.visual(), instance, unit);
            }
        }
    }

    /** The Tactical View shows every unit as its icon, whatever the tuning's display; the 3D view follows it. */
    private UnitDisplayMode displayMode() {
        return boardCamera.tactical() ? UnitDisplayMode.ALL_MEEPLES : tuning.unitDisplayMode();
    }

    /**
     * In the Tactical View a unit's labels hang from the north point of its base's ring, straight up the screen at
     * every zoom, instead of from its model's head.
     */
    private void anchorOnBases() {
        for (Map.Entry<BoardScene.Unit, UnitFootprint.Pose> entry : unitFootprints.entrySet()) {
            Vector2 north = GpuUnitBases.oval(scene, entry.getKey()).rim(0);
            unitAnchors.put(entry.getKey(), entry.getValue().position().cpy().add(north.x, north.y, 0));
        }
    }

    private void prepareUnits() {
        hover.clear();
        unitAnchors.clear();
        unitFootprints.clear();
        unitGhosts.clear();
        // Zoomed out, the 3D view's units grow to stay visible; the Tactical View's icons keep their size in the hex.
        float growth = boardCamera.tactical() ? 1
              : UnitScreenScale.factor(UnitScreenScale.hexPixels(boardCamera.camera.zoom, layoutScale));
        Set<String> keys = scene.units().stream().map(unit -> unit.id() + ":" + unit.part()).collect(Collectors.toSet());
        unitInstances.keySet().retainAll(keys);
        deadUnits.retainAll(keys);
        unitTints.keySet().retainAll(unitInstances.keySet());
        upperBodyTurns.keySet().retainAll(unitInstances.keySet());
        armFlips.keySet().retainAll(unitInstances.keySet());
        animators.keySet().retainAll(unitInstances.keySet());
        unitDamage.keySet().retainAll(unitInstances.keySet());
        equipmentAppearance.keySet().retainAll(unitInstances.keySet());
        hits.keySet().retainAll(scene.units().stream().map(BoardScene.Unit::id).collect(Collectors.toSet()));
        hits.values().removeIf(hit -> hoverClock - hit.clock() > HIT_MEMORY_SECONDS);
        for (BoardScene.Unit unit : scene.units()) {
            var hit = hit(unit);
            if (hit != null) { hits.put(unit.id(), hit); }
            var recent = recentHit(unit);
            Vector3 position = BoardGeometry.center(unit.location().coords(), unit.location().elevation());
            playback.placeDisplacement(unit, position);
            float facing = unit.location().facing() * 60;
            UnitMotion motion = unit.sensorContact() ? null : motions.get(unit.id());
            UnitMotion.Sample sample = motion == null ? UnitMotion.Sample.STILL : motion.sample();
            BoardScene.Unit placement = sample.placement(unit);
            boolean airborne = sample.airborne(unit);
            if (motion != null && motion.isMoving()) {
                position.sub(motion.destination()).add(airborne ? motion.position() : motion.surfacePosition(scene));
                if (placement.footprint().size() > 1) {
                    // The path already carries its own absolute elevation; destination support is not a constant lift.
                    position.set(airborne ? motion.position() : motion.surfacePosition(scene));
                }
                facing = motion.facing();
                UnitFootprint.clearTerrain(scene, placement, position, facing, airborne);
            }
            BoardScene.Tile tile = scene.tile(unit.location().coords());
            if (tile != null && tile.waterDepth() == 0 && !tile.frozen() && !airborne
                  && MathUtils.isEqual(position.z, tile.elevation() * BoardGeometry.level())) {
                position.z = BoardGeometry.groundZ(tile);
            }
            // A unit on an elevator the server moved rides its platform (GpuTerrain#present).
            position.z += terrain.liftOffset(unit.location().coords());
            // On a kit building's roof it stands on the roof as drawn (GpuTerrain#roofLift): looks only.
            if (!airborne) { position.z += terrain.roofLift(scene, position); }
            float footprintFacing = facing;
            GpuUnitModel visual = unit.sensorContact() ? markers.model(BoardMarker.Kind.SENSOR_CONTACT)
                  : unitModels == null ? null : unitModels.get(unit.model(), unit.id(), unit.part());
            boolean authored = !unit.sensorContact() && visual != null;
            boolean dead = dead(unit);
            var death = playback.attacks().stream().filter(attack -> attack.death() && attack.event.entityId() == unit.id())
                  .findFirst().orElse(null);
            float collapse = death == null ? dead ? 1 : 0 : death.event.result().crewLoss() ? 0 : death.deathProgress();
            if (airborne && collapse > 0) {
                float ground = UnitLandingSupports.ground(scene, position.x, position.y, groundSurfaces);
                if (!Float.isFinite(ground) && tile != null) { ground = BoardGeometry.surfaceZ(tile); }
                if (Float.isFinite(ground)) { position.z = MathUtils.lerp(position.z, ground, collapse); }
            }
            if (visual == null) {
                visual = spriteModels.computeIfAbsent(new UnitSprite(unit.image(), displayMode().meeple(unit.model()), boardCamera.tactical()),
                      sprite -> sprite.icon() ? GpuUnitModel.icon(sprite.pixels(), unitTextures.region(sprite.pixels()))
                            : sprite.meeple() ? GpuUnitModel.meeple(sprite.pixels(), unitTextures.region(sprite.pixels()))
                            : GpuUnitModel.sprite(sprite.pixels(), unitTextures.region(sprite.pixels())));
            }
            boolean resting = !airborne && (motion == null || !motion.isMoving()) && tile != null;
            boolean waiting = resting && UnitAnimator.waiting(unit);
            if (visual.meeple() && resting && !waiting && groundSurfaces.get(scene, tile).dug()) {
                // A token has no ground contact of its own: at rest it stands on the dug ground under it, as a model does,
                // in a strongpoint's vehicle pit (BoardGroundCut).
                float dug = UnitLandingSupports.terrain(scene, position.x, position.y, groundSurfaces);
                if (dug < position.z && position.z - dug < BoardGeometry.level()) { position.z = dug; }
            }
            String key = unit.id() + ":" + unit.part();
            ModelInstance instance = unitInstances.get(key);
            // Its death begins as the hit that killed it lands, or as a death no hit caused starts to play.
            boolean destroyed = death == null ? dead : death.seconds >= 0;
            boolean dying = destroyed && deadUnits.add(key);
            if (!destroyed && deadUnits.remove(key)) { casualties.revived(unit); }
            if (!destroyed && death != null && !death.event.result().crewLoss()
                  && instance instanceof GpuUnitInstance standing
                  && history.speed() != UnitMotion.Speed.INSTANT) {
                // Its death waits for the hit that causes it: its meeple is cut meanwhile, off this thread, for the
                // explosion only to make meshes.
                casualties.prepare(death, standing, unit);
            }
            float ghost = destroyed ? ghost(unit, death) : 0;
            if (destroyed) {
                // The casualty owns the last drawn pose until it fades. A later simultaneous attack can still draw
                // a temporary ghost, but neither view keeps a permanent wreck.
                if (dying || ghost == 0) { unitInstances.remove(key); }
                if (dying && instance instanceof GpuUnitInstance standing
                      && history.speed() != UnitMotion.Speed.INSTANT) {
                    casualties.died(standing, unit, recent == null ? null : recent.push(), death,
                          death != null && death.cause != null ? death.cause : recent == null ? null : recent.cause(),
                          new UnitFootprint.Pose(placement, position, footprintFacing));
                }
                if (ghost == 0) { continue; }
                instance = unitInstances.get(key);
            }
            if (instance == null || instance.model != visual.instance.model) {
                animators.remove(key);
                ModelInstance previous = instance;
                instance = newUnitInstance(key, visual);
                // A meeple formation's lost soldiers and vehicles die where they stood, rather than vanish with its
                // smaller assembly.
                if (previous instanceof GpuUnitInstance shown && shown.visual() != null && shown.visual().infantry()
                      && authored && visual.infantry() && displayMode().meeple(unit.model()) && UnitAnimator.troops(unit)
                      && unit.attachment() == null && history.speed() != UnitMotion.Speed.INSTANT) {
                    casualties.lost(shown, visual, unit, recent == null ? null : recent.push(), recent == null ? null : recent.cause());
                }
            }
            BoardScene.LocationDamage shownDamage = unitDamage.getOrDefault(key, BoardScene.LocationDamage.NONE);
            boolean mek = authored && (unit.model().state() == null ? visual.turnsUpperBody()
                  : unit.model().state().structure().anatomy() != null);
            BoardScene.LocationDamage damage = authored ? visual.infantry() ? unit.model().damage()
                  : UnitDamageDisplay.preview(unit.model().damage(), mek, tuning.damageOverride(),
                        visual.damageLocation(tuning.damageLocation()))
                  : BoardScene.LocationDamage.NONE;
            UnitModelState.Appearance appearance = !unit.sensorContact() && unit.model() != null && unit.model().state() != null
                  ? unit.model().state().appearance() : null;
            if (visual.meeple()) {
                // Fresh materials make repair/camouflage changes reversible; mesh buffers remain shared.
                var bodyDamage = tuning.damageOverride() >= 0
                      ? UnitDamageDisplay.preview(BoardScene.LocationDamage.NONE, false, tuning.damageOverride())
                      : UnitDamageDisplay.body(appearance == null ? null : appearance.bodyStage());
                if (!bodyDamage.equals(shownDamage) || !java.util.Objects.equals(appearance, equipmentAppearance.get(key))) {
                    instance = newUnitInstance(key, visual);
                    MeepleVisual.appearance(instance, visual, appearance, tuning.damageOverride(), unit.id(), camouflage, damageDisplay);
                    unitDamage.put(key, bodyDamage);
                    equipmentAppearance.put(key, appearance);
                }
            }
            if (authored && (!damage.equals(shownDamage)
                  || !java.util.Objects.equals(appearance, equipmentAppearance.get(key)))) {
                UpperBodyTurn previousTurn = upperBodyTurns.get(key);
                instance = showDamage(key, visual, unit, damage);
                if (appearance != null) {
                    visual.showEquipment(instance, appearance);
                    camouflage.apply(instance, visual.instance, appearance);
                    equipmentAppearance.put(key, appearance);
                }
                damageDisplay.applyTexture(instance, damage, unit.id());
                if (previousTurn != null) {
                    upperBodyTurns.put(key, previousTurn);
                    visual.turnUpperBody(instance, previousTurn.degrees());
                }
            }
            if (dead && authored) { damageDisplay.wreck(instance, visual, unit.id()); }
            // Presentation-only color for the see-through pass; the normal model materials retain their artwork.
            if (!(instance.userData instanceof GpuUnitShader.Shown)) {
                instance.userData = new GpuUnitShader.Shown();
            }
            var shown = (GpuUnitShader.Shown) instance.userData;
            Color.rgb888ToColor(shown, unit.outlineRgb());
            // The hex the unit stands in right now, also while it walks through other hexes.
            BoardScene.Tile standing = BoardGeometry.tile(scene, position.x, position.y);
            shown.a = standing == null ? 0 : GpuUnitVisibility.ownHex(standing);
            shown.opacity = 1;
            if (authored && appearance == null && !unit.image().equals(unitTints.get(key))) {
                // The sprite average and imported paint factor share linear RGB; normalize that pigment once.
                Color tint = GpuCutout.averageColor(unit.image());
                float brightest = Math.max(tint.r, Math.max(tint.g, tint.b));
                if (brightest > 0.01f) {
                    tint.mul(1 / brightest);
                    tint.a = 1;
                }
                for (var material : instance.materials) {
                    if ("paint".equals(material.id)) {
                        material.set(ColorAttribute.createDiffuse(tint));
                    }
                }
                unitTints.put(key, unit.image());
            }
            if (authored && visual.turnsUpperBody()) {
                // A movement replay already follows the legs, so only a unit standing still shows its twist.
                boolean isMoving = (motion != null) && motion.isMoving();
                facing -= turnUpperBody(visual, instance, key, unit, isMoving ? 0 : unit.model().twist());
            }
            if (authored && unit.model().state() != null && !visual.rigs().isEmpty()) {
                if (visual.rigs().stream().allMatch(rig -> rig.trooper() || "infantry-transport".equals(rig.family()))) {
                    facing = 0; // Troops and their transports have cosmetic member headings, no gameplay facing.
                }
                var turn = upperBodyTurns.get(key);
                animators.computeIfAbsent(key, ignored -> newAnimator()).apply(visual, instance, unit,
                      sample, hoverClock, animationSeconds(),
                      history.speed() == UnitMotion.Speed.INSTANT, turn == null ? 0 : turn.degrees(),
                      UnitScreenScale.growth(placement, growth));
                animators.get(key).attacks(visual, unit, playback.attacks());
                animators.get(key).conversion(playback.conversion(), unit);
                if (visual.infantry()) { animators.get(key).previewCasualties(tuning.damageOverride()); }
            }
            // After the animator, never before it: the animator resets every joint to its rest pose
            // each frame and then poses leftArm and rightArm itself, which are the same nodes a flip
            // turns. Setting the flip first meant it was overwritten before anything was drawn.
            if (authored && visual.flipsArms()) {
                flipArms(visual, instance, key, unit);
            }
            Vector3 anchor = unit.sensorContact()
                  ? markers.placeSensor(unit.location().coords(), instance, boardCamera.camera, position)
                  : visual.meeple() ? MeepleAnimator.place(visual, instance, placement, sample, boardCamera.camera, position, facing,
                        playback.conversion(), boardCamera.isTopDown())
                  : visual.place(instance, boardCamera.camera, position, facing, placement);
            anchor = UnitScreenScale.grow(placement, instance, anchor, growth);
            if (waiting) {
                // A tank that could go hull down here but has not waits in its strongpoint's hide, at ground level.
                var box = UnitBounds.local(instance);
                List<Vector3> hull = new ArrayList<>(4);
                for (float x : new float[] { box.min.x, box.max.x }) {
                    for (float y : new float[] { box.min.y, box.max.y }) {
                        hull.add(new Vector3(x, y, box.min.z).mul(instance.transform));
                    }
                }
                Vector3 hide = BoardGroundCut.hide(tile, groundSurfaces.get(scene, tile).stands(), hull);
                if (hide != null) {
                    instance.transform.trn(hide);
                    position.add(hide);
                    anchor.add(hide);
                }
            }
            UnitAnimator animator = animators.get(key);
            if (authored && animator != null && unit.attachment() == null && animator.groundSupports(scene, placement, sample)) {
                anchor = visual.anchor(instance, boardCamera.camera);
            }
            var shove = playback.pushedOff(unit);
            if (shove != null) {
                shown.opacity = shove.posePushedOff(instance);
                anchor = visual.anchor(instance, boardCamera.camera);
            }
            if (airborne && collapse == 0) {
                float offset = hoverOffset(hoverClock, unit.id(), unit.part());
                hover.add(new Hover(instance, offset));
                anchor.add(0, 0, offset);
                position.z += offset;
            }
            if (ghost == 0) { unitFootprints.put(unit, new UnitFootprint.Pose(placement, position, footprintFacing)); }
            unitAnchors.put(unit, anchor);
            if (ghost > 0) { unitGhosts.put(instance, ghost); }
        }
        occupiedGround.clear();
        for (BoardScene.Unit unit : scene.units()) {
            var instance = unitInstances.get(unit.id() + ":" + unit.part());
            if (instance == null || UnitAnimator.troops(unit) || unit.sensorContact() || unit.airborne()
                  || unit.attachment() != null) { continue; }
            // The outline of its turned box seen from above, in whole world units so idle animation does not keep
            // nudging the troops beside it.
            var box = UnitBounds.local(instance);
            float[] corners = new float[16];
            for (int corner = 0; corner < 8; corner++) {
                var point = new Vector3((corner & 1) == 0 ? box.min.x : box.max.x, (corner & 2) == 0 ? box.min.y : box.max.y,
                      (corner & 4) == 0 ? box.min.z : box.max.z).mul(instance.transform);
                corners[2 * corner] = Math.round(point.x);
                corners[2 * corner + 1] = Math.round(point.y);
            }
            var hull = new ConvexHull().computePolygon(corners, false);
            var ground = new Polygon(java.util.Arrays.copyOf(hull.items, hull.size - 2));
            unit.footprint().forEach(coords -> occupiedGround.computeIfAbsent(coords, ignored -> new ArrayList<>()).add(ground));
        }
        crowds.pack(occupiedGround);
    }

    /** Destroyed, or doomed and about to be: its wreck, or its death playing. */
    private static boolean dead(BoardScene.Unit unit) {
        return !unit.sensorContact() && unit.model() != null && unit.model().state() != null
              && unit.model().state().pose().dead();
    }

    /**
     * How much shows of a destroyed unit's ghost: attacks resolve together, and one it fired or took after its death
     * plays on its ghost, which fades in as that attack or the death begins and out as the attack ends. None without
     * one: the hit that killed it plays on the unit.
     */
    private float ghost(BoardScene.Unit unit, UnitAttack death) {
        float shown = 0;
        for (var attack : playback.attacks()) {
            var attacker = attack.event.attacker();
            var target = attack.event.target();
            if (attack.death() || death != null && death.cause == attack
                  || (attacker == null || attacker.id() != unit.id()) && (target == null || target.id() != unit.id())) { continue; }
            float since = death == null ? attack.seconds : Math.min(attack.seconds, death.seconds);
            shown = Math.max(shown, MathUtils.clamp(since / GHOST_FADE_SECONDS, 0, 1)
                  * MathUtils.clamp((attack.duration - attack.seconds) / GHOST_FADE_SECONDS, 0, 1));
        }
        return shown;
    }

    private UnitAnimator newAnimator() {
        return new UnitAnimator(groundSurfaces, () -> scene, crowds);
    }

    /**
     * How the hits playing against {@code unit} drive it, horizontal: away from each attacker, from an artillery round
     * landing in its hex or from a nuclear blast's centre, each by the damage it dealt; null when none is playing.
     */
    private Hit hit(BoardScene.Unit unit) {
        var at = BoardGeometry.center(unit.location().coords(), unit.location().elevation());
        var push = new Vector3();
        UnitAttack cause = null;
        for (var attack : playback.attacks()) {
            var target = attack.event.target();
            boolean blast = attack.event.result().shot() != null && attack.event.result().shot().artillery();
            var landing = attack.event.destination();
            boolean affected = blast ? landing.coords().equals(unit.location().coords()) : target != null && target.id() == unit.id();
            if (affected && blast) {
                push.mulAdd(away(at, BoardGeometry.center(landing.coords(), landing.elevation())), damage(attack));
            } else if (affected) {
                var attacker = attack.event.attacker();
                push.mulAdd(away(at, UnitAttack.center(unitInstances.get(attacker.id() + ":" + attacker.part()),
                      attacker.location(), new Vector3())), damage(attack));
            }
            if (affected && !attack.death() && !attack.defensive() && attack.event.result().hit()
                  && attack.seconds >= attack.contactSeconds
                  && (cause == null || attack.seconds - attack.contactSeconds <= cause.seconds - cause.contactSeconds)) {
                cause = attack;
            }
        }
        var detonation = playback.detonation();
        if (push.isZero() && detonation != null) {
            var blast = detonation.result();
            // A strike throws everyone; a conventional explosion only those its damage reaches, as hard as it is there.
            float damage = blast.kind() != megamek.common.NuclearDetonation.Kind.EXPLOSION ? NUCLEAR_DAMAGE
                  : blast.baseDamage() - blast.coords().distance(unit.location().coords()) * (float) blast.degradation();
            if (damage > 0) {
                push.mulAdd(away(at, BoardGeometry.center(blast.coords(), blast.elevation())), damage);
            }
        }
        if (push.isZero(1e-6f) && cause == null) { return null; }
        var recent = recentHit(unit);
        return new Hit(push.isZero(1e-6f) ? null : push, hoverClock,
              cause != null ? cause : recent == null ? null : recent.cause());
    }

    /** The damage an attack dealt, as its impacts report it, or its rounds' when it reports none (an area blast). */
    private static float damage(UnitAttack attack) {
        var result = attack.event.result();
        float dealt = 0;
        for (var impact : result.impacts()) { dealt += impact.weight(); }
        return dealt > 0 || !result.hit() || result.shot() == null ? dealt : (float) result.shot().damagePerHit();
    }

    /** The hits that played against {@code unit} in the last {@link #HIT_MEMORY_SECONDS}, or null. */
    private Hit recentHit(BoardScene.Unit unit) {
        var hit = hits.get(unit.id());
        return hit == null || hoverClock - hit.clock() > HIT_MEMORY_SECONDS ? null : hit;
    }

    /** The horizontal direction from {@code source} to {@code at}; none where they coincide. */
    private static Vector3 away(Vector3 at, Vector3 source) {
        var away = new Vector3(at.x - source.x, at.y - source.y, 0);
        return away.len2() < 1e-4f ? away.setZero() : away.nor();
    }

    /**
     * What thrown meeple pieces land on at (x, y) ({@link #debrisGround}): the top of the ground, roof, deck or rock the
     * pointer would pick there, foliage giving way, or a liquid's bed; half a unit up, as units stand. Above the height
     * {@code from} they started at, the pieces look past a bridge deck, and over their meeple's {@code own} ground past
     * the building's roof that covered it; never past the ground. NaN off the board.
     */
    private float debrisHeight(float x, float y, float from, boolean own) {
        return debrisHeight(x, y, from, own, true);
    }

    /**
     * {@link #debrisHeight}, the pieces looking past what stands above where they started only with {@code past}: the
     * pieces of a structure that came down look past only within their own reach ({@link #collapseGround}).
     */
    private float debrisHeight(float x, float y, float from, boolean own, boolean past) {
        var tile = BoardGeometry.tile(scene, x, y);
        if (tile == null) { return Float.NaN; }
        float top = (tile.elevation() + 40) * BoardGeometry.level();
        var hit = surfaceHit(x, y, top);
        float height = hit == null ? BoardGeometry.surfaceZ(tile) : top - (float) Math.sqrt(hit.distance());
        String struck = hit == null ? "ground" : hit.receiver();
        if (past && height > from && ("bridge".equals(struck) || own && "building".equals(struck))) {
            hit = surfaceHit(x, y, from);
            height = hit == null ? BoardGeometry.surfaceZ(tile) : from - (float) Math.sqrt(hit.distance());
        }
        boolean liquid = tile.liquid().present() && !tile.frozen() && height <= BoardGeometry.surfaceZ(tile) + 1;
        return (liquid ? BoardGeometry.groundZ(tile) : height) + .5f;
    }

    /** The surface the pointer would pick straight below (x, y, top). */
    private BoardGeometry.Hit surfaceHit(float x, float y, float top) {
        return terrain.selectionHit(scene, new com.badlogic.gdx.math.collision.Ray(new Vector3(x, y, top),
              new Vector3(0, 0, -1)));
    }

    /**
     * Frame an action before its clock advances, including when one large frame reaches several queued actions. A scene
     * change waits for the terrain to build the ground its moving parts start from (GpuTerrain#ready).
     */
    boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) {
        if (!state.reviewing() && (state.movement() != null || state.attack() != null)) { boardCamera.beginPlayback(); }
        if (state.movement() != null) {
            boardCamera.frameMovement(state.movement(), motions.get(state.activeEntityId()), state.present(scene), playbackCameraWidth());
        } else if (state.attack() != null && state.attack().shot()) {
            boardCamera.frameAttacks(state.attacks(), playbackCameraWidth());
        } else if (state.transition() != null) {
            // A structure comes down, or an elevator moves, where it is: the camera stays where the player has it. The
            // clock waits for the terrain to take what falls (GpuTerrain#ready).
            return terrain.ready(state.transition());
        } else {
            // A strike, a bombardment or an explosion goes off where it is: the camera is the player's throughout.
            return true;
        }
        return !boardCamera.isFraming();
    }

    private float cameraWidth() { return boardCamera.camera.viewportWidth; }

    /**
     * Whether the unit stands on the map. Off-board artillery is placed past the map's edge, as far out as its
     * off-board distance, and framing it would fly the camera away from the board the player is aiming at.
     */
    private static boolean onMap(BoardScene scene, BoardScene.Unit unit) {
        return scene.tile(unit.location().coords()) != null;
    }

    /** Only replay framing consumes HUD clearance; all ordinary navigation uses the full viewport. */
    private float playbackCameraWidth() {
        if (boardCamera.firstPerson()) { return cameraWidth(); }
        float left = ui == null ? 0 : ui.framingLeft();
        float width = ui == null ? cameraWidth() : ui.framingWidth();
        boardCamera.viewableArea(left, width);
        return width;
    }

    private void fitBoard() {
        if (ui == null) { boardCamera.fit(scene); }
        else { boardCamera.fit(scene, ui.framingLeft(), ui.framingWidth(), ui.framingBottom(), ui.framingTop()); }
    }

    /** Frame the presented action; selection changes during playback take effect after its final hold. */
    void updateCameraFocus(BoardScene scene, BoardFocus request) {
        updateCameraFocus(scene, request, null);
    }

    void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation instantAction) {
        if (!fitted) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.fit(scene);
            fitted = true;
            cameraSelection = scene.selectedId();
            cameraFollowingPlayback = false;
            // Start with the whole map, ignoring any earlier Legacy 2D board centering. Later board switches
            // can follow a unit-list click, whose pending focus request must still be applied.
            centerSequence = centerSequence < 0 ? request.sequence() : 0;
        }
        if (boardCamera.firstPerson()) {
            // Consume automatic requests during free flight so leaving it restores the user's tactical view.
            centerSequence = request.sequence();
            cameraSelection = scene.selectedId();
            cameraFollowingPlayback = false;
            cameraReviewing = false;
            return;
        }
        boolean instant = instantAction != null || history.speed() == UnitMotion.Speed.INSTANT;
        if (!history.running() && !playback.reviewing()) {
            if (centerSequence != request.sequence() && request.entityId() != Entity.NONE) {
                // A deliberate locate request takes precedence over returning to the earlier view.
                boardCamera.discardPlaybackReturn();
            } else if (boardCamera.endPlayback(!instant)
                  || instantAction != null && boardCamera.returnAfterPlayback()) {
                cameraReviewing = false;
                cameraSelection = scene.selectedId();
                centerSequence = request.sequence();
                cameraFollowingPlayback = false;
                return;
            }
        }
        if (cameraReviewing && !playback.reviewing()) {
            // Keep the event's framing when a report replay finishes. Later explicit requests still work.
            cameraReviewing = false;
            cameraSelection = scene.selectedId();
            centerSequence = request.sequence();
            cameraFollowingPlayback = false;
            boardCamera.clearPlaybackFrame();
            if (!playback.busy()) { return; }
        }
        cameraReviewing = playback.reviewing();
        boolean firing = playback.attack() != null && playback.attack().shot();
        if (!instant && !firing && playback.movement() == null) { boardCamera.clearPlaybackFrame(); }
        // Keep the last idle selection until the entire playback, including its completion hold, ends; a reviewed
        // attack is framed as a live one.
        if (playback.busy() || playback.reviewing()) {
            // A structure coming down, an elevator moving, a strike, a bombardment or an explosion leaves the camera
            // where the player has it: no framing, and nothing to fly back to.
            if (playback.transition() != null || playback.detonation() != null) {
                cameraFollowingPlayback = false;
                return;
            }
            if (!playback.reviewing()) { boardCamera.beginPlayback(); }
            if (playback.movement() != null) {
                // The complete route is already framed. Do not chase the unit or recenter it on arrival.
                cameraFollowingPlayback = false;
                // The Legacy 2D board auto-centers each move's start; do not replay that request after arrival.
                if (request.entityId() == Entity.NONE
                      && playback.movement().path().getFirst().coords().equals(request.coords())) {
                    centerSequence = request.sequence();
                }
                boardCamera.frameMovement(playback.movement(), motions.get(playback.activeEntityId()), scene, playbackCameraWidth());
                return;
            }
            cameraFollowingPlayback = true;
            if (firing) {
                boardCamera.frameAttacks(playback.attacks(), playbackCameraWidth());
                return;
            }
            var active = scene.units().stream().filter(unit -> unit.id() == playback.activeEntityId())
                  .findFirst().orElse(null);
            if (active != null && onMap(scene, active)) {
                playbackCameraWidth();
                var motion = motions.get(active.id());
                Vector3 position = motion != null && motion.isMoving() ? motion.surfacePosition(scene).cpy()
                      : BoardGeometry.center(active.location().coords(), active.location().elevation());
                playback.placeDisplacement(active, position);
                if (!boardCamera.focus.epsilonEquals(position, .001f)) {
                    boardCamera.center(position);
                }
            }
            return;
        }
        boolean requested = centerSequence != request.sequence();
        boolean changedSelection = cameraSelection != scene.selectedId()
              || cameraFollowingPlayback && instantAction == null;
        var selection = changedSelection && scene.selectedId() != Entity.NONE
              ? scene.units().stream().filter(unit -> unit.id() == scene.selectedId()).findFirst().orElse(null) : null;
        if (selection == null && requested && request.entityId() != Entity.NONE) {
            selection = scene.units().stream().filter(unit -> unit.id() == request.entityId()).findFirst().orElse(null);
        }
        Coords center = selection == null && requested && request.entityId() == Entity.NONE ? request.coords() : null;
        centerSequence = request.sequence();
        cameraSelection = scene.selectedId();
        cameraFollowingPlayback = false;
        if (selection != null && !onMap(scene, selection)) {
            // Off-board artillery taking its turn: the player aims at the map, so the camera stays on it.
            LOGGER.debug("[SelectionCamera] {} is off the map, the camera stays", selection.name());
        } else if (selection != null) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.frameSelection(selection, cameraWidth());
        } else if (instantAction instanceof BoardScene.Combat combat && combat.result().kind() == ResolvedAttack.Kind.SHOT) {
            boardCamera.frameAttacks(List.of(new UnitAttack(combat)), playbackCameraWidth());
        } else if (instantAction instanceof BoardScene.Detonation) {
            // As when it plays: the camera stays.
            boardCamera.viewableArea(0, cameraWidth());
        } else if (instantAction != null) {
            scene.units().stream().filter(unit -> unit.id() == instantAction.entityId()).findFirst()
                  .filter(unit -> onMap(scene, unit))
                  .ifPresent(unit -> boardCamera.frameSelection(unit, playbackCameraWidth()));
        } else if (center != null && scene.tile(center) != null) {
            boardCamera.viewableArea(0, cameraWidth());
            boardCamera.frameLocation(BoardGeometry.center(center, scene.tile(center).elevation()), cameraWidth());
        } else {
            // Rebase the orbit pivot without moving the displayed board when replay control ends.
            boardCamera.viewableArea(0, cameraWidth());
        }
        if (instant) {
            // Replace any old transition with the final requested view before snapping it, never visiting each event.
            if (boardCamera.isFraming()) { boardCamera.advance(BoardCamera.CAMERA_FRAMING_SECONDS); }
            boardCamera.clearPlaybackFrame();
        }
    }

    private void aimAttack() {
        if (unitModels == null) { return; }
        Set<Integer> aimed = new java.util.HashSet<>();
        for (var attack : playback.attacks()) {
            if (!attack.shot() && attack.aimWeight() <= 0) { continue; }
            var unit = attack.event.attacker();
            if (!aimed.add(unit.id())) { continue; }
            var key = unit.id() + ":" + unit.part();
            var animator = animators.get(key);
            if (animator == null || !unitInstances.containsKey(key) || unit.model() == null) { continue; }
            var model = unitModels.loaded(unit.model(), unit.id(), unit.part());
            if (model == null) { continue; }
            var target = attack.event.target();
            var targetInstance = target == null ? null : unitInstances.get(target.id() + ":" + target.part());
            var origin = UnitAttack.center(unitInstances.get(key), unit.location(), new Vector3());
            var endpoint = attack.shot() ? attack.endpoint(targetInstance, origin, new Vector3())
                  : attack.contact(targetInstance, origin, unitPicking, new Vector3());
            if (attack.shot()) {
                animator.aimShots(model, unit, playback.attacks(), shot -> shot.event.target() == null ? null
                      : unitInstances.get(shot.event.target().id() + ":" + shot.event.target().part()));
            } else { animator.aim(model, unit, attack, endpoint, targetInstance); }
            for (var displayed : scene.units()) {
                if (displayed.id() == unit.id()) {
                    unitAnchors.put(displayed, model.anchor(unitInstances.get(key), boardCamera.camera));
                    break;
                }
            }
        }
    }

    /** One floating visual's drift for the current frame: a draw-time offset, never part of the game state. */
    private record Hover(ModelInstance instance, float offset) { }

    /** Apply the same hover transform before drawing, feature fading, and shadow capture. */
    private void applyHover() {
        for (Hover drifting : hover) {
            Vector3 center = drifting.instance().transform.getTranslation(new Vector3());
            drifting.instance().transform.setTranslation(center.add(0, 0, drifting.offset()));
        }
    }

    /**
     * Vertical hover offset in world units for an airborne visual at the current animation time: a slow sine wave that
     * each unit starts at a different phase, so the floating tokens drift as a loose wave instead of rising and
     * falling together. A grounded unit (elevation or altitude 0) never gets an offset. This only moves the rendered
     * token; no game state changes.
     */
    static float hoverOffset(float seconds, int id, int part) {
        return UnitAnimator.hoverOffset(seconds, id, part);
    }

    /** The animation clock's step: none while the playback is paused, unless a review plays its attack meanwhile. */
    private float animationSeconds() {
        return !playback.clockRunning() ? 0
              : (float) (Gdx.graphics.getDeltaTime() * history.speed().rate * timeScale);
    }

    /**
     * A shot that hits can slow before landing ({@link #BULLET_TIME}). A killing blow ({@link #DRAMATIC_DEATH}) slows
     * to 0.1 just before its scheduled death effect. Impact restores full speed immediately.
     * Physical attacks, crew loss and deaths without a ranged cause never trigger the effect.
     * The camera and the HUD keep real time.
     */
    private void updateSlowMotion() {
        if (history.speed() == UnitMotion.Speed.INSTANT || !history.running() && !playback.reviewing()) {
            timeScale = 1;
            return;
        }
        timeScale = UnitSlowMotion.scale(playback.attacks(), tuning.dramaticDeath());
    }

    private boolean hasInfantryTransports(BoardScene.Unit unit) {
        if (unitModels == null || unit == null || unit.sensorContact()) {
            return false;
        }
        var model = unitModels.get(unit.model(), unit.id(), unit.part());
        return model != null && model.rigs().stream().anyMatch(UnitRig::transport);
    }

    /** Evaluate the final captured formation even when several queued moves are skipped in one frame. */
    private void completeMovement(BoardScene.Movement movement) {
        var unit = movement.unit();
        if (unitModels == null || unit == null || unit.sensorContact() || unit.model() == null || unit.model().state() == null) {
            return;
        }
        var model = unitModels.get(unit.model(), unit.id(), unit.part());
        if (model == null || model.rigs().isEmpty()) {
            return;
        }
        String key = unit.id() + ":" + unit.part();
        var instance = unitInstances.get(key);
        if (instance == null || instance.model != model.instance.model) {
            instance = newUnitInstance(key, model);
        }
        animators.computeIfAbsent(key, ignored -> newAnimator()).apply(model, instance, unit,
              motions.get(unit.id()).sample(), hoverClock, 0, true, unit.model().twist());
    }

    /** Level a floating visual's stem ends at, or NaN when no stem is drawn for this token at this time. */
    static float tetherGround(BoardScene.Unit unit, int tileElevation, Vector3 center, boolean moving) {
        float ground = tileElevation * BoardGeometry.level();
        return unit.airborne() && !moving && center.z > ground ? ground : Float.NaN;
    }

    /**
     * Faint stems from each floating visual's center down to the center of the hex it occupies, so an airborne token
     * beside a hill or another raised tile still reads as belonging to the hex under it. A grounded token already
     * covers its hex, and a moving token is between hexes, so neither gets a stem. Elevated LOS endpoints use the
     * same stems in every native board mode.
     */
    private void renderTethers() {
        Vector3 center = new Vector3();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        tethers.setProjectionMatrix(boardCamera.camera.combined);
        tethers.begin(ShapeRenderer.ShapeType.Line);
        tethers.setColor(TETHER_COLOR);
        for (BoardScene.Unit unit : scene.units()) {
            BoardScene.Tile tile = scene.tile(unit.location().coords());
            ModelInstance instance = unitInstances.get(unit.id() + ":" + unit.part());
            UnitMotion motion = motions.get(unit.id());
            if (tile == null || instance == null) {
                continue;
            }
            instance.transform.getTranslation(center);
            renderTether(center, tetherGround(unit, tile.elevation(), center, motion != null && motion.isMoving()));
        }
        var ruler = scene.tactical().ruler();
        if (ruler != null) {
            renderRulerTether(ruler.start(), ruler.startHeight());
            if (ruler.end() != null) { renderRulerTether(ruler.end(), ruler.endHeight()); }
        }
        if (source.isEditor() && source.editorState() != null && terrain != null) { renderObjectTethers(source.editorState()); }
        tethers.end();
        Gdx.gl.glDepthMask(true);
    }

    /**
     * The same stems for the editor's selected objects that do not rest on their support, during drags too: from the
     * drawn anchor down by a positive offset to the support (the ground where the support is missing), or from a fixed
     * level down to the owning hex's level.
     */
    private void renderObjectTethers(megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
        for (var item : state.selection()) {
            if (item.object().isEmpty() || scene.tile(item.coords()) == null) { continue; }
            var drawn = frameObjects(item.coords()).stream()
                  .filter(value -> value.id().equals(item.object())).findFirst().orElse(null);
            if (drawn == null) { continue; }
            var placement = drawn.placement();
            renderTether(drawn.anchor(), placement.mode().equals("absolute") ? BoardTacticalGeometry.floatingZ(scene, item.coords())
                  : drawn.anchor().z - (float) (placement.offset() * BoardGeometry.level()));
        }
    }

    /**
     * The installed objects of a hex whose selection outlines, hover boxes or tethers this frame draws: each hex is
     * listed once per frame for both outline passes and the tethers.
     */
    private List<GpuTerrain.EditorObject> frameObjects(Coords coords) {
        return frameObjects.computeIfAbsent(coords, terrain::editorObjects);
    }

    /**
     * The handle that turns the Select tool's selection about the vertical axis through its {@code pivot}: one object or
     * decal, or one whole group about its members' centre. One object's handle attaches to its local +X bottom edge and turns
     * with its box. A group's handle uses the collective world envelope. The circle stays a readable size on screen.
     * {@code from} is what a drag's turn adds to: the object's rotation, or 0 for a group, whose value is its turn.
     */
    private record RotationHandle(Vector3 pivot, Vector3 base, Vector3 tip, float radius, boolean group, double from) { }

    /** The selection's rotation handle; null for hexes, routes, other multiple selections and objects not drawn yet. */
    private RotationHandle rotationHandle() {
        var state = source.isEditor() && scene != null && terrain != null ? source.editorState() : null;
        if (state == null || state.tool() != megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT
              || state.selection().isEmpty() || state.selection().size() > 1 && state.group().isEmpty()) { return null; }
        var box = new com.badlogic.gdx.math.collision.BoundingBox().inf();
        OrientedBoundingBox outline = null;
        Vector3 pivot = new Vector3();
        double from = 0;
        for (var item : state.selection()) {
            var tile = scene.tile(item.coords());
            var object = item.object().isEmpty() || tile == null ? null : tile.features().stream().map(BoardScene.Feature::decoration)
                  .filter(decoration -> decoration != null && decoration.id().equals(item.object())).findFirst().orElse(null);
            var drawn = object == null ? null
                  : frameObjects(item.coords()).stream().filter(value -> value.id().equals(item.object())).findFirst().orElse(null);
            // A route is laid by painting its hexes; it does not turn.
            if (drawn == null || megamek.common.board.RailRoute.isRoute(object)) { return null; }
            box.ext(drawn.bounds());
            outline = drawn.outline();
            pivot.add(drawn.anchor().x, drawn.anchor().y, 0);
            from = object.rotation();
        }
        var camera = boardCamera.camera;
        boolean group = state.selection().size() > 1;
        Vector3 base, outward;
        if (group) {
            outward = new Vector3(camera.direction).crs(camera.up); outward.z = 0; outward.nor();
            Vector3 centre = box.getCenter(new Vector3());
            float reach = Math.abs(outward.x) * box.getWidth() / 2 + Math.abs(outward.y) * box.getHeight() / 2;
            base = new Vector3(centre.x, centre.y, box.min.z).mulAdd(outward, reach);
        } else {
            var local = outline.getBounds();
            base = local.getCenter(new Vector3()); base.x = local.max.x; base.z = local.min.z;
            base.mul(outline.getTransform());
            outward = new Vector3(Vector3.X).rot(outline.getTransform()).nor();
        }
        pivot.scl(1f / state.selection().size());
        pivot.z = base.z;
        // The pivot's depth stays fixed through a Z turn; sizing at the moving side made a perspective grip drift
        // off the pointer's arc. Use the shared camera scale instead of subtracting nearly identical projections.
        float unit = layoutScale * BoardCamera.worldUnitsPerPixel(camera, pivot);
        Vector3 tip = new Vector3(base).mulAdd(outward, HANDLE_LENGTH * unit);
        Vector3 screen = camera.project(new Vector3(tip), 0, 0, camera.viewportWidth, camera.viewportHeight);
        if (!(unit > 0) || !Float.isFinite(unit) || screen.z < 0 || screen.z > 1) { return null; }
        float radius = HANDLE_RADIUS * layoutScale * BoardCamera.worldUnitsPerPixel(camera, tip);
        pivot.z = tip.z;
        return new RotationHandle(pivot, base, tip, radius,
              group, group ? 0 : from);
    }

    /** Selection boxes and handles are editor controls: terrain, water and later scene overlays must not hide them. */
    private void renderEditorObjectControls() {
        var state = source.isEditor() ? source.editorState() : null;
        if (state == null) { return; }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        lines.setProjectionMatrix(boardCamera.camera.combined);
        lines.begin(ShapeRenderer.ShapeType.Line);
        try {
            lines.setColor(.45f, 1, .8f, 1);
            for (var item : state.selection()) {
                if (item.object().isEmpty()) { continue; }
                frameObjects(item.coords()).stream().filter(object -> object.id().equals(item.object()))
                      .forEach(object -> drawObjectBox(object.outline()));
            }
            // The objects hovered in the side view (every one of a shared pill) or Layers.
            List<String> hoveredObjects = ui instanceof GpuMapHud map ? map.editorHoverObjects() : List.of();
            if (!hoveredObjects.isEmpty() && state.selected() != null) {
                lines.setColor(1, 1, 1, 1);
                frameObjects(state.selected()).stream().filter(object -> hoveredObjects.contains(object.id()))
                      .forEach(object -> drawObjectBox(object.outline()));
            }
            drawRotationHandle(1);
        } finally {
            lines.end();
            Gdx.gl.glDepthMask(true);
        }
    }

    /** The eight local corners follow the same full transform as the drawn mesh, including pitch, roll and stretch. */
    private void drawObjectBox(OrientedBoundingBox box) {
        Vector3[] corners = box.getVertices();
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) { lines.line(corners[i], corners[i | bit]); }
            }
        }
    }

    /**
     * The rotation handle: its line, and its circle facing the camera; white while hovered or held. A held handle turns
     * about its pivot with the selection, so it stays under the pointer.
     */
    private void drawRotationHandle(float alpha) {
        RotationHandle held = boardInput.rotationGrab, handle = rotationHandle();
        if (handle == null) { return; }
        if (held != null && held.group()) {
            float turn = (float) (boardInput.rotationSent - held.from());
            Vector3 base = new Vector3(held.base()).sub(held.pivot()).rotate(Vector3.Z, turn).add(held.pivot());
            Vector3 tip = new Vector3(held.tip()).sub(held.pivot()).rotate(Vector3.Z, turn).add(held.pivot());
            handle = new RotationHandle(held.pivot(), base, tip, held.radius(), held.group(), held.from());
        }
        boolean lit = held != null || boardInput.onHandle(handle, Gdx.input.getX(), Gdx.input.getY());
        lines.setColor(lit ? 1 : .45f, 1, lit ? 1 : .8f, alpha);
        Vector3 toward = new Vector3(handle.tip()).sub(handle.base()).nor();
        lines.line(handle.base(), new Vector3(handle.tip()).mulAdd(toward, -handle.radius()));
        var camera = boardCamera.camera;
        Vector3 across = new Vector3(camera.direction).crs(camera.up).nor();
        Vector3 up = new Vector3(across).crs(camera.direction).nor();
        Vector3 previous = new Vector3(handle.tip()).mulAdd(across, handle.radius());
        for (int i = 1; i <= 24; i++) {
            float angle = i * MathUtils.PI2 / 24;
            Vector3 next = new Vector3(handle.tip()).mulAdd(across, handle.radius() * MathUtils.cos(angle))
                  .mulAdd(up, handle.radius() * MathUtils.sin(angle));
            lines.line(previous, next);
            previous = next;
        }
    }

    /** Keep the elevated anchor and its drop footprint visible as one column throughout the drag. */
    private void editorProjection(Coords coords, float top) {
        if (scene.tile(coords) == null) { return; }
        float ground = BoardTacticalGeometry.floatingZ(scene, coords);
        ring(coords, ground);
        if (top <= ground + BoardGeometry.hexScale()) { return; }
        ring(coords, top);
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 point = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center, HOVER_HEX_INSET);
            lines.line(point.x, point.y, ground, point.x, point.y, top);
        }
    }

    private void renderRulerTether(Coords coords, int height) {
        BoardScene.Tile tile = scene.tile(coords);
        if (tile != null) { renderTether(BoardGeometry.center(coords, height), tile.elevation() * BoardGeometry.level()); }
    }

    private void renderTether(Vector3 center, float ground) {
        if (center.z > ground) { tethers.line(center.x, center.y, center.z, center.x, center.y, ground); }
    }

    /** One render-only selection shared by color, depth, outlines and shadows. */
    void updateEquipmentDetail() {
        Set<Integer> detailedUnits = new java.util.HashSet<>();
        detailedUnits.add(scene.selectedId());
        for (var attack : playback.attacks()) {
            detailedUnits.add(attack.event.entityId());
            if (attack.event.target() != null) { detailedUnits.add(attack.event.target().id()); }
        }
        for (var unit : scene.units()) {
            if (unitInstances.get(unit.id() + ":" + unit.part()) instanceof GpuUnitInstance instance) {
                instance.equipmentDetail(boardCamera.camera, detailedUnits.contains(unit.id()));
            }
        }
    }

    /** A fresh instance shows no tint, twist or damage yet, so everything remembered about the old one is dropped. */
    private ModelInstance newUnitInstance(String key, GpuUnitModel visual) {
        ModelInstance instance = new GpuUnitInstance(visual);
        unitInstances.put(key, instance);
        unitTints.remove(key);
        upperBodyTurns.remove(key);
        armFlips.remove(key);
        unitDamage.remove(key);
        equipmentAppearance.remove(key);
        return instance;
    }

    /**
     * Takes lost arms off the unit's model and burns out its other lost locations. Starts from a fresh instance
     * instead of undoing the old damage, which also covers a location that a game master has repaired.
     */
    private ModelInstance showDamage(String key, GpuUnitModel visual, BoardScene.Unit unit, BoardScene.LocationDamage damage) {
        ModelInstance instance = newUnitInstance(key, visual);
        List<String> missing = UnitDamageDisplay.show(instance, damage);
        unitDamage.put(key, damage);
        LOGGER.debug("[GpuDamage] {}: taken off {}, burnt out {}, no part in the model for {}",
              unit.name(), damage.removed(), damage.wrecked(), missing);
        return instance;
    }

    /**
     * Shows a torso twist on a model whose upper body turns on its own. The scene gives the unit's torso facing, as
     * the 2D unit sprite does, so the legs are that facing less the twist.
     *
     * @param twist hexsides the torso is turned clockwise from the legs
     *
     * @return the degrees to take off the displayed facing to get the facing of the legs
     */
    /**
     * Swings one unit's arms toward the pose its firing arc calls for, a step at a time, and poses the model again
     * when the shown angle moves. The game state holds the arc; this only shows it.
     */
    private void flipArms(GpuUnitModel visual, ModelInstance instance, String key, BoardScene.Unit unit) {
        var state = unit.model().state();
        boolean wanted = (state != null) && (state.pose() != null) && state.pose().armsFlipped();
        ArmFlip flip = armFlips.computeIfAbsent(key, ignored -> new ArmFlip());
        boolean previous = flip.flipped();
        if (flip.advance(wanted, history.speed() == UnitMotion.Speed.INSTANT ? Float.MAX_VALUE : animationSeconds())) {
            visual.flipArms(instance, flip.degrees());
        }
        if (previous != wanted) {
            LOGGER.debug("[GpuArmFlip] {}: arms now {}", unit.name(), wanted ? "flipped to the rear" : "forward");
        }
    }

    private float turnUpperBody(GpuUnitModel visual, ModelInstance instance, String key, BoardScene.Unit unit, int twist) {
        UpperBodyTurn turn = upperBodyTurns.get(key);
        if (turn == null) {
            turn = new UpperBodyTurn();
            upperBodyTurns.put(key, turn);
        }
        int previousTwist = turn.hexsides();
        if (turn.advance(twist, history.speed() == UnitMotion.Speed.INSTANT ? Float.MAX_VALUE : animationSeconds())) {
            visual.turnUpperBody(instance, turn.degrees());
        }
        if (previousTwist != twist) {
            LOGGER.debug("[GpuTwist] {}: upper body now {} hexside(s) clockwise of the legs, was {}",
                  unit.name(), twist, previousTwist);
        }
        return turn.targetDegrees();
    }

    private void renderUnits(boolean thermal) {
        boolean tactical = boardCamera.tactical();
        // The Tactical View's icons show their own colours, whatever falls on them.
        Environment light = tactical ? null : terrain.environment();
        unitBatch.begin(boardCamera.camera);
        List<ModelInstance> depthless = new ArrayList<>();
        for (BoardScene.Unit unit : unitAnchors.keySet()) {
            ModelInstance instance = unitInstances.get(unit.id() + ":" + unit.part());
            if (!unitGhosts.containsKey(instance) && boardCamera.camera.frustum.boundsInFrustum(unitBounds.get(instance))) {
                if (unit.sensorContact()) {
                    unitBatch.render(instance);
                } else if (thermal) {
                    wireframe.units.add(instance, unit, unitBounds.get(instance));
                } else if (tactical || instance.userData instanceof GpuUnitShader.Shown shown && shown.opacity < 1) {
                    depthless.add(instance);
                } else {
                    unitBatch.render(instance, light);
                }
            }
        }
        for (var casualty : casualties.casualties()) {
            var bounds = unitBounds.get(casualty.instance);
            if (!boardCamera.camera.frustum.boundsInFrustum(bounds)) { continue; }
            if (thermal) { wireframe.units.add(casualty.instance, casualty.unit, bounds); }
            else if (tactical || casualty.opacity() < 1) { depthless.add(casualty.instance); }
            else { unitBatch.render(casualty.instance, light); }
        }
        unitBatch.end();
        // What writes no depth, a unit fading out or an icon, covers what lies behind it: after the units, the farthest first.
        var eye = boardCamera.camera.position;
        depthless.sort(Comparator.comparingDouble(instance -> -unitBounds.get(instance).getCenter(new Vector3()).dst2(eye)));
        unitBatch.begin(boardCamera.camera);
        for (var instance : depthless) {
            unitBatch.render(instance, light);
            unitBatch.flush();
        }
        // The board's markers last: a warning shows over the Tactical View's icons, which write no depth. Every marker
        // writes normal scene depth, independently of its optional see-through outline.
        markers.instances().forEach(unitBatch::render);
        unitBatch.end();
        // An icon's depth range (MeepleVisual.ICON_DEPTH) stays behind for the direct GL passes.
        Gdx.gl.glDepthRangef(0, 1);
        if (thermal) { wireframe.units.render(boardCamera.camera); }
    }

    /**
     * The board markers' labels in screen space, clear of the markers. The units' labels are the HUD's nameplates;
     * MegaMek's unit annotations are not drawn here (I3 X3).
     */
    private void renderAnnotations() {
        annotationBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
        annotationBatch.begin();
        markers.renderLabels(annotationBatch, boardCamera.camera, layoutScale,
              markers.labelObstacles(boardCamera.camera));
        if (editorBadges != null) {
            editorBadges.render(annotationBatch, boardCamera.camera, layoutScale, scene,
                  source.editorBadges(), terrain::roofBounds, hovered);
        }
        if (source.isEditor() && editorPreviewShown()) { renderEditorPreviewLabel(); }
        if (source.isEditor()) { renderAuthoredHeights(); }
        annotationBatch.end();
    }

    /** The editor preview label's capital height, in layout units. */
    private static final float PREVIEW_LABEL_HEIGHT = 11;

    private void renderAuthoredHeights() {
        var selected = authoredSelection();
        if (selected.isEmpty()) { return; }
        var font = theme.skin.getFont("default-font");
        float scale = font.getData().scaleX;
        font.getData().setScale(PREVIEW_LABEL_HEIGHT * layoutScale * scale / font.getCapHeight());
        try {
            for (var tile : selected) {
                if (tile.cover().height() <= 0) { continue; }
                Vector3 point = boardCamera.camera.project(BoardGeometry.center(tile.coords(), tile.elevation() + tile.cover().height() + .25f),
                      0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                if (point.z < 0 || point.z > 1) { continue; }
                String label = Messages.getString("BoardEditor.derivedHeightLabel", tile.cover().height());
                font.setColor(0, 0, 0, .85f);
                font.draw(annotationBatch, label, point.x + layoutScale, point.y - layoutScale, 0, Align.center, false);
                font.setColor(1, .8f, .35f, 1);
                font.draw(annotationBatch, label, point.x, point.y, 0, Align.center, false);
            }
        } finally { font.getData().setScale(scale); font.setColor(Color.WHITE); }
    }

    /** The editor preview's label over its hex: what the click brings, or for Erase what goes, in red. */
    private void renderEditorPreviewLabel() {
        var preview = source.editorPreview();
        Vector3 point = boardCamera.camera.project(new Vector3(editorPreview.labelPoint()), 0, 0,
              boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
        if (preview.label().isEmpty() || point.z < 0 || point.z > 1) { return; }
        var font = theme.skin.getFont("default-font");
        float scale = font.getData().scaleX;
        font.getData().setScale(PREVIEW_LABEL_HEIGHT * layoutScale * scale / font.getCapHeight());
        try {
            font.setColor(0, 0, 0, .85f);
            font.draw(annotationBatch, preview.label(), point.x + layoutScale, point.y - layoutScale, 0, Align.center, false);
            font.setColor(preview.erase() ? UiTheme.CORAL : Color.WHITE);
            font.draw(annotationBatch, preview.label(), point.x, point.y, 0, Align.center, false);
        } finally {
            font.getData().setScale(scale);
            font.setColor(Color.WHITE);
        }
    }

    static Rectangle spreadAnnotation(Rectangle preferred, List<Rectangle> occupied,
          float viewportWidth, float viewportHeight, float gap) {
        float maxX = Math.max(0, viewportWidth - preferred.width);
        float maxY = Math.max(0, viewportHeight - preferred.height);
        Rectangle origin = new Rectangle(MathUtils.clamp(preferred.x, 0, maxX),
              MathUtils.clamp(preferred.y, 0, maxY), preferred.width, preferred.height);
        Rectangle result = new Rectangle(origin);
        float nearest = Float.POSITIVE_INFINITY;
        List<Float> columns = new ArrayList<>(List.of(origin.x, 0f, maxX));
        for (Rectangle bounds : occupied) {
            columns.add(MathUtils.clamp(bounds.x - gap - origin.width, 0, maxX));
            columns.add(MathUtils.clamp(bounds.x + bounds.width + gap, 0, maxX));
        }
        var sorted = occupied.stream().sorted(Comparator.comparingDouble(bounds -> bounds.y)).toList();
        for (float left : columns) {
            float bottom = 0;
            for (int index = 0; index <= sorted.size(); index++) {
                Rectangle obstacle = index < sorted.size() ? sorted.get(index) : null;
                if (obstacle != null && (left + origin.width + gap <= obstacle.x
                      || left >= obstacle.x + obstacle.width + gap)) {
                    continue;
                }
                float top = obstacle == null ? maxY : Math.min(maxY, obstacle.y - gap - origin.height);
                if (bottom <= top) {
                    float candidateY = MathUtils.clamp(origin.y, bottom, top);
                    float distance = (left - origin.x) * (left - origin.x)
                          + (candidateY - origin.y) * (candidateY - origin.y);
                    if (distance < nearest) {
                        nearest = distance;
                        result.setPosition(left, candidateY);
                    }
                }
                if (obstacle != null) {
                    bottom = Math.max(bottom, obstacle.y + obstacle.height + gap);
                }
            }
        }
        return result;
    }

    private void renderHexText(boolean wireframeView) {
        var overlays = source.uiPreferences().overlays();
        hexText.update(scene, theme.skin.getFont("default-font"), theme.skin.getFont(GpuBoardSkin.OUTLINE_FONT),
              terrain::roofBounds, boardCamera.tactical(), overlays.hexTypes(),
              overlays.hexLabelOutline() || wireframeView, terrain::fallTaken, terrain.fallsRevision(), comingTiles);
        hexText.render(annotationBatch, boardCamera, atmosphere.depthTexture(), unitVisibility.depthTexture(), 0,
              atmosphere, wireframeView);
    }

    /** The camera zoom of {@code notches} of the mouse wheel, on the board and on the minimap. */
    static float wheelZoom(float notches) {
        return (float) Math.pow(1.12, notches);
    }

    /**
     * The height of the hovered hex's outline on a building floor under the pointer (hover column): half
     * a hex above the floor's level when that is more than a hex above the hex's own ground, else NaN.
     */
    private float hoverTop() {
        return scene == null ? Float.NaN : GpuHexHover.top(scene, hovered, hoverZ);
    }

    private boolean hovers(BoardScene.Unit unit) {
        return hovered != null && unit.footprint().contains(hovered);
    }

    /** Held camera binds share the existing focus and release routing; free flight uses wall-clock movement. */
    private void advanceFirstPerson(float seconds, float speed) {
        float forward = 0, sideways = 0, vertical = 0, pitch = 0;
        for (KeyCommandBind command : cameraKeys.values()) {
            switch (command) {
                case SCROLL_NORTH -> forward += 1;
                case SCROLL_SOUTH -> forward -= 1;
                case SCROLL_EAST -> sideways += 1;
                case SCROLL_WEST -> sideways -= 1;
                case CAMERA_ROTATE_LEFT -> vertical -= 1;
                case CAMERA_ROTATE_RIGHT -> vertical += 1;
                case CAMERA_TILT_UP -> pitch += 1;
                case CAMERA_TILT_DOWN -> pitch -= 1;
                default -> { }
            }
        }
        float elapsed = MathUtils.clamp(seconds, 0, .1f) * speed;
        if (pitch != 0) { boardCamera.look(0, pitch * BoardCameraControls.TILT_DEGREES_PER_SECOND * elapsed); }
        boardCamera.fly(forward, sideways, vertical, BoardGeometry.height() * FLIGHT_HEXES_PER_SECOND * elapsed);
    }

    Vector3 screenPosition(Coords coords) {
        Vector3 point = boardCamera.camera.project(BoardGeometry.center(coords, scene.tile(coords).elevation()),
              0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    private final class BoardInput extends InputAdapter {
        private long gestureBoardGeneration;
        private int dragX;
        private int dragY;
        private boolean panning;
        private boolean orbiting;
        private boolean dragged;
        private boolean boardGesture;
        /** Every end of a gesture (release, focus loss, a modal, a new board) goes through {@link #reset()}. */
        private final UiCursorCapture cursorCapture = new UiCursorCapture();
        /** Frozen at the press: an editor LOS gesture must never paint, even if Alt is released first. */
        private boolean measurementGesture;
        /** B+click samples a whole hex; releasing B before the mouse must not invoke the active tool. */
        private boolean brushPickerGesture;
        /**
         * A Select press that grabbed no selected item: its drag draws a selection box (Shift adds, Ctrl removes) and a
         * release without a drag is a click. Only a press on the selection, or inside a selected object's outline,
         * moves it.
         */
        private boolean marquee;
        private int gestureButton;
        private GpuTerrain.DecorationHit editorGrab;
        private boolean editorAuthoredGrab;
        /** A press on the selection's rotation handle: its drag turns the selection, one undo step. */
        private RotationHandle rotationGrab;
        /** The pointer's angle about the grabbed handle's pivot at the press, and the rotation last sent. */
        private double rotationPress, rotationSent;
        private Coords editorHexGrab;
        private float editorPlaneZ;
        private float editorGrabX, editorGrabY;
        private int gestureModifiers;
        private int startX;
        private int startY;
        private Coords elevationScrollHex;
        private float elevationScroll;
        private record KeyPress(int code, int modifiers) { }
        private final Map<Integer, KeyPress> pressedKeys = new HashMap<>();

        /**
         * {@code floorZ}: the height of the walkable surface under the pointer in the picked hex ({@link
         * GpuTerrain#walkableHit}), which the hover ring marks; NaN without one. {@code onTerrain}: no unit or marker
         * takes priority over the terrain hit.
         */
        private record Pick(Coords coords, int entityId, float floorZ, boolean onTerrain) {
            /** The height a click measures to: the ring's, or NaN (the unit's own) when a unit or marker takes it. */
            float pointedZ() { return onTerrain ? floorZ : Float.NaN; }
        }

        private void editorPointer(int x, int y, boolean drag) {
            if (scene == null || terrain == null) { return; }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var state = source.editorState();
            boolean additive = (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
            if (!drag || additive) {
                editorHexGrab = null;
                editorGrab = state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT
                      ? objectPress(ray, state) : null;
            }
            if (drag && !additive && editorHexGrab != null && Math.abs(ray.direction.z) > .00001f) {
                Vector3 point = ray.getEndPoint(new Vector3(), (editorPlaneZ - ray.origin.z) / ray.direction.z);
                var target = BoardGeometry.tile(scene, point.x - editorGrabX, point.y - editorGrabY);
                if (target != null) {
                    hovered = target.coords(); hoverZ = Float.NaN; source.setHover(hovered);
                    source.editorPointer(target.coords(), 0, 0, true, null, false, gestureBoardGeneration);
                }
                return;
            }
            if (editorGrab != null && Math.abs(ray.direction.z) > .00001f) {
                Vector3 point = ray.getEndPoint(new Vector3(), (editorGrab.anchorZ() - ray.origin.z) / ray.direction.z);
                Coords at = editorGrab.coords();
                if (!drag || additive) {
                    var object = scene.tiles().stream().flatMap(tile -> tile.features().stream()).map(BoardScene.Feature::decoration)
                          .filter(java.util.Objects::nonNull).filter(d -> d.id().equals(editorGrab.id())).findFirst().orElse(null);
                    if (object == null) { return; }
                    editorAuthoredGrab = megamek.common.board.BuildingVolumes.of(object.asset()).present();
                    editorGrabX = point.x - BoardGeometry.centerX(at) - (float) object.x() * BoardGeometry.width();
                    editorGrabY = point.y - BoardGeometry.centerY(at) - (float) object.y() * BoardGeometry.height();
                }
                var target = BoardGeometry.tile(scene, point.x - editorGrabX, point.y - editorGrabY);
                if (target == null && !editorAuthoredGrab) { return; }
                hovered = target == null ? at : target.coords(); hoverZ = Float.NaN; source.setHover(hovered);
                source.editorPointer(at, (point.x - editorGrabX - BoardGeometry.centerX(at)) / BoardGeometry.width(),
                      (point.y - editorGrabY - BoardGeometry.centerY(at)) / BoardGeometry.height(), drag, editorGrab.id(), additive, gestureBoardGeneration);
                return;
            }
            var hit = editorHit(ray, state);
            if (hit == null) {
                if (!drag && gestureModifiers == 0) {
                    source.editorCommand(new Command(Action.CLEAR_SELECTION), gestureBoardGeneration);
                }
                return;
            }
            // A route drawn by hand: the hover, which shows its preview, follows the drag (mouseMoved leaves it alone).
            if (drawingRoute()) { hovered = hit.coords(); hoverZ = Float.NaN; source.setHover(hovered); }
            Vector3 point = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
            if (!drag && !additive && state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT) {
                editorHexGrab = hit.coords(); editorPlaneZ = point.z;
                editorGrabX = point.x - BoardGeometry.centerX(hit.coords());
                editorGrabY = point.y - BoardGeometry.centerY(hit.coords());
            }
            double hexX = (point.x - BoardGeometry.centerX(hit.coords())) / BoardGeometry.width();
            double hexY = (point.y - BoardGeometry.centerY(hit.coords())) / BoardGeometry.height();
            // A drag moves the preview's pointer too: a route stroke's next step heads where the pointer goes.
            source.editorHover(hit.coords(), hexX, hexY, hit.receiver());
            source.editorPointer(hit.coords(), hexX, hexY, drag, null, additive, hit.receiver(),
                  (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0, gestureBoardGeneration);
        }

        /** The hit a Paint, Erase or Select event along {@code ray} takes: an object brush's support, else the ground. */
        private BoardGeometry.Hit editorHit(com.badlogic.gdx.math.collision.Ray ray,
              megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
            if (state != null && state.activeBrush().way()
                  && (state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.PAINT
                  || state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.ERASE)) {
                return terrain.walkableHit(scene, ray);
            }
            return state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.PAINT
                  && state.activeBrush().object() != null ? terrain.placementHit(scene, ray) : terrain.selectionHit(scene, ray);
        }

        /**
         * Tells the source where a Paint or Erase event at the pointer {@code x, y} would land, the hit {@link #editorPointer}
         * takes, for the editor's preview; nothing over the HUD, off the board or for the other tools.
         */
        private void editorHover(int x, int y, boolean overHud) {
            var state = source.editorState();
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var hit = overHud || scene == null || terrain == null || state == null
                  || state.tool() != megamek.client.ui.boardeditor.BoardEditorSession.Tool.PAINT
                  && state.tool() != megamek.client.ui.boardeditor.BoardEditorSession.Tool.ERASE ? null : editorHit(ray, state);
            if (hit == null) { source.editorHover(null, 0, 0, null); return; }
            Vector3 point = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
            source.editorHover(hit.coords(), (point.x - BoardGeometry.centerX(hit.coords())) / BoardGeometry.width(),
                  (point.y - BoardGeometry.centerY(hit.coords())) / BoardGeometry.height(), hit.receiver());
        }

        /**
         * The object a Select press picks: over a hex selection, a press on a selected hex picks that hex (null); a press
         * inside a selected object's outline (its installed bounds) picks that object even where its mesh is open;
         * otherwise the nearest object hit, or null for the ground. A decal's paint is picked exactly, without its
         * outline, so an object standing on a selected decal stays pickable.
         */
        private GpuTerrain.DecorationHit objectPress(com.badlogic.gdx.math.collision.Ray ray,
              megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
            var selection = state.selection();
            if (!selection.isEmpty() && selection.getFirst().object().isEmpty()) {
                var ground = terrain.selectionHit(scene, ray);
                if (ground != null && selection.contains(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(ground.coords(), ""))) {
                    return null;
                }
            }
            var hit = terrain.decorationHit(scene, ray);
            if (hit != null && selection.contains(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(hit.coords(), hit.id()))) {
                return hit;
            }
            for (var item : selection) {
                if (item.object().isEmpty()) { continue; }
                for (var object : terrain.editorObjects(item.coords())) {
                    if (object.id().equals(item.object()) && !object.decal()) {
                        var box = object.outline();
                        // Test in object space: the oriented-box shortcut assumes unit axes and mis-picks scaled props.
                        var local = new com.badlogic.gdx.math.collision.Ray(ray.origin, ray.direction).mul(new Matrix4(box.getTransform()).inv());
                        if (com.badlogic.gdx.math.Intersector.intersectRayBoundsFast(local, box.getBounds())) {
                            return new GpuTerrain.DecorationHit(item.coords(), object.id(), 0, object.anchorLevel() * BoardGeometry.level());
                        }
                    }
                }
            }
            return hit;
        }

        /** Whether the pointer x, y (input coordinates) lies on the handle's circle. */
        private boolean onHandle(RotationHandle handle, int x, int y) {
            var camera = boardCamera.camera;
            Vector3 tip = camera.project(new Vector3(handle.tip()), 0, 0, camera.viewportWidth, camera.viewportHeight);
            return Vector2.dst(tip.x, Gdx.graphics.getHeight() - tip.y, x, y) <= (HANDLE_RADIUS + 4) * layoutScale;
        }

        /** The pointer's angle (radians) about the handle's pivot, on the horizontal plane of the handle; NaN off it. */
        private double handleAngle(RotationHandle handle, int x, int y) {
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            float distance = Math.abs(ray.direction.z) < .00001f ? -1 : (handle.tip().z - ray.origin.z) / ray.direction.z;
            if (distance <= 0) { return Double.NaN; }
            Vector3 point = ray.getEndPoint(new Vector3(), distance);
            return Math.atan2(point.y - handle.pivot().y, point.x - handle.pivot().x);
        }

        /**
         * A handle drag's step: the selection turned by the pointer's sweep about the pivot since the press, in whole
         * degrees (counter-clockwise from above, as the board turns), as one continuing edit that the release finishes.
         */
        private void turn(int x, int y) {
            double angle = handleAngle(rotationGrab, x, y);
            if (Double.isNaN(angle)) { return; }
            double degrees = Math.round(Math.IEEEremainder(rotationGrab.from() + Math.toDegrees(angle - rotationPress), 360));
            if (degrees == rotationSent) { return; }
            rotationSent = degrees;
            source.editorValue(new megamek.client.ui.boardeditor.BoardEditorSession.Command(rotationGrab.group()
                        ? megamek.client.ui.boardeditor.BoardEditorSession.Action.GROUP_VALUE
                        : megamek.client.ui.boardeditor.BoardEditorSession.Action.OBJECT_VALUE, "rotation", Long.toString((long) degrees)),
                  false, gestureBoardGeneration);
        }

        /** The item a Select press at x, y picks: the object {@link #objectPress} picks, else the ground's hex, else null. */
        private megamek.client.ui.boardeditor.BoardEditorSession.Selection pressedItem(int x, int y,
              megamek.client.ui.boardeditor.BoardEditorSession.Snapshot state) {
            if (scene == null || terrain == null) { return null; }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var object = objectPress(ray, state);
            if (object != null) { return new megamek.client.ui.boardeditor.BoardEditorSession.Selection(object.coords(), object.id()); }
            var ground = terrain.selectionHit(scene, ray);
            return ground == null ? null : new megamek.client.ui.boardeditor.BoardEditorSession.Selection(ground.coords(), "");
        }

        private Coords pick(int x, int y) {
            return pickSelection(x, y).coords();
        }

        /** The nearest rendered unit under the pointer, including units outlined through scenery. */
        private BoardScene.Unit pickUnit(float x, float y) {
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            float nearest = Float.POSITIVE_INFINITY;
            BoardScene.Unit picked = null;
            for (BoardScene.Unit unit : scene.units()) {
                var instance = unitInstances.get(unit.id() + ":" + unit.part());
                // A wreck has no gameplay, nor its ghost: a click on it takes the ground beneath.
                if (instance == null || dead(unit) || unitGhosts.containsKey(instance)) { continue; }
                float distance = unitPicking.distance(instance, ray);
                if (distance < nearest) {
                    nearest = distance;
                    picked = unit;
                }
            }
            return picked;
        }

        private Pick pickSelection(int x, int y) {
            if (scene == null || reloadingAssets || assetReloadFailed) {
                return new Pick(null, Entity.NONE, Float.NaN, false);
            }
            var pickedUnit = pickUnit(x, y);
            if (pickedUnit == null && tuning.seeThrough() > 0) {
                // The outline extends slightly beyond the mesh. Direct model hits always take priority.
                float step = GpuUnitVisibility.OUTLINE_STEP * layoutScale;
                for (int dx = -1; dx <= 1 && pickedUnit == null; dx++) {
                    for (int dy = -1; dy <= 1 && pickedUnit == null; dy++) {
                        if (dx != 0 || dy != 0) { pickedUnit = pickUnit(x + dx * step, y + dy * step); }
                    }
                }
            }
            var ray = boardCamera.camera.getPickRay(x, y, 0, 0,
                  boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
            var ground = terrain.selectionHit(scene, ray);
            float nearest = ground == null ? Float.POSITIVE_INFINITY : ground.distance();
            Coords coords = ground == null ? null : ground.coords();
            int entityId = Entity.NONE;
            boolean onTerrain = ground != null;
            if (pickedUnit != null) {
                coords = pickedUnit.location().coords();
                entityId = pickedUnit.id();
                onTerrain = false;
            } else {
                for (var entry : markers.locatedInstances().entrySet()) {
                    float distance = unitPicking.distance(entry.getValue(), ray);
                    if (distance < nearest) {
                        nearest = distance;
                        coords = entry.getKey().coords();
                        onTerrain = false;
                    }
                }
            }
            var floor = coords == null ? null : terrain.walkableHit(scene, ray);
            float floorZ = floor != null && floor.coords().equals(coords)
                  ? ray.getEndPoint(new Vector3(), (float) Math.sqrt(floor.distance())).z : Float.NaN;
            if (entityId == Entity.NONE && coords != null) {
                for (BoardScene.Unit unit : scene.units()) {
                    // A unit's hex footprint must not intercept a click on another floor of its building.
                    if (unit.footprint().contains(coords) && (!scene.tile(coords).building() || Float.isNaN(floorZ)
                          || GpuLosResult.pointedLevel(floorZ) == Math.round(unit.location().elevation()))) {
                        entityId = unit.id();
                        break;
                    }
                }
            }
            return new Pick(coords, entityId, floorZ, onTerrain);
        }

        @Override
        public boolean touchDown(int x, int y, int pointer, int button) {
            if (reloadingAssets || assetReloadFailed) { return true; }
            if (button == Input.Buttons.RIGHT && drawingRoute()) {
                cancelRoute();
                return true;
            }
            if (boardGesture || ui.hit(x, y)) {
                return false;
            }
            dragX = x;
            dragY = y;
            startX = x;
            startY = y;
            boardGesture = true;
            gestureButton = button;
            gestureModifiers = modifiers();
            measurementGesture = button == Input.Buttons.LEFT && (GpuBoardSource.isMeasurement(gestureModifiers)
                  || ui instanceof GpuMapHud map && map.measuring());
            brushPickerGesture = source.isEditor() && button == Input.Buttons.LEFT && !measurementGesture
                  && gestureModifiers == 0 && Gdx.input.isKeyPressed(Input.Keys.B);
            gestureBoardGeneration = boardGeneration;
            dragged = false;
            panning = BoardCameraControls.cameraButton(button);
            orbiting = BoardCameraControls.orbits(button, gestureModifiers);
            ui.boardPress();
            if (button == Input.Buttons.LEFT && source.isEditor() && !measurementGesture && !brushPickerGesture) {
                // A press on the selection's rotation handle turns the selection.
                var handle = rotationHandle();
                double angle = handle == null || !onHandle(handle, x, y) ? Double.NaN : handleAngle(handle, x, y);
                if (!Double.isNaN(angle)) {
                    rotationGrab = handle; rotationPress = angle; rotationSent = handle.from(); marquee = false;
                    return true;
                }
                var state = source.editorState();
                // A plain press on the selection grabs it to move; any other Select press (also one that hits nothing,
                // such as the sky around the board) waits for a box or a click.
                var item = state == null || state.selection().isEmpty() ? null : pressedItem(x, y, state);
                marquee = state != null && state.tool() == megamek.client.ui.boardeditor.BoardEditorSession.Tool.SELECT
                      && ((gestureModifiers & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)) != 0
                      || item == null || !state.selection().contains(item));
                // The board editor paints from the press on; a left drag goes on painting.
                if (!marquee) { editorPointer(x, y, false); }
            }
            return true;
        }

        @Override
        public boolean touchDragged(int x, int y, int pointer) {
            if (!boardGesture) {
                return false;
            }
            if (source.isEditor() && gestureButton == Input.Buttons.LEFT && !measurementGesture && !brushPickerGesture) {
                if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < BoardCameraControls.DRAG_THRESHOLD * layoutScale) { return true; }
                dragged = true;
                dragX = x;
                dragY = y;
                if (rotationGrab != null) { turn(x, y); return true; }
                if (marquee) { return true; }
                // The board editor's left drag paints every hex it crosses, from the press on.
                if (!ui.hit(x, y)) {
                    editorPointer(x, y, true);
                }
                return true;
            }
            if (!dragged && Math.abs(x - startX) + Math.abs(y - startY) < BoardCameraControls.DRAG_THRESHOLD * layoutScale) {
                return true;
            }
            // Past the threshold a gesture is a drag: a left one does nothing more and cancels its click (C.4). An orbit
            // wraps the hidden cursor, so it never stops at the screen's edge; a pan keeps the board under the pointer.
            if (!dragged && orbiting) {
                cursorCapture.capture(x, y, 1, 1);
                cursorCapture.beganAt(startX, startY);
            }
            dragged = true;
            if (orbiting) {
                Vector2 at = cursorCapture.trusted(x, y);
                x = (int) at.x;
                y = (int) at.y;
            }
            if (panning) {
                BoardCameraControls.drag(boardCamera, orbiting, x - dragX, y - dragY, layoutScale);
            }
            dragX = x;
            dragY = y;
            return true;
        }

        @Override
        public boolean touchUp(int x, int y, int pointer, int button) {
            if (!boardGesture || button != gestureButton) {
                return false;
            }
            if (source.isEditor() && !measurementGesture && !brushPickerGesture && button == Input.Buttons.LEFT) {
                if (marquee && dragged) { selectBox(x, y); }
                // A click: Ctrl removes the clicked item; otherwise it selects it, or with Shift toggles it.
                else if (marquee && (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
                    var state = source.editorState();
                    var item = state == null ? null : pressedItem(startX, startY, state);
                    if (item != null) {
                        source.editorSelect(List.of(item), megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REMOVE,
                              gestureBoardGeneration);
                    }
                } else if (marquee) { editorPointer(startX, startY, false); }
                else if (!dragged && editorHexGrab != null) {
                    // A click on a placed object standing on a selected hex selects the object; only a drag moves hexes.
                    var object = terrain == null ? null : terrain.decorationHit(scene, boardCamera.camera.getPickRay(startX, startY,
                          0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
                    if (object != null) { source.editorPointer(object.coords(), 0, 0, false, object.id(), false, gestureBoardGeneration); }
                } else if (!dragged && editorGrab != null && terrain != null) {
                    // A click on the selected authored building selects its hex under the pointer (the hover ring's), on
                    // that hex's Building layer, where it can be destroyed alone; a click on that hex selects the whole
                    // building again, which a drag moves.
                    var under = terrain.selectionHit(scene, boardCamera.camera.getPickRay(startX, startY, 0, 0,
                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
                    var tile = under == null ? null : scene.tile(under.coords());
                    var member = tile == null ? null : tile.cover().reference();
                    if (member != null && member.id().equals(editorGrab.id())) {
                        source.editorCommand(new Command(Action.SELECT_AT, under.coords().getX() + "," + under.coords().getY(), ""),
                              gestureBoardGeneration);
                        source.editorCommand(new Command(Action.COMPONENT, "", "building"), gestureBoardGeneration);
                    }
                }
                source.endEditorStroke();
                reset();
                return true;
            }
            // A short left or right click on the board of the press goes to the HUD: the tool click or the menu. A
            // map preview follows hover unless its menu is open; Alt or a pending ruler consumes a left click for LOS.
            if (!dragged && button != Input.Buttons.MIDDLE && !ui.hit(x, y)
                  && gestureBoardGeneration == boardGeneration) {
                Pick picked = pickSelection(x, y);
                // The height the pointer shows, which a measurement takes.
                float pointedZ = picked.pointedZ();
                if (ui instanceof GpuHud hud) {
                    // The shared phase tool handles a press; releasing Shift first must not turn it into placement.
                    hud.boardClick(picked.coords(), picked.entityId(), button, gestureModifiers, x, y, pointedZ);
                } else if (button == Input.Buttons.RIGHT) {
                    ((GpuMapHud) ui).boardMenu(picked.coords(), pointedZ, x, y);
                } else if (measurementGesture) {
                    source.measure(picked.coords(), gestureModifiers, pointedZ);
                } else if (brushPickerGesture) {
                    Coords at = picked.coords();
                    if (at != null) {
                        source.editorCommand(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""),
                              gestureBoardGeneration);
                        source.editorCommand(new Command(Action.SAMPLE), gestureBoardGeneration);
                    }
                } else {
                    source.setHover(picked.coords());
                }
            }
            reset();
            return true;
        }

        void reset() {
            cursorCapture.release();
            panning = false;
            orbiting = false;
            dragged = false;
            boardGesture = false;
            measurementGesture = false;
            brushPickerGesture = false;
            marquee = false;
            editorGrab = null; editorHexGrab = null; rotationGrab = null;
            clearElevationScroll();
        }

        /**
         * Whether the left button's gesture draws a route by hand in the editor (Paint with a rail, maglev or road brush,
         * which the session builds on the release): Esc or a right click drops it, and near the board area's edge its drag
         * scrolls the board ({@link #autoPan}).
         */
        private boolean drawingRoute() {
            // The window can pause or close while it still shows the loading screen, before any map is attached.
            if (source == null) { return false; }
            var state = source.editorState();
            return boardGesture && gestureButton == Input.Buttons.LEFT && !measurementGesture && !brushPickerGesture
                  && !marquee && rotationGrab == null
                  && source.isEditor() && state != null && state.drawsRoutes();
        }

        /** Drops the route the drag draws, unbuilt, and ends the gesture, so its release does nothing more. */
        private void cancelRoute() {
            source.cancelEditorStroke();
            reset();
        }

        /**
         * Ends the gesture the window takes away (focus lost, a modal menu), whose release never comes here: a route drawn
         * by hand is dropped unbuilt, as Esc drops it, so no later command or press builds it.
         */
        void interrupt() {
            if (drawingRoute()) { cancelRoute(); }
            else { reset(); }
        }

        /**
         * Each frame of a route's drag ({@link #drawingRoute}) with the pointer near or past the edge of the board area
         * the panels leave free (as Fit board frames it): the board scrolls towards that edge ({@link #edgePan}), at most
         * as fast as the keyboard scrolls it, and the route follows the hex then under the pointer. Over a panel the drag
         * sends no events, so the route could not follow: there the board stays.
         */
        void autoPan(float seconds) {
            if (!dragged || ui == null || !drawingRoute() || ui.hit(dragX, dragY)) { return; }
            // The drag's last pointer, which stays where it is while the board scrolls under it.
            Vector2 pan = edgePan(dragX, dragY, ui.framingLeft(), ui.framingTop(), ui.framingLeft() + ui.framingWidth(),
                  Gdx.graphics.getHeight() - ui.framingBottom(), EDGE_PAN_MARGIN * layoutScale,
                  BoardCameraControls.PAN_PIXELS_PER_SECOND * seconds);
            if (pan.isZero()) { return; }
            boardCamera.pan(pan.x, pan.y);
            editorPointer(dragX, dragY, true);
        }

        /**
         * One box selection of the objects whose anchors, and the hexes whose centres, in the scene the frame drew,
         * project inside the rectangle; the session takes the objects, or the hexes when the box holds no object.
         */
        private void selectBox(int x, int y) {
            if (scene == null) { return; }
            int left = Math.min(startX, x), right = Math.max(startX, x), top = Math.min(startY, y), bottom = Math.max(startY, y);
            Set<megamek.client.ui.boardeditor.BoardEditorSession.Selection> hits = new java.util.LinkedHashSet<>();
            for (BoardScene.Tile tile : scene.tiles()) {
                Vector3 centre = boardCamera.camera.project(BoardGeometry.center(tile.coords(), tile.elevation()),
                      0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                float centreY = Gdx.graphics.getHeight() - centre.y;
                if (centre.z >= 0 && centre.z <= 1 && centre.x >= left && centre.x <= right && centreY >= top && centreY <= bottom) {
                    hits.add(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(tile.coords(), ""));
                }
                if (tile.features().stream().allMatch(feature -> feature.decoration() == null)) { continue; }
                // The installed anchors, the same placement the selection outlines use: roofs, decks and offsets.
                Map<String, Float> anchors = new HashMap<>();
                // What the zoom hides cannot be box selected, as it cannot be clicked: the box takes the hexes there.
                Set<String> hidden = new java.util.HashSet<>();
                if (terrain != null) {
                    for (var installed : terrain.editorObjects(tile.coords())) {
                        anchors.put(installed.id(), installed.anchorLevel());
                        if (!installed.shown()) { hidden.add(installed.id()); }
                    }
                }
                for (BoardScene.Feature feature : tile.features()) {
                    var object = feature.decoration();
                    if (object == null || hidden.contains(object.id())) { continue; }
                    Vector3 point = boardCamera.camera.project(new Vector3(
                                BoardGeometry.centerX(tile.coords()) + (float) object.x() * BoardGeometry.width(),
                                BoardGeometry.centerY(tile.coords()) + (float) object.y() * BoardGeometry.height(),
                                anchors.getOrDefault(object.id(), (float) tile.elevation()) * BoardGeometry.level()),
                          0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                    float screenY = Gdx.graphics.getHeight() - point.y;
                    if (point.z >= 0 && point.z <= 1 && point.x >= left && point.x <= right && screenY >= top && screenY <= bottom) {
                        hits.add(new megamek.client.ui.boardeditor.BoardEditorSession.Selection(tile.coords(), object.id()));
                    }
                }
            }
            var mode = (gestureModifiers & InputEvent.CTRL_DOWN_MASK) != 0 ? megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REMOVE
                  : (gestureModifiers & InputEvent.SHIFT_DOWN_MASK) != 0 ? megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.ADD
                  : megamek.client.ui.boardeditor.BoardEditorSession.SelectMode.REPLACE;
            source.editorSelect(List.copyOf(hits), mode, gestureBoardGeneration);
        }

        private void clearElevationScroll() {
            elevationScrollHex = null;
            elevationScroll = 0;
        }

        @Override
        public boolean mouseMoved(int x, int y) {
            // Camera refreshes must not replace an editor drag's projected destination, or the hex of a route drawn by
            // hand (editorPointer: a drag sends no mouse-moves), with a new cursor hit, which past the board is none.
            if (source.isEditor() && boardGesture && gestureButton == Input.Buttons.LEFT && !measurementGesture
                  && (editorGrab != null || editorHexGrab != null || rotationGrab != null || drawingRoute())) { return true; }
            boolean overHud = ui.hit(x, y);
            Pick picked = overHud ? new Pick(null, Entity.NONE, Float.NaN, false) : pickSelection(x, y);
            hovered = picked.coords();
            hoveredUnit = picked.entityId();
            pointerOverHud = overHud;
            hoverZ = picked.floorZ();
            source.setHover(hoverHeld ? null : hovered, picked.pointedZ());
            if (source.isEditor()) { editorHover(x, y, overHud); }
            // An object under the pointer in the editor's inspected hex is highlighted in its side view and Layers.
            if (ui instanceof GpuMapHud map && source.editorState() != null) {
                var hit = hovered != null && hovered.equals(source.editorState().selected()) ? terrain.decorationHit(scene,
                      boardCamera.camera.getPickRay(x, y, 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight)) : null;
                map.editorBoardHover(hit != null && hit.coords().equals(hovered) ? hit.id() : "");
            }
            // Desktop pointers: a hand over units a click selects or inspects and over the editor's rotation handle, a
            // move cursor over the minimap, which drags the camera (Q5).
            cursor(overHud ? ui.dragsCamera(x, y) ? SystemCursor.AllResize : SystemCursor.Arrow
                  : boardCursor(pointedUnit(), x, y));
            return false;
        }

        private void finishElevationScroll() {
            if (elevationScrollHex != null) {
                source.endEditorStroke();
                clearElevationScroll();
            }
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (reloadingAssets || assetReloadFailed) { return true; }
            if (!hudUnderPointer()) {
                if (source.isEditor() && modifiers() == InputEvent.CTRL_DOWN_MASK) {
                    if (!boardGesture && !ui.isTextEditing()) {
                        Coords coords = pick(Gdx.input.getX(), Gdx.input.getY());
                        if (coords != null) {
                            if (!coords.equals(elevationScrollHex)) {
                                elevationScroll = 0;
                                elevationScrollHex = coords;
                            }
                            elevationScroll -= amountY;
                            int levels = (int) elevationScroll;
                            elevationScroll -= levels;
                            if (levels != 0) {
                                source.adjustEditorElevation(coords, levels, boardGeneration);
                            }
                        }
                    }
                    return true;
                }
                finishElevationScroll();
                boardCamera.zoomAt(wheelZoom(amountY), Gdx.input.getX(), Gdx.graphics.getHeight() - Gdx.input.getY());
                return true;
            }
            return false;
        }

        @Override
        public boolean keyDown(int key) {
            if (pressedKeys.containsKey(key) || cameraKeys.containsKey(key)) {
                return true;
            }
            // Esc takes the editor's left-button gesture before the HUD's Esc chain: a route drawn by hand is dropped
            // unbuilt, and a box, click, pick or measurement is dropped before its release acts. A stroke, move or
            // turn changes the board as it goes, so Esc leaves it to its release.
            if (key == Input.Keys.ESCAPE && boardGesture && gestureButton == Input.Buttons.LEFT && source.isEditor()) {
                if (drawingRoute()) { cancelRoute(); }
                else if (marquee || measurementGesture || brushPickerGesture) { reset(); }
                return true;
            }
            int awt = awtKey(key);
            int modifiers = modifiers();
            // Resolve Shift as camera acceleration before HUD hotkeys can turn or twist a unit with Shift+A/D.
            List<GpuBoardSource.Bind> captured = source.uiPreferences().binds();
            int bindModifiers = modifiers;
            if (modifiers == InputEvent.SHIFT_DOWN_MASK
                  && GpuHud.binds(captured, awt, 0).stream().anyMatch(BoardCameraControls::held)) {
                bindModifiers = 0;
            }
            // While a route is drawn by hand only the camera takes keys: a hotkey or an editor command (Undo, a tool
            // key) would end the stroke and so build the route before its release.
            boolean drawing = drawingRoute();
            // The HUD first (C.4): a pending dialog, the Esc chain, a focused field or list, the hotkeys.
            if (!drawing && ui.keyDown(key, awt, bindModifiers)) {
                return true;
            }
            if (reloadingAssets || assetReloadFailed) { return true; }
            // The binds the client captured on the Swing thread; the bind fields are Swing's (rebuild plan D).
            Set<KeyCommandBind> binds = GpuHud.binds(captured, awt, bindModifiers);
            // Swing's GUI scale is Swing's alone: its keys do nothing here, and never reach the Swing client.
            if (binds.contains(KeyCommandBind.INC_GUI_SCALE) || binds.contains(KeyCommandBind.DEC_GUI_SCALE)) {
                return true;
            }
            for (KeyCommandBind command : binds) {
                if (cameraCommand(key, command)) {
                    return true;
                }
            }
            if (drawing) { return true; }
            if (awt != KeyEvent.VK_UNDEFINED) {
                pressedKeys.put(key, new KeyPress(awt, modifiers));
                source.key(awt, true, modifiers);
            }
            return awt != KeyEvent.VK_UNDEFINED;
        }

        /**
         * Applies a bind that moves the camera. Held binds are only noted here and applied every frame while the key
         * stays down.
         *
         * @return {@code false} for a bind that is not a camera command, which is then left to the Swing key dispatcher
         */
        private boolean cameraCommand(int key, KeyCommandBind command) {
            switch (command) {
                case SCROLL_NORTH, SCROLL_SOUTH, SCROLL_EAST, SCROLL_WEST, CAMERA_TILT_UP, CAMERA_TILT_DOWN,
                     CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT -> cameraKeys.put(key, command);
                case TOGGLE_ISO -> setTacticalView(!boardCamera.tactical());
                case ZOOM_IN -> boardCamera.zoom(1 / ZOOM_STEP);
                case ZOOM_OUT -> boardCamera.zoom(ZOOM_STEP);
                case CAMERA_RESET, CAMERA_FIT_BOARD, ZOOM_OVERVIEW_TOGGLE -> frameBoard(command);
                default -> {
                    return false;
                }
            }
            LOGGER.debug("[GpuCamera] {} handled by the GPU camera", command);
            return true;
        }

        /** These camera moves measure the board, so they wait until the first frame has delivered one. */
        private void frameBoard(KeyCommandBind command) {
            if (scene == null) {
                LOGGER.debug("[GpuCamera] {} ignored: no board has been drawn yet", command);
                return;
            }
            switch (command) {
                case CAMERA_RESET -> boardCamera.reset(scene);
                case CAMERA_FIT_BOARD -> fitBoard();
                case ZOOM_OVERVIEW_TOGGLE -> boardCamera.toggleOverview(scene);
                default -> { }
            }
        }

        @Override
        public boolean keyUp(int key) {
            if (key == Input.Keys.CONTROL_LEFT || key == Input.Keys.CONTROL_RIGHT) {
                finishElevationScroll();
            }
            // Every release reaches the HUD; one whose press it consumed ends there.
            if (ui.keyUp(key, awtKey(key))) {
                return true;
            }
            if (cameraKeys.remove(key) != null) {
                return true;
            }
            KeyPress pressed = pressedKeys.remove(key);
            if (pressed != null) {
                source.key(pressed.code(), false, pressed.modifiers());
            }
            return pressed != null;
        }

        @Override
        public boolean keyTyped(char character) {
            return ui.keyTyped(character);
        }
    }

    /**
     * An edge scroll's pan (window pixels, as {@link BoardCamera#pan} takes a drag's) for the pointer at x, y (input
     * coordinates, y down) in the area from {@code left, top} to {@code right, bottom}: towards each edge the pointer lies
     * within {@code margin} of, or past, by up to {@code distance}, in proportion to how deep it is in the margin; zero
     * farther inside. A positive pan moves the board right or down, bringing in what lies beyond the left or top edge.
     */
    static Vector2 edgePan(float x, float y, float left, float top, float right, float bottom, float margin, float distance) {
        return new Vector2((depth(left + margin - x, margin) - depth(x - right + margin, margin)) * distance,
              (depth(top + margin - y, margin) - depth(y - bottom + margin, margin)) * distance);
    }

    /** How deep (0 to 1) a point lies {@code into} a margin of {@code margin}. */
    private static float depth(float into, float margin) { return MathUtils.clamp(into / margin, 0, 1); }

    /**
     * The board's pointer at x, y (input coordinates): a hand over the unit a click there takes, and over the editor's
     * rotation handle or while it is held; else the arrow.
     */
    private SystemCursor boardCursor(int unitId, int x, int y) {
        if (unitId != Entity.NONE || boardInput.rotationGrab != null) { return SystemCursor.Hand; }
        var handle = rotationHandle();
        return handle != null && boardInput.onHandle(handle, x, y) ? SystemCursor.Hand : SystemCursor.Arrow;
    }

    /** Shows a system pointer on desktop platforms; mobile platforms have none to change (user correction 4). */
    private void cursor(SystemCursor next) {
        if (next != cursor && Gdx.app.getType() == Application.ApplicationType.Desktop) {
            cursor = next;
            Gdx.graphics.setSystemCursor(next);
        }
    }

    static int awtKey(int key) {
        boolean numLock = true;
        if (key >= Input.Keys.NUMPAD_0 && key <= Input.Keys.NUMPAD_DOT) {
            try {
                numLock = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_NUM_LOCK);
            } catch (UnsupportedOperationException ignored) {
                // Some window systems do not expose lock state; keep the numeric keypad usable there.
            }
        }
        return awtKey(key, numLock);
    }

    static int awtKey(int key, boolean numLock) {
        if (!numLock) {
            int navigation = switch (key) {
                case Input.Keys.NUMPAD_0 -> KeyEvent.VK_INSERT;
                case Input.Keys.NUMPAD_1 -> KeyEvent.VK_END;
                case Input.Keys.NUMPAD_2 -> KeyEvent.VK_KP_DOWN;
                case Input.Keys.NUMPAD_3 -> KeyEvent.VK_PAGE_DOWN;
                case Input.Keys.NUMPAD_4 -> KeyEvent.VK_KP_LEFT;
                case Input.Keys.NUMPAD_5 -> KeyEvent.VK_CLEAR;
                case Input.Keys.NUMPAD_6 -> KeyEvent.VK_KP_RIGHT;
                case Input.Keys.NUMPAD_7 -> KeyEvent.VK_HOME;
                case Input.Keys.NUMPAD_8 -> KeyEvent.VK_KP_UP;
                case Input.Keys.NUMPAD_9 -> KeyEvent.VK_PAGE_UP;
                case Input.Keys.NUMPAD_DOT -> KeyEvent.VK_DELETE;
                default -> KeyEvent.VK_UNDEFINED;
            };
            if (navigation != KeyEvent.VK_UNDEFINED) {
                return navigation;
            }
        }
        if (key >= Input.Keys.A && key <= Input.Keys.Z) {
            return KeyEvent.VK_A + key - Input.Keys.A;
        }
        if (key >= Input.Keys.NUM_0 && key <= Input.Keys.NUM_9) {
            return KeyEvent.VK_0 + key - Input.Keys.NUM_0;
        }
        if (key >= Input.Keys.F1 && key <= Input.Keys.F12) {
            return KeyEvent.VK_F1 + key - Input.Keys.F1;
        }
        if (key >= Input.Keys.F13 && key <= Input.Keys.F24) {
            return KeyEvent.VK_F13 + key - Input.Keys.F13;
        }
        if (key >= Input.Keys.NUMPAD_0 && key <= Input.Keys.NUMPAD_9) {
            return KeyEvent.VK_NUMPAD0 + key - Input.Keys.NUMPAD_0;
        }
        return switch (key) {
            case Input.Keys.UP -> KeyEvent.VK_UP;
            case Input.Keys.DOWN -> KeyEvent.VK_DOWN;
            case Input.Keys.LEFT -> KeyEvent.VK_LEFT;
            case Input.Keys.RIGHT -> KeyEvent.VK_RIGHT;
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> KeyEvent.VK_ENTER;
            case Input.Keys.TAB -> KeyEvent.VK_TAB;
            case Input.Keys.ESCAPE -> KeyEvent.VK_ESCAPE;
            case Input.Keys.SPACE -> KeyEvent.VK_SPACE;
            case Input.Keys.BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case Input.Keys.FORWARD_DEL -> KeyEvent.VK_DELETE;
            case Input.Keys.INSERT -> KeyEvent.VK_INSERT;
            case Input.Keys.HOME -> KeyEvent.VK_HOME;
            case Input.Keys.END -> KeyEvent.VK_END;
            case Input.Keys.PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case Input.Keys.PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case Input.Keys.MINUS -> KeyEvent.VK_MINUS;
            case Input.Keys.EQUALS, Input.Keys.NUMPAD_EQUALS -> KeyEvent.VK_EQUALS;
            case Input.Keys.COMMA -> KeyEvent.VK_COMMA;
            case Input.Keys.PERIOD -> KeyEvent.VK_PERIOD;
            case Input.Keys.SLASH -> KeyEvent.VK_SLASH;
            case Input.Keys.BACKSLASH -> KeyEvent.VK_BACK_SLASH;
            case Input.Keys.SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case Input.Keys.APOSTROPHE -> KeyEvent.VK_QUOTE;
            case Input.Keys.GRAVE -> KeyEvent.VK_BACK_QUOTE;
            case Input.Keys.LEFT_BRACKET -> KeyEvent.VK_OPEN_BRACKET;
            case Input.Keys.RIGHT_BRACKET -> KeyEvent.VK_CLOSE_BRACKET;
            case Input.Keys.PLUS -> KeyEvent.VK_PLUS;
            case Input.Keys.NUMPAD_ADD -> KeyEvent.VK_ADD;
            case Input.Keys.NUMPAD_SUBTRACT -> KeyEvent.VK_SUBTRACT;
            case Input.Keys.NUMPAD_MULTIPLY -> KeyEvent.VK_MULTIPLY;
            case Input.Keys.NUMPAD_DIVIDE -> KeyEvent.VK_DIVIDE;
            case Input.Keys.NUMPAD_DOT -> KeyEvent.VK_DECIMAL;
            case Input.Keys.NUMPAD_COMMA -> KeyEvent.VK_SEPARATOR;
            case Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT -> KeyEvent.VK_SHIFT;
            case Input.Keys.CONTROL_LEFT, Input.Keys.CONTROL_RIGHT -> KeyEvent.VK_CONTROL;
            case Input.Keys.ALT_LEFT, Input.Keys.ALT_RIGHT -> KeyEvent.VK_ALT;
            case Input.Keys.SYM -> KeyEvent.VK_META;
            case Input.Keys.CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;
            case Input.Keys.NUM_LOCK -> KeyEvent.VK_NUM_LOCK;
            case Input.Keys.SCROLL_LOCK -> KeyEvent.VK_SCROLL_LOCK;
            case Input.Keys.PAUSE -> KeyEvent.VK_PAUSE;
            case Input.Keys.PRINT_SCREEN -> KeyEvent.VK_PRINTSCREEN;
            case Input.Keys.MENU -> KeyEvent.VK_CONTEXT_MENU;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    /**
     * The fullscreen switch: Alt+Enter (either Alt, Enter or numpad Enter) everywhere, and the platform's own key, which
     * no default bind uses: Control+Command+F on macOS, whose F11 shows the desktop, and F11 elsewhere.
     */
    static boolean isFullscreenSwitch(int key, int modifiers, boolean mac) {
        return switch (key) {
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
            case Input.Keys.F -> mac && modifiers == (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK);
            case Input.Keys.F11 -> !mac && modifiers == 0;
            default -> false;
        };
    }

    /**
     * The fullscreen switch ({@link #isFullscreenSwitch}), ahead of the HUD and the game's key bindings: its press, the
     * character the window types for an Enter and for each repeat, and its release reach neither.
     */
    final class FullscreenKey extends InputAdapter {
        private int held = -1;

        @Override
        public boolean keyDown(int key) {
            if (!isFullscreenSwitch(key, modifiers(), SharedLibraryLoader.os == Os.MacOsX)) {
                // A release the window never delivered (focus lost): the key's next plain press is the game's again.
                if (key == held) { held = -1; }
                return false;
            }
            held = key;
            toggleFullscreen();
            return true;
        }

        @Override
        public boolean keyUp(int key) {
            if (key != held) { return false; }
            held = -1;
            return true;
        }

        @Override
        public boolean keyTyped(char character) {
            return character == '\n' && (held == Input.Keys.ENTER || held == Input.Keys.NUMPAD_ENTER);
        }
    }

    /** The tuning's Display mode: the same switch as the key's, when the window is not already in that mode. */
    private void setFullscreen(boolean fullscreen) {
        if (Gdx.graphics.isFullscreen() != fullscreen) { toggleFullscreen(); }
    }

    /**
     * Fullscreen on the window's monitor in that monitor's display mode, or back to the windowed bounds before. The
     * resize reaches {@link #resize} and every size-dependent target follows the new back buffer.
     */
    private void toggleFullscreen() {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics graphics)) { return; }
        Lwjgl3Window window = graphics.getWindow();
        if (graphics.isFullscreen()) {
            if (windowed != null) {
                graphics.setWindowedMode(windowed.width(), windowed.height());
                window.setPosition(windowed.x(), windowed.y());
                if (windowed.maximized()) { window.maximizeWindow(); }
            }
            return;
        }
        // The monitor holds the window now; a maximized window then returns to the normal bounds it restores to.
        var monitor = graphics.getMonitor();
        long handle = window.getWindowHandle();
        boolean maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        if (maximized) { window.restoreWindow(); }
        int[] width = new int[1], height = new int[1];
        GLFW.glfwGetWindowSize(handle, width, height);
        windowed = new GpuWindowBounds(window.getPositionX(), window.getPositionY(), width[0], height[0], maximized);
        graphics.setFullscreenMode(graphics.getDisplayMode(monitor));
    }

    @Override
    public void pause() {
        cameraKeys.clear();
        boardInput.pressedKeys.clear();
        boardInput.interrupt();
        if (ui != null) {
            ui.focusLost();
        }
        if (source != null) {
            source.stopKeys();
        }
    }

    static int modifiers() {
        int result = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
            result |= InputEvent.SHIFT_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) {
            result |= InputEvent.CTRL_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)) {
            result |= InputEvent.ALT_DOWN_MASK;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SYM)) {
            result |= InputEvent.META_DOWN_MASK;
        }
        return result;
    }

    long frames() {
        return frames;
    }

    boolean isMoving() {
        return playback.busy();
    }

    /**
     * GPU thread: the battle's own cards over its HUD, such as the Game Master's damage card; null before the HUD
     * stands or once the view is gone.
     */
    LobbyNativeDialogs cards() {
        if (ui == null || disposed) {
            return null;
        }
        if (cards == null) {
            cards = new LobbyNativeDialogs(ui.uiKit(), ui.stage(),
                  !megamek.client.ui.clientGUI.GUIPreferences.getInstance().getReduceMotion());
        }
        return cards;
    }

    @Override
    public void dispose() {
        try { shaderEdits.run(this::disposeView); }
        finally { shaderEdits.close(); }
    }

    private void disposeView() {
        disposed = true;
        // The open view's choice (BoardDisplaySettings.apply): nothing drawn without a view inherits it.
        BoardQuality.current = BoardQuality.HIGH;
        GpuGlsl.presenting(false);
        if (loadingStage != null) {
            pace(false);
            loadingStage.dispose();
            loadingStage.getBatch().dispose();
            if (loadingTheme != null) { loadingTheme.release(); }
        }
        playback.clear();
        groundSurfaces.clear();
        clearUnitInstances();
        disposeSceneRenderers();
        if (cards != null) {
            cards.dispose();
            cards = null;
        }
        if (ui != null) {
            ui.dispose();
        }
        if (theme != null) {
            theme.release();
        }
        if (source != null) {
            source.stopKeys();
        }
        spriteModels.values().forEach(GpuUnitModel::dispose);
        spriteModels.clear();
        if (unitModels != null) {
            unitModels.dispose();
        }
        camouflage.dispose();
        damageDisplay.dispose();
        if (unitBases != null) { unitBases.dispose(); }
        if (annotationBatch != null) {
            annotationBatch.dispose();
        }
        // After every batch that draws with it: the loading stage's, the HUD's and the scene renderers'.
        if (sprites != null) { sprites.dispose(); }
        if (editorBadges != null) { editorBadges.dispose(); }
        if (unitTextures != null) {
            unitTextures.dispose();
        }
        if (markers != null) {
            markers.dispose();
        }
        fieldOfView.dispose();
        if (overlay != null) {
            overlay.dispose();
        }
        // After the lines, the tactical layer and the overlay.
        if (shapes != null) { shapes.dispose(); }
        if (source != null) {
            source.close();
        }
    }

    /**
     * Instances borrow a sprite model's mesh, so they leave with it: picking before {@link #prepareUnits} rebuilds them
     * would otherwise read the freed vertex buffer and crash the JVM.
     */
    private void disposeSpriteModels(java.util.function.Predicate<UnitSprite> evicted) {
        boolean removed = spriteModels.entrySet().removeIf(entry -> {
            if (!evicted.test(entry.getKey())) { return false; }
            var model = entry.getValue().instance.model;
            unitInstances.values().removeIf(instance -> instance.model == model);
            entry.getValue().dispose();
            return true;
        });
        if (removed) {
            unitPicking.clear();
            attachments.geometryChanged();
        }
    }

    /** Instances borrow model buffers and textures, so drop them before either cache is disposed. */
    private void clearUnitInstances() {
        animators.clear();
        unitInstances.clear();
        casualties.dispose();
        hits.clear();
        deadUnits.clear();
        unitBounds.begin();
        unitAnchors.clear();
        unitTints.clear();
        unitDamage.clear();
        equipmentAppearance.clear();
        upperBodyTurns.clear();
        armFlips.clear();
        unitFootprints.clear();
        hover.clear();
        unitPicking.clear();
    }
    void cameraCommand(KeyCommandBind command) { boardInput.cameraCommand(-1, command); }

}
