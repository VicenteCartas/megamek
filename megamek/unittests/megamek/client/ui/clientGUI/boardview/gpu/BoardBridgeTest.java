/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Check the generated triangles against playable road footprints, including every turn and junction. */
class BoardBridgeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static IntStream masks() { return IntStream.range(0, 64); }

    @ParameterizedTest(name = "Bridge exits {0}: connected deck and outside rails")
    @MethodSource("masks")
    void completeDeckHasNoHolesOrRailsAcrossItsCarriageway(int exits) {
        Coords at = new Coords(0, 0);
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        var features = BoardFeatures.capture(hex, at, Map.of()).stream()
              .filter(f -> f.asset().equals("bridge")).toList();
        assertEquals(1, features.size());
        assertEquals(exits, features.getFirst().bridgeExits());

        List<float[]> top = new ArrayList<>();
        List<float[]> geometry = new ArrayList<>();
        for (var face : facets(exits)) {
            float[] triangle = { face.a().x, face.a().y, face.a().z, face.b().x, face.b().y, face.b().z,
                  face.c().x, face.c().y, face.c().z };
            if (triangle[2] == triangle[5] && triangle[5] == triangle[8]) { top.add(triangle); }
            geometry.add(triangle);
        }
        var road = BoardRoad.clearance(at, exits);
        var carriageway = road.footprint(-.4f);
        var rails = road.footprint(BoardBridge.SHOULDER - .3f);
        rails.subtract(road.footprint(.3f));
        var center = BoardGeometry.center(at, 0);
        int deckSamples = 0, railSamples = 0;
        for (float y = -35.69f; y < 36; y += 1) {
            for (float x = -41.77f; x < 42; x += 1) {
                if (!BoardGeometry.contains(at, x + center.x, y + center.y)) { continue; }
                if (carriageway.contains(x, y)) {
                    assertEquals(0, height(top, x, y), .001f, "Hole or rail in deck at " + x + "," + y);
                    deckSamples++;
                } else if (rails.contains(x, y)) {
                    assertEquals(2.5f, height(top, x, y), .001f, "Missing outside rail at " + x + "," + y);
                    railSamples++;
                }
            }
        }
        assertTrue(deckSamples > 100);
        assertTrue(railSamples > 10);
        if (BoardRoad.roundabout(exits)) {
            assertEquals(2.5f, height(top, 0, 0), .001f, "The bridge roundabout has a solid raised island");
            for (int angle = 0; angle < 360; angle += 5) {
                float nx = (float) Math.cos(Math.toRadians(angle)), ny = (float) Math.sin(Math.toRadians(angle));
                for (float along : new float[] { -9, 9 }) {
                    for (float across : new float[] { -5, 5 }) {
                        assertEquals(0, height(top, (18 + across) * nx - along * ny, (18 + across) * ny + along * nx),
                              .001f, "Vehicle body clearance must include the bridge's physical island and rails");
                    }
                }
            }
        }
        // Each actual exit is open all the way to the boundary; absent exits must not acquire a phantom deck.
        for (int d = 0; d < 6; d++) {
            var gate = BoardGeometry.center(at.translated(d), 0).sub(center).scl(.499f);
            float z = height(top, gate.x, gate.y);
            if ((exits & (1 << d)) != 0) {
                assertEquals(0, z, .001f, "Exit " + d);
                // A vertical ray just inside the deck misses a zero-thickness rail
                // extruded across its end. A vehicle must also pass through the edge.
                Vector3 along = new Vector3(gate).nor(), across = new Vector3(along.y, -along.x, 0);
                for (float offset : new float[] { -6, 0, 6 }) {
                    Ray crossing = new Ray(new Vector3(gate).mulAdd(along, -.25f).mulAdd(across, offset).add(0, 0, 1), along);
                    Vector3 hit = new Vector3();
                    for (float[] triangle : geometry) {
                        boolean blocked = Intersector.intersectRayTriangle(crossing,
                              new Vector3(triangle[0], triangle[1], triangle[2]),
                              new Vector3(triangle[3], triangle[4], triangle[5]),
                              new Vector3(triangle[6], triangle[7], triangle[8]), hit) && hit.dst(crossing.origin) < .6f;
                        assertTrue(!blocked, "Barrier across bridge exit " + d + " at " + offset);
                    }
                }
            }
            else { assertEquals(Float.NEGATIVE_INFINITY, z, "Unconnected exit " + d); }
        }
    }

    /** Local generated geometry of a level, plain bridge with these exits and no rail marker. */
    static List<BoardBridge.Facet> facets(int exits) {
        var tile = BridgeScenes.bridge(new Coords(0, 0), 0, 2, exits);
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
        return BoardBridgeMesh.build(tile, BoardBridge.deck(scene, tile));
    }

    private static float height(List<float[]> triangles, float x, float y) {
        float highest = Float.NEGATIVE_INFINITY;
        for (float[] t : triangles) {
            float denominator = (t[4] - t[7]) * (t[0] - t[6]) + (t[6] - t[3]) * (t[1] - t[7]);
            if (Math.abs(denominator) < 1e-6f) { continue; }
            float a = ((t[4] - t[7]) * (x - t[6]) + (t[6] - t[3]) * (y - t[7])) / denominator;
            float b = ((t[7] - t[1]) * (x - t[6]) + (t[0] - t[6]) * (y - t[7])) / denominator;
            if (a >= -1e-5f && b >= -1e-5f && a + b <= 1.00001f) { highest = Math.max(highest, t[2]); }
        }
        return highest;
    }

    @Test
    void aBridgeSpansAChasmOnTheHexLevel() {
        Hex hex = new Hex(2);
        hex.addTerrain(new Terrain(Terrains.ULTRA_SUBLEVEL, 1));
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        var bridges = BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .filter(feature -> feature.asset().equals("bridge")).toList();
        assertEquals(1, bridges.size(), "The deck spans the chasm");
        assertEquals(1, bridges.getFirst().elevation(), .0001, "One level above the hex's level, not the pit's");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "desert", "snow", "volcano", "dirt", "lunar" })
    void modeledBridgesKeepTheUnderlyingTerrainMaterial(String theme) {
        Hex hex = new Hex(-2);
        hex.setTheme(theme);
        var surface = BoardFeatures.surface(hex);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_REPAIRED, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Bridge geometry must not force the legacy ground artwork");
        assertEquals(surface, BoardFeatures.surface(hex));
        assertFalse(BoardLiquid.capture(hex).present(), "A suspended bridge does not imply liquid beneath it");
        hex.addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardLiquid.capture(hex).present(), "Authored water beneath a bridge must remain water");
        hex.removeTerrain(Terrains.WATER);
        hex.addTerrain(new Terrain(Terrains.RUBBLE, 1));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Unmodeled ground markings still keep their artwork");
    }

    private static final Coords AT = new Coords(4, 4);

    @Test
    void anIndependentDeckRailPreventsAnIslandInTheRoadAndItsSupportingSlab() {
        for (boolean rail : new boolean[] { false, true }) {
            Hex hex = new Hex(0, "bridge:1:63;bridge_cf:40;bridge_elev:2;bridge_road:1:31"
                  + (rail ? ";bridge_rail:1:32" : ""), "");
            var tile = BridgeScenes.withDeckRoad(BridgeScenes.bridge(AT, 0, 2, 63),
                  BoardRoad.Kind.PAVED, 31).withFeatures(BoardFeatures.capture(hex, AT, Map.of()));
            var scene = BoardScenes.scene(at -> at.equals(AT) ? tile
                  : BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(0, deck.corridor().sides(), "The independent rail has no shared road approach");
            assertEquals(rail, deck.road(AT).footprint(0).contains(0, 0),
                  "A rail-bearing road hub must not grow a roundabout island");
            assertEquals(rail, deck.slab(AT).footprint(0).contains(0, 0),
                  "The structural bridge deck must carry the independent track across its hub");
            assertEquals(rail ? 5 : 0, deck.road(AT).stopApproaches(31).size());
        }
    }

    @Test
    void aPlainBridgeHasThirtyPixelCarriagewayAndThreePixelShoulders() {
        for (boolean truss : new boolean[] { false, true }) {
            var shape = shape(-1, RailRoute.Corridor.NONE, truss);
            assertEquals(36, shape.bounds().getWidth(), .001f, "The former 18px deck is twice as wide");
            for (float x : new float[] { -14.9f, 0, 14.9f }) { assertEquals(0, height(shape, x, 0), .001f); }
            for (float x : new float[] { -16.5f, 16.5f }) { assertTrue(height(shape, x, 0) > 1); }
        }
    }

    @Test
    void movingTheTrackBesideTheRoadKeepsOneWideDeckWithOnlyOutsideBarriers() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int beside : new int[] { 0, 9 }) {
                for (int right : new int[] { 0, 9 }) {
                    var shape = shape(9, new RailRoute.Corridor(9, beside, right), truss);
                    for (float y : new float[] { -30, -15, 0, 15, 30 }) {
                        for (float x : new float[] { -20.5f, -RailRoute.BESIDE_RAIL, -2, 0, 2, RailRoute.BESIDE_RAIL, 20.5f }) {
                            assertEquals(0, height(shape, x, y), .001f,
                                  "The road, rail and space between them belong to one unobstructed deck");
                        }
                    }
                    float barrier = BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2;
                    for (float x : new float[] { -barrier, barrier }) {
                        assertTrue(height(shape, x, 0) > 1, "The outside bridge barriers are retained");
                    }
                }
            }
        }
    }

    @Test
    void northSouthSeamsSupportTheFullBallastAndCarsWhileShouldersOverhangUnusedHexes() {
        for (boolean truss : new boolean[] { false, true }) {
            for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 0),
                  new RailRoute.Corridor(9, 9, 9))) {
                var shape = shape(9, corridor, truss);
                float rail = RailRoute.BESIDE_RAIL * corridor.lateral(0);
                float road = RailRoute.BESIDE_ROAD * corridor.lateral(0);
                float lane = corridor.beside() == 0 ? (BoardRoad.MIDDLE_HALF + BoardRoad.MEDIAN_HALF) / 2
                      : BoardRoad.Kind.PAVED.halfWidth / 2;
                for (int end : new int[] { -1, 1 }) {
                    for (float x : new float[] { rail - 5.6f, rail - 3.8f, rail, rail + 3.8f, rail + 5.6f,
                          road - lane - 3, road - lane + 3, road + lane - 3, road + lane + 3 }) {
                        assertEquals(0, height(shape, x, end * 35.9f), .001f,
                              "Ballast, sleepers and scale-1 cars retain a flat carrier past the 42px edge corners");
                        var crossing = new Ray(new Vector3(x, end * 35.75f, 1), new Vector3(0, end, 0));
                        assertTrue(shape.hit(crossing) > .6f * .6f, "No seam barrier across the occupied width");
                    }
                    float barrier = BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2;
                    assertTrue(height(shape, barrier, end * 35.9f) > 1,
                          "The outer shoulder continues into the unused intermediate hex instead of narrowing at the corner");
                }
            }
        }
    }

    @Test
    void trackTerminationDoesNotBuildAWallBetweenTheTrackAndContinuingRoad() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int right : new int[] { 0, 8 }) {
                var shape = shape(8, new RailRoute.Corridor(8, 8, right), truss);
                for (float y : new float[] { -30, -15, 0, 4 }) {
                    for (float x : new float[] { -18.5f, -RailRoute.BESIDE_RAIL, -2, 0, 2, RailRoute.BESIDE_RAIL, 18.5f }) {
                        assertEquals(0, height(shape, x, y), .001f,
                              "The rail stop must not grow an internal parapet or rounded truss enclosure");
                    }
                }
                for (float y : new float[] { 12, 24, 34 }) {
                    assertEquals(0, height(shape, 0, y), .001f, "The road continues past the track end");
                }
            }
        }
    }

    @Test
    void taperedTrussKeepsOpenPanelsWithoutCuttingAcrossItsCarriageway() {
        var corridor = new RailRoute.Corridor(8, 8, 8);
        var shape = shape(8, corridor, true);
        var carriageway = BoardRoad.layout(AT, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
              .bridgeCorridor(corridor).footprint(-.05f);
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.STEEL) { continue; }
            for (var point : List.of(face.a(), face.b(), face.c())) {
                if (point.z > 2) {
                    assertFalse(carriageway.contains(point.x, point.y), "Steel intrudes into the road at " + point);
                }
            }
        }
        int covered = 0, longest = 0;
        for (float y = .125f; y < 35; y += .25f) {
            float hit = shape.hit(new Ray(new Vector3(-30, y, 6), new Vector3(1, 0, 0)));
            // Only the nearer truss: a hit beyond the center belongs to the far side.
            covered = hit < 30 * 30 ? covered + 1 : 0;
            longest = Math.max(longest, covered);
        }
        assertTrue(longest * .25f < 4, "The taper must keep open Warren panels rather than a dense steel wall");
    }

    private static BoardBridge.Shape shape(int railExits, RailRoute.Corridor corridor, boolean truss) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        if (railExits >= 0) {
            hex.setDecorations(List.of(new BoardDecoration("track", "prop", RailRoute.Kind.RAIL.asset, null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.on("bridge", 0), 0).withConnections(railExits)));
        }
        var tile = BridgeScenes.bridge(AT, 0, 1, 9)
              .withFeatures(BoardFeatures.capture(hex, AT, Map.of()));
        var deck = new BoardBridge.Deck(BoardRoad.Kind.PAVED, 9, 9, false,
              Collections.nCopies(6, BoardRoad.Kind.PAVED), BoardScene.Surface.CONCRETE,
              Collections.nCopies(6, 0f), false, truss ? BoardDecoration.Colours.of("#732417") : null, 9, corridor);
        return BoardBridge.shape(deck.surface(), 0, BoardBridgeMesh.build(tile, deck));
    }

    private static float height(BoardBridge.Shape shape, float x, float y) {
        return 30 - (float) Math.sqrt(shape.hit(new Ray(new Vector3(x, y, 30), new Vector3(0, 0, -1))));
    }
}
