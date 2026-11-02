/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Native playback and casualties; optional fixed-time frames make a review video. */
@Tag("on-demand")
class GpuSlowMotionSmokeTest {
    private static final float FRAME = 1 / 60f;
    private enum Instant { NEVER, BEFORE_ACTION, DURING_APPROACH, AFTER_IMPACT }

    @Test
    void mekKillingBulletSlowsBeforeImpact() throws Exception { review(false, false, "ISAC20"); }

    @Test
    void lastBattleArmorTrooperKillingBulletSlowsBeforeImpact() throws Exception { review(true, false, "ISAC20"); }

    @Test
    void meleeKillingBlowKeepsNormalSpeed() throws Exception { review(false, true, ""); }

    @Test
    void lethalBeamSlowsBeforeDeathThenExplodesAtFullSpeed() throws Exception { review(false, false, "ISMediumLaser"); }

    @Test
    void lastBattleArmorTrooperLethalBeamSlowsBeforeDeathThenCarbonizes() throws Exception { review(true, false, "ISMediumLaser"); }

    @Test
    void mixedWeaponsSlowBeforeTheirScheduledDeath() throws Exception { review(false, false, "mixed"); }

    @Test
    void instantPlaybackNeverStartsSlowMotion() throws Exception {
        review(false, false, "ISAC20", false, Instant.BEFORE_ACTION);
    }

    @Test
    void switchingToInstantImmediatelyClearsAnActiveSlowdown() throws Exception {
        review(false, false, "ISAC20", false, Instant.DURING_APPROACH);
    }

    @ParameterizedTest
    @CsvSource({ "false, false, ISAC20", "true, false, ISAC20", "false, true, ISMediumLaser" })
    void switchingToInstantAfterImpactRemovesBodiesDebrisAndMarkers(boolean tactical, boolean battleArmor, String weapon) throws Exception {
        review(battleArmor, false, weapon, tactical, Instant.AFTER_IMPACT);
    }

    @Test
    void tacticalKillingBulletShowsExplosionAndCrossAtImpact() throws Exception { review(false, false, "ISAC20", true); }

    private static void review(boolean battleArmor, boolean melee, String weapon) throws Exception {
        review(battleArmor, melee, weapon, false);
    }

    private static void review(boolean battleArmor, boolean melee, String weapon, boolean tactical) throws Exception {
        review(battleArmor, melee, weapon, tactical, Instant.NEVER);
    }

