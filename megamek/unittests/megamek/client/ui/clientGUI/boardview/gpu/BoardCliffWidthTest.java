/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Natural cliff insets leave usable, continuous tops across the shared ridge mesh at every detail level. */
class BoardCliffWidthTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void boxCanyonKeepsContinuousRidgeConnections(TerrainLod lod) {
        BoardScenes.withTransitions(true, () -> {
            var scene = BoardScenes.scene(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
            Map<Coords, BoardSurface> surfaces = new HashMap<>();
            int checked = 0;
            for (var tile : scene.tiles()) {
                if (tile.elevation() != 8) { continue; }
                for (int edge = 0; edge < 6; edge++) {
                    var other = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                    if (other == null || other.elevation() != tile.elevation()
                          || tile.coords().getBoardNum().compareTo(other.coords().getBoardNum()) >= 0) { continue; }
                    var a = surfaces.computeIfAbsent(tile.coords(), key -> new BoardSurface(scene, tile, lod));
                    var b = surfaces.computeIfAbsent(other.coords(), key -> new BoardSurface(scene, other, lod));
                    checkConnection(tile, other, a, b, edge, lod);
                    checked++;
                }
            }
            assertTrue(checked > 50, "Exercise ridge connections throughout the shipped board");
        });
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void dryCliffsKeepRidgesAcrossMaterialsAndDirections(TerrainLod lod) {
        BoardScenes.withTransitions(true, () -> {
            Coords start = new Coords(3, 3);
            for (var family : BoardScene.Surface.values()) {
                for (int direction = 0; direction < 6; direction++) {
                    Coords end = start.translated(direction);
                    List<BoardScene.Tile> tiles = new ArrayList<>();
                    for (int x = 0; x < 7; x++) {
                        for (int y = 0; y < 7; y++) {
                            Coords at = new Coords(x, y);
                            tiles.add(new BoardScene.Tile(at, at.equals(start) || at.equals(end) ? 8 : 0, -1,
                                  false, 0, family, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
                        }
                    }
                    var scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                    var a = new BoardSurface(scene, scene.tile(start), lod);
                    var b = new BoardSurface(scene, scene.tile(end), lod);
                    checkConnection(scene.tile(start), scene.tile(end), a, b, Math.floorMod(1 - direction, 6), lod);
                }
            }
        });
    }

    private static void checkConnection(BoardScene.Tile tile, BoardScene.Tile other, BoardSurface a, BoardSurface b,
          int edge, TerrainLod lod) {
        Vector3 from = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
        Vector3 to = BoardGeometry.corner(tile.coords(), tile.elevation(), edge + 1);
        Vector3 along = new Vector3(to).sub(from).nor();
        Vector3 first = a.relief.seam(edge, edge, 0), last = a.relief.seam(edge, edge, 1);
        String where = tile.coords().getBoardNum() + " -> " + other.coords().getBoardNum() + " " + tile.surface() + " " + lod;
        // Keep at least 4.5 m of a roughly 15 m hex edge. The former inset left several Box Canyon joins under 2 m.
        assertTrue(new Vector3(last).sub(first).dot(along) >= .3f * from.dst(to), "Ridge width: " + where);
        int opposite = (edge + 3) % 6;
        assertTrue(first.epsilonEquals(b.relief.seam(opposite, opposite, 1), .001f), "Shared rim: " + where);
        assertTrue(last.epsilonEquals(b.relief.seam(opposite, opposite, 0), .001f), "Shared rim: " + where);
        // Sample real top triangles on both sides; height() also has a logical-ground fallback that hides holes.
        for (int i = 1; i < 8; i++) {
            Vector3 on = new Vector3(first).lerp(last, i / 8f);
            for (var side : List.of(a, b)) {
                var owner = side == a ? tile : other;
                Vector3 point = new Vector3(on).lerp(BoardGeometry.center(owner.coords(), owner.elevation()), .02f);
                float top = Float.NEGATIVE_INFINITY;
                for (var face : side.groundFaces()) {
                    if (face.finish() == BoardSurface.Finish.TOP) { top = Math.max(top, face.height(point.x, point.y)); }
                }
                assertEquals(owner.elevation() * BoardGeometry.level(), top, .001f, "Continuous top: " + where);
            }
        }
    }
}
