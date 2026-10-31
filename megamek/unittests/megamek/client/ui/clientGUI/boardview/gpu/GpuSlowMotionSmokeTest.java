/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.common.Hex;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Wall-clock playback: with the defaults a hit plays at speed (no bullet time); the meeple it kills explodes as it
 * lands, its death plays in slow motion and the playback returns to speed after it.
 */
@Tag("on-demand")
class GpuSlowMotionSmokeTest {
    @Test
    void aDeathPlaysInSlowMotionAndAHitAtSpeed() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Hex[] hexes = new Hex[16 * 17];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        try (var fixture = GpuBoardFixture.create(new Board(16, 17, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(4, 8));
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(9, 8));
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            new Lwjgl3Application(new GpuBattleView(source) {
                private final long deadline = System.nanoTime() + 60_000_000_000L;
                private boolean queued;
                private long started, ended;
                private float from, slowest = 1;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "The playback must finish");
                        if (GpuBoardTestUi.loading(this)) { return; }
                        var playback = (UnitPlayback) field(this, "playback");
                        var casualties = ((MeepleCasualties) field(this, "casualties")).casualties();
                        var death = playback.attacks().stream().filter(UnitAttack::death).findFirst().orElse(null);
                        float scale = (float) field(this, "timeScale");
                        if (!queued) {
                            var scene = (BoardScene) field(this, "scene");
                            var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
                            var target = scene.units().stream().filter(unit -> unit.id() == 42).findFirst().orElseThrow();
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.QUADRUPLE);
                            pending.set(List.of(UnitPlaybackTest.attack(attacker, target, ResolvedAttack.Kind.SHOT, true),
                                  UnitPlaybackTest.attack(target, target, ResolvedAttack.Kind.DEATH, true)));
                            queued = true;
                        } else if (ended == 0 && (death == null || death.seconds < 0)) {
                            if (playback.attack() != null) {
                                assertEquals(1, scale, "Without bullet time a hit plays at speed");
                                assertTrue(casualties.isEmpty(), "The meeple stands until its killing hit lands");
                            }
                        } else if (ended == 0) {
                            if (started == 0) {
                                started = System.nanoTime();
                                assertEquals(1, casualties.size(), "The meeple explodes as its killing hit lands");
                            }
                            slowest = Math.min(slowest, scale);
                            // Timed from a quarter in: past the frame that breaks the meeple and the ease into slow motion.
                            if (from == 0 && death.seconds >= death.duration / 4) {
                                started = System.nanoTime();
                                from = death.seconds;
                            }
                            if (death.seconds >= death.duration) {
                                ended = System.nanoTime();
                                double atSpeed = (death.duration - from) / UnitMotion.Speed.QUADRUPLE.rate;
                                assertTrue((ended - started) / 1e9 > 1.5 * atSpeed, "The death plays slower than its speed: "
                                      + (ended - started) / 1e9 + " s, " + atSpeed + " s at speed, slowest " + slowest);
                                assertTrue(slowest < .6f, "The death slows the playback: " + slowest);
                            }
                        } else if (System.nanoTime() - ended > 1_500_000_000L) {
                            assertTrue(scale > .99f, "The playback returns to speed after the death: " + scale);
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Slow motion failed", failure.get()); }
    }
}
