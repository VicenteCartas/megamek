/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.FloatArray;

/**
 * A meeple, a building or a bridge broken into rubble: its meshes cut by planes in several directions into rigid
 * pieces, each closed where it was cut. {@link MeepleDebris} throws or drops them. Presentation only.
 */
final class MeepleShatter {
    static final String PIECE = "piece-";
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

    /**
     * Where the cut planes lie. {@code burst}: all of them through the middle, as a blast breaks a meeple, k planes giving
     * up to 2^k wedges. {@code crumble}: {@code levels} near-level breaks spread up the height, as storeys part, and
     * {@code steep} near-vertical breaks spread across the footprint, as a building or a deck crumbles into pieces of
     * about one size.
     */
    record Planes(boolean crumble, int levels, int steep) {
        static Planes burst(int cuts) { return new Planes(false, 0, cuts); }

        static Planes crumble(int levels, int steep) { return new Planes(true, levels, steep); }
    }

    /**
     * One drawn part to break: its mesh range, the material it draws with, and the transform from its mesh into the
     * cut's frame, null for none. {@code boardUV}: the units one texture repeat spans for a material textured along
     * the board's axes (GpuModelMaterial#boardUV), whose pieces take those UVs from where they lie on the board when cut,
     * as the vertex stage maps them; 0 keeps the mesh's own UVs.
     */
    record Source(MeshPart part, Material material, Matrix4 transform, float boardUV) {
        Source(NodePart part) { this(part.meshPart, part.material, null, 0); }
    }

    /**
     * What the faces a cut opens draw with: a material, its vertex layout, and the units one texture repeat spans,
     * projected on each cut's plane. Null caps take the meeple's painted walls ({@code "paint"}) or else its first part.
     */
    record Caps(Material material, VertexAttributes attributes, float repeat) { }

    /**
     * One material in one vertex layout: the source parts that share both are cut as one. A {@code skin} blends over
     * the solid it lies on, as a road's coats on a deck: it is cut with it, but no section of it is closed.
     */
    private static final class Channel {
        final Material material;
        final VertexAttributes attributes;
        final boolean skin;
        final int stride, position, normal, packed, unpacked, texture, tangent, binormal;

        Channel(Material material, VertexAttributes attributes) {
            this.material = material;
            this.attributes = attributes;
            var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
            skin = blend != null && blend.blended;
            stride = attributes.vertexSize / Float.BYTES;
            position = offset(VertexAttributes.Usage.Position);
            normal = offset(VertexAttributes.Usage.Normal);
            packed = offset(VertexAttributes.Usage.ColorPacked);
            unpacked = offset(VertexAttributes.Usage.ColorUnpacked);
            texture = offset(VertexAttributes.Usage.TextureCoordinates);
            tangent = offset(VertexAttributes.Usage.Tangent);
            binormal = offset(VertexAttributes.Usage.BiNormal);
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

        boolean drawn() { return Arrays.stream(triangles).anyMatch(list -> list.size > 0); }
    }

    private static void normalize(float[] vertex, int at) {
        float length = (float) Math.sqrt(vertex[at] * vertex[at] + vertex[at + 1] * vertex[at + 1] + vertex[at + 2] * vertex[at + 2]);
        if (length > 0) { for (int i = 0; i < 3; i++) { vertex[at + i] /= length; } }
    }

    /** A body's pieces before they are meshes ({@link #model}), with the points each rests on. */
    static final class Cut {
        private final List<Channel> channels;
        private final List<Piece> pieces;
        private final List<List<Vector3>> supports;
        /** The parts it read, which a meeple must still draw when its pieces are made ({@link #model(String, Node, Cut)}). */
        private final List<MeshPart> read;
        /** Where the cut's frame lies on the board; zero for a frame of the body's own. */
        private final Vector3 origin;

        private Cut(List<Channel> channels, List<Piece> pieces, List<MeshPart> read, Vector3 origin) {
            this.channels = channels;
            this.pieces = pieces;
            this.read = read;
            this.origin = origin;
            supports = pieces.stream().map(piece -> MeepleShatter.supports(channels, piece)).toList();
        }

