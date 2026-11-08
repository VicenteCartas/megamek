/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuMachineGunSmokeTest {
    @Test
    void normalAndRapidFireEmitSuccessiveRoundsWithAndWithoutWeaponMeshes() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var library = new GpuUnitModels();
                var effects = new GpuAttackEffects();
                try (var renderer = new GpuPlaybackReview.ReviewRenderer()) {
                    var tileset = new MekTileset(Configuration.unitImagesDir());
                    tileset.loadFromFile("mekset.txt");
                    var entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
                    entity.setId(9800);
                    var gun = entity.addEquipment(EquipmentType.get("ISMG"), Mek.LOC_LEFT_ARM);
                    var selection = UnitModelSelection.capture(entity, -1, false, tileset);
                    var model = library.get(selection, entity.getId());
                    var attacker = unit(entity.getId(), new Coords(2, 3), selection);
                    var target = unit(9801, new Coords(2, 1), selection);
                    var source = new ModelInstance(model.instance.model);
                    var victim = new ModelInstance(model.instance.model);
                    var origin = BoardGeometry.center(attacker.location().coords(), 0);
                    var destination = BoardGeometry.center(target.location().coords(), 0);
                    model.place(victim, renderer.camera, destination, 180, target);
                    var animator = new UnitAnimator();
                    for (boolean rapid : List.of(false, true)) {
                        gun.setRapidFire(rapid);
                        var profile = ResolvedAttack.Shot.capture(gun);
                        for (boolean fallback : List.of(false, true)) {
                            for (boolean hit : List.of(false, true)) {
                                var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
                                      new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
                                      new UnitLocation(target.id(), target.location().coords(), 3, 0, 0), Targetable.TYPE_ENTITY,
                                      gun.getEquipmentNum(), gun.getType().getInternalName(), gun.getLocation(), hit,
                                      ResolvedAttack.captureMounts(entity, gun.getEquipmentNum()), profile);
                                var event = new BoardScene.Combat(result, attacker, target, target.location());
                                var playback = new UnitPlayback();
                                playback.accept(List.of(event), UnitPlaybackFixture.scene(attacker, target), ignored -> false);
                                playback.advance(0, UnitMotion.Speed.NORMAL);
                                var attack = playback.attack();
                                int previous = 0;
                                for (int frame = 0; frame < Math.ceil(attack.duration * 60); frame++) {
                                    playback.advance(1.0 / 60 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
                                    animator.apply(model, source, attacker, UnitMotion.Sample.STILL, 0, 0, true, 0);
                                    animator.attacks(model, attacker, playback.attacks());
                                    model.place(source, renderer.camera, origin, 0, attacker);
                                    animator.aimShots(model, attacker, playback.attacks(), ignored -> victim);
                                    effects.update(playback.attacks(), fallback ? null : library,
                                          Map.of(attacker.id() + ":-1", source, target.id() + ":-1", victim));
                                    effects.render(renderer.camera);
                                    int emitted = effects.emissions(attack).size();
                                    assertTrue(emitted >= previous && emitted <= previous + 1,
                                          "Rounds must leave one at a time at this frame rate");
                                    if (attack.seconds > .76f && attack.seconds < 1.29f) {
                                        assertEquals(6, rapid ? Math.min(6, emitted) : emitted);
                                        if (rapid && attack.seconds > 1) { assertTrue(emitted > 6, "Rapid fire must keep shooting longer"); }
                                    }
                                    if (hit && !fallback && frame % 4 == 0) {
                                        for (boolean top : List.of(false, true)) {
                                            renderer.topView = top;
                                            renderer.frame(List.of(source, victim), origin.cpy().lerp(destination, .5f), effects,
                                                  "machine-gun-" + (rapid ? "rapid" : "normal") + (top ? "-top" : "-iso"), frame);
                                        }
                                    }
                                    previous = emitted;
                                }
                                assertEquals(rapid ? 18 : 6, previous, "rapid=" + rapid + " fallback=" + fallback + " hit=" + hit
                                      + " clock=" + attack.seconds + " duration=" + attack.duration);
                                effects.update(List.of(), library, Map.of());
                                assertTrue(effects.emissions(attack).isEmpty(), "Completing playback must release the traces");
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { effects.dispose(); library.dispose(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Machine-gun playback failed", failure.get()); }
    }

    private static BoardScene.Unit unit(int id, Coords coords, BoardScene.UnitModel model) {
        return new BoardScene.Unit(id, -1, "Machine gun review", new BoardScene.Waypoint(coords, 0, 0),
              null, false, null, 2, false, model, 0);
    }
}
