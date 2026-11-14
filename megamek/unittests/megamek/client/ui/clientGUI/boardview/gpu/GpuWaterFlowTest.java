/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuWaterFlowTest {
    private static final Coords HIGH = new Coords(3, 3);

    @Test
    void riverRipplesDissipateAcrossSeveralLakeHexesAndAgreeAcrossChunks() {
        BoardScene scene = basin("");
        Coords mouth = new Coords(5, 4);
        var original = new BoardFlow.Current(0, -.1f * BoardGeometry.width() / BoardGeometry.height());
        var routed = Map.of(mouth, original);
        var spread = GpuWaterShader.surfaceCurrents(scene, routed);
        assertEquals(routed, Map.of(mouth, original), "Presentation must not mutate the routed current");
        assertEquals(original, spread.get(mouth), "The river keeps its incoming speed and direction");
        float previous = Math.abs(original.v());
        for (int distance = 1; distance <= 3; distance++) {
            var next = spread.get(mouth.translated(3, distance));
            assertTrue(next != null && next.v() < 0, "Ripples continue downstream through lake hex " + distance);
            assertTrue(Math.abs(next.v()) < previous, "The disturbance dissipates as it enters the lake");
            previous = Math.abs(next.v());
        }
        assertTrue(previous < Math.abs(original.v()) * .2f, "The distant tail must be small before it ends");
        assertEquals(null, spread.get(mouth.translated(3, 5)), "Distant lake water has no invented current");
        var left = new GpuWaterShader.Pools(scene, spread, Map.of(mouth, new BoardSurface(scene, scene.tile(mouth))));
        Coords lake = mouth.translated(3, 3);
        var right = new GpuWaterShader.Pools(scene, spread, Map.of(lake, new BoardSurface(scene, scene.tile(lake))));
        for (int distance = 0; distance <= 3; distance++) {
            Vector3 a = BoardGeometry.center(mouth.translated(3, distance), 0);
            Vector3 b = BoardGeometry.center(mouth.translated(3, distance + 1), 0);
            Vector3 before = a.cpy().lerp(b, .4999f), after = a.cpy().lerp(b, .5001f);
            assertTrue(sample(left, before).dst(sample(right, after)) < .0001f,
                  "The mouth must stay continuous across tile and chunk boundaries");
        }
        var edited = new GpuWaterShader.Pools(scene, GpuWaterShader.surfaceCurrents(scene, Map.of()), Map.of());
        assertEquals(0, sample(edited, BoardGeometry.center(lake, 0)).len(),
              "Removing the inlet removes its derived ripple tail");
    }

    @Test
    void riverRipplesRespectConnectedWaterAndNeverExtendLava() {
        Coords mouth = new Coords(5, 6), beyond = new Coords(7, 6);
        var routed = Map.of(mouth, new BoardFlow.Current(-.1f, 0));
        assertTrue(GpuWaterShader.surfaceCurrents(basin(""), routed).containsKey(beyond),
              "The control must be close enough to receive river ripples");
        for (String barrier : List.of("land", "ice", "height", "lava")) {
            assertEquals(null, GpuWaterShader.surfaceCurrents(basin(barrier), routed).get(beyond),
                  "Ripples must not pass through a " + barrier + " barrier");
        }
        var lava = Map.of(new Coords(6, 6), new BoardFlow.Current(-.025f, 0));
        assertEquals(lava, GpuWaterShader.surfaceCurrents(basin("lava"), lava), "Lava keeps its existing flow");
    }

    private static Vector2 sample(GpuWaterShader.Pools pools, Vector3 point) {
        float[] values = new float[4];
        pools.sample(point.x, point.y, values);
        return new Vector2(values[1], values[2]);
    }

    private static BoardScene basin(String barrier) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 13; x++) {
            for (int y = 0; y < 13; y++) {
                boolean wall = x == 6;
                BoardLiquid liquid = wall && barrier.equals("land") ? BoardLiquid.NONE
                      : wall && barrier.equals("lava") ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.WATER;
                tiles.add(BoardScenes.newTile(new Coords(x, y), wall && barrier.equals("height") ? 1 : 0,
                      liquid.present() ? 1 : -1, wall && barrier.equals("ice"), 0, BoardScene.Surface.SAND,
                      List.of(), List.of(), liquid, null));
            }
        }
        return new BoardScene(0, 13, 13, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void theAnimatedCurrentAcceleratesDownBothSlopesAndSettlesAfterTheFoot() {
        for (int direction = 0; direction < 6; direction++) {
            float gentle = 0;
            for (int drop : new int[] { 1, 2 }) {
                BoardScene scene = scene(direction, drop, 1);
                var pools = new GpuWaterShader.Pools(scene, BoardFlow.calculate(scene), Map.of());
                Vector2 heading = heading(direction);
                float ordinary = current(pools, direction, -.3f).dot(heading);
                assertTrue(ordinary > .05f, "The level approach has an ordinary downstream current");
                float previous = ordinary;
                for (float along : new float[] { .25f, .5f, .75f, .9f }) {
                    Vector2 velocity = current(pools, direction, along);
                    float speed = velocity.dot(heading);
                    assertTrue(speed > previous + .005f,
                          "Gain speed during descent: drop=" + drop + ", direction=" + direction + ", at=" + along
                                + ", previous=" + previous + ", speed=" + speed);
                    assertTrue(new Vector2(velocity).nor().dot(heading) > .98f, "The added current travels downhill");
                    previous = speed;
                }
                assertTrue(previous > 2 * ordinary, "Acceleration is visible, not just a tiny change");
                if (drop == 1) { gentle = previous; }
                else { assertTrue(previous > gentle * 1.2f, "A two-level chute accelerates more than a gentle slope"); }
                float foot = current(pools, direction, 1).dot(heading);
                float leaving = current(pools, direction, 1.3f).dot(heading);
                float settled = current(pools, direction, 1.45f).dot(heading);
                assertTrue(foot > leaving && leaving > settled, "Momentum eases away downstream of the foot");
                assertEquals(ordinary, settled, .001f, "The downstream flat reach returns to its ordinary current");
                assertTrue(current(pools, direction, .4999f).dst(current(pools, direction, .5001f)) < .001f,
                      "Acceleration stays continuous across the shared hex edge");
            }
        }
    }

    @Test
    void riverbedDepthDoesNotAccelerateTheWaterSurface() {
        for (int drop : new int[] { 0, 1, 2 }) {
            Vector2 first = null;
            for (int depth : new int[] { 0, 1, 4 }) {
                BoardScene scene = scene(3, drop, depth);
                var pools = new GpuWaterShader.Pools(scene, BoardFlow.calculate(scene), Map.of());
                Vector2 middle = current(pools, 3, .5f);
                if (first == null) { first = middle; }
                else { assertTrue(first.epsilonEquals(middle, .0001f), "Only surface levels affect acceleration"); }
                if (drop == 0) { assertEquals(0, middle.len(), .0001f, "A depth step in level water creates no flow"); }
            }
        }
    }

    @Test
    void adjacentChunksSampleTheSameAccelerationAndALiveEditRemovesIt() {
        int direction = 3;
        Coords low = HIGH.translated(direction);
        BoardScene scene = scene(direction, 2, 1);
        var currents = BoardFlow.calculate(scene);
        var upstream = new GpuWaterShader.Pools(scene, currents,
              Map.of(HIGH, new BoardSurface(scene, scene.tile(HIGH))));
        var downstream = new GpuWaterShader.Pools(scene, currents,
              Map.of(low, new BoardSurface(scene, scene.tile(low))));
        for (float along : new float[] { .25f, .4999f, .5f, .5001f, .75f, 1, 1.3f }) {
            assertTrue(current(upstream, direction, along).epsilonEquals(current(downstream, direction, along), .000001f),
                  "The field cannot depend on which chunk owns the surface");
        }
        BoardScene flat = scene(direction, 0, 1);
        var edited = new GpuWaterShader.Pools(flat, BoardFlow.calculate(flat), Map.of());
        assertTrue(current(upstream, direction, .75f).len() > .2f);
        assertEquals(0, current(edited, direction, .75f).len(), .0001f, "Removing the surface drop removes its acceleration");
    }

    private static Vector2 current(GpuWaterShader.Pools pools, int direction, float along) {
        Vector3 point = BoardGeometry.center(HIGH, 0).lerp(BoardGeometry.center(HIGH.translated(direction), 0), along);
        float[] sample = new float[4];
        pools.sample(point.x, point.y, sample);
        return new Vector2(sample[1], sample[2]);
    }

    private static Vector2 heading(int direction) {
        Coords low = HIGH.translated(direction);
        return new Vector2(BoardGeometry.centerX(low) - BoardGeometry.centerX(HIGH),
              BoardGeometry.centerY(low) - BoardGeometry.centerY(HIGH)).nor();
    }

    /** A stream reaching both board edges, with a level approach, one descent, and a level outlet. */
    private static BoardScene scene(int direction, int drop, int depth) {
        Map<Coords, Integer> water = new HashMap<>();
        for (int step = -7; step <= 7; step++) {
            Coords coords = HIGH.translated(step < 0 ? (direction + 3) % 6 : direction, Math.abs(step));
            water.put(coords, step <= 0 ? drop : 0);
        }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean wet = water.containsKey(coords);
                // Give the lower reach a different bed depth even when the surface is entirely level.
                int bed = wet ? depth + (water.get(coords) == 0 && !coords.equals(HIGH) ? 1 : 0) : -1;
                tiles.add(BoardScenes.newTile(coords, water.getOrDefault(coords, drop + 1), bed, false, 0,
                      BoardScene.Surface.SAND, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
