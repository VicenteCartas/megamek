/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardGroundFootprintTest {
    @Test void visibleExtensionsOnlyClearGrassWithinItsReachAndRetainGaps() {
        var triangles = new ArrayList<Vector3>();
        // A wide footing, a low overhang and a high dish: all display geometry, independent of gameplay volumes.
        panel(triangles, -12, -4, -6, 4, 0);
        panel(triangles, 2, -4, 8, 4, 4);
        panel(triangles, -30, -30, 30, 30, 20);
        var ground = ground(0, 0, 40, 0, 0);
        var area = BoardGroundFootprint.of(triangles, ground, 5, 1);
        assertTrue(area.contains(-9, 0), "A wide visible base clears grass outside any smaller gameplay volume");
        assertTrue(area.contains(5, 0), "Low overhangs are within blade reach");
        assertTrue(area.contains(-5.5, 0), "The blade's radius keeps it clear of the footing edge");
        assertFalse(area.contains(0, 0), "Open gaps remain planted");
        assertFalse(area.contains(15, 0), "A high dish does not clear the ground below it");
        assertFalse(BoardGroundFootprint.of(triangles, ground, 3, 1).contains(5, 0),
              "Short grass fits beneath the same overhang");
    }

    @Test void slopeIsClippedAtTheActualTerrainHeightAndVerticalWallsKeepClearance() {
        var triangles = new ArrayList<Vector3>();
        panel(triangles, -15, -10, 15, 10, 10);
        var area = BoardGroundFootprint.of(triangles, ground(0, 0, 25, .5f, 10), 3, .2f);
        assertFalse(area.contains(-12, 0), "Above the grass tops on the low end");
        assertTrue(area.contains(-3, 0), "Intersects the terrain-to-grass band");
        assertFalse(area.contains(8, 0), "Buried below the high end of the terrain");
        triangles = new ArrayList<>(List.of(new Vector3(0, -5, -10), new Vector3(0, 5, -10), new Vector3(0, 5, 20)));
        var wall = BoardGroundFootprint.of(triangles, ground(0, 0, 20, 0, 0), 4, .5f);
        assertTrue(wall.contains(.25, 3), "Vertical triangles project to lines with blade clearance");
        assertFalse(wall.contains(2, 3));
    }

    @Test void authoredMembersUseThePlacedSlicesAndDoNotRegrowTheCollapsedWingAsAnObstacle() {
        GdxNativesLoader.load();
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(9, 9), null);
        var at = new Coords(4, 4); var high = at.translated(1);
        editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
        editor.pointer(at, 0, 0, false); editor.finishStroke();
        var scene = capture(editor.board());
        var center = BoardGeometry.center(at, 0);
        var obstacles = new BoardObstacles(scene, scene.tile(at), ground(center.x, center.y, 110, 0, 0), 6, .2f);
        assertTrue(obstacles.obstructs(new Vector3(center).add(2, 3, 0), .2f, 6));
        assertTrue(obstacles.obstructs(BoardGeometry.center(high, 0).add(2, 3, 0), .2f, 6));
        assertFalse(obstacles.obstructs(new Vector3(center).add(-20, 5, 0), .2f, 6), "The low wing's notch stays open");
        assertFalse(obstacles.obstructs(new Vector3(center).add(59, 55, 0), .2f, 6), "The tower's notch stays open");
        editor.board().collapseBuilding(at);
        scene = capture(editor.board());
        // Collapse creates real rubble, which has its own clearance. Inspect the surviving building separately.
        var remaining = BoardObstacles.structures(scene, scene.tile(high));
        assertFalse(remaining.stream().anyMatch(p -> p.obstructs(new Vector3(center).add(2, 3, 0), .2f, 6)),
              "A surviving member cannot reserve the lost wing");
        assertTrue(remaining.stream().anyMatch(p -> p.obstructs(BoardGeometry.center(high, 0).add(2, 3, 0), .2f, 6)));
    }

    private static BoardScene capture(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of());
    }

    private static void panel(List<Vector3> into, float x0, float y0, float x1, float y1, float z) {
        into.addAll(List.of(new Vector3(x0, y0, z), new Vector3(x1, y0, z), new Vector3(x1, y1, z),
              new Vector3(x0, y0, z), new Vector3(x1, y1, z), new Vector3(x0, y1, z)));
    }

    private static List<BoardSurface.Face> ground(float x, float y, float size, float slope, float z) {
        var a = new Vector3(x - size, y - size, z - slope * size); var b = new Vector3(x + size, y - size, z + slope * size);
        var c = new Vector3(x + size, y + size, z + slope * size); var d = new Vector3(x - size, y + size, z - slope * size);
        return List.of(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP), new BoardSurface.Face(a, c, d, BoardSurface.Finish.TOP));
    }
}
