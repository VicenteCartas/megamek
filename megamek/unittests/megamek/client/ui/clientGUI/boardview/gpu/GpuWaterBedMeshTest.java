/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuWaterBedMeshTest {
    @ParameterizedTest
    @CsvSource({
          "Map Pack Savannahs/16x17 River Delta-Drainage Basin 1 (Savannah).board, 8, 12",
          "unofficial/Lanceman/16x17 Waterfall.board, 4, 7",
          "Map Pack Volcanic/16x17 Laze Plumes 1.board, 3, 5"
    })
    void convertedShallowBarsKeepTheirGeometryAndContinuousWaterMaterial(String path, int x, int y) throws Exception {
        var board = BoardFile.loadSheet(new File(Configuration.boardsDir(), path), false, true);
        var scene = BoardScenes.scene(board);
        var tile = scene.tile(new Coords(x, y));
        assertEquals(0, tile.waterDepth(), "The converted fixture includes the reported shallow bar");
        for (TerrainLod lod : List.of(TerrainLod.FULL, TerrainLod.MEDIUM)) {
            var surface = new BoardSurface(scene, tile, lod);
            var bed = surface.renderBed(surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.BED).toList());
            double expectedArea = 0, drawnArea = 0;
            float highest = Float.NEGATIVE_INFINITY, drawnHighest = Float.NEGATIVE_INFINITY;
            for (var face : bed) {
                expectedArea += area(face.a(), face.b(), face.c());
                for (var p : List.of(face.a(), face.b(), face.c())) { highest = Math.max(highest, p.z); }
            }
            assertTrue(highest > BoardGeometry.waterZ(tile) + .01f, "Keep the existing exposed bar: " + path);
            var plan = GpuTerrain.prepareSculpt(scene, tile, surface, BoardGeometry.floor(scene), lod, new HashMap<>());
            int dry = 0, wet = 0;
            for (var triangle : BoardScenes.blendedTriangles(plan)) {
                var a = triangle.a().vertex().position;
                var b = triangle.b().vertex().position;
                var c = triangle.c().vertex().position;
                var middle = new Vector3(a).add(b).add(c).scl(1 / 3f);
                // The material batches also contain the surrounding bank and walls; isolate the actual bed plane.
                float bedHeight = BoardSurface.sampleHeight(bed, middle.x, middle.y, Float.NaN);
                if (!Float.isFinite(bedHeight) || Math.abs(bedHeight - middle.z) > .001f) { continue; }
                drawnArea += area(a, b, c);
                for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    var vertex = point.vertex();
                    var p = vertex.position;
                    drawnHighest = Math.max(drawnHighest, p.z);
                    assertEquals(p.z, BoardSurface.sampleHeight(bed, p.x, p.y, Float.NaN), .003f,
                          "Material preparation keeps the canonical bar surface");
                    assertTrue(vertex.color.a < .25f && vertex.color.b < .125f,
                          "Wet and exposed parts share water material context: " + path + " " + lod + " " + p);
                    if (p.z > surface.waterHeight(p.x, p.y) + .01f) {
                        dry++;
                        assertTrue(vertex.uv.y <= -1, "An exposed bar retains depth-zero material ownership");
                        float water = (vertex.color.g * 255 - 64 + vertex.color.b * 8) * BoardGeometry.level()
                              - BoardGeometry.hexScale();
                        assertEquals(surface.waterHeight(p.x, p.y), water, .003f,
                              "The existing bank fade can continue across the exposed bar");
                    } else { wet++; }
                }
            }
            assertTrue(dry > 0 && wet > 0, "Exercise both sides of the waterline on the same bed");
            assertEquals(highest, drawnHighest, .003f, "The visible bar is not flattened or submerged");
            assertEquals(expectedArea, drawnArea, expectedArea * .0001,
                  "Material preparation preserves the whole bed without duplicate layers");
        }
    }

    private static float area(Vector3 a, Vector3 b, Vector3 c) {
        return Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2;
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 4 })
    void levelPoolBedsStayInsideTheWaterAlongCurvedBanksAndMouths(int depth) {
        for (boolean mouths : new boolean[] { false, true }) {
            var center = BoardScenes.CENTER;
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.SAND,
                  0, c.equals(center) || mouths && (c.equals(center.translated(1)) || c.equals(center.translated(4)))
                        ? depth : -1, 0));
            var original = new BoardSurface(scene, scene.tile(center));
            // Cached water geometry must make the same coverage decision as a freshly built surface.
            for (var surface : List.of(original, original.waterGeometry().surface(scene))) {
                assertTrue(surface.bedUnderLevelWater());
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED) { continue; }
                    for (int a = 0; a <= 4; a++) for (int b = 0; b <= 4 - a; b++) {
                        var p = new Vector3(face.a()).scl(a / 4f).mulAdd(face.b(), b / 4f)
                              .mulAdd(face.c(), (4 - a - b) / 4f);
                        float water = BoardSurface.sampleHeight(surface.waterFaces, p.x, p.y, Float.NaN);
                        assertEquals(BoardGeometry.waterZ(surface.tile), water, .001f,
                              "Unclipped beds must stay within the actual water footprint, including concave banks");
                        assertTrue(p.z <= water + .001f, "An unclipped bed cannot expose a dry bar");
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void levelWaterKeepsTheSparseBedThroughMaterialPreparation(TerrainLod lod) {
        for (boolean varyingDepth : new boolean[] { false, true }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.SAND,
                  0, varyingDepth ? 1 + Math.floorMod(c.getX() + c.getY(), 4) : 2, 0));
            var tile = scene.tile(BoardScenes.CENTER);
            var surface = new BoardSurface(scene, tile, lod);
            var bed = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.BED).toList();
            var plan = GpuTerrain.prepareSculpt(scene, tile, surface, BoardGeometry.floor(scene), lod, new HashMap<>());
            var drawn = BoardScenes.blendedTriangles(plan);
            System.out.printf("WATER BED lod=%s varyingDepth=%s source=%d rendered=%d%n", lod, varyingDepth,
                  bed.size(), drawn.size());
            assertEquals(bed.size(), drawn.size(), "Water's internal diagonals must not subdivide the bed");
            if (!varyingDepth) { assertEquals(6, drawn.size(), "An open flat bed needs only six triangles"); }
            double area = 0;
            for (var triangle : drawn) {
                var a = triangle.a().vertex().position;
                var b = triangle.b().vertex().position;
                var c = triangle.c().vertex().position;
                float cross = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).z;
                assertTrue(cross > 0, "The bed stays upward-facing");
                area += cross * .5;
                for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    var p = point.vertex().position;
                    assertEquals(p.z, BoardSurface.sampleHeight(bed, p.x, p.y, Float.NaN), .001f,
                          "Depth transitions retain the canonical bed height");
                    assertEquals(1, point.cover().sand(), .00001f);
                    assertTrue(point.vertex().color.b < .125f && point.vertex().color.a < .25f,
                          "Every bed vertex retains water optics");
                }
            }
            assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05,
                  "The bed still covers the complete hex");
        }
    }
}
