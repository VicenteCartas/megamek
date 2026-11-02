/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises real board picking and modifier routing in both cameras. */
@Tag("on-demand")
class GpuMeasurementSmokeTest {
    @Test
    void leftClicksPreserveMeasurementModifiersInBothCameras() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords start = new Coords(4, 4), end = new Coords(8, 6);
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.drawRuler(start, end, Color.CYAN, Color.ORANGE);
                fixture.view.checkLOS(start);
                fixture.view.checkLOS(end);
                fixture.source.refresh();
            });
            GpuBoardSource source = mock(GpuBoardSource.class);
            source.uiPreferences = fixture.source.uiPreferences;
            when(source.uiPreferences()).thenAnswer(invocation -> fixture.source.uiPreferences());
            when(source.takeFrame()).thenAnswer(invocation -> fixture.source.takeFrame());
            new Lwjgl3Application(new GpuBattleView(source) {
                private int tick;

                @Override
                public void render() {
                    try {
                        super.render();
                        if (frames() == 0) { return; }
                        tick++;
                        if (tick == 1) {
                            // Board gestures only: the HUD routes them, but its panels (the LOS card) must not cover
                            // the measured hex in either camera.
                            hud().stage.getRoot().setVisible(false);
                            boardCamera.setIsometric(false);
                            boardCamera.center(BoardGeometry.center(new Coords(6, 5), 0));
                            boardCamera.zoom(0.45f);
                        } else if (tick == 3 || tick == 7) {
                            gesture(InputEvent.ALT_DOWN_MASK);
                        } else if (tick == 4 || tick == 8) {
                            verifyModifiers(InputEvent.ALT_DOWN_MASK);
                            gesture(InputEvent.CTRL_DOWN_MASK);
                        } else if (tick == 5 || tick == 9) {
                            // Ctrl no longer measures: the board editor took it (2026-10-07).
                            verify(source, never()).measure(eq(start), eq(InputEvent.CTRL_DOWN_MASK), anyFloat());
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            GpuBoardTestUi.capture(new File(output,
                                  tick == 5 ? "ruler-los-top.png" : "ruler-los-isometric.png"));
                            if (tick == 5) {
                                boardCamera.setIsometric(true);
                            } else {
                                Gdx.app.exit();
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void verifyModifiers(int modifiers) {
                    // The HUD hands an Alt click to MegaMek's measurement tools, at the height the pointer
                    // shows: the measured hex's ground.
                    ArgumentCaptor<Float> pointed = ArgumentCaptor.forClass(Float.class);
                    verify(source).measure(eq(start), eq(modifiers), pointed.capture());
                    assertEquals(0, GpuLosResult.pointedHeight(fixture.game.getBoard().getHex(start),
                          pointed.getValue()));
                }

                private void gesture(int modifiers) {
                    clearInvocations(source);
                    var tile = fixture.source.takeFrame().scene().tile(start);
                    Vector3 screen = boardCamera.camera.project(BoardGeometry.center(start, tile.elevation()),
                          0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                    int x = Math.round(screen.x), y = Gdx.graphics.getHeight() - Math.round(screen.y);
                    Input realInput = Gdx.input;
                    InputProcessor processor = realInput.getInputProcessor();
                    Input keys = mock(Input.class);
                    when(keys.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
                    when(keys.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
                    Gdx.input = keys;
                    try {
                        processor.touchDown(x, y, 0, Input.Buttons.LEFT);
                        processor.touchDragged(x + 1, y, 0);
                        processor.touchUp(x, y, 0, Input.Buttons.LEFT);
                    } finally {
                        Gdx.input = realInput;
                    }
                }

                private GpuHud hud() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("ui");
                    field.setAccessible(true);
                    return (GpuHud) field.get(this);
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) {
            throw new AssertionError("Native measurement routing failed", failure.get());
        }
    }
}
