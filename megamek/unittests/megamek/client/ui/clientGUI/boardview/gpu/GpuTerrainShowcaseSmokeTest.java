/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Deterministic sculpted-terrain review scene: a connected multi-hex plateau with a concave notch, a tall isolated
 * formation, a one-hex ridge, stepped mixed-elevation junctions, a sunken basin, a lake and woods of every density.
 * Each surface family is captured from a tactical overview, a medium oblique angle, close rim/base views, the woods and
 * one light wood up close, with a grid-free variant. Captures are drawn as on the board, through
 * the atmosphere composite ({@link GpuReviewFrame}), at the hour megamek.gpu.showcase.hour, or at each of the
 * comma-separated megamek.gpu.showcase.hours, whose captures are named with a "-h<hour>" suffix, under the cloud cover
 * megamek.gpu.showcase.clouds (0 to 1, default clear).
 */
@Tag("on-demand")
class GpuTerrainShowcaseSmokeTest {
    /** Rows are y; characters are x. Digits are levels, a/b are -1/-2, w/W are lakes of depth 1/2 at level 0. */
    static final String[] LAYOUT = {
          "0000000000000000",
          "0000033300000000",
          "0000333330000000",
          "0003333332000000",
          "0003333322005000",
          "0000330331000000",
          "0000021110000000",
          "0020000000000000",
          "0020000000000000",
          "0020000000aa0000",
          "0020000000ab0www",
          "0020000000a00wWw",
          "000000000000wwWw",
          "000000000000wwww",
    };

    /** Woods over the layout: l, h and u are light, heavy and ultra-heavy woods with the game's default heights. */
    static final String[] WOODS = {
          "................",
          "................",
          "....h...........",
          "...hh...........",
          "................",
          "................",
          "................",
          ".............l..",
          "............l...",
          ".....uu.........",
          "....uhh.........",
          "....hl..........",
          "................",
          "................",
    };

    private record View(String name, boolean isometric, float tilt, float rotation, float zoom, Coords focus,
          int level, float grid) { }

    private static final List<View> VIEWS = List.of(
          new View("overview", false, 0, 0, 1.02f, new Coords(8, 7), 0, .8f),
          new View("oblique", true, 0, 0, .52f, new Coords(7, 5), 1, .8f),
          new View("oblique-nogrid", true, 0, 0, .52f, new Coords(7, 5), 1, 1f),
          new View("rim", true, 12, 30, .2f, new Coords(8, 5), 2, .8f),
          new View("base", true, 22, -40, .22f, new Coords(4, 5), 1, .8f),
          new View("formation", true, 18, 150, .3f, new Coords(12, 4), 2, .8f),
          new View("rough", true, 18, 150, .12f, new Coords(12, 6), 0, .8f),
          new View("rough-ridge", true, 18, 150, .12f, new Coords(12, 4), 5, .8f),
          new View("ridge", true, 10, -100, .32f, new Coords(2, 9), 1, .8f),
          new View("corner", true, 20, 60, .1f, new Coords(12, 4), 3, .8f),
          new View("notch", true, 25, 200, .12f, new Coords(8, 4), 2, .8f),
          new View("junction-top", false, 0, 0, .16f, new Coords(5, 6), 2, .8f),
          new View("woods", true, 14, -20, .24f, new Coords(5, 9), 1, .8f),
          new View("woods-close", true, 22, -20, .09f, new Coords(5, 11), 0, .8f));

