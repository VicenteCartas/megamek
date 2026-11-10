/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Briefcase;
import megamek.common.equipment.Cargo;
import megamek.common.equipment.GroundObject;
import megamek.common.equipment.ICarryable;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

/**
 * The battle view's input routing (rebuild plan C.4, I1a acceptance A1-A4). Real key and pointer events go through the
 * view's BoardInput and GpuHud to a recording source, whose frames carry the fixture's real board and a scripted phase
 * and local turn: every key reaches exactly one of the HUD, the camera and MegaMek's Swing keys; board clicks follow
 * the phase; a text field or a pending dialog keeps the keys and presses; the HUD draws a dialog asked before the
 * first board. The window is 1920 x 1080 with one HUD unit per pixel.
 */
@Tag("on-demand")
class GpuHudRoutingSmokeTest {
    /** The fixture's own Atlas and the Archer this test adds for an opposing player. */
    private static final int OWN_ID = 1;
    private static final int FOE_ID = 2;
    /** A second own unit that only the scripted status lists; it stands nowhere on the board. */
    private static final int OTHER_OWN_ID = 3;
    private static final Coords OWN_HEX = new Coords(5, 5);
    private static final Coords FOE_HEX = new Coords(8, 5);
    private static final Coords EMPTY_HEX = new Coords(6, 9);
    private static final Coords OTHER_HEX = new Coords(9, 9);
    /** The binds the board view's camera handles itself (C.4's last row, TOGGLE_ISO and the framing keys). */
    private static final Set<KeyCommandBind> CAMERA = EnumSet.of(KeyCommandBind.SCROLL_NORTH,
          KeyCommandBind.SCROLL_SOUTH, KeyCommandBind.SCROLL_EAST, KeyCommandBind.SCROLL_WEST,
          KeyCommandBind.CAMERA_ROTATE_LEFT, KeyCommandBind.CAMERA_ROTATE_RIGHT, KeyCommandBind.CAMERA_TILT_UP,
          KeyCommandBind.CAMERA_TILT_DOWN, KeyCommandBind.CAMERA_RESET, KeyCommandBind.CAMERA_FIT_BOARD,
          KeyCommandBind.TOGGLE_ISO, KeyCommandBind.ZOOM_IN, KeyCommandBind.ZOOM_OUT,
          KeyCommandBind.ZOOM_OVERVIEW_TOGGLE);

    /** Who handles a key press; exactly one does. */
    /** Who takes a key; NONE for Swing's GUI scale keys, which do nothing in the native window. */
    private enum Handler { HUD, CAMERA, SWING, NONE }

    /** One phase of the local player's view: whose turn it is and whether the movement planner runs. */
    private record Scenario(String name, GamePhase phase, boolean myTurn, boolean planner) {
        boolean planning() {
            return myTurn && phase.isMovement() && planner;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
          new Scenario("initiative report", GamePhase.INITIATIVE_REPORT, false, false),
          new Scenario("own deployment", GamePhase.DEPLOYMENT, true, false),
          new Scenario("own planned movement", GamePhase.MOVEMENT, true, true),
          new Scenario("own movement without the planner", GamePhase.MOVEMENT, true, false),
          new Scenario("opponent's movement", GamePhase.MOVEMENT, false, true),
          new Scenario("movement report", GamePhase.MOVEMENT_REPORT, false, false),
          new Scenario("own firing", GamePhase.FIRING, true, false),
          new Scenario("opponent's firing", GamePhase.FIRING, false, false),
          new Scenario("own targeting", GamePhase.TARGETING, true, false),
          new Scenario("own offboard", GamePhase.OFFBOARD, true, false),
          new Scenario("firing report", GamePhase.FIRING_REPORT, false, false),
          new Scenario("own physical", GamePhase.PHYSICAL, true, false),
          new Scenario("physical report", GamePhase.PHYSICAL_REPORT, false, false),
          new Scenario("end report", GamePhase.END_REPORT, false, false));

    /** One key press: the libGDX key, the AWT key and modifiers, and the default binds it invokes. */
    private record Key(int gdx, int awt, int modifiers, Set<KeyCommandBind> binds) { }

    /**
     * A1: for every phase and every default bind exactly one handler runs: the HUD (consumed, never forwarded and no
     * camera move), the camera, or Swing (one press and one release, with the modifiers of the press); Swing's GUI scale
     * keys reach none, as the GUI scale is Swing's alone. The phase
     * displays' own binds (TURN_*, UNDO_*, CANCEL, MOVE_*, TWIST_*) therefore never run twice. Esc with nothing to
     * close clears the selection or opens the Menu; MegaMek's own movement turn gets it only while its display has a
     * path, gear or hex pick to drop, which the scenarios' empty plans do not have.
     */
    @Test
    void everyKeyReachesExactlyOneHandlerInEveryPhase() throws Exception {
        run(routing -> {
            List<Key> keys = keys();
            assertTrue(keys.size() > 80, "The sweep covers the default binds");
            for (Scenario scenario : SCENARIOS) {
                routing.show(scenario);
                for (Key key : keys) {
                    Handler expected = expected(scenario, key, routing.hud.state.logOpen());
                    assertEquals(expected, routing.press(key), scenario + ", " + key.binds());
                    if (key.binds().contains(KeyCommandBind.CANCEL)) {
                        // With nothing else to close, Esc cleared the selection: the next keys start from it again.
                        routing.hud.state.restoreSelection();
                        routing.view.render();
                    }
                }
            }
        });
    }

    /**
     * C.4 clicks: the press's modifiers count (lead decision D5); the movement planner plans, MegaMek's board tool
     * takes the other phases' own turn (the targeting display's included, D1), firing and physical address the
     * clicked enemy, a right click opens the menu and changes no orders, and drags only move the camera.
     */
    @Test
    void boardClicksFollowThePhaseAndDragsOnlyMoveTheCamera() throws Exception {
        run(routing -> {
            routing.show(SCENARIOS.get(2));
            routing.click(EMPTY_HEX, Input.Buttons.LEFT, 0, 0);
            // With the surface height the pointer picked, which chooses a building's floor.
            verify(routing.moves).planTo(eq(EMPTY_HEX), eq(0), eq(false), anyFloat());
            // Ctrl pins the waypoint; Shift released before the button still turns the facing, as at its press
            // (GpuHud.leftClick).
            routing.click(OTHER_HEX, Input.Buttons.LEFT, InputEvent.CTRL_DOWN_MASK, InputEvent.CTRL_DOWN_MASK);
            verify(routing.moves).planTo(eq(OTHER_HEX), eq(0), eq(true), anyFloat());
            routing.click(OTHER_HEX, Input.Buttons.LEFT, InputEvent.SHIFT_DOWN_MASK, 0);
            verify(routing.moves).faceToward(OTHER_HEX, 0);
            routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
            assertEquals(FOE_ID, routing.hud.state.inspected, "An enemy is inspected, never planned to");
            routing.click(EMPTY_HEX, Input.Buttons.LEFT, InputEvent.ALT_DOWN_MASK, InputEvent.ALT_DOWN_MASK);
            // MegaMek's ruler on Alt (GpuBoardSource.isMeasurement), at the height the pointer shows.
            verify(routing.source).measure(eq(EMPTY_HEX), eq(InputEvent.ALT_DOWN_MASK), anyFloat());
            verify(routing.moves, times(2)).planTo(any(), anyInt(), anyBoolean(), anyFloat());
            clearInvocations(routing.moves);
            routing.click(EMPTY_HEX, Input.Buttons.RIGHT, 0, 0);
            assertTrue(shown(routing.find("context-menu-popover")), "A right click opens the context menu");
            verifyNoInteractions(routing.moves);
            routing.reset();

            routing.show(SCENARIOS.get(3));
            routing.click(EMPTY_HEX, Input.Buttons.LEFT, 0, 0);
            verify(routing.source).hover(EMPTY_HEX, 0);
            verify(routing.source).click(EMPTY_HEX, false, 0);
            verifyNoInteractions(routing.moves);

            routing.show(SCENARIOS.get(6));
            routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
            verify(routing.fire).focusTarget(TargetKey.unit(FOE_ID));
            for (Scenario unplanned : List.of(SCENARIOS.get(8), SCENARIOS.get(9))) {
                clearInvocations(routing.source, routing.fire);
                routing.show(unplanned);
                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                verify(routing.source).hover(FOE_HEX, 0);
                verify(routing.source).click(FOE_HEX, false, 0);
                verifyNoInteractions(routing.fire);
            }
            routing.show(SCENARIOS.get(11));
            routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
            verify(routing.physical).target(FOE_ID);
            routing.show(SCENARIOS.get(4));
            clearInvocations(routing.source);
            routing.click(OWN_HEX, Input.Buttons.LEFT, 0, 0);
            assertEquals(List.of(Entity.NONE, OWN_ID), List.of(routing.hud.state.inspected, routing.hud.state.focus()),
                  "Outside the local turn an own unit is the shown unit, not an inspected one");
            verify(routing.source, never()).selectUnit(anyInt());

            // Drags past the threshold: a left one clicks nothing, right pans, middle and Shift+right orbit.
            routing.show(SCENARIOS.get(2));
            clearInvocations(routing.source, routing.moves);
            BoardCamera camera = routing.view.boardCamera;
            Vector3 focus = camera.focus.cpy();
            float azimuth = camera.azimuth();
            routing.drag(EMPTY_HEX, Input.Buttons.LEFT, 0);
            verifyNoInteractions(routing.moves);
            verify(routing.source, never()).click(any(), anyBoolean(), anyInt());
            assertTrue(focus.epsilonEquals(camera.focus, .001f) && azimuth == camera.azimuth(), "A left drag");
            routing.drag(EMPTY_HEX, Input.Buttons.RIGHT, 0);
            assertFalse(focus.epsilonEquals(camera.focus, .001f), "A right drag pans");
            assertEquals(azimuth, camera.azimuth(), .001f);
            assertFalse(shown(routing.find("context-menu-popover")), "A drag opens no menu");
            routing.drag(EMPTY_HEX, Input.Buttons.MIDDLE, 0);
            assertNotEquals(azimuth, camera.azimuth(), .001f, "A middle drag orbits");
            azimuth = camera.azimuth();
            routing.drag(EMPTY_HEX, Input.Buttons.RIGHT, InputEvent.SHIFT_DOWN_MASK);
            assertNotEquals(azimuth, camera.azimuth(), .001f, "Shift turns a right drag into an orbit");
            verifyNoInteractions(routing.moves);

            // The wheel zooms at the pointer over the board only.
            float zoom = camera.camera.zoom;
            routing.wheel(routing.screen(EMPTY_HEX));
            assertTrue(camera.camera.zoom < zoom, "The wheel zooms over the board");
            Actor header = routing.find("phase-header");
            Vector3 overHeader = routing.screen(header);
            zoom = camera.camera.zoom;
            routing.wheel(overHeader);
            assertEquals(zoom, camera.camera.zoom, "Not over a panel");
            // A press on a panel belongs to it: no board click.
            routing.touch(overHeader, Input.Buttons.LEFT, 0, 0);
            verifyNoInteractions(routing.moves);
            verify(routing.source, never()).hover(any(), anyInt());
        });
    }

