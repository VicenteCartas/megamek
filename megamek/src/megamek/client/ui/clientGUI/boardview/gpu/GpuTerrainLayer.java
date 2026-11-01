/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.cliffRelief;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.Sculpt;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.TileMesh;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.TileRange;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/**
 * One layer of a terrain chunk's geometry. Collect by material before opening a mesh part: ModelBuilder has only one
 * active part at a time.
 */
final class GpuTerrainLayer {
    record Shape(Coords owner, Material material, boolean route, boolean lift, Consumer<MeshBatch> emit) { }
    final Map<Material, List<Shape>> geometry = new LinkedHashMap<>();
    Coords owner;
    boolean route;
    boolean lift;

    void add(Material material, Consumer<MeshPartBuilder> shape) {
        // Bounded legacy shapes retain the headroom previously reserved at 48,000 vertices.
        geometry.computeIfAbsent(material, key -> new ArrayList<>())
              .add(new Shape(owner, material, route, lift, batch -> shape.accept(batch.reserve(65536 - 48001))));
    }

    /** Request a mesh before each triangle; a new mesh also requires fresh shared vertex indices. */
    void addTriangles(Material material, Consumer<Supplier<MeshPartBuilder>> shape) {
        geometry.computeIfAbsent(GpuRoads.batchMaterial(material), key -> new ArrayList<>())
              .add(new Shape(owner, material, route, lift, batch -> shape.accept(() -> batch.reserve(3))));
    }

    /** Snapshot CPU mesh buffers while the GL thread still owns the source chunk. */
    void reuse(TileRange range, Material material) {
        FloatArray points = new FloatArray();
        ShortArray indices = new ShortArray();
        GpuPropBatch.copyVertices(range.mesh(), range.offset(), range.count(), points, indices);
        float[] vertices = points.toArray();
        var mask = material.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
        if (mask != null) { GpuRoads.relocate(vertices, mask, range.mesh().getVertexAttributes()); }
        short[] elements = indices.toArray();
        int count = vertices.length / (range.mesh().getVertexSize() / Float.BYTES);
        geometry.computeIfAbsent(GpuRoads.batchMaterial(material), key -> new ArrayList<>()).add(new Shape(owner, material, range.route(), range.lift(), batch -> {
            MeshPartBuilder mesh = batch.reserve(count);
            int base = mesh.lastIndex() + 1;
            // MeshBuilder.addMesh uses a static remapping table, so it cannot run on multiple workers.
            // This range is already indexed: append its vertices and adjust indices directly.
            mesh.vertex(vertices);
            for (int i = 0; i < elements.length; i += 3) {
                mesh.index((short) (base + Short.toUnsignedInt(elements[i])),
                      (short) (base + Short.toUnsignedInt(elements[i + 1])),
                      (short) (base + Short.toUnsignedInt(elements[i + 2])));
            }
        }));
    }

    void finish(List<ModelInstance> destination) {
        prepare().upload(destination);
    }

    MeshBatch prepare() { return prepare(() -> { }, () -> { }); }

    MeshBatch prepare(Runnable check, Runnable completed) {
        MeshBatch batch = new MeshBatch();
        int shapes = 0;
        for (var entry : roadGroups(paintGroups()).entrySet()) {
            check.run();
            batch.part(entry.getKey());
            var ordered = entry.getValue();
            if (entry.getKey().has(GpuRoads.Coats.TYPE)) {
                // One draw blends its triangles in index order: base coats first, wear and paint over them.
                ordered = ordered.stream().sorted(Comparator.comparingDouble(shape -> GpuRoads.layer(shape.material()))).toList();
            }
            for (var shape : ordered) {
                if ((shapes++ & 63) == 0) { check.run(); }
                batch.owner(shape.owner(), shape.material(), shape.route(), shape.lift());
                shape.emit().accept(batch);
                completed.run();
            }
        }
        batch.endRange();
        return batch;
    }

