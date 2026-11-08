/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
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
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.ResolvedAttack;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native coverage, occlusion and appearance of the shared procedural projectile batch. No model assets. */
@Tag("on-demand")
class GpuProjectileSmokeTest {
    private static final int SIZE = 192;

    @Test
    void drawsProceduralProjectilesInBothCamerasWithOcclusionAndEndOnTracers() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var effects = new GpuEffectBatch(32, "projectiles");
                var batch = new ModelBatch();
                FrameBuffer buffer = null;
                Model wall = null;
                try {
                    buffer = GpuAtmosphere.buffer(SIZE, SIZE, true);
                    wall = new ModelBuilder().createBox(100, 100, 1,
                          new Material(ColorAttribute.createDiffuse(.1f, .2f, .3f, 1)), VertexAttributes.Usage.Position);
                    var occluder = new ModelInstance(wall);
                    var top = new OrthographicCamera(20, 20);
                    top.position.set(0, 0, 30);
                    top.up.set(Vector3.Y);
                    var free = new PerspectiveCamera(45, SIZE, SIZE);
                    free.position.set(15, -20, 16);
                    free.up.set(Vector3.Z);
                    Camera[] cameras = { top, free };
                    int[] kinds = { GpuAttackEffects.TRACER, GpuAttackEffects.ENERGY_TRAIL, GpuAttackEffects.ENERGY_GLOW,
                          GpuAttackEffects.FLARE_GLOW, GpuAttackEffects.SPRAY, GpuAttackEffects.SCREEN, GpuAttackEffects.CONTACT_GLOW,
                          GpuAttackEffects.IMPACT_SPARK };
                    String[] labels = { "Tracer", "Energy trail", "Plasma", "Flare", "Spray", "Screen", "Contact", "Impact spark" };
                    var sheet = new BufferedImage(SIZE * kinds.length, SIZE * cameras.length, BufferedImage.TYPE_INT_RGB);
                    var graphics = sheet.createGraphics();
                    try {
                        for (int row = 0; row < cameras.length; row++) {
                            Camera camera = cameras[row];
                            camera.lookAt(Vector3.Zero);
                            camera.near = .1f;
                            camera.far = 100;
                            camera.update();
                            for (int column = 0; column < kinds.length; column++) {
                                int kind = kinds[column];
                                var rendered = frame(buffer, camera, effects, batch, null, () -> emit(effects, camera, kind, .37f));
                                assertTrue(changed(rendered) > 100, labels[column] + " must draw in both cameras");
                                assertTrue(Arrays.equals(pixels(rendered), pixels(frame(buffer, camera, effects, batch, null,
                                      () -> emit(effects, camera, kind, .37f)))), "Paused effects must be repeatable");
                                if (kind == GpuAttackEffects.ENERGY_GLOW) {
                                    assertTrue(warmth(rendered) < -1000, "Plasma must retain a blue/cyan halo");
                                    assertFalse(Arrays.equals(pixels(rendered), pixels(frame(buffer, camera, effects, batch, null,
                                          () -> emit(effects, camera, kind, .73f)))), "The plasma shell must follow attack phase");
                                } else if (kind == GpuAttackEffects.FLARE_GLOW || kind == GpuAttackEffects.TRACER || kind == GpuAttackEffects.IMPACT_SPARK) {
                                    assertTrue(warmth(rendered) > 1000, "Flares and tracers retain their warm light");
                                }
                                graphics.drawImage(rendered, column * SIZE, row * SIZE, null);
                                graphics.setColor(java.awt.Color.LIGHT_GRAY);
                                graphics.drawString(labels[column], column * SIZE + 12, (row + 1) * SIZE - 12);
                            }
                            occluder.transform.setToTranslation(camera.position.cpy().scl(.5f)).rotate(Vector3.Z, camera.direction);
                            var covered = frame(buffer, camera, effects, batch, occluder, () -> { });
                            for (int kind : new int[] { GpuAttackEffects.TRACER, GpuAttackEffects.ENERGY_GLOW,
                                  GpuAttackEffects.FLARE_GLOW, GpuAttackEffects.IMPACT_SPARK }) {
                                assertTrue(Arrays.equals(pixels(covered), pixels(frame(buffer, camera, effects, batch, occluder,
                                      () -> emit(effects, camera, kind, .37f)))), "Opaque geometry must occlude ribbons and glows");
                            }
                        }
                        File output = new File(System.getProperty("megamek.gpu.screenshots"), "projectile-shader-review.png");
                        assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
                        ImageIO.write(sheet, "png", output);
                    } finally { graphics.dispose(); }
                    var endOn = frame(buffer, top, effects, batch, null, () -> effects.ribbon(top,
                          new Vector3(0, 0, -3), new Vector3(0, 0, 3), 1.2f, GpuAttackEffects.TRACER, 1));
                    assertTrue(changed(endOn) > 100, "Looking along a tracer must not collapse its geometry");
                    assertEquals(0, changed(frame(buffer, top, effects, batch, null, () -> { })), "Clearing the batch removes all effects");
                    verifyBallisticImpacts(buffer);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    effects.dispose();
                    batch.dispose();
                    if (wall != null) { wall.dispose(); }
                    if (buffer != null) { buffer.dispose(); }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Procedural projectile review failed", failure.get()); }
    }

