/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The vertical shore at 0815/0915 on Mountain Lake (Savannah), above and below the water surface. */
@Tag("on-demand")
class GpuLakeCliffSmokeTest {
    @Test
    void rendersTheVerticalCliffIntoTheWaterbedWithoutDryStreaks() {
        var failure = new AtomicReference<Throwable>();
        var original = BoardRelief.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardScenes.tune(true);
                    var scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
                    terrain.update(scene);
                    terrain.animate(.4f, List.of());
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                          "lake-cliff-contact");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    for (int angle : new int[] { 0, 30, -30, 90 }) {
                        var camera = new BoardCamera();
                        camera.resize(1440, 900);
                        camera.setIsometric(true);
                        camera.orbit(angle, -10);
                        camera.camera.zoom = .12f;
                        camera.center(BoardGeometry.center(new Coords(7, 14), 0));
                        camera.update();
                        for (boolean water : new boolean[] { true, false }) {
                            frame.render(terrain, camera, scene, water);
                            GpuReviewFrame.save(new File(output, "shore-" + angle + (water ? "-water" : "-bed") + ".png"));
                            if (angle == 0) { assertUnderwaterWall(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardRelief.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Mountain Lake submerged cliff", failure.get()); }
    }

    private static void assertUnderwaterWall() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, 1440, 900);
        try {
            // Dry ground projection used to stretch vertical brown stripes across these submerged wall faces.
            for (int[] point : new int[][] { { 940, 360 }, { 1075, 411 }, { 1150, 440 }, { 870, 360 } }) {
                int pixel = image.getPixel(point[0], 899 - point[1]);
                int red = pixel >>> 24, green = (pixel >>> 16) & 255, blue = (pixel >>> 8) & 255;
                // The wet tint has green leading red; brown dry rock, as those stripes were, has red leading.
                assertTrue(green > red,
                      "Submerged wall lost its waterbed shading at " + point[0] + "," + point[1]
                            + ": RGB " + red + "," + green + "," + blue);
            }
            int dry = image.getPixel(1000, 899 - 260);
            assertTrue((dry >>> 24) > ((dry >>> 8) & 255), "The exposed sandstone must keep its dry material");
        } finally {
            image.dispose();
        }
    }
}
