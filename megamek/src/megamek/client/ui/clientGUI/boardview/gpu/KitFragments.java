/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.logging.MMLogger;

/**
 * One kit level's nodes broken ahead of any collapse, for {@link GpuDestruction}: each node's drawn parts cut together
 * into closed fragments a few metres across ({@link MeepleShatter}), the cuts running across its walls. Each part keeps
 * one range, its fragments one after another, welded within a fragment only, so that a falling part stays one instanced
 * batch and each of its vertices finds its fragment's box by its index in the batch's copy (tree-instances.glsl). Every
 * building placing a node shares its cut. Cut on a thread of its own from the kit's CPU data, installed as CPU-only
 * meshes on the render thread, which the debris batches copy their ranges from. Presentation only.
 */
final class KitFragments implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(KitFragments.class);
    /** About the side of a fragment, in kit units (36 a hex, 18 a level). */
    private static final float SIDE = 8;
    /**
     * A node's fragments hold at most this many times the vertices it has whole: the debris' vertex cost stays bounded. A
     * box a break crosses doubles, about 32 vertices more: a wall of box bays takes a slanted break and two or three
     * upright ones.
     */
    static final float VERTEX_SHARE = 3;
    /**
     * A small node, a roof plate one box, may take this many vertices more whatever its share: a building has few of
     * them, and its roof should not fall in one plate.
     */
    static final int SMALL_EXTRA = 192;
    /** A batch's copy of a part indexes its vertices with shorts. */
    private static final int MESH_VERTICES = 65_536;
    /** Walls facing one way at least this much more than across: its breaks all run across them. */
    private static final float ONE_WAY = .5f;

    /**
     * Kit levels are cut here, one at a time and below the render thread's priority, never ahead of the old path's cuts
     * (GpuCollapse), which a collapse waits for.
     */
    private static final ExecutorService CUTTER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "Kit fragment cutter");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** A drawn part's fragments: its range of the fragments' meshes and the table its batch draws them by. */
    record Part(MeshPart range, GpuInstancedMesh.Fragments fragments) { }

    /** A part's range, before its mesh exists: its mesh, first index and index count, and its fragments' table. */
    private record Range(int mesh, int offset, int count, float[] table) { }

    /** Per mesh to make: its layout, vertex records and indices. */
    private final List<VertexAttributes> layouts = new ArrayList<>();
    private final List<FloatArray> vertices = new ArrayList<>();
    private final List<ShortArray> indices = new ArrayList<>();
    private final Map<String, Range> ranges = new HashMap<>();
    /** By mesh part id, once installed. */
    private final Map<String, Part> parts = new HashMap<>();
    private final List<Mesh> meshes = new ArrayList<>();
    private volatile boolean installed;
    private int nodes, broken;

    private KitFragments() { }

    /** Cuts {@code level} on the kit cutter's thread; {@code skipped}, the materials no falling piece draws (#floor, glass). */
    static CompletableFuture<KitFragments> cutLater(RigidGlb.Data level, Set<String> skipped) {
        return CompletableFuture.supplyAsync(() -> cut(level, skipped), CUTTER).whenComplete((cut, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                LOGGER.warn(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                      ? failure.getCause() : failure, "Kit {}: its pieces fall whole, its cut failed", level.id);
            }
        });
    }

    /**
     * Each node of {@code level} as fragments, where its parts break into at least two within the bounds: at most {@link
     * GpuDestruction#FRAGMENTS}, at most {@link #VERTEX_SHARE} times its vertices (or {@link #SMALL_EXTRA} more), no part
     * over a batch's vertices, and no part another node draws too. Any thread: it reads the kit's data only.
     */
    static KitFragments cut(RigidGlb.Data level, Set<String> skipped) {
        Map<String, ModelMesh> meshOf = new HashMap<>();
        Map<String, ModelMeshPart> partOf = new HashMap<>();
        // A node part draws the first mesh part of its id, as libGDX's Model binds them. Index loops: libGDX's Array
        // reuses its iterator, and the kit's data is read by other threads too (ConnectedKit).
        for (int index = 0; index < level.meshes.size; index++) {
            ModelMesh mesh = level.meshes.get(index);
            for (ModelMeshPart part : mesh.parts) {
                if (partOf.putIfAbsent(part.id, part) == null) { meshOf.put(part.id, mesh); }
            }
        }
        List<ModelNode> all = new ArrayList<>();
        for (int index = 0; index < level.nodes.size; index++) { collect(level.nodes.get(index), all); }
        Map<String, Integer> drawn = new HashMap<>();
        for (ModelNode node : all) {
            for (ModelNodePart part : node.parts) { drawn.merge(part.meshPartId, 1, Integer::sum); }
        }
        var result = new KitFragments();
        for (ModelNode node : all) { result.add(node, meshOf, partOf, drawn, skipped); }
        return result;
    }

    private static void collect(ModelNode node, List<ModelNode> result) {
        if (node.parts != null && node.parts.length > 0) { result.add(node); }
        if (node.children != null) {
            for (ModelNode child : node.children) { collect(child, result); }
        }
    }

    private void add(ModelNode node, Map<String, ModelMesh> meshOf, Map<String, ModelMeshPart> partOf,
          Map<String, Integer> drawn, Set<String> skipped) {
        nodes++;
        List<Material> materials = new ArrayList<>();
        List<FloatArray> triangles = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        VertexAttributes layout = null;
        int whole = 0;
        for (ModelNodePart nodePart : node.parts) {
            ModelMeshPart part = partOf.get(nodePart.meshPartId);
            ModelMesh mesh = meshOf.get(nodePart.meshPartId);
            if (part == null || part.primitiveType != GL20.GL_TRIANGLES || part.indices.length < 3
                  || skipped.contains(nodePart.materialId)) { continue; }
            var attributes = new VertexAttributes(mesh.attributes);
            // One layout, no colour slots (whose colours a placement carries), no mesh part another node draws too.
            if (layout != null && !layout.equals(attributes) || RigidGlb.colourSlotted(attributes)
                  || drawn.get(nodePart.meshPartId) > 1) { return; }
            layout = attributes;
            int stride = layout.vertexSize / Float.BYTES;
            var records = new FloatArray(part.indices.length * stride);
            Set<Integer> used = new HashSet<>();
            for (short index : part.indices) {
                int vertex = Short.toUnsignedInt(index);
                records.addAll(mesh.vertices, vertex * stride, stride);
                used.add(vertex);
            }
            whole += used.size();
            // Each part a channel of its own, though two share a material.
            materials.add(new Material(nodePart.materialId));
            triangles.add(records);
            ids.add(nodePart.meshPartId);
        }
        if (layout == null) { return; }
        int stride = layout.vertexSize / Float.BYTES, position = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        var bounds = new BoundingBox().inf();
        var point = new Vector3();
        for (FloatArray records : triangles) {
            for (int at = 0; at < records.size; at += stride) {
                bounds.ext(point.set(records.items[at + position], records.items[at + position + 1], records.items[at + position + 2]));
            }
        }
        int steep = MathUtils.clamp(Math.round(Math.max(bounds.getWidth(), bounds.getHeight()) / SIDE)
              + Math.round(bounds.getDepth() / (2 * SIDE)), 0, 8);
        if (steep == 0) { return; }
        float[] walls = walls(triangles, stride, position);
        // Each part's own material object: two parts in one material weigh apart.
        Map<Material, Integer> sizes = new java.util.IdentityHashMap<>();
        for (int index = 0; index < materials.size(); index++) { sizes.put(materials.get(index), triangles.get(index).size); }
        Material body = MeepleShatter.Caps.body(materials, sizes::get);
        // Closed where a wall is closed: an open sheet's cut stays open, never filled across the room.
        var caps = new MeepleShatter.Caps(body, layout, (float) KitOutline.LEVEL, false);
        for (; steep > 0; steep--) {
            var cut = MeepleShatter.cut(materials, triangles, layout, Vector3.Z,
                  MeepleShatter.Planes.across(steep, walls[0], walls[1] < ONE_WAY), node.id.hashCode(), caps);
            if (cut.pieces() < 2) { return; }
            if (cut.pieces() > GpuDestruction.FRAGMENTS) { continue; }
            var packed = pack(cut, layout, body);
            if (packed == null) { continue; }
            int vertices = packed.stream().mapToInt(part -> part.vertices().size / stride).sum();
            if (vertices > Math.max(VERTEX_SHARE * whole, whole + SMALL_EXTRA)) { continue; }
            for (int index = 0; index < packed.size(); index++) { place(ids.get(index), layout, packed.get(index)); }
            broken++;
            return;
        }
    }

    /**
     * Which way the node's walls face, as an angle about the vertical in degrees, and how much more they face that way
     * than across it (0 alike both ways, 1 one way only): the area-weighted spread of their faces' level normals.
     */
    private static float[] walls(List<FloatArray> triangles, int stride, int position) {
        double xx = 0, xy = 0, yy = 0;
        var a = new Vector3();
        var b = new Vector3();
        var c = new Vector3();
        for (FloatArray records : triangles) {
            float[] items = records.items;
            for (int at = 0; at + 3 * stride <= records.size; at += 3 * stride) {
                a.set(items[at + position], items[at + position + 1], items[at + position + 2]);
                b.set(items[at + stride + position], items[at + stride + position + 1], items[at + stride + position + 2]).sub(a);
                c.set(items[at + 2 * stride + position], items[at + 2 * stride + position + 1], items[at + 2 * stride + position + 2]).sub(a);
                // The cross product's length is twice the area: weighted by area, its level part squared.
                b.crs(c);
                float area = b.len();
                if (area < 1e-9f) { continue; }
                double x = b.x / area, y = b.y / area;
                xx += area * x * x;
                xy += area * x * y;
                yy += area * y * y;
            }
        }
        double mean = (xx + yy) / 2, spread = Math.sqrt((xx - yy) * (xx - yy) / 4 + xy * xy);
        float facing = (float) Math.toDegrees(.5 * Math.atan2(2 * xy, xx - yy));
        return new float[] { facing, mean <= 0 ? 0 : (float) (spread / mean) };
    }

    /** A part's fragments packed: vertex records, indices from 0 and its table. */
    private record Packed(FloatArray vertices, ShortArray indices, float[] table) { }

    /**
     * Per part of the cut, its fragments one after another, each welded on its own so no two share a vertex, the caps in
     * the body's part after its own triangles; per fragment, a table entry of its box over every part and cap: centre
     * and first vertex, half size and 0. Null when a part outgrows a batch's vertices.
     */
    private static List<Packed> pack(MeepleShatter.Cut cut, VertexAttributes layout, Material body) {
        int stride = layout.vertexSize / Float.BYTES, position = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        int pieces = cut.pieces(), caps = cut.channels() - 1;
        var boxes = new BoundingBox[pieces];
        var point = new Vector3();
        for (int piece = 0; piece < pieces; piece++) {
            boxes[piece] = new BoundingBox().inf();
            for (int channel = 0; channel < cut.channels(); channel++) {
                FloatArray records = cut.triangles(piece, channel);
                for (int at = 0; at < records.size; at += stride) {
                    boxes[piece].ext(point.set(records.items[at + position], records.items[at + position + 1],
                          records.items[at + position + 2]));
                }
            }
        }
        List<Packed> result = new ArrayList<>();
        var centre = new Vector3();
        var half = new Vector3();
        for (int channel = 0; channel < caps; channel++) {
            var vertices = new FloatArray();
            var indices = new ShortArray();
            float[] table = new float[8 * pieces];
            for (int piece = 0; piece < pieces; piece++) {
                boxes[piece].getCenter(centre);
                boxes[piece].getDimensions(half).scl(.5f);
                int first = vertices.size / stride;
                table[8 * piece] = centre.x;
                table[8 * piece + 1] = centre.y;
                table[8 * piece + 2] = centre.z;
                table[8 * piece + 3] = first;
                table[8 * piece + 4] = half.x;
                table[8 * piece + 5] = half.y;
                table[8 * piece + 6] = half.z;
                Map<GeneratedMesh.Weld, Integer> welded = new HashMap<>();
                FloatArray own = cut.triangles(piece, channel);
                for (int at = 0; at < own.size; at += stride) { indices.add(GeneratedMesh.weld(own.items, at, stride, vertices, welded)); }
                if (cut.material(channel) == body) {
                    FloatArray closing = cut.triangles(piece, caps);
                    for (int at = 0; at < closing.size; at += stride) {
                        indices.add(GeneratedMesh.weld(closing.items, at, stride, vertices, welded));
                    }
                }
                if (vertices.size / stride > MESH_VERTICES - 1) { return null; }
            }
            result.add(new Packed(vertices, indices, table));
        }
        return result;
    }

    /** The part's range in the last mesh of its layout, or in a new one when it would not fit. */
    private void place(String id, VertexAttributes layout, Packed part) {
        int stride = layout.vertexSize / Float.BYTES, count = part.vertices().size / stride;
        int mesh = layouts.lastIndexOf(layout);
        if (mesh < 0 || vertices.get(mesh).size / stride + count > MESH_VERTICES) {
            layouts.add(layout);
            vertices.add(new FloatArray());
            indices.add(new ShortArray());
            mesh = layouts.size() - 1;
        }
        int base = vertices.get(mesh).size / stride, offset = indices.get(mesh).size;
        vertices.get(mesh).addAll(part.vertices());
        for (int index = 0; index < part.indices().size; index++) {
            indices.get(mesh).add((short) (Short.toUnsignedInt(part.indices().get(index)) + base));
        }
        ranges.put(id, new Range(mesh, offset, part.indices().size, part.table()));
    }

    /** Render thread, once: CPU-only meshes (no GL object) the debris batches copy their parts' ranges from. */
    void install() {
        if (installed) { return; }
        for (int index = 0; index < layouts.size(); index++) {
            var layout = layouts.get(index);
            var mesh = new Mesh(Mesh.VertexDataType.VertexArray, true,
                  vertices.get(index).size / (layout.vertexSize / Float.BYTES), indices.get(index).size, layout);
            mesh.setVertices(vertices.get(index).items, 0, vertices.get(index).size);
            mesh.setIndices(indices.get(index).items, 0, indices.get(index).size);
            meshes.add(mesh);
        }
        ranges.forEach((id, range) -> parts.put(id, new Part(new MeshPart(id, meshes.get(range.mesh()), range.offset(),
              range.count(), GL20.GL_TRIANGLES), new GpuInstancedMesh.Fragments(range.table()))));
        vertices.clear();
        indices.clear();
        installed = true;
    }

    boolean installed() { return installed; }

    /** The drawn part's fragments, by its mesh part's id; null where it falls whole. */
    Part part(MeshPart drawn) { return parts.get(drawn.id); }

    /** How many of its nodes broke, of how many: for tests and reports. */
    int broken() { return broken; }

    int nodes() { return nodes; }

    /** The installed meshes' bytes. */
    long bytes() {
        long total = 0;
        for (Mesh mesh : meshes) { total += (long) mesh.getNumVertices() * mesh.getVertexSize() + 2L * mesh.getNumIndices(); }
        return total;
    }

    @Override
    public void dispose() {
        meshes.forEach(Mesh::dispose);
        meshes.clear();
        parts.clear();
        vertices.clear();
        indices.clear();
    }
}
