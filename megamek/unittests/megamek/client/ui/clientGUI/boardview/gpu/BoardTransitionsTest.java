/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Hex transitions: the room a step takes on both sides of its edge, the outlines that keep their edge, and support. */
class BoardTransitionsTest {
    private static final Coords HIGH = new Coords(2, 2);
    private static final Coords LOW = HIGH.translated(BoardGeometry.edgeDirection(0));

    /** Columns 0 to 2 at {@code step} levels, 3 to 5 at level 0; the high side natural or not. */
    private static BoardScene scene(int step, BoardScene.Surface high, boolean detailed) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 5; y++) {
                boolean upper = x < 3;
                tiles.add(new BoardScene.Tile(new Coords(x, y), upper ? step : 0, -1, false, 0,
                      upper ? high : BoardScene.Surface.GRASS, null, null, List.of(), List.of(),
                      BoardLiquid.NONE, null, !upper || detailed));
            }
        }
        return new BoardScene(0, 6, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The edge between HIGH and LOW: its two corners and the unit normal from HIGH toward LOW. */
    private static Vector3[] edge() {
        Vector3 a = BoardGeometry.corner(HIGH, 0, 0), b = BoardGeometry.corner(HIGH, 0, 1);
        Vector3 normal = BoardGeometry.center(LOW, 0).sub(BoardGeometry.center(HIGH, 0)).nor();
        return new Vector3[] { a, b, normal };
    }

    /**
     * How far a hex's top reaches toward LOW across the middle third of the shared edge, in metres from the edge: the
     * outermost surface point for the high hex, the innermost for the low one.
     */
    private static float reach(BoardScene scene, Coords coords, boolean outermost) {
        Vector3[] edge = edge();
        Vector3 along = new Vector3(edge[1]).sub(edge[0]);
        float length = along.len(), result = outermost ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        along.scl(1 / length);
        for (BoardSurface.Face face : new BoardSurface(scene, scene.tile(coords)).faces) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            // Sparse planar tops may have only their edge endpoints outside the middle third.
            for (Vector3 p : List.of(face.a(), face.b(), face.c(), new Vector3(face.a()).lerp(face.b(), .5f),
                  new Vector3(face.b()).lerp(face.c(), .5f), new Vector3(face.c()).lerp(face.a(), .5f))) {
                Vector3 offset = new Vector3(p).sub(edge[0]);
                float t = (offset.x * along.x + offset.y * along.y) / length;
                if (t < 1 / 3f || t > 2 / 3f) { continue; }
                float out = offset.x * edge[2].x + offset.y * edge[2].y;
                result = outermost ? Math.max(result, out) : Math.min(result, out);
            }
        }
        return result / BoardRelief.metres(1);
    }

    @Test
    void paddingSetsTheRoomEveryStepTakes() {
        for (BoardScene.Surface family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND)) {
            for (int step : new int[] { 1, 3 }) {
                BoardScene scene = scene(step, family, true);
                for (float padding : new float[] { 4, BoardGeometry.MAX_PADDING }) {
                    String name = family + " " + step + " levels, padding " + padding;
                    BoardScenes.withPadding(padding, () -> {
                        float rim = reach(scene, HIGH, true), foot = reach(scene, LOW, false), room = padding / 2;
                        assertTrue(rim < -room + 1.5f && rim > -room - 1.5f,
                              name + ": the rim stands back by half the padding, " + rim);
                        assertTrue(foot > room - 1 && foot < room + 1.5f,
                              name + ": the foot spreads out by half the padding, " + foot);
                    });
                }
            }
        }
    }

    @Test
    void stepsTakeRoomOnBothSidesOfTheirEdgeOnlyWhenTransitionsAreOn() {
        for (BoardScene.Surface family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.ROCK,
              BoardScene.Surface.SAND)) {
            for (int step : new int[] { 1, 2, 3, 5 }) {
                BoardScene scene = scene(step, family, true);
                String name = family + " " + step + " levels";
                BoardScenes.withTransitions(false, () -> assertTrue(reach(scene, HIGH, true) > -1.5f,
                      name + ": without transitions the rim follows the edge"));
                BoardScenes.withTransitions(true, () -> {
                    float rim = reach(scene, HIGH, true), foot = reach(scene, LOW, false);
                    // Dry cliffs retain wider plateau tops than gentle one/two-level slopes.
                    float room = BoardRelief.TRANSITION * (step >= 3 ? .6f : 1);
                    assertTrue(rim < -room + 1.5f && rim > -room - 1.5f, name + ": the rim stands back by the room, " + rim);
                    assertTrue(foot > room - 1 && foot < room + 1.5f, name + ": the foot spreads out by the room, " + foot);
                });
            }
        }
    }

    @Test
    void pavedCliffsAndSpecialArtworkKeepTheirOutline() {
        BoardScenes.withTransitions(true, () -> {
            // One/two-level concrete drops form fitted slopes; taller poured cliffs keep a vertical rim.
            BoardScene paving = scene(3, BoardScene.Surface.CONCRETE, true);
            assertTrue(reach(paving, HIGH, true) > -1.5f, "A paved cliff rim stays on its edge");
            BoardScene artwork = scene(1, BoardScene.Surface.GRASS, false);
            assertTrue(reach(artwork, HIGH, true) > -1.5f, "Special artwork keeps its edge");
            assertTrue(reach(artwork, LOW, false) < 3, "No slope runs out below special artwork");
        });
    }

    @Test
    void featuresSettleOnTheirOwnTop() {
        BoardScenes.withTransitions(true, () -> {
            BoardScene scene = scene(2, BoardScene.Surface.GRASS, true);
            BoardSurface surface = new BoardSurface(scene, scene.tile(HIGH));
            Vector3[] edge = edge();
            Vector3 middle = new Vector3(edge[0]).lerp(edge[1], .5f).mulAdd(edge[2], -BoardRelief.metres(1));
            float[] spot = surface.relief.settle(middle.x, middle.y, BoardRelief.metres(.8f));
            assertFalse(Float.isNaN(BoardSurface.sampleHeight(surface.faces, spot[0], spot[1], Float.NaN)),
                  "A tree beside a receding rim is moved onto the top");
            Vector3 center = BoardGeometry.center(HIGH, 2);
            assertTrue(Math.hypot(spot[0] - center.x, spot[1] - center.y) < Math.hypot(middle.x - center.x,
                  middle.y - center.y), "It moves toward the anchor");
            float[] inside = surface.relief.settle(center.x + 5, center.y, BoardRelief.metres(.8f));
            assertEquals(center.x + 5, inside[0], 1e-4f, "A tree on the top stays where it is");
        });
    }

    @Test
    void aSlopeIsPickedAsTheHexWhoseFootprintHoldsIt() {
        BoardScenes.withTransitions(true, () -> {
            BoardScene scene = scene(1, BoardScene.Surface.GRASS, true);
            Vector3[] edge = edge();
            Vector3 middle = new Vector3(edge[0]).lerp(edge[1], .5f);
            for (float side : new float[] { -1, 1 }) {
                Vector3 point = new Vector3(middle).mulAdd(edge[2], side * BoardRelief.metres(2));
                Ray ray = new Ray(new Vector3(point.x, point.y, 500), new Vector3(0, 0, -1));
                assertEquals(side < 0 ? HIGH : LOW, BoardGeometry.pick(scene, ray),
                      side < 0 ? "The slope's upper half" : "The slope's lower half");
                float ground = UnitLandingSupports.ground(scene, point.x, point.y);
                assertTrue(ground > .05f * BoardGeometry.LEVEL && ground < .95f * BoardGeometry.LEVEL,
                      "Units and markers meet the slope itself, " + ground);
            }
        });
    }
}
