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
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real shader and material binding, including triple junctions and material roles through elevation changes. */
@Tag("on-demand")
class GpuTerrainBlendSmokeTest {
    @Test
    void capturesNaturalTransitions() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-blend");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    var mixed = BoardScenes.scene(c -> BoardScenes.tile(c,
                          c.getX() < 4 ? BoardScene.Surface.SAND : c.getY() < 4 ? BoardScene.Surface.SNOW : BoardScene.Surface.GRASS,
                          0, -1, 0));
                    capture(mixed, "mixed", terrain, frame, camera);
                    var mixedStep = BoardScenes.scene(c -> BoardScenes.tile(c,
                                c.getX() < 4 ? BoardScene.Surface.GRASS : BoardScene.Surface.SNOW,
                                c.getX() < 4 ? 1 : 0, -1, 0));
                    capture(mixedStep, "mixed-step", terrain, frame, camera);
                    terrain.setNormalMaps(false);
                    capture(mixedStep, "mixed-step-no-normals", terrain, frame, camera);
                    capture(mixed, "mixed-no-normals", terrain, frame, camera);
                    terrain.setNormalMaps(true);
                    terrain.setWetness(1);
                    capture(mixed, "mixed-wet", terrain, frame, camera);
                    terrain.setWetness(0);
                    var families = List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND,
                          BoardScene.Surface.ROCK, BoardScene.Surface.SNOW);
                    capture(BoardScenes.scene(c -> BoardScenes.tile(c,
                                families.get(Math.floorMod(c.getX() + 2 * c.getY(), families.size())), 0, -1, 0)),
                          "crowded", terrain, frame, camera);
                    // Successive snapshots on the same renderer exercise neighbour invalidation after a local edit.
                    for (var patch : List.of(BoardScene.Surface.SNOW, BoardScene.Surface.SAND)) {
                        var island = BoardScenes.scene(c -> BoardScenes.tile(c,
                              c.equals(BoardScenes.CENTER) ? patch : BoardScene.Surface.GRASS, 0, -1, 0));
                        capture(island, "island-" + patch.name().toLowerCase(java.util.Locale.ROOT), terrain, frame, camera);
                        if (patch == BoardScene.Surface.SAND) { compareRebuild(island, terrain, frame, camera); }
                    }
                    for (int depth : new int[] { 0, 1 }) {
                        var shores = BoardScenes.shores(depth, false);
                        terrain.update(shores);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .13f;
                        camera.center(BoardGeometry.center(BoardScenes.WATER, 0));
                        frame.render(terrain, camera, shores);
                        GpuReviewFrame.save(new File(output, "mixed-shores-close-depth" + depth + ".png"));
                    }
                    for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW)) {
                        var steps = BoardScenes.scene(c -> BoardScenes.tile(c, family,
                              c.getY() < 3 ? 4 : c.getY() < 5 ? 1 : 0, -1, 0));
                        capture(steps,
                              "steps-" + family.name().toLowerCase(java.util.Locale.ROOT), terrain, frame, camera);
                        if (family == BoardScene.Surface.GRASS) {
                            camera.camera.zoom = .16f;
                            camera.center(BoardGeometry.center(new Coords(4, 3), 1));
                            frame.render(terrain, camera, steps);
                            GpuReviewFrame.save(new File(output, "grass-cliff-foot.png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }

            private void capture(BoardScene scene, String name, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera) throws Exception {
                terrain.update(scene);
                terrain.animate(.5f, List.of());
                for (boolean oblique : new boolean[] { false, true }) {
                    camera.setIsometric(oblique);
                    camera.camera.zoom = .42f;
                    camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                    renderSettled(terrain, frame, camera, scene);
                    GpuReviewFrame.save(new File(output, name + (oblique ? "-oblique" : "-top") + ".png"));
                }
            }

            private void compareRebuild(BoardScene scene, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera) throws Exception {
                // The edited renderer keeps its shadow projection while that still covers the board
                // (GpuTerrain#canReuseShadowCamera), so it may still be fitted to an earlier scene, on another texel
                // grid than a clean build's. A shadow pass under another sun drops that projection, so the next frame
                // fits a new one to this board, as a clean build does.
                terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(15, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
                terrain.renderShadows(camera.camera, List.of());
                frame.render(terrain, camera, scene);
                byte[] edited = screen();
                GpuReviewFrame.save(new File(output, "island-sand-edited.png"));
                var fresh = new GpuTerrain();
                try {
                    fresh.update(scene);
                    var clock = GpuTerrain.class.getDeclaredField("clock");
                    clock.setAccessible(true);
                    fresh.animate(clock.getFloat(terrain), List.of());
                    renderSettled(fresh, frame, camera, scene);
                    byte[] rebuilt = screen();
                    GpuReviewFrame.save(new File(output, "island-sand-rebuilt.png"));
                    long error = 0;
                    int large = 0;
                    for (int i = 0; i < edited.length; i++) {
                        int difference = Math.abs(Byte.toUnsignedInt(edited[i]) - Byte.toUnsignedInt(rebuilt[i]));
                        error += difference;
                        if (difference > 4) { large++; }
                    }
                    assertTrue(error / (double) edited.length < .1 && large < edited.length / 1000,
                          "Edited cover/scatter differs from a clean build: mean byte error " + error / (double) edited.length);
                } finally {
                    fresh.dispose();
                }
            }

            private void renderSettled(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) throws Exception {
                // Ground cover is planted with the terrain: once the terrain is installed, one frame shows it completely.
                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                frame.render(terrain, camera, scene);
            }

            private byte[] screen() {
                var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                try {
                    byte[] result = new byte[pixels.getPixels().remaining()];
                    pixels.getPixels().get(result);
                    return result;
                } finally {
                    pixels.dispose();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Natural material transition review", failure.get()); }
    }
}
