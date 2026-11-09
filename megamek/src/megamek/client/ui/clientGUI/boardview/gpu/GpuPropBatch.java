/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelCache;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.ShortArray;

/**
 * Combines the visible chunks' opaque colour/texture props. Sources must be the chunks' persistent cached renderables:
 * geometry, transforms and materials stay fixed until a chunk replaces those renderables. Source meshes and
 * materials remain borrowed; this helper owns only the meshes of its spatial pages. Supply hidden chunks too:
 * camera movement selects cached index ranges, while replacement sources invalidate only their own page.
 */
final class GpuPropBatch implements Disposable {
    static final int CHUNKS_PER_PAGE = GpuMeshPage.CHUNKS_PER_PAGE;
    private static final float[] IDENTITY = new Matrix4().val;
    private final Map<Integer, GpuMeshPage> pages = new HashMap<>();
    private final Array<Renderable> source = new Array<>();
    private final Array<Renderable> separate = new Array<>();
    private boolean enabled = true;
    private long rebuilds;

    void setEnabled(boolean value) { enabled = value; }

    void begin() {
        source.clear();
        separate.clear();
        if (enabled) { pages.values().forEach(GpuMeshPage::begin); }
    }

    void add(RenderableProvider props, int pageId, boolean visible) {
        // Chunk caches supply persistent renderables and do not need a temporary renderable pool.
        source.clear();
        props.getRenderables(source, null);
        add(source, pageId, visible);
    }

    /** Cutaway caches change with the hovered floor; draw them without replacing a whole static page. */
    void addDynamic(RenderableProvider props) {
        props.getRenderables(separate, null);
    }

    void add(Array<Renderable> parts, int pageId, boolean visible) {
        for (Renderable value : parts) {
            if (enabled && eligible(value)) {
                pages.computeIfAbsent(pageId, key -> new GpuMeshPage()).add(value, visible);
            } else if (visible) {
                separate.add(value);
            }
        }
    }

    /** Pages merge until {@code deadline} (System.nanoTime), the frame's share for every page (GpuMeshPage.advance). */
    void render(ModelBatch batch, Environment environment, long deadline) {
        if (enabled) {
            for (GpuMeshPage page : pages.values()) {
                if (page.advance(deadline)) { rebuilds++; }
                page.render(batch, environment);
            }
        }
        for (Renderable value : separate) {
            value.environment = environment;
            batch.render(value);
        }
    }

    /**
     * The maps a board material's .tres gives a prop (RigidGlb.boardSurface, GpuAssets.finish). The merge keeps each
     * material whole and the model shader takes its tangent frame from screen derivatives, so they batch as colour does.
     */
    private static final long BOARD_MAPS = TextureAttribute.Normal | TextureAttribute.Ambient | TextureAttribute.Emissive
          | TextureAttribute.Bump;

    private static boolean eligible(Renderable value) {
        long mask = value.material.getMask();
        return value.bones == null && !value.meshPart.mesh.isInstanced()
              && value.meshPart.primitiveType == GL20.GL_TRIANGLES && value.meshPart.size > 0
              && value.meshPart.mesh.getNumIndices() > 0 && Arrays.equals(value.worldTransform.val, IDENTITY)
              && ((mask & ~(ColorAttribute.Diffuse | TextureAttribute.Diffuse | GpuModelMaterial.TYPE
                    | GpuLinearColors.TYPE | GpuBuildingPaint.TYPE | BOARD_MAPS)) == 0 || GpuTerrain.batchableSculpt(value.material));
    }

    /**
     * A page's merge, a source range at a time. Chunk caches already contain world-space vertices: each range's
     * referenced vertices are appended as they are and its indices renumbered, into meshes of up to 65536 vertices with
     * one part per run of a material. A frame continues it for its share of time only (GpuMeshPage.advance). A range
     * placed by a transform (a chunk's props, #cache) is appended in world space.
     */
    static final class Merge implements Disposable {
        final Array<Renderable> merged = new Array<>();
        final Array<GpuMeshPage.Range> ranges = new Array<>();
        private final Array<Renderable> ordered;
        private final FloatArray points = new FloatArray(), vertices = new FloatArray();
        private final ShortArray indices = new ShortArray(), elements = new ShortArray();
        // Per vertex of the current range's span, its index in the mesh being filled, or -1 where unreferenced.
        private final IntArray renumbered = new IntArray();
        private final BoundingBox bounds = new BoundingBox();
        private final Matrix3 turn = new Matrix3();
        private final Vector3 point = new Vector3();
        private VertexAttributes attributes;
        private Material material;
        private Renderable combined;
        // The next source range, and the first part of the mesh being filled.
        private int next, first;

