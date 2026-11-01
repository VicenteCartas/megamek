/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardBridgeBankCurveTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void grasslandTerminalsKeepTheirAuthoredFootprintAndTheRampHasOneLevelRoadSurface() throws Exception {
        var at = new Coords(8, 7);
        for (boolean low : new boolean[] { true, false }) {
            var scene = GpuRoadSourceTest.scene("AGoAC Maps/16x17 Grassland 2"
                  + (low ? " (Low Bridges)" : "") + ".board");
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surfaces = new HashMap<Coords, BoardSurface>();
                var tile = scene.tile(at);
                var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                var authored = BoardShape.loadKit("bridges/bridge-terminal").get(
                      "bridge-terminal-lod" + (lod == TerrainLod.FULL ? 0 : 1)).polygons();
                var facets = footing.shape().facets();
                // Both end blocks retain their horizontal edge lengths after turning with the bank.
                // Seating the undersides on terrain may change Z, but cannot stretch or kink the blocks.
                for (int side = 0; side < 2; side++) {
                    int start = facets.size() - authored.size() * (2 - side);
                    for (int i = 0; i < authored.size(); i++) {
                        var expected = Arrays.stream(authored.get(i).points())
                              .map(p -> new Vector3(p.x * BoardBridge.SHOULDER / 1.5f, p.y, 0)
                                    .scl(BoardGeometry.hexScale())).toList();
                        var face = facets.get(start + i);
                        var actual = List.of(face.a(), face.b(), face.c()).stream()
                              .map(p -> new Vector3(p.x, p.y, 0)).toList();
                        float[] lengths = lengths(expected), placed = lengths(actual);
                        for (int edge = 0; edge < 3; edge++) {
                            assertEquals(lengths[edge], placed[edge], .002f,
                                  "Curving a landing must not deform its terminal: " + low + "/" + lod);
                        }
                    }
                }
                if (!low) {
                    float ground = BoardGeometry.groundZ(scene.tile(new Coords(9, 6)));
                    assertFalse(facets.stream().anyMatch(f -> f.part() == BoardBridge.Part.TOP
                          && List.of(f.a(), f.b(), f.c()).stream().allMatch(p ->
                                Math.abs(p.z - ground - GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale()) < .001f)),
                          "The level bank road must not have a second asphalt slab beneath the terminals");
                }
                var bank = surfaces.get(new Coords(9, 6));
                assertTrue(bank.containmentWalls.isEmpty(),
                      "The grass bank must not add concrete panels beside the authored bridge terminals");
                BoardRoadSlopeTest.assertClosed(scene, new Coords(9, 6), lod);
            }
        }
    }

    @Test
    void bothEndsOfTheLowGrasslandBridgeKeepHillsidesOutOfTheCarriageway() throws Exception {
        var scene = GpuRoadSourceTest.scene("AGoAC Maps/16x17 Grassland 2 (Low Bridges).board");
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            assertClear(scene, List.of(new Coords(8, 7), new Coords(5, 8)), lod, 2);
        }
    }

    @Test
    void widenedCurvedBridgeBanksRecedeBeyondTheWholeRoadAndRailSlab() {
        for (var panel : List.of(RoadGradeFixture.Panel.CENTERED, RoadGradeFixture.Panel.BESIDE)) {
            for (int parity = 0; parity < 2; parity++) {
                var scene = RoadGradeFixture.scene(RoadGradeFixture.focused(false, panel, "bridge-curved", 0, parity));
                var bank = RoadGradeFixture.focusedCenter(parity);
                for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                    var surface = new BoardSurface(scene, scene.tile(bank), lod);
                    float gate = BoardSurface.roadEdgeElevation(surface.tile, scene.tile(bank.translated(2)), 2)
                          * BoardGeometry.level();
                    for (int corner : new int[] { 5, 0 }) {
                        var point = BoardGeometry.corner(bank, surface.tile.elevation(), corner);
                        assertEquals(gate, BoardSurface.sampleHeight(surface.foundation(), point.x, point.y, Float.NaN),
                              .002f * BoardGeometry.hexScale(),
                              "The widened pavement covers both mouth corners and must meet the bridge there");
                    }
                    assertClear(scene, List.of(bank.translated(2), bank.translated(2, 2)), lod, 1);
                    var sources = new HashMap<Coords, BoardSurface>();
                    for (var tile : scene.tiles()) {
                        if (tile.coords().distance(bank) <= 3) {
                            sources.put(tile.coords(), new BoardSurface(scene, tile, lod));
                        }
                    }
                    // The clearance moves a shared boundary, so every adjoining top and wall must agree.
                    for (var entry : sources.entrySet()) {
                        for (int edge = 0; edge < 6; edge++) {
                            var other = sources.get(entry.getKey().translated(BoardGeometry.edgeDirection(edge)));
                            if (other == null) { continue; }
                            for (float t : new float[] { 0, .25f, .5f, .75f, 1 }) {
                                for (float z : new float[] { 2 * BoardGeometry.level(), 3 * BoardGeometry.level() }) {
                                    var point = entry.getValue().relief.outlinePoint(edge, t, z);
                                    assertTrue(point.epsilonEquals(other.relief.outlinePoint((edge + 3) % 6, 1 - t, z), .003f),
                                          "The recessed bridge bank must retain its shared rim: " + panel + "/" + lod);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void theInsidePlateauLeavesRoomForTheDescendingCurvedBridgeBank() {
        for (var panel : List.of(RoadGradeFixture.Panel.CENTERED, RoadGradeFixture.Panel.BESIDE)) {
            for (int parity = 0; parity < 2; parity++) {
                var scene = RoadGradeFixture.scene(RoadGradeFixture.focused(false, panel, "bridge-curved", 0, parity));
                var at = RoadGradeFixture.focusedCenter(parity);
                var tile = scene.tile(at);
                var bridge = scene.tile(at.translated(2));
                var center = BoardGeometry.center(at, tile.elevation());
                var along = BoardGeometry.center(bridge.coords(), tile.elevation()).sub(center);
                var inside = BoardGeometry.center(at.translated(1), tile.elevation()).sub(center);
                float lengthSquared = along.len2(), scale = BoardGeometry.hexScale();
                var road = BoardRoad.way(scene, tile);
                var grade = BoardRoadGrade.of(scene, tile, true);
                // From where the ramp leaves the bank's level to the gate, on the plateau's side of the curve.
                Predicate<Vector3> band = p -> {
                    float progress = 2 * ((p.x - center.x) * along.x + (p.y - center.y) * along.y) / lengthSquared;
                    float side = along.x * (p.y - center.y) - along.y * (p.x - center.x);
                    return progress >= .5f && progress <= 1 && side * (along.x * inside.y - along.y * inside.x) > 0;
                };
                for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var shape = BoardBridgeFooting.build(scene, bridge, lod, surfaces)
                          .withDeck(scene, bridge, BoardBridge.deck(scene, bridge), lod, surfaces);
                    var terrain = new ArrayList<BoardSurface.Face>();
                    for (var near : List.of(at, at.translated(1))) {
                        var surface = surfaces.computeIfAbsent(near, c -> new BoardSurface(scene, scene.tile(c), lod));
                        terrain.addAll(surface.foundation());
                        terrain.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
                    }
                    int covered = 0;
                    for (float x = center.x - BoardGeometry.width(); x < center.x + BoardGeometry.width(); x += .5f * scale) {
                        for (float y = center.y - BoardGeometry.height(); y < center.y + BoardGeometry.height(); y += .5f * scale) {
                            if (!band.test(new Vector3(x, y, 0))) { continue; }
                            float distance = road.distance((x - center.x) / scale, (y - center.y) / scale);
                            float ground = BoardSurface.sampleHeight(terrain, x, y, Float.NaN), surface = grade.height(x, y);
                            if (distance < -.5f) {
                                assertFalse(ground > surface + .01f * scale,
                                      "No ground stands in the descending curve's carriageway: " + panel + "/" + parity + "/" + lod);
                            } else if (distance > .25f && distance < BoardRoad.SHOULDER + .5f && ground > surface + .5f * scale) {
                                // The plateau stands back in a vertical cut past the road's shoulder, which a retaining wing
                                // faces to its top.
                                assertTrue(ground <= structure(shape, x, y) + .01f * scale,
                                      "The inside plateau's cut beside the descending curve is covered by the retaining wall: "
                                            + panel + "/" + parity + "/" + lod + " at " + x + "," + y);
                                covered++;
                            }
                        }
                    }
                    assertTrue(covered >= 10, "Check the cut along the curve, not only one corner");
                    BoardRoadSlopeTest.assertClosed(scene, at.translated(1), lod,
                          p -> band.test(p) && p.z > grade.height(p.x, p.y) + .5f * scale);
                    var plateau = surfaces.get(at.translated(1));
                    var top = BoardGeometry.center(plateau.tile.coords(), plateau.tile.elevation());
                    assertEquals(top.z, plateau.height(top.x, top.y), .001f * scale,
                          "The cut must return to the surrounding plateau");
                }
            }
        }
    }

    /** The highest of a bridge's facets over a point, its retaining wings included; negative infinity off it. */
    private static float structure(BoardBridge.Shape shape, float x, float y) {
        float height = Float.NEGATIVE_INFINITY;
        for (var facet : shape.facets()) {
            height = Math.max(height, new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)
                  .height(x, y));
        }
        return height;
    }

    private static void assertClear(BoardScene scene, List<Coords> decks, TerrainLod lod, float step) {
        var surfaces = new HashMap<Coords, BoardSurface>();
        for (var at : decks) {
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
            var shape = footing.withDeck(scene, tile, deck, lod, surfaces);
            var road = footing.road(deck, at);
            var terrain = new ArrayList<BoardSurface.Face>();
            for (int direction = -1; direction < 6; direction++) {
                var near = direction < 0 ? at : at.translated(direction);
                var surface = surfaces.computeIfAbsent(near, c -> new BoardSurface(scene, scene.tile(c), lod));
                terrain.addAll(surface.foundation());
                terrain.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
            }
            int samples = 0;
            float scale = BoardGeometry.hexScale();
            for (float x = shape.bounds().min.x; x < shape.bounds().max.x; x += step * scale) {
                for (float y = shape.bounds().min.y; y < shape.bounds().max.y; y += step * scale) {
                    if (road.distance((x - BoardGeometry.centerX(at)) / scale,
                          (y - BoardGeometry.centerY(at)) / scale) > -.5f) { continue; }
                    float top = BoardBridge.top(shape, x, y);
                    if (!Float.isFinite(top)) { continue; }
                    float hill = BoardSurface.sampleHeight(terrain, x, y, Float.NEGATIVE_INFINITY);
                    assertTrue(hill <= top + .01f * scale,
                          "Hillside intersects bridge " + at.getBoardNum() + "/" + lod + " at " + x + "," + y);
                    samples++;
                }
            }
            assertTrue(samples > 100, "Check the entire carriageway and its bank landing");
        }
    }

    private static float[] lengths(List<Vector3> points) {
        float[] result = { points.get(0).dst(points.get(1)), points.get(1).dst(points.get(2)),
                          points.get(2).dst(points.get(0)) };
        Arrays.sort(result);
        return result;
    }
}
