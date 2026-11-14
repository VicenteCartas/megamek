/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardFrozenWaterfallTest {
    private static final Coords HIGH = new Coords(3, 3);

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void frozenAndMixedFallsMeetTheirSlabAndReceivingSurface(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean upperIce : new boolean[] { false, true }) {
                for (boolean lowerIce : new boolean[] { false, true }) {
                    if (!upperIce && !lowerIce) { continue; }
                    BoardScene scene = scene(direction, 3, upperIce, lowerIce);
                    BoardSurface upper = new BoardSurface(scene, scene.tile(HIGH), lod);
                    assertEquals(1, upper.waterfalls.size(), "Every connected level drop retains its curtain");
                    var fall = upper.waterfalls.getFirst();
                    var lower = scene.tile(HIGH.translated(direction));
                    assertEquals(BoardGeometry.surfaceZ(lower), fall.lowA(), .0001f);
                    assertEquals(BoardGeometry.surfaceZ(lower), fall.lowB(), .0001f);
                    Vector3[][] grid = upperIce ? GpuFrozenWaterfall.grid(upper, fall, lod)
                          : GpuWaterfall.grid(upper, fall);
                    for (var column : grid) {
                        assertEquals(BoardGeometry.surfaceZ(upper.tile), column[0].z, .0001f);
                        assertEquals(upperIce ? BoardGeometry.surfaceZ(lower) : BoardGeometry.waterZ(lower),
                              column[column.length - 1].z, .0001f);
                        for (Vector3 point : column) {
                            assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y) && Float.isFinite(point.z));
                        }
                        if (upperIce) {
                            assertEquals(column[0].z,
                                  iceHeight(upper.iceSlab, column[0]), .0001f,
                                  "The frozen curtain must meet the drawn slab without a recessed shore gap");
                        }
                    }
                    if (upperIce) {
                        assertTrue(upper.waterFaces.isEmpty());
                        for (var face : upper.faces) {
                            if (face.finish() != BoardSurface.Finish.ICE) { continue; }
                            for (var point : List.of(face.a(), face.b(), face.c())) {
                                assertEquals(BoardGeometry.surfaceZ(upper.tile), point.z, .0001f,
                                      "Frozen falls must preserve the level where units stand");
                            }
                        }
                    }
                    BoardSurface restored = upper.waterGeometry().surface(scene);
                    assertEquals(upper.waterfalls, restored.waterfalls);
                    assertEquals(upper.iceSlab, restored.iceSlab);
                    assertSame(upper.frozenFalls(), restored.frozenFalls(),
                          "Reusing water geometry must retain the drawn curtain groups without rebuilding them");
                    assertEquals(upper.crest(fall), restored.crest(restored.waterfalls.getFirst()));
                }
            }
        }
    }

    @Test
    void freezingEitherEndInvalidatesTheCachedSlopeAndThawRestoresIt() {
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardScene thawed = scene(3, 1, false, false);
        BoardSurface slope = cache.get(thawed, thawed.tile(HIGH));
        assertTrue(slope.waterfalls.isEmpty(), "An unfrozen one-level step remains a graded stream");
        for (BoardScene frozen : List.of(scene(3, 1, true, false), scene(3, 1, false, true))) {
            BoardSurface fall = cache.get(frozen, frozen.tile(HIGH));
            assertNotSame(slope, fall);
            assertEquals(1, fall.waterfalls.size(), "Either frozen endpoint needs a curtain across the step");
            var frozenFaces = fall.frozenFalls();
            assertEquals(fall.tile.frozen(), !frozenFaces.isEmpty(), "Only a frozen source owns ice geometry");
            assertSame(frozenFaces, cache.get(frozen, frozen.tile(HIGH)).frozenFalls());
            slope = cache.get(thawed, thawed.tile(HIGH));
            assertNotSame(fall, slope);
            assertTrue(slope.waterfalls.isEmpty());
            assertTrue(slope.frozenFalls().isEmpty(), "Thawing must release the previous frozen draw groups");
        }
    }

    @Test
    void liquidFallsOpenOnlyTheDrawnIceAtTheirFootInEveryDirection() {
        for (int direction = 0; direction < 6; direction++) {
            for (int drop : new int[] { 1, 3, 6 }) {
                BoardScene scene = scene(direction, drop, false, true);
                BoardSurface upper = new BoardSurface(scene, scene.tile(HIGH));
                BoardSurface lower = new BoardSurface(scene, scene.tile(HIGH.translated(direction)));
                assertImpactPocket(scene, upper, upper.waterfalls.getFirst(), lower);
                Vector3[][] liquid = GpuWaterfall.grid(upper, upper.waterfalls.getFirst());
                for (int column = 2; column < liquid.length - 2; column++) {
                    Vector3 landing = liquid[column][liquid[column].length - 1];
                    Vector3 inward = BoardGeometry.center(lower.tile.coords(), 0).sub(landing);
                    inward.z = 0;
                    Vector3 nearby = new Vector3(landing).mulAdd(inward.nor(), 2 * BoardGeometry.hexScale());
                    assertTrue(Float.isFinite(iceHeight(lower.iceSlab, nearby)),
                          "Ice must closely surround the actual landing, not leave a broad conservative apron");
                }
                BoardScene frozen = scene(direction, drop, true, true);
                BoardSurface frozenUpper = new BoardSurface(frozen, frozen.tile(HIGH));
                BoardSurface frozenLower = new BoardSurface(frozen, frozen.tile(HIGH.translated(direction)));
                assertEquals(lower.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.ICE).toList(),
                      frozenLower.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.ICE).toList(),
                      "The visual opening must not change the receiver's game support");
                Vector3[][] grid = GpuWaterfall.grid(frozenUpper, frozenUpper.waterfalls.getFirst());
                for (int column = 2; column < grid.length - 2; column++) {
                    Vector3 landing = grid[column][grid[column].length - 1];
                    assertEquals(landing.z, iceHeight(frozenLower.iceSlab, landing), .0001f,
                          "A frozen source still joins the receiving slab, with no impact opening");
                }
            }
        }
    }

    @Test
    void liquidImpactPocketsContinueRoundBothJoinedCornersAndInvalidateOnFreeze() {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean valley : new boolean[] { false, true }) {
                BoardScene frozen = joinedScene(direction, valley);
                var tiles = frozen.tiles().stream().map(t -> tile(t.coords(), t.elevation(), t.liquid().present(),
                      t.frozen() && t.elevation() == 0)).toList();
                BoardScene liquid = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface.Cache cache = new BoardSurface.Cache();
                int checked = 0;
                for (var tile : liquid.tiles()) {
                    if (!tile.liquid().present() || tile.elevation() == 0) { continue; }
                    BoardSurface upper = cache.get(liquid, tile);
                    for (var fall : upper.waterfalls) {
                        Coords receiving = tile.coords().translated(BoardGeometry.edgeDirection(fall.edge()));
                        BoardSurface lower = cache.get(liquid, liquid.tile(receiving));
                        assertImpactPocket(liquid, upper, fall, lower);
                        BoardSurface refrozen = cache.get(frozen, frozen.tile(receiving));
                        assertNotSame(lower, refrozen, "Freezing the source must close the receiver's cached opening");
                        assertNotEquals(lower.iceSlab, refrozen.iceSlab);
                        BoardSurface reopened = cache.get(liquid, liquid.tile(receiving));
                        assertNotSame(refrozen, reopened);
                        assertEquals(lower.iceSlab, reopened.iceSlab, "Thawing deterministically restores the same opening");
                        assertEquals(lower.waterFaces, reopened.waterGeometry().surface(liquid).waterFaces);
                        checked++;
                    }
                }
                assertEquals(2, checked, "Both actual curtains at a prow or valley must reach their receiving water");
            }
        }
    }

    private static void assertImpactPocket(BoardScene scene, BoardSurface upper, BoardSurface.Side fall, BoardSurface lower) {
        Vector3[][] grid = GpuWaterfall.grid(upper, fall);
        var support = lower.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.ICE).toList();
        int covered = 0;
        for (var column : grid) {
            Vector3 landing = column[column.length - 1];
            assertEquals(BoardGeometry.waterZ(lower.tile), landing.z, .0001f, "Liquid reaches the pool below the slab");
            if (!Float.isFinite(iceHeight(support, landing))) { continue; }
            assertFalse(Float.isFinite(iceHeight(lower.iceSlab, landing)), "No drawn slab may cover the landing " + landing);
            assertEquals(landing.z, iceHeight(lower.waterFaces, landing), .0001f, "The opening uses the actual liquid surface");
            assertEquals(BoardGeometry.surfaceZ(lower.tile), iceHeight(support, landing), .0001f);
            covered++;
        }
        assertTrue(covered >= grid.length / 2, "Exercise actual landings inside the receiver, including joined ends");
        Vector3 centre = BoardGeometry.center(lower.tile.coords(), 0);
        assertEquals(BoardGeometry.surfaceZ(lower.tile), iceHeight(lower.iceSlab, centre), .0001f,
              "Ice beyond the impact pocket remains");
        var hit = BoardGeometry.hit(scene, new Ray(new Vector3(centre.x, centre.y, BoardGeometry.surfaceZ(lower.tile) + 1),
              new Vector3(0, 0, -1)));
        assertNotNull(hit);
        assertEquals(lower.tile.coords(), hit.coords());
        assertTrue(lower.tile.frozen());
        Map<Coords, BoardSurface> surfaces = Map.of(upper.tile.coords(), upper, lower.tile.coords(), lower);
        var pools = new GpuWaterShader.Pools(scene, Map.of(), surfaces);
        float[] sample = new float[4];
        pools.sample(centre.x, centre.y, sample);
        assertTrue(sample[0] < 0, "The retained central slab must not become open water in the field");
        var face = lower.waterFaces.get(lower.waterFaces.size() / 2);
        Vector3 wet = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
        pools.sample(wet.x, wet.y, sample);
        assertTrue(sample[0] > 0, "The actual pocket is liquid inside its ice bank");
        assertFalse(GpuWaterShader.impacts(scene, lower.tile, surfaces::get).isEmpty(),
              "The existing waterfall impact/foam inputs must reach the pocket");
    }

    @Test
    void frozenSourcesNeverCreateWaterImpactsEvenWhenTheLookupContainsIce() {
        for (boolean frozen : new boolean[] { false, true }) {
            BoardScene scene = scene(3, 3, frozen, false);
            Map<Coords, BoardSurface> surfaces = new HashMap<>();
            for (var tile : scene.tiles()) {
                if (tile.liquid().present()) { surfaces.put(tile.coords(), new BoardSurface(scene, tile)); }
            }
            var impacts = GpuWaterShader.impacts(scene, scene.tile(HIGH.translated(3)), surfaces::get);
            assertEquals(frozen, impacts.isEmpty(), "Only flowing sources agitate the receiving pool");
        }
    }

    @Test
    void freezingTheReceivingStreamsNextHexUpdatesCachedLandingHeights() {
        Coords lower = HIGH.translated(3), downstream = lower.translated(3);
        BoardScene scene = scene(3, 3, true, false);
        List<BoardScene.Tile> sloping = scene.tiles().stream()
              .map(t -> t.coords().equals(downstream) ? tile(downstream, -1, true, false) : t).toList();
        BoardScene thawed = new BoardScene(0, 7, 7, sloping, List.of(), List.of(), -1, "", List.of());
        List<BoardScene.Tile> stopped = sloping.stream()
              .map(t -> t.coords().equals(downstream) ? tile(downstream, -1, true, true) : t).toList();
        BoardScene frozen = new BoardScene(0, 7, 7, stopped, List.of(), List.of(), -1, "", List.of());
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface before = cache.get(thawed, thawed.tile(HIGH));
        Vector3 point = BoardGeometry.center(lower, 0).lerp(BoardGeometry.center(downstream, 0), .4f);
        float beforeHeight = before.receivingHeight(before.waterfalls.getFirst(), point.x, point.y);
        var beforeFaces = before.frozenFalls();
        BoardSurface after = cache.get(frozen, frozen.tile(HIGH));
        assertNotSame(before, after, "A frozen neighbour of the receiving pool changes the source's landing geometry");
        assertFalse(beforeFaces.isEmpty());
        assertFalse(after.frozenFalls().isEmpty());
        assertNotSame(beforeFaces, after.frozenFalls(), "Changed receiving heights require new frozen geometry");
        assertEquals(BoardGeometry.waterZ(frozen.tile(lower)),
              after.receivingHeight(after.waterfalls.getFirst(), point.x, point.y), .0001f);
        assertTrue(beforeHeight < BoardGeometry.waterZ(thawed.tile(lower)) - .01f,
              "The original receiver follows its open downstream slope");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void frozenCurtainPickingUsesItsDrawnFacesWithOrWithoutFinishedGeometry(TerrainLod lod) {
        BoardScene scene = scene(3, 3, true, true);
        var upper = new BoardSurface(scene, scene.tile(HIGH), lod);
        var drop = upper.waterfalls.getFirst();
        var outward = upper.crest(drop).normal(.5f);
        float middle = (BoardGeometry.surfaceZ(upper.tile) + drop.lowA()) / 2;
        var faces = upper.frozenFalls().getFirst().faces();
        assertFalse(faces.isEmpty());
        var face = faces.stream().filter(f -> {
            Vector3 centre = new Vector3(f.a()).add(f.b()).add(f.c()).scl(1 / 3f);
            Vector3 normal = new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a())).nor();
            return Math.abs(centre.z - middle) < BoardGeometry.level() * .5f && normal.dot(outward) > .8f;
        }).findFirst().orElseThrow();
        Vector3 centre = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float reach = .05f * BoardGeometry.hexScale();
        Ray ray = new Ray(new Vector3(centre).mulAdd(normal, reach), new Vector3(normal).scl(-1));
        if (lod == TerrainLod.FULL) {
            var direct = BoardGeometry.hit(scene, ray);
            assertNotNull(direct);
            assertEquals(HIGH, direct.coords());
            assertNotNull(direct.face());
            assertEquals(BoardSurface.Finish.ICE, direct.face().finish());
            assertEquals(reach * reach, direct.distance(), .0001f);
        }
        float floor = BoardGeometry.floor(scene);
        var finished = BoardTacticalGeometry.Surface.of(upper, scene, floor);
        assertSame(upper.frozenFalls(), finished.frozenFalls(), "The picking snapshot borrows the draw groups");
        BoardSurface.Face picked = null;
        for (int repeat = 0; repeat < 3; repeat++) {
            var installed = BoardGeometry.hit(scene, ray, List.of(upper.tile), floor, coords -> finished);
            assertNotNull(installed);
            assertEquals(HIGH, installed.coords());
            assertEquals(reach * reach, installed.distance(), .0001f,
                  "Installed picking must intersect this LOD's drawn triangle, not the full-detail fallback");
            assertTrue(faces.stream().anyMatch(drawn -> drawn == installed.face()),
                  "A pick must return an actual retained draw face, not an equal regenerated triangle");
            if (picked != null) { assertSame(picked, installed.face()); }
            picked = installed.face();
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void detailSamplesTheSameShapeAndKeepsAClosedBoundedMesh(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = scene(direction, 3, true, true);
            var full = new BoardSurface(scene, scene.tile(HIGH));
            var surface = new BoardSurface(scene, scene.tile(HIGH), lod);
            var drop = surface.waterfalls.getFirst();
            Vector3[][] reference = GpuFrozenWaterfall.grid(full, full.waterfalls.getFirst());
            Vector3[][] grid = GpuFrozenWaterfall.grid(surface, drop, lod);
            Vector3[][] repeated = GpuFrozenWaterfall.grid(surface, drop, lod);
            Vector3[][] contact = GpuWaterfall.grid(surface, drop);
            int stride = (reference.length - 1) / (grid.length - 1);
            for (int column = 0; column < grid.length; column++) {
                for (int row = 0; row < grid[column].length; row++) {
                    assertEquals(reference[column * stride][row], grid[column][row],
                          "LOD must sample the same stationary shape without reseeding its growth");
                    assertEquals(grid[column][row], repeated[column][row]);
                    assertEquals(contact[column * stride][row].z, grid[column][row].z, 0,
                          "Connected ice growth must not change the established fall heights");
                    assertTrue(grid[column][row].dst(contact[column * stride][row]) <= BoardRelief.metres(6.001f),
                          "Broad deposits must remain inside the bounded six-metre growth footprint");
                    if (column == 0 || column == grid.length - 1 || row == 0 || row == grid[column].length - 1) {
                        assertEquals(contact[column * stride][row], grid[column][row],
                              "The slab, receiving surface and shared corner curves must remain exact");
                    }
                }
            }
            var faces = surface.frozenFalls().getFirst().faces();
            int budget = lod.dressing ? 1300 : 700;
            assertTrue(faces.size() <= budget, "Frozen geometry exceeds its per-fall triangle budget: " + faces.size());
            Map<Segment, Integer> edges = new HashMap<>();
            for (var face : faces) {
                Vector3[] points = { face.a(), face.b(), face.c() };
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).len2() > 1e-10f);
                for (int i = 0; i < 3; i++) {
                    Vector3 a = points[i], b = points[(i + 1) % 3];
                    Segment reverse = new Segment(b, a);
                    edges.merge(edges.containsKey(reverse) ? reverse : new Segment(a, b), 1, Integer::sum);
                }
            }
            assertTrue(edges.values().stream().allMatch(count -> count == 2),
                  "The retained ice body must be closed, including its lip and receiving-surface caps");
            Map<Vector3, List<Vector3>> neighbours = new HashMap<>();
            for (var edge : edges.keySet()) {
                neighbours.computeIfAbsent(edge.a(), ignored -> new ArrayList<>()).add(edge.b());
                neighbours.computeIfAbsent(edge.b(), ignored -> new ArrayList<>()).add(edge.a());
            }
            var reached = new HashSet<Vector3>();
            var pending = new ArrayList<Vector3>();
            pending.add(faces.getFirst().a());
            for (int next = 0; next < pending.size(); next++) {
                Vector3 point = pending.get(next);
                if (reached.add(point)) { pending.addAll(neighbours.get(point)); }
            }
            assertEquals(neighbours.size(), reached.size(),
                  "All drawn ice must form one connected body, with no separate capped pendants");
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void frozenProwsAndValleysKeepSharedCornersAtEveryDetail(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean valley : new boolean[] { false, true }) {
                BoardScene scene = joinedScene(direction, valley);
                List<BoardSurface> surfaces = scene.tiles().stream().filter(tile -> tile.water() && tile.elevation() > 0)
                      .map(tile -> new BoardSurface(scene, tile, lod)).toList();
                var ends = new ArrayList<Vector3[]>();
                for (var surface : surfaces) {
                    for (var drop : surface.waterfalls) {
                        Vector3[][] grid = GpuFrozenWaterfall.grid(surface, drop, lod);
                        if (surface.fallJoins(drop, true)) { ends.add(grid[0]); }
                        if (surface.fallJoins(drop, false)) { ends.add(grid[grid.length - 1]); }
                    }
                }
                assertEquals(2, ends.size(), "Exercise the joined pair around the upper prow or receiving valley");
                for (int row = 0; row < ends.getFirst().length; row++) {
                    assertTrue(ends.getFirst()[row].epsilonEquals(ends.getLast()[row], .0001f),
                          "Frozen falls must share the complete corner curve: " + direction + ", valley=" + valley);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void frozenMaterialChartsKeepScaleAndCoverTheClosedGeometry(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene scene = scene(direction, 3, true, true);
            var surface = new BoardSurface(scene, scene.tile(HIGH), lod);
            var drop = surface.waterfalls.getFirst();
            var geometry = surface.frozenFalls().getFirst();
            float[] uv = geometry.uv();
            assertEquals(geometry.faces().size() * 6, uv.length, "Rejected triangles must not leave UV entries");
            for (int offset = 0; offset < uv.length; offset += 6) {
                for (int i = 0; i < 6; i++) { assertTrue(Float.isFinite(uv[offset + i])); }
                float area = (uv[offset + 2] - uv[offset]) * (uv[offset + 5] - uv[offset + 1])
                      - (uv[offset + 4] - uv[offset]) * (uv[offset + 3] - uv[offset + 1]);
                assertTrue(Math.abs(area) > 1e-12f, "Every retained face needs a usable material chart");
            }
            Vector3[][] grid = GpuFrozenWaterfall.grid(surface, drop, lod);
            var full = new BoardSurface(scene, scene.tile(HIGH), TerrainLod.FULL);
            var fullGeometry = full.frozenFalls().getFirst();
            Vector3[][] fullGrid = GpuFrozenWaterfall.grid(full, full.waterfalls.getFirst());
            int stride = (fullGrid.length - 1) / (grid.length - 1);
            for (int column = 0; column < grid.length; column++) {
                for (int row = 0; row < grid[column].length; row++) {
                    float[] expected = materialUv(fullGeometry, fullGrid[column * stride][row]);
                    float[] actual = materialUv(geometry, grid[column][row]);
                    assertEquals(expected[0], actual[0], 0, "LOD must not shift the material along the crest");
                    assertEquals(expected[1], actual[1], 0, "LOD must not shift the material down the fall");
                }
            }
            float length = 0;
            for (int column = 1; column < fullGrid.length; column++) {
                Vector3 a = fullGrid[column - 1][1], b = fullGrid[column][1];
                length += (float) Math.hypot(b.x - a.x, b.y - a.y);
            }
            float[] first = materialUv(geometry, grid[0][0]);
            float[] last = materialUv(geometry, grid[grid.length - 1][0]);
            assertEquals(length / BoardRelief.metres(3.5f), last[0] - first[0], .0001f,
                  "The free curtain uses the same physical horizontal repeat in all six orientations");
            int column = grid.length / 2;
            for (int row = 1; row < grid[column].length; row++) {
                float[] a = materialUv(geometry, grid[column][row - 1]);
                float[] b = materialUv(geometry, grid[column][row]);
                assertEquals(grid[column][row].dst(grid[column][row - 1]) / BoardRelief.metres(15),
                      a[1] - b[1], .0001f, "Material V follows the lip and apron as well as vertical descent");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void frozenMaterialChartsMeetAtProwsAndValleys(TerrainLod lod) {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean valley : new boolean[] { false, true }) {
                BoardScene scene = joinedScene(direction, valley);
                var ends = new ArrayList<List<float[]>>();
                for (var tile : scene.tiles()) {
                    if (!tile.frozen() || tile.elevation() <= 0) { continue; }
                    var surface = new BoardSurface(scene, tile, lod);
                    for (int index = 0; index < surface.waterfalls.size(); index++) {
                        var drop = surface.waterfalls.get(index);
                        Vector3[][] grid = GpuFrozenWaterfall.grid(surface, drop, lod);
                        var geometry = surface.frozenFalls().get(index);
                        for (boolean atA : new boolean[] { true, false }) {
                            if (!surface.fallJoins(drop, atA)) { continue; }
                            var edge = new ArrayList<float[]>();
                            for (var point : grid[atA ? 0 : grid.length - 1]) { edge.add(materialUv(geometry, point)); }
                            ends.add(edge);
                        }
                    }
                }
                assertEquals(2, ends.size());
                for (int row = 0; row < ends.getFirst().size(); row++) {
                    float[] a = ends.getFirst().get(row), b = ends.getLast().get(row);
                    float difference = a[0] - b[0];
                    assertEquals(Math.round(difference), difference, .0001f,
                          "Repeated material U must have the same phase at a joined corner");
                    assertEquals(a[1], b[1], .0001f, "The shared curved path must retain identical material V");
                }
            }
        }
    }

    /** The first occurrence is on the front curtain; later cap occurrences intentionally have their own charts. */
    private static float[] materialUv(GpuFrozenWaterfall.Geometry geometry, Vector3 point) {
        for (int index = 0; index < geometry.faces().size(); index++) {
            var face = geometry.faces().get(index);
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int corner = 0; corner < points.length; corner++) {
                if (points[corner].equals(point)) {
                    int offset = index * 6 + corner * 2;
                    return new float[] { geometry.uv()[offset], geometry.uv()[offset + 1] };
                }
            }
        }
        throw new AssertionError("Missing retained curtain vertex: " + point);
    }

    private record Segment(Vector3 a, Vector3 b) { }

    private static BoardScene joinedScene(int direction, boolean valley) {
        Coords lower = HIGH.translated(direction), side = HIGH.translated((direction + 1) % 6);
        List<Coords> water = List.of(HIGH, lower, side);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean high = coords.equals(HIGH) || valley && coords.equals(side);
                tiles.add(tile(coords, high ? 3 : 0, water.contains(coords), water.contains(coords)));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene scene(int direction, int drop, boolean upperIce, boolean lowerIce) {
        Coords low = HIGH.translated(direction);
        Vector3 highCentre = BoardGeometry.center(HIGH, 0), lowCentre = BoardGeometry.center(low, 0);
        Vector3 middle = new Vector3(highCentre).lerp(lowCentre, .5f), toward = new Vector3(lowCentre).sub(highCentre);
        List<Coords> river = List.of(HIGH.translated((direction + 3) % 6), HIGH, low, low.translated(direction));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean high = new Vector3(BoardGeometry.center(coords, 0)).sub(middle).dot(toward) < 0;
                boolean water = river.contains(coords);
                tiles.add(tile(coords, high ? drop : 0, water, water && (high ? upperIce : lowerIce)));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile tile(Coords coords, int elevation, boolean water, boolean frozen) {
        return BoardScenes.newTile(coords, elevation, water ? 2 : -1, frozen, 0, BoardScene.Surface.SAND,
              List.of(), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null);
    }

    private static float iceHeight(List<BoardSurface.Face> faces, Vector3 point) {
        return (float) faces.stream().mapToDouble(face -> face.height(point.x, point.y)).max().orElse(Double.NaN);
    }
}
