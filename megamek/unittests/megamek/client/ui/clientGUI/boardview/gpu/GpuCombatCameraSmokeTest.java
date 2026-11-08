/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real camera and playback integration: replays fit between panels, idle panel changes leave the camera still,
 * and shots wait until framing is complete, including in a smaller window.
 */
@Tag("on-demand")
class GpuCombatCameraSmokeTest {
    @Test
    void splitFireUsesPanelClearanceButIdlePanelChangesLeaveCameraStill() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Hex[] hexes = new Hex[20 * 20];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(i % 20 >= 14 ? 2 : 0); }
        try (var fixture = GpuBoardFixture.create(new Board(20, 20, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(2, 3));
                    Player enemy = new Player(2, "Opponent");
                    enemy.setTeam(2);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(17, 5)).setOwner(enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 43, new Coords(13, 17)).setOwner(enemy);
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var source = spy(fixture.source);
            // The volleys join the source's own events, as MegaMek's attack reports would.
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            new Lwjgl3Application(new GpuBattleView(source) {
                /**
                 * The camera moves and the shots play in wall-clock time, so the review has 30 s rather than a number
                 * of frames: an uncapped hidden window renders hundreds of frames a second.
                 */
                private final long deadline = System.nanoTime() + 30_000_000_000L;
                private int step;
                private int ticks;
                private int resizedAt;
                private List<BoardScene.Animation> shots;
                private float[] beforePanelChange;
                private boolean logWasOpen;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        ticks++;
                        assertTrue(System.nanoTime() < deadline, "Combat camera review must finish");
                        if (GpuBoardTestUi.loading(this)) { return; }
                        var playback = (UnitPlayback) GpuTestSupport.field(this, "playback");
                        var ui = (GpuHud) GpuTestSupport.field(this, "ui");
                        if (boardCamera.isFraming()) {
                            playback.attacks().forEach(shot -> assertTrue(shot.seconds <= 0,
                                  "No weapon animation may advance before the camera is ready"));
                        }
                        if (step == 0) {
                            var scene = (BoardScene) GpuTestSupport.field(this, "scene");
                            assertTrue(boardCamera.camera.zoom < 10,
                                  "Opening the window must not zoom far beyond the board");
                            var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst()
                                  .orElseThrow();
                            shots = scene.units().stream().filter(unit -> unit.id() != 1)
                                  .<BoardScene.Animation>map(target -> UnitPlaybackFixture.attack(attacker, target,
                                        ResolvedAttack.Kind.SHOT, true)).toList();
                            assertEquals(2, shots.size());
                            boardCamera.setIsometric(true);
                            boardCamera.orbit(25, 25);
                            boardCamera.center(BoardGeometry.center(attacker.location().coords(),
                                  attacker.location().elevation()));
                            boardCamera.zoom(.12f);
                            ((GpuPlaybackHistory) GpuTestSupport.field(this, "history")).speed(UnitMotion.Speed.QUADRUPLE);
                            beforePanelChange = boardCamera.camera.combined.val.clone();
                            logWasOpen = ui.state.logOpen();
                            GpuBoardTestUi.click("utility-log");
                            resizedAt = ticks;
                            step = 5;
                        } else if (step == 1 && firing(playback)) {
                            playback.togglePaused();
                            assertCoverage(ui);
                            GpuBoardTestUi.capture(new File(output, "combat-camera-firing-oblique.png"));
                            // The log takes the right column's place, wider than the contacts.
                            if (!ui.state.logOpen()) { GpuBoardTestUi.click("utility-log"); }
                            nextVolley(playback, BoardCamera.ATTACK_TOP_VIEW_TILT_DEGREES, 73);
                            step++;
                        } else if (step == 5 && ticks > resizedAt + 3) {
                            assertEquals(!logWasOpen, ui.state.logOpen(), "The Log utility changes panel clearance");
                            assertArrayEquals(beforePanelChange, boardCamera.camera.combined.val,
                                  "Opening a wider HUD panel must not move, rotate or zoom the camera");
                            GpuBoardTestUi.click("utility-log");
                            resizedAt = ticks;
                            step = 6;
                        } else if (step == 6 && ticks > resizedAt + 3) {
                            assertArrayEquals(beforePanelChange, boardCamera.camera.combined.val,
                                  "Closing the HUD panel must also leave the idle camera still");
                            pending.set(shots);
                            step = 1;
                        } else if (step == 2) {
                            assertTrue(ui.state.logOpen(), "The Log utility opens the log");
                            assertEquals(BoardCamera.ATTACK_TOP_VIEW_TILT_DEGREES, boardCamera.tilt(), .001f);
                            assertEquals(73, boardCamera.azimuth(), .001f);
                            if (firing(playback)) {
                                playback.togglePaused();
                                assertCoverage(ui);
                                GpuBoardTestUi.capture(new File(output, "combat-camera-log-threshold.png"));
                                resizedAt = ticks;
                                step++;
                                // A native resize may reenter render before setWindowedMode returns.
                                Gdx.graphics.setWindowedMode(900, 680);
                            }
                        } else if (step == 3 && ticks > resizedAt + 2) {
                            assertEquals(900, Gdx.graphics.getWidth());
                            nextVolley(playback, 0, 0);
                            step++;
                        } else if (step == 4) {
                            assertEquals(0, boardCamera.tilt());
                            assertEquals(0, boardCamera.azimuth());
                            if (firing(playback)) {
                                playback.togglePaused();
                                assertCoverage(ui);
                                GpuBoardTestUi.capture(new File(output, "combat-camera-log-top-small.png"));
                                Gdx.app.exit();
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void nextVolley(UnitPlayback playback, float tilt, float orbit) {
                    playback.finish();
                    if (playback.paused()) { playback.togglePaused(); }
                    boardCamera.setIsometric(false);
                    boardCamera.orbit(orbit, tilt);
                    boardCamera.zoom(.2f);
                    pending.set(shots);
                }

                /** Replay participants remain visible between the HUD columns. */
                @SuppressWarnings("unchecked")
                private void assertCoverage(GpuHud ui) throws Exception {
                    float left = ui.framingLeft(), right = left + ui.framingWidth();
                    var instances = (Map<String, ModelInstance>) GpuTestSupport.field(this, "unitInstances");
                    for (int id : new int[] { 1, 42, 43 }) {
                        var instance = instances.entrySet().stream()
                              .filter(entry -> entry.getKey().startsWith(id + ":"))
                              .findFirst().orElseThrow().getValue();
                        var bounds = UnitBounds.world(instance);
                        for (float x : new float[] { bounds.min.x, bounds.max.x }) {
                            for (float y : new float[] { bounds.min.y, bounds.max.y }) {
                                for (float z : new float[] { bounds.min.z, bounds.max.z }) {
                                    var point = boardCamera.camera.project(new Vector3(x, y, z), 0, 0,
                                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                                    assertTrue(point.x > left && point.x < right && point.y > 0
                                          && point.y < boardCamera.camera.viewportHeight,
                                          () -> "Replay unit " + id + " outside the visible framing area: " + point);
                                }
                            }
                        }
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Combat camera framing failed", failure.get()); }
    }

    private static boolean firing(UnitPlayback playback) {
        return playback.attack() != null && playback.attack().seconds > UnitAttack.ANTICIPATION_SECONDS;
    }
}