    /**
     * One atlas page can blend all road coats in index order. With multiple pages, or coats drawn apart from the
     * merged ones (a sand verge's maps are no sculpt layer), each coat needs its own sort layer: drawing a whole
     * gravel/markings page before or after a whole asphalt page, or a verge after it, cannot preserve both.
     */
    private Map<Material, List<Shape>> roadGroups(Map<Material, List<Shape>> groups) {
        if (groups.keySet().stream().noneMatch(material -> material.has(GpuRoads.Coats.TYPE))
              || groups.keySet().stream().filter(material -> material.has(GpuRoads.Mask.TYPE)).limit(2).count() < 2) {
            return groups;
        }
        Map<Material, List<Shape>> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().has(GpuRoads.Coats.TYPE)) { result.put(entry.getKey(), entry.getValue()); continue; }
            for (var shape : entry.getValue()) {
                result.computeIfAbsent(GpuRoads.lifted(entry.getKey(), shape.material()), ignored -> new ArrayList<>())
                      .add(shape);
            }
        }
        return result;
    }

    /**
     * Authored decals in painter order, one draw per run of the same material: a decal joins the latest draw of
     * its texture unless a decal drawn after that one shares a hex with it. One draw blends in index order. The
     * draws are numbered layers ({@link GpuDecalOrder#layer}), so other chunks' equal draws can share a page.
     */
    private Map<Material, List<Shape>> paintGroups() {
        var paint = geometry.entrySet().stream().filter(entry -> entry.getKey().has(GpuDecalOrder.TYPE))
              .sorted(Comparator.comparing(entry -> entry.getKey().get(GpuDecalOrder.TYPE))).toList();
        if (paint.isEmpty()) { return geometry; }
        record Group(Material material, Set<Coords> hexes, List<Shape> shapes) { }
        List<Group> groups = new ArrayList<>();
        for (var entry : paint) {
            Material material = new Material(entry.getKey());
            material.remove(GpuDecalOrder.TYPE);
            Set<Coords> hexes = new HashSet<>();
            entry.getValue().forEach(shape -> hexes.add(shape.owner()));
            Group target = null;
            for (int i = groups.size() - 1; i >= 0 && target == null; i--) {
                if (groups.get(i).material().equals(material)) { target = groups.get(i); }
                else if (!Collections.disjoint(groups.get(i).hexes(), hexes)) { break; }
            }
            if (target == null) {
                target = new Group(material, new HashSet<>(), new ArrayList<>());
                groups.add(target);
            }
            target.hexes().addAll(hexes);
            target.shapes().addAll(entry.getValue());
        }
        Map<Material, List<Shape>> result = new LinkedHashMap<>();
        geometry.forEach((key, shapes) -> { if (!key.has(GpuDecalOrder.TYPE)) { result.put(key, shapes); } });
        for (int layer = 0; layer < groups.size(); layer++) {
            Material key = new Material(groups.get(layer).material());
            key.set(GpuDecalOrder.layer(layer));
            result.put(key, groups.get(layer).shapes());
        }
        return result;
    }

    /** Splits even a single sculpted hex across meshes without exceeding unsigned 16-bit vertex indices. */
    static final class MeshBatch {
        private record Part(MeshPart mesh, Material material) { }
        private record Range(Coords owner, Material material, int offset, int count, boolean route, boolean lift) { }
        static final class Buffer {
            final Builder builder = new Builder();
            final List<Part> parts = new ArrayList<>();
            final List<Range> ranges = new ArrayList<>();
            Buffer(VertexAttributes attributes) { builder.begin(attributes); }
        }
        /** Lends its own vertex and index arrays to wallArea, on the worker that fills it, without copying them. */
        private static final class Builder extends MeshBuilder {
            float[] vertexArray() { return getVertices(); }
            short[] indexArray() { return getIndices(); }
        }
        private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked;
        static final VertexAttributes STANDARD = MeshBuilder.createAttributes(ATTRIBUTES);
        final List<Buffer> buffers = new ArrayList<>();
        private final Map<VertexAttributes, Buffer> active = new HashMap<>();
        private Buffer current;
        private Material material;
        private Material rangeMaterial;
        private Coords owner;
        private boolean route;
        private boolean lift;
        private int rangeStart;
        private int index;

        void part(Material next) {
            endRange();
            material = next;
            VertexAttributes attributes = material.has(GpuSurfaceBlend.TYPE) ? GpuSurfaceBlend.VERTICES
                  : material.has(GpuRoads.Mask.TYPE) ? GpuTessellation.curved(material)
                        ? GpuRoads.TESSELLATED_VERTICES : GpuRoads.VERTICES : STANDARD;
            if (material.has(Sculpt.TYPE) && GpuTessellation.curved(material)) {
                attributes = GpuTessellation.contacts(attributes);
            }
            if (GpuMaterialCache.isWall(material)) { attributes = GpuMaterialCache.chartLayout(attributes); }
            current = active.computeIfAbsent(attributes, key -> {
                Buffer buffer = new Buffer(key);
                buffers.add(buffer);
                return buffer;
            });
            current.parts.add(new Part(current.builder.part("surface-" + index++, GL20.GL_TRIANGLES), material));
            rangeStart = current.builder.getNumIndices();
        }

        void owner(Coords next, Material source, boolean infrastructure, boolean machine) {
            if (!Objects.equals(owner, next) || rangeMaterial != source || route != infrastructure || lift != machine) {
                endRange(); owner = next; rangeMaterial = source; route = infrastructure; lift = machine;
            }
        }

        void endRange() {
            if (current == null) { return; }
            int end = current.builder.getNumIndices();
            if (owner != null && end > rangeStart) {
                current.ranges.add(new Range(owner, rangeMaterial, rangeStart, end - rangeStart, route, lift));
            }
            rangeStart = end;
        }

        MeshPartBuilder reserve(int vertices) {
            if (current.builder.lastIndex() + 1 + vertices > 65536) {
                active.remove(current.builder.getAttributes());
                part(material.has(GpuRoads.Coats.TYPE) ? GpuRoads.lifted(material, rangeMaterial)
                      : material.has(GpuDecalOrder.TYPE) ? continued(material) : material);
            }
            return current.builder;
        }

        /**
         * The walls' area among the ranges whose source material a cliff chunk marks (cliffRelief): each triangle's area
         * as seen from beside it, none for level ground and all of it for a vertical face. The board's sides, which face
         * past its {@code columns} by {@code rows} hexes, hide nothing and do not count.
         */
        float wallArea(int columns, int rows) {
            float width = BoardGeometry.width(), height = BoardGeometry.height(), step = width / 4;
            double area = 0;
            for (Buffer buffer : buffers) {
                float[] vertices = buffer.builder.vertexArray();
                short[] indices = buffer.builder.indexArray();
                int stride = buffer.builder.getFloatsPerVertex();
                int position = buffer.builder.getAttributes().getOffset(VertexAttributes.Usage.Position);
                for (Range range : buffer.ranges) {
                    if (!cliffRelief(range.material())) { continue; }
                    for (int i = range.offset(); i < range.offset() + range.count(); i += 3) {
                        int a = Short.toUnsignedInt(indices[i]) * stride + position;
                        int b = Short.toUnsignedInt(indices[i + 1]) * stride + position;
                        int c = Short.toUnsignedInt(indices[i + 2]) * stride + position;
                        float ux = vertices[b] - vertices[a], uy = vertices[b + 1] - vertices[a + 1];
                        float uz = vertices[b + 2] - vertices[a + 2], vx = vertices[c] - vertices[a];
                        float vy = vertices[c + 1] - vertices[a + 1], vz = vertices[c + 2] - vertices[a + 2];
                        // The cross product's horizontal part: twice the triangle's area projected on a vertical plane.
                        float x = uy * vz - uz * vy, y = uz * vx - ux * vz, side = (float) Math.sqrt(x * x + y * y);
                        if (side == 0) { continue; }
                        // A quarter of a hex from its middle toward where it faces: a board's side faces no hex.
                        float px = (vertices[a] + vertices[b] + vertices[c]) / 3 + x / side * step;
                        float py = (vertices[a + 1] + vertices[b + 1] + vertices[c + 1]) / 3 + y / side * step;
                        if (HexTile.at(px, py, width, height, columns, rows) >= 0) { area += side / 2; }
                    }
                }
            }
            return (float) area;
        }

        /** A paint layer continued in another mesh draws after its first part. */
        private static Material continued(Material layer) {
            Material continued = new Material(layer);
            continued.set(layer.get(GpuDecalOrder.class, GpuDecalOrder.TYPE).continued());
            return continued;
        }

        /** Geometry emission above is CPU-only. Only this final ownership transfer allocates GL meshes. */
        void upload(List<ModelInstance> destination) {
            for (Buffer buffer : buffers) { upload(buffer, destination); }
        }

        void upload(Buffer buffer, List<ModelInstance> destination) {
            upload(buffer, destination, null, 0);
        }

        void upload(Buffer buffer, List<ModelInstance> destination, Map<Coords, TileMesh> tiles, int layer) {
            ModelBuilder model = new ModelBuilder();
            model.begin();
            var mesh = buffer.builder.end();
            model.manage(mesh);
            for (Part part : buffer.parts) { model.part(part.mesh(), part.material()); }
            destination.add(new ModelInstance(model.end()));
            if (tiles != null) {
                for (Range range : buffer.ranges) {
                    tiles.get(range.owner()).ranges.add(new TileRange(layer, mesh, range.material(), range.offset(), range.count(),
                          range.route(), range.lift()));
                }
            }
            // MeshBuilder.end() only fills CPU buffers. Bind now so uploads share the refinement budget instead
            // of all landing on the first shadow draw after publication. No shader attributes are needed yet.
            int[] locations = new int[mesh.getVertexAttributes().size()];
            Arrays.fill(locations, -1);
            mesh.bind(null, locations, null);
            mesh.unbind(null, locations, null);
        }
    }
}
