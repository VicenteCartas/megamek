/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuTreeInstancesTest {
    /** The placed object's drawn transform, as the terrain builds it. */
    private static com.badlogic.gdx.math.Matrix4 transform(BoardDecoration object) {
        var feature = new BoardScene.Feature(object.asset(), 0, 0, (float) object.rotation(), (float) object.scale(), 1, 0,
              BoardScene.FeatureKind.PROP, 0, false, object);
        return BoardFeatures.decorationTransform(new Coords(3, 4), feature, 17);
    }

    private static boolean placeable(BoardDecoration object) {
        return GpuTreeInstances.placeable(transform(object));
    }

    private static boolean instanceable(BoardDecoration object) {
        return GpuTreeInstances.instanceable(transform(object));
    }

    private static BoardDecoration object(double rotation, boolean mirror, double rotationX, double rotationY) {
        return new BoardDecoration("id", "prop", "scenery/parks/picnic-table", null, .2, -.1, rotation, mirror, 1.7,
              BoardDecoration.Placement.ground(), 0, false, rotationX, rotationY);
    }

    @Test
    void instancesHoldTurnedAndScaledObjectsButNotTiltsMirrorsOrUnevenFootprints() {
        assertTrue(placeable(object(0, false, 0, 0)));
        assertTrue(placeable(object(137, false, 0, 0)));
        assertTrue(placeable(object(-90, false, 0, 0).withStretch(new BoardDecoration.Stretch(1.4, 1.4, 3))),
              "A taller object with an even footprint keeps its vertical scale");
        assertFalse(placeable(object(30, true, 0, 0)), "A stand's species keep the counter-clockwise front face");
        assertFalse(placeable(object(30, false, 12, 0)));
        assertFalse(placeable(object(30, false, 0, -5)));
        assertFalse(placeable(object(30, false, 0, 0).withStretch(new BoardDecoration.Stretch(2, 1, 1))),
              "One horizontal scale per instance");
    }

    /** An object part's instance also holds a mirror image (its batch flips the front face), still no tilt or shear. */
    @Test
    void objectInstancesAlsoHoldMirrorImages() {
        for (double rotation : new double[] { 0, 30, 137, -90 }) {
            assertTrue(instanceable(object(rotation, true, 0, 0)), "Mirrored at " + rotation);
            assertTrue(instanceable(object(rotation, false, 0, 0)), "Unmirrored at " + rotation);
            assertTrue(GpuTreeInstances.mirrored(transform(object(rotation, true, 0, 0))));
            assertFalse(GpuTreeInstances.mirrored(transform(object(rotation, false, 0, 0))));
        }
        assertTrue(instanceable(object(30, true, 0, 0).withStretch(new BoardDecoration.Stretch(1.4, 1.4, 3))));
        assertFalse(instanceable(object(30, true, 12, 0)), "A tilted mirror image");
        assertFalse(instanceable(object(30, true, 0, 0).withStretch(new BoardDecoration.Stretch(2, 1, 1))));
    }

    private static final float DURATION = .25f;

    /** A tree one world unit across: 100 pixels a unit take level 0, 60 level 1. */
    private static GpuTreeInstances.Stand stand(float pixelsPerUnit, float now, float duration) {
        var stand = new GpuTreeInstances.Stand();
        stand.add("tree", new Matrix4(), 1);
        assertEquals(0, stand.detail(pixelsPerUnit, now, duration), "A new tree takes its level at once");
        return stand;
    }

    /** Each tree's dissolve in a level's run: 0 at rest, else when it began, negative at the level it leaves. */
    private static float[] dissolves(GpuTreeInstances.Stand stand, int level) {
        FloatArray run = stand.levels("tree")[level];
        float[] result = new float[run.size / GpuTreeInstances.STRIDE];
        for (int i = 0; i < result.length; i++) { result[i] = run.get((i + 1) * GpuTreeInstances.STRIDE - 1); }
        return result;
    }

    @Test
    void aTreeDrawsAtBothLevelsWhileItDissolvesThenAtItsNewOne() {
        var stand = stand(100, 1, DURATION);
        assertArrayEquals(new float[] { 0 }, dissolves(stand, 0));
        assertEquals(1.25f, stand.detail(60, 1, DURATION));
        assertArrayEquals(new float[] { -1 }, dissolves(stand, 0));
        assertArrayEquals(new float[] { 1 }, dissolves(stand, 1));
        assertEquals(1.25f, stand.detail(60, 1.2f, DURATION), "Still dissolving");
        assertArrayEquals(new float[] { -1 }, dissolves(stand, 0));
        assertEquals(0, stand.detail(60, 1.25f, DURATION));
        assertArrayEquals(new float[0], dissolves(stand, 0));
        assertArrayEquals(new float[] { 0 }, dissolves(stand, 1));
    }

    @Test
    void aDissolvingTreeHoldsItsLevelUntilItsDissolveEnds() {
        var stand = stand(100, 1, DURATION);
        stand.detail(60, 1, DURATION);
        // Back toward level 0 while 40 % into level 1: no change until the dissolve ends, then a dissolve back.
        assertEquals(1.25f, stand.detail(100, 1.1f, DURATION));
        assertArrayEquals(new float[] { -1 }, dissolves(stand, 0));
        assertArrayEquals(new float[] { 1 }, dissolves(stand, 1));
        assertEquals(1.5f, stand.detail(100, 1.25f, DURATION));
        assertArrayEquals(new float[] { 1.25f }, dissolves(stand, 0));
        assertArrayEquals(new float[] { -1.25f }, dissolves(stand, 1));
    }

    /** A larger tree never takes a coarser level than a smaller one, so it waits for a smaller one that holds. */
    @Test
    void largerTreesWaitForASmallerOneThatHolds() {
        var stand = new GpuTreeInstances.Stand();
        stand.add("tree", new Matrix4(), 1);
        stand.add("tree", new Matrix4(), .9f);
        stand.detail(100, 1, DURATION);
        // 75 pixels a unit: the smaller tree (67.5 px) leaves level 0, the larger (75 px) stays.
        stand.detail(75, 1, DURATION);
        assertArrayEquals(new float[] { 0, -1 }, dissolves(stand, 0));
        assertArrayEquals(new float[] { 1 }, dissolves(stand, 1));
        // 40 pixels a unit asks level 2 of both; the smaller holds level 1, so the larger goes no further than level 1.
        stand.detail(40, 1.1f, DURATION);
        assertArrayEquals(new float[] { -1.1f, -1 }, dissolves(stand, 0));
        assertArrayEquals(new float[] { 1.1f, 1 }, dissolves(stand, 1));
        assertArrayEquals(new float[0], dissolves(stand, 2));
    }

    @Test
    void withoutADurationATreeSwitchesAtOnce() {
        var stand = stand(100, 1, 0);
        assertEquals(0, stand.detail(60, 1, 0));
        assertArrayEquals(new float[0], dissolves(stand, 0));
        assertArrayEquals(new float[] { 0 }, dissolves(stand, 1));
    }
}
