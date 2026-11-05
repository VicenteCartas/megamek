/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardCliffColumnTest {
    /** Read actual generated faces before considering a lighting workaround for the reported dark chips. */
    @org.junit.jupiter.api.Test
    void exportActualMinesAndBoxCanyonCliffFrames() throws Exception {
        var boards = java.util.Map.of("mines", "Deserts/16x17 Mines 1.board",
              "box-canyon", "Map Pack Savannahs/16x17 Box Canyon (Savannah).board");
        var reports = new java.util.LinkedHashMap<String, Object>();
        for (var board : boards.entrySet()) {
            var scene = GpuRoadSourceTest.scene(board.getValue());
            Coords focus = board.getKey().equals("mines") ? new Coords(9, 3) : new Coords(4, 9);
            var records = new java.util.ArrayList<Object>();
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(focus) > 2) { continue; }
                var surface = new BoardSurface(scene, tile, TerrainLod.FULL);
                for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                    if (face.finish() != BoardSurface.Finish.WALL || face.landEdge() < 0) { continue; }
                    var vertices = new java.util.ArrayList<Object>();
                    for (var point : java.util.List.of(face.a(), face.b(), face.c())) {
                        var shade = surface.relief.shade(point);
                        assertTrue(shade != null, "Every actual cliff vertex has its generated shade");
                        vertices.add(new float[] {point.x, point.y, point.z,
                              shade.normal().x, shade.normal().y, shade.normal().z});
                    }
                    records.add(java.util.Map.of("hex", tile.coords().getBoardNum(), "edge", face.landEdge(),
                          "vertices", vertices));
                }
            }
            assertTrue(records.size() > 100, "Inspect actual cliff geometry around " + focus);
            reports.put(board.getKey(), java.util.Map.of("path", board.getValue(), "faces", records));
        }
        File output = new File("build/mines-cliff-geometry/geometry.json");
        java.nio.file.Files.createDirectories(output.toPath().getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(output, reports);
        System.out.println("Actual Mines/Box Canyon cliff frames: " + output.getAbsolutePath());
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void minesCliffColumnsDoNotFoldAcrossTheNarrowedCorner(TerrainLod detail) {
        var scene = BoardScenes.scene(new File("data/boards/Deserts/16x17 Mines 1.board"));
        var at = new Coords(12, 3);
        var surface = new BoardSurface(scene, scene.tile(at), detail);
        var outward = BoardGeometry.center(new Coords(11, 3), 0).sub(BoardGeometry.center(at, 0)).nor();
        int checked = 0;
        for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
            if (face.landEdge() != 3 || face.finish() != BoardSurface.Finish.WALL) { continue; }
            float top = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
            if (top > .5f * BoardGeometry.level()) { continue; }
            // Reduced-detail corners stitch rows at different heights. This checks the horizontal column edges,
            // whose reversal made the fin, rather than those diagonal stitching triangles.
            if (face.a().z != face.b().z && face.b().z != face.c().z && face.c().z != face.a().z) { continue; }
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            assertTrue(normal.dot(outward) >= 0,
                  "The lower wall at Mines 1304→1204 must not turn inside out into a hanging strip: " + face);
            checked++;
        }
        assertTrue(checked > 0, "The regression must examine the exposed lower cliff at " + detail);
    }
}
