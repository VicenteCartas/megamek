/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GpuModelLightTest {
    @Test
    void explicitOffKeepsGlassDiffuseAndOverridesDefaultSignalLighting() {
        var off = new GpuModelLight(0, 0, 1);
        var material = new Material("Amber", off);
        GpuTrafficSignal.mark("scenery/road/traffic-signal", material);
        assertEquals(off, material.get(GpuModelLight.class, GpuModelLight.TYPE));
        var shader = mock(DefaultShader.class);
        GpuModelLight.register(shader, () -> .25);
        var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
        verify(shader).register(eq("u_modelLight"), setter.capture());
        var target = mock(BaseShader.class);
        setter.getValue().set(target, 4, new Renderable(), material.copy());
        verify(target).set(4, 1f, 0f, 1f);
    }

    @Test
    void nightWindowsFollowAtmosphereAndFrozenViewsWithoutRebuildingMaterials() {
        var shader = mock(DefaultShader.class);
        GpuModelLight.register(shader, () -> 0);
        var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
        verify(shader).register(eq("u_modelLight"), setter.capture());
        var target = mock(BaseShader.class);
        var draw = new Renderable();
        var window = new GpuModelLight(1, 0, 1, true);
        var environment = new GpuEnvironmentLighting();
        environment.set(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0, 2.5f, 0, 0)));
        var frozenDay = environment.copy();
        int hash = window.hashCode();
        setter.getValue().set(target, 4, draw, new Material(window, environment));
        verify(target).set(4, 1f, 0f, 1f);

        environment.set(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(0, 0, 0, 2.5f, 0, 0)));
        clearInvocations(target);
        setter.getValue().set(target, 4, draw, new Material(window.copy(), environment));
        verify(target).set(4, 1f, 1f, 1f);
        assertEquals(hash, window.hashCode(), "A clock change must not invalidate material/instance batches");
        clearInvocations(target);
        setter.getValue().set(target, 4, draw, new Material(window, frozenDay));
        verify(target).set(4, 1f, 0f, 1f);

        clearInvocations(target);
        setter.getValue().set(target, 4, draw, new Material(new GpuModelLight(2, 0, 1), frozenDay));
        verify(target).set(4, 1f, 2f, 1f);
    }

    @Test
    void steadyAndBlinkingLightsUseMaterialSettingsWithoutChangingBatchKeys() {
        var shader = mock(DefaultShader.class);
        double[] seconds = { 0 };
        GpuModelLight.register(shader, () -> seconds[0]);
        var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
        verify(shader).register(eq("u_modelLight"), setter.capture());
        var target = mock(BaseShader.class);
        var draw = new Renderable();
        // Intensity, period, off diffuse, sample time, expected emission, expected diffuse.
        for (float[] sample : new float[][] {
              { 2, 0, 1, 7.75f, 2, 1 }, // A steady lamp ignores the clock.
              { 0, 0, .2f, 7.75f, 0, .2f }, // A switched-off lens.
              { 3, 2, 1, .75f, 3, 1 },
              { 3, 2, 1, 1, 0, 1 }, // Emission alone can blink; the painted surface retains its albedo.
              { 3, 2, 1, 2, 3, 1 }
        }) {
            draw.material = GpuTreeInstances.instancedMaterial(new Material("Lamp",
                  new GpuModelLight(sample[0], sample[1], sample[2])));
            int hash = draw.material.hashCode();
            seconds[0] = sample[3];
            clearInvocations(target);
            setter.getValue().set(target, 4, draw, draw.material);
            verify(target).set(4, 1f, sample[4], sample[5]);
            assertEquals(hash, draw.material.hashCode());
        }
    }

    @Test
    void copiedLightsBatchTogetherButDifferentSettingsRemainSeparate() {
        var material = new Material("Lamp", new GpuModelLight(1, 1, .06f));
        var first = GpuTreeInstances.instancedMaterial(material);
        var copy = GpuTreeInstances.instancedMaterial(new Material(material));
        assertTrue(first.same(copy, true));
        assertEquals(first.hashCode(), copy.hashCode());
        for (var light : new GpuModelLight[] {
              new GpuModelLight(2, 1, .06f), new GpuModelLight(1, 2, .06f), new GpuModelLight(1, 1, 1),
              new GpuModelLight(1, 1, .06f, true)
        }) {
            var other = GpuTreeInstances.instancedMaterial(new Material("Lamp", light));
            assertFalse(first.same(other, true), "Different light controls cannot share an instanced draw");
            assertTrue(first.compareTo(other) != 0);
        }
    }
}
