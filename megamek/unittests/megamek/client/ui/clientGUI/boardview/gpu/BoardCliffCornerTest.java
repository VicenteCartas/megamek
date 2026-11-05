/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardCliffCornerTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void domeVentCliffsRoundAboveTheDescendingLavaWithoutMovingItsContacts(TerrainLod lod) {
        BoardScene scene = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        for (Coords at : List.of(new Coords(12, 11), new Coords(13, 11))) {
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            var walls = surface.walls(scene, BoardGeometry.floor(scene));
            Set<Vector3> shared = points(walls, 0);
            shared.retainAll(points(walls, 1));
            assertTrue(shared.size() > 4, "The two cliffs must retain a continuous shared corner at " + at);
            Vector3 corner = BoardGeometry.corner(at, 0, 1);
            float water = Math.max(scene.tile(at.translated(BoardGeometry.edgeDirection(0))).elevation(),
                  scene.tile(at.translated(BoardGeometry.edgeDirection(1))).elevation()) * BoardGeometry.level();
            float top = surface.tile.elevation() * BoardGeometry.level();
            float bend = 0;
            int contacts = 0, rims = 0;
            for (Vector3 point : shared) {
                float displacement = (float) Math.hypot(point.x - corner.x, point.y - corner.y);
                if (point.z <= water) {
                    assertEquals(0, displacement, .001f, "Existing lava mouths stay fixed at " + at);
                    contacts++;
                } else {
                    bend = Math.max(bend, displacement);
                }
                if (point.z == top) {
                    assertTrue(surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP)
                                .anyMatch(face -> face.a().equals(point) || face.b().equals(point) || face.c().equals(point)),
                          "The rounded dry rim keeps its elevation and shares the top's actual corner at " + at);
                    assertTrue(displacement > BoardRelief.metres(.1f), "The dry rim must not leave a pointed roof");
                    rims++;
                }
            }
            assertTrue(contacts > 0 && rims > 0, "Exercise both the lava and upper ground contact at " + at);
            assertTrue(bend > BoardRelief.metres(.1f),
                  "The exposed cliff corner must follow the rock relief instead of a straight vertical fin at " + at);
            for (int edge = 0; edge <= 1; edge++) {
                var outward = BoardGeometry.center(at.translated(BoardGeometry.edgeDirection(edge)), 0)
                      .sub(BoardGeometry.center(at, 0)).nor();
                for (var face : walls) {
                    if (face.landEdge() != edge) { continue; }
                    if (face.a().z != face.b().z && face.b().z != face.c().z && face.c().z != face.a().z) { continue; }
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    assertTrue(normal.dot(outward) >= 0, "The rounded corner must not fold the cliff columns at " + at);
                }
            }
        }
    }

    private static Set<Vector3> points(List<BoardSurface.Face> faces, int edge) {
        Set<Vector3> result = new HashSet<>();
        for (var face : faces) {
            if (face.landEdge() == edge) { result.addAll(List.of(face.a(), face.b(), face.c())); }
        }
        return result;
    }
}
