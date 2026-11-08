/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardWaterSlopeTest {
    @Test
    void boardEdgeCutsCloseToTheDrawnGradedLavaAndWater() {
        BoardScene lava = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        int checked = 0;
        for (BoardScene scene : List.of(lava, BoardWaterfallTest.withWater(lava))) {
            for (var tile : scene.tiles()) {
                if (!tile.liquid().present()) { continue; }
                BoardSurface surface = new BoardSurface(scene, tile);
                Vector3[] bed = surface.waterGeometry().bedOutline();
                for (int edge = 0; edge < 6; edge++) {
                    if (scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge))) != null) { continue; }
                    int side = edge;
                    var cut = surface.cutFaces.stream().filter(face -> face.landEdge() == side).toList();
                    for (int i = 0; i < BoardSurface.SHORE_SEGMENTS; i++) {
                        int at = edge * BoardSurface.SHORE_SEGMENTS + i, next = (at + 1) % bed.length;
                        for (float t : new float[] { 0, .5f, 1 }) {
                            Vector3 top = new Vector3(surface.water.get(at)).lerp(surface.water.get(next), t);
                            Vector3 bottom = new Vector3(bed[at]).lerp(bed[next], t);
                            if (top.z - bottom.z < .01f) { continue; }
                            for (Vector3 point : List.of(top, bottom)) {
                                float gap = Float.POSITIVE_INFINITY;
                                for (var face : cut) { gap = Math.min(gap, distanceToFace(point, face)); }
                                assertTrue(gap < .003f, "Board-edge cut must join its actual graded rim and bed: "
                                      + tile.coords() + ", " + tile.liquid().kind() + ", " + point + ", gap=" + gap);
                            }
                            checked++;
                        }
                    }
                    for (var face : cut) {
                        for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                            assertTrue(point.z <= surface.waterHeight(point.x, point.y) + .003f,
                                  "The cut must not protrude above the liquid at " + tile.coords() + ": " + point);
                        }
                    }
                }
            }
        }
        assertTrue(checked > 100, "Exercise both ends of several graded board-edge cuts");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void descendingDomeVentBanksMeetBothEndsOfTheirWalls(TerrainLod lod) {
        BoardScene lava = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        for (BoardScene scene : List.of(lava, BoardWaterfallTest.withWater(lava))) {
            for (boolean transitions : new boolean[] { false, true }) {
                BoardScenes.withTransitions(transitions, () -> checkDescendingBankContacts(scene, lod));
            }
        }
    }

    private static void checkDescendingBankContacts(BoardScene scene, TerrainLod lod) {
        float floor = BoardGeometry.floor(scene);
        int checked = 0;
        for (var tile : scene.tiles()) {
            if (!tile.liquid().present()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile, lod);
            var walls = surface.walls(scene, floor);
            for (var side : surface.sides(scene, floor)) {
                var lower = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(side.edge())));
                if (!BoardSurface.waterSlope(tile, lower) || lower.elevation() >= tile.elevation()) { continue; }
                for (var point : List.of(side.a(), side.b(), new Vector3(side.a().x, side.a().y, side.lowA()),
                      new Vector3(side.b().x, side.b().y, side.lowB()))) {
                    float distance = Float.POSITIVE_INFINITY;
                    for (var face : walls) { distance = Math.min(distance, distanceToFace(point, face)); }
                    assertTrue(distance < .004f, "A descending bank's wall must reach its rim and foot: "
                          + tile.coords() + ", " + tile.liquid().kind() + ", " + lod + ", transitions="
                          + BoardGeometry.tuning().transitions() + ", point=" + point + ", gap=" + distance);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 16, "Exercise several narrow banks on Dome Vent 2");
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM", "COARSE" })
    void descendingWaterReachesTheCliffFaceAboveItsBed(TerrainLod lod) {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            Coords high = new Coords(3, 3);
            for (int direction = 0; direction < 6; direction++) {
                Coords low = high.translated(direction);
                BoardScene scene = scene(Map.of(high, 2, low, 0), Map.of(high, 1, low, 1));
                assertCliffContact(scene, low, lod);
            }
            assertCliffContact(BoardScenes.mapScene(BoardScene.Surface.SAND), new Coords(9, 9), lod);
            BoardScene pools = BoardScenes.mixedDepthScene();
            for (var tile : pools.tiles()) {
                if (tile.liquid().present()) { assertCliffContact(pools, tile.coords(), lod); }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static void assertCliffContact(BoardScene scene, Coords coords, TerrainLod lod) {
        BoardSurface surface = new BoardSurface(scene, scene.tile(coords), lod);
        for (var face : surface.waterFaces) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            assertTrue(normal.z >= -.001f, "Extending the water to the cliff must not fold the surface: " + coords
                  + ", normal=" + normal + ", face=" + face);
        }
        for (int edge = 0; edge < 6; edge++) {
            if (!surface.relief.wetCliff(edge)) { continue; }
            var land = scene.tile(coords.translated(BoardGeometry.edgeDirection(edge)));
            var cliff = new BoardSurface(scene, land, lod);
            int opposite = (edge + 3) % 6;
            var walls = new ArrayList<>(cliff.walls(scene, -100).stream()
                  .filter(f -> f.landEdge() == opposite).toList());
            final int cliffEdge = edge;
            walls.addAll(surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.WALL
                  && f.landEdge() == cliffEdge).toList());
            List<Vector3> boundary = surface.waterBoundary(edge);
            List<Vector3> samples = new ArrayList<>(boundary.subList(1, boundary.size() - 1));
            for (int i = 0; i < boundary.size() - 1; i++) {
                Vector3 a = boundary.get(i), b = boundary.get(i + 1);
                if (a.z < surface.tile.elevation() * BoardGeometry.LEVEL && Math.abs(a.z - b.z) < .0001f) {
                    samples.add(new Vector3(a).lerp(b, .5f));
                }
            }
            for (Vector3 p : samples) {
                float distance = Float.POSITIVE_INFINITY;
                for (var face : walls) {
                    distance = Math.min(distance, distanceToFace(p, face));
                }
                assertTrue(distance < .04f, "Water must touch the drawn cliff, not stop at the bed's inset: "
                      + coords + ", edge=" + edge + ", sample=" + p + ", gap=" + distance);
            }
        }
    }

    /** A normal projection avoids grazing-ray misses at the refined contour's wall-edge vertices. */
    private static float distanceToFace(Vector3 point, BoardSurface.Face face) {
        float boundary = Float.POSITIVE_INFINITY;
        Vector3[] vertices = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            Vector3 a = vertices[i], edge = new Vector3(vertices[(i + 1) % 3]).sub(a);
            float t = edge.len2() == 0 ? 0 : Math.clamp(new Vector3(point).sub(a).dot(edge) / edge.len2(), 0, 1);
            boundary = Math.min(boundary, new Vector3(a).mulAdd(edge, t).dst(point));
        }
        Vector3 ab = new Vector3(face.b()).sub(face.a()), ac = new Vector3(face.c()).sub(face.a());
        Vector3 ap = new Vector3(point).sub(face.a());
        float aa = ab.dot(ab), bb = ac.dot(ac), cross = ab.dot(ac), pa = ap.dot(ab), pb = ap.dot(ac);
        float determinant = aa * bb - cross * cross;
        if (determinant < 1e-8f) { return boundary; }
        float u = (bb * pa - cross * pb) / determinant, v = (aa * pb - cross * pa) / determinant;
        if (u < 0 || v < 0 || u + v > 1) { return boundary; }
        return Math.min(boundary, Math.abs(ab.crs(ac).nor().dot(ap)));
    }

    /** Easing a bank over only a third of its length tilted the water sideways; sky reflections traced its sides. */
    @Test
    void aDescendingStreamLiesAboutLevelAcrossItsChannel() {
        Coords high = new Coords(3, 3);
        for (int drop : new int[] { 1, 2 }) {
            for (int direction = 0; direction < 6; direction++) {
                Coords low = high.translated(direction), upstream = high.translated((direction + 3) % 6);
                Coords downstream = low.translated(direction);
                BoardScene scene = scene(Map.of(upstream, drop, high, drop, low, 0, downstream, 0),
                      Map.of(upstream, 1, high, 1, low, 1, downstream, 1));
                BoardSurface a = new BoardSurface(scene, scene.tile(high)), b = new BoardSurface(scene, scene.tile(low));
                Vector3 from = BoardGeometry.center(high, 0), to = BoardGeometry.center(low, 0);
                Vector3 across = new Vector3(-(to.y - from.y), to.x - from.x, 0).scl(.2f);
                for (float along : new float[] { .3f, .4f, .6f, .7f }) {
                    BoardSurface surface = along < .5f ? a : b;
                    Vector3 middle = new Vector3(from).lerp(to, along);
                    float level = surface.waterHeight(middle.x, middle.y);
                    for (int side : new int[] { -1, 1 }) {
                        Vector3 point = new Vector3(middle).mulAdd(across, side);
                        float height = BoardSurface.sampleHeight(surface.waterFaces, point.x, point.y, Float.NaN);
                        assertTrue(Float.isFinite(height), "The sample must be inside the channel");
                        assertEquals(level, height, .08f * drop * BoardGeometry.LEVEL,
                              "Level across the channel: drop=" + drop + ", direction=" + direction + ", at=" + along);
                    }
                }
            }
        }
    }

    @Test
    void oneAndTwoLevelStreamsShareSlopingWaterAndBedsRegardlessOfDepth() {
        Coords high = new Coords(3, 3);
        for (int drop : new int[] { 1, 2 }) {
            for (int direction = 0; direction < 6; direction++) {
                for (int[] depths : List.of(new int[] { 1, 1 }, new int[] { 0, 3 }, new int[] { 3, 0 })) {
                    Coords low = high.translated(direction);
                    BoardScene scene = scene(Map.of(high, drop, low, 0), Map.of(high, depths[0], low, depths[1]));
                    BoardSurface a = new BoardSurface(scene, scene.tile(high)), b = new BoardSurface(scene, scene.tile(low));
                    String label = "Drop " + drop + ", direction " + direction + ", depths " + depths[0] + "/" + depths[1];
                    assertTrue(a.waterfalls.isEmpty() && b.waterfalls.isEmpty(), label);
                    int edge = Math.floorMod(1 - direction, 6), opposite = (edge + 3) % 6, n = BoardSurface.SHORE_SEGMENTS;
                    for (int i = 0; i <= n; i++) {
                        Vector3 p = a.outline.get((edge * n + i) % (6 * n));
                        Vector3 q = b.outline.get(((opposite + 1) * n - i) % (6 * n));
                        assertTrue(p.epsilonEquals(q, .002f), "Shared water: " + label + ": " + p + " / " + q);
                        // Cliff rocks can overhang this seam; compare the bed itself beneath those rocks.
                        assertEquals(BoardSurface.sampleHeight(a.faces.stream()
                                    .filter(f -> f.finish() == BoardSurface.Finish.BED).toList(), p.x, p.y, Float.NaN),
                              BoardSurface.sampleHeight(b.faces.stream()
                                    .filter(f -> f.finish() == BoardSurface.Finish.BED).toList(), p.x, p.y, Float.NaN),
                              .02f, "Shared bed: " + label + ", sample " + i + ", " + p);
                    }
                    for (BoardSurface surface : List.of(a, b)) {
                        Vector3 p = BoardGeometry.center(surface.tile.coords(), 0);
                        assertEquals(BoardGeometry.waterZ(surface.tile), surface.waterHeight(p.x, p.y), .001f, label);
                        assertEquals(BoardGeometry.groundZ(surface.tile), surface.height(p.x, p.y), .001f, label);
                        for (var face : surface.waterFaces) {
                            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                            assertTrue(normal.z >= 0, "The stream does not fold: " + label);
                        }
                    }
                    Vector3 from = BoardGeometry.center(high, 0), to = BoardGeometry.center(low, 0);
                    float previous = Float.POSITIVE_INFINITY;
                    BoardSurface.Cache cache = new BoardSurface.Cache();
                    for (int i = 0; i <= 20; i++) {
                        Vector3 p = new Vector3(from).lerp(to, i / 20f);
                        BoardSurface own = i < 10 ? a : b;
                        float water = own.waterHeight(p.x, p.y);
                        assertTrue(water <= previous + .01f, "The water descends continuously: " + label);
                        if (i % 5 == 0) {
                            assertEquals(water, UnitLandingSupports.surface(scene, p.x, p.y, cache), .02f,
                                  "Markers follow the drawn water: " + label);
                        }
                        if (i == 0 && direction == 0 && depths[0] == 1) {
                            assertEquals(high, BoardGeometry.pick(scene,
                                  new Ray(new Vector3(p.x, p.y, 500), new Vector3(0, 0, -1))));
                        }
                        previous = water;
                    }
                }
            }
        }
    }

    @Test
    void wallsBesideSlopingWaterMeetTheDrawnBank() {
        Coords high = new Coords(3, 3);
        for (int drop : new int[] { 1, 2 }) {
            for (int direction = 0; direction < 6; direction++) {
                Coords low = high.translated(direction);
                BoardScene scene = scene(Map.of(high, drop, low, 0), Map.of(high, 1, low, 1));
                BoardSurface surface = new BoardSurface(scene, scene.tile(high));
                List<BoardSurface.Face> ground = surface.faces.stream()
                      .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING)
                      .toList();
                int edge = Math.floorMod(1 - direction, 6);
                Vector3 center = BoardGeometry.center(high, 0);
                Vector3 a = surface.outline.get(edge * BoardSurface.SHORE_SEGMENTS);
                Vector3 b = surface.outline.get((edge + 1) % 6 * BoardSurface.SHORE_SEGMENTS);
                for (Vector3 end : List.of(a, b)) {
                    Vector3 intoBank = new Vector3(end).sub(end == a ? b : a).nor().scl(.1f * BoardGeometry.HEX_SCALE);
                    Vector3 p = new Vector3(end).add(intoBank);
                    assertEquals(end.z, surface.waterHeight(p.x, p.y), .5f * BoardGeometry.HEX_SCALE,
                          "Bank wetness follows the sloping shore, not the hex's nominal water level");
                }
                for (var side : surface.sides(scene, -200)) {
                    if (side.edge() != edge) { continue; }
                    for (float t : new float[] { .2f, .5f, .8f }) {
                        Vector3 p = new Vector3(side.a()).lerp(side.b(), t);
                        // Just inside the bank, clear of triangle-edge rounding in the point sampler.
                        Vector3 sample = new Vector3(p).lerp(center, .00001f);
                        assertEquals(p.z, BoardSurface.sampleHeight(ground, sample.x, sample.y, Float.NaN), .04f,
                              "No gouge above the wall: drop=" + drop + ", direction=" + direction + ", at " + p);
                    }
                }
            }
        }
    }

    @Test
    void threeLevelsStillFallAndDepthChangesAloneNeverCreateAFall() {
        Coords high = new Coords(3, 3), low = high.translated(0);
        BoardScene deep = scene(Map.of(high, 0, low, 0), Map.of(high, 0, low, 3));
        BoardSurface flat = new BoardSurface(deep, deep.tile(high));
        assertTrue(flat.waterfalls.isEmpty());
        for (Vector3 p : flat.outline) { assertEquals(BoardGeometry.waterZ(deep.tile(high)), p.z, .001f); }
        BoardScene drop = scene(Map.of(high, 3, low, 0), Map.of(high, 0, low, 0));
        assertEquals(1, new BoardSurface(drop, drop.tile(high)).waterfalls.size());
        BoardScene gentle = scene(Map.of(high, 1, low, 0), Map.of(high, 1, low, 1));
        BoardScene chute = scene(Map.of(high, 2, low, 0), Map.of(high, 1, low, 1));
        Vector3 middle = BoardGeometry.center(high, 0).lerp(BoardGeometry.center(low, 0), .5f);
        BoardSurface a = new BoardSurface(chute, chute.tile(high)), b = new BoardSurface(gentle, gentle.tile(high));
        assertTrue(a.slopeAgitation(new Vector3(middle.x, middle.y, a.waterHeight(middle.x, middle.y)))
              > b.slopeAgitation(new Vector3(middle.x, middle.y, b.waterHeight(middle.x, middle.y))));
    }

    @Test
    void slopeWhitewaterBuildsDuringTheDescentAndLeavesTheLevelApproachClear() {
        Coords high = new Coords(3, 3);
        for (int drop : new int[] { 1, 2 }) {
            for (int direction = 0; direction < 6; direction++) {
                Coords low = high.translated(direction), upstream = high.translated((direction + 3) % 6);
                BoardScene scene = scene(Map.of(high, drop, low, 0, upstream, drop),
                      Map.of(high, 1, low, 1, upstream, 1));
                BoardSurface a = new BoardSurface(scene, scene.tile(high)), b = new BoardSurface(scene, scene.tile(low));
                Vector3 from = BoardGeometry.center(high, 0), to = BoardGeometry.center(low, 0);
                float middle = 0;
                for (int i = 0; i <= 20; i++) {
                    Vector3 p = new Vector3(from).lerp(to, i / 20f);
                    p.z = (i <= 10 ? a : b).waterHeight(p.x, p.y);
                    float agitation = a.slopeAgitation(p);
                    if (i <= 5 || i == 20) {
                        assertEquals(0, agitation, .001f, "Level water stays clear, direction " + direction);
                    }
                    if (i == 10) { middle = agitation; }
                    if (i == 12) { assertTrue(agitation > middle, "Whitewater builds downstream of the mouth"); }
                }
                assertTrue(middle > .1f, "The descending water still churns");
                Vector3 p = BoardGeometry.center(upstream, BoardGeometry.waterZ(scene.tile(upstream)));
                assertEquals(0, a.slopeAgitation(p));
            }
        }
    }

    @Test
    void slopesAtAThreeWayWaterCornerShareTheirHeightsEvenBesideAFall() {
        Coords a = new Coords(3, 3), b = a.translated(0), c = a.translated(1);
        for (int high : new int[] { 2, 3 }) {
            BoardScene scene = scene(Map.of(a, 0, b, 1, c, high), Map.of(a, 1, b, 1, c, 1));
            for (Coords coords : List.of(a, b, c)) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                for (int d = 0; d < 6; d++) {
                    var other = scene.tile(coords.translated(d));
                    if (!BoardSurface.waterSlope(surface.tile, other)) { continue; }
                    BoardSurface next = new BoardSurface(scene, other);
                    int edge = Math.floorMod(1 - d, 6), opposite = (edge + 3) % 6, n = BoardSurface.SHORE_SEGMENTS;
                    for (int i = 0; i <= n; i++) {
                        Vector3 p = surface.outline.get((edge * n + i) % (6 * n));
                        Vector3 q = next.outline.get(((opposite + 1) * n - i) % (6 * n));
                        assertTrue(p.epsilonEquals(q, .003f), "Continuous corner at " + coords + ": " + p + " / " + q);
                    }
                }
                for (var fall : surface.waterfalls) {
                    Vector3[][] sheet = GpuWaterfall.grid(surface, fall);
                    for (int i = 0; i < sheet.length; i += 2) {
                        Vector3 top = sheet[i][0];
                        assertTrue(surface.water.stream().anyMatch(p -> p.epsilonEquals(top, .003f)),
                              "The waterfall starts on its sloping upper pool: " + top);
                        for (Vector3 point : sheet[i]) {
                            assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y) && Float.isFinite(point.z));
                        }
                    }
                }
            }
        }
    }

    private static BoardScene scene(Map<Coords, Integer> levels, Map<Coords, Integer> depths) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int depth = depths.getOrDefault(coords, -1);
                tiles.add(new BoardScene.Tile(coords, levels.getOrDefault(coords, 2), depth, false, 0,
                      BoardScene.Surface.SAND, null, null, List.of(), List.of(),
                      depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void sculptedBedsDescendWithoutFoldingAndMeetBedsOfTheirLevelWithoutAStep() {
        var random = new java.util.Random(7);
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        for (int trial = 0; trial < 12; trial++) {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    tiles.add(new BoardScene.Tile(new Coords(x, y), random.nextInt(3) == 0 ? 1 : 0,
                          random.nextInt(5) == 0 ? -1 : random.nextInt(4), false, 0, BoardScene.Surface.GRASS,
                          null, List.of(), List.of()));
                }
            }
            BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            for (BoardScene.Tile tile : tiles) {
                if (!tile.liquid().present()) { continue; }
                BoardSurface surface = new BoardSurface(scene, tile);
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.BED) {
                        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                        assertTrue(normal.z > 0, "A sculpted bed must never fold over at " + tile.coords());
                    }
                }
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(BoardGeometry.groundZ(tile), surface.height(center.x, center.y), 0.01f,
                      "A unit at the centre stands on the full-depth bed");
                for (int edge = 0; edge < 6; edge++) {
                    BoardScene.Tile other = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                    if (other == null || !other.liquid().present() || other.elevation() != tile.elevation()) { continue; }
                    BoardSurface neighbor = new BoardSurface(scene, other);
                    Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge);
                    Vector3 b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                    for (int sample = 1; sample < 60; sample++) {
                        Vector3 point = new Vector3(a).lerp(b, sample / 60f);
                        assertEquals(surface.height(point.x, point.y), neighbor.height(point.x, point.y), 0.01f,
                              "Beds of one level meet without a step: " + tile.coords() + " edge " + edge);
                    }
                }
            }
        }
    }
}
