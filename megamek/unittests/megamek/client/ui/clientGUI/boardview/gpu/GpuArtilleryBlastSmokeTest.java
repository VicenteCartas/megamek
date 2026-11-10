/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders every artillery blast shape at several moments onto one review sheet, seen from a low angle over a grey
 * ground like the reference sheet the effect was drawn from, and checks the blast actually draws, burns, cools and
 * clears.
 */
@Tag("on-demand")
class GpuArtilleryBlastSmokeTest {
    private static final int SIZE = 256;
    private static final float[] MOMENTS = { .08f, .35f, .9f, 1.8f, 3.2f, 4.8f };
    private static final GpuArtilleryBlast.Style[] STYLES = { GpuArtilleryBlast.Style.CROWN,
          GpuArtilleryBlast.Style.MUSHROOM, GpuArtilleryBlast.Style.FIREBALL, GpuArtilleryBlast.Style.CROWN,
          GpuArtilleryBlast.Style.CROWN, GpuArtilleryBlast.Style.CLUSTER, GpuArtilleryBlast.Style.MINES };
    /** The R value of each row: a Sniper for the three shapes, Copperhead, Long Tom, its cluster round, FASCAM. */
    private static final int[] HEX_RADII = { 1, 1, 1, 0, 2, 1, 0 };

    @Test
    void drawsEachBlastShapeFromIgnitionToClearing() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var volumes = new GpuExplosionEffects();
                var blast = new GpuArtilleryBlast();
                var batch = new ModelBatch(GpuUnitShader.provider());
                Model groundModel = null;
                GpuSceneTestOutput buffer = null;
                try {
                    buffer = new GpuSceneTestOutput(SIZE, SIZE);
                    groundModel = new ModelBuilder().createBox(2000, 2000, 1,
                          new Material(ColorAttribute.createDiffuse(.3f, .3f, .31f, 1)), VertexAttributes.Usage.Position);
                    var ground = new ModelInstance(groundModel);
                    ground.transform.setToTranslation(0, 0, -.5f);
                    float size = GpuArtilleryBlast.size(null);
                    var camera = new PerspectiveCamera(40, SIZE, SIZE);
                    // Wide enough to show a Long Tom's debris landing two hexes out.
                    camera.position.set(0, -size * 11, size * 2.4f);
                    camera.up.set(Vector3.Z);
                    camera.lookAt(0, 0, size * .7f);
                    camera.near = 1;
                    camera.far = size * 40;
                    camera.update();
                    volumes.setLightDirection(new Vector3(-.3f, .5f, -1).nor());
                    var sheet = new BufferedImage(SIZE * MOMENTS.length, SIZE * STYLES.length, BufferedImage.TYPE_INT_RGB);
                    var graphics = sheet.createGraphics();
                    File folder = new File(System.getProperty("megamek.gpu.screenshots"));
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    var empty = frame(buffer, batch, ground, camera, volumes, () -> { });
                    try {
                        for (int row = 0; row < STYLES.length; row++) {
                            GpuArtilleryBlast.Style style = STYLES[row];
                            int hexRadius = HEX_RADII[row];
                            long firstWarmth = 0;
                            for (int column = 0; column < MOMENTS.length; column++) {
                                float seconds = MOMENTS[column];
                                var image = frame(buffer, batch, ground, camera, volumes,
                                      () -> blast.emit(volumes, Vector3.Zero, size, hexRadius, style, seconds, 41));
                                if (column == 0) {
                                    firstWarmth = warmth(image) - warmth(empty);
                                    // Kept beside the sheet, so a shape that fails here can be looked at.
                                    ImageIO.write(image, "png", new File(folder,
                                          "artillery-ignition-" + style + ".png"));
                                    long drawn = different(image, empty);
                                    // A minefield round only pops its mines out, far smaller than any fiery blast.
                                    long minimum = style == GpuArtilleryBlast.Style.MINES ? 40 : 500;
                                    assertTrue(drawn > minimum, style + " must draw at ignition, changed pixels " + drawn);
                                    var repeated = frame(buffer, batch, ground, camera, volumes,
                                          () -> blast.emit(volumes, Vector3.Zero, size, hexRadius, style, seconds, 41));
                                    assertTrue(Arrays.equals(pixels(image), pixels(repeated)),
                                          style + " must not flicker while paused");
                                }
                                if (column == MOMENTS.length - 1) {
                                    assertTrue(warmth(image) - warmth(empty) < firstWarmth, style + " must cool to smoke");
                                }
                                graphics.drawImage(image, column * SIZE, row * SIZE, null);
                                graphics.setColor(java.awt.Color.WHITE);
                                graphics.drawString(style + " R" + hexRadius + " " + seconds + "s", column * SIZE + 8, row * SIZE + 16);
                            }
                            var cleared = frame(buffer, batch, ground, camera, volumes,
                                  () -> blast.emit(volumes, Vector3.Zero, size, hexRadius, style, GpuArtilleryBlast.SECONDS, 41));
                            assertTrue(Arrays.equals(pixels(cleared), pixels(empty)), style + " must clear completely");
                        }
                    } finally { graphics.dispose(); }
                    File output = new File(System.getProperty("megamek.gpu.screenshots"), "artillery-blast-review.png");
                    assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
                    ImageIO.write(sheet, "png", output);
                    verifyFlatVolumeAtFrustumEdge(buffer, volumes);
                    reviewCluster(buffer, batch, ground, volumes, blast, size, output.getParentFile());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    volumes.dispose();
                    batch.dispose();
                    if (groundModel != null) { groundModel.dispose(); }
                    if (buffer != null) { buffer.close(); }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Artillery blast review failed", failure.get()); }
    }

