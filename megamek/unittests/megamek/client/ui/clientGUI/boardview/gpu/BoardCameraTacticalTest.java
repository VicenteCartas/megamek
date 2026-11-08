/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Tactical View's camera: it enters north up, rotates while looking straight down, and hands the replaced 3D
 * pose back unchanged.
 */
class BoardCameraTacticalTest {
    private static final float TOLERANCE = 0.0001f;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    /** A manual 3D pose: turned, tilted, panned and zoomed away from every preset. */
    private static BoardCamera orbiting() {
        BoardCamera view = new BoardCamera();
        view.resize(1100, 750);
        view.setIsometric(true);
        view.orbit(20, 7);
        view.center(new Vector3(300, -200, 12));
        view.zoom(.6f);
        return view;
    }

    private static void assertTopView(BoardCamera view) {
        assertTrue(view.camera.direction.epsilonEquals(0, 0, -1, TOLERANCE), "Straight down: " + view.camera.direction);
        assertTrue(view.camera.up.epsilonEquals(0, 1, 0, TOLERANCE), "North up: " + view.camera.up);
        assertEquals(0, view.azimuth());
        assertEquals(0, view.tilt());
    }

    @Test
    void entersTheNorthUpTopViewAllowsRotationAndRestoresTheReplacedPoseExactly() {
        BoardCamera view = orbiting();
        Vector3 focus = view.focus.cpy();
        float zoom = view.camera.zoom;
        float azimuth = view.azimuth();
        float tilt = view.tilt();

        view.setTactical(true, null);
        assertTrue(view.tactical());
        assertTopView(view);
        assertEquals(focus, view.focus, "Entering keeps the centre of the view");
        assertEquals(zoom, view.camera.zoom, "Entering keeps the scale");

        view.pan(40, -25);
        assertTopView(view);
        assertEquals(focus.x - 40 * zoom, view.focus.x, TOLERANCE, "A positive x pan moves west, like SCROLL_WEST");
        assertEquals(focus.y - 25 * zoom, view.focus.y, TOLERANCE, "A negative y pan moves south, like SCROLL_SOUTH");
        assertEquals(focus.z, view.focus.z, TOLERANCE, "Panning stays on the focus plane");
        view.zoom(2);
        assertEquals(2 * zoom, view.camera.zoom, TOLERANCE);

        // Mouse, minimap and keyboard turns rotate the top view; tilt and the isometric preset cannot incline it.
        view.orbit(35, 20);
        assertEquals(35, view.azimuth(), TOLERANCE);
        assertEquals(0, view.tilt(), TOLERANCE);
        assertTrue(view.camera.direction.epsilonEquals(0, 0, -1, TOLERANCE));
        assertFalse(view.camera.up.epsilonEquals(0, 1, 0, TOLERANCE), "The camera rotates, not just its stored azimuth");
        view.tilt(15);
        assertEquals(35, view.azimuth(), TOLERANCE);
        assertEquals(0, view.tilt(), TOLERANCE);
        view.rotateStep(1);
        assertTrue(view.isRotating(), "A keyboard turn starts in the Tactical View");
        view.advance(BoardCamera.ROTATION_SECONDS);
        assertEquals(35 + BoardCamera.ROTATION_STEP, view.azimuth(), TOLERANCE);
        assertEquals(0, view.tilt(), TOLERANCE);
        view.setIsometric(true);
        assertFalse(view.isIsometric());
        assertEquals(35 + BoardCamera.ROTATION_STEP, view.azimuth(), TOLERANCE,
              "The unavailable isometric preset preserves the tactical rotation");
        assertTrue(view.camera.direction.epsilonEquals(0, 0, -1, TOLERANCE));
        assertTrue(view.tactical(), "Only setTactical leaves the Tactical View");
        assertEquals(2 * zoom, view.camera.zoom, TOLERANCE);

        view.setTactical(false, null);
        assertFalse(view.tactical());
        assertEquals(focus, view.focus);
        assertEquals(zoom, view.camera.zoom);
        assertEquals(azimuth, view.azimuth());
        assertEquals(tilt, view.tilt());
        view.orbit(10, 5);
        assertEquals(azimuth + 10, view.azimuth(), TOLERANCE, "The 3D view orbits on from its own pose");
        assertEquals(tilt + 5, view.tilt(), TOLERANCE, "The 3D view can still tilt");
    }

