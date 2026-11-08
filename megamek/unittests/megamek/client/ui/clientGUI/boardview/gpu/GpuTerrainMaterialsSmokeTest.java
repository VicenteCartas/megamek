/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sculpted materials in native OpenGL: grassland banks of two levels are earth while cliffs of three are rock,
 * concrete steps are cast slabs while a three-level concrete cliff is a slab on darker bedrock, and rain darkens
 * exposed ground and rock but never snow.
 */
@Tag("on-demand")
class GpuTerrainMaterialsSmokeTest {
    /** Half the edge of the sampled screen window, in pixels. */
    private static final int WINDOW = 6;

    @Test
    void rendersSculptedMaterialsAndRain() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try (var outputFrame = new GpuSceneTestOutput()) {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.camera.zoom = .18f;
                    BoardAtmosphere.Settings settings = new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                    terrain.setAtmosphere(BoardAtmosphere.lighting(settings));

                    // Grassland: the same south face as a two-level bank and as a three-level cliff.
                    float[] bank = wallColor(outputFrame, terrain, camera, step(BoardScene.Surface.GRASS, 2), 2, output, "bank");
                    float[] cliff = wallColor(outputFrame, terrain, camera, step(BoardScene.Surface.GRASS, 3), 3, output, "cliff");
                    assertTrue(bank[0] / bank[2] > cliff[0] / cliff[2] + .15f,
                          "A two-level grassland step is an earth bank, a three-level one rock: bank "
                                + ratio(bank) + ", cliff " + ratio(cliff));

                    // The same point on a cast face keeps its material when the wall grows taller.
                    BoardScenes.withTransitions(false, () -> {
                        BoardScene lowWall = step(BoardScene.Surface.CONCRETE, 2);
                        float[] low = sample(outputFrame, terrain, camera, lowWall,
                              facePoint(lowWall, 1.1f), output, "concrete-wall");
                        for (int height : new int[] { 3, 6 }) {
                            BoardScene tallWall = step(BoardScene.Surface.CONCRETE, height);
                            float[] tall = sample(outputFrame, terrain, camera, tallWall,
                                  facePoint(tallWall, 1.1f), output, "concrete-wall-" + height);
                            assertEquals(luminance(low), luminance(tall), .08f,
                                  "The foot stays cast concrete: low " + ratio(low) + ", tall " + ratio(tall));
                        }
                    });
                    BoardScene slope = step(BoardScene.Surface.CONCRETE, 2);
                    sample(outputFrame, terrain, camera, slope, facePoint(slope, 1), output, "concrete-slope");

                    checkRainAbsorption(outputFrame, camera, settings);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Sculpted materials", failure.get()); }
    }

    /** Wet pigment darkens even when a view-dependent reflection makes the final pixel brighter. */
    private static void checkRainAbsorption(GpuSceneTestOutput outputFrame, BoardCamera camera,
          BoardAtmosphere.Settings settings) {
        var manager = new GpuShaderManager();
        try {
            manager.run(() -> {
                String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
                String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
                assertTrue(sculpt.contains(result), "The absorption probe must read completed material albedo");
                var changed = manager.apply(Map.of("terrain-sculpt.frag", sculpt.replace(result,
                      result + "\nresult = surface.albedo;\n")));
                assertTrue(changed.success(), changed.message());
                GpuTerrain probe = new GpuTerrain();
                try {
                    probe.setAtmosphere(BoardAtmosphere.lighting(settings));
                    for (BoardScene.Surface family : BoardScene.Surface.values()) {
                        BoardScene scene = step(family, 3);
                        Vector3 face = facePoint(scene, 1.5f);
                        probe.setWetness(0);
                        float[] dry = sample(outputFrame, probe, camera, scene, face, null, null, true);
                        probe.setWetness(1);
                        float[] wet = sample(outputFrame, probe, camera, scene, face, null, null, true);
                        float baseline = luminance(dry);
                        assertTrue(baseline > .01f, family + " must expose actual pigment in the sampled face");
                        float retained = luminance(wet) / baseline;
                        if (family == BoardScene.Surface.SNOW) {
                            assertEquals(1, retained, .002f, "Snow never takes the liquid rain film");
                        } else {
                            // Full rain absorbs a quarter (sealed) to a half (loose ground) of the light the
                            // pigment still returns (rain-surface.glsl wetAlbedo): a dark pigment loses more of
                            // itself. Relative to that share the bound is independent of albedo calibration, exposure,
                            // sky color and specular sheen; over a textured window the mean reads slightly under a
                            // quarter.
                            for (int channel = 0; channel < 3; channel++) {
                                float absorbed = (1 - wet[channel] / dry[channel]) / (1 - dry[channel]);
                                assertTrue(absorbed >= .24f && absorbed <= .5f, family + " rain must absorb a quarter"
                                      + " to a half of the light its pigment returns: " + absorbed);
                            }
                        }
                        System.out.println(family + " rain pigment retained=" + retained);
                    }
                } finally { probe.dispose(); }
            });
        } finally { manager.close(); }
    }

