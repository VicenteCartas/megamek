/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.JsonReader;
import org.junit.jupiter.api.Test;

/** Authored container LODs must survive the real loader, its vertex budget and colour-slot conversion. */
class ContainerAssetLodTest {
    private static final List<String> MODELS = List.of("container", "container-stack-2", "container-stack-3",
          "foliage-rows", "foliage-scattered", "light-rows", "heavy-rows", "ultra-rows",
          "light-yard-gantry", "heavy-yard-gantry", "ultra-yard-gantry", "light-quay-crane",
          "light-quay-crane-mirrored", "quay-crane", "reach-stacker", "straddle-carrier", "yard-truck");

    @Test
    void everyContainerAssetHasThreeProgressivelyCheaperLevelsWithStablePlacementAndPaint() {
        File root = new File(megamek.common.Configuration.dataDir(), "models");
        var layouts = new JsonReader().parse(new FileHandle(new File(root, "board/scenery/layouts.json")));
        for (String name : MODELS) {
            boolean composition = layouts.has("yard/" + name);
            var file = new File(root, "board/scenery/seaport/yard/" + name + ".glb");
            assertEquals(3, RigidGlb.levels(file), name);
            var levels = RigidGlb.loadLods(new FileHandle(file), root.toPath());
            assertNotSame(levels.get(0), levels.get(1), name);
            assertNotSame(levels.get(1), levels.get(2), name);
            assertSame(levels.get(2), levels.get(3), "The final level is the supported fallback: " + name);
            var first = (RigidGlb.Data) levels.getFirst();
            var placement = bounds(first);
            int previous = Integer.MAX_VALUE;
            for (int level = 0; level < 3; level++) {
                var data = (RigidGlb.Data) levels.get(level);
                int triangles = triangles(data);
                assertTrue(triangles > 0 && triangles < previous, name + " LOD" + level);
                if (composition) {
                    assertTrue(triangles <= new int[] { 3000, 2000, 1100 }[level],
                          "The budget applies to the complete yard mesh: " + name + " LOD" + level);
                }
                previous = triangles;
                assertEquals(first.colourSlots, data.colourSlots, "Paint presets survive distance changes: " + name);
                // Photographic vehicles cut their railings and ladders out of their picture; a paint mask stays opaque.
                for (String mask : data.paintMasks) {
                    assertFalse(data.alphaTests.containsKey(mask), "The far atlas's paint mask is not transparency: " + name);
                }
                var box = bounds(data);
                assertTrue(box.min.dst(placement.min) < .5f && box.max.dst(placement.max) < .5f,
                      "LOD bounds keep the same placement and silhouette: " + name);
                assertTrue(data.slots.length <= 65535, name);
                if (!data.colourSlots.isEmpty()) {
                    for (int slot = 1; slot <= data.colourSlots.size(); slot++) {
                        boolean used = false;
                        for (byte value : data.slots) { used |= value == slot; }
                        assertTrue(used, "Every container colour remains selectable: " + name + " slot " + slot);
                    }
                }
            }
            var far = (RigidGlb.Data) levels.get(2);
            if (!composition) {
                assertTrue(containerTriangles(levels.get(1)) * 4 <= containerTriangles(first),
                      "Prop LOD1 must remove at least 75% of container triangles, excluding machinery: " + name);
            }
            assertTrue(far.paintMasks.contains("containers-far"), name);
            assertNotNull(far.surfaces.get("containers-far").map(), "Baked roughness/metalness loads: " + name);
        }
    }

    private static int triangles(ModelData data) {
        int result = 0;
        for (var mesh : data.meshes) { for (var part : mesh.parts) { result += part.indices.length / 3; } }
        return result;
    }

    private static int containerTriangles(ModelData data) {
        int result = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                if (part.id.contains("-containers")) { result += part.indices.length / 3; }
            }
        }
        return result;
    }

    private static BoundingBox bounds(ModelData data) {
        var result = new BoundingBox();
        for (var mesh : data.meshes) {
            for (int at = 0; at < mesh.vertices.length; at += RigidGlb.STRIDE) {
                result.ext(new Vector3(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2]));
            }
        }
        return result;
    }
}
