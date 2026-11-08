/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardWaterfallTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void collapsedFreeBankLipKeepsFiniteSheetNormals(TerrainLod lod) {
        int collapsed = 0, visible = 0;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = mesa(direction);
            var surface = new BoardSurface(scene, scene.tile(new Coords(3, 3)), lod);
            var drop = surface.waterfalls.getFirst();
            Vector3[][] grid = GpuWaterfall.grid(surface, drop);
            int columns = grid.length - 1, rows = grid[0].length;
            List<Vector3> normals = new ArrayList<>();
            MeshPartBuilder mesh = mock(MeshPartBuilder.class);
            when(mesh.vertex(any(MeshPartBuilder.VertexInfo.class))).thenAnswer(call -> {
                MeshPartBuilder.VertexInfo vertex = call.getArgument(0);
                assertTrue(Float.isFinite(vertex.normal.x) && Float.isFinite(vertex.normal.y)
                      && Float.isFinite(vertex.normal.z), "Every sheet normal must be finite");
                assertEquals(1, vertex.normal.len2(), .00001,
                      "Zero CPU normals become undefined when normalized by the vertex shader");
                normals.add(new Vector3(vertex.normal));
                return (short) (normals.size() - 1);
            });
            GpuWaterfall.sheet(mesh, surface, drop, null);
            assertEquals(grid.length * rows, normals.size());
            for (int column : new int[] { 0, columns }) {
                assertEquals(grid[column][0], grid[column][1], "Exercise the collapsed free-bank lip");
                collapsed++;
                // Although one triangle collapses, the adjacent column's lip still leaves a visible triangle
                // touching this vertex. Its winding must agree with the limiting crest normal.
                int neighbor = column == 0 ? 1 : columns - 1;
                Vector3 a = grid[neighbor][column == 0 ? 1 : 0];
                Vector3 b = grid[neighbor][column == 0 ? 0 : 1];
                Vector3 face = new Vector3(b).sub(a).crs(new Vector3(grid[column][0]).sub(a));
                if (!face.isZero()) {
                    assertTrue(face.dot(normals.get(column * rows)) > 0,
                          "The repaired anchor must face the same way as its nondegenerate sheet triangle");
                    visible++;
                }
            }
        }
        assertEquals(12, collapsed, "Both free banks in every board direction must be covered");
        assertTrue(visible > 0, "Collapsed lip anchors must participate in visible geometry");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void domeVentWaterfallWallsAreClosedForLavaAndWater(TerrainLod lod) {
        BoardScene lava = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        for (BoardScene scene : List.of(lava, withWater(lava))) {
            for (boolean transitions : new boolean[] { false, true }) {
                BoardScenes.withTransitions(transitions, () -> checkDomeVentCliffEnds(scene, lod));
            }
        }
    }

    /** Exercise ordinary water with exactly the same drops, adjoining cliffs and connected mouths as the lava map. */
    static BoardScene withWater(BoardScene scene) {
        var tiles = scene.tiles().stream().map(t -> !t.liquid().molten() ? t
              : new BoardScene.Tile(t.coords(), t.elevation(), 1, t.frozen(), t.roadExits(), t.surface(), t.decals(),
                    t.decalsWithoutLimbs(), t.features(), t.text(),
                    BoardLiquid.WATER, t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable())).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void checkDomeVentCliffEnds(BoardScene scene, TerrainLod lod) {
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        for (var tile : scene.tiles()) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
        float floor = BoardGeometry.floor(scene);
        int checked = 0;
        for (var surface : surfaces.values()) {
            for (var fall : surface.waterfalls) {
                int edge = fall.edge();
                var walls = surface.walls(scene, floor, surfaces);
                var cliff = walls.stream().filter(f -> f.landEdge() == edge).toList();
                List<BoardSurface.Face> joined = new ArrayList<>(surface.groundFaces());
                joined.addAll(walls.stream().filter(f -> f.landEdge() != edge).toList());
                for (int direction = 0; direction < 6; direction++) {
                    BoardSurface neighbor = surfaces.get(surface.tile.coords().translated(direction));
                    if (neighbor == null) { continue; }
                    joined.addAll(neighbor.groundFaces());
                    joined.addAll(neighbor.walls(scene, floor, surfaces));
                }
                joined.removeIf(f -> f.finish() == BoardSurface.Finish.OUTCROP || f.finish() == BoardSurface.Finish.DRESSING);
                for (var entry : segments(cliff).entrySet()) {
                    Segment segment = entry.getKey();
                    if (entry.getValue() != 1 || Math.abs(segment.a().z - segment.b().z) < .001f) { continue; }
                    Vector3 p = new Vector3(segment.a()).lerp(segment.b(), .5f);
                    if (p.z <= fall.lowA() + .1f * BoardGeometry.level()
                          || p.z >= BoardGeometry.waterZ(surface.tile) - .1f * BoardGeometry.level()) { continue; }
                    assertTrue(onSurface(p, joined), () -> "Open waterfall wall at " + surface.tile.coords()
                          + ", edge " + edge + ", " + surface.tile.liquid().kind() + ", " + lod
                          + ", transitions " + BoardGeometry.tuning().transitions() + ": " + p);
                    checked++;
                }
            }
        }
        assertTrue(checked > 10, "Exercise the exposed cliff joins behind the Dome Vent falls");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void waterfallEndsShareTheAdjoiningCliffShapeAndShading(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = mesa(direction);
            var surface = new BoardSurface(scene, scene.tile(new Coords(3, 3)), lod);
            var walls = surface.walls(scene, BoardGeometry.floor(scene));
            int edge = surface.waterfalls.getFirst().edge();
            var fall = walls.stream().filter(f -> f.landEdge() == edge).toList();
            Map<Vector3, BoardRelief.Shade> fallShades = new HashMap<>();
            for (var face : fall) {
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    fallShades.put(p, surface.relief.shade(p));
                }
            }
            for (int end = 0; end < 2; end++) {
                int adjacent = Math.floorMod(edge + (end == 0 ? -1 : 1), 6);
                Vector3 corner = BoardGeometry.corner(surface.tile.coords(), 0, edge + end);
                var cliff = walls.stream().filter(f -> f.landEdge() == adjacent).toList();
                Map<Segment, Integer> counts = segments(cliff);
                int shared = 0;
                float rounding = 0;
                for (var segment : counts.entrySet()) {
                    if (segment.getValue() != 1) { continue; }
                    for (Vector3 p : List.of(segment.getKey().a(), segment.getKey().b())) {
                        float distance = (float) Math.hypot(p.x - corner.x, p.y - corner.y);
                        if (distance > BoardGeometry.WIDTH * .2f || p.z < .1f * BoardGeometry.LEVEL
                              || p.z >= 5 * BoardGeometry.LEVEL) { continue; }
                        Vector3 joined = fallShades.keySet().stream().filter(q -> q.epsilonEquals(p, .002f))
                              .findFirst().orElse(null);
                        assertTrue(joined != null, "The fall must share the cliff's corner rows: " + p + ", " + lod);
                        assertTrue(fallShades.get(joined).normal().dot(surface.relief.shade(p).normal()) > .995f,
                              "The waterfall join must not have a lighting crease: " + p + ", " + lod);
                        assertEquals(surface.relief.shade(p).level(), fallShades.get(joined).level(), .0001f,
                              "Rock must blend into the adjoining bank material: " + p + ", " + lod);
                        assertEquals(surface.relief.shade(p).occlusion(), fallShades.get(joined).occlusion(), .0001f,
                              "The shared corner must not have an ambient lighting seam: " + p + ", " + lod);
                        rounding = Math.max(rounding, distance);
                        shared++;
                    }
                }
                assertTrue(shared > 2, "Both ends must exercise the common cliff boundary");
                assertTrue(rounding > BoardRelief.metres(.1f), "The side must round into the cliff instead of a pillar");
                // Below the dry bank's level, the same corner belongs to that bank's wall into the lower pool.
                Coords bank = surface.tile.coords().translated(BoardGeometry.edgeDirection(adjacent));
                Coords pool = surface.tile.coords().translated(direction);
                var bankSurface = new BoardSurface(scene, scene.tile(bank), lod);
                var lowerCliff = bankSurface.walls(scene, BoardGeometry.floor(scene)).stream()
                      .filter(f -> f.landEdge() >= 0 && bank.translated(BoardGeometry.edgeDirection(f.landEdge())).equals(pool))
                      .toList();
                int lowerShared = 0;
                for (var segment : segments(lowerCliff).entrySet()) {
                    if (segment.getValue() != 1 || Math.abs(segment.getKey().a().z - segment.getKey().b().z) < .001f) { continue; }
                    for (Vector3 p : List.of(segment.getKey().a(), segment.getKey().b())) {
                        if (p.z <= -BoardGeometry.LEVEL || p.z >= 0
                              || Math.hypot(p.x - corner.x, p.y - corner.y) > BoardGeometry.WIDTH * .2f) { continue; }
                        assertTrue(fallShades.keySet().stream().anyMatch(q -> q.epsilonEquals(p, .002f)),
                              "The lower three-way cliff join must stay closed: " + p + ", " + lod);
                        lowerShared++;
                    }
                }
                assertTrue(lowerShared > 2, "Exercise the side join immediately above the receiving pool");
            }
        }
    }

    private record Segment(Vector3 a, Vector3 b) { }

    private static Map<Segment, Integer> segments(List<BoardSurface.Face> faces) {
        Map<Segment, Integer> counts = new HashMap<>();
        for (var face : faces) {
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                var a = points[i];
                var b = points[(i + 1) % 3];
                var reverse = new Segment(b, a);
                counts.merge(counts.containsKey(reverse) ? reverse : new Segment(a, b), 1, Integer::sum);
            }
        }
        return counts;
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void waterfallRockAndWaterMeetTheReceivingBasin(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            for (int depth : new int[] { 1, 3 }) {
                BoardScene scene = mesa(direction, depth);
                Coords high = new Coords(3, 3), low = high.translated(direction);
                var upper = new BoardSurface(scene, scene.tile(high), lod);
                var lower = new BoardSurface(scene, scene.tile(low), lod);
                int edge = upper.waterfalls.getFirst().edge(), receivingEdge = (edge + 3) % 6;
                float contact = lower.tile.elevation() * BoardGeometry.level();
                var cliff = upper.walls(scene, BoardGeometry.floor(scene), Map.of(low, lower)).stream()
                      .filter(f -> f.landEdge() == edge).toList();
                var submerged = lower.groundFaces().stream()
                      .filter(f -> f.finish() == BoardSurface.Finish.WALL && f.landEdge() == receivingEdge).toList();
                assertTrue(!submerged.isEmpty(), "The basin must close the rock down to its bed");
                Map<Vector3, Vector3> normals = new HashMap<>();
                for (var face : cliff) {
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(p.z >= contact - .001f, "The upper wall must not project a separate fin into the basin");
                        if (Math.abs(p.z - contact) < .001f) {
                            assertTrue(onSurface(p, submerged), "The cliff's foot must meet the basin: " + p);
                        }
                        if (p.z < 2 * BoardGeometry.level() || p.z > 4 * BoardGeometry.level()) { continue; }
                        Vector3 normal = upper.relief.shade(p).normal();
                        Vector3 previous = normals.putIfAbsent(p, normal);
                        assertTrue(previous == null || previous.dot(normal) > .999f,
                              "A continuous rock face must not have separate strip normals: " + p);
                    }
                }
                var boundary = lower.waterBoundary(receivingEdge);
                for (int i = 0; i + 1 < boundary.size(); i++) {
                    for (float t : new float[] { .2f, .5f, .8f }) {
                        Vector3 p = new Vector3(boundary.get(i)).lerp(boundary.get(i + 1), t);
                        assertEquals(BoardGeometry.waterZ(lower.tile), p.z, .001f);
                        assertTrue(onSurface(p, submerged), "The waterline must follow the actual rock triangles: " + p);
                    }
                }
            }
        }
    }

    private static boolean onSurface(Vector3 point, List<BoardSurface.Face> faces) {
        Vector3 hit = new Vector3();
        for (var face : faces) {
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Vector3 a = vertices[i], b = vertices[(i + 1) % 3], edge = new Vector3(b).sub(a);
                float t = edge.len2() == 0 ? 0 : Math.clamp(new Vector3(point).sub(a).dot(edge) / edge.len2(), 0, 1);
                if (new Vector3(a).mulAdd(edge, t).epsilonEquals(point, .002f)) { return true; }
            }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            Ray ray = new Ray(new Vector3(point).mulAdd(normal, 1), new Vector3(normal).scl(-1));
            if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit) && hit.epsilonEquals(point, .002f)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void fallingWaterClearsTheRockLip(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = mesa(direction);
            var upper = new BoardSurface(scene, scene.tile(new Coords(3, 3)), lod);
            var solid = new ArrayList<>(upper.groundFaces().stream()
                  .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING).toList());
            solid.addAll(upper.walls(scene, BoardGeometry.floor(scene)));
            Vector3[][] sheet = GpuWaterfall.grid(upper, upper.waterfalls.getFirst());
            // Exercise the actual triangles as the sheet turns over the ledge, inside its frayed free sides.
            // Vertex-only checks miss the rock cutting through between consecutive water rows.
            for (int column = 3; column + 3 < sheet.length; column++) {
                for (int row = 0; row < 4; row++) {
                    assertAboveRock(sheet[column][row], sheet[column][row + 1], sheet[column + 1][row + 1], solid);
                    assertAboveRock(sheet[column + 1][row + 1], sheet[column + 1][row], sheet[column][row], solid);
                }
            }
        }
    }

    private static void assertAboveRock(Vector3 a, Vector3 b, Vector3 c, List<BoardSurface.Face> rock) {
        Vector3 hit = new Vector3();
        for (int i = 1; i < 10; i += 2) {
            for (int j = 1; i + j < 10; j += 2) {
                Vector3 p = new Vector3(a).scl(1 - (i + j) / 10f).mulAdd(b, i / 10f).mulAdd(c, j / 10f);
                Ray ray = new Ray(new Vector3(p).add(0, 0, .002f), Vector3.Z);
                for (var face : rock) {
                    assertTrue(!Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit),
                          () -> "The rock lip must stay below the falling water: water " + p + ", rock " + hit);
                }
            }
        }
    }

    /** A raised pool above land on five sides, with a six-level waterfall on the remaining side. */
    private static BoardScene mesa(int direction) {
        return mesa(direction, 1);
    }

    private static BoardScene mesa(int direction, int depth) {
        Coords high = new Coords(3, 3), low = high.translated(direction);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean water = coords.equals(high) || coords.equals(low);
                tiles.add(tile(coords, coords.equals(high) ? 5 : coords.equals(low) ? -1 : 0,
                      coords.equals(low) ? depth : water ? 1 : -1));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void rockBehindTheCurtainBlocksRaysFromObliqueAngles() {
        var original = BoardRelief.tuning();
        try {
            for (int direction = 0; direction < 6; direction++) {
                BoardScene scene = scene(direction, 1);
                BoardSurface upper = new BoardSurface(scene, scene.tile(new Coords(3, 3)));
                for (var face : upper.waterFaces) {
                    // Collinear mouth samples can leave subpixel ears through float rounding.
                    assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z
                                >= -.001f * BoardGeometry.HEX_SCALE * BoardGeometry.HEX_SCALE,
                          "The upper pool must not fold over where its lip meets a bank: direction " + direction);
                }
                var fall = upper.waterfalls.getFirst();
                var crest = upper.crest(fall);
                List<BoardSurface.Face> solid = new ArrayList<>(upper.walls(scene, BoardGeometry.floor(scene)));
                solid.addAll(upper.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList());
                // Avoid casting precisely along a triangle edge, where float ray tests can miss both faces.
                for (int column = 1; column < 23; column++) {
                    float s = column / 23f;
                    for (int row = 1; row < 13; row++) {
                        Vector3 p = crest.point(s);
                        p.z = fall.lowA() + (p.z - fall.lowA()) * row / 13;
                        for (float angle : new float[] { -.7f, 0, .7f }) {
                            Vector3 outward = crest.normal(s).mulAdd(crest.tangent(s), angle).nor();
                            float reach = BoardGeometry.WIDTH * .4f;
                            Ray ray = new Ray(new Vector3(p).mulAdd(outward, reach), new Vector3(outward).scl(-1));
                            Vector3 hit = new Vector3();
                            assertTrue(solid.stream().anyMatch(f -> Intersector.intersectRayTriangle(ray,
                                        f.a(), f.b(), f.c(), hit) && hit.dst(ray.origin) < 2 * reach
                                        && new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a()))
                                              .dot(ray.direction) < 0),
                                  "Open cliff: direction " + direction + ", at " + p + ", angle " + angle);
                        }
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void waterfallRocksKeepFoundationsAtEveryWaterDepth() {
        var original = BoardRelief.tuning();
        try {
            for (int depth : new int[] { 0, 1, 3 }) {
                BoardScene scene = scene(3, depth);
                for (Coords coords : List.of(new Coords(3, 3), new Coords(3, 4))) {
                    BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                    List<BoardSurface.Face> ground = surface.faces.stream()
                          .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING).toList();
                    // Every placed rock carries its own deterministic tint on all its faces.
                    Map<Float, List<Vector3>> rocks = new HashMap<>();
                    for (var face : surface.faces) {
                        if (face.finish() == BoardSurface.Finish.OUTCROP
                              && surface.relief.shade(face.a()).kind() == BoardRelief.Kind.ROCK) {
                            rocks.computeIfAbsent(surface.relief.shade(face.a()).tint(), key -> new ArrayList<>())
                                  .addAll(List.of(face.a(), face.b(), face.c()));
                        }
                    }
                    assertTrue(!rocks.isEmpty(), "Geological lip/pool rocks remain at depth " + depth);
                    for (var rock : rocks.values()) {
                        assertTrue(rock.stream().anyMatch(p -> p.z < BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN)),
                              "A rock must enter the terrain, including the depth-" + depth + " bed at " + coords);
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void terrainSlopeIncludesWaterDepthWhileSurfaceHeightStaysTheSame() {
        BoardScenes.withTransitions(true, () -> {
            Coords water = new Coords(3, 3), land = water.translated(0);
            for (int depth : new int[] { 1, 2, 3 }) {
                BoardScene scene = scene(3, depth);
                List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                tiles.replaceAll(t -> t.coords().equals(water) ? tile(water, 0, depth)
                      : t.coords().equals(land) ? tile(land, 1, -1) : t);
                scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(water));
                assertEquals(depth == 1, surface.relief.slope(1), "Land is one surface level above water; bed depth adds to its drop");
                Vector3 center = BoardGeometry.center(water, 0);
                assertEquals(-depth * BoardGeometry.LEVEL, surface.height(center.x, center.y), .001f);
                assertEquals(BoardGeometry.waterZ(scene.tile(water)), surface.waterHeight(center.x, center.y), .001f);
            }
        });
    }

    private static BoardScene scene(int direction, int depth) {
        Coords high = new Coords(3, 3), low = high.translated(direction);
        Vector3 a = BoardGeometry.center(high, 0), b = BoardGeometry.center(low, 0);
        Vector3 middle = new Vector3(a).lerp(b, .5f), toward = new Vector3(b).sub(a);
        List<Coords> river = List.of(high.translated((direction + 3) % 6), high, low, low.translated(direction));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int level = new Vector3(BoardGeometry.center(coords, 0)).sub(middle).dot(toward) < 0 ? 3 : 0;
                tiles.add(tile(coords, level, river.contains(coords) ? depth : -1));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile tile(Coords coords, int level, int depth) {
        return new BoardScene.Tile(coords, level, depth, false, 0, BoardScene.Surface.SAND, null, null, List.of(),
              List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true);
    }

    @Test
    void waterfallsJoinTheUpperAndLowerWaterSurfacesOnlyOnceAndArePickable() {
        Coords high = new Coords(2, 2);
        for (int direction = 0; direction < 6; direction++) {
            Coords low = high.translated(direction);
            BoardScene scene = BoardScenes.riverScene(Map.of(high, 3, low, 0), false);
            BoardSurface upper = new BoardSurface(scene, scene.tile(high));
            BoardSurface lower = new BoardSurface(scene, scene.tile(low));
            assertEquals(1, upper.waterfalls.size());
            assertTrue(lower.waterfalls.isEmpty());
            BoardSurface.Side fall = upper.waterfalls.getFirst();
            assertEquals(BoardGeometry.waterZ(scene.tile(high)), fall.a().z, 0.001f);
            assertEquals(BoardGeometry.waterZ(scene.tile(low)), fall.lowA(), 0.001f);
            Vector3 point = new Vector3(fall.a()).lerp(fall.b(), 0.5f).add(0, 0, -2);
            Vector3 outward = new Vector3(fall.b()).sub(fall.a()).crs(Vector3.Z).nor();
            assertEquals(high, BoardGeometry.pick(scene, new Ray(new Vector3(point).mulAdd(outward, 6), outward.scl(-1))));
        }
        // Frozen falls keep their curtain and picking: BoardFrozenWaterfallTest.
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void wallsBesideFallsMeetTheGradedBank(boolean frozen) {
        Coords high = new Coords(2, 2);
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = BoardScenes.riverScene(Map.of(high, 3, high.translated(direction), 0), frozen);
            BoardSurface upper = new BoardSurface(scene, scene.tile(high));
            int edge = upper.waterfalls.getFirst().edge();
            int checked = 0;
            for (BoardSurface.Side side : upper.sides(scene, BoardGeometry.floor(scene))) {
                if (side.edge() != edge) { continue; }
                Vector3 middle = new Vector3(side.a()).lerp(side.b(), .5f);
                float bank = Float.NEGATIVE_INFINITY;
                for (BoardSurface.Face face : upper.faces) {
                    if (face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE) {
                        bank = Math.max(bank, face.height(middle.x, middle.y));
                    }
                }
                if (!Float.isFinite(bank)) { continue; } // The crest's submerged wall has no bank above it.
                assertEquals(bank, middle.z, .03f,
                      "The wall beside fall " + direction + " must close at the rendered bank: " + middle);
                checked++;
            }
            assertTrue(checked > 0, "The fixture must contain bank walls beside the fall");
        }
    }

    @Test
    void fallsThatShareACornerTurnItTogetherWithoutAGap() {
        Coords high = new Coords(3, 3);
        // One pool falling over two neighbouring edges, then two pools side by side falling into one pool.
        for (boolean twoPools : List.of(false, true)) {
            Map<Coords, Integer> levels = new HashMap<>();
            levels.put(high, 4);
            if (twoPools) {
                levels.put(high.translated(1), 4);
                levels.put(high.translated(2), 1);
            } else {
                levels.put(high.translated(2), 1);
                levels.put(high.translated(3), 1);
            }
            BoardScene scene = BoardScenes.riverScene(levels, false);
            List<BoardSurface> pools = new ArrayList<>(List.of(new BoardSurface(scene, scene.tile(high))));
            if (twoPools) { pools.add(new BoardSurface(scene, scene.tile(high.translated(1)))); }
            List<Vector3[][]> sheets = new ArrayList<>();
            List<BoardSurface> owners = new ArrayList<>();
            for (BoardSurface pool : pools) {
                for (BoardSurface.Side fall : pool.waterfalls) {
                    sheets.add(GpuWaterfall.grid(pool, fall));
                    owners.add(pool);
                }
            }
            assertEquals(2, sheets.size(), "Two falls meet at the corner");
            Vector3[] first = null, second = null;
            for (Vector3[] columnA : List.of(sheets.get(0)[0], sheets.get(0)[sheets.get(0).length - 1])) {
                for (Vector3[] columnB : List.of(sheets.get(1)[0], sheets.get(1)[sheets.get(1).length - 1])) {
                    if (columnA[columnA.length - 1].dst(columnB[columnB.length - 1]) < 1) {
                        first = columnA;
                        second = columnB;
                    }
                }
            }
            assertNotNull(first, "The sheets must end at the same corner");
            for (int row = 0; row < first.length; row++) {
                assertTrue(first[row].epsilonEquals(second[row], 0.01f),
                      "Both sheets turn the corner on the same vertices, row " + row + ": " + first[row] + " " + second[row]);
            }
            // Where the water leaves the pool, the sheet starts on the pool's own pulled-back edge.
            Vector3 crest = first[0];
            for (BoardSurface pool : owners) {
                assertTrue(pool.water.stream().anyMatch(point -> point.epsilonEquals(crest, 0.01f)),
                      "Each pool's surface reaches the shared crest corner: " + crest);
            }
        }
    }

    @Test
    void fallsAlongARowCurveRoundEveryCornerAndMeetWithoutAGap() {
        // A lake spilling along a whole row of hexes: its edge zigzags, prows and valleys by turns.
        Map<Coords, Integer> levels = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            levels.put(new Coords(x, 2), 3);
            levels.put(new Coords(x, 3), 0);
            levels.put(new Coords(x, 4), 0);
        }
        BoardScene scene = BoardScenes.riverScene(levels, false);
        float floor = BoardGeometry.floor(scene);
        List<BoardSurface> pools = new ArrayList<>();
        for (int x = 1; x <= 7; x++) { pools.add(new BoardSurface(scene, scene.tile(new Coords(x, 2)))); }
        List<Vector3[]> ends = new ArrayList<>();
        List<Vector3> crestEnds = new ArrayList<>();
        List<BoardSurface.Side> walls = new ArrayList<>();
        int joined = 0, falls = 0;
        for (BoardSurface pool : pools) {
            for (BoardSurface.Face face : pool.faces) {
                if (face.finish() == BoardSurface.Finish.BED) {
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z > 0, "The bed reaching out to a crest never folds at " + pool.tile.coords());
                }
            }
            walls.addAll(pool.sides(scene, floor));
            for (BoardSurface.Side fall : pool.waterfalls) {
                BoardSurface.Crest crest = pool.crest(fall);
                Coords below = pool.tile.coords().translated(BoardGeometry.edgeDirection(fall.edge()));
                Vector3 a = BoardGeometry.corner(pool.tile.coords(), 0, fall.edge());
                Vector3 b = BoardGeometry.corner(pool.tile.coords(), 0, fall.edge() + 1);
                Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
                for (int k = 0; k <= 24; k++) {
                    Vector3 point = crest.point(k / 24f);
                    float out = new Vector3(point).sub(a).dot(outward);
                    assertTrue(out > -0.01f && out < (BoardSurface.VALLEY + .01f) * BoardGeometry.HEX_SCALE,
                          "The crest bows out a little over the pool below, never into its own hex: " + out);
                    Vector3 inward = BoardGeometry.center(below, 0).sub(point.x, point.y, 0).nor().scl(.01f);
                    assertTrue(BoardGeometry.contains(below, point.x + inward.x, point.y + inward.y),
                          "The crest stays over the pool it falls into: " + point);
                }
                Vector3[][] grid = GpuWaterfall.grid(pool, fall);
                ends.add(grid[0]);
                ends.add(grid[grid.length - 1]);
                crestEnds.add(crest.a());
                crestEnds.add(crest.b());
                joined += (pool.fallJoins(fall, true) ? 1 : 0) + (pool.fallJoins(fall, false) ? 1 : 0);
                falls++;
            }
        }
        assertEquals(13, falls, "The row falls over every edge it shares with the lake below");
        assertEquals(2 * (falls - 1), joined, "Every corner along the row carries the fall on into the next");
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                if (ends.get(i)[0].dst(ends.get(j)[0]) > 1) { continue; }
                for (int row = 0; row < ends.get(i).length; row++) {
                    assertTrue(ends.get(i)[row].epsilonEquals(ends.get(j)[row], 0.01f),
                          "Neighbouring sheets turn each corner on the same vertices, row " + row);
                }
            }
        }
        // The walls beneath the crests run on from one fall to the next: every crest end tops one.
        for (Vector3 end : crestEnds) {
            long meeting = walls.stream().filter(side -> side.a().dst(end.x, end.y, side.a().z) < 0.01f
                  || side.b().dst(end.x, end.y, side.b().z) < 0.01f).count();
            assertTrue(meeting >= 1, "A wall stands under the crest at " + end);
        }
    }

    @Test
    void poolsSharingAnEdgeChurnWithTheSameFallsNearIt() {
        Map<Coords, Integer> levels = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            levels.put(new Coords(x, 2), 3);
            levels.put(new Coords(x, 3), 0);
            levels.put(new Coords(x, 4), 0);
        }
        BoardScene scene = BoardScenes.riverScene(levels, false);
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        java.util.function.Function<Coords, BoardSurface> lookup = coords -> {
            BoardScene.Tile tile = scene.tile(coords);
            return tile == null || !tile.liquid().present() ? null
                  : surfaces.computeIfAbsent(coords, key -> new BoardSurface(scene, tile));
        };
        Map<Coords, List<GpuWaterShader.Impact>> impacts = new HashMap<>();
        for (int x = 1; x <= 7; x++) {
            for (int y = 3; y <= 4; y++) {
                Coords coords = new Coords(x, y);
                impacts.put(coords, GpuWaterShader.impacts(scene, scene.tile(coords), lookup));
            }
        }
        assertFalse(impacts.get(new Coords(4, 3)).isEmpty(), "A pool below falls churns");
        for (var entry : impacts.entrySet()) {
            for (int direction = 0; direction < 6; direction++) {
                List<GpuWaterShader.Impact> other = impacts.get(entry.getKey().translated(direction));
                if (other == null) { continue; }
                int edge = Math.floorMod(1 - direction, 6);
                Vector3 a = BoardGeometry.corner(entry.getKey(), 0, edge);
                Vector3 b = BoardGeometry.corner(entry.getKey(), 0, edge + 1);
                for (GpuWaterShader.Impact impact : entry.getValue()) {
                    // The shader's boil reaches at most 2.1 radii from its landing, and behind a curtain a further
                    // 0.085 hex widths; one that can reach the shared edge churns both sides of it, so no seam shows.
                    float reach = 2.1f * impact.radius() + .085f * BoardGeometry.WIDTH, nearest = Float.MAX_VALUE;
                    for (int k = 0; k <= 20; k++) {
                        Vector3 point = new Vector3(a).lerp(b, k / 20f);
                        nearest = Math.min(nearest, BoardScenes.distance(point, impact.from(), impact.to()));
                    }
                    if (nearest < reach) {
                        assertTrue(other.contains(impact), "Both pools churn with a landing near their edge");
                    }
                }
            }
        }
    }
}
