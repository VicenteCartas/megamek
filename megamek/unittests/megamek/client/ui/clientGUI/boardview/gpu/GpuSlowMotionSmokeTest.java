/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Hex;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.IArmorState;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native playback, including actual bullet endpoints and casualties; optional fixed-time frames make a review video. */
@Tag("on-demand")
class GpuSlowMotionSmokeTest {
    private static final float FRAME = 1 / 60f;

    @Test
    void mekKillingBulletSlowsBeforeImpact() throws Exception { review(false, false, "ISAC20"); }

    @Test
    void lastBattleArmorTrooperKillingBulletSlowsBeforeImpact() throws Exception { review(true, false, "ISAC20"); }

    @Test
    void meleeKillingBlowKeepsNormalSpeed() throws Exception { review(false, true, ""); }

    @Test
    void lethalBeamLastsAnExtraSecondThenExplodesAtFullSpeed() throws Exception { review(false, false, "ISMediumLaser"); }

    @Test
    void tacticalKillingBulletShowsExplosionAndCrossAtImpact() throws Exception { review(false, false, "ISAC20", true); }

    private static void review(boolean battleArmor, boolean melee, String weapon) throws Exception {
        review(battleArmor, melee, weapon, false);
    }

    private static void review(boolean battleArmor, boolean melee, String weapon, boolean tactical) throws Exception {
        boolean beam = !melee && !"ISAC20".equals(weapon);
        String name = tactical ? "tactical" : melee ? "melee" : beam ? "beam" : battleArmor ? "battle-armor" : "mek";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        AtomicReference<Entity> victim = new AtomicReference<>();
        Hex[] hexes = new Hex[12 * 10];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        try (var fixture = GpuBoardFixture.create(new Board(12, 10, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(melee ? GamePhase.PHYSICAL : GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(2, 4));
                    fixture.entity.setFacing(2);
                    fixture.entity.setSecondaryFacing(2);
                    if (battleArmor) {
                        Entity armor = new MekFileParser(new File(
                              "testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity();
                        armor.setId(42);
                        armor.setOwner(fixture.player);
                        armor.setPosition(new Coords(8, 4));
                        armor.setDeployed(true);
                        for (int trooper = 2; trooper < armor.locations(); trooper++) {
                            armor.setInternal(IArmorState.ARMOR_DESTROYED, trooper);
                        }
                        fixture.game.addEntity(armor, false);
                        victim.set(armor);
                    } else {
                        victim.set(GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(melee ? 3 : 8, 4)));
                    }
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            File folder = new File(System.getProperty("megamek.gpu.screenshots"), "dramatic-death/" + name);
            boolean record = Boolean.getBoolean("megamek.gpu.deathVideo");
            if (record) { Files.createDirectories(folder.toPath()); }
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1280, 800);
            new Lwjgl3Application(new GpuBattleView(source) {
                private final long deadline = System.nanoTime() + 240_000_000_000L;
                private final StringBuilder samples = new StringBuilder("frame,scale,distance_hexes,after_impact\n");
                private Graphics nativeGraphics, fixedGraphics;
                private int stage, frameIndex, impactFrame = -1;
                private int ordinaryBeamFrames, lethalBeamFrames;
                private float previous = 1;
                private boolean slowed, outside, paused;

                @Override
                public void create() {
                    nativeGraphics = Gdx.graphics;
                    fixedGraphics = spy(nativeGraphics);
                    doReturn(FRAME).when(fixedGraphics).getDeltaTime();
                    Gdx.graphics = fixedGraphics;
                    super.create();
                    boardCamera.setIsometric(true);
                }

                @Override
                public void render() {
                    try {
                        // LWJGL reinstalls the window's native graphics before each render callback.
                        Gdx.graphics = fixedGraphics;
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "The playback must finish: " + GpuBoardTestUi.loadingState(this));
                        if (GpuBoardTestUi.loading(this)) { return; }
                        var playback = (UnitPlayback) field(this, "playback");
                        float scale = (float) field(this, "timeScale");
                        if (melee) { assertEquals(1, scale, "Physical attacks stay at normal speed, including death"); }
                        if (stage == 0) {
                            if (tactical) { boardCamera.setTactical(true, (BoardScene) field(this, "scene")); }
                            if (melee) {
                                boardCamera.camera.zoom = .4f;
                                boardCamera.center(BoardGeometry.center(new Coords(2, 4), 0));
                            }
                            if (record) {
                                // Keep the small final suit and its fall visible in the video close-up.
                                var nameplates = GpuBoardTestUi.stage().getRoot().findActor("nameplates");
                                assertTrue(nameplates != null);
                                nameplates.setVisible(false);
                            }
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.NORMAL);
                            pending.set(List.of(shot(fixture, (BoardScene) field(this, "scene"), melee, weapon)));
                            stage = 1;
                            return;
                        }
                        if (stage == 1) {
                            assertEquals(1, scale, "An ordinary hit stays at normal speed");
                            if (beam && playback.attack() != null && playback.attack().seconds >= UnitAttack.ANTICIPATION_SECONDS
                                  && playback.attack().seconds < playback.attack().contactSeconds) { ordinaryBeamFrames++; }
                            if (playback.busy()) { return; }
                            var before = (BoardScene) field(this, "scene");
                            var target = before.units().stream().filter(unit -> unit.id() == 42).findFirst().orElseThrow();
                            if (battleArmor) { assertEquals(1, target.model().state().structure().activeTroopers()); }
                            var shot = shot(fixture, before, melee, weapon);
                            SwingUtilities.invokeAndWait(() -> {
                                if (battleArmor) { victim.get().setInternal(IArmorState.ARMOR_DESTROYED, 1); }
                                victim.get().setDoomed(true);
                                fixture.source.refresh();
                            });
                            var after = fixture.source.takeFrame().scene();
                            pending.set(List.of(new BoardScene.SceneUpdate(before), shot,
                                  UnitPlaybackTest.attack(target, target, ResolvedAttack.Kind.DEATH, true),
                                  new BoardScene.SceneUpdate(after)));
                            stage = 2;
                            return;
                        }
                        var death = playback.attacks().stream().filter(UnitAttack::death).findFirst().orElse(null);
                        assertTrue(death == null || death.cause != null, "The death must be linked to its killing shot");
                        float distance = melee || death == null || death.cause == null ? Float.POSITIVE_INFINITY
                              : ((GpuAttackEffects) field(this, "attackEffects")).distanceToImpact(death.cause) / BoardGeometry.height();
                        if (impactFrame < 0 && death != null && death.seconds < 0) {
                            if (beam && death.cause.seconds >= UnitAttack.ANTICIPATION_SECONDS) { lethalBeamFrames++; }
                            if (Float.isFinite(distance) && distance > 4) {
                                outside = true;
                                assertEquals(1, scale, "The projectile stays at speed outside four hexes");
                            }
                            if (scale < .9f) { slowed = true; }
                            assertTrue(scale <= previous + .0001f, "Approach slows progressively");
                            previous = scale;
                            assertTrue(((MeepleCasualties) field(this, "casualties")).casualties().isEmpty(),
                                  "The target stays alive until the projectile arrives");
                            if (tactical) {
                                var bases = (GpuUnitBases) field(this, "unitBases");
                                assertTrue(!bases.spec(death.event.attacker()).destroyed(), "No cross before impact");
                            }
                            if (!record && !paused && scale < .5f) {
                                float at = death.cause.seconds;
                                playback.togglePaused();
                                for (int tick = 0; tick < 30; tick++) { super.render(); }
                                assertEquals(at, death.cause.seconds, "Pause freezes the lethal projectile");
                                assertEquals(scale, (float) field(this, "timeScale"), "Pause preserves the approach scale");
                                playback.togglePaused();
                                paused = true;
                            }
                        } else if (impactFrame < 0 && death != null) {
                            impactFrame = frameIndex;
                            if (!melee && !beam) {
                                assertTrue(slowed && outside, "The actual projectile crosses the four-hex slowdown boundary");
                                assertEquals(.1f, previous, .002f, "The final approach frame is at one tenth speed");
                            } else if (beam) {
                                assertTrue(slowed);
                                assertEquals(1, (lethalBeamFrames - ordinaryBeamFrames) * FRAME, 2 * FRAME,
                                      "The lethal beam is visible one real second longer than an ordinary beam");
                            }
                            assertEquals(1, scale, "The visible impact and explosion already run at full speed");
                            assertEquals(1, ((MeepleCasualties) field(this, "casualties")).casualties().size(),
                                  "The Mek or final Battle Armor figure dies at impact");
                            if (tactical) {
                                assertTrue(((GpuUnitBases) field(this, "unitBases")).spec(death.event.attacker()).destroyed(),
                                      "The cross starts at the lethal impact");
                                assertTrue(((GpuAttackEffects) field(this, "attackEffects")).explosionCount() > 0,
                                      "Tactical View draws the explosion on the impact frame");
                            }
                        }
                        int afterImpact = impactFrame < 0 ? -1 : frameIndex - impactFrame;
                        if (afterImpact >= 0) {
                            assertEquals(1, scale, "No slowdown remains after impact");
                        }
                        int restingFrame = (int) Math.ceil((MeepleCasualties.FALL_SECONDS + .15f)
                              / (FRAME * UnitMotion.Speed.NORMAL.rate));
                        if (battleArmor && afterImpact == restingFrame) {
                            var casualty = ((MeepleCasualties) field(this, "casualties")).casualties().getFirst();
                            var points = GpuMeepleDeathSmokeTest.vertices(casualty.instance, casualty.instance.nodes.first());
                            float gap = GpuMeepleDeathSmokeTest.gap(points, (MeepleDebris.Ground) field(this, "debrisGround"));
                            GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots"), "battle-armor-resting.png"));
                            assertEquals(0, gap, .25f, "The fallen GLB suit's actual surface touches the ground");
                        }
                        if (record) {
                            GpuReviewFrame.save(new File(folder, String.format(Locale.ROOT, "frame-%04d.png", frameIndex)));
                            samples.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f%n", frameIndex, scale,
                                  distance, impactFrame < 0 ? -1 : (frameIndex - impactFrame) * FRAME));
                        }
                        int aftermathFrames = battleArmor ? (int) Math.ceil((MeepleCasualties.FALL_SECONDS
                              + MeepleCasualties.LIE_SECONDS + UnitAttack.FADE_SECONDS + .3f)
                              / (FRAME * UnitMotion.Speed.NORMAL.rate)) : 180;
                        if (impactFrame >= 0 && frameIndex > impactFrame + aftermathFrames) {
                            if (battleArmor) {
                                assertTrue(((MeepleCasualties) field(this, "casualties")).casualties().isEmpty(),
                                      "The grounded final suit fades away completely");
                            }
                            if (record) { Files.writeString(new File(folder, "timing.csv").toPath(), samples); }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                        frameIndex++;
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    Gdx.graphics = nativeGraphics;
                    super.dispose();
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError(name + " slow motion failed", failure.get()); }
    }

    private static BoardScene.Combat shot(GpuBoardFixture fixture, BoardScene scene, boolean melee, String weapon) {
        var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
        var target = scene.units().stream().filter(unit -> unit.id() == 42).findFirst().orElseThrow();
        if (melee) {
            var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.PUNCH,
                  new UnitLocation(1, attacker.location().coords(), 2, 0, scene.boardId()),
                  new UnitLocation(42, target.location().coords(), 0, 0, scene.boardId()),
                  Targetable.TYPE_ENTITY, -1, "", Mek.LOC_RIGHT_ARM, true);
            return new BoardScene.Combat(result, attacker, target, target.location());
        }
        var gun = fixture.entity.getWeaponList().stream().filter(mount -> mount.getType() == EquipmentType.get(weapon))
              .findFirst().orElseThrow();
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(1, attacker.location().coords(), 2, 0, scene.boardId()),
              new UnitLocation(42, target.location().coords(), 0, 0, scene.boardId()), Targetable.TYPE_ENTITY,
              gun.getEquipmentNum(), gun.getType().getInternalName(), 4, true, null, ResolvedAttack.Shot.capture(gun));
        return new BoardScene.Combat(result, attacker, target, target.location());
    }
}
