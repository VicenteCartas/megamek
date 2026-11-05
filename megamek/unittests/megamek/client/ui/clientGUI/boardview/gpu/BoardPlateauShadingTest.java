/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardPlateauShadingTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void boxCanyonNarrowNeckStaysSharedWithoutFolding(TerrainLod detail) {
        var scene = BoardScenes.scene(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
        var north = new BoardSurface(scene, scene.tile(new Coords(14, 13)), detail).relief;
        var south = new BoardSurface(scene, scene.tile(new Coords(14, 14)), detail).relief;
        Vector3 start = south.seam(1, 1, 0), end = south.seam(1, 1, 1);
        Vector3 direction = new Vector3(end).sub(start).nor();
        float previous = -1;
        for (int i = 0; i <= 32; i++) {
            float t = i / 32f;
            Vector3 point = south.seam(1, 1, t);
            assertTrue(point.epsilonEquals(north.seam(4, 5, t), .0001f),
                  "Hexes 1514 and 1515 share their narrow plateau neck at " + t + ", " + detail);
            float distance = new Vector3(point).sub(start).dot(direction);
            assertTrue(distance > previous && distance <= start.dst(end) + .0001f,
                  "The rim must advance without folding back at " + t + ", " + detail);
            previous = distance;
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void boxCanyonKeepsCliffLightingNearTheRim(TerrainLod detail) {
        var scene = BoardScenes.scene(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
        for (Coords at : List.of(new Coords(13, 1), new Coords(12, 1), new Coords(11, 12), new Coords(14, 13),
              new Coords(14, 14))) {
            var tile = scene.tile(at);
            var surface = new BoardSurface(scene, tile, detail);
            var top = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            Map<Set<Vector3>, Integer> edges = new HashMap<>();
            for (var face : top) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z > 0,
                      "Plateau triangles must face upward at " + at + ", " + detail + ": " + face);
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertEquals(tile.elevation() * BoardGeometry.level(), p.z, .001f);
                }
                count(edges, face.a(), face.b());
                count(edges, face.b(), face.c());
                count(edges, face.c(), face.a());
            }
            long vertices = edges.keySet().stream().flatMap(Set::stream).distinct().count();
            assertEquals(1, vertices - edges.size() + top.size(),
                  "The plateau remains a disc with no holes or unmatched interior subdivisions at " + at + ", " + detail);
            List<List<Vector3>> boundary = edges.entrySet().stream().filter(e -> e.getValue() == 1)
                  .map(e -> List.copyOf(e.getKey())).toList();
            assertFlatInterior(surface, top, boundary, at);

            // The top keeps every boundary segment used by the cliff: refinement must not open cracks there.
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    Vector3 a = points.get(i), b = points.get((i + 1) % 3);
                    if (a.z == tile.elevation() * BoardGeometry.level() && b.z == a.z && !a.equals(b)) {
                        assertEquals(1, edges.getOrDefault(Set.of(a, b), 0),
                              "Cliff and plateau retain the same boundary segment at " + at + ", " + detail);
                    }
                }
            }
        }
    }

    private static void assertFlatInterior(BoardSurface surface, List<BoardSurface.Face> top,
          List<List<Vector3>> boundary, Coords at) {
        int checked = 0;
        float worstTilt = 0;
        BoardSurface.Face worstFace = null;
        for (var face : top) {
            for (int u = 0; u <= 4; u++) {
                for (int v = 0; v <= 4 - u; v++) {
                    float a = u / 4f, b = v / 4f, c = 1 - a - b;
                    Vector3 point = new Vector3(face.a()).scl(a).mulAdd(face.b(), b).mulAdd(face.c(), c);
                    float margin = Float.POSITIVE_INFINITY;
                    for (var edge : boundary) {
                        var from = edge.get(0);
                        var to = edge.get(1);
                        margin = Math.min(margin, BoardRelief.distance(point.x, point.y, from.x, from.y, to.x, to.y));
                    }
                    if (margin < BoardRelief.metres(2.5f)) { continue; }
                    Vector3 normal = new Vector3(surface.relief.shade(face.a()).normal()).scl(a)
                          .mulAdd(surface.relief.shade(face.b()).normal(), b)
                          .mulAdd(surface.relief.shade(face.c()).normal(), c).nor();
                    float tilt = new Vector3(normal).sub(Vector3.Z).len();
                    if (tilt > worstTilt) { worstTilt = tilt; worstFace = face; }
                    checked++;
                }
            }
        }
        assertTrue(checked > 10, "Exercise the plateau interior at " + at);
        assertEquals(0, worstTilt, .02f, "Cliff rim lighting must not cross the flat interior at " + at + ": " + worstFace);
    }

    private static void count(Map<Set<Vector3>, Integer> edges, Vector3 a, Vector3 b) {
        assertTrue(edges.merge(Set.of(a, b), 1, Integer::sum) <= 2, "No overlapping plateau faces");
    }
}