        Merge(Array<Renderable> source) {
            ordered = new Array<>(source);
            new ModelCache.Sorter().sort(null, ordered);
        }

        /** Merges at least one range, and more until the deadline (System.nanoTime); true once all are merged. */
        boolean step(long deadline) {
            try {
                do {
                    if (next == ordered.size) {
                        flush();
                        return true;
                    }
                    append(ordered.get(next++));
                } while (System.nanoTime() < deadline);
                return false;
            } catch (RuntimeException | Error failure) {
                dispose();
                throw failure;
            }
        }

        private void append(Renderable value) {
            Mesh mesh = value.meshPart.mesh;
            // Only the referenced vertex span, reusing scratch buffers instead of retaining whole chunk arrays.
            copyVertices(mesh, value.meshPart.offset, value.meshPart.size, points, indices);
            int stride = mesh.getVertexSize() / Float.BYTES, span = points.size / stride;
            // A sparse range's span can hold many vertices it does not reference: append only those it does.
            int[] index = renumbered.setSize(span);
            Arrays.fill(index, 0, span, -1);
            int referenced = 0;
            for (int i = 0; i < indices.size; i++) {
                int vertex = Short.toUnsignedInt(indices.items[i]);
                if (index[vertex] < 0) { index[vertex] = 0; referenced++; }
            }
            if (attributes == null || !attributes.equals(mesh.getVertexAttributes())
                  || vertices.size / stride + referenced > 65536) {
                flush();
                attributes = mesh.getVertexAttributes();
                material = null;
            }
            // Level tops keep their own program, though their attributes are the same (GpuFamilyPrograms.flat).
            if (material == null || !material.same(value.material, true)
                  || GpuFamilyPrograms.isFlat(material) != GpuFamilyPrograms.isFlat(value.material)) {
                combined = new Renderable();
                combined.material = material = value.material;
                combined.meshPart.primitiveType = GL20.GL_TRIANGLES;
                combined.meshPart.offset = elements.size;
                merged.add(combined);
                bounds.inf();
            }
            int position = attributes.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
            boolean placed = !Arrays.equals(value.worldTransform.val, IDENTITY);
            // Its normals, tangents and binormals turn with it, as MeshBuilder turns them.
            if (placed) { turn.set(value.worldTransform).inv().transpose(); }
            int[] turned = placed ? directions(attributes) : new int[0];
            for (int vertex = 0, slot = vertices.size / stride; vertex < span; vertex++) {
                if (index[vertex] < 0) { continue; }
                index[vertex] = slot++;
                int at = vertices.size + position;
                vertices.addAll(points.items, vertex * stride, stride);
                float[] added = vertices.items;
                if (placed) {
                    point.set(added[at], added[at + 1], added[at + 2]).mul(value.worldTransform);
                    added[at] = point.x;
                    added[at + 1] = point.y;
                    added[at + 2] = point.z;
                    for (int direction : turned) {
                        int from = at - position + direction;
                        point.set(added[from], added[from + 1], added[from + 2]).mul(turn).nor();
                        added[from] = point.x;
                        added[from + 1] = point.y;
                        added[from + 2] = point.z;
                    }
                }
                bounds.ext(added[at], added[at + 1], added[at + 2]);
            }
            int offset = elements.size;
            short[] added = elements.ensureCapacity(indices.size);
            for (int i = 0; i < indices.size; i++) {
                added[offset + i] = (short) index[Short.toUnsignedInt(indices.items[i])];
            }
            elements.size += indices.size;
            combined.meshPart.size = elements.size - combined.meshPart.offset;
            bounds.getCenter(combined.meshPart.center);
            bounds.getDimensions(combined.meshPart.halfExtents).scl(.5f);
            combined.meshPart.radius = combined.meshPart.halfExtents.len();
            ranges.add(new GpuMeshPage.Range(value, combined, offset, indices.size));
        }

