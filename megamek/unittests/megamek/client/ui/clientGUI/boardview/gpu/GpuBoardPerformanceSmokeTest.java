/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Separated-water benchmark including edits and deployment using the actual client painters. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuBoardPerformanceSmokeTest {
    @Test
    void measuresTerrainUpdatesAndSeparatedWater() throws Exception {
        assumeFalse(Boolean.getBoolean("megamek.gpu.performanceFullView"));
        int size = Integer.getInteger("megamek.gpu.performanceSize", 24);
        Board board = board(size);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long opened = System.nanoTime();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            report("capture", opened);
            stopTimer(fixture);
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(Integer.getInteger("megamek.gpu.performanceWidth", 1280),
                  Integer.getInteger("megamek.gpu.performanceHeight", 900));
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuTactical tactical = new GpuTactical(terrain::tacticalSurface);
                    BoardRelief.Tuning original = BoardRelief.tuning();
                    try (var output = new GpuSceneTestOutput()) {
                        System.out.printf("PERF size=%d renderer=%s%n", size, Gdx.gl.glGetString(GL20.GL_RENDERER));
                        System.out.println("PERF terrain frames include the final display conversion");
                        BoardScene scene = fixture.source.takeFrame().scene();
                        long start = System.nanoTime();
                        terrain.update(scene);
                        report("terrain-open", start);
                        BoardCamera camera = new BoardCamera();
                        camera.resize(1280, 900);
                        camera.setIsometric(true);
                        camera.fit(scene);
                        measure(output, terrain, camera, "overview");
                        camera.camera.zoom = .5f;
                        camera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                        measure(output, terrain, camera, "close");
                        if (size <= 32 || Boolean.getBoolean("megamek.gpu.performanceDeployment")) {
                            start = System.nanoTime();
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.player.setStartingPos(Integer.getInteger("megamek.gpu.performanceDeploymentStart",
                                      Board.START_ANY));
                                fixture.entity.setDeployed(false);
                                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                                fixture.view.markDeploymentHexesFor(fixture.entity);
                                fixture.source.refresh();
                            });
                            report("deployment-capture", start);
                            BoardScene deployment = overlayPresentation(fixture.source.takeFrame().scene());
                            start = System.nanoTime();
                            terrain.update(deployment);
                            report("deployment-terrain", start);
                            start = System.nanoTime();
                            tactical.update(deployment);
                            report("deployment-overlay", start);
                            reportOverlayCache(tactical);
                            draw(output, terrain, camera);
                            start = System.nanoTime();
                            tactical.render(camera.camera, 0);
                            Gdx.gl.glFinish();
                            report("deployment-first-render", start);
                            reportMaskCache(tactical);
                            File screenshots = new File(System.getProperty("megamek.gpu.screenshots",
                                  "build/gpu-board-review"));
                            screenshots.mkdirs();
                            GpuBoardTestUi.capture(new File(screenshots, "performance-deployment.png"));
                            measure(output, terrain, camera, "deployment-close", tactical);
                            if (Boolean.getBoolean("megamek.gpu.performanceDeploymentOverview")) {
                                float closeZoom = camera.camera.zoom;
                                camera.fit(deployment);
                                draw(output, terrain, camera);
                                GpuBoardTestUi.capture(new File(screenshots, "performance-overview-without-deployment.png"));
                                start = System.nanoTime();
                                tactical.render(camera.camera, 0);
                                Gdx.gl.glFinish();
                                report("deployment-overview-first-render", start);
                                reportMaskCache(tactical);
                                GpuBoardTestUi.capture(new File(screenshots, "performance-deployment-overview.png"));
                                measure(output, terrain, camera, "deployment-overview", tactical);
                                if (Boolean.getBoolean("megamek.gpu.performanceDeploymentTopView")) {
                                    camera.setIsometric(false);
                                    camera.fit(deployment);
                                    draw(output, terrain, camera);
                                    tactical.render(camera.camera, 0);
                                    GpuBoardTestUi.capture(new File(screenshots, "performance-deployment-top.png"));
                                    camera.setIsometric(true);
                                }
                                camera.camera.zoom = closeZoom;
                                camera.center(BoardGeometry.center(new Coords(size / 2, size / 2), 0));
                            }
                            start = System.nanoTime();
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.entity.setDeployed(true);
                                fixture.view.redrawEntity(fixture.entity);
                                fixture.source.refresh();
                            });
                            report("deployed-unit-capture", start);
                            BoardScene placed = overlayPresentation(fixture.source.takeFrame().scene());
                            start = System.nanoTime();
                            terrain.update(placed);
                            report("deployed-unit-terrain", start);
                            start = System.nanoTime();
                            tactical.update(placed);
                            report("deployed-unit-overlay", start);
                        }
                        start = System.nanoTime();
                        SwingUtilities.invokeAndWait(() -> {
                            board.setHex(new Coords(size / 2, size / 2), new Hex(1));
                            fixture.source.refresh();
                        });
                        report("edit-capture", start);
                        scene = overlayPresentation(fixture.source.takeFrame().scene());
                        start = System.nanoTime();
                        updateWithTimeline(terrain, scene, "edit-terrain");
                        report("edit-terrain", start);
                        start = System.nanoTime();
                        tactical.update(scene);
                        report("edit-overlay", start);
                        // The frame after each edit, for comparing an atlas or mesh change pixel for pixel.
                        File screens = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        screens.mkdirs();
                        draw(output, terrain, camera);
                        GpuBoardTestUi.capture(new File(screens, "performance-edit.png"));
                        // A planting edit changes trees but no ground, water, level, road or structure.
                        start = System.nanoTime();
                        SwingUtilities.invokeAndWait(() -> {
                            Coords planted = new Coords(size / 2 + 1, size / 2);
                            Hex woods = board.getHex(planted).duplicate();
                            woods.addTerrain(new Terrain(Terrains.WOODS, 1));
                            board.setHex(planted, woods);
                            fixture.source.refresh();
                        });
                        report("woods-capture", start);
                        scene = overlayPresentation(fixture.source.takeFrame().scene());
                        start = System.nanoTime();
                        updateWithTimeline(terrain, scene, "woods-terrain");
                        report("woods-terrain", start);
                        draw(output, terrain, camera);
                        GpuBoardTestUi.capture(new File(screens, "performance-woods.png"));
                        if (size <= 32) {
                            start = System.nanoTime();
                            BoardRelief.tune(new BoardRelief.Tuning(!original.cliffsIntoWater(), original.containmentWalls()));
                            terrain.update(scene);
                            report("terrain-tuning", start);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        BoardRelief.tune(original);
                        tactical.dispose();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Board rendering benchmark", failure.get()); }
    }

    private static Board board(int size) throws Exception {
        String saved = System.getProperty("megamek.gpu.performanceBoard", "");
        if (!saved.isEmpty() && new File(saved).isFile()) {
            // As a game loads a sheet: converted, so a legacy board draws its scenery as placed objects.
            return BoardFile.loadSheet(new File(saved), false, true);
        }
        if (Boolean.getBoolean("megamek.gpu.performanceMedium")) {
            // The Random Map dialog's medium water, hills, mountains, cliffs, woods and rough-ground settings.
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardSize(size, size);
            settings.setWaterParams(2, 5, 6, 10, 30);
            settings.setElevationParams(50, 5, 0);
            settings.setMountainParams(2, 7, 10, 6, 8, 0);
            settings.setCliffParam(50);
            settings.setForestParams(4, 8, 3, 10, 30, 0);
            settings.setRoughParams(3, 8, 2, 5, 0);
            Board board = BoardUtilities.generateRandom(settings);
            if (!saved.isEmpty()) {
                try (var output = Files.newOutputStream(new File(saved).toPath())) { board.save(output); }
            }
            return board;
        }
        Random random = new Random(0x706f6e64);
        Hex[] hexes = new Hex[size * size];
        for (int i = 0; i < hexes.length; i++) {
            Hex hex = new Hex(0);
            if (random.nextFloat() < .22f) { hex.addTerrain(new Terrain(Terrains.WATER, 1)); }
            hexes[i] = hex;
        }
        return new Board(size, size, hexes);
    }

    @Test
    void measuresTheCompleteBoardViewWithLiveCapture() throws Exception {
        assumeTrue(Boolean.getBoolean("megamek.gpu.performanceFullView"));
        int size = Integer.getInteger("megamek.gpu.performanceSize", 200);
        int warmupFrames = Integer.getInteger("megamek.gpu.performanceWarmupFrames", 15);
        int sampleFrames = Integer.getInteger("megamek.gpu.performanceSampleFrames", 30);
        boolean noFinish = Boolean.getBoolean("megamek.gpu.performanceNoFinish");
        boolean fixedAtmosphere = Boolean.getBoolean("megamek.gpu.performanceFixedAtmosphere");
        boolean pinnedSettings = Boolean.getBoolean("megamek.gpu.performancePinnedSettings");
        int width = Integer.getInteger("megamek.gpu.performanceWidth", 1280);
        int height = Integer.getInteger("megamek.gpu.performanceHeight", 900);
        if (warmupFrames < 2 || sampleFrames < 2) {
            throw new IllegalArgumentException("Performance scenarios need at least two warmup and sample frames");
        }
        int familyFrames = Integer.getInteger("megamek.gpu.performanceFamilyFrames", 0);
        if (familyFrames != 0 && familyFrames < 16) {
            throw new IllegalArgumentException("Family GPU attribution needs at least sixteen separate diagnostic frames");
        }
        int scenarioFrames = warmupFrames + sampleFrames;
        boolean comparePages = Boolean.getBoolean("megamek.gpu.performanceComparePages");
        boolean compareCache = Boolean.getBoolean("megamek.gpu.performanceCompareMaterialCache");
        // High, Normal, Normal, High in one GL context, so other work on the machine weighs on both alike.
        boolean compareQuality = Boolean.getBoolean("megamek.gpu.performanceCompareQuality");
        Map<String, String> shaderBaseline = shaderSources(System.getProperty("megamek.gpu.performanceCompareShaders"));
        boolean compareShaders = !shaderBaseline.isEmpty();
        boolean waitForCache = compareCache || compareShaders || Boolean.getBoolean("megamek.gpu.performanceMaterialCache");
        // The 3D Graphics of the run's variants, the first also loads the board (for example CLASSIC,ENHANCED); none
        // keeps the saved choice. Each variant runs every scenario, the first variant's before the second's: the one
        // switch rebuilds the terrain behind the loading stage, within the second variant's first overview warm-up
        // frame (its detail-settle).
        List<BoardGraphics> graphics = graphics();
        // The Tactical View's overview, close and moving scenarios after the 3D view's, in the same JVM.
        int views = Boolean.getBoolean("megamek.gpu.performanceOverviewOnly") ? 1
              : Boolean.getBoolean("megamek.gpu.performanceTactical") ? 6 : 3;
        int comparisons = (comparePages ? 1 : 0) + (compareCache ? 1 : 0) + (compareShaders ? 1 : 0) + (compareQuality ? 1 : 0);
        if (comparisons > 1 || comparisons > 0 && graphics.size() > 1) {
            throw new IllegalArgumentException("One comparison per run: terrain pages, material cache, shaders, quality or graphics");
        }
        int variants = compareShaders || compareQuality ? 4 : comparePages || compareCache ? 2 : Math.max(1, graphics.size());
        var preferences = GUIPreferences.getInstance();
        BoardGraphics savedGraphics = preferences.getBoardGraphics();
        String savedAa = preferences.getBoardAntiAliasing();
        boolean savedSpecularAa = preferences.getBoardSpecularAntiAliasing();
        boolean savedAo = preferences.getBoardAmbientOcclusion();
        boolean savedNormals = preferences.getBoardNormalMaps();
        boolean savedTessellation = preferences.getBoardTessellation();
        boolean savedParallax = preferences.getBoardTerrainParallax();
        String savedQuality = preferences.getBoardQuality();
        float savedResolution = preferences.getBoardRenderResolution();
        TerrainLod.Tuning savedLod = TerrainLod.tuning();
        float savedUiScale = preferences.getBoardUiScale();
        float savedRenderScale = BoardCamera.renderScale;
        boolean savedShaderAa = GpuEnvironmentLighting.specularAntialiasing;
        Set<GpuTerrain.Prepass> savedPrepass = GpuTerrain.depthPrepass;
        if (pinnedSettings) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardAntiAliasing("OFF");
                preferences.setBoardSpecularAntiAliasing(true);
                preferences.setBoardAmbientOcclusion(Boolean.getBoolean("megamek.gpu.performanceAmbientOcclusion"));
                preferences.setBoardNormalMaps(Boolean.parseBoolean(System.getProperty("megamek.gpu.performanceNormalMaps", "true")));
                preferences.setBoardTessellation(Boolean.parseBoolean(System.getProperty("megamek.gpu.performanceTessellation", "true")));
                preferences.setBoardTerrainParallax(Boolean.getBoolean("megamek.gpu.performanceTerrainParallax"));
                preferences.setBoardQuality(System.getProperty("megamek.gpu.performanceQuality", "HIGH"));
                preferences.setBoardRenderResolution(1);
                preferences.setBoardUiScale(1);
            });
            // A quality comparison measures the game's own pre-pass; older comparisons keep their pinned set.
            if (System.getProperty("megamek.gpu.performanceQuality") == null) {
                GpuTerrain.depthPrepass = EnumSet.of(GpuTerrain.Prepass.CLIFFS, GpuTerrain.Prepass.FOLIAGE,
                      GpuTerrain.Prepass.IMPOSTORS);
            }
            TerrainLod.tune(new TerrainLod.Tuning(Integer.getInteger("megamek.gpu.performanceFullPixels", savedLod.fullPixels()),
                  Integer.getInteger("megamek.gpu.performanceMediumPixels", savedLod.mediumPixels())));
        }
        if (!graphics.isEmpty()) { setGraphics(graphics.getFirst()); }
        long settleNanos = Integer.getInteger("megamek.gpu.performanceSettleSeconds", 180) * 1_000_000_000L;
        // The draw audit installs GL interceptors. Leave warmup after removing them before measuring frame pacing.
        int drawCountFrame = warmupFrames / 2;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        phase("capture");
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board(size))) {
            if (Boolean.getBoolean("megamek.gpu.performanceFrozenCapture")) { stopTimer(fixture); }
            var config = GpuBoardWindow.configuration(Boolean.getBoolean("megamek.gpu.performanceVisible"));
            config.setWindowedMode(width, height);
            config.useVsync(false);
            config.setForegroundFPS(0);
            config.enableGLDebugOutput(Boolean.getBoolean("megamek.gpu.performanceGlDebug"), System.err);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int frame;
                int configuredFrame = -1;
                GpuStageTimings timings;
                GpuStageTimings total;
                GLProfiler profiler;
                GL20 rawGl20;
                Input rawInput;
                Input fixedInput;
                GpuDrawCallAudit drawAudit;
                GpuDrawCallAudit.PipelineStatistics pipelineStatistics;
                String profiledStage;
                final StringBuilder draws = new StringBuilder();
                final double[] samples = new double[sampleFrames];
                final double[] submissions = new double[sampleFrames];
                // Only intervals between measured frames: the separate draw-count frame must not enter this sample.
                final double[] intervals = new double[sampleFrames - 1];
                long previousFrameStart;
                boolean measuring;
                boolean timingFrame;
                long detailStarted;
                long loadReported;
                int detailFrames;
                int detailQuietFrames;
                final List<Double> detailSubmissions = new ArrayList<>();
                double detailSubmissionMaximum;
                long stageStarted;
                String currentStage;
                BoardAtmosphere.Settings measuredAtmosphere;
                BoardScene benchmarkScene;
                int shaderVariant = -1;
                @Override
                boolean preparePlaybackCamera(UnitPlayback state, BoardScene scene) { return true; }

                @Override
                public void create() {
                    phase("open");
                    shaderOverrides();
                    super.create();
                    rawInput = Gdx.input;
                    // Preserve hover picking under a fixed cursor without warping the user's desktop pointer.
                    // Live Swing capture and frozen capture must exercise the same pointer path.
                    rawInput.setInputProcessor(null);
                    fixedInput = (Input) Proxy.newProxyInstance(Input.class.getClassLoader(), new Class<?>[] { Input.class },
                          (proxy, method, args) -> switch (method.getName()) {
                              case "getX" -> Gdx.graphics.getWidth() / 2;
                              case "getY" -> Gdx.graphics.getHeight() / 2;
                              case "isKeyPressed", "isKeyJustPressed", "isButtonPressed", "isButtonJustPressed",
                                    "isTouched", "justTouched" -> false;
                              default -> method.invoke(rawInput, args);
                          });
                    timings = new GpuStageTimings();
                    total = new GpuStageTimings();
                    rawGl20 = Gdx.graphics.getGL20();
                    profiler = new GLProfiler(Gdx.graphics);
                    boardCamera.animateOnSelectionChange = false;
                    boardCamera.animateCombatPlayback = false;
                    boardCamera.animateOnMove = false;
                    BoardScene scene = fixture.source.takeFrame().scene();
                    benchmarkScene = scene;
                    String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                    String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "").trim();
                    assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expectedRenderer.toLowerCase(Locale.ROOT)),
                          () -> "Expected renderer containing '" + expectedRenderer + "', got " + renderer);
                    assertEquals(width, Gdx.graphics.getBackBufferWidth(), "Requested physical framebuffer width");
                    assertEquals(height, Gdx.graphics.getBackBufferHeight(), "Requested physical framebuffer height");
                    var tuning = GpuBoardTestUi.tuning(this);
                    if (System.getProperty("megamek.gpu.performanceMaterialCache") != null) {
                        CheckBox cache = GpuBoardTestUi.tuning(tuning, "tuning-terrain-cache");
                        cache.setChecked(Boolean.getBoolean("megamek.gpu.performanceMaterialCache"));
                    }
                    if (pinnedSettings) {
                        assertEquals(1, BoardCamera.renderScale);
                        assertEquals(false, tuning.antiAliasing());
                        assertEquals(true, GpuEnvironmentLighting.specularAntialiasing);
                        assertEquals(Boolean.getBoolean("megamek.gpu.performanceAmbientOcclusion"), tuning.ambientOcclusion());
                        assertEquals("NORMAL".equalsIgnoreCase(System.getProperty("megamek.gpu.performanceQuality")),
                              tuning.normalQuality());
                        if (System.getProperty("megamek.gpu.performanceQuality") == null) {
                            assertEquals(Set.of(GpuTerrain.Prepass.CLIFFS, GpuTerrain.Prepass.FOLIAGE,
                                  GpuTerrain.Prepass.IMPOSTORS), GpuTerrain.depthPrepass);
                        }
                    }
                    System.out.printf("PERF settings renderScale=%.2f specularAA=%s SMAA=%s AO=%s prepass=%s uiScale=%.2f%n",
                          BoardCamera.renderScale, GpuEnvironmentLighting.specularAntialiasing, tuning.antiAliasing(),
                          tuning.ambientOcclusion(), GpuTerrain.depthPrepass, preferences.getBoardUiScale());
                    System.out.printf("PERF terrain normals=%s tessellation=%s parallax=%s lod=%s%n",
                          tuning.normalMaps(), tuning.tessellation(), tuning.terrainParallax(), TerrainLod.tuning());
                    System.out.println("PERF total GPU is measured independently; stage percentiles must not be summed. "
                          + "Animation/FFT is included in cutaways and light; serialized wall time is not pipelined FPS.");
                    System.out.printf("PERF full-view board=%dx%d framebuffer=%dx%d renderer=%s%n", scene.width(), scene.height(),
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Gdx.gl.glGetString(GL20.GL_RENDERER));
                    System.out.printf("PERF full-view warmup=%d samples=%d synchronization=%s%n",
                          warmupFrames, sampleFrames, noFinish ? "none" : "glFinish");
                    System.out.printf("PERF frozenCapture=%s%n", Boolean.getBoolean("megamek.gpu.performanceFrozenCapture"));
                    System.out.printf("PERF graphics=%s%n", graphics.isEmpty()
                          ? List.of(GUIPreferences.getInstance().getBoardGraphics()) : graphics);
                }

                @Override
                void updateCameraFocus(BoardScene scene, BoardFocus request, BoardScene.Animation action) {
                    if (frame == 0) { super.updateCameraFocus(scene, request, action); }
                }

                @Override
                void renderStage(String stage) {
                    if (pipelineStatistics != null) { pipelineStatistics.stage(stage); }
                    long now = System.nanoTime();
                    if (currentStage != null && now - stageStarted > 8_000_000) {
                        SlowStage event = new SlowStage();
                        event.stage = currentStage;
                        event.milliseconds = (now - stageStarted) / 1e6;
                        event.commit();
                    }
                    currentStage = stage;
                    stageStarted = now;
                    if (timingFrame) { timings.stage(stage); }
                    if (drawAudit != null) { drawAudit.stage(stage); }
                    if (profiler.isEnabled()) {
                        if (profiledStage != null) {
                            draws.append(String.format(java.util.Locale.ROOT, "%s,%d,%.0f,%d,%d,%d%n", profiledStage,
                                  profiler.getDrawCalls(), profiler.getVertexCount().total, profiler.getShaderSwitches(),
                                  profiler.getTextureBindings(), profiler.getCalls()));
                        }
                        profiler.reset();
                        profiledStage = stage;
                    }
                }

                @Override
                public void render() {
                    long frameStart = System.nanoTime();
                    try {
                        Gdx.input = fixedInput;
                        int phase = frame % scenarioFrames;
                        // Detail-settle retries keep the pose and controls of this logical frame.
                        boolean configureFrame = configuredFrame != frame;
                        configuredFrame = frame;
                        // Every scenario of the first variant, then every scenario of the second.
                        int variant = frame / (scenarioFrames * views);
                        int view = frame / scenarioFrames % views;
                        if (compareShaders && shaderVariant != variant) {
                            // ABBA in one GL context: the editor rebuilds the installed programs, retaining meshes,
                            // material families and draw order. Compilation precedes every warm-up, never a sample.
                            applyShaders(this, variant == 0 || variant == 3 ? shaderBaseline : Map.of());
                            shaderVariant = variant;
                        }
                        // Overview, close and moving: in the 3D view, then (views 3-5) in the Tactical View.
                        int shot = view % 3;
                        if (configureFrame && comparePages && phase == 0) { setPages(this, variant == 1); }
                        if (configureFrame && compareCache && phase == 0) {
                            CheckBox cache = GpuBoardTestUi.tuning(GpuBoardTestUi.tuning(this), "tuning-terrain-cache");
                            cache.setChecked(variant == 1);
                        }
                        BoardGraphics shown = graphics.isEmpty() ? null : graphics.get(Math.min(variant, graphics.size() - 1));
                        if (configureFrame && variant > 0 && view == 0 && phase == 0) {
                            // The next variant starts from the 3D view's fitted overview, as the first did.
                            setTacticalView(false);
                            boardCamera.setIsometric(!Boolean.getBoolean("megamek.gpu.performanceTopView"));
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        }
                        // The switch takes effect in this warmup frame, which builds what the variant first draws.
                        if (configureFrame && graphics.size() > 1 && view == 0 && phase == 0) { setGraphics(shown); }
                        if (configureFrame && compareQuality && view == 0 && phase == 0) {
                            SelectBox<String> quality = GpuBoardTestUi.tuning(GpuBoardTestUi.tuning(this), "tuning-quality");
                            quality.setSelectedIndex(variant == 0 || variant == 3 ? 1 : 0);
                            String resolution = System.getProperty("megamek.gpu.performanceNormalResolution");
                            if (resolution != null) {
                                Slider scale = GpuBoardTestUi.tuning(GpuBoardTestUi.tuning(this), "tuning-render-resolution");
                                scale.setValue(variant == 0 || variant == 3 ? 100 : Float.parseFloat(resolution));
                            }
                        }
                        if (configureFrame && frame == 1) {
                            boardCamera.setIsometric(!Boolean.getBoolean("megamek.gpu.performanceTopView"));
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        }
                        if (configureFrame && shot == 0 && (frame == 1 || variant > 0 && phase == 0)) {
                            overviewCamera(boardCamera);
                        }
                        if (configureFrame && view == 3 && phase == 0) {
                            // T: the locked top view with meeples and the ground layer, framed as the 3D overview.
                            setTacticalView(true);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        }
                        // The close view starts at the board's centre; each variant's moving view pans on from there.
                        if (configureFrame && shot == 1 && phase == 0) {
                            boardCamera.camera.zoom = Float.parseFloat(System.getProperty("megamek.gpu.performanceZoom", ".5"));
                            BoardScene scene = fixture.source.takeFrame().scene();
                            boardCamera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                        }
                        // Keep the original total pan distance when taking more samples, independent of frame time.
                        if (shot == 2) {
                            boardCamera.pan(BoardGeometry.WIDTH * .25f * 45 / scenarioFrames, 0);
                        }
                        String scenario = (view >= 3 ? "tactical-" : "") + (shot == 0 ? "overview" : shot == 1 ? "close" : "moving");
                        if (comparePages) { scenario += variant == 0 ? "-original" : "-pages"; }
                        if (compareCache) { scenario += variant == 0 ? "-cache-off" : "-cache-on"; }
                        if (compareShaders) {
                            scenario += (variant == 0 || variant == 3 ? "-baseline-" : "-candidate-") + variant;
                        }
                        if (compareQuality) { scenario += (variant == 0 || variant == 3 ? "-high-" : "-normal-") + variant; }
                        if (shown != null) { scenario += "-" + shown.name().toLowerCase(Locale.ROOT); }
                        if (phase == 0 && detailFrames == 0) {
                            phase(frame == 0 ? "terrain-build" : scenario + "-warmup");
                            detailStarted = System.nanoTime();
                            detailSubmissionMaximum = 0;
                            detailQuietFrames = 0;
                            detailSubmissions.clear();
                        }
                        if (phase == warmupFrames) {
                            if (Boolean.getBoolean("megamek.gpu.performanceAssertPlainGround")) {
                                // A source diagnostic may erase only channels proven absent from every sculpt layer.
                                var plain = GpuTerrain.class.getDeclaredMethod("plainSculptFinish");
                                plain.setAccessible(true);
                                assertEquals(true, plain.invoke(terrain(this)), "Expected non-emissive dielectric sculpt materials");
                            }
                            if (fixedAtmosphere) {
                                assertEquals(measuredAtmosphere, GpuBoardTestUi.tuning(this).atmosphere());
                                assertEquals(false, GpuBoardTestUi.tuning(this).fixedSun());
                            }
                            phase(scenario + "-sample");
                        }
                        measuring = phase >= warmupFrames;
                        timingFrame = phase != drawCountFrame;
                        if (phase > warmupFrames) {
                            intervals[phase - warmupFrames - 1] = (frameStart - previousFrameStart) / 1e6;
                        }
                        previousFrameStart = frameStart;
                        if (phase == drawCountFrame) {
                            draws.setLength(0);
                            if (Boolean.getBoolean("megamek.gpu.performanceDrawAudit") && shot != 2) {
                                drawAudit = new GpuDrawCallAudit(this);
                            }
                            profiler.enable();
                        }
                        if (measuring) {
                            assertSame(rawGl20, Gdx.gl20, "Draw counting must not leave error-checking wrappers in timed frames");
                        }
                        if (timingFrame) {
                            timings.beginFrame(measuring);
                            total.beginFrame(measuring);
                        }
                        long start = System.nanoTime();
                        if (timingFrame) { total.stage("complete board view"); }
                        super.render();
                        if (timingFrame) { total.stage(null); }
                        if (fixedAtmosphere && measuredAtmosphere == null) {
                            // The first frame has adopted the random scenario time; set the normal HUD control.
                            var tuning = GpuBoardTestUi.tuning(this);
                            Slider hour = GpuBoardTestUi.tuning(tuning, "Time of day");
                            var clear = BoardAtmosphere.DEFAULTS;
                            hour.setValue(clear.hour());
                            // Apply even when the random minute already matched; retain the actual slider grid.
                            hour.fire(new ChangeListener.ChangeEvent());
                            tuning.setFixedSun(false);
                            measuredAtmosphere = new BoardAtmosphere.Settings(hour.getValue(), clear.clouds(),
                                  clear.fog(), clear.groundLayerHeight(), clear.haze(), clear.exposure());
                            assertEquals(measuredAtmosphere, tuning.atmosphere(), "Expected the clear-day fixture");
                            System.out.printf("PERF fixed atmosphere=%s options=%s%n",
                                  measuredAtmosphere, tuning.atmosphereOptions());
                        }
                        if (timingFrame) { timings.stage(null); }
                        long submitted = System.nanoTime();
                        if (!noFinish) { Gdx.gl.glFinish(); }
                        if (phase == drawCountFrame) {
                            renderStage(null);
                            GpuStageTimings.stopCounting(profiler, rawGl20);
                            System.out.printf("PERF full-%s draw counts%n stage,draws,vertices,shaderSwitches,textureBindings,glCalls%n%s",
                                  scenario, draws);
                            if (drawAudit != null) {
                                drawAudit.close();
                                drawAudit.report(scenario);
                                drawAudit = null;
                            }
                        }
                        if (phase < drawCountFrame) {
                            detailSubmissionMaximum = Math.max(detailSubmissionMaximum, (submitted - start) / 1e6);
                        }
                        // A terrain-ready frame may still be covered by the shader warm-up's loading stage.
                        // Count opening only after an actual board frame reaches the screen.
                        // Camera changes may queue several meshes. Measure steady frames only after they settle;
                        // three idle frames avoid mistaking the gap between two chunk jobs for completion.
                        // The moving scenario intentionally includes refinement during panning.
                        boolean pendingDetail = phase < drawCountFrame && shot != 2
                              && (frame == 0 ? frames() == 0 : detailPending(this, benchmarkScene));
                        if (phase < drawCountFrame && shot != 2
                              && (phase == 0 || detailFrames > 0 || pendingDetail
                                    || frame > 0 && waitForCache && cachePending(terrain(this)))) {
                            detailQuietFrames = pendingDetail
                                  || frame > 0 && waitForCache && cachePending(terrain(this)) ? 0 : detailQuietFrames + 1;
                            detailFrames++;
                            detailSubmissions.add((submitted - start) / 1e6);
                            if (Boolean.getBoolean("megamek.gpu.performanceLoadProgress")
                                  && submitted - loadReported >= 10_000_000_000L) {
                                loadReported = submitted;
                                GpuTerrain loading = terrain(this);
                                System.out.printf("PERF loading elapsed=%.1fs progress=%d shaders=%s details=%s cache=%s%n",
                                      (submitted - detailStarted) / 1e9, loading.buildProgress(), loading.shaderProgress(),
                                      loading.buildDetails(), loading.materialCacheStatus());
                            }
                            boolean withinDeadline = System.nanoTime() - detailStarted < settleNanos;
                            if (!withinDeadline) { recordPendingDetail(this, benchmarkScene, boardCamera, scenario); }
                            assertTrue(withinDeadline, "Visible detail must settle");
                            if (detailQuietFrames < 3) { return; }
                        }
                        if (detailFrames > 0) {
                            double[] buildSamples = detailSubmissions.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                            System.out.printf("PERF full-%s detail-settle=%.3f ms frames=%d maxSubmission=%.3f ms%n", scenario,
                                  (System.nanoTime() - detailStarted) / 1e6, detailFrames, detailSubmissionMaximum);
                            System.out.printf("PERF full-%s building-submission median=%.3f ms p95=%.3f ms p99=%.3f ms%n", scenario,
                                  percentile(buildSamples, .5), percentile(buildSamples, .95), percentile(buildSamples, .99));
                            detailFrames = 0;
                        }
                        if (frame == 0) {
                            report("full-view-open", detailStarted);
                            if (Boolean.getBoolean("megamek.gpu.performanceLoadOnly")) {
                                phase("interactive");
                                System.out.println("PERF initial " + boardCamera.readout());
                                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                                output.mkdirs();
                                GpuBoardTestUi.capture(new File(output, "performance-initial-map.png"));
                                Gdx.app.exit();
                                return;
                            }
                            phase("overview-warmup");
                        }
                        if (measuring) {
                            samples[phase - warmupFrames] = (System.nanoTime() - start) / 1e6;
                            submissions[phase - warmupFrames] = (submitted - start) / 1e6;
                            assertTrue(terrain(this).ready(benchmarkScene),
                                  "Measured frames must draw the ready board, not the loading stage");
                        }
                        if (phase == scenarioFrames - 1) {
                            phase(scenario + "-report");
                            var familyPrograms = terrain(this).familyPrograms();
                            System.out.printf("PERF full-%s familyPrograms=%d/%d%n", scenario,
                                  familyPrograms.ready().size(), familyPrograms.requested());
                            Arrays.sort(samples);
                            Arrays.sort(submissions);
                            Arrays.sort(intervals);
                            if (!noFinish) {
                                System.out.printf("PERF full-%s median=%.3f ms p95=%.3f ms%n",
                                      scenario, percentile(samples, .5), percentile(samples, .95));
                            }
                            System.out.printf("PERF full-%s submission median=%.3f ms p95=%.3f ms%n",
                                  scenario, percentile(submissions, .5), percentile(submissions, .95));
                            System.out.printf("PERF full-%s frame-interval median=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms averageFPS=%.2f intervals=%d%n",
                                  scenario, percentile(intervals, .5), percentile(intervals, .95),
                                  percentile(intervals, .99), intervals[intervals.length - 1],
                                  1000 / Arrays.stream(intervals).average().orElseThrow(), intervals.length);
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            output.mkdirs();
                            // Readback completes this scenario's queries before appendReport drains them. Reuse the
                            // query objects across scenarios, avoiding another first-use driver stall at each zoom.
                            GpuBoardTestUi.capture(new File(output, "performance-full-" + scenario + ".png"));
                            StringBuilder report = new StringBuilder();
                            total.appendReport(report, scenario + " total");
                            timings.appendReport(report, scenario + " stages");
                            System.out.print(report);
                            Files.writeString(new File(output, "performance-full-" + scenario + "-timings.txt").toPath(), report);
                            memory(this, scenario);
                            System.out.println("PERF " + boardCamera.readout());
                            System.out.println("PERF " + terrain(this).materialCacheReport());
                            if (compareCache && variant == 1 && shot == 0) {
                                var cache = terrain(this).materialCacheStatus();
                                String expected = System.getProperty("megamek.gpu.performanceCacheCoverage", "");
                                if (expected.equals("partial")) {
                                    assertTrue(cache.cachedTopChunks() + cache.cachedWallChunks() > 0,
                                          "A constrained view must actually draw cached regions: " + cache);
                                    assertTrue(!terrain(this).materialCacheComplete(), "This fixture must exercise partial coverage");
                                } else if (expected.equals("full")) {
                                    assertTrue(terrain(this).materialCacheComplete(), "Expected full cache coverage: " + cache);
                                }
                            }
                            if (familyFrames > 0 && shot != 2) {
                                // No query wrappers, attribution work or readbacks enter the normal measured frames.
                                measuring = false;
                                timingFrame = false;
                                try (var audit = new GpuDrawCallAudit(this, true)) {
                                    drawAudit = audit;
                                    for (int diagnostic = -3; diagnostic < familyFrames; diagnostic++) {
                                        audit.beginTimingFrame();
                                        super.render();
                                        audit.endTimingFrame(diagnostic >= 0);
                                        assertTrue(terrain(this).ready(benchmarkScene), "Family diagnostic draws the ready board");
                                    }
                                    String attribution = audit.timingReport(scenario);
                                    System.out.print(attribution);
                                    Files.writeString(new File(output, "performance-full-" + scenario + "-families.txt").toPath(),
                                          attribution);
                                } finally { drawAudit = null; }
                            }
                            if (Boolean.getBoolean("megamek.gpu.performancePipelineStatistics") && shot != 2) {
                                // Hardware work counts and synchronous readback are diagnostic only, after timings.
                                measuring = timingFrame = false;
                                try (var statistics = new GpuDrawCallAudit.PipelineStatistics()) {
                                    pipelineStatistics = statistics;
                                    super.render();
                                    String counts = statistics.report(scenario);
                                    System.out.print(counts);
                                    Files.writeString(new File(output, "performance-full-" + scenario + "-pipeline.txt").toPath(),
                                          counts);
                                } finally { pipelineStatistics = null; }
                            }
                            if (compareCache) {
                                // Readback and debug frames stay outside both timing and draw-count samples.
                                measuring = timingFrame = false;
                                CheckBox debug = GpuBoardTestUi.tuning(GpuBoardTestUi.tuning(this),
                                      "tuning-terrain-cache-debug");
                                debug.setChecked(true);
                                super.render();
                                GpuBoardTestUi.capture(new File(output, "performance-full-" + scenario + "-debug.png"));
                                debug.setChecked(false);
                            }
                            if (view == 2 && Boolean.getBoolean("megamek.gpu.performanceFarPan")) {
                                // Extra visual regression capture, after all performance samples are complete.
                                measuring = false;
                                timingFrame = false;
                                boardCamera.fit(fixture.source.takeFrame().scene());
                                boardCamera.camera.zoom = 40;
                                boardCamera.pan(0, 180);
                                super.render();
                                GpuBoardTestUi.capture(new File(output, "performance-full-far-pan.png"));
                            }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (++frame == views * scenarioFrames * variants) { phase("done"); Gdx.app.exit(); }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    } finally {
                        Gdx.input = rawInput;
                    }
                }

                @Override
                public void dispose() {
                    if (drawAudit != null) { drawAudit.close(); }
                    if (profiler.isEnabled()) { GpuStageTimings.stopCounting(profiler, rawGl20); }
                    timings.close();
                    total.close();
                    super.dispose();
                }

            }, config);
        } finally {
            if (System.getProperty("megamek.gpu.performanceShaderDirectory") != null) { GpuShaderSource.forget(); }
            setGraphics(savedGraphics);
            if (pinnedSettings) {
                SwingUtilities.invokeAndWait(() -> {
                    preferences.setBoardAntiAliasing(savedAa);
                    preferences.setBoardSpecularAntiAliasing(savedSpecularAa);
                    preferences.setBoardAmbientOcclusion(savedAo);
                    preferences.setBoardNormalMaps(savedNormals);
                    preferences.setBoardTessellation(savedTessellation);
                    preferences.setBoardTerrainParallax(savedParallax);
                    preferences.setBoardQuality(savedQuality);
                    preferences.setBoardRenderResolution(savedResolution);
                    preferences.setBoardUiScale(savedUiScale);
                });
                BoardCamera.renderScale = savedRenderScale;
                GpuEnvironmentLighting.specularAntialiasing = savedShaderAa;
                GpuTerrain.depthPrepass = savedPrepass;
                TerrainLod.tune(savedLod);
            }
        }
        if (failure.get() != null) { throw new AssertionError("Full board rendering benchmark", failure.get()); }
    }

    /** {@code megamek.gpu.performanceGraphics}: a comma-separated list of 3D Graphics names, empty for none. */
    private static List<BoardGraphics> graphics() {
        String names = System.getProperty("megamek.gpu.performanceGraphics", "");
        return names.isBlank() ? List.of() : Arrays.stream(names.split(","))
              .map(name -> BoardGraphics.valueOf(name.trim().toUpperCase(Locale.ROOT))).toList();
    }

    /** Diagnostic shader substitutions live only in this test JVM; installation and checkout files are untouched. */
    @SuppressWarnings("unchecked")
    private static void shaderOverrides() {
        String folder = System.getProperty("megamek.gpu.performanceShaderDirectory");
        if (folder == null) { return; }
        try {
            var field = GpuShaderSource.class.getDeclaredField("DISK");
            field.setAccessible(true);
            Map<String, String> disk = (Map<String, String>) field.get(null);
            for (var entry : shaderSources(folder).entrySet()) {
                disk.put(Configuration.dataDir().getPath() + '\n' + entry.getKey(), entry.getValue());
                System.out.println("PERF diagnostic shader=" + new File(folder, entry.getKey()));
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Diagnostic shader substitution", error);
        }
    }

    /** A saved set of shader files, read once before the GL run; no checkout or installed files are changed. */
    private static Map<String, String> shaderSources(String folder) {
        if (folder == null) { return Map.of(); }
        var sources = new TreeMap<String, String>();
        try (var files = Files.list(new File(folder).toPath())) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                sources.put(file.getFileName().toString(), Files.readString(file));
            }
        } catch (IOException error) { throw new IllegalStateException("Diagnostic shader sources", error); }
        if (sources.isEmpty()) { throw new IllegalArgumentException("No diagnostic shaders in " + folder); }
        return sources;
    }

    private static void applyShaders(GpuBattleView view, Map<String, String> sources) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("shaderEdits");
        field.setAccessible(true);
        var session = (GpuShaderManager) field.get(view);
        session.run(() -> {
            var result = session.apply(sources);
            assertTrue(result.success(), () -> "Shader comparison must compile: " + result);
            System.out.println("PERF shader comparison " + result);
        });
        terrain(view).shadersChanged();
    }

    /** Chooses the 3D Graphics on the Swing thread, as the menus do; the view applies it in its next frame. */
    private static void setGraphics(BoardGraphics graphics) throws Exception {
        SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setBoardGraphics(graphics));
    }

    @Name("megamek.GpuBenchmarkPhase")
    @StackTrace(false)
    private static final class Phase extends Event {
        String phase;
    }

    @Name("megamek.GpuSlowStage")
    @StackTrace(false)
    private static final class SlowStage extends Event {
        String stage;
        double milliseconds;
    }

    private static void phase(String name) {
        Phase event = new Phase();
        event.phase = name;
        event.commit();
        System.out.printf("PERF phase=%s at=%s%n", name, Instant.now());
    }

    private static GpuTerrain terrain(GpuBattleView view) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("terrain");
        field.setAccessible(true);
        return (GpuTerrain) field.get(view);
    }

    private static void setPages(GpuBattleView view, boolean enabled) throws Exception {
        var field = GpuTerrain.class.getDeclaredField("terrainPages");
        field.setAccessible(true);
        Object pages = field.get(terrain(view));
        var method = pages.getClass().getDeclaredMethod("setEnabled", boolean.class);
        method.setAccessible(true);
        method.invoke(pages, enabled);
    }

    /** Optional reproducible camera readout: span, viewport-centre world position, azimuth and tilt. */
    private static void overviewCamera(BoardCamera camera) {
        String span = System.getProperty("megamek.gpu.performanceSpan");
        String at = System.getProperty("megamek.gpu.performanceAt");
        if (span == null || at == null) { return; }
        float azimuth = Float.parseFloat(System.getProperty("megamek.gpu.performanceAzimuth", Float.toString(camera.azimuth())));
        float tilt = Float.parseFloat(System.getProperty("megamek.gpu.performanceTilt", Float.toString(camera.tilt())));
        camera.orbit(azimuth - camera.azimuth(), tilt - camera.tilt());
        String[] xyz = at.split(",");
        camera.camera.zoom = Float.parseFloat(span) / camera.camera.viewportHeight;
        Vector3 centre = new Vector3(Float.parseFloat(xyz[0]), Float.parseFloat(xyz[1]), Float.parseFloat(xyz[2]));
        camera.center(centre);
        Vector3 shown = new Vector3(camera.camera.position).mulAdd(camera.camera.direction, camera.camera.pivotDepth);
        camera.center(new Vector3(camera.focus).add(centre).sub(shown));
    }

    private static boolean cachePending(GpuTerrain terrain) {
        var status = terrain.materialCacheStatus();
        return status.active() && status.pages().current() < status.pages().needed();
    }

    private static boolean detailPending(GpuBattleView view, BoardScene scene) throws Exception {
        GpuTerrain terrain = terrain(view);
        return terrain.busy() || !terrain.ready(scene) || terrain.pagesMerging();
    }

    /** Failure-only evidence from the same owners the loading stage waits for; no extra polling or rendering. */
    private static void recordPendingDetail(GpuBattleView view, BoardScene scene, BoardCamera camera, String scenario)
          throws Exception {
        GpuTerrain terrain = terrain(view);
        GpuFamilyPrograms families = terrain.familyPrograms();
        var report = new StringBuilder("Visible detail deadline expired; original readiness assertion retained.\n")
              .append("busy=").append(terrain.busy()).append(" ready=").append(terrain.ready(scene))
              .append(" progress=").append(terrain.buildProgress()).append(" shaders=").append(terrain.shaderProgress())
              .append("\ndetails=").append(terrain.buildDetails())
              .append("\nfamily requested=").append(families.requested()).append(" ready=").append(families.ready().size())
              .append(" built=").append(families.programs()).append(" pending=").append(families.pending())
              .append(" building=").append(families.building());
        for (String name : List.of("requested", "rebuild", "detailJob", "terrainMaterialPreparation")) {
            var field = GpuTerrain.class.getDeclaredField(name);
            field.setAccessible(true);
            Object owner = field.get(terrain);
            report.append('\n').append(name).append(" present=").append(owner != null);
            if (owner instanceof GpuGlsl.Preparation preparation) { report.append(" linking=").append(preparation.linking()); }
            if (name.equals("detailJob") && owner != null) {
                for (String property : List.of("index", "lod", "rebuilding", "ready")) {
                    var method = owner.getClass().getDeclaredMethod(property);
                    method.setAccessible(true);
                    report.append(' ').append(property).append('=').append(method.invoke(owner));
                }
            }
        }
        report.append("\ncamera zoom=").append(camera.camera.zoom).append(" focus=").append(camera.focus)
              .append(" position=").append(camera.camera.position).append(" direction=").append(camera.camera.direction).append('\n');
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        output.mkdirs();
        System.out.print(report);
        Files.writeString(new File(output, "performance-full-" + scenario + "-settle-failure.txt").toPath(), report);
        GpuBoardTestUi.capture(new File(output, "performance-full-" + scenario + "-settle-failure.png"));
    }

    private static void memory(GpuBattleView view, String scenario) throws Exception {
        // Outside measured frames. Report installed tiers and heap after collecting temporary build objects.
        var field = GpuTerrain.class.getDeclaredField("chunks");
        field.setAccessible(true);
        Map<String, Integer> levels = new java.util.TreeMap<>();
        for (Object chunk : (List<?>) field.get(terrain(view))) {
            var level = chunk.getClass().getDeclaredField("lod");
            level.setAccessible(true);
            levels.merge(level.get(chunk).toString(), 1, Integer::sum);
        }
        System.gc();
        var runtime = Runtime.getRuntime();
        System.out.printf("PERF full-%s heapAfterGc=%.1f MiB installed=%s%n", scenario,
              (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0, levels);
        for (String name : List.of("propBatch", "terrainPages")) {
            var cacheField = GpuTerrain.class.getDeclaredField(name);
            cacheField.setAccessible(true);
            Object cache = cacheField.get(terrain(view));
            var rebuilds = cache.getClass().getDeclaredMethod("rebuilds");
            rebuilds.setAccessible(true);
            System.out.printf("PERF full-%s %s builds=%s geometryMiB=%.3f (CPU and GPU each store a copy)%n",
                  scenario, name, rebuilds.invoke(cache), GpuDrawCallAudit.pageBytes(cache) / 1048576.0);
        }
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * fraction))];
    }

    private static void stopTimer(GpuBoardFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var field = GpuBoardSource.class.getDeclaredField("timer");
                field.setAccessible(true);
                ((Timer) field.get(fixture.source)).stop();
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    /**
     * Drives an update to completion with the editor's frame slice, printing the wall time of every stage the
     * terrain reports, so an edit's cost can be read per stage rather than as one number.
     */
    private static void updateWithTimeline(GpuTerrain terrain, BoardScene scene, String name) {
        long stageStart = System.nanoTime();
        String stage = "";
        terrain.update(scene, null);
        while (terrain.busy()) {
            terrain.refine(null, true);
            String current = terrain.buildDetails().stream()
                  .map(status -> status.section() + ":" + status.step().task()).collect(Collectors.joining(" "));
            if (!current.equals(stage)) {
                long now = System.nanoTime();
                if (!stage.isEmpty()) { System.out.printf("PERF %s stage %-48s %8.1f ms%n", name, stage, (now - stageStart) / 1e6); }
                stage = current;
                stageStart = now;
            }
            if (terrain.busy()) { LockSupport.parkNanos(200_000); }
        }
        System.out.printf("PERF %s stage %-48s %8.1f ms%n", name, stage.isEmpty() ? "(done)" : stage, (System.nanoTime() - stageStart) / 1e6);
    }

    private static void report(String name, long start) {
        System.out.printf("PERF %s %.3f ms%n", name, (System.nanoTime() - start) / 1e6);
    }

    /** Exercise the draped mask path with exactly the deployment painter's captured borders and legal state. */
    private static BoardScene overlayPresentation(BoardScene scene) {
        if (!Boolean.getBoolean("megamek.gpu.performanceHexMasks")) { return scene; }
        var captured = scene.tactical();
        List<BoardTactical.Fill> fills = captured.fills().stream().map(fill -> {
            BoardTactical.HexBorder border = fill.border();
            return border == null ? fill : new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback(),
                  new BoardTactical.HexBorder(border.anchor(), border.padding(), border.width(), border.scale()));
        }).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(), scene.plannedPath(),
              scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(), scene.rangeBorders(),
              scene.markers(), new BoardTactical(fills, captured.labels(), captured.walls(), captured.flatWalls()),
              scene.rangeLabels(), scene.fieldOfView());
    }

    private static void reportMaskCache(GpuTactical tactical) throws ReflectiveOperationException {
        for (String name : List.of("hexMasks", "deploymentTint")) {
            var field = GpuTactical.class.getDeclaredField(name);
            field.setAccessible(true);
            var masks = (GpuHexMasks) field.get(tactical);
            System.out.printf("PERF %s active=%s carrier-payload=%.3fMiB%n", name, masks.active(), masks.bytes() / 1048576.0);
        }
    }

    /** Exact retained vertex payload, excluding map/object overhead and mesh/GPU buffers; old variants have no cache. */
    private static void reportOverlayCache(GpuTactical tactical) throws ReflectiveOperationException {
        Set<float[]> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        long dependencies = 0;
        for (String name : List.of("fills", "walls")) {
            java.lang.reflect.Field field;
            try { field = GpuTactical.class.getDeclaredField(name); }
            catch (NoSuchFieldException oldVariant) { return; }
            field.setAccessible(true);
            for (Object geometry : ((Map<?, ?>) field.get(tactical)).values()) {
                for (var component : geometry.getClass().getRecordComponents()) {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    if (component.getType() == float[].class) { arrays.add((float[]) accessor.invoke(geometry)); }
                    if (component.getName().equals("surfaces")) {
                        dependencies += ((Map<?, ?>) accessor.invoke(geometry)).size();
                    }
                }
            }
        }
        long bytes = arrays.stream().mapToLong(array -> (long) array.length * Float.BYTES).sum();
        System.out.printf("PERF overlay-cache vertex-payload=%.3fMiB arrays=%d surface-references=%d%n",
              bytes / 1048576.0, arrays.size(), dependencies);
    }

    private static void draw(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera) {
        terrain.animate(1 / 60f, List.of());
        terrain.renderShadows(camera.camera, List.of());
        output.begin(.08f, .08f, .08f, 1);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        output.display();
    }

    private static void measure(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera, String name) {
        measure(output, terrain, camera, name, null);
    }

    private static void measure(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera, String name,
          GpuTactical tactical) {
        double[] samples = new double[20];
        for (int i = -10; i < samples.length; i++) {
            long start = System.nanoTime();
            draw(output, terrain, camera);
            if (tactical != null) { tactical.render(camera.camera, 0); }
            Gdx.gl.glFinish();
            if (i >= 0) { samples[i] = (System.nanoTime() - start) / 1e6; }
        }
        GL20 rawGl20 = Gdx.gl20;
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        draw(output, terrain, camera);
        if (tactical != null) { tactical.render(camera.camera, 0); }
        GpuStageTimings.stopCounting(profiler, rawGl20);
        Arrays.sort(samples);
        System.out.printf("PERF %s median=%.3f ms p95=%.3f ms draws=%d vertices=%.0f%n",
              name, samples[10], samples[19], profiler.getDrawCalls(), profiler.getVertexCount().total);
    }
}