    /**
     * A2: the chat field takes the keys while it has the focus (the Enter that opens it types nothing, '1' sets no
     * movement mode and W pans nothing), modal Help holds WASD and Q/E until it closes, and a pending dialog takes every
     * key but posts nothing except its own answer to Enter or Esc, while its scrim takes the presses and the wheel.
     */
    @Test
    void aTextFieldOrAPendingDialogKeepsTheKeysAndPresses() throws Exception {
        run(routing -> {
            routing.show(SCENARIOS.get(2));
            BoardCamera camera = routing.view.boardCamera;
            routing.key(Input.Keys.ENTER, 0);
            routing.typed('\n');
            assertTrue(routing.hud.isTextEditing(), "Enter opens the chat with the focus in its field");
            routing.key(Input.Keys.NUM_1, 0);
            routing.typed('1');
            TextField chat = (TextField) routing.find("chat-input");
            assertEquals("1", chat.getText(), "Only the typed digit reaches the field");
            verify(routing.moves, never()).setMode(any());
            Vector3 focus = camera.focus.cpy();
            routing.hold(Input.Keys.W, () -> routing.advance(3, .1f));
            assertTrue(focus.epsilonEquals(camera.focus, .001f), "W types instead of panning");
            routing.with(InputEvent.SHIFT_DOWN_MASK, 0, 0, () -> {
                routing.processor.keyDown(Input.Keys.W);
                routing.advance(1, .1f);
                routing.processor.keyUp(Input.Keys.W);
            });
            assertTrue(focus.epsilonEquals(camera.focus, .001f), "Shift+W also stays with the text field");
            verify(routing.source, never()).key(anyInt(), anyBoolean(), anyInt());
            routing.key(Input.Keys.ESCAPE, 0);
            assertFalse(routing.hud.isTextEditing(), "Esc ends the typing");
            routing.key(Input.Keys.NUM_1, 0);
            verify(routing.moves).setMode(GpuMovePlan.Mode.WALK);

            // Help is modal (GpuHud.isModal): it holds the camera keys while open, and they work again once it closes.
            routing.hud.state.dialog = GpuHudState.Dialog.HELP;
            routing.view.render();
            float azimuth = camera.azimuth();
            routing.hold(Input.Keys.W, () -> routing.advance(3, .1f));
            routing.hold(Input.Keys.E, () -> routing.advance(3, .1f));
            assertTrue(focus.epsilonEquals(camera.focus, .001f), "W pans nothing with Help open");
            assertEquals(azimuth, camera.azimuth(), .001f, "nor does E turn");
            routing.key(Input.Keys.ESCAPE, 0);
            assertEquals(GpuHudState.Dialog.NONE, routing.hud.state.dialog, "One Esc closes Help from its list");
            assertNull(routing.hud.stage.getKeyboardFocus());
            routing.hold(Input.Keys.W, () -> routing.advance(3, .1f));
            assertFalse(focus.epsilonEquals(camera.focus, .001f), "W pans once Help is closed");
            routing.hold(Input.Keys.E, () -> routing.advance(3, .1f));
            assertEquals(27, turned(azimuth, camera.azimuth()), .01f, "E turns 90 degrees a second while held");

            // A pending dialog: no key acts, no press clicks, no wheel zooms, nothing is posted but its answer.
            camera.fit((BoardScene) field(routing.view, "scene"));
            routing.view.render();
            Vector3 board = routing.screen(EMPTY_HEX);
            DialogRequest choice = new DialogRequest(41, DialogKind.CHOICE, "Load unit", "Which unit?", false,
                  List.of("OK", "Cancel"), 0, 1, List.of(new DialogRow("Atlas", "", null, true),
                  new DialogRow("Archer", "", null, true)), List.of(0), "", false, null, null, null, List.of());
            routing.dialog.set(choice);
            routing.view.render();
            assertTrue(shown(routing.find("modal-dialog")));
            clearInvocations(routing.source, routing.moves, routing.fire, routing.physical);
            float zoom = camera.camera.zoom;
            focus.set(camera.focus);
            azimuth = camera.azimuth();
            // Every key but the dialog's own: Enter, Esc and its list's navigation (the arrows, Home and End).
            for (Key key : keys()) {
                if (!Set.of(KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT,
                      KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END).contains(key.awt())) {
                    assertEquals(Handler.HUD, routing.press(key), "The dialog takes " + key.binds());
                }
            }
            routing.advance(2, .1f);
            assertTrue(routing.hud.hit(Math.round(board.x), Math.round(board.y)), "The scrim covers the board");
            routing.touch(board, Input.Buttons.LEFT, 0, 0);
            routing.touch(board, Input.Buttons.RIGHT, 0, 0);
            routing.wheel(board);
            assertEquals(zoom, camera.camera.zoom);
            assertTrue(focus.epsilonEquals(camera.focus, .001f) && azimuth == camera.azimuth(), "The camera stays");
            verifyNoInteractions(routing.moves, routing.fire, routing.physical);
            verify(routing.source, never()).click(any(), anyBoolean(), anyInt());
            verify(routing.source, never()).hover(any(), anyInt());
            verify(routing.source, never()).selectUnit(anyInt());
            verify(routing.source, never()).answer(anyLong(), any());
            assertFalse(shown(routing.find("context-menu-popover")));
            routing.key(Input.Keys.DOWN, 0);
            routing.key(Input.Keys.ENTER, 0);
            verify(routing.source).answer(41, new DialogAnswer(0, List.of(1), null, false, List.of()));
            DialogRequest again = new DialogRequest(42, DialogKind.MESSAGE, "Confirm", "Continue?", false,
                  List.of("Yes", "No"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
            routing.dialog.set(again);
            routing.view.render();
            routing.key(Input.Keys.ESCAPE, 0);
            verify(routing.source).answer(42, DialogAnswer.cancelled(again));
            verify(routing.source, never()).key(anyInt(), anyBoolean(), anyInt());
        });
    }

    @Test
    void dockConfirmationPassesOutsideClicksAndTogglesFromItsTrigger() throws Exception {
        run(routing -> {
            GpuBoardSource.Frame frame = routing.frame(SCENARIOS.get(2));
            BoardScene scene = frame.scene();
            List<BoardScene.Command> commands = List.of(new BoardScene.Command(GpuBoardActions.SKIP_ID,
                  "Hold position", "", true, false, List.of(), () -> routing.moves.holdAll()));
            scene = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
                  scene.plannedPath(), scene.selectedId(), scene.phase(), commands, scene.light(), scene.firingLines(),
                  scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(), scene.fieldOfView());
            GpuHudData data = frame.panels();
            GpuBoardActions.PhaseInfo phase = new GpuBoardActions.PhaseInfo("", false, "", GpuBoardActions.SKIP_ID,
                  "", List.of(), List.of());
            data = new GpuHudData(phase, data.move(), data.fire(), data.physical(), data.record(), data.preview(),
                  data.chat(), data.toasts(), data.los(), data.players());
            routing.show(new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", frame.centerRequest(),
                  frame.boardGeneration(), "", frame.scenarioAtmosphere(), GpuReportLog.Snapshot.EMPTY,
                  frame.status(), data));
            Vector3 board = routing.screen(EMPTY_HEX);
            Actor trigger = routing.find("dock-hold-all");
            routing.press(trigger);
            assertTrue(shown(routing.find("dock-confirm")));
            assertFalse(routing.hud.isModal());
            assertSame(routing.find("dock-confirm-no"), routing.hud.stage.getKeyboardFocus());
            BoardCamera camera = routing.view.boardCamera;
            Vector3 focus = camera.focus.cpy();
            clearInvocations(routing.source, routing.moves);
            routing.key(Input.Keys.NUM_1, 0);
            routing.key(Input.Keys.F, 0);
            routing.key(Input.Keys.DEL, 0);
            routing.hold(Input.Keys.W, () -> routing.advance(3, .1f));
            assertTrue(focus.epsilonEquals(camera.focus, .001f));
            verifyNoInteractions(routing.moves);
            verify(routing.source, never()).key(anyInt(), anyBoolean(), anyInt());
            routing.capture("g6-hold-confirm-board");

            // The same outside click dismisses the card and reaches the board's normal movement handler.
            Coords picked = routing.pick(Math.round(board.x), Math.round(board.y));
            assertNotNull(picked);
            routing.touch(board, Input.Buttons.LEFT, 0, 0);
            assertFalse(shown(routing.find("dock-confirm")));
            verify(routing.moves).planTo(eq(picked), eq(0), eq(false), anyFloat());

            // An outside HUD button also receives its first click.
            routing.press(trigger);
            routing.press(routing.find("dock-turn-right"));
            assertFalse(shown(routing.find("dock-confirm")));
            verify(routing.moves).turn(1);
            routing.press(trigger);
            routing.press(trigger);
            assertFalse(shown(routing.find("dock-confirm")), "A second trigger click closes without reopening");
            verify(routing.moves, never()).holdAll();

            routing.press(trigger);
            routing.key(Input.Keys.ENTER, 0);
            assertFalse(shown(routing.find("dock-confirm")), "Enter initially cancels");
            routing.press(trigger);
            routing.key(Input.Keys.TAB, InputEvent.SHIFT_DOWN_MASK);
            assertSame(routing.find("dock-confirm-yes"), routing.hud.stage.getKeyboardFocus());
            routing.key(Input.Keys.ENTER, 0);
            verify(routing.moves).holdAll();
            assertFalse(shown(routing.find("dock-confirm")));
            routing.press(trigger);
            routing.key(Input.Keys.ESCAPE, 0);
            assertFalse(shown(routing.find("dock-confirm")));
            verify(routing.moves, never()).clearRoute();
            verify(routing.moves).holdAll();
        });
    }

