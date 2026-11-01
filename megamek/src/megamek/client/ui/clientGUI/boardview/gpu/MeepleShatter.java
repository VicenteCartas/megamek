/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.FloatArray;

/**
 * A meeple broken into rubble: its mesh cut by planes in several directions into rigid pieces, each closed where it was
 * cut. {@link MeepleDebris} throws them. Presentation only.
 */
final class MeepleShatter {
    static final String PIECE = "piece-";
    /**
     * A piece's empty marker children, which it rests on: its outermost points along {@link #AXES} and its cuts, and
     * points across its surface.
     */
    private static final String SUPPORT = "support";
    /** The surface's support points lie in a grid of this many cells along each of the piece's bounds. */
    private static final int SURFACE_CELLS = 4;
    /** The 13 directions of a 26-sided bounding volume. */
    private static final List<Vector3> AXES = axes();

    private MeepleShatter() { }

    private static List<Vector3> axes() {
        List<Vector3> result = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    int first = x != 0 ? x : y != 0 ? y : z;
                    if (first > 0) { result.add(new Vector3(x, y, z).nor()); }
                }
            }
        }
        return List.copyOf(result);
    }

    /** One source part: its material and vertex layout. */
    private static final class Channel {
        final NodePart part;
        final VertexAttributes attributes;
        final int stride, position, normal, packed;

        Channel(NodePart part) {
            this.part = part;
            attributes = part.meshPart.mesh.getVertexAttributes();
            stride = attributes.vertexSize / Float.BYTES;
            position = offset(VertexAttributes.Usage.Position);
            normal = offset(VertexAttributes.Usage.Normal);
            packed = offset(VertexAttributes.Usage.ColorPacked);
        }

        private int offset(int usage) {
            VertexAttribute attribute = attributes.findByUsage(usage);
            return attribute == null ? -1 : attribute.offset / Float.BYTES;
        }

        float[] lerp(float[] a, float[] b, float t) {
            float[] result = new float[stride];
            for (int i = 0; i < stride; i++) { result[i] = a[i] + (b[i] - a[i]) * t; }
            if (packed >= 0) { result[packed] = a[packed]; }
            if (normal >= 0) { normalize(result, normal); }
            return result;
        }

        Vector3 position(float[] vertex, Vector3 result) {
            return position(vertex, 0, result);
        }

        /** The position of the vertex record at {@code at} in {@code records}. */
        Vector3 position(float[] records, int at, Vector3 result) {
            return result.set(records[at + position], records[at + position + 1], records[at + position + 2]);
        }

        /** Adds the triangle unless it has no area. */
        void add(FloatArray triangles, float[] a, float[] b, float[] c) {
            var ab = position(b, new Vector3()).sub(a[position], a[position + 1], a[position + 2]);
            var ac = position(c, new Vector3()).sub(a[position], a[position + 1], a[position + 2]);
            if (ab.crs(ac).len2() < 1e-10f) { return; }
            triangles.addAll(a);
            triangles.addAll(b);
            triangles.addAll(c);
        }
    }

    /** One piece: per channel its unindexed triangles, as vertex records, and the normals of the planes it was cut by. */
    private record Piece(FloatArray[] triangles, List<Vector3> cuts) {
        static Piece empty(int channels, List<Vector3> cuts) {
            var triangles = new FloatArray[channels];
            for (int index = 0; index < channels; index++) { triangles[index] = new FloatArray(); }
            return new Piece(triangles, cuts);
        }

        boolean drawn() { return java.util.Arrays.stream(triangles).anyMatch(list -> list.size > 0); }
    }

    private static void normalize(float[] vertex, int at) {
        float length = (float) Math.sqrt(vertex[at] * vertex[at] + vertex[at + 1] * vertex[at + 1] + vertex[at + 2] * vertex[at + 2]);
        if (length > 0) { for (int i = 0; i < 3; i++) { vertex[at + i] /= length; } }
    }

    /** A meeple's pieces before they are meshes ({@link #model}), with the points each rests on. */
    static final class Cut {
        private final List<Channel> channels;
        private final List<Piece> pieces;
        private final List<List<Vector3>> supports;

        private Cut(List<Channel> channels, List<Piece> pieces) {
            this.channels = channels;
            this.pieces = pieces;
            supports = pieces.stream().map(piece -> supports(channels, piece)).toList();
        }
    }

    /**
     * Breaks the drawn parts of {@code source}, in its own coordinates (where camouflage was projected), along {@code
     * cuts} planes: one slanted across its extrusion axis {@code up}, the others steep or oblique around it. Every cut
     * is closed by its section, filled in the part that holds the source's painted walls ({@code "paint"}) or else its
     * first. The source must be closed. Its parts are read here, on the thread that owns their meshes; the cut needs no
     * GL context and can run ahead of the explosion on another thread.
     */
    static Supplier<Cut> cutter(Node source, Vector3 up, int cuts, int seed) {
        List<Channel> channels = channels(source);
        FloatArray[] triangles = channels.stream().map(MeepleShatter::triangles).toArray(FloatArray[]::new);
        return () -> cut(channels, triangles, up, cuts, seed);
    }

    private static List<Channel> channels(Node source) {
        List<Channel> channels = new ArrayList<>();
        for (NodePart part : source.parts) {
            if (part.enabled && part.meshPart.primitiveType == GL20.GL_TRIANGLES && part.meshPart.size > 0) {
                channels.add(new Channel(part));
            }
        }
        return channels;
    }

    private static Cut cut(List<Channel> channels, FloatArray[] triangles, Vector3 up, int cuts, int seed) {
        var axis = new Vector3(up).nor();
        var whole = Piece.empty(channels.size(), List.of(axis));
        var bounds = new BoundingBox().inf();
        var point = new Vector3();
        for (int index = 0; index < channels.size(); index++) {
            Channel channel = channels.get(index);
            whole.triangles()[index] = triangles[index];
            for (int at = 0; at < whole.triangles()[index].size; at += channel.stride) {
                bounds.ext(channel.position(whole.triangles()[index].items, at, point));
            }
        }
        List<Piece> pieces = new ArrayList<>(List.of(whole));
        if (!bounds.isValid()) { return new Cut(channels, pieces); }
        Channel faces = channels.stream().filter(channel -> "paint".equals(channel.part.material.id)).findFirst()
              .orElse(channels.getFirst());
        var across = new Vector3(axis).crs(Math.abs(axis.x) < .9f ? Vector3.X : Vector3.Y).nor();
        var beside = new Vector3(axis).crs(across).nor();
        var center = bounds.getCenter(new Vector3());
        float heading = UnitAttack.noise(seed) * 360;
        for (int cut = 0; cut < cuts; cut++) {
            // The first break runs across the axis; the others turn about it by the golden angle, steep and oblique.
            float tilt = (cut == 0 ? 15 : cut % 3 == 0 ? 35 : 60) + 25 * UnitAttack.noise(seed + 31 * cut + 7);
            float turn = (heading + cut * 137.5f) * MathUtils.degreesToRadians;
            var level = new Vector3(across).scl(MathUtils.cos(turn)).mulAdd(beside, MathUtils.sin(turn));
            var normal = new Vector3(axis).scl(MathUtils.cosDeg(tilt)).mulAdd(level, MathUtils.sinDeg(tilt)).nor();
            float offset = normal.dot(center) + (UnitAttack.noise(seed + 31 * cut + 13) - .5f) * .3f * extent(bounds, normal);
            List<Piece> next = new ArrayList<>();
            for (Piece piece : pieces) { split(channels, faces, piece, normal, offset, next); }
            pieces = next;
        }
        return new Cut(channels, pieces);
    }

    /** The part's triangles, unindexed, as vertex records. */
    private static FloatArray triangles(Channel channel) {
        Mesh mesh = channel.part.meshPart.mesh;
        float[] vertices = new float[mesh.getNumVertices() * channel.stride];
        short[] indices = new short[mesh.getNumIndices()];
        mesh.getVertices(vertices);
        if (indices.length > 0) { mesh.getIndices(indices); }
        var result = new FloatArray();
        int end = channel.part.meshPart.offset + channel.part.meshPart.size;
        for (int index = channel.part.meshPart.offset; index < end; index++) {
            int vertex = indices.length == 0 ? index : Short.toUnsignedInt(indices[index]);
            result.addAll(vertices, vertex * channel.stride, channel.stride);
        }
        return result;
    }

    /** Cuts the piece by the plane into what lies in front of it and behind it, closing both where the plane crossed. */
    private static void split(List<Channel> channels, Channel faces, Piece piece, Vector3 normal, float offset,
          List<Piece> result) {
        List<Vector3> cuts = new ArrayList<>(piece.cuts());
        cuts.add(normal);
        var front = Piece.empty(channels.size(), cuts);
        var back = Piece.empty(channels.size(), cuts);
        // The plane's own coordinates, in which its section is filled.
        var u = new Vector3(normal).crs(Math.abs(normal.x) < .9f ? Vector3.X : Vector3.Y).nor();
        var v = new Vector3(normal).crs(u);
        List<GpuCutout.Edge> section = new ArrayList<>();
        float[] sample = null;
        float[][] corners = new float[3][];
        float[] distance = new float[3];
        var a = new Vector3();
        var b = new Vector3();
        for (int index = 0; index < channels.size(); index++) {
            Channel channel = channels.get(index);
            FloatArray triangles = piece.triangles()[index];
            for (int at = 0; at + 3 * channel.stride <= triangles.size; at += 3 * channel.stride) {
                for (int corner = 0; corner < 3; corner++) {
                    corners[corner] = java.util.Arrays.copyOfRange(triangles.items, at + corner * channel.stride,
                          at + (corner + 1) * channel.stride);
                    distance[corner] = channel.position(corners[corner], a).dot(normal) - offset;
                }
                clip(channel, corners, distance, 1, front.triangles()[index]);
                clip(channel, corners, distance, -1, back.triangles()[index]);
                List<float[]> crossing = new ArrayList<>(2);
                for (int corner = 0; corner < 3; corner++) {
                    int other = (corner + 1) % 3;
                    if (distance[corner] == 0) { crossing.add(corners[corner]); }
                    if (distance[corner] * distance[other] < 0) {
                        crossing.add(cross(channel, corners[corner], distance[corner], corners[other], distance[other]));
                    }
                }
                if (crossing.size() == 2) {
                    channel.position(crossing.get(0), a);
                    channel.position(crossing.get(1), b);
                    section.add(new GpuCutout.Edge(new Vector3(a.dot(u), a.dot(v), 0), new Vector3(b.dot(u), b.dot(v), 0)));
                    if (sample == null && channel.attributes.equals(faces.attributes)) { sample = crossing.getFirst(); }
                }
            }
        }
        if (sample != null) {
            int index = channels.indexOf(faces);
            var origin = new Vector3(normal).scl(offset);
            var inward = new Vector3(normal).scl(-1);
            for (Vector3[] span : GpuCutout.fill(section)) {
                var at = new Vector3[4];
                for (int corner = 0; corner < 4; corner++) {
                    at[corner] = new Vector3(origin).mulAdd(u, span[corner].x).mulAdd(v, span[corner].y);
                }
                face(faces, sample, at[0], at[1], at[2], inward, front.triangles()[index]);
                face(faces, sample, at[0], at[2], at[3], inward, front.triangles()[index]);
                face(faces, sample, at[0], at[1], at[2], normal, back.triangles()[index]);
                face(faces, sample, at[0], at[2], at[3], normal, back.triangles()[index]);
            }
        }
        if (front.drawn()) { result.add(front); }
        if (back.drawn()) { result.add(back); }
    }

    /**
     * Where the edge from {@code a} to {@code b} crosses the plane, always worked out from its end behind the plane,
     * so the two triangles sharing the edge agree exactly and the pieces stay closed for the next cut.
     */
    private static float[] cross(Channel channel, float[] a, float aDistance, float[] b, float bDistance) {
        return aDistance < bDistance ? channel.lerp(a, b, aDistance / (aDistance - bDistance))
              : channel.lerp(b, a, bDistance / (bDistance - aDistance));
    }

    /** Sutherland-Hodgman against one side of the plane ({@code sign} 1 its front, -1 its back), as a triangle fan. */
    private static void clip(Channel channel, float[][] corners, float[] distance, float sign, FloatArray result) {
        List<float[]> polygon = new ArrayList<>(4);
        for (int corner = 0; corner < 3; corner++) {
            int other = (corner + 1) % 3;
            if (distance[corner] * sign >= 0) { polygon.add(corners[corner]); }
            if (distance[corner] * distance[other] < 0) {
                polygon.add(cross(channel, corners[corner], distance[corner], corners[other], distance[other]));
            }
        }
        for (int corner = 1; corner + 1 < polygon.size(); corner++) {
            channel.add(result, polygon.getFirst(), polygon.get(corner), polygon.get(corner + 1));
        }
    }

    /** One triangle of a cut's section, with the attributes of {@code sample}, wound and lit toward {@code facing}. */
    private static void face(Channel channel, float[] sample, Vector3 a, Vector3 b, Vector3 c, Vector3 facing,
          FloatArray result) {
        var corners = new float[3][];
        Vector3[] points = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).dot(facing) >= 0
              ? new Vector3[] { a, b, c } : new Vector3[] { a, c, b };
        for (int corner = 0; corner < 3; corner++) {
            corners[corner] = sample.clone();
            corners[corner][channel.position] = points[corner].x;
            corners[corner][channel.position + 1] = points[corner].y;
            corners[corner][channel.position + 2] = points[corner].z;
            if (channel.normal >= 0) {
                corners[corner][channel.normal] = facing.x;
                corners[corner][channel.normal + 1] = facing.y;
                corners[corner][channel.normal + 2] = facing.z;
            }
        }
        channel.add(result, corners[0], corners[1], corners[2]);
    }

    /**
     * The cut's pieces as a model, in the materials {@code source} draws now, which they share; the model owns only the
     * new meshes. One mesh per channel holds every piece's triangles; each piece draws its own range of it.
     *
     * @return a model whose node {@code rootId} has one child per piece, {@link #PIECE} and its number, at rest; null
     *       when the source no longer draws the meshes the cut was made of
     */
    static Model model(String rootId, Node source, Cut cut) {
        List<Channel> channels = channels(source);
        if (channels.size() != cut.channels.size()) { return null; }
        for (int index = 0; index < channels.size(); index++) {
            MeshPart now = channels.get(index).part.meshPart, then = cut.channels.get(index).part.meshPart;
            if (now.mesh != then.mesh || now.offset != then.offset || now.size != then.size) { return null; }
        }
        List<Piece> pieces = cut.pieces;
        var model = new Model();
        var root = new Node();
        root.id = rootId;
        model.nodes.add(root);
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            var node = new Node();
            node.id = PIECE + index;
            root.addChild(node);
            nodes.add(node);
            for (Vector3 support : cut.supports.get(index)) {
                var marker = new Node();
                marker.id = SUPPORT;
                marker.translation.set(support);
                node.addChild(marker);
            }
        }
        for (int index = 0; index < channels.size(); index++) {
            Channel channel = channels.get(index);
            var vertices = new FloatArray();
            for (Piece piece : pieces) { vertices.addAll(piece.triangles()[index]); }
            if (vertices.size == 0) { continue; }
            var mesh = new Mesh(true, vertices.size / channel.stride, 0, channel.attributes);
            mesh.setVertices(vertices.items, 0, vertices.size);
            model.meshes.add(mesh);
            model.manageDisposable(mesh);
            if (!model.materials.contains(channel.part.material, true)) { model.materials.add(channel.part.material); }
            int first = 0;
            for (int piece = 0; piece < pieces.size(); piece++) {
                int count = pieces.get(piece).triangles()[index].size / channel.stride;
                if (count > 0) {
                    var part = new MeshPart(channel.part.meshPart.id + "-" + PIECE + piece, mesh, first, count, GL20.GL_TRIANGLES);
                    part.update();
                    model.meshParts.add(part);
                    nodes.get(piece).parts.add(new NodePart(part, channel.part.material));
                }
                first += count;
            }
        }
        model.calculateTransforms();
        return model;
    }

    /**
     * The piece's outermost points along the volume's directions and its cuts, and one point of its surface in each cell
     * of a {@link #SURFACE_CELLS}-cubed grid over its bounds that the surface crosses: a rock under the middle of a face
     * holds the piece up rather than pass through it.
     */
    private static List<Vector3> supports(List<Channel> channels, Piece piece) {
        List<Vector3> directions = new ArrayList<>(AXES);
        directions.addAll(piece.cuts());
        Set<Vector3> supports = new LinkedHashSet<>();
        var point = new Vector3();
        var bounds = new BoundingBox().inf();
        // One pass over the vertices for every direction: where each one's lowest and highest vertex lies.
        int count = directions.size();
        float[] x = new float[count], y = new float[count], z = new float[count];
        for (int direction = 0; direction < count; direction++) {
            x[direction] = directions.get(direction).x;
            y[direction] = directions.get(direction).y;
            z[direction] = directions.get(direction).z;
        }
        float[] low = new float[count], high = new float[count];
        int[] lowChannel = new int[count], lowAt = new int[count], highChannel = new int[count], highAt = new int[count];
        Arrays.fill(low, Float.POSITIVE_INFINITY);
        Arrays.fill(high, Float.NEGATIVE_INFINITY);
        for (int index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            FloatArray triangles = piece.triangles()[index];
            for (int at = 0; at < triangles.size; at += channel.stride) {
                channel.position(triangles.items, at, point);
                for (int direction = 0; direction < count; direction++) {
                    float along = point.x * x[direction] + point.y * y[direction] + point.z * z[direction];
                    if (along < low[direction]) {
                        low[direction] = along;
                        lowChannel[direction] = index;
                        lowAt[direction] = at;
                    }
                    if (along > high[direction]) {
                        high[direction] = along;
                        highChannel[direction] = index;
                        highAt[direction] = at;
                    }
                }
            }
        }
        if (low[0] == Float.POSITIVE_INFINITY) { return List.of(); }
        for (int direction = 0; direction < count; direction++) {
            var lowest = channels.get(lowChannel[direction])
                  .position(piece.triangles()[lowChannel[direction]].items, lowAt[direction], new Vector3());
            var highest = channels.get(highChannel[direction])
                  .position(piece.triangles()[highChannel[direction]].items, highAt[direction], new Vector3());
            supports.add(lowest);
            supports.add(highest);
            bounds.ext(lowest).ext(highest);
        }
        var size = bounds.getDimensions(new Vector3());
        boolean[] cells = new boolean[SURFACE_CELLS * SURFACE_CELLS * SURFACE_CELLS];
        var a = new Vector3();
        var b = new Vector3();
        var c = new Vector3();
        for (int index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            FloatArray triangles = piece.triangles()[index];
            for (int at = 0; at + 3 * channel.stride <= triangles.size; at += 3 * channel.stride) {
                channel.position(triangles.items, at, a);
                channel.position(triangles.items, at + channel.stride, b);
                channel.position(triangles.items, at + 2 * channel.stride, c);
                b.sub(a);
                c.sub(a);
                // Samples at most a cell apart across the triangle.
                int steps = Math.max(1, MathUtils.ceil(Math.max(span(b, size), Math.max(span(c, size),
                      span(point.set(c).sub(b), size))) * SURFACE_CELLS));
                for (int i = 0; i <= steps; i++) {
                    for (int j = 0; i + j <= steps; j++) {
                        point.set(a).mulAdd(b, (float) i / steps).mulAdd(c, (float) j / steps);
                        int cell = cell(point, bounds.min, size);
                        if (!cells[cell]) {
                            cells[cell] = true;
                            supports.add(new Vector3(point));
                        }
                    }
                }
            }
        }
        return List.copyOf(supports);
    }

    /** The largest share of the piece's bounds {@code size} that {@code edge} spans along any axis. */
    private static float span(Vector3 edge, Vector3 size) {
        return Math.max(share(edge.x, size.x), Math.max(share(edge.y, size.y), share(edge.z, size.z)));
    }

    private static float share(float length, float size) {
        return size > 0 ? Math.abs(length) / size : 0;
    }

    /** The {@link #SURFACE_CELLS}-cubed grid cell over bounds from {@code min} of {@code size} that holds {@code point}. */
    private static int cell(Vector3 point, Vector3 min, Vector3 size) {
        return (column(point.x - min.x, size.x) * SURFACE_CELLS + column(point.y - min.y, size.y)) * SURFACE_CELLS
              + column(point.z - min.z, size.z);
    }

    private static int column(float offset, float size) {
        return size > 0 ? MathUtils.clamp((int) (offset / size * SURFACE_CELLS), 0, SURFACE_CELLS - 1) : 0;
    }

    /** The piece's outermost points and points across its surface, in its own coordinates. */
    static List<Vector3> supports(Node piece) {
        List<Vector3> result = new ArrayList<>();
        for (Node marker : piece.getChildren()) {
            if (SUPPORT.equals(marker.id)) { result.add(new Vector3(marker.translation)); }
        }
        return result;
    }

    /** The box's corners, transformed by {@code frame}. */
    static Vector3[] corners(BoundingBox box, Matrix4 frame) {
        var result = new Vector3[8];
        for (int corner = 0; corner < 8; corner++) {
            result[corner] = new Vector3((corner & 1) == 0 ? box.min.x : box.max.x, (corner & 2) == 0 ? box.min.y : box.max.y,
                  (corner & 4) == 0 ? box.min.z : box.max.z).mul(frame);
        }
        return result;
    }

    /** The box's size along a direction. */
    private static float extent(BoundingBox box, Vector3 direction) {
        return Math.abs(direction.x) * box.getWidth() + Math.abs(direction.y) * box.getHeight()
              + Math.abs(direction.z) * box.getDepth();
    }

    /** The lowest point's height above {@code center} once turned by {@code turn} about it (negative: below). */
    static float lowest(Vector3[] points, Vector3 center, Quaternion turn) {
        float lowest = Float.POSITIVE_INFINITY;
        var relative = new Vector3();
        for (Vector3 point : points) { lowest = Math.min(lowest, turn.transform(relative.set(point).sub(center)).z); }
        return lowest;
    }
}
