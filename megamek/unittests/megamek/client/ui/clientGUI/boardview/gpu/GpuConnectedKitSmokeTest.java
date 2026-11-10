/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review renders of the connected building kits on a synthetic board: each kit in a block of its own, in its Angled and
 * its Square footprint, a hex alone, a pair across a straight hexside stepping down a level, a pair across a slanted one
 * and a seven-hex flower.
 */
@Tag("on-demand")
class GpuConnectedKitSmokeTest {
    private static final List<String> LOOKS = List.of("buildings/standard/building-light",
          "buildings/standard/fortress-light", "building/lunar-habitat");
    private static final int BLOCK = 7, WIDTH = BLOCK * 3, ROWS = 10, HEIGHT = ROWS * 2;

    @Test
    void capturesConnectedKits() throws Exception {
        float hour = Float.parseFloat(System.getProperty("megamek.gpu.boards.hour", "10"));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "connected-kits");
        Files.createDirectories(output.toPath());
        Board board = BoardFile.readNative(document());
        AtomicReference<BoardScene> scene = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.update(scene.get());
                    terrain.animate(.5f, List.of());
                    for (int shot = 0; shot < LOOKS.size() * 2; shot++) {
                        int block = shot % LOOKS.size();
                        boolean square = shot >= LOOKS.size();
                        Coords middle = new Coords(block * BLOCK + 3, 4 + (square ? ROWS : 0));
                        for (boolean isometric : new boolean[] { false, true }) {
                            camera.setIsometric(isometric);
                            camera.camera.zoom = isometric ? .62f : .75f;
                            camera.center(BoardGeometry.center(middle, 0));
                            frame.render(terrain, camera, scene.get());
                            String name = LOOKS.get(block).replaceAll(".*/", "");
                            GpuReviewFrame.save(new File(output, String.format(Locale.ROOT, "%s-%s-%s-h%s.png", name,
                                  square ? "square" : "angled", isometric ? "oblique" : "top", hour)));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Connected kit review", failure.get()); }
    }

    /** The board: per block, its buildings' hexes with their heights; every hex connects to its group's others. */
    private static String document() {
        Map<Coords, Integer> heights = new LinkedHashMap<>();
        Map<Coords, Integer> groups = new LinkedHashMap<>();
        Map<Coords, String> looks = new LinkedHashMap<>();
        int group = 0;
        // Each kit's Angled footprint in the upper rows, its Square one (HexAppearance.Footprint) in the lower.
        Map<Coords, Boolean> squares = new LinkedHashMap<>();
        for (int shot = 0; shot < LOOKS.size() * 2; shot++) {
            int block = shot % LOOKS.size(), x = block * BLOCK, y = shot >= LOOKS.size() ? ROWS : 0;
            List<Map<Coords, Integer>> buildings = new ArrayList<>();
            buildings.add(Map.of(new Coords(x + 1, y + 1), 3));
            buildings.add(Map.of(new Coords(x + 3, y + 1), 3, new Coords(x + 3, y + 2), 2));
            Coords slanted = new Coords(x + 5, y + 2);
            buildings.add(Map.of(slanted, 2, slanted.translated(1), 2));
            Coords centre = new Coords(x + 3, y + 6);
            Map<Coords, Integer> flower = new LinkedHashMap<>(Map.of(centre, 3));
            for (int direction = 0; direction < 6; direction++) { flower.put(centre.translated(direction), 2); }
            buildings.add(flower);
            for (var building : buildings) {
                for (var entry : building.entrySet()) {
                    heights.put(entry.getKey(), entry.getValue());
                    groups.put(entry.getKey(), group);
                    looks.put(entry.getKey(), LOOKS.get(block));
                    squares.put(entry.getKey(), y > 0);
                }
                group++;
            }
        }
        StringBuilder hexes = new StringBuilder();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Coords at = new Coords(x, y);
                if (!hexes.isEmpty()) { hexes.append(",\n"); }
                hexes.append(String.format(Locale.ROOT, "{\"at\":[%d,%d],\"elevation\":0", x + 1, y + 1));
                if (heights.containsKey(at)) {
                    var connections = new java.util.StringJoiner(",", "[", "]");
                    for (int direction = 0; direction < 6; direction++) {
                        if (groups.get(at).equals(groups.get(at.translated(direction)))) {
                            connections.add("\"" + List.of("n", "ne", "se", "s", "sw", "nw").get(direction) + "\"");
                        }
                    }
                    hexes.append(String.format(Locale.ROOT, ",\"components\":{\"building\":{\"type\":\"light\","
                          + "\"connections\":%s,\"height\":%d,\"cf\":15,\"appearance\":{\"variant\":\"%s\"%s}}}",
                          connections, heights.get(at), looks.get(at), squares.get(at) ? ",\"footprint\":\"square\"" : ""));
                }
                hexes.append('}');
            }
        }
        return String.format(Locale.ROOT, "{\"format\":\"megamek-board\",\"version\":%d,\"size\":{\"width\":%d,\"height\":%d},"
              + "\"hexes\":[%s]}", BoardFile.VERSION, WIDTH, HEIGHT, hexes);
    }
}
