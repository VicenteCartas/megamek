/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardWallTest {
    static final Coords CENTER = new Coords(4, 4);

    @Test
    void concurrentTerrainWorkersAssembleIdenticalGeometryFromTheSharedKit() throws Exception {
        boolean allocateIterators = com.badlogic.gdx.utils.Collections.allocateIterators;
        var workers = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try {
            com.badlogic.gdx.utils.Collections.allocateIterators = false;
            int[] expected = new int[BoardWall.LODS];
            for (int lod = 0; lod < expected.length; lod++) {
                expected[lod] = fingerprint(BoardWall.model("buildings/wall-63", 3, lod));
            }
            List<Future<?>> results = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int round = 0; round < 32; round++) {
                        int lod = round % expected.length;
                        assertEquals(expected[lod], fingerprint(BoardWall.model("buildings/wall-63", 3, lod)),
                              "Parallel terrain workers must receive the same complete materials, nodes and geometry");
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var result : results) { result.get(30, TimeUnit.SECONDS); }
        } finally {
            start.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                com.badlogic.gdx.utils.Collections.allocateIterators = allocateIterators;
            }
        }
    }

    private static int fingerprint(ModelData model) {
        int hash = 1;
        for (int i = 0; i < model.materials.size; i++) {
            hash = 31 * hash + model.materials.get(i).id.hashCode();
        }
        for (int i = 0; i < model.nodes.size; i++) {
            var node = model.nodes.get(i);
            hash = 31 * hash + node.id.hashCode();
            hash = 31 * hash + node.meshId.hashCode();
            for (var part : node.parts) {
                hash = 31 * hash + part.meshPartId.hashCode();
                hash = 31 * hash + part.materialId.hashCode();
            }
        }
        for (int i = 0; i < model.meshes.size; i++) {
            var mesh = model.meshes.get(i);
            hash = 31 * hash + Arrays.hashCode(mesh.vertices);
            for (var part : mesh.parts) { hash = 31 * hash + Arrays.hashCode(part.indices); }
        }
        return hash;
    }

    @Test
    void everyExitMaskHasSolidGeometryWithoutInventedConnections() {
        for (int exits = 0; exits < 64; exits++) {
            var triangles = BoardWall.triangles("buildings/wall-" + exits);
            var bounds = new BoundingBox().inf();
            triangles.forEach(bounds::ext);
            assertEquals(0, bounds.min.z, .001f);
            assertTrue(bounds.max.z > BoardGeometry.MODEL_LEVEL_HEIGHT && bounds.max.z <= BoardGeometry.MODEL_LEVEL_HEIGHT + 8.01f);
            Vector3 hit = new Vector3();
            assertTrue(Intersector.intersectRayTriangles(down(.31f, .27f), triangles, hit));
            assertTrue(hit.z >= BoardGeometry.MODEL_LEVEL_HEIGHT
                  && hit.z < BoardGeometry.MODEL_LEVEL_HEIGHT + 2, "The solid deck caps the last full-height floor");
            if (exits == 0) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Vector3 arm = joint(direction).scl(.98f);
                boolean present = Intersector.intersectRayTriangles(down(arm.x, arm.y), triangles, hit);
                assertEquals((exits & 1 << direction) != 0, present,
                      "Only the authored wall exits reach their hex edge: mask " + exits + ", direction " + direction);
            }
        }
    }

    @Test
    void opposingExitsMeetAtOneSharedPhysicalEndpoint() {
        for (int direction = 0; direction < 6; direction++) {
            Vector3 joint = joint(direction);
            var first = BoardWall.triangles("buildings/wall-" + (1 << direction));
            var second = BoardWall.triangles("buildings/wall-" + (1 << ((direction + 3) % 6)));
            Vector3 a = new Vector3(), b = new Vector3();
            assertTrue(Intersector.intersectRayTriangles(down(joint.x * .999999f, joint.y * .999999f), first, a));
            assertTrue(Intersector.intersectRayTriangles(down(-joint.x * .999999f, -joint.y * .999999f), second, b));
            b.add(new Vector3(joint).scl(2));
            assertEquals(0, a.dst(b), .001f, "Both hexes use the same mesh endpoint");
        }
    }

    @Test
    void tallWallsKeepTheirFullFloorsAndContinuousDeckAtEveryJoin() {
        for (int levels : new int[] { 1, 3, 30 }) {
            float height = levels * BoardGeometry.MODEL_LEVEL_HEIGHT;
            for (int exits = 0; exits < 64; exits++) {
                var triangles = BoardWall.triangles("buildings/wall-" + exits, levels);
                var hit = new Vector3();
                assertTrue(Intersector.intersectRayTriangles(
                      new Ray(new Vector3(.31f, .27f, height + 2), new Vector3(0, 0, -1)), triangles, hit));
                assertTrue(hit.z >= height && hit.z < height + 2);
                for (int direction = 0; direction < 6; direction++) {
                    if ((exits & 1 << direction) == 0) { continue; }
                    // Probe the seam within float roundoff, not inside differently scaled end bevels. Exact edge
                    // rays can miss the upper triangle after its rotation and hit the wider foundation instead.
                    var joint = joint(direction).scl(.999999f);
                    assertTrue(Intersector.intersectRayTriangles(
                          new Ray(joint.add(0, 0, height + 2), new Vector3(0, 0, -1)), triangles, hit));
                    float floor = hit.z;
                    assertTrue(floor >= height && floor < height + 2, "The roof caps the last full floor");
                    var neighbour = BoardWall.triangles("buildings/wall-" + (1 << ((direction + 3) % 6)), levels);
                    var other = joint(direction).scl(-.999999f).add(0, 0, height + 2);
                    assertTrue(Intersector.intersectRayTriangles(new Ray(other, new Vector3(0, 0, -1)), neighbour, hit));
                    assertEquals(floor, hit.z, .001f, "The deck meets its neighbor without a raised end cap: height "
                          + levels + ", exits " + exits + ", direction " + direction);
                    var outward = joint(direction).nor();
                    var start = joint(direction).mulAdd(outward, .5f).add(0, 0, height + 1);
                    if (Intersector.intersectRayTriangles(new Ray(start, new Vector3(outward).scl(-1)), triangles, hit)) {
                        assertTrue(start.dst(hit) > 1, "No thin vertical baffle may close the channel at the exact module seam");
                    }
                }
            }
        }
    }

    @Test
    void fortressBodyMatchesTheRoadWidthAndItsArmorHasRealProjection() {
        var solid = BoardWall.triangles(BoardWall.layout("buildings/wall-9", 1, 90));
        var full = new BoundingBox().inf();
        solid.forEach(full::ext);
        assertTrue(full.getWidth() > 2 * HexTile.ROAD_HALF_WIDTH + 6
              && full.getWidth() < 2 * HexTile.ROAD_HALF_WIDTH + 12,
              "Heavy buttresses project beyond the thirty-unit road-width body");
        assertTrue(full.max.z > HexTile.LEVEL_HEIGHT && full.max.z < HexTile.LEVEL_HEIGHT + 8,
              "Only the shallow roof details extend above the last full floor");
        var hit = new Vector3();
        assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(40, 0, 9), new Vector3(-1, 0, 0)), solid, hit));
        assertTrue(hit.x >= HexTile.ROAD_HALF_WIDTH, "The authored body and armor have real side geometry");
    }

    @Test
    void assembledLodsRetainEmbeddedMaterialsAndHeightChangesPreserveAtlasUvs() {
        for (String asset : List.of("buildings/wall-0", "buildings/wall-9", "buildings/wall-63")) {
            int previous = Integer.MAX_VALUE;
            for (int lod = 0; lod < BoardWall.LODS; lod++) {
                var model = (RigidGlb.Data) BoardWall.model(asset, 3, lod);
                int indices = 0;
                for (var mesh : model.meshes) {
                    for (var part : mesh.parts) { indices += part.indices.length; }
                }
                assertTrue(indices < previous, "Each authored level actually reduces the wall geometry");
                previous = indices;
                assertFalse(model.images.isEmpty(), "The assembly retains the authored embedded atlas");
                assertEquals(model.materials.size, model.surfaces.size());
                for (var material : model.materials) { assertNotNull(material.textures); }
            }
            int far = fingerprint(BoardWall.model(asset, 3, 1));
            assertEquals(far, fingerprint(BoardWall.model(asset, 3, 2)), "The ordinary loader reuses LOD1 for slot2");
            assertEquals(far, fingerprint(BoardWall.model(asset, 3, 3)), "The ordinary loader reuses LOD1 for slot3");
        }
        var near = BoardWall.model("buildings/wall-63", 3);
        var tall = BoardWall.model("buildings/wall-63", 30);
        assertTrue(tall.meshes.size > near.meshes.size, "Tall walls repeat the authored body instead of stretching its details");
        for (var mesh : tall.meshes) {
            String name = mesh.id.replaceAll("/floor0-at-[0-9]+/", "/floor0-at-1/")
                  .replace("/roof0-at-30/", "/roof0-at-3/");
            var reference = near.meshes.first();
            for (var candidate : near.meshes) { if (candidate.id.equals(name)) { reference = candidate; break; } }
            assertEquals(name, reference.id);
            float[] source = reference.vertices, stretched = mesh.vertices;
            assertEquals(source.length, stretched.length);
            float lowest = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < source.length; i += RigidGlb.STRIDE) {
                assertEquals(source[i], stretched[i]);
                assertEquals(source[i + 1], stretched[i + 1]);
                assertEquals(source[i + 10], stretched[i + 10]);
                assertEquals(source[i + 11], stretched[i + 11], "Height fitting never reprojects the atlas");
                float z = source[i + 2];
                if (name.contains("/base0-")) { assertEquals(z, stretched[i + 2], .001f); }
                if (name.contains("/roof0-")) { assertEquals(54 - z, 540 - stretched[i + 2], .001f); }
                lowest = Math.min(lowest, stretched[i + 2]);
                highest = Math.max(highest, stretched[i + 2]);
                assertEquals(1, new Vector3(stretched[i + 3], stretched[i + 4], stretched[i + 5]).len(), .001f);
            }
            if (name.contains("/floor0-")) { assertEquals(18, highest - lowest, .001f, "Every repeated floor keeps its exact height"); }
        }
    }

    @Test
    void wallFootprintsKeepPlantsOutsideTheSolidAndBelowRaisedWalls() {
        var scene = scene(Map.of(CENTER, 9), 3, 2);
        var obstacles = new BoardObstacles(scene, scene.tile(CENTER));
        Vector3 base = BoardGeometry.center(CENTER, 2);
        assertTrue(obstacles.obstructs(new Vector3(base).add(0, 0, .1f), .1f, 1));
        assertFalse(obstacles.obstructs(new Vector3(base).add(20, 0, .1f), .1f, 1));
        assertFalse(obstacles.obstructs(new Vector3(base).add(0, 0, 3 * BoardGeometry.level() + 8 * BoardGeometry.hexScale()), .1f, 1));
        assertFalse(obstacles.obstructs(new Vector3(base).add(0, 0, -4), .1f, 1));
    }

    @Test
    void wallCaptureUsesTheAuthoredExitsHeightAndEnhancedGround() {
        Hex hex = new Hex(2, "building:5:37;bldg_elev:3;bldg_cf:120", "");
        var models = TilesetScenery.structureModels(hex);
        var features = BoardFeatures.capture(hex, CENTER, models);
        var wall = features.stream().filter(feature -> BoardWall.supports(feature.asset())).findFirst().orElseThrow();
        assertEquals("buildings/wall-37", wall.asset());
        assertEquals(BoardScene.FeatureKind.BUILDING, wall.kind());
        assertEquals(3, wall.height());
        assertTrue(BoardFeatures.detailedGround(hex, models));
    }

    @Test
    void authoredWallModelKeepsItsAppearanceAndSolidRulesHeight() {
        Hex hex = new Hex(2, "building:5:4;bldg_elev:7;bldg_cf:120", "");
        String asset = "buildings/saxarba/building_hard/building_hard_04";
        hex.setAppearance(Map.of("building", new HexAppearance(null, asset, null, null)));
        var models = TilesetScenery.structureModels(hex);
        assertEquals(asset, models.get(Terrains.BUILDING));
        var feature = BoardFeatures.capture(hex, CENTER, models).stream()
              .filter(part -> part.kind() == BoardScene.FeatureKind.BUILDING).findFirst().orElseThrow();
        assertEquals(asset, feature.asset());
        assertEquals(7, feature.height());
        assertTrue(BoardWall.building(BoardScene.Cover.capture(hex, null, CENTER), feature),
              "A supplied model on a wall must not introduce occupiable storeys or hollow-building cutaways");
        assertTrue(BoardFeatures.detailedGround(hex, models));
    }

    static BoardScene scene(Map<Coords, Integer> walls, int height, int elevation) {
        return BoardScenes.scene(coords -> {
            var tile = BoardScenes.tile(coords, BoardScene.Surface.GRASS, elevation, -1, 0);
            var features = walls.containsKey(coords) ? List.of(new BoardScene.Feature(
                  "buildings/wall-" + walls.get(coords), 0, 0, 0, 1, height, 0, BoardScene.FeatureKind.BUILDING)) : List.<BoardScene.Feature>of();
            return new BoardScene.Tile(coords, elevation, -1, false, 0, tile.surface(), null, null, features, List.of(),
                  BoardLiquid.NONE, null, true);
        });
    }

    private static Vector3 joint(int direction) {
        return BoardGeometry.center(CENTER.translated(direction), 0).sub(BoardGeometry.center(CENTER, 0))
              .scl(.5f / BoardGeometry.hexScale());
    }

    private static Ray down(float x, float y) { return new Ray(new Vector3(x, y, 100), new Vector3(0, 0, -1)); }
}
