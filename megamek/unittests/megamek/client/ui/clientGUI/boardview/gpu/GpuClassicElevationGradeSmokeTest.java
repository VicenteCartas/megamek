/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercise the Classic material and runtime toggle through actual scene draws, including its column walls. */
@Tag("on-demand")
class GpuClassicElevationGradeSmokeTest {
    private static final int SIZE = 256;
    private static final Coords CENTER = new Coords(1, 1);

    @Test
    void classicTerrainGradesImmediatelyAtPositiveAndNegativeElevations() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(SIZE, SIZE);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                try (var output = new GpuSceneTestOutput(SIZE, SIZE)) {
                    terrain.setColumns(true);
                    var camera = new OrthographicCamera(BoardGeometry.width() * 4, BoardGeometry.width() * 4);
                    camera.near = 1;
                    camera.far = 10000;
                    // Level zero first: its ungraded artwork is the reference the graded levels lift or darken.
                    double[] flat = new double[2];
                    for (int level : new int[] { 0, -3, 3 }) {
                        terrain.update(scene(level));
                        assertTrue(terrain.columns());
                        for (boolean angled : new boolean[] { false, true }) {
                            Vector3 center = BoardGeometry.center(CENTER, level);
                            camera.up.set(angled ? Vector3.Z : Vector3.Y);
                            camera.position.set(center).add(0, angled ? -500 : 0, 1000);
                            camera.lookAt(center);
                            camera.update();
                            float[] graded = frame(terrain, output, camera);
                            assertArrayEquals(graded, frame(terrain, output, camera),
                                  "Grading repeats exactly without updating the terrain");
                            // The center stays on the flat top in both views; walls may cross level zero.
                            double before = flat[angled ? 1 : 0], after = centerLuminance(graded);
                            assertTrue(after > .01, "Sample visible column artwork");
                            if (level == 0) {
                                flat[angled ? 1 : 0] = after;
                            } else {
                                assertTrue(level > 0 ? after > before * 1.01 : after < before * .99,
                                      "Classic level " + level + " (angled=" + angled + ") must "
                                            + (level > 0 ? "brighten" : "darken") + ": " + before + " -> " + after);
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Classic elevation grading", failure.get()); }
    }

    private static float[] frame(GpuTerrain terrain, GpuSceneTestOutput output, OrthographicCamera camera) {
        output.draw(terrain, camera, false);
        return output.linearPixels();
    }

    private static double centerLuminance(float[] pixels) {
        double sum = 0;
        for (int y = SIZE / 2 - 8; y < SIZE / 2 + 8; y++) {
            for (int x = SIZE / 2 - 8; x < SIZE / 2 + 8; x++) {
                int at = (y * SIZE + x) * 4;
                sum += .2126 * pixels[at] + .7152 * pixels[at + 1] + .0722 * pixels[at + 2];
            }
        }
        return sum / 256;
    }

    private static BoardScene scene(int level) {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (x / 7 + y / 6) % 2 == 0 ? 0xff806040 : 0xffc0a080);
            }
        }
        var art = new BoardScene.Pixels(image);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), level, -1, false, 0, BoardScene.Surface.DIRT, List.of(), List.of(), BoardLiquid.NONE, art));
            }
        }
        return new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
