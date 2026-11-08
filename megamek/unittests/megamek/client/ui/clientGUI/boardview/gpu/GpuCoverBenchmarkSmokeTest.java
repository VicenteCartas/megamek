/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ground cover cost through the actual client painters, on boards covered entirely by one kind of plant, on
 * MesaCity 1 (plantations) and on Elevated Highway (grass among cliffs): load time and installed detail levels,
 * frames until every visible cover hex shows its plants, settled frame cost with and without the plants, a pan and
 * a zoom cycle, tilt sweeps between the near-horizontal and overhead views with and without the plants, and a dive
 * from an overview into the close view that counts the frames its plants are missing. The tactical overlay is
 * driven like the battle view does, with a movement envelope around the focus. Measured, not asserted.
 */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuCoverBenchmarkSmokeTest {
    private static final String MESA_CITY = "data/boards/unofficial/SimonLandmine/96x102/96x102 MesaCity1.board";
    private static final String ELEVATED_HIGHWAY = "data/boards/unofficial/Vamp/Elevated Highway.board";
    private static final int WIDTH = Integer.getInteger("megamek.gpu.coverWidth", 1600);
    private static final int HEIGHT = Integer.getInteger("megamek.gpu.coverHeight", 900);
    private static final int SIZE = Integer.getInteger("megamek.gpu.coverSize", 50);
    /** Projected hex widths of the two measured views: a low overview like the reported screenshot, and a close view. */
    private static final float[] VIEW_PIXELS = { 110, 320 };
    private static final String[] VIEW_NAMES = { "low", "close" };

    enum Kind { FIELDS, GRASS, MARSH, WOODS }

    @ParameterizedTest
    @ValueSource(strings = { "fields", "grass", "marsh", "woods", "mesacity", "elevated" })
    void profilesGroundCover(String board) throws Exception {
        String boards = System.getProperty("megamek.gpu.coverBoards", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(boards.isEmpty() || List.of(boards.split(",")).contains(board));
        Kind kind = board.equals("grass") || board.equals("elevated") ? Kind.GRASS : board.equals("marsh") ? Kind.MARSH
              : board.equals("woods") ? Kind.WOODS : Kind.FIELDS;
        Board loaded;
        Coords focus;
        if (board.equals("mesacity")) {
            loaded = BoardFile.loadSheet(new File(MESA_CITY), false, true);
            focus = new Coords(36, 24);
        } else if (board.equals("elevated")) {
            // Grass lowlands three levels below a plateau carrying the highway: the reported grass pop-in.
            loaded = BoardFile.loadSheet(new File(ELEVATED_HIGHWAY), false, true);
            focus = new Coords(24, 28);
        } else {
            loaded = uniform(kind);
            focus = new Coords(SIZE / 2, SIZE / 2 + 5);
        }
        profile(loaded, kind, focus, board);
    }

    /** One kind of cover on every hex, with a raised block whose banks split rows and a road through the middle. */
    private static Board uniform(Kind kind) {
        Hex[] hexes = new Hex[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                Hex hex = new Hex(x >= 30 && x < 40 && y >= 6 && y < 18 ? y < 12 ? 2 : 1 : 0);
                switch (kind) {
                    case FIELDS -> { hex.setTheme("desert"); hex.addTerrain(new Terrain(Terrains.FIELDS, 3)); }
                    case MARSH -> hex.addTerrain(new Terrain(Terrains.SWAMP, 1));
                    case WOODS -> {
                        hex.addTerrain(new Terrain(Terrains.WOODS, 1 + (x * 7 + y * 3) % 3));
                        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                    }
                    case GRASS -> { }
                }
                if (x == 20) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9)); }
                hexes[y * SIZE + x] = hex;
            }
        }
        return new Board(SIZE, SIZE, hexes);
    }

    private static void profile(Board board, Kind kind, Coords focus, String name) throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "cover-benchmark/" + name);
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var report = new StringBuilder();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var timer = GpuBoardSource.class.getDeclaredField("timer");
                    timer.setAccessible(true);
                    ((Timer) timer.get(fixture.source)).stop();
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(WIDTH, HEIGHT);
            config.useVsync(false);
            config.setForegroundFPS(0);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var terrain = new GpuTerrain();
                    var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    var profiler = new GLProfiler(Gdx.graphics);
                    try (var timings = new GpuStageTimings()) {
                        report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                              .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                        // The battle view re-drapes its tactical markings whenever terrain detail changes; a
                        // selected unit's movement envelope is the usual marking around the view.
                        BoardScene scene = withEnvelope(fixture.source.takeFrame().scene(), focus);
                        var tactical = new GpuTactical(terrain::tacticalSurface);
                        var cover = new Cover(terrain, kind, scene);
                        report.append(String.format(Locale.ROOT, "%s: %dx%d board, %d cover hexes, %dx%d window%n",
                              name, scene.width(), scene.height(), cover.hexes.size(), WIDTH, HEIGHT));
                        var camera = view(focus, VIEW_PIXELS[0]);
                        long start = System.nanoTime();
                        terrain.update(scene, camera.camera);
                        settle(terrain, camera);
                        report.append(String.format(Locale.ROOT, "load: terrain open and settle %.0f ms%n",
                              (System.nanoTime() - start) / 1e6));
                        for (int v = 0; v < VIEW_PIXELS.length; v++) {
                            camera = view(focus, VIEW_PIXELS[v]);
                            settle(terrain, camera);
                            String view = VIEW_NAMES[v];
                            report.append("\n[").append(view).append(" view, ").append((int) VIEW_PIXELS[v]).append(" px/hex]\n");
                            report.append(detail(terrain, camera));
                            cold(terrain, frame, camera, scene, cover, report, output, view);
                            report.append(cover.summary(profiler, terrain, frame, camera, scene));
                            steady(terrain, frame, camera, scene, timings, report, view + ": settled");
                            cover.hide();
                            steady(terrain, frame, camera, scene, timings, report, view + ": settled without cover");
                            cover.show();
                            draw(terrain, frame, camera, scene);
                            report.append("pan: ").append(pan(terrain, frame, camera, scene, timings, cover, output, view));
                            timings.appendReport(report, view + ": pan frames");
                        }
                        report.append("\nzoom: ").append(zoom(terrain, frame, focus, scene, cover));
                        for (int v = 0; v < VIEW_PIXELS.length; v++) {
                            for (float rate : new float[] { .5f, 2 }) {
                                report.append(String.format(Locale.ROOT, "\norbit %s view %.1f deg/frame: ", VIEW_NAMES[v], rate))
                                      .append(orbit(terrain, tactical, frame, focus, scene, cover, timings, output, VIEW_PIXELS[v], rate));
                            }
                        }
                        report.append("\ndive: ").append(dive(terrain, tactical, frame, focus, scene, cover, output));
                        tactical.dispose();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        try {
                            Files.writeString(new File(output, "cover-benchmark.txt").toPath(), report);
                            terrain.dispose();
                            frame.dispose();
                        } catch (Throwable error) {
                            if (failure.get() == null) { failure.set(error); } else { failure.get().addSuppressed(error); }
                        }
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        System.out.print(report);
        if (failure.get() != null) { throw new AssertionError("Cover benchmark " + name, failure.get()); }
    }

    /** A low perspective view north over the cover, as in the reported screenshot. */
    private static BoardCamera view(Coords focus, float hexPixels) {
        var camera = new BoardCamera();
        camera.resize(WIDTH, HEIGHT);
        camera.setIsometric(false);
        camera.setPerspective(true);
        camera.tilt(Float.parseFloat(System.getProperty("megamek.gpu.coverTilt", "68")));
        camera.camera.zoom = BoardGeometry.width() / hexPixels;
        camera.center(BoardGeometry.center(focus, 0));
        return camera;
    }

    /** One frame in the battle view's order: publish, hand over finished terrain detail, draw. */
    private static void draw(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        terrain.update(scene, camera.camera);
        terrain.refine(camera.camera);
        frame.render(terrain, camera, scene);
    }

    /** Terrain detail for this view, including its plants, without drawing. A large board can take minutes. */
    private static void settle(GpuTerrain terrain, BoardCamera camera) throws InterruptedException {
        long deadline = System.nanoTime() + 900_000_000_000L;
        while (terrain.refine(camera.camera) || terrain.busy()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Terrain did not settle"); }
            Thread.sleep(2);
        }
    }

    /** The first frames of a settled view: how long until every visible cover hex shows its plants. */
    private static void cold(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, Cover cover,
          StringBuilder report, File output, String view) throws Exception {
        report.append("cold: frame, cumulative ms, frame ms, complete/visible cover hexes\n");
        long start = System.nanoTime(), limit = Long.getLong("megamek.gpu.coverColdSeconds", 90) * 1_000_000_000L;
        int frames = 0;
        int[] coverage;
        do {
            long t = System.nanoTime();
            draw(terrain, frame, camera, scene);
            Gdx.gl.glFinish();
            frames++;
            coverage = cover.coverage(camera);
            if (frames <= 3 || Integer.bitCount(frames) == 1 || coverage[0] == coverage[1]) {
                report.append(String.format(Locale.ROOT, "  %d, %.0f, %.1f, %d/%d%n", frames,
                      (System.nanoTime() - start) / 1e6, (System.nanoTime() - t) / 1e6, coverage[0], coverage[1]));
            }
            if (frames == 1 || frames == 16) { GpuReviewFrame.save(new File(output, view + "-cold-" + frames + ".png")); }
        } while (coverage[0] < coverage[1] && System.nanoTime() - start < limit);
        report.append(String.format(Locale.ROOT, "  %s after %d frames, %.0f ms%n",
              coverage[0] < coverage[1] ? "stopped" : "complete", frames, (System.nanoTime() - start) / 1e6));
        GpuReviewFrame.save(new File(output, view + "-settled.png"));
    }

    private static void steady(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          GpuStageTimings timings, StringBuilder report, String name) {
        for (int i = 0; i < 20; i++) {
            timings.beginFrame(false);
            timings.stage("frame");
            draw(terrain, frame, camera, scene);
            timings.stage(null);
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
        }
        for (int i = 0; i < 90; i++) {
            timings.beginFrame();
            timings.stage("frame");
            draw(terrain, frame, camera, scene);
            timings.stage(null);
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
            Gdx.gl.glFinish();
        }
        timings.appendReport(report, name);
        // The same frame stage by stage, to show where its time goes.
        for (int i = 0; i < 60; i++) {
            terrain.update(scene, camera.camera);
            terrain.refine(camera.camera);
            timings.beginFrame(i >= 10);
            frame.render(terrain, camera, scene, timings);
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
            Gdx.gl.glFinish();
        }
        timings.appendReport(report, name + " by stage");
    }

    /** Pan east then back at a quarter hex per frame, as holding an arrow key does. */
    private static String pan(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          GpuStageTimings timings, Cover cover, File output, String view) throws Exception {
        int incomplete = 0, worst = 100;
        long uploads = cover.uploads(), treeUploads = terrain.treeInstanceUploads();
        double[] times = new double[240];
        for (int step = 0; step < times.length; step++) {
            float direction = step < times.length / 2 ? 1 : -1;
            camera.pan(direction * BoardGeometry.width() * .25f / camera.camera.zoom, 0);
            long t = System.nanoTime();
            timings.beginFrame();
            timings.stage("pan frame");
            draw(terrain, frame, camera, scene);
            timings.stage(null);
            times[step] = (System.nanoTime() - t) / 1e6;
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
            Gdx.gl.glFinish();
            int[] coverage = cover.coverage(camera);
            if (coverage[0] < coverage[1]) {
                incomplete++;
                worst = Math.min(worst, 100 * coverage[0] / coverage[1]);
            }
            if (step == times.length / 4) { GpuReviewFrame.save(new File(output, view + "-pan.png")); }
        }
        Arrays.sort(times);
        return String.format(Locale.ROOT, "%d frames, CPU submit median %.1f ms, p95 %.1f ms, max %.1f ms;"
                    + " %d frames missing cover, lowest complete share %d%%, cover uploads %d, tree uploads %d%n", times.length,
              times[times.length / 2], times[times.length * 95 / 100], times[times.length - 1], incomplete, worst,
              cover.uploads() - uploads, terrain.treeInstanceUploads() - treeUploads);
    }

    /** Zoom from the low view to the close one and back, with terrain detail changing on the way. */
    private static String zoom(GpuTerrain terrain, GpuReviewFrame frame, Coords focus, BoardScene scene, Cover cover)
          throws Exception {
        int incomplete = 0, worst = 100, busy = 0;
        long uploads = cover.uploads(), treeUploads = terrain.treeInstanceUploads();
        double[] times = new double[180];
        var camera = view(focus, VIEW_PIXELS[0]);
        for (int step = 0; step < times.length; step++) {
            float t = (step < 90 ? step : 179 - step) / 89f;
            camera.camera.zoom = BoardGeometry.width() / (float) (VIEW_PIXELS[0] * Math.pow(VIEW_PIXELS[1] / VIEW_PIXELS[0], t));
            camera.center(BoardGeometry.center(focus, 0));
            long start = System.nanoTime();
            draw(terrain, frame, camera, scene);
            Gdx.gl.glFinish();
            times[step] = (System.nanoTime() - start) / 1e6;
            if (terrain.busy()) { busy++; }
            int[] coverage = cover.coverage(camera);
            if (coverage[0] < coverage[1]) {
                incomplete++;
                worst = Math.min(worst, 100 * coverage[0] / coverage[1]);
            }
        }
        Arrays.sort(times);
        return String.format(Locale.ROOT, "%d frames, frame median %.1f ms, p95 %.1f ms, max %.1f ms; %d frames with terrain"
                    + " detail pending, %d frames missing cover, lowest complete share %d%%, cover uploads %d, tree uploads %d%n",
              times.length, times[times.length / 2], times[times.length * 95 / 100], times[times.length - 1], busy,
              incomplete, worst, cover.uploads() - uploads, terrain.treeInstanceUploads() - treeUploads);
    }

    /**
     * Tilt between the near-horizontal view and overhead at a steady rate, as dragging the view up and down does,
     * once with the plants and once without them, so their cost shows per viewing angle. Each frame with plants is
     * split into publish, terrain detail handoff, overlay and drawing, so a slow frame names its cause.
     */
    private static String orbit(GpuTerrain terrain, GpuTactical tactical, GpuReviewFrame frame, Coords focus, BoardScene scene,
          Cover cover, GpuStageTimings timings, File output, float hexPixels, float rate) throws Exception {
        float high = BoardCamera.MAX_TILT, low = 10;
        int half = Math.round((high - low) / rate), frames = 2 * half;
        double[][] passes = new double[2][frames];
        double[] update = new double[frames], refine = new double[frames], render = new double[frames], overlay = new double[frames];
        double[] shadows = new double[frames];
        int[] replaced = new int[frames], missing = new int[frames], visible = new int[frames];
        float[] tilt = new float[frames];
        int incomplete = 0, worst = 100, busy = 0, replacements = 0;
        long uploads = 0, treeUploads = 0;
        var slow = new StringBuilder();
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) { cover.hide(); }
            var camera = view(focus, hexPixels);
            camera.tilt(high);
            settle(terrain, camera);
            // Warm up with the timer queries in use: their first driver use can otherwise stall a measured frame.
            // Finish each warm-up frame too, or the first measured frame waits for the whole queued backlog.
            for (int i = 0; i < 20; i++) {
                terrain.update(scene, camera.camera);
                terrain.refine(camera.camera);
                tactical.update(scene, true, null);
                timings.beginFrame(false);
                frame.render(terrain, camera, scene, timings);
                Gdx.gl.glFinish();
                org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
            }
            if (pass == 0) { uploads = cover.uploads(); treeUploads = terrain.treeInstanceUploads(); }
            for (int step = 0; step < frames; step++) {
                camera.tilt(step < half ? -rate : rate);
                tilt[step] = camera.tilt();
                long t0 = System.nanoTime();
                terrain.update(scene, camera.camera);
                long t1 = System.nanoTime();
                boolean changed = terrain.refine(camera.camera);
                long t2 = System.nanoTime();
                tactical.update(scene, changed, null);
                long t2b = System.nanoTime();
                // The frame's own shadow pass then finds nothing to do: this times detail selection and shadows alone.
                terrain.renderShadows(camera.camera, List.of());
                Gdx.gl.glFinish();
                long t2c = System.nanoTime();
                if (pass == 0) { timings.beginFrame(); frame.render(terrain, camera, scene, timings); }
                else { frame.render(terrain, camera, scene); }
                Gdx.gl.glFinish();
                long t3 = System.nanoTime();
                org.lwjgl.glfw.GLFW.glfwSwapBuffers(org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
                passes[pass][step] = (t3 - t0) / 1e6;
                if (pass == 1) { continue; }
                update[step] = (t1 - t0) / 1e6; refine[step] = (t2 - t1) / 1e6; overlay[step] = (t2b - t2) / 1e6;
                shadows[step] = (t2c - t2b) / 1e6;
                render[step] = (t3 - t2c) / 1e6;
                if (changed) { replaced[step] = 1; replacements++; }
                if (terrain.busy()) { busy++; }
                int[] coverage = cover.coverage(camera);
                visible[step] = coverage[1];
                missing[step] = coverage[1] - coverage[0];
                if (coverage[0] < coverage[1]) {
                    incomplete++;
                    worst = Math.min(worst, 100 * coverage[0] / coverage[1]);
                }
                if (step % (frames / 4) == 0 || step == frames - 1) {
                    GpuReviewFrame.save(new File(output, String.format(Locale.ROOT, "orbit-%.0f-%.1f-%03d.png", hexPixels, rate, step)));
                }
            }
            if (pass == 0) { uploads = cover.uploads() - uploads; treeUploads = terrain.treeInstanceUploads() - treeUploads; }
            if (pass == 1) { cover.show(); }
        }
        slow.append("frame ms by tilt band, median with plants / without plants:");
        for (int band = 80; band > 10; band -= 10) {
            var with = new java.util.ArrayList<Double>();
            var without = new java.util.ArrayList<Double>();
            for (int step = 0; step < frames; step++) {
                if (tilt[step] > band - 10 && tilt[step] <= band) { with.add(passes[0][step]); without.add(passes[1][step]); }
            }
            if (with.isEmpty()) { continue; }
            with.sort(Double::compare);
            without.sort(Double::compare);
            slow.append(String.format(Locale.ROOT, " %d-%d: %.1f / %.1f;", band - 10, band, with.get(with.size() / 2),
                  without.get(without.size() / 2)));
        }
        slow.append('\n');
        slow.append(detail(terrain, view(focus, hexPixels)));
        timings.appendReport(slow, String.format(Locale.ROOT, "orbit %.0f px %.1f deg/frame frames", hexPixels, rate));
        Integer[] order = new Integer[frames];
        for (int i = 0; i < frames; i++) { order[i] = i; }
        double[] total = passes[0];
        Arrays.sort(order, (a, b) -> Double.compare(total[b], total[a]));
        slow.append("slowest frames: frame, tilt, total ms = publish + detail + overlay + shadows + draw, detail replaced,"
              + " cover missing/visible\n");
        for (int i = 0; i < Math.min(8, frames); i++) {
            int f = order[i];
            slow.append(String.format(Locale.ROOT, "  %d, %.0f deg, %.1f = %.1f + %.1f + %.1f + %.1f + %.1f, %s, %d/%d%n", f,
                  tilt[f], total[f], update[f], refine[f], overlay[f], shadows[f], render[f], replaced[f] == 1 ? "yes" : "no",
                  missing[f], visible[f]));
        }
        double[] sorted = total.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%d frames, frame median %.1f ms, p95 %.1f ms, max %.1f ms; %d chunk detail"
                    + " replacements, %d frames with terrain detail pending, %d frames missing cover, lowest complete share"
                    + " %d%%, cover uploads %d, tree uploads %d%n%s", frames, sorted[frames / 2], sorted[frames * 95 / 100],
              sorted[frames - 1], replacements, busy, incomplete, worst, uploads, treeUploads, slow);
    }

    /**
     * Arrive at the close view from an overview in ten wheel steps, as a player does, then keep drawing: how long the
     * plants of the hexes now in view take to appear, and how much of the view lacks them meanwhile.
     */
    private static String dive(GpuTerrain terrain, GpuTactical tactical, GpuReviewFrame frame, Coords focus, BoardScene scene,
          Cover cover, File output) throws Exception {
        var camera = view(focus, 36);
        settle(terrain, camera);
        for (int i = 0; i < 5; i++) { draw(terrain, frame, camera, scene); tactical.update(scene, true, null); Gdx.gl.glFinish(); }
        long start = System.nanoTime(), uploads = cover.uploads();
        int frames = 0, incomplete = 0, worst = 100, replacements = 0, firstComplete = -1;
        double firstCompleteMs = -1, maxFrame = 0, maxOverlay = 0, maxDetail = 0;
        var trace = new StringBuilder("  frame, cumulative ms, frame ms (detail + overlay + draw), detail replaced,"
              + " complete/visible cover hexes\n");
        while (frames < 600) {
            if (frames < 10) {
                camera.camera.zoom = BoardGeometry.width() / (float) (36 * Math.pow(VIEW_PIXELS[1] / 36, (frames + 1) / 10f));
                camera.center(BoardGeometry.center(focus, 0));
            }
            long t = System.nanoTime();
            terrain.update(scene, camera.camera);
            boolean changed = terrain.refine(camera.camera);
            if (changed) { replacements++; }
            long t1 = System.nanoTime();
            tactical.update(scene, changed, null);
            long t2 = System.nanoTime();
            frame.render(terrain, camera, scene);
            Gdx.gl.glFinish();
            long t3 = System.nanoTime();
            double ms = (t3 - t) / 1e6, detail = (t1 - t) / 1e6, overlay = (t2 - t1) / 1e6;
            maxFrame = Math.max(maxFrame, ms);
            maxOverlay = Math.max(maxOverlay, overlay);
            maxDetail = Math.max(maxDetail, detail);
            frames++;
            int[] coverage = cover.coverage(camera);
            boolean complete = coverage[0] == coverage[1];
            if (!complete) {
                incomplete++;
                worst = Math.min(worst, 100 * coverage[0] / coverage[1]);
            }
            if (frames <= 12 || frames % 20 == 0 || changed || complete && firstComplete < 0) {
                trace.append(String.format(Locale.ROOT, "  %d, %.0f, %.1f (%.1f + %.1f + %.1f), %s, %d/%d%n", frames,
                      (System.nanoTime() - start) / 1e6, ms, detail, overlay, (t3 - t2) / 1e6, changed ? "yes" : "no",
                      coverage[0], coverage[1]));
            }
            if (frames == 10 || frames == 30 || frames == 60 || frames == 120) {
                GpuReviewFrame.save(new File(output, "dive-" + frames + ".png"));
            }
            if (complete && frames >= 10 && firstComplete < 0) {
                firstComplete = frames;
                firstCompleteMs = (System.nanoTime() - start) / 1e6;
                GpuReviewFrame.save(new File(output, "dive-complete.png"));
            }
            if (firstComplete >= 0 && !terrain.busy() && frames >= firstComplete + 30) { break; }
        }
        return String.format(Locale.ROOT, "%d frames, plants complete after %s (%.0f ms), %d frames missing cover,"
                    + " lowest complete share %d%%, %d chunk detail replacements, worst frame %.1f ms (detail handoff %.1f,"
                    + " overlay %.1f), cover uploads %d%n%s%s",
              frames, firstComplete < 0 ? "never" : firstComplete + " frames", firstCompleteMs, incomplete, worst,
              replacements, maxFrame, maxDetail, maxOverlay, cover.uploads() - uploads, trace, detail(terrain, camera));
    }

    /**
     * The scene with a selected unit's movement envelope: hex-border markings on the hexes within ten of the focus,
     * built like the impassable-hex markings, so the tactical overlay has the usual draping work on detail changes.
     */
    private static BoardScene withEnvelope(BoardScene scene, Coords focus) {
        List<BoardTactical.Fill> fills = new ArrayList<>();
        float width = BoardGeometry.TILE_WIDTH, height = BoardGeometry.TILE_HEIGHT;
        for (BoardScene.Tile tile : scene.tiles()) {
            Coords coords = tile.coords();
            if (coords.distance(focus) > 10) { continue; }
            float x = coords.getX() * width * .75f;
            float y = (coords.getY() + (coords.getX() & 1) * .5f) * height;
            var anchor = new BoardTactical.Point(x + width / 2, y + height / 2);
            var outline = new BoardTactical.Contour(List.of(new BoardTactical.Point(x + width * .25f, y),
                  new BoardTactical.Point(x + width * .75f, y), new BoardTactical.Point(x + width, y + height / 2),
                  new BoardTactical.Point(x + width * .75f, y + height), new BoardTactical.Point(x + width * .25f, y + height),
                  new BoardTactical.Point(x, y + height / 2)));
            fills.add(new BoardTactical.Fill(List.of(outline), java.awt.geom.Path2D.WIND_NON_ZERO, 0xFF40C0FF,
                  BoardTactical.Playback.LIVE, new BoardTactical.HexBorder(anchor, 1, 1.8f, 1), anchor));
        }
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(),
              scene.rangeBorders(), scene.markers(), new BoardTactical(List.copyOf(fills), List.of()), scene.rangeLabels(),
              scene.fieldOfView());
    }

    /** Installed terrain detail levels, all chunks and the ones in this view, to show what a sweep can change. */
    private static String detail(GpuTerrain terrain, BoardCamera camera) throws Exception {
        int[] counts = new int[TerrainLod.values().length], visible = new int[counts.length];
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            var lod = (TerrainLod) GpuTestSupport.field(chunk, "lod");
            counts[lod.ordinal()]++;
            if (camera.camera.frustum.boundsInFrustum((com.badlogic.gdx.math.collision.BoundingBox) GpuTestSupport.field(chunk, "bounds"))) {
                visible[lod.ordinal()]++;
            }
        }
        var text = new StringBuilder("detail: ");
        for (TerrainLod lod : TerrainLod.values()) {
            text.append(String.format(Locale.ROOT, "%s %d (%d in view) ", lod, counts[lod.ordinal()], visible[lod.ordinal()]));
        }
        return text.append('\n').toString();
    }

    /** The cover hexes of one kind and, through the renderers' state, whether each visible one is drawn. */
    private static final class Cover {
        final GpuTerrain terrain;
        final Kind kind;
        final BoardScene scene;
        final List<BoardScene.Tile> hexes = new ArrayList<>();
        final GpuGroundCover grass;
        final GpuBiomeVegetation plants;
        /** Each chunk's trees while the woods are hidden. */
        final List<Map<?, ?>> trees = new ArrayList<>();

        Cover(GpuTerrain terrain, Kind kind, BoardScene scene) throws Exception {
            this.terrain = terrain;
            this.kind = kind;
            this.scene = scene;
            grass = (GpuGroundCover) GpuTestSupport.field(terrain, "groundCover");
            plants = (GpuBiomeVegetation) GpuTestSupport.field(terrain, "biomeVegetation");
            for (var tile : scene.tiles()) {
                boolean covered = switch (kind) {
                    case FIELDS -> BoardBiome.plantKind(scene, tile) == BoardScene.Biome.FIELD;
                    case MARSH -> BoardBiome.plantKind(scene, tile) == BoardScene.Biome.MARSH;
                    case GRASS -> BoardSurfaceBlend.natural(tile) && BoardBiome.kind(tile) == BoardScene.Biome.NONE
                          && tile.surface() == BoardScene.Surface.GRASS;
                    // Trees are chunk props gathered per pass by GpuTreeInstances; count their uploads and cost only.
                    case WOODS -> tile.features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE);
                };
                if (covered) { hexes.add(tile); }
            }
        }

        /**
         * Visible cover hexes whose installed plants are in a drawn batch, and all visible cover hexes. Grass fades
         * in from 120 projected pixels per hex; hexes smaller than that show none by design and are not counted.
         */
        int[] coverage(BoardCamera camera) throws Exception {
            Set<Object> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object batch : batches(renderer())) { drawn.addAll((List<?>) GpuTestSupport.field(batch, "current")); }
            int complete = 0, visible = 0;
            for (var tile : hexes) {
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                if (!camera.camera.frustum.sphereInFrustum(center, BoardGeometry.width() * .5f)) { continue; }
                float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera.camera,
                      new Vector3(center).mulAdd(camera.camera.direction, -BoardGeometry.width() * .75f));
                if (kind == Kind.GRASS && pixels <= 120 || kind == Kind.FIELDS && pixels <= GpuBiomeVegetation.CROP_FADE[0]) {
                    continue;
                }
                visible++;
                if (kind == Kind.WOODS) { complete++; continue; }
                var planted = terrain.planted(tile.coords());
                if (planted == null) { continue; }
                boolean shown = switch (kind) {
                    case FIELDS -> (planted.wheat() != null || planted.maize() != null)
                          && (planted.wheat() == null || drawn.contains(planted.wheat()))
                          && (planted.maize() == null || drawn.contains(planted.maize()));
                    case GRASS -> planted.grass() != null && (planted.grass().size == 0 || drawn.contains(planted.grass()));
                    case MARSH -> planted.reeds() != null && (planted.reeds().size == 0 || drawn.contains(planted.reeds()));
                    case WOODS -> true;
                };
                if (shown) { complete++; }
            }
            return new int[] { complete, visible };
        }

        /** The renderer drawing this kind's plants: the grass engine draws the fields' too. */
        private Object renderer() { return kind == Kind.MARSH ? plants : grass; }

        long uploads() {
            return kind == Kind.MARSH ? plants.uploads() : kind == Kind.WOODS ? terrain.treeInstanceUploads() : grass.uploads();
        }

        void hide() throws Exception {
            if (kind == Kind.GRASS) { terrain.setGrass(false); }
            else if (kind == Kind.FIELDS) {
                // The fields' plants stand whatever the Grass blades option: a scene taken for one without fields hides them.
                terrain.setGrass(false);
                set(terrain, "fieldsScene", GpuTestSupport.field(terrain, "coverScene"));
                set(terrain, "fields", false);
            }
            else if (kind == Kind.WOODS) {
                for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                    Map<?, ?> stand = (Map<?, ?>) GpuTestSupport.field(GpuTestSupport.field(chunk, "stand"), "trees");
                    trees.add(new java.util.LinkedHashMap<>(stand));
                    stand.clear();
                }
            }
            else { ((Set<?>) GpuTestSupport.field(plants, "marshes")).clear(); set(plants, "view", null); }
        }

        void show() throws Exception {
            if (kind == Kind.GRASS) { terrain.setGrass(true); }
            else if (kind == Kind.FIELDS) {
                terrain.setGrass(true);
                set(terrain, "fieldsScene", null);
            }
            else if (kind == Kind.WOODS) {
                List<?> chunks = (List<?>) GpuTestSupport.field(terrain, "chunks");
                for (int index = 0; index < chunks.size(); index++) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> stand = (Map<Object, Object>) GpuTestSupport.field(GpuTestSupport.field(chunks.get(index), "stand"), "trees");
                    stand.putAll(trees.get(index));
                }
                trees.clear();
            }
            else { set(plants, "tiles", null); }
        }

        /** Draws, instances and triangles of the cover in this view, with the instanced draw count of the whole frame. */
        String summary(GLProfiler profiler, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene)
              throws Exception {
            profiler.reset();
            profiler.enable();
            draw(terrain, frame, camera, scene);
            profiler.disable();
            if (kind == Kind.WOODS) {
                return String.format(Locale.ROOT, "cover: trees in %d draws of the whole frame, %.1f MB tree geometry%n",
                      profiler.getDrawCalls(), terrain.treeGeometryBytes() / 1e6);
            }
            int draws = 0, ranges = 0;
            long instances = 0, triangles = 0;
            for (Object batch : batches(renderer())) {
                if (((List<?>) GpuTestSupport.field(batch, "current")).isEmpty()) { continue; }
                var mesh = (GpuInstancedMesh) GpuTestSupport.field(batch, "mesh");
                if (mesh == null) { continue; }
                long count;
                if (kind == Kind.MARSH) { count = ((FloatArray) GpuTestSupport.field(batch, "data")).size / 4; }
                else if (mesh.drawInstances >= 0) { count = mesh.drawInstances; }
                else {
                    // One bound buffer per chunk, drawn as one base-instance call per hex range.
                    var hexRanges = (com.badlogic.gdx.utils.IntArray) GpuTestSupport.field(batch, "ranges");
                    count = 0;
                    for (int i = 1; i < hexRanges.size; i += 2) { if (hexRanges.items[i] > 0) { count += hexRanges.items[i]; ranges++; } }
                }
                draws++;
                instances += count;
                triangles += (long) mesh.getNumIndices() / 3 * count;
            }
            long needed = 0;
            if (kind == Kind.GRASS) {
                // Blades the view needs by each hex's own projected size; the chunk draws its nearest hex's prefix.
                for (var tile : hexes) {
                    Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                    if (!camera.camera.frustum.sphereInFrustum(center, BoardGeometry.width() * .75f)) { continue; }
                    float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera.camera,
                          new Vector3(center).mulAdd(camera.camera.direction, -BoardGeometry.width() * .75f));
                    float t = Math.clamp((pixels - 120) / 380, 0, 1);
                    needed += Math.round(4096 * t * t * (3 - 2 * t));
                }
            }
            return String.format(Locale.ROOT, "cover: %d draws%s, %d instances, %d triangles submitted%s; whole frame %d draws%n",
                  draws, ranges > 0 ? " in " + ranges + " hex ranges" : "", instances, triangles,
                  kind == Kind.GRASS ? " (" + needed + " blades needed by hex)" : "", profiler.getDrawCalls());
        }

        private static List<Object> batches(Object renderer) throws Exception {
            return GpuBiomeSmokeTest.batches(renderer);
        }
    }

    private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        member.set(owner, value);
    }
}
