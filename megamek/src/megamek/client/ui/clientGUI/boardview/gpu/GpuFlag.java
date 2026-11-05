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
import com.badlogic.gdx.math.MathUtils;
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
    private final float[] previousVertices = new float[vertices.length];
    private final Matrix4 groundPose = new Matrix4();
    private int groundScene = -1;
    private GroundGrid groundGrid;
    /** What vacuum cloth was last resolved against: without wind, the same inputs give the same sheet. */
    private ToDoubleFunction<Vector3> settledGround;
    private List<BoundingBox> settledObstacles;
    private final Matrix4 settledPose = new Matrix4();
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

    /**
     * The installed ground under a flag's reach, read once per pose and terrain: a frame's cloth reads this table
     * thousands of times instead of searching the hexes' faces. Interpolates slopes; a step inside a cell is its top.
     */
    static final class GroundGrid implements ToDoubleFunction<Vector3> {
        private static final int CELLS = 48;
        private final float minX, minY, cellX, cellY;
        private final float[] heights = new float[(CELLS + 1) * (CELLS + 1)];
        private float top = Float.NEGATIVE_INFINITY;

        GroundGrid(BoundingBox reach, ToDoubleFunction<Vector3> ground) {
            minX = reach.min.x; minY = reach.min.y;
            cellX = reach.getWidth() / CELLS; cellY = reach.getHeight() / CELLS;
            Vector3 probe = new Vector3();
            for (int y = 0, at = 0; y <= CELLS; y++) {
                for (int x = 0; x <= CELLS; x++, at++) {
                    heights[at] = (float) ground.applyAsDouble(probe.set(minX + x * cellX, minY + y * cellY, reach.max.z));
                    if (Float.isFinite(heights[at])) { top = Math.max(top, heights[at]); }
                }
            }
        }

        /** The highest ground anywhere in the reach, or negative infinity with none: cloth above it needs no query. */
        float top() { return top; }

        @Override public double applyAsDouble(Vector3 point) {
            float x = MathUtils.clamp((point.x - minX) / cellX, 0, CELLS), y = MathUtils.clamp((point.y - minY) / cellY, 0, CELLS);
            int column = Math.min((int) x, CELLS - 1), row = Math.min((int) y, CELLS - 1);
            float u = x - column, v = y - row;
            int at = row * (CELLS + 1) + column;
            float a = heights[at], b = heights[at + 1], c = heights[at + CELLS + 1], d = heights[at + CELLS + 2];
            float low = Math.min(a, Math.min(b, Math.min(c, d))), high = Math.max(a, Math.max(b, Math.max(c, d)));
            // Off the board has no floor: a cell at its edge keeps the nearer corner's answer.
            if (!Float.isFinite(low)) { return v < .5f ? u < .5f ? a : b : u < .5f ? c : d; }
            if (high - low > 2 * Math.max(cellX, cellY)) { return high; }
            return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
        }
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
        // Include the small elastic allowance used when resolving contact, plus collision clearance.
        float reach = new Vector3(WIDTH, 0, 0).rot(transform).len() * 1.1f + .05f * BoardGeometry.hexScale();
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
        float strength = vacuum ? 0 : Math.min(1, Math.max(.15f, wind.z));
        float verticalSeam = Math.abs(new Vector3(0, 0, 1).rot(transform).nor().z);
        Vector3 breeze = vacuum ? new Vector3(rest.x, rest.y, 0) : new Vector3(wind.x, wind.y, 0);
        if (breeze.isZero()) { breeze.set(0, 1, 0); }
        breeze.nor();
        Vector3 across = new Vector3(-breeze.y, breeze.x, 0);
        // Cloth has bending stiffness: a vertical seam must not collapse the whole sheet onto a single line.
        // Keep the spread downwind, even when the authored fly points upwind or the placement is mirrored.
        Vector3 force = new Vector3(breeze).scl(Math.max(4 * strength * strength, .55f * verticalSeam)).add(0, 0, -1);
        Vector3 point = new Vector3(), previous = new Vector3(), direction = new Vector3(), probe = new Vector3();
        Vector3 candidate = new Vector3();
        obstacles = obstacles.stream().map(box -> attachmentBounds(box, transform)).toList();
        float top = top(ground);
        boolean contact = false;
        for (int row = 0, at = 0; row <= ROWS; row++) {
            float v = (float) row / ROWS;
            point.set(0, 0, (1 - v) * HEIGHT).mul(transform);
            for (int column = 0; column <= COLUMNS; column++, at += 8) {
                float u = (float) column / COLUMNS;
                if (column > 0) {
                    previous.set(point);
                    float folds = .22f * verticalSeam * (float) Math.sin(v * Math.PI * 2 + u * 7)
                          + strength * (.6f * (float) Math.sin(u * 12 - phase * 3 + v * 2)
                          + .2f * (float) Math.sin(u * 23 - phase * 5 - v * 4));
                    // A short bend at the seam preserves its orientation; the free cloth follows gravity and wind.
                    direction.set(force).mulAdd(rest, .35f * (float) Math.exp(-12 * u)).mulAdd(across, folds).nor();
                    point.mulAdd(direction, segment);
                    candidate.set(point);
                    for (int pass = 0; pass < 2; pass++) {
                        for (BoundingBox obstacle : obstacles) { slide(previous, point, obstacle); }
                        ground(previous, point, ground, probe, top);
                    }
                    // Ground projection must not leave the cloth inside a low overhang or another prop.
                    for (BoundingBox obstacle : obstacles) { slide(previous, point, obstacle); }
                    if (below(point, ground, top) || inside(point, obstacles)) {
                        point.set(previous);
                    }
                    contact |= !point.epsilonEquals(candidate, .00001f);
                }
                vertices[at] = point.x; vertices[at + 1] = point.y; vertices[at + 2] = point.z;
                vertices[at + 6] = u; vertices[at + 7] = v;
            }
        }
        float ceiling = bounds(transform).max.z;
        contact |= groundSurface(vertices, ground, top, obstacles, ceiling, probe, previous);
        if (contact) {
            float rowLength = new Vector3(0, 0, HEIGHT / ROWS).rot(transform).len();
            relax(vertices, segment, rowLength, ground, top, obstacles, ceiling);
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
        slide(start, end, box, true);
    }

    private static void slide(Vector3 start, Vector3 end, BoundingBox box, boolean travel) {
        if (!box.isValid()) { return; }
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
        if (axis < 0) { return; }
        float dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float tx = axis == 0 ? 0 : dx, ty = axis == 1 ? 0 : dy, tz = axis == 2 ? 0 : dz;
        float tangent = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        float remaining = travel ? tangent == 0 ? 0 : length * (1 - enter) / tangent : 1 - enter;
        end.set(start.x + enter * dx + tx * remaining, start.y + enter * dy + ty * remaining,
              start.z + enter * dz + tz * remaining);
        if (axis == 0) { end.x = face; }
        if (axis == 1) { end.y = face; }
        if (axis == 2) { end.z = face; }
    }

    /** A coarse box may enclose empty space around a roof. Use one attachment plane for the whole rigid seam. */
    static BoundingBox attachmentBounds(BoundingBox box, Matrix4 transform) {
        Vector3 root = new Vector3().mul(transform), top = new Vector3(0, 0, HEIGHT).mul(transform);
        float mountingGap = new Vector3(WIDTH / COLUMNS, 0, 0).rot(transform).len();
        float enter = 0, leave = 1;
        for (int axis = 0; axis < 3; axis++) {
            float start = component(root, axis), delta = component(top, axis) - start;
            float low = component(box.min, axis) - mountingGap, high = component(box.max, axis) + mountingGap;
            if (Math.abs(delta) < .000001f) { if (start < low || start > high) { return box; } continue; }
            float a = (low - start) / delta, b = (high - start) / delta;
            enter = Math.max(enter, Math.min(a, b)); leave = Math.min(leave, Math.max(a, b));
            if (enter > leave) { return box; }
        }
        // Choose the face that needs the smallest correction to put every seam vertex outside the box.
        int face = 0; float distance = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            float lo = Math.min(component(root, axis), component(top, axis));
            float hi = Math.max(component(root, axis), component(top, axis));
            float lower = hi - component(box.min, axis), upper = component(box.max, axis) - lo;
            if (lower < distance) { distance = lower; face = axis * 2; }
            if (upper < distance) { distance = upper; face = axis * 2 + 1; }
        }
        var adjusted = new BoundingBox(box);
        int axis = face / 2;
        float edge = face % 2 == 0 ? Math.max(component(box.min, axis), Math.max(component(root, axis), component(top, axis)) + .02f)
              : Math.min(component(box.max, axis), Math.min(component(root, axis), component(top, axis)) - .02f);
        Vector3 corner = face % 2 == 0 ? adjusted.min : adjusted.max;
        if (axis == 0) { corner.x = edge; } else if (axis == 1) { corner.y = edge; } else { corner.z = edge; }
        if (adjusted.min.x > adjusted.max.x || adjusted.min.y > adjusted.max.y || adjusted.min.z > adjusted.max.z) {
            return new BoundingBox().inf();
        }
        float wallSpan = axis == 0 ? box.getHeight() : box.getWidth();
        float flyLength = new Vector3(WIDTH, 0, 0).rot(transform).len();
        if (axis != 2 && wallSpan >= flyLength * .25f) {
            // A mounting wall is one barrier for the connected sheet. Letting individual rows wrap onto
            // the coarse box's roof/back face creates incompatible paths and long triangles between them.
            BoundingBox reach = bounds(transform);
            if (axis != 0) { adjusted.min.x = Math.min(adjusted.min.x, reach.min.x); adjusted.max.x = Math.max(adjusted.max.x, reach.max.x); }
            if (axis != 1) { adjusted.min.y = Math.min(adjusted.min.y, reach.min.y); adjusted.max.y = Math.max(adjusted.max.y, reach.max.y); }
            adjusted.min.z = Math.min(adjusted.min.z, reach.min.z); adjusted.max.z = Math.max(adjusted.max.z, reach.max.z);
        }
        return adjusted.set(adjusted.min, adjusted.max);
    }

    private static boolean inside(Vector3 point, List<BoundingBox> obstacles) {
        for (BoundingBox obstacle : obstacles) { if (obstacle.contains(point)) { return true; } }
        return false;
    }

    /** Contacts must not pull neighboring rows apart into long, pointed triangles. Compress and fold as a connected sheet. */
    private static void relax(float[] vertices, float columnLength, float rowLength, ToDoubleFunction<Vector3> ground,
          float top, List<BoundingBox> obstacles, float ceiling) {
        float[] before = new float[FACE_VERTICES * 8];
        Vector3 start = new Vector3(), point = new Vector3();
        float diagonal = (float) Math.sqrt(columnLength * columnLength + rowLength * rowLength);
        // After the first pass a vertex no constraint moved is already valid; once none moves, later passes change nothing.
        for (int pass = 0; pass < 32; pass++) {
            System.arraycopy(vertices, 0, before, 0, before.length);
            for (int row = 0; row <= ROWS; row++) {
                for (int column = 0; column <= COLUMNS; column++) {
                    int a = row * (COLUMNS + 1) + column;
                    if (column < COLUMNS) { limit(vertices, a, a + 1, columnLength); }
                    if (row < ROWS) { limit(vertices, a, a + COLUMNS + 1, rowLength); }
                    if (row < ROWS && column < COLUMNS) {
                        limit(vertices, a, a + COLUMNS + 2, diagonal);
                        limit(vertices, a + 1, a + COLUMNS + 1, diagonal);
                    }
                }
            }
            boolean moved = false;
            for (int vertex = 0; vertex < FACE_VERTICES; vertex++) {
                if (vertex % (COLUMNS + 1) == 0) { continue; }
                int at = vertex * 8;
                if (pass > 0 && vertices[at] == before[at] && vertices[at + 1] == before[at + 1]
                      && vertices[at + 2] == before[at + 2]) { continue; }
                moved = true;
                start.set(before[at], before[at + 1], before[at + 2]);
                point.set(vertices[at], vertices[at + 1], vertices[at + 2]);
                if (point.z <= top) { point.z = Math.min(ceiling, Math.max(point.z, (float) ground.applyAsDouble(point))); }
                else { point.z = Math.min(ceiling, point.z); }
                for (BoundingBox obstacle : obstacles) { slide(start, point, obstacle, false); }
                if (inside(point, obstacles) || below(point, ground, top)) { point.set(start); }
                vertices[at] = point.x; vertices[at + 1] = point.y; vertices[at + 2] = point.z;
            }
            if (!moved) { break; }
        }
        // Finish from the fixed edge outward. Symmetric relaxation alone can leave strain concentrated
        // in the first column, where a wall pins the sheet's normal motion.
        for (int column = 1; column <= COLUMNS; column++) {
            for (int row = 0; row <= ROWS; row++) {
                int vertex = row * (COLUMNS + 1) + column, at = vertex * 8;
                start.set(vertices[at], vertices[at + 1], vertices[at + 2]);
                for (int pass = 0; pass < 12; pass++) {
                    cap(vertices, vertex, vertex - 1, columnLength * 1.05f);
                    if (row > 0) { cap(vertices, vertex, vertex - COLUMNS - 1, rowLength * 1.05f); }
                }
                point.set(vertices[at], vertices[at + 1], vertices[at + 2]);
                if (point.equals(start)) { continue; }
                if (inside(point, obstacles) || below(point, ground, top)) {
                    vertices[at] = start.x; vertices[at + 1] = start.y; vertices[at + 2] = start.z;
                }
            }
        }
        groundSurface(vertices, ground, top, obstacles, ceiling, point, start);
    }

    /** The highest ground {@code ground} can answer: a grid knows it, none has none, and any other may be anywhere. */
    private static float top(ToDoubleFunction<Vector3> ground) {
        return ground instanceof GroundGrid grid ? grid.top() : ground == NO_GROUND ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    }

    private static boolean below(Vector3 point, ToDoubleFunction<Vector3> ground, float top) {
        return point.z < top && point.z < ground.applyAsDouble(point);
    }

    private static void cap(float[] vertices, int vertex, int anchor, float length) {
        int a = anchor * 8, b = vertex * 8;
        float x = vertices[b] - vertices[a], y = vertices[b + 1] - vertices[a + 1], z = vertices[b + 2] - vertices[a + 2];
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        if (distance <= length) { return; }
        vertices[b] = vertices[a] + x * length / distance;
        vertices[b + 1] = vertices[a + 1] + y * length / distance;
        vertices[b + 2] = vertices[a + 2] + z * length / distance;
    }

    private static void limit(float[] vertices, int a, int b, float length) {
        boolean pinnedA = a % (COLUMNS + 1) == 0, pinnedB = b % (COLUMNS + 1) == 0;
        if (pinnedA && pinnedB) { return; }
        a *= 8; b *= 8;
        float x = vertices[b] - vertices[a], y = vertices[b + 1] - vertices[a + 1], z = vertices[b + 2] - vertices[a + 2];
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        if (distance <= length * 1.05f) { return; }
        float correction = (distance - length * 1.05f) / distance / (pinnedA || pinnedB ? 1 : 2);
        if (!pinnedA) { vertices[a] += x * correction; vertices[a + 1] += y * correction; vertices[a + 2] += z * correction; }
        if (!pinnedB) { vertices[b] -= x * correction; vertices[b + 1] -= y * correction; vertices[b + 2] -= z * correction; }
    }

    private static float component(Vector3 value, int axis) { return axis == 0 ? value.x : axis == 1 ? value.y : value.z; }

    /** Lift the segment clear of the installed ground; stop at steep steps rather than stretching onto their tops. */
    static void ground(Vector3 start, Vector3 end, ToDoubleFunction<Vector3> height, Vector3 probe) {
        ground(start, end, height, probe, top(height));
    }

    private static void ground(Vector3 start, Vector3 end, ToDoubleFunction<Vector3> height, Vector3 probe, float top) {
        float length2 = start.dst2(end), raised = end.z;
        float clearance = Math.min(.05f * BoardGeometry.hexScale(), (float) Math.sqrt(length2) * .1f);
        // Both ends above the highest ground: every sample's lift would stay under the segment.
        if (start.z >= top && end.z >= top + clearance) { return; }
        float dx = end.x - start.x, dy = end.y - start.y;
        int samples = Math.max(4, Math.min(256, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy) / (.5f * BoardGeometry.hexScale()))));
        for (int sample = 1; sample <= samples; sample++) {
            float t = (float) sample / samples;
            probe.set(start).lerp(end, t);
            float floor = (float) height.applyAsDouble(probe);
            // Ramp clearance from the pinned/contact end, rather than demanding a full gap infinitesimally after it.
            if (Float.isFinite(floor)) { raised = Math.max(raised, start.z + (floor - start.z) / t + clearance); }
        }
        if (raised <= end.z) { return; }
        end.z = raised;
        if (start.dst2(end) > length2 + .0001f) { end.set(start); }
    }

    /** The ground can also intersect between rows. Check cross-row edges, diagonals and triangle interiors. */
    private static boolean groundSurface(float[] vertices, ToDoubleFunction<Vector3> ground, float top,
          List<BoundingBox> obstacles, float ceiling, Vector3 probe, Vector3 start) {
        if (top == Float.NEGATIVE_INFINITY) { return false; }
        boolean changed = false;
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int a = row * (COLUMNS + 1) + column, b = a + COLUMNS + 1;
                // A quad wholly above the highest ground holds no sample under it.
                if (Math.min(Math.min(vertices[a * 8 + 2], vertices[(a + 1) * 8 + 2]),
                      Math.min(vertices[b * 8 + 2], vertices[(b + 1) * 8 + 2])) >= top) { continue; }
                changed |= groundTriangle(vertices, a, a + 1, b, ground, obstacles, ceiling, probe, start);
                changed |= groundTriangle(vertices, a + 1, b + 1, b, ground, obstacles, ceiling, probe, start);
            }
        }
        return changed;
    }

    private static boolean groundTriangle(float[] vertices, int a, int b, int c, ToDoubleFunction<Vector3> ground,
          List<BoundingBox> obstacles, float ceiling, Vector3 probe, Vector3 start) {
        boolean changed = false;
        for (int sample = 0; sample < 4; sample++) {
            float wa = sample == 3 ? 1f / 3 : sample == 0 ? 0 : .5f;
            float wb = sample == 3 ? 1f / 3 : sample == 1 ? 0 : .5f;
            float wc = sample == 3 ? 1f / 3 : sample == 2 ? 0 : .5f;
            probe.set(vertices[a * 8] * wa + vertices[b * 8] * wb + vertices[c * 8] * wc,
                  vertices[a * 8 + 1] * wa + vertices[b * 8 + 1] * wb + vertices[c * 8 + 1] * wc,
                  vertices[a * 8 + 2] * wa + vertices[b * 8 + 2] * wb + vertices[c * 8 + 2] * wc);
            float movable = (a % (COLUMNS + 1) == 0 ? 0 : wa) + (b % (COLUMNS + 1) == 0 ? 0 : wb)
                  + (c % (COLUMNS + 1) == 0 ? 0 : wc);
            if (movable == 0) { continue; }
            float lift = ((float) ground.applyAsDouble(probe) - probe.z) / movable;
            if (!(lift > 0)) { continue; }
            for (int vertex : new int[] { a, b, c }) {
                if (vertex % (COLUMNS + 1) == 0) { continue; }
                int at = vertex * 8;
                start.set(vertices[at], vertices[at + 1], vertices[at + 2]);
                probe.set(start); probe.z = Math.min(ceiling, start.z + lift + .001f * BoardGeometry.hexScale());
                for (BoundingBox obstacle : obstacles) { slide(start, probe, obstacle); }
                if (inside(probe, obstacles)) { probe.set(start); }
                // A ceiling can limit draping. Never lower an already valid ground contact into the terrain.
                vertices[at + 2] = Math.max(start.z, probe.z);
                changed |= vertices[at + 2] > start.z;
            }
        }
        return changed;
    }

    /** The grid for this pose and terrain ({@code scene}, a revision the caller bumps when either source changes). */
    GroundGrid ground(Matrix4 transform, int scene, ToDoubleFunction<Vector3> source) {
        if (groundGrid == null || groundScene != scene || !java.util.Arrays.equals(groundPose.val, transform.val)) {
            groundGrid = new GroundGrid(bounds(transform), source);
            groundScene = scene;
            groundPose.set(transform);
        }
        return groundGrid;
    }

    void animate(Matrix4 transform, float phase, Vector3 wind, boolean vacuum, List<BoundingBox> obstacles,
          ToDoubleFunction<Vector3> ground) {
        if (vacuum && settledGround == ground && obstacles.equals(settledObstacles)
              && java.util.Arrays.equals(settledPose.val, transform.val)) { return; }
        deform(vertices, transform, phase, wind, vacuum, obstacles, ground);
        settledGround = vacuum ? ground : null;
        settledObstacles = obstacles;
        settledPose.set(transform);
        if (java.util.Arrays.equals(vertices, previousVertices)) { return; }
        model.meshes.first().setVertices(vertices);
        model.meshParts.forEach(part -> part.update());
        System.arraycopy(vertices, 0, previousVertices, 0, vertices.length);
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
