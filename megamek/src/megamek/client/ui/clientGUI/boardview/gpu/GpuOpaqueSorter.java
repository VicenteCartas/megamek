/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

/** Group opaque shaders unless ground cover needs the original depth order; transparency keeps its depth ordering. */
final class GpuOpaqueSorter extends DefaultRenderableSorter {
    private boolean groupShaders = true;
    private final Map<Shader, Integer> shaderOrder = new IdentityHashMap<>();
    private final Map<Shader, Integer> groupOrder = new IdentityHashMap<>();
    private final Function<Shader, Shader> group;
    private Camera view;
    private final Vector3 leftCentre = new Vector3(), rightCentre = new Vector3();

    GpuOpaqueSorter() { this(Function.identity()); }

    /** Shaders with one {@code group} sort as one shader: their draws keep their mutual order and tie winners. */
    GpuOpaqueSorter(Function<Shader, Shader> group) { this.group = group; }

    @Override
    public void sort(Camera camera, Array<Renderable> renderables) {
        view = camera;
        groupShaders = true;
        shaderOrder.clear();
        groupOrder.clear();
        for (Renderable renderable : renderables) {
            if (renderable.material.has(GpuGroundCover.Wind.TYPE)) {
                // Grass intersects the ground at depth-buffer precision. Keep its original tie winners,
                // using one ordering for the whole pass so the comparator remains transitive.
                groupShaders = false;
                break;
            }
            // Allocation identity used to reshuffle groups when programs were recreated, changing depth ties
            // and early depth rejection. Encounter order is stable for the same submitted scene.
            shaderOrder.computeIfAbsent(renderable.shader,
                  shader -> groupOrder.computeIfAbsent(group.apply(shader), ignored -> groupOrder.size()));
        }
        super.sort(camera, renderables);
    }

    @Override
    public int compare(Renderable left, Renderable right) {
        var a = left.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        var b = right.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (a != null && a.blended && b != null && b.blended) {
            var paintA = left.material.get(GpuDecalOrder.class, GpuDecalOrder.TYPE);
            var paintB = right.material.get(GpuDecalOrder.class, GpuDecalOrder.TYPE);
            // Authored paint follows other surface coats. Mesh centres and texture batches cannot order it.
            if ((paintA == null) != (paintB == null)) { return paintA == null ? -1 : 1; }
            if (paintA != null) {
                int layer = paintA.compareTo(paintB);
                if (layer != 0) { return layer; }
            }
            boolean roadA = left.material.has(GpuRoads.Mask.TYPE), roadB = right.material.has(GpuRoads.Mask.TYPE);
            // Road coats lie on opaque support: draw their base before wear/paint, then other transparency.
            // Chunk mesh centres cannot order overlapping coats reliably, especially after batching masks.
            if (roadA != roadB) { return roadA ? -1 : 1; }
            if (roadA) {
                int layer = Float.compare(GpuRoads.layer(left.material), GpuRoads.layer(right.material));
                if (layer != 0) { return layer; }
            }
            // A lake slab's margin floats over the open water beside it: blend the ice after that water.
            boolean iceA = GpuIceShader.floating(left.material), iceB = GpuIceShader.floating(right.material);
            if (iceA != iceB) { return iceA ? 1 : -1; }
            // The default integer distance key saturates on large boards, losing transparent primitive order.
            float leftDistance = leftCentre.set(left.meshPart.center).mul(left.worldTransform).dst2(view.position);
            float rightDistance = rightCentre.set(right.meshPart.center).mul(right.worldTransform).dst2(view.position);
            return Float.compare(rightDistance, leftDistance);
        }
        if (groupShaders && (a == null || !a.blended) && (b == null || !b.blended)) {
            int shader = Integer.compare(shaderOrder.get(left.shader), shaderOrder.get(right.shader));
            if (shader != 0) { return shader; }
        }
        return super.compare(left, right);
    }
}
