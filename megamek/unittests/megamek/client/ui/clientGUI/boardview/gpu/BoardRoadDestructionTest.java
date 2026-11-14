/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadDestructionTest {
    @Test void everyRoadSurfaceEndsAcrossItsFullWidthAtTheDestroyedBorder() throws Exception {
        var at = new Coords(2, 2);
        for (int kind : List.of(1, 2, 3, 4)) {
            for (int direction = 0; direction < 6; direction++) {
                Board board = Board.createEmptyBoard(5, 5);
                var lost = at.translated(direction);
                board.setHex(at, new Hex(0, "road:" + kind, ""));
                board.setHex(at.translated((direction + 3) % 6), new Hex(0, "road:" + kind, ""));
                board.setHex(lost, new Hex(0, "road:" + kind, ""));
                Hex destroyed = board.getHex(lost);
                destroyed.destroyRoad(board::getHex);
                for (int level : List.of(0, -3)) {
                    destroyed.setLevel(level);
                    board.setHex(lost, destroyed);
                    var scene = BoardScenes.scene(board);
                    var tile = scene.tile(at);
                    assertEquals(1 << direction, tile.roadCuts());
                    var road = GpuRoads.painted(scene, tile, BoardRoad.of(scene, tile));
                    var shape = road.footprint(0);
                    assertTrue(shape.equals(GpuRoads.painted(scene, tile, BoardRoad.of(scene, tile), true).footprint(0)),
                          "Classic and Enhanced share the same destroyed road edge");
                    float[] border = RailRoute.border(direction);
                    float length = (float) Math.hypot(border[0], border[1]);
                    float nx = border[0] / length, ny = border[1] / length;
                    for (float across : new float[] { -.8f * tile.road().halfWidth, 0, .8f * tile.road().halfWidth }) {
                        assertTrue(shape.contains(nx * (length - .05f) - ny * across, ny * (length - .05f) + nx * across));
                        assertFalse(shape.contains(nx * (length + .05f) - ny * across, ny * (length + .05f) + nx * across));
                    }
                    assertEquals(tile.roadCuts(), tile.withText(List.of()).withoutGroundCover(false).roadCuts());
                    var patches = GpuRoads.patches(scene, tile, road, BoardRoad.of(scene, tile));
                    var scorch = patches.stream().filter(p -> p.texture().equals("ground-scorch")).findFirst().orElseThrow();
                    assertTrue(GpuRoads.coverage(road, scorch, nx * (length - 3), ny * (length - 3)) > .5f);
                    assertEquals(0, GpuRoads.coverage(road, scorch, nx * (length + 1), ny * (length + 1)));
                    assertEquals(0, GpuRoads.coverage(road, scorch, 0, 0));
                }
                // A new road at the same location is an editor repair, not another damaged end.
                board.setHex(lost, new Hex(0, "road:" + kind, ""));
                assertEquals(0, BoardScenes.scene(board).tile(at).roadCuts());
            }
        }
    }

    @Test void ordinaryDeadEndsAndUnconnectedExplicitRoadsKeepTheirOriginalEnds() throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        var at = new Coords(2, 2);
        board.setHex(at, new Hex(0, "road:1:8", ""));
        board.setHex(at.translated(3), new Hex(0, "road:1:1", ""));
        var scene = BoardScenes.scene(board);
        var original = BoardRoad.of(scene, scene.tile(at));
        assertSame(original, GpuRoads.painted(scene, scene.tile(at), original));
        assertTrue(GpuRoads.patches(scene, scene.tile(at), original, original).stream()
              .noneMatch(p -> p.texture().equals("ground-scorch")));
        // The north hex points south, but the original dead end never joined it back.
        var north = at.translated(0);
        board.setHex(north, new Hex(0, "road:1:8", ""));
        board.getHex(north).destroyRoad(board::getHex);
        board.setHex(north, board.getHex(north));
        var after = BoardScenes.scene(board);
        assertEquals(0, after.tile(at).roadCuts());
        assertTrue(original.footprint(0).equals(BoardRoad.of(after, after.tile(at)).footprint(0)));
    }

    @Test void reauthoringTheSurvivingRoadRemovesItsOldDamageBranchIncludingAfterNativeReload() throws Exception {
        for (boolean automatic : List.of(false, true)) {
            Board board = Board.createEmptyBoard(5, 5);
            var at = new Coords(2, 2); var lost = at.translated(0);
            String road = automatic ? "road:1" : "road:1:9";
            board.setHex(at, new Hex(0, road, ""));
            board.setHex(lost, new Hex(0, road, ""));
            board.getHex(lost).destroyRoad(board::getHex);
            board.setHex(lost, board.getHex(lost));
            assertEquals(1, board.getHex(at).roadCuts(board::getHex));
            var bytes = new ByteArrayOutputStream(); BoardFile.write(board, bytes);
            Board loaded = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8));
            assertEquals(!automatic, loaded.getHex(at).getTerrain(Terrains.ROAD).hasExitsSpecified());
            assertEquals(1, loaded.getHex(at).roadCuts(loaded::getHex), "Automatic roads retain their destroyed approach after reload");
            for (Board edited : List.of(board, loaded)) {
                edited.setHex(at, new Hex(0, "road:1:18", ""));
                var scene = BoardScenes.scene(edited);
                assertEquals(0, scene.tile(at).roadCuts());
                assertEquals(18, scene.tile(at).roadExits(), "The destroyed north neighbour cannot add an explicitly removed branch");
            }
        }
    }
}
