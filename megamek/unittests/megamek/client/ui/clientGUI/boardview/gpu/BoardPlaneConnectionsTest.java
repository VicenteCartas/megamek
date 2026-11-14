/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.common.RangeType;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardPlaneConnectionsTest {
    @Test
    void realRangePaintersJoinBothEndsOfEveryElevationSeam() {
        Coords high = new Coords(2, 2);
        BoardScene scene = scene(high, 3);
        for (boolean weapon : List.of(false, true)) {
            for (int direction = 0; direction < 6; direction++) {
                Coords low = high.translated(direction);
                BoardTactical tactical = range(Set.of(high, low), weapon, Color.CYAN);
                List<BoardTacticalGeometry.Triangle> connections = connections(scene, tactical.fills());
                assertFalse(connections.isEmpty(), "Connect the actual painter at direction " + direction + ", weapon=" + weapon);
                var step = BoardTacticalGeometry.planeStep(scene, high, Math.floorMod(1 - direction, 6));
                Vector3 edge = new Vector3(step.b()).sub(step.a());
                boolean firstEnd = false, secondEnd = false;
                for (var triangle : connections) {
                    assertTrue(tactical.fills().stream().anyMatch(fill -> fill.argb() == triangle.argb()),
                          "Keep the captured band/outline color and opacity");
                    for (Vector3 point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        assertTrue(point.z == step.a().z || point.z == step.bottom());
                        Vector3 offset = new Vector3(point.x - step.a().x, point.y - step.a().y, 0);
                        float along = offset.dot(edge) / edge.len2();
                        assertEquals(0, new Vector3(offset).mulAdd(edge, -along).len(), .001,
                              "Only the common hex edge gets a vertical connector");
                        firstEnd |= along < .35f;
                        secondEnd |= along > .65f;
                    }
                }
                assertTrue(firstEnd && secondEnd, "Both ends of the region must join across the step");
            }
        }
    }

    @Test
    void levelPlanesIsolatedBandsAndDifferentColorsDoNotAcquireWalls() {
        Coords high = new Coords(2, 2), low = high.translated(0);
        BoardScene raised = scene(high, 3);
        var together = range(Set.of(high, low), false, Color.CYAN);
        assertTrue(connections(scene(high, 0), together.fills()).isEmpty());
        List<BoardTactical.Fill> first = together.fills().stream()
              .filter(fill -> BoardTacticalGeometry.anchorCoords(raised, fill.planeAnchor()).equals(high)).toList();
        assertTrue(connections(raised, first).isEmpty());
        List<BoardTactical.Fill> different = new ArrayList<>(first);
        different.addAll(range(Set.of(high, low), false, Color.YELLOW).fills().stream()
              .filter(fill -> BoardTacticalGeometry.anchorCoords(raised, fill.planeAnchor()).equals(low)).toList());
        assertTrue(connections(raised, different).isEmpty());

        var deployment = new BoardTacticalGraphics();
        try {
            BoardTacticalGraphics.drawDeployment(deployment, local -> {
                for (Coords coords : List.of(high, low)) {
                    var hex = BoardTacticalGraphics.onHexPlane(local, coords);
                    hex.setColor(Color.CYAN);
                    hex.fillHexBorder(0, 7);
                    hex.dispose();
                }
            });
            assertTrue(connections(raised, deployment.snapshot().fills()).isEmpty(),
                  "Deployment already draws its own joined perimeter curtains");
        } finally { deployment.dispose(); }
    }

    static List<BoardTacticalGeometry.Triangle> connections(BoardScene scene, List<BoardTactical.Fill> fills) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        BoardTacticalGeometry.connectPlanes(scene, fills, result::add);
        return result;
    }

    static BoardTactical range(Set<Coords> owners, boolean weapon, Color color) {
        var graphics = new BoardTacticalGraphics();
        try {
            for (Coords owner : owners.stream().sorted(java.util.Comparator.comparingInt(Coords::getX)
                  .thenComparingInt(Coords::getY)).toList()) {
                int borders = 0;
                for (int direction = 0; direction < 6; direction++) {
                    if (!owners.contains(owner.translated(direction))) { borders |= 1 << direction; }
                }
                if (weapon) {
                    BoardTacticalMarks.zone(graphics, owner, borders, TacticalStyle.withAlpha(
                          TacticalStyle.rangeColor(RangeType.RANGE_SHORT), TacticalStyle.RANGE_BAND_OPACITY),
                          TacticalStyle.RANGE_BAND_WIDTH, null);
                } else {
                    BoardTacticalMarks.envelope(graphics, new TacticalMark.Envelope(owner, borders, color));
                }
            }
            return graphics.snapshot();
        } finally {
            graphics.dispose();
        }
    }

    static BoardScene scene(Coords raised, int level) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(BoardScenes.newTile(coords, coords.equals(raised) ? level : 0, -1, false, 0,
                      BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
