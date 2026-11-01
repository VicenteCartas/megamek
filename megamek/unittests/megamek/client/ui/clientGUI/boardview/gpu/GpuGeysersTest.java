/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuGeysersTest {
    @Test
    void followsInstalledAssetStateAndItsGroundTransform() {
        Matrix4 transform = new Matrix4().setToTranslation(120, -90, 18).scale(2, 2, 2);
        var active = GpuGeysers.emitter("scenery/geysers/water-erupting", transform, .84f);
        assertNotNull(active);
        assertTrue(active.active());
        assertFalse(active.magma());
        assertEquals(new Vector3(120, -90, 19.68f), active.origin());
        assertEquals(2, active.scale());
        var dormant = GpuGeysers.emitter("scenery/geysers/water-dormant", transform, .84f);
        assertFalse(dormant.active());
        assertEquals(active.origin(), dormant.origin());
        var magma = GpuGeysers.emitter("scenery/geysers/magma", transform, 1.85f);
        assertTrue(magma.magma());
        assertFalse(magma.active());
        assertTrue(magma.origin().epsilonEquals(120, -90, 21.7f, 1e-4f), "The vent height places it");
        // Each geyser has a second look, the same state.
        assertTrue(GpuGeysers.emitter("scenery/geysers/water-erupting-b", transform, .84f).active());
        assertTrue(GpuGeysers.emitter("scenery/geysers/magma-b", transform, 1.85f).magma());
        assertNull(GpuGeysers.emitter("scenery/fluff/pool", transform, 0));
    }

    @Test
    void eachHexPaintsTheApronOfTheLookItShows() {
        for (String key : List.of("geyser_magma", "geyser_water_off", "geyser_water_on")) {
            var layout = BoardSceneryLayouts.layout("scenery/saxarba/misc/" + key);
            Set<Boolean> looks = new HashSet<>();
            for (int x = 0; x < 8; x++) {
                var features = BoardFeatures.features(layout, new Coords(x, 3), new Hex());
                String model = features.stream().filter(f -> f.decoration() == null).findFirst().orElseThrow().asset();
                String apron = features.stream().filter(f -> f.decoration() != null).findFirst().orElseThrow()
                      .decoration().asset();
                assertEquals(model.endsWith("-b"), apron.endsWith("-b"), key + " at x " + x);
                looks.add(model.endsWith("-b"));
            }
            assertEquals(2, looks.size(), "Both looks occur");
        }
    }

    @Test
    void dropletsAdvanceOnTheSharedClockAndStayWithinTheCullingEnvelope() {
        var source = GpuGeysers.emitter("scenery/geysers/water-erupting", new Matrix4(), .84f);
        Vector3 first = GpuGeysers.droplet(source, 4, .5f, 9.8f, Vector3.Zero, new Vector3());
        assertEquals(first, GpuGeysers.droplet(source, 4, .5f, 9.8f, Vector3.Zero, new Vector3()));
        assertNotEquals(first, GpuGeysers.droplet(source, 4, .9f, 9.8f, Vector3.Zero, new Vector3()));
        Vector3 sample = new Vector3();
        for (float gravity : new float[] { .16f, 1, 4 }) {
            for (int drop = 0; drop < 40; drop++) for (int frame = 0; frame < 120; frame++) {
                GpuGeysers.droplet(source, drop, frame * .071f, gravity * BoardAtmosphere.STANDARD_GRAVITY,
                      new Vector3(1, 0, 1), sample);
                assertTrue(sample.z >= source.origin().z && sample.z <= source.origin().z + 34.001f);
                assertTrue(Math.abs(sample.x) < 20 && Math.abs(sample.y) < 20);
            }
        }
    }
}
