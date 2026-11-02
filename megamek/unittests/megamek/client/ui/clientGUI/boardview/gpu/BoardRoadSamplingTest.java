/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BoardRoadSamplingTest {
    private record Point(float x, float y, float width) { }

    @Test
    void boundedSamplingMatchesExhaustiveProjectionIncludingOpenEndsAndVaryingWidths() throws Exception {
        Class<?> pointType = Class.forName(BoardRoad.class.getName() + "$Point");
        Constructor<?> constructor = pointType.getDeclaredConstructor(float.class, float.class, float.class);
        constructor.setAccessible(true);
        Method bounds = BoardRoad.class.getDeclaredMethod("sampleBounds", List.class);
        bounds.setAccessible(true);
        Method sample = BoardRoad.class.getDeclaredMethod("pathSample", float.class, float.class, List.class,
              byte[].class, int.class, int.class, bounds.getReturnType());
        sample.setAccessible(true);
        List<Method> fields = new ArrayList<>();
        for (String field : List.of("distance", "edge", "dx", "dy", "width")) {
            Method method = sample.getReturnType().getDeclaredMethod(field);
            method.setAccessible(true);
            fields.add(method);
        }
        Random random = new Random(0x71DE);
        for (int fixture = 0; fixture < 12; fixture++) {
            List<List<Point>> paths = new ArrayList<>();
            List<List<Object>> actualPaths = new ArrayList<>();
            byte[] ends = new byte[5];
            for (int p = 0; p < ends.length; p++) {
                List<Point> path = new ArrayList<>();
                List<Object> actual = new ArrayList<>();
                float cx = random.nextFloat(-80, 80), cy = random.nextFloat(-80, 80);
                if (fixture == 0) { cx = cy = 0; }
                int points = fixture == 8 ? 2 : fixture == 9 ? 7 : fixture == 10 ? 1 : 129;
                for (int i = 0; i < points; i++) {
                    double angle = i * Math.PI / 64;
                    Point point = new Point(cx + 40 * (float) Math.cos(angle), cy + 40 * (float) Math.sin(angle),
                          random.nextFloat(1, 24));
                    // Exercise the old degenerate-segment behavior too; bounds must not skip its NaN edge.
                    if (fixture == 11 && i == 64) { point = path.getLast(); }
                    path.add(point);
                    actual.add(constructor.newInstance(point.x, point.y, point.width));
                }
                paths.add(path);
                actualPaths.add(actual);
                ends[p] = (byte) random.nextInt(16);
            }
            Object index = bounds.invoke(null, actualPaths);
            for (int i = 0; i < 500; i++) {
                float x = random.nextFloat(-150, 150), y = random.nextFloat(-150, 150);
                if (i < paths.getFirst().size()) { x = paths.getFirst().get(i).x; y = paths.getFirst().get(i).y; }
                int open = i % 3 == 0 ? 0 : i % 3 == 1 ? 1 : 4, only = i % 6 - 1;
                Object value = sample.invoke(null, x, y, actualPaths, ends, open, only, i % 2 == 0 ? index : null);
                float[] actual = new float[fields.size()];
                for (int field = 0; field < actual.length; field++) { actual[field] = (float) fields.get(field).invoke(value); }
                assertArrayEquals(exhaustive(x, y, paths, ends, open, only), actual,
                      "All results retain the original segment order and exact projection arithmetic");
            }
        }
    }

    @Test
    void combinedWearRetainsHeadingAndLanePositionAcrossEveryExitPattern() {
        Random random = new Random(0xCAFE);
        for (int exits = 1; exits < 64; exits++) {
            var road = BoardRoad.clearance(BoardRoadTest.CENTER, exits);
            for (int i = 0; i < 128; i++) {
                float x = random.nextFloat(-100, 100), y = random.nextFloat(-100, 100);
                var together = road.pavedWear(x, y);
                assertEquals(road.wheelDirection(x, y), together.heading());
                assertEquals(road.wearLane(x, y), together.across());
            }
        }
    }

    /** The previous exhaustive calculation, without bounds or shortcuts, is the numerical reference. */
    private static float[] exhaustive(float x, float y, List<List<Point>> paths, byte[] ends, int open, int only) {
        float distance = Float.POSITIVE_INFINITY, edge = Float.POSITIVE_INFINITY, alongX = 0, alongY = 1, width = 0;
        for (int p = Math.max(0, only); p < (only < 0 ? paths.size() : only + 1); p++) {
            var path = paths.get(p);
            boolean first = (ends[p] & open) != 0, last = (ends[p] & open << 1) != 0;
            for (int i = 1; i < path.size(); i++) {
                Point a = path.get(i - 1), b = path.get(i);
                float dx = b.x - a.x, dy = b.y - a.y;
                float along = ((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy);
                float t = Math.clamp(along, first && i == 1 ? Float.NEGATIVE_INFINITY : 0,
                      last && i == path.size() - 1 ? Float.POSITIVE_INFINITY : 1);
                float ex = x - a.x - t * dx, ey = y - a.y - t * dy;
                float near = (float) Math.sqrt((double) ex * ex + (double) ey * ey);
                t = Math.clamp(t, 0, 1);
                edge = Math.min(edge, near - a.width - t * (b.width - a.width));
                if (near < distance) { distance = near; alongX = dx; alongY = dy; width = a.width + t * (b.width - a.width); }
            }
        }
        return new float[] { distance, edge, alongX, alongY, width };
    }
}
