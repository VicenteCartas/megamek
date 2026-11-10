/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Terrain scenes and checks the board tests share. */
final class BoardScenes {
    static final Coords CENTER = new Coords(4, 4);
    /**
     * A hex of the 9 x 9 fixture wholly within one parcel (BoardFields), clear of its headland: the crop fixtures
     * stand there.
     */
    static final Coords CROPS = new Coords(6, 6);
    static final Coords RELIEF_CENTER = new Coords(2, 2);
    /** Rows are y; characters are x. Digits are land levels, r is river (level 2, depth 1), w/W lake (level 1). */
    static final String[] LAYOUT = {
          "33r22233344433",
          "43r32233444443",
          "43rr3223344333",
          "3323r432233322",
          "2222rrrr333222",
          "11221112r33222",
          "11111122rr3322",
          "1111112222r332",
          "1111111121r122",
          "111111111www22",
          "111111111wWW11",
          "1111111111ww11",
    };
    static final Coords WATER = new Coords(3, 3);
    static final Coords MESH_AT = new Coords(3, 3);
    static final Coords FIRST = new Coords(1, 1);
    static final Coords SECOND = FIRST.translated(1);

    private BoardScenes() { }

    static BoardScene.Tile tile(Coords coords, BoardScene.Surface family, int level, int depth, int road) {
        return new BoardScene.Tile(coords, level, depth, false, road, family, null, null,
              List.of(), List.of(), depth < 0 ? BoardLiquid.NONE : BoardLiquid.WATER, null, true);
    }

    static BoardScene.Tile tile(Coords coords, int family, int level) {
        var surface = family >= BoardSurfaceBlend.CRUST ? BoardScene.Surface.ROCK : BoardScene.Surface.values()[family];
        var liquid = family < BoardSurfaceBlend.CRUST ? BoardLiquid.NONE : new BoardLiquid(
              family == BoardSurfaceBlend.CRUST ? BoardLiquid.Kind.MAGMA_CRUST : BoardLiquid.Kind.MAGMA, "", 0);
        return new BoardScene.Tile(coords, level, -1, false, 0, surface, null, null,
              List.of(), List.of(), liquid, null, true);
    }

