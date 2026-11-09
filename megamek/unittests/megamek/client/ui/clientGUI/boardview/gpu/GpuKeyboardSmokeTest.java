/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.util.KeyCommandBind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The battle view's keyboard path as GLFW reports keys: a modifier key reports itself while it goes down, a release
 * keeps the modifiers of its press, and the HUD and the camera follow the key bindings the client captured on the
 * Swing thread, so a rebound key works natively without the GL thread reading the bind fields.
 */
@Tag("on-demand")
class GpuKeyboardSmokeTest {
    @Test
    void nativeKeysFollowTheCapturedBindsAndTheModifiersOfTheirPress() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardSource source = mock(GpuBoardSource.class);
            source.uiPreferences = GpuHudFixtures.preferences();
            // The view reads the preferences through the accessor; the test rebinds through the field.
            when(source.uiPreferences()).thenAnswer(invocation -> source.uiPreferences);
            when(source.takeFrame()).thenAnswer(invocation -> fixture.source.takeFrame());
            when(source.scenarioAtmosphere()).thenAnswer(invocation -> fixture.source.scenarioAtmosphere());
            new Lwjgl3Application(new GpuBattleView(source) {
                private int tick;

                @Override
                public void render() {
                    try {
                        super.render();
                        if (frames() < 3 || ++tick != 1) {
                            return;
                        }
                        GpuHud hud = (GpuHud) field("ui");
                        Input realInput = Gdx.input;
                        InputProcessor processor = realInput.getInputProcessor();
                        Input keyboard = mock(Input.class);
                        when(keyboard.getInputProcessor()).thenReturn(processor);
                        Graphics realGraphics = Gdx.graphics;
                        Graphics timed = spy(realGraphics);
                        doReturn(.05f).when(timed).getDeltaTime();
                        Gdx.graphics = timed;
                        Gdx.input = keyboard;
                        try {
                            // Alt goes down reporting Alt itself: the nameplate key, which never reaches Swing.
                            press(keyboard, InputEvent.ALT_DOWN_MASK);
                            assertTrue(processor.keyDown(Input.Keys.ALT_LEFT));
                            assertTrue(hud.state.allNameplates, "Alt shows every nameplate while held");
                            press(keyboard, 0);
                            processor.keyUp(Input.Keys.ALT_LEFT);
                            assertFalse(hud.state.allNameplates);
                            verify(source, never()).key(anyInt(), anyBoolean(), anyInt());

                            // A bare Shift has no bind: Swing gets it with the mask it reports.
                            press(keyboard, InputEvent.SHIFT_DOWN_MASK);
                            processor.keyDown(Input.Keys.SHIFT_LEFT);
                            processor.keyUp(Input.Keys.SHIFT_LEFT);
                            verify(source).key(KeyEvent.VK_SHIFT, true, InputEvent.SHIFT_DOWN_MASK);
                            verify(source).key(KeyEvent.VK_SHIFT, false, InputEvent.SHIFT_DOWN_MASK);

                            // Ctrl released before W: the unbound chord's release keeps its press's modifiers,
                            // and an auto-repeated press does not reach Swing twice.
                            clearInvocations(source);
                            press(keyboard, InputEvent.CTRL_DOWN_MASK);
                            processor.keyDown(Input.Keys.W);
                            processor.keyDown(Input.Keys.W);
                            press(keyboard, 0);
                            processor.keyUp(Input.Keys.W);
                            verify(source, times(1)).key(KeyEvent.VK_W, true, InputEvent.CTRL_DOWN_MASK);
                            verify(source, times(1)).key(KeyEvent.VK_W, false, InputEvent.CTRL_DOWN_MASK);

                            // Rebound on the Swing thread: Help on F9 and north scrolling on I. The view follows the
                            // captured binds, while the bind fields keep MegaMek's defaults.
                            source.uiPreferences = rebound(Map.of(KeyCommandBind.KEY_BINDS, KeyEvent.VK_F9,
                                  KeyCommandBind.SCROLL_NORTH, KeyEvent.VK_I));
                            super.render();
                            assertEquals(KeyEvent.VK_K, KeyCommandBind.KEY_BINDS.key);
                            clearInvocations(source);
                            processor.keyDown(Input.Keys.F9);
                            processor.keyUp(Input.Keys.F9);
                            assertEquals(GpuHudState.Dialog.HELP, hud.state.dialog, "F9 opens Help now");
                            // Help consumes every key while open; close it before checking board-key forwarding.
                            processor.keyDown(Input.Keys.ESCAPE);
                            processor.keyUp(Input.Keys.ESCAPE);
                            assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);
                            super.render();
                            press(keyboard, InputEvent.CTRL_DOWN_MASK);
                            processor.keyDown(Input.Keys.K);
                            processor.keyUp(Input.Keys.K);
                            assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog, "Ctrl+K no longer toggles Help");
                            verify(source).key(KeyEvent.VK_K, true, InputEvent.CTRL_DOWN_MASK);
                            press(keyboard, 0);
                            processor.keyDown(Input.Keys.I);
                            assertTrue(cameraKeys().containsValue(KeyCommandBind.SCROLL_NORTH), "I scrolls north");
                            processor.keyUp(Input.Keys.I);
                            assertCameraBoost(keyboard, processor, Input.Keys.I);
                            processor.keyDown(Input.Keys.W);
                            assertTrue(cameraKeys().isEmpty(), "W no longer scrolls");
                            processor.keyUp(Input.Keys.W);
                            verify(source).key(KeyEvent.VK_W, true, 0);
                            verify(source, never()).key(KeyEvent.VK_I, true, 0);
                            verify(source, never()).key(KeyEvent.VK_I, true, InputEvent.SHIFT_DOWN_MASK);
                            verify(source, never()).key(KeyEvent.VK_F9, true, 0);

                            // Both Shift keys boost WASD and Q/E in 3D, Tactical View and Free Flight.
                            source.uiPreferences = GpuHudFixtures.preferences();
                            super.render();
                            clearInvocations(source);
                            for (int mode = 0; mode < 3; mode++) {
                                setTacticalView(mode == 1);
                                if (mode == 2) {
                                    boardCamera.setFirstPerson(true);
                                    boardCamera.look(0, 90 - boardCamera.tilt());
                                    boardCamera.camera.position.z += 5000;
                                    boardCamera.update();
                                }
                                for (int key : new int[] { Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.D,
                                      Input.Keys.Q, Input.Keys.E }) {
                                    assertCameraBoost(keyboard, processor, key);
                                }
                            }
                            var start = boardCamera.camera.position.cpy();
                            processor.keyDown(Input.Keys.W);
                            processor.keyDown(Input.Keys.S);
                            super.render();
                            processor.keyUp(Input.Keys.W);
                            processor.keyUp(Input.Keys.S);
                            assertEquals(start, boardCamera.camera.position, "Opposing flight keys cancel");
                            verify(source, never()).key(anyInt(), anyBoolean(), anyInt());
                            boardCamera.setFirstPerson(false);
                            for (boolean tactical : new boolean[] { false, true }) {
                                setTacticalView(tactical);
                                boardCamera.setIsometric(false);
                                boardCamera.pan(100000, 0);
                                Vector3 edge = boardCamera.focus.cpy();
                                processor.keyDown(Input.Keys.A);
                                super.render();
                                super.render();
                                processor.keyUp(Input.Keys.A);
                                assertTrue(edge.epsilonEquals(boardCamera.focus, .01f), "Held panning stops at the map edge");
                                processor.keyDown(Input.Keys.D);
                                super.render();
                                processor.keyUp(Input.Keys.D);
                                assertTrue(boardCamera.focus.x > edge.x, "Reversing away from the map edge moves immediately");
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        } finally {
                            Gdx.input = realInput;
                            Gdx.graphics = realGraphics;
                        }
                        Gdx.app.exit();
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void assertCameraBoost(Input keyboard, InputProcessor processor, int key) {
                    processor.keyDown(key);
                    float normal = advanceCamera(key);
                    processor.keyUp(key);
                    assertTrue(normal > .01f, "The unmodified camera key must move");
                    for (int shift : new int[] { Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT }) {
                        when(keyboard.isKeyPressed(shift)).thenReturn(true);
                        processor.keyDown(key);
                        assertEquals(normal * 4, advanceCamera(key), .03f, "Shift held before the camera key boosts it");
                        when(keyboard.isKeyPressed(shift)).thenReturn(false);
                        assertEquals(normal, advanceCamera(key), .03f, "Releasing Shift restores normal speed");
                        when(keyboard.isKeyPressed(shift)).thenReturn(true);
                        assertEquals(normal * 4, advanceCamera(key), .03f, "Shift pressed during movement boosts it");
                        processor.keyUp(key);
                        assertEquals(0, advanceCamera(key), .001f, "Releasing the camera key stops movement");
                        when(keyboard.isKeyPressed(shift)).thenReturn(false);
                    }
                }

                private float advanceCamera(int key) {
                    if (!boardCamera.firstPerson()) {
                        // Measure input speed with room to move; repeated boosted steps now stop at map boundaries.
                        boardCamera.zoom(1 / boardCamera.camera.zoom);
                        boardCamera.center(BoardGeometry.center(fixture.game.getBoard().getCenter(), 0));
                    }
                    Vector3 position = boardCamera.camera.position.cpy();
                    float azimuth = boardCamera.azimuth();
                    super.render();
                    if (!boardCamera.firstPerson() && (key == Input.Keys.Q || key == Input.Keys.E)) {
                        return Math.abs(((boardCamera.azimuth() - azimuth) % 360 + 540) % 360 - 180);
                    }
                    return position.dst(boardCamera.camera.position);
                }

                @SuppressWarnings("unchecked")
                private Map<Integer, KeyCommandBind> cameraKeys() throws Exception {
                    return (Map<Integer, KeyCommandBind>) field("cameraKeys");
                }

                private Object field(String name) throws Exception {
                    var field = GpuBattleView.class.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(this);
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) {
            throw new AssertionError("Native keyboard routing failed", failure.get());
        }
    }

    /** MegaMek's default binds with {@code keys} moved to new keys without modifiers, as Swing captures them. */
    private static GpuBoardSource.UiPreferences rebound(Map<KeyCommandBind, Integer> keys) {
        GpuBoardSource.UiPreferences defaults = GpuHudFixtures.preferences();
        List<GpuBoardSource.Bind> binds = defaults.binds().stream().map(bind -> keys.containsKey(bind.command())
              ? new GpuBoardSource.Bind(bind.command(), keys.get(bind.command()), 0, bind.text()) : bind).toList();
        return GpuHudFixtures.with(defaults, Map.of("binds", binds));
    }

    /** The modifier keys the mocked input reports as held, as GLFW does while they are down. */
    private static void press(Input keyboard, int modifiers) {
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SYM)).thenReturn((modifiers & InputEvent.META_DOWN_MASK) != 0);
    }
}
