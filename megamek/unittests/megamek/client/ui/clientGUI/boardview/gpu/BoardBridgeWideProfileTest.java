/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

/** Wide grades remain a continuous height field even where two differently graded exits share a corner. */
class BoardBridgeWideProfileTest {
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
                var tile = BoardBridgeSlopeTest.bridge(at, 0, 1, sides);
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
              ? BoardBridgeMaterialsTest.withDeckRoad(BoardBridgeSlopeTest.bridge(c, 0, levels.get(c), exits.get(c)),
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
}
