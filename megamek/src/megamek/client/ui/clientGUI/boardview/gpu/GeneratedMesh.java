/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;

/**
 * Generated geometry: triangles by board material, in RigidGlb's vertex layout (position, normal, colour, UV),
 * unindexed. Written as one model node ({@link #data}, the multi-hex buildings' shares) or as a connected kit's named
 * pieces ({@link #kit}, the generated building kits).
 */
final class GeneratedMesh {
    /** Whether each board material's .tres is transparent ({@link GodotMaterial#blended}), read once per asset load. */
    private static final Map<String, Boolean> BLENDED = new ConcurrentHashMap<>();
    /**
     * A part keyed {@code <board material>#board} draws that board material textured on the board's axes ({@link #kit}),
     * under a material of its own, so a modelled piece of the same board material keeps its own texture coordinates.
     */
    static final String BOARD_UV = "#board";
    static final String BUILDING_BODY = "structures/common/cast-concrete#body";
    /**
     * A roof part keyed {@code <board material>#deck} (before any {@link #BOARD_UV}) is the roof's own surface, drawn as
     * that board material: what units stand on ({@link GpuBuilding.Assembly#roof}). Its parapets, fittings and lights
     * are other parts.
     */
    static final String ROOF_DECK = "#deck";
    static final String BUILDING_FLOOR = "#floor";
    /** A mesh's vertex count limit: kit parts index their vertices with shorts. */
    private static final int MESH_VERTICES = 65_536;
    final Map<String, FloatArray> parts = new LinkedHashMap<>();

    /** A half plane: the side where {@code at(x, y) >= 0} is kept. */
    interface Keep {
        double at(double x, double y);
    }

    /** An asset reload rereads the board materials' .tres. */
    static void reload() { BLENDED.clear(); }

    void triangle(String material, float[] a, float[] b, float[] c) {
        var part = parts.computeIfAbsent(material, ignored -> new FloatArray());
        part.addAll(a);
        part.addAll(b);
        part.addAll(c);
    }

    void quad(String material, float[] a, float[] b, float[] c, float[] d) {
        triangle(material, a, b, c);
        triangle(material, a, c, d);
    }

    /** A solid convex slab, including its underside and edge faces. The existing cap may supply its textured top. */
    void slab(String material, List<double[]> outline, double bottom, double top, boolean cap) {
        List<float[]> upper = new ArrayList<>(), lower = new ArrayList<>();
        for (double[] p : outline) {
            upper.add(slabVertex(p, top, 0, 0, 1));
            lower.addFirst(slabVertex(p, bottom, 0, 0, -1));
        }
        if (cap) { polygon(material, upper); }
        polygon(material, lower);
        for (int i = 0; i < outline.size(); i++) {
            double[] p = outline.get(i), q = outline.get((i + 1) % outline.size()), n = KitOutline.normal(p, q);
            quad(material, slabVertex(p, bottom, n[0], n[1], 0), slabVertex(q, bottom, n[0], n[1], 0),
                  slabVertex(q, top, n[0], n[1], 0), slabVertex(p, top, n[0], n[1], 0));
        }
    }

    private static float[] slabVertex(double[] p, double z, double nx, double ny, double nz) {
        double u = nz == 0 ? p[0] * ny - p[1] * nx : p[0], v = nz == 0 ? z : p[1];
        return new float[] { (float) p[0], (float) p[1], (float) z, (float) nx, (float) ny, (float) nz,
              1, 1, 1, 1, (float) (u / 18), (float) (-v / 18) };
    }

    /** A convex polygon, fanned from its first vertex. */
    void polygon(String material, List<float[]> points) {
        for (int i = 1; i < points.size() - 1; i++) { triangle(material, points.get(0), points.get(i), points.get(i + 1)); }
    }

    void add(GeneratedMesh other) {
        other.parts.forEach((material, part) -> parts.computeIfAbsent(material, ignored -> new FloatArray()).addAll(part));
    }

