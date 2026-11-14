/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native regression for the dense, stationary-looking foam on Jungle River 1's level torrent. */
@Tag("on-demand")
class GpuRapidsFoamSmokeTest {
    @Test
    void torrentsLeaveOpenWaterBetweenMovingFoamPatches() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/unofficial/Drewbacca/16x17 Jungle River 1.board"), false, true);
        assertEquals(2, board.getHex(8, 1).terrainLevel(Terrains.RAPIDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                BoardAtmosphere.Settings settings = BoardAtmosphere.DEFAULTS;
                StringBuilder report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(settings);
                try {
                    assertTrue(output.isDirectory() || output.mkdirs());
                    terrain.setWind(BoardAtmosphere.Effects.NONE);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1280, 900);
                    List<String> findings = new ArrayList<>();
                    for (boolean perspective : new boolean[] { false, true }) {
                        camera.setPerspective(perspective);
                        camera.setIsometric(perspective);
                        camera.camera.zoom = perspective ? .55f : .65f;
                        camera.center(BoardGeometry.center(new Coords(8, 3), 0));
                        camera.update();
                        double ordinary = 0;
                        for (int severity = 0; severity <= 2; severity++) {
                            BoardScene scene = scene(board, severity);
                            terrain.update(scene);
                            terrain.animate(.4f, List.of());
                            frame.render(terrain, camera, scene);
                            String name = "jungle-river-1-" + (perspective ? "perspective" : "top")
                                  + "-rapids-" + severity;
                            GpuReviewFrame.save(new File(output, name + ".png"));
                            int[] first = samples(camera);
                            double bright = brightFraction(first);
                            terrain.animate(.4f, List.of());
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, name + "-later.png"));
                            int[] later = samples(camera);
                            int changed = 0;
                            for (int i = 0; i < first.length; i++) {
                                if (Math.abs((first[i] >>> 24) - (later[i] >>> 24)) > 10) { changed++; }
                            }
                            report.append(String.format(Locale.ROOT, "%s: bright %.3f, changed %.3f%n", name,
                                  bright, changed / (double) first.length));
                            if (severity == 0) { ordinary = bright; }
                            if (severity == 2) {
                                if (bright < ordinary + .025) {
                                    findings.add(name + ": torrent must remain visibly foamy");
                                }
                                if (bright > .25) {
                                    findings.add(name + ": dense bright foam hides too much water (" + bright + ")");
                                }
                                if (changed < first.length * .08) {
                                    findings.add(name + ": foam must visibly evolve in still water");
                                }
                            }
                        }
                    }
                    Files.writeString(new File(output, "rapids-foam.txt").toPath(), report);
                    System.out.print(report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    assertTrue(findings.isEmpty(), String.join("\n", findings));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Rapids foam rendering", failure.get()); }
    }

    /** Sample the channel interior in world space, away from the banks and depth transition. */
    private static int[] samples(BoardCamera camera) {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            int[] pixels = new int[3 * 2 * 21 * 21];
            int index = 0;
            for (int column = 7; column <= 9; column++) {
                for (int row = 1; row <= 2; row++) {
                    Vector3 center = BoardGeometry.center(new Coords(column, row), 0);
                    for (int y = -20; y <= 20; y += 2) {
                        for (int x = -20; x <= 20; x += 2) {
                            Vector3 point = camera.camera.project(new Vector3(center)
                                  .add(x, y, -BoardGeometry.HEX_SCALE));
                            pixels[index++] = image.getPixel(Math.round(point.x), Math.round(point.y));
                        }
                    }
                }
            }
            return pixels;
        } finally {
            image.dispose();
        }
    }

    private static double brightFraction(int[] pixels) {
        int bright = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) > 150 && ((pixel >>> 16) & 255) > 170 && ((pixel >>> 8) & 255) > 170) { bright++; }
        }
        return bright / (double) pixels.length;
    }

    /** Preserve the shipped map's terrain and depth; only vary its authored rapids for the comparison. */
    private static BoardScene scene(Board board, int severity) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = board.getHex(x, y);
                int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1;
                BoardLiquid liquid = BoardLiquid.capture(hex);
                if (liquid.rapids() > 0) { liquid = new BoardLiquid(liquid.kind(), liquid.theme(), severity); }
                tiles.add(BoardScenes.newTile(new Coords(x, y), hex.getLevel(), depth, false, 0,
                      BoardFeatures.surface(hex), List.of(), List.of(), liquid,
                      null));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }
}
