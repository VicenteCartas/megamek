/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuTilesetTerrainTest {
    private static final Coords CENTER = new Coords(1, 1);

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void dryClassicColumnsKeepInclineAndAuthoredCliffWallMaterials() {
        for (int drop : new int[] { 1, 2, 3 }) {
            for (boolean cliff : new boolean[] { false, true }) {
                var board = Board.createEmptyBoard(3, 3);
                board.getHex(CENTER).setLevel(drop);
                if (cliff) { board.getHex(CENTER).addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, 1)); }
                var scene = RoadGradeFixture.scene(board);
                var column = GpuTilesetTerrain.column(scene, scene.tile(CENTER), BoardGeometry.floor(scene));
                assertEquals(12, column.walls().size(), "All six dry hex edges have a visible drop");
                for (var face : column.walls()) {
                    boolean rock = drop > 2 || cliff && BoardGeometry.edgeDirection(face.landEdge()) == 0;
                    assertEquals(rock ? BoardSurface.Finish.OUTCROP : BoardSurface.Finish.WALL, face.finish(),
                          "Classic grass uses rock on high/explicit cliffs and soil on ordinary inclines");
                    assertEquals(0, new Vector3(face.b()).sub(face.a())
                          .crs(new Vector3(face.c()).sub(face.a())).nor().z, .001f,
                          "The material cue must not sculpt the vertical column");
                }
            }
        }
    }

    @Test
    void gradedClassicArtworkUsesSlopeNormalsWhileFlatTopsStayUpward() {
        var tile = BoardScenes.tile(CENTER, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS);
        for (float rise : new float[] { 0, 1, 4 }) {
            var a = new Vector3(0, 0, 0); var b = new Vector3(1, 0, rise);
            var c = new Vector3(1, 1, rise); var d = new Vector3(0, 1, 0);
            var top = List.of(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP),
                  new BoardSurface.Face(a, c, d, BoardSurface.Finish.TOP));
            var surface = new BoardTacticalGeometry.Surface(top, List.of(), top, List.of(), List.of(), List.of());
            var normals = new ArrayList<Vector3>();
            var mesh = (MeshPartBuilder) Proxy.newProxyInstance(MeshPartBuilder.class.getClassLoader(),
                  new Class<?>[] { MeshPartBuilder.class }, (proxy, method, arguments) -> {
                      if (method.getName().equals("triangle")) {
                          for (var argument : arguments) {
                              normals.add(new Vector3(((MeshPartBuilder.VertexInfo) argument).normal));
                          }
                      }
                      return null;
                  });
            var art = new TextureRegion();
            GpuTilesetTerrain.write(tile, surface, BoardFlow.Current.STILL, at -> art, null,
                  (material, draw) -> draw.accept(mesh), (material, draw) -> { }, new HashMap<>(), new BoundingBox());
            assertEquals(6, normals.size(), "Exercise the actual Classic artwork mesh emitter");
            var expected = new Vector3(-rise, 0, 1).nor();
            for (var normal : normals) {
                assertEquals(1, normal.len(), .00001f, "Lighting receives a unit normal");
                assertEquals(1, normal.dot(expected), .00001f,
                      "The same plane must shade continuously across triangles, including steep road banks");
            }
        }
    }

    @Test
    void classicQuaysKeepTheirNominalHexFootprintAndPicking() {
        BoardScene scene = BoardScenes.dockScene();
        float floor = BoardGeometry.floor(scene);
        for (int y = 2; y <= 5; y++) {
            Coords at = new Coords(10, y);
            var column = GpuTilesetTerrain.column(scene, scene.tile(at), floor);
            assertEquals(.75 * BoardGeometry.width() * BoardGeometry.height(),
                  column.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .05);
            for (var face : column.top()) {
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(BoardGeometry.contains(at, p.x, p.y), "A Classic quay keeps its sharp hex boundary");
                    assertEquals(BoardGeometry.surfaceZ(scene.tile(at)), p.z, .001f);
                }
            }
            for (var face : column.walls()) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                assertEquals(0, normal.z, .001f, "The quay wall remains vertical");
            }
            // This point is inside the nominal pier tip, which only Enhanced concrete fitting narrows.
            Vector3 point = BoardGeometry.center(at, 0).add(30 * BoardGeometry.hexScale(), 0, 100);
            BoardGeometry.Hit hit = BoardGeometry.hit(scene, new Ray(point, new Vector3(0, 0, -1)), scene.tiles(), floor,
                  coords -> GpuTilesetTerrain.column(scene, scene.tile(coords), floor));
            assertEquals(at, hit.coords());
            assertEquals(100 - BoardGeometry.surfaceZ(scene.tile(at)), Math.sqrt(hit.distance()), .002f);
        }
    }

    @Test
    void columnsStandFlatAtTheirLevelAndWallOnlyDownToLowerNeighboursAndTheFloor() {
        // A board at level 2, except the centre's northern neighbour at 0 and its southern neighbour at 3.
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                int level = coords.equals(CENTER.translated(0)) ? 0 : coords.equals(CENTER.translated(3)) ? 3 : 2;
                tiles.add(new BoardScene.Tile(coords, level, -1, false, 0, BoardScene.Surface.GRASS, null,
                      List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        BoardTacticalGeometry.Surface center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
        for (BoardSurface.Face face : center.top()) {
            for (Vector3 corner : List.of(face.a(), face.b(), face.c())) { assertEquals(2 * BoardGeometry.level(), corner.z); }
        }
        assertEquals(2, center.walls().size(), "Only the drop to the lower neighbour has a wall");
        for (BoardSurface.Face face : center.walls()) {
            assertEquals(0, Math.min(face.a().z, Math.min(face.b().z, face.c().z)));
        }
        BoardTacticalGeometry.Surface corner = GpuTilesetTerrain.column(scene, scene.tile(new Coords(0, 0)), floor);
        assertEquals(floor, corner.walls().stream().mapToDouble(face -> face.b().z).min().orElseThrow(), 1e-4,
              "The board's edges wall down to its floor");

        // A pointer straight down beside the drop picks the flat top the view draws, not a sculpted slope.
        Vector3 nearDrop = BoardGeometry.center(CENTER, 0).add(0, .45f * BoardGeometry.height(), 100);
        BoardGeometry.Hit hit = BoardGeometry.hit(scene, new Ray(nearDrop, new Vector3(0, 0, -1)), scene.tiles(), floor,
              coords -> GpuTilesetTerrain.column(scene, scene.tile(coords), floor));
        assertEquals(CENTER, hit.coords());
        assertEquals(100 - 2 * BoardGeometry.level(), (float) Math.sqrt(hit.distance()), 1e-3);
    }

    @Test
    void aRoadRampsThroughALevelChangeAndBothHexesMeetAtItsMouth() {
        // The centre at level 1 and its northern neighbour at level 0, joined by a road across their shared edge.
        Coords north = CENTER.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                int road = coords.equals(CENTER) ? 1 : coords.equals(north) ? 1 << 3 : 0;
                // Road exits make a road only with a kind of road (BoardRoad.rendered).
                tiles.add(new BoardScene.Tile(coords, coords.equals(north) ? 0 : 1, -1, false, road,
                      BoardScene.Surface.GRASS, null, null, List.of(), List.of(), BoardLiquid.NONE, null, false,
                      road == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        // Edge 1 of the centre runs between its corners 1 and 2, toward direction 0.
        Vector3 mouth = BoardGeometry.corner(CENTER, 0, 1).lerp(BoardGeometry.corner(CENTER, 0, 2), .5f);
        float meet = .5f * BoardGeometry.level();
        for (Coords coords : List.of(CENTER, north)) {
            BoardTacticalGeometry.Surface column = GpuTilesetTerrain.column(scene, scene.tile(coords), floor);
            assertTrue(column.top().stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
                        .anyMatch(p -> Math.abs(p.z - meet) < .001f && Vector3.dst(p.x, p.y, 0, mouth.x, mouth.y, 0)
                              <= BoardSurface.ROAD_MOUTH * BoardGeometry.hexScale() + .001f),
                  coords + " ramps to the height both hexes meet at in the road's mouth");
            for (BoardSurface.Face wall : column.walls()) {
                if (wall.finish() != BoardSurface.Finish.WALL) { continue; }
                for (Vector3 p : List.of(wall.a(), wall.b(), wall.c())) {
                    assertTrue(Vector3.dst(p.x, p.y, 0, mouth.x, mouth.y, 0)
                          >= BoardSurface.ROAD_MOUTH * BoardGeometry.hexScale() - .001f, coords + " walls off the mouth");
                }
            }
        }
        assertTrue(GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor).walls().stream()
              .anyMatch(face -> face.finish() == BoardSurface.Finish.WALL), "Beside the mouth the step keeps its wall");
    }

    @Test
    void threeLevelRoadClimbDoesNotReshapeItsMiddleLevelClassicBank() {
        var lower = BoardScenes.CENTER;
        var upper = lower.translated(0);
        var bank = lower.translated(1);
        for (int level : new int[] { 1, 2 }) {
            var scene = BoardScenes.scene(at -> BoardScenes.tile(at,
                  at.equals(lower) || at.equals(upper) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  at.equals(lower) ? 1 : at.equals(upper) ? 8 : 0,
                  at.equals(lower) ? 0 : at.equals(upper) ? 3 : level, BoardScene.Surface.GRASS));
            var tile = scene.tile(bank);
            assertTrue(BoardRoad.nearby(scene, tile).isEmpty(), "Neither adjoining road has the bank's level");
            var classic = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene));
            assertEquals(.75 * BoardGeometry.width() * BoardGeometry.height(),
                  classic.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .05);
            for (var face : classic.top()) {
                for (var point : List.of(face.a(), face.b(), face.c())) {
                    assertEquals(level * BoardGeometry.level(), point.z, .001f,
                          "Nearby ramps leave the entire unconnected Classic hex flat");
                }
            }
            for (var wall : classic.walls()) {
                assertEquals(0, new Vector3(wall.b()).sub(wall.a()).crs(new Vector3(wall.c()).sub(wall.a())).nor().z,
                      .001f, "The bank retains vertical column walls");
            }
        }
    }

    @Test
    void waterColumnsWallFromTheBedAndDryBanksReachDownToIt() {
        Coords north = CENTER.translated(0), south = CENTER.translated(3);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, coords.equals(south) ? -1 : coords.equals(north) ? 4 : 2,
                      false, 0, BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene), bed = BoardGeometry.groundZ(scene.tile(CENTER));
        var center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
        assertEquals(2, center.walls().size(), "Only the deeper northern bed creates an underwater step");
        for (var face : center.walls()) {
            assertEquals(bed, Math.max(face.a().z, Math.max(face.b().z, face.c().z)), .001f);
            assertEquals(BoardGeometry.groundZ(scene.tile(north)), Math.min(face.a().z, Math.min(face.b().z, face.c().z)), .001f);
        }
        var bank = GpuTilesetTerrain.column(scene, scene.tile(south), floor);
        assertTrue(bank.walls().stream().anyMatch(face -> Math.min(face.a().z, Math.min(face.b().z, face.c().z)) == bed),
              "The dry bank meets the bed rather than leaving a hole below the liquid surface");
        var edge = GpuTilesetTerrain.column(scene, scene.tile(north), floor);
        assertTrue(edge.walls().stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
              .allMatch(point -> point.z <= BoardGeometry.groundZ(scene.tile(north))),
              "The board edge must not grow a solid wall up to the water surface");
    }

    @Test
    void openWaterFallsOnlyIntoTheLowerOpenWaterItJoins() {
        // A pool at level 2 among pools at its level; below it to the north open water, to the south frozen water, to
        // the south-west dry land, all at level 1; a higher pool to the north-west.
        Coords north = CENTER.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                boolean low = coords.equals(north) || coords.equals(CENTER.translated(3)) || coords.equals(CENTER.translated(4));
                tiles.add(new BoardScene.Tile(coords, low ? 1 : coords.equals(CENTER.translated(5)) ? 3 : 2,
                      coords.equals(CENTER.translated(4)) ? -1 : 1, coords.equals(CENTER.translated(3)), 0,
                      BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        BoardTacticalGeometry.Surface center = GpuTilesetTerrain.column(scene, scene.tile(CENTER), BoardGeometry.floor(scene));
        assertTrue(center.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.BED)
              .allMatch(face -> face.a().z == BoardGeometry.groundZ(scene.tile(CENTER))), "The lakebed retains its depth");
        assertTrue(center.water().stream().allMatch(face -> face.a().z == BoardGeometry.surfaceZ(scene.tile(CENTER))),
              "Water picking retains the liquid surface");
        assertTrue(center.top().containsAll(center.water()), "Overlay support includes the water and its dry shores");
        var frozen = scene.tile(CENTER.translated(3));
        var ice = GpuTilesetTerrain.column(scene, frozen, BoardGeometry.floor(scene));
        assertEquals(ice.top(), ice.faces(), "Ice remains a solid support at the surface");
        assertEquals(1, center.waterfalls().size(), "Only the open pool below takes a fall");
        BoardSurface.Side fall = center.waterfalls().getFirst();
        // Edge 1 of the centre runs between its corners 1 and 2, toward direction 0.
        Vector3 from = BoardGeometry.corner(CENTER, 0, 1), to = BoardGeometry.corner(CENTER, 0, 2);
        assertEquals(0, Vector3.dst(fall.a().x, fall.a().y, 0, from.x, from.y, 0), 1e-3);
        assertEquals(0, Vector3.dst(fall.b().x, fall.b().y, 0, to.x, to.y, 0), 1e-3);
        assertEquals(BoardGeometry.surfaceZ(scene.tile(CENTER)), fall.a().z, 1e-4, "It leaves the pool's surface");
        assertEquals(BoardGeometry.surfaceZ(scene.tile(north)), fall.lowA(), 1e-4, "and lands on the surface below");
    }

    @Test
    void raisedClassicPoolKeepsItsSharpWaterOutlineAndVerticalBedWalls() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            Coords at = new Coords(x, y);
            tiles.add(new BoardScene.Tile(at, at.equals(CENTER) ? 6 : 1, at.equals(CENTER) ? 1 : -1,
                  false, 0, BoardScene.Surface.GRASS, null, List.of(), List.of()));
        }
        BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
        var tile = scene.tile(CENTER);
        var surface = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene));
        float water = BoardGeometry.waterZ(tile), level = tile.elevation() * BoardGeometry.level();
        assertTrue(surface.waterfalls().isEmpty(), "A shore above dry land must not create a waterfall");
        assertEquals(6, surface.water().size(), "Classic water retains its nominal hex outline");
        assertTrue(surface.faces().stream().allMatch(face -> face.finish() == BoardSurface.Finish.BED),
              "No sculpted bank or shallow slope enters the Classic lakebed");
        assertEquals(BoardGeometry.groundZ(tile), surface.walls().stream().flatMap(f -> Stream.of(f.a(), f.b(), f.c()))
              .mapToDouble(p -> p.z).max().orElseThrow(), .001f);
        assertEquals(BoardGeometry.level(), surface.walls().stream().flatMap(f -> Stream.of(f.a(), f.b(), f.c()))
              .mapToDouble(p -> p.z).min().orElseThrow(), .001f);
        Vector3 middle = BoardGeometry.center(CENTER, 0);
        Vector3 nearBank = new Vector3(middle).lerp(BoardGeometry.corner(CENTER, 0, 1)
              .lerp(BoardGeometry.corner(CENTER, 0, 2), .5f), .92f);
        for (var point : List.of(middle, nearBank)) {
            assertEquals(water, BoardSurface.sampleHeight(surface.top(), point.x, point.y, Float.NaN), .001f);
            var hit = BoardGeometry.hit(scene, new Ray(new Vector3(point.x, point.y, level + 100), new Vector3(0, 0, -1)),
                  scene.tiles(), BoardGeometry.floor(scene), coords -> GpuTilesetTerrain.column(scene, scene.tile(coords),
                        BoardGeometry.floor(scene)));
            assertEquals(CENTER, hit.coords());
            assertEquals(level + 100 - water, Math.sqrt(hit.distance()), .001f, "Picking uses the full water hex");
        }
    }

    @Test
    void everyCoastPatternCoversItsHexAndKeepsSharedWaterMouthsOpen() {
        for (int mask = 0; mask < 64; mask++) {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                Coords at = new Coords(x, y);
                boolean dry = false;
                for (int d = 0; d < 6; d++) { dry |= (mask & 1 << d) != 0 && at.equals(CENTER.translated(d)); }
                tiles.add(new BoardScene.Tile(at, 0, dry ? -1 : 2, false, 0, BoardScene.Surface.GRASS, null, List.of(),
                      List.of()));
            }
            BoardScene scene = new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
            float floor = BoardGeometry.floor(scene), water = BoardGeometry.waterZ(scene.tile(CENTER));
            var surface = GpuTilesetTerrain.column(scene, scene.tile(CENTER), floor);
            double footprint = .75 * BoardGeometry.width() * BoardGeometry.height();
            assertEquals(footprint, surface.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .05,
                  "Dry shore and liquid cover the hex once, mask=" + mask);
            assertTrue(surface.faces().stream().noneMatch(f -> f.finish() == BoardSurface.Finish.BANK
                  || f.finish() == BoardSurface.Finish.SHORE), "Classic coast cues do not reshape terrain");
            for (var face : surface.faces()) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                      "Submerged banks never fold back under the dry shore, mask=" + mask);
            }
            for (var face : surface.top()) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                      "Shore triangles never fold, mask=" + mask);
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(BoardGeometry.contains(CENTER, p.x, p.y), "The bank stays inside its column, mask=" + mask);
                }
            }
            for (int edge = 0; edge < 6; edge++) {
                int direction = BoardGeometry.edgeDirection(edge);
                if ((mask & 1 << direction) != 0) { continue; }
                Coords next = CENTER.translated(direction);
                var neighbor = GpuTilesetTerrain.column(scene, scene.tile(next), floor);
                for (float t : new float[] { .05f, .5f, .95f }) {
                    Vector3 p = BoardGeometry.corner(CENTER, 0, edge).lerp(BoardGeometry.corner(CENTER, 0, edge + 1), t);
                    for (var side : List.of(surface, neighbor)) {
                        assertEquals(water, side.water().stream().map(f -> f.height(p.x, p.y)).max(Float::compare).orElseThrow(),
                              .001f, "Both water tiles meet across the whole mouth, mask=" + mask);
                    }
                }
            }
        }
    }

    @Test
    void classicWaterRetainsFullHexCoverageBesideEveryQuayMaterial() {
        for (var scene : List.of(BoardScenes.dockScene(),
              BoardScenes.quayScene(BoardScene.Surface.GRASS))) {
            float floor = BoardGeometry.floor(scene);
            for (var tile : scene.tiles()) {
                if (!tile.liquid().present() || tile.frozen()) { continue; }
                var surface = GpuTilesetTerrain.column(scene, tile, floor);
                assertEquals(.75 * BoardGeometry.width() * BoardGeometry.height(),
                      surface.top().stream().mapToDouble(GpuTilesetTerrainTest::area).sum(), .08,
                      "Classic land and water share their nominal hex edges: " + tile.coords());
                for (var face : surface.top()) {
                    assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z > 0);
                    for (var point : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(BoardGeometry.contains(tile.coords(), point.x, point.y));
                    }
                }
            }
        }
    }

    private static double area(BoardSurface.Face face) {
        return Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x)) / 2;
    }
}
