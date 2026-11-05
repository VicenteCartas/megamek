/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.Test;

class BoardWallHeightTest {
    static final Coords CENTER = BoardWallTest.CENTER;
    record Wall(int exits, int height, int elevation) { }

    @Test
    void cosmeticClearanceUsesTheShortArmHeightAndTheFullTowerHeight() {
        var scene = scene(Map.of(CENTER, new Wall(1, 6, 0), CENTER.translated(0), new Wall(8, 2, 0)));
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        var edge = joint(0).scl(.94f * BoardGeometry.hexScale()).add(BoardGeometry.center(CENTER, 0));
        assertTrue(obstacles.obstructs(new Vector3(edge).add(0, 0, BoardGeometry.level()), .1f, .1f));
        assertFalse(obstacles.obstructs(new Vector3(edge).add(0, 0, 3 * BoardGeometry.level()), .1f, .1f),
              "The tower does not reserve the empty space above its lower arm");
        assertTrue(obstacles.obstructs(BoardGeometry.center(CENTER, 3), .1f, .1f));
    }

    @Test
    void repeatedBodyJoinsRemainSolidAtAndImmediatelyAroundEverySeam() {
        for (int levels : new int[] { 6, 30 }) {
            var triangles = BoardWall.triangles("buildings/wall-9", levels);
            for (int section = 1; section < levels; section++) {
                float seam = section * HexTile.LEVEL_HEIGHT;
                for (float offset : new float[] { -.001f, 0, .001f }) {
                    var hit = new Vector3();
                    assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(40, 0, seam + offset),
                          new Vector3(-1, 0, 0)), triangles, hit), "Every repeat meets a closed side surface");
                    assertTrue(hit.x >= .7f * HexTile.ROAD_HALF_WIDTH - .01f);
                }
            }
        }
    }

    @Test
    void shortConnectionsMeetOnDifferentGroundLevelsWithoutChangingTheTowerHeight() {
        for (int ground : new int[] { -1, 0, 1, 3 }) {
            var scene = scene(Map.of(CENTER, new Wall(9, 6, 0), CENTER.translated(0), new Wall(8, 2, ground),
                  CENTER.translated(3), new Wall(1, 2, 0)));
            var own = scene.tile(CENTER);
            var layout = BoardWall.layout(scene, own, own.features().getFirst());
            var triangles = BoardWall.triangles(layout);
            var bounds = new BoundingBox().inf();
            triangles.forEach(bounds::ext);
            assertEquals(6 * HexTile.LEVEL_HEIGHT + 6.2f, bounds.max.z, .001f,
                  "The tower keeps six full levels and its barbed-wire crown");
            for (int direction : new int[] { 0, 3 }) {
                var neighbor = scene.neighbor(own, direction);
                var next = BoardWall.layout(scene, neighbor, neighbor.features().getFirst());
                var other = BoardWall.triangles(next);
                var joint = joint(direction);
                var hit = new Vector3();
                assertTrue(Intersector.intersectRayTriangles(down(new Vector3(joint).scl(.999f)), triangles, hit));
                float roof = hit.z;
                assertTrue(roof < 6 * HexTile.LEVEL_HEIGHT - 6, "No tall blank connector end extends over the lower roof");
                assertTrue(Intersector.intersectRayTriangles(down(new Vector3(joint).scl(-.999f)), other, hit));
                assertEquals(roof, hit.z + neighbor.elevation() * HexTile.LEVEL_HEIGHT, .001f,
                      "The connected roof recess meets in world space even when the ground levels differ");
                var outward = new Vector3(joint).nor();
                float above = Math.min(6 * HexTile.LEVEL_HEIGHT - 8, roof + 9);
                var start = new Vector3(joint).mulAdd(outward, 2).add(0, 0, above);
                assertTrue(Intersector.intersectRayTriangles(new Ray(start, new Vector3(outward).scl(-1)), triangles, hit));
                assertTrue(start.dst(hit) > 3, "The exposed high face belongs to the tower, not a tall arm at the hex seam");
            }
        }
    }

    @Test
    void separatedElevationsAndUnconnectedNeighborsDoNotInventAnArmConnection() {
        for (Wall neighbor : List.of(new Wall(8, 2, 8), new Wall(8, 1, -3), new Wall(1, 2, 0))) {
            var scene = scene(Map.of(CENTER, new Wall(1, 6, 0), CENTER.translated(0), neighbor));
            var tile = scene.tile(CENTER);
            var layout = BoardWall.layout(scene, tile, tile.features().getFirst());
            assertEquals(BoardWall.layout("buildings/wall-1", 6), layout,
                  "The layout only adjusts a reciprocal wall connection with overlapping vertical spans");
        }
    }

    static BoardScene mixedScene() {
        return scene(Map.of(CENTER, new Wall(39, 6, 0), CENTER.translated(0), new Wall(9, 2, 1),
              CENTER.translated(0, 2), new Wall(8, 2, 1),
              CENTER.translated(1), new Wall(16, 4, 0), CENTER.translated(2), new Wall(36, 2, -1),
              CENTER.translated(2, 2), new Wall(32, 2, -1),
              CENTER.translated(5), new Wall(4, 3, 0), new Coords(2, 6), new Wall(0, 1, 0)));
    }

    private static BoardScene scene(Map<Coords, Wall> walls) {
        return BoardScenes.scene(coords -> {
            Wall wall = walls.get(coords);
            var tile = BoardScenes.tile(coords, BoardScene.Surface.GRASS, wall == null ? 0 : wall.elevation(), -1, 0);
            return tile.withFeatures(wall == null ? List.of() : List.of(new BoardScene.Feature(
                  "buildings/wall-" + wall.exits(), 0, 0, 0, 1, wall.height(), 0, BoardScene.FeatureKind.BUILDING)));
        });
    }

    private static Vector3 joint(int direction) {
        return BoardGeometry.center(CENTER.translated(direction), 0).sub(BoardGeometry.center(CENTER, 0))
              .scl(.5f / BoardGeometry.hexScale());
    }

    private static Ray down(Vector3 at) { return new Ray(at.add(0, 0, 1000), new Vector3(0, 0, -1)); }
}