        /**
         * Each piece's outermost points along the volume's directions and its cuts, and points across its surface, in
         * the cut's frame: what it rests on ({@link MeepleDebris}). In the order of the pieces' nodes.
         */
        List<List<Vector3>> supports() { return supports; }

        int pieces() { return pieces.size(); }
    }

    /**
     * Breaks the drawn parts of {@code source}, in its own coordinates (where camouflage was projected), along {@code
     * cuts} planes: one slanted across its extrusion axis {@code up}, the others steep or oblique around it. Every cut
     * is closed by its section, filled in the part that holds the source's painted walls ({@code "paint"}) or else its
     * first. Its parts are read here, on the thread that owns their meshes; the cut needs no GL context and can run
     * ahead of the explosion on another thread.
     */
    static Supplier<Cut> cutter(Node source, Vector3 up, int cuts, int seed) {
        return cutter(drawnParts(source).stream().map(Source::new).toList(), Vector3.Zero, up, Planes.burst(cuts), seed, null);
    }

    /**
     * Breaks {@code sources}, each brought into one frame by its transform, along {@code planes} about the frame's
     * {@code up}. Every cut is closed by its section: what the parts it crosses wrap, parts that overlap as one and a
     * shell open at the cut across its opening. Read here, cut by the supplier, which needs no GL context.
     *
     * @param origin where the frame lies on the board, near the parts so their coordinates stay small; zero for a frame
     *               of the body's own
     */
    static Supplier<Cut> cutter(List<Source> sources, Vector3 origin, Vector3 up, Planes planes, int seed, Caps caps) {
        List<Channel> channels = new ArrayList<>();
        List<FloatArray> triangles = new ArrayList<>();
        Map<Mesh, float[]> vertices = new IdentityHashMap<>();
        Map<Mesh, short[]> indices = new IdentityHashMap<>();
        for (Source source : sources) {
            Mesh mesh = source.part().mesh;
            int index = 0;
            while (index < channels.size() && (channels.get(index).material != source.material()
                  || !channels.get(index).attributes.equals(mesh.getVertexAttributes()))) { index++; }
            if (index == channels.size()) {
                channels.add(new Channel(source.material(), mesh.getVertexAttributes()));
                triangles.add(new FloatArray());
            }
            read(channels.get(index), source, origin, vertices.computeIfAbsent(mesh, MeepleShatter::vertices),
                  indices.computeIfAbsent(mesh, MeepleShatter::indices), triangles.get(index));
        }
        if (caps != null) {
            channels.add(new Channel(caps.material(), caps.attributes()));
            triangles.add(new FloatArray());
        }
        List<MeshPart> read = sources.stream().map(Source::part).toList();
        FloatArray[] all = triangles.toArray(FloatArray[]::new);
        var at = new Vector3(origin);
        return () -> cut(channels, all, up, planes, seed, caps, read, at);
    }

    private static List<NodePart> drawnParts(Node source) {
        List<NodePart> parts = new ArrayList<>();
        for (NodePart part : source.parts) {
            if (part.enabled && part.meshPart.primitiveType == GL20.GL_TRIANGLES && part.meshPart.size > 0) { parts.add(part); }
        }
        return parts;
    }

    private static float[] vertices(Mesh mesh) {
        float[] result = new float[mesh.getNumVertices() * mesh.getVertexSize() / Float.BYTES];
        mesh.getVertices(result);
        return result;
    }

    private static short[] indices(Mesh mesh) {
        short[] result = new short[mesh.getNumIndices()];
        if (result.length > 0) { mesh.getIndices(result); }
        return result;
    }

