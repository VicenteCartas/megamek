/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.gdx.UiCursorCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises free flight through the real renderer, tuning controls and native input multiplexer. */
@Tag("on-demand")
class GpuFreeFlightSmokeTest {
    @Test
    void freeFlightControlsRenderAndRestoreTheTacticalViewWithoutIssuingOrders() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create()) {
            BoardSource source = mock(BoardSource.class);
            when(source.uiPreferences()).thenAnswer(invocation -> fixture.source.uiPreferences());
            when(source.phaseStatus()).thenAnswer(invocation -> fixture.source.phaseStatus());
            when(source.takeFrame()).thenAnswer(invocation -> fixture.source.takeFrame());
            new Lwjgl3Application(new GpuBattleView(source) {
                private Vector3 eye;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (frames() == 20) {
                            // The HUD's Tuning utility; its panel shows from the next frame, then its Camera tab.
                            GpuBoardTestUi.click("tuning-button");
                        } else if (frames() == 21) {
                            GpuBoardTestUi.click("tuning-camera-tab");
                        } else if (frames() == 22) {
                            assertNull(GpuBoardTestUi.stage().getRoot().findActor("tuning-perspective"));
                            GpuBoardTestUi.click("tuning-free-flight");
                            assertTrue(boardCamera.firstPerson());
                            assertTrue(boardCamera.perspective());
                            assertEquals(45, boardCamera.fieldOfView());
                            eye = boardCamera.camera.position.cpy();
                            setFov(20);
                            assertEquals(eye, boardCamera.camera.position);
                        } else if (frames() == 26) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-fov-20.png"));
                            setFov(100);
                            assertEquals(eye, boardCamera.camera.position);
                        } else if (frames() == 30) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-fov-100.png"));
                            setFov(BoardCamera.DEFAULT_FIELD_OF_VIEW);
                            GpuBoardTestUi.click("tuning-button");
                            assertDrag(Input.Buttons.RIGHT, false, true);
                            assertDrag(Input.Buttons.MIDDLE, false, false);
                            assertDrag(Input.Buttons.RIGHT, true, false);
                            assertDrag(Input.Buttons.MIDDLE, true, true);
                            boardCamera.look(0, 90 - boardCamera.tilt());
                        } else if (frames() == 34) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-horizon.png"));
                            boardCamera.look(180, 0);
                        } else if (frames() == 36) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-rear-labels.png"));
                            boardCamera.look(-180, -20);
                            eye = boardCamera.camera.position.cpy();
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.E);
                        } else if (frames() == 38) {
                            assertTrue(eye.dst(boardCamera.camera.position) > .01f);
                            assertTrue(boardCamera.camera.position.z > eye.z, "E ascends while W flies forward");
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.W);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.E);
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            eye = boardCamera.camera.position.cpy();
                            GpuBoardTestUi.click("tuning-button");
                        } else if (frames() == 42) {
                            assertTrue(eye.dst(boardCamera.camera.position) > .01f, "Opening a panel must allow held flight");
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.W);
                            eye = boardCamera.camera.position.cpy();
                            GpuBoardTestUi.click("tuning-button");
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            pause();
                        } else if (frames() == 46) {
                            assertEquals(eye, boardCamera.camera.position, "Losing focus stops held flight");
                            var scene = fixture.source.takeFrame().scene();
                            var tile = scene.tiles().getFirst();
                            boardCamera.camera.position.set(BoardGeometry.center(tile.coords(), tile.elevation())).add(0, 0, 200);
                            boardCamera.update();
                            boardCamera.fly(0, 0, -1, 10000);
                            eye = boardCamera.camera.position.cpy();
                            assertTrue(eye.z > BoardGeometry.floor(scene), "The rendered terrain must stop downward flight");
                            boardCamera.fly(0, 0, -1, 10000);
                            assertEquals(eye.z, boardCamera.camera.position.z, .01f);
                            boardCamera.look(0, 90 - boardCamera.tilt());
                        } else if (frames() == 47) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-ground-clearance.png"));
                            // The Tactical View's straight-down view ends Free Flight.
                            GpuBoardTestUi.click("utility-tactical");
                            assertFalse(boardCamera.firstPerson());
                        } else if (frames() == 48) {
                            GpuBoardTestUi.click("tuning-button");
                        } else if (frames() == 49) {
                            GpuBoardTestUi.click("tuning-free-flight");
                            GpuBoardTestUi.click("tuning-defaults");
                            assertFalse(boardCamera.firstPerson());
                            assertFalse(boardCamera.perspective());
                            assertEquals(45, boardCamera.fieldOfView());
                            boardCamera.reset(fixture.source.takeFrame().scene());
                        } else if (frames() == 52) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-restored-tactical.png"));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void setFov(float degrees) {
                    GpuBoardTestUi.<Slider>tuning(this, "tuning-camera-fov").setValue(degrees);
                    assertEquals(degrees, boardCamera.fieldOfView());
                }


                private void assertDrag(int button, boolean shift, boolean pan) {
                    Vector3 position = boardCamera.camera.position.cpy();
                    Vector3 direction = boardCamera.camera.direction.cpy();
                    Input original = Gdx.input;
                    Input input = mock(Input.class);
                    var processor = original.getInputProcessor();
                    when(input.getInputProcessor()).thenReturn(processor);
                    when(input.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn(shift);
                    Gdx.input = input;
                    try {
                        processor.touchDown(300, 300, 0, button);
                        // A gesture keeps the Shift state from its press even when the modifier changes mid-drag.
                        when(input.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn(!shift);
                        processor.touchDragged(360, 340, 0);
                        // Only a look or an orbit holds the cursor, and its release shows it again; a pan never does.
                        assertEquals(!pan, UiCursorCapture.active());
                        processor.touchUp(360, 340, 0, button);
                        assertFalse(UiCursorCapture.active());
                    } finally {
                        Gdx.input = original;
                    }
                    String gesture = "Button " + button + ", Shift " + shift;
                    if (pan) {
                        assertTrue(position.dst(boardCamera.camera.position) > .01f, gesture + " must pan the eye");
                        assertEquals(direction, boardCamera.camera.direction, gesture + " must preserve the viewing angle");
                    } else {
                        assertEquals(position, boardCamera.camera.position, gesture + " must rotate around the eye");
                        assertFalse(direction.epsilonEquals(boardCamera.camera.direction, .001f), gesture + " must look around");
                    }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            verify(source, never()).key(anyInt(), anyBoolean(), anyInt());
            assertEquals(0, fixture.clicks.get(), "Camera navigation must not issue gameplay orders");
        }
    }
}