    /** A complete authored node hierarchy, retaining its transforms rather than treating it as a kit layout sheet. */
    void placed(ModelData source, ModelNode node, Matrix4 parent) {
        Matrix4 frame = new Matrix4(parent).mul(new Matrix4(node.translation == null ? Vector3.Zero : node.translation,
              node.rotation == null ? new Quaternion() : node.rotation, node.scale == null ? new Vector3(1, 1, 1) : node.scale));
        Matrix3 normals = new Matrix3().set(frame).inv().transpose();
        boolean mirror = frame.det3x3() < 0;
        if (node.parts != null) {
            for (var nodePart : node.parts) {
                for (int meshIndex = 0; meshIndex < source.meshes.size; meshIndex++) {
                    var mesh = source.meshes.get(meshIndex);
                    for (var part : mesh.parts) {
                        if (!part.id.equals(nodePart.meshPartId)) { continue; }
                        for (int i = 0; i < part.indices.length; i += 3) {
                            List<float[]> triangle = new ArrayList<>(3);
                            for (int k = 0; k < 3; k++) {
                                int at = Short.toUnsignedInt(part.indices[i + (mirror && k > 0 ? 3 - k : k)]) * RigidGlb.STRIDE;
                                float[] vertex = Arrays.copyOfRange(mesh.vertices, at, at + RigidGlb.STRIDE);
                                Vector3 point = new Vector3(vertex[0], vertex[1], vertex[2]).mul(frame);
                                vertex[0] = point.x; vertex[1] = point.y; vertex[2] = point.z;
                                point.set(vertex[3], vertex[4], vertex[5]).mul(normals).nor();
                                vertex[3] = point.x; vertex[4] = point.y; vertex[5] = point.z;
                                triangle.add(vertex);
                            }
                            triangle(nodePart.materialId, triangle.get(0), triangle.get(1), triangle.get(2));
                        }
                    }
                }
            }
        }
        if (node.children != null) { for (var child : node.children) { placed(source, child, frame); } }
    }

