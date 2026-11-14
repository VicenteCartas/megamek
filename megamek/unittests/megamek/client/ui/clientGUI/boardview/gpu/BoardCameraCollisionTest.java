/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardCameraCollisionTest {
    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    @Test
    void aLargeDownwardMoveStopsAboveTheSharedGroundAndCanSlideOrLeaveIt() {
        var scene = scene();
        var surfaces = BoardTacticalGeometry.surfaces(scene);
        Vector3 eye = BoardGeometry.center(new Coords(0, 0), 0).add(0, 0, 100);
        BoardCameraCollision.move(scene, surfaces, eye, new Vector3(0, 0, -1000), 4);
        assertEquals(4, eye.z, .01f);
        float x = eye.x;
        BoardCameraCollision.move(scene, surfaces, eye, new Vector3(10, 0, -100), 4);
        assertEquals(x + 10, eye.x, .01f);
        assertEquals(4, eye.z, .01f);
        BoardCameraCollision.move(scene, surfaces, eye, new Vector3(0, 0, 30), 4);
        assertEquals(34, eye.z, .01f);
    }

    @Test
    void cliffFacesBlockFastTravelAndSlideAlongTheWall() {
        var wall = surface(List.of(
              face(20, -100, 0, 20, 100, 0, 20, 100, 100),
              face(20, -100, 0, 20, 100, 100, 20, -100, 100)));
        Vector3 eye = new Vector3(0, 0, 20);
        BoardCameraCollision.move(scene(), coords -> wall, eye, new Vector3(1000, 30, 0), 4);
        assertEquals(16, eye.x, .01f);
        assertEquals(30, eye.y, .01f);
        assertEquals(20, eye.z, .01f);
        BoardCameraCollision.move(scene(), coords -> wall, eye, new Vector3(-10, 0, 0), 4);
        assertEquals(6, eye.x, .01f, "Contact must not trap the camera when backing away");
    }

    @Test
    void sphereEdgesAndSlopesKeepTheNearPlaneClear() {
        var wall = surface(List.of(face(20, 0, 0, 20, 100, 0, 20, 0, 100)));
        Vector3 eye = new Vector3(0, -2, 20);
        BoardCameraCollision.move(scene(), coords -> wall, eye, new Vector3(40, 0, 0), 4);
        assertTrue(eye.y < -2, "A glancing edge contact must deflect the eye even when its centre misses the triangle");
        assertTrue(eye.x < 40);
        assertTrue(eye.dst(new Vector3(20, 0, eye.z)) >= 3.99f);

        var slope = surface(List.of(face(-100, -100, -50, 200, -100, 100, -100, 200, -50)));
        eye.set(0, 0, 20);
        BoardCameraCollision.move(scene(), coords -> slope, eye, new Vector3(80, 0, -30), 4);
        assertTrue((eye.z - eye.x / 2) / Math.sqrt(1.25) >= 3.99,
              "The sphere must remain outside the slope after sliding");
    }

    @Test
    void enteringInsideEditedTerrainRecoversAboveItAndAllFlightTranslationsUseCollision() {
        var scene = scene();
        var surfaces = BoardTacticalGeometry.surfaces(scene);
        Vector3 eye = BoardGeometry.center(new Coords(0, 0), 0).add(0, 0, -30);
        BoardCameraCollision.move(scene, surfaces, eye, new Vector3(), 4);
        assertEquals(4, eye.z, .01f);
        BoardCamera camera = new BoardCamera();
        camera.resize(1200, 800);
        camera.center(BoardGeometry.center(new Coords(0, 0), 0));
        camera.flightCollision = (position, movement) -> BoardCameraCollision.move(scene, surfaces,
              position, movement, camera.collisionRadius());
        camera.setFirstPerson(true);
        camera.fly(1, 0, 0, 10000);
        assertEquals(camera.collisionRadius(), camera.camera.position.z, .01f);
        camera.zoomAt(.01f, 600, 400);
        assertEquals(camera.collisionRadius(), camera.camera.position.z, .01f);
        camera.look(0, 90);
        camera.pan(0, -1000);
        assertEquals(camera.collisionRadius(), camera.camera.position.z, .01f);
    }

    private static BoardScene scene() {
        return new BoardScene(0, 1, 1, List.of(BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0,
              BoardScene.Surface.GRASS, List.of(), List.of())), List.of(), List.of(), -1, "", List.of());
    }

    private static BoardTacticalGeometry.Surface surface(List<BoardSurface.Face> faces) {
        return new BoardTacticalGeometry.Surface(faces, List.of(), faces, List.of(), List.of(), List.of());
    }

    private static BoardSurface.Face face(float ax, float ay, float az, float bx, float by, float bz,
          float cx, float cy, float cz) {
        return new BoardSurface.Face(new Vector3(ax, ay, az), new Vector3(bx, by, bz), new Vector3(cx, cy, cz), BoardSurface.Finish.TOP);
    }
}
