/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.Test;

class GpuAuthoredPartitionTest {
    @Test void authoredFramesStayIdenticalWhenWorkersAndTheRenderThreadRotateDifferentObjects() throws Exception {
        GdxNativesLoader.load();
        var at = new Coords(4, 6);
        var objects = List.of(
              new BoardDecoration("a", "prop", "buildings/test-frame", null, .2, -.3, 75, false, 2,
                    BoardDecoration.Placement.ground(), 0, false, 45, -31),
              new BoardDecoration("b", "prop", "buildings/test-frame", null, -.7, .4, 23, true, .7,
                    BoardDecoration.Placement.ground(), 0, false, -26, 81)
                    .withStretch(new BoardDecoration.Stretch(.4, 1.7, 2.1)));
        var expected = objects.stream().map(object -> GpuAuthoredBuilding.frame(at, object, 3).val.clone()).toList();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var runs = java.util.stream.IntStream.range(0, 4).mapToObj(worker -> workers.submit(() -> {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    int pose = (i + worker) % objects.size();
                    assertArrayEquals(expected.get(pose), GpuAuthoredBuilding.frame(at, objects.get(pose), 3).val, .0001f,
                          "Concurrent mesh slicing and editor transforms must use the same placement frame");
                }
                return null;
            })).toList();
            start.countDown();
            for (var run : runs) { run.get(10, TimeUnit.SECONDS); }
        }
    }

    @Test void tiltedSlicesHaveHorizontalRoofSupportsAtTheirBakedHeightAndNoInteriorGeometry() {
        GdxNativesLoader.load();
        var anchor = new Coords(8, 8);
        for (double[] angles : new double[][] {{45, 0}, {0, -45}, {23, -31}, {90, 0}, {0, 90}}) {
            var object = new BoardDecoration("tilted-debug", "prop", "buildings/debug/debug-authored", null,
                  0, 0, 37, false, 2, BoardDecoration.Placement.absolute(3), 0, false, angles[0], angles[1]);
            var covered = BuildingVolumes.coverage(anchor, object, 16, 16, at -> new megamek.common.Hex(0));
            assertFalse(covered.isEmpty());
            var members = List.copyOf(covered.keySet());
            var source = GpuAuthoredBuilding.load(object.asset());
            for (var entry : covered.entrySet()) {
                // Even a stale YES policy must not manufacture horizontal floors inside the tilted shell.
                var layout = new GpuAuthoredBuilding.Layout(entry.getKey(), anchor, object, 3, 0, entry.getValue(),
                      BuildingInteriors.YES, members, 16, 16);
                var prepared = GpuAuthoredBuilding.prepare(layout, source);
                assertTrue(prepared.floors().isEmpty()); assertTrue(prepared.columns().isEmpty());
                assertFalse(prepared.roof().isEmpty(), "Every baked building hex has a roof support: " + entry);
                assertTrue(prepared.roof().stream().allMatch(p -> p.z == entry.getValue() * HexTile.LEVEL_HEIGHT));
                assertFalse(prepared.levels().getFirst().meshes.isEmpty());
                var cell = BuildingVolumes.hex(entry.getKey());
                float cx = HexTile.centerX(entry.getKey(), HexTile.MODEL_WIDTH), cy = HexTile.centerY(entry.getKey(), HexTile.HEIGHT);
                double roofArea = 0;
                for (int i = 0; i < prepared.roof().size(); i += 3) {
                    var a = prepared.roof().get(i); var b = prepared.roof().get(i + 1); var c = prepared.roof().get(i + 2);
                    roofArea += new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len() * .5;
                    var p = new Vector3(a).add(b).add(c).scl(1f / 3);
                    assertTrue(cell.contains(p.x + cx, p.y + cy), "Roof support stays in its own hex");
                }
                assertTrue(roofArea >= BuildingVolumes.area(cell) * .5 - .1, "The usable roof covers the footprint, not just a thin slope band");
            }
        }
    }

    @Test void debugAuthoredAssetUsesItsVolumesForInteriorFloorsAndRoofSupport() {
        GdxNativesLoader.load();
        var at = new Coords(4, 4);
        var object = new BoardDecoration("debug", "prop", "buildings/debug/debug-authored", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0);
        var members = List.of(at, at.translated(1));
        for (Coords cell : members) {
            int height = cell.equals(at) ? 2 : 4;
            var layout = new GpuAuthoredBuilding.Layout(cell, at, object, 0, 0, height, BuildingInteriors.YES, members, 9, 9);
            var prepared = GpuAuthoredBuilding.prepare(layout, GpuAuthoredBuilding.load(object.asset()));
            assertEquals(height, prepared.floors().size());
            float notchX = cell.equals(at) ? -20 : 59 - HexTile.MODEL_WIDTH * .75f;
            float notchY = cell.equals(at) ? 5 : 55 - HexTile.HEIGHT * .5f;
            for (var floor : prepared.floors()) {
                assertTrue(covers(floor, 2, 3), "Each storey has a usable interior at " + cell);
                assertFalse(covers(floor, notchX, notchY), "Generated floors must not fill the authored notch");
            }
            assertFalse(prepared.roof().isEmpty(), "The helper's top supplies roof supports");
            assertTrue(prepared.roof().stream().allMatch(p -> p.z == height * HexTile.LEVEL_HEIGHT));
            assertFalse(prepared.levels().getFirst().meshes.isEmpty(), "The visible shell survives hex slicing");
        }
    }

    @Test void sharesConserveSurfaceAreaAndOwnOnlyTheirVoronoiRegionIncludingDistantOverhangs() {
        GdxNativesLoader.load();
        var source = partitionBox();
        var anchor = new Coords(3, 3);
        var members = List.of(new Coords(2, 2), new Coords(4, 2), anchor, new Coords(2, 4), new Coords(4, 4));
        for (double rotation : List.of(0.0, 37.0, 90.0)) {
            var object = new BoardDecoration("fixture", "prop", "buildings/test-partition", null, 0, 0, rotation, rotation == 90,
                  1, BoardDecoration.Placement.ground(), 0).withStretch(new BoardDecoration.Stretch(.9, 1.1, 1.2));
            double area = 0;
            var localBounds = new BoundingBox().inf();
            for (Coords cell : members) {
                var layout = new GpuAuthoredBuilding.Layout(cell, anchor, object, 0, 0, 0, BuildingInteriors.NO, members, 8, 8);
                var prepared = GpuAuthoredBuilding.prepare(layout, List.of(source));
                localBounds.ext(prepared.localBounds());
                var share = AuthoredBoardClipTest.mesh(prepared.levels().getFirst());
                for (var part : share.parts.values()) {
                    for (int i = 0; i < part.size; i += RigidGlb.STRIDE) {
                        double x = part.items[i] + HexTile.centerX(cell, HexTile.MODEL_WIDTH);
                        double y = part.items[i + 1] + HexTile.centerY(cell, HexTile.HEIGHT);
                        double own = distance(x, y, cell);
                        for (Coords other : members) { assertTrue(own <= distance(x, y, other) + .5, "Share escaped its region"); }
                    }
                    for (int i = 0; i < part.size; i += 3 * RigidGlb.STRIDE) {
                        var a = point(part.items, i); var b = point(part.items, i + RigidGlb.STRIDE); var c = point(part.items, i + 2 * RigidGlb.STRIDE);
                        area += b.sub(a).crs(c.sub(a)).len() * .5;
                    }
                }
            }
            double width = 290 * .9, depth = 180 * 1.1, height = 30 * 1.2;
            assertEquals(2 * (width * depth + width * height + depth * height), area, 1.5, "No missing or duplicate exterior at " + rotation);
            assertTrue(localBounds.min.epsilonEquals(new Vector3(-120, -90, 0), .002f), "Slicing preserves the original lower corner");
            assertTrue(localBounds.max.epsilonEquals(new Vector3(170, 90, 30), .002f), "The selection envelope stays in object space after rotation, mirror and stretch");
        }
    }

    @Test void orientedEnvelopeOnlyContainsTheRetainedMapPart() {
        GdxNativesLoader.load();
        var at = new Coords(0, 0);
        var object = new BoardDecoration("clipped-box", "prop", "buildings/test-partition", null,
              0, 0, 90, false, 1, BoardDecoration.Placement.ground(), 0);
        var layout = new GpuAuthoredBuilding.Layout(at, at, object, 0, 0, 0, BuildingInteriors.NO, List.of(at), 1, 1);
        var prepared = GpuAuthoredBuilding.prepare(layout, List.of(partitionBox()));
        // The large box fills this one-hex board. A quarter turn swaps its retained width and length in object space.
        assertTrue(prepared.localBounds().min.epsilonEquals(new Vector3(-36, -HexTile.MODEL_WIDTH / 2, 0), .002f));
        assertTrue(prepared.localBounds().max.epsilonEquals(new Vector3(36, HexTile.MODEL_WIDTH / 2, 30), .002f));
    }

    private static ModelData partitionBox() {
        var mesh = new GeneratedMesh();
        mesh.slab("body", List.of(new double[] {-120, -90}, new double[] {170, -90}, new double[] {170, 90}, new double[] {-120, 90}), 0, 30, true);
        var materials = new ModelData(); var material = new ModelMaterial(); material.id = "body"; materials.materials.add(material);
        return mesh.data("partition-fixture-lod0", materials);
    }

    private static boolean covers(List<Vector3> triangles, float x, float y) {
        for (int i = 0; i < triangles.size(); i += 3) {
            var a = triangles.get(i); var b = triangles.get(i + 1); var c = triangles.get(i + 2);
            if (com.badlogic.gdx.math.Intersector.isPointInTriangle(x, y, a.x, a.y, b.x, b.y, c.x, c.y)) { return true; }
        }
        return false;
    }

    private static Vector3 point(float[] vertices, int at) { return new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]); }
    private static double distance(double x, double y, Coords cell) {
        double dx = x - HexTile.centerX(cell, HexTile.MODEL_WIDTH), dy = y - HexTile.centerY(cell, HexTile.HEIGHT);
        return dx * dx + dy * dy;
    }
}
