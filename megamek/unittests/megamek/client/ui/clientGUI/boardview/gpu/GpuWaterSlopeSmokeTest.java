/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native review of the cross-channel shoulders on one- and two-level sloping water. */
@Tag("on-demand")
class GpuWaterSlopeSmokeTest {
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void capturesTheWholeDescentFromBothBanksAndAbove(boolean cliffs) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              cliffs ? "cliffs" : "banks");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 960);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var original = BoardRelief.tuning();
                BoardScenes.tune(cliffs);
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                try {
                    BoardScene scene = BoardScenes.dropScene(BoardScene.Surface.GRASS);
                    terrain.update(scene);
                    terrain.animate(.4f, List.of());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1280, 960);
                    for (int drop : new int[] { 1, 2 }) {
                        Vector3 focus = BoardGeometry.center(new Coords(drop == 1 ? 1 : 4, 2), drop)
                              .lerp(BoardGeometry.center(new Coords(drop == 1 ? 1 : 4, 3), 0), .5f);
                        capture(terrain, frame, camera, scene, focus, output, "water-slope-" + drop);
                    }
                    scene = BoardScenes.mapScene(BoardScene.Surface.GRASS);
                    terrain.update(scene);
                    terrain.animate(.4f, List.of());
                    Vector3 focus = BoardGeometry.center(new Coords(8, 9), 2)
                          .lerp(BoardGeometry.center(new Coords(9, 9), 0), .5f);
                    capture(terrain, frame, camera, scene, focus, output, "mountain-lake-slope");
                    scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
                    terrain.update(scene);
                    terrain.animate(.4f, List.of());
                    capture(terrain, frame, camera, scene, focus, output, "mountain-lake-sand");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    BoardRelief.tune(original);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Sloping water review", failure.get()); }
    }

    private static void capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          Vector3 focus, File output, String name) {
        for (boolean perspective : new boolean[] { false, true }) {
            camera.setPerspective(perspective);
            for (int angle : new int[] { -35, 35, 0, -65, 65 }) {
                camera.setIsometric(angle != 0);
                camera.orbit(angle, angle == 0 ? 0 : Math.abs(angle) == 65 ? 10 : -15);
                camera.camera.zoom = .15f;
                camera.center(focus);
                camera.update();
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, name + "-"
                      + (perspective ? "perspective" : "ortho") + "-" + angle + ".png"));
                if (name.equals("mountain-lake-sand")) {
                    if (perspective && angle == 35) { assertSubmergedRockTint(); }
                    frame.render(terrain, camera, scene, false);
                    GpuReviewFrame.save(new File(output, name + "-bed-"
                          + (perspective ? "perspective" : "ortho") + "-" + angle + ".png"));
                    if (perspective && angle == 35) {
                        assertSubmergedRockTint();
                        // Close view of the reported left wall beside the 0910/1010 descent.
                        camera.camera.zoom = .075f;
                        camera.center(new Vector3(574, -723, 8));
                        camera.update();
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, name + "-contact-close.png"));
                    }
                }
            }
        }
    }

    private static void assertSubmergedRockTint() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
        try {
            // The land-owned cliff above the receiving hex's level, the submerged foot, and the projecting rocks.
            // Check with water both visible and hidden: dry cliff faces showed as brown holes through the stream.
            boolean cliffs = BoardRelief.tuning().cliffsIntoWater();
            int[][] submerged = cliffs ? new int[][] { { 500, 520 }, { 490, 530 }, { 507, 505 },
                  { 468, 630 }, { 455, 670 }, { 651, 540 } } : new int[][] { { 651, 540 } };
            for (int[] point : submerged) {
                int pixel = image.getPixel(point[0], 959 - point[1]);
                int red = pixel >>> 24, green = (pixel >>> 16) & 255, blue = (pixel >>> 8) & 255;
                // Shadowed moss can keep blue below red even with the correct wet tint. The green lead rejects the
                // red/brown dry cliff holes this check guards against.
                assertTrue(green > red,
                      "Submerged rock must share the bed's tint at " + point[0] + "," + point[1]
                            + ": RGB " + red + "," + green + "," + blue);
            }
            int exposed = image.getPixel(491, 959 - 400);
            assertTrue((exposed >>> 24) > ((exposed >>> 8) & 255), "Exposed sandstone retains its dry colour");
            if (!cliffs) {
                for (int[] point : new int[][] { { 500, 520 }, { 490, 530 }, { 468, 630 } }) {
                    int pixel = image.getPixel(point[0], 959 - point[1]);
                    assertTrue((pixel >>> 24) > ((pixel >>> 8) & 255),
                          "The exposed bank outside the stream must not acquire a blue water stain");
                }
            }
        } finally {
            image.dispose();
        }
    }
}
