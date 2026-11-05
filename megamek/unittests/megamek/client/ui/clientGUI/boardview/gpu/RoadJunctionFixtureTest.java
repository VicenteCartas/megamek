/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoadJunctionFixtureTest {
    @TempDir Path directory;

    @Test
    void savedFixtureKeepsEveryExitMaskAndItsConnectedArms() throws Exception {
        Board original = RoadJunctionFixture.create();
        Path file = directory.resolve("junctions.board2");
        BoardFile.save(original, file);
        Board loaded = BoardFile.read(file);
        assertEquals(original.getDocumentName(), loaded.getDocumentName());
        assertEquals(original.getAnnotations(), loaded.getAnnotations());
        for (var panel : RoadJunctionFixture.Panel.values()) {
            for (int mask = 0; mask < 64; mask++) {
                Coords at = RoadJunctionFixture.center(panel, mask);
                var hex = loaded.getHex(at);
                assertEquals(panel.road, hex.containsTerrain(Terrains.ROAD), panel + " " + mask);
                assertEquals(panel.rail, hex.containsTerrain(Terrains.RAIL), panel + " " + mask);
                if (panel.road) { assertEquals(mask, hex.getTerrain(Terrains.ROAD).getExits()); }
                if (panel.rail) {
                    assertEquals(mask, hex.getTerrain(Terrains.RAIL).getExits());
                    var route = RailRoute.find(hex, RailRoute.Kind.RAIL);
                    assertEquals(1 + Integer.bitCount(mask), RailRoute.run(loaded::getHex, at, route).size(),
                          "Each specimen is isolated from other cases but its own arms join");
                    for (int d = 0; d < 6; d++) {
                        if ((mask & 1 << d) != 0) {
                            assertNotNull(RailRoute.joined(hex, route, d, loaded.getHex(at.translated(d))));
                        }
                    }
                }
                assertEquals(original.getHex(at).getAppearance(), hex.getAppearance());
            }
        }
        for (var sample : RoadJunctionFixture.widthCases()) {
            assertEquals(original.getHex(sample.at()).getDecorations(), loaded.getHex(sample.at()).getDecorations(), sample.id());
            assertEquals(original.getHex(sample.at()).getAppearance(), loaded.getHex(sample.at()).getAppearance(), sample.id());
        }
        var controls = RoadJunctionFixture.controlCases();
        var expected = java.util.List.of(HexAppearance.TrafficControl.STOPS, HexAppearance.TrafficControl.SIGNALS,
              HexAppearance.TrafficControl.GANTRY, HexAppearance.TrafficControl.STOPS,
              HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY);
        for (int i = 0; i < controls.size(); i++) {
            var sample = controls.get(i);
            var hex = loaded.getHex(sample.at());
            int exits = i < 2 ? 11 : 27;
            assertEquals(exits, hex.getTerrain(Terrains.ROAD).getExits(), sample.id());
            assertEquals(expected.get(i), HexAppearance.trafficControl(hex.getAppearance(), "road", exits), sample.id());
            assertEquals(i != 0 && i != 2, hex.getAppearance().containsKey("road"),
                  "Automatic controls stay automatic; explicit choices survive saving: " + sample.id());
            assertEquals(original.getHex(sample.at()).getAppearance(), hex.getAppearance(), sample.id());
            assertEquals(i == 5, hex.containsTerrain(Terrains.RAIL));
            for (int d = 0; d < 6; d++) {
                if ((exits & 1 << d) == 0) { continue; }
                var arm = loaded.getHex(sample.at().translated(d));
                assertTrue(arm.containsTerrainExit(Terrains.ROAD, (d + 3) % 6));
                assertEquals(0, arm.getLevel(), "Control examples remain separate from the bridge and tunnel rows");
            }
        }
    }

    @Test
    void widthExamplesKeepTheirSceneryAndBridgeLayers() {
        Board board = RoadJunctionFixture.create();
        var samples = RoadJunctionFixture.widthCases();
        var parked = board.getHex(samples.get(0).at()).getDecorations();
        assertTrue(parked.stream().anyMatch(o -> o.asset().startsWith("decal/car-park/")));
        assertTrue(parked.stream().anyMatch(o -> o.asset().startsWith("scenery/vehicles/")));
        assertTrue(board.getHex(samples.get(1).at()).getDecorations().stream()
              .anyMatch(o -> BoardTreeDistribution.PARK.contains(o.asset())));
        for (int i = 2; i < 5; i++) {
            Coords at = samples.get(i).at();
            for (int step = -2; step <= 2; step++) {
                var hex = board.getHex(at.translated(step < 0 ? 0 : 3, Math.abs(step)));
                assertTrue(hex.containsTerrain(Terrains.BRIDGE_ROAD));
                assertFalse(hex.containsTerrain(Terrains.ROAD));
                assertEquals(i > 2, hex.containsTerrain(Terrains.BRIDGE_RAIL));
                assertEquals(2, hex.terrainLevel(Terrains.BRIDGE_ELEV));
            }
            for (int d : new int[] { 0, 3 }) {
                var approach = board.getHex(at.translated(d, 3));
                assertEquals(2, approach.getLevel());
                assertTrue(approach.containsTerrainExit(Terrains.ROAD, (d + 3) % 6));
                assertEquals(i > 2, approach.containsTerrain(Terrains.RAIL));
            }
            if (i > 2) {
                var marker = RailRoute.find(board.getHex(at), RailRoute.Kind.RAIL);
                assertEquals(7, RailRoute.run(board::getHex, at, marker).size(), "Deck and ground tracks join at equal height");
            }
        }
        assertBesideJoins(board, samples.get(4).at(), true);
        for (int d : new int[] { 0, 3 }) { assertBesideJoins(board, samples.get(4).at().translated(d, 2), true); }
        Coords tunnel = samples.get(5).at();
        assertTrue(board.getHex(tunnel).containsTerrainExit(Terrains.ROAD, 0));
        assertEquals(4, board.getHex(tunnel.translated(0)).getLevel());
        assertFalse(board.getHex(tunnel.translated(0)).containsTerrain(Terrains.ROAD),
              "A cosmetic portal must not invent a road connection through the cliff");
    }

    @Test
    void mixedAndTriangleCasesKeepIndependentTracksAndContinuousBesideChoices() {
        Board board = RoadJunctionFixture.create();
        var mixed = RoadJunctionFixture.mixedCases();
        var crossing = board.getHex(mixed.get(0).at());
        assertEquals(9, crossing.getTerrain(Terrains.ROAD).getExits());
        assertEquals(18, crossing.getTerrain(Terrains.RAIL).getExits());
        assertEquals(0, RailRoute.corridor(crossing, mixed.get(0).at(), board::getHex).sides(),
              "An independent crossing is not a shared road/rail corridor");
        for (int index : new int[] { 2, 4 }) { assertBesideJoins(board, mixed.get(index).at()); }
        var triangles = RoadJunctionFixture.triangleCases();
        for (var sample : triangles) {
            var vertices = RoadJunctionFixture.triangle(sample);
            for (int i = 0; i < vertices.size(); i++) {
                Coords from = vertices.get(i), to = vertices.get((i + 1) % vertices.size());
                boolean joined = false;
                for (int d = 0; d < 6; d++) {
                    if (from.translated(d).equals(to)) {
                        joined = board.getHex(from).containsTerrainExit(Terrains.ROAD, d)
                              && board.getHex(to).containsTerrainExit(Terrains.ROAD, (d + 3) % 6);
                    }
                }
                assertTrue(joined, sample.id() + " keeps every edge of the three-hex loop");
            }
        }
        assertFalse(board.getHex(triangles.get(0).at()).containsTerrain(Terrains.RAIL));
        assertBesideJoins(board, triangles.get(4).at());
        for (Coords at : new Coords[] { mixed.get(5).at(), triangles.get(5).at() }) {
            var hex = board.getHex(at);
            assertFalse(hex.containsTerrain(Terrains.RAIL));
            assertTrue(hex.containsTerrain(Terrains.ROAD));
            assertEquals(18, hex.getTerrain(Terrains.BRIDGE_RAIL).getExits());
            assertEquals(2, hex.terrainLevel(Terrains.BRIDGE_ELEV));
            assertFalse(hex.containsTerrain(Terrains.BRIDGE_ROAD));
        }
        assertTrue(HexAppearance.beside(board.getHex(RoadJunctionFixture.center(RoadJunctionFixture.Panel.BESIDE, 9))
              .getAppearance(), "rail") != 0);
        var variants = RoadJunctionFixture.triangleVariants();
        for (int i = 0; i < 2; i++) {
            var vertices = RoadJunctionFixture.triangle(variants.get(i));
            int[] expected = i == 0 ? new int[] { 13, 50, 19 } : new int[] { 44, 52, 19 };
            for (int vertex = 0; vertex < 3; vertex++) {
                assertEquals(expected[vertex], board.getHex(vertices.get(vertex)).getTerrain(Terrains.ROAD).getExits(),
                      variants.get(i).id() + " reproduces the reported Seaport topology");
            }
        }
    }

    private static void assertBesideJoins(Board board, Coords at) {
        assertBesideJoins(board, at, false);
    }

    private static void assertBesideJoins(Board board, Coords at, boolean deck) {
        var corridor = RailRoute.corridor(board.getHex(at), at, board::getHex, deck);
        var marker = RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL).stream()
              .filter(m -> RailRoute.onDeck(m) == deck).findFirst().orElseThrow();
        assertTrue(corridor.sides() != 0);
        assertEquals(corridor.sides(), corridor.beside());
        for (int d = 0; d < 6; d++) {
            if ((corridor.sides() & 1 << d) == 0) { continue; }
            Coords next = at.translated(d);
            var joined = RailRoute.joined(board.getHex(at), marker, d, board.getHex(next));
            assertNotNull(joined);
            var other = RailRoute.corridor(board.getHex(next), next, board::getHex, RailRoute.onDeck(joined));
            assertArrayEquals(RailRoute.borderOffset(d, RailRoute.BESIDE_RAIL * corridor.lateral(d)),
                  RailRoute.borderOffset((d + 3) % 6, RailRoute.BESIDE_RAIL * other.lateral((d + 3) % 6)), .0001f,
                  "The two offset tracks reach the same physical point on their shared border");
        }
    }
}
