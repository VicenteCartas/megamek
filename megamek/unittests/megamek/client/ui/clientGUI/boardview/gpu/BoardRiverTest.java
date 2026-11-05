/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRiverTest {
    @Test
    void theConvertedBridgeBankRoundsItsThreeShoresWithoutAProjectingShelf() throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "GrassLands/16x17 Grasslands River CommCenter.board"), false, true);
            Coords bridge = new Coords(7, 14), peninsula = new Coords(8, 15);
            assertEquals(1, board.getHex(bridge).terrainLevel(Terrains.BRIDGE_ROAD),
                  "Exercise the converted map that the game renders, including its connected paved bridge");
            var scene = BoardScenes.scene(board);
            for (TerrainLod lod : TerrainLod.values()) {
                Map<Coords, BoardSurface> surfaces = new HashMap<>();
                for (Coords at : List.of(bridge, new Coords(7, 15), new Coords(8, 14), peninsula)) {
                    var surface = new BoardSurface(scene, scene.tile(at), lod);
                    surfaces.put(at, surface);
                    for (var face : surface.faces) {
                        if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                        assertTrue(normal.z >= -.001f * normal.len(), "No folded bank top: " + at + ", " + lod);
                    }
                }
                BoardSurface water = surfaces.get(bridge), land = surfaces.get(peninsula);
                List<Vector3> bank = water.waterBoundary(5);
                Vector3 first = bank.getFirst(), last = bank.getLast();
                Vector3 along = new Vector3(last).sub(first).nor();
                float previous = 0;
                for (Vector3 point : bank) {
                    float width = Intersector.distanceSegmentPoint(first.x, first.y, last.x, last.y, point.x, point.y);
                    assertTrue(width < BoardRelief.metres(4),
                          "The narrow bank stays near its two mouths instead of forming a long shelf: " + width);
                    float progress = new Vector3(point).sub(first).dot(along);
                    assertTrue(progress >= previous - .01f * BoardGeometry.hexScale(),
                          "The shore advances around the bank without doubling back into a pointed tongue");
                    previous = progress;
                }
                int count = BoardSurface.SHORE_SEGMENTS;
                for (int edge : new int[] { 0, 4 }) {
                    Coords neighbor = bridge.translated(BoardGeometry.edgeDirection(edge));
                    BoardSurface across = surfaces.get(neighbor);
                    int opposite = (edge + 3) % 6;
                    for (int i = 0; i <= count; i++) {
                        Vector3 a = water.outline.get((edge * count + i) % (6 * count));
                        Vector3 b = across.outline.get(((opposite + 1) * count - i) % (6 * count));
                        assertTrue(a.epsilonEquals(b, .002f), "The three shores retain their exact shared mouths");
                    }
                }
                for (Coords at : List.of(bridge, new Coords(7, 15), new Coords(8, 14))) {
                    BoardSurface shore = surfaces.get(at);
                    int edge = Math.floorMod(1 - at.direction(peninsula), 6), opposite = (edge + 3) % 6;
                    for (float t : new float[] { .25f, .5f, .75f }) {
                        for (float z : new float[] { 0, BoardGeometry.level() / 2, BoardGeometry.level() }) {
                            assertTrue(shore.relief.outlinePoint(edge, t, z)
                                  .epsilonEquals(land.relief.outlinePoint(opposite, 1 - t, z), .002f),
                                  "The water-owned bank and the raised land share the same wall");
                        }
                    }
                }
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void concreteAcrossANarrowChannelCannotPullTheOppositeBankIntoWater() {
        var original = BoardRelief.tuning();
        var geometry = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            BoardScene scene = commCenterRiverScene();
            Coords peninsula = new Coords(4, 5);
            BoardSurface land = new BoardSurface(scene, scene.tile(peninsula));
            Vector3 corner = BoardGeometry.corner(peninsula, 0, 2);
            Vector3 inward = BoardGeometry.center(peninsula, 0).sub(corner).nor();
            float[] shift = land.relief.shoreShift(2);
            assertTrue(shift[0] * inward.x + shift[1] * inward.y > 0,
                  "The bank recedes into its peninsula instead of reaching across the channel towards concrete");

            // The other tip legitimately advances a little. Searching backwards must find its local crossing too.
            corner = BoardGeometry.corner(peninsula, 0, 0);
            Vector3 away = corner.sub(BoardGeometry.center(new Coords(5, 4), 0)).nor();
            shift = land.relief.shoreShift(0);
            float retreat = shift[0] * away.x + shift[1] * away.y;
            assertTrue(retreat < -BoardGeometry.hexScale() && retreat > -20 * BoardGeometry.hexScale(),
                  "The neighbouring tip keeps its bounded outward shoreline instead of clamping all moves inward");

            for (Coords first : List.of(new Coords(3, 4), new Coords(4, 4))) {
                Coords second = new Coords(first.getX() + 1, first.getY());
                BoardSurface left = new BoardSurface(scene, scene.tile(first));
                BoardSurface right = new BoardSurface(scene, scene.tile(second));
                int edge = Math.floorMod(1 - first.direction(second), 6), opposite = (edge + 3) % 6;
                int count = BoardSurface.SHORE_SEGMENTS;
                for (int i = 0; i <= count; i++) {
                    Vector3 a = left.outline.get((edge * count + i) % (6 * count));
                    Vector3 b = right.outline.get(((opposite + 1) * count - i) % (6 * count));
                    assertTrue(a.epsilonEquals(b, .002f),
                          "Both water tiles meet at the moved bank and throughout their shared mouth");
                }
            }
        } finally {
            BoardRelief.tune(original);
            BoardGeometry.tune(geometry);
        }
    }

    /** Shared CommCenter river crop for the opposite-bank and concrete/natural contact regressions. */
    static BoardScene commCenterRiverScene() {
        // C/D are concrete at levels 1/3, W water, digits raised grass, and dots level grass.
        // The concrete heights matter: fortifications split the fitted quay into separate terraces.
        String[] rows = { "CDCCCCDW.", "CDCCCD2WW", "CDCDD.WWW", "CC2CC.WW.",
              "C.CWWWW..", "..WW11...", "..WW....." };
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < rows[0].length(); x++) {
            for (int y = 0; y < rows.length; y++) {
                char ground = rows[y].charAt(x);
                boolean wet = ground == 'W', concrete = ground == 'C' || ground == 'D';
                int level = ground == 'C' || ground == '1' ? 1 : ground == 'D' ? 3 : ground == '2' ? 2 : 0;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, wet ? 1 : -1, false, 0,
                      concrete ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, null, null, List.of(),
                      List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, rows[0].length(), rows.length, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void exposedBanksMeetAcrossFrozenAndOpenRiverMouths() {
        var original = BoardRelief.tuning();
        var geometry = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            Coords frozen = new Coords(3, 3);
            for (int direction = 0; direction < 6; direction++) {
                Coords open = frozen.translated(direction);
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        Coords coords = new Coords(x, y);
                        boolean wet = coords.equals(frozen) || coords.equals(open);
                        tiles.add(new BoardScene.Tile(coords, 0, wet ? 1 : -1, coords.equals(frozen), 0,
                              BoardScene.Surface.SNOW, null, null, List.of(), List.of(),
                              wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface frozenSurface = new BoardSurface(scene, scene.tile(frozen));
                BoardSurface openSurface = new BoardSurface(scene, scene.tile(open));
                List<BoardSurface.Face> frozenBank = frozenSurface.faces.stream()
                      .filter(face -> face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE)
                      .toList();
                List<BoardSurface.Face> openBank = openSurface.faces.stream()
                      .filter(face -> face.finish() == BoardSurface.Finish.TOP || face.finish() == BoardSurface.Finish.SHORE)
                      .toList();
                int edge = Math.floorMod(1 - direction, 6), compared = 0;
                Vector3[] corners = frozenSurface.waterGeometry().shoreCorners();
                Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
                Vector3 across = BoardGeometry.center(open, 0).sub(BoardGeometry.center(frozen, 0)).nor().scl(.0001f);
                for (int k = 1; k < 100; k++) {
                    Vector3 p = new Vector3(a).lerp(b, k / 100f);
                    float own = BoardSurface.sampleHeight(frozenBank, p.x - across.x, p.y - across.y, Float.NaN);
                    float theirs = BoardSurface.sampleHeight(openBank, p.x + across.x, p.y + across.y, Float.NaN);
                    if (Float.isNaN(own) || Float.isNaN(theirs)) { continue; }
                    assertEquals(own, theirs, .005f, "Frozen/open banks meet without a slit in direction " + direction
                          + " at " + p);
                    compared++;
                }
                assertTrue(compared > 10, "Both dry banks of the shared mouth were sampled: " + compared);
            }
        } finally {
            BoardRelief.tune(original);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void channelProfilesStayDeterministicAcrossDepthArtworkAndQueryOrderChanges() {
        var original = BoardRelief.tuning();
        var geometry = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            Coords center = new Coords(3, 3), artwork = center.translated(2);
            BoardScene scene = scene(Map.of(center, 1, center.translated(0), 0, center.translated(1), 2,
                  artwork, 1, center.translated(4), 0, new Coords(0, 0), 1), artwork);
            // Sample mixed depths, lake inlets, authored water and the board edge before warming the reverse query.
            BoardRiver river = new BoardRiver(scene);
            List<Vector3> samples = new ArrayList<>();
            for (int x = -80; x < 540; x += 13) {
                for (int y = -570; y < 80; y += 17) {
                    float field = river.field(x + .25f, y + .75f);
                    assertFalse(Float.isNaN(field), "The shore field remains defined");
                    samples.add(new Vector3(x + .25f, y + .75f, field));
                }
            }
            BoardRiver reverse = new BoardRiver(scene);
            for (int i = samples.size() - 1; i >= 0; i--) {
                Vector3 sample = samples.get(i);
                assertEquals(sample.z, reverse.field(sample.x, sample.y), "Query order cannot move a bank");
                assertEquals(sample.z, river.field(sample.x, sample.y), "A cached query cannot move a bank");
            }
        } finally {
            BoardRelief.tune(original);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void boundedRiverQueriesPreserveTheShoreIntersectionAtJunctionsAndBoardEdges() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3);
            BoardScene scene = scene(Map.of(center, 0, center.translated(0), 1, center.translated(2), 0,
                  center.translated(4), 2, new Coords(0, 0), 1));
            BoardRiver river = new BoardRiver(scene);
            for (int x = -80; x < 540; x += 13) {
                for (int y = -570; y < 80; y += 17) {
                    float full = river.field(x, y);
                    for (float limit : new float[] { -20, -1, 0, 1, 20 }) {
                        assertEquals(Math.min(limit, full), Math.min(limit, river.field(x, y, limit)),
                              "Clamping a river union must preserve the exact shore intersection");
                    }
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void streamsKeepUnitsInWaterAndMeetAcrossBends() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3);
            for (int depth : new int[] { 0, 1 }) {
                for (int from : new int[] { 0, 2, 4 }) {
                    for (int turn : new int[] { 2, 3 }) {
                        int to = (from + turn) % 6;
                        BoardScene scene = scene(Map.of(center, depth, center.translated(from), depth,
                              center.translated(to), depth));
                        BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                        String label = "depth=" + depth + ", from=" + from + ", turn=" + turn;
                        for (Vector3 point : surface.outline) {
                            assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y), "Finite shore: " + label);
                        }
                        Vector3 p = BoardGeometry.center(center, 0);
                        assertEquals(BoardGeometry.groundZ(scene.tile(center)), surface.height(p.x, p.y), .001f, label);
                        if (depth > 0) {
                            for (int a = 0; a < 12; a++) {
                                float x = p.x + 8 * BoardGeometry.HEX_SCALE * (float) Math.cos(a * Math.PI / 6);
                                float y = p.y + 8 * BoardGeometry.HEX_SCALE * (float) Math.sin(a * Math.PI / 6);
                                assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.waterFaces, x, y,
                                      Float.NEGATIVE_INFINITY)), "Unit footprint stays in water: " + label);
                            }
                        }
                        for (var face : surface.faces) {
                            if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP
                                  && face.finish() != BoardSurface.Finish.SHORE) { continue; }
                            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                            assertTrue(normal.z >= -.001f * normal.len(), "No folded ground: " + label + ": " + face);
                        }
                        for (int direction : new int[] { from, to }) {
                            BoardSurface next = new BoardSurface(scene, scene.tile(center.translated(direction)));
                            int e = Math.floorMod(1 - direction, 6), opposite = (e + 3) % 6, n = BoardSurface.SHORE_SEGMENTS;
                            for (int i = 0; i <= n; i++) {
                                Vector3 a = surface.outline.get((e * n + i) % (6 * n));
                                Vector3 b = next.outline.get(((opposite + 1) * n - i) % (6 * n));
                                assertTrue(a.epsilonEquals(b, .002f), "Shared mouth: " + label + " at " + i);
                            }
                            float opening = surface.outline.get(e * n).dst(surface.outline.get((e + 1) * n % (6 * n)));
                            assertTrue(opening > .3f * BoardGeometry.HEX_SCALE, "Connected stream stays open: " + label);
                        }
                    }
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void everyRiverJunctionKeepsTheUnitAnchor() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3);
            for (int mask = 0; mask < 64; mask++) {
                Map<Coords, Integer> water = new HashMap<>(Map.of(center, 1));
                for (int d = 0; d < 6; d++) {
                    if ((mask & 1 << d) != 0) { water.put(center.translated(d), 1); }
                }
                BoardScene scene = scene(water);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                Vector3 p = BoardGeometry.center(center, 0);
                assertEquals(BoardGeometry.groundZ(scene.tile(center)), surface.height(p.x, p.y), .001f, "Mask " + mask);
                for (Vector3 point : surface.outline) {
                    assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y), "Finite junction " + mask);
                }
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP) { continue; }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z >= -.001f * normal.len(), "Junction bank faces up: " + mask);
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void aStreamSharesItsMouthWithWaterUsingSpecialArtwork() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3), north = center.translated(0);
            BoardScene scene = scene(Map.of(center, 1, north, 1, center.translated(3), 1), north);
            BoardSurface a = new BoardSurface(scene, scene.tile(center)), b = new BoardSurface(scene, scene.tile(north));
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int i = 0; i <= n; i++) {
                assertTrue(a.outline.get(n + i).epsilonEquals(b.outline.get(5 * n - i), .002f));
            }
            Vector3 p = BoardGeometry.center(north, 0);
            assertEquals(BoardGeometry.groundZ(scene.tile(north)), b.height(p.x, p.y), .001f);
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void continuingRiverHexesChangeTheBendBeforeThem() {
        Coords a = new Coords(3, 4), b = a.translated(0), c = b.translated(0);
        BoardScene straight = scene(Map.of(a, 1, b, 1, c, 1));
        BoardScene turning = scene(Map.of(a, 1, b, 1, b.translated(1), 1));
        float x = BoardGeometry.centerX(a), y = (BoardGeometry.centerY(a) + BoardGeometry.centerY(b)) / 2;
        float left = new BoardRiver(straight).field(x - 15, y);
        float curved = new BoardRiver(turning).field(x - 15, y);
        assertTrue(Math.abs(left - curved) > .5f, "The following hex changes the approaching stream's direction");
    }

    @Test
    void channelsDoNotPinchAtHexEdges() {
        var original = BoardRelief.tuning();
        try {
            for (int depth : new int[] { 0, 1 }) {
                Map<Coords, Integer> water = new HashMap<>();
                for (int y = 0; y < 7; y++) { water.put(new Coords(3, y), depth); }
                BoardScene scene = scene(water);
                List<BoardSurface> surfaces = water.keySet().stream()
                      .map(c -> new BoardSurface(scene, scene.tile(c))).toList();
                float from = BoardGeometry.centerY(new Coords(3, 1)), to = BoardGeometry.centerY(new Coords(3, 5));
                float narrowest = Float.POSITIVE_INFINITY, widest = 0;
                for (int i = 0; i < 33; i++) {
                    float[] section = section(surfaces, from + (to - from) * i / 32);
                    narrowest = Math.min(narrowest, section[1] - section[0]);
                    widest = Math.max(widest, section[1] - section[0]);
                }
                assertTrue(narrowest > .65f * widest, "No sequence of pools and pinched crossings: depth=" + depth
                      + ", narrowest=" + narrowest + ", widest=" + widest);
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void straightRunsWanderSidewaysAcrossHexes() {
        var original = BoardRelief.tuning();
        try {
            Map<Coords, Integer> water = new HashMap<>();
            for (int y = 0; y < 7; y++) { water.put(new Coords(3, y), 1); }
            BoardScene scene = scene(water);
            List<BoardSurface> surfaces = water.keySet().stream()
                  .map(c -> new BoardSurface(scene, scene.tile(c))).toList();
            float from = BoardGeometry.centerY(new Coords(3, 1)), to = BoardGeometry.centerY(new Coords(3, 5));
            float[] middle = new float[65];
            for (int i = 0; i < middle.length; i++) {
                float[] section = section(surfaces, from + (to - from) * i / (middle.length - 1));
                middle[i] = (section[0] + section[1]) / 2;
            }
            float bend = 0;
            for (int i = 1; i < middle.length - 1; i++) {
                float straight = middle[0] + (middle[middle.length - 1] - middle[0]) * i / (middle.length - 1);
                bend = Math.max(bend, Math.abs(middle[i] - straight));
            }
            // A change of width alone leaves the centreline straight. How far it bends is where the run samples the
            // wander field: 1.1 to 3.3 units on the columns of a board this size, 1.5 on this one.
            assertTrue(bend > BoardGeometry.HEX_SCALE, "The centreline bends, rather than only changing width: " + bend);
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static float[] section(List<BoardSurface> surfaces, float y) {
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        for (BoardSurface surface : surfaces) {
            for (int j = 0; j < surface.outline.size(); j++) {
                Vector3 a = surface.outline.get(j), b = surface.outline.get((j + 1) % surface.outline.size());
                if ((a.y > y) == (b.y > y)) { continue; }
                float x = a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        assertTrue(Float.isFinite(left) && right > left, "The stream stays connected at " + y);
        return new float[] { left, right };
    }

    @Test
    void riverJunctionsHaveRoundedInsideCorners() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3);
            Map<Coords, Integer> water = new HashMap<>(Map.of(center, 1));
            for (int d : new int[] { 0, 2, 4 }) {
                water.put(center.translated(d), 1);
                water.put(center.translated(d, 2), 1);
            }
            BoardScene scene = scene(water);
            List<Vector3> outline = new BoardSurface(scene, scene.tile(center)).outline;
            float sharpest = 0;
            for (int i = 0; i < outline.size(); i++) {
                Vector3 a = new Vector3(outline.get(i)).sub(outline.get((i + outline.size() - 1) % outline.size()));
                Vector3 b = new Vector3(outline.get((i + 1) % outline.size())).sub(outline.get(i));
                float turn = (float) Math.atan2(a.x * b.y - a.y * b.x, a.x * b.x + a.y * b.y);
                sharpest = Math.max(sharpest, -turn);
            }
            assertTrue(sharpest < Math.PI / 4, "No sharp land point between the branches: " + Math.toDegrees(sharpest));
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void theReferenceRiverBendHasARoundedSandTip() {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            var scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
            var surface = new BoardSurface(scene, scene.tile(new Coords(7, 14)));
            List<Vector3> bank = surface.waterBoundary(5);
            for (int i = 1; i + 1 < bank.size(); i++) {
                Vector3 incoming = new Vector3(bank.get(i)).sub(bank.get(i - 1));
                Vector3 outgoing = new Vector3(bank.get(i + 1)).sub(bank.get(i));
                double turn = Math.atan2(incoming.x * outgoing.y - incoming.y * outgoing.x,
                      incoming.x * outgoing.x + incoming.y * outgoing.y);
                assertTrue(turn > -Math.PI / 6,
                      "The inner sand bank must round the bend instead of ending in a cusp: " + Math.toDegrees(turn));
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void raisedBanksMeetSmoothlyAcrossTheReferenceRiverJunction() {
        var original = BoardRelief.tuning();
        var geometry = BoardGeometry.tuning();
        try {
            BoardScenes.tune(.8f, true);
            BoardScene scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
            Coords center = new Coords(6, 8);
            BoardSurface a = new BoardSurface(scene, scene.tile(center));
            // Both raised banks in the reported image: 0708/0709 and 0609/0709.
            for (int direction : new int[] { 0, 4 }) {
                Coords next = center.translated(direction);
                BoardSurface b = new BoardSurface(scene, scene.tile(next));
                int edge = Math.floorMod(1 - direction, 6);
                for (int side = 0; side < 2; side++) {
                    assertSmoothBankJoin(a, b, edge, side, "neighbor=" + next + ", bank=" + side);
                }
            }
        } finally {
            BoardRelief.tune(original);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void theReferenceLakeFillsOutItsFlatBank() {
        var original = BoardRelief.tuning();
        var geometry = BoardGeometry.tuning();
        try {
            BoardScenes.tune(.8f, true);
            BoardScene scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
            List<Coords> bank = List.of(new Coords(3, 11), new Coords(3, 12), new Coords(3, 13));
            List<BoardSurface> surfaces = bank.stream().map(c -> new BoardSurface(scene, scene.tile(c))).toList();
            float[] west = new float[bank.size()];
            for (int i = 0; i < bank.size(); i++) {
                west[i] = section(surfaces, BoardGeometry.centerY(bank.get(i)))[0];
            }
            float scale = BoardGeometry.HEX_SCALE;
            assertTrue((west[0] + west[2]) / 2 - west[1] > 3 * scale,
                  "The lake bows outward across its three bank hexes");
            float midline = (BoardGeometry.centerX(bank.get(1)) + BoardGeometry.centerX(new Coords(2, 12))) / 2;
            assertTrue(west[1] < midline - 2 * scale, "Open water fills the flat margin");
            BoardSurface inlet = new BoardSurface(scene, scene.tile(new Coords(4, 10)));
            BoardSurface pool = new BoardSurface(scene, scene.tile(new Coords(4, 11)));
            assertSmoothBankJoin(inlet, pool, 4, 0, "The stream opens smoothly into the lake");
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int i = 1; i < surfaces.size(); i++) {
                BoardSurface north = surfaces.get(i - 1), south = surfaces.get(i);
                for (int j = 0; j <= n; j++) {
                    assertTrue(north.outline.get(4 * n + j).epsilonEquals(south.outline.get(2 * n - j), .002f),
                          "The expanded lake still shares each water crossing");
                }
            }
            for (int y = 11; y <= 14; y++) {
                Coords coords = new Coords(2, y);
                BoardSurface land = new BoardSurface(scene, scene.tile(coords));
                Vector3 center = BoardGeometry.center(coords, scene.tile(coords).elevation());
                assertEquals(center.z, land.height(center.x, center.y), .001f, "Land units keep dry ground");
                assertTrue(land.relief.shore(center.x, center.y, false) < 0, "The lake respects land centres");
            }
        } finally {
            BoardRelief.tune(original);
            BoardGeometry.tune(geometry);
        }
    }

    private static void assertSmoothBankJoin(BoardSurface a, BoardSurface b, int edge, int side, String label) {
        int n = BoardSurface.SHORE_SEGMENTS, count = 6 * n, opposite = (edge + 3) % 6;
        int i = (edge + side) % 6 * n, j = (opposite + 1 - side) % 6 * n;
        Vector3 join = a.outline.get(i);
        assertTrue(join.epsilonEquals(b.outline.get(j), .002f), "Shared crossing: " + label);
        List<Vector3> bank = new ArrayList<>();
        for (int offset = 3; offset > 0; offset--) {
            bank.add(side == 0 ? a.outline.get((i + count - offset) % count) : b.outline.get((j + count - offset) % count));
        }
        bank.add(join);
        for (int offset = 1; offset <= 3; offset++) {
            bank.add(side == 0 ? b.outline.get((j + offset) % count) : a.outline.get((i + offset) % count));
        }
        for (int p = 1; p < bank.size() - 1; p++) {
            Vector3 in = new Vector3(bank.get(p)).sub(bank.get(p - 1));
            Vector3 out = new Vector3(bank.get(p + 1)).sub(bank.get(p));
            double turn = Math.atan2(in.x * out.y - in.y * out.x, in.x * out.x + in.y * out.y);
            assertTrue(Math.abs(turn) < Math.PI / 6, "No pointed bank around the hex join: " + label
                  + ", sample=" + p + ", turn=" + Math.toDegrees(turn));
        }
    }

    @Test
    void depthZeroCanExposeSandButDeepWaterKeepsItsBedSubmerged() {
        Coords center = new Coords(3, 3);
        for (int depth : new int[] { 0, 1 }) {
            BoardScene scene = scene(Map.of(center, depth, center.translated(0), depth, center.translated(3), depth));
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            float highest = Float.NEGATIVE_INFINITY;
            for (var face : surface.faces) {
                if (face.finish() != BoardSurface.Finish.BED) { continue; }
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) { highest = Math.max(highest, p.z); }
            }
            float water = BoardGeometry.waterZ(scene.tile(center));
            if (depth == 0) { assertTrue(highest > water, "Shallows have visible sand bars"); }
            else { assertTrue(highest <= water, "Deep-water beds stay under water"); }
        }
    }

    @Test
    void shallowBarsJoinTheBankAndTheirExposedEdgesDefineTheWaterline() {
        Coords center = new Coords(3, 3);
        BoardScene scene = scene(Map.of(center, 0, center.translated(0), 0, center.translated(3), 0));
        BoardSurface surface = new BoardSurface(scene, scene.tile(center));
        List<BoardSurface.Face> bank = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
        float water = BoardGeometry.waterZ(surface.tile);
        List<Float> edges = new ArrayList<>();
        int joins = 0;
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.BED) { continue; }
            GpuWaterShader.waterline(edges, surface, face);
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.z <= water + .1f || surface.outline.stream().noneMatch(q -> Math.hypot(p.x - q.x, p.y - q.y) < .001f)) {
                    continue;
                }
                assertEquals(p.z, BoardSurface.sampleHeight(bank, p.x, p.y, Float.NaN), .002f,
                      "Emerging sand joins the bank above the former shoreline");
                assertNotNull(surface.relief.shade(p), "The bed and bank share their lighting normal");
                joins++;
            }
        }
        assertTrue(joins > 0, "Sand bars reach the dry bank without an artificial groove");
        assertFalse(edges.isEmpty(), "Exposed sand has its own waterline");
        assertEquals(0, edges.size() % 4, "Every contour is a complete segment");
        for (int i = 0; i < edges.size(); i += 2) {
            assertEquals(water, surface.height(edges.get(i), edges.get(i + 1)), .003f,
                  "Foam follows the ground's actual intersection with water");
        }
    }

    private static BoardScene scene(Map<Coords, Integer> water, Coords... artwork) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        List<Coords> special = List.of(artwork);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int depth = water.getOrDefault(coords, -1);
                tiles.add(new BoardScene.Tile(coords, depth >= 0 ? 0 : 1, depth, false, 0, BoardScene.Surface.SAND,
                      null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, !special.contains(coords)));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void riverBanksOccupyOnlyTheLandEdgesAndNeighboringWaterMouthsMatch() {
        BoardScene scene = BoardScenes.roadScene(true);
        BoardSurface first = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
        BoardSurface second = new BoardSurface(scene, scene.tile(BoardScenes.SECOND));
        Vector3 center = BoardGeometry.center(BoardScenes.FIRST, 0);
        assertEquals(-2 * BoardGeometry.LEVEL, first.height(center.x, center.y), 0.01f);
        Vector3 bank = BoardGeometry.corner(BoardScenes.FIRST, 0, 3).lerp(BoardGeometry.corner(BoardScenes.FIRST, 0, 4), 0.5f);
        assertEquals(0, first.height(bank.x, bank.y), 0.01f);
        Vector3 a = BoardGeometry.corner(BoardScenes.FIRST, 0, 0), b = BoardGeometry.corner(BoardScenes.FIRST, 0, 1);
        List<Vector3> mouth = first.water.stream().filter(p -> onEdge(p, a, b)).toList();
        List<Vector3> neighbor = second.water.stream().filter(p -> onEdge(p, a, b)).toList();
        assertTrue(mouth.size() >= 2);
        assertEquals(mouth.size(), neighbor.size());
        assertTrue(mouth.stream().allMatch(p -> neighbor.stream().anyMatch(n -> n.epsilonEquals(p, 0.01f))));
        assertTrue(first.water.stream().anyMatch(p -> p.dst2(BoardGeometry.corner(BoardScenes.FIRST, 0, 3)) > 1));
    }

    private static boolean onEdge(Vector3 p, Vector3 a, Vector3 b) {
        return Math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) < 0.01f;
    }

    @Test
    void straightRiversMeanderAsChannelsInsteadOfMakingAPool() {
        for (int direction = 0; direction < 6; direction++) {
            Coords center = new Coords(2, 2);
            BoardScene scene = BoardScenes.riverScene(Map.of(center, 0, center.translated(direction), 0,
                  center.translated((direction + 3) % 6), 0), false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            Vector3 along = BoardGeometry.center(center.translated(direction), 0).sub(BoardGeometry.center(center, 0)).nor();
            Vector3 across = new Vector3(along).crs(Vector3.Z);
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Vector3 point : surface.water) {
                float offset = new Vector3(point).sub(BoardGeometry.center(center, 0)).dot(across);
                min = Math.min(min, offset);
                max = Math.max(max, offset);
            }
            int edge = Math.floorMod(1 - direction, 6);
            Vector3 a = BoardGeometry.corner(center, 0, edge), b = BoardGeometry.corner(center, 0, edge + 1);
            float edgeWidth = a.dst(b);
            var mouth = surface.water.stream().filter(point -> onEdge(point, a, b)).toList();
            float mouthWidth = 0;
            for (Vector3 first : mouth) {
                for (Vector3 second : mouth) {
                    mouthWidth = Math.max(mouthWidth, first.dst(second));
                }
            }
            assertTrue(mouthWidth > edgeWidth * 0.75f && mouthWidth < edgeWidth * 0.85f,
                  "The water uses most of the shared edge while leaving room for the sand fade");
            // The centre retains the pool radius even where fixed banks make its mouths narrower. Elsewhere the river
            // can meander and breathe, but must still stay a channel; the shore search places each bank point to
            // within a quarter unit.
            float widest = Math.max(mouthWidth * 1.6f, 2 * BoardRelief.SHORE_POOL * BoardGeometry.HEX_SCALE);
            assertTrue(max - min > mouthWidth * .99f && max - min <= widest + .5f * BoardGeometry.HEX_SCALE,
                  "The channel stays a channel: " + (max - min) + " across for a mouth of " + mouthWidth);
            for (Vector3 corner : List.of(a, b)) {
                assertTrue(surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP)
                      .anyMatch(face -> face.a().epsilonEquals(corner, 0.01f)
                            || face.b().epsilonEquals(corner, 0.01f) || face.c().epsilonEquals(corner, 0.01f)),
                      "The bank starts at the shared edge's corners");
            }
            assertEquals(-BoardGeometry.LEVEL, surface.height(BoardGeometry.centerX(center), BoardGeometry.centerY(center)), 0.01f);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void aRiverBankKeepsCurvingBesideHigherGround(boolean transitions) {
        BoardScenes.withTransitions(transitions, () -> {
            Coords center = new Coords(3, 3);
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int edge = 0; edge < 6; edge++) {
                int before = (edge + 5) % 6, after = (edge + 1) % 6;
                Coords upstream = center.translated(BoardGeometry.edgeDirection(before));
                Coords downstream = center.translated(BoardGeometry.edgeDirection(after));
                Coords ridge = center.translated(BoardGeometry.edgeDirection(edge));
                for (int height : new int[] { 1, 2 }) {
                    BoardScene scene = BoardScenes.sandScene(7, 0, Map.of(ridge, height),
                          Map.of(center, 1, upstream, 1, downstream, 1), false);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                    Vector3 a = surface.outline.get(edge * n);
                    Vector3 b = surface.outline.get((edge + 1) * n % surface.outline.size());
                    float curve = 0;
                    for (int i = 1; i < n; i++) {
                        curve = Math.max(curve, BoardScenes.distance(surface.outline.get(edge * n + i), a, b));
                    }
                    assertTrue(curve > BoardGeometry.HEX_SCALE,
                          "The stream bends around the ridge, edge " + edge + ", height " + height);
                    for (int mouth : new int[] { before, after }) {
                        BoardSurface neighbor = new BoardSurface(scene,
                              scene.tile(center.translated(BoardGeometry.edgeDirection(mouth))));
                        for (int i = 0; i <= n; i++) {
                            Vector3 point = surface.outline.get((mouth * n + i) % surface.outline.size());
                            assertTrue(neighbor.outline.stream().anyMatch(point::equals),
                                  "Rounding the bank must preserve matching river openings");
                        }
                    }
                }
            }
        });
    }

    @Test
    void theRiverbedMeetsItsCurvedBanksAndDescendsBeneathTheWater() {
        // A river a level deep between banks a level above its surface: two levels between the grounds units stand on,
        // a slope that meets the water on the edge and carries on down to the bed.
        BoardScenes.withTransitions(true, () -> {
            Coords center = new Coords(3, 3);
            for (int direction = 0; direction < 6; direction++) {
                List<Coords> river = List.of(center, center.translated(direction),
                      center.translated((direction + 3) % 6));
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        Coords coords = new Coords(x, y);
                        boolean wet = river.contains(coords);
                        tiles.add(new BoardScene.Tile(coords, wet ? 0 : 1, wet ? 1 : -1, false, 0,
                              BoardScene.Surface.SAND, null, null, List.of(), List.of(),
                              wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                List<Integer> mouths = List.of(Math.floorMod(1 - direction, 6), Math.floorMod(-2 - direction, 6));
                for (int edge = 0; edge < 6; edge++) {
                    if (mouths.contains(edge)) { continue; }
                    // Width is now adjustable. Check the actual shore, rather than requiring it to lie within
                    // 6.72 units of the original straight hex edge.
                    for (int sample = 2; sample < BoardSurface.SHORE_SEGMENTS - 1; sample++) {
                        Vector3 point = surface.outline.get(edge * BoardSurface.SHORE_SEGMENTS + sample);
                        assertEquals(BoardGeometry.waterZ(scene.tile(center)), surface.height(point.x, point.y), .03f,
                              "The bank meets the waterline");
                        Vector3 inside = new Vector3(point).lerp(BoardGeometry.center(center, 0), .2f);
                        assertTrue(surface.height(inside.x, inside.y) < BoardGeometry.waterZ(scene.tile(center)) - .1f,
                              "The slope continues down under the water");
                    }
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 18 })
    void raisedRiverBanksShareTheirGroundNormalsAndFeatherBothContacts(int levelHeight) {
        var geometry = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
            for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT)) {
                var tiles = new ArrayList<BoardScene.Tile>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        boolean wet = x == 3 && y > 0 && y < 6;
                        tiles.add(new BoardScene.Tile(new Coords(x, y), x < 3 ? 1 : 0, wet ? 1 : -1, false,
                              0, family, null, null, List.of(), List.of(),
                              wet ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
                    }
                }
                var scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                var high = new BoardSurface(scene, scene.tile(new Coords(2, 3)));
                var low = new BoardSurface(scene, scene.tile(new Coords(3, 3)));
                Map<Vector3, Vector3> upperNormals = new HashMap<>(), lowerNormals = new HashMap<>();
                for (var surface : List.of(high, low)) {
                    var normals = surface == high ? upperNormals : lowerNormals;
                    for (var face : surface.faces) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            var shade = surface.relief.shade(point);
                            if (shade != null && shade.kind() == BoardRelief.Kind.GROUND) {
                                normals.put(point, new Vector3(shade.normal()));
                            }
                        }
                    }
                }
                int feet = 0, rims = 0, feathers = 0, middle = 0;
                float contact = Math.min(BoardRelief.metres(1.5f), levelHeight * .5f);
                for (var face : high.walls(scene, BoardGeometry.floor(scene))) {
                    if (!high.tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge()))
                          .equals(low.tile.coords())) { continue; }
                    for (var point : List.of(face.a(), face.b(), face.c())) {
                        var normal = high.relief.shade(point).normal();
                        if (point.z == 0 && lowerNormals.containsKey(point)) {
                            assertTrue(normal.epsilonEquals(lowerNormals.get(point), .00001f),
                                  "The exposed wall meets its water-owned ground with the same normal");
                            feet++;
                        } else if (point.z == levelHeight && upperNormals.containsKey(point)) {
                            assertTrue(normal.epsilonEquals(upperNormals.get(point), .00001f),
                                  "The wall retains its actual shared crest normal");
                            rims++;
                        } else if (point.z > 0 && point.z < contact * .5f
                              || point.z < levelHeight && point.z > levelHeight - contact * .5f) {
                            assertTrue(normal.z > .75f, "Contact rows feather into the adjoining ground");
                            feathers++;
                        } else if (point.z > contact && point.z < levelHeight - contact && normal.z < .8f) {
                            middle++;
                        }
                    }
                }
                assertTrue(feet > 0 && rims > 0 && feathers > 0, "Exercise both contacts and their interior rows");
                if (levelHeight == 18) { assertTrue(middle > 0, "The slope's middle retains its steep normals"); }
                for (var entry : upperNormals.entrySet()) {
                    assertTrue(high.relief.shade(entry.getKey()).normal().epsilonEquals(entry.getValue(), .00001f),
                          "Blending the wall must not mutate its top's shared normal vectors");
                }
            }
        } finally { BoardGeometry.tune(geometry); }
    }

    @Test
    void riverFacingSlopesShareTheirShadingWithTheAdjoiningDrySlope() throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "GrassLands/16x17 Grasslands River CommCenter.board"), false, true);
            var scene = BoardScenes.scene(board);
            // The bridge landing and the separate northern hill both turn from a river bank into a dry slope.
            for (var coords : List.of(new Coords(8, 15), new Coords(10, 2))) {
                int wetEdge = coords.getX() == 8 ? 3 : 4, dryEdge = wetEdge + 1;
                assertTrue(scene.tile(coords.translated(BoardGeometry.edgeDirection(wetEdge))).liquid().present());
                assertFalse(scene.tile(coords.translated(BoardGeometry.edgeDirection(dryEdge))).liquid().present());
                for (TerrainLod lod : TerrainLod.values()) {
                    Map<Coords, BoardSurface> surfaces = new HashMap<>();
                    surfaces.put(coords, new BoardSurface(scene, scene.tile(coords), lod));
                    for (int direction = 0; direction < 6; direction++) {
                        Coords at = coords.translated(direction);
                        if (scene.tile(at) != null) { surfaces.put(at, new BoardSurface(scene, scene.tile(at), lod)); }
                    }
                    var surface = surfaces.get(coords);
                    Map<Vector3, BoardRelief.Shade> wet = new HashMap<>(), dry = new HashMap<>();
                    for (var face : surface.walls(scene, BoardGeometry.floor(scene), surfaces)) {
                        if (face.landEdge() != wetEdge && face.landEdge() != dryEdge) { continue; }
                        var shades = face.landEdge() == wetEdge ? wet : dry;
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            // Equal positions have separate vertex identities and must retain each side's shade.
                            shades.put(point, surface.relief.shade(point));
                        }
                    }
                    int feet = 0, rims = 0, middle = 0;
                    float top = surface.tile.elevation() * BoardGeometry.level(), contact = BoardRelief.metres(1.5f);
                    for (var entry : wet.entrySet()) {
                        Vector3 point = entry.getKey();
                        var a = entry.getValue();
                        var b = dry.get(point);
                        if (b == null) { continue; }
                        String label = coords + ", " + lod + " at " + point;
                        assertTrue(a.normal().epsilonEquals(b.normal(), .00001f), "Shared slope normal: " + label);
                        assertEquals(a.kind(), b.kind(), label);
                        assertEquals(a.level(), b.level(), .00001f, "Shared rock amount: " + label);
                        assertEquals(a.rim(), b.rim(), .00001f, "Shared foot distance: " + label);
                        assertEquals(a.foot(), b.foot(), .00001f, "Shared rim distance: " + label);
                        assertEquals(a.tint(), b.tint(), .00001f, "Shared material tint: " + label);
                        if (point.z > 0 && point.z < contact) { feet++; }
                        else if (point.z < top && point.z > top - contact) { rims++; }
                        else if (point.z >= contact && point.z <= top - contact) { middle++; }
                    }
                    assertTrue(feet > 0 && rims > 0 && middle > 0,
                          "Exercise both contact bands and the steep middle at " + coords + ", " + lod);
                }
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void bentChannelsTriangulateTheirActualOutlineAndMeetTheNeighboringMouths() {
        Coords center = new Coords(2, 2);
        for (int from = 0; from < 6; from++) {
            for (int separation : new int[] { 2, 3, 4 }) {
                int to = (from + separation) % 6;
                BoardScene scene = BoardScenes.riverScene(Map.of(center, 0, center.translated(from), 0,
                      center.translated(to), 0), false);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                assertEquals(-BoardGeometry.LEVEL, surface.height(BoardGeometry.centerX(center), BoardGeometry.centerY(center)), 0.01f,
                      "A unit at the centre of a bend must stand on its full-depth bed");
                float polygonArea = 0, trianglesArea = 0;
                for (int i = 0; i < surface.water.size(); i++) {
                    Vector3 a = surface.water.get(i), b = surface.water.get((i + 1) % surface.water.size());
                    polygonArea += a.x * b.y - a.y * b.x;
                }
                for (BoardSurface.Face face : surface.waterFaces) {
                    float area = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                    assertTrue(area >= -0.01f, "No inverted water triangles");
                    trianglesArea += area;
                }
                assertEquals(polygonArea, trianglesArea, 0.1f, "Water must not fan across the curved banks");
                for (int direction : new int[] { from, to }) {
                    BoardSurface neighbor = new BoardSurface(scene, scene.tile(center.translated(direction)));
                    int edge = Math.floorMod(1 - direction, 6);
                    Vector3 a = BoardGeometry.corner(center, 0, edge), b = BoardGeometry.corner(center, 0, edge + 1);
                    var mouth = surface.water.stream().filter(p -> onEdge(p, a, b)).toList();
                    assertTrue(mouth.size() >= 2);
                    assertTrue(mouth.stream().allMatch(p -> neighbor.water.stream().anyMatch(n -> n.epsilonEquals(p, 0.01f))));
                }
            }
        }
    }

    @Test
    void waterAndBanksStayInsideTheirHexForEveryNeighborPattern() {
        Coords center = new Coords(7, 33);
        for (int mask = 0; mask < 64; mask++) {
            BoardScene scene = BoardScenes.riverScene(center, mask);
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
            faces.addAll(surface.waterFaces);
            for (BoardSurface.Face face : faces) {
                for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(center, 0, edge);
                        Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                        Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
                        float outside = new Vector3(point).sub(a).dot(outward);
                        // Only a fall's crest, rounding the prow between two falls, bows out over the pool below.
                        boolean falls = (mask & 1 << BoardGeometry.edgeDirection(edge)) != 0;
                        float allowed = falls ? BoardSurface.VALLEY : 0.01f;
                        assertTrue(outside <= allowed * BoardGeometry.HEX_SCALE,
                              "Water neighbor mask " + mask + ", " + face.finish() + " outside edge " + edge + ": " + point);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = { 0.5f, 1, 2 })
    void dryRiverRimsRetainTheirFullHeightWhileBanksGradeTowardsWater(float scale) {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, 0.6f, 0.87f, 18, 0.8f));
            Coords center = new Coords(7, 33);
            int graded = 0;
            for (int mask = 0; mask < 64; mask++) {
                BoardScene scene = BoardScenes.riverScene(center, mask);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                float top = scene.tile(center).elevation() * BoardGeometry.LEVEL;
                for (int edge = 0; edge < 6; edge++) {
                    if ((mask & (1 << BoardGeometry.edgeDirection(edge))) != 0) {
                        continue;
                    }
                    Vector3 a = BoardGeometry.corner(center, 0, edge);
                    Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                    Vector3 middle = new Vector3(a).lerp(b, .5f);
                    Vector3 inward = BoardGeometry.center(center, 0).sub(middle).nor();
                    // Just inside the bank's rim, which rounds its corners as every cliff's does.
                    for (int sample = 20; sample <= 80; sample++) {
                        Vector3 point = new Vector3(a).lerp(b, sample / 100f).mulAdd(inward, .015f * BoardGeometry.WIDTH);
                        float height = surface.height(point.x, point.y);
                        assertTrue(height <= top + .01f * scale
                                    && height >= surface.waterHeight(point.x, point.y) - .01f * scale,
                              "Water neighbor mask " + mask + ", dry edge " + edge + ", sample " + sample
                                    + " must grade between its rim and water: " + height);
                        if (height < top - .01f * scale) { graded++; }
                    }
                }
                for (BoardSurface.Side side : surface.sides(scene, BoardGeometry.floor(scene))) {
                    if ((mask & (1 << BoardGeometry.edgeDirection(side.edge()))) == 0) {
                        assertEquals(top, side.a().z, 0.01f * scale, "The cliff must close beneath the dry bank");
                        assertEquals(top, side.b().z, 0.01f * scale, "The cliff must close beneath the dry bank");
                    }
                }
            }
            assertTrue(graded > 0, "The banks must begin grading before reaching the waterline");
        } finally {
            BoardGeometry.tune(original);
        }
    }
}
