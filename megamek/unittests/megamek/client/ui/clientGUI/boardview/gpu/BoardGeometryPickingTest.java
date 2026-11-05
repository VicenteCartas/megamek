/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardGeometryPickingTest {
    @Test
    void badlandsRimRocksOverhangingTheLakeBelongToTheRaisedHex() {
        // Recessed transition rims can keep this board's small rocks inside their owner's hex. The sharp-rim
        // presentation deliberately exercises the overhang whose visible geometry must win over the lake below.
        BoardScenes.withTransitions(false, this::assertBadlandsOverhangPicking);
    }

    private void assertBadlandsOverhangPicking() {
        Board board = new Board();
        board.load(new File("data/boards/Shrapnel 13/16x17 Badlands 2 (Hazardous Liquid).board"));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = board.getHex(x, y);
                int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), hex.getLevel(), depth, false, 0,
                      BoardFeatures.surface(hex), null, null, List.of(), List.of(),
                      BoardLiquid.capture(hex), null, true));
            }
        }
        BoardScene scene = new BoardScene(0, board.getWidth(), board.getHeight(), tiles,
              List.of(), List.of(), -1, "", List.of());
        Coords cliff = new Coords(9, 8), lake = new Coords(8, 8);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface upper = cache.get(scene, scene.tile(cliff));
        float floor = BoardGeometry.floor(scene);
        Map<Coords, BoardTacticalGeometry.Surface> finished = new HashMap<>();
        var candidates = tiles.stream().filter(tile -> tile.coords().distance(cliff) <= 1).toList();
        for (var tile : candidates) {
            finished.put(tile.coords(), BoardTacticalGeometry.Surface.of(cache.get(scene, tile), scene, floor));
        }
        int checked = 0;
        for (var face : upper.faces) {
            if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
            Vector3 point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
            if (!BoardGeometry.contains(lake, point.x, point.y)
                  || point.z < BoardGeometry.surfaceZ(scene.tile(cliff))) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            if (normal.z < .1f) { continue; }
            // Hit the actual rock above the lake footprint, including its sloping sides, from just outside it.
            Ray ray = new Ray(new Vector3(point).mulAdd(normal, .1f), new Vector3(normal).scl(-1));
            BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray, candidates, floor, cache);
            assertNotNull(hit);
            assertEquals(cliff, hit.coords(), "The visible rim rock must not select the lake four levels below");
            assertTrue(hit.hardSurface() && hit.distance() < .02f, "Picking must still intersect the rock itself");
            assertEquals(hit, BoardGeometry.hit(scene, ray, candidates, floor, finished::get));
            checked++;
        }
        assertTrue(checked > 3, "The regression must exercise several real overhanging rock faces");
        Vector3 water = BoardGeometry.center(lake, scene.tile(lake).elevation()).add(0, 0, 2);
        assertEquals(lake, BoardGeometry.pick(scene, new Ray(water, new Vector3(0, 0, -1))),
              "The water below remains independently selectable");
    }

    @Test
    void finishedTerrainKeepsBuilderPickingForWaterIceCliffsAndTalus() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        BoardRelief.Tuning originalRelief = BoardRelief.tuning();
        try {
            BoardRelief.tune(BoardRelief.DEFAULTS);
            for (BoardGeometry.Tuning tuning : List.of(BoardGeometry.DEFAULTS,
                  new BoardGeometry.Tuning(1, 1, 1, 18, .8f, 1, false, 2),
                  new BoardGeometry.Tuning(1, 1, 1, 18, .8f, 1, false, 0))) {
                BoardGeometry.tune(tuning);
                compareFinishedTerrain();
            }
        } finally {
            BoardGeometry.tune(original);
            BoardRelief.tune(originalRelief);
        }
    }

    private static void compareFinishedTerrain() {
        BoardScene scene = scene();
        float floor = BoardGeometry.floor(scene);
        Map<Coords, BoardSurface> builders = new HashMap<>();
        for (BoardScene.Tile tile : scene.tiles()) { builders.put(tile.coords(), new BoardSurface(scene, tile)); }
        Map<Coords, BoardTacticalGeometry.Surface> finished = new HashMap<>();
        Map<BoardSurface.Finish, BoardSurface.Face> kinds = new EnumMap<>(BoardSurface.Finish.class);
        List<Ray> rays = new ArrayList<>();
        int water = 0, falls = 0, slopes = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            BoardSurface builder = builders.get(tile.coords());
            // This is how the render build finishes walls with already-built neighboring surfaces.
            builder.walls(scene, floor, builders);
            BoardTacticalGeometry.Surface surface = BoardTacticalGeometry.Surface.of(builder, scene, floor);
            finished.put(tile.coords(), surface);
            assertBorrowed(builder.faces, surface.faces());
            assertBorrowed(builder.waterFaces, surface.water());
            assertBorrowed(builder.walls(scene, floor), surface.walls());
            assertBorrowed(builder.waterfalls, surface.waterfalls());
            for (BoardSurface.Face face : surface.faces()) { kinds.putIfAbsent(face.finish(), face); }
            for (BoardSurface.Face face : surface.walls()) { kinds.putIfAbsent(face.finish(), face); }
            water += surface.water().size();
            falls += surface.waterfalls().size();
            slopes += surface.slopes().size();
            if (!surface.water().isEmpty()) { probe(rays, surface.water().getFirst()); }
            if (!surface.walls().isEmpty()) { probe(rays, surface.walls().getFirst()); }
            for (BoardSurface.Side side : surface.waterfalls()) {
                probe(rays, new BoardSurface.Face(side.a(), new Vector3(side.a().x, side.a().y, side.lowA()),
                      new Vector3(side.b().x, side.b().y, side.lowB()), BoardSurface.Finish.WALL));
            }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            // Both overhead and oblique camera rays, including exact shared corners and edges.
            for (int edge = 0; edge < 6; edge++) {
                Vector3 point = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
                Vector3 next = BoardGeometry.corner(tile.coords(), tile.elevation(), edge + 1);
                Vector3 direction = new Vector3(center).sub(point).add(0, 0, -BoardGeometry.HEIGHT).nor();
                rays.add(new Ray(new Vector3(center).mulAdd(direction, -400), direction));
                rays.add(new Ray(new Vector3(point).add(0, 0, 400), new Vector3(0, 0, -1)));
                rays.add(new Ray(point.lerp(next, .5f).add(0, 0, 400), new Vector3(0, 0, -1)));
            }
            if (tile.water() && !tile.frozen()) {
                // A ray starting below the liquid surface must still see the recessed bed.
                center.z = BoardGeometry.waterZ(tile) - 2;
                rays.add(new Ray(center, new Vector3(0, 0, -1)));
            }
        }
        assertTrue(kinds.containsKey(BoardSurface.Finish.BED), "The fixture includes submerged riverbeds");
        assertTrue(kinds.containsKey(BoardSurface.Finish.ICE), "The fixture includes frozen water");
        assertTrue(kinds.containsKey(BoardSurface.Finish.WALL), "The fixture includes exposed cliffs");
        assertTrue(water > 0 && falls > 0, "The fixture includes liquid surfaces and waterfalls");
        if (BoardGeometry.tuning().stepsBetweenTops()) { assertTrue(slopes > 0, "The fixture includes talus/slopes"); }
        for (BoardSurface.Face face : kinds.values()) { probe(rays, face); }
        builders.clear();

        BoardSurface.Cache cache = new BoardSurface.Cache();
        int hits = 0;
        for (Ray ray : rays) {
            BoardGeometry.Hit expected = BoardGeometry.hit(scene, ray, scene.tiles(), floor, cache);
            BoardGeometry.Hit actual = BoardGeometry.hit(scene, ray, scene.tiles(), floor, finished::get);
            assertEquals(expected, actual, () -> "Picking order, distance and owner must match at " + ray);
            if (actual != null) { hits++; }
        }
        assertTrue(hits > scene.tiles().size() * 12, "The comparison must exercise hits, not just empty rays");
        Ray outside = new Ray(new Vector3(-1000, 1000, 400), new Vector3(0, 0, -1));
        assertEquals(BoardGeometry.hit(scene, outside, scene.tiles(), floor, cache),
              BoardGeometry.hit(scene, outside, scene.tiles(), floor, finished::get));
    }

    private static <T> void assertBorrowed(List<T> source, List<T> retained) {
        assertEquals(source.size(), retained.size());
        for (int i = 0; i < source.size(); i++) { assertSame(source.get(i), retained.get(i)); }
    }

    /** The editor's object picks look through water to its bed (GpuTerrain#decorationHit); lava stays opaque. */
    @Test
    void throughWaterLooksPastTheWaterSurfaceToItsBedButNotPastLava() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                BoardLiquid liquid = x == 1 && y == 1 ? BoardLiquid.WATER : x == 3 && y == 1
                      ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE;
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, liquid == BoardLiquid.WATER ? 3 : liquid.present() ? 0 : -1,
                      false, 0, BoardScene.Surface.SAND, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        BoardScene scene = new BoardScene(0, 5, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        for (Coords pool : List.of(new Coords(1, 1), new Coords(3, 1))) {
            BoardScene.Tile tile = scene.tile(pool);
            // Just off the centre: a ray along the shared edges through it would retry nudged.
            Ray down = new Ray(BoardGeometry.center(pool, 5).add(.013f, .007f, 0), new Vector3(0, 0, -1));
            var surface = BoardGeometry.hit(scene, down, tiles, floor, cache, null, false);
            var through = BoardGeometry.hit(scene, down, tiles, floor, cache, null, true);
            assertEquals(pool, surface.coords());
            assertEquals(pool, through.coords());
            assertEquals(BoardGeometry.waterZ(tile), down.getEndPoint(new Vector3(), (float) Math.sqrt(surface.distance())).z, .01f);
            float z = down.getEndPoint(new Vector3(), (float) Math.sqrt(through.distance())).z;
            if (tile.liquid().molten()) {
                assertEquals(BoardGeometry.waterZ(tile), z, .01f, "Lava stays opaque");
            } else {
                assertTrue(z < BoardGeometry.waterZ(tile) - 2 * BoardGeometry.level(), "The depth-3 bed lies beneath: " + z);
            }
        }
    }

    private static void probe(List<Ray> rays, BoardSurface.Face face) {
        Vector3 center = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        if (normal.isZero()) { return; }
        for (int sign : new int[] { -1, 1 }) {
            rays.add(new Ray(new Vector3(center).mulAdd(normal, sign * 2), new Vector3(normal).scl(-sign)));
        }
    }

    private static BoardScene scene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                boolean ice = x == 1 && y == 1;
                boolean water = ice || x == 3 && y >= 1 && y <= 5;
                int level = y < 3 ? 3 : x == 5 && y == 5 ? 1 : 0;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, water ? 2 : -1, ice, 0,
                      BoardScene.Surface.SAND, null, null, List.of(), List.of(),
                      water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void terrainImpactsUseThePickedMaterialAndCacheItForPlayback() {
        for (var material : BoardScene.Surface.values()) {
            for (int water : new int[] { -1, 2 }) {
                var tile = new BoardScene.Tile(new Coords(0, 0), 0, water, false, 0, material, null, List.of(),
                      List.of());
                var board = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
                var cache = new BoardSurface.Cache();
                var ray = new Ray(BoardGeometry.center(tile.coords(), 0).add(0, 0, 100), new Vector3(0, 0, -1));
                var hit = BoardGeometry.hit(board, ray, board.tiles(), BoardGeometry.floor(board), cache);
                boolean sparks = water < 0 && (material == BoardScene.Surface.ROCK || material == BoardScene.Surface.LUNAR
                      || material == BoardScene.Surface.CONCRETE);
                assertEquals(sparks, hit.hardSurface(), "The exposed material matters, including water over rock");
                var at = ray.origin.cpy().mulAdd(ray.direction, (float) Math.sqrt(hit.distance()));
                var attack = new UnitAttack(UnitPlaybackTest.attack(UnitPlaybackTest.unit(1, 1), UnitPlaybackTest.unit(2, 0),
                      megamek.common.ResolvedAttack.Kind.SHOT, false));
                var picks = new java.util.concurrent.atomic.AtomicInteger();
                attack.landscape = probe -> {
                    picks.incrementAndGet();
                    return BoardGeometry.hit(board, probe, board.tiles(), BoardGeometry.floor(board), cache);
                };
                assertEquals(sparks, attack.hardImpact(at));
                assertEquals(sparks, attack.hardImpact(at.cpy()));
                assertEquals(1, picks.get(), "An impact must not repeat terrain picking on every animation frame");
            }
        }
    }

    @Test
    void everyHexCenterPicksCorrectlyInBothViews() {
        BoardScene scene = BoardScenes.raisedScene(0);
        BoardCamera view = new BoardCamera();
        view.resize(1100, 750);
        for (boolean isometric : List.of(false, true)) {
            view.setIsometric(isometric);
            view.fit(scene);
            for (BoardScene.Tile tile : scene.tiles()) {
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                Ray ray = new Ray(new Vector3(center).mulAdd(view.camera.direction, -1000), view.camera.direction);
                assertEquals(tile.coords(), BoardGeometry.pick(scene, ray));
            }
        }
    }

    @Test
    void theTileUnderAPointIsTheHexWhoseFootprintHoldsIt() {
        BoardScene scene = BoardScenes.raisedScene(0);
        for (BoardScene.Tile tile : scene.tiles()) {
            Coords coords = tile.coords();
            // Just inside each of the six edges' midpoints, and just beyond it in the neighbour or off the board.
            for (int corner = 0; corner < 6; corner++) {
                Vector3 edge = BoardGeometry.corner(coords, 0, corner)
                      .add(BoardGeometry.corner(coords, 0, (corner + 1) % 6)).scl(.5f);
                Vector3 center = BoardGeometry.center(coords, 0);
                Vector3 inside = new Vector3(edge).lerp(center, .02f);
                Vector3 outside = new Vector3(edge).lerp(center, -.02f);
                assertEquals(tile, BoardGeometry.tile(scene, inside.x, inside.y));
                BoardScene.Tile beyond = BoardGeometry.tile(scene, outside.x, outside.y);
                assertTrue(beyond == null || !beyond.coords().equals(coords), "A point beyond an edge is elsewhere");
            }
        }
        assertNull(BoardGeometry.tile(scene, -BoardGeometry.WIDTH, BoardGeometry.HEIGHT));
    }

    @Test
    void cliffFaceBelongsToTheRaisedHexAndOutsideMisses() {
        BoardScene scene = BoardScenes.raisedScene(4);
        Coords raised = new Coords(2, 2);
        Vector3 center = BoardGeometry.center(raised, 2);
        Ray ray = new Ray(new Vector3(center).add(200, 0, 0), new Vector3(-1, 0, 0));
        assertEquals(raised, BoardGeometry.pick(scene, ray));
        assertNull(BoardGeometry.pick(scene, new Ray(new Vector3(-500, 500, 100), new Vector3(0, 0, -1))));
    }

    @Test
    void lowAnglePickingDoesNotPrepareTerrainBehindTheNearestCliff() {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 2, -1, false, 0, BoardScene.Surface.GRASS, null,
                      List.of(), List.of()));
            }
        }
        var board = new BoardScene(0, 12, 5, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(board);
        for (boolean reverse : new boolean[] { false, true }) {
            var candidates = reverse ? tiles.reversed() : tiles;
            for (int column : new int[] { 0, 11 }) {
                var target = new Coords(column, 2);
                int direction = column == 0 ? 1 : -1;
                var ray = new Ray(BoardGeometry.center(target, 1).add(-200 * direction, 0, 0), new Vector3(direction, 0, 0));
                var queried = new ArrayList<Coords>();
                var hit = BoardGeometry.hit(board, ray, candidates, floor, coords -> {
                    queried.add(coords);
                    return BoardTacticalGeometry.Surface.of(new BoardSurface(board, board.tile(coords)), board, floor);
                });
                assertEquals(target, hit.coords(), "Candidate order must not change the nearest rendered cliff");
                assertFalse(queried.contains(new Coords(11 - column, 2)), "Occluded distant surfaces must not be rebuilt");
                assertTrue(queried.size() < 6, "A nearby cliff must bound the expensive surface queries");
            }
        }
    }

    @Test
    void raisedTerrainOccludesPickingTheHexBehindIt() {
        BoardScene scene = BoardScenes.raisedScene(6);
        BoardCamera view = new BoardCamera();
        view.resize(1100, 750);
        view.setIsometric(true);
        Vector3 hidden = BoardGeometry.center(new Coords(1, 1), 0);
        Ray ray = new Ray(hidden.mulAdd(view.camera.direction, -1000), view.camera.direction);
        assertEquals(new Coords(2, 2), BoardGeometry.pick(scene, ray));
    }

    @Test
    void recessedWaterUsesRealDepthAndStillPicksItsHex() {
        for (int depth : new int[] { 0, 1, 2, 7 }) {
            BoardScene.Tile wet = new BoardScene.Tile(new Coords(0, 0), 2, depth, false, 0, BoardScene.Surface.GRASS,
                  null, List.of(), List.of());
            BoardScene scene = new BoardScene(0, 1, 1, List.of(wet), List.of(), List.of(), -1, "", List.of());
            assertTrue(BoardGeometry.groundZ(wet) < BoardGeometry.waterZ(wet));
            assertEquals(2 * BoardGeometry.LEVEL - (depth == 0 ? 2 * BoardGeometry.HEX_SCALE : depth * BoardGeometry.LEVEL),
                  BoardGeometry.groundZ(wet), 0.001f);
            Vector3 above = BoardGeometry.center(wet.coords(), 20);
            assertEquals(wet.coords(), BoardGeometry.pick(scene, new Ray(above, new Vector3(0, 0, -1))));
            assertTrue(BoardGeometry.floor(scene) < BoardGeometry.groundZ(wet));
        }
    }
}