    /**
     * Node {@code id} of a pieces GLB's level, placed by {@code frame}, stretched up so its top stands {@code height}
     * over its origin (0: as modelled), its parts by their board materials. The node's position is only its place in
     * the file's layout.
     */
    void piece(ModelData source, String id, Matrix4 frame, float height) {
        var node = ConnectedKit.node(source, id);
        if (node.parts == null || node.parts.length == 0) { throw new IllegalArgumentException("Empty piece " + id); }
        var mesh = source.meshes.first();
        Map<String, ModelMeshPart> meshParts = new HashMap<>();
        for (var part : mesh.parts) { meshParts.put(part.id, part); }
        var placement = new Matrix4(new Vector3(), node.rotation, node.scale);
        var point = new Vector3();
        if (height > 0) {
            float top = -Float.MAX_VALUE;
            for (var nodePart : node.parts) {
                for (short index : meshParts.get(nodePart.meshPartId).indices) {
                    int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                    top = Math.max(top, point.set(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2]).mul(placement).z);
                }
            }
            if (top <= 0) { throw new IllegalArgumentException("Piece " + id + " has no height to stretch"); }
            placement = new Matrix4().scale(1, 1, height / top).mul(placement);
        }
        placement = new Matrix4(frame).mul(placement);
        Matrix3 normals = new Matrix3().set(placement).inv().transpose();
        // A mirrored piece (a bay laid end for end) keeps facing out: its triangles wound the other way.
        boolean mirror = placement.det3x3() < 0;
        for (var nodePart : node.parts) {
            String board = ((RigidGlb.Data) source).surfaces.get(nodePart.materialId).board();
            if (board == null) {
                throw new IllegalArgumentException("A building kit's pieces use library materials only, not " + nodePart.materialId);
            }
            // Keyed by the GLB's material, its board material and finish (authored_examples#material_id): each finish draws apart.
            if (!boardName(nodePart.materialId).equals(board)) {
                throw new IllegalArgumentException("Piece material " + nodePart.materialId + " is not named after its board material " + board);
            }
            var target = parts.computeIfAbsent(nodePart.materialId, ignored -> new FloatArray());
            short[] indices = meshParts.get(nodePart.meshPartId).indices;
            for (int i = 0; i < indices.length; i++) {
                int at = Short.toUnsignedInt(indices[mirror && i % 3 > 0 ? i - i % 3 + 3 - i % 3 : i]) * RigidGlb.STRIDE;
                float[] vertex = Arrays.copyOfRange(mesh.vertices, at, at + RigidGlb.STRIDE);
                point.set(vertex[0], vertex[1], vertex[2]).mul(placement);
                vertex[0] = point.x; vertex[1] = point.y; vertex[2] = point.z;
                point.set(vertex[3], vertex[4], vertex[5]).mul(normals).nor();
                vertex[3] = point.x; vertex[4] = point.y; vertex[5] = point.z;
                target.addAll(vertex);
            }
        }
    }

    /** The part of a triangle over the hex (its outline about the centre), attributes interpolated. */
    void clipped(String material, List<float[]> hex, float[] a, float[] b, float[] c) {
        List<float[]> polygon = List.of(a, b, c);
        for (int corner = 0; corner < 6 && !polygon.isEmpty(); corner++) {
            float[] p = hex.get(corner), q = hex.get((corner + 1) % 6);
            float length = (float) Math.hypot(q[0] - p[0], q[1] - p[1]);
            float nx = (q[1] - p[1]) / length, ny = -(q[0] - p[0]) / length;
            polygon = clip(polygon, nx, ny, nx * p[0] + ny * p[1], false);
        }
        for (int i = 1; i < polygon.size() - 1; i++) {
            triangle(material, normalised(polygon.get(0)), normalised(polygon.get(i)), normalised(polygon.get(i + 1)));
        }
    }

    /** The geometry where every half plane in {@code keeps} holds, each triangle clipped, attributes interpolated. */
    GeneratedMesh clipped(List<Keep> keeps) {
        return clipped(keeps, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /** Reject triangles outside the convex region's bounds before allocating vertices or visiting its planes. */
    GeneratedMesh clipped(List<Keep> keeps, float minX, float minY, float maxX, float maxY) {
        var result = new GeneratedMesh();
        parts.forEach((material, part) -> {
            int stride = RigidGlb.STRIDE;
            triangles:
            for (int start = 0; start < part.size; start += 3 * RigidGlb.STRIDE) {
                float ax = part.items[start], ay = part.items[start + 1];
                float bx = part.items[start + stride], by = part.items[start + stride + 1];
                float cx = part.items[start + 2 * stride], cy = part.items[start + 2 * stride + 1];
                if (Math.max(ax, Math.max(bx, cx)) < minX || Math.min(ax, Math.min(bx, cx)) > maxX
                      || Math.max(ay, Math.max(by, cy)) < minY || Math.min(ay, Math.min(by, cy)) > maxY) { continue; }
                boolean inside = true;
                for (Keep keep : keeps) {
                    double a = keep.at(ax, ay), b = keep.at(bx, by), c = keep.at(cx, cy);
                    if (a < 0 && b < 0 && c < 0) { continue triangles; }
                    inside &= a >= 0 && b >= 0 && c >= 0;
                }
                if (inside) {
                    result.parts.computeIfAbsent(material, ignored -> new FloatArray()).addAll(part.items, start, 3 * stride);
                    continue;
                }
                List<float[]> polygon = new ArrayList<>(3);
                for (int k = 0; k < 3; k++) {
                    float[] vertex = new float[RigidGlb.STRIDE];
                    System.arraycopy(part.items, start + k * RigidGlb.STRIDE, vertex, 0, RigidGlb.STRIDE);
                    polygon.add(vertex);
                }
                for (Keep keep : keeps) {
                    polygon = clip(polygon, keep);
                    if (polygon.isEmpty()) { break; }
                }
                result.polygon(material, polygon);
            }
        });
        return result;
    }

    /** {@code polygon} (convex, counter-clockwise) where n.p <= offset, or >= offset with {@code beyond}. */
    static List<float[]> clip(List<float[]> polygon, float nx, float ny, float offset, boolean beyond) {
        List<float[]> result = new ArrayList<>(polygon.size() + 1);
        float sign = beyond ? -1 : 1;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float da = sign * (nx * a[0] + ny * a[1] - offset), db = sign * (nx * b[0] + ny * b[1] - offset);
            if (da <= 0) { result.add(a); }
            if (da < 0 != db < 0 && da != 0 && db != 0) { result.add(between(a, b, da / (da - db))); }
        }
        return result.size() < 3 ? List.of() : result;
    }

    /** Sutherland-Hodgman: {@code polygon}'s part where {@code keep} holds, attributes interpolated; empty below three. */
    static List<float[]> clip(List<float[]> polygon, Keep keep) {
        return clipBy(polygon, p -> keep.at(p[0], p[1]));
    }

    /** As {@link #clip(List, Keep)}, kept where {@code side} of a vertex (its position first) is not negative. */
    static List<float[]> clipBy(List<float[]> polygon, java.util.function.ToDoubleFunction<float[]> side) {
        boolean inside = false, outside = false;
        for (float[] p : polygon) {
            if (side.applyAsDouble(p) >= 0) { inside = true; } else { outside = true; }
        }
        if (!outside) { return polygon; }
        if (!inside) { return List.of(); }
        List<float[]> result = new ArrayList<>(polygon.size() + 1);
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            double da = side.applyAsDouble(a), db = side.applyAsDouble(b);
            if (da >= 0) { result.add(a); }
            if (da < 0 != db < 0 && da != 0 && db != 0) { result.add(between(a, b, (float) (da / (da - db)))); }
        }
        return result.size() < 3 ? List.of() : result;
    }

    private static float[] between(float[] a, float[] b, float t) {
        float[] cut = new float[a.length];
        for (int k = 0; k < a.length; k++) { cut[k] = a[k] + (b[k] - a[k]) * t; }
        return cut;
    }

    private static float[] normalised(float[] vertex) {
        float length = (float) Math.sqrt(vertex[3] * vertex[3] + vertex[4] * vertex[4] + vertex[5] * vertex[5]);
        if (length > 0 && Math.abs(length - 1) > 1e-4f) {
            vertex = vertex.clone();
            vertex[3] /= length; vertex[4] /= length; vertex[5] /= length;
        }
        return vertex;
    }

    /** Whether any part is (or, with {@code blended} false, is not) a transparent board material. */
    boolean has(boolean blended) {
        return parts.keySet().stream().anyMatch(material -> blended(material) == blended);
    }

    private static boolean blended(String material) {
        return BLENDED.computeIfAbsent(material, name -> GpuMaterialMaps.board(boardName(name)).blended());
    }

    /** Clipped authored geometry keeps the GLB's own materials and textures, including embedded images. */
    RigidGlb.Data data(String id, ModelData source) {
        var data = new RigidGlb.Data();
        data.id = id;
        data.materials.addAll(source.materials);
        if (source instanceof RigidGlb.Data rigid) {
            data.inheritMaterials(rigid);
        }
        parts.forEach((material, vertices) -> {
            // Triangles remain whole; split large authored models across meshes with 16-bit indices.
            int limit = (MESH_VERTICES / 3) * 3 * RigidGlb.STRIDE;
            for (int start = 0; start < vertices.size; start += limit) {
                int size = Math.min(limit, vertices.size - start);
                var mesh = new ModelMesh();
                mesh.id = "slice-" + data.meshes.size;
                mesh.attributes = attributes();
                mesh.vertices = Arrays.copyOfRange(vertices.items, start, start + size);
                var part = new ModelMeshPart();
                part.id = mesh.id;
                part.primitiveType = GL20.GL_TRIANGLES;
                part.indices = new short[size / RigidGlb.STRIDE];
                for (int i = 0; i < part.indices.length; i++) { part.indices[i] = (short) i; }
                mesh.parts = new ModelMeshPart[] { part };
                var nodePart = new ModelNodePart();
                nodePart.meshPartId = part.id;
                nodePart.materialId = material;
                var node = new ModelNode();
                node.id = mesh.id;
                node.meshId = mesh.id;
                node.parts = new ModelNodePart[] { nodePart };
                data.meshes.add(mesh);
                data.nodes.add(node);
            }
        });
        return data;
    }

    /** The transparent or opaque board-material parts; oversized shares use the same mesh splitting as authored models. */
    RigidGlb.Data data(String id, boolean blended) {
        int vertexCount = parts.entrySet().stream().filter(part -> blended(part.getKey()) == blended)
              .mapToInt(part -> part.getValue().size / RigidGlb.STRIDE).sum();
        if (vertexCount > MESH_VERTICES) {
            // Detailed tall buildings can exceed one 16-bit mesh. Reuse the authored-geometry chunking path,
            // keeping every storey and material rather than dropping detail or overflowing the indices.
            var visible = new GeneratedMesh();
            var materials = new RigidGlb.Data();
            parts.forEach((material, part) -> {
                if (blended(material) != blended) { return; }
                visible.parts.put(material, part);
                RigidGlb.boardMaterial(materials, material, boardName(material));
            });
            return visible.data(id, materials);
        }
        var data = new RigidGlb.Data();
        data.id = id;
        var vertices = new FloatArray();
        var mesh = new ModelMesh();
        mesh.id = "multihex";
        mesh.attributes = attributes();
        var node = new ModelNode();
        node.id = "multihex";
        node.meshId = mesh.id;
        node.children = new ModelNode[0];
        List<ModelMeshPart> meshParts = new ArrayList<>();
        List<ModelNodePart> nodeParts = new ArrayList<>();
        parts.forEach((material, part) -> {
            if (blended(material) != blended) { return; }
            int first = vertices.size / RigidGlb.STRIDE, count = part.size / RigidGlb.STRIDE;
            if (first + count > MESH_VERTICES) { throw new IllegalStateException("Multi-hex building share too large: " + id); }
            vertices.addAll(part);
            var meshPart = new ModelMeshPart();
            meshPart.id = material;
            meshPart.primitiveType = GL20.GL_TRIANGLES;
            meshPart.indices = new short[count];
            for (int i = 0; i < count; i++) { meshPart.indices[i] = (short) (first + i); }
            meshParts.add(meshPart);
            RigidGlb.boardMaterial(data, material, boardName(material));
            var nodePart = new ModelNodePart();
            nodePart.meshPartId = material;
            nodePart.materialId = material;
            nodeParts.add(nodePart);
        });
        mesh.vertices = vertices.toArray();
        mesh.parts = meshParts.toArray(ModelMeshPart[]::new);
        node.parts = nodeParts.toArray(ModelNodePart[]::new);
        data.meshes.add(mesh);
        data.nodes.add(node);
        return data;
    }

    /**
     * Named pieces as a connected kit's nodes ({@link ConnectedKit}), their materials the board materials of the same
     * names, in meshes of welded vertices; {@code boardUVs}, the {@link #BOARD_UV} parts' repeats on
     * the board's axes ({@link RigidGlb.Data#boardUVs}).
     */
    static RigidGlb.Data kit(String id, Map<String, GeneratedMesh> pieces, Map<String, Float> boardUVs) {
        var data = new RigidGlb.Data();
        data.id = id;
        Set<String> materials = new HashSet<>();
        var vertices = new FloatArray();
        List<ModelMeshPart> meshParts = new ArrayList<>();
        Map<Weld, Integer> welded = new HashMap<>();
        for (var piece : pieces.entrySet()) {
            int count = piece.getValue().parts.values().stream().mapToInt(part -> part.size / RigidGlb.STRIDE).sum();
            if (vertices.size > 0 && vertices.size / RigidGlb.STRIDE + count > MESH_VERTICES) {
                kitMesh(data, vertices, meshParts);
                vertices.clear(); meshParts.clear(); welded.clear();
            }
            var node = new ModelNode();
            node.id = piece.getKey();
            node.children = new ModelNode[0];
            // At the kit's origin, as a GLB's nodes read (RigidGlb#node).
            node.translation = new Vector3();
            node.rotation = new Quaternion();
            node.scale = new Vector3(1, 1, 1);
            List<ModelNodePart> nodeParts = new ArrayList<>();
            piece.getValue().parts.forEach((material, part) -> {
                var meshPart = new ModelMeshPart();
                meshPart.id = piece.getKey() + "/" + material;
                meshPart.primitiveType = GL20.GL_TRIANGLES;
                meshPart.indices = new short[part.size / RigidGlb.STRIDE];
                for (int i = 0; i < meshPart.indices.length; i++) {
                    meshPart.indices[i] = (short) weld(part.items, i * RigidGlb.STRIDE, RigidGlb.STRIDE, vertices, welded);
                }
                meshParts.add(meshPart);
                if (materials.add(material)) {
                    RigidGlb.boardMaterial(data, material, boardName(material));
                    if (boardUVs.containsKey(material)) { data.boardUVs.put(material, boardUVs.get(material)); }
                }
                var nodePart = new ModelNodePart();
                nodePart.meshPartId = meshPart.id;
                nodePart.materialId = material;
                nodeParts.add(nodePart);
            });
            node.parts = nodeParts.toArray(ModelNodePart[]::new);
            data.nodes.add(node);
        }
        kitMesh(data, vertices, meshParts);
        return data;
    }

    private static void kitMesh(RigidGlb.Data data, FloatArray vertices, List<ModelMeshPart> meshParts) {
        if (vertices.size / RigidGlb.STRIDE > MESH_VERTICES) { throw new IllegalStateException("Generated kit piece too large: " + data.id); }
        var mesh = new ModelMesh();
        mesh.id = data.id + "-mesh" + data.meshes.size;
        mesh.attributes = attributes();
        mesh.vertices = vertices.toArray();
        mesh.parts = meshParts.toArray(ModelMeshPart[]::new);
        data.meshes.add(mesh);
    }

    /** The board material a part's key names: its key up to a finish or {@link #BOARD_UV}. */
    static String boardName(String key) {
        int suffix = key.indexOf('#');
        return suffix < 0 ? key : key.substring(0, suffix);
    }

    /**
     * The index in {@code vertices} of the vertex record of {@code stride} floats at {@code offset} in {@code items}: one
     * already there with every bit the same ({@code welded}), or the record added.
     */
    static int weld(float[] items, int offset, int stride, FloatArray vertices, Map<Weld, Integer> welded) {
        return welded.computeIfAbsent(key(items, offset, stride), ignored -> {
            vertices.addAll(items, offset, stride);
            return vertices.size / stride - 1;
        });
    }

    /** A vertex's attributes, bit for bit: two vertices weld when every bit matches. */
    record Weld(int[] bits) {
        @Override
        public boolean equals(Object other) { return other instanceof Weld weld && Arrays.equals(bits, weld.bits); }

        @Override
        public int hashCode() { return Arrays.hashCode(bits); }
    }

    private static Weld key(float[] items, int offset, int stride) {
        int[] bits = new int[stride];
        for (int k = 0; k < bits.length; k++) { bits[k] = Float.floatToIntBits(items[offset + k]); }
        return new Weld(bits);
    }

    private static VertexAttribute[] attributes() {
        return new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorUnpacked(),
              VertexAttribute.TexCoords(0) };
    }
}
