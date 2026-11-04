/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.ToDoubleFunction;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFlag;

/** One subdivided cloth surface, shared by colour, shadow and picking passes. No separate decal geometry. */
final class GpuFlag {
    static final int COLUMNS = 32, ROWS = 16;
    static final float WIDTH = BoardFlag.WIDTH, HEIGHT = BoardFlag.HEIGHT;
    static final ToDoubleFunction<Vector3> NO_GROUND = point -> Double.NEGATIVE_INFINITY;
    private static final int FACE_VERTICES = (COLUMNS + 1) * (ROWS + 1);
    private final float[] vertices = new float[FACE_VERTICES * 2 * 8];
    final Model model;
    final Texture paint;
    int revision;

    GpuFlag(Texture paint) {
        this.paint = paint;
        model = new Model(data());
        try {
            model.materials.first().set(TextureAttribute.createDiffuse(paint),
                  new GpuModelMaterial(.95f, 0, null),
                  com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.createDiffuse(Color.WHITE));
        } catch (RuntimeException | Error failure) { model.dispose(); throw failure; }
    }

    static ModelData data() {
        var mesh = new ModelMesh(); mesh.id = "flag";
        mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0) };
        mesh.vertices = new float[FACE_VERTICES * 2 * 8];
        for (int row = 0, at = 0; row <= ROWS; row++) {
            for (int column = 0; column <= COLUMNS; column++, at += 8) {
                float u = (float) column / COLUMNS, v = (float) row / ROWS;
                mesh.vertices[at] = u * WIDTH; mesh.vertices[at + 2] = (1 - v) * HEIGHT;
                mesh.vertices[at + 4] = 1; mesh.vertices[at + 6] = u; mesh.vertices[at + 7] = v;
            }
        }
        var part = new ModelMeshPart(); part.id = "cloth"; part.primitiveType = GL20.GL_TRIANGLES;
        backFace(mesh.vertices);
        part.indices = new short[COLUMNS * ROWS * 12];
        for (int row = 0, at = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int a = row * (COLUMNS + 1) + column, b = a + COLUMNS + 1;
                for (int index : new int[] { a, a + 1, b, a + 1, b + 1, b }) { part.indices[at++] = (short) index; }
            }
        }
        int faceIndices = part.indices.length / 2;
        for (int i = 0; i < faceIndices; i += 3) {
            part.indices[faceIndices + i] = (short) (part.indices[i] + FACE_VERTICES);
            part.indices[faceIndices + i + 1] = (short) (part.indices[i + 2] + FACE_VERTICES);
            part.indices[faceIndices + i + 2] = (short) (part.indices[i + 1] + FACE_VERTICES);
        }
        mesh.parts = new ModelMeshPart[] { part };
        var material = new ModelMaterial(); material.id = "flag"; material.diffuse = Color.valueOf(BoardFlag.COLOURS.getFirst());
        var nodePart = new ModelNodePart(); nodePart.meshPartId = part.id; nodePart.materialId = material.id;
        var node = new ModelNode(); node.id = "flag"; node.meshId = mesh.id; node.parts = new ModelNodePart[] { nodePart };
        var data = new ModelData(); data.meshes.add(mesh); data.materials.add(material); data.nodes.add(node);
        return data;
    }

    /** A conservative world-space reach, including nonuniform scale and mirroring. */
    static BoundingBox bounds(Matrix4 transform) {
        Vector3 root = new Vector3().mul(transform), top = new Vector3(0, 0, HEIGHT).mul(transform);
        float reach = new Vector3(WIDTH, 0, 0).rot(transform).len();
        var result = new BoundingBox(root, top);
        result.min.sub(reach, reach, reach); result.max.add(reach, reach, reach);
        return result.set(result.min, result.max);
    }

    /** Quasi-static cloth: segment lengths stay fixed until contact; forces are always in world space. */
    static void deform(float[] vertices, Matrix4 transform, float phase, Vector3 wind, boolean vacuum,
          List<BoundingBox> obstacles, ToDoubleFunction<Vector3> ground) {
        Matrix4 inverse = new Matrix4(transform).inv();
        Vector3 rest = new Vector3(1, 0, 0).rot(transform);
        float segment = rest.len() * WIDTH / COLUMNS;
        rest.nor();
        float strength = vacuum ? 0 : Math.min(1, Math.max(.1f, wind.z));
        Vector3 breeze = new Vector3(wind.x, wind.y, 0);
        if (breeze.isZero()) { breeze.set(0, 1, 0); }
        breeze.nor();
        Vector3 across = new Vector3(-breeze.y, breeze.x, 0);
        Vector3 force = new Vector3(breeze).scl(4 * strength * strength).add(0, 0, -1);
        Vector3 point = new Vector3(), previous = new Vector3(), direction = new Vector3(), probe = new Vector3();
        for (int row = 0, at = 0; row <= ROWS; row++) {
            float v = (float) row / ROWS;
            point.set(0, 0, (1 - v) * HEIGHT).mul(transform);
            for (int column = 0; column <= COLUMNS; column++, at += 8) {
                float u = (float) column / COLUMNS;
                if (column > 0) {
                    previous.set(point);
                    float folds = strength * (.6f * (float) Math.sin(u * 12 - phase * 3 + v * 2)
                          + .2f * (float) Math.sin(u * 23 - phase * 5 - v * 4));
                    // A short bend at the seam preserves its orientation; the free cloth follows gravity and wind.
                    direction.set(force).mulAdd(rest, .35f * (float) Math.exp(-12 * u)).mulAdd(across, folds).nor();
                    point.mulAdd(direction, segment);
                    for (int pass = 0; pass < 2; pass++) {
                        for (BoundingBox obstacle : obstacles) { slide(previous, point, obstacle); }
                        ground(previous, point, ground, probe);
                    }
                }
                vertices[at] = point.x; vertices[at + 1] = point.y; vertices[at + 2] = point.z;
                vertices[at + 6] = u; vertices[at + 7] = v;
            }
        }
        // Return to mesh-local space only after gravity, wind and contact have been resolved.
        for (int at = 0; at < FACE_VERTICES * 8; at += 8) {
            point.set(vertices[at], vertices[at + 1], vertices[at + 2]).mul(inverse);
            vertices[at] = point.x; vertices[at + 1] = point.y; vertices[at + 2] = point.z;
        }
        Vector3 along = new Vector3(), down = new Vector3();
        for (int row = 0; row <= ROWS; row++) {
            for (int column = 0; column <= COLUMNS; column++) {
                int at = (row * (COLUMNS + 1) + column) * 8;
                difference(vertices, row * (COLUMNS + 1) + Math.min(column + 1, COLUMNS),
                      row * (COLUMNS + 1) + Math.max(column - 1, 0), along);
                difference(vertices, Math.min(row + 1, ROWS) * (COLUMNS + 1) + column,
                      Math.max(row - 1, 0) * (COLUMNS + 1) + column, down);
                along.crs(down).nor();
                vertices[at + 3] = along.x; vertices[at + 4] = along.y; vertices[at + 5] = along.z;
            }
        }
        backFace(vertices);
    }

    private static void difference(float[] vertices, int a, int b, Vector3 out) {
        out.set(vertices[a * 8] - vertices[b * 8], vertices[a * 8 + 1] - vertices[b * 8 + 1],
              vertices[a * 8 + 2] - vertices[b * 8 + 2]);
    }

    /** Sweep each cloth segment, then slide its endpoint on the first box face (also catches thin props). */
    static void slide(Vector3 start, Vector3 end, BoundingBox box) {
        if (box.contains(start)) { return; } // An authored attachment inside a solid cannot be moved.
        float enter = 0, leave = 1, face = 0;
        int axis = -1;
        for (int i = 0; i < 3; i++) {
            float a = component(start, i), delta = component(end, i) - a;
            float lo = component(box.min, i), hi = component(box.max, i);
            if (Math.abs(delta) < .000001f) { if (a < lo || a > hi) { return; } continue; }
            float near = ((delta > 0 ? lo : hi) - a) / delta;
            float far = ((delta > 0 ? hi : lo) - a) / delta;
            if (near > enter) { enter = near; axis = i; face = delta > 0 ? lo - .02f : hi + .02f; }
            leave = Math.min(leave, far);
            if (enter > leave) { return; }
        }
        if (axis == 0) { end.x = face; }
        if (axis == 1) { end.y = face; }
        if (axis == 2) { end.z = face; }
    }

    private static float component(Vector3 value, int axis) { return axis == 0 ? value.x : axis == 1 ? value.y : value.z; }

    /** Lift the segment clear of the installed ground; stop at steep steps rather than stretching onto their tops. */
    static void ground(Vector3 start, Vector3 end, ToDoubleFunction<Vector3> height, Vector3 probe) {
        float length2 = start.dst2(end), raised = end.z;
        float dx = end.x - start.x, dy = end.y - start.y;
        int samples = Math.max(4, Math.min(256, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy) / (.5f * BoardGeometry.hexScale()))));
        for (int sample = 1; sample <= samples; sample++) {
            float t = (float) sample / samples;
            probe.set(start).lerp(end, t);
            float floor = (float) height.applyAsDouble(probe);
            if (Float.isFinite(floor)) { raised = Math.max(raised, start.z + (floor + .05f - start.z) / t); }
        }
        if (raised <= end.z) { return; }
        end.z = raised;
        if (start.dst2(end) > length2 + .0001f) { end.set(start); }
    }

    void animate(Matrix4 transform, float phase, Vector3 wind, boolean vacuum, List<BoundingBox> obstacles,
          ToDoubleFunction<Vector3> ground) {
        deform(vertices, transform, phase, wind, vacuum, obstacles, ground);
        model.meshes.first().setVertices(vertices);
        model.meshParts.forEach(part -> part.update());
        revision++;
    }

    private static void backFace(float[] vertices) {
        int offset = FACE_VERTICES * 8;
        System.arraycopy(vertices, 0, vertices, offset, offset);
        for (int at = offset; at < vertices.length; at += 8) {
            vertices[at + 3] = -vertices[at + 3];
            vertices[at + 4] = -vertices[at + 4];
            vertices[at + 5] = -vertices[at + 5];
        }
    }

    static Texture texture(BoardFlag flag, BoardDecoration.Colours colours) {
        Texture texture = GpuColorTexture.create(() -> paint(flag, colours), true, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        return texture;
    }

    private static Pixmap paint(BoardFlag flag, BoardDecoration.Colours colours) {
        Pixmap image = new Pixmap(768, 512, Pixmap.Format.RGBA8888);
        Pixmap emblem = null;
        try {
            if (!flag.emblem().isEmpty() && BoardDecalArt.image(flag.emblem()).isFile()) {
                emblem = GpuColorTexture.decode(new FileHandle(BoardDecalArt.image(flag.emblem())));
            }
            int[] palette = new int[4];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = Color.rgba8888(Color.valueOf(colours.slot(i) == null ? BoardFlag.COLOURS.get(i) : colours.slot(i)));
            }
            double angle = Math.toRadians(flag.rotation()), c = Math.cos(angle), s = Math.sin(angle);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    double u = (x + .5) / image.getWidth(), v = (y + .5) / image.getHeight();
                    image.drawPixel(x, y, palette[flag.slot(u, v)]);
                    if (emblem == null) { continue; }
                    // Normalized offsets; positive Y is up. Default emblem fits half the cloth height, preserving aspect.
                    double px = (u - .5 - flag.x()) * WIDTH, py = (.5 - v - flag.y()) * HEIGHT;
                    double h = HEIGHT * .5 * flag.scale(), w = h * emblem.getWidth() / emblem.getHeight();
                    double ex = (c * px + s * py) / w + .5, ey = .5 - (-s * px + c * py) / h;
                    if (ex >= 0 && ex < 1 && ey >= 0 && ey < 1) {
                        image.drawPixel(x, y, emblem.getPixel((int) (ex * emblem.getWidth()), (int) (ey * emblem.getHeight())));
                    }
                }
            }
            return image;
        } catch (RuntimeException | Error failure) { image.dispose(); throw failure; }
        finally { if (emblem != null) { emblem.dispose(); } }
    }
}
