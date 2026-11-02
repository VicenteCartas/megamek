/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.model.Node;
import megamek.common.Hex;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.IArmorState;
import megamek.common.equipment.WeaponMounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.common.weapons.infantry.InfantryWeapon;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Optional real-renderer video capture; fixed camera and hidden HUD, with the production playback and death clocks. */
@Tag("on-demand")
class GpuInfantryDeathVideoSmokeTest {
    private static final float FRAME = 1 / 30f;
    private static final Coords TARGET = new Coords(8, 5);
    private static final int WIDTH = 640, HEIGHT = 600;
    private record Example(String name, String weapon, int type) {
        String file() { return name + "-" + List.of("infantry", "battle-armor", "motorized").get(type); }
    }

    @Test
    void recordLethalHitsAndVerifyTheirVisibleAftermath() throws Exception {
        assumeTrue(Boolean.getBoolean("megamek.gpu.infantryVideo"));
        var failure = new AtomicReference<Throwable>();
        var pending = new AtomicReference<List<BoardScene.Animation>>(List.of());
        List<Example> examples = new ArrayList<>();
        for (String[] weapon : List.of(new String[] { "rifle", "InfantryAutoRifle" }, new String[] { "ac", "ISAC20" },
              new String[] { "laser", "ISMediumLaser" }, new String[] { "flamer", "ISFlamer" })) {
            for (int type = 0; type < 3; type++) { examples.add(new Example(weapon[0], weapon[1], type)); }
        }
        String selected = System.getProperty("megamek.gpu.infantryVideoWeapon", "");
        if (!selected.isEmpty()) { examples.removeIf(example -> !example.name().equals(selected)); }
        int limit = Integer.getInteger("megamek.gpu.infantryVideoCases", examples.size());
        Hex[] hexes = new Hex[14 * 12];
        for (int index = 0; index < hexes.length; index++) { hexes[index] = new Hex(0); }
        File folder = new File(System.getProperty("megamek.gpu.screenshots"), "infantry-video");
        Files.createDirectories(folder.toPath());
        try (var fixture = GpuBoardFixture.create(new Board(14, 12, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(2, 5));
                    fixture.entity.setFacing(2);
                    fixture.entity.setSecondaryFacing(2);
                    fixture.entity.addEquipment(EquipmentType.get("ISFlamer"), Mek.LOC_LEFT_ARM);
                    var infantry = platoon(EntityMovementMode.INF_LEG);
                    String weapon = "InfantryAutoRifle";
                    infantry.setPrimaryWeapon((InfantryWeapon) EquipmentType.get(weapon));
                    infantry.addEquipment(EquipmentType.get(weapon), ConvInfantry.LOC_INFANTRY);
                    infantry.setId(2);
                    infantry.setOwner(fixture.player);
                    infantry.setPosition(new Coords(6, 5));
                    infantry.setFacing(2);
                    infantry.setDeployed(true);
                    fixture.game.addEntity(infantry, false);
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                var events = new ArrayList<>(frame.timeline());
                events.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(events);
            }).when(source).takeFrame();
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(960, 720);
            new Lwjgl3Application(new GpuBattleView(source) {
                private Graphics nativeGraphics, fixedGraphics;
                private Entity victim;
                private int exampleIndex, stage, warmup, frameIndex, impact = -1;
                private float minimumScale = 1;
                private long deadline;
                private Process encoder;
                private final byte[] pixels = new byte[WIDTH * HEIGHT * 4];
                private StringBuilder samples;
                private List<String> figureIds;
                private boolean installed;

                @Override
                public void create() {
                    nativeGraphics = Gdx.graphics;
                    fixedGraphics = spy(nativeGraphics);
                    doReturn(FRAME).when(fixedGraphics).getDeltaTime();
                    Gdx.graphics = fixedGraphics;
                    super.create();
                }

                @Override
                public void render() {
                    try {
                        Gdx.graphics = fixedGraphics;
                        super.render();
                        if (GpuBoardTestUi.loading(this)) { return; }
                        if (!installed) {
                            var camera = spy(boardCamera);
                            doNothing().when(camera).frameAttacks(anyList(), anyFloat());
                            doNothing().when(camera).frameSelection(any(), anyFloat());
                            replace(this, "boardCamera", camera);
                            var hud = spy((GpuHud) field(this, "ui"));
                            doNothing().when(hud).draw();
                            replace(this, "ui", hud);
                            boardCamera.setIsometric(true);
                            boardCamera.setReturnAfterPlayback(false);
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.NORMAL);
                            installed = true;
                        }
                        var example = examples.get(exampleIndex);
                        if (stage == 0) {
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    fixture.entity.setPosition(example.name().equals("flamer") ? new Coords(6, 5) : new Coords(2, 5));
                                    fixture.game.getEntity(2).setPosition(example.name().equals("rifle") ? new Coords(6, 5) : new Coords(0, 1));
                                    victim = example.type() == 1 ? new MekFileParser(new File(
                                          "testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity()
                                          : platoon(example.type() == 2 ? EntityMovementMode.INF_MOTORIZED : EntityMovementMode.INF_LEG);
                                    victim.setId(40 + exampleIndex);
                                    victim.setOwner(fixture.player);
                                    victim.setPosition(TARGET);
                                    victim.setFacing(5);
                                    victim.setDeployed(true);
                                    if (example.type() == 1) {
                                        for (int location = 2; location < victim.locations(); location++) {
                                            victim.setInternal(IArmorState.ARMOR_DESTROYED, location);
                                        }
                                    }
                                    fixture.game.addEntity(victim, false);
                                    fixture.source.refresh();
                                } catch (Exception error) { throw new IllegalStateException(error); }
                            });
                            boardCamera.center(BoardGeometry.center(TARGET, 0));
                            boardCamera.camera.zoom = example.type() == 1 ? .14f : .18f;
                            warmup = 0;
                            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                            stage = 1;
                            return;
                        }
                        assertTrue(System.nanoTime() < deadline, "The recording must finish: " + example.file());
                        var playback = (UnitPlayback) field(this, "playback");
                        if (stage == 1) {
                            if (++warmup < 20) { return; }
                            var before = (BoardScene) field(this, "scene");
                            var target = before.units().stream().filter(unit -> unit.id() == victim.getId()).findFirst().orElseThrow();
                            int attackerId = example.name().equals("rifle") ? 2 : 1;
                            var attacker = before.units().stream().filter(unit -> unit.id() == attackerId).findFirst().orElseThrow();
                            var firing = fixture.game.getEntity(attackerId);
                            WeaponMounted gun = firing.getWeaponList().stream()
                                  .filter(mount -> mount.getType() == EquipmentType.get(example.weapon())).findFirst().orElseThrow();
                            var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
                                  new UnitLocation(attackerId, attacker.location().coords(), 2, 0, before.boardId()),
                                  new UnitLocation(target.id(), TARGET, 5, 0, before.boardId()), Targetable.TYPE_ENTITY,
                                  gun.getEquipmentNum(), gun.getType().getInternalName(), gun.getLocation(), true, null,
                                  ResolvedAttack.Shot.capture(gun))
                                  .withImpacts(List.of(new ResolvedAttack.Impact("*", false, example.name().equals("ac") ? 20 : 1)));
                            var standing = (GpuUnitInstance) ((Map<?, ?>) field(this, "unitInstances")).get(target.id() + ":-1");
                            figureIds = standing.visual().rigs().stream().filter(rig -> !rig.transport()).map(UnitRig::container).toList();
                            SwingUtilities.invokeAndWait(() -> {
                                if (victim instanceof ConvInfantry) { victim.setInternal(0, ConvInfantry.LOC_INFANTRY); }
                                else { victim.setInternal(IArmorState.ARMOR_DESTROYED, 1); }
                                victim.setDoomed(true);
                                fixture.source.refresh();
                            });
                            var after = fixture.source.takeFrame().scene();
                            pending.set(List.of(new BoardScene.SceneUpdate(before),
                                  new BoardScene.Combat(result, attacker, target, target.location(), victim instanceof ConvInfantry),
                                  UnitPlaybackTest.attack(target, target, ResolvedAttack.Kind.DEATH, true), new BoardScene.SceneUpdate(after)));
                            encoder = new ProcessBuilder(System.getProperty("megamek.gpu.ffmpeg"), "-hide_banner", "-y",
                                  "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", WIDTH + "x" + HEIGHT,
                                  "-framerate", "30", "-i", "pipe:0", "-vf", "vflip", "-an", "-c:v", "libx264",
                                  "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                                  new File(folder, example.file() + ".mp4").getAbsolutePath())
                                  .redirectError(new File(folder, example.file() + "-encode.log")).start();
                            samples = new StringBuilder("frame,scale,after_impact,flight,until_death\n");
                            frameIndex = 0;
                            impact = -1;
                            minimumScale = 1;
                            stage = 2;
                            return;
                        }
                        float scale = (float) field(this, "timeScale");
                        var death = playback.attacks().stream().filter(UnitAttack::death).findFirst().orElse(null);
                        var casualties = (MeepleCasualties) field(this, "casualties");
                        if (death != null && death.seconds < 0) {
                            assertTrue(death.cause != null);
                            minimumScale = Math.min(minimumScale, scale);
                            if (death.seconds <= -UnitSlowMotion.DEATH_LEAD_SECONDS) {
                                assertEquals(1, scale, "All ranged weapons stay at speed until the death lead-in window");
                            }
                            assertTrue(casualties.casualties().isEmpty(), "No casualty before impact");
                        } else if (impact < 0 && death != null) {
                            impact = frameIndex;
                            assertEquals(1, scale, "Normal speed resumes on the impact frame");
                            assertEquals(.1f, minimumScale, .005f, "Every ranged death approaches one tenth speed before contact");
                            assertEquals(1, casualties.casualties().size());
                            var casualty = casualties.casualties().getFirst();
                            assertEquals(example.type() == 2 ? 2 : 0, ((List<?>) field(casualty, "blasts")).size(),
                                  "Only the transport vehicles have internal explosions");
                            int intact = 0;
                            for (Node node : casualty.instance.nodes) {
                                if (!figureIds.contains(node.id)) { continue; }
                                intact++;
                                assertEquals(example.name().equals("laser") || example.name().equals("flamer"), burnt(node));
                            }
                            assertEquals(example.name().equals("ac") ? 0 : figureIds.size(), intact,
                                  "Heavy hits leave fragments instead of falling figures");
                        }
                        int since = impact < 0 ? -1 : frameIndex - impact;
                        if (since >= 0) { assertEquals(1, scale, "The entire aftermath stays at full speed"); }
                        boolean heat = example.name().equals("laser") || example.name().equals("flamer");
                        if (heat && example.type() != 2 && since >= 0 && since <= 27) {
                            assertEquals(1, casualties.casualties().getFirst().opacity(), "Burning holds before fading");
                        }
                        if (heat && example.type() != 2 && since == 34) {
                            assertTrue(casualties.casualties().getFirst().opacity() < 1, "Burning fades after one real second");
                        }
                        if (example.name().equals("laser") && since == 9) {
                            int blasts = ((GpuAttackEffects) field(this, "attackEffects")).explosionCount();
                            if (example.type() < 2) { assertEquals(example.type(), blasts, "Only Battle Armor has a laser impact fireball"); }
                            else { assertTrue(blasts >= 2, "Transport vehicles still explode"); }
                        }
                        if (since == 60 && example.name().equals("rifle")) {
                            int grounded = 0;
                            var ground = (MeepleDebris.Ground) field(this, "debrisGround");
                            for (Node node : casualties.casualties().getFirst().instance.nodes) {
                                if (!figureIds.contains(node.id)) { continue; }
                                assertEquals(0, GpuMeepleDeathSmokeTest.gap(GpuMeepleDeathSmokeTest.vertices(
                                      casualties.casualties().getFirst().instance, node), ground), .25f,
                                      "The actual fallen GLB rests on the terrain");
                                grounded++;
                            }
                            assertEquals(figureIds.size(), grounded);
                        }
                        if (frameIndex == 0 || since == 9 || since == 60 || since == 240) {
                            GpuReviewFrame.save(new File(folder, example.file() + "-review-" + frameIndex + ".png"));
                        }
                        var image = Pixmap.createFromFrameBuffer(160, 60, WIDTH, HEIGHT);
                        try {
                            image.getPixels().get(pixels);
                            encoder.getOutputStream().write(pixels);
                        } finally { image.dispose(); }
                        samples.append(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f,%.6f%n", frameIndex, scale,
                              since < 0 ? -1 : since * FRAME, death == null || death.cause == null ? 1 : death.cause.flight(),
                              death == null ? Float.POSITIVE_INFINITY : -death.seconds));
                        frameIndex++;
                        if (since >= 250) {
                            assertTrue(casualties.casualties().isEmpty(), "All figures and vehicle fragments disappear");
                            assertFalse(((Map<?, ?>) field(this, "unitInstances")).containsKey(victim.getId() + ":-1"));
                            encoder.getOutputStream().close();
                            assertTrue(encoder.waitFor(45, TimeUnit.SECONDS));
                            assertEquals(0, encoder.exitValue());
                            encoder = null;
                            Files.writeString(new File(folder, example.file() + ".csv").toPath(), samples);
                            System.out.println("Recorded " + example.file() + ": " + frameIndex + " frames; impact " + impact);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            if (++exampleIndex >= limit) { Gdx.app.exit(); }
                            else { stage = 0; }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    if (encoder != null) { encoder.destroy(); }
                    Gdx.graphics = nativeGraphics;
                    super.dispose();
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError("Infantry video review failed", failure.get()); }
    }

    private static ConvInfantry platoon(EntityMovementMode mode) {
        var infantry = new ConvInfantry();
        infantry.setChassis("Infantry Review");
        infantry.setModel("Rifle");
        infantry.setMovementMode(mode);
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        return infantry;
    }

    private static void replace(GpuBattleView view, String name, Object value) throws Exception {
        var field = GpuBattleView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private static boolean burnt(Node node) {
        for (var part : node.parts) {
            if (part.enabled && part.material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX)) {
                assertTrue(part.material.has(UnitDamageDisplay.Overlay.TYPE));
                return true;
            }
        }
        for (Node child : node.getChildren()) { if (burnt(child)) { return true; } }
        return false;
    }
}
