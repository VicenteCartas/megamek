/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Forest cost on a large board: build time, and at each tree detail level the frame time, draws, vertices and the
 * tree geometry held in memory. Woods cover about 40% of a 40 by 40 board of mixed grounds. The numbers are measured,
 * not asserted; a software renderer's frame times say little about a GPU, but memory and build times carry over.
 */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuForestBenchmarkSmokeTest {
    private static final int SIZE = 40;
    private static final int WARMUP = 24;
    private static final int FRAMES = 40;

    @Test
    void measuresWoodsOnALargeBoard() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder report = new StringBuilder();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        config.useVsync(false);
        config.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                GL20 rawGl20 = Gdx.gl20;
                try (var timings = new GpuStageTimings(); var outputFrame = new GpuSceneTestOutput()) {
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                    report.append("1440x1080; 24 warmup + 40 samples; profiler excluded from timings.\n")
                          .append("Terrain and display steady frames with glFinish; not full-game FPS.\n");
                    BoardAtmosphere.Settings settings = new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                    terrain.setAtmosphere(BoardAtmosphere.lighting(settings));
                    BoardScene scene = forest();
                    long start = System.nanoTime();
                    terrain.update(scene);
                    int trees = 0;
                    for (BoardScene.Tile tile : scene.tiles()) {
                        trees += (int) tile.features().stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).count();
                    }
                    report.append(String.format(Locale.ROOT, "%d by %d hexes, %d trees; build %.0f ms%n", SIZE, SIZE, trees,
                          (System.nanoTime() - start) / 1e6));
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    // Far, middle and near tree detail: trees about 15, 50 and 200 pixels across.
                    for (float zoom : new float[] { 3.2f, 1.0f, .25f }) {
                        camera.camera.zoom = zoom;
                        camera.center(BoardGeometry.center(new Coords(SIZE / 2, SIZE / 2), 0));
                        long lod = System.nanoTime();
                        terrain.renderShadows(camera.camera, List.of());
                        frame(outputFrame, terrain, camera, null);
                        Gdx.gl.glFinish();
                        double switchMillis = (System.nanoTime() - lod) / 1e6;
                        for (int i = 0; i < WARMUP; i++) {
                            timings.beginFrame(false);
                            frame(outputFrame, terrain, camera, timings);
                            Gdx.gl.glFinish();
                        }
                        double[] times = new double[FRAMES];
                        for (int i = 0; i < FRAMES; i++) {
                            timings.beginFrame();
                            long t = System.nanoTime();
                            frame(outputFrame, terrain, camera, timings);
                            Gdx.gl.glFinish();
                            times[i] = (System.nanoTime() - t) / 1e6;
                        }
                        Arrays.sort(times);
                        profiler.reset();
                        profiler.enable();
                        frame(outputFrame, terrain, camera, null);
                        GpuStageTimings.stopCounting(profiler, rawGl20);
                        report.append(String.format(Locale.ROOT,
                              "  zoom %.2f: first frame %.0f ms, median frame %.1f ms, draws=%d vertices=%.0f,"
                                    + " tree geometry %.1f MB%n", zoom, switchMillis, times[FRAMES / 2],
                              profiler.getDrawCalls(), profiler.getVertexCount().total, terrain.treeGeometryBytes() / 1e6));
                        timings.appendReport(report, "  zoom " + zoom);
                        GpuBoardTestUi.capture(new File(output, String.format(Locale.ROOT, "forest-%.2f.png", zoom)));
                    }
                    Files.writeString(new File(output, "forest-benchmark.txt").toPath(), report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuStageTimings.stopCounting(profiler, rawGl20);
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Forest benchmark", failure.get()); }
        System.out.print(report);
    }

    private static void frame(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera,
          GpuStageTimings timings) {
        if (timings != null) { timings.stage("opaque"); }
        output.begin(.42f, .56f, .69f, 1);
        terrain.render(camera.camera);
        if (timings != null) { timings.stage("transparent"); }
        terrain.renderTransparent(camera.camera);
        if (timings != null) { timings.stage("display"); }
        output.display();
        if (timings != null) { timings.stage(null); }
    }

    /** Level ground of mixed families; woods of every density on about 40% of the hexes, seeded. */
    static BoardScene forest() {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        BoardScene.Pixels ground = new BoardScene.Pixels(image);
        Random random = new Random(40);
        BoardScene.Surface[] families = { BoardScene.Surface.GRASS, BoardScene.Surface.GRASS, BoardScene.Surface.ROCK,
              BoardScene.Surface.SNOW, BoardScene.Surface.SAND, BoardScene.Surface.DIRT };
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                Coords coords = new Coords(x, y);
                BoardScene.Surface family = families[(x / 8 + y / 8) % families.length];
                List<BoardScene.Feature> features = List.of();
                if (random.nextFloat() < .4f) {
                    Hex hex = new Hex(0);
                    int density = 1 + random.nextInt(3);
                    hex.addTerrain(new Terrain(Terrains.WOODS, density));
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
                    switch (family) {
                        case SNOW -> hex.addTerrain(new Terrain(Terrains.SNOW, 1));
                        case SAND -> hex.addTerrain(new Terrain(Terrains.SAND, 1));
                        case ROCK -> hex.setTheme("rock");
                        case DIRT -> hex.setTheme("dirt");
                        default -> { }
                    }
                    features = BoardFeatures.capture(hex, coords, Map.of());
                }
                tiles.add(BoardScenes.newTile(coords, 0, -1, false, 0, family, features,
                      List.of(), BoardLiquid.NONE, null));
            }
        }
        return new BoardScene(0, SIZE, SIZE, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
