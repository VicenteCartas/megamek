/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.interfaces.IEntityRemovalConditions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real playback and renderer: death cause, frozen crew-loss pose, shared marker fade and no permanent wreck. */
@Tag("on-demand")
class GpuUnitDeathSmokeTest {
    private static final float FRAME = 1 / 60f;

    @ParameterizedTest
    @CsvSource({ "false,false,false", "false,true,false", "true,false,false", "true,true,false",
          "false,false,true", "false,true,true" })
    void deathsFadeAndTacticalMarkersShareTheirLifetime(boolean tactical, boolean crewLoss, boolean models) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var pending = new AtomicReference<List<BoardScene.Animation>>(List.of());
        String name = (tactical ? "tactical" : models ? "model" : "meeple") + (crewLoss ? "-crew-loss" : "-destroyed");
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.FIRING);
                fixture.source.refresh();
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                var timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            new Lwjgl3Application(new GpuBattleView(source) {
                private final long deadline = System.nanoTime() + 240_000_000_000L;
                private Graphics nativeGraphics, fixedGraphics;
                private int stage, warmup;
                private boolean started, fading, burst;
                private BoardScene.Unit victim;
                private GpuUnitInstance standing;
                private Matrix4 lastPose;
                private List<Matrix4> joints;

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
                        Gdx.graphics = fixedGraphics;
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Death playback finishes: " + name);
                        if (GpuBoardTestUi.loading(this)) { return; }
                        var scene = (BoardScene) field(this, "scene");
                        var instances = (Map<?, ?>) field(this, "unitInstances");
                        var casualties = (MeepleCasualties) field(this, "casualties");
                        var bases = (GpuUnitBases) field(this, "unitBases");
                        if (stage == 0) {
                            boardCamera.setTactical(tactical, scene);
                            boardCamera.setReturnAfterPlayback(false);
                            boardCamera.center(BoardGeometry.center(new Coords(5, 5), 0));
                            boardCamera.zoom(.3f);
                            GpuBoardTestUi.<SelectBox<UnitDisplayMode>>tuning(this, "tuning-unit-display")
                                  .setSelected(models ? UnitDisplayMode.MODELS : UnitDisplayMode.ALL_MEEPLES);
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.NORMAL);
                            stage = 1;
                            return;
                        }
                        if (stage == 1) {
                            if (++warmup < 5) { return; }
                            victim = scene.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
                            standing = (GpuUnitInstance) instances.get("1:-1");
                            assertNotNull(standing);
                            if (models) { assertFalse(MeepleVisual.isMeeple(standing)); }
                            lastPose = standing.transform.cpy();
                            joints = new ArrayList<>();
                            standing.nodes.forEach(node -> joints.add(node.localTransform.cpy()));
                            capture("before");
                            var death = UnitPlaybackTest.attack(victim, victim, ResolvedAttack.Kind.DEATH, true);
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.entity.setDoomed(true);
                                fixture.game.removeEntity(1, crewLoss ? IEntityRemovalConditions.REMOVE_EJECTED
                                      : IEntityRemovalConditions.REMOVE_SALVAGEABLE);
                                fixture.source.refresh();
                            });
                            var after = fixture.source.takeFrame().scene();
                            pending.set(List.of(new BoardScene.SceneUpdate(scene),
                                  new BoardScene.Combat(death.result().withCrewLoss(crewLoss), victim, victim, victim.location()),
                                  new BoardScene.SceneUpdate(after)));
                            stage = 2;
                            return;
                        }
                        var effects = (GpuAttackEffects) field(this, "attackEffects");
                        if (crewLoss) { assertEquals(0, effects.explosionCount(), "Crew loss never emits a death explosion"); }
                        if (!casualties.casualties().isEmpty()) {
                            var casualty = casualties.casualties().getFirst();
                            assertSame(standing, casualty.instance, "The casualty owns the last drawn model");
                            assertFalse(instances.containsKey("1:-1"), "No second body remains in the scene");
                            if (!started) {
                                if (!crewLoss) { assertTrue(effects.explosionCount() > 0, "The blast starts on the death frame"); }
                                started = true;
                                capture("death");
                            }
                            var death = ((UnitPlayback) field(this, "playback")).attacks().stream()
                                  .filter(UnitAttack::death).findFirst().orElse(null);
                            if (!crewLoss && !burst && death != null && death.seconds >= .2f) {
                                burst = true;
                                capture("burst");
                            }
                            if (crewLoss) {
                                assertArrayEquals(lastPose.val, standing.transform.val, .0001f, "Crew loss stays exactly where it was");
                                for (int i = 0; i < joints.size(); i++) {
                                    assertArrayEquals(joints.get(i).val, standing.nodes.get(i).localTransform.val, .0001f,
                                          "Crew loss does not collapse");
                                }
                            }
                            if (tactical) {
                                assertNotNull(bases.spec(victim));
                                assertTrue(bases.spec(victim).destroyed());
                                assertEquals(casualty.opacity(), ((GpuUnitShader.Shown) bases.base(victim).userData).opacity);
                                assertEquals(casualty.opacity(), ((GpuUnitShader.Shown) bases.badges(victim).userData).opacity);
                            }
                            if (!fading && casualty.opacity() < .6f) {
                                fading = true;
                                capture("fading");
                            }
                            return;
                        }
                        if (!started) { return; }
                        assertTrue(fading, "Death fades before removal");
                        assertFalse(instances.containsKey("1:-1"), "The body stays gone after the fade");
                        if (tactical) {
                            assertNull(bases.base(victim), "The circle is gone with the body");
                            assertNull(bases.badges(victim), "The cross is gone with the body");
                        }
                        capture("gone");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        Gdx.app.exit();
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void capture(String moment) throws Exception {
                    GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots"),
                          "unit-death/" + name + "-" + moment + ".png"));
                }

                @Override
                public void dispose() {
                    Gdx.graphics = nativeGraphics;
                    super.dispose();
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError(name, failure.get()); }
    }
}