    static BoardScene scene(Function<Coords, BoardScene.Tile> tiles) {
        List<BoardScene.Tile> result = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) { result.add(tiles.apply(new Coords(x, y))); }
        }
        return new BoardScene(0, 9, 9, result, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene.Tile tile(Coords coords, BoardRoad.Kind road, int exits, int level, BoardScene.Surface family) {
        return new BoardScene.Tile(coords, level, -1, false, exits, family, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true, road);
    }

    static float maskAlpha(GpuRoads.MaskData mask, float x, float y) {
        return (maskTexel(mask, x, y) & 255) / 255f;
    }

    /** The mask's RGBA texel at a point, 0 off the mask. */
    static int maskTexel(GpuRoads.MaskData mask, float x, float y) {
        int px = (int) Math.floor((x - mask.x()) / mask.width() * mask.pixels().width());
        int py = (int) Math.floor((y - mask.y()) / mask.height() * mask.pixels().height());
        if (px < 0 || py < 0 || px >= mask.pixels().width() || py >= mask.pixels().height()) { return 0; }
        return mask.pixels().rgba(py * mask.pixels().width() + px);
    }

    static BoardScene scene(String path) {
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

    static void assertJoined(Map<Segment, Integer> boundary, Map<Segment, Integer> joined, String label) {
        List<Segment> open = boundary.entrySet().stream()
              .filter(entry -> entry.getValue() == 1 && joined.get(entry.getKey()) < 2
                    && !covered(entry.getKey(), joined))
              .map(Map.Entry::getKey).toList();
        assertTrue(open.isEmpty(), label + " must meet its adjoining cliff, rim and bank: "
              + open.stream().limit(5).toList());
    }

    static void checkSubmergedShading(BoardSurface surface) throws Exception {
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

    static void countEdges(Map<Segment, Integer> edges, List<BoardSurface.Face> faces) {
        for (BoardSurface.Face face : faces) {
            if (face.finish() == BoardSurface.Finish.OUTCROP || face.finish() == BoardSurface.Finish.DRESSING) { continue; }
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Point a = Point.of(vertices[i]), b = Point.of(vertices[(i + 1) % 3]);
                edges.merge(a.compareTo(b) < 0 ? new Segment(a, b) : new Segment(b, a), 1, Integer::sum);
            }
        }
    }

    /** A collapsed bank may join two collinear samples; compare coverage across that subdivision too. */
    static boolean covered(Segment edge, Map<Segment, Integer> joined) {
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

    record Segment(Point a, Point b) { }

    record Point(long x, long y, long z) implements Comparable<Point> {
        static Point of(Vector3 p) {
            return new Point(Math.round(p.x * 1000), Math.round(p.y * 1000), Math.round(p.z * 1000));
        }

        @Override
        public int compareTo(Point other) {
            return x != other.x ? Long.compare(x, other.x) : y != other.y ? Long.compare(y, other.y)
                  : Long.compare(z, other.z);
        }
    }

    /** Runs a check with hex transitions off or on, restoring the tuning afterwards. */
    static void withTransitions(boolean transitions, Runnable check) {
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        BoardGeometry.tune(new BoardGeometry.Tuning(previous.hexScale(), previous.unitScale(), previous.unitHeightScale(),
              previous.levelHeight(), previous.gridShade(), previous.multiHexUnitScale(), transitions));
        try {
            check.run();
        } finally {
            BoardGeometry.tune(previous);
        }
    }

    /** Runs a check with the tiles padded this many metres apart, restoring the tuning afterwards. */
    static void withPadding(float padding, Runnable check) {
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        BoardGeometry.tune(new BoardGeometry.Tuning(previous.hexScale(), previous.unitScale(), previous.unitHeightScale(),
              previous.levelHeight(), previous.gridShade(), previous.multiHexUnitScale(), false, padding));
        try {
            check.run();
        } finally {
            BoardGeometry.tune(previous);
        }
    }

    static void tune(boolean enabled) {
        var t = BoardRelief.tuning();
        BoardRelief.tune(new BoardRelief.Tuning(enabled, BoardRelief.DEFAULTS.containmentWalls()));
    }

    /** A low river with abrupt changes of bed depth and bank height, including cliff/slope junctions. */
    static BoardScene mixedDepthScene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                boolean water = x >= 1 && x <= 7 && (y == 3 || y == 4 && x >= 3 && x <= 6);
                int depth = !water ? -1 : new int[] { 1, 2, 1, 5, 2, 1, 2 }[x - 1];
                int level = water ? -2 : y < 3 ? x == 4 ? 9 : x == 5 ? -2 : x < 5 ? 1 : 3 : -2;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, BoardScene.Surface.GRASS, null,
                      null, List.of(), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene capture(Board board) throws Exception {
        var task = new FutureTask<>(() -> {
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    static BoardScene replace(BoardScene scene, BoardScene.Tile tile) {
        var tiles = new ArrayList<>(scene.tiles());
        tiles.set(tile.coords().getX() * scene.height() + tile.coords().getY(), tile);
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The board's scene as every 3D view captures it ({@link BoardTiles}). */
    static BoardScene scene(Board board) throws Exception {
        var captured = new AtomicReference<BoardScene>();
        SwingUtilities.invokeAndWait(() -> {
            BoardSceneryLayouts.convert(board);
            captured.set(new BoardScene(0, board.getWidth(), board.getHeight(),
                  BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of()));
        });
        return captured.get();
    }

    static BoardScene.Tile tile(Coords coords, BoardScene.Biome kind, int elevation) {
        var base = tile(coords, kind == BoardScene.Biome.MUD || kind == BoardScene.Biome.QUICKSAND
              ? BoardScene.Surface.DIRT : BoardScene.Surface.GRASS, elevation, -1, 0);
        return new BoardScene.Tile(coords, elevation, -1, false, 0, base.surface(), null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE, kind);
    }

    /** A field hex's plants of both crops, one after the other; empty where none grows. */
    static FloatArray cropPlants(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardDecals.Opacity paint) {
        var plants = new FloatArray();
        for (var crop : BoardFields.Crop.values()) {
            var roots = GpuBiomeVegetation.plantStalks(scene, tile, surface, paint, crop);
            if (roots != null) { plants.addAll(roots); }
        }
        return plants;
    }

    static BoardScene reliefScene(int centerLevel, boolean detailed, BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                boolean center = x == RELIEF_CENTER.getX() && y == RELIEF_CENTER.getY();
                tiles.add(new BoardScene.Tile(new Coords(x, y), center ? centerLevel : 0, -1, false, 0, family, null,
                      null, List.of(), List.of(), BoardLiquid.NONE, null, center ? detailed : true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static void tune(float grid, boolean transitions) {
        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, grid, BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE,
              transitions, BoardGeometry.DEFAULT_PADDING));
    }

    /** A paved waterfront, including an inlet that must stay open and buildings whose foundations stay intact. */
    static BoardScene quayScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 11; y++) {
                boolean land = x >= 4 && y >= 2 && y <= 8 || x >= 6 && y >= 1 && y <= 9;
                land &= !(x == 4 && y == 3);
                boolean building = land && (x == 4 && y == 5 || x == 5 && y == 7);
                List<BoardScene.Feature> features = building
                      ? List.of(new BoardScene.Feature("buildings/standard/building-hard",
                            0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                Hex hex = new Hex(0);
                if (family == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                if (building) { hex.addTerrain(new Terrain(Terrains.BUILDING, 4)); }
                boolean detailed = BoardFeatures.detailedGround(hex, building
                      ? Map.of(Terrains.BUILDING, features.getFirst().asset()) : Map.of());
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, land ? -1 : 2, false, 0,
                      land ? family : BoardScene.Surface.GRASS, null, null, features, List.of(),
                      land ? BoardLiquid.NONE : BoardLiquid.WATER, null, detailed));
            }
        }
        return new BoardScene(0, 9, 11, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** A diagonal and a vertical paved finger connected to a broad waterfront. */
    static BoardScene dockScene() {
        Set<Coords> dock = new HashSet<>();
        Coords at = new Coords(8, 7);
        for (int i = 0; i < 6; i++) { dock.add(at); at = at.translated(5); }
        at = new Coords(10, 6);
        for (int i = 0; i < 5; i++) { dock.add(at); at = at.translated(0); }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 12; y++) {
                Coords coords = new Coords(x, y);
                boolean land = x >= 8 && y >= 6 || dock.contains(coords);
                tiles.add(new BoardScene.Tile(coords, 0, land ? -1 : 2, false, 0,
                      land ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, null, null,
                      List.of(), List.of(), land ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 14, 12, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The reported broad junctions and triangular terrain islands; buildings retain their protected hexes. */
    static BoardScene pavedMapScene(int number) {
        Board board = BoardFile.loadSheet(new File("data/boards/Deserts/16x17 AeroBase " + number + ".board"), false, true);
        BoardScene.Pixels pixels = groundPixels();
        Map<Integer, String> models = Map.of(Terrains.BUILDING, "buildings/standard/building-hard");
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                Map<Integer, String> selected = hex.containsTerrain(Terrains.BUILDING) ? models : Map.of();
                tiles.add(new BoardScene.Tile(at, hex.getLevel(), -1, false, 0, BoardFeatures.surface(hex), null, null,
                      BoardFeatures.capture(hex, at, selected), List.of(),
                      BoardLiquid.NONE, null, BoardFeatures.detailedGround(hex, selected)));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Parallel streams drop one, two and three surface levels, from left to right. */
    static BoardScene dropScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                int level = y < 3 ? x / 3 + 1 : 0;
                int depth = x % 3 == 1 ? 1 : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, null, null, List.of(),
                      List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The printed reference's wrapped peninsulas, raised banks and depth-zero shallows. */
    static BoardScene mapScene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        Board board = BoardFile.loadSheet(new File("data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"), false, true);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = board.getHex(x, y);
                int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), hex.getLevel(), depth, false, 0, family, null, null,
                      List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Every hex, water included, carries the family, as the game gives a desert map's water hexes its theme. */
    static BoardScene scene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                int level = switch (cell) {
                    case 'r' -> 2;
                    case 'w', 'W' -> 1;
                    default -> cell - '0';
                };
                int depth = switch (cell) {
                    case 'r', 'w' -> 1;
                    case 'W' -> 2;
                    default -> -1;
                };
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, null, null, List.of(),
                      List.of(),
                      depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels groundPixels() {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        return new BoardScene.Pixels(image);
    }

    static BoardScene.Tile detailTile(Coords coords, BoardScene.Surface family, int level, int depth, int road,
          List<BoardScene.Feature> features) {
        return new BoardScene.Tile(coords, level, depth, false, road, family, null, null,
              features, List.of(), depth < 0 ? BoardLiquid.NONE : BoardLiquid.WATER, null, road == 0);
    }

    static BoardScene shores(int depth, boolean mouths) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                BoardScene.Surface family = BoardScene.Surface.GRASS;
                boolean wet = coords.equals(WATER);
                int road = 0;
                for (int e = 0; e < 6; e++) {
                    if (coords.equals(WATER.translated(BoardGeometry.edgeDirection(e)))) {
                        family = BoardScene.Surface.values()[e];
                        wet |= mouths && (e == 1 || e == 4);
                        road = e == 0 ? 9 : 0;
                    }
                }
                tiles.add(detailTile(coords, family, 0, wet ? depth : -1, road, List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene rough(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = roughHex(family, y < 3 ? 1 : 2);
                tiles.add(detailTile(coords, family, x < 2 ? 0 : 1, -1, 0, BoardFeatures.capture(hex, coords, Map.of())));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene isolatedRough(BoardScene.Surface family, int elevation) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                boolean center = coords.equals(new Coords(2, 2));
                tiles.add(detailTile(coords, family, center ? Math.max(0, elevation) : Math.max(0, -elevation), -1, 0,
                      center ? BoardFeatures.capture(roughHex(family, 1), coords, Map.of()) : List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static Hex roughHex(BoardScene.Surface family, int level) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.ROUGH, level));
        switch (family) {
            case SAND -> hex.addTerrain(new Terrain(Terrains.SAND, 1));
            case SNOW -> hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            case CONCRETE -> hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            case DIRT -> hex.setTheme("dirt");
            case ROCK -> hex.setTheme("rock");
            case LUNAR -> hex.setTheme("lunar");
            case FUNGUS -> hex.setTheme("fungus");
            case DESERT -> hex.setTheme("desert");
            case MARS -> hex.setTheme("mars");
            case VOLCANO -> hex.setTheme("volcano");
            case TROPICAL -> hex.setTheme("tropical");
            default -> { }
        }
        return hex;
    }

    static BoardScene meshScene(BoardScene.Surface family, int level) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                tiles.add(new BoardScene.Tile(at, at.equals(MESH_AT) ? level : 0, -1, false, 0, family, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene roadScene(boolean river) {
        return roadScene(river, 1, true, true, 2);
    }

    static BoardScene roadScene(boolean river, int direction, boolean firstRoad, boolean secondRoad, int elevation) {
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                Coords coords = new Coords(x, y);
                boolean first = coords.equals(FIRST), second = coords.equals(FIRST.translated(direction));
                int exits = river ? 0 : first && firstRoad ? 1 << direction
                      : second && secondRoad ? 1 << ((direction + 3) % 6) : 0;
                boolean wet = river && (first || second);
                // A road's exits come with its drawn road, as a captured hex has them: only a carriageway is graded.
                tiles.add(new BoardScene.Tile(coords, !river && first ? elevation : 0, wet ? 2 : -1, false, exits,
                      BoardScene.Surface.GRASS, null, null, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, false,
                      exits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED));
            }
        }
        return new BoardScene(0, 4, 4, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Level distance from p to the segment from a to b. */
    static float distance(Vector3 p, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float t = Math.clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
        return (float) Math.hypot(a.x + dx * t - p.x, a.y + dy * t - p.y);
    }

    static BoardScene riverScene(Coords center, int mask) {
        Map<Coords, Integer> levels = new HashMap<>();
        levels.put(center, 2);
        for (int direction = 0; direction < 6; direction++) {
            if ((mask & (1 << direction)) != 0) {
                levels.put(center.translated(direction), 0);
            }
        }
        return riverScene(levels, false);
    }

    static BoardScene riverScene(Map<Coords, Integer> levels, boolean frozen) {
        var art = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        int width = Math.max(5, levels.keySet().stream().mapToInt(Coords::getX).max().orElseThrow() + 2);
        int height = Math.max(5, levels.keySet().stream().mapToInt(Coords::getY).max().orElseThrow() + 2);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, levels.getOrDefault(coords, 0), levels.containsKey(coords) ? 1 : -1,
                      frozen && levels.containsKey(coords), 0, BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /**
     * A square board of sand with detailed ground, {@code size} hexes a side: hexes at {@code land} unless levels says
     * otherwise, dry unless depths gives them water.
     */
    static BoardScene sandScene(int size, int land, Map<Coords, Integer> levels, Map<Coords, Integer> depths,
          boolean frozen) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                Coords coords = new Coords(x, y);
                boolean wet = depths.containsKey(coords);
                tiles.add(new BoardScene.Tile(coords, levels.getOrDefault(coords, land), wet ? depths.get(coords) : -1,
                      frozen && wet, 0, BoardScene.Surface.SAND, null, null, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene raisedScene(int raisedLevel) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), x == 2 && y == 2 ? raisedLevel : 0, -1, false, 0,
                      BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "Movement", List.of());
    }

    /** The shipped board as a game shows it: converted where it loads, as every load site does. */
    static BoardScene installedScene(String path) throws Exception {
        return installedScene(path, board -> { });
    }

    /** The shipped board after {@code edit}, as an editor would change it, then converted and shown. */
    static BoardScene installedScene(String path, Consumer<Board> edit) throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/" + path));
        edit.accept(board);
        BoardSceneryLayouts.convert(board);
        var installedScene = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                installedScene.set(fixture.source.takeFrame().scene());
            });
        }
        return installedScene.get();
    }

    /** The ground's blended triangles as drawn: the plan's groups and its tops, which the material cache can hold. */
    static List<GpuSurfaceBlend.Triangle> blendedTriangles(GpuTerrain.SculptPlan plan) {
        List<GpuSurfaceBlend.Triangle> triangles = new ArrayList<>();
        plan.blended().values().forEach(triangles::addAll);
        plan.blendedTops().values().forEach(triangles::addAll);
        return triangles;
    }

    static BoardScene minesScene() throws Exception {
        return BoardScenes.installedScene("Deserts/16x17 Mines 1.board");
    }
}
