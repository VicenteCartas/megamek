/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.board.BoardFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Program binaries on the terrain calibration board: the first load compiles and keeps every program, a second loads
 * them all and draws the same picture, and two damaged entries compile again. Times and sizes go to
 * build/gpu-board-review/program-cache/report.txt.
 */
@Tag("on-demand")
class GpuProgramCacheSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "program-cache");
    private static final File BOARD = new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");
    @TempDir Path directory;

    @Test
    void warmLoadDrawsTheSameAndDamagedEntriesCompileAgain() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        BoardScene scene = scene();
        Path entries = directory.resolve("cache");
        StringBuilder report = new StringBuilder();
        AtomicBoolean unsupported = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuProgramCache previous = GpuGlsl.programCache;
                // A load compiles only a share of the family programs (GpuFamilyPrograms.FRESH): the generic programs
                // alone show what the cache keeps.
                String family = System.getProperty(GpuFamilyPrograms.PROPERTY);
                System.setProperty(GpuFamilyPrograms.PROPERTY, "false");
                try {
                    var cache = GpuProgramCache.open(entries);
                    unsupported.set(cache == null);
                    if (cache == null) { return; }
                    GpuGlsl.programCache = cache;
                    report.append(String.format(Locale.ROOT, "%s, %s; %d processors%n",
                          Gdx.gl.glGetString(GL20.GL_RENDERER), Gdx.gl.glGetString(GL20.GL_VERSION),
                          Runtime.getRuntime().availableProcessors()));
                    Pass first = load(scene, "first");
                    int stored = cache.stored.get();
                    report.append(String.format(Locale.ROOT, "First load, nothing kept: %d ms; kept %d programs, %s%n",
                          first.millis(), stored, sizes(entries)));
                    assertTrue(stored > 0, "The first load keeps its programs");

                    Instant warm = Instant.now();
                    Pass second = load(scene, "second");
                    report.append(String.format(Locale.ROOT, "Second load, all kept: %d ms; loaded %d programs%n",
                          second.millis(), cache.loaded.get()));
                    assertEquals(stored, cache.stored.get(), "The second load compiles nothing");
                    assertEquals(0, cache.discarded.get());
                    assertTrue(difference(first.pixels(), second.pixels()) <= 1, "Loaded programs draw the same");

                    // Two entries that load used: one fails its checksum, the driver rejects the other.
                    List<Path> used = used(entries, warm);
                    String flipped = key(used.get(0)), rejected = key(used.get(1));
                    byte[] bytes = Files.readAllBytes(used.get(0));
                    bytes[bytes.length - 1] ^= 1;
                    Files.write(used.get(0), bytes);
                    assertTrue(cache.write(rejected, cache.read(rejected).format(), new byte[64]));
                    Pass repaired = load(scene, "repaired");
                    report.append(String.format(Locale.ROOT, "After damaging two entries: %d ms; discarded %d, kept %d%n",
                          repaired.millis(), cache.discarded.get(), cache.stored.get() - stored));
                    assertEquals(2, cache.discarded.get(), "Both damaged entries are discarded");
                    assertEquals(stored + 2, cache.stored.get(), "Both programs compile again and are kept");
                    assertNotNull(cache.read(flipped));
                    assertNotNull(cache.read(rejected));
                    assertTrue(difference(first.pixels(), repaired.pixels()) <= 1, "Recompiled programs draw the same");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuGlsl.programCache = previous;
                    if (family == null) { System.clearProperty(GpuFamilyPrograms.PROPERTY); }
                    else { System.setProperty(GpuFamilyPrograms.PROPERTY, family); }
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Program binary cache", failure.get()); }
        assumeFalse(unsupported.get(), "The driver offers no program binary format");
        System.out.print(report);
        Files.writeString(new File(OUTPUT, "report.txt").toPath(), report);
    }

    private record Pass(long millis, byte[] pixels) { }

    /** A terrain and review frame created, loaded, settled and drawn once, as a launch does, then released. */
    private static Pass load(BoardScene scene, String name) throws InterruptedException {
        long start = System.nanoTime();
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        try {
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(true);
            camera.fit(scene);
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(.5f, List.of());
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (terrain.refine(camera.camera) | terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
                Thread.sleep(5);
            }
            frame.render(terrain, camera, scene);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                  Gdx.graphics.getBackBufferHeight());
            try {
                ByteBuffer pixels = image.getPixels();
                byte[] result = new byte[pixels.remaining()];
                pixels.get(result);
                GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
                return new Pass(millis, result);
            } finally {
                image.dispose();
            }
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /** The board captured as a game loads it, artwork included. */
    private static BoardScene scene() throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(BOARD, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    /** Entries used since the instant, smallest first: quick to compile again. */
    private static List<Path> used(Path entries, Instant since) throws IOException {
        try (Stream<Path> files = Files.list(entries)) {
            return files.filter(file -> file.toString().endsWith(".bin")).filter(file -> {
                try { return !Files.getLastModifiedTime(file).toInstant().isBefore(since); }
                catch (IOException failure) { return false; }
            }).sorted(Comparator.comparingLong(file -> file.toFile().length())).toList();
        }
    }

    private static String key(Path entry) {
        return entry.getFileName().toString().replaceFirst("\\.bin$", "");
    }

    private static String sizes(Path entries) throws IOException {
        try (Stream<Path> files = Files.list(entries)) {
            long[] lengths = files.filter(file -> file.toString().endsWith(".bin")).mapToLong(file -> file.toFile().length())
                  .sorted().toArray();
            long total = 0;
            for (long length : lengths) { total += length; }
            return String.format(Locale.ROOT, "%d entries, %.1f MB, largest %.1f MB", lengths.length, total / 1048576.0,
                  lengths.length == 0 ? 0 : lengths[lengths.length - 1] / 1048576.0);
        }
    }

    /** The largest difference of any colour channel, in 8-bit steps. */
    private static int difference(byte[] first, byte[] second) {
        int largest = 0;
        for (int i = 0; i < first.length; i++) {
            if (i % 4 != 3) { largest = Math.max(largest, Math.abs((first[i] & 255) - (second[i] & 255))); }
        }
        return largest;
    }
}
