/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraFirstPersonTest {
    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    @Test
    void freeFlightRestoresTheTacticalPoseAndStopsAnUnfinishedTurn() {
        BoardCamera camera = camera();
        camera.rotateStep(1);
        camera.advance(.1f);
        Vector3 position = camera.camera.position.cpy(), focus = camera.focus.cpy();
        Vector3 direction = camera.camera.direction.cpy();
        float zoom = camera.camera.zoom;
        camera.setFirstPerson(true);
        assertFalse(camera.isRotating());
        assertTrue(direction.epsilonEquals(camera.camera.direction, .00001f));
        camera.look(120, 50);
        camera.fly(1, 1, 1, 1000);
        camera.zoom(.5f);
        camera.advance(2);
        camera.setFirstPerson(false);
        assertFalse(camera.perspective());
        assertEquals(zoom, camera.camera.zoom);
        assertEquals(focus, camera.focus);
        assertEquals(position, camera.camera.position);
        assertEquals(direction, camera.camera.direction);
    }

    @Test
    void lookRotatesAboutTheEyeThroughTheHorizonWithoutRollingOrFlipping() {
        BoardCamera camera = camera();
        camera.setFirstPerson(true);
        Vector3 eye = camera.camera.position.cpy();
        camera.look(0, 90 - camera.tilt());
        assertEquals(0, camera.camera.direction.z, .00001f);
        camera.look(45, 30);
        assertTrue(camera.camera.direction.z > 0, "The camera must be able to look above the horizon");
        for (float pitch : new float[] { 10000, -10000, 90 }) {
            camera.look(720, pitch);
            assertEquals(eye, camera.camera.position);
            assertTrue(camera.camera.up.z > 0);
            assertEquals(0, camera.camera.direction.dot(camera.camera.up), .00001f);
            assertEquals(0, camera.camera.direction.cpy().crs(camera.camera.up).z, .00001f);
            for (float value : camera.camera.combined.val) { assertTrue(Float.isFinite(value)); }
        }
    }

    @Test
    void flightUsesTheGazeAndWorldUpWithEqualDiagonalSpeedAndNoBoardBoundary() {
        BoardCamera camera = camera();
        camera.setFirstPerson(true);
        camera.look(35, 90 - camera.tilt());
        Vector3 start = camera.camera.position.cpy();
        Vector3 forward = camera.camera.direction.cpy(), right = forward.cpy().crs(camera.camera.up).nor();
        camera.fly(1, 0, 0, 100);
        assertTrue(start.cpy().mulAdd(forward, 100).epsilonEquals(camera.camera.position, .001f));
        camera.fly(-1, 0, 0, 100);
        camera.fly(0, 1, 0, 100);
        assertTrue(start.cpy().mulAdd(right, 100).epsilonEquals(camera.camera.position, .001f));
        camera.fly(0, -1, 0, 100);
        camera.fly(0, 0, 1, 100);
        assertTrue(start.cpy().add(0, 0, 100).epsilonEquals(camera.camera.position, .001f));
        camera.fly(0, 0, -1, 100);
        camera.fly(1, 1, 1, 5000);
        assertEquals(5000, start.dst(camera.camera.position), .01f);
    }

    @Test
    void lensViewportAndPanelChangesKeepTheEyeStillAndRestoreTheScaledTacticalView() {
        BoardCamera camera = camera(), reference = camera();
        camera.setFirstPerson(true);
        camera.look(75, 60);
        camera.fly(1, 0, 1, 100);
        Vector3 eye = camera.camera.position.cpy(), direction = camera.camera.direction.cpy();
        float projectionScale = camera.camera.projection.val[0];
        camera.setFieldOfView(90);
        assertTrue(camera.camera.projection.val[0] < projectionScale);
        camera.resize(1600, 1000, null, 2);
        camera.viewableArea(120, 900);
        assertEquals(eye, camera.camera.position);
        assertEquals(direction, camera.camera.direction);
        assertEquals(1, camera.camera.near);
        reference.resize(1600, 1000, null, 2);
        reference.viewableArea(120, 900);
        camera.setFirstPerson(false);
        assertTrue(reference.camera.position.epsilonEquals(camera.camera.position, .001f));
        assertTrue(reference.focus.epsilonEquals(camera.focus, .001f));
        assertEquals(reference.camera.zoom, camera.camera.zoom);
    }

    @Test
    void closeFreeFlightViewPicksTheSameTerrainAndPresetsExitTheMode() {
        Coords coords = new Coords(0, 0);
        BoardScene scene = new BoardScene(0, 1, 1, List.of(BoardScenes.newTile(coords, 0, -1, false, 0,
              BoardScene.Surface.GRASS, List.of(), List.of())), List.of(), List.of(), -1, "", List.of());
        BoardCamera camera = camera();
        camera.setIsometric(false);
        camera.center(BoardGeometry.center(coords, 0));
        camera.setFirstPerson(true);
        camera.fly(1, 0, 0, camera.camera.position.z - 25);
        Vector3 screen = camera.camera.project(BoardGeometry.center(coords, 0), 0, 0, 1200, 800);
        Vector3 origin = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, -1)
              .prj(camera.camera.invProjectionView);
        Vector3 direction = new Vector3(2 * screen.x / 1200 - 1, 2 * screen.y / 800 - 1, 1)
              .prj(camera.camera.invProjectionView).sub(origin).nor();
        assertEquals(coords, BoardGeometry.pick(scene, new Ray(origin, direction)));
        camera.setIsometric(true);
        assertFalse(camera.firstPerson());
        assertTrue(camera.isIsometric());
        camera.setFirstPerson(true);
        camera.fit(scene);
        assertFalse(camera.firstPerson());
        assertFalse(camera.perspective());
    }

    private static BoardCamera camera() {
        BoardCamera camera = new BoardCamera();
        camera.resize(1200, 800);
        camera.setIsometric(true);
        camera.center(new Vector3(200, -200, 0));
        return camera;
    }
}
