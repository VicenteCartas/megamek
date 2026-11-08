/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.ProneCause;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuDefensePlaybackSmokeTest {
    @Test
    void counterFireStopsResolvedMissilesAndWaterFallsRenderInBothCameras() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var library = new GpuUnitModels();
                var effects = new GpuAttackEffects();
                var splash = new GpuWaterImpacts();
                try (var renderer = new GpuPlaybackReview.ReviewRenderer()) {
                    renderer.camera.viewportWidth = 340;
                    renderer.camera.viewportHeight = 255;
                    var tileset = new MekTileset(Configuration.unitImagesDir());
                    tileset.loadFromFile("mekset.txt");
                    var entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
                    entity.setId(9805);
                    var launcher = entity.getWeaponList().stream().filter(gun -> gun.getType().hasFlag(megamek.common.equipment.WeaponType.F_MISSILE)
                          && gun.getType().getRackSize() == 20).findFirst().orElseThrow();
                    for (String name : List.of("ISLaserAMS", "ISAMS", "ISAPDS")) {
                        var counter = (WeaponMounted) entity.addEquipment(EquipmentType.get(name), Mek.LOC_LEFT_ARM);
                        var selection = UnitModelSelection.capture(entity, -1, false, tileset);
                        var model = library.get(selection, entity.getId());
                        var source = withModel(UnitDefensePlaybackTest.unit(1, 3, 0, ProneCause.NONE), selection);
                        var target = withModel(UnitDefensePlaybackTest.unit(2, 0, 0, ProneCause.NONE), selection);
                        var sourceModel = new ModelInstance(model.instance.model);
                        var targetModel = new ModelInstance(model.instance.model);
                        model.place(sourceModel, renderer.camera, BoardGeometry.center(source.location().coords(), 0), 0, source);
                        model.place(targetModel, renderer.camera, BoardGeometry.center(target.location().coords(), 0), 180, target);
                        for (boolean fallback : List.of(false, true)) {
                            var id = UUID.randomUUID();
                            var incoming = combat(source, target, launcher, ResolvedAttack.Shot.capture(launcher).withResolution(null, 8).withInterception(id, 6));
                            var defense = combat(target, source, counter, ResolvedAttack.Shot.capture(counter).asDefensive().withInterception(id, 0));
                            var playback = new UnitPlayback();
                            playback.accept(List.of(defense, incoming), UnitPlaybackFixture.scene(source, target), ignored -> false);
                            playback.advance(0, UnitMotion.Speed.NORMAL);
                            var attack = playback.attacks().stream().filter(shot -> !shot.defensive()).findFirst().orElseThrow();
                            int maxBursts = 0, laserFrames = 0;
                            for (int frame = 0; frame < 57; frame++) {
                                playback.advance(1.0 / 60 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
                                effects.update(playback.attacks(), fallback ? null : library, Map.of("1:-1", sourceModel, "2:-1", targetModel));
                                effects.render(renderer.camera);
                                maxBursts = Math.max(maxBursts, effects.airburstCount());
                                var launches = effects.missileLaunches(attack);
                                int expected = 0;
                                for (var launch : launches) {
                                    for (int missile = 0; missile < launch.missiles(); missile++) {
                                        float progress = GpuMissileEffects.progress(launch, missile, attack.seconds);
                                        if (progress >= 0 && progress < launch.endProgress(missile)) { expected++; }
                                    }
                                }
                                assertEquals(expected, effects.missileCount(), "Intercepted missile geometry must disappear immediately");
                                for (var beam : effects.interceptionBeams()) {
                                    assertEquals("ISLaserAMS", name);
                                    assertTrue(launches.stream().anyMatch(launch -> java.util.stream.IntStream.range(0, launch.missiles())
                                          .filter(launch::intercepted).anyMatch(missile -> GpuMissileEffects.position(launch, missile,
                                                Math.min(launch.endProgress(missile), GpuMissileEffects.progress(launch, missile, attack.seconds)),
                                                new Vector3()).epsilonEquals(beam.target(), .001f))), "The beam must end on the actual missile");
                                    laserFrames++;
                                }
                                if (!fallback && frame >= 20 && frame <= 40 && frame % 4 == 0) {
                                    for (boolean top : List.of(false, true)) {
                                        renderer.topView = top;
                                        renderer.frame(List.of(sourceModel, targetModel), BoardGeometry.center(source.location().coords(), 0)
                                                    .lerp(BoardGeometry.center(target.location().coords(), 0), .5f), effects,
                                              "interception-" + name + (top ? "-top" : "-iso"), frame);
                                    }
                                }
                            }
                            assertEquals(6, maxBursts);
                            assertEquals(name.equals("ISLaserAMS"), laserFrames > 0);
                        }
                    }
                    var selection = UnitModelSelection.capture(entity, -1, false, tileset);
                    var model = library.get(selection, entity.getId());
                    var before = withModel(UnitDefensePlaybackTest.unit(1, 0, 0, ProneCause.NONE), selection);
                    var after = withModel(UnitDefensePlaybackTest.unit(1, 1, -1, ProneCause.FORCED), selection);
                    var scene = UnitDefensePlaybackTest.scene(after);
                    var motion = new UnitMotion(before.location());
                    motion.append(List.of(before.location(), after.location()), EntityMovementType.MOVE_NONE, 0);
                    var instance = new ModelInstance(model.instance.model);
                    var animator = new UnitAnimator();
                    int maxSpray = 0;
                    for (int frame = 0; frame < 100; frame++) {
                        motion.advance(1.0 / 60, 1);
                        animator.apply(model, instance, after, motion.sample(), frame / 60f, 1f / 60, false, 0);
                        model.place(instance, renderer.camera, motion.position(), motion.facing(), after);
                        for (boolean top : List.of(false, true)) {
                            renderer.topView = top;
                            waterFrame(renderer, scene, instance, splash, Map.of(1, motion), frame);
                            maxSpray = Math.max(maxSpray, splash.particleCount());
                        }
                        if (frame == 30) {
                            var torso = UnitBounds.subtree(instance.getNode("CT")).mul(instance.transform);
                            var contact = splash.contact(1);
                            assertNotNull(contact);
                            assertTrue(contact.x >= torso.min.x && contact.x <= torso.max.x
                                  && contact.y >= torso.min.y && contact.y <= torso.max.y,
                                  "The splash must extend with the entering torso rather than being left behind");
                            var placement = instance.transform.cpy();
                            float water = BoardGeometry.waterZ(scene.tile(after.location().coords()));
                            for (boolean top : List.of(false, true)) {
                                renderer.topView = top;
                                instance.transform.set(placement).trn(0, 0, water - torso.getCenterZ());
                                waterFrame(renderer, scene, instance, splash, Map.of(1, motion), -1);
                                assertEquals(1, splash.rippleCount(), "A partially immersed body makes a surface ripple");
                                var bounds = UnitBounds.world(instance);
                                instance.transform.trn(0, 0, water - bounds.max.z - 1);
                                waterFrame(renderer, scene, instance, splash, Map.of(1, motion), -1);
                                assertEquals(0, splash.rippleCount(), "A fully submerged body must not leave a ripple");
                                assertTrue(splash.particleCount() > 0, "Previously emitted entry spray may finish fading");
                                bounds = UnitBounds.world(instance);
                                instance.transform.trn(0, 0, water - bounds.min.z + 1);
                                waterFrame(renderer, scene, instance, splash, Map.of(1, motion), -1);
                                assertEquals(0, splash.rippleCount(), "A body clear above the surface makes no ripple");
                            }
                            instance.transform.set(placement);
                        }
                    }
                    assertTrue(maxSpray > 20, "A water fall must create visible spray and foam");
                    motion.finish();
                    splash.render(renderer.camera, scene, Map.of(1, motion), Map.of("1:-1", instance), Color.WHITE);
                    assertEquals(0, splash.particleCount());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { splash.dispose(); effects.dispose(); library.dispose(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Defense/fall playback failed", failure.get()); }
    }

    private static BoardScene.Unit withModel(BoardScene.Unit unit, BoardScene.UnitModel model) {
        return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), unit.location(), null, false, null, 2, false, model, 0);
    }

    private static BoardScene.Combat combat(BoardScene.Unit source, BoardScene.Unit target, WeaponMounted gun, ResolvedAttack.Shot shot) {
        var raw = UnitDefensePlaybackTest.combat(source, target, shot).result();
        var result = new ResolvedAttack(raw.id(), raw.kind(), raw.attacker(), raw.target(), raw.targetType(), gun.getEquipmentNum(),
              gun.getType().getInternalName(), gun.getLocation(), true,
              List.of(new ResolvedAttack.Mount(source.id(), gun.getEquipmentNum(), shot)), shot);
        return new BoardScene.Combat(result, source, target, target.location());
    }

    private static void waterFrame(GpuPlaybackReview.ReviewRenderer renderer, BoardScene scene, ModelInstance model,
          GpuWaterImpacts splash, Map<Integer, UnitMotion> motions, int frame) {
        var center = BoardGeometry.center(scene.units().getFirst().location().coords(), 0);
        renderer.begin();
        renderer.camera.viewportWidth = 180;
        renderer.camera.viewportHeight = 135;
        renderer.camera.position.set(center).add(renderer.topView ? new Vector3(0, 0, 260) : renderer.viewOffset);
        renderer.camera.up.set(renderer.topView ? Vector3.Y : Vector3.Z);
        renderer.camera.lookAt(center);
        renderer.camera.update();
        renderer.batch.begin(renderer.camera);
        renderer.batch.render(model, renderer.light);
        renderer.batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        renderer.lines.setProjectionMatrix(renderer.camera.combined);
        renderer.lines.begin(ShapeRenderer.ShapeType.Filled);
        renderer.lines.setColor(.15f, .35f, .45f, .55f);
        renderer.lines.rect(center.x - 80, center.y - 36, 160, 100);
        renderer.lines.end();
        boolean newContact = splash.contact(1) == null;
        splash.render(renderer.camera, scene, motions, Map.of("1:-1", model), Color.WHITE);
        if (newContact && splash.contact(1) != null) {
            var bounds = UnitBounds.world(model);
            var contact = splash.contact(1);
            assertTrue(UnitBounds.subtree(model.getNode("CT")).mul(model.transform).min.z <= contact.z,
                  "Spray must not start before the torso enters the water");
            assertTrue(contact.x >= bounds.min.x && contact.x <= bounds.max.x);
            assertTrue(contact.y >= bounds.min.y && contact.y <= bounds.max.y, "Splash must originate on the posed body, not its movement anchor");
        }
        if (frame % 4 == 0) {
            renderer.output.display();
            File output = new File(System.getProperty("megamek.gpu.screenshots"), "playback-water-fall-" + (renderer.topView ? "top" : "iso"));
            assertTrue(output.isDirectory() || output.mkdirs());
            var pixels = Pixmap.createFromFrameBuffer(0, 0, 640, 480);
            PixmapIO.writePNG(new FileHandle(new File(output, String.format("%03d.png", frame))), pixels, -1, true);
            pixels.dispose();
        }
        renderer.output.scene().end();
    }
}