    /**
     * A4 and W3: a dialog asked before the first board exists is drawn over the empty window and answered once; the
     * HUD draws without a board.
     */
    @Test
    void aDialogAskedBeforeTheFirstBoardIsDrawnAndAnsweredOnce() throws Exception {
        run(routing -> {
            routing.frame.set(new GpuBoardSource.Frame(null, List.of(), null, List.of(), "",
                  new BoardFocus(0, null), 0, "", BoardAtmosphere.DEFAULTS, GpuReportLog.Snapshot.EMPTY,
                  GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY));
            DialogRequest alert = new DialogRequest(61, DialogKind.MESSAGE, "Deployment",
                  "Your units are ready to deploy.", false, List.of("OK"), 0, -1, List.of(), List.of(), "", false, null,
                  null, null, List.of());
            routing.dialog.set(alert);
            routing.view.render();
            assertNull(field(routing.view, "scene"), "No board was drawn");
            assertTrue(shown(routing.find("modal-dialog")), "The dialog shows without a board");
            routing.capture("i1a1-modal-without-board");
            routing.key(Input.Keys.ENTER, 0);
            routing.view.render();
            routing.key(Input.Keys.ENTER, 0);
            verify(routing.source, times(1)).answer(eq(61L), any());
            routing.dialog.set(null);
            routing.view.render();
            assertFalse(shown(routing.find("modal-dialog")));
        });
    }

    /**
     * A3 and W6: the board fills the window, so a pick in the former menu and turn bands returns its hex and plans
     * there; Q turns continuously while held in both views.
     */
    @Test
    void theBoardFillsTheWindowAndTheTurnKeysAreContinuous() throws Exception {
        run(routing -> {
            routing.show(SCENARIOS.get(2));
            routing.capture("i1a1-routing-movement");
            BoardCamera camera = routing.view.boardCamera;
            // The top view, zoomed in on the board's middle: the board covers the whole window.
            camera.setIsometric(false);
            camera.center(BoardGeometry.center(new Coords(8, 8), 0));
            camera.zoom(.45f);
            routing.view.render();
            int x = 520;
            for (int y : new int[] { 6, 1080 - 6 }) {
                assertFalse(routing.hud.hit(x, y), "No panel at " + x + ", " + y);
                Coords picked = routing.pick(x, y);
                assertNotNull(picked, "The board reaches the window's edge at " + y);
                routing.touch(new Vector3(x, y, 0), Input.Buttons.LEFT, 0, 0);
                verify(routing.moves).planTo(eq(picked), eq(0), eq(false), anyFloat());
            }

            float azimuth = camera.azimuth();
            routing.hold(Input.Keys.Q, () -> routing.advance(3, .1f));
            assertEquals(-27, turned(azimuth, camera.azimuth()), .01f, "Q turns 90 degrees a second while held");
            azimuth = camera.azimuth();
            routing.advance(2, .1f);
            assertEquals(azimuth, camera.azimuth(), "The turn stops with the key");
            routing.view.setTacticalView(true);
            routing.view.render();
            azimuth = camera.azimuth();
            routing.hold(Input.Keys.Q, () -> routing.advance(3, .1f));
            assertEquals(-27, turned(azimuth, camera.azimuth()), .01f,
                  "Q turns at the same speed in the Tactical View");
            azimuth = camera.azimuth();
            routing.advance(2, .1f);
            assertEquals(azimuth, camera.azimuth(), "The tactical turn also stops with the key");
            assertTrue(camera.tactical(), "Rotation keeps the Tactical View active");
            routing.view.setTacticalView(false);
        });
    }

    /**
     * T6 without a server: a real phase display's list prompt (the cargo to place in minefield deployment) asked
     * through the client's input facade while the native window is presented. The real view over the real source
     * draws it on the board, its keys answer it, and the chosen object itself is placed.
     */
    @Test
    void aRealPhaseDisplaysListPromptIsAnsweredInTheNativeWindow() throws Exception {
        ClientGUI gui = GpuDialogFixture.routingClient();
        Client client = mock(Client.class);
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(gui.getClient()).thenReturn(client);
            GroundObject crate = GpuListPromptBridgeTest.groundObject(new Cargo(), "Supply crate", 2);
            GroundObject briefcase = GpuListPromptBridgeTest.groundObject(new Briefcase(), "Briefcase", 1);
            fixture.player.getGroundObjectsToPlace().addAll(List.of(crate, briefcase));
            DeployMinefieldDisplay display = GpuListPromptBridgeTest.placingCargo(gui);
            GpuDialogFixture.present(gui, fixture.view, fixture.source);
            try {
                Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
                configuration.setWindowedMode(1920, 1080);
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override
                    public void create() {
                        GpuBattleView view = null;
                        try {
                            System.out.println("GL renderer " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                            // One HUD unit per window pixel, as in the prototype's 1920 x 1080 captures.
                            float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                            GpuTestSupport.onSwing(() -> {
                                preferences.setBoardUiScale(preference);
                                fixture.source.refresh();
                                return null;
                            });
                            view = new GpuBattleView(fixture.source);
                            view.create();
                            // The HUD draws once the board is presented, after the terrain is built.
                            GpuBoardTestUi.present(view);
                            FutureTask<List<ICarryable>> placing = new FutureTask<>(
                                  () -> GpuListPromptBridgeTest.placeAt(display, fixture, EMPTY_HEX));
                            SwingUtilities.invokeLater(placing);
                            long deadline = System.nanoTime() + SECONDS.toNanos(20);
                            while (fixture.source.dialog() == null) {
                                assertTrue(System.nanoTime() < deadline, "The prompt reaches the native window");
                                view.render();
                            }
                            view.render();
                            view.render();
                            GpuHud hud = (GpuHud) field(view, "ui");
                            assertTrue(shown(hud.stage.getRoot().findActor("modal-dialog")), "The HUD draws it");
                            UiTestStage.capture("i1a1-cargo-prompt", Gdx.graphics.getBackBufferWidth(),
                                  Gdx.graphics.getBackBufferHeight()).dispose();
                            InputProcessor processor = Gdx.input.getInputProcessor();
                            for (int key : new int[] { Input.Keys.DOWN, Input.Keys.ENTER }) {
                                processor.keyDown(key);
                                processor.keyUp(key);
                            }
                            assertEquals(List.of(briefcase), placing.get(20, SECONDS), "The chosen object is placed");
                            view.render();
                            assertFalse(shown(hud.stage.getRoot().findActor("modal-dialog")));
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            if (view != null) {
                                view.dispose();
                            }
                            Gdx.app.exit();
                        }
                    }
                }, configuration);
            } finally {
                GpuDialogFixture.dismiss();
                GpuTestSupport.onSwing(() -> {
                    display.removeAllListeners();
                    preferences.setBoardUiScale(originalScale);
                    return null;
                });
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("The native window did not answer the prompt", failure.get());
        }
    }

