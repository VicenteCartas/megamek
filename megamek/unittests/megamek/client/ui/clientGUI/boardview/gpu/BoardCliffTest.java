/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared cliff corners, rims and feet on the shipped map, including the artwork-covered bunker. */
class BoardCliffTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void adjoiningCliffsShareTheirBoundaries(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardCliffTest::checkBoundaries);
    }

    @Test
    void submergedCliffsJoinBothTheRiverbedAndOrdinaryBanks() throws Exception {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            BoardScene scene = BoardScenes.scene("Map Pack Savannahs/16x17 Mountain Lake (Savannah).board");
            Map<BoardScenes.Segment, Integer> joined = new HashMap<>();
            List<BoardSurface.Face> submerged = new ArrayList<>();
            Map<String, List<BoardSurface.Face>> upper = new HashMap<>();
            Coords center = new Coords(8, 14);
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(center) > 3) { continue; }
                var surface = new BoardSurface(scene, tile);
                BoardScenes.countEdges(joined, surface.faces);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                BoardScenes.countEdges(joined, walls);
                if (tile.coords().distance(center) <= 1 && tile.liquid().present()) {
                    BoardScenes.checkSubmergedShading(surface);
                    submerged.addAll(surface.faces.stream()
                          .filter(face -> face.finish() == BoardSurface.Finish.WALL).toList());
                    for (var face : surface.faces) {
                        if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                        for (var p : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(BoardRelief.Kind.GROUND, surface.relief.shade(p).kind(),
                                  "A bank touching the cliff must retain its own ground material: " + p);
                        }
                    }
                } else if (!tile.liquid().present()) {
                    for (var face : walls) {
                        if (face.landEdge() < 0) { continue; }
                        var next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                        if (next != null && next.liquid().present() && next.coords().distance(center) <= 1) {
                            upper.computeIfAbsent(tile.coords() + " edge " + face.landEdge(), key -> new ArrayList<>())
                                  .add(face);
                        }
                    }
                }
            }
            Map<BoardScenes.Segment, Integer> boundary = new HashMap<>();
            BoardScenes.countEdges(boundary, submerged);
            assertFalse(boundary.isEmpty());
            BoardScenes.assertJoined(boundary, joined, "Submerged cliff");
            for (var entry : upper.entrySet()) {
                Map<BoardScenes.Segment, Integer> edge = new HashMap<>();
                BoardScenes.countEdges(edge, entry.getValue());
                BoardScenes.assertJoined(edge, joined, entry.getKey());
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static void checkBoundaries() {
        BoardScene scene = BoardScenes.scene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        Map<BoardScenes.Segment, Integer> joined = new HashMap<>();
        Map<Coords, List<BoardSurface.Face>> cliffs = new HashMap<>();
        // The cliffs at 1111 and 1112 and all the adjoining ground, banks and walls.
        for (int x = 9; x <= 11; x++) {
            for (int y = 9; y <= 12; y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> walls = surface.walls(scene, floor);
                BoardScenes.countEdges(joined, surface.faces);
                BoardScenes.countEdges(joined, walls);
                if (x == 10 && (y == 10 || y == 11)) { cliffs.put(tile.coords(), walls); }
            }
        }
        for (var cliff : cliffs.entrySet()) {
            for (int e = 0; e < 6; e++) {
                if (!scene.tile(cliff.getKey().translated(BoardGeometry.edgeDirection(e))).liquid().present()) {
                    continue;
                }
                int edge = e;
                Map<BoardScenes.Segment, Integer> boundary = new HashMap<>();
                BoardScenes.countEdges(boundary, cliff.getValue().stream().filter(face -> face.landEdge() == edge).toList());
                assertFalse(boundary.isEmpty(), "The exposed cliff must exist");
                BoardScenes.assertJoined(boundary, joined, cliff.getKey() + " edge " + edge);
            }
        }
    }

    private static final Coords CENTER = new Coords(3, 3);

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void onlyMarkedDirectionsUseCliffsAndKeepTheirActualHeights(int levels) {
        BoardScenes.withTransitions(true, () -> {
            for (Coords upper : List.of(CENTER, new Coords(2, 2))) {
                for (int mask : new int[] { 21, 42 }) {
                    BoardScene scene = island(upper, levels, mask);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(upper));
                    List<BoardSurface.Face> walls = surface.walls(scene, BoardGeometry.floor(scene));
                    for (int direction = 0; direction < 6; direction++) {
                        boolean cliff = (mask & (1 << direction)) != 0;
                        Coords lower = upper.translated(direction);
                        int edge = Math.floorMod(1 - direction, 6);
                        BoardSurface foot = new BoardSurface(scene, scene.tile(lower));
                        assertEquals(!cliff, foot.relief.slope((edge + 3) % 6), "Direction " + direction);
                        var points = walls.stream().filter(f -> f.landEdge() == edge)
                              .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                        assertFalse(points.isEmpty());
                        assertEquals(0, points.stream().mapToDouble(p -> p.z).min().orElseThrow(), .001);
                        assertEquals(levels * BoardGeometry.level(),
                              points.stream().mapToDouble(p -> p.z).max().orElseThrow(), .001);
                        double rock = points.stream().map(surface.relief::shade)
                              .filter(s -> s != null && s.kind() == BoardRelief.Kind.CLIFF)
                              .mapToDouble(BoardRelief.Shade::level).max().orElseThrow();
                        assertEquals(cliff, rock > .99, "Marked edges expose rock; other edges retain soil slopes");
                    }
                    Vector3 anchor = BoardGeometry.center(upper, levels);
                    assertEquals(anchor.z, surface.height(anchor.x, anchor.y), .001f);
                    assertEquals(upper, BoardGeometry.pick(scene,
                          new Ray(new Vector3(anchor.x, anchor.y, 500), new Vector3(0, 0, -1))));
                }
            }
        });
    }

    @Test
    void exitsMustBeSpecifiedAndBelongToTheHigherHex() {
        Hex hex = new Hex(1);
        hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, false, 63));
        assertEquals(0, capture(hex, CENTER).cliffTopExits());
        hex.addTerrain(new Terrain("cliff_top:1:56"));
        var tile = capture(hex, CENTER);
        assertEquals(56, tile.cliffTopExits());
        assertEquals(56, tile.withText(List.of()).cliffTopExits());
        BoardScenes.withTransitions(true, () -> {
            BoardScene scene = island(CENTER, -1, 63);
            var surface = new BoardSurface(scene, scene.tile(CENTER));
            for (int edge = 0; edge < 6; edge++) {
                assertTrue(surface.relief.slope(edge), "An exit on the low side must not turn the upper edge into a cliff");
            }
        });
    }

    @Test
    void editingOnlyExitsRebuildsTheCliffAndItsNeighborSupport() {
        BoardScenes.withTransitions(true, () -> {
            BoardScene before = island(CENTER, 1, 0);
            BoardScene after = island(CENTER, 1, 1);
            assertFalse(before.tile(CENTER).sameGeometry(after.tile(CENTER)));
            var cache = new BoardSurface.Cache();
            for (Coords at : List.of(CENTER, CENTER.translated(0))) {
                var slope = cache.get(before, before.tile(at));
                var cliff = cache.get(after, after.tile(at));
                assertNotSame(slope, cliff);
                assertNotEquals(slope.faces, cliff.faces, "Exit edits change the shared rim and foot");
                assertEquals(slope.faces, cache.get(before, before.tile(at)).faces, "Removing exits restores the slope");
            }
            Coords farther = CENTER.translated(0, 2);
            assertNotEquals(BoardSurface.geometryKey(before, before.tile(farther)),
                  BoardSurface.geometryKey(after, after.tile(farther)), "Shore and corner dependencies include distant exits");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mixedCliffsAndSlopesStayClosedAtEveryDetail(boolean transitions) {
        BoardScenes.withTransitions(transitions, () -> {
            for (boolean concrete : new boolean[] { false, true }) {
                BoardScene scene = island(CENTER, 1, 56, concrete);
                float floor = BoardGeometry.floor(scene);
                for (TerrainLod lod : TerrainLod.values()) {
                    List<BoardSurface.Face> faces = new ArrayList<>();
                    for (var tile : scene.tiles()) {
                        var surface = new BoardSurface(scene, tile, lod);
                        faces.addAll(surface.faces);
                        faces.addAll(surface.walls(scene, floor));
                    }
                    BoardScenes.assertClosed(faces, floor, "Authored cliffs " + lod + " concrete=" + concrete);
                }
            }
        });
    }

    @Test
    void referenceBoardUsesAuthoredDryAndWatersideCliffs() {
        Board board = new Board();
        board.load(new File("testresources/megamek/client/ui/clientGUI/boardview/gpu/cliffs.board"));
        BoardScene scene = capture(board);
        assertEquals(56, scene.tile(new Coords(8, 12)).cliffTopExits());
        assertEquals(24, scene.tile(new Coords(9, 12)).cliffTopExits());
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            BoardScenes.withTransitions(true, () -> {
                for (Coords at : List.of(new Coords(8, 12), new Coords(9, 12))) {
                    for (int direction = 0; direction < 6; direction++) {
                        if ((scene.tile(at).cliffTopExits() & (1 << direction)) == 0) { continue; }
                        var lower = scene.tile(at.translated(direction));
                        assertEquals(-1, lower.elevation());
                        int edge = Math.floorMod(1 - (direction + 3), 6);
                        var surface = new BoardSurface(scene, lower);
                        assertFalse(surface.relief.slope(edge), "The reference one-level drop must be a cliff");
                        if (lower.water()) {
                            assertTrue(surface.relief.wetCliff(edge));
                            assertTrue(surface.faces.stream().anyMatch(f -> f.finish() == BoardSurface.Finish.WALL
                                  && f.landEdge() == edge), "The waterside cliff continues down to the bed");
                        }
                    }
                }
                var ordinary = new BoardSurface(scene, scene.tile(new Coords(7, 11)));
                assertTrue(ordinary.relief.slope(0), "The unmarked edge from 0912 to 0812 stays a slope");
            });
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static BoardScene island(Coords upper, int levels, int exits) {
        return island(upper, levels, exits, false);
    }

    private static BoardScene island(Coords upper, int levels, int exits, boolean concrete) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(at.equals(upper) ? levels : 0);
                if (concrete) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                if (at.equals(upper)) { hex.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, exits)); }
                tiles.add(capture(hex, at));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene capture(Board board) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                tiles.add(capture(board.getHex(at), at));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile capture(Hex hex, Coords at) {
        var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
        return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
    }

    /** Read actual generated faces before considering a lighting workaround for the reported dark chips. */
    @org.junit.jupiter.api.Test
    void exportActualMinesAndBoxCanyonCliffFrames() throws Exception {
        var boards = java.util.Map.of("mines", "Deserts/16x17 Mines 1.board",
              "box-canyon", "Map Pack Savannahs/16x17 Box Canyon (Savannah).board");
        var reports = new java.util.LinkedHashMap<String, Object>();
        for (var board : boards.entrySet()) {
            var scene = BoardScenes.installedScene(board.getValue());
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
