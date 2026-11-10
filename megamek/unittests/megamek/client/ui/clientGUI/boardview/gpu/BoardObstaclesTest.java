/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardObstaclesTest {
    @Test
    void nativePaintAndUnresolvedModelsRemainEditableWithoutLoadingMeshes() {
        for (String kind : List.of("prop", "decal")) {
            var object = new megamek.common.board.BoardDecoration("unknown", kind, "missing/catalog-entry", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
            var feature = new BoardScene.Feature(object.asset(), 0, 0, 0, 1, 1, 0,
                  BoardScene.FeatureKind.PROP, 0, false, object);
            var scene = scene(CENTER, feature, 0);
            assertTrue(new BoardObstacles(scene, scene.tile(CENTER)).isEmpty());
        }
    }

    @Test
    void floatingNativeObjectsUseTheirAbsoluteHeightAndUniformScaleForClearance() {
        String model = "scenery/construction/bulldozer";
        var object = new megamek.common.board.BoardDecoration("floating", "prop", model, null,
              0, 0, 0, false, .5, megamek.common.board.BoardDecoration.Placement.absolute(8), 0);
        var feature = new BoardScene.Feature(model, 0, 0, 0, .5f, 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
        var scene = scene(CENTER, feature, 2);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        int occupied = 0;
        for (int x = -30; x <= 30; x += 2) {
            for (int y = -30; y <= 30; y += 2) {
                var point = BoardGeometry.center(CENTER, 2).add(x, y, 0);
                assertFalse(obstacles.obstructs(point, .1f, 1), "Floating objects cannot clear the ground below");
                point.z = 8 * BoardGeometry.level();
                if (obstacles.obstructs(point, .1f, 1)) { occupied++; }
            }
        }
        assertTrue(occupied > 0, "The actual elevated footprint still provides clearance");
    }

    @Test
    void aBridgePierKeepsGroundCoverOutOfItsFootingAtAnyHeight() {
        Coords near = BridgeScenes.START, far = near.translated(3);
        var joint = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
        for (boolean toggled : new boolean[] { true, false }) {
            var scene = BridgeScenes.span(2, true, 0, toggled ? java.util.Set.of(0, 1) : java.util.Set.of());
            for (Coords at : List.of(near, far)) {
                var obstacles = new BoardObstacles(scene, scene.tile(at));
                for (float z : new float[] { -60, -18 }) {
                    assertEquals(toggled, obstacles.obstructs(new Vector3(joint.x, joint.y, z), .1f, 1), "At the joint, z " + z);
                    assertFalse(obstacles.obstructs(BoardGeometry.center(at, 0).add(0, 0, z), .1f, 1), "Not at a hex centre");
                }
                // The deck itself lies at the water's level here: nothing grows through it, piers or not.
                assertTrue(obstacles.obstructs(BoardGeometry.center(at, 0), .1f, 1), "The deck at its own level");
            }
        }
    }

    @Test
    void aMaglevPierKeepsGroundCoverOutOfItsFootingWhileTheGroundUnderTheBeamStaysOpen() {
        Coords near = BridgeScenes.START, far = near.translated(3);
        var joint = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
        for (boolean toggled : new boolean[] { true, false }) {
            var scene = BridgeScenes.line(megamek.common.board.RailRoute.Kind.MAGLEV, new double[] { 2, 2 },
                  toggled ? java.util.Set.of(0, 1) : java.util.Set.of(), java.util.Set.of());
            for (Coords at : List.of(near, far)) {
                var obstacles = new BoardObstacles(scene, scene.tile(at));
                assertEquals(toggled, obstacles.obstructs(new Vector3(joint.x, joint.y, 0), .1f, 1), "At the joint");
                assertFalse(obstacles.obstructs(BoardGeometry.center(at, 0), .1f, 1), "The ground under the beam");
            }
        }
    }

    @Test
    void lowerBareBankRampsClearTheirLandingWithoutClearingBeneathTheSuspendedSpan() {
        var at = new Coords(4, 4);
        float scale = BoardGeometry.hexScale();
        for (int direction = 0; direction < 6; direction++) {
            var bankAt = at.translated(direction);
            var center = BoardGeometry.center(at, 0);
            var bank = BoardGeometry.center(bankAt, 0);
            var along = new Vector3(bank).sub(center).nor();
            var across = new Vector3(-along.y, along.x, 0);
            for (boolean wide : new boolean[] { false, true }) {
                var scene = BridgeScenes.lowerBank(at, direction, false);
                if (wide) {
                    var t = scene.tile(at);
                    int exits = (1 << direction) | (1 << ((direction + 3) % 6));
                    var tile = new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(),
                          t.surface(), t.decals(), t.decalsWithoutLimbs(),
                          t.features(), t.text(), t.liquid(), t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(),
                          t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(), t.groundCover(),
                          t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), t.appearance(), t.cover(), t.barren(),
                          t.deckRoad(), t.deckRoadExits(), t.deckMud(), t.railBed(), t.corridor(),
                          new RailRoute.Corridor(exits, exits, 0));
                    scene = BoardScenes.replace(scene, tile);
                }
                for (var owner : List.of(at, bankAt)) {
                    var obstacles = new BoardObstacles(scene, scene.tile(owner));
                    for (float t : new float[] { .3f, .4f, .65f, .8f, .92f }) {
                        for (float side : new float[] { -1, 0, 1 }) {
                            var root = new Vector3(center).lerp(bank, t).mulAdd(across, side * (wide ? 12 : 7) * scale);
                            root.z = t < .5f ? -BoardGeometry.level() : 0;
                            assertTrue(obstacles.obstructs(root, .1f * scale, .2f * scale),
                                  "Even short grass clears the complete solid ramp: direction=" + direction + " wide=" + wide);
                        }
                    }
                    float lane = wide ? BoardRoad.CORRIDOR_HALF : BoardRoad.Kind.PAVED.halfWidth;
                    for (int side : new int[] { -1, 1 }) {
                        var lip = new Vector3(center).lerp(bank, .8f)
                              .mulAdd(across, side * (lane + BoardBridgeFooting.terminalWidth() - .05f) * scale);
                        assertTrue(obstacles.obstructs(lip, 0, .2f * scale),
                              "The authored terminal lip extends outside the ordinary shoulder");
                    }
                    assertFalse(obstacles.obstructs(BoardGeometry.center(at, -1), scale, 2 * scale),
                          "Low vegetation beneath the level span remains visible");
                    var suspended = new Vector3(center).lerp(bank, .15f);
                    suspended.z = -BoardGeometry.level();
                    assertFalse(obstacles.obstructs(suspended, scale, 2 * scale),
                          "The solid reservation begins at the inset, not at the span centre");
                    // Beyond the widest reserved passage: a corridor's, with its landing lip.
                    var beside = new Vector3(center).lerp(bank, .8f).mulAdd(across, 30 * scale);
                    assertFalse(obstacles.obstructs(beside, scale, 2 * scale), "Free ground beside the ramp remains available");
                }
            }
        }
    }

    @Test
    void lowerBankClearanceDoesNotInventARampForNaturalOrHighSpans() {
        var at = new Coords(4, 4);
        var bankAt = at.translated(3);
        var base = BridgeScenes.lowerBank(at, 3, false);
        var natural = BridgeScenes.typed(base, HexAppearance.NATURAL_BRIDGE);
        var high = BoardScenes.replace(base, BridgeScenes.bridge(at, -1, 3, 9));
        var root = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(bankAt, 0), .8f);
        for (var scene : List.of(natural, high)) {
            assertFalse(new BoardObstacles(scene, scene.tile(bankAt)).obstructs(root, .1f, 1),
                  "Only a built span with a real lower-bank ramp reserves its landing");
        }
    }

    private static final Coords CENTER = new Coords(2, 2);
    private static final String TANK = "buildings/saxarba/fuel_tanks/fuel_tank_hard_15";

    @Test
    void entireScatterFootprintClearsSolidWhileCourtyardsAndOtherLevelsRemainOpen() {
        List<Vector3> triangles = new ArrayList<>();
        rectangle(triangles, -10, -10, -3, 10);
        rectangle(triangles, 3, -10, 10, 10);
        var footprint = new BoardObstacles.Footprint(triangles, 0, 8,
              new BoundingBox(new Vector3(-10, -10, 0), new Vector3(10, 10, 8)));
        assertTrue(footprint.obstructs(new Vector3(5, 0, 0), .3f, 1));
        assertTrue(footprint.obstructs(new Vector3(2, 0, 0), 1.1f, 1), "The whole rock clears the wall");
        assertFalse(footprint.obstructs(new Vector3(0, 0, 0), 1, 1), "A courtyard is still open ground");
        assertFalse(footprint.obstructs(new Vector3(15, 0, 0), 1, 1));
        assertFalse(footprint.obstructs(new Vector3(5, 0, -4), .3f, 1), "A lower cliff ledge remains clear");
    }

    @Test
    void neighboringStructureOverhangIsCheckedAndClearGroundIsRetained() {
        Coords neighbor = CENTER.translated(0);
        float x = (BoardGeometry.centerX(CENTER) - BoardGeometry.centerX(neighbor)) / BoardGeometry.hexScale();
        float y = (BoardGeometry.centerY(CENTER) - BoardGeometry.centerY(neighbor)) / BoardGeometry.hexScale();
        var feature = new BoardScene.Feature(TANK, x, y, 27, 1, 2, 0, BoardScene.FeatureKind.FUEL_TANK);
        BoardScene scene = scene(neighbor, feature, 0);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        assertFalse(obstacles.isEmpty());
        int occupied = 0, clear = 0;
        Vector3 center = BoardGeometry.center(CENTER, 0);
        for (int ix = -30; ix <= 30; ix += 3) {
            for (int iy = -30; iy <= 30; iy += 3) {
                boolean blocked = obstacles.obstructs(new Vector3(center.x + ix, center.y + iy, 0), .1f, 1);
                if (blocked) { occupied++; } else { clear++; }
            }
        }
        assertTrue(occupied > 10, "An adjacent tank group occupies its actual moved footprint");
        assertTrue(clear > 10, "The rest of the hex remains available");
        assertFalse(new BoardObstacles(scene, scene.tile(new Coords(0, 0)))
              .obstructs(BoardGeometry.center(new Coords(0, 0), 0), 1, 1));
    }

    @Test
    void rimRocksCannotIntrudeIntoAnAuthoredStructure() {
        var small = new BoardScene.Feature(TANK, 18, 0, 0, 1, 2, 0, BoardScene.FeatureKind.FUEL_TANK);
        var clear = new BoardScene.Feature(TANK, 300, 0, 0, 1, 2, 0, BoardScene.FeatureKind.FUEL_TANK);
        BoardScene original = scene(CENTER, clear, 3), occupied = scene(CENTER, small, 3);
        var baseline = new BoardSurface(original, original.tile(CENTER)).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
        var actual = new BoardSurface(occupied, occupied.tile(CENTER)).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
        var obstacle = new BoardObstacles(occupied, occupied.tile(CENTER));
        assertTrue(baseline.size() > 0);
        assertTrue(actual.size() < baseline.size(), "The structure must exclude overlapping rim rocks");
        assertTrue(actual.size() > 0, "Rocks on the free side of the cliff are retained");
        for (var face : actual) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                assertFalse(obstacle.obstructs(p, 0, 0), "No retained rock vertex penetrates the structure");
            }
        }
    }

    @Test
    void modularBuildingUsesItsSelectedAuthoredPartsWithoutGpuResources() {
        var feature = new BoardScene.Feature("buildings/standard/fortress-light",
              0, 0, 0, 1, 4, 0, BoardScene.FeatureKind.BUILDING);
        BoardScene scene = scene(CENTER, feature, 0);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        assertFalse(obstacles.isEmpty());
        int occupied = 0;
        Vector3 center = BoardGeometry.center(CENTER, 0);
        for (int x = -30; x < 30; x += 5) {
            for (int y = -30; y < 30; y += 5) {
                if (obstacles.obstructs(new Vector3(center.x + x, center.y + y, 0), .5f, 1)) { occupied++; }
            }
        }
        assertTrue(occupied > 0 && occupied < 144);
    }

    @Test
    void modularClearancePreservesAuthoredXyOffsetsAndDiscardsVerticalPlacement() {
        var data = GpuBuildingTest.lods(GpuBuildingTest.ASSET).getFirst();
        var original = clearance(ConnectedKit.load(List.of(data)));
        // Both baked geometry and node transforms determine XY placement; only Z is normalized.
        for (var mesh : data.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                mesh.vertices[i] += 71;
                mesh.vertices[i + 1] -= 23;
                mesh.vertices[i + 2] -= 100;
            }
        }
        for (var node : data.nodes) { node.translation.add(900, -400, -257); }
        var moved = clearance(ConnectedKit.load(List.of(data)));
        assertEquals(original.size(), moved.size());
        for (int i = 0; i < original.size(); i++) {
            Vector3 expected = new Vector3(original.get(i)).add(971, -423, 0);
            assertTrue(expected.epsilonEquals(moved.get(i), .0001f), expected + " " + moved.get(i));
        }
    }

    /** A three-level assembly's clearance triangles, as BoardObstacles takes them from a custom kit. */
    private static List<Vector3> clearance(ConnectedKit.Kit kit) {
        List<Vector3> result = new ArrayList<>();
        for (var piece : ConnectedKit.placements(kit, 0, 3, new int[6], 0, 7)) {
            result.addAll(ConnectedKit.triangles(kit, 0, piece));
        }
        return result;
    }

    @Test
    void capturedScatterIsRejectedAfterSettlingAgainstAnAdjacentObject() {
        Coords neighbor = CENTER.translated(0);
        float x = (BoardGeometry.centerX(CENTER) - BoardGeometry.centerX(neighbor)) / BoardGeometry.hexScale();
        float y = (BoardGeometry.centerY(CENTER) - BoardGeometry.centerY(neighbor)) / BoardGeometry.hexScale();
        var feature = new BoardScene.Feature(TANK, x, y, 0, 1, 2, 0, BoardScene.FeatureKind.FUEL_TANK);
        BoardScene scene = scene(neighbor, feature, 0);
        BoardScene clear = scene(neighbor, new BoardScene.Feature(TANK, 300, 0, 0, 1, 2, 0,
              BoardScene.FeatureKind.FUEL_TANK), 0);
        var surface = new BoardSurface(scene, scene.tile(CENTER));
        var freeSurface = new BoardSurface(clear, clear.tile(CENTER));
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        Vector3 center = BoardGeometry.center(CENTER, 0), spot = null;
        for (int ix = -20; ix <= 20 && spot == null; ix += 4) {
            for (int iy = -20; iy <= 20; iy += 4) {
                var point = new Vector3(center.x + ix, center.y + iy, 0);
                if (obstacles.obstructs(point, 0, 1)) { spot = point; break; }
            }
        }
        assertTrue(spot != null);
        var scatter = new BoardScene.Feature("scatter-rock", (spot.x - center.x) / BoardGeometry.hexScale(),
              (spot.y - center.y) / BoardGeometry.hexScale(), 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
        var blocked = mesh();
        GpuScatter.build(blocked, scene.tile(CENTER), surface, scatter);
        assertEquals(0, blocked.getNumIndices());
        var available = mesh();
        GpuScatter.build(available, clear.tile(CENTER), freeSurface, scatter);
        assertTrue(available.getNumIndices() > 0, "The same scatter remains visible on free ground");
    }

    private static MeshBuilder mesh() {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        return mesh;
    }

    @Test
    void deepWaterKeepsGeologicalRocksButRejectsCosmeticScatterAtEveryDetail() {
        for (var lod : TerrainLod.values()) {
            for (int depth : new int[] { 1, 2, 4 }) {
                BoardScene scene = water(depth, List.of());
                var surface = new BoardSurface(scene, scene.tile(CENTER), lod);
                var rocks = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
                if (lod.dressing) {
                    assertFalse(rocks.isEmpty(), lod + " depth=" + depth + ": cliff and bed boulders remain");
                }
                assertTrue(rocks.stream().allMatch(face -> surface.relief.shade(face.a()).kind() == BoardRelief.Kind.ROCK),
                      "Positive-depth water must not acquire cosmetic bushes");
                assertTrue(surface.faces.stream().anyMatch(face -> face.finish() == BoardSurface.Finish.BED));
                for (String asset : List.of("scatter-rock", "scatter-plant")) {
                    var scatter = new BoardScene.Feature(asset, 16, 0, 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
                    var mesh = mesh();
                    GpuScatter.build(mesh, scene.tile(CENTER), surface, scatter);
                    assertEquals(0, mesh.getNumIndices(), "Deep-water cosmetics remain excluded: " + asset);
                }
            }
        }
        var boulder = new BoardScene.Feature("boulder", 15, 0, 0, 1, .5f, 0, BoardScene.FeatureKind.BOULDER);
        BoardScene rough = water(2, List.of(boulder));
        assertTrue(new BoardSurface(rough, rough.tile(CENTER)).faces.stream()
              .anyMatch(face -> face.finish() == BoardSurface.Finish.OUTCROP), "Gameplay Rough cover is retained");
    }

    @Test
    void depthZeroAllowsVisibleStonesButRejectsHiddenUnderwaterDressing() {
        BoardScene shallow = water(0, List.of());
        var tile = shallow.tile(CENTER);
        float water = BoardGeometry.waterZ(tile);
        Vector3 center = BoardGeometry.center(CENTER, 0);
        assertTrue(BoardScatter.allowed(tile));
        assertFalse(BoardScatter.visible(shallow, tile, new Vector3(center.x, center.y, water - 2), 1));
        assertTrue(BoardScatter.visible(shallow, tile, new Vector3(center.x, center.y, water - .2f), 1));
        assertFalse(BoardScatter.allowed(water(1, List.of()).tile(CENTER)));
        var surface = new BoardSurface(shallow, tile);
        for (String asset : List.of("scatter-rock", "scatter-plant")) {
            var scatter = new BoardScene.Feature(asset, 16, 0, 17, 1, .15f, 0, BoardScene.FeatureKind.SCATTER);
            var mesh = mesh();
            GpuScatter.build(mesh, tile, surface, scatter);
            assertTrue(mesh.getNumIndices() > 0, "Visible depth-zero scatter remains allowed: " + asset);
        }
    }

    private static BoardScene water(int depth, List<BoardScene.Feature> features) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                boolean pool = coords.equals(CENTER);
                tiles.add(new BoardScene.Tile(coords, pool ? 0 : 3, pool ? depth : -1, false, 0,
                      BoardScene.Surface.SAND, null, null, pool ? features : List.of(), List.of(),
                      pool ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void rectangle(List<Vector3> points, float left, float bottom, float right, float top) {
        points.addAll(List.of(new Vector3(left, bottom, 0), new Vector3(right, bottom, 0), new Vector3(right, top, 0),
              new Vector3(right, top, 0), new Vector3(left, top, 0), new Vector3(left, bottom, 0)));
    }

    @Test
    void everyGeyserClearsGrassFromItsBasinAndKeepsSurroundingMeadow() {
        for (String state : List.of("water-dormant", "water-erupting", "magma", "water-dormant-b", "water-erupting-b",
              "magma-b")) {
            var feature = new BoardScene.Feature("scenery/geysers/" + state,
                  7, -3, 27, .8f, 0, 0, BoardScene.FeatureKind.SCENERY);
            BoardScene scene = scene(CENTER, feature, 0, BoardScene.Surface.GRASS);
            var tile = scene.tile(CENTER);
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
            var roots = GpuGroundCover.plant(scene, tile, surface, null);
            assertTrue(roots.size / 4 > 1500, "The surrounding meadow must remain planted: " + state);
            float scale = BoardGeometry.hexScale();
            float x = BoardGeometry.centerX(CENTER) + 7 * scale, y = BoardGeometry.centerY(CENTER) - 3 * scale;
            // Every look's solid sinter or spatter reaches at least 10.1 model units from its vent (magma-b's, the
            // smallest); each outline follows its photograph, so grass meets it unevenly beyond.
            float basin = 10 * .8f * scale;
            int outside = 0;
            for (int i = 0; i < roots.size; i += 4) {
                float dx = roots.get(i) - x, dy = roots.get(i + 1) - y;
                assertTrue(dx * dx + dy * dy > basin * basin, "No grass may emerge through the basin: " + state);
                if (dx * dx + dy * dy > 30 * 30 * scale * scale) { outside++; }
            }
            assertTrue(outside > 500, "Grass outside the geyser footprint remains available");
            var obstacles = new BoardObstacles(scene, tile);
            assertTrue(obstacles.obstructs(new Vector3(x, y, 0), 0, 1));
            assertFalse(obstacles.obstructs(new Vector3(x, y, -10 * scale), 0, scale),
                  "The scenery footprint must not clear a separate lower ledge");
        }
    }

    private static BoardScene scene(Coords structure, BoardScene.Feature feature, int elevation) {
        return scene(structure, feature, elevation, BoardScene.Surface.SAND);
    }

    private static BoardScene scene(Coords structure, BoardScene.Feature feature, int elevation, BoardScene.Surface surface) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(structure) ? elevation : 0, -1, false, 0,
                      surface, null, null,
                      coords.equals(structure) ? List.of(feature) : List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
