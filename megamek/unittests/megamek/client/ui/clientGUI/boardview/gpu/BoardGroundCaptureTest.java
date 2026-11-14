/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.tileset.TilesetManager;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real artwork capture must not let cosmetic terrain transitions replace native ground materials. */
class BoardGroundCaptureTest {
    /** SeaPort's structured pavement: the hexes the board stored as pavement 4 before that alias became pavement 1. */
    private static final Set<String> STRUCTURED = Set.of("0601", "0801", "0901", "0602", "0702", "0902", "0603", "0707", "0807",
          "1007", "1207", "0708", "1310", "1311", "1411", "1412", "1313", "1314", "1315", "1316", "1317");

    @Test
    void seaportStructuredPavementKeepsNativeConcreteWithoutPaintedGroundDecals() throws Exception {
        onEdt(() -> {
            // The map preview's own load, which converts the board's legacy scenery.
            var board = GpuBoardWindow.loadPreview(new File("data/boards/Templates/SeaPort.board"));
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null);
                  var original = new HexTileset(new File(Configuration.dataDir(), "models/board/classic"))) {
                original.loadFromFile("tileset/classic.tileset");
                var scene = source.takeFrame().scene();
                int structured = 0;
                for (var tile : scene.tiles()) {
                    if (!STRUCTURED.contains(tile.coords().getBoardNum())) { continue; }
                    structured++;
                    assertEquals(BoardScene.Surface.CONCRETE, tile.surface(), tile.coords().toString());
                    assertEquals(1, tile.groundCover().concrete(), "Natural transitions stay beneath paving at " + tile.coords());
                    assertNotNull(tile.tileset(), "Tactical View keeps the authored pavement at " + tile.coords());
                }
                assertEquals(21, structured, "Exercise the shore, pier, building and ground-transition hexes");
                assertStraightSeaportEdge(scene);
                for (int y = 13; y < 17; y++) {
                    var at = new Coords(6, y);
                    var fullWater = new Hex(0, "water:2:63", board.getHex(at).getTheme(), at);
                    assertEquals(tilesetPixels(original, fullWater), scene.tile(at).tileset(),
                          "No painted grass bank between open water and SeaPort's west quay at " + at);
                }
            }
            return null;
        });
    }

    private static void assertStraightSeaportEdge(BoardScene scene) {
        var points = new ArrayList<Vector3>();
        var shape = BoardConcrete.of(scene);
        for (var tile : scene.tiles()) {
            var at = tile.coords();
            if (at.getX() < 12 || at.getY() < 10 || tile.surface() != BoardScene.Surface.CONCRETE) { continue; }
            for (int e = 0; e < 6; e++) {
                var other = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
                if (other == null || other.liquid().present() || other.surface() == BoardScene.Surface.CONCRETE) { continue; }
                for (int k : new int[] { e, (e + 1) % 6 }) {
                    var shift = shape.corners(at).get(k);
                    var p = BoardGeometry.corner(at, 0, k).add(shift.x(), shift.y(), 0);
                    if (points.stream().noneMatch(old -> old.epsilonEquals(p, .001f))) { points.add(p); }
                }
            }
        }
        points.sort(Comparator.comparingDouble(p -> -p.y));
        assertTrue(points.size() > 10, "Exercise the complete east boundary through the last board row");
        // The last corner lies on the board's edge and keeps its place there; the fitted runs end at the one before it.
        var end = points.removeLast();
        assertTrue(scene.tiles().stream().anyMatch(tile -> IntStream.range(0, 6)
              .anyMatch(k -> BoardGeometry.corner(tile.coords(), 0, k).epsilonEquals(end, .001f))),
              "The board keeps its hex outline where the fitted edge reaches it");
        var directions = new ArrayList<Vector3>();
        for (int i = 1; i < points.size(); i++) {
            var direction = new Vector3(points.get(i)).sub(points.get(i - 1)).nor();
            if (directions.isEmpty() || !directions.getLast().epsilonEquals(direction, .001f)) { directions.add(direction); }
        }
        assertEquals(3, directions.size(), "Two vertical runs meet through one diagonal, without residual hex steps");
        assertEquals(0, directions.getFirst().x, .001f);
        assertEquals(0, directions.getLast().x, .001f, "The lower run stays vertical into the last board row");
        for (int side : new int[] { -1, 1 }) {
            var ray = new Ray(new Vector3(end.x + side * 2 * BoardGeometry.hexScale(),
                  end.y + 2 * BoardGeometry.hexScale(), 500), new Vector3(0, 0, -1));
            var hit = BoardGeometry.hit(scene, ray);
            assertNotNull(hit, "No picking gap where the fitted edge reaches the board border");
            assertEquals(side < 0, hit.hardSurface(), "Picking intersects concrete and grass on their fitted sides");
            assertEquals(BoardGeometry.tile(scene, ray.origin.x, ray.origin.y).coords(), hit.coords(),
                  "Moving the visual boundary preserves logical game hex ownership");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4 })
    void groundTransitionsStayBehindPavementInBothViews(int level) throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork();
                  var original = new HexTileset(new File(Configuration.dataDir(), "models/board/classic"))) {
                original.loadFromFile("tileset/classic.tileset");
                for (int family = 1; family <= 5; family++) {
                    for (int exits : new int[] { 15, 57, 63 }) {
                        var hex = new Hex(0, "pavement:" + level + ":" + exits + ";ground_fluff:" + family + ":3", "grass", coords);
                        board.setHex(coords, hex);
                        artwork.invalidate(coords);
                        var tile = BoardScene.captureTile(hex, artwork.capture(board, coords, board.getHex(coords)), null, new BoardScene.PixelPool(), neighbour -> null);
                        assertEquals(1, tile.groundCover().concrete(), "Native paving stays entirely concrete");
                        var full = new Hex(0, "pavement:" + level + ":63", "grass", coords);
                        assertEquals(tilesetPixels(original, full), tile.tileset(),
                              "Concrete fills the fitted footprint without a second boundary painted into its texture");
                        assertEquals(exits, hex.getTerrain(Terrains.PAVEMENT).getExits());
                        assertEquals(3, hex.getTerrain(Terrains.GROUND_FLUFF).getExits(), "Rendering leaves authored terrain intact");
                    }
                }
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 4 })
    void tacticalWaterHasNoPaintedBanksWhileAuthoredWaterExitsRemainIntact(int depth) throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var at = new Coords(0, 0);
            try (var artwork = new BoardArtwork();
                  var original = new HexTileset(new File(Configuration.dataDir(), "models/board/classic"))) {
                original.loadFromFile("tileset/classic.tileset");
                for (String frozen : new String[] { "", ";ice:1" }) {
                    var fullWater = tilesetPixels(original, new Hex(0, "water:" + depth + ":63" + frozen, "grass", at));
                    for (int exits = 0; exits < 64; exits++) {
                        var hex = new Hex(0, "water:" + depth + ":" + exits + frozen, "grass", at);
                        board.setHex(at, hex);
                        artwork.invalidate(at);
                        var tile = BoardScene.captureTile(hex, artwork.capture(board, at, board.getHex(at)), null, new BoardScene.PixelPool(), neighbour -> null);
                        assertEquals(fullWater, tile.tileset(), "No painted shore in Tactical View: " + depth + ":" + exits + frozen);
                        assertEquals(exits, hex.getTerrain(Terrains.WATER).getExits(), "Rendering preserves gameplay exits");
                        assertEquals(depth, tile.waterDepth());
                        assertEquals(!frozen.isEmpty(), tile.frozen());
                    }
                }
            }
            return null;
        });
    }

    private static BoardScene.Pixels tilesetPixels(HexTileset tileset, Hex hex) {
        var image = new BufferedImage(HexTileset.HEX_W, HexTileset.HEX_H, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            var base = tileset.getBase(hex);
            BoardArtwork.drawBaseTerrain(hex, graphics, base, base, TilesetManager.loadHexMask(), 1);
            for (var overlay : tileset.getSupers(hex)) { graphics.drawImage(overlay, 0, 0, null); }
            for (var overlay : tileset.getOrthographic(hex)) { graphics.drawImage(overlay, 0, 0, null); }
        } finally { graphics.dispose(); }
        return BoardScene.Pixels.capture(image, null);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4 })
    void pavementStylesStayInTheGroundLayerWhileActualSceneryIsPreserved(int level) throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                board.setHex(coords, new Hex(0, "pavement:" + level + ":63", "grass", coords));
                var full = BoardScene.Pixels.capture(artwork.capture(board, coords, board.getHex(coords)).tileset(), null);
                for (int exits : new int[] { 0, 15, 57, 63, 65, 76 }) {
                    var hex = new Hex(0, "pavement:" + level + ":" + exits, "grass", coords);
                    board.setHex(coords, hex);
                    artwork.invalidate(coords);
                    var image = artwork.capture(board, coords, board.getHex(coords));
                    var tile = BoardScene.captureTile(hex, image, null, new BoardScene.PixelPool(), neighbour -> null);
                    assertEquals(BoardScene.Surface.CONCRETE, tile.surface());
                    assertEquals(full, tile.tileset(), "Tactical paving reaches the outline supplied by the geometry");
                    assertEquals(exits, hex.getTerrain(Terrains.PAVEMENT).getExits());
                }
                for (String decoration : new String[] { "fluff:50:1", "fluff:6:0", "woods:1;fluff:80:1" }) {
                    var hex = new Hex(0, "pavement:" + level + ";" + decoration, "grass", coords);
                    board.setHex(coords, hex);
                    artwork.invalidate(coords);
                    var image = artwork.capture(board, coords, board.getHex(coords));
                    var tile = BoardScene.captureTile(hex, image, null, new BoardScene.PixelPool(), neighbour -> null);
                    if (decoration.equals("fluff:50:1")) {
                        // A ground symbol is a legacy decal: the conversion turns it into a decal both views draw.
                        assertFalse(image.scenery().decals().isEmpty(), "Surface markings remain visible");
                    } else if (decoration.startsWith("woods:")) {
                        // A container yard's art is its woods' look (b12), never tileset scenery or paint.
                        assertTrue(image.scenery().models().isEmpty());
                        assertNull(tile.tilesetScenery());
                    } else {
                        assertFalse(image.scenery().models().isEmpty(), "Keep models selected by composite tileset rules");
                        assertNull(tile.tilesetScenery(), "Its model draws it: no Classic sprite duplicates it");
                    }
                }
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void grasslandsKeepAuthoredMaterialWeightsInNativeGround(int number) throws Exception {
        onEdt(() -> {
            var board = grassland(number);
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                BoardScene scene = source.takeFrame().scene();
                int transitions = 0, roads = 0, water = 0, unmixedCentres = 0;
                for (var tile : scene.tiles()) {
                    var hex = board.getHex(tile.coords());
                    assertEquals(BoardScene.Surface.GRASS, tile.surface(), tile.coords().toString());
                    if (hex.containsTerrain(Terrains.WATER)) { water++; }
                    assertEquals(hex.containsTerrain(Terrains.WATER), tile.liquid().present());
                    if (hex.containsTerrain(Terrains.GROUND_FLUFF)) {
                        transitions++;
                        if (hex.terrainLevel(Terrains.GROUND_FLUFF) == 1 && !tile.liquid().present()) {
                            float amount = hex.getTerrain(Terrains.GROUND_FLUFF).getExits() / 6f;
                            var p = BoardGeometry.center(tile.coords(), tile.elevation());
                            assertEquals(amount, tile.groundCover().desert(), .00001f);
                            // Higher neighbouring terrain blends its talus into the receiving hex, even at its
                            // centre. A centre without that contact retains the captured proportions exactly.
                            boolean belowCliff = tile.coords().allAdjacent().stream().map(scene::tile)
                                  .anyMatch(next -> next != null && next.elevation() > tile.elevation());
                            if (!belowCliff) {
                                unmixedCentres++;
                                assertEquals(amount, BoardSurfaceBlend.sample(scene, tile, p.x, p.y, p.z).desert(), .00001f,
                                      "The authored desert percentage reaches native material vertices at " + tile.coords());
                            }
                            assertTrue(BoardSurfaceBlend.boundary(scene, tile));
                        }
                    }
                    if (hex.containsTerrain(Terrains.ROAD)) {
                        roads++;
                        assertEquals(hex.getTerrain(Terrains.ROAD).getExits(), tile.roadExits());
                        assertEquals(BoardRoad.Kind.PAVED, tile.road());
                    }
                }
                assertTrue(transitions > 100, "Exercise the actual map's widespread decorative transitions");
                assertTrue(unmixedCentres > 0, "Exercise native material sampling away from higher neighbouring terrain");
                if (number == 2) { assertTrue(roads > 0 && water > 0, "The real road and river remain in the captured scene"); }
                assertEquals(transitions, board.getHexes(scene.tiles().stream().map(BoardScene.Tile::coords).toList()).stream()
                      .filter(hex -> hex.containsTerrain(Terrains.GROUND_FLUFF)).count(), "Capture never edits the board");
            }
            return null;
        });
    }

    @Test
    void cosmeticTransitionsConvertToNativeGround() throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                for (int family = 1; family <= 5; family++) {
                    for (int strength = 1; strength <= 5; strength++) {
                        Hex hex = new Hex(0, "ground_fluff:" + family + ":" + strength, "grass", coords);
                        board.setHex(coords, hex);
                        artwork.invalidate(coords);
                        var pixels = artwork.capture(board, coords, board.getHex(coords));
                        assertTrue(BoardEditorTerrain.badges(hex).isEmpty(),
                              "Cosmetic blends must not fill the editor with hidden-terrain badges");
                        var tile = BoardScene.captureTile(hex, pixels, null, new BoardScene.PixelPool(), neighbour -> null);
                        if (family == 1) {
                            float amount = strength / 6f;
                            assertEquals(amount, tile.groundCover().desert(), .00001f);
                            assertEquals(1 - amount, tile.groundCover().grass(), .00001f);
                        } else if (family == 4) {
                            assertEquals(strength / 6f, tile.groundCover().mars(), .00001f);
                        }
                        assertEquals(0, tile.groundCover().sand(), "Theme gradients never introduce loose SAND");
                        assertEquals(strength, hex.getTerrain(Terrains.GROUND_FLUFF).getExits());
                    }
                }
                assertEquals(BoardSceneryLayouts.Taken.DROP, BoardSceneryLayouts.takenTokens(new Hex(0, "ground_fluff:99:4",
                      "grass")).get(Terrains.GROUND_FLUFF), "Unknown hidden artwork is dropped: no editor marker is needed");
            }
            return null;
        });
    }

    @Test
    void gradientEditsInvalidateNativeMaterialsAndKeepSharedEdgesContinuous() throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(3, 3);
            var center = new Coords(1, 1);
            var next = center.translated(2);
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                board.setHex(new Coords(x, y), new Hex(0, "", "grass"));
            }
            var game = new Game();
            game.setBoard(board);
            BoardScene before;
            try (var source = new GpuMapSource(game, null, null)) { before = source.takeFrame().scene(); }
            board.setHex(center, new Hex(0, "ground_fluff:1:3", "grass"));
            // Typed as the editor's Fluff value: converted into its ground design.
            BoardSceneryLayouts.convert(board, java.util.List.of(center));
            BoardScene after;
            try (var source = new GpuMapSource(game, null, null)) { after = source.takeFrame().scene(); }
            assertFalse(before.tile(center).sameGeometry(after.tile(center)));
            assertNotEquals(BoardSurface.geometryKey(before, before.tile(next)), BoardSurface.geometryKey(after, after.tile(next)));
            var p = BoardGeometry.center(center, 0).lerp(BoardGeometry.center(next, 0), .5f);
            var mixed = BoardSurfaceBlend.sample(after, after.tile(center), p.x, p.y, p.z);
            assertEquals(mixed, BoardSurfaceBlend.sample(after, after.tile(next), p.x, p.y, p.z));
            assertTrue(mixed.desert() > 0 && mixed.desert() < .5f);
            assertEquals(1, mixed.grass() + mixed.desert(), .00001f);
            assertEquals(after.tile(center).groundCover(), after.tile(center).withText(List.of()).groundCover());
            return null;
        });
    }

    /** The real map as a map preview loads it: converted, so its transitions are drawn by their ground designs. */
    static Board grassland(int number) {
        return GpuBoardWindow.loadPreview(new File("data/boards/AGoAC Maps/16x17 Grassland " + number + ".board"));
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