    private static void emit(GpuEffectBatch effects, Camera camera, int kind, float phase) {
        if (kind == GpuAttackEffects.TRACER || kind == GpuAttackEffects.ENERGY_TRAIL || kind == GpuAttackEffects.IMPACT_SPARK) {
            effects.ribbon(camera, new Vector3(-6, 0, 0), new Vector3(6, 0, 0), .7f, kind + phase, 1);
        } else { effects.billboard(camera, Vector3.Zero, 4, kind + phase, 1); }
    }

    private static void verifyBallisticImpacts(FrameBuffer buffer) throws java.io.IOException {
        var effects = new GpuAttackEffects();
        var attacker = UnitPlaybackFixture.unit(1, 2);
        var victim = UnitPlaybackFixture.unit(2, 0);
        var camera = new OrthographicCamera(32, 32);
        var center = UnitAttack.center(null, victim.location(), new Vector3());
        camera.position.set(center).add(14, -22, 28);
        camera.up.set(Vector3.Z);
        camera.lookAt(center);
        camera.near = .1f;
        camera.far = 100;
        camera.update();
        var profile = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), EquipmentType.get("ISAC5")));
        var sheet = new BufferedImage(SIZE * 3, SIZE * 2, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            for (boolean hit : List.of(true, false)) {
                var result = new ResolvedAttack(new UUID(34, 91), ResolvedAttack.Kind.SHOT,
                      new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
                      new UnitLocation(victim.id(), victim.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY,
                      0, "ISAC5", 0, hit, List.of(new ResolvedAttack.Mount(attacker.id(), 0)), profile);
                var armor = new UnitAttack(new BoardScene.Combat(result, attacker, victim, victim.location(),
                      new BattleArmor().isConventionalInfantry()));
                var infantry = new UnitAttack(new BoardScene.Combat(result, attacker, victim, victim.location(),
                      new ConvInfantry().isConventionalInfantry()));
                float[] ages = { .15f, .45f, .75f };
                for (int column = 0; column < ages.length; column++) {
                    armor.seconds = infantry.seconds = armor.contactSeconds + ages[column] * UnitAttack.RECOVERY_SECONDS;
                    var sparks = impactFrame(buffer, effects, camera, armor);
                    assertEquals(0, effects.explosionCount(), "Ballistic impacts must never enqueue fire or smoke volumes");
                    var flesh = impactFrame(buffer, effects, camera, infantry);
                    assertEquals(0, changed(flesh), "Conventional-infantry and unknown-terrain contacts have no ballistic impact effect");
                    if (hit) {
                        assertTrue(changed(sparks) > 100, "Armor sparks must remain visible throughout their short lifetime");
                        graphics.drawImage(sparks, column * SIZE, 0, null);
                        graphics.drawImage(flesh, column * SIZE, SIZE, null);
                        graphics.setColor(java.awt.Color.LIGHT_GRAY);
                        graphics.drawString("Battle Armor / " + ages[column], column * SIZE + 8, SIZE - 12);
                        graphics.drawString("Conventional infantry / " + ages[column], column * SIZE + 8, SIZE * 2 - 12);
                    } else {
                        assertTrue(Arrays.equals(pixels(sparks), pixels(flesh)), "Unknown terrain must not spark for either intended target");
                    }
                    assertTrue(Arrays.equals(pixels(sparks), pixels(impactFrame(buffer, effects, camera, armor))),
                          "Paused spark trajectories must be repeatable");
                }
                armor.seconds = infantry.seconds = armor.contactSeconds - .02f;
                assertTrue(Arrays.equals(pixels(impactFrame(buffer, effects, camera, armor)),
                      pixels(impactFrame(buffer, effects, camera, infantry))), "Sparks cannot precede contact");
                armor.seconds = armor.duration;
                assertEquals(0, changed(impactFrame(buffer, effects, camera, armor)), "Sparks must finish with playback");
                assertEquals(0, changed(impactFrame(buffer, effects, camera, null)), "Cancellation must leave no sparks");
            }
            var miss = new ResolvedAttack(new UUID(34, 91), ResolvedAttack.Kind.SHOT,
                  new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
                  new UnitLocation(victim.id(), victim.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY,
                  0, "ISAC5", 0, false, List.of(new ResolvedAttack.Mount(attacker.id(), 0)), profile);
            var soft = new UnitAttack(new BoardScene.Combat(miss, attacker, victim, victim.location(), true));
            var rock = new UnitAttack(new BoardScene.Combat(miss, attacker, victim, victim.location(), true));
            // The geometry test supplies real rock/concrete/water faces; this checks the render boundary with the same picked metadata.
            soft.landscape = ray -> new BoardGeometry.Hit(victim.location().coords(), ray.origin.z * ray.origin.z, false);
            rock.landscape = ray -> new BoardGeometry.Hit(victim.location().coords(), ray.origin.z * ray.origin.z, true);
            soft.seconds = rock.seconds = soft.contactSeconds + UnitAttack.RECOVERY_SECONDS * .45f;
            impactFrame(buffer, effects, camera, soft);
            var landing = effects.emissions(soft).getFirst().target();
            camera.position.set(landing).add(14, -22, 28);
            camera.lookAt(landing);
            camera.update();
            assertEquals(0, changed(impactFrame(buffer, effects, camera, soft)), "Soft terrain has no ballistic impact effect");
            assertTrue(changed(impactFrame(buffer, effects, camera, rock)) > 100,
                  "A rock/concrete miss sparks even when aimed at infantry");
            assertEquals(0, effects.explosionCount(), "Hard terrain sparks without an explosion");
            ImageIO.write(sheet, "png", new File(System.getProperty("megamek.gpu.screenshots"), "ballistic-impact-review.png"));
            camera.position.set(center).add(14, -22, 28);
            camera.lookAt(center);
            camera.viewportWidth = camera.viewportHeight = 48;
            camera.update();
            verifySparkSizes(buffer, effects, camera);
            camera.viewportWidth = camera.viewportHeight = 32;
            camera.update();
            verifyMachineGunSparks(buffer, effects, camera);
        } finally { graphics.dispose(); effects.dispose(); }
    }

    private static void verifySparkSizes(FrameBuffer buffer, GpuAttackEffects effects, Camera camera) throws java.io.IOException {
        var names = List.of("ISAC2", "ISAC5", "ISAC10", "ISAC20", "ISLRM5");
        var sheet = new BufferedImage(SIZE * names.size(), SIZE, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        var profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        int[] sparks = { 6, 9, 14, 24 };
        long previous = 0;
        try {
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                var profile = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), EquipmentType.get(name)));
                var attack = impactAttack(name, profile, false);
                attack.seconds = attack.contactSeconds + .45f * UnitAttack.RECOVERY_SECONDS;
                profiler.reset();
                var rendered = impactFrame(buffer, effects, camera, attack);
                if (profile.ballistic()) {
                    assertEquals(0, effects.explosionCount(), "Every ballistic calibre is sparks only");
                    assertEquals((sparks[index] + effects.flameParticleCount()) * 6f, profiler.getVertexCount().total,
                          "Larger racks must submit more spark quads, independently of their size");
                    assertTrue(changed(rendered) > previous, "Larger rack size must draw larger sparks at the same camera and age: " + name);
                    previous = changed(rendered);
                } else {
                    assertTrue(effects.explosionCount() > 0 && changed(rendered) > 100, "Missiles must retain their explosions");
                }
                graphics.drawImage(rendered, index * SIZE, 0, null);
                graphics.setColor(java.awt.Color.LIGHT_GRAY);
                graphics.drawString(profile.ballistic() ? "AC/" + profile.rackSize() + " / " + sparks[index] + " sparks" : "LRM5 explosion",
                      index * SIZE + 12, SIZE - 12);
            }
            ImageIO.write(sheet, "png", new File(System.getProperty("megamek.gpu.screenshots"), "ballistic-rack-size-review.png"));
        } finally { profiler.disable(); graphics.dispose(); }
    }

    private static void verifyMachineGunSparks(FrameBuffer buffer, GpuAttackEffects effects, Camera camera) throws java.io.IOException {
        float[] times = { .02f, .13f, .24f, .35f, .57f, .73f, .91f, 1.12f };
        var sheet = new BufferedImage(SIZE * times.length, SIZE * 2, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            for (boolean rapid : List.of(false, true)) {
                var gun = Mounted.createMounted(new Tank(), EquipmentType.get("ISMG"));
                gun.setRapidFire(rapid);
                var profile = ResolvedAttack.Shot.capture(gun);
                var armor = impactAttack("ISMG", profile, false);
                var infantry = impactAttack("ISMG", profile, true);
                int rounds = rapid ? 18 : 6;
                for (int round = 0; round < rounds; round++) {
                    float contact = armor.roundContactSeconds(profile, armor.roundDelay(round, profile));
                    armor.seconds = infantry.seconds = contact - .001f;
                    long before = coreBrightness(impactFrame(buffer, effects, camera, armor))
                          - coreBrightness(impactFrame(buffer, effects, camera, infantry));
                    if (round == 0) { assertEquals(0, before, "No MG spark before the first projectile arrives"); }
                    armor.seconds = infantry.seconds = contact + .008f;
                    var sparks = impactFrame(buffer, effects, camera, armor);
                    assertEquals(0, effects.explosionCount(), "MG rounds never explode");
                    long after = coreBrightness(sparks) - coreBrightness(impactFrame(buffer, effects, camera, infantry));
                    assertTrue(after > before + 100, "Each MG round must produce a new contact pulse: rapid=" + rapid + " round=" + round);
                    assertTrue(Arrays.equals(pixels(sparks), pixels(impactFrame(buffer, effects, camera, armor))),
                          "Pausing an MG burst must freeze all sparks");
                }
                for (int column = 0; column < times.length; column++) {
                    armor.seconds = armor.roundContactSeconds(profile, 0) + times[column];
                    var rendered = impactFrame(buffer, effects, camera, armor);
                    int row = rapid ? 1 : 0;
                    graphics.drawImage(rendered, column * SIZE, row * SIZE, null);
                    graphics.setColor(java.awt.Color.LIGHT_GRAY);
                    graphics.drawString((rapid ? "Rapid" : "Normal") + " / +" + times[column] + "s",
                          column * SIZE + 8, (row + 1) * SIZE - 12);
                    if (column == 5) {
                        if (rapid) { assertTrue(changed(rendered) > 100, "Rapid MG must continue producing impacts after normal fire ends"); }
                        else { assertEquals(0, changed(rendered), "Normal MG sparks must finish before the rapid burst"); }
                    }
                }
                armor.seconds = armor.contactSeconds + .14f;
                assertEquals(0, changed(impactFrame(buffer, effects, camera, armor)), "MG sparks dissipate promptly after the last round");
                assertEquals(0, changed(impactFrame(buffer, effects, camera, null)), "Cancelling a burst clears every spark");
            }
            ImageIO.write(sheet, "png", new File(System.getProperty("megamek.gpu.screenshots"), "machine-gun-impact-review.png"));
        } finally { graphics.dispose(); }
    }

    private static UnitAttack impactAttack(String weapon, ResolvedAttack.Shot profile, boolean infantry) {
        var attacker = UnitPlaybackFixture.unit(1, 2);
        var victim = UnitPlaybackFixture.unit(2, 0);
        var result = new ResolvedAttack(new UUID(34, 91), ResolvedAttack.Kind.SHOT,
              new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
              new UnitLocation(victim.id(), victim.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY,
              0, weapon, 0, true, List.of(new ResolvedAttack.Mount(attacker.id(), 0)), profile);
        return new UnitAttack(new BoardScene.Combat(result, attacker, victim, victim.location(), infantry));
    }

    /** Brightness at contact separates each new flash from older outward streaks and ongoing tracer flight. */
    private static long coreBrightness(BufferedImage image) {
        long light = 0;
        for (int y = SIZE / 2 - 4; y <= SIZE / 2 + 4; y++) {
            for (int x = SIZE / 2 - 4; x <= SIZE / 2 + 4; x++) {
                int pixel = image.getRGB(x, y);
                light += (pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255);
            }
        }
        return light;
    }

    private static BufferedImage impactFrame(FrameBuffer buffer, GpuAttackEffects effects, Camera camera, UnitAttack attack) {
        buffer.begin();
        try {
            ScreenUtils.clear(Color.BLACK, true);
            effects.update(attack, null, Map.of());
            effects.render(camera);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            return snapshot();
        } finally { buffer.end(); }
    }

    private static BufferedImage frame(FrameBuffer buffer, Camera camera, GpuEffectBatch effects, ModelBatch batch,
          ModelInstance wall, Runnable enqueue) {
        buffer.begin();
        try {
            ScreenUtils.clear(Color.BLACK, true);
            if (wall != null) { batch.begin(camera); batch.render(wall); batch.end(); }
            effects.begin();
            enqueue.run();
            effects.render(camera, 0);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            return snapshot();
        } finally { buffer.end(); }
    }

    private static BufferedImage snapshot() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
        try {
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) { image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8); }
            }
            return image;
        } finally { pixels.dispose(); }
    }

    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE); }
    private static long changed(BufferedImage image) { return Arrays.stream(pixels(image)).filter(p -> (p & 0xffffff) != 0).count(); }
    private static long warmth(BufferedImage image) {
        return Arrays.stream(pixels(image)).mapToLong(p -> (p >> 16 & 255) - (p & 255)).sum();
    }
}
