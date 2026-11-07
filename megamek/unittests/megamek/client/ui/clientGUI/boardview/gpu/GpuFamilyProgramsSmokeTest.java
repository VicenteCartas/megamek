/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Terrain family programs (GpuFamilyPrograms) against their generic programs, within one 8-bit step in colour and
 * alpha and one depth step: on the terrain calibration board with its 32 most-drawn family programs, and on a small
 * board where grass, dirt and cooled lava meet, which builds the plain, ordinary-blend and mixed volcanic kinds. A
 * second calibration load takes every program from the program cache. On the small board a newly painted combination
 * draws at once with its generic program, never waits and links nothing: family programs build only behind the
 * loading stage. A lighting change while a family link is pending, and back, draws no stale program, leaks no link and
 * keeps the provider's family programs within the capacity. A shader edit's swap releases the draw-order groups of the
 * programs it replaced, a rejected edit keeps them and the frame, and a program its provider does not keep has none.
 * The tests share one program cache; in this order the small board loads most programs the calibration board
 * compiled. Reports go to build/gpu-board-review/family-programs.
 */
@Tag("on-demand")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GpuFamilyProgramsSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "family-programs");
    private static final File CALIBRATION =
          new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");
    /** One step of the 24-bit depth buffer. */
    private static final double DEPTH_STEP = 1.0 / ((1 << 24) - 1);
    /** A frame that compiled a program on the render thread would take seconds on the integrated GPUs we target. */
    private static final double LONGEST_EDIT_FRAME_MS = 250;
    /** Frames slower than this while family programs build are listed in the report. */
    private static final double SLOW_FRAME_MS = 200;
    /** Pixel comparisons that failed in the running test; run() fails the test after its other checks. */
    private static final List<String> PIXEL_FAILURES = new java.util.ArrayList<>();
    /** A moonless night: zero direct light with the same directional/shadow bindings as daylight. */
    private static final BoardAtmosphere.Settings NIGHT = new BoardAtmosphere.Settings(0, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false);
    @TempDir static Path directory;

    @Test
    @Order(1)
    void calibrationFamilyProgramsDrawTheGenericFrameAndComeFromTheCache() throws Exception {
        BoardScene scene;
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(CALIBRATION, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        StringBuilder report = new StringBuilder();
        run("calibration", report, () -> {
            var cache = GpuProgramCache.open(directory.resolve("cache"));
            GpuGlsl.programCache = cache;
            report.append(String.format(Locale.ROOT, "%s, %s%n", Gdx.gl.glGetString(GL20.GL_RENDERER),
                  Gdx.gl.glGetString(GL20.GL_VERSION)));
            System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
            Frame generic = load(scene, "calibration-generic", report);
            // Control: a second generic build must match the first, or the comparison below cannot be judged.
            compare("Generic twice", generic, load(scene, "calibration-control", report), report);
            System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
            Frame family = load(scene, "calibration-family", report);
            assertTrue(family.ready() > 0, "The calibration board builds family programs");
            compare("Generic against family", generic, family, report);
            if (cache == null) {
                report.append("No program binary format: the warm load is skipped.\n");
                return;
            }
            assertTrue(family.ready() <= GpuFamilyPrograms.FRESH, "A load compiles its share of family programs");
            int stored = cache.stored.get(), loaded = cache.loaded.get();
            Frame warm = load(scene, "calibration-warm", report);
            int compiled = cache.stored.get() - stored;
            report.append(String.format(Locale.ROOT, "Warm load: %d programs loaded, %d compiled%n",
                  cache.loaded.get() - loaded, compiled));
            assertTrue(compiled <= GpuFamilyPrograms.FRESH, "A second load compiles only its share of family programs");
            assertEquals(family.ready() + compiled, warm.ready(), "and takes the first load's from the cache");
            assertTrue(difference(family.pixels(), warm.pixels()) <= 1, "Cached family programs draw the same");
        });
    }

    @Test
    @Order(2)
    void smallBoardMatchesAndAPaintedCombinationNeverWaits() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        // Grass, dirt and cooled lava meet: plain, ordinary-blend and mixed volcanic family programs, under the cap.
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        Coords painted = new Coords(10, 10);
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene before = fixture.source.takeFrame().scene();
            run("small-board", report, () -> {
                var cache = GpuProgramCache.open(directory.resolve("cache"));
                GpuGlsl.programCache = cache;
                System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
                Frame generic = load(before, "small-generic", report);
                System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(before);
                    settle(terrain, frame, camera, before);
                    finish(terrain, frame, camera, before, "small-family", report);
                    Frame family = capture(terrain, frame, camera, before, "small-family");
                    compare("Generic against family", generic, family, report);
                    sameInstalledPrograms(terrain, frame, camera, before, family, report);
                    List<float[]> built = terrain.familyPrograms().ready();
                    report.append("Small board family programs: ")
                          .append(built.stream().map(Arrays::toString).toList()).append('\n');
                    assertTrue(built.stream().anyMatch(GpuFamilyProgramsSmokeTest::mixedVolcanic),
                          "Grass, dirt and cooled lava build a mixed volcanic family program");

                    // Paint a combination the board has not had: its chunk installs with the generic program, which
                    // it keeps until the next load; nothing links while editing.
                    int ready = built.size(), stored = cache == null ? 0 : cache.stored.get();
                    SwingUtilities.invokeAndWait(() -> {
                        fixture.game.getBoard().setHex(painted, new Hex(0, "", "lunar"));
                        fixture.source.refresh();
                    });
                    BoardScene after = fixture.source.takeFrame().scene();
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    long start = System.nanoTime(), installed = 0;
                    int waiting = -1, frames = 0;
                    double longest = 0;
                    // What's timed is a wait for programs: the smoke tasks' cache fill at once would take a whole frame of its own.
                    terrain.setMaterialCache(false);
                    terrain.update(after, camera.camera);
                    do {
                        long frameStart = System.nanoTime();
                        terrain.refine(camera.camera, true);
                        frame.render(terrain, camera, after);
                        longest = Math.max(longest, (System.nanoTime() - frameStart) / 1e6);
                        frames++;
                        if (installed == 0 && !terrain.busy()
                              && terrain.presentation(after).tile(painted).equals(after.tile(painted))) {
                            installed = System.nanoTime();
                            waiting = terrain.familyPrograms().pending();
                        }
                        assertTrue(System.nanoTime() < deadline, "The painted combination must install");
                    } while (installed == 0 || frames < 120);
                    report.append(String.format(Locale.ROOT, "Paint: installed after %d ms with %d family programs"
                                + " waiting; %d -> %d ready after %d ms and %d frames, %d compiled; longest frame"
                                + " %.1f ms%n", (installed - start) / 1_000_000, waiting, ready,
                          terrain.familyPrograms().ready().size(), (System.nanoTime() - start) / 1_000_000, frames,
                          cache == null ? -1 : cache.stored.get() - stored, longest));
                    assertTrue(waiting > 0, "The painted combination draws with its generic program");
                    assertEquals(ready, terrain.familyPrograms().ready().size(), "and keeps it while editing");
                    if (cache != null) { assertEquals(stored, cache.stored.get(), "Nothing links while editing"); }
                    assertTrue(longest < LONGEST_EDIT_FRAME_MS, "No frame waits for a family program");
                } finally {
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    @Test
    @Order(3)
    void lightingChangesWhileAFamilyLinkIsPendingLeaveNoStaleProgramNoLeakAndTheCapacity() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            run("lifecycle", report, () -> {
                // A family link must be pending when the lighting changes. The earlier tests stored this board's family
                // programs, which would load at once: its own cold cache. A driver's program cache can finish a parallel
                // link within its first step: the shared-context path, where a link finishes on another thread.
                GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("lifecycle-cache"));
                System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
                Frame generic = load(scene, "lifecycle-generic", report);
                System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
                // A capacity the small board exceeds, so the toggles below reach it.
                int capacity = GpuFamilyPrograms.capacity;
                GpuFamilyPrograms.capacity = 3;
                GpuGlsl.Preparation.sharedContext = true;
                boolean antialiasing = GpuEnvironmentLighting.specularAntialiasing;
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(scene);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    terrain.animate(.5f, List.of());
                    // Toggle AA while a family link is pending; day/night alone must keep the configuration.
                    boolean night = false;
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
                        if (!night && terrain.familyPrograms().building()) {
                            GpuEnvironmentLighting.specularAntialiasing = !antialiasing;
                            frame.configure(NIGHT);
                            frame.prepare(terrain, camera, scene);
                            night = true;
                        }
                        assertTrue(System.nanoTime() < deadline, "The night must settle");
                        Thread.sleep(5);
                    }
                    assertTrue(night, "A family link was pending when the lighting changed");
                    assertFalse(frame.lighting().hasDirectLight(), "The retained binding does not create moonlight");
                    assertTrue(terrain.environment().shadowMap != null, "Night retains the shadow binding");
                    int afterNight = terrain.familyPrograms().programs();
                    // AA really changes the program configuration, retaining the in-flight/capacity regression.
                    assertEquals(3, terrain.familyPrograms().ready().size(),
                          "The changed AA configuration has its own family programs");
                    GpuEnvironmentLighting.specularAntialiasing = antialiasing;
                    frame.configure(BoardAtmosphere.DEFAULTS);
                    frame.prepare(terrain, camera, scene);
                    while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
                        assertTrue(System.nanoTime() < deadline, "The day must settle");
                        Thread.sleep(5);
                    }
                    report.append(String.format(Locale.ROOT, "Lifecycle: %d family programs after the night, %d after"
                                + " the day again, %d in the provider; %d ready%n", afterNight,
                          terrain.familyPrograms().programs(), familyProgramsIn(terrain),
                          terrain.familyPrograms().ready().size()));
                    compare("Day again against generic", generic, capture(terrain, frame, camera, scene,
                          "lifecycle-day"), report);
                    assertTrue(terrain.familyPrograms().programs() <= 2 * 3, "Twice the capacity bounds the programs built");
                    assertEquals(terrain.familyPrograms().programs(), familyProgramsIn(terrain),
                          "and the provider holds exactly those");
                    assertEquals(0, unfinishedLinks(terrain), "No link is left behind");

                    // Edits and more changes: the provider's family programs stay within the capacity.
                    for (int round = 0; round < 2; round++) {
                        int hex = round;
                        SwingUtilities.invokeAndWait(() -> {
                            fixture.game.getBoard().setHex(new Coords(10 + hex, 10), new Hex(0, "", "lunar"));
                            fixture.source.refresh();
                        });
                        terrain.update(fixture.source.takeFrame().scene(), camera.camera);
                        for (var settings : List.of(NIGHT, BoardAtmosphere.DEFAULTS)) {
                            GpuEnvironmentLighting.specularAntialiasing = settings == NIGHT ? !antialiasing : antialiasing;
                            frame.configure(settings);
                            frame.prepare(terrain, camera, scene);
                            while (terrain.refine(camera.camera) | terrain.busy()
                                  || terrain.familyPrograms().pending() > 0 && terrain.familyPrograms().building()) {
                                assertTrue(System.nanoTime() < deadline, "Edits and changes must settle");
                                Thread.sleep(5);
                            }
                        }
                    }
                    report.append(String.format(Locale.ROOT, "After edits and changes: %d family programs, %d in the"
                          + " provider%n", terrain.familyPrograms().programs(), familyProgramsIn(terrain)));
                    assertTrue(familyProgramsIn(terrain) <= 2 * 3, "Edits and changes keep the capacity");
                    assertEquals(0, unfinishedLinks(terrain), "and leave no link behind");
                } finally {
                    GpuFamilyPrograms.capacity = capacity;
                    GpuEnvironmentLighting.specularAntialiasing = antialiasing;
                    GpuGlsl.Preparation.sharedContext = false;
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    @Test
    @Order(4)
    void shaderEditsReleaseReplacedDrawOrderGroupsAndRejectedOnesKeepThem() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            run("shader-edits", report, () -> {
                GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("cache"));
                // A shader-editor session owns the terrain's providers, as in the board window.
                var session = new GpuShaderManager();
                AtomicReference<Exception> thrown = new AtomicReference<>();
                session.run(() -> {
                    try { shaderEdits(session, scene, report); }
                    catch (Exception failure) { thrown.set(failure); }
                });
                if (thrown.get() != null) { throw thrown.get(); }
            });
        }
    }

    /**
     * Inside the session: an edit of a file the board does not draw swaps every terrain program for a copy from the
     * program cache; an edit that a later program fails to compile is rejected after the terrain prepared its
     * replacements; and a family program built as its provider builds one but not initialized, as when its init fails
     * before the provider keeps it, has no group.
     */
    private static void shaderEdits(GpuShaderManager session, BoardScene scene, StringBuilder report) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        try {
            BoardCamera camera = camera(scene);
            settle(terrain, frame, camera, scene);
            finish(terrain, frame, camera, scene, "shader-edits", report);
            Map<Shader, Shader> groups = shaderGroups(terrain);
            Map<Shader, Shader> before = new IdentityHashMap<>(groups);
            Frame first = capture(terrain, frame, camera, scene, "shader-edits-before");
            String spray = GpuShaderSource.readDisk("water-spray.frag");
            assertTrue(session.apply(Map.of("water-spray.frag", spray + "\n// draw-order groups\n")).success(),
                  "The edit swaps the terrain's programs");
            Map<Shader, Shader> swapped = new IdentityHashMap<>(groups);
            int family = familyProgramsIn(terrain);
            Set<Shader> grouped = groupedProgramsIn(terrain);
            Frame afterSwap = capture(terrain, frame, camera, scene, "shader-edits-swapped");
            assertFalse(session.apply(Map.of("water-spray.frag", spray + "\n// rejected\n",
                  "atmosphere-composite.frag", "#version 330 core\nthis does not compile")).success(),
                  "A program that fails rejects the edit");
            Map<Shader, Shader> rejected = new IdentityHashMap<>(groups);
            Frame afterRejection = capture(terrain, frame, camera, scene, "shader-edits-rejected");

            var create = DefaultShaderProvider.class.getDeclaredMethod("createShader", Renderable.class);
            create.setAccessible(true);
            Shader built = (Shader) create.invoke(((GpuShaderProvider) provider(terrain)).active(), familySample(terrain));
            boolean uninitialized = groups.containsKey(built), initialized;
            try {
                built.init();
                initialized = groups.containsKey(built);
            } finally {
                built.dispose();
            }
            report.append(String.format(Locale.ROOT, "Draw-order groups: %d before the edits, %d after the swap (%d family"
                  + " programs), %d after the rejected edit; a family program built aside has one: not initialized %b,"
                  + " initialized %b, disposed %b%n", before.size(), swapped.size(), family, rejected.size(), uninitialized,
                  initialized, groups.containsKey(built)));
            compare("Swapped against before", first, afterSwap, report);
            compare("Rejected against swapped", afterSwap, afterRejection, report);
            assertFalse(before.isEmpty(), "Family programs have draw-order groups");
            assertTrue(Collections.disjoint(before.keySet(), swapped.keySet()), "The swap releases the replaced programs");
            assertTrue(identities(providerShaders(terrain)).containsAll(swapped.keySet()),
                  "Every group belongs to an installed program");
            assertEquals(grouped, identities(swapped.keySet()), "one for each family and cached-lit program");
            assertEquals(swapped, rejected, "A rejected edit keeps the groups");
            assertFalse(uninitialized, "A program its provider does not keep has no group");
            assertTrue(initialized, "An initialized family program sorts with its generic program");
            assertFalse(groups.containsKey(built), "and a disposed one has no group");
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    @Test
    @Order(5)
    void returningFromALightingConfigurationWithoutFamilyProgramsTakesBackTheEarlierOnes() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        StringBuilder report = new StringBuilder();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            run("return", report, () -> {
                GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("cache"));
                int capacity = GpuFamilyPrograms.capacity;
                boolean antialiasing = GpuEnvironmentLighting.specularAntialiasing;
                GpuFamilyPrograms.capacity = 3;
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(scene);
                    settle(terrain, frame, camera, scene);
                    int day = terrain.familyPrograms().ready().size();
                    // The opposite AA configuration builds none, so returning must recover the earlier markers.
                    GpuFamilyPrograms.capacity = 0;
                    GpuEnvironmentLighting.specularAntialiasing = !antialiasing;
                    frame.configure(NIGHT);
                    settle(terrain, frame, camera, scene);
                    int night = terrain.familyPrograms().ready().size();
                    GpuFamilyPrograms.capacity = 3;
                    GpuEnvironmentLighting.specularAntialiasing = antialiasing;
                    frame.configure(BoardAtmosphere.DEFAULTS);
                    frame.prepare(terrain, camera, scene);
                    // The day's programs are in the provider: taking them back links nothing.
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
                    while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
                        assertTrue(System.nanoTime() < deadline, "The day's family programs must come back");
                        Thread.sleep(5);
                    }
                    report.append(String.format(Locale.ROOT, "Return: %d family programs by day, %d at night, %d by day"
                          + " again%n", day, night, terrain.familyPrograms().ready().size()));
                    assertTrue(day > 0, "The day builds family programs");
                    assertEquals(0, night, "The other AA configuration builds none at zero capacity");
                    assertEquals(day, terrain.familyPrograms().ready().size(), "The day takes its programs back");
                } finally {
                    GpuFamilyPrograms.capacity = capacity;
                    GpuEnvironmentLighting.specularAntialiasing = antialiasing;
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    /**
     * Specular anti-aliasing uses two configurations, each up to capacity. Day/night transitions within either reuse
     * the exact programs. Returning to the earlier AA choice also reuses its programs. The report lists the totals
     * and what the provider and the draw-order groups hold.
     */
    @Test
    @Order(6)
    void lightingToggleChurnStopsAtTwiceTheCapacityWithoutLeaks() throws Exception {
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(new Coords(4, 4), new Hex(0, "", "dirt"));
        board.setHex(new Coords(5, 4), new Hex(0, "magma:1", ""));
        StringBuilder report = new StringBuilder("configuration,ready,built,provider,groups,unfinishedLinks\n");
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            run("churn", report, () -> {
                GpuGlsl.programCache = GpuProgramCache.open(directory.resolve("cache"));
                int capacity = GpuFamilyPrograms.capacity;
                boolean antialiasing = GpuEnvironmentLighting.specularAntialiasing;
                GpuFamilyPrograms.capacity = 3;
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardCamera camera = camera(scene);
                    // Only AA changes the configuration; no night preset spends another compile slot.
                    int[] expected = { 3, 3, 3, 3, 3, 3 };
                    int[] expectedBuilt = { 3, 3, 6, 6, 6, 6 };
                    List<BoardAtmosphere.Settings> nights = List.of(AtmospherePreset.MOONLESS.settings(0.5),
                          AtmospherePreset.PITCH_BLACK.settings(0.5), NIGHT);
                    Set<Shader> dayPrograms = Set.of();
                    for (int step = 0; step < expected.length; step++) {
                        boolean on = step < 2 || step > 3, night = step % 2 == 1;
                        GpuEnvironmentLighting.specularAntialiasing = on;
                        frame.configure(night ? nights.get(step / 2) : BoardAtmosphere.DEFAULTS);
                        settle(terrain, frame, camera, scene);
                        int ready = terrain.familyPrograms().ready().size(), built = terrain.familyPrograms().programs();
                        int provider = familyProgramsIn(terrain), groups = shaderGroups(terrain).size();
                        int links = unfinishedLinks(terrain);
                        report.append(String.format(Locale.ROOT, "%s %s,%d,%d,%d,%d,%d%n", night ? "night" : "day",
                              on ? "AA on" : "AA off", ready, built, provider, groups, links));
                        assertEquals(expected[step], ready, "Configuration " + step + " family programs");
                        assertEquals(expectedBuilt[step], built, "Day/night changes no compiled family configuration");
                        if (!night) { dayPrograms = identities(providerShaders(terrain)); }
                        else {
                            assertEquals(dayPrograms, identities(providerShaders(terrain)),
                                  "The first moonless frame retains the exact generic and family programs");
                        }
                        assertTrue(built <= 2 * 3, "Twice the capacity bounds the programs built");
                        assertEquals(built, provider, "The provider holds exactly the programs built");
                        assertEquals(groupedProgramsIn(terrain), identities(shaderGroups(terrain).keySet()),
                              "each family and cached-lit program with its draw-order group");
                        assertEquals(0, links, "No link is left behind");
                    }
                } finally {
                    GpuFamilyPrograms.capacity = capacity;
                    GpuEnvironmentLighting.specularAntialiasing = antialiasing;
                    frame.dispose();
                    terrain.dispose();
                }
            });
        }
    }

    /** Change only routing on the already installed meshes; keep the independent-build comparison above too. */
    private static void sameInstalledPrograms(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, Frame family, StringBuilder report) throws Exception {
        Set<Material> marked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object value : (com.badlogic.gdx.utils.Array<?>)
                  GpuTestSupport.field(chunk, "terrainRenderables")) {
                Material material = ((Renderable) value).material;
                if (material.has(GpuFamilyPrograms.Constants.TYPE)) { marked.add(material); }
            }
        }
        assertFalse(marked.isEmpty(), "The installed terrain has actual marked family materials");
        var routing = (GpuTerrainBatch) GpuTestSupport.field(terrain, "terrainBatch");
        report.append("Same installed meshes: toggling ").append(marked.size()).append(" material markers\n");
        try {
            marked.forEach(material -> material.remove(GpuFamilyPrograms.Constants.TYPE));
            routing.clear(); // Same invalidation as GpuTerrain.refine when family adoption changes these markers.
            Frame generic = capture(terrain, frame, camera, scene, "small-same-mesh-generic");
            assertTrue(marked.stream().noneMatch(material -> material.has(GpuFamilyPrograms.Constants.TYPE)),
                  "The generic capture must not have silently re-adopted family markers");
            compare("Same installed meshes generic against family", generic, family, report);
            assertEquals(family.geometry(), generic.geometry(), "Routing must not change installed vertex/index bytes");
        } finally {
            marked.forEach(material -> material.set(new GpuFamilyPrograms.Constants()));
            routing.clear();
        }
        Frame restored = capture(terrain, frame, camera, scene, "small-same-mesh-restored");
        compare("Same installed meshes restored family", family, restored, report);
        assertEquals(family.geometry(), restored.geometry(), "Restoration must keep installed vertex/index bytes");
    }

    /** The family programs the terrain's shader provider holds, counted from their sources. */
    private static int familyProgramsIn(GpuTerrain terrain) throws ReflectiveOperationException {
        int count = 0;
        for (Shader shader : providerShaders(terrain)) { if (family(shader)) { count++; } }
        return count;
    }

    /** The programs that sort with a generic program: family programs, and the material cache's cached-lit ones. */
    private static Set<Shader> groupedProgramsIn(GpuTerrain terrain) throws ReflectiveOperationException {
        Set<Shader> result = identities(List.of());
        for (Shader shader : providerShaders(terrain)) {
            if (family(shader) || shader instanceof BaseShader base && base.program.getFragmentShaderSource().contains("#define materialCachedFlag")) {
                result.add(shader);
            }
        }
        return result;
    }

    private static boolean family(Shader shader) {
        return shader instanceof BaseShader base && base.program.getFragmentShaderSource().contains("#define SCULPT_FAMILY");
    }

    private static ShaderProvider provider(GpuTerrain terrain) throws ReflectiveOperationException {
        var batch = GpuTerrain.class.getDeclaredField("batch");
        batch.setAccessible(true);
        return ((com.badlogic.gdx.graphics.g3d.ModelBatch) batch.get(terrain)).getShaderProvider();
    }

    /** The programs the terrain's shader provider holds; in a shader-editor session, its installed provider's. */
    private static List<Shader> providerShaders(GpuTerrain terrain) throws ReflectiveOperationException {
        ShaderProvider provider = provider(terrain);
        if (provider instanceof GpuShaderProvider edited) { provider = edited.active(); }
        var shaders = com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        List<Shader> result = new ArrayList<>();
        for (var shader : (com.badlogic.gdx.utils.Array<?>) shaders.get(provider)) { result.add((Shader) shader); }
        return result;
    }

    /** The sample a shader-editor session keeps for one of the terrain's family programs: what it rebuilds it from. */
    private static Renderable familySample(GpuTerrain terrain) throws ReflectiveOperationException {
        var handles = GpuShaderProvider.class.getDeclaredField("handles");
        handles.setAccessible(true);
        for (Object handle : (List<?>) handles.get(provider(terrain))) {
            var shader = handle.getClass().getDeclaredField("shader");
            var sample = handle.getClass().getDeclaredField("sample");
            shader.setAccessible(true);
            sample.setAccessible(true);
            if (family((Shader) shader.get(handle))) { return (Renderable) sample.get(handle); }
        }
        throw new AssertionError("The terrain has a family program");
    }

    /** The terrain's draw-order groups (GpuTerrain.shaderGroups): each family program's generic program. */
    @SuppressWarnings("unchecked")
    private static Map<Shader, Shader> shaderGroups(GpuTerrain terrain) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("shaderGroups");
        field.setAccessible(true);
        return (Map<Shader, Shader>) field.get(terrain);
    }

    private static Set<Shader> identities(Collection<Shader> shaders) {
        Set<Shader> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(shaders);
        return result;
    }

    /** Links the terrain's compiler queue still holds: started and never adopted. */
    private static int unfinishedLinks(GpuTerrain terrain) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("terrainMaterialPreparation");
        field.setAccessible(true);
        Object preparation = field.get(terrain);
        int count = 0;
        for (String name : List.of("pending", "working")) {
            var queue = preparation.getClass().getDeclaredField(name);
            queue.setAccessible(true);
            count += ((java.util.Map<?, ?>) queue.get(preparation)).size();
        }
        return count;
    }

    private interface Body { void run() throws Exception; }

    /** Runs the body on a window's GL thread, restoring the program cache and family property; reports to name.txt. */
    private static void run(String name, StringBuilder report, Body body) throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuProgramCache previous = GpuGlsl.programCache;
                String property = System.getProperty(GpuFamilyPrograms.PROPERTY);
                try {
                    PIXEL_FAILURES.clear();
                    body.run();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    assertTrue(PIXEL_FAILURES.isEmpty(), "Pixels: " + PIXEL_FAILURES);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuGlsl.programCache = previous;
                    if (property == null) { System.clearProperty(GpuFamilyPrograms.PROPERTY); }
                    else { System.setProperty(GpuFamilyPrograms.PROPERTY, property); }
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.writeString(new File(OUTPUT, name + ".txt").toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Family programs", failure.get()); }
    }

    private record Frame(byte[] pixels, float[] depth, int ready, Map<String, String> geometry) { }

    /** A fresh terrain settled on the board, drawn until no family program is waiting, then captured. */
    private static Frame load(BoardScene scene, String name, StringBuilder report) throws InterruptedException {
        long start = System.nanoTime();
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        try {
            BoardCamera camera = camera(scene);
            settle(terrain, frame, camera, scene);
            long settled = System.nanoTime();
            double longest = finish(terrain, frame, camera, scene, name, report);
            report.append(String.format(Locale.ROOT, "%s: settled in %d ms; %d of %d variants built (cap %d) %d ms"
                        + " later; longest frame meanwhile %.1f ms%n", name, (settled - start) / 1_000_000,
                  terrain.familyPrograms().ready().size(), terrain.familyPrograms().requested(),
                  GpuFamilyPrograms.CAPACITY, (System.nanoTime() - settled) / 1_000_000, longest));
            return capture(terrain, frame, camera, scene, name);
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /**
     * The settled scene's colour and alpha, and the depth the composite wrote. Frames are drawn until nothing builds and
     * refinement twice has nothing left, since detail, tree levels and scatter visibility settle with drawn frames; then
     * the shadow map is drawn afresh, since it keeps what it showed when it was drawn. Two terrains with different
     * histories then capture the same scene.
     */
    private static Frame capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name) {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        for (int quiet = 0; quiet < 2; ) {
            frame.render(terrain, camera, scene);
            quiet = terrain.refine(camera.camera) | terrain.busy() ? 0 : quiet + 1;
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
        }
        terrain.refreshShadows();
        frame.render(terrain, camera, scene);
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        byte[] pixels;
        try {
            ByteBuffer buffer = image.getPixels();
            pixels = new byte[buffer.remaining()];
            buffer.get(pixels);
        } finally {
            image.dispose();
        }
        FloatBuffer depths = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
        float[] depth = new float[width * height];
        depths.get(depth);
        GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
        // Only this small-board diagnostic hashes CPU mesh bytes; calibration/timing paths retain their old cost.
        Map<String, String> geometry = name.startsWith("small-") ? geometry(terrain) : Map.of();
        return new Frame(pixels, depth, terrain.familyPrograms().ready().size(), geometry);
    }

    /** Canonical indexed triangles: independent of source part order/packing, retaining winding and transforms. */
    private static Map<String, String> geometry(GpuTerrain terrain) {
        var result = new TreeMap<String, String>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int chunkIndex = 0;
            for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                for (String layer : List.of("terrainRenderables", "scatterRenderables")) {
                    var triangles = new ArrayList<String>();
                    var vertices = new IdentityHashMap<Mesh, float[]>();
                    var indices = new IdentityHashMap<Mesh, short[]>();
                    for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, layer)) {
                        Renderable part = (Renderable) value;
                        Mesh mesh = part.meshPart.mesh;
                        int stride = mesh.getVertexSize() / Float.BYTES;
                        float[] points = vertices.computeIfAbsent(mesh, key -> key.getVertices(new float[key.getNumVertices() * stride]));
                        short[] elements = indices.computeIfAbsent(mesh, key -> {
                            short[] values = new short[key.getNumIndices()];
                            if (values.length > 0) { key.getIndices(values); }
                            return values;
                        });
                        assertEquals(GL20.GL_TRIANGLES, part.meshPart.primitiveType, "These installed layers are triangles");
                        assertEquals(0, part.meshPart.size % 3, "Every indexed triangle is complete");
                        ByteBuffer triangle = ByteBuffer.allocate(Long.BYTES + (16 + 3 * stride) * Float.BYTES);
                        for (int i = part.meshPart.offset; i < part.meshPart.offset + part.meshPart.size; i += 3) {
                            triangle.clear();
                            triangle.putLong(mesh.getVertexAttributes().getMask());
                            for (float component : part.worldTransform.val) { triangle.putInt(Float.floatToRawIntBits(component)); }
                            for (int corner = 0; corner < 3; corner++) {
                                int vertex = elements.length == 0 ? i + corner : Short.toUnsignedInt(elements[i + corner]);
                                for (int field = 0; field < stride; field++) {
                                    triangle.putInt(Float.floatToRawIntBits(points[vertex * stride + field]));
                                }
                            }
                            triangles.add(HexFormat.of().formatHex(digest.digest(triangle.array())));
                        }
                    }
                    Collections.sort(triangles);
                    for (String triangle : triangles) { digest.update(HexFormat.of().parseHex(triangle)); }
                    result.put(chunkIndex + ":" + layer, triangles.size() + ":" + HexFormat.of().formatHex(digest.digest()));
                }
                chunkIndex++;
            }
        } catch (Exception error) { throw new AssertionError(error); }
        return result;
    }

    private static void compare(String label, Frame generic, Frame family, StringBuilder report) {
        for (var entry : generic.geometry().entrySet()) {
            String other = family.geometry().get(entry.getKey());
            if (!entry.getValue().equals(other)) {
                report.append(label).append(" mesh ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append(" -> ").append(other).append('\n');
            }
        }
        report.append(String.format(Locale.ROOT, "%s: colour/alpha largest difference %d, %d values over 1;"
                    + " depth largest difference %.1f steps%n", label,
              difference(generic.pixels(), family.pixels()), over(generic.pixels(), family.pixels()),
              difference(generic.depth(), family.depth()) / DEPTH_STEP));
        // The first differing pixels, top-down like the saved PNGs: where to look, and whether depth tied.
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight(), listed = 0;
        for (int i = 0; i < generic.pixels().length && listed < 10; i += 4) {
            int largest = 0;
            for (int c = 0; c < 4; c++) {
                largest = Math.max(largest, Math.abs((generic.pixels()[i + c] & 255) - (family.pixels()[i + c] & 255)));
            }
            if (largest <= 1) { continue; }
            int pixel = i / 4;
            report.append(String.format(Locale.ROOT, "    pixel %d,%d: %s -> %s, depth %.9f -> %.9f%n", pixel % width,
                  height - 1 - pixel / width, rgba(generic.pixels(), i), rgba(family.pixels(), i),
                  generic.depth()[pixel], family.depth()[pixel]));
            listed++;
        }
        // Recorded, not thrown: the rest of the test still runs, and run() fails it at the end.
        if (difference(generic.pixels(), family.pixels()) > 1) {
            PIXEL_FAILURES.add(label + ": colour/alpha differ by " + difference(generic.pixels(), family.pixels()));
        }
        if (difference(generic.depth(), family.depth()) > DEPTH_STEP * 1.5) { PIXEL_FAILURES.add(label + ": depth"); }
    }

    private static String rgba(byte[] pixels, int i) {
        return String.format(Locale.ROOT, "(%d,%d,%d,%d)", pixels[i] & 255, pixels[i + 1] & 255, pixels[i + 2] & 255,
              pixels[i + 3] & 255);
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
              .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    /** A blend program with cooled lava and at least two ordinary families (GpuSurfaceBlend.ordinaryFamily -1). */
    private static boolean mixedVolcanic(float[] families) {
        if (families.length != 5) { return false; }
        float[] slots = Arrays.copyOfRange(families, 1, 5);
        boolean lava = false;
        float ordinary = -1;
        boolean mixed = false;
        for (float family : slots) {
            if (family >= BoardSurfaceBlend.CRUST) { lava = true; }
            else if (ordinary < 0) { ordinary = family; }
            else if (family != ordinary) { mixed = true; }
        }
        return lava && mixed;
    }

    private static BoardCamera camera(BoardScene scene) {
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        return camera;
    }

    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene)
          throws InterruptedException {
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        // The loading stage, not busy(), waits for a load's family programs: so does this.
        while (terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0) {
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
    }

    /**
     * Draws frames until no family program is waiting; returns the longest frame. Reports the frame count and each
     * frame over SLOW_FRAME_MS with its wall-clock start (to match the GPU_SHADER log), the family programs ready
     * before and after it, and the garbage collection inside it.
     */
    private static double finish(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          String name, StringBuilder report) {
        long start = System.nanoTime(), deadline = start + TimeUnit.MINUTES.toNanos(20);
        double longest = 0;
        int frames = 0;
        StringBuilder slow = new StringBuilder();
        do {
            int ready = terrain.familyPrograms().ready().size();
            long collected = gcMillis();
            LocalTime at = LocalTime.now();
            long before = System.nanoTime();
            frame.render(terrain, camera, scene);
            double millis = (System.nanoTime() - before) / 1e6;
            longest = Math.max(longest, millis);
            frames++;
            if (millis > SLOW_FRAME_MS) {
                slow.append(String.format(Locale.ROOT, "    slow frame at %s: %.1f ms; family programs ready %d -> %d;"
                            + " garbage collection %d ms%n", at, millis, ready, terrain.familyPrograms().ready().size(),
                      gcMillis() - collected));
            }
            assertTrue(System.nanoTime() < deadline, "Family programs must finish");
        } while (terrain.familyPrograms().pending() > 0);
        report.append(String.format(Locale.ROOT, "%s: %d frames in %.1f s while family programs built%n", name, frames,
              (System.nanoTime() - start) / 1e9)).append(slow);
        return longest;
    }

    private static int difference(byte[] first, byte[] second) {
        assertEquals(first.length, second.length);
        int largest = 0;
        for (int i = 0; i < first.length; i++) {
            largest = Math.max(largest, Math.abs((first[i] & 255) - (second[i] & 255)));
        }
        return largest;
    }

    private static double difference(float[] first, float[] second) {
        assertEquals(first.length, second.length);
        double largest = 0;
        for (int i = 0; i < first.length; i++) { largest = Math.max(largest, Math.abs(first[i] - second[i])); }
        return largest;
    }

    private static int over(byte[] first, byte[] second) {
        int count = 0;
        for (int i = 0; i < first.length; i++) {
            if (Math.abs((first[i] & 255) - (second[i] & 255)) > 1) { count++; }
        }
        return count;
    }
}
