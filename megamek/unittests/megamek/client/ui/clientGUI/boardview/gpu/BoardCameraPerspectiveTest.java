/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraPerspectiveTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void switchingProjectionOrFieldOfViewKeepsThePivotPlaneStillAndIsClamped() {
        BoardCamera camera = camera();
        assertFalse(camera.perspective());
        // Straight down, a point beside the pivot lies in the pivot's plane; one raised above it does not.
        Vector3 beside = camera.focus.cpy().add(100, 0, 0), raised = camera.focus.cpy().add(100, 0, 100);
        float orthographic = project(camera, beside).x;
        camera.setPerspective(true);
        assertEquals(orthographic, project(camera, beside).x, .01f, "Switching projection must not zoom the view");
        float narrow = project(camera, raised).x;
        Vector3 position = camera.camera.position.cpy();
        camera.setFieldOfView(90);
        assertEquals(orthographic, project(camera, beside).x, .01f, "The field of view must not zoom the view");
        assertTrue(camera.camera.position.dst(camera.focus) < position.dst(camera.focus),
              "A wider field of view moves the camera closer instead");
        assertTrue(project(camera, raised).x > narrow, "A wider field of view shows more perspective");
        camera.setFieldOfView(0);
        assertEquals(BoardCamera.MIN_FIELD_OF_VIEW, camera.fieldOfView());
        camera.setFieldOfView(180);
        assertEquals(BoardCamera.MAX_FIELD_OF_VIEW, camera.fieldOfView());
        camera.setFieldOfView(Float.NaN);
        assertEquals(BoardCamera.MAX_FIELD_OF_VIEW, camera.fieldOfView());
    }

    @Test
    void explicitFitUsesPanelClearanceInBothProjections() {
        BoardScene scene = scene(6, 6, 2);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera camera = camera();
            camera.setPerspective(perspective);
            camera.setIsometric(true);
            camera.fit(scene, 250, 630, 240, 80);
            for (var tile : scene.tiles()) {
                for (int corner = 0; corner < 6; corner++) {
                    Vector3 screen = project(camera, BoardGeometry.corner(tile.coords(), tile.elevation(), corner));
                    assertTrue(screen.x >= 250 && screen.x <= 880, "Explicit fit reserves both side panels");
                    assertTrue(screen.y >= 240 && screen.y <= 720, "Explicit fit reserves bottom controls and toolbar");
                }
            }
        }
    }

    @Test
    void perspectiveUsesWorldDepthAndBothProjectionsPickTheSameBoardGeometry() {
        BoardCamera camera = camera();
        BoardScene scene = scene(0);
        camera.setPerspective(true);
        Vector3 near = camera.focus.cpy().add(0, 0, 100);
        Vector3 far = camera.focus.cpy().add(0, 0, -100);
        assertTrue(BoardCamera.worldUnitsPerPixel(camera.camera, near)
              < BoardCamera.worldUnitsPerPixel(camera.camera, far));
        for (boolean perspective : new boolean[] { false, true }) {
            camera.setPerspective(perspective);
            camera.setIsometric(true);
            camera.fit(scene);
            for (var tile : scene.tiles()) {
                Vector3 point = BoardGeometry.center(tile.coords(), tile.elevation());
                Vector3 screen = project(camera, point);
                Vector3 origin = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, -1)
                      .prj(camera.camera.invProjectionView);
                Vector3 direction = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, 1)
                      .prj(camera.camera.invProjectionView).sub(origin).nor();
                assertEquals(tile.coords(), BoardGeometry.pick(scene, new Ray(origin, direction)));
            }
        }
    }

    @Test
    void perspectiveFitKeepsRaisedTerrainInsideTheAvailableBoardArea() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            for (float tilt : new float[] { 0, 55, 80 }) {
                BoardCamera camera = camera();
                camera.setPerspective(true);
                camera.setFieldOfView(fieldOfView);
                camera.orbit(30, tilt);
                camera.viewableArea(100, 600);
                BoardScene scene = scene(2);
                camera.fit(scene);
                for (var tile : scene.tiles()) {
                    for (float elevation : new float[] { BoardGeometry.floor(scene) / BoardGeometry.LEVEL, tile.elevation() }) {
                        for (int corner = 0; corner < 6; corner++) {
                            Vector3 screen = project(camera, BoardGeometry.corner(tile.coords(), elevation, corner));
                            assertTrue(screen.x > 100 && screen.x < 700 && screen.y > 0 && screen.y < 800,
                                  () -> "Corner outside perspective fit: " + screen);
                        }
                    }
                    assertTrue(camera.visibleArea(scene).contains(tile.coords().getX(), tile.coords().getY()));
                }
            }
        }
    }

    @Test
    void initialFitIncludesFloatingAndSurfaceAttachedObjectsInBothProjections() {
        for (boolean perspective : new boolean[] { false, true }) {
            for (var placement : List.of(BoardDecoration.Placement.absolute(80),
                  BoardDecoration.Placement.surface("bridge", "deck", 60))) {
                BoardCamera camera = camera();
                camera.setPerspective(perspective);
                camera.setIsometric(true);
                camera.viewableArea(100, 600);
                var object = new BoardDecoration("floating", "prop", "scenery/car", null, 0, 0, 0,
                      false, 2, placement, 0);
                var features = List.of(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 4),
                      new BoardScene.Feature(object.asset(), 0, 0, 0, 2, 1, 0,
                            BoardScene.FeatureKind.PROP, 0, false, object));
                var tile = BoardScenes.newTile(new Coords(0, 0), 5, -1, false, 0,
                      BoardScene.Surface.GRASS, features, List.of());
                var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
                camera.fit(scene);
                float top = placement.mode().equals("absolute") ? 82 : 5 + 4 + 60 + 2;
                for (int corner = 0; corner < 6; corner++) {
                    Vector3 screen = project(camera, BoardGeometry.corner(tile.coords(), top, corner));
                    assertTrue(screen.x > 100 && screen.x < 700 && screen.y > 0 && screen.y < 800,
                          () -> "Authored height outside fitted viewport: " + screen);
                }
            }
        }
    }

    @Test
    void fittingUsesRelativeHeightAtHighAndLowAbsoluteElevations() {
        for (boolean perspective : new boolean[] { false, true }) {
            for (float tilt : new float[] { 0, 55, 80 }) {
                BoardCamera reference = camera();
                reference.setPerspective(perspective);
                reference.orbit(30, tilt);
                reference.viewableArea(100, 600);
                reference.fit(scene(5, 5, 0, 2));
                for (int base : new int[] { -9999, 9999 }) {
                    BoardCamera camera = camera();
                    camera.setPerspective(perspective);
                    camera.orbit(30, tilt);
                    camera.viewableArea(100, 600);
                    BoardScene scene = scene(5, 5, base, 2);
                    camera.fit(scene);
                    assertEquals(reference.camera.zoom, camera.camera.zoom, .001f,
                          "Absolute elevation must not change the fitted scale");
                    for (var tile : scene.tiles()) {
                        for (float elevation : new float[] { BoardGeometry.floor(scene) / BoardGeometry.LEVEL,
                              tile.elevation() }) {
                            for (int corner = 0; corner < 6; corner++) {
                                Vector3 screen = project(camera, BoardGeometry.corner(tile.coords(), elevation, corner));
                                Vector3 expected = project(reference,
                                      BoardGeometry.corner(tile.coords(), elevation - base, corner));
                                assertEquals(expected.x, screen.x, .2f);
                                assertEquals(expected.y, screen.y, .2f);
                                assertTrue(screen.z > 0 && screen.z < 1,
                                      "The fitted terrain must be in front of the camera and within its clip planes");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void zoomedOutOrthographicPanningKeepsTheWholeViewportInFrontOfTheNearPlane() {
        for (float tilt : new float[] { 0, 55, 80 }) {
            BoardCamera camera = camera();
            camera.orbit(45, tilt);
            camera.camera.zoom = 40;
            camera.center(new Vector3(6000, -7000, 90));
            for (int pan = 0; pan < 3; pan++) {
                Vector3 right = new Vector3(camera.camera.direction).crs(camera.camera.up).nor();
                Vector3 upOnGround = new Vector3(camera.camera.up.x, camera.camera.up.y, 0);
                upOnGround.scl(1 / upOnGround.len2());
                for (float x : new float[] { -.45f, .45f }) {
                    for (float y : new float[] { -.45f, .45f }) {
                        Vector3 point = camera.focus.cpy()
                              .mulAdd(right, x * camera.camera.viewportWidth * camera.camera.zoom)
                              .mulAdd(upOnGround, y * camera.camera.viewportHeight * camera.camera.zoom);
                        Vector3 screen = project(camera, point);
                        assertEquals((x + .5f) * 1200, screen.x, .1f);
                        assertEquals((y + .5f) * 800, screen.y, .1f);
                        assertTrue(screen.z > 0 && screen.z < 1,
                              () -> "Visible ground must not cross a clip plane while panning: " + screen);
                        assertTrue(camera.camera.frustum.pointInFrustum(point), "Culling must agree with visible ground");
                    }
                }
                camera.pan(120, -250);
            }
        }
    }

    @Test
    void perspectivePanAndPointerZoomKeepTheirWorldPlaneAnchors() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            for (float tilt : new float[] { 0, 40, 70 }) {
                BoardCamera camera = camera();
                camera.setPerspective(true);
                camera.setFieldOfView(fieldOfView);
                camera.orbit(40, tilt);
                camera.viewableArea(0, 800);
                Vector3 anchor = camera.focus.cpy();
                Vector3 before = project(camera, anchor);
                camera.pan(30, -20);
                Vector3 panned = project(camera, anchor);
                assertEquals(before.x + 30, panned.x, .05f);
                assertEquals(before.y + 20, panned.y, .05f);
                camera.zoomAt(.7f, panned.x, panned.y);
                Vector3 zoomed = project(camera, anchor);
                assertEquals(panned.x, zoomed.x, .05f);
                assertEquals(panned.y, zoomed.y, .05f);
                assertEquals(anchor.z, camera.focus.z, .001f);
            }
        }
    }

    @Test
    void selectionFramingIncludesTheWholeUnitInPerspective() {
        for (float fieldOfView : new float[] { 20, 45, 100 }) {
            BoardCamera camera = camera();
            camera.setPerspective(true);
            camera.setFieldOfView(fieldOfView);
            camera.orbit(50, 70);
            camera.zoom(.1f);
            BoardScene.Unit unit = new BoardScene.Unit(1, -1, "Unit", new BoardScene.Waypoint(new Coords(8, 7), 4, 0),
                  null, false, null, 4, true);
            camera.frameSelection(unit, 500);
            camera.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
            BoardCameraFramingTest.assertVisible(camera, 500, unit);
        }
    }

    @Test
    void perspectiveFramingPansToTheEdgeWithoutMovingAnAxisThatAlreadyFits() {
        BoardCamera camera = camera();
        camera.setPerspective(true);
        camera.viewableArea(0, 700);
        camera.center(BoardGeometry.center(new Coords(0, 6), 0));
        float y = camera.focus.y;
        BoardScene.Unit unit = new BoardScene.Unit(1, -1, "Unit", new BoardScene.Waypoint(new Coords(8, 6), 0, 0),
              null, false, null, 2, false);
        camera.frameSelection(unit, 700);
        camera.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
        assertEquals(1, camera.camera.zoom);
        assertEquals(y, camera.focus.y, .001f);
        BoardCameraFramingTest.assertVisible(camera, 700, unit);
        float rightmost = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 6; corner++) {
            rightmost = Math.max(rightmost, project(camera, BoardGeometry.corner(unit.location().coords(), 3, corner)).x);
        }
        assertEquals(700 - 64, rightmost, .05f);
    }

    @Test
    void visibleAreaMatchesFreshHeightLimitsAfterTerrainAndBoardChanges() {
        BoardScene flat = scene(40, 40, 0);
        BoardScene raised = scene(40, 40, 20);
        BoardScene lowered = scene(40, 40, -20);
        BoardScene replacement = new BoardScene(1, 24, 60, scene(24, 60, 8).tiles(),
              List.of(), List.of(), -1, "", List.of());
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera cached = visibleCamera(perspective, 0);
            var initial = cached.visibleArea(flat);
            assertNotEquals(initial, visibleCamera(perspective, 0).visibleArea(raised),
                  "The raised terrain must exercise different viewport limits");
            int panSteps = 0;
            for (BoardScene next : List.of(raised, lowered, replacement, flat)) {
                for (int step = 0; step < 3; step++) {
                    assertEquals(visibleCamera(perspective, panSteps).visibleArea(next), cached.visibleArea(next));
                    cached.pan(40, -20);
                    panSteps++;
                }
            }
        }
    }

    @Test
    void visibleAreaRefreshesHeightLimitsWhenGeometrySettingsChange() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            for (boolean perspective : new boolean[] { false, true }) {
                BoardGeometry.tune(original);
                BoardScene scene = scene(40, 40, 20);
                BoardCamera cached = visibleCamera(perspective, 0);
                var initial = cached.visibleArea(scene);
                BoardGeometry.tune(new BoardGeometry.Tuning(original.hexScale(), original.unitScale(),
                      original.unitHeightScale(), original.levelHeight() == 2 ? 18 : 2, original.gridShade(),
                      original.multiHexUnitScale(), original.transitions(), original.padding()));
                var expected = visibleCamera(perspective, 0).visibleArea(scene);
                assertNotEquals(initial, expected, "The level-height change must alter the viewport limits");
                assertEquals(expected, cached.visibleArea(scene));
                BoardGeometry.terrainChanged();
                assertEquals(visibleCamera(perspective, 0).visibleArea(scene), cached.visibleArea(scene));
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    private static BoardCamera visibleCamera(boolean perspective, int panSteps) {
        BoardCamera camera = camera();
        camera.setPerspective(perspective);
        // Keep the edge rays inside this board so height changes affect the bounds instead of board-edge clamping.
        camera.setFieldOfView(35);
        camera.setIsometric(true);
        camera.center(new Vector3(1500, -1500, 0));
        // Repeat the same steps to keep accumulated camera arithmetic identical to the reused camera.
        for (int step = 0; step < panSteps; step++) { camera.pan(40, -20); }
        return camera;
    }

    private static BoardCamera camera() {
        BoardCamera camera = new BoardCamera();
        camera.resize(1200, 800);
        return camera;
    }

    private static Vector3 project(BoardCamera camera, Vector3 point) {
        return camera.camera.project(point.cpy(), 0, 0, 1200, 800);
    }

    private static BoardScene scene(int raisedLevel) {
        return scene(5, 5, raisedLevel);
    }

    private static BoardScene scene(int width, int height, int raisedLevel) {
        return scene(width, height, 0, raisedLevel);
    }

    private static BoardScene scene(int width, int height, int base, int raisedLevel) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int elevation = base + (x == width / 2 && y == height / 2 ? raisedLevel : 0);
                tiles.add(BoardScenes.newTile(new Coords(x, y), elevation, -1, false, 0,
                      BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
