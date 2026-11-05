/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared cliff corners, rims and feet on the shipped map, including the artwork-covered bunker. */
class BoardCliffSeamTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void adjoiningCliffsShareTheirBoundaries(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardCliffSeamTest::checkBoundaries);
    }

    @Test
    void submergedCliffsJoinBothTheRiverbedAndOrdinaryBanks() throws Exception {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            BoardScene scene = BoardScenes.scene("Map Pack Savannahs/16x17 Mountain Lake (Savannah).board");
            Map<BoardScenes.Segment, Integer> joined = new HashMap<>();
            List<BoardSurface.Face> submerged = new ArrayList<>();
            Map<String, List<BoardSurface.Face>> upper = new HashMap<>();
            Coords center = new Coords(8, 14);
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(center) > 3) { continue; }
                var surface = new BoardSurface(scene, tile);
                BoardScenes.countEdges(joined, surface.faces);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                BoardScenes.countEdges(joined, walls);
                if (tile.coords().distance(center) <= 1 && tile.liquid().present()) {
                    BoardScenes.checkSubmergedShading(surface);
                    submerged.addAll(surface.faces.stream()
                          .filter(face -> face.finish() == BoardSurface.Finish.WALL).toList());
                    for (var face : surface.faces) {
                        if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                        for (var p : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(BoardRelief.Kind.GROUND, surface.relief.shade(p).kind(),
                                  "A bank touching the cliff must retain its own ground material: " + p);
                        }
                    }
                } else if (!tile.liquid().present()) {
                    for (var face : walls) {
                        if (face.landEdge() < 0) { continue; }
                        var next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                        if (next != null && next.liquid().present() && next.coords().distance(center) <= 1) {
                            upper.computeIfAbsent(tile.coords() + " edge " + face.landEdge(), key -> new ArrayList<>())
                                  .add(face);
                        }
                    }
                }
            }
            Map<BoardScenes.Segment, Integer> boundary = new HashMap<>();
            BoardScenes.countEdges(boundary, submerged);
            assertFalse(boundary.isEmpty());
            BoardScenes.assertJoined(boundary, joined, "Submerged cliff");
            for (var entry : upper.entrySet()) {
                Map<BoardScenes.Segment, Integer> edge = new HashMap<>();
                BoardScenes.countEdges(edge, entry.getValue());
                BoardScenes.assertJoined(edge, joined, entry.getKey());
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static void checkBoundaries() {
        BoardScene scene = BoardScenes.scene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        Map<BoardScenes.Segment, Integer> joined = new HashMap<>();
        Map<Coords, List<BoardSurface.Face>> cliffs = new HashMap<>();
        // The cliffs at 1111 and 1112 and all the adjoining ground, banks and walls.
        for (int x = 9; x <= 11; x++) {
            for (int y = 9; y <= 12; y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> walls = surface.walls(scene, floor);
                BoardScenes.countEdges(joined, surface.faces);
                BoardScenes.countEdges(joined, walls);
                if (x == 10 && (y == 10 || y == 11)) { cliffs.put(tile.coords(), walls); }
            }
        }
        for (var cliff : cliffs.entrySet()) {
            for (int e = 0; e < 6; e++) {
                if (!scene.tile(cliff.getKey().translated(BoardGeometry.edgeDirection(e))).liquid().present()) {
                    continue;
                }
                int edge = e;
                Map<BoardScenes.Segment, Integer> boundary = new HashMap<>();
                BoardScenes.countEdges(boundary, cliff.getValue().stream().filter(face -> face.landEdge() == edge).toList());
                assertFalse(boundary.isEmpty(), "The exposed cliff must exist");
                BoardScenes.assertJoined(boundary, joined, cliff.getKey() + " edge " + edge);
            }
        }
    }
}