        /** The float offsets of the layout's normal, tangent and binormal: what turns with a placed part. */
        private static int[] directions(VertexAttributes layout) {
            IntArray result = new IntArray(3);
            for (int usage : new int[] { VertexAttributes.Usage.Normal, VertexAttributes.Usage.Tangent,
                  VertexAttributes.Usage.BiNormal }) {
                var attribute = layout.findByUsage(usage);
                if (attribute != null) { result.add(attribute.offset / Float.BYTES); }
            }
            return result.toArray();
        }

        /** The parts from first on share one new mesh of the appended vertices and indices. */
        private void flush() {
            if (elements.size > 0) {
                int count = vertices.size / (attributes.vertexSize / Float.BYTES);
                Mesh mesh = new Mesh(true, count, elements.size, attributes);
                mesh.setVertices(vertices.items, 0, vertices.size);
                mesh.setIndices(elements.items, 0, elements.size);
                for (int i = first; i < merged.size; i++) { merged.get(i).meshPart.mesh = mesh; }
            }
            vertices.clear();
            elements.clear();
            first = merged.size;
        }

        @Override
        public void dispose() {
            disposeMeshes(merged);
            ranges.clear();
        }
    }

    /** Read only an indexed range's CPU vertex span; neither operation reads back from the GPU. */
    static void copyVertices(Mesh mesh, int offset, int count, FloatArray points, ShortArray indices) {
        short[] selected = indices.setSize(count);
        mesh.getIndices(offset, count, selected, 0);
        int first = 65535, last = 0;
        for (int i = 0; i < count; i++) {
            int vertex = Short.toUnsignedInt(selected[i]);
            first = Math.min(first, vertex);
            last = Math.max(last, vertex);
        }
        int stride = mesh.getVertexSize() / Float.BYTES;
        int floats = (last - first + 1) * stride;
        mesh.getVertices(first * stride, floats, points.setSize(floats), 0);
        for (int i = 0; i < count; i++) { selected[i] = (short) (Short.toUnsignedInt(selected[i]) - first); }
    }

    /** Counts completed page merges, which source replacement causes, never visibility changes. */
    long rebuilds() { return rebuilds; }

    /** Whether a page the last frame showed has a merge still to make (GpuMeshPage#needsBuild). */
    boolean merging() { return enabled && pages.values().stream().anyMatch(GpuMeshPage::needsBuild); }

    /**
     * Packs the instances {@code submit} adds into tightly sized meshes in world space for destination, a part per run
     * of a material; source meshes remain borrowed. Their indexed triangles go through this class's own merge: libGDX's
     * ModelCache copies a part's whole mesh and looks every index up in a hash map (45 ms for the 45 rubble piles of a
     * city chunk after a strike, in the frame that finished the chunk). Anything else packs through ModelCache.
     */
    static void cache(Array<Renderable> destination, Consumer<List<ModelInstance>> submit) {
        List<ModelInstance> instances = new ArrayList<>();
        submit.accept(instances);
        Array<Renderable> parts = GpuTerrainDepth.snapshot(instances);
        boolean triangles = true;
        for (int i = parts.size - 1; i >= 0; i--) {
            Renderable part = parts.get(i);
            // ModelCache leaves skinned parts out.
            if (part.bones != null) { parts.removeIndex(i); continue; }
            triangles &= part.meshPart.primitiveType == GL20.GL_TRIANGLES && part.meshPart.mesh.getNumIndices() > 0;
        }
        if (triangles) {
            Merge merge = new Merge(parts);
            merge.step(Long.MAX_VALUE);
            destination.addAll(merge.merged);
            return;
        }
        ModelCache.TightMeshPool meshes = new ModelCache.TightMeshPool();
        ModelCache builder = new ModelCache(new ModelCache.Sorter(), meshes);
        try {
            builder.begin();
            instances.forEach(builder::add);
            builder.end();
            builder.getRenderables(destination, null);
        } catch (RuntimeException | Error failure) {
            meshes.dispose();
            destination.clear();
            throw failure;
        }
    }

    static void disposeMeshes(Array<Renderable> renderables) {
        Set<Mesh> meshes = new HashSet<>();
        for (Renderable value : renderables) {
            // An interrupted page builder may not have assigned the current mesh to its parts yet.
            if (value.meshPart.mesh != null) { meshes.add(value.meshPart.mesh); }
        }
        meshes.forEach(Mesh::dispose);
        renderables.clear();
    }

    @Override
    public void dispose() {
        pages.values().forEach(GpuMeshPage::dispose);
        pages.clear();
        source.clear();
        separate.clear();
    }
}
