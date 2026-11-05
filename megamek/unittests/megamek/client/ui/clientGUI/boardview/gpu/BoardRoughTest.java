/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoughTest {
    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void fluffSelectsConcreteRowsOrFallenWoodAtBothRoughLevels(int fluff) {
        Coords coords = new Coords(2, 2);
        int count = 0;
        for (int level : new int[] { 1, 2 }) {
            Hex hex = hex(fluff, level);
            var features = BoardFeatures.capture(hex, coords, Map.of());
            assertTrue(features.size() > count, "Ultra rough has denser obstacles");
            count = features.size();
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.ROUGH));
            assertEquals(fluff == 1 ? Set.of("rough/dragon-tooth")
                        : Set.of("rough/felled-trunk", "rough/charred-stump", "rough/fallen-stump"),
                  new HashSet<>(features.stream().map(BoardScene.Feature::asset).toList()));
            assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            if (fluff == 1) { staggeredSpacing(features); }
            hex.removeTerrain(Terrains.ROUGH);
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.ROUGH));
            assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Unrelated fluff keeps its original artwork");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void roadsMoveRoughCoverOntoTheVergesInsteadOfRemovingIt(int fluff) {
        Coords coords = new Coords(2, 2);
        int standard = BoardFeatures.capture(hex(fluff, 1), coords, Map.of()).size();
        var ultraTeeth = BoardFeatures.capture(hex(1, 2), coords, Map.of());
        for (int level : new int[] { 1, 2 }) {
            int open = BoardFeatures.capture(hex(fluff, level), coords, Map.of()).size();
            // Ultra teeth already stand at the closest spacing and ultra stumps are large; beside these roads not
            // every piece fits, but they keep the standard count.
            int expected = fluff != 0 && level == 2 ? standard : open;
            for (int exits : new int[] { 1, 9, 3 }) {
                Hex hex = hex(fluff, level);
                hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, exits));
                var features = BoardFeatures.capture(hex, coords, Map.of());
                assertTrue(features.size() >= expected,
                      "Road exits " + exits + " keep " + features.size() + " of " + open + " pieces");
                if (fluff == 1) {
                    assertTrue(staggeredSpacing(features) >= staggeredSpacing(ultraTeeth) - .001f,
                          "Relocated teeth never stand closer than ultra rough's");
                    BoardScene scene = scene(hex, 0, false, BoardScene.Surface.GRASS);
                    assertEquals(features.size(), new BoardSurface(scene, scene.tile(coords)).roughModels().size(),
                          "Every captured tooth lies wholly inside its hex");
                }
            }
        }
    }

    @Test
    void standardTeethSpreadAcrossTheHexMoreSparselyThanUltraTeeth() {
        Coords coords = new Coords(2, 2);
        var standard = BoardFeatures.capture(hex(1, 1), coords, Map.of());
        var ultra = BoardFeatures.capture(hex(1, 2), coords, Map.of());
        assertTrue(staggeredSpacing(standard) > staggeredSpacing(ultra));
        assertTrue(standard.stream().map(BoardScene.Feature::y).distinct().count() >= 3, "Not a strip of two lines");
    }

    @Test
    void teethKeepIdenticalDimensionsAcrossRowsHexesAndRoughLevels() {
        Set<Float> widths = new HashSet<>(), heights = new HashSet<>();
        for (int level : new int[] { 1, 2 }) {
            for (Coords coords : List.of(new Coords(0, 0), new Coords(2, 2), new Coords(9, 7))) {
                for (var feature : BoardFeatures.capture(hex(1, level), coords, Map.of())) {
                    widths.add(feature.scale());
                    heights.add(feature.height());
                }
            }
        }
        assertEquals(1, widths.size(), "Concrete teeth share one width");
        assertEquals(1, heights.size(), "Rear rows and ultra rough must not grow taller teeth");
    }

    @Test
    void absentOrUnknownFluffKeepsOrdinaryBoulders() {
        for (int fluff : new int[] { 0, 3, 17 }) {
            var features = BoardFeatures.capture(hex(fluff, 1), new Coords(2, 2), Map.of());
            assertFalse(features.isEmpty());
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void variantsKeepRoadsAndBridgeApproachesClear(int fluff) {
        Coords coords = new Coords(2, 2);
        for (int type : new int[] { Terrains.ROAD, Terrains.BRIDGE }) {
            for (int exits : new int[] { 1, 9, 18, 21, 63 }) {
                Hex hex = hex(fluff, 2);
                hex.addTerrain(new Terrain(type, 1, true, exits));
                BoardRoad road = BoardRoad.clearance(coords, exits);
                List<BoardSurface.Face> deck = type == Terrains.BRIDGE ? bridgeDeck(exits) : List.of();
                for (var feature : BoardFeatures.capture(hex, coords, Map.of())) {
                    if (feature.kind() != BoardScene.FeatureKind.ROUGH) { continue; }
                    var shape = BoardShape.loadModel(feature.asset());
                    double turn = Math.toRadians(feature.rotation());
                    for (var polygon : shape.polygons()) {
                        for (Vector3 p : polygon.points()) {
                            float x = feature.x() + feature.scale() * (float) (Math.cos(turn) * p.x - Math.sin(turn) * p.y);
                            float y = feature.y() + feature.scale() * (float) (Math.sin(turn) * p.x + Math.cos(turn) * p.y);
                            assertTrue(type == Terrains.ROAD ? road.distance(x, y) >= BoardRoad.SHOULDER
                                        : !Float.isFinite(BoardSurface.sampleHeight(deck, x, y, Float.NaN)),
                                  "Obstacle intersects route: type=" + type + " exits=" + exits + " " + feature
                                        + " at " + x + "," + y + " distance=" + road.distance(x, y));
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "1,0", "2,0", "1,1", "2,1" })
    void renderedModelsShareTheirTrianglesWithPickingAndFooting(int fluff, int step) {
        BoardScene scene = scene(fluff, step, false);
        Coords coords = new Coords(2, 2);
        BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
        assertFalse(surface.roughModels().isEmpty());
        if (fluff == 2 && step == 0) {
            assertTrue(surface.roughModels().stream().anyMatch(p -> p.asset().equals("rough/charred-stump")));
            assertTrue(surface.roughModels().stream().anyMatch(p -> p.asset().equals("rough/fallen-stump")),
                  "Level ground keeps both stump poses; an elevation edge may omit unsupported pieces");
        }
        Set<Vector3> rendered = new HashSet<>();
        for (var placement : surface.roughModels()) {
            for (var polygon : BoardShape.loadModel(placement.asset()).polygons()) {
                for (Vector3 point : polygon.points()) { rendered.add(new Vector3(point).mul(placement.transform())); }
            }
        }
        Set<Vector3> support = new HashSet<>();
        int checked = 0;
        for (var face : surface.rough) {
            assertEquals(BoardSurface.Finish.ROUGH, face.finish());
            support.addAll(List.of(face.a(), face.b(), face.c()));
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            if (normal.z < .5f) { continue; }
            Vector3 p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
            float height = surface.height(p.x, p.y);
            assertTrue(height >= p.z - .001f);
            Ray ray = new Ray(new Vector3(p.x, p.y, 200), new Vector3(0, 0, -1));
            var hit = BoardGeometry.hit(scene, ray, List.of(scene.tile(coords)), BoardGeometry.floor(scene));
            assertNotNull(hit);
            assertEquals(coords, hit.coords());
            assertEquals(height, 200 - Math.sqrt(hit.distance()), .002,
                  "Ray intersection and barycentric height agree within float precision");
            assertEquals(height, UnitLandingSupports.ground(scene, p.x, p.y), .001f);
            checked++;
        }
        assertEquals(rendered, support, "Support uses the exact transformed authored mesh");
        assertTrue(checked > 0);
        assertTrue(surface.groundFaces().stream().noneMatch(f -> f.finish() == BoardSurface.Finish.ROUGH));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void waterSnapshotRetainsVariantPlacementsAndSeparateGround(int fluff) {
        BoardScene scene = scene(fluff, 0, true);
        var tile = scene.tile(new Coords(2, 2));
        BoardSurface original = new BoardSurface(scene, tile);
        BoardSurface restored = original.waterGeometry().surface(scene);
        assertFalse(original.roughModels().isEmpty());
        assertEquals(original.roughModels(), restored.roughModels());
        assertEquals(original.rough, restored.rough);
        assertEquals(original.groundFaces(), restored.groundFaces());
    }

    @Test
    void horizontalRayCanHitTeethAboveThePavedGroundBounds() {
        BoardScene scene = scene(1, 0, false, BoardScene.Surface.CONCRETE);
        Coords coords = new Coords(2, 2);
        // Aim along a row of teeth.
        float row = scene.tile(coords).features().getFirst().y() * BoardGeometry.hexScale();
        Vector3 origin = BoardGeometry.center(coords, 0).add(BoardGeometry.width(), row, 3);
        var hit = BoardGeometry.hit(scene, new Ray(origin, new Vector3(-1, 0, 0)),
              List.of(scene.tile(coords)), BoardGeometry.floor(scene));
        assertNotNull(hit, "Picking bounds must include the obstacles above a flat slab");
        assertEquals(coords, hit.coords());
    }

    private static List<BoardSurface.Face> bridgeDeck(int exits) {
        return BoardBridgeTest.facets(exits).stream().map(face ->
              new BoardSurface.Face(face.a(), face.b(), face.c(), BoardSurface.Finish.TOP)).toList();
    }

    private static Hex hex(int fluff, int rough) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.ROUGH, rough));
        if (fluff != 0) { hex.addTerrain(new Terrain(Terrains.FLUFF, fluff)); }
        return hex;
    }

    private static BoardScene scene(int fluff, int step, boolean water) {
        return scene(fluff, step, water, BoardScene.Surface.GRASS);
    }

    /**
     * Asserts identical teeth rotations, on one lattice whose alternate rows sit halfway between each other's teeth,
     * and returns its spacing.
     */
    private static float staggeredSpacing(List<BoardScene.Feature> teeth) {
        assertEquals(1, teeth.stream().map(BoardScene.Feature::rotation).distinct().count());
        var rowsY = teeth.stream().map(BoardScene.Feature::y).distinct().sorted().toList();
        float pitch = Float.POSITIVE_INFINITY;
        for (int row = 1; row < rowsY.size(); row++) { pitch = Math.min(pitch, rowsY.get(row) - rowsY.get(row - 1)); }
        for (var a : teeth) {
            for (var b : teeth) {
                double rows = (b.y() - a.y()) / pitch;
                assertEquals(Math.rint(rows), rows, .001, "Rows are one tooth spacing apart");
                double columns = (b.x() - a.x()) / pitch + Math.abs(Math.rint(rows) % 2) / 2;
                assertEquals(Math.rint(columns), columns, .001,
                      "Adjacent rows put their teeth halfway between each other's teeth: " + a + " " + b);
            }
        }
        return pitch;
    }

    private static BoardScene scene(int fluff, int step, boolean water, BoardScene.Surface family) {
        return scene(hex(fluff, 1), step, water, family);
    }

    private static BoardScene scene(Hex centre, int step, boolean water, BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = x == 2 && y == 2 ? centre : new Hex(0);
                tiles.add(new BoardScene.Tile(coords, x > 2 ? step : 0, water ? 1 : -1, false, 0,
                      family, null, null,
                      BoardFeatures.capture(hex, coords, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void roughUsesEngineGroundAndStableBouldersOfTheLocalGeology() {
        Coords coords = new Coords(2, 2);
        for (var family : BoardScene.Surface.values()) {
            Hex hex = BoardScenes.roughHex(family, 1);
            assertEquals(family == BoardScene.Surface.SAND ? BoardScene.Surface.GRASS : family,
                  BoardFeatures.surface(hex), "Gameplay sand is cover over its theme, not a replacement geology");
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Rough artwork is replaced by the terrain engine");
            var features = BoardFeatures.capture(hex, coords, Map.of());
            assertEquals(9, features.size());
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
            assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()));
            hex.addTerrain(new Terrain(Terrains.ROUGH, 2));
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).size() > features.size(), "Ultra rough has more cover");
            hex.addTerrain(new Terrain(Terrains.WOODS, 1));
            var wooded = BoardFeatures.capture(hex, coords, Map.of());
            assertTrue(wooded.stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE),
                  "Rough coexists with the theme's woods, whose species/count are captured independently");
            assertEquals(18, wooded.stream().filter(f -> f.kind() == BoardScene.FeatureKind.BOULDER).count());
            hex.removeTerrain(Terrains.ROUGH);
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
                  .noneMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER), "Terrain edits remove the rocks");
        }
    }

    @Test
    void roughBouldersCoverTheCentreAndMergeIntoTheFinishedSlopes() {
        for (var family : BoardScene.Surface.values()) {
            for (int elevation : new int[] { -3, -1, 0, 1, 3 }) {
                BoardScene scene = BoardScenes.isolatedRough(family, elevation);
                var tile = scene.tile(new Coords(2, 2));
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> ground = new ArrayList<>(surface.groundFaces());
                for (var other : scene.tiles()) {
                    var neighbor = new BoardSurface(scene, other);
                    ground.addAll(neighbor.groundFaces());
                    ground.addAll(neighbor.walls(scene, BoardGeometry.floor(scene)));
                }
                ground.removeIf(f -> f.finish() == BoardSurface.Finish.OUTCROP || f.finish() == BoardSurface.Finish.DRESSING);
                var rocks = surface.rough;
                assertFalse(rocks.isEmpty(), "Rough must generate real rock geometry for " + family);
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                int buried = 0, exposed = 0;
                for (var face : rocks) {
                    assertEquals(family, surface.family(face));
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertNotNull(surface.relief.shade(p), "Rocks use the terrain shader's local geology");
                        float z = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN);
                        assertTrue(Float.isFinite(z), "The whole rock footprint has ground below it");
                        if (p.z < z) { buried++; } else { exposed++; }
                    }
                }
                assertTrue(buried > 0 && exposed > 0, "Rocks emerge from the terrain instead of floating above it");
                assertTrue(surface.height(center.x, center.y) > center.z + BoardGeometry.HEX_SCALE,
                      "Rough covers the centre even on an isolated high or low tile: " + family + " " + elevation);
                int visible = 0, outer = 0;
                for (var feature : tile.features()) {
                    float x = center.x + feature.x() * BoardGeometry.HEX_SCALE;
                    float y = center.y + feature.y() * BoardGeometry.HEX_SCALE;
                    float rock = BoardSurface.sampleHeight(rocks, x, y, Float.NEGATIVE_INFINITY);
                    if (rock > BoardSurface.sampleHeight(ground, x, y, center.z) + .1f * BoardGeometry.HEX_SCALE) {
                        visible++;
                        if (Math.hypot(feature.x(), feature.y()) > 20) { outer++; }
                    }
                }
                assertTrue(visible >= 7, family + " " + elevation + " lost Rough cover: " + visible);
                assertTrue(outer >= 3, "Rough must remain on the slopes and their feet: " + family + " " + elevation);
                assertEquals(center.z, UnitLandingSupports.terrain(scene, center.x, center.y, new BoardSurface.Cache()), .01f,
                      "Vehicles use the ground beneath the Rough");
                assertEquals(surface.faces, new BoardSurface(scene, tile).faces, "Rebuilding never reshuffles the rocks");
            }
        }
    }

    @Test
    void roughKeepsRoadExitsAndBuildingFoundationsClear() {
        Coords coords = new Coords(2, 2);
        Hex hex = BoardScenes.roughHex(BoardScene.Surface.GRASS, 2);
        hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9));
        for (var rock : BoardFeatures.capture(hex, coords, Map.of())) {
            assertTrue(Math.abs(rock.x()) >= 9 + 6 * rock.scale(), "North/south road clearance includes rock width");
        }
        hex.addTerrain(new Terrain(Terrains.BUILDING, 1));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of(Terrains.BUILDING, "building")).stream()
              .noneMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
    }
}
