/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraTerrainTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void visibleTerrainStaysAtTheUsableCenterThroughTurnsTiltsAndPresets() {
        for (boolean perspective : new boolean[] { false, true }) {
            for (int elevation : new int[] { -10, 0, 10 }) {
                BoardScene scene = scene(elevation, elevation);
                for (float tilt : new float[] { 0, 55 }) {
                    BoardCamera view = camera(perspective, tilt, new Coords(10, 10));
                    AtomicInteger picks = new AtomicInteger();
                    view.terrainHit = ray -> {
                        picks.incrementAndGet();
                        return BoardGeometry.hit(scene, ray);
                    };
                    List<Vector3> points = List.of(BoardGeometry.center(new Coords(9, 9), elevation),
                          BoardGeometry.center(new Coords(11, 11), elevation + 4),
                          BoardGeometry.center(new Coords(10, 12), elevation - 3));
                    var before = points.stream().map(point -> project(view, point)).toList();
                    Vector3 eye = view.camera.position.cpy();
                    float zoom = view.camera.zoom;

                    // Starting an eased turn anchors immediately, before its first angular animation frame.
                    view.rotateStep(1);
                    assertEquals(elevation * BoardGeometry.level(), view.focus.z, .01f);
                    for (int i = 0; i < points.size(); i++) {
                        assertScreen(before.get(i), project(view, points.get(i)));
                    }
                    if (perspective) {
                        assertTrue(eye.epsilonEquals(view.camera.position, .01f), "Anchoring must keep the eye still");
                    } else {
                        assertEquals(zoom, view.camera.zoom, "Orthographic anchoring must keep the scale");
                    }
                    Vector3 pivot = view.focus.cpy();
                    assertCentered(view, pivot);
                    view.advance(BoardCamera.ROTATION_SECONDS / 2);
                    assertCentered(view, pivot);
                    view.tilt(12);
                    assertCentered(view, pivot);
                    view.advance(BoardCamera.ROTATION_SECONDS);
                    assertCentered(view, pivot);
                    view.orbit(75, -20);
                    assertCentered(view, pivot);
                    view.setIsometric(false);
                    assertCentered(view, pivot);
                    view.setIsometric(true);
                    assertCentered(view, pivot);
                    assertEquals(1, picks.get(), "Changing angles must not retarget the pivot to an occluding surface");
                }
            }
        }
    }

    @Test
    void navigationReanchorsToTheNewHexHeight() {
        BoardScene scene = scene(0, 10);
        List<Consumer<BoardCamera>> navigation = List.of(
              view -> view.pan(-400, 0),
              view -> view.zoomAt(.4f, 1150, 400),
              view -> view.viewableArea(800, 400),
              view -> view.center(BoardGeometry.center(new Coords(14, 10), 0)),
              view -> {
                  view.frameLocation(BoardGeometry.center(new Coords(14, 10), 0), 700);
                  view.advance(BoardCamera.CAMERA_FRAMING_SECONDS);
              });
        for (boolean perspective : new boolean[] { false, true }) {
            for (var navigate : navigation) {
                BoardCamera view = camera(perspective, 0, new Coords(6, 10));
                view.terrainHit = ray -> BoardGeometry.hit(scene, ray);
                view.rotateStep(1);
                assertEquals(0, view.focus.z, .01f);
                navigate.accept(view);
                view.rotateStep(1);
                assertEquals(10 * BoardGeometry.level(), view.focus.z, .01f,
                      "The next rotation must use the raised terrain now at the view center");
                Vector3 pivot = view.focus.cpy();
                view.tilt(30);
                assertEquals(pivot, view.focus);
            }
        }
    }

    @Test
    void panningDuringAnEasedTurnReanchorsBeforeItsNextFrame() {
        BoardScene scene = scene(0, 10);
        for (boolean perspective : new boolean[] { false, true }) {
            BoardCamera view = camera(perspective, 0, new Coords(6, 10));
            view.terrainHit = ray -> BoardGeometry.hit(scene, ray);
            view.rotateStep(1);
            view.pan(-400, 0);
            view.advance(BoardCamera.ROTATION_SECONDS / 2);
            assertEquals(10 * BoardGeometry.level(), view.focus.z, .01f);
            assertCentered(view, view.focus.cpy());
        }
    }

    @Test
    void terrainEditsRefreshTheOrbitHeight() {
        BoardCamera view = camera(false, 0, new Coords(10, 10));
        AtomicReference<BoardScene> scene = new AtomicReference<>(scene(10, 10));
        view.terrainHit = ray -> BoardGeometry.hit(scene.get(), ray);
        view.rotateStep(1);
        assertEquals(10 * BoardGeometry.level(), view.focus.z, .01f);
        scene.set(scene(-5, -5));
        view.terrainChanged(scene.get());
        view.rotateStep(1);
        assertEquals(-5 * BoardGeometry.level(), view.focus.z, .01f);
    }

    @Test
    void missingTerrainKeepsThePivotAndCanBeRetriedWhenItBecomesAvailable() {
        BoardCamera view = camera(false, 0, new Coords(10, 10));
        view.terrainHit = ray -> null;
        Vector3 pivot = view.focus.cpy();
        view.orbit(15, 10);
        view.rotateStep(1);
        view.advance(BoardCamera.ROTATION_SECONDS);
        assertCentered(view, pivot);
        BoardScene scene = scene(10, 10);
        view.terrainHit = ray -> BoardGeometry.hit(scene, ray);
        view.tilt(5);
        assertEquals(10 * BoardGeometry.level(), view.focus.z, .01f);
    }

    @Test
    void firstPersonLookNeverMovesTheEyeToATerrainPivot() {
        BoardCamera view = camera(false, 55, new Coords(10, 10));
        view.terrainHit = ray -> { throw new AssertionError("Free look must not pick an orbit pivot"); };
        view.setFirstPerson(true);
        Vector3 eye = view.camera.position.cpy();
        view.orbit(20, 10);
        view.tilt(-5);
        view.rotateStep(1);
        assertEquals(eye, view.camera.position);
    }

    private static BoardCamera camera(boolean perspective, float tilt, Coords coords) {
        BoardCamera view = new BoardCamera();
        view.resize(1200, 800);
        view.setPerspective(perspective);
        view.orbit(0, tilt);
        view.viewableArea(100, 700);
        view.center(BoardGeometry.center(coords, 0));
        return view;
    }

    private static BoardScene scene(int leftLevel, int rightLevel) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), x < 10 ? leftLevel : rightLevel, -1, false, 0,
                      BoardScene.Surface.CONCRETE, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 20, 20, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static Vector3 project(BoardCamera view, Vector3 point) {
        return view.camera.project(point.cpy(), 0, 0, view.camera.viewportWidth, view.camera.viewportHeight);
    }

    private static void assertCentered(BoardCamera view, Vector3 pivot) {
        assertEquals(pivot, view.focus, "The terrain pivot stays fixed while rotating or tilting");
        assertScreen(new Vector3(450, 400, 0), project(view, pivot));
    }

    private static void assertScreen(Vector3 expected, Vector3 actual) {
        assertEquals(expected.x, actual.x, .03f);
        assertEquals(expected.y, actual.y, .03f);
    }
}
