/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.logging.MMLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shader warm-up as the start screen runs it, into an empty program cache; then a game on Grassland 3 must start
 * no terrain link of its own. The grass theme's sample board covers the game's generic programs (family programs off
 * for the game); the board itself, asked for as the lobby or the last game ask for it, covers all of them, its family
 * programs and their blends too. {@code -Dmegamek.gpu.shaderWarmupSteps} chooses other sample boards for the first;
 * {@code none} prepares nothing, and it only reports the game's cold load beside a warmed one.
 */
@Tag("on-demand")
class GpuShaderWarmupSmokeTest {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderWarmupSmokeTest.class);
    private static final File GRASSLAND = new File("data/boards/AGoAC Maps/16x17 Grassland 3.board");
    /** {@code -Dmegamek.gpu.shaderWarmupMinutes}: how long the warm-up may take, for more steps than the default one. */
    private static final long WARMUP_NANOS = Long.getLong("megamek.gpu.shaderWarmupMinutes", 20) * 60 * 1_000_000_000L;
    /** How long a test that waits for the process to start, stop and start again may take. */
    private static final long PHASES_NANOS = 180_000_000_000L;
    private static final String[] PROPERTIES = { GpuProgramCache.DIRECTORY_PROPERTY, GpuShaderWarmup.PROPERTY,
          GpuShaderWarmup.STEPS_PROPERTY, GpuShaderWarmup.FREE_PROPERTY, GpuFamilyPrograms.PROPERTY };

    @Test
    void sampleBoardWarmsAGamesTerrain() throws Exception {
        String steps = System.getProperty(GpuShaderWarmup.STEPS_PROPERTY);
        boolean cold = "none".equals(steps);
        warmThenPlay(steps == null ? "game/grass" : steps, null, false, cold);
    }

    @Test
    void boardAskedForWarmsAllItsPrograms() throws Exception {
        warmThenPlay("none", BoardFile.loadSheet(GRASSLAND, false, true), true, false);
    }

    /** Combat's effects alone: a game's loading stage then takes every one of their programs from the cache. */
    @Test
    void combatEffectsLinkAhead() throws Exception {
        withWarmup("effects", cache -> new Menu() {
            @Override
            void frame(long now) {
                if (!GpuShaderWarmup.finished() || GpuShaderWarmup.busy()) {
                    assertTrue(now - started < WARMUP_NANOS, "The warm-up did not finish");
                    return;
                }
                int stored = GpuGlsl.programCache.stored.get();
                var attack = new GpuAttackEffects();
                var designators = new GpuDesignators();
                var fire = new GpuTerrainEffects();
                var splashes = new GpuWaterImpacts();
                var jets = new GpuJumpJets();
                try {
                    GpuGlsl.offscreen(() -> GpuBattleView.prepareEffects(attack, designators, fire, splashes, jets));
                    assertEquals(stored, GpuGlsl.programCache.stored.get(), "Effect programs the game linked itself");
                } finally {
                    attack.dispose();
                    designators.dispose();
                    fire.dispose();
                    splashes.dispose();
                    jets.dispose();
                }
                Gdx.app.exit();
            }
        }.run());
    }

    /**
     * A board drawn inside a menu (the lobby's map preview) ends the warm-up's process at once, nothing starts it while
     * boards keep drawing, and it goes on a moment after the last.
     */
    @Test
    void boardDrawnInAMenuStopsTheWarmup() throws Exception {
        withWarmup("game/grass", cache -> new Menu() {
            /** A link helper an earlier test left idling is no warm-up. */
            private final Instant began = Instant.now();
            private long phaseStarted;
            private int phase;

            @Override
            void frame(long now) {
                assertTrue(now - started < PHASES_NANOS, "Stuck in phase " + phase);
                if (phase == 0 && GpuShaderWarmup.busy()) {
                    GpuShaderWarmup.boardDrawn();
                    assertFalse(GpuShaderWarmup.busy(), "A board drew but the warm-up runs on");
                    phase = 1;
                    phaseStarted = now;
                } else if (phase == 1) {
                    // The lobby's preview drawing for three seconds: nothing may start between its frames, and the
                    // process the first one ended is gone.
                    assertFalse(GpuShaderWarmup.busy(), "The warm-up started while a board drew");
                    GpuShaderWarmup.boardDrawn();
                    if (now - phaseStarted > 1_000_000_000L) {
                        assertTrue(ProcessHandle.current().descendants().noneMatch(child -> child.isAlive()
                              && child.info().startInstant().map(began::isBefore).orElse(true)),
                              "The warm-up's process still runs");
                    }
                    if (now - phaseStarted > 3_000_000_000L) { phase = 2; }
                } else if (phase == 2 && GpuShaderWarmup.busy()) {
                    Gdx.app.exit();
                }
            }
        }.run());
    }

    /**
     * Short of memory as a menu shows, the warm-up doesn't start, past a hold either; with memory to spare it starts.
     * Short while it runs, its process ends within a second and nothing starts it while the shortage lasts, and it goes on
     * once there is memory to spare again. A reserve no machine has stands in for the shortage, the check off for the
     * memory to spare.
     */
    @Test
    void shortOfMemoryTheWarmupEndsAndWaits() throws Exception {
        withWarmup("game/grass", cache -> {
            System.setProperty(GpuShaderWarmup.FREE_PROPERTY, Long.toString(1L << 30));
            new Menu() {
                private long phaseStarted;
                private int phase;

                @Override
                void frame(long now) {
                    assertTrue(now - started < PHASES_NANOS, "Stuck in phase " + phase);
                    if (phase == 0) {
                        // The start found too little, and so did the hold's end.
                        assertFalse(GpuShaderWarmup.busy(), "The warm-up started, short of memory");
                        if (now - started > 12_000_000_000L) {
                            System.setProperty(GpuShaderWarmup.FREE_PROPERTY, "-1");
                            phase = 1;
                        }
                    } else if (phase == 1 && GpuShaderWarmup.busy()) {
                        System.setProperty(GpuShaderWarmup.FREE_PROPERTY, Long.toString(1L << 30));
                        phase = 2;
                        phaseStarted = now;
                    } else if (phase == 2) {
                        // Past the first check it has ended, and the holds' ends find too little.
                        if (now - phaseStarted > 2_000_000_000L) {
                            assertFalse(GpuShaderWarmup.busy(), "The warm-up ran on, short of memory");
                        }
                        if (now - phaseStarted > 15_000_000_000L) {
                            System.setProperty(GpuShaderWarmup.FREE_PROPERTY, "-1");
                            phase = 3;
                        }
                    } else if (phase == 3 && GpuShaderWarmup.busy()) {
                        Gdx.app.exit();
                    }
                }
            }.run();
        });
    }

    /**
     * Warms {@code steps} and {@code board}, then plays Grassland 3, with family programs or without, and asserts that
     * its terrain linked nothing itself unless {@code cold}.
     */
    private static void warmThenPlay(String steps, Board board, boolean families, boolean cold) throws Exception {
        withWarmup(steps, cache -> {
            System.clearProperty(GpuFamilyPrograms.PROPERTY);
            long[] seconds = new long[2];
            new Menu() {
                @Override
                void opened() {
                    if (board != null) { GpuShaderWarmup.prepare(board, false, null); }
                }

                @Override
                void frame(long now) {
                    if (GpuShaderWarmup.finished() && !GpuShaderWarmup.busy()) {
                        seconds[0] = (now - started) / 1_000_000_000L;
                        Gdx.app.exit();
                    } else {
                        assertTrue(now - started < WARMUP_NANOS, "The warm-up did not finish");
                    }
                }
            }.run();
            long entries;
            try (var files = Files.list(cache)) { entries = files.filter(file -> file.toString().endsWith(".bin")).count(); }
            assertTrue(cold || entries > 0, "The warm-up left no program in the cache");

            if (!families) { System.setProperty(GpuFamilyPrograms.PROPERTY, "false"); }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var fixture = GpuBoardFixture.create(BoardFile.loadSheet(GRASSLAND, false, true));
            try {
                new Lwjgl3Application(new GpuBattleView(fixture.source) {
                    private long started, shownAt, last, longest;
                    private int shown;

                    @Override
                    public void render() {
                        try {
                            long now = System.nanoTime();
                            if (started == 0) { started = now; }
                            super.render();
                            if (GpuBoardTestUi.loading(this)) {
                                shown = 0;
                                last = 0;
                                return;
                            }
                            // The shown board's first frames: a binary of the wrong kind would compile again here.
                            if (last != 0) { longest = Math.max(longest, now - last); }
                            last = now;
                            if (shown++ == 0) { shownAt = now; }
                            if (now - shownAt < 3_000_000_000L) { return; }
                            seconds[1] = (shownAt - started) / 1_000_000_000L;
                            GpuTerrain terrain = GpuTestSupport.field(this, "terrain");
                            GpuGlsl.Preparation links = GpuTestSupport.field(terrain, "terrainMaterialPreparation");
                            int asked = GpuTestSupport.field(terrain, "terrainPrograms");
                            GpuFamilyPrograms familyPrograms = GpuTestSupport.field(terrain, "familyPrograms");
                            String summary = String.format(Locale.ROOT, "WARMUP %s%s: warm-up %d s, %d programs; game "
                                  + "load %d s, %d generic and %d family programs, %d linked from source, longest shown "
                                  + "frame %d ms", steps, board == null ? "" : " + the board", seconds[0], entries,
                                  seconds[1], asked, familyPrograms.programs(), links.freshLinks(), longest / 1_000_000);
                            LOGGER.info(summary);
                            System.out.println(summary);
                            if (!cold) { assertEquals(0, links.freshLinks(), "Terrain programs the game linked itself"); }
                            Gdx.app.exit();
                        } catch (Throwable error) {
                            failure.set(error);
                            Gdx.app.exit();
                        }
                    }
                }, window());
            } finally {
                SwingUtilities.invokeAndWait(fixture.view::close);
            }
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
        });
    }

    /** A test's body, given its program cache. */
    private interface Body {
        void run(Path cache) throws Exception;
    }

    /**
     * Runs {@code body} with the warm-up on, preparing {@code steps}, into an empty program cache of its own, whatever
     * memory the machine has free (its check off); then ends its process and restores the properties.
     */
    private static void withWarmup(String steps, Body body) throws Exception {
        Path cache = Files.createTempDirectory("shader-warmup-cache");
        String[] saved = new String[PROPERTIES.length];
        for (int i = 0; i < PROPERTIES.length; i++) { saved[i] = System.getProperty(PROPERTIES[i]); }
        System.setProperty(GpuProgramCache.DIRECTORY_PROPERTY, cache.toString());
        System.setProperty(GpuShaderWarmup.PROPERTY, "true");
        System.setProperty(GpuShaderWarmup.STEPS_PROPERTY, steps);
        System.setProperty(GpuShaderWarmup.FREE_PROPERTY, "-1");
        try {
            body.run(cache);
        } finally {
            // Its process ends with this test's cache, which no later test's window may warm into.
            GpuShaderWarmup.close();
            for (int i = 0; i < PROPERTIES.length; i++) {
                if (saved[i] == null) { System.clearProperty(PROPERTIES[i]); } else { System.setProperty(PROPERTIES[i], saved[i]); }
            }
            delete(cache);
        }
    }

    /** A window showing a menu, as GpuHost tells the warm-up; {@link #frame(long)} each frame after the warm-up's. */
    private abstract static class Menu extends ApplicationAdapter {
        long started;
        private Throwable failure;

        /** After the warm-up opens, before the menu shows. */
        void opened() { }

        abstract void frame(long now) throws Exception;

        @Override
        public void create() {
            started = System.nanoTime();
            GpuShaderWarmup.open();
            opened();
            GpuShaderWarmup.showing(false);
        }

        @Override
        public void render() {
            try {
                GpuShaderWarmup.frame();
                frame(System.nanoTime());
            } catch (Throwable error) {
                failure = error;
                Gdx.app.exit();
            }
        }

        /** Shows the window until a frame exits it; what failed fails the test. */
        void run() {
            new Lwjgl3Application(this, window());
            if (failure != null) { throw new AssertionError(failure); }
        }
    }

    private static Lwjgl3ApplicationConfiguration window() {
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        return config;
    }

    /** Retried a while: a warm-up process just ended may still hold one of its files. */
    private static void delete(Path directory) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
                return;
            } catch (IOException held) {
                if (attempt == 20) { throw held; }
                Thread.sleep(250);
            }
        }
    }
}