    /**
     * A close-up of a Long Tom cluster round from its air burst to its haze, at an angle and from straight above, so
     * the spread of the pops over the target hex and its ring can be judged.
     */
    private static void reviewCluster(GpuSceneTestOutput buffer, ModelBatch batch, ModelInstance ground,
          GpuExplosionEffects volumes, GpuArtilleryBlast blast, float size, File folder) throws java.io.IOException {
        float[] moments = { .05f, .2f, .4f, .6f, .8f, 1.1f, 1.6f, 2.6f };
        float hex = BoardGeometry.height();
        var oblique = new PerspectiveCamera(40, SIZE, SIZE);
        oblique.position.set(0, -hex * 4.2f, hex * 2.2f);
        oblique.lookAt(0, 0, hex * .5f);
        var above = new PerspectiveCamera(40, SIZE, SIZE);
        above.position.set(0, -.01f, hex * 6.5f);
        above.lookAt(0, 0, 0);
        PerspectiveCamera[] cameras = { oblique, above };
        var sheet = new BufferedImage(SIZE * moments.length, SIZE * cameras.length, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            for (int row = 0; row < cameras.length; row++) {
                PerspectiveCamera camera = cameras[row];
                camera.up.set(Vector3.Z);
                camera.near = 1;
                camera.far = size * 40;
                camera.update();
                for (int column = 0; column < moments.length; column++) {
                    float seconds = moments[column];
                    var image = frame(buffer, batch, ground, camera, volumes,
                          () -> blast.emit(volumes, Vector3.Zero, size, 1, GpuArtilleryBlast.Style.CLUSTER, seconds, 41));
                    graphics.drawImage(image, column * SIZE, row * SIZE, null);
                    graphics.setColor(java.awt.Color.WHITE);
                    graphics.drawString((row == 0 ? "Cluster " : "From above ") + seconds + "s", column * SIZE + 8,
                          row * SIZE + 16);
                }
            }
        } finally { graphics.dispose(); }
        ImageIO.write(sheet, "png", new File(folder, "artillery-cluster-review.png"));
        // The animations blow in a moderate eastward wind, so the drift of the smoke can be judged.
        volumes.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, .6f, 90));
        writeAnimation(buffer, batch, ground, volumes, blast, size, oblique, GpuArtilleryBlast.Style.CLUSTER, 1,
              new File(folder, "artillery-cluster-frames"));
        // Wide enough for a Long Tom's debris landing two rings out.
        var wide = new PerspectiveCamera(40, SIZE, SIZE);
        wide.position.set(0, -hex * 6.5f, hex * 2.6f);
        wide.up.set(Vector3.Z);
        wide.lookAt(0, 0, hex * .4f);
        wide.near = 1;
        wide.far = size * 40;
        wide.update();
        writeAnimation(buffer, batch, ground, volumes, blast, size, wide, GpuArtilleryBlast.Style.CROWN, 2,
              new File(folder, "artillery-longtom-frames"));
        // Close on the single hex a minefield round seeds.
        var close = new PerspectiveCamera(40, SIZE, SIZE);
        close.position.set(0, -hex * 2.4f, hex * 1.5f);
        close.up.set(Vector3.Z);
        close.lookAt(0, 0, hex * .3f);
        close.near = 1;
        close.far = size * 40;
        close.update();
        writeAnimation(buffer, batch, ground, volumes, blast, size, close, GpuArtilleryBlast.Style.MINES, 0,
              new File(folder, "artillery-mines-frames"));
        // A homing round with no TAG to follow: a dud's dirt burst.
        writeAnimation(buffer, batch, ground, volumes, blast, size, close, GpuArtilleryBlast.Style.DUD, 0,
              new File(folder, "artillery-dud-frames"));
    }

    /** Numbered frames at 15 a second over the blast's life, for stitching into an animation to review. */
    private static void writeAnimation(GpuSceneTestOutput buffer, ModelBatch batch, ModelInstance ground,
          GpuExplosionEffects volumes, GpuArtilleryBlast blast, float size, PerspectiveCamera camera,
          GpuArtilleryBlast.Style style, int hexRadius, File folder) throws java.io.IOException {
        assertTrue(folder.isDirectory() || folder.mkdirs());
        int frames = Math.round(GpuArtilleryBlast.SECONDS * 15);
        for (int index = 0; index < frames; index++) {
            float seconds = index / 15f;
            var image = frame(buffer, batch, ground, camera, volumes,
                  () -> blast.emit(volumes, Vector3.Zero, size, hexRadius, style, seconds, 41));
            ImageIO.write(image, "png", new File(folder, String.format("frame-%03d.png", index)));
        }
    }

    private static BufferedImage frame(GpuSceneTestOutput buffer, ModelBatch batch, ModelInstance ground,
          PerspectiveCamera camera, GpuExplosionEffects volumes, Runnable enqueue) {
        buffer.begin(.62f, .63f, .65f, 1);
        try {
            batch.begin(camera);
            batch.render(ground);
            batch.end();
            volumes.begin();
            enqueue.run();
            volumes.render(camera);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            buffer.display();
            return snapshot();
        } finally { buffer.pixels().end(); }
    }

    /** A shallow blast still has its full horizontal extent when its centre has left the view. */
    private static void verifyFlatVolumeAtFrustumEdge(GpuSceneTestOutput output, GpuExplosionEffects volumes) {
        var camera = new OrthographicCamera(200, 200);
        camera.position.set(0, 0, 400);
        camera.up.set(Vector3.Y);
        camera.lookAt(Vector3.Zero);
        camera.near = 1;
        camera.far = 1000;
        camera.update();
        var centre = new Vector3(115, 0, 20);
        assertFalse(camera.frustum.pointInFrustum(centre), "The blast centre is deliberately outside the view");
        assertTrue(camera.frustum.pointInFrustum(90, 0, 20), "Part of its horizontal lobe remains visible");
        output.begin(0, 0, 0, 1);
        output.display();
        BufferedImage empty = snapshot();
        output.begin(0, 0, 0, 1);
        volumes.begin();
        volumes.addFlat(centre, 40, .25f, 1, 1, 73, GpuExplosionEffects.Look.PLAIN, .1f);
        var profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        try {
            volumes.render(camera);
            assertEquals(2, profiler.getDrawCalls(),
                  "The XY-visible flat volume must reach its draw call, and its layer the scene");
        } finally {
            profiler.disable();
        }
        output.display();
        assertTrue(different(snapshot(), empty) > 0, "The submitted horizontal lobe must also produce visible pixels");
        output.pixels().end();
    }

    private static BufferedImage snapshot() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
        try {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) { image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8); }
            }
            return image;
        } finally { pixels.dispose(); }
    }

    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE); }

    private static long warmth(BufferedImage image) {
        return Arrays.stream(pixels(image)).mapToLong(pixel -> Math.max(0, (pixel >> 16 & 255) - (pixel & 255))).sum();
    }

    private static long different(BufferedImage left, BufferedImage right) {
        int[] leftPixels = pixels(left);
        int[] rightPixels = pixels(right);
        long count = 0;
        for (int index = 0; index < leftPixels.length; index++) {
            if (leftPixels[index] != rightPixels[index]) { count++; }
        }
        return count;
    }
}
