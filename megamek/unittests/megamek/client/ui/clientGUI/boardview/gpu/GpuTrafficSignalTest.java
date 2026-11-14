/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.GdxNativesLoader;
import java.util.List;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GpuTrafficSignalTest {
    @ParameterizedTest
    @ValueSource(strings = { "traffic-signal", "traffic-signal-gantry" })
    void bothShippedSignalLevelsPreserveTheAmberMaterialAndTheirInstancedPulseMarker(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        var levels = RigidGlb.loadLods(new FileHandle(root.resolve("scenery/road/" + asset + ".glb").toFile()), root)
              .stream().distinct().toList();
        assertEquals(2, levels.size(), "The existing GLB LOD groups supply separate near and far meshes");
        for (var data : levels) {
            ModelMaterial amber = null;
            for (ModelMaterial material : data.materials) {
                if (material.id.equals("Amber")) { amber = material; }
            }
            assertNotNull(amber, "The renderer marks the actual imported material id, not a node or part name");
            assertEquals(1f, amber.diffuse.r, .000001f);
            assertEquals(.34239164f, amber.diffuse.g, .000001f);
            assertEquals(.003095975f, amber.diffuse.b, .000001f);
            assertTrue(java.util.Arrays.stream(data.meshes.first().parts).anyMatch(part -> part.id.equals(asset + "/Amber")));
            var drawn = new Material(amber.id, ColorAttribute.createDiffuse(amber.diffuse));
            GpuTrafficSignal.mark("scenery/road/" + asset, drawn);
            assertTrue(GpuTreeInstances.instancedMaterial(drawn).has(GpuModelLight.TYPE), data.id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "traffic-signal", "traffic-signal-gantry" })
    void existingPropDetailSwitchCopiesTheAmberMarkerToBothLoadedLevelsWithoutTouchingTheirModels(String asset) throws Exception {
        GdxNativesLoader.load();
        var root = Configuration.dataDir().toPath().resolve("models/board");
        var levels = RigidGlb.loadLods(new FileHandle(root.resolve("scenery/road/" + asset + ".glb").toFile()), root)
              .stream().distinct().map(GpuTrafficSignalTest::materialModel).toList();
        assertEquals(2, levels.size());
        var initial = new ModelInstance(levels.getFirst());
        GpuTrafficSignal.mark("scenery/road/" + asset, initial.getMaterial("Amber"));
        // Exercise the real Prop.detail/level path without allocating any GPU geometry.
        Class<?> type = Class.forName(GpuTerrain.class.getName() + "$Prop");
        var constructor = type.getDeclaredConstructor(Coords.class, ModelInstance.class, List.class, BoundingBox.class,
              String.class, boolean.class);
        constructor.setAccessible(true);
        Object prop = constructor.newInstance(new Coords(1, 1), initial, levels,
              new BoundingBox(new Vector3(), new Vector3(1, 1, 3)), null, true);
        var instance = type.getDeclaredField("instance"); instance.setAccessible(true);
        var detail = type.getDeclaredMethod("detail", float.class); detail.setAccessible(true);
        for (int level : new int[] { 1, 0, 1, 0 }) {
            assertEquals(true, detail.invoke(prop, level == 0 ? 200f : 20f));
            var selected = (ModelInstance) instance.get(prop);
            assertSame(levels.get(level), selected.model);
            assertTrue(GpuTreeInstances.instancedMaterial(selected.getMaterial("Amber")).has(GpuModelLight.TYPE));
            for (Model shared : levels) {
                assertFalse(shared.getMaterial("Amber").has(GpuModelLight.TYPE), "Both asset models remain immutable");
            }
        }
    }

    /** Imported material identities on libGDX nodes; Prop's level copy needs no native mesh buffers. */
    private static Model materialModel(ModelData data) {
        var model = new Model();
        var node = new Node(); node.id = data.id;
        for (ModelMaterial source : data.materials) {
            var material = new Material(source.id, ColorAttribute.createDiffuse(source.diffuse));
            model.materials.add(material);
            var part = new NodePart(); part.meshPart = new MeshPart(); part.material = material;
            node.parts.add(part);
        }
        model.nodes.add(node);
        model.calculateTransforms();
        return model;
    }

    @Test void onlyTheSignalAmberLensIsMarkedAndItsCachedCopiesKeepBatchIdentity() {
        var original = new Material("Amber", ColorAttribute.createDiffuse(Color.WHITE));
        var placed = new Material(original);
        GpuTrafficSignal.mark("scenery/road/traffic-signal", placed);
        var cached = new Material(placed);
        var instanced = GpuTreeInstances.instancedMaterial(cached);
        assertFalse(original.has(GpuModelLight.TYPE), "The GLB library's material remains untouched");
        assertTrue(cached.has(GpuModelLight.TYPE));
        assertTrue(instanced.has(GpuModelLight.TYPE), "The actual instanced draw retains the animated lens marker");
        var another = GpuTreeInstances.instancedMaterial(new Material(placed));
        assertTrue(instanced.same(another, true), "All signal copies can share one instanced batch");
        assertEquals(instanced.hashCode(), another.hashCode());
        var gantry = new Material(original);
        GpuTrafficSignal.mark("scenery/road/traffic-signal-gantry", gantry);
        assertTrue(GpuTreeInstances.instancedMaterial(gantry).has(GpuModelLight.TYPE),
              "Overhead signal heads use the same amber animation as roadside poles");
        var red = new Material("Red");
        GpuTrafficSignal.mark("scenery/road/traffic-signal", red);
        assertFalse(red.has(GpuModelLight.TYPE));
        GpuTrafficSignal.mark("scenery/road/stop-sign", original);
        assertFalse(original.has(GpuModelLight.TYPE), "Other assets cannot animate merely by using the same material name");
    }

    @Test void cachedDrawReadsBothHalvesOfTheSharedClockAndResetsForOrdinaryMaterials() {
        var shader = mock(DefaultShader.class);
        double[] seconds = { 0 };
        GpuModelLight.register(shader, () -> seconds[0]);
        var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
        verify(shader).register(eq("u_modelLight"), setter.capture());
        var target = mock(BaseShader.class);
        var lens = new Material("Amber");
        GpuTrafficSignal.mark("scenery/road/traffic-signal", lens);
        var draw = new Renderable();
        draw.material = GpuTreeInstances.instancedMaterial(lens);
        int initialHash = draw.material.hashCode();
        for (double[] sample : new double[][] { { 0, 1 }, { .499, 1 }, { .5, 0 }, { .999, 0 }, { 1, 1 }, { 7.75, 0 } }) {
            seconds[0] = sample[0];
            clearInvocations(target);
            setter.getValue().set(target, 4, draw, draw.material);
            verify(target).set(4, 1f, (float) sample[1], sample[1] == 0 ? .06f : 1f);
            assertEquals(initialHash, draw.material.hashCode(), "Time never invalidates the material-keyed instance cache");
        }
        draw.material = new Material("Housing");
        clearInvocations(target);
        setter.getValue().set(target, 4, draw, draw.material);
        verify(target).set(4, 0f, 0f, 0f);
    }
}
