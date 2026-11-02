/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * Owns copied geometry for a fixed spatial page; visibility selects index ranges without rebuilding meshes. When some
 * sources are replaced, as refining a chunk does, the merged page keeps drawing the others and the new ones draw as
 * they are, until the page has been unchanged for {@link #SETTLE_FRAMES} and merges again, a little each frame
 * (advance).
 */
final class GpuMeshPage implements Disposable {
    static final int CHUNKS_PER_PAGE = 4;
    /**
     * How long a frame continues its pages' merges (advance), as terrain refinement hands over its chunks: merging a
     * whole page at once stalled a frame for 55-120 ms on NVIDIA and 100-170 ms on an Iris Xe each time panning refined
     * one of its chunks.
     */
    static final long BUILD_NANOS = 2_000_000;
    /** Frames a page's sources stay unchanged before it merges again: a pan refines a chunk every few frames. */
    static final int SETTLE_FRAMES = 30;
    record Range(Renderable source, Renderable combined, int offset, int size) { }

    final Array<Renderable> current = new Array<>();
    final Array<Renderable> visible = new Array<>();
    final Array<Renderable> cached = new Array<>();
    private final Array<Renderable> previous = new Array<>();
    private final Array<Range> ranges = new Array<>();
    private final Set<Renderable> visibleSet = Collections.newSetFromMap(new IdentityHashMap<>());
    // The sources the cached meshes hold; any other draws as it is.
    private final Set<Renderable> merged = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Array<Renderable> runs = new Array<>();
    // The merge in progress, over several frames; the cached page draws meanwhile.
    private GpuPropBatch.Merge merge;
    // Whether the cached meshes hold exactly the current sources, and for how many frames those have not changed.
    private boolean prepared;
    private int stableFrames;
    int indices;

    void begin() {
        current.clear();
        visible.clear();
        visibleSet.clear();
        indices = 0;
    }

    void add(Renderable part, boolean shown) {
        // A material can finish without triangles. Its end-of-buffer offset is not a readable indexed range.
        if (part.meshPart.size == 0) { return; }
        current.add(part);
        indices += part.meshPart.size;
        if (shown) { visible.add(part); visibleSet.add(part); }
    }

    /** All sources are supplied, including hidden chunks. Only source replacement outdates the merge. */
    void update() {
        boolean changed = current.size != previous.size;
        for (int i = 0; !changed && i < current.size; i++) { changed = current.get(i) != previous.get(i); }
        if (!changed) {
            stableFrames++;
            return;
        }
        if (merge != null) { merge.dispose(); merge = null; }
        previous.clear();
        previous.addAll(current);
        prepared = false;
        stableFrames = 0;
    }

    boolean needsBuild() {
        return !prepared && current.size > 1 && !visible.isEmpty();
    }

    /** A frame's upkeep: once settled, the merge continues until the deadline (System.nanoTime); true once complete. */
    boolean advance(long deadline) {
        update();
        return needsBuild() && stableFrames >= SETTLE_FRAMES && System.nanoTime() < deadline && build(deadline);
    }

    /** Continues the merge until the deadline; true once it completes and replaces the cached page. */
    boolean build(long deadline) {
        if (merge == null) { merge = new GpuPropBatch.Merge(current); }
        boolean done;
        try {
            done = merge.step(deadline);
        } catch (RuntimeException | Error failure) {
            // The merge released what it had built; the next build starts over.
            merge = null;
            throw failure;
        }
        if (!done) { return false; }
        clearCache();
        // A merge with no fewer draws than its sources is dropped: they draw as they are.
        if (merge.merged.size < current.size) {
            cached.addAll(merge.merged);
            ranges.addAll(merge.ranges);
            for (Range range : ranges) { merged.add(range.source()); }
        } else {
            merge.dispose();
        }
        merge = null;
        prepared = true;
        return true;
    }

    void render(ModelBatch batch, Environment environment) {
        forEachVisible(part -> submit(batch, environment, part));
    }

    /** The exact same culled ranges can feed a depth pass without copying or rebuilding the page. */
    void forEachVisible(Consumer<Renderable> submit) {
        forEachVisible((part, material) -> submit.accept(part), null);
    }

    /**
     * Select a material per source range, keeping adjacent ranges with the same selection merged. Coverage changes
     * only the submitted ranges, never the stored geometry or the source materials. With no selector the whole-page
     * fast path also serves depth, water and the ordinary terrain passes.
     */
    void forEachVisible(BiConsumer<Renderable, Material> submit,
          BiFunction<Renderable, Material, Material> materials) {
        if (visible.isEmpty()) { return; }
        if (cached.isEmpty()) {
            for (Renderable part : visible) {
                submit.accept(part, materials == null ? part.material : materials.apply(part, part.material));
            }
            return;
        }
        if (materials == null && prepared && visible.size == current.size) {
            for (Renderable part : cached) { submit.accept(part, part.material); }
            return;
        }

        // A partial or outdated page keeps only its visible merged sources. Adjacent ranges of the same material/mesh
        // form one draw. Reuse the small renderable pool; no vertex or index buffers change during a pan.
        int used = 0;
        Renderable run = null;
        Renderable combined = null;
        for (Range range : ranges) {
            if (!visibleSet.contains(range.source())) { run = null; continue; }
            Material material = materials == null ? range.combined().material
                  : materials.apply(range.source(), range.combined().material);
            if (run != null && combined == range.combined()
                  && run.material == material && run.meshPart.offset + run.meshPart.size == range.offset()) {
                run.meshPart.size += range.size();
            } else {
                if (used == runs.size) { runs.add(new Renderable()); }
                run = runs.get(used++);
                run.set(combined = range.combined());
                run.material = material;
                run.meshPart.offset = range.offset();
                run.meshPart.size = range.size();
            }
        }
        for (int i = 0; i < used; i++) { submit.accept(runs.get(i), runs.get(i).material); }
        // Sources the page does not hold yet, such as a refined chunk's, draw as they are.
        for (Renderable part : visible) {
            if (!merged.contains(part)) {
                submit.accept(part, materials == null ? part.material : materials.apply(part, part.material));
            }
        }
    }

    private static void submit(ModelBatch batch, Environment environment, Renderable part) {
        part.shader = null;
        part.environment = environment;
        batch.render(part);
    }

    private void clearCache() {
        GpuPropBatch.disposeMeshes(cached);
        ranges.clear();
        merged.clear();
        runs.clear();
    }

    @Override
    public void dispose() {
        if (merge != null) { merge.dispose(); merge = null; }
        clearCache();
        previous.clear();
        prepared = false;
        stableFrames = 0;
    }
}
