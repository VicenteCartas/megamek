/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraPanTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void panningCannotLoseTheBoardAtEdgesOrRotatedCorners() {
        for (int[] size : new int[][] { { 1, 1 }, { 1, 3 }, { 2, 1 }, { 16, 17 } }) {
            BoardScene scene = scene(size[0], size[1]);
            for (boolean perspective : new boolean[] { false, true }) {
                for (float[] angle : new float[][] { { 0, 0 }, { 45, 55 }, { 135, 80 } }) {
                    BoardCamera view = camera(scene, perspective);
                    view.orbit(angle[0], angle[1]);
                    for (float zoom : new float[] { .05f, 1, 20 }) {
                        view.zoom(zoom / view.camera.zoom);
                        for (int x = -1; x <= 1; x++) {
                            for (int y = -1; y <= 1; y++) {
                                view.center(BoardGeometry.center(new Coords(size[0] / 2, size[1] / 2), 0));
                                view.pan(x * 100000, y * 100000);
                                assertBoardOnCenterLines(view, scene);
                                for (int step = 0; step < 10; step++) { view.pan(x * 20, y * 20); }
                                assertBoardOnCenterLines(view, scene);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void blockedPanningStopsWithoutRedirectingAndReversesImmediately() {
        BoardScene scene = scene(16, 17);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.zoom(1 / view.camera.zoom);
            view.pan(100000, 0);
            Vector3 edge = view.focus.cpy();
            for (int step = 0; step < 20; step++) { view.pan(100, 0); }
            assertTrue(edge.epsilonEquals(view.focus, .01f), "Held input must not accumulate beyond the boundary");

            view.pan(100, 20);
            assertEquals(edge.x, view.focus.x, .01f, "The blocked part of a diagonal drag must not move");
            assertEquals(edge.y + 20, view.focus.y, .01f, "Its free part still drags along the edge");
            view.pan(0, 20);
            assertEquals(edge.x, view.focus.x, .01f);
            assertEquals(edge.y + 40, view.focus.y, .01f, "Dragging along the edge still works");
            assertEquals(edge.z, view.focus.z);
            view.pan(-10, 0);
            assertEquals(edge.x + 10, view.focus.x, .01f, "Moving back responds on the first input");
            assertBoardAtCenter(view, scene);
        }
    }

    @Test
    void diagonalDragAgainstOneLimitStillMovesAlongTheOtherScreenAxis() {
        BoardScene scene = scene(16, 17);
        for (boolean perspective : new boolean[] { false, true }) {
            // Top view, tilted, turned and steep views; yaw 0 and 180 put the limit parallel to the vertical axis.
            for (float[] angle : new float[][] { { 0, 0 }, { 0, 70 }, { 180, 45 }, { 30, 60 }, { 210, 80 } }) {
                for (float zoom : new float[] { .5f, 1, 3 }) {
                    BoardCamera view = camera(scene, perspective);
                    view.orbit(angle[0], angle[1]);
                    view.zoom(zoom / view.camera.zoom);
                    view.center(BoardGeometry.center(new Coords(8, 8), 0));
                    Vector3 screen = project(view, view.focus);
                    view.pan(600 - screen.x, screen.y - 400); // Start with the board at the full viewport center.
                    view.pan(-100000, 0); // The view reaches its limit to the right, away from the board's corners.
                    Vector3 edge = view.focus.cpy();
                    Vector3 right = new Vector3(view.camera.direction).crs(view.camera.up).nor();
                    Vector3 forward = new Vector3(-right.y, right.x, 0);
                    int moving = 0;
                    for (float dy : new float[] { 10, -10 }) {
                        view.center(edge);
                        view.pan(0, dy);
                        float vertical = view.focus.cpy().sub(edge).dot(forward);
                        view.center(edge);
                        view.pan(-100, dy);
                        Vector3 travel = view.focus.cpy().sub(edge);
                        String pose = (perspective ? "perspective " : "orthographic ") + angle[0] + "/" + angle[1]
                              + ", zoom " + zoom + ", dy " + dy;
                        assertTrue(travel.dot(right) < .01f, () -> "The blocked part must stay at its limit: " + pose);
                        assertBoardOnCenterLines(view, scene);
                        assertEquals(vertical, travel.dot(forward), .01f + .01f * Math.abs(vertical),
                              () -> "The free part must move as far as a vertical drag: " + pose);
                        if (Math.abs(vertical) > 5 * zoom) { moving++; }
                    }
                    assertTrue(moving >= (angle[0] % 180 == 0 ? 2 : 1), () -> "Along a limit parallel to the vertical axis"
                          + " both directions move, elsewhere the inward one: " + (perspective ? "perspective " : "")
                          + angle[0] + "/" + angle[1] + ", zoom " + zoom);
                }
            }
        }
    }

    /**
     * The board editor's camera (retest 2026-10-10): fitted between the Assets and Hex/Layers columns above the Hex
     * Section dock, rebased to the full width as every frame does, and pushed against a pan limit by mouse drags. A
     * drag into the limit and along it moves the board exactly as far along it, in both directions, and not into it;
     * a drag straight into the limit stops; a drag away from it moves on both axes. At all four limits, in the top
     * view and turned views, with the panels open and closed, over flat and raised ground. Turned views sweep the yaw,
     * because rounding at a side limit with raised ground depends on the pose.
     */
    @Test
    void atEachPanLimitTheOtherScreenAxisStillFollowsTheMouse() {
        List<float[]> angles = new ArrayList<>(List.of(new float[] { 0, 0 }, new float[] { 225, 0 },
              new float[] { 250, 70 }));
        for (float yaw = 0; yaw < 360; yaw += 15) { angles.add(new float[] { yaw, 54.73561f }); }
        for (int raised : new int[] { 0, 3 }) {
            BoardScene scene = scene(16, 17, 0, raised, List.of());
            for (boolean perspective : new boolean[] { false, true }) {
                for (float[] angle : angles) {
                    for (boolean panels : new boolean[] { true, false }) {
                        for (int[] limit : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                            BoardCamera view = editorCamera(scene, perspective, angle, panels);
                            String pose = (perspective ? "perspective " : "") + angle[0] + "/" + angle[1]
                                  + (panels ? ", panels" : "") + ", raised " + raised + ", push " + limit[0] + "," + limit[1];
                            // From the middle of the board at the viewport center, the mouse pushes until the limit holds.
                            Vector3 middle = project(view, BoardGeometry.center(new Coords(8, 8), 0));
                            view.pan(800 - middle.x, middle.y - 500);
                            drag(view, 5 * limit[0], 5 * limit[1], 400);
                            Vector3 edge = view.focus.cpy();
                            drag(view, 5 * limit[0], 5 * limit[1], 1);
                            assertTrue(edge.epsilonEquals(view.focus, .01f), () -> "A push into the limit stops: " + pose);
                            for (int along : new int[] { -1, 1 }) {
                                int dx = 3 * (limit[0] + along * Math.abs(limit[1]));
                                int dy = 3 * (limit[1] + along * Math.abs(limit[0]));
                                assertDragMoves(view, dx, dy, 3 * along * Math.abs(limit[1]),
                                      3 * along * Math.abs(limit[0]), () -> "Into the limit and along it: " + pose);
                            }
                            assertDragMoves(view, -3 * limit[0] - 3 * limit[1], -3 * limit[1] - 3 * limit[0],
                                  -3 * limit[0] - 3 * limit[1], -3 * limit[1] - 3 * limit[0],
                                  () -> "Away from the limit and along it: " + pose);
                            if (raised == 0) { assertBoardOnCenterLines(view, scene); }
                        }
                    }
                }
            }
        }
    }

    @Test
    void rotatedPanLimitsNeverRedirectTheDrag() {
        for (boolean perspective : new boolean[] { false, true }) {
            for (int height : new int[] { 0, 100 }) {
                BoardScene scene = scene(16, 17, 0, height, List.of());
                for (float bearing : new float[] { 25, 45, 135, 250 }) {
                    BoardCamera view = camera(scene, perspective);
                    view.orbit(bearing, 70);
                    view.zoom((perspective ? 5 : 1) / view.camera.zoom);
                    // A diagonal drag keeps its free part at a limit (see diagonalDragAgainstOneLimit...).
                    for (float[] drag : new float[][] { { 0, 20 }, { 0, -20 }, { 20, 0 }, { -20, 0 } }) {
                        view.center(BoardGeometry.center(new Coords(8, 8), 0));
                        Vector3 screen = project(view, view.focus);
                        view.pan(600 - screen.x, screen.y - 400); // Start with the board at the full viewport center.
                        Vector3 start = view.focus.cpy();
                        view.pan(drag[0], drag[1]);
                        Vector3 direction = view.focus.cpy().sub(start).nor();
                        assertTrue(direction.len2() > .9f, "The first step must move inside the board");
                        for (int step = 0; step < 600; step++) {
                            view.pan(drag[0], drag[1]);
                            Vector3 travel = view.focus.cpy().sub(start);
                            // Beside the view's centre a perspective drag up or down also slants sideways, and a side
                            // limit stops only that slant.
                            if (!perspective || drag[1] == 0) {
                                assertEquals(0, travel.crs(direction).len(), .05f,
                                      "Clipping must shorten the drag, never change its direction");
                            }
                        }
                        Vector3 edge = view.focus.cpy();
                        view.pan(drag[0], drag[1]);
                        assertTrue(edge.epsilonEquals(view.focus, .01f), "Continued dragging must stop at the edge");
                        view.pan(-drag[0], -drag[1]);
                        assertTrue(view.focus.cpy().sub(edge).dot(direction) < -1,
                              "Reversing a blocked drag must respond immediately");
                        if (height == 0) { assertBoardOnCenterLines(view, scene); }
                    }
                }
            }
        }
    }

    @Test
    void pointerZoomCannotPullTheBoardOffTheViewportCenterLines() {
        BoardScene scene = scene(5, 4);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.orbit(35, 55);
            for (float x : new float[] { 100, 800 }) {
                for (float y : new float[] { 0, 800 }) {
                    view.fit(scene);
                    view.zoom(20 / view.camera.zoom);
                    for (int step = 0; step < 12; step++) {
                        view.zoomAt(.5f, x, y);
                        assertBoardOnCenterLines(view, scene);
                    }
                    for (int step = 0; step < 12; step++) {
                        view.zoomAt(2, x, y);
                        assertBoardOnCenterLines(view, scene);
                    }
                }
            }
        }
    }

    @Test
    void ordinaryDraggingAndPointerZoomStillTrackThePointerInsideTheBoard() {
        BoardScene scene = scene(32, 32);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(scene, perspective);
            view.orbit(35, 55);
            view.zoom(1 / view.camera.zoom);
            view.center(BoardGeometry.center(new Coords(16, 16), 0));
            Vector3 anchor = view.focus.cpy();
            Vector3 before = project(view, anchor);
            view.pan(30, -20);
            Vector3 panned = project(view, anchor);
            assertEquals(before.x + 30, panned.x, .05f);
            assertEquals(before.y + 20, panned.y, .05f);
            view.zoomAt(.7f, panned.x, panned.y);
            Vector3 zoomed = project(view, anchor);
            assertEquals(panned.x, zoomed.x, .05f);
            assertEquals(panned.y, zoomed.y, .05f);
        }
    }

    @Test
    void tallTerrainCanBePannedToTheViewportCenterAtDifferentBearings() {
        for (boolean perspective : new boolean[] { false, true }) {
            for (float bearing : new float[] { 0, 45, 135, 270 }) {
                BoardScene scene = scene(16, 17, 0, 100, List.of());
                BoardCamera view = camera(scene, perspective);
                view.orbit(bearing, 70);
                view.setFieldOfView(20);
                view.zoom((perspective ? 20 : 1) / view.camera.zoom);
                Coords pillar = new Coords(8, 8);
                view.center(BoardGeometry.center(pillar, 0));
                Vector3 peak = BoardGeometry.center(pillar, 100);
                for (int step = 0; step < 100; step++) {
                    Vector3 screen = project(view, peak);
                    view.pan((600 - screen.x) * .25f, (screen.y - 400) * .25f);
                }
                Vector3 screen = project(view, peak);
                assertEquals(600, screen.x, .1f, "A rotated pillar must remain horizontally reachable");
                assertEquals(400, screen.y, .1f, "Panning up must reach the peak even when the base leaves the center");
                assertTrue(screen.z > 0 && screen.z < 1, "The peak must be in front of the camera");
            }
        }
    }

    @Test
    void liveTerrainEditsRefreshTheVerticalLimitWithoutRefitting() {
        BoardScene flat = scene(16, 17);
        BoardCamera view = camera(flat, false);
        view.orbit(0, 70);
        view.zoom(1 / view.camera.zoom);
        view.pan(0, 100000);
        Vector3 flatLimit = view.focus.cpy();
        view.terrainChanged(scene(16, 17, 0, 100, List.of()));
        assertEquals(flatLimit, view.focus, "An edit updates the boundary without moving the camera");
        view.pan(0, 100000);
        assertTrue(view.focus.y > flatLimit.y + 100 * BoardGeometry.level(), "A new pillar opens room above the base");
        Vector3 raisedLimit = view.focus.cpy();
        view.pan(0, 100);
        assertTrue(raisedLimit.epsilonEquals(view.focus, .01f), "The raised limit must still stop further panning");
        view.pan(0, -10);
        assertTrue(view.focus.y < raisedLimit.y, "Reversing at the raised limit moves immediately");
        view.terrainChanged(flat);
        view.pan(0, 0);
        assertTrue(flatLimit.epsilonEquals(view.focus, .01f), "Lowering terrain restores the original limit");
    }

    @Test
    void structuresUseTheSameHeightAllowanceAsRaisedGround() {
        var tower = new BoardScene.Feature("building", 0, 0, 0, 1, 100, 0, BoardScene.FeatureKind.BUILDING);
        for (int base : new int[] { -1000, 0, 1000 }) {
            BoardCamera terrain = camera(scene(16, 17, base, 100, List.of()), false);
            BoardCamera structure = camera(scene(16, 17, base, 0, List.of(tower)), false);
            for (var view : List.of(terrain, structure)) {
                view.orbit(45, 70);
                view.center(BoardGeometry.center(new Coords(8, 8), base));
                view.zoom(1 / view.camera.zoom);
                view.pan(-100000, 100000);
            }
            assertTrue(terrain.focus.epsilonEquals(structure.focus, .05f), "Framing and panning share feature heights");
        }
    }

    @Test
    void topDownAndHorizontalLimitsDoNotGrowWithTerrainHeight() {
        BoardCamera flat = camera(scene(16, 17), false);
        BoardCamera raised = camera(scene(16, 17, 0, 100, List.of()), false);
        for (float tilt : new float[] { 0, 70 }) {
            for (var view : List.of(flat, raised)) {
                view.setIsometric(false);
                view.tilt(tilt);
                view.center(BoardGeometry.center(new Coords(8, 8), 0));
                view.zoom(1 / view.camera.zoom);
                view.pan(100000, 0);
            }
            assertTrue(flat.focus.epsilonEquals(raised.focus, .01f), "Height must not loosen sideways panning");
            flat.pan(0, -100000);
            raised.pan(0, -100000);
            assertTrue(flat.focus.epsilonEquals(raised.focus, .01f), "The lower boundary stays where it was");
        }
    }

    @Test
    void tacticalViewAndReplacementBoardsUseTheirCurrentDimensionsAndScale() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            BoardCamera view = camera(scene(32, 32), false);
            view.pan(-100000, -100000);
            BoardScene next = scene(2, 1);
            view.boardChanged();
            view.setTactical(true, next);
            view.fit(next);
            view.resize(600, 1000, next, 2);
            view.viewableArea(60, 360);
            BoardGeometry.tune(new BoardGeometry.Tuning(original.hexScale() * 2, original.unitScale(),
                  original.unitHeightScale(), original.levelHeight(), original.gridShade()));
            view.pan(-100000, -100000);
            assertBoardAtCenter(view, next);
            view.pan(100000, 100000);
            assertBoardAtCenter(view, next);
        } finally {
            BoardGeometry.tune(original);
        }
    }

    @Test
    void freeFlightKeepsItsIndependentMovementAndRestoresTheOrbitPivot() {
        BoardScene scene = scene(2, 2);
        BoardCamera view = camera(scene, false);
        view.pan(100000, 0);
        Vector3 pivot = view.focus.cpy();
        view.setFirstPerson(true);
        Vector3 eye = view.camera.position.cpy();
        view.pan(100000, 0);
        assertEquals(100000, eye.dst(view.camera.position), .1f);
        assertEquals(pivot, view.focus);
        view.setFirstPerson(false);
        assertEquals(pivot, view.focus);
        assertBoardAtCenter(view, scene);
    }

    private static BoardCamera camera(BoardScene scene, boolean perspective) {
        BoardCamera view = new BoardCamera();
        view.resize(1200, 800);
        view.viewableArea(100, 700);
        view.setPerspective(perspective);
        view.fit(scene);
        return view;
    }

    /** The board editor's camera: Fit board between its panels, then rebased to the full width as every frame does. */
    private static BoardCamera editorCamera(BoardScene scene, boolean perspective, float[] angle, boolean panels) {
        BoardCamera view = new BoardCamera();
        view.resize(1600, 1000);
        view.setPerspective(perspective);
        view.orbit(angle[0], angle[1]);
        if (panels) {
            // GpuMapHud at 1600x1000: Assets and Hex/Layers columns with their gaps, the Hex Section dock, the toolbar.
            view.fit(scene, 280, 1020, 260, 50);
        } else {
            view.fit(scene);
        }
        view.viewableArea(0, 1600); // GpuBattleView.updateCameraFocus
        return view;
    }

    /** Mouse drag steps as BoardInput.touchDragged passes them: screen pixels, y down. */
    private static void drag(BoardCamera view, int dx, int dy, int steps) {
        for (int step = 0; step < steps; step++) { view.pan(dx, dy); }
    }

    /**
     * Twenty drag steps of (dx, dy) each move the ground under the pivot by (movedX, movedY) pixels, y down. Each step is
     * measured on its own: in perspective only the ground under the pivot follows the mouse exactly.
     */
    private static void assertDragMoves(BoardCamera view, int dx, int dy, int movedX, int movedY, Supplier<String> pose) {
        for (int step = 0; step < 20; step++) {
            Vector3 anchor = view.focus.cpy();
            Vector3 before = project(view, anchor);
            view.pan(dx, dy);
            Vector3 after = project(view, anchor);
            assertEquals(movedX, after.x - before.x, .1f, pose);
            assertEquals(-movedY, after.y - before.y, .1f, pose);
        }
    }

    /** The board stays visible along the full viewport's center row and center column, at any angle. */
    private static void assertBoardOnCenterLines(BoardCamera view, BoardScene scene) {
        Supplier<String> pose = () -> "angle=" + view.azimuth() + "/" + view.tilt() + ", zoom=" + view.camera.zoom
              + (view.perspective() ? ", perspective" : "");
        assertTrue(meetsBoard(view, scene, true), () -> "The board must stay on the viewport center's row: " + pose.get());
        assertTrue(meetsBoard(view, scene, false), () -> "The board must stay on the viewport center's column: " + pose.get());
    }

    /**
     * Whether the board meets the viewport center's row (or column) inside the viewport, searched from the center
     * outward; a pixel either side counts, for a board that touches the line only with a corner. The flat board's
     * surface first, then its full geometry, which also finds a board thinner than a pixel at a steep angle by its sides.
     */
    private static boolean meetsBoard(BoardCamera view, BoardScene scene, boolean row) {
        return meetsBoard(view, row, ray -> {
            if (ray.direction.z >= 0) { return false; }
            Vector3 ground = ray.getEndPoint(new Vector3(), -ray.origin.z / ray.direction.z);
            return BoardGeometry.tile(scene, ground.x, ground.y) != null;
        }) || meetsBoard(view, row, ray -> BoardGeometry.hit(scene, ray) != null);
    }

    private static boolean meetsBoard(BoardCamera view, boolean row, Predicate<Ray> board) {
        float width = view.camera.viewportWidth, height = view.camera.viewportHeight;
        for (float offset = 0; offset <= (row ? width : height) / 2; offset += .5f) {
            for (float sign : new float[] { -1, 1 }) {
                for (float across = -1; across <= 1; across++) {
                    float x = row ? width / 2 + sign * offset : width / 2 + across;
                    float y = row ? height / 2 + across : height / 2 + sign * offset;
                    // The view ray through that pixel, bottom-left origin.
                    Vector3 near = new Vector3(2 * x / width - 1, 2 * y / height - 1, -1).prj(view.camera.invProjectionView);
                    Vector3 far = new Vector3(2 * x / width - 1, 2 * y / height - 1, 1).prj(view.camera.invProjectionView);
                    if (board.test(new Ray(near, far.sub(near).nor()))) { return true; }
                }
            }
        }
        return false;
    }

    private static void assertBoardAtCenter(BoardCamera view, BoardScene scene) {
        Ray ray = new Ray(view.camera.position, view.camera.direction);
        Vector3 center = ray.origin.cpy().mulAdd(ray.direction,
              (view.focus.z - ray.origin.z) / ray.direction.z);
        Vector3 screen = project(view, center);
        assertEquals(view.camera.viewportWidth / 2, screen.x, .05f);
        assertEquals(view.camera.viewportHeight / 2, screen.y, .05f);
        assertNotNull(BoardGeometry.hit(scene, ray),
              () -> "The full viewport center must still point at terrain: " + center
                    + ", angle=" + view.azimuth() + "/" + view.tilt() + ", zoom=" + view.camera.zoom);
    }

    private static Vector3 project(BoardCamera view, Vector3 point) {
        return view.camera.project(point.cpy(), 0, 0, view.camera.viewportWidth, view.camera.viewportHeight);
    }

    private static BoardScene scene(int width, int height) {
        return scene(width, height, 0, 0, List.of());
    }

    private static BoardScene scene(int width, int height, int base, int raised, List<BoardScene.Feature> features) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean pillar = x == width / 2 && y == height / 2;
                tiles.add(new BoardScene.Tile(new Coords(x, y), base + (pillar ? raised : 0), -1, false, 0,
                      BoardScene.Surface.GRASS, null, pillar ? features : List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void panFollowsThePointerAndZoomRetainsItsAnchorWithoutRaisingThePivot() {
        BoardCamera view = new BoardCamera();
        view.resize(1100, 750);
        for (float tilt : new float[] { 0, 35, BoardCamera.MAX_TILT }) {
            view.setIsometric(false);
            view.orbit(135, tilt);
            view.center(BoardGeometry.center(new Coords(2, 2), 0));
            Vector3 anchor = BoardGeometry.center(new Coords(3, 3), 0);
            Vector3 before = view.camera.project(new Vector3(anchor), 0, 0, 1100, 750);
            view.pan(75, -25);
            Vector3 panned = view.camera.project(new Vector3(anchor), 0, 0, 1100, 750);
            assertEquals(before.x + 75, panned.x, 0.1f);
            assertEquals(before.y + 25, panned.y, 0.1f);
            assertEquals(0, view.focus.z, 0.001f);
            view.zoomAt(0.6f, panned.x, panned.y);
            Vector3 zoomed = view.camera.project(new Vector3(anchor), 0, 0, 1100, 750);
            assertEquals(panned.x, zoomed.x, 0.1f);
            assertEquals(panned.y, zoomed.y, 0.1f);
            assertEquals(0, view.focus.z, 0.001f);
        }
    }
}
