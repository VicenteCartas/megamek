/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Wide grades remain a continuous height field even where two differently graded exits share a corner. */
class BoardBridgeWideTest {
    @Test
    void adjacentWideGradesCoverTheHexOnceAndMeetTheirBanksAcrossTheWholeMouth() {
        float scale = BoardGeometry.hexScale();
        for (int column : new int[] { 3, 4 }) {
            var at = new Coords(column, 4);
            var center = BoardGeometry.center(at, 0);
            for (int direction = 0; direction < 6; direction++) {
                int next = (direction + 1) % 6, sides = (1 << direction) | (1 << next);
                var rises = new ArrayList<>(Collections.nCopies(6, 0f));
                rises.set(direction, .5f);
                rises.set(next, -.5f);
                var tile = BridgeScenes.bridge(at, 0, 1, sides);
                var deck = new BoardBridge.Deck(BoardRoad.Kind.PAVED, sides, sides, false,
                      Collections.nCopies(6, BoardRoad.Kind.PAVED), BoardScene.Surface.CONCRETE,
                      rises, false, null, 0, new RailRoute.Corridor(sides, sides, sides));
                var profile = BoardBridgeSlope.profile(tile, deck);
                for (float y = -35.17f; y < 36; y += 2.31f) {
                    for (float x = -41.23f; x < 42; x += 2.17f) {
                        var point = new Vector3(center).add(x * scale, y * scale, 0);
                        if (BoardGeometry.contains(at, point.x, point.y)) { sample(profile, point); }
                    }
                }
                for (int d : new int[] { direction, next }) {
                    var axis = BoardGeometry.center(at.translated(d), 0).sub(center).nor();
                    var across = new Vector3(-axis.y, axis.x, 0);
                    int edge = Math.floorMod(1 - d, 6);
                    var a = BoardGeometry.corner(at, 0, edge);
                    var b = BoardGeometry.corner(at, 0, edge + 1);
                    var normal = new Vector3(b).sub(a);
                    normal.set(-normal.y, normal.x, 0).nor();
                    for (float offset : new float[] { -16.5f, -RailRoute.BESIDE_RAIL, 0, RailRoute.BESIDE_RAIL, 16.5f }) {
                        var point = new Vector3(a).lerp(b, .5f).mulAdd(across, offset * scale);
                        // The diagonal hex edge is slightly non-orthogonal to the center-to-center direction.
                        point.mulAdd(axis, new Vector3(a).sub(point).dot(normal) / axis.dot(normal));
                        assertEquals(rises.get(d) * BoardGeometry.level(), sample(profile, point), .005f,
                              "The entire wide bridge mouth meets its bank at direction " + d);
                    }
                }
            }
        }
    }

    @Test
    void threeConnectedDecksDeriveOneCornerHeightAndBlendTheirWideMouthSeams() {
        var at = new Coords(4, 4);
        var north = at.translated(0);
        var northeast = at.translated(1);
        // Each three-arm mask has a legal rail stem; the shared corner belongs to all three different deck planes.
        var levels = Map.of(at, 2, north, 3, northeast, 2);
        var exits = Map.of(at, 11, north, 44, northeast, 50);
        var scene = BoardScenes.scene(c -> levels.containsKey(c)
              ? BridgeScenes.withDeckRoad(BridgeScenes.bridge(c, 0, levels.get(c), exits.get(c)),
                    BoardRoad.Kind.PAVED, exits.get(c))
              : BoardScenes.tile(c, BoardRoad.Kind.NONE, 0, -2, BoardScene.Surface.ROCK));
        var corner = BoardGeometry.corner(at, 0, 1);
        var profiles = new java.util.HashMap<Coords, List<BoardSurface.Face>>();
        for (var c : levels.keySet()) {
            var tile = scene.tile(c);
            var deck = BoardBridge.deck(scene, tile);
            var wide = new BoardBridge.Deck(deck.kind(), deck.roadExits(), deck.exits(), false, deck.neighbors(),
                  deck.surface(), deck.rises(), false, null, deck.joints(), new RailRoute.Corridor(exits.get(c), 0, 0),
                  deck.cornerRises(), true);
            var profile = BoardBridgeSlope.profile(tile, wide);
            profiles.put(c, profile);
            assertEquals(7f / 3 * BoardGeometry.level(), sample(profile, corner) + levels.get(c) * BoardGeometry.level(), .005f,
                  "The common corner uses the same three participating deck levels from every tile");
        }
        var members = List.of(at, north, northeast);
        for (int i = 0; i < members.size(); i++) {
            var a = members.get(i);
            var b = members.get((i + 1) % members.size());
            var middle = BoardGeometry.center(a, 0).lerp(BoardGeometry.center(b, 0), .5f);
            for (float t : new float[] { 0, .02f, .08f, .2f, .5f, 1 }) {
                var point = new Vector3(corner).lerp(middle, t);
                assertEquals(sample(profiles.get(a), point) + levels.get(a) * BoardGeometry.level(),
                      sample(profiles.get(b), point) + levels.get(b) * BoardGeometry.level(), .005f,
                      "Both sides of each graded wide seam share its entire height, including the corner blend");
            }
        }
    }