    /** Rows 0-2 stand {@code levels} above rows 3-5, so the step's face looks south, toward the default camera. */
    private static BoardScene step(BoardScene.Surface family, int levels) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 6; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), y < 3 ? levels : 0, -1, false, 0, family, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 6, 6, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Mid-height of the face between hexes (2, 2) and (2, 3), averaged over a small window. */
    private static float[] wallColor(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, int levels, File output, String name) {
        return sample(outputFrame, terrain, camera, scene, facePoint(scene, levels * .5f), output, name);
    }

    /**
     * The middle of the edge between hexes (2, 2) and (2, 3) where the scene draws it (concrete straightens its level
     * boundaries, BoardConcrete), {@code levels} above the foot.
     */
    private static Vector3 facePoint(BoardScene scene, float levels) {
        BoardConcrete fit = BoardConcrete.of(scene);
        Vector3 edge = fit.corner(new Coords(2, 2), 4).lerp(fit.corner(new Coords(2, 2), 5), .5f);
        edge.z = levels * BoardGeometry.LEVEL;
        return edge;
    }

    private static float[] sample(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, Vector3 point, File output, String name) {
        return sample(outputFrame, terrain, camera, scene, point, output, name, false);
    }

    private static float[] sample(GpuSceneTestOutput outputFrame, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, Vector3 point, File output, String name, boolean radiance) {
        terrain.update(scene);
        camera.center(point);
        terrain.renderShadows(camera.camera, List.of());
        outputFrame.begin(.4f, .5f, .6f, 1);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        outputFrame.display();
        if (name != null) { GpuBoardTestUi.capture(new File(output, "terrain-materials-" + name + ".png")); }
        Vector3 screen = camera.camera.project(new Vector3(point));
        if (radiance) {
            int count = (2 * WINDOW + 1) * (2 * WINDOW + 1);
            var pixels = BufferUtils.newFloatBuffer(count * 4);
            outputFrame.scene().bind();
            Gdx.gl.glReadPixels((int) screen.x - WINDOW, (int) screen.y - WINDOW, 2 * WINDOW + 1, 2 * WINDOW + 1,
                  GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            outputFrame.pixels().bind();
            float[] sum = new float[3];
            for (int i = 0; i < count; i++) {
                for (int channel = 0; channel < 3; channel++) {
                    sum[channel] += pixels.get(i * 4 + channel) / (count * GpuSceneRadiance.SCALE);
                }
            }
            return sum;
        }
        Pixmap pixels = ScreenUtils.getFrameBufferPixmap((int) screen.x - WINDOW, (int) screen.y - WINDOW,
              2 * WINDOW + 1, 2 * WINDOW + 1);
        try {
            float[] sum = new float[3];
            int count = 0;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int rgba = pixels.getPixel(x, y);
                    sum[0] += (rgba >>> 24) / 255f;
                    sum[1] += (rgba >>> 16 & 255) / 255f;
                    sum[2] += (rgba >>> 8 & 255) / 255f;
                    count++;
                }
            }
            return new float[] { sum[0] / count, sum[1] / count, sum[2] / count };
        } finally {
            pixels.dispose();
        }
    }

    private static float luminance(float[] c) { return .2126f * c[0] + .7152f * c[1] + .0722f * c[2]; }

    private static String ratio(float[] c) { return String.format("rgb(%.3f, %.3f, %.3f)", c[0], c[1], c[2]); }
}
