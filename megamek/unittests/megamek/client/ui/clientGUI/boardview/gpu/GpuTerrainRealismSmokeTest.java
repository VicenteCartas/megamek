/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Repeatable shipped-landscape review through the real scene compositor; does not load or alter unit models. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuTerrainRealismSmokeTest {
    private static final boolean CAPTURE_ONLY = Boolean.getBoolean("megamek.gpu.realismCaptureOnly");
    private record Landscape(String name, String path) { }
    private record Conditions(String name, BoardAtmosphere.Settings settings) {
        Conditions(String name, float hour, float clouds, float fog) {
            this(name, new BoardAtmosphere.Settings(hour, clouds, fog, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        }
    }
    private record Scene(Landscape landscape, BoardScene board) { }
    private record View(Scene scene, boolean top, Conditions conditions) {
        String name() { return scene.landscape.name + (top ? "-top-" : "-iso-") + conditions.name; }
    }
    private record Variant(String name, String repeat, Map<String, String> sources) { }

    private static final List<Landscape> LANDSCAPES = List.of(
          new Landscape("grassland", "AGoAC Maps/16x17 Grassland 3.board"),
          new Landscape("forest", "Map Set 4/16x17 Heavy Forest 1.board"),
          new Landscape("canyon", "Map Pack Savannahs/16x17 Box Canyon (Savannah).board"),
          new Landscape("lake", "Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"),
          new Landscape("lava", "Map Pack Volcanic/16x17 Lava Tubes 1.board"),
          new Landscape("dome-vent", "Map Pack Volcanic/16x17 Dome Vent 1.board"),
          new Landscape("box-canyon", "Savannah BattleMats/32x17 Box Canyon.board"),
          new Landscape("fungus", "Alien Worlds/32x17 Fungal Crevasse.board"),
          new Landscape("misery", "Legendary Battles BattleMats/32x17 Misery.board"),
          new Landscape("nebraska", "unofficial/Lanceman/16x17 Nebraska North Field.board"),
          new Landscape("calibration", "Calibration/32x34 Terrain Material Calibration.board"));
    private static final List<Conditions> CONDITIONS = List.of(new Conditions("morning", 9, 0, 0),
          new Conditions("midday", 13, 0, 0), new Conditions("overcast", 13, .9f, .04f));

    @Test
    void reviewsShippedLandscapesAndMeasuresCompleteTerrainFrames() throws Exception {
        var selected = Arrays.asList(System.getProperty("megamek.gpu.realismBoards", "forest,canyon,lake,lava,fungus").split(","));
        var selectedViews = Arrays.asList(System.getProperty("megamek.gpu.realismViews", "top,iso").split(","));
        var selectedConditions = Arrays.asList(System.getProperty("megamek.gpu.realismConditions", "morning,midday,overcast,night").split(","));
        boolean overlays = Boolean.getBoolean("megamek.gpu.realismOverlays");
        boolean fitted = Boolean.getBoolean("megamek.gpu.realismFitted");
        boolean scorch = Boolean.getBoolean("megamek.gpu.realismScorch");
        assertTrue(!scorch || System.getProperty("megamek.gpu.shaderAuditBaseline") == null,
              "Scorched cost compares current clean/scorched boards, not old/new scorch recipes");
        boolean freezeAnimation = CAPTURE_ONLY || overlays || Boolean.getBoolean("megamek.gpu.realismFreezeAnimation");
        var views = new ArrayList<View>();
        for (Landscape landscape : LANDSCAPES) {
            if (!selected.contains(landscape.name)) { continue; }
            var board = BoardFile.loadSheet(new File("data/boards", landscape.path), false, true);
            if (scorch) {
                var severities = List.of("singed", "charred", "ash", "glass");
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        var hex = board.getHex(x, y);
                        var appearance = new HashMap<>(hex.getAppearance());
                        int quadrant = (x < board.getWidth() / 2 ? 0 : 1) + (y < board.getHeight() / 2 ? 0 : 2);
                        appearance.put("scorch", new HexAppearance("scorch/" + severities.get(quadrant), null, null, null));
                        hex.setAppearance(appearance);
                    }
                }
            }
            var captured = new AtomicReference<BoardScene>();
            try (var fixture = GpuBoardFixture.create(board)) {
                SwingUtilities.invokeAndWait(() -> {
                    var preferences = GUIPreferences.getInstance();
                    boolean coordinates = preferences.getCoordsEnabled(), types = preferences.getHexTypesEnabled();
                    java.awt.Color color = preferences.getBoardTextColor();
                    try {
                        if (overlays) {
                            preferences.setCoordsEnabled(true);
                            preferences.setHexTypesEnabled(true);
                            preferences.setBoardTextColor(java.awt.Color.BLACK);
                        }
                        fixture.source.refresh();
                        captured.set(fixture.source.takeFrame().scene());
                    } finally {
                        if (overlays) {
                            preferences.setCoordsEnabled(coordinates);
                            preferences.setHexTypesEnabled(types);
                            preferences.setBoardTextColor(color);
                        }
                    }
                });
            }
            var scene = new Scene(landscape, captured.get());
            for (boolean top : new boolean[] { true, false }) {
                if (!selectedViews.contains(top ? "top" : "iso")) { continue; }
                for (Conditions conditions : CONDITIONS) {
                    if (selectedConditions.contains(conditions.name)) { views.add(new View(scene, top, conditions)); }
                }
                for (AtmospherePreset preset : List.of(AtmospherePreset.DAWN, AtmospherePreset.DUSK, AtmospherePreset.CLEAR,
                      AtmospherePreset.FULL_MOON, AtmospherePreset.MOONLESS, AtmospherePreset.PITCH_BLACK)) {
                    String name = preset.name().toLowerCase(Locale.ROOT);
                    if (selectedConditions.contains(name)) { views.add(new View(scene, top, new Conditions(name, preset.settings(.5)))); }
                }
                if (selectedConditions.contains("night") && (landscape.name.equals("lava")
                      || landscape.name.equals("dome-vent") || landscape.name.equals("fungus"))) {
                    views.add(new View(scene, top, new Conditions("night", 0, 0, 0)));
                }
            }
        }
        // Optional local pressure override retains each preset's source time, EV and moon policy.
        String pressureName = System.getProperty("megamek.gpu.realismPressure");
        if (pressureName != null) {
            Atmosphere pressure = Atmosphere.valueOf(pressureName.trim().toUpperCase(Locale.ROOT));
            views.replaceAll(view -> {
                var s = view.conditions.settings();
                var settings = new BoardAtmosphere.Settings(s.hour(), s.clouds(), s.fog(), s.groundLayerHeight(),
                      s.haze(), s.exposure(), s.effects(), pressure, s.temperature(), s.moonlight(), s.taint(), s.gravity());
                return new View(view.scene, view.top, new Conditions(view.conditions.name, settings));
            });
        }
        assertTrue(!views.isEmpty(), "Select at least one known landscape");
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "landscapes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 900);
        config.useVsync(false);
        config.setForegroundFPS(0);
        int warmup = CAPTURE_ONLY ? 3 : Integer.getInteger("megamek.gpu.realismWarmup", 30);
        int samples = CAPTURE_ONLY ? 0 : Integer.getInteger("megamek.gpu.realismSamples", 60);
        assertTrue(warmup >= 0 && (CAPTURE_ONLY || samples > 0), "Invalid measurement frame counts");
        String reportName = CAPTURE_ONLY ? "visual-review.txt" : "timings.txt";
        new Lwjgl3Application(new ApplicationAdapter() {
            private final GpuShaderManager manager = new GpuShaderManager();
            private List<Variant> variants;
            private boolean stableOrder;
            private GpuTerrain terrain;
            private GpuReviewFrame compositor;
            private GpuHexGrid grid;
            private GpuHexText labels;
            private GpuBoardSkin skin;
            private SpriteBatch annotations;
            private GpuStageTimings stages;
            private GpuStageTimings total;
            private BoardCamera camera;
            private Scene installed;
            private Ray pick;
            private Coords picked;
            private int viewIndex;
            private int variantIndex;
            private int frame;
            private byte[] firstPixels;
            private final Map<String, byte[]> repeats = new HashMap<>();
            private long previousFrame;
            private final double[] cpu = new double[samples];
            private final double[] intervals = new double[samples];
            private final StringBuilder report = new StringBuilder();

            @Override
            public void create() {
                manager.run(this::createManaged);
            }

            private void createManaged() {
                try {
                    String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                    String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "").trim();
                    assertTrue(renderer.toLowerCase(Locale.ROOT).contains(expectedRenderer.toLowerCase(Locale.ROOT)),
                          () -> "Requested benchmark renderer containing '" + expectedRenderer + "', got " + renderer);
                    variants = variants(report);
                    stableOrder = freezeAnimation || variants.size() > 1 || Boolean.getBoolean("megamek.gpu.realismStableOrder");
                    String initialSources = System.getProperty("megamek.gpu.realismInitialSources");
                    if (initialSources != null) {
                        assertEquals(1, variants.size(), "Initial sources require a single-version process");
                        var sources = new HashMap<String, String>();
                        for (String name : GpuShaderSource.files()) {
                            var file = new File(initialSources, name);
                            if (file.isFile()) { sources.put(name, Files.readString(file.toPath())); }
                        }
                        assertTrue(!sources.isEmpty(), "Initial sources must contain known shader files");
                        // Apply before compositor/terrain construction, including initial preparation.
                        replaceSources(manager, sources, "initial-sources", report);
                        variants = List.of(new Variant("", "current", Map.copyOf(sources)));
                        report.append("Initial source drafts: ").append(initialSources).append('\n');
                    }
                    if (!CAPTURE_ONLY) {
                        stages = new GpuStageTimings();
                        total = new GpuStageTimings();
                    }
                    compositor = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                    if (overlays) {
                        grid = new GpuHexGrid(); labels = new GpuHexText();
                        skin = new GpuBoardSkin(); annotations = new SpriteBatch();
                        report.append("Overlay capture: actual captured neutral coordinates/types, outlines enabled; ")
                              .append("EDT preferences restored before native launch. Overlays are outside timing ranges.\n")
                              .append("Animation advances .5s once per board, then freezes across all conditions and cameras.\n");
                    }
                    report.append(renderer).append("; ")
                          .append(Gdx.graphics.getBackBufferWidth()).append('x')
                          .append(Gdx.graphics.getBackBufferHeight());
                    if (CAPTURE_ONLY) {
                        report.append("; visual-only; ").append(warmup).append(" untimed settling frames; ")
                              .append("no GPU, CPU, interval, preparation or source-replacement timings\n");
                    } else {
                        report.append("; ").append(warmup).append(" warmup / ").append(samples)
                          .append(" measured frames; terrain scene, no HUD or unit models\n")
                          .append("GL: ").append(Gdx.gl.glGetString(GL20.GL_VERSION))
                          .append("; Java: ").append(System.getProperty("java.home"))
                          .append("; GPU request: ").append(GpuGraphicsCard.applied().name()).append('\n')
                          .append("Frozen animation; glFinish after GPU timestamps and CPU submission timing.\n")
                          .append("Preparation wall times include asynchronous CPU/driver work and polling; ")
                          .append("they are not GPU rendering times or isolated shader-compile measurements.\n")
                          .append("Frame intervals include synchronization and presentation; not pipelined game frame rates.\n");
                    }
                    if (CAPTURE_ONLY) {
                        report.append("GL: ").append(Gdx.gl.glGetString(GL20.GL_VERSION))
                              .append("; GPU request: ").append(GpuGraphicsCard.applied().name()).append('\n');
                    }
                    if (Boolean.getBoolean("megamek.gpu.realismCliffs")
                          && views.stream().anyMatch(view -> view.scene.landscape.name.equals("calibration"))) {
                        report.append("Calibration camera fits authored cliff rows 19–34 before close zoom; ")
                              .append("the timed view is a central close-up, not all bays. The complete board remains installed. ")
                              .append("This is a separate workload from Dome.\n");
                    }
                    if (freezeAnimation && !overlays) {
                        report.append("Lighting review: animation advances .5s once per board, then freezes across cases.\n");
                    }
                    if (pressureName != null) { report.append("Pressure override: ").append(pressureName).append('\n'); }
                    report.append("Camera framing: ").append(fitted ? "fitted board" : "middle zoom").append('\n');
                    if (scorch) {
                        report.append("Scorch workload: singed/charred/ash/glass in NW/NE/SW/SE quadrants, ")
                              .append("plus one cooled nuclear footprint; current shaders only. ")
                              .append("Compare with the identical clean-board current run.\n");
                    }
                    if (stableOrder) { report.append("Stable draw sorting enabled for this process.\n"); }
                    if (variants.size() > 1) {
                        report.append("Source comparisons use stable draw sorting; frozen same-source repeats must be byte-exact.\n")
                              .append("Ablations retain geometry, depth and shadow prepass; marginal costs are not additive totals.\n")
                              .append("No-reflections keeps diffuse energy budgets; no-shadows removes receiver sampling only.\n");
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void render() {
                manager.run(this::renderManaged);
            }

            private void renderManaged() {
                if (failure.get() != null) { return; }
                try {
                    View view = views.get(viewIndex);
                    Variant variant = variants.get(variantIndex);
                    String label = view.name() + (variant.name.isEmpty() ? "" : "-" + variant.name);
                    if (frame == 0) {
                        if (variantIndex == 0) { prepare(view); }
                        replaceSources(manager, variant.sources, label, report);
                        if (CAPTURE_ONLY) { settleView(); }
                    }
                    if (CAPTURE_ONLY) {
                        compositor.render(terrain, camera, installed.board);
                    } else {
                        long now = System.nanoTime();
                        if (frame >= warmup) { intervals[frame - warmup] = (now - previousFrame) / 1e6; }
                        previousFrame = now;
                        total.beginFrame(frame >= warmup);
                        stages.beginFrame(frame >= warmup);
                        total.stage("complete terrain scene");
                        compositor.render(terrain, camera, installed.board, stages);
                        total.stage(null);
                        if (frame >= warmup) { cpu[frame - warmup] = (System.nanoTime() - now) / 1e6; }
                        // Complete each frame outside both timestamp ranges and CPU submission timing. Otherwise this
                        // unlimited CPU loop fills the query ring long before the GPU can retire its first four frames.
                        Gdx.gl.glFinish();
                        total.poll();
                        stages.poll();
                    }
                    if (++frame == warmup + samples) {
                        if (overlays) { captureOverlays(view, label); }
                        if (freezeAnimation && !overlays) {
                            byte[] paused = ScreenUtils.getFrameBufferPixels(0, 0,
                                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
                            compositor.render(terrain, camera, installed.board);
                            assertArrayEquals(paused, ScreenUtils.getFrameBufferPixels(0, 0,
                                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false),
                                  "Frozen lighting review must repeat exactly: " + label);
                        }
                        GpuReviewFrame.save(new File(output, label + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), view.name());
                        assertTrue(terrain.ready(installed.board),
                              () -> view.name() + ": captured terrain must be ready: " + terrain.buildDetails());
                        var hit = terrain.hit(installed.board, pick);
                        assertNotNull(hit, view.name() + ": retain board picking");
                        assertEquals(picked, hit.coords(), view.name() + ": shared picking in every light and view");
                        if (!CAPTURE_ONLY) {
                            appendSamples(total, report, label, samples);
                            appendSamples(stages, report, label + " stages", samples);
                        }
                        if (variants.size() > 1) {
                            byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0,
                                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
                            if (firstPixels == null) { firstPixels = pixels; }
                            imageDifference(report, label, firstPixels, pixels);
                            if (variant.name.equals("diagnostic-fixed-exposure")) {
                                assertTrue(!Arrays.equals(firstPixels, pixels), "Fixed exposure must change the full-moon image");
                            }
                            if (variant.repeat != null) {
                                byte[] previous = repeats.putIfAbsent(variant.repeat, pixels);
                                if (previous != null) {
                                    report.append(label).append(" frozen-repeat=")
                                          .append(Arrays.equals(previous, pixels) ? "byte-exact" : "FAILED").append('\n');
                                    assertArrayEquals(previous, pixels, "Frozen source control must repeat exactly: " + label);
                                }
                            }
                        }
                        if (overlays && variants.size() == 1
                              && List.of("box-canyon", "dome-vent").contains(view.scene.landscape.name)) {
                            captureInkPan(view, label);
                        }
                        if (!CAPTURE_ONLY) {
                            Arrays.sort(cpu);
                            Arrays.sort(intervals);
                            report.append(String.format(Locale.ROOT,
                                  "CPU submission median/p95 %.4f/%.4f ms; synchronized frame interval median/p95 %.4f/%.4f ms; plant buffers %d bytes%n",
                                  percentile(cpu, .5), percentile(cpu, .95), percentile(intervals, .5),
                                  percentile(intervals, .95), terrain.treeGeometryBytes()));
                        }
                        Files.writeString(new File(output, reportName).toPath(), report);
                        frame = 0;
                        if (++variantIndex == variants.size()) {
                            variantIndex = 0;
                            firstPixels = null;
                            repeats.clear();
                            if (++viewIndex == views.size()) { Gdx.app.exit(); }
                        }
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            private void drawOverlays() {
                compositor.atmosphere().prepareContrast(camera.camera, installed.board, 0);
                grid.render(camera, installed.board, terrain.columns(), false, GpuBattleStatus.Arc.NONE,
                      false, compositor.atmosphere());
                labels.render(annotations, camera, compositor.depthTexture(), null, 0, compositor.atmosphere());
            }

            private void captureOverlays(View view, String label) {
                int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                byte[] before = ScreenUtils.getFrameBufferPixels(0, 0, width, height, false);
                drawOverlays();
                byte[] after = ScreenUtils.getFrameBufferPixels(0, 0, width, height, false);
                int changed = 0;
                for (int i = 0; i < after.length; i += 4) {
                    if (before[i] != after[i] || before[i + 1] != after[i + 1] || before[i + 2] != after[i + 2]) { changed++; }
                }
                assertTrue(changed > 100, "The actual map must contain visible overlay pixels: " + label);
                // One untimed redraw establishes that the saved appearance is a paused complete-frame result.
                compositor.render(terrain, camera, installed.board);
                drawOverlays();
                assertArrayEquals(after, ScreenUtils.getFrameBufferPixels(0, 0, width, height, false),
                      "Frozen complete scene and overlays must repeat exactly: " + label);
                report.append(label).append(" overlayPixels=").append(changed)
                      .append(" settings=").append(view.conditions.settings())
                      .append(" exposure=").append(compositor.atmosphere().exposure())
                      .append(" directRGB=").append(compositor.lighting().direct().r).append(',')
                      .append(compositor.lighting().direct().g).append(',').append(compositor.lighting().direct().b)
                      .append(" ambientRGB=").append(compositor.lighting().ambient().r).append(',')
                      .append(compositor.lighting().ambient().g).append(',').append(compositor.lighting().ambient().b)
                      .append(" directSource=").append(compositor.lighting().hasDirectLight())
                      .append(" sunlight=").append(compositor.lighting().sunlight())
                      .append(" light=").append(compositor.lighting().direction())
                      .append(" camera=").append(camera.camera.position).append(" zoom=").append(camera.camera.zoom)
                      .append(" pausedRepeat=byte-exact\n");
            }

            /** Paired slow pans over the actual frozen landscape, outside all measurement ranges. */
            private void captureInkPan(View view, String label) throws Exception {
                Vector3 origin = camera.focus.cpy();
                assertTrue(!camera.camera.perspective, "This footprint classifier covers the top/isometric orthographic sweep");
                int pulseOwners = 0;
                var pan = new StringBuilder("Actual paused scene; 16 quarter-pixel steps out and back.\n")
                      .append("fresh resets per frame; history retains the production state. Binary states are in row-major board order.\n")
                      .append("b/w=learned black/white, B/W=unlearned black/white; visibility is projected full-hex coverage, independent of history.\n");
                try {
                    for (float scale : new float[] { 1, .5f }) {
                        for (boolean history : new boolean[] { false, true }) {
                            compositor.atmosphere().resetContrast();
                            String mode = history ? "history" : "fresh";
                            String previous = null;
                            int[] transitions = new int[installed.board.width() * installed.board.height()];
                            int[] visibleTransitions = new int[transitions.length], unlearnedTransitions = new int[transitions.length];
                            boolean[] previousVisible = null;
                            for (int step = 0; step < 16; step++) {
                                camera.center(origin);
                                camera.pan((step < 8 ? step : 15 - step) * .25f, 0);
                                if (step == 7) {
                                    assertTrue(!camera.focus.epsilonEquals(origin, .0001f), "The pan must move the real camera");
                                }
                                if (!history) { compositor.atmosphere().resetContrast(); }
                                compositor.render(terrain, camera, installed.board, scale);
                                drawOverlays();
                                String ink = inkStates();
                                boolean[] visible = visibleInkOwners();
                                var visibility = new StringBuilder(visible.length);
                                for (boolean owner : visible) { visibility.append(owner ? 'v' : '.'); }
                                boolean changedInk = false;
                                if (previous != null) {
                                    for (int i = 0; i < ink.length(); i++) {
                                        if (Character.toLowerCase(previous.charAt(i)) != Character.toLowerCase(ink.charAt(i))) {
                                            transitions[i]++;
                                            if (visible[i] || previousVisible[i]) { visibleTransitions[i]++; }
                                            if (Character.isUpperCase(previous.charAt(i)) || Character.isUpperCase(ink.charAt(i))) {
                                                unlearnedTransitions[i]++;
                                            }
                                            changedInk = true;
                                        }
                                    }
                                }
                                previous = ink;
                                previousVisible = visible;
                                pan.append("scale=").append(scale).append(" mode=").append(mode)
                                      .append(" step=").append(step).append(" focus=").append(camera.focus)
                                      .append(" states=").append(ink).append(" visible=").append(visibility).append('\n');
                                // Preserve the transition evidence even if the repeat-pixel assertion below fails.
                                Files.writeString(new File(output, label + "-ink-pan.txt").toPath(), pan);
                                if (step == 0 || step == 7 || history && changedInk) {
                                    String name = label + "-pan-" + scale + "-" + mode + "-" + step;
                                    GpuReviewFrame.save(new File(output, name + ".png"));
                                    byte[] paused = ScreenUtils.getFrameBufferPixels(0, 0,
                                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
                                    compositor.render(terrain, camera, installed.board, scale);
                                    drawOverlays();
                                    assertEquals(ink, inkStates(), "An unchanged real frame must retain every owner: " + name);
                                    assertArrayEquals(paused, ScreenUtils.getFrameBufferPixels(0, 0,
                                                Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false),
                                          "Paused real-map pan frames must repeat exactly: " + name);
                                }
                            }
                            int changes = 0, repeated = 0;
                            for (int i = 0; i < transitions.length; i++) {
                                if (transitions[i] == 0) { continue; }
                                changes += transitions[i];
                                if (transitions[i] > 1) { repeated++; }
                                // In this frozen 1.75-pixel out/back sweep, a broad brightness boundary may
                                // switch once each way. Repeated pulses require investigation, not acceptance.
                                if (history && visibleTransitions[i] > 2) { pulseOwners++; }
                                pan.append("owner=").append(i % installed.board.width()).append(',')
                                      .append(i / installed.board.width()).append(" transitions=").append(transitions[i])
                                      .append(" visibleTransitions=").append(visibleTransitions[i])
                                      .append(" unlearnedTransitions=").append(unlearnedTransitions[i]).append('\n');
                            }
                            report.append(label).append(" inkPan scale=").append(scale).append(" mode=").append(mode)
                                  .append(" transitions=").append(changes).append(" repeatedOwners=").append(repeated).append('\n');
                        }
                    }
                    Files.writeString(new File(output, label + "-ink-pan.txt").toPath(), pan);
                    assertEquals(0, pulseOwners, "Repeated pan-induced ink pulses; see " + label + "-ink-pan.txt");
                    if (view.name().equals("dome-vent-iso-full_moon")) { captureInkPresetChanges(label); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Real-map ink pan: " + view.name());
                } finally {
                    compositor.configure(view.conditions.settings());
                    camera.center(origin);
                    compositor.atmosphere().resetContrast();
                    compositor.render(terrain, camera, installed.board);
                    drawOverlays();
                }
            }

            /** A real preset change must produce the same visible ink as a fresh decision, including clipped owners. */
            private void captureInkPresetChanges(String label) throws Exception {
                compositor.configure(AtmospherePreset.FULL_MOON.settings(.5));
                compositor.render(terrain, camera, installed.board);
                drawOverlays();
                String previous = inkStates();
                boolean[] visible = visibleInkOwners();
                boolean[] clipped = new boolean[visible.length];
                int edgeOwners = 0, edgeChanges = 0;
                for (var tile : installed.board.tiles()) {
                    int owner = tile.coords().getY() * installed.board.width() + tile.coords().getX();
                    Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation()).prj(camera.camera.combined);
                    clipped[owner] = visible[owner] && (Math.abs(center.x) > 1 || Math.abs(center.y) > 1);
                    if (clipped[owner]) { edgeOwners++; }
                }
                assertTrue(edgeOwners > 0, "The actual-map preset probe must contain visible hexes with clipped centers");
                var evidence = new StringBuilder("Actual map; fixed camera/animation; preset history versus fresh decisions.\n");
                for (var preset : List.of(AtmospherePreset.CLEAR, AtmospherePreset.PITCH_BLACK, AtmospherePreset.FULL_MOON)) {
                    Set<Object> resources = inkResources();
                    compositor.configure(preset.settings(.5));
                    compositor.render(terrain, camera, installed.board);
                    drawOverlays();
                    String changed = inkStates();
                    GpuReviewFrame.save(new File(output, label + "-ink-preset-" + preset.name().toLowerCase(Locale.ROOT) + ".png"));
                    evidence.append(preset).append(" changed=").append(changed).append('\n');
                    Files.writeString(new File(output, label + "-ink-presets.txt").toPath(), evidence);
                    assertEquals(resources, inkResources(), "Changing presets preserves both targets and the owner mesh");
                    compositor.atmosphere().resetContrast();
                    compositor.render(terrain, camera, installed.board);
                    drawOverlays();
                    String fresh = inkStates();
                    evidence.append(preset).append(" fresh=  ").append(fresh).append('\n');
                    Files.writeString(new File(output, label + "-ink-presets.txt").toPath(), evidence);
                    for (int owner = 0; owner < visible.length; owner++) {
                        if (!visible[owner]) { continue; }
                        assertEquals(fresh.charAt(owner), changed.charAt(owner),
                              "Preset change must relearn visible owner " + owner + ": " + preset);
                        if (clipped[owner] && Character.toLowerCase(previous.charAt(owner))
                              != Character.toLowerCase(changed.charAt(owner))) { edgeChanges++; }
                    }
                    previous = fresh;
                }
                evidence.append("clippedOwners=").append(edgeOwners).append(" clippedInkChanges=").append(edgeChanges).append('\n');
                Files.writeString(new File(output, label + "-ink-presets.txt").toPath(), evidence);
                assertTrue(edgeChanges > 0, "The actual preset controls must cross the threshold on a clipped owner");
                report.append(label).append(" inkPreset clippedOwners=").append(edgeOwners)
                      .append(" clippedChanges=").append(edgeChanges).append(" historyEqualsFresh=true\n");
            }

            private Set<Object> inkResources() throws Exception {
                var resources = new java.util.HashSet<Object>();
                for (String name : List.of("contrastRead", "contrastWrite", "contrastPoints")) {
                    var field = GpuAtmosphere.class.getDeclaredField(name);
                    field.setAccessible(true);
                    resources.add(field.get(compositor.atmosphere()));
                }
                assertEquals(3, resources.size());
                return resources;
            }

            /** Test-only readback of the tiny atlas; production makes no CPU decision or readback. */
            private String inkStates() throws Exception {
                var field = GpuAtmosphere.class.getDeclaredField("contrastRead");
                field.setAccessible(true);
                FrameBuffer target = (FrameBuffer) field.get(compositor.atmosphere());
                assertNotNull(target, "The real-map overlay pass must prepare its ink atlas");
                byte[] pixels;
                target.begin();
                try { pixels = ScreenUtils.getFrameBufferPixels(0, 0, target.getWidth(), target.getHeight(), false); }
                finally {
                    target.end();
                    HdpiUtils.glViewport(0, 0, (int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight);
                }
                var states = new StringBuilder(target.getWidth() * target.getHeight());
                for (int i = 0; i < pixels.length; i += 4) {
                    int ink = pixels[i] & 255, valid = pixels[i + 1] & 255;
                    assertTrue((ink == 0 || ink == 255) && (valid == 0 || valid == 255), "Every ink state is binary");
                    char choice = ink == 0 ? 'b' : 'w';
                    states.append(valid == 0 ? Character.toUpperCase(choice) : choice);
                }
                return states.toString();
            }

            /** Full projected hexes, including edge slivers with no inset tap; independent of history validity. */
            private boolean[] visibleInkOwners() {
                boolean[] visible = new boolean[installed.board.width() * installed.board.height()];
                var viewport = new Polygon(new float[] { -1, -1, 1, -1, 1, 1, -1, 1 });
                float[] corners = new float[12];
                var footprint = new Polygon(corners);
                for (var tile : installed.board.tiles()) {
                    float near = Float.POSITIVE_INFINITY, far = Float.NEGATIVE_INFINITY;
                    for (int corner = 0; corner < 6; corner++) {
                        Vector3 point = BoardGeometry.corner(tile.coords(), tile.elevation(), corner).prj(camera.camera.combined);
                        corners[corner * 2] = point.x; corners[corner * 2 + 1] = point.y;
                        near = Math.min(near, point.z); far = Math.max(far, point.z);
                    }
                    footprint.setVertices(corners);
                    int owner = tile.coords().getY() * installed.board.width() + tile.coords().getX();
                    visible[owner] = far >= -1 && near <= 1 && Intersector.overlapConvexPolygons(footprint, viewport);
                }
                return visible;
            }

            private void settleView() throws Exception {
                if (!CAPTURE_ONLY) {
                    GpuReviewFrame.settle(terrain, null, installed.board, camera);
                    return;
                }
                // A light-condition change can finish geometry while family programs still link. Pump the
                // production loading path until the same readiness gate as drawing/picking is satisfied.
                compositor.prepare(terrain, camera, installed.board);
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (true) {
                    boolean changed = terrain.refine(camera.camera);
                    if (!changed && !terrain.busy() && terrain.ready(installed.board)) { return; }
                    assertTrue(System.nanoTime() < deadline,
                          () -> "Visual capture preparation must settle: " + terrain.buildDetails());
                    Thread.sleep(5);
                }
            }

            private void prepare(View view) throws Exception {
                compositor.configure(view.conditions.settings());
                camera = new BoardCamera();
                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.setIsometric(!view.top);
                if (view.scene.landscape.name.equals("calibration") && Boolean.getBoolean("megamek.gpu.realismCliffs")) {
                    camera.fit(view.scene.board,
                          view.scene.board.tiles().stream().filter(tile -> tile.coords().getY() >= 18).toList());
                } else { camera.fit(view.scene.board); }
                boolean newBoard = installed != view.scene;
                if (newBoard) {
                    if (terrain != null) { terrain.dispose(); }
                    terrain = new GpuTerrain();
                    if (stableOrder) {
                        var batch = GpuTerrain.class.getDeclaredField("batch");
                        batch.setAccessible(true);
                        var sorter = ModelBatch.class.getDeclaredField("sorter");
                        sorter.setAccessible(true);
                        sorter.set(batch.get(terrain), new DefaultRenderableSorter());
                    }
                    installed = view.scene;
                    compositor.prepare(terrain, camera, installed.board);
                    long initialBuild = preparationStart(view.name() + "-initial-update-blocking");
                    terrain.update(installed.board);
                    if (scorch) {
                        var centre = BoardGeometry.center(new Coords(installed.board.width() / 2,
                              installed.board.height() / 2), 0);
                        terrain.scorch(installed.board, new GpuGroundDamage.Scorch(centre,
                              BoardRelief.metres(80), BoardRelief.metres(15)));
                        // Cold nuclear residue retains the directory workload without a wall-clock-varying image.
                        var clock = GpuGroundDamage.class.getDeclaredField("scorchedAt");
                        clock.setAccessible(true);
                        clock.setLong(terrain.groundDamage(), System.nanoTime() - 3_600_000_000_000L);
                        terrain.groundDamage().upload();
                        assertTrue(terrain.groundDamage().scorchShown());
                    }
                    preparationEnd(view.name() + "-initial-update-blocking", initialBuild, report);
                    var coords = new Coords(installed.board.width() / 2, installed.board.height() / 2);
                    var center = BoardGeometry.center(coords, 0);
                    pick = new Ray(center.cpy().add(0, 0, 10000), new Vector3(0, 0, -1));
                    var hit = terrain.hit(installed.board, pick);
                    assertNotNull(hit, installed.landscape.name);
                    picked = hit.coords();
                }
                if (variants.size() > 1) { replaceSources(manager, Map.of(), view.name() + "-prepare", report); }
                compositor.prepare(terrain, camera, installed.board);
                long overviewStart = preparationStart(view.name() + "-overview-settle");
                settleView();
                preparationEnd(view.name() + "-overview-settle", overviewStart, report);
                terrain.animate(freezeAnimation && !newBoard ? 0 : .5f, List.of());
                long overviewDraw = preparationStart(view.name() + "-overview-first-draw-submit");
                compositor.render(terrain, camera, installed.board);
                preparationEnd(view.name() + "-overview-first-draw-submit", overviewDraw, report);
                if (CAPTURE_ONLY || view.conditions.name.equals("midday")) {
                    GpuReviewFrame.save(new File(output, view.name() + "-overview.png"));
                }
                if (!fitted) { camera.camera.zoom = Math.min(.55f, camera.camera.zoom); }
                camera.update();
                long closeStart = preparationStart(view.name() + "-close-settle");
                settleView();
                preparationEnd(view.name() + "-close-settle", closeStart, report);
                if (Boolean.getBoolean("megamek.gpu.assertBlendedRoles")) {
                    int[] roles = GpuTerrainPreparationSmokeTest.checkBlendedRoles(terrain, view.name());
                    assertTrue(roles[0] > 0, "The selected landscape must exercise blended geometry");
                    if (view.scene.landscape.name.equals("calibration")) {
                        assertTrue(roles[1] > 0 && roles[2] > 0 && roles[4] > 0,
                              "Calibration must retain blended tops, cliffs and constructed concrete cliffs");
                    }
                }
                compositor.prepare(terrain, camera, installed.board);
                // Compile each newly reached program before starting warmup or timestamp collection.
                long closeDraw = preparationStart(view.name() + "-close-first-draw-submit");
                compositor.render(terrain, camera, installed.board);
                preparationEnd(view.name() + "-close-first-draw-submit", closeDraw, report);
                if (freezeAnimation && !overlays) {
                    report.append(view.name()).append(" settings=").append(view.conditions.settings())
                          .append(" exposure=").append(compositor.atmosphere().exposure())
                          .append(" directRGB=").append(compositor.lighting().direct().r).append(',')
                          .append(compositor.lighting().direct().g).append(',').append(compositor.lighting().direct().b)
                          .append(" ambientRGB=").append(compositor.lighting().ambient().r).append(',')
                          .append(compositor.lighting().ambient().g).append(',').append(compositor.lighting().ambient().b)
                          .append(" camera=").append(camera.camera.position).append(" zoom=").append(camera.camera.zoom)
                          .append('\n');
                }
                if (overlays) {
                    assertTrue(installed.board.tiles().stream().anyMatch(tile -> !tile.text().isEmpty()),
                          "The captured map must contain real board labels");
                    labels.update(installed.board, skin.skin.getFont("default-font"),
                          skin.skin.getFont(GpuBoardSkin.OUTLINE_FONT), terrain::roofBounds, false, true, true);
                }
            }

            @Override
            public void dispose() {
                manager.run(() -> {
                    if (annotations != null) { annotations.dispose(); }
                    if (labels != null) { labels.dispose(); }
                    if (grid != null) { grid.dispose(); }
                    if (skin != null) { skin.dispose(); }
                    if (terrain != null) { terrain.dispose(); }
                    if (compositor != null) { compositor.dispose(); }
                    if (stages != null) { stages.close(); }
                    if (total != null) { total.close(); }
                    manager.close();
                });
                Gdx.files.absolute(new File(output, reportName).getAbsolutePath()).writeString(report.toString(), false);
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain realism review", failure.get()); }
    }

    private static long preparationStart(String label) {
        System.out.println(label + " preparation starting");
        System.out.flush();
        return CAPTURE_ONLY ? 0 : System.nanoTime();
    }

    private static void preparationEnd(String label, long started, StringBuilder report) {
        String completed = CAPTURE_ONLY ? label + " preparation complete (visual-only)\n"
              : String.format(Locale.ROOT, "%s preparation wallMs=%.3f%n",
                    label, (System.nanoTime() - started) / 1e6);
        report.append(completed);
        System.out.print(completed);
        System.out.flush();
    }

    /** Optional source replay and deliberately isolated diagnostics; all mutations stay in the shader session. */
    private static List<Variant> variants(StringBuilder report) throws Exception {
        var result = new ArrayList<Variant>();
        String baseline = System.getProperty("megamek.gpu.shaderAuditBaseline");
        if (baseline == null) {
            result.add(new Variant("", "current", Map.of()));
        } else {
            var sources = new HashMap<String, String>();
            for (String name : GpuShaderSource.files()) {
                var file = new File(baseline, "megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/" + name);
                if (file.isFile()) { sources.put(name, Files.readString(file.toPath())); }
            }
            assertTrue(!sources.isEmpty(), "The saved shader snapshot must contain actual sources: " + baseline);
            report.append("Baseline shader sources: ").append(sources.keySet().stream().sorted().toList()).append('\n');
            result.add(new Variant("0-reference", "reference", sources));
            result.add(new Variant("1-current", "current", Map.of()));
            result.add(new Variant("2-current", "current", Map.of()));
            result.add(new Variant("3-reference", "reference", sources));
        }
        var diagnostics = Arrays.asList(System.getProperty("megamek.gpu.realismDiagnostics", "").split(","));
        for (String choice : diagnostics) {
            assertTrue(List.of("", "true", "color", "materials", "shadows", "localLight", "all",
                        "materialDetail", "ordinaryMaterial", "ordinaryColors", "ordinaryNormals", "solidRelief", "solidMaterial",
                        "lavaPhase", "contactNoise", "fixedExposure").contains(choice),
                  "Unknown landscape diagnostic: " + choice);
        }
        if (diagnostics.contains("fixedExposure")) {
            assertTrue(CAPTURE_ONLY && !Boolean.getBoolean("megamek.gpu.realismOverlays"),
                  "Fixed exposure is a frozen visual-only diagnostic without overlays");
            assertEquals("full_moon", System.getProperty("megamek.gpu.realismConditions"),
                  "Fixed exposure retains only the full-moon lighting case");
            assertEquals(List.of("fixedExposure"), diagnostics, "Isolate exposure from other source diagnostics");
            assertTrue(baseline == null && System.getProperty("megamek.gpu.realismInitialSources") == null,
                  "Fixed exposure compares the current source against itself");
        }
        boolean all = diagnostics.contains("all");
        if (!diagnostics.equals(List.of(""))) {
            result.add(new Variant("diagnostic-control-before", "current", Map.of()));
        }
        if (all || diagnostics.contains("color") || diagnostics.contains("true")) {
            result.add(new Variant("diagnostic-no-reflections", null, Map.of(
                  "light-model.glsl", functionBody(GpuShaderSource.readDisk("light-model.glsl"),
                        "vec3 surfaceReflectance(vec3 normal, vec3 light, vec3 view, float roughness, vec3 f0)", "return vec3(0.0);"),
                  "environment-lighting.glsl", functionBody(GpuShaderSource.readDisk("environment-lighting.glsl"),
                        "vec3 environmentSpecular(vec3 normal, vec3 view, float roughness, vec3 f0, vec3 ground, vec3 fallbackSky)",
                        "return vec3(0.0);"))));
        }
        if (all || diagnostics.contains("materials")) {
            String materials = GpuShaderSource.readDisk("terrain-materials.glsl");
            String prepared = "TerrainMaterial naturalMaterialFor(MaterialProjection projection, vec2 noise, vec3 world, vec3 face,";
            String evaluator = materials.contains(prepared) ? prepared
                  : "TerrainMaterial naturalMaterialFor(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,";
            result.add(new Variant("diagnostic-no-material-maps", null, Map.of(
                  "terrain-materials.glsl", functionBody(materials, evaluator,
                        "return TerrainMaterial(vec3(.2), face, 1.0, .5, Finish(.85, 0.0, 0.0, vec3(0.0)), 0.0, 1.0);"),
                  "terrain-magma.glsl", functionBody(GpuShaderSource.readDisk("terrain-magma.glsl"),
                        "vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy)", """
                              if (map == MAGMA_COLOR) return vec4(.2, .2, .2, 1.0);
                              if (map == MAGMA_NORMAL) return vec4(128.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
                              if (map == MAGMA_SURFACE) return vec4(.5, .85, 1.0, 0.0);
                              return vec4(0.0, .5, .5, 0.0);
                              """))));
            report.append("No-material-maps bypasses naturalMaterialFor evaluation and replaces all magmaTexel ")
                  .append("aligned color/normal/surface/heat reads with role-correct neutral constants, including ")
                  .append("deferred flowing phases. Coverage, other noise/biome evaluation, volcanic projection/flow ")
                  .append("and downstream lighting remain; constant-dependent work may be folded by the driver.\n");
        }
        if (all || diagnostics.contains("shadows")) {
            String surface = functionBody(GpuShaderSource.readDisk("surface-lighting.glsl"),
                  "float surfaceVisibility()", "return 1.0;");
            surface = functionBody(surface, "float sculptShadow(vec3 normal, vec3 light)", "return 1.0;");
            // Models read the same geometry shadow (surfaceVisibility) through shadeSurface.
            result.add(new Variant("diagnostic-no-shadows", null, Map.of("surface-lighting.glsl", surface)));
        }
        if (all || diagnostics.contains("localLight")) {
            // Every reader of the light list walks it through lavaLighting: terrain, units, water and volumes.
            result.add(new Variant("diagnostic-no-local-light", null, Map.of("lava-lighting.glsl",
                  functionBody(GpuShaderSource.readDisk("lava-lighting.glsl"), "vec3 lavaLighting(",
                        "specular = vec3(0.0); specular2 = vec3(0.0); return vec3(0.0);"))));
        }
        if (diagnostics.contains("fixedExposure")) {
            float diagnosticExposure = Float.parseFloat(System.getProperty("megamek.gpu.realismFixedExposure", "1.02573"));
            assertTrue(Float.isFinite(diagnosticExposure) && diagnosticExposure > 0,
                  "The diagnostic exposure must be finite and positive");
            String grade = GpuShaderSource.replaceOnce(GpuShaderSource.readDisk("atmosphere-grade.glsl"),
                  "linear *= u_exposure *", "linear *= diagnosticExposure() *", "fixed scene exposure");
            // Retain the actual uniform/upload contract. Only this shader-session control substitutes the
            // requested exposure; source lighting, emission, animation and the shoulder are unchanged.
            grade = GpuShaderSource.replaceOnce(grade, "uniform float u_exposure;", """
                  uniform float u_exposure;
                  float diagnosticExposure() { return u_exposure > 0.0 ? %s : u_exposure; }
                  """.formatted(Float.toString(diagnosticExposure)), "fixed exposure uniform");
            String composite = GpuShaderSource.readDisk("atmosphere-composite.frag")
                  .replace("u_exposure", "diagnosticExposure()");
            result.add(new Variant("diagnostic-fixed-exposure", null,
                  Map.of("atmosphere-grade.glsl", grade, "atmosphere-composite.frag", composite)));
            report.append("Fixed-exposure control: whole composite exposure=").append(diagnosticExposure)
                  .append(", including heat/glow and ")
                  .append("the authored backdrop's inverse mapping. Logged source adaptation remains unchanged. ")
                  .append("Full-moon lighting, emitted radiance, frozen pose and tone mapping are unchanged; ")
                  .append("production controls before/after must repeat byte-exactly.\n");
        }
        materialDiagnostics(result, diagnostics, report);
        if (!diagnostics.equals(List.of(""))) {
            result.add(new Variant("diagnostic-control-after", "current", Map.of()));
        }
        return result;
    }

    /** Session-only ablations: each has a restored production control; differences are not additive costs. */
    private static void materialDiagnostics(List<Variant> result, List<String> diagnostics, StringBuilder report) {
        boolean detail = diagnostics.contains("materialDetail");
        if (detail || diagnostics.contains("ordinaryMaterial")) {
            materialProbe(result, "ordinary-material", Map.of("terrain-materials.glsl",
                  functionBody(GpuShaderSource.readDisk("terrain-materials.glsl"),
                        "TerrainMaterial naturalMaterialFor(MaterialProjection projection, vec2 noise, vec3 world, vec3 face,",
                        "return TerrainMaterial(vec3(.2), face, 1.0, .5, Finish(.92, 0.0, 0.0, vec3(0.0)), 0.0, 1.0);")));
            report.append("Ordinary-material bypasses the full ordinary evaluator, retaining volcanic evaluation. ")
                  .append("Neutral height/normal/cavity also change height competition and downstream lighting; ")
                  .append("this is a family-work ablation, not isolated texture bandwidth.\n");
        }
        if (detail || diagnostics.contains("ordinaryNormals")) {
            materialProbe(result, "ordinary-normals", Map.of("terrain-materials.glsl",
                  functionBody(GpuShaderSource.readDisk("terrain-materials.glsl"),
                        "vec4 materialNormal(float normalMap, float tile, float weight, MaterialProjection p, vec3 face,",
                        "return vec4(face, 1.0);")));
            report.append("Ordinary-normals removes ordinary normal/AO reads and reconstruction; ")
                  .append("color/height and material coverage remain, normal-dependent lighting changes.\n");
        }
        if (detail || diagnostics.contains("ordinaryColors")) {
            materialProbe(result, "ordinary-colors", Map.of("terrain-materials.glsl",
                  functionBody(GpuShaderSource.readDisk("terrain-materials.glsl"),
                        "vec4 sampleMaterial(float colorMap, float tile, float amount, MaterialProjection p,",
                        "return amount < .0001 ? vec4(0.0) : vec4(.2, .2, .2, .5);")));
            report.append("Ordinary-colors removes color/height sampling and pigment treatment. ")
                  .append("Neutral heights change role competition and which later normal/AO reads survive.\n");
        }
        if (detail || diagnostics.contains("solidRelief")) {
            materialProbe(result, "solid-relief", Map.of("magma-solid.glsl",
                  functionBody(GpuShaderSource.readDisk("magma-solid.glsl"),
                        "Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill, vec3 random)",
                        "return magmaSample(uv, dx, dy, eye, false);")));
            report.append("Solid-relief removes crust/bank relief tracing and displaced surface reread. ")
                  .append("All four material maps and flowing lava remain; displaced texture values/coverage change.\n");
        }
        if (detail || diagnostics.contains("solidMaterial")) {
            materialProbe(result, "solid-material", Map.of("magma-solid.glsl",
                  functionBody(GpuShaderSource.readDisk("magma-solid.glsl"),
                        "Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill, vec3 random)",
                        "return Volcanic(vec3(.2), vec3(0.0, 0.0, 1.0), vec4(.5, .92, 1.0, 0.0), vec2(.1, .1), vec3(0.0));")));
            report.append("Solid-material neutralizes static volcanic sampling/relief, keeping volcanic identity ")
                  .append("and the flowing shader. Constant-dependent projection work can fold; height/lighting change.\n");
        }
        if (detail || diagnostics.contains("lavaPhase")) {
            String flow = GpuShaderSource.readDisk("magma-flow.glsl");
            String phase = "Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 velocity, vec3 random)";
            int start = flow.indexOf('{', flow.indexOf(phase)) + 1;
            int second = flow.indexOf("    Volcanic b =", start);
            assertTrue(start > 0 && second > start, "Expected the first complete advected phase before the second");
            materialProbe(result, "lava-phase", Map.of("magma-flow.glsl",
                  functionBody(flow, phase, flow.substring(start, second) + "return a;")));
            report.append("Lava-phase retains the first advected complete material and its cooling response. ")
                  .append("It removes the second phase and plate contact; the visible combined pattern changes.\n");
        }
        if (detail || diagnostics.contains("contactNoise")) {
            materialProbe(result, "contact-noise", Map.of("terrain-materials.glsl",
                  functionBody(GpuShaderSource.readDisk("terrain-materials.glsl"),
                        "float coverPatch(vec3 world, float familyId)", "return .5;")));
            report.append("Contact-noise replaces two contact-field reads per evaluated palette slot with .5. ")
                  .append("Raw coverage/material evaluations remain; final height-competition weights change.\n");
        }
    }

    private static void materialProbe(List<Variant> result, String name, Map<String, String> sources) {
        result.add(new Variant("diagnostic-" + name, null, sources));
        result.add(new Variant("diagnostic-" + name + "-restored", "current", Map.of()));
    }

    /** Keep declarations and helper insertion points while removing just one known function's work. */
    private static String functionBody(String source, String signature, String body) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0 && source.indexOf(signature, start + signature.length()) < 0,
              "Expected one diagnostic function: " + signature);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        assertTrue(open >= 0, "Expected a function body: " + signature);
        while (end < source.length() && depth > 0) {
            char next = source.charAt(end++);
            if (next == '{') { depth++; }
            else if (next == '}') { depth--; }
        }
        assertEquals(0, depth, "Unbalanced diagnostic function: " + signature);
        return source.substring(0, open + 1) + "\n" + body + "\n}" + source.substring(end);
    }

    private static void replaceSources(GpuShaderManager manager, Map<String, String> sources, String label,
          StringBuilder report) {
        System.out.println(label + " source-replace starting");
        System.out.flush();
        Gdx.gl.glFinish();
        long start = CAPTURE_ONLY ? 0 : System.nanoTime();
        var change = manager.apply(sources);
        String completed = CAPTURE_ONLY ? label + " source-replace complete (visual-only), programs=" + change.updated() + "\n"
              : String.format(Locale.ROOT, "%s source-replace wallMs=%.3f programs=%d%n",
                    label, (System.nanoTime() - start) / 1e6, change.updated());
        report.append(completed);
        System.out.print(completed);
        System.out.flush();
        assertTrue(change.success(), change.message());
    }

    private static void appendSamples(GpuStageTimings timings, StringBuilder report, String label, int count) {
        var measured = new StringBuilder();
        timings.appendReport(measured, label);
        report.append(measured);
        String[] rows = measured.toString().split("\\R");
        assertTrue(rows[0].contains("GPU timestamps: supported") && rows[0].endsWith("dropped samples: 0"), rows[0]);
        assertTrue(rows.length > 2, "Expected actual measured stages: " + label);
        for (int row = 2; row < rows.length; row++) {
            String[] columns = rows[row].split(",");
            assertEquals(count, Integer.parseInt(columns[1]), label + " incomplete samples: " + columns[0]);
        }
    }

    private static void imageDifference(StringBuilder report, String label, byte[] reference, byte[] current) {
        long total = 0, changed = 0;
        int maximum = 0;
        for (int i = 0; i < reference.length; i++) {
            int difference = Math.abs(Byte.toUnsignedInt(reference[i]) - Byte.toUnsignedInt(current[i]));
            total += difference;
            maximum = Math.max(maximum, difference);
            if (difference > 2) { changed++; }
        }
        report.append(String.format(Locale.ROOT, "%s image meanAbs=%.6f channelsAbove2=%.5f%% max=%d%n",
              label, total / (double) reference.length, changed * 100.0 / reference.length, maximum));
    }

    private static double percentile(double[] values, double fraction) {
        return values[Math.min(values.length - 1, (int) (values.length * fraction))];
    }
}
