/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRailDamageTest {
    @Test void aBrokenJunctionRefinesOnlyPathsThatReachTheDestroyedBorder() throws Exception {
        var at = new Coords(2, 2);
        for (var kind : RailRoute.Kind.values()) {
            var board = Board.createEmptyBoard(5, 5);
            RailRoute.lay(board.getHex(at), false, 63, 0, kind);
            for (int d = 0; d < 6; d++) {
                Coords next = at.translated(d);
                RailRoute.lay(board.getHex(next), false, 1 << d | 1 << ((d + 3) % 6), 0, kind);
                board.setHex(next, board.getHex(next));
            }
            board.setHex(at, board.getHex(at));
            var original = BoardRailTest.layout(BoardScenes.scene(board), at);
            RailRoute.destroyGround(board.getHex(at.translated(0)));
            var damaged = BoardRailTest.layout(BoardScenes.scene(board), at);
            assertEquals(1, damaged.cuts());
            float[] border = RailRoute.border(0);
            var intact = original.paths().stream().filter(path -> List.of(path.getFirst(), path.getLast()).stream()
                  .noneMatch(point -> Math.abs(point.x - border[0]) < .01f && Math.abs(point.y - border[1]) < .01f)).toList();
            assertFalse(intact.isEmpty(), "The junction contains other routes");
            assertTrue(damaged.paths().containsAll(intact), "Other routes keep their original sampling and geometry");
            assertNotEquals(original.paths(), damaged.paths(), "The broken route gains vertices for its curled end");
        }
    }

    @Test void bothKindsKeepTheirCourseAndCurlOnlyTheirBrokenBorderWithoutAddingStops() throws Exception {
        var at = new Coords(2, 2);
        for (var kind : RailRoute.Kind.values()) {
            for (int d = 0; d < 6; d++) {
                var board = Board.createEmptyBoard(5, 5);
                var lost = at.translated(d);
                int sides = 1 << d | 1 << ((d + 3) % 6);
                RailRoute.lay(board.getHex(at), false, sides, 0, kind);
                RailRoute.lay(board.getHex(lost), false, sides, 0, kind);
                board.setHex(at, board.getHex(at));
                board.setHex(lost, board.getHex(lost));
                var before = BoardScenes.scene(board);
                var original = BoardRailTest.layout(before, at);
                RailRoute.destroyGround(board.getHex(lost));
                var after = BoardScenes.scene(board);
                var tile = after.tile(at);
                var layout = BoardRailTest.layout(after, at);
                assertNotEquals(original, layout, "Damage must invalidate the shared route model's key");
                assertEquals(original.ends(), layout.ends(), "The former course keeps its original border");
                assertEquals(1 << d, layout.cuts());
                assertEquals(tile.railCuts(), tile.withText(List.of()).withoutGroundCover(false).railCuts());
                assertTrue(tile.features().stream().noneMatch(f -> f.asset().equals(RailRoute.BUFFER_STOP)));
                assertNull(BoardRail.route(after.tile(lost)));
                var mesh = BoardRail.model(layout).meshes.first();
                float highest = 0, opposite = 0;
                float[] border = RailRoute.border(d);
                for (int i = 0; i < mesh.vertices.length; i += 7) {
                    float z = mesh.vertices[i + 2];
                    highest = Math.max(highest, z);
                    if (mesh.vertices[i] * border[0] + mesh.vertices[i + 1] * border[1] < 0) { opposite = Math.max(opposite, z); }
                }
                float top = kind == RailRoute.Kind.RAIL ? RailRoute.RAIL_TOP : 4;
                assertTrue(highest > top + 2, "Exposed steel tips must bend out of the former plane");
                assertEquals(top, opposite, .01f, "The undamaged half keeps its original profile");
                boolean gradient = false;
                for (var part : mesh.parts) {
                    for (int i = 0; i < part.indices.length; i += 3) {
                        int a = Short.toUnsignedInt(part.indices[i]) * 7, b = Short.toUnsignedInt(part.indices[i + 1]) * 7;
                        int c = Short.toUnsignedInt(part.indices[i + 2]) * 7;
                        if (Float.floatToRawIntBits(mesh.vertices[a + 6]) != Float.floatToRawIntBits(mesh.vertices[b + 6])
                              || Float.floatToRawIntBits(mesh.vertices[a + 6]) != Float.floatToRawIntBits(mesh.vertices[c + 6])) {
                            gradient = true; break;
                        }
                    }
                }
                assertTrue(gradient, "Scorch must interpolate within faces instead of assigning a flat stripe per segment");
                RailRoute.lay(board.getHex(at), false, 1 << ((d + 3) % 6), 0, kind);
                var terminal = BoardScenes.scene(board).tile(at);
                assertEquals(0, terminal.railCuts().ground());
                assertEquals(kind == RailRoute.Kind.RAIL ? 1 : 0,
                      terminal.features().stream().filter(f -> f.asset().equals(RailRoute.BUFFER_STOP)).count());
            }
        }
    }
}
