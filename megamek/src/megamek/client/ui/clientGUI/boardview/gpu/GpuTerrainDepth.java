/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;

/**
 * Depth-only ranges over a finished chunk's static opaque meshes. Meshes and materials remain owned by the chunk;
 * rebuild this snapshot when its meshes, transforms or material depth state change. No vertex data is copied.
 */
final class GpuTerrainDepth implements RenderableProvider {
    private final Array<Renderable> ranges = new Array<>();
    /** Empty aerospace planes occlude artwork but never block illumination of neighboring platforms. */
    final RenderableProvider shadows = (renderables, pool) -> gather(renderables, true);

    GpuTerrainDepth(List<ModelInstance> instances) {
        for (Renderable next : snapshot(instances)) {
            Renderable previous = ranges.isEmpty() ? null : ranges.peek();
            if (previous != null && mergeable(previous) && mergeable(next)
                  && previous.meshPart.mesh == next.meshPart.mesh
                  && previous.meshPart.offset + previous.meshPart.size == next.meshPart.offset
                  && Arrays.equals(previous.worldTransform.val, next.worldTransform.val)
                  && sameState(previous, next)) {
                previous.meshPart.size += next.meshPart.size;
                extend(previous.meshPart, next.meshPart);
            } else {
                ranges.add(next);
            }
        }
    }

    /** Persistent ranges borrowing static instance meshes/materials; capture again when the owning chunk changes. */
    static Array<Renderable> snapshot(List<ModelInstance> instances) {
        Array<Renderable> result = new Array<>();
        Pool<Renderable> pool = new Pool<>() {
            @Override
            protected Renderable newObject() { return new Renderable(); }
        };
        for (ModelInstance instance : instances) { instance.getRenderables(result, pool); }
        for (int i = result.size - 1; i >= 0; i--) {
            if (result.get(i).meshPart.size == 0) { result.removeIndex(i); }
        }
        return result;
    }

    private static boolean mergeable(Renderable value) {
        if (value.bones != null || value.meshPart.mesh.isInstanced() || value.meshPart.mesh.getNumIndices() == 0
              || value.meshPart.primitiveType != GL20.GL_TRIANGLES || value.meshPart.size <= 0 || value.meshPart.size % 3 != 0
              || value.material.has(BlendingAttribute.Type) || value.material.has(FloatAttribute.AlphaTest)) { return false; }
        DepthTestAttribute depth = value.material.get(DepthTestAttribute.class, DepthTestAttribute.Type);
        // Unusual depth/write state can depend on submission order after the depth batch sorts its ranges.
        return depth == null || depth.depthMask && (depth.depthFunc == GL20.GL_LESS || depth.depthFunc == GL20.GL_LEQUAL);
    }

    private static boolean sameState(Renderable first, Renderable second) {
        if (first.material.has(GpuBackdrop.TYPE) != second.material.has(GpuBackdrop.TYPE)) { return false; }
        IntAttribute a = first.material.get(IntAttribute.class, IntAttribute.CullFace);
        IntAttribute b = second.material.get(IntAttribute.class, IntAttribute.CullFace);
        if (a != b && (a == null || b == null || a.value != b.value)) { return false; }
        DepthTestAttribute x = first.material.get(DepthTestAttribute.class, DepthTestAttribute.Type);
        DepthTestAttribute y = second.material.get(DepthTestAttribute.class, DepthTestAttribute.Type);
        // Keep absent attributes absent: the depth shader's configured defaults remain authoritative.
        return x == y || x != null && y != null && x.depthFunc == y.depthFunc && x.depthMask == y.depthMask
              && x.depthRangeNear == y.depthRangeNear && x.depthRangeFar == y.depthRangeFar;
    }

    private static void extend(MeshPart target, MeshPart added) {
        float minX = Math.min(target.center.x - target.halfExtents.x, added.center.x - added.halfExtents.x);
        float minY = Math.min(target.center.y - target.halfExtents.y, added.center.y - added.halfExtents.y);
        float minZ = Math.min(target.center.z - target.halfExtents.z, added.center.z - added.halfExtents.z);
        float maxX = Math.max(target.center.x + target.halfExtents.x, added.center.x + added.halfExtents.x);
        float maxY = Math.max(target.center.y + target.halfExtents.y, added.center.y + added.halfExtents.y);
        float maxZ = Math.max(target.center.z + target.halfExtents.z, added.center.z + added.halfExtents.z);
        target.center.set((minX + maxX) * .5f, (minY + maxY) * .5f, (minZ + maxZ) * .5f);
        target.halfExtents.set((maxX - minX) * .5f, (maxY - minY) * .5f, (maxZ - minZ) * .5f);
        target.radius = target.halfExtents.len();
    }

    @Override
    public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
        gather(renderables, false);
    }

    private void gather(Array<Renderable> renderables, boolean shadows) {
        for (Renderable range : ranges) {
            if (shadows && range.material.has(GpuBackdrop.TYPE)) { continue; }
            range.shader = null;
            range.environment = null;
            renderables.add(range);
        }
    }
}
