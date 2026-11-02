/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntArray;

/**
 * Small ranges shared by nearby chunks: opaque terrain, and authored paint, whose equal numbered draws
 * ({@link GpuDecalOrder#layer}) merge. Large meshes keep their original storage. Partial pages select
 * visible ranges in their existing buffers, preserving chunk culling. Camera movement never rebuilds a page.
 * Only replacement source ranges invalidate it. Perspective passes retain the original projected-detail uniforms.
 */
final class GpuTerrainPages implements Disposable {
    static final int CHUNKS_PER_PAGE = GpuMeshPage.CHUNKS_PER_PAGE;
    private static final int MAX_PART_INDICES = 8192;
    private static final int MAX_PAGE_INDICES = 65536;
    private final Predicate<Renderable> eligible;
    private final boolean perspective;
    private final Map<Integer, GpuMeshPage> pages = new HashMap<>();
    private final Array<Renderable> separate = new Array<>();
    private final IntArray separateRegions = new IntArray();
    private final Map<Renderable, Integer> regions = new IdentityHashMap<>();
    // Copies of parts drawn in another material this frame (render with materials): sources are never changed.
    private final GpuRenderableCopies copies = new GpuRenderableCopies();
    private boolean enabled = true, active;
    private long rebuilds;

    GpuTerrainPages(Predicate<Renderable> eligible) { this(eligible, false); }
    GpuTerrainPages(Predicate<Renderable> eligible, boolean perspective) { this.eligible = eligible; this.perspective = perspective; }

    void setEnabled(boolean value) { enabled = value; }

    void begin(Camera camera) {
        active = enabled && (perspective || camera.projection.val[Matrix4.M33] != 0);
        separate.clear();
        separateRegions.clear();
        regions.clear();
        copies.clear();
        if (active) { pages.values().forEach(GpuMeshPage::begin); }
    }

    /** Supply every chunk, including invisible ones, so visibility cannot change a page's geometry. */
    void add(Array<Renderable> parts, int pageId, boolean visible) {
        add(parts, pageId, -1, visible);
    }

    /** A material region may be smaller than the mesh page: the ground cache selects individual chunks. */
    void add(Array<Renderable> parts, int pageId, int region, boolean visible) {
        if (!active && !visible) { return; }
        GpuMeshPage page = active ? pages.computeIfAbsent(pageId, ignored -> new GpuMeshPage()) : null;
        for (Renderable part : parts) {
            part.shader = null;
            part.environment = null;
            // An empty part (a paint draw whose decal found no receiving face here) draws nothing and copies nothing.
            if (part.meshPart.size == 0) { continue; }
            if (region >= 0 && visible) { regions.put(part, region); }
            if (active && part.meshPart.mesh.getNumIndices() > 0 && part.meshPart.size % 3 == 0
                  && part.meshPart.size <= MAX_PART_INDICES
                  && page.indices + part.meshPart.size <= MAX_PAGE_INDICES && eligible.test(part)) {
                page.add(part, visible);
            } else if (visible) {
                separate.add(part);
                separateRegions.add(region >= 0 ? region : pageId);
            }
        }
    }

    /** The material a source region draws in this frame (the ground material cache's twin, GpuMaterialCache). */
    interface Materials {
        Material of(int region, Material material);
    }

    /** Pages merge until {@code deadline} (System.nanoTime); meanwhile their sources draw as GpuMeshPage describes. */
    void render(ModelBatch batch, Environment environment, long deadline) {
        render(batch, environment, deadline, null);
    }

    /**
     * As render, each source region uses the selected material. Equal adjacent ranges remain merged; mixed coverage
     * splits draws in the existing buffers. Unmerged and oversized sources make the same selection.
     */
    void render(ModelBatch batch, Environment environment, long deadline, Materials materials) {
        if (active) {
            for (var entry : pages.entrySet()) {
                GpuMeshPage page = entry.getValue();
                if (page.advance(deadline)) { rebuilds++; }
                int id = entry.getKey();
                page.forEachVisible((part, material) -> submit(batch, environment, part, material), materials == null
                      ? null : (source, material) -> materials.of(regions.getOrDefault(source, id), material));
            }
        }
        for (int i = 0; i < separate.size; i++) {
            Renderable part = separate.get(i);
            submit(batch, environment, part, materials == null ? part.material
                  : materials.of(separateRegions.get(i), part.material));
        }
    }

    private void submit(ModelBatch batch, Environment environment, Renderable part, Material material) {
        part.shader = null;
        part.environment = environment;
        if (material != part.material) { part = copies.of(part, material); }
        batch.render(part);
    }

    long rebuilds() { return rebuilds; }

    /** Whether a page the last frame showed has a merge still to make (GpuMeshPage#needsBuild). */
    boolean merging() { return active && pages.values().stream().anyMatch(GpuMeshPage::needsBuild); }

    @Override
    public void dispose() {
        pages.values().forEach(GpuMeshPage::dispose);
        pages.clear();
        separate.clear();
        separateRegions.clear();
        regions.clear();
        copies.clear();
    }
}