    private static void review(boolean battleArmor, boolean melee, String weapon, boolean tactical, Instant instant) throws Exception {
        boolean beam = "ISMediumLaser".equals(weapon);
        String name = tactical ? "tactical" : melee ? "melee" : "mixed".equals(weapon) ? "mixed"
              : beam ? "beam" : battleArmor ? "battle-armor" : "mek";
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
                    if ("mixed".equals(weapon)) {
                        fixture.entity.addEquipment(EquipmentType.get("ISFlamer"), Mek.LOC_LEFT_ARM);
                        fixture.entity.addEquipment(EquipmentType.get("ISMG"), Mek.LOC_LEFT_ARM);
                    }
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
                private final StringBuilder samples = new StringBuilder("frame,scale,until_death,after_impact\n");
                private Graphics nativeGraphics, fixedGraphics;
                private int stage, frameIndex, impactFrame = -1;
                private float previous = 1;
                private boolean slowed, outside, paused;
                private BoardScene.Unit skippedCasualty;

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
                                if (battleArmor) {
                                    // Keep the real board/animation passes, with no HUD covering the final suit's landing.
                                    var hud = spy((GpuHud) field(this, "ui"));
                                    doNothing().when(hud).draw();
                                    var ui = GpuBattleView.class.getDeclaredField("ui");
                                    ui.setAccessible(true);
                                    ui.set(this, hud);
                                }
                            }
                            ((GpuPlaybackHistory) field(this, "history")).speed(instant == Instant.BEFORE_ACTION
                                  ? UnitMotion.Speed.INSTANT : UnitMotion.Speed.NORMAL);
                            pending.set(List.of(shot(fixture, (BoardScene) field(this, "scene"), melee, weapon)));
                            stage = 1;
                            return;
                        }
                        if (stage == 1) {
                            assertEquals(1, scale, "An ordinary hit stays at normal speed");
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
                        if (instant != Instant.NEVER && ((GpuPlaybackHistory) field(this, "history")).speed() == UnitMotion.Speed.INSTANT) {
                            assertEquals(1, scale, "Instant bypasses slow motion on the very next rendered frame");
                            assertFalse(playback.busy(), "Instant finishes the attack and death without an approach or hold");
                            assertTrue(playback.attacks().isEmpty());
                            if (instant == Instant.DURING_APPROACH) { assertTrue(slowed, "The test switched during an active slowdown"); }
                            assertTrue(((MeepleCasualties) field(this, "casualties")).casualties().isEmpty(),
                                  "Instant removes existing bodies and debris on the first rendered frame");
                            if (instant == Instant.AFTER_IMPACT) {
                                assertTrue(skippedCasualty != null, "The test must switch after the casualty is visible");
                                assertEquals(0, ((GpuAttackEffects) field(this, "attackEffects")).explosionCount(),
                                      "No death fireball remains after switching to Instant");
                                if (tactical) {
                                    assertNull(((GpuUnitBases) field(this, "unitBases")).badges(skippedCasualty),
                                          "The Tactical death marker disappears with the casualty");
                                }
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                            return;
                        }
                        var death = playback.attacks().stream().filter(UnitAttack::death).findFirst().orElse(null);
                        assertTrue(death == null || death.cause != null, "The death must be linked to its killing shot");
                        float remaining = death == null ? Float.POSITIVE_INFINITY : -death.seconds;
                        if (impactFrame < 0 && death != null && death.seconds < 0) {
                            if (remaining >= UnitSlowMotion.DEATH_LEAD_SECONDS) {
                                outside = true;
                                assertEquals(1, scale, "The clock stays at speed before the death's lead-in window");
                            }
                            if (scale < .9f) { slowed = true; }
                            assertTrue(scale <= previous + .0001f, "Approach slows progressively");
                            previous = scale;
                            if (instant == Instant.DURING_APPROACH && scale < .5f) {
                                ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.INSTANT);
                                return;
                            }
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
                            if (!melee) {
                                assertTrue(slowed && outside, "The rendered attack slows only in the death's lead-in window");
                                assertEquals(.1f, previous, .002f, "The final approach frame is at one tenth speed");
                            }
                            assertEquals(1, scale, "The visible impact and explosion already run at full speed");
                            assertEquals(1, ((MeepleCasualties) field(this, "casualties")).casualties().size(),
                                  "The Mek or final Battle Armor figure dies at impact");
                            if (battleArmor && !beam) {
                                var casualty = ((MeepleCasualties) field(this, "casualties")).casualties().getFirst();
                                assertTrue(casualty.instance.nodes.first().getChildren().iterator().next().id.startsWith(MeepleShatter.PIECE),
                                      "The final AC/20 hit shatters the actual Battle Armor GLB at impact");
                            }
                            if (tactical) {
                                assertTrue(((GpuUnitBases) field(this, "unitBases")).spec(death.event.attacker()).destroyed(),
                                      "The cross starts at the lethal impact");
                                assertTrue(((GpuAttackEffects) field(this, "attackEffects")).explosionCount() > 0,
                                      "Tactical View draws the explosion on the impact frame");
                            }
                        }
                        int afterImpact = impactFrame < 0 ? -1 : frameIndex - impactFrame;
                        if (instant == Instant.AFTER_IMPACT && afterImpact == 30) {
                            var remains = ((MeepleCasualties) field(this, "casualties")).casualties();
                            assertEquals(1, remains.size(), "Switch while the death effect is still visible");
                            skippedCasualty = remains.getFirst().unit;
                            playback.togglePaused();
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.INSTANT);
                            return;
                        }
                        if (afterImpact >= 0) {
                            assertEquals(1, scale, "No slowdown remains after impact");
                        }
                        if (record) {
                            GpuReviewFrame.save(new File(folder, String.format(Locale.ROOT, "frame-%04d.png", frameIndex)));
                            samples.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f%n", frameIndex, scale,
                                  remaining, impactFrame < 0 ? -1 : (frameIndex - impactFrame) * FRAME));
                        }
                        int aftermathFrames = battleArmor ? (int) Math.ceil((MeepleCasualties.DEBRIS_SECONDS
                              + UnitAttack.FADE_SECONDS + .3f)
                              / (FRAME * UnitMotion.Speed.NORMAL.rate)) : 180;
                        if (impactFrame >= 0 && frameIndex > impactFrame + aftermathFrames) {
                            if (battleArmor) {
                                assertTrue(((MeepleCasualties) field(this, "casualties")).casualties().isEmpty(),
                                      "The final suit's fragments fade away completely");
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
        boolean mixed = "mixed".equals(weapon);
        var gun = fixture.entity.getWeaponList().stream().filter(mount -> mount.getType() == EquipmentType.get(mixed ? "ISAC20" : weapon))
              .findFirst().orElseThrow();
        var types = List.of("ISAC20", "ISMG", "ISMediumLaser", "ISFlamer").stream().map(EquipmentType::get).toList();
        var mounts = mixed ? fixture.entity.getWeaponList().stream()
              .filter(mount -> types.contains(mount.getType()))
              .map(mount -> new ResolvedAttack.Mount(1, mount.getEquipmentNum(), ResolvedAttack.Shot.capture(mount))).toList() : null;
        if (mixed) {
            assertTrue(types.stream().allMatch(type -> fixture.entity.getWeaponList().stream().anyMatch(mount -> mount.getType() == type)),
                  "The native scene must contain all four weapon families");
        }
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(1, attacker.location().coords(), 2, 0, scene.boardId()),
              new UnitLocation(42, target.location().coords(), 0, 0, scene.boardId()), Targetable.TYPE_ENTITY,
              gun.getEquipmentNum(), gun.getType().getInternalName(), 4, true, mounts, ResolvedAttack.Shot.capture(gun));
        return new BoardScene.Combat(result, attacker, target, target.location());
    }
}
