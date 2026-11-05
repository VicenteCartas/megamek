/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared cliff corners, rims and feet on the shipped map, including the artwork-covered bunker. */
class BoardCliffSeamTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    private static BoardScene scene(String path) {
        return scene(new File("data/boards/" + path));
    }

    static BoardScene scene(File file) {
        Board board = new Board();
        board.load(file);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var pixels = new BoardScene.PixelPool();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(board.getHex(at), artwork, null, pixels, board::getHex));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void adjoiningCliffsShareTheirBoundaries(boolean transitions) {
        BoardSculptTest.withTransitions(transitions, BoardCliffSeamTest::checkBoundaries);
    }

    @Test
    void commCenterBoardCutNormalsStaySeparateFromTheRoadAbove() throws Exception {
        BoardScene scene = capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
        for (Coords at : List.of(new Coords(0, 2), new Coords(0, 3))) {
            for (TerrainLod lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                int checked = 0;
                for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                    if (scene.tile(at.translated(BoardGeometry.edgeDirection(face.landEdge()))) != null) { continue; }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(surface.relief.shade(point).normal().epsilonEquals(normal, .0001f),
                              "The map cut must not inherit upward road normals at " + at + ", " + lod + ": " + point);
                    }
                    checked++;
                }
                assertTrue(checked > 0, "Exercise the exposed board cut beside the road");
            }
        }
    }

    @ParameterizedTest(name = "quay {0}")
    @ValueSource(strings = { "Templates/SeaPort.board", "Map Set 7/16x17 Seaport.board" })
    void seaportQuayPanelsStayClosedAndFaceTheWater(String path) throws Exception {
        BoardScene scene = capturedScene(path);
        for (TerrainLod lod : TerrainLod.values()) {
            Map<Segment, Integer> joined = new HashMap<>();
            Map<Coords, List<BoardSurface.Face>> quays = new HashMap<>();
            for (var tile : scene.tiles()) {
                var surface = new BoardSurface(scene, tile, lod);
                countEdges(joined, surface.faces);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                countEdges(joined, walls);
                if (!tile.liquid().present() && tile.surface() == BoardScene.Surface.CONCRETE) {
                    for (var face : walls) {
                        if (face.landEdge() < 0) { continue; }
                        var water = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                        if (water == null || !water.liquid().present()) { continue; }
                        for (var vertex : List.of(face.a(), face.b(), face.c())) {
                            assertTrue(vertex.z >= water.elevation() * BoardGeometry.level() - .001f,
                                  "Only the basin owns the submerged quay: " + tile.coords() + " beside " + water.coords()
                                        + ", " + lod + ": " + vertex);
                        }
                    }
                }
                if (!tile.liquid().present()) { continue; }
                List<BoardSurface.Face> panels = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.WALL
                      && face.landEdge() >= 0 && BoardConcrete.concreteBank(scene, tile, face.landEdge())).toList();
                if (!panels.isEmpty()) {
                    quays.put(tile.coords(), panels);
                    checkSubmergedShading(surface);
                }
                Vector3 center = BoardGeometry.center(tile.coords(), 0);
                for (var face : panels) {
                    for (var vertex : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(BoardRelief.Kind.SUBMERGED_CLIFF, surface.relief.shade(vertex).kind(),
                              "The complete panel keeps underwater shading at " + tile.coords() + ", " + lod);
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    Vector3 towardWater = new Vector3(center).sub(face.a());
                    towardWater.z = 0;
                    assertTrue(normal.dot(towardWater) > 0,
                          "Quay panel faces the water at " + tile.coords() + ", " + lod + ": " + face);
                }
            }
            assertFalse(quays.isEmpty());
            for (var quay : quays.entrySet()) {
                Map<Segment, Integer> boundary = new HashMap<>();
                countEdges(boundary, quay.getValue());
                assertJoined(boundary, joined, "SeaPort quay " + quay.getKey() + ", " + lod);
            }
        }
    }

    @Test
    void seaportApronFitsBothSides() throws Exception {
        BoardScene scene = capturedScene("Map Set 7/16x17 Seaport.board");
        BoardConcrete shape = BoardConcrete.of(scene);
        Vector3 a = shape.corner(new Coords(2, 1), 3), b = shape.corner(new Coords(5, 2), 5);
        Vector3 along = new Vector3(b).sub(a).nor();
        Vector3 back = shape.corner(new Coords(2, 1), 2);
        for (int x = 2; x <= 5; x++) {
            Coords coords = new Coords(x, x / 2);
            for (int k = 3; k <= 5; k++) {
                Vector3 offset = shape.corner(coords, k).sub(a);
                assertEquals(0, along.x * offset.y - along.y * offset.x, .003f,
                      "The northwest quay stays straight beside inland buildings: " + coords + " corner " + k);
            }
            for (int k = 0; k <= 2; k++) {
                Vector3 offset = shape.corner(coords, k).sub(back);
                assertEquals(0, along.x * offset.y - along.y * offset.x, .003f,
                      "The inland side stays straight and parallel to the quay: " + coords + " corner " + k);
            }
        }
    }

    static BoardScene capturedScene(String path) throws Exception {
        FutureTask<BoardScene> capture = new FutureTask<>(() -> {
            // The map preview's own load, which converts the board's legacy scenery.
            var board = GpuBoardWindow.loadPreview(new File("data/boards/" + path));
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(capture);
        return capture.get();
    }

    @Test
    void commCenterBoardCutsMeetEverySlope() throws Exception {
        BoardScene scene = capturedScene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        for (TerrainLod lod : TerrainLod.values()) {
            Map<Segment, Integer> joined = new HashMap<>();
            Map<String, List<BoardSurface.Face>> cuts = new HashMap<>();
            for (var tile : scene.tiles()) {
                Coords at = tile.coords();
                if (at.getX() > 1 && at.getX() < scene.width() - 2 && at.getY() > 1 && at.getY() < scene.height() - 2) { continue; }
                var surface = new BoardSurface(scene, tile, lod);
                countEdges(joined, surface.faces);
                var walls = surface.walls(scene, floor);
                countEdges(joined, walls);
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
                Map<Segment, Integer> boundary = new HashMap<>();
                countEdges(boundary, cut.getValue());
                boundary.keySet().removeIf(edge -> edge.a.z == bottom && edge.b.z == bottom);
                assertJoined(boundary, joined, "CommCenter map-edge slope at " + cut.getKey() + ", " + lod);
            }
        }
    }

    @Test
    void submergedCliffsJoinBothTheRiverbedAndOrdinaryBanks() throws Exception {
        var original = BoardRelief.tuning();
        try {
            BoardWetCliffTest.tune(true);
            BoardScene scene = scene("Map Pack Savannahs/16x17 Mountain Lake (Savannah).board");
            Map<Segment, Integer> joined = new HashMap<>();
            List<BoardSurface.Face> submerged = new ArrayList<>();
            Map<String, List<BoardSurface.Face>> upper = new HashMap<>();
            Coords center = new Coords(8, 14);
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(center) > 3) { continue; }
                var surface = new BoardSurface(scene, tile);
                countEdges(joined, surface.faces);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                countEdges(joined, walls);
                if (tile.coords().distance(center) <= 1 && tile.liquid().present()) {
                    checkSubmergedShading(surface);
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
            Map<Segment, Integer> boundary = new HashMap<>();
            countEdges(boundary, submerged);
            assertFalse(boundary.isEmpty());
            assertJoined(boundary, joined, "Submerged cliff");
            for (var entry : upper.entrySet()) {
                Map<Segment, Integer> edge = new HashMap<>();
                countEdges(edge, entry.getValue());
                assertJoined(edge, joined, entry.getKey());
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static void checkSubmergedShading(BoardSurface surface) throws Exception {
        var encode = GpuTerrain.class.getDeclaredMethod("sculptVertex", Vector3.class, BoardRelief.Shade.class,
              float.class, BoardSurface.class);
        encode.setAccessible(true);
        for (var face : surface.faces) {
            for (var point : List.of(face.a(), face.b(), face.c())) {
                var shade = surface.relief.shade(point);
                if (shade == null || point.z >= BoardGeometry.waterZ(surface.tile)) { continue; }
                boolean rock = shade.kind() == BoardRelief.Kind.ROCK;
                if (!rock && shade.kind() != BoardRelief.Kind.GROUND && shade.kind() != BoardRelief.Kind.SUBMERGED_CLIFF) { continue; }
                var vertex = (MeshPartBuilder.VertexInfo) encode.invoke(null, point, shade, 0f, surface);
                int packed = Float.floatToRawIntBits(vertex.color.toFloatBits());
                int blue = packed >>> 16 & 255;
                assertTrue((packed >>> 24 & 255) < 64 && (rock ? blue >= 224 && blue < 255 : blue < 32),
                      "Submerged rock and bank vertices must both enable the water optics: " + point);
                float waterLevel = (packed >>> 8 & 255) - 64 + (rock ? (blue - 224) / 30f : blue * (8f / 255));
                assertEquals(BoardGeometry.waterZ(surface.tile),
                      waterLevel * BoardGeometry.LEVEL - BoardGeometry.HEX_SCALE, .01f,
                      "The bank and cliff must share the real water level, not their own submerged height: " + point);
            }
        }
    }

    private static void checkBoundaries() {
        BoardScene scene = scene("GrassLands/16x17 Grasslands River CommCenter.board");
        float floor = BoardGeometry.floor(scene);
        Map<Segment, Integer> joined = new HashMap<>();
        Map<Coords, List<BoardSurface.Face>> cliffs = new HashMap<>();
        // The cliffs at 1111 and 1112 and all the adjoining ground, banks and walls.
        for (int x = 9; x <= 11; x++) {
            for (int y = 9; y <= 12; y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> walls = surface.walls(scene, floor);
                countEdges(joined, surface.faces);
                countEdges(joined, walls);
                if (x == 10 && (y == 10 || y == 11)) { cliffs.put(tile.coords(), walls); }
            }
        }
        for (var cliff : cliffs.entrySet()) {
            for (int e = 0; e < 6; e++) {
                if (!scene.tile(cliff.getKey().translated(BoardGeometry.edgeDirection(e))).liquid().present()) {
                    continue;
                }
                int edge = e;
                Map<Segment, Integer> boundary = new HashMap<>();
                countEdges(boundary, cliff.getValue().stream().filter(face -> face.landEdge() == edge).toList());
                assertFalse(boundary.isEmpty(), "The exposed cliff must exist");
                assertJoined(boundary, joined, cliff.getKey() + " edge " + edge);
            }
        }
    }

    private static void assertJoined(Map<Segment, Integer> boundary, Map<Segment, Integer> joined, String label) {
        List<Segment> open = boundary.entrySet().stream()
              .filter(entry -> entry.getValue() == 1 && joined.get(entry.getKey()) < 2
                    && !covered(entry.getKey(), joined))
              .map(Map.Entry::getKey).toList();
        assertTrue(open.isEmpty(), label + " must meet its adjoining cliff, rim and bank: "
              + open.stream().limit(5).toList());
    }

    /** Continuous coverage also accepts a flat panel meeting several collinear rock segments. */
    static void assertClosed(List<BoardSurface.Face> faces, float floor, String label) {
        Map<Segment, Integer> edges = new HashMap<>();
        countEdges(edges, faces);
        long bottom = Math.round(floor * 1000);
        var open = edges.entrySet().stream().filter(entry -> entry.getValue() == 1)
              .map(Map.Entry::getKey).filter(e -> e.a.z != bottom || e.b.z != bottom)
              .filter(e -> !covered(e, edges)).toList();
        assertTrue(open.isEmpty(), label + " must close every boundary: " + open.stream().limit(8).toList());
    }

    private record Point(long x, long y, long z) implements Comparable<Point> {
        static Point of(Vector3 p) {
            return new Point(Math.round(p.x * 1000), Math.round(p.y * 1000), Math.round(p.z * 1000));
        }

        @Override
        public int compareTo(Point other) {
            return x != other.x ? Long.compare(x, other.x) : y != other.y ? Long.compare(y, other.y)
                  : Long.compare(z, other.z);
        }
    }

    private record Segment(Point a, Point b) { }

    /** A collapsed bank may join two collinear samples; compare coverage across that subdivision too. */
    private static boolean covered(Segment edge, Map<Segment, Integer> joined) {
        double dx = edge.b.x - edge.a.x, dy = edge.b.y - edge.a.y, dz = edge.b.z - edge.a.z;
        double length2 = dx * dx + dy * dy + dz * dz;
        double tolerance = 2 / Math.sqrt(length2); // Two thousandths of a world unit.
        List<double[]> spans = new ArrayList<>();
        for (Segment other : joined.keySet()) {
            if (other.equals(edge)) { continue; }
            double ax = other.a.x - edge.a.x, ay = other.a.y - edge.a.y, az = other.a.z - edge.a.z;
            double bx = other.b.x - edge.a.x, by = other.b.y - edge.a.y, bz = other.b.z - edge.a.z;
            double a = (ax * dx + ay * dy + az * dz) / length2;
            double b = (bx * dx + by * dy + bz * dz) / length2;
            if (Math.abs(b - a) < 1e-12) { continue; }
            double low = Math.max(0, Math.min(a, b)), high = Math.min(1, Math.max(a, b));
            if (low >= high) { continue; }
            boolean collinear = true;
            // Compare only the overlapping span: extending a short quantized edge all the way to the far end
            // of a large slab amplifies its rounding error and falsely reports a gap.
            for (double t : new double[] { low, high }) {
                double u = (t - a) / (b - a);
                double rx = ax + (bx - ax) * u - t * dx;
                double ry = ay + (by - ay) * u - t * dy;
                double rz = az + (bz - az) * u - t * dz;
                collinear &= rx * rx + ry * ry + rz * rz <= 4;
            }
            if (collinear) { spans.add(new double[] { low, high }); }
        }
        spans.sort(java.util.Comparator.comparingDouble(span -> span[0]));
        double end = 0;
        for (double[] span : spans) {
            if (span[0] > end + tolerance) { return false; }
            end = Math.max(end, span[1]);
            if (end >= 1 - tolerance) { return true; }
        }
        return false;
    }

    private static void countEdges(Map<Segment, Integer> edges, List<BoardSurface.Face> faces) {
        for (BoardSurface.Face face : faces) {
            if (face.finish() == BoardSurface.Finish.OUTCROP || face.finish() == BoardSurface.Finish.DRESSING) { continue; }
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Point a = Point.of(vertices[i]), b = Point.of(vertices[(i + 1) % 3]);
                edges.merge(a.compareTo(b) < 0 ? new Segment(a, b) : new Segment(b, a), 1, Integer::sum);
            }
        }
    }
}