    /**
     * The source's triangles, unindexed, as vertex records of its channel, brought into the cut's frame: positions by
     * its transform, normals and tangents turned with it, a mirrored transform's triangles wound back the right way
     * round, and board UVs baked where they lie.
     */
    private static void read(Channel channel, Source source, Vector3 origin, float[] vertices, short[] indices,
          FloatArray result) {
        MeshPart part = source.part();
        Matrix4 transform = source.transform();
        Matrix3 normals = transform == null ? null : new Matrix3().set(transform).inv().transpose();
        boolean mirrored = transform != null && transform.det3x3() < 0;
        int stride = channel.stride;
        int end = part.offset + part.size;
        float[] record = new float[stride];
        var point = new Vector3();
        int first = result.size;
        for (int index = part.offset; index < end; index++) {
            int vertex = indices.length == 0 ? index : Short.toUnsignedInt(indices[index]);
            System.arraycopy(vertices, vertex * stride, record, 0, stride);
            if (transform != null) {
                channel.position(record, point).mul(transform);
                record[channel.position] = point.x;
                record[channel.position + 1] = point.y;
                record[channel.position + 2] = point.z;
                turn(record, channel.normal, normals);
                turn(record, channel.tangent, transform);
                turn(record, channel.binormal, transform);
            }
            if (source.boardUV() > 0 && channel.texture >= 0) {
                // GpuUnitShader.linearVertex's board mapping: north up, one repeat per boardUV units.
                record[channel.texture] = (record[channel.position] + origin.x) / source.boardUV();
                record[channel.texture + 1] = -(record[channel.position + 1] + origin.y) / source.boardUV();
            }
            result.addAll(record);
        }
        if (mirrored) {
            // Swap each triangle's last two corners.
            float[] items = result.items;
            for (int at = first; at + 3 * stride <= result.size; at += 3 * stride) {
                System.arraycopy(items, at + stride, record, 0, stride);
                System.arraycopy(items, at + 2 * stride, items, at + stride, stride);
                System.arraycopy(record, 0, items, at + 2 * stride, stride);
            }
        }
    }

    private static void turn(float[] record, int at, Matrix3 matrix) {
        if (at < 0) { return; }
        var direction = new Vector3(record[at], record[at + 1], record[at + 2]).mul(matrix).nor();
        record[at] = direction.x;
        record[at + 1] = direction.y;
        record[at + 2] = direction.z;
    }

    private static void turn(float[] record, int at, Matrix4 matrix) {
        if (at < 0) { return; }
        var direction = new Vector3(record[at], record[at + 1], record[at + 2]).rot(matrix).nor();
        record[at] = direction.x;
        record[at + 1] = direction.y;
        record[at + 2] = direction.z;
    }

    private static Cut cut(List<Channel> channels, FloatArray[] triangles, Vector3 up, Planes planes, int seed, Caps caps,
          List<MeshPart> read, Vector3 origin) {
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
        if (!bounds.isValid()) { return new Cut(channels, pieces, read, origin); }
        Channel faces = caps != null ? channels.getLast() : channels.stream()
              .filter(channel -> "paint".equals(channel.material.id)).findFirst().orElse(channels.getFirst());
        var across = new Vector3(axis).crs(Math.abs(axis.x) < .9f ? Vector3.X : Vector3.Y).nor();
        var beside = new Vector3(axis).crs(across).nor();
        var center = bounds.getCenter(new Vector3());
        // Section ends further apart than rounding meet: two triangles' crossings of one line, where a cap made by an
        // earlier cut only touches the middle of the other's edge.
        float tolerance = Math.max(bounds.getDimensions(point).len() * 1e-5f, 1e-6f);
        float heading = UnitAttack.noise(seed) * 360;
        for (float[] plane : planes.crumble() ? crumble(bounds, center, axis, across, beside, planes, heading, seed)
              : burst(bounds, center, axis, across, beside, planes.steep(), heading, seed)) {
            var normal = new Vector3(plane[0], plane[1], plane[2]);
            List<Piece> next = new ArrayList<>();
            for (Piece piece : pieces) { split(channels, faces, caps, piece, normal, plane[3], tolerance, next); }
            pieces = next;
        }
        return new Cut(channels, pieces, read, origin);
    }