    @Test
    void capturesSculptedTerrainReviewViews() throws Exception {
        String families = System.getProperty("megamek.gpu.showcase.families", "SAND,GRASS");
        String hour = System.getProperty("megamek.gpu.showcase.hour", "13");
        String series = System.getProperty("megamek.gpu.showcase.hours", "");
        List<String> hours = List.of((series.isBlank() ? hour : series).split(","));
        List<String> views = List.of(System.getProperty("megamek.gpu.showcase.views", "").split(","));
        // Hex transitions on or off; captures with them on are named with a "-transitions" suffix.
        boolean transitions = Boolean.parseBoolean(System.getProperty("megamek.gpu.showcase.transitions",
              String.valueOf(BoardGeometry.DEFAULT_TRANSITIONS)));
        // Hex padding in metres; captures with it on are named with a "-padding" suffix.
        float padding = Float.parseFloat(System.getProperty("megamek.gpu.showcase.padding",
              String.valueOf(BoardGeometry.DEFAULT_PADDING)));
        String suffix = padding > 0 ? "-padding" : transitions ? "-transitions" : "";
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-showcase");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder report = new StringBuilder();
        boolean measure = Boolean.getBoolean("megamek.gpu.showcase.measure");
        Map<String, String> baseline = new HashMap<>();
        String baselineDirectory = System.getProperty("megamek.gpu.showcase.baseline", "");
        if (measure && !baselineDirectory.isBlank()) {
            try (var files = Files.list(Path.of(baselineDirectory))) {
                for (Path file : files.filter(p -> p.toString().matches(".*\\.(glsl|frag|vert)$")).toList()) {
                    baseline.put(file.getFileName().toString(), Files.readString(file));
                }
            }
            assertTrue(!baseline.isEmpty(), "The comparison needs saved shader sources");
        }
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        if (measure) {
            config.useVsync(false);
            config.setForegroundFPS(0);
        }
        new Lwjgl3Application(new ApplicationAdapter() {
            private final GpuShaderManager shaders = new GpuShaderManager();

            @Override
            public void create() {
                try { shaders.run(this::capture); }
                finally { shaders.close(); }
            }

            private void capture() {
                GpuTerrain terrain = new GpuTerrain();
                GL20 rawGl20 = Gdx.gl20;
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                GpuReviewFrame frame = new GpuReviewFrame(settings(hours.get(0)));
                try {
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                    if (measure) {
                        report.append("1440x1080; 24 warmup + 40 samples with glFinish; profiler excluded.\n")
                              .append("Terrain, plants, shadows and atmosphere only; not full-game FPS.\n");
                    }
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (String name : families.split(",")) {
                        BoardScene.Surface family = BoardScene.Surface.valueOf(name.trim().toUpperCase(Locale.ROOT));
                        BoardScene scene = scene(family);
                        long start = System.nanoTime();
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, .8f,
                              BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE, transitions, padding));
                        terrain.update(scene);
                        report.append(String.format(Locale.ROOT, "%s: build %.1f ms%n", family,
                              (System.nanoTime() - start) / 1e6));
                        for (View view : VIEWS) {
                            if (!views.get(0).isBlank() && !views.contains(view.name())) { continue; }
                            BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, view.grid(),
                                  BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE, transitions, padding));
                            terrain.update(scene);
                            camera.setIsometric(view.isometric());
                            if (view.rotation() != 0 || view.tilt() != 0) { camera.orbit(view.rotation(), view.tilt()); }
                            camera.camera.zoom = view.zoom();
                            camera.center(BoardGeometry.center(view.focus(), view.level()));
                            for (String at : hours) {
                                frame.configure(settings(at));
                                if (measure) {
                                    int rounds = baseline.isEmpty() ? 1 : 2;
                                    for (int round = 0; round < rounds; round++) {
                                        if (!baseline.isEmpty()) {
                                            var change = shaders.apply(baseline);
                                            assertTrue(change.success(), change.message());
                                            report.append(String.format(Locale.ROOT, "  %s h%s baseline%d: %s%n",
                                                  view.name(), at, round, measure(frame, terrain, camera, scene)));
                                            change = shaders.apply(Map.of());
                                            assertTrue(change.success(), change.message());
                                        }
                                        report.append(String.format(Locale.ROOT, "  %s h%s current%d: %s%n", view.name(),
                                              at, round, measure(frame, terrain, camera, scene)));
                                    }
                                }
                                profiler.reset();
                                profiler.enable();
                                frame.render(terrain, camera, scene);
                                GpuStageTimings.stopCounting(profiler, rawGl20);
                                report.append(String.format(Locale.ROOT, "  %s: draws=%d vertices=%.0f%n",
                                      view.name(), profiler.getDrawCalls(), profiler.getVertexCount().total));
                                GpuReviewFrame.save(new File(output, family.name().toLowerCase(Locale.ROOT) + "-"
                                      + view.name() + suffix + (series.isBlank() ? "" : "-h" + at.trim()) + ".png"));
                            }
                        }
                    }
                    Files.writeString(new File(output, "report.txt").toPath(), report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuStageTimings.stopCounting(profiler, rawGl20);
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain showcase", failure.get()); }
        System.out.print(report);
    }

    /** Settled terrain, shadows and atmosphere; no GLProfiler, file capture, UI or presentation in the timed frames. */
    private static String measure(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        double[] samples = new double[40];
        for (int i = -24; i < samples.length; i++) {
            long start = System.nanoTime();
            frame.render(terrain, camera, scene);
            Gdx.gl.glFinish();
            if (i >= 0) { samples[i] = (System.nanoTime() - start) / 1e6; }
        }
        Arrays.sort(samples);
        return String.format(Locale.ROOT, "medianMs=%.3f p95Ms=%.3f", samples[20], samples[38]);
    }

    private static BoardAtmosphere.Settings settings(String hour) {
        float clouds = Float.parseFloat(System.getProperty("megamek.gpu.showcase.clouds", "0"));
        return new BoardAtmosphere.Settings(Float.parseFloat(hour.trim()), clouds, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    static BoardScene scene(BoardScene.Surface family) {
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                boolean water = cell == 'w' || cell == 'W';
                int level = switch (cell) {
                    case 'a' -> -1;
                    case 'b' -> -2;
                    case 'w', 'W' -> 0;
                    default -> cell - '0';
                };
                int depth = cell == 'w' ? 1 : cell == 'W' ? 2 : -1;
                Coords coords = new Coords(x, y);
                var features = new ArrayList<>(woods(WOODS[y].charAt(x), level, family, coords));
                if (Boolean.getBoolean("megamek.gpu.showcase.rough") && x == 12 && (y == 4 || y == 6)) {
                    Hex rough = new Hex(level);
                    rough.addTerrain(new Terrain(Terrains.ROUGH, 1));
                    features.addAll(BoardFeatures.capture(rough, coords, Map.of()));
                }
                var tile = BoardScenes.newTile(coords, level, depth, false, 0, family, features, List.of(),
                      water ? BoardLiquid.WATER : BoardLiquid.NONE, null);
                tiles.add(family == BoardScene.Surface.LUNAR ? tile.lunar(true) : tile);
            }
        }
        assertTrue(tiles.size() == width * height);
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The trees the game captures for woods of this density in this family, with the default foliage height. */
    private static List<BoardScene.Feature> woods(char mark, int level, BoardScene.Surface family, Coords coords) {
        if (mark == '.') { return List.of(); }
        int density = mark == 'l' ? 1 : mark == 'h' ? 2 : 3;
        Hex hex = new Hex(level);
        hex.addTerrain(new Terrain(Terrains.WOODS, density));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
        switch (family) {
            case SAND -> hex.addTerrain(new Terrain(Terrains.SAND, 1));
            case SNOW -> hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            case CONCRETE -> hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            case ROCK -> hex.setTheme("rock");
            case DIRT -> hex.setTheme("dirt");
            case DESERT -> hex.setTheme("desert");
            case MARS -> hex.setTheme("mars");
            case VOLCANO -> hex.setTheme("volcano");
            case LUNAR -> { return List.of(); }
            default -> { }
        }
        return BoardFeatures.capture(hex, coords, Map.of());
    }
}