    /** Sample independently of the render clipper, detecting gaps, overlapping interiors and split seam heights. */
    private static float sample(List<BoardSurface.Face> profile, Vector3 point) {
        float lowest = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY;
        int interiors = 0;
        for (var face : profile) {
            var a = face.a();
            var b = face.b();
            var c = face.c();
            double denominator = (double) (b.y - c.y) * (a.x - c.x) + (double) (c.x - b.x) * (a.y - c.y);
            if (Math.abs(denominator) < 1e-9) { continue; }
            double u = ((double) (b.y - c.y) * (point.x - c.x) + (double) (c.x - b.x) * (point.y - c.y)) / denominator;
            double v = ((double) (c.y - a.y) * (point.x - c.x) + (double) (a.x - c.x) * (point.y - c.y)) / denominator;
            double w = 1 - u - v;
            if (u < -.00001 || v < -.00001 || w < -.00001) { continue; }
            if (u > .00001 && v > .00001 && w > .00001) { interiors++; }
            float height = (float) (u * a.z + v * b.z + w * c.z);
            lowest = Math.min(lowest, height);
            highest = Math.max(highest, height);
        }
        assertTrue(Float.isFinite(highest), "Missing grade at " + point);
        assertTrue(interiors <= 1, "Overlapping grade patches at " + point);
        assertEquals(lowest, highest, .005f, "Grade discontinuity at " + point);
        return highest;
    }

    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void outsideParapetsAndBothLanesShareTheRampGradeAtEveryOrientation() {
        float scale = BoardGeometry.hexScale();
        for (int direction = 0; direction < 6; direction++) {
            for (int parity = 0; parity < 2; parity++) {
                for (boolean beside : new boolean[] { false, true }) {
                    var at = new Coords(3 + parity, 4);
                    var scene = wideBank(at, direction, beside, false);
                    var tile = scene.tile(at);
                    // All four LODs are exercised on every axis without repeating the same grade at each LOD.
                    var lod = TerrainLod.values()[2 * parity + (beside ? 1 : 0)];
                    var footing = BoardBridgeFooting.build(scene, tile, lod, new HashMap<>());
                    var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
                    var center = BoardGeometry.center(at, 1);
                    var bank = BoardGeometry.center(at.translated(direction), 0);
                    var along = new Vector3(bank).sub(center);
                    along.z = 0;
                    along.nor();
                    var across = new Vector3(-along.y, along.x, 0);
                    int edge = Math.floorMod(1 - direction, 6);
                    var edgeVector = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge));
                    var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
                    for (float t : new float[] { .3f, .4f, .49f }) {
                        var point = new Vector3(center).lerp(bank, t);
                        String context = "direction=" + direction + " parity=" + parity + " beside=" + beside
                              + " lod=" + lod + " t=" + t;
                        for (int side : new int[] { -1, 1 }) {
                            var parapet = new Vector3(point).mulAdd(across,
                                  side * (BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2) * scale);
                            assertEquals(grade(center, bank, parapet, normal) + 2.5f * scale,
                                  BridgeScenes.height(shape, parapet, false), BridgeScenes.EASED,
                                  "The outer parapet follows the same grade as the center: " + context);
                            var lane = new Vector3(point).mulAdd(across, side * 19.5f * scale);
                            float grade = grade(center, bank, lane, normal);
                            assertEquals(grade, BoardBridge.top(shape, lane.x, lane.y), BridgeScenes.EASED,
                                  "The carrier remains planar across its entire width: " + context);
                            assertEquals(grade, BridgeScenes.height(shape, lane, false),
                                  BridgeScenes.EASED,
                                  "Picking sees the drawn lane without a raised sliver: " + context);
                        }
                    }
                }
            }
        }
    }

    /** The slightly non-regular hex lattice grades across its real edge, not perpendicular to its center line. */
    private static float grade(Vector3 center, Vector3 bank, Vector3 point, Vector3 normal) {
        float span = (bank.x - center.x) * normal.x + (bank.y - center.y) * normal.y;
        float projected = ((point.x - center.x) * normal.x + (point.y - center.y) * normal.y) / span;
        return center.z + (bank.z - center.z) * BridgeScenes.eased(projected)
              + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
    }

    @Test
    void wideTrussFasciaDoesNotOverlapTheConcreteRampSides() {
        float scale = BoardGeometry.hexScale();
        for (int direction : new int[] { 1, 3 }) {
            var at = new Coords(direction == 1 ? 3 : 4, 4);
            var scene = wideBank(at, direction, true, true);
            var tile = scene.tile(at);
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
            var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
            var steel = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                  .filter(face -> face.part() == BoardBridge.Part.STEEL).toList());
            var concrete = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                  .filter(face -> face.part() != BoardBridge.Part.STEEL).toList());
            var center = BoardGeometry.center(at, 1);
            var bank = BoardGeometry.center(at.translated(direction), 0);
            var along = new Vector3(bank).sub(center);
            along.z = 0;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            for (float t : new float[] { .31f, .44f }) {
                for (float below : new float[] { .35f, 1.7f }) {
                    var point = new Vector3(center).lerp(bank, t);
                    point.z = (1.5f - 2 * t) * BoardGeometry.level() + (GpuRoads.SURFACE_LIFT - below) * scale;
                    for (int side : new int[] { -1, 1 }) {
                        var ray = new Ray(new Vector3(point).mulAdd(across, side * 30 * scale),
                              new Vector3(across).scl(-side));
                        float steelDistance = (float) Math.sqrt(steel.hit(ray));
                        float concreteDistance = (float) Math.sqrt(concrete.hit(ray));
                        assertTrue(Float.isFinite(concreteDistance), "The broad ramp has a real support face");
                        assertEquals((30 - BoardRoad.CORRIDOR_HALF - BoardBridge.SHOULDER) * scale,
                              Math.min(steelDistance, concreteDistance), .02f, "The outside support remains closed");
                        assertFalse(Math.abs(steelDistance - concreteDistance) < .001f,
                              "No coplanar steel and concrete face at direction=" + direction + " t=" + t);
                    }
                }
            }
        }
    }

    private static BoardScene wideBank(Coords at, int direction, boolean beside, boolean truss) {
        var scene = BridgeScenes.lowerBank(at, direction, true);
        var t = scene.tile(at);
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        var corridor = new RailRoute.Corridor(exits, beside ? exits : 0, 0);
        var tile = new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(),
              t.surface(), t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("bridge", truss ? HexAppearance.TRUSS : HexAppearance.BUILT_BRIDGE), t.cover(), t.barren(),
              t.deckRoad(), t.deckRoadExits(), t.deckMud(), t.railBed(), t.corridor(), corridor);
        return BoardScenes.replace(scene, tile);
    }
}
