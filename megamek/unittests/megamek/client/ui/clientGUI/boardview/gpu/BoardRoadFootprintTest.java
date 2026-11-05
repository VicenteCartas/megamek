/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

import org.junit.jupiter.api.Test;

class BoardRoadFootprintTest {
    @Test
    void constantWidthCurvesJunctionsAndFlatEndCapsMatchTheirSharedClearance() {
        for (int exits : new int[] { 0, 1, 3, 5, 9, 18, 21, 27 }) {
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            for (float margin : new float[] { -3, 0, BoardRoad.SHOULDER }) {
                var footprint = road.footprint(margin);
                for (float x = -48.37f; x <= 48; x += 1) {
                    for (float y = -48.21f; y <= 48; y += 1) {
                        float distance = road.distance(x, y) - margin;
                        // AWT rounds circular arcs to cubic segments. Allow only a small fraction of a mask texel
                        // at the boundary; preserve the whole interior, shoulders, joins and flat terminal cuts.
                        if (Math.abs(distance) <= .015f) { continue; }
                        assertTrue(footprint.contains(x, y) == (distance < 0),
                              "Footprint and clearance disagree: exits=" + exits + " margin=" + margin + " at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void callersCanTrimACachedFootprintWithoutChangingAnotherPatchOrMargin() {
        var road = BoardRoad.clearance(BoardScenes.CENTER, 21);
        Area original = road.footprint(0), shoulder = road.footprint(BoardRoad.SHOULDER);
        var trimmed = road.footprint(0);
        trimmed.reset();
        var widened = road.footprint(BoardRoad.SHOULDER);
        widened.add(new Area(new Rectangle2D.Float(-100, -100, 200, 200)));

        assertFalse(road.footprint(0).isEmpty());
        original.exclusiveOr(road.footprint(0));
        shoulder.exclusiveOr(road.footprint(BoardRoad.SHOULDER));
        assertTrue(original.isEmpty(), "An individual patch may not mutate the cached road core");
        assertTrue(shoulder.isEmpty(), "An individual patch may not mutate a cached shoulder");
    }
}
