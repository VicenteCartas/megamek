/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardRoadRoutingTest {
    private static final File MESA = new File(
          "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");

    @Test
    void mesaStraightRoadsKeepTheirAxisAndCaptureClearsTheRenderedRoute() {
        Board board = new Board();
        board.load(MESA);
        var scene = BoardScenes.scene(MESA);
        for (Coords at : List.of(new Coords(33, 15), new Coords(28, 22))) {
            var tile = scene.tile(at);
            int direction = Integer.numberOfTrailingZeros(tile.roadExits());
            assertEquals((1 << direction) | (1 << (direction + 3)), tile.roadExits(),
                  "Exercise the reported opposite-exit roads at 3416 and 2923");
            var rendered = BoardRoad.of(scene, tile);
            var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
            Vector3 gate = BoardGeometry.center(at.translated(direction), 0).sub(BoardGeometry.center(at, 0))
                  .scl(.5f / BoardGeometry.hexScale());
            Vector3 across = new Vector3(-gate.y, gate.x, 0).nor();
            for (float along = -1; along <= 1; along += .0625f) {
                for (float offset : new float[] { -9, -7, -3, 0, 3, 7, 9 }) {
                    Vector3 point = new Vector3(gate).scl(along).mulAdd(across, offset);
                    float expected = Math.abs(offset) - BoardRoad.Kind.PAVED.halfWidth;
                    assertEquals(expected, rendered.distance(point.x, point.y), .002f, at + " must not bow sideways");
                    assertEquals(rendered.distance(point.x, point.y), captured.distance(point.x, point.y), .002f,
                          "Vegetation and rocks must clear the same route that is rendered");
                }
            }
        }
    }

    @Test
    void mesaLowerRoadMakesOneBroadTurnWithoutReversingItsCurvature() {
        var scene = BoardScenes.scene(MESA);
        float previousHeading = Float.NEGATIVE_INFINITY;
        for (Coords at : List.of(new Coords(27, 22), new Coords(28, 22), new Coords(29, 21))) {
            var road = BoardRoad.of(scene, scene.tile(at));
            // Traverse the actual 2823 -> 2923 -> 3022 road from west towards north. The last tile ends at its
            // north border, so stop just before its vertical tangent; the other tiles cross their full width.
            float first = -31.25f, last = at.getX() == 29 ? -.25f : 31.25f;
            Vector2 before = new Vector2(first, centerY(road, first));
            for (float x = first + 1; x <= last; x++) {
                Vector2 point = new Vector2(x, centerY(road, x));
                float heading = (float) Math.atan2(point.y - before.y, point.x - before.x);
                assertTrue(heading >= previousHeading - .025f,
                      "A broad left turn must not insert a reverse bend at " + at + ": " + previousHeading + " -> " + heading);
                previousHeading = heading;
                before = point;
            }
        }
    }

    private static float centerY(BoardRoad road, float x) {
        float low = -36, high = 36;
        for (int step = 0; step < 45; step++) {
            float a = high - .618f * (high - low), b = low + .618f * (high - low);
            if (road.distance(x, a) < road.distance(x, b)) { high = b; } else { low = a; }
        }
        return (low + high) / 2;
    }
}