    @Test
    void aTurnOrFramingInProgressReturnsWhereItWasHeading() {
        BoardCamera turning = orbiting();
        float azimuth = turning.azimuth();
        turning.rotateStep(1);
        turning.advance(.1f);
        turning.setTactical(true, null);
        turning.setTactical(false, null);
        assertEquals(azimuth + BoardCamera.ROTATION_STEP, turning.azimuth(), TOLERANCE, "Turns still land on a step");

        var unit = new BoardScene.Unit(1, -1, "Unit 1", new BoardScene.Waypoint(new Coords(40, 30), 0, 0),
              null, false, null, 2, true);
        BoardCamera framed = orbiting();
        framed.frameSelection(unit, 700);
        framed.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
        BoardCamera framing = orbiting();
        framing.frameSelection(unit, 700);
        framing.advance(.1f);
        assertTrue(framing.isFraming());
        framing.setTactical(true, null);
        framing.setTactical(false, null);
        assertTrue(framed.focus.epsilonEquals(framing.focus, .01f), framing.focus + " vs " + framed.focus);
        assertEquals(framed.camera.zoom, framing.camera.zoom, TOLERANCE);
        assertEquals(framed.tilt(), framing.tilt(), TOLERANCE);
    }

    @Test
    void aBoardChangeFitsTheNewBoardOnLeaving() {
        BoardCamera view = orbiting();
        view.setTactical(true, board(4, 4));
        BoardScene next = board(30, 20);
        view.boardChanged();
        view.fit(next);
        view.pan(50, 20);
        view.setTactical(false, next);

        BoardCamera fitted = orbiting();
        fitted.fit(next);
        assertTrue(fitted.focus.epsilonEquals(view.focus, .01f), view.focus + " vs " + fitted.focus);
        assertEquals(fitted.camera.zoom, view.camera.zoom, TOLERANCE);
        assertEquals(fitted.azimuth(), view.azimuth());
        assertEquals(fitted.tilt(), view.tilt());
    }

    @Test
    void theFitToTheWindowTravelsWithThePoseInBothDirections() {
        BoardScene scene = UnitPlaybackFixture.scene();
        BoardCamera fitted = new BoardCamera();
        fitted.resize(1100, 750);
        fitted.setIsometric(true);
        fitted.fit(scene);
        Vector3 focus = fitted.focus.cpy();
        float zoom = fitted.camera.zoom;

        fitted.setTactical(true, scene);
        assertTopView(fitted);
        BoardCamera top = new BoardCamera();
        top.resize(1100, 750);
        top.setIsometric(false);
        top.fit(scene);
        assertEquals(top.camera.zoom, fitted.camera.zoom, TOLERANCE, "A fitted view stays fitted from above");
        fitted.pan(60, 30);
        fitted.setTactical(false, scene);
        assertTrue(fitted.isIsometric());
        assertEquals(focus, fitted.focus);
        assertEquals(zoom, fitted.camera.zoom);
        fitted.resize(900, 600, scene, 1);
        BoardCamera refit = new BoardCamera();
        refit.resize(900, 600);
        refit.setIsometric(true);
        refit.fit(scene);
        assertEquals(refit.camera.zoom, fitted.camera.zoom, TOLERANCE, "The restored fit follows the window again");

        BoardCamera manual = orbiting();
        Vector3 manualFocus = manual.focus.cpy();
        manual.setTactical(true, scene);
        manual.fit(scene);
        manual.setTactical(false, scene);
        manual.resize(900, 600, scene, 1);
        assertEquals(manualFocus, manual.focus, "A manual pose must not start following the window");
    }

    @Test
    void framingKeepsTheTopViewAndResetStaysInIt() {
        BoardCamera view = orbiting();
        view.setTactical(true, null);
        var unit = new BoardScene.Unit(1, -1, "Unit 1", new BoardScene.Waypoint(new Coords(18, 14), 3, 0),
              null, false, null, 2, true);
        view.frameSelection(unit, 700);
        view.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
        assertTopView(view);
        BoardCameraFramingTest.assertVisible(view, 700, unit);

        // A long east-west shot out of view: the 3D view would turn to lay it across the usable area.
        var attacker = new BoardScene.Unit(2, -1, "Unit 2", new BoardScene.Waypoint(new Coords(2, 30), 0, 0),
              null, false, null, 2, true);
        var target = new BoardScene.Unit(3, -1, "Unit 3", new BoardScene.Waypoint(new Coords(40, 30), 0, 0),
              null, false, null, 2, true);
        view.frameAttacks(List.of(new UnitAttack(UnitPlaybackFixture.attack(attacker, target, ResolvedAttack.Kind.SHOT,
              true))), 700);
        view.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
        assertTopView(view);
        BoardCameraFramingTest.assertVisible(view, 700, attacker);
        BoardCameraFramingTest.assertVisible(view, 700, target);

        view.reset(UnitPlaybackFixture.scene(unit));
        assertTopView(view);
        assertTrue(view.tactical(), "Reset fits the board without leaving the Tactical View");
    }

    /** A flat board of the given size in hexes. */
    private static BoardScene board(int width, int height) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, null, null, List.of(),
                      List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "MOVEMENT", List.of());
    }
}
