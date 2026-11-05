/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Line2D;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadConcreteTest {
    private static BoardScene scene() {
        return BoardScenes.scene(new File(
              "data/boards/unofficial/SimonLandmine/32x34/32x34 DesertCity1 NE.board"));
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void desertCityRoadsStayPaintedAcrossTheirMovedBorders(TerrainLod lod) {
        var scene = scene();
        var at = new Coords(23, 2);
        var center = BoardGeometry.center(at, 0);
        var roads = List.of(road(scene, at.translated(0), lod), road(scene, at, lod),
              road(scene, at.translated(3), lod));
        float scale = BoardGeometry.hexScale();
        for (int direction : new int[] { 0, 3 }) {
            float borderY = (center.y + BoardGeometry.centerY(at.translated(direction))) / 2;
            for (float across = -6; across <= 6; across += 1.5f) {
                for (float along = -8; along <= 8; along += .5f) {
                    float x = center.x + across * scale, y = borderY + along * scale;
                    assertTrue(roads.stream().anyMatch(road -> road.covers(x, y)),
                          lod + " road gap at 2403 direction " + direction + ": " + x + ", " + y);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void desertCityRoadMeetsTheFullWidthOfTheDiagonalConcreteEdge(TerrainLod lod) {
        var scene = scene();
        var at = new Coords(24, 9);
        var center = BoardGeometry.center(at, 0);
        var road = road(scene, at, lod);
        var concrete = new BoardSurface(scene, scene.tile(at.translated(3)), lod);
        assertEquals(BoardScene.Surface.CONCRETE, concrete.tile.surface());
        float scale = BoardGeometry.hexScale();
        for (float across = -6; across <= 6; across += 1.5f) {
            for (float along = 24; along <= 60; along += .5f) {
                float x = center.x + across * scale, y = center.y - along * scale;
                boolean paved = Float.isFinite(BoardSurface.sampleHeight(concrete.groundFaces(), x, y, Float.NaN));
                assertTrue(paved || road.covers(x, y), lod + " road must reach concrete at 2511: " + x + ", " + y);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsReachFittedConcreteFromEveryDirection(int direction) {
        var at = BoardScenes.CENTER;
        var receiver = at.translated(direction);
        var paving = new HashSet<Coords>();
        for (int i = -2; i <= 2; i++) {
            paving.add(receiver.translated((direction + (i < 0 ? 4 : 1)) % 6, Math.abs(i)));
        }
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 1 << direction : 0, 0,
              paving.contains(c) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND));
        var concrete = new BoardSurface(scene, scene.tile(receiver));
        float scale = BoardGeometry.hexScale();
        assertTrue(BoardConcrete.of(scene).corners(receiver).stream()
              .anyMatch(shift -> Math.hypot(shift.x(), shift.y()) > scale), "Exercise a fitted concrete edge");
        assertMeetsConcrete(scene, at, direction, TerrainLod.FULL);
        assertTrue(BoardRoad.nearby(scene, concrete.tile).isEmpty(),
              "The fitted concrete edge must end the approaching asphalt, shoulder and centre line");
        assertFalse(BoardRoad.nearbyWays(scene, concrete.tile).isEmpty(),
              "Paint ownership must not remove the approaching carriageway from placement clearance");
    }

    private static void assertMeetsConcrete(BoardScene scene, Coords at, int direction, TerrainLod lod) {
        var receiver = at.translated(direction);
        var concrete = new ArrayList<BoardSurface>();
        for (int d = 0; d < 6; d++) {
            var next = scene.neighbor(scene.tile(at), d);
            if (next != null && next.surface() == BoardScene.Surface.CONCRETE
                  && next.elevation() == scene.tile(at).elevation()) {
                concrete.add(new BoardSurface(scene, next, lod));
            }
        }
        var road = road(scene, at, lod);
        var center = BoardGeometry.center(at, 0);
        var along = BoardGeometry.center(receiver, 0).sub(center).nor();
        var across = new Vector3(-along.y, along.x, 0);
        float scale = BoardGeometry.hexScale();
        for (float lateral = -BoardRoad.Kind.PAVED.halfWidth + 1.5f;
              lateral <= BoardRoad.Kind.PAVED.halfWidth - 1.5f; lateral += 1.5f) {
            boolean joined = false;
            for (float distance = 24; distance <= 65; distance += .5f) {
                var point = new Vector3(center).mulAdd(along, distance * scale).mulAdd(across, lateral * scale);
                if (concrete.stream().anyMatch(apron -> Float.isFinite(
                      BoardSurface.sampleHeight(apron.groundFaces(), point.x, point.y, Float.NaN)))) {
                    joined = true;
                    break;
                }
                assertTrue(road.covers(point.x, point.y),
                      at.getBoardNum() + " " + lod + " road gap before concrete, direction " + direction + ": " + point);
            }
            assertTrue(joined, "Every part of the carriageway must reach the concrete");
        }
    }

    @Test
    void militaryBaseConcreteApronsDoNotReceiveRoundedRoadCaps() throws Exception {
        var scene = GpuRoadSourceTest.scene("buildingsnobasement/militarybase2.board");
        for (var at : List.of(new Coords(5, 6), new Coords(7, 15), new Coords(7, 16),
              new Coords(8, 15), new Coords(8, 16), new Coords(14, 8), new Coords(15, 8), new Coords(15, 9))) {
            var apron = scene.tile(at);
            assertEquals(BoardScene.Surface.CONCRETE, apron.surface(), at.getBoardNum());
            assertEquals(BoardRoad.Kind.NONE, apron.road(), at.getBoardNum());
            assertFalse(BoardRoad.nearbyWays(scene, apron).isEmpty(), "Road approach at " + at.getBoardNum());
            var paint = BoardRoad.nearby(scene, apron);
            int entrances = 0;
            for (int direction = 0; direction < 6; direction++) {
                var source = scene.neighbor(apron, direction);
                if (source == null || !BoardRoad.rendered(source) || source.elevation() != apron.elevation()
                      || (source.roadExits() & 1 << ((direction + 3) % 6)) == 0) { continue; }
                var owner = BoardRoad.of(scene, source).owner();
                assertFalse(paint.stream().anyMatch(road -> road.owner().equals(owner)),
                      "Concrete " + at.getBoardNum() + " must end the incoming road cap from "
                            + source.coords().getBoardNum());
                entrances++;
            }
            assertTrue(entrances > 0, "Exercise an actual entrance at " + at.getBoardNum());
        }
    }

    @Test
    void militaryBaseRoadsMeetConcreteAcrossTheirFullCarriageways() throws Exception {
        var scene = GpuRoadSourceTest.scene("buildingsnobasement/militarybase2.board");
        for (var lod : TerrainLod.values()) {
            for (int direction : new int[] { 1, 2 }) { assertMeetsConcrete(scene, new Coords(6, 16), direction, lod); }
            for (int direction : new int[] { 4, 5 }) { assertMeetsConcrete(scene, new Coords(9, 15), direction, lod); }
        }
    }

    @Test
    void militaryBaseConcreteEntrancesHaveNoLinesAndRetainPaintFartherAlongTheRoad() throws Exception {
        var scene = GpuRoadSourceTest.scene("buildingsnobasement/militarybase2.board");
        for (var at : List.of(new Coords(6, 16), new Coords(9, 15))) {
            var tile = scene.tile(at);
            var road = BoardRoad.of(scene, tile);
            assertTrue(road.stopApproaches(tile.roadExits()).isEmpty(),
                  "The open apron entrance is not a stopped road junction at " + at.getBoardNum());
            var paint = GpuRoads.patches(tile, road).stream().filter(patch -> patch.texture().equals("concrete-ground"))
                  .map(patch -> GpuRoads.mask(road, patch)).toList();
            var edges = new ArrayList<Vector3[]>();
            var concrete = BoardConcrete.of(scene);
            for (int direction = 0; direction < 6; direction++) {
                var next = scene.neighbor(tile, direction);
                if ((tile.roadExits() & 1 << direction) == 0 || next == null
                      || next.surface() != BoardScene.Surface.CONCRETE || next.road() != BoardRoad.Kind.NONE) { continue; }
                int edge = Math.floorMod(1 - direction, 6);
                edges.add(new Vector3[] { concrete.corner(at, edge), concrete.corner(at, edge + 1) });
            }
            assertEquals(2, edges.size(), "Both arms meet the same open apron at " + at.getBoardNum());
            int entrance = 0, retained = 0;
            float scale = BoardGeometry.hexScale();
            for (float x = -60; x <= 60; x++) {
                for (float y = -60; y <= 60; y++) {
                    if (!road.footprint(-2).contains(x, y)) { continue; }
                    float wx = BoardGeometry.centerX(at) + x * scale, wy = BoardGeometry.centerY(at) + y * scale;
                    double distance = edges.stream().mapToDouble(edge -> Line2D.ptSegDist(
                          edge[0].x, edge[0].y, edge[1].x, edge[1].y, wx, wy)).min().orElseThrow() / scale;
                    final float px = x, py = y;
                    float alpha = (float) paint.stream().mapToDouble(mask -> BoardScenes.maskAlpha(mask, px, py))
                          .max().orElse(0);
                    if (distance < 12) {
                        assertEquals(0, alpha, .001f, "No stop bar or divider beside the fitted apron: "
                              + at.getBoardNum() + " " + x + "," + y);
                        entrance++;
                    } else if (distance > 20 && alpha > .9f) { retained++; }
                }
            }
            assertTrue(entrance > 50, "Exercise the full mouth rather than a single paint gap");
            assertTrue(retained > 5, "Markings remain beyond the apron entrance");
        }
    }

    @Test
    void anExplicitRoadOnConcreteRetainsItsOwnPaint() {
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) || c.equals(at.translated(0)) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(at) || c.equals(at.translated(0)) ? 9 : 0, 0,
              c.equals(at) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND));
        var roads = BoardRoad.nearby(scene, scene.tile(at));
        assertEquals(List.of(at, at.translated(0)), roads.stream().map(BoardRoad::coords).toList());
        assertFalse(GpuRoads.patches(scene.tile(at), roads.getFirst()).isEmpty());
        var approach = scene.tile(at.translated(0));
        var paint = GpuRoads.patches(approach, BoardRoad.of(scene, approach)).stream()
              .filter(patch -> patch.texture().equals("concrete-ground")).toList();
        boolean marked = false;
        for (int y = -20; y >= -34; y--) {
            for (var patch : paint) { marked |= patch.shape().contains(0, y); }
        }
        assertTrue(marked, "A road continuing across concrete retains markings near the shared border");
    }

    @Test
    void parkDistrictThroughRoadsAndRoundaboutsStayPaintedAcrossFittedConcrete() throws Exception {
        var scene = GpuRoadSourceTest.scene("Map Pack City/City Districts/16x17 Park District.board");
        record Crossing(Coords road, Coords concrete) { }
        for (var crossing : List.of(
              new Crossing(new Coords(13, 7), new Coords(14, 7)),
              new Crossing(new Coords(14, 6), new Coords(14, 5)),
              new Crossing(new Coords(14, 6), new Coords(14, 7)),
              new Crossing(new Coords(15, 7), new Coords(15, 6)))) {
            var source = scene.tile(crossing.road());
            var receiver = scene.tile(crossing.concrete());
            var course = BoardRoad.of(scene, source);
            assertEquals(BoardScene.Surface.CONCRETE, receiver.surface());
            assertTrue(BoardRoad.nearby(scene, receiver).stream().anyMatch(road -> road.owner().equals(course.owner())),
                  "Concrete must carry the adjacent through-route " + crossing);
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surface = new BoardSurface(scene, receiver, lod);
                var painted = road(scene, crossing.road(), lod);
                var bounds = course.footprint(0).getBounds2D();
                int checked = 0;
                float scale = BoardGeometry.hexScale();
                // Fitted concrete can move well outside its nominal hex; sample the actual road footprint.
                for (int x = (int) Math.floor(bounds.getMinX()); x <= Math.ceil(bounds.getMaxX()); x++) {
                    for (int y = (int) Math.floor(bounds.getMinY()); y <= Math.ceil(bounds.getMaxY()); y++) {
                        if (BoardScenes.maskAlpha(painted.mask(), x, y) < .99f) { continue; }
                        float px = BoardGeometry.centerX(crossing.road()) + x * scale;
                        float py = BoardGeometry.centerY(crossing.road()) + y * scale;
                        if (!Float.isFinite(BoardSurface.sampleHeight(surface.groundFaces(), px, py, Float.NaN))) { continue; }
                        assertTrue(painted.covers(px, py), "Concrete must not hide asphalt at "
                              + crossing + " " + lod + ": " + px + "," + py);
                        checked++;
                    }
                }
                assertTrue(checked > 10, "Exercise actual asphalt crossing the fitted concrete edge: " + crossing);
            }
        }
    }

    private static Road road(BoardScene scene, Coords at, TerrainLod lod) {
        var tile = scene.tile(at);
        var road = BoardRoad.of(scene, tile);
        var asphalt = GpuRoads.patches(tile, road).stream()
              .filter(patch -> patch.texture().equals("roads/asphalt") && patch.lift() == GpuRoads.SURFACE_LIFT)
              .findFirst().orElseThrow();
        var triangles = new ArrayList<BoardTacticalGeometry.Triangle>();
        for (int direction = -1; direction < 6; direction++) {
            var receiver = direction < 0 ? tile : scene.neighbor(tile, direction);
            if (BoardRoad.nearby(scene, receiver).stream().anyMatch(source -> source.owner().equals(road.owner()))) {
                // The renderer supports widened/fitted approaches on adjoining dry terrain too.
                triangles.addAll(GpuRoads.drape(tile, new BoardSurface(scene, receiver, lod), asphalt));
            }
        }
        return new Road(at, GpuRoads.mask(road, asphalt), triangles);
    }

    private record Road(Coords at, GpuRoads.MaskData mask, List<BoardTacticalGeometry.Triangle> triangles) {
        boolean covers(float x, float y) {
            float scale = BoardGeometry.hexScale();
            if (BoardScenes.maskAlpha(mask, (x - BoardGeometry.centerX(at)) / scale,
                  (y - BoardGeometry.centerY(at)) / scale) < .99f) { return false; }
            // Use terrain's border tolerance: a float ray can reject both triangles of an exact shared edge.
            return triangles.stream().anyMatch(t -> Float.isFinite(new BoardSurface.Face(t.a(), t.b(), t.c(), BoardSurface.Finish.TOP)
                  .height(x, y)));
        }
    }
}