    /**
     * The unit sheet over the real source, the client's own records: a board click on the own Atlas outside the local
     * turn makes it the shown unit, the card's Unit record opens its sheet, whose tab row ends in the ✕; on the ARMOR
     * tab a doll location's click opens the critical table with that location's block flashing; the ✕ closes the
     * sheet at once.
     */
    @Test
    void theUnitSheetOpensFromTheBoardOverTheRealSource() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            try {
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override
                    public void create() {
                        GpuBattleView view = null;
                        try {
                            // One HUD unit per window pixel, as in the prototype's 1920 x 1080 captures.
                            float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                            GpuTestSupport.onSwing(() -> {
                                preferences.setBoardUiScale(preference);
                                fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                                fixture.source.refresh();
                                return null;
                            });
                            view = new GpuBattleView(fixture.source);
                            view.create();
                            // The HUD draws once the board is presented, after the terrain is built.
                            GpuBoardTestUi.present(view);
                            settle(fixture, view);
                            GpuHud hud = (GpuHud) field(view, "ui");
                            Vector3 atlas = view.screenPosition(OWN_HEX);
                            press(view, Math.round(atlas.x), Math.round(atlas.y));
                            settle(fixture, view);
                            assertEquals(OWN_ID, hud.state.focus(), "the board click shows the own Atlas");
                            press(view, hud, hud.stage.getRoot().findActor("unit-card-record"));
                            settle(fixture, view);
                            Actor close = hud.stage.getRoot().findActor("record-sheet-close");
                            assertTrue(hud.state.recordOpen && shown(close), "the sheet with its ✕");
                            UiTestStage.capture("i1a2-unit-sheet-from-board", Gdx.graphics.getBackBufferWidth(),
                                  Gdx.graphics.getBackBufferHeight()).dispose();

                            press(view, hud, hud.stage.getRoot().findActor("record-tab-armor"));
                            settle(fixture, view);
                            GpuPaperdoll doll = hud.stage.getRoot().findActor("record-doll-armor");
                            Vector2 arm = hud.stage.stageToScreenCoordinates(
                                  doll.localToStageCoordinates(GpuUnitPanelTest.inside(doll, "LA")));
                            press(view, Math.round(arm.x), Math.round(arm.y));
                            view.render();
                            assertEquals(GpuHudState.SheetTab.SYSTEMS, hud.state.sheetTab, "the critical table");
                            Table block = hud.stage.getRoot().findActor(GpuCriticalTable.blockName("LA"));
                            assertNotNull(block.findActor("record-flash"), "the arm's block flashes");
                            UiTestStage.capture("i1a2-doll-flash", Gdx.graphics.getBackBufferWidth(),
                                  Gdx.graphics.getBackBufferHeight()).dispose();

                            press(view, hud, hud.stage.getRoot().findActor("record-sheet-close"));
                            assertFalse(hud.state.recordOpen, "the ✕ closes the sheet");
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            if (view != null) {
                                view.dispose();
                            }
                            Gdx.app.exit();
                        }
                    }
                }, configuration);
            } finally {
                GpuTestSupport.onSwing(() -> {
                    preferences.setBoardUiScale(originalScale);
                    return null;
                });
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("The unit sheet over the real source failed", failure.get());
        }
    }

    /** Lets the source capture what the view posted, then draws its frames. */
    private static void settle(GpuBoardFixture fixture, GpuBattleView view) throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            GpuTestSupport.onSwing(() -> {
                fixture.source.refresh();
                return null;
            });
            view.render();
        }
    }

    /** A left press and release at a window point (y down) through the view's input. */
    private static void press(GpuBattleView view, int x, int y) {
        InputProcessor processor = Gdx.input.getInputProcessor();
        processor.touchDown(x, y, 0, Input.Buttons.LEFT);
        processor.touchUp(x, y, 0, Input.Buttons.LEFT);
        view.render();
    }

    /** A left press and release on a HUD actor's centre through the view's input. */
    private static void press(GpuBattleView view, GpuHud hud, Actor actor) {
        assertNotNull(actor);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        press(view, Math.round(point.x), Math.round(point.y));
    }

    /**
     * Outside the local turn a click on an own unit, on its forces row or on the
     * board, makes it the unit the card and the forces list show as selected (mint), in the initiative phases, a report
     * phase, the end report and the opponent's movement turn. MegaMek's selection is not asked, the pick holds over
     * later captures even where no turn accepts the unit, a friendly row never takes the enemy's coral, and an enemy
     * is still inspected. Inside the local turn an own unit that cannot act is inspected with mint edges.
     */
    @Test
    void anOwnUnitClickedOutsideTheLocalTurnBecomesTheSelectedUnit() throws Exception {
        run(routing -> {
            GpuHudState state = routing.hud.state;
            for (Scenario scenario : List.of(new Scenario("initiative", GamePhase.INITIATIVE, false, false),
                  SCENARIOS.get(0), SCENARIOS.get(4), SCENARIOS.get(10), SCENARIOS.get(13))) {
                // In the end report no turn accepts the units: nothing is the focus until a click picks one.
                boolean pending = !scenario.phase().isEndReport();
                GpuBoardSource.Frame shown = routing.frame(GpuHudFixtures.status(3, scenario.phase(), false,
                      Entity.NONE, 1, GpuHudFixtures.unit(OWN_ID, OWN, false, pending),
                      GpuHudFixtures.unit(OTHER_OWN_ID, OWN, false, pending),
                      GpuHudFixtures.unit(FOE_ID, ENEMY, false, false)), GpuHudData.EMPTY);
                routing.show(shown);
                assertEquals(pending ? OWN_ID : Entity.NONE, state.focus(), scenario + ": the C.5 focus");
                clearInvocations(routing.source);

                routing.press(routing.find("forces-unit-" + OTHER_OWN_ID));
                assertSelected(routing, OTHER_OWN_ID, scenario + ", forces row");
                if (scenario == SCENARIOS.get(0)) {
                    routing.capture("i1a2-initiative-friendly-click");
                }
                // A later capture of the same phase keeps the pick.
                routing.frame.set(routing.frame(GpuHudFixtures.status(3, scenario.phase(), false, Entity.NONE, 1,
                      GpuHudFixtures.unit(OWN_ID, OWN, false, pending),
                      GpuHudFixtures.unit(OTHER_OWN_ID, OWN, false, pending),
                      GpuHudFixtures.unit(FOE_ID, ENEMY, false, false)), GpuHudData.EMPTY));
                routing.view.render();
                assertSelected(routing, OTHER_OWN_ID, scenario + ", a later capture");

                routing.click(OWN_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertSelected(routing, OWN_ID, scenario + ", board click");
                verify(routing.source, never()).selectUnit(anyInt());
                verify(routing.fire, never()).selectUnit(anyInt());

                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                assertEquals(FOE_ID, state.inspected, scenario + ": an enemy is inspected");
                assertEquals(OWN_ID, state.focus());
            }

            // The local movement turn: an own unit that cannot act now is inspected, never in the enemy's coral.
            routing.show(routing.frame(GpuHudFixtures.status(3, GamePhase.MOVEMENT, true, OWN_ID, 1,
                  GpuHudFixtures.unit(OWN_ID, OWN, true, true), GpuHudFixtures.unit(OTHER_OWN_ID, OWN, false, false),
                  GpuHudFixtures.unit(FOE_ID, ENEMY, false, false)),
                  GpuHudFixtures.panels(GpuHudFixtures.move(true, List.of()), GpuFireOrders.Snapshot.EMPTY,
                        GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY)));
            routing.press(routing.find("forces-unit-" + OTHER_OWN_ID));
            assertEquals(OTHER_OWN_ID, state.inspected);
            UiButton row = routing.find("forces-unit-" + OTHER_OWN_ID);
            assertSame(row.getSkin().getDrawable("row-friend"), row.getBackground(), "a friendly unit's mint edges");
            routing.capture("i1a2-forces-own-turn-inspected");

            // The card's ✕ closes the card and deselects: no card and no
            // highlighted row while the actor keeps its turn; a click on a unit shows the card again.
            routing.press(routing.find("unit-card-close"));
            routing.view.render();
            assertEquals(List.of(Entity.NONE, Entity.NONE), List.of(state.inspected, state.cardUnit()), "the ✕");
            assertFalse(shown(routing.find("unit-card")), "closes the card");
            for (int id : List.of(OWN_ID, OTHER_OWN_ID)) {
                UiButton unitRow = routing.find("forces-unit-" + id);
                assertFalse(unitRow.isChecked() || unitRow.getBackground() == unitRow.getSkin().getDrawable("row-friend"),
                      "and highlights no row: " + id);
            }
            verify(routing.source, never()).selectUnit(anyInt());
            routing.press(routing.find("forces-unit-" + OTHER_OWN_ID));
            routing.view.render();
            assertEquals(OTHER_OWN_ID, state.cardUnit(), "A click shows the card again");
            assertTrue(shown(routing.find("unit-card")));
        });
    }

    /**
     * The card's ✕, on the selected unit as on an inspected one, clears the
     * selection. In the local turn no unit is then selected at all: no card, no unit commands in the dock (it keeps
     * only the chat), no selected row; a hex does nothing, an enemy is only inspected, and the keys of MegaMek's
     * current unit stay with the HUD while a menu-bar bind still reaches MegaMek. A click on the own unit selects it
     * again, afresh.
     */
    @Test
    void theCardsCloseButtonClearsTheSelection() throws Exception {
        run(routing -> {
            GpuHudState state = routing.hud.state;
            List<Key> keys = keys();
            for (Scenario scenario : List.of(SCENARIOS.get(2), SCENARIOS.get(6), SCENARIOS.get(11))) {
                routing.show(scenario);
                assertEquals(OWN_ID, state.cardUnit(), scenario + ": the acting unit is the selected one");
                // The dock's head (the unit's title) comes with its commands; the chat-only dock has none.
                boolean dock = shown(routing.find("dock-title"));
                routing.press(routing.find("unit-card-close"));
                routing.view.render();
                assertEquals(List.of(Entity.NONE, Entity.NONE), List.of(state.inspected, state.cardUnit()),
                      scenario + ": the ✕ clears the selection");
                assertFalse(shown(routing.find("unit-card")), scenario + ": no card");
                assertFalse(shown(routing.find("dock-title")), scenario + ": the dock keeps only the chat");
                assertFalse(routing.<UiButton>find("forces-unit-" + OWN_ID).isChecked(), scenario + ": no row");
                clearInvocations(routing.source, routing.moves, routing.fire);

                routing.click(EMPTY_HEX, Input.Buttons.LEFT, 0, 0);
                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertEquals(FOE_ID, state.inspected, scenario + ": an enemy is inspected");
                assertFalse(shown(routing.find("dock-title")), scenario + ": and the selection stays cleared");
                assertEquals(Handler.CAMERA, routing.press(key(keys, KeyCommandBind.TURN_LEFT)),
                      scenario + ": Shift+A pans even with no unit selected");
                for (KeyCommandBind bind : List.of(KeyCommandBind.DONE,
                      KeyCommandBind.UNDO_LAST_STEP, KeyCommandBind.FIRE, KeyCommandBind.NEXT_TARGET,
                      KeyCommandBind.PHYS_PUNCH, KeyCommandBind.CENTER_ON_SELECTED)) {
                    assertEquals(Handler.HUD, routing.press(key(keys, bind)), scenario + ": " + bind);
                }
                assertEquals(Handler.SWING, routing.press(key(keys, KeyCommandBind.HEX_COORDS)),
                      scenario + ": a menu-bar bind reaches MegaMek");
                verifyNoInteractions(routing.moves);
                verify(routing.source, never()).click(any(), anyBoolean(), anyInt());
                verify(routing.source, never()).selectUnit(anyInt());
                verify(routing.fire, never()).selectUnit(anyInt());
                verify(routing.fire, never()).focusTarget(any(TargetKey.class));

                routing.click(OWN_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                if (scenario.phase().isFiring()) {
                    verify(routing.fire).selectUnit(OWN_ID);
                } else {
                    verify(routing.source).selectUnit(OWN_ID);
                }
                assertEquals(OWN_ID, state.cardUnit(), scenario + ": a click selects the unit again");
                assertEquals(dock, shown(routing.find("dock-title")), scenario + ": with its dock");
            }
        });
    }

    /** The default key of {@code bind}. */
    private static Key key(List<Key> keys, KeyCommandBind bind) {
        return keys.stream().filter(key -> key.binds().contains(bind)).findFirst().orElseThrow();
    }

    /**
     * The unit the card shows is the one highlighted row of the forces list, in the initiative phase, the own movement
     * turn and the opponent's. Panel clicks and board clicks switch it from a friendly unit to the enemy and back: a
     * friendly unit's forces row, the enemy's row in the forces list's contacts, never a second row; in the own turn
     * the acting unit keeps its "Acting now" line meanwhile. The forces grid's tiles follow the same rule.
     */
    @Test
    void theCardsUnitIsTheOneHighlightedRowOfTheLists() throws Exception {
        run(routing -> {
            GpuHudState state = routing.hud.state;
            for (Scenario scenario : List.of(new Scenario("initiative", GamePhase.INITIATIVE, false, false),
                  SCENARIOS.get(2), SCENARIOS.get(4))) {
                boolean own = scenario.myTurn();
                // In the own turn the second own unit has moved; otherwise it waits for its turn as the first does.
                routing.show(routing.frame(GpuHudFixtures.status(3, scenario.phase(), own,
                      own ? OWN_ID : Entity.NONE, 1, GpuHudFixtures.unit(OWN_ID, OWN, own, true),
                      GpuHudFixtures.unit(OTHER_OWN_ID, OWN, false, !own),
                      GpuHudFixtures.unit(FOE_ID, ENEMY, false, false)),
                      GpuHudFixtures.panels(GpuHudFixtures.move(scenario.planner(), List.of()),
                            GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY,
                            GpuUnitRecord.Snapshot.EMPTY)));
                // The C.5 focus: the Atlas, or after the own turn the next own unit.
                assertHighlighted(routing, state.focus(), scenario + ": the focus");

                // Panel clicks: the other friendly unit, the enemy's row in the forces list's contacts, the first
                // friendly unit.
                routing.press(routing.find("forces-unit-" + OTHER_OWN_ID));
                assertHighlighted(routing, OTHER_OWN_ID, scenario + ": a friendly forces row");
                if (own) {
                    assertEquals("Acting now", routing.<GpuHudKit.UnitRow>find("forces-unit-" + OWN_ID).line
                          .getText().toString(), scenario + ": the actor keeps its line");
                }
                routing.press(routing.find("forces-tab-contacts"));
                routing.press(routing.find("forces-unit-" + FOE_ID));
                assertHighlighted(routing, FOE_ID, scenario + ": the enemy's contacts row");
                routing.press(routing.find("forces-tab-friendly"));
                if (own) {
                    assertEquals("Acting now", routing.<GpuHudKit.UnitRow>find("forces-unit-" + OWN_ID).line
                          .getText().toString(), scenario + ": the actor keeps its line, unhighlighted");
                }
                routing.press(routing.find("forces-unit-" + OWN_ID));
                assertHighlighted(routing, OWN_ID, scenario + ": the first friendly forces row");

                // Board clicks: the other friendly unit picked in the list, then the enemy, then the Atlas.
                routing.press(routing.find("forces-unit-" + OTHER_OWN_ID));
                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertHighlighted(routing, FOE_ID, scenario + ": the enemy clicked on the board");
                routing.click(OWN_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertHighlighted(routing, OWN_ID, scenario + ": the Atlas clicked on the board");

                // The forces list's contacts tab highlights the board-clicked enemy, and still no friendly row.
                routing.press(routing.find("forces-tab-contacts"));
                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertHighlighted(routing, FOE_ID, scenario + ": the enemy in the contacts tab");
                routing.press(routing.find("forces-tab-friendly"));
                if (scenario.phase() == GamePhase.INITIATIVE) {
                    routing.capture("ux2-highlight-initiative-enemy");
                }
                routing.key(Input.Keys.ESCAPE, 0);
                routing.view.render();
                assertEquals(Entity.NONE, state.inspected, scenario + ": Esc ends the inspection");
                assertHighlighted(routing, OWN_ID, scenario + ": the focus again");

                // The grid's tiles: the focus's in mint; another friendly unit's click picks it (outside the turn) or
                // inspects it (inside, mint edges); no friendly tile while the enemy is shown.
                state.forcesGrid = true;
                routing.view.render();
                assertTile(routing, OWN_ID, "field-focused", scenario + ": the focus's tile");
                assertTile(routing, OTHER_OWN_ID, "row", scenario + ": another tile");
                routing.press(routing.find("forces-tile-" + OTHER_OWN_ID));
                assertTile(routing, OTHER_OWN_ID, own ? "row-friend" : "field-focused", scenario + ": its click");
                assertTile(routing, OWN_ID, "row", scenario + ": one tile");
                routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
                routing.view.render();
                assertTile(routing, OTHER_OWN_ID, "row", scenario + ": no friendly tile while the enemy is shown");
                assertTile(routing, OWN_ID, "row", scenario + ": nor the focus's");
                state.forcesGrid = false;
            }
        });
    }

    /**
     * Item 33.2: a list takes the mouse wheel only while the pointer is over it. Over the forces list the wheel scrolls
     * the list and leaves the camera; once the pointer has moved on to the board the wheel zooms the camera and leaves
     * the list, also after a press on one of the list's rows.
     */
    @Test
    void aListTakesTheWheelOnlyWhileThePointerIsOverIt() throws Exception {
        run(routing -> {
            // Thirty more own units, more than the list has room for.
            List<GpuBattleStatus.UnitStatus> units = new ArrayList<>(List.of(GpuHudFixtures.unit(OWN_ID, OWN, false,
                  true), GpuHudFixtures.unit(FOE_ID, ENEMY, false, false)));
            for (int id = 10; id < 40; id++) {
                units.add(GpuHudFixtures.unit(id, OWN, false, true));
            }
            routing.show(routing.frame(GpuHudFixtures.status(3, GamePhase.INITIATIVE_REPORT, false, Entity.NONE, 1,
                  units.toArray(GpuBattleStatus.UnitStatus[]::new)), GpuHudData.EMPTY));
            ScrollPane list = routing.find("forces-list");
            assertTrue(list.isScrollY(), "the forces list scrolls");
            BoardCamera camera = routing.view.boardCamera;
            Vector3 overList = routing.screen(list);
            Vector3 board = routing.screen(EMPTY_HEX);
            for (boolean pressed : new boolean[] { false, true }) {
                list.setScrollY(0);
                list.updateVisualScroll();
                routing.move(overList);
                if (pressed) {
                    // A press on a row takes the wheel as well (ScrollPane), and the row selects its unit.
                    routing.press(routing.find("forces-unit-12"));
                }
                float zoom = camera.camera.zoom;
                float top = list.getScrollY();
                routing.wheel(overList, 1);
                assertTrue(list.getScrollY() > top, "the wheel over the list scrolls it, pressed " + pressed);
                assertEquals(zoom, camera.camera.zoom, "and leaves the camera");
                float scrolled = list.getScrollY();
                routing.move(board);
                routing.wheel(board, 1);
                assertNotEquals(zoom, camera.camera.zoom, "on the board the wheel zooms, pressed " + pressed);
                assertEquals(scrolled, list.getScrollY(), "and leaves the list");
                assertNull(routing.hud.stage.getScrollFocus(), "no widget holds the wheel");
            }
        });
    }

    /**
     * The one highlighted row of the forces list is the unit's: its forces row, for an enemy while the list shows its
     * contacts; the card shows the unit, an enemy on the card for other units.
     */
    private static void assertHighlighted(Routing routing, int unitId, String when) {
        Set<String> expected = new HashSet<>();
        boolean enemy = unitId == FOE_ID;
        if (!enemy || shown(routing.find("forces-unit-" + unitId))) {
            expected.add("forces-unit-" + unitId);
        }
        Set<String> highlighted = new HashSet<>();
        for (int id : List.of(OWN_ID, FOE_ID, OTHER_OWN_ID)) {
            GpuHudKit.UnitRow row = routing.find("forces-unit-" + id);
            if (row != null && shown(row) && (row.isChecked()
                  || row.getBackground() == row.getSkin().getDrawable("row-friend")
                  || row.getBackground() == row.getSkin().getDrawable("row-foe"))) {
                highlighted.add("forces-unit-" + id);
            }
        }
        assertEquals(expected, highlighted, when + ": the highlighted rows");
        Label name = routing.find(enemy ? "other-unit-card-name" : "unit-card-name");
        assertEquals(UiTheme.upper("Unit " + unitId), name.getText().toString(), when + ": the card");
    }

    /** The forces grid's tile of the unit shows the named skin drawable. */
    private static void assertTile(Routing routing, int unitId, String drawable, String when) {
        Table tile = routing.find("forces-tile-" + unitId);
        assertNotNull(tile, when);
        Skin skin = routing.<UiButton>find("utility-map").getSkin();
        assertSame(skin.getDrawable(drawable), tile.getBackground(), when);
    }

    /** The unit is the shown, selected one: the focus, its forces row pressed in mint, the card showing it. */
    private static void assertSelected(Routing routing, int unitId, String when) {
        assertEquals(List.of(unitId, Entity.NONE), List.of(routing.hud.state.focus(), routing.hud.state.inspected),
              when + ": focus and inspection");
        for (int id : List.of(OWN_ID, OTHER_OWN_ID)) {
            UiButton row = routing.find("forces-unit-" + id);
            assertEquals(id == unitId, row.isChecked(), when + ": row " + id + " selected");
            assertNotEquals(row.getSkin().getDrawable("row-foe"), row.getBackground(), when + ": a friendly row");
        }
        Label name = routing.find("unit-card-name");
        assertEquals(UiTheme.upper("Unit " + unitId), name.getText().toString(), when + ": the card");
    }

    /**
     * L5 and N9 through the real view: the Menu's Players opens the Players panel; Esc on one of the Menu's pages goes
     * back to its first page, where the next Esc closes it; a switch of a page applies at once and keeps the Menu open.
     * One Esc closes the Menu, the Players panel and the Tuning panel with its open choice list, from the keyboard focus
     * inside them.
     */
    @Test
    void theMenuPagesGoBackOnEscAndOneEscClosesEachDialog() throws Exception {
        run(routing -> {
            List<String> ran = new CopyOnWriteArrayList<>();
            routing.menuBar = GpuHelpMenuPlayersSmokeTest.menuBar(routing.fixture, ran);
            routing.show(SCENARIOS.get(4));
            GpuHudState state = routing.hud.state;
            Actor menu = routing.find("menu-panel");
            routing.press(routing.find("utility-menu"));
            assertEquals(GpuHudState.Dialog.MENU, state.dialog, "the Menu utility opens the Menu");
            routing.choose(menu, Messages.getString("GpuBoard.hud.players.title"));
            assertEquals(GpuHudState.Dialog.PLAYERS, state.dialog, "Players opens the Players panel");
            routing.key(Input.Keys.ESCAPE, 0);
            routing.view.render();

            routing.press(routing.find("utility-menu"));
            routing.choose(menu, Messages.getString("GpuBoard.hud.menu.display"));
            routing.view.render();
            assertNotNull(routing.find(GpuGameMenu.BACK), "the Display page");
            routing.capture("i1a2-menu-view");
            routing.press(routing.find(ClientGUI.VIEW_TOGGLE_SHOW_OBJECTS));
            routing.view.render();
            assertEquals(GpuHudState.Dialog.MENU, state.dialog, "a switch keeps the Menu open");
            routing.key(Input.Keys.ESCAPE, 0);
            routing.view.render();
            assertEquals(GpuHudState.Dialog.MENU, state.dialog, "Esc on a page goes back");
            assertNull(routing.find(GpuGameMenu.BACK), "to the first page");
            routing.key(Input.Keys.ESCAPE, 0);
            routing.view.render();
            assertEquals(GpuHudState.Dialog.NONE, state.dialog, "and Esc there closes the Menu");
            routing.press(routing.find("utility-menu"));
            routing.choose(menu, Messages.getString("GpuBoard.hud.menu.display"));
            routing.view.render();
            routing.press(routing.find(ClientGUI.VIEW_TOGGLE_SHOW_OBJECTS));
            routing.view.render();
            routing.key(Input.Keys.ESCAPE, 0);
            routing.key(Input.Keys.ESCAPE, 0);
            routing.view.render();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(List.of(ClientGUI.VIEW_TOGGLE_SHOW_OBJECTS, ClientGUI.VIEW_TOGGLE_SHOW_OBJECTS), ran,
                  "the switch runs its item, and again to restore it");
            // One Esc closes each dialog from the focus inside it.
            routing.press(routing.find("utility-menu"));
            assertTrue(routing.hud.stage.getKeyboardFocus().isDescendantOf(menu), "the Menu's list has the focus");
            routing.key(Input.Keys.ESCAPE, 0);
            assertEquals(GpuHudState.Dialog.NONE, state.dialog, "one Esc closes the Menu");
            assertNull(routing.hud.stage.getKeyboardFocus());
            routing.key(Input.Keys.G, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            routing.view.render();
            assertEquals(GpuHudState.Dialog.PLAYERS, state.dialog);
            assertTrue(routing.hud.stage.getKeyboardFocus().isDescendantOf(routing.find("players-panel")));
            routing.key(Input.Keys.ESCAPE, 0);
            assertEquals(GpuHudState.Dialog.NONE, state.dialog, "one Esc closes the Players panel");
            // The next frame lays out the closed dialog's backdrop, which takes presses until then.
            routing.view.render();
            routing.press(routing.find("tuning-button"));
            routing.press(routing.find("tuning-atmosphere-tab"));
            routing.press(routing.find("tuning-atmosphere-pressure"));
            Actor choices = routing.find("tuning-choices");
            assertTrue(shown(choices), "the pressure's choices");
            assertTrue(routing.hud.stage.getKeyboardFocus().isDescendantOf(choices));
            routing.key(Input.Keys.ESCAPE, 0);
            routing.view.render();
            assertEquals(GpuHudState.Dialog.NONE, state.dialog, "one Esc closes the Tuning panel");
            assertFalse(shown(choices), "with its choices");
            assertNull(routing.hud.stage.getKeyboardFocus());
        });
    }

    /**
     * F1, L6, L7 and the sheet's ✕: in the own firing turn an enemy click focuses it and assigns the armed weapon once;
     * a sensor contact goes to the fire orders alone, whose toast refuses it, and the weapon stays armed. A press on
     * the open context menu runs its item and no board tool; the unit sheet's ✕ closes the sheet at once, where Esc
     * would first fold the expanded weapon row.
     */
    @Test
    void firingClicksPanelRowsAndPopoversTakeTheirOwnRoutes() throws Exception {
        run(routing -> {
            GpuHudState state = routing.hud.state;
            routing.show(SCENARIOS.get(6));
            state.armedWeapon = 4;
            routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
            verify(routing.fire).focusTarget(TargetKey.unit(FOE_ID));
            verify(routing.fire).assign(4, TargetKey.unit(FOE_ID));
            assertEquals(-1, state.armedWeapon, "the armed weapon is used once");
            clearInvocations(routing.fire);
            routing.show(routing.frame(GpuHudFixtures.status(3, GamePhase.FIRING, true, OWN_ID, 1,
                  GpuHudFixtures.unit(OWN_ID, OWN, true, true),
                  GpuHudFixtures.unit(FOE_ID, ENEMY, false, false, 1, true)), GpuHudData.EMPTY));
            state.armedWeapon = 4;
            routing.click(FOE_HEX, Input.Buttons.LEFT, 0, 0);
            verify(routing.fire).focusTarget(TargetKey.unit(FOE_ID));
            verify(routing.fire, never()).assign(anyInt(), any(TargetKey.class));
            assertEquals(4, state.armedWeapon, "a sensor contact leaves the weapon armed");

            routing.show(SCENARIOS.get(4));
            routing.click(FOE_HEX, Input.Buttons.RIGHT, 0, 0);
            Actor popover = routing.find("context-menu-popover");
            assertTrue(shown(popover), "the enemy's menu");
            // Its actions are MegaMek's map menu, which the source captures for the clicked unit (GpuContextMenu).
            GpuBoardSource.Frame open = routing.frame.get();
            MapMenu.UnitAction center = new MapMenu.UnitAction(MapMenu.UnitActionType.CENTER_UNIT, FOE_ID);
            routing.frame.set(new GpuBoardSource.Frame(open.scene(), open.timeline(), new BoardScene.Context(FOE_HEX,
                  FOE_ID, List.of(new BoardScene.Command(center.type() + ":" + FOE_ID,
                  Messages.getString("GpuBoard.hud.common.centerCamera"), "", true, false, false, List.of(), () -> { },
                  "", null, center)), state.turnLocked(open.status())), open.globalCommands(), open.tooltip(),
                  open.centerRequest(), open.boardGeneration(), open.actorName(), open.scenarioAtmosphere(),
                  open.reports(), open.status(), open.panels()));
            routing.view.render();
            routing.capture("i1a2-context-menu-board");
            clearInvocations(routing.source, routing.moves);
            routing.choose(popover, Messages.getString("GpuBoard.hud.common.centerCamera"));
            verify(routing.source).locateUnit(FOE_ID);
            verify(routing.source, never()).hover(any(), anyInt());
            verify(routing.source, never()).click(any(), anyBoolean(), anyInt());
            verifyNoInteractions(routing.moves);
            assertFalse(shown(popover), "the item closed the menu");

            state.inspected = Entity.NONE;
            state.recordOpen = true;
            state.expandedWeapon = "Medium Laser";
            routing.view.render();
            Actor close = routing.find("record-sheet-close");
            assertTrue(shown(close), "the sheet's ✕");
            routing.press(close);
            assertFalse(state.recordOpen, "the ✕ closes the sheet at once");
        });
    }

    /**
     * L1 and L4: the Tactical View's north mark is centred at top 84 over the board, and a middle-area panel with no
     * room for its minimum height hides instead of spilling over the dock: the LOS card below the initiative card on
     * a 900 x 600 stage (E rule 6; the display scale keeps a real window at 960 x 640 units at least), while at the
     * 960 x 640 minimum it shows above the dock.
     */
    @Test
    void theNorthMarkIsPlacedAndASqueezedPanelHidesAboveTheDock() throws Exception {
        run(routing -> {
            routing.show(SCENARIOS.get(4));
            Actor north = routing.find("tactical-north");
            assertFalse(shown(north), "only in the Tactical View");
            routing.view.setTacticalView(true);
            routing.view.render();
            routing.view.render();
            assertTrue(shown(north));
            Vector2 corner = north.localToStageCoordinates(new Vector2());
            float stageWidth = routing.hud.stage.getWidth();
            float stageHeight = routing.hud.stage.getHeight();
            assertEquals(stageWidth / 2, corner.x + north.getWidth() / 2, .51f, "centred");
            assertEquals(GpuUtilityBar.NORTH_TOP, stageHeight - corner.y - north.getHeight(), .51f, "its top");
            routing.capture("i1a2-tactical-north");
            routing.view.setTacticalView(false);
        });
    }

    /** C.4's handler of a key press: the HUD's hotkeys first, then the camera binds, then MegaMek's Swing keys. */
    private static Handler expected(Scenario scenario, Key key, boolean logOpen) {
        // Shift+WASD now accelerates the camera instead of stepping, turning or twisting the unit.
        if (key.modifiers() == InputEvent.SHIFT_DOWN_MASK
              && Set.of(KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D).contains(key.awt())) {
            return Handler.CAMERA;
        }
        Set<KeyCommandBind> binds = key.binds();
        if (binds.stream().anyMatch(bind -> hudBind(scenario, bind, logOpen))) {
            return Handler.HUD;
        }
        if (binds.contains(KeyCommandBind.INC_GUI_SCALE) || binds.contains(KeyCommandBind.DEC_GUI_SCALE)) {
            return Handler.NONE;
        }
        return binds.stream().anyMatch(CAMERA::contains) ? Handler.CAMERA : Handler.SWING;
    }

    /**
     * C.4's hotkey table with nothing open and nothing to cancel, the actor framed in the local turn. The targeting
     * display's phases keep MegaMek's keys (D1) and the physical attack keys stay MegaMek's.
     */
    private static boolean hudBind(Scenario scenario, KeyCommandBind bind, boolean logOpen) {
        GamePhase phase = scenario.phase();
        boolean firing = scenario.myTurn() && phase.isFiring();
        return switch (bind) {
            // Esc clears the selection, or opens the Menu; F10 always toggles it.
            case CANCEL, GAME_MENU, SHOW_NAMEPLATES, ROUND_REPORT, KEY_BINDS, BOT_COMMANDS, LOS_SETTING,
                 UNIT_OVERVIEW, FORCE_DISPLAY, UNIT_DISPLAY, UD_GENERAL, UD_PILOT, UD_ARMOR, UD_WEAPONS, UD_SYSTEMS,
                 UD_EXTRAS, FORCES_GRID, TOGGLE_CHAT, TOGGLE_CHAT_CMD, DONE -> true;
            case MOVE_MODE_WALK, MOVE_MODE_RUN, MOVE_MODE_JUMP, TURN_LEFT, TURN_RIGHT -> scenario.planning();
            case TWIST_LEFT, TWIST_RIGHT -> firing;
            case UNDO_LAST_STEP, CLEAR_ORDERS -> scenario.planning() || firing;
            case PLAYBACK_TOGGLE, PLAYBACK_PREV, PLAYBACK_NEXT -> phase.isReport();
            case CENTER_ON_SELECTED -> !phase.isReport() && scenario.myTurn();
            case REPORT_KEY_NEXT, REPORT_KEY_PREV, REPORT_KEY_SELECT_NEXT, REPORT_KEY_SELECT_PREVIOUS,
                 REPORT_FILTER_KEY_SELECT_NEXT, REPORT_KEY_FILTER -> logOpen;
            default -> false;
        };
    }

    /** Every default key of MegaMek's binds that a desktop key reaches, with the binds it invokes. */
    private static List<Key> keys() {
        Map<List<Integer>, Set<KeyCommandBind>> byKey = new LinkedHashMap<>();
        for (KeyCommandBind bind : KeyCommandBind.values()) {
            byKey.computeIfAbsent(List.of(bind.keyDefault, bind.modifiersDefault),
                  ignored -> EnumSet.noneOf(KeyCommandBind.class)).add(bind);
        }
        List<Key> keys = new ArrayList<>();
        byKey.forEach((key, binds) -> {
            int gdx = gdxKey(key.get(0));
            // Num Lock decides whether the keypad's navigation keys exist; GpuKeyboardTest covers both states.
            if (gdx != Input.Keys.UNKNOWN) {
                keys.add(new Key(gdx, key.get(0), key.get(1), binds));
            }
        });
        return keys;
    }

    private static int gdxKey(int awt) {
        for (int key = 1; key <= Input.Keys.MAX_KEYCODE; key++) {
            if (GpuBattleView.awtKey(key) == awt) {
                return key;
            }
        }
        return Input.Keys.UNKNOWN;
    }

    /** A modifier key reports its own mask while it is down, as GLFW input does (W0.2b). */
    private static int ownMask(int awt) {
        return switch (awt) {
            case KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK;
            case KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK;
            case KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK;
            case KeyEvent.VK_META -> InputEvent.META_DOWN_MASK;
            default -> 0;
        };
    }

    /** Degrees from {@code from} to {@code to}, between -180 and 180. */
    private static float turned(float from, float to) {
        return ((to - from) % 360 + 540) % 360 - 180;
    }

    private static boolean shown(Actor actor) {
        for (Actor current = actor; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return actor != null;
    }

    /** The test body; it runs on the GL thread. */
    private interface Body {
        void run(Routing routing) throws Exception;
    }

    /**
     * Captures the fixture's board with the Atlas and an opposing Archer, then opens a hidden 1920 x 1080 window and
     * runs {@code body} with a battle view over a recording source.
     */
    private static void run(Body body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FutureTask<GpuBoardSource.Frame> capture = new FutureTask<>(() -> {
                Player opponent = new Player(1, "Opposing force");
                opponent.setTeam(2);
                fixture.game.addPlayer(opponent.getId(), opponent);
                Entity foe = new MekFileParser(new File("testresources/megamek/common/units/Archer ARC-2R.mtf"))
                      .getEntity();
                foe.setId(FOE_ID);
                foe.setOwner(opponent);
                foe.setPosition(FOE_HEX);
                foe.setFacing(3);
                foe.setDeployed(true);
                fixture.game.addEntity(foe, false);
                fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                fixture.source.refresh();
                return fixture.source.takeFrame();
            });
            SwingUtilities.invokeAndWait(capture);
            GpuBoardSource.Frame board = capture.get();
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    Routing routing = null;
                    try {
                        routing = new Routing(board, fixture);
                        body.run(routing);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (routing != null) {
                            routing.view.dispose();
                        }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        }
        if (failure.get() != null) {
            throw new AssertionError("Native input routing failed", failure.get());
        }
    }

    /** The view under test, its recording source and the scripted frame and dialog; used on the GL thread only. */
    private static final class Routing {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuMovePlan moves = mock(GpuMovePlan.class);
        final GpuFireOrders fire = mock(GpuFireOrders.class);
        final GpuPhysicalOptions physical = mock(GpuPhysicalOptions.class);
        final AtomicReference<GpuBoardSource.Frame> frame = new AtomicReference<>();
        final AtomicReference<DialogRequest> dialog = new AtomicReference<>();
        final GpuBoardSource.Frame board;
        /** The fixture whose board the frames carry, for captures on the Swing thread. */
        final GpuBoardFixture fixture;
        final GpuBattleView view;
        final GpuHud hud;
        final InputProcessor processor;
        /** Stands in for Gdx.input while a key or button is pressed, reporting the held modifiers and the pointer. */
        final Input keyboard = mock(Input.class);
        /** The client's menu bar the frames carry; none unless a test captures it. */
        List<BoardScene.Command> menuBar = List.of();

        Routing(GpuBoardSource.Frame board, GpuBoardFixture fixture) throws Exception {
            this.board = board;
            this.fixture = fixture;
            when(source.scenarioAtmosphere()).thenAnswer(invocation -> fixture.source.scenarioAtmosphere());
            // One HUD unit per window pixel, as in the prototype's 1920 x 1080 captures.
            source.uiPreferences = GpuHudFixtures.preferences(.1f
                  / DisplayScale.read(.1f, new GpuDisplayScale().contentScale()));
            // The view reads the preferences through the accessor; tests change them through the field.
            when(source.uiPreferences()).thenAnswer(invocation -> source.uiPreferences);
            when(source.takeFrame()).thenAnswer(invocation -> frame.get());
            when(source.dialog()).thenAnswer(invocation -> dialog.get());
            when(source.moves()).thenReturn(moves);
            when(source.fire()).thenReturn(fire);
            when(source.physical()).thenReturn(physical);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.toasts()).thenReturn(mock(GpuToasts.class));
            when(source.los()).thenReturn(mock(GpuLosResult.class));
            when(source.chat()).thenReturn(mock(GpuChat.class));
            when(source.players()).thenReturn(mock(GpuPlayers.class));
            frame.set(frame(SCENARIOS.getFirst()));
            view = new GpuBattleView(source);
            view.create();
            hud = (GpuHud) field(view, "ui");
            // The forces list stays open, as the routes use its rows; the folded rail has its own test.
            hud.state.forcesPinned = true;
            processor = Gdx.input.getInputProcessor();
        }

        /** A frame of the real board whose status and panels script {@code scenario}. */
        GpuBoardSource.Frame frame(Scenario scenario) {
            GpuBattleStatus.Snapshot status = GpuHudFixtures.status(3, scenario.phase(), scenario.myTurn(),
                  scenario.myTurn() ? OWN_ID : Entity.NONE, 1,
                  GpuHudFixtures.unit(OWN_ID, OWN, scenario.myTurn(), true),
                  GpuHudFixtures.unit(FOE_ID, ENEMY, false, false));
            GpuPhysicalOptions.Snapshot adjacent = scenario.phase().isPhysical()
                  ? new GpuPhysicalOptions.Snapshot(true, OWN_ID, Entity.NONE, List.of(FOE_ID), List.of())
                  : GpuPhysicalOptions.Snapshot.EMPTY;
            GpuHudData panels = GpuHudFixtures.panels(GpuHudFixtures.move(scenario.planner(), List.of()),
                  GpuFireOrders.Snapshot.EMPTY, adjacent, GpuUnitRecord.Snapshot.EMPTY);
            return frame(status, panels);
        }

        /** A frame of the real board with {@code status} and {@code panels}. */
        GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuHudData panels) {
            return new GpuBoardSource.Frame(board.scene(), List.of(), null, menuBar, "",
                  board.centerRequest(), board.boardGeneration(), "", board.scenarioAtmosphere(),
                  GpuReportLog.Snapshot.EMPTY, status, panels);
        }

        /** Draws a frame of {@code scenario}, so that the HUD reads its phase and turn. */
        void show(Scenario scenario) {
            show(frame(scenario));
        }

        /** Draws {@code next} with nothing open, focused or inspected. */
        void show(GpuBoardSource.Frame next) {
            reset();
            frame.set(next);
            // The HUD draws once the board is presented, after the terrain is built.
            GpuBoardTestUi.present(view);
            view.render();
        }

        /** A left press and release on an actor's centre through the view's input, then a frame. */
        void press(Actor actor) throws Exception {
            touch(screen(actor), Input.Buttons.LEFT, 0, 0);
            view.render();
        }

        /** Presses the item or button with this text under {@code root} (an open menu or list). */
        void choose(Actor root, String text) throws Exception {
            Actor item = GpuHelpMenuPlayersSmokeTest.find(root, text);
            assertNotNull(item, "no \"" + text + "\"");
            press(item);
        }

        /** Nothing open, nothing focused, nothing inspected, no camera key held. */
        void reset() {
            hud.stage.setKeyboardFocus(null);
            GpuHudState state = hud.state;
            state.overview = false;
            state.chatOpen = false;
            state.dialog = GpuHudState.Dialog.NONE;
            state.recordOpen = false;
            state.forcesGrid = false;
            state.inspected = Entity.NONE;
            state.armedWeapon = -1;
            state.allNameplates = false;
            hud.boardPress();
        }

        /**
         * Presses and releases {@code key} with nothing open and returns its one handler; fails when none or more than
         * one acted.
         */
        Handler press(Key key) throws Exception {
            if (dialog.get() == null) {
                reset();
            }
            clearInvocations(source);
            long revision = view.boardCamera.revision();
            int pressed = key.modifiers() | ownMask(key.awt());
            boolean[] acted = new boolean[2];
            with(pressed, 0, 0, () -> {
                processor.keyDown(key.gdx());
                acted[0] = consumed().contains(key.gdx());
                acted[1] = !cameraKeys().isEmpty() || view.boardCamera.revision() != revision;
                processor.keyUp(key.gdx());
            });
            if (view.boardCamera.tactical()) {
                view.setTacticalView(false);
            }
            List<Invocation> forwarded = mockingDetails(source).getInvocations().stream()
                  .filter(call -> call.getMethod().getName().equals("key")).toList();
            String name = key.binds().toString();
            if (acted[0]) {
                assertFalse(acted[1], name + ": the HUD consumed it and the camera moved as well");
                assertTrue(forwarded.isEmpty(), name + ": the HUD consumed it and Swing got it as well");
                return Handler.HUD;
            }
            if (acted[1]) {
                assertTrue(forwarded.isEmpty(), name + ": the camera moved and Swing got it as well");
                return Handler.CAMERA;
            }
            if (forwarded.isEmpty()) {
                return Handler.NONE;
            }
            assertEquals(2, forwarded.size(), name + ": one press and one release reach Swing");
            verify(source).key(key.awt(), true, pressed);
            verify(source).key(key.awt(), false, pressed);
            return Handler.SWING;
        }

        /** A key press and release with {@code modifiers} held. */
        void key(int gdx, int modifiers) throws Exception {
            with(modifiers, 0, 0, () -> {
                processor.keyDown(gdx);
                processor.keyUp(gdx);
            });
        }

        void typed(char character) {
            processor.keyTyped(character);
        }

        /** Holds {@code gdx} down while {@code body} runs. */
        void hold(int gdx, Check body) throws Exception {
            with(0, 0, 0, () -> processor.keyDown(gdx));
            body.run();
            with(0, 0, 0, () -> processor.keyUp(gdx));
        }

        /** Draws {@code frames} frames of {@code seconds} each. */
        void advance(int frames, float seconds) {
            Graphics graphics = Gdx.graphics;
            Graphics timed = spy(graphics);
            doReturn(seconds).when(timed).getDeltaTime();
            Gdx.graphics = timed;
            try {
                for (int index = 0; index < frames; index++) {
                    view.render();
                }
            } finally {
                Gdx.graphics = graphics;
            }
        }

        /** A short click on {@code hex}: the button pressed with {@code pressed} and released with {@code released}. */
        void click(Coords hex, int button, int pressed, int released) throws Exception {
            touch(screen(hex), button, pressed, released);
        }

        void touch(Vector3 point, int button, int pressed, int released) throws Exception {
            int x = Math.round(point.x);
            int y = Math.round(point.y);
            with(pressed, x, y, () -> processor.touchDown(x, y, 0, button));
            with(released, x, y, () -> processor.touchUp(x, y, 0, button));
        }

        /** A drag of 60 x 40 pixels from {@code hex}, past the threshold. */
        void drag(Coords hex, int button, int modifiers) throws Exception {
            Vector3 point = screen(hex);
            int x = Math.round(point.x);
            int y = Math.round(point.y);
            with(modifiers, x, y, () -> {
                processor.touchDown(x, y, 0, button);
                processor.touchDragged(x + 30, y + 20, 0);
                processor.touchDragged(x + 60, y + 40, 0);
                processor.touchUp(x + 60, y + 40, 0, button);
            });
        }

        /** One wheel notch towards the user (zoom in) with the pointer at {@code point}. */
        void wheel(Vector3 point) throws Exception {
            wheel(point, -1);
        }

        /** Wheel notches, positive away from the user (down a list), with the pointer at {@code point}. */
        void wheel(Vector3 point, float notches) throws Exception {
            with(0, Math.round(point.x), Math.round(point.y), () -> processor.scrolled(0, notches));
        }

        /** Moves the mouse to {@code point} without a button, then draws a frame, which fires its enter and exit. */
        void move(Vector3 point) throws Exception {
            int x = Math.round(point.x);
            int y = Math.round(point.y);
            with(0, x, y, () -> processor.mouseMoved(x, y));
            view.render();
        }

        /** The window point (y down) of a hex centre, which no HUD panel covers. */
        Vector3 screen(Coords hex) {
            Vector3 point = view.screenPosition(hex);
            assertFalse(hud.hit(Math.round(point.x), Math.round(point.y)), hex + " lies under a HUD panel");
            return point;
        }

        /** The window point (y down) of an actor's centre. */
        Vector3 screen(Actor actor) {
            Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
            hud.stage.stageToScreenCoordinates(point);
            return new Vector3(point.x, point.y, 0);
        }

        Coords pick(int x, int y) throws Exception {
            Object input = field(view, "boardInput");
            var pick = input.getClass().getDeclaredMethod("pick", int.class, int.class);
            pick.setAccessible(true);
            return (Coords) pick.invoke(input, x, y);
        }

        <T extends Actor> T find(String name) {
            return hud.stage.getRoot().findActor(name);
        }

        /** Writes the window to {name}.png in the screenshot folder. */
        void capture(String name) {
            UiTestStage.capture(name, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()).dispose();
        }

        @SuppressWarnings("unchecked")
        private Set<Integer> consumed() throws Exception {
            return (Set<Integer>) field(hud, "consumedKeys");
        }

        @SuppressWarnings("unchecked")
        private Map<Integer, KeyCommandBind> cameraKeys() throws Exception {
            return (Map<Integer, KeyCommandBind>) field(view, "cameraKeys");
        }

        /** Runs {@code body} with {@code modifiers} held and the pointer at ({@code x}, {@code y}). */
        private void with(int modifiers, int x, int y, Check body) throws Exception {
            Input input = Gdx.input;
            when(keyboard.getInputProcessor()).thenReturn(processor);
            when(keyboard.getX()).thenReturn(x);
            when(keyboard.getY()).thenReturn(y);
            boolean control = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
            boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
            when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(control);
            when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn(shift);
            when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
            when(keyboard.isKeyPressed(Input.Keys.SYM)).thenReturn((modifiers & InputEvent.META_DOWN_MASK) != 0);
            Gdx.input = keyboard;
            try {
                body.run();
            } finally {
                Gdx.input = input;
            }
        }
    }

    /** A step of a test that may throw. */
    private interface Check {
        void run() throws Exception;
    }
}
