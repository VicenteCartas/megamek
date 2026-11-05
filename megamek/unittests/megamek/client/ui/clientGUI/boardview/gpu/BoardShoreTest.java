/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardShoreTest {
    @Test
    void waterAboveLandKeepsItsOutlineOnTheEdge() {
        // A pond raised above the land round it, frozen or not: the land takes the whole step on its own side, so the
        // pond's bank stands at its own height right up to the hex edge (rim rocks may stand on it) and its ice rests
        // on it.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            for (int[] pond : new int[][] { { 1, 0 }, { 1, 1 }, { 2, 1 }, { 3, 1 }, { 3, 3 } }) {
                for (boolean frozen : List.of(false, true)) {
                    BoardScene scene = BoardScenes.sandScene(7, 0, Map.of(center, pond[0]), Map.of(center, pond[1]), frozen);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(center, 0, edge);
                        Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                        Vector3 inward = BoardGeometry.center(center, 0).sub(new Vector3(a).lerp(b, .5f)).nor();
                        for (int sample = 30; sample <= 70; sample += 10) {
                            Vector3 point = new Vector3(a).lerp(b, sample / 100f)
                                  .mulAdd(inward, BoardGeometry.HEX_SCALE);
                            assertTrue(surface.height(point.x, point.y) > (pond[0] - .01f) * BoardGeometry.LEVEL,
                                  "Water " + pond[0] + " deep " + pond[1] + (frozen ? " frozen" : "")
                                        + " over land 0, edge " + edge + ", sample " + sample + ": the bank dips to "
                                        + surface.height(point.x, point.y));
                        }
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void waterBanksNeverFoldWhereSlopesMeetBeachesAndWalls() {
        // Ponds and rivers a level deep whose banks mix slopes up from the water (land a level up), beaches (land at
        // the water's level) and walls (land two levels up): the bank strips never turn face down.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            List<int[]> ponds = new ArrayList<>(List.of(new int[] { 1, 2, 1, 1, 1, 1 }, new int[] { 1, 1, 0, 0, 0, 0 },
                  new int[] { 1, 3, 1, 2, 1, 4 }));
            for (int edge = 0; edge < 6; edge++) {
                int[] one = new int[6];
                one[edge] = 1;
                ponds.add(one);
            }
            for (int[] land : ponds) {
                Map<Coords, Integer> levels = new HashMap<>();
                for (int edge = 0; edge < 6; edge++) {
                    levels.put(center.translated(BoardGeometry.edgeDirection(edge)), land[edge]);
                }
                assertBanksFaceUp(BoardScenes.sandScene(7, 0, levels, Map.of(center, 1), false), List.of(center),
                      "pond beside " + Arrays.toString(land));
            }
            int[][] kinds = { { 1, 0, 0, 1, 0, 0 }, { 1, 2, 1, 2, 1, 2 }, { 2, 1, 2, 1, 2, 1 } };
            for (int from = 0; from < 6; from++) {
                for (int separation : new int[] { 2, 3, 4 }) {
                    List<Coords> river = List.of(center, center.translated(from),
                          center.translated((from + separation) % 6));
                    for (int[] kind : kinds) {
                        Map<Coords, Integer> levels = new HashMap<>(), depths = new HashMap<>();
                        for (int direction = 0; direction < 6; direction++) {
                            levels.put(center.translated(direction), kind[direction]);
                        }
                        for (Coords coords : river) {
                            levels.put(coords, 0);
                            depths.put(coords, 1);
                        }
                        assertBanksFaceUp(BoardScenes.sandScene(7, 1, levels, depths, false), river, "river from " + from + " by "
                              + separation + " beside " + Arrays.toString(kind));
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void openMouthsMatchWhereTheStepsThroughTheirCornersMoveThem() {
        // Water no deeper than its surface between slopes, and water raised above the land: the steps through a
        // mouth's corners move its ends, and both hexes of the mouth still meet without a step and never fold.
        Coords center = new Coords(3, 3);
        for (Runnable tuning : tunings(true, () -> {
            for (int[] water : new int[][] { { 0, 0, 1 }, { 0, 0, 2 }, { 1, 1, 0 }, { 1, 2, 0 } }) {
                for (int from = 0; from < 6; from++) {
                    for (int separation : new int[] { 2, 3 }) {
                        List<Coords> river = List.of(center, center.translated(from),
                              center.translated((from + separation) % 6));
                        Map<Coords, Integer> levels = new HashMap<>(), depths = new HashMap<>();
                        for (Coords coords : river) {
                            levels.put(coords, water[0]);
                            depths.put(coords, water[1]);
                        }
                        BoardScene scene = BoardScenes.sandScene(7, water[2], levels, depths, false);
                        String label = "water " + water[0] + " deep " + water[1] + " by land " + water[2] + ", from "
                              + from + " by " + separation;
                        assertBanksFaceUp(scene, river, label);
                        BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                        for (int direction : new int[] { from, (from + separation) % 6 }) {
                            BoardSurface neighbor = new BoardSurface(scene, scene.tile(center.translated(direction)));
                            int edge = Math.floorMod(1 - direction, 6);
                            Vector3 a = BoardGeometry.corner(center, 0, edge);
                            Vector3 b = BoardGeometry.corner(center, 0, edge + 1);
                            for (int sample = 1; sample < 60; sample++) {
                                // Where the steps through a corner move the seam off the edge line, only one hex
                                // covers the point; where both do, they meet.
                                Vector3 point = new Vector3(a).lerp(b, sample / 60f);
                                float own = BoardSurface.sampleHeight(surface.faces, point.x, point.y,
                                      Float.NEGATIVE_INFINITY);
                                float other = BoardSurface.sampleHeight(neighbor.faces, point.x, point.y,
                                      Float.NEGATIVE_INFINITY);
                                assertTrue(Float.isInfinite(own) || Float.isInfinite(other)
                                      || Math.abs(own - other) < .01f, label + ": both hexes meet on the mouth, sample "
                                            + sample + ": " + own + " against " + other);
                            }
                        }
                    }
                }
            }
        })) {
            tuning.run();
        }
    }

    @Test
    void aStraightGroupEdgeGivesAStraightShore() {
        // A lake three columns wide: its western bank runs straight down a column however the hex outline zig-zags,
        // near the midline between the water and the land.
        Map<Coords, Integer> lake = lake(0, 10);
        BoardScene scene = BoardScenes.sandScene(11, 0, Map.of(), lake, false);
        Vector3 top = BoardGeometry.center(new Coords(3, 1), 0);
        float[] west = reach(scene, lake.keySet(), top, new Vector3(0, -1, 0), new Vector3(-1, 0, 0), 8 * 72);
        float hex = amplitude(west, 72), half = amplitude(west, 36);
        assertTrue(hex < 2 && half < 2, "The west bank keeps to a line: " + hex + " and " + half
              + " peak to peak at the hex's period and half of it; along the hex outline it swung 16");
        // The midline runs between the columns; the bank keeps its bias (3) and slow wander (8) inside the water.
        float midline = (BoardGeometry.centerX(new Coords(3, 1)) - BoardGeometry.centerX(new Coords(2, 1))) / 2;
        float mean = 0;
        for (float value : west) { mean += (midline - value) / BoardGeometry.HEX_SCALE / west.length; }
        assertTrue(mean > -2.25f && mean < 8.25f, "The bank keeps near the midline, " + mean + " inside the water");
        // A river two hexes wide running diagonally: its bank keeps to a line too.
        Map<Coords, Integer> river = new HashMap<>();
        for (int x = 0; x < 13; x++) {
            river.put(new Coords(x, x / 2 + 1), 1);
            river.put(new Coords(x, x / 2 + 2), 1);
        }
        BoardScene diagonal = BoardScenes.sandScene(13, 0, Map.of(), river, false);
        Vector3 from = BoardGeometry.center(new Coords(2, 2), 0), to = BoardGeometry.center(new Coords(10, 6), 0);
        Vector3 along = new Vector3(to).sub(from).nor(), across = new Vector3(along.y, -along.x, 0);
        float[] bank = reach(diagonal, river.keySet(), from, along, across, from.dst(to));
        float step = from.dst(to) / 8;
        assertTrue(amplitude(bank, step) < 2, "The diagonal bank keeps to a line: " + amplitude(bank, step));
    }

    @Test
    void theWaterRunsPastTheCornerALandHexPokesInAndBothWaterHexesEndTheMouthThere() {
        // Land (2, 4) pokes its east corner in between water hexes (3, 3) and (3, 4).
        BoardScene scene = BoardScenes.sandScene(9, 0, Map.of(), lake(1, 7), false);
        Coords land = new Coords(2, 4);
        List<Coords> water = List.of(new Coords(3, 3), new Coords(3, 4));
        Vector3 corner = BoardGeometry.corner(land, 0, 0);
        Vector3 toLand = BoardGeometry.center(land, 0).sub(corner).nor();
        List<Vector3> ends = new ArrayList<>();
        for (Coords coords : water) {
            // The mouth's end near the corner: its water point on the shared edge's line nearest the land.
            Vector3 best = null;
            for (Vector3 p : new BoardSurface(scene, scene.tile(coords)).water) {
                Vector3 d = new Vector3(p).sub(corner);
                d.z = 0;
                if (Math.abs(new Vector3(d).crs(toLand).z) < .01f
                      && (best == null || d.dot(toLand) > new Vector3(best).sub(corner).dot(toLand))) {
                    best = p;
                }
            }
            assertNotNull(best, coords + " has water on the mouth's line");
            ends.add(best);
        }
        assertEquals(ends.get(0), ends.get(1), "Both water hexes end the mouth at the same point");
        float past = new Vector3(ends.getFirst()).sub(corner).dot(toLand) / BoardGeometry.HEX_SCALE;
        assertTrue(past > 1.5f && past < 12.5f, "The mouth ends " + past + " past the land's corner");
        // The land's top stands back there; the water hexes' banks and water cover it, and the land stays level.
        BoardSurface ground = new BoardSurface(scene, scene.tile(land));
        Vector3 inside = new Vector3(corner).mulAdd(toLand, 5 * BoardGeometry.HEX_SCALE);
        assertTrue(Float.isNaN(BoardSurface.sampleHeight(ground.faces, inside.x, inside.y, Float.NaN)));
        float bank = Float.NEGATIVE_INFINITY;
        for (Coords coords : water) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            bank = Math.max(bank, BoardSurface.sampleHeight(surface.faces, inside.x, inside.y, bank));
        }
        assertTrue(bank <= .001f && bank > -BoardGeometry.LEVEL, "A water hex's bank or bed lies there: " + bank);
        Vector3 anchor = BoardGeometry.center(land, 0);
        assertEquals(0, ground.height(anchor.x, anchor.y), .001f, "The land's anchor stays level");
        // Every seam around the corner closes: each edge near it belongs to exactly two faces.
        Map<List<Long>, Integer> edges = new HashMap<>();
        for (Coords coords : List.of(land, water.get(0), water.get(1))) {
            for (BoardSurface.Face face : new BoardSurface(scene, scene.tile(coords)).faces) {
                Vector3[] q = { face.a(), face.b(), face.c() };
                for (int i = 0; i < 3; i++) {
                    Vector3 p = q[i], r = q[(i + 1) % 3];
                    if (p.dst(corner.x, corner.y, p.z) > 20 * BoardGeometry.HEX_SCALE
                          || r.dst(corner.x, corner.y, r.z) > 20 * BoardGeometry.HEX_SCALE) { continue; }
                    List<Long> a = key(p), b = key(r);
                    List<Long> edge = new ArrayList<>(a.toString().compareTo(b.toString()) < 0 ? a : b);
                    edge.addAll(a.toString().compareTo(b.toString()) < 0 ? b : a);
                    edges.merge(edge, 1, Integer::sum);
                }
            }
        }
        edges.forEach((edge, count) -> assertEquals(2, count, "An edge near the corner " + edge + " is shared by " + count));
    }

    @Test
    void theShoreOverALandCornerIsPickedAndSupportedAsTheLand() {
        BoardScene scene = BoardScenes.sandScene(9, 0, Map.of(), lake(1, 7), false);
        Coords land = new Coords(2, 4);
        Vector3 center = BoardGeometry.center(land, 0);
        int checked = 0;
        for (int k = 0; k < 6; k++) {
            Vector3 corner = BoardGeometry.corner(land, 0, k);
            Vector3 point = new Vector3(corner).lerp(center, 3 * BoardGeometry.HEX_SCALE / corner.dst(center));
            float drawn = Float.NEGATIVE_INFINITY, water = Float.NEGATIVE_INFINITY, own = Float.NEGATIVE_INFINITY;
            for (int direction = -1; direction < 6; direction++) {
                Coords coords = direction < 0 ? land : land.translated(direction);
                if (scene.tile(coords) == null) { continue; }
                BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                float here = BoardSurface.sampleHeight(surface.faces, point.x, point.y, Float.NEGATIVE_INFINITY);
                drawn = Math.max(drawn, here);
                if (direction < 0) { own = here; }
                water = Math.max(water, BoardSurface.sampleHeight(surface.waterFaces, point.x, point.y,
                      Float.NEGATIVE_INFINITY));
            }
            if (Float.isFinite(own)) { continue; } // A corner the land keeps.
            Ray ray = new Ray(new Vector3(point.x, point.y, 500), new Vector3(0, 0, -1));
            assertEquals(land, BoardGeometry.pick(scene, ray), "Picked as the hex whose footprint holds it");
            assertEquals(drawn, UnitLandingSupports.ground(scene, point.x, point.y), .01f,
                  "Units stand on the ground drawn there");
            assertEquals(Math.max(drawn, water), UnitLandingSupports.surface(scene, point.x, point.y, null), .01f,
                  "Markers lie on the water drawn there");
            checked++;
        }
        assertTrue(checked > 0, "The land gives up a corner to the shore");
    }

    @Test
    void iceCoversTheCornersTheLandGivesUpToTheShore() {
        Map<Coords, Integer> lake = lake(1, 7);
        BoardScene frozen = BoardScenes.sandScene(9, 0, Map.of(), lake, true), liquid = BoardScenes.sandScene(9, 0, Map.of(), lake, false);
        for (Coords coords : lake.keySet()) {
            BoardSurface surface = new BoardSurface(frozen, frozen.tile(coords));
            for (Vector3 p : new BoardSurface(liquid, liquid.tile(coords)).outline) {
                Vector3 q = new Vector3(p).lerp(BoardGeometry.center(coords, 0), .02f);
                boolean covered = surface.faces.stream().anyMatch(face -> face.finish() == BoardSurface.Finish.ICE
                      && Float.isFinite(face.height(q.x, q.y)));
                assertTrue(covered, "Ice covers the water up to its shore at " + coords);
            }
        }
    }

    @Test
    void waterTwoHexesAwayThatMakesItsLandNeighbourAPointChangesItsCacheKey() {
        // (2, 3) is two hexes from (3, 4); as water it gives land (2, 4) a third water neighbour in a row, a point,
        // whose corner the shore of (3, 4) runs past less far.
        Coords water = new Coords(3, 4);
        Map<Coords, Integer> lake = lake(1, 7);
        BoardScene before = BoardScenes.sandScene(9, 0, Map.of(), lake, false);
        lake.put(new Coords(2, 3), 1);
        BoardScene after = BoardScenes.sandScene(9, 0, Map.of(), lake, false);
        assertNotEquals(new BoardSurface(before, before.tile(water)).outline,
              new BoardSurface(after, after.tile(water)).outline, "The shore stands back from a point's corner");
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(water)),
              BoardSurface.geometryKey(after, after.tile(water)), "So the cache must see that water");
    }

    /** Water a level deep in columns 3 to 5 of rows {@code from} to {@code to}: a lake three hexes wide. */
    private static Map<Coords, Integer> lake(int from, int to) {
        Map<Coords, Integer> lake = new HashMap<>();
        for (int x = 3; x <= 5; x++) {
            for (int y = from; y <= to; y++) { lake.put(new Coords(x, y), 1); }
        }
        return lake;
    }

    /**
     * How far toward the land along {@code across} the water of the given hexes reaches from {@code origin}, every
     * 2 units along {@code along} for {@code length}.
     */
    private static float[] reach(BoardScene scene, Iterable<Coords> water, Vector3 origin, Vector3 along,
          Vector3 across, float length) {
        List<List<Vector3>> outlines = new ArrayList<>();
        for (Coords coords : water) { outlines.add(new BoardSurface(scene, scene.tile(coords)).outline); }
        float[] result = new float[(int) (length / 2) + 1];
        for (int i = 0; i < result.length; i++) {
            float s = 2 * i;
            result[i] = Float.NaN;
            for (float a = -60; a <= 60; a += .25f) {
                float x = origin.x + along.x * s + across.x * a, y = origin.y + along.y * s + across.y * a;
                for (List<Vector3> outline : outlines) {
                    if (inside(outline, x, y)) { result[i] = a; }
                }
            }
        }
        return result;
    }

    /** Whether (x, y) lies inside the closed polygon, by the even-odd rule. */
    private static boolean inside(List<Vector3> polygon, float x, float y) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Vector3 a = polygon.get(i), b = polygon.get(j);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { in = !in; }
        }
        return in;
    }

    /** Peak-to-peak amplitude of a profile's component with the given period, sampled every 2 units. */
    private static float amplitude(float[] profile, float period) {
        double cc = 0, ss = 0, cs = 0, mean = 0, yc = 0, ys = 0;
        for (float value : profile) { mean += value / profile.length; }
        for (int i = 0; i < profile.length; i++) {
            double phase = 2 * Math.PI * 2 * i / period, co = Math.cos(phase), si = Math.sin(phase);
            cc += co * co;
            ss += si * si;
            cs += co * si;
            yc += (profile[i] - mean) * co;
            ys += (profile[i] - mean) * si;
        }
        double det = cc * ss - cs * cs, c = (yc * ss - ys * cs) / det, s = (ys * cc - yc * cs) / det;
        return (float) (2 * Math.hypot(c, s));
    }

    /** A vertex rounded to a thousandth of a unit, to match shared vertices. */
    private static List<Long> key(Vector3 p) {
        return List.of(Math.round(p.x * 1000.0), Math.round(p.y * 1000.0), Math.round(p.z * 1000.0));
    }

    /** The check with hex transitions on, and again with the tiles padded eight (and three) metres apart. */
    private static List<Runnable> tunings(boolean narrow, Runnable check) {
        List<Runnable> result = new ArrayList<>(List.of(() -> BoardScenes.withTransitions(true, check),
              () -> BoardScenes.withPadding(8, check)));
        if (narrow) { result.add(() -> BoardScenes.withPadding(3, check)); }
        return result;
    }

    /** No bank (TOP or SHORE face) of the given water hexes faces down. */
    private static void assertBanksFaceUp(BoardScene scene, List<Coords> water, String label) {
        for (Coords coords : water) {
            for (BoardSurface.Face face : new BoardSurface(scene, scene.tile(coords)).faces) {
                if (face.finish() != BoardSurface.Finish.TOP && face.finish() != BoardSurface.Finish.SHORE) {
                    continue;
                }
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertTrue(normal.z >= -.001f * normal.len(), label + ": a bank of " + coords + " faces down: " + face);
            }
        }
    }

    private static final Coords SIDE = new Coords(2, 2);

    /** Two hexes of {@code token} north and south of one side, on grass, which {@code sides} parts on the south hex. */
    private static BoardScene sides(String token, String owner, int sides) throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        Hex south = new Hex(0, token, "");
        if (sides != 0) { south.setAppearance(java.util.Map.of(owner, megamek.common.board.HexAppearance.withSeparated(null, sides))); }
        board.setHex(SIDE, south);
        board.setHex(SIDE.translated(0), new Hex(0, token, ""));
        return BoardScenes.scene(board);
    }

    private static int edgeToward(int direction) {
        for (int edge = 0; edge < 6; edge++) {
            if (BoardGeometry.edgeDirection(edge) == direction) { return edge; }
        }
        throw new AssertionError(direction);
    }

    private static Vector3 sideMiddle() {
        int edge = edgeToward(0);
        return BoardGeometry.corner(SIDE, 0, edge).lerp(BoardGeometry.corner(SIDE, 0, (edge + 1) % 6), .5f);
    }

    private static float nearest(List<BoardSurface.Face> faces, Vector3 point) {
        float nearest = Float.POSITIVE_INFINITY;
        for (var face : faces) {
            for (Vector3 corner : List.of(face.a(), face.b(), face.c())) {
                nearest = Math.min(nearest, (float) Math.hypot(corner.x - point.x, corner.y - point.y));
            }
        }
        return nearest;
    }

    private static float topArea(BoardSurface surface) {
        float area = 0;
        for (var face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.TOP) {
                area += new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).len() / 2;
            }
        }
        return area;
    }

    /** A separated side is no mouth: the water of both hexes stays off it, with land between them, and no legacy exit does this. */
    @Test void aSeparatedWaterSideIsABankWithLandAlongIt() throws Exception {
        float scale = BoardGeometry.hexScale();
        BoardScene joined = sides("water:1", "water", 0), apart = sides("water:1", "water", 1);
        assertTrue(new BoardSurface(joined, joined.tile(SIDE)).mouth(edgeToward(0)));
        assertTrue(nearest(new BoardSurface(joined, joined.tile(SIDE)).waterFaces, sideMiddle()) < 2 * scale, "Open water crosses the side");
        for (Coords hex : List.of(SIDE, SIDE.translated(0))) {
            var surface = new BoardSurface(apart, apart.tile(hex));
            assertFalse(surface.mouth(edgeToward(hex.equals(SIDE) ? 0 : 3)), hex.toString());
            assertTrue(nearest(surface.waterFaces, sideMiddle()) > 3 * scale, hex + " keeps its water off the side");
        }
        assertEquals(0, BoardLiquid.capture(new Hex(0, "water:1:0", "")).separated());
    }

    /** Where open water passes round a separated side's end, no land or wall reaches the corner: the land stops at a nose. */
    @Test void aSeparatedSidesFreeEndLeavesOpenWaterAtTheCorner() throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        Coords east = SIDE.translated(1), north = SIDE.translated(0);
        Hex south = new Hex(0, "water:1", "");
        south.setAppearance(java.util.Map.of("water", megamek.common.board.HexAppearance.withSeparated(null, 1 << 1)));
        board.setHex(SIDE, south);
        board.setHex(east, new Hex(0, "water:1", ""));
        board.setHex(north, new Hex(0, "water:1", ""));
        BoardScene scene = BoardScenes.scene(board);
        var surface = new BoardSurface(scene, scene.tile(SIDE));
        int edge = edgeToward(1);
        Vector3 a = BoardGeometry.corner(SIDE, 0, edge), b = BoardGeometry.corner(SIDE, 0, (edge + 1) % 6);
        // The free corner is the one the north hex shares.
        Vector3 shared = java.util.stream.IntStream.range(0, 6).mapToObj(k -> BoardGeometry.corner(north, 0, k))
              .anyMatch(corner -> corner.dst(a) < .01f) ? a : b;
        Vector3 other = shared == a ? b : a;
        float water = BoardGeometry.waterZ(scene.tile(SIDE)), scale = BoardGeometry.hexScale();
        for (var face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.BED) { continue; }
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.dst(shared.x, shared.y, p.z) < .1f * shared.dst(other)) {
                    assertTrue(p.z <= water + .01f * scale, "Nothing stands out of the water near the corner: " + p);
                }
            }
        }
        var relief = new BoardRelief(scene, scene.tile(SIDE), BoardSurface.ramps(scene, scene.tile(SIDE)), TerrainLod.FULL);
        Vector3 open = new Vector3(shared).lerp(other, .1f), land = new Vector3(shared).lerp(other, .5f);
        assertTrue(relief.shore(open.x, open.y, false) > 0, "Open water on the side near the corner");
        assertTrue(relief.shore(land.x, land.y, false) < 0, "Land along the rest of the side");
    }

    /** Pavement stops short of a separated side and the ground it lies on fills the rest of its hex, with no gap in the mesh. */
    @Test void separatedPavementShowsTheGroundAlongItsSide() throws Exception {
        BoardScene joined = sides("pavement:1", "pavement", 0), apart = sides("pavement:1", "pavement", 1);
        var open = new BoardSurface(joined, joined.tile(SIDE));
        var parted = new BoardSurface(apart, apart.tile(SIDE));
        assertTrue(open.faces.stream().allMatch(face -> open.family(face) == BoardScene.Surface.CONCRETE));
        var ground = parted.faces.stream().filter(face -> parted.family(face) != BoardScene.Surface.CONCRETE).toList();
        assertFalse(ground.isEmpty());
        assertTrue(ground.stream().allMatch(face -> parted.family(face) == BoardScene.Surface.GRASS));
        Vector3 middle = sideMiddle();
        assertTrue(ground.stream().anyMatch(face -> face.height(middle.x, middle.y) != Float.NEGATIVE_INFINITY), "Ground on the side itself");
        assertEquals(topArea(open), topArea(parted), .01f * topArea(open), "The strip and the slab fill the hex");
        assertFalse(BoardSurface.geometryKey(joined, joined.tile(SIDE)).equals(BoardSurface.geometryKey(apart, apart.tile(SIDE))),
              "An edit of the side rebuilds the hex");
    }
}
