/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardInfrastructureModeTest {
    @BeforeAll
    static void loadNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void flatRailKeepsTheFullClassicPlateauAndVerticalOuterWalls() {
        var at = new Coords(2, 2);
        Map<Coords, Hex> hexes = new HashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) { hexes.put(new Coords(x, y), new Hex(x >= 2 ? 1 : 0)); }
        }
        hexes.put(at, new Hex(1, "rail:1:9", ""));
        var scene = scene(hexes, 5, 5);
        var tile = scene.tile(at);
        float floor = BoardGeometry.floor(scene), height = BoardGeometry.surfaceZ(tile);
        var classic = GpuTerrain.prepareColumns(scene, 0, 0, floor, Map.of(), Set.of(), Map.of(), Map.of(),
              () -> { }, new TerrainLoadProgress());
        var column = classic.topography().get(at);
        assertFalse(column.walls().isEmpty(), "The rail sits beside an actual one-level drop");
        for (var wall : column.walls()) {
            var normal = new Vector3(wall.b()).sub(wall.a()).crs(new Vector3(wall.c()).sub(wall.a())).nor();
            assertEquals(0, normal.z, .0001f, "A flat rail does not turn the Classic plateau edge into a slope");
        }
        assertTrue(column.slopes().isEmpty(), "No unrelated relief slope enters Classic picking or tactical overlays");
        for (var face : column.top()) {
            for (var point : List.of(face.a(), face.b(), face.c())) { assertEquals(height, point.z, .0001f); }
        }
        var center = BoardGeometry.center(at, tile.elevation());
        for (int edge = 0; edge < 6; edge++) {
            var a = BoardGeometry.corner(at, tile.elevation(), edge);
            var b = BoardGeometry.corner(at, tile.elevation(), edge + 1);
            for (float fraction : new float[] { 0, .25f, .5f, .75f }) {
                var point = new Vector3(a).lerp(b, fraction).lerp(center, .001f);
                assertTrue(column.top().stream().anyMatch(face -> Math.abs(face.height(point.x, point.y) - height) < .001f),
                      "The flat top reaches every nominal edge and corner: " + point);
            }
        }
        var feature = BoardRail.route(tile);
        assertNotNull(feature);
        var track = BoardRail.layout(scene, tile, feature, new BoardSurface(scene, tile), c -> null);
        assertEquals(track, BoardRail.layout(scene, tile, feature, classic.surfaces().get(at), classic.bridgeShapes()::get),
              "Classic retains the shared rail layout even when its surrounding column stays flat");
        assertFalse(track.paths().isEmpty());
        for (var point : track.paths().stream().flatMap(List::stream).toList()) {
            assertEquals(0, point.z, .0001f, "The straight rail retains its level carrier plane");
        }
        assertEquals(column.top(), GpuTilesetTerrain.column(scene, tile, floor).top(),
              "Cache reconstruction keeps the same full Classic top");
    }

    @Test
    void classicRoutesStayInsideSharpColumnsInEveryOrientation() throws Exception {
        int graded = 0;
        for (boolean concrete : new boolean[] { false, true }) {
            for (var panel : RoadGradeFixture.Panel.values()) {
                for (int direction = 0; direction < 6; direction++) {
                    for (int parity = 0; parity < 2; parity++) {
                        var scene = BoardScenes.scene(RoadGradeFixture.focused(concrete, panel,
                              "grade-curve", direction, parity));
                        var tile = scene.tile(RoadGradeFixture.focusedCenter(parity));
                        var source = BoardSurface.columns(scene, tile, TerrainLod.FULL);
                        var column = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene), source,
                              Map.of(tile.coords(), source));
                        var center = BoardGeometry.center(tile.coords(), tile.elevation());
                        var road = BoardRoad.way(scene, tile);
                        assertTrue(column.slopes().isEmpty(), "Classic never imports Enhanced bank geometry");
                        double area = 0;
                        for (var face : column.top()) {
                            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                            assertTrue(normal.z > 0, "Every drawn and picked roof faces upward");
                            area += normal.z / 2;
                            var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                            for (var vertex : List.of(face.a(), face.b(), face.c())) {
                                assertTrue(BoardGeometry.contains(tile.coords(), vertex.x, vertex.y),
                                      "Classic keeps the nominal hex boundary: " + vertex);
                            }
                            if (road.distance((point.x - center.x) / BoardGeometry.hexScale(),
                                  (point.y - center.y) / BoardGeometry.hexScale()) > BoardRoad.SHOULDER + .05f) {
                                for (var vertex : List.of(face.a(), face.b(), face.c())) {
                                    assertEquals(center.z, vertex.z, .001f,
                                          "The terrain outside the road/rail corridor stays flat");
                                }
                            } else if (Math.abs(point.z - center.z) > .01f) { graded++; }
                        }
                        assertEquals(.75 * BoardGeometry.width() * BoardGeometry.height(), area, .1,
                              "The flat roof and actual route ramp cover the nominal hex exactly once");
                        assertVertical(column.walls());
                        assertEquals(column.top(), GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene)).top(),
                              "Cache reconstruction and picking retain the drawn Classic geometry");
                    }
                }
            }
        }
        assertTrue(graded > 72, "Every road/rail placement exercises actual changing road grades");
    }

    @Test
    void aRoadEndingAtDryGroundCarriesTheWholeRampAndTheBankKeepsItsShape() {
        for (int direction = 0; direction < 6; direction++) {
            for (int parity = 0; parity < 2; parity++) {
                var at = new Coords(2 + parity, 2);
                var next = at.translated(direction);
                int exits = 1 << direction;
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                      c.equals(at) ? exits : 0, c.equals(next) ? 3 : 1, BoardScene.Surface.GRASS));
                float floor = BoardGeometry.floor(scene), bankTop = BoardGeometry.surfaceZ(scene.tile(next));
                var bank = GpuTilesetTerrain.column(scene, scene.tile(next), floor);
                for (var face : bank.top()) {
                    for (var point : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(bankTop, point.z, .001f, "A road ending at it leaves the bank's own shape");
                    }
                }
                // BoardRoadGrade.edgeLevel: the road climbs to the bank's level within its own hex.
                var road = GpuTilesetTerrain.column(scene, scene.tile(at), floor);
                assertTrue(road.top().stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
                      .anyMatch(point -> Math.abs(point.z - bankTop) < .001f), "The road end ramps up to the bank");
                assertVertical(bank.walls());
                assertVertical(road.walls());
            }
        }
    }

    @Test
    void suppliedEnhancedSurfacesCannotChangeClassicGeometry() throws Exception {
        var scene = BoardScenes.scene(RoadGradeFixture.focused(false,
              RoadGradeFixture.Panel.BESIDE, "grade-high-bank", 1, 1));
        var at = RoadGradeFixture.focusedCenter(1);
        var tile = scene.tile(at);
        var enhanced = new HashMap<Coords, BoardSurface>();
        for (var cell : scene.tiles()) { enhanced.put(cell.coords(), new BoardSurface(scene, cell)); }
        var expected = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene));
        var actual = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene), enhanced.get(at), enhanced);
        assertEquals(expected, actual, "Visible tops, walls and picking must use one Classic construction path");
    }

    @Test
    void bothModesRetainTheAuthoredBuiltDecksAndRailConnections() {
        var scene = scene();
        float floor = BoardGeometry.floor(scene);
        var enhanced = GpuTerrain.prepare(scene, 0, 0, floor, TerrainLod.FULL, Map.of(), TerrainSettings.capture(),
              Set.of(), Map.of(), () -> { }, new TerrainLoadProgress(), new GpuRoads.Masks());
        var classic = GpuTerrain.prepareColumns(scene, 0, 0, floor, Map.of(), Set.of(), Map.of(), Map.of(),
              () -> { }, new TerrainLoadProgress());
        assertEquals(3, enhanced.bridges().size());
        assertEquals(enhanced.bridges(), classic.bridges(), "Modes share the authored bridge connectivity and elevations");
        int routes = 0;
        for (var tile : scene.tiles()) {
            for (var feature : tile.features()) {
                if (!BoardRail.route(feature)) { continue; }
                var track = BoardRail.layout(scene, tile, feature, classic.surfaces().get(tile.coords()),
                      classic.bridgeShapes()::get);
                assertFalse(track.paths().isEmpty(), "The changed terrain style must retain every rail connection");
                for (var point : track.paths().stream().flatMap(List::stream).toList()) {
                    assertTrue(Float.isFinite(point.z), "Rail support stays finite on Classic ramps and bridge decks");
                }
                routes++;
            }
        }
        assertEquals(5, routes);
        assertFalse(classic.roads().isEmpty());
    }

    private static void assertVertical(List<BoardSurface.Face> walls) {
        for (var face : walls) {
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            assertEquals(0, normal.z, .00001f, "Classic cuts and outer column walls remain vertical");
        }
    }

    private static BoardScene scene() {
        Map<Coords, Hex> hexes = new HashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 7; y++) { hexes.put(new Coords(x, y), new Hex(x == 1 ? 3 : 0)); }
        }
        for (int y = 1; y <= 5; y++) {
            boolean deck = y > 1 && y < 5;
            var hex = new Hex(deck ? 0 : y == 1 ? 1 : 2, deck
                  ? "water:1;bridge:1:9;bridge_cf:80;bridge_elev:" + (y == 4 ? 3 : 2) + ";bridge_road:1:9;bridge_rail:1:9"
                  : "road:1:9;rail:1:9", "");
            if (deck) { hex.setAppearance(Map.of("bridge", HexAppearance.BUILT_BRIDGE)); }
            hexes.put(new Coords(2, y), hex);
        }
        return scene(hexes, 5, 7);
    }

    private static BoardScene scene(Map<Coords, Hex> hexes, int width, int height) {
        var pixels = new BoardScene.PixelPool();
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(hexes.get(at), artwork, null, pixels, hexes::get));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
