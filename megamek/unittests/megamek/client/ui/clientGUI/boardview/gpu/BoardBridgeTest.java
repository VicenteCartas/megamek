/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Hex;
import megamek.common.board.Coords;
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
        var tile = BoardBridgeSlopeTest.bridge(new Coords(0, 0), 0, 2, exits);
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
}
