/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders a Long Tom illumination round's flare (radius 3) over a dark board with coloured blocks standing in for
 * units, at night and by day, and a frame sequence of it burning, for review; checks its light reaches the hexes in
 * its radius and not those beyond.
 */
@Tag("on-demand")
class GpuStarShellsSmokeTest {
    private static final int SIZE = 480;

    @Test
    void lightsTheHexesInItsRadius() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var batch = new ModelBatch(GpuUnitShader.provider());
                var starShells = new GpuStarShells();
                var depth = new GpuEffectDepth();
                List<Model> models = new ArrayList<>();
                GpuSceneTestOutput buffer = null;
                try {
                    buffer = new GpuSceneTestOutput(SIZE, SIZE);
                    Coords flare = new Coords(8, 8);
                    Vector3 centre = BoardGeometry.center(flare, 0);
                    float hex = BoardGeometry.height();
                    var builder = new ModelBuilder();
                    Model groundModel = builder.createBox(hex * 20, hex * 20, 1,
                          new Material(ColorAttribute.createDiffuse(.32f, .36f, .28f, 1)),
                          VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                    models.add(groundModel);
                    var ground = new ModelInstance(groundModel);
                    ground.transform.setToTranslation(centre.x, centre.y, -.5f);
                    List<ModelInstance> scene = new ArrayList<>(List.of(ground));
                    // A row of coloured blocks east of the flare, one per hex out to 6: lit to 3, dark beyond.
                    Color[] colours = { Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
                          Color.MAGENTA };
                    for (int step = 0; step <= 6; step++) {
                        Model block = builder.createBox(hex * .3f, hex * .3f, hex * .4f,
                              new Material(ColorAttribute.createDiffuse(colours[step])),
                              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                        models.add(block);
                        Vector3 at = BoardGeometry.center(new Coords(flare.getX() + step, flare.getY()), 0);
                        var instance = new ModelInstance(block);
                        instance.transform.setToTranslation(at.x, at.y, hex * .2f);
                        scene.add(instance);
                    }
                    var camera = new PerspectiveCamera(45, SIZE, SIZE);
                    camera.position.set(centre.x + hex * 1.5f, centre.y - hex * 9, hex * 6);
                    camera.up.set(Vector3.Z);
                    camera.lookAt(centre.x + hex * 1.5f, centre.y, 0);
                    camera.near = 1;
                    camera.far = hex * 60;
                    camera.update();
                    var shells = scene(new BoardScene.StarShell(flare, 3, 5, true));
                    starShells.update(shells, Map.of(), 3);
                    verifySceneLight(buffer, batch, ground, camera, starShells, depth, centre);
                    File folder = new File(System.getProperty("megamek.gpu.screenshots"));
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    BufferedImage dark = frame(buffer, batch, scene, camera, starShells, depth, 0, false, .12f);
                    BufferedImage night = frame(buffer, batch, scene, camera, starShells, depth, 0, true, .12f);
                    BufferedImage day = frame(buffer, batch, scene, camera, starShells, depth, 1, true, 1);
                    ImageIO.write(dark, "png", new File(folder, "star-shell-night-unlit.png"));
                    ImageIO.write(night, "png", new File(folder, "star-shell-night.png"));
                    ImageIO.write(day, "png", new File(folder, "star-shell-day.png"));
                    assertLit(night, dark, camera, flare, hex);
                    // A second flare two hexes east: where the pools overlap the ground is no brighter than under one.
                    float single = brightening(night, dark, camera, new Coords(flare.getX() + 1, flare.getY()), hex);
                    var pair = new GpuStarShells();
                    try {
                        pair.update(scene(new BoardScene.StarShell(flare, 3, 5, true),
                              new BoardScene.StarShell(new Coords(flare.getX() + 2, flare.getY()), 3, 5, true)),
                              Map.of(), 3);
                        BufferedImage both = frame(buffer, batch, scene, camera, pair, depth, 0, true, .12f);
                        ImageIO.write(both, "png", new File(folder, "star-shell-overlap.png"));
                        float overlap = brightening(both, dark, camera, new Coords(flare.getX() + 1, flare.getY()), hex);
                        System.out.println("[StarShell] under one flare " + single + ", under two " + overlap);
                        assertEquals(single, overlap, single * .08f, "Two flares light a hex no more than one");
                    } finally {
                        pair.dispose();
                    }
                    // Close on the parachute, from the side and a little below, to review the canopy.
                    var close = new PerspectiveCamera(35, SIZE, SIZE);
                    Vector3 hang = centre.cpy().add(0, 0, hex * GpuStarShells.HANG_HEXES + hex * .12f);
                    close.position.set(hang.x + hex * .5f, hang.y - hex * .9f, hang.z + hex * .25f);
                    close.up.set(Vector3.Z);
                    close.lookAt(hang);
                    close.near = .1f;
                    close.far = hex * 60;
                    close.update();
                    ImageIO.write(frame(buffer, batch, scene, close, starShells, depth, 0, true, .12f), "png",
                          new File(folder, "star-shell-canopy.png"));
                    File frames = new File(folder, "star-shell-frames");
                    assertTrue(frames.isDirectory() || frames.mkdirs());
                    for (int index = 0; index < 45; index++) {
                        starShells.update(shells, Map.of(), 1 / 15f);
                        ImageIO.write(frame(buffer, batch, scene, camera, starShells, depth, 0, true, .12f), "png",
                              new File(frames, String.format("frame-%03d.png", index)));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    starShells.dispose();
                    depth.dispose();
                    batch.dispose();
                    models.forEach(Model::dispose);
                    if (buffer != null) {
                        buffer.close();
                    }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Star shell review failed", failure.get());
        }
    }

    /** The block three hexes out is lit and the one five hexes out is not. */
    private static void assertLit(BufferedImage lit, BufferedImage unlit, PerspectiveCamera camera, Coords flare,
          float hex) {
        float inside = brightening(lit, unlit, camera, new Coords(flare.getX() + 3, flare.getY()), hex);
        float outside = brightening(lit, unlit, camera, new Coords(flare.getX() + 5, flare.getY()), hex);
        System.out.println("[StarShell] block 3 hexes out brightened " + inside + ", 5 hexes out " + outside);
        assertTrue(inside > 1.5f, "In its radius the light brightens: " + inside);
        assertTrue(outside < 1.05f, "Beyond it the light does not reach: " + outside);
    }

    private static float brightening(BufferedImage lit, BufferedImage unlit, PerspectiveCamera camera, Coords hex,
          float size) {
        Vector3 at = BoardGeometry.center(hex, 0).add(0, 0, size * .25f);
        camera.project(at, 0, 0, SIZE, SIZE);
        int x = Math.round(at.x), y = SIZE - 1 - Math.round(at.y);
        return (luminance(lit.getRGB(x, y)) + 1) / (luminance(unlit.getRGB(x, y)) + 1);
    }

    private static float luminance(int rgb) {
        return .3f * (rgb >> 16 & 255) + .59f * (rgb >> 8 & 255) + .11f * (rgb & 255);
    }

    /** The same light must multiply stored radiance, add canonical light, and leave coverage unchanged. */
    private static void verifySceneLight(GpuSceneTestOutput output, ModelBatch batch, ModelInstance ground,
          PerspectiveCamera camera, GpuStarShells shells, GpuEffectDepth depth, Vector3 centre) {
        output.begin(0, 0, 0, 1);
        batch.begin(camera);
        batch.render(ground);
        batch.end();
        Vector3 at = camera.project(centre.cpy(), 0, 0, SIZE, SIZE);
        int x = Math.round(at.x), y = Math.round(at.y);
        assertTrue(x >= 0 && x < SIZE && y >= 0 && y < SIZE, "The light probe must lie in the framebuffer");
        float[] red = new float[2];
        var pixel = BufferUtils.newFloatBuffer(4);
        for (int sample = 0; sample < red.length; sample++) {
            // Keep the real ground depth, replacing only its color with two controlled canonical inputs.
            float radiance = sample * .25f * GpuSceneRadiance.SCALE;
            Gdx.gl.glClearColor(radiance, radiance, radiance, .375f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            depth.begin();
            shells.renderLight(camera, depth, 0);
            pixel.clear();
            Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            red[sample] = pixel.get(0) / GpuSceneRadiance.SCALE;
            assertEquals(.375f, pixel.get(3), 0, "Illumination must not change scene coverage");
        }
        // The authored night maximum is three times .02 radiance. Half-float storage contributes <.0001 here.
        assertTrue(red[0] > 0 && red[0] <= .0601f, "The additive lift must stay in canonical units: " + red[0]);
        float gain = (red[1] - red[0]) / .25f;
        assertTrue(gain > 3.1f && gain <= 4.001f, "The dimensionless night gain must not be storage-scaled: " + gain);
        output.scene().end();
    }

    private static BufferedImage frame(GpuSceneTestOutput buffer, ModelBatch batch, List<ModelInstance> scene,
          PerspectiveCamera camera, GpuStarShells starShells, GpuEffectDepth depth, float daylight, boolean lit,
          float ambient) {
        buffer.begin(.02f * (1 + 20 * daylight), .025f * (1 + 20 * daylight),
              .04f * (1 + 15 * daylight), 1);
        try {
            var environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, ambient, ambient, ambient * 1.2f, 1));
            environment.add(new DirectionalLight().set(ambient, ambient, ambient, -.3f, .4f, -1));
            batch.begin(camera);
            batch.render(scene, environment);
            batch.end();
            depth.x = 0;
            depth.y = 0;
            depth.width = SIZE;
            depth.height = SIZE;
            depth.begin();
            if (lit) {
                starShells.renderLight(camera, depth, daylight);
                starShells.renderFlares(camera, daylight);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            buffer.display();
            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
            try {
                BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8);
                    }
                }
                return image;
            } finally {
                pixels.dispose();
            }
        } finally {
            buffer.pixels().end();
        }
    }

    private static BoardScene scene(BoardScene.StarShell... shells) {
        BoardScene base = UnitPlaybackFixture.scene();
        return new BoardScene(base.boardId(), base.width(), base.height(), base.tiles(), base.units(),
              base.plannedPath(), base.selectedId(), base.phase(), base.commands(), base.light(), base.firingLines(),
              base.rangeBorders(), base.markers(), base.tactical(), base.rangeLabels(), base.fieldOfView(),
              base.electronicWarfare(), base.designations(), List.of(shells));
    }
}
