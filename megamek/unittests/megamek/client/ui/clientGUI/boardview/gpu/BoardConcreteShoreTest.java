/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardConcreteShoreTest {
    /** Separated sides run straight as a concrete edge does, where the hexes alone would zigzag. */
    @Test void aSeparatedPavementSideFitsStraight() {
        assertStraightCut(11, 3, 8);
    }

    /** A cut that stops inside the pavement, whose two hands still join beyond it, runs straight to its end. */
    @Test void aCutStoppingInsideThePavementFitsStraight() {
        assertStraightCut(7, 1, 7);
    }

    /** A branch leaves a cut straight through the corner it joins, and runs straight itself to the cut's line. */
    @Test void aCutStaysStraightThroughABranch() {
        var board = cutBoard(11);
        int southEast = 1 << 2, south = 1 << 3;
        board.getHex(4, 5).setAppearance(java.util.Map.of("pavement", megamek.common.board.HexAppearance.withSeparated(null, southEast | south)));
        var fit = new megamek.common.board.ConcreteFit(12, 10, megamek.common.board.ConcreteFit.cells(board), 1, true, java.util.Set.of());
        assertStraight(fit, 3, 8);
        int first = 0, second = 0;
        while (megamek.common.board.HexTile.edgeDirection(first) != 2) { first++; }
        while (megamek.common.board.HexTile.edgeDirection(second) != 3) { second++; }
        var points = new java.util.ArrayList<megamek.common.board.ConcreteFit.Point>();
        for (int k : new int[] { first, (first + 1) % 6, second, (second + 1) % 6 }) {
            var point = fit.corner(new Coords(4, 5), k);
            if (!points.contains(point)) { points.add(point); }
        }
        assertEquals(3, points.size());
        var a = points.get(0);
        var b = points.get(1);
        var c = points.get(2);
        float area = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        assertTrue(Math.abs(area) < .5f, "One straight branch: twice its triangle's area is " + area);
    }

    private static void assertStraightCut(int lastColumn, int from, int to) {
        var fit = new megamek.common.board.ConcreteFit(12, 10, megamek.common.board.ConcreteFit.cells(cutBoard(lastColumn)), 1, true, java.util.Set.of());
        assertStraight(fit, from, to);
    }

    private static megamek.common.board.Board cutBoard(int lastColumn) {
        var board = megamek.common.board.Board.createEmptyBoard(12, 10);
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 10; y++) {
                var hex = new megamek.common.Hex(0, "pavement:1", "");
                if (y == 4 && x <= lastColumn) {
                    int sides = 1 << 3 | ((x & 1) == 1 ? 1 << 2 | 1 << 4 : 0);
                    hex.setAppearance(java.util.Map.of("pavement", megamek.common.board.HexAppearance.withSeparated(null, sides)));
                }
                board.setHex(x, y, hex);
            }
        }
        return board;
    }

    private static void assertStraight(megamek.common.board.ConcreteFit fit, int from, int to) {
        int south = 0;
        while (megamek.common.board.HexTile.edgeDirection(south) != 3) { south++; }
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (int x = from; x <= to; x++) {
            for (int k : new int[] { south, (south + 1) % 6 }) {
                float y = fit.corner(new Coords(x, 4), k).y();
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
        }
        assertTrue(high - low < .01f, "One straight side: " + low + " to " + high);
    }

    private static final Coords CENTER = new Coords(3, 3);

    @ParameterizedTest
    @ValueSource(floats = { .5f, 1, 2 })
    void footprintDistancesFollowFittedGeometryAndLeaveUnmovedHexesAlone(float scale) {
        BoardGeometry.Tuning geometry = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, geometry.unitScale(), geometry.unitHeightScale(),
                  geometry.levelHeight(), geometry.gridShade()));
            for (int direction = 0; direction < 3; direction++) {
                BoardScene scene = pavedPatch(direction);
                BoardConcrete shape = BoardConcrete.of(scene);
                int moved = 0, unmoved = 0;
                for (var tile : scene.tiles()) {
                    Coords at = tile.coords();
                    boolean fitted = shape.corners(at).stream().anyMatch(shift -> shift.x() != 0 || shift.y() != 0);
                    Vector3 center = BoardGeometry.center(at, 0);
                    if (!fitted) {
                        assertTrue(Float.isNaN(shape.distance(at, center.x, center.y)));
                        unmoved++;
                        continue;
                    }
                    moved++;
                    float[] polygon = new float[12];
                    for (int k = 0; k < 6; k++) {
                        Vector3 corner = shape.corner(at, k);
                        polygon[k * 2] = corner.x;
                        polygon[k * 2 + 1] = corner.y;
                    }
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            float x = center.x + dx * BoardGeometry.width() / 3;
                            float y = center.y + dy * BoardGeometry.height() / 3;
                            float expected = Float.POSITIVE_INFINITY;
                            for (int k = 0; k < 6; k++) {
                                int next = (k + 1) % 6;
                                expected = Math.min(expected, Intersector.distanceSegmentPoint(polygon[k * 2], polygon[k * 2 + 1],
                                      polygon[next * 2], polygon[next * 2 + 1], x, y));
                            }
                            if (Intersector.isPointInPolygon(polygon, 0, polygon.length, x, y)) { expected = -expected; }
                            assertEquals(expected, shape.distance(at, x, y), .001f,
                                  "Blending must use the exact fitted outline, including adjoining natural hexes: " + at);
                        }
                    }
                }
                assertTrue(moved > 0 && unmoved > 0);
            }
        } finally {
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void broadConcretePatchesFitFourStraightSidesInAllOrientations() {
        for (int direction = 0; direction < 3; direction++) {
            BoardScene scene = pavedPatch(direction);
            BoardConcrete shape = BoardConcrete.of(scene);
            Coords origin = new Coords(8, 8);
            Vector3 axis = BoardGeometry.center(origin.translated(direction), 0)
                  .sub(BoardGeometry.center(origin, 0)).nor();
            List<Vector3> points = new ArrayList<>();
            for (BoardScene.Tile tile : scene.tiles()) {
                if (tile.surface() != BoardScene.Surface.CONCRETE) { continue; }
                for (int e = 0; e < 6; e++) {
                    if (scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(e))).surface()
                          == BoardScene.Surface.CONCRETE) { continue; }
                    for (int k : new int[] { e, (e + 1) % 6 }) {
                        Vector3 p = BoardGeometry.corner(tile.coords(), 0, k);
                        var shift = shape.corners(tile.coords()).get(k);
                        p.add(shift.x(), shift.y(), 0);
                        points.add(new Vector3(p.x * axis.x + p.y * axis.y, -p.x * axis.y + p.y * axis.x, 0));
                    }
                }
            }
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (Vector3 p : points) {
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            }
            int corners = 0;
            for (Vector3 p : points) {
                boolean xSide = Math.min(Math.abs(p.x - minX), Math.abs(p.x - maxX)) < .003f;
                boolean ySide = Math.min(Math.abs(p.y - minY), Math.abs(p.y - maxY)) < .003f;
                assertTrue(xSide || ySide, "Every boundary point lies on the rectangle, orientation=" + direction);
                if (xSide && ySide) { corners |= 1 << ((Math.abs(p.x - minX) < .003f ? 0 : 1)
                      + (Math.abs(p.y - minY) < .003f ? 0 : 2)); }
            }
            assertEquals(15, corners, "All four square corners are present");
        }
    }

    /**
     * A placed object keeps its hex's concrete outline only where the fit moves a wall into it (b12 pin refinement). On a
     * raised patch, a car at the centre of a hex on the fitted side leaves the straight side as it is; the same car in
     * the band an inward-moved wall sweeps keeps the hex's original outline, as a structure does; a car on a wall the fit
     * moves outward, away from it, does not. On a patch level with the grass a moved edge only changes the ground
     * beneath the car, so the fit stays straight.
     */
    @Test
    void aPlacedObjectKeepsItsHexOutlineOnlyWhereTheFitMovesAWallIntoIt() {
        for (int level : new int[] { 2, 0 }) {
            BoardScene plain = pavedPatch(0, level, null, List.of());
            BoardConcrete fit = BoardConcrete.of(plain);
            var side = fittedSide(plain, fit, Move.IN);
            Coords at = side.at();
            // Inside the band the edge sweeps: halfway between the original and the fitted middle of the edge.
            Vector3 band = bandMiddle(fit, side, .5f);
            BoardConcrete onEdge = BoardConcrete.of(pavedPatch(0, level, at,
                  List.of(car(band.x / BoardGeometry.width(), band.y / BoardGeometry.height()))));
            if (level == 0) {
                assertEquals(fit.corners(at), onEdge.corners(at), "A moved edge without a wall keeps the fit: " + at);
                continue;
            }
            BoardConcrete clear = BoardConcrete.of(pavedPatch(0, level, at, List.of(car(0, 0))));
            for (var tile : plain.tiles()) {
                assertEquals(fit.corners(tile.coords()), clear.corners(tile.coords()),
                      "A car clear of the moved walls keeps the fit straight: " + tile.coords());
            }
            for (var shift : onEdge.corners(at)) {
                assertEquals(0, Math.hypot(shift.x(), shift.y()), .001f, "A car on the moved wall keeps its hex: " + at);
            }
            assertNotEquals(fit.corners(at), onEdge.corners(at));
            // Astride a wall the fit moves outward: the concrete grows away from the car, which keeps the fit.
            var out = fittedSide(plain, fit, Move.OUT);
            Vector3 edge = bandMiddle(fit, out, 0);
            BoardConcrete astride = BoardConcrete.of(pavedPatch(0, level, out.at(),
                  List.of(car(edge.x / BoardGeometry.width(), edge.y / BoardGeometry.height()))));
            for (var tile : plain.tiles()) {
                assertEquals(fit.corners(tile.coords()), astride.corners(tile.coords()),
                      "A wall moved away from the car keeps the fit: " + tile.coords());
            }
        }
    }

    /**
     * Only a cut of at least 4 px² inside the object's own hex counts: a small square in the band an inward-moved wall
     * sweeps measures its own area and pins from 4 px²; where the fitted edge crosses the original one, a square on the
     * half moved outward is not cut, one in the half moved inward is.
     */
    @Test
    void theCutCountsTheSweptAreaInsideTheObjectsOwnHexFromFourSquarePixels() {
        BoardScene plain = pavedPatch(0, 2, null, List.of());
        BoardConcrete shape = BoardConcrete.of(plain);
        var fit = shape.fit();
        var in = fittedSide(plain, shape, Move.IN);
        Vector3 band = bandMiddle(shape, in, .5f).add(BoardGeometry.center(in.at(), 0));
        assertEquals(1.5f, fit.cut(square(band, 1.5f), in.at(), in.edge()), .01f);
        assertFalse(fit.reaches(square(band, 1.5f), in.at()), "A touch of 1.5 px² does not pin");
        assertTrue(fit.reaches(square(band, 6), in.at()), "6 px² in the band pins");
        var cross = fittedSide(plain, shape, Move.CROSS);
        Coords at = cross.at();
        int k = cross.edge();
        Vector3 a = BoardGeometry.corner(at, 0, k), b = BoardGeometry.corner(at, 0, (k + 1) % 6);
        boolean firstIn = side(a, b, shape.corner(at, k)) > 0;
        Vector3 inner = new Vector3(firstIn ? a : b).lerp(firstIn ? b : a, .1f);
        Vector3 outer = new Vector3(firstIn ? b : a).lerp(firstIn ? a : b, .1f);
        Vector3 inward = BoardGeometry.center(at, 0).sub(a.cpy().lerp(b, .5f)).nor();
        assertEquals(0, fit.cut(square(outer.mulAdd(inward, 1.5f), 2), at, k), .001f, "The outward half cuts nothing");
        assertTrue(fit.cut(square(inner.mulAdd(inward, 1.5f), 2), at, k) > 0, "The inward half cuts");
    }

    /**
     * Edges Fitted: the car on the inward-moved wall leaves its hex fitted, and the fit lists it as cut. Edges Hex: an
     * empty hex there keeps its outline while its neighbours stay fitted, and nothing is cut.
     */
    @Test
    void fittedEdgesIgnoreTheCarAndListItAndHexEdgesKeepTheOutline() {
        BoardScene plain = pavedPatch(0, 2, null, List.of());
        BoardConcrete fit = BoardConcrete.of(plain);
        var side = fittedSide(plain, fit, Move.IN);
        Coords at = side.at();
        Vector3 band = bandMiddle(fit, side, .5f);
        var car = List.of(car(band.x / BoardGeometry.width(), band.y / BoardGeometry.height()));
        BoardConcrete fitted = BoardConcrete.of(pavedPatch(0, 2, at, car, HexAppearance.Edges.FITTED));
        for (var tile : plain.tiles()) {
            assertEquals(fit.corners(tile.coords()), fitted.corners(tile.coords()), "Fitted ignores the car: " + tile.coords());
        }
        assertEquals(List.of(new BoardConcrete.Cut(at, "car", "scenery/vehicles/car")), fitted.cuts());
        assertTrue(fit.cuts().isEmpty());
        BoardConcrete hex = BoardConcrete.of(pavedPatch(0, 2, at, List.of(), HexAppearance.Edges.HEX));
        for (var shift : hex.corners(at)) {
            assertEquals(0, Math.hypot(shift.x(), shift.y()), .001f, "Hex keeps the hex's outline: " + at);
        }
        assertTrue(plain.tiles().stream().anyMatch(tile -> tile.coords().distance(at) == 1
              && hex.corners(tile.coords()).stream().anyMatch(s -> s.x() != 0 || s.y() != 0)), "Its neighbours stay fitted");
        assertTrue(hex.cuts().isEmpty());
    }

    /**
     * The legacy conversion's fit, read from the hexes alone ({@link megamek.common.board.ConcreteFit#cells}), is the 3D
     * board's fit of the captured scene: a paved quay with a pier over water, a road ramp onto the quay and a bridge.
     */
    @Test
    void theConversionsFitFromTheHexesIsTheBoardsFit() {
        var board = megamek.common.board.Board.createEmptyBoard(10, 8);
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 8; y++) {
                boolean quay = y <= 2 || x == 4 && y <= 5;
                board.setHex(new Coords(x, y), new megamek.common.Hex(0, quay ? "pavement:1" : "water:1", ""));
            }
        }
        board.setHex(new Coords(8, 0), new megamek.common.Hex(2, "road:1:09", ""));
        board.setHex(new Coords(8, 1), new megamek.common.Hex(0, "pavement:1;road:1:09", ""));
        board.setHex(new Coords(1, 3), new megamek.common.Hex(0, "water:1;bridge:1:09;bridge_cf:40;bridge_elev:1", ""));
        var scene = new BoardScene(0, 10, 8, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of());
        var client = BoardConcrete.of(scene);
        var common = new megamek.common.board.ConcreteFit(10, 8, megamek.common.board.ConcreteFit.cells(board), 1, true, Set.of());
        int moved = 0;
        for (var tile : scene.tiles()) {
            assertEquals(client.corners(tile.coords()), common.corners(tile.coords()), tile.coords().toString());
            moved += client.corners(tile.coords()).stream().anyMatch(shift -> shift.x() != 0 || shift.y() != 0) ? 1 : 0;
        }
        assertTrue(moved > 10, "The quay and the pier are fitted: " + moved);
    }

    /**
     * A container yard's rows are objects of its hex ({@link BoardFeatures#decorations}), so the one rule pins it: where a
     * fitted wall moves into its containers, its hex keeps its outline; plain light woods there take the fit.
     */
    @Test
    void aContainerYardKeepsItsHexOutlineWhereAMovedWallReachesItsContainers() {
        BoardScene plain = pavedPatch(0, 2, null, List.of());
        BoardConcrete fit = BoardConcrete.of(plain);
        Coords at = fittedSide(plain, fit, Move.IN).at();
        var yard = new megamek.common.Hex(0, "pavement:1;woods:1;foliage_elev:2", "");
        yard.setAppearance(java.util.Map.of("vegetation",
              new megamek.common.board.HexAppearance("woods/container-yard/rows", null, null, null)));
        var woods = BoardFeatures.capture(new megamek.common.Hex(0, "pavement:1;woods:1;foliage_elev:2", ""), at, java.util.Map.of());
        assertTrue(woods.stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
        assertEquals(fit.corners(at), BoardConcrete.of(pavedPatch(0, 2, at, woods)).corners(at), "Woods take the fit");
        BoardConcrete pinned = BoardConcrete.of(pavedPatch(0, 2, at, BoardFeatures.decorations(yard, at)));
        for (var shift : pinned.corners(at)) {
            assertEquals(0, Math.hypot(shift.x(), shift.y()), .001f, "The yard keeps its hex: " + at);
        }
        assertNotEquals(fit.corners(at), pinned.corners(at));
    }

    /** A hex on the patch's fitted side and one of its edges toward the grass that the fit moves. */
    private record Side(Coords at, int edge) { }

    /** How the fit moves an edge: both corners into the hex, both out of it, or one each way (the edges cross). */
    private enum Move { IN, OUT, CROSS }

    private static Side fittedSide(BoardScene plain, BoardConcrete fit, Move move) {
        for (var tile : plain.tiles()) {
            for (int k = 0; k < 6 && tile.surface() == BoardScene.Surface.CONCRETE; k++) {
                var across = plain.tile(tile.coords().translated(BoardGeometry.edgeDirection(k)));
                if (across.surface() == BoardScene.Surface.CONCRETE) { continue; }
                Vector3 a = BoardGeometry.corner(tile.coords(), 0, k), b = BoardGeometry.corner(tile.coords(), 0, (k + 1) % 6);
                float first = side(a, b, fit.corner(tile.coords(), k)), second = side(a, b, fit.corner(tile.coords(), (k + 1) % 6));
                Move found = first > .01f && second > .01f ? Move.IN : first < -.01f && second < -.01f ? Move.OUT
                      : first * second < 0 ? Move.CROSS : null;
                if (found == move) { return new Side(tile.coords(), k); }
            }
        }
        throw new AssertionError("The patch has no fitted side moved " + move);
    }

    /** Positive where p lies inside the hex, left of its counter-clockwise edge a-b. */
    private static float side(Vector3 a, Vector3 b, Vector3 p) {
        return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
    }

    /** The point {@code t} of the way from the edge's original middle to its fitted middle, from the hex centre. */
    private static Vector3 bandMiddle(BoardConcrete fit, Side side, float t) {
        Coords at = side.at();
        int edge = side.edge();
        Vector3 middle = BoardGeometry.corner(at, 0, edge).add(BoardGeometry.corner(at, 0, (edge + 1) % 6)).scl(.5f);
        Vector3 fitted = fit.corner(at, edge).add(fit.corner(at, (edge + 1) % 6)).scl(.5f);
        return middle.lerp(fitted, t).sub(BoardGeometry.center(at, 0));
    }

    /** An axis-aligned square footprint of {@code area} px² centred on {@code c}, counter-clockwise. */
    private static List<megamek.common.board.ConcreteFit.Point> square(Vector3 c, float area) {
        float h = (float) Math.sqrt(area) / 2;
        return List.of(new megamek.common.board.ConcreteFit.Point(c.x - h, c.y - h),
              new megamek.common.board.ConcreteFit.Point(c.x + h, c.y - h),
              new megamek.common.board.ConcreteFit.Point(c.x + h, c.y + h),
              new megamek.common.board.ConcreteFit.Point(c.x - h, c.y + h));
    }

    /** A placed car at (x, y) hex widths/heights from its hex centre, as the scene captures a .board2 object. */
    private static BoardScene.Feature car(double x, double y) {
        var object = new BoardDecoration("car", "prop", "scenery/vehicles/car", null, x, y, 30, false, 1,
              BoardDecoration.Placement.ground(), 0);
        return new BoardScene.Feature(object.asset(), (float) (x * BoardGeometry.TILE_WIDTH),
              (float) (y * BoardGeometry.TILE_HEIGHT), 30, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
    }

    private static BoardScene pavedPatch(int direction) {
        return pavedPatch(direction, 0, null, List.of());
    }

    /** The patch's concrete stands at {@code level}; one hex may hold features, such as a placed object. */
    private static BoardScene pavedPatch(int direction, int level, Coords objectAt, List<BoardScene.Feature> features) {
        return pavedPatch(direction, level, objectAt, features, HexAppearance.Edges.AUTO);
    }

    /** The same patch whose hex {@code objectAt} has pavement {@code edges}. */
    private static BoardScene pavedPatch(int direction, int level, Coords objectAt, List<BoardScene.Feature> features,
          HexAppearance.Edges edges) {
        var appearance = edges == HexAppearance.Edges.AUTO ? Map.<String, HexAppearance>of() : Map.of("pavement", HexAppearance.of(edges));
        Coords origin = new Coords(8, 8);
        Vector3 center = BoardGeometry.center(origin, 0);
        Vector3 axis = BoardGeometry.center(origin.translated(direction), 0).sub(center).nor();
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 17; x++) {
            for (int y = 0; y < 17; y++) {
                Coords at = new Coords(x, y);
                Vector3 relative = BoardGeometry.center(at, 0).sub(center);
                boolean land = Math.abs(relative.dot(axis)) <= 280 * BoardGeometry.HEX_SCALE
                      && Math.abs(relative.x * -axis.y + relative.y * axis.x) <= 85 * BoardGeometry.HEX_SCALE;
                var surface = land ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS;
                tiles.add(new BoardScene.Tile(at, land ? level : 0, -1, false, 0, surface, null, null,
                      at.equals(objectAt) ? features : List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE,
                      BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false, 0, false, BoardSurfaceBlend.solid(surface),
                      false, null, null, at.equals(objectAt) ? appearance : Map.of(), null));
            }
        }
        return new BoardScene(0, 17, 17, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void rectanglesKeepTheirWidthWhereTheyMeetTheQuay() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        BoardConcrete coast = BoardConcrete.of(scene);
        Coords tip = new Coords(10, 2);
        for (int y = 2; y <= 5; y++) {
            Coords at = new Coords(10, y);
            for (int k = 0; k < 6; k++) {
                Vector3 p = BoardGeometry.corner(at, 0, k);
                var shift = coast.corners(at).get(k);
                p.add(shift.x(), shift.y(), 0);
                assertEquals(BoardGeometry.TILE_WIDTH / 4 * BoardGeometry.HEX_SCALE, Math.abs(p.x - BoardGeometry.centerX(tip)), .002f,
                      "Every pier vertex remains on its parallel sides, including the landing end");
            }
        }
    }

    @Test
    void straightConcreteFingersHaveParallelSidesInEveryDirection() {
        for (int direction = 0; direction < 6; direction++) {
            Set<Coords> land = new HashSet<>();
            Coords at = CENTER;
            for (int i = 0; i < 3; i++) { land.add(at); at = at.translated(direction); }
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            var decks = new HashMap<Coords, BoardSurface>();
            Vector3 a = BoardGeometry.center(CENTER, 0);
            Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(a).nor();
            int clipped = 0;
            Set<Coords> waters = new HashSet<>();
            for (Coords coords : land) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                assertStraightSamples(surface, coords);
                decks.put(coords, surface);
                for (int k = 0; k < 6; k++) {
                    Vector3 corner = BoardGeometry.corner(coords, 0, k);
                    float[] shift = surface.relief.shoreShift(k);
                    if (Math.hypot(shift[0], shift[1]) > 10 * BoardGeometry.HEX_SCALE) { clipped++; }
                    corner.add(shift[0], shift[1], 0);
                    float across = (corner.x - a.x) * -axis.y + (corner.y - a.y) * axis.x;
                    assertTrue(Math.abs(across) <= (BoardGeometry.TILE_WIDTH / 4 + .01f) * BoardGeometry.HEX_SCALE,
                          "A dock follows its whole chain, direction=" + direction);
                }
                Vector3 center = BoardGeometry.center(coords, 0);
                for (int angle = 0; angle < 12; angle++) {
                    float x = center.x + 12 * BoardGeometry.HEX_SCALE * (float) Math.cos(angle * Math.PI / 6);
                    float y = center.y + 12 * BoardGeometry.HEX_SCALE * (float) Math.sin(angle * Math.PI / 6);
                    assertEquals(0, BoardSurface.sampleHeight(surface.faces, x, y, Float.NaN), .001f,
                          "A land unit retains solid support on a dock");
                }
                for (int d = 0; d < 6; d++) {
                    Coords next = coords.translated(d);
                    if (!land.contains(next)) { waters.add(next); }
                }
            }
            assertEquals(6, clipped, "Only the two side tips of each dock hex need clipping");
            for (Coords coords : waters) {
                BoardSurface water = new BoardSurface(scene, scene.tile(coords));
                assertWaterCentre(water, coords);
                assertUnfolded(water, "Dock water in direction " + direction);
                // The water's canonical boundary must share every moved land corner exactly.
                for (Coords dry : land) {
                    BoardSurface deck = decks.get(dry);
                    for (int k = 0; k < 6; k++) {
                        Vector3 corner = BoardGeometry.corner(dry, 0, k);
                        for (int w = 0; w < 6; w++) {
                            if (corner.dst2(BoardGeometry.corner(coords, 0, w)) < .0001f) {
                                assertArrayEquals(deck.relief.shoreShift(k), water.relief.shoreShift(w), .001f,
                                      "Deck and adjoining water agree on the moved corner");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void isolatedConcreteAlwaysKeepsItsHexFootprint() {
        for (boolean building : new boolean[] { false, true }) {
            BoardScene scene = scene(Set.of(CENTER), BoardScene.Surface.CONCRETE, building);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            for (int k = 0; k < 6; k++) {
                assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .001f,
                      "Six water neighbours always leave a hexagonal platform");
            }
            assertStraightSamples(surface, CENTER);
            assertUnfolded(surface, "Island platform");
        }
    }

    @Test
    void dockAndQuaySidesExtendToOneLandingCorner() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        Coords pier = new Coords(5, 5), quay = new Coords(8, 9);
        BoardSurface pierSurface = new BoardSurface(scene, scene.tile(pier));
        BoardSurface quaySurface = new BoardSurface(scene, scene.tile(quay));
        Vector3 a = moved(pierSurface, pier, 3), b = moved(pierSurface, pier, 4);
        Vector3 c = moved(quaySurface, quay, 3), d = moved(quaySurface, quay, 4);
        boolean joint = false;
        for (int[] vertex : new int[][] { { 7, 6, 3 }, { 7, 6, 4 }, { 7, 6, 5 }, { 8, 7, 4 },
              { 8, 8, 3 }, { 8, 8, 4 } }) {
            Coords at = new Coords(vertex[0], vertex[1]);
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            Vector3 p = moved(surface, at, vertex[2]);
            assertTrue(Math.min(distance(a, b, p), distance(c, d, p)) < .002f,
                  "The landing follows the two straight sides without an extra hex-shaped step");
            joint |= Math.max(distance(a, b, p), distance(c, d, p)) < .002f;
            assertStraightSamples(surface, at);
        }
        assertTrue(joint, "Both extended sides meet at the same corner");
    }

    @Test
    void concreteKeepsStraightEdgesBesideHigherAndLowerGround() {
        for (int elevation : new int[] { -3, -1, 1, 3 }) {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    Coords at = new Coords(x, y);
                    boolean concrete = at.equals(CENTER);
                    tiles.add(new BoardScene.Tile(at, concrete ? 0 : elevation, -1, false, 0,
                          concrete ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, null, null, List.of(),
                          List.of(), BoardLiquid.NONE, null, true));
                }
            }
            BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            for (int e = 0; e < 6; e++) {
                Vector3 a = surface.relief.seam(e, e, 0), b = surface.relief.seam(e, e, 1);
                for (int i = 1; i < 12; i++) {
                    assertEquals(0, distance(a, b, surface.relief.seam(e, e, i / 12f)), .002f,
                          "Straight edge " + e + " beside level " + elevation);
                }
            }
        }
    }

    @Test
    void concreteLevelWallsFitBetweenProtectedRoadOpeningsUnlessHexEdgesAreChosen() {
        for (var panel : RoadGradeFixture.Panel.values()) {
            var board = RoadGradeFixture.focused(true, panel, "x-mixed", 0, 0);
            Coords ramp = RoadGradeFixture.focusedCenter(0).translated(1), end = ramp.translated(1);
            BoardScene scene = RoadGradeFixture.scene(board);
            BoardConcrete shape = BoardConcrete.of(scene);
            // The graded opening keeps its existing road plane. Beyond it, the exposed level
            // boundary still fits a straight wall through the terminal hex's intermediate corner.
            Vector3 a = shape.corner(end, 0), b = shape.corner(end, 2);
            assertEquals(0, distance(a, b, shape.corner(end, 1)), .003f, panel + " exposed wall run");
            assertTrue(shape.corners(end).stream().anyMatch(shift -> shift.x() != 0 || shift.y() != 0),
                  "Protecting the road opening must not disable fitting of its exposed walls");
            assertFalse(shape.fit().pinned().contains(ramp), "The track's graded mouth is not a retaining wall");

            var appearance = new HashMap<>(board.getHex(ramp).getAppearance());
            appearance.put("pavement", HexAppearance.EDGES_HEX);
            board.getHex(ramp).setAppearance(appearance);
            BoardConcrete hex = BoardConcrete.of(RoadGradeFixture.scene(board));
            for (var shift : hex.corners(ramp)) {
                assertEquals(0, Math.hypot(shift.x(), shift.y()), .001f, panel + " explicit Hex outline");
            }
            assertTrue(hex.corners(end).stream().anyMatch(shift -> shift.x() != 0 || shift.y() != 0),
                  "The manual outline override must not turn off fitting for the whole concrete plateau");
        }
    }

    @Test
    void concreteAroundAnEnclosedTerrainPatchHasThreeStraightSides() {
        BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(1);
        Set<Coords> island = new HashSet<>();
        var queue = new ArrayDeque<Coords>();
        queue.add(new Coords(8, 9));
        while (!queue.isEmpty()) {
            Coords at = queue.remove();
            if (!island.add(at)) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                assertNotNull(scene.tile(next), "The terrain island stays enclosed");
                if (scene.tile(next).surface() != BoardScene.Surface.CONCRETE && !island.contains(next)) {
                    queue.add(next);
                }
            }
        }
        var neighbors = new HashMap<Vector3, List<Vector3>>();
        for (Coords at : island) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            Vector3 center = BoardGeometry.center(at, 0);
            assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.faces, center.x, center.y, Float.NaN)),
                  "Fitting concrete leaves the enclosed terrain centres intact");
            assertUnfolded(surface, "Fitted terrain island");
            for (int edge = 0; edge < 6; edge++) {
                if (island.contains(at.translated(BoardGeometry.edgeDirection(edge)))) { continue; }
                Vector3 a = moved(surface, at, edge), b = moved(surface, at, (edge + 1) % 6);
                neighbors.computeIfAbsent(a, ignored -> new ArrayList<>()).add(b);
                neighbors.computeIfAbsent(b, ignored -> new ArrayList<>()).add(a);
            }
        }
        int turns = 0;
        for (var entry : neighbors.entrySet()) {
            assertEquals(2, entry.getValue().size(), "The island boundary is a closed loop");
            Vector3 a = new Vector3(entry.getValue().getFirst()).sub(entry.getKey()).nor();
            Vector3 b = new Vector3(entry.getValue().getLast()).sub(entry.getKey()).nor();
            if (Math.abs(a.x * b.y - a.y * b.x) > .001f) { turns++; }
        }
        assertEquals(3, turns, "The triangular island has three straight sides without hex teeth");
    }

    @Test
    void broadJunctionExtendsItsDiagonalToTheVerticalSide() {
        BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(1);
        Coords diagonal = new Coords(1, 1), vertical = new Coords(4, 1);
        BoardSurface arm = new BoardSurface(scene, scene.tile(diagonal));
        BoardSurface trunk = new BoardSurface(scene, scene.tile(vertical));
        Vector3 a = moved(arm, diagonal, 1), b = moved(arm, diagonal, 2);
        Vector3 c = moved(trunk, vertical, 3), d = moved(trunk, vertical, 4);
        for (int[] vertex : new int[][] { { 2, 2, 0 }, { 2, 2, 1 }, { 2, 2, 2 }, { 3, 2, 1 }, { 4, 2, 2 } }) {
            Coords at = new Coords(vertex[0], vertex[1]);
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            Vector3 p = moved(surface, at, vertex[2]);
            assertTrue(Math.min(distance(a, b, p), distance(c, d, p)) < .002f,
                  "No extra flat section between the diagonal and vertical sides: " + at + " " + p);
            assertStraightSamples(surface, at);
        }
    }

    @Test
    void longDiagonalOnAeroBaseTwoHasNoHexSteps() {
        BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(2);
        Coords first = new Coords(4, 11), last = new Coords(10, 14);
        Vector3 a = moved(new BoardSurface(scene, scene.tile(first)), first, 2);
        Vector3 b = moved(new BoardSurface(scene, scene.tile(last)), last, 0);
        for (int x = 4; x <= 10; x += 2) {
            Coords at = new Coords(x, 9 + x / 2);
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            for (int k = 0; k <= 2; k++) {
                assertEquals(0, distance(a, b, moved(surface, at, k)), .003f,
                      "The inner diagonal of the runway follows one straight line: " + at);
            }
            assertStraightSamples(surface, at);
            assertUnfolded(surface, "Diagonal runway");
        }
    }

    @Test
    void fittedAeroBasesKeepTerrainCentresAndUnfoldedMeshes() {
        for (int number : new int[] { 1, 2 }) {
            BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(number);
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                assertUnfolded(surface, "AeroBase " + number + " " + tile.coords());
                Vector3 center = BoardGeometry.center(tile.coords(), 0);
                for (int direction = 0; direction < 12; direction++) {
                    float x = center.x + 12 * BoardGeometry.HEX_SCALE * (float) Math.cos(direction * Math.PI / 6);
                    float y = center.y + 12 * BoardGeometry.HEX_SCALE * (float) Math.sin(direction * Math.PI / 6);
                    assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.faces, x, y, Float.NaN)),
                          "Each original terrain keeps room at its centre: " + tile.coords());
                }
            }
        }
    }

    @Test
    void angledConnectionsKeepTheParallelSidesOfBothArms() {
        for (int first = 0; first < 6; first++) {
            for (int gap : new int[] { 1, 2 }) {
                int second = (first + gap) % 6;
                Set<Coords> land = Set.of(CENTER, CENTER.translated(first), CENTER.translated(second));
                BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
                BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                assertStraightSamples(surface, CENTER);
                Vector3 center = BoardGeometry.center(CENTER, 0);
                for (int e = 0; e < 6; e++) {
                    if (land.contains(CENTER.translated(BoardGeometry.edgeDirection(e)))) { continue; }
                    Vector3 a = moved(surface, CENTER, e), b = moved(surface, CENTER, (e + 1) % 6);
                    boolean parallel = false;
                    for (int direction : new int[] { first, second }) {
                        Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(center).nor();
                        float ca = (a.x - center.x) * -axis.y + (a.y - center.y) * axis.x;
                        float cb = (b.x - center.x) * -axis.y + (b.y - center.y) * axis.x;
                        parallel |= Math.abs(ca - cb) < .002f
                              && Math.abs(Math.abs(ca) / BoardGeometry.HEX_SCALE - BoardGeometry.TILE_WIDTH / 4) < .2f;
                    }
                    assertTrue(parallel, "Each angled bank follows a rectangular arm: " + first + ", " + second + ", edge " + e);
                }
                assertUnfolded(surface, "Angled dock");
                for (int d = 0; d < 6; d++) {
                    Coords at = CENTER.translated(d);
                    if (land.contains(at)) { continue; }
                    BoardSurface water = new BoardSurface(scene, scene.tile(at));
                    assertWaterCentre(water, at);
                    assertUnfolded(water, "Water beside an angled dock");
                }
            }
        }
    }

    private static Vector3 moved(BoardSurface surface, Coords coords, int k) {
        float[] shift = surface.relief.shoreShift(k);
        return BoardGeometry.corner(coords, 0, k).add(shift[0], shift[1], 0);
    }

    private static void assertStraightSamples(BoardSurface surface, Coords coords) {
        for (int e = 0; e < 6; e++) {
            Vector3 a = moved(surface, coords, e), b = moved(surface, coords, (e + 1) % 6);
            for (int s = 0; s <= 12; s++) {
                Vector3 expected = new Vector3(a).lerp(b, s / 12f);
                Vector3 actual = surface.relief.seam(e, e, s / 12f);
                assertEquals(expected.x, actual.x, .001f, "Every rendered concrete edge sample lies on its straight side");
                assertEquals(expected.y, actual.y, .001f);
            }
        }
    }

    @Test
    void neighboringConcreteBanksFollowTheSameFittedEdgeAsTheLand() {
        var original = BoardRelief.tuning();
        try {
            for (int direction = 0; direction < 6; direction++) {
                BoardScene scene = scene(Set.of(CENTER.translated(direction), CENTER.translated((direction + 1) % 6)),
                      BoardScene.Surface.CONCRETE, false);
                BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                int edge = Math.floorMod(-direction, 6), n = BoardSurface.SHORE_SEGMENTS;
                for (int e : new int[] { edge, (edge + 1) % 6 }) {
                    for (int i = 0; i < n; i++) {
                        Vector3 p = surface.outline.get(e * n + i), expected = surface.relief.seam(e, e, i / (float) n);
                        assertEquals(expected.x, p.x, .002f, "Shared concrete shoreline, direction=" + direction);
                        assertEquals(expected.y, p.y, .002f);
                    }
                }
                assertWaterCentre(surface);
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void concreteQuayWallsContinueVerticallyToTheBedBesideBuildingsAtEveryDetail() {
        BoardScene scene = GpuRiverTerrainSmokeTest.quayScene(BoardScene.Surface.CONCRETE);
        for (TerrainLod lod : TerrainLod.values()) {
            for (int y = 4; y <= 7; y++) {
                BoardSurface water = new BoardSurface(scene, scene.tile(new Coords(3, y)), lod);
                int walls = 0;
                for (var face : water.faces) {
                    if (face.landEdge() < 0 || !BoardConcrete.concreteBank(scene, water.tile, face.landEdge())
                          || face.finish() != BoardSurface.Finish.WALL) { continue; }
                    walls++;
                    int e = face.landEdge();
                    Vector3 a = water.relief.seam(e, e, 0), b = water.relief.seam(e, e, 1);
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertEquals(0, distance(a, b, p), .002f, "The submerged wall stays on the quay's edge: " + lod);
                    }
                }
                assertTrue(walls > 0, "Concrete owns a full underwater wall");
                assertUnfolded(water, "Quay bed " + lod);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = { .5f, 1, 2 })
    void naturalBanksStayVisibleWhenTheyTaperIntoAFixedQuay(float scale) {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            var defaults = BoardGeometry.DEFAULTS;
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, defaults.unitScale(), defaults.unitHeightScale(),
                  defaults.levelHeight(), defaults.gridShade()));
            BoardRelief.tune(BoardRelief.DEFAULTS);
            BoardScene scene = BoardRiverTest.commCenterRiverScene();
            Coords at = new Coords(4, 4);
            BoardSurface water = new BoardSurface(scene, scene.tile(at));
            BoardSurface grass = new BoardSurface(scene, scene.tile(new Coords(5, 3)));
            Vector3 center = BoardGeometry.center(at, 0);
            int count = BoardSurface.SHORE_SEGMENTS;
            // Edge 0 is level natural ground, ending at the raised concrete quay on edge 1.
            for (int i = 1; i < count; i++) {
                Vector3 point = water.outline.get(i);
                assertTrue(Float.isNaN(BoardSurface.sampleHeight(grass.faces, point.x, point.y, Float.NaN)),
                      "The natural waterline stays inside its actual relief boundary, clear of the grass top");
                Vector3 outward = new Vector3(point.x - center.x, point.y - center.y, 0).nor();
                Vector3 bank = new Vector3(point).mulAdd(outward, .2f * scale);
                assertTrue(Float.isNaN(BoardSurface.sampleHeight(grass.faces, bank.x, bank.y, Float.NaN)),
                      "A visible strip of natural bank remains before the neighbouring grass");
                float height = BoardSurface.sampleHeight(water.faces, bank.x, bank.y, Float.NaN);
                assertTrue(height > point.z && height < 0,
                      "The exposed bank rises from the water before meeting the fixed quay endpoint");
            }
            Vector3 a = water.relief.seam(1, 1, 0), b = water.relief.seam(1, 1, 1);
            int walls = 0;
            for (var face : water.faces) {
                if (face.finish() != BoardSurface.Finish.WALL || face.landEdge() != 1) { continue; }
                walls++;
                for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                    assertEquals(0, distance(a, b, point), .002f * scale,
                          "Tapering the natural bank keeps the adjacent quay wall vertical");
                }
            }
            assertTrue(walls > 0, "The concrete quay keeps its submerged wall");
            assertUnfolded(water, "Natural bank beside a fixed quay");
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void concreteBanksBesideDescendingWaterKeepFiniteContactGeometry() {
        for (int direction = 0; direction < 6; direction++) {
            Coords upstream = CENTER.translated(direction);
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    Coords at = new Coords(x, y);
                    boolean water = at.equals(CENTER) || at.equals(upstream);
                    tiles.add(new BoardScene.Tile(at, at.equals(upstream) ? 1 : 0, water ? 2 : -1, false, 0,
                          water ? BoardScene.Surface.GRASS : BoardScene.Surface.CONCRETE, null, null, List.of(),
                          List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE,
                          null, true));
                }
            }
            BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            for (var face : surface.waterFaces) {
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.z),
                          "The stream remains a valid surface at the level quay, direction=" + direction);
                }
            }
            assertWaterCentre(surface);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 2 })
    void aeroBaseRunwayContinuesAcrossTheNarrowConnectorToTheTrunk(int elevation) {
        BoardScene scene = aeroBaseAtElevation(elevation);
        BoardConcrete shape = BoardConcrete.of(scene);
        Vector3 a = shape.corner(new Coords(9, 5), 0), b = shape.corner(new Coords(10, 6), 0);
        Coords join = new Coords(6, 4);
        assertEquals(0, distance(a, b, shape.corner(join, 1)), .003f,
              "The connector meets the continued runway edge");
        assertEquals(0, distance(a, b, shape.corner(join, 2)), .003f,
              "The runway reaches the trunk without a triangular notch");
        // Where the continued runway edge reaches the trunk's vertical side, a fitted corner joins the two.
        float x = shape.corner(new Coords(5, 1), 0).x, y = a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x);
        boolean meets = false;
        for (Coords at : join.allAtDistanceOrLess(1)) {
            for (int k = 0; k < 6; k++) { meets |= Math.hypot(shape.corner(at, k).x - x, shape.corner(at, k).y - y) < .003f; }
        }
        assertTrue(meets, "The continued runway meets the trunk's vertical side");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 2 })
    void aeroBaseTaxiwayKeepsItsDiagonalSidePastTheJunctionUpToTheMapEdge(int elevation) {
        BoardScene scene = aeroBaseAtElevation(elevation);
        BoardConcrete shape = BoardConcrete.of(scene);
        Coords arm = new Coords(12, 3);
        Vector3 a = shape.corner(arm, 3), b = shape.corner(arm, 4);
        for (int[] vertex : new int[][] { { 13, 4, 0 }, { 14, 4, 4 }, { 14, 4, 5 }, { 15, 4, 4 } }) {
            Vector3 p = shape.corner(new Coords(vertex[0], vertex[1]), vertex[2]);
            assertEquals(0, distance(a, b, p), .003f,
                  "No extra concrete apron below the diagonal taxiway: " + p);
        }
        // Its corner on the map's edge keeps its place, so the board keeps its hex outline.
        Coords edge = new Coords(15, 4);
        assertEquals(0, shape.corner(edge, 5).dst(BoardGeometry.corner(edge, 0, 5)), .003f,
              "The taxiway turns back to the board's corner");
        assertEquals(shape.corner(new Coords(13, 5), 0).x, shape.corner(new Coords(13, 4), 0).x, .003f,
              "The two taxiway sides meet at one corner");
    }

    private static BoardScene aeroBaseAtElevation(int elevation) {
        BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(1);
        if (elevation == 0) { return scene; }
        return scene.withTiles(scene.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(),
              tile.surface() == BoardScene.Surface.CONCRETE ? elevation : tile.elevation(), -1, false, 0,
              tile.surface(), null, null, tile.features(), List.of(), BoardLiquid.NONE, null,
              tile.detailedGround())).toList());
    }

    @Test
    void concreteNeverClosesAnInletAcrossItsWaterCentreOrFoldsItsBed() {
        for (int mask = 0; mask < 64; mask++) {
            Set<Coords> land = new HashSet<>();
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & 1 << direction) != 0) { land.add(CENTER.translated(direction)); }
            }
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertWaterCentre(surface);
            assertUnfolded(surface, "Unfolded concrete shore, mask=" + mask);
        }
    }

    @Test
    void buildingsUsingTheConcreteEngineKeepTheirWholeFoundationAndConcreteBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, true);
        assertTrue(land.stream().allMatch(coords -> scene.tile(coords).detailedGround()));
        BoardSurface water = new BoardSurface(scene, scene.tile(CENTER));
        assertEquals(BoardScene.Surface.CONCRETE.ordinal(), water.relief.family(),
              "The building's ground and adjoining bank remain concrete");
        for (Coords coords : land) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            Vector3 center = BoardGeometry.center(coords, 0);
            for (int k = 0; k < 6; k++) {
                Vector3 corner = BoardGeometry.corner(coords, 0, k);
                assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .0001f,
                      "No recession into a concrete building hex");
                for (float radius : new float[] { 0, .25f, .5f, .75f, .99f }) {
                    Vector3 p = new Vector3(center).lerp(corner, radius);
                    assertEquals(0, BoardSurface.sampleHeight(surface.faces, p.x, p.y, Float.NaN), .001f,
                          "The complete foundation stays at its original level");
                }
            }
        }
    }

    @Test
    void naturalMaterialsKeepTheirOwnCurvedBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            if (family == BoardScene.Surface.CONCRETE) { continue; }
            BoardScene scene = scene(land, family, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertEquals(family.ordinal(), surface.relief.family());
            int n = BoardSurface.SHORE_SEGMENTS;
            Vector3 a = surface.outline.get(5 * n), b = surface.outline.get(n);
            float bend = 0;
            for (int i = 1; i < 2 * n; i++) {
                bend = Math.max(bend, distance(a, b, surface.outline.get((5 * n + i) % (6 * n))));
            }
            assertTrue(bend > BoardGeometry.HEX_SCALE, "Natural banks still curve: " + family);
        }
    }

    private static float distance(Vector3 a, Vector3 b, Vector3 p) {
        return Math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x))
              / (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private static void assertWaterCentre(BoardSurface surface) {
        assertWaterCentre(surface, CENTER);
    }

    private static void assertWaterCentre(BoardSurface surface, Coords coords) {
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int angle = 0; angle < 12; angle++) {
            float x = center.x + 8 * BoardGeometry.HEX_SCALE * (float) Math.cos(angle * Math.PI / 6);
            float y = center.y + 8 * BoardGeometry.HEX_SCALE * (float) Math.sin(angle * Math.PI / 6);
            assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.waterFaces, x, y, Float.NaN)),
                  "A water unit's footprint remains in water");
        }
    }

    private static void assertUnfolded(BoardSurface surface, String message) {
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            assertTrue(normal.z >= -.001f * normal.len(), () -> message + " " + face);
        }
    }

    private static BoardScene scene(Set<Coords> land, BoardScene.Surface family, boolean buildings) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean dry = land.contains(coords);
                List<BoardScene.Feature> features = dry && buildings
                      ? List.of(new BoardScene.Feature("building", 0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                tiles.add(new BoardScene.Tile(coords, 0, dry ? -1 : 2, false, 0,
                      dry ? family : BoardScene.Surface.GRASS, null, null, features, List.of(),
                      dry ? BoardLiquid.NONE : BoardLiquid.WATER, null,
                      features.isEmpty() || family == BoardScene.Surface.CONCRETE));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
