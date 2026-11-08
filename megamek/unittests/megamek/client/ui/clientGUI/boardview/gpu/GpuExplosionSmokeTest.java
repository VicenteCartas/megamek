/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
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
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native shader/depth boundary and repeatable visual evidence for the shared camera-independent effect. */
@Tag("on-demand")
class GpuExplosionSmokeTest {
    private static final int SIZE = 256;

    @Test
    void drawsProceduralVolumesWithOpaqueOcclusionAndRepeatablePlayback() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var effects = new GpuExplosionEffects();
                Model wall = null;
                GpuSceneTestOutput buffer = null;
                ModelBatch batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    var missile = RigidGlb.loadLods(new FileHandle(new File(Configuration.dataDir(),
                          "models/effects/missile-body.glb"))).getFirst();
                    assertEquals(4, UnitModelDescriptor.triangleCount(missile));
                    buffer = new GpuSceneTestOutput(SIZE, SIZE);
                    var top = new OrthographicCamera(24, 24);
                    top.position.set(0, 0, 30);
                    top.up.set(Vector3.Y);
                    top.lookAt(Vector3.Zero);
                    top.near = .1f;
                    top.far = 100;
                    top.update();
                    var free = new PerspectiveCamera(45, SIZE, SIZE);
                    free.position.set(16, -20, 12);
                    free.up.set(Vector3.Z);
                    free.lookAt(Vector3.Zero);
                    free.near = .1f;
                    free.far = 100;
                    free.update();
                    var sheet = new BufferedImage(SIZE * 6, SIZE * 2, BufferedImage.TYPE_INT_RGB);
                    var graphics = sheet.createGraphics();
                    try {
                        for (int row = 0; row < 2; row++) {
                            Camera camera = row == 0 ? top : free;
                            BufferedImage early = frame(buffer, effects, camera, .08f, 1, null, batch);
                            BufferedImage repeated = frame(buffer, effects, camera, .08f, 1, null, batch);
                            assertTrue(Arrays.equals(pixels(early), pixels(repeated)), "A paused attack must not evolve");
                            assertTrue(changed(early) > 500, "Fire must actually draw in both cameras");
                            BufferedImage smoke = frame(buffer, effects, camera, .8f, 0, null, batch);
                            assertTrue(changed(smoke) > 500, "Smoke must remain visible");
                            assertTrue(warmth(early) > warmth(smoke) + 5000, "Hot emission must cool to smoke");
                            float[] ages = { .02f, .16f, .4f, .7f, .93f };
                            String[] labels = { "Ignition", "Expansion", "Cooling", "Smoke", "Dissipation" };
                            for (int column = 0; column < ages.length; column++) {
                                float age = ages[column];
                                var burst = frame(buffer, effects, camera, null, batch, Color.BLACK,
                                      () -> effects.burst(Vector3.Zero, 7, age, 17));
                                graphics.drawImage(burst, column * SIZE, row * SIZE, null);
                                graphics.setColor(java.awt.Color.LIGHT_GRAY);
                                graphics.drawString(labels[column], column * SIZE + 12, (row + 1) * SIZE - 12);
                            }
                            effects.setSmokeLight(new Color(.1f, .15f, .25f, 1));
                            graphics.drawImage(frame(buffer, effects, camera, null, batch, Color.BLACK,
                                  () -> effects.burst(Vector3.Zero, 7, .7f, 17)), SIZE * 5, row * SIZE, null);
                            graphics.drawString("Smoke in low light", SIZE * 5 + 12, (row + 1) * SIZE - 12);
                            effects.setSmokeLight(Color.WHITE);
                            var exhausted = frame(buffer, effects, camera, null, batch, Color.BLACK,
                                  () -> effects.burst(Vector3.Zero, 7, 1, 17));
                            assertEquals(0, changed(exhausted), "The shared clock must exhaust both fire and smoke");
                        }
                    } finally { graphics.dispose(); }
                    File output = new File(System.getProperty("megamek.gpu.screenshots"), "explosion-volume-review.png");
                    assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
                    ImageIO.write(sheet, "png", output);
                    verifyWind(buffer, effects, batch, output.getParentFile());
                    wall = new ModelBuilder().createBox(24, 24, 1,
                          new Material(ColorAttribute.createDiffuse(.1f, .2f, .3f, 1)), VertexAttributes.Usage.Position);
                    var occluder = new ModelInstance(wall);
                    occluder.transform.setToTranslation(0, 0, 15);
                    BufferedImage hidden = frame(buffer, effects, top, .1f, 1, occluder, batch);
                    BufferedImage original = frame(buffer, effects, top, .1f, 0, occluder, batch, false);
                    assertTrue(Arrays.equals(pixels(original), pixels(hidden)), "Opaque geometry must completely hide the volume");
                    effects.setWind(wind(1, 90));
                    assertTrue(Arrays.equals(pixels(original), pixels(frame(buffer, effects, top, .75f, 0, occluder, batch))),
                          "Opaque depth must clip the stretched, wind-displaced volume too");
                    effects.setWind(BoardAtmosphere.Effects.NONE);
                    occluder.transform.setToTranslation(12, 0, 15);
                    BufferedImage partial = frame(buffer, effects, top, .1f, 1, occluder, batch);
                    BufferedImage partialWall = frame(buffer, effects, top, .1f, 0, occluder, batch, false);
                    long visible = different(partial, partialWall);
                    assertTrue(visible > 100 && visible < changed(frame(buffer, effects, top, .1f, 1, null, batch)) * .75,
                          "The wall must clip only the covered portion of the volume");
                    BufferedImage whole = frame(buffer, effects, top, .8f, 0, null, batch);
                    top.near = 35;
                    top.update();
                    BufferedImage clipped = frame(buffer, effects, top, .8f, 0, null, batch);
                    assertTrue(changed(clipped) > 100 && changed(clipped) < changed(whole) * .7,
                          "Ray integration must start at the camera near plane");
                    top.near = .1f;
                    top.update();
                    var background = new Color(.6f, .65f, .7f, 1);
                    BufferedImage clear = frame(buffer, effects, top, null, batch, background, () -> { });
                    BufferedImage opaqueSmoke = frame(buffer, effects, top, null, batch, background,
                          () -> effects.add(Vector3.Zero, 7, .8f, 0, 1, 17));
                    BufferedImage thinSmoke = frame(buffer, effects, top, null, batch, background,
                          () -> effects.add(Vector3.Zero, 7, .8f, 0, .1f, 17));
                    assertTrue(brightness(opaqueSmoke) < brightness(thinSmoke) && brightness(thinSmoke) < brightness(clear),
                          "Smoke must absorb the background and thin out as density falls");
                    free.position.set(Vector3.Zero);
                    free.direction.set(0, 1, 0);
                    free.update();
                    assertTrue(changed(frame(buffer, effects, free, .4f, 1, null, batch)) > 500,
                          "A camera inside the proxy must still see the volume");
                    free.far = 3;
                    free.update();
                    assertTrue(changed(frame(buffer, effects, free, .4f, 1, null, batch)) > 500,
                          "The coverage mesh must still draw when the far plane is inside the volume");
                    effects.begin();
                    for (int i = 0; i < GpuExplosionEffects.CAPACITY + 1; i++) {
                        effects.add(Vector3.Zero, 1, .5f, 1, 1, i);
                    }
                    assertEquals(GpuExplosionEffects.CAPACITY, effects.size());
                    effects.begin();
                    assertEquals(0, effects.size(), "Cancelling/clearing leaves no retained particles");
                    verifyDeathPlayback(buffer);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    effects.dispose();
                    if (wall != null) { wall.dispose(); }
                    if (buffer != null) { buffer.close(); }
                    batch.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Explosion shader review failed", failure.get()); }
    }

    private static BufferedImage frame(GpuSceneTestOutput buffer, GpuExplosionEffects effects, Camera camera,
          float age, float fire, ModelInstance wall, ModelBatch batch) {
        return frame(buffer, effects, camera, age, fire, wall, batch, true);
    }

    private static BufferedImage frame(GpuSceneTestOutput buffer, GpuExplosionEffects effects, Camera camera,
          float age, float fire, ModelInstance wall, ModelBatch batch, boolean draw) {
        return frame(buffer, effects, camera, wall, batch, Color.BLACK,
              () -> { if (draw) { effects.add(Vector3.Zero, 7, age, fire, 1, 17); } });
    }

    private static BufferedImage frame(GpuSceneTestOutput buffer, GpuExplosionEffects effects, Camera camera,
          ModelInstance wall, ModelBatch batch, Color background, Runnable enqueue) {
        buffer.begin(background.r, background.g, background.b, background.a);
        try {
            if (wall != null) { batch.begin(camera); batch.render(wall); batch.end(); }
            effects.begin();
            enqueue.run();
            effects.render(camera);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            return snapshot(buffer);
        } finally { FrameBuffer.unbind(); }
    }

    private static void verifyDeathPlayback(GpuSceneTestOutput buffer) {
        var effects = new GpuAttackEffects();
        effects.setWind(wind(.4f, 60));
        var unit = UnitPlaybackFixture.unit(1, 0);
        var attack = new UnitAttack(UnitPlaybackFixture.attack(unit, unit, ResolvedAttack.Kind.DEATH, true));
        var center = UnitAttack.center(null, unit.location(), new Vector3());
        var camera = new OrthographicCamera(56, 56);
        camera.position.set(center).add(0, 0, 60);
        camera.up.set(Vector3.Y);
        camera.lookAt(center);
        camera.near = .1f;
        camera.far = 100;
        camera.update();
        try {
            attack.seconds = attack.duration * .4f;
            effects.update(attack, null, Map.of());
            buffer.begin(0, 0, 0, 1);
            effects.render(camera);
            var burning = snapshot(buffer);
            assertTrue(changed(burning) > 500, "Destruction playback must use the volume renderer");
            buffer.begin(0, 0, 0, 1);
            effects.render(camera);
            assertTrue(Arrays.equals(pixels(burning), pixels(snapshot(buffer))), "Pausing the attack preserves its fire and smoke");
            attack.seconds = attack.duration * .75f;
            buffer.begin(0, 0, 0, 1);
            effects.render(camera);
            assertTrue(warmth(snapshot(buffer)) < warmth(burning), "The same attack clock cools the blast into smoke");
            effects.update((UnitAttack) null, null, Map.of());
            buffer.begin(0, 0, 0, 1);
            effects.render(camera);
            assertEquals(0, changed(snapshot(buffer)), "Skipping playback must clear the complete effect");
            attack.seconds = attack.duration;
            effects.update(attack, null, Map.of());
            buffer.begin(0, 0, 0, 1);
            effects.render(camera);
            assertEquals(0, changed(snapshot(buffer)), "Completed playback must not leave retained smoke");
        } finally { FrameBuffer.unbind(); effects.dispose(); }
    }

    private static BufferedImage snapshot(GpuSceneTestOutput buffer) {
        buffer.display();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
        try {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) { image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8); }
            }
            return image;
        } finally { pixels.dispose(); }
    }

    private static void verifyWind(GpuSceneTestOutput buffer, GpuExplosionEffects effects, ModelBatch batch,
          File output) throws java.io.IOException {
        var top = new OrthographicCamera(40, 40);
        top.position.set(0, 0, 30);
        top.up.set(Vector3.Y);
        var free = new PerspectiveCamera(45, SIZE, SIZE);
        free.position.set(24, -30, 18);
        free.up.set(Vector3.Z);
        Camera[] cameras = { top, free };
        BoardAtmosphere.Effects[] weather = { wind(0, 0), wind(.2f, 90), wind(1, 90), wind(1, 270), wind(1, 0), wind(1, 180) };
        String[] labels = { "Calm", "Light eastward wind", "Strong eastward wind", "Strong westward wind",
              "Strong northward wind", "Strong southward wind" };
        var sheet = new BufferedImage(SIZE * weather.length, SIZE * cameras.length, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            for (int row = 0; row < cameras.length; row++) {
                Camera camera = cameras[row];
                camera.lookAt(Vector3.Zero);
                camera.near = .1f;
                camera.far = 100;
                camera.update();
                var frames = new BufferedImage[weather.length];
                for (int column = 0; column < weather.length; column++) {
                    effects.setWind(weather[column]);
                    frames[column] = frame(buffer, effects, camera, .75f, 0, null, batch);
                    graphics.drawImage(frames[column], column * SIZE, row * SIZE, null);
                    graphics.setColor(java.awt.Color.LIGHT_GRAY);
                    graphics.drawString(labels[column], column * SIZE + 12, (row + 1) * SIZE - 12);
                }
                if (row == 0) {
                    double calmX = centroid(frames[0], true), calmY = centroid(frames[0], false);
                    assertTrue(centroid(frames[1], true) > calmX + 1, "A light wind must displace released smoke");
                    assertTrue(centroid(frames[2], true) > centroid(frames[1], true) + 8, "Stronger wind must drift farther");
                    assertTrue(centroid(frames[3], true) < calmX - 8, "Reversing wind must reverse world-space drift");
                    assertTrue(centroid(frames[4], false) < calmY - 8 && centroid(frames[5], false) > calmY + 8,
                          "North/south must use the same travel convention as board weather");
                }
                effects.setWind(wind(0, 237));
                assertTrue(Arrays.equals(pixels(frames[0]), pixels(frame(buffer, effects, camera, .75f, 0, null, batch))),
                      "Wind bearing must have no effect when strength is zero");
                effects.setWind(weather[2]);
                assertTrue(Arrays.equals(pixels(frames[2]), pixels(frame(buffer, effects, camera, .75f, 0, null, batch))),
                      "A paused wind-driven plume must be repeatable in either camera");
            }
            ImageIO.write(sheet, "png", new File(output, "explosion-wind-review.png"));
        } finally { graphics.dispose(); effects.setWind(BoardAtmosphere.Effects.NONE); }
    }

    private static BoardAtmosphere.Effects wind(float strength, float direction) {
        return new BoardAtmosphere.Effects(0, 0, 0, 0, 0, strength, direction);
    }

    private static double centroid(BufferedImage image, boolean horizontal) {
        long total = 0, weighted = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int pixel = image.getRGB(x, y);
                int weight = (pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255);
                total += weight;
                weighted += (long) weight * (horizontal ? x : y);
            }
        }
        return total == 0 ? 0 : weighted / (double) total;
    }

    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE); }
    private static long changed(BufferedImage image) { return Arrays.stream(pixels(image)).filter(p -> (p & 0xffffff) != 0).count(); }
    private static long warmth(BufferedImage image) {
        return Arrays.stream(pixels(image)).mapToLong(p -> Math.max(0, (p >> 16 & 255) - (p & 255))).sum();
    }

    private static long brightness(BufferedImage image) {
        return Arrays.stream(pixels(image)).mapToLong(p -> (p >> 16 & 255) + (p >> 8 & 255) + (p & 255)).sum();
    }

    private static long different(BufferedImage left, BufferedImage right) {
        int[] a = pixels(left), b = pixels(right);
        return java.util.stream.IntStream.range(0, a.length).filter(i -> a[i] != b[i]).count();
    }
}
