/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRailTest {
    private static final Coords CENTER = new Coords(4, 4);

    /** Markers at fixed levels that leave towards each neighbouring marker, as a painted network does. */
    static BoardScene scene(Map<Coords, Double> nodes) {
        var sides = new java.util.HashMap<Coords, Integer>();
        for (var at : nodes.keySet()) {
            int mask = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (nodes.containsKey(at.translated(direction))) { mask |= RailRoute.side(direction); }
            }
            sides.put(at, mask);
        }
        return scene(nodes, sides);
    }

    static BoardScene scene(Map<Coords, Double> nodes, Map<Coords, Integer> sides) {
        return scene(nodes, sides, RailRoute.Kind.MAGLEV);
    }

    /** Native way terrain: a maglev on an implicit deck at the node's level, a rail on the ground. */
    static BoardScene scene(Map<Coords, Double> nodes, Map<Coords, Integer> sides, RailRoute.Kind kind) {
        return BoardScenes.scene(coords -> nodes.containsKey(coords)
              ? BoardBridgeFootingTest.routeTile(coords, false, kind, nodes.get(coords).intValue(), sides.get(coords), false, 0)
              : BoardScenes.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0));
    }

    static BoardRail.Layout layout(BoardScene scene, Coords at) {
        return BoardRail.layout(scene, scene.tile(at), BoardRail.route(scene.tile(at)));
    }

    @Test void storedSidesDecideJoinsSoParallelRoutesStayApartAndUnmatchedSidesEndLevel() {
        var east = CENTER.translated(1);
        // Two parallel N/S routes in adjacent hexes: neither leaves towards the other.
        var parallel = scene(Map.of(CENTER, 2.0, east, 2.0), Map.of(CENTER, 9, east, 9));
        var ends = layout(parallel, CENTER).ends();
        assertEquals(2, ends.size());
        for (var end : ends) {
            assertEquals(0, end.x(), .0001f, "Each side runs to its own hex edge");
            assertEquals(0, end.z(), .0001f);
        }
        // A side whose neighbour does not leave back is an open, level end at the edge, whatever that marker's level.
        var north = CENTER.translated(0);
        var open = layout(scene(Map.of(CENTER, 2.0, north, 5.0), Map.of(CENTER, 1, north, 1)), CENTER);
        assertEquals(1, open.ends().size());
        assertEquals(0, open.ends().getFirst().z(), .0001f);
        assertEquals(0, open.ends().getFirst().slope(), .0001f);
        var joined = layout(scene(Map.of(CENTER, 2.0, north, 3.0), Map.of(CENTER, 1, north, 8)), CENTER);
        assertTrue(joined.ends().getFirst().z() > 0, "A joined side climbs towards its neighbour's level");
        var unreachable = layout(scene(Map.of(CENTER, 2.0, north, 5.0), Map.of(CENTER, 1, north, 8)), CENTER);
        assertEquals(0, unreachable.ends().getFirst().z(), "Separate bridge levels do not form a steep unsupported ramp");
        // An empty mask still draws the short N/S marker stub.
        var stub = layout(scene(Map.of(CENTER, 2.0), Map.of(CENTER, 0)), CENTER);
        assertTrue(stub.ends().isEmpty());
        assertEquals(1, BoardRail.paths(stub).size());
    }

    @Test void allSixConnectionsMeetAtExactlyTheSameMeshCornersAcrossElevations() {
        for (int direction = 0; direction < 6; direction++) {
            var next = CENTER.translated(direction);
            var scene = scene(Map.of(CENTER, 2.0, next, 3.0,
                  CENTER.translated((direction + 2) % 6), 2.0, CENTER.translated((direction + 4) % 6), 1.0));
            var a = layout(scene, CENTER); var b = layout(scene, next);
            Vector3 edge = b.ends().getFirst().point().add(BoardGeometry.center(next, 3));
            assertTrue(a.ends().stream().anyMatch(end -> end.point().add(BoardGeometry.center(CENTER, 2)).epsilonEquals(edge, .0001f)));
            var first = edgeValues(a, CENTER, 2, edge, false);
            var second = edgeValues(b, next, 3, edge, false);
            assertEquals(8, first.size()); assertEquals(first.size(), second.size());
            for (var vertex : first) {
                assertTrue(second.stream().anyMatch(v -> v.epsilonEquals(vertex, .0001f)), "Unmatched seam vertex " + vertex);
            }
            var firstNormals = edgeValues(a, CENTER, 2, edge, true);
            var secondNormals = edgeValues(b, next, 3, edge, true);
            assertEquals(1, firstNormals.size()); assertEquals(1, secondNormals.size());
            assertTrue(firstNormals.getFirst().epsilonEquals(secondNormals.getFirst(), .0001f),
                  "Lighting normals must carry the same slope across every turnout seam");
            assertTrue(Math.abs(firstNormals.getFirst().z - 1) > .0001f, "An inclined join must not flatten");
        }
    }

    private static List<Vector3> edgeValues(BoardRail.Layout layout, Coords at, float level, Vector3 edge, boolean normals) {
        var data = BoardRail.model(layout).meshes.first();
        var origin = BoardGeometry.center(at, level);
        var axis = new Vector3(edge).sub(origin); axis.z = 0; axis.nor();
        var points = new ArrayList<Vector3>();
        for (int i = 0; i < data.vertices.length; i += 7) {
            var point = new Vector3(data.vertices[i], data.vertices[i + 1], data.vertices[i + 2]).add(origin);
            if (Math.abs(new Vector3(point).sub(edge).dot(axis)) >= .001f) { continue; }
            if (normals) {
                if (data.vertices[i + 5] <= 0) { continue; }
                point.set(data.vertices[i + 3], data.vertices[i + 4], data.vertices[i + 5]);
            }
            if (points.stream().noneMatch(v -> v.epsilonEquals(point, .0001f))) { points.add(point); }
        }
        return points;
    }

    @Test void everyExitPatternHasBoundedGeometryAndTurnsKeepAnOpenInside() {
        for (int mask = 0; mask < 64; mask++) {
            var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(CENTER, 2.0);
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 1.0 + direction % 3); }
            }
            var layout = layout(scene(nodes), CENTER);
            var mesh = BoardRail.model(layout).meshes.first();
            assertTrue(mesh.parts[0].indices.length / 3 <= 1000, "At most five bounded turnout curves");
            assertEquals(1, mesh.parts.length);
            for (int i = 0; i < mesh.vertices.length; i += 7) {
                for (int channel = 0; channel < 6; channel++) { assertTrue(Float.isFinite(mesh.vertices[i + channel])); }
                assertEquals(1, new Vector3(mesh.vertices[i + 3], mesh.vertices[i + 4], mesh.vertices[i + 5]).len(), .001);
                var color = new com.badlogic.gdx.graphics.Color();
                com.badlogic.gdx.graphics.Color.abgr8888ToColor(color, mesh.vertices[i + 6]);
                assertTrue(color.r > .3f && color.g > .3f && color.b > .3f, "Mesh attribute order must preserve rail pigment");
            }
        }
        var straight = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(3), 2.0)), CENTER);
        var mesh = BoardRail.model(straight).meshes.first();
        assertEquals(24, mesh.parts[0].indices.length / 3);
        for (int i = 0; i < mesh.vertices.length; i += 7) { assertTrue(Math.abs(mesh.vertices[i]) <= 2); }
        var turn = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(2), 2.0)), CENTER);
        assertEquals(1, BoardRail.paths(turn).size());
        assertTrue(BoardRail.paths(turn).getFirst().stream().allMatch(p -> new Vector3(p.x, p.y, 0).len() > 5),
              "A rounded turn leaves the inside open instead of drawing a bridge deck or radial spokes");
    }

    @Test void steadyClimbsAndDescentsKeepAConstantGradeWithoutPerHexFlatSpots() {
        for (int direction = 0; direction < 6; direction++) {
            for (double change : new double[] { -1, 1 }) {
                var nodes = new java.util.HashMap<Coords, Double>();
                for (int step = -2; step <= 2; step++) {
                    nodes.put(CENTER.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step)), 5 + change * step);
                }
                var scene = scene(nodes);
                Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(BoardGeometry.center(CENTER, 0));
                float grade = (float) (change * BoardGeometry.level()) / axis.len(); axis.nor();
                for (int step = -1; step <= 1; step++) {
                    var at = CENTER.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step));
                    var shape = layout(scene, at);
                    for (var point : BoardRail.paths(shape).getFirst()) {
                        assertEquals(new Vector3(point.x, point.y, 0).dot(axis) * grade, point.z, .0001f,
                              "A steady ramp must lie on one incline, not a chain of easing curves");
                    }
                    assertEquals(24, BoardRail.model(shape).meshes.first().parts[0].indices.length / 3,
                          "A constant incline needs no extra subdivisions");
                }
            }
        }
    }

    @Test void changingGradesStayMonotoneAndCrestsNeverOvershootChosenHeights() {
        for (double incoming : new double[] { -1, 0, 1 }) {
            for (double outgoing : new double[] { -1, 0, 1 }) {
                var shape = layout(scene(Map.of(CENTER, 5.0, CENTER.translated(0), 5 + incoming,
                      CENTER.translated(3), 5 + outgoing)), CENTER);
                var path = BoardRail.paths(shape).getFirst();
                int middle = path.size() / 2;
                if (path.size() == 2) { continue; }
                assertEquals(0, path.get(middle).z, .0001f);
                for (int i = 0; i < path.size(); i++) {
                    float end = (i <= middle ? path.getFirst() : path.getLast()).z;
                    assertTrue(path.get(i).z >= Math.min(0, end) - .0001f && path.get(i).z <= Math.max(0, end) + .0001f,
                          "Transitions must remain between the authored heights");
                    if (i > 0) {
                        double direction = i <= middle ? -incoming : outgoing;
                        assertTrue((path.get(i).z - path.get(i - 1).z) * direction >= -.0001f);
                    }
                }
            }
        }
    }

    /**
     * Every side mask lays its gentle pieces ({@link RailRoute#pieces}) for both kinds: a straight between opposite
     * edges, a 60-degree arc between edges two apart (radius sqrt(3) apothem, 62.4 px, to within half a pixel), a short
     * stub on each stranded edge; a crossing is two real straights, never a fan from one approach.
     */
    @Test void junctionsLayOnlyGentlePiecesRealCrossingsAndStubs() {
        for (var kind : RailRoute.Kind.values()) {
            for (int mask = 0; mask < 64; mask++) {
                var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(CENTER, 2.0);
                for (int direction = 0; direction < 6; direction++) {
                    if ((mask & (1 << direction)) != 0) { nodes.put(CENTER.translated(direction), 2.0); }
                }
                var scene = scene(nodes, sides(nodes), kind);
                var paths = layout(scene, CENTER).paths();
                if (Integer.bitCount(mask) < 2) { assertEquals(1, paths.size()); continue; }
                int stranded = RailRoute.stranded(mask);
                assertEquals(Integer.bitCount(RailRoute.pieces(mask)) + Integer.bitCount(stranded), paths.size(), kind + " " + mask);
                int stubs = 0;
                for (var path : paths) {
                    var first = horizontal(path.getFirst()); var last = horizontal(path.getLast());
                    float turn = angle(first, last);
                    if (Math.abs(last.len() - first.len()) > 1) {
                        // A stub runs from its edge straight towards the centre.
                        stubs++;
                        assertEquals(RailRoute.stub(mask), first.len() - last.len(), .01f, kind + " " + mask);
                        assertEquals(0, first.crs(last).len(), .01f);
                    } else if (Math.abs(turn - 180) > .5f) {
                        // Edges two apart lie 120 degrees apart round the centre.
                        assertEquals(120, turn, 1, kind + " " + mask + ": an arc joins edges two apart, never adjacent ones");
                        // The arc's centre is the centre of the hex across the edge between its ends.
                        var centre = new Vector3(first).add(last).scl(2);
                        float from = new Vector3(first).sub(centre).len(), to = new Vector3(last).sub(centre).len();
                        assertEquals(62.4f, (from + to) / 2, 1, "sqrt(3) apothems");
                        for (var point : path) {
                            float radius = horizontal(point).sub(centre).len();
                            assertTrue(radius > Math.min(from, to) - .5f && radius < Math.max(from, to) + .5f, kind + " " + mask);
                        }
                    }
                }
                assertEquals(Integer.bitCount(stranded), stubs, kind + " " + mask);
            }
            // N, NE, S, SW: the N-S and NE-SW lines cross, each a straight.
            var crossing = layout(scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(1), 2.0,
                  CENTER.translated(3), 2.0, CENTER.translated(4), 2.0), Map.of(CENTER, 27, CENTER.translated(0), 8,
                  CENTER.translated(1), 16, CENTER.translated(3), 1, CENTER.translated(4), 2), kind), CENTER).paths();
            assertEquals(2, crossing.size());
            for (var path : crossing) { assertEquals(180, angle(horizontal(path.getFirst()), horizontal(path.getLast())), .01f); }
        }
    }

    /** Sides of a node map, as {@link #scene(Map)} derives them. */
    private static Map<Coords, Integer> sides(Map<Coords, Double> nodes) {
        var sides = new java.util.HashMap<Coords, Integer>();
        for (var at : nodes.keySet()) {
            int mask = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (nodes.containsKey(at.translated(direction))) { mask |= RailRoute.side(direction); }
            }
            sides.put(at, mask);
        }
        return sides;
    }

    private static Vector3 horizontal(Vector3 point) { return new Vector3(point.x, point.y, 0); }

    /** The angle between two directions from the hex centre, in degrees. */
    private static float angle(Vector3 a, Vector3 b) {
        return (float) Math.toDegrees(Math.acos(Math.clamp(a.dot(b) / (a.len() * b.len()), -1f, 1f)));
    }

    @Test void neighborEditsChangeOnlyDerivedLayoutsAndIdenticalRoutesShareKeys() {
        var next = CENTER.translated(0);
        var before = scene(Map.of(CENTER, 2.0, next, 2.0));
        var higher = scene(Map.of(CENTER, 2.0, next, 3.0));
        assertNotEquals(layout(before, CENTER), layout(higher, CENTER));
        assertNotEquals(layout(before, CENTER), layout(scene(Map.of(CENTER, 2.0)), CENTER));
        var shifted = scene(Map.of(CENTER, 5.0, next, 5.0));
        assertEquals(layout(before, CENTER), layout(shifted, CENTER), "Absolute elevation does not duplicate shared meshes");
        var beyond = next.translated(0);
        assertNotEquals(layout(scene(Map.of(CENTER, 2.0, next, 3.0, beyond, 3.0)), CENTER),
              layout(scene(Map.of(CENTER, 2.0, next, 3.0, beyond, 4.0)), CENTER),
              "An edge's grade also follows its neighbour's continuation, within the existing two-hex edit invalidation");
        var valley = layout(scene(Map.of(CENTER, 2.0, next, 3.0, CENTER.translated(3), 3.0)), CENTER);
        var path = BoardRail.paths(valley).getFirst();
        assertEquals(0, path.get(path.size() / 2).z, .0001f, "Equal higher neighbours still respect this hex's chosen level");
    }

    @Test void implicitMaglevIsTheNarrowPickableBeamWithoutSlabOrLandings() {
        var scene = scene(Map.of(CENTER, 2.0, CENTER.translated(0), 2.0, CENTER.translated(3), 2.0));
        var tile = scene.tile(CENTER);
        assertTrue(BoardBridge.guideway(tile));
        var surfaces = new java.util.HashMap<Coords, BoardSurface>();
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        assertTrue(footing.shape().facets().isEmpty(), "No ordinary slab supports or landings");
        assertTrue(footing.lengths().stream().allMatch(length -> length == 0));
        var shape = footing.withDeck(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, surfaces);
        assertEquals(4 * BoardGeometry.hexScale(), shape.bounds().getWidth(), .001f);
        var centre = BoardGeometry.center(CENTER, 2);
        assertEquals(centre.z + 4 * BoardGeometry.hexScale(), BoardBridge.top(shape, centre.x, centre.y), .001f);
        var ray = new com.badlogic.gdx.math.collision.Ray(new Vector3(centre).add(0, 0, 50), new Vector3(0, 0, -1));
        assertTrue(Float.isFinite(shape.hit(ray)), "The guideway remains a pickable bridge deck");
        ray.origin.x += 6 * BoardGeometry.hexScale();
        assertFalse(Float.isFinite(shape.hit(ray)), "No invisible wide slab next to the beam");
    }

    private static final Coords AT = BoardScenes.CENTER;

    private static BoardScene.Feature rail(Coords at, int sides, BoardDecoration.Placement placement) {
        var marker = new BoardDecoration("rail-" + at, "prop", RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1, placement, 0)
              .withConnections(sides);
        return new BoardScene.Feature(marker.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, marker);
    }

    /** A tile with a road of these exits, or rails on its ground with these sides, at {@code level}. */
    private static BoardScene.Tile tile(Coords at, int level, int sides, boolean road, List<BoardScene.Feature> more) {
        var base = BoardScenes.tile(at, road ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, road ? sides : 0, level,
              BoardScene.Surface.GRASS);
        List<BoardScene.Feature> features = new ArrayList<>(more);
        if (!road && sides != 0) { features.add(rail(at, sides, BoardDecoration.Placement.ground())); }
        return new BoardScene.Tile(at, level, -1, false, base.roadExits(), base.surface(), null, null, features, List.of(), BoardLiquid.NONE, null, true, base.road());
    }

    /**
     * A N-S line through {@link #AT}: its north neighbour {@code rise} levels up, as roads or as rails, with a branch
     * to the SE neighbour (a turnout) on level ground.
     */
    private static BoardScene line(boolean road, int rise) {
        Coords north = AT.translated(0), south = AT.translated(3), east = AT.translated(2);
        return BoardScenes.scene(at -> at.equals(AT) ? tile(at, 0, 1 | 8 | 4, road, List.of())
              : at.equals(north) ? tile(at, rise, 8, road, List.of())
              : at.equals(south) ? tile(at, 0, 1, road, List.of())
              : at.equals(east) ? tile(at, 0, RailRoute.side(5), road, List.of())
              : tile(at, at.getX() == north.getX() && at.getY() < north.getY() ? rise : 0, 0, road, List.of()));
    }

    @Test
    void aRailOnlyHexIsShapedExactlyAsTheRoadHexWithTheSameExits() {
        for (int rise : new int[] { 0, 1, 2 }) {
            var roads = line(true, rise);
            var rails = line(false, rise);
            for (Coords at : List.of(AT, AT.translated(0), AT.translated(3), AT.translated(2))) {
                var road = roads.tile(at);
                var rail = rails.tile(at);
                assertEquals(road.roadExits(), rail.wayExits(), "rise " + rise + " " + at);
                assertEquals(0, rail.roadExits(), "no road paint under a rail");
                assertTrue(BoardRoad.carriageway(rail) && !BoardRoad.rendered(rail));
                assertEquals(BoardSurface.ramps(roads, road), BoardSurface.ramps(rails, rail), "rise " + rise + " " + at);
                assertGround(new BoardSurface(roads, road), new BoardSurface(rails, rail), "rise " + rise + " " + at);
            }
        }
        // A junction keeps its crossing level; the line beyond it carries the whole ramp (BoardRoadGrade.edgeLevel).
        assertNotEquals(0, BoardSurface.ramps(line(false, 1), line(false, 1).tile(AT.translated(0))),
              "a rail climbs to its neighbour on a ramp");
    }

    @Test
    void theTrackLiesOnTheGroundTheRoadEngineGivesTheSamePoints() {
        for (int rise : new int[] { 0, 1, 2 }) {
            var roads = line(true, rise);
            var rails = line(false, rise);
            for (Coords at : List.of(AT, AT.translated(0))) {
                var tile = rails.tile(at);
                var feature = BoardRail.route(tile);
                var layout = BoardRail.layout(rails, tile, feature, new BoardSurface(rails, tile), coords -> null);
                var road = new BoardSurface(roads, roads.tile(at));
                float base = BoardRail.level(tile, feature) * BoardGeometry.level(), scale = BoardGeometry.hexScale();
                int points = 0;
                for (var path : layout.paths()) {
                    for (Vector3 point : path) {
                        float x = BoardGeometry.centerX(at) + point.x * scale, y = BoardGeometry.centerY(at) + point.y * scale;
                        assertEquals(BoardSurface.sampleHeight(road.groundFaces(), x, y, Float.NaN), base + point.z * scale,
                              .001f * scale, "rise " + rise + " " + at + " " + point);
                        points++;
                    }
                }
                assertTrue(points >= 2);
                if (rise > 0 && !at.equals(AT)) {
                    assertTrue(layout.paths().stream().flatMap(List::stream).anyMatch(p -> p.z < -.5f),
                          "the track descends the ramp to the junction");
                }
            }
        }
        // Flat ground: every point on the plane, and every flat straight of a kind shares one layout.
        var flat = line(false, 0);
        var south = flat.tile(AT.translated(3));
        var layout = BoardRail.layout(flat, south, BoardRail.route(south), new BoardSurface(flat, south), coords -> null);
        assertTrue(layout.paths().stream().flatMap(List::stream).allMatch(p -> Math.abs(p.z) < 1e-4f));
    }

    @Test
    void aDeckRailRidesTheDrawnDeckAndOnlyAlongTheBridge() {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        var deck = rail(AT, 9 | 18, BoardDecoration.Placement.on("bridge", 0));
        var scene = BoardScenes.scene(at -> at.equals(AT) ? tile(at, 0, 0, false, List.of(bridge, deck))
              : tile(at, 0, 0, false, List.of()));
        var tile = scene.tile(AT);
        assertEquals(0, tile.railExits(), "a deck rail does not shape the ground beneath");
        var layout = BoardRail.layout(scene, tile, deck, new BoardSurface(scene, tile), coords -> null);
        float expected = (BoardBridge.deckZ(tile) - BoardRail.level(tile, deck) * BoardGeometry.level()) / BoardGeometry.hexScale();
        assertEquals(1, layout.paths().size(), "only the bridge's N-S exits carry track");
        for (var point : layout.paths().getFirst()) { assertEquals(expected, point.z, 1e-4f); }
        assertEquals(1 + 8, BoardRail.drawnSides(tile, deck));
    }

    @Test
    void groundRailsClimbTheBridgeOwnedRampAndMeetTheDeckInEveryDirection() {
        for (int x : new int[] { 3, 4 }) {
            var bankAt = new Coords(x, 4);
            for (int direction = 0; direction < 6; direction++) {
                var bridgeAt = bankAt.translated(direction);
                int sides = RailRoute.side(direction) | RailRoute.side((direction + 3) % 6);
                var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, sides);
                var deckRail = rail(bridgeAt, sides, BoardDecoration.Placement.on("bridge", 0));
                var scene = BoardScenes.scene(at -> at.equals(bankAt) ? tile(at, 0, sides, false, List.of())
                      : tile(at, 0, 0, false, at.equals(bridgeAt) ? List.of(bridge, deckRail) : List.of()));
                var bank = scene.tile(bankAt);
                var span = scene.tile(bridgeAt);
                var marker = BoardRail.route(bank);
                for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var footing = BoardBridgeFooting.build(scene, span, lod, surfaces);
                    var shape = BoardBridgeSlope.build(span, BoardBridge.deck(scene, span), footing);
                    var shapes = Map.of(bridgeAt, shape);
                    var ground = surfaces.computeIfAbsent(bankAt, at -> new BoardSurface(scene, bank, lod));
                    var approach = BoardRail.layout(scene, bank, marker, ground, shapes::get);
                    var deck = BoardRail.layout(scene, span, deckRail, new BoardSurface(scene, span, lod), shapes::get);
                    float scale = BoardGeometry.hexScale();
                    var center = BoardGeometry.center(bankAt, BoardRail.level(bank, marker));
                    var seam = new Vector3(center).lerp(BoardGeometry.center(bridgeAt, 0), .5f);
                    float rampTop = BoardBridge.top(shape, seam.x, seam.y);
                    assertEquals(BoardBridge.deckZ(.5f), rampTop, .001f);
                    assertEquals(0, BoardSurface.sampleHeight(ground.groundFaces(), seam.x, seam.y, Float.NaN), .001f,
                          "The bank stays flat beneath the solid approach");
                    for (var point : approach.paths().getFirst()) {
                        var world = new Vector3(point).scl(scale).add(center);
                        float support = Math.max(BoardSurface.sampleHeight(ground.groundFaces(), world.x, world.y, 0),
                              BoardBridge.top(shape, world.x, world.y));
                        assertEquals(support, world.z, .001f, "Rail follows the drawn support: " + direction + " " + lod);
                    }
                    for (var layout : List.of(approach, deck)) {
                        var origin = layout == approach ? center : BoardGeometry.center(bridgeAt, BoardRail.level(span, deckRail));
                        var endpoint = layout.paths().getFirst().stream().map(p -> new Vector3(p).scl(scale).add(origin))
                              .filter(p -> Math.hypot(p.x - seam.x, p.y - seam.y) < .001f).findFirst().orElseThrow();
                        assertEquals(rampTop, endpoint.z, .001f, "The two rails meet on the ramp at the hex boundary");
                    }
                    // A separate marker with no exit toward the bridge must retain its own ground support.
                    var other = rail(bankAt, 0, BoardDecoration.Placement.ground());
                    assertTrue(BoardRail.approaches(scene, bank, other).isEmpty());
                }
            }
        }
    }

    @Test
    void groundTrackContinuingUnderABridgeDoesNotClimbItsApproach() {
        var bridgeAt = AT.translated(0);
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        var scene = BoardScenes.scene(at -> tile(at, 0, at.equals(AT) || at.equals(bridgeAt) ? 9 : 0,
              false, at.equals(bridgeAt) ? List.of(bridge) : List.of()));
        var bank = scene.tile(AT);
        var marker = BoardRail.route(bank);
        assertTrue(BoardRail.approaches(scene, bank, marker).isEmpty());
        var shape = BoardBridgeFooting.build(scene, scene.tile(bridgeAt), TerrainLod.FULL, new HashMap<>()).shape();
        var layout = BoardRail.layout(scene, bank, marker, new BoardSurface(scene, bank), Map.of(bridgeAt, shape)::get);
        assertTrue(layout.paths().stream().flatMap(List::stream).allMatch(p -> Math.abs(p.z) < .001f),
              "Ground continuation takes precedence over the overhead deck");
    }

    @Test
    void onADrawnRoadOnlyTheRailsShowSetIntoIt() {
        // A NE-SW road with a N-S rail across it: street track over the carriageway, full track beyond it.
        var scene = BoardScenes.scene(at -> at.equals(AT)
              ? new BoardScene.Tile(at, 0, -1, false, 18, BoardScene.Surface.GRASS, null, null, List.of(rail(at, 9,
                    BoardDecoration.Placement.ground())),
                    List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.PAVED)
              : tile(at, 0, 0, false, List.of()));
        var tile = scene.tile(AT);
        assertEquals(18 | 9, tile.wayExits());
        var layout = BoardRail.layout(scene, tile, BoardRail.route(tile), new BoardSurface(scene, tile), coords -> null);
        var street = layout.streets().getFirst();
        assertTrue(street.contains(true) && street.contains(false));
        assertFalse(street.getFirst() && street.getLast(), "the ends beyond the road keep their ballast");
        var data = BoardRail.model(layout).meshes.first();
        var embedded = BoardRail.model(new BoardRail.Layout(layout.kind(), layout.ends(), layout.paths(),
              List.of(street.stream().map(flag -> false).toList()))).meshes.first();
        assertTrue(data.vertices.length < embedded.vertices.length, "no ballast or sleepers on the road");
        // A rail rider stands at the rail top the track draws under it (model px, as the track at any level height):
        // set into the road there, on the ballast beyond it. Other objects, or a rider on another support, get none.
        var path = layout.paths().getFirst();
        var wagon = rider("scenery/rail/box-car", BoardDecoration.Placement.ground());
        for (int i = 0; i < path.size(); i++) {
            assertEquals(street.get(i) ? BoardRail.STREET_TOP : RailRoute.RAIL_TOP,
                  BoardRail.railTop(scene, tile, wagon, path.get(i).x, path.get(i).y), 1e-6f);
        }
        assertEquals(0, BoardRail.railTop(scene, tile, rider("scenery/rail/crossing-signal", BoardDecoration.Placement.ground()), 0, 0));
        assertEquals(0, BoardRail.railTop(scene, tile, rider("scenery/rail/box-car", BoardDecoration.Placement.on("bridge", 0)), 0, 0));
    }

    private static BoardScene.Feature rider(String asset, BoardDecoration.Placement placement) {
        var object = new BoardDecoration(asset, "prop", asset, null, 0, 0, 0, false, 1, placement, 0);
        return new BoardScene.Feature(asset, 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
    }

    @Test
    void groundRailsKeepCoverOffTheirTrackAndOwnTheirBufferStops() {
        var scene = line(false, 0);
        var south = scene.tile(AT.translated(3));
        var obstacles = new BoardObstacles(scene, south);
        var centre = BoardGeometry.center(south.coords(), 0);
        assertTrue(obstacles.obstructs(new Vector3(centre).add(0, 20 * BoardGeometry.hexScale(), 0), 0, 1),
              "the track's ballast band");
        assertFalse(obstacles.obstructs(new Vector3(centre).add(20 * BoardGeometry.hexScale(), 0, 0), 0, 1));
        // A dead end's stop is the rail's, at its place, riding the rail top; the hex never stores it.
        Hex hex = new Hex(0, "rail:1:1", "");
        var marker = RailRoute.derived(hex, "terrain").getFirst();
        var features = BoardFeatures.capture(hex, AT, Map.of());
        var stops = features.stream().filter(feature -> feature.asset().equals(RailRoute.BUFFER_STOP)).toList();
        assertEquals(1, stops.size());
        var stop = stops.getFirst().decoration();
        assertTrue(BoardFeatures.terrainOwned(stop), "never picked or stored as an object");
        assertEquals(RailRoute.ride(marker), stop.placement());
        assertEquals(RailRoute.STOP_DEPTH / BoardGeometry.TILE_HEIGHT, stop.y(), 1e-6);
        assertTrue(hex.getDecorations().isEmpty(), "The rail and its stop are terrain-owned, never stored objects");
    }

    @Test
    void railMeshesAreBoundedVertexColouredAndSharedByFlatHexes() {
        for (int mask = 0; mask < 64; mask++) {
            var nodes = new java.util.HashMap<Coords, Double>(); nodes.put(AT, 0.0);
            for (int d = 0; d < 6; d++) { if ((mask & 1 << d) != 0) { nodes.put(AT.translated(d), 0.0); } }
            var sides = new java.util.HashMap<Coords, Integer>();
            nodes.keySet().forEach(at -> {
                int m = 0;
                for (int d = 0; d < 6; d++) { if (nodes.containsKey(at.translated(d))) { m |= 1 << d; } }
                sides.put(at, m);
            });
            var scene = BoardRailTest.scene(nodes, sides, RailRoute.Kind.RAIL);
            var tile = scene.tile(AT);
            var layout = BoardRail.layout(scene, tile, BoardRail.route(tile), new BoardSurface(scene, tile), coords -> null);
            var mesh = BoardRail.model(layout).meshes.first();
            assertTrue(mesh.vertices.length / 7 < 65_536, "mask " + mask);
            for (int i = 0; i < mesh.vertices.length; i += 7) {
                assertTrue(Math.abs(mesh.vertices[i]) <= 44 && Math.abs(mesh.vertices[i + 1]) <= 40, "within its hex: " + mask);
                assertTrue(mesh.vertices[i + 2] <= RailRoute.RAIL_TOP + 1e-4f, "nothing above the rail top");
                assertEquals(1, new Vector3(mesh.vertices[i + 3], mesh.vertices[i + 4], mesh.vertices[i + 5]).len(), .001f);
            }
            // The same mask on another flat hex is the same layout, one shared mesh.
            var other = BoardRailTest.scene(Map.of(AT.translated(3), 0.0), Map.of(AT.translated(3), mask), RailRoute.Kind.RAIL);
            var otherTile = other.tile(AT.translated(3));
            if (Integer.bitCount(mask) < 2) { continue; }
            var shared = BoardRail.layout(other, otherTile, BoardRail.route(otherTile), new BoardSurface(other, otherTile), coords -> null);
            assertEquals(layout.paths(), shared.paths(), "mask " + mask);
        }
    }

    /**
     * The same ground over the hex. Loose rocks are left out, and so is the ground they cover: they keep clear of a
     * rail's wider course (BoardRoad.way) than of a road's; that course's outline also splits the flat ground into
     * other triangles.
     */
    private static void assertGround(BoardSurface road, BoardSurface rail, String context) {
        var center = BoardGeometry.center(road.tile.coords(), 0);
        float step = BoardGeometry.width() / 20;
        int compared = 0, samples = 0;
        for (float dx = -.3f * BoardGeometry.width(); dx <= .3f * BoardGeometry.width(); dx += step) {
            for (float dy = -.3f * BoardGeometry.height(); dy <= .3f * BoardGeometry.height(); dy += step) {
                float x = center.x + dx, y = center.y + dy;
                float roadHeight = BoardSurface.sampleHeight(road.foundation(), x, y, Float.NaN);
                float railHeight = BoardSurface.sampleHeight(rail.foundation(), x, y, Float.NaN);
                if (Float.isNaN(roadHeight) && Float.isNaN(railHeight)) { continue; }
                samples++;
                if (Float.isNaN(roadHeight) || Float.isNaN(railHeight)) { continue; }
                assertEquals(roadHeight, railHeight, .001f * BoardGeometry.hexScale(), context + " at " + x + "," + y);
                compared++;
            }
        }
        assertTrue(compared > .9f * samples, context + ": compare the ground, not the rocks");
    }
}
