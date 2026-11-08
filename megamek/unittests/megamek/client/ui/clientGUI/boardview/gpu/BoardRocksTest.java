/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The rock kit: closed library solids, rim formations scaled by drop, fallen rock below, anchors kept clear. */
class BoardRocksTest {
    /** A three-level mesa beside open ground, and a one-level step elsewhere. */
    private static final String[] LAYOUT = {
          "0000000",
          "0033000",
          "0033300",
          "0003000",
          "0000011",
          "0000011",
    };

    private static BoardScene scene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < LAYOUT[0].length(); x++) {
            for (int y = 0; y < LAYOUT.length; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), LAYOUT[y].charAt(x) - '0', -1, false, 0, family, null,
                      null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, LAYOUT[0].length(), LAYOUT.length, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void authoredRoughOnNaturalRidgesUsesBoundedBedrockAtEveryLod() {
        Coords centre = new Coords(1, 1);
        for (TerrainLod detail : TerrainLod.values()) {
            for (BoardScene.Surface family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DESERT,
                  BoardScene.Surface.ROCK, BoardScene.Surface.MARS, BoardScene.Surface.VOLCANO)) {
                var rough = roughRidge(family, 4, false, "rough-boulder");
                var bedrock = roughRidge(family, 4, false, BoardRocks.OUTCROP);
                var actual = new BoardSurface(rough, rough.tile(centre), detail);
                var expected = new BoardSurface(bedrock, bedrock.tile(centre), detail);
                assertEquals(expected.faces, actual.faces, "Rough reuses the closed outcrop kit: " + family + detail);
                Vector3 anchor = BoardGeometry.center(centre, 4);
                float height = actual.height(anchor.x, anchor.y) - anchor.z;
                assertTrue(height > 0 && height <= BoardGeometry.level() * .5f,
                      "Bedrock stays rooted and inside the captured rough height");
                assertEquals(rough.tile(centre).features().size(), 1, "No additional obstacles or scene state");
            }
        }
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.CONCRETE)) {
            for (boolean wet : List.of(false, true)) {
                int level = family == BoardScene.Surface.GRASS && !wet ? 0 : 4;
                var ordinary = roughRidge(family, level, wet, "rough-boulder");
                var bedrock = roughRidge(family, level, wet, BoardRocks.OUTCROP);
                assertNotEquals(new BoardSurface(ordinary, ordinary.tile(centre)).faces,
                      new BoardSurface(bedrock, bedrock.tile(centre)).faces,
                      "Flat, submerged and concrete rough retain loose boulders");
            }
        }
    }

    private static BoardScene roughRidge(BoardScene.Surface family, int height, boolean wet, String asset) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var rough = new BoardScene.Feature(asset, 0, 0, 20, 1, .5f, 0, BoardScene.FeatureKind.BOULDER);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                boolean centre = x == 1 && y == 1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), centre ? height : 0, wet ? 1 : -1, false, 0,
                      family, null, null, centre ? List.of(rough) : List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 3, 3, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private record Key(long x, long y, long z) {
        static Key of(Vector3 p) {
            return new Key(Math.round(p.x * 1e5), Math.round(p.y * 1e5), Math.round(p.z * 1e5));
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void libraryRocksAreClosedOutwardSolids(TerrainLod detail) {
        // Plants have open branches and explicit leaf backs, rather than closed rock volumes. Kinds: blocks, boulders
        // and the bedrock outcrops of zero-gravity rough.
        for (int kind = 0; kind < 3; kind++) {
            int count = kind == 0 ? BoardRocks.BLOCKS : kind == 1 ? BoardRocks.BOULDERS : BoardRocks.OUTCROPS;
            for (int variant = 0; variant < count; variant++) {
                BoardShape rock = shape(kind, variant, detail);
                Map<List<Key>, Integer> edges = new HashMap<>();
                double volume = 0;
                int triangles = 0;
                for (BoardShape.Polygon polygon : rock.polygons()) {
                    Vector3[] p = polygon.points();
                    for (int i = 0; i < p.length; i++) {
                        assertTrue(Float.isFinite(p[i].x) && Float.isFinite(p[i].y) && Float.isFinite(p[i].z));
                        assertTrue(Math.abs(p[i].x) <= .5001f && Math.abs(p[i].y) <= .5001f && p[i].z >= -.0001f);
                        edges.merge(List.of(Key.of(p[i]), Key.of(p[(i + 1) % p.length])), 1, Integer::sum);
                    }
                    for (int i = 1; i + 1 < p.length; i++) {
                        volume += p[0].dot(new Vector3(p[i]).crs(p[i + 1])) / 6.0;
                        triangles++;
                    }
                }
                for (List<Key> edge : edges.keySet()) {
                    assertEquals(1, edges.getOrDefault(List.of(edge.get(1), edge.get(0)), 0),
                          detail + " kind=" + kind + " variant=" + variant + " edge=" + edge
                                + ": each directed edge meets its reverse exactly once");
                }
                assertTrue(volume > .02, "Faces wind outward and enclose a real volume");
                assertTrue(triangles <= 120, "A rock keeps a small triangle budget");
                assertTrue(rock.height() > .2f && rock.height() < 1.2f);
                if (detail != TerrainLod.FULL) {
                    int fullTriangles = shape(kind, variant, TerrainLod.FULL).polygons().stream()
                          .mapToInt(p -> p.points().length - 2).sum();
                    assertTrue(triangles <= fullTriangles, "Distant rocks keep a closed silhouette with fewer facets");
                    if (detail == TerrainLod.DISTANT) { assertTrue(triangles <= 28); }
                }
            }
        }
    }

    private static BoardShape shape(int kind, int variant, TerrainLod detail) {
        return kind == 2 ? BoardRocks.outcrop(variant, detail) : BoardRocks.rock(kind == 0, variant, detail);
    }

    private static List<BoardSurface.Face> rocks(BoardScene scene, Coords coords) {
        var surface = new BoardSurface(scene, scene.tile(coords));
        return surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.OUTCROP
              && surface.relief.shade(face.a()).kind() == BoardRelief.Kind.ROCK).toList();
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void mountainLakeKeepsCliffFootBouldersOnWaterOwnedBanks(TerrainLod detail) {
        var scene = BoardScenes.scene(new File("data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"));
        for (Coords coords : List.of(new Coords(7, 14), new Coords(8, 14))) {
            var tile = scene.tile(coords);
            assertEquals(1, tile.waterDepth(), "The visible dry toe belongs to a depth-one water hex");
            var surface = new BoardSurface(scene, tile, detail);
            var rocks = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
            var formations = new HashSet<Float>();
            float tallest = 0;
            for (var face : rocks) {
                var shade = surface.relief.shade(face.a());
                assertEquals(BoardRelief.Kind.ROCK, shade.kind(), "Water retains geology, not cosmetic bushes");
                formations.add(shade.tint());
                tallest = Math.max(tallest, (shade.rim() + shade.foot()) * BoardRelief.metres(1));
            }
            assertTrue(formations.size() >= 2, "The cliff foot must retain several fallen blocks at " + coords + " " + detail);
            assertTrue(tallest >= BoardRelief.metres(.5f), "The bank retains substantial boulders, not only tiny stones");
            System.out.printf("MOUNTAIN_LAKE_BOULDERS %s %s rocks=%d triangles=%d tallestMetres=%.3f%n",
                  coords, detail, formations.size(), rocks.size(), tallest / BoardRelief.metres(1));
        }
    }

    @Test
    void scatterUsesEightTrianglesWithBroadCrownsAndOnlyItsBaseOpen() {
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            for (int variant = 0; variant < Math.max(BoardRocks.BLOCKS, BoardRocks.BOULDERS); variant++) {
                for (boolean slab : new boolean[] { false, true }) {
                    BoardShape rock = BoardScatter.rock(family, variant, slab);
                    Map<List<Key>, Integer> edges = new HashMap<>();
                    var crown = new HashSet<Key>();
                    int triangles = 0;
                    for (BoardShape.Polygon polygon : rock.polygons()) {
                        Vector3[] p = polygon.points();
                        assertEquals(3, p.length);
                        assertNotNull(polygon.normal(), "Simplification must not collapse a face");
                        assertTrue(polygon.normal().z > 0, "Scatter has only outward-facing upper surfaces");
                        for (int i = 0; i < p.length; i++) {
                            if (p[i].z > 0) { crown.add(Key.of(p[i])); }
                            edges.merge(List.of(Key.of(p[i]), Key.of(p[(i + 1) % p.length])), 1, Integer::sum);
                        }
                        triangles++;
                    }
                    assertEquals(8, triangles, "Tiny rocks use their own eight-triangle geometry");
                    assertTrue(crown.size() >= 2, "A rock needs a ridge or crown, not a single pyramid apex");
                    int open = 0;
                    for (List<Key> edge : edges.keySet()) {
                        int reverse = edges.getOrDefault(List.of(edge.get(1), edge.get(0)), 0);
                        if (reverse == 0) {
                            assertEquals(0, edge.get(0).z(), "Only the base may be open");
                            assertEquals(0, edge.get(1).z(), "The whole open edge must lie on the base");
                            open++;
                        } else {
                            assertEquals(1, reverse, "The exposed faces must join without gaps");
                        }
                    }
                    assertTrue(open >= 4 && open <= 6, "The four- or six-sided footprint is the only opening");
                }
            }
        }
        var silhouettes = new HashSet<HashSet<Key>>();
        for (String family : new String[] { "block", "boulder" }) {
            for (int variant = 0; variant < 8; variant++) {
                var points = new HashSet<Key>();
                BoardScatter.shape("stone-" + family + "-" + variant).polygons()
                      .forEach(polygon -> { for (Vector3 point : polygon.points()) { points.add(Key.of(point)); } });
                assertTrue(silhouettes.add(points), "Every stone variant must have distinct geometry");
            }
        }
    }

    private static float tallest(List<BoardSurface.Face> faces, float level) {
        float height = 0;
        for (BoardSurface.Face face : faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) { height = Math.max(height, p.z - level); }
        }
        return height;
    }

    @Test
    void deepRimsCarryMoreProminentFormationsThanShallowSteps() {
        // Without hex transitions, whose slopes shed fewer rim formations.
        BoardScenes.withTransitions(false, this::compareRims);
    }

    private void compareRims() {
        BoardScene scene = scene(BoardScene.Surface.SAND);
        float deep = 0, shallow = 0;
        int deepRocks = 0, shallowRocks = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            List<BoardSurface.Face> faces = rocks(scene, tile.coords());
            if (tile.elevation() == 3) {
                deep = Math.max(deep, tallest(faces, 3 * BoardGeometry.LEVEL));
                deepRocks += faces.size();
            } else if (tile.elevation() == 1) {
                shallow = Math.max(shallow, tallest(faces, BoardGeometry.LEVEL));
                shallowRocks += faces.size();
            }
        }
        assertTrue(deepRocks > shallowRocks, "Deep rims have more formations: " + deepRocks + " vs " + shallowRocks);
        assertTrue(deep > shallow * 1.3f, "Deep rim formations stand taller: " + deep + " vs " + shallow);
    }

    @Test
    void rocksKeepAnchorsClearStayInBoundsAndMatchPickingAndSupport() {
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            BoardScene scene = scene(family);
            float floor = BoardGeometry.floor(scene);
            int rocks = 0;
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(center.z, surface.height(center.x, center.y), .001f, "Rocks never lift a unit's anchor");
                BoardSurface.Face highest = null;
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                    rocks++;
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(Math.hypot(p.x - center.x, p.y - center.y) > BoardGeometry.WIDTH * .2f,
                              "The standing area stays clear");
                        assertTrue(p.z <= center.z + BoardRelief.headroom(tile) + .001f, "Picking bounds hold rocks");
                        assertTrue(p.z >= floor);
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    if (normal.z > .5f && (highest == null || centroid(face).z > centroid(highest).z)) { highest = face; }
                }
                if (highest == null || highest.a().z < center.z + .01f) { continue; }
                Vector3 point = centroid(highest);
                Ray ray = new Ray(new Vector3(point.x, point.y, center.z + BoardGeometry.WIDTH), new Vector3(0, 0, -1));
                BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray, List.of(tile), floor);
                assertNotNull(hit);
                float distance = ray.origin.z - surface.height(point.x, point.y);
                assertEquals(distance * distance, hit.distance(), .05f, "Picking and support meet the same rock");
            }
            if (family != BoardScene.Surface.CONCRETE) {
                assertTrue(rocks > 100, family + " has rim and fallen rock");
            } else {
                assertEquals(0, rocks, "Concrete rims and feet stay clear of fallen rock at every height");
            }
        }
    }

    private static Vector3 centroid(BoardSurface.Face face) {
        return new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
    }

    @Test
    void placementsAreCanonicalAndNeverMutateTheLibrary() {
        List<Vector3> before = new ArrayList<>();
        BoardRocks.rock(true, 0).polygons().forEach(polygon -> before.addAll(List.of(polygon.points())));
        List<Vector3> copy = before.stream().map(Vector3::cpy).toList();
        BoardScene scene = scene(BoardScene.Surface.ROCK);
        for (BoardScene.Tile tile : scene.tiles()) {
            List<BoardSurface.Face> first = rocks(scene, tile.coords()), second = rocks(scene, tile.coords());
            assertEquals(first.size(), second.size());
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.get(i).a(), second.get(i).a(), "The same board always yields the same rocks");
            }
        }
        assertEquals(copy, before, "Placement transforms copies, never the shared unit rocks");
    }

    @Test
    void groupedRocksKeepTheirWholeFootprintsInsideTheHexAndClearOfRoutes() {
        Coords coords = new Coords(2, 2);
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DESERT, BoardScene.Surface.VOLCANO)) {
            for (int type : new int[] { Terrains.ROAD, Terrains.BRIDGE }) {
                for (int exits = 0; exits < 64; exits++) {
                    Hex hex = BoardScenes.roughHex(family, 2);
                    if (exits != 0) { hex.addTerrain(new Terrain(type, 1, true, exits)); }
                    var route = BoardRoad.clearance(coords, exits);
                    var features = BoardFeatures.capture(hex, coords, Map.of()).stream()
                          .filter(f -> f.kind() == BoardScene.FeatureKind.BOULDER).toList();
                    assertTrue(features.size() <= 18, "Grouping does not add rock instances");
                    for (int i = 0; i < features.size(); i++) {
                        var feature = features.get(i);
                        double turn = Math.toRadians(feature.rotation());
                        float c = (float) Math.cos(turn), sine = (float) Math.sin(turn);
                        float size = BoardFeatures.ROUGH_BOULDER_WIDTH * feature.scale();
                        int variant = coords.getX() * 31 + coords.getY() * 17 + i;
                        for (var polygon : BoardRocks.rock(family, variant, TerrainLod.FULL).polygons()) {
                            for (Vector3 p : polygon.points()) {
                                float x = feature.x() + size * (c * p.x - sine * p.y * .8f);
                                float y = feature.y() + size * (sine * p.x + c * p.y * .8f);
                                assertTrue(Math.abs(y) <= 36.001f && Math.abs(x) + Math.abs(y) * 21 / 36 <= 42.001f,
                                      "Rock footprint leaves its hex: " + feature + " at " + x + "," + y);
                                if (exits != 0) {
                                    assertTrue(route.distance(x, y) >= BoardRoad.SHOULDER - .001f,
                                          "Rock crosses the route shoulder: " + family + " exits=" + exits
                                                + " " + feature + " at " + x + "," + y);
                                    if (type == Terrains.BRIDGE && BoardRoad.roundabout(exits)) {
                                        assertTrue(Math.hypot(x, y) >= BoardRoad.ROUNDABOUT_RADIUS,
                                              "Bridge roundabout islands remain clear too");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void meteorRimBouldersDoNotStretchDownTheCliff() {
        var scene = BoardScenes.scene(new File("data/boards/unofficial/Unknown/meteor.board"));
        var tile = scene.tile(new Coords(11, 11));
        var surface = new BoardSurface(scene, tile);
        float height = (float) tile.features().stream().filter(f -> f.kind() == BoardScene.FeatureKind.BOULDER)
              .mapToDouble(BoardScene.Feature::height).max().orElseThrow() * BoardGeometry.level();
        assertTrue(surface.rough.size() > 20, "Rough still places rocks on hex 1212");
        for (var face : surface.rough) {
            var shade = surface.relief.shade(face.a());
            float extent = (shade.rim() + shade.foot()) * BoardRelief.metres(1);
            assertTrue(extent <= height * 2.2f, "A rim boulder must not become a cliff-height pillar: " + extent);
        }
    }
}
