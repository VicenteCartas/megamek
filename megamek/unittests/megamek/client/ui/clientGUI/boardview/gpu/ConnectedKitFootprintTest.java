/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Raised connected storeys retain their ground footprint and close only above shorter neighbours. */
class ConnectedKitFootprintTest {
    private static final List<String> ASSETS = List.of("buildings/building_light/building_light",
          "buildings/lunar_habitat/lunar_habitat", "buildings/lunar_habitat/lunar_habitat@parapet", "buildings/civic/civic",
          "buildings/utopia/utopia", "buildings/frontier/frontier");

    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @ParameterizedTest
    @ValueSource(ints = { 4, 9 })
    void raisedStoreysKeepTheirConnectedFootprint(int height) {
        for (String asset : ASSETS) {
            for (var footprint : HexAppearance.Footprint.values()) {
                var kit = GpuBuilding.kit(TilesetScenery.footprint(asset, footprint));
                for (int lod = 0; lod < kit.lods().size(); lod++) {
                    for (int direction = 0; direction < 6; direction++) {
                        int[] reach = new int[6];
                        reach[direction] = 1;
                        var triangles = triangles(kit, lod, height, reach);
                        String context = asset + " " + footprint + " LOD " + lod + " direction " + direction;
                        var turn = ConnectedKit.placement(0, direction);
                        var hit = new Vector3();
                        var down = new Ray(new Vector3(0, 34, height * 18 + 30).mul(turn), new Vector3(0, 0, -1));
                        assertTrue(Intersector.intersectRayTriangles(down, triangles, hit), context);
                        // A bevelled roof edge (the Frontier modules') may stand up to a chamfer below the roof; an open
                        // roof would show the storey below, a level lower.
                        assertTrue(hit.z >= height * 18 - 3, "The top roof must cover the connected floor: " + context + " " + hit);
                        // A wall across the former connection must enclose the upper storey at its original hexside.
                        var upper = new Ray(new Vector3(.37f, 50, (height - .47f) * 18).mul(turn),
                              new Vector3(0, -1, 0).rot(turn));
                        assertTrue(Intersector.intersectRayTriangles(upper, triangles, hit), context);
                        assertTrue(hit.dst(upper.origin) < 18, "The raised wall must meet the floor's end: " + context + " " + hit);
                        var ground = new Ray(new Vector3(.37f, 50, 9.54f).mul(turn), new Vector3(0, -1, 0).rot(turn));
                        assertTrue(!Intersector.intersectRayTriangles(ground, triangles, hit) || hit.dst(ground.origin) > 24,
                              "The occupied connection must stay open on the ground floor: " + context + " " + hit);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 3, 9 })
    void unequalNeighboursCloseAtTheirOwnRoofHeights(int mask) {
        var kit = GpuBuilding.kit(ASSETS.getFirst());
        int[] reach = new int[6];
        reach[0] = 1;
        int other = mask == 3 ? 1 : 3;
        reach[other] = 4;
        var triangles = triangles(kit, 0, 9, reach);
        for (int direction : new int[] { 0, other }) {
            var turn = ConnectedKit.placement(0, direction);
            for (int level : new int[] { reach[direction] - 1, reach[direction] }) {
                var ray = new Ray(new Vector3(.37f, 50, (level + .53f) * 18).mul(turn), new Vector3(0, -1, 0).rot(turn));
                var hit = new Vector3();
                boolean closed = Intersector.intersectRayTriangles(ray, triangles, hit) && hit.dst(ray.origin) < 18;
                assertEquals(level >= reach[direction], closed, "Connection " + direction + " at level " + level + " " + hit);
            }
        }
    }

    private static List<Vector3> triangles(ConnectedKit.Kit kit, int lod, int height, int[] reach) {
        return triangles(kit, lod, height, reach, false);
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void wallsMeetingInANotchTurnItsCornerAndTheRoofsWhereTheyStandLevel(int southEastTop) {
        // Linked south only, its south and south-east neighbours (2 levels; the south-east one southEastTop) linked to
        // each other: its wall square to the south side ends in a 60-degree notch with the south-east one's.
        var kit = GpuBuilding.kit(ASSETS.getFirst());
        int[] reach = { 0, 0, 0, 2, 0, 0 }, corners = { 0, 0, southEastTop, 2, 0, 0, 0, 0, 2, 0, 0, 0 };
        var placed = ConnectedKit.placements(kit, 0, GpuBuilding.select(kit.roles(), 2, 0), reach, 0, false,
              ConnectedKit.junctions(2, reach, corners, true));
        // The band, authored linked north and turned to the south, meets the notch at its own corner 5.
        List<Integer> notched = placed.stream().filter(piece -> piece.node().endsWith("-notch-32")).map(ConnectedKit.Placement::level).toList();
        assertEquals(southEastTop == 2 ? List.of(0, 1, 2) : List.of(0, 1), notched.stream().sorted().toList());
        assertEquals(notched.size(), placed.stream().filter(piece -> piece.node().contains("-notch-")).count());
    }

    @Test
    void whereModulesPartAtJointsTheCornerHexClosesTheNotch() {
        // Linked south and south-west, both neighbours as tall and not linked to each other: their walls end in their
        // joints' grooves at its corner 3, which it closes with its capped twin, storeys and roof.
        var kit = GpuBuilding.kit("buildings/frontier/frontier");
        int[] reach = { 0, 0, 0, 2, 2, 0 }, corners = { 0, 0, 0, 2, 2, 0, 0, 0, 0, 0, 0, 0 };
        var placed = ConnectedKit.placements(kit, 0, GpuBuilding.select(kit.roles(), 2, 0), reach, 0, false,
              ConnectedKit.junctions(2, reach, corners, true));
        assertEquals(List.of(0, 1, 2), placed.stream().filter(piece -> piece.node().endsWith("-notch-8"))
              .map(ConnectedKit.Placement::level).sorted().toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void exposedHabitatRoofEdgesCurveUpToTheDeck(boolean square) {
        var kit = GpuBuilding.kit(ASSETS.get(1) + (square ? "-square" : ""));
        for (int lod = 0; lod < kit.lods().size(); lod++) {
            for (int[] pattern : List.of(new int[] { 1, 0, 0, 0, 0, 0 }, new int[] { 1, 1, 0, 0, 0, 0 },
                  new int[] { 1, 4, 0, 0, 0, 0 }, new int[] { 1, 0, 0, 1, 0, 0 }, new int[] { 1, 1, 1, 1, 1, 1 })) {
                for (int rotation = 0; rotation < 6; rotation++) {
                    int[] reach = new int[6];
                    for (int direction = 0; direction < 6; direction++) { reach[(direction + rotation) % 6] = pattern[direction]; }
                    var shape = triangles(kit, lod, 4, reach);
                    for (int direction = 0; direction < 6; direction++) {
                        if (reach[direction] == 0) { continue; }
                        var turn = ConnectedKit.placement(0, direction);
                        var hit = new Vector3();
                        // Stay between the structural piers while checking the roof surface itself.
                        var edge = new Ray(new Vector3(7, 35, 100).mul(turn), new Vector3(0, 0, -1));
                        String context = "Square " + square + ", LOD " + lod + ", direction " + direction
                              + ", reach " + java.util.Arrays.toString(reach);
                        assertTrue(Intersector.intersectRayTriangles(edge, shape, hit), context);
                        if (reach[direction] < 4) {
                            assertTrue(hit.z > 72.5f && hit.z < 77, "The exposed edge must be the curved rim, not a flat deck: " + context + " " + hit);
                        } else {
                            assertEquals(78, hit.z, .1f, "An equal-height connection keeps its uninterrupted roof: " + context);
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 4, 9 })
    void optionalSetbackKeepsTheConnectionBelowAndShrinksAbove(int height) {
        for (String asset : ASSETS) {
            for (var footprint : HexAppearance.Footprint.values()) {
                var kit = GpuBuilding.kit(TilesetScenery.footprint(asset, footprint));
                for (int lod = 0; lod < kit.lods().size(); lod++) {
                    for (int direction = 0; direction < 6; direction++) {
                        int[] reach = new int[6]; reach[direction] = 1;
                        var shape = triangles(kit, lod, height, reach, true);
                        var turn = ConnectedKit.placement(0, direction);
                        var hit = new Vector3();
                        var down = new Ray(new Vector3(0, 34, height * 18 + 30).mul(turn), new Vector3(0, 0, -1));
                        assertTrue(Intersector.intersectRayTriangles(down, shape, hit));
                        // Square sections already reach their north/south ends even without those connections.
                        if (footprint != HexAppearance.Footprint.SQUARE || direction % 3 != 0) {
                            assertTrue(hit.z < 36, "Only the lower connection should reach this far: " + asset + " " + footprint + " " + hit);
                        }
                        var center = new Ray(new Vector3(0, 0, height * 18 + 30), new Vector3(0, 0, -1));
                        assertTrue(Intersector.intersectRayTriangles(center, shape, hit));
                        assertTrue(hit.z >= height * 18, "The smaller upper tower must keep its full height");
                    }
                }
            }
        }
    }

    private static List<Vector3> triangles(ConnectedKit.Kit kit, int lod, int height, int[] reach, boolean stepped) {
        List<Vector3> result = new ArrayList<>();
        for (var piece : ConnectedKit.placements(kit, lod, height, reach, 0, 0, stepped)) {
            var vertices = ConnectedKit.triangles(kit, lod, piece);
            for (int i = 0; i < vertices.size(); i += 3) {
                Vector3 a = vertices.get(i), b = vertices.get(i + 1), c = vertices.get(i + 2);
                // Clipping can leave zero-area faces; libGDX treats some of these as a hit at the ray's origin.
                if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-6f) {
                    result.add(a); result.add(b); result.add(c);
                }
            }
        }
        return result;
    }
}
