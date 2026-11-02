/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each board, light, centre and view: the direct frame, the cache switched on and drawn until every visible top draws
 * cached (the frame before the switch, the switch frame, a settled cached frame; frames, time, base tiles, the cached
 * share while it fills, frame times cached and direct), optionally the direct frame without parallax and the close
 * view averaged (k x k tiles at the view's zoom over k, averaged in linear light). Optionally a top view zoomed out and
 * in through a level crossing a step a frame, and an edit stroke on a cached view (crossing-*, edit-* crops). Writes
 * cache-*.png and cache-parity.txt.
 */
@Tag("on-demand")
class ZzMaterialCacheParitySmokeTest {
    /** A view, and k: how many close-view pixels across one of its pixels (it must divide the window's sides). */
    private record View(String name, float zoom, boolean top, float tilt, int k) { }

    /** A board with its centres of interest. */
    private record Target(String name, BoardScene scene, List<Coords> centres, List<String> centreNames) { }

    private static final List<View> VIEWS = List.of(new View("close", .2f, false, 0, 1),
          new View("medium", .5f, false, 0, 2), new View("near", .35f, false, 0, 2), new View("far", 1.3f, false, 0, 5),
          new View("top-medium", .5f, true, 0, 2), new View("top-far", 1.3f, true, 0, 5),
          new View("tilt70", .5f, false, 70, 2), new View("max-top", 20f, true, 0, 1),
          new View("max-iso", 20f, false, 0, 1));
    private static final Pattern SHOWN = Pattern.compile("shown=(\\d+) cached=\\[([^]]*)]");
    private static final Pattern TILES = Pattern.compile("tiles=(\\d+)");

    @Test
    void capturesCachedTopsAgainstDirect() throws Exception {
        String boards = System.getProperty("megamek.gpu.cacheBoards", "calibration,grass");
        String views = System.getProperty("megamek.gpu.cacheViews", "medium,far,tilt70");
        String lights = System.getProperty("megamek.gpu.cacheLights", "noon");
        boolean truth = !"false".equals(System.getProperty("megamek.gpu.cacheTruth"));
        // The ground alone: grass blades and Object LoD change with zoom whatever the ground does.
        boolean isolate = !"false".equals(System.getProperty("megamek.gpu.cacheIsolate"));
        boolean crossing = Boolean.getBoolean("megamek.gpu.cacheCrossing");
        boolean edit = Boolean.getBoolean("megamek.gpu.cacheEdit");
        boolean sweep = Boolean.getBoolean("megamek.gpu.cacheSweep");
        boolean zoomIn = Boolean.getBoolean("megamek.gpu.cacheZoomIn");
        float waitSeconds = Float.parseFloat(System.getProperty("megamek.gpu.cacheWaitSeconds", "40"));
        File output = new File(GpuDepthPrepassBaselineSmokeTest.OUTPUT, "material-cache");
        Files.createDirectories(output.toPath());
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Target> targets = new ArrayList<>();
        if (boards.contains("calibration")) {
            targets.add(new Target("calibration", GpuDepthPrepassBaselineSmokeTest.scene(
                  GpuDepthPrepassBaselineSmokeTest.CALIBRATION), List.of(GpuDepthPrepassBaselineSmokeTest.FOOT),
                  List.of("")));
        }
        File grassFile = new File("data/boards/Beginner Box/16x17 Grassland 1.board");
        if (boards.contains("grass")) {
            targets.add(new Target("grassland", GpuDepthPrepassBaselineSmokeTest.scene(grassFile),
                  List.of(new Coords(8, 8)), List.of("")));
        }
        if (boards.contains("mines")) {
            // Grass and desert on one board, with water: blended tops and tops beside water (stage 2).
            targets.add(new Target("mines", GpuDepthPrepassBaselineSmokeTest.scene(
                  new File("data/boards/unofficial/Kajis/16x17 Mines.board")), List.of(new Coords(8, 8)),
                  List.of("")));
        }
        if (boards.contains("city")) {
            targets.add(new Target("city", GpuDepthPrepassBaselineSmokeTest.scene(
                  new File("data/boards/unofficial/VictorMorson/48x48.board")), List.of(new Coords(24, 24)),
                  List.of("")));
        }
        long ocoeeLoad = 0;
        if (boards.contains("ocoee")) {
            long start = System.nanoTime();
            BoardScene ocoee = GpuDepthPrepassBaselineSmokeTest.scene(
                  new File("data/boards/unofficial/Lanceman/50x700 Ocoee.board"));
            ocoeeLoad = System.nanoTime() - start;
            targets.add(new Target("ocoee", ocoee, List.of(new Coords(25, 6), new Coords(25, 693)),
                  List.of("-south", "-north")));
        }
        // The edit stroke: the grassland with its centre hex raised a level.
        BoardScene edited = edit ? edited(grassFile, new Coords(8, 8)) : null;
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(1, 1, -1, -1);
        // The window, as the scene's pixels: 1920x1080, 2560x1440 or 3840x2160 (-Dmegamek.gpu.cacheSize).
        String[] size = System.getProperty("megamek.gpu.cacheSize", "1920x1080").split("x");
        config.setWindowedMode(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
        long sceneNanos = ocoeeLoad;
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" scene ")
                          .append(Gdx.graphics.getBackBufferWidth()).append('x')
                          .append(Gdx.graphics.getBackBufferHeight()).append(" hexWidth=")
                          .append(BoardGeometry.width()).append(" split=").append(GpuMaterialCache.SPLIT).append('\n');
                    if (sceneNanos > 0) {
                        report.append(String.format(Locale.ROOT, "ocoee scene ms=%.0f%n", sceneNanos / 1e6));
                    }
                    for (Target target : targets) {
                        for (String light : lights.split(",")) {
                            capture(target, light.equals("rain") ? GpuDepthPrepassBaselineSmokeTest.RAIN
                                  : GpuDepthPrepassBaselineSmokeTest.NOON, light);
                        }
                    }
                    report.append("GL error: ").append(Gdx.gl.glGetError()).append('\n');
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }

            private void capture(Target target, BoardAtmosphere.Settings light, String lightName) throws Exception {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(light);
                try {
                    var camera = new BoardCamera();
                    long start = System.nanoTime();
                    GpuDepthPrepassBaselineSmokeTest.prepare(terrain, frame, camera, target.scene());
                    report.append(String.format(Locale.ROOT, "%s build ms=%.0f%n", target.name(),
                          (System.nanoTime() - start) / 1e6));
                    if (isolate) {
                        terrain.setGrass(false);
                        terrain.setSmallObjects(false);
                    }
                    BoardScene scene = target.scene();
                    for (int c = 0; c < target.centres().size(); c++) {
                        Coords centre = target.centres().get(c);
                        Vector3 focus = BoardGeometry.center(centre, scene.tile(centre).elevation());
                        for (View view : VIEWS) {
                            if (!List.of(views.split(",")).contains(view.name())) { continue; }
                            String base = "cache-" + target.name() + target.centreNames().get(c) + "-" + lightName + "-"
                                  + view.name();
                            terrain.setMaterialCache(false);
                            pose(camera, view, focus, 1);
                            GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                            int compiles = GpuGlsl.compilesAfterLoading();
                            direct(terrain, frame, camera, scene, new File(output, base + "-direct.png"));
                            double directMs = frameMs(terrain, frame, camera, scene, 10);
                            if (truth && view.k() > 1) {
                                terrain.setTerrainParallax(false);
                                direct(terrain, frame, camera, scene, new File(output, base + "-noparallax.png"));
                                terrain.setTerrainParallax(true);
                            }
                            report.append(base).append(' ')
                                  .append(transition(terrain, frame, camera, scene, output, base, waitSeconds))
                                  .append(String.format(Locale.ROOT, " directMs=%.2f pixelMetres=%.3f", directMs,
                                        camera.camera.zoom * 30 / BoardGeometry.width()))
                                  .append('\n');
                            report.append(base).append(" compilesAfterLoading=")
                                  .append(GpuGlsl.compilesAfterLoading() - compiles).append('\n');
                            if (truth && view.k() > 1) {
                                closeViewAveraged(terrain, frame, camera, scene, view, focus,
                                      new File(output, base + "-truth.png"));
                            }
                            Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
                        }
                        if (sweep && c == 0) { sweep(target.name(), terrain, frame, camera, scene, focus, output); }
                        if (crossing && c == 0 && target == targets.get(0)) {
                            crossing(terrain, frame, camera, scene, focus, output);
                        }
                        if (zoomIn && c == 0) { zoomIn(target.name(), terrain, frame, camera, scene, focus, output); }
                        if (edited != null && target.name().equals("grassland")) {
                            edit(terrain, frame, camera, scene, edited, focus, output);
                        }
                    }
                } finally {
                    terrain.setMaterialCache(true);
                    frame.dispose();
                    terrain.dispose();
                }
            }

            /** The view's pose, its zoom divided by {@code closer}. */
            private void pose(BoardCamera camera, View view, Vector3 focus, int closer) {
                camera.setIsometric(!view.top());
                if (view.tilt() > 0) { camera.tilt(view.tilt() - BoardCamera.ISOMETRIC_TILT); }
                camera.camera.zoom = view.zoom() / closer;
                camera.center(focus);
            }

            /**
             * A top view zoomed out from 0.28 to 0.57 m a pixel and back, a step of 3% a frame, through the crossing
             * between levels 1 and 2 at 0.4 m: each frame's centre crop and the cache's state.
             */
            private void crossing(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
                  Vector3 focus, File output) throws Exception {
                float metre = BoardGeometry.width() / 30;
                List<Float> steps = new ArrayList<>();
                for (float p = .28f; p <= .57f; p *= 1.03f) { steps.add(p); }
                List<Float> back = new ArrayList<>(steps);
                java.util.Collections.reverse(back);
                terrain.setMaterialCache(true);
                for (var sweep : List.of(Map.entry("out", steps), Map.entry("in", back))) {
                    pose(camera, new View("crossing", sweep.getValue().get(0) * metre, true, 0, 1), focus, 1);
                    GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                    settleCached(terrain, frame, camera, scene);
                    List<Float> zooms = sweep.getValue();
                    for (int i = 0; i < zooms.size(); i++) {
                        pose(camera, new View("crossing", zooms.get(i) * metre, true, 0, 1), focus, 1);
                        terrain.refine(camera.camera);
                        frame.render(terrain, camera, scene);
                        String name = String.format(Locale.ROOT, "crossing-%s-%02d", sweep.getKey(), i);
                        crop(new File(output, name + ".png"), 512);
                        report.append(String.format(Locale.ROOT, "%s pixelMetres=%.3f complete=%b %s%n", name,
                              zooms.get(i), terrain.materialCacheComplete(), state(terrain)));
                    }
                }
                Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
            }

            /**
             * A zoom from the close view (0.2) out to the camera's maximum (20) and back, 5% a frame, top and iso:
             * frames where the cache drew while some visible top drew directly, frame times.
             */
            private void sweep(String board, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
                  BoardScene scene, Vector3 focus, File output) throws Exception {
                for (boolean top : new boolean[] { true, false }) {
                    List<Float> zooms = new ArrayList<>();
                    for (float z = .2f; z < 20; z *= 1.05f) { zooms.add(z); }
                    zooms.add(20f);
                    List<Float> back = new ArrayList<>(zooms);
                    java.util.Collections.reverse(back);
                    zooms.addAll(back);
                    double[][] times = new double[2][zooms.size()];
                    String[] states = new String[zooms.size()];
                    int active = 0, gaps = 0;
                    StringBuilder levels = new StringBuilder();
                    // Direct first (the frame's own cost at each zoom), then cached.
                    for (int pass = 0; pass < 2; pass++) {
                        terrain.setMaterialCache(pass == 1);
                        pose(camera, new View("sweep", zooms.get(0), top, 0, 1), focus, 1);
                        GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                        for (int i = 0; i < zooms.size(); i++) {
                            pose(camera, new View("sweep", zooms.get(i), top, 0, 1), focus, 1);
                            terrain.refine(camera.camera);
                            long start = System.nanoTime();
                            frame.render(terrain, camera, scene);
                            Gdx.gl.glFinish();
                            times[pass][i] = (System.nanoTime() - start) / 1e6;
                            if (pass == 0) { continue; }
                            String state = state(terrain);
                            states[i] = state;
                            boolean on = state.contains("active=true");
                            int at = state.indexOf("cachedShare=");
                            float share = at < 0 ? 0 : Float.parseFloat(state.substring(at + 12));
                            if (on) { active++; }
                            if (on && share < 1) { gaps++; }
                            Matcher m = Pattern.compile("level=(\\d+) reading=(\\d+)").matcher(state);
                            if (m.find()) {
                                levels.append(m.group(1)).append('/').append(m.group(2)).append(on && share < 1 ? "!" : "")
                                      .append(' ');
                            }
                            if (i % 20 == 0) {
                                crop(new File(output, String.format(Locale.ROOT, "sweep-%s-%s-%03d.png", board,
                                      top ? "top" : "iso", i)), 512);
                            }
                        }
                    }
                    double[] mean = new double[2], worst = new double[2];
                    for (int pass = 0; pass < 2; pass++) {
                        for (double t : times[pass]) {
                            mean[pass] += t / zooms.size();
                            worst[pass] = Math.max(worst[pass], t);
                        }
                    }
                    report.append(String.format(Locale.ROOT, "sweep %s %s frames=%d activeFrames=%d framesWithDirectTops=%d "
                                + "meanMs cached=%.2f direct=%.2f worstMs cached=%.2f direct=%.2f%n  levels %s%n", board,
                          top ? "top" : "iso", zooms.size(), active, gaps, mean[1], mean[0], worst[1], worst[0], levels));
                    // The frames where the cache cost most over the direct frame at the same zoom.
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < zooms.size(); i++) { order.add(i); }
                    order.sort((x, y) -> Double.compare(times[1][y] - times[0][y], times[1][x] - times[0][x]));
                    for (int i : order.subList(0, 6)) {
                        report.append(String.format(Locale.ROOT, "  worst %d zoom=%.2f cachedMs=%.2f directMs=%.2f %s%n", i,
                              zooms.get(i), times[1][i], times[0][i],
                              states[i] == null ? "" : states[i].replaceAll("capacity=.*?shown", "shown")));
                    }
                    Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
                }
            }

            /** A cached far view, then medium in one step (two levels in): frames until exact, with direct tops. */
            private void zoomIn(String board, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
                  BoardScene scene, Vector3 focus, File output) throws Exception {
                terrain.setMaterialCache(true);
                pose(camera, VIEWS.get(2), focus, 1);
                GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                settleCached(terrain, frame, camera, scene);
                pose(camera, VIEWS.get(1), focus, 1);
                int frames = 0, direct = 0;
                for (; frames < 600 && (frames == 0 || !terrain.materialCacheComplete()); frames++) {
                    terrain.refine(camera.camera);
                    frame.render(terrain, camera, scene);
                    String state = state(terrain);
                    if (!state.endsWith("cachedShare=1.0")) { direct++; }
                    if (frames < 4 || frames % 10 == 0) {
                        crop(new File(output, String.format(Locale.ROOT, "zoomin-%s-%03d.png", board, frames)), 512);
                        report.append(String.format(Locale.ROOT, "zoomin-%s-%03d %s%n", board, frames, state));
                    }
                }
                report.append(String.format(Locale.ROOT, "zoomin %s frames=%d framesWithDirectTops=%d complete=%b %s%n",
                      board, frames, direct, terrain.materialCacheComplete(), terrain.materialCacheReport()));
                Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
            }

            /** A cached medium view, then the edit stroke built incrementally: each frame's state and crop. */
            private void edit(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
                  BoardScene after, Vector3 focus, File output) throws Exception {
                terrain.setMaterialCache(true);
                pose(camera, VIEWS.get(1), focus, 1);
                GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                settleCached(terrain, frame, camera, scene);
                terrain.update(after, camera.camera);
                int frames = 0, direct = 0, rebuilding = 0;
                boolean built = false;
                for (; frames < 400 && !(built && terrain.materialCacheComplete()); frames++) {
                    terrain.refine(camera.camera);
                    frame.render(terrain, camera, scene);
                    built |= !terrain.busy();
                    String state = state(terrain);
                    if (!state.endsWith("cachedShare=1.0")) { direct++; }
                    if (!terrain.materialCacheComplete()) { rebuilding++; }
                    if (frames < 8 || frames % 10 == 0) {
                        crop(new File(output, String.format(Locale.ROOT, "edit-%03d.png", frames)), 512);
                        report.append(String.format(Locale.ROOT, "edit-%03d busy=%b complete=%b %s%n", frames,
                              terrain.busy(), terrain.materialCacheComplete(), state));
                    }
                }
                report.append(String.format(Locale.ROOT, "edit frames=%d framesWithDirectTops=%d framesNotExact=%d "
                            + "complete=%b %s%n", frames, direct, rebuilding, terrain.materialCacheComplete(),
                      terrain.materialCacheReport()));
                Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
            }

            private void settleCached(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
                for (int i = 0; i < 600 && !terrain.materialCacheComplete(); i++) {
                    terrain.refine(camera.camera);
                    frame.render(terrain, camera, scene);
                }
            }

            /** The cache's levels and the share of terrain pages with visible tops drawn cached. */
            private String state(GpuTerrain terrain) {
                String text = terrain.materialCacheReport();
                Matcher m = SHOWN.matcher(text);
                if (!m.find()) { return text; }
                int shown = Integer.parseInt(m.group(1));
                long cached = Arrays.stream(m.group(2).split(",")).filter(s -> !s.isBlank()).count();
                return text.replaceAll(" cached=\\[[^]]*]", "") + " cachedShare=" + (shown == 0 ? 1 : cached / (float) shown);
            }

            /** The median of {@code frames} frames' times, drawn and finished, milliseconds. */
            private double frameMs(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
                  int frames) {
                double[] times = new double[frames];
                for (int i = 0; i < frames; i++) {
                    long start = System.nanoTime();
                    frame.render(terrain, camera, scene);
                    Gdx.gl.glFinish();
                    times[i] = (System.nanoTime() - start) / 1e6;
                }
                Arrays.sort(times);
                return times[frames / 2];
            }

            /** The central {@code side} x {@code side} pixels of the frame. */
            private void crop(File file, int side) {
                int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                Pixmap pixels = Pixmap.createFromFrameBuffer((width - side) / 2, (height - side) / 2, side, side);
                try {
                    ByteBuffer data = pixels.getPixels();
                    for (int i = 3; i < data.limit(); i += 4) { data.put(i, (byte) 255); }
                    PixmapIO.writePNG(new FileHandle(file), pixels, -1, true);
                } finally {
                    pixels.dispose();
                }
            }

            /**
             * The view's framing drawn directly at its zoom over k, in k x k tiles offset by exactly a tile in the
             * camera's plane, each averaged k x k in linear light into its block of the view's pixels.
             */
            private void closeViewAveraged(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
                  BoardScene scene, View view, Vector3 focus, File file) throws Exception {
                int k = view.k(), width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                pose(camera, view, focus, 1);
                float spanX = camera.camera.viewportWidth * camera.camera.zoom;
                float spanY = camera.camera.viewportHeight * camera.camera.zoom;
                Vector3 up = new Vector3(camera.camera.up).nor();
                Vector3 right = new Vector3(camera.camera.direction).crs(up).nor();
                float[] sums = new float[width * height * 3];
                terrain.setMaterialCache(false);
                for (int j = 0; j < k; j++) {
                    for (int i = 0; i < k; i++) {
                        Vector3 tile = new Vector3(focus).mulAdd(right, spanX * ((i + .5f) / k - .5f))
                              .mulAdd(up, spanY * ((j + .5f) / k - .5f));
                        pose(camera, view, tile, k);
                        GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                        frame.render(terrain, camera, scene);
                        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
                        try {
                            ByteBuffer data = pixels.getPixels();
                            // Rows from the bottom, as the framebuffer holds them.
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    int at = (y * width + x) * 4;
                                    int to = ((j * height + y) / k * width + (i * width + x) / k) * 3;
                                    for (int c = 0; c < 3; c++) { sums[to + c] += linear(data.get(at + c) & 255); }
                                }
                            }
                        } finally {
                            pixels.dispose();
                        }
                    }
                }
                Pixmap averaged = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                try {
                    ByteBuffer out = averaged.getPixels();
                    for (int p = 0; p < width * height; p++) {
                        for (int c = 0; c < 3; c++) { out.put(p * 4 + c, (byte) srgb(sums[p * 3 + c] / (k * k))); }
                        out.put(p * 4 + 3, (byte) 255);
                    }
                    PixmapIO.writePNG(new FileHandle(file), averaged, -1, true);
                } finally {
                    averaged.dispose();
                }
                pose(camera, view, focus, 1);
            }

            private float linear(int code) {
                float v = code / 255f;
                return v <= .04045f ? v / 12.92f : (float) Math.pow((v + .055f) / 1.055f, 2.4f);
            }

            private int srgb(float v) {
                float e = v <= .0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4f) - .055f;
                return Math.round(Math.max(0, Math.min(1, e)) * 255);
            }

            private void direct(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, File file) {
                terrain.setMaterialCache(false);
                frame.render(terrain, camera, scene);
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(file);
            }

            /**
             * The cache on: frames until every visible top draws cached (or {@code waitSeconds}), saving the frames
             * around the switch; the base tiles built, the mean cached share and frame time while it fills, and the
             * cached frame time after.
             */
            private String transition(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
                  File output, String base, float waitSeconds) throws Exception {
                terrain.setMaterialCache(true);
                long start = System.nanoTime();
                int frames = 0, tiles0 = tiles(terrain);
                double shareSum = 0, fillMs = 0, rootMs = -1;
                Pixmap before = null;
                try {
                    while (frames < waitSeconds * 15 && System.nanoTime() - start < waitSeconds * 1e9) {
                        long frameStart = System.nanoTime();
                        frame.render(terrain, camera, scene);
                        Gdx.gl.glFinish();
                        fillMs += (System.nanoTime() - frameStart) / 1e6;
                        frames++;
                        String state = state(terrain);
                        Matcher root = Pattern.compile("root=\\d+:(\\d+)/(\\d+)").matcher(state);
                        if (rootMs < 0 && root.find() && root.group(1).equals(root.group(2))) {
                            rootMs = (System.nanoTime() - start) / 1e6;
                        }
                        int at = state.indexOf("cachedShare=");
                        shareSum += at < 0 ? 0 : Float.parseFloat(state.substring(at + 12));
                        if (terrain.materialCacheComplete()) { break; }
                        if (before != null) { before.dispose(); }
                        before = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                              Gdx.graphics.getBackBufferHeight());
                    }
                    File beforeFile = new File(output, base + "-before.png");
                    Files.deleteIfExists(beforeFile.toPath());
                    if (before != null) {
                        var pixels = before.getPixels();
                        for (int i = 3; i < pixels.limit(); i += 4) { pixels.put(i, (byte) 255); }
                        PixmapIO.writePNG(new FileHandle(beforeFile), before, -1, true);
                    }
                } finally {
                    if (before != null) { before.dispose(); }
                }
                double ms = (System.nanoTime() - start) / 1e6;
                boolean complete = terrain.materialCacheComplete();
                int tiles = tiles(terrain) - tiles0;
                File switchFile = new File(output, base + "-switch.png");
                Files.deleteIfExists(switchFile.toPath());
                if (complete) { GpuReviewFrame.save(switchFile); }
                frame.render(terrain, camera, scene);
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, base + "-cached.png"));
                double cachedMs = frameMs(terrain, frame, camera, scene, 10);
                return String.format(Locale.ROOT, "complete=%b frames=%d ms=%.0f rootBuiltMs=%.0f tilesBuilt=%d "
                            + "meanCachedShare=%.3f fillFrameMs=%.2f cachedMs=%.2f %s", complete, frames, ms, rootMs, tiles,
                      frames == 0 ? 0 : shareSum / frames, frames == 0 ? 0 : fillMs / frames, cachedMs,
                      terrain.materialCacheReport());
            }

            private int tiles(GpuTerrain terrain) {
                Matcher m = TILES.matcher(terrain.materialCacheReport());
                return m.find() ? Integer.parseInt(m.group(1)) : 0;
            }
        }, config);
        Files.writeString(new File(output, "cache-parity.txt").toPath(), report);
        System.out.print(report);
        if (failure.get() != null) { throw new AssertionError("Material cache parity", failure.get()); }
    }

    /** A board's scene with the hex at {@code at} raised a level. */
    private static BoardScene edited(File file, Coords at) throws Exception {
        Board board = BoardFile.loadSheet(file, false, true);
        Hex hex = board.getHex(at).duplicate();
        hex.setLevel(hex.getLevel() + 1);
        board.setHex(at.getX(), at.getY(), hex);
        try (var fixture = GpuBoardFixture.create(board)) {
            AtomicReference<BoardScene> scene = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
            return scene.get();
        }
    }
}
