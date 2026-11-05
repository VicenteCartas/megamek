/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardCliffTopTest {
    private static final Coords CENTER = new Coords(3, 3);

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void onlyMarkedDirectionsUseCliffsAndKeepTheirActualHeights(int levels) {
        BoardScenes.withTransitions(true, () -> {
            for (Coords upper : List.of(CENTER, new Coords(2, 2))) {
                for (int mask : new int[] { 21, 42 }) {
                    BoardScene scene = island(upper, levels, mask);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(upper));
                    List<BoardSurface.Face> walls = surface.walls(scene, BoardGeometry.floor(scene));
                    for (int direction = 0; direction < 6; direction++) {
                        boolean cliff = (mask & (1 << direction)) != 0;
                        Coords lower = upper.translated(direction);
                        int edge = Math.floorMod(1 - direction, 6);
                        BoardSurface foot = new BoardSurface(scene, scene.tile(lower));
                        assertEquals(!cliff, foot.relief.slope((edge + 3) % 6), "Direction " + direction);
                        var points = walls.stream().filter(f -> f.landEdge() == edge)
                              .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                        assertFalse(points.isEmpty());
                        assertEquals(0, points.stream().mapToDouble(p -> p.z).min().orElseThrow(), .001);
                        assertEquals(levels * BoardGeometry.level(),
                              points.stream().mapToDouble(p -> p.z).max().orElseThrow(), .001);
                        double rock = points.stream().map(surface.relief::shade)
                              .filter(s -> s != null && s.kind() == BoardRelief.Kind.CLIFF)
                              .mapToDouble(BoardRelief.Shade::level).max().orElseThrow();
                        assertEquals(cliff, rock > .99, "Marked edges expose rock; other edges retain soil slopes");
                    }
                    Vector3 anchor = BoardGeometry.center(upper, levels);
                    assertEquals(anchor.z, surface.height(anchor.x, anchor.y), .001f);
                    assertEquals(upper, BoardGeometry.pick(scene,
                          new Ray(new Vector3(anchor.x, anchor.y, 500), new Vector3(0, 0, -1))));
                }
            }
        });
    }

    @Test
    void exitsMustBeSpecifiedAndBelongToTheHigherHex() {
        Hex hex = new Hex(1);
        hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, false, 63));
        assertEquals(0, capture(hex, CENTER).cliffTopExits());
        hex.addTerrain(new Terrain("cliff_top:1:56"));
        var tile = capture(hex, CENTER);
        assertEquals(56, tile.cliffTopExits());
        assertEquals(56, tile.withText(List.of()).cliffTopExits());
        BoardScenes.withTransitions(true, () -> {
            BoardScene scene = island(CENTER, -1, 63);
            var surface = new BoardSurface(scene, scene.tile(CENTER));
            for (int edge = 0; edge < 6; edge++) {
                assertTrue(surface.relief.slope(edge), "An exit on the low side must not turn the upper edge into a cliff");
            }
        });
    }

    @Test
    void editingOnlyExitsRebuildsTheCliffAndItsNeighborSupport() {
        BoardScenes.withTransitions(true, () -> {
            BoardScene before = island(CENTER, 1, 0);
            BoardScene after = island(CENTER, 1, 1);
            assertFalse(before.tile(CENTER).sameGeometry(after.tile(CENTER)));
            var cache = new BoardSurface.Cache();
            for (Coords at : List.of(CENTER, CENTER.translated(0))) {
                var slope = cache.get(before, before.tile(at));
                var cliff = cache.get(after, after.tile(at));
                assertNotSame(slope, cliff);
                assertNotEquals(slope.faces, cliff.faces, "Exit edits change the shared rim and foot");
                assertEquals(slope.faces, cache.get(before, before.tile(at)).faces, "Removing exits restores the slope");
            }
            Coords farther = CENTER.translated(0, 2);
            assertNotEquals(BoardSurface.geometryKey(before, before.tile(farther)),
                  BoardSurface.geometryKey(after, after.tile(farther)), "Shore and corner dependencies include distant exits");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mixedCliffsAndSlopesStayClosedAtEveryDetail(boolean transitions) {
        BoardScenes.withTransitions(transitions, () -> {
            for (boolean concrete : new boolean[] { false, true }) {
                BoardScene scene = island(CENTER, 1, 56, concrete);
                float floor = BoardGeometry.floor(scene);
                for (TerrainLod lod : TerrainLod.values()) {
                    List<BoardSurface.Face> faces = new ArrayList<>();
                    for (var tile : scene.tiles()) {
                        var surface = new BoardSurface(scene, tile, lod);
                        faces.addAll(surface.faces);
                        faces.addAll(surface.walls(scene, floor));
                    }
                    BoardScenes.assertClosed(faces, floor, "Authored cliffs " + lod + " concrete=" + concrete);
                }
            }
        });
    }

    @Test
    void referenceBoardUsesAuthoredDryAndWatersideCliffs() {
        Board board = new Board();
        board.load(new File("testresources/megamek/client/ui/clientGUI/boardview/gpu/cliffs.board"));
        BoardScene scene = capture(board);
        assertEquals(56, scene.tile(new Coords(8, 12)).cliffTopExits());
        assertEquals(24, scene.tile(new Coords(9, 12)).cliffTopExits());
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            BoardScenes.withTransitions(true, () -> {
                for (Coords at : List.of(new Coords(8, 12), new Coords(9, 12))) {
                    for (int direction = 0; direction < 6; direction++) {
                        if ((scene.tile(at).cliffTopExits() & (1 << direction)) == 0) { continue; }
                        var lower = scene.tile(at.translated(direction));
                        assertEquals(-1, lower.elevation());
                        int edge = Math.floorMod(1 - (direction + 3), 6);
                        var surface = new BoardSurface(scene, lower);
                        assertFalse(surface.relief.slope(edge), "The reference one-level drop must be a cliff");
                        if (lower.water()) {
                            assertTrue(surface.relief.wetCliff(edge));
                            assertTrue(surface.faces.stream().anyMatch(f -> f.finish() == BoardSurface.Finish.WALL
                                  && f.landEdge() == edge), "The waterside cliff continues down to the bed");
                        }
                    }
                }
                var ordinary = new BoardSurface(scene, scene.tile(new Coords(7, 11)));
                assertTrue(ordinary.relief.slope(0), "The unmarked edge from 0912 to 0812 stays a slope");
            });
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static BoardScene island(Coords upper, int levels, int exits) {
        return island(upper, levels, exits, false);
    }

    private static BoardScene island(Coords upper, int levels, int exits, boolean concrete) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(at.equals(upper) ? levels : 0);
                if (concrete) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                if (at.equals(upper)) { hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, exits)); }
                tiles.add(capture(hex, at));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene capture(Board board) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                tiles.add(capture(board.getHex(at), at));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile capture(Hex hex, Coords at) {
        var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
        return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
    }
}
