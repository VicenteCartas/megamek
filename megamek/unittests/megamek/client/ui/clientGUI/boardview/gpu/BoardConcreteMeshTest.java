/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardConcreteMeshTest {
    @Test
    void terminalRoadsAndTheirNeighborsCoverTheWholeFittedConcreteFootprint() {
        for (var panel : RoadGradeFixture.Panel.values()) {
            var scene = RoadGradeFixture.scene(RoadGradeFixture.focused(true, panel, "x-mixed", 0, 0));
            var outline = BoardConcrete.of(scene);
            Set<Coords> owners = new HashSet<>();
            for (int direction : new int[] { 0, 1, 4 }) {
                Coords end = RoadGradeFixture.focusedCenter(0).translated(direction, 2);
                owners.add(end);
                for (int d = 0; d < 6; d++) { owners.add(end.translated(d)); }
            }
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                for (Coords at : owners) {
                    var surface = new BoardSurface(scene, scene.tile(at), lod);
                    assertFittedTop(surface, outline, panel + " " + at + "/" + lod);
                }
            }
        }
    }

    @Test
    void separatePlateausCannotFoldTheirSharedLowerNeighbors() {
        // The first layout has separate contours at one height; the second combines successive height passes.
        for (var rows : List.of(
              List.of("111111111", "111111111", "113111111", "113133311", "131443311",
                    "131443311", "111133111", "111111111", "111111111"),
              List.of("111111111", "111331111", "122331111", "122211111", "122331111",
                    "111114411", "111114411", "111114411", "111111111"))) {
            var scene = scene(at -> at.getX() < 9 ? rows.get(at.getY()).charAt(at.getX()) - '0' : 1, false, 63);
            var outline = BoardConcrete.of(scene);
            assertTrue(scene.tiles().stream().anyMatch(tile -> outline.fit().footprint(tile.coords()) != null),
                  "Safe parts of the plateaus still use fitted boundaries");
            for (var tile : scene.tiles()) {
                for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                    assertFittedTop(new BoardSurface(scene, tile, lod), outline, tile.coords() + "/" + lod);
                }
            }
        }
    }

    private static void assertFittedTop(BoardSurface surface, BoardConcrete outline, String label) {
        var at = surface.tile.coords();
        var center = BoardGeometry.center(at, surface.tile.elevation());
        double expected = 0, actual = 0;
        for (int e = 0; e < 6; e++) {
            var a = outline.corner(at, e).sub(center);
            var b = outline.corner(at, (e + 1) % 6).sub(center);
            expected += ((double) a.x * b.y - (double) a.y * b.x) / 2;
            var middle = outline.corner(at, e).lerp(outline.corner(at, (e + 1) % 6), .5f);
            for (float t : new float[] { .5f, .98f }) {
                var p = new Vector3(center).lerp(middle, t);
                float height = BoardSurface.sampleHeight(surface.faces, p.x, p.y, Float.NaN);
                assertTrue(Float.isFinite(height), label + " has no gap inside its fitted edge");
                if (surface.ramps == 0) {
                    assertEquals(center.z, height, .001f, "An ungraded concrete slab stays at its own level");
                }
            }
        }
        for (var face : surface.faces) {
            float area = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z / 2;
            assertTrue(area > 0, label + " concrete top cannot fold: " + face);
            actual += area;
        }
        assertEquals(Math.abs(expected), actual, .1, label + " includes every part of the fitted footprint exactly once");
    }

    @Test
    void commCenterBorderSlopeUsesItsActualFaceNormals() throws Exception {
        BoardScene scene = commCenter();
        for (TerrainLod lod : TerrainLod.values()) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(new Coords(0, 10)), lod);
            var panels = surface.walls(scene, BoardGeometry.floor(scene)).stream()
                  .filter(face -> face.landEdge() == 4).toList();
            assertTrue(panels.size() >= 2, "The two-level concrete drop beside 0112 is present");
            for (var face : panels) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                for (var vertex : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(surface.relief.shade(vertex).normal().epsilonEquals(normal, .0001f),
                          "A concrete face must not inherit its horizontal rim's texture projection: " + lod + " " + face);
                }
            }
        }
    }

    @Test
    void commCenterFittedSlopeKeepsAStraightRimAndConstantWidth() throws Exception {
        BoardScene scene = commCenter();
        BoardConcrete shape = BoardConcrete.of(scene);
        Coords first = new Coords(1, 14), last = new Coords(2, 15);
        Vector3 a = shape.corner(first, 3), direction = shape.corner(last, 5).sub(a).nor();
        float rimDistance = Float.NaN;
        for (Coords at : List.of(new Coords(0, 14), first, last)) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            // At 0115 edge 3 is the board cut and corner 4 keeps its place on the board's edge; the fitted slope begins
            // at corner 5.
            int start = at.getX() == 0 ? 5 : 3;
            for (int k = start; k <= 5; k++) {
                Vector3 rim = surface.relief.seam(k, k, 0).sub(a);
                float distance = direction.x * rim.y - direction.y * rim.x;
                if (Float.isNaN(rimDistance)) { rimDistance = distance; }
                assertEquals(rimDistance, distance, .003f,
                      "The rim above 0216 follows the fitted straight boundary: " + at + " corner " + k);
            }
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                if (face.landEdge() < start || face.landEdge() > 4) { continue; }
                for (var vertex : List.of(face.a(), face.b(), face.c())) {
                    Vector3 offset = new Vector3(vertex).sub(a);
                    float distance = direction.x * offset.y - direction.y * offset.x;
                    float expected = rimDistance * (2 * vertex.z / BoardGeometry.level() - 1);
                    assertEquals(expected, distance, .003f,
                          "The entire concrete slope remains planar across former hex edges at " + at);
                }
            }
        }
    }

    private static BoardScene commCenter() throws Exception {
        return BoardScenes.capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
    }

    @Test
    void commCenterCliffToSlopeJunctionsDoNotFoldUnderTheirRims() throws Exception {
        BoardScene scene = commCenter();
        for (Coords at : List.of(new Coords(6, 2), new Coords(8, 13))) {
            for (TerrainLod lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                assertTrue(walls.size() >= 2);
                for (var face : walls) {
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    assertTrue(normal.z >= -.001f,
                          "The poured join must not fold back underneath the slab at " + at + ", " + lod + ": " + face);
                }
            }
        }
    }

    @Test
    void commCenterSlopeEndsInFlatCutFaces() throws Exception {
        BoardScene scene = commCenter();
        for (TerrainLod lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, scene.tile(new Coords(0, 14)), lod);
            List<Vector3> normals = new ArrayList<>();
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                if (face.landEdge() != 3 || Math.min(face.a().z, Math.min(face.b().z, face.c().z)) < -.001f) { continue; }
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                if (normals.stream().noneMatch(other -> other.epsilonEquals(normal, .001f))) { normals.add(normal); }
            }
            assertTrue(!normals.isEmpty() && normals.size() <= 2,
                  "The slope ends in a flat cut or two sharp facets, without a fan of bent strips: " + lod + " " + normals);
        }
    }

    @Test
    void wallTextureFrameStaysFixedAcrossConcreteAndEarthCutFacets() throws Exception {
        BoardScene scene = commCenter();
        var encode = GpuTerrain.class.getDeclaredMethod("sculptVertex", Vector3.class, BoardRelief.Shade.class,
              float.class, BoardSurface.class);
        encode.setAccessible(true);
        for (Coords at : List.of(new Coords(0, 10), new Coords(0, 2))) {
            var surface = new BoardSurface(scene, scene.tile(at));
            Map<Integer, Float> projections = new java.util.HashMap<>();
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                if (scene.tile(at.translated(BoardGeometry.edgeDirection(face.landEdge()))) != null) { continue; }
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    var shade = surface.relief.shade(p);
                    assertTrue(Float.isFinite(shade.projection()), "The cut has an explicit texture frame");
                    Float previous = projections.putIfAbsent(face.landEdge(), shade.projection());
                    if (previous != null) { assertEquals(previous, shade.projection(), .0001f); }
                    var vertex = (com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo)
                          encode.invoke(null, p, shade, Float.NaN, surface);
                    int packed = Float.floatToRawIntBits(vertex.color.toFloatBits());
                    float kind = (packed >>> 16 & 255) / 255f;
                    assertTrue(kind >= .375f && kind < .475f, "The shader uses the fixed wall projection");
                    float angle = ((packed >>> 24 & 255) / 254f - .5f) * 2 * (float) Math.PI;
                    assertEquals(shade.projection(), angle, .05f, "The packed frame survives the GPU vertex format");
                }
            }
            assertTrue(!projections.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 6 })
    void markedCliffsStayPlanarForTheirFullHeightAtEveryDetail(int levels) {
        BoardScene scene = scene(at -> at.getX() >= 8 ? levels : 0, false, 63);
        BoardConcrete outline = BoardConcrete.of(scene);
        for (TerrainLod lod : TerrainLod.values()) {
            for (Coords at : List.of(new Coords(2, 2), new Coords(7, 4), new Coords(8, 4))) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at), lod);
                assertEquals(6, surface.faces.size(), "One center fan, including across chunk boundaries: " + lod);
                float area = 0;
                Vector3 center = BoardGeometry.center(at, scene.tile(at).elevation());
                for (var face : surface.faces) {
                    Vector3 cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(cross.z > 0, "Slab faces point up");
                    area += cross.z / 2;
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(center.z, p.z, .001f);
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(Vector3.Z, .0001f),
                              "Planar tops keep a vertical normal");
                    }
                }
                assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75f, area, .01f);
                for (int e = 0; e < 6; e++) {
                    for (float t : new float[] { .1f, .5f, .99f }) {
                        Vector3 p = new Vector3(center).lerp(outline.corner(at, e).add(0, 0, center.z), t);
                        assertEquals(center.z, surface.height(p.x, p.y), .001f, "Picking/support covers the whole slab");
                    }
                }
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                if (at.getX() < 8) { assertTrue(walls.isEmpty()); continue; }
                assertEquals(4, walls.size(), "Two exposed rectangular panels: " + lod);
                float wallArea = 0, expectedWallArea = 0;
                for (int e = 0; e < 6; e++) {
                    var neighbor = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
                    if (neighbor != null && neighbor.elevation() < scene.tile(at).elevation()) {
                        expectedWallArea += outline.corner(at, e).dst(outline.corner(at, (e + 1) % 6))
                              * (scene.tile(at).elevation() - neighbor.elevation()) * BoardGeometry.level();
                    }
                }
                for (var face : walls) {
                    Vector3 cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    wallArea += cross.len() / 2;
                    assertTrue(cross.dot(new Vector3(face.a()).sub(center)) > 0, "Panels face out");
                    Vector3 a = outline.corner(at, face.landEdge());
                    Vector3 edge = outline.corner(at, (face.landEdge() + 1) % 6).sub(a);
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(0, (edge.x * (p.y - a.y) - edge.y * (p.x - a.x)) / edge.len(), .001f,
                              "The full-height panel stays on its fitted vertical plane");
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(new Vector3(cross).nor(), .0001f));
                        assertEquals(p.z / BoardRelief.metres(1), surface.relief.shade(p).rim(), .001f);
                        assertEquals((center.z - p.z) / BoardRelief.metres(1), surface.relief.shade(p).foot(), .001f);
                    }
                }
                assertEquals(expectedWallArea, wallArea, .01f, "Fitted panels have no holes or overlaps");
            }
        }
    }

    @Test
    void naturalNeighborsMeetTheStraightSlabAcrossMixedDetailChunks() {
        BoardScene scene = scene(at -> 0, true);
        Coords at = new Coords(7, 4);
        BoardSurface concrete = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
        assertEquals(6, concrete.faces.size(), "Adjacent soil must not subdivide the slab");
        int compared = 0;
        for (int e = 0; e < 6; e++) {
            Coords other = at.translated(BoardGeometry.edgeDirection(e));
            if (other.getX() != 8) { continue; }
            BoardSurface natural = new BoardSurface(scene, scene.tile(other), TerrainLod.FULL);
            Set<Vector3> own = vertices(concrete.faces), adjacent = vertices(natural.faces);
            for (int i = 0; i <= 1; i++) {
                Vector3 p = concrete.relief.seam(e, e, i);
                p.z = concrete.relief.groundHeight(p.x, p.y);
                assertTrue(own.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Concrete keeps the straight edge");
                assertTrue(adjacent.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Both chunk details agree");
                compared++;
            }
            for (int i = 1; i < 10; i++) {
                Vector3 p = concrete.relief.seam(e, e, i / 10f);
                assertEquals(0, concrete.height(p.x, p.y), .001f);
                assertEquals(0, natural.height(p.x, p.y), .001f, "Natural ground meets the whole straight seam");
            }
        }
        assertTrue(compared >= 4, "Exercise both shared edges, including their endpoints");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void unmarkedConcreteStepsHaveSharpPlanarSlopesAndMatchingPicking(int levels) {
        BoardScenes.withTransitions(true, () -> {
            BoardScene scene = scene(at -> at.getX() >= 8 ? levels : 0, false);
            Coords at = new Coords(8, 4);
            for (TerrainLod lod : TerrainLod.values()) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at), lod);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                assertEquals(4, walls.size(), "Two planar slopes need two triangles each: " + lod);
                for (var face : walls) {
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    assertTrue(normal.z > .1f && normal.z < .95f, "The side slopes between the two levels");
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(surface.relief.shade(p).normal().epsilonEquals(normal, .0001f), "Slopes have sharp arrises");
                    }
                    Vector3 middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    Ray ray = new Ray(new Vector3(middle.x, middle.y, 500), new Vector3(0, 0, -1));
                    BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray);
                    assertNotNull(hit);
                    assertEquals(middle.z, 500 - Math.sqrt(hit.distance()), .002, "Picking follows the drawn slope");
                }
            }
        });
    }

    private static Set<Vector3> vertices(List<BoardSurface.Face> faces) {
        Set<Vector3> vertices = new HashSet<>();
        for (var face : faces) { vertices.addAll(List.of(face.a(), face.b(), face.c())); }
        return vertices;
    }

    @Test
    void concreteSlopesStayClosedAtTheBoardCut() {
        BoardScene scene = scene(at -> at.getX() >= 8 ? 1 : 0, false);
        float floor = BoardGeometry.floor(scene);
        List<BoardSurface.Face> complete = new ArrayList<>();
        for (var tile : scene.tiles()) {
            var surface = new BoardSurface(scene, tile);
            complete.addAll(surface.faces);
            complete.addAll(surface.walls(scene, floor));
        }
        BoardScenes.assertClosed(complete, floor, "Concrete slope at the board cut");
    }

    private static BoardScene scene(ToIntFunction<Coords> elevation, boolean natural) {
        return scene(elevation, natural, 0);
    }

    private static BoardScene scene(ToIntFunction<Coords> elevation, boolean natural, int cliffs) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 11; x++) {
            for (int y = 0; y < 9; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(elevation.applyAsInt(at));
                if (!natural || x < 8) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, cliffs));
                var art = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(hex, art, null, new BoardScene.PixelPool(), neighbour -> null));
            }
        }
        return new BoardScene(0, 11, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void tallConcreteCliffsRemainFlatDownToTheirFeet() {
        float m = BoardRelief.metres(1);
        for (int level : new int[] { 3, 6 }) {
            BoardScene scene = BoardScenes.reliefScene(level, true, BoardScene.Surface.CONCRETE);
            BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.RELIEF_CENTER));
            Vector3 center = BoardGeometry.center(BoardScenes.RELIEF_CENTER, level);
            int samples = 0;
            for (BoardSurface.Face face : surface.walls(scene, BoardGeometry.floor(scene))) {
                // A rectangular slab needs only corner vertices; also measure inside its triangles.
                Vector3 middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
                for (Vector3 p : List.of(face.a(), face.b(), face.c(), middle)) {
                    // Distance beyond the nearest logical edge; the rounded corners are skipped.
                    float beyond = -Float.MAX_VALUE, along = 0;
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(BoardScenes.RELIEF_CENTER, level, edge);
                        Vector3 b = BoardGeometry.corner(BoardScenes.RELIEF_CENTER, level, edge + 1);
                        float ex = b.x - a.x, ey = b.y - a.y, length = (float) Math.hypot(ex, ey);
                        float nx = ey / length, ny = -ex / length;
                        if (nx * (a.x - center.x) + ny * (a.y - center.y) < 0) {
                            nx = -nx;
                            ny = -ny;
                        }
                        float distance = (p.x - a.x) * nx + (p.y - a.y) * ny;
                        if (distance > beyond) {
                            beyond = distance;
                            along = ((p.x - a.x) * ex + (p.y - a.y) * ey) / (length * length);
                        }
                    }
                    if (along < .15f || along > .85f) { continue; }
                    assertEquals(0, beyond, .01f * m, "Cast concrete faces are flat, at " + p);
                    samples++;
                }
            }
            assertTrue(samples > 0, "Every face was measured");
        }
    }

    @Test
    void commCenterBoardCutNormalsStaySeparateFromTheRoadAbove() throws Exception {
        BoardScene scene = BoardScenes.capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
        for (Coords at : List.of(new Coords(0, 2), new Coords(0, 3))) {
            for (TerrainLod lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                int checked = 0;
                for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                    if (scene.tile(at.translated(BoardGeometry.edgeDirection(face.landEdge()))) != null) { continue; }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                        // A sliver's own normal rounds off by about .0001; a road's normal differs by far more.
                        assertTrue(surface.relief.shade(point).normal().epsilonEquals(normal, .002f),
                              "The map cut must not inherit upward road normals at " + at + ", " + lod + ": " + point);
                    }
                    checked++;
                }
                assertTrue(checked > 0, "Exercise the exposed board cut beside the road");
            }
        }
    }

    @Test
    void commCenterBoardCutsMeetEverySlope() throws Exception {
        BoardScene scene = BoardScenes.capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        for (TerrainLod lod : TerrainLod.values()) {
            Map<BoardScenes.Segment, Integer> joined = new HashMap<>();
            Map<String, List<BoardSurface.Face>> cuts = new HashMap<>();
            for (var tile : scene.tiles()) {
                Coords at = tile.coords();
                if (at.getX() > 1 && at.getX() < scene.width() - 2 && at.getY() > 1 && at.getY() < scene.height() - 2) { continue; }
                var surface = new BoardSurface(scene, tile, lod);
                BoardScenes.countEdges(joined, surface.faces);
                var walls = surface.walls(scene, floor);
                BoardScenes.countEdges(joined, walls);
                if (!tile.liquid().present()) {
                    for (int edge = 0; edge < 6; edge++) {
                        if (scene.tile(at.translated(BoardGeometry.edgeDirection(edge))) != null) { continue; }
                        int cut = edge;
                        var panels = walls.stream().filter(face -> face.landEdge() == cut).toList();
                        Vector3 outward = BoardGeometry.corner(at, 0, edge).add(BoardGeometry.corner(at, 0, edge + 1))
                              .scl(.5f).sub(BoardGeometry.center(at, 0)).nor();
                        for (var face : panels) {
                            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                            assertTrue(normal.dot(outward) > 0,
                                  "The board cut must not fold inward at " + at + ", edge " + edge + ", " + lod);
                        }
                        cuts.put(at + " edge " + edge, panels);
                    }
                }
            }
            long bottom = Math.round(floor * 1000);
            assertFalse(cuts.isEmpty());
            for (var cut : cuts.entrySet()) {
                Map<BoardScenes.Segment, Integer> boundary = new HashMap<>();
                BoardScenes.countEdges(boundary, cut.getValue());
                boundary.keySet().removeIf(edge -> edge.a().z() == bottom && edge.b().z() == bottom);
                BoardScenes.assertJoined(boundary, joined, "CommCenter map-edge slope at " + cut.getKey() + ", " + lod);
            }
        }
    }
}