    /** The first break runs across the axis; the others turn about it by the golden angle, steep and oblique. */
    private static List<float[]> burst(BoundingBox bounds, Vector3 center, Vector3 axis, Vector3 across, Vector3 beside,
          int cuts, float heading, int seed) {
        List<float[]> result = new ArrayList<>();
        for (int cut = 0; cut < cuts; cut++) {
            float tilt = (cut == 0 ? 15 : cut % 3 == 0 ? 35 : 60) + 25 * UnitAttack.noise(seed + 31 * cut + 7);
            var normal = tilted(axis, across, beside, tilt, heading + cut * 137.5f);
            float offset = normal.dot(center) + (UnitAttack.noise(seed + 31 * cut + 13) - .5f) * .3f * extent(bounds, normal);
            result.add(new float[] { normal.x, normal.y, normal.z, offset });
        }
        return result;
    }

    /**
     * Near-level breaks spread evenly up the height, each slanted a little, then near-vertical breaks turned by the
     * golden angle, each through its own share of the footprint rather than its middle: pieces of about one size.
     */
    private static List<float[]> crumble(BoundingBox bounds, Vector3 center, Vector3 axis, Vector3 across, Vector3 beside,
          Planes planes, float heading, int seed) {
        List<float[]> result = new ArrayList<>();
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        var corner = new Vector3();
        for (int index = 0; index < 8; index++) {
            float along = corner.set((index & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (index & 2) == 0 ? bounds.min.y : bounds.max.y, (index & 4) == 0 ? bounds.min.z : bounds.max.z).dot(axis);
            low = Math.min(low, along);
            high = Math.max(high, along);
        }
        // A break slants at most half the spacing of the breaks across the footprint: storeys part one at a time.
        float slant = MathUtils.atan2(.5f * (high - low) / (planes.levels() + 1),
              Math.max(extent(bounds, across), extent(bounds, beside))) * MathUtils.radiansToDegrees;
        for (int level = 0; level < planes.levels(); level++) {
            int noise = seed + 53 * level + 101;
            float tilt = Math.min(3 + 11 * UnitAttack.noise(noise), slant);
            var normal = tilted(axis, across, beside, tilt, heading + level * 137.5f + 90);
            float share = (level + .75f + .5f * UnitAttack.noise(noise + 1)) / (planes.levels() + 1);
            var at = new Vector3(center).mulAdd(axis, MathUtils.lerp(low, high, share) - center.dot(axis));
            result.add(new float[] { normal.x, normal.y, normal.z, normal.dot(at) });
        }
        for (int cut = 0; cut < planes.steep(); cut++) {
            int noise = seed + 59 * cut + 211;
            float tilt = 72 + 18 * UnitAttack.noise(noise);
            var normal = tilted(axis, across, beside, tilt, heading + cut * 137.5f);
            // A low-discrepancy spread: successive breaks fall in different shares of the footprint.
            float spread = ((cut * .618034f + UnitAttack.noise(noise + 1)) % 1) * 2 - 1;
            float offset = normal.dot(center) + spread * .35f * extent(bounds, normal);
            result.add(new float[] { normal.x, normal.y, normal.z, offset });
        }
        return result;
    }

    /** {@code tilt} degrees from the axis toward the level direction {@code turn} degrees about it. */
    private static Vector3 tilted(Vector3 axis, Vector3 across, Vector3 beside, float tilt, float turn) {
        float radians = turn * MathUtils.degreesToRadians;
        var level = new Vector3(across).scl(MathUtils.cos(radians)).mulAdd(beside, MathUtils.sin(radians));
        return new Vector3(axis).scl(MathUtils.cosDeg(tilt)).mulAdd(level, MathUtils.sinDeg(tilt)).nor();
    }

    /**
     * Cuts the piece by the plane into what lies in front of it and behind it, closing both where the plane crossed it:
     * the section's edges run with the solid on their left seen from the front, a chain that never closes (the cut
     * through a shell with no floor, or open where it was sliced from its neighbours) is closed by its chord, and what
     * any outline wraps is filled once.
     */
    private static void split(List<Channel> channels, Channel faces, Caps caps, Piece piece, Vector3 normal, float offset,
          float tolerance, List<Piece> result) {
        // A plane that misses the piece, or only touches it, leaves it whole.
        float nearest = Float.POSITIVE_INFINITY, furthest = Float.NEGATIVE_INFINITY;
        var point = new Vector3();
        for (int index = 0; index < channels.size(); index++) {
            Channel channel = channels.get(index);
            FloatArray triangles = piece.triangles()[index];
            for (int at = 0; at < triangles.size; at += channel.stride) {
                float distance = channel.position(triangles.items, at, point).dot(normal) - offset;
                nearest = Math.min(nearest, distance);
                furthest = Math.max(furthest, distance);
            }
        }
        if (nearest >= -tolerance || furthest <= tolerance) {
            result.add(piece);
            return;
        }
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
        var face = new Vector3();
        var side = new Vector3();
        for (int index = 0; index < channels.size(); index++) {
            Channel channel = channels.get(index);
            FloatArray triangles = piece.triangles()[index];
            for (int at = 0; at + 3 * channel.stride <= triangles.size; at += 3 * channel.stride) {
                for (int corner = 0; corner < 3; corner++) {
                    corners[corner] = Arrays.copyOfRange(triangles.items, at + corner * channel.stride,
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
                if (crossing.size() == 2 && !channel.skin) {
                    channel.position(crossing.get(0), a);
                    channel.position(crossing.get(1), b);
                    // Its face points out of the solid: the section runs along the plane's normal across it.
                    channel.position(corners[1], face).sub(corners[0][channel.position], corners[0][channel.position + 1],
                          corners[0][channel.position + 2]);
                    face.crs(channel.position(corners[2], side).sub(corners[0][channel.position],
                          corners[0][channel.position + 1], corners[0][channel.position + 2]));
                    side.set(normal).crs(face);
                    var start = new Vector3(a.dot(u), a.dot(v), 0);
                    var end = new Vector3(b.dot(u), b.dot(v), 0);
                    if ((end.x - start.x) * side.dot(u) + (end.y - start.y) * side.dot(v) < 0) {
                        section.add(new GpuCutout.Edge(end, start));
                    } else {
                        section.add(new GpuCutout.Edge(start, end));
                    }
                    if (sample == null && channel.attributes.equals(faces.attributes)) { sample = crossing.getFirst(); }
                }
            }
        }
        if (caps != null || sample != null) {
            int index = channels.indexOf(faces);
            var origin = new Vector3(normal).scl(offset);
            var inward = new Vector3(normal).scl(-1);
            List<GpuCutout.Edge> closed = new ArrayList<>();
            for (List<Vector3> loop : GpuCutout.outlines(section, tolerance)) {
                for (int corner = 0; corner < loop.size(); corner++) {
                    closed.add(new GpuCutout.Edge(loop.get(corner), loop.get((corner + 1) % loop.size())));
                }
            }
            for (Vector3[] span : GpuCutout.fillNonZero(closed)) {
                var at = new Vector3[4];
                for (int corner = 0; corner < 4; corner++) {
                    at[corner] = new Vector3(origin).mulAdd(u, span[corner].x).mulAdd(v, span[corner].y);
                }
                FloatArray frontCaps = front.triangles()[index], backCaps = back.triangles()[index];
                cap(faces, caps, sample, at[0], at[1], at[2], inward, u, v, frontCaps);
                cap(faces, caps, sample, at[0], at[2], at[3], inward, u, v, frontCaps);
                cap(faces, caps, sample, at[0], at[1], at[2], normal, u, v, backCaps);
                cap(faces, caps, sample, at[0], at[2], at[3], normal, u, v, backCaps);
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

    /**
     * One triangle of a cut's section, wound and lit toward {@code facing}: with {@code caps}, in their layout, white,
     * its texture projected on the plane's axes {@code u} and {@code v}; else with the attributes of {@code sample}.
     */
    private static void cap(Channel channel, Caps caps, float[] sample, Vector3 a, Vector3 b, Vector3 c, Vector3 facing,
          Vector3 u, Vector3 v, FloatArray result) {
        var corners = new float[3][];
        Vector3[] points = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).dot(facing) >= 0
              ? new Vector3[] { a, b, c } : new Vector3[] { a, c, b };
        for (int corner = 0; corner < 3; corner++) {
            corners[corner] = caps == null ? sample.clone() : blank(channel);
            float[] vertex = corners[corner];
            vertex[channel.position] = points[corner].x;
            vertex[channel.position + 1] = points[corner].y;
            vertex[channel.position + 2] = points[corner].z;
            if (channel.normal >= 0) {
                vertex[channel.normal] = facing.x;
                vertex[channel.normal + 1] = facing.y;
                vertex[channel.normal + 2] = facing.z;
            }
            if (caps != null && channel.texture >= 0) {
                vertex[channel.texture] = points[corner].dot(u) / caps.repeat();
                vertex[channel.texture + 1] = points[corner].dot(v) / caps.repeat();
            }
        }
        channel.add(result, corners[0], corners[1], corners[2]);
    }

    /** A white vertex record of the channel's layout, its tangent frame level. */
    private static float[] blank(Channel channel) {
        float[] vertex = new float[channel.stride];
        if (channel.packed >= 0) { vertex[channel.packed] = Color.WHITE_FLOAT_BITS; }
        if (channel.unpacked >= 0) { Arrays.fill(vertex, channel.unpacked, channel.unpacked + 4, 1); }
        if (channel.tangent >= 0) { vertex[channel.tangent] = 1; }
        if (channel.binormal >= 0) { vertex[channel.binormal + 1] = 1; }
        return vertex;
    }

    /**
     * The cut's pieces as a model, in the materials {@code source} draws now, which they share; null when the source
     * no longer draws the meshes the cut was made of.
     */
    static Model model(String rootId, Node source, Cut cut) {
        List<NodePart> drawn = drawnParts(source);
        if (drawn.size() != cut.read.size()) { return null; }
        for (int index = 0; index < drawn.size(); index++) {
            MeshPart now = drawn.get(index).meshPart, then = cut.read.get(index);
            if (now.mesh != then.mesh || now.offset != then.offset || now.size != then.size) { return null; }
        }
        return model(rootId, cut);
    }

    /**
     * The cut's pieces as a model in their sources' materials, which they share; the model owns only the new meshes. One
     * mesh per channel holds every piece's triangles; each piece draws its own range of it.
     *
     * @return a model whose node {@code rootId}, where the cut's frame lies, has one child per piece, {@link #PIECE} and
     *       its number, at rest
     */
    static Model model(String rootId, Cut cut) {
        List<Piece> pieces = cut.pieces;
        var model = new Model();
        var root = new Node();
        root.id = rootId;
        root.translation.set(cut.origin);
        model.nodes.add(root);
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            var node = new Node();
            node.id = PIECE + index;
            root.addChild(node);
            nodes.add(node);
        }
        for (int index = 0; index < cut.channels.size(); index++) {
            Channel channel = cut.channels.get(index);
            var vertices = new FloatArray();
            for (Piece piece : pieces) { vertices.addAll(piece.triangles()[index]); }
            if (vertices.size == 0) { continue; }
            var mesh = new Mesh(true, vertices.size / channel.stride, 0, channel.attributes);
            mesh.setVertices(vertices.items, 0, vertices.size);
            model.meshes.add(mesh);
            model.manageDisposable(mesh);
            if (!model.materials.contains(channel.material, true)) { model.materials.add(channel.material); }
            int first = 0;
            for (int piece = 0; piece < pieces.size(); piece++) {
                int count = pieces.get(piece).triangles()[index].size / channel.stride;
                if (count > 0) {
                    var part = new MeshPart(rootId + "-" + index + "-" + PIECE + piece, mesh, first, count, GL20.GL_TRIANGLES);
                    part.update();
                    model.meshParts.add(part);
                    nodes.get(piece).parts.add(new NodePart(part, channel.material));
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

    /** The box's size along a direction. */
    private static float extent(BoundingBox box, Vector3 direction) {
        return Math.abs(direction.x) * box.getWidth() + Math.abs(direction.y) * box.getHeight()
              + Math.abs(direction.z) * box.getDepth();
    }
}
